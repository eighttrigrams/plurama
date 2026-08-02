(ns plurama.app.agent.telegram-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [aero.core :as aero]
            [plurama.app.agent.telegram :as telegram]))

;; The whole point of the flag: ai/chat builds the model's tool surface from the
;; keys of what it is handed, so anything in that map is something the model may
;; decide to call by itself. The prefix-only credentials are deliberately weak
;; and must never get there.
(deftest the-ai-only-sees-apps-marked-visible
  (let [agent-apps {:tracker        {:base-url "http://tracker" :ai-visible? true}
                    :tracker-direct {:base-url "http://tracker"}}
        app-ctxs   {"tracker"        {:base-url "http://tracker" :username "u"}
                    "tracker-direct" {:base-url "http://tracker" :username "d"}}
        visible    (telegram/ai-app-ctxs agent-apps app-ctxs)]
    (is (= #{"tracker"} (set (keys visible))))
    (is (contains? visible "tracker"))
    (is (not (contains? visible "tracker-direct")))
    (testing "the app-ctx itself is handed through unchanged"
      (is (= (get app-ctxs "tracker") (get visible "tracker"))))))

(deftest visibility-defaults-to-invisible
  (testing "an app that says nothing is unreachable by the model"
    (is (empty? (telegram/ai-app-ctxs {:newcomer {:base-url "http://x"}}
                                      {"newcomer" {:base-url "http://x"}}))))
  (testing "and only literal true counts — not a truthy value, not a string"
    (doseq [flag [nil false "true" 1 :yes]]
      (is (empty? (telegram/ai-app-ctxs {:newcomer {:base-url "http://x" :ai-visible? flag}}
                                        {"newcomer" {:base-url "http://x"}}))
          (str ":ai-visible? " (pr-str flag) " must not make an app visible"))))
  (testing "a visible app with no credential row is still not reachable"
    (is (empty? (telegram/ai-app-ctxs {:tracker {:base-url "http://t" :ai-visible? true}}
                                      {})))))

;; Under default-invisible, forgetting the flag in a config file costs the AI its
;; tracker access silently — in production, where nobody is watching a test run.
;; So the shipped configs are asserted, not just the function.
(def ^:private tracked-configs ["config.edn.template" "config.prod.edn"])

(deftest every-shipped-config-keeps-tracker-visible-and-the-direct-apps-hidden
  (doseq [path tracked-configs]
    (let [agent-apps (:agent (aero/read-config (io/file path)))]
      (testing path
        (is (seq agent-apps) (str path " must configure :agent apps"))
        (is (true? (get-in agent-apps [:tracker :ai-visible?]))
            (str path " must keep tracker visible to the AI"))
        (is (nil? (get-in agent-apps [:tracker-direct :ai-visible?]))
            (str path " must not expose tracker-direct to the AI"))
        (testing "and the flag is the only gate, so the AI sees exactly tracker"
          (let [ctxs (zipmap (map name (keys agent-apps)) (repeat {:username "u"}))]
            (is (= #{"tracker"} (set (keys (telegram/ai-app-ctxs agent-apps ctxs)))))))))))
