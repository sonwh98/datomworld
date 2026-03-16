(ns test-runner
  (:require
    [cljs.test :refer-macros [run-tests]]
    [dao.stream-test]
    [datomworld.continuation-transport-test]
    [yin.content-test]
    [yin.transport-test]
    [yin.vm.ast-walker-test]
    [yin.vm.parity-test]
    [yin.vm.register-test]
    [yin.vm.semantic-test]
    [yin.vm.stack-test]
    [yin.vm.state-projection-test]
    [yin.vm.wasm-test]))


(defn -main
  []
  (run-tests 'datomworld.continuation-transport-test
             'dao.db.core-test
             'dao.stream-test
             'yin.content-test
             'yin.transport-test
             'yin.vm.ast-walker-test
             'yin.vm.parity-test
             'yin.vm.register-test
             'yin.vm.semantic-test
             'yin.vm.stack-test
             'yin.vm.state-projection-test
             'yin.vm.wasm-test))


(set! *main-cli-fn* -main)
