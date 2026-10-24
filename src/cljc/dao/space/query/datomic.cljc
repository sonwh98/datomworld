(ns dao.space.query.datomic
  "The Datomic Client query adapter boundary
   (docs/design/dao.space.query.datomic-compat.md, Architecture and
   ownership).

   `q` takes the two public Client call shapes, `(q arg-map)` and
   `(q query & args)`, validates and normalizes them, translates database
   pattern syntax, runs core `dao.space.query/q` + `collect` over the
   caller's immutable source values, applies `:offset`/`:limit` to the
   realized rows, and returns ordinary data.

   The adapter borrows its sources: it opens, closes, publishes and writes
   nothing, keeps no registry, no cache, no atom, no dynamic var and reads
   no clock. Core evaluation never sees a Client option name.

   Refused loudly (never silently ignored): the db-first call shape, raw
   vectors/maps at source positions, unknown options, `:timeout` (until
   the execution-bounds phase), `:ret`, `:io-context`, `:query-stats`,
   four-/five-position history patterns, rest patterns, parameterized
   aggregate syntax, unvalidated pull syntax (recursion, reverse
   attributes, dynamic or source-qualified patterns).

   Numeric limitation (corpus errata E1): core builds its final result set
   with host `=`, so content-distinct numerics that compare equal on a host
   (1.0M and 1.00M, 0.0 and -0.0 on the JVM) collapse to one row before
   this adapter sees them; its vector output cannot recover them.

   Errors are ex-info with `:dao.space.query.datomic/error` and
   `:cognitect.anomalies/category`, carrying minimal context only (option,
   variable, a bounded clause fragment). Core exception data, which can
   embed bindings, is never copied; only a genuine evaluator or source
   failure is kept as `ex-cause`."
  (:require [clojure.string :as str]
            [dao.space.query :as query]
            #?(:cljd [clojure.edn :as edn]
               :clj [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])))


;; ==========================================================================
;; Errors
;; ==========================================================================

(def ^:private categories
  {:invalid-request :cognitect.anomalies/incorrect
   :unsupported :cognitect.anomalies/unsupported
   :evaluation-failed :cognitect.anomalies/fault
   :source-failed :cognitect.anomalies/fault})


(defn- fragment
  "A bounded printed fragment of a clause, never the whole input."
  [x]
  (let [s (pr-str x)]
    (if (> (count s) 120) (str (subs s 0 120) "...") s)))


(defn- fail
  "Throw the adapter error `kind` with a message and minimal context."
  ([kind message context]
   (throw (ex-info message
                   (merge {:cognitect.anomalies/category (get categories kind)}
                          context
                          {::error kind}))))
  ([kind message context cause]
   (throw (ex-info message
                   (merge {:cognitect.anomalies/category (get categories kind)}
                          context
                          {::error kind})
                   cause))))


;; ==========================================================================
;; Safe EDN string reading
;; ==========================================================================

(def ^:private max-string-length 100000)
(def ^:private max-nesting 64)


(defn- scan-string
  "Refuse, before any reader runs, what a safe profile must not read:
   tagged literals, reader conditionals, evaluation, over-long or too deeply
   nested text. Returns nil when acceptable, else [kind message]."
  [s]
  (if (> (count s) max-string-length)
    [:invalid-request "query string is too long"]
    (loop [i 0 in-str? false esc? false comment? false depth 0]
      (if (>= i (count s))
        nil
        (let [c (nth s i)]
          (cond
            comment? (recur (inc i) in-str? false (not= c \newline) depth)
            esc? (recur (inc i) in-str? false false depth)
            in-str? (recur (inc i) (not= c \") (= c \\) false depth)
            (= c \") (recur (inc i) true false false depth)
            (= c \;) (recur (inc i) false false true depth)
            (= c \\) (recur (+ i 2) false false false depth)
            (and (= c \#) (< (inc i) (count s))
                 (let [n (nth s (inc i))]
                   (not (or (= n \{) (= n \_)))))
            [:unsupported "tagged literals and dispatch forms are not read"]
            (contains? #{\( \[ \{} c)
            (if (>= depth max-nesting)
              [:invalid-request "query string is nested too deeply"]
              (recur (inc i) false false false (inc depth)))
            (contains? #{\) \] \}} c) (recur (inc i) false false false (dec depth))
            :else (recur (inc i) false false false depth)))))))


(defn- read-edn
  [s]
  #?(:cljd (edn/read-string s)
     :clj (edn/read-string {:readers {}
                            :default (fn [_ _] (throw (ex-info "tag" {})))}
                           s)
     :cljs (reader/read-string {:readers {}
                                :default (fn [_ _] (throw (ex-info "tag" {})))}
                               s)))


(defn- read-query-string
  "The single EDN form in s, read without evaluation or tag handlers."
  [s]
  (when-let [[kind message] (scan-string s)]
    (fail kind message {:option :query}))
  (let [forms (try (read-edn (str "[" s "\n]"))
                   (catch #?(:cljd Object :clj Throwable :cljs :default) _
                     (fail :invalid-request "query string is not readable EDN"
                           {:option :query})))]
    (when-not (and (vector? forms) (= 1 (count forms)))
      (fail :invalid-request "query string must hold exactly one form"
            {:option :query}))
    (first forms)))


;; ==========================================================================
;; Query normalization
;; ==========================================================================

(def ^:private sections #{:find :in :with :where :keys :syms :strs})


(defn- sections-of-sequence
  [form]
  (loop [xs (seq form) result {} cur nil vals []]
    (if-not xs
      (if cur (assoc result cur vals) result)
      (let [x (first xs)]
        (cond
          (contains? sections x)
          (do (when (or (= cur x) (contains? result x))
                (fail :invalid-request "query section repeated" {:clause x}))
              (recur (next xs) (if cur (assoc result cur vals) result) x []))
          (nil? cur)
          (fail :invalid-request "query must start with a section keyword"
                {:clause (fragment x)})
          :else (recur (next xs) result cur (conj vals x)))))))


(defn- query-map
  "The validated query map of a map, a sequential form or an EDN string."
  [form]
  (let [form (if (string? form) (read-query-string form) form)
        m (cond
            (map? form)
            (do (doseq [k (keys form)]
                  (when-not (contains? sections k)
                    (fail :invalid-request "unknown query section"
                          {:clause (fragment k)})))
                (into {} (map (fn [[k v]]
                                (when-not (sequential? v)
                                  (fail :invalid-request "query section must be a sequence"
                                        {:clause k}))
                                [k (vec v)]))
                      form))
            (sequential? form) (sections-of-sequence form)
            :else (fail :invalid-request
                        "query must be a map, a vector/list or an EDN string"
                        {:option :query}))]
    (when (empty? (:find m))
      (fail :invalid-request "query requires a non-empty :find" {:clause :find}))
    (assoc m :where (vec (:where m)))))


;; ==========================================================================
;; Pattern translation
;; ==========================================================================

(defn- db-sym?
  [x]
  (and (symbol? x) (= \$ (first (name x)))))


(defn- pad-pattern
  [clause]
  (let [items (vec clause)
        src? (db-sym? (first items))
        body (if src? (subvec items 1) items)
        n (count body)]
    (cond
      (some #(= '& %) body)
      (fail :unsupported "rest patterns are outside the Client profile"
            {:clause (fragment clause)})
      (zero? n) (fail :invalid-request "empty data pattern" {:clause (fragment clause)})
      (> n 3) (fail :unsupported
                    "four-/five-position history patterns are outside this profile"
                    {:clause (fragment clause)})
      :else (into (if src? [(first items)] [])
                  (concat body (repeat (- 3 n) '_))))))


(declare translate-clause)


(defn- translate-clauses
  [clauses]
  (mapv translate-clause clauses))


(defn- rebuild
  "A (head [prefix] children...) list with translated children."
  [head more children]
  (apply list head (concat more (translate-clauses children))))


(defn- translate-clause
  [clause]
  (cond
    (vector? clause)
    (if (seq? (first clause)) clause (pad-pattern clause))

    (seq? clause)
    (let [[h & more] clause
          src (when (db-sym? (first more)) [(first more)])
          more (if src (rest more) more)]
      (cond
        (contains? #{'not 'or 'and} h) (rebuild h src more)
        (contains? #{'not-join 'or-join} h)
        (do (when-not (vector? (first more))
              (fail :invalid-request "join form requires a variable vector"
                    {:clause (fragment clause)}))
            (rebuild h (concat src [(first more)]) (rest more)))
        :else clause))

    :else (fail :invalid-request "unrecognized where clause"
                {:clause (fragment clause)})))


(defn- translate-rules
  [rules]
  (when-not (sequential? rules)
    (fail :invalid-request "rules input must be a vector of rules" {:option :args}))
  (mapv (fn [rule]
          (when-not (and (sequential? rule) (seq rule))
            (fail :invalid-request "malformed rule" {:clause (fragment rule)}))
          (into [(first rule)] (translate-clauses (rest rule))))
        rules))


;; ==========================================================================
;; Find validation (aggregates and finite pull)
;; ==========================================================================

(def ^:private core-aggregates
  #{'count 'count-distinct 'sum 'min 'max 'avg})


(def ^:private later-aggregates
  #{'distinct 'median 'variance 'stddev 'rand 'sample})


(declare check-pull-pattern)


(defn- check-attr-options
  [element]
  (let [[attr & opts] element]
    (when-not (and (keyword? attr) (not= \_ (first (name attr))))
      (fail :unsupported "pull attribute options need a forward keyword attribute"
            {:clause (fragment element)}))
    (when (odd? (count opts))
      (fail :invalid-request "pull attribute options must be key/value pairs"
            {:clause (fragment element)}))
    (doseq [[k v] (partition 2 opts)]
      (when-not (contains? #{:default :as :limit} k)
        (fail :unsupported "pull attribute option is outside the admitted profile"
              {:option k}))
      (when (and (= :limit k) (not (and (integer? v) (not (neg? v)))))
        (fail :invalid-request "pull :limit must be a nonnegative integer"
              {:option :limit})))))


(defn- check-pull-pattern
  [pattern]
  (cond
    (symbol? pattern)
    (fail :unsupported "dynamic pull patterns are outside the admitted profile" {})
    (not (vector? pattern))
    (fail :invalid-request "pull pattern must be a vector" {:clause (fragment pattern)})
    :else
    (doseq [e pattern]
      (cond
        (= '* e) nil
        (keyword? e)
        (when (= \_ (first (name e)))
          (fail :unsupported "reverse pull attributes are not admitted"
                {:clause (fragment e)}))
        (vector? e) (check-attr-options e)
        (map? e)
        (do (when-not (= 1 (count e))
              (fail :unsupported "one nested attribute per pull map"
                    {:clause (fragment e)}))
            (let [[attr sub] (first e)]
              (when-not (and (keyword? attr) (not= \_ (first (name attr))))
                (fail :unsupported "nested pull needs a forward keyword attribute"
                      {:clause (fragment e)}))
              (when (or (= '... sub) (integer? sub))
                (fail :unsupported "recursive pull is not admitted"
                      {:clause (fragment e)}))
              (check-pull-pattern sub)))
        (seq? e)
        (fail :unsupported
              "list-form pull options are not read; use the vector form [attr :default x]"
              {:clause (fragment e)})
        :else (fail :invalid-request "malformed pull pattern" {:clause (fragment e)})))))


(defn- find-elements
  [find]
  (let [f (first find)]
    (if (vector? f) f find)))


(defn- check-find
  [find]
  (doseq [el (find-elements find)]
    (when (seq? el)
      (let [h (first el)]
        (cond
          (= 'pull h)
          (do (when-not (= 3 (count el))
                (fail :unsupported
                      "source-qualified or malformed pull is outside the admitted profile"
                      {:clause (fragment el)}))
              (check-pull-pattern (nth el 2)))
          (or (contains? later-aggregates h) (> (count el) 2))
          (fail :unsupported "aggregate or parameterized aggregate is not available yet"
                {:variable (fragment el)})
          (not (contains? core-aggregates h))
          (fail :invalid-request "unknown aggregate" {:variable (fragment el)}))))))


;; ==========================================================================
;; Inputs
;; ==========================================================================

(defn- check-inputs
  "Validate :args against :in before evaluation. Source positions come from
   :in only; every other argument stays ordinary data, even a map or vector.
   Rules passed at `%` get the same pattern translation as :where."
  [in args]
  (when-not (sequential? args)
    (fail :invalid-request ":args must be a vector" {:option :args}))
  (let [args (vec args)]
    (when-not (= (count in) (count args))
      (fail :invalid-request "number of :args must match :in" {:option :args}))
    (mapv (fn [pattern arg]
            (cond
              (db-sym? pattern)
              (if (query/value? arg)
                arg
                (fail :invalid-request
                      (str "source input must be a dao.space query value; wrap raw "
                           "data with dao.space.query/relation, fact-relation, "
                           "current or history")
                      {:variable (fragment pattern)}))
              (= '% pattern) (translate-rules arg)
              :else arg))
          in args)))


;; ==========================================================================
;; Options and pagination
;; ==========================================================================

(def ^:private known-options #{:query :args :offset :limit})


(def ^:private refused-options #{:timeout :ret :io-context :query-stats :stats})


(defn- check-options
  [arg-map]
  (doseq [k (keys arg-map)]
    (cond
      (contains? known-options k) nil
      (contains? refused-options k)
      (fail :unsupported (str "option " k " is outside this profile") {:option k})
      :else (fail :unsupported "unknown option is not ignored"
                  {:option (if (keyword? k) k (fragment k))})))
  (let [offset (get arg-map :offset 0)
        limit (get arg-map :limit -1)]
    (when-not (and (integer? offset) (not (neg? offset)))
      (fail :invalid-request ":offset must be a nonnegative integer" {:option :offset}))
    (when-not (and (integer? limit) (or (= -1 limit) (not (neg? limit))))
      (fail :invalid-request ":limit must be a nonnegative integer or -1"
            {:option :limit}))
    [offset limit]))


(defn- paginate
  [result spec offset limit]
  (let [paged? (or (pos? offset) (not= -1 limit))]
    (cond
      (contains? #{:scalar :tuple} spec)
      (do (when paged?
            (fail :unsupported "offset/limit require a relation or collection find"
                  {:option (if (pos? offset) :offset :limit)}))
          result)
      :else
      (let [rows (cond->> (seq result)
                   (pos? offset) (drop offset)
                   (not= -1 limit) (take limit))]
        (vec rows)))))


;; ==========================================================================
;; Execution
;; ==========================================================================

(defn- spec-of
  [find]
  (let [f (first find)]
    (cond (and (vector? f) (some #(= '... %) f)) :coll
          (vector? f) :tuple
          (some #(= (symbol ".") %) find) :scalar
          :else :relation)))


(defn- classify-core-failure
  "Rethrow a core failure under the adapter taxonomy. Core ex-data can embed
   bindings, so ordinary core refusals keep only their message; a genuine
   fault or source failure keeps its cause."
  [e]
  (let [data (ex-data e)
        category (:cognitect.anomalies/category data)]
    (cond
      (::error data) (throw e)
      category (fail :source-failed "source read failed"
                     {:cognitect.anomalies/category category} e)
      (some? data)
      (let [msg (or (ex-message e) "query refused")]
        (if (str/includes? msg "not implemented")
          (fail :unsupported msg {})
          (fail :invalid-request msg {})))
      :else (fail :evaluation-failed "query evaluation failed" {} e))))


(defn- run
  [m args]
  (let [in (vec (or (seq (:in m)) ['$]))
        _ (check-find (:find m))
        where (translate-clauses (:where m))
        bound (check-inputs in args)
        core-query (assoc m :in in :where where)]
    (try
      (query/collect (apply query/q core-query bound))
      (catch #?(:cljd Object :clj Throwable :cljs :default) e
        (classify-core-failure e)))))


(defn- execute
  [form args offset limit]
  (let [m (query-map form)
        result (run m args)]
    (paginate result (if (or (:keys m) (:syms m) (:strs m))
                       :relation
                       (spec-of (:find m)))
              offset limit)))


(defn- db-first?
  [x]
  (or (query/value? x)
      (and (map? x) (contains? x :relation) (contains? x :status))))


(defn q
  "Datomic Client `q`: `(q arg-map)` with `:query`, `:args`, `:offset`,
   `:limit`, or `(q query & args)`. `query` is a map, a vector/list or an
   EDN string. Returns realized data; see the namespace docstring for the
   refusals and the error taxonomy."
  ([arg-map]
   (cond
     (db-first? arg-map)
     (fail :unsupported "db-first q is not the public Client call shape" {})

     (and (map? arg-map) (contains? arg-map :query))
     (let [[offset limit] (check-options arg-map)]
       (execute (:query arg-map) (get arg-map :args []) offset limit))

     (or (string? arg-map) (sequential? arg-map)
         (and (map? arg-map) (some #(contains? arg-map %) [:find :where :in])))
     (execute arg-map [] 0 -1)

     :else (fail :invalid-request "q requires an arg-map or a query" {})))
  ([query-or-db & args]
   (if (db-first? query-or-db)
     (fail :unsupported "db-first q is not the public Client call shape" {})
     (execute query-or-db (vec args) 0 -1))))
