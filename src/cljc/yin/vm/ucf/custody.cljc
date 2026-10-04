(ns yin.vm.ucf.custody
  "The custody facts of M-next C slice C5 (UCF 7.2.1, 7.7.2, 7.7.8): the
   occurrence form, the offer and binding constructors with the
   validators the ledger fold runs, and `binding-evidence`, the pure
   reader-side proof that a grant carries a valid epoch binding.

   An occurrence is a UUID string minted once at park, in its one
   lowercase spelling, so two ids are equal exactly when their canonical
   bytes are.  It is stable across retries because the emitter keeps it,
   not because it is derived; it is distinct for distinct parks and
   collision-resistant by its 122 random bits.

   The authority records three fact kinds here, beside C3's:
     :yin.k/offered       an admitted offer of one snapshot variant, with
                          the operation baseline the inspector derived
     :dao.lease/accepted  the lease grant, the dao.lease fact unchanged
     :yin.k/bound         the grant's epoch binding, in the same
                          transaction as its grant

   Nothing here throws on any input."
  (:require [dao.jing.cbor :as cbor]))


(def max-exact
  "2^52-1, the largest epoch (7.7.8)."
  4503599627370495)


(defn exact?
  "A nonnegative exact integer no greater than 2^52-1, by the canonical
   codec's integer kind: an integral float is not one."
  [x]
  (and (cbor/numeric? x)
       (= :integer (cbor/numeric-kind x))
       (not (neg? (cbor/num-compare x 0)))
       (not (pos? (cbor/num-compare x max-exact)))))


;; =============================================================================
;; Occurrence
;; =============================================================================

(defn mint-occurrence
  "A fresh occurrence id, minted once at park."
  []
  (str (random-uuid)))


(defn occurrence?
  "True for an occurrence id: a UUID string in lowercase hex."
  [x]
  (and (string? x)
       (some? (re-matches
                #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                x))))


;; =============================================================================
;; Facts
;; =============================================================================

(defn offer
  "The authority's record of an admitted offer: snapshot variant
   `address` of occurrence `o`, carried on `medium`, with the operation
   `baseline` checkpoint/inspect derived from its bytes."
  [o address medium baseline]
  {:yin.k/custody :yin.k/offered
   :yin.k/occurrence o
   :yin.k/id address
   :yin.k/policy :yin.k/exclusive
   :yin.k/medium medium
   :yin.k/baseline baseline})


(defn bound
  "The grant epoch binding of lease `l` to holder `h` on occurrence `o`
   at epoch `e` (7.7.8)."
  [o l h e]
  {:yin.k/custody :yin.k/bound
   :yin.k/occurrence o
   :dao.lease/lease l
   :dao.lease/holder h
   :yin.k/epoch e})


(defn reclaimed
  "The epoch change of the reclaim of lease `l` on occurrence `o`: the
   occurrence's epoch `e` after it, committed in the transaction of
   l's `:dao.lease/lapsed` fact (7.7.8)."
  [o l e]
  {:yin.k/custody :yin.k/reclaimed
   :yin.k/occurrence o
   :dao.lease/lease l
   :yin.k/epoch e})


(defn refused
  "The authority's record that it refused `proposer`'s proposal `pid`,
   committed in the transaction of the plain `:dao.lease/rejected`
   fact, which names no proposer."
  [proposer pid]
  {:yin.k/custody :yin.k/refused
   :yin.k/proposer proposer
   :dao.lease/proposal pid})


(defn subject
  "The lease subject of occurrence `o`."
  [o]
  {:yin.k/occurrence o})


(defn subject-occurrence
  "The occurrence a lease subject names, or nil when it is not exactly
   `{:yin.k/occurrence o}` with o an occurrence."
  [s]
  (when (and (map? s) (= 1 (count s))
             (occurrence? (get s :yin.k/occurrence)))
    (get s :yin.k/occurrence)))


;; =============================================================================
;; Binding evidence (7.7.8): pure, over attributed records
;; =============================================================================

(defn- record-facts
  "The facts of one transaction record as `[t [fact ...]]`, entities in
   first-appearance order, or nil when it is not a record."
  [record]
  (let [tx (when (map? record) (get record :dao.space/transaction))
        t (when (map? tx) (get tx :t))
        datoms (when (map? tx) (get tx :datoms))]
    (when (and (exact? t) (vector? datoms)
               (every? #(and (vector? %) (= 5 (count %)) (= t (nth % 3)))
                       datoms))
      [t (->> datoms
              (reduce (fn [acc [e a v]]
                        (if (contains? (:by-e acc) e)
                          (assoc-in acc [:by-e e a] v)
                          (-> acc
                              (assoc-in [:by-e e] {a v})
                              (update :order conj e))))
                      {:by-e {} :order []})
              ((fn [{:keys [by-e order]}] (mapv by-e order))))])))


(defn- lease-bindings
  [l facts]
  (filter #(and (= :yin.k/bound (get % :yin.k/custody))
                (= l (get % :dao.lease/lease)))
          facts))


(defn binding-evidence
  "The evidence that lease `l` holds a valid epoch binding, read from
   `records`, a sequence of `[author record]`: each transaction record
   paired with the author the composition's resolver attributed to the
   stream it was read from.  Only records whose author is `arbitration`,
   the identity of the authority a body's `:yin.k/arbitration` names,
   count; any other author's are facts about that author.

   Valid when exactly one binding for `l` stands among the authority's
   records, its epoch is an exact integer in range, and the same record
   holds a `:dao.lease/accepted` fact with lease `l`, the binding's
   holder and the subject `{:yin.k/occurrence o}`.  Answers

     {:yin.k/transaction {:yin.k/arbitration arbitration :dao.space/t t}
      :yin.k/occurrence o :dao.lease/lease l :dao.lease/holder h
      :yin.k/epoch e}

   where t names the record, or `{::no-evidence reason}`, reason one of
   :malformed-record, :no-binding, :duplicate-binding, :inexact-epoch,
   :not-in-grant-transaction, :grant-mismatch.  A malformed record
   from the authority fails the whole read closed."
  [arbitration records l]
  (let [mine (into [] (comp (filter #(= arbitration (first %)))
                            (map (comp record-facts second)))
                   records)
        bindings (for [[t facts] mine
                       b (lease-bindings l facts)]
                   [t facts b])
        [[t facts b] more] [(first bindings) (next bindings)]
        o (get b :yin.k/occurrence)
        h (get b :dao.lease/holder)
        grants (filter #(and (= :dao.lease/accepted
                                (get % :dao.lease/status))
                             (= l (get % :dao.lease/lease)))
                       facts)
        reason (cond
                 (some nil? mine) :malformed-record
                 (nil? b) :no-binding
                 more :duplicate-binding
                 (not (exact? (get b :yin.k/epoch))) :inexact-epoch
                 (empty? grants) :not-in-grant-transaction
                 (not (and (= 1 (count grants))
                           (= h (get (first grants) :dao.lease/holder))
                           (= (subject o)
                              (get (first grants) :dao.lease/subject))))
                 :grant-mismatch)]
    (if reason
      {::no-evidence reason}
      {:yin.k/transaction {:yin.k/arbitration arbitration :dao.space/t t}
       :yin.k/occurrence o
       :dao.lease/lease l
       :dao.lease/holder h
       :yin.k/epoch (get b :yin.k/epoch)})))
