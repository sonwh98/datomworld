(ns yin.vm.v2.telemetry
  "Telemetry stub for the v2 VM slice.

   v1's `emit-snapshot` short-circuits on `(if-not (enabled? state) state ...)`,
   and `enabled?` asks whether a telemetry stream is installed. With none
   installed, every call site in the engine, the FFI and the evaluator is
   already a no-op returning state unchanged. This namespace makes that the
   whole implementation: nothing appends, nothing is created, no capacity is
   chosen.

   Classification no longer lives here: `ffi` calls `dao.data/tag` directly,
   and its `(mapv data/tag request-args)` is evaluated as an *argument* to
   `emit-snapshot`, so it runs before the no-op check. `dao.data/tag` orders
   the descriptor check before `map?`/`sequential?`, the ordering this stub
   once deferred to the real emit path.

   A supplied `:telemetry` option is rejected rather than ignored. A stub that
   merely recorded the model would accept a stream and then write nothing to it
   forever; silent acceptance is the one way this stub could mislead.

   What is deferred with the real emit path: reading `append!` outcomes,
   summarising a cursor-ref without descent, and the cursor recognition
   problem — v2 cursors are opaque and the contract offers no predicate
   for one.")


(def deferral-message
  "Telemetry is deferred in the yin.vm.v2 slice; a :telemetry option would name a stream that is never written")


(defn reject-telemetry-opt!
  "Throw when a non-nil telemetry configuration is supplied."
  [telemetry]
  (when (some? telemetry)
    (throw (ex-info deferral-message {:telemetry telemetry})))
  nil)


(defn enabled?
  "Always false in this slice."
  [_state]
  false)


(defn install
  "Record the VM model. Rejects a supplied `:telemetry` option."
  [state model]
  (reject-telemetry-opt! (:telemetry state))
  (assoc state
         :vm-model model
         :telemetry-step (or (:telemetry-step state) 0)
         :telemetry-t (or (:telemetry-t state) 0)))


(defn next-telemetry-state
  [state]
  state)


(defn emit-snapshot
  "Identity on state. Both arities survive: the FFI and the evaluator use
   different ones."
  ([state _phase] state)
  ([state _phase _opts] state))
