(ns yin.repl.build-test
  "The REPL's host entry points are named by build configuration, so the
   entries are checked here."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))


(defn- read-config
  [path]
  (edn/read-string (slurp (io/file path))))


(deftest deps-edn-carries-the-four-additive-aliases
  (let [aliases (:aliases (read-config "deps.edn"))]
    (is (= ["-m" "yin.repl"] (:main-opts (:clj-yin-repl aliases))))
    (is (= ["-m" "shadow.cljs.devtools.cli" "run" "yin.repl/-main"]
           (:main-opts (:cljs-yin-repl aliases))))
    (is (= ["-m" "yin.repl.runner"] (:main-opts (:cljd-yin-repl aliases))))
    (is (= 'yin.repl (:main (:cljd/opts (:cljd-yin-repl-build aliases)))))
    (testing "the v1 aliases are deleted"
      (is (not (contains? aliases :telemetry-server))))))


(deftest shadow-cljs-edn-carries-the-additive-node-build
  (let [builds (:builds (read-config "shadow-cljs.edn"))]
    (is (= :node-script (:target (:yin-repl builds))))
    (is (= 'yin.repl/-main (:main (:yin-repl builds))))
    (testing "the v1 node builds are deleted"
      (is (not (contains? builds :telemetry-server))))))


(deftest the-cljd-entry-point-exists-and-names-the-namespace
  (let [entry (slurp (io/file "bin/yin_repl_main.dart"))]
    (is (re-find #"cljd-out/yin/repl\.dart" entry))
    (is (re-find #"repl\.main\(args\)" entry))))
