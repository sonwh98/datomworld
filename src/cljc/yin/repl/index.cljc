(ns yin.repl.index
  "The shell's code indexer: a `dao.space.index` observer on `program-out`
   (docs/design/yin.repl.dao.space-index.md).

   It is a peer of the evaluator's observer on the same medium, attached
   independently and advanced by the evaluation round, never by the
   evaluator: neither knows the other.  Each expanded tree packet `[root
   rows]` it observes is projected to local `[e a v t m]` AST facts
   (yin.vm.code-as-tuples.md §6.5) and committed as ONE atomic transaction
   record through a `dao.space.transactor`, which allocates `t`; this
   namespace supplies no clock.  Every row of a committed packet is
   materialized into the `dao.jing` store in the same round, so its
   `:yin/address` facts name content the store holds (yin.vm.linker.dht.md
   5.1, rows every round).  After a round that committed, the covered
   indexes are published through `dao.space.index` into that round's
   own fresh intake, the intake is drained into the `dao.jing` store, the
   manifest is read back, and the intake is dropped. Between rounds the
   composition retains the transactor's allocator and a verified covered
   frontier: its log cursor, manifest and transaction count. Each fresh
   publication folds only the committed suffix into the previous covered
   roots and emits their dirty subgraph through `dao.space.index`.

   A round publishes as data, never through a callback
   (docs/design/yin.repl.dht-composition.md sections 3-4): once its
   content materialized and its manifest read back, it appends a
   correlated `{:op :publish :id :round :manifest}` request to the
   composition's publication medium and holds the frontier it prepared on
   `:pending-publications`.  Only the matching `{:op :publication-result
   ... :status :published}` read back from that medium promotes it; a
   `:refused` result preserves the previous frontier and reports the
   round unpublished, and a duplicate, stale or mismatched result
   promotes nothing.  This namespace holds no writer and calls none: the
   medium it is given is `yin.repl.publication/engine-media`, the request
   and result sides with the composition's durable HEAD writer stripped
   off, and whoever owns the medium services the request — a composition
   that services locally does so in the round that emitted it, so local
   publication still completes synchronously, and one with transport of
   its own delivers its result later, and no round waits on it.  Requests
   are serialized: a round settles every result already on the medium
   before it emits its own, and an outstanding request keeps its prepared
   frontier until a matching result settles it, so a newer commit never
   discards an older round's success.  An indexer given no medium — a
   store that declares no durable HEAD — settles its own round at once,
   exactly as an ephemeral composition always has.  Stream append success
   means the request was accepted, never that publication completed, and a
   network dissemination result is a different outcome from local
   durability: only the latter settles a round.

   Initial publication and recovery reconstruct the full authoritative
   history; no saved process cursor or allocator is trusted on restart.
   Incremental tree mutation removes repeated prefix construction, but
   does not promise a fixed wall-time bound on hashing, storage or other
   round work.

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
   session.  Over a durable store a new process's indexer is seeded from
   the recovered snapshot (`rehydrate`), and a rebuilt session's continues
   the old one's index (`carry-over`), so `t` and entity allocation never
   restart there."
  (:require [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.space.index :as index]
            [dao.space.transactor :as transactor]
            [dao.stream :as stream]
            [dao.stream.memory-log :as memory-log]
            [dao.stream.observer :as observer]
            [yin.repl.publication :as publication]
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
     :root root
     :row-set row-set}))


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

   `publication-media` is the composition's durable-publication medium
   (`yin.repl.publication`): the request side a round appends its
   `{:op :publish ...}` to and the result side it reads the matching
   `{:op :publication-result ...}` from.  Only those two sides and the
   result cursor are retained here — a `:local` durable HEAD writer on the
   supplied medium is the composition's to keep and service
   (`yin.repl.publication/service-local!`), never the indexer's to call —
   so the indexer holds no callback.  Nil, a store that declares no durable
   HEAD, settles every round locally at once.

   `:allocator` retains only the transactor authority, with its intake
   detached. `:materialization` retains verified coverage as plain data:
   `{:cursor c :transactions n :manifest-address a :manifest m :max-t t}`.
   `:publication-seq` is the session's publication-request counter, so
   every `:id` it emits is unique within the session;
   `:pending-publications` is the serialized queue of the requests no
   result has settled yet, oldest first, each `{:request r :promotes
   frontier}`.  A round's fresh `:publication` intake, pool and
   tree builder are dropped at its end; no earlier publication payload is
   retained."
  [{:keys [observer session-token content-store publish-opts publication-media]}]
  {:observer observer
   :local (memory-log)
   :content-store content-store
   :session-token session-token
   :publish-opts publish-opts
   :publication-media (some-> publication-media publication/engine-media)
   :publication-seq 0
   :pending-publications []
   :next-e datom/first-user-id
   :transactions 0
   :published 0
   :published-payloads 0
   :manifest-address nil
   :allocator nil
   :materialization nil
   :lost? false
   :failure nil})


(defn- next-entity-id
  "One greater than the greatest entity or metadata-entity id `datoms`
   use, never below `datom/first-user-id`."
  [datoms]
  (reduce (fn [next-e [e _a _v _t m]]
            (cond-> next-e
              (integer? e) (max (inc e))
              (integer? m) (max (inc m))))
          datom/first-user-id
          datoms))


(defn rehydrate
  "Seed a fresh indexer from a recovered durable snapshot, `{:manifest
   address :datoms [...]}` (yin.repl.store's `:recovery`), before any
   evaluation is admitted.  Its log becomes a fresh complete-retention
   memory log holding the recovered datoms as transaction records, one
   per original `t` in ascending order, each datom and its `t` preserved,
   so a new transactor derives the next `t` from it. Entity
   allocation resumes one past the greatest restored entity or metadata
   id.  The manifest is the published one, covering every restored
   transaction, so `q` answers from it before anything new is evaluated.
   Restored facts keep their original session tokens and round metadata:
   they are the datoms as committed.  A recovery without a manifest is an
   empty index. Live allocation and publication successors are always
   discarded, including any stale or foreign cursor: the first subsequent
   publication takes the full reconstruction path and derives a new cursor
   from this log, never from a saved process checkpoint."
  [indexer {:keys [manifest datoms]}]
  (if (nil? manifest)
    (assoc indexer :allocator nil :materialization nil)
    (let [log (memory-log)
          by-t (group-by index/datom-t datoms)
          ts (sort (keys by-t))]
      (doseq [t ts]
        (let [answer (stream/append! log {:dao.space/transaction
                                          {:t t, :datoms (vec (get by-t t))}})]
          (when-not (= :dao.stream/ok (:dao.stream/outcome answer))
            (throw (ex-info "the recovered snapshot could not be rehydrated"
                            {:t t, :outcome answer})))))
      (assoc indexer
             :local log
             :next-e (next-entity-id datoms)
             :transactions (count ts)
             :published (count ts)
             :allocator nil
             :materialization nil
             :manifest-address manifest))))


(defn carry-over
  "A rebuilt session's fresh `indexer`, continuing `previous`'s index: its
   transaction log, entity allocation, counts, published manifest, and
   live allocator/verified materialization successors.
   `(reset)` and VM selection over a durable store use it, so a rebuild
   never resets `t` or entity allocation and `q` keeps answering the
   published index; the observer is the new session's, and a lost or
   failed indexer is recovered by the rebuild as it is today."
  [indexer previous]
  (merge indexer
         (select-keys previous [:local :next-e :transactions :published
                                :published-payloads :manifest-address
                                :allocator :materialization :unwritten])))


(defn- open-publication
  "The round's transactor and the `dao.jing` pool observer over its intake.
   The retained allocator remains the transactor's authority; when absent
   or bound to another log, create! derives it from authoritative history.
   No caller-supplied clock is accepted. The intake is
   complete-retention: `publish-index!` appends every node blob before the
   manifest and the round drains only after it returns, so an evicting
   intake would lose the head of any publication larger than its capacity
   and that publication could never complete."
  [ix]
  (if (:publication ix)
    ix
    (let [intake (memory-log)
          allocator (:allocator ix)
          tx (if (and allocator
                      (identical? (:local ix) (:local-stream allocator)))
               (transactor/with-intake-pool allocator [intake])
               (transactor/create! {:local-stream (:local ix)
                                    :intake-pool [intake]
                                    :name "yin.repl.index"}))]
      (assoc ix
             :allocator (transactor/with-intake-pool tx nil)
             :publication
             {:transactor tx
              :intake intake
              :pool (jing/observer-state
                      [{:stream intake
                        :cursor (:dao.stream/cursor
                                  (stream/cursor intake
                                                 :dao.stream/oldest))}])}))))


(defn- write-rows
  "Put the rows of every committed program not yet written into the index
   store, oldest first (yin.vm.linker.dht.md 5.1, owner decision 1: rows
   every round), so `:yin/address` facts name content the store holds.
   `:unwritten` holds those programs' row sets.  A put that fails stops
   the writing: it and every later row set stay `:unwritten`, and the
   round's failure is stage `:materialize`.  Once nothing is unwritten, a
   `:materialize` failure is recovered and cleared.  No publication is
   made while anything is unwritten, so a HEAD never moves over a missing
   row."
  [ix round]
  (loop [ix ix]
    (if-let [row-set (first (:unwritten ix))]
      (let [failed (try (vm/materialize-tree! (:content-store ix) row-set)
                        nil
                        (catch #?(:cljd Object :clj Exception :cljs js/Error) e
                          {:round round
                           :stage :materialize
                           :root (:root row-set)
                           :message (ex-message e)}))]
        (if failed
          (assoc ix :failure failed)
          (recur (update ix :unwritten (comp vec rest)))))
      (cond-> (dissoc ix :unwritten)
        (= :materialize (:stage (:failure ix))) (dissoc :failure)))))


(defn- commit
  "Commit one packet as one transaction; its rows join `:unwritten`, which
   the round writes before it publishes (`write-rows`).  A packet that
   could not be projected or committed is consumed and recorded as the
   failure, never counted, and materializes nothing."
  [ix packet round]
  (try
    (let [{:keys [tx-data next-e root row-set]}
          (packet->tx-data packet
                           (:next-e ix)
                           {:session-token (:session-token ix), :round round})
          answer (transactor/transact! (get-in ix [:publication :transactor])
                                       tx-data)]
      (if (= :dao.stream/ok (:dao.stream/outcome answer))
        [(-> ix
             (assoc :next-e next-e)
             (update :transactions inc)
             (update :unwritten (fnil conj []) row-set))
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
        :dao.stream/ok
        (let [missing #?(:cljd (Object.) :clj (Object.) :cljs (js-obj))]
          ;; Every emitted dirty node reads back before HEAD moves. The
          ;; unchanged subgraphs were verified by earlier publications;
          ;; immutable retained content makes that verification reusable.
          (when (identical? missing (jing/get content-store (:address r) missing))
            (throw (ex-info "published content could not be read back"
                            {:address (:address r)})))
          (recur (:state r) (inc n)))
        :dao.stream/blocked [n nil]
        [n signal]))))


(defn- suffix-state
  "Restore verified roots and fold only records after their cursor. The
   expected record count independently validates the cursor against this
   composition's one-record-per-transaction log. A stale/foreign cursor,
   mismatched frontier or rejected record returns nil: the caller must
   reconstruct in full, never publish an uncertain suffix."
  [ix intake]
  (let [{:keys [cursor transactions manifest-address manifest max-t]}
        (:materialization ix)]
    (when (and manifest-address
               (= manifest-address (:manifest-address ix))
               (= transactions (:published ix))
               (= (:branching-factor manifest)
                  (:branching-factor (:publish-opts ix) 512)))
      (try
        (let [candidate {:manifest-address manifest-address
                         :mode :resolved :schema-address (jing/segment-key {})
                         :ids nil :batch transactions :max-t max-t :rejected 0}
              consumer (index/restore candidate
                                      {:content-store (:content-store ix)
                                       :intake intake :mode :resolved :schema {}})
              expected (- (:transactions ix) transactions)]
          (loop [cursor cursor, consumer consumer, n 0]
            (let [r (stream/next (:local ix) cursor)]
              (when (stream/valid-outcome? :next r)
                (case (:dao.stream/outcome r)
                  :dao.stream/ok
                  (when (< n expected)
                    (let [consumer' (index/fold-batch consumer (:dao.stream/value r))]
                      (when (zero? (:rejected consumer'))
                        (recur (:dao.stream/cursor r) consumer' (inc n)))))
                  (:dao.stream/blocked :dao.stream/end)
                  (when (= n expected) {:cursor cursor :consumer consumer})
                  nil)))))
        (catch #?(:cljd Object :clj Exception :cljs js/Error) _
          nil)))))


(defn- enqueue-publication
  "Full reconstruction when no validated frontier is available; otherwise
   publication of the suffix-folded trees through the existing indexer."
  [ix]
  (let [{:keys [transactor intake]} (:publication ix)]
    (if-let [{:keys [cursor consumer]} (suffix-state ix intake)]
      (let [;; A fresh round has exactly one intake; still honor the
            ;; caller's selection function and its pool-membership rule.
            select-stream (:select-stream (:publish-opts ix) first)
            selected (when (fn? select-stream) (select-stream [intake]))
            _ (when-not (identical? selected intake)
                (throw (ex-info "publication must select its round's intake" {})))
            {:keys [status state]} (index/publish! consumer)
            manifest (get-in state [:publish :manifest])]
        (when-not (= :ok status)
          (throw (ex-info "complete-retention publication intake refused" {:status status})))
        {:cursor cursor :manifest manifest :manifest-address (jing/segment-key manifest)})
      (let [result (transactor/publish! transactor (:publish-opts ix))
            tail (stream/cursor (:local ix) :dao.stream/newest)]
        (when-not (and (stream/valid-outcome? :cursor tail)
                       (= :dao.stream/ok (:dao.stream/outcome tail)))
          (throw (ex-info "published log frontier could not be read" {:result tail})))
        (assoc result :cursor (:dao.stream/cursor tail))))))


(defn- promote-frontier
  "`ix` with the frontier `promotes` reported: the manifest address,
   published counts and verified coverage a settled publication prepared
   become the round's, and a `:publish` failure that promotion answers is
   cleared.  Reached only for a matching `:published` result, or at once
   for an indexer with no medium, so a frontier never moves for a
   publication nobody settled."
  [ix promotes]
  (let [promoted (merge ix promotes)]
    (cond-> promoted
      (= :publish (:stage (:failure promoted))) (assoc :failure nil))))


(defn- advances?
  "Whether the frontier `promotes` is ahead of `ix`'s published one.  A
   publication covers the whole committed suffix, so a later one
   supersedes an earlier: a result delivered out of order behind a newer
   promotion has nothing left to promote, and answering it must neither
   regress the frontier nor report a failure that newer publication
   already answered."
  [ix promotes]
  (> (or (:published promotes) 0) (or (:published ix) 0)))


(defn- outstanding
  "The entry of `:pending-publications` the `result` answers — identifier,
   round and manifest address together — or nil: a duplicate, stale or
   foreign result matches nothing."
  [indexer result]
  (some (fn [entry]
          (let [request (:request entry)]
            (when (and (= (:id result) (:id request))
                       (= (:round result) (:round request))
                       (= (:manifest result) (:manifest request)))
              entry)))
        (:pending-publications indexer)))


(defn- settled-without
  "`indexer` with `entry`'s request off its outstanding queue."
  [indexer entry]
  (update indexer :pending-publications
          (fn [entries]
            (vec (remove #(= (:request %) (:request entry)) entries)))))


(defn accept-publication-result
  "`indexer` after one local publication `result`
   (`{:op :publication-result :id :round :manifest :status :durability}`)
   its composition delivered.

   Only a result matching an outstanding request — identifier, round and
   manifest address together — settles it: `:published` promotes the
   frontier that request prepared, exactly once, and `:refused` preserves
   the previous published frontier, recording the round's failure under
   the refusal's reason.  A duplicate, stale or mismatched result settles
   nothing, so a repeated or foreign result can never advance or regress
   the frontier; neither can a result delivered out of order behind a
   newer promotion, whose own frontier already covered its suffix.  Every
   other outstanding request keeps its place in the queue.  A network
   dissemination result is not this: local durability alone settles a
   round."
  [indexer result]
  (if-let [entry (outstanding indexer result)]
    (let [settled (settled-without indexer entry)
          promotes (:promotes entry)]
      (cond
        (not (advances? indexer promotes))
        settled

        (= :published (:status result))
        (promote-frontier settled promotes)

        (= :refused (:status result))
        (assoc settled
               :failure {:round (:round (:request entry))
                         :stage :publish
                         :message (or (:reason result)
                                      "the composition refused the publication")})

        :else
        settled))
    indexer))


(defn settle-publication
  "`indexer` after every publication result its medium already carries is
   consumed, oldest first.  A composition that services locally leaves
   nothing unread past its own round; one with transport of its own calls
   this once it has delivered, before it reports the round settled.  An
   indexer with no medium has no result side to read and answers itself."
  [indexer]
  (if-let [media (:publication-media indexer)]
    (if-let [[result cursor] (publication/next-result media)]
      (recur (accept-publication-result
               (assoc indexer
                      :publication-media (publication/advance media cursor))
               result))
      indexer)
    indexer))


(defn pending-publication
  "The oldest publication request this indexer emitted and no matching
   result has settled, or nil.  A composition reads it to know what it
   still owes the engine before it may report the round settled
   (docs/design/yin.repl.dht-composition.md section 4)."
  [indexer]
  (:request (first (:pending-publications indexer))))


(defn pending-publications
  "Every publication request this indexer emitted that no matching result
   has settled, oldest first.  A composition owes the engine each of them:
   one that services locally, in the round that emitted; one with
   transport of its own, before it reports the round settled.  An
   outstanding request of an earlier round is why a later round's own
   promotion has not been reported yet
   (docs/design/yin.repl.dht-composition.md sections 3-4)."
  [indexer]
  (mapv :request (:pending-publications indexer)))


(defn- emit-publication
  "Emit `round`'s publication request for the frontier `promotes` and hold
   that frontier on the outstanding queue until a matching result settles
   it.  Results already on the medium are consumed first, so a success the
   composition delivered promotes before a newer request is emitted over
   it, and a round still outstanding keeps its own prepared frontier
   beside this one instead of losing it.  A medium that refuses the append
   leaves the round reported unpublished, never promoting a frontier
   nobody was asked to publish.  Nothing here services the request:
   whoever owns the medium reads it and appends its result
   (`yin.repl/serve-publication`).  An indexer with no medium has no HEAD
   to ask: it promotes its own round, as an ephemeral composition always
   has."
  [ix round promotes]
  (if-let [media (:publication-media ix)]
    (let [ix (settle-publication ix)
          id (inc (:publication-seq ix))
          request (publication/request id round (:manifest-address promotes))]
      (if (publication/emit! media request)
        (assoc ix
               :publication-seq id
               :pending-publications (conj (:pending-publications ix)
                                           {:request request
                                            :promotes promotes}))
        (assoc ix
               :failure {:round round
                         :stage :publish
                         :message "the publication medium refused the request"})))
    (promote-frontier ix promotes)))


(defn- publish
  "Publish the covered indexes over every committed transaction, using
   the retained frontier when valid, drain the intake, and read the
   manifest back from it.  Only a publication whose manifest the store
   answers is offered for promotion, covering `:published` transactions;
   the durable HEAD moves — the composition's, over the publication
   medium — only after that read-back, so a HEAD never names a manifest
   the store cannot answer, and a HEAD that cannot be written leaves the
   round reported unpublished.  What this answers is the frontier
   prepared and its request emitted; `settle-publication` promotes it."
  [ix round]
  (try
    (let [{:keys [pool]} (:publication ix)
          {:keys [manifest-address manifest cursor]} (enqueue-publication ix)
          [n defect] (materialize (:content-store ix) pool)]
      (if defect
        (assoc ix :failure {:round round :stage :materialize :outcome defect})
        (do (index/read-manifest (:content-store ix) manifest-address)
            (emit-publication
              ix
              round
              {:manifest-address manifest-address
               :published (:transactions ix)
               :published-payloads n
               :materialization {:cursor cursor
                                 :transactions (:transactions ix)
                                 :manifest-address manifest-address
                                 :manifest manifest
                                 :max-t (dec (transactor/watermark
                                               (:allocator ix)))}}))))
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
   when any was committed, settle every publication result the medium
   already carries, then drop the publication's intake, pool and temporary
   trees. Only the detached allocator, the verified covered frontier and
   the publication medium continue into the next round. A gap is counted on
   the observer's `:ingress-gaps`, and marks the indexer lost: the missing
   packet and every later one are never called indexed.

   Servicing the request this round emitted is the medium owner's, not
   this namespace's: an unsettled request stays outstanding on
   `:pending-publications`, its prepared frontier with it, until the
   composition delivers a matching result."
  [indexer round]
  (let [ix (consume indexer
                    (fn [ix packet]
                      (first (commit (open-publication ix) packet round))))
        committed? (> (:transactions ix) (:transactions indexer))
        ;; this round's rows and any an earlier round could not write,
        ;; before any publication: no announcement names a missing row
        ix (if committed? (write-rows ix round) ix)
        ;; a round that published settles here, and so does a result a
        ;; composition with transport of its own delivered since the last
        ;; round — a step that committed nothing still consumes it
        ix (settle-publication (if (and committed? (empty? (:unwritten ix)))
                                 (publish ix round)
                                 ix))]
    (dissoc ix :publication)))


(defn commit-names
  "Commit the signed name envelopes `signed` -- each `{:envelope e :proof
   p}` -- as ONE transaction, then publish the covered indexes at once
   (docs/design/yin.vm.linker.dht.md 6.1): one entity per envelope
   carrying `:yin.module/envelope` and `:yin.module/proof`, with the
   session's metadata entity -- `:db/op`, the session token and `round`,
   no program root -- in `m`.  A transaction refused is recorded as the
   failure, like a packet's.

   The publication this emits is a request on the composition's medium,
   settled by the result that composition delivers
   (`accept-publication-result`): what answers here is the frontier
   prepared and the request outstanding, never a completed HEAD write.  A
   signed publication whose completion the caller must hear about services
   and settles the medium before it answers
   (docs/design/yin.repl.dht-composition.md sections 3-4)."
  [indexer signed round]
  (let [ix (open-publication indexer)
        meta-e (:next-e ix)
        untimed (fn [e a v m] [e a v nil m])
        tx-data (into [(untimed meta-e :db/op :db/assert datom/default-op)
                       (untimed meta-e :yin.repl/session (:session-token ix)
                                datom/default-op)
                       (untimed meta-e :yin.repl/round round datom/default-op)]
                      (comp (map-indexed
                              (fn [i {:keys [envelope proof]}]
                                (let [e (+ meta-e 1 i)]
                                  [(untimed e :yin.module/envelope envelope meta-e)
                                   (untimed e :yin.module/proof proof meta-e)])))
                            cat)
                      signed)
        answer (transactor/transact! (get-in ix [:publication :transactor])
                                     tx-data)]
    (dissoc (if (= :dao.stream/ok (:dao.stream/outcome answer))
              (let [ix (-> ix
                           (assoc :next-e (+ meta-e 1 (count signed)))
                           (update :transactions inc)
                           (write-rows round))]
                (if (empty? (:unwritten ix))
                  (settle-publication (publish ix round))
                  ix))
              (assoc ix
                     :failure {:round round
                               :stage :transact
                               :outcome (:dao.stream/outcome answer)}))
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
   lost indexer (the round's code was not indexed), the note that rows an
   earlier round could not write are now written, or a new failure (the
   round's code was not committed, or its publication was not readable or
   was refused).  A failure's `:stage` is `:project`, `:transact`,
   `:materialize` or `:publish`.  A round that cleared an earlier failure
   — a refused publication its retry settled — says nothing: there is
   nothing left to report, and rendering the cleared failure as a warning
   would roll the retry back."
  [before after]
  (let [{:keys [stage outcome message]} (:failure after)]
    (cond
      (:lost? after)
      (str "Warning: the code index lost a program batch; this round's code"
           " was not indexed, and indexing is suspended until (reset)")

      (nil? (:failure after))
      (when (= :materialize (:stage (:failure before)))
        (str "Note: the code index caught up: rows of earlier rounds are now"
             " written, and this round is published"))

      (not= (:failure before) (:failure after))
      (str "Warning: this round's code is not in the published code index ("
           (name (or stage :publication)) " failed"
           (when-let [detail (or message (some-> outcome name))]
             (str ": " detail))
           ")"))))
