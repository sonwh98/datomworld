(ns dao.data.btree.cursor-test
  "The pure stepped B-tree cursor (dao.data.btree.cursor, Phase 4b-1).

   Fixtures are real persisted trees: a set built through a kv-storage over
   an in-memory content store (small branching factors give deep trees),
   stored with `store-tree`, and read back ONLY by this test's driver, which
   answers each :need-node request with one `jing/get` and counts it. The
   cursor never reads (it has no storage handle); the tests prove that by
   counting the driver's reads against the requests the cursor made.

   Equivalence oracles: `bt/slice` over the same persisted set (inclusive
   bounds) and an explicit sorted-vector filter (any bounds). The kind-tie
   fixtures also carry EXPLICIT expectations, so two implementations cannot
   pass by making the same filtering mistake."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.data.btree :as bt]
            [dao.data.btree.cursor :as cursor]
            [dao.data.btree.storage :as bts]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.mem :as mem]
            [dao.space.index :as index]))


;; ---------------------------------------------------------------------------
;; Fixtures and the driver
;; ---------------------------------------------------------------------------

(defn- fixture
  "A persisted set of elems under cmp at branching factor bf."
  [cmp elems bf]
  (let [store (mem/create-content-mem)
        storage (bts/kv-storage store {:branching-factor bf})
        s (into (bt/restore-tree cmp nil storage 0) elems)
        addr (bt/store-tree s storage)]
    {:store store, :address addr, :cnt (count s), :set s, :cmp cmp, :bf bf}))


(defn- descriptor
  [fx lower upper]
  {:root (:address fx)
   :count (:cnt fx)
   :branching-factor (:bf fx)
   :lower lower
   :upper upper})


(defn- fetch-from
  "The driver's reader: exactly one jing/get per call, counted."
  [store reads]
  (fn [address]
    (vswap! reads conj address)
    (jing/get store address ::absent)))


(defn- drive
  "Run a cursor to completion, answering every request through `fetch`.
   Optional (:while pred): stop pulling at the first item failing pred (the
   caller-owned prefix stop). Returns {:items :requests :stopped-at}."
  [c fetch & {:keys [while]}]
  (loop [c c
         items []
         requests []]
    (let [r (cursor/step c)]
      (case (:status r)
        :continue (recur (:cursor r) items requests)
        :need-node (recur (cursor/supply (:cursor r)
                                         {:address (:address r)
                                          :node (fetch (:address r))})
                          items
                          (conj requests (:address r)))
        :item (if (and while (not (while (:value r))))
                {:items items, :requests requests, :stopped-at (:value r)}
                (recur (:cursor r) (conj items (:value r)) requests))
        :done {:items items, :requests requests, :stopped-at nil}))))


(defn- walk
  "Items and requests of a range over fx."
  [fx lower upper & opts]
  (let [reads (volatile! [])
        res (apply drive (cursor/start (descriptor fx lower upper) (:cmp fx))
                   (fetch-from (:store fx) reads) opts)]
    (assoc res :reads @reads)))


(defn- expected-range
  "The explicit oracle: sorted distinct elems filtered by the bounds."
  [cmp elems lower upper]
  (let [sorted (vec (sort cmp (distinct elems)))]
    (filterv (fn [k]
               (and (or (nil? lower)
                        (let [d (cmp k (:key lower))]
                          (or (pos? d) (and (:inclusive? lower) (zero? d)))))
                    (or (nil? upper)
                        (let [d (cmp k (:key upper))]
                          (or (neg? d) (and (:inclusive? upper) (zero? d)))))))
             sorted)))


(defn- error-kind
  "The cursor error kind f throws, ::none, or [::other message]."
  [f]
  (try (f)
       ::none
       (catch #?(:cljd Object :clj Throwable :cljs :default) e
         (or (::cursor/error (ex-data e)) [::other (str e)]))))


(defn- incl
  [k]
  {:key k, :inclusive? true})


(defn- excl
  [k]
  {:key k, :inclusive? false})


;; ---------------------------------------------------------------------------
;; Shapes: empty, single-leaf, multi-level
;; ---------------------------------------------------------------------------

(deftest empty-tree-is-done-with-zero-requests
  (let [fx (fixture compare [] 4)
        c (cursor/start (descriptor fx nil nil) compare)
        r (cursor/step c)]
    (is (nil? (:address fx)))
    (is (= :done (:status r)))
    (is (= {:items [] :requests [] :stopped-at nil}
           (select-keys (walk fx nil nil) [:items :requests :stopped-at])))
    (testing "a bounded empty range too"
      (is (empty? (:requests (walk fx (incl 3) (incl 9))))))))


(deftest full-walks-match-the-sorted-contents-at-many-shapes
  (doseq [bf [4 5 8]
          n [1 2 3 4 5 6 9 17 33 100 400]]
    (let [elems (shuffle (range n))
          fx (fixture compare elems bf)
          {:keys [items requests reads]} (walk fx nil nil)]
      (is (= (vec (range n)) items) (str "bf " bf " n " n))
      (is (= requests reads) "every request is exactly one driver read")
      (is (= (count requests) (count (distinct requests)))
          "a full walk reads each node once"))))


(deftest single-leaf-reads-one-node
  (let [fx (fixture compare (range 3) 8)
        {:keys [items requests]} (walk fx nil nil)]
    (is (= [0 1 2] items))
    (is (= 1 (count requests)))))


;; ---------------------------------------------------------------------------
;; Seeks and bounds
;; ---------------------------------------------------------------------------

(deftest seeks-and-bounds-over-a-multi-level-tree
  (let [elems (mapv #(* 2 %) (range 60))          ; 0, 2, ... 118
        fx (fixture compare elems 4)]
    (testing "lower seeks: before the minimum, at a key, between keys, beyond the maximum"
      (doseq [k [-5 0 1 2 59 60 117 118 119 500]
              inclusive? [true false]]
        (let [lower {:key k, :inclusive? inclusive?}]
          (is (= (expected-range compare elems lower nil)
                 (:items (walk fx lower nil)))
              (str "lower " k " " inclusive?)))))
    (testing "upper bounds"
      (doseq [k [-5 0 1 2 59 60 117 118 119 500]
              inclusive? [true false]]
        (let [upper {:key k, :inclusive? inclusive?}]
          (is (= (expected-range compare elems nil upper)
                 (:items (walk fx nil upper)))
              (str "upper " k " " inclusive?)))))
    (testing "both bounds, empty ranges included"
      (doseq [lo [-1 0 7 8 60 118 200]
              hi [-1 0 7 8 60 118 200]
              li [true false]
              ui [true false]]
        (let [lower {:key lo, :inclusive? li}
              upper {:key hi, :inclusive? ui}]
          (is (= (expected-range compare elems lower upper)
                 (:items (walk fx lower upper)))
              (str [lo li hi ui])))))
    (testing "inclusive bounds equal bt/slice exactly"
      (doseq [lo [-1 0 7 8 60 118 200]
              hi [-1 0 7 8 60 118 200]]
        (is (= (vec (bt/slice (:set fx) lo hi compare))
               (:items (walk fx (incl lo) (incl hi)))))))))


(deftest a-selective-seek-does-not-read-the-whole-tree
  (let [fx (fixture compare (range 400) 4)
        all (count (:requests (walk fx nil nil)))
        {:keys [items requests]} (walk fx (incl 200) (incl 203))]
    (is (= [200 201 202 203] items))
    (is (< (count requests) (quot all 4))
        "root-to-leaf descent plus the boundary leaves, not the tree")))


(deftest a-genuine-nil-key-bound-is-a-bound
  (let [cmp (fn [a b]
              (cond (and (nil? a) (nil? b)) 0
                    (nil? a) -1
                    (nil? b) 1
                    :else (compare a b)))
        fx (fixture cmp [1 2 3 4 5] 4)]
    ;; nil is never stored here; a {:key nil} lower bound sits below every key
    (is (= [1 2 3 4 5] (:items (walk fx (incl nil) nil))))
    (is (= [] (:items (walk fx nil (incl nil)))))))


;; ---------------------------------------------------------------------------
;; Property tests against bt/slice and the explicit oracle
;; ---------------------------------------------------------------------------

(defn- lcg
  "A deterministic generator (MINSTD): small enough for exact doubles."
  [seed]
  (rest (iterate (fn [s] (mod (* s 48271) 2147483647)) (inc (mod seed 2147483646)))))


(deftest random-trees-and-bounds-agree-with-bt-slice
  (doseq [seed (range 1 41)]
    (let [rs (lcg seed)
          bf (nth [4 5 8] (mod (first rs) 3))
          n (mod (second rs) 160)
          elems (mapv #(mod % 500) (take n (drop 2 rs)))
          fx (fixture compare elems bf)
          distinct-sorted (vec (sort (distinct elems)))
          pick (fn [i] (- (mod (nth rs (+ 200 i)) 520) 10))
          lo (pick 0)
          hi (pick 1)]
      (is (= distinct-sorted (:items (walk fx nil nil))) (str "walk " seed))
      (is (= (vec (bt/slice (:set fx) lo hi compare))
             (:items (walk fx (incl lo) (incl hi))))
          (str "slice " seed " " lo " " hi))
      (doseq [li [true false]
              ui [true false]]
        (is (= (expected-range compare elems {:key lo :inclusive? li} {:key hi :inclusive? ui})
               (:items (walk fx {:key lo :inclusive? li} {:key hi :inclusive? ui})))
            (str "bounds " seed " " [lo li hi ui]))))))


;; ---------------------------------------------------------------------------
;; Parity fixtures: occupancy is never a rejection reason
;; ---------------------------------------------------------------------------

(deftest roots-spines-and-partial-chunks-read-fine
  (testing "tiny root leaves, partial final chunks"
    (doseq [bf [4 8]
            n [1 2 (inc bf) (+ bf bf 1) (+ (* bf bf) 1)]]
      (let [fx (fixture compare (range n) bf)]
        (is (= (vec (range n)) (:items (walk fx nil nil))) (str bf " " n)))))
  (testing "a hand-built single-child spine of one-key nodes"
    (let [blobs {:root {:level 2, :keys [9], :addresses [:mid]}
                 :mid {:level 1, :keys [9], :addresses [:leaf]}
                 :leaf {:keys [9]}}
          out (drive (cursor/start {:root :root :count 1 :branching-factor 512
                                    :lower nil :upper nil}
                                   compare)
                     blobs)]
      (is (= [9] (:items out)))
      (is (= [:root :mid :leaf] (:requests out)))))
  (testing "a branch holding one child under a wide branching factor"
    (let [blobs {:root {:level 1, :keys [5], :addresses [:a]}
                 :a {:keys [1 2 5]}}
          out (drive (cursor/start {:root :root :count 3 :branching-factor 512
                                    :lower (incl 2) :upper nil}
                                   compare)
                     blobs)]
      (is (= [2 5] (:items out))))))


;; ---------------------------------------------------------------------------
;; Protocol
;; ---------------------------------------------------------------------------

(deftest start-and-step-and-supply-perform-no-reads
  (let [fx (fixture compare (range 50) 4)
        reads (volatile! [])
        fetch (fetch-from (:store fx) reads)
        c (cursor/start (descriptor fx (incl 10) nil) compare)
        r1 (cursor/step c)]
    (is (zero? (count @reads)) "start and the first step read nothing")
    (is (= :need-node (:status r1)))
    (is (= (:address fx) (:address r1)))
    (let [node (fetch (:address r1))
          c2 (cursor/supply (:cursor r1) {:address (:address r1) :node node})]
      (is (= 1 (count @reads)) "only the driver's own fetch")
      (let [r (loop [c c2 steps 0]
                (let [r (cursor/step c)]
                  (if (and (= :continue (:status r)) (< steps 200))
                    (recur (:cursor r) (inc steps))
                    r)))]
        (is (= 1 (count @reads)) "validation and local steps read nothing")
        (is (contains? #{:need-node :item} (:status r)))))))


(deftest waiting-cursors-are-idempotent
  (let [fx (fixture compare (range 20) 4)
        c (cursor/start (descriptor fx nil nil) compare)
        r1 (cursor/step c)
        r2 (cursor/step (:cursor r1))
        r3 (cursor/step (:cursor r2))]
    (is (= :need-node (:status r1) (:status r2) (:status r3)))
    (is (= (:address r1) (:address r2) (:address r3)))
    (is (= (:cursor r1) (:cursor r2) (:cursor r3)) "no progress while waiting")))


(deftest supply-rejections
  (let [fx (fixture compare (range 20) 4)
        c (cursor/start (descriptor fx nil nil) compare)
        r1 (cursor/step c)
        node (jing/get (:store fx) (:address r1) ::absent)]
    (testing "no node pending"
      (is (= :unexpected-response
             (error-kind #(cursor/supply c {:address (:address r1) :node node})))))
    (testing "a different address"
      (is (= :address-mismatch
             (error-kind #(cursor/supply (:cursor r1) {:address :other :node node})))))
    (testing "a second response to an already satisfied request"
      (let [c2 (cursor/supply (:cursor r1) {:address (:address r1) :node node})]
        (is (= :already-satisfied
               (error-kind #(cursor/supply c2 {:address (:address r1) :node node}))))
        (is (= :unexpected-response
               (error-kind #(cursor/supply c2 {:address :something-else :node node}))))))
    (testing "a finished cursor accepts nothing"
      (let [empty-fx (fixture compare [] 4)
            done (cursor/start (descriptor empty-fx nil nil) compare)]
        (is (= :unexpected-response
               (error-kind #(cursor/supply done {:address :x :node {:keys [1]}}))))
        (is (= :done (:status (cursor/step done))))))
    (testing "an invalid descriptor"
      (is (= :invalid-descriptor
             (error-kind #(cursor/start {:root nil :count 3} compare))))
      (is (= :invalid-descriptor
             (error-kind #(cursor/start {:root :r :count 1} :not-a-comparator)))))))


(deftest the-pinned-comparator-cannot-be-replaced
  (let [fx (fixture compare (range 30) 4)
        c (cursor/start (descriptor fx (incl 5) (incl 9)) compare)]
    (is (= 1 (count (filter fn? [(::cursor/cmp c)])))
        "the comparator lives in the cursor")
    (is (= [5 6 7 8 9]
           (:items (drive c (fetch-from (:store fx) (volatile! []))))))
    (is (= :done (:status (cursor/step (:cursor (cursor/step (cursor/start {:root nil :count 0} compare))))))
        "step takes the cursor alone: the comparator was pinned at start")))


;; ---------------------------------------------------------------------------
;; Malformed nodes are structured failures
;; ---------------------------------------------------------------------------

(defn- run-blobs
  "Drive a synthetic tree; the failure kind, or the items."
  [root blobs & {:keys [lower upper cmp] :or {cmp compare}}]
  (let [out (atom nil)
        kind (error-kind
               #(reset! out (drive (cursor/start {:root root :count 9 :branching-factor 4
                                                  :lower lower :upper upper}
                                                 cmp)
                                   blobs)))]
    (if (= ::none kind) (:items @out) kind)))


(deftest malformed-nodes
  (testing "leaf shape"
    (is (= :malformed-node (run-blobs :r {:r :not-a-map})))
    (is (= :malformed-node (run-blobs :r {:r {:keys '(1 2 3)}})))
    (is (= :malformed-node (run-blobs :r {:r {:keys []}})))
    (is (= :malformed-node (run-blobs :r {:r {:keys [1 2] :level 3}})))
    (is (= :malformed-node (run-blobs :r {:r nil}))))
  (testing "branch shape"
    (is (= :malformed-node (run-blobs :r {:r {:level 1 :keys [1 2] :addresses [:a]}
                                          :a {:keys [1 2]}})))
    (is (= :malformed-node (run-blobs :r {:r {:level 1 :keys [2] :addresses [nil]}})))
    (is (= :malformed-node (run-blobs :r {:r {:level 0 :keys [2] :addresses [:a]}})))
    (is (= :malformed-node (run-blobs :r {:r {:level :one :keys [2] :addresses [:a]}})))
    (is (= :malformed-node (run-blobs :r {:r {:level 1 :keys [2] :addresses '(:a)}}))))
  (testing "ordering"
    (is (= :unordered-keys (run-blobs :r {:r {:keys [3 2 1]}})))
    (is (= :unordered-keys (run-blobs :r {:r {:keys [1 1 2]}})) "strictly ordered")
    (is (= :unordered-keys (run-blobs :r {:r {:level 1 :keys [5 4] :addresses [:a :b]}}))))
  (testing "levels"
    (is (= :inconsistent-level
           (run-blobs :r {:r {:level 1 :keys [5] :addresses [:a]}
                          :a {:level 1 :keys [5] :addresses [:b]}})))
    (is (= :inconsistent-level
           (run-blobs :r {:r {:level 2 :keys [5] :addresses [:a]}
                          :a {:level 3 :keys [5] :addresses [:b]}}))))
  (testing "child maximum against the parent separator (comparison equality)"
    (is (= :separator-mismatch
           (run-blobs :r {:r {:level 1 :keys [5] :addresses [:a]}
                          :a {:keys [1 2 3]}})))
    (is (= [1 2 5]
           (run-blobs :r {:r {:level 1 :keys [5] :addresses [:a]}
                          :a {:keys [1 2 5]}}))))
  (testing "a missing node surfaces from the driver's read, not as an empty result"
    (is (not= [] (run-blobs :r {:r {:level 1 :keys [5] :addresses [:a]}
                                :a nil})))
    (is (= :malformed-node
           (run-blobs :r {:r {:level 1 :keys [5] :addresses [:a]} :a nil}))))
  (testing "keys that are increasing inside each leaf but not across leaves"
    (is (= :unordered-keys
           (run-blobs :r {:r {:level 1 :keys [10 20] :addresses [:l1 :l2]}
                          :l1 {:keys [5 10]}
                          :l2 {:keys [8 20]}}))))
  (testing "a malformed node later on the path is only found when reached"
    (is (= [1 2 3]
           (run-blobs :r {:r {:level 1 :keys [4 9] :addresses [:a :b]}
                          :a {:keys [1 2 3 4]}
                          :b {:keys [9 8]}}
                      :upper (incl 3))))
    (is (= :unordered-keys
           (run-blobs :r {:r {:level 1 :keys [4 9] :addresses [:a :b]}
                          :a {:keys [1 2 3 4]}
                          :b {:keys [9 8]}})))))


(deftest endless-traversal-is-impossible
  (testing "a cycle in the addresses fails on the level check"
    (is (= :inconsistent-level
           (run-blobs :r {:r {:level 1 :keys [5] :addresses [:r]}}))))
  (testing "long validation is chunked across steps"
    (let [ks (vec (range 1000))
          c (cursor/start {:root :r :count 1000 :branching-factor 4
                           :lower nil :upper nil} compare)
          r (cursor/step c)
          c2 (cursor/supply (:cursor r) {:address :r :node {:keys ks}})
          continues (loop [c c2 n 0]
                      (let [r (cursor/step c)]
                        (if (= :continue (:status r)) (recur (:cursor r) (inc n)) n)))]
      (is (> continues 10) "validating 1000 keys takes many bounded transitions"))))


;; ---------------------------------------------------------------------------
;; Kind ties (r8): comparator-tied, content-distinct values
;; ---------------------------------------------------------------------------

(def ^:private pair-int-float [1 (cbor/float64 1)])
(def ^:private pair-decimals [(cbor/decimal -1 10) (cbor/decimal -2 100)])


(def ^:private pair-zeros
  [(cbor/float64-from-bits "0000000000000000")
   (cbor/float64-from-bits "8000000000000000")])


(def ^:private pairs
  [{:name "1 vs 1.0" :kinds pair-int-float :lo 0 :hi 2}
   {:name "1.0M vs 1.00M" :kinds pair-decimals :lo 0 :hi 2}
   {:name "0.0 vs -0.0" :kinds pair-zeros :lo -1 :hi 1}])


(def ^:private orders
  {:eavt {:cmp index/eavt-cmp
          :row (fn [slot id] [slot id 0 1 1])
          :sentinel (fn [t] [t nil nil nil nil])
          :slot (fn [d] (nth d 0))
          :id (fn [d] (nth d 1))
          :same? (fn [t d] (zero? (index/compare-vals t (nth d 0))))
          :side (fn [slot id] [[slot id 0 1 1]])}
   :aevt {:cmp index/aevt-cmp
          :row (fn [slot id] [id slot 0 1 1])
          :sentinel (fn [t] [nil t nil nil nil])
          :slot (fn [d] (nth d 1))
          :id (fn [d] (nth d 0))
          :same? (fn [t d] (zero? (index/compare-vals t (nth d 1))))}
   :avet {:cmp index/avet-cmp
          :row (fn [slot id] [id 7 slot 1 1])
          :sentinel (fn [t] [nil 7 t nil nil])
          :slot (fn [d] (nth d 2))
          :id (fn [d] (nth d 0))
          :same? (fn [t d]
                   (and (zero? (index/compare-vals 7 (nth d 1)))
                        (zero? (index/compare-vals t (nth d 2)))))}
   :vaet {:cmp index/vaet-cmp
          :row (fn [slot id] [id 7 slot 1 1])
          :sentinel (fn [t] [nil nil t nil nil])
          :slot (fn [d] (nth d 2))
          :id (fn [d] (nth d 0))
          :same? (fn [t d] (zero? (index/compare-vals t (nth d 2))))}})


(def ^:private run-length 41)   ; odd; far beyond a leaf (4) and a subtree (16) at bf 4


(defn- tie-rows
  "Rows: two below-prefix rows, the tied run (ids 1..n, kinds alternating
   from k0), two above-prefix rows. Later comparator fields (the id) keep
   every row distinct."
  [order {:keys [kinds lo hi]}]
  (let [row (:row (orders order))
        [k0 k1] kinds
        run (mapv (fn [id] (row (if (odd? id) k0 k1) id)) (range 1 (inc run-length)))]
    (-> []
        (into [(row lo 101) (row lo 102)])
        (into run)
        (into [(row hi 201) (row hi 202)]))))


(deftest the-tie-pairs-really-tie-in-comparator-order
  (doseq [{:keys [name kinds]} pairs]
    (is (zero? (index/compare-vals (first kinds) (second kinds))) name)
    (is (not (cbor/content= (first kinds) (second kinds))) (str name " are content-distinct"))))


(deftest kind-tied-values-at-the-seek-and-prefix-stop-boundaries
  (doseq [order [:eavt :aevt :avet :vaet]
          {:keys [name kinds] :as pair} pairs
          bf [4 5]]
    (let [{:keys [cmp sentinel slot id same?]} (orders order)
          rows (tie-rows order pair)
          fx (fixture cmp rows bf)
          label (str order " " name " bf " bf)
          all-ids (vec (range 1 (inc run-length)))]
      (is (= (count rows) (:cnt fx)) (str label ": every tied row survives the sorted set"))
      (doseq [[ti target] (map-indexed vector kinds)]
        (let [seek {:key (sentinel target) :inclusive? true}
              same-fn (fn [d] (same? target d))
              out (walk fx seek nil :while same-fn)
              candidates (mapv id (:items out))
              matching (filterv #(cbor/content= target (slot %)) (:items out))]
          (testing (str label " target " ti)
            ;; explicit expectations
            (is (= all-ids candidates)
                "the scan continues across comparator-tied, content-distinct values")
            (is (= (filterv (if (zero? ti) odd? even?) all-ids) (mapv id matching))
                "content-strict filtering happens after candidate production")
            (is (some? (:stopped-at out)) "the scan stopped at a key")
            (is (= 201 (id (:stopped-at out)))
                "the first genuinely out-of-prefix key stops the scan")
            ;; parity with bt/slice under identical conditions
            (is (= (vec (take-while same-fn (bt/slice (:set fx) (:key seek) nil cmp)))
                   (:items out)))
            ;; no node past the stopping key's leaf is read
            (is (<= (count (:reads out)) (count (:reads (walk fx nil nil))))))))
      (testing (str label " generic bounds around the run")
        (let [mid (nth rows (+ 2 19))                 ; a stored key in the middle of the run
              firstrun (nth rows 2)
              lastrun (nth rows (+ 2 (dec run-length)))]
          (doseq [[lo up] [[(incl mid) nil] [(excl mid) nil] [nil (incl mid)] [nil (excl mid)]
                           [(incl firstrun) (incl lastrun)] [(excl firstrun) (excl lastrun)]
                           [(incl lastrun) nil] [(excl lastrun) nil]]]
            (is (= (expected-range cmp rows lo up) (:items (walk fx lo up)))
                (str "bounds " (mapv #(some-> % :inclusive?) [lo up]))))
          (is (= (vec (bt/slice (:set fx) mid lastrun cmp))
                 (:items (walk fx (incl mid) (incl lastrun))))))))))


(deftest tied-runs-span-leaf-and-subtree-boundaries
  (let [pair (first pairs)
        rows (tie-rows :avet pair)
        fx (fixture index/avet-cmp rows 4)
        out (walk fx {:key [nil 7 (first (:kinds pair)) nil nil] :inclusive? true} nil
                  :while (fn [d] (zero? (index/compare-vals (first (:kinds pair)) (nth d 2)))))]
    (is (= (vec (range 1 (inc run-length))) (mapv #(nth % 0) (:items out))))
    (is (> (count (:requests out)) 5)
        "the single tied run alone spans several leaves and more than one subtree")))
