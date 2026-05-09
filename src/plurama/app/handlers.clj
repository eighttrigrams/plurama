(ns plurama.app.handlers
  (:require [clojure.string :as str]
            [plurama.app.auth :as auth]
            [plurama.app.users :as users]
            [plurama.app.views :as views]))

(defn- html [status body]
  {:status status
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body body})

(defn- redirect [url]
  {:status 302 :headers {"Location" url}})

(defn require-login [handler]
  (fn [req]
    (if (auth/logged-in? req)
      (handler req)
      (redirect "/login"))))

(defn landing [{:keys [umbrella]}]
  (fn [req]
    (html 200 (views/landing-page {:umbrella umbrella
                                   :logged-in? (auth/logged-in? req)}))))

(defn login-page [_req]
  (html 200 (views/login-page {})))

(defn login-submit [req]
  (let [pw (get-in req [:form-params "password"])]
    (if (and pw (= pw (auth/admin-password)))
      {:status 302
       :headers {"Location" "/admin/users"
                 "Set-Cookie" (str "token=" (auth/create-token)
                                   "; Path=/; HttpOnly; SameSite=Strict")}}
      (html 401 (views/login-page {:error "Wrong password"})))))

(defn logout [_req]
  {:status 302
   :headers {"Location" "/"
             "Set-Cookie" "token=; Path=/; HttpOnly; Max-Age=0"}})

(defn users-list [conn]
  (fn [_req]
    (html 200 (views/users-page {:users (users/list-users conn)}))))

(defn users-create [conn]
  (fn [req]
    (let [name (some-> (get-in req [:form-params "name"]) str/trim not-empty)]
      (if name
        (try
          (users/create-user! conn name)
          (redirect "/admin/users")
          (catch Exception e
            (html 400 (views/users-page {:users (users/list-users conn)
                                         :error (str "Could not create user: " (.getMessage e))}))))
        (redirect "/admin/users")))))

(defn users-delete [conn]
  (fn [req]
    (let [id (some-> (get-in req [:params :id]) Integer/parseInt)]
      (when id (users/delete-user! conn id))
      (redirect "/admin/users"))))

(defn- render-user-page
  ([conn id] (render-user-page conn id nil))
  ([conn id error]
   (let [user (users/get-user conn id)]
     (if user
       (html (if error 400 200)
             (views/user-page {:user user
                               :credentials (users/list-credentials conn id)
                               :telegram-link (users/get-telegram-link conn id)
                               :error error}))
       (html 404 (str "<h1>Not found</h1>"))))))

(defn user-show [conn]
  (fn [req]
    (let [id (some-> (get-in req [:params :id]) Integer/parseInt)]
      (if id
        (render-user-page conn id)
        (html 404 (str "<h1>Not found</h1>"))))))

(defn password-update [conn]
  (fn [req]
    (let [user-id  (some-> (get-in req [:params :id]) Integer/parseInt)
          password (some-> (get-in req [:form-params "password"]) str/trim not-empty)]
      (when (and user-id password)
        (users/set-password! conn user-id password))
      (redirect (str "/admin/users/" user-id)))))

(defn cred-create [conn]
  (fn [req]
    (let [user-id (some-> (get-in req [:params :id]) Integer/parseInt)
          app      (some-> (get-in req [:form-params "app"]) str/trim not-empty)
          username (some-> (get-in req [:form-params "username"]) str/trim not-empty)
          password (some-> (get-in req [:form-params "password"]) str/trim not-empty)]
      (cond
        (not user-id) (redirect "/admin/users")
        (not (and app username password))
        (let [user (users/get-user conn user-id)]
          (html 400 (views/user-page {:user user
                                      :credentials (users/list-credentials conn user-id)
                                      :error "App, username and password are all required."})))
        :else
        (try
          (users/create-credential! conn user-id app username password)
          (redirect (str "/admin/users/" user-id))
          (catch Exception e
            (let [user (users/get-user conn user-id)]
              (html 400 (views/user-page {:user user
                                          :credentials (users/list-credentials conn user-id)
                                          :error (str "Could not save credential: "
                                                      (.getMessage e))})))))))))

(defn cred-delete [conn]
  (fn [req]
    (let [user-id (some-> (get-in req [:params :id]) Integer/parseInt)
          cred-id (some-> (get-in req [:params :cred-id]) Integer/parseInt)]
      (when cred-id (users/delete-credential! conn cred-id))
      (redirect (str "/admin/users/" user-id)))))

(defn telegram-update [conn]
  (fn [req]
    (let [user-id (some-> (get-in req [:params :id]) Integer/parseInt)
          tid (some-> (get-in req [:form-params "telegram_user_id"])
                      str/trim
                      not-empty)
          display (some-> (get-in req [:form-params "display_name"])
                          str/trim
                          not-empty)]
      (cond
        (not user-id) (redirect "/admin/users")
        (nil? tid)
        (render-user-page conn user-id "Telegram user id is required.")
        :else
        (try
          (users/set-telegram-link! conn user-id tid display)
          (redirect (str "/admin/users/" user-id))
          (catch Exception e
            (let [msg (.getMessage e)
                  friendly (if (and msg (re-find #"UNIQUE|PRIMARY KEY|constraint" msg))
                             (str "Telegram id " tid " is already linked to a different user.")
                             (str "Could not save Telegram link: " msg))]
              (render-user-page conn user-id friendly))))))))

(defn telegram-delete [conn]
  (fn [req]
    (let [user-id (some-> (get-in req [:params :id]) Integer/parseInt)]
      (when user-id (users/delete-telegram-link! conn user-id))
      (redirect (str "/admin/users/" user-id)))))
