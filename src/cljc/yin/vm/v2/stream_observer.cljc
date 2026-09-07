(ns yin.vm.v2.stream-observer
  "Observer of one program stream: attachment, cursor, and gap accounting.

   The program stream exists independently of any evaluator. Host composition
   supplies a unary attach capability and a portable DaoStream descriptor;
   `attach` calls that capability exactly once, validates the returned handle
   against the reader surface, and mints the observer's cursor at
   `:dao.stream/oldest` directly through `dao.stream.v2`. The observer never
   creates, appends to, or closes the stream, and its complete initial state
   is `{:stream handle :cursor cursor :ingress-gaps 0}`.

   This namespace requires only `dao.stream.v2`. It cannot depend on a ring
   buffer, a WebSocket adapter, a resolver representation, a transport key or
   state, or any VM namespace: transport-specific composition constructs the
   unary attacher outside this boundary, for example by partially applying a
   dispatch table or a transport resolver.

   A `gap` is recoverable here and only here in the generic machinery. A batch
   the observer never saw is advanced past at the recovery cursor and counted,
   and observation continues with the next retained batch; silently pretending
   the stream was contiguous hides a missing program. What a host does with a
   gap count is host policy — the REPL shell, for one, reports the loss and
   refuses further evaluation until reset — and that policy lives above this
   boundary. Terminal read outcomes are errors.

   Exports exactly `attach`, `observe-next`, and `run-on-stream`."
  (:require [dao.stream.v2 :as stream]
            [dao.stream.v2.observe :as observe]))


;; =============================================================================
;; Attachment
;; =============================================================================

(defn attach
  "Attach to an existing program stream and return the initial observer state
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
      (throw (ex-info "Program stream attachment failed"
                      {:dao.stream/outcome outcome,
                       :descriptor descriptor,
                       :result result}))
      (let [handle (:dao.stream/handle result)]
        (if-not (stream/reader? handle)
          (throw (ex-info "Attached program handle declares no reader surface"
                          {:descriptor descriptor,
                           :surfaces (stream/declared-surfaces handle)}))
          (let [minted (stream/cursor handle stream/anchor-oldest)
                mint-outcome (:dao.stream/outcome minted)]
            (if-not (= :dao.stream/ok mint-outcome)
              (throw (ex-info "Program cursor could not be minted"
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
      (terminal "Program observation cannot continue from this cursor"
                outcome
                observer)
      (terminal "Unexpected DaoStream outcome while observing the program stream"
                outcome
                observer))))


(defn- adopt-gap
  "This observer's gap policy: commit the recovery cursor and count the loss."
  [observer observed]
  (-> observer
      (assoc :cursor (:recovery observed))
      (update :ingress-gaps (fnil inc 0))))


(defn observe-next
  "Observe one program batch from the attached stream.

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
  "Coordinate one `{:observer observer :vm vm}` session over the program
   stream, returning the updated session.

   `ready?` decides when `vm` may accept another program batch,
   `load-program` loads one observed batch into the VM, and `run-vm` runs
   loaded work. This is coordination, not interpretation: no
   evaluator-specific field is inspected, so any VM shape can be driven.

   A VM that is not ready runs first, and observation follows only if
   execution becomes ready. A ready VM observes one batch; `gap` commits the
   recovery cursor and continues; `blocked` and `end` return the session.
   After a batch loads and runs, a VM that is ready again observes the next
   batch, and a suspended one returns the session without another read.

   A `load-program` that throws propagates before any successor session is
   published: the caller retains the previous observer cursor and the same
   malformed batch is retried on the next call."
  [session ready? load-program run-vm]
  (loop [{:keys [observer vm]} session]
    (if-not (ready? vm)
      (let [vm' (run-vm vm)]
        (if (ready? vm')
          (recur {:observer observer, :vm vm'})
          {:observer observer, :vm vm'}))
      ;; The load is the step's effect, so the cursor advances only once it has
      ;; returned: a throwing loader propagates before any successor exists.
      (let [observed (observe/step (:stream observer)
                                   (:cursor observer)
                                   (fn [batch]
                                     {:dao.stream/outcome :dao.stream/ok,
                                      :yin.vm.v2.stream-observer/loaded
                                      (load-program vm batch)}))]
        (case (:status observed)
          :advance (let [vm' (run-vm (:yin.vm.v2.stream-observer/loaded
                                       (:effect observed)))
                         observer' (assoc observer :cursor (:cursor observed))]
                     (if (ready? vm')
                       (recur {:observer observer', :vm vm'})
                       {:observer observer', :vm vm'}))
          :gap (recur {:observer (adopt-gap observer observed), :vm vm})
          ;; :retry (blocked) and :ended retain the cursor and end the round.
          (:retry :ended) {:observer observer, :vm vm}
          (observation-terminal observed observer))))))
