(ns plurama.server
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ring.adapter.jetty9 :as jetty]
            [nrepl.server :as nrepl]
            [et.pe.server :as personalist]
            [et.blog.server :as blog]
            [et.tr.server :as tracker]
            [plurama.app.server :as plurama-app])
  (:gen-class))

(defn- host-only [req]
  (some-> (get-in req [:headers "host"])
          (str/split #":")
          first
          str/lower-case))

(defn- dispatch [host->handler fallback-handler]
  (fn [req]
    (let [h (or (get host->handler (host-only req)) fallback-handler)]
      (if h
        (h req)
        {:status 404
         :headers {"Content-Type" "text/plain"}
         :body "no handler for host"}))))

(defn- load-config []
  (let [f (io/file "config.edn")]
    (when-not (.exists f)
      (throw (ex-info "config.edn required" {})))
    (edn/read-string (slurp f))))

(defn- prod-mode? []
  (not= "true" (System/getenv "DEV")))

(defn -main [& _args]
  (let [config (load-config)
        apps   {:personalist (personalist/build-handler
                              (get-in config [:apps :personalist]))
                :blog        (blog/build-handler
                              (get-in config [:apps :blog]))
                :tracker     (tracker/build-app
                              (get-in config [:apps :tracker]))
                :plurama     (plurama-app/build-handler
                              (assoc (get-in config [:apps :plurama])
                                     :umbrella config))}
        _      (when (and (prod-mode?)
                          (get-in config [:apps :tracker :workers?]))
                 (tracker/start-workers!))
        _      (when (and (prod-mode?)
                          (get-in config [:apps :plurama :mail :enabled?]))
                 (plurama-app/start-mail-poller!
                   (get-in config [:apps :plurama])))
        host->handler (into {} (for [[host k] (:hosts config)]
                                 [(str/lower-case host) (get apps k)]))
        fallback (get apps (:default config))
        port (or (some-> (System/getenv "PORT") Integer/parseInt)
                 (get-in config [:server :port]))
        host (or (System/getenv "HOST")
                 (get-in config [:server :host])
                 "127.0.0.1")]
    (println "[plurama] starting on" (str host ":" port))
    (jetty/run-jetty (dispatch host->handler fallback)
                     {:port port :host host :join? false})
    (when-not (prod-mode?)
      (let [nrepl-port (Integer/parseInt (or (System/getenv "NREPL_PORT") "7889"))]
        (nrepl/start-server :port nrepl-port)
        (spit ".nrepl-port" nrepl-port)
        (println "[plurama] nREPL on" nrepl-port)))
    @(promise)))
