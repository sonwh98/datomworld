(ns dao.data.btree-protocols-test
  "Phase 2 conformance suite (docs/design/dao.data.btree.md §4, §6 Phase 2).

  Exercises BTSet exclusively through the host's own collection verbs —
  clojure.core conj/disj/get/contains?/seq/rseq/reduce/subseq/hash/= — with
  the host's sorted-set as the oracle. The §4 contract under test: equal
  sets hash equal and compare equal in both directions, BTSet works as a
  hash-map key, set-as-function invocation, reduce with early `reduced`,
  O(1) count, subseq/rsubseq bounds, and metadata round-trip."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.data.btree :as bt]))


(defn- bt-set
  "A BTSet of xs at branching factor 16 (multi-level fast), via core conj."
  [xs]
  (reduce clojure.core/conj
          (bt/sorted-set* {:cmp compare, :branching-factor 16})
          xs))


(def ^:private xs100 (vec (range 100)))


(deftest core-verbs-test
  (let [s (bt-set xs100)]
    (testing "count / seq / rseq / first"
      (is (== 100 (count s)))
      (is (= (range 100) (seq s)))
      (is (= (reverse (range 100)) (rseq s)))
      (is (== 0 (first s))))
    (testing "conj / disj through core"
      (let [s' (-> s
                   (conj 100)
                   (disj 0))]
        (is (== 100 (count s')))
        (is (= (range 1 101) (seq s')))
        ;; persistence: original untouched
        (is (= (range 100) (seq s)))))
    (testing "get / contains?"
      (is (== 42 (get s 42)))
      (is (nil? (get s 1000)))
      (is (= :nf (get s 1000 :nf)))
      (is (contains? s 42))
      (is (not (contains? s 1000))))
    (testing "into (reduce path)"
      (is (= (range 200) (seq (into s (range 100 200))))))))


(deftest empty-set-verbs-test
  (let [e (bt/sorted-set-by compare)]
    (is (zero? (count e)))
    (is (nil? (seq e)))
    (is (nil? (rseq e)))
    (is (= [] (vec e)))
    (testing "empty preserves comparator and settings"
      (let [rcmp (fn [a b] (compare b a))
            s (bt-set [])
            s2 (empty (bt-set xs100))]
        (is (zero? (count s2)))
        (is (= [5 3 1]
               (seq (into (empty (reduce clojure.core/conj
                                         (bt/sorted-set-by rcmp)
                                         [9 8 7]))
                          [1 3 5]))))
        (is (= (:branching-factor (bt/settings s))
               (:branching-factor (bt/settings s2))))))))


(deftest equality-test
  (let [s (bt-set xs100)
        s2 (bt-set (reverse xs100)) ; different insertion order
        oracle (apply sorted-set xs100)]
    (testing "= in both directions, against sorted and hashed sets"
      (is (= s s2))
      (is (= s oracle))
      (is (= oracle s))
      (is (= s (set xs100)))
      (is (= (set xs100) s))
      (is (not= s (apply sorted-set (range 99))))
      (is (not= s (conj oracle 100)))
      (is (not= s [0 1 2])))
    (testing "hash agrees with the host's own sets"
      (is (== (hash oracle) (hash s)))
      (is (== (hash s) (hash s2)))
      (is (== (hash (hash-set)) (hash (bt-set [])))))
    (testing "BTSet as a hash-map key"
      (let [m {s :found}]
        (is (= :found (get m s2)))
        (is (= :found (get m oracle)))))))


(deftest invoke-test
  (let [s (bt-set [1 2 3])]
    (is (== 2 (s 2)))
    (is (nil? (s 9)))
    (is (= :nf (s 9 :nf)))
    (is (= [1 2 nil] (map s [1 2 9])))
    (is (= [1 2] (filter s [1 5 2 9])))
    #?(:clj (is (== 2 (apply s [2]))))))


#?(:cljd nil
   :clj (deftest java-set-contract-test
          (let [s (bt-set [1 2 3])
                js (java.util.HashSet. [1 2 3])]
            (is (== 3 (.size s)))
            (is (false? (.isEmpty s)))
            (is (true? (.isEmpty (bt-set []))))
            (is (.contains s 2))
            (is (not (.contains s 9)))
            (is (.containsAll s [1 3]))
            (is (not (.containsAll s [1 9])))
            (is (= [1 2 3] (vec (.toArray s))))
            (is (= [1 2 3] (iterator-seq (.iterator s))))
            (testing "java.util.Set equals/hashCode contract, both directions"
              (is (.equals s js))
              (is (.equals js s))
              (is (== (.hashCode js) (.hashCode s))))
            (testing "mutators throw"
              (is (thrown? UnsupportedOperationException (.add s 4)))
              (is (thrown? UnsupportedOperationException (.remove s 1)))
              (is (thrown? UnsupportedOperationException (.clear s)))))))


(deftest reduce-test
  (let [s (bt-set xs100)]
    (is (== 4950 (reduce + s)))
    (is (== 4950 (reduce + 0 s)))
    (is (== 0 (reduce + 0 (bt-set []))))
    (is (== 0 (reduce + (bt-set []))) "no-init reduce on empty calls (f)")
    (is (= :init (reduce (fn [a _] a) :init (bt-set []))))
    (testing "early termination via reduced"
      (is (= :big
             (reduce (fn [acc x] (if (> (+ acc x) 10) (reduced :big) (+ acc x)))
                     0
                     s)))
      (is (== 3 (reduce (fn [acc x] (if (== x 3) (reduced x) acc)) 0 s))))
    (testing "transduce over BTSet"
      (is (= (transduce (map inc) + 0 (range 100))
             (transduce (map inc) + 0 s))))))


(deftest subseq-rsubseq-test
  (let [xs (range 0 200 3)
        s (bt-set xs)
        oracle (apply sorted-set xs)]
    (doseq [k [-5 0 1 3 99 100 197 198 300]]
      (testing (str "single bound, k=" k)
        (is (= (seq (subseq oracle > k)) (seq (subseq s > k))) (str "> " k))
        (is (= (seq (subseq oracle >= k)) (seq (subseq s >= k))) (str ">= " k))
        (is (= (seq (subseq oracle < k)) (seq (subseq s < k))) (str "< " k))
        (is (= (seq (subseq oracle <= k)) (seq (subseq s <= k))) (str "<= " k))
        (is (= (seq (rsubseq oracle > k)) (seq (rsubseq s > k))) (str "r> " k))
        (is (= (seq (rsubseq oracle >= k)) (seq (rsubseq s >= k)))
            (str "r>= " k))
        (is (= (seq (rsubseq oracle < k)) (seq (rsubseq s < k))) (str "r< " k))
        (is (= (seq (rsubseq oracle <= k)) (seq (rsubseq s <= k)))
            (str "r<= " k))))
    (doseq [[lo hi] [[0 199] [3 99] [4 98] [-10 500] [99 99] [100 100] [98 4]]
            [t1 t2] [[> <] [>= <=] [> <=] [>= <]]]
      (testing (str "double bound " lo " " hi)
        (is (= (seq (subseq oracle t1 lo t2 hi)) (seq (subseq s t1 lo t2 hi))))
        (is (= (seq (rsubseq oracle t1 lo t2 hi))
               (seq (rsubseq s t1 lo t2 hi))))))))


(deftest sorted-surface-test
  (let [s (bt-set xs100)]
    #?(:cljd nil
       :clj (do (is (identical? compare (.comparator ^clojure.lang.Sorted s)))
                (is (== 7 (.entryKey ^clojure.lang.Sorted s 7))))
       :cljs (is (= [50 51 52] (take 3 (-sorted-seq-from s 50 true)))))
    ;; slice with an explicit overriding comparator still works via core
    ;; path
    (is (= [42] (bt/slice s 42 42)))))


(deftest metadata-test
  (let [s (bt-set [1 2 3])
        sm (with-meta s {:origin :test})]
    (is (nil? (meta s)))
    (is (= {:origin :test} (meta sm)))
    (is (= s sm) "meta does not affect equality")
    (is (= [1 2 3] (seq sm)))
    (testing "meta survives conj/disj"
      (is (= {:origin :test} (meta (conj sm 4))))
      (is (= {:origin :test} (meta (disj sm 1)))))))


(deftest print-test
  (let [s (bt-set [3 1 2])]
    (is (= "#{1 2 3}" (pr-str s)))
    (is (= "#{}" (pr-str (bt-set []))))
    (is (= (pr-str (sorted-set 1 2 3)) (pr-str s)))))


(deftest large-set-protocol-smoke-test
  ;; the protocol surface over a multi-level tree (bf 16, 3+ levels)
  (let [n 5000
        s (bt-set (range n))
        oracle (apply sorted-set (range n))]
    (is (= oracle s))
    (is (== (hash oracle) (hash s)))
    (is (= (subseq oracle >= 2500 < 2600) (subseq s >= 2500 < 2600)))
    (is (== (dec n) (reduce (fn [_ x] x) nil s)) "reduce visits in order")))
