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
  (:require [dao.data.btree.cursor :as cursor]
            [dao.data.btree.storage :as bts]
            [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.coordinate :as jing-coordinate]
            [dao.space.index :as index]
            [dao.stream :as stream]
            [dao.space.query.execution :as exec]
            [dao.stream.observe :as observe]
            #?@(:cljd [["dart:math" :as dart-math]])))


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

(defn published-db
  "A query value over the manifest at `manifest-address` in `store`, a
   live `dao.jing` handle the caller owns: the covered sets restored
   lazily (`index/restored-indexes`), the EAVT rows deferred.  The value
   owns nothing, so `close-published!` closes nothing; `open-published!`
   is this over a store it opens from a coordinate."
  [store manifest-address]
  (let [manifest (index/read-manifest store manifest-address)]
    {:dao.space.query/published {:manifest-address manifest-address}
     :indexes (index/restored-indexes store manifest)
     :rows (delay (index/read-datoms store manifest-address))
     ;; The local, nonpersistent checked-read descriptor (Phase 4b): the
     ;; manifest this constructor already read, plus the borrowed store
     ;; capability. It is source implementation data, never a result or
     ;; error context, and it changes neither the serializable coordinate
     ;; nor the persisted manifest.
     :dao.space.query/published-read {:version 1
                                      :store store
                                      :manifest-address manifest-address
                                      :manifest manifest}}))


(defn open-published!
  "Open a published covered-index coordinate — the serializable map
   `index/published-index` builds — as a query value the caller owns: the
   content-store handle it opens is closed by `close-published!`, never by
   a query. `opts` reaches `dao.jing.coordinate/open!` as-is: a remote
   content-store coordinate opens through the composition's
   :dao.jing/attach entry, which a coordinate never carries, being data.
   Nothing is fetched beyond the manifest; the row vector stays
   deferred behind :rows and the covered sets behind restored B-trees, so a
   `current` view with no as-of can answer selective clauses without a
   full drain."
  ([coordinate] (open-published! coordinate nil))
  ([coordinate opts]
   (when-not (and (map? coordinate)
                  (= :dao.space.index/published
                     (:dao.stream/type coordinate)))
     (throw (ex-info "open-published! takes a published-index coordinate"
                     {:coordinate coordinate})))
   (let [{:keys [content-store manifest-address]} coordinate
         expected (index/published-index content-store manifest-address)]
     (when-not (= expected coordinate)
       (throw (ex-info "invalid published-index coordinate"
                       {:coordinate coordinate, :expected expected})))
     (let [store (jing-coordinate/open! content-store opts)]
       (try
         (let [{:keys [indexes rows]
                read-descriptor :dao.space.query/published-read}
               (published-db store manifest-address)]
           {:dao.space.query/published coordinate
            :indexes indexes
            :rows rows
            ;; preserved through this reconstruction (same descriptor,
            ;; same manifest: no second manifest read)
            :dao.space.query/published-read read-descriptor
            :store store
            :close-guard (atom false)})
         (catch #?(:cljd Object
                   :clj Throwable
                   :cljs :default)
                error
           (jing/close! store)
           (throw error)))))))


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
;; Evaluation kernel plumbing: explicit state-passing, [state result]
;; =============================================================================
;; One evaluator serves bounded and unbounded calls. Every evaluation step
;; takes the invocation's immutable execution state (dao.space.query.execution)
;; and returns it with its result; nothing is an atom, volatile or dynamic
;; var. `ctx` is LEXICAL scope (the fn table, the active-rule path and the
;; driver capability); the state is PROGRESS. A nested rule body gets an
;; extended ctx for its own work and the caller's ctx is untouched on return,
;; but the state returned from the body is the state the caller continues
;; with. Legacy `q` runs this kernel with the explicit unbounded policy.

(defn- spend
  "One accountable unit of operation op: observe, then charge before the
   unit is performed. Returns the state; callers perform the unit only when
   it is still live."
  [st ctx op]
  (exec/step st (::driver ctx) op))


(defn- spend-n
  "n units of operation op, charged before they are performed (n = 0 only
   observes)."
  [st ctx op n]
  (if (pos? n)
    (exec/step st (::driver ctx) (* n (get exec/step-units op)))
    (exec/checkpoint st (::driver ctx) (exec/phase st))))


(defn- observe-st
  "The post-operation observation: result interpretation waits for it."
  [st ctx]
  (exec/checkpoint st (::driver ctx) (exec/phase st)))


(defn- enter-st
  "A named phase boundary."
  [st ctx phase]
  (exec/checkpoint st (::driver ctx) phase))


(defn- halted?
  "True once evaluation must stop: a terminal state, or a consumer that
   answered `reduced` (first-witness termination)."
  [st acc]
  (or (exec/terminal? st) (reduced? acc)))


(defn- fold-st
  "Direct-style fold of f over xs, threading the execution state and an
   accumulator: (f st acc x) -> [st acc]. Stops at a terminal state or a
   reduced accumulator. A loop, so stack depth does not grow with the number
   of items. Used where f is shallow (projection, pull, input expansion)."
  [st acc xs f]
  (loop [st st
         acc acc
         xs (seq xs)]
    (if (or (nil? xs) (halted? st acc))
      [st acc]
      (let [[st acc] (f st acc (first xs))]
        ;; never advance the source after a halt: `next` may realize an
        ;; element the consumer has already decided it does not need
        (if (halted? st acc) [st acc] (recur st acc (next xs)))))))


;; ---------------------------------------------------------------------------
;; The trampoline: explicit execution frames
;; ---------------------------------------------------------------------------
;; Evaluation is continuation-passing, and a continuation chain can grow with
;; recursive rule expansion, nested evaluation and the bindings processed.
;; Evaluator steps therefore never call each other (or their continuations)
;; directly: a step returns a COMPUTATION, and `run-comp` is the only
;; driver. A computation is a plain result [state acc], a bounce
;; ({::bounce thunk}: call the thunk later) or a bind ({::m comp ::f f}: run
;; comp, then hand its result to f). The pending fs are an explicit
;; heap-allocated frame list, so host stack depth is constant however deep
;; the evaluation nests, and a generous work allowance ends in structured
;; exhaustion instead of a host stack overflow.

(defn- bounce
  "Suspend thunk: it runs from the driver, after the stack has unwound."
  [thunk]
  {::bounce thunk})


(defn- bind
  "The computation that runs m, then (f result)."
  [m f]
  {::m m, ::f f})


(defn- run-comp
  "The only driver: run a computation to its plain [state acc] result with a
   constant host stack."
  [c]
  (loop [c c
         frames ()]
    (cond
      (and (map? c) (contains? c ::f)) (recur (::m c) (conj frames (::f c)))
      (and (map? c) (contains? c ::bounce)) (recur ((::bounce c)) frames)
      (empty? frames) c
      :else (recur ((first frames) c) (rest frames)))))


(defn- each-st
  "Monadic fold of f over xs, threading the execution state and an
   accumulator: (f st acc x) -> computation of [st acc]. Stops at a terminal
   state or a reduced accumulator. Each item runs from the driver and the
   rest of the fold is a frame, so the stack never grows with the number of
   items or the nesting of what f evaluates."
  [st acc xs f]
  (let [xs (seq xs)]
    (if (or (nil? xs) (halted? st acc))
      [st acc]
      (bind (bounce (fn [] (f st acc (first xs))))
            (fn [[st acc]]
              (if (halted? st acc)
                [st acc]
                (each-st st acc (next xs) f)))))))


(def ^:private unbounded-ctx {::driver nil})


;; =============================================================================
;; Realize a db-value: value tag -> {::relation ::fact-index ::indexes}
;; =============================================================================

(defn- relation->fact-index
  [relation]
  (index/index-datoms (mapv (fn [tuple]
                              [(nth tuple 0) (nth tuple 1)
                               (nth tuple 2) 0 datom/default-op])
                            relation)))


(defn- tuple-source?
  "A valid row source: nil (no rows), a sequential collection or a set of
   tuples."
  [x]
  (or (nil? x) (sequential? x) (set? x)))


(defn- refuse-relation
  []
  (throw (ex-info "relation source must be a sequence of tuples"
                  {:reason :invalid-relation})))


(defn- force-relation
  "Force a relation if it is delayed (e.g. from a lazy covered-index
   realization) or return it directly. Memoization belongs to the delay:
   forcing is therefore at most one walk. A delay whose value is not a
   tuple source is refused here (E13), never read as empty."
  [relation]
  (let [r (if (delay? relation) @relation relation)]
    (when-not (tuple-source? r) (refuse-relation))
    r))


(declare realize-st)


(defn ^:no-doc realize-db-value!
  "Turn one db-value into an immutable tuple relation plus, for current
   fact views, an in-memory fact index. Opens nothing, closes nothing.
   Runs the checked realization with the explicit unbounded policy."
  [db-value]
  (second (realize-st exec/unbounded unbounded-ctx db-value)))


(def ^:private raw-source-message
  "query db input must be a relation value, a datom view, or an opened published index; raw vectors and maps are rejected")


(defn- relation-size
  "The tuple count of a relation without forcing a deferred one."
  [relation]
  (if (delay? relation) 0 (count relation)))


(defn- apply-view-st
  "Interpret ONE current/history view over its already realized source. A
   current view with no as-of over an opened published index routes through
   the restored covered sets; every other combination resolves the source's
   rows. The tuples interpreted and indexed are charged before the
   indivisible host operation runs."
  [st ctx d src]
  (let [kind (:dao.space.query/view d)
        as-of (:as-of d)
        {src-relation ::relation, src-indexes ::indexes} src]
    (if (and (= :current kind) (nil? as-of) src-indexes)
      [st {::relation (delay (d5->current-facts (force-relation src-relation) nil)),
           ::fact-index src-indexes}]
      (let [forced-rel (force-relation src-relation)
            st (spend-n st ctx :scan-advance (relation-size forced-rel))]
        (if (exec/terminal? st)
          [st nil]
          (let [rows (case kind
                       :current (d5->current-facts forced-rel as-of)
                       :history (d5->history-rows forced-rel as-of))
                st (spend-n st ctx :scan-advance
                            (if (= :current kind) (count rows) 0))]
            (if (exec/terminal? st)
              [st nil]
              (let [fact-index (when (= :current kind)
                                 (relation->fact-index rows))
                    st (observe-st st ctx)]
                (if (exec/terminal? st)
                  [st nil]
                  [st {::relation rows, ::fact-index fact-index}])))))))))


(defn- valid-read-descriptor?
  "STRUCTURAL validity of a published value's checked-read descriptor: the
   exact key set, version 1, non-nil capability fields, a manifest of the
   persisted shape. Never judged by identity, and nothing is read."
  [d]
  (and (map? d)
       (= #{:version :store :manifest-address :manifest} (set (keys d)))
       (= 1 (:version d))
       (some? (:store d))
       (some? (:manifest-address d))
       (let [m (:manifest d)]
         (and (map? m)
              (map? (:indexes m))
              (integer? (:count m))
              (integer? (:branching-factor m))))))


(defn ^:no-doc source-step
  "The ONE bounded-source classification policy, shared by adapter admission
   and bounded realization. One hop over a source descriptor, inspected and
   never forced:
     {:class :checked}       exactly `current`, no as-of, DIRECTLY over a
                             published value with a valid checked-read
                             descriptor (depth 0 only)
     {:class :materialized}  a relation / fact-relation vector
     {:class :refused}       a bare published value, a deferred or non-vector
                             relation, anything unrecognized
     {:next src}             a view: continue with its source
   A view over a published value that is not that exact shape (history, an
   as-of bound, a nested view, a descriptor-less value) therefore ends at the
   published base and is refused."
  [v depth]
  (cond
    (not (map? v)) {:class :refused}
    (contains? v :dao.space.query/relation)
    {:class (if (vector? (:dao.space.query/relation v)) :materialized :refused)}
    (contains? v :dao.space.query/published) {:class :refused}
    (contains? v :dao.space.query/view)
    (let [src (:source v)]
      (if (and (zero? depth)
               (= :current (:dao.space.query/view v))
               (nil? (:as-of v))
               (map? src)
               (contains? src :dao.space.query/published)
               (not (contains? src :dao.space.query/relation))
               (valid-read-descriptor? (:dao.space.query/published-read src)))
        {:class :checked}
        {:next src}))
    :else {:class :refused}))


(defn- realize-checked-st
  "Realize an admitted checked source: no fetch, no rows, no tree. The
   realized value carries an explicit checked-index marker; its relation
   slot is a truthy sentinel (planning only tests truthiness) that the
   eager relation fallback refuses to use. The legacy :rows delay is never
   touched."
  [st ctx db-value]
  (let [d (get-in db-value [:source :dao.space.query/published-read])
        st (observe-st st ctx)]
    (if (exec/terminal? st)
      [st nil]
      [st {::relation ::checked-source
           ::fact-index {::checked {:store (:store d), :manifest (:manifest d)}}}])))


(defn- realize-base-st
  "Realize a non-view db-value: a relation (and, for a fact relation, its
   index) or an opened published index."
  [st ctx db-value]
  (cond
    (contains? db-value :dao.space.query/relation)
    (let [tuples (:dao.space.query/relation db-value)
          _ (when-not (or (delay? tuples) (tuple-source? tuples)) (refuse-relation))
          fact? (:fact? db-value)
          st (spend-n st ctx :scan-advance (if fact? (relation-size tuples) 0))]
      (if (exec/terminal? st)
        [st nil]
        (let [fact-index (when fact? (relation->fact-index tuples))
              st (observe-st st ctx)]
          (if (exec/terminal? st)
            [st nil]
            [st {::relation tuples, ::fact-index fact-index}]))))

    (contains? db-value :dao.space.query/published)
    (do (when (exec/bounded? st)
          (throw (ex-info "bounded execution does not support opened published indexes"
                          {:reason :bounded-source-unqualified})))
        (let [indexes (index/covered-indexes db-value)]
          [st {::relation (:rows db-value),
               ::indexes indexes,
               ::fact-index nil}]))

    :else
    (throw (ex-info raw-source-message {:input db-value}))))


(declare realize-views-st)


(defn- realize-st
  "The checked realization of one db-value: [state realized]. Iterative: the
   view chain is walked down to its base source in a loop (one accounted
   unit per descriptor hop) and the views are applied back up in a second
   loop, so a source chain of any depth uses constant host stack. Tuples and
   index entries are charged before the (indivisible) host construction and
   the state is observed when it returns. A bounded state refuses an opened
   published index: its traversal is not checkpointed."
  [st ctx db-value]
  (if (and (exec/bounded? st)
           (= :checked (:class (source-step db-value 0))))
    (realize-checked-st st ctx db-value)
    (realize-views-st st ctx db-value)))


(defn- realize-views-st
  [st ctx db-value]
  (loop [st st
         v db-value
         views ()]
    (when-not (map? v)
      (throw (ex-info raw-source-message {:input v})))
    (cond
      (exec/terminal? st) [st nil]

      (and (contains? v :dao.space.query/view)
           (not (contains? v :dao.space.query/relation))
           (not (contains? v :dao.space.query/published)))
      (recur (spend st ctx :scan-advance) (:source v) (conj views v))

      :else
      (let [[st base] (realize-base-st st ctx v)]
        ;; views holds the innermost first: apply back up
        (loop [st st
               realized base
               views views]
          (cond
            (exec/terminal? st) [st nil]
            (empty? views) [st realized]
            :else (let [[st r] (apply-view-st st ctx (first views) realized)]
                    (recur st r (rest views)))))))))


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


(defn- slot-pred
  "The kind-strict filter of a datom against the bound slots of a pattern."
  [e a v]
  (fn [d]
    (and (or (wildcard? e) (cbor/content= e (index/datom-e d)))
         (or (wildcard? a) (cbor/content= a (index/datom-a d)))
         (or (wildcard? v) (cbor/content= v (index/datom-v d))))))


(def ^:private order-cmps
  {:eavt index/eavt-cmp, :aevt index/aevt-cmp, :avet index/avet-cmp, :vaet index/vaet-cmp})


(defn- range-plan
  "The PURE range plan of an index access, shared by every producer: which
   covered order to use (bound entity -> EAVT; attribute and value -> AVET;
   value -> VAET; attribute -> AEVT; otherwise a full EAVT walk), the lower
   seek key in canonical [e a v t m] slot positions (never physically
   reordered), and the caller-owned prefix test that says whether a
   candidate is still inside the scanned slot(s). The prefix test is a
   COMPARATOR test (same-slot?): it continues across values that compare
   equal but differ in content, and content-strict filtering (slot-pred)
   happens after candidate production. No generic upper key is ever
   manufactured."
  [e a v]
  (let [e? (and (not (wildcard? e)) (range-scannable? e))
        a? (and (not (wildcard? a)) (range-scannable? a))
        v? (and (not (wildcard? v)) (range-scannable? v))]
    (cond e? {:order :eavt
              :lower [e nil nil nil nil]
              :continue? #(same-slot? e (index/datom-e %))}
          (and a? v?) {:order :avet
                       :lower [nil a v nil nil]
                       :continue? #(and (same-slot? a (index/datom-a %))
                                        (same-slot? v (index/datom-v %)))}
          v? {:order :vaet
              :lower [nil nil v nil nil]
              :continue? #(same-slot? v (index/datom-v %))}
          a? {:order :aevt
              :lower [nil a nil nil nil]
              :continue? #(same-slot? a (index/datom-a %))}
          :else {:order :eavt, :lower nil, :continue? nil})))


(defn- index-candidates
  "The raw candidate datoms of an index range for the bound scalar slots,
   unfiltered and lazy (the restored-tree producer): the range plan's seek
   and prefix stop over the in-memory or restored covered set. The
   kind-strict filter is the caller's, applied per candidate."
  [idx e a v]
  (let [{:keys [order lower continue?]} (range-plan e a v)]
    (if lower
      (take-while continue?
                  (index/subseq-from (get idx order) (get order-cmps order) lower))
      (seq (:eavt idx)))))


(defn- select-by-index
  "The current-state datoms whose bound slots are content= the pattern's.
   The index range of the bound scalar slots is scanned by index order
   (numerically equal values of different kinds included), then every bound
   slot is matched kind-strictly."
  [idx e a v]
  (current-state-seq (filter (slot-pred e a v) (index-candidates idx e a v))))


(declare checked-datoms-st restored-datoms-st)


(defn- datoms-st
  "The checked select: [state datoms]. Each candidate the range scan
   yields is one accountable unit of op, charged before it is examined and
   pulled one at a time; the state is observed again before the collected
   candidates are interpreted. The current-state resolution over the
   charged candidates is one indivisible host operation. A published value's
   checked source (`::checked`) is read by `checked-datoms-st`, one accounted
   cursor transition and one bracketed node read at a time; every other index
   is the in-memory or restored-tree producer."
  [st ctx idx e a v op]
  (if (contains? idx ::checked)
    (checked-datoms-st st ctx (::checked idx) e a v op)
    (restored-datoms-st st ctx idx e a v op)))


(defn- restored-datoms-st
  [st ctx idx e a v op]
  (let [pred (slot-pred e a v)]
    (loop [st st
           xs (seq (index-candidates idx e a v))
           kept []]
      (cond
        (exec/terminal? st) [st nil]
        (nil? xs) (let [st (observe-st st ctx)]
                    (if (exec/terminal? st)
                      [st nil]
                      [st (vec (current-state-seq kept))]))
        :else (let [st (spend st ctx op)]
                (if (exec/terminal? st)
                  [st nil]
                  (let [d (first xs)]
                    (recur st (next xs) (if (pred d) (conj kept d) kept)))))))))


(defn- source-failure
  "A traversal or read failure of the checked path: the original is the
   cause and the data carries no handle, descriptor or source value."
  [cause]
  (ex-info "checked index traversal failed"
           {::source-failure true}
           cause))


(defn- guarded
  [f & args]
  (try (apply f args)
       (catch #?(:cljd Object :clj Throwable :cljs :default) e
         (throw (source-failure e)))))


(defn- checked-datoms-st
  "The checked candidate producer over a published value's persisted
   B-tree nodes: [state datoms]. It is the query-side interpreter of the
   pure cursor (dao.data.btree.cursor), threading [state cursor kept] in one
   constant-stack loop:
     every cursor transition: observe + charge one :tree-step; a terminal
       state stops before the cursor advances;
     :need-node: observe + charge one :node-read, then exactly ONE
       `read-node-blob` inside `execution/read-step`; a terminal state
       starts no read, and a node returned after the deadline passed is
       DISCARDED and never supplied;
     :item: charge one `op` for the candidate; the first key outside the
       range plan's prefix ends the scan (nothing further is requested);
       the kind-strict filter runs after candidate production.
   Reads go through read-node-blob only: KVStorage/-restore and the restored
   BTSet machinery are never on this path. A throw from the cursor or the
   read becomes a ::source-failure with the original as its cause."
  [st ctx {:keys [store manifest]} e a v op]
  (let [{:keys [order lower continue?]} (range-plan e a v)
        cmp (get order-cmps order)
        pred (slot-pred e a v)
        phase (exec/phase st)
        driver (::driver ctx)
        ;; start is guarded like every other cursor call: a structurally
        ;; valid but inconsistent descriptor (a positive count with no root
        ;; for the selected order) is a source failure, not an evaluator fault
        cursor0 (guarded cursor/start
                         {:root (get-in manifest [:indexes order])
                          :count (:count manifest)
                          :branching-factor (:branching-factor manifest)
                          :lower (when lower {:key lower, :inclusive? true})
                          :upper nil}
                         cmp)
        finish (fn [st kept]
                 (let [st (observe-st st ctx)]
                   (if (exec/terminal? st)
                     [st nil]
                     [st (vec (current-state-seq kept))])))]
    (loop [st st
           cur cursor0
           kept []]
      (if (exec/terminal? st)
        [st nil]
        (let [st (spend st ctx :tree-step)]
          (if (exec/terminal? st)
            [st nil]
            (let [r (guarded cursor/step cur)]
              (case (:status r)
                :continue (recur st (:cursor r) kept)

                :need-node
                (let [address (:address r)
                      st (spend st ctx :node-read)]
                  (if (exec/terminal? st)
                    [st nil]
                    (let [[st blob] (exec/read-step
                                      st driver
                                      (fn [] (guarded bts/read-node-blob store address)))]
                      (if (exec/terminal? st)
                        [st nil]
                        (recur (exec/enter st phase)
                               (guarded cursor/supply (:cursor r)
                                        {:address address, :node blob})
                               kept)))))

                :item
                (let [d (:value r)]
                  (if (and continue? (not (continue? d)))
                    (finish st kept)
                    (let [st (spend st ctx op)]
                      (if (exec/terminal? st)
                        [st nil]
                        (recur st (:cursor r) (if (pred d) (conj kept d) kept))))))

                :done (finish st kept)))))))))


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
  [st ctx idx eid spec]
  (let [rev-attr (forward-attr-name spec)
        fwd-attr (reverse-to-forward rev-attr)
        [st matching] (datoms-st st ctx idx '_ fwd-attr eid :pull-advance)]
    (if (exec/terminal? st)
      [st nil]
      (let [results (mapv (fn [d] {:db/id (nth d 0)}) matching)]
        [st (if (seq results)
              [(apply-options spec rev-attr)
               (if (and (map? spec) (:limit spec))
                 (take (:limit spec) results)
                 results)]
              (when (and (map? spec) (contains? spec :default))
                [(apply-options spec rev-attr) (:default spec)]))]))))


(defn- project-flat-attr
  [st ctx idx eid spec]
  (let [attr (forward-attr-name spec)]
    (if (reverse-attr? attr)
      (project-reverse-attr st ctx idx eid spec)
      (let [[st matching] (datoms-st st ctx idx eid attr '_ :pull-advance)]
        (if (exec/terminal? st)
          [st nil]
          (let [values (mapv #(nth % 2) matching)]
            [st (cond (empty? values) (when (and (map? spec) (contains? spec :default))
                                        [(apply-options spec attr) (:default spec)])
                      (= 1 (count values)) [(apply-options spec attr) (first values)]
                      :else (let [limit (and (map? spec) (:limit spec))
                                  result (if limit (take limit values) values)]
                              [(apply-options spec attr) result]))]))))))


(defn- project-wildcard
  [st ctx idx eid]
  (let [[st matching] (datoms-st st ctx idx eid '_ '_ :pull-advance)]
    (if (exec/terminal? st)
      [st nil]
      [st (reduce (fn [m [attr ds]]
                    (let [values (mapv #(nth % 2) ds)]
                      (assoc m attr (if (= 1 (count values)) (first values) values))))
                  {}
                  (group-by #(nth % 1) matching))])))


(defn- pull-flat
  [st ctx idx eid parsed]
  (let [[st base] (if (:wildcard? parsed)
                    (project-wildcard st ctx idx eid)
                    [st {}])]
    (if (exec/terminal? st)
      [st nil]
      (let [[st with-attrs]
            (fold-st st base (:attrs parsed)
                     (fn [st m spec]
                       (let [st (spend st ctx :pull-advance)]
                         (if (exec/terminal? st)
                           [st m]
                           (let [[st kv] (project-flat-attr st ctx idx eid spec)]
                             (if-let [[k v] (and (not (exec/terminal? st)) kv)]
                               [st (assoc m k v)]
                               [st m]))))))]
        [st (when-not (exec/terminal? st) (assoc with-attrs :db/id eid))]))))


(declare pull-full-impl)


(defn- project-nested-attr
  [st ctx idx eid attr sub-spec]
  (let [reverse? (reverse-attr? attr)
        [st matching] (if reverse?
                        (datoms-st st ctx idx '_ (reverse-to-forward attr) eid :pull-advance)
                        (datoms-st st ctx idx eid attr '_ :pull-advance))]
    (if (exec/terminal? st)
      [st nil]
      (let [values (mapv #(nth % (if reverse? 0 2)) matching)
            [st results]
            (fold-st st [] values
                     (fn [st acc v]
                       (let [[st ds] (datoms-st st ctx idx v '_ '_ :pull-advance)]
                         (if (or (exec/terminal? st) (empty? ds))
                           [st acc]
                           (let [[st r] (pull-full-impl st ctx idx v sub-spec)]
                             [st (if (exec/terminal? st) acc (conj acc r))])))))]
        (cond (exec/terminal? st) [st nil]
              (empty? results) [st nil]
              reverse? [st [attr results]]
              (= 1 (count results)) [st [attr (first results)]]
              :else [st [attr results]])))))


(defn- pull-full-impl
  [st ctx idx eid parsed]
  (let [[st base] (pull-flat st ctx idx eid parsed)]
    (if (exec/terminal? st)
      [st nil]
      (fold-st st base (:nested parsed)
               (fn [st m [attr sub-spec]]
                 (let [[st kv] (project-nested-attr st ctx idx eid attr sub-spec)]
                   (if-let [[k v] (and (not (exec/terminal? st)) kv)]
                     [st (assoc m k v)]
                     [st m])))))))


(defn- pull-idx
  "idx-level pull: the fact index has already been built. {:db/id eid} (only)
   if eid has no datoms — matching Datomic: entity ids are not
   existence-checked, so pull never returns nil at the top level, it
   always echoes back :db/id. [state result]; the result is meaningful only
   while the state is live."
  [st ctx idx eid pattern]
  (let [[st ds] (datoms-st st ctx idx eid '_ '_ :pull-advance)]
    (cond (exec/terminal? st) [st nil]
          (seq ds) (pull-full-impl st ctx idx eid (parse-pattern pattern))
          :else [st {:db/id eid}])))


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
    (second (pull-idx exec/unbounded unbounded-ctx fact-index eid pattern))))


(defn pull-many
  "Pull multiple entities with one shared fact index."
  [source eids pattern]
  (let [{::keys [fact-index]} (realize-db-value! source)]
    (when-not fact-index
      (throw (ex-info
               "pull-many requires a current fact-shaped source view"
               {:source source})))
    (let [parsed (parse-pattern pattern)]
      (second
        (fold-st exec/unbounded [] eids
                 (fn [st acc eid]
                   (let [[st ds] (datoms-st st unbounded-ctx fact-index eid '_ '_ :pull-advance)]
                     (if (seq ds)
                       (let [[st r] (pull-full-impl st unbounded-ctx fact-index eid parsed)]
                         [st (conj acc r)])
                       [st (conj acc {:db/id eid})]))))))))


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


(defn- portable-subvec
  "subvec over a vector with checked integer bounds `0 <= start <= end <=
   (count v)`. Anything else is an explicit refusal with the same text on
   every host, never a host error."
  ([v start] (portable-subvec v start (when (vector? v) (count v))))
  ([v start end]
   (when-not (vector? v)
     (throw (ex-info "query builtin subvec requires a vector operand"
                     {:fn 'subvec, :operand v, :type (type-label v)})))
   (when-not (and (integer? start) (integer? end) (<= 0 start end (count v)))
     (throw (ex-info (str "query builtin subvec requires integer bounds"
                          " 0 <= start <= end <= (count v)")
                     {:fn 'subvec, :start start, :end end, :count (count v)})))
   (subvec v start end)))


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
   'subvec portable-subvec,
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


(defn- resolve-binding
  "The value a pattern, fn-argument or rule-argument term stands for: a
   ?-variable's bound value (FREE while unbound), FREE for the blank `_`,
   and any other term, a bare symbol included, as the constant it is."
  [binding sym]
  (cond (query-var-symbol? sym) (get binding sym FREE)
        (= '_ sym) FREE
        :else sym))


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
   addressing identifies it. Only a ?-symbol is a variable: a bare symbol
   is a constant matching that symbol value."
  [binding sym val]
  (cond (or (= sym FREE) (= sym '_)) binding
        (not (query-var-symbol? sym)) (when (cbor/content= sym val) binding)
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
    ;; lazy: the checked cross product pulls one binding at a time and
    ;; charges each, so an input is never enumerated ahead of the budget
    :coll (let [sym (first pattern)] (map (fn [elem] {sym elem}) value))
    :tuple [(zipmap pattern value)]
    :relation (let [vars (first pattern)]
                (map (fn [tup] (zipmap vars tup)) value))))


(defn- merge-bindings
  [b1 b2]
  (let [merged-dbs (merge (get b1 ::dbs {}) (get b2 ::dbs {}))]
    (cond-> (merge b1 b2) (seq merged-dbs) (assoc ::dbs merged-dbs))))


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
  (let [relation (::relation (resolve-db binding db-sym))]
    (when (= ::checked-source relation)
      ;; the checked source has no row relation, and the legacy fallback
      ;; (read-datoms / force-relation / a legacy tree seq) is never taken
      (throw (ex-info "a checked published source cannot answer this clause without an eager fallback"
                      {:reason :checked-source-fallback})))
    (force-relation relation)))


(defn- resolve-fact-index
  [binding db-sym]
  (::fact-index (resolve-db binding db-sym)))


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


(defn- resolve-special-arg
  [binding a]
  (let [v (resolve-binding binding a)]
    (when (= v FREE)
      (throw (ex-info "Unbound variable in special-form clause" {:var a})))
    v))


(defn- unify-tuple
  "Unify each term of a tuple binding form with the matching position of
   ret, or nil on a mismatch. A ret that is not sequential or not of the
   form's arity is a query error."
  [binding terms ret clause]
  (when (or (not (sequential? ret)) (not= (count terms) (count ret)))
    (throw (ex-info "Function return does not match tuple binding arity"
                    {:binding-form terms, :returned ret, :clause clause})))
  (reduce (fn [b [term val]] (or (unify b term val) (reduced nil)))
          binding
          (map vector terms ret)))


(defn- bind-fn-result
  "The bindings a fn clause's result ret yields under its binding form out:
   the forms :in accepts (classify-in-pattern), with Datomic semantics.
   Scalar ?x binds ret; tuple [?a ?b] binds positionally; collection
   [?x ...] yields one binding per element; relation [[?a ?b]] one per
   tuple. Terms unify as in patterns, so _ is blank and a bare symbol is a
   constant. A collection or relation form takes a sequential or a set ret
   (order is irrelevant: the result is a set); nil or an empty one yields
   no binding, and anything else, a map included, is a query error."
  [binding out ret clause]
  (if-not (vector? out)
    (if-let [b (unify binding out ret)] [b] [])
    (let [kind (classify-in-pattern out)
          terms (case kind
                  :coll (butlast out)
                  :relation (first out)
                  :tuple out)]
      (when (or (and (= :coll kind) (not= 2 (count out)))
                (and (= :relation kind) (not= 1 (count out)))
                (some #(or (= '... %) (vector? %)) terms))
        (throw (ex-info
                 "Unsupported binding form — use ?x, [?a ?b], [?x ...] or [[?a ?b]]"
                 {:binding-form out, :clause clause})))
      (when (and (#{:coll :relation} kind)
                 (not (or (nil? ret) (sequential? ret) (set? ret))))
        (throw (ex-info (str "Function return for a "
                             (if (= :coll kind) "collection" "relation")
                             " binding must be nil, sequential or a set")
                        {:binding-form out, :returned ret, :clause clause})))
      (case kind
        :tuple (if-let [b (unify-tuple binding out ret clause)] [b] [])
        ;; collection / relation results are NOT enumerated here: the plan
        ;; hands the kernel the items and the per-item attempt, and the
        ;; kernel charges every attempt (rejected ones too)
        :coll (let [term (first out)]
                {::items ret, ::attempt (fn [x] (unify binding term x))})
        :relation {::items ret,
                   ::attempt (fn [t] (unify-tuple binding terms t clause))}))))


;; =============================================================================
;; The evaluation kernel (continuation-passing, depth-first, state-passing)
;; =============================================================================
;; `emit-*` evaluates one clause against ONE binding and hands every output
;; binding to the continuation k: (k st acc b') -> [st acc]. The rest of the
;; conjunction is the continuation, so evaluation is depth first and the
;; consumer decides the demand: collecting everything, or answering
;; `reduced` at the first witness (not, not-join). Nothing is materialized
;; beyond what a clause itself enumerates; deduplication of a nested rule or
;; disjunction is a seen-set carried in the accumulator, not a lazy chain.

(declare emit-clause eval-where*)


(defn- select-probe-st
  [st ctx idx e a v]
  (if-not idx
    [st nil]
    (let [[st ds] (datoms-st st ctx idx e '_ '_ :scan-advance)]
      (if (exec/terminal? st)
        [st nil]
        (let [matches (filter (fn [d]
                                (and (or (wildcard? a) (cbor/content= a (index/datom-a d)))
                                     (or (wildcard? v) (cbor/content= v (index/datom-v d)))))
                              ds)]
          [st (if (seq matches)
                {:v (mapv #(nth % 2) matches), :missing false}
                {:missing true})])))))


(defn- eval-special-fn
  "[state special] where special is {:ret x} for get-else / missing? and nil
   for any other function."
  [st ctx fsym args binding]
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
                   [st probe] (select-probe-st st ctx idx e' a' '_)]
               (if (exec/terminal? st)
                 [st nil]
                 (let [vs (:v probe)] [st {:ret (if (seq vs) (first vs) d')}])))
    missing? (let [[_src e a] args
                   idx (resolve-fact-index binding _src)
                   _ (when-not idx
                       (throw
                         (ex-info
                           "missing? requires a current fact-shaped source view"
                           {:source _src})))
                   e' (resolve-special-arg binding e)
                   a' (resolve-special-arg binding a)
                   [st probe] (select-probe-st st ctx idx e' a' '_)]
               (if (exec/terminal? st)
                 [st nil]
                 [st {:ret (boolean (:missing probe))}]))
    [st nil]))


(defn- fn-clause-outputs
  "[state outputs]: the bindings a fn clause yields for one binding. The
   host function is one indivisible operation; the state is observed when
   it returns, before its answer is interpreted."
  [st ctx clause binding]
  (let [[fsym-and-args & result-vars] clause
        fsym (first fsym-and-args)
        args (rest fsym-and-args)
        [st special] (eval-special-fn st ctx fsym args binding)]
    (cond
      (exec/terminal? st) [st nil]

      special
      (let [{:keys [ret]} special]
        (when (> (count result-vars) 1)
          (throw
            (ex-info
              "Function clause takes one binding form; use a tuple [?a ?b] for multi-return"
              {:clause clause})))
        [st (if (empty? result-vars)
              (if ret [binding] [])
              (let [out (first result-vars)]
                (when (vector? out)
                  (throw (ex-info
                           "get-else / missing? take a scalar binding, not a tuple"
                           {:binding-form out, :clause clause})))
                (let [b (unify binding out ret)] (if b [b] []))))])

      :else
      (let [f (get-in ctx [:fns fsym])]
        (when-not f
          (throw (ex-info "Unknown query fn — pass it via the :fns option"
                          {:fn fsym, :clause clause})))
        (let [arg-vals (mapv (fn [a]
                               (let [v (resolve-binding binding a)]
                                 (when (= v FREE)
                                   (throw (ex-info "Unbound variable in fn clause"
                                                   {:var a, :clause clause})))
                                 v))
                             args)
              ret (apply f arg-vals)
              st (observe-st st ctx)]
          (if (exec/terminal? st)
            [st nil]
            [st (if (empty? result-vars)
                  (if ret [binding] [])
                  (let [out (first result-vars)]
                    (when (> (count result-vars) 1)
                      (throw
                        (ex-info
                          "Function clause takes one binding form; use a tuple [?a ?b] for multi-return"
                          {:clause clause})))
                    (bind-fn-result binding out ret clause)))]))))))


(defn- emit-fn
  [st ctx clause binding acc k]
  (let [st (spend st ctx :candidate)]
    (if (exec/terminal? st)
      [st acc]
      (let [[st outs] (fn-clause-outputs st ctx clause binding)]
        (cond
          (exec/terminal? st) [st acc]
          ;; incremental producer: charge each ATTEMPTED binding, honor
          ;; terminal / reduced outcomes before the next element
          (map? outs)
          (each-st st acc (::items outs)
                   (fn [st acc item]
                     (let [st (spend st ctx :candidate)]
                       (if (exec/terminal? st)
                         [st acc]
                         (if-let [b ((::attempt outs) item)]
                           (k st acc b)
                           [st acc])))))
          :else (each-st st acc outs k))))))


(defn- emit-pattern
  [st ctx clause binding acc k]
  (let [[db-sym pattern] (clause-db-and-pattern clause)
        fact-index (resolve-fact-index binding db-sym)
        parsed (parse-tuple-pattern pattern)]
    (if (and fact-index (nil? (:rest parsed)) (= 3 (count (:fixed parsed))))
      (let [[e a v] (mapv #(resolve-binding binding %) (:fixed parsed))
            [st ds] (datoms-st st ctx fact-index e a v :scan-advance)]
        (if (exec/terminal? st)
          [st acc]
          (each-st st acc ds
                   (fn [st acc d]
                     (let [st (spend st ctx :candidate)]
                       (if (exec/terminal? st)
                         [st acc]
                         (if-let [b (unify-slots binding parsed
                                                 [(index/datom-e d) (index/datom-a d)
                                                  (index/datom-v d)])]
                           (k st acc b)
                           [st acc])))))))
      (each-st st acc (resolve-relation binding db-sym)
               (fn [st acc tuple]
                 (let [st (spend st ctx :scan-advance)
                       st (if (exec/terminal? st) st (spend st ctx :candidate))]
                   (if (exec/terminal? st)
                     [st acc]
                     (if-let [b (unify-slots binding parsed tuple)]
                       (k st acc b)
                       [st acc]))))))))


(defn- dedup-k
  "Wrap continuation k so it sees each content-distinct binding once. The
   accumulator becomes [seen acc]."
  [k]
  (fn [st [seen acc] b]
    (let [key (binding-key b)]
      (if (contains? seen key)
        [st [seen acc]]
        (bind (bounce (fn [] (k st acc b)))
              (fn [[st acc']]
                (let [seen' (conj seen key)]
                  (if (reduced? acc')
                    [st (reduced [seen' @acc'])]
                    [st [seen' acc']]))))))))


(defn- run-dedup
  "Run (body st sa dk) with a deduplicating continuation, handing the
   caller's accumulator back unwrapped. A computation."
  [st acc k body]
  (bind (bounce (fn [] (body st [#{} acc] (dedup-k k))))
        (fn [[st sa]]
          (if (reduced? sa)
            [st (reduced (second @sa))]
            [st (second sa)]))))


(defn- exists-st
  "Computation of [state bool]: does the clause list have a witness for
   binding? Stops at the first one; nothing after it is evaluated."
  [st ctx clauses binding]
  (bind (eval-where* st ctx clauses [binding] false
                     (fn [st _ _] [st (reduced true)]))
        (fn [[st found]] [st (true? (unreduced found))])))


(defn- emit-not
  [st ctx clause binding acc k]
  (let [inner-clauses (rest clause)]
    (doseq [v (not-required-vars inner-clauses)]
      (when (= FREE (resolve-binding binding v))
        (throw
          (ex-info
            "All variables inside not must be bound; use not-join to introduce local variables"
            {:var v, :clause clause}))))
    (bind (exists-st st ctx inner-clauses binding)
          (fn [[st witness?]]
            (cond (exec/terminal? st) [st acc]
                  witness? [st acc]
                  :else (bounce (fn [] (k st acc binding))))))))


(defn- emit-not-join
  [st ctx clause binding acc k]
  (let [req-vars (nth clause 1)
        inner-clauses (drop 2 clause)]
    (doseq [v req-vars]
      (when (= FREE (resolve-binding binding v))
        (throw (ex-info "not-join variables must be bound" {:var v}))))
    (let [inner-binding (into (select-keys binding [::dbs '%])
                              (map (fn [v] [v (get binding v)]))
                              req-vars)]
      (bind (exists-st st ctx inner-clauses inner-binding)
            (fn [[st witness?]]
              (cond (exec/terminal? st) [st acc]
                    witness? [st acc]
                    :else (bounce (fn [] (k st acc binding)))))))))


(defn- emit-or
  [st ctx clause binding acc k]
  (run-dedup st acc k
             (fn [st sa dk]
               (each-st st sa (rest clause)
                        (fn [st sa branch]
                          (eval-where* st ctx (branch-clauses branch) [binding] sa dk))))))


(defn- emit-or-join
  [st ctx clause binding acc k]
  (let [join-vars (nth clause 1)
        branches (drop 2 clause)
        seed (into (select-keys binding [::dbs '%])
                   (keep (fn [v]
                           (let [v' (get binding v FREE)]
                             (when (not= v' FREE) [v v']))))
                   join-vars)
        augment (fn [branch-result]
                  (reduce (fn [acc k] (unify acc k (get branch-result k FREE)))
                          binding
                          join-vars))]
    (run-dedup st acc k
               (fn [st sa dk]
                 (each-st st sa branches
                          (fn [st sa branch]
                            (eval-where* st ctx (branch-clauses branch) [seed] sa
                                         (fn [st sa res]
                                           (if-let [b' (augment res)]
                                             (dk st sa b')
                                             [st sa])))))))))


(defn- rule-head
  "Parse a rule head `(name args...)`. Datomic's required-bound form
   `(name [?a ?b] ?c)` declares in ONE leading vector the variables a call
   must already have bound; the call itself is flat: `(name 1 2 ?c)`.
   Returns {:vars (all head variables, required first) :required n}."
  [head]
  (let [args (vec (rest head))
        req (when (and (seq args) (vector? (first args))) (first args))
        others (if req (subvec args 1) args)]
    (when (or (some vector? others)
              (not (every? symbol? req)))
      (throw (ex-info "malformed rule head: required-bound variables are one leading vector of symbols"
                      {:head head})))
    {:vars (vec (concat req others)), :required (count req)}))


(defn- note-hit
  "Record, in the threaded state, that a repeated call read the table of
   call-key (the recursion signal the leader's fixed point waits on)."
  [st call-key]
  (if (exec/terminal? st) st (update st ::hits (fnil conj #{}) call-key)))


(defn- clear-hit
  [st call-key]
  (if (exec/terminal? st) st (update st ::hits (fnil disj #{}) call-key)))


(defn- hit?
  [st call-key]
  (contains? (::hits st #{}) call-key))


(defn- unify-call
  "The caller's binding extended by unifying each call argument with the
   matching value of a head tuple, or nil."
  [binding call-args tuple]
  (reduce (fn [b [arg v]] (and-then b #(unify % arg v)))
          binding
          (map vector call-args tuple)))


(defn- read-table
  "A repeated call (its key is being evaluated further up this path) reads
   the tuples tabled so far, one accounted candidate each, and records the
   hit. Complete answers arrive because the leader re-runs until nothing new
   appears."
  [st ctx table binding call-args call-key acc k]
  (each-st (note-hit st call-key) acc (:tuples table)
           (fn [st acc tuple]
             (let [st (spend st ctx :candidate)]
               (if (exec/terminal? st)
                 [st acc]
                 (if-let [b' (unify-call binding call-args tuple)]
                   (k st acc b')
                   [st acc]))))))


(defn- rule-iteration
  "One pass over every definition of the rule, with the tuples tabled so far
   visible to repeated calls. Each NEW head tuple (content-distinct across
   all passes) is handed to k at once; a2 is {:seen :new :acc}."
  [st ctx table defs rule-name call-args arg-vals binding call-key a2 k]
  (let [body-ctx (assoc ctx ::tables (assoc (::tables ctx {}) call-key table))]
    (each-st
      st a2 defs
      (fn [st a2 [head & body]]
        (let [{:keys [vars required]} (rule-head head)]
          (when (not= (count vars) (count arg-vals))
            (throw (ex-info "Rule invoked with wrong arity"
                            {:rule rule-name, :head head, :args (vec call-args)})))
          (doseq [i (range required)]
            (when (= FREE (nth arg-vals i))
              (throw (ex-info "Rule required-bound variable is not bound at the call"
                              {:rule rule-name, :var (nth vars i)}))))
          (let [seed (reduce (fn [b [hv av]]
                               (if (= FREE av) b (and-then b #(unify % hv av))))
                             (select-keys binding [::dbs '%])
                             (map vector vars arg-vals))]
            (if-not seed
              [st a2]
              (eval-where*
                st body-ctx (vec body) [seed] a2
                (fn [st a2 res]
                  (let [tuple (mapv (fn [hv]
                                      (let [v (get res hv FREE)]
                                        (when (= FREE v)
                                          (throw (ex-info "Rule head var not bound by rule body"
                                                          {:rule rule-name, :var hv})))
                                        v))
                                    vars)
                        tk (binding-key tuple)]
                    (if (contains? (:seen a2) tk)
                      [st a2]
                      (let [a2' (-> a2 (update :seen conj tk) (update :new conj tuple))]
                        (if-let [b' (unify-call binding call-args tuple)]
                          (bind (bounce (fn [] (k st (:acc a2') b')))
                                (fn [[st acc']]
                                  (if (reduced? acc')
                                    [st (reduced (assoc a2' :acc @acc'))]
                                    [st (assoc a2' :acc acc')])))
                          [st a2'])))))))))))))


(defn- rule-fixpoint
  "Evaluate a call whose key is not being evaluated above it: the LEADER of
   its own (possibly recursive) call. Naive fixed point over immutable
   tables: pass i sees the tuples of passes < i; another pass runs only when
   this pass both found new tuples AND a repeated call actually read this
   key's table (so non-recursive and right-recursive-over-new-keys rules cost
   one pass, exactly as before). The least fixed point is reached when a
   pass finds nothing new; the work budget is the backstop when fresh values
   keep generating new tuples. A pass's progress is the state it returns."
  [st ctx binding rule-name call-args arg-vals call-key defs acc k]
  (let [run (fn run [st table acc]
              (let [st (clear-hit st call-key)
                    a2 {:seen (:seen table), :new [], :acc acc}]
                (bind (bounce (fn []
                                (rule-iteration st ctx table defs rule-name call-args
                                                arg-vals binding call-key a2 k)))
                      (fn [[st a2]]
                        (let [stopped? (reduced? a2)
                              a2 (unreduced a2)
                              acc' (:acc a2)]
                          (cond
                            stopped? [st (reduced acc')]
                            (exec/terminal? st) [st acc']
                            (and (seq (:new a2)) (hit? st call-key))
                            (run st {:tuples (into (:tuples table) (:new a2)), :seen (:seen a2)} acc')
                            :else [(clear-hit st call-key) acc']))))))]
    (run st {:tuples [], :seen #{}} acc)))


(defn- emit-rule
  [st ctx clause binding acc k]
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
      (let [st (spend st ctx :rule-entry)]
        (if (exec/terminal? st)
          [st acc]
          (let [arg-vals (mapv #(resolve-binding binding %) call-args)
                ;; keyed by content, so a call on 1.00M is not taken for a
                ;; repeat of the call on 1.0M
                call-key [rule-name (binding-key arg-vals)]
                table (get (::tables ctx) call-key)]
            (if table
              (read-table st ctx table binding call-args call-key acc k)
              (rule-fixpoint st ctx binding rule-name call-args arg-vals call-key
                             defs acc k))))))))


(defn- emit-clause
  [st ctx clause binding acc k]
  (cond (and (seq? clause) (= 'or (first clause))) (emit-or st ctx clause binding acc k)
        (and (seq? clause) (= 'or-join (first clause)))
        (emit-or-join st ctx clause binding acc k)
        (and (seq? clause) (= 'and (first clause)))
        (eval-where* st ctx (rest clause) [binding] acc k)
        (and (seq? clause) (= 'not (first clause)))
        (emit-not st ctx clause binding acc k)
        (and (seq? clause) (= 'not-join (first clause)))
        (emit-not-join st ctx clause binding acc k)
        (seq? clause) (emit-rule st ctx clause binding acc k)
        (and (vector? clause) (seq? (first clause)))
        (emit-fn st ctx clause binding acc k)
        :else (emit-pattern st ctx clause binding acc k)))


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


(defn- validate-clauses!
  "The static checks a clause list always paid at evaluation, whether or not
   an earlier clause produced a binding: disjunction branches bind the same
   variables, and or-join branches bind their join variables."
  [clauses]
  (doseq [clause clauses]
    (when (seq? clause)
      (let [h (first clause)]
        (cond
          (= 'or h) (let [branches (rest clause)]
                      (check-same-var-rule branches)
                      (doseq [b branches] (validate-clauses! (branch-clauses b))))
          (= 'or-join h)
          (let [join-vars (nth clause 1)
                branches (drop 2 clause)]
            (doseq [branch branches]
              (let [branch-vars (set (branch-free-vars (branch-clauses branch)))
                    missing (seq (remove branch-vars join-vars))]
                (when missing
                  (throw (ex-info (str "or-join branch did not bind join var(s): "
                                       (vec missing))
                                  {:unbound (vec missing),
                                   :join-vars join-vars,
                                   :branch branch})))))
            (doseq [b branches] (validate-clauses! (branch-clauses b))))
          (= 'and h) (validate-clauses! (rest clause))
          (= 'not h) (validate-clauses! (rest clause))
          (= 'not-join h) (validate-clauses! (drop 2 clause))
          ;; a rule call: its arguments are variables, constants or _.
          ;; Required-bound variables are declared in the rule HEAD only.
          :else (when (some vector? (rest clause))
                  (throw (ex-info "rule call arguments are flat; a vector is not an argument form (required-bound variables are declared in the rule head)"
                                  {:clause (pr-str clause)}))))))))


(defn- walk
  "Evaluate the (already planned) clause list against one binding, handing
   each complete binding to k. A computation: nothing here calls a clause
   evaluator or k directly."
  [st ctx clauses binding acc k]
  (if-let [cs (seq clauses)]
    (bounce (fn []
              (emit-clause st ctx (first cs) binding acc
                           (fn [st acc b] (walk st ctx (rest cs) b acc k)))))
    (bounce (fn [] (k st acc binding)))))


(defn- eval-where*
  "Evaluate a clause list over a vector of initial bindings, folding every
   resulting binding through k: (k st acc b) -> computation of [st acc]. A
   `reduced` accumulator stops evaluation and is passed on to the caller.
   Returns a computation; `run-comp` drives it."
  [st ctx clauses init-bindings acc k]
  (validate-clauses! clauses)
  (let [planned (vec (plan-where clauses init-bindings))]
    (each-st st acc init-bindings
             (fn [st acc b] (walk st ctx planned b acc k)))))


(defn- build-init-bindings-st
  "The initial bindings of :in, a checked cross product: each produced
   binding is one candidate."
  [st ctx in-patterns inputs dbs]
  (reduce
    (fn [[st acc] [pat val]]
      (if (exec/terminal? st)
        (reduced [st nil])
        (let [expansion (expand-in-binding pat val dbs)
              [st out] (fold-st st []
                                (for [b1 acc b2 expansion] [b1 b2])
                                (fn [st out [b1 b2]]
                                  (let [st (spend st ctx :candidate)]
                                    (if (exec/terminal? st)
                                      [st out]
                                      [st (conj out (merge-bindings b1 b2))]))))]
          (if (exec/terminal? st) (reduced [st nil]) [st out]))))
    [st [{}]]
    (map vector in-patterns inputs)))


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


(defn- host-double
  "A host number as a double, refusing a numeric carrier loudly (the same
   refusal as the arithmetic builtins): no silent conversion of a value the
   host cannot safely evaluate."
  [op x]
  (when (and (cbor/numeric? x) (not (number? x)))
    (throw (ex-info (str "query arithmetic builtin " op
                         " does not accept a numeric carrier")
                    {:fn op, :operand x, :type (type-label x)})))
  (require-numeric! op x)
  (double x))


(defn- numeric-order
  "The total, deterministic order aggregates sort by: exact numeric order
   across every kind (cbor/num-compare), and on a numeric tie the owner's
   min/max tie rule (an exact operand before a float64; else the shorter
   canonical encoding first), so tied kinds order identically on every host
   whatever order the group arrived in. Non-numeric operands are refused."
  [a b]
  (let [c (cbor/num-compare (require-numeric! 'sort a) (require-numeric! 'sort b))]
    (cond (not (zero? c)) c
          (cbor/content= a b) 0
          (cbor/content= (preferred-tie a b) a) -1
          :else 1)))


(defn- sorted-numeric
  [xs]
  (vec (sort numeric-order xs)))


(defn- sqrt-double
  [x]
  #?(:cljd (dart-math/sqrt x)
     :clj (Math/sqrt x)
     :cljs (js/Math.sqrt x)))


(defn- agg-median
  "Median of numeric values: sort by `numeric-order`; an odd count answers
   the middle element itself (its kind preserved); an even count answers the
   double mean of the two middle elements."
  [xs]
  (let [v (sorted-numeric xs)
        n (count v)
        mid (quot n 2)]
    (if (odd? n)
      (nth v mid)
      (let [a (host-double 'median (nth v (dec mid)))
            b (host-double 'median (nth v mid))]
        (/ (+ a b) 2.0)))))


(defn- agg-variance
  "POPULATION variance (the sum of squared deviations from the mean divided
   by n), a double. Values are converted to doubles and folded in `numeric-order`
   so the evaluation order, and therefore every IEEE-754 rounding step, is
   fixed: addition, subtraction, multiplication and division are correctly
   rounded on every host, so the result is the same double on all of them for
   the same inputs. A single value has variance 0.0."
  [xs]
  (let [ds (mapv #(host-double 'variance %) (sorted-numeric xs))
        n (count ds)
        mean (/ (reduce + 0.0 ds) n)
        ss (reduce (fn [acc d] (let [e (- d mean)] (+ acc (* e e)))) 0.0 ds)]
    (/ ss n)))


(def ^:private aggregate-fns
  "name -> {:arities #{counts of arguments incl. parameters}, :f (fn [params
   xs])}. `params` are the literal leading arguments (only min and max take
   one: the positive count n)."
  {'count {:arities #{1}, :f (fn [_ xs] (count xs))},
   'count-distinct {:arities #{1}, :f (fn [_ xs] (count (content-distinct xs)))},
   'sum {:arities #{1}, :f (fn [_ xs] (reduce (host-arithmetic 'sum +) 0 xs))},
   'min {:arities #{1 2},
         :f (fn [[n] xs]
              (if n
                (vec (take n (sorted-numeric xs)))
                (reduce portable-min xs)))},
   'max {:arities #{1 2},
         :f (fn [[n] xs]
              (if n
                (vec (take n (reverse (sorted-numeric xs))))
                (reduce portable-max xs)))},
   'avg {:arities #{1},
         :f (fn [_ xs]
              (double (/ (reduce (host-arithmetic 'avg +) 0 xs) (count xs))))},
   'distinct {:arities #{1}, :f (fn [_ xs] (set (content-distinct xs)))},
   'median {:arities #{1}, :f (fn [_ xs] (agg-median xs))},
   'variance {:arities #{1}, :f (fn [_ xs] (agg-variance xs))},
   'stddev {:arities #{1}, :f (fn [_ xs] (sqrt-double (agg-variance xs)))}})


(defn- parse-aggregate
  "Parse `(name arg)` or `(min|max n arg)`: the parameters are preserved
   (the leading literals), the arity validated against the aggregate's
   declared arities, and a parameter must be a positive integer."
  [el]
  (let [[agg-sym & args] el
        spec (get aggregate-fns agg-sym)]
    (when-not spec
      (throw (ex-info "Unknown aggregate in :find" {:aggregate agg-sym})))
    (when-not (contains? (:arities spec) (count args))
      (throw (ex-info "Aggregate arity does not match its definition"
                      {:aggregate agg-sym, :arity (count args)})))
    (let [arg (last args)
          params (vec (butlast args))]
      (when (and (seq? arg) (= 'pull (first arg)))
        (throw (ex-info "pull cannot be used as an aggregate argument"
                        {:element el})))
      (when-not (symbol? arg)
        (throw (ex-info "aggregate argument must be a query variable"
                        {:aggregate agg-sym})))
      (doseq [p params]
        (when-not (and (integer? p) (pos? p))
          (throw (ex-info "aggregate parameter must be a positive integer"
                          {:aggregate agg-sym, :parameter p}))))
      {:agg (fn [xs] ((:f spec) params xs)), :arg arg, :agg-name agg-sym, :agg-params params})))


(defn- parse-find-element
  [el]
  (cond (and (seq? el) (= 'pull (first el)))
        (let [[_ pull-var pull-pattern] el]
          (when-not (symbol? pull-var)
            (throw (ex-info "pull find element requires a variable"
                            {:element el})))
          {:pull-var pull-var, :pull-pattern pull-pattern})
        (seq? el) (parse-aggregate el)
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


(defn- check-declared-symbols
  "Refuse a bare symbol where `:in` or `:find` declares a term. Under the
   term rule a bare symbol is a constant, so there it would silently bind
   no variable or project nil. `:in` also admits the blank `_`, a
   `$`-source, the `%` rule set and the `...` collection marker."
  [in find]
  (let [refuse (fn [clause sym]
                 (throw (ex-info (str "A bare symbol in " clause
                                      " is not a query variable: " sym
                                      " (only ?-symbols are variables)")
                                 {:clause clause, :symbol sym})))]
    (doseq [pattern in]
      (if (symbol? pattern)
        (when-not (or (query-var-symbol? pattern) (db-sym? pattern)
                      (= '% pattern) (= '_ pattern))
          (refuse :in pattern))
        (doseq [s (filter symbol? (tree-seq coll? seq pattern))]
          (when-not (or (query-var-symbol? s) (= '_ s) (= '... s))
            (refuse :in s)))))
    (doseq [element (:find-vars (parse-find find))]
      (let [{:keys [var arg pull-var]} (parse-find-element element)]
        (doseq [s [var arg pull-var]]
          (when (and (symbol? s) (not (query-var-symbol? s)))
            (refuse :find s)))))))


(defn- clause-bound-vars
  "The ?variables a where clause can bind (conservative: a variable that a
   clause merely tests, such as one inside `not`, does not count)."
  [clause]
  (cond
    (vector? clause)
    (filter query-var-symbol?
            (tree-seq coll? seq (if (seq? (first clause)) (rest clause) clause)))
    (seq? clause)
    (let [h (first clause)]
      (cond (contains? #{'not 'not-join} h) nil
            (= 'or h) (mapcat #(mapcat clause-bound-vars (branch-clauses %)) (rest clause))
            (= 'or-join h) (filter query-var-symbol? (nth clause 1))
            (= 'and h) (mapcat clause-bound-vars (rest clause))
            ;; a rule call: variables among its arguments (vectors included,
            ;; so a misplaced required-bound vector reaches the flat-call
            ;; refusal instead of reading as an unbound find variable)
            :else (filter query-var-symbol? (tree-seq coll? seq (rest clause)))))
    :else nil))


(defn- check-find-vars-bound!
  "Static refusal (E3): every variable the :find, :with and aggregate
   arguments name must be bound by :in or by some :where clause; otherwise it
   would silently project nil."
  [in find with where]
  (let [bound (-> #{}
                  (into (filter query-var-symbol?) (tree-seq coll? seq (vec in)))
                  (into (mapcat clause-bound-vars) where))
        used (concat with
                     (mapcat (fn [element]
                               (let [{:keys [var arg pull-var]} (parse-find-element element)]
                                 (filter query-var-symbol? [var arg pull-var])))
                             (:find-vars (parse-find find))))]
    (doseq [v used]
      (when-not (contains? bound v)
        (throw (ex-info (str "Find variable is not bound by :in or :where: " v)
                        {:var v}))))))


(defn- collect-rule-calls
  "[name negated?] pairs of the rule calls inside clauses; negated? is true
   inside not / not-join at any depth."
  [clauses negated?]
  (mapcat
    (fn [clause]
      (if-not (seq? clause)
        nil
        (let [h (first clause)]
          (cond
            (= 'not h) (collect-rule-calls (rest clause) true)
            (= 'not-join h) (collect-rule-calls (drop 2 clause) true)
            (= 'and h) (collect-rule-calls (rest clause) negated?)
            (= 'or h) (mapcat #(collect-rule-calls (branch-clauses %) negated?) (rest clause))
            (= 'or-join h) (mapcat #(collect-rule-calls (branch-clauses %) negated?)
                                   (drop 2 clause))
            (symbol? h) [[h negated?]]
            :else nil))))
    clauses))


(defn- check-rules-stratified!
  "Refuse, before any evaluation, recursion THROUGH negation: a rule that
   calls (inside not / not-join) a rule from which it is reachable, itself
   included. Positive recursion is evaluated to its least fixed point;
   negation inside a cycle has no such meaning here and is refused."
  [rules]
  (when (sequential? rules)
    (let [named (filter #(and (sequential? %) (seq? (first %))) rules)
          calls (reduce (fn [m rule]
                          (update m (first (first rule)) (fnil into [])
                                  (collect-rule-calls (rest rule) false)))
                        {}
                        named)
          reach (fn [start]
                  (loop [seen #{start}
                         frontier [start]]
                    (if (empty? frontier)
                      seen
                      (let [nxt (->> frontier
                                     (mapcat #(map first (get calls %)))
                                     (remove seen)
                                     distinct)]
                        (recur (into seen nxt) (vec nxt))))))]
      (doseq [[rule-name edges] calls
              [callee negated?] edges
              :when negated?]
        (when (contains? (reach callee) rule-name)
          (throw (ex-info (str "recursion through negation is not supported: rule "
                               rule-name " reaches itself through a negated call to "
                               callee)
                          {:reason :recursion-through-negation
                           :rule rule-name
                           :through callee})))))))


(defn- project-element-st
  [st ctx element b]
  (if (:pull-var element)
    (let [eid (get b (:pull-var element))
          idx (resolve-fact-index b '$)]
      (when-not idx
        (throw (ex-info "pull find element requires a bound $ source"
                        {:element element})))
      (pull-idx st ctx idx eid (:pull-pattern element)))
    [st (get b (:var element))]))


(defn- project-row-st
  [st ctx elements b]
  (fold-st st [] elements
           (fn [st row element]
             (let [[st v] (project-element-st st ctx element b)]
               [st (conj row v)]))))


(defn- add-distinct
  "Add row to acc's rows unless a content-equal one was already kept."
  [acc row]
  (let [key (binding-key row)]
    (if (contains? (:seen acc) key)
      acc
      (-> acc (update :seen conj key) (update :rows conj row)))))


(defn- relation-result-st
  "The q result: [state set-of-find-tuples]. Rows, groups and tuples are
   deduplicated kind-strictly (content-key) before the set is built. The set
   itself still compares members with host =, so on a host where two
   content-distinct tuples are host = and hash alike (the JVM for 1.0M/1.00M
   and 0.0/-0.0, Dart for 0.0/-0.0) they remain one member of the returned
   set. Evaluation is folded into projection: no binding is retained beyond
   its (deduplicated) projected row."
  [st ctx find with where init-bindings]
  (let [elements (mapv parse-find-element find)]
    (if (not-any? :agg elements)
      (let [[st acc] (run-comp
                       (eval-where*
                         st ctx where init-bindings {:seen #{}, :rows []}
                         (fn [st acc b]
                           (let [st (spend st ctx :output-row)]
                             (if (exec/terminal? st)
                               [st acc]
                               (let [[st row] (project-row-st st ctx elements b)]
                                 [st (if (exec/terminal? st) acc (add-distinct acc row))]))))))]
        [st (when-not (exec/terminal? st) (into #{} (:rows acc)))])
      (let [grouping-vars (filterv some?
                                   (mapv #(or (:var %) (:pull-var %)) elements))
            ;; proj-vars are query variable symbols, which host distinct
            ;; already compares correctly
            proj-vars (-> grouping-vars
                          (into with)
                          (into (keep :arg elements))
                          distinct)
            keep-keys (conj (vec proj-vars) ::dbs)
            [st acc] (run-comp
                       (eval-where*
                         st ctx where init-bindings {:seen #{}, :rows []}
                         (fn [st acc b]
                           (let [st (spend st ctx :aggregate-input)]
                             (if (exec/terminal? st)
                               [st acc]
                               [st (add-distinct acc (select-keys b keep-keys))])))))]
        (if (exec/terminal? st)
          [st nil]
          (let [groups (vals (group-by (fn [row]
                                         (cbor/content-key
                                           (mapv #(get row %) grouping-vars)))
                                       (:rows acc)))
                [st out] (fold-st
                           st {:seen #{}, :rows []} groups
                           (fn [st out group]
                             (let [st (spend st ctx :output-row)]
                               (if (exec/terminal? st)
                                 [st out]
                                 (let [[st row]
                                       (fold-st
                                         st [] elements
                                         (fn [st row element]
                                           (let [{:keys [agg arg]} element]
                                             (if agg
                                               (let [v (agg (map #(get % arg) group))
                                                     st (observe-st st ctx)]
                                                 [st (conj row v)])
                                               (let [[st v] (project-element-st
                                                              st ctx element (first group))]
                                                 [st (conj row v)])))))]
                                   [st (if (exec/terminal? st) out (add-distinct out row))])))))]
            [st (when-not (exec/terminal? st) (into #{} (:rows out)))]))))))


(defn q-with-state
  "The state-passing form of `q`: runs the one evaluation kernel under the
   invocation's execution state st (dao.space.query.execution) and driver,
   and returns [state result]. The result is the same local result value `q`
   returns; it is meaningful only while the returned state is live, and is
   nil once the state is terminal (a terminal return exposes no partial
   result). Names no Client option. Opens nothing, closes nothing."
  [st driver query & inputs]
  (let [ctx {::driver driver}
        st (enter-st st ctx :parse)
        {:keys [find in with where keys syms strs]} (normalize-query query)
        in-patterns (or in '[$])
        _ (check-declared-symbols in-patterns find)
        _ (check-find-vars-bound! in-patterns find with where)
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
        _ (doseq [[p v] (map vector in-patterns bind-inputs)]
            (when (= '% p) (check-rules-stratified! v)))
        ctx (assoc ctx
                   :fns (if (false? (:builtins opts))
                          (or (:fns opts) {})
                          (merge builtins (:fns opts))))
        st (spend-n st ctx :parse-step
                    (+ 1 (count find) (count in-patterns) (count with) (count where)))]
    (if (exec/terminal? st)
      [st nil]
      (let [st (enter-st st ctx :realize)
            [st dbs] (if (exec/terminal? st)
                       [st nil]
                       (reduce
                         (fn [[st dbs] [pat val]]
                           (if (or (exec/terminal? st)
                                   (not= :db (classify-in-pattern pat)))
                             [st dbs]
                             (let [[st realized] (realize-st st ctx val)]
                               [st (assoc dbs pat
                                          (select-keys realized [::relation ::fact-index]))])))
                         [st {}]
                         (map vector in-patterns bind-inputs)))
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
                                        rm-keys))]
        (if (exec/terminal? st)
          [st nil]
          (let [st (enter-st st ctx :join)
                [st init-bindings] (if (exec/terminal? st)
                                     [st nil]
                                     (build-init-bindings-st st ctx in-patterns bind-inputs dbs))
                [st relation] (if (exec/terminal? st)
                                [st nil]
                                (relation-result-st st ctx (:find-vars parsed) with
                                                    where init-bindings))
                st (if (exec/terminal? st) st (enter-st st ctx :output))]
            (if (exec/terminal? st)
              [st nil]
              [st {:dao.space.query/result relation
                   :spec (:spec parsed)
                   :return-map-key rm-key
                   :return-map-keys rm-keys}])))))))


(defn q
  "Datalog: (q query & inputs) where $ binds to the first input. Each
   database input is a query value (a relation value, a datom view over one,
   or an opened published index); scalar/tuple/coll/relation :in bindings
   are plain data. Returns a local result value carrying the find spec;
   `collect` materializes it into the find shapes. Opens nothing, closes
   nothing: the caller owns every handle. Runs the same evaluation kernel as
   `q-with-state` under the explicit unbounded, never-expiring policy."
  [query & inputs]
  (second (apply q-with-state exec/unbounded nil query inputs)))


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
