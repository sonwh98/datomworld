(ns yin.vm.ucf
  "Universal Continuation Format, Phase 1: code identity and safepoints
   (`docs/design/yin.vm.universal-continuation-format.md` S7.3, S7.4).

   Two static halves of the format live here, both pure functions over
   data:

   - S7.3 code identity. `batch->canonical-instruction-vector` reads a
     well-formed `:yin.code/*` segment batch through the loader's resolved
     interpretation (index-batch collapse, last value wins) and produces
     the positional instruction tuple vector -- pc is the index, each tuple
     is its mnemonic plus exactly the S2.4 operands in table order, every
     ref a resolved pc, every defaulted operand materialized. The address
     of that vector is `(dao.jing/segment-key v)`; the execution contract
     it is interpreted under is `contract-stamp`. `canonicalize` bundles
     the three into one S7.9 outcome map.

   - S7.4 safepoints. `safepoints` derives, from the canonical vector
     alone, the static half of the safepoint map a foreign engine
     publishes: the parking transitions, their canonical resume pcs, the
     stack effect across the parking instruction, and the names the code
     reachable from the resume pc can read from E. The dynamic half --
     absolute depth, captured bindings, activation bases -- is never
     derived: `activation-state` reads it off a frame, which is the only
     place it exists.

   Content addressing is delegated entirely to `dao.jing`; nothing here
   knows how a vector is serialized."
  (:require [dao.jing :as jing]
            [yin.vm :as vm]
            [yin.vm.code :as code]))


;; =============================================================================
;; The execution contract (S7.3.3)
;; =============================================================================

(def contract-stamp
  "The complete execution contract a canonical vector is interpreted under:
   `:yin.code/contract` names the published revision of the tuple grammar,
   S2.6 rules, last-value-wins resolution, opcode table, transitions,
   resolution precedence, effect outcomes and scheduler
   (`yin.vm.semantic.md` S2.4); `:yin.k/version` is this envelope's own
   version. A resumer on another revision answers `:yin.k/profile-mismatch`
   before lowering; a code index is keyed per stamp."
  {:yin.code/contract "v2",
   :yin.k/version 0})


;; =============================================================================
;; The canonical instruction vector (S7.3.2)
;; =============================================================================

(def ^:private segment-attrs
  "Segment-entity attributes the canonical form folds away or excludes:
   the type and length are implied by the vector, provenance and the hash
   itself are excluded. Anything else on the segment entity is a fact the
   vector has no slot for."
  #{:yin.code/type :yin.code/length :yin.code/derived-from :yin.code/hash})


(def ^:private structural-attrs
  "Instruction attributes that position and the mnemonic replace."
  #{:yin.code/segment :yin.code/pc :yin.code/op})


(def ^:private excluded-instruction-attrs
  "Instruction attributes excluded from identity: provenance."
  #{:yin.code/source})


(def ^:private saturation-defaults
  "The loader's defaults, materialized so an omitted operand and a stated
   one canonicalize identically (S7.3.2, `yin.vm.semantic.md` S2.4)."
  {[:gensym :yin.code/prefix] "id",
   [:stream-make :yin.code/buffer] vm/default-stream-capacity,
   [:ffi-call :yin.code/argc] 0,
   [:call :yin.code/tail?] false})


(defn- index-batch
  "Entity ids in order of first appearance, and each entity's attribute
   map. A repeated single-valued attribute keeps its last value in datom
   order -- the resolved interpretation the loader executes (S7.3.2)."
  [datoms]
  (reduce (fn [[order attrs] [e a v]]
            [(if (contains? attrs e) order (conj order e))
             (assoc-in attrs [e a] v)])
          [[] {}]
          datoms))


(defn refusal
  "The S7.9 `:yin.k/non-portable` outcome for a batch that has no canonical
   vector, naming the offending place (`:yin.k/path`) and the defect."
  [path defect hint]
  {:yin.k/status :yin.k/non-portable,
   :yin.k/kind :non-canonicalizable,
   :yin.k/path path,
   :yin.k/defect defect,
   :yin.k/hint hint})


(defn- refuse
  [path defect hint]
  (throw (ex-info (str "Cannot canonicalize segment: " hint)
                  (refusal path defect hint))))


(defn- resolved-batch
  "The loader's reading of a batch: `{:seg :attrs :instructions}` with
   instructions in pc order. A batch that is not well formed (S2.6) is not
   a segment, and is refused with the loader's own defect."
  [datoms]
  (when-let [defect (code/well-formed? datoms)]
    (refuse [:entity (:entity defect)]
            defect
            (str "the batch is not well formed: " (name (:rule defect)))))
  (let [[order attrs] (index-batch datoms)
        seg (some #(when (= :segment (get-in attrs [% :yin.code/type])) %)
                  order)
        instructions (filterv (fn [e]
                                (and (not= seg e)
                                     (some #(contains? (get attrs e) %)
                                           structural-attrs)))
                              order)]
    {:seg seg, :attrs attrs, :instructions instructions}))


(defn- check-segment-entity
  [seg attrs]
  (let [extra (remove segment-attrs (keys (get attrs seg)))]
    (when (seq extra)
      (refuse [:entity seg]
              {:rule :segment-attribute, :entity seg, :attrs (vec extra)}
              "the segment entity carries an attribute with no slot"))))


(defn- canonical-tuple
  "One instruction's tuple: the mnemonic, then its S2.4 operands in table
   order, refs resolved to pcs and defaults materialized. Refused when the
   mnemonic has no tuple or the instruction carries an operand its tuple
   has no slot for."
  [pc-of pc e ia]
  (let [mnem (:yin.code/op ia)
        operands (get code/vector-operand-table mnem)]
    (when-not operands
      (refuse [:pc pc]
              {:rule :mnemonic, :pc pc, :entity e, :op mnem}
              "the mnemonic is outside the S2.4 table"))
    (let [slots (into #{} (map first) operands)
          extra (remove (fn [a]
                          (or (structural-attrs a)
                              (excluded-instruction-attrs a)
                              (slots a)))
                        (keys ia))]
      (when (seq extra)
        (refuse [:pc pc]
                {:rule :operand-slot, :pc pc, :entity e, :attrs (vec extra)}
                "the instruction carries an operand its tuple has no slot for"))
      (into [mnem]
            (map (fn [[a kind]]
                   (let [v (get ia a)]
                     (cond
                       (= :pc kind)
                       (or (get pc-of v)
                           (refuse [:pc pc]
                                   {:rule :dangling-target, :pc pc,
                                    :entity e, :ref v}
                                   "a ref resolves to no instruction"))
                       (nil? v) (get saturation-defaults [mnem a])
                       :else v))))
            operands))))


(defn batch->canonical-instruction-vector
  "The canonical instruction vector (S7.3.2) of one `:yin.code/*` segment
   batch, or a thrown `:yin.k/non-portable` refusal (`refusal`, kind
   `:non-canonicalizable`) when the batch has none.

   The batch is read through the loader's resolved interpretation, so two
   batches that execute alike canonicalize alike: entity ids, datom order
   within an entity, provenance, and omitted defaulted operands leave the
   vector unchanged; a repeated attribute keeps its last value. The result
   is checked against the S7.5 tuple grammar before it is returned, so a
   vector this function yields is always one `load-vector` accepts."
  [datoms]
  (let [{:keys [seg attrs instructions]} (resolved-batch datoms)
        pc-of (zipmap instructions (range))]
    (check-segment-entity seg attrs)
    (let [v (into []
                  (map-indexed (fn [pc e]
                                 (canonical-tuple pc-of pc e (get attrs e))))
                  instructions)]
      (when-let [defect (code/well-formed-vector? v)]
        (refuse [:pc (:pc defect)]
                defect
                (str "the resolved tuple is not admissible: "
                     (name (:rule defect)))))
      v)))


(defn code-address
  "`:yin.code/hash` of a canonical vector: its `dao.jing` segment address.
   UCF mints no second addressing scheme."
  [v]
  (jing/segment-key v))


(defn canonicalize
  "Code identity as one S7.9 outcome. On success
   `{:yin.k/status :yin.k/ok, :yin.code/vector v, :yin.code/hash address,
   :yin.k/contract contract-stamp}`; otherwise the `refusal` map the
   vector function would have thrown."
  [datoms]
  (try
    (let [v (batch->canonical-instruction-vector datoms)]
      {:yin.k/status :yin.k/ok,
       :yin.code/vector v,
       :yin.code/hash (code-address v),
       :yin.k/contract contract-stamp})
    (catch #?(:cljd Object :clj Throwable :cljs :default) e
      (let [data (ex-data e)]
        (if (= :yin.k/non-portable (:yin.k/status data))
          data
          (throw e))))))


;; =============================================================================
;; Safepoints (S7.4)
;; =============================================================================

(def transitions
  "Every S2.4 mnemonic classified by what its S4.2 transition does to
   control. `:step` continues at pc+1; `:jump`, `:branch` and `:transfer`
   move control (a `:resume` restores a parked configuration, so its
   successor is not pc+1); `:terminal` leaves no successor; `:parking` may
   leave the machine parked with a canonical configuration at pc+1 -- the
   safepoints of S7.4.1. `:call` is parking because the operator may be a
   primitive whose result is a blocking effect; `:current-continuation`
   is a step, not a safepoint (it reifies and continues)."
  {:const :step,
   :var :step,
   :closure :step,
   :push :step,
   :gensym :step,
   :store-get :step,
   :store-put :step,
   :stream-make :step,
   :stream-cursor :step,
   :stream-close :step,
   :current-continuation :step,
   :jump :jump,
   :branch-false :branch,
   :resume :transfer,
   :return :terminal,
   :halt :terminal,
   :park :parking,
   :stream-next :parking,
   :stream-put :parking,
   :ffi-call :parking,
   :call :parking})


(def ^:private parking-reasons
  "S7.4.1's safepoint kinds, by parking mnemonic. An `:ffi-call` parks
   under two reasons -- sent, or retained while the request is in hand --
   at one safepoint."
  {:park [:park],
   :stream-next [:next],
   :stream-put [:put],
   :ffi-call [:ffi :ffi-request],
   :call [:call-effect]})


(defn stack-effect
  "Delta |St| across one parking instruction, from the tuple alone
   (S7.4.1's `Stack in frame` column): a park and a blocked read leave St;
   a blocked write has popped its target; an FFI call its arguments; an
   effectful call its operator and arguments."
  [t]
  (case (nth t 0)
    :park 0
    :stream-next 0
    :stream-put -1
    :ffi-call (- (nth t 2))
    :call (- (inc (nth t 1)))
    nil))


(defn- successors
  "The pcs control may reach next from `pc`, with the names bound on the
   way: a closure's body is entered with its params bound, everything else
   keeps the current binding set."
  [v pc bound]
  (let [t (nth v pc)
        mnem (nth t 0)]
    (case (get transitions mnem)
      :step (if (= :closure mnem)
              [[(inc pc) bound] [(nth t 2) (into bound (nth t 1))]]
              [[(inc pc) bound]])
      :parking [[(inc pc) bound]]
      :jump [[(nth t 1) bound]]
      :branch [[(inc pc) bound] [(nth t 1) bound]]
      [])))


(defn lexically-required
  "The names the code reachable from `pc` reads from E, from the vector
   alone (S7.4.2): every `:var` name on a control path from `pc` --
   through fall-through, jumps, branches, and the bodies of closures
   created on the way -- minus the names those closures bind. Callees are
   values, not code reachable here: a closure already on the stack carries
   its own captured environment, which is the frame's business. Sorted by
   name so the result is a stable value."
  [v pc]
  (loop [work [[pc #{}]]
         seen #{}
         names #{}]
    (if-let [[p bound :as item] (peek work)]
      (let [work (pop work)]
        (if (contains? seen item)
          (recur work seen names)
          (let [t (nth v p)
                names (if (and (= :var (nth t 0))
                               (not (contains? bound (nth t 1))))
                        (conj names (nth t 1))
                        names)]
            (recur (into work (successors v p bound))
                   (conj seen item)
                   names))))
      (vec (sort-by str names)))))


(defn safepoints
  "The static safepoint map of one canonical vector (S7.4.2): one entry per
   parking instruction, each carrying only what the vector alone fixes --
   the parking pc, the canonical resume pc (pc+1, always inside the segment
   by S2.6), the reasons under which the machine parks there, the stack
   effect across the instruction, and the lexically required names at the
   resume pc. Absolute depth, activation bases, captured environments and
   physical layouts are not here: they ride in the frame
   (`activation-state`)."
  [v]
  (into []
        (keep-indexed
          (fn [pc t]
            (when (= :parking (get transitions (nth t 0)))
              {:yin.safepoint/at pc,
               :yin.safepoint/pc (inc pc),
               :yin.safepoint/reasons (get parking-reasons (nth t 0)),
               :yin.safepoint/stack-effect (stack-effect t),
               :yin.safepoint/lexically-required
               (lexically-required v (inc pc))})))
        v))


(defn safepoint-table
  "`safepoints` published per segment, keyed by the code's address and the
   engine profile that derived it (the reference machine by default)."
  ([v] (safepoint-table v :yin.vm.semantic/reference))
  ([v engine]
   {:yin.safepoint/segment (code-address v),
    :yin.safepoint/engine engine,
    :yin.safepoint/safepoints (safepoints v)}))


(defn safepoint-at
  "The static safepoint entry whose canonical resume pc is `pc`, or the
   S7.9 `:yin.k/not-at-safepoint` outcome naming the pc."
  [v pc]
  (or (some #(when (= pc (:yin.safepoint/pc %)) %) (safepoints v))
      {:yin.k/status :yin.k/not-at-safepoint, :yin.k/pc pc}))


(defn activation-state
  "The dynamic half of a parked configuration, read off a frame -- a wait
   entry or parked record `{segment pc env stack k}` -- never derived from
   code: the absolute operand-stack depth, the names E actually binds, and
   each return frame's activation base."
  [frame]
  {:yin.k/depth (count (:stack frame)),
   :yin.k/bound (vec (sort-by str (keys (:env frame)))),
   :yin.k/stack-bases (mapv :stack-base (:k frame))})


(defn unsatisfied-names
  "The lexically required names of a static safepoint that the frame's
   environment does not bind and that `resolvable` (store, primitives,
   modules -- the rest of `resolve-var`'s order) does not answer: the
   conformance check that a frame carries what its code will read."
  [safepoint frame resolvable]
  (vec (remove (fn [n]
                 (or (contains? (:env frame) n) (resolvable n)))
               (:yin.safepoint/lexically-required safepoint))))
