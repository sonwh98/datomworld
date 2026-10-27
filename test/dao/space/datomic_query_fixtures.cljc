(ns dao.space.datomic-query-fixtures
  "The frozen datomic-q-v1 conformance corpus (phase 0 of
   docs/design/dao.space.query.datomic-compat.md), loaded on the JVM, Node,
   and Dart.

   The corpus lives in test/resources/dao/space/datomic-q-v1.json; its
   fixture DSL, evidence classes and promotion rules are in
   datomic-q-v1.README.md beside it. Every host reads that one file by its
   repository-relative path (the test lanes run from the repository root).

   The JSON is parsed to string-keyed data, then `decode` rebuilds typed
   values from the explicit tagged representation. Plain JSON number
   decoding never carries a value's identity: bare numbers are safe-range
   integers only, and every other numeric kind is a tagged object.

   Pure data handling: nothing here evaluates a query, writes a file, or
   mints an entity id."
  (:require [dao.jing.cbor :as cbor]
            [dao.jing.cbor-fixtures :as fx]
            [dao.space.query :as query])
  #?(:cljd (:import ["dart:core" BigInt])))


(def path
  "The corpus, relative to the repository root."
  "test/resources/dao/space/datomic-q-v1.json")


(def readme-path "test/resources/dao/space/datomic-q-v1.README.md")
(def errata-path "test/resources/dao/space/datomic-q-v1.errata.md")
(def observations-path "test/resources/dao/space/datomic-q-v1.oracle-observations.edn")
(def provenance-path "test/resources/dao/space/datomic-q-v1.provenance.edn")


(defn read-text
  "The corpus file's text."
  []
  (fx/read-path path))


(def ^:private corpus* (delay (fx/parse (read-text))))


(defn corpus
  "The whole corpus document, string-keyed, exactly as the JSON spells it."
  []
  @corpus*)


(defn cases
  "Every case, in file order."
  []
  (get (corpus) "cases"))


(defn points
  "The supported-domain inventory: the contract points the cases pin."
  []
  (get (corpus) "points"))


(defn fixtures
  "The logical fixtures, keyed by fixture id."
  []
  (get (corpus) "fixtures"))


(defn case-by-id
  [id]
  (first (filter #(= id (get % "id")) (cases))))


;; ---------------------------------------------------------------------------
;; Typed-value reconstruction
;; ---------------------------------------------------------------------------

(defn- big
  [s]
  #?(:cljd (BigInt.parse s)
     :clj (bigint s)
     :cljs (js/BigInt s)))


(defn- split-name
  "[ns name] for \"ns/name\" (split at the first slash), [nil name] else."
  [s]
  (let [i (.indexOf ^String s "/")]
    (if (pos? i)
      [(subs s 0 i) (subs s (inc i))]
      [nil s])))


(defn- kw
  [s]
  (let [[n m] (split-name s)]
    (if n (keyword n m) (keyword m))))


(defn- sym
  [s]
  (let [[n m] (split-name s)]
    (if n (symbol n m) (symbol m))))


(def tags
  "The tag keys a decoded object may carry. A JSON object has exactly one."
  #{"kw" "sym" "list" "set" "map" "bigint" "decimal" "float64-bits" "ratio"})


(defn decode
  "The typed value a corpus datum denotes.

   null, booleans, strings and safe-range integers are themselves; a JSON
   array is a vector; a fractional bare number is refused (it would lose its
   kind). An object has exactly one tag key:
     {\"kw\" \"ns/name\"}                         keyword
     {\"sym\" \"?e\"}                             symbol
     {\"list\" [..]}                             list
     {\"set\" [..]}                              set
     {\"map\" [[k v] ..]}                        map
     {\"bigint\" \"123\"}                         big integer
     {\"decimal\" {\"exponent\" -2 \"mantissa\" \"100\"}} decimal, exact scale
     {\"float64-bits\" \"3ff0000000000000\"}      IEEE-754 double by bits
     {\"ratio\" [\"1\" \"3\"]}                      rational"
  [x]
  (cond
    (nil? x) nil
    (or (true? x) (false? x) (string? x)) x
    (number? x)
    (if (and (integer? x) (<= -9007199254740991 x 9007199254740991))
      x
      (throw (ex-info "bare JSON number must be a safe-range integer"
                      {:value x})))
    (vector? x) (mapv decode x)
    (map? x)
    (do
      (when-not (= 1 (count x))
        (throw (ex-info "tagged object must have exactly one tag key"
                        {:keys (vec (keys x))})))
      (let [[tag v] (first x)]
        (case tag
          "kw" (kw v)
          "sym" (sym v)
          "list" (apply list (map decode v))
          "set" (set (map decode v))
          "map" (into {} (map (fn [[k m]] [(decode k) (decode m)])) v)
          "bigint" (big v)
          "decimal" (cbor/decimal (get v "exponent") (get v "mantissa"))
          "float64-bits" (cbor/float64-from-bits v)
          "ratio" (cbor/ratio (first v) (second v))
          (throw (ex-info "unknown corpus tag" {:tag tag})))))
    :else (throw (ex-info "undecodable corpus datum" {:value x}))))


(defn fixture-facts
  "The typed d3 fact rows of a logical fixture (data only)."
  [fixture-id]
  (mapv decode (get-in (fixtures) [fixture-id "facts"])))


(defn fixture-source
  "The DaoSpace loader: the explicit query value of a logical fixture, a
   fresh immutable value on each call. Fixture \"view\" \"facts\" (default) is
   a current-fact relation of d3 rows; \"current-d5\" is the explicit
   `current` interpreter over d5 rows [e a v t m] (m 1 asserts, 0 retracts)."
  [fixture-id]
  (let [facts (fixture-facts fixture-id)]
    (case (get-in (fixtures) [fixture-id "view"] "facts")
      "facts" (query/fact-relation facts)
      "current-d5" (query/current (query/relation facts))
      (throw (ex-info "unknown fixture view" {:fixture fixture-id})))))


(defn case-inputs
  "The typed query inputs of a case, in :in order. {\"db\" id} is the
   DaoSpace source of that fixture; {\"value\" v} is ordinary data."
  [c]
  (mapv (fn [in]
          (cond
            (contains? in "db") (fixture-source (get in "db"))
            (contains? in "value") (decode (get in "value"))
            :else (throw (ex-info "case input must be db or value"
                                  {:case (get c "id") :input in}))))
        (get c "inputs")))


(defn case-query
  "The typed query form of a case."
  [c]
  (decode (get c "query")))


(defn case-options
  "The typed option map of a case (keyword keys), or nil."
  [c]
  (when-let [o (get c "options")]
    (decode o)))


;; ---------------------------------------------------------------------------
;; Entity-id annotations (oracle runner)
;; ---------------------------------------------------------------------------
;; The corpus uses logical entity ids as literals, but a real Datomic allocates
;; its own ids. A case that needs literals rewritten for an oracle run says so
;; with "entity-ids": a list of {"source" <fixture id>, "path" [...]} naming
;; ENUMERATED positions. The first path element is "query" or an input index;
;; the rest index into the decoded form. Only those positions are rewritten, so
;; an ordinary number that happens to equal a logical id (an age, an aggregate
;; parameter, a data value) is never touched.

(defn case-entity-ids
  "The enumerated entity-id positions of a case: [{:source .. :path [..]}]."
  [c]
  (mapv (fn [p] {:source (get p "source"), :path (vec (get p "path"))})
        (get c "entity-ids")))


(defn- update-at
  [coll i f]
  (cond
    (vector? coll) (assoc coll i (f (nth coll i)))
    (seq? coll) (apply list (assoc (vec coll) i (f (nth (vec coll) i))))
    :else (throw (ex-info "entity-id path crosses a non-sequence"
                          {:coll coll, :index i}))))


(defn- update-path
  [form path f]
  (if (empty? path)
    (f form)
    (update-at form (first path) (fn [x] (update-path x (rest path) f)))))


(defn substitute-entity-ids
  "Rewrite ONLY the annotated positions of [query inputs], each logical id
   mapped through its source's logical->real map. `inputs` is the vector of
   decoded inputs (db inputs are passed through untouched). A position that is
   not an integer, or whose id is unmapped, throws: nothing is skipped."
  [c query inputs logical->real]
  (reduce (fn [[q ins] {:keys [source path]}]
            (let [real (fn [x]
                         (when-not (integer? x)
                           (throw (ex-info "entity-id position is not an integer"
                                           {:case (get c "id"), :path path, :value x})))
                         (when-not (contains? (get logical->real source) x)
                           (throw (ex-info "entity-id position names an unmapped id"
                                           {:case (get c "id"), :path path, :value x})))
                         (get-in logical->real [source x]))
                  [head & more] path]
              (if (= "query" head)
                [(update-path q more real) ins]
                [q (update-path ins path real)])))
          [query (vec inputs)]
          (case-entity-ids c)))


(defn case-expected
  "The typed expected value of an executable or difference case."
  [c]
  (decode (get-in c ["expect" "value"])))
