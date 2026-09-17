(ns dao.runtime
  "Cooperative scheduler over DaoStream v2 — the scheduler slice, and
   nothing else: a ready queue, a wait set, the two outcome classifiers, the
   three operation handlers, and the loop. Its contract:

   - **State is a value.** `{:ready-queue [] :wait-set [] :blocked? false}`
     from `initial-state`. Every function takes that map and returns it, or
     returns nil where \"no work\" is the answer. No atom, no `defonce`, no
     host dependency; requires only `dao.stream`. A composition that
     needs the state to persist across host callbacks holds it; the runtime
     does not.
   - **A task is a map with `:resume`.** `(resume rt entry value)` returns
     the next runtime state. Ready entries carry `:value`, `:status`, and
     for a woken reader `:cursor`. Wait entries additionally carry `:reason`
     (`:next` or `:put`), `:stream` (a live v2 handle), and either `:cursor`
     or `:datom`. The runtime resolves nothing: the caller that parks a task
     hands it the handle and cursor it will be polled with. Cursors are
     opaque; no arithmetic on one, and the successor is stored exactly as
     `next` returned it.
   - **Classification is total.** `read-outcome->task` and
     `write-outcome->task` map every outcome in the contract's closed sets
     to `[:wait]` or `[:ready updates]`. Exactly two outcomes wait —
     `blocked` for a reader and `full` for a writer — because they are the
     only two that can change on their own. Every other outcome resolves
     the task: `ok` with the value and successor, `end` as
     `{:value nil :status :end}`, `gap` as `{:value :dao.stream/gap
     :status :dao.stream/gap :cursor recovery}`, the terminal outcomes under
     their own keyword, and a writer's `closed` as `:end`. An outcome
     outside the closed set is terminal, not a wait: waiting on an answer
     the runtime cannot interpret would spin.
   - **The polling wait set is the mechanism.** `check-wait-set` polls each
     parked entry against its transport — `next` for `:next`, `append!` for
     `:put` — and moves resolved entries to the ready queue in wait-set
     order. There is no other path: no transport is waitable, `append!`
     returns no wake list, `close!` wakes nothing. A reader parked on a
     stream that is then closed learns of it from its own next `next`.
   - **A parked writer retries by appending.** A `:put` entry re-attempts
     `append!` on every poll. The append is an effect of polling, and the
     state a poll returns is the only record that the append happened — no
     polled state is ever discarded.
   - **The loop is a step.** `run-once` resumes one ready task — the head of
     the ready queue, if it has `:resume` — and never polls. `run-loop`
     drains the ready queue, then polls once; if the poll moved anything it
     continues, else it returns. Entries without `:resume` are host-owned:
     whenever the ready queue's head is one, the state that holds it is
     returned, so the composition that put it on the queue is the one that
     reads it off. Neither schedules itself; cadence belongs to the driver.

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
  (:require [dao.stream :as stream]))


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
  "Pop and resume the head of the ready queue.
   Returns the state the resume returned, or nil when the queue is empty or
   its head has no :resume — a host-owned entry this namespace must not run,
   and must not poll past. Never polls the wait set: check-wait-set is the
   only function that polls."
  [rt]
  (let [queue (or (:ready-queue rt) [])]
    (when (seq queue)
      (let [entry (first queue)]
        (when (:resume entry)
          (let [[_entry state'] (pop-ready rt)]
            ((:resume entry) state' entry (:value entry))))))))


(defn run-loop
  "Drain the ready queue, then poll the wait set, until quiescent.

   One round: run ready entries until one cannot run, then poll once. A poll
   that moved anything from the wait set starts another round; a poll that
   moved nothing ends the loop. When the ready queue's head is host-owned
   (no :resume) the state that holds it is returned — the composition that
   put it on the queue is the one that reads it off — and the poll's state
   is returned with it, never discarded: a writer the poll resolved sits in
   the returned ready queue behind the host-owned head, appended exactly
   once."
  [rt]
  (loop [curr-rt rt]
    (let [drained (loop [v curr-rt]
                    (if-let [v' (run-once v)]
                      (recur v')
                      v))
          polled (check-wait-set drained)]
      (if (< (count (:wait-set polled)) (count (:wait-set drained)))
        (recur polled)
        polled))))
