(ns dao.space.query.execution
  "Generic bounded-execution machinery for dao.space query
   (docs/design/dao.space.query.datomic-compat.md, Normative execution
   bounds). It names no Client option and no query clause.

   One invocation owns one IMMUTABLE state: progress, remaining work
   allowance, deadline data, phase. Every operation takes a state and returns
   a new state; nothing here is an atom, volatile, dynamic var, callback or
   namespace-level clock. A caller that forks the same state into two
   branches has not threaded it: each step must receive the state the prior
   step returned.

   Time enters only through an explicit driver (`IDriver`): a synchronous
   observation the driver takes when a checkpoint asks for one. The driver is
   never registered and never stored in the state; the deadline is absolute
   data in the same unit the driver reports.

   Exhaustion is a terminal DATA outcome, not an exception: once a state
   is terminal, every later charge, observation and read returns the same
   outcome without touching the driver (no read after observed expiry). The
   public adapter translates the outcome to an exception.

   Status (Phase 4a): the state is threaded through the one evaluation
   kernel in `dao.space.query` (`q-with-state`) and the datomic adapter; legacy
   `q` runs the same kernel with `unbounded`. Storage reads inside a lazy
   B-tree range are NOT checkpointed per node fetch: bounded calls are
   admitted only over materialized sources, and checked published-index
   traversal is the separately reviewed Phase 4b.")


(defprotocol IDriver

  (-observe
    [driver]
    "One synchronous time observation, a number in the deadline's unit."))


(defrecord FnDriver
  [now-fn]

  IDriver

  (-observe [_] (now-fn)))


(defn fn-driver
  "A driver over a zero-argument now-fn (monotonic where the host offers
   one; a wall-clock fallback must be documented by the composer)."
  [now-fn]
  (->FnDriver now-fn))


(def step-units
  "The operation-to-unit table, as data. Work units are logical accounting,
   not CPU instructions or elapsed time. A candidate step is one attempted
   candidate-binding production, charged whether it is retained or rejected.
   The other entries are the explicit units for operations that could
   otherwise continue without attempting a binding. Opaque host functions,
   parsing primitives and storage calls are INDIVISIBLE: one unit does not
   preempt them."
  {:candidate 1
   :scan-advance 1
   :rule-entry 1
   :aggregate-input 1
   :pull-advance 1
   :tree-step 1
   :node-read 1
   :parse-step 1
   :output-row 1})


(def phases
  "Phase boundaries observed at entry and exit, plus :read, the phase a
   storage-read bracket (`read-step`) records: a deadline that passes during a
   node fetch reports :read as its phase of death."
  [:parse :realize :plan :join :nested :recursion :aggregate :pull :output :read])


(defn start
  "A fresh invocation state. `:deadline` is absolute data in the driver's
   unit (nil: none); `:max-steps` is the work allowance (nil: unbounded, no
   ambient default)."
  [{:keys [deadline max-steps]}]
  (when-not (or (nil? max-steps) (and (integer? max-steps) (pos? max-steps)))
    (throw (ex-info "max-steps must be a positive integer" {:option :max-steps})))
  {::steps 0
   ::remaining max-steps
   ::deadline deadline
   ::phase nil
   ::outcome nil})


(def unbounded
  "The explicit never-expiring policy legacy q composes with."
  (start {}))


(defn outcome
  "The terminal outcome data, or nil while the state is live."
  [state]
  (::outcome state))


(defn terminal?
  [state]
  (some? (::outcome state)))


(defn steps
  [state]
  (::steps state))


(defn phase
  [state]
  (::phase state))


(defn- terminal-with
  [state kind]
  (assoc state ::outcome {:kind kind
                          :phase (::phase state)
                          :steps (::steps state)}))


(defn charge
  "Consume the units of operation `op` (a key of `step-units`, or an
   integer count). Refuses BEFORE performing the unit when no allowance
   remains: the state turns terminal :step-budget-exhausted and the unit is
   not consumed. No clock is read. A terminal state is returned unchanged."
  [state op]
  (let [n (if (integer? op) op (get step-units op))]
    (when-not (and (integer? n) (pos? n))
      (throw (ex-info "unknown or non-positive step unit" {:variable (str op)})))
    (cond
      (terminal? state) state
      (and (some? (::remaining state)) (< (::remaining state) n))
      (terminal-with state :step-budget-exhausted)
      :else (cond-> (update state ::steps + n)
              (some? (::remaining state)) (update ::remaining - n)))))


(defn enter
  "Record a phase without observing the clock (the next step observes). A
   terminal state is returned unchanged."
  [state phase]
  (if (terminal? state) state (assoc state ::phase phase)))


(defn bounded?
  "True when the state carries a deadline or a work allowance."
  [state]
  (or (some? (::deadline state)) (some? (::remaining state))))


(defn- observe
  "Observe the deadline without changing the phase. A terminal state is
   returned as the SAME value and the driver is not touched."
  [state driver]
  (cond
    (terminal? state) state
    (nil? (::deadline state)) state
    (>= (-observe driver) (::deadline state)) (terminal-with state :timeout)
    :else state))


(defn checkpoint
  "Observe the deadline at a named phase boundary. A terminal state is
   absorbing: it is returned unchanged (equal as a whole value, phase
   included) and the driver is not touched. Otherwise the phase is recorded
   and, when a deadline exists, the driver is observed; expiry (observation
   >= deadline) turns the state terminal :timeout."
  [state driver phase]
  (if (terminal? state)
    state
    (observe (assoc state ::phase phase) driver)))


(defn step
  "One accountable unit, in the normative order: observe the deadline; if
   live, charge the unit BEFORE it is performed (refusing when no
   allowance remains). The caller performs the unit only when the returned
   state is live, and observes again at its post-operation boundary. A
   terminal state is returned unchanged, no clock read, no unit consumed."
  [state driver op]
  (let [state (observe state driver)]
    (if (terminal? state) state (charge state op))))


(defn read-step
  "Run ONE query-controlled storage read under the one-in-flight
   guarantee. Observe before the read (a terminal or expired state starts
   no read); run `read-fn` (indivisible: it may overshoot the deadline);
   observe when it returns, before anything else can start another.
   Returns [state value]. The value is ::not-read when no read started and
   ::discarded when the post-read observation expired: a value whose
   post-read observation expired is never delivered."
  [state driver read-fn]
  (let [state (checkpoint state driver :read)]
    (if (terminal? state)
      [state ::not-read]
      (let [v (read-fn)
            state (checkpoint state driver :read)]
        (if (terminal? state)
          [state ::discarded]
          [state v])))))


(defn run-reads
  "Perform `read-fns` in order, each through `read-step`, threading the
   state. Stops at the first terminal state; later reads never start.
   Returns [state values]."
  [state driver read-fns]
  (reduce (fn [[st acc] f]
            (let [[st v] (read-step st driver f)]
              (if (terminal? st)
                (reduced [st acc])
                [st (conj acc v)])))
          [state []]
          read-fns))


(defn finish
  "The final observation at the end of the pass. Exact-boundary completion
   succeeds only when the state is live after it."
  [state driver]
  (checkpoint state driver :output))
