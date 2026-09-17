(ns dao.stream.rpc
  "Transport-neutral, polling RPC state machines for DaoStream v2.

   This layer deliberately owns no socket, scheduler, atom, promise, callback,
   retry policy, or transport registry.  A composition supplies two explicit
   handles (a request writer and a response reader), owns the returned state,
   and chooses when to step `request!`, `poll!`, and `serve-once!`."
  (:require [dao.stream :as stream]
            [dao.stream.apply :as apply]))


;; =============================================================================
;; Public data vocabulary
;; =============================================================================

(def max-safe-id
  "Largest integer that remains exactly representable by every supported host."
  9007199254740991)


(def event-key :dao.stream.rpc/event)
(def response-key :dao.stream.rpc/response)
(def lifecycle-key :dao.stream.rpc/lifecycle)
(def diagnostic-key :dao.stream.rpc/diagnostic)
(def code-key :dao.stream.rpc/code)


(def lifecycle-values
  "The lifecycle vocabulary belongs to dao.stream.apply.  Transports decode
   their local events into these values before the RPC core sees them."
  #{:dao.stream.apply/established
    :dao.stream.apply/detached
    :dao.stream.apply/ended
    :dao.stream.apply/not-found
    :dao.stream.apply/transport-error
    :dao.stream.apply/diagnostic})


(defn response-event
  [response]
  {event-key :dao.stream.rpc/response response-key response})


(defn lifecycle-event
  [lifecycle]
  {event-key :dao.stream.rpc/lifecycle lifecycle-key lifecycle})


(defn diagnostic-event
  ([code]
   (diagnostic-event code nil))
  ([code value]
   {event-key :dao.stream.rpc/diagnostic
    diagnostic-key {code-key code
                    :dao.stream.rpc/value value}}))


(defn ignore-event
  []
  {event-key :dao.stream.rpc/ignore})


(defn safe-id?
  "True for an integer which round-trips without precision loss across CLJ,
   CLJS, and CLJD.  IDs are routing tokens, not application values."
  [id]
  (and (integer? id) (<= 0 id max-safe-id)))


;; =============================================================================
;; Explicit client state
;; =============================================================================

(defn client-state
  "Create caller-owned RPC client state.

   `writer` is the request path. `reader` and `response-cursor` are the
   independent response path.  `:decode`, when present, transforms one raw
   response-medium value into an RPC event made by this namespace; nil means a
   bare apply response stream.  No cursor is fabricated here."
  ([writer reader response-cursor]
   (client-state writer reader response-cursor {}))
  ([writer reader response-cursor {:keys [me decode]}]
   {:writer writer
    :reader reader
    :cursor response-cursor
    :me me
    :decode decode
    :next-id 0
    :outstanding {}
    :unsent nil
    :completed []
    :diagnostics []
    :terminal nil}))


(def init-client client-state)


(defn server-state
  "The server state is owned by dao.stream.apply, which owns the envelope."
  [request-cursor]
  (apply/server-state request-cursor))


(defn serve-once!
  "Advance one server request/response step.  This is an aliasing boundary, not
   a second server implementation; apply remains the sole envelope owner."
  [handlers request-handle response-handle state]
  (apply/serve-once! handlers request-handle response-handle state))


(defn- rpc-result
  [outcome state & {:as extra}]
  (merge {:dao.stream.rpc/outcome outcome
          :dao.stream.rpc/state state}
         extra))


(defn- valid-operation-result
  [op result]
  (if (stream/valid-outcome? op result)
    result
    {:dao.stream/outcome :dao.stream/transport-error
     :dao.stream/error :dao.stream/invalid-operation-result}))


(defn- completion
  [request & {:keys [response reason]}]
  (cond-> {:dao.stream.rpc/id (:id request)
           :dao.stream.rpc/op (:op request)
           :dao.stream.rpc/args (:args request)}
    response (assoc :dao.stream.rpc/response response)
    reason (assoc :dao.stream.rpc/reason reason)))


(defn- append-completion
  [state request & {:as fields}]
  (update state :completed conj (apply completion request (mapcat identity fields))))


(defn- append-diagnostic
  [state code value]
  (update state :diagnostics conj {code-key code
                                   :dao.stream.rpc/value value}))


(defn- ids-in-use?
  [state id]
  (or (= id (get-in state [:unsent :id]))
      (contains? (:outstanding state) id)
      (boolean (some #(= id (:dao.stream.rpc/id %)) (:completed state)))))


;; Defined below with the other completion bookkeeping; allocation failure is
;; a terminal transition and owes the same conservative loss as every other.
(declare lose-outstanding)


(defn- allocation-failure
  [state code]
  (let [state (-> state
                  (lose-outstanding :dao.stream.rpc/allocator-error true)
                  (append-diagnostic code (:next-id state)))]
    (rpc-result :dao.stream.rpc/allocator-error state
                :dao.stream.rpc/diagnostic (last (:diagnostics state)))))


(defn- allocate-request
  [state op args]
  (let [id (:next-id state)]
    (cond
      (not (safe-id? id))
      (allocation-failure state :dao.stream.rpc/id-exhausted)

      (ids-in-use? state id)
      (allocation-failure state :dao.stream.rpc/id-collision)

      :else
      (let [request {:id id
                     :op op
                     :args args
                     :encoded (apply/request id op args)}]
        (rpc-result :dao.stream.rpc/allocated
                    (-> state
                        (assoc :unsent request)
                        (update :next-id inc)))))))


(defn- accept-unsent
  [state request]
  (-> state
      (assoc :unsent nil)
      (assoc-in [:outstanding (:id request)]
                {:op (:op request) :args (:args request)})))


(defn- attempt-unsent
  [state]
  (let [request (:unsent state)
        append-result (valid-operation-result
                        :append!
                        (apply/put-request! (:writer state) (:encoded request)))
        outcome (:dao.stream/outcome append-result)]
    (case outcome
      :dao.stream/ok
      (rpc-result :dao.stream.rpc/requested
                  (accept-unsent state request)
                  :dao.stream.rpc/id (:id request)
                  :dao.stream.rpc/append append-result)

      :dao.stream/full
      (rpc-result :dao.stream.rpc/pending-request state
                  :dao.stream.rpc/id (:id request)
                  :dao.stream.rpc/append append-result)

      (:dao.stream/closed :dao.stream/invalid-value :dao.stream/transport-error)
      (let [state (-> state
                      (assoc :unsent nil)
                      (append-completion request :reason outcome))]
        (rpc-result :dao.stream.rpc/request-undeliverable state
                    :dao.stream.rpc/id (:id request)
                    :dao.stream.rpc/reason outcome
                    :dao.stream.rpc/append append-result))

      ;; A malformed writer answer becomes transport-error in
      ;; valid-operation-result, so this branch is defensive only.
      (let [state (-> state
                      (assoc :unsent nil)
                      (append-completion request :reason :dao.stream/transport-error))]
        (rpc-result :dao.stream.rpc/request-undeliverable state
                    :dao.stream.rpc/id (:id request)
                    :dao.stream.rpc/reason :dao.stream/transport-error
                    :dao.stream.rpc/append append-result)))))


(defn request!
  "Attempt one request append, returning an explicit next state.

   If a previous append was full, this retries the exact already-allocated
   envelope and ignores `op`/`args`; it never allocates a replacement id.  The
   function makes one append attempt at most and never spins on `:full`."
  [state op args]
  (cond
    (:terminal state)
    (rpc-result :dao.stream.rpc/terminal state)

    (:unsent state)
    (attempt-unsent state)

    (not (and (keyword? op) (vector? args)))
    (let [state (append-diagnostic state :dao.stream.rpc/invalid-request
                                   {:op op :args args})]
      (rpc-result :dao.stream.rpc/invalid-request state
                  :dao.stream.rpc/diagnostic (last (:diagnostics state))))

    :else
    (let [allocated (allocate-request state op args)]
      (if (= :dao.stream.rpc/allocated (:dao.stream.rpc/outcome allocated))
        (attempt-unsent (:dao.stream.rpc/state allocated))
        allocated))))


(defn unsent?
  "True while an allocated request is still owed one accepted append.  A caller
   that allocates ids from this state must not submit new work while it holds:
   `request!` retries the unsent envelope and ignores the operation it is given."
  [state]
  (some? (:unsent state)))


(defn abandon-unsent
  "Give up on an allocated-but-unsent request, appending its completion with
   `reason`.

   A composition that changes what the writer *is* — an operator disconnect, a
   rebind onto a fresh attachment — owns the decision that the retained
   envelope no longer belongs to the new binding.  Abandoning it here reports
   the loss on the ordinary completion path, so it is neither silently dropped
   nor resent in place of the caller's next request.  The id is retired with
   it; `:next-id` never goes back."
  ([state] (abandon-unsent state :dao.stream.rpc/abandoned))
  ([state reason]
   (if-let [request (:unsent state)]
     (-> state
         (assoc :unsent nil)
         (append-completion request :reason reason))
     state)))


;; =============================================================================
;; Response decoding and correlation
;; =============================================================================

(defn- normalize-event
  [value]
  (cond
    (nil? value) (ignore-event)
    (apply/response? value) (response-event value)
    (contains? lifecycle-values value) (lifecycle-event value)
    (and (map? value) (keyword? (get value event-key))) value
    :else (diagnostic-event :dao.stream.rpc/malformed-response value)))


(defn- decode-value
  [state value]
  (try
    (normalize-event (if-let [decode (:decode state)] (decode value) value))
    (catch #?(:cljd Object :clj Throwable :cljs :default) _
      (diagnostic-event :dao.stream.rpc/decode-error value))))


(defn- lose-outstanding
  [state reason terminal?]
  (let [state (reduce (fn [s [id request]]
                        (append-completion s (assoc request :id id) :reason reason))
                      state
                      (:outstanding state))]
    (cond-> (assoc state :outstanding {})
      terminal? (assoc :terminal reason))))


(defn handle-event
  "Apply one decoded RPC event to explicit client state.

   Transport adapters use `response-event`, `lifecycle-event`,
   `diagnostic-event`, and `ignore-event`; raw bare apply responses are also
   accepted.  This function has no cursor operation and is useful for testing
   adapters independently from a response medium."
  [state event]
  (let [event (normalize-event event)
        kind (get event event-key)]
    (cond
      ;; A terminal binding cannot complete an old request later.  Keep the
      ;; event as diagnostic data rather than silently treating it as a reply.
      (:terminal state)
      (let [state (append-diagnostic state :dao.stream.rpc/event-after-terminal event)]
        (rpc-result :dao.stream.rpc/diagnostic state
                    :dao.stream.rpc/diagnostic (last (:diagnostics state))))

      (= kind :dao.stream.rpc/ignore)
      (rpc-result :dao.stream.rpc/ignored state)

      (= kind :dao.stream.rpc/diagnostic)
      (let [diagnostic (get event diagnostic-key)
            state (append-diagnostic state (get diagnostic code-key) diagnostic)]
        (rpc-result :dao.stream.rpc/diagnostic state
                    :dao.stream.rpc/diagnostic (last (:diagnostics state))))

      (= kind :dao.stream.rpc/response)
      (let [response (get event response-key)]
        (if-not (apply/response? response)
          (handle-event state (diagnostic-event :dao.stream.rpc/malformed-response response))
          (let [id (apply/response-id response)]
            (if-let [request (get (:outstanding state) id)]
              (let [state (-> state
                              (update :outstanding dissoc id)
                              (append-completion (assoc request :id id)
                                                 :response response))]
                (rpc-result :dao.stream.rpc/responded state
                            :dao.stream.rpc/id id
                            :dao.stream.rpc/response response))
              (handle-event state
                            (diagnostic-event :dao.stream.rpc/unsolicited-response response))))))

      (= kind :dao.stream.rpc/lifecycle)
      (let [lifecycle (get event lifecycle-key)]
        (case lifecycle
          :dao.stream.apply/established
          (rpc-result :dao.stream.rpc/established state)

          :dao.stream.apply/diagnostic
          (handle-event state (diagnostic-event :dao.stream.rpc/transport-diagnostic event))

          :dao.stream.apply/detached
          (let [state (lose-outstanding state lifecycle true)]
            (rpc-result :dao.stream.rpc/lost state :dao.stream.rpc/reason lifecycle))

          (:dao.stream.apply/ended
            :dao.stream.apply/not-found
            :dao.stream.apply/transport-error)
          (let [state (lose-outstanding state lifecycle true)]
            (rpc-result :dao.stream.rpc/lost state :dao.stream.rpc/reason lifecycle))

          (handle-event state (diagnostic-event :dao.stream.rpc/unhandled-event event))))

      :else
      (handle-event state (diagnostic-event :dao.stream.rpc/unhandled-event event)))))


(defn- poll-read
  [state]
  (let [read-result (valid-operation-result :next (stream/next (:reader state) (:cursor state)))
        outcome (:dao.stream/outcome read-result)]
    (case outcome
      :dao.stream/ok
      ;; Advance before decoding. A malformed or unsolicited element is thereby
      ;; consumed exactly once and cannot poison polling.
      (handle-event (assoc state :cursor (:dao.stream/cursor read-result))
                    (decode-value state (:dao.stream/value read-result)))

      :dao.stream/blocked
      (rpc-result :dao.stream.rpc/idle state :dao.stream.rpc/read read-result)

      :dao.stream/gap
      (let [state (-> state
                      (assoc :cursor (:dao.stream/cursor read-result))
                      (lose-outstanding :dao.stream/gap false))]
        (rpc-result :dao.stream.rpc/lost state
                    :dao.stream.rpc/reason :dao.stream/gap
                    :dao.stream.rpc/read read-result))

      (:dao.stream/end :dao.stream/cursor-mismatch :dao.stream/invalid-cursor
                       :dao.stream/transport-error)
      (let [state (lose-outstanding state outcome true)]
        (rpc-result :dao.stream.rpc/lost state
                    :dao.stream.rpc/reason outcome
                    :dao.stream.rpc/read read-result))

      (let [state (lose-outstanding state :dao.stream/transport-error true)]
        (rpc-result :dao.stream.rpc/lost state
                    :dao.stream.rpc/reason :dao.stream/transport-error
                    :dao.stream.rpc/read read-result)))))


(defn poll!
  "Read at most `budget` response-medium elements.

   A `:dao.stream/blocked` result yields immediately; this function never loops
   waiting for a response.  Each successful read installs the exact successor
   cursor returned by that handle before decoding the element."
  ([state] (poll! state 1))
  ([state budget]
   (cond
     (:terminal state) (rpc-result :dao.stream.rpc/terminal state)
     (not (and (integer? budget) (pos? budget)))
     (rpc-result :dao.stream.rpc/idle state)
     :else
     (loop [remaining budget
            state state
            last-result nil]
       (if (zero? remaining)
         (or last-result (rpc-result :dao.stream.rpc/idle state))
         (let [result (poll-read state)
               next-state (:dao.stream.rpc/state result)
               outcome (:dao.stream.rpc/outcome result)]
           (if (or (:terminal next-state)
                   (= outcome :dao.stream.rpc/idle))
             result
             (recur (dec remaining) next-state result))))))))


(defn take-completed
  "Return `[completions next-state]`, clearing the unpublished completion
   outbox exactly once.  The caller owns publication and must call this only
   from its single state-owning driver step."
  [state]
  [(:completed state) (assoc state :completed [])])


(defn take-diagnostics
  "Return `[diagnostics next-state]`, clearing the unpublished diagnostic outbox
   exactly once.  Diagnostics are bounded by publication exactly as completions
   are: a noisy shared response medium can produce one per read, so a caller
   that never publishes would grow this vector for the session's lifetime."
  [state]
  [(:diagnostics state) (assoc state :diagnostics [])])


(defn rebind
  "Replace a detached client's writer (and optionally reader) without creating
   a cursor or replacing its monotonic id allocator. Only `/detached` is
   reconnectable; other terminal reasons remain terminal."
  ([state writer me]
   (if (= :dao.stream.apply/detached (:terminal state))
     (assoc state :writer writer :me me :terminal nil)
     state))
  ([state writer reader me]
   (if (= :dao.stream.apply/detached (:terminal state))
     (assoc state :writer writer :reader reader :me me :terminal nil)
     state)))
