(ns yin.vm.host-ffi
  "Explicit host-side dao.stream.apply bridge state for Yin VMs.

   The bridge is data carried by the VM itself:
     {:handlers {:op/name (fn [& args] ...)}
      :cursor   {:position 0}}

   Construct VMs with {:bridge handlers} and use ordinary vm/run or vm/eval.
   This namespace provides the normalization and stepping logic those VM
   implementations delegate to."
  (:require
    [dao.stream :as ds]
    [dao.stream.apply :as dao.stream.apply]
    [yin.vm :as vm]
    [yin.vm.engine :as engine]
    [yin.vm.telemetry :as telemetry]))


(defn normalize
  "Normalize bridge input to explicit bridge state.

   Accepted forms:
   - nil
   - handler map {:op/name fn}
   - explicit bridge map {:handlers {...} :cursor {:position n}}"
  [bridge]
  (cond
    (nil? bridge)
    nil

    (and (map? bridge) (contains? bridge :handlers))
    (-> bridge
        (update :handlers #(or % {}))
        (update :cursor #(or % {:position 0})))

    (map? bridge)
    {:handlers bridge
     :cursor {:position 0}}

    :else
    (throw (ex-info "Bridge must be nil, a handler map, or {:handlers ... :cursor ...}"
                    {:bridge bridge}))))


(defn bridge-from-opts
  "Read bridge configuration from create-vm opts.

   :bridge is the canonical key."
  [opts]
  (normalize (:bridge opts)))


(defn attach
  "Attach explicit bridge state to a VM value."
  [vm bridge]
  (if-let [bridge* (normalize bridge)]
    (assoc vm :bridge bridge*)
    vm))


(defn installed?
  "True when the VM carries explicit bridge handlers."
  [vm]
  (boolean (seq (get-in vm [:bridge :handlers]))))


(defn- dispatch-call
  [handlers call-op call-args]
  (let [handler (get handlers call-op)]
    (when-not handler
      (throw (ex-info "No bridge handler for dao.stream.apply op"
                      {:op call-op
                       :available (vec (keys handlers))})))
    (apply handler (or call-args []))))


(defn- synthesize-resume-entry
  [vm call-id response]
  (let [parked (get-in vm [:parked call-id])
        cursor-data (get-in vm [:store vm/call-out-cursor-key])]
    (when-not parked
      (throw (ex-info "Cannot synthesize dao.stream.apply resume entry"
                      {:call-id call-id
                       :parked-ids (vec (keys (:parked vm)))})))
    (assoc parked
           :type :dao.stream.apply/resumer
           :value response
           :store-updates
           {vm/call-out-cursor-key
            (update cursor-data :position inc)})))


(defn bridge-step
  "Handle one pending dao.stream.apply request from the VM's call-in stream.

   Returns:
   - {:handled? true  :vm vm' ...} when a request was dispatched and resume work
     was enqueued
   - {:handled? false :vm vm  :stream-result <ds/next result>} when the VM is
     blocked for some non-bridge reason"
  [vm]
  (let [handlers (get-in vm [:bridge :handlers])
        cursor (or (get-in vm [:bridge :cursor]) {:position 0})
        store-data (vm/store vm)
        call-in (get store-data vm/call-in-stream-key)
        next-result (ds/next call-in cursor)
        ok (:ok next-result)
        cursor' (:cursor next-result)]
    (if ok
      (let [{request-id :dao.stream.apply/id
             request-op :dao.stream.apply/op
             request-args :dao.stream.apply/args} ok
            result (dispatch-call handlers request-op request-args)
            call-out (get store-data vm/call-out-stream-key)
            response (dao.stream.apply/response request-id result)
            put-result (ds/put! call-out response)
            woke (:woke put-result)
            entries (if (seq woke)
                      (engine/make-woken-run-queue-entries vm woke)
                      [(synthesize-resume-entry vm request-id response)])
            vm' (-> vm
                    (assoc-in [:bridge :cursor] cursor')
                    (update :run-queue (fnil into []) entries)
                    (telemetry/emit-snapshot :bridge
                                             {:bridge-op request-op
                                              :arg-shape (mapv telemetry/type-tag request-args)}))]
        {:handled? true
         :vm vm'
         :request ok
         :wake-count (count woke)
         :entry-count (count entries)})
      {:handled? false
       :vm vm
       :stream-result next-result})))


(defn maybe-run
  "Run a VM with bridge dispatch if explicit bridge handlers are installed.

   run-fn must be the VM-specific raw runner that advances the VM until halt or
   block without recursively calling vm/run again."
  [vm run-fn]
  (if-not (installed? vm)
    (run-fn vm)
    (loop [v (run-fn vm)]
      (if (:blocked? v)
        (let [{:keys [handled? vm]} (bridge-step v)]
          (if handled?
            (recur (run-fn vm))
            v))
        v))))


(defn run-with-bridge
  "Compatibility helper: attach handlers to a VM and return the final value.

   New code should prefer constructing the VM with {:bridge handlers} and then
   using ordinary vm/run or vm/eval."
  [vm handlers]
  (-> vm
      (attach handlers)
      (vm/run)
      (vm/value)))
