(ns dao.stream.rpc.dedup
  "Optional response-cache middleware for dao.stream.rpc.server, for lossy
   transports where a client may resend a request under the same req-id
   after not seeing a response in time (dao.stream.rpc.retry on the client
   side). Wraps a dispatch-fn; dao.stream.rpc.server never requires this
   namespace, so a caller that doesn't need dedup pays nothing.

   Caches the {:ok val}/{:error msg} envelope dispatch-fn returns -- the same
   shape dao.stream.apply/response expects -- never a bare handler result.
   That envelope is always a non-nil map, so a cache hit can be distinguished
   from a miss with a plain lookup even when the handler's own return value
   is nil or false.

   Concurrency: the cache check-then-act (get, then later swap!) is not
   atomic as a single operation, but this is safe because
   dao.stream.rpc.server/serve-connection! processes one connection's
   requests single-threaded, and req-ids are unique per connection -- a
   req-id can only recur via a resend (network duplication or client
   retry), and a resend is necessarily sequential with the original request
   on that same single-threaded loop, never concurrent with it. If
   serve-connection! ever becomes concurrent per connection, this invariant
   breaks.")


(defn wrap-dedup
  "Wrap dispatch-fn ((req-id, op, args) -> envelope) so a repeated req-id
   returns the cached envelope instead of re-invoking dispatch-fn -- the
   guarantee that makes client retries safe for non-idempotent handlers.

   opts:
     :max-size — maximum cached entries (default 1024). Once exceeded, the
                 oldest entry (smallest req-id, which is also insertion
                 order: req-id is monotonically increasing per connection)
                 is evicted."
  ([dispatch-fn] (wrap-dedup dispatch-fn {}))
  ([dispatch-fn {:keys [max-size], :or {max-size 1024}}]
   (let [state (atom {:by-id {}, :order []})]
     (fn [req-id op args]
       (if-let [cached (get (:by-id @state) req-id)]
         cached
         (let [result (dispatch-fn req-id op args)]
           (swap! state (fn [{:keys [by-id order]}]
                          (let [by-id' (assoc by-id req-id result)
                                order' (conj order req-id)]
                            (if (> (count by-id') max-size)
                              {:by-id (dissoc by-id' (first order')),
                               :order (subvec order' 1)}
                              {:by-id by-id', :order order'}))))
           result))))))
