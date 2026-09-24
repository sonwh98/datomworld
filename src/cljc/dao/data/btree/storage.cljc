(ns dao.data.btree.storage
  "IStorage adapters over dao.jing content-store handles (docs/design/dao.data.btree.md
   sections 5.1, 5.4). A handle is the plain-data byte store carrying
   :put-bytes-fn, :get-bytes-fn, and :close-fn (docs/design/dao.jing.md,
   Materialization rule); addresses are derived from payloads by
   dao.jing/materialize!, never supplied by this layer. Two adapters, one
   error taxonomy:

   - KVStorage (kv-storage): the sync rule-1 adapter. Absence is
     authoritative: a missing blob throws \"missing index segment\".
     Optional same-host integrity verification (§5.2): rehash the fetched
     blob against its content address; mismatch throws \"corrupt index
     segment\". Off by default — dao.jing's print-based content hash is not
     host-stable, so cross-host reads must not verify (dao.jing.md, Current
     Scope).
   - HydrationStorage (hydration-storage): the §5.4 hydration-cache
     adapter for async-only backends. Reads answer only from the cache;
     any miss throws \"unhydrated segment\" — the cache cannot distinguish
     absent from not-yet-fetched without blocking on the backend. hydrate!
     copies the reachable blob graph from the source into the cache. The
     source is either a sync content handle or an async one
     (dao.jing.remote.async/async-content); over an async source the
     non-blocking hydrate-async and store-tree-async are the only way in
     and out, and the sync store path throws.

   dao.data.btree itself stays storage-agnostic; this namespace is the one
   place the tree meets dao.jing (Decision 1: no new storage protocol —
   everything below is materialize!/get, plus segment-matches? for §5.2
   verification)."
  (:require #?@(:cljd [["dart:async" :as async]])
            [dao.data.btree :as bt]
            [dao.jing :as jing]))


(def ^:private absent
  "Identity sentinel for handle misses (§5.1 sketch): keywords are not
   reliably `identical?` on cljs, an opaque host object is."
  #?(:cljd (Object.)
     :clj (Object.)
     :cljs (js-obj)))


(deftype KVStorage
  [store settings verify? algorithm]

  bt/IStorage

  (-store
    [_ node]
    (let [blob (bt/node->blob node)]
      (if algorithm
        (jing/materialize! store blob {:algorithm algorithm})
        (jing/materialize! store blob))))


  (-restore
    [_ addr]
    (let [blob (jing/get store addr absent)]
      (when (identical? blob absent)
        (throw (ex-info "missing index segment" {:address addr})))
      (when verify?
        (when-not (jing/segment-matches? addr blob)
          (throw (ex-info "corrupt index segment"
                          {:expected addr, :actual (jing/segment-key blob)}))))
      (bt/blob->node blob settings)))


  (-accessed [_ _addr] nil)


  (-settings [_] settings))


(defn kv-storage
  "A sync IStorage over a dao.jing content-store handle. opts:
   {:branching-factor n (default 512) :ref-type k (default per host, §5.3)
   :verify? bool (default false, §5.2 — same-host mint+read only)
   :algorithm keyword (default nil, mints with default-hash-algorithm)}. The
   returned storage owns the Settings every tree restored through it shares
   (§5.1 threading rule); pass the manifest's :branching-factor here."
  ([store] (kv-storage store nil))
  ([store opts]
   (let [box (volatile! nil)
         sett (bt/->Settings (or (:branching-factor opts) 512)
                             (or (:ref-type opts) (bt/default-ref-type*))
                             nil
                             box)
         storage (KVStorage. store
                             sett
                             (boolean (:verify? opts))
                             (:algorithm opts))]
     (vreset! box storage)
     storage)))


;; ---------------------------------------------------------------------------
;; Hydration cache (§5.4)

(defn- async-source?
  "True for an async content handle (dao.jing.remote.async/async-content)."
  [source]
  (some? (:get-content-async-fn source)))


(deftype HydrationStorage
  [source cache settings outbox algorithm]
  ;; outbox: atom {:writing? bool, :unacked [[addr blob] ...]} — used
  ;; only over an async source. :writing? is true only inside
  ;; store-tree-async; :unacked holds every blob stored to the cache whose
  ;; source write has not yet acknowledged, carried across failed calls so
  ;; a retry re-pushes them (§5.4 durability ordering).

  bt/IStorage

  (-store
    [_ node]
    (let [blob (bt/node->blob node)
          opts (when algorithm {:algorithm algorithm})]
      (if (async-source? source)
        (do
          ;; the sync store-tree cannot wait for acknowledgments (§5.4)
          (when-not (:writing? @outbox)
            (throw (ex-info "sync store-tree against an async backend" {})))
          (let [addr (if opts
                       (jing/materialize! cache blob opts)
                       (jing/materialize! cache blob))]
            (swap! outbox update :unacked
                   (fn [u]
                     (if (some #(= addr (first %)) u) u (conj u [addr blob]))))
            addr))
        ;; writes land in the durable source AND the read cache; both are
        ;; content-addressed, so both must answer with the same address
        (let [source-addr (if opts
                            (jing/materialize! source blob opts)
                            (jing/materialize! source blob))
              cache-addr (if opts
                           (jing/materialize! cache blob opts)
                           (jing/materialize! cache blob))]
          (when-not (= source-addr cache-addr)
            (throw (ex-info "hydration source and cache diverged"
                            {:source source-addr, :cache cache-addr})))
          source-addr))))


  (-restore
    [_ addr]
    (let [blob (jing/get cache addr absent)]
      (if (identical? blob absent)
        ;; absent vs not-hydrated is undecidable synchronously (§5.4)
        (throw (ex-info "unhydrated segment" {:address addr}))
        (bt/blob->node blob settings))))


  (-accessed [_ _addr] nil)


  (-settings [_] settings))


(defn hydration-storage
  "The §5.4 hydration-cache adapter: reads answer only from `cache` (miss
   => \"unhydrated segment\"); `hydrate!` fills the cache from `source`.
   `source` is a sync content handle (hydrate!, store-tree) or an async
   one from dao.jing.remote.async (hydrate-async, store-tree-async)."
  ([source cache] (hydration-storage source cache nil))
  ([source cache opts]
   (let [box (volatile! nil)
         sett (bt/->Settings (or (:branching-factor opts) 512)
                             (or (:ref-type opts) (bt/default-ref-type*))
                             nil
                             box)
         storage (HydrationStorage. source
                                    cache
                                    sett
                                    (atom {:writing? false, :unacked []})
                                    (:algorithm opts))]
     (vreset! box storage)
     storage)))


(defn hydrate!
  "Full-graph hydration (§5.4 rule 2, blocking variant): copy every blob
   reachable from the set's root address out of the source backend into
   the hydration cache, so subsequent reads — and mutations, whose write
   path requires residency — succeed. Idempotent: already-cached segments
   are re-materialized as no-ops. Returns the set. On a non-hydration
   storage this is a no-op (sync backends need no hydration)."
  [s]
  (let [storage (bt/set-storage s)]
    (when (instance? HydrationStorage storage)
      (let [^HydrationStorage hs storage
            source (.-source hs)
            cache (.-cache hs)]
        (when (async-source? source)
          (throw (ex-info "hydrate! against an async backend; use hydrate-async"
                          {})))
        (letfn [(pull!
                  [addr]
                  (let [blob (jing/get source addr absent)]
                    (when (identical? blob absent)
                      (throw (ex-info "missing index segment" {:address addr})))
                    (let [algo (jing/segment-algorithm addr)
                          addr' (jing/materialize! cache blob
                                                   {:algorithm algo})]
                      (when-not (= addr addr')
                        (throw (ex-info "hydration address mismatch"
                                        {:expected addr, :actual addr'}))))
                    (doseq [a (:addresses blob)] (pull! a))))]
          (when-some [addr (bt/set-address s)] (pull! addr)))))
    s))


;; ---------------------------------------------------------------------------
;; Async variants (§5.4 rule 2 non-blocking, and durability ordering)

(defn- deferred
  "The host's one-shot async value: a Promise (cljs), a Completer-backed
   Future (cljd), a CompletableFuture (JVM). `start` receives resolve and
   reject fns and calls exactly one of them."
  [start]
  #?(:cljd (let [c (async/Completer)]
             (start #(.complete c %) #(.completeError c %))
             (.-future c))
     :clj (let [f (java.util.concurrent.CompletableFuture.)]
            (start #(.complete f %) #(.completeExceptionally f %))
            f)
     :cljs (js/Promise. (fn [resolve reject] (start resolve reject)))))


(defn- attempt
  "Run thunk `f`, answering [:ok v] or [:err e] — so a callback is never
   called from inside the try that guards the work it reports on."
  [f]
  (try [:ok (f)]
       (catch #?(:cljd Object
                 :clj Throwable
                 :cljs :default)
              e
         [:err e])))


(defn- report
  [[tag x] on-ok on-err]
  (if (= :ok tag) (on-ok x) (on-err x)))


(defn- async-hydration-storage
  "The storage when it is a HydrationStorage over an async source, else nil."
  [storage]
  (when (and (instance? HydrationStorage storage)
             (async-source? (.-source ^HydrationStorage storage)))
    storage))


(defn hydrate-async
  "Full-graph hydration (§5.4 rule 2, non-blocking variant): fetch every
   blob reachable from the set's root address out of the async source into
   the hydration cache, then resolve to the same set. Addresses already in
   the cache are read from it rather than fetched, so hydrating a resident
   graph issues no requests. Over a sync source it runs hydrate!; over a
   non-hydration storage it resolves at once.

   (hydrate-async s)              => Promise / Future / CompletableFuture
   (hydrate-async s on-ok on-err) => nil; exactly one callback is called

   A source miss rejects \"missing index segment\" (the source answers
   absence authoritatively); an `:error` or `:lost` completion rejects
   \"hydration fetch failed\" with the completion attached."
  ([s] (deferred (fn [resolve reject] (hydrate-async s resolve reject))))
  ([s on-ok on-err]
   (if-let [^HydrationStorage hs (async-hydration-storage (bt/set-storage s))]
     (let [cache (.-cache hs)
           get-async (:get-content-async-fn (.-source hs))
           settled (atom false)
           ok! #(when (compare-and-set! settled false true) (on-ok s))
           fail! #(when (compare-and-set! settled false true) (on-err %))
           ;; one token for the walk itself plus one per visited address
           outstanding (atom 1)
           seen (atom #{})
           done! #(when (zero? (swap! outstanding dec)) (ok!))]
       (letfn [(visit!
                 [addr]
                 (when-not (contains? (first (swap-vals! seen conj addr)) addr)
                   (swap! outstanding inc)
                   (let [blob (jing/get cache addr absent)]
                     (if (identical? blob absent)
                       (get-async addr #(report (attempt (fn [] (fetched! addr %)))
                                                (fn [_] nil)
                                                fail!))
                       (do (doseq [a (:addresses blob)] (visit! a))
                           (done!))))))
               (fetched!
                 [addr c]
                 (cond
                   (not (contains? c :found?))
                   (fail! (ex-info "hydration fetch failed"
                                   {:address addr, :completion c}))

                   (not (:found? c))
                   (fail! (ex-info "missing index segment" {:address addr}))

                   :else
                   (let [blob (:value c)
                         algo (jing/segment-algorithm addr)
                         addr' (jing/materialize! cache blob
                                                  {:algorithm algo})]
                     (if (= addr addr')
                       (do (doseq [a (:addresses blob)] (visit! a))
                           (done!))
                       (fail! (ex-info "hydration address mismatch"
                                       {:expected addr, :actual addr'}))))))]
         (report (attempt (fn []
                            (when-some [addr (bt/set-address s)] (visit! addr))
                            (done!)))
                 (fn [_] nil)
                 fail!))
       nil)
     (report (attempt #(hydrate! s)) on-ok on-err))))


(defn store-tree-async
  "The write-side sibling of hydrate-async (§5.4 durability ordering):
   store the set's dirty subgraph into the hydration cache, push every
   unacknowledged segment to the async source, and resolve to the root
   address only after every one of those writes has acknowledged. The
   caller chains the root `cas!` on that resolution — never before.

   A segment whose write fails stays queued on the storage and is pushed
   again by the next store-tree-async through it, so retrying a failed
   call — even on the now-addressed set, whose store-tree is a no-op —
   cannot resolve over an incomplete closure. Over a sync storage it
   resolves to store-tree's result. An empty set resolves to nil, as
   store-tree answers.

   (store-tree-async s storage)              => Promise / Future / CompletableFuture
   (store-tree-async s storage on-ok on-err) => nil; exactly one callback is called"
  ([s storage]
   (deferred (fn [resolve reject] (store-tree-async s storage resolve reject))))
  ([s storage on-ok on-err]
   (if-let [^HydrationStorage hs (async-hydration-storage storage)]
     (let [outbox (.-outbox hs)
           source (.-source hs)
           put-content-async (or (:put-content-async-fn source)
                                 (when-let [m (:materialize-async-fn source)]
                                   (fn [addr blob cb]
                                     (m blob
                                        {:algorithm
                                         (jing/segment-algorithm addr)}
                                        cb))))
           stored (attempt (fn []
                             (swap! outbox assoc :writing? true)
                             (try (bt/store-tree s storage)
                                  (finally (swap! outbox assoc :writing? false)))))]
       (if (= :err (first stored))
         (report stored on-ok on-err)
         (let [root (second stored)
               unacked (:unacked @outbox)
               remaining (atom (count unacked))
               failure (atom nil)
               settle! #(when (zero? (swap! remaining dec))
                          (if-some [e @failure] (on-err e) (on-ok root)))]
           (if (empty? unacked)
             (on-ok root)
             (doseq [[addr blob] unacked]
               (put-content-async
                 addr
                 blob
                 (fn [c]
                   (if (and (:result c) (= addr (:address c)))
                     ;; acknowledged: the one place a segment leaves the queue
                     (swap! outbox update :unacked
                            (fn [u] (filterv #(not= addr (first %)) u)))
                     (compare-and-set!
                       failure
                       nil
                       (ex-info "store-tree-async: segment write failed"
                                {:address addr, :completion c})))
                   (settle!)))))))
       nil)
     (report (attempt #(bt/store-tree s storage)) on-ok on-err))))
