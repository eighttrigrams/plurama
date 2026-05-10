(ns plurama.app.mail.config
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]))

(defn load-mail-config
  "Read the gitignored per-user mail.yaml from `path`. Returns the parsed
  map (with `:user-configs`) or nil if the file is missing."
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (yaml/parse-string (slurp f)))))
