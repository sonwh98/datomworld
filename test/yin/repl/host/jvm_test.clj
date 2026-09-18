(ns yin.repl.host.jvm-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.ws :as ws]
            [dao.stream.ws.jvm :as jvm]
            [yin.repl.host.jvm :as host])
  (:import [java.net ServerSocket]))


(def portable-admission
  {:retention :evict-oldest :capacity 32 :value-domain :portable-values})


(def handoff-admission
  {:retention :evict-oldest :capacity 1 :value-domain :host-values})


(defn- buffer
  [capacity]
  (:dao.stream/handle
    (ring/create! {:dao.stream/type ring/transport-type
                   ring/capacity-key capacity})))


(defn- target
  [handle]
  {:dao.stream/handle handle :dao.stream/surface #{:writer}})


(defn- cursor
  [handle]
  (:dao.stream/cursor (stream/cursor handle stream/anchor-newest)))


(defn- free-port
  []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))


(defn- await-next
  [handle at]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (let [result (stream/next handle at)]
        (if (or (not= :dao.stream/blocked (:dao.stream/outcome result))
                (>= (System/currentTimeMillis) deadline))
          result
          (do (Thread/sleep 5) (recur)))))))


(defn- await-pred
  [pred]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (if (or (pred) (>= (System/currentTimeMillis) deadline))
        (pred)
        (do (Thread/sleep 5) (recur))))))


(deftest websocket-is-the-complete-host-seam
  (let [adapter (host/websocket)]
    (is (= #{:connect! :bind! :unbind!} (set (keys adapter))))
    (is (every? fn? (vals adapter)))))


(deftest request-targets-are-canonicalized-before-resolution
  (is (= "/repl/~user/x/" (jvm/canonical-path "/repl/%7euser/a/../x/./?ignored=1")))
  (is (= "/" (jvm/canonical-path "")))
  (is (nil? (jvm/canonical-path "relative")))
  (is (nil? (jvm/canonical-path "/bad/%xy"))))


(deftest real-jvm-sockets-translate-traffic-and-lifecycle-to-data
  (let [port (free-port)
        descriptor {:dao.stream/type ws/transport-type
                    :dao.stream/identity "jvm-real-socket"
                    :ws/host "127.0.0.1"
                    :ws/port port
                    :ws/path "/repl"}
        control (buffer 32)
        offer (buffer 1)
        ack (buffer 1)
        offer-cursor (cursor offer)
        ack-cursor (cursor ack)
        endpoint (ws/make-endpoint
                   {:served {"/repl" descriptor}
                    :control (target control)
                    :control-admission portable-admission
                    :slots [{:offer (target offer)
                             :offer-admission handoff-admission
                             :ack (target ack)
                             :ack-admission handoff-admission
                             :ack-cursor ack-cursor}]})
        lifecycle (atom [])
        deposit! (fn [kind value] (swap! lifecycle conj [kind value]))
        adapter (host/websocket)
        listener ((:bind! adapter)
                  {:endpoint endpoint
                   :bind-host "127.0.0.1"
                   :bind-port port
                   :path "/repl"
                   :accept! (fn [path socket now]
                              (ws/accept-connection! endpoint path socket now))
                   :deposit! deposit!})
        client-traffic (buffer 32)
        client-cursor (cursor client-traffic)
        attach! (ws/make-attacher {:traffic (target client-traffic)
                                   :admission portable-admission
                                   :connect! (:connect! adapter)})
        attached (attach! descriptor)]
    (try
      (is (= :dao.stream/ok (:dao.stream/outcome listener)))
      (is (= :bind-succeeded (ffirst @lifecycle)))
      (is (= port (get-in @lifecycle [0 1 :port])))
      (is (= :dao.stream/ok (:dao.stream/outcome attached)))

      (let [offered (await-next offer offer-cursor)
            offer-event (:dao.stream/value offered)
            attachment (:ws/attachment offer-event)
            server-handle (get-in offer-event [:ws/handle :dao.stream/handle])
            server-traffic (buffer 32)
            server-cursor (cursor server-traffic)]
        (is (= :dao.stream/ok (:dao.stream/outcome offered)))
        (is (= :ws/accepted (:ws/event offer-event)))
        (is (= :dao.stream/ok
               (:dao.stream/outcome
                 (stream/append! ack {:ws/attachment attachment
                                      :ws/command :ws/accept
                                      :ws/deposit (target server-traffic)
                                      :ws/admission portable-admission}))))
        (ws/endpoint-step endpoint (System/currentTimeMillis))

        (let [opened (await-next client-traffic client-cursor)
              client-next (:dao.stream/cursor opened)]
          (is (= :ws/opened (get-in opened [:dao.stream/value :ws/event])))

          (is (= :dao.stream/ok
                 (:dao.stream/outcome
                   (stream/append! (:dao.stream/handle attached) {:from :client}))))
          (let [request (await-next server-traffic server-cursor)]
            (is (= :ws/payload (get-in request [:dao.stream/value :ws/event])))
            (is (= {:from :client} (get-in request [:dao.stream/value :ws/value]))))

          (is (= :dao.stream/ok
                 (:dao.stream/outcome (stream/append! server-handle {:from :server}))))
          (let [response (await-next client-traffic client-next)
                response-next (:dao.stream/cursor response)]
            (is (= :ws/payload (get-in response [:dao.stream/value :ws/event])))
            (is (= {:from :server} (get-in response [:dao.stream/value :ws/value])))

            (testing "a served-stream end remains distinct from detachment"
              (is (= :dao.stream/ok
                     (:dao.stream/outcome (ws/close-ended! server-handle))))
              (is (= :ws/ended
                     (get-in (await-next client-traffic response-next)
                             [:dao.stream/value :ws/event])))))))

      (is (= :dao.stream/ok
             (:dao.stream/outcome ((:unbind! adapter) listener deposit!))))
      (is (await-pred #(some (fn [[kind _]] (= :stopped kind)) @lifecycle)))
      (finally
        (when-not (some (fn [[kind _]] (= :stopped kind)) @lifecycle)
          ((:unbind! adapter) listener deposit!))))))
