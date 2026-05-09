(ns plurama.app.migrations
  (:require [ragtime.next-jdbc :as ragtime-jdbc]
            [ragtime.repl :as repl]
            [next.jdbc :as jdbc]))

(defn- wrap-connectable [conn-or-ds]
  (if (instance? java.sql.Connection conn-or-ds)
    (jdbc/with-options conn-or-ds {})
    conn-or-ds))

(defn- migration-config [connectable]
  {:datastore (ragtime-jdbc/sql-database (wrap-connectable connectable))
   :migrations (ragtime-jdbc/load-resources "migrations/net/et/plurama")})

(defn migrate! [connectable]
  (println "[plurama] running database migrations...")
  (repl/migrate (migration-config connectable))
  (println "[plurama] migrations completed"))

(defn rollback! [connectable]
  (repl/rollback (migration-config connectable)))
