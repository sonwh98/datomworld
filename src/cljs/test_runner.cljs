(ns test-runner
  (:require [cljs.test :refer-macros [run-tests]]
            [daodb.core-test]
            [yin.asm-bench]
            [yin.asm-test]
            [yin.vm-addition-test]
            [yin.vm-basic-test]
            [yin.vm-continuation-test]
            [yin.vm-literal-test]
            [yin.vm-state-test]
            [yin.vm-stream-test]
            [yin.vm-test]
            [yin.vm.register-test]))


(defn -main
  []
  (run-tests 'daodb.core-test
             'yin.vm-test
             'yin.vm-literal-test
             'yin.vm-addition-test
             'yin.vm-state-test
             'yin.vm-basic-test
             'yin.asm-test
             'yin.asm-bench
             'yin.vm-continuation-test
             'yin.vm-stream-test
             'yin.vm.register-test))


(set! *main-cli-fn* -main)
