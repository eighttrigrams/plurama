(ns plurama.app.users
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]))

(def ^:private opts {:builder-fn rs/as-unqualified-lower-maps})

(defn list-users [{:keys [conn]}]
  (jdbc/execute! conn
                 (sql/format {:select [:u.id :u.name :u.created_at
                                       [[:count :c.id] :credential_count]]
                              :from [[:users :u]]
                              :left-join [[:user_app_credentials :c] [:= :c.user_id :u.id]]
                              :group-by [:u.id]
                              :order-by [[:u.name :asc]]})
                 opts))

(defn get-user [{:keys [conn]} id]
  (jdbc/execute-one! conn
                     (sql/format {:select [:id :name :password :created_at]
                                  :from [:users]
                                  :where [:= :id id]})
                     opts))

(defn find-user-by-name [{:keys [conn]} name]
  (jdbc/execute-one! conn
                     (sql/format {:select [:id :name :password :created_at]
                                  :from [:users]
                                  :where [:= :name name]})
                     opts))

(defn set-password! [{:keys [conn]} id password]
  (jdbc/execute-one! conn
                     (sql/format {:update :users
                                  :set {:password password
                                        :updated_at [:raw "CURRENT_TIMESTAMP"]}
                                  :where [:= :id id]})
                     opts))

(defn create-user! [{:keys [conn]} name]
  (jdbc/execute-one! conn
                     (sql/format {:insert-into :users
                                  :values [{:name name}]})
                     opts))

(defn delete-user! [{:keys [conn]} id]
  (jdbc/execute-one! conn
                     (sql/format {:delete-from :users
                                  :where [:= :id id]})
                     opts))

(defn list-credentials [{:keys [conn]} user-id]
  (jdbc/execute! conn
                 (sql/format {:select [:id :app :username :password :created_at]
                              :from [:user_app_credentials]
                              :where [:= :user_id user-id]
                              :order-by [[:app :asc]]})
                 opts))

(defn create-credential! [{:keys [conn]} user-id app username password]
  (jdbc/execute-one! conn
                     (sql/format {:insert-into :user_app_credentials
                                  :values [{:user_id user-id
                                            :app app
                                            :username username
                                            :password password}]})
                     opts))

(defn delete-credential! [{:keys [conn]} cred-id]
  (jdbc/execute-one! conn
                     (sql/format {:delete-from :user_app_credentials
                                  :where [:= :id cred-id]})
                     opts))

(defn get-telegram-link [{:keys [conn]} user-id]
  (jdbc/execute-one! conn
                     (sql/format {:select [:telegram_user_id :display_name :created_at]
                                  :from [:telegram_users]
                                  :where [:= :user_id user-id]})
                     opts))

(defn set-telegram-link!
  "Replace any existing telegram link for this plurama user. Throws if
  the given telegram-user-id is already linked to a *different* plurama
  user (PK conflict)."
  [{:keys [conn]} user-id telegram-user-id display-name]
  (jdbc/execute-one! conn
                     (sql/format {:delete-from :telegram_users
                                  :where [:= :user_id user-id]})
                     opts)
  (jdbc/execute-one! conn
                     (sql/format {:insert-into :telegram_users
                                  :values [{:telegram_user_id telegram-user-id
                                            :user_id user-id
                                            :display_name display-name}]})
                     opts))

(defn delete-telegram-link! [{:keys [conn]} user-id]
  (jdbc/execute-one! conn
                     (sql/format {:delete-from :telegram_users
                                  :where [:= :user_id user-id]})
                     opts))
