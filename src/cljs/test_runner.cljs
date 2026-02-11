(ns test-runner
  (:require [cljs.test :refer-macros [run-tests]]
            [daodb.core-test]
            [yin.vm-continuation-test]
            [yin.vm-literal-test]
            [yin.vm-state-test]
            [yin.vm-stream-test]
            [yin.vm-test]
            [yin.vm.ast-walker-test]
            [yin.vm.register-test]
            [yin.vm.semantic-test]
            [yin.vm.stack-test]))


(defn -main
  []
  (run-tests 'daodb.core-test
             'yin.vm-test
             'yin.vm-literal-test
             'yin.vm-state-test
             'yin.vm-continuation-test
             'yin.vm-stream-test
             'yin.vm.ast-walker-test
             'yin.vm.register-test
             'yin.vm.semantic-test
             'yin.vm.stack-test))


(set! *main-cli-fn* -main)
