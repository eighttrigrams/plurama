(ns plurama.app.agent.telegram
  (:require [plurama.app.agent.ai :as ai]
            [plurama.app.agent.app-client :as app-client]
            [plurama.app.agent.db :as db]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private client (HttpClient/newHttpClient))

(defn- to-telegram-markdown
  "Convert the AI's CommonMark-flavoured output into Telegram's legacy
  `Markdown` parse_mode. The main mismatch is bold: AI emits `**x**`,
  Telegram expects `*x*`."
  [text]
  (str/replace text #"\*\*([^*\n]+)\*\*" "*$1*"))

(defn- post-send-message [bot-token payload]
  (let [request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str "https://api.telegram.org/bot" bot-token "/sendMessage")))
                    (.timeout (Duration/ofSeconds 30))
                    (.header "Content-Type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-str payload)))
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body   (.body response)}))

(defn- send-telegram-message [bot-token chat-id text]
  (when bot-token
    (try
      (println "Telegram out [" chat-id "]:" text)
      (let [md-text (to-telegram-markdown text)
            {:keys [status body]} (post-send-message
                                    bot-token
                                    {:chat_id chat-id
                                     :text md-text
                                     :parse_mode "Markdown"})]
        (if (<= 200 status 299)
          {:status status :body body}
          (do (println "Telegram rejected Markdown send (" status "):" body
                       "— retrying as plain text")
              (post-send-message bot-token {:chat_id chat-id :text text}))))
      (catch Exception e
        (println "Failed to send Telegram message:" (.getMessage e))))))

(defn- prefix-match
  "Inspect raw Telegram text and decide whether to bypass the AI agent.
  Returns one of:
    {:kind :task :forward text}     — `t<ws>` or `tt<ws>` (case-insensitive).
                                       Forward verbatim so tracker recognises
                                       its own prefix and creates the task.
    {:kind :note :forward stripped} — `n<ws>`, `np<ws>` or `nw<ws>`
                                       (case-insensitive). Strip the prefix; the
                                       rest lands as a plain inbox message. `np`
                                       scopes it private, `nw` work, plain `n`
                                       leaves it unscoped.
    {:kind :blog-note :forward stripped}
                                    — `b<ws>` (case-insensitive). Strip the
                                       prefix; the rest becomes a Note in blog's
                                       Notes box. A different thing from :note
                                       above, which is a tracker inbox message.
    nil                             — no prefix; the AI agent handles it."
  [text]
  (when (string? text)
    (or (when (re-matches #"(?si)tt?\s+.+" text)
          {:kind :task :forward text})
        (when-let [[_ scope-char body] (re-matches #"(?si)n([pw]?)\s+(.+)" text)]
          {:kind :note
           :scope (case (str/lower-case scope-char) "p" "private" "w" "work" nil)
           :forward (str/trim body)})
        (when-let [[_ body] (re-matches #"(?si)b\s+(.+)" text)]
          {:kind :blog-note :forward (str/trim body)}))))

(defn- forward-to-tracker!
  "POST `text` as a fresh message into the user's tracker inbox.
  `tracker-ctx` carries the tracker-direct credentials — a separate
  tracker user (with message-only permissions) that the AI agent never
  sees. Returns the HTTP response map."
  ([tracker-ctx text] (forward-to-tracker! tracker-ctx text nil))
  ([tracker-ctx text scope]
   (app-client/request
     tracker-ctx "POST" "/api/messages"
     (cond-> {:sender "Telegram"
              :title text}
       (#{"private" "work"} scope) (assoc :scope scope)))))

(defn- forward-to-blog-notes!
  "POST `text` as a Note into blog's Notes box. `blog-ctx` carries the
  blog notes-user credentials — a credential that may deliver a Note and
  do nothing else. A Note is one text field, so a multi-line `b` message
  goes over whole rather than being split into a title and a body.
  Returns the HTTP response map."
  [blog-ctx text]
  (app-client/request
    blog-ctx "POST" "/api/notes"
    {:text text :source "telegram"}))

(def ^:private prefix-targets
  "Per prefix kind: the configured app that delivers it, and how to name that app
  to the user. `:app` is the key both the config and the user's credential rows
  use, so a missing-credential message can say which of the two it means."
  {:task      {:app "tracker-direct" :label "Tracker"}
   :note      {:app "tracker-direct" :label "Tracker"}
   :blog-note {:app "blog-notes"     :label "Blog"}})

(defn- handle-prefix!
  "Run the prefix shortcut against the app that owns its kind and reply over
  Telegram. `app-ctx` is the per-user {:base-url :username :password} map for
  that app."
  [{:keys [bot-token]} chat-id app-ctx {:keys [kind forward scope]}]
  (let [{:keys [label]} (prefix-targets kind)]
    (try
      (let [{:keys [status]} (if (= :blog-note kind)
                               (forward-to-blog-notes! app-ctx forward)
                               (forward-to-tracker! app-ctx forward scope))]
        (if (<= 200 status 299)
          (send-telegram-message
            bot-token chat-id
            (case kind
              :task "Forwarded to tracker."
              :note (case scope
                      "private" "Saved to inbox (private)."
                      "work" "Saved to inbox (work)."
                      "Saved to inbox.")
              :blog-note "Saved to blog's Notes box."))
          (send-telegram-message
            bot-token chat-id
            (str label " rejected the forward (" status ")."))))
      (catch Exception e
        (println "Failed to forward prefixed message to" label ":" (.getMessage e))
        (send-telegram-message
          bot-token chat-id
          (str "Failed to reach " (str/lower-case label) "."))))))

(defn- build-app-ctxs
  "Intersect the configured agent apps with the user's per-app
  credentials. Returns a map app-name (string) → {:base-url :username
  :password}, or {} if the user has no usable credentials."
  [conn user-id agent-apps]
  (let [creds-by-app (->> (db/list-user-credentials conn user-id)
                          (map (juxt :app identity))
                          (into {}))]
    (reduce-kv
      (fn [acc app-key {:keys [base-url]}]
        (let [app-name (name app-key)]
          (if-let [{:keys [username password]} (get creds-by-app app-name)]
            (assoc acc app-name {:base-url base-url
                                 :username username
                                 :password password})
            acc)))
      {}
      agent-apps)))

(defn ai-app-ctxs
  "The subset of `app-ctxs` the AI agent may reach as tools. `ai/chat` builds the
  model's tool surface from the keys of what it is handed, so an app in this map
  is an app the model may call on its own judgment.

  An app is only here when its config says `:ai-visible? true`. Default-invisible
  is the point: omit the flag on a future app and the AI cannot reach it, which is
  a visible failure fixed in one line — the reverse default would make a
  prefix-only credential silently callable by the model."
  [agent-apps app-ctxs]
  (select-keys app-ctxs
               (->> agent-apps
                    (keep (fn [[app-key {:keys [ai-visible?]}]]
                            (when (true? ai-visible?) (name app-key))))
                    set)))

(defn- handle-update
  [{:keys [conn anthropic-key bot-token agent-apps system-prompt] :as agent-ctx}
   from-id chat-id text]
  (if-let [{:keys [user_id]} (db/lookup-telegram-user conn from-id)]
    ;; The prefix apps are deliberately weak credentials used by the T/TT/N/B
    ;; shortcuts; they must not appear in the AI's tool surface, which the
    ;; :ai-visible? flag on each configured app now decides.
    (let [app-ctxs    (build-app-ctxs conn user_id agent-apps)
          ai-ctxs     (ai-app-ctxs agent-apps app-ctxs)
          shortcut    (prefix-match text)
          target      (prefix-targets (:kind shortcut))
          prefix-ctx  (get app-ctxs (:app target))]
      (cond
        (= "/clear" (some-> text str/trim str/lower-case))
        (do (db/clear-history! conn user_id)
            (send-telegram-message bot-token chat-id "Conversation history cleared."))

        (and shortcut prefix-ctx)
        (handle-prefix! agent-ctx chat-id prefix-ctx shortcut)

        (and shortcut (not prefix-ctx))
        (send-telegram-message
          bot-token chat-id
          (str "No " (:app target) " credentials configured for your account."))

        (seq ai-ctxs)
        (try
          (let [reply-text (ai/chat
                             {:conn conn
                              :user-id user_id
                              :anthropic-key anthropic-key
                              :app-ctxs ai-ctxs
                              :system-prompt system-prompt}
                             text)]
            (send-telegram-message bot-token chat-id reply-text))
          (catch Exception e
            (println "ai/chat failed:" (.getMessage e))
            (send-telegram-message
              bot-token chat-id
              (str "Internal error: " (.getMessage e)))))

        :else
        (do (println "No usable app credentials for plurama user" user_id)
            (send-telegram-message
              bot-token chat-id
              "I don't have any app credentials configured for your account yet."))))
    (do (println "Unmapped Telegram user:" from-id)
        ;; Stay silent for unmapped users to avoid leaking the bot's existence.
        nil)))

(defn webhook-handler
  "Ring handler for POST /webhook/telegram. `agent-ctx` carries the
  shared state:
    :conn           plurama jdbc connection
    :webhook-secret expected x-telegram-bot-api-secret-token
    :anthropic-key  Anthropic API key
    :bot-token      Telegram bot token (for outbound replies)
    :agent-apps     map app-key → {:base-url ...} of configured apps
    :system-prompt  the agent's standing instructions

  Behaviour:
   - 503 if no webhook-secret configured
   - 403 on bad/missing x-telegram-bot-api-secret-token header
   - 200 {:ok true} otherwise (Telegram retries on non-2xx, so we
     always return 200 once auth passes; failures are logged and the
     user is told via Telegram)"
  [agent-ctx]
  (fn [req]
    (let [{:keys [webhook-secret]} agent-ctx
          provided-secret (get-in req [:headers "x-telegram-bot-api-secret-token"])]
      (cond
        (nil? webhook-secret)
        (do (println "No Telegram webhook secret defined")
            {:status 503 :body {:error "Webhook not configured"}})

        (not= webhook-secret provided-secret)
        (do (println "Unauthorized Telegram webhook access attempt")
            {:status 403 :body {:error "Unauthorized"}})

        :else
        (let [update  (:body req)
              message (or (:message update) (:edited_message update))
              text    (:text message)
              chat-id (get-in message [:chat :id])
              from-id (some-> (get-in message [:from :id]) str)]
          (when (and from-id text chat-id)
            (println "Telegram in [" chat-id "] from" from-id ":" text)
            (try
              (handle-update agent-ctx from-id chat-id text)
              (catch Exception e
                (println "Error handling Telegram message:" (.getMessage e)))))
          {:status 200 :body {:ok true}})))))
