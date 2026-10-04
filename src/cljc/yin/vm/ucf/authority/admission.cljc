(ns yin.vm.ucf.authority.admission
  "Admission and result delivery of M-next C slice C7 (UCF 7.7.8, 7.9;
   docs/design/yin.vm.linker.dht.md 14.2.3 step 4 and 5).

   `admit!` is the one admission entry point: a fenced envelope that
   arrived at enrolled target i, attributed to `author` by the
   composition's resolver, is admitted by one authority transition.  A
   well-formed envelope meets the checks in this order, the first to
   fail deciding:

     1. authority  readable, and the occurrence the lease is bound to
                   neither exhausted nor quarantined, else :suspended
     2. binding    the lease has a binding and the author is its
                   holder, else a diagnostic (:unbound-lease,
                   :wrong-author)
     3. tenure     the lease is the occurrence's active lease at the
                   envelope's epoch, else :stale, even over a record
     4. scope      the op id names the bound occurrence, else a
                   diagnostic (:foreign-op-id); inherited ids are slice
                   C9's and fail closed here
     5. dedup      one namespace per authority, keyed by op id alone:
                   equal intent :replayed, other intent :intent-conflict
                   (which commits the occurrence's quarantine), no record
                   :committed (the effect, its result, its dedup record
                   and its fenced stamp in one transaction)

   The answer is one map on the closed `:yin.k/admission` family
   (`admissions`), a defective-envelope diagnostic, or `::unenrolled`
   for a boundary the authority never enrolled, which runs no
   admission.  A defective envelope commits nothing; exactly one
   diagnostic is appended to the composition's diagnostic stream, and
   the append's own result is returned beside it, so a failed append is
   data and admits nothing.

   Result delivery: every fenced commit yields its `:committed` outcome
   on the authority's outcome projection (`outcome-reader`), a reader-
   only stream derived from the ledger in t order, with cursors stable
   across reopen, so a driver holding a kept cursor needs no reply
   route after a crash.  `:replayed`, `:stale`, `:intent-conflict` and
   `:suspended` are direct replies only; a lost one is recovered by
   retrying.

   Admission, grants and reclaims share the authority lock
   (`authority/transition!`), so an admission and a reclaim serialize."
  (:require [dao.jing.cbor :as cbor]
            [dao.stream :as stream]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.custody :as custody]
            [yin.vm.ucf.ledger :as ledger]))


(def admissions
  "The closed `:yin.k/admission` family (UCF 7.9)."
  #{:committed :replayed :stale :intent-conflict :suspended})


(def defects
  "The closed defective-envelope set (UCF 7.7.8)."
  #{:malformed :unbound-lease :wrong-author :foreign-op-id})


(def outcomes-type
  "The transport type of the outcome projection."
  ::outcomes)


(def max-seq
  "2^52-2, the largest assignable operation sequence (UCF 7.7.8)."
  (dec ledger/max-exact))


(defn- arbitration
  "The authority's journal identity."
  [a]
  (:dao.stream/identity (stream/descriptor (:journal a))))


(defn- outcome
  "The admission outcome `k` answering `envelope`, with `more`.  Only a
   member of `admissions` can be made."
  [k envelope more]
  (when-not (contains? admissions k)
    (throw (ex-info "not an admission outcome" {:yin.k/admission k})))
  (merge {:yin.k/admission k
          :yin.k/op-id (get envelope :yin.k/op-id)
          :yin.k/incarnation (get envelope :yin.k/incarnation)}
         more))


(defn- suspended
  [envelope arb]
  (outcome :suspended envelope
           {:yin.k/arbitration {:dao.stream/identity arb}}))


;; =============================================================================
;; Defective envelopes
;; =============================================================================

(def ^:private envelope-keys
  [:yin.k/envelope :yin.k/incarnation :yin.k/epoch :yin.k/op-id
   :yin.k/value])


(defn- well-formed?
  [e]
  (and (map? e)
       (every? #(contains? e %) envelope-keys)
       (= :yin.k/fenced-v1 (get e :yin.k/envelope))
       (some? (get e :yin.k/incarnation))
       (custody/exact? (get e :yin.k/epoch))
       (ledger/op-id? (get e :yin.k/op-id))
       (not (pos? (cbor/num-compare (get-in e [:yin.k/op-id :yin.k/seq])
                                    max-seq)))))


(defn- intent
  "The canonical intent digest of the envelope's append to i, or nil
   when it cannot be canonically encoded."
  [i e]
  (try
    (ledger/intent i (get e :yin.k/value))
    (catch #?(:cljd Object :clj Throwable :cljs :default) _
      nil)))


(defn- diagnose!
  "Append the one diagnostic for `envelope` with defect `d` and answer it
   beside the append's result."
  [diagnostics i author envelope d]
  (let [m (cond-> {:yin.k/diagnostic :yin.k/defective-envelope
                   :yin.k/defect d
                   :yin.k/target i}
            (some? author) (assoc :yin.k/author author)
            :always (assoc :yin.k/claimed
                           (if (map? envelope)
                             (select-keys envelope [:yin.k/incarnation
                                                    :yin.k/epoch
                                                    :yin.k/op-id])
                             {})))
        r (try
            (stream/append! diagnostics m)
            (catch #?(:cljd Object :clj Throwable :cljs :default) _
              {:dao.stream/outcome :dao.stream/transport-error}))]
    {::diagnostic m ::appended r}))


;; =============================================================================
;; The decision
;; =============================================================================

(defn- reply
  [r]
  {::authority/reply r})


(defn- decide
  "The admission of well-formed envelope `e` with intent h at target i
   from `author`, against projection p."
  [i author e h]
  (fn [p]
    (let [target (get-in p [:targets i])
          l (get e :yin.k/incarnation)
          bound (get-in p [:leases l])
          o (:yin.k/occurrence bound)
          occ (get-in p [:occurrences o])
          op (get e :yin.k/op-id)
          recorded (get-in p [:admitted (cbor/content-key op)])]
      (cond
        (nil? target) (reply {::unenrolled i})
        (or (:yin.k/exhausted occ) (:yin.k/quarantined occ))
        (reply (suspended e (:arbitration p)))
        (nil? (:yin.k/epoch bound)) (reply {::defect :unbound-lease})
        (not= author (:dao.lease/holder bound))
        (reply {::defect :wrong-author})
        (not (and (= l (:dao.lease/lease occ))
                  (= (get e :yin.k/epoch) (:yin.k/epoch occ))))
        (reply (outcome :stale e
                        (cond-> {:yin.k/observed-epoch (:yin.k/epoch occ)}
                          (some? (:dao.lease/lease occ))
                          (assoc :yin.k/observed-lease
                                 (:dao.lease/lease occ)))))
        (not= o (:yin.k/occurrence op)) (reply {::defect :foreign-op-id})
        (and recorded (= h (:yin.k/intent recorded)))
        (reply (outcome :replayed e
                        {:yin.k/effect-result (:yin.k/result recorded)}))
        recorded
        {::authority/facts [{:yin.k/custody :yin.k/quarantined
                             :yin.k/occurrence o
                             :yin.k/op-id op}]
         ::authority/reply (outcome :intent-conflict e
                                    {:yin.k/recorded-intent
                                     (:yin.k/intent recorded)
                                     :yin.k/observed-intent h})}
        :else
        (let [r (if (:closed? target) ledger/closed-result ledger/ok-result)]
          {::authority/facts [(cond-> {:yin.k/custody :yin.k/admitted
                                       :yin.k/target i
                                       :yin.k/op-id op
                                       :yin.k/intent h}
                                (not (:closed? target))
                                (assoc :yin.k/value (get e :yin.k/value))
                                :always (assoc :yin.k/result r))
                              {:yin.k/custody :yin.k/fenced
                               :yin.k/op-id op
                               :yin.k/incarnation l
                               :yin.k/epoch (get e :yin.k/epoch)}]
           ::authority/reply (outcome :committed e
                                      {:yin.k/effect-result r})})))))


(defn admit!
  "Admit fenced envelope `envelope`, which arrived at target `i` and was
   attributed to `author` (nil when unattributed), against authority
   `a`; `diagnostics` is the composition's diagnostic stream writer.
   Answers one of

     {:yin.k/admission k :yin.k/op-id id :yin.k/incarnation l ...}
     {::diagnostic d ::appended r}   ; defective: nothing committed
     {::unenrolled i}                ; no admission at this boundary

   with k in `admissions` and its keys as UCF 7.9 lists them: intents
   are the recorded and observed canonical intent digests, and
   `:yin.k/arbitration` is `{:dao.stream/identity arb}`.  A poisoned or
   closed authority, or a transaction past a bound, answers
   :suspended."
  [a i author envelope diagnostics]
  (let [h (when (well-formed? envelope) (intent i envelope))]
    (if-not h
      (diagnose! diagnostics i author envelope :malformed)
      (let [r (authority/transition! a (decide i author envelope h))]
        (cond
          (contains? r :yin.k/admission) (dissoc r :dao.space/t)
          (contains? r ::defect)
          (diagnose! diagnostics i author envelope (::defect r))
          (contains? r ::unenrolled) r
          :else (suspended envelope (arbitration a)))))))


;; =============================================================================
;; The outcome projection
;; =============================================================================

(defn- result
  [outcome]
  {:dao.stream/outcome outcome})


(defn- committed
  "The `:committed` outcome of the fenced admission of `op` in p."
  [p op]
  (let [recorded (get-in p [:admitted (cbor/content-key op)])]
    {:yin.k/admission :committed
     :yin.k/op-id op
     :yin.k/incarnation (:yin.k/incarnation recorded)
     :yin.k/effect-result (:yin.k/result recorded)}))


(deftype OutcomeReader
  [authority identity]

  stream/IDaoStreamDescriptor

  (descriptor
    [_]
    {:dao.stream/outcome :dao.stream/ok
     :dao.stream/descriptor {:dao.stream/type outcomes-type
                             :dao.stream/identity identity}
     :dao.stream/identity identity})


  stream/IDaoStreamReader

  (cursor
    [_ anchor]
    (if-let [p (authority/projection authority)]
      (case anchor
        :dao.stream/oldest
        (assoc (result :dao.stream/ok)
               :dao.stream/cursor {::outcomes identity ::position 0})
        :dao.stream/newest
        (assoc (result :dao.stream/ok)
               :dao.stream/cursor {::outcomes identity
                                   ::position (count (:outcomes p))})
        (result :dao.stream/invalid-anchor))
      (result :dao.stream/transport-error)))


  (next
    [_ cursor-value]
    (if-let [p (authority/projection authority)]
      (let [ops (get p :outcomes [])
            pos (when (map? cursor-value) (::position cursor-value))
            tail (count ops)]
        (cond
          (not (and (map? cursor-value)
                    (contains? cursor-value ::outcomes)))
          (result :dao.stream/invalid-cursor)
          (not= identity (::outcomes cursor-value))
          (result :dao.stream/cursor-mismatch)
          (not (and (integer? pos) (<= 0 pos tail)))
          (result :dao.stream/invalid-cursor)
          (< pos tail)
          {:dao.stream/outcome :dao.stream/ok
           :dao.stream/value (committed p (nth ops pos))
           :dao.stream/cursor {::outcomes identity ::position (inc pos)}}
          :else (result :dao.stream/blocked)))
      (result :dao.stream/transport-error))))


(defn outcome-reader
  "The outcome projection of authority `a`: the `:committed` outcome of
   every fenced admission its ledger records, in ledger t order, at
   dense positions stable across reopen; blocked at the tail, never
   ended.  Its identity is the ledger's identity with \"/outcomes\".  It
   has no writer surface, and a poisoned or closed authority serves
   nothing from it (`:dao.stream/transport-error`)."
  [a]
  (OutcomeReader. a (str (arbitration a) "/outcomes")))
