(ns yin.vm.ucf.authority.grant
  "Offers, grants and epoch bindings of M-next C slice C5, and the
   reclaims, epochs and reopen of slice C6 (UCF 7.7.2, 7.7.7, 7.7.8;
   docs/design/yin.vm.linker.dht.md 14.2.2 and 14.2.3).

   `offer!` admits one snapshot variant of an occurrence: it inspects
   the bytes (yin.vm.ucf.checkpoint), stores the accepted body in a
   content store, and only then commits the offer fact with its
   operation baseline, so the ledger never references missing content.
   An uninspectable body commits nothing and stores nothing.  An offer
   of a recorded variant is a replay; a known occurrence offered with
   another origin, or a variant with another baseline, is refused.

   The grant is the dao.lease judge's, composed per plan 1.3:
   `judge-config` supplies the judge's `:writer`, a ledger adapter that
   records an appended `:dao.lease/accepted` fact and its `:yin.k/bound`
   binding as one transaction; its `:reclaim`, a readiness check that
   revokes nothing; and its `:answer` hook, which grants one proposal per
   offered, unheld occurrence and refuses the losing candidates.
   `step!` runs a whole judge-step under the authority's lock, so the
   hook and the writer see one projection.  The writer, not the hook,
   enforces one live lease per occurrence.

   The reclaim adapter contract (plan 1.3).  The judge marks a due lease
   pending and calls `:reclaim`, which only reports readiness; the
   revocation is the writer's lapse transaction, which holds the
   `:dao.lease/lapsed` fact and its occurrence's epoch change
   (`:yin.k/reclaimed`) together.  Nothing claiming the revocation
   leaves the authority before that transaction commits: until then the
   projection shows the lease live at its epoch.  A non-ok writer answer
   leaves the lease pending in the judge and the projection unchanged,
   and the ledger fold refuses a lapse without its epoch change.

   `reopen!` is plan 1.5 steps 1 to 4 and 6: it opens the authority and
   reclaims every tenure the ledger shows live, cause :policy, each as
   one transaction, before it hands the authority out; nothing is
   regranted.  `rebuild-judge` is step 5: the judge's `:seen` and
   `:answered` from the ledger, its `:ledger` and `:queue` empty."
  (:require [dao.lease :as lease]
            [dao.stream :as stream]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.checkpoint :as checkpoint]
            [yin.vm.ucf.custody :as custody]
            [yin.vm.ucf.ledger :as ledger]))


(defn- reply
  ([s] {::authority/reply {:yin.k/status s}})
  ([s reason o]
   {::authority/reply (cond-> {:yin.k/status s :yin.k/reason reason}
                        (some? o) (assoc :yin.k/occurrence o))}))


;; =============================================================================
;; Offers
;; =============================================================================

(defn- offer-decision
  "Decide the offer of variant `address` with baseline `b` on `medium`
   against projection p."
  [address medium b]
  (fn [p]
    (let [o (:yin.k/occurrence b)
          known (get-in p [:occurrences o])]
      (cond
        (not (contains? #{:blocked :parked} (:yin.k/kind b)))
        (reply :refused :not-offerable nil)
        (not (custody/occurrence? o))
        (reply :refused :malformed-occurrence nil)
        (not= (:arbitration p)
              (get-in b [:yin.k/arbitration :dao.stream/identity]))
        (reply :refused :foreign-arbitration o)
        (nil? medium) (reply :refused :missing-medium o)
        (contains? (:yin.k/variants known) address)
        {::authority/reply {:yin.k/status :replayed :yin.k/occurrence o}}
        (and known (not= (:yin.k/origin b)
                         (get-in known [:yin.k/baseline :yin.k/origin])))
        (reply :refused :occurrence-conflict o)
        (and known (not= b (:yin.k/baseline known)))
        (reply :refused :variant-conflict o)
        :else
        {::authority/facts [(custody/offer o address medium b)]
         ::authority/reply {:yin.k/status :committed :yin.k/occurrence o}}))))


(defn- store!
  "Put the body's bytes in the content store; true once it holds them."
  [store address bytes]
  (try
    (contains? #{:inserted :present}
               ((:put-bytes-fn store) address bytes))
    (catch #?(:cljd Object :clj Throwable :cljs :default) _
      false)))


(defn offer!
  "Admit the offer of the body whose canonical bytes are `bytes` at
   segment address `address`, carried on `medium`.  `store` is a dao.jing
   byte-store handle (`:put-bytes-fn`), where the accepted body is put
   before the ledger references it.  Answers

     {:yin.k/status :committed :yin.k/occurrence o :dao.space/t t}
     {:yin.k/status :replayed :yin.k/occurrence o}   ; a recorded variant
     {:yin.k/status :refused :yin.k/reason r ...}
     {:yin.k/status :suspended :yin.k/reason :content-unavailable}

   with r one of :uninspectable (carrying the inspector's refusal under
   `:yin.k/inspection`), :not-offerable (a halted root), :malformed-
   occurrence, :foreign-arbitration, :missing-medium,
   :occurrence-conflict (another origin) and :variant-conflict (another
   baseline); or the authority's own :suspended and :closed answers.
   Nothing is stored unless the offer is admitted, and nothing is
   committed unless the store holds the body."
  [a store address bytes medium]
  (let [b (checkpoint/inspect address bytes)]
    (if (contains? b :yin.k/status)
      {:yin.k/status :refused :yin.k/reason :uninspectable
       :yin.k/inspection b}
      (let [decide (offer-decision address medium b)]
        (authority/locked
          a
          (fn []
            (let [p (authority/projection a)]
              (if (and p
                       (seq (::authority/facts (decide p)))
                       (not (store! store address bytes)))
                {:yin.k/status :suspended :yin.k/reason :content-unavailable}
                (authority/transition! a decide)))))))))


;; =============================================================================
;; The judge's writer: grants, lapses and refusals, each one transaction
;; =============================================================================

(def ^:private ok {:dao.stream/outcome :dao.stream/ok})

(def ^:private invalid {:dao.stream/outcome :dao.stream/invalid-value})


(def ^:private transport-error
  {:dao.stream/outcome :dao.stream/transport-error})


(def ^:private grant-terms
  [:dao.lease/duration :dao.lease/proposal :dao.lease/max])


(defn- grant-decision
  "Decide grant fact `g` of lease l on occurrence o to holder h.  The
   grant recorded for l is a replay only when its occurrence, holder and
   terms are g's."
  [g l o h]
  (fn [p]
    (let [known (get-in p [:occurrences o])
          recorded (get-in p [:leases l])]
      (cond
        (and recorded
             (= o (:yin.k/occurrence recorded))
             (= h (:dao.lease/holder recorded))
             (= (select-keys g grant-terms)
                (select-keys recorded grant-terms)))
        {::authority/reply ok}
        (or recorded
            (nil? known)
            (:yin.k/exhausted known)
            (:yin.k/quarantined known)
            (some? (:dao.lease/lease known))
            (contains? (:answered p) [h (:dao.lease/proposal g)]))
        {::authority/reply invalid}
        :else
        {::authority/facts [g (custody/bound o l h (:yin.k/epoch known))]
         ::authority/reply ok}))))


(defn- lapse-decision
  "Decide lapse fact `f` of lease l: the lapse and its occurrence's epoch
   change in one transaction.  The lapse recorded for l with the same
   cause is a replay."
  [f l]
  (fn [p]
    (let [recorded (get-in p [:leases l])
          o (:yin.k/occurrence recorded)]
      (cond
        (and recorded
             (= (:dao.lease/cause f) (:dao.lease/cause recorded)))
        {::authority/reply ok}
        (or (nil? recorded)
            (contains? recorded :dao.lease/cause)
            (not= l (get-in p [:occurrences o :dao.lease/lease])))
        {::authority/reply invalid}
        :else
        {::authority/facts
         [f (custody/reclaimed o l (ledger/next-epoch
                                     (:max-epoch p)
                                     (:yin.k/epoch recorded)))]
         ::authority/reply ok}))))


(defn- refusal-decision
  "Decide the refusal of `proposer`'s proposal pid: the plain lease fact
   `f` and its `:yin.k/refused` fact in one transaction."
  [f proposer pid]
  (fn [p]
    (case (get-in p [:answered [proposer pid]])
      nil {::authority/facts [f (custody/refused proposer pid)]
           ::authority/reply ok}
      :dao.lease/rejected {::authority/reply ok}
      {::authority/reply invalid})))


(defn- decision
  "The decision for appended lease fact `f`, or nil when the authority
   cannot record it."
  [f]
  (case (:dao.lease/status f)
    :dao.lease/accepted
    (when-let [o (custody/subject-occurrence (:dao.lease/subject f))]
      (grant-decision f (:dao.lease/lease f) o (:dao.lease/holder f)))
    :dao.lease/lapsed (lapse-decision f (:dao.lease/lease f))
    :dao.lease/rejected
    (when (some? (:yin.k/proposer f))
      (refusal-decision (dissoc f :yin.k/proposer) (:yin.k/proposer f)
                        (:dao.lease/proposal f)))
    nil))


(defn- append-fact!
  [a f]
  (let [status (:dao.lease/status f)
        order (get ledger/attribute-order status)
        ;; The hook's refusal names its proposer outside the lease fact.
        lf (if (= :dao.lease/rejected status) (dissoc f :yin.k/proposer) f)
        decide (when (and order
                          (not (lease/defective? lf))
                          (every? (set order) (keys lf)))
                 (decision f))]
    (if-not decide
      invalid
      (let [r (authority/transition! a decide)]
        (if (contains? r :dao.stream/outcome)
          (select-keys r [:dao.stream/outcome])
          transport-error)))))


(defn writer
  "The judge's `:writer` over authority `a`.  Each lease fact it can
   record commits as one transaction and answers ok:
     a `:dao.lease/accepted` fact on an offered, unexhausted,
     unquarantined occurrence with no live lease, answering no
     answered proposal, with its `:yin.k/bound` binding at the
     occurrence's current epoch;
     a `:dao.lease/lapsed` fact of a live lease, with its occurrence's
     `:yin.k/reclaimed` epoch change;
     a `:dao.lease/rejected` fact the hook hands over with its
     `:yin.k/proposer`, for a proposal that proposer has not had
     answered: the key is stripped, and the plain lease fact commits
     with a `:yin.k/refused` fact naming the proposer.
   A fact already recorded with the same terms answers ok and commits
   nothing.  Any other fact, or one outside the published attribute
   order, answers invalid-value; a poisoned or closed authority, or a
   transaction past a bound, answers transport-error.  No outcome
   carries a key beyond `:dao.stream/outcome`."
  [a]
  (reify stream/IDaoStreamWriter
    (append!
      [_ fact]
      (if (map? fact) (append-fact! a fact) invalid))))


(defn ready?
  "The judge's `:reclaim` readiness: true only while the authority is
   open and unpoisoned.  It revokes nothing; the lapse transaction is
   the revocation (plan 1.3)."
  [a]
  (some? (authority/projection a)))


(defn- answer
  "The judge's `:answer` hook: grant the first drained proposal for each
   offered occurrence with no live lease, to its proposer, for
   `duration`, and refuse the proposals for an occurrence that is held,
   exhausted, quarantined or granted in this pass.  A proposal its
   proposer already had answered, one with no author, and one for an
   occurrence never offered get no answer."
  [a duration]
  (fn [judge drained]
    (let [p (authority/projection a)]
      (dissoc
        (reduce
          (fn [acc {:keys [fact author]}]
            (let [s (:dao.lease/subject fact)
                  pid (:dao.lease/proposal fact)
                  o (custody/subject-occurrence s)
                  known (get-in p [:occurrences o])]
              (cond
                (or (nil? known)
                    (nil? author)
                    (contains? (:answered judge) [author pid]))
                acc
                (or (:yin.k/exhausted known)
                    (:yin.k/quarantined known)
                    (some? (:dao.lease/lease known))
                    (contains? (:taken acc) o))
                (update acc :refusals conj
                        {:proposer author
                         :refusal (assoc (lease/refusal pid)
                                         :yin.k/proposer author)})
                :else
                (-> acc
                    (update :taken conj o)
                    (update :grants conj
                            (lease/grant (lease/mint-lease-id) s author
                                         duration
                                         {:dao.lease/proposal pid}))))))
          {:taken #{} :grants [] :refusals []}
          drained)
        :taken))))


(defn judge-config
  "The entries authority `a` supplies to dao.lease/initial-judge: its
   `:writer`, `:reclaim` and `:answer`, granting for `duration`.  The
   composition adds `:resolver` and `:self`.  Throws, at assembly, on a
   duration dao.lease would refuse."
  [a duration]
  (when-not (lease/duration? duration)
    (throw (ex-info (str "a grant duration is a single-entry"
                         " {unit positive-integer} map")
                    {:duration duration})))
  {:writer (writer a)
   :reclaim (fn [_subject] (ready? a))
   :answer (answer a duration)})


(defn step!
  "Run one dao.lease judge-step under the authority's lock and answer the
   next judge."
  [a judge]
  (authority/locked a #(lease/judge-step judge)))


;; =============================================================================
;; Reopen (plan 1.5)
;; =============================================================================

(defn- live-leases
  "The leases the projection shows live, in the order they were granted."
  [p]
  (->> (vals (:occurrences p))
       (keep :dao.lease/lease)
       (sort-by #(get-in p [:leases % :dao.space/t]))
       vec))


(defn reopen!
  "Open the authority over journal `backend` (authority/open! with
   `opts`) and reclaim every tenure its ledger shows live, cause
   :policy, each as one lapse transaction that raises the epoch, before
   the authority is handed out.  Nothing is regranted, and
   dao.lease/restart is not used.  Answers authority/open!'s answer
   with `:yin.k/reclaimed-leases`, the leases reclaimed in order; or, with the
   authority closed, `{:yin.k/status :refused :yin.k/defect :unreclaimed
   :dao.lease/lease l}` for the first reclaim that did not commit."
  [backend opts]
  (let [r (authority/open! backend opts)
        a (::authority/authority r)]
    (if-not a
      r
      (let [w (writer a)
            live (live-leases (authority/projection a))
            failed (some #(when-not (= ok (stream/append!
                                            w (lease/lapsed % :policy)))
                            %)
                         live)]
        (if failed
          (do (authority/close! a)
              {:yin.k/status :refused :yin.k/defect :unreclaimed
               :dao.lease/lease failed})
          (assoc r :yin.k/reclaimed-leases live))))))


(defn rebuild-judge
  "Plan 1.5 step 5: `judge` with `:seen` from every grant and lapse
   authority `a`'s ledger records, `:answered` from every recorded grant
   and refusal keyed `[proposer proposal-id]` (a refusal's proposer
   from its `:yin.k/refused` fact), and an empty `:ledger` and `:queue`.
   Call it after reopen!, which leaves no tenure live.  Queued grants
   are discarded and not reported (contrast dao.lease/restart's
   `:discarded-queue`): they were authored against a projection that
   reopen has replaced, a grant that never committed established
   nothing, and the hook re-decides from the re-drained proposals."
  [a judge]
  (let [p (authority/projection a)]
    (assoc judge
           :seen (into {}
                       (map (fn [[l entry]]
                              [l (cond-> #{:dao.lease/accepted}
                                   (contains? entry :dao.lease/cause)
                                   (conj :dao.lease/lapsed))]))
                       (:leases p))
           :answered (:answered p)
           :ledger {}
           :queue [])))
