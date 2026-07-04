(ns dao.space.query
  "The Query boundary of the tuple space: `match` (Linda-style positional
  templates) and `q` (Datalog: joins, negation-free conjunction, one merged
  index) over a **source**. Pure and stateless — it owns no durable state,
  performs no writes, and enforces no schema; those are the write-side
  Transactor's job (each `dao.stream` writer is its own), never the query
  library's. See docs/design/dao.space.md, \"The Query Library\" and
  \"Source Polymorphism\".

  A source is, interchangeably (and freely mixed in a collection):
    - a single `dao.jing` IKVStore handle
    - a collection of `dao.jing` handles (a federated query — ADR 0001's
      monoid-homomorphism proof is why folding N stores and merging equals
      one store holding everything)
    - a raw vector of datoms, `[[e a v t m] ...]`
    - a raw vector of entity maps, `[{:attr val ...} ...]`

  Reading a `dao.jing` handle uses the simplest possible convention ahead
  of the target B-Tree-segment architecture (dao.jing.md, Index
  Realization): each handle's datoms live wholesale at one mutable root,
  `default-datoms-key` (`:root/datoms`), as `{:datoms [...]}`. This is the
  documented rebuild-per-query baseline (\"kept only as the conceptual
  baseline\", dao.space.md) — dao.space.query never writes there; a stream
  owner does, exactly as it writes any other root.

  Deferred, not built here: current-state resolution (masking retracted
  datoms and superseding cardinality-one values) is named in ADR 0001 as a
  query-time concern (\"resolved at query time, not destructively at index
  time\") but is not implemented yet. `match`/`q` today answer over the
  historical datom log exactly as given, retractions and all — a caller
  wanting current state must filter by `dao.datom/asserted?` itself."
  (:require [dao.datom :as datom]
            [dao.jing :as kv]
            #?(:cljs [me.tonsky.persistent-sorted-set :as psset])))


(def default-datoms-key
  "The default root key a dao.jing handle's datoms are read from."
  :root/datoms)


;; =============================================================================
;; Value comparison (heterogeneous datom values)
;; =============================================================================

(defn- type-rank
  [x]
  (cond (nil? x) 0
        (boolean? x) 1
        (number? x) 2
        (string? x) 3
        (keyword? x) 4
        (symbol? x) 5
        :else 6))


(defn compare-vals
  "Compare two datom values across heterogeneous types using type-rank."
  [a b]
  (let [ra (type-rank a)
        rb (type-rank b)]
    (if (= ra rb) (compare a b) (compare ra rb))))


;; =============================================================================
;; Source -> datoms
;; =============================================================================

(defn read-datoms
  "Read the datoms held at a dao.jing handle's datoms-key (default
  `default-datoms-key`), or [] if never seeded."
  ([store] (read-datoms store default-datoms-key))
  ([store datoms-key] (:datoms (kv/get store datoms-key {:datoms []}))))


(defn- entity-map->datoms
  "Normalize one entity map into datoms: one datom per k/v pair (no
  cardinality expansion — a collection value is stored verbatim as one
  datom's v, matching dao.db's map-form tx-data convention), `t` 0, `m`
  dao.datom/default-op. A map with no :db/id gets a fresh negative tempid."
  [next-tmp m]
  (let [e (if (contains? m :db/id) (:db/id m) (next-tmp))]
    (into []
          (keep (fn [[a v]] (when (not= a :db/id) [e a v 0 datom/default-op])))
          m)))


(defn- entity-maps->datoms
  [maps]
  (let [counter (atom 0)
        next-tmp #(- (swap! counter dec))]
    (into [] (mapcat #(entity-map->datoms next-tmp %)) maps)))


(defn- dao-jing-handle?
  [x]
  (satisfies? kv/IKVStore x))


(declare source->datoms)


(defn- coll-source->datoms
  [source]
  (cond (every? dao-jing-handle? source) (mapcat read-datoms source)
        (every? vector? source) source
        (every? map? source) (entity-maps->datoms source)
        :else (mapcat source->datoms source)))


(defn source->datoms
  "Fold any source shape (see ns docstring) into a flat seq of datoms."
  [source]
  (cond (dao-jing-handle? source) (read-datoms source)
        (coll? source) (coll-source->datoms source)
        :else (throw (ex-info "unrecognized query source" {:source source}))))


;; =============================================================================
;; Index
;; =============================================================================

(defn- datom-e
  [d]
  (nth d 0))


(defn- datom-a
  [d]
  (nth d 1))


(defn- datom-v
  [d]
  (nth d 2))


(defn- datom-t
  [d]
  (nth d 3))


(defn- cmp-field
  "Nil-first, heterogeneous-safe field comparison. Entity ids are not
  guaranteed to be integers here the way dao.db's tempid pipeline
  guarantees — a raw entity map's :db/id is caller-chosen and can be any
  type — so every slot, not just v, needs compare-vals."
  [a b]
  (cond (nil? a) (if (nil? b) 0 -1)
        (nil? b) 1
        :else (compare-vals a b)))


(defn- eavt-cmp
  [d1 d2]
  (let [c (cmp-field (datom-e d1) (datom-e d2))]
    (if (zero? c)
      (let [c (cmp-field (datom-a d1) (datom-a d2))]
        (if (zero? c) (cmp-field (datom-v d1) (datom-v d2)) c))
      c)))


(defn- aevt-cmp
  [d1 d2]
  (let [c (cmp-field (datom-a d1) (datom-a d2))]
    (if (zero? c)
      (let [c (cmp-field (datom-e d1) (datom-e d2))]
        (if (zero? c) (cmp-field (datom-v d1) (datom-v d2)) c))
      c)))


(defn- avet-cmp
  [d1 d2]
  (let [c (cmp-field (datom-a d1) (datom-a d2))]
    (if (zero? c)
      (let [c (cmp-field (datom-v d1) (datom-v d2))]
        (if (zero? c) (cmp-field (datom-e d1) (datom-e d2)) c))
      c)))


(defn- sorted-index-by
  [cmp]
  #?(:clj ((requiring-resolve 'me.tonsky.persistent-sorted-set/sorted-set-by)
           cmp)
     :cljs (psset/sorted-set-by cmp)
     :cljd (sorted-set-by cmp)))


(defn- subseq-from
  [sorted-set cmp sentinel]
  (drop-while #(neg? (cmp % sentinel)) (seq sorted-set)))


(defn index-datoms
  "Build {:eavt ... :aevt ... :avet ...} sorted indexes from a seq of
  datoms."
  [datoms]
  {:eavt (into (sorted-index-by eavt-cmp) datoms),
   :aevt (into (sorted-index-by aevt-cmp) datoms),
   :avet (into (sorted-index-by avet-cmp) datoms)})


(defn fold
  "Fold a source into an index, optionally bounded to datoms with t <= as-of."
  ([source] (fold source nil))
  ([source as-of]
   (let [datoms (source->datoms source)
         datoms (if as-of
                  (filter #(<= (compare-vals (datom-t %) as-of) 0) datoms)
                  datoms)]
     (index-datoms datoms))))


;; =============================================================================
;; match: Linda-style positional template
;; =============================================================================

;; Shared with q below: an unbound Datalog variable resolves to FREE, and a
;; raw match template's open slot is `_`/nil — select-by-index is shared by
;; both, so wildcard? must recognize every "no constraint" spelling.
(def ^:private FREE ::free)


(defn- wildcard?
  [x]
  (or (= x '_) (nil? x) (= x FREE)))


(defn- select-by-index
  [idx e a v]
  (cond (not (wildcard? e))
        (take-while #(= e (datom-e %))
                    (subseq-from (:eavt idx) eavt-cmp [e nil nil nil nil]))
        (and (not (wildcard? a)) (not (wildcard? v)))
        (take-while #(and (= a (datom-a %)) (= v (datom-v %)))
                    (subseq-from (:avet idx) avet-cmp [nil a v nil nil]))
        (not (wildcard? a))
        (take-while #(= a (datom-a %))
                    (subseq-from (:aevt idx) aevt-cmp [nil a nil nil nil]))
        :else (seq (:eavt idx))))


(defn match
  "Positional template match, Linda-style: [e a v] or [e a v t m], `_` or
  nil as wildcard elsewhere. Returns the matching datoms (not bindings).
  Options: {:as-of t}."
  ([source pattern] (match source pattern nil))
  ([source pattern {:keys [as-of]}]
   (let [[te ta tv tt tm] (into (vec pattern) (repeat (- 5 (count pattern)) '_))
         idx (fold source as-of)
         candidates (select-by-index idx te ta tv)]
     (vec (filter (fn [d]
                    (and (or (wildcard? te) (= te (nth d 0)))
                         (or (wildcard? ta) (= ta (nth d 1)))
                         (or (wildcard? tv) (= tv (nth d 2)))
                         (or (wildcard? tt) (= tt (nth d 3)))
                         (or (wildcard? tm) (= tm (nth d 4)))))
                  candidates)))))


;; =============================================================================
;; q: Datalog (:find / :where, one merged index)
;; =============================================================================

(defn- resolve-binding
  [binding sym]
  (if (symbol? sym) (get binding sym FREE) sym))


(defn- pad-to-5
  [clause]
  (into (vec clause) (repeat (- 5 (count clause)) FREE)))


(defn- unify
  [binding sym val]
  (cond (or (= sym FREE) (= sym '_)) binding
        (not (symbol? sym)) (when (= sym val) binding)
        (contains? binding sym) (when (= (get binding sym) val) binding)
        :else (assoc binding sym val)))


(defn- and-then
  [x f]
  (when (some? x) (f x)))


(defn- select-datoms
  [idx e a v]
  (select-by-index idx e a v))


(defn- eval-clause
  [idx clause binding]
  (let [[ce ca cv ct cm] (pad-to-5 clause)
        e-val (resolve-binding binding ce)
        a-val (resolve-binding binding ca)
        v-val (resolve-binding binding cv)
        datoms (select-datoms idx e-val a-val v-val)]
    (keep (fn [d]
            (-> binding
                (unify ce (nth d 0))
                (and-then #(unify % ca (nth d 1)))
                (and-then #(unify % cv (nth d 2)))
                (and-then #(unify % ct (nth d 3)))
                (and-then #(unify % cm (nth d 4)))))
          datoms)))


(defn- estimate-clause-cost
  [clause binding]
  (let [[ce ca cv] (pad-to-5 clause)
        e-val (resolve-binding binding ce)
        a-val (resolve-binding binding ca)
        v-val (resolve-binding binding cv)]
    (cond (and (not= e-val FREE) (not= a-val FREE) (not= v-val FREE)) 1
          (not= e-val FREE) 2
          (and (not= a-val FREE) (not= v-val FREE)) 4
          (not= a-val FREE) 16
          :else 1024)))


(defn- plan-where
  [clauses init-bindings]
  (if (or (<= (count clauses) 1) (empty? init-bindings))
    clauses
    (let [binding (first init-bindings)]
      (mapv second
            (sort-by (fn [[idx clause]] [(estimate-clause-cost clause binding) idx])
                     (map-indexed vector clauses))))))


(defn- eval-where
  [idx clauses init-bindings]
  (reduce (fn [bindings clause] (mapcat #(eval-clause idx clause %) bindings))
          init-bindings
          (plan-where clauses init-bindings)))


(defn- normalize-query
  [query]
  (if (map? query)
    query
    (let [v (vec query)]
      (loop [i 0
             result {}
             cur-key nil
             cur-vals []]
        (if (>= i (count v))
          (if cur-key (assoc result cur-key cur-vals) result)
          (let [x (nth v i)]
            (if (#{:find :where} x)
              (recur (inc i)
                     (if cur-key (assoc result cur-key cur-vals) result)
                     x
                     [])
              (recur (inc i) result cur-key (conj cur-vals x)))))))))


(defn q
  "Datalog: [:find ?a ?b :where [pattern] ...] over source. Options:
  {:as-of t}. Returns a set of result tuples."
  ([query source] (q query source nil))
  ([query source {:keys [as-of]}]
   (let [{:keys [find where]} (normalize-query query)
         idx (fold source as-of)
         result (eval-where idx where [{}])]
     (into #{} (map (fn [b] (mapv #(get b %) find))) result))))
