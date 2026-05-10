(ns plurama.app.mail.imap
  (:import [javax.mail Session Folder Store Multipart Part Message Flags$Flag]
           [javax.mail.search FlagTerm]))

(defn- extract-text [^Part part]
  (let [content-type (.getContentType part)]
    (cond
      (.startsWith content-type "text/plain")
      (.getContent part)

      (.startsWith content-type "multipart/")
      (let [mp ^Multipart (.getContent part)
            cnt (.getCount mp)]
        (first (keep #(extract-text (.getBodyPart mp %)) (range cnt))))

      :else nil)))

(defn connect [host user password]
  (let [props (doto (java.util.Properties.)
                (.put "mail.store.protocol" "imaps"))
        session (Session/getInstance props)
        store (.getStore session "imaps")]
    (.connect store host user password)
    store))

(defn open-folder [store folder-name]
  (let [folder (.getFolder ^Store store folder-name)]
    (.open folder Folder/READ_WRITE)
    folder))

(defn get-unread-emails [folder]
  (let [unseen (FlagTerm. (javax.mail.Flags. Flags$Flag/SEEN) false)
        messages (.search folder unseen)]
    (mapv (fn [msg]
            {:message-id (.getMessageID msg)
             :subject (.getSubject msg)
             :from (some-> (.getFrom msg) first str)
             :sent-date (.getSentDate msg)
             :body (extract-text msg)
             :msg msg})
          messages)))

(defn ensure-folder! [store folder-name]
  (let [folder (.getFolder ^Store store folder-name)]
    (when-not (.exists folder)
      (.create folder Folder/HOLDS_MESSAGES))
    folder))

(defn archive! [^Message msg ^Store store archive-folder-name]
  (let [source-folder (.getFolder msg)
        dest-folder (ensure-folder! store archive-folder-name)]
    (when-not (.isOpen dest-folder)
      (.open dest-folder Folder/READ_WRITE))
    (try
      (.copyMessages source-folder (into-array Message [msg]) dest-folder)
      (.setFlag msg Flags$Flag/DELETED true)
      (finally
        (.close dest-folder)))))

(defn close-folder [folder]
  (.close folder true))

(defn disconnect [store]
  (.close ^Store store))
