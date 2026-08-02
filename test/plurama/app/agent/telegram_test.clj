(ns plurama.app.agent.telegram-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [aero.core :as aero]
            [plurama.app.agent.telegram :as telegram]))

(def ^:private prefix-match #'telegram/prefix-match)

(deftest the-b-prefix-forwards-to-blogs-notes-box
  (testing "the prefix is stripped and the rest is the Note"
    (is (= {:kind :blog-note :forward "buy milk"} (prefix-match "b buy milk"))))
  (testing "case-insensitive, like the tracker prefixes"
    (is (= {:kind :blog-note :forward "buy milk"} (prefix-match "B buy milk"))))
  (testing "any whitespace separates it, and the body keeps its own newlines"
    (is (= {:kind :blog-note :forward "a title\nand more"}
           (prefix-match "b\ta title\nand more"))))
  (testing "a bare b is not a prefix — there is no body after it to forward"
    (is (nil? (prefix-match "b"))))
  (testing "a whitespace-only body forwards empty, exactly as the n prefix does"
    ;; Both regexes let \s+ and (.+) split the run of whitespace, so the body
    ;; trims to "". Blog answers that 400 and the user is told, so the two shapes
    ;; are kept identical rather than one of them made stricter.
    (is (= {:kind :blog-note :forward ""} (prefix-match "b   ")))
    (is (= "" (:forward (prefix-match "n   "))))))

(deftest the-b-prefix-does-not-collide-with-the-tracker-ones
  (testing "the tracker kinds are untouched"
    (is (= :task (:kind (prefix-match "t something"))))
    (is (= :task (:kind (prefix-match "tt something"))))
    (is (= :note (:kind (prefix-match "n something"))))
    (is (= "private" (:scope (prefix-match "np something"))))
    (is (= "work" (:scope (prefix-match "nw something")))))
  (testing "and a tracker inbox message is a different kind from a blog Note"
    (is (not= (:kind (prefix-match "n same words"))
              (:kind (prefix-match "b same words")))))
  (testing "words that merely start with b are not the prefix"
    (is (nil? (prefix-match "because it rained")))
    (is (nil? (prefix-match "blog is nice")))))

;; The whole point of the flag: ai/chat builds the model's tool surface from the
;; keys of what it is handed, so anything in that map is something the model may
;; decide to call by itself. The prefix-only credentials are deliberately weak
;; and must never get there.
(deftest the-ai-only-sees-apps-marked-visible
  (let [agent-apps {:tracker        {:base-url "http://tracker" :ai-visible? true}
                    :tracker-direct {:base-url "http://tracker"}
                    :blog-notes     {:base-url "http://blog"}}
        app-ctxs   {"tracker"        {:base-url "http://tracker" :username "u"}
                    "tracker-direct" {:base-url "http://tracker" :username "d"}
                    "blog-notes"     {:base-url "http://blog" :username "notes-user"}}
        visible    (telegram/ai-app-ctxs agent-apps app-ctxs)]
    (is (= #{"tracker"} (set (keys visible))))
    (is (contains? visible "tracker"))
    (is (not (contains? visible "tracker-direct")))
    (is (not (contains? visible "blog-notes")))
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
        (is (contains? agent-apps :blog-notes)
            (str path " must configure blog-notes for the B prefix"))
        (doseq [app [:tracker-direct :blog-notes]]
          (is (nil? (get-in agent-apps [app :ai-visible?]))
              (str path " must not expose " app " to the AI")))
        (testing "and the flag is the only gate, so the AI sees exactly tracker"
          (let [ctxs (zipmap (map name (keys agent-apps)) (repeat {:username "u"}))]
            (is (= #{"tracker"} (set (keys (telegram/ai-app-ctxs agent-apps ctxs)))))))))))
