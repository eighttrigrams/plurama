(ns plurama.app.mail.poller
  "Per-user IMAP -> tracker mail forwarder.

   For each entry in mail.yaml's :user-configs, looks up the matching
   plurama user and their tracker credential. If both are present, polls
   the configured inboxes, forwards each unread message to tracker via
   POST /api/messages, and on success archives the message in IMAP."
  (:require [clojure.data.json :as json]
            [plurama.app.agent.app-client :as app-client]
            [plurama.app.mail.config :as mail-config]
            [plurama.app.mail.imap :as imap]
            [plurama.app.users :as users]))

(def ^:private folders-to-check ["INBOX" "Spam"])
(def ^:private archive-folder "Archive")
(def ^:private heartbeat-ms 20000)

(defn- forward-to-tracker [tracker-ctx inbox-name {:keys [subject from body]}]
  (let [{:keys [status body]} (app-client/request
                                tracker-ctx "POST" "/api/messages"
                                {:sender inbox-name
                                 :title (or subject "(no subject)")
                                 :description (str "From: " from "\n\n" body)})]
    (when-not (<= 200 status 299)
      (throw (ex-info (str "Tracker returned " status)
                      {:status status :body body})))
    (try (json/read-str body :key-fn keyword)
         (catch Exception _ {:status status}))))

(defn- poll-folder [store inbox-name folder-name tracker-ctx]
  (let [folder (imap/open-folder store folder-name)]
    (try
      (doseq [{:keys [msg] :as email} (imap/get-unread-emails folder)]
        (try
          (forward-to-tracker tracker-ctx inbox-name email)
          (imap/archive! msg store archive-folder)
          (catch Exception e
            (println "  forward failed for" inbox-name "/" folder-name
                     "-" (.getName (class e)) ":" (.getMessage e)
                     (when-let [d (ex-data e)] (str " data=" d)))
            (.printStackTrace e))))
      (finally
        (imap/close-folder folder)))))

(defn- poll-inbox [{:keys [name host user password]} tracker-ctx]
  (let [store (imap/connect host user password)]
    (try
      (doseq [folder-name folders-to-check]
        (try
          (poll-folder store name folder-name tracker-ctx)
          (catch Exception e
            (println "  Error checking folder" folder-name "for" name
                     "-" (.getMessage e)))))
      (finally
        (imap/disconnect store)))))

(defn- poll-user-config [conn tracker-base-url {:keys [user inboxes]}]
  (let [parts {:conn conn}
        plurama-user (users/find-user-by-name parts user)]
    (cond
      (not plurama-user)
      (println "Skipping mail for" user "- no plurama user")

      :else
      (let [cred (users/find-credential parts (:id plurama-user) "tracker")]
        (cond
          (not cred)
          (println "Skipping mail for" user "- no tracker credential")

          :else
          (let [tracker-ctx {:base-url tracker-base-url
                             :username (:username cred)
                             :password (:password cred)}]
            (doseq [inbox inboxes]
              (try
                (poll-inbox inbox tracker-ctx)
                (catch Exception e
                  (println "Error polling" (:name inbox) "for" user
                           "-" (.getMessage e)))))))))))

(defn- run-once [conn {:keys [tracker-base-url config-path]}]
  (when-let [{:keys [user-configs]} (mail-config/load-mail-config config-path)]
    (println "Polling mail for" (count user-configs) "user-config(s)...")
    (doseq [uc user-configs]
      (try
        (poll-user-config conn tracker-base-url uc)
        (catch Exception e
          (println "Error polling user-config" (:user uc)
                   "-" (.getMessage e)))))
    (println "Finished mail poll.")))

(defn- schedule-minutes [every-minutes start-minute]
  (let [offset (mod start-minute every-minutes)]
    (set (take-while #(< % 60) (iterate #(+ % every-minutes) offset)))))

(defn- run-loop [conn {:keys [poll] :as mail-config}]
  (let [{:keys [every-minutes start-minute]} poll
        minutes (schedule-minutes every-minutes start-minute)]
    (println "[mail] poller starting; minutes:" (sort minutes))
    (loop [last-minute nil]
      (let [current (try (.getMinute (java.time.LocalDateTime/now))
                         (catch Throwable t
                           (println "[mail] clock error:" (.getMessage t))
                           -1))]
        (when (and (contains? minutes current) (not= last-minute current))
          (try (run-once conn mail-config)
               (catch Throwable t
                 (println "[mail] poll iteration crashed:" (.getMessage t)))))
        (try (Thread/sleep heartbeat-ms)
             (catch InterruptedException _ nil))
        (recur (if (contains? minutes current) current last-minute))))))

(defn start!
  "Start the mail poller in a daemon thread. Returns the Thread."
  [conn mail-config]
  (let [t (Thread. ^Runnable #(run-loop conn mail-config) "plurama-mail-poller")]
    (.setDaemon t true)
    (.start t)
    t))
