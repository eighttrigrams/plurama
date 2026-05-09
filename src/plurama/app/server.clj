(ns plurama.app.server
  (:require [plurama.app.db :as db]))

(defn- landing-page-html [umbrella]
  (str
    "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
    "<title>plurama</title>"
    "<style>body{font-family:system-ui,sans-serif;max-width:640px;margin:4em auto;padding:0 1em;color:#222}"
    "h1{font-weight:300}"
    "ul{list-style:none;padding:0}"
    "li{padding:.5em 0;border-bottom:1px solid #eee}"
    "a{color:#06c;text-decoration:none}a:hover{text-decoration:underline}"
    "code{background:#f4f4f4;padding:.1em .3em;border-radius:3px;font-size:.9em}"
    "</style></head><body>"
    "<h1>plurama</h1>"
    "<p>An umbrella JVM hosting multiple apps, routed by Host header.</p>"
    "<h2>Hosted apps</h2><ul>"
    (apply str
      (for [[host k] (sort-by first (:hosts umbrella))
            :when (not= k :plurama)]
        (str "<li><a href=\"https://" host "/\">" host "</a> &rarr; <code>"
             (name k) "</code></li>")))
    "</ul></body></html>"))

(defn build-handler
  "Initialise plurama's own db and return a ring handler.
   `config` must include `:db` (per-app) and `:umbrella` (the full plurama config
   so the landing page can list other hosted apps)."
  [{:keys [umbrella] :as config}]
  (db/init-conn (:db config))
  (fn [req]
    (case (:uri req)
      "/" {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body (landing-page-html umbrella)}
      {:status 404
       :headers {"Content-Type" "text/plain"}
       :body "Not Found"})))
