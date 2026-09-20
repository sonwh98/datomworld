(ns dao.stream.waitset.cadence
  "The pure idle curve for a wait-set owner: how long to sleep after a round
   that moved nothing.

   Cadence belongs to the runtime driving the interpreters — `dao.stream.md`,
   *What it costs* — and this namespace is the whole decidable-as-data part
   of it. `cadence-step` never calls `check`, reads no clock, and touches no
   stream, so it is testable on every host with no fixture at all: the curve
   is arithmetic on values the host supplied.

   The host loop owns everything else. It computes `moved?` from its own
   round — any woken entry, any progress a consumer step reports, or any
   outstanding pending write, which must never wait out a backoff ceiling —
   threads the returned `:cadence-state` into the next call, and sleeps for
   `:sleep-ms` through its host's wake source (`dao.stream.waitset.driver`).
   The right cadence for a REPL is not the right cadence for a serving
   endpoint; neither is this namespace's to know."
  )


(defn init
  "The cadence state for `params`, a host-supplied map:

     {:poll-ms 25 :backoff {:factor 2 :ceiling-ms 200}}

   `:poll-ms` is the base interval a wake resets to; `:backoff` is optional
   and climbs an idle round by `:factor` up to `:ceiling-ms`, where it stays.
   A fresh state is armed at `:poll-ms`. Parameters are composition data, so
   a defect in them is named here rather than silently rounded: a
   non-positive `:poll-ms`; a `:backoff` missing its factor or ceiling; a
   `:factor` that is not a number of at least 1 (a smaller factor would
   shrink the interval toward a zero busy-loop); or a `:ceiling-ms` below
   `:poll-ms` (the curve must never idle faster than its base) — all throw
   before any host sleeps on them."
  [{:keys [poll-ms backoff], :as params}]
  (when-not (and (number? poll-ms) (pos? poll-ms))
    (throw (ex-info "cadence needs a positive :poll-ms" {:params params})))
  (doseq [k [:factor :ceiling-ms]]
    (when (and backoff (not (contains? backoff k)))
      (throw (ex-info "a cadence :backoff needs :factor and :ceiling-ms"
                      {:params params}))))
  (when backoff
    (when-not (and (number? (:factor backoff)) (>= (:factor backoff) 1))
      (throw (ex-info "a cadence :backoff :factor must be a number of at least 1"
                      {:params params})))
    (when-not (and (number? (:ceiling-ms backoff))
                   (>= (:ceiling-ms backoff) poll-ms))
      (throw (ex-info "a cadence :backoff :ceiling-ms must be a number of at least :poll-ms"
                      {:params params}))))
  (assoc params :current poll-ms))


(defn cadence-step
  "One round of the idle curve. `moved?` resets it: the next sleep is
   `:poll-ms` again, whatever it had climbed to. An idle round arms the
   interval the curve is already at, then climbs the curve — `:current ×
   :factor`, capped at `:ceiling-ms`, flat once there — so a fresh owner's
   first idle sleep is `:poll-ms` itself, and one without a `:backoff`
   stays at `:poll-ms` forever.

   Returns `{:cadence-state … :sleep-ms …}`: the state is threaded into the
   next call unchanged by the host, and `:sleep-ms` arms the host's sleep.
   Neither the ceiling nor a reset is stored anywhere else — whether
   anything moved is derived per round, never retained."
  [cadence-state moved?]
  (let [poll-ms (:poll-ms cadence-state)
        current (or (:current cadence-state) poll-ms)
        {:keys [factor ceiling-ms], :or {factor 1}, :as backoff} (:backoff cadence-state)
        base (if moved? poll-ms current)
        armed (if backoff (min ceiling-ms base) base)
        next-current (cond
                       moved? poll-ms
                       (nil? backoff) armed
                       :else (min ceiling-ms (* factor armed)))]
    {:cadence-state (assoc cadence-state :current next-current)
     :sleep-ms armed}))
