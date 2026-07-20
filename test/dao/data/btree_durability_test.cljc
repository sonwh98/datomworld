(ns dao.data.btree-durability-test
  "Phase 4 durability tests (docs/design/dao.data.btree.md §6 Phase 4).

  Coverage per the phase bullets: store/restore round-trips at multiple
  branching factors, the §5.5 lazy-fetch bar (segment fetches counted at
  the jing store, the layer every fault crosses), zero-fetch count on
  restored trees, deterministic eviction through the :test ref-type seam,
  same-host corruption detection, mixed-state incremental store (only the
  dirty subgraph written, clean siblings keep their addresses), the
  uniform-API hydration contract (reads and writes throw \"unhydrated
  segment\" until hydrate!, succeed after), settings threading (restored
  trees mutate at the manifest's branching factor, never defaults), and
  walk-addresses (root first, prune protocol)."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.data.btree :as bt]
            [dao.data.btree.storage :as bts]
            [dao.data.psset-fixtures :as fx]
            [dao.jing :as jing]
            [dao.jing.mem :as mem]))


(defn- ex-msg
  [f]
  (try (f)
       nil
       (catch #?(:cljd cljd.core/ExceptionInfo
                 :clj Exception
                 :cljs js/Error)
              e
         (ex-message e))))


(defn- counting-store
  "Wrap an IKVStore, counting get calls (= segment fetches) in an atom."
  [store counter]
  (reify
    jing/IKVStore
    (put! [_ k v] (jing/put! store k v))

    (cas! [_ k old-rev v] (jing/cas! store k old-rev v))

    (get [_ k not-found] (swap! counter inc) (jing/get store k not-found))

    (delete! [_ k] (jing/delete! store k))

    (close! [_] (jing/close! store))))


(defn- stored-fixture
  "Build a bf-`bf` set of (range n), store it, return
   {:store :address :cnt}."
  [bf n]
  (let [store (mem/create-kv-mem)
        storage (bts/kv-storage store {:branching-factor bf})
        ;; build through the storage's settings (restore-tree of nil is
        ;; the empty set carrying them), so store-tree stores that tree
        s (into (bt/restore-tree compare nil storage 0) (range n))
        addr (bt/store-tree s storage)]
    {:store store, :storage storage, :address addr, :cnt n, :set s}))


;; ---------------------------------------------------------------------------

(deftest round-trip-test
  (doseq [bf [16 32 512]
          n [0 1 100 1300]]
    (testing (str "bf=" bf " n=" n)
      (let [{:keys [storage address cnt]} (stored-fixture bf n)
            r (bt/restore-tree compare address storage cnt)]
        (is (== n (count r)))
        (is (= (seq (range n)) (seq r)))
        (is (= (seq (reverse (range n))) (rseq r)))
        (when (pos? n) (is (some? address)))
        (when (zero? n)
          (is (nil? address) "empty set stores as nil root address"))))))


(deftest restored-equality-test
  (let [{:keys [storage address cnt]} (stored-fixture 16 500)
        r (bt/restore-tree compare address storage cnt)]
    (is (= (apply sorted-set (range 500)) r))
    (is (== (hash (apply sorted-set (range 500))) (hash r)))))


(deftest count-zero-fetches-test
  ;; §5.1: count on a lazily restored set performs ZERO segment fetches —
  ;; the regression guard against psset's fault-the-whole-tree count
  (let [store (mem/create-kv-mem)
        storage (bts/kv-storage store {:branching-factor 16})
        s (into (bt/restore-tree compare nil storage 0) (range 1000))
        addr (bt/store-tree s storage)
        counter (atom 0)
        cstore (counting-store store counter)
        cstorage (bts/kv-storage cstore {:branching-factor 16})
        r (bt/restore-tree compare addr cstorage 1000)]
    (is (== 1000 (count r)))
    (is (zero? @counter) "count faulted segments")))


(deftest lazy-fetch-bar-test
  ;; §5.5 ported: 600 elements at bf 32; a narrow slice stays under 6
  ;; segment fetches while the tree exceeds 15 segments
  (let [n 600
        {:keys [store address]} (stored-fixture 32 n)
        segments (atom 0)
        _ (bt/walk-addresses (bts/kv-storage store {:branching-factor 32})
                             address
                             (fn [_] (swap! segments inc) true))
        counter (atom 0)
        cstorage (bts/kv-storage (counting-store store counter)
                                 {:branching-factor 32})
        r (bt/restore-tree compare address cstorage n)]
    (is (> @segments 15) "tree too small to prove laziness")
    (is (= [300 301 302] (take 3 (bt/slice r 300 nil))))
    (is (< @counter 6) (str "narrow slice fetched " @counter " segments"))))


(deftest eviction-refault-test
  ;; §5.3 test seam: fault, clear via :test refs, refault
  (let [store (mem/create-kv-mem)
        storage (bts/kv-storage store {:branching-factor 16, :ref-type :test})
        s (into (bt/restore-tree compare nil storage 0) (range 400))
        addr (bt/store-tree s storage)
        counter (atom 0)
        cstorage (bts/kv-storage (counting-store store counter)
                                 {:branching-factor 16, :ref-type :test})
        r (bt/restore-tree compare addr cstorage 400)]
    (is (= (range 400) (seq r)))
    (let [first-pass @counter]
      (is (pos? first-pass))
      ;; memoized: a second full traversal fetches nothing
      (is (= (range 400) (seq r)))
      (is (== first-pass @counter) "memoized children were refetched")
      ;; force 'collection', then the same traversal refaults everything
      (bt/clear-test-refs! r)
      (is (= (range 400) (seq r)))
      (is (> @counter first-pass) "cleared refs did not refault"))))


(deftest corruption-detection-test
  (let [{:keys [store storage address]} (stored-fixture 16 200)
        ;; tamper: overwrite the root blob under its original key
        blob (dissoc (jing/get store address nil) :rev)
        evil (update blob :keys (fn [ks] (assoc (vec ks) 0 :tampered)))
        _ (jing/put! store address evil)]
    (testing "verification off (default): tampering goes unnoticed"
      (let [r (bt/restore-tree compare address storage 200)]
        (is (some? (seq r)))))
    (testing "verification on: corrupt index segment"
      (let [vstorage (bts/kv-storage store
                                     {:branching-factor 16, :verify? true})
            r (bt/restore-tree compare address vstorage 200)]
        (is (= "corrupt index segment" (ex-msg #(doall (seq r)))))))))


(deftest missing-segment-test
  (let [{:keys [store storage address]} (stored-fixture 16 200)]
    (jing/delete! store address)
    (let [r (bt/restore-tree compare address storage 200)]
      (is (= "missing index segment" (ex-msg #(doall (seq r))))))))


(deftest mixed-state-incremental-store-test
  ;; §3.1: restore, modify one path, store — only the dirty subgraph is
  ;; written, and clean siblings keep their original addresses
  (let [{:keys [store storage address cnt]} (stored-fixture 16 1000)
        all-addrs
        (let [acc (atom [])]
          (bt/walk-addresses storage address (fn [a] (swap! acc conj a) true))
          @acc)
        r (bt/restore-tree compare address storage cnt)
        r' (conj r 5000)
        stores (atom 0)
        cstorage
        (let [inner storage]
          (reify
            bt/IStorage
            (-store [_ node] (swap! stores inc) (bt/-store inner node))

            (-restore [_ a] (bt/-restore inner a))

            (-accessed [_ a] (bt/-accessed inner a))

            (-settings [_] (bt/-settings inner))))
        addr' (bt/store-tree r' cstorage)
        addrs'
        (let [acc (atom [])]
          (bt/walk-addresses storage addr' (fn [a] (swap! acc conj a) true))
          @acc)]
    (is (not= address addr') "root must change")
    (testing "store cost proportional to the changed path, not the tree"
      (is (<= @stores 4) (str "stored " @stores " nodes for one insert")))
    (testing "clean siblings keep their original addresses"
      (let [shared (count (filter (set all-addrs) addrs'))]
        (is (> shared (- (count addrs') 5))
            "almost all segments shared with the previous version")))
    (testing "restored new version reads correctly"
      (let [r2 (bt/restore-tree compare addr' storage (inc cnt))]
        (is (= (concat (range 1000) [5000]) (seq r2)))))))


(deftest store-tree-idempotent-test
  (let [{:keys [storage address set]} (stored-fixture 16 100)]
    (is (= address (bt/store-tree set storage))
        "second store-tree returns the memoized address")))


(deftest walk-addresses-test
  (let [{:keys [storage address]} (stored-fixture 16 300)]
    (testing "root address emitted first"
      (let [acc (atom [])]
        (bt/walk-addresses storage address (fn [a] (swap! acc conj a) true))
        (is (= address (first @acc)))
        (is (apply distinct? @acc))))
    (testing "prune: falsey return stops descent"
      (let [acc (atom [])]
        (bt/walk-addresses storage address (fn [a] (swap! acc conj a) false))
        (is (= [address] @acc))))
    (testing "nil address walks nothing"
      (let [acc (atom [])]
        (bt/walk-addresses storage nil (fn [a] (swap! acc conj a) true))
        (is (= [] @acc))))))


(deftest settings-threading-test
  ;; the §5.1 threading rule made observable: a tree published at bf 16
  ;; and restored through a bf-16 storage keeps bf-16 fill invariants
  ;; under mutation (default-settings restore would split at 512)
  (let [{:keys [storage address cnt]} (stored-fixture 16 500)
        r (bt/restore-tree compare address storage cnt)
        r' (into r (range 500 1200))]
    (is (== 16 (:branching-factor (bt/settings r'))))
    (is (= (range 1200) (seq r')))
    ;; node fill respects bf 16 everywhere
    (letfn [(walk
              [n]
              (is (<= (bt/node-len n) 16) "node exceeds manifest bf")
              (when (bt/branch? n)
                (dotimes [i (bt/node-len n)] (walk (bt/node-child n i)))))]
      (walk (bt/root r')))))


(deftest hydration-contract-test
  ;; §5.4: uniform API; hydration state, not platform, decides what
  ;; succeeds
  (let [source (mem/create-kv-mem)
        seed-storage (bts/kv-storage source {:branching-factor 16})
        s (into (bt/restore-tree compare nil seed-storage 0) (range 300))
        addr (bt/store-tree s seed-storage)
        cache (mem/create-kv-mem)
        hstorage (bts/hydration-storage source cache {:branching-factor 16})
        r (bt/restore-tree compare addr hstorage 300)]
    (testing "unhydrated reads and writes throw \"unhydrated segment\""
      (is (= "unhydrated segment" (ex-msg #(doall (seq r)))))
      (is (= "unhydrated segment" (ex-msg #(conj r 1000))))
      (is (== 300 (count r)) "count needs no residency"))
    (testing "after hydrate! the same calls succeed"
      (bts/hydrate! r)
      (is (= (range 300) (seq r)))
      (let [r' (conj r 1000)] (is (= (concat (range 300) [1000]) (seq r')))))
    (testing "hydrate! is idempotent"
      (bts/hydrate! r)
      (is (= (range 300) (seq r))))
    (testing "a fully in-memory tree mutates freely with no hydration"
      (let [m (into (bt/sorted-set-by compare) (range 10))]
        (is (= (range 11) (seq (conj m 10))))))))


(deftest fixture-blobs-restore-through-storage-test
  ;; the psset fixture blobs (§5.2) read through the real IStorage path:
  ;; load every blob into a KVMem under its recorded address, restore
  ;; lazily, compare with psset's own element order — then mutate
  (let [fx fx/fixtures]
    (doseq [bf [16 32 512]
            profile [:sequential :churned]]
      (testing (str "bf=" bf " " (name profile))
        (let [{:keys [root count elements blobs]} (get-in fx [bf profile])
              store (mem/create-kv-mem)
              _ (doseq [[addr blob] blobs] (jing/put! store addr blob))
              storage (bts/kv-storage store {:branching-factor bf})
              r (bt/restore-tree compare root storage count)]
          (is (== count (clojure.core/count r)))
          (is (= elements (vec (seq r))))
          (let [r' (-> r
                       (conj -1)
                       (disj (first elements)))]
            (is (= (cons -1 (rest elements)) (seq r')))))))))


(deftest disj-on-restored-tree-test
  ;; disj path-copies through address-carrying branches: changed slots
  ;; lose their addresses, siblings keep theirs, and the re-stored tree
  ;; round-trips (review finding: distinct address-handling path from conj)
  (let [{:keys [storage address cnt]} (stored-fixture 16 1000)
        r (bt/restore-tree compare address storage cnt)
        r' (reduce disj r [0 500 999])
        addr' (bt/store-tree r' storage)
        r2 (bt/restore-tree compare addr' storage (- cnt 3))]
    (is (= (remove #{0 500 999} (range 1000)) (seq r2)))
    (is (== 997 (count r2)))))


(deftest transient-over-restored-tree-test
  ;; transient faults its modification path through node-child, persistent!
  ;; yields a storable tree, and only the dirty subgraph re-stores
  (let [{:keys [storage address cnt]} (stored-fixture 16 1000)
        r (bt/restore-tree compare address storage cnt)
        t (transient r)
        t (reduce conj! t (range 1000 1100))
        t (disj! t 0)
        p (persistent! t)
        addr' (bt/store-tree p storage)
        r2 (bt/restore-tree compare addr' storage (count p))]
    (is (= (concat (range 1 1000) (range 1000 1100)) (seq r2)))
    (testing "the restored snapshot is untouched"
      (is (= (range 1000)
             (seq (bt/restore-tree compare address storage cnt)))))))


(deftest unchanged-transient-keeps-address-test
  ;; psset: _address survives until the first edit, so an untouched
  ;; transient round-trip keeps store-tree a no-op
  (let [{:keys [storage address cnt]} (stored-fixture 16 200)
        r (bt/restore-tree compare address storage cnt)
        p (persistent! (transient r))]
    (is (= address (bt/store-tree p storage))
        "no-edit transient lost the address (full re-store)")))


(deftest walk-addresses-completeness-test
  ;; every address the blob graph references is emitted exactly once
  (let [{:keys [store storage address]} (stored-fixture 16 500)
        blob-graph (letfn [(walk
                             [addr]
                             (let [blob (dissoc (jing/get store addr nil) :rev)]
                               (into #{addr} (mapcat walk (:addresses blob)))))]
                     (walk address))
        walked
        (let [acc (atom [])]
          (bt/walk-addresses storage address (fn [a] (swap! acc conj a) true))
          @acc)]
    (is (= blob-graph (set walked)))
    (is (== (count blob-graph) (count walked)) "an address emitted twice")))


(deftest hydration-store-dual-write-test
  ;; writes through a HydrationStorage land in the durable source AND the
  ;; cache, so a freshly stored tree reads back without re-hydration
  (let [source (mem/create-kv-mem)
        cache (mem/create-kv-mem)
        hstorage (bts/hydration-storage source cache {:branching-factor 16})
        s (into (bt/restore-tree compare nil hstorage 0) (range 100))
        addr (bt/store-tree s hstorage)]
    (is (some? (jing/get source addr nil)) "segment missing from source")
    (is (some? (jing/get cache addr nil)) "segment missing from cache")
    (is (= (range 100) (seq (bt/restore-tree compare addr hstorage 100))))))
