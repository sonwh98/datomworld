(ns dao.data.btree-durability-test
  "Phase 4 durability tests (docs/design/dao.data.btree.md §6 Phase 4),
   rewritten against the dao.jing content-handle API (dao.jing/materialize!,
   dao.jing/get; docs/design/dao.jing.md): every store is a
   mem/create-content-mem or file/create-content-file handle, an address
   equals the content hash of the blob stored at it, and stored content is
   never overwritten or deleted.

   Coverage per the phase bullets: store/restore round-trips at multiple
   branching factors, the address-is-content-hash contract, file close/reopen
   recovery, the authoritative \"missing index segment\" taxonomy, same-host
   corruption detection through an explicit forged read handle (no store
   mutation), the §5.5 lazy-fetch bar (segment fetches counted at the jing
   store, the layer every fault crosses), zero-fetch count on restored trees,
   deterministic eviction through the :test ref-type seam, mixed-state
   incremental store (only the dirty subgraph written, clean siblings keep
   their addresses), the uniform-API hydration contract (miss then full-graph
   hydrate!), source/cache contents, settings threading (restored trees
   mutate at the manifest's branching factor, never defaults), walk-addresses
   (root first, prune protocol), and JVM-only concurrent store-tree
   convergence."
  (:require #?@(:cljd [["dart:io" :as dart-io]])
            [clojure.test :refer [deftest is testing]]
            [dao.data.btree :as bt]
            [dao.data.btree.storage :as bts]
            [dao.data.psset-fixtures :as fx]
            [dao.jing :as jing]
            [dao.jing.file :as jing-file]
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


(defn- temp-path
  [prefix]
  (str "target/test-btree-durability-" prefix "-" (random-uuid) ".log"))


(defn- cleanup-file
  [path]
  #?(:clj (let [f (java.io.File. path)] (when (.exists f) (.delete f)))
     :cljs (try (.unlinkSync (js/require "fs") path) (catch :default _))
     :cljd (try (let [f (dart-io/File path)]
                  (when (.existsSync f) (.deleteSync f)))
                (catch #?(:cljd Object
                          :default Exception)
                       _
                  nil))))


(defn- counting-store
  "Wrap a content handle, counting segment fetches via a wrapped
   :get-content-fn (the layer every fault crosses)."
  [handle counter]
  (let [get-fn (:get-content-fn handle)]
    (assoc handle
           :get-content-fn (fn [address not-found]
                             (swap! counter inc)
                             (get-fn address not-found)))))


(defn- forged-read-handle
  "A content handle that serves `blob` for `address` and delegates every
   other read to `store`, with writes throwing: presents a corrupt segment
   to -restore without mutating (or even touching) the store."
  [store address blob]
  {:put-content-fn (fn [_address _payload]
                     (throw (ex-info "forged read handle does not write" {}))),
   :get-content-fn (fn [a not-found]
                     (if (= a address) blob (jing/get store a not-found)))})


(defn- stored-fixture
  "Build a bf-`bf` set of (range n), store it, return
   {:store :storage :address :cnt :set}."
  [bf n]
  (let [store (mem/create-content-mem)
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


(deftest address-is-content-hash-test
  ;; §5.2: every address a storage mints is the content hash of the blob
  ;; stored at it, and re-materializing the same blob is an idempotent
  ;; no-op returning the same address
  (let [{:keys [store address]} (stored-fixture 16 200)
        storage (bts/kv-storage store {:branching-factor 16})
        addrs (atom [])
        _ (bt/walk-addresses storage
                             address
                             (fn [a] (swap! addrs conj a) true))]
    (is (jing/segment-address? address))
    (doseq [a @addrs]
      (let [blob (jing/get store a nil)]
        (is (= a (jing/segment-key blob))
            (str "segment " a " is stored at its content hash"))
        (is (= a (jing/materialize! store blob))
            "re-materializing the same blob returns the same address")))))


(deftest restored-equality-test
  (let [{:keys [storage address cnt]} (stored-fixture 16 500)
        r (bt/restore-tree compare address storage cnt)]
    (is (= (apply sorted-set (range 500)) r))
    (is (== (hash (apply sorted-set (range 500))) (hash r)))))


(deftest count-zero-fetches-test
  ;; §5.1: count on a lazily restored set performs ZERO segment fetches —
  ;; the regression guard against psset's fault-the-whole-tree count
  (let [store (mem/create-content-mem)
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
  (let [store (mem/create-content-mem)
        storage (bts/kv-storage store {:branching-factor 16, :ref-type :test})
        s (into (bt/restore-tree compare nil storage 0) (range 400))
        addr (bt/store-tree s storage)
        r (bt/restore-tree compare addr storage 400)]
    (is (= (range 400) (seq r)))
    (is (= (range 400) (seq r)) "second traversal succeeds")
    (bt/clear-test-refs! r)
    (is (= (range 400) (seq r)) "survives after test refs cleared")))


(deftest corruption-detection-test
  (let [{:keys [store address]} (stored-fixture 16 200)
        blob (jing/get store address nil)
        evil (update blob :keys (fn [ks] (assoc (vec ks) 0 :tampered)))
        forged (forged-read-handle store address evil)]
    (testing "verification off (default): tampering goes unnoticed"
      (let [storage (bts/kv-storage forged {:branching-factor 16})
            r (bt/restore-tree compare address storage 200)]
        (is (some? (seq r)))))
    (testing "verification on: corrupt index segment"
      (let [vstorage (bts/kv-storage forged
                                     {:branching-factor 16, :verify? true})
            r (bt/restore-tree compare address vstorage 200)]
        (is (= "corrupt index segment" (ex-msg #(doall (seq r)))))))))


(deftest missing-segment-test
  ;; authoritative absence on a sync backend: an address with no blob is a
  ;; hard error, not a not-found. Nothing is deleted — a fresh store simply
  ;; never had it (delete! is not part of the content-handle API)
  (let [{:keys [address]} (stored-fixture 16 200)
        storage (bts/kv-storage (mem/create-content-mem) {:branching-factor 16})
        r (bt/restore-tree compare address storage 200)]
    (is (= "missing index segment" (ex-msg #(doall (seq r)))))))


(deftest file-close-reopen-recovery-test
  ;; durability across the handle boundary: a tree stored through a content
  ;; file reads back after close + reopen (append-log replay)
  (let [path (temp-path "roundtrip")]
    (try (let [h (jing-file/create-content-file path)
               storage (bts/kv-storage h {:branching-factor 16})
               s (into (bt/restore-tree compare nil storage 0) (range 300))
               addr (bt/store-tree s storage)]
           (is (= addr (jing/segment-key (jing/get h addr nil))))
           (jing/close! h)
           (let [h2 (jing-file/create-content-file path)
                 storage2 (bts/kv-storage h2 {:branching-factor 16})
                 r (bt/restore-tree compare addr storage2 300)]
             (is (= (range 300) (seq r)))
             (is (== 300 (count r)))
             (jing/close! h2)))
         (finally (cleanup-file path)))))


(deftest mixed-state-incremental-store-test
  ;; §3.1: restore, modify one path, store — only the dirty subgraph is
  ;; written, and clean siblings keep their original addresses
  (let [{:keys [storage address cnt]} (stored-fixture 16 1000)
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
  (let [source (mem/create-content-mem)
        seed-storage (bts/kv-storage source {:branching-factor 16})
        s (into (bt/restore-tree compare nil seed-storage 0) (range 300))
        addr (bt/store-tree s seed-storage)
        cache (mem/create-content-mem)
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


(deftest hydrate-store-dual-write-test
  ;; writes through a HydrationStorage land in the durable source AND the
  ;; cache under identical content addresses, so a freshly stored tree
  ;; reads back without re-hydration
  (let [source (mem/create-content-mem)
        cache (mem/create-content-mem)
        hstorage (bts/hydration-storage source cache {:branching-factor 16})
        s (into (bt/restore-tree compare nil hstorage 0) (range 100))
        addr (bt/store-tree s hstorage)]
    (is (= addr (jing/segment-key (jing/get source addr nil)))
        "root segment missing from source")
    (is (= addr (jing/segment-key (jing/get cache addr nil)))
        "root segment missing from cache")
    (is (= (:content @(:state source)) (:content @(:state cache)))
        "source and cache hold identical segment maps")
    (is (= (range 100) (seq (bt/restore-tree compare addr hstorage 100))))))


(deftest fixture-blobs-restore-through-storage-test
  ;; the psset fixture blobs (§5.2) read through the real IStorage path:
  ;; materialize every blob under its recorded content address, restore
  ;; lazily, compare with psset's own element order — then mutate
  (let [fx fx/fixtures]
    (doseq [bf [16 32 512]
            profile [:sequential :churned]]
      (testing (str "bf=" bf " " (name profile))
        (let [{:keys [root count elements blobs]} (get-in fx [bf profile])
              store (mem/create-content-mem)
              _ (doseq [[addr blob] blobs]
                  (is (= addr (jing/materialize! store blob))
                      "fixture address is the content hash of its blob"))
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
                             (let [blob (jing/get store addr nil)]
                               (into #{addr} (mapcat walk (:addresses blob)))))]
                     (walk address))
        walked
        (let [acc (atom [])]
          (bt/walk-addresses storage address (fn [a] (swap! acc conj a) true))
          @acc)]
    (is (= blob-graph (set walked)))
    (is (== (count blob-graph) (count walked)) "an address emitted twice")))


(deftest concurrent-store-converges-test
  ;; JVM-only: concurrent store-tree of equal trees through one content
  ;; store must converge on one address with exactly one copy of every
  ;; segment — the insert-if-absent of the mem backend is linearizable
  ;; under contention
  #?(:clj
     (testing "concurrent store-tree converges on one root address"
       (let [store (mem/create-content-mem)
             storage (bts/kv-storage store {:branching-factor 16})
             barrier (java.util.concurrent.CyclicBarrier. 8)
             results (java.util.concurrent.ConcurrentLinkedQueue.)
             threads (mapv
                       (fn [_]
                         (Thread.
                           (fn []
                             (let [s (into
                                       (bt/restore-tree compare nil storage 0)
                                       (range 500))]
                               (.await barrier)
                               (.add results (bt/store-tree s storage))))))
                       (range 8))]
         (doseq [t threads] (.start t))
         (doseq [t threads] (.join t))
         (let [addrs (into [] results)]
           (is (apply = addrs)
               "all concurrent stores return the same address")
           (let [segments (atom #{})
                 _ (bt/walk-addresses storage
                                      (first addrs)
                                      (fn [a] (swap! segments conj a) true))]
             (is (= (count @segments) (count (:content @(:state store))))
                 "every segment stored exactly once, no duplicates")))))
     :cljs (is true)
     :cljd (is true)))
