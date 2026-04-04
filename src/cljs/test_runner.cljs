(ns test-runner
  (:require
    [cljs.test :refer-macros [run-tests]]
    [dao.repl-test]
    [dao.stream-test]
    [dao.stream.transport.file-test]
    [datomworld.continuation-transport-test]
    [yin.content-test]
    [yin.transport-test]
    [yin.vm.ast-walker-test]
    [yin.vm.parity-test]
    [yin.vm.register-test]
    [yin.vm.semantic-test]
    [yin.vm.stack-test]
    [yin.vm.wasm-test]))


(defn -main
  []
  (run-tests 'datomworld.continuation-transport-test
             'dao.repl-test
             'dao.stream-test
             'dao.stream.transport.file-test
             'yin.content-test
             'yin.transport-test
             'yin.vm.ast-walker-test
             'yin.vm.parity-test
             'yin.vm.register-test
             'yin.vm.semantic-test
             'yin.vm.stack-test
             'yin.vm.wasm-test))


(set! *main-cli-fn* -main)
