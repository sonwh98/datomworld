(ns yin.repl.v2-build-test
  "The v2 REPL's host entry points are named by build configuration, so the two
   additive entries are checked here — including that the v1 entries they sit
   beside are unchanged."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))


(defn- read-config
  [path]
  (edn/read-string (slurp (io/file path))))


(deftest deps-edn-carries-the-four-additive-v2-aliases
  (let [aliases (:aliases (read-config "deps.edn"))]
    (is (= ["-m" "yin.repl.v2"] (:main-opts (:clj-yin-repl-v2 aliases))))
    (is (= ["-m" "shadow.cljs.devtools.cli" "run" "yin.repl.v2/-main"]
           (:main-opts (:cljs-yin-repl-v2 aliases))))
    (is (= ["-m" "yin.repl.v2.runner"] (:main-opts (:cljd-yin-repl-v2 aliases))))
    (is (= 'yin.repl.v2 (:main (:cljd/opts (:cljd-yin-repl-v2-build aliases)))))
    (testing "no existing alias is changed or repointed"
      (is (= ["-m" "yin.repl"] (:main-opts (:clj-yin-repl aliases))))
      (is (= ["-m" "yin.repl.runner"] (:main-opts (:cljd-yin-repl aliases))))
      (is (= 'yin.repl (:main (:cljd/opts (:cljd-yin-repl-build aliases))))))))


(deftest shadow-cljs-edn-carries-the-additive-node-build
  (let [builds (:builds (read-config "shadow-cljs.edn"))]
    (is (= :node-script (:target (:yin-repl-v2 builds))))
    (is (= 'yin.repl.v2/-main (:main (:yin-repl-v2 builds))))
    (testing "the v1 node build is untouched"
      (is (= 'yin.repl/-main (:main (:yin-repl builds)))))))


(deftest the-cljd-entry-point-exists-and-names-the-v2-namespace
  (let [entry (slurp (io/file "bin/yin_repl_v2_main.dart"))]
    (is (.exists (io/file "bin/yin_repl_main.dart")) "the v1 entry stays")
    (is (re-find #"cljd-out/yin/repl/v2\.dart" entry))
    (is (re-find #"repl\.main\(args\)" entry))))
