(ns yin.vm.runtime-adapter
  (:require
    [dao.runtime :as rt]))


(defn- calculate-store-updates
  [rt vm-entry _value position]
  (if-let [cursor-ref (:cursor-ref vm-entry)]
    (let [cursor-id (:id cursor-ref)
          cursor-data (get (:store rt) cursor-id)]
      (when position {cursor-id (assoc cursor-data :position (inc position))}))
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
                    position (or (:position task-entry) (:position vm-entry))
                    store-updates
                    (calculate-store-updates rt vm-entry value position)
                    base (assoc rt
                                :store (merge (:store rt) store-updates)
                                :blocked? false
                                :halted? false
                                :ready-queue (:ready-queue rt)
                                :wait-set (:wait-set rt))]
                (restore-fn base vm-entry value)))})))


(defn enqueue-woken-vm-entries
  "Translate raw transport woken entries into dao.runtime ready-queue entries
   using the VM-specific resume wrapper."
  [rt woken restore-fn]
  (let [tasks (mapv (fn [{:keys [entry value position]}]
                      (let [task (vm-task entry restore-fn)]
                        (assoc task
                               :value value
                               :position position)))
                    woken)]
    (rt/enqueue-ready rt tasks)))
