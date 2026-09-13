(ns dao.stream.v2.observer
  "Observer of one DaoStream medium: attachment, cursor, gap accounting, and
   the coordination loop that feeds a consumer one batch at a time.

   The medium exists independently of any consumer. Host composition supplies
   a unary attach capability and a portable DaoStream descriptor; `attach`
   calls that capability exactly once, validates the returned handle against
   the reader surface, and mints the observer's cursor at `:dao.stream/oldest`
   directly through `dao.stream.v2`. The observer never creates, appends to,
   or closes the medium, and its complete initial state is
   `{:stream handle :cursor cursor :ingress-gaps 0}`.

   This namespace requires only `dao.stream.v2` and `dao.stream.v2.observe`,
   whose single `step` it loops over. It cannot depend on a ring buffer, a
   WebSocket adapter, a resolver representation, a transport key or state, or
   any consumer's namespace: transport-specific composition constructs the
   unary attacher outside this boundary, for example by partially applying a
   dispatch table or a transport resolver. The consumer is opaque here — a
   `yin.vm` evaluator, a macro expander, a `dao.space` indexer, or anything
   else that can be asked whether it is ready, handed a batch, and run.

   A `gap` is recoverable here and only here in the generic machinery. A batch
   the observer never saw is advanced past at the recovery cursor and counted,
   and observation continues with the next retained batch; silently pretending
   the medium was contiguous hides missing data. What a host does with a gap
   count is host policy — the REPL shell, for one, reports the loss and
   refuses further evaluation until reset — and that policy lives above this
   boundary. Terminal read outcomes are errors.

   Exports exactly `attach`, `observe-next`, and `run-on-stream`."
  (:require [dao.stream.v2 :as stream]
            [dao.stream.v2.observe :as observe]))


;; =============================================================================
;; Attachment
;; =============================================================================

(defn attach
  "Attach to an existing medium and return the initial observer state
   `{:stream handle :cursor cursor :ingress-gaps 0}`, or throw.

   `attach!` is a unary attachment capability: `(attach! descriptor)`. It is
   called exactly once and there is no fallback to stream creation. A
   non-`ok` attachment or cursor outcome throws `ex-info` preserving the
   original `:dao.stream/outcome`. A handle without the reader surface is a
   host assembly error reported with the descriptor and the handle's declared
   surfaces, without inventing a DaoStream outcome. Attachment cleanup stays
   with the host: this function closes nothing, and a failure leaves no
   partial observer behind."
  [attach! descriptor]
  (let [result (attach! descriptor)
        outcome (:dao.stream/outcome result)]
    (if-not (= :dao.stream/ok outcome)
      (throw (ex-info "Stream attachment failed"
                      {:dao.stream/outcome outcome,
                       :descriptor descriptor,
                       :result result}))
      (let [handle (:dao.stream/handle result)]
        (if-not (stream/reader? handle)
          (throw (ex-info "Attached handle declares no reader surface"
                          {:descriptor descriptor,
                           :surfaces (stream/declared-surfaces handle)}))
          (let [minted (stream/cursor handle stream/anchor-oldest)
                mint-outcome (:dao.stream/outcome minted)]
            (if-not (= :dao.stream/ok mint-outcome)
              (throw (ex-info "Observer cursor could not be minted"
                              {:dao.stream/outcome mint-outcome,
                               :descriptor descriptor,
                               :result minted}))
              {:stream handle,
               :cursor (:dao.stream/cursor minted),
               :ingress-gaps 0})))))))


;; =============================================================================
;; Observation
;; =============================================================================

(defn- terminal
  "Throw, preserving the original read outcome and the observer's own state."
  [message outcome observer]
  (throw (ex-info message
                  {:dao.stream/outcome outcome,
                   :cursor (:cursor observer),
                   :ingress-gaps (:ingress-gaps observer)})))


(def ^:private uncontinuable-read-outcomes
  "The contract's read outcomes this observer cannot continue from."
  #{:dao.stream/cursor-mismatch
    :dao.stream/invalid-cursor
    :dao.stream/transport-error})


(defn- answered-outcome
  "The outcome the transport actually answered with.

   `observe/step` classifies an answer outside the contract as
   `transport-error` and retains the raw answer, so the observer reports what
   it was told rather than the classification."
  [read]
  (if (contains? read :dao.stream/answer)
    (:dao.stream/outcome (:dao.stream/answer read))
    (:dao.stream/outcome read)))


(defn- observation-terminal
  "This observer's policy for a step defect: throw, naming the outcome the
   transport answered with."
  [observed observer]
  (let [outcome (answered-outcome (:read observed))]
    (if (contains? uncontinuable-read-outcomes outcome)
      (terminal "Observation cannot continue from this cursor"
                outcome
                observer)
      (terminal "Unexpected DaoStream outcome while observing the medium"
                outcome
                observer))))


(defn- adopt-gap
  "This observer's gap policy: commit the recovery cursor and count the loss."
  [observer observed]
  (-> observer
      (assoc :cursor (:recovery observed))
      (update :ingress-gaps (fnil inc 0))))


(defn observe-next
  "Observe one batch from the attached medium.

   Returns `{:status :ok :batch batch :observer observer'}` on `ok`, where the
   successor state carries the exact returned cursor; `{:status :blocked
   :observer observer}` and `{:status :end :observer observer}`, which retain
   the cursor; or `{:status :gap :observer observer'}`, where the successor
   carries the recovery cursor and one more counted `:ingress-gaps`. Terminal
   outcomes (`cursor-mismatch`, `invalid-cursor`, `transport-error`) and
   unexpected ones throw with the original outcome preserved."
  [observer]
  (let [observed (observe/step (:stream observer)
                               (:cursor observer)
                               (fn [_value] {:dao.stream/outcome :dao.stream/ok}))]
    (case (:status observed)
      :advance {:status :ok,
                :batch (:dao.stream/value (:read observed)),
                :observer (assoc observer :cursor (:cursor observed))}

      :retry {:status :blocked, :observer observer}
      :ended {:status :end, :observer observer}
      :gap {:status :gap, :observer (adopt-gap observer observed)}

      (observation-terminal observed observer))))


;; =============================================================================
;; Coordination
;; =============================================================================

(defn run-on-stream
  "Coordinate one `{:observer observer :consumer consumer}` session over the
   attached medium, returning the updated session.

   The consumer is any value the three supplied functions agree on. `ready?`
   decides when it may accept another batch, `load` hands it one observed
   batch and returns the successor consumer, and `run` lets it do whatever
   work a loaded batch implies — evaluate, expand, index, forward — and
   returns the successor. This is coordination, not interpretation: no field
   of the consumer is inspected, so any consumer shape can be driven.

   A consumer that is not ready runs first, and observation follows only if
   it becomes ready. A ready consumer observes one batch; `gap` commits the
   recovery cursor and continues; `blocked` and `end` return the session.
   After a batch loads and runs, a consumer that is ready again observes the
   next batch, and one that is not returns the session without another read.

   A `load` that throws propagates before any successor session is published:
   the caller retains the previous observer cursor and the same batch is
   re-read on the next call. A consumer whose input can be bad should
   therefore return that as data from `load` rather than throw, and reserve
   the throw for its own defects."
  [session ready? load run]
  (loop [{:keys [observer consumer]} session]
    (if-not (ready? consumer)
      (let [consumer' (run consumer)]
        (if (ready? consumer')
          (recur {:observer observer, :consumer consumer'})
          {:observer observer, :consumer consumer'}))
      ;; The load is the step's effect, so the cursor advances only once it has
      ;; returned: a throwing load propagates before any successor exists.
      (let [observed (observe/step (:stream observer)
                                   (:cursor observer)
                                   (fn [batch]
                                     {:dao.stream/outcome :dao.stream/ok,
                                      :dao.stream.v2.observer/loaded
                                      (load consumer batch)}))]
        (case (:status observed)
          :advance (let [consumer' (run (:dao.stream.v2.observer/loaded
                                          (:effect observed)))
                         observer' (assoc observer :cursor (:cursor observed))]
                     (if (ready? consumer')
                       (recur {:observer observer', :consumer consumer'})
                       {:observer observer', :consumer consumer'}))
          :gap (recur {:observer (adopt-gap observer observed),
                       :consumer consumer})
          ;; :retry (blocked) and :ended retain the cursor and end the round.
          (:retry :ended) {:observer observer, :consumer consumer}
          (observation-terminal observed observer))))))
