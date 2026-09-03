(ns dao.stream.v2.forward
  "A single, host-agnostic interpreter step for copying one DaoStream to another.

  `forward-step` owns no scheduler, callback, registry, or mutable state.  A
  caller supplies the source cursor and calls the step again with the returned
  state when its driver chooses."
  (:require [dao.stream.v2 :as stream]))


(def default-options
  "Defaults for `forward-step`: one value per call and terminal gap policy."
  {:batch-budget 1
   :gap-policy :terminate})


(defn initial-state
  "Create the state consumed by `forward-step` from a source cursor."
  [cursor]
  {:cursor cursor})


(defn- result-state
  [cursor status forwarded outcome]
  (cond-> {:cursor cursor
           :status status
           :forwarded forwarded}
    outcome (assoc :outcome outcome)))


(defn- malformed-result
  "Turn a defective host implementation answer into the total outcome algebra.
  A conforming operation already returns one of the contract outcomes; this
  guard keeps the interpreter's branch total if a composition supplies a bad
  handle."
  [operation result]
  (if (stream/valid-outcome? operation result)
    result
    {:dao.stream/outcome :dao.stream/transport-error
     :dao.stream/error :dao.stream/invalid-operation-result}))


(defn- terminal-status
  [side outcome]
  ;; Keep dispatch scalar: ClojureDart cannot compile hashed vector constants
  ;; in `case` expressions.
  (if (= side :source)
    (case outcome
      :dao.stream/end :source-ended
      :dao.stream/gap :source-gap
      :dao.stream/cursor-mismatch :source-cursor-mismatch
      :dao.stream/invalid-cursor :source-invalid-cursor
      :dao.stream/transport-error :source-transport-error
      :transport-error)
    (case outcome
      :dao.stream/closed :destination-closed
      :dao.stream/invalid-value :destination-invalid-value
      :dao.stream/transport-error :destination-transport-error
      :transport-error)))


(defn- gap-action
  [policy gap-result]
  (let [action (if (fn? policy) (policy gap-result) policy)]
    (case action
      :resume :resume
      :terminate :terminate
      :terminate)))


(defn- positive-budget
  [options]
  (let [budget (:batch-budget options)]
    (if (and (integer? budget) (not (neg? budget)))
      budget
      0)))


(defn forward-step
  "Copy at most `:batch-budget` values from source to destination.

  `source` must provide the DaoStream reader surface and `destination` the
  writer surface. `state` is normally produced by `initial-state` or an earlier
  call and contains `:cursor`. Options are ordinary data:

  * `:batch-budget` is a non-negative integer. A zero budget performs no read.
  * `:gap-policy` is `:resume` (continue from the gap result's recovery
    cursor), `:terminate`, or a function receiving the gap result and returning
    one of those keywords. A resume forwards no value, so it is bounded
    separately: at most `:batch-budget` resumes happen in one call, after which
    the step returns `:continue` at the last recovery cursor. A recovery cursor
    equal to the cursor just read is a transport fixed point rather than
    progress, and terminates the step as `:source-gap`.

  The returned map always has `:cursor`, `:status`, and `:forwarded`.  It also
  has `:outcome` whenever the last source/destination operation produced one.
  `:continue` and `:retry` are non-terminal statuses. `:retry` is returned for
  source `blocked` and destination `full`, and neither advances the cursor.
  All other operation outcomes are terminal and are preserved as `:outcome`.
  In particular, `end` only stops this interpreter; it never closes either
  handle."
  ([source destination state]
   (forward-step source destination state default-options))
  ([source destination state options]
   (let [state (if (map? state) state {})
         options (merge default-options (or options {}))
         cursor (:cursor state)
         status (:status state)]
     ;; Terminal state is data owned by the driver. Re-stepping it is a no-op,
     ;; which makes accidental extra driver ticks harmless and idempotent.
     (if (and status (not (contains? #{:continue :retry} status)))
       state
       (loop [cursor cursor
              remaining (positive-budget options)
              resumes (positive-budget options)
              forwarded 0]
         (if (zero? remaining)
           (result-state cursor :continue forwarded nil)
           (let [read-result (malformed-result :next (stream/next source cursor))
                 read-outcome (:dao.stream/outcome read-result)]
             (case read-outcome
               :dao.stream/ok
               (let [next-cursor (:dao.stream/cursor read-result)
                     write-result (malformed-result
                                    :append!
                                    (stream/append! destination
                                                    (:dao.stream/value read-result)))
                     write-outcome (:dao.stream/outcome write-result)]
                 (case write-outcome
                   :dao.stream/ok
                   (recur next-cursor (dec remaining) resumes (inc forwarded))

                   :dao.stream/full
                   (result-state cursor :retry forwarded write-outcome)

                   (result-state cursor
                                 (terminal-status :destination write-outcome)
                                 forwarded
                                 write-outcome)))

               :dao.stream/blocked
               (result-state cursor :retry forwarded read-outcome)

               :dao.stream/gap
               (let [recovery (:dao.stream/cursor read-result)]
                 (cond
                   ;; A recovery cursor that does not move cannot be observed
                   ;; from, whatever the policy asks for.  Terminating keeps the
                   ;; step total against a defective transport.
                   (or (not= :resume (gap-action (:gap-policy options) read-result))
                       (= recovery cursor))
                   (result-state cursor :source-gap forwarded read-outcome)

                   (pos? resumes)
                   (recur recovery remaining (dec resumes) forwarded)

                   ;; The resume allowance is spent.  Yield the recovered cursor
                   ;; so the driver keeps this step's progress and decides when
                   ;; to step again.
                   :else (result-state recovery :continue forwarded nil)))

               ;; end and every source-side failure are terminal. No close is
               ;; implied: the composition decides transport-specific teardown.
               (result-state cursor
                             (terminal-status :source read-outcome)
                             forwarded
                             read-outcome)))))))))
