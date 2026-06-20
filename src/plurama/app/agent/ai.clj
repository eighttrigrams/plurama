(ns plurama.app.agent.ai
  "Conversation loop. Takes a user's text message, persists turns,
  calls Anthropic with the per-user tools, runs tool calls, and returns
  the final assistant text. Multi-app: which downstream apps the agent
  can talk to is determined per-user from the intersection of
  configured apps × the user's per-app credentials."
  (:require [plurama.app.agent.db :as db]
            [plurama.app.agent.tools :as tools]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration ZonedDateTime]
           [java.time.format DateTimeFormatter]))

(def ^:private client (HttpClient/newHttpClient))
(def ^:private model "claude-haiku-4-5-20251001")
(def ^:private context-turns 5)
(def ^:private max-tool-iterations 5)

(def ^:private base-system
  (str "You are a friendly assistant in a personal Telegram chat. Reply briefly. "
       "You can use the app_request tool to read and write data on any of the user's "
       "configured apps. Pick the right `app` for each request based on the per-app "
       "guidance below. When the user asks about their tasks, today board, or wants "
       "to add/update entries, use the tool — do not make up data."))

(defn- now-context []
  (let [now (ZonedDateTime/now)
        cal-fmt (DateTimeFormatter/ofPattern "EEEE yyyy-MM-dd")
        calendar (->> (range 0 8)
                      (map (fn [d] (str "  " (.format (.plusDays now d) cal-fmt))))
                      (str/join "\n"))]
    (str "## Current date and time\n"
         (.format now (DateTimeFormatter/ofPattern "EEEE, yyyy-MM-dd HH:mm zzz"))
         "\nTreat this as \"now\" for any time-relative question (today, "
         "overdue, due soon, the next N hours, this week).\n"
         "Resolve weekday names against this calendar — do not compute dates "
         "yourself:\n"
         calendar)))

(defn build-system-prompt
  "Concatenate the base system prompt with one section per available app.
  `app-skills` is a vector of {:app :skill-md} maps; `:skill-md` may be
  nil (the heading is shown but no body, in case the skill resource is
  missing)."
  [app-skills]
  (let [parts (cons base-system
                    (for [{:keys [app skill-md]} app-skills
                          :let [trimmed (when skill-md (str/trim skill-md))]
                          :when (seq trimmed)]
                      (str "## App: " app "\n\n" trimmed)))]
    (str/join "\n\n" parts)))

(defn- post-messages [api-key body]
  (let [request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create "https://api.anthropic.com/v1/messages"))
                    (.timeout (Duration/ofSeconds 120))
                    (.header "Content-Type" "application/json")
                    (.header "x-api-key" api-key)
                    (.header "anthropic-version" "2023-06-01")
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))
        status (.statusCode response)
        body-str (.body response)]
    (when-not (<= 200 status 299)
      (throw (ex-info (str "Anthropic API " status ": " body-str)
                      {:status status :body body-str})))
    (json/read-str body-str :key-fn keyword)))

(defn- db-row->msg [{:keys [role content]}]
  {:role role
   :content (json/read-str content :key-fn keyword)})

(defn- extract-text [content-blocks]
  (->> content-blocks
       (filter #(= "text" (:type %)))
       (map :text)
       (apply str)))

(defn- tool-uses [content-blocks]
  (filter #(= "tool_use" (:type %)) content-blocks))

(defn- run-tool-use [app-ctxs on-tool-call {:keys [id name input]}]
  (try
    (let [result (tools/run app-ctxs {:name name :input input})]
      (println "Tool invoked:" name "input:" input "result:" result)
      (when on-tool-call (on-tool-call name input result))
      {:type "tool_result"
       :tool_use_id id
       :content result})
    (catch Exception e
      (println "Tool error:" name "-" (.getMessage e))
      {:type "tool_result"
       :tool_use_id id
       :is_error true
       :content (str "Error: " (.getMessage e))})))

(defn chat
  "Run one turn of the conversation. `ctx` carries everything the loop
  needs:
    :conn          plurama jdbc connection
    :user-id       plurama user (conversation owner)
    :anthropic-key Anthropic API key
    :app-ctxs      map app-name → {:base-url :username :password}
                   for the user's available apps
    :system-prompt full system prompt string (precomputed)
    :on-tool-call  optional (fn [tool-name input result]) callback

  Returns the assistant's final text, or a fallback string if the tool
  iteration limit is reached."
  [{:keys [conn user-id anthropic-key app-ctxs system-prompt on-tool-call]}
   user-text]
  (let [tool-specs (tools/build-tool-specs (sort (keys app-ctxs)))
        turn-id (db/next-turn-id conn user-id)
        history (mapv db-row->msg (db/recent-messages conn user-id context-turns))
        user-msg {:role "user" :content user-text}
        system (str system-prompt "\n\n" (now-context))]
    (db/add-message! conn user-id turn-id "user" (json/write-str user-text))
    (loop [msgs (conj history user-msg)
           iter 0]
      (if (>= iter max-tool-iterations)
        (do (db/prune-turns! conn user-id context-turns)
            "(tool-use iteration limit reached)")
        (let [_ (println "LLM request iter" iter
                         "msg-shapes:"
                         (mapv (fn [m]
                                 {:role (:role m)
                                  :content-type (cond
                                                  (string? (:content m)) :string
                                                  (vector? (:content m)) :vector
                                                  (sequential? (:content m)) :seq
                                                  :else (type (:content m)))
                                  :block-types (when (sequential? (:content m))
                                                 (mapv :type (:content m)))})
                               msgs))
              response (post-messages anthropic-key
                                      {:model model
                                       :max_tokens 1024
                                       :system system
                                       :tools tool-specs
                                       :messages msgs})
              content (:content response)]
          (println "LLM response iter" iter
                   "stop_reason:" (:stop_reason response)
                   "block-types:" (mapv :type content)
                   "raw:" (json/write-str response))
          (db/add-message! conn user-id turn-id "assistant" (json/write-str content))
          (if (= "tool_use" (:stop_reason response))
            (let [result-blocks (mapv (partial run-tool-use app-ctxs on-tool-call)
                                      (tool-uses content))
                  tool-user-msg {:role "user" :content result-blocks}]
              (db/add-message! conn user-id turn-id "user" (json/write-str result-blocks))
              (recur (conj msgs
                           {:role "assistant" :content content}
                           tool-user-msg)
                     (inc iter)))
            (do (db/prune-turns! conn user-id context-turns)
                (let [text (extract-text content)]
                  (println "LLM final reply length:" (count text))
                  (if (str/blank? text)
                    (str "(empty LLM reply, stop_reason="
                         (:stop_reason response) ")")
                    text)))))))))
