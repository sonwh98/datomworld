(ns yin.vm.v2.engine
  "Shared evaluation machinery for Yin VMs on DaoStream v2.

   What the port changed, and why:

   - **The host supplies streams.** `:stream/make` calls the composition's
     `:make-stream`; there is no transport identity in this namespace. Every
     other stream handler is a protocol call on a handle already in the store.
   - **Cursors are opaque.** A store cursor entry is
     `{:stream-id id :cursor <opaque>}`. Nothing increments one.
   - **Outcomes are maps, and every handler is total over its closed set.**
     `blocked` and `full` park; `end` and `gap` are values the program sees;
     the rest are errors that name their outcome.
   - **Waiters are gone.** No v2 transport is waitable, so the polling wait
     set is the mechanism rather than a fallback. Cadence comes from the
     driver above the VM.
   - **`:stream/take` is gone.** Destructive read needs a reader position in
     the medium, which the contract retired.
   - **The module registry is a value** carried in VM state, so effect
     dispatch and `resolve-var` read a supplied registry rather than a global.
   - **Program observation is not engine work.** `ready-for-ingress?` lives
     here because it speaks the scheduler's own vocabulary, but the program
     handle, cursor, and gap count belong to `dao.stream.v2.observer`;
     nothing in this namespace polls a program stream."
  (:refer-clojure :exclude [gensym])
  (:require [clojure.set]
            [dao.datom :as datom]
            [dao.runtime.v2 :as rt]
            [dao.stream.v2 :as stream]
            [yin.vm.v2 :as vm]
            [yin.vm.v2.module :as module]
            [yin.vm.v2.runtime-adapter :as adapter]
            [yin.vm.v2.telemetry :as telemetry]))


(defn- outcome
  [result]
  (:dao.stream/outcome result))


(defn- fail
  [message data]
  (throw (ex-info message data)))


(defn resolve-var
  "Look up a variable name: env -> store -> primitives -> module registry.

   The registry is a value supplied by the composition, not a global."
  [env store primitives registry name]
  (if-let [pair (find env name)]
    (val pair)
    (if-let [pair (find store name)]
      (val pair)
      (if-let [pair (find primitives name)]
        (val pair)
        (if-let [resolved (when (namespace name)
                            (module/resolve-module
                              registry
                              (symbol (str (namespace name)
                                           "."
                                           (clojure.core/name name)))))]
          resolved
          (fail (str "Unable to resolve symbol: " name " in this context")
                {:symbol name}))))))


(defn- gen-id
  "Generate a unique keyword ID from a prefix and counter value."
  [prefix id-counter]
  (keyword (str prefix "-" id-counter)))


(defn vm-blocked?
  "Returns true if the VM is blocked."
  [vm]
  (boolean (:blocked? vm)))


(defn vm-value
  "Returns the current value of the VM."
  [vm]
  (:value vm))


(defn halted-with-empty-queue?
  "Returns true if the VM has halted and its ready-queue is empty."
  [vm]
  (and (boolean (:halted? vm)) (empty? (or (:ready-queue vm) []))))


(defn restore-initial-env
  "After eval, restore :env to initial-env when the computation halted.
   Blocked and parked states keep the active lexical env for resumption."
  [initial-env result]
  (if (halted-with-empty-queue? result) (assoc result :env initial-env) result))


(defn active-continuation?
  "Returns true when the currently active continuation should keep stepping."
  [vm]
  (and (not (:blocked? vm)) (not (:halted? vm))))


(defn ready-for-ingress?
  "Returns true when the VM is between evaluations and can accept another
   program batch: not blocked, nothing scheduled or waiting, no active
   continuation, and no loaded work.

   This predicate gates observer coordination above the VM. The VM itself
   never polls a program stream: `step` and `run` execute loaded work only."
  [vm]
  (let [has-bytecode? (contains? vm :bytecode)
        bytecode (:bytecode vm)
        no-bytecode? (and has-bytecode?
                          (or (nil? bytecode)
                              (and (sequential? bytecode) (empty? bytecode))))]
    (and (not (:blocked? vm))
         (empty? (or (:ready-queue vm) []))
         (empty? (or (:wait-set vm) []))
         (nil? (:k vm))
         (or (:halted? vm) (nil? (:control vm)) no-bytecode?))))


(defn make-woken-run-queue-entries
  "Transform woken wait-set entries into ready-queue entries.
   Readers (with :cursor-ref) store the successor cursor the transport
   returned. Writers (no :cursor-ref) just stamp :value."
  ([state woken] (make-woken-run-queue-entries state woken nil))
  ([state woken restore-fn]
   (mapv (fn [{:keys [entry value cursor], :as woken-entry}]
           (let [cursor-ref (:cursor-ref entry)
                 store-updates
                 (or (:store-updates woken-entry)
                     (when (and cursor-ref cursor)
                       (let [cursor-id (:id cursor-ref)
                             cursor-data (get (:store state) cursor-id)]
                         {cursor-id (assoc cursor-data :cursor cursor)})))
                 base-entry (assoc entry
                                   :value value
                                   :store-updates store-updates
                                   :cursor cursor)]
             (if restore-fn
               (let [task (adapter/vm-task base-entry restore-fn)]
                 (assoc task
                        :value value
                        :cursor cursor))
               base-entry)))
         woken)))


(defn handle-make
  "Handle :stream/make. Creates a stream through the composition's
   `:make-stream` and stores the handle.

   A nil capacity has no v2 meaning, so the module path defaults exactly as
   the AST path does. Absent a supplied constructor this fails and says so;
   silently falling back to a private transport is the failure mode the rule
   exists to prevent.
   Returns [stream-ref updated-state]."
  [state effect id]
  (let [capacity (or (:capacity effect) vm/default-stream-capacity)
        handle (vm/create-stream! (:make-stream state) capacity :stream/make)
        new-store (assoc (:store state) id handle)
        stream-ref {:type :stream-ref, :id id}]
    [stream-ref (assoc state :store new-store)]))


(defn handle-put
  "Handle :stream/put. Total over the five append outcomes.
   Returns {:value v :state s} on success, {:park true :stream-id id :state s}
   on `full`. `closed`, `invalid-value` and `transport-error` are errors that
   name their outcome, as v1's throw on a closed stream did."
  [state effect]
  (let [stream-ref (:stream effect)
        val (:val effect)
        stream-id (:id stream-ref)
        handle (get (:store state) stream-id)]
    (when (nil? handle) (fail "Invalid stream reference" {:ref stream-ref}))
    (let [result (stream/append! handle val)
          o (outcome result)]
      (case o
        :dao.stream/ok {:value val, :state state}
        :dao.stream/full {:park true, :stream-id stream-id, :state state}
        (fail "Stream append failed" {:outcome o, :stream-id stream-id})))))


(defn handle-cursor
  "Handle :stream/cursor. Mints an opaque cursor at `:dao.stream/oldest`.

   v1 fabricated `{:position 0}` and touched no stream. Minting is a stream
   operation, so `closed` and `transport-error` arrive here.
   Returns [cursor-ref updated-state]."
  [state effect id]
  (let [stream-ref (:stream effect)
        stream-id (:id stream-ref)
        handle (get (:store state) stream-id)]
    (when (nil? handle) (fail "Invalid stream reference" {:ref stream-ref}))
    (let [cursor (vm/mint-oldest handle :stream/cursor)
          cursor-data (vm/cursor-entry stream-id cursor)
          new-store (assoc (:store state) id cursor-data)
          cursor-ref {:type :cursor-ref, :id id}]
      [cursor-ref (assoc state :store new-store)])))


(defn handle-next
  "Handle :stream/next. Total over the seven read outcomes.

   `ok` advances the store cursor to the exact returned successor. `blocked`
   parks. `end` yields nil, as v1 did. `gap` advances to the recovery cursor
   and yields `:dao.stream/gap`, so a program that reads it learns values were
   lost. The three terminal outcomes are errors."
  [state effect]
  (let [cursor-ref (:cursor effect)
        cursor-id (:id cursor-ref)
        store (:store state)
        cursor-data (get store cursor-id)]
    (when (nil? cursor-data) (fail "Invalid cursor reference" {:ref cursor-ref}))
    (let [stream-id (:stream-id cursor-data)
          handle (get store stream-id)]
      (when (nil? handle)
        (fail "Stream not found for cursor" {:stream-id stream-id}))
      (let [result (stream/next handle (:cursor cursor-data))
            o (outcome result)
            advance (fn [state* value]
                      {:value value,
                       :state (assoc state*
                                     :store
                                     (assoc store
                                            cursor-id
                                            (assoc cursor-data
                                                   :cursor
                                                   (:dao.stream/cursor
                                                     result))))})]
        (case o
          :dao.stream/ok (advance state (:dao.stream/value result))
          :dao.stream/blocked {:park true,
                               :cursor-ref cursor-ref,
                               :stream-id stream-id,
                               :state state}
          :dao.stream/end {:value nil, :state state}
          :dao.stream/gap (advance state :dao.stream/gap)
          (fail "Stream read failed"
                {:outcome o, :stream-id stream-id, :cursor-id cursor-id}))))))


(defn handle-close
  "Handle :stream/close. `close!` is total over {ok} and wakes nothing: a
   reader parked on this stream learns of the close from its own next `next`.
   Returns {:state s'}."
  [state effect]
  (let [stream-ref (:stream effect)
        stream-id (:id stream-ref)
        handle (get (:store state) stream-id)]
    (when (nil? handle) (fail "Invalid stream reference" {:ref stream-ref}))
    (stream/close! handle)
    {:state state}))


(defn- augment-wait-entry
  "Resolve one wait entry to a live handle and an opaque cursor out of the
   store. The cursor is re-resolved on every round even when an earlier round
   baked a value into the entry: another waiter on the same cursor-ref may
   have advanced the stored cursor since."
  [store entry]
  (if-let [cursor-ref (:cursor-ref entry)]
    (let [cursor-data (get store (:id cursor-ref))]
      (assoc entry
             :stream (or (:stream entry)
                         (get store (:stream-id cursor-data)))
             :cursor (:cursor cursor-data)))
    (if (and (not (:stream entry)) (:stream-id entry))
      (assoc entry :stream (get store (:stream-id entry)))
      entry)))


(defn check-wait-set
  "Check wait-set entries against their transports.

   Every entry is resolved to a live handle and an opaque cursor out of the
   store before `dao.runtime.v2` polls it. There is no transport-local waking
   to fall back from: this is the only mechanism.

   Entries are polled one at a time in wait-set order, and a woken reader's
   successor cursor is written back to the store before the next entry is
   resolved. Waiters sharing a cursor-ref therefore read distinct values — the
   value at the cursor wakes the first, its successor wakes the next — instead
   of every waiter reading the value at the shared pre-poll cursor."
  ([state] (check-wait-set state nil))
  ([state restore-fn]
   (let [wait-set (:wait-set state)]
     (if (empty? wait-set)
       state
       (loop [remaining wait-set
              v (assoc state :wait-set [])
              woken []]
         (if (empty? remaining)
           (let [new-tasks (make-woken-run-queue-entries v woken restore-fn)]
             (update v :ready-queue (fnil into []) new-tasks))
           (let [augmented (augment-wait-entry (:store v) (first remaining))
                 ;; A singleton wait set makes the outcome below belong to
                 ;; this entry alone.
                 polled (rt/check-wait-set (assoc v
                                                  :wait-set [augmented]
                                                  :ready-queue []))
                 raw (first (:ready-queue polled))
                 ;; A woken reader's successor is stored before later entries
                 ;; resolve, so a shared cursor-ref advances within the round.
                 advance (when (and raw (:cursor-ref raw) (:cursor raw))
                           {(:id (:cursor-ref raw))
                            (assoc (get (:store polled) (:id (:cursor-ref raw)))
                                   :cursor (:cursor raw))})]
             (recur (rest remaining)
                    (assoc polled
                           :store (merge (:store polled) advance)
                           :wait-set (into (:wait-set v) (:wait-set polled))
                           :ready-queue (:ready-queue v))
                    (if raw
                      (conj woken {:entry raw,
                                   :value (:value raw),
                                   :cursor (:cursor raw),
                                   :store-updates (:store-updates raw)})
                      woken)))))))))


(declare handle-effect resume-continuation)


(defn run-loop
  "Generic eval loop with scheduler support."
  [state active? step-fn resume-fn restore-fn]
  (loop [v state]
    (let [q (or (:ready-queue v) [])]
      (cond (active? v) (recur (step-fn v))
            (:blocked? v) (let [v' (check-wait-set v restore-fn)]
                            (if-let [resumed (or (rt/run-once v')
                                                 (resume-fn v'))]
                              (recur resumed)
                              (telemetry/emit-snapshot v' :blocked)))
            (seq q) (if-let [resumed (or (rt/run-once v) (resume-fn v))]
                      (recur resumed)
                      v)
            :else (if (:halted? v) (telemetry/emit-snapshot v :halt) v)))))


(defn resume-from-run-queue
  "Pop first entry from the ready-queue, merge store-updates, and restore
   VM-specific context."
  [state restore-fn]
  (let [run-queue (or (:ready-queue state) [])]
    (when (seq run-queue)
      (let [entry (first run-queue)
            rest-queue (subvec run-queue 1)
            base (assoc state
                        :ready-queue rest-queue
                        :store (merge (:store state) (:store-updates entry))
                        :blocked? false
                        :halted? false)]
        (restore-fn base entry)))))


(defn park-continuation
  "Add a parked continuation entry and halt the VM."
  [state cont-fields]
  (let [id-counter (or (:id-counter state) 0)
        park-id (keyword (str "parked-" id-counter))
        parked (merge {:type :parked-continuation, :id park-id} cont-fields)]
    (-> state
        (update :parked assoc park-id parked)
        (assoc :value parked
               :halted? true
               :id-counter (inc id-counter))
        (telemetry/emit-snapshot :park {:parked-id park-id}))))


(defn resume-continuation
  "Restore state from a parked continuation."
  [state parked-id resume-val restore-fn]
  (if-let [parked (get-in state [:parked parked-id])]
    (let [new-state (update state :parked dissoc parked-id)]
      (-> (restore-fn new-state parked resume-val)
          (telemetry/emit-snapshot :resume {:parked-id parked-id})))
    (fail "Cannot resume: parked continuation not found"
          {:parked-id parked-id})))


(defn gensym
  "Generate a unique ID and return [id updated-state]."
  ([state] (gensym state "id"))
  ([state prefix]
   (let [id-counter (or (:id-counter state) 0)
         id (gen-id prefix id-counter)]
     [id (assoc state :id-counter (inc id-counter))])))


(defn handle-stream-block
  [result entry]
  (if (:park result)
    (let [entry (or entry
                    (fail "Parked entry required for blocking stream"
                          {:result result}))
          new-state (-> (:state result)
                        (rt/park-task entry)
                        (assoc :value :yin/blocked
                               :blocked? true
                               :halted? false))]
      {:state new-state, :value :yin/blocked, :blocked? true})
    {:state (:state result), :value (:value result), :blocked? false}))


(defn handle-effect
  "Dispatch an effect and return {:state updated-state :value v :blocked? bool}.
   park-entry-fns maps :stream/put and :stream/next to functions that build
   wait entries."
  [state effect {:keys [park-entry-fns restore-fn], :as opts}]
  (let [park-entry (get park-entry-fns (:effect effect))
        result
        (case (:effect effect)
          :vm/store-put {:state (assoc state
                                       :store (assoc (:store state)
                                                     (:key effect)
                                                     (:val effect))),
                         :value (:val effect),
                         :blocked? false}
          :stream/make
          (let [[id s'] (gensym state "stream")
                [stream-ref new-state] (handle-make s' effect id)]
            {:state new-state, :value stream-ref, :blocked? false})
          :stream/cursor
          (let [[id s'] (gensym state "cursor")
                [cursor-ref new-state] (handle-cursor s' effect id)]
            {:state new-state, :value cursor-ref, :blocked? false})
          :stream/put
          (let [result (handle-put state effect)]
            (if (:park result)
              (let [built-entry (when park-entry
                                  (park-entry state effect result))
                    handle (get (:store state) (:stream-id result))
                    built-entry (cond-> built-entry
                                  (and built-entry (not (:stream built-entry)))
                                  (assoc :stream handle)
                                  (and built-entry (not (:datom built-entry)))
                                  (assoc :datom (:val effect)))
                    task (when (and built-entry restore-fn)
                           (adapter/vm-task built-entry restore-fn))]
                (handle-stream-block result (or task built-entry)))
              {:state (:state result),
               :value (:value result),
               :blocked? false}))
          :stream/next
          (let [result (handle-next state effect)]
            (if (:park result)
              (let [built-entry (when park-entry
                                  (park-entry state effect result))
                    stream-id (:stream-id result)
                    handle (get (:store state) stream-id)
                    cursor-id (:id (:cursor-ref result))
                    cursor (:cursor (get (:store state) cursor-id))
                    built-entry (cond-> built-entry
                                  (and built-entry (not (:stream built-entry)))
                                  (assoc :stream handle)
                                  (and built-entry (not (:cursor built-entry)))
                                  (assoc :cursor cursor))
                    task (when (and built-entry restore-fn)
                           (adapter/vm-task built-entry restore-fn))]
                (handle-stream-block result (or task built-entry)))
              {:state (:state result),
               :value (:value result),
               :blocked? false}))
          :stream/close
          (let [close-result (handle-close state effect)]
            {:state (:state close-result), :value nil, :blocked? false})
          (let [[id s'] (gensym state "effect")
                handler (module/get-effect-handler (:modules state)
                                                   (:effect effect))]
            (if handler
              (handler s' effect (assoc opts :id id))
              (fail "Unknown effect" {:effect (:effect effect)}))))]
    (update result
            :state
            (fn [result-state]
              (telemetry/emit-snapshot (assoc result-state
                                              :value (:value result))
                                       :effect
                                       {:effect-type (:effect effect)})))))


;; =============================================================================
;; Program Cache Machinery
;; =============================================================================

(def default-compiled-cache-limit 8)
(def ^:private derived-metadata-eid (:db/derived datom/reserved))


(defn build-program-index
  [datoms]
  (group-by first (vec datoms)))


(defn executable-program-datom?
  [[_e a _v _t m]]
  (and (keyword? a) (= "yin" (namespace a)) (not= m derived-metadata-eid)))


(defn frame-versions
  [frame]
  (loop [f frame
         acc #{}]
    (if (map? f)
      (let [acc (cond-> acc (:compiled-version f) (conj (:compiled-version f)))]
        (recur (:next f) acc))
      acc)))


(defn collect-frame-versions
  [frames]
  (reduce (fn [acc f] (into acc (frame-versions f))) #{} (or frames [])))


(defn pinned-compiled-versions
  [vm]
  (let [active (:active-compiled-version vm)
        k-versions (frame-versions (:k vm))
        parked-versions (collect-frame-versions (vals (:parked vm)))
        ready-versions (collect-frame-versions (:ready-queue vm))
        wait-versions (collect-frame-versions (:wait-set vm))]
    (cond-> (clojure.set/union k-versions
                               parked-versions
                               ready-versions
                               wait-versions)
      active (conj active))))


(defn trim-compiled-cache
  [vm]
  (let [cache (:compiled-by-version vm)
        limit
        (max 1 (or (:compiled-cache-limit vm) default-compiled-cache-limit))]
    (if (> (count cache) limit)
      (let [pinned (pinned-compiled-versions vm)
            sorted-versions (reverse (sort (keys cache)))
            to-keep (into pinned (take limit sorted-versions))]
        (assoc vm :compiled-by-version (select-keys cache to-keep)))
      vm)))


(defn cache-compiled-artifact
  [vm version artifact program-index]
  (-> vm
      (assoc :compile-dirty? false
             :datom-index program-index
             :active-compiled-version version)
      (update :compiled-by-version (fnil assoc {}) version artifact)
      trim-compiled-cache))


(defn ensure-compiled-version
  [vm version compile-fn]
  (if-let [artifact (get-in vm [:compiled-by-version version])]
    [vm artifact]
    (let [program-datoms (vec (or (:datoms vm) []))
          program-root-eid (:program-root-eid vm)]
      (when (or (empty? program-datoms) (nil? program-root-eid))
        (fail "Canonical program is not loaded" {:program-version version}))
      (let [program-index (or (:datom-index vm)
                              (build-program-index program-datoms))
            artifact (compile-fn program-root-eid program-datoms program-index)
            vm' (cache-compiled-artifact vm version artifact program-index)]
        [vm' artifact]))))


(defn maybe-recompile-at-boundary
  [vm compile-fn]
  (if (and (:compile-dirty? vm)
           (seq (:datoms vm))
           (some? (:program-root-eid vm)))
    (ensure-compiled-version vm (:program-version vm) compile-fn)
    [vm nil]))


(defn append-program-datoms
  ([vm new-datoms] (append-program-datoms vm new-datoms nil))
  ([vm new-datoms new-root-eid]
   (when-not (some? (:program-root-eid vm))
     (fail "append-program-datoms requires a canonical program" {}))
   (let [appended (vec new-datoms)]
     (if (and (empty? appended) (nil? new-root-eid))
       vm
       (let [version (inc (or (:program-version vm) 0))
             dirty? (or (some executable-program-datom? appended)
                        (some? new-root-eid))]
         (assoc vm
                :program-version version
                :program-root-eid (or new-root-eid (:program-root-eid vm))
                :compile-dirty? (or (:compile-dirty? vm) dirty?)
                :datoms (into (vec (or (:datoms vm) [])) appended)
                :datom-index (if dirty? nil (:datom-index vm))))))))
