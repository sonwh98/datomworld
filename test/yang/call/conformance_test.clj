(ns yang.call.conformance-test
  (:require [clojure.test :refer [deftest is]]
            [yang.call.conformance :as kit]
            [yang.call.exception-contract :as contract]))


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
