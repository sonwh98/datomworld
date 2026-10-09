(ns yin.repl.frontend-catalog-test
  "The REPL compiles through the frontend catalog its composition supplies
   (docs/design/yang.antlr.md section 8.5.6, slice F2): a toy frontend
   defined here evaluates on every VM, a session pins its catalog snapshot,
   and an id the catalog cannot answer is an unavailable-parser diagnostic
   with no fallback."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [yang.clojure :as yang.clojure]
    [yang.frontend :as frontend]
    [yin.repl :as repl]
    [yin.repl.frontends :as repl.frontends]))


(defn- manifest
  [revision]
  {:yang.frontend/id :example.toy/frontend,
   :yang.frontend/spi 1,
   :yang.frontend/revision revision,
   :yang.frontend/language :toy,
   :yang.frontend/grammar {:yang.grammar/package "grammar-address",
                           :yang.grammar/entries {:module "program"},
                           :yang.grammar/export-profile :yang.cst/v1},
   :yang.frontend/options-schema "options-address",
   :yang.frontend/lowering-profile "lowering-address",
   :yang.frontend/runtime-profile "runtime-address",
   :yang.frontend/support-profile "support-address"})


(defn- toy-binding
  "A toy language: a source's value is its length times `scale`."
  [scale]
  {:parse count,
   :lower (fn [n] (yang.clojure/compile (* scale n)))})


(defn- toy-catalog
  [revision scale]
  (frontend/install (repl.frontends/standard)
                    (manifest revision)
                    (toy-binding scale)))


(defn- run
  [state line]
  (repl/eval-input state line))


(deftest a-toy-frontend-evaluates-on-every-vm-test
  (let [catalog (toy-catalog "r1" 1)]
    (doseq [vm-type (keys repl/vm-constructors)]
      (testing (str vm-type)
        (let [state (repl/create-state {:frontends catalog,
                                        :vm-type vm-type})
              [state' switched] (run state "(lang :toy)")
              [_ by-id] (run state "(lang :example.toy/frontend)")
              [_ result] (run state' "abcd")]
          (is (= "Switched to :toy" switched))
          (is (= "Switched to :example.toy/frontend" by-id))
          (is (= "4" result)))))))


(deftest the-standard-languages-still-select-by-id-and-language-test
  (let [state (repl.frontends/create-state)
        [python switched] (run state "(lang :yang.python/legacy)")
        [_ value] (run python "1 + 2")
        [_ compiled] (run (first (run state "(lang :php)")) "(compile \"1 + 2;\")")]
    (is (str/starts-with? switched "Switched to"))
    (is (= "3" value))
    (is (str/includes? compiled "AST:"))))


(deftest a-newer-revision-leaves-a-live-session-unchanged-test
  (let [catalog (toy-catalog "r1" 1)
        state (repl/create-state {:frontends catalog})
        [state' _] (run state "(lang :toy)")
        newer (frontend/install catalog (manifest "r2") (toy-binding 100))]
    (is (frontend/ok? (frontend/select newer
                                       {:yang.frontend/id :example.toy/frontend,
                                        :yang.frontend/revision "r2"})))
    (is (= "2" (second (run state' "ab"))))
    (is (= catalog (:frontends state')))
    (testing "a session composed with the newer catalog pins its own snapshot"
      (let [other (repl/create-state {:frontends (toy-catalog "r2" 100)})
            [other' _] (run other "(lang :toy)")]
        (is (= "200" (second (run other' "ab"))))
        (is (= "2" (second (run state' "ab"))))))))


(deftest an-unavailable-frontend-answers-without-fallback-test
  (let [state (repl.frontends/create-state)
        [state' answer] (run state "(lang :yang.python/antlr)")]
    (testing "selecting an id the catalog does not hold is a diagnostic"
      (is (str/includes? answer "unavailable-parser"))
      (is (= :clojure (:lang state'))))
    (testing "compiling under it answers unavailable-parser, not legacy"
      (let [pinned (assoc state :lang :yang.python/antlr)
            [_ result] (run pinned "1 + 2")
            [_ compiled] (run pinned "(compile \"1 + 2\")")]
        (is (str/includes? result "unavailable-parser"))
        (is (not= "3" result))
        (is (str/includes? compiled "unavailable-parser"))))
    (testing "a binding with no parse stage is unavailable too"
      (let [catalog (frontend/install frontend/empty-catalog
                                      (manifest "r1")
                                      {:lower identity})
            pinned (assoc (repl/create-state {:frontends catalog})
                          :lang :toy)
            [_ result] (run pinned "abc")]
        (is (str/includes? result "unavailable-parser"))))))


(deftest a-core-session-with-no-catalog-has-no-frontend-test
  (let [[_ result] (run (repl/create-state) "(+ 1 2)")]
    (is (str/includes? result "unavailable-parser"))
    (is (= {:yang.frontend/entries {}} (:frontends (repl/create-state))))))


(deftest a-revision-is-pinned-by-id-and-revision-test
  (let [catalog (-> (toy-catalog "r1" 1)
                    (frontend/install (manifest "r2") (toy-binding 100)))
        state (repl/create-state {:frontends catalog})]
    (testing "an id or language with two revisions is ambiguous, not chosen"
      (is (str/includes? (second (run state "(lang :example.toy/frontend)"))
                         "unavailable-parser"))
      (is (str/includes? (second (run state "(lang :toy)"))
                         "unavailable-parser")))
    (testing "[id revision] selects each revision and is kept in :lang"
      (let [[r1 _] (run state "(lang [:example.toy/frontend \"r1\"])")
            [r2 _] (run state "(lang [:example.toy/frontend \"r2\"])")]
        (is (= [:example.toy/frontend "r1"] (:lang r1)))
        (is (= "2" (second (run r1 "ab"))))
        (is (= "200" (second (run r2 "ab"))))))
    (testing "an uninstalled revision is unavailable"
      (is (str/includes?
            (second (run state "(lang [:example.toy/frontend \"r3\"])"))
            "unavailable-parser")))))


(deftest an-explicit-parser-service-makes-an-id-available-test
  (let [id :yang.python/antlr
        antlr-manifest (assoc (manifest "antlr-1") :yang.frontend/id id)
        bare (frontend/install (repl.frontends/standard)
                               antlr-manifest
                               {:lower (fn [_] nil)})
        served (frontend/install
                 (repl.frontends/standard)
                 antlr-manifest
                 {:parse (fn [src] (count src)),
                  :lower (fn [n] (yang.clojure/compile n))})]
    (testing "no parser and no service: unavailable, no legacy fallback"
      (let [[state _] (run (repl.frontends/create-state {:frontends bare})
                           "(lang :yang.python/antlr)")]
        (is (= :clojure (:lang state))))
      (is (str/includes?
            (second (run (assoc (repl.frontends/create-state
                                  {:frontends bare})
                                :lang id)
                         "1 + 2"))
            "unavailable-parser")))
    (testing "a configured service binding answers"
      (let [[state _] (run (repl.frontends/create-state {:frontends served})
                           "(lang :yang.python/antlr)")]
        (is (= "5" (second (run state "12345"))))))))
