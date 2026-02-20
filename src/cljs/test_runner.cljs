(ns test-runner
  (:require
    [cljs.test :refer-macros [run-tests]]
    [dao.db.core-test]
    [dao.stream-test]
    [yin.vm.ast-walker-test]
    [yin.vm.register-test]
    [yin.vm.semantic-test]
    [yin.vm.stack-test]))


(defn -main
  []
  (run-tests 'dao.db.core-test
             'dao.stream-test
             'yin.vm.ast-walker-test
             'yin.vm.register-test
             'yin.vm.semantic-test
             'yin.vm.stack-test))


(set! *main-cli-fn* -main)
