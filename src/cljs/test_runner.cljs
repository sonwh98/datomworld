(ns test-runner
  (:require
    [cljs.test :refer-macros [run-tests]]
    [daodb.core-test]
    [yin.vm.ast-walker-test]
    [yin.vm.register-test]
    [yin.vm.semantic-test]
    [yin.vm.stack-test]))


(defn -main
  []
  (run-tests 'daodb.core-test
             'yin.vm.ast-walker-test
             'yin.vm.register-test
             'yin.vm.semantic-test
             'yin.vm.stack-test))


(set! *main-cli-fn* -main)
