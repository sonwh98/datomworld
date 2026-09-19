(ns dao.lease
  "DaoLease: a lease is a grant that lapses unless renewed.

  A lease adds no operation to any contract and no key to any DaoStream
  result map. This namespace is the fact vocabulary and the judge: facts are
  plain maps on ordinary streams, classified by two dispatch keys --
  `:dao.lease/status` for the negotiation, `:dao.lease/event` for evidence --
  and the judge is a single effectful, state-threaded step in the
  `dao.stream.forward/forward-step` shape. The holder discipline --
  observe the grant before acting, renew at strictly less than half the
  duration, stop at the bound, release when done -- is a set of pure
  functions over a small threaded state that the holder's own control
  flow calls and drives; the composition constructors join this
  namespace when they are built.

  A fact carrying neither dispatch key is not a lease fact and is ignored by
  a reader; a fact carrying both is defective and establishes nothing. A
  structurally defective fact (V4) establishes nothing either; the
  history-dependent rules -- a second answer to one proposal, a repeated
  status for one lease -- are admissibility (V7), decided by the judge's
  drain against its ledger, and the gap, release and answer branches never
  see a fact that failed either gate.

  The judge keeps, privately, a ledger of live leases and runs the six-step
  pass of the design's *The pass* section. It owns no scheduler, callback,
  registry or mutable state of its own, but it is not pure: it reads
  transport handles, invokes the reclaim effect, and appends `:lapsed`.
  Time reaches it only as `:dao.lease/reading` data drained from
  composition-wired tick cursors, never a clock call. Attribution comes from
  the composition's resolver bound to each wired source, never from a
  fact's own claim about its author. The ledger is not rebuildable from any
  stream: it is state threaded through `judge-step` that a composition
  either persists or recovers by `restart`.

  The holder keeps no ledger and owns no clock. Its state is the observed
  grant, the readings the grant was observed and the last renewal was
  appended at, and a stopped flag; time reaches it only as a reading the
  control flow drained from the holder's own tick cursor, and a renewal
  advances a bound only as an append that answered `:dao.stream/ok`
  before the bound was reached -- the bound is terminal, and once reached
  it is latched shut. Nothing here renews on a holder's behalf or
  performs a reclaim, and the holder's bound bounds attention, not
  access: a holder needing exclusion obtains it from the resource.

  `dao.lease` requires `dao.stream` and never the reverse."
  (:require [dao.stream :as stream]))


;; =============================================================================
;; Units and duration arithmetic
;; =============================================================================

(def magnitude-limit
  "Upper bound on a duration's base magnitude: 2^52. `duration?`/`tolerance?`
  bound the raw magnitude here, and against a supplied unit table the
  structural gate additionally bounds n by `(quot magnitude-limit
  unit-magnitude)` -- a division, so the check itself cannot overflow.
  Every product the arithmetic forms is then at most 2^52 and every sum at
  most 2^53: exact as a JVM long, exactly representable below ClojureScript's
  2^53 integer precision, and far from Dart's int64 wrap. No host can
  overflow, wrap or lose precision in an ordering, and no untrusted fact can
  wedge the judge on arithmetic -- while a millisecond reading stays legal
  for about 140,000 years, so the bound never silently ends a judge's life."
  4503599627370496)


(def default-units
  "Reference unit table: unit keyword -> magnitude in a shared base. The value
  is a composition's interoperability decision, fixed once and shared by its
  grantor, holders and judge; this placeholder exists so tests and examples
  have one. Magnitudes must be commensurate: normalizing to a finer unit
  divides exactly or the table is a composition defect, rejected at assembly
  by `initial-judge`."
  {:ms 1 :s 1000})


(defn duration?
  "True for a single-entry map from a unit keyword to a positive integer at
  or below `magnitude-limit` in raw magnitude -- the shared shape of
  `:dao.lease/duration`, `:dao.lease/max` and `:dao.lease/reading`. Against
  a unit table the binding bound is per-unit and tighter (see
  `defective?`)."
  [x]
  (and (map? x)
       (= 1 (count x))
       (let [k (first (keys x))]
         (and (keyword? k)
              (let [v (get x k)]
                (and (integer? v)
                     (pos? v)
                     (<= v magnitude-limit)))))))


(defn tolerance?
  "duration? permitting a zero magnitude, as a judge's tolerance may be zero."
  [x]
  (and (map? x)
       (= 1 (count x))
       (let [k (first (keys x))]
         (and (keyword? k)
              (let [v (get x k)]
                (and (integer? v)
                     (not (neg? v))
                     (<= v magnitude-limit)))))))


(def causes
  "The closed set of reclaim causes."
  #{:silence :release :cap :policy})


(defn cause?
  "True for one of the four reclaim causes."
  [x]
  (contains? causes x))


(defn- magnitude-entry
  "The [unit magnitude] pair of a single-entry {unit non-negative-integer}
  map, or nil. Arithmetic admits zero magnitudes because a computed interval
  can be zero; the fact predicates above do not."
  [d]
  (when (map? d)
    (when (= 1 (count d))
      (let [k (first (keys d))
            v (get d k)]
        (when (and (keyword? k) (integer? v) (not (neg? v)))
          [k v])))))


(defn- unit-magnitude
  [units unit]
  (or (get units unit)
      (throw (ex-info "dao.lease: unit absent from the composition's unit table"
                      {:unit unit}))))


(defn- finer-unit
  "The one of two units with the smaller base magnitude."
  [units unit-a unit-b]
  (if (< (unit-magnitude units unit-a) (unit-magnitude units unit-b))
    unit-a
    unit-b))


(defn normalize
  "Express duration in target-unit of the composition's table, exactly.
  Throws when the division is inexact -- the table is then incommensurate,
  which is a composition defect, not a lease fact."
  [units duration target-unit]
  (let [[u n] (magnitude-entry duration)
        base (* n (unit-magnitude units u))
        m (unit-magnitude units target-unit)
        r (rem base m)]
    (when-not (zero? r)
      (throw (ex-info "dao.lease: duration does not normalize exactly to the target unit"
                      {:duration duration
                       :target-unit target-unit
                       :remainder r})))
    {target-unit (quot base m)}))


(defn compare-durations
  "Strict three-way comparison of two durations, -1/0/1. Comparison is exact
  in base magnitudes -- no division -- and strict: an interval exactly equal
  to its bound has not yet passed it. Fact-carried magnitudes are bounded by
  `magnitude-bound`, so the products here stay small on every host."
  [units a b]
  (let [[ua na] (magnitude-entry a)
        [ub nb] (magnitude-entry b)
        base-a (* na (unit-magnitude units ua))
        base-b (* nb (unit-magnitude units ub))]
    (cond
      (< base-a base-b) -1
      (> base-a base-b) 1
      :else 0)))


(defn exceeds?
  "True when interval is strictly past bound."
  [units interval bound]
  (pos? (compare-durations units interval bound)))


(defn add-duration
  "The sum of two durations, expressed in their finer unit."
  [units a b]
  (let [[ua _na] (magnitude-entry a)
        [ub _nb] (magnitude-entry b)
        f (finer-unit units ua ub)]
    {f (+ (get (normalize units a f) f)
          (get (normalize units b f) f))}))


(defn interval
  "The duration from earlier to later, expressed in their finer unit and
  clamped at zero. Readings never decrease on one stream; the clamp only
  defends a composition wiring inconsistent tick sources."
  [units later earlier]
  (let [[ul _nl] (magnitude-entry later)
        [ue _ne] (magnitude-entry earlier)
        f (finer-unit units ul ue)
        a (get (normalize units later f) f)
        b (get (normalize units earlier f) f)]
    {f (max 0 (- a b))}))


;; =============================================================================
;; The facts
;; =============================================================================

(def status-key :dao.lease/status)
(def event-key :dao.lease/event)


(def statuses
  "The closed set of `:dao.lease/status` values."
  #{:dao.lease/proposed
    :dao.lease/accepted
    :dao.lease/rejected
    :dao.lease/released
    :dao.lease/lapsed})


(def events
  "The closed set of `:dao.lease/event` values."
  #{:dao.lease/renewal
    :dao.lease/tick})


(defn lease-fact?
  "True when fact carries exactly one of the two dispatch keys. A fact
  carrying neither is not a lease fact and is ignored by a reader; a fact
  carrying both is defective."
  [fact]
  (boolean
    (and (map? fact)
         (let [s (some? (get fact status-key))
               e (some? (get fact event-key))]
           (and (or s e) (not (and s e)))))))


(defn- vconj
  "conj a maybe-defect onto the defect list, skipping nil."
  [defects defect]
  (if (nil? defect) defects (conj defects defect)))


(defn- missing-key
  [fact key defect]
  (when (nil? (get fact key)) defect))


(defn- bad-shape
  [fact key pred defect]
  (when (and (contains? fact key) (not (pred (get fact key)))) defect))


(defn- foreign-unit
  "Against a supplied unit table, a duration key outside the table is a
  structural defect: the composition fixed its units once, and a fact in a
  foreign unit is not readable by it. With no table supplied there is
  nothing to be foreign to."
  [fact key units defect]
  (when (and (some? units) (map? (get fact key)) (= 1 (count (get fact key))))
    (let [k (first (keys (get fact key)))]
      (when (nil? (get units k)) defect))))


(defn- or-false
  [defects]
  (if (empty? defects) false defects))


(defn- base-over-bound
  "Against a supplied unit table, a single-entry magnitude whose base value
  would exceed `magnitude-limit`: n past `(quot magnitude-limit
  unit-magnitude)` for a unit the table knows. The bound is taken by
  division, so the check itself cannot overflow."
  [fact key units defect]
  (when (and (some? units)
             (map? (get fact key))
             (= 1 (count (get fact key))))
    (let [k (first (keys (get fact key)))
          v (get (get fact key) k)
          m (get units k)]
      (when (and (some? m) (integer? v) (> v (quot magnitude-limit m)))
        defect))))


(defn- universal-shape-defects
  "Shape defects decidable for ANY fact carrying the keys, whatever its
  status or event: the contract's Validity sentence is not limited by
  status, so a :rejected carrying a malformed duration is as defective as
  an :accepted carrying one."
  [fact units]
  (-> []
      (vconj (bad-shape fact :dao.lease/duration duration? :invalid-duration))
      (vconj (bad-shape fact :dao.lease/max duration? :invalid-cap))
      (vconj (bad-shape fact :dao.lease/reading duration? :invalid-reading))
      (vconj (foreign-unit fact :dao.lease/duration units :invalid-duration))
      (vconj (foreign-unit fact :dao.lease/max units :invalid-cap))
      (vconj (foreign-unit fact :dao.lease/reading units :invalid-reading))
      (vconj (base-over-bound fact :dao.lease/duration units :invalid-duration))
      (vconj (base-over-bound fact :dao.lease/max units :invalid-cap))
      (vconj (base-over-bound fact :dao.lease/reading units :invalid-reading))))


(defn- status-defects
  [fact status]
  (case status
    :dao.lease/proposed
    (or-false
      (-> []
          (vconj (missing-key fact :dao.lease/proposal :missing-proposal-id))
          (vconj (missing-key fact :dao.lease/subject :missing-subject))
          (vconj (when (some? (get fact :dao.lease/lease)) :lease-id-on-proposal))))

    :dao.lease/accepted
    (or-false
      (-> []
          (vconj (missing-key fact :dao.lease/lease :missing-lease-id))
          (vconj (missing-key fact :dao.lease/subject :missing-subject))
          (vconj (missing-key fact :dao.lease/holder :missing-holder))
          (vconj (missing-key fact :dao.lease/duration :missing-duration))))

    :dao.lease/rejected
    (or-false
      (-> []
          (vconj (missing-key fact :dao.lease/proposal :missing-proposal-id))))

    :dao.lease/released
    (or-false
      (-> []
          (vconj (missing-key fact :dao.lease/lease :missing-lease-id))))

    :dao.lease/lapsed
    (or-false
      (-> []
          (vconj (missing-key fact :dao.lease/lease :missing-lease-id))
          (vconj (missing-key fact :dao.lease/cause :missing-cause))
          (vconj (when (and (contains? fact :dao.lease/cause)
                            (not (cause? (get fact :dao.lease/cause))))
                   :invalid-cause))))

    [:unknown-status]))


(defn- event-defects
  [fact event]
  (case event
    :dao.lease/renewal
    (or-false
      (-> []
          (vconj (missing-key fact :dao.lease/lease :missing-lease-id))))

    :dao.lease/tick
    (or-false
      (-> []
          (vconj (missing-key fact :dao.lease/reading :missing-reading))))

    [:unknown-event]))


(defn defective?
  "The structural validity gate (V4), pure in the fact alone. ([fact]
  [fact units]) Returns false, or a truthy vector of defect keywords naming
  every structural defect the fact carries: a key its status or event
  requires is missing (nil-valued counts as missing); the status or event is
  outside the tables; a duration, cap or reading -- on ANY fact carrying
  them, whatever its status or event -- is not a single-entry {unit
  positive-integer} map within `magnitude-bound`, or, against a supplied
  unit table, carries a unit outside it; a lease id on a proposal; both
  dispatch keys at once.

  The history-dependent rules -- a second answer to an already-answered
  proposal, a repeated :accepted/:released/:lapsed for one lease -- are not
  structural and live in `admissible?`. A fact carrying neither dispatch key
  is not a lease fact and is not defective here; a reader partitions with
  `lease-fact?` first. A non-map is likewise not a lease fact. Nothing but
  the arguments is consulted."
  ([fact] (defective? fact nil))
  ([fact units]
   (if (map? fact)
     (let [s (get fact status-key)
           e (get fact event-key)]
       (cond
         (and (some? s) (some? e)) [:both-dispatch-keys]
         ;; neither dispatch key: not a lease fact, and not defective
         (and (nil? s) (nil? e)) false
         :else (let [branch (if (some? s)
                              (status-defects fact s)
                              (event-defects fact e))
                     merged (vec (distinct (into (if (vector? branch) branch [])
                                                 (universal-shape-defects
                                                   fact units))))]
                 (or-false merged))))
     false)))


(defn valid?
  "True when fact is a well-formed lease fact: it carries exactly one
  dispatch key and `defective?` finds no structural defect."
  ([fact] (valid? fact nil))
  ([fact units]
   (and (lease-fact? fact) (not (defective? fact units)))))


(defn stale-reading?
  "The pure half of the stale-reading rule: true when reading is older than
  an explicitly supplied prior. Readings are monotonic on one stream, so
  staleness is judged only where a tick is evidence -- against the reading
  basis of that tick stream -- never against an arbitrary fact from an
  untrusted author. Admissibility of the history-dependent rules is
  `admissible?`'s to decide."
  [units reading prior]
  (and (duration? reading)
       (duration? prior)
       (neg? (compare-durations units reading prior))))


;; =============================================================================
;; Fact constructors
;; =============================================================================

(defn- check-assembly!
  [ok message data]
  (when-not ok
    (throw (ex-info (str "dao.lease: " message) data))))


(defn- check-units!
  "The unit table is a composition decision fixed once; a table that cannot
  order durations exactly on every host is an assembly defect, not a
  runtime one."
  [units]
  (check-assembly! (map? units)
                   "the unit table is a map of unit keyword to magnitude"
                   {:units units})
  (check-assembly! (pos? (count units))
                   "the unit table names at least one unit; an empty table
                    has no basis to order readings"
                   {:units units})
  (doseq [entry units]
    (let [u (first entry)
          m (second entry)]
      (check-assembly! (and (keyword? u)
                            (integer? m)
                            (pos? m)
                            (<= m magnitude-limit))
                       "each unit magnitude is a positive integer within the bound"
                       {:unit u :magnitude m})))
  (let [magnitudes (vals units)]
    (doseq [a magnitudes
            b magnitudes]
      (when (> a b)
        (check-assembly! (zero? (rem a b))
                         "the unit table is commensurate: each magnitude divides each larger one"
                         {:larger a :smaller b})))))


(defn proposal
  "A holder's proposal. `ask` must be a map; it may carry
  `:dao.lease/duration`, the holder's ask, which the grantor is free to
  ignore."
  ([proposal-id subject] (proposal proposal-id subject nil))
  ([proposal-id subject ask]
   (check-assembly! (some? proposal-id)
                    "a proposal needs its minted identity"
                    {:key :dao.lease/proposal})
   (check-assembly! (some? subject)
                    "a proposal needs a subject"
                    {:key :dao.lease/subject})
   (check-assembly! (or (nil? ask) (map? ask))
                    "a proposal's ask is a map of optional keys"
                    {:key :ask :value ask})
   (check-assembly! (nil? (get ask :dao.lease/lease))
                    "a proposal carries no lease id; a lease does not exist yet"
                    {:key :dao.lease/lease})
   (when (and (map? ask) (contains? ask :dao.lease/duration))
     (check-assembly! (duration? (get ask :dao.lease/duration))
                      "a proposal's duration ask must be a single-entry {unit positive-integer} map within the bound"
                      {:key :dao.lease/duration :value (get ask :dao.lease/duration)}))
   (cond-> {:dao.lease/status :dao.lease/proposed
            :dao.lease/proposal proposal-id
            :dao.lease/subject subject}
     (and (map? ask) (contains? ask :dao.lease/duration))
     (assoc :dao.lease/duration (get ask :dao.lease/duration)))))


(defn grant
  "A grantor's grant, minting tenure. `opts` may carry `:dao.lease/proposal`
  when answering one and `:dao.lease/max`, the cap on total tenure. A grant
  carries subject and holder outright, so a reader of the grant alone knows
  what was granted to whom."
  ([lease-id subject holder duration] (grant lease-id subject holder duration nil))
  ([lease-id subject holder duration opts]
   (check-assembly! (some? lease-id)
                    "a grant needs its minted identity"
                    {:key :dao.lease/lease})
   (check-assembly! (some? subject)
                    "a grant needs a subject"
                    {:key :dao.lease/subject})
   (check-assembly! (some? holder)
                    "a grant needs a holder"
                    {:key :dao.lease/holder})
   (check-assembly! (duration? duration)
                    "a grant's duration must be a single-entry {unit positive-integer} map within the bound"
                    {:key :dao.lease/duration :value duration})
   (when (and (map? opts) (contains? opts :dao.lease/proposal))
     (check-assembly! (some? (get opts :dao.lease/proposal))
                      "an answering grant carries the proposal identity it answers"
                      {:key :dao.lease/proposal}))
   (when (and (map? opts) (contains? opts :dao.lease/max))
     (check-assembly! (duration? (get opts :dao.lease/max))
                      "a grant's max must be a single-entry {unit positive-integer} map within the bound"
                      {:key :dao.lease/max :value (get opts :dao.lease/max)}))
   (cond-> {:dao.lease/status :dao.lease/accepted
            :dao.lease/lease lease-id
            :dao.lease/subject subject
            :dao.lease/holder holder
            :dao.lease/duration duration}
     (and (map? opts) (some? (get opts :dao.lease/proposal)))
     (assoc :dao.lease/proposal (get opts :dao.lease/proposal))
     (and (map? opts) (contains? opts :dao.lease/max))
     (assoc :dao.lease/max (get opts :dao.lease/max)))))


(defn refusal
  "A grantor's refusal answering one proposal."
  [proposal-id]
  (check-assembly! (some? proposal-id)
                   "a refusal answers a proposal identity"
                   {:key :dao.lease/proposal})
  {:dao.lease/status :dao.lease/rejected
   :dao.lease/proposal proposal-id})


(defn release
  "A holder's release: done with the resource. The holder stops its own
  activity and authors no `:lapsed`; the grantor still reclaims and records."
  [lease-id]
  (check-assembly! (some? lease-id)
                   "a release carries its lease identity"
                   {:key :dao.lease/lease})
  {:dao.lease/status :dao.lease/released
   :dao.lease/lease lease-id})


(defn lapsed
  "A grantor's reclaim record. `:lapsed` does not cross a boundary; it is the
  grantor's record on the grantor's stream."
  [lease-id cause]
  (check-assembly! (some? lease-id)
                   "a lapsed record carries its lease identity"
                   {:key :dao.lease/lease})
  (check-assembly! (cause? cause)
                   "a lapsed record carries one of the four causes"
                   {:key :dao.lease/cause :value cause})
  {:dao.lease/status :dao.lease/lapsed
   :dao.lease/lease lease-id
   :dao.lease/cause cause})


(defn renewal
  "A holder's renewal: evidence about its author, observed not when sent but
  when drained."
  [lease-id]
  (check-assembly! (some? lease-id)
                   "a renewal carries its lease identity"
                   {:key :dao.lease/lease})
  {:dao.lease/event :dao.lease/renewal
   :dao.lease/lease lease-id})


(defn tick
  "An adapter's tick: the only form time takes inside lease code. The
  reference tick producer is this constructor plus a host driver outside
  this namespace; nothing here installs a timer."
  [reading]
  (check-assembly! (duration? reading)
                   "a tick carries a single-entry {unit positive-integer} reading within the bound"
                   {:key :dao.lease/reading :value reading})
  {:dao.lease/event :dao.lease/tick
   :dao.lease/reading reading})


(defn mint-lease-id
  "A fresh lease identity. The contract requires only that the grantor mint
  ids and never reuse one within itself; a host-neutral uuid string is the
  reference form. A composition may mint its own."
  []
  (str (random-uuid)))


;; =============================================================================
;; The judge
;; =============================================================================
;;
;; The judge is one effectful, state-threaded step, `judge-step`, in the
;; `dao.stream.forward/forward-step` shape: it owns no scheduler, callback,
;; registry or mutable state of its own, but it is not pure -- it reads
;; transport handles, invokes the reclaim effect, and appends :lapsed. *Now*
;; is always the newest reading drained from the wired tick cursors, never a
;; clock call. The ledger is not rebuildable from any stream; a composition
;; persists it or recovers by `restart`.

(def default-drain-budget
  "Reference per-cursor drain budget: each wired cursor yields at most this
  many elements (a gap-resume counts as one, as `forward-step` counts its
  resumes) before the remainder is left to the next pass. The value is a
  composition decision; ticks are monotonic so a bounded tick drain is
  late, not wrong, while a bounded FACT drain additionally suppresses the
  :silence clause for the leases it could have carried (see
  `silence-suppressed`)."
  256)


(defn initial-judge
  "Initial judge state from a config map of injected seams:

  :units         the composition's unit table (default `default-units`);
                 validated here -- magnitudes positive, bounded and pairwise
                 commensurate -- so no mid-pass arithmetic can throw on it
  :tolerance     a duration map, possibly zero; nil means zero
  :drain-budget  per-cursor elements drained per pass (default
                 `default-drain-budget`); must be a positive integer
  :resolver      (fn [source fact] -> author), the composition's
                 attribution, called with the source each cursor was wired
                 with -- per-author media, an envelope key, or a transport's
                 attachment identity. A fact's own claim about its author is
                 never consulted
  :reclaim       (fn [subject] -> success?), idempotent, reporting; a throw
                 counts as \"did not report\"
  :policy        optional (fn [ledger-entry now] -> boolean); truth ends the
                 lease with cause :policy. Eligibility precedes policy:
                 eligible renewals are applied in the drain whatever the
                 policy decides, and a policy may not be implemented by
                 declining to count evidence received
  :writer        the grantor's stream, where grants and :lapsed are written
  :self          the author value the resolver returns for this grantor
  :answer        optional (fn [judge drained-proposals] -> {:grants [grant-fact...]
                 :refusals [{:proposer author :refusal refusal-fact}...]}),
                 called each pass on the proposals it drained (each carrying
                 its author); a grantor owes no answer. Answers are keyed by
                 the PROPOSER: a grant answers the proposal of the holder it
                 carries, while a refusal names no proposer of its own, so
                 the hook names the proposal's author it refuses -- one
                 holder's answered id never blocks another holder's

  Misconfiguration throws here, at assembly. `:seen` and `:answered` grow
  with the distinct leases and proposals a judge actually applies --
  legitimate growth that only a ledger-persistence design may prune; an
  untrusted author's facts never reach either (a forged release registers
  nothing, a forged proposal id cannot collide with another author's answer
  key). Assembly validation of the medium declarations is the composition
  constructor's duty; this builds the state the step threads."
  [config]
  (let [budget (or (get config :drain-budget) default-drain-budget)
        units (get config :units default-units)
        tolerance (get config :tolerance)
        resolver (get config :resolver)]
    (check-assembly! (and (integer? budget) (pos? budget))
                     "the drain budget is a positive integer"
                     {:drain-budget budget})
    ;; A judge without a working attribution resolver would drop every
    ;; lease fact into :dropped -- with the per-element catch in the drain,
    ;; silently -- and every lease would then falsely lapse for :silence.
    ;; It is an assembly defect, thrown here before any step can run.
    (check-assembly! (fn? resolver)
                     "the judge needs an attribution resolver: (fn [source fact] -> author)"
                     {:resolver resolver})
    (check-units! units)
    ;; A tolerance in a unit the table does not know -- or past the unit's
    ;; per-unit bound -- would throw from add-duration inside
    ;; classification, mid-pass, after step 3 has already delivered grants:
    ;; it is an assembly defect, rejected here.
    (when (some? tolerance)
      (check-assembly! (tolerance? tolerance)
                       "the tolerance is a single-entry {unit non-negative-integer} map within the bound"
                       {:tolerance tolerance})
      (let [unit (first (keys tolerance))
            magnitude (get tolerance unit)]
        (check-assembly! (some? (get units unit))
                         "the tolerance's unit is in the unit table"
                         {:tolerance tolerance :units units})
        (check-assembly! (<= magnitude
                             (quot magnitude-limit (get units unit)))
                         "the tolerance's magnitude is within the per-unit bound"
                         {:tolerance tolerance :units units})))
    {:units units
     :tolerance tolerance
     :drain-budget budget
     :resolver resolver
     :reclaim (get config :reclaim)
     :policy (get config :policy)
     :writer (get config :writer)
     :self (get config :self)
     :answer (get config :answer)
     :ticks []
     :facts []
     :queue []
     :ledger {}
     :answered {}
     :seen {}
     :dropped 0
     :drained-proposals []
     :now nil
     :abort nil
     :abort-error nil}))


(defn wire-tick
  "Wire one tick cursor: a reader handle, a cursor the composition minted
  from the anchor of its choosing, and the `source` the composition binds
  to that wiring."
  [judge handle cursor source]
  (update judge :ticks conj {:handle handle
                             :cursor cursor
                             :retired false
                             :source source}))


(defn wire-facts
  "Wire one lease-fact cursor over `source` -- the value the composition's
  attribution resolver will receive for every fact read here: a per-author
  medium, an envelope key's owner, a transport's attachment identity. The
  judge learns which leases ride the medium only from the authoritative
  facts it observes there, and marks exactly those unknown when that medium
  gaps."
  [judge handle cursor source]
  (update judge :facts conj {:handle handle
                             :cursor cursor
                             :retired false
                             :truncated? false
                             :leases #{}
                             :source source}))


(defn author-grant
  "Queue a grant authored outside a pass -- unsolicited, or an answer
  composed between passes. Only an `:accepted` fact is authorable; anything
  else throws at assembly. The next pass delivers it to the writer and
  seeds the ledger stamped with that pass's now; a grant authored between
  passes is seeded with the next pass's now."
  [judge grant-fact]
  (check-assembly! (and (map? grant-fact)
                        (= :dao.lease/accepted (get grant-fact status-key))
                        (not (defective? grant-fact)))
                   "an authored grant must be a well-shaped accepted fact"
                   {:fact grant-fact})
  (update judge :queue conj grant-fact))


(defn- observed-of
  "The admissibility gate's context, from what this judge has already
  applied. `:proposer` is the author of the proposal the fact being gated
  answers, where that is derivable."
  [judge]
  {:answered (:answered judge)
   :seen (:seen judge)
   :units (:units judge)})


(defn admissible?
  "The history-dependent gate (V7): against `observed` -- plain data a
  reader supplies from what it has already applied, plus the `:proposer`
  of the proposal the fact answers, where derivable -- returns false, or a
  truthy vector naming every inadmissibility the fact carries: it answers a
  proposal whose PROPOSER has already had that proposal answered
  (`:answered` is keyed by `[proposer proposal-id]`, because holders mint
  proposal ids: one holder's answered id must never block another holder's
  id minted independently -- only its own); or it repeats a status `:seen`
  already shows for its lease (:accepted, :released and :lapsed are once
  per lease, and a lease identity is never reused within its grantor). A
  stale READING is not decided here: readings are per-stream monotonic and
  staleness is judged only where a tick is evidence, in the judge's tick
  drain. The judge's drain decides admissibility against its ledger; this
  function decides it as pure arithmetic over the supplied history,
  consulting nothing else."
  [fact observed]
  (if (map? fact)
    (or-false
      (-> []
          (vconj (when-some [pid (get fact :dao.lease/proposal)]
                   (when-some [proposer (get observed :proposer)]
                     (when (some? (get (get observed :answered) [proposer pid]))
                       :proposal-already-answered))))
          (vconj (when-some [status (get fact status-key)]
                   (when (and (contains? #{:dao.lease/accepted
                                           :dao.lease/released
                                           :dao.lease/lapsed}
                                         status)
                              (some? (get fact :dao.lease/lease)))
                     (when (get (get (get observed :seen)
                                     (get fact :dao.lease/lease))
                                status)
                       :repeated-status))))))
    false))


(defn- next-answer
  "One read through the contract's outcome algebra: a conforming operation
  passes through; a defective host answer folds into :dao.stream/transport-error
  so the drain's branch stays total."
  [handle cursor]
  (let [result (stream/next handle cursor)]
    (if (stream/valid-outcome? :next result)
      result
      {:dao.stream/outcome :dao.stream/transport-error
       :dao.stream/error :dao.stream/invalid-operation-result
       :dao.stream/answer result})))


(defn- resolve-author
  "The composition's attribution for one fact: the resolver bound to the
  source the cursor was wired with. DaoStream supplies no authorship, and a
  fact's own claim about its author is exactly what must not be trusted."
  [judge source fact]
  (let [resolver (:resolver judge)]
    (when (nil? resolver)
      (throw (ex-info "dao.lease: the judge needs an attribution resolver to read facts"
                      {:fact fact})))
    (resolver source fact)))


(defn- apply-tick-value
  "An in-shape, non-stale tick observed on a tick cursor advances *now* to
  the newest reading. Staleness is judged here, against this judge's own
  reading basis, and nowhere else. A structurally defective tick -- an
  over-bound reading above all -- is COUNTED in :dropped: a rejected tick is
  a signal, never a silent no-op. A stale tick, a non-fact, or another
  lease fact on a tick cursor is noise, ignored."
  [judge value]
  (if (and (lease-fact? value)
           (= :dao.lease/tick (get value event-key)))
    (if (defective? value (:units judge))
      (update judge :dropped inc)
      (if (or (nil? (:now judge))
              (not (stale-reading? (:units judge)
                                   (get value :dao.lease/reading)
                                   (:now judge))))
        (let [r (get value :dao.lease/reading)]
          (if (or (nil? (:now judge))
                  (pos? (compare-durations (:units judge) r (:now judge))))
            (assoc judge :now r)
            judge))
        judge))
    judge))


(defn- drain-tick-cursor
  "Drain one tick cursor up to the drain budget. A tick-stream gap makes a
  pass late, not wrong, and marks nothing unknown: resume from the recovery
  cursor and keep draining, the resume costing one budget unit as
  `forward-step` counts its resumes. A retired cursor is not read again."
  [judge tick]
  (if (:retired tick)
    [judge tick]
    (loop [judge judge
           tick tick
           budget (:drain-budget judge)]
      (let [read (next-answer (:handle tick) (:cursor tick))
            outcome (:dao.stream/outcome read)]
        (case outcome
          :dao.stream/ok
          (if (pos? budget)
            (recur (apply-tick-value judge (:dao.stream/value read))
                   (assoc tick :cursor (:dao.stream/cursor read))
                   (dec budget))
            ;; Budget spent with the stream still yielding: leave the remainder
            ;; to the next pass -- late, not wrong.
            [judge tick])

          :dao.stream/blocked
          [judge tick]

          :dao.stream/end
          [judge (assoc tick :retired true)]

          :dao.stream/gap
          (let [recovery (:dao.stream/cursor read)]
            (cond
              ;; A recovery cursor that does not move cannot be observed from;
              ;; stop rather than spin on a defective transport.
              (= recovery (:cursor tick))
              [judge tick]

              (pos? budget)
              (recur judge (assoc tick :cursor recovery) (dec budget))

              :else [judge tick]))

          ;; transport-error and cursor defects: pass-aborting (J10)
          [(assoc judge :abort outcome) tick])))))


(defn- drain-ticks
  "Step 1: drain every non-retired tick cursor within the budget. A
  transport error on one stops the drain of the remaining tick cursors;
  steps 2 and 3 still run. A throw from the READ itself is folded into the
  same abort -- :abort :dao.stream/transport-error with the message under
  :abort-error -- so no exception escapes the step. Tick VALUES cannot
  throw here: readings are shape-gated and bounded before any arithmetic,
  so the tick path needs no per-element handler."
  [judge]
  (loop [judge judge
         remaining (:ticks judge)
         done []]
    (if (or (empty? remaining) (some? (:abort judge)))
      (assoc judge :ticks (into done remaining))
      (let [[judge' tick'] (try
                             (drain-tick-cursor judge (first remaining))
                             (catch #?(:cljd Object :clj Exception :cljs :default) e
                               [(assoc judge
                                       :abort :dao.stream/transport-error
                                       :abort-error (ex-message e))
                                (first remaining)]))]
        (recur judge' (rest remaining) (conj done tick'))))))


(defn- mark-seen
  [judge lease-id status]
  (let [prior (get (:seen judge) lease-id)]
    (assoc-in judge [:seen lease-id]
              (if (nil? prior) #{status} (conj prior status)))))


(defn- mark-answered-maybe
  [judge proposer fact status]
  (if (nil? proposer)
    judge
    (if-some [pid (get fact :dao.lease/proposal)]
      (assoc-in judge [:answered [proposer pid]] status)
      judge)))


(defn- seed-lease
  "A lease enters the ledger when the grantor grants it, seeded from that
  act: terms, tenure start at this pass's now -- which no renewal or gap
  moves -- and last observation at the same now."
  [judge fact]
  (assoc-in judge [:ledger (get fact :dao.lease/lease)]
            {:subject (get fact :dao.lease/subject)
             :holder (get fact :dao.lease/holder)
             :duration (get fact :dao.lease/duration)
             :max (get fact :dao.lease/max)
             :tenure-start (:now judge)
             :last-observation (:now judge)
             :evidence :known
             :released? false
             :reclaim {:state :live :cause nil :success nil}}))


(defn- apply-valid-fact
  "Apply one structurally valid, admissible lease fact drained from a fact
  cursor, stamped with this pass's now. Returns [judge counted-lease-id]:
  `counted-lease-id` is the lease this fact was AUTHORITATIVE for -- an
  eligible renewal or release from the lease's HOLDER, and nothing else --
  and only an authoritative fact registers the lease on the medium it
  arrived on. A grantor-authored grant seeds tenure but registers NO
  medium: the grant rides the grantor's stream, not the holder's, and
  registration is the holder-medium relation the gap and truncation
  fallbacks depend on. Everything else establishes nothing and registers
  nothing: a holder fact establishes no term (a delayed holder renewal is
  still evidence and counts), a renewal or release from any other author
  is a fact about that author, a proposal creates no state, a tick on a
  fact cursor is noise. An answer is recorded under the PROPOSER it
  answers -- for a grant, the holder it carries -- so one holder's answered
  proposal id never blocks another's; a drained refusal echo names no
  proposer and records no answer."
  [judge author fact]
  (if-some [status (get fact status-key)]
    (case status
      :dao.lease/proposed
      [(update judge :drained-proposals conj {:fact fact :author author}) nil]

      :dao.lease/accepted
      (if (= author (:self judge))
        [(-> judge
             (mark-seen (get fact :dao.lease/lease) status)
             (mark-answered-maybe (get fact :dao.lease/holder) fact status)
             (seed-lease fact))
         nil]
        [judge nil])

      :dao.lease/rejected
      (if (= author (:self judge))
        ;; An echoed refusal names no proposer: it records no answer key,
        ;; and repeat protection for refusals is the hook's delivery gate.
        [judge nil]
        [judge nil])

      :dao.lease/released
      (let [lid (get fact :dao.lease/lease)]
        (if (and (some? (get (:ledger judge) lid))
                 (= author (get-in judge [:ledger lid :holder])))
          [(-> judge
               (mark-seen lid status)
               (assoc-in [:ledger lid :released?] true))
           lid]
          ;; A release from any other author -- or for a lease not in the
          ;; ledger -- establishes nothing, registers nothing, and above all
          ;; does not enter :seen: a forged release must not make the
          ;; holder's own later release read as a repeat.
          [judge nil]))

      :dao.lease/lapsed
      (if (= author (:self judge))
        [(mark-seen judge (get fact :dao.lease/lease) status) nil]
        [judge nil])

      [judge nil])
    (case (get fact event-key)
      :dao.lease/renewal
      (let [lid (get fact :dao.lease/lease)]
        (if (and (some? (get (:ledger judge) lid))
                 (= author (get-in judge [:ledger lid :holder])))
          [(-> judge
               (assoc-in [:ledger lid :last-observation] (:now judge))
               (assoc-in [:ledger lid :evidence] :known))
           lid]
          [judge nil]))
      [judge nil])))


(defn- on-any-medium
  [judge lease-id]
  (boolean (some (fn [entry] (contains? (:leases entry) lease-id))
                 (:facts judge))))


(defn- mark-medium-unknown
  "A gap on a lease-fact medium marks every lease that medium has
  authoritatively observed -- and only that medium's -- unknown, with now as
  its resumed reading; a surviving older renewal is not inferred to be the
  newest, so unknown is the state whatever the retained tail holds.
  Conservatively it ALSO marks every lease registered on no medium at all:
  a lease whose holder's medium the judge has not yet learned could be this
  one, and a gap we cannot attribute is exactly the window the judge did
  not observe. The conservative direction errs against a false lapse, which
  is the contract's error direction; the alternative -- a grant declaring
  its medium -- is a composition-constructor concern, not the step's.
  Conditions other than :silence apply to an unknown lease unchanged."
  [judge entry]
  (let [gapped (:leases entry)
        unregistered (remove (fn [lid] (on-any-medium judge lid))
                             (keys (:ledger judge)))]
    (reduce (fn [judge lid]
              (if (some? (get-in judge [:ledger lid]))
                (assoc-in judge [:ledger lid]
                          (assoc (get-in judge [:ledger lid])
                                 :evidence :unknown
                                 :resumed (:now judge)))
                judge))
            judge
            (concat gapped unregistered))))


(defn- process-fact-value
  "Gate and apply one drained value: resolve the author from the wired
  source, drop the structurally defective and the inadmissible, apply the
  rest, and register the lease on this medium only when the fact was
  holder-authoritative for it. Returns [judge' entry']. May throw -- the
  resolver is composition code, and a hostile envelope is exactly the
  thrower -- which the drain treats as a dropped element."
  [judge entry value]
  (let [lease-fact (lease-fact? value)
        author (when lease-fact
                 (resolve-author judge (:source entry) value))
        ;; the answered-key is the PROPOSER: for a grant, the holder it
        ;; carries; nothing else names one
        proposer (when (and lease-fact
                            (= :dao.lease/accepted (get value status-key)))
                   (get value :dao.lease/holder))
        ok (and lease-fact
                (not (defective? value (:units judge)))
                (not (admissible? value (assoc (observed-of judge)
                                               :proposer proposer))))
        [judge' counted] (if ok
                           (apply-valid-fact judge author value)
                           [judge nil])
        judge' (if (or ok (not lease-fact))
                 judge'
                 (update judge' :dropped inc))
        ;; Registration on the medium is part of application, and only a
        ;; holder-authoritative fact registers: a defective, inadmissible or
        ;; non-counting fact establishes nothing, not even presence -- so no
        ;; non-holder can keep another holder's lease alive by gap-flooding
        ;; its own medium, and a grant read from a cursor does not claim the
        ;; holder's medium for the lease.
        entry' (if (some? counted)
                 (update entry :leases conj counted)
                 entry)]
    [judge' entry']))


(defn- drain-fact-cursor
  "Drain one fact cursor up to the drain budget, stamping each applied fact
  with this pass's now. A retired cursor is not read again. A throw while
  PROCESSING a value -- a resolver that throws on a hostile envelope, above
  all -- drops that element and advances past it: a hostile element cannot
  wedge the cursor. When the budget runs out while the cursor still has
  elements, the entry is marked `:truncated?` -- a drain that did not reach
  quiescence is never mistaken for one that did."
  [judge entry]
  (if (:retired entry)
    [judge entry]
    (loop [judge judge
           entry entry
           budget (:drain-budget judge)]
      (let [read (next-answer (:handle entry) (:cursor entry))
            outcome (:dao.stream/outcome read)]
        (case outcome
          :dao.stream/ok
          (if (pos? budget)
            (let [cursor' (:dao.stream/cursor read)
                  processed (try
                              (process-fact-value judge
                                                  entry
                                                  (:dao.stream/value read))
                              (catch #?(:cljd Object :clj Exception :cljs :default) _
                                :element-threw))]
              (if (= :element-threw processed)
                (recur (update judge :dropped inc)
                       (assoc entry :cursor cursor')
                       (dec budget))
                (recur (first processed)
                       (assoc (second processed) :cursor cursor')
                       (dec budget))))
            [judge (assoc entry :truncated? true)])

          :dao.stream/blocked
          [judge (assoc entry :truncated? false)]

          :dao.stream/end
          [judge (assoc entry :retired true :truncated? false)]

          :dao.stream/gap
          (let [recovery (:dao.stream/cursor read)
                judge' (mark-medium-unknown judge entry)]
            (cond
              (= recovery (:cursor entry))
              [judge' (assoc entry :truncated? false)]

              (pos? budget)
              (recur judge' (assoc entry :cursor recovery) (dec budget))

              :else [judge' (assoc entry :truncated? true)]))

          ;; transport-error and cursor defects: pass-aborting (J10), never a
          ;; completed drain -- that would manufacture absence evidence.
          [(assoc judge :abort outcome) entry])))))


(defn- drain-facts
  "Step 2: drain every non-retired fact cursor within the budget. Runs even
  when step 1 recorded an abort -- the contract ends the pass before step 4,
  not before step 2 -- and stops its own drain only when an abort is NEWLY
  set by one of its own cursors, so a persistent tick error cannot starve
  fact cursors two through n. A throw from the READ itself is folded into
  the same abort (:abort-error carries the message); a throw while
  processing a value is handled inside the cursor's drain -- dropped,
  advanced past, counted -- and never reaches here."
  [judge]
  (loop [judge (assoc judge :drained-proposals [])
         remaining (:facts judge)
         done []
         newly-aborted false]
    (if (or (empty? remaining) newly-aborted)
      (assoc judge :facts (into done remaining))
      (let [abort-before (:abort judge)
            [judge' entry'] (try
                              (drain-fact-cursor judge (first remaining))
                              (catch #?(:cljd Object :clj Exception :cljs :default) e
                                [(assoc judge
                                        :abort :dao.stream/transport-error
                                        :abort-error (ex-message e))
                                 (first remaining)]))]
        (recur judge' (rest remaining) (conj done entry')
               (not= (:abort judge') abort-before))))))


(defn- apply-authored
  "Commit the ledger effect of a delivered authored fact. Authored facts
  are the grantor's own; `proposer` is the author of the proposal the fact
  answers -- the grant's holder for a grant, the hook-supplied proposer for
  a refusal."
  [judge fact proposer]
  (let [status (get fact status-key)]
    (if (= :dao.lease/accepted status)
      (-> judge
          (mark-seen (get fact :dao.lease/lease) status)
          (mark-answered-maybe proposer fact status)
          (seed-lease fact))
      (mark-answered-maybe judge proposer fact status))))


(defn- append-authored
  "Append one authored fact to the grantor's writer. A missing writer can
  never commit; the fact stays queued, the record stays unmade."
  [judge fact]
  (if-some [writer (:writer judge)]
    (stream/append! writer fact)
    {:dao.stream/outcome :dao.stream/transport-error
     :dao.stream/error :dao.lease/no-writer}))


(defn- deliver-authored
  "Deliver one authored fact (grant or refusal -- only :accepted and
  :rejected are deliverable; a hook handing back a :lapsed, :released or
  renewal fact is refused) to the writer and commit its ledger effect only
  when the append answered :dao.stream/ok. `proposer` is the author of the
  proposal the fact answers; for a grant that is the holder the grant
  carries. Returns [judge disposition]: `:delivered`; `:rejected`
  (structurally defective, inadmissible against its proposer's answers, or
  not a deliverable status -- counted in :dropped once and discarded, not
  retried); or `:retry` (a non-ok append -- the fact stays queued for the
  next pass). No tenure is established by a fact that never landed."
  [judge fact proposer]
  (let [status (get fact status-key)]
    (if (or (not (contains? #{:dao.lease/accepted :dao.lease/rejected} status))
            (defective? fact (:units judge))
            (admissible? fact (assoc (observed-of judge)
                                     :proposer proposer)))
      [(update judge :dropped inc) :rejected]
      (let [append (append-authored judge fact)]
        (if (= :dao.stream/ok (:dao.stream/outcome append))
          [(apply-authored judge fact proposer) :delivered]
          [judge :retry])))))


(defn- flush-queue
  "Deliver grants authored between passes. Rejected grants are discarded
  after one count; undelivered ones stay queued. A queued grant's proposer
  is the holder it carries."
  [judge]
  (loop [judge judge
         remaining (:queue judge)
         kept []]
    (if (empty? remaining)
      (assoc judge :queue kept)
      (let [fact (first remaining)
            [judge' disposition] (deliver-authored judge
                                                   fact
                                                   (get fact :dao.lease/holder))]
        (recur judge' (rest remaining)
               (if (= :retry disposition)
                 (conj kept fact)
                 kept))))))


(defn- answer-and-grant
  "Step 3: flush queued grants, then let the composition's :answer hook
  answer the proposals drained this pass and author grants afresh. The hook
  returns `{:grants [grant-fact...] :refusals [{:proposer author
  :refusal refusal-fact}...]}` -- a grant answers the proposal of the
  holder it carries, whose id it echoes, while a refusal names no proposer
  of its own, so the hook names the proposal's author it refuses. Both are
  delivered and seeded stamped with this pass's now. A hook-authored fact
  that is rejected is dropped with a count; one whose append does not
  answer ok is lost to this pass -- the hook runs again next pass and may
  re-decide, and nothing queued exists for it."
  [judge]
  (let [judge (flush-queue judge)]
    (if-some [answer (:answer judge)]
      (let [answers (answer judge (:drained-proposals judge))]
        (reduce (fn [judge fact]
                  (let [refusal-entry? (contains? fact :refusal)
                        proposer (if refusal-entry?
                                   (get fact :proposer)
                                   (get fact :dao.lease/holder))
                        authored (if refusal-entry?
                                   (get fact :refusal)
                                   fact)]
                    (first (deliver-authored judge authored proposer))))
                judge
                (concat (get answers :grants) (get answers :refusals))))
      judge)))


(defn- duration-plus-tolerance
  [units duration tolerance]
  (if (nil? tolerance) duration (add-duration units duration tolerance)))


(defn- silence-suppressed
  "The leases whose :silence clause is suppressed this pass: when any fact
  medium's drain was truncated, the leases registered on a truncated medium
  plus the leases registered on no medium at all -- a lease whose holder's
  medium the judge has not yet learned cannot have its absence judged while
  some window was cut short. With no truncated medium nothing is
  suppressed: a clean drain to quiescence IS an observed window, and a
  holder who sent nothing observable is silent in it. (A GAPPED medium
  needs no suppression: the gap's marking already turns the leases it could
  carry, registered or not, :unknown.) Suppression is sound against
  untrusted authors because registration requires an authoritative fact, so
  no one can claim another holder's lease onto a medium they then flood.
  :release, :cap, :policy and pending retries are never suppressed: a
  truncated observation window bars silence, not the conditions the ledger
  already knows."
  [judge]
  (if (some :truncated? (:facts judge))
    (let [on-truncated-medium (fn [lease-id]
                                (boolean (some (fn [entry]
                                                 (and (:truncated? entry)
                                                      (contains? (:leases entry)
                                                                 lease-id)))
                                               (:facts judge))))]
      (set (filter (fn [lease-id]
                     (or (on-truncated-medium lease-id)
                         (not (on-any-medium judge lease-id))))
                   (keys (:ledger judge)))))
    #{}))


(defn- classify-entry
  "Step 4: why a lease is due for reclaim, or nil when it is not. The first
  clause that holds in this order is the cause: already pending, keeping the
  cause it carries -- a pending lease is never reclassified; released by its
  holder; tenure start further back than :dao.lease/max, which binds within
  one ledger lifetime and is moved by no renewal or gap; the grantor's own
  policy; interval since the last relevant observation exceeding duration
  plus tolerance, in silence. A lease whose evidence is unknown is due for
  :silence only once a full duration has passed since its resumed reading;
  every other condition applies to it unchanged. `suppressed?` bars the
  :silence clauses only (see `silence-suppressed`), and an unknown entry
  with no resumed reading is never due for silence -- there is no window to
  measure, and no malformed ledger entry may throw mid-pass."
  [judge now entry suppressed?]
  (let [units (:units judge)]
    (cond
      (= :pending (get-in entry [:reclaim :state]))
      (get-in entry [:reclaim :cause])

      (:released? entry)
      :release

      (and (some? (:max entry))
           (exceeds? units
                     (interval units now (:tenure-start entry))
                     (:max entry)))
      :cap

      (and (some? (:policy judge))
           ((:policy judge) entry now))
      :policy

      (and (not suppressed?)
           (= :unknown (:evidence entry))
           (some? (:resumed entry))
           (exceeds? units
                     (interval units now (get entry :resumed))
                     (:duration entry)))
      :silence

      (and (not suppressed?)
           (= :known (:evidence entry))
           (exceeds? units
                     (interval units now (:last-observation entry))
                     (duration-plus-tolerance units
                                              (:duration entry)
                                              (:tolerance judge))))
      :silence

      :else nil)))


(defn- reclaim-one
  "Steps 5 and 6 for one due lease, as explicit sequencing: mark the lease
  `pending` with its classified cause FIRST, then invoke the injected
  reclaim and record whether it reported success -- a throw counts as \"did
  not report\" and leaves the lease pending -- then append `:lapsed`, and
  remove the lease from the ledger only when the append answered
  :dao.stream/ok. A reclaim that fails or does not report leaves the lease
  pending and unrecorded; a non-`ok` append leaves it pending for the next
  pass, reached through classification's first clause with its original
  cause. The record follows the act and never precedes it, and the absence
  of a `:lapsed` fact is never evidence of tenure."
  [judge lease-id cause]
  (let [reclaim (:reclaim judge)
        entry (get-in judge [:ledger lease-id])]
    (when (nil? reclaim)
      (throw (ex-info "dao.lease: the judge needs a reclaim procedure to reclaim"
                      {:lease lease-id :subject (:subject entry)})))
    (let [judge (assoc-in judge [:ledger lease-id :reclaim]
                          {:state :pending :cause cause :success nil})
          success? (try
                     (boolean (reclaim (:subject entry)))
                     (catch #?(:cljd Object :clj Exception :cljs :default) _
                       false))
          judge (assoc-in judge [:ledger lease-id :reclaim :success] success?)]
      (if-not success?
        judge
        (let [fact (lapsed lease-id cause)
              append (append-authored judge fact)]
          (if (= :dao.stream/ok (:dao.stream/outcome append))
            (mark-seen (update judge :ledger dissoc lease-id)
                       lease-id :dao.lease/lapsed)
            judge))))))


(defn- reclaim-and-record
  [judge]
  (let [suppressed (silence-suppressed judge)]
    (loop [judge judge
           remaining (keys (:ledger judge))]
      (if (empty? remaining)
        judge
        (let [lease-id (first remaining)
              cause (classify-entry judge
                                    (:now judge)
                                    (get-in judge [:ledger lease-id])
                                    (contains? suppressed lease-id))]
          (if (nil? cause)
            (recur judge (rest remaining))
            (recur (reclaim-one judge lease-id cause) (rest remaining))))))))


(defn judge-step
  "One pass of the judge: the composition's driver calls this at its declared
  cadence -- a maximum interval between *completed* passes -- and threads the
  returned judge into the next call. The step is effectful and
  state-threaded; it owns no scheduler, callback or registry, and reads no
  clock. The pass, in order:

  1. drain every wired tick cursor, each within the `:drain-budget`; the
     newest reading drained, or the newest previously observed, is *now* for
     the whole pass. A tick-cursor gap makes the pass late, not wrong, and
     marks nothing unknown.
  2. drain every wired lease-fact cursor, each within the budget, stamping
     each structurally valid, admissible fact with that same *now* and
     applying it -- eligible renewals first, whatever a policy may later
     decide about the lease. A :dao.stream/gap marks every lease
     authoritatively observed on that medium -- and, conservatively, every
     lease not yet observed on any -- unknown with *now* as its resumed
     reading. A cursor whose budget runs out while elements remain is
     recorded `:truncated?`.
  3. answer and grant -- queued grants are delivered, and the composition's
     :answer hook may answer the proposals drained this pass -- both seeded
     and stamped with this pass's *now*.
  4. classify: each lease is due for reclaim by the first cause that holds,
     in order pending (keeping its carried cause), :release, :cap, :policy,
     :silence. The :silence clauses are barred for leases on truncated
     media and on no medium yet: absence is evidence only over a window the
     judge observed, and a cut-short drain is not one.
  5. reclaim each due lease by the injected procedure, marking it pending
     with its cause first and recording whether the procedure reported
     success.
  6. record :lapsed; on :dao.stream/ok the lease leaves the ledger.

  A pass before any tick has ever been observed performs nothing: there is
  no *now* to stamp with, and the contract bars classification until a first
  tick is observed. A drain ending in :dao.stream/end retires that cursor
  for subsequent passes -- later passes do not call it again. A drain ending
  in :dao.stream/transport-error, :dao.stream/cursor-mismatch or
  :dao.stream/invalid-cursor is recorded under :abort and ends the pass
  before step 4: an aborted pass reclaims nothing, so a broken reader is
  never mistaken for silence. A throw from the READ itself is folded into
  the same abort, its message under :abort-error; a throw while PROCESSING
  a drained value drops that one element -- counted in :dropped, cursor
  advanced past it -- and judging continues."
  [judge]
  (let [judge (assoc judge :abort nil :abort-error nil)
        judge (drain-ticks judge)]
    (if (nil? (:now judge))
      judge
      (let [judge (drain-facts judge)
            judge (answer-and-grant judge)]
        (if (some? (:abort judge))
          judge
          (reclaim-and-record judge))))))


(defn restart
  "A grantor that has lost its ledger reclaims and re-grants: for each
  resource in `inventory` -- maps carrying :subject, :holder, :duration and
  optionally :max -- it performs the reclaim first, then grants afresh,
  queueing a grant under a fresh identity that the next pass delivers and
  seeds with its now. The reclaim ends the prior tenure; a new grant alone
  does not.

  Returns `{:judge ... :unreclaimed [item...] :discarded-queue [fact...]}`.
  Items whose reclaim did not report success (or threw) are returned under
  `:unreclaimed` and are not re-granted -- a possessed resource that cannot
  be reclaimed is reported, not skipped silently. The stale queue of grants
  authored before the loss is cleared, its contents recorded under
  `:discarded-queue`. `:seen` and `:answered` SURVIVE the restart: an
  observation made before the loss is still an observation, and an old
  self-authored `:accepted` still undrained on a medium must re-read as
  inadmissible rather than re-seed a departed tenure. No :lapsed record is
  written: the terms of the lost tenures are gone with the ledger, which is
  why the inventory and a recoverable holder per resource belong to the
  resource rather than to this vocabulary."
  [judge inventory]
  (let [discarded-queue (:queue judge)
        reclaim (:reclaim judge)
        {:keys [reclaimed unreclaimed]}
        (reduce (fn [acc item]
                  (if (nil? reclaim)
                    (throw (ex-info "dao.lease: restart needs a reclaim procedure"
                                    {:item item}))
                    (if (try
                          (boolean (reclaim (get item :subject)))
                          (catch #?(:cljd Object :clj Exception :cljs :default) _
                            false))
                      (update acc :reclaimed conj item)
                      (update acc :unreclaimed conj item))))
                {:reclaimed [] :unreclaimed []}
                inventory)
        judge (-> judge
                  (assoc :ledger {})
                  (assoc :queue []))
        judge (reduce (fn [judge item]
                        (let [cap (get item :max)]
                          (author-grant judge
                                        (grant (mint-lease-id)
                                               (get item :subject)
                                               (get item :holder)
                                               (get item :duration)
                                               (if (nil? cap)
                                                 nil
                                                 {:dao.lease/max cap})))))
                      judge
                      reclaimed)]
    {:judge judge
     :unreclaimed unreclaimed
     :discarded-queue discarded-queue}))


;; =============================================================================
;; The holder
;; =============================================================================
;;
;; The holder discipline is a set of pure functions and constructors the
;; holder's OWN control flow calls: observe the grant before acting (H1),
;; renew at strictly less than half the duration (H2), stop at the bound
;; (H3), release when done (H4). Nothing here installs a timer, renews on a
;; holder's behalf, or reads a clock -- time reaches these functions only as
;; readings the control flow drained from the holder's own tick cursor, and
;; a renewal advances a bound only as an append that answered
;; :dao.stream/ok. The holder threads a small plain map -- the observed
;; grant, the readings it was observed and last renewed at, a stopped flag
;; -- no ledger, no mutable state; a holder holding several leases threads
;; one state per lease. The bound bounds attention, not access (H5): every
;; function here returns a truth value, a fact, or that plain state -- there
;; is no token, capability or access surface in this vocabulary.

(defn initial-holder
  "The holder's state before any grant is observed. The config carries the
  injected seams:

  :self              this holder's identity -- the :dao.lease/holder value
                     of the grants it may observe
  :grantor           the author value the composition's resolver returns
                     for the grantor's facts
  :resolver          (fn [source fact] -> author), the SAME attribution the
                     judge is given; a fact's own claim about its author is
                     never consulted
  :units             the composition's shared unit table (default
                     `default-units`), validated as `initial-judge`
                     validates its own
  :renewal-interval  the composition's renewal interval, sized by
                     `renewal-interval` strictly below half the duration
                     the composition is arranging (H2/S1); validated here
                     with the tolerance's own checks -- unit membership and
                     the per-unit quot bound, run after `check-units!` --
                     so no first `due-to-renew?` can throw on it (assembly
                     parity with the judge's N4/R3 tolerance gate)
  :subject           optional expectation: when present, only a grant for
                     this subject is held -- a holder that proposed for one
                     resource will not keep-first an unsolicited or stale
                     grant naming it for another
  :proposal          optional expectation: when present, only a grant
                     answering this proposal identity is held; a grant
                     carrying no proposal does not match it

  Missing or mis-shaped seams throw here, at assembly: a holder with no
  working attribution would observe no grant, hold nothing, and falsely
  never renew -- the same failure `initial-judge` refuses at assembly."
  [{:keys [self grantor resolver renewal-interval] :as config}]
  (let [units (get config :units default-units)]
    (check-assembly! (some? self)
                     "the holder needs its identity: the :dao.lease/holder it observes grants for"
                     {:self self})
    (check-assembly! (some? grantor)
                     "the holder needs the author its attribution returns for the grantor"
                     {:grantor grantor})
    (check-assembly! (fn? resolver)
                     "the holder needs an attribution resolver: (fn [source fact] -> author)"
                     {:resolver resolver})
    (check-assembly! (duration? renewal-interval)
                     "the renewal interval is a single-entry {unit positive-integer} map"
                     {:renewal-interval renewal-interval})
    (check-units! units)
    ;; The interval gets the tolerance's checks, run after check-units!:
    ;; unit membership, then the per-unit bound -- taken by division, so
    ;; the check itself cannot overflow and no later arithmetic on the
    ;; interval can either (N4/R3 parity with the judge's tolerance).
    (let [unit (first (keys renewal-interval))
          magnitude (get renewal-interval unit)]
      (check-assembly! (some? (get units unit))
                       "the renewal interval's unit is in the unit table"
                       {:renewal-interval renewal-interval :units units})
      (check-assembly! (<= magnitude (quot magnitude-limit (get units unit)))
                       "the renewal interval's magnitude is within the per-unit bound"
                       {:renewal-interval renewal-interval :units units}))
    {:self self
     :grantor grantor
     :resolver resolver
     :units units
     :renewal-interval renewal-interval
     :subject (get config :subject)
     :proposal (get config :proposal)
     :grant nil
     :granted-at nil
     :last-renewal-at nil
     :released? false
     :bound-reached? false
     :undersized? false}))


(defn renewal-interval
  "The composition's renewal interval, validated against the duration it
  is sized to (H2 as amended): strictly below HALF the duration -- equality
  is the violation, and an interval at or above half already breaks the
  sizing relation duration > interval + interval, so no composition may
  hold one. Returns the interval unchanged; throws, as an assembly defect,
  when a shape is not a duration, a unit is outside the table or past its
  per-unit bound (an over-bound magnitude would overflow the comparison
  as a raw host error -- the gate runs before any arithmetic, as an
  assembly ex-info), or the relation fails.

  The optional `tick-period` sizes for discrete ticks: the holder renews
  at the first reading at or past the interval, which lands up to one
  period of its tick stream late, so the before-half guarantee must be
  bought against the interval PLUS the period -- 2 x (interval + period)
  strictly below the duration. A period is itself a duration. The granted
  duration is the grantor's to mint, so sizing the interval against the
  duration a composition expects is the composition's duty (S1); this is
  the check to run wherever the two are known together, and
  `observe-grant` runs the unsized check against the duration actually
  granted."
  ([units duration proposed] (renewal-interval units duration proposed nil))
  ([units duration proposed tick-period]
   (check-assembly! (duration? duration)
                    "the renewal interval is sized against a duration"
                    {:duration duration})
   (check-assembly! (duration? proposed)
                    "the renewal interval is a single-entry {unit positive-integer} map"
                    {:renewal-interval proposed})
   (check-assembly! (or (nil? tick-period) (duration? tick-period))
                   "the tick period is a single-entry {unit positive-integer} map"
                   {:tick-period tick-period})
   (doseq [[key d] [[:duration duration]
                    [:renewal-interval proposed]
                    [:tick-period tick-period]]]
     (when (some? d)
       (let [unit (first (keys d))
             magnitude (get d unit)]
         (check-assembly! (some? (get units unit))
                          "the duration, interval and period carry units from the unit table"
                          {key d :units units})
         (check-assembly! (<= magnitude (quot magnitude-limit (get units unit)))
                          "the duration, interval and period are within the per-unit bound"
                          {key d :units units}))))
   (let [effective (if (nil? tick-period)
                     proposed
                     (add-duration units proposed tick-period))]
     (check-assembly! (neg? (compare-durations units
                                              (add-duration units effective effective)
                                              duration))
                      "the renewal interval (+ tick period) must be strictly below half the duration:
                       at or above half it already violates the sizing relation"
                      {:renewal-interval proposed
                       :tick-period tick-period
                       :duration duration})
     proposed)))


(defn- at-or-past?
  "True when elapsed has REACHED bound. The holder acts at its bounds --
  renewing at its interval, stopping at its bound -- unlike the judge,
  which acts only past them: V3's strictness answers `exceeds?`'s
  \"passed\", and the holder must not wait for the pass."
  [units elapsed bound]
  (not (neg? (compare-durations units elapsed bound))))


(defn- later-reading
  "The later of two readings on the shared unit table."
  [units a b]
  (if (pos? (compare-durations units a b)) a b))


(defn- activity-basis
  "The later of the holder's last renewal and its observed grant (H3): the
  reading its duration bound is measured from."
  [holder]
  (if-some [renewed-at (:last-renewal-at holder)]
    (later-reading (:units holder) renewed-at (:granted-at holder))
    (:granted-at holder)))


(defn- reading-ok?
  "A reading the holder will act on: a well-shaped duration in a unit the
  composition's table knows, within the per-unit bound -- the same gate
  the judge's tick drain applies before letting a reading near any
  arithmetic. An invalid reading is never an error the holder throws
  (chosen to mirror the judge's first-tick rule, where no now means the
  pass performs nothing): every function receiving one observes nothing
  and answers nothing, so a control flow with no usable tick yet holds
  nothing, renews nothing and is at no bound, rather than crashing
  mid-flow."
  [holder reading]
  (and (duration? reading)
       (let [k (first (keys reading))
             v (get reading k)
             m (get (:units holder) k)]
         (and (some? m)
              (<= v (quot magnitude-limit m))))))


(defn observe-grant
  "H1: observe the grant before acting. The grant is held only when the
  fact is a structurally valid `:accepted` naming this holder
  (`:dao.lease/holder` = `:self`) whose author, by the composition's
  resolver bound to the source the fact was read from, is the grantor --
  the same attribution seam the judge resolves with -- and, when the
  state carries a `:subject` or `:proposal` expectation (wired at
  assembly by a holder that proposed), the grant's subject and answered
  proposal match it; a grant answering no proposal matches no proposal
  expectation. Anything else -- a forged or non-grantor `:accepted`, a
  grant naming another holder or another lease's proposal, a
  structurally defective fact, a refusal -- establishes nothing: the
  holder is returned unchanged and `:grant` nil is the report \"no grant
  observed\"; a holder with `:grant` nil holds nothing, and every
  discipline below answers false.

  `reading` is the newest reading drained from the holder's OWN tick
  cursor at the moment of observation -- the tenure basis no renewal
  moves, which the cap binds -- and no clock supplies it. A reading that
  is not a well-shaped, in-table, in-bound duration observes nothing
  (mirroring the judge's first-tick rule; see `reading-ok?`).

  Sizing is checked where the interval and the GRANTED duration are known
  together, because the grantor may grant shorter than the ask: an
  undersized grant -- interval + interval not strictly below the granted
  duration, equality included -- is still HELD, recorded `:undersized?`:
  the holder holds what it was granted, the grantor's word, and may
  release it, but every discipline function treats it as at the bound --
  no renewal is due, no renewal advances anything, `holding?` is false.

  The first observed grant is the one held: observation seeds the basis
  once, and no later fact moves it (a fresh lease id is a different
  lease; a holder holding several threads one state per lease). A
  resolver that throws propagates the throw: attribution failure is loud,
  and the control flow observing through a broken seam observes nothing."
  [holder source fact reading]
  (if (and (reading-ok? holder reading)
           (lease-fact? fact)
           (= :dao.lease/accepted (get fact status-key))
           (not (defective? fact (:units holder)))
           (= (:self holder) (get fact :dao.lease/holder))
           (= (:grantor holder) ((:resolver holder) source fact))
           (nil? (:grant holder))
           (or (nil? (:subject holder))
               (= (:subject holder) (get fact :dao.lease/subject)))
           (or (nil? (:proposal holder))
               (= (:proposal holder) (get fact :dao.lease/proposal))))
    (assoc holder
           :grant fact
           :granted-at reading
           :last-renewal-at nil
           :bound-reached? false
           :undersized? (not (neg? (compare-durations
                                    (:units holder)
                                    (add-duration (:units holder)
                                                  (:renewal-interval holder)
                                                  (:renewal-interval holder))
                                    (get fact :dao.lease/duration)))))
    holder))


(defn at-bound?
  "H3: true when the holder's reading has reached the bound -- the earlier
  of the duration since the later of its last renewal and its observed
  grant, and the cap the grant carries measured from the grant itself --
  whether or not anything has been heard. The holder stops acting AT the
  bound: equality fires here where the judge's `exceeds?` would not, so a
  stopped holder's last renewal lands before the judge's window closes.
  The bound is TERMINAL (P1): once reached it is latched
  (`:bound-reached?`) and stays true for any later reading, however stale
  or small -- a late renewal cannot buy back a lease the judge may already
  have reclaimed. An undersized grant is at the bound from observation on.
  False before any grant is observed: no grant, no bound, nothing held.
  An invalid reading answers false meaning UNANSWERABLE, never free to
  act: with no usable tick there is no bound to measure, and a flow that
  guards on this predicate alone would act on a bound it could not see --
  gate flows through `holding?`, which fails closed on the same input.
  The bound bounds attention, not access (H5): this answers a question, it revokes nothing."
  [holder reading]
  (let [{:keys [grant granted-at units]} holder]
    (cond
      (:bound-reached? holder) true
      (:undersized? holder) true
      (nil? grant) false
      (not (reading-ok? holder reading)) false
      :else (let [duration-reached (at-or-past?
                                     units
                                     (interval units reading (activity-basis holder))
                                     (get grant :dao.lease/duration))]
              (if-some [cap (get grant :dao.lease/max)]
                (or duration-reached
                    (at-or-past? units (interval units reading granted-at) cap))
                duration-reached)))))


(defn observe-renewal
  "Record this holder's own renewal. The control flow appends the renewal
  fact and hands the append's outcome here with `reading` -- the reading
  drained BEFORE the append: a reading taken after it would move the
  basis later than any evidence the judge can have for the renewal. The
  activity basis advances to that reading only when the append answered
  `:dao.stream/ok` (H2) -- a `full`, `closed` or
  `:dao.stream/transport-error` append advances no bound, and the
  schedule still says due, measured from where it was, so the control
  flow retries -- AND only when the holder is not at its bound: the bound
  is terminal (P1). A renewal that lands at or past the bound -- the
  control flow stalled, and the judge may already have lapsed the lease --
  latches `:bound-reached?` and moves nothing, and the latch is sticky:
  no later stale or smaller reading reopens the lease. A renewal recorded
  after `stop` records nothing: the holder's activity has stopped. A
  reading that fails `reading-ok?` records nothing."
  [holder reading outcome]
  (cond
    (or (nil? (:grant holder)) (:released? holder)) holder
    (not (reading-ok? holder reading)) holder
    (at-bound? holder reading) (assoc holder :bound-reached? true)
    (= :dao.stream/ok (:dao.stream/outcome outcome)) (assoc holder :last-renewal-at reading)
    :else holder))


(defn due-to-renew?
  "H2: true when the interval since the later of the last renewal and the
  observed grant has REACHED the composition's renewal interval -- at
  exactly the interval, never later, so a successful next renewal is
  guaranteed before half the duration elapses -- measured against the
  reading the holder's own tick cursor supplies. False before any grant is
  observed: a holder that has not observed a grantor-authored grant holds
  nothing and acts on nothing. False once `stop` has marked the holder
  released: its activity is its own to have stopped. False at and past the
  bound, latched or computed (P1): the holder stops acting at the bound
  whether or not anything has been heard, and nothing past it is due.
  An invalid reading answers nothing. Whether the append then answers ok
  is `observe-renewal`'s to record; nothing here renews on the holder's
  behalf."
  [holder reading]
  (let [{:keys [grant released? renewal-interval units]} holder]
    (boolean
      (and grant
           (not released?)
           (reading-ok? holder reading)
           (not (at-bound? holder reading))
           (at-or-past? units
                        (interval units reading (activity-basis holder))
                        renewal-interval)))))


(defn stop
  "H4, the release discipline: the holder is done. Returns
  `{:holder ... :release ...}` -- the `:released` fact to append, authored
  once and exactly the vocabulary's release (the holder authors no
  `:lapsed`, and there is no reclaim here to perform: the grantor still
  reclaims and records), and the holder with its own activity stopped --
  `due-to-renew?` false from here on. Throws when no grant has been
  observed: a holder that has not observed its grantor-authored grant
  holds nothing, and there is nothing to release.

  Idempotent (a repeated `:released` for one lease is a defect the
  contract names): a second call returns `:release` nil and the holder
  unchanged. A FAILED append is retried with the SAME fact the first
  call returned -- the control flow keeps it and re-appends -- never by
  stopping again for a fresh one."
  [holder]
  (if (:released? holder)
    {:holder holder :release nil}
    (do (check-assembly! (some? (:grant holder))
                         "a holder that has observed no grant holds nothing to release"
                         {:self (:self holder)})
        {:holder (assoc holder :released? true)
         :release (release (get-in holder [:grant :dao.lease/lease]))})))


(defn holding?
  "The one \"may I act?\" predicate, answered for the reading supplied:
  a grant has been observed (the right lease, when expectations were
  given), the holder has neither stopped nor latched its terminal bound,
  the grant was not undersized, and the reading itself is valid and has
  not reached the bound. Taking the reading is what makes this safe: the
  bound latch is set only by a call a conforming flow never makes past
  the bound, so state alone cannot see a stall -- this predicate can.
  It fails closed: an invalid reading answers false (\"unanswerable\"),
  never \"free to act\"."
  [holder reading]
  (boolean (and (some? (:grant holder))
                (not (:released? holder))
                (not (:bound-reached? holder))
                (not (:undersized? holder))
                (reading-ok? holder reading)
                (not (at-bound? holder reading)))))
