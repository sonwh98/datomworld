(ns dao.stream.apply
  "The transport-neutral request/response envelope for DaoStream v2.

   This namespace owns only application data and one explicit server step.  It
   neither creates streams nor retains handles, cursors, handlers, or requests
   outside values supplied by its caller.  A driver owns the returned state and
   decides when to call `serve-once!` again."
  (:require [dao.stream :as stream]))


;; =============================================================================
;; Envelope
;; =============================================================================

(def id-key :dao.stream.apply/id)
(def op-key :dao.stream.apply/op)
(def args-key :dao.stream.apply/args)
(def ok-key :dao.stream.apply/ok)
(def error-key :dao.stream.apply/error)
(def code-key :dao.stream.apply/code)
(def message-key :dao.stream.apply/message)


(defn correlation-id?
  "True for a usable opaque correlation id.  Allocation policy (including the
   cross-host safe-integer bound) belongs to the RPC client, not this envelope."
  [id]
  (some? id))


(defn request
  "Construct a request envelope.  Use `request?` to validate data received from
   a stream; constructors deliberately add no interpretation to arguments."
  [id op args]
  {id-key id op-key op args-key args})


(defn request?
  "True when value is a valid request envelope.  Unknown keys are intentionally
   ignored so the envelope remains open."
  [value]
  (and (map? value)
       (correlation-id? (get value id-key))
       (keyword? (get value op-key))
       (vector? (get value args-key))))


(defn request-id
  [value]
  (get value id-key))


(defn request-op
  [value]
  (get value op-key))


(defn request-args
  [value]
  (get value args-key))


(defn error
  "Construct portable error data.  Codes are qualified keywords and messages
   are ordinary strings, never host exception values."
  [code message]
  {code-key code message-key message})


(defn error?
  [value]
  (and (map? value)
       (stream/qualified-keyword?* (get value code-key))
       (string? (get value message-key))))


(defn success-response
  "Construct a successful response preserving the request id."
  [id value]
  {id-key id ok-key value})


(defn error-response
  "Construct an error response preserving the request id."
  [id code message]
  {id-key id error-key (error code message)})


(defn response?
  "True when value is a valid response envelope.  A response contains exactly
   one of `:dao.stream.apply/ok` or `/error`; nil is a valid success value."
  [value]
  (and (map? value)
       (correlation-id? (get value id-key))
       (not= (contains? value ok-key) (contains? value error-key))
       (or (contains? value ok-key)
           (error? (get value error-key)))))


(defn response-id
  [value]
  (get value id-key))


(defn response-ok
  [value]
  (get value ok-key))


(defn response-error
  [value]
  (get value error-key))


;; =============================================================================
;; Endpoint descriptors and explicit stream helpers
;; =============================================================================

(def request-key :dao.stream.apply/request)
(def response-key :dao.stream.apply/response)


(defn endpoint
  "Construct a portable endpoint pair from request and response descriptors.
   Resolving descriptors into handles is host composition and stays outside this
   namespace."
  [request-descriptor response-descriptor]
  {request-key request-descriptor response-key response-descriptor})


(def make-endpoint endpoint)


(defn endpoint?
  [value]
  (and (map? value)
       (stream/valid-descriptor? (get value request-key))
       (stream/valid-descriptor? (get value response-key))))


(defn endpoint-request
  [value]
  (get value request-key))


(defn endpoint-response
  [value]
  (get value response-key))


(defn put-request!
  "Append one validated request to an explicit writer handle.  Invalid envelope
   data is reported as application data and is never appended."
  [request-handle request-value]
  (if (request? request-value)
    (stream/append! request-handle request-value)
    {:dao.stream.apply/outcome :dao.stream.apply/invalid-request
     :dao.stream.apply/value request-value}))


(defn put-response!
  "Append one validated response to an explicit writer handle."
  [response-handle response-value]
  (if (response? response-value)
    (stream/append! response-handle response-value)
    {:dao.stream.apply/outcome :dao.stream.apply/invalid-response
     :dao.stream.apply/value response-value}))


(defn next-request
  "Read one raw request value at the explicit cursor.  Validation belongs to the
   server step because malformed stream data must be consumed once, not retried."
  [request-handle request-cursor]
  (stream/next request-handle request-cursor))


(defn next-response
  "Read one raw response value at the explicit cursor."
  [response-handle response-cursor]
  (stream/next response-handle response-cursor))


;; =============================================================================
;; Pure request dispatch
;; =============================================================================

(defn dispatch-request
  "Invoke at most one handler for a validated request and return its correlated
   response data.  Unknown operations and host handler failures are ordinary,
   portable error responses; no exception escapes into a polling driver."
  [handlers request-value]
  (cond
    (not (request? request-value))
    (when (correlation-id? (request-id request-value))
      (error-response (request-id request-value)
                      :dao.stream.apply/malformed-request
                      "Malformed request envelope"))

    :else
    (let [id (request-id request-value)
          op (request-op request-value)
          handler (get handlers op)]
      (if-not (fn? handler)
        (error-response id :dao.stream.apply/unknown-operation
                        "No handler for operation")
        (try
          (success-response id (apply handler (request-args request-value)))
          (catch #?(:cljd Object :clj Throwable :cljs :default) _
            (error-response id :dao.stream.apply/handler-error
                            "Handler failed")))))))


;; =============================================================================
;; One explicit server step
;; =============================================================================

(defn server-state
  "Create caller-owned state for `serve-once!`.  The cursor must have been
   minted by the request handle; this namespace never fabricates one."
  [request-cursor]
  {:request-cursor request-cursor
   :pending-response nil
   :pending-request-id nil
   :pending-successor nil
   :terminal nil
   :diagnostics []})


(defn- step-result
  [outcome state & {:as extra}]
  (merge {:dao.stream.apply/outcome outcome
          :dao.stream.apply/state state}
         extra))


(defn- clear-pending
  [state]
  (assoc state
         :pending-response nil
         :pending-request-id nil
         :pending-successor nil))


(defn- retain-response
  [state response successor]
  (assoc state
         :pending-response response
         :pending-request-id (response-id response)
         :pending-successor successor))


(defn- deliver-pending
  [response-handle state]
  (let [response (:pending-response state)
        put-result (put-response! response-handle response)
        outcome (:dao.stream/outcome put-result)]
    (case outcome
      :dao.stream/ok
      (step-result :dao.stream.apply/responded
                   (-> state
                       (assoc :request-cursor (:pending-successor state))
                       clear-pending)
                   :dao.stream.apply/response response
                   :dao.stream.apply/append put-result)

      :dao.stream/full
      (step-result :dao.stream.apply/pending-response state
                   :dao.stream.apply/response response
                   :dao.stream.apply/append put-result)

      ;; A response that cannot be delivered is consumed once and terminal for
      ;; this binding.  Retrying would rerun neither handler nor cursor, but
      ;; would conceal the known loss from the caller.
      (:dao.stream/invalid-value :dao.stream/closed :dao.stream/transport-error)
      (step-result :dao.stream.apply/response-undeliverable
                   (-> state
                       (assoc :request-cursor (:pending-successor state)
                              :terminal outcome)
                       clear-pending)
                   :dao.stream.apply/response response
                   :dao.stream.apply/append put-result)

      ;; A helper-level invalid response is also terminal.  It indicates a
      ;; caller/handler defect but still must not leave a request poison-pill.
      (step-result :dao.stream.apply/response-undeliverable
                   (-> state
                       (assoc :request-cursor (:pending-successor state)
                              :terminal outcome)
                       clear-pending)
                   :dao.stream.apply/response response
                   :dao.stream.apply/append put-result))))


(defn- terminal-result
  [state outcome read-result]
  (step-result :dao.stream.apply/terminal
               (assoc state :terminal outcome)
               :dao.stream.apply/read read-result))


(defn serve-once!
  "Advance one server session by at most one request and one response append.

   `state` is explicit caller-owned data.  A computed response and its exact
   successor cursor remain in that state until append succeeds, so retrying on
   `:dao.stream/full` cannot invoke the handler twice.  This function neither
   loops nor waits."
  [handlers request-handle response-handle state]
  (cond
    (:terminal state)
    (step-result :dao.stream.apply/terminal state)

    (:pending-response state)
    (deliver-pending response-handle state)

    :else
    (let [read-result (next-request request-handle (:request-cursor state))
          outcome (:dao.stream/outcome read-result)]
      (case outcome
        :dao.stream/ok
        (let [request-value (:dao.stream/value read-result)
              successor (:dao.stream/cursor read-result)]
          (if-let [response (dispatch-request handlers request-value)]
            (deliver-pending response-handle
                             (retain-response state response successor))
            ;; A malformed element with no usable id cannot be correlated, but
            ;; it still advances exactly once and becomes a local diagnostic.
            (step-result :dao.stream.apply/malformed-request
                         (-> state
                             (assoc :request-cursor successor)
                             (update :diagnostics conj
                                     {:dao.stream.apply/code
                                      :dao.stream.apply/malformed-request
                                      :dao.stream.apply/value request-value}))
                         :dao.stream.apply/read read-result)))

        :dao.stream/blocked
        (step-result :dao.stream.apply/idle state
                     :dao.stream.apply/read read-result)

        :dao.stream/gap
        (step-result :dao.stream.apply/gap
                     (-> state
                         (assoc :request-cursor (:dao.stream/cursor read-result))
                         (update :diagnostics conj
                                 {:dao.stream.apply/code
                                  :dao.stream.apply/request-gap}))
                     :dao.stream.apply/read read-result)

        (:dao.stream/end :dao.stream/cursor-mismatch :dao.stream/invalid-cursor
                         :dao.stream/transport-error)
        (terminal-result state outcome read-result)

        ;; A malformed transport implementation is terminal at this consumer;
        ;; retrying an unknown answer could spin forever.
        (terminal-result state outcome read-result)))))
