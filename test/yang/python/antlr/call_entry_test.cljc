(ns yang.python.antlr.call-entry-test
  "Python function binder fixtures and source-call helper coverage for the
   shared call runtime. The binder fixtures prove the record/constructor
   contract only; they are not conformance evidence under docs/design/yang.antlr.md §8.10.11."
  (:require [clojure.test :refer [deftest is]]
            [yang.python.antlr.int-ops-test :as ops]
            [yang.python.antlr.prelude :as prelude]))


(def ^:private bindings
  '[raise-exit (fn [condition] condition)
    record (fn [f positional named]
             (assoc (assoc (assoc (assoc {} :yang.call/callee f)
                                  :yang.call/positional positional)
                           :yang.call/named named)
                    :yang.call/raise raise-exit))
    invoke (fn [f positional named]
             ((get (cell/get f) :yang.call/entry) (record f positional named)))
    positional (py/make-function "positional" {:params ["x"]} [] []
                                 (fn [args] (get args 0)))
    named (py/make-function "named" {:params ["x" "y"]} [] []
                            (fn [args] (get args 1)))
    defaulted (py/make-function "defaulted" {:params ["x"]} [7] []
                                (fn [args] (get args 0)))
    variadic (py/make-function "variadic" {:params ["x"] :star? true} [] []
                               (fn [args] (get (get args 1) :items)))
    duplicate (py/make-function "duplicate" {:params ["x"]} [] []
                                (fn [args] (get args 0)))
    substituted (py/make-function "substituted" {:params ["x"]} [] []
                                  (fn [args] (get args 0)))
    receiver-fn (py/make-function "receiver" {:params ["x"]} [] []
                                  (fn [args] (get args 0)))
    profile-fn (py/make-function "profile" {:params ["x"]} [] []
                                 (fn [args] (get args 0)))
    caught (fn [thunk]
             (py/try thunk
                     (fn [e] (get (cell/get (get (cell/get e) :class)) :name))
                     (fn [] :py/None)))])


(def ^:private result-expressions
  '[(if (get (cell/get positional) :yang.call/entry) true false)
    (if (get (cell/get py.b/len) :yang.call/entry) true false)
    (if (get (cell/get py.b/TypeError) :yang.call/entry) true false)
    (let [absent (record positional [] [])
          supplied (assoc (record positional [] []) :yang.call/receiver :py/None)]
      (py/vconj (py/vconj [] (get absent :yang.call/receiver :py/missing))
                (get supplied :yang.call/receiver :py/missing)))
    (invoke positional [4] [])
    (invoke named [1] [["y" 9]])
    (invoke defaulted [] [])
    (invoke defaulted [:py/None] [])
    (invoke variadic [1 2 3] [])
    (let [r (invoke duplicate [1] [["x" 2]])
          e (get r :yang.call/value)]
      (py/vconj (py/vconj [] (get r :yang.call/reason))
                (get (cell/get (get (cell/get e) :class)) :name)))
    (get ((get (cell/get positional) :yang.call/entry)
          (record substituted [5] []))
         :yang.call/reason)
    (invoke receiver-fn [11] [])
    ((get (cell/get receiver-fn) :yang.call/entry)
     (assoc (record receiver-fn [12] []) :yang.call/receiver :py/None))
    (get ((get (cell/get profile-fn) :yang.call/entry)
          (assoc (record profile-fn [3] []) :yang.call/binding-profile :py/None))
         :yang.call/reason)])


(def ^:private fixtures
  (list 'let bindings
        (reduce (fn [acc expression] (list 'py/vconj acc expression))
                []
                result-expressions)))


(deftest python-function-entry-record-contract-test
  (let [expected [true false false [:py/missing :py/None]
                  4 9 7 :py/None [2 3]
                  [:yang.call/thrown "TypeError"]
                  :yang.call/invalid-invocation
                  11 12 :yang.call/unsupported-binding]]
    (doseq [[backend result] (ops/run-with-prelude prelude/uast fixtures)]
      (is (= expected result) (str backend)))))


(def ^:private fixture
  '(let [python-fn (py/make-function "python" {:params ["x" "y"]} [7] []
                                     (fn [args] (get args 1)))
         foreign-fn (assoc {}
                           :yang.call/entry
                           (fn [invocation]
                             (get (get invocation :yang.call/positional) 0)))
         call-python (py/call-kw python-fn [1] [])
         call-named (py/call-kw python-fn [1] [["y" 9]])
         call-foreign (py/call foreign-fn [42])
         not-callable (py/try (fn [] (py/call-kw {} [] []))
                              (fn [e] (get (cell/get (get (cell/get e) :class)) :name))
                              (fn [] :py/None))]
     (py/vconj (py/vconj (py/vconj [] call-python) call-named)
               (py/vconj (py/vconj [] call-foreign) not-callable))))


(deftest python-source-calls-use-shared-entry-test
  (doseq [[backend result] (ops/run-with-prelude prelude/uast fixture)]
    (is (= [7 9 [42 "TypeError"]] result) (str backend))))


(deftest python-cross-frontend-callable-identity-test
  (let [fixture
        '(let [php-function
               (cell/new
                 (assoc {:php/type :function}
                        :yang.call/entry
                        (fn [invocation]
                          (get invocation :yang.call/callee))))
               returned (py/call-kw php-function [] [])]
           (if (= php-function returned) :same :different))]
    (doseq [[backend result] (ops/run-with-prelude prelude/uast fixture)]
      (is (= :same result) (str backend)))))
