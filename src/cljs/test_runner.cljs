(ns test-runner
  (:require
    [agent.tools-test]
    [agent.tzu-test]
    [cljs.test :as t :refer-macros [run-tests]]
    [dao.await-test]
    [dao.db.file-test]
    [dao.gui.compiler-cljs-test]
    [dao.postgraphics.web.gpu-test]
    [dao.runtime.driver-cljs-test]
    [dao.stream-test]
    [dao.stream.http-test]
    [datomworld.continuation-transport-test]
    [datomworld.demo.continuation-handoff-test]
    [datomworld.demo.responsive-test]
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
  (run-tests 'dao.await-test
             'dao.db.file-test
             'dao.runtime.driver-cljs-test
             'agent.tools-test
             'agent.tzu-test
             'dao.stream.http-test
             'dao.gui.compiler-cljs-test
             'dao.postgraphics.web.gpu-test
             'datomworld.continuation-transport-test
             'datomworld.demo.yin-repl-test
             'datomworld.demo.continuation-handoff-test
             'datomworld.demo.responsive-test
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
