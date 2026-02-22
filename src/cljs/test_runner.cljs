(ns test-runner
  (:require [cljs.test :refer-macros [run-tests]]
            [dao.db.core-test]
            [dao.stream-test]
            [yin.content-test]
            [yin.transport-test]
            [yin.vm.ast-walker-test]
            [yin.vm.register-test]
            [yin.vm.semantic-test]
            [yin.vm.stack-test]
            [yin.vm.state-projection-test]))


(defn -main
  []
  (run-tests 'dao.db.core-test
             'dao.stream-test
             'yin.content-test
             'yin.transport-test
             'yin.vm.ast-walker-test
             'yin.vm.register-test
             'yin.vm.semantic-test
             'yin.vm.stack-test
             'yin.vm.state-projection-test))


(set! *main-cli-fn* -main)
