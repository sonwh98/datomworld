(ns yin.vm.v2.ffi
  "Explicit host-side `dao.stream.v2.apply` bridge state for Yin VMs.

   The bridge is data carried by the VM itself:

     {:handlers {:op/name (fn [& args] ...)}
      :cursor   <opaque cursor on the call-in stream>}

   Three things the port had to settle:

   - **Minting moved.** v1's `normalize` fabricated `{:position 0}` from a
     bare handler map and never saw a handle. An opaque cursor has nothing to
     mint against there, so `attach` mints it, where the store is visible.
     `attach` runs on a built VM, so with no call pair it errors exactly as
     construction does.
   - **The step is once-only.** A computed response and the successor cursor
     it belongs to are retained until the append succeeds, so a handler runs
     at most once per request and a `full` response stream never drops one.
   - **A gap at the bridge cursor is fatal.** The FFI pair is sized for the
     outstanding calls plus one, so a gap there means a request or a response
     was evicted; the parked call it belonged to would wait forever. Reporting
     it beats hanging.

   Waiters are gone: `park-and-call` places its continuation in the polling
   wait set and the ordinary scheduler wakes it when the response lands."
  (:require [dao.data :as data]
            [dao.stream.v2 :as stream]
            [dao.stream.v2.apply :as apply2]
            [yin.vm.v2 :as vm]
            [yin.vm.v2.telemetry :as telemetry]))


(defn call-pair
  "Return {:call-in h :call-out h} from a VM store, or nil when the VM was
   built without a pair."
  [store]
  (let [in (get store vm/call-in-stream-key)
        out (get store vm/call-out-stream-key)]
    (when (and in out) {:call-in in, :call-out out})))


(defn require-call-pair!
  "Fail when the VM holds no FFI pair. Callers must run this before parking:
   an error raised after `park-continuation` strands a continuation in
   `:parked` and consumes an id counter."
  [store what]
  (or (call-pair store)
      (throw (ex-info "This VM was constructed without an FFI call pair, so it cannot make a dao.stream.v2.apply call"
                      {:what what}))))


(defn call-response-wait-entry
  "The polling wait entry for a sent call awaiting its correlated response."
  [call-id next-k env]
  {:k {:type :dao.stream.v2.apply/eval-call,
       :next next-k,
       :env env,
       :call-id call-id},
   :env env,
   :cursor-ref {:type :cursor-ref, :id vm/call-out-cursor-key},
   :reason :next,
   :stream-id vm/call-out-stream-key})


(defn call-result
  "Unwrap a response envelope for the continuation that made the call.

   v1 read `:dao.stream.apply/value` off a response that could only succeed.
   A v2 response carries exactly one of `ok` or `error`, and correlation is
   checked here rather than assumed from stream order."
  [response call-id]
  (when-not (apply2/response? response)
    (throw (ex-info "FFI response envelope is malformed" {:response response})))
  (when (and call-id (not= call-id (apply2/response-id response)))
    (throw (ex-info "FFI response does not correlate with this parked call"
                    {:call-id call-id,
                     :response-id (apply2/response-id response)})))
  (if-let [err (apply2/response-error response)]
    (throw (ex-info (str "FFI call failed: "
                         (:dao.stream.v2.apply/message err))
                    {:call-id call-id, :error err}))
    (apply2/response-ok response)))


(defn normalize
  "Normalize bridge input to explicit bridge state, without minting.

   Accepted forms: nil, a handler map, or an explicit
   {:handlers ... :cursor ...} map."
  [bridge]
  (cond (nil? bridge) nil
        (and (map? bridge) (contains? bridge :handlers))
        (update bridge :handlers #(or % {}))
        (map? bridge) {:handlers bridge}
        :else (throw (ex-info
                       "Bridge must be nil, a handler map, or {:handlers ... :cursor ...}"
                       {:bridge bridge}))))


(defn attach
  "Attach explicit bridge state to a built VM, minting the bridge cursor on
   the call-in stream when the bridge does not carry one."
  [vm bridge]
  (if-let [bridge* (normalize bridge)]
    (let [{:keys [call-in]} (require-call-pair! (:store vm) :bridge)
          cursor (or (:cursor bridge*) (vm/mint-oldest call-in :bridge))]
      (assoc vm :bridge (assoc bridge* :cursor cursor)))
    vm))


(defn bridge-from-opts
  "Read bridge configuration from create-vm opts. `:bridge` is the canonical
   key. Minting happens in `attach`, which runs after the pair exists."
  [opts]
  (normalize (:bridge opts)))


(defn installed?
  "True when the VM carries explicit bridge handlers."
  [vm]
  (boolean (seq (get-in vm [:bridge :handlers]))))


(defn- retain
  [vm response successor request-id]
  (update vm
          :bridge
          assoc
          :pending-response response
          :pending-successor successor
          :pending-request-id request-id))


(defn- clear-pending
  [vm]
  (update vm
          :bridge
          (fn [b]
            (dissoc b :pending-response :pending-successor :pending-request-id))))


(defn- deliver-response
  "Append one computed response, total over the five append outcomes.

   `ok` advances the bridge cursor to the retained successor exactly once.
   `full` retains both response and successor without advancing, so the
   handler is not re-run. The three terminal outcomes advance once, report the
   response undeliverable, and terminate the bridge."
  [vm response successor request-id]
  (let [call-out (get (:store vm) vm/call-out-stream-key)
        result (apply2/put-response! call-out response)
        o (:dao.stream/outcome result)]
    (case o
      :dao.stream/ok {:handled? true,
                      :vm (-> vm
                              (clear-pending)
                              (assoc-in [:bridge :cursor] successor)),
                      :request-id request-id}
      :dao.stream/full {:handled? false,
                        :retained? true,
                        :vm (retain vm response successor request-id)}
      ;; Includes :dao.stream.v2.apply/invalid-response, which put-response!
      ;; reports rather than appending.
      {:handled? false,
       :terminal o,
       :undeliverable response,
       :vm (-> vm
               (clear-pending)
               (assoc-in [:bridge :cursor] successor)
               (assoc-in [:bridge :terminal] o))})))


(defn bridge-step
  "Handle one pending `dao.stream.v2.apply` request from the VM's call-in
   stream.

   Returns {:handled? true :vm vm' :request-id id} when a response was
   appended, or {:handled? false :vm vm ...} otherwise. A `:terminal` key
   names the outcome that ended the bridge."
  [vm]
  (let [bridge (:bridge vm)
        handlers (:handlers bridge)
        cursor (:cursor bridge)]
    (cond
      (:terminal bridge) {:handled? false, :vm vm, :terminal (:terminal bridge)}
      (:pending-response bridge)
      (deliver-response vm
                        (:pending-response bridge)
                        (:pending-successor bridge)
                        (:pending-request-id bridge))
      :else
      (let [call-in (get (:store vm) vm/call-in-stream-key)
            _ (when (nil? call-in)
                (throw (ex-info "This VM has no call-in stream to bridge"
                                {:bridge? (some? bridge)})))
            result (stream/next call-in cursor)
            o (:dao.stream/outcome result)]
        (case o
          :dao.stream/ok
          (let [request (:dao.stream/value result)
                successor (:dao.stream/cursor result)
                response (apply2/dispatch-request handlers request)]
            (if (nil? response)
              ;; A malformed request without a usable id is a local diagnostic
              ;; and advances once; no handler ever saw it.
              {:handled? false,
               :diagnostic :dao.stream.v2.apply/malformed-request,
               :vm (assoc-in vm [:bridge :cursor] successor)}
              (deliver-response
                (telemetry/emit-snapshot
                  vm
                  :bridge
                  {:bridge-op (apply2/request-op request),
                   :arg-shape (mapv data/tag
                                    (or (apply2/request-args request) []))})
                response
                successor
                (apply2/request-id request))))
          :dao.stream/blocked {:handled? false, :vm vm, :outcome o}
          :dao.stream/gap
          (throw (ex-info "Gap at the FFI bridge cursor: a request was evicted and the call that made it can never be answered"
                          {:vm-model (:vm-model vm)}))
          ;; :end, :cursor-mismatch, :invalid-cursor, :transport-error
          {:handled? false,
           :terminal o,
           :vm (assoc-in vm [:bridge :terminal] o)})))))


(defn maybe-run
  "Run a VM with bridge dispatch if explicit bridge handlers are installed.

   run-fn must be the VM-specific raw runner that advances the VM until halt
   or block without recursively calling vm/run again."
  [vm run-fn]
  (if-not (installed? vm)
    (run-fn vm)
    (loop [v (run-fn vm)]
      (if (:blocked? v)
        (let [{:keys [handled? diagnostic vm]} (bridge-step v)]
          (cond handled? (recur (run-fn vm))
                ;; A malformed request consumed as a diagnostic advanced the
                ;; cursor, so there may be a real request behind it.
                diagnostic (recur vm)
                :else vm))
        v))))
