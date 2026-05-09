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

(defn- token-cookie [token]
  (str "token=" token "; Path=/; HttpOnly; SameSite=Strict"))

(defn require-login [handler]
  (fn [req]
    (if (auth/logged-in? req)
      (handler req)
      (redirect "/login"))))

(defn require-admin [handler]
  (fn [req]
    (cond
      (auth/admin? req) (handler req)
      (auth/logged-in? req) (redirect "/me")
      :else (redirect "/login"))))

(defn require-self-or-admin
  "Wrap a handler so only the admin or the user whose id matches the
  first numeric `/users/<id>` segment in the URI can reach it.
  Extracts the id from `:uri` directly because this wrapper runs
  before compojure parses path params."
  [handler]
  (fn [req]
    (let [m (re-find #"/users/(\d+)" (or (:uri req) ""))
          target-id (when m (Integer/parseInt (second m)))]
      (cond
        (and target-id (auth/self-or-admin? req target-id)) (handler req)
        (auth/logged-in? req) (redirect "/me")
        :else (redirect "/login")))))

(defn landing [{:keys [umbrella]}]
  (fn [req]
    (html 200 (views/landing-page {:umbrella umbrella
                                   :logged-in? (auth/logged-in? req)
                                   :admin? (auth/admin? req)
                                   :user-id (auth/current-user-id req)}))))

(defn login-page [_req]
  (html 200 (views/login-page {})))

(defn login-submit [conn]
  (fn [req]
    (let [name (some-> (get-in req [:form-params "name"]) str/trim not-empty)
          pw   (some-> (get-in req [:form-params "password"]) str/trim not-empty)]
      (cond
        ;; admin login: empty name + matching admin password
        (and (nil? name) pw (= pw (auth/admin-password)))
        {:status 302
         :headers {"Location" "/admin/users"
                   "Set-Cookie" (token-cookie (auth/create-admin-token))}}

        ;; user login: name + password match a users row
        (and name pw)
        (if-let [user (users/find-user-by-name conn name)]
          (if (and (:password user) (= pw (:password user)))
            {:status 302
             :headers {"Location" "/me"
                       "Set-Cookie" (token-cookie (auth/create-user-token (:id user)))}}
            (html 401 (views/login-page {:error "Wrong name or password"
                                         :name name})))
          (html 401 (views/login-page {:error "Wrong name or password"
                                       :name name})))

        :else
        (html 401 (views/login-page {:error "Name + password (or admin password alone)"
                                     :name name}))))))

(defn logout [_req]
  {:status 302
   :headers {"Location" "/"
             "Set-Cookie" "token=; Path=/; HttpOnly; Max-Age=0"}})

(defn me-redirect [req]
  (cond
    (auth/admin? req) (redirect "/admin/users")
    (auth/current-user-id req) (redirect (str "/admin/users/" (auth/current-user-id req)))
    :else (redirect "/login")))

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
  ([conn id req] (render-user-page conn id req nil))
  ([conn id req error]
   (let [user (users/get-user conn id)]
     (if user
       (html (if error 400 200)
             (views/user-page {:user user
                               :credentials (users/list-credentials conn id)
                               :telegram-link (users/get-telegram-link conn id)
                               :admin? (auth/admin? req)
                               :error error}))
       (html 404 (str "<h1>Not found</h1>"))))))

(defn user-show [conn]
  (fn [req]
    (let [id (some-> (get-in req [:params :id]) Integer/parseInt)]
      (if id
        (render-user-page conn id req)
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
        (render-user-page conn user-id req
                          "App, username and password are all required.")
        :else
        (try
          (users/create-credential! conn user-id app username password)
          (redirect (str "/admin/users/" user-id))
          (catch Exception e
            (render-user-page conn user-id req
                              (str "Could not save credential: " (.getMessage e)))))))))

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
        (render-user-page conn user-id req "Telegram user id is required.")
        :else
        (try
          (users/set-telegram-link! conn user-id tid display)
          (redirect (str "/admin/users/" user-id))
          (catch Exception e
            (let [msg (.getMessage e)
                  friendly (if (and msg (re-find #"UNIQUE|PRIMARY KEY|constraint" msg))
                             (str "Telegram id " tid " is already linked to a different user.")
                             (str "Could not save Telegram link: " msg))]
              (render-user-page conn user-id req friendly))))))))

(defn telegram-delete [conn]
  (fn [req]
    (let [user-id (some-> (get-in req [:params :id]) Integer/parseInt)]
      (when user-id (users/delete-telegram-link! conn user-id))
      (redirect (str "/admin/users/" user-id)))))
