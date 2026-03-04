(ns yin.vm.engine
  (:refer-clojure :exclude [gensym])
  (:require
    [yin.module :as module]
    [yin.stream :as stream]
    [yin.vm :as vm]))


(defn resolve-var
  "Look up a variable name: env -> store -> primitives -> module system."
  [env store primitives name]
  (if-let [pair (find env name)]
    (val pair)
    (if-let [pair (find store name)]
      (val pair)
      (or (get primitives name) (module/resolve-symbol name)))))


(defn- gen-id
  "Generate a unique keyword ID from a prefix and counter value."
  [prefix id-counter]
  (keyword (str prefix "-" id-counter)))


(defn vm-blocked?
  "Returns true if the VM is blocked."
  [vm]
  (boolean (:blocked vm)))


(defn vm-value
  "Returns the current value of the VM."
  [vm]
  (:value vm))


(defn halted-with-empty-queue?
  "Returns true if the VM has halted and its run-queue is empty."
  [vm]
  (and (boolean (:halted vm)) (empty? (or (:run-queue vm) []))))


(defn active-continuation?
  "Returns true when the currently active continuation should keep stepping."
  [vm]
  (and (not (:blocked vm)) (not (:halted vm))))


(defn check-wait-set
  "Check wait-set entries against current store.
   Returns updated state with newly runnable entries moved to run-queue."
  [state]
  (let [wait-set (or (:wait-set state) [])
        run-queue (or (:run-queue state) [])]
    (if (empty? wait-set)
      state
      (let [initial-store (:store state)]
        (loop [remaining wait-set
               new-wait []
               new-run run-queue
               store initial-store]
          (if (empty? remaining)
            (assoc state
                   :wait-set new-wait
                   :run-queue new-run
                   :store store)
            (let [entry (first remaining)
                  rest-entries (rest remaining)]
              (case (:reason entry)
                :next
                (let [cursor-ref (:cursor-ref entry)
                      cursor-id (:id cursor-ref)
                      cursor-data (get store cursor-id)
                      stream-ref (:stream-ref cursor-data)
                      _stream-id (:id stream-ref)
                      effect {:effect :stream/next, :cursor cursor-ref}
                      result (stream/handle-next (assoc state :store store)
                                                 effect)]
                  (if (:park result)
                    (recur rest-entries (conj new-wait entry) new-run store)
                    (let [updated-store (:store (:state result))]
                      (recur rest-entries
                             new-wait
                             (conj new-run
                                   (assoc entry
                                          :value (:value result)
                                          :store-updates (when (not= store
                                                                     updated-store)
                                                           updated-store)))
                             (or updated-store store)))))
                :take
                (let [stream-id (:stream-id entry)
                      stream-ref {:type :stream-ref, :id stream-id}
                      effect {:effect :stream/take, :stream stream-ref}
                      result (stream/handle-take (assoc state :store store)
                                                 effect)]
                  (if (:park result)
                    (recur rest-entries (conj new-wait entry) new-run store)
                    (let [updated-store (:store (:state result))]
                      (recur rest-entries
                             new-wait
                             (conj new-run
                                   (assoc entry
                                          :value (:value result)
                                          :store-updates (when (not= store
                                                                     updated-store)
                                                           updated-store)))
                             (or updated-store store)))))
                :put (let [stream-id (:stream-id entry)
                           stream (get store stream-id)]
                       (if (:closed stream)
                         (recur rest-entries new-wait new-run store)
                         (let [datom (:datom entry)
                               stream-ref {:type :stream-ref, :id stream-id}
                               effect {:effect :stream/put,
                                       :stream stream-ref,
                                       :val datom}
                               result (stream/handle-put (assoc state
                                                                :store store)
                                                         effect)]
                           (if (:park result)
                             (recur rest-entries
                                    (conj new-wait entry)
                                    new-run
                                    store)
                             (let [updated-store (:store (:state result))]
                               (recur rest-entries
                                      new-wait
                                      (conj new-run
                                            (assoc entry
                                                   :value (:value result)
                                                   :store-updates updated-store))
                                      (or updated-store store)))))))
                ;; Unknown reason, keep waiting
                (recur rest-entries (conj new-wait entry) new-run store)))))))))


(declare handle-effect resume-continuation)


(defn enqueue-ffi-request
  "Append an FFI request to the shared outbound stream.
   Request shape:
   {:op keyword :args vector :parked-id keyword}."
  [state request]
  (let [{:keys [state blocked?]}
        (handle-effect state
                       {:effect :stream/put,
                        :stream {:type :stream-ref, :id vm/ffi-out-stream-key},
                        :val request}
                       {})]
    (if blocked?
      (throw (ex-info "FFI out stream must not block"
                      {:request request}))
      state)))


(defn check-ffi-out
  "Idle handler for FFI requests.
   Performs one non-blocking read from :yin/ffi-out-cursor.
   If a request is available, dispatches through :bridge-dispatcher and enqueues
   the resumed continuation into :run-queue."
  [state]
  (if (and (contains? (:store state) vm/ffi-out-stream-key)
           (contains? (:store state) vm/ffi-out-cursor-key))
    (let [result (stream/handle-next state
                                     {:effect :stream/next,
                                      :cursor {:type :cursor-ref,
                                               :id vm/ffi-out-cursor-key}})]
      (if (:park result)
        state
        (let [state' (:state result)
              request (:value result)]
          (if (map? request)
            (let [{:keys [op args parked-id]} request
                  handler (get (:bridge-dispatcher state') op)]
              (when-not parked-id
                (throw (ex-info "FFI request missing parked-id"
                                {:request request})))
              (when-not (fn? handler)
                (throw (ex-info "No FFI handler registered for op"
                                {:op op})))
              (let [resume-val (apply handler (or args []))]
                (resume-continuation state'
                                     parked-id
                                     resume-val
                                     (fn [new-state parked rv]
                                       (update new-state
                                               :run-queue
                                               (fnil conj [])
                                               (assoc parked :value rv))))))
            state'))))
    state))


(defn run-loop
  "Generic eval loop with scheduler support.
   active? is a predicate that returns true when the VM should keep stepping.
   step-fn steps the VM one tick.
   resume-fn pops the run-queue and resumes, returning updated state or nil."
  [state active? step-fn resume-fn]
  (loop [v state]
    (cond (active? v) (recur (step-fn v))
          (:blocked v) (let [v' (-> v
                                    check-wait-set
                                    check-ffi-out)]
                         (if-let [resumed (resume-fn v')]
                           (recur resumed)
                           v'))
          (seq (or (:run-queue v) [])) (if-let [resumed (resume-fn v)]
                                         (recur resumed)
                                         v)
          :else v)))


(defn index-datoms
  "Index AST datoms by entity.
   Returns {:by-entity map, :get-attr fn, :root-id int}."
  [ast-as-datoms]
  (let [datoms (vec ast-as-datoms)
        by-entity (group-by first datoms)
        get-attr (fn [e attr]
                   (some (fn [[_ a v]] (when (= a attr) v)) (get by-entity e)))
        root-id (apply max (keys by-entity))]
    {:by-entity by-entity, :get-attr get-attr, :root-id root-id}))


(defn- resume-entries-with-nil
  "Set :value to nil on each entry, for waking parked continuations after stream close."
  [entries]
  (mapv #(assoc % :value nil) entries))


(defn resume-from-run-queue
  "Pop first entry from run-queue, merge store-updates, and restore VM-specific context."
  [state restore-fn]
  (let [run-queue (or (:run-queue state) [])]
    (when (seq run-queue)
      (let [entry (first run-queue)
            rest-queue (subvec run-queue 1)
            base (assoc state
                        :run-queue rest-queue
                        :store (or (:store-updates entry) (:store state))
                        :blocked false
                        :halted false)]
        (restore-fn base entry)))))


(defn park-continuation
  "Add a parked continuation entry and halt the VM."
  [state cont-fields]
  (let [park-id (keyword (str "parked-" (:id-counter state)))
        parked (merge {:type :parked-continuation, :id park-id} cont-fields)]
    (-> state
        (update :parked assoc park-id parked)
        (assoc :value parked
               :halted true
               :id-counter (inc (:id-counter state))))))


(defn resume-continuation
  "Restore state from a parked continuation."
  [state parked-id resume-val restore-fn]
  (if-let [parked (get-in state [:parked parked-id])]
    (let [new-state (update state :parked dissoc parked-id)]
      (restore-fn new-state parked resume-val))
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


(defn- handle-stream-block
  [result entry]
  (if (:park result)
    (let [entry (or entry
                    (throw (ex-info "Parked entry required for blocking stream"
                                    {:result result})))
          new-state (-> (:state result)
                        (add-wait-entry entry)
                        (assoc :value :yin/blocked
                               :blocked true
                               :halted false))]
      {:state new-state, :value :yin/blocked, :blocked? true})
    {:state (:state result), :value (:value result), :blocked? false}))


(defn handle-effect
  "Dispatch an effect and return {:state updated-state :value v :blocked? bool}.
   park-entry-fns maps :stream/put/:stream/next etc to functions that build wait entries."
  [state effect {:keys [park-entry-fns]}]
  (let [park-entry (get park-entry-fns (:effect effect))]
    (case (:effect effect)
      :vm/store-put {:state (assoc state
                                   :store (assoc (:store state)
                                                 (:key effect) (:val effect))),
                     :value (:val effect),
                     :blocked? false}
      :stream/make (let [[id s'] (gensym state "stream")
                         [stream-ref new-state]
                         (stream/handle-make s' effect id)]
                     {:state new-state, :value stream-ref, :blocked? false})
      :stream/cursor (let [[id s'] (gensym state "cursor")
                           [cursor-ref new-state]
                           (stream/handle-cursor s' effect id)]
                       {:state new-state, :value cursor-ref, :blocked? false})
      :stream/put (let [result (stream/handle-put state effect)]
                    (handle-stream-block result
                                         (when park-entry
                                           (park-entry state effect result))))
      :stream/next (let [result (stream/handle-next state effect)]
                     (handle-stream-block result
                                          (when park-entry
                                            (park-entry state effect result))))
      :stream/take (let [result (stream/handle-take state effect)]
                     (handle-stream-block result
                                          (when park-entry
                                            (park-entry state effect result))))
      :stream/close (let [close-result (stream/handle-close state effect)
                          new-state (:state close-result)
                          to-resume (:resume-parked close-result)
                          run-queue (or (:run-queue new-state) [])
                          new-run-queue (into run-queue
                                              (resume-entries-with-nil
                                                to-resume))]
                      {:state (assoc new-state :run-queue new-run-queue),
                       :value nil,
                       :blocked? false})
      (throw (ex-info "Unknown effect" {:effect effect})))))
