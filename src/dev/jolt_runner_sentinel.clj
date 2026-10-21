;; The sentinel of src/dev/jolt_test_runner.clj --self-test: a throwing test
;; between two passing ones. The runner must count one error and still run
;; the test after it. Not an oracle; never part of bb test:jolt-host.
(ns jolt-runner-sentinel
  (:require
    [clojure.test :refer [deftest is]]))


(deftest before-the-throw
  (is (= 2 (+ 1 1))))


(deftest throws
  (throw (ex-info "sentinel: a test that throws outside any assertion" {})))


(deftest after-the-throw
  (is (= 3 (+ 1 2))))
