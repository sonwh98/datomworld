(ns yin.content
  "Content addressing for AST datoms via Merkle hashing.
   Computes gauge-invariant content hashes over [a v] pairs,
   replacing entity-ref values with content hashes of referenced entities."
  (:require [dao.db.primitives :as p]
            [yin.vm :as vm]))


;; Derive ref attrs from the canonical schema
(def ref-attrs
  "Attributes whose values are entity references."
  (->> vm/schema
       (keep (fn [[k v]] (when (= :db.type/ref (:db/valueType v)) k)))
       set))


(def vector-ref-attrs
  "Ref attrs whose values are vectors of refs (cardinality-many)."
  (->> vm/schema
       (keep (fn [[k v]] (when (= :db.cardinality/many (:db/cardinality v)) k)))
       set))


(defn resolve-value
  "Resolve a datom value, replacing entity refs with content hashes from cache.
   For cardinality-many attrs, v may be a vector (raw) or scalar (materialized)."
  [a v hash-cache]
  (cond (contains? vector-ref-attrs a)
          (if (vector? v) (mapv #(get hash-cache % %) v) (get hash-cache v v))
        (contains? ref-attrs a) (get hash-cache v v)
        :else v))


(defn content-hash-for-entity
  "Compute content hash for a single entity given its datoms and a hash cache.
   Normalizes cardinality-many attrs: repeated scalar datoms are accumulated
   into a sorted vector, matching the canonical vector representation.
   Returns \"sha256:hex...\"."
  [entity-datoms hash-cache]
  (let [non-derived (->> entity-datoms
                         (filter (fn [[_e _a _v _t m]] (= 0 m))))
        av-map (reduce (fn [m [_e a v _t _m]]
                         (if (contains? vector-ref-attrs a)
                           ;; Cardinality-many: accumulate into vector,
                           ;; handling both raw vector values and
                           ;; materialized scalar datoms
                           (let [vals (if (vector? v) v [v])]
                             (update m a (fnil into []) vals))
                           (assoc m a v)))
                 {}
                 non-derived)
        resolved (into (sorted-map)
                       (map (fn [[a v]] [a (resolve-value a v hash-cache)]))
                       av-map)]
    (str "sha256:" (p/sha256 (pr-str resolved)))))


(defn- entity-refs
  "Extract the set of entity IDs referenced by this entity's datoms.
   Handles both vector and scalar representations of cardinality-many refs."
  [entity-datoms by-entity]
  (->> entity-datoms
       (filter (fn [[_e _a _v _t m]] (= 0 m)))
       (mapcat (fn [[_e a v _t _m]]
                 (cond (contains? vector-ref-attrs a) (if (vector? v) v [v])
                       (contains? ref-attrs a) [v]
                       :else nil)))
       (filter #(contains? by-entity %))
       set))


(defn compute-content-hashes
  "Compute content hashes for all entities in a datom stream.
   Uses dependency-based topo sort (leaves first, root last).
   Returns {eid -> \"sha256:hex...\"}."
  [datoms]
  (let [by-entity (group-by first datoms)
        deps (reduce-kv (fn [m eid ed] (assoc m eid (entity-refs ed by-entity)))
                        {}
                        by-entity)
        sorted-eids
          (loop [result []
                 remaining deps
                 resolved #{}]
            (if (empty? remaining)
              result
              (let [ready (into []
                                (keep (fn [[eid eid-deps]]
                                        (when (every? resolved eid-deps) eid)))
                                remaining)]
                (when (empty? ready)
                  (throw (ex-info "Cyclic dependency in AST" {})))
                (recur (into result ready)
                       (apply dissoc remaining ready)
                       (into resolved ready)))))]
    (reduce (fn [hash-cache eid]
              (assoc hash-cache
                eid (content-hash-for-entity (get by-entity eid) hash-cache)))
      {}
      sorted-eids)))


(defn annotate-datoms
  "Append content hash datoms [e :yin/content-hash hash t 1] to the stream.
   m=1 marks these as derived (per CLAUDE.md: entity 1 = :db/derived)."
  ([datoms] (annotate-datoms datoms 0))
  ([datoms t]
   (let [hashes (compute-content-hashes datoms)
         hash-datoms (mapv (fn [[eid hash]] [eid :yin/content-hash hash t 1])
                       hashes)]
     (into datoms hash-datoms))))
