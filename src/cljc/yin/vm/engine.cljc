(ns yin.vm.engine
  (:refer-clojure :exclude [gensym])
  (:require
    [dao.runtime :as rt]
    [dao.stream :as ds]
    [dao.stream.ringbuffer]
    [yin.module :as module]
    [yin.vm.runtime-adapter :as adapter]
    [yin.vm.stream-driver :as stream-driver]
    [yin.vm.telemetry :as telemetry]))


(defn- rt-result->vm-result
  ([rt-result] (rt-result->vm-result rt-result nil))
  ([rt-result effect]
   (let [base {:state (:state rt-result),
               :value (if (= :blocked (:result rt-result))
                        :yin/blocked
                        (:value rt-result)),
               :blocked? (or (= :blocked (:result rt-result))
                             (= :full (:result rt-result))
                             (= :empty (:result rt-result)))}]
     (if-let [new-cursor (:cursor rt-result)]
       (let [cursor-id (:id (:cursor effect))
             state (:state base)
             new-store (assoc (:store state)
                              cursor-id (assoc (get (:store state) cursor-id)
                                               :position (:position new-cursor)))]
         (assoc base :state (assoc state :store new-store)))
       base))))


(defn resolve-var
  "Look up a variable name: env -> store -> primitives -> module system."
  [env store primitives name]
  (if-let [pair (find env name)]
    (val pair)
    (if-let [pair (find store name)]
      (val pair)
      (if-let [pair (find primitives name)]
        (val pair)
        (if-let [resolved (when (namespace name)
                            (module/resolve-module
                              (symbol (str (namespace name)
                                           "."
                                           (clojure.core/name name)))))]
          resolved
          (throw (ex-info
                   (str "Unable to resolve symbol: " name " in this context")
                   {:symbol name})))))))


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
  "Returns true if the VM has halted and its run-queue is empty."
  [vm]
  (and (boolean (:halted? vm))
       (empty? (or (:ready-queue vm) (:ready-queue vm) []))))


(defn restore-initial-env
  "After eval, restore :env to initial-env when the computation halted.
   Blocked and parked states keep the active lexical env for resumption."
  [initial-env result]
  (if (halted-with-empty-queue? result) (assoc result :env initial-env) result))


(defn active-continuation?
  "Returns true when the currently active continuation should keep stepping."
  [vm]
  (and (not (:blocked? vm)) (not (:halted? vm))))


(def ready-for-ingress? stream-driver/ready-for-ingress?)


(def ingest-next-program stream-driver/ingest-next-program)


(def step-on-stream stream-driver/step-on-stream)


(defn make-woken-run-queue-entries
  "Transform transport-level woken entries (readers or writers) into ready-queue entries.
   Readers (with :cursor-ref) compute :store-updates for cursor advance.
   Writers (no :cursor-ref) just stamp :value.
   If restore-fn is provided, wraps entries as dao.runtime tasks."
  ([state woken] (make-woken-run-queue-entries state woken nil))
  ([state woken restore-fn]
   (mapv (fn [{:keys [entry value position], :as woken-entry}]
           (let [cursor-ref (:cursor-ref entry)
                 store-updates
                 (or (:store-updates woken-entry)
                     (when cursor-ref
                       (let [cursor-id (:id cursor-ref)
                             cursor-data (get (:store state) cursor-id)
                             pos (or position (:position cursor-data))]
                         {cursor-id (assoc cursor-data
                                           :position (inc pos))})))
                 base-entry (assoc entry
                                   :value value
                                   :store-updates store-updates
                                   :position position)]
             (if restore-fn
               (let [task (adapter/vm-task base-entry restore-fn)]
                 (assoc task
                        :value value
                        :position position))
               base-entry)))
         woken)))


(defn handle-make
  "Handle :stream/make effect. Creates a stream in the VM store.
   Returns [stream-ref updated-state]."
  [state effect id]
  (let [capacity (:capacity effect)
        descriptor {:type :ringbuffer, :mode :create, :capacity capacity}
        stream (ds/open! descriptor)
        new-store (assoc (:store state) id stream)
        stream-ref {:type :stream-ref, :id id}]
    [stream-ref (assoc state :store new-store)]))


(defn handle-put
  "Handle :stream/put effect. Appends value to stream.
   Returns result map:
   {:value v, :state s, :woke [...]}       on success
   {:park true, :stream-id id, :state s}   if at capacity"
  [state effect]
  (let [stream-ref (:stream effect)
        val (:val effect)
        stream-id (:id stream-ref)
        stream (get (:store state) stream-id)]
    (when (nil? stream)
      (throw (ex-info "Invalid stream reference" {:ref stream-ref})))
    (let [result (ds/put! stream val)]
      (if (= :full (:result result))
        {:park true, :stream-id stream-id, :state state}
        {:value val, :state state, :woke (:woke result)}))))


(defn handle-cursor
  "Handle :stream/cursor effect. Creates a cursor in the VM store.
   Returns [cursor-ref updated-state].
   cursor-data is VM-internal: {:stream-id id, :position 0}"
  [state effect id]
  (let [stream-ref (:stream effect)
        cursor-data {:stream-id (:id stream-ref), :position 0}
        new-store (assoc (:store state) id cursor-data)
        cursor-ref {:type :cursor-ref, :id id}]
    [cursor-ref (assoc state :store new-store)]))


(defn handle-next
  "Handle :stream/next effect. Advances cursor, returns next value.
   Returns result map:
   {:value val, :state s'}                    data available
   {:park true, :cursor-ref ref, :stream-id id, :state s}  blocked
   {:value nil, :state s}                     end of closed stream"
  [state effect]
  (let [cursor-ref (:cursor effect)
        cursor-id (:id cursor-ref)
        store (:store state)
        cursor-data (get store cursor-id)]
    (when (nil? cursor-data)
      (throw (ex-info "Invalid cursor reference" {:ref cursor-ref})))
    (let [stream-id (:stream-id cursor-data)
          stream (get store stream-id)]
      (when (nil? stream)
        (throw (ex-info "Stream not found for cursor" {:stream-id stream-id})))
      (let [ds-cursor {:position (:position cursor-data)}
            result (ds/next stream ds-cursor)]
        (cond (map? result)
              (let [new-cursor (assoc cursor-data
                                      :position (:position (:cursor result)))
                    new-store (assoc store cursor-id new-cursor)]
                {:value (:ok result), :state (assoc state :store new-store)})
              (= :blocked result) {:park true,
                                   :cursor-ref cursor-ref,
                                   :stream-id stream-id,
                                   :state state}
              (= :end result) {:value nil, :state state}
              ;; Reserved: :daostream/gap (requires eviction, deferred)
              (= :daostream/gap result) {:value :daostream/gap,
                                         :state state})))))


(defn handle-take
  "Handle :stream/take effect. Destructively consumes next value.
   Returns result map:
   {:value val, :state s, :woke [...]}  value available, head advanced, woken writers
   {:park true, :stream-id id, :state s} empty (open stream, no data)
   {:value nil, :state s}                end of closed stream

   NOTE: This uses ds/drain-one! (utility function) not a protocol method,
   since destructive consumption is not part of the canonical model."
  [state effect]
  (let [stream-ref (:stream effect)
        stream-id (:id stream-ref)
        stream (get (:store state) stream-id)]
    (when (nil? stream)
      (throw (ex-info "Invalid stream reference" {:ref stream-ref})))
    (let [result (ds/drain-one! stream)]
      (cond (map? result)
            {:value (:ok result), :state state, :woke (:woke result)}
            (= :empty result) {:park true, :stream-id stream-id, :state state}
            (= :end result) {:value nil, :state state}))))


(defn handle-close
  "Handle :stream/close effect. Closes the stream.
   Returns {:state s', :resume-parked woke-entries}"
  [state effect]
  (let [stream-ref (:stream effect)
        stream-id (:id stream-ref)
        stream (get (:store state) stream-id)
        {:keys [woke]} (ds/close! stream)]
    {:state state, :resume-parked woke}))


(defn check-wait-set
  "Check wait-set entries against current store.
   Returns updated state with newly runnable entries moved to ready-queue.

   NOTE: Transport-local waking (IDaoStreamWaitable) is an optimization that
   removes entries from the scheduler's wait-set. This function serves as the
   universal fallback for transports that do not support local registration."
  ([state] (check-wait-set state nil))
  ([state restore-fn]
   (let [wait-set (:wait-set state)
         store (:store state)
         augmented-wait-set
         (mapv (fn [entry]
                 (cond (and (not (:stream entry)) (:cursor-ref entry))
                       (let [cursor-id (:id (:cursor-ref entry))
                             cursor-data (get store cursor-id)
                             stream-id (:stream-id cursor-data)
                             stream (get store stream-id)]
                         (assoc entry
                                :stream stream
                                :cursor {:position (:position cursor-data)}))
                       (and (not (:stream entry)) (:stream-id entry))
                       (assoc entry :stream (get store (:stream-id entry)))
                       :else entry))
               wait-set)
         rt-result (rt/check-wait-set (assoc state
                                             :wait-set augmented-wait-set))
         ;; rt/check-wait-set moved entries from its :wait-set to its
         ;; :ready-queue. These are raw dao.runtime entries. We need to
         ;; wrap them for the VM.
         woken (mapv (fn [entry]
                       {:entry entry,
                        :value (:value entry),
                        :position (:position entry),
                        :store-updates (:store-updates entry)})
                     (:ready-queue rt-result))
         new-tasks (make-woken-run-queue-entries state woken restore-fn)]
     (assoc rt-result :ready-queue new-tasks))))


(declare handle-effect resume-continuation)


(defn run-loop
  "Generic eval loop with scheduler support.
   active? is a predicate that returns true when the VM should keep stepping.
   step-fn steps the VM one tick.
   resume-fn pops the ready-queue and resumes, returning updated state or nil."
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


(defn run-on-stream
  "Run a VM while polling its ingress DaoStream between evaluations.
   Internal VM blocking still returns immediately; ingress polling only happens
   when the VM is idle between program batches."
  [vm in-stream load-fn step-fn resume-fn restore-fn]
  (loop [v vm]
    (if (and in-stream (ready-for-ingress? v))
      (let [{:keys [status state]} (ingest-next-program v in-stream load-fn)]
        (case status
          :ok (recur state)
          state))
      (if (ready-for-ingress? v)
        v
        (let [v' (run-loop v active-continuation? step-fn resume-fn restore-fn)]
          (if (and in-stream (not (:blocked? v')) (ready-for-ingress? v'))
            (recur v')
            v'))))))


(defn- resume-entries-with-nil
  "Set :value to nil on each entry, for waking parked continuations after stream close.
   Handles raw transport entries {:entry map, :value val}.
   If restore-fn is provided, wraps entries as dao.runtime tasks."
  ([entries] (resume-entries-with-nil entries nil))
  ([entries restore-fn]
   (mapv (fn [{:keys [entry]}]
           (if restore-fn
             (let [task (adapter/vm-task entry restore-fn)]
               (assoc task :value nil))
             (assoc entry :value nil)))
         entries)))


(defn resume-from-run-queue
  "Pop first entry from run-queue, merge store-updates, and restore VM-specific context."
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
    (throw (ex-info "Cannot resume: parked continuation not found"
                    {:parked-id parked-id}))))


(defn- add-wait-entry
  [state entry]
  (update state :wait-set (fnil conj []) entry))


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
                    (throw (ex-info "Parked entry required for blocking stream"
                                    {:result result})))
          new-state (-> (:state result)
                        (rt/park-task entry)
                        (assoc :value :yin/blocked
                               :blocked? true
                               :halted? false))]
      {:state new-state, :value :yin/blocked, :blocked? true})
    {:state (:state result), :value (:value result), :blocked? false}))


(defn handle-effect
  "Dispatch an effect and return {:state updated-state :value v :blocked? bool}.
   park-entry-fns maps :stream/put/:stream/next etc to functions that build wait entries."
  [state effect {:keys [park-entry-fns restore-fn], :as opts}]
  (let [park-entry (get park-entry-fns (:effect effect))
        result
        (case (:effect effect)
          :vm/store-put {:state (assoc state
                                       :store (assoc (:store state)
                                                     (:key effect) (:val effect))),
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
                    stream-id (:stream-id result)
                    stream (get (:store state) stream-id)
                    ;; Augment for dao.runtime compatibility
                    built-entry
                    (cond-> built-entry
                      (and built-entry (not (:stream built-entry)))
                      (assoc :stream stream)
                      (and built-entry (not (:datom built-entry)))
                      (assoc :datom (:val effect)))
                    task (when (and built-entry restore-fn)
                           (adapter/vm-task built-entry restore-fn))]
                (if (satisfies? ds/IDaoStreamWaitable stream)
                  (if task
                    (rt-result->vm-result
                      (rt/handle-write state stream (:val effect) task)
                      effect)
                    (do (ds/register-writer-waiter! stream built-entry)
                        {:state (assoc (:state result)
                                       :blocked? true
                                       :halted? false
                                       :value :yin/blocked),
                         :value :yin/blocked,
                         :blocked? true}))
                  (handle-stream-block result (or task built-entry))))
              (let [woken (:woke result)
                    base {:state (:state result),
                          :value (:value result),
                          :blocked? false}]
                (if (seq woken)
                  (let [entries (make-woken-run-queue-entries (:state
                                                                result)
                                                              woken
                                                              restore-fn)]
                    (update base :state update :ready-queue into entries))
                  base))))
          :stream/next
          (let [result (handle-next state effect)]
            (if (:park result)
              (let [built-entry (when park-entry
                                  (park-entry state effect result))
                    stream-id (:stream-id result)
                    stream (get (:store state) stream-id)
                    cursor-id (:id (:cursor-ref result))
                    position (:position (get (:store state) cursor-id))
                    ;; Augment for dao.runtime compatibility
                    built-entry
                    (cond-> built-entry
                      (and built-entry (not (:stream built-entry)))
                      (assoc :stream stream)
                      (and built-entry (not (:cursor built-entry)))
                      (assoc :cursor {:position position}))
                    task (when (and built-entry restore-fn)
                           (adapter/vm-task built-entry restore-fn))]
                (if (satisfies? ds/IDaoStreamWaitable stream)
                  (if task
                    (rt-result->vm-result (rt/handle-read state
                                                          stream
                                                          {:position
                                                           position}
                                                          task)
                                          effect)
                    (do (ds/register-reader-waiter! stream
                                                    position
                                                    built-entry)
                        {:state (assoc (:state result)
                                       :blocked? true
                                       :halted? false
                                       :value :yin/blocked),
                         :value :yin/blocked,
                         :blocked? true}))
                  (handle-stream-block result (or task built-entry))))
              {:state (:state result),
               :value (:value result),
               :blocked? false}))
          :stream/take
          (let [result (handle-take state effect)]
            (if (:park result)
              (let [built-entry (when park-entry
                                  (park-entry state effect result))
                    stream-id (:stream-id result)
                    stream (get (:store state) stream-id)
                    ;; Augment for dao.runtime compatibility
                    built-entry (cond-> built-entry
                                  (and built-entry
                                       (not (:stream built-entry)))
                                  (assoc :stream stream))
                    task (when (and built-entry restore-fn)
                           (adapter/vm-task built-entry restore-fn))]
                (handle-stream-block result (or task built-entry)))
              (let [woken (:woke result)
                    base {:state (:state result),
                          :value (:value result),
                          :blocked? false}]
                (if (seq woken)
                  (let [entries (make-woken-run-queue-entries (:state
                                                                result)
                                                              woken
                                                              restore-fn)]
                    (update base :state update :ready-queue into entries))
                  base))))
          :stream/close
          (let [close-result (handle-close state effect)
                new-state (:state close-result)
                to-resume (:resume-parked close-result)
                run-queue (or (:ready-queue new-state) [])
                new-run-queue (into run-queue
                                    (resume-entries-with-nil to-resume
                                                             restore-fn))]
            {:state (assoc new-state :ready-queue new-run-queue),
             :value nil,
             :blocked? false})
          (let [[id s'] (gensym state "effect")
                handler (module/get-effect-handler (:effect effect))]
            (if handler
              (handler s' effect (assoc opts :id id))
              (throw (ex-info "Unknown effect"
                              {:effect (:effect effect)})))))]
    (update result
            :state
            (fn [result-state]
              (telemetry/emit-snapshot (assoc result-state
                                              :value (:value result))
                                       :effect
                                       {:effect-type (:effect effect)})))))
