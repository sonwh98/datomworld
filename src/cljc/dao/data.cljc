(ns dao.data
  "Bounded, plain-data descriptions of arbitrary values
   (docs/design/dao.data.md).

   `tag` classifies a value without entering it; `summarize` turns a value
   into a `{:depth :items :chars}`-bounded tree of tagged plain data — safe
   to append, print, or ship. No functions, stream handles, or host objects
   survive into the result. This is the parent namespace's own file, sitting
   alongside the `dao.data.*` sub-namespaces; neither depends on the other."
  (:require [dao.stream.v2 :as stream]
            [dao.stream.v2.transit :as transit]))


(defn tag
  "Classify x without entering it.

   The descriptor check precedes `map?`/`sequential?` because a handle may
   be a record. `stream/descriptor?` alone is enough: every handle
   implements it, regardless of declared surfaces."
  [x]
  (cond (nil? x) :nil
        (boolean? x) :boolean
        (number? x) :number
        (string? x) :string
        (keyword? x) :keyword
        (symbol? x) :symbol
        (fn? x) :fn
        (stream/descriptor? x) :stream
        (vector? x) :vector
        (set? x) :set
        (map? x) :map
        (sequential? x) :sequence
        :else :opaque))


(declare summarize)


(defn- char-node
  "The one leaf-bound rule, shared by strings and printed keywords/symbols.
   Over `chars`, the cut printed form stands in for the value — a partial
   name isn't a valid keyword or symbol to begin with. A stream's identity
   gets no bespoke rule; it reuses this one by being summarized like any
   other value."
  [type x printed chars]
  (let [truncated? (> (count printed) chars)]
    {:dao.data/type type
     :dao.data/value (if truncated? (subs printed 0 chars) x)
     :dao.data/truncated? truncated?}))


(defn- number-node
  "A number outside the live wire codec's portable domain — non-finite, a
   ratio, a BigInt/BigDecimal, or outside ±9007199254740991 — is stringified
   rather than preserved. `portable-value?` is the actual check the v2 wire
   boundary applies, so this can't drift from the real contract."
  [x]
  (if (transit/portable-value? x)
    {:dao.data/type :number :dao.data/value x :dao.data/truncated? false}
    {:dao.data/type :number :dao.data/value (str x) :dao.data/truncated? true}))


(defn- stream-node
  "The identity projection of the descriptor outcome — not the whole
   `:dao.stream/descriptor` envelope — summarized with the same bounds,
   since it isn't a child of a container being walked. A conforming
   descriptor never fails on a value satisfying `descriptor?`; the
   `:opaque` fallback covers only a non-conforming implementation."
  [x bounds]
  (let [result (stream/descriptor x)]
    (if (= :dao.stream/ok (:dao.stream/outcome result))
      {:dao.data/type :stream
       :dao.data/identity (summarize (:dao.stream/identity result) bounds)}
      {:dao.data/type :opaque})))


(defn- container-node
  "The shared `:depth`/`:items` rule for `:vector`, `:set`, `:sequence`, and
   `:map` (entries, not items). `:count` is decided by `counted?` alone,
   before any probing — never inferred from what a probe happened to find.
   A non-`counted?` sequence is asked for at most `:items` + 1 elements:
   enough to fill `:items` and check that a next one exists, no more. At
   depth 0 a non-`counted?` sequence isn't probed at all — nothing in the
   result would depend on the answer."
  [type x bounds]
  (let [{:keys [depth items]} bounds
        counted (counted? x)]
    (if (zero? depth)
      (cond-> {:dao.data/type type :dao.data/truncated? true}
        counted (assoc :dao.data/count (count x)))
      (let [child-bounds (assoc bounds :depth (dec depth))
            probe (take (if counted items (inc items)) x)
            truncated? (if counted
                         (< items (count x))
                         (< items (count probe)))
            shown (take items probe)
            pairs (if (= :map type)
                    (mapv (fn [[k v]]
                            [(summarize k child-bounds)
                             (summarize v child-bounds)])
                          shown)
                    (mapv #(summarize % child-bounds) shown))]
        (cond-> {:dao.data/type type :dao.data/truncated? truncated?}
          counted (assoc :dao.data/count (count x))
          true (assoc (if (= :map type) :dao.data/entries :dao.data/items)
                      pairs))))))


(defn summarize
  "Bounded plain-data description of x under {:depth n :items n :chars n}.

   Every node is a map tagged `:dao.data/type` — scalars too, so a result is
   never ambiguous with an ordinary value. `:items`/`:entries` is absent at
   depth 0 and is always a plain vector, never the input's own collection
   type. Leaf truncation (string, number, keyword, symbol) is governed by
   `:chars` and unaffected by `:depth`."
  [x bounds]
  (case (tag x)
    :nil {:dao.data/type :nil}
    :boolean {:dao.data/type :boolean :dao.data/value x}
    :number (number-node x)
    :string (char-node :string x x (:chars bounds))
    :keyword (char-node :keyword x (str x) (:chars bounds))
    :symbol (char-node :symbol x (str x) (:chars bounds))
    :fn {:dao.data/type :fn}
    :opaque {:dao.data/type :opaque}
    :stream (stream-node x bounds)
    (container-node (tag x) x bounds)))
