(ns yin.vm.semantic-register.walker-trace-test
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.parity-test :as b0]
            [yin.vm.semantic-register.corpus :as corpus]
            [yin.vm.semantic-register.parity-test :as parity]))


(def fuel-bound 100000)


(defn extract
  "Tagged events, read only from the instruction's explicit destination."
  [inst before after]
  (let [op (first inst)]
    (cond
      (:blocked? after) {:event? false}
      (contains? #{:const :var :closure :gensym :store-get :store-put :define} op)
      {:event? true :value (get (:window after) (nth inst 1))}
      (and (= :call op) (fn? (get (:window before) (nth inst 2))))
      (if (nth inst 4)
        (if-let [frame (peek (:k before))]
          {:event? true :value (get (:window after) (:rd frame))}
          {:event? true :value (:value after)})
        {:event? true :value (get (:window after) (nth inst 1))})
      :else {:event? false})))


(defn trace-machine
  [machine register?]
  (loop [machine machine events [] n 0]
    (cond
      (engine/halted-with-empty-queue? machine)
      (conj events [:halt (parity/trace-normalize (vm/value machine))])
      (:blocked? machine) events
      (= n fuel-bound) (conj events [:fuel-exhausted n])
      :else
      (let [{:keys [segment pc]} (vm/control machine)
            inst (when register? (nth (get-in machine [:code segment :vector]) pc))
            park? (if register? (= :park (first inst)) (= :vm/park (:type (:control machine))))
            stepped (try {:machine (vm/step machine)}
                         (catch #?(:cljd Object :clj Throwable :cljs :default) e
                           {:error (ex-message e)}))]
        (if (contains? stepped :error)
          (conj events [:error (:error stepped)])
          (let [after (:machine stepped)
                event (if register? (extract inst machine after)
                          (if (and (nil? (:control after)) (not (:blocked? after)))
                            {:event? true :value (:value after)} {:event? false}))]
            (if park?
              (conj events [:park (:id (:value after))])
              (recur after (if (:event? event)
                             (conj events [:value (parity/trace-normalize (:value event))]) events)
                     (inc n)))))))))


(defn trace-walker
  [ast]
  (trace-machine (parity/walker-machine ast) false))


(defn trace-register
  [ast]
  (trace-machine (parity/register-machine ast) true))


(defn first-difference
  [a b]
  (first (keep (fn [i]
                 (when (not= (get a i ::missing) (get b i ::missing))
                   {:index i :walker (get a i ::missing) :register (get b i ::missing)}))
               (range (max (count a) (count b))))))


(def trace-only-rows
  [[:literal-none (corpus/lit :none) [[:value :none] [:halt :none]]]
   [:parked-shaped-literal (corpus/lit {:type :parked-continuation :id :parked-0})
    [[:value {:type :parked-continuation :id :parked-0}]
     [:halt {:type :parked-continuation :id :parked-0}]]]
   [:literal-none-in-call (corpus/app (corpus/lam '[x] (corpus/v 'x)) (corpus/lit :none))
    '[[:value {:type :closure :params [x]}] [:value :none] [:value :none] [:halt :none]]]
   [:main-tail-primitive (corpus/tail (corpus/app (corpus/v '+) (corpus/lit 1) (corpus/lit 2)))
    [[:value :host-fn] [:value 1] [:value 2] [:value 3] [:halt 3]]]])


(deftest traces-align
  (let [rows (filter #(parity/slice-4a-eligible? (second %))
                     (concat b0/corpus corpus/programs parity/definition-programs))]
    (is (= 51 (count rows)))
    (doseq [[label ast] rows]
      (testing (str label)
        (let [a (trace-walker ast) b (trace-register ast)]
          (is (= a b) (pr-str (first-difference a b)))
          (is (not-any? #(= :fuel-exhausted (first %)) (concat a b))))))))


(deftest trace-oracle-pinned-values
  (is (= 4 (count trace-only-rows)))
  (doseq [[label ast expected] trace-only-rows]
    (testing label
      (is (parity/slice-4a-eligible? ast))
      (is (= expected (trace-walker ast)))
      (is (= expected (trace-register ast))))))


(deftest return-is-administrative
  (let [ast (second (first (filter #(= "lambda application" (first %)) b0/corpus)))
        expected '[[:value {:type :closure :params [x]}] [:value 10] [:value :host-fn]
                   [:value 10] [:value 1] [:value 11] [:halt 11]]]
    (is (= expected (trace-walker ast)))
    (is (= expected (trace-register ast)))
    (is (= 6 (count (filter #(= :value (first %)) (trace-register ast)))))))
