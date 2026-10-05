(ns yin.vm.ucf.authority
  "The arbitration authority core of M-next C slice C3: a dao.space
   transactor whose local stream is a dao.stream.journal, so the journal
   is the authority's sole transaction history and the projection is
   derived from it (docs/design/dao.space.transactor.md, *The
   arbitration ledger exception*).  Each decision is one
   `transactor/transact!` and one journal frame.

   `open!` replays the journal, requires each record's t to equal its
   position, rebuilds the transactor (whose derived next t must equal
   the record count) and folds the projection (yin.vm.ucf.ledger); any
   defect refuses the open.  `close!` retires the value.

   `transition!` is the one path for every change, under one lock (JVM
   `locking`; Node and Dart run it synchronously in one isolate):
   refuse if poisoned, read the projection, decide purely, build the
   datoms, transact once, fold the committed record, reply.  A
   transition that would pass a counter bound commits nothing and
   answers `:suspended`.

   An uncertain durable append, or a failure between persistence and
   projection install, poisons the whole value: every later transition
   answers `{:yin.k/status :suspended}` and every target reader answers
   `:dao.stream/transport-error`.  Only a reopen, which reads the
   frames, clears it.

   `durability` is the backend's declaration kept from open (plan 1.8)
   and `exclusive-capable?` compares it with the failure model a
   composition requires (slice C12).

   Enrollment mints a target whose identity derives from the ledger's
   identity and the enrolling t.  `target-reader` is the reader-only
   stream of a target's committed appends (`ledger-projection`); the
   only append is an admission, yin.vm.ucf.authority.admission/admit!
   (yin.vm.ucf.authority.seam is a substrate test seam)."
  (:require [dao.space.transactor :as transactor]
            [dao.stream :as stream]
            [dao.stream.journal :as journal]
            [dao.stream.memory-log :as memory-log]
            [yin.vm.ucf.ledger :as ledger]))


(def target-type
  "The transport type of a target's reader stream."
  ::target)


(defn- with-lock
  "Serialize transitions on hosts with shared-memory threads.
   ClojureScript and ClojureDart calls are synchronous within one
   isolate."
  #_{:clj-kondo/ignore [:unused-binding]}
  [lock f]
  #?(:cljd (f)
     :clj (locking lock (f))
     :default (f)))


(defn locked
  "Call `f` under the authority's transition lock, so a composite
   decision (a whole dao.lease judge-step, an offer that stores content
   before it commits) sees one projection throughout.  `transition!`
   inside `f` re-enters the same lock."
  [authority f]
  (with-lock (:lock authority) f))


(defn- status
  ([s] {:yin.k/status s})
  ([s reason] {:yin.k/status s :yin.k/reason reason}))


(defn- ok?
  [r]
  (= :dao.stream/ok (:dao.stream/outcome r)))


(defn- fold-from
  "Fold every record from `cursor` to the tail of `h`.  Answers the
   projection and the tail cursor, or ::ledger/defect."
  [h projection cursor]
  (loop [p projection c cursor]
    (let [r (stream/next h c)]
      (if (ok? r)
        (let [p' (ledger/fold-record p (:dao.stream/value r))]
          (if (::ledger/defect p')
            p'
            (recur p' (:dao.stream/cursor r))))
        {:projection p :cursor c}))))


;; =============================================================================
;; Open and close
;; =============================================================================

(defn open!
  "Open the authority over a journal `backend`.  `opts` may carry
   ::max-exact, a lower counter bound for tests, and ::max-epoch, a
   lower epoch bound for tests; a ledger must be reopened with the
   epoch bound it was written under.  Answers
   `{:yin.k/status :open ::authority a :dao.stream/identity i}`, or
   `{:yin.k/status :refused :yin.k/defect d}` having written nothing
   beyond what dao.stream.journal/open! writes."
  ([backend] (open! backend nil))
  ([backend opts]
   (let [j (journal/open! backend)
         h (:dao.stream/handle j)
         arb (:dao.stream/identity j)
         folded (when (ok? j)
                  (fold-from h (ledger/empty-projection
                                 arb (get opts ::max-epoch ledger/max-exact))
                             (:dao.stream/cursor
                               (stream/cursor h :dao.stream/oldest))))
         tx (when (:projection folded)
              (try
                (transactor/create!
                  {:local-stream h
                   :intake-pool [(:dao.stream/handle
                                   (memory-log/create!
                                     {:dao.stream/type
                                      :dao.stream/memory-log}))]
                   :name arb})
                (catch #?(:cljd Object :clj Throwable :cljs :default) _
                  nil)))]
     (cond
       (not (ok? j))
       {:yin.k/status :refused
        :yin.k/defect (:dao.stream.journal/defect j)}
       (::ledger/defect folded)
       {:yin.k/status :refused :yin.k/defect (::ledger/defect folded)}
       (not (and tx (= @(:next-t tx) (:next-t (:projection folded)))))
       {:yin.k/status :refused :yin.k/defect :t-mismatch}
       :else
       {:yin.k/status :open
        :dao.stream/identity arb
        ::authority {:lock (atom nil)
                     :journal h
                     :transactor tx
                     :bound (get opts ::max-exact ledger/max-exact)
                     :durability (when-let [d (::journal/durability backend)]
                                   (d))
                     :state (atom {:projection (:projection folded)
                                   :cursor (:cursor folded)
                                   :poisoned? false
                                   :clean? true
                                   :closed? false})}}))))


(defn close!
  "Retire this authority value: later transitions answer
   `{:yin.k/status :closed}` and its readers serve nothing.  The journal
   is left as it is, for the next open."
  [authority]
  (with-lock (:lock authority)
    (fn []
      (swap! (:state authority) assoc :closed? true)
      (transactor/close! (:transactor authority))
      {:yin.k/status :closed})))


(defn projection
  "The current projection, or nil when the value is poisoned or closed:
   only an open, unpoisoned authority serves it as authoritative."
  [authority]
  (let [s @(:state authority)]
    (when-not (or (:poisoned? s) (:closed? s))
      (:projection s))))


;; =============================================================================
;; Durability (plan 1.8)
;; =============================================================================

(defn durability
  "The declaration the journal backend made when this authority opened
   it, as data: `{:dao.stream.journal/backend :file|:memory
   :dao.stream.journal/failure-model :process-crash|:power-loss|:none
   :dao.stream.journal/lock-kind :os-lock|:claim-file|:none
   :dao.stream.journal/persisted #{...}}`.  Nil for a backend that
   declares nothing."
  [authority]
  (:durability authority))


(def ^:private failure-rank
  {:none 0 :process-crash 1 :power-loss 2})


(defn exclusive-capable?
  "True only when this authority can serve an exclusive composition that
   requires surviving `required`, :process-crash or :power-loss: its
   backend is :file whose declared lock kind is not :none,
   it is open, unpoisoned and clean (`:clean?` is set by a successful
   open!, which grant/reopen! goes through, and cleared by poison, so
   only a reopen sets it again; today that makes it equivalent to not
   poisoned, and it is the hook a stricter rule would fill), and its
   declared failure model covers `required` (:power-loss covers
   :process-crash, not the reverse).  A memory backend is never
   capable.  The lock kind and failure model are the declaration the
   backend made at open, and the flags are read as they stand: neither
   the lock nor the file is probed."
  [authority required]
  (let [d (durability authority)
        s @(:state authority)]
    (boolean
      (and (contains? #{:process-crash :power-loss} required)
           (= :file (:dao.stream.journal/backend d))
           (contains? #{:os-lock :claim-file}
                      (:dao.stream.journal/lock-kind d))
           (:clean? s)
           (not (:poisoned? s))
           (not (:closed? s))
           (<= (failure-rank required)
               (get failure-rank (:dao.stream.journal/failure-model d) 0))))))


;; =============================================================================
;; Transition
;; =============================================================================

(defn- poison!
  [authority reason]
  (swap! (:state authority) assoc :poisoned? true :clean? false)
  (status :suspended reason))


(defn- commit!
  "Transact `datoms` once and fold the committed record."
  [authority {:keys [cursor] :as s} datoms reply]
  (let [r (try
            (transactor/transact! (:transactor authority) datoms)
            (catch #?(:cljd Object :clj Throwable :cljs :default) _
              {:dao.stream/outcome :dao.stream/transport-error}))
        t (:dao.space/t r)]
    (if-not (ok? r)
      (poison! authority :uncertain-append)
      (let [n (try
                (stream/next (:journal authority) cursor)
                (catch #?(:cljd Object :clj Throwable :cljs :default) _
                  nil))
            p (when (ok? n)
                (ledger/fold-record (:projection s) (:dao.stream/value n)))]
        (if (or (nil? p) (::ledger/defect p))
          (poison! authority :uninstalled)
          (do (swap! (:state authority) assoc
                     :projection p
                     :cursor (:dao.stream/cursor n))
              (assoc reply :dao.space/t t)))))))


(defn transition!
  "Run one decision.  `decide` is a pure function of the projection
   answering `{::reply r}`, which commits nothing, or `{::facts fs
   ::reply r}`: the facts are committed as one transaction, ids drawn in
   order from the projection's `:next-e`, and r is answered with
   `:dao.space/t`.  A poisoned value answers `{:yin.k/status :suspended
   :yin.k/reason :poisoned}`, a closed one `{:yin.k/status :closed}`.  A
   fact outside its kind's published attribute order is an argument
   defect: ledger/facts->datoms throws before anything is written."
  [authority decide]
  (with-lock
    (:lock authority)
    (fn []
      (let [s @(:state authority)
            bound (:bound authority)]
        (cond
          (:closed? s) (status :closed)
          (:poisoned? s) (status :suspended :poisoned)
          :else
          (let [p (:projection s)
                {::keys [facts reply]} (decide p)
                datoms (ledger/facts->datoms (:next-e p) facts)]
            (cond
              (empty? facts) reply
              (or (> (:next-t p) bound)
                  (> (reduce max (map first datoms)) bound))
              (status :suspended :bound)
              :else (commit! authority s datoms reply))))))))


;; =============================================================================
;; Enrollment and target close
;; =============================================================================

(defn enroll!
  "Mint and enroll one target.  Answers `{:yin.k/status :committed
   :yin.k/target i :dao.space/t t}`."
  [authority]
  (transition!
    authority
    (fn [p]
      (let [i (ledger/target-identity (:arbitration p) (:next-t p))]
        {::facts [{:yin.k/custody :yin.k/enrolled
                   :yin.k/target i
                   :yin.k/effect-kinds ledger/effect-kinds}]
         ::reply {:yin.k/status :committed :yin.k/target i}}))))


(defn close-target!
  "End target `i`'s stream.  A second close commits nothing and answers
   `:replayed`; an unknown target is `:refused`."
  [authority i]
  (transition!
    authority
    (fn [p]
      (let [target (get-in p [:targets i])]
        (cond
          (nil? target) {::reply (status :refused :unknown-target)}
          (:closed? target) {::reply (status :replayed)}
          :else {::facts [{:yin.k/custody :yin.k/target-closed
                           :yin.k/target i}]
                 ::reply (status :committed)})))))


;; =============================================================================
;; Target reader
;; =============================================================================

(defn- result
  [outcome]
  {:dao.stream/outcome outcome})


(deftype TargetReader
  [authority target]

  stream/IDaoStreamDescriptor

  (descriptor
    [_]
    {:dao.stream/outcome :dao.stream/ok
     :dao.stream/descriptor {:dao.stream/type target-type
                             :dao.stream/identity target}
     :dao.stream/identity target})


  stream/IDaoStreamReader

  (cursor
    [_ anchor]
    (if-let [t (get-in (projection authority) [:targets target])]
      (case anchor
        :dao.stream/oldest
        (assoc (result :dao.stream/ok)
               :dao.stream/cursor {::target target ::position 0})
        :dao.stream/newest
        (assoc (result :dao.stream/ok)
               :dao.stream/cursor {::target target
                                   ::position (count (:values t))})
        (result :dao.stream/invalid-anchor))
      (result :dao.stream/transport-error)))


  (next
    [_ cursor-value]
    (if-let [t (get-in (projection authority) [:targets target])]
      (let [pos (when (map? cursor-value) (::position cursor-value))
            tail (count (:values t))]
        (cond
          (not (and (map? cursor-value)
                    (contains? cursor-value ::target)))
          (result :dao.stream/invalid-cursor)
          (not= target (::target cursor-value))
          (result :dao.stream/cursor-mismatch)
          (not (and (integer? pos) (<= 0 pos tail)))
          (result :dao.stream/invalid-cursor)
          (< pos tail)
          {:dao.stream/outcome :dao.stream/ok
           :dao.stream/value (nth (:values t) pos)
           :dao.stream/cursor {::target target ::position (inc pos)}}
          (:closed? t) (result :dao.stream/end)
          :else (result :dao.stream/blocked)))
      (result :dao.stream/transport-error))))


(defn target-reader
  "The reader of target `i`: identity i, positions dense over its
   committed appends in ledger t order, blocked at the tail, end after
   its close.  It has no writer surface.  Nil for a target this
   authority never enrolled."
  [authority i]
  (when (get-in (:projection @(:state authority)) [:targets i])
    (TargetReader. authority i)))
