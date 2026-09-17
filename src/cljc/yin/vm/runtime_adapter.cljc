(ns yin.vm.runtime-adapter
  "Wrap VM wait-set entries as dao.runtime tasks.

   The only v1 idiom here was cursor arithmetic: a woken reader advanced its
   stored cursor by `(inc position)`. A v2 cursor is opaque, so the successor
   comes from the transport in the `next` outcome and is stored as it was
   given."
  (:require [dao.runtime :as rt]))


(defn terminal-resume-outcome
  "The outcome a parked retry terminated with, when it is one the immediate
   path raises as an error.

   `dao.runtime` wakes a task for every outcome except the two that can
   change on their own, so a retry that ends in a terminal outcome would
   otherwise reach Yin code as an ordinary value. `handle-put` and
   `handle-next` throw for these same outcomes, and whether the first attempt
   blocked must not change that. A writer's `:end` is `closed` wearing its
   task classification and is reported under the outcome the transport gave.

   Public because restoration is dispatched explicitly: `vm-task`'s closure
   checks it when a host runtime runs the task, and `engine/resume-from-run-queue`
   checks it when the VM's own scheduler pops the entry."
  [vm-entry status]
  (case (:reason vm-entry)
    :next (case status
            (:dao.stream/cursor-mismatch
              :dao.stream/invalid-cursor
              :dao.stream/transport-error)
            status
            nil)
    :put (case status
           :end :dao.stream/closed
           (:dao.stream/invalid-value :dao.stream/transport-error) status
           nil)
    nil))


(defn throw-terminal-resume!
  "Fail a resumed task exactly as the immediate operation would have."
  [vm-entry outcome]
  (if (= :next (:reason vm-entry))
    (throw (ex-info "Stream read failed"
                    {:outcome outcome,
                     :stream-id (:stream-id vm-entry),
                     :cursor-id (:id (:cursor-ref vm-entry))}))
    (throw (ex-info "Stream append failed"
                    {:outcome outcome, :stream-id (:stream-id vm-entry)}))))


(defn- calculate-store-updates
  "Store updates for a woken entry.

   A reader carries a `:cursor-ref` into the VM store; waking it replaces that
   entry's opaque cursor with the successor the transport returned. A writer
   carries whatever updates it was built with."
  [rt vm-entry _value cursor]
  (if-let [cursor-ref (:cursor-ref vm-entry)]
    (let [cursor-id (:id cursor-ref)
          cursor-data (get (:store rt) cursor-id)]
      (when cursor {cursor-id (assoc cursor-data :cursor cursor)}))
    (:store-updates vm-entry)))


(defn vm-task
  "Wrap a VM entry as a dao.runtime task."
  [vm-entry restore-fn]
  (let [vm-entry (or (:task vm-entry) vm-entry)]
    (merge vm-entry
           {:task vm-entry,
            :resume
            (fn [rt task-entry value]
              (let [vm-entry (:task task-entry)
                    value (or value (:value task-entry))
                    cursor (or (:cursor task-entry) (:cursor vm-entry))
                    terminal (terminal-resume-outcome vm-entry
                                                      (:status task-entry))]
                (when terminal (throw-terminal-resume! vm-entry terminal))
                (let [store-updates
                      (calculate-store-updates rt vm-entry value cursor)
                      base (assoc rt
                                  :store (merge (:store rt) store-updates)
                                  :blocked? false
                                  :halted? false
                                  :ready-queue (:ready-queue rt)
                                  :wait-set (:wait-set rt))]
                  (restore-fn base vm-entry value))))})))


(defn enqueue-woken-vm-entries
  "Translate raw woken entries into dao.runtime ready-queue entries using
   the VM-specific resume wrapper."
  [rt woken restore-fn]
  (let [tasks (mapv (fn [{:keys [entry value cursor]}]
                      (let [task (vm-task entry restore-fn)]
                        (assoc task
                               :value value
                               :cursor cursor)))
                    woken)]
    (rt/enqueue-ready rt tasks)))
