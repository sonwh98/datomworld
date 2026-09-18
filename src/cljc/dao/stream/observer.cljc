(ns dao.stream.observer
  "Observer of one DaoStream medium: attachment, cursor, gap accounting, and
   the coordination loop that feeds a consumer one batch at a time.

   The medium exists independently of any consumer. Host composition supplies
   a unary attach capability and a portable DaoStream descriptor; `attach`
   calls that capability exactly once, validates the returned handle against
   the reader surface, and mints the observer's cursor at `:dao.stream/oldest`
   directly through `dao.stream` — or keeps a cursor handed to it, which is
   how a session resumes from where it stopped. The observer never creates,
   appends to, or closes the medium, and its complete initial state is
   `{:stream handle :cursor cursor :ingress-gaps 0}`.

   This namespace requires only `dao.stream` and `dao.stream.observe`,
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
  (:require [dao.stream :as stream]
            [dao.stream.observe :as observe]))


;; =============================================================================
;; Attachment
;; =============================================================================

(defn- attached-handle
  "Call the unary capability once and validate its answer, yielding the
   handle both `attach` arities build on: a non-`ok` outcome and a handle
   without the reader surface throw exactly as `attach` documents."
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
          handle)))))


(defn attach
  "Attach to an existing medium and return the initial observer state, or
   throw.

   `(attach attach! descriptor)` mints the cursor at `:dao.stream/oldest`
   and returns `{:stream handle :cursor cursor :ingress-gaps 0}`.
   `(attach attach! descriptor {:cursor c :ingress-gaps g})` re-attaches at
   a kept cursor, returning `{:stream handle :cursor c :ingress-gaps g}`
   without minting one: the composition resuming a session knows where its
   previous one stopped, a cursor already covers repositioning, the
   transport validates the kept cursor on the first `next`, and the gap
   count is seeded from the checkpoint rather than reset to zero.

   `attach!` is a unary attachment capability: `(attach! descriptor)`. It is
   called exactly once and there is no fallback to stream creation. A
   non-`ok` attachment or cursor outcome throws `ex-info` preserving the
   original `:dao.stream/outcome`. A handle without the reader surface is a
   host assembly error reported with the descriptor and the handle's declared
   surfaces, without inventing a DaoStream outcome. Attachment cleanup stays
   with the host: this function closes nothing, and a failure leaves no
   partial observer behind."
  ([attach! descriptor]
   (let [handle (attached-handle attach! descriptor)
         minted (stream/cursor handle stream/anchor-oldest)
         mint-outcome (:dao.stream/outcome minted)]
     (if-not (= :dao.stream/ok mint-outcome)
       (throw (ex-info "Observer cursor could not be minted"
                       {:dao.stream/outcome mint-outcome,
                        :descriptor descriptor,
                        :result minted}))
       {:stream handle,
        :cursor (:dao.stream/cursor minted),
        :ingress-gaps 0})))
  ([attach! descriptor {:keys [cursor ingress-gaps]}]
   {:stream (attached-handle attach! descriptor),
    :cursor cursor,
    :ingress-gaps ingress-gaps}))


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

(defn- consumer-left-by-run
  "The consumer state a failing `run` left, for the carried session.

   A `run` that made partial progress before throwing — a flush that
   delivered one medium and failed on another — reports the state it reached
   by carrying a `:consumer` in its exception's data, so a retry resumes
   from that progress rather than repeating delivered work. A `run` that
   reports nothing left the state it was handed, which is the furthest
   progress the coordination can name for it."
  [e handed]
  (let [data (ex-data e)]
    (if (contains? data :consumer)
      (:consumer data)
      handed)))


(defn- carry-session
  "Rethrow `e`, keeping the throw and carrying the partial session in the
   exception's data.

   The original message and data are preserved and `:session` is added. The
   rethrow is a new `ExceptionInfo` whose cause is the original, so a caller
   matching the original exception's concrete type no longer matches the
   wrapper — `(ex-cause caught)` names it. Recovery is opt-in: a caller that
   does not read `:session` keeps the old semantics — a throw means the
   round failed — and a caller that catches resumes from the carried
   session, which names the furthest progress the round had made where the
   failure happened."
  [session e]
  (throw (ex-info (or (ex-message e) (str e))
                  (assoc (ex-data e) :session session)
                  e)))


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

   A `load` or `run` that throws still propagates — the throw is kept — but
   carries the partial session in the exception's data under `:session`, so
   recovery is opt-in. Where the failure happened decides the carried
   session: a throwing `load` carries the cursor *before* the failing batch,
   so a retry re-reads it; a throwing `run` carries the cursor *after* the
   loaded batch and the consumer the failing `run` left — the partial state
   it reported under `:consumer` in its own exception's data when it made
   progress before throwing, else the state `load` returned — so a retry
   neither re-reads the batch nor repeats work already delivered; a
   terminal read after processed batches carries the session those batches
   produced. A consumer whose input can be bad should therefore return that
   as data from `load` rather than throw, and reserve the throw for its own
   defects."
  [session ready? load run]
  (loop [{:keys [observer consumer]} session]
    (if-not (ready? consumer)
      (let [consumer' (try (run consumer)
                           (catch #?(:cljd Object :clj Throwable :cljs :default) e
                             (carry-session
                               {:observer observer,
                                :consumer (consumer-left-by-run e consumer)}
                               e)))]
        (if (ready? consumer')
          (recur {:observer observer, :consumer consumer'})
          {:observer observer, :consumer consumer'}))
      ;; The load is the step's effect, so the cursor advances only once it has
      ;; returned: a throwing load propagates before any successor exists, and
      ;; the session it carries names the cursor before the failing batch.
      (let [observed (try (observe/step (:stream observer)
                                        (:cursor observer)
                                        (fn [batch]
                                          {:dao.stream/outcome :dao.stream/ok,
                                           :dao.stream.observer/loaded
                                           (load consumer batch)}))
                          (catch #?(:cljd Object :clj Throwable :cljs :default) e
                            (carry-session {:observer observer, :consumer consumer} e)))]
        (case (:status observed)
          :advance (let [loaded (:dao.stream.observer/loaded (:effect observed))
                         ;; The batch was observed and loaded before the run, so
                         ;; a throwing run carries the post-batch cursor and the
                         ;; consumer it left: the partial state it reported in
                         ;; its exception's data, else the loaded value.
                         observer' (assoc observer :cursor (:cursor observed))
                         consumer' (try (run loaded)
                                        (catch #?(:cljd Object :clj Throwable :cljs :default) e
                                          (carry-session
                                            {:observer observer',
                                             :consumer (consumer-left-by-run e loaded)}
                                            e)))]
                     (if (ready? consumer')
                       (recur {:observer observer', :consumer consumer'})
                       {:observer observer', :consumer consumer'}))
          :gap (recur {:observer (adopt-gap observer observed),
                       :consumer consumer})
          ;; :retry (blocked) and :ended retain the cursor and end the round.
          (:retry :ended) {:observer observer, :consumer consumer}
          (try (observation-terminal observed observer)
               (catch #?(:cljd Object :clj Throwable :cljs :default) e
                 (carry-session {:observer observer, :consumer consumer} e))))))))
