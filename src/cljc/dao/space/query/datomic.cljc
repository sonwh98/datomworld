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

   Bounded execution (Phase 4a): `:timeout` (positive integer milliseconds,
   a cooperative deadline minted at entry before parsing; expiry is observed
   time >= deadline), the DaoSpace extension
   `:dao.space.query.datomic/max-steps` (a positive explicit work allowance;
   no ambient default) and the injectable clock
   `:dao.space.query.datomic/now-fn` (zero-argument, milliseconds). One
   immutable execution state is carried from parsing through translation,
   the shared evaluation kernel and output. A call supplying either bound
   is admitted only over explicit materialized relations/fact-relations and
   views over them: opened published indexes and deferred rows are refused
   with `:unsupported` / `:bounded-source-unqualified` before any
   realization or read (checked published-index traversal is Phase 4b).
   Exhaustion is `:timeout` or `:step-budget-exhausted`
   (`:cognitect.anomalies/interrupted`), never a partial relation. The
   default clock is monotonic milliseconds on the JVM and JavaScript and a
   WALL-CLOCK fallback on Dart.

   Refused loudly (never silently ignored): the db-first call shape, raw
   vectors/maps at source positions, unknown options, `:ret`,
   `:io-context`, `:query-stats`,
   four-/five-position history patterns, rest patterns, the excluded
   randomized aggregates (rand, sample), unvalidated pull syntax (recursion,
   reverse attributes, dynamic or source-qualified patterns). Aggregates are
   count, count-distinct, sum, avg, distinct, median, variance, stddev and
   min/max, optionally with the positive integer n (Phase 2); arity and
   parameters are validated before evaluation.

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
            [dao.space.query.execution :as exec]
            #?(:cljd [clojure.edn :as edn]
               :clj [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])
            #?(:cljd ["dart:core" :as dart-core])))


;; ==========================================================================
;; Errors
;; ==========================================================================

(def ^:private categories
  {:invalid-request :cognitect.anomalies/incorrect
   :unsupported :cognitect.anomalies/unsupported
   :timeout :cognitect.anomalies/interrupted
   :step-budget-exhausted :cognitect.anomalies/interrupted
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


(def max-syntax-depth
  "The documented bound on collection nesting in a query's SYNTAX (the
   query, its pull patterns, and rules passed at `%`): string queries are
   checked by the scanner, native map/vector queries by `syntax-extent`.
   Recursive validation, translation and pull parsing walk syntax by host
   recursion, so the profile declares this limit instead of relying on the
   host stack. Source descriptor chains are NOT syntax: they are traversed
   iteratively and accounted. Data in `:args` is opaque and not measured."
  64)


;; ==========================================================================
;; Syntax extent (iterative, bounded)
;; ==========================================================================

(defn- syntax-extent
  "[nodes depth-exceeded?] of a native syntax form, measured with an explicit
   stack (no host recursion). Stops as soon as the nesting exceeds
   `max-syntax-depth`."
  [form]
  (loop [stack [[form 1]]
         nodes 0]
    (if (empty? stack)
      [nodes false]
      (let [[x d] (peek stack)
            stack (pop stack)]
        (if (coll? x)
          (if (> d max-syntax-depth)
            [nodes true]
            (recur (into stack
                         (map (fn [c] [c (inc d)]))
                         (if (map? x) (mapcat identity x) x))
                   (inc nodes)))
          (recur stack (inc nodes)))))))


(defn- check-syntax-extent!
  "Refuse over-deep native syntax before anything recurses over it; returns
   the node count."
  [form]
  (let [[nodes exceeded?] (syntax-extent form)]
    (when exceeded?
      (fail :unsupported "query syntax nests deeper than the supported profile"
            {:reason :syntax-depth-limit}))
    nodes))


;; ==========================================================================
;; Safe EDN string reading
;; ==========================================================================

(def ^:private max-string-length 100000)


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
            (if (>= depth max-syntax-depth)
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

(def ^:private aggregate-arities
  "The aggregates the profile admits and the argument counts each accepts
   (parameters included): only min and max take the leading positive
   integer n."
  {'count #{1} 'count-distinct #{1} 'sum #{1} 'avg #{1}
   'distinct #{1} 'median #{1} 'variance #{1} 'stddev #{1}
   'min #{1 2} 'max #{1 2}})


(def ^:private excluded-aggregates
  "Excluded by the contract: ambient randomness has no entropy ownership."
  #{'rand 'sample})


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
          (contains? excluded-aggregates h)
          (fail :unsupported "randomized aggregates are excluded from the profile"
                {:variable (fragment el)})
          (not (contains? aggregate-arities h))
          (fail :invalid-request "unknown aggregate" {:variable (fragment el)})
          (not (contains? (get aggregate-arities h) (dec (count el))))
          (fail :invalid-request "aggregate arity does not match its definition"
                {:variable (fragment el)})
          (and (= 3 (count el))
               (not (and (integer? (second el)) (pos? (second el)))))
          (fail :invalid-request "aggregate parameter must be a positive integer"
                {:variable (fragment el)}))))))


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
              (= '% pattern) (do (check-syntax-extent! arg) (translate-rules arg))
              :else arg))
          in args)))


;; ==========================================================================
;; Options and pagination
;; ==========================================================================

(def ^:private known-options
  #{:query :args :offset :limit :timeout ::max-steps ::now-fn})


(def ^:private refused-options #{:ret :io-context :query-stats :stats})


(defn- positive-integer?
  [x]
  (and (integer? x) (pos? x)))


(defn- check-options
  "Validate the arg-map options: [offset limit timeout max-steps]."
  [arg-map]
  (doseq [k (keys arg-map)]
    (cond
      (contains? known-options k) nil
      (contains? refused-options k)
      (fail :unsupported (str "option " k " is outside this profile") {:option k})
      :else (fail :unsupported "unknown option is not ignored"
                  {:option (if (keyword? k) k (fragment k))})))
  (let [offset (get arg-map :offset 0)
        limit (get arg-map :limit -1)
        timeout (get arg-map :timeout)
        max-steps (get arg-map ::max-steps)]
    (when-not (and (integer? offset) (not (neg? offset)))
      (fail :invalid-request ":offset must be a nonnegative integer" {:option :offset}))
    (when-not (and (integer? limit) (or (= -1 limit) (not (neg? limit))))
      (fail :invalid-request ":limit must be a nonnegative integer or -1"
            {:option :limit}))
    (when (and (contains? arg-map :timeout) (not (positive-integer? timeout)))
      (fail :invalid-request ":timeout must be a positive integer number of milliseconds"
            {:option :timeout}))
    (when (and (contains? arg-map ::max-steps) (not (positive-integer? max-steps)))
      (fail :invalid-request "max-steps must be a positive integer"
            {:option ::max-steps}))
    (when (and (contains? arg-map ::now-fn) (not (fn? (get arg-map ::now-fn))))
      (fail :invalid-request "now-fn must be a zero-argument function"
            {:option ::now-fn}))
    [offset limit timeout max-steps]))


(defn ^:no-doc default-now-ms
  "The default clock: a monotonic source expressed in milliseconds where the
   host offers one (JVM `System/nanoTime`; JavaScript `performance.now`).
   Dart has no portable monotonic reading here, so it falls back to
   WALL-CLOCK milliseconds: a clock adjustment can then shorten or lengthen
   a timeout, and that is not monotonic behavior. Callers needing a
   different clock pass `:dao.space.query.datomic/now-fn`."
  []
  #?(:cljd (.-millisecondsSinceEpoch (dart-core/DateTime.now))
     :clj (quot (System/nanoTime) 1000000)
     :cljs (if (and (exists? js/performance) (fn? (.-now js/performance)))
             (.now js/performance)
             (.getTime (js/Date.)))))


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
      (:dao.space.query/source-failure data)
      ;; a traversal or read failure of a checked published source: the
      ;; original (cursor validation error, missing or corrupt segment) is
      ;; the cause; no address, descriptor or handle is copied
      (fail :source-failed "source read failed" {} (or (ex-cause e) e))
      (= :checked-source-fallback (:reason data))
      (fail :unsupported "this clause needs an eager fallback a checked source does not offer"
            {:reason :checked-source-fallback})
      (some? data)
      (let [msg (or (ex-message e) "query refused")]
        (if (str/includes? msg "not implemented")
          (fail :unsupported msg {})
          (fail :invalid-request msg {})))
      :else (fail :evaluation-failed "query evaluation failed" {} e))))


(defn- exhausted!
  "Translate a terminal execution outcome into the structured exception. A
   partial relation is never returned."
  [st]
  (let [{:keys [kind phase steps]} (exec/outcome st)]
    (if (= :timeout kind)
      (fail :timeout "query deadline expired"
            {:option :timeout, :phase phase, :steps steps})
      (fail :step-budget-exhausted "query work allowance exhausted"
            {:option ::max-steps, :phase phase, :steps steps}))))


(defn- advance
  "Adapter-side accounting: enter `phase`, then charge n units before the
   adapter performs the corresponding (indivisible) work. n = 0 only
   observes. Throws the structured exhaustion error on a terminal state."
  [st driver phase n]
  (let [st (exec/enter st phase)
        st (if (pos? n) (exec/step st driver n) (exec/checkpoint st driver phase))]
    (if (exec/terminal? st) (exhausted! st) st)))


(defn- source-admissible?
  "Bounded admission through the ONE shared classification policy
   (`dao.space.query/source-step`, also used by bounded realization). A
   source is admitted when it is an explicit materialized relation, a view
   over one, or exactly a `current` view with no as-of directly over a
   published value carrying a structurally valid checked-read descriptor.
   Everything else (bare published values, history, as-of, nested views over
   published data, descriptor-less values, deferred rows, anything
   unrecognized) is refused. Descriptors are inspected, never forced, and
   nothing is read. The chain is walked iteratively and each hop is one
   accounted unit, so a chain of any depth is a structured exhaustion or
   refusal, never a host stack overflow. Returns [state admissible?]."
  [st driver v]
  (loop [st st
         v v
         depth 0]
    (let [r (query/source-step v depth)]
      (if-let [class (:class r)]
        [st (contains? #{:materialized :checked} class)]
        (recur (advance st driver :parse 1) (:next r) (inc depth))))))


(defn- check-bounded-sources!
  "Fail closed, before any realization: every declared source of a bounded
   call must be admissible, including a source the plan would not use.
   Returns the state."
  [st driver in bound]
  (reduce (fn [st [pattern arg]]
            (if-not (db-sym? pattern)
              st
              (let [[st ok?] (source-admissible? st driver arg)]
                (when-not ok?
                  (fail :unsupported
                        "bounded execution requires an explicit materialized relation source"
                        {:reason :bounded-source-unqualified
                         :variable (fragment pattern)}))
                st)))
          st
          (map vector in bound)))


(defn- run
  "[state result] of the common kernel for one translated query."
  [st driver m args bounded?]
  (let [in (vec (or (seq (:in m)) ['$]))
        _ (check-find (:find m))
        where (translate-clauses (:where m))
        bound (check-inputs in args)
        core-query (assoc m :in in :where where)
        st (if bounded? (check-bounded-sources! st driver in bound) st)]
    (try
      (apply query/q-with-state st driver core-query bound)
      (catch #?(:cljd Object :clj Throwable :cljs :default) e
        (classify-core-failure e)))))


(defn- parse-cost
  [form]
  (if (string? form)
    (inc (quot (count form) 128))
    1))


(defn- execute
  "The whole invocation under one execution state: parse, translate,
   evaluate, output. `bounded?` selects the admission rule."
  [form args {:keys [offset limit st driver bounded?]}]
  (let [st (advance st driver :parse (parse-cost form))
        m (query-map form)
        st (advance st driver :parse (check-syntax-extent! m))
        [st res] (run st driver m args bounded?)
        _ (when (exec/terminal? st) (exhausted! st))
        rows (:dao.space.query/result res)
        st (advance st driver :output (if (coll? rows) (count rows) 1))
        result (query/collect res)
        paged (paginate result (if (or (:keys m) (:syms m) (:strs m))
                                 :relation
                                 (spec-of (:find m)))
                        offset limit)
        st (exec/finish st driver)]
    (when (exec/terminal? st) (exhausted! st))
    paged))


(defn- invocation
  "The execution inputs of an arg-map call; the clock is read FIRST (before
   any parsing) when a timeout was supplied, and the absolute deadline is
   that reading plus the timeout."
  [arg-map]
  (let [now-fn (let [f (::now-fn arg-map)] (if (fn? f) f default-now-ms))
        t0 (when (contains? arg-map :timeout) (now-fn))
        [offset limit timeout max-steps] (check-options arg-map)]
    {:offset offset
     :limit limit
     :driver (exec/fn-driver now-fn)
     :bounded? (or (some? timeout) (some? max-steps))
     :st (exec/start {:deadline (when timeout (+ t0 timeout))
                      :max-steps max-steps})}))


(def ^:private unbounded-invocation
  {:offset 0 :limit -1 :driver nil :bounded? false :st exec/unbounded})


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
     (execute (:query arg-map) (get arg-map :args []) (invocation arg-map))

     (or (string? arg-map) (sequential? arg-map)
         (and (map? arg-map) (some #(contains? arg-map %) [:find :where :in])))
     (execute arg-map [] unbounded-invocation)

     :else (fail :invalid-request "q requires an arg-map or a query" {})))
  ([query-or-db & args]
   (if (db-first? query-or-db)
     (fail :unsupported "db-first q is not the public Client call shape" {})
     (execute query-or-db (vec args) unbounded-invocation))))
