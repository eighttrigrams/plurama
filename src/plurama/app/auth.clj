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

(defn create-token []
  (jwt/sign {:admin true} (jwt-secret)))

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

(defn logged-in? [req]
  (when-let [token (some-> (get-in req [:headers "cookie"])
                           parse-cookie-header
                           (get "token"))]
    (some? (verify-token token))))
