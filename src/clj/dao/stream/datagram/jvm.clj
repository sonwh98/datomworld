(ns dao.stream.datagram.jvm
  "The JVM host seam of docs/design/dao.stream.datagram.md 3: one
   DatagramSocket, bound once, with a daemon receiver thread that deposits
   every host fact and every received datagram on the composition's
   deposit writer as plain dao.stream.datagram values. The seam takes
   {:identity id :deposit w :bind-host h :bind-port p :max-bytes n} -- a
   deposit writer and nothing else, no function to invoke -- and returns at
   once {:send! f :close! f}; binding is synchronous here, so the bound
   event is already on the deposit writer when bind! returns, and a failed
   bind deposits bind-failed and never answers ok on send!. All protocol
   interpretation -- validation, fragmentation, projection -- stays above
   this seam; the transform deposits and returns, judging no datagram
   except the max-bytes drop, which is protocol validation below it:
   never deposited."
  (:require [clojure.string :as str]
            [dao.stream :as stream]
            [dao.stream.datagram :as datagram])
  (:import (java.net DatagramPacket DatagramSocket InetAddress
                     InetSocketAddress)))


(defn bind!
  "Bind one DatagramSocket per the seam contract. `:bind-host` is the
   local interface address (the wildcard address is legal), `:bind-port` 0
   asks for an ephemeral port, `:max-bytes` defaults to 1200 and is the
   symmetric datagram budget. Returns {:send! f :close! f} at once:
   `:send!` is (send! host port bytes) -> {:dao.stream/outcome
   :dao.stream/ok} | {:dao.stream/outcome :dao.stream/transport-error
   :dao.stream.datagram/reason text} (a synchronous host failure, clean:
   nothing was sent), and every send failure is also deposited as a
   send-failed event; `:close!` closes the host socket, deposits closed
   while the deposit writer still can, and is idempotent."
  [{:keys [identity deposit bind-host bind-port max-bytes]
    :or {bind-host "0.0.0.0" bind-port 0}}]
  (let [max-bytes (long (or max-bytes datagram/default-max-bytes))
        running (atom true)
        closed-deposited (atom false)
        deposit-event!
        (fn [event]
          (try (stream/append! deposit event)
               (catch Exception _ nil)))
        socket
        (try
          (DatagramSocket.
            (InetSocketAddress.
              ^InetAddress (InetAddress/getByName ^String bind-host)
              (int bind-port)))
          (catch Exception e
            (deposit-event! (datagram/bind-failed-event
                              identity (str e)))
            nil))]
    (when socket
      (deposit-event!
        (datagram/bound-event identity
                              (.getHostAddress (.getLocalAddress socket))
                              (.getLocalPort socket)))
      (let [thread
            (Thread.
              (fn []
                (let [buf (byte-array 65536)
                      packet (DatagramPacket. buf (alength buf))]
                  (while @running
                    ;; .receive leaves the packet's length at what the last
                    ;; datagram filled; reset it to the buffer's own length
                    ;; so a datagram after a short one is not truncated.
                    (.setLength packet (alength buf))
                    (when (try (.receive socket packet)
                               true
                               (catch Exception _
                                 (when @running (Thread/sleep 10))
                                 false))
                      (let [n (.getLength packet)]
                        (when (<= n max-bytes)
                          (deposit-event!
                            (datagram/datagram-event
                              identity
                              (.getHostAddress (.getAddress packet))
                              (.getPort packet)
                              (java.util.Arrays/copyOfRange
                                (.getData packet) 0 n))))))))))]
        (.setDaemon thread true)
        (.start thread)))
    {:send!
     (fn [host port bytes]
       (if-not socket
         {:dao.stream/outcome :dao.stream/transport-error
          :dao.stream.datagram/reason "bind failed"}
         (if (not= (str/includes? bind-host ":") (str/includes? host ":"))
           (do (deposit-event!
                 (datagram/send-failed-event identity "IP family mismatch"))
               {:dao.stream/outcome :dao.stream/transport-error
                :dao.stream.datagram/reason "IP family mismatch"})
           (try
             (.send ^DatagramSocket socket
                    (DatagramPacket. ^bytes bytes (alength ^bytes bytes)
                                     (InetAddress/getByName ^String host)
                                     (int port)))
             {:dao.stream/outcome :dao.stream/ok}
             (catch Exception e
               (let [reason (str (.getMessage ^Exception e))]
                 (deposit-event! (datagram/send-failed-event identity reason))
                 {:dao.stream/outcome :dao.stream/transport-error
                  :dao.stream.datagram/reason reason}))))))
     :close!
     (fn []
       (when (compare-and-set! closed-deposited false true)
         (reset! running false)
         (when socket
           (.close ^DatagramSocket socket))
         (deposit-event! (datagram/closed-event identity)))
       nil)}))
