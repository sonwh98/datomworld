(ns dao.jing.dht.facade
  "The JVM blocking facade over a DHT composition
   (docs/design/dao.jing.dht.md section 5.2). Host policy, JVM only,
   optional; no portable code is written against it.

   start! owns one driver thread: it is the DHT state's one step owner and
   the tick adapter's cadence. Each turn it appends one
   {:dao.lease/event :dao.lease/tick :dao.lease/reading ms} reading
   (milliseconds since start, from the monotonic clock, so readings never
   decrease) to the composition's :ticks, steps the state, and sleeps
   :poll-interval-ms.

   The returned handle is a dao.jing byte-store handle. Its put-bytes-fn
   is dao.jing.dht/store-handle's: the local verdict, at once, never
   waiting for the acknowledgement. Its get-bytes-fn reads :local and, on
   a miss, issues :jing/get through dao.jing.content.driver, which waits to
   its own request deadline (:request-timeout-ms) while the driver thread
   steps the DHT; the verified bytes are then in :local as well.

   The composition's :ticks must also be a writer (a ring owner handle
   is), and :requests and :answers are the pair the content driver speaks
   over."
  (:require [dao.jing.content.driver :as driver]
            [dao.jing.content.step :as step]
            [dao.jing.dht :as dht]
            [dao.stream :as stream]))


(def default-poll-interval-ms
  "How long the driver thread sleeps between steps, in milliseconds."
  5)


(def default-budget
  "Each step's per-stage budget."
  64)


(defn start!
  "Start a DHT driver thread over `composition` and return its blocking
   dao.jing handle {:local :put-bytes-fn :get-bytes-fn :close-fn :thread}.
   Options: :poll-interval-ms, :budget, and the content driver's
   :request-timeout-ms. close-fn stops and joins the thread, then closes
   the request pair and :local."
  ([composition] (start! composition {}))
  ([composition {:keys [poll-interval-ms budget request-timeout-ms]
                 :or {poll-interval-ms default-poll-interval-ms
                      budget default-budget}}]
   (let [initial (dht/state composition)
         {:keys [local requests answers ticks]} composition
         _ (when-not (stream/writer? ticks)
             (throw (ex-info "the facade appends ticks: :ticks must be a writer"
                             {})))
         store (dht/store-handle
                 {:local local
                  :requests requests
                  :max-message-bytes (::dht/max-message-bytes initial)})
         client (driver/driver
                  (step/client-state requests answers
                                     (:dao.stream/cursor
                                       (stream/cursor answers
                                                      :dao.stream/newest)))
                  (cond-> {:poll-interval-ms 1}
                    request-timeout-ms (assoc :request-timeout-ms
                                              request-timeout-ms)))
         running (atom true)
         origin (System/nanoTime)
         thread (Thread.
                  ^Runnable
                  (fn []
                    (loop [state initial]
                      (when @running
                        (stream/append! ticks
                                        {:dao.lease/event :dao.lease/tick
                                         :dao.lease/reading
                                         (quot (- (System/nanoTime) origin)
                                               1000000)})
                        (let [state (dht/step state budget)]
                          (when (try (Thread/sleep ^long poll-interval-ms)
                                     true
                                     (catch InterruptedException _ false))
                            (recur state))))))
                  "dao.jing.dht.facade")
         absent (Object.)
         closed (atom false)]
     (.setDaemon thread true)
     (.start thread)
     {:local local
      :thread thread
      :put-bytes-fn (:put-bytes-fn store)
      :get-bytes-fn
      (fn [address not-found]
        (let [bs ((:get-bytes-fn local) address absent)]
          (if (identical? absent bs)
            ((:get-bytes-fn client) address not-found)
            bs)))
      :close-fn
      (fn []
        (when (compare-and-set! closed false true)
          (reset! running false)
          (.interrupt thread)
          (.join thread)
          ((:close-fn client))
          ((:close-fn store))))})))
