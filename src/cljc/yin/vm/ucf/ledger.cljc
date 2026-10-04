(ns yin.vm.ucf.ledger
  "The arbitration ledger of M-next C slice C3: the pure fold of an
   authority's transaction records into its projection, and the
   deterministic datoms an authority commits.

   A ledger is the local stream of a dao.space transactor over a
   dao.stream.journal: the record at journal position p is
   `{:dao.space/transaction {:t p :datoms [[e a v p 1] ...]}}`, so
   transaction time equals position and every datom is an assertion.
   The ledger is add-only.

   Facts are entities.  Each fact is one entity whose id comes from a
   counter derived from the ledger (one above the largest id seen, from
   dao.datom/first-user-id), and whose datoms follow `attribute-order`
   for its kind (`fact-kind`), an absent attribute skipped.  Facts
   keep their decision order within a transaction.  So one decision
   sequence over one journal identity has the same bytes on every host.

   The kinds of this slice, all authored by the authority:
     :yin.k/enrolled       a target, minted by this transaction
     :yin.k/target-closed  the end of a target's stream
     :yin.k/admitted       one effect's dedup record with its result,
                           and the effect itself when the result is ok
   and of slice C5 (yin.vm.ucf.custody):
     :yin.k/offered        an admitted offer of one snapshot variant
     :dao.lease/accepted   a lease grant on one offered occurrence
     :yin.k/bound          its epoch binding, in the grant's transaction
   and of slice C6:
     :dao.lease/lapsed     the reclaim of a live lease
     :yin.k/reclaimed      its epoch change, in the lapse's transaction:
                           one above the binding's epoch, or the same
                           epoch at the bound, which exhausts the
                           occurrence (UCF 7.7.8)
     :dao.lease/rejected   a refusal, the lease fact unchanged
     :yin.k/refused        the proposer it refused, in the refusal's
                           transaction

   The projection is plain data:

     {:arbitration a      ; the ledger's stream identity
      :max-epoch   m      ; the epoch bound, 2^52-1 unless lowered
      :next-t      t      ; the next transaction time, the record count
      :next-e      e      ; the next entity id
      :targets     {i {:closed? b :values [v ...]}}
      :admitted    {(cbor/content-key op-id)
                    {:yin.k/target i :yin.k/intent h :yin.k/result r}}
      :occurrences {o {:yin.k/baseline b :yin.k/variants #{address}
                       :yin.k/epoch e :dao.lease/lease l-or-nil
                       :yin.k/exhausted true}}     ; once exhausted
      :leases      {l {:yin.k/occurrence o :dao.lease/holder h
                       :yin.k/epoch e :dao.space/t t
                       :dao.lease/duration d     ; the grant's terms:
                       :dao.lease/proposal p     ; each when it has one
                       :dao.lease/max m
                       :dao.lease/cause c}}      ; once lapsed
      :answered    {[proposer proposal-id] status}}

   `:answered` holds every recorded grant (keyed by its holder) and
   refusal (by the proposer its `:yin.k/refused` fact names) that
   answers a proposal, so a dao.lease judge can be rebuilt from the
   ledger.

   An occurrence id is a UUID string in one spelling, so it keys the
   map directly and compares by its canonical bytes.

   `fold-record` answers the next projection, or `{::defect d}` when the
   record is not one this ledger can hold; nothing here throws."
  (:require [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.lease :as lease]
            [yin.vm.ucf.custody :as custody]))


(def max-exact
  "2^52-1, the bound on every ledger counter: transaction time, entity
   ids and target positions.  Nothing wraps.  Every transaction and
   every effect allocates an entity id above both its t and its target
   position, so bounding entity ids bounds the other two."
  4503599627370495)


(def attribute-order
  "The published attribute order of each fact kind."
  {:yin.k/enrolled [:yin.k/custody :yin.k/target :yin.k/effect-kinds]
   :yin.k/target-closed [:yin.k/custody :yin.k/target]
   :yin.k/admitted [:yin.k/custody :yin.k/target :yin.k/op-id :yin.k/intent
                    :yin.k/value :yin.k/result]
   :yin.k/offered [:yin.k/custody :yin.k/occurrence :yin.k/id :yin.k/policy
                   :yin.k/medium :yin.k/baseline]
   :dao.lease/accepted [:dao.lease/status :dao.lease/lease :dao.lease/proposal
                        :dao.lease/subject :dao.lease/holder
                        :dao.lease/duration :dao.lease/max]
   :yin.k/bound [:yin.k/custody :yin.k/occurrence :dao.lease/lease
                 :dao.lease/holder :yin.k/epoch]
   :dao.lease/lapsed [:dao.lease/status :dao.lease/lease :dao.lease/cause]
   :yin.k/reclaimed [:yin.k/custody :yin.k/occurrence :dao.lease/lease
                     :yin.k/epoch]
   :dao.lease/rejected [:dao.lease/status :dao.lease/proposal]
   :yin.k/refused [:yin.k/custody :yin.k/proposer :dao.lease/proposal]})


(def effect-kinds
  "The effect kinds an enrolled target accepts in this slice."
  #{:yin.k/append})


(def ok-result {:dao.stream/outcome :dao.stream/ok})

(def closed-result {:dao.stream/outcome :dao.stream/closed})


(defn target-identity
  "The identity of the target minted by the transaction at time t of the
   ledger whose identity is `arbitration`: stable, and never reused."
  [arbitration t]
  (str arbitration "/target/" t))


(defn intent
  "The canonical intent digest of appending `value` to `target`: the
   BLAKE3 hex of the canonical bytes of `[:yin.k/append target value]`."
  [target value]
  (jing/digest-bytes :blake3 (cbor/encode [:yin.k/append target value])))


(defn empty-projection
  "The projection of an empty ledger.  `max-epoch`, a lower epoch bound
   for tests, defaults to `max-exact`."
  ([arbitration] (empty-projection arbitration max-exact))
  ([arbitration max-epoch]
   {:arbitration arbitration
    :max-epoch max-epoch
    :next-t 0
    :next-e datom/first-user-id
    :targets {}
    :admitted {}
    :occurrences {}
    :leases {}
    :answered {}}))


(defn fact-kind
  "The kind a fact dispatches on: its `:yin.k/custody`, or for a lease
   fact its `:dao.lease/status`.  Nil when it carries both or neither."
  [fact]
  (let [c (get fact :yin.k/custody)
        s (get fact :dao.lease/status)]
    (cond
      (and (some? c) (some? s)) nil
      (some? c) c
      :else s)))


(defn- argument-defect!
  [d fact]
  (throw (ex-info (str "Ledger argument defect: " (name d))
                  {::defect d :fact fact})))


(defn fact-datoms
  "The `[e a v]` datoms of one fact map, in its kind's attribute order.
   A fact whose kind has no published order, or that carries an
   attribute outside it, is an argument defect and throws: nothing is
   silently dropped."
  [e fact]
  (let [order (get attribute-order (fact-kind fact))]
    (when-not order
      (argument-defect! :unknown-kind fact))
    (when-not (every? (set order) (keys fact))
      (argument-defect! :unpublished-attribute fact))
    (into []
          (comp (filter #(contains? fact %))
                (map (fn [a] [e a (get fact a)])))
          order)))


(defn facts->datoms
  "The datoms of `facts`, in order, with ids drawn from `next-e`.
   Throws on a fact `fact-datoms` refuses, before any write."
  [next-e facts]
  (into [] (mapcat fact-datoms (iterate inc next-e) facts)))


;; =============================================================================
;; Fold
;; =============================================================================

(defn- defect!
  [d data]
  (throw (ex-info (str "Ledger defect: " (name d)) (assoc data ::defect d))))


(defn- entities
  "The entity maps of `datoms` in first-appearance order, each checked
   against its kind's attribute order."
  [datoms]
  (let [groups (reduce (fn [acc [e a v]]
                         (if-let [i (get (:index acc) e)]
                           (update-in acc [:groups i] conj [a v])
                           (-> acc
                               (assoc-in [:index e] (count (:groups acc)))
                               (update :groups conj [[a v]]))))
                       {:index {} :groups []}
                       datoms)]
    (mapv (fn [avs]
            (let [m (into {} avs)
                  order (get attribute-order (fact-kind m))]
              (when-not (and order
                             (= (map first avs)
                                (filter #(contains? m %) order)))
                (defect! :malformed-fact {:fact avs}))
              m))
          (:groups groups))))


(defn- target!
  [projection i]
  (or (get-in projection [:targets i])
      (defect! :unknown-target {:yin.k/target i})))


(defn- offer-defect
  "Why `fact` is not an offer this ledger can hold, or nil."
  [projection fact]
  (let [o (:yin.k/occurrence fact)
        b (:yin.k/baseline fact)
        known (get-in projection [:occurrences o])]
    (cond
      (not (and (custody/occurrence? o)
                (jing/segment-address? (:yin.k/id fact))
                (= :yin.k/exclusive (:yin.k/policy fact))
                (some? (:yin.k/medium fact))
                (map? b)
                (= o (:yin.k/occurrence b))
                (contains? #{:blocked :parked} (:yin.k/kind b))))
      :malformed-fact
      (not= (:arbitration projection)
            (get-in b [:yin.k/arbitration :dao.stream/identity]))
      :foreign-arbitration
      (contains? (:yin.k/variants known) (:yin.k/id fact)) :duplicate-offer
      (and known (not= b (:yin.k/baseline known))) :variant-conflict
      :else nil)))


(defn- grant-defect
  "Why `fact` is not a grant this ledger can hold, or nil."
  [projection fact]
  (let [o (custody/subject-occurrence (:dao.lease/subject fact))
        known (get-in projection [:occurrences o])]
    (cond
      (or (lease/defective? fact) (nil? o)) :malformed-fact
      (nil? known) :unknown-occurrence
      (contains? (:leases projection) (:dao.lease/lease fact))
      :duplicate-lease
      (:yin.k/exhausted known) :exhausted-occurrence
      (some? (:dao.lease/lease known)) :held-occurrence
      (contains? (:answered projection)
                 [(:dao.lease/holder fact) (:dao.lease/proposal fact)])
      :answered-proposal
      :else nil)))


(defn- bound-defect
  "Why `fact` is not the binding of a grant in this transaction, or nil."
  [projection fact]
  (let [l (:dao.lease/lease fact)
        granted (get-in projection [:leases l])]
    (cond
      (not (custody/exact? (:yin.k/epoch fact))) :malformed-fact
      (nil? granted) :unknown-lease
      (not (contains? (::unbound projection) l)) :duplicate-binding
      (not (and (= (:yin.k/occurrence granted) (:yin.k/occurrence fact))
                (= (:dao.lease/holder granted) (:dao.lease/holder fact))))
      :binding-mismatch
      (not= (get-in projection [:occurrences (:yin.k/occurrence fact)
                                :yin.k/epoch])
            (:yin.k/epoch fact))
      :epoch-mismatch
      :else nil)))


(defn- lapse-defect
  "Why `fact` is not the lapse of a live lease, or nil."
  [projection fact]
  (let [granted (get-in projection [:leases (:dao.lease/lease fact)])]
    (cond
      (lease/defective? fact) :malformed-fact
      (nil? granted) :unknown-lease
      (contains? granted :dao.lease/cause) :duplicate-lapse
      (nil? (:yin.k/epoch granted)) :unbound-grant
      :else nil)))


(defn next-epoch
  "The epoch a reclaim of a lease bound at `e` leaves: e + 1, or e at
   the bound `max-epoch`, where the reclaim exhausts the occurrence."
  [max-epoch e]
  (if (< e max-epoch) (inc e) e))


(defn- reclaimed-defect
  "Why `fact` is not the epoch change of a lapse in this transaction,
   or nil."
  [projection fact]
  (let [l (:dao.lease/lease fact)
        granted (get-in projection [:leases l])]
    (cond
      (not (and (custody/occurrence? (:yin.k/occurrence fact))
                (custody/exact? (:yin.k/epoch fact))))
      :malformed-fact
      (not (contains? (::unepoched projection) l)) :unpaired-epoch
      (not= (:yin.k/occurrence granted) (:yin.k/occurrence fact))
      :binding-mismatch
      (not= (next-epoch (:max-epoch projection) (:yin.k/epoch granted))
            (:yin.k/epoch fact))
      :epoch-mismatch
      :else nil)))


(defn- refused-defect
  "Why `fact` is not the proposer of a refusal in this transaction, or
   nil."
  [projection fact]
  (let [pid (:dao.lease/proposal fact)]
    (cond
      (or (nil? (:yin.k/proposer fact)) (nil? pid)) :malformed-fact
      (not (contains? (::unrefused projection) pid)) :unpaired-refusal
      (contains? (:answered projection) [(:yin.k/proposer fact) pid])
      :answered-proposal
      :else nil)))


(def ^:private grant-terms
  [:dao.lease/duration :dao.lease/proposal :dao.lease/max])


(defn- fold-fact
  [projection t fact]
  (case (fact-kind fact)
    :yin.k/enrolled
    (let [i (:yin.k/target fact)]
      (when-not (= (target-identity (:arbitration projection) t) i)
        (defect! :foreign-target {:yin.k/target i}))
      (when-not (= effect-kinds (:yin.k/effect-kinds fact))
        (defect! :malformed-fact {:fact fact}))
      (assoc-in projection [:targets i] {:closed? false :values []}))

    :yin.k/target-closed
    (let [i (:yin.k/target fact)]
      (when (:closed? (target! projection i))
        (defect! :closed-target {:yin.k/target i}))
      (assoc-in projection [:targets i :closed?] true))

    :yin.k/admitted
    (let [i (:yin.k/target fact)
          k (cbor/content-key (:yin.k/op-id fact))
          closed? (:closed? (target! projection i))
          r (:yin.k/result fact)]
      (when (contains? (:admitted projection) k)
        (defect! :duplicate-op-id {:yin.k/op-id (:yin.k/op-id fact)}))
      (when-not (and (string? (:yin.k/intent fact))
                     (if closed?
                       (and (= closed-result r)
                            (not (contains? fact :yin.k/value)))
                       (and (= ok-result r)
                            (contains? fact :yin.k/value))))
        (defect! :malformed-fact {:fact fact}))
      (cond-> (assoc-in projection [:admitted k]
                        (select-keys fact [:yin.k/target :yin.k/intent
                                           :yin.k/result]))
        (not closed?) (update-in [:targets i :values]
                                 conj (:yin.k/value fact))))

    :yin.k/offered
    (let [o (:yin.k/occurrence fact)]
      (when-let [d (offer-defect projection fact)]
        (defect! d {:fact fact}))
      (if (get-in projection [:occurrences o])
        (update-in projection [:occurrences o :yin.k/variants]
                   conj (:yin.k/id fact))
        (assoc-in projection [:occurrences o]
                  {:yin.k/baseline (:yin.k/baseline fact)
                   :yin.k/variants #{(:yin.k/id fact)}
                   :yin.k/epoch 0
                   :dao.lease/lease nil})))

    ;; A grant opens a lease that the same transaction must bind; the
    ;; record's fold fails when one is left in ::unbound.
    :dao.lease/accepted
    (let [l (:dao.lease/lease fact)
          o (custody/subject-occurrence (:dao.lease/subject fact))]
      (when-let [d (grant-defect projection fact)]
        (defect! d {:fact fact}))
      (cond-> (-> projection
                  (assoc-in [:occurrences o :dao.lease/lease] l)
                  (assoc-in [:leases l]
                            (merge {:yin.k/occurrence o
                                    :dao.lease/holder (:dao.lease/holder fact)
                                    :yin.k/epoch nil
                                    :dao.space/t t}
                                   (select-keys fact grant-terms)))
                  (update ::unbound (fnil conj #{}) l))
        (some? (:dao.lease/proposal fact))
        (assoc-in [:answered [(:dao.lease/holder fact)
                              (:dao.lease/proposal fact)]]
                  :dao.lease/accepted)))

    :yin.k/bound
    (let [l (:dao.lease/lease fact)]
      (when-let [d (bound-defect projection fact)]
        (defect! d {:fact fact}))
      (-> projection
          (assoc-in [:leases l :yin.k/epoch] (:yin.k/epoch fact))
          (update ::unbound disj l)))

    ;; A lapse ends a live lease; the same transaction must change its
    ;; occurrence's epoch, or the record's fold fails.
    :dao.lease/lapsed
    (let [l (:dao.lease/lease fact)
          o (get-in projection [:leases l :yin.k/occurrence])]
      (when-let [d (lapse-defect projection fact)]
        (defect! d {:fact fact}))
      (-> projection
          (assoc-in [:leases l :dao.lease/cause] (:dao.lease/cause fact))
          (assoc-in [:occurrences o :dao.lease/lease] nil)
          (update ::unepoched (fnil conj #{}) l)))

    :yin.k/reclaimed
    (let [l (:dao.lease/lease fact)
          o (:yin.k/occurrence fact)
          e (:yin.k/epoch fact)]
      (when-let [d (reclaimed-defect projection fact)]
        (defect! d {:fact fact}))
      (cond-> (-> projection
                  (assoc-in [:occurrences o :yin.k/epoch] e)
                  (update ::unepoched disj l))
        (= e (get-in projection [:leases l :yin.k/epoch]))
        (assoc-in [:occurrences o :yin.k/exhausted] true)))

    ;; A rejection names no proposer; the same transaction must name it
    ;; in a :yin.k/refused fact, or the record's fold fails.
    :dao.lease/rejected
    (let [pid (:dao.lease/proposal fact)]
      (when (lease/defective? fact)
        (defect! :malformed-fact {:fact fact}))
      (when (contains? (::unrefused projection) pid)
        (defect! :duplicate-rejection {:fact fact}))
      (update projection ::unrefused (fnil conj #{}) pid))

    :yin.k/refused
    (let [pid (:dao.lease/proposal fact)]
      (when-let [d (refused-defect projection fact)]
        (defect! d {:fact fact}))
      (-> projection
          (assoc-in [:answered [(:yin.k/proposer fact) pid]]
                    :dao.lease/rejected)
          (update ::unrefused disj pid)))))


(defn- fold-record*
  [projection record]
  (let [t (:next-t projection)
        tx (get record :dao.space/transaction)
        datoms (get tx :datoms)]
    (when-not (and (map? record) (= 1 (count record))
                   (map? tx) (= #{:t :datoms} (set (keys tx))))
      (defect! :malformed-record {:record record}))
    (when-not (= t (:t tx))
      (defect! :t-mismatch {:t (:t tx) :position t}))
    (when-not (and (vector? datoms) (seq datoms))
      (defect! :malformed-record {:record record}))
    (doseq [d datoms]
      (when-not (and (datom/local-datom? d)
                     (= t (nth d 3))
                     (= datom/default-op (nth d 4))
                     (<= (:next-e projection) (nth d 0)))
        (defect! :malformed-datom {:datom d})))
    (let [p (reduce #(fold-fact %1 t %2) projection (entities datoms))]
      (when (seq (::unbound p))
        (defect! :unbound-grant {:dao.lease/lease (first (::unbound p))}))
      (when (seq (::unepoched p))
        (defect! :lapse-without-epoch
          {:dao.lease/lease (first (::unepoched p))}))
      (when (seq (::unrefused p))
        (defect! :rejection-without-refusal
          {:dao.lease/proposal (first (::unrefused p))}))
      (-> (dissoc p ::unbound ::unepoched ::unrefused)
          (assoc :next-t (inc t)
                 :next-e (inc (reduce max (map first datoms))))))))


(defn fold-record
  "Fold the record at the projection's next position, or answer
   `{::defect d}`."
  [projection record]
  (try
    (fold-record* projection record)
    (catch #?(:cljd Object :clj Throwable :cljs :default) e
      (if-let [d (::defect (ex-data e))]
        {::defect d}
        {::defect :unreadable-record}))))
