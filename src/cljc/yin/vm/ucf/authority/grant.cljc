(ns yin.vm.ucf.authority.grant
  "Offers, grants and epoch bindings of M-next C slice C5 (UCF 7.7.2,
   7.7.8; docs/design/yin.vm.linker.dht.md 14.2.2 and 14.2.3).

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
   offered, unheld occurrence.  `step!` runs a whole judge-step under
   the authority's lock, so the hook and the writer see one projection.
   The writer, not the hook, enforces one live lease per occurrence.
   Lapses, epoch increments and reopen reconstruction are slice C6's;
   until then the writer refuses every fact but a grant, so a due
   reclaim stays pending in the judge."
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
;; The judge's writer: a grant and its binding in one transaction
;; =============================================================================

(def ^:private ok {:dao.stream/outcome :dao.stream/ok})

(def ^:private invalid {:dao.stream/outcome :dao.stream/invalid-value})


(def ^:private transport-error
  {:dao.stream/outcome :dao.stream/transport-error})


(defn- grant-decision
  "Decide grant fact `g` of lease l on occurrence o to holder h."
  [g l o h]
  (fn [p]
    (let [known (get-in p [:occurrences o])
          recorded (get-in p [:leases l])]
      (cond
        (and recorded
             (= o (:yin.k/occurrence recorded))
             (= h (:dao.lease/holder recorded)))
        {::authority/reply ok}
        (or recorded (nil? known) (some? (:dao.lease/lease known)))
        {::authority/reply invalid}
        :else
        {::authority/facts [g (custody/bound o l h (:yin.k/epoch known))]
         ::authority/reply ok}))))


(defn- append-grant!
  [a g]
  (let [l (:dao.lease/lease g)
        o (custody/subject-occurrence (:dao.lease/subject g))
        h (:dao.lease/holder g)]
    (if-not (and (= :dao.lease/accepted (:dao.lease/status g))
                 (not (lease/defective? g))
                 (some? o)
                 (every? (set (get ledger/attribute-order
                                   :dao.lease/accepted))
                         (keys g)))
      invalid
      (let [r (authority/transition! a (grant-decision g l o h))]
        (if (contains? r :dao.stream/outcome)
          (select-keys r [:dao.stream/outcome])
          transport-error)))))


(defn writer
  "The judge's `:writer` over authority `a`.  An appended
   `:dao.lease/accepted` fact on an offered occurrence with no live
   lease commits with its `:yin.k/bound` binding, at the occurrence's
   current epoch, in one transaction and answers ok.  The grant already
   recorded for that lease and holder answers ok and commits nothing.
   Any other fact, a grant on a held or never-offered occurrence, or a
   fact outside the published attribute order answers invalid-value; a
   poisoned or closed authority answers transport-error.  No outcome
   carries a key beyond `:dao.stream/outcome`."
  [a]
  (reify stream/IDaoStreamWriter
    (append!
      [_ fact]
      (if (map? fact) (append-grant! a fact) invalid))))


(defn ready?
  "The judge's `:reclaim` readiness: true only while the authority is
   open and unpoisoned.  It revokes nothing; the lapse transaction is
   the revocation (plan 1.3, slice C6)."
  [a]
  (some? (authority/projection a)))


(defn- answer
  "The judge's `:answer` hook: grant the first drained proposal for each
   offered occurrence with no live lease, to its proposer, for
   `duration`.  It owes no refusal; a losing candidate learns from the
   ledger that another holder is bound."
  [a duration]
  (fn [_judge drained]
    (let [p (authority/projection a)]
      {:grants
       (:grants
         (reduce
           (fn [acc {:keys [fact author]}]
             (let [s (:dao.lease/subject fact)
                   o (custody/subject-occurrence s)
                   known (get-in p [:occurrences o])]
               (if (and known
                        (nil? (:dao.lease/lease known))
                        (some? author)
                        (not (contains? (:taken acc) o)))
                 (-> acc
                     (update :taken conj o)
                     (update :grants conj
                             (lease/grant (lease/mint-lease-id) s author
                                          duration
                                          {:dao.lease/proposal
                                           (:dao.lease/proposal fact)})))
                 acc)))
           {:taken #{} :grants []}
           drained))})))


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
