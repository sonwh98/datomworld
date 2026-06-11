(ns dao.datom
  "Reserved metadata-entity ids for the d5 m slot (validity + provenance).

   In a (e a v t m) datom, m is a metadata-entity reference. Low reserved ids
   mirror Datomic's `added` boolean and extend it; high ids (>= 1025) reference
   reified metadata entities that carry :db/op and provenance as their own datoms.

   This namespace is the single source of truth for the reserved ids; nothing
   should compare m against a bare literal. See docs/agents/datom-spec.md.")


(def reserved
  "Reserved metadata-entity ids (ident -> id) for the m slot.
   0/1 mirror Datomic's `added` boolean; 2 extends it."
  {:db/retract 0, ; added=false
   :db/assert 1,  ; added=true (emit default)
   :db/derived 2})  ; computed/derived; excluded from content hash


(def default-op
  "The m written for emitted datoms when no metadata is supplied: assertion."
  (:db/assert reserved))


(defn op
  "The m (metadata/op) slot of a datom (a [e a v t m] vector or a Datom record)."
  [datom]
  (if (map? datom) (:m datom) (nth datom 4)))


(defn asserted?
  [datom]
  (= (op datom) (:db/assert reserved)))


(defn retracted?
  [datom]
  (= (op datom) (:db/retract reserved)))


(defn derived?
  [datom]
  (= (op datom) (:db/derived reserved)))


(def markers
  "The reserved op/derived marker ids. These are validity/derivation flags, not
   references to real metadata entities."
  (set (vals reserved)))


(defn metadata-ref?
  "True when m references a real metadata entity (not a bare validity/derived
   marker). Datoms satisfying this belong in the MEAVT index."
  [datom]
  (not (contains? markers (op datom))))
