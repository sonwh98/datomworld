(ns dao.runtime.v2
  "Cooperative scheduler over DaoStream v2.

   Three things v1 had are gone here, and their absence is the point:

   - **Waiters.** No v2 transport is waitable, so `register-reader-waiter!`
     and the `:woke` entries an append returned have no v2 counterpart. The
     polling wait set is not a fallback any more; it is the mechanism.
   - **Destructive take.** `:take` entries cannot exist without a reader
     position held in the medium.
   - **`closed?`.** Nothing asks a stream whether it is closed. Operations
     report `:dao.stream/closed` and callers read the answer.

   Cursors are opaque values obtained from the transport. Nothing here does
   arithmetic on one."
  (:require [dao.stream.v2 :as stream]))


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
;; Outcome classification
;; =============================================================================
;;
;; Both read and write are total over their closed outcome sets. A task waits
;; only for the two outcomes that can change on their own — `blocked` and
;; `full`. Every other outcome resolves the task, because retrying it would
;; either spin forever or conceal a loss the task has to see.

(defn read-outcome->task
  "Classify a `next` outcome for a waiting reader.
   Returns [:wait] or [:ready entry-updates]."
  [result]
  (case (:dao.stream/outcome result)
    :dao.stream/ok [:ready {:value (:dao.stream/value result),
                            :status :ok,
                            :cursor (:dao.stream/cursor result)}]
    :dao.stream/blocked [:wait]
    :dao.stream/end [:ready {:value nil, :status :end}]
    ;; A gap advances to the transport's recovery cursor. The reader is told
    ;; what happened: values it had not read are gone and no retry recovers
    ;; them.
    :dao.stream/gap [:ready {:value :dao.stream/gap,
                             :status :dao.stream/gap,
                             :cursor (:dao.stream/cursor result)}]
    (:dao.stream/cursor-mismatch :dao.stream/invalid-cursor
                                 :dao.stream/transport-error)
    [:ready {:value (:dao.stream/outcome result),
             :status (:dao.stream/outcome result)}]
    ;; A transport that answers outside the closed set is terminal here;
    ;; waiting on an answer this namespace cannot interpret would spin.
    [:ready {:value (:dao.stream/outcome result),
             :status (:dao.stream/outcome result)}]))


(defn write-outcome->task
  "Classify an `append!` outcome for a waiting writer.
   Returns [:wait] or [:ready entry-updates].

   `full` parks: the composition may have supplied a transport that frees
   capacity. A ring buffer never returns it."
  [result val]
  (case (:dao.stream/outcome result)
    :dao.stream/ok [:ready {:value val, :status :ok}]
    :dao.stream/full [:wait]
    :dao.stream/closed [:ready {:value nil, :status :end}]
    (:dao.stream/invalid-value :dao.stream/transport-error)
    [:ready {:value (:dao.stream/outcome result),
             :status (:dao.stream/outcome result)}]
    [:ready {:value (:dao.stream/outcome result),
             :status (:dao.stream/outcome result)}]))


;; =============================================================================
;; Wait set
;; =============================================================================

(defn check-wait-set
  "Check wait-set entries against their transports.
   Returns updated state with newly runnable entries moved to ready-queue."
  [rt]
  (let [wait-set (:wait-set rt)]
    (if (empty? wait-set)
      rt
      (loop [remaining wait-set
             new-wait []
             new-ready (or (:ready-queue rt) [])]
        (if (empty? remaining)
          (assoc rt
                 :wait-set new-wait
                 :ready-queue new-ready)
          (let [entry (first remaining)
                rest-entries (rest remaining)
                [disposition updates]
                (case (:reason entry)
                  :next (read-outcome->task
                          (stream/next (:stream entry) (:cursor entry)))
                  :put (write-outcome->task
                         (stream/append! (:stream entry) (:datom entry))
                         (:datom entry))
                  ;; Unknown reason, keep waiting.
                  [:wait])]
            (if (= :wait disposition)
              (recur rest-entries (conj new-wait entry) new-ready)
              (recur rest-entries
                     new-wait
                     (conj new-ready (merge entry updates))))))))))


;; =============================================================================
;; Public Operation Handlers
;; =============================================================================

(defn handle-read
  "Attempt a read. If blocked, park the task.
   Returns {:state updated-rt :result result-tag :value v :cursor c}."
  [rt handle cursor task]
  (let [result (stream/next handle cursor)
        [disposition updates] (read-outcome->task result)]
    (if (= :wait disposition)
      (if task
        {:state (-> rt
                    (park-task (assoc task
                                      :reason :next
                                      :stream handle
                                      :cursor cursor))
                    (assoc :blocked? true)),
         :result :blocked}
        {:state (assoc rt :blocked? true), :result :blocked})
      {:state rt,
       :result (:status updates),
       :value (:value updates),
       :cursor (:cursor updates)})))


(defn handle-write
  "Attempt an append. If full, park the task.
   Returns {:state updated-rt :result result-tag :value v}."
  ([rt handle val] (handle-write rt handle val nil))
  ([rt handle val task]
   (let [result (stream/append! handle val)
         [disposition updates] (write-outcome->task result val)]
     (if (= :wait disposition)
       (if task
         {:state (-> rt
                     (park-task (assoc task
                                       :reason :put
                                       :stream handle
                                       :datom val))
                     (assoc :blocked? true)),
          :result :full}
         {:state (assoc rt :blocked? true), :result :full})
       {:state rt, :result (:status updates), :value (:value updates)}))))


(defn handle-close
  "Close a stream. `close!` is total over {ok} and wakes nothing: a reader
   parked on this stream learns of the close from its own next `next`.
   Returns {:state rt :result :ok}."
  [rt handle]
  (stream/close! handle)
  {:state rt, :result :ok})


;; =============================================================================
;; Runtime Loop
;; =============================================================================

(defn run-once
  "Fetch one ready task and resume it.
   Returns updated state or nil if no work.
   If the next entry has no :resume function, it is not runnable by
   run-once; returns nil so the host can handle the entry itself."
  [rt]
  (let [take-one (fn [state]
                   (let [queue (or (:ready-queue state) [])]
                     (when (seq queue)
                       (let [entry (first queue)]
                         (when (:resume entry)
                           (let [[entry state'] (pop-ready state)]
                             ((:resume entry) state' entry (:value entry))))))))]
    (or (take-one rt) (take-one (check-wait-set rt)))))


(defn run-loop
  "Drain ready tasks, then check wait-set, until quiescent."
  [rt]
  (loop [curr-rt rt]
    (if-let [next-rt (run-once curr-rt)]
      (recur next-rt)
      curr-rt)))
