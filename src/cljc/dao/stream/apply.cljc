(ns dao.stream.apply
  "Function application over streams.

   This namespace models a request/reply stream pair that reifies function
   application as explicit data. A caller emits an operation plus evaluated
   arguments on the request stream, and a callee emits the resulting value on
   the reply stream.")


(defn make-endpoint
  "Create an endpoint descriptor from two stream descriptors.

  Args:
    request-descriptor  - stream descriptor where callee reads requests
    response-descriptor - stream descriptor where caller reads responses

  Returns:
    {:dao.stream.apply/request <request-desc>
     :dao.stream.apply/response <response-desc>}

  Example:
    (make-endpoint {:transport {:type :ringbuffer :capacity nil}}
                   {:transport {:type :ringbuffer :capacity nil}})"
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
  {:dao.stream.apply/id id
   :dao.stream.apply/op op
   :dao.stream.apply/args args})


(defn response
  "Create a response datom.

  Args:
    id    - keyword; must match request's :dao.stream.apply/id for routing
    value - any value (result, error, etc.)

  Returns:
    {:dao.stream.apply/id <id> :dao.stream.apply/value <value>}"
  [id value]
  {:dao.stream.apply/id id
   :dao.stream.apply/value value})
