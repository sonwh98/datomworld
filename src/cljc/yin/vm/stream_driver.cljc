(ns yin.vm.stream-driver
  (:require
    [dao.stream :as ds]))


(defn ready-for-ingress?
  "Returns true when the VM is between evaluations and can poll :in-stream."
  [vm]
  (let [has-bytecode? (contains? vm :bytecode)
        bytecode (:bytecode vm)
        no-bytecode? (and has-bytecode?
                          (or (nil? bytecode)
                              (and (sequential? bytecode) (empty? bytecode))))]
    (and (not (:blocked vm))
         (empty? (or (:run-queue vm) []))
         (empty? (or (:wait-set vm) []))
         (nil? (:k vm))
         (or (:halted vm)
             (nil? (:control vm))
             no-bytecode?))))


(defn ingest-next-program
  "Poll one program batch from in-stream and hand it to ingest-fn.
   Returns {:status :ok|:blocked|:end :state vm'}."
  [vm in-stream ingest-fn]
  (let [cursor (or (:in-cursor vm) {:position 0})
        result (ds/next in-stream cursor)]
    (cond
      (map? result)
      (let [cursor' (:cursor result)
            vm' (-> (assoc vm :in-cursor cursor')
                    (ingest-fn (:ok result))
                    (assoc :in-cursor cursor'))]
        {:status :ok, :state vm'})

      (= :blocked result)
      {:status :blocked, :state (assoc vm :halted true :blocked false)}

      (= :end result)
      {:status :end, :state (assoc vm :halted true :blocked false)}

      (= :daostream/gap result)
      (throw (ex-info "VM ingress cursor fell behind the input stream"
                      {:cursor cursor
                       :vm-model (:vm-model vm)}))

      :else
      (throw (ex-info "Unexpected DaoStream response while ingesting VM program"
                      {:result result
                       :vm-model (:vm-model vm)})))))


(defn step-on-stream
  "If the VM is idle, ingest one pending program batch before executing a single step."
  [vm in-stream ingest-fn step-fn]
  (if (ready-for-ingress? vm)
    (let [{:keys [status state]} (ingest-next-program vm in-stream ingest-fn)]
      (case status
        :ok (step-fn state)
        state))
    (step-fn vm)))
