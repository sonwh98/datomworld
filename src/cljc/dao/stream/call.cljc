(ns dao.stream.call)


(defn make-endpoint
  "Create an endpoint descriptor from two stream descriptors.

  Args:
    call-in-descriptor  - stream descriptor where callee reads requests
    call-out-descriptor - stream descriptor where caller reads responses

  Returns:
    {:dao.stream/call-in <in-desc> :dao.stream/call-out <out-desc>}

  Example:
    (make-endpoint {:transport {:type :ringbuffer :capacity nil}}
                   {:transport {:type :ringbuffer :capacity nil}})"
  [call-in-descriptor call-out-descriptor]
  {:dao.stream/call-in call-in-descriptor
   :dao.stream/call-out call-out-descriptor})


(defn call-request
  "Create a request datom.

  Args:
    call-id - keyword or other opaque ID; must be present in response
    op      - keyword naming the operation
    args    - vector of argument values

  Returns:
    {:dao.stream/call-id <id> :dao.stream/call-op <op> :dao.stream/call-args <args>}"
  [call-id op args]
  {:dao.stream/call-id call-id :dao.stream/call-op op :dao.stream/call-args args})


(defn call-response
  "Create a response datom.

  Args:
    call-id - keyword; must match request's :dao.stream/call-id for routing
    value   - any value (result, error, etc.)

  Returns:
    {:dao.stream/call-id <id> :dao.stream/call-value <value>}"
  [call-id value]
  {:dao.stream/call-id call-id :dao.stream/call-value value})
