(ns dao.datom
  "Reserved metadata-entity ids for the d5 m slot (validity + provenance).

   In a (e a v t m) datom, m is a metadata-entity reference. Low reserved ids
   mirror Datomic's `added` boolean and extend it; ids at `first-user-id` and
   above reference reified metadata entities that carry :db/op and provenance
   as their own datoms.

   This namespace is the single source of truth for the reserved ids and for
   the boundary between reserved and user space; nothing should compare m or e
   against a bare literal. See docs/agents/datom-spec.md.")


(def reserved
  "Reserved metadata-entity ids (ident -> id) for the m slot.
   0/1 mirror Datomic's `added` boolean; 2 extends it."
  {:db/retract 0, ; added=false
   :db/assert 1,  ; added=true (emit default)
   :db/derived 2})  ; computed/derived; excluded from content hash


(def default-op
  "The m written for emitted datoms when no metadata is supplied: assertion."
  (:db/assert reserved))


(def first-user-id
  "Lowest entity id available to user entities; 0 through 15 are reserved.

   The block is 16 wide rather than 1024 because only the m-slot markers need
   globally fixed numbers. Built-in attributes and type markers live in the a
   and v slots as namespaced keywords, already globally meaningful without an
   id, and a schema entity in e position takes an ordinary stream-local id
   named across streams by its :db/ident. Reserving more would put global
   coordinates in a slot the spec declares a stream-local gauge, and would put
   the floor above what an int8-wide e can represent at all.

   See docs/agents/datom-spec.md, Reserved Entities."
  16)


(defn local-datom?
  "True for the runtime-enforced portion of a persisted local d5 datom:
   `[e a v t m]`, with non-negative integer stream-local coordinates `e` and
   `t`, integer `m`, and a namespaced keyword attribute. Negative tempids are
   compilation values and must be resolved before commitment. Canonical value
   encoding is a separate persistence concern and is not decided here."
  [x]
  (and (vector? x)
       (= 5 (count x))
       (integer? (nth x 0))
       (not (neg? (nth x 0)))
       (keyword? (nth x 1))
       (some? (namespace (nth x 1)))
       (integer? (nth x 3))
       (not (neg? (nth x 3)))
       (integer? (nth x 4))))


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
