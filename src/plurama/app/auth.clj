(ns plurama.app.auth
  (:require [buddy.sign.jwt :as jwt]
            [clojure.string :as str]))

(defn- jwt-secret []
  (or (System/getenv "ADMIN_PASSWORD")
      (if (System/getenv "FLY_APP_NAME")
        (throw (ex-info "ADMIN_PASSWORD env var is required" {}))
        "dev-secret")))

(defn admin-password []
  (or (System/getenv "ADMIN_PASSWORD")
      (when-not (System/getenv "FLY_APP_NAME") "admin")))

(defn create-admin-token []
  (jwt/sign {:role :admin} (jwt-secret)))

(defn create-user-token [user-id]
  (jwt/sign {:role :user :user-id user-id} (jwt-secret)))

(defn verify-token [token]
  (try
    (jwt/unsign token (jwt-secret))
    (catch Exception _ nil)))

(defn- parse-cookie-header [header]
  (when header
    (->> (str/split header #";\s*")
         (filter #(str/includes? % "="))
         (map #(let [[k v] (str/split % #"=" 2)] [k v]))
         (into {}))))

(defn current-identity
  "Return the JWT claims map (e.g. {:role :admin} or
  {:role :user :user-id 7}) for the request, or nil if not logged in."
  [req]
  (when-let [token (some-> (get-in req [:headers "cookie"])
                           parse-cookie-header
                           (get "token"))]
    (verify-token token)))

(defn logged-in? [req]
  (some? (current-identity req)))

(defn admin? [req]
  (= "admin" (some-> (current-identity req) :role name)))

(defn current-user-id
  "Return the plurama user-id of the logged-in non-admin user, or nil
  (admins do not have a user-id, only the :admin role)."
  [req]
  (let [{:keys [role user-id]} (current-identity req)]
    (when (= "user" (some-> role name)) user-id)))

(defn self-or-admin?
  "True if the request is by the admin or by user-id matching `target-id`."
  [req target-id]
  (or (admin? req)
      (= (current-user-id req) target-id)))
