(ns dao.data.btree-test
  "Phase 1 tests for dao.data.btree (docs/design/dao.data.btree.md §6).

  Generative invariant tests over seeded random insert/delete runs, with
  clojure.core sorted sets as the oracle. Generators are hand-rolled and
  seeded (minstd LCG) so the same runs execute identically on JVM, Node
  cljs, and cljd — no test.check dependency (Phase 1 preflight fallback).

  Invariants checked by walking the tree:
  - height balance: all leaves at the same depth
  - fill bounds: non-root nodes hold >= (quot branching-factor 2) and
    <= branching-factor keys; a root branch has >= 2 children
  - order: keys strictly ascending within every node
  - separator contract: a branch key equals its child's max key
  - count: BTSet count equals the sum of leaf lengths and the oracle's"
  (:require [clojure.test :refer [deftest is testing]]
            [dao.data.arrays :as arr]
            [dao.data.btree :as bt]
            [dao.data.psset-fixtures :as fx]))


;; ---------------------------------------------------------------------------
;; Seeded PRNG (Park-Miller minstd: products stay < 2^47, safe in JS doubles)

(def ^:private prng-modulus 2147483647)


(defn- next-seed
  [seed]
  (rem (* 48271 seed) prng-modulus))


(defn- op-seq
  "Deterministic vector of [:conj k] / [:disj k] ops (2/3 conj-weighted)
   with keys drawn from [0, domain)."
  [seed n domain]
  (loop [ops []
         s seed
         i 0]
    (if (== i n)
      ops
      (let [s1 (next-seed s)
            k (rem s1 domain)
            s2 (next-seed s1)
            op (if (< (rem s2 3) 2) :conj :disj)]
        (recur (conj ops [op k]) s2 (inc i))))))


(def ^:private op-count
  #?(:cljd 10000
     :clj 100000
     :cljs 10000))


(defn- pseudo-shuffle
  "Deterministic Fisher-Yates over the LCG; cljd-safe (no core shuffle)."
  [seed coll]
  (let [v (vec coll)
        n (count v)]
    (loop [v v
           s seed
           i (dec n)]
      (if (pos? i)
        (let [s' (next-seed s)
              j (rem s' (inc i))]
          (recur (assoc v
                        i (v j)
                        j (v i))
                 s'
                 (dec i)))
        v))))


;; ---------------------------------------------------------------------------
;; Invariant walker

(defn- check-node
  "Validates the subtree rooted at node; returns {:count n :depth d}."
  [node cmp bf root?]
  (let [min-fill (quot bf 2)
        ks (bt/node-keys-vec node)
        len (count ks)]
    (is (<= len bf) "node exceeds branching factor")
    (is (every? #(neg? (cmp (ks %) (ks (inc %)))) (range (dec len)))
        "keys not strictly ascending within node")
    (if (bt/branch? node)
      (do (is (>= len (if root? 2 min-fill)) "branch underfilled")
          (is (every? #(zero? (cmp (ks %)
                                   (bt/node-lim-key (bt/node-child node %))))
                      (range len))
              "branch key does not equal child's max key")
          (let [subs (mapv #(check-node (bt/node-child node %) cmp bf false)
                           (range len))]
            (is (apply = (map :depth subs)) "leaves at differing depths")
            {:count (reduce + (map :count subs)),
             :depth (inc (:depth (first subs)))}))
      (do (when-not root? (is (>= len min-fill) "leaf underfilled"))
          {:count len, :depth 0}))))


(defn- check-set
  [s]
  (let [root (bt/root s)
        bf (:branching-factor (bt/settings s))
        cmp (bt/comparator s)]
    (if (zero? (bt/count s))
      (is (zero? (bt/node-len root)) "empty set with non-empty root")
      (let [{:keys [count]} (check-node root cmp bf true)]
        (is (== count (bt/count s)) "tree count differs from BTSet cnt")))))


;; ---------------------------------------------------------------------------
;; Basics

(deftest empty-set-test
  (let [s (bt/sorted-set-by compare)]
    (is (zero? (bt/count s)))
    (is (nil? (bt/seq s)))
    (is (nil? (bt/lookup s 1)))
    (is (false? (bt/contains? s 1)))
    (is (nil? (bt/slice s nil nil)))
    (is (nil? (bt/slice s 0 10)))
    (is (nil? (bt/rslice s 10 0)))
    (is (identical? s (bt/disj s 1)) "disj on empty returns the same set")
    (check-set s)))


(deftest basic-conj-test
  (doseq [bf [16 32]]
    (let [xs (pseudo-shuffle 11 (range 200))
          s (reduce bt/conj
                    (bt/sorted-set* {:cmp compare, :branching-factor bf})
                    xs)]
      (is (== 200 (bt/count s)))
      (is (= (range 200) (bt/seq s)))
      (is (every? #(bt/contains? s %) xs))
      (is (not (bt/contains? s 200)))
      (is (not (bt/contains? s -1)))
      (check-set s))))


(deftest nil-element-rejected-test
  ;; nil is lookup's absence signal, so it is not a legal element
  (let [s (bt/sorted-set-by compare)]
    (is (thrown? #?(:cljd cljd.core/ExceptionInfo
                    :clj Exception
                    :cljs js/Error)
          (bt/conj s nil)))
    (is (thrown? #?(:cljd cljd.core/ExceptionInfo
                    :clj Exception
                    :cljs js/Error)
          (bt/from-sequential compare [1 nil 2])))))


(deftest identity-on-no-op-test
  (let [s (reduce bt/conj (bt/sorted-set-by compare) (range 100))]
    (is (identical? s (bt/conj s 50)) "conj of present key returns same set")
    (is (identical? s (bt/disj s 1000)) "disj of absent key returns same set")))


(deftest lookup-stored-element-test
  ;; lookup returns the *stored* element under the comparator's equality,
  ;; not the probe
  (let [cmp (fn [a b] (compare (first a) (first b)))
        s (-> (bt/sorted-set-by cmp)
              (bt/conj [1 :a])
              (bt/conj [2 :b])
              (bt/conj [3 :c]))]
    (is (= [2 :b] (bt/lookup s [2 :whatever])))
    (is (nil? (bt/lookup s [4 :nope])))
    ;; conj of an element equal under cmp is a no-op (psset semantics)
    (is (identical? s (bt/conj s [2 :zzz])))))


(deftest custom-comparator-test
  (let [rcmp (fn [a b] (compare b a))
        s (reduce bt/conj
                  (bt/sorted-set-by rcmp)
                  (pseudo-shuffle 22 (range 100)))]
    (is (= (reverse (range 100)) (bt/seq s)))
    (check-set s)
    ;; explicit comparator on slice
    (is (= [50 49 48] (take 3 (bt/slice s 50 nil rcmp))))))


;; ---------------------------------------------------------------------------
;; Bulk build

(deftest from-sequential-test
  (doseq [bf [16 32 512]]
    (let [xs (pseudo-shuffle 33 (concat (range 1000) (range 500))) ; with
          ;; duplicates
          s (bt/from-sequential compare xs {:branching-factor bf})]
      (is (== 1000 (bt/count s)))
      (is (= (range 1000) (bt/seq s)))
      (check-set s)))
  (testing "empty and tiny"
    (is (nil? (bt/seq (bt/from-sequential compare []))))
    (is (= [1] (bt/seq (bt/from-sequential compare [1 1 1]))))
    (is (= [1 2] (bt/seq (bt/from-sequential compare [2 1 2]))))))


(deftest bulk-build-then-mutate-test
  (let [s (bt/from-sequential compare (range 0 2000 2) {:branching-factor 16})]
    (check-set s)
    (let [s' (reduce bt/conj s (range 1 2000 2))]
      (is (= (range 2000) (bt/seq s')))
      (check-set s'))
    (let [s'' (reduce bt/disj s (range 0 1000 2))]
      (is (= (range 1000 2000 2) (bt/seq s'')))
      (check-set s''))))


;; ---------------------------------------------------------------------------
;; Generative: random ops against the core sorted-set oracle

(deftest generative-invariants-test
  (doseq [bf [16 32]
          seed [42 987654321]]
    (testing (str "bf=" bf " seed=" seed)
      (let [n op-count
            domain (max 16 (quot n 4))
            ops (op-seq seed n domain)
            check-every (max 1 (quot n 10))
            final (loop [s (bt/sorted-set* {:cmp compare, :branching-factor bf})
                         oracle (sorted-set)
                         i 0]
                    (if (== i n)
                      [s oracle]
                      (let [[op k] (ops i)
                            s' (case op
                                 :conj (bt/conj s k)
                                 :disj (bt/disj s k))
                            o' (case op
                                 :conj (conj oracle k)
                                 :disj (disj oracle k))]
                        (when (zero? (rem (inc i) check-every))
                          (is (== (count o') (bt/count s'))
                              (str "count diverged at op " i))
                          (check-set s'))
                        (recur s' o' (inc i)))))
            [s oracle] final]
        (is (== (count oracle) (bt/count s)))
        (is (= (seq oracle) (bt/seq s)))
        (check-set s)))))


(deftest drain-test
  ;; drive nodes through the underflow boundary until empty
  (let [xs (vec (range 1000))
        s (reduce bt/conj
                  (bt/sorted-set* {:cmp compare, :branching-factor 16})
                  xs)
        final (loop [s s
                     ks (pseudo-shuffle 44 xs)
                     i 0]
                (if (empty? ks)
                  s
                  (let [s' (bt/disj s (first ks))]
                    (when (zero? (rem i 100)) (check-set s'))
                    (recur s' (rest ks) (inc i)))))]
    (is (zero? (bt/count final)))
    (is (nil? (bt/seq final)))
    (check-set final)))


;; ---------------------------------------------------------------------------
;; Slices

(defn- oracle-slice
  [oracle from to]
  (seq (cond->> (seq oracle)
         (some? from) (filter #(>= % from))
         (some? to) (filter #(<= % to)))))


(deftest slice-test
  (let [seed 777
        xs (loop [acc []
                  s seed
                  i 0]
             (if (== i 700)
               acc
               (let [s' (next-seed s)]
                 (recur (conj acc (rem s' 2000)) s' (inc i)))))
        s (bt/from-sequential compare xs {:branching-factor 16})
        oracle (apply sorted-set xs)]
    (testing "unbounded"
      (is (= (seq oracle) (bt/slice s nil nil)))
      (is (= (seq oracle) (bt/seq s))))
    (testing "random bounds (member and non-member endpoints)"
      (loop [sd 31337
             i 0]
        (when (< i 50)
          (let [s1 (next-seed sd)
                s2 (next-seed s1)
                a (- (rem s1 2200) 100)
                b (- (rem s2 2200) 100)
                from (min a b)
                to (max a b)]
            (is (= (oracle-slice oracle from to) (bt/slice s from to))
                (str "slice " from ".." to))
            (is (= (oracle-slice oracle from nil) (bt/slice s from nil))
                (str "slice " from "..nil"))
            (is (= (oracle-slice oracle nil to) (bt/slice s nil to))
                (str "slice nil.." to))
            (recur s2 (inc i))))))
    (testing "empty result"
      (is (nil? (bt/slice s 5000 6000)))
      (is (nil? (bt/slice s -100 -50))))))


(deftest rslice-test
  (let [xs (range 0 1000 3)
        s (bt/from-sequential compare xs {:branching-factor 16})
        oracle (apply sorted-set xs)]
    (testing "unbounded" (is (= (reverse (seq oracle)) (bt/rslice s nil nil))))
    (testing "rslice from hi down to lo, inclusive"
      (loop [sd 4242
             i 0]
        (when (< i 50)
          (let [s1 (next-seed sd)
                s2 (next-seed s1)
                a (- (rem s1 1200) 100)
                b (- (rem s2 1200) 100)
                lo (min a b)
                hi (max a b)]
            (is (= (seq (reverse (oracle-slice oracle lo hi)))
                   (bt/rslice s hi lo))
                (str "rslice " hi ".." lo))
            (is (= (seq (reverse (oracle-slice oracle nil hi)))
                   (bt/rslice s hi nil))
                (str "rslice " hi "..nil"))
            (is (= (seq (reverse (oracle-slice oracle lo nil)))
                   (bt/rslice s nil lo))
                (str "rslice nil.." lo))
            (recur s2 (inc i)))))
      (is (nil? (bt/rslice s -1 -100)))
      (is (nil? (bt/rslice s 5000 4000))))))


;; ---------------------------------------------------------------------------
;; O(log n + k) shape probe: a narrow slice touches few nodes; here we only
;; assert correctness of a narrow slice on a large set (perf gates are Phase 3)

(deftest narrow-slice-on-large-set-test
  (let [n op-count
        s (bt/from-sequential compare (range n) {:branching-factor 32})]
    (is (== n (bt/count s)))
    (let [mid (quot n 2)]
      (is (= (range mid (+ mid 10)) (take 10 (bt/slice s mid nil))))
      (is (= [(dec n)] (bt/slice s (dec n) nil)))
      (is (= (reverse (range mid (+ mid 10)))
             (take 10 (bt/rslice s (+ mid 9) nil)))))))


;; ---------------------------------------------------------------------------
;; psset fixture pins (§5.2, §6 Phase 1): segments generated by psset 0.3.0
;; (src/dev/psset_fixtures.clj) are restored eagerly into dao.data.btree
;; nodes, mutated, and re-validated. This pins split/merge invariants to
;; psset's exact fill policy on every host.

(defn- blob->node
  "Eagerly build a tree from fixture blobs; node type from blob shape
   (a map carrying :addresses is a Branch, one with :keys alone a Leaf)."
  [blobs addr sett]
  (let [{:keys [level keys addresses], :as blob} (get blobs addr)]
    (is (some? blob) (str "missing fixture segment " addr))
    (if addresses
      (let [n (count keys)
            ks (arr/from-coll keys)
            cs (arr/from-coll (mapv #(blob->node blobs % sett) addresses))]
        (bt/->Branch level n ks cs nil sett))
      (bt/->Leaf (count keys) (arr/from-coll keys) sett))))


(defn- restore-fixture
  [bf profile]
  (let [{:keys [root count blobs]} (get-in fx/fixtures [bf profile])
        sett (bt/->Settings bf :strong nil)]
    (bt/->BTSet nil
                compare
                nil
                nil
                (blob->node blobs root sett)
                count
                0
                sett
                nil)))


(deftest psset-fixture-restore-test
  (doseq [bf [16 32 512]
          profile [:sequential :churned]]
    (testing (str "bf=" bf " " (name profile))
      (let [{:keys [count elements]} (get-in fx/fixtures [bf profile])
            s (restore-fixture bf profile)]
        (is (== count (bt/count s)))
        (is (= elements (vec (bt/seq s))) "restored seq differs from psset's")
        (check-set s)))))


(deftest psset-fixture-mutate-test
  (doseq [bf [16 32 512]
          profile [:sequential :churned]]
    (testing (str "bf=" bf " " (name profile))
      (let [{:keys [elements]} (get-in fx/fixtures [bf profile])
            s (restore-fixture bf profile)
            oracle (apply sorted-set elements)
            ;; conj/disj churn on the restored tree, same LCG
            final (loop [s s
                         o oracle
                         sd 555
                         i 0]
                    (if (== i 2000)
                      [s o]
                      (let [s1 (next-seed sd)
                            k (rem s1 4000)
                            s2 (next-seed s1)
                            c? (< (rem s2 3) 2)]
                        (recur (if c? (bt/conj s k) (bt/disj s k))
                               (if c? (conj o k) (disj o k))
                               s2
                               (inc i)))))
            [s' o'] final]
        (is (== (count o') (bt/count s')))
        (is (= (seq o') (bt/seq s')))
        (check-set s')))))
