(ns yin.vm.v2.stream-driver
  "Ingress: poll one program batch from the VM's `:in-stream` between
   evaluations.

   Two things change from v1. The ingress cursor is never fabricated — it is
   minted `:dao.stream/oldest` when the VM first holds the handle and is
   thereafter whatever the transport last returned. And `next` answers with an
   outcome map over a closed set, so every outcome has a branch here.

   A `gap` is recoverable at ingress and only here. v1 threw, because its
   transport could not evict; a v2 composition may hand the VM an
   evict-oldest transport, and a batch the VM never saw is a program it never
   ran. Losing it is reported by advancing to the recovery cursor and counting
   the gap, and ingestion continues with the next batch. Silently pretending
   the stream was contiguous is the failure this avoids."
  (:require [dao.stream.v2 :as stream]))


(defn ready-for-ingress?
  "Returns true when the VM is between evaluations and can poll :in-stream."
  [vm]
  (let [has-bytecode? (contains? vm :bytecode)
        bytecode (:bytecode vm)
        no-bytecode? (and has-bytecode?
                          (or (nil? bytecode)
                              (and (sequential? bytecode) (empty? bytecode))))]
    (and (not (:blocked? vm))
         (empty? (or (:ready-queue vm) []))
         (empty? (or (:wait-set vm) []))
         (nil? (:k vm))
         (or (:halted? vm) (nil? (:control vm)) no-bytecode?))))


(defn- idle
  [vm]
  (assoc vm :halted? true :blocked? false))


(defn ingest-next-program
  "Poll one program batch from in-stream and hand it to load-fn.
   Returns {:status :ok|:blocked|:end|:gap :state vm'}."
  [vm in-stream load-fn]
  (let [cursor (:in-cursor vm)
        result (stream/next in-stream cursor)
        outcome (:dao.stream/outcome result)]
    (case outcome
      :dao.stream/ok
      (let [cursor' (:dao.stream/cursor result)
            vm' (-> (assoc vm :in-cursor cursor')
                    (load-fn (:dao.stream/value result))
                    (assoc :in-cursor cursor'))]
        {:status :ok, :state vm'})
      :dao.stream/blocked {:status :blocked, :state (idle vm)}
      :dao.stream/end {:status :end, :state (idle vm)}
      :dao.stream/gap {:status :gap,
                       :state (-> vm
                                  (assoc :in-cursor (:dao.stream/cursor result))
                                  (update :ingress-gaps (fnil inc 0)))}
      (:dao.stream/cursor-mismatch :dao.stream/invalid-cursor
                                   :dao.stream/transport-error)
      (throw (ex-info "VM ingress cannot continue from this cursor"
                      {:outcome outcome, :vm-model (:vm-model vm)}))
      (throw (ex-info "Unexpected DaoStream outcome while ingesting VM program"
                      {:outcome outcome, :vm-model (:vm-model vm)})))))


(defn step-on-stream
  "If the VM is idle, ingest one pending program batch before executing a
   single step. A gap consumes the step: the loss is recorded and the caller
   sees an idle VM whose cursor has moved to the recovery point."
  [vm in-stream load-fn step-fn]
  (cond (and in-stream (ready-for-ingress? vm))
        (let [{:keys [status state]}
              (ingest-next-program vm in-stream load-fn)]
          (case status
            :ok (step-fn state)
            state))
        (ready-for-ingress? vm) vm
        :else (step-fn vm)))
