(ns yin.vm.semantic-register.parity-test
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as walker]
            [yin.vm.parity-test :as b0]
            [yin.vm.test-utils :as tu]
            [yin.vm.values :as values]
            [yin.vm.semantic-register :as sr]
            [yin.vm.semantic-register.code :as code]
            [yin.vm.semantic-register.corpus :as corpus]
            [yin.vm.semantic-register.linearize :as sr-linearize]))


(def slice-4a-mnemonics
  #{:const :var :closure :jump :branch-false :define :gensym :store-get
    :store-put :halt :return :call})


(defn slice-4a-eligible?
  [ast]
  (every? #(contains? slice-4a-mnemonics (nth % 0))
          (:vector (sr-linearize/project ast))))


(defn trace-normalize
  [value]
  (cond
    (values/continuation? value) :continuation
    (values/host-typed? value) (trace-normalize (values/payload value))
    (and (map? value) (= :closure (:type value))) {:type :closure :params (:params value)}
    (and (map? value) (contains? #{:stream-ref :cursor-ref} (:type value)))
    (select-keys value [:type :id])
    (fn? value) :host-fn
    (map? value) (into {} (map (fn [[k v]] [(trace-normalize k) (trace-normalize v)]) value))
    (vector? value) (mapv trace-normalize value)
    (set? value) (into #{} (map trace-normalize value))
    (seq? value) (mapv trace-normalize value)
    :else value))


(defn register-machine
  ([ast] (register-machine ast {}))
  ([ast opts]
   (sr/load-vector (sr/create-vm (merge {:make-stream tu/make-stream :capability-secret tu/secret} opts))
                   (:vector (sr-linearize/project ast)) code/contract)))


(defn walker-machine
  [ast]
  (walker/vm-load-rows (tu/create-vm) (vm/ast->semantic-bytecode ast) vm/ast-contract))


(defn outcome
  [machine]
  (try (let [result (vm/run machine)]
         {:value (trace-normalize (vm/value result)) :halted? (vm/halted? result)
          :blocked? (vm/blocked? result) :store (trace-normalize (vm/store result))})
       (catch #?(:cljd Object :clj Throwable :cljs :default) e
         {:error (ex-message e)})))


(defn then
  [a b]
  (corpus/app (corpus/lam '[ignored] b) a))


;; Respelled from rule-r-test/definition-programs, which is private.
(def definition-programs
  [["define then read" (then (corpus/def! 'x (corpus/lit 1))
                             (corpus/def! 'y (corpus/app (corpus/v '+) (corpus/v 'x) (corpus/lit 1)))) 2 {'x 1 'y 2}]
   ["redefine x twice reads the second value"
    (then (corpus/def! 'x (corpus/lit 1)) (then (corpus/def! 'x (corpus/lit 2)) (corpus/v 'x))) 2 {'x 2}]
   ["a definition's value is its own value" (corpus/def! 'z (corpus/lit 9)) 9 {'z 9}]
   ["a stored closure is called by name"
    (then (corpus/def! 'f (corpus/lam '[n] (corpus/app (corpus/v '*) (corpus/v 'n) (corpus/lit 2))))
          (then (corpus/def! 'r (corpus/app (corpus/v 'f) (corpus/lit 5))) (corpus/v 'r))) 10 {'r 10}]
   ["the reserved symbol as a literal value is data" (corpus/def! 'q (corpus/lit 'yin/def)) 'yin/def {'q 'yin/def}]])


(deftest b0-values
  (let [rows (filter #(slice-4a-eligible? (second %)) b0/corpus)]
    (is (= 25 (count rows)))
    (doseq [[label ast expected] rows]
      (testing label
        (doseq [machine [(register-machine ast) (walker-machine ast)]]
          (let [result (vm/run machine)]
            (is (= (trace-normalize expected) (trace-normalize (vm/value result))))
            (is (vm/halted? result))))))))


(deftest corpus-outcomes
  (let [rows (filter #(slice-4a-eligible? (second %)) corpus/programs)]
    (is (= 21 (count rows)))
    (doseq [[label ast] rows]
      (testing label (is (= (outcome (walker-machine ast)) (outcome (register-machine ast))))))))


(deftest rule-r-definitions
  (is (= 5 (count definition-programs)))
  (doseq [[label ast expected slice] definition-programs]
    (testing label
      (is (slice-4a-eligible? ast))
      (doseq [machine [(register-machine ast) (walker-machine ast)]]
        (let [result (vm/run machine)]
          (is (= expected (vm/value result)))
          (is (= slice (select-keys (vm/store result) (keys slice)))))))))


;; Slice-4a extras stay separate from the phase-3 corpus and its pinned addresses.
(def slice-4a-extra-rows
  [[:parameter-shadowing
    (corpus/app (corpus/lam '[x]
                            (corpus/app (corpus/lam '[x] (corpus/v 'x)) (corpus/lit 2)))
                (corpus/lit 1))
    2]])


(deftest extra-row-values
  (is (= 1 (count slice-4a-extra-rows)))
  (doseq [[label ast expected] slice-4a-extra-rows]
    (testing label
      (is (slice-4a-eligible? ast))
      (let [walker (vm/run (walker-machine ast))
            register (vm/run (register-machine ast))]
        (doseq [result [walker register]]
          (is (= expected (vm/value result)))
          (is (= (trace-normalize expected) (trace-normalize (vm/value result))))
          (is (vm/halted? result)))
        (is (= (trace-normalize (vm/value walker))
               (trace-normalize (vm/value register))))))))
