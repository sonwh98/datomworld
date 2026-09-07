(ns dao.stream.v2.forward
  "A single, host-agnostic interpreter step for copying one DaoStream to another.

  `forward-step` owns no scheduler, callback, registry, or mutable state.  A
  caller supplies the source cursor and calls the step again with the returned
  state when its driver chooses."
  (:require [dao.stream.v2 :as stream]
            [dao.stream.v2.observe :as observe]))


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
           (let [observed (observe/step source
                                        cursor
                                        (fn [value]
                                          (stream/append! destination value)))
                 outcome (:outcome observed)]
             (case (:status observed)
               ;; The cursor advanced only because the append answered ok;
               ;; `observe/step` owns that ordering.
               :advance
               (recur (:cursor observed) (dec remaining) resumes (inc forwarded))

               ;; Source `blocked` and destination `full` are the same answer
               ;; to this interpreter: no progress, cursor kept, step again.
               :retry
               (result-state cursor :retry forwarded outcome)

               :gap
               (let [recovery (:recovery observed)]
                 (cond
                   ;; A recovery cursor that does not move cannot be observed
                   ;; from, whatever the policy asks for.  Terminating keeps the
                   ;; step total against a defective transport.
                   (or (not= :resume
                             (gap-action (:gap-policy options) (:read observed)))
                       (= recovery cursor))
                   (result-state cursor :source-gap forwarded outcome)

                   (pos? resumes)
                   (recur recovery remaining (dec resumes) forwarded)

                   ;; The resume allowance is spent.  Yield the recovered cursor
                   ;; so the driver keeps this step's progress and decides when
                   ;; to step again.
                   :else (result-state recovery :continue forwarded nil)))

               ;; A destination answer that is neither ok nor full.
               :failed
               (result-state cursor
                             (terminal-status :destination outcome)
                             forwarded
                             outcome)

               ;; end and every source-side failure are terminal. No close is
               ;; implied: the composition decides transport-specific teardown.
               (result-state cursor
                             (terminal-status :source outcome)
                             forwarded
                             outcome)))))))))
