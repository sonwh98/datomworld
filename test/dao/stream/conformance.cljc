(ns dao.stream.conformance
  "Declaration-driven contract conformance harness and bounded concurrency oracle
   for DaoStream v2 transports.

   The harness runs licensed law blocks based on the transport manifest:
     - Result shapes, generic envelope validation, and descriptor identity (universal)
     - Reader laws (cursor provenance, non-destructive reading, anchors)
     - Writer laws (append acceptance, sequence ordering)
     - Close laws (idempotent close, frozen availability, post-close outcomes)
     - Manifest outcome exhaustiveness and induction coverage

   The exclusion principle:
     Induction proves that every outcome a manifest declares is inducible.
     It cannot prove that an outcome a manifest excludes is impossible —
     an exclusion is discharged by the absence of a fixture, and absence
     proves nothing.  Each exclusion is therefore a proof obligation,
     discharged by one of three things: a structural argument about the
     transport (there is no code path that could produce it), a property
     law such as complete retention that would observe the outcome if it
     fired, or a transport-specific falsification test.  Fixtures must
     never be required for excluded outcomes: a fixture inducing one
     would contradict the exclusion it is meant to support.  A law that
     performs operations of its own is bound by the same declaration it
     is checking, and must classify what it observes against the manifest
     before interpreting it.

   Concurrency oracle:
     Bounded offline linearizability checking over recorded invocation intervals
     respecting happens-before real-time precedence and checking against an
     abstract sequential stream model."
  (:require [clojure.set :as set]
            [dao.stream :as v2]))


;; =============================================================================
;; Manifest Validation
;; =============================================================================

(defn validate-manifest
  "Validates that manifest adheres to the declaration-driven conformance contract:
   - :dao.stream/type is a qualified keyword.
   - :surfaces is a subset of #{:reader :writer :closable}.
   - :descriptor is always declared in :operations.
   - For every declared operation:
       * :produces is a subset of the contract's outcome set for that op.
       * :exclusions is a map of {excluded-outcome reason-string}.
       * (union produces (keys exclusions)) == contract outcome set for op.
       * (intersection produces (keys exclusions)) is empty.
       * Every exclusion has a non-empty explanation reason.
   - Declared surfaces require their corresponding operations:
       * :reader requires :cursor and :next
       * :writer requires :append!
       * :closable requires :close!
   Returns {:valid? true} or {:valid? false :errors [...]}."
  [manifest]
  (let [errors (atom [])
        ttype (:dao.stream/type manifest)
        surfaces (:surfaces manifest)
        ops-decl (:operations manifest)]

    (when-not (v2/qualified-keyword?* ttype)
      (swap! errors conj {:error :invalid-transport-type :type ttype}))

    (when-not (and (set? surfaces) (set/subset? surfaces v2/surfaces))
      (swap! errors conj {:error :invalid-surfaces :surfaces surfaces :allowed v2/surfaces}))

    (when-not (map? ops-decl)
      (swap! errors conj {:error :missing-operations-map :operations ops-decl}))

    ;; Universal operation: descriptor must always be declared
    (when-not (contains? ops-decl :descriptor)
      (swap! errors conj {:error :missing-descriptor-declaration}))

    ;; Surface-licensed operations
    (when (contains? surfaces :reader)
      (when-not (contains? ops-decl :cursor)
        (swap! errors conj {:error :missing-reader-operation :operation :cursor}))
      (when-not (contains? ops-decl :next)
        (swap! errors conj {:error :missing-reader-operation :operation :next})))

    (when (contains? surfaces :writer)
      (when-not (contains? ops-decl :append!)
        (swap! errors conj {:error :missing-writer-operation :operation :append!})))

    (when (contains? surfaces :closable)
      (when-not (contains? ops-decl :close!)
        (swap! errors conj {:error :missing-closable-operation :operation :close!})))

    ;; Validate each operation's produces/exclusions partition
    (doseq [[op-key decl] ops-decl]
      (let [canon-op (v2/canonical-op op-key)
            contract-outcomes (get v2/operation-outcomes canon-op)]
        (if-not contract-outcomes
          (swap! errors conj {:error :unknown-operation :operation op-key})
          (let [produces (get decl :produces #{})
                exclusions (get decl :exclusions {})
                excluded-outcomes (set (keys exclusions))
                overlap (set/intersection produces excluded-outcomes)
                union-outcomes (set/union produces excluded-outcomes)]
            (when-not (empty? overlap)
              (swap! errors conj {:error :produces-exclusions-overlap
                                  :operation canon-op
                                  :overlap overlap}))
            (when-not (= union-outcomes contract-outcomes)
              (swap! errors conj {:error :incomplete-outcome-partition
                                  :operation canon-op
                                  :declared union-outcomes
                                  :expected contract-outcomes
                                  :missing (set/difference contract-outcomes union-outcomes)
                                  :unexpected (set/difference union-outcomes contract-outcomes)}))
            (doseq [[ex-outcome reason] exclusions]
              (when-not (and (string? reason) (pos? (count reason)))
                (swap! errors conj {:error :missing-exclusion-reason
                                    :operation canon-op
                                    :outcome ex-outcome
                                    :reason reason})))))))

    (if (empty? @errors)
      {:valid? true}
      {:valid? false :errors @errors})))


;; =============================================================================
;; Conformance Test Runner & Law Blocks
;; =============================================================================

(defn- run-induction-coverage
  "Asserts manifest induction:
   1. For each declared outcome in :produces, the fixture induces it.
   2. No observed outcome falls outside the declared :produces set.
   3. Every produced result satisfies v2/valid-outcome?."
  [manifest]
  (let [violations (atom [])
        fixtures (:fixtures manifest {})
        ops-decl (:operations manifest {})]
    (doseq [[op-key decl] ops-decl]
      (let [canon-op (v2/canonical-op op-key)
            produces (get decl :produces #{})
            op-fixtures (get fixtures op-key {})]
        (doseq [outcome produces]
          (if-let [inducer (get op-fixtures outcome)]
            (let [res (inducer)
                  observed-outcome (:dao.stream/outcome res)]
              ;; Check 1: Inducer actually produces the declared outcome
              (when-not (= observed-outcome outcome)
                (swap! violations conj {:check :induction-mismatch
                                        :operation canon-op
                                        :expected-outcome outcome
                                        :observed-outcome observed-outcome
                                        :result res}))
              ;; Check 2: Result is not outside declared produces
              (when-not (contains? produces observed-outcome)
                (swap! violations conj {:check :undeclared-outcome-observed
                                        :operation canon-op
                                        :outcome observed-outcome
                                        :allowed produces}))
              ;; Check 3: Result map is valid (required keys present, open map)
              (when-let [defect (v2/validate-outcome canon-op res)]
                (swap! violations conj {:check :invalid-outcome-shape
                                        :operation canon-op
                                        :defect defect})))
            (swap! violations conj {:check :missing-fixture-inducer
                                    :operation canon-op
                                    :outcome outcome})))))
    @violations))


(defn- run-descriptor-laws
  "Asserts descriptor projection laws on every handle:
   - Handle satisfies IDaoStreamDescriptor regardless of surface.
   - descriptor returns :dao.stream/ok.
   - Sibling identity matches identity inside descriptor envelope.
   - Descriptor envelope is valid."
  [handle]
  (let [violations (atom [])]
    (if-not (v2/descriptor? handle)
      (swap! violations conj {:check :handle-missing-descriptor-protocol})
      (let [res (v2/descriptor handle)]
        (when-not (= :dao.stream/ok (:dao.stream/outcome res))
          (swap! violations conj {:check :descriptor-outcome-not-ok :result res}))
        (when-not (v2/descriptor-identity-consistent? res)
          (swap! violations conj {:check :descriptor-identity-inconsistent :result res}))))
    @violations))


(defn- run-reader-laws
  "Asserts reader laws on a handle with reader surface:
   - Handle satisfies IDaoStreamReader.
   - Anchors :dao.stream/oldest and :dao.stream/newest mint valid cursors.
   - Non-destructive reading: two cursors reading same position observe same value.
   - Next returns valid outcomes and successor cursors."
  [handle]
  (let [violations (atom [])]
    (if-not (v2/reader? handle)
      (swap! violations conj {:check :handle-missing-reader-protocol})
      (do
        ;; Mint oldest and newest
        (let [r-oldest (v2/cursor handle :dao.stream/oldest)]
          (when-not (and (= :dao.stream/ok (:dao.stream/outcome r-oldest))
                         (contains? r-oldest :dao.stream/cursor))
            (swap! violations conj {:check :cursor-oldest-failed :result r-oldest})))
        (let [r-newest (v2/cursor handle :dao.stream/newest)]
          (when-not (and (= :dao.stream/ok (:dao.stream/outcome r-newest))
                         (contains? r-newest :dao.stream/cursor))
            (swap! violations conj {:check :cursor-newest-failed :result r-newest})))
        ;; Non-destructive read test if elements present
        (let [invalid-anchor (v2/cursor handle ::invalid-anchor)
              _ (when-not (= :dao.stream/invalid-anchor
                             (:dao.stream/outcome invalid-anchor))
                  (swap! violations conj {:check :invalid-anchor-not-rejected
                                          :result invalid-anchor}))
              c0 (:dao.stream/cursor (v2/cursor handle :dao.stream/oldest))
              res1 (v2/next handle c0)
              res2 (v2/next handle c0)]
          (when-not (= res1 res2)
            (swap! violations conj {:check :destructive-read-detected
                                    :first-read res1
                                    :second-read res2}))
          ;; The only legal way for a contract consumer to advance is to feed
          ;; a stream-minted successor (or gap recovery cursor) back to next.
          ;; Do not inspect cursor shape: it belongs to the transport.
          (when (contains? #{:dao.stream/ok :dao.stream/gap}
                           (:dao.stream/outcome res1))
            (let [successor (:dao.stream/cursor res1)
                  follow-up (v2/next handle successor)]
              (when-not (v2/valid-outcome? :next follow-up)
                (swap! violations conj {:check :invalid-successor-or-recovery-cursor
                                        :source res1 :follow-up follow-up})))))))
    @violations))


(defn- run-writer-laws
  "Asserts writer laws on a handle with writer surface:
   - Handle satisfies IDaoStreamWriter.
   - append! returns valid outcome."
  [handle test-val]
  (let [violations (atom [])]
    (if-not (v2/writer? handle)
      (swap! violations conj {:check :handle-missing-writer-protocol})
      (let [res (v2/append! handle test-val)]
        (when-not (v2/valid-outcome? :append! res)
          (swap! violations conj {:check :invalid-append-outcome :result res}))))
    @violations))


(defn- run-close-laws
  "Asserts close laws on a handle with closable surface:
   - Handle satisfies IDaoStreamClosable.
   - close! returns :dao.stream/ok.
   - close! is idempotent: subsequent close! returns :dao.stream/ok."
  [handle]
  (let [violations (atom [])]
    (if-not (v2/closable? handle)
      (swap! violations conj {:check :handle-missing-closable-protocol})
      (let [res1 (v2/close! handle)
            res2 (v2/close! handle)]
        (when-not (= :dao.stream/ok (:dao.stream/outcome res1))
          (swap! violations conj {:check :close-outcome-not-ok :result res1}))
        (when-not (= :dao.stream/ok (:dao.stream/outcome res2))
          (swap! violations conj {:check :close-idempotence-failed :result res2}))))
    @violations))


(defn run-conformance-suite
  "Executes the full declaration-driven conformance suite against manifest and optional handle.
   Returns {:passed? true} or {:passed? false :failures [...]}."
  ([manifest]
   (run-conformance-suite manifest (when-let [hf (:handle-factory manifest)] (hf))))
  ([manifest handle]
   (let [failures (atom [])
         manifest-res (validate-manifest manifest)
         ;; Each destructive law block gets a fresh transport instance.  The
         ;; manifest factory is the transport's responsibility; the supplied
         ;; handle remains a compatibility fallback for older callers.
         fresh-handle (fn []
                        (if-let [factory (:handle-factory manifest)]
                          (factory)
                          handle))]
     (if-not (:valid? manifest-res)
       {:passed? false :failures (:errors manifest-res)}
       (do
         ;; 1. Induction & exhaustiveness check
         (let [ind-v (run-induction-coverage manifest)]
           (when (seq ind-v)
             (swap! failures concat ind-v)))

         ;; 2. Descriptor identity laws (always checked)
         (when (fresh-handle)
           (let [desc-v (run-descriptor-laws (fresh-handle))]
             (when (seq desc-v)
               (swap! failures concat desc-v)))

           ;; 3. Reader laws (only if reader declared)
           (when (contains? (:surfaces manifest) :reader)
             (let [r-v (run-reader-laws (fresh-handle))]
               (when (seq r-v)
                 (swap! failures concat r-v))))

           ;; 4. Writer laws (only if writer declared)
           (when (contains? (:surfaces manifest) :writer)
             (let [w-v (run-writer-laws (fresh-handle) ::conformance-token)]
               (when (seq w-v)
                 (swap! failures concat w-v))))

           ;; 5. Close laws (only if closable declared)
           (when (contains? (:surfaces manifest) :closable)
             (let [c-v (run-close-laws (fresh-handle))]
               (when (seq c-v)
                 (swap! failures concat c-v)))))

         (if (empty? @failures)
           {:passed? true}
           {:passed? false :failures @failures}))))))


;; =============================================================================
;; Concurrency Oracle (Bounded Offline Linearizability Checker)
;; =============================================================================

(defn record-op!
  "Helper to record an operation invocation interval into a history atom.
   Returns the result of executing (f)."
  [history-atom clock-atom op args f]
  (let [start (swap! clock-atom inc)
        result (f)
        finish (swap! clock-atom inc)]
    (swap! history-atom conj {:id (count @history-atom)
                              :op op
                              :args args
                              :result result
                              :start start
                              :end finish})
    result))


(defn- happens-before?
  "True if op A completed before op B was invoked (A ≺ B)."
  [op-a op-b]
  (< (:end op-a) (:start op-b)))


(defn- legal-next-ops
  "Given remaining operations to schedule and already scheduled operations,
   returns operations from remaining that have no un-scheduled predecessors
   in the happens-before partial order."
  [remaining scheduled]
  (filter (fn [candidate]
            (not-any? (fn [other]
                        (and (not (contains? scheduled other))
                             (happens-before? other candidate)))
                      remaining))
          remaining))


(defn check-linearizability
  "Offline bounded linearizability checker.
   history: vector of {:id .. :op .. :args .. :result .. :start .. :end ..}
   initial-model: abstract sequential state
   step-fn: (fn [model-state op args result]) -> next-state or nil if invalid transition.

   Returns {:linearizable? true :linearization [...]} if a valid sequential
   interleaving respecting happens-before exists, or {:linearizable? false}."
  [history initial-model step-fn]
  (let [total (count history)]
    (letfn [(search
              [current-model remaining scheduled-set acc]
              (if (empty? remaining)
                {:linearizable? true :linearization acc}
                (let [candidates (legal-next-ops remaining scheduled-set)]
                  (loop [cands candidates]
                    (if (empty? cands)
                      nil
                      (let [candidate (first cands)
                            next-model (step-fn current-model
                                                (:op candidate)
                                                (:args candidate)
                                                (:result candidate))]
                        (if next-model
                          (let [sub (search next-model
                                            (disj remaining candidate)
                                            (conj scheduled-set candidate)
                                            (conj acc candidate))]
                            (if (:linearizable? sub)
                              sub
                              (recur (rest cands))))
                          (recur (rest cands)))))))))]
      (or (search initial-model (set history) #{} [])
          {:linearizable? false
           :total total
           :reason :no-legal-linearization}))))


;; =============================================================================
;; Pure Abstract Model for Stream & Ring Buffer Linearizability
;; =============================================================================

(defn make-abstract-stream-model
  "Creates the pure abstract sequential model for a stream/ringbuffer:
   - stream-identity: logical-stream identity
   - capacity: nil (unbounded) or integer (evict-oldest bound)
   - initial-elements: optional initial sequence of values"
  ([stream-identity]
   (make-abstract-stream-model stream-identity nil []))
  ([stream-identity capacity]
   (make-abstract-stream-model stream-identity capacity []))
  ([stream-identity capacity initial-elements]
   (make-abstract-stream-model stream-identity capacity initial-elements {}))
  ([stream-identity capacity initial-elements options]
   {:stream-identity stream-identity
    :capacity capacity
    :elements (vec (map-indexed (fn [i v] {:pos i :val v}) initial-elements))
    :next-pos (count initial-elements)
    :closed? false
    ;; Attachment entries are supplied by the test composition.  A close on an
    ;; attachment freezes its own visible tail, not the logical stream.
    :attachments (or (:attachments options) {})
    ;; Cursor layout is transport-owned.  The model receives projection
    ;; functions rather than depending on a particular map key convention.
    :cursor-projector
    (or (:cursor-projector options)
        {:identity (fn [cursor] (:stream-identity cursor))
         :position (fn [cursor] (:position cursor))})}))


(defn- invocation
  "Normalizes legacy positional arguments and the extensible oracle argument
   shape.  New histories may use
   {:dao.stream/args [...] :dao.stream/handle {:attachment <id>}}; a missing
   handle means the logical-stream owner."
  [args]
  (if (and (map? args) (contains? args :dao.stream/args))
    args
    {:dao.stream/args args :dao.stream/handle {:owner? true}}))


(defn- attachment-id
  [inv]
  (get-in inv [:dao.stream/handle :attachment]))


(defn- attachment-state
  [state inv]
  (when-let [id (attachment-id inv)]
    (get-in state [:attachments id])))


(defn- visible-tail
  [state inv]
  (or (:tail (attachment-state state inv)) (:next-pos state)))


(defn- cursor-identity
  [state cursor]
  (when (map? cursor)
    ((get-in state [:cursor-projector :identity]) cursor)))


(defn- cursor-position
  [state cursor]
  (when (map? cursor)
    ((get-in state [:cursor-projector :position]) cursor)))


(defn- result-cursor-position
  [state result]
  (cursor-position state (:dao.stream/cursor result)))


(defn- retained-first
  [state]
  (or (:pos (first (:elements state))) (:next-pos state)))


(defn abstract-stream-step
  "Pure abstract transition function for stream operations:
   state: abstract model state
   op: :append!, :close!, :cursor, or :next
   args: arguments passed to operation
   result: observed outcome map from operation
   Returns next state if the operation and outcome are legally accepted by
   the abstract sequential model; returns nil if transition is invalid."
  [state op args result]
  (let [outcome (:dao.stream/outcome result)
        inv (invocation args)
        positional (:dao.stream/args inv)
        attachment (attachment-state state inv)
        handle-closed? (and attachment (not (:open? attachment)))]
    (case op
      :append!
      (let [[_val] positional]
        (if (or (:closed? state) handle-closed?)
          (when (= outcome :dao.stream/closed) state)
          (when (= outcome :dao.stream/ok)
            (let [pos (:next-pos state)
                  new-elem {:pos pos :val (first positional)}
                  new-elements (conj (:elements state) new-elem)
                  trimmed-elements (if (and (:capacity state)
                                            (> (count new-elements) (:capacity state)))
                                     (subvec new-elements (- (count new-elements) (:capacity state)))
                                     new-elements)]
              (assoc state
                     :elements trimmed-elements
                     :next-pos (inc pos))))))

      :close!
      (when (= outcome :dao.stream/ok)
        (if-let [id (attachment-id inv)]
          (if (get-in state [:attachments id])
            (if (get-in state [:attachments id :open?])
              (assoc-in state [:attachments id]
                        {:open? false :tail (:next-pos state)})
              state)
            nil)
          (assoc state :closed? true)))

      :cursor
      (let [[anchor] positional]
        (if handle-closed?
          (when (= outcome :dao.stream/closed) state)
          (case anchor
            :dao.stream/oldest
            (when (and (= outcome :dao.stream/ok)
                       (map? (:dao.stream/cursor result)))
              (let [expected-pos (min (retained-first state) (visible-tail state inv))]
                (when (= (result-cursor-position state result) expected-pos) state)))

            :dao.stream/newest
            (when (and (= outcome :dao.stream/ok)
                       (map? (:dao.stream/cursor result)))
              (when (= (result-cursor-position state result) (visible-tail state inv)) state))

            ;; Invalid anchor
            (when (= outcome :dao.stream/invalid-anchor)
              state))))

      :next
      (let [[cur] positional]
        (cond
          (not (map? cur))
          (when (= outcome :dao.stream/invalid-cursor) state)

          (nil? (cursor-identity state cur))
          (when (= outcome :dao.stream/invalid-cursor) state)

          (not= (:stream-identity state) (cursor-identity state cur))
          (when (= outcome :dao.stream/cursor-mismatch) state)

          (not (integer? (cursor-position state cur)))
          (when (= outcome :dao.stream/invalid-cursor) state)

          :else
          (let [pos (cursor-position state cur)
                first-pos (retained-first state)
                tail (visible-tail state inv)]
            (cond
              ;; Evicted position -> gap. Recovery names the earliest retained
              ;; position, even when a closed attachment's frozen tail is gone.
              (< pos first-pos)
              (when (and (= outcome :dao.stream/gap)
                         (= first-pos (result-cursor-position state result)))
                state)

              ;; Retained position visible through this handle -> ok.
              (< pos tail)
              (let [elem (first (filter #(= (:pos %) pos) (:elements state)))]
                (when (and elem (= outcome :dao.stream/ok)
                           (= (:val elem) (:dao.stream/value result))
                           (= (inc pos) (result-cursor-position state result)))
                  state))

              ;; A frozen attachment is at end even while the logical stream
              ;; remains open.  Otherwise logical close determines end/blocked.
              :else
              (if (or handle-closed? (:closed? state))
                (when (= outcome :dao.stream/end) state)
                (when (= outcome :dao.stream/blocked) state))))))

      ;; Unknown op
      nil)))
