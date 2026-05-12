(ns plurama.app.agent.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]))

(def ^:private opts {:builder-fn rs/as-unqualified-lower-maps})

(defn lookup-telegram-user
  "Resolve a Telegram numeric user id (as string) to a plurama user row.
  Returns {:user_id :name :display_name} or nil if unmapped."
  [conn telegram-user-id]
  (jdbc/execute-one! conn
    (sql/format {:select [:tu.user_id :u.name :tu.display_name]
                 :from [[:telegram_users :tu]]
                 :join [[:users :u] [:= :u.id :tu.user_id]]
                 :where [:= :tu.telegram_user_id telegram-user-id]})
    opts))

(defn lookup-credential
  "Return {:username :password} for the given user/app, or nil."
  [conn user-id app]
  (jdbc/execute-one! conn
    (sql/format {:select [:username :password]
                 :from [:user_app_credentials]
                 :where [:and
                         [:= :user_id user-id]
                         [:= :app app]]})
    opts))

(defn list-user-credentials
  "Return all per-app credentials for this user as a vector of
  {:app :username :password} maps."
  [conn user-id]
  (jdbc/execute! conn
    (sql/format {:select [:app :username :password]
                 :from [:user_app_credentials]
                 :where [:= :user_id user-id]})
    opts))

(defn next-turn-id
  "Return the next turn id for this user (max + 1)."
  [conn user-id]
  (-> (jdbc/execute-one! conn
        (sql/format {:select [[[:coalesce [[:max :turn_id]] 0] :max_turn]]
                     :from [:agent_messages]
                     :where [:= :user_id user-id]})
        opts)
      :max_turn
      inc))

(defn add-message!
  "Persist one message in the conversation."
  [conn user-id turn-id role content-json]
  (jdbc/execute-one! conn
    (sql/format {:insert-into :agent_messages
                 :values [{:user_id user-id
                           :turn_id turn-id
                           :role role
                           :content content-json}]})))

(defn recent-messages
  "Return the rows from the last `n` distinct turns for this user, in
  insertion order. Each row is {:role :content}."
  [conn user-id n]
  (jdbc/execute! conn
    (sql/format {:select [:role :content]
                 :from [:agent_messages]
                 :where [:and
                         [:= :user_id user-id]
                         [:in :turn_id
                          {:select [:turn_id]
                           :from [{:select-distinct [:turn_id]
                                   :from [:agent_messages]
                                   :where [:= :user_id user-id]
                                   :order-by [[:turn_id :desc]]
                                   :limit n}]}]]
                 :order-by [[:id :asc]]})
    opts))

(defn clear-history!
  "Delete every stored message for this user."
  [conn user-id]
  (jdbc/execute-one! conn
    (sql/format {:delete-from :agent_messages
                 :where [:= :user_id user-id]})))

(defn prune-turns!
  "Delete all but the most recent `keep` turns for this user."
  [conn user-id keep]
  (jdbc/execute-one! conn
    (sql/format {:delete-from :agent_messages
                 :where [:and
                         [:= :user_id user-id]
                         [:not-in :turn_id
                          {:select [:turn_id]
                           :from [{:select-distinct [:turn_id]
                                   :from [:agent_messages]
                                   :where [:= :user_id user-id]
                                   :order-by [[:turn_id :desc]]
                                   :limit keep}]}]]})))
