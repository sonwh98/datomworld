(ns yin.vm.telemetry
  "The v2 telemetry emit path: full VM state snapshots as datoms on an
   explicit dao.stream sink.

   Opt-in through the :telemetry construction option
   ({:stream <writer> :vm-id <id>}, stored by yin.vm/empty-state and
   validated by install). A VM built without it is behaviorally identical
   to one with no telemetry at all: enabled? is false and emit-snapshot is
   identity on state, so every emit site in the engine, the FFI, and the
   two VMs stays a no-op.

   emit-snapshot fires at the runtime boundaries vm-telemetry-design.md
   names — construction :init, each successful step :step, each handled
   effect :effect, park :park, resume :resume, bridge dispatch :bridge,
   and the run loop's terminal :blocked and :halt. Each call emits one
   full snapshot, not a delta: a root :vm/snapshot entity carrying the VM
   facts and refs to component entities, every component and summary node
   an entity of its own, all datoms sharing one t, and the counters
   advancing once per snapshot (one snapshot = one transaction).

   Summaries are dao.data/summarize trees projected one-to-one onto the
   :vm.summary/* attribute vocabulary, so no datom ever carries a
   function, stream handle, or host object. Store summaries preserve
   stream identities and cursor positions; continuation summaries carry
   the parked set, so parked continuation identities survive.

   append! outcomes are read and discarded: :dao.stream/full drops that
   datom with no retry — telemetry is lossy by design, an observability
   side channel must not park or fail the VM it observes — and terminal
   outcomes are ignored rather than raised."
  (:require [dao.data :as data]
            [dao.datom :as datom]
            [dao.stream :as stream]))


(def summary-bounds
  "The fixed bounds every telemetry summary runs under. Deep enough that a
   store's stream identities and cursor positions — plain data two or
   three levels under the store map — always survive; wide and long
   enough that uuids and typical identifiers are never cut."
  {:depth 6, :items 16, :chars 128})


(defn summarize-control
  "Bounded plain-data summary of the control component (an AST node, a
   segment pointer, ...). A thin wrapper over dao.data/summarize: the
   wrapper fixes the bounds, dao.data does all the bounded traversal."
  [control]
  (data/summarize control summary-bounds))


(defn summarize-environment
  "Bounded plain-data summary of the lexical environment. Bindings are map
   entries, so names and values both survive as summarized nodes."
  [environment]
  (data/summarize environment summary-bounds))


(defn summarize-store
  "Bounded plain-data summary of the store. dao.data checks the stream
   descriptor before map?, so a stored handle summarizes as
   {:dao.data/type :stream :dao.data/identity …} rather than a collection,
   and a cursor entry's opaque cursor is a map whose identity and position
   are plain values: store membership, stream identity, and cursor
   position — what an analyzer needs — all survive these bounds."
  [store]
  (data/summarize store summary-bounds))


(defn summarize-continuation
  "Bounded plain-data summary of the active continuation together with the
   parked set. Parking identities are map keys (:parked-0, ...), so the
   summary names every parked continuation an analyzer could look for,
   which the active chain alone would not."
  ([continuation] (summarize-continuation continuation {}))
  ([continuation parked]
   (data/summarize {:active continuation, :parked (or parked {})} summary-bounds)))


(defn enabled?
  "True when the state carries a telemetry configuration with a stream.
   This is the predicate every emit site short-circuits on; a VM built
   without :telemetry answers false and emits nothing."
  [state]
  (boolean (get-in state [:telemetry :stream])))


(defn install
  "Finish installing telemetry on a freshly built state: record the model,
   validate the :telemetry configuration empty-state stored, and mint the
   instance id.

   With :telemetry absent this changes nothing but the model. With it
   present, :stream must name a dao.stream writer — a missing or
   non-writer stream is a construction error, not a silent no-op, because
   a silently unwritten sink is the one way an enabled VM could mislead.
   :vm-id is kept when the configuration supplies one and minted as a
   stable per-instance uuid string when not."
  [state model]
  (let [config (:telemetry state)]
    (when (some? config)
      (when-not (map? config)
        (throw (ex-info "The :telemetry option must be a map"
                        {:telemetry config})))
      (when-not (contains? config :stream)
        (throw (ex-info "Telemetry is enabled but names no :stream"
                        {:telemetry config})))
      (when-not (stream/writer? (:stream config))
        (throw (ex-info "The :telemetry stream is not a dao.stream writer"
                        {:telemetry config}))))
    (cond-> (assoc state :vm-model model)
      (some? config) (assoc :vm-id (or (:vm-id config) (str (random-uuid))))
      (nil? (:telemetry-step state)) (assoc :telemetry-step 0)
      (nil? (:telemetry-t state)) (assoc :telemetry-t 0)
      (nil? (:telemetry-eid state)) (assoc :telemetry-eid datom/first-user-id))))


(defn next-telemetry-state
  "Advance the telemetry counters after one emitted snapshot:
   :telemetry-step and :telemetry-t by one — one snapshot, one
   transaction, so t stays constant within a snapshot — and :telemetry-eid
   past the highest entity id the snapshot minted, so the next snapshot's
   entity ids cannot collide with this one's."
  ([state] (next-telemetry-state state nil))
  ([state datoms]
   (let [with-eid (if (seq datoms)
                    (assoc state
                           :telemetry-eid (inc (reduce max (map first datoms))))
                    state)]
     (-> with-eid
         (update :telemetry-step (fnil inc 0))
         (update :telemetry-t (fnil inc 0))))))


(defn- summary-tag
  "The :vm.summary/type value for a dao.data tag. The two host-object tags
   take the names the design commits to; every other tag mirrors dao.data
   one-to-one."
  [tag]
  (case tag
    :fn :vm.summary/host-fn
    :opaque :vm.summary/opaque
    tag))


(declare summary-entity)


(defn- summary-entity
  "One summary node as datoms: the node's own facts under eid, then its
   children's entities depth-first. Child entity ids are minted from pool
   in emission order, so a node's datoms stay contiguous and its block is
   followed by its children's blocks.

   The dao.data → :vm.summary/* projection, key for key:
     :dao.data/type       → :vm.summary/type       (via summary-tag)
     :dao.data/value      → :vm.summary/value      (a scalar)
     :dao.data/truncated? → :vm.summary/truncated?
     :dao.data/count      → :vm.summary/count
     :dao.data/identity   → :vm.summary/identity   (a ref)
     :dao.data/items      → :vm.summary/item       (many refs)
     :dao.data/entries    → :vm.summary/entry      (many refs), each entry
                            entity carrying :vm.summary/key and
                            :vm.summary/value-ref refs to its nodes."
  [node eid t pool]
  (let [fact (fn [a v] [eid a v t datom/default-op])
        items (:dao.data/items node)
        entries (:dao.data/entries node)
        identity-node (:dao.data/identity node)
        identity-eid (when (some? identity-node) (swap! pool inc))
        item-eids (when items (mapv (fn [_] (swap! pool inc)) items))
        entry-eids (when entries (mapv (fn [_] (swap! pool inc)) entries))
        own (cond-> [(fact :vm.summary/type (summary-tag (:dao.data/type node)))]
              (contains? node :dao.data/value)
              (conj (fact :vm.summary/value (:dao.data/value node)))

              (contains? node :dao.data/truncated?)
              (conj (fact :vm.summary/truncated? (:dao.data/truncated? node)))

              (contains? node :dao.data/count)
              (conj (fact :vm.summary/count (:dao.data/count node)))

              identity-eid (conj (fact :vm.summary/identity identity-eid))

              item-eids
              (into (map (fn [child-eid] (fact :vm.summary/item child-eid)))
                    item-eids)

              entry-eids
              (into (map (fn [entry-eid] (fact :vm.summary/entry entry-eid)))
                    entry-eids))
        entry-blocks
        (mapcat (fn [entry-eid [key-node value-node]]
                  (let [key-eid (swap! pool inc)
                        value-eid (swap! pool inc)]
                    (concat [[entry-eid :vm.summary/key key-eid t datom/default-op]
                             [entry-eid :vm.summary/value-ref value-eid t datom/default-op]]
                            (summary-entity key-node key-eid t pool)
                            (summary-entity value-node value-eid t pool))))
                entry-eids
                entries)
        item-blocks
        (when items
          (mapcat (fn [child child-eid] (summary-entity child child-eid t pool))
                  items item-eids))]
    (vec (concat own
                 entry-blocks
                 item-blocks
                 (when identity-node
                   (summary-entity identity-node identity-eid t pool))))))


(defn snapshot-datoms
  "The ordered datom vector of one full snapshot of state at boundary
   phase with opts: a root :vm/snapshot entity with the VM facts and
   component refs, then the value and component summary entities. Pure —
   it reads the counters, mints entity ids upward from :telemetry-eid
   (never below datom/first-user-id), gives every datom the current
   :telemetry-t as its t, and touches no stream.

   The CESK components are read by the field names every VM built on
   yin.vm/empty-state carries (:control, :env, :store, :k, :parked) — the
   same values the IVMState accessors project — because this namespace
   cannot require yin.vm back without a require cycle the ClojureScript
   build would reject.

   An absent component (no control; no continuation and nothing parked) is
   omitted, never emitted as a nil ref. Phase opts ride along as one extra
   root fact each under :vm/<name>, except :parked-id, which describes the
   continuation component and is folded onto it."
  ([state phase] (snapshot-datoms state phase nil))
  ([state phase opts]
   (let [t (or (:telemetry-t state) 0)
         pool (atom (dec (or (:telemetry-eid state) datom/first-user-id)))
         mint! (fn [] (swap! pool inc))
         root (mint!)
         d (fn [e a v] [e a v t datom/default-op])
         value-eid (mint!)
         control (some-> state :control summarize-control)
         control-eid (when control (mint!))
         environment (some-> state :env summarize-environment)
         environment-eid (when environment (mint!))
         store (some-> state :store summarize-store)
         store-eid (when store (mint!))
         continuation
         (when (or (some? (:k state))
                   (seq (:parked state))
                   (contains? opts :parked-id))
           (summarize-continuation (:k state) (:parked state)))
         continuation-eid (when continuation (mint!))
         vm-id (:vm-id state)
         model (:vm-model state)
         root-datoms
         (cond-> [(d root :vm/type :vm/snapshot)
                  (d root :vm/phase phase)
                  (d root :vm/step (or (:telemetry-step state) 0))
                  (d root :vm/value value-eid)
                  (d root :vm/blocked? (boolean (:blocked? state)))
                  (d root :vm/halted? (boolean (:halted? state)))]
           vm-id (conj (d root :vm/vm-id vm-id))
           model (conj (d root :vm/model model))
           control-eid (conj (d root :vm/control control-eid))
           environment-eid (conj (d root :vm/environment environment-eid))
           store-eid (conj (d root :vm/store store-eid))
           continuation-eid (conj (d root :vm/continuation continuation-eid)))
         opt-datoms
         (mapv (fn [[k v]] (d root (keyword "vm" (name k)) v))
               (dissoc opts :parked-id))
         continuation-datoms
         (when continuation-eid
           (if-some [parked-id (:parked-id opts)]
             (conj (summary-entity continuation continuation-eid t pool)
                   (d continuation-eid :vm/parked-id parked-id))
             (summary-entity continuation continuation-eid t pool)))]
     (vec (concat root-datoms
                  opt-datoms
                  (summary-entity (data/summarize (:value state) summary-bounds)
                                  value-eid t pool)
                  (when control-eid
                    (summary-entity control control-eid t pool))
                  (when environment-eid
                    (summary-entity environment environment-eid t pool))
                  (when store-eid (summary-entity store store-eid t pool))
                  continuation-datoms)))))


(defn emit-snapshot
  "Emit one full snapshot at a runtime boundary: identity on state when
   telemetry is disabled; otherwise snapshot-datoms appended to the sink
   and the counters advanced once. Both arities survive — the boundaries
   name their phase with or without extra context opts."
  ([state phase] (emit-snapshot state phase nil))
  ([state phase opts]
   (if-not (enabled? state)
     state
     (let [datoms (snapshot-datoms state phase opts)
           sink (get-in state [:telemetry :stream])]
       (doseq [snapshot-datom datoms]
         (stream/append! sink snapshot-datom))
       (next-telemetry-state state datoms)))))
