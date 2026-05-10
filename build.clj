(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'plurama/plurama)
(def version "0.0.1")
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"
                            :aliases [:run]}))
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"
                          "../personalist/src/clj" "../personalist/resources"
                          "../blog/src/clj"        "../blog/resources"
                          "../tracker/src/clj"     "../tracker/src/cljc" "../tracker/resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :src-dirs ["src"
                             "../personalist/src/clj"
                             "../blog/src/clj"
                             "../tracker/src/clj"]
                  :class-dir class-dir
                  :ns-compile '[plurama.server
                                plurama.app.server
                                plurama.app.handlers
                                plurama.app.users
                                plurama.app.views
                                plurama.app.auth
                                plurama.app.agent.ai
                                plurama.app.agent.app-client
                                plurama.app.agent.db
                                plurama.app.agent.telegram
                                plurama.app.agent.tools
                                plurama.app.mail.config
                                plurama.app.mail.imap
                                plurama.app.mail.poller
                                et.pe.server
                                et.blog.server
                                et.tr.server]
                  :java-opts []})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'plurama.server})
  (println "Uberjar written to" uber-file))
