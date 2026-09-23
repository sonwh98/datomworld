(ns dao.jing.remote.async
  "The async content backend over the stepped client
   (`dao.jing.remote.step`): the consumer-facing half `dao.jing.md`'s *Async
   hydration* open item owes to `dao.data.btree.md` §5.4.

   `dao.jing.remote.step` is pure: the caller owns the state and chooses
   every cadence.  This namespace is that caller, once, for consumers that
   want a callback per request instead of a state machine — chiefly
   `dao.data.btree.storage/hydrate-async` and `store-tree-async`.  It owns
   the stepped state as the single step owner (`step`'s precondition) and
   drives it with the non-blocking reschedule pattern: a pump runs, then
   reschedules itself through `schedule` while any request is queued or
   outstanding, and goes idle otherwise.  Nothing ever blocks or waits.

   The handle is plain data, like a dao.jing content handle:

     {:get-content-async-fn (fn [address callback])
      :materialize-async-fn (fn [payload callback])
      :put-content-async-fn (fn [address payload callback])}

   Each callback is invoked exactly once, on the pump, with the stepped
   client's published completion for that request — `{:found? b :value v}`
   for a get, `{:materialized? true :address a :result r}` for a
   materialization — or its `:error` / `:lost` tail.  A request the rpc
   layer refuses without an id (`:terminal`, `:allocator-error`,
   `:invalid-request`) completes `:lost` with the rpc reason (or the
   outcome) at once, so no callback is ever left owed.

   Requests are only queued by the fns above; every stream operation
   happens inside the pump, so the stepped state has one owner even when
   requests arrive from other threads (JVM)."
  (:require #?@(:cljd [["dart:async" :as async]])
            [dao.jing :as jing]
            [dao.jing.remote.step :as step]))


(def default-budget
  "Response-medium elements each pump polls at most (`step`'s budget)."
  64)


(def default-delay-ms
  "Reschedule delay between pumps while work is owed."
  1)


(defn default-schedule
  "The host's non-blocking reschedule: run the thunk `f` after `delay-ms`
   on a timer (cljs setTimeout, cljd Timer, JVM delayed executor)."
  [delay-ms]
  (fn [f]
    #?(:cljd (async/Timer (Duration .milliseconds delay-ms) f)
       :clj (.execute (java.util.concurrent.CompletableFuture/delayedExecutor
                        (long delay-ms)
                        java.util.concurrent.TimeUnit/MILLISECONDS)
                      ^Runnable f)
       :cljs (js/setTimeout f delay-ms))
    nil))


(defn- submit
  "Submit one queued request to the stepped state. Returns
   `[:busy]` when an unsent envelope is owed (nothing submitted),
   `[:registered state' id]`, or `[:refused state' completion]` for an
   outcome that carries no id."
  [state [kind arg opts]]
  (let [r (case kind
            :get (step/request-get state arg)
            :materialize (step/request-materialize state arg (or opts {}))
            :put (step/request-put state (first arg) (second arg)))]
    (cond
      (= :busy (:outcome r)) [:busy]
      (some? (:id r)) [:registered (:state r) (:id r)]
      :else [:refused (:state r)
             {:lost (or (:dao.stream.rpc/reason r) (:outcome r))}])))


(defn async-content
  "An async content handle over one stepped-client state (see the
   namespace docstring). The caller attaches the state's rpc media first
   (`rpc.ws/init-client` after a WebSocket `attach!`, ring buffers in a
   test) and hands ownership of it to this handle. opts:
   {:schedule (fn [thunk]) — reschedule hook (default `default-schedule`
                             over :delay-ms); tests pass a manual queue
    :delay-ms n            — default `default-delay-ms`
    :budget n              — default `default-budget`
    :on-diagnostics f      — receives each step's non-empty diagnostics}"
  ([step-state] (async-content step-state nil))
  ([step-state opts]
   (let [budget (or (:budget opts) default-budget)
         schedule (or (:schedule opts)
                      (default-schedule (or (:delay-ms opts) default-delay-ms)))
         on-diagnostics (:on-diagnostics opts)
         ;; pump-owned
         client (volatile! step-state)
         callbacks (volatile! {})
         ;; shared with enqueuers
         ctl (atom {:inbox [], :scheduled? false})
         pump!
         (fn pump!
           []
           (let [inbox (:inbox (first (swap-vals! ctl assoc :inbox [])))
                 fire (volatile! [])]
             (try
               (loop [requests (seq inbox)]
                 (when requests
                   (let [request (first requests)
                         callback (peek request)
                         [tag state' x] (submit @client request)]
                     (case tag
                       :busy (swap! ctl update :inbox #(into (vec requests) %))
                       :registered (do (vreset! client state')
                                       (vswap! callbacks assoc x callback)
                                       (recur (next requests)))
                       :refused (do (vreset! client state')
                                    (vswap! fire conj [callback x])
                                    (recur (next requests)))))))
               (let [{:keys [state completions diagnostics]}
                     (step/step @client budget)]
                 (vreset! client state)
                 (when (and on-diagnostics (seq diagnostics))
                   (on-diagnostics diagnostics))
                 (doseq [c completions]
                   (when-some [callback (get @callbacks (:id c))]
                     (vswap! callbacks dissoc (:id c))
                     (vswap! fire conj [callback c]))))
               ;; every owed callback runs even if an earlier one throws;
               ;; the first throw surfaces after the batch
               (when-some [e (reduce (fn [e [callback c]]
                                       (try (callback c)
                                            e
                                            (catch #?(:cljd Object
                                                      :clj Throwable
                                                      :cljs :default)
                                                   t
                                              (or e t))))
                                     nil
                                     @fire)]
                 (throw e))
               (finally
                 ;; callbacks above may have queued more work; decide
                 ;; idleness only after they ran
                 (let [idle? (empty? @callbacks)
                       c (swap! ctl
                                (fn [c]
                                  (if (and idle? (empty? (:inbox c)))
                                    (assoc c :scheduled? false)
                                    c)))]
                   (when (:scheduled? c) (schedule pump!)))))))
         enqueue!
         (fn [request]
           (let [[old _] (swap-vals! ctl
                                     #(-> %
                                          (update :inbox conj request)
                                          (assoc :scheduled? true)))]
             (when-not (:scheduled? old) (schedule pump!)))
           nil)]
     {:get-content-async-fn
      (fn [address callback]
        ;; C1 before the queue, so a bad address throws at the caller
        ;; rather than inside the pump
        (when-not (jing/segment-address? address)
          (throw (ex-info
                   "an async content request names a non-segment address"
                   {:address address})))
        (enqueue! [:get address callback]))
      :materialize-async-fn
      (fn
        ([payload callback]
         (enqueue! [:materialize payload {} callback]))
        ([payload opts callback]
         (enqueue! [:materialize payload opts callback])))
      :put-content-async-fn
      (fn [address payload callback]
        (when-not (jing/segment-address? address)
          (throw (ex-info
                   "an async content request names a non-segment address"
                   {:address address})))
        (enqueue! [:put [address payload] nil callback]))})))
