(ns yin.vm.ucf.holder.evidence
  "The authenticated ledger reader of M-next D slice D3 (UCF 7.7.8;
   docs/design/yin.vm.linker.dht.md 14.2).

   A holder reads the authority's ledger stream, attributed by the
   composition's resolver to the arbitration identity, and folds it with
   `ledger/fold-record` into its own reader projection.  From that
   projection it derives the evidence a grant needs: the epoch binding
   (`custody/binding-evidence`), the enrolled targets, and the replay
   prefix (`input/inputs`).

   This namespace is pure over attributed record data.  It takes no
   stream handle and no authority value, and it reads no authority
   projection.

   Complete history or nothing.  The fold starts at the ledger's origin,
   with dense t, and must reach the grant's record.  A gap, a start past
   the origin, a transport error or a fold defect makes the evidence
   unavailable, and unavailable evidence is never an empty prefix or a
   missing enrollment."
  (:require [yin.vm.ucf.authority.input :as input]
            [yin.vm.ucf.custody :as custody]
            [yin.vm.ucf.ledger :as ledger]))


(defn- unsatisfied
  [reason]
  {:yin.k/status :yin.k/unsatisfied :yin.k/reason reason})


(defn- awaiting-grant
  [l]
  {:yin.k/status :yin.k/awaiting-grant :dao.lease/lease l})


(defn- transport-error?
  "True for a read that answered a stream outcome in place of a record."
  [record]
  (and (map? record) (contains? record :dao.stream/outcome)))


(defn- record-t
  [record]
  (get-in record [:dao.space/transaction :t]))


(defn- defect
  "The reason record `record` cannot be folded at position `n`, or nil
   when its t is the next one."
  [n record]
  (let [t (record-t record)]
    (cond
      (not (custody/exact? t)) :fold-defect
      (= t n) nil
      (> t n) (if (zero? n) :start-past-origin :gap)
      :else :fold-defect)))


(defn- fold
  "Fold `mine`, the arbitration's records in read order, from the origin.
   Answers the projection, or `{::reason r}`."
  [arbitration mine]
  (loop [p (ledger/empty-projection arbitration) rs (seq mine)]
    (if-not rs
      p
      (let [record (first rs)]
        (if-let [d (defect (:next-t p) record)]
          {::reason d}
          (let [p' (ledger/fold-record p record)]
            (if (::ledger/defect p')
              {::reason :fold-defect}
              (recur p' (next rs)))))))))


(defn read-evidence
  "The holder evidence of lease `l`, read from `records`, a sequence of
   `[author record]`: each transaction record of the authority's ledger
   stream, in stream order from the origin, paired with the author the
   composition's resolver attributed to the stream it was read from.  A
   read that answered a stream outcome in place of a record, such as a
   transport error, takes the place of a record.  Only records whose
   author is `arbitration` count; any other author's establish nothing.

   Answers, when the whole history reaches l's grant,

     {:yin.k/status :yin.k/ready
      :yin.k/binding b        ; custody/binding-evidence's answer
      :yin.k/enrolled ids     ; the set of enrolled target identities
      :yin.k/prefix p}        ; input/inputs's answer, possibly empty

   and otherwise one of

     {:yin.k/status :yin.k/awaiting-grant :dao.lease/lease l}
         ; the history is whole so far and holds no grant of l yet
     {:yin.k/status :yin.k/unsatisfied :yin.k/reason r}
         ; r is :transport-error, :start-past-origin, :gap,
         ; :fold-defect, or the binding's own no-evidence reason

   Nothing here throws on any input."
  [arbitration records l]
  (if (or (not (some? arbitration))
          (some #(not (and (vector? %) (>= (count %) 2))) records))
    ;; A malformed item, most dangerously a bare stream-outcome map from
    ;; a failed read, is a partial history pretending to be a whole one:
    ;; refuse the read instead of filtering it out (gate finding 1).
    (unsatisfied :fold-defect)
    (let [mine (into [] (comp (filter #(= arbitration (first %)))
                              (map second))
                     records)]
      (if (some transport-error? mine)
        (unsatisfied :transport-error)
        (let [p (fold arbitration mine)]
          (cond
            (::reason p) (unsatisfied (::reason p))
            (nil? (get-in p [:leases l])) (awaiting-grant l)
            :else
            (let [attributed (mapv (fn [r] [arbitration r]) mine)
                  b (custody/binding-evidence arbitration attributed l)]
              (if-let [reason (::custody/no-evidence b)]
                (unsatisfied reason)
                {:yin.k/status :yin.k/ready
                 :yin.k/binding b
                 :yin.k/enrolled (set (keys (:targets p)))
                 :yin.k/prefix (input/inputs p l)}))))))))
