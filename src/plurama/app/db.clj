(ns plurama.app.db
  (:require [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [plurama.app.migrations :as migrations]))

(defn- ensure-parent-dir [path]
  (when-let [parent (.getParentFile (io/file path))]
    (.mkdirs parent)))

(defn init-conn [{:keys [type path] :as opts}]
  (when (and (= type :sqlite-on-disk) (not path))
    (throw (ex-info "Missing :path in plurama :db config for :sqlite-on-disk" {:opts opts})))
  (when (= type :sqlite-on-disk) (ensure-parent-dir path))
  (let [db-spec (case type
                  :sqlite-in-memory {:dbtype "sqlite" :dbname "file::memory:?cache=shared"}
                  :sqlite-on-disk  {:dbtype "sqlite" :dbname path})
        ds (jdbc/get-datasource db-spec)
        persistent (when (= type :sqlite-in-memory) (jdbc/get-connection ds))
        conn (or persistent ds)]
    (migrations/migrate! conn)
    {:conn conn :persistent persistent :type type}))
