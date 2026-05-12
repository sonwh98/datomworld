(ns test-runner
  (:require
    [cljs.test :as t :refer-macros [run-tests]]
    [dao.mr-clean.compiler-cljs-test]
    [dao.runtime.driver-cljs-test]
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


(defmethod t/report :end-run-tests
  [m]
  (let [{:keys [fail error]} m]
    (if (pos? (+ fail error)) (js/process.exit 1) (js/process.exit 0))))


(defn -main
  []
  (run-tests 'dao.runtime.driver-cljs-test
             'dao.mr-clean.compiler-cljs-test
             'datomworld.continuation-transport-test
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
