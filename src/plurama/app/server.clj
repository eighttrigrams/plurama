(ns plurama.app.server
  (:require [compojure.core :refer [GET POST routes context]]
            [compojure.route :as route]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [clojure.java.io :as io]
            [plurama.app.db :as db]
            [plurama.app.handlers :as h]
            [plurama.app.agent.ai :as agent.ai]
            [plurama.app.agent.telegram :as agent.telegram]
            [plurama.app.mail.poller :as mail.poller]))

(defn- load-resource [path]
  (when path
    (when-let [r (io/resource path)]
      (slurp r))))

(defn- collect-app-skills
  "For each configured agent app, slurp the :skill resource (if any)
  and return a vector of {:app :skill-md} pairs in deterministic order."
  [agent-apps]
  (vec (for [[app-key {:keys [skill]}] (sort-by (comp name first) agent-apps)]
         {:app (name app-key)
          :skill-md (load-resource skill)})))

(defn- build-agent-ctx [{:keys [conn]} {:keys [agent]}]
  (let [agent-apps (or agent {})
        app-skills (collect-app-skills agent-apps)]
    {:conn conn
     :webhook-secret  (System/getenv "TELEGRAM_WEBHOOK_SECRET")
     :anthropic-key   (System/getenv "ANTHROPIC_API_KEY")
     :bot-token       (System/getenv "TELEGRAM_BOT_TOKEN")
     :agent-apps      agent-apps
     :system-prompt   (agent.ai/build-system-prompt app-skills)}))

(defn- admin-routes [conn]
  (-> (routes
        (GET  "/users"            []      (h/users-list   conn))
        (POST "/users"            []      (h/users-create conn))
        (POST "/users/:id/delete" []      (h/users-delete conn)))
      h/require-admin))

(defn- self-routes [conn]
  (-> (routes
        (GET  "/users/:id"              [] (h/user-show       conn))
        (POST "/users/:id/password"     [] (h/password-update conn))
        (POST "/users/:id/credentials"  [] (h/cred-create     conn))
        (POST "/users/:id/credentials/:cred-id/delete" [] (h/cred-delete conn))
        (POST "/users/:id/telegram"        [] (h/telegram-update conn))
        (POST "/users/:id/telegram/delete" [] (h/telegram-delete conn)))
      h/require-self-or-admin))

(defn- html-routes [conn umbrella]
  (routes
    (GET  "/"       []      (h/landing {:umbrella umbrella}))
    (GET  "/login"  []      h/login-page)
    (POST "/login"  []      (h/login-submit conn))
    (GET  "/logout" []      h/logout)
    (GET  "/me"     []      h/me-redirect)
    (context "/admin" []
      ;; Self-routes (require-self-or-admin) is tried first; it returns
      ;; nil when the URI has no /users/<id> segment, falling through
      ;; to admin-routes. Reversing this order causes a redirect loop:
      ;; require-admin would bounce non-admins on /admin/users/<self>
      ;; to /me which redirects right back here.
      (self-routes conn)
      (admin-routes conn))
    (route/not-found {:status 404
                      :headers {"Content-Type" "text/plain"}
                      :body "Not Found"})))

(defn- json-routes [agent-ctx]
  (-> (routes
        (POST "/webhook/telegram" [] (agent.telegram/webhook-handler agent-ctx)))
      (wrap-json-body {:keywords? true})
      wrap-json-response))

(defn build-handler
  "Initialise plurama's own db and return a ring handler."
  [{:keys [umbrella] :as config}]
  (let [parts (db/init-conn (:db config))
        agent-ctx (build-agent-ctx parts config)
        json-routes (json-routes agent-ctx)
        html-routes (-> (html-routes parts umbrella) wrap-params)]
    (fn [req]
      (if (= "/webhook/telegram" (:uri req))
        (json-routes req)
        (html-routes req)))))

(defn start-mail-poller!
  "Open a dedicated db connection and start the mail forwarder thread.
  Returns the Thread."
  [config]
  (let [{:keys [conn]} (db/init-conn (:db config))]
    (mail.poller/start! conn (:mail config))))
