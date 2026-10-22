;; Babashka-only self-test; fixtures stay inside the worktree and are deleted.
(ns check-node-discovery-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))


(deftest node-discovery-check
  (let [scratch (fs/create-temp-dir {:dir "." :prefix ".node-discovery-"})
        driver (str (fs/absolutize "src/dev/check_node_discovery.clj"))
        write! (fn [path text]
                 (let [file (fs/path scratch path)]
                   (fs/create-dirs (fs/parent file))
                   (spit (str file) text)))
        run (fn []
              (process/shell {:dir (str scratch) :continue true
                              :shutdown nil :out :string :err :string}
                             "bb" driver))]
    (try
      (write! "deps.edn"
              "{:paths [\"src/main\"] :aliases {:cljs {:extra-paths [\"test\" \"src/dev\" \"missing-generated\"]}}}")
      (write! "shadow-cljs.edn" "{:builds {:test {:target :node-test}}}")
      (write! "src/main/clean.cljs" "(ns clean)")
      (write! "test/nested/allowed.cljc" "(ns allowed-test)")
      (is (zero? (:exit (run))) "clean tree passes; test/ and missing roots are excluded")

      (write! "src/dev/unrelated_filename.cljc"
              "; (ns comment-decoy)\n\"(ns string-decoy)\"\n(ns ^{:doc \"metadata\"} actual-test #?@(:cljs [(:require [cljs.test])]))")
      (let [{:keys [exit err]} (run)]
        (is (= 1 exit) "a dev .cljc ns matching the default regexp fails")
        (doseq [text ["src/dev/unrelated_filename.cljc" "actual-test"
                      "auto-discovers" "rename" "reader conditionals"]]
          (is (str/includes? err text) (str "diagnostic includes " text))))
      (fs/delete (fs/path scratch "src/dev/unrelated_filename.cljc"))

      (write! "src/dev/ignored.clj" "(ns ignored-test)")
      (is (zero? (:exit (run))) ".clj files are ignored")
      (write! "src/dev/shim.cljc" "(ns shim-check)")
      (is (zero? (:exit (run))) "nonmatching .cljc ns passes")
      (write! "src/dev/guarded.cljc" "#?(:clj (ns guarded-test (:require [jvm.only])))")
      (is (zero? (:exit (run))) "a JVM-only conditional ns passes")
      (write! "src/dev/guarded.cljc" "#?(:clj (ns guarded-test) :cljs (ns guarded-check))")
      (is (zero? (:exit (run))) "select the CLJS ns branch")
      (write! "src/dev/guarded.cljc" "#?(:cljs (ns ^:private conditional-test))")
      (is (= 1 (:exit (run))) "a conditional CLJS ns matching the regexp fails")
      (fs/delete (fs/path scratch "src/dev/guarded.cljc"))

      (write! "src/main/root.cljs" "(ns root-test)")
      (is (= 1 (:exit (run))) "base :paths and .cljs files are scanned")
      (write! "shadow-cljs.edn" "{:builds {:test {:target :node-test :ns-regexp \"-spec$\"}}}")
      (is (zero? (:exit (run))) "custom regexp replaces the default")
      (write! "src/dev/custom.cljc" "(ns custom-spec)")
      (let [{:keys [exit err]} (run)]
        (is (= 1 exit) "custom regexp is honored")
        (is (str/includes? err "custom-spec")))
      (finally (fs/delete-tree scratch)))))


(let [{:keys [fail error]} (run-tests 'check-node-discovery-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
