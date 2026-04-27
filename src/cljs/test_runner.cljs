(ns test-runner
  (:require
    [cljs.test :refer-macros [run-tests]]
    [dao.stream-test]
    [datomworld.continuation-transport-test]
    [datomworld.demo.continuation-handoff-test]
    [datomworld.demo.vm-state-keys-test]
    [datomworld.demo.yin-repl-test]
    [yin.content-test]
    [yin.repl-test]
    [yin.transport-test]
    [yin.vm.ast-walker-test]
    [yin.vm.parity-test]
    [yin.vm.register-test]
    [yin.vm.semantic-test]
    [yin.vm.stack-test]
    [yin.vm.telemetry-viewer-test]
    [yin.vm.wasm-test]))


(defn -main
  []
  (run-tests 'datomworld.continuation-transport-test
             'datomworld.demo.yin-repl-test
             'datomworld.demo.continuation-handoff-test
             'datomworld.demo.vm-state-keys-test
             'yin.repl-test
             'dao.stream-test
             'yin.content-test
             'yin.transport-test
             'yin.vm.ast-walker-test
             'yin.vm.parity-test
             'yin.vm.register-test
             'yin.vm.semantic-test
             'yin.vm.stack-test
             'yin.vm.telemetry-viewer-test
             'yin.vm.wasm-test))


(set! *main-cli-fn* -main)
