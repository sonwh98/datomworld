(ns dao.space.pull
  "Declarative entity projection: `(pull source eid pattern)` walks the
  folded index outward from one entity and returns a map shaped by the
  pattern. Third read verb next to `match` (template → datoms) and `q`
  (Datalog → relations): pull is entity → tree.

  Pattern surface:
    [:name :age]               ; attr names
    '[*]                       ; wildcard — every attr of the entity
    [{:friend [:name]}]        ; nested map spec (forward navigation)
    [:_friend]                 ; reverse ref (AVET)
    [{:_friend [:name]}]       ; reverse + nested
    [[:age :default 0]]        ; attr options

  Design rulings (see docs/datomic-pull.md):
  - No schema: every attr is potentially multi-valued. Forward attrs
    follow entity-attrs convention (one datom → scalar, more → vector).
    Reverse attrs (`:_attr`) always return a vector.
  - Ref-ness is asserted by the pattern, not guessed. Nested map spec
    navigates values as entity ids.
  - Missing attrs are omitted, not nil-valued, unless pattern gives :default.
  - `:db/id` is included in every pull result map.
  - Recursion markers deferred (finite patterns bound the walk).
  - Nested map specs ({:friend [...]}) do not support options like :limit or :as (only flat vector elements support options)."
  (:require [dao.space.query :as query]))


(defn- wildcard-symbol?
  "The wildcard marker is the symbol `* (not a keyword)."
  [x]
  (and (symbol? x) (= x '*)))


(defn- parse-attr-options
  "Parse [:attr :default v :limit n :as k] into {:attr :attr :default v ...}."
  [[attr & opts]]
  (when-not (keyword? attr)
    (throw
      (ex-info
        (str "malformed pull pattern: attr options require keyword first, got "
             (pr-str attr))
        {:element [attr opts]})))
  (loop [opts opts
         acc {:attr attr}]
    (if (seq opts) (let [[k v & rest] opts] (recur rest (assoc acc k v))) acc)))


(defn parse-pattern
  "Parse a pull pattern into a normalized spec:
    {:attrs [...] :wildcard? bool :nested {...}}

  attrs: keywords (simple) or maps (with options {:attr :x :default v ...})
  wildcard?: true if pattern contains '*
  nested: {attr subpattern-spec} for map specs

  Throws ex-info on malformed elements."
  [pattern]
  (loop [elems pattern
         acc {:attrs [], :wildcard? false, :nested {}}]
    (if (seq elems)
      (let [e (first elems)
            rest (rest elems)]
        (cond
          ;; wildcard
          (wildcard-symbol? e) (recur rest (assoc acc :wildcard? true))
          ;; simple keyword attr
          (keyword? e) (recur rest (update acc :attrs conj e))
          ;; nested map spec
          (map? e) (let [[attr subpattern] (first e)
                         sub-spec (parse-pattern subpattern)]
                     (recur rest (assoc-in acc [:nested attr] sub-spec)))
          ;; attr with options
          (vector? e) (let [opts (parse-attr-options e)]
                        (recur rest (update acc :attrs conj opts)))
          :else (throw (ex-info (str "malformed pull pattern: " (pr-str e))
                                {:element e}))))
      acc)))


;; ---------------------------------------------------------------------------
;; Increment 2: Flat pull
;; ---------------------------------------------------------------------------

(defn- reverse-attr?
  "Reverse attrs start with :_ (e.g. :_friend)."
  [attr]
  (and (keyword? attr) (= \_ (first (name attr)))))


(defn- forward-attr-name
  "Extract the base attr name from a spec element (keyword or {:attr ...})."
  [spec]
  (if (keyword? spec) spec (:attr spec)))


(defn- apply-options
  "Apply :as rename to the output key."
  [spec key]
  (if-let [as-key (and (map? spec) (:as spec))]
    as-key
    key))


(defn- reverse-to-forward
  "Convert :_friend to :friend."
  [attr]
  (keyword (namespace attr) (subs (name attr) 1)))


(defn- project-reverse-attr
  "Project a reverse attribute: entities whose [e :attr eid] points here.
  Always returns a vector of maps with :db/id (per design ruling)."
  [idx eid spec]
  (let [rev-attr (forward-attr-name spec)
        fwd-attr (reverse-to-forward rev-attr)
        ;; Query AVET: who has [e fwd-attr eid]?
        datoms (query/datoms idx '_ fwd-attr eid)
        eids (mapv #(nth % 0) datoms)
        results (mapv (fn [e] {:db/id e}) eids)]
    (if (seq results)
      [(apply-options spec rev-attr)
       (if (and (map? spec) (:limit spec))
         (take (:limit spec) results)
         results)]
      (when (and (map? spec) (contains? spec :default))
        [(apply-options spec rev-attr) (:default spec)]))))


(defn- project-flat-attr
  "Project one attribute from the datoms. Returns [output-key value] or nil."
  [idx eid spec]
  (let [attr (forward-attr-name spec)]
    (if (reverse-attr? attr)
      (project-reverse-attr idx eid spec)
      (let [datoms (query/datoms idx eid attr '_)
            values (mapv #(nth % 2) datoms)]
        (cond
          ;; no datoms: use :default if present, else omit
          (empty? values) (when (and (map? spec) (contains? spec :default))
                            [(apply-options spec attr) (:default spec)])
          ;; single datom: scalar
          (= 1 (count values)) [(apply-options spec attr) (first values)]
          ;; multiple datoms: vector, apply :limit if present
          :else (let [limit (and (map? spec) (:limit spec))
                      result (if limit (take limit values) values)]
                  [(apply-options spec attr) result]))))))


(defn- project-wildcard
  "Project all attributes of the entity."
  [idx eid]
  (let [datoms (query/datoms idx eid '_ '_)
        grouped (group-by #(nth % 1) datoms)]
    (reduce (fn [m [attr ds]]
              (let [values (mapv #(nth % 2) ds)]
                (assoc m attr (if (= 1 (count values)) (first values) values))))
            {}
            grouped)))


(defn- pull-flat
  "Flat pull: project attrs and wildcard, no nested navigation."
  [idx eid parsed]
  (let [base (if (:wildcard? parsed) (project-wildcard idx eid) {})
        with-attrs (reduce (fn [m spec]
                             (if-let [[k v] (project-flat-attr idx eid spec)]
                               (assoc m k v)
                               m))
                           base
                           (:attrs parsed))]
    (assoc with-attrs :db/id eid)))


(declare pull-full-impl)


(defn- project-nested-attr
  "Project a nested attribute: for each value (entity id), recursively pull."
  [idx eid attr sub-spec]
  (if (reverse-attr? attr)
    ;; Reverse nested: entities pointing here, then pull their attrs
    (let [fwd-attr (reverse-to-forward attr)
          datoms (query/datoms idx '_ fwd-attr eid)
          values (mapv #(nth % 0) datoms)
          results (keep (fn [v]
                          (when (seq (query/datoms idx v '_ '_))
                            (pull-full-impl idx v sub-spec)))
                        values)]
      (when (seq results) [attr results]))
    ;; Forward nested: values are entity ids, pull them
    (let [datoms (query/datoms idx eid attr '_)
          values (mapv #(nth % 2) datoms)
          results (keep (fn [v]
                          (when (seq (query/datoms idx v '_ '_))
                            (pull-full-impl idx v sub-spec)))
                        values)]
      (when (seq results)
        (if (= 1 (count results)) [attr (first results)] [attr results])))))


(declare pull-full)


(defn- pull-full-impl
  "Full pull: flat attrs + nested navigation."
  [idx eid parsed]
  (let [base (pull-flat idx eid parsed)
        with-nested
        (reduce (fn [m [attr sub-spec]]
                  (if-let [[k v] (project-nested-attr idx eid attr sub-spec)]
                    (assoc m k v)
                    m))
                base
                (:nested parsed))]
    with-nested))


(defn- pull-full
  "Full pull with shared index."
  [idx eid pattern]
  (let [parsed (parse-pattern pattern)] (pull-full-impl idx eid parsed)))


;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn pull
  "Declarative entity projection: walk the index from eid and return a
  map shaped by pattern. Returns nil if no datoms exist at eid.

  Options:
    :as-of - temporal bound (t <= as-of)

  Example:
    (pull source 123 [:name :age {:friend [:name]}])"
  ([source eid pattern] (pull source eid pattern nil))
  ([source eid pattern opts]
   (let [idx (query/fold source (:as-of opts))
         ;; check if entity has any datoms
         exists? (seq (query/datoms idx eid '_ '_))]
     (when exists? (pull-full idx eid pattern)))))


(defn pull-many
  "Pull multiple entities with a shared fold."
  ([source eids pattern] (pull-many source eids pattern nil))
  ([source eids pattern opts]
   (let [idx (query/fold source (:as-of opts))
         parsed (parse-pattern pattern)]
     (mapv (fn [eid]
             (when (seq (query/datoms idx eid '_ '_))
               (pull-full-impl idx eid parsed)))
           eids))))
