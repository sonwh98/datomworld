(ns yin.vm.semantic.parity-test
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as walker]
            [yin.vm.parity-test :as b0]
            [yin.vm.test-utils :as tu]
            [yin.vm.values :as values]
            [yin.vm.semantic :as sr]
            [yin.vm.semantic.code :as code]
            [yin.vm.semantic.corpus :as corpus :refer [lit v lam app tail if-node def!]]
            [yin.vm.semantic.linearize :as sr-linearize]))


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
  (let [rows b0/corpus]
    (is (= 26 (count rows)))
    (doseq [[label ast expected] rows]
      (testing label
        (doseq [machine [(register-machine ast) (walker-machine ast)]]
          (let [result (vm/run machine)]
            (is (= (trace-normalize expected) (trace-normalize (vm/value result))))
            (is (vm/halted? result))))))))


(deftest corpus-outcomes
  (let [rows corpus/programs]
    (is (= 32 (count rows)))
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
    2]
   [:caller-env-after-return
    (corpus/app (corpus/lam '[x]
                            (corpus/app (corpus/v 'list)
                                        (corpus/app (corpus/lam '[x] (corpus/lit 2)) (corpus/lit 9))
                                        (corpus/v 'x)))
                (corpus/lit 1))
    '(2 1)]])


(deftest extra-row-values
  (is (= 2 (count slice-4a-extra-rows)))
  (doseq [[label ast expected] slice-4a-extra-rows]
    (testing label
      (is (slice-4a-eligible? ast))
      (let [walker (vm/run (walker/vm-load-rows (tu/create-vm {:env {'list list}})
                                                (vm/ast->semantic-bytecode ast) vm/ast-contract))
            register (vm/run (register-machine ast {:env {'list list}}))]
        (doseq [result [walker register]]
          (is (= expected (vm/value result)))
          (is (= (trace-normalize expected) (trace-normalize (vm/value result))))
          (is (vm/halted? result)))
        (is (= (trace-normalize (vm/value walker))
               (trace-normalize (vm/value register))))))))


(def capture {:type :vm/current-continuation})


(defn with-k
  [body]
  (app (lam '[r] body) capture))


;; The five reviewed continuation programs; arity-two is checked separately.
(def continuation-programs
  [[:escape (app (v '+)
                 (lit 1)
                 (with-k (if-node (app (v '=) (v 'r) (lit 7))
                                  (app (v '+) (v 'r) (lit 1))
                                  (app (v '+)
                                       (lit 1000)
                                       (app (v 'r) (lit 7)))))) 9]
   [:tail-invoke (app (v '+)
                      (lit 1)
                      (with-k (if-node (app (v '=) (v 'r) (lit 7))
                                       (v 'r)
                                       (tail (app (v 'r) (lit 7)))))) 8]
   [:re-entry (then
                (def! 'count (lit 0))
                (app (lam ['x]
                          (then (if-node (app (v '=) (v 'count) (lit 0))
                                         (def! 'saved (v 'x))
                                         (lit nil))
                                (if-node (app (v '<) (v 'count) (lit 3))
                                         (then (def! 'count
                                                 (app (v '+) (v 'count) (lit 1)))
                                               (app (v 'saved)
                                                    (app (v '*)
                                                         (lit 10)
                                                         (v 'count))))
                                         (app (v 'conj)
                                              (app (v 'conj) (lit []) (v 'x))
                                              (v 'count)))))
                     (app (lam [] capture)))) [30 3]]
   [:store-after-invoke (with-k (if-node (app (v '=) (v 'r) (lit 1))
                                         (app (v 'conj)
                                              (app (v 'conj) (lit []) (v 'r))
                                              (v 'mark))
                                         (then (def! 'mark (lit :written))
                                               (app (v 'r) (lit 1))))) [1 :written]]
   [:arity-zero (with-k (if-node (app (v '=) (v 'r) (lit 1))
                                 (v 'r)
                                 (apply app (v 'r)
                                        (repeat 0 (lit 1))))) [:thrown "Continuation expects exactly one argument"]]])


(defn continuation-outcome
  [machine]
  (try
    (loop [machine machine steps 0]
      (cond
        (vm/halted? machine) (outcome machine)
        (vm/blocked? machine) {:error "Continuation parity test blocked"}
        (= steps 10000) {:error "Continuation parity step bound exhausted"}
        :else (recur (vm/step machine) (inc steps))))
    (catch #?(:cljd Object :clj Throwable :cljs :default) e
      {:error (ex-message e)})))


(deftest continuation-outcomes
  (is (= 5 (count continuation-programs)))
  (doseq [[label ast expected] continuation-programs]
    (testing label
      (let [w (continuation-outcome (walker-machine ast)) r (continuation-outcome (register-machine ast))]
        (is (= w r))
        (if (and (vector? expected) (= :thrown (first expected)))
          (is (= (second expected) (:error r)))
          (is (= expected (:value r))))))))


(deftest ffi-round-trip-parity
  (let [ast {:type :dao.stream.apply/call :op :op/echo :operands [(lit 42)]}
        run (fn [register?]
              (let [seen (atom []) opts {:bridge {:op/echo (fn [x] (swap! seen conj x) x)}}
                    machine (if register? (register-machine ast opts)
                                (walker/vm-load-rows (tu/create-vm opts) (vm/ast->semantic-bytecode ast) vm/ast-contract))]
                [(outcome machine) @seen]))]
    (is (= (run false) (run true)))
    (is (= 42 (get-in (run true) [0 :value])))
    (is (= [42] (second (run true))))))


(deftest stream-run-parity
  (doseq [register? [false true]]
    (let [load (fn [machine ast]
                 (if register? (sr/load-ast machine (vm/ast->datoms ast) vm/ast-contract)
                     (walker/vm-load-rows machine (vm/ast->semantic-bytecode ast) vm/ast-contract)))
          base (if register? (register-machine {:type :stream/make :buffer 4})
                   (walker-machine {:type :stream/make :buffer 4}))
          made (vm/run base) s (vm/value made)
          read {:type :stream/next :source {:type :stream/cursor :source (lit s)}}
          blocked (vm/run (load made read))
          put (vm/run (load made {:type :stream/put :target (lit s) :val (lit 99)}))
          got (vm/run (load put read))
          closed (vm/run (load got {:type :stream/close :source (lit s)}))]
      (is (vm/blocked? blocked))
      (is (= :yin/blocked (vm/value blocked)))
      (is (= 99 (vm/value put)))
      (is (= 99 (vm/value got)))
      (is (nil? (vm/value closed)))
      (is (vm/halted? closed)))))
