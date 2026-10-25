(ns yang.antlr.packet-test
  "Fix D1: `validate!` emits the caller's diagnostic namespace."
  (:require
    [clojure.test :refer [deftest is]]
    [yang.antlr.packet :as packet]))


(defn- data-of
  [opts]
  (try (packet/validate! {} opts)
       nil
       (catch #?(:cljd Object
                 :clj clojure.lang.ExceptionInfo
                 :cljs ExceptionInfo)
              e
         (ex-data e))))


(deftest caller-namespace-test
  (let [data (data-of {:diagnostic-ns "yang.typescript.antlr"})]
    (is (= :yang.typescript.antlr/malformed-cst
           (:yang.typescript.antlr/diagnostic data)))
    (is (nil? (:yang.python.antlr/diagnostic data)))))


(deftest python-namespace-test
  (is (= :yang.python.antlr/malformed-cst
         (:yang.python.antlr/diagnostic
           (data-of {:diagnostic-ns "yang.python.antlr"})))))


(deftest default-namespace-is-not-python-test
  (let [data (data-of nil)]
    (is (= :yang.cst/malformed-cst (:yang.cst/diagnostic data)))
    (is (nil? (:yang.python.antlr/diagnostic data)))))
