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
   for its `:yin.k/custody` kind, an absent attribute skipped.  Facts
   keep their decision order within a transaction.  So one decision
   sequence over one journal identity has the same bytes on every host.

   The kinds of this slice, all authored by the authority:
     :yin.k/enrolled       a target, minted by this transaction
     :yin.k/target-closed  the end of a target's stream
     :yin.k/admitted       one effect's dedup record with its result,
                           and the effect itself when the result is ok

   The projection is plain data:

     {:arbitration a      ; the ledger's stream identity
      :next-t      t      ; the next transaction time, the record count
      :next-e      e      ; the next entity id
      :targets     {i {:closed? b :values [v ...]}}
      :admitted    {(cbor/content-key op-id)
                    {:yin.k/target i :yin.k/intent h :yin.k/result r}}}

   `fold-record` answers the next projection, or `{::defect d}` when the
   record is not one this ledger can hold; nothing here throws."
  (:require [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]))


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
                    :yin.k/value :yin.k/result]})


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
  [arbitration]
  {:arbitration arbitration
   :next-t 0
   :next-e datom/first-user-id
   :targets {}
   :admitted {}})


(defn fact-datoms
  "The `[e a v]` datoms of one fact map, in its kind's attribute order."
  [e fact]
  (into []
        (comp (filter #(contains? fact %))
              (map (fn [a] [e a (get fact a)])))
        (get attribute-order (:yin.k/custody fact))))


(defn facts->datoms
  "The datoms of `facts`, in order, with ids drawn from `next-e`."
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
                  order (get attribute-order (:yin.k/custody m))]
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


(defn- fold-fact
  [projection t fact]
  (case (:yin.k/custody fact)
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
                                 conj (:yin.k/value fact))))))


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
    (-> (reduce #(fold-fact %1 t %2) projection (entities datoms))
        (assoc :next-t (inc t)
               :next-e (inc (reduce max (map first datoms)))))))


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
