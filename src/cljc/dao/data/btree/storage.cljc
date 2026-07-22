(ns dao.data.btree.storage
  "IStorage adapters over dao.jing's IKVStore (docs/design/dao.data.btree.md
  §5.1, §5.4). Two adapters, one error taxonomy:

  - KVStorage (kv-storage): the sync rule-1 adapter. Absence is
    authoritative: a missing blob throws \"missing index segment\".
    Optional same-host integrity verification (§5.2): rehash the fetched
    blob against its content address; mismatch throws \"corrupt index
    segment\". Off by default — jing/segment-key hashes a pr-str that is
    not host-stable, so cross-host reads must not verify (dao.jing.md,
    Current Scope).
  - HydrationStorage (hydration-storage): the §5.4 hydration-cache
    adapter for async-only backends. Reads answer only from the cache;
    any miss throws \"unhydrated segment\" — the cache cannot distinguish
    absent from not-yet-fetched without blocking on the backend. hydrate!
    copies the reachable blob graph from the source into the cache.

  dao.data.btree itself stays storage-agnostic; this namespace is the one
  place the tree meets dao.jing (Ruling 1: no new storage protocol —
  everything below is put!/get/segment-key)."
  (:require [dao.data.btree :as bt]
            [dao.jing :as jing]))


(def ^:private absent
  "Identity sentinel for IKVStore misses (§5.1 sketch): keywords are not
   reliably `identical?` on cljs, an opaque host object is."
  #?(:cljd (Object.)
     :clj (Object.)
     :cljs (js-obj)))


(defn- rev-less
  [blob]
  (dissoc blob :rev))


(deftype KVStorage
  [store settings verify?]

  bt/IStorage

  (-store
    [_ node]
    (let [blob (bt/node->blob node)
          k (jing/segment-key blob)]
      (jing/put! store k blob)
      k))


  (-restore
    [_ addr]
    (let [blob (jing/get store addr absent)]
      (when (identical? blob absent)
        (throw (ex-info "missing index segment" {:address addr})))
      (let [blob (rev-less blob)]
        (when verify?
          (let [actual (jing/segment-key blob)]
            (when (not= addr actual)
              (throw (ex-info "corrupt index segment"
                              {:expected addr, :actual actual})))))
        (bt/blob->node blob settings))))


  (-accessed [_ _addr] nil)


  (-settings [_] settings))


(defn kv-storage
  "A sync IStorage over an IKVStore. opts: {:branching-factor n (default
   512) :ref-type k (default per host, §5.3) :verify? bool (default false,
   §5.2 — same-host mint+read only)}. The returned storage owns the
   Settings every tree restored through it shares (§5.1 threading rule);
   pass the manifest's :branching-factor here."
  ([store] (kv-storage store nil))
  ([store opts]
   (let [box (volatile! nil)
         sett (bt/->Settings (or (:branching-factor opts) 512)
                             (or (:ref-type opts) (bt/default-ref-type*))
                             nil
                             box)
         storage (KVStorage. store sett (boolean (:verify? opts)))]
     (vreset! box storage)
     storage)))


;; ---------------------------------------------------------------------------
;; Hydration cache (§5.4)

(deftype HydrationStorage
  [source cache settings]

  bt/IStorage

  (-store
    [_ node]
    ;; writes go to the source (the durable backend); the cache is a read
    ;; accelerator only
    (let [blob (bt/node->blob node)
          k (jing/segment-key blob)]
      (jing/put! source k blob)
      (jing/put! cache k blob)
      k))


  (-restore
    [_ addr]
    (let [blob (jing/get cache addr absent)]
      (if (identical? blob absent)
        ;; absent vs not-hydrated is undecidable synchronously (§5.4)
        (throw (ex-info "unhydrated segment" {:address addr}))
        (bt/blob->node (rev-less blob) settings))))


  (-accessed [_ _addr] nil)


  (-settings [_] settings))


(defn hydration-storage
  "The §5.4 hydration-cache adapter: reads answer only from `cache` (miss
   => \"unhydrated segment\"); `hydrate!` fills the cache from `source`.
   In production `source` is an async backend accessed only by the
   hydration pre-pass; in tests any IKVStore stands in."
  ([source cache] (hydration-storage source cache nil))
  ([source cache opts]
   (let [box (volatile! nil)
         sett (bt/->Settings (or (:branching-factor opts) 512)
                             (or (:ref-type opts) (bt/default-ref-type*))
                             nil
                             box)
         storage (HydrationStorage. source cache sett)]
     (vreset! box storage)
     storage)))


(defn hydrate!
  "Full-graph hydration (§5.4 rule 2, blocking variant): copy every blob
   reachable from the set's root address out of the source backend into
   the hydration cache, so subsequent reads — and mutations, whose write
   path requires residency — succeed. Idempotent: already-cached segments
   are re-put, not re-derived. Returns the set. On a non-hydration storage
   this is a no-op (sync backends need no hydration)."
  [s]
  (let [storage (bt/set-storage s)]
    (when (instance? HydrationStorage storage)
      (let [^HydrationStorage hs storage
            source (.-source hs)
            cache (.-cache hs)]
        (letfn [(pull!
                  [addr]
                  (let [blob (jing/get source addr absent)]
                    (when (identical? blob absent)
                      (throw (ex-info "missing index segment" {:address addr})))
                    (let [blob (rev-less blob)]
                      (jing/put! cache addr blob)
                      (doseq [a (:addresses blob)] (pull! a)))))]
          (when-some [addr (bt/set-address s)] (pull! addr)))))
    s))
