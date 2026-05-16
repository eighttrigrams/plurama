(ns plurama.server
  (:require [aero.core :as aero]
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
    (aero/read-config f)))

(defn- prod-mode? []
  (not= "true" (System/getenv "DEV")))

(defn -main [& _args]
  (let [config (load-config)
        ;; The plurama umbrella handler is always built — its db/agent/mail
        ;; live at the top level of config. The embedded apps are optional,
        ;; sourced from :apps.
        plurama-handler (plurama-app/build-handler (assoc config :umbrella config))
        apps   {:personalist (some-> (get-in config [:apps :personalist])
                                     personalist/build-handler)
                :blog        (some-> (get-in config [:apps :blog])
                                     blog/build-handler)
                :tracker     (some-> (get-in config [:apps :tracker])
                                     tracker/build-app)}
        _      (when (and (prod-mode?)
                          (get-in config [:apps :tracker :workers?]))
                 (tracker/start-workers!))
        _      (when (and (prod-mode?)
                          (get-in config [:mail :enabled?]))
                 (plurama-app/start-mail-poller! config))
        host->handler (into {} (for [[host k] (:hosts config)
                                     :let [h (get apps k)]
                                     :when h]
                                 [(str/lower-case host) h]))
        ;; Unmatched hosts always fall through to plurama itself.
        fallback plurama-handler
        port (get-in config [:server :port])
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
