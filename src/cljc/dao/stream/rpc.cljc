(ns dao.stream.rpc
  "Transport-neutral, polling RPC state machines for DaoStream v2.

   This layer deliberately owns no socket, scheduler, atom, promise, callback,
   retry policy, or transport registry.  A composition supplies two explicit
   handles (a request writer and a response reader), owns the returned state,
   and chooses when to step `request!` and `poll!`.

   The request/answer vocabulary below is owned by this namespace, not by
   `dao.stream.apply`: a request is `{:dao.stream.rpc/id
   :dao.stream.rpc/op :dao.stream.rpc/args}` and an answer is `{:dao.stream.rpc/id
   :dao.stream.rpc/ok <value>}` or `{:dao.stream.rpc/id
   :dao.stream.rpc/error {:dao.stream.rpc/code :dao.stream.rpc/message}}`.
   Ids are self-minted random safe integers, not a sequential counter: callers
   sharing one answers stream must not collide on a caller-chosen id
   (docs/design/dao.stream.remote.md, section 5)."
  (:require [dao.stream :as stream]))


;; =============================================================================
;; Public data vocabulary
;; =============================================================================

(def max-safe-id
  "Largest integer that remains exactly representable by every supported host."
  9007199254740991)


(def event-key :dao.stream.rpc/event)
(def response-key :dao.stream.rpc/response)
(def diagnostic-key :dao.stream.rpc/diagnostic)
(def code-key :dao.stream.rpc/code)


(def max-id-retries
  "Bound on re-rolling a self-minted id after a collision, before allocation
   fails through the existing terminal path."
  8)


(defn response-event
  [response]
  {event-key :dao.stream.rpc/response response-key response})


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
;; Request/answer value vocabulary owned by this namespace
;; =============================================================================

(defn request-value
  [id op args]
  {:dao.stream.rpc/id id :dao.stream.rpc/op op :dao.stream.rpc/args args})


(defn request-value?
  [v]
  (and (map? v)
       (safe-id? (:dao.stream.rpc/id v))
       (keyword? (:dao.stream.rpc/op v))
       (vector? (:dao.stream.rpc/args v))))


(defn request-id
  [v]
  (:dao.stream.rpc/id v))


(defn request-op
  [v]
  (:dao.stream.rpc/op v))


(defn request-args
  [v]
  (:dao.stream.rpc/args v))


(defn success-answer
  [id value]
  {:dao.stream.rpc/id id :dao.stream.rpc/ok value})


(defn error-answer
  [id code message]
  {:dao.stream.rpc/id id
   :dao.stream.rpc/error {:dao.stream.rpc/code code
                          :dao.stream.rpc/message message}})


(defn answer?
  [v]
  (and (map? v)
       (safe-id? (:dao.stream.rpc/id v))
       (let [ok? (contains? v :dao.stream.rpc/ok)
             err? (contains? v :dao.stream.rpc/error)]
         (and (or ok? err?) (not (and ok? err?))))))


(defn answer-id
  [answer]
  (:dao.stream.rpc/id answer))


(defn answer-ok
  [answer]
  (:dao.stream.rpc/ok answer))


(defn answer-error
  [answer]
  (:dao.stream.rpc/error answer))


(defn answer-ok?
  [answer]
  (contains? answer :dao.stream.rpc/ok))


;; =============================================================================
;; Explicit client state
;; =============================================================================

(defn- random-safe-id
  []
  #?(:clj (long (* max-safe-id (rand)))
     :cljs (Math/floor (* max-safe-id (rand)))
     :cljd (.floor ^double (* max-safe-id (rand)))))


(defn client-state
  "Create caller-owned RPC client state.

   `writer` is the request path. `reader` and `response-cursor` are the
   independent response path.  No cursor is fabricated here; a caller
   over a dao.stream.remote reflection passes `stream/anchor-newest`
   (the request/response service's own mint, docs/design/
   dao.stream.remote.md section 5) and `poll!` resolves it, retrying
   while the reflection answers a retryable mint -- the same pattern
   `dao.jing.content.step/mint-cursor` uses for the content client."
  ([writer reader response-cursor]
   (client-state writer reader response-cursor {}))
  ([writer reader response-cursor _opts]
   {:writer writer
    :reader reader
    :cursor response-cursor
    :outstanding {}
    :unsent nil
    :completed []
    :diagnostics []
    :terminal nil}))


(def init-client client-state)


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
                  (append-diagnostic code nil))]
    (rpc-result :dao.stream.rpc/allocator-error state
                :dao.stream.rpc/diagnostic (last (:diagnostics state)))))


(defn- allocate-request
  "Self-mint a random safe id in [0, max-safe-id], re-rolling on a collision
   with an id already in use by this state, up to `max-id-retries` times.
   This is the request/answer service's own id space (docs/design/
   dao.stream.remote.md section 5): callers sharing one answers stream must
   not collide on a caller-chosen id, so the id is not a sequential counter."
  [state op args]
  (loop [attempt 0]
    (if (>= attempt max-id-retries)
      (allocation-failure state :dao.stream.rpc/id-collision)
      (let [id (random-safe-id)]
        (if (ids-in-use? state id)
          (recur (inc attempt))
          (let [request {:id id
                         :op op
                         :args args
                         :encoded (request-value id op args)}]
            (rpc-result :dao.stream.rpc/allocated
                        (assoc state :unsent request))))))))


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
                        (stream/append! (:writer state) (:encoded request)))
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
   nor resent in place of the caller's next request."
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
    (answer? value) (response-event value)
    (and (map? value) (keyword? (get value event-key))) value
    :else (diagnostic-event :dao.stream.rpc/malformed-response value)))


(defn- decode-value
  [_state value]
  (try
    (normalize-event value)
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

   `response-event`, `diagnostic-event`, and `ignore-event` are the events a
   composition constructs; a bare answer value is also accepted.  This
   function has no cursor operation and is useful for testing adapters
   independently from a response medium."
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
        (if-not (answer? response)
          (handle-event state (diagnostic-event :dao.stream.rpc/malformed-response response))
          (let [id (answer-id response)]
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

      :else
      (handle-event state (diagnostic-event :dao.stream.rpc/unhandled-event event)))))


(defn- transport-error-reason
  "Translate a reflection's `:dao.stream/transport-error` into the client
   terminal vocabulary, inspecting `:dao.stream.remote/reason` when the
   reflection supplied one (dao.stream.remote.cljc reasons a reflection's
   read failure this way).  A reason this translation does not recognize
   still becomes the generic transport-error terminal, never left raw."
  [read-result]
  (case (:dao.stream.remote/reason read-result)
    :dao.stream.remote/not-found :dao.stream.apply/not-found
    :dao.stream.remote/channel-gone :dao.stream.apply/detached
    :dao.stream.apply/transport-error))


(defn- terminal-lost
  "Translate a non-ok, non-blocked, non-gap read or mint result into the
   client's terminal vocabulary and complete every outstanding request
   with it.  Shared by `poll-read`'s `next` failures and `mint-cursor`'s
   terminal mint failures, so a reasoned not-found or channel-gone
   reaches the terminal the same way regardless of which operation
   the reflection answered it to."
  [state result]
  (let [reason (case (:dao.stream/outcome result)
                 :dao.stream/end :dao.stream.apply/ended
                 :dao.stream/transport-error (transport-error-reason result)
                 :dao.stream.apply/transport-error)
        state (lose-outstanding state reason true)]
    (rpc-result :dao.stream.rpc/lost state
                :dao.stream.rpc/reason reason
                :dao.stream.rpc/read result)))


(defn- mint-cursor
  "Resolve an anchor-keyword cursor through the reader.  A retryable
   answer -- `:dao.stream/transport-error` carrying `:dao.stream/retry?
   true` -- leaves the anchor in place for a later poll (dao.jing.content.
   step/mint-cursor is the precedent this mirrors).  Any other non-ok
   outcome is a reasoned terminal failure: a reflection can answer
   `not-found` or `channel-gone` while minting a cursor
   (dao.stream.remote.cljc's `refl-cursor`), and that must not be
   mistaken for still-pending.  Returns `[status cursor result]`, status
   one of `:ok`, `:retry`, `:terminal`; `result` is retained for
   diagnostics either way."
  [reader anchor]
  (let [r (stream/cursor reader anchor)
        outcome (:dao.stream/outcome r)]
    (cond
      (= :dao.stream/ok outcome) [:ok (:dao.stream/cursor r) r]

      (and (= :dao.stream/transport-error outcome) (:dao.stream/retry? r))
      [:retry anchor r]

      :else [:terminal anchor r])))


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

      (terminal-lost state read-result))))


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
       (cond
         (zero? remaining) (or last-result (rpc-result :dao.stream.rpc/idle state))

         ;; The response cursor is an unresolved anchor: settling it is not
         ;; a read and does not spend the budget, exactly as
         ;; dao.jing.content.step/poll's own anchor branch does not.
         (contains? stream/standard-anchors (:cursor state))
         (let [[status cursor r] (mint-cursor (:reader state) (:cursor state))]
           (case status
             :ok (recur remaining (assoc state :cursor cursor) last-result)
             :retry (or last-result
                        (rpc-result :dao.stream.rpc/idle state :dao.stream.rpc/read r))
             :terminal (terminal-lost state r)))

         :else
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
   a cursor or replacing the random id allocator. Only `/detached` is
   reconnectable; other terminal reasons remain terminal."
  ([state writer]
   (if (= :dao.stream.apply/detached (:terminal state))
     (assoc state :writer writer :terminal nil)
     state))
  ([state writer reader]
   (if (= :dao.stream.apply/detached (:terminal state))
     (assoc state :writer writer :reader reader :terminal nil)
     state)))
