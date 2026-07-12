(ns dao.space.query
  "The Query boundary of the tuple space: `match` (Linda-style positional
  templates) and `q` (Datalog: joins, `not`/`not-join` negation, `:find`
  aggregates — count, count-distinct, sum, min, max, avg — with `:with`,
  predicate/function clauses whose fns come from a caller-supplied
  `{:fns {sym fn}}` option, and recursive rules bound to `%` via `:in`;
  one merged index) over a **source**. The read side is pure and stateless —
  it owns no durable state, never writes, and enforces no schema; schema is
  the write-side Transactor's job (each `dao.stream` writer is its own).
  This library is the embeddable Peer: it consumes the covered-index
  realization owned by `dao.space.index` (root shapes, sort orders, the
  persisted node-blob format, and the owner-side `publish-index!` — see
  docs/design/dao.space.index.md). See docs/design/dao.space.md, \"The
  Query Library\" and \"Source Polymorphism\".

  A source is, interchangeably (and freely mixed in a collection):
    - a single `dao.jing` IKVStore handle
    - a collection of `dao.jing` handles (a federated query — ADR 0001's
      monoid-homomorphism proof is why folding N stores and merging equals
      one store holding everything)
    - a raw vector of datoms, `[[e a v t m] ...]`
    - a raw vector of entity maps, `[{:attr val ...} ...]`

  Reading a `dao.jing` handle supports both root shapes at
  `index/default-datoms-key` (`:root/datoms`): the wholesale
  `{:datoms [...]}` baseline, folded into a fresh in-memory index per
  query, and the owner-built `{:indexes ...}` manifest, restored lazily on
  the JVM and walked eagerly everywhere else (see dao.space.index).

  Current-state resolution (masking retracted datoms and superseding
  cardinality-one values) is resolved dynamically at query time using
  `current-state-seq`."
  (:require [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.space.index :as index]))


;; =============================================================================
;; Source -> datoms
;; =============================================================================

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
  (satisfies? jing/IKVStore x))


(declare source->datoms)


(defn- coll-source->datoms
  [source]
  (cond (every? dao-jing-handle? source) (mapcat index/read-datoms source)
        (every? vector? source) source
        (every? map? source) (entity-maps->datoms source)
        :else (mapcat source->datoms source)))


(defn source->datoms
  "Fold any source shape (see ns docstring) into a flat seq of datoms."
  [source]
  (cond (dao-jing-handle? source) (index/read-datoms source)
        (coll? source) (coll-source->datoms source)
        :else (throw (ex-info "unrecognized query source" {:source source}))))


(defn- bound-datoms
  [datoms as-of]
  (if as-of
    (filter #(<= (index/compare-vals (index/datom-t %) as-of) 0) datoms)
    datoms))


(defn fold
  "Fold a source into an index, optionally bounded to datoms with t <= as-of.
  A single dao.jing handle whose root carries owner-built `:indexes` folds
  lazily on the JVM (nothing loaded until traversal); every other shape —
  as-of bounds, federated collections, raw vectors, non-JVM platforms —
  takes the eager path (for `:indexes` roots, via walk-index-datoms)."
  ([source] (fold source nil))
  ([source as-of]
   (or #?(:cljd nil
          :clj (when (dao-jing-handle? source)
                 ;; single-handle: read the root exactly once and dispatch
                 ;; on its shape here, rather than falling through to
                 ;; source->datoms and re-reading the same key
                 (let [root (jing/get source index/default-datoms-key nil)
                       indexes (:indexes root)]
                   (if (and (nil? as-of)
                            ;; a complete manifest only: an empty published
                            ;; index has nil root addresses (walk of nil =>
                            ;; ()), and a partial hand-crafted one must not
                            ;; reach restore-by
                            (every? #(some? (get indexes %))
                                    [:eavt :aevt :avet]))
                     (index/restored-indexes source indexes)
                     (-> (if indexes
                           (index/walk-index-datoms source (:eavt indexes))
                           (:datoms root))
                         (bound-datoms as-of)
                         index/index-datoms)))))
       (-> (source->datoms source)
           (bound-datoms as-of)
           index/index-datoms))))


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


(defn current-state-seq
  "Takes a sequence of datoms (the log) and returns a sequence of currently asserted datoms.
   If a datom is retracted, it masks prior assertions."
  ([s] (current-state-seq s nil))
  ([s pending]
   (lazy-seq (if-let [s (seq s)]
               (let [d (first s)]
                 (if pending
                   (if (and (= (index/datom-e pending) (index/datom-e d))
                            (= (index/datom-a pending) (index/datom-a d))
                            (= (index/datom-v pending) (index/datom-v d)))
                     (current-state-seq (rest s) d)
                     (if (not= (index/datom-m pending) 0)
                       (cons pending (current-state-seq (rest s) d))
                       (current-state-seq (rest s) d)))
                   (current-state-seq (rest s) d)))
               (when (and pending (not= (index/datom-m pending) 0))
                 (list pending))))))


(defn- select-by-index
  [idx e a v]
  (let [candidates (cond (not (wildcard? e))
                         (take-while #(= e (index/datom-e %))
                                     (index/subseq-from (:eavt idx)
                                                        index/eavt-cmp
                                                        [e nil nil nil nil]))
                         (and (not (wildcard? a)) (not (wildcard? v)))
                         (take-while #(and (= a (index/datom-a %))
                                           (= v (index/datom-v %)))
                                     (index/subseq-from (:avet idx)
                                                        index/avet-cmp
                                                        [nil a v nil nil]))
                         (not (wildcard? a))
                         (take-while #(= a (index/datom-a %))
                                     (index/subseq-from (:aevt idx)
                                                        index/aevt-cmp
                                                        [nil a nil nil nil]))
                         :else (seq (:eavt idx)))]
    (current-state-seq candidates)))


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
;; q: Datalog (:find / :in / :where, one merged index)
;; =============================================================================

(declare eval-where)


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


(defn- db-sym?
  [x]
  (and (symbol? x) (= \$ (first (name x)))))


(defn- classify-in-pattern
  [pattern]
  (cond (db-sym? pattern) :db
        (symbol? pattern) :scalar
        (and (vector? pattern) (vector? (first pattern))) :relation
        (and (vector? pattern) (= '... (last pattern))) :coll
        (vector? pattern) :tuple
        :else (throw (ex-info "Unknown :in pattern" {:pattern pattern}))))


(defn- expand-in-binding
  [pattern value as-of]
  (case (classify-in-pattern pattern)
    :db [{::dbs {pattern (fold value as-of)}}]
    :scalar [{pattern value}]
    :coll (let [sym (first pattern)] (mapv (fn [elem] {sym elem}) value))
    :tuple [(zipmap pattern value)]
    :relation (let [vars (first pattern)]
                (mapv (fn [tup] (zipmap vars tup)) value))))


(defn- merge-bindings
  [b1 b2]
  (let [merged-dbs (merge (get b1 ::dbs {}) (get b2 ::dbs {}))]
    (cond-> (merge b1 b2) (seq merged-dbs) (assoc ::dbs merged-dbs))))


(defn- cross-join-bindings
  [rows1 rows2]
  (for [b1 rows1 b2 rows2] (merge-bindings b1 b2)))


(defn- build-init-bindings
  [in-patterns inputs as-of]
  (reduce (fn [acc [pat val]]
            (cross-join-bindings acc (expand-in-binding pat val as-of)))
          [{}]
          (map vector in-patterns inputs)))


(defn- pattern-clause?
  [clause]
  ;; Only a plain [e a v (t m)] vector is a reorderable pattern clause.
  ;; Every list form is an order barrier: not/not-join (negation) and any
  ;; other list (a rule invocation). The clause planner can't see inside a
  ;; rule, so it must not reorder one by a selectivity estimate that would
  ;; treat the rule name as an entity id.
  (not (or (seq? clause) (and (vector? clause) (seq? (first clause))))))


(defn- clause-db-and-pattern
  [clause]
  (if (db-sym? (first clause))
    [(first clause) (vec (rest clause))]
    ['$ (vec clause)]))


(defn- resolve-db
  [binding db-sym]
  (get (get binding ::dbs) db-sym))


(defn- eval-pattern-clause
  [clause binding _ctx]
  (let [[db-sym pattern] (clause-db-and-pattern clause)
        idx (resolve-db binding db-sym)]
    (if-not idx
      []
      (let [[ce ca cv ct cm] (pad-to-5 pattern)
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
              datoms)))))


(defn- query-var?
  [x]
  (and (symbol? x) (= \? (first (name x)))))


(defn- not-required-vars
  "Vars a plain `not` requires bound in the outer scope: every ?var under it,
  except those scoped to a nested not-join — a nested not-join requires only
  its own join vars; everything else under it is locally scoped."
  [clauses]
  (distinct (mapcat (fn [clause]
                      (cond (and (seq? clause) (= 'not-join (first clause)))
                            (filter query-var? (nth clause 1))
                            (and (seq? clause) (= 'not (first clause)))
                            (not-required-vars (rest clause))
                            :else (filter query-var?
                                          (tree-seq coll? seq clause))))
                    clauses)))


(defn- eval-not
  [clause bindings ctx]
  (let [inner-clauses (rest clause)
        ;; Datomic: every var inside a plain not unifies with the outer
        ;; scope and must be bound there — an unbound var would act as a
        ;; wildcard and make the negation silently fail for every
        ;; candidate. not-join is the form that introduces not-local vars.
        ;; Computed once per clause, not per candidate binding.
        req-vars (not-required-vars inner-clauses)]
    (mapcat
      (fn [binding]
        (doseq [v req-vars]
          (when (= FREE (resolve-binding binding v))
            (throw
              (ex-info
                "All variables inside not must be bound; use not-join to introduce local variables"
                {:var v, :clause clause}))))
        (if (seq (eval-where inner-clauses [binding] ctx)) [] [binding]))
      bindings)))


(defn- eval-not-join
  [clause binding ctx]
  (let [req-vars (nth clause 1)
        inner-clauses (drop 2 clause)]
    (doseq [v req-vars]
      (when (= FREE (resolve-binding binding v))
        (throw (ex-info "not-join variables must be bound"
                        {:var v, :binding binding}))))
    ;; Only the declared join vars unify with the outer scope; any other
    ;; inner var is fresh, even if it shares a name with an outer var.
    (let [inner-binding (into (select-keys binding [::dbs])
                              (map (fn [v] [v (get binding v)]))
                              req-vars)]
      (if (seq (eval-where inner-clauses [inner-binding] ctx)) [] [binding]))))


(defn- eval-fn-clause
  "[(f arg ...)] is a predicate: keep the binding iff (f args) is truthy.
  [(f arg ...) ?out] or [(f arg ...) [?a ?b ...]] is a function clause:
  unify the return value into the output var(s). f is looked up in the
  caller-supplied :fns registry — no symbol resolution, no hidden globals."
  [clause binding ctx]
  (let [[[fsym & args] & result-vars] clause
        f (get-in ctx [:fns fsym])]
    (when-not f
      (throw (ex-info "Unknown query fn — pass it via the :fns option"
                      {:fn fsym, :clause clause})))
    (let [arg-vals (mapv (fn [a]
                           (let [v (resolve-binding binding a)]
                             (when (= FREE v)
                               (throw (ex-info "Unbound variable in fn clause"
                                               {:var a, :clause clause})))
                             v))
                         args)
          ret (apply f arg-vals)]
      (if (empty? result-vars)
        (if ret [binding] [])
        (let [out (first result-vars)]
          (when (> (count result-vars) 1)
            (throw
              (ex-info
                "Function clause takes one binding form; use a tuple [?a ?b] for multi-return"
                {:clause clause})))
          (when (and (vector? out) (some #(or (= '... %) (vector? %)) out))
            (throw
              (ex-info
                "Unsupported binding form — only a scalar ?out or tuple [?a ?b]"
                {:binding-form out, :clause clause})))
          (when (and (vector? out)
                     (or (not (sequential? ret))
                         (not= (count out) (count ret))))
            (throw (ex-info
                     "Function return does not match tuple binding arity"
                     {:binding-form out, :returned ret, :clause clause})))
          (let [pairs (if (vector? out) (map vector out ret) [[out ret]])
                b (reduce (fn [b [sym val]] (when b (unify b sym val)))
                          binding
                          pairs)]
            (if b [b] [])))))))


(defn- eval-rule
  "Evaluates a rule invocation (head) by finding matching rule definitions,
  unifying arguments with the rule head, evaluating the body, and unifying
  the results back to the caller's context. Tracks active rules to terminate
  on cyclic data, sound because every fact derivable through a cycle also has
  a finite acyclic derivation."
  [clause binding ctx]
  (let [[rule-name & call-args] clause
        rules (get binding '%)]
    (when-not rules
      (throw (ex-info "No rules bound; pass a rule set via :in $ %"
                      {:clause clause})))
    (let [defs (filter #(= rule-name (first (first %))) rules)]
      (when (empty? defs)
        (throw (ex-info "Unknown rule"
                        {:rule rule-name,
                         :known (vec (distinct (map ffirst rules)))})))
      (let [arg-vals (mapv #(resolve-binding binding %) call-args)
            call-key [rule-name arg-vals]
            active (::active-rules ctx #{})]
        (if (contains? active call-key)
          []
          (let [ctx (assoc ctx ::active-rules (conj active call-key))]
            (distinct
              (mapcat
                (fn [[head & body]]
                  (let [head-vars (vec (rest head))]
                    (when (some vector? head-vars)
                      (throw
                        (ex-info
                          "Required-bound rule vars ([?a ...] in the head) are not implemented"
                          {:head head})))
                    (when (not= (count head-vars) (count arg-vals))
                      (throw (ex-info "Rule invoked with wrong arity"
                                      {:rule rule-name,
                                       :head head,
                                       :args (vec call-args)})))
                    (let [seed (reduce (fn [b [hv av]]
                                         (if (= FREE av)
                                           b
                                           (and-then b #(unify % hv av))))
                                       (select-keys binding [::dbs '%])
                                       (map vector head-vars arg-vals))]
                      (when seed
                        (keep
                          (fn [res]
                            (reduce
                              (fn [b [arg hv]]
                                (let [v (get res hv FREE)]
                                  (when (= FREE v)
                                    (throw
                                      (ex-info
                                        "Rule head var not bound by rule body"
                                        {:rule rule-name, :var hv})))
                                  (and-then b #(unify % arg v))))
                              binding
                              (map vector call-args head-vars)))
                          (eval-where (vec body) [seed] ctx))))))
                defs))))))))


(defn- eval-clause
  [clause bindings ctx]
  (cond (and (seq? clause) (= 'not (first clause)))
        (eval-not clause bindings ctx)
        (and (seq? clause) (= 'not-join (first clause)))
        (mapcat #(eval-not-join clause % ctx) bindings)
        (seq? clause) (mapcat #(eval-rule clause % ctx) bindings)
        (and (vector? clause) (seq? (first clause)))
        (mapcat #(eval-fn-clause clause % ctx) bindings)
        :else (mapcat #(eval-pattern-clause clause % ctx) bindings)))


(defn- estimate-clause-cost
  [clause binding]
  (let [[db-sym pattern] (clause-db-and-pattern clause)
        idx (resolve-db binding db-sym)]
    (if-not idx
      1000000
      (let [[ce ca cv] (pad-to-5 pattern)
            e-val (resolve-binding binding ce)
            a-val (resolve-binding binding ca)
            v-val (resolve-binding binding cv)]
        (cond (and (not= e-val FREE) (not= a-val FREE) (not= v-val FREE)) 1
              (not= e-val FREE) 2
              (and (not= a-val FREE) (not= v-val FREE)) 4
              (not= a-val FREE) 16
              :else 1024)))))


(defn- plan-where
  [clauses init-bindings]
  (if (or (<= (count clauses) 1) (empty? init-bindings))
    clauses
    (let [binding (first init-bindings)]
      (mapcat (fn [chunk]
                (if (pattern-clause? (first chunk))
                  (mapv second
                        (sort-by (fn [[idx clause]]
                                   [(estimate-clause-cost clause
                                                          binding)
                                    idx])
                                 (map-indexed vector chunk)))
                  chunk))
              (partition-by pattern-clause? clauses)))))


(defn- eval-where
  [clauses init-bindings ctx]
  (reduce (fn [bindings clause] (eval-clause clause bindings ctx))
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
            (if (#{:find :in :with :where} x)
              (recur (inc i)
                     (if cur-key (assoc result cur-key cur-vals) result)
                     x
                     [])
              (recur (inc i) result cur-key (conj cur-vals x)))))))))


(def ^:private aggregate-fns
  {'count count,
   'count-distinct (fn [xs] (count (distinct xs))),
   'sum (fn [xs] (reduce + 0 xs)),
   'min (fn [xs] (reduce min xs)),
   'max (fn [xs] (reduce max xs)),
   'avg (fn [xs] (double (/ (reduce + 0 xs) (count xs))))})


(defn- parse-find-element
  [el]
  (if (seq? el)
    (let [[agg-sym arg] el
          agg-fn (get aggregate-fns agg-sym)]
      (when-not agg-fn
        (throw (ex-info "Unknown aggregate in :find" {:aggregate agg-sym})))
      {:agg agg-fn, :arg arg})
    {:var el}))


(defn- project-find
  "Datomic aggregation pipeline: project each binding to the find ∪ :with ∪
  aggregate-arg vars, dedupe those tuples as a set (bindings carry every
  joined var, whose multiplicity must not leak into aggregates — :with is
  the mechanism for keeping intended duplicates), then group by the
  find vars ONLY (:with vars grant multiplicity within a group, they never
  split groups) and aggregate per group."
  [find with bindings]
  (let [elements (mapv parse-find-element find)]
    (if (not-any? :agg elements)
      (into #{} (map (fn [b] (mapv #(get b %) find))) bindings)
      (let [grouping-vars (filterv some? (mapv :var elements))
            proj-vars (-> grouping-vars
                          (into with)
                          (into (keep :arg elements)))
            rows (into #{} (map #(select-keys % proj-vars)) bindings)
            groups (vals (group-by (fn [row] (mapv #(get row %) grouping-vars))
                                   rows))]
        (into #{}
              (map (fn [group]
                     (mapv (fn [{:keys [var agg arg]}]
                             (if agg
                               (agg (map #(get % arg) group))
                               (get (first group) var)))
                           elements)))
              groups)))))


(defn q
  "Datalog: (q query & inputs) where $ binds to first input.
   Example: (q '[:find ?e :where [?e :name \"Alice\"]] source)
   Options like {:as-of t} can be passed as a final argument if not bound by :in."
  [query & inputs]
  (let [{:keys [find in with where]} (normalize-query query)
        in-patterns (or in '[$])
        [bind-inputs opts] (if (> (count inputs) (count in-patterns))
                             [(take (count in-patterns) inputs) (last inputs)]
                             [inputs nil])
        as-of (:as-of opts)
        init-bindings (build-init-bindings in-patterns bind-inputs as-of)
        ctx {:fns (:fns opts)}
        result (eval-where where init-bindings ctx)]
    (project-find find with result)))


;; =============================================================================
;; Entity Attributes
;; =============================================================================

(defn entity-attrs
  "Convenience: return a map of {attr val} for the given entity in source.
   If multiple datoms exist for an attribute, returns a vector of values."
  ([source eid] (entity-attrs source eid nil))
  ([source eid {:keys [as-of]}]
   (let [datoms (match source [eid '_ '_] {:as-of as-of})]
     (reduce (fn [m d]
               (let [a (nth d 1)
                     v (nth d 2)
                     existing (get m a)]
                 (cond (nil? existing) (assoc m a v)
                       (vector? existing) (update m a conj v)
                       :else (assoc m a [existing v]))))
             {}
             datoms))))
