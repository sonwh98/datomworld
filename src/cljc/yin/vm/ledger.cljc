(ns yin.vm.ledger
  "The ledger: derivation records, ledger events, and naming rows
   (`docs/design/yin.vm.code-as-tuples.md` §5.2, §8).

   The hash chain between a tree and the segment lowered from it lives
   here, not in the code content: neither value carries the other's
   address (§5.2), so the link is a `:derive` record — a plain map,
   content-addressed like any other value — published as a ledger event
   entity, with the tree and the segment named by address.

   A ledger is written over a `dao.space` transactor value. Ledger
   tx-data is dao.space tx-data (`[:db/add e a v m]` vectors, tempids in
   `e` and `m`); `transact!` resolves it against the retained history and
   commits the resolved datoms through the transactor, which owns
   transaction time and appends them as one atomic record. A naming
   datom's `m` names its event entity through the metadata-entity path
   (§8.2): the transactor's tempid resolution replaces an `m` that is a
   tempid with the event's allocated id.

   Reading is `dao.space.query`: naming rows through the `current` view
   and its as-of bound (§8.6), provenance walks as joins over event
   entities, and the raw `m` of a naming row through the `history` view —
   latest event per `[e a v]` selected with `max ?t` first, its `m`
   interpreted second, never a hand-written negation (§8.6).

   Scope: the `:derive` record and event. The `:expand` record (§8.4)
   waits on the v2 expander, the `:publish` record on UCF custody — this
   namespace mints neither."
  (:require [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.space.index :as index]
            [dao.space.query :as query]
            [dao.space.transact :as transact]
            [dao.space.transactor :as transactor]
            [yin.vm :as vm]
            [yin.vm.code :as code]
            [yin.vm.linearize :as linearize]))


(def lowering-profile
  "The lowering profile this repository implements, exactly as published
   in §5.2.1: `\"ast-to-bytecode\"` over the UCF §7.3.3 stamp
   `{:yin.code/contract \"v2\", :yin.k/version 0}`. A `:derive` record
   carries this map verbatim, and `verify-derivation` recomputes a
   derivation only when the record's profile is `=` to the profile the
   consumer implements (§5.2.2 step 2)."
  {:yin.lower/profile "ast-to-bytecode"
   :yin.code/contract "v2"
   :yin.k/version 0})


(defn derive-record
  "§5.2/§8.2's `:derive` record: `output` was computed from `input` by
   `:yin.vm/lower` under `lowering-profile`. A plain map, so it is
   content-addressed by `dao.jing/segment-key`, immutable, and
   verifiable; its own address is what the ledger publishes. The
   addresses are the only coordinates — the record holds no log-local
   id, so it is hashed once, when built."
  [input output]
  {:yin.ledger/op :derive
   :yin.ledger/input input
   :yin.ledger/output output
   :yin.ledger/function :yin.vm/lower
   :yin.ledger/profile lowering-profile})


(defn derive-event
  "§8.2's ledger event tx-data for one record: a local entity with the
   record's fields as attributes and the record's address as one more.
   `ev` is a tempid — distinct tempids for distinct events, whether in
   one transaction or several. The profile travels as the structured map,
   exactly as it appears in the record; no string rendering is defined."
  [ev {:yin.ledger/keys [op input output function profile] :as record}]
  [[:db/add ev :yin.ledger/op op]
   [:db/add ev :yin.ledger/input input]
   [:db/add ev :yin.ledger/output output]
   [:db/add ev :yin.ledger/function function]
   [:db/add ev :yin.ledger/profile profile]
   [:db/add ev :yin.ledger/record (jing/segment-key record)]])


(defn derive-lowering
  "The `:derive` material for one row-lane lowering, written by
   `linearize/lower-rows`'s caller (§5.2): `lower-rows` produces the
   vector and provenance and knows no ledger; its caller names the two
   addresses and mints the record. Returns the record, its address, the
   event tx-data for tempid `ev`, and the addresses the ledger names —
   `:tree-address` is the row set's root id, `:segment-address` the
   vector's `(jing/segment-key vector)`."
  [{:keys [root]} {:keys [vector]} ev]
  (let [segment (jing/segment-key vector)
        record (derive-record root segment)]
    {:tree-address root
     :segment-address segment
     :record record
     :record-address (jing/segment-key record)
     :event (derive-event ev record)}))


(defn name-entity
  "§8.3's name entity — a local entity carrying `:yin/name` — as tx-data,
   written once when the entity is created. A later naming assertion or
   retraction goes through `assert-name`/`retract-name` on the same
   entity id."
  [name sym]
  [[:db/add name :yin/name sym]])


(defn assert-name
  "§8.3's naming row `[t :assert tree-addr name]` as tx-data:
   `[:db/add name :yin/code tree-addr]` on the name entity
   (`name-entity` writes its identity datom).

   With an `event` tempid shared with a `derive-event`'s tx-data, the
   assertion's `m` resolves to the event's allocated id — the provenance
   link from the naming datom to its event through the metadata-entity
   path (§8.2). `naming-event` walks it back. Without one, `m` defaults
   to the `:db/assert` marker."
  ([name tree-address]
   [[:db/add name :yin/code tree-address]])
  ([name tree-address event]
   [[:db/add name :yin/code tree-address event]]))


(defn retract-name
  "§8.3's naming retraction `[t :retract tree-addr name]` as tx-data: the
   reserved `:db/retract` marker in `m`. A validity op is expressed by
   the reserved marker, never by an event (§8.2), so a retraction carries
   no provenance link; a later assertion of the same address supersedes
   it under the fold's latest-event-wins (§8.6)."
  [name tree-address]
  [[:db/add name :yin/code tree-address (:db/retract datom/reserved)]])


(defn transact!
  "Commit ledger tx-data over a `dao.space` transactor value.
   `dao.space.transact/prepare-tx` resolves tempids — an event tempid in
   `e`, a naming datom's `m` tempid naming its event — and implicit
   cardinality-one retractions against the retained history; the
   transactor then stamps `t` and appends the resolved datoms as one
   atomic transaction record.

   Returns `transactor/transact!`'s receipt with prepare-tx's `:tempids`
   merged under `:dao.space/tempids`, so a caller keeps the allocated
   ids — a name entity is retracted and reasserted through its id, never
   a fresh one."
  [log tx-data]
  (let [prepared (transact/prepare-tx
                   {:base-datoms (index/snapshot-datoms (:local-stream log))
                    :tx-data tx-data})]
    (assoc (transactor/transact! log (mapv #(assoc % 3 nil) (:datoms prepared)))
           :dao.space/tempids (:tempids prepared))))


(defn verify-derivation
  "§5.2.2's verification of a derivation, in two separate steps, before a
   segment is trusted.

   1. **Content integrity.** The claimed root id is verified against the
      root row and every row of the set against its own body — after
      `validate-rows`, every row is the reachable closure — and the
      vector against the record's `:yin.ledger/output`. A mismatch
      reports `{:kind :yin.k/hash-mismatch :value <claimed-address>}`.
   2. **Derivation.** Only a consumer implementing the record's profile
      exactly recomputes: it re-lowers the row set and the resulting
      address must equal `:yin.ledger/output`. Inequality reports
      `{:kind :yin.k/derivation-mismatch :output <claimed> :actual
      <re-lowered>}` — a defective or dishonest lowering, never repaired
      silently. A record under another profile reports `{:kind
      :yin.k/profile-mismatch :record <p> :implemented <p>}` and
      verifies nothing further; the vector may still load, because step
      1 is independent of who lowered it.

   `tree` is the projected row set, `vector` the canonical instruction
   vector, `record` a `:derive` record map, `implemented` the consumer's
   profile (defaults to `lowering-profile`). Returns nil when both steps
   pass. A structural defect of tree or vector is reported as the §7.4
   or §7.5 defect itself, unchanged: validation names its rule, and a
   correct hash is never structural validation."
  ([tree vector record]
   (verify-derivation tree vector record lowering-profile))
  ([{:keys [root rows] :as tree} vector record implemented]
   (when (not= :derive (:yin.ledger/op record))
     (throw (ex-info "verify-derivation takes a :derive record"
                     {:op (:yin.ledger/op record)})))
   (let [row-address (fn [id]
                       (jing/segment-key (subvec (get rows id) 1)))
         hash-mismatch (fn [value] {:kind :yin.k/hash-mismatch :value value})]
     (or (vm/validate-rows tree)
         (when-not (= root (row-address root)) (hash-mismatch root))
         (some (fn [id] (when-not (= id (row-address id)) (hash-mismatch id)))
               (keys rows))
         (code/well-formed-vector? vector)
         (let [output (:yin.ledger/output record)]
           (when-not (= output (jing/segment-key vector))
             (hash-mismatch output)))
         (let [profile (:yin.ledger/profile record)]
           (when-not (= profile implemented)
             {:kind :yin.k/profile-mismatch
              :record profile
              :implemented implemented}))
         (let [output (:yin.ledger/output record)
               actual (jing/segment-key
                        (:vector (linearize/lower-rows tree)))]
           (when-not (= output actual)
             {:kind :yin.k/derivation-mismatch
              :output output
              :actual actual}))))))


(defn resolve-name
  "§8.6's current value of a name: the tree address its name entity
   refers to under `:yin/code`, through the `current` view — the fold
   resolves the greatest `t` per `[e a v]` and removes retractions, so a
   retracted-and-reasserted address survives, and a retracted one
   resolves to nil. With `t`, the view is as-of bounded: visible datoms
   are bounded to `t <= as-of` before folding.

   `source` is the refs history as raw d5 rows — what
   `dao.space.index/snapshot-datoms` answers over the local stream.
   Answers the address, or nil when the name resolves to nothing."
  ([source sym] (resolve-name source sym nil))
  ([source sym t]
   (query/collect
     (query/q '[:find ?addr . :in $ ?name
                :where [?e :yin/name ?name] [?e :yin/code ?addr]]
              (query/current (query/relation source) t)
              sym))))


(defn segment->tree
  "§8.6's provenance walk, the `:derive` half: the tree address a segment
   was lowered from, as a join over the ledger's `:derive` event
   entities. `db` is a query value over the refs history — usually
   `(query/current refs)`. Answers the tree address, or nil when no
   derivation names the segment. The `:expand` half of the walk's
   published query joins through the expander's events and waits on the
   v2 expander with them."
  [db segment-address]
  (query/collect
    (query/q '[:find ?tree . :in $ ?seg
               :where [?d :yin.ledger/op :derive]
               [?d :yin.ledger/output ?seg]
               [?d :yin.ledger/input ?tree]]
             db segment-address)))


(defn naming-event
  "The event entity a naming datom's `m` names — §8.2's metadata-entity
   path walked back. Per §8.6's rule for raw rows: the latest event per
   `[e a v]` is selected with `max ?t` first, through the `history` view
   over `source` (raw d5 rows), and that row's `m` is interpreted second.
   Answers the event entity id, the `:db/assert` marker for an
   unattributed assertion, or the `:db/retract` marker when the latest
   event is a retraction."
  [source entity tree-address]
  (query/collect
    (query/q '[:find ?m . :in $ ?e ?addr
               :where [?e :yin/code ?addr ?t ?m]
               (not-join [?e ?addr ?t]
                         [?e :yin/code ?addr ?t2 ?m2]
                         [(> ?t2 ?t)])]
             (query/history (query/relation source))
             entity tree-address)))
