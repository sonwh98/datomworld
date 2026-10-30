(ns yang.core.call-test
  "Synthetic guest proof for the language-neutral calling runtime."
  (:require [clojure.test :refer [deftest is testing]]
            [yang.core.call :as call]
            [yang.core.prelude :as core]
            [yang.core.uast :as u]
            [yin.vm :as vm]
            [yin.vm.data :as data]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.semantic.derive :as derive]
            [yin.vm.semantic.linearize :as sr-linearize]
            [yin.vm.test-utils :as tu]))


(def ^:private registry
  (-> (module/empty-registry)
      module/register-cell-module
      data/register-data-module))


(def ^:private opts
  {:make-stream tu/make-stream
   :capability-secret tu/secret
   :primitives vm/primitives
   :modules registry})


(def ^:private load-semantic-ast
  (vm/fresh-code-loader semantic/load-ast vm/ast-contract))


(def ^:private runners
  {:ast-walker (fn [ast] (vm/eval (tu/create-vm opts) ast))
   :semantic (fn [ast]
               (vm/run (load-semantic-ast (semantic/create-vm opts)
                                          (vm/ast->datoms ast))))
   :stack (fn [ast]
            (vm/run (dvm/create-vm
                      (:image (derive/stack-image
                                (:vector (sr-linearize/project ast))))
                      (assoc opts :contract vm/stack-contract))))
   :register (fn [ast]
               (vm/run (rvm/create-vm
                         (derive/register-image
                           (:vector (sr-linearize/project ast)))
                         (assoc opts :contract vm/register-contract))))})


(defn- run-everywhere
  [emitter form]
  (let [ast (u/mark-tails (u/then emitter (u/sexp->uast form)))]
    (into {}
          (map (fn [[engine run]] [engine (vm/value (run ast))]))
          runners)))


(deftest base-validation-and-construction-test
  (testing "required fields, ordered names, duplicate names and receiver presence"
    (is (every? true?
                (vals (run-everywhere
                        call/uast
                        '(yang.call/valid-invocation?
                           {:yang.call/callee :f
                            :yang.call/positional []
                            :yang.call/named [["x" 1] ["x" 2]]
                            :yang.call/raise :exit
                            :yang.call/receiver nil})))))
    (is (every? false?
                (vals (run-everywhere
                        call/uast
                        '(yang.call/valid-invocation?
                           {:yang.call/callee :f
                            :yang.call/positional []
                            :yang.call/named [[1 :bad]]
                            :yang.call/raise :exit})))))
    (is (every? false?
                (vals (run-everywhere
                        call/uast
                        '(yang.call/valid-invocation?
                           {:yang.call/callee :f
                            :yang.call/positional nil
                            :yang.call/named []
                            :yang.call/raise :exit}))))))
  (testing "invocation-with retains optional fields and cannot replace core fields"
    (is (every? true?
                (vals (run-everywhere
                        call/uast
                        '(let [record
                               (yang.call/invocation-with
                                 :callee [] [] :exit
                                 {:yang.call/receiver nil
                                  :yang.call/callee :substitute})]
                           (if (= :callee (get record :yang.call/callee))
                             (if (data/contains? record :yang.call/receiver)
                               (nil? (get record :yang.call/receiver))
                               false)
                             false))))))))


(deftest entry-and-invocation-test
  (testing "descriptor entry, eligibility, original identity and unchanged result"
    (is (every? true?
                (vals (run-everywhere
                        call/uast
                        '(let [callee {:opaque [1 2]}
                               descriptor
                               (assoc {}
                                      :yang.call/entry
                                      (fn [inv]
                                        (get inv :yang.call/callee)))]
                           (yang.call/eligible? descriptor))))))
    (let [payload [1 2]]
      (is (every? #(= payload %)
                  (vals (run-everywhere
                          call/uast
                          '(let [callee (assoc {} :opaque [1 2])
                                 descriptor
                                 (assoc callee
                                        :yang.call/entry
                                        (fn [inv]
                                          (get (get inv :yang.call/callee)
                                               :opaque)))]
                             (yang.call/invoke descriptor [] []
                                               (fn [condition]
                                                 condition)))))))))
  (testing "cell-backed entry lookup and an ineligible descriptor"
    (is (every? true?
                (vals (run-everywhere
                        call/uast
                        '(let [descriptor
                               (cell/new
                                 (assoc {}
                                        :yang.call/entry
                                        (fn [inv] :called)))
                               ineligible
                               (assoc (assoc {} :yang.call/entry
                                             (fn [inv] :wrong))
                                      :yang.call/eligible?
                                      (fn [callee] false))
                               called
                               (yang.call/invoke
                                 descriptor [] []
                                 (fn [condition] condition))
                               result
                               (yang.call/invoke
                                 ineligible [] []
                                 (fn [condition] condition))]
                           (if (= :called called)
                             (= :yang.call/not-callable
                                (get result :yang.call/reason))
                             false))))))))


(deftest conditions-and-transfer-test
  (testing "throwing wraps condition-shaped data; normal return remains data"
    (is (every? true?
                (vals (run-everywhere
                        call/uast
                        '(let [payload {:yang.call/reason :yang.call/thrown
                                        :yang.call/value :inner}
                               result (yang.call/thrown payload)]
                           (if (= payload (get result :yang.call/value))
                             (= :yang.call/thrown
                                (get result :yang.call/reason))
                             false)))))))
  (testing "transfer applies the supplied exit once"
    (is (every? #(= {:yang.call/reason :yang.call/not-callable
                     :yang.call/data {:callee :value}
                     :seen true}
                    %)
                (vals (run-everywhere
                        call/uast
                        '(yang.call/transfer
                           (fn [condition]
                             (assoc condition :seen true))
                           (yang.call/condition :yang.call/not-callable
                                                {:callee :value})))))))
  (testing "bundled and linked emitters use the same definition source"
    (is (= call/module-uast
           (core/module-tree
             (core/module-definitions "yang.call" :yang.call/uninit
                                      [] call/definitions)
             [])))
    (is (= call/module-exports
           (:exports (call/module-spec registry))))
    (is (every? true?
                (vals (run-everywhere
                        call/module-uast
                        '(valid-invocation?
                           {:yang.call/callee :f
                            :yang.call/positional []
                            :yang.call/named []
                            :yang.call/raise :exit})))))))


(deftest cross-frontend-callable-identity-fixture-test
  (testing "a stored PHP function crosses a TS entry, is called, and stays same-cell"
    (let [results (run-everywhere
                    call/uast
                    '(let [php-function
                           (cell/new
                             (assoc {:php/type :function}
                                    :yang.call/entry
                                    (fn [invocation]
                                      (get invocation :yang.call/callee))))
                           stored (cell/new php-function)
                           original (cell/get stored)
                           typescript-relay
                           (assoc {}
                                  :yang.call/entry
                                  (fn [invocation]
                                    (yang.call/invoke
                                      (get invocation :yang.call/receiver)
                                      [] []
                                      (get invocation :yang.call/raise))))
                           returned
                           (yang.call/invoke-with
                             typescript-relay [] []
                             (fn [condition] condition)
                             (assoc {} :yang.call/receiver original))]
                       (if (= original returned) :same :different)))]
      (is (every? #{:same} (vals results))
          (pr-str results)))))
