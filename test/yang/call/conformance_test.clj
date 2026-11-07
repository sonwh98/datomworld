(ns yang.call.conformance-test
  (:require [clojure.test :refer [deftest is]]
            [yang.call.conformance :as kit]
            [yang.call.exception-contract :as contract]
            [yang.core.call :as call]
            [yang.core.uast :as u]
            [yin.vm :as vm]
            [yin.vm.data :as data]
            [yin.vm.module :as module]
            [yin.vm.test-utils :as tu]))


(deftest evaluated-boundary-observation-test
  (let [registry (-> (module/empty-registry) module/register-cell-module
                     data/register-data-module)
        run (fn [form]
              (vm/value
                (vm/eval (tu/create-vm {:primitives vm/primitives :modules registry})
                         (u/mark-tails (u/then call/uast (u/sexp->uast form))))))]
    (is (= {:x 7 :computed 8}
           (run (list 'let ['x 7]
                      (kit/observation-map-form {:x 'x :computed '(+ x 1)})))))
    (doseq [[entry expected]
            [['(fn [invocation] ((get invocation :yang.call/raise) (yang.call/thrown 7)))
              {:route :raise :condition {:yang.call/reason :yang.call/thrown :yang.call/value 7}
               :transfers 1 :after false}]
             ['(fn [invocation] 7)
              {:route :return :value 7 :transfers 0 :after true}]]]
      (is (= expected
             (run (list 'let ['callee (list 'cell/new (list 'assoc {} :yang.call/entry entry))]
                        (kit/boundary-call-form 'callee [] []))))))
    (is (true?
          (run
            (list 'let ['payload '(cell/new :identity)
                        'callee '(cell/new (assoc {} :yang.call/entry
                                                  (fn [invocation]
                                                    (get (get invocation :yang.call/positional) 0))))
                        'result (kit/boundary-call-form
                                  'callee (kit/observation-vector-form ['payload]) [])]
                  '(= payload (get result :value))))))))


(deftest uniform-root-exception-observation-test
  (doseq [[frontend raw expected]
          [[:python {:py/out [] :py/exception {:type "ValueError"}}
            {:type "ValueError"}]
           [:typescript {:js/out [] :js/uncaught {:value {:js/str "arbitrary"}}}
            {:value {:js/str "arbitrary"}}]
           [:php {:php/out "" :php/uncaught {:class "Exception" :message "x"}}
            {:class "Exception" :message "x"}]
           [:clojure [:yin.k/error {:marker :original}] {:marker :original}]]]
    (let [observation (kit/exception-outcome frontend raw)]
      (is (= frontend (:frontend observation)))
      (is (= expected (:exception observation)))))
  (is (= {:yang.call/reason :yang.call/thrown :yang.call/value :data}
         (:value (kit/exception-outcome
                   :clojure {:yang.call/reason :yang.call/thrown
                             :yang.call/value :data}))))
  (is (nil? (:exception (kit/exception-outcome :clojure [:yin.k/ok :data])))))


(deftest mechanically-derived-obligations-test
  (is (= 12 (count (set (map (juxt :donor :caller) contract/pair-matrix)))))
  (is (= 6 (count (set (map (juxt :donor :caller)
                            (filter #(= :jvm-linked (:execution %))
                                    contract/pair-matrix))))))
  (is (every? #(and (seq (:gates %)) (seq (:frontends %)))
              (concat contract/fixtures contract/pair-matrix
                      contract/pending-obligations)))
  (is (= #{::contract/r1 ::contract/r2 ::contract/r3 ::contract/r4 ::contract/r5}
         (get (contract/ledger-seed) :clojure))))
