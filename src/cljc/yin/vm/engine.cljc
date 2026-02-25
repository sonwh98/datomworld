(ns yin.vm.engine
  (:require
    [yin.module :as module]
    [yin.stream :as stream]))


(defn resolve-var
  "Look up a variable name: env -> store -> primitives -> module system."
  [env store primitives name]
  (if-let [pair (find env name)]
    (val pair)
    (if-let [pair (find store name)]
      (val pair)
      (or (get primitives name) (module/resolve-symbol name)))))


(defn gen-id
  "Generate a unique keyword ID from a prefix and counter value."
  [prefix id-counter]
  (keyword (str prefix "-" id-counter)))


(defn gen-id-fn
  "Return a function that generates a unique keyword ID for a given prefix."
  [id-counter]
  (fn [prefix] (gen-id prefix id-counter)))


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


(defn- check-wait-set
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


(defn run-loop
  "Generic eval loop with scheduler support.
   active? is a predicate that returns true when the VM should keep stepping.
   step-fn steps the VM one tick.
   resume-fn pops the run-queue and resumes, returning updated state or nil."
  [state active? step-fn resume-fn]
  (loop [v state]
    (cond (active? v) (recur (step-fn v))
          (:blocked v) (let [v' (check-wait-set v)]
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


(defn resume-entries-with-nil
  "Set :value to nil on each entry, for waking parked continuations after stream close."
  [entries]
  (mapv #(assoc % :value nil) entries))
