(ns dao.space.query
  "The reader-side index consumer (docs/design/dao.space.query.md).

   An embeddable Peer over finite relations of tuples: positional `match`,
   Datalog `q` and `pull` above them, with `current` and `history` as the
   two explicit interpreters of canonical d5 datoms. The library is pure and
   stateless: it owns no durable state, never writes, and enforces no
   schema. It never opens and never closes an input — every database input
   is a value the caller constructed:

   - a relation value (`relation`, `entity-map-relation`, `fact-relation`);
   - a datom view over a value (`current`, `history`);
   - an opened published index (`open-published!`, released by the caller
     through `close-published!`).

   Raw vectors and raw maps are not database inputs and throw. A live
   dao.stream handle becomes an input only through `snapshot`, the one
   place this namespace touches v2. Source scope is interpreter context,
   never a tuple slot."
  (:require [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.coordinate :as jing-coordinate]
            [dao.space.index :as index]
            [dao.stream :as stream]
            [dao.stream.observe :as observe]))


;; =============================================================================
;; Canonical d5 interpretation (current / history)
;; =============================================================================

(defn- flatten-datoms
  "Flatten one source's row elements into canonical d5 rows. An element is
   either a canonical d5 vector `[e a v t m]` or an atomic transaction record
   `{:dao.space/transaction {:t n :datoms [...]}}`; the record is flattened
   into its datoms."
  [elements]
  (into
    []
    (mapcat
      (fn [x]
        (cond
          (and (map? x) (contains? x :dao.space/transaction))
          (let [tx (:dao.space/transaction x)
                _ (when-not (and (map? tx)
                                 (= #{:t :datoms} (set (keys tx)))
                                 (integer? (:t tx))
                                 (not (neg? (:t tx)))
                                 (vector? (:datoms tx))
                                 (seq (:datoms tx)))
                    (throw (ex-info "malformed dao.space transaction record"
                                    {:payload x})))
                _ (when-not (every? #(= (:t tx) (index/datom-t %))
                                    (:datoms tx))
                    (throw (ex-info
                             "transaction record datoms carry mismatched t"
                             {:payload x})))]
            (:datoms tx))
          (and (vector? x) (= 5 (count x))) [x]
          :else
          (throw
            (ex-info
              "canonical d5 source element must be a 5-datom vector or a transaction record"
              {:element x})))))
    elements))


(defn- bound-datoms
  "Bound a d5 sequence to t <= as-of when an as-of is given."
  [datoms as-of]
  (if as-of
    (filterv #(<= (index/compare-vals (index/datom-t %) as-of) 0) datoms)
    datoms))


(defn current-state-seq
  "Takes a sequence of canonical d5 datoms and returns the sequence of
   currently-asserted datoms. Facts are keyed by local [e a v] under
   kind-strict content identity (dao.jing.cbor/content-key), so a fact about
   1.0M is never superseded or retracted by one about 1.00M, nor 0.0 by
   -0.0, though host = merges both on the JVM: the greatest t wins within
   each key, and two rows sharing [e a v t] but differing in m are
   conflicting transaction history and are rejected (metadata is not an
   implicit tie-break). Retractions are removed; the result is EAVT-ordered."
  [s]
  (->> (reduce
         (fn [acc d]
           (let [key (cbor/content-key
                       [(index/datom-e d) (index/datom-a d) (index/datom-v d)])]
             (update
               acc
               key
               (fn [winner]
                 (cond (nil? winner) d
                       (< (index/datom-t winner) (index/datom-t d)) d
                       (> (index/datom-t winner) (index/datom-t d)) winner
                       (not= (index/datom-m winner) (index/datom-m d))
                       (throw
                         (ex-info
                           "conflicting d5 rows: same [e a v t], different m"
                           {:a winner, :b d}))
                       :else winner)))))
         {}
         s)
       (vals)
       (remove datom/retracted?)
       (sort index/eavt-cmp)))


(defn- d5->current-facts
  [elements as-of]
  (->> (bound-datoms (flatten-datoms elements) as-of)
       current-state-seq
       (mapv #(subvec (vec %) 0 3))))


(defn- d5->history-rows
  [elements as-of]
  (mapv vec (bound-datoms (flatten-datoms elements) as-of)))


;; =============================================================================
;; Entity-map relation normalization (explicit d3 projection)
;; =============================================================================

(defn- entity-map->d3
  [m]
  (when-not (map? m)
    (throw (ex-info "entity-map-relation requires entity maps" {:entity m})))
  (when-not (contains? m :db/id)
    (throw (ex-info "raw entity-map source requires an explicit :db/id"
                    {:entity m})))
  (let [e (:db/id m)]
    (into [] (keep (fn [[a v]] (when (not= a :db/id) [e a v]))) m)))


(defn- entity-maps->d3
  [maps]
  (into [] (mapcat entity-map->d3) maps))


;; =============================================================================
;; Query values: relation, view, opened published index (Decision 2)
;; =============================================================================

(defn value?
  "True for the query values q/match/pull take as database inputs: a
   relation value, a datom view, or an opened published index. Raw vectors
   and raw maps answer false."
  [x]
  (and (map? x)
       (or (contains? x :dao.space.query/relation)
           (contains? x :dao.space.query/view)
           (contains? x :dao.space.query/published))))


(defn relation
  "An in-memory relation value. Its retained contents are an arbitrary,
   mixed-dimensional tuple collection; arity never selects an
   interpretation. This is the replacement for the direct raw-vector input."
  [tuples]
  {:dao.space.query/relation (vec tuples)})


(defn entity-map-relation
  "An entity-map relation value: a bounded relation of entity maps,
   projected explicitly to [e a v] facts on the read side. The read side is
   pure and never mints an entity id, so every map must carry :db/id."
  [maps]
  ;; Normalize at the explicit constructor boundary. The resulting value is
  ;; the same generic relation value q consumes; no second transport type
  ;; or opening path is needed merely because the input syntax was maps.
  (relation (entity-maps->d3 maps)))


(defn fact-relation
  "The explicit current-fact relation: d3 rows that are already
   current-state facts, so the fact index is built for them without a d5
   pass. dao.space.schema builds these directly."
  [tuples]
  (assoc (relation tuples) :fact? true))


(defn- snapshot-value?
  [x]
  (and (map? x) (contains? x :relation) (contains? x :status)))


(defn- db-source
  "Normalize a current/history source. Query values and snapshots pass
   through (a snapshot contributes its relation); everything else — raw
   maps and vectors included — throws."
  [source]
  (cond
    (value? source) source
    (snapshot-value? source) (:relation source)
    :else
    (throw (ex-info "query db input must be a relation value, a datom view, or an opened published index; raw vectors and maps are rejected"
                    {:input source}))))


(defn- view-value
  [kind source as-of]
  (cond-> {:dao.space.query/view kind, :source source}
    (some? as-of) (assoc :as-of as-of)))


(defn current
  "The explicit current d5 interpreter: a view value over source. Resolves
   the greatest t per [e a v], removes retractions, and rejects conflicting
   [e a v t] rows with differing m. An optional as-of bounds visible datoms
   to t <= as-of."
  ([source] (current source nil))
  ([source as-of]
   (view-value :current (db-source source) as-of)))


(defn history
  "The explicit history d5 interpreter: a view value over source exposing
   the exact logical d5 rows. An optional as-of bounds visible datoms to
   t <= as-of."
  ([source] (history source nil))
  ([source as-of]
   (view-value :history (db-source source) as-of)))


;; =============================================================================
;; Published indexes: open-published! / close-published! (Decision 1)
;; =============================================================================

(defn open-published!
  "Open a published covered-index coordinate — the serializable map
   `index/published-index` builds — as a query value the caller owns: the
   content-store handle it opens is closed by `close-published!`, never by
   a query. Nothing is fetched beyond the manifest; the row vector stays
   deferred behind :rows and the covered sets behind restored B-trees, so a
   `current` view with no as-of can answer selective clauses without a
   full drain."
  [coordinate]
  (when-not (and (map? coordinate)
                 (= :dao.space.index/published (:dao.stream/type coordinate)))
    (throw (ex-info "open-published! takes a published-index coordinate"
                    {:coordinate coordinate})))
  (let [{:keys [content-store manifest-address]} coordinate
        expected (index/published-index content-store manifest-address)]
    (when-not (= expected coordinate)
      (throw (ex-info "invalid published-index coordinate"
                      {:coordinate coordinate, :expected expected})))
    (let [store (jing-coordinate/open! content-store)]
      (try
        (let [manifest (index/read-manifest store manifest-address)]
          {:dao.space.query/published coordinate
           :indexes (index/restored-indexes store manifest)
           :rows (delay (index/read-datoms store manifest-address))
           :store store
           :close-guard (atom false)})
        (catch #?(:cljd Object
                  :clj Throwable
                  :cljs :default)
               error
          (jing/close! store)
          (throw error))))))


(defn close-published!
  "Close the content-store handle an opened published index owns. A second
   close is a no-op here, before the backend is even asked."
  [opened]
  (when-let [guard (:close-guard opened)]
    (when (compare-and-set! guard false true)
      (jing/close! (:store opened))))
  nil)


;; =============================================================================
;; snapshot: the one dao.stream interpreter (Decision 3)
;; =============================================================================

(defn snapshot
  "Turn a dao.stream reader handle into a relation value:

     {:relation <relation value>
      :status   :ended | :blocked | :gap | :defect
      :cursor   c            ; the cursor reached; :gap keeps the last retained
      :recovery c'}          ; :gap only
      :read     raw}         ; :defect only

   Mints at :dao.stream/oldest and loops observe/step with a total effect
   (conj onto the accumulator, answering ok) until the step stops. Every
   stopping outcome is data: :blocked is an open stream caught up (a
   snapshot at call time), :gap carries the values read before the hole and
   the recovery cursor, :defect the raw answer. The caller decides whether a
   partial snapshot is a usable relation. The handle is never closed, and a
   cursor never advances past a value the accumulator did not retain (the
   step's effect-before-commit)."
  [handle]
  (let [mint (stream/cursor handle :dao.stream/oldest)]
    (if-not (= :dao.stream/ok (:dao.stream/outcome mint))
      {:relation (relation [])
       :status :defect
       :cursor nil
       :read mint}
      (loop [cursor (:dao.stream/cursor mint), values []]
        (let [acc (volatile! values)
              step (observe/step handle cursor
                                 (fn [value]
                                   (vswap! acc conj value)
                                   {:dao.stream/outcome :dao.stream/ok}))]
          (case (:status step)
            :advance (recur (:cursor step) @acc)
            :retry {:relation (relation @acc)
                    :status :blocked
                    :cursor cursor}
            :ended {:relation (relation @acc)
                    :status :ended
                    :cursor cursor}
            :gap {:relation (relation @acc)
                  :status :gap
                  :cursor cursor
                  :recovery (:recovery step)}
            ;; :defect, and :failed (impossible for this total effect,
            ;; classified the same way if a composition ever produces it)
            {:relation (relation @acc)
             :status :defect
             :cursor cursor
             :read (:read step)}))))))


;; =============================================================================
;; Realize a db-value: value tag -> {::relation ::fact-index ::indexes}
;; =============================================================================

(defn- relation->fact-index
  [relation]
  (index/index-datoms (mapv (fn [tuple]
                              [(nth tuple 0) (nth tuple 1)
                               (nth tuple 2) 0 datom/default-op])
                            relation)))


(defn- force-relation
  "Force a relation if it is delayed (e.g. from a lazy covered-index
   realization) or return it directly. Memoization belongs to the delay:
   forcing is therefore at most one walk, but this function itself neither
   creates nor guarantees non-nilness."
  [relation]
  (if (delay? relation) @relation relation))


(declare realize-db-value!)


(defn- realize-datom-view
  "Interpret a current/history view inside the query layer. The nested
   source is another query value; a current view with no as-of over an
   opened published index routes through the restored covered sets, every
   other combination resolves the source's rows."
  [d]
  (let [kind (:dao.space.query/view d)
        as-of (:as-of d)
        {src-relation ::relation, src-indexes ::indexes}
        (realize-db-value! (:source d))]
    (if (and (= :current kind) (nil? as-of) src-indexes)
      {::relation (delay (d5->current-facts (force-relation src-relation) nil)),
       ::fact-index src-indexes}
      (let [forced-rel (force-relation src-relation)
            rows (case kind
                   :current (d5->current-facts forced-rel as-of)
                   :history (d5->history-rows forced-rel as-of))]
        {::relation rows,
         ::fact-index (when (= :current kind)
                        (relation->fact-index rows))}))))


(defn ^:no-doc realize-db-value!
  "Turn one db-value into an immutable tuple relation plus, for current
   fact views, an in-memory fact index. Opens nothing, closes nothing."
  [db-value]
  (when-not (map? db-value)
    (throw
      (ex-info
        "query db input must be a relation value, a datom view, or an opened published index; raw vectors and maps are rejected"
        {:input db-value})))
  (cond
    (contains? db-value :dao.space.query/relation)
    (let [tuples (:dao.space.query/relation db-value)]
      {::relation tuples,
       ::fact-index (when (:fact? db-value) (relation->fact-index tuples))})

    (contains? db-value :dao.space.query/published)
    (let [indexes (index/covered-indexes db-value)]
      {::relation (:rows db-value),
       ::indexes indexes,
       ::fact-index nil})

    (contains? db-value :dao.space.query/view)
    (realize-datom-view db-value)

    :else
    (throw
      (ex-info
        "query db input must be a relation value, a datom view, or an opened published index; raw vectors and maps are rejected"
        {:input db-value}))))


;; =============================================================================
;; match: Linda-style positional template (an ergonomic materializer)
;; =============================================================================

(def ^:private FREE ::free)


(defn- wildcard?
  [x]
  (or (= x '_) (nil? x) (= x FREE)))


(defn- query-var-symbol?
  [x]
  (and (symbol? x) (= \? (first (name x)))))


(defn- parse-tuple-pattern
  "Parse exact positional syntax plus an optional final `& tail`."
  [pattern]
  (let [pattern (vec pattern)
        amps (vec (keep-indexed #(when (= '& %2) %1) pattern))]
    (cond
      (empty? amps) {:fixed pattern, :rest nil}
      (or (not= 1 (count amps)) (not= (first amps) (- (count pattern) 2)))
      (throw
        (ex-info
          "Malformed tuple rest pattern: & must precede one final tail form"
          {:pattern pattern}))
      :else (let [tail (peek pattern)]
              (when-not (or (= tail '_) (query-var-symbol? tail))
                (throw (ex-info
                         "Tuple rest pattern tail must be _ or a query variable"
                         {:pattern pattern, :tail tail})))
              {:fixed (subvec pattern 0 (first amps)), :rest tail}))))


(defn- tuple-shape-matches?
  [{:keys [fixed rest]} tuple]
  (if rest (<= (count fixed) (count tuple)) (= (count fixed) (count tuple))))


(defn- slots-match?
  [parsed tuple]
  (and (tuple-shape-matches? parsed tuple)
       (every? (fn [[expected actual]]
                 (or (wildcard? expected) (cbor/content= expected actual)))
               (map vector (:fixed parsed) tuple))))


(defn- same-slot?
  "True while a range scan is still inside the slot value's index range:
   compare-vals ties, not host =. The index orders by value, so 1 and 1.0
   (or 1.0M and 1.00M) tie and interleave by the later slots; a scan that
   stopped at the first kind-distinct row would miss the rows after it.
   Kind-strict matching is the caller's filter, applied after the scan."
  [expected actual]
  (zero? (index/compare-vals expected actual)))


(defn- range-scannable?
  "True for a bound slot value whose index range is exactly its kind-loose
   equals: the scalar buckets of dao.space.index/compare-vals. A collection
   or other value orders by host compare with a string fallback, which need
   not agree with content= (a vector holding 1 and one holding the big
   integer 1 are content= but print differently), so its matches are found
   by scanning the other slots' range instead."
  [x]
  (or (nil? x) (boolean? x) (cbor/numeric? x) (string? x) (keyword? x) (symbol? x)))


(defn- select-by-index
  "The current-state datoms whose bound slots are content= the pattern's.
   The index range of the bound scalar slots is scanned by index order
   (numerically equal values of different kinds included), then every bound
   slot is matched kind-strictly."
  [idx e a v]
  (let [e? (and (not (wildcard? e)) (range-scannable? e))
        a? (and (not (wildcard? a)) (range-scannable? a))
        v? (and (not (wildcard? v)) (range-scannable? v))
        candidates (cond e?
                         (take-while #(same-slot? e (index/datom-e %))
                                     (index/subseq-from (:eavt idx)
                                                        index/eavt-cmp
                                                        [e nil nil nil nil]))
                         (and a? v?)
                         (take-while #(and (same-slot? a (index/datom-a %))
                                           (same-slot? v (index/datom-v %)))
                                     (index/subseq-from (:avet idx)
                                                        index/avet-cmp
                                                        [nil a v nil nil]))
                         v?
                         (take-while #(same-slot? v (index/datom-v %))
                                     (index/subseq-from (:vaet idx)
                                                        index/vaet-cmp
                                                        [nil nil v nil nil]))
                         a?
                         (take-while #(same-slot? a (index/datom-a %))
                                     (index/subseq-from (:aevt idx)
                                                        index/aevt-cmp
                                                        [nil a nil nil nil]))
                         :else (seq (:eavt idx)))]
    (current-state-seq
      (filter (fn [d]
                (and (or (wildcard? e) (cbor/content= e (index/datom-e d)))
                     (or (wildcard? a) (cbor/content= a (index/datom-a d)))
                     (or (wildcard? v) (cbor/content= v (index/datom-v d)))))
              candidates))))


(defn datoms
  "Index-routed datom selector: returns current-state datoms matching
   the [e a v] pattern. Wildcards are `_`, nil, or FREE. Routes through
   EAVT (e bound), AVET (a+v bound), VAET (v bound), or AEVT (a bound).
   The index must be supplied by the explicit datom interpreter. The peer of
   DataScript's `datoms` API; the Pull section below is its main internal
   consumer, but it's public for any other idx-level caller."
  [idx e a v]
  (let [candidates (select-by-index idx e a v)]
    (filter (fn [d]
              (and (or (wildcard? e) (cbor/content= e (index/datom-e d)))
                   (or (wildcard? a) (cbor/content= a (index/datom-a d)))
                   (or (wildcard? v) (cbor/content= v (index/datom-v d)))))
            candidates)))


(defn rows
  "The resolved row vector of a view or relation value — what a caller
   that used to drain a view reaches for now. Forcing a view over an opened
   published index drains it (L2); a relation value returns its tuples."
  [x]
  (cond
    (contains? x :dao.space.query/relation)
    (vec (:dao.space.query/relation x))

    (contains? x :dao.space.query/view)
    (let [kind (:dao.space.query/view x)
          as-of (:as-of x)
          src-rows (rows (:source x))]
      (case kind
        :current (d5->current-facts src-rows as-of)
        :history (d5->history-rows src-rows as-of)))

    (contains? x :dao.space.query/published)
    (force (:rows x))

    :else
    (throw (ex-info "rows takes a relation value, a datom view, or an opened published index"
                    {:input x}))))


(defn match
  "Exact-arity positional matching over a logical source. An explicit final
   `& _` ignores a tail. Accepts a query value (relation value, datom view,
   opened published index) and returns the matched logical tuples."
  [source pattern]
  (let [{::keys [relation]} (realize-db-value! source)
        parsed (parse-tuple-pattern pattern)
        rel (force-relation relation)]
    (vec (filter #(slots-match? parsed %) rel))))


;; =============================================================================
;; Pull: declarative entity projection (entity -> tree)
;; =============================================================================
;; Third read verb next to match (template -> datoms) and q (Datalog ->
;; relations). Design rulings are spelled out in the Pull section of
;; docs/design/dao.space.query.md:
;;   - No schema: every attr is potentially multi-valued. Forward attrs
;;     follow the entity-attrs convention (one datom -> scalar, more ->
;;     vector). Reverse attrs (`:_attr`) always return a vector.
;;   - Ref-ness is asserted by the pattern, not guessed. A nested map spec
;;     navigates values as entity ids.
;;   - Missing attrs are omitted, not nil-valued, unless the pattern gives
;;     :default. `:db/id` is included in every pull result map.
;;   - Recursion markers (`'...` / depth limits) are deferred: finite
;;     patterns bound the walk by construction.
;;   - Nested map specs ({:friend [...]}) do not support :limit/:as
;;     (only flat vector elements do).

(defn- wildcard-symbol?
  [x]
  (and (symbol? x) (= x '*)))


(defn- parse-attr-options
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
    {:attrs [...] :wildcard? bool :nested {...}}"
  [pattern]
  (loop [elems pattern
         acc {:attrs [], :wildcard? false, :nested {}}]
    (if (seq elems)
      (let [e (first elems)
            rest (rest elems)]
        (cond (wildcard-symbol? e) (recur rest (assoc acc :wildcard? true))
              (keyword? e) (recur rest (update acc :attrs conj e))
              (map? e) (let [[attr subpattern] (first e)
                             sub-spec (parse-pattern subpattern)]
                         (recur rest (assoc-in acc [:nested attr] sub-spec)))
              (vector? e) (let [opts (parse-attr-options e)]
                            (recur rest (update acc :attrs conj opts)))
              :else (throw (ex-info (str "malformed pull pattern: " (pr-str e))
                                    {:element e}))))
      acc)))


(defn- reverse-attr?
  [attr]
  (and (keyword? attr) (= \_ (first (name attr)))))


(defn- forward-attr-name
  [spec]
  (if (keyword? spec) spec (:attr spec)))


(defn- apply-options
  [spec key]
  (if-let [as-key (and (map? spec) (:as spec))]
    as-key
    key))


(defn- reverse-to-forward
  [attr]
  (keyword (namespace attr) (subs (name attr) 1)))


(defn- project-reverse-attr
  [idx eid spec]
  (let [rev-attr (forward-attr-name spec)
        fwd-attr (reverse-to-forward rev-attr)
        matching (datoms idx '_ fwd-attr eid)
        eids (mapv #(nth % 0) matching)
        results (mapv (fn [e] {:db/id e}) eids)]
    (if (seq results)
      [(apply-options spec rev-attr)
       (if (and (map? spec) (:limit spec))
         (take (:limit spec) results)
         results)]
      (when (and (map? spec) (contains? spec :default))
        [(apply-options spec rev-attr) (:default spec)]))))


(defn- project-flat-attr
  [idx eid spec]
  (let [attr (forward-attr-name spec)]
    (if (reverse-attr? attr)
      (project-reverse-attr idx eid spec)
      (let [matching (datoms idx eid attr '_)
            values (mapv #(nth % 2) matching)]
        (cond (empty? values) (when (and (map? spec) (contains? spec :default))
                                [(apply-options spec attr) (:default spec)])
              (= 1 (count values)) [(apply-options spec attr) (first values)]
              :else (let [limit (and (map? spec) (:limit spec))
                          result (if limit (take limit values) values)]
                      [(apply-options spec attr) result]))))))


(defn- project-wildcard
  [idx eid]
  (let [matching (datoms idx eid '_ '_)
        grouped (group-by #(nth % 1) matching)]
    (reduce (fn [m [attr ds]]
              (let [values (mapv #(nth % 2) ds)]
                (assoc m attr (if (= 1 (count values)) (first values) values))))
            {}
            grouped)))


(defn- pull-flat
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
  [idx eid attr sub-spec]
  (if (reverse-attr? attr)
    (let [fwd-attr (reverse-to-forward attr)
          matching (datoms idx '_ fwd-attr eid)
          values (mapv #(nth % 0) matching)
          results (keep (fn [v]
                          (when (seq (datoms idx v '_ '_))
                            (pull-full-impl idx v sub-spec)))
                        values)]
      (when (seq results) [attr results]))
    (let [matching (datoms idx eid attr '_)
          values (mapv #(nth % 2) matching)
          results (keep (fn [v]
                          (when (seq (datoms idx v '_ '_))
                            (pull-full-impl idx v sub-spec)))
                        values)]
      (when (seq results)
        (if (= 1 (count results)) [attr (first results)] [attr results])))))


(defn- pull-full-impl
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
  [idx eid pattern]
  (let [parsed (parse-pattern pattern)] (pull-full-impl idx eid parsed)))


(defn- pull-idx
  "idx-level pull: the fact index has already been built. {:db/id eid} (only)
   if eid has no datoms — matching Datomic: entity ids are not
   existence-checked, so pull never returns nil at the top level, it
   always echoes back :db/id."
  [idx eid pattern]
  (if (seq (datoms idx eid '_ '_)) (pull-full idx eid pattern) {:db/id eid}))


(defn pull
  "Declarative entity projection: walk the index from eid and return a map
   shaped by pattern. {:db/id eid} if no datoms exist at eid. Accepts a
   current fact view over a query value.

   Example:
     (pull (current source) 123 [:name :age {:friend [:name]}])"
  [source eid pattern]
  (let [{::keys [fact-index]} (realize-db-value! source)]
    (when-not fact-index
      (throw (ex-info "pull requires a current fact-shaped source view"
                      {:source source})))
    (pull-idx fact-index eid pattern)))


(defn pull-many
  "Pull multiple entities with one shared fact index."
  [source eids pattern]
  (let [{::keys [fact-index]} (realize-db-value! source)]
    (when-not fact-index
      (throw (ex-info
               "pull-many requires a current fact-shaped source view"
               {:source source})))
    (let [parsed (parse-pattern pattern)]
      (mapv (fn [eid]
              (if (seq (datoms fact-index eid '_ '_))
                (pull-full-impl fact-index eid parsed)
                {:db/id eid}))
            eids))))


;; =============================================================================
;; q: Datalog (:find / :in / :where over bounded db streams)
;; =============================================================================

(defn- type-label
  [x]
  #?(:cljd (str (.-runtimeType x))
     :default (str (type x))))


(defn- content-eq
  "The query `=`: kind-strict content equality (dao.jing.cbor/content=),
   variadic like clojure.core/=. (= 1 1.0), (= 0.0 -0.0) and
   (= 1.0M 1.00M) are all false."
  ([_] true)
  ([a b] (cbor/content= a b))
  ([a b & more] (and (cbor/content= a b) (every? #(cbor/content= a %) more))))


(defn- content-not-eq
  [& args]
  (not (apply content-eq args)))


(defn- require-numeric!
  [op x]
  (when-not (cbor/numeric? x)
    (throw (ex-info (str "query builtin " op " requires numeric operands")
                    {:fn op, :operand x, :type (type-label x)})))
  x)


(defn- numeric-chain
  "A variadic ordering builtin over dao.jing.cbor/num-compare: exact across
   every numeric kind and carrier, in either operand order. A non-numeric
   operand is an explicit refusal, never a coercion."
  [op ok?]
  (fn [x & more]
    (require-numeric! op x)
    (loop [a x
           [b & bs :as rest-args] more]
      (if (empty? rest-args)
        true
        (do (require-numeric! op b)
            (if (ok? (cbor/num-compare a b))
              (recur b bs)
              ;; keep validating the rest, then answer false
              (do (run! #(require-numeric! op %) bs) false)))))))


(defn- preferred-tie
  "The owner's min/max tie-break for two numerically equal operands
   (docs/design/dao.jing.cbor.md, Numeric identity): content-identical
   operands are the same value; otherwise (1) an exact operand (integer,
   decimal, rational) beats a float64 one, (2) else the shorter canonical
   CBOR encoding wins, (3) else the first by canonical bytes. Encoding is
   paid only here, after numeric order has tied, so an ordinary min/max
   fold never encodes."
  [a b]
  (if (cbor/content= a b)
    a
    (let [fa (cbor/float64? a)
          fb (cbor/float64? b)]
      (cond
        (and fb (not fa)) a
        (and fa (not fb)) b
        :else (if (pos? (cbor/encoded-compare a b)) b a)))))


(defn- portable-min
  "min over two numbers: the lesser by portable numeric order, the tie-break
   on a numeric tie. Independent of argument order and host."
  [a b]
  (let [c (cbor/num-compare (require-numeric! 'min a) (require-numeric! 'min b))]
    (cond (neg? c) a
          (pos? c) b
          :else (preferred-tie a b))))


(defn- portable-max
  [a b]
  (let [c (cbor/num-compare (require-numeric! 'max a) (require-numeric! 'max b))]
    (cond (pos? c) a
          (neg? c) b
          :else (preferred-tie a b))))


(defn- variadic
  "The variadic builtin of a binary fold. The aggregate reducers use the
   same binary fold, so the builtin and the aggregate always agree."
  [f]
  (fn [x & more] (reduce f x more)))


(defn- host-arithmetic
  "A host-native arithmetic builtin that refuses numeric carriers loudly:
   a value dao.jing.cbor treats as a number but the host does not (the JVM
   Rational carrier; the JavaScript and Dart float64, decimal and rational
   carriers and big integers) would otherwise fail with an opaque host
   error or, on JavaScript, silently concatenate strings."
  [op f]
  (fn [& args]
    (doseq [x args]
      (when (and (cbor/numeric? x) (not (number? x)))
        (throw (ex-info (str "query arithmetic builtin " op
                             " does not accept a numeric carrier")
                        {:fn op, :operand x, :type (type-label x)}))))
    (apply f args)))


(def ^:private builtins
  {'= content-eq,
   'not= content-not-eq,
   '< (numeric-chain '< neg?),
   '> (numeric-chain '> pos?),
   '<= (numeric-chain '<= (complement pos?)),
   '>= (numeric-chain '>= (complement neg?)),
   '+ (host-arithmetic '+ +),
   '- (host-arithmetic '- -),
   '* (host-arithmetic '* *),
   '/ (host-arithmetic '/ /),
   'quot (host-arithmetic 'quot quot),
   'rem (host-arithmetic 'rem rem),
   'mod (host-arithmetic 'mod mod),
   'inc (host-arithmetic 'inc inc),
   'dec (host-arithmetic 'dec dec),
   'min (variadic portable-min),
   'max (variadic portable-max),
   'abs (host-arithmetic 'abs abs),
   'str str,
   'subs subs,
   'count count,
   'first first,
   'last last,
   'get get,
   'nth nth,
   'identity identity,
   'vector vector,
   'tuple vector,
   'untuple identity,
   'ground identity})


(declare eval-where)


(defn- resolve-binding
  [binding sym]
  (if (symbol? sym) (get binding sym FREE) sym))


(defn- binding-key
  "The kind-strict identity of one query element: a binding map compares
   its variables' values by dao.jing.cbor/content-key and keeps the ::dbs
   and rule-set entries as themselves (they are the query's inputs, not
   values, and content-key would walk every database in them); anything
   else, a projected tuple or a bare value, is its content-key."
  [x]
  (if (map? x)
    (into {}
          (map (fn [[k v]] [k (if (or (= k ::dbs) (= k '%)) v (cbor/content-key v))]))
          x)
    (cbor/content-key x)))


(defn- content-distinct
  "Like clojure.core/distinct, lazy, first occurrence kept, order
   preserved, but two elements are duplicates only when binding-key gives
   them the same key: never merely because host = merges 1.0M with 1.00M
   or 0.0 with -0.0."
  [coll]
  (let [step (fn step
               [xs seen]
               (lazy-seq
                 ((fn [xs seen]
                    (when-let [s (seq xs)]
                      (let [x (first s)
                            k (binding-key x)]
                        (if (contains? seen k)
                          (recur (rest s) seen)
                          (cons x (step (rest s) (conj seen k)))))))
                  xs
                  seen)))]
    (step coll #{})))


(defn- unify
  "Bind sym to val, or check a constant or an already-bound variable against
   it kind-strictly (dao.jing.cbor/content=): per the owner ruling in
   docs/design/dao.jing.cbor.md (Numeric identity) a value unifies only with
   a value of the same numeric kind, scale and zero sign, as content
   addressing identifies it."
  [binding sym val]
  (cond (or (= sym FREE) (= sym '_)) binding
        (not (symbol? sym)) (when (cbor/content= sym val) binding)
        (contains? binding sym) (when (cbor/content= (get binding sym) val) binding)
        :else (assoc binding sym val)))


(defn- unify-slots
  [binding {:keys [fixed rest], :as parsed} tuple]
  (when (tuple-shape-matches? parsed tuple)
    (let [b (reduce (fn [b [term value]]
                      (or (unify b term value) (reduced nil)))
                    binding
                    (map vector fixed tuple))]
      (when b
        (if (and rest (not= rest '_))
          (unify b rest (subvec (vec tuple) (count fixed)))
          b)))))


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
  [pattern value dbs]
  (case (classify-in-pattern pattern)
    :db [{::dbs {pattern (get dbs pattern)}}]
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
  [in-patterns inputs dbs]
  (reduce (fn [acc [pat val]]
            (cross-join-bindings acc (expand-in-binding pat val dbs)))
          [{}]
          (map vector in-patterns inputs)))


(defn- pattern-clause?
  [clause]
  (not (or (seq? clause) (and (vector? clause) (seq? (first clause))))))


(defn- clause-db-and-pattern
  [clause]
  (if (db-sym? (first clause))
    [(first clause) (vec (rest clause))]
    ['$ (vec clause)]))


(defn- resolve-db
  [binding db-sym]
  (get (get binding ::dbs) db-sym))


(defn- resolve-relation
  [binding db-sym]
  (force-relation (::relation (resolve-db binding db-sym))))


(defn- resolve-fact-index
  [binding db-sym]
  (::fact-index (resolve-db binding db-sym)))


(defn- eval-pattern-clause
  [clause binding _ctx]
  (let [[db-sym pattern] (clause-db-and-pattern clause)
        fact-index (resolve-fact-index binding db-sym)
        parsed (parse-tuple-pattern pattern)]
    (if (and fact-index (nil? (:rest parsed)) (= 3 (count (:fixed parsed))))
      (let [[e a v] (mapv #(resolve-binding binding %) (:fixed parsed))]
        (keep #(unify-slots binding
                            parsed
                            [(index/datom-e %) (index/datom-a %)
                             (index/datom-v %)])
              (select-datoms fact-index e a v)))
      (let [relation (resolve-relation binding db-sym)]
        (keep #(unify-slots binding parsed %) relation)))))


(defn- query-var?
  [x]
  (and (symbol? x) (= \? (first (name x)))))


(defn- not-required-vars
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


(defn- branch-clauses
  [branch]
  (if (and (seq? branch) (= 'and (first branch))) (rest branch) [branch]))


(defn- branch-free-vars
  [clauses]
  (distinct (mapcat
              (fn [clause]
                (cond (and (seq? clause) (#{'not 'not-join} (first clause))) nil
                      :else (filter query-var? (tree-seq coll? seq clause))))
              clauses)))


(defn- check-same-var-rule
  [branches]
  (let [var-sets (map (comp set branch-free-vars branch-clauses) branches)]
    (when-not (apply = var-sets)
      (throw (ex-info "All branches of or must bind the same set of variables"
                      {:branches (vec branches),
                       :branch-vars (vec var-sets)})))))


(defn- eval-or
  [clause bindings ctx]
  (let [branches (rest clause)]
    (check-same-var-rule branches)
    (content-distinct (mapcat (fn [branch]
                                (eval-where (branch-clauses branch) bindings ctx))
                              branches))))


(defn- eval-or-join
  [clause bindings ctx]
  (let [join-vars (nth clause 1)
        branches (drop 2 clause)
        _ (doseq [branch branches]
            (let [branch-vars (set (branch-free-vars (branch-clauses branch)))
                  missing (seq (remove branch-vars join-vars))]
              (when missing
                (throw (ex-info (str "or-join branch did not bind join var(s): "
                                     (vec missing))
                                {:unbound (vec missing),
                                 :join-vars join-vars,
                                 :branch branch})))))
        seed-for (fn [b]
                   (into (select-keys b [::dbs '%])
                         (keep (fn [v]
                                 (let [v' (get b v FREE)]
                                   (when (not= v' FREE) [v v']))))
                         join-vars))
        augment (fn [b branch-result]
                  (reduce (fn [acc k] (unify acc k (get branch-result k FREE)))
                          b
                          join-vars))]
    (mapcat (fn [b]
              (let [seed (seed-for b)]
                (->> branches
                     (mapcat #(eval-where (branch-clauses %) [seed] ctx))
                     (map #(augment b %))
                     (remove nil?)
                     content-distinct)))
            bindings)))


(defn- eval-and
  [clause bindings ctx]
  (eval-where (rest clause) bindings ctx))


(defn- eval-not-join
  [clause binding ctx]
  (let [req-vars (nth clause 1)
        inner-clauses (drop 2 clause)]
    (doseq [v req-vars]
      (when (= FREE (resolve-binding binding v))
        (throw (ex-info "not-join variables must be bound"
                        {:var v, :binding binding}))))
    (let [inner-binding (into (select-keys binding [::dbs '%])
                              (map (fn [v] [v (get binding v)]))
                              req-vars)]
      (if (seq (eval-where inner-clauses [inner-binding] ctx)) [] [binding]))))


(defn- select-probe
  [idx e a v]
  (when idx
    (let [matches (filter (fn [d]
                            (and (or (wildcard? a) (cbor/content= a (index/datom-a d)))
                                 (or (wildcard? v) (cbor/content= v (index/datom-v d)))))
                          (select-datoms idx e '_ '_))]
      (if (seq matches)
        (let [vs (mapv #(nth % 2) matches)] {:v vs, :missing false})
        {:missing true}))))


(defn- resolve-special-arg
  [binding a]
  (let [v (resolve-binding binding a)]
    (when (= v FREE)
      (throw (ex-info "Unbound variable in special-form clause"
                      {:var a, :binding binding})))
    v))


(defn- eval-special-fn
  [fsym args binding]
  (case fsym
    get-else (let [[_src e a default] args
                   idx (resolve-fact-index binding _src)
                   _ (when-not idx
                       (throw
                         (ex-info
                           "get-else requires a current fact-shaped source view"
                           {:source _src})))
                   e' (resolve-special-arg binding e)
                   a' (resolve-special-arg binding a)
                   d' (resolve-special-arg binding default)
                   probe (select-probe idx e' a' '_)
                   vs (:v probe)]
               {:ret (if (seq vs) (first vs) d')})
    missing? (let [[_src e a] args
                   idx (resolve-fact-index binding _src)
                   _ (when-not idx
                       (throw
                         (ex-info
                           "missing? requires a current fact-shaped source view"
                           {:source _src})))
                   e' (resolve-special-arg binding e)
                   a' (resolve-special-arg binding a)
                   probe (select-probe idx e' a' '_)]
               {:ret (boolean (:missing probe))})
    nil))


(defn- eval-fn-clause
  [clause binding ctx]
  (let [[fsym-and-args & result-vars] clause
        fsym (first fsym-and-args)
        args (rest fsym-and-args)
        special (eval-special-fn fsym args binding)]
    (if special
      (let [{:keys [ret]} special]
        (when (> (count result-vars) 1)
          (throw
            (ex-info
              "Function clause takes one binding form; use a tuple [?a ?b] for multi-return"
              {:clause clause})))
        (if (empty? result-vars)
          (if ret [binding] [])
          (let [out (first result-vars)]
            (when (vector? out)
              (throw (ex-info
                       "get-else / missing? take a scalar binding, not a tuple"
                       {:binding-form out, :clause clause})))
            (let [b (unify binding out ret)] (if b [b] [])))))
      (let [f (get-in ctx [:fns fsym])]
        (when-not f
          (throw (ex-info "Unknown query fn — pass it via the :fns option"
                          {:fn fsym, :clause clause})))
        (let [arg-vals (mapv (fn [a]
                               (let [v (resolve-binding binding a)]
                                 (when (= FREE v)
                                   (throw (ex-info
                                            "Unbound variable in fn clause"
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
                (if b [b] [])))))))))


(defn- eval-rule
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
            ;; keyed by content, so a call on 1.00M is not taken for a
            ;; repeat of the call on 1.0M
            call-key [rule-name (binding-key arg-vals)]
            active (::active-rules ctx #{})]
        (if (contains? active call-key)
          []
          (let [ctx (assoc ctx ::active-rules (conj active call-key))]
            (content-distinct
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
  (cond (and (seq? clause) (= 'or (first clause))) (eval-or clause bindings ctx)
        (and (seq? clause) (= 'or-join (first clause)))
        (eval-or-join clause bindings ctx)
        (and (seq? clause) (= 'and (first clause)))
        (eval-and clause bindings ctx)
        (and (seq? clause) (= 'not (first clause)))
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
        ;; Truthiness only, never forced: planning must not drain a
        ;; deferred (lazy published) relation. A delay is always truthy;
        ;; forcing here would fully materialize the source for every
        ;; multi-clause query before the first clause runs.
        relation (::relation (resolve-db binding db-sym))]
    (if-not relation
      1000000
      (let [{:keys [fixed]} (parse-tuple-pattern pattern)
            bound-count (count (remove #(= FREE (resolve-binding binding %))
                                       fixed))]
        (- 1024 bound-count)))))


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
            (if (#{:find :in :with :where :keys :syms :strs} x)
              (recur (inc i)
                     (if cur-key (assoc result cur-key cur-vals) result)
                     x
                     [])
              (recur (inc i) result cur-key (conj cur-vals x)))))))))


(def ^:private aggregate-fns
  {'count count,
   'count-distinct (fn [xs] (count (content-distinct xs))),
   'sum (fn [xs] (reduce (host-arithmetic 'sum +) 0 xs)),
   'min (fn [xs] (reduce portable-min xs)),
   'max (fn [xs] (reduce portable-max xs)),
   'avg (fn [xs] (double (/ (reduce (host-arithmetic 'avg +) 0 xs) (count xs))))})


(defn- parse-find-element
  [el]
  (cond (and (seq? el) (= 'pull (first el)))
        (let [[_ pull-var pull-pattern] el]
          (when-not (symbol? pull-var)
            (throw (ex-info "pull find element requires a variable"
                            {:element el})))
          {:pull-var pull-var, :pull-pattern pull-pattern})
        (seq? el) (let [[agg-sym arg] el
                        agg-fn (get aggregate-fns agg-sym)]
                    (when-not agg-fn
                      (throw (ex-info "Unknown aggregate in :find"
                                      {:aggregate agg-sym})))
                    (when (and (seq? arg) (= 'pull (first arg)))
                      (throw (ex-info
                               "pull cannot be used as an aggregate argument"
                               {:element el})))
                    {:agg agg-fn, :arg arg})
        :else {:var el}))


(def ^:private find-scalar-marker (symbol "."))


(defn- parse-find
  [find]
  (let [scalar? (and (some #(= find-scalar-marker %) find)
                     (not (vector? (first find))))
        coll? (and (vector? (first find)) (some #(= '... %) (first find)))
        tuple? (and (vector? (first find)) (not coll?))
        spec (cond scalar? :scalar
                   coll? :coll
                   tuple? :tuple
                   :else :relation)
        vars (cond scalar? (vec (remove #(= find-scalar-marker %) find))
                   coll? (vec (remove #(= '... %) (first find)))
                   tuple? (vec (first find))
                   :else find)]
    {:find-vars vars, :spec spec}))


(defn- check-return-map-arity
  [find-vars spec rm-key rm-keys]
  (when (not= :relation spec)
    (throw (ex-info
             "Return map form (:keys/:syms/:strs) requires a relation find"
             {:spec spec, :rm-key rm-key})))
  (when (not= (count find-vars) (count rm-keys))
    (throw (ex-info "Return map arity must match find vars"
                    {:find-vars find-vars, :rm-keys rm-keys}))))


(defn- project-element
  [element b]
  (if (:pull-var element)
    (let [eid (get b (:pull-var element))
          idx (resolve-fact-index b '$)]
      (when-not idx
        (throw (ex-info "pull find element requires a bound $ source"
                        {:element element})))
      (pull-idx idx eid (:pull-pattern element)))
    (get b (:var element))))


(defn- relation-result
  "The q result: a host set of find tuples. Rows, groups and tuples are
   deduplicated kind-strictly (content-distinct, content-key) before the
   set is built. The set itself still compares members with host =, so on
   a host where two content-distinct tuples are host = and hash alike (the
   JVM for 1.0M/1.00M and 0.0/-0.0, Dart for 0.0/-0.0) they remain one
   member of the returned set."
  [find with bindings]
  (let [elements (mapv parse-find-element find)]
    (if (not-any? :agg elements)
      (into #{}
            (content-distinct (map (fn [b] (mapv #(project-element % b) elements))
                                   bindings)))
      (let [grouping-vars (filterv some?
                                   (mapv #(or (:var %) (:pull-var %)) elements))
            ;; proj-vars are query variable symbols, which host distinct
            ;; already compares correctly
            proj-vars (-> grouping-vars
                          (into with)
                          (into (keep :arg elements))
                          distinct)
            rows (content-distinct
                   (map #(select-keys % (conj (vec proj-vars) ::dbs)) bindings))
            groups (vals (group-by (fn [row]
                                     (cbor/content-key (mapv #(get row %) grouping-vars)))
                                   rows))]
        (into #{}
              (content-distinct
                (map (fn [group]
                       (mapv (fn [element]
                               (let [{:keys [agg arg]} element]
                                 (if agg
                                   (agg (map #(get % arg) group))
                                   (project-element element (first group)))))
                             elements))
                     groups)))))))


(defn- apply-spec
  [spec relation]
  (case spec
    :relation relation
    :scalar (let [rows (seq relation)] (when rows (first (first rows))))
    :coll (let [rows (seq relation)]
            (if rows
              (let [first-row (first rows)]
                (if (= 1 (count first-row)) (mapv first rows) (mapv vec rows)))
              []))
    :tuple (let [rows (seq relation)] (when rows (first rows)))))


(defn- key-fn-for
  [rm-key]
  (case rm-key
    :keys keyword
    :syms (fn [x] (if (symbol? x) x (symbol (name x))))
    :strs str
    nil))


(defn- apply-return-map
  [parsed relation]
  (if-let [rm-key (:return-map-key parsed)]
    (let [k-fn (key-fn-for rm-key)
          rm-keys (:return-map-keys parsed)]
      (mapv #(zipmap (map k-fn rm-keys) %) relation))
    relation))


(defn q
  "Datalog: (q query & inputs) where $ binds to the first input. Each
   database input is a query value (a relation value, a datom view over one,
   or an opened published index); scalar/tuple/coll/relation :in bindings
   are plain data. Returns a local result value carrying the find spec;
   `collect` materializes it into the find shapes. Opens nothing, closes
   nothing: the caller owns every handle."
  [query & inputs]
  (let [{:keys [find in with where keys syms strs]} (normalize-query query)
        in-patterns (or in '[$])
        extra-count (- (count inputs) (count in-patterns))
        _ (when (> extra-count 1)
            (throw (ex-info "query input arity permits at most one options map"
                            {:expected (count in-patterns),
                             :actual (count inputs)})))
        [bind-inputs opts] (if (= extra-count 1)
                             [(take (count in-patterns) inputs) (last inputs)]
                             [inputs nil])
        _ (when (and opts (not (map? opts)))
            (throw (ex-info "query options must be a map" {:options opts})))
        _ (when (not= (count in-patterns) (count bind-inputs))
            (throw (ex-info "query input arity must match :in"
                            {:expected (count in-patterns),
                             :actual (count bind-inputs)})))
        dbs (reduce
              (fn [dbs [pat val]]
                (if (= :db (classify-in-pattern pat))
                  (let [{relation ::relation, fact-index ::fact-index}
                        (realize-db-value! val)]
                    (assoc dbs pat {::relation relation, ::fact-index fact-index}))
                  dbs))
              {}
              (map vector in-patterns bind-inputs))
        init-bindings (build-init-bindings in-patterns bind-inputs dbs)
        fns (if (false? (:builtins opts))
              (or (:fns opts) {})
              (merge builtins (:fns opts)))
        ctx {:fns fns}
        result (eval-where where init-bindings ctx)
        parsed (parse-find find)
        [rm-key rm-keys] (cond keys [:keys keys]
                               syms [:syms syms]
                               strs [:strs strs]
                               :else [nil nil])
        parsed (cond-> parsed
                 rm-key (assoc :return-map-key
                               rm-key :return-map-keys
                               rm-keys))
        _ (when rm-key
            (check-return-map-arity (:find-vars parsed)
                                    (:spec parsed)
                                    rm-key
                                    rm-keys))
        relation (relation-result (:find-vars parsed) with result)]
    {:dao.space.query/result relation
     :spec (:spec parsed)
     :return-map-key rm-key
     :return-map-keys rm-keys}))


(defn collect
  "Materialize a q result value into plain Clojure data: relation (set of
   tuples), scalar, tuple, collection, or return-map."
  [result]
  (when-not (and (map? result) (contains? result :dao.space.query/result))
    (throw (ex-info "collect requires a query result value" {:result result})))
  (let [rows (:dao.space.query/result result)
        spec-result (apply-spec (:spec result) rows)]
    (if-let [rm-key (:return-map-key result)]
      (apply-return-map {:return-map-key rm-key,
                         :return-map-keys (:return-map-keys result)}
                        spec-result)
      spec-result)))


;; =============================================================================
;; Entity Attributes
;; =============================================================================

(defn entity-attrs
  "Convenience: return a map of {attr val} for the given entity in source.
   If multiple datoms exist for an attribute, returns a vector of values."
  [source eid]
  (dissoc (pull source eid '[*]) :db/id))
