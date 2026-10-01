(ns yang.python.antlr.stage
  "One compiler stage: an interpreter that reads one dao.stream and writes
   others (docs/design/yang.antlr.md §1.1, §5.4, §6.2).

   A stage is a value:

     {:in      reader handle
      :cursor  its read position
      :outs    {port writer-handle}
      :outbox  [[port value] ...]   ; staged, not yet appended
      :state   the transform's own state
      :status  :blocked | :end | :full | nil}

   `step` flushes the outbox, then reads until the input blocks or ends or an
   output is full. The transform is a pure function
   `(transform state value) -> [state' [[port value] ...]]`; it never touches
   a stream. A `full` append keeps the identical staged value and yields; a
   `blocked` read keeps state and cursor and yields. Nothing buffers without
   bound: a non-empty outbox stops reading."
  (:require
    [dao.stream :as stream]))


(defn- oldest-cursor
  [handle]
  (let [result (stream/cursor handle stream/anchor-oldest)]
    (when-not (= :dao.stream/ok (:dao.stream/outcome result))
      (throw (ex-info "Stage input refused a cursor" {:result result})))
    (:dao.stream/cursor result)))


(defn open
  "A stage reading `in` from its oldest value and writing the `outs` ports."
  [in outs initial-state]
  {:in in,
   :cursor (oldest-cursor in),
   :outs outs,
   :outbox [],
   :state initial-state,
   :status nil})


(defn- flush-outbox
  "Append staged values in order; stop at the first that is not appended."
  [{:keys [outs], :as stage}]
  (loop [stage stage]
    (if-let [[port value] (first (:outbox stage))]
      (let [handle (get outs port)
            _ (when-not handle
                (throw (ex-info "Stage wrote an unwired port" {:port port})))
            result (stream/append! handle value)
            outcome (:dao.stream/outcome result)]
        (case outcome
          :dao.stream/ok (recur (update stage :outbox (comp vec rest)))
          :dao.stream/full (assoc stage :status :full)
          (throw (ex-info "Stage output refused a value"
                          {:port port, :outcome outcome}))))
      stage)))


(defn step
  "Advance the stage as far as its streams allow; returns the new stage."
  [stage transform]
  (loop [stage (flush-outbox (assoc stage :status nil))]
    (cond
      (= :full (:status stage)) stage
      (seq (:outbox stage)) stage
      :else
      (let [result (stream/next (:in stage) (:cursor stage))
            outcome (:dao.stream/outcome result)]
        (case outcome
          :dao.stream/ok
          (let [[state' emitted] (transform (:state stage)
                                            (:dao.stream/value result))]
            (recur (flush-outbox (assoc stage
                                        :cursor (:dao.stream/cursor result)
                                        :state state'
                                        :outbox (vec emitted)))))
          :dao.stream/blocked (assoc stage :status :blocked)
          :dao.stream/end (assoc stage :status :end)
          (throw (ex-info "Stage input read failed" {:outcome outcome})))))))
