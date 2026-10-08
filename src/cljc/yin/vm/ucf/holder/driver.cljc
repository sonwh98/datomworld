(ns yin.vm.ucf.holder.driver
  "The custody driver of M-next D slices D13 and D14 (UCF 7.7.2 to
   7.7.8, 7.8, 7.9; r3 1.5, 1.6, 1.7, 1.8, 1.9 and 1.10; the D9 header
   and D10 lower-inputs rulings; linker-dht 14.2.2): one explicit
   composition-local step state, stepped by the composition, that
   carries a candidate from bytes to a task under custody, drives that
   task's run, and -- D14 -- carries the source half (a first export
   and its offer) and the exit half (the successor, the report, the
   release and the closure) over a write-ahead progress journal.

   The driver reaches the authority through exactly the three remote
   paths of r3 1.5, all composition-supplied functions: the front's
   request stream (`append-request!`), the version-1 positional
   `reply-inbox` and `outcome-inbox`, and the authenticated ledger
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

   :activating -- an authenticated grant is retained with its original
   observation basis, without a machine. Control ticks may renew it;
   only a program tick revalidates and lowers it.

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
   entry, no queued work); the exit half (D14) owns what follows, under
   tenure that is still held: renewal and the recheck continue while it
   runs, and all IO stops at the bound.

   :releasing -- a pending release is retried with the identical
   request until authenticated carriage is acknowledged, then its failure is
   the driver's answer.  :failed is terminal.

   The D14 phases and entries beside these:

   :exporting -- the source half over a machine the composition hands
   the driver (`source`, below): enter exporting, mint the occurrence
   once and journal it before prepare (the D9 ruling), prepare, encode,
   put the prepared record and the body bytes in the content store,
   journal the fenced record, then the bracketed offer -- an intent
   record durable before each send, an attempt record after it, an
   acknowledgment once the front's authenticated reply admits the offer.
   A nil :arbitration in the config is the composition's fork decision:
   the lift is version 0, nothing is minted, offered or journaled, and
   the driver answers the bytes (:lifted).

   :exiting -- the exit half of a restarted holder, journaled but
   machine-less: it reconciles committed reports, carries release and
   watches for closure. An uncommitted report returns to candidacy,
   without borrowing old tenure. A live driver runs the exit body from
   :safepoint under its own tenure.

   :exited -- the exit completed: the report committed, the release
   carried, the closure observed and, for a continuation, the
   successor's offer admitted.  :lifted and :aborted are the fork's and
   a legal abort's terminals.  :stalled is an uncertain journal append:
   everything stops -- program steps and sends alike -- until the
   composition reopens the journal and reconciles through `reopen`.

   The source is a candidate like any other: the driver holds bytes,
   never the source's local machine, so the source itself resumes only
   through a grant to itself, never on data.  No transport is known
   here; the machine's own resource handles are the only ones touched,
   by the writer and the reader."
  (:require [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.lease :as lease]
            [dao.stream :as stream]
            [yin.vm :as vm]
            [yin.vm.ucf.authority.completion :as completion]
            [yin.vm.ucf.authority.front :as front]
            [yin.vm.ucf.checkpoint :as checkpoint]
            [yin.vm.ucf.custody :as custody]
            [yin.vm.ucf.handoff :as handoff]
            [yin.vm.ucf.holder.evidence :as evidence]
            [yin.vm.ucf.holder.export :as export]
            [yin.vm.ucf.holder.inbox :as inbox]
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
  [:append-request! :read-ledger!
   :clock :observe! :append-diagnostic! :attach! :serve!])


(def ^:private optional-seams
  "Composition-supplied functions the driver uses only when asked."
  [:enroll!])


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


(defn- check-common!
  "The checks every driver constructor runs: the identity, the
   protection declaration, the receiving machine, the renewal interval,
   the fn seams, the progress journal, the content store and the carrier
   medium -- the D14 seams beside the D13 ones.  A candidate that never
   exits still carries them: a grant may make it a holder whose run
   ends in an exit."
  [config units]
  (check! (some? (:me config)) "an identity: the holder a grant would name"
          {:me (:me config)})
  (check! (and (map? (:protection config))
               (every? classes (vals (:protection config))))
          "a protection declaration over the three classes"
          {:protection (:protection config)})
  (check! (map? (:receiver config)) "a receiving machine" {})
  (check-interval! units (:renewal-interval config))
  (doseq [s seams]
    (check! (fn? (get config s)) (str "the seam " (name s)) {}))
  (doseq [key (vals inbox/lanes)]
    (inbox/check-descriptor! (get config key)))
  (doseq [s optional-seams
          :when (some? (get config s))]
    (check! (fn? (get config s)) (str "the seam " (name s)) {}))
  (check! (some? (get config :journal))
          "a progress journal: a dao.stream.journal handle" {})
  (check! (and (map? (:store config))
               (fn? (:put-bytes-fn (:store config)))
               (fn? (:get-bytes-fn (:store config))))
          "a content store: a dao.jing byte-store handle"
          {})
  (check! (some? (:medium config)) "a carrier medium name" {}))


(defn- bare-state
  "The step state every constructor fills from `config`."
  [config units]
  {:phase :validating
   :status nil
   :detail nil
   :me (:me config)
   :bytes (:bytes config)
   :address (:address config)
   :protection (:protection config)
   :renewal-interval (:renewal-interval config)
   :units units
   :receiver (:receiver config)
   :export-version (:export-version config)
   :seams (-> (select-keys config (into (into seams optional-seams) (vals inbox/lanes)))
              (assoc :journal (:journal config)
                     :content-store (:store config)
                     :medium (:medium config)))
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
   :diagnostics []
   :export nil
   :exit nil
   :stopped? false
   :inbox {:positions {:reply 0 :outcome 0} :program [] :control []}})


(declare journal-records fold-journal restore-inbox)


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
     :reply-inbox       {:version 1 :identity stable-id :read-at! f},
                        the completely retained front reply source
     :outcome-inbox     the same descriptor for admission outcomes;
                        f takes a dense portable position, without
                        consuming, and returns {:status :record
                        :position n :author a :record r}, {:status :empty}
                        at the tail, or {:status :unavailable} for
                        inaccessible history. No destructive fallback.
     :read-ledger!      (fn [] [[author record] ...] | nil), the
                        authority's ledger records from the origin, or
                        nil when the arbitration medium cannot be
                        attached at all
     :clock             (fn [] reading), the lease clock
     :observe!          (fn [handle op arg] outcome), the handle observer
     :append-diagnostic!  (fn [diagnostic] ...), the composition's
                        diagnostic stream
     :serve!            (fn [handle] descriptor), the exporter's server,
                        for the exit a grant may reach
     :journal           the composition's progress journal (a
                        dao.stream.journal handle): every durable act of
                        the driver -- each bracketed external action, the
                        mint, the fence, the accepted grant -- is
                        write-ahead in it
     :store             the composition's content store: the prepared
                        record and the body bytes are put in it before
                        the fenced record references them
     :medium            the carrier medium name the offer carries
     :enroll!           optional (fn [] enrollment-answer), the
                        composition's enrollment seam

   A missing or mis-shaped seam throws here, at assembly, as
   dao.lease's own halves do."
  [{:keys [bytes address] :as config}]
  (let [units (or (:units config) lease/default-units)]
    (check! (some? bytes) "the checkpoint bytes" {})
    (check! (some? address) "the address the bytes were fetched under"
            {:address address})
    (check-common! config units)
    (restore-inbox (bare-state config units)
                   (:inbox (fold-journal (journal-records (:journal config)))))))


(defn source
  "The source's composition-local step state over `config`: everything
   `initial` takes except :bytes and :address -- the driver mints those
   itself -- plus

     :machine           the machine at its liftable safepoint (parked,
                        blocked or halted), gate :running; never one
                        that already holds custody (a holder exports its
                        successor through `hand-off`)
     :arbitration       the arbitration medium the composition names, a
                        version-1 exclusive export; nil is the
                        composition's fork decision: the lift is
                        version 0, nothing is minted, offered or
                        journaled, and the driver answers the bytes

   The driver enters exporting, mints the occurrence once, journals it
   before prepare, prepares, encodes, fences and offers; once the offer
   is admitted it becomes a candidate like any other, over its own
   bytes.  The header is the driver's, never the composition's: a first
   export carries the composition's arbitration, next-op-seq 0 and no
   origin; the enrolled set is the ledger fold's."
  [{:keys [machine] :as config}]
  (let [units (or (:units config) lease/default-units)]
    (check! (map? machine) "the machine to export" {})
    (check! (not (contains? machine :yin.k/custody))
            "a machine that holds no custody: a holder exports through hand-off"
            {})
    (check! (or (nil? (:arbitration config))
                (and (map? (:arbitration config))
                     (some? (get-in config
                                    [:arbitration :dao.stream/identity]))))
            "an arbitration medium: a map naming the identity, or nil for the fork"
            {:arbitration (:arbitration config)})
    (check-common! config units)
    (restore-inbox (assoc (bare-state config units)
                          :phase :exporting
                          :bytes nil
                          :address nil
                          :arbitration (get-in config [:arbitration :dao.stream/identity])
                          :export {:role (if (some? (:arbitration config))
                                           :yin.k/first nil)
                                   :machine machine
                                   :arbitration (:arbitration config)})
                   (:inbox (fold-journal (journal-records (:journal config)))))))


(defn- append-request!
  [state request]
  ((get-in state [:seams :append-request!]) request))


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


(defn- serve!
  [state handle]
  ((get-in state [:seams :serve!]) handle))


(defn- enroll!
  [state]
  ((get-in state [:seams :enroll!])))


(defn- journal-of
  [state]
  (get-in state [:seams :journal]))


;; =============================================================================
;; The progress journal (r3 1.10): write-ahead, plain-data records
;; =============================================================================

(defn journal!
  "Append one `record` to the composition's progress journal.  A
   transport-error is the journal's own uncertain answer -- a failure
   between write and visibility poisons its appends -- and unwinds as
   ::journal-uncertain, which `step` answers by stalling everything
   until the composition reopens the journal and reconciles.  Any other
   non-ok answer is a defect of the record's shape and throws."
  [state record]
  (let [o (stream/append! (journal-of state) record)]
    (when-not (= :dao.stream/ok (:dao.stream/outcome o))
      (if (= :dao.stream/transport-error (:dao.stream/outcome o))
        (throw (ex-info "The progress journal append is uncertain"
                        {::journal-uncertain true}))
        (throw (ex-info "The progress journal refused a record"
                        {::journal-defect (:dao.stream/outcome o)
                         :record record}))))))


(defn- stalled
  [state]
  (assoc state :phase :stalled
         :status :yin.k/unsatisfied
         :detail {:yin.k/reason :journal-uncertain}))


(defn- progress-record?
  [record]
  (let [action (:yin.k/action record)
        actions #{:yin.k/offer :yin.k/proposal :yin.k/resumed :yin.k/release
                  :yin.k/enroll :yin.k/serve :yin.k/store :yin.k/renewal}]
    (and (map? record)
         (case (:yin.k/journal record)
           :yin.k/minted (and (some? (:yin.k/occurrence record))
                              (map? (:yin.k/header record))
                              (contains? #{:yin.k/first :yin.k/successor :yin.k/result}
                                         (:yin.k/role record)))
           :yin.k/fenced (and (some? (:yin.k/occurrence record))
                              (keyword? (:yin.k/address record)) (keyword? (:yin.k/record record)))
           :yin.k/aborted (some? (:yin.k/occurrence record))
           :yin.k/inbox (inbox/retention? record)
           :yin.k/intent (and (contains? actions action)
                              (if (= :yin.k/enroll action)
                                (some? (:yin.k/derived record))
                                (and (map? (:yin.k/request record))
                                     (some? (get-in record [:yin.k/request :yin.k/request-id])))))
           :yin.k/attempt (and (contains? actions action) (contains? record :yin.k/append))
           :yin.k/ack (case action
                        :yin.k/grant (and (some? (:yin.k/occurrence record))
                                          (some? (:dao.lease/lease record)))
                        :yin.k/enroll (some? (:yin.k/target record))
                        (and (contains? actions action) (some? (:yin.k/request-id record))
                             (map? (:yin.k/answer record))))
           false))))


(defn journal-records
  "Every record of journal handle `j`, from the origin: the whole
   durable history a reopen folds."
  [j]
  (let [origin (stream/cursor j :dao.stream/oldest)]
    (check! (= :dao.stream/ok (:dao.stream/outcome origin)) "journal origin" origin)
    (loop [cursor (:dao.stream/cursor origin) records []]
      (let [answer (stream/next j cursor)]
        (case (:dao.stream/outcome answer)
          :dao.stream/ok
          (do
            (check! (and (contains? answer :dao.stream/cursor)
                         (not= cursor (:dao.stream/cursor answer))
                         (or (not (map? cursor))
                             (and (= (:dao.stream.memory-log/identity cursor)
                                     (get-in answer [:dao.stream/cursor :dao.stream.memory-log/identity]))
                                  (integer? (:dao.stream.memory-log/position cursor))
                                  (= (inc (:dao.stream.memory-log/position cursor))
                                     (get-in answer [:dao.stream/cursor :dao.stream.memory-log/position]))))
                         (progress-record? (:dao.stream/value answer)))
                    "journal frame" answer)
            (recur (:dao.stream/cursor answer) (conj records (:dao.stream/value answer))))
          :dao.stream/blocked records
          (throw (ex-info "Journal history unavailable" {:yin.k/reason :journal-unavailable
                                                         :answer answer})))))))


(defn fold-journal
  "The driver's projection of its progress journal: what was minted and
   fenced, which external actions were intended, attempted and
   acknowledged, and which grant was accepted.  The records are the
   driver's own grammar, plain data under `:yin.k/journal`:

     {:yin.k/journal :yin.k/minted :yin.k/role r :yin.k/occurrence O
      :yin.k/header h}
     {:yin.k/journal :yin.k/fenced :yin.k/occurrence O
      :yin.k/address A :yin.k/record RA}
     {:yin.k/journal :yin.k/intent :yin.k/action a :yin.k/occurrence O
      :yin.k/request req :yin.k/bytes-at A}   ; offer, proposal, resumed,
                                              ; release, enroll (no
                                              ; request identity)
     {:yin.k/journal :yin.k/attempt :yin.k/action :yin.k/offer
      :yin.k/occurrence O :yin.k/append outcome}
     {:yin.k/journal :yin.k/ack :yin.k/action a :yin.k/occurrence O
      :yin.k/request-id rid :yin.k/answer ans}  ; or :yin.k/grant, which
                                                ; carries the lease

   The fold answers

     {:mint        the last minted occurrence's data, or nil
      :fences      occurrence to {:address A :record RA}
      :intents     request-id to the last intent record of that id
      :acked       the request-ids whose acknowledgment stands
      :answers     request-id to the authenticated answer
      :attempts    occurrence to the offer attempts, {:append o} each
      :offered     the occurrences whose offer intent stands
      :grant       {:yin.k/occurrence O :dao.lease/lease l}, or nil}"
  [records]
  (reduce
    (fn [f [index record]]
      (case (get record :yin.k/journal)
        :yin.k/inbox
        (update f :inbox inbox/retain record)
        :yin.k/minted
        (assoc f :mint {:role (get record :yin.k/role)
                        :index index
                        :occurrence (get record :yin.k/occurrence)
                        :header (get record :yin.k/header)})
        :yin.k/fenced
        (assoc-in f [:fences (get record :yin.k/occurrence)]
                  {:address (get record :yin.k/address)
                   :record (get record :yin.k/record)})
        :yin.k/aborted
        (assoc-in f [:aborted (:yin.k/occurrence record)] {:index index})
        :yin.k/intent
        (let [rid (get-in record [:yin.k/request :yin.k/request-id])]
          (-> f
              (update :intents assoc rid record)
              (update :ordered-intents conj record)
              (cond-> (= :yin.k/enroll (get record :yin.k/action))
                (assoc :enroll {:derived (:yin.k/derived record)}))
              (cond-> (= :yin.k/offer (get record :yin.k/action))
                (update :offered conj (get record :yin.k/occurrence))
                (= :yin.k/offer (get record :yin.k/action))
                (update-in [:attempts (get record :yin.k/occurrence)] (fnil conj []) {}))))
        :yin.k/ack
        (cond
          (= :yin.k/enroll (get record :yin.k/action))
          (assoc f :enroll {:target (:yin.k/target record)})

          (= :yin.k/grant (get record :yin.k/action))
          (assoc f :grant {:occurrence (get record :yin.k/occurrence)
                           :index index
                           :lease (get record :dao.lease/lease)})
          :else
          (let [rid (get record :yin.k/request-id)]
            (-> f
                (update :acked (fnil conj #{}) rid)
                (update :answers assoc rid record))))
        :yin.k/attempt
        (if (= :yin.k/offer (:yin.k/action record))
          (update-in f [:attempts (get record :yin.k/occurrence)]
                     (fn [attempts]
                       (check! (seq attempts) "attempt with durable intent" record)
                       (assoc attempts (dec (count attempts))
                              {:append (get record :yin.k/append)})))
          (update f :action-attempts (fnil conj []) record))
        f))
    {:mint nil :fences {} :intents {} :ordered-intents [] :acked #{} :answers {}
     :attempts {} :offered #{} :grant nil :inbox []}
    (map-indexed vector records)))


(defn- offered-attempts
  "The journaled offer attempts of `occurrence` (r3 1.9): one per
   attempt whose intent was durable before the send, `:append` the
   outcome its append answered -- absent when none did.  An intent with
   no attempt record is one send of unknown outcome."
  [folded occurrence]
  (let [sent (get-in folded [:attempts occurrence])]
    (if (and (contains? (:offered folded) occurrence)
             (empty? sent))
      [{}]
      (or sent []))))


(defn- intent-of
  "The last intent record of `action` for `occurrence`, or nil."
  [folded action occurrence]
  (some (fn [record]
          (when (and (= action (get record :yin.k/action))
                     (or (nil? occurrence)
                         (= occurrence (get record :yin.k/occurrence))))
            record))
        (reverse (:ordered-intents folded))))


;; =============================================================================
;; The content store: the prepared record and the body bytes (r3 1.10)
;; =============================================================================

(defn- store-put!
  "Put `bytes` at `address` in the composition's content store: true
   once it holds them, false when the store refused or threw."
  [state address bytes]
  (try
    (contains? #{:inserted :present}
               ((:put-bytes-fn (get-in state [:seams :content-store])) address bytes))
    (catch #?(:cljd Object :clj Throwable :cljs :default) _
      false)))


(defn- store-get
  "The bytes the composition's content store holds at `address`, or nil."
  [state address]
  (try
    (let [v ((:get-bytes-fn (get-in state [:seams :content-store]))
             address ::absent)]
      (when-not (= ::absent v) v))
    (catch #?(:cljd Object :clj Throwable :cljs :default) _
      nil)))


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
;; The export half (r3 1.8 and 1.9; the D9 ruling): mint, prepare,
;; fence, offer
;; =============================================================================

;; The candidate loop's reply drain and the release request live below,
;; with the halves that own them; the export and exit halves step
;; through both.
(declare drain-control release-request require-tenure! releasing end-run)


(defn- folded-projection
  "The arbitration's own fold of the ledger read, or nil when the
   history is not whole (r3 1.5: complete history or nothing)."
  [state]
  (let [records (read-ledger! state)]
    (when (some? records)
      (let [mine-records (vec (mine (:arbitration state) records))
            defect (history-defect mine-records)]
        (when (nil? defect)
          (fold (:arbitration state) mine-records))))))


(defn- enrolled-of
  "The enrolled target identities of the arbitration's own fold, or nil
   with the reason the fold could not run.  Unavailable is never an
   empty enrolled set."
  [state]
  (let [records (read-ledger! state)]
    (if (nil? records)
      [nil :no-arbitration]
      (let [mine-records (vec (mine (:arbitration state) records))
            defect (history-defect mine-records)]
        (cond
          (some? defect) [nil defect]
          :else
          (let [p (fold (:arbitration state) mine-records)]
            (if (nil? p)
              [nil :fold-defect]
              [(set (keys (:targets p))) nil])))))))


(defn- export-prepare-step
  "Prepare the export of `k`'s cell: serve each stream once, retaining
   what was served, so a refusal's retry never serves a stream twice.
   The minted occurrence is already durable in the journal."
  [state k]
  (when (= :exit k) (require-tenure! state))
  (let [cell (get state k)
        r (export/prepare
            (:m cell) (:record cell)
            (fn [handle]
              (let [resource (first (for [[path machine] (machines (:m cell))
                                          resource-id (sort-by str (keys (:resources machine)))
                                          :when (= handle (get (:resources machine) resource-id))]
                                      [path resource-id]))
                    request-id [:yin.k/serve (:occurrence cell) resource]
                    journaled? (some? (:occurrence cell))]
                (when journaled?
                  (journal! state {:yin.k/journal :yin.k/intent :yin.k/action :yin.k/serve
                                   :yin.k/occurrence (:occurrence cell)
                                   :yin.k/request {:yin.k/request-id request-id
                                                   :yin.k/resource resource}}))
                (when (= :exit k) (require-tenure! state))
                (let [descriptor (serve! state handle)]
                  (when (and journaled? descriptor)
                    (journal! state {:yin.k/journal :yin.k/ack :yin.k/action :yin.k/serve
                                     :yin.k/occurrence (:occurrence cell)
                                     :yin.k/request-id request-id :yin.k/answer descriptor}))
                  descriptor)))
            (:header cell))]
    (if (= :ok (:status r))
      (-> state
          (assoc-in [k :record] (:record r))
          (assoc-in [k :prepared?] true)
          (with-status :yin.k/ok
            {:export (:role cell) :step :prepared}))
      (-> state
          (assoc-in [k :record] (:record r))
          (unsatisfied :export-refused
                       (select-keys r [:yin.k/status :yin.k/reason
                                       :yin.k/kind :yin.k/path]))))))


(defn- export-fence-step
  "Freeze the complete record and persist both addressed objects before
   journaling the fence: the recovery object first, then the body, and a
   store that refuses stops the step before the next object is
   attempted. The journal contains canonical progress only; the runtime
   environment retains composition functions and handles."
  [state k]
  (when (= :exit k) (require-tenure! state))
  (let [cell (get state k)
        e (export/freeze (:m cell) (:record cell))]
    (if (not= :ok (:status e))
      (unsatisfied state :export-refused
                   (select-keys e [:yin.k/status :yin.k/reason
                                   :yin.k/kind :yin.k/path]))
      (let [bytes (:body-bytes e)
            address (:body-address e)
            record-bytes (:bytes e)
            record-address (:address e)
            store-object
            (fn [[object-address object-bytes]]
              (let [request-id [:yin.k/store (:occurrence cell) object-address]]
                (journal! state {:yin.k/journal :yin.k/intent :yin.k/action :yin.k/store
                                 :yin.k/occurrence (:occurrence cell)
                                 :yin.k/request {:yin.k/request-id request-id
                                                 :yin.k/address object-address}})
                (when (= :exit k) (require-tenure! state))
                (let [stored? (store-put! state object-address object-bytes)]
                  (when stored?
                    (journal! state {:yin.k/journal :yin.k/ack :yin.k/action :yin.k/store
                                     :yin.k/occurrence (:occurrence cell)
                                     :yin.k/request-id request-id
                                     :yin.k/answer {:yin.k/status :stored}}))
                  stored?)))]
        (if-not (and (store-object [record-address record-bytes])
                     (store-object [address bytes]))
          (unsatisfied state :content-unavailable {:yin.k/address address})
          (do
            (journal! state
                      {:yin.k/journal :yin.k/fenced
                       :yin.k/occurrence (:occurrence cell)
                       :yin.k/address address
                       :yin.k/record record-address})
            (-> state
                (assoc-in [k :address] address)
                (assoc-in [k :record-address] record-address)
                (assoc-in [k :bytes] bytes)
                (assoc-in [k :kind] (:kind e))
                (with-status :yin.k/ok
                  {:export (:role cell) :step :fenced}))))))))


(defn- offer-request
  [o address medium bytes]
  {:yin.k/request :yin.k/offer
   :yin.k/request-id [:yin.k/offer o]
   :yin.k/id address
   :yin.k/bytes bytes
   :yin.k/medium medium})


(defn- admitted?
  [answer]
  (contains? #{:committed :replayed} (get answer :yin.k/status)))


(defn- export-offer-step
  "The offer bracket of `k`'s cell: one intent record durable before
   the bracket's first send, one attempt record after each send
   carrying the append's outcome, and the acknowledgment once the
   front's authenticated reply admits the offer.  The request is
   rebuilt from the cell (the bytes from the store when the cell holds
   none), so a resend is the identical request."
  [state k]
  (when (and (= :exit k) (:holder state) (not (get-in state [:release :carried])))
    (require-tenure! state))
  (let [cell (get state k)
        o (:occurrence cell)
        medium (get-in state [:seams :medium])
        offer (or (:offer cell)
                  {:request (offer-request o (:address cell) medium
                                           (or (:bytes cell)
                                               (store-get state
                                                          (:address cell))))})]
    (journal! state
              {:yin.k/journal :yin.k/intent
               :yin.k/action :yin.k/offer
               :yin.k/occurrence o
               :yin.k/request (dissoc (:request offer) :yin.k/bytes)
               :yin.k/bytes-at (:address cell)})
    (let [_ (when (and (= :exit k) (:holder state) (not (get-in state [:release :carried])))
              (require-tenure! state))
          outcome (append-request! state (:request offer))
          offer' (assoc offer
                        :intended? true
                        :sent (or (:sent offer)
                                  (= :dao.stream/ok
                                     (:dao.stream/outcome outcome))))]
      (journal! state
                {:yin.k/journal :yin.k/attempt
                 :yin.k/action :yin.k/offer
                 :yin.k/occurrence o
                 :yin.k/append (:dao.stream/outcome outcome)})
      (-> state
          (assoc-in [k :offer] offer')
          (with-status :yin.k/ok
            {:export (:role cell) :step :offered})))))


(defn- offer-done?
  [cell]
  (admitted? (get-in cell [:offer :answer])))


(defn- source-mint
  "The first step of a first exclusive export: enter exporting, mint
   the occurrence once, and journal it before prepare is ever called
   (the D9 ruling).  The header is the driver's: the composition's
   arbitration, next-op-seq 0, no origin, the enrolled set of the
   ledger fold."
  [state]
  (let [cell (:export state)
        e (export/enter (:machine cell) {:version (:export-version state)})]
    (if (not= :ok (:status e))
      (failed state e)
      (let [[enrolled reason] (enrolled-of state)]
        (if (nil? enrolled)
          (unsatisfied state reason
                       {:dao.stream/identity (:arbitration state)})
          (let [o (custody/mint-occurrence)
                header {:yin.k/occurrence o
                        :yin.k/arbitration (:arbitration cell)
                        :yin.k/next-op-seq 0
                        :yin.k/enrolled enrolled}]
            (journal! state
                      {:yin.k/journal :yin.k/minted
                       :yin.k/role (:role cell)
                       :yin.k/occurrence o
                       :yin.k/header header})
            (-> state
                (assoc :export (assoc cell
                                      :occurrence o
                                      :header header
                                      :m (:machine e)
                                      :record (:record e)))
                (with-status :yin.k/ok
                  {:export :yin.k/first :step :minted}))))))))


(defn- fork-step
  "The composition's fork decision (a nil arbitration): the version-0
   lift over the machine -- enter, prepare under a nil header, encode --
   with no occurrence, no offer and no journal bracket.  The answer is
   the bytes."
  [state]
  (let [cell (:export state)]
    (cond
      (nil? (:m cell))
      (let [e (export/enter (:machine cell) {:version (:export-version state)})]
        (if (= :ok (:status e))
          (assoc state :export (assoc cell
                                      :m (:machine e)
                                      :record (:record e)))
          (failed state e)))

      (not (:prepared? cell))
      (export-prepare-step state :export)

      :else
      (let [e (export/encode (:m cell) (:record cell))]
        (if (= :ok (:status e))
          (assoc state
                 :phase :lifted
                 :status :yin.k/ok
                 :detail {:yin.k/kind (:kind e)
                          :yin.k/address (:address e)
                          :bytes (:bytes e)})
          (unsatisfied state :export-refused
                       (select-keys e [:yin.k/status :yin.k/reason
                                       :yin.k/kind :yin.k/path])))))))


(defn- to-candidacy
  "The source becomes a candidate like any other, over its own bytes."
  [state]
  (let [cell (:export state)]
    (-> state
        (dissoc :export)
        (assoc :phase :proposing
               :occurrence (:occurrence cell)
               :arbitration (get-in cell
                                    [:header :yin.k/arbitration
                                     :dao.stream/identity])
               :kind (:kind cell)
               :bytes (:bytes cell)
               :address (:address cell))
        (with-status :yin.k/awaiting-grant
          {:yin.k/occurrence (:occurrence cell)}))))


(defn- step-exporting
  [state]
  (let [state (if (nil? (get-in state [:export :role])) state (drain-control state))
        cell (:export state)]
    (cond
      (:inbox-unavailable? state) state
      (nil? (:role cell)) (fork-step state)
      (nil? (:occurrence cell)) (source-mint state)
      (not (:prepared? cell)) (export-prepare-step state :export)
      (nil? (:address cell)) (export-fence-step state :export)
      (offer-done? cell) (to-candidacy state)
      :else (export-offer-step state :export))))


;; =============================================================================
;; The exit half (r3 1.6's residual and the landed completion contract):
;; the successor append, the resumed report, the release, the closure
;; =============================================================================

(defn- arm-exit
  "Arm the exit over the state's machine: enter exporting now (the
   refusal answers and arms nothing).  The fenced machine stays as
   :machine and travels in the cell; the answer is the armed state or
   the refusal, never both."
  [state role]
  (let [e (export/enter (:machine state) {:version (:export-version state)})]
    (if (= :ok (:status e))
      {:state (-> state
                  (assoc :machine (:machine e))
                  (assoc :exit {:role role
                                :m (:machine e)
                                :record (:record e)}))
       :refusal nil}
      {:state (with-status state :yin.k/unsatisfied
                {:yin.k/reason :not-liftable
                 :refusal (select-keys e [:yin.k/status :yin.k/kind
                                          :yin.k/path :yin.k/hold])})
       :refusal e})))


(defn hand-off
  "Arm the exit half over a holder whose machine is parked or blocked
   at a liftable safepoint: the composition's explicit export of a
   parked continuation (UCF 7.8's lift is composition-driven, never
   automatic on a park).  The phase becomes :safepoint and the next
   step mints the successor; a machine that cannot enter exporting
   answers the refusal, arms nothing and keeps running."
  [state]
  (if (= :stalled (:phase state))
    state
    (let [{:keys [state refusal]} (arm-exit state :yin.k/successor)]
      (if (nil? refusal)
        (assoc state :phase :safepoint
               :status :yin.k/ok
               :detail {:yin.k/occurrence (:occurrence state)
                        :dao.lease/lease (:lease state)})
        state))))


(defn- exit-mint
  "The first step of the exit: mint the successor occurrence once and
   journal it before prepare.  The header is built from the custody
   map: a fresh occurrence, the same arbitration, an origin naming the
   predecessor occurrence and lease and this driver as the emitter, the
   current counter, and the enrolled set of the ledger fold."
  [state]
  (when (:holder state) (require-tenure! state))
  (let [cell (:exit state)
        cust (get-in (:machine state) [:yin.k/custody])
        [enrolled reason] (enrolled-of state)]
    (if (nil? enrolled)
      (unsatisfied state reason {:dao.stream/identity (:arbitration state)})
      (let [o (custody/mint-occurrence)
            header {:yin.k/occurrence o
                    :yin.k/arbitration (:yin.k/arbitration cust)
                    :yin.k/next-op-seq (:yin.k/next-op-seq cust)
                    :yin.k/enrolled enrolled
                    :yin.k/origin {:yin.k/occurrence (:yin.k/occurrence cust)
                                   :dao.lease/lease (:dao.lease/lease cust)
                                   :yin.k/emitter (:me state)}}]
        (journal! state
                  {:yin.k/journal :yin.k/minted
                   :yin.k/role (:role cell)
                   :yin.k/occurrence o
                   :yin.k/header header})
        (-> state
            (assoc :exit (assoc cell :occurrence o :header header))
            (with-status :yin.k/ok
              {:exit (:role cell) :step :minted}))))))


(defn- exit-report-step
  "The resumed report bracket: one intent record durable before the
   send, naming the successor's address; for a halt the report carries
   the result body's address and the closure's edge is terminal.  The
   acknowledgment is journaled when the front's authenticated reply
   commits or replays it."
  [state]
  (when (:holder state) (require-tenure! state))
  (let [cell (:exit state)
        o1 (:occurrence state)
        l (:lease state)
        address (:address cell)
        request {:yin.k/request :yin.k/resumed
                 :yin.k/request-id [:yin.k/resumed o1 l address]
                 :yin.k/report (completion/resumed o1 l address)
                 :yin.k/bytes (or (:bytes cell)
                                  (store-get state address))}]
    (journal! state
              {:yin.k/journal :yin.k/intent
               :yin.k/action :yin.k/resumed
               :yin.k/occurrence o1
               :dao.lease/lease l
               :yin.k/request (dissoc request :yin.k/bytes)
               :yin.k/bytes-at address})
    (let [_ (when (:holder state) (require-tenure! state))
          outcome (append-request! state request)]
      (journal! state {:yin.k/journal :yin.k/attempt :yin.k/action :yin.k/resumed
                       :yin.k/occurrence o1 :yin.k/request-id (:yin.k/request-id request)
                       :yin.k/append (:dao.stream/outcome outcome)})
      (-> state
          (assoc-in [:exit :report]
                    {:request request
                     :sent (or (get-in cell [:report :sent])
                               (= :dao.stream/ok
                                  (:dao.stream/outcome outcome)))})
          (with-status :yin.k/ok {:exit (:role cell) :step :reported})))))


(defn- release!
  "The release bracket of lease `l`: one intent record durable before
   the bracket's first send, then the send, retried with the identical
   request until authenticated carriage is acknowledged.  Used by the
   exit half and, through `releasing`, by a run end."
  [state l]
  (let [cell (or (:release state)
                 {:lease l
                  :request (release-request l)
                  :sent false})]
    (when (not (true? (:carried cell)))
      (journal! state
                {:yin.k/journal :yin.k/intent
                 :yin.k/action :yin.k/release
                 :dao.lease/lease l
                 :yin.k/request (:request cell)}))
    (if (true? (:carried cell))
      state
      (let [outcome (append-request! state (:request cell))]
        (journal! state {:yin.k/journal :yin.k/attempt :yin.k/action :yin.k/release
                         :dao.lease/lease l
                         :yin.k/request-id (get-in cell [:request :yin.k/request-id])
                         :yin.k/append (:dao.stream/outcome outcome)})
        (assoc state
               :release (assoc cell
                               :intended? true
                               :sent (or (:sent cell)
                                         (= :dao.stream/ok
                                            (:dao.stream/outcome outcome)))))))))


(defn- exited
  "The exit's terminal state."
  [state detail]
  (assoc state :phase :exited :status :yin.k/ok :detail detail))


(defn- end-recovered-exit
  [state cause]
  (let [detail {:cause cause :yin.k/occurrence (:occurrence state)
                :dao.lease/lease (:lease state)
                :yin.k/successor (get-in state [:exit :occurrence])
                :yin.k/result (get-in state [:exit :address])}
        diagnostic {:yin.k/diagnostic :yin.k/run-ended
                    :yin.k/occurrence (:occurrence state)
                    :dao.lease/lease (:lease state) :yin.k/end detail}]
    (append-diagnostic! state diagnostic)
    (-> state
        (assoc :phase :failed :status :yin.k/ended :detail detail)
        (update :diagnostics conj diagnostic))))


(defn- matching-exit-closure?
  [state projection]
  (let [cell (:exit state)
        closed (get-in projection [:occurrences (:occurrence state) :yin.k/closed])]
    (and (some? closed)
         (= (:lease state) (:dao.lease/lease closed))
         (= (:address cell) (get-in projection [:leases (:lease state) :yin.k/result]))
         (if (= :yin.k/result (:role cell))
           (and (nil? (:yin.k/successor closed))
                (= (:address cell) (:yin.k/result closed)))
           (= (:occurrence cell) (:yin.k/successor closed))))))


(defn- exit-closure-step
  "The authoritative closure (the grantor's transition on a release or policy
   lapse of a reported lease), read from the ledger fold.  Until it
   stands the exit waits; once it does, a halted result is done and a
   continuation drives the successor's offer to its admission -- the
   last bracket -- so the successor is eligible exactly once."
  [state projection]
  (let [cell (:exit state)
        o1 (:occurrence state)
        closed (get-in projection [:occurrences o1 :yin.k/closed])]
    (if-not (matching-exit-closure? state projection)
      (if (and (:restarted? state) (some? closed))
        (end-recovered-exit state :completion-mismatch)
        (with-status state :yin.k/ok {:exit (:role cell) :step :closure}))
      (if (= :yin.k/result (:role cell))
        (exited state
                {:yin.k/result (:address cell)
                 :yin.k/occurrence o1
                 :dao.lease/lease (:dao.lease/lease closed)})
        (if (offer-done? cell)
          (exited state
                  {:yin.k/successor (:occurrence cell)
                   :yin.k/address (:address cell)
                   :yin.k/occurrence o1
                   :dao.lease/lease (:dao.lease/lease closed)})
          (export-offer-step (assoc-in state [:exit :closure] closed) :exit))))))


(defn- step-exit-body
  "One step of the exit body over the armed :exit cell: mint, prepare,
   fence, the successor append, the report, the release -- in order, so
   an exit cut at any boundary resumes from the last one."
  [state projection]
  (let [cell (:exit state)
        answer (get-in cell [:report :answer])]
    (cond
      (nil? (:occurrence cell)) (exit-mint state)
      (not (:prepared? cell)) (export-prepare-step state :exit)
      (nil? (:address cell)) (export-fence-step state :exit)

      ;; the successor append: sent once before the report, per the
      ;; exit's order; the authority refuses it :awaiting-completion
      ;; until the closure, and the closure step drives its admission
      (and (= :yin.k/successor (:role cell))
           (nil? (:offer cell)))
      (export-offer-step state :exit)

      (nil? (get-in cell [:report :answer])) (exit-report-step state)

      (= :suspended (:yin.k/status answer)) (exit-report-step state)

      (not (admitted? (get-in cell [:report :answer])))
      (end-run state (or (:yin.k/reason answer) :report-refused)
               {:yin.k/answer answer})

      (not (true? (get-in state [:release :carried])))
      (release! state (:lease state))

      :else (exit-closure-step state projection))))


(defn- step-exiting
  "The step of :exiting, the exit of a restarted holder: no machine and
   no holder state -- the process died -- so no renewal and no local
   tenure arithmetic.  The ledger's own fold says whether the closure
   stands; a tenure that ended before the report committed leaves the
   successor an orphan and the exit over."
  [state]
  (let [records (read-ledger! state)]
    (if (nil? records)
      (unsatisfied state :no-arbitration
                   {:dao.stream/identity (:arbitration state)})
      (let [mine-records (vec (mine (:arbitration state) records))
            defect (history-defect mine-records)
            projection (when (nil? defect)
                         (fold (:arbitration state) mine-records))
            o1 (:occurrence state)
            state (if (and projection (nil? defect))
                    (drain-control
                      (cond-> state
                        (matching-exit-closure? state projection)
                        (assoc-in [:exit :closure] (get-in projection [:occurrences o1 :yin.k/closed]))))
                    state)]
        (cond
          (:inbox-refused? state) state
          (or (some? defect) (nil? projection))
          (unsatisfied state (or defect :fold-defect) {})

          (some? (get-in projection [:occurrences o1 :yin.k/closed]))
          (exit-closure-step state projection)

          (get-in projection [:occurrences o1 :yin.k/quarantined])
          (end-recovered-exit state :quarantined-occurrence)

          (get-in projection [:occurrences o1 :yin.k/exhausted])
          (end-recovered-exit state :exhausted-occurrence)

          (and (some? (get-in projection [:leases (:lease state) :dao.lease/cause]))
               (= (get-in state [:exit :address])
                  (get-in projection [:leases (:lease state) :yin.k/result])))
          (end-recovered-exit state :incomplete-completion-history)

          (and (:restarted? state)
               (some? (get-in projection [:leases (:lease state) :dao.lease/cause])))
          (-> state
              (dissoc :release :holder :evidence)
              (assoc :phase :proposing :lease nil :proposal nil :exit nil
                     :bytes (get-in state [:restart-checkpoint :bytes])
                     :address (get-in state [:restart-checkpoint :address]))
              (with-status :yin.k/awaiting-grant {:yin.k/occurrence o1}))

          (and (:restarted? state)
               (not= (get-in state [:exit :address])
                     (get-in projection [:leases (:lease state) :yin.k/result])))
          (if-not (true? (get-in state [:release :carried]))
            (release! state (:lease state))
            (if (nil? (get-in projection [:leases (:lease state) :dao.lease/cause]))
              (with-status state :yin.k/ok {:exit :release-visibility})
              (-> state
                  (assoc :phase :releasing :status :yin.k/ended
                         :detail {:cause :restarted :yin.k/occurrence o1}
                         :exit nil
                         :bytes (get-in state [:restart-checkpoint :bytes])
                         :address (get-in state [:restart-checkpoint :address]))
                  (assoc-in [:release :re-propose?] (some? (get-in state [:restart-checkpoint :bytes]))))))

          (and (nil? (get-in projection [:occurrences o1 :dao.lease/lease]))
               (not (admitted? (get-in state [:exit :report :answer]))))
          (assoc state :phase :failed
                 :status :yin.k/ended
                 :detail {:cause :stale :yin.k/occurrence o1})

          :else (step-exit-body
                  (cond-> state
                    (:restarted? state)
                    (assoc-in [:exit :report :answer] {:yin.k/status :replayed}))
                  projection))))))


;; =============================================================================
;; The release: cleanup, retried until it has left
;; =============================================================================

(defn- release-request
  [l]
  {:yin.k/request :yin.k/release
   :yin.k/request-id [:yin.k/release l]
   :dao.lease/lease l})


(defn- releasing-state
  "The state of a run just ended (r3 1.6), before the release bracket
   opens: any machine is gated ended, the dao.lease holder has stopped,
   and the failure the release will answer rides :status and :detail.
   The bracket itself opens through `release!` -- for an abort whose
   journal append crossed the lease bound it opens only with the next
   step, so the abort's terminal record is the journal's last word of
   the call that wrote it.  This is cleanup, never recovery: it clears
   no quarantine, completes nothing without accepted completion
   evidence, and the occurrence of an ordinary failed run stays
   regrantable under the ordinary policy."
  [state status detail]
  (-> state
      (assoc :phase :releasing
             :status status
             :detail detail
             :machine (some-> (:machine state) ended)
             :holder (some-> (:holder state)
                             (as-> h (when (some? (:grant h))
                                       (:holder (lease/stop h)))))
             :release nil)))


(defn- releasing
  "`state` entering :releasing over lease `l`, carrying the failure it
   will answer once the release has left: any machine is gated ended,
   the dao.lease holder has stopped, and the release bracket opens --
   its intent journaled before the send, the identical request retried
   until authenticated carriage.  This is cleanup, never recovery: it
   clears no quarantine, completes nothing without accepted completion
   evidence, and the occurrence of an ordinary failed run stays
   regrantable under the ordinary policy."
  [state status detail l]
  (release! (releasing-state state status detail) l))


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
   `:yin.k/awaiting-grant`.  A minted id's intent record is durable in
   the journal before its first send, so a resent proposal keeps its
   stable id across a reopen.  A stopped candidate never proposes
   again, and an unavailable inbox holds the proposal until a scan
   succeeds: either way the outstanding proposal waits passively, and
   a late grant to it is still observed from the ledger."
  [state projection]
  (cond
    (and (:stopped? state)
         (or (nil? (:proposal state))
             (= :dao.lease/rejected (get-in projection [:answered [(:me state) (get-in state [:proposal :id])]]))))
    (with-status state :yin.k/ended {:cause :stopped})

    (or (:stopped? state) (:inbox-unavailable? state))
    state

    :else
    (let [refused? (= :dao.lease/rejected
                      (get-in projection [:answered [(:me state)
                                                     (:id (:proposal state))]]))
          mint? (or (nil? (:proposal state)) refused?)
          proposal (if mint?
                     (let [pid (fresh-pid state)
                           request (proposal-request (:occurrence state) pid)]
                       {:id pid :request request :sent false})
                     (:proposal state))
          sent? (true? (:sent proposal))
          outcome (when-not (:carried proposal)
                    (journal! state {:yin.k/journal :yin.k/intent :yin.k/action :yin.k/proposal
                                     :yin.k/occurrence (:occurrence state)
                                     :yin.k/request (:request proposal)})
                    (let [answer (append-request! state (:request proposal))]
                      (journal! state {:yin.k/journal :yin.k/attempt :yin.k/action :yin.k/proposal
                                       :yin.k/occurrence (:occurrence state)
                                       :yin.k/request-id (get-in proposal [:request :yin.k/request-id])
                                       :yin.k/append (:dao.stream/outcome answer)})
                      answer))
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
             :dao.lease/proposal (:id proposal')})))))


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
                        :bound (let [duration-bound (+ (max (base basis)
                                                            (base (or (:last-renewal-at holder) basis)))
                                                       (base duration))]
                                 (if-some [cap (:dao.lease/max grant)]
                                   (min duration-bound (+ (base basis) (base cap)))
                                   duration-bound))
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
      (cond
        (never-granted-again? entry)
        (with-status state :yin.k/not-holder (occurrence-state projection o))
        (:stopped? state) (with-status state :yin.k/ended {:cause :stopped})
        :else
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
      (do
        (journal! state
                  {:yin.k/journal :yin.k/ack
                   :yin.k/action :yin.k/grant
                   :yin.k/occurrence o
                   :dao.lease/lease l})
        (let [accepted (-> state
                           (assoc :phase :activating :lease l :holder holder' :evidence E)
                           (with-status :yin.k/ok
                             {:yin.k/occurrence o :dao.lease/lease l}))]
          (if (:stopped? accepted)
            (releasing accepted :yin.k/ended {:cause :stopped} l)
            accepted))))))


(defn- ack!
  "Journal the acknowledgment of the bracketed action whose request id
   is `rid`, with its authenticated `answer`."
  [state action occurrence rid answer]
  (journal! state
            {:yin.k/journal :yin.k/ack
             :yin.k/action action
             :yin.k/occurrence occurrence
             :yin.k/request-id rid
             :yin.k/answer answer})
  state)


(defn- ack-offer
  "The reply to this driver's own offer: the answer rides the cell (of
   `k`, :export or :exit) and the acknowledgment is journaled once the
   offer is admitted.  A refusal -- :awaiting-completion before the
   closure, the expected interim answer of a successor's offer -- keeps
   the bracket open and the resend coming."
  [state k rid answer]
  (let [state (assoc-in state [k :offer :answer] answer)]
    (if (admitted? answer)
      (ack! state :yin.k/offer (get-in state [k :occurrence]) rid answer)
      state)))


(defn- control-reply
  "One attributed reply of the front to this driver's own requests: a
   proposal, renewal, release, offer or resumed-report carriage.  It
   counts only when `front/reply-evidence` authenticates it -- the
   author attributed to the stream equals the arbitration identity --
   and its request id is one this driver sent; anything else changes
   nothing.  A bracketed action's acknowledgment is durable in the
   journal when its answer stands: a carriage for a proposal, renewal
   or release, an admission for an offer, a commit or replay for a
   report."
  [state author record]
  (let [arb (:arbitration state)
        e (front/reply-evidence arb author record)]
    (if (contains? e ::front/no-evidence)
      state
      (let [rid (get record :yin.k/request-id)
            answer (get record :yin.k/answer)
            carried? (= :carried (get answer :yin.k/status))]
        (cond
          (and (map? (:proposal state))
               (= :yin.k/proposal (get record :yin.k/reply))
               (= (get-in (:proposal state)
                          [:request :yin.k/request-id])
                  rid))
          (if carried?
            (-> state
                (assoc-in [:proposal :carried] true)
                (ack! :yin.k/proposal (:occurrence state) rid answer))
            (assoc-in state [:proposal :carried] false))

          (and (map? (:release state))
               (not (and (:restarted? state) (get-in state [:exit :closure])))
               (= :yin.k/release (get record :yin.k/reply))
               (= (get-in (:release state)
                          [:request :yin.k/request-id])
                  rid))
          (if carried?
            (-> state
                (assoc-in [:release :carried] true)
                (ack! :yin.k/release nil rid answer))
            (assoc-in state [:release :carried] false))

          (and (= :yin.k/offer (get record :yin.k/reply))
               (map? (:export state))
               (= (get-in state [:export :offer :request
                                 :yin.k/request-id])
                  rid))
          (ack-offer state :export rid answer)

          (and (= :yin.k/offer (get record :yin.k/reply))
               (map? (:exit state))
               (= (get-in state [:exit :offer :request
                                 :yin.k/request-id])
                  rid))
          (ack-offer state :exit rid answer)

          (and (= :yin.k/resumed (get record :yin.k/reply))
               (map? (:exit state))
               (= (get-in state [:exit :report :request
                                 :yin.k/request-id])
                  rid))
          (let [state (assoc-in state [:exit :report :answer] answer)]
            (if (admitted? answer)
              (ack! state :yin.k/resumed (:occurrence state) rid answer)
              state))

          (and (contains? #{:activating :running :safepoint} (:phase state))
               (map? (:renewal state))
               (= :yin.k/renewal (get record :yin.k/reply))
               (= (get-in state [:renewal :request :yin.k/request-id]) rid)
               (= :carried (get-in record [:yin.k/answer :yin.k/status])))
          (if (lease/holding? (:holder state) (clock state))
            (let [state (ack! state :yin.k/renewal (:occurrence state) rid answer)]
              (if (lease/holding? (:holder state) (clock state))
                (-> state
                    (update :holder lease/observe-renewal
                            (get-in state [:renewal :reading])
                            {:dao.stream/outcome :dao.stream/ok})
                    (assoc :renewal nil :renewal-pending? false)
                    (update :renewals inc))
                (end-run state :lease-bound {:dao.lease/lease (:lease state)})))
            (end-run state :lease-bound {:dao.lease/lease (:lease state)}))

          :else state)))))


(defn- run-binding
  [state]
  (when (:lease state)
    {:yin.k/occurrence (:occurrence state)
     :dao.lease/lease (:lease state)
     :yin.k/epoch (get-in state [:evidence :yin.k/binding :yin.k/epoch])}))


(defn- program-record?
  [record]
  (and (map? record)
       (or (contains? #{:yin.k/admit :yin.k/input} (:yin.k/reply record))
           (and (nil? (:yin.k/reply record)) (contains? record :yin.k/admission)))))


(defn- restore-inbox
  [state receipts]
  (let [receipts (inbox/reconcile! (:seams state) receipts)]
    (assoc state :inbox {:positions (inbox/positions receipts)
                         :identities (inbox/identities receipts)
                         :program (filterv #(program-record? (:yin.k/record %)) receipts)
                         :control (filterv #(not (program-record? (:yin.k/record %))) receipts)})))


(defn- dispatch-receipt
  [state receipt]
  (if (program-record? (:yin.k/record receipt))
    (update-in state [:inbox :program] conj receipt)
    (control-reply state (:yin.k/author receipt) (:yin.k/record receipt))))


(defn- reconcile-cleanup-inbox
  [state]
  (if-not (contains? state :inbox-reconciliation)
    state
    (try
      (inbox/reconcile! (:seams state) (:inbox-reconciliation state))
      (dissoc state :inbox-reconciliation)
      (catch #?(:cljd Object :clj Throwable :cljs :default) failure
        (if-some [reason (::inbox/refusal (ex-data failure))]
          (cond-> (-> state
                      (assoc :inbox-unavailable? true)
                      (unsatisfied reason {}))
            (not= :inbox-unavailable reason) (assoc :inbox-refused? true))
          (throw failure))))))


(defn- collect-inbox
  "Select reply-first, retaining the complete attributed record before dispatch.
   A selected but unretained observation remains reproducible at its position."
  [state]
  (let [state (reconcile-cleanup-inbox state)]
    (if (contains? state :inbox-reconciliation)
      state
      (let [state (reduce dispatch-receipt state (get-in state [:inbox :control]))
            state (-> state (assoc-in [:inbox :control] []) (dissoc :inbox-unavailable? :inbox-refused?))]
        (loop [state state lane :reply]
          (let [descriptor (get-in state [:seams (get inbox/lanes lane)])
                position (get-in state [:inbox :positions lane])
                selection (try
                            (when (and (contains? (get-in state [:inbox :identities]) lane)
                                       (not (inbox/same-data? (:identity descriptor)
                                                              (get-in state [:inbox :identities lane]))))
                              (throw (ex-info "Changed holder inbox identity" {::inbox/refusal :inbox-identity})))
                            {:answer (inbox/read-at descriptor position)}
                            (catch #?(:cljd Object :clj Throwable :cljs :default) failure
                              (if-some [reason (::inbox/refusal (ex-data failure))]
                                {:refusal reason}
                                (throw failure))))
                answer (:answer selection)]
            (if-some [reason (:refusal selection)]
              (-> state
                  (assoc :inbox-unavailable? true :inbox-refused? true)
                  (unsatisfied reason {:yin.k/lane lane}))
              (case (:status answer)
                :empty (if (= :reply lane) (recur state :outcome) state)
                :unavailable (-> state
                                 (assoc :inbox-unavailable? true)
                                 (unsatisfied :inbox-unavailable {:yin.k/lane lane}))
                :record
                (let [receipt (cond-> {:yin.k/journal :yin.k/inbox
                                       :yin.k/lane lane :yin.k/identity (:identity descriptor)
                                       :yin.k/position position :yin.k/author (:author answer)
                                       :yin.k/record (:record answer)}
                                (run-binding state) (assoc :yin.k/binding (run-binding state)))]
                  (journal! state receipt)
                  (recur (dispatch-receipt (-> state
                                               (update-in [:inbox :positions lane] inc)
                                               (assoc-in [:inbox :identities lane] (:identity descriptor))) receipt)
                         :reply))))))))))


(defn- drain-control
  [state]
  (collect-inbox state))


(defn- step-proposing
  "Observe the grant from the ledger even while the inbox is
   unavailable -- a late grant to a stopped candidate is accepted and
   released, never lowered -- and leave the proposal to `propose`."
  [state]
  (let [state (drain-control state)]
    (if (:inbox-refused? state)
      state
      (let [records (read-ledger! state)]
        (if (nil? records)
          (unsatisfied state :no-arbitration {:dao.stream/identity (:arbitration state)})
          (let [arbitration (:arbitration state)
                own-records (vec (mine arbitration records))
                defect (history-defect own-records)
                grants (filterv #(contains? (:sent-proposals state) (:dao.lease/proposal %))
                                (grants-to arbitration (:me state) (:occurrence state) records))]
            (if (seq grants)
              (accept state records defect (peek grants))
              (if defect
                (unsatisfied state defect {})
                (if-some [projection (fold arbitration own-records)]
                  (let [entry (get-in projection [:occurrences (:occurrence state)])]
                    (if (or (:dao.lease/lease entry) (never-granted-again? entry))
                      (with-status state :yin.k/not-holder
                        (occurrence-state projection (:occurrence state)))
                      (propose state projection)))
                  (unsatisfied state :fold-defect {}))))))))))


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
      (journal! state {:yin.k/journal :yin.k/intent :yin.k/action :yin.k/renewal
                       :dao.lease/lease (:lease state) :yin.k/reading (:reading pending)
                       :yin.k/request (:request pending)})
      (require-tenure! state)
      (let [answer (append-request! state (:request pending))]
        (journal! state {:yin.k/journal :yin.k/attempt :yin.k/action :yin.k/renewal
                         :dao.lease/lease (:lease state)
                         :yin.k/request-id (get-in pending [:request :yin.k/request-id])
                         :yin.k/append (:dao.stream/outcome answer)}))
      [(assoc state :renewal pending :renewal-pending? true) holder])))


(defn- binding-check
  "Fresh complete evidence and the same live occurrence, lease and epoch.
   Availability suspends; contradiction ends the run through its existing cleanup."
  [state]
  (if-not (lease/holding? (:holder state) (clock state))
    {:state (end-run state :lease-bound {:dao.lease/lease (:lease state)})}
    (let [records (read-ledger! state)
          arbitration (:arbitration state)
          attributed (when records (vec (mine arbitration records)))
          defect (when records (history-defect attributed))
          projection (when (and records (nil? defect)) (fold arbitration attributed))
          binding (when projection (custody/binding-evidence arbitration records (:lease state)))
          proof (when projection (evidence/read-evidence arbitration records (:lease state)))
          entry (get-in projection [:occurrences (:occurrence state)])]
      (cond
        (or (nil? records) defect (nil? projection))
        {:state (unsatisfied state (or defect :no-arbitration) {})}
        (or (::custody/no-evidence binding)
            (not= (run-binding state)
                  (select-keys binding [:yin.k/occurrence :dao.lease/lease :yin.k/epoch]))
            (not= (:lease state) (:dao.lease/lease entry))
            (not= (get-in state [:evidence :yin.k/binding :yin.k/epoch]) (:yin.k/epoch entry)))
        {:state (end-run state :stale {:dao.lease/lease (:lease state)
                                       :dao.lease/observed-lease (:dao.lease/lease entry)})}
        (not= :yin.k/ready (:yin.k/status proof))
        {:state (unsatisfied state (or (:yin.k/reason proof) :no-evidence) {})}
        (not (lease/holding? (:holder state) (clock state)))
        {:state (end-run state :lease-bound {:dao.lease/lease (:lease state)})}
        :else {:state state :projection projection :proof proof}))))


(defn- apply-program-inbox
  [state]
  (loop [state state]
    (if-some [receipt (first (get-in state [:inbox :program]))]
      (if (not= (run-binding state) (:yin.k/binding receipt))
        (recur (update-in state [:inbox :program] #(vec (rest %))))
        (let [{checked :state projection :projection} (binding-check state)]
          (if-not projection
            (assoc checked :program-suspended? true)
            (let [record (:yin.k/record receipt)
                  result (if (= :yin.k/input (:yin.k/reply record))
                           (reader/settle (:machine checked) (:yin.k/author receipt) record)
                           (writer/discharge (:machine checked) (:yin.k/author receipt) record))]
              (recur (-> checked
                         (assoc :machine (:machine result)
                                :run-end (or (:run-end checked) (:run-end result)))
                         (update-in [:inbox :program] #(vec (rest %)))))))))
      state)))


(defn- drain
  [state mode]
  (let [state (if (contains? #{:program :control-buffered} mode) state (collect-inbox state))]
    (if (or (contains? #{:control :control-buffered} mode) (:stopped? state) (:inbox-unavailable? state)
            (not (contains? #{:running :safepoint} (:phase state))))
      state
      (apply-program-inbox state))))


(defn- activate
  [state]
  (let [{checked :state projection :projection proof :proof} (binding-check state)]
    (if-not projection
      checked
      (let [checked (assoc checked :evidence proof)
            result (lower checked (:lease checked) (:holder checked)
                          proof (clock checked))]
        (if (= :ok (:status result))
          (assoc checked :phase :running :machine (:vm result))
          (releasing checked (:yin.k/status result)
                     (dissoc result :yin.k/status) (:lease checked)))))))


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
        _ (require-tenure! state)
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


(defn- exit-settled?
  "True when the exit's release bracket is closed: the report stands
   and the release has been carried, so the closure is the grantor's
   transition and no tenure of this driver's can change it."
  [state]
  (and (map? (:exit state))
       (true? (get-in state [:release :carried]))))


(defn- cycle-result
  [state]
  (let [run-end (:run-end state)
        unsatisfied-step (:step-unsatisfied state)
        base (dissoc state :run-end :step-unsatisfied)]
    (cond
      (= :releasing (:phase base)) base
      run-end (end-run base (:cause run-end) (dissoc run-end :cause))
      (:inbox-unavailable? base) base
      unsatisfied-step (with-status base :yin.k/unsatisfied
                         (dissoc unsatisfied-step :yin.k/status))
      (at-safepoint? (:machine base))
      (let [{:keys [state refusal]} (arm-exit base :yin.k/result)]
        (cond-> (assoc state :phase :safepoint)
          (nil? refusal)
          (assoc :status :yin.k/ok
                 :detail {:yin.k/occurrence (:occurrence state)
                          :dao.lease/lease (:lease state)})))
      :else (with-status base :yin.k/ok
              {:yin.k/occurrence (:occurrence base) :dao.lease/lease (:lease base)}))))


(defn- step-active-held
  "Share binding validation and tenure between control and program scheduling.
   Only program scheduling applies retained results or prepares an export."
  [state mode]
  (let [state (if (or (= :program mode) (nil? (:lease state))) state (collect-inbox state))]
    (cond
      (:inbox-refused? state) state
      (= :releasing (:phase state)) state
      (nil? (:lease state)) state
      (and (:stopped? state) (nil? (get-in state [:exit :address])))
      (end-run state :stopped {:dao.lease/lease (:lease state)})
      (exit-settled? state)
      (if (= :program mode) state (step-exiting state))
      :else
      (let [{checked :state projection :projection} (binding-check state)]
        (if-not projection
          checked
          (let [drained (drain (dissoc checked :program-suspended?)
                               (if (= :control mode) :control-buffered :program))]
            (cond
              (= :releasing (:phase drained)) (dissoc drained :run-end)
              (:program-suspended? drained) (dissoc drained :program-suspended?)
              (:run-end drained) (end-run (dissoc drained :run-end)
                                          (get-in drained [:run-end :cause])
                                          (dissoc (:run-end drained) :cause))
              :else
              (let [reading (clock drained)
                    _ (require-tenure! drained)
                    [renewed holder] (if (= :program mode)
                                       [drained (:holder drained)]
                                       (renew drained (:holder drained) reading))
                    state (assoc renewed :holder holder)
                    armed? (some? (:exit state))
                    preparing? (nil? (get-in state [:exit :address]))]
                (cond
                  (:inbox-unavailable? state)
                  (if (and armed? (not preparing?) (not= :program mode))
                    (let [progressed (drain (step-exit-body state projection) mode)]
                      (if (and (:inbox-unavailable? progressed) (= :yin.k/ok (:status progressed)))
                        (with-status progressed (:status state) (:detail state))
                        progressed))
                    state)
                  (and armed?
                       (or (and (= :control mode) preparing?)
                           (and (= :program mode) (not preparing?)))) state
                  armed? (drain (step-exit-body state projection) mode)
                  (or (= :control mode) (= :activating (:phase state)))
                  (with-status state :yin.k/ok
                    {:yin.k/occurrence (:occurrence state) :dao.lease/lease (:lease state)})
                  :else (cycle-result (drain (run-cycle state) mode)))))))))))


(defn- step-active
  "The held step under the catch: every seam the cycle runs through
   answers an outcome and never throws (the seam contract of
   `initial`), so a throw here is the cycle's own -- a tenure guard or
   a program effect -- and it ends the run.  An uncertain journal
   append is none of these: it unwinds untouched, and `step` stalls
   everything on it."
  [state mode]
  (try (step-active-held state mode)
       (catch #?(:cljd Object :clj Throwable :cljs :default) failure
         (if (or (::journal-uncertain (ex-data failure)) (::inbox/refusal (ex-data failure)))
           (throw failure)
           (if (= :lease-bound (::cause (ex-data failure)))
             (end-run state :lease-bound {:dao.lease/lease (:lease state)})
             (end-run state :program-failure
                      {:failure {:message (ex-message failure)
                                 :data (ex-data failure)}}))))))


;; =============================================================================
;; The release step
;; =============================================================================

(defn- step-releasing
  "A pending release is retried with the identical request until
   authenticated carriage is acknowledged, whose acknowledgment is
   journaled.  A release a restarted holder owes (:re-propose?)
   re-enters as a candidate once it has left, instead of answering the
   failure the dead run died with."
  [state]
  (let [state (drain-control state)
        release (:release state)]
    (if (:inbox-refused? state)
      state
      (if (true? (:carried release))
        (if (and (:re-propose? release) (not (:stopped? state)))
          (-> state
              (dissoc :release :holder :evidence)
              (assoc :phase :proposing :proposal nil)
              (with-status :yin.k/awaiting-grant
                {:yin.k/occurrence (:occurrence state)}))
          (-> state
              (assoc :phase :failed)
              (update :detail assoc :dao.lease/released true)))
        (release! state (:lease release))))))


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

(defn stop
  "Irreversibly stop local program progression. Control cleanup remains eligible."
  [state]
  (assoc state :stopped? true))


(defn- advance
  [state mode]
  (if (or (= :stalled (:phase state))
          (and (= :program mode) (:stopped? state)))
    state
    (try
      (case (:phase state)
        :validating (if (or (= :control mode) (:stopped? state)) state (step-validating state))
        :exporting (if (and (= :control mode) (nil? (get-in state [:export :role])))
                     state
                     (if (or (and (= :control mode) (nil? (get-in state [:export :address])))
                             (and (:stopped? state) (nil? (get-in state [:export :address])))
                             (and (= :program mode) (some? (get-in state [:export :address]))))
                       (if (= :program mode) state (drain-control state))
                       (step-exporting state)))
        :proposing (if (= :program mode) state
                       (let [observed (step-proposing state)]
                         (if (and (= :combined mode) (= :activating (:phase observed))
                                  (not (:inbox-unavailable? observed))
                                  (not (:stopped? observed)))
                           (activate observed) observed)))
        :activating (cond
                      (= :control mode) (step-active state mode)
                      (= :program mode) (if (:inbox-unavailable? state) state (activate state))
                      :else (let [observed (step-active state :control)]
                              (if (and (= :activating (:phase observed))
                                       (not (:inbox-unavailable? observed))
                                       (not (:stopped? observed)))
                                (activate observed)
                                observed)))
        (:running :safepoint) (step-active state mode)
        :exiting (if (= :program mode) state (step-exiting state))
        :releasing (if (= :program mode) state (step-releasing state))
        state)
      (catch #?(:cljd Object :clj Throwable :cljs :default) failure
        (cond
          (::journal-uncertain (ex-data failure)) (stalled state)
          (::inbox/refusal (ex-data failure))
          (unsatisfied state (::inbox/refusal (ex-data failure)) {})
          :else (throw failure))))))


(defn control-step
  "Custody-only progress: retain inbox observations, authenticate control replies,
   renew, reconcile grants, and finish established control brackets. Never lower,
   execute, apply program results, attach, observe or prepare program exports."
  [state]
  (advance state :control))


(defn program-step
  "Activate or advance the program under independently revalidated custody.
   A stopped or journal-stalled driver performs no program work."
  [state]
  (advance state :program))


(defn step
  "Normal-operation convenience over the shared split machinery, preserving
   one renewal and one program cycle per tick. No destructive inbox fallback."
  [state]
  (if (:stopped? state) (control-step state) (advance state :combined)))


(defn owed-control-write?
  "Pure total cadence query. Inbound acceptance does not discharge a bracket;
   passive ledger waits and uncertain-journal stalls owe no executable write."
  [state]
  (boolean
    (and (map? state) (not (:inbox-refused? state))
         (not (contains? #{:stalled :failed :exited :aborted :lifted} (:phase state)))
         (or (and (= :proposing (:phase state))
                  (not (:stopped? state))
                  (not (:inbox-unavailable? state))
                  (if (:proposal state)
                    (not (true? (get-in state [:proposal :carried])))
                    (and (not= :yin.k/not-holder (:status state))
                         (not= :no-arbitration (get-in state [:detail :yin.k/reason])))))
             (some? (:renewal state))
             (and (:release state) (not (true? (get-in state [:release :carried]))))
             (and (:stopped? state) (:lease state) (nil? (:exit state)) (nil? (:release state)))
             (let [cell (or (:exit state) (:export state))
                   report-answer (get-in cell [:report :answer])]
               (and (:address cell)
                    (if (:exit state)
                      (or (and (= :yin.k/successor (:role cell))
                               (nil? (:offer cell)))
                          (nil? report-answer)
                          (= :suspended (:yin.k/status report-answer))
                          (and report-answer (not (admitted? report-answer)))
                          (and (admitted? report-answer)
                               (not (true? (get-in state [:release :carried]))))
                          (and (= :yin.k/successor (:role cell))
                               (true? (get-in state [:release :carried]))
                               (:closure cell)
                               (not (offer-done? cell))))
                      (not (offer-done? cell)))))))))


;; =============================================================================
;; Reopen (r3 1.10): fold the journal, rebuild, resend from the last
;; intent
;; =============================================================================

(defn- fence-bytes
  "The stored bytes of `folded`'s fence of `occurrence`, and the fence
   itself, or nils when neither journal nor store holds them."
  [state folded occurrence]
  (let [fence (get-in folded [:fences occurrence])]
    (when (some? fence)
      [(store-get state (:address fence)) fence])))


(defn- rebuilt-cell
  "Reconstruct only a complete fenced snapshot, never a supplied live machine."
  [state folded mint]
  (let [o (:occurrence mint)
        [bytes fence] (fence-bytes state folded o)
        recovery-bytes (when fence (store-get state (:record fence)))
        _ (check! (and (some? bytes) (some? recovery-bytes)) "complete fenced snapshot" {})
        recovery (cbor/decode recovery-bytes)
        _ (check! (and (= (:address fence) (:yin.k/body-address recovery))
                       (= (:header mint) (:yin.k/header recovery))
                       (jing/segment-bytes-match? (:address fence) bytes)
                       (= (vec bytes) (vec (jing/base64->bytes (:yin.k/body-bytes recovery)))))
                  "matching fenced recovery references" {})
        recovered (export/rehydrate-fenced (:receiver state) recovery-bytes
                                           {:address (:record fence)
                                            :attach (get-in state [:seams :attach!])})
        _ (check! (= :ok (:status recovered)) "valid fenced recovery" recovered)
        _ (check! (= (:address fence)
                     (:address (export/encode (:machine recovered) (:record recovered))))
                  "matching fenced body" {})
        offer-intent (intent-of folded :yin.k/offer o)
        offer-rid (get-in offer-intent
                          [:yin.k/request :yin.k/request-id])]
    (cond-> {:role (:role mint)
             :occurrence o
             :header (:header mint)
             :m (:machine recovered)
             :record (:record recovered)
             :prepared? (some? bytes)
             :address (:address fence)
             :record-address (:record fence)
             :bytes bytes
             :kind (when (some? bytes)
                     (:yin.k/kind (checkpoint/inspect (:address fence) bytes)))
             :offer (when (some? offer-intent)
                      {:request (assoc (:yin.k/request offer-intent)
                                       :yin.k/bytes bytes)
                       :intended? true
                       :sent true
                       :answer (when (contains? (:acked folded) offer-rid)
                                 (get-in folded [:answers offer-rid
                                                 :yin.k/answer]))})}
      (nil? offer-intent) (dissoc :offer))))


(defn- reopen-source
  "Recover the complete stored snapshot as a fenced source, keeping
   its occurrence, authentic waits and retained ids. Replay the same
   journaled offer and proposal; no original machine is required."
  [config units folded]
  (let [state (assoc (bare-state config units)
                     :phase :exporting
                     :arbitration (get-in config
                                          [:arbitration
                                           :dao.stream/identity]))
        mint (:mint folded)
        cell (rebuilt-cell state folded mint)
        proposal-intent (intent-of folded :yin.k/proposal (:occurrence mint))
        proposal-id (get-in proposal-intent [:yin.k/request :dao.lease/proposal])
        request-id (get-in proposal-intent [:yin.k/request :yin.k/request-id])]
    (cond-> (assoc state
                   :export cell
                   :occurrence (:occurrence cell)
                   :status :yin.k/ok
                   :detail {:reopened :source
                            :yin.k/occurrence (:occurrence cell)})
      proposal-intent
      (to-candidacy)
      proposal-intent
      (assoc :proposals 1 :sent-proposals #{proposal-id}
             :proposal {:id proposal-id :request (:yin.k/request proposal-intent)
                        :sent true :carried (contains? (:acked folded) request-id)}))))


(defn- reopen-holder
  "The recovery of a holder: a journaled grant never restores
   execution -- the machine died with its process -- so the pending
   release is sent (its bracket journaled) and the driver re-enters as
   a candidate over its own checkpoint."
  [config units folded]
  (let [state (bare-state config units)
        {:keys [occurrence lease]} (:grant folded)
        [bytes fence] (or (when (and (some? (:bytes config))
                                     (some? (:address config)))
                            [(:bytes config) {:address (:address config)}])
                          (fence-bytes state folded occurrence))
        _ (check! (some? bytes) "the checkpoint of the granted occurrence"
                  {:yin.k/occurrence occurrence})
        base (try
               (checkpoint/inspect (get fence :address) bytes)
               (catch #?(:cljd Object :clj Throwable :cljs :default) _
                 nil))
        release-intent (some (fn [record]
                               (when (and (= :yin.k/release (:yin.k/action record))
                                          (= lease (get-in record [:yin.k/request :dao.lease/lease])))
                                 record))
                             (reverse (:ordered-intents folded)))
        release-rid (get-in release-intent
                            [:yin.k/request :yin.k/request-id])]
    (assoc state
           :phase :releasing
           :status :yin.k/ended
           :detail {:cause :restarted
                    :yin.k/occurrence occurrence
                    :dao.lease/lease lease}
           :occurrence (or (:yin.k/occurrence base) occurrence)
           :arbitration (or (get-in folded [:mint :header :yin.k/arbitration
                                            :dao.stream/identity])
                            (get-in base [:yin.k/arbitration
                                          :dao.stream/identity]))
           :bytes bytes
           :address (get fence :address)
           :lease lease
           :release {:lease lease
                     :request (if (some? release-intent)
                                (:yin.k/request release-intent)
                                (release-request lease))
                     :sent (some? release-intent)
                     :intended? (some? release-intent)
                     :carried (when (some? release-rid)
                                (contains? (:acked folded) release-rid))
                     :re-propose? true})))


(defn- reopen-exit
  "The recovery of an exit in flight (an exit cut at any boundary): the
   successor's journaled occurrence and prepared record are reused,
   never reminted, and the identical report, release and offer requests
   are resent -- idempotent through the ledger's dedup.  The report is
   rebuilt from the successor header's origin when no intent stands."
  [config units folded]
  (let [state (bare-state config units)
        mint (:mint folded)
        cell (rebuilt-cell state folded mint)
        _ (check! (some? (:address cell))
                  "the successor's fenced record" {})
        report-intent (some (fn [record]
                              (when (and (= :yin.k/resumed (:yin.k/action record))
                                         (= (:address cell) (:yin.k/bytes-at record))
                                         (= (get-in cell [:header :yin.k/origin :dao.lease/lease])
                                            (get-in record [:yin.k/request :yin.k/report :dao.lease/lease])))
                                record))
                            (reverse (:ordered-intents folded)))
        report-rid (get-in report-intent
                           [:yin.k/request :yin.k/request-id])
        o1 (or (get-in report-intent
                       [:yin.k/request :yin.k/report :yin.k/occurrence])
               (get-in cell [:header :yin.k/origin :yin.k/occurrence]))
        l (or (get-in report-intent
                      [:yin.k/request :yin.k/report :dao.lease/lease])
              (get-in cell [:header :yin.k/origin :dao.lease/lease]))
        _ (check! (and (some? o1) (some? l))
                  "the exit's report, from the successor's origin" {})
        release-intent (some (fn [record]
                               (when (and (= :yin.k/release (:yin.k/action record))
                                          (= l (get-in record [:yin.k/request :dao.lease/lease])))
                                 record))
                             (reverse (:ordered-intents folded)))
        release-rid (get-in release-intent
                            [:yin.k/request :yin.k/request-id])
        [origin-bytes origin-fence] (or (fence-bytes state folded o1)
                                        [(:bytes config) {:address (:address config)}])]
    (assoc state
           :phase :exiting
           :restarted? true
           :restart-checkpoint {:bytes origin-bytes :address (:address origin-fence)}
           :status :yin.k/ok
           :detail {:reopened :exit :yin.k/occurrence o1}
           :occurrence o1
           :arbitration (get-in cell [:header :yin.k/arbitration
                                      :dao.stream/identity])
           :lease l
           :machine nil
           :exit (cond-> cell
                   (nil? report-intent) (dissoc :report)
                   (some? report-intent)
                   (assoc :report
                          {:request (assoc (:yin.k/request report-intent)
                                           :yin.k/bytes (:bytes cell))
                           :sent true
                           :answer (when (contains? (:acked folded)
                                                    report-rid)
                                     (get-in folded [:answers report-rid
                                                     :yin.k/answer]))}))
           :release (when (some? release-intent)
                      {:lease l
                       :request (:yin.k/request release-intent)
                       :sent true
                       :intended? true
                       :carried (contains? (:acked folded) release-rid)}))))


(defn- reopen-candidate
  "The recovery of a candidate whose journal holds no grant and no
   mint: the last unanswered proposal keeps its stable id, and a source
   begins its export afresh."
  [config folded]
  (let [state (if (and (some? (:bytes config)) (some? (:address config)))
                (step-validating (initial config))
                (assoc (bare-state config (or (:units config) lease/default-units))
                       :phase (if (:enroll folded) :reconciling :stalled)
                       :arbitration (get-in config [:arbitration :dao.stream/identity])
                       :status :yin.k/unsatisfied
                       :detail {:yin.k/reason :incomplete-preparation}))
        intent (intent-of folded :yin.k/proposal (:occurrence state))
        rid (get-in intent [:yin.k/request :yin.k/request-id])]
    (if (and (= :proposing (:phase state)) (some? intent))
      (assoc state
             :phase :proposing
             :occurrence (get intent :yin.k/occurrence)
             :arbitration (:arbitration state)
             :proposals 1
             :proposal {:id (get-in intent [:yin.k/request
                                            :dao.lease/proposal])
                        :request (:yin.k/request intent)
                        :sent true :carried (contains? (:acked folded) rid)}
             :sent-proposals #{(get-in intent [:yin.k/request
                                               :dao.lease/proposal])})
      state)))


(defn reopen
  "Recover a driver from its reopened progress journal (r3 1.10): fold
   the records and answer the state at the last boundary -- the same
   occurrence, waits and ids, the same intents to resend.  `config` is
   the one the driver was built with, its :journal the reopened handle
   over the same frames. Complete sources are rebuilt from canonical
   recovery bytes in the content store, not a supplied live machine.

   The fold decides which recovery runs: an exit whose successor is
   minted and fenced resumes the exit, from the stored bytes; a
   journaled grant (never one that restores execution) releases and
   re-proposes; a minted export reopens fenced, resending its offer;
   incomplete preparations stay non-runnable. A stalled driver can
   reconcile its uncertain append only here. Persisted clock readings
   never restore tenure."
  [config]
  (let [units (or (:units config) lease/default-units)]
    (check! (some? (:journal config)) "a progress journal" {})
    (check-common! config units)
    (let [folded (fold-journal (journal-records (:journal config)))
          mint (:mint folded)
          exit? (and (some? mint)
                     (or (nil? (:grant folded))
                         (> (:index mint) (get-in folded [:grant :index])))
                     (contains? #{:yin.k/successor :yin.k/result}
                                (:role mint))
                     (contains? (:fences folded) (:occurrence mint)))
          reconciliation (try
                           {:receipts (inbox/reconcile! (:seams (bare-state config units)) (:inbox folded))}
                           (catch #?(:cljd Object :clj Throwable :cljs :default) failure
                             (if (and (:grant folded)
                                      (= :inbox-unavailable (::inbox/refusal (ex-data failure))))
                               {:receipts (:inbox folded) :unavailable? true}
                               (throw failure))))
          receipts (:receipts reconciliation)]
      (cond-> (assoc (cond
                       (and (get-in folded [:aborted (:occurrence mint)])
                            (= :yin.k/first (:role mint)))
                       (assoc (bare-state config units) :phase :aborted :status :yin.k/ok
                              :detail {:yin.k/aborted (:occurrence mint)})

                       (and (some? mint) (not (contains? (:fences folded) (:occurrence mint)))
                            (or (= :yin.k/first (:role mint)) (nil? (:grant folded))))
                       (assoc (bare-state config units) :phase :stalled :status :yin.k/unsatisfied
                              :detail {:yin.k/reason :incomplete-preparation
                                       :yin.k/occurrence (:occurrence mint)})

                       (and exit? (not (get-in folded [:aborted (:occurrence mint)])))
                       (reopen-exit config units folded)

                       (some? (:grant folded))
                       (reopen-holder config units folded)

                       (some? mint)
                       (reopen-source config units folded)

                       :else (reopen-candidate config folded))
                     :enroll (:enroll folded)
                     :inbox {:positions (inbox/positions receipts)
                             :identities (inbox/identities receipts)
                             :program (filterv #(program-record? (:yin.k/record %)) receipts)
                             :control (filterv #(not (program-record? (:yin.k/record %))) receipts)})
        (:unavailable? reconciliation)
        (assoc :inbox-reconciliation receipts :inbox-unavailable? true)
        (:unavailable? reconciliation)
        (unsatisfied :inbox-unavailable {})))))


;; =============================================================================
;; Enrollment (r3 1.10; UCF 7.7.7's C12 identity discipline)
;; =============================================================================

(defn- enroll-attempt*
  "One bracketed enrollment step (r3 1.10; UCF 7.7.7 as amended by
   C12): enrollment carries no request identity, so an uncertain answer
   is reconciled by rereading the projection -- the driver enrolls
   again only when the derived target identity is absent, because a
   blind retry enrolls a second target.  The derived identity (the one
   the authority's next transaction would mint) is journaled with the
   intent; a committed answer journals the acknowledgment and answers
   the target.  `:enroll!` is the composition's enrollment seam (the
   authority's own enrollment, `(fn [] enrollment-answer)`)."
  [state]
  (let [cell (or (:enroll state) {})]
    (cond
      (some? (:target cell))
      (with-status state :yin.k/ok {:yin.k/enrolled (:target cell)})

      (nil? (:derived cell))
      (if-some [p (folded-projection state)]
        (let [i (ledger/target-identity (:arbitration state) (:next-t p))]
          (journal! state
                    {:yin.k/journal :yin.k/intent
                     :yin.k/action :yin.k/enroll
                     :yin.k/derived i})
          (with-status (assoc state :enroll {:derived i})
            :yin.k/ok {:yin.k/enroll :intent :yin.k/derived i}))
        (unsatisfied state :no-arbitration
                     {:dao.stream/identity (:arbitration state)}))

      :else
      (let [answer (try
                     (enroll! state)
                     (catch #?(:cljd Object :clj Throwable :cljs :default) _
                       {:yin.k/status :suspended :yin.k/reason :threw}))
            acknowledge
            (fn [state i]
              (journal! state
                        {:yin.k/journal :yin.k/ack
                         :yin.k/action :yin.k/enroll
                         :yin.k/target i})
              (with-status (assoc state :enroll {:target i})
                :yin.k/ok {:yin.k/enrolled i}))]
        (cond
          (= :committed (:yin.k/status answer))
          (acknowledge state (:yin.k/target answer))

          ;; uncertain: the derived identity's presence in a fresh fold
          ;; is the commit; its absence licenses exactly one more
          ;; attempt, never a blind retry
          :else
          (if-some [p (folded-projection state)]
            (if (contains? (:targets p) (:derived cell))
              (acknowledge state (:derived cell))
              (let [i (ledger/target-identity (:arbitration state)
                                              (:next-t p))]
                (when (not= i (:derived cell))
                  (journal! state
                            {:yin.k/journal :yin.k/intent
                             :yin.k/action :yin.k/enroll
                             :yin.k/derived i}))
                (with-status (assoc state :enroll {:derived i})
                  :yin.k/unsatisfied
                  {:yin.k/reason :enroll-uncertain
                   :yin.k/derived i})))
            (unsatisfied state :no-arbitration
                         {:dao.stream/identity
                          (:arbitration state)})))))))


;; =============================================================================
;; Abort (r3 1.9; UCF 7.7.4 as amended)
;; =============================================================================

(defn- abort*
  "Return the exporting machine to local execution (r3 1.9; UCF 7.7.4
   as amended): `attempts` are the journaled ones -- one per offer
   attempt whose intent was durable before the send, `:append` the
   outcome its append answered -- and a holder exporting a successor
   aborts only with tenure `{:now n :bound b :live true}`, the ledger
   reader's evidence and the bound.  After the lease has ended its old
   local machine is never restored.

   The terminal :aborted record is durable in the journal before local
   execution is handed back, and a holder rechecks tenure -- dao.lease's
   own `holding?`, cap and latches -- across that append: tenure that
   died under it ends the run (r3 1.6) instead of restoring the
   machine, and the cleanup release opens with the next step, after the
   record.

   Answers the state over export/abort's answer: :ok restores the
   machine (:running for a holder mid-run, :aborted for a first
   export); a refusal leaves the source fenced, the state unchanged
   but for the status."
  [state]
  (let [cell (or (:export state) (:exit state))
        _ (check! (some? cell) "an export or exit to abort" {})
        machine (or (:m cell) (:machine state))
        folded (fold-journal (journal-records (journal-of state)))
        attempts (offered-attempts folded (:occurrence cell))
        tenure (when (contains? machine :yin.k/custody)
                 (let [p (folded-projection state)
                       l (:lease state)
                       entry (get-in p [:occurrences (:occurrence state)])
                       holder (:holder state)
                       units (:units state)
                       base (fn [d]
                              (let [[unit magnitude] (first d)]
                                (* magnitude (get units unit))))
                       reading (clock state)
                       bound (when (and (map? p)
                                        (some? holder)
                                        (some? (:granted-at holder)))
                               (let [granted (base (:granted-at holder))
                                     renewed (base (or (:last-renewal-at holder) (:granted-at holder)))
                                     duration-bound (+ (max granted renewed)
                                                       (base (:dao.lease/duration (:grant holder))))]
                                 (if-some [cap (get-in holder [:grant :dao.lease/max])]
                                   (min duration-bound (+ granted (base cap)))
                                   duration-bound)))]
                   (when (and (some? bound) (lease/duration? reading)
                              (contains? units (first (keys reading))))
                     {:now (base reading)
                      :bound bound
                      :live (and (lease/holding? holder reading)
                                 (= l (:dao.lease/lease entry))
                                 (= (get-in state
                                            [:evidence :yin.k/binding
                                             :yin.k/epoch])
                                    (:yin.k/epoch entry)))})))
        r (export/abort machine (:record cell) attempts tenure)]
    (if (= :ok (:status r))
      (do
        (when-some [occurrence (:occurrence cell)]
          (journal! state {:yin.k/journal :yin.k/aborted :yin.k/occurrence occurrence}))
        (if (contains? (:machine r) :yin.k/custody)
          (if-not (lease/holding? (:holder state) (clock state))
            (let [detail {:cause :lease-bound
                          :yin.k/aborted (:occurrence cell)
                          :dao.lease/lease (:lease state)}
                  diagnostic {:yin.k/diagnostic :yin.k/run-ended
                              :yin.k/occurrence (:occurrence state)
                              :dao.lease/lease (:lease state)
                              :yin.k/end detail}]
              (append-diagnostic! state diagnostic)
              (assoc (releasing-state (-> state
                                          (assoc :machine (:machine r))
                                          (dissoc :exit :export)
                                          (update :diagnostics conj diagnostic))
                                      :yin.k/ended detail)
                     ;; the bracket's request, seeded unopened: the next
                     ;; step journals its intent and sends it
                     :release {:lease (:lease state)
                               :request (release-request (:lease state))
                               :sent false}))
            (-> state
                (assoc :machine (:machine r))
                (dissoc :exit :export)
                (assoc :phase :running)
                (with-status :yin.k/ok
                  {:yin.k/aborted (:occurrence cell)})))
          (-> state
              (assoc :machine (:machine r))
              (dissoc :exit :export)
              (assoc :phase :aborted)
              (with-status :yin.k/ok
                {:yin.k/aborted (:occurrence cell)}))))
      (with-status state (:yin.k/status r)
        (dissoc r :yin.k/status)))))


(defn- enroll*
  [state]
  (let [cell (:enroll state)]
    (if (and (:derived cell) (nil? (:target cell)))
      (if-some [projection (folded-projection state)]
        (if (contains? (:targets projection) (:derived cell))
          (do
            (journal! state {:yin.k/journal :yin.k/ack :yin.k/action :yin.k/enroll
                             :yin.k/target (:derived cell)})
            (with-status (assoc state :enroll {:target (:derived cell)})
              :yin.k/ok {:yin.k/enrolled (:derived cell)}))
          (let [derived (ledger/target-identity (:arbitration state) (:next-t projection))]
            (journal! state {:yin.k/journal :yin.k/intent :yin.k/action :yin.k/enroll
                             :yin.k/derived derived})
            (enroll-attempt* (assoc state :enroll {:derived derived}))))
        (unsatisfied state :no-arbitration {:dao.stream/identity (:arbitration state)}))
      (enroll-attempt* state))))


(defn- public-progress
  [state advance]
  (if (= :stalled (:phase state))
    state
    (try (advance state)
         (catch #?(:cljd Object :clj Throwable :cljs :default) failure
           (if (::journal-uncertain (ex-data failure))
             (stalled state)
             (throw failure))))))


(defn enroll
  "Advance enrollment unless an uncertain journal append has stalled progress."
  [state]
  (public-progress state enroll*))


(defn abort
  "Abort only uninterrupted local export, never stalled or restarted progress."
  [state]
  (public-progress state abort*))
