(ns yin.vm.semantic-register.vm-test
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.values :as values]
            [yin.vm.semantic-register :as sr]
            [yin.vm.semantic-register.analysis :as analysis]
            [yin.vm.semantic-register.code :as code]
            [yin.vm.semantic-register.code-test :as code-test]
            [yin.vm.semantic-register.corpus :as corpus]
            [yin.vm.semantic-register.parity-test :as parity]))


(defn error-data
  [f]
  (try (f) nil (catch #?(:cljd Object :clj Throwable :cljs :default) e (ex-data e))))


(defn step-n
  [machine n]
  (nth (iterate vm/step machine) n))


(defn golden
  [label]
  (:vector (get corpus/goldens label)))


(defn load-golden
  [label opts]
  (sr/load-vector (sr/create-vm opts) (golden label) code/contract))


(deftest stamp-before-rules
  (doseq [[stamp rule] [[nil :contract-missing] ["v3" :contract-mismatch]]]
    (is (= rule (:rule (error-data #(sr/load-vector (sr/create-vm) [] stamp)))))))


(deftest loader-refusals-are-the-validators
  (doseq [[item expected vector] code-test/refusals]
    (testing (str item " " vector)
      (is (= expected (:defect (error-data #(sr/load-vector (sr/create-vm) vector code/contract))))))))


(deftest image-shape
  (let [machine (load-golden :worked-example {}) seg (:program machine)
        image (get (:code machine) seg)]
    (is (= #{:vector :address :bodies :analysis :segment :length} (set (keys image))))
    (is (= (golden :worked-example) (:vector image)))
    (is (= (analysis/analyze (:vector image)) (:analysis image)))
    (is (= (:address (get corpus/goldens :worked-example)) (:address image)))
    (is (= seg (get (:code-aliases machine) (:address image))))
    (is (= {:segment seg :pc 0} (vm/control machine)))
    (is (= {} (:window machine)))
    (is (nil? (:k machine)))
    (is (= 7 (:length image)))
    (is (not (contains? machine :stack)))))


(deftest segment-ids
  (let [a (load-golden :literal {}) floor (vm/loaded-code-floor (:code a))
        b (sr/load-vector a (golden :variable) code/contract)
        reload (sr/load-vector b (golden :literal) code/contract {:id 999})]
    (is (< (:program b) floor))
    (is (= (:program a) (:program reload)))
    (is (= (:code b) (:code reload)))
    (is (re-find #"already holds different code"
                 (try (sr/load-vector a (golden :variable) code/contract {:id (:program a)}) ""
                      (catch #?(:cljd Object :clj Throwable :cljs :default) e (ex-message e)))))))


(deftest attach-load-ast-and-reset
  (let [a (step-n (load-golden :literal {}) 1)
        b (sr/attach-image a (golden :variable) code/contract)
        fields [:control :program :env :window :k :value :halted? :blocked? :parked :wait-set :ready-queue]
        reset (vm/reset b)
        ast-loaded (sr/load-ast (sr/create-vm) (vm/ast->datoms (corpus/lit 42)) vm/ast-contract)]
    (is (= (select-keys a fields) (select-keys b fields)))
    (is (= 2 (count (:code b))))
    (is (= b (sr/attach-image b (golden :variable) code/contract)))
    (is (= {} (:window reset)))
    (is (nil? (:value reset)))
    (is (= (:code b) (:code reset)))
    (is (= 42 (vm/value (vm/run ast-loaded))))
    (is (= :contract-mismatch (:rule (error-data #(sr/load-ast (sr/create-vm) [] "bad")))))
    (is (some? (error-data #(vm/eval a (corpus/lit 1)))))
    (is (= 42 (vm/value (vm/eval a nil))))))


(deftest one-step-one-write
  (let [f vector machine (load-golden :worked-example {:env {'f f 'g inc 'x 1 'y 2}})
        after (vm/step machine)]
    (is (= {:segment (:program machine) :pc 1} (vm/control after)))
    (is (= {1 f} (:window after)))))


(deftest return-frame-shape
  (let [machine (load-golden :worked-example {:env {'f vector 'x nil 'y 2}})
        seg (:program machine)
        c (values/closure (:owner machine) {:type :closure :params '[z] :segment seg :entry 0 :env {}})
        machine (assoc-in machine [:env 'g] c)
        before (step-n machine 3) after (vm/step before)
        frame (peek (:k after))
        saved (select-keys (:window before) (analysis/saved (get-in before [:code seg :analysis]) 4 2))]
    (is (= 3 (get-in before [:control :pc])))
    (is (= {:type :return :segment seg :pc 4 :env (:env before) :window saved :rd 2} frame))
    (is (= {1 vector} (:window frame)))
    (is (= 2 (:rd frame)))
    (is (= {} (:window after)))))


(deftest tail-call-grows-no-frame
  (let [ast (corpus/app (corpus/lam '[x] (corpus/tail (corpus/app (corpus/lam '[y] (corpus/v 'y)) (corpus/v 'x)))) (corpus/lit 7))
        before (loop [m (parity/register-machine ast)]
                 (let [{:keys [segment pc]} (:control m) inst (nth (get-in m [:code segment :vector]) pc)]
                   (if (and (= :call (first inst)) (nth inst 4)) m (recur (vm/step m)))))
        after (vm/step before)]
    (is (= 1 (count (:k before))))
    (is (= (:k before) (:k after)))
    (is (= {} (:window after)))
    (is (= 7 (vm/value (vm/run after))))))


(deftest tail-countdown
  (let [ast (parity/then
              (corpus/def! 'loop (corpus/lam '[n]
                                             (corpus/if-node (corpus/app (corpus/v '=) (corpus/v 'n) (corpus/lit 0))
                                                             (corpus/lit "done")
                                                             (corpus/tail (corpus/app (corpus/v 'loop)
                                                                                      (corpus/app (corpus/v '-) (corpus/v 'n) (corpus/lit 1)))))))
              (corpus/tail (corpus/app (corpus/v 'loop) (corpus/lit 100000))))
        ;; Observe every instruction so the bound includes every closure entry.
        bounded? (atom true)
        machine (parity/register-machine ast)
        result (loop [m machine]
                 (if (vm/halted? m) m
                     (let [next-state (vm/step m)]
                       (when (> (count (:k next-state)) 1) (reset! bounded? false))
                       (recur next-state))))]
    (is @bounded?)
    (is (= "done" (vm/value result)))
    (is (vm/halted? result))
    (is (nil? (:k result)))))


(deftest return-writes-rd-once
  (let [m (parity/register-machine (corpus/app (corpus/lam '[x] (corpus/v 'x)) (corpus/lit nil)))
        before (loop [m m]
                 (let [{:keys [segment pc]} (:control m)]
                   (if (= :return (first (nth (get-in m [:code segment :vector]) pc))) m (recur (vm/step m)))))
        frame (peek (:k before)) after (vm/step before)]
    (is (= (:window frame) (dissoc (:window after) (:rd frame))))
    (is (contains? (:window after) (:rd frame)))
    (is (nil? (get (:window after) (:rd frame))))
    (is (nil? (:k after)))))


(deftest halt-shape
  (doseq [vector [[[:const 0 nil] [:halt 0]] [[:const 0 false] [:halt 0]]]]
    (let [m (sr/load-vector (sr/create-vm) vector code/contract)
          m (assoc m :env {engine/store-of-key 'module 'x 1}) after (vm/run m)]
      (is (nil? (:control after))) (is (nil? (:k after)))
      (is (:halted? after)) (is (vm/halted? after))
      (is (= (nth (first vector) 2) (:value after)))
      (is (= {'x 1} (:env after))) (is (= {} (:window after)))))
  (let [ast (corpus/tail (corpus/app (corpus/lam '[] (corpus/lit 8))))
        result (vm/run (parity/register-machine ast {:env {engine/store-of-key 'module 'x 1}}))]
    (is (= 8 (:value result)))
    (is (nil? (:control result)))
    (is (nil? (:k result)))
    (is (vm/halted? result))
    (is (= {'x 1} (:env result))))
  (let [m (sr/create-vm) after (sr/register-restore (assoc m :env {engine/store-of-key 'm 'x 1})
                                                    {:k nil :deliver {:deliver :return}} 9)]
    (is (= 9 (:value after))) (is (= {'x 1} (:env after))) (is (vm/halted? after))))


(deftest tail-primitive-returns-through-k
  (let [ast (corpus/tail (corpus/app (corpus/lam '[n] (corpus/tail (corpus/app (corpus/v '+) (corpus/v 'n) (corpus/lit 1)))) (corpus/lit 1)))
        states (loop [m (parity/register-machine ast) states []]
                 (if (vm/halted? m) (conj states m) (recur (vm/step m) (conj states m))))
        body-start (get-in (first states) [:code (:program (first states)) :bodies 1 :start])]
    (is (= 2 (vm/value (peek states))))
    (is (every? #(not (contains? (:window %) 0))
                (filter #(and (:control %) (>= (get-in % [:control :pc]) body-start)) states)))))


(deftest define-routes-to-the-module-store
  (let [ast (corpus/app (corpus/lam '[] (corpus/def! 'answer (corpus/lit 42))))
        machine (parity/register-machine ast {:env {engine/store-of-key 'foo}})
        result (vm/run machine)]
    (is (= 42 (vm/value result)))
    (is (= 42 (get-in result [:module-stores 'foo 'answer])))
    (is (not (contains? (:store result) 'answer)))))


(deftest branch-arms-share-rd
  (doseq [[label env expected rd] [[:if {'c nil} 2 0] [:if {'c false} 2 0] [:if {'c true} 1 0]
                                   [:nested-if-same-rd {'a true 'b false 'c false} 2 0]
                                   [:nested-if-same-rd {'a false 'b false 'c true} 3 0]
                                   [:if-operand {'f vector 'c false 'y 9} [2 9] 2]
                                   [:if-operand {'f vector 'c true 'y 9} [1 9] 2]]]
    (testing label
      (let [states (loop [m (load-golden label {:env env}) states []]
                     (if (vm/halted? m) (conj states m) (recur (vm/step m) (conj states m))))
            writes (keep #(when (contains? (:window %) rd) (get (:window %) rd)) states)]
        (is (= expected (vm/value (peek states))))
        (is (seq writes))
        (is (= (if (= :if-operand label) (first expected) expected) (first writes)))
        (when (= :if-operand label) (is (= (first expected) (first writes)))))))
  (let [m (load-golden :if-in-tail {:env {'loop identity}})
        before (loop [m m]
                 (let [{:keys [segment pc]} (:control m) inst (nth (get-in m [:code segment :vector]) pc)]
                   (if (and (= :call (first inst)) (nth inst 4)) m (recur (vm/step m)))))]
    (is (= #{1 2 3 4 5 6 7 8 9} (set (keys (:window before)))))
    (is (= [5 7 8 9 6]
           (mapv second (filter #(contains? #{:var :const :call} (first %))
                                (subvec (golden :if-in-tail) 11 16)))))))


(deftest application-refusals
  (let [ast (corpus/app (corpus/v 'f)) m (parity/register-machine ast {:env {'f 3}})
        foreign (values/closure :other-owner {:type :closure :params [] :entry 0 :segment (:program m) :env {}})]
    (is (= :not-applicable (:reason (error-data #(vm/run m)))))
    (is (= {:reason :foreign-value :kind :closure}
           (select-keys (error-data #(vm/run (assoc-in m [:env 'f] foreign))) [:reason :kind])))))


(deftest ready-for-ingress
  (is (engine/ready-for-ingress? (sr/create-vm)))
  (is (engine/ready-for-ingress? (vm/run (load-golden :literal {})))))


(deftest restore-delivery
  (let [base (sr/create-vm) frame {:type :return :segment -1 :pc 3 :env {'x 1} :window {1 nil} :rd 2}
        rd (sr/register-restore base {:segment -1 :pc 4 :env {} :window {1 nil} :k nil :deliver {:deliver :rd :rd 0}} false)
        ret (sr/register-restore base {:k [frame] :deliver {:deliver :return} :value 9})]
    (is (= {1 nil 0 false} (:window rd)))
    (is (= {1 nil 2 9} (:window ret)))
    (is (= {:segment -1 :pc 3} (:control ret)))
    (is (nil? (:k ret)))
    (is (= :deliver (:rule (error-data #(sr/register-restore base {} 1)))))))


(deftest deferred-arms
  (doseq [ast [{:type :vm/park} {:type :vm/current-continuation}
               {:type :stream/make} {:type :vm/resume :parked-id :p :val (corpus/lit 1)}]]
    (let [m (parity/register-machine ast) op (first (last (butlast (get-in m [:code (:program m) :vector]))))]
      (is (= {:reason :not-in-slice-4a :op op}
             (select-keys (error-data #(vm/run m)) [:reason :op])))))
  (let [effect (parity/register-machine (corpus/app (corpus/v 'require) (corpus/lit 'foo)))
        m (parity/register-machine (corpus/app (corpus/v 'k)))
        k (values/continuation (:owner m) {})]
    (is (= {:reason :not-in-slice-4a :op :call} (error-data #(vm/run effect))))
    (is (= {:reason :not-in-slice-4a :op :call} (error-data #(vm/run (assoc-in m [:env 'k] k)))))))
