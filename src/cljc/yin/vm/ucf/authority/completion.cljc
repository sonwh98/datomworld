(ns yin.vm.ucf.authority.completion
  "Completion of an occurrence, M-next C slice C8 (UCF 7.7.5 *Durable
   completion*, 7.7.6, 7.7.8 *Exhaustion*; docs/design/yin.vm.linker.dht.md
   14.2.3 step 6).

   The holder's exit is: append the successor to its carrier, report
   `:yin.k/resumed` naming the successor's address, then release.
   `report!` admits the report: the reporting author must be the
   lease's holder, the lease live, its occurrence not quarantined, and
   the successor's bytes must pass the inspector (yin.vm.ucf.checkpoint:
   the address, version 1, the root role) with an origin naming this
   occurrence and lease.  A continuation must also name the same
   arbitration identity, be an occurrence the ledger has never seen, and
   carry a counter that continues the predecessor's (`:yin.k/next-op-seq`
   not below it).  A halted result carries none of those.  The authority
   records the report, with the successor occurrence it verified for a
   continuation; the bytes are not stored.

   Completion is the grantor's transition, not the report.  When the
   judge's writer records a `:release` or `:policy` lapse of a reported lease
   (yin.vm.ucf.authority.grant), `closure` adds the closure and its one
   edge to that lapse's transaction, between the lapse and its epoch
   change, so no reader sees a closure without its edge or its lapse.
   The edge goes to the successor occurrence, or, for a halted result,
   is a terminal edge to the result's address.  A release or policy lapse with no
   report, a reclaim of any other cause, an exhausting reclaim (a lease
   bound at the epoch bound), a quarantined occurrence and a successor
   the ledger has meanwhile seen all leave a plain reclaim: the
   occurrence returns to offered, or stays exhausted or quarantined.  A
   closed occurrence never grants again.

   The successors form one acyclic chain: one edge per predecessor, and
   one predecessor per successor, because an edge's successor is an
   occurrence the ledger has never seen; a result is no occurrence, so
   the chain ends there.  `offer-refusal` is the offer precondition for
   a body with an origin: it is admitted only as its predecessor's
   recorded successor in this ledger, at the reported address, after the
   closure.  Before closure it is `:awaiting-completion`; a body that can
   no longer become that successor, or whose predecessor this ledger
   never saw, is an `:orphan`, refused, so never offered and never
   grantable."
  (:require [dao.jing.cbor :as cbor]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.checkpoint :as checkpoint]
            [yin.vm.ucf.custody :as custody]
            [yin.vm.ucf.ledger :as ledger]))


;; =============================================================================
;; Facts
;; =============================================================================

(defn resumed
  "The holder's report that occurrence `o`, under lease `l`, became the
   successor whose body is at segment address `address` (7.7.2).  The
   authority records it with `:yin.k/successor`, the occurrence it
   verified."
  [o l address]
  {:yin.k/custody :yin.k/resumed
   :yin.k/occurrence o
   :dao.lease/lease l
   :yin.k/result address})


(defn completed
  "The closure of occurrence `o` by release or policy reclaim of lease `l`."
  [o l]
  {:yin.k/custody :yin.k/completed
   :yin.k/occurrence o
   :dao.lease/lease l})


(defn succeeded
  "The edge from closed occurrence `o` to its successor `s`."
  [o s]
  {:yin.k/custody :yin.k/succeeded
   :yin.k/occurrence o
   :yin.k/successor s})


(defn terminated
  "The terminal edge from closed occurrence `o` to the address of its
   halted result."
  [o address]
  {:yin.k/custody :yin.k/succeeded
   :yin.k/occurrence o
   :yin.k/result address})


;; =============================================================================
;; The report
;; =============================================================================

(defn- successor-defect
  "Why baseline `b` is not a successor of occurrence `o` under lease `l`
   against projection p, or nil.  A halted result is checked only for
   its origin: it carries no occurrence, arbitration or counter."
  [p o l b]
  (let [s (:yin.k/occurrence b)]
    (cond
      (not (and (= o (get-in b [:yin.k/origin :yin.k/occurrence]))
                (= l (get-in b [:yin.k/origin :dao.lease/lease]))))
      :wrong-origin
      (= :halted (:yin.k/kind b)) nil
      (not= (:arbitration p)
            (get-in b [:yin.k/arbitration :dao.stream/identity]))
      :foreign-arbitration
      (ledger/successor-seen? p s) :seen-occurrence
      (neg? (cbor/num-compare
              (:yin.k/next-op-seq b)
              (get-in p [:occurrences o :yin.k/baseline :yin.k/next-op-seq])))
      :counter-regression
      :else nil)))


(defn- refused
  [reason]
  {::authority/reply {:yin.k/status :refused :yin.k/reason reason}})


(defn- report-decision
  [author report b]
  (fn [p]
    (let [o (:yin.k/occurrence report)
          l (:dao.lease/lease report)
          address (:yin.k/result report)
          entry (get-in p [:leases l])]
      (cond
        (nil? entry) (refused :unknown-lease)
        (not= o (:yin.k/occurrence entry)) (refused :wrong-occurrence)
        (not= author (:dao.lease/holder entry)) (refused :not-holder)
        (= address (:yin.k/result entry))
        {::authority/reply {:yin.k/status :replayed}}
        (contains? entry :dao.lease/cause) (refused :ended-lease)
        (contains? entry :yin.k/result) (refused :report-conflict)
        (get-in p [:occurrences o :yin.k/quarantined]) (refused :quarantined)
        :else
        (if-let [d (successor-defect p o l b)]
          (refused d)
          {::authority/facts [(cond-> report
                                (not= :halted (:yin.k/kind b))
                                (assoc :yin.k/successor
                                       (:yin.k/occurrence b)))]
           ::authority/reply {:yin.k/status :committed}})))))


(defn report!
  "Admit holder `author`'s report `report` (a `resumed` fact) of the
   successor whose canonical bytes are `bytes`.  Answers

     {:yin.k/status :committed :dao.space/t t}
     {:yin.k/status :replayed}                 ; the recorded report
     {:yin.k/status :refused :yin.k/reason r}

   with r one of :malformed-report, :uninspectable (carrying the
   inspector's refusal under `:yin.k/inspection`), :unknown-lease,
   :wrong-occurrence, :not-holder, :ended-lease, :report-conflict
   (another report for the lease), :quarantined, :wrong-origin,
   :foreign-arbitration, :seen-occurrence and :counter-regression (the
   last three never for a halted result); or the authority's own
   :suspended and :closed answers.  A refused report writes nothing."
  [a author report bytes]
  (if-not (and (map? report)
               (= #{:yin.k/custody :yin.k/occurrence :dao.lease/lease
                    :yin.k/result}
                  (set (keys report)))
               (= :yin.k/resumed (:yin.k/custody report))
               (custody/occurrence? (:yin.k/occurrence report))
               (some? (:dao.lease/lease report)))
    {:yin.k/status :refused :yin.k/reason :malformed-report}
    (let [b (checkpoint/inspect (:yin.k/result report) bytes)]
      (if (contains? b :yin.k/status)
        {:yin.k/status :refused :yin.k/reason :uninspectable
         :yin.k/inspection b}
        (authority/transition! a (report-decision author report b))))))


;; =============================================================================
;; Completion, in the release or policy lapse's transaction
;; =============================================================================

(defn closure
  "The facts that complete the occurrence of lapse fact `f` against
   projection p: its closure and its one edge, when f is a `:release` or `:policy`
   lapse of a live, reported lease on an unquarantined occurrence whose
   reclaim does not exhaust it, and whose successor, if the report named
   a continuation, the ledger has not seen; else none.  The edge goes to
   the successor occurrence, or for a halted result is the terminal edge
   to its address.  They belong between the lapse and its epoch change."
  [p f]
  (let [l (:dao.lease/lease f)
        entry (get-in p [:leases l])
        o (:yin.k/occurrence entry)
        s (:yin.k/successor entry)]
    (if (and (contains? #{:release :policy} (:dao.lease/cause f))
             (contains? entry :yin.k/result)
             (not (contains? entry :dao.lease/cause))
             (= l (get-in p [:occurrences o :dao.lease/lease]))
             (not (get-in p [:occurrences o :yin.k/closed]))
             (not (get-in p [:occurrences o :yin.k/quarantined]))
             (not= (:max-epoch p) (:yin.k/epoch entry))
             (not (and (some? s) (ledger/successor-seen? p s))))
      [(completed o l)
       (if (some? s) (succeeded o s) (terminated o (:yin.k/result entry)))]
      [])))


;; =============================================================================
;; Successor offers
;; =============================================================================

(defn offer-refusal
  "Why the first offer of variant `address` with baseline `b` may not be
   admitted against projection p, or nil.  A body with no origin, a
   first export, is unconstrained.  A body with an origin must be the
   successor its predecessor's closure recorded in this ledger, under
   the closing lease, at the reported address.  :awaiting-completion
   while the origin's lease is the live one; :orphan otherwise,
   including a predecessor this ledger never saw (7.7.8: a successor's
   arbitration is its predecessor's, unchanged)."
  [p address b]
  (let [o (get-in b [:yin.k/origin :yin.k/occurrence])
        l (get-in b [:yin.k/origin :dao.lease/lease])
        pred (get-in p [:occurrences o])
        closed (:yin.k/closed pred)]
    (cond
      (not (contains? b :yin.k/origin)) nil
      (nil? pred) :orphan
      (nil? closed)
      (if (= l (:dao.lease/lease pred)) :awaiting-completion :orphan)
      (and (= l (:dao.lease/lease closed))
           (= (:yin.k/occurrence b) (:yin.k/successor closed))
           (= address (get-in p [:leases l :yin.k/result])))
      nil
      :else :orphan)))
