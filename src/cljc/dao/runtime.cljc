(ns dao.runtime
  (:require
    [dao.stream :as ds]))


;; =============================================================================
;; Core Runtime State
;; =============================================================================

(defn initial-state
  "Returns a new empty runtime state."
  []
  {:ready-queue [], :wait-set [], :blocked? false})


;; =============================================================================
;; Task Management
;; =============================================================================

(defn park-task
  "Record a blocked task in the wait-set."
  [rt entry]
  (update rt :wait-set (fnil conj []) entry))


(defn enqueue-ready
  "Add runnable task entries to the end of the ready-queue."
  [rt entries]
  (if (seq entries) (update rt :ready-queue (fnil into []) entries) rt))


(defn pop-ready
  "Fetch and remove the next runnable task from the ready-queue.
   Returns [entry updated-rt] or nil."
  [rt]
  (let [queue (:ready-queue rt)]
    (when (seq queue)
      [(first queue) (assoc rt :ready-queue (subvec queue 1))])))


;; =============================================================================
;; Transport Helpers
;; =============================================================================

(defn- make-ready-entries
  "Transform transport-level woken entries into runtime ready entries."
  [woken]
  (mapv (fn [{:keys [entry value position]}]
          (assoc entry
                 :value value
                 :position position))
        woken))


(defn check-wait-set
  "Check wait-set entries against their transports.
   Returns updated state with newly runnable entries moved to ready-queue."
  [rt]
  (let [wait-set (:wait-set rt)]
    (if (empty? wait-set)
      rt
      (loop [remaining wait-set
             new-wait []
             new-ready (:ready-queue rt)]
        (if (empty? remaining)
          (assoc rt
                 :wait-set new-wait
                 :ready-queue new-ready)
          (let [entry (first remaining)
                rest-entries (rest remaining)]
            (case (:reason entry)
              :next
              (let [result (ds/next (:stream entry) (:cursor entry))]
                (cond (map? result) (recur rest-entries
                                           new-wait
                                           (conj new-ready
                                                 (assoc entry
                                                        :value (:ok result)
                                                        :cursor (:cursor result)
                                                        :position (:position
                                                                    (:cursor
                                                                      entry)))))
                      (= :blocked result)
                      (recur rest-entries (conj new-wait entry) new-ready)
                      :else ; :end or :daostream/gap
                      (recur rest-entries
                             new-wait
                             (conj new-ready (assoc entry :value nil)))))
              :take (let [result (ds/drain-one! (:stream entry))]
                      (cond
                        (map? result)
                        (let [woken (make-ready-entries (:woke result))]
                          (recur rest-entries
                                 new-wait
                                 (into new-ready
                                       (cons (assoc entry :value (:ok result))
                                             woken))))
                        (= :empty result)
                        (recur rest-entries (conj new-wait entry) new-ready)
                        :else ; :end
                        (recur rest-entries
                               new-wait
                               (conj new-ready (assoc entry :value nil)))))
              :put (let [stream (:stream entry)]
                     (if (ds/closed? stream)
                       (recur rest-entries
                              new-wait
                              (conj new-ready (assoc entry :value nil)))
                       (let [result (ds/put! stream (:datom entry))]
                         (if (= :full (:result result))
                           (recur rest-entries (conj new-wait entry) new-ready)
                           (let [woken (make-ready-entries (:woke result))]
                             (recur rest-entries
                                    new-wait
                                    (into new-ready
                                          (cons (assoc entry
                                                       :value (:datom entry))
                                                woken))))))))
              ;; Unknown reason, keep waiting
              (recur rest-entries (conj new-wait entry) new-ready))))))))


;; =============================================================================
;; Public Operation Handlers
;; =============================================================================

(defn handle-read
  "Attempt a ds/next. If blocked, park the task.
   Returns {:state updated-rt :result result-tag :value v}."
  [rt stream cursor task]
  (let [result (ds/next stream cursor)]
    (cond (map? result) {:state rt,
                         :result :ok,
                         :value (:ok result),
                         :cursor (:cursor result)}
          (= :blocked result)
          (let [entry {:task task,
                       :resume (:resume task),
                       :reason :next,
                       :stream stream,
                       :cursor cursor}]
            (if (satisfies? ds/IDaoStreamWaitable stream)
              (do (ds/register-reader-waiter! stream (:position cursor) entry)
                  {:state (assoc rt :blocked? true), :result :blocked})
              {:state (-> rt
                          (park-task entry)
                          (assoc :blocked? true)),
               :result :blocked}))
          :else {:state rt, :result result, :value nil})))


(defn handle-write
  "Attempt a ds/put!. If full, park the task.
   Returns {:state updated-rt :result result-tag :value v}."
  ([rt stream val] (handle-write rt stream val nil))
  ([rt stream val task]
   (let [result (ds/put! stream val)]
     (if (= :full (:result result))
       (if task
         (let [entry {:task task,
                      :resume (:resume task),
                      :reason :put,
                      :stream stream,
                      :datom val}]
           (if (satisfies? ds/IDaoStreamWaitable stream)
             (do (ds/register-writer-waiter! stream entry)
                 {:state (assoc rt :blocked? true), :result :full})
             {:state (-> rt
                         (park-task entry)
                         (assoc :blocked? true)),
              :result :full}))
         (throw (ex-info "handle-write: task required for blocking put"
                         {:stream stream, :val val})))
       (let [woken (make-ready-entries (:woke result))]
         {:state (enqueue-ready rt woken), :result :ok, :value val})))))


(defn handle-take
  "Attempt a ds/drain-one!. If empty, park the task.
   Returns {:state updated-rt :result result-tag :value v}."
  [rt stream task]
  (let [result (ds/drain-one! stream)]
    (cond (map? result) (let [woken (make-ready-entries (:woke result))]
                          {:state (enqueue-ready rt woken),
                           :result :ok,
                           :value (:ok result)})
          (= :empty result) (let [entry {:task task,
                                         :resume (:resume task),
                                         :reason :take,
                                         :stream stream}]
                              {:state (-> rt
                                          (park-task entry)
                                          (assoc :blocked? true)),
                               :result :empty})
          :else {:state rt, :result result, :value nil})))


(defn handle-close
  "Close a stream and wake any parked tasks.
   Returns {:state updated-rt :result :ok}."
  [rt stream]
  (let [{:keys [woke]} (ds/close! stream)
        woken (make-ready-entries woke)]
    {:state (enqueue-ready rt woken), :result :ok}))


;; =============================================================================
;; Runtime Loop
;; =============================================================================

(defn run-once
  "Fetch one ready task and resume it.
   Returns updated state or nil if no work.
   If the next entry has no :resume function (raw legacy entry), returns nil
   so the host can handle it via legacy dispatch."
  [rt]
  (let [queue (:ready-queue rt)]
    (if (seq queue)
      (let [entry (first queue)]
        (if (:resume entry)
          (let [[entry rt'] (pop-ready rt)]
            ((:resume entry) rt' entry (:value entry)))
          nil))
      (let [rt' (check-wait-set rt)]
        (let [queue' (:ready-queue rt')]
          (if (seq queue')
            (let [entry (first queue')]
              (if (:resume entry)
                (let [[entry rt''] (pop-ready rt')]
                  ((:resume entry) rt'' entry (:value entry)))
                nil))
            nil))))))


(defn run-loop
  "Drain ready tasks, then check wait-set, until quiescent."
  [rt]
  (loop [curr-rt rt]
    (if-let [next-rt (run-once curr-rt)]
      (recur next-rt)
      curr-rt)))
