(ns dao.data.btree-transients-test
  "Phase 3 transient tests (docs/design/dao.data.btree.md §6 Phase 3).

  Contract under test: transient/persistent! round-trips agree with the
  core transient sorted-set oracle, in-place edits never leak into the
  persistent snapshot the transient came from (lease ownership), stale
  transient use throws (the deliberate §3.3.3 divergence from psset's
  silent persistent fallback), EARLY-EXIT adjusts cnt, and the resulting
  trees satisfy the same fill/balance invariants as persistent builds."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.data.btree :as bt]))


;; Same minstd LCG as btree_test (identical runs on all hosts)
(defn- next-seed
  [seed]
  (rem (* 48271 seed) 2147483647))


;; Compact invariant walker (structure-level; mirrors btree_test's)
(defn- check-node
  [node cmp bf root?]
  (let [ks (bt/node-keys-vec node)
        len (count ks)]
    (is (<= len bf) "node exceeds branching factor")
    (is (every? #(neg? (cmp (ks %) (ks (inc %)))) (range (dec len)))
        "keys not strictly ascending")
    (if (bt/branch? node)
      (do (is (>= len (if root? 2 (quot bf 2))) "branch underfilled")
          (is (every? #(zero? (cmp (ks %)
                                   (bt/node-lim-key (bt/node-child node %))))
                      (range len))
              "separator key differs from child's max")
          (let [subs (mapv #(check-node (bt/node-child node %) cmp bf false)
                           (range len))]
            (is (apply = (map :depth subs)) "unbalanced")
            {:count (reduce + (map :count subs)),
             :depth (inc (:depth (first subs)))}))
      (do (when-not root? (is (>= len (quot bf 2)) "leaf underfilled"))
          {:count len, :depth 0}))))


(defn- check-set
  [s]
  (let [root (bt/root s)
        bf (:branching-factor (bt/settings s))]
    (if (zero? (count s))
      (is (zero? (bt/node-len root)))
      (let [{:keys [count]} (check-node root (bt/comparator s) bf true)]
        (is (== count (clojure.core/count s)) "walked count != cnt")))))


(defn- bt16
  []
  (bt/sorted-set* {:cmp compare, :branching-factor 16}))


(deftest transient-build-test
  (doseq [n [0 1 100 5000]]
    (testing (str n " elements")
      (let [p (persistent! (reduce conj! (transient (bt16)) (range n)))]
        (is (== n (count p)))
        (is (= (clojure.core/seq (range n)) (seq p)))
        (check-set p)))))


(deftest into-uses-transient-test
  ;; into dispatches through IEditableCollection now — equality +
  ;; invariants
  (let [p (into (bt16) (range 2000))]
    (is (= (apply sorted-set (range 2000)) p))
    (check-set p)))


(deftest transient-disj-test
  (let [t (reduce conj! (transient (bt16)) (range 1000))
        t (reduce disj! t (range 0 1000 2))
        p (persistent! t)]
    (is (= (range 1 1000 2) (seq p)))
    (check-set p)))


(deftest snapshot-isolation-test
  ;; in-place edits must never reach the persistent snapshot (lease
  ;; ownership)
  (let [p0 (into (bt16) (range 1000))
        v0 (vec (seq p0))
        t (transient p0)
        t (reduce conj! t (range 1000 2000))
        t (reduce disj! t (range 0 500))
        p1 (persistent! t)]
    (is (= v0 (vec (seq p0))) "snapshot mutated by transient ops")
    (is (== 1000 (count p0)))
    (is (= (range 500 2000) (seq p1)))
    (check-set p0)
    (check-set p1)))


(deftest stale-transient-throws-test
  (let [t (reduce conj! (transient (bt16)) [1 2 3])
        p (persistent! t)]
    (is (= [1 2 3] (seq p)))
    (doseq [op [#(conj! t 4) #(disj! t 1) #(persistent! t) #(count t)
                #(get t 1)]]
      (is (thrown? #?(:cljd cljd.core/ExceptionInfo
                      :clj Exception
                      :cljs js/Error)
            (op))
          "stale transient use must throw"))))


(deftest early-exit-cnt-test
  ;; targeted EARLY-EXIT: first insert path-copies (grown alloc), the next
  ;; interior insert edits in place and must still bump cnt
  (let [p (into (bt16) [0 10 20 30])
        t (transient p)
        t (conj! t 5) ; copy path (leaf not lease-owned yet)
        t (conj! t 6) ; in-place interior insert: EARLY-EXIT
        t (conj! t 7)]
    (is (== 7 (count t)))
    (is (= [0 5 6 7 10 20 30] (seq (persistent! t))))
    (is (== 4 (count p)) "snapshot count unchanged")))


(deftest transient-disj-last-element-propagation-test
  ;; removing a leaf's max key in place returns [left this right] so the
  ;; parent separator updates; slices over the boundary must stay correct
  (let [p (into (bt16) (range 64)) ; multi-level at bf 16
        t (transient p)
        ;; force a lease-owned leaf first, then remove that leaf's max
        t (conj! t 1000)
        t (disj! t 1000)
        t (disj! t 15)]            ; a leaf-boundary max key
    (let [p' (persistent! t)]
      (is (= (remove #{15} (range 64)) (seq p')))
      (is (= [14 16] (bt/slice p' 14 16)) "separator stale after max removal")
      (is (nil? (bt/lookup p' 15))))))


(deftest transient-nil-element-test
  (let [t (transient (bt16))]
    (is (thrown? #?(:cljd cljd.core/ExceptionInfo
                    :clj Exception
                    :cljs js/Error)
          (conj! t nil)))))


(deftest transient-lookup-test
  (let [t (reduce conj! (transient (bt16)) (range 100))]
    (is (== 42 (get t 42)))
    (is (nil? (get t 1000)))
    (is (= :nf (get t 1000 :nf)))
    (is (== 100 (count t)))
    #?(:clj (is (.contains ^clojure.lang.ITransientSet t 42)))))


(deftest re-transient-test
  ;; persistent! then transient again: fresh lease, old nodes not editable
  (let [p1 (persistent! (reduce conj! (transient (bt16)) (range 100)))
        p2 (persistent! (reduce conj! (transient p1) (range 100 200)))]
    (is (= (range 100) (seq p1)) "first persistent mutated by second transient")
    (is (= (range 200) (seq p2)))
    (check-set p1)
    (check-set p2)))


(deftest meta-through-transient-test
  (let [p (with-meta (into (bt16) [1 2 3]) {:m 1})]
    (is (= {:m 1} (meta (persistent! (conj! (transient p) 4)))))))


(deftest generative-transient-vs-oracle-test
  ;; seeded conj!/disj! churn against core's transient sorted-set... which
  ;; does not exist; use a persistent sorted-set oracle instead
  (doseq [seed [7 123456]]
    (let [n #?(:clj 50000
               :cljs 8000
               :cljd 8000)
          domain (quot n 3)
          final (loop [t (transient (bt16))
                       o (sorted-set)
                       sd seed
                       i 0]
                  (if (== i n)
                    [(persistent! t) o]
                    (let [s1 (next-seed sd)
                          k (rem s1 domain)
                          s2 (next-seed s1)
                          c? (< (rem s2 3) 2)]
                      (recur (if c? (conj! t k) (disj! t k))
                             (if c? (conj o k) (disj o k))
                             s2
                             (inc i)))))
          [p o] final]
      (is (== (count o) (count p)))
      (is (= (seq o) (seq p)))
      (check-set p))))


(deftest interleaved-persistent-and-transient-test
  ;; alternate persistent conj and transient batches; snapshots stay intact
  (let [p1 (into (bt16) (range 100))
        p2 (conj p1 1000)
        p3 (persistent! (reduce conj! (transient p2) (range 2000 2100)))
        p4 (disj p3 1000)]
    (is (= (range 100) (seq p1)))
    (is (= (concat (range 100) [1000]) (seq p2)))
    (is (= (concat (range 100) [1000] (range 2000 2100)) (seq p3)))
    (is (= (concat (range 100) (range 2000 2100)) (seq p4)))
    (doseq [p [p1 p2 p3 p4]] (check-set p))))
