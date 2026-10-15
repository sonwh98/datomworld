(ns yin.vm.linker.production-pipeline-test
  "The production composition must not regain the retired AST lowerers."
  (:require [clojure.test :refer [deftest is testing]]))


(deftest production-lowering-dependencies-are-a-derived
  (doseq [path ["src/cljc/yin/vm/linker.cljc"
                "src/cljc/yin/vm/linker/publish.cljc"
                "src/cljc/yin/vm/ledger.cljc"
                "src/cljc/yin/repl.cljc"]]
    (testing path
      (is (nil? (re-find #"\[yin\.vm\.(?:debruijn-resolve|debruijn-linearize|debruijn-register-compile|linearize|code)\s"
                         (slurp path)))))))
