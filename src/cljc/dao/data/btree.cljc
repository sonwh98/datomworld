(ns dao.data.btree
  "Universal persistent B-tree — a generic persistent sorted set
  (docs/design/dao.data.btree.md). One .cljc source for Clojure (JVM),
  ClojureScript, and ClojureDart.

  Phase 1: core tree — conj/disj/lookup/seq/slice/rslice and bulk build,
  persistent only. The transient edit lease (`Settings.edit`) is nil
  throughout this phase (§3.1); durability (IStorage, store/restore) lands
  in Phase 4.

  Node layout and split/merge/borrow invariants transcribe
  persistent-sorted-set 0.3.0's Leaf.java/Branch.java exactly (§3.3, §5.2),
  with the deliberate divergences of §3.3.3 (protocol not Java interface,
  acopy shape, no restored-count sentinel, stale-transient-throws).
  Comparators are caller-supplied; this namespace knows nothing of datoms."
  (:refer-clojure :exclude
                  [conj disj count contains? seq rseq comparator sorted-set-by])
  (:require #?(:cljd ["dart:core" :as dc])
            [dao.data.arrays :as arr]))


;; ---------------------------------------------------------------------------
;; Sentinels (§3.3.2) — two distinct empty arrays, compared by identity only.

(def UNCHANGED
  "Returned by node-conj/node-disj when the key was already present/absent."
  (arr/anew 0))


(def EARLY-EXIT
  "Returned when a transient edited in place, nothing to propagate upward.
   Unreachable in Phase 1 (edit lease is always nil)."
  (arr/anew 0))


;; ---------------------------------------------------------------------------
;; Settings (§3.1)

(def ^:private default-ref-type
  ;; §5.3: :soft on the JVM (memory-pressure-aware), :strong on cljs/cljd
  ;; where :soft would silently degrade to :weak.
  #?(:cljd :strong
     :clj :soft
     :cljs :strong))


(deftype Settings
  [branching-factor ; max keys per node, default 512
   ref-type         ; :strong | :soft | :weak | :test;
   ;; default per host (§5.3)
   edit             ; transient mutation lease (Phase 3);
   ;; nil when persistent
   storage-box])    ; nil, or a volatile holding the IStorage faulting reads
;; go through (Phase 4). A box, not a value: the
;; storage
;; and the settings reference each other, so the slot
;; is
;; filled after construction. Plumbing, not identity —
;; the conceptual Settings shape stays §3.1's three.

(defn- make-settings
  ^Settings [opts]
  (Settings. (or (:branching-factor opts) 512)
             (or (:ref-type opts) default-ref-type)
             nil
             nil))


(defn- settings-storage
  [^Settings sett]
  (some-> (.-storage-box sett)
          deref))


(defn- bf*
  [^Settings s]
  (.-branching-factor s))


(defn- min-bf*
  [^Settings s]
  (quot (.-branching-factor s) 2))


;; ---------------------------------------------------------------------------
;; Edit lease (§3.1, Phase 3). The token is host-appropriate: an
;; AtomicBoolean on the JVM (transients may be handed between threads, so
;; the staleness read must be atomic), a plain mutable box on cljs/cljd
;; (single-threaded / isolate-local). A node is editable in place iff its
;; own `settings` carries a live lease — nodes from before the transient
;; began hold different Settings and are never mutated (ownership rule).

#?(:cljd (deftype EditBox
           [^:mutable val])
   :clj nil
   :cljs (deftype EditBox
           [^:mutable val]))


(defn- new-lease
  []
  #?(:cljd (EditBox. true)
     :clj (java.util.concurrent.atomic.AtomicBoolean. true)
     :cljs (EditBox. true)))


(defn- editing?
  [^Settings sett]
  (when-some [e (.-edit sett)]
    #?(:cljd (.-val ^EditBox e)
       :clj (.get ^java.util.concurrent.atomic.AtomicBoolean e)
       :cljs (.-val ^EditBox e))))


(defn- clear-lease!
  [^Settings sett]
  (let [e (.-edit sett)]
    #?(:cljd (set! (.-val ^EditBox e) false)
       :clj (.set ^java.util.concurrent.atomic.AtomicBoolean e false)
       :cljs (set! (.-val ^EditBox e) false))))


(defn- editable-settings
  "A fresh Settings sharing bf/ref-type/storage with sett, carrying a live
   lease."
  ^Settings [^Settings sett]
  (Settings. (.-branching-factor sett)
             (.-ref-type sett)
             (new-lease)
             (.-storage-box sett)))


(def ^:private expand-len
  "Extra key slots allocated per copy under a lease (psset expandLen), so
   repeated transient inserts into one node do not reallocate every time."
  8)


(defn- new-cap
  "Allocation size for n live entries: exact when persistent, grown by
   expand-len (capped at the branching factor) under a live lease."
  [n ^Settings sett]
  (if (editing? sett) (min (bf* sett) (+ n expand-len)) n))


;; ---------------------------------------------------------------------------
;; IStorage (§5.1, Phase 4). Modeled on psset's IStorage over dao.jing's
;; IKVStore; the concrete adapters live in dao.data.btree.storage so this
;; namespace stays storage-agnostic. `-settings` is the concrete form of
;; the §5.1 settings-threading rule: the storage and the trees restored
;; through it share one Settings, so no restored node can ever carry
;; defaults.

(defprotocol IStorage

  (-store
    [storage node]
    "Serialize node, put! it content-addressed, return the address. Called
     only after all of the node's children have been stored and have
     addresses (§5.1).")

  (-restore
    [storage address]
    "Fetch + deserialize the node at address; node type from blob shape
     (§5.1). Throws \"missing index segment\" on authoritative absence,
     \"unhydrated segment\" when a hydration cache cannot answer,
     \"corrupt index segment\" on a same-host hash mismatch (§5.2).")

  (-accessed
    [storage address]
    "No-op hook: a memoized child was reused (§5.1 layering note).")

  (-settings
    [storage]
    "The Settings shared by every tree restored through this storage."))


;; ---------------------------------------------------------------------------
;; Faulted-child references (§5.3). One factory (psset makeReference); the
;; :test ref-type is the deterministic-eviction seam — clearable on demand,
;; because js/WeakRef and Dart WeakReference cannot be forced to clear.

(defprotocol ITestRef

  (test-ref-val [r])

  (test-ref-clear! [r]))


(deftype TestRef
  [#?(:cljd ^:mutable val
      :clj ^:unsynchronized-mutable val
      :cljs ^:mutable val)]

  ITestRef

  (test-ref-val [_] val)


  (test-ref-clear! [_] (set! val nil)))


(defn- make-ref
  "Wrap a freshly faulted (or newly stored) node per the settings ref-type.
   :soft degrades to :weak off-JVM (§5.3)."
  [^Settings sett node]
  (case (.-ref-type sett)
    :strong node
    :test (TestRef. node)
    :soft #?(:cljd (dc/WeakReference. node)
             :clj (java.lang.ref.SoftReference. node)
             :cljs (js/WeakRef. node))
    :weak #?(:cljd (dc/WeakReference. node)
             :clj (java.lang.ref.WeakReference. node)
             :cljs (js/WeakRef. node))))


(defn- make-store-ref
  "Ref for a just-stored child (§3.1 dirty->stored conversion). When the
   node's settings carry no storage (a tree built in memory and then
   stored), a cleared reference could never refault — so pin strong
   regardless of ref-type; the node was strongly held before the store, so
   this is not a memory regression. Trees restored through a storage get
   the configured ref-type."
  [^Settings sett node]
  (if (settings-storage sett) (make-ref sett node) node))


(defn- read-ref
  "Deref a child/root slot: nil, a node held strong, or a reference whose
   target may have been collected (=> nil, refault)."
  [x]
  #?(:cljd (cond (nil? x) nil
                 (instance? TestRef x) (test-ref-val x)
                 (dart/is? x dc/WeakReference) (.-target ^dc/WeakReference x)
                 :else x)
     :clj (cond (nil? x) nil
                (instance? TestRef x) (test-ref-val x)
                (instance? java.lang.ref.Reference x)
                (.get ^java.lang.ref.Reference x)
                :else x)
     :cljs (cond (nil? x) nil
                 (instance? TestRef x) (test-ref-val x)
                 (instance? js/WeakRef x) (.deref x)
                 :else x)))


;; ---------------------------------------------------------------------------
;; Binary search (ANode.search / searchFirst / searchLast)

(defn- search
  "Arrays.binarySearch semantics over keys[0 ... len): index of k, or
   -(insertion-point)-1 when absent. JVM-only `^long` on `len`/loop
   locals/return (§3.2 note in dao.data.arrays): the JFR profiling record
   in docs/design/dao.data.btree.md §6 found this loop's own arithmetic
   was ~45% of the tree's Long allocation, boxing low/high/mid on every
   binary-search step; cljs/cljd get the identical untagged source below.
   `:cljd` must be listed first in the reader conditional — ClojureDart's
   host-eval pass also matches `:clj` branches, so a `:clj`/`:default`
   pair leaks the JVM tag into cljd and fails to compile there."
  #?(:cljd [keys len k cmp]
     :clj ^long [keys ^long len k cmp]
     :cljs [keys len k cmp])
  (loop [low 0
         high (dec len)]
    (if (<= low high)
      (let [mid (quot (+ low high) 2)
            d (cmp (arr/aget keys mid) k)]
        (cond (neg? d) (recur (inc mid) high)
              (pos? d) (recur low (dec mid))
              :else mid))
      (- (- low) 1))))


(defn- search-first
  "First idx in [0 ... len) with keys[idx] >= k; len when none."
  #?(:cljd [keys len k cmp]
     :clj ^long [keys ^long len k cmp]
     :cljs [keys len k cmp])
  (loop [low 0
         high len]
    (if (< low high)
      (let [mid (quot (+ low high) 2)]
        (if (neg? (cmp (arr/aget keys mid) k))
          (recur (inc mid) high)
          (recur low mid)))
      low)))


(defn- search-last
  "Last idx in [0 ... len) with keys[idx] <= k; -1 when none."
  #?(:cljd [keys len k cmp]
     :clj ^long [keys ^long len k cmp]
     :cljs [keys len k cmp])
  (loop [low 0
         high len]
    (if (< low high)
      (let [mid (quot (+ low high) 2)]
        (if (<= (cmp (arr/aget keys mid) k) 0)
          (recur (inc mid) high)
          (recur low mid)))
      (dec low))))


;; ---------------------------------------------------------------------------
;; Stitch (Stitch.java): sequential writes into a target array. Both return
;; the advanced offset. A nil src advances without writing (Java semantics:
;; the slots stay nil), which is how lazily-restored branches with nil
;; children/addresses arrays flow through node surgery.

;; Not primitive-hinted, unlike search/the deftype fields above. Two dead
;; ends, both confirmed empirically rather than assumed:
;; - stitch-all: 5 params, over Clojure's 4-arg ceiling for primitive ARGS
;;   ("fns taking primitives support only 4 or fewer args"); a
;;   return-only name-tag (`(defn ^long stitch-all ...)`) sidesteps that
;;   ceiling but produces no `invokePrim`-style interface (verified via
;;   `(supers (class stitch-all))`: plain `IFn`, not `IFn$...`) — it is
;;   inert metadata here, not a real optimization, for a fn with no
;;   primitive-tagged args.
;; - stitch-one: 3 params, well under the ceiling, and both a vector+arg
;;   tag and a name+arg tag genuinely produce a primitive `IFn$OLLL`-style
;;   interface — but doing so corrupts compilation of a LATER, unrelated
;;   top-level form in this namespace (reproduced repeatedly: `Leaf`'s
;;   deftype fails with "Unable to resolve classname:
;;   clojure.core$long@...", even though Leaf is read and compiled before
;;   stitch-one's callers are ever parsed, which rules out any
;;   call-site-shape explanation — bisected form-by-form to confirm).
;;   Left unhinted; a `definline` variant (sidesteps the IFn machinery
;;   this bug lives in) is the one untried avenue if this is revisited.
(defn- stitch-all
  [target offset src from to]
  (if (>= to from)
    (do (when src (arr/acopy src from target offset (- to from)))
        (+ offset (- to from)))
    offset))


(defn- stitch-one
  [target offset x]
  (arr/aset target offset x) (inc offset))


;; Result arrays (return conventions, §3.3.2)

(defn- one
  [n]
  (let [r (arr/anew 1)]
    (arr/aset r 0 n)
    r))


(defn- two
  [n1 n2]
  (let [r (arr/anew 2)]
    (arr/aset r 0 n1)
    (arr/aset r 1 n2)
    r))


(defn- three
  [l c r*]
  (let [r (arr/anew 3)]
    (arr/aset r 0 l)
    (arr/aset r 1 c)
    (arr/aset r 2 r*)
    r))


;; ---------------------------------------------------------------------------
;; Node protocols

(defprotocol INode

  (node-lim-key
    [node]
    "Max key of the subtree (keys[len-1]).")

  (node-len [node])

  (node-lookup
    [node cmp k]
    "The stored element equal to k under cmp, or nil.")

  (node-conj
    [node cmp k settings]
    "UNCHANGED | EARLY-EXIT | [n] | [n1 n2] (§3.3.2).")

  (node-disj
    [node cmp k root? left right settings]
    "UNCHANGED | EARLY-EXIT | [left center right], entries nilable (§3.3.2)."))


(defprotocol INodeInternal
  "Field access for cross-node surgery, traversal, and the test suite.
   Not part of the public contract."

  (node-level
    [node]
    "0 for leaves, 1+ for branches.")

  (node-keys
    [node]
    "The backing keys array; only [0 ... len) valid.")

  (node-children-arr
    [node]
    "Branch children array or nil; leaf: nil.")

  (node-addresses-arr
    [node]
    "Branch addresses array or nil; leaf: nil.")

  (node-child
    [node i]
    "Resident child at slot i, faulting through the settings' storage when
     the slot is stored-but-unfaulted (§3.1 state 3). Throws \"unhydrated
     segment\" when no storage or address can satisfy the fault (§5.4).")

  (node-store
    [node storage]
    "Store the dirty subgraph rooted here, children before parents (§5.1);
     skips slots that already carry an address (§3.1). Returns this node's
     address."))


(declare ->Leaf ->Branch branch?)


(defn- safe-len
  [node]
  (if (nil? node) -1 (node-len node)))


(defn- unhydrated!
  [i]
  (throw (ex-info "unhydrated segment" {:slot i})))


;; ---------------------------------------------------------------------------
;; Leaf (Leaf.java)

(deftype Leaf
  [#?(:cljd ^:mutable len
      :clj ^long ^:unsynchronized-mutable len
      :cljs ^:mutable len) keys settings]

  INode

  (node-lim-key [_] (arr/aget keys (dec len)))


  (node-len [_] len)


  (node-lookup
    [_ cmp k]
    (let [idx (search keys len k cmp)] (when (>= idx 0) (arr/aget keys idx))))


  (node-conj
    [this cmp k sett]
    (let [idx (search keys len k cmp)]
      (if (>= idx 0)
        UNCHANGED ; already in set
        (let [ins (- (- idx) 1)]
          (cond
            ;; transient in-place (Leaf.add editable): this node is owned
            ;; by the live lease and has spare allocated capacity
            (and (editing? settings) (< len (arr/alength keys)))
            (if (== ins len)
              (do (arr/aset keys len k) (set! len (inc len)) (one this)) ; appended
              ;; at end: MaxKey changed
              (do (arr/acopy keys ins keys (inc ins) (- len ins))
                  (arr/aset keys ins k)
                  (set! len (inc len))
                  EARLY-EXIT))
            ;; simply adding (grown allocation under a lease)
            (< len (bf* settings)) (let [nl (inc len)
                                         ks (arr/anew (new-cap nl sett))]
                                     (as-> 0 o
                                           (stitch-all ks o keys 0 ins)
                                           (stitch-one ks o k)
                                           (stitch-all ks o keys ins len))
                                     (one (Leaf. nl ks sett)))
            ;; splitting
            :else (let [h1 (quot (inc len) 2)
                        h2 (- (inc len) h1)]
                    (if (< ins h1)
                      ;; new key goes to the first half
                      (let [k1 (arr/anew h1)
                            k2 (arr/anew h2)]
                        (as-> 0 o
                              (stitch-all k1 o keys 0 ins)
                              (stitch-one k1 o k)
                              (stitch-all k1 o keys ins (dec h1)))
                        (arr/acopy keys (dec h1) k2 0 (- len (dec h1)))
                        (two (Leaf. h1 k1 sett) (Leaf. h2 k2 sett)))
                      ;; copy first, insert into second
                      (let [k1 (arr/asub keys 0 h1)
                            k2 (arr/anew h2)]
                        (as-> 0 o
                              (stitch-all k2 o keys h1 ins)
                              (stitch-one k2 o k)
                              (stitch-all k2 o keys ins len))
                        (two (Leaf. h1 k1 sett) (Leaf. h2 k2 sett))))))))))


  (node-disj
    [this cmp k root? left right sett]
    (let [idx (search keys len k cmp)]
      (if (neg? idx)
        UNCHANGED ; not in set
        (let [new-len (dec len)
              llen (safe-len left)
              rlen (safe-len right)]
          (cond
            ;; nothing to merge
            (or (>= new-len (min-bf* settings))
                (and (nil? left) (nil? right)))
            (if (editing? settings)
              ;; transient in-place (Leaf.remove editable)
              (do (arr/acopy keys (inc idx) keys idx (- len (inc idx)))
                  (set! len new-len)
                  (if (== idx new-len)
                    (three left this right) ; removed last: maxKey
                    ;; changed
                    EARLY-EXIT))
              (let [ck (arr/aremove-at keys len idx)]
                (three left (Leaf. new-len ck sett) right)))
            ;; can join with left
            (and (some? left) (<= (+ llen new-len) (bf* settings)))
            (let [jl (+ llen new-len)
                  jk (arr/anew jl)]
              (as-> 0 o
                    (stitch-all jk o (node-keys left) 0 llen)
                    (stitch-all jk o keys 0 idx)
                    (stitch-all jk o keys (inc idx) len))
              (three nil (Leaf. jl jk sett) right))
            ;; can join with right
            (and (some? right) (<= (+ new-len rlen) (bf* settings)))
            (let [jl (+ new-len rlen)
                  jk (arr/anew jl)]
              (as-> 0 o
                    (stitch-all jk o keys 0 idx)
                    (stitch-all jk o keys (inc idx) len)
                    (stitch-all jk o (node-keys right) 0 rlen))
              (three left (Leaf. jl jk sett) nil))
            ;; borrow from left
            (and (some? left) (or (nil? right) (>= llen rlen)))
            (let [total (+ llen new-len)
                  nll (quot total 2)
                  ncl (- total nll)
                  ck (arr/anew ncl)
                  lk (arr/asub (node-keys left) 0 nll)]
              (as-> 0 o
                    (stitch-all ck o (node-keys left) nll llen)
                    (stitch-all ck o keys 0 idx)
                    (stitch-all ck o keys (inc idx) len))
              (three (Leaf. nll lk sett) (Leaf. ncl ck sett) right))
            ;; borrow from right
            (some? right)
            (let [total (+ new-len rlen)
                  ncl (quot total 2)
                  nrl (- total ncl)
                  right-head (- rlen nrl)
                  ck (arr/anew ncl)
                  rk (arr/asub (node-keys right) right-head rlen)]
              (as-> 0 o
                    (stitch-all ck o keys 0 idx)
                    (stitch-all ck o keys (inc idx) len)
                    (stitch-all ck o (node-keys right) 0 right-head))
              (three left (Leaf. ncl ck sett) (Leaf. nrl rk sett)))
            :else (throw (ex-info "unreachable leaf disj state" {})))))))


  INodeInternal

  (node-level [_] 0)


  (node-keys [_] keys)


  (node-children-arr [_] nil)


  (node-addresses-arr [_] nil)


  (node-child [_ i] (unhydrated! i))


  (node-store [this storage] (-store storage this)))


;; ---------------------------------------------------------------------------
;; Branch (Branch.java). Per-slot state machine per §3.1; Phase 1 trees are
;; all-dirty (addresses nil, children resident and strong).

(deftype Branch
  [#?(:cljd level
      :clj ^long level
      :cljs level)
   #?(:cljd len
      :clj ^long len
      :cljs len) keys
   #?(:cljd ^:mutable children
      :clj ^:unsynchronized-mutable children
      :cljs ^:mutable children)
   #?(:cljd ^:mutable addresses
      :clj ^:unsynchronized-mutable addresses
      :cljs ^:mutable addresses) settings]

  INode

  (node-lim-key [_] (arr/aget keys (dec len)))


  (node-len [_] len)


  (node-lookup
    [this cmp k]
    (let [idx (search keys len k cmp)]
      (if (>= idx 0)
        (arr/aget keys idx) ; separator is the stored max element
        (let [ins (- (- idx) 1)]
          (when (< ins len) (node-lookup (node-child this ins) cmp k))))))


  (node-conj
    [this cmp k sett]
    (let [idx (search keys len k cmp)]
      (if (>= idx 0)
        UNCHANGED ; already in set
        (let [ins0 (- (- idx) 1)
              ins (if (== ins0 len) (dec len) ins0)
              nodes (node-conj (node-child this ins) cmp k sett)]
          (cond
            (identical? UNCHANGED nodes) UNCHANGED
            (identical? EARLY-EXIT nodes) EARLY-EXIT
            ;; single replacement node — same len
            (== 1 (arr/alength nodes))
            (if (and (editing? settings) children)
              ;; transient in-place (Branch.add editable): swap key +
              ;; child
              (do (arr/aset keys ins (node-lim-key (arr/aget nodes 0)))
                  (when addresses (arr/aset addresses ins nil))
                  (arr/aset children ins (arr/aget nodes 0))
                  (if (== ins (dec len))
                    (one this) ; maxKey changed: propagate upward
                    EARLY-EXIT))
              (let [node (arr/aget nodes 0)
                    nk (if (== 0
                               (cmp (node-lim-key node)
                                    (arr/aget keys ins)))
                         keys ; share unmodified keys array
                         (let [ks (arr/asub keys 0 len)]
                           (arr/aset ks ins (node-lim-key node))
                           ks))
                    na (when addresses
                         (let [as (arr/asub addresses 0 len)]
                           (arr/aset as ins nil)
                           as))
                    nc (let [cs (if children
                                  (arr/asub children 0 len)
                                  (arr/anew len))]
                         (arr/aset cs ins node)
                         cs)]
                (one (Branch. level len nk nc na sett))))
            ;; two nodes, len + 1 still fits (grown allocation under
            ;; lease)
            (< len (bf* settings))
            (let [n1 (arr/aget nodes 0)
                  n2 (arr/aget nodes 1)
                  nl (inc len)
                  cap (new-cap nl sett)
                  ks (arr/anew cap)
                  cs (arr/anew cap)
                  as (when addresses (arr/anew cap))]
              (as-> 0 o
                    (stitch-all ks o keys 0 ins)
                    (stitch-one ks o (node-lim-key n1))
                    (stitch-one ks o (node-lim-key n2))
                    (stitch-all ks o keys (inc ins) len))
              (as-> 0 o
                    (stitch-all cs o children 0 ins)
                    (stitch-one cs o n1)
                    (stitch-one cs o n2)
                    (stitch-all cs o children (inc ins) len))
              (when as
                (as-> 0 o
                      (stitch-all as o addresses 0 ins)
                      (stitch-one as o nil)
                      (stitch-one as o nil)
                      (stitch-all as o addresses (inc ins) len)))
              (one (Branch. level nl ks cs as sett)))
            ;; split
            :else
            (let [n1 (arr/aget nodes 0)
                  n2 (arr/aget nodes 1)
                  h1 (let [h (quot (inc len) 2)]
                       (if (== (inc ins) h) (inc h) h))
                  h2 (- (inc len) h1)]
              (if (< ins h1)
                ;; add to first half
                (let [ks1 (arr/anew h1)
                      ks2 (arr/anew h2)
                      cs1 (arr/anew h1)
                      cs2 (when children (arr/anew h2))
                      as1 (when addresses (arr/anew h1))
                      as2 (when addresses (arr/anew h2))]
                  (as-> 0 o
                        (stitch-all ks1 o keys 0 ins)
                        (stitch-one ks1 o (node-lim-key n1))
                        (stitch-one ks1 o (node-lim-key n2))
                        (stitch-all ks1 o keys (inc ins) (dec h1)))
                  (arr/acopy keys (dec h1) ks2 0 (- len (dec h1)))
                  (as-> 0 o
                        (stitch-all cs1 o children 0 ins)
                        (stitch-one cs1 o n1)
                        (stitch-one cs1 o n2)
                        (stitch-all cs1 o children (inc ins) (dec h1)))
                  (when cs2
                    (arr/acopy children (dec h1) cs2 0 (- len (dec h1))))
                  (when as1
                    (as-> 0 o
                          (stitch-all as1 o addresses 0 ins)
                          (stitch-one as1 o nil)
                          (stitch-one as1 o nil)
                          (stitch-all as1 o addresses (inc ins) (dec h1)))
                    (arr/acopy addresses (dec h1) as2 0 (- len (dec h1))))
                  (two (Branch. level h1 ks1 cs1 as1 sett)
                       (Branch. level h2 ks2 cs2 as2 sett)))
                ;; add to second half
                (let [ks1 (arr/asub keys 0 h1)
                      ks2 (arr/anew h2)
                      cs1 (when children (arr/asub children 0 h1))
                      cs2 (arr/anew h2)
                      as1 (when addresses (arr/asub addresses 0 h1))
                      as2 (when addresses (arr/anew h2))]
                  (as-> 0 o
                        (stitch-all ks2 o keys h1 ins)
                        (stitch-one ks2 o (node-lim-key n1))
                        (stitch-one ks2 o (node-lim-key n2))
                        (stitch-all ks2 o keys (inc ins) len))
                  (as-> 0 o
                        (stitch-all cs2 o children h1 ins)
                        (stitch-one cs2 o n1)
                        (stitch-one cs2 o n2)
                        (stitch-all cs2 o children (inc ins) len))
                  (when as2
                    (as-> 0 o
                          (stitch-all as2 o addresses h1 ins)
                          (stitch-one as2 o nil)
                          (stitch-one as2 o nil)
                          (stitch-all as2 o addresses (inc ins) len)))
                  (two (Branch. level h1 ks1 cs1 as1 sett)
                       (Branch. level h2 ks2 cs2 as2 sett))))))))))


  (node-disj
    [this cmp k root? left right sett]
    (let [idx0 (search keys len k cmp)
          idx (if (neg? idx0) (- (- idx0) 1) idx0)]
      (if (== idx len)
        UNCHANGED ; not in set
        (let [left-child (when (pos? idx) (node-child this (dec idx)))
              right-child (when (< idx (dec len)) (node-child this (inc idx)))
              lc-len (safe-len left-child)
              rc-len (safe-len right-child)
              nodes (node-disj (node-child this idx)
                               cmp
                               k
                               false
                               left-child
                               right-child
                               sett)]
          (cond
            (identical? UNCHANGED nodes) UNCHANGED
            (identical? EARLY-EXIT nodes) EARLY-EXIT
            :else
            (let [n0 (arr/aget nodes 0)
                  n1 (arr/aget nodes 1) ; never nil
                  n2 (arr/aget nodes 2)
                  left-changed? (or (not (identical? left-child n0))
                                    (not (== lc-len (safe-len n0))))
                  right-changed? (or (not (identical? right-child n2))
                                     (not (== rc-len (safe-len n2))))
                  new-len (+ (- (dec len)
                                (if left-child 1 0)
                                (if right-child 1 0))
                             (if n0 1 0)
                             1
                             (if n2 1 0))
                  addr-at (fn [i] (when addresses (arr/aget addresses i)))
                  ;; shared stitch chains for the center region:
                  ;; keys[0, idx-1) ++ maxKeys(n0? n1 n2?) ++
                  ;; keys[idx+2, len)
                  stitch-center-keys!
                  (fn [ks o]
                    (as-> o o
                          (if n0 (stitch-one ks o (node-lim-key n0)) o)
                          (stitch-one ks o (node-lim-key n1))
                          (if n2 (stitch-one ks o (node-lim-key n2)) o)
                          (stitch-all ks o keys (+ idx 2) len)))
                  stitch-center-children!
                  (fn [cs o]
                    (as-> o o
                          (if n0 (stitch-one cs o n0) o)
                          (stitch-one cs o n1)
                          (if n2 (stitch-one cs o n2) o)
                          (stitch-all cs o children (+ idx 2) len)))
                  stitch-center-addresses!
                  (fn [as o]
                    (as-> o o
                          (if n0
                            (stitch-one
                              as
                              o
                              (if left-changed? nil (addr-at (dec idx))))
                            o)
                          (stitch-one as o nil)
                          (if n2
                            (stitch-one
                              as
                              o
                              (if right-changed? nil (addr-at (inc idx))))
                            o)
                          (stitch-all as o addresses (+ idx 2) len)))]
              (cond
                ;; no rebalance needed
                (or (>= new-len (min-bf* settings))
                    (and (nil? left) (nil? right)))
                (let [ks (arr/anew new-len)
                      cs (arr/anew new-len)
                      as (when addresses (arr/anew new-len))]
                  (stitch-center-keys! ks
                                       (stitch-all ks 0 keys 0 (dec idx)))
                  (stitch-center-children!
                    cs
                    (stitch-all cs 0 children 0 (dec idx)))
                  (when as
                    (stitch-center-addresses!
                      as
                      (stitch-all as 0 addresses 0 (dec idx))))
                  (three left
                         (Branch. level new-len ks cs as sett)
                         right))
                ;; can join with left
                (and (some? left)
                     (<= (+ (node-len left) new-len) (bf* settings)))
                (let [llen (node-len left)
                      jl (+ llen new-len)
                      la (node-addresses-arr left)
                      ks (arr/anew jl)
                      cs (arr/anew jl)
                      as (when (or la addresses) (arr/anew jl))]
                  (as-> 0 o
                        (stitch-all ks o (node-keys left) 0 llen)
                        (stitch-all ks o keys 0 (dec idx))
                        (stitch-center-keys! ks o))
                  (as-> 0 o
                        (stitch-all cs o (node-children-arr left) 0 llen)
                        (stitch-all cs o children 0 (dec idx))
                        (stitch-center-children! cs o))
                  (when as
                    (as-> 0 o
                          (stitch-all as o la 0 llen)
                          (stitch-all as o addresses 0 (dec idx))
                          (stitch-center-addresses! as o)))
                  (three nil (Branch. level jl ks cs as sett) right))
                ;; can join with right
                (and (some? right)
                     (<= (+ new-len (node-len right)) (bf* settings)))
                (let [rlen (node-len right)
                      jl (+ new-len rlen)
                      ra (node-addresses-arr right)
                      ks (arr/anew jl)
                      cs (arr/anew jl)
                      as (when (or addresses ra) (arr/anew jl))]
                  (as-> 0 o
                        (stitch-all ks o keys 0 (dec idx))
                        (stitch-center-keys! ks o)
                        (stitch-all ks o (node-keys right) 0 rlen))
                  (as-> 0 o
                        (stitch-all cs o children 0 (dec idx))
                        (stitch-center-children! cs o)
                        (stitch-all cs o (node-children-arr right) 0 rlen))
                  (when as
                    (as-> 0 o
                          (stitch-all as o addresses 0 (dec idx))
                          (stitch-center-addresses! as o)
                          (stitch-all as o ra 0 rlen)))
                  (three left (Branch. level jl ks cs as sett) nil))
                ;; borrow from left
                (and (some? left)
                     (or (nil? right)
                         (>= (node-len left) (node-len right))))
                (let [llen (node-len left)
                      total (+ llen new-len)
                      nll (quot total 2)
                      ncl (- total nll)
                      la (node-addresses-arr left)
                      lc (node-children-arr left)
                      lk (node-keys left)
                      nlk (arr/asub lk 0 nll)
                      nlc (let [c (arr/anew nll)]
                            (stitch-all c 0 lc 0 nll)
                            c)
                      nla (when la (arr/asub la 0 nll))
                      ks (arr/anew ncl)
                      cs (arr/anew ncl)
                      as (when (or la addresses) (arr/anew ncl))]
                  (as-> 0 o
                        (stitch-all ks o lk nll llen)
                        (stitch-all ks o keys 0 (dec idx))
                        (stitch-center-keys! ks o))
                  (as-> 0 o
                        (stitch-all cs o lc nll llen)
                        (stitch-all cs o children 0 (dec idx))
                        (stitch-center-children! cs o))
                  (when as
                    (as-> 0 o
                          (stitch-all as o la nll llen)
                          (stitch-all as o addresses 0 (dec idx))
                          (stitch-center-addresses! as o)))
                  (three (Branch. level nll nlk nlc nla sett)
                         (Branch. level ncl ks cs as sett)
                         right))
                ;; borrow from right
                (some? right)
                (let [rlen (node-len right)
                      total (+ new-len rlen)
                      ncl (quot total 2)
                      nrl (- total ncl)
                      right-head (- rlen nrl)
                      ra (node-addresses-arr right)
                      rc (node-children-arr right)
                      rk (node-keys right)
                      nrk (arr/asub rk right-head rlen)
                      nrc (let [c (arr/anew nrl)]
                            (stitch-all c 0 rc right-head rlen)
                            c)
                      nra (when ra (arr/asub ra right-head rlen))
                      ks (arr/anew ncl)
                      cs (arr/anew ncl)
                      as (when (or addresses ra) (arr/anew ncl))]
                  (as-> 0 o
                        (stitch-all ks o keys 0 (dec idx))
                        (stitch-center-keys! ks o)
                        (stitch-all ks o rk 0 right-head))
                  (as-> 0 o
                        (stitch-all cs o children 0 (dec idx))
                        (stitch-center-children! cs o)
                        (stitch-all cs o rc 0 right-head))
                  (when as
                    (as-> 0 o
                          (stitch-all as o addresses 0 (dec idx))
                          (stitch-center-addresses! as o)
                          (stitch-all as o ra 0 right-head)))
                  (three left
                         (Branch. level ncl ks cs as sett)
                         (Branch. level nrl nrk nrc nra sett)))
                :else (throw (ex-info "unreachable branch disj state"
                                      {})))))))))


  INodeInternal

  (node-level [_] level)


  (node-keys [_] keys)


  (node-children-arr [_] children)


  (node-addresses-arr [_] addresses)


  (node-child
    [this i]
    ;; §3.1 per-slot state machine: state 1 (dirty) and state 2 (stored,
    ;; memoized) deref the slot; state 3 (stored, not faulted) restores
    ;; at addresses[i] and memoizes a ref-type reference. No storage
    ;; attached or no address => the slot is unhydrated (§5.4).
    (let [c (when children (read-ref (arr/aget children i)))]
      (if (some? c)
        (do (when-some [addr (when addresses (arr/aget addresses i))]
              (when-some [st (settings-storage settings)]
                (-accessed st addr)))
            c)
        (let [addr (when addresses (arr/aget addresses i))
              st (settings-storage settings)]
          (if (or (nil? addr) (nil? st))
            (unhydrated! i)
            (let [n (-restore st addr)]
              (when (nil? children)
                (set! children (arr/anew (arr/alength keys))))
              (arr/aset children i (make-ref settings n))
              n))))))


  (node-store
    [this storage]
    ;; children before parents (§5.1); slots already carrying an address
    ;; are clean subtrees and are skipped (§3.1) — this is what makes
    ;; incremental re-publish proportional to the changed path.
    (when (nil? addresses) (set! addresses (arr/anew (arr/alength keys))))
    (dotimes [i len]
      (when (nil? (arr/aget addresses i))
        (let [c (read-ref (arr/aget children i))]
          (when (nil? c)
            (throw (ex-info "dirty child not resident" {:slot i})))
          (arr/aset addresses i (node-store c storage))
          (arr/aset children i (make-store-ref settings c)))))
    (-store storage this)))


(defn branch?
  [node]
  (instance? Branch node))


;; ---------------------------------------------------------------------------
;; Blob conversion (§5.1/§5.2): pure functions over the EDN shapes
;; `publish-index!` persists today. Leaf `{:keys [...]}`, Branch
;; `{:level n :keys [...] :addresses [...]}`. Bounded by len, never by
;; allocation (grown transient arrays carry spare slots).

(defn node->blob
  "The §5.2 EDN blob for node. Branch slots must all be addressed (§3.1:
   no partially-addressed branch is ever serialized)."
  [node]
  (let [ks (node-keys node)
        l (node-len node)
        keys-v (mapv #(arr/aget ks %) (range l))]
    (if (branch? node)
      (let [as (node-addresses-arr node)]
        {:level (node-level node),
         :keys keys-v,
         :addresses (mapv #(arr/aget as %) (range l))})
      {:keys keys-v})))


(defn blob->node
  "Rebuild a node from its blob; type from blob shape (a map carrying
   :addresses is a Branch, one with :keys alone a Leaf — §5.1). Every node
   carries sett (the §5.1 settings-threading rule). Branch children start
   nil: every slot is §3.1 state 3."
  [blob ^Settings sett]
  (let [ks (:keys blob)]
    (if-some [as (:addresses blob)]
      (Branch. (:level blob)
               (clojure.core/count ks)
               (arr/from-coll ks)
               nil
               (arr/from-coll as)
               sett)
      (Leaf. (clojure.core/count ks) (arr/from-coll ks) sett))))


;; ---------------------------------------------------------------------------
;; Direct tree reduce: IReduce walks the node arrays instead of realizing
;; the lazy path-stack seq (a ~5x win measured against psset's chunked Seq
;; on full-set reduce). Early termination propagates `reduced` upward
;; unwrapped; the entry points deref it.

(def ^:private no-init
  "Identity sentinel: tree-reduce-1 accumulator before the first element."
  (arr/anew 0))


(defn- node-reduce
  [node f acc]
  (if (branch? node)
    (let [l (node-len node)]
      (loop [i 0
             acc acc]
        (if (< i l)
          (let [acc' (node-reduce (node-child node i) f acc)]
            (if (reduced? acc') acc' (recur (inc i) acc')))
          acc)))
    (let [ks (node-keys node)
          l (long (node-len node))]
      ;; hot loop: raw host array access, not the arr/aget fn indirection
      (loop [i 0
             acc acc]
        (if (< i l)
          (let [acc' (f acc
                        #?(:cljd (clojure.core/aget ks (int i))
                           :clj (clojure.core/aget ^objects ks i)
                           :cljs (unchecked-get ks i)))]
            (if (reduced? acc') acc' (recur (inc i) acc')))
          acc)))))


(defn- tree-reduce
  "clojure.core/reduce semantics over the tree, without a seq."
  ([root f]
   (let [r (node-reduce root
                        (fn [acc x] (if (identical? acc no-init) x (f acc x)))
                        no-init)]
     (cond (reduced? r) (deref r)
           (identical? r no-init) (f)
           :else r)))
  ([root f init]
   (let [r (node-reduce root f init)] (if (reduced? r) (deref r) r))))


;; ---------------------------------------------------------------------------
;; BTSet wrapper (§4): {meta cmp address storage root cnt version settings}
;; plus the memoized unordered-hash slot (hmemo, per-version — a new BTSet
;; starts with an empty memo; §4). Phase 2 implements the host collection
;; protocol matrix; this namespace's named fns remain the primary API and
;; the protocol methods delegate to them. IEditableCollection/transients
;; land in Phase 3; durability fields (address/storage) activate in Phase 4.

(declare conj
         disj
         lookup
         contains?
         slice
         rslice
         slice*
         rslice*
         seq
         rseq
         empty-root
         equiv-sets
         nil-element!
         resident-root
         ->TBTSet)


(defprotocol IBTSetImpl
  "Internal: root residency and the store entry point — deftype methods
   because they mutate the wrapper's memo fields (§4: root is itself a
   ref-type reference refaulted from address; store-tree is a no-op when
   address is non-nil)."

  (resident-root [s])

  (-store-tree! [s storage])

  (raw-root
    [s]
    "The root slot as held: node, ref, or nil. No faulting.")

  (raw-address [s])

  (raw-storage [s]))


(deftype BTSet
  [meta cmp
   #?(:cljd ^:mutable address
      :clj ^:unsynchronized-mutable address
      :cljs ^:mutable address)
   #?(:cljd ^:mutable storage
      :clj ^:unsynchronized-mutable storage
      :cljs ^:mutable storage)
   #?(:cljd ^:mutable root
      :clj ^:unsynchronized-mutable root
      :cljs ^:mutable root)
   #?(:cljd cnt
      :clj ^long cnt
      :cljs cnt)
   #?(:cljd version
      :clj ^long version
      :cljs version) settings
   #?(:cljd ^:mutable hmemo
      :clj ^:unsynchronized-mutable hmemo
      :cljs ^:mutable hmemo)]

  IBTSetImpl

  (resident-root
    [this]
    ;; §4: root is itself a ref-type reference; refault from address when
    ;; it has cleared. Memoized per wrapper, like any branch child slot.
    (let [r (read-ref root)]
      (if (some? r)
        r
        (let [st storage]
          (when (or (nil? address) (nil? st))
            (throw (ex-info "no resident root and no address to restore from"
                            {:address address})))
          (let [n (-restore st address)]
            (set! root (make-ref settings n))
            n)))))


  (-store-tree!
    [this storage']
    ;; no-op when already addressed (§4/§5.1): an unchanged tree
    ;; re-publishes for free
    (if (some? address)
      address
      (let [r (read-ref root)]
        (when (nil? r) (throw (ex-info "dirty root not resident" {})))
        (let [addr (node-store r storage')]
          (set! storage storage')
          (set! address addr)
          (set! root (make-store-ref settings r))
          addr))))


  (raw-root [_] root)


  (raw-address [_] address)


  (raw-storage [_] storage)
  #?@(:cljd [cljd.core/ISeqable (-seq [this] (seq this))
             cljd.core/ICounted (-count [_] cnt)
             cljd.core/ICollection (-conj [this k] (conj this k))
             cljd.core/ISet (-disjoin [this k] (disj this k))
             cljd.core/IEmptyableCollection (-empty [_]
                                                    (BTSet. meta
                                                            cmp
                                                            nil
                                                            storage
                                                            (empty-root settings)
                                                            0
                                                            0
                                                            settings
                                                            nil))
             cljd.core/ILookup (-lookup [this k] (lookup this k))
             (-lookup [this k not-found]
                      (if-some [v (lookup this k)]
                        v
                        not-found))
             (-contains-key? [this k] (some? (lookup this k)))
             cljd.core/IEquiv (-equiv [this other] (equiv-sets this other))
             cljd.core/IHash (-hash [this]
                                    (if (nil? hmemo)
                                      (let [h (hash-unordered-coll this)]
                                        (set! hmemo h)
                                        h)
                                      hmemo))
             (-hash-realized? [_] (some? hmemo)) cljd.core/IReversible
             (-rseq [this] (rseq this)) cljd.core/ISorted
             (-sorted-seq [this from to flags]
                          (let [flags (int flags)]
                            (slice* this
                                    (when (pos? (bit-and flags 8)) from)
                                    (pos? (bit-and flags 4))
                                    (when (pos? (bit-and flags 2)) to)
                                    (pos? (bit-and flags 1))
                                    cmp)))
             (-sorted-rseq [this from to flags]
                           ;; from/to are not swapped (cljd contract): range [from
                           ;; to], iterated descending — start at the `to` side, stop
                           ;; at `from`
                           (let [flags (int flags)]
                             (rslice* this
                                      (when (pos? (bit-and flags 2)) to)
                                      (pos? (bit-and flags 1))
                                      (when (pos? (bit-and flags 8)) from)
                                      (pos? (bit-and flags 4))
                                      cmp)))
             cljd.core/IReduce
             (-reduce [this f]
                      (if (zero? cnt) (f) (tree-reduce (resident-root this) f)))
             (-reduce [this f start]
                      (if (zero? cnt)
                        start
                        (tree-reduce (resident-root this) f start)))
             cljd.core/IFn
             (-invoke [this k] (lookup this k)) (-invoke [this k not-found]
                                                         (if-some [v (lookup this k)]
                                                           v
                                                           not-found))
             cljd.core/IMeta (-meta [_] meta)
             cljd.core/IWithMeta
             (-with-meta [_ m]
                         (BTSet. m cmp address storage root cnt version settings hmemo))
             cljd.core/IEditableCollection (-as-transient [this]
                                                          (->TBTSet meta
                                                                    cmp
                                                                    storage
                                                                    address
                                                                    (resident-root this)
                                                                    cnt
                                                                    version
                                                                    (editable-settings
                                                                      settings)))
             cljd.core/IPrint (-print [this sink]
                                      (.write sink
                                              (apply str
                                                     (clojure.core/concat
                                                       ["#{"]
                                                       (interpose " "
                                                                  (map pr-str (or (seq this) ())))
                                                       ["}"]))))]
      :clj [clojure.lang.IPersistentSet (disjoin [this k] (disj this k))
            (contains [this k] (clojure.core/boolean (contains? this k)))
            (get [this k] (lookup this k))
            ;; IPersistentCollection
            (count [_] cnt) (cons [this k] (conj this k))
            (empty [_]
                   (BTSet. meta
                           cmp
                           nil
                           storage
                           (empty-root settings)
                           0
                           0
                           settings
                           nil))
            (equiv [this other] (equiv-sets this other))
            ;; Seqable
            (seq [this] (slice this nil nil)) clojure.lang.Sorted
            (comparator [_] cmp) (entryKey [_ entry] entry)
            (seq [this ascending] (if ascending (seq this) (rseq this)))
            (seqFrom [this k ascending]
                     (if ascending (slice this k nil) (rslice this k nil)))
            clojure.lang.Reversible (rseq [this] (rslice this nil nil))
            clojure.lang.IObj
            (withMeta [_ m]
                      (BTSet. m cmp address storage root cnt version settings hmemo))
            (meta [_] meta) clojure.lang.ILookup
            (valAt [this k] (lookup this k)) (valAt [this k not-found]
                                                    (if-some [v (lookup this k)]
                                                      v
                                                      not-found))
            clojure.lang.IFn (invoke [this k] (lookup this k))
            (invoke [this k not-found]
                    (if-some [v (lookup this k)]
                      v
                      not-found))
            (applyTo [this args] (clojure.lang.AFn/applyToHelper this args))
            clojure.lang.IHashEq (hasheq [this]
                                         (if (nil? hmemo)
                                           (let [h (clojure.lang.Murmur3/hashUnordered
                                                     this)]
                                             (set! hmemo h)
                                             h)
                                           hmemo))
            clojure.lang.IReduce
            (reduce [this f]
                    (if (zero? cnt) (f) (tree-reduce (resident-root this) f)))
            (reduce [this f start]
                    (if (zero? cnt) start (tree-reduce (resident-root this) f start)))
            java.lang.Iterable
            (iterator [this] (clojure.lang.SeqIterator. (seq this)))
            java.util.Set
            (size [_] (int cnt)) (isEmpty [_] (zero? cnt))
            (containsAll [this c] (every? #(contains? this %) c))
            (^objects toArray [this] (clojure.lang.RT/seqToArray (seq this)))
            (^objects toArray
             [this ^objects a]
             (clojure.lang.RT/seqToPassedArray (seq this) a))
            (add [_ _] (throw (UnsupportedOperationException.)))
            (remove [_ _] (throw (UnsupportedOperationException.)))
            (addAll [_ _] (throw (UnsupportedOperationException.)))
            (removeAll [_ _] (throw (UnsupportedOperationException.)))
            (retainAll [_ _] (throw (UnsupportedOperationException.)))
            (clear [_] (throw (UnsupportedOperationException.)))
            java.io.Serializable
            clojure.lang.IEditableCollection (asTransient [this]
                                                          (->TBTSet meta
                                                                    cmp
                                                                    storage
                                                                    address
                                                                    (resident-root this)
                                                                    cnt
                                                                    version
                                                                    (editable-settings
                                                                      settings)))
            Object (equals [this other]
                           (clojure.core/boolean
                             (or (identical? this other)
                                 (and (instance? java.util.Set other)
                                      (== cnt (.size ^java.util.Set other))
                                      (every? #(contains? this %) other)))))
            (hashCode [this]
                      ;; java.util.Set contract: sum of element hashCodes
                      (clojure.core/reduce (fn [^long acc x]
                                             (unchecked-add-int acc
                                                                (.hashCode ^Object x)))
                                           (int 0)
                                           (or (seq this) ())))
            (toString [this]
                      (apply str
                             (clojure.core/concat ["#{"]
                                                  (interpose " "
                                                             (map pr-str (or (seq this) ())))
                                                  ["}"])))]
      :cljs [ISeqable (-seq [this] (seq this))
             ICounted (-count [_] cnt)
             ICollection (-conj [this k] (conj this k))
             ISet (-disjoin [this k] (disj this k))
             IEmptyableCollection (-empty [_]
                                          (BTSet. meta
                                                  cmp
                                                  nil
                                                  storage
                                                  (empty-root settings)
                                                  0
                                                  0
                                                  settings
                                                  nil))
             ILookup (-lookup [this k] (lookup this k))
             (-lookup [this k not-found]
                      (if-some [v (lookup this k)]
                        v
                        not-found))
             IEquiv
             (-equiv [this other] (equiv-sets this other)) IHash
             (-hash [this]
                    (if (nil? hmemo)
                      (let [h (hash-unordered-coll this)]
                        (set! hmemo h)
                        h)
                      hmemo))
             IReversible
             (-rseq [this] (rseq this)) ISorted
             (-sorted-seq [this ascending?]
                          (if ascending? (seq this) (rseq this)))
             (-sorted-seq-from [this k ascending?]
                               (if ascending? (slice this k nil) (rslice this k nil)))
             (-entry-key [_ entry] entry) (-comparator [_] cmp)
             IReduce
             (-reduce [this f]
                      (if (zero? cnt) (f) (tree-reduce (resident-root this) f)))
             (-reduce [this f start]
                      (if (zero? cnt)
                        start
                        (tree-reduce (resident-root this) f start)))
             IFn
             (-invoke [this k] (lookup this k)) (-invoke [this k not-found]
                                                         (if-some [v (lookup this k)]
                                                           v
                                                           not-found))
             IMeta (-meta [_] meta)
             IWithMeta
             (-with-meta [_ m]
                         (BTSet. m cmp address storage root cnt version settings hmemo))
             IEditableCollection (-as-transient [this]
                                                (->TBTSet meta
                                                          cmp
                                                          storage
                                                          address
                                                          (resident-root this)
                                                          cnt
                                                          version
                                                          (editable-settings settings)))
             Object (toString [this]
                              (apply str
                                     (clojure.core/concat ["#{"]
                                                          (interpose " "
                                                                     (map pr-str (or (seq this) ())))
                                                          ["}"])))
             IPrintWithWriter (-pr-writer [this writer _opts]
                                          (-write writer (.toString this)))]))


#?(:cljd nil
   :clj (defmethod print-method BTSet [s ^java.io.Writer w] (.write w (str s))))


(defn- equiv-sets
  "Set equality under the host's `=`: other is a set, same size, and every
   element of other is contained here (via this set's comparator, matching
   psset's APersistentSortedSet.equiv)."
  [^BTSet s other]
  (boolean (and #?(:cljd (set? other)
                   :clj (instance? java.util.Set other)
                   :cljs (set? other))
                (== (.-cnt s) (clojure.core/count other))
                (every? #(contains? s %) other))))


(defn- empty-root
  [^Settings sett]
  (Leaf. 0 (arr/anew 0) sett))


;; ---------------------------------------------------------------------------
;; Transients (Phase 3, §3.1/§6). TBTSet is a separate mutable wrapper — the
;; standard core split (PersistentHashSet/TransientHashSet), not psset's
;; single-class design, which needs covariant returns a deftype cannot
;; express. Two consequences worth naming:
;;
;; - Iterating a transient is unrepresentable: TBTSet is not seqable, so
;;   psset's version-capture fail-fast (Seq captures _version) has nothing
;;   to guard. Persistent snapshots are immune by the lease-ownership rule:
;;   in-place mutation touches only nodes created under the live lease, and
;;   no pre-transient node ever points at a lease-owned node.
;; - EARLY-EXIT adjusts cnt/version here (psset's alterCount on that path);
;;   the persistent wrapper keeps throwing on it.

(defprotocol ITransientBTSet
  "Internal: the transient operations, implemented once; the host transient
   protocols in the TBTSet splices delegate here."

  (t-conj! [t k])

  (t-disj! [t k])

  (t-persistent! [t])

  (t-lookup [t k])

  (t-count [t]))


(defn- ensure-editable!
  [^Settings sett]
  (when-not (editing? sett)
    (throw (ex-info "Transient used after persistent! call" {}))))


(deftype TBTSet
  [meta cmp storage
   #?(:cljd ^:mutable address
      :clj ^:unsynchronized-mutable address
      :cljs ^:mutable address)
   #?(:cljd ^:mutable root
      :clj ^:unsynchronized-mutable root
      :cljs ^:mutable root)
   #?(:cljd ^:mutable cnt
      :clj ^long ^:unsynchronized-mutable cnt
      :cljs ^:mutable cnt)
   #?(:cljd ^:mutable version
      :clj ^long ^:unsynchronized-mutable version
      :cljs ^:mutable version) ^Settings settings]

  ITransientBTSet

  (t-conj!
    [this k]
    (ensure-editable! settings)
    (when (nil? k) (nil-element!))
    (let [nodes (node-conj root cmp k settings)]
      (cond (identical? UNCHANGED nodes) this
            (identical? EARLY-EXIT nodes) (do (set! address nil)
                                              (set! cnt (inc cnt))
                                              (set! version (inc version))
                                              this)
            (== 1 (arr/alength nodes)) (do (set! address nil)
                                           (set! root (arr/aget nodes 0))
                                           (set! cnt (inc cnt))
                                           (set! version (inc version))
                                           this)
            :else ; new root level
            (let [n1 (arr/aget nodes 0)
                  n2 (arr/aget nodes 1)
                  cs (two n1 n2)
                  ks (arr/anew 2)]
              (arr/aset ks 0 (node-lim-key n1))
              (arr/aset ks 1 (node-lim-key n2))
              (set! address nil)
              (set! root
                    (Branch. (inc (node-level n1)) 2 ks cs nil settings))
              (set! cnt (inc cnt))
              (set! version (inc version))
              this))))


  (t-disj!
    [this k]
    (ensure-editable! settings)
    (let [nodes (node-disj root cmp k true nil nil settings)]
      (cond (identical? UNCHANGED nodes) this
            (identical? EARLY-EXIT nodes) (do (set! address nil)
                                              (set! cnt (dec cnt))
                                              (set! version (inc version))
                                              this)
            :else (let [nr (arr/aget nodes 1)
                        nr (if (and (branch? nr) (== 1 (node-len nr)))
                             (node-child nr 0) ; root collapse
                             nr)]
                    (set! address nil)
                    (set! root nr)
                    (set! cnt (dec cnt))
                    (set! version (inc version))
                    this))))


  (t-persistent!
    [_]
    (ensure-editable! settings)
    (clear-lease! settings)
    ;; an untouched transient keeps its address (psset: _address survives
    ;; until the first edit), so persistent!-then-store-tree stays a
    ;; no-op
    (BTSet. meta cmp address storage root cnt version settings nil))


  (t-lookup
    [_ k]
    (ensure-editable! settings)
    (when (pos? cnt) (node-lookup root cmp k)))


  (t-count [_] (ensure-editable! settings) cnt)
  #?@(:cljd [cljd.core/ITransientCollection (-conj! [this k] (t-conj! this k))
             (-persistent! [this] (t-persistent! this)) cljd.core/ITransientSet
             (-disjoin! [this k] (t-disj! this k)) cljd.core/ICounted
             (-count [this] (t-count this)) cljd.core/ILookup
             (-lookup [this k] (t-lookup this k))
             (-lookup [this k not-found]
                      (if-some [v (t-lookup this k)]
                        v
                        not-found))
             (-contains-key? [this k] (some? (t-lookup this k)))]
      :clj [clojure.lang.ITransientSet (disjoin [this k] (t-disj! this k))
            (contains [this k] (clojure.core/boolean (some? (t-lookup this k))))
            (get [this k] (t-lookup this k))
            ;; ITransientCollection
            (conj [this k] (t-conj! this k))
            (persistent [this] (t-persistent! this)) clojure.lang.Counted
            (count [this] (t-count this)) clojure.lang.ILookup
            (valAt [this k] (t-lookup this k))
            (valAt [this k not-found]
                   (if-some [v (t-lookup this k)]
                     v
                     not-found))]
      :cljs [ITransientCollection (-conj! [this k] (t-conj! this k))
             (-persistent! [this] (t-persistent! this)) ITransientSet
             (-disjoin! [this k] (t-disj! this k)) ICounted
             (-count [this] (t-count this)) ILookup
             (-lookup [this k] (t-lookup this k))
             (-lookup [this k not-found]
                      (if-some [v (t-lookup this k)]
                        v
                        not-found))]))


(defn sorted-set*
  "Create a set from opts {:cmp :branching-factor :ref-type :meta}."
  [opts]
  (let [sett (make-settings opts)]
    (BTSet. (:meta opts)
            (or (:cmp opts) compare)
            nil
            nil
            (empty-root sett)
            0
            0
            sett
            nil)))


(defn sorted-set-by
  "Create an empty set with a custom comparator."
  [cmp]
  (sorted-set* {:cmp cmp}))


(defn count
  [^BTSet s]
  (.-cnt s))


(defn comparator
  [^BTSet s]
  (.-cmp s))


(defn root
  "The resident root node (faults from storage when the memo has cleared)."
  [^BTSet s]
  (resident-root s))


(defn settings
  "Settings of the set as a map."
  [^BTSet s]
  (let [^Settings sett (.-settings s)]
    {:branching-factor (.-branching-factor sett), :ref-type (.-ref-type sett)}))


(defn- early-exit-unreachable!
  []
  ;; Phase 3 trap, made loud: when the transient path lands, the EARLY-EXIT
  ;; branch must adjust cnt/version (psset's cons/disjoin do alterCount on
  ;; it — the in-place edit still changed the element count). Silently
  ;; returning s here would corrupt cnt. Unreachable while `edit` is nil.
  (throw (ex-info "EARLY-EXIT reached without an edit lease" {})))


(defn- nil-element!
  []
  (throw (ex-info "nil is not a legal element" {})))


(defn conj
  "Persistent conj of k; with cmp, overrides the set's comparator for this
   operation (psset signature). Returns the same set when k is present.
   nil is not a legal element (it is `lookup`'s absence signal): throws."
  ([^BTSet s k] (conj s k (.-cmp s)))
  ([^BTSet s k cmp]
   (when (nil? k) (nil-element!))
   (let [^Settings sett (.-settings s)
         nodes (node-conj (resident-root s) cmp k sett)]
     (cond (identical? UNCHANGED nodes) s
           (identical? EARLY-EXIT nodes) (early-exit-unreachable!)
           (== 1 (arr/alength nodes)) (BTSet. (.-meta s)
                                              (.-cmp s)
                                              nil
                                              (raw-storage s)
                                              (arr/aget nodes 0)
                                              (inc (.-cnt s))
                                              (inc (.-version s))
                                              sett
                                              nil)
           :else ; new root level
           (let [n1 (arr/aget nodes 0)
                 n2 (arr/aget nodes 1)
                 ks (two n1 n2) ; reuse 2-array shape
                 keys (arr/anew 2)
                 _ (arr/aset keys 0 (node-lim-key n1))
                 _ (arr/aset keys 1 (node-lim-key n2))
                 new-root (Branch. (inc (node-level n1)) 2 keys ks nil sett)]
             (BTSet. (.-meta s)
                     (.-cmp s)
                     nil
                     (raw-storage s)
                     new-root
                     (inc (.-cnt s))
                     (inc (.-version s))
                     sett
                     nil))))))


(defn disj
  "Persistent disj of k. Returns the same set when k is absent."
  ([^BTSet s k] (disj s k (.-cmp s)))
  ([^BTSet s k cmp]
   (let [^Settings sett (.-settings s)
         nodes (node-disj (resident-root s) cmp k true nil nil sett)]
     (cond (identical? UNCHANGED nodes) s
           (identical? EARLY-EXIT nodes) (early-exit-unreachable!)
           :else (let [nr (arr/aget nodes 1)
                       nr (if (and (branch? nr) (== 1 (node-len nr)))
                            (node-child nr 0) ; root collapse
                            nr)]
                   (BTSet. (.-meta s)
                           (.-cmp s)
                           nil
                           (raw-storage s)
                           nr
                           (dec (.-cnt s))
                           (inc (.-version s))
                           sett
                           nil))))))


(defn lookup
  "The stored element equal to k under the comparator, or nil.
   nil is not a legal element, so nil unambiguously means absent."
  ([^BTSet s k] (lookup s k (.-cmp s)))
  ([^BTSet s k cmp]
   (when (pos? (.-cnt s)) (node-lookup (resident-root s) cmp k))))


(defn contains?
  [^BTSet s k]
  (some? (lookup s k)))


;; ---------------------------------------------------------------------------
;; Iteration (Seq.java behavior via an explicit path stack; §2 O(log n + k)).
;; A stack is a list of [node idx] frames, leaf frame first.

(defn- descend-leftmost
  [stack node]
  (loop [stack stack
         node node]
    (if (branch? node)
      (recur (cons [node 0] stack) (node-child node 0))
      (cons [node 0] stack))))


(defn- descend-rightmost
  [stack node]
  (loop [stack stack
         node node]
    (let [idx (dec (node-len node))]
      (if (branch? node)
        (recur (cons [node idx] stack) (node-child node idx))
        (cons [node idx] stack)))))


(defn- advance
  "Stack positioned at the next element, or nil at the end of the tree."
  [stack]
  (loop [stack stack]
    (when-let [[node idx] (first stack)]
      (let [idx (inc idx)]
        (if (< idx (node-len node))
          (if (branch? node)
            (descend-leftmost (cons [node idx] (next stack))
                              (node-child node idx))
            (cons [node idx] (next stack)))
          (recur (next stack)))))))


(defn- retreat
  "Stack positioned at the previous element, or nil at the beginning."
  [stack]
  (loop [stack stack]
    (when-let [[node idx] (first stack)]
      (let [idx (dec idx)]
        (if (>= idx 0)
          (if (branch? node)
            (descend-rightmost (cons [node idx] (next stack))
                               (node-child node idx))
            (cons [node idx] (next stack)))
          (recur (next stack)))))))


(defn- descend-from
  "Stack positioned at the first element >= from, or nil when none."
  [root from cmp]
  (loop [stack nil
         node root]
    (let [ks (node-keys node)
          l (node-len node)
          idx (search-first ks l from cmp)]
      (if (== idx l)
        nil
        (if (branch? node)
          (recur (cons [node idx] stack) (node-child node idx))
          (cons [node idx] stack))))))


(defn- descend-from-r
  "Stack positioned at the last element <= from, or nil when none.
   Transcribes PersistentSortedSet.rslice's descent."
  [root from cmp]
  (loop [stack nil
         node root]
    (let [ks (node-keys node)
          l (node-len node)]
      (if (branch? node)
        (let [i (inc (search-last ks l from cmp))
              i (if (== i l) (dec i) i)]    ; last or beyond: clamp
          (recur (cons [node i] stack) (node-child node i)))
        (let [i (search-last ks l from cmp)]
          (if (== i -1)
            (retreat (cons [node 0] stack)) ; all keys here > from
            (cons [node i] stack)))))))


(defn- stack-key
  [stack]
  (let [[leaf idx] (first stack)] (arr/aget (node-keys leaf) idx)))


(defn- stack-seq
  [stack to to-incl? cmp]
  (lazy-seq (when stack
              (let [k (stack-key stack)]
                (when (or (nil? to)
                          (let [d (cmp k to)]
                            (or (neg? d) (and to-incl? (zero? d)))))
                  (cons k (stack-seq (advance stack) to to-incl? cmp)))))))


(defn- stack-rseq
  [stack lo lo-incl? cmp]
  (lazy-seq (when stack
              (let [k (stack-key stack)]
                (when (or (nil? lo)
                          (let [d (cmp k lo)]
                            (or (pos? d) (and lo-incl? (zero? d)))))
                  (cons k (stack-rseq (retreat stack) lo lo-incl? cmp)))))))


(defn- non-empty
  [s]
  (when (some? (first s)) s))


(defn- slice*
  "Generalized ascending range with per-bound inclusivity (nil = unbounded).
   The exclusivity knobs exist for cljd's ISorted bit-flag contract (§4);
   the public `slice` is inclusive-inclusive, matching psset."
  [^BTSet s from from-incl? to to-incl? cmp]
  (when (pos? (.-cnt s))
    (let [root (resident-root s)]
      (when-let [stack (if (nil? from)
                         (descend-leftmost nil root)
                         (when-let [st (descend-from root from cmp)]
                           (if (and (not from-incl?)
                                    (zero? (cmp (stack-key st) from)))
                             (advance st) ; distinct keys: skip at most one
                             st)))]
        (non-empty (stack-seq stack to to-incl? cmp))))))


(defn- rslice*
  "Generalized descending range: iterates from the hi bound down to lo."
  [^BTSet s hi hi-incl? lo lo-incl? cmp]
  (when (pos? (.-cnt s))
    (let [root (resident-root s)]
      (when-let [stack (if (nil? hi)
                         (descend-rightmost nil root)
                         (when-let [st (descend-from-r root hi cmp)]
                           (if (and (not hi-incl?)
                                    (zero? (cmp (stack-key st) hi)))
                             (retreat st)
                             st)))]
        (non-empty (stack-rseq stack lo lo-incl? cmp))))))


(defn slice
  "Lazy ascending seq of all X with from <= X <= to (nil bound = unbounded);
   nil when empty. Explicit-comparator arity per psset."
  ([^BTSet s from to] (slice s from to (.-cmp s)))
  ([^BTSet s from to cmp] (slice* s from true to true cmp)))


(defn rslice
  "Lazy descending seq of all X with to <= X <= from, iterating from `from`
   downward (nil bound = unbounded); nil when empty."
  ([^BTSet s from to] (rslice s from to (.-cmp s)))
  ([^BTSet s from to cmp] (rslice* s from true to true cmp)))


(defn seq
  "Lazy ascending seq of the whole set; nil when empty."
  [^BTSet s]
  (slice s nil nil))


(defn rseq
  "Lazy descending seq of the whole set; nil when empty."
  [^BTSet s]
  (rslice s nil nil))


;; ---------------------------------------------------------------------------
;; Bulk build (from-sorted-array / from-sequential;
;; persistent_sorted_set.clj:99-135 — the §5.2 fill policy)

(defn- split-ranges
  "[from to) chunk ranges over len items, chunks between min and max keys,
   aiming for avg — transcribes `split` (persistent_sorted_set.clj:63)."
  [len avg mx]
  (loop [acc []
         from 0]
    (let [l (- len from)]
      (cond (== 0 l) acc
            (>= l (* 2 avg)) (recur (clojure.core/conj acc [from (+ from avg)])
                                    (+ from avg))
            (<= l mx) (clojure.core/conj acc [from len])
            :else (let [half (+ from (quot l 2))]
                    (-> acc
                        (clojure.core/conj [from half])
                        (clojure.core/conj [half len])))))))


(defn- distinct-sorted!
  "Compact adjacent cmp-equal elements of sorted arr in place; new length."
  [arr cmp]
  (let [l (arr/alength arr)]
    (if (<= l 1)
      l
      (loop [w 1
             r 1]
        (if (== r l)
          w
          (let [x (arr/aget arr r)]
            (if (== 0 (cmp (arr/aget arr (dec w)) x))
              (recur w (inc r))
              (do (arr/aset arr w x) (recur (inc w) (inc r))))))))))


(defn from-sorted-array
  "Fast path: build a set from a sorted, distinct array (first len items)."
  ([cmp arr] (from-sorted-array cmp arr (arr/alength arr) nil))
  ([cmp arr len opts]
   (let [^Settings sett (make-settings opts)
         mx (bf* sett)
         avg (quot (+ (min-bf* sett) mx) 2)
         leaves (mapv (fn [[from to]]
                        (Leaf. (- to from) (arr/asub arr from to) sett))
                      (split-ranges len avg mx))]
     (loop [level 1
            nodes leaves]
       (condp == (clojure.core/count nodes)
         0 (BTSet. (:meta opts) cmp nil nil (empty-root sett) 0 0 sett nil)
         1 (BTSet. (:meta opts) cmp nil nil (nodes 0) len 0 sett nil)
         (recur (inc level)
                (mapv (fn [[from to]]
                        (let [n (- to from)
                              ks (arr/anew n)
                              cs (arr/anew n)]
                          (dotimes [i n]
                            (let [c (nodes (+ from i))]
                              (arr/aset ks i (node-lim-key c))
                              (arr/aset cs i c)))
                          (Branch. level n ks cs nil sett)))
                      (split-ranges (clojure.core/count nodes) avg mx))))))))


(defn from-sequential
  "Build a set from an arbitrary collection (sorts and dedupes first)."
  ([cmp coll] (from-sequential cmp coll nil))
  ([cmp coll opts]
   (let [arr (arr/from-coll coll)]
     (dotimes [i (arr/alength arr)]
       (when (nil? (arr/aget arr i)) (nil-element!)))
     (arr/asort arr cmp)
     (from-sorted-array cmp arr (distinct-sorted! arr cmp) opts))))


;; ---------------------------------------------------------------------------
;; Inspection helpers for the invariant test suite

(defn node-keys-vec
  "Vector of the node's valid keys [0 ... len)."
  [node]
  (let [ks (node-keys node)] (mapv #(arr/aget ks %) (range (node-len node)))))


;; ---------------------------------------------------------------------------
;; Durability entry points (§5.1, Phase 4)

(defn store-tree
  "Store the dirty subgraph, children before parents (Merkle by
   construction); returns the root address. No-op returning the existing
   address when the set is already stored (§4). An empty set stores as nil
   (the existing convention: nil root address, walk of nil => ())."
  [^BTSet s storage]
  (when (pos? (.-cnt s)) (-store-tree! s storage)))


(defn restore-tree
  "A lazy BTSet over `address`; nothing is fetched until traversed. `cnt`
   is the element count from the manifest (§5.1 — psset's fault-the-tree
   count sentinel is deliberately not ported). nil address => the empty
   set (cnt must be 0). The tree adopts the storage's Settings (`-settings`),
   which is how the manifest's branching factor reaches every restored
   node — never construct the storage with defaults for a tree published
   at another branching factor (§5.1)."
  [cmp address storage cnt]
  (let [sett (-settings storage)]
    (if (nil? address)
      (BTSet. nil cmp nil storage (empty-root sett) 0 0 sett nil)
      (BTSet. nil cmp address storage nil cnt 0 sett nil))))


(defn walk-addresses
  "Call on-address with every segment key reachable from address, the
   root's own address first; descend into a subtree only while on-address
   returns truthy (psset's prune protocol — lets a GC mark phase skip
   subtrees shared with already-marked versions). nil address => nothing."
  [storage address on-address]
  (letfn [(walk-node
            [n]
            (when (branch? n)
              (let [as (node-addresses-arr n)
                    l (node-len n)
                    deep? (> (node-level n) 1)]
                (dotimes [i l]
                  (let [a (arr/aget as i)]
                    (when (and (on-address a) deep?)
                      (walk-node (-restore storage a))))))))]
    (when (some? address)
      (when (on-address address) (walk-node (-restore storage address))))))


(defn clear-test-refs!
  "Test seam (§5.3): clear every :test-ref-type reference reachable from
   the set's resident nodes (root slot included), so the eviction suite
   can force 'collection' deterministically and assert the refault path."
  [^BTSet s]
  (letfn [(walk-node
            [n]
            (when (branch? n)
              (when-some [cs (node-children-arr n)]
                (dotimes [i (node-len n)]
                  (let [slot (arr/aget cs i)]
                    (when-some [c (read-ref slot)] (walk-node c))
                    (when (instance? TestRef slot) (test-ref-clear! slot)))))))]
    (let [slot (raw-root s)]
      (when-some [r (read-ref slot)] (walk-node r))
      (when (instance? TestRef slot) (test-ref-clear! slot)))))


;; ---------------------------------------------------------------------------
;; Accessors for storage adapters (dao.data.btree.storage) and tests

(defn default-ref-type*
  "The per-host default ref-type (§5.3): :soft on the JVM, :strong on
   cljs/cljd where :soft would silently degrade to :weak."
  []
  default-ref-type)


(defn set-address
  "The set's own root address; nil while the root is dirty (§4)."
  [^BTSet s]
  (raw-address s))


(defn set-storage
  "The set's attached IStorage, or nil for a purely in-memory tree."
  [^BTSet s]
  (raw-storage s))
