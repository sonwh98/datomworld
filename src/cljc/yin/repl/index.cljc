(ns yin.repl.index
  "The shell's code indexer: a `dao.space.index` observer on `program-out`
   (docs/design/yin.repl.dao.space-index.md).

   It is a peer of the evaluator's observer on the same medium, attached
   independently and advanced by the evaluation round, never by the
   evaluator: neither knows the other.  Each expanded tree packet `[root
   rows]` it observes is projected to local `[e a v t m]` AST facts
   (yin.vm.code-as-tuples.md §6.5) and committed as ONE atomic transaction
   record through a `dao.space.transactor`, which allocates `t`; this
   namespace supplies no clock.  After a round that committed, the covered
   indexes are published through `transactor/publish!` into that round's
   own fresh intake, the intake is drained into the `dao.jing` store, the
   manifest is read back, and the intake is dropped: between rounds the
   indexer retains its local transaction log and nothing of any
   publication.

   Cost, accepted as the price of the owner's every-round cadence
   (2026-09-28): each publication rebuilds the covered indexes over the
   whole retained history, and opening the round's transactor rescans
   that history to derive `t`, so a round's index time grows linearly with
   the session's committed code and a session's total grows
   quadratically.  Memory does not: at most one publication is held, and
   only during its round.  An incremental publish in `dao.space.index`
   would remove the time growth; nothing here would need to change but
   the call.

   Every `e` is a transactor-local entity id; the only durable name in the
   projection is the `:yin/address` fact each node occurrence carries back
   to its row address.  `m` is a local metadata entity, one per program,
   carrying `:db/op`, the shell's stable session token, the program's root
   address, and the round.  Only code is indexed: no printed output, last
   value, or evaluation result enters.

   Loss is reported, never hidden, and never stops the evaluator (owner
   ruling, 2026-09-28): a gap is counted on the observer (`:ingress-gaps`)
   and marks the indexer `:lost?`, after which it consumes packets without
   indexing any until reset rebuilds it; a packet that could not be
   committed, or a publication that did not become readable, is recorded
   under `:failure`, never counted as indexed.  `notice` renders what a
   round lost for the shell's round result, and `status` is the
   evaluator-independent view `repl-state` reports.  Everything here is a
   session-composition value; reset and VM selection rebuild it with the
   session."
  (:require [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.space.index :as index]
            [dao.space.transactor :as transactor]
            [dao.stream :as stream]
            [dao.stream.memory-log :as memory-log]
            [dao.stream.observer :as observer]
            [yin.vm :as vm]
            [yin.vm.macro :as macro]))


;; =============================================================================
;; The §6.5 projection
;; =============================================================================

(defn- assign-local-ids
  "Give every node occurrence of `node`, the map AST rebuilt from row `id`,
   a fresh local entity id as its `:eid`, pairing it with its row through
   the §2.3 slot table.  Distinct occurrences of one shared row get distinct
   ids, so `vm/ast->datoms` emits every occurrence exactly as it does for a
   tree without `:eid`s.  `acc` is `[next-e addresses]`; answers
   `[node' acc']`, `addresses` mapping each id to its row address."
  [rows node id [next-e addresses]]
  (let [row (get rows id)
        slots (get vm/semantic-bytecode-grammar (:type node))
        child (fn [acc child-node child-id]
                (assign-local-ids rows child-node child-id acc))]
    (reduce
      (fn [[node acc] [i [field kind]]]
        (let [slot (nth row (+ 2 i))]
          (case kind
            :node (let [[c acc'] (child acc (get node field) slot)]
                    [(assoc node field c) acc'])
            :nodes (let [[cs acc']
                         (reduce (fn [[cs acc] [c cid]]
                                   (let [[c' acc'] (child acc c cid)]
                                     [(conj cs c') acc']))
                                 [[] acc]
                                 (map vector (get node field) slot))]
                     [(assoc node field cs) acc'])
            [node acc])))
      [(assoc node :eid next-e) [(inc next-e) (assoc addresses next-e id)]]
      (map-indexed vector slots))))


(defn packet->tx-data
  "Project one expanded tree packet to transactor tx-data, allocating local
   entity ids from `next-e`.  `provenance` is `{:session-token s :round r}`.

   Answers `{:tx-data [...] :next-e n :meta-e m :root root}`: the metadata
   entity's facts, then `vm/ast->datoms` of the expanded tree under `m`,
   then one `[e :yin/address addr]` per node occurrence.  Every datom leaves
   `t` nil, which the transactor stamps."
  [packet next-e {:keys [session-token round]}]
  (let [{:keys [root rows], :as row-set} (macro/packet->row-set packet)
        meta-e next-e
        [ast [next-e' addresses]] (assign-local-ids
                                    rows
                                    (vm/semantic-bytecode->ast row-set)
                                    root
                                    [(inc meta-e) {}])
        facts (vm/ast->datoms ast {:m meta-e})
        untimed (fn [e a v m] [e a v nil m])]
    (when-let [stray (some (fn [[e]] (when (neg? e) e)) facts)]
      (throw (ex-info "projected node escaped local id assignment"
                      {:e stray, :root root})))
    {:tx-data (-> [(untimed meta-e :db/op :db/assert datom/default-op)
                   (untimed meta-e :yin.repl/session session-token
                            datom/default-op)
                   (untimed meta-e :yin.repl/root root datom/default-op)
                   (untimed meta-e :yin.repl/round round datom/default-op)]
                  (into (map (fn [[e a v _t m]] (untimed e a v m))) facts)
                  (into (map (fn [[e addr]]
                               (untimed e :yin/address addr meta-e)))
                        (sort-by key addresses))),
     :next-e next-e',
     :meta-e meta-e,
     :root root}))


;; =============================================================================
;; The indexer value
;; =============================================================================

(defn- memory-log
  []
  (:dao.stream/handle
    (memory-log/create! {:dao.stream/type memory-log/transport-type})))


(defn make-indexer
  "The indexer for one session: `observer`, attached to `program-out`
   beside the evaluator's; `:local`, the complete-retention log its
   transactions are committed to (dao.space.transactor requires memory-log
   retention); and `content-store`, the `dao.jing` byte store publications
   materialize into.  `session-token` is the shell's stable token, recorded
   as provenance; `publish-opts` reach `transactor/publish!` as-is.

   Between rounds that is all it holds.  A round that has code to commit
   opens its `:publication` — a transactor over `:local` whose intake pool
   is one fresh complete-retention intake — and drops it when the round
   ends, so no earlier publication's payloads stay reachable from the
   indexer (see `step`)."
  [{:keys [observer session-token content-store publish-opts]}]
  {:observer observer
   :local (memory-log)
   :content-store content-store
   :session-token session-token
   :publish-opts publish-opts
   :next-e datom/first-user-id
   :transactions 0
   :published 0
   :published-payloads 0
   :manifest-address nil
   :lost? false
   :failure nil})


(defn- open-publication
  "The round's transactor and the `dao.jing` pool observer over its intake.
   `transactor/create!` derives the next `t` from `:local`'s retained
   history, so transaction time continues across rounds.  The intake is
   complete-retention: `publish-index!` appends every node blob before the
   manifest and the round drains only after it returns, so an evicting
   intake would lose the head of any publication larger than its capacity
   and that publication could never complete."
  [ix]
  (if (:publication ix)
    ix
    (let [intake (memory-log)]
      (assoc ix
             :publication
             {:transactor (transactor/create! {:local-stream (:local ix)
                                               :intake-pool [intake]
                                               :name "yin.repl.index"})
              :pool (jing/observer-state
                      [{:stream intake
                        :cursor (:dao.stream/cursor
                                  (stream/cursor intake
                                                 :dao.stream/oldest))}])}))))


(defn- commit
  "Commit one packet as one transaction.  A packet that could not be
   projected or committed is consumed and recorded as the failure, never
   counted."
  [ix packet round]
  (try
    (let [{:keys [tx-data next-e root]}
          (packet->tx-data packet
                           (:next-e ix)
                           {:session-token (:session-token ix), :round round})
          answer (transactor/transact! (get-in ix [:publication :transactor])
                                       tx-data)]
      (if (= :dao.stream/ok (:dao.stream/outcome answer))
        [(-> ix
             (assoc :next-e next-e)
             (update :transactions inc))
         true]
        [(assoc ix
                :failure {:round round
                          :root root
                          :stage :transact
                          :outcome (:dao.stream/outcome answer)})
         false]))
    (catch #?(:cljd Object :clj Exception :cljs js/Error) e
      [(assoc ix
              :failure {:round round
                        :stage :project
                        :message (ex-message e)})
       false])))


(defn- materialize
  "Drain the intake into the store until it blocks.  Answers how many
   payloads it materialized and, when the drain met a gap or a defect, what
   it met.  The pool is not kept: its intake is dropped with the round."
  [content-store pool]
  (loop [pool pool
         n 0]
    (let [{:keys [signal], :as r} (jing/observe-step! content-store pool)]
      (case signal
        :dao.stream/ok (recur (:state r) (inc n))
        :dao.stream/blocked [n nil]
        [n signal]))))


(defn- publish
  "Publish the covered indexes over every committed transaction through
   the round's transactor, drain its intake into the store, and read the
   manifest back from it.  Only a publication whose manifest the store
   answers becomes the reported one, covering `:published` transactions."
  [ix round]
  (try
    (let [{:keys [transactor pool]} (:publication ix)
          {:keys [manifest-address]} (transactor/publish! transactor
                                                          (:publish-opts ix))
          [n defect] (materialize (:content-store ix) pool)]
      (if defect
        (assoc ix :failure {:round round :stage :materialize :outcome defect})
        (do (index/read-manifest (:content-store ix) manifest-address)
            (assoc ix
                   :manifest-address manifest-address
                   :published (:transactions ix)
                   :published-payloads n))))
    (catch #?(:cljd Object :clj Exception :cljs js/Error) e
      (assoc ix
             :failure {:round round :stage :publish :message (ex-message e)}))))


(defn- consume
  "Advance the observer past every packet still on the medium, handing each
   to `f` with the indexer; a gap marks the indexer lost, after which no
   packet reaches `f`."
  [indexer f]
  (loop [ix indexer]
    (let [{:keys [status batch] observer' :observer}
          (observer/observe-next (:observer ix))
          ix (assoc ix :observer observer')]
      (case status
        :ok (recur (if (:lost? ix) ix (f ix batch)))
        :gap (recur (assoc ix :lost? true))
        ix))))


(defn step
  "The round's index step: observe every packet `program-out` still holds
   for this observer, commit each through the round's publication, publish
   when any was committed, then drop the publication — its transactor,
   intake, and pool — so the indexer carries none of this round's payloads
   into the next.  A gap is recovered across, counted on the observer's
   `:ingress-gaps`, and marks the indexer lost: the missing packet and
   every later one are never called indexed."
  [indexer round]
  (let [ix (consume indexer
                    (fn [ix packet]
                      (first (commit (open-publication ix) packet round))))]
    (dissoc (if (> (:transactions ix) (:transactions indexer))
              (publish ix round)
              ix)
            :publication)))


(defn skip
  "Advance the observer past every packet still on the medium without
   indexing it: a round that threw before its program ran executes nothing,
   so nothing is indexed.  A gap still marks the indexer lost."
  [indexer]
  (consume indexer (fn [ix _packet] ix)))


(defn status
  "The indexer's state as the shell reports it.  It depends on the programs
   evaluated, never on which evaluator ran them or on the shell's token,
   so every VM reports the same status for the same inputs."
  [indexer]
  {:transactions (:transactions indexer)
   :published? (and (some? (:manifest-address indexer))
                    (= (:published indexer) (:transactions indexer)))
   :lost? (:lost? indexer)
   :gaps (:ingress-gaps (:observer indexer) 0)
   :failure (:failure indexer)})


(defn notice
  "What the round from `before` to `after` must tell the user, or nil: a
   lost indexer (the round's code was not indexed), or a new failure (the
   round's code was not committed or its publication is not readable).
   A failure's `:stage` is `:project`, `:transact`, `:materialize` or
   `:publish`."
  [before after]
  (let [{:keys [stage outcome message]} (:failure after)]
    (cond
      (:lost? after)
      (str "Warning: the code index lost a program batch; this round's code"
           " was not indexed, and indexing is suspended until (reset)")

      (not= (:failure before) (:failure after))
      (str "Warning: this round's code is not in the published code index ("
           (name stage) " failed"
           (when-let [detail (or message (some-> outcome name))]
             (str ": " detail))
           ")"))))
