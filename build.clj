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
  (b/copy-dir {:src-dirs ["src"
                          "../personalist/src/clj" "../personalist/resources"
                          "../blog/src/clj"        "../blog/resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :src-dirs ["src" "../personalist/src/clj" "../blog/src/clj"]
                  :class-dir class-dir
                  :ns-compile '[plurama.server et.pe.server et.blog.server]
                  :java-opts []})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'plurama.server})
  (println "Uberjar written to" uber-file))
