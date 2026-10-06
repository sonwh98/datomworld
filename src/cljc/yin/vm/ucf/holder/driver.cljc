(ns yin.vm.ucf.holder.driver
  "The candidate half of the custody driver of M-next D slice D13 (UCF
   7.7.2 to 7.7.8, 7.8, 7.9; r3 1.5, 1.6 and 1.7; the D10 lower-inputs
   ruling; linker-dht 14.2.2): one explicit composition-local step state,
   stepped by the composition, that carries a candidate from bytes to a
   task under custody and then drives that task's run.

   The driver reaches the authority through exactly the three remote
   paths of r3 1.5, all composition-supplied functions: the front's
   request and reply streams (`append-request!` and `read-reply!`), the
   outcome projection (`read-outcome!`), and the authenticated ledger
   reader (`read-ledger!`, whose attributed records D3's
   `read-evidence` and `custody/binding-evidence` fold).  The lease
   clock (`clock`) is the composition's too, and dao.lease's own holder
   discipline -- `observe-grant`, `holding?`, `due-to-renew?`,
   `observe-renewal`, `stop` -- is the tenure arithmetic over it.

   The phases, one `:phase` key:

   :validating -- fetch and validate with zero side effects: the D7
   reader over the bytes (`resume-task` before `checkpoint/inspect`, that
   supplies no grant, so nothing is attached and no machine is exposed)
   answers `:yin.k/awaiting-grant` for a valid version-1 blocked or
   parked root; anything else is a data refusal with no cleanup owed.  A
   halted version-1 root is not a lease subject: it lowers once, gated
   `:ended`, and the driver publishes it with no custody.

   :proposing -- check the authority attachment (a missing one is
   `:yin.k/unsatisfied` naming the arbitration), propose through the
   front with a fresh proposal id, and observe the grant from the
   ledger: while the proposal is unanswered the step answers
   `:yin.k/awaiting-grant`; a grant to another, or an occurrence that
   can never be granted again, answers `:yin.k/not-holder` with the
   lease state observed and releases nothing.  A grant to this driver
   is accepted only whole: `custody/binding-evidence` over the same
   records (an invalid binding -- none, a duplicate, an inexact epoch,
   one outside the grant's transaction, one that mismatches its grant
   -- is `:yin.k/not-holder` followed by a release), then D3's
   `read-evidence` (unavailable history is `:yin.k/unsatisfied`, never
   an empty prefix, and never a release: custody was not accepted),
   then the admitted-variant rule (the address must be among the fold's
   admitted variants of the granted occurrence -- equality of supplied
   addresses proves nothing), then current tenure: the fold's live
   lease at the binding's epoch and dao.lease's bound, rechecked before
   any execution or IO is scheduled.  Only then D10's lower runs, with
   the checkpoint address, the composition's protection declaration and
   the grant evidence; a lower failure is after the grant, and releases
   first.  A regrant replays from the checkpoint: the lower starts
   input at zero under the evidence's prefix.

   :running -- run under the gate and drive the writer's and reader's
   steps: tenure rechecked, the renewal sent through D2's
   `:yin.k/renewal` request before half the duration and never past the
   bound, the machine's internal computation, replay below the
   frontier, `writer/emit`, `reader/step`, and every attributed record
   the readers answer routed to the writer's `discharge`, the reader's
   `settle` or this driver's own control arm.  All IO stops at the
   bound; a run end (:stale, :intent-conflict, :input-conflict, a
   replay divergence, the bound) gates the machine `:ended`, appends
   one diagnostic, and releases -- cleanup, not recovery: a release
   never clears a quarantine, never completes without accepted
   completion evidence, and the occurrence of an ordinary failed run
   stays regrantable under the ordinary policy.

   :safepoint -- the machine has nothing left to drive (halted, no wait
   entry, no queued work); the exit half (D14) owns what follows.
   Tenure is still held: renewal and the recheck continue.

   :releasing -- a pending release is retried with the identical
   request until authenticated carriage is acknowledged, then its failure is
   the driver's answer.  :failed is terminal.

   The source is a candidate like any other: the driver holds bytes,
   never the source's local machine, so the source itself resumes only
   through a grant to itself, never on data.  No transport is known
   here; the machine's own resource handles are the only ones touched,
   by the writer and the reader."
  (:require [dao.lease :as lease]
            [dao.stream :as stream]
            [yin.vm :as vm]
            [yin.vm.ucf.authority.front :as front]
            [yin.vm.ucf.checkpoint :as checkpoint]
            [yin.vm.ucf.custody :as custody]
            [yin.vm.ucf.handoff :as handoff]
            [yin.vm.ucf.holder.evidence :as evidence]
            [yin.vm.ucf.holder.reader :as reader]
            [yin.vm.ucf.holder.writer :as writer]
            [yin.vm.ucf.ledger :as ledger]))


;; =============================================================================
;; Assembly
;; =============================================================================

(def ^:private classes
  "The three protection classes of r3 1.11."
  #{:enrolled :at-least-once :fail-stop})


(def ^:private seams
  "The composition-supplied functions the driver steps through."
  [:append-request! :read-reply! :read-outcome! :read-ledger!
   :clock :observe! :append-diagnostic! :attach!])


(defn- check!
  [ok what data]
  (when-not ok
    (throw (ex-info (str "The candidate driver needs " what)
                    (into {} (cons {:yin.k/hint :assembly} data))))))


(defn- check-interval!
  "The renewal interval's own shape gate, as dao.lease's holder runs it
   at assembly: a duration, in a unit of the table, within the per-unit
   bound.  The sizing against the granted duration -- strictly below
   half -- is checked where the two are known together, at the grant."
  [units interval]
  (check! (lease/duration? interval)
          "a renewal interval: a single-entry {unit positive-integer} map"
          {:renewal-interval interval})
  (let [[unit magnitude] (first interval)]
    (check! (some? (get units unit))
            "a renewal interval whose unit is in the unit table"
            {:renewal-interval interval :units units})
    (check! (<= magnitude (quot lease/magnitude-limit (get units unit)))
            "a renewal interval within the per-unit bound"
            {:renewal-interval interval})))


(defn initial
  "The candidate's composition-local step state over `config`:

     :me                this candidate's holder identity
     :bytes             the checkpoint bytes the composition fetched
     :address           the segment address they were fetched under
     :protection        the composition's declaration, stream identity to
                        class (:enrolled, :at-least-once or :fail-stop)
     :renewal-interval  the composition's interval, sized strictly below
                        half the duration it arranges (H2/S1)
     :units             optional unit table, dao.lease's default
     :receiver          the receiving machine of the receiver's own
                        composition
     :attach!           the receiver's :dao.stream/attach dispatch
     :append-request!   (fn [request] append-outcome), the holder's
                        inbound stream
     :read-reply!       (fn [] [author reply] | nil), the front's replies
     :read-outcome!     (fn [] [author outcome] | nil), the outcome
                        projection
     :read-ledger!      (fn [] [[author record] ...] | nil), the
                        authority's ledger records from the origin, or
                        nil when the arbitration medium cannot be
                        attached at all
     :clock             (fn [] reading), the lease clock
     :observe!          (fn [handle op arg] outcome), the handle observer
     :append-diagnostic!  (fn [diagnostic] ...), the composition's
                        diagnostic stream

   A missing or mis-shaped seam throws here, at assembly, as
   dao.lease's own halves do."
  [{:keys [me bytes address protection renewal-interval receiver]
    :as config}]
  (let [units (or (:units config) lease/default-units)]
    (check! (some? me) "an identity: the holder a grant would name" {:me me})
    (check! (some? bytes) "the checkpoint bytes" {})
    (check! (some? address) "the address the bytes were fetched under"
            {:address address})
    (check! (and (map? protection)
                 (every? classes (vals protection)))
            "a protection declaration over the three classes"
            {:protection protection})
    (check! (map? receiver) "a receiving machine" {})
    (check-interval! units renewal-interval)
    (doseq [s seams]
      (check! (fn? (get config s)) (str "the seam " (name s)) {}))
    {:phase :validating
     :status nil
     :detail nil
     :me me
     :bytes bytes
     :address address
     :protection protection
     :renewal-interval renewal-interval
     :units units
     :receiver receiver
     :seams (select-keys config seams)
     :occurrence nil
     :arbitration nil
     :kind nil
     :proposal nil
     :proposals 0
     :sent-proposals #{}
     :lease nil
     :holder nil
     :evidence nil
     :machine nil
     :renewals 0
     :renewal-pending? false
     :release nil
     :diagnostics []}))


(defn- append-request!
  [state request]
  ((get-in state [:seams :append-request!]) request))


(defn- read-reply!
  [state]
  ((get-in state [:seams :read-reply!])))


(defn- read-outcome!
  [state]
  ((get-in state [:seams :read-outcome!])))


(defn- read-ledger!
  [state]
  ((get-in state [:seams :read-ledger!])))


(defn- clock
  [state]
  ((get-in state [:seams :clock])))


(defn- observe!
  [state handle op arg]
  ((get-in state [:seams :observe!]) handle op arg))


(defn- append-diagnostic!
  [state diagnostic]
  ((get-in state [:seams :append-diagnostic!]) diagnostic))


;; =============================================================================
;; The task tree and the gate
;; =============================================================================

(defn- machines
  "`[path machine]` of the root and, recursively, each install child,
   children in install-name order."
  [root]
  (letfn [(walk
            [prefix m]
            (concat [[prefix m]]
                    (mapcat (fn [name]
                              (when-some [child (:vm (get (:installs m) name))]
                                (walk (into prefix [:installs name :vm])
                                      child)))
                            (sort-by str (keys (:installs m))))))]
    (walk [] root)))


(defn- ended
  "`machine` gated `:ended` at the root and at every install child: the
   root owns the mode, and nothing applies a late result after it."
  [machine]
  (reduce (fn [m [prefix _]]
            (assoc-in m (conj prefix :yin.k/gate) :ended))
          (assoc machine :yin.k/gate :ended)
          (machines machine)))


;; =============================================================================
;; The ledger read: whole history, fold, and the raw grant
;; =============================================================================

(defn- mine
  "The records `records` attributes to the arbitration identity."
  [arbitration records]
  (filter #(= arbitration (first %)) records))


(defn- transport-error?
  [record]
  (and (map? record) (contains? record :dao.stream/outcome)))


(defn- history-defect
  "Why `records` is not a whole history read from the origin -- a
   transport error, a start past the origin, a gap, or a fold defect --
   or nil when they are dense from zero.  The same rule D3's reader
   applies before any evidence is derived."
  [records]
  (loop [rs (seq records) n 0]
    (if-not rs
      nil
      (let [record (second (first rs))]
        (cond
          (transport-error? record) :transport-error
          :else
          (let [t (get-in record [:dao.space/transaction :t])]
            (cond
              (not (custody/exact? t)) :fold-defect
              (= t n) (recur (next rs) (inc n))
              (> t n) (if (zero? n) :start-past-origin :gap)
              :else :fold-defect)))))))


(defn- fold
  "The projection of the arbitration's `records`, or nil when they do
   not fold whole -- the same fold D3's reader runs."
  [arbitration records]
  (loop [p (ledger/empty-projection arbitration)
         rs (seq records)]
    (if-not rs
      p
      (let [p' (ledger/fold-record p (second (first rs)))]
        (if (::ledger/defect p')
          nil
          (recur p' (next rs)))))))


(defn- record-facts
  "The fact maps of one transaction record, entities in first-appearance
   order -- the raw datoms grouped, with no fold run over them."
  [record]
  (let [datoms (get-in record [:dao.space/transaction :datoms])]
    (when (vector? datoms)
      (mapv (fn [entity]
              (into {} (keep (fn [[other attribute value]]
                               (when (= entity other) [attribute value])) datoms)))
            (distinct (map first datoms))))))


(defn- grants-to
  "The accepted facts, in record order, granted to `me` on `occurrence`
   among the arbitration's records -- read raw, before any fold: the
   grant this driver observes is its own, and a history too corrupt to
   fold cannot hide it (the binding check that follows is what
   authenticates it)."
  [arbitration me occurrence records]
  (into []
        (comp (filter #(= arbitration (first %)))
              (mapcat (fn [[_ record]] (record-facts record)))
              (filter #(and (= :dao.lease/accepted (:dao.lease/status %))
                            (= me (:dao.lease/holder %))
                            (= (custody/subject occurrence)
                               (:dao.lease/subject %)))))
        records))


(defn- occurrence-state
  "The lease state observed for `occurrence` in `projection`: what a
   `:yin.k/not-holder` answer carries (7.9)."
  [projection occurrence]
  (let [entry (get-in projection [:occurrences occurrence])
        l (:dao.lease/lease entry)]
    (cond-> {:yin.k/occurrence occurrence
             :dao.lease/lease l
             :yin.k/epoch (:yin.k/epoch entry)
             :yin.k/admitted (:yin.k/variants entry)}
      (some? l) (assoc :dao.lease/holder
                       (get-in projection [:leases l :dao.lease/holder]))
      (:yin.k/quarantined entry) (assoc :yin.k/quarantined true)
      (:yin.k/closed entry) (assoc :yin.k/closed true)
      (:yin.k/exhausted entry) (assoc :yin.k/exhausted true))))


(defn- never-granted-again?
  "True when the occurrence can never be granted again: closed,
   exhausted or quarantined."
  [entry]
  (boolean (or (:yin.k/closed entry)
               (:yin.k/exhausted entry)
               (:yin.k/quarantined entry))))


;; =============================================================================
;; The answers the step state carries
;; =============================================================================

(defn- with-status
  [state status detail]
  (assoc state :status status :detail detail))


(defn- unsatisfied
  ([state reason] (unsatisfied state reason nil))
  ([state reason data]
   (with-status state :yin.k/unsatisfied
     (cond-> {:yin.k/reason reason}
       (map? data) (merge data)))))


(defn- failed
  "A terminal refusal no cleanup is owed for: before custody, none."
  [state refusal]
  (assoc state :phase :failed
         :status (:yin.k/status refusal)
         :detail (dissoc refusal :yin.k/status)))


(def ^:private invalid-binding
  "custody/binding-evidence's no-evidence reasons that make the binding
   of a grant to this driver invalid -- as against a history that is
   merely unavailable."
  #{:no-binding :duplicate-binding :inexact-epoch
    :not-in-grant-transaction :grant-mismatch})


;; =============================================================================
;; The release: cleanup, retried until it has left
;; =============================================================================

(defn- release-request
  [l]
  {:yin.k/request :yin.k/release
   :yin.k/request-id [:yin.k/release l]
   :dao.lease/lease l})


(defn- releasing
  "`state` entering :releasing over lease `l`, carrying the failure it
   will answer once the release has left: any machine is gated ended,
   the dao.lease holder has stopped, and the release request is sent at
   once -- a send that does not answer ok is retained and the identical
   request is retried.  This is cleanup, never recovery: it clears no
   quarantine, completes nothing without accepted completion evidence,
   and the occurrence of an ordinary failed run stays regrantable under
   the ordinary policy."
  [state status detail l]
  (let [request (release-request l)
        outcome (append-request! state request)]
    (assoc state
           :phase :releasing
           :status status
           :detail detail
           :machine (some-> (:machine state) ended)
           :holder (some-> (:holder state)
                           (as-> h (when (some? (:grant h))
                                     (:holder (lease/stop h)))))
           :release {:lease l
                     :request request
                     :sent (= :dao.stream/ok (:dao.stream/outcome outcome))})))


(defn- end-run
  "End the run (r3 1.6): all program IO stops, the machine is gated
   ended, one diagnostic is appended to the composition's diagnostic
   stream, and the lease is released while it may still be live.  A
   run end the state already carries -- the cycle's own, or a record's
   earlier in the same drain -- folds into that one diagnostic under
   :also-ended, never a second diagnostic."
  [state cause detail]
  (let [also (some-> (:run-end state) :cause)
        detail (if (some? also) (assoc detail :also-ended also) detail)
        diagnostic {:yin.k/diagnostic :yin.k/run-ended
                    :yin.k/occurrence (:occurrence state)
                    :dao.lease/lease (:lease state)
                    :yin.k/end (merge {:cause cause} detail)}]
    (append-diagnostic! state diagnostic)
    (releasing (-> state (dissoc :run-end) (update :diagnostics conj diagnostic))
               :yin.k/ended (merge {:cause cause} detail) (:lease state))))


;; =============================================================================
;; The candidate loop: propose, observe the grant, accept it whole
;; =============================================================================

(defn- proposal-request
  [occurrence pid]
  {:yin.k/request :yin.k/proposal
   :yin.k/request-id [:yin.k/proposal occurrence pid]
   :dao.lease/proposal pid
   :yin.k/occurrence occurrence})


(defn- fresh-pid
  [_state]
  (str (random-uuid)))


(defn- propose
  "Send or resend the current proposal, minting a fresh id when the last
   one was refused (7.7.8: a proposal id is answered at most once), and
   answer the state after it: while it is unanswered the step answers
   `:yin.k/awaiting-grant`."
  [state projection]
  (let [refused? (= :dao.lease/rejected
                    (get-in projection [:answered [(:me state)
                                                   (:id (:proposal state))]]))
        mint? (or (nil? (:proposal state)) refused?)
        proposal (if mint?
                   (let [pid (fresh-pid state)]
                     {:id pid :request (proposal-request (:occurrence state)
                                                         pid)
                      :sent false})
                   (:proposal state))
        sent? (true? (:sent proposal))
        outcome (when-not (:carried proposal)
                  (append-request! state (:request proposal)))
        proposal' (assoc proposal
                         :sent (or sent?
                                   (= :dao.stream/ok
                                      (:dao.stream/outcome outcome))))]
    (-> state
        (update :sent-proposals
                (fn [ids]
                  (if (= :dao.stream/ok (:dao.stream/outcome outcome))
                    (conj ids (:id proposal)) ids)))
        (assoc :proposal proposal'
               :proposals (if mint?
                            (inc (or (:proposals state) 0))
                            (or (:proposals state) 0)))
        (with-status :yin.k/awaiting-grant
          {:yin.k/occurrence (:occurrence state)
           :dao.lease/proposal (:id proposal')}))))


(defn- lower
  "D10's lower with the accepted grant's inputs, over the receiver:
  the checkpoint address, the composition's protection declaration, and
  the grant -- lease, holder, the evidence `E` and the current tenure
  as numbers, `now` the clock's reading and `bound` the grant's
  duration over the observation basis."
  [state l holder E reading]
  (let [units (:units state)
        grant (:grant holder)
        basis (:granted-at holder)
        duration (:dao.lease/duration grant)
        base (fn [d]
               (let [entry (first d)]
                 (* (val entry) (get units (key entry)))))]
    (handoff/resume-task
      (assoc (:receiver state) :attach-stream (get-in state [:seams :attach!]))
      (:bytes state)
      (get-in state [:seams :attach!])
      {:exclusive true
       :address (:address state)
       :protection (:protection state)
       :grant {:checkpoint (:address state)
               :dao.lease/lease l
               :dao.lease/holder (:me state)
               :tenure {:now (base reading)
                        :bound (+ (base basis) (base duration))
                        :live true}
               :evidence E}})))


(defn- accept
  "The grant `fact`, read raw and naming this driver on this occurrence:
   accept it whole -- the binding over the same records, the evidence
   fold, the admitted variant, current tenure -- and lower; or refuse
   it, releasing what it acquired.  A history that does not read whole
   is refused before any evidence is derived from it (complete history
   or nothing), without a release: custody was not accepted."
  [state records defect fact]
  (let [l (:dao.lease/lease fact)
        arb (:arbitration state)
        b (custody/binding-evidence arb records l)
        binding-reason (::custody/no-evidence b)
        E (when (nil? binding-reason)
            (evidence/read-evidence arb records l))
        projection (fold arb (vec (mine arb records)))
        o (:yin.k/occurrence b)
        entry (get-in projection [:occurrences o])
        stale? (or (not= l (:dao.lease/lease entry))
                   (not= (:yin.k/epoch b) (:yin.k/epoch entry)))
        holder (lease/initial-holder
                 {:self (:me state)
                  :subject (custody/subject (:occurrence state))
                  :proposal (:dao.lease/proposal fact)
                  :grantor arb
                  :resolver (fn [_ _] arb)
                  :units (:units state)
                  :renewal-interval (:renewal-interval state)})
        reading (clock state)
        holder' (lease/observe-grant holder arb fact reading)]
    (cond
      (some? defect)
      (unsatisfied state defect {:dao.lease/lease l})

      (and (some? binding-reason) (contains? invalid-binding binding-reason))
      (releasing state :yin.k/not-holder
                 {:yin.k/reason binding-reason :dao.lease/lease l} l)

      (some? binding-reason)
      (unsatisfied state binding-reason {:dao.lease/lease l})

      (not= :yin.k/ready (:yin.k/status E))
      (unsatisfied state (or (:yin.k/reason E) (:yin.k/status E))
                   {:dao.lease/lease l})

      (nil? projection)
      (unsatisfied state :fold-defect {:dao.lease/lease l})

      (not (contains? (:yin.k/variants entry) (:address state)))
      (releasing state :yin.k/not-holder
                 {:yin.k/reason :variant-not-admitted
                  :yin.k/address (:address state)
                  :yin.k/admitted (:yin.k/variants entry)} l)

      ;; the grant is stale and the occurrence unheld: a fresh
      ;; proposal, for a regrant may come -- unless the occurrence can
      ;; never be granted again, which is not-holder, as below: the
      ;; judge refuses every proposal on such an occurrence, so a
      ;; proposal minted here would be minted and refused each pass
      ;; forever
      (and stale? (nil? (:dao.lease/lease entry)))
      (if (never-granted-again? entry)
        (with-status state :yin.k/not-holder (occurrence-state projection o))
        (propose (if (= (:dao.lease/proposal fact) (get-in state [:proposal :id]))
                   (assoc state :proposal nil) state) projection))

      ;; stale while another holds the occurrence: not the holder
      stale?
      (with-status state :yin.k/not-holder (occurrence-state projection o))

      (nil? (:grant holder'))
      ;; dao.lease refused to hold the raw fact: it does not read as
      ;; this holder's grant at all
      (releasing state :yin.k/unsatisfied
                 {:yin.k/reason :grant-not-held :dao.lease/lease l} l)

      (not (lease/holding? holder' reading))
      ;; held but not runnable: an undersized grant is at its bound
      ;; from observation on
      (releasing state :yin.k/unsatisfied
                 {:yin.k/reason :undersized-grant :dao.lease/lease l} l)

      :else
      (let [r (lower state l holder' E reading)]
        (if (= :ok (:status r))
          (-> state
              (assoc :phase :running
                     :machine (:vm r)
                     :lease l
                     :holder holder'
                     :evidence E)
              (with-status :yin.k/ok
                {:yin.k/occurrence o :dao.lease/lease l}))
          (releasing state (:yin.k/status r)
                     (dissoc r :yin.k/status) l))))))


(defn- control-reply
  "One attributed reply of the front to this driver's own requests: a
   proposal, renewal or release carriage.  It counts only when
   `front/reply-evidence` authenticates it and its request id is one
   this driver sent; anything else changes nothing."
  [state author record]
  (let [arb (:arbitration state)
        e (front/reply-evidence arb author record)]
    (if (contains? e ::front/no-evidence)
      state
      (let [rid (get record :yin.k/request-id)]
        (cond
          (and (map? (:proposal state))
               (= :yin.k/proposal (get record :yin.k/reply))
               (= (get-in (:proposal state)
                          [:request :yin.k/request-id])
                  rid))
          (assoc-in state [:proposal :carried]
                    (= :carried (get-in record [:yin.k/answer :yin.k/status])))

          (and (map? (:release state))
               (= :yin.k/release (get record :yin.k/reply))
               (= (get-in (:release state)
                          [:request :yin.k/request-id])
                  rid))
          (assoc-in state [:release :carried]
                    (= :carried (get-in record [:yin.k/answer :yin.k/status])))

          (and (contains? #{:running :safepoint} (:phase state))
               (map? (:renewal state))
               (= :yin.k/renewal (get record :yin.k/reply))
               (= (get-in state [:renewal :request :yin.k/request-id]) rid)
               (= :carried (get-in record [:yin.k/answer :yin.k/status])))
          (if (lease/holding? (:holder state) (clock state))
            (-> state
                (update :holder lease/observe-renewal
                        (get-in state [:renewal :reading])
                        {:dao.stream/outcome :dao.stream/ok})
                (assoc :renewal nil :renewal-pending? false)
                (update :renewals inc))
            (end-run state :lease-bound {:dao.lease/lease (:lease state)}))

          :else state)))))


(defn- drain-control
  "Every reply the front has answered that is this driver's own, read
   dry.  During the candidate loop no program record can exist; during
   a run `drain` reads these beside the program's."
  [state]
  (loop [state state]
    (if-some [pair (read-reply! state)]
      (recur (control-reply state (first pair) (second pair)))
      state)))


(defn- step-proposing
  [state]
  (let [records (read-ledger! state)]
    (if (nil? records)
      (unsatisfied state :no-arbitration
                   {:dao.stream/identity (:arbitration state)})
      (let [arb (:arbitration state)
            mine-records (vec (mine arb records))
            defect (history-defect mine-records)
            grants (filterv #(contains? (:sent-proposals state) (:dao.lease/proposal %))
                            (grants-to arb (:me state) (:occurrence state) records))]
        (if (seq grants)
          (accept (drain-control state) records defect (peek grants))
          (if (some? defect)
            (unsatisfied state defect {})
            (let [projection (fold arb mine-records)]
              (if (nil? projection)
                (unsatisfied state :fold-defect {})
                (let [entry (get-in projection
                                    [:occurrences (:occurrence state)])]
                  (cond
                    ;; held by another lease: not the holder; nothing
                    ;; was acquired and nothing is released
                    (some? (:dao.lease/lease entry))
                    (with-status (drain-control state)
                      :yin.k/not-holder
                      (occurrence-state
                        projection (:occurrence state)))

                    (never-granted-again? entry)
                    (with-status (drain-control state)
                      :yin.k/not-holder
                      (occurrence-state
                        projection (:occurrence state)))

                    :else (propose (drain-control state)
                                   projection)))))))))))


;; =============================================================================
;; The run: tenure first, then renewal, machine, writer, reader, drains
;; =============================================================================

(defn- control-outstanding?
  "The one divergence fact the machine cannot see (r3 1.4): a control
   request this driver still owes -- a renewal that has not left, or a
   release."
  [state]
  (boolean (or (:renewal-pending? state)
               (and (map? (:release state))
                    (not (true? (:sent (:release state))))))))


(defn- renewal-request
  [l n]
  {:yin.k/request :yin.k/renewal
   :yin.k/request-id [:yin.k/renewal l n]
   :dao.lease/lease l})


(defn- renew
  "The renewal discipline (H2, H3) at `reading`: send at the interval,
  retaining the pre-send reading until authenticated carriage while
  still holding. Unacknowledged attempts retry unchanged; inbound
  acceptance alone advances nothing."
  [state holder reading]
  (if-not (or (:renewal state) (lease/due-to-renew? holder reading))
    [(assoc state :renewal-pending? false) holder]
    (let [pending (or (:renewal state)
                      {:request (renewal-request (:lease state) (:renewals state))
                       :reading reading})]
      (append-request! state (:request pending))
      [(assoc state :renewal pending :renewal-pending? true) holder])))


(defn- drain
  "Fold every attributed record the composition's two readers answer --
  the front's replies and the outcome projection -- into the machine,
  routing each to the writer's `discharge`, the reader's `settle` or
  this driver's own control arm.  A record that matches nothing changes
  nothing, and draining continues past a run end, for the readers are
  read dry either way."
  [state]
  (loop [state state]
    (if-some [pair (or (when-some [p (read-reply! state)] [:reply p])
                       (when-some [p (read-outcome! state)] [:outcome p]))]
      (let [[author record] (second pair)
            m (:machine state)
            kind (get record :yin.k/reply)
            r (cond
                ;; the writer's: an admit reply, or a projected
                ;; admission outcome with no request wrapper
                (or (= :yin.k/admit kind)
                    (and (nil? kind) (contains? record :yin.k/admission)))
                (writer/discharge m author record)

                ;; the reader's: an input acknowledgment
                (= :yin.k/input kind)
                (reader/settle m author record)

                ;; this driver's own
                :else {:machine m})]
        (recur (control-reply
                 (assoc state
                        :machine (:machine r)
                        :run-end (or (:run-end state) (:run-end r)))
                 author record)))
      state)))


(defn- require-tenure!
  [state]
  (when-not (lease/holding? (:holder state) (clock state))
    (throw (ex-info "Program IO past lease bound" {::cause :lease-bound}))))


(defn- guarded-handle
  [state handle]
  (reify
    stream/IDaoStreamDescriptor
    (descriptor [_] (stream/descriptor handle))


    stream/IDaoStreamWriter

    (append!
      [_ value]
      (require-tenure! state)
      (stream/append! handle value))


    stream/IDaoStreamClosable

    (close!
      [_]
      (require-tenure! state)
      (stream/close! handle))))


(defn- guard-writes
  [state machine]
  (reduce (fn [root [path task]]
            (assoc-in root (conj path :resources)
                      (into {} (map (fn [[resource handle]]
                                      [resource
                                       (if (satisfies? stream/IDaoStreamDescriptor handle)
                                         (guarded-handle state handle) handle)]))
                            (:resources task))))
          machine (machines machine)))


(defn- restore-handles
  [machine original]
  (reduce (fn [root [path task]]
            (reduce (fn [root [resource handle]]
                      (if (satisfies? stream/IDaoStreamDescriptor handle)
                        (assoc-in root (into path [:resources resource]) handle)
                        root)) root (:resources task)))
          machine (machines original)))


(defn- run-cycle
  "One program cycle of the run, after tenure: the machine's internal
   computation, replay below the frontier, the writer's emit, the
   reader's live step.  The unsatisfied answers of the writer and the
   reader ride the state as :step-unsatisfied; a run end anywhere
   rides it as :run-end."
  [state]
  (let [_ (require-tenure! state)
        m0 (:machine state)
        m1 (if (and (= :running (vm/gate-mode m0)) (not (:halted? m0)))
             (vm/run m0)
             m0)
        replay (reader/replay m1 {:control-outstanding?
                                  (control-outstanding? state)})
        m2 (:machine replay)
        _ (require-tenure! state)
        request! (fn [request] (require-tenure! state) (append-request! state request))
        emit (writer/emit (guard-writes state m2) request!
                          (fn [diagnostic]
                            (require-tenure! state)
                            (append-diagnostic! state diagnostic)))
        m3 (restore-handles (:machine emit) m2)
        live (reader/step m3 request!
                          (fn [h op arg]
                            (require-tenure! state)
                            (observe! state h op arg)))
        m4 (:machine live)]
    (assoc state
           :machine m4
           :run-end (or (:run-end replay) (:run-end emit) (:run-end live))
           :step-unsatisfied (or (:unsatisfied replay)
                                 (:unsatisfied emit)
                                 (:unsatisfied live)))))


(defn- at-safepoint?
  "True when the machine has nothing left the candidate half owes it:
   halted with no wait entry and no queued work anywhere in the tree."
  [machine]
  (and (:halted? machine)
       (every? (fn [[_ m]]
                 (and (empty? (:wait-set m)) (empty? (:ready-queue m))))
               (machines machine))))


(defn- step-active-held
  "The step of :running and :safepoint alike: tenure is rechecked
   before any execution or IO is scheduled (the D10 ruling); not
   holding ends the run and releases; then the renewal, the program
   cycle and the drains.  A halted result published without custody
   steps to itself."
  [state]
  (if (nil? (:lease state))
    state
    (if-not (lease/holding? (:holder state) (clock state))
      (end-run state :lease-bound {:dao.lease/lease (:lease state)})
      (let [state (drain state)
            records (read-ledger! state)]
        (if (= :releasing (:phase state))
          ;; the pre-cycle drain ended the run: its answer stands, and
          ;; the state carries no run end, as the post-cycle path's
          (dissoc state :run-end)
          (if-some [end (:run-end state)]
            (end-run (dissoc state :run-end) (:cause end) (dissoc end :cause))
            (if (nil? records)
              (unsatisfied state :no-arbitration
                           {:dao.stream/identity (:arbitration state)})
              (let [arb (:arbitration state)
                    mine-records (vec (mine arb records))
                    defect (history-defect mine-records)
                    projection (when (nil? defect) (fold arb mine-records))
                    o (:occurrence state)
                    l (:lease state)
                    entry (get-in projection [:occurrences o])]
                (cond
                  ;; unknown tenure: no execution and no IO scheduled
                  (or (some? defect) (nil? projection) (nil? entry))
                  (unsatisfied state (or defect :fold-defect) {})

                  ;; the lease is no longer the occurrence's live lease
                  (or (not= l (:dao.lease/lease entry))
                      (not= (get-in state [:evidence :yin.k/binding :yin.k/epoch])
                            (:yin.k/epoch entry)))
                  (end-run state :stale
                           {:dao.lease/lease l
                            :dao.lease/observed-lease (:dao.lease/lease entry)})

                  :else
                  (let [holder (:holder state)
                        reading (clock state)]
                    (if-not (lease/holding? holder reading)
                      ;; at the bound: all IO stops, and the lease may still
                      ;; be live, so it is released
                      (end-run state :lease-bound {:dao.lease/lease l})
                      (let [[state' holder'] (renew state holder reading)
                            state'' (drain (run-cycle (assoc state' :holder holder')))
                            end (:run-end state'')
                            unsat (:step-unsatisfied state'')
                            base (dissoc state'' :run-end :step-unsatisfied)]
                        (cond
                          ;; the post-cycle drain can itself have ended
                          ;; the run -- a renewal carriage accepted only
                          ;; after the clock crossed the bound: its
                          ;; answer stands, never re-labelled below
                          (= :releasing (:phase base))
                          base

                          (some? end)
                          (end-run base (:cause end) (dissoc end :cause))

                          (some? unsat)
                          (with-status base :yin.k/unsatisfied
                            (dissoc unsat :yin.k/status))

                          (at-safepoint? (:machine base))
                          (assoc base :phase :safepoint
                                 :status :yin.k/ok
                                 :detail {:yin.k/occurrence o :dao.lease/lease l})

                          :else (with-status base :yin.k/ok
                                  {:yin.k/occurrence o
                                   :dao.lease/lease l}))))))))))))))


(defn- step-active
  "The held step under the catch: every seam the cycle runs through
   answers an outcome and never throws (the seam contract of
   `initial`), so a throw here is the cycle's own -- a tenure guard or
   a program effect -- and it ends the run."
  [state]
  (try (step-active-held state)
       (catch #?(:cljd Object :clj Throwable :cljs :default) failure
         (if (= :lease-bound (::cause (ex-data failure)))
           (end-run state :lease-bound {:dao.lease/lease (:lease state)})
           (end-run state :program-failure
                    {:failure {:message (ex-message failure)
                               :data (ex-data failure)}})))))


;; =============================================================================
;; The release step
;; =============================================================================

(defn- step-releasing
  [state]
  (let [state (drain-control state)
        release (:release state)]
    (if (true? (:carried release))
      (-> state
          (assoc :phase :failed)
          (update :detail assoc :dao.lease/released true))
      (let [o (append-request! state (:request release))]
        (if (= :dao.stream/ok (:dao.stream/outcome o))
          (assoc-in state [:release :sent] true)
          ;; retained: the identical request is retried
          state)))))


;; =============================================================================
;; Validate: the D7 pipeline with zero side effects
;; =============================================================================

(defn- step-validating
  [state]
  (let [r (handoff/resume-task
            (assoc (:receiver state)
                   :attach-stream (get-in state [:seams :attach!]))
            (:bytes state)
            (get-in state [:seams :attach!])
            {:exclusive true :address (:address state)})]
    (cond
      ;; a valid blocked or parked root answers awaiting-grant at
      ;; the custody gate, before any attachment
      (= :yin.k/awaiting-grant (:yin.k/status r))
      (let [b (checkpoint/inspect (:address state) (:bytes state))]
        (assoc state :phase :proposing
               :occurrence (:yin.k/occurrence b)
               :arbitration (get-in b
                                    [:yin.k/arbitration
                                     :dao.stream/identity])
               :kind (:yin.k/kind b)))

      ;; a halted version-1 root is no lease subject: it lowers
      ;; once, gated ended, and no custody is manufactured
      (and (= :ok (:status r)) (= :halted (:kind r)))
      (assoc state :phase :safepoint
             :status :yin.k/ok
             :detail {:yin.k/kind :halted}
             :machine (:vm r))

      :else (failed state r))))


;; =============================================================================
;; The step
;; =============================================================================

(defn step
  "Advance `state` by one explicit step and answer the next state.  The
   composition reads `:phase` (:validating, :proposing, :running,
   :safepoint, :releasing or :failed), `:status` (:yin.k/ok,
   :yin.k/awaiting-grant, :yin.k/not-holder, :yin.k/unsatisfied or
   :yin.k/ended) and `:detail`; the lowered task, once a grant is
   accepted whole, is `:machine`.  A terminal state steps to itself, a
   safepoint still renews and rechecks tenure, and a pending release is
   retried."
  [state]
  (case (:phase state)
    :validating (step-validating state)
    :proposing (step-proposing state)
    (:running :safepoint) (step-active state)
    :releasing (step-releasing state)
    state))
