(ns dao.stream.apply
  "Function application over streams.

   This namespace models a request/reply stream pair that reifies function
   application as explicit data. A caller emits an operation plus evaluated
   arguments on the request stream, and a callee emits the resulting value on
   the reply stream."
  (:require
    [dao.stream :as ds]))


(defn endpoint-request
  "Return the request stream descriptor from an endpoint descriptor."
  [endpoint]
  (:dao.stream.apply/request endpoint))


(defn endpoint-response
  "Return the response stream descriptor from an endpoint descriptor."
  [endpoint]
  (:dao.stream.apply/response endpoint))


(defn make-endpoint
  "Create an endpoint descriptor from two stream descriptors.

  Args:
    request-descriptor  - stream descriptor where callee reads requests
    response-descriptor - stream descriptor where caller reads responses

  Returns:
    {:dao.stream.apply/request <request-desc>
     :dao.stream.apply/response <response-desc>}

  Example:
    (make-endpoint {:type :ringbuffer :capacity nil}
                   {:type :ringbuffer :capacity nil})"
  [request-descriptor response-descriptor]
  {:dao.stream.apply/request request-descriptor
   :dao.stream.apply/response response-descriptor})


(defn request
  "Create a request datom.

  Args:
    id   - keyword or other opaque ID; must be present in response
    op   - keyword naming the operation
    args - vector of argument values

  Returns:
    {:dao.stream.apply/id <id>
     :dao.stream.apply/op <op>
     :dao.stream.apply/args <args>}"
  [id op args]
  (when-not (keyword? op)
    (throw (ex-info "dao.stream.apply request op must be a keyword"
                    {:id id
                     :op op
                     :args args})))
  (when-not (vector? args)
    (throw (ex-info "dao.stream.apply request args must be a vector"
                    {:id id
                     :op op
                     :args args})))
  {:dao.stream.apply/id id
   :dao.stream.apply/op op
   :dao.stream.apply/args args})


(defn request?
  "True when value has the mandatory dao.stream.apply request fields."
  [value]
  (and (map? value)
       (contains? value :dao.stream.apply/id)
       (keyword? (:dao.stream.apply/op value))
       (vector? (:dao.stream.apply/args value))))


(defn request-id
  "Return the opaque request id."
  [value]
  (:dao.stream.apply/id value))


(defn request-op
  "Return the request operation keyword."
  [value]
  (:dao.stream.apply/op value))


(defn request-args
  "Return the request argument vector."
  [value]
  (:dao.stream.apply/args value))


(defn response
  "Create a response datom.

  Args:
    id    - opaque ID; must match request's :dao.stream.apply/id for routing
    value - any value (result, error, etc.)

  Returns:
    {:dao.stream.apply/id <id> :dao.stream.apply/value <value>}"
  [id value]
  {:dao.stream.apply/id id
   :dao.stream.apply/value value})


(defn response?
  "True when value has the mandatory dao.stream.apply response fields."
  [value]
  (and (map? value)
       (contains? value :dao.stream.apply/id)
       (contains? value :dao.stream.apply/value)))


(defn response-id
  "Return the opaque response id."
  [value]
  (:dao.stream.apply/id value))


(defn response-value
  "Return the response payload."
  [value]
  (:dao.stream.apply/value value))


(defn- expect-request
  [value]
  (when-not (request? value)
    (throw (ex-info "Expected dao.stream.apply request"
                    {:value value})))
  value)


(defn- expect-response
  [value]
  (when-not (response? value)
    (throw (ex-info "Expected dao.stream.apply response"
                    {:value value})))
  value)


(defn put-request!
  "Append a request value to a request stream.

   Arity 2 expects a fully formed request map.
   Arity 4 constructs the request from id/op/args before appending."
  ([request-stream request-value]
   (ds/put! request-stream (expect-request request-value)))
  ([request-stream id op args]
   (put-request! request-stream (request id op args))))


(defn put-response!
  "Append a response value to a response stream.

   Arity 2 expects a fully formed response map.
   Arity 3 constructs the response from id/value before appending."
  ([response-stream response-value]
   (ds/put! response-stream (expect-response response-value)))
  ([response-stream id value]
   (put-response! response-stream (response id value))))


(defn next-request
  "Read one request-shaped value from a stream using DaoStream cursor semantics.

   Returns the underlying ds/next sentinel values unchanged:
   :blocked, :end, or :daostream/gap.

   When a value is present, validates that the payload is a request map and
   returns {:ok request :cursor cursor'}."
  ([request-stream]
   (next-request request-stream {:position 0}))
  ([request-stream cursor]
   (let [result (ds/next request-stream cursor)]
     (if (map? result)
       (do (expect-request (:ok result))
           result)
       result))))


(defn next-response
  "Read one response-shaped value from a stream using DaoStream cursor semantics.

   Returns the underlying ds/next sentinel values unchanged:
   :blocked, :end, or :daostream/gap.

   When a value is present, validates that the payload is a response map and
   returns {:ok response :cursor cursor'}."
  ([response-stream]
   (next-response response-stream {:position 0}))
  ([response-stream cursor]
   (let [result (ds/next response-stream cursor)]
     (if (map? result)
       (do (expect-response (:ok result))
           result)
       result))))


(defn dispatch-request
  "Dispatch a request map through a handler map and return a response map.

   Missing handlers are explicit callee errors and throw ex-info. Handler
   results are wrapped as ordinary response values."
  [handlers request-value]
  (let [request* (expect-request request-value)
        request-id* (request-id request*)
        op (request-op request*)
        args (request-args request*)
        handler (get handlers op)]
    (when-not handler
      (throw (ex-info "No dao.stream.apply handler for op"
                      {:id request-id*
                       :op op
                       :available (vec (keys handlers))})))
    (response request-id* (apply handler args))))


(defn serve-once!
  "Read at most one request, dispatch it, and append the matching response.

   Returns :blocked, :end, or :daostream/gap unchanged when no request can be
   served at the given cursor.

   On success returns:
   {:request  <request>
    :response <response>
    :cursor   <next-request-cursor>
    :put-result <ds/put! result>}"
  ([handlers request-stream response-stream]
   (serve-once! handlers request-stream response-stream {:position 0}))
  ([handlers request-stream response-stream cursor]
   (let [read-result (next-request request-stream cursor)]
     (if (map? read-result)
       (let [request-value (:ok read-result)
             response-value (dispatch-request handlers request-value)
             put-result (put-response! response-stream response-value)]
         {:request request-value
          :response response-value
          :cursor (:cursor read-result)
          :put-result put-result})
       read-result))))
