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

(defn- send-telegram-message [bot-token chat-id text]
  (when bot-token
    (try
      (println "Telegram out [" chat-id "]:" text)
      (let [body {:chat_id chat-id :text text}
            request (-> (HttpRequest/newBuilder)
                        (.uri (URI/create (str "https://api.telegram.org/bot" bot-token "/sendMessage")))
                        (.timeout (Duration/ofSeconds 30))
                        (.header "Content-Type" "application/json")
                        (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                        (.build))]
        (.send client request (HttpResponse$BodyHandlers/ofString)))
      (catch Exception e
        (println "Failed to send Telegram message:" (.getMessage e))))))

(defn- prefix-match
  "Inspect raw Telegram text and decide whether to bypass the AI agent.
  Returns one of:
    {:kind :task :forward text}     — `t<ws>` or `tt<ws>` (case-insensitive).
                                       Forward verbatim so tracker recognises
                                       its own prefix and creates the task.
    {:kind :note :forward stripped} — `n<ws>` (case-insensitive). Strip the
                                       prefix; tracker doesn't act on it, so
                                       the rest lands as a plain inbox message.
    nil                             — no prefix; the AI agent handles it."
  [text]
  (when (string? text)
    (or (when (re-matches #"(?si)tt?\s+.+" text)
          {:kind :task :forward text})
        (when-let [[_ body] (re-matches #"(?si)n\s+(.+)" text)]
          {:kind :note :forward (str/trim body)}))))

(defn- forward-to-tracker!
  "POST `text` as a fresh message into the user's tracker inbox.
  `tracker-ctx` carries the tracker-direct credentials — a separate
  tracker user (with message-only permissions) that the AI agent never
  sees. Returns the HTTP response map."
  [tracker-ctx text]
  (app-client/request
    tracker-ctx "POST" "/api/messages"
    {:sender "Telegram"
     :title text}))

(defn- handle-prefix!
  "Run the prefix shortcut against tracker and reply over Telegram.
  `tracker-ctx` is the per-user {:base-url :username :password} map."
  [{:keys [bot-token]} chat-id tracker-ctx {:keys [kind forward]}]
  (try
    (let [{:keys [status]} (forward-to-tracker! tracker-ctx forward)]
      (if (<= 200 status 299)
        (send-telegram-message
          bot-token chat-id
          (case kind
            :task "Forwarded to tracker."
            :note "Saved to inbox."))
        (send-telegram-message
          bot-token chat-id
          (str "Tracker rejected the forward (" status ")."))))
    (catch Exception e
      (println "Failed to forward prefixed message to tracker:" (.getMessage e))
      (send-telegram-message
        bot-token chat-id
        "Failed to reach tracker."))))

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

(defn- handle-update
  [{:keys [conn anthropic-key bot-token agent-apps system-prompt] :as agent-ctx}
   from-id chat-id text]
  (if-let [{:keys [user_id]} (db/lookup-telegram-user conn from-id)]
    ;; tracker-direct is the message-only tracker user used for the
    ;; T/TT/N prefix shortcuts; it must not appear in the AI's tool
    ;; surface, so we strip it from the map handed to ai/chat.
    (let [app-ctxs    (build-app-ctxs conn user_id agent-apps)
          direct-ctx  (get app-ctxs "tracker-direct")
          ai-app-ctxs (dissoc app-ctxs "tracker-direct")
          shortcut    (prefix-match text)]
      (cond
        (and shortcut direct-ctx)
        (handle-prefix! agent-ctx chat-id direct-ctx shortcut)

        (and shortcut (not direct-ctx))
        (send-telegram-message
          bot-token chat-id
          "No tracker-direct credentials configured for your account.")

        (seq ai-app-ctxs)
        (let [on-tool-call (fn [tool-name input result]
                             (send-telegram-message
                               bot-token chat-id
                               (str "[tool] " tool-name " " (json/write-str input)
                                    " = " result)))
              reply-text (ai/chat
                           {:conn conn
                            :user-id user_id
                            :anthropic-key anthropic-key
                            :app-ctxs ai-app-ctxs
                            :system-prompt system-prompt
                            :on-tool-call on-tool-call}
                           text)]
          (send-telegram-message bot-token chat-id reply-text))

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
    :system-prompt  precomputed system prompt incl. all app skills

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
