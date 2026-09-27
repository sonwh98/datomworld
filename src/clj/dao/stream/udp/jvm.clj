(ns dao.stream.udp.jvm
  "The JVM host edge for dao.stream.udp: one DatagramSocket, bound once,
   with a daemon receiver thread that feeds every datagram's source
   address and raw bytes to a composed `receive-fn`
   (dao.stream.udp/receive!, partially applied over the port), and the
   :send! seam dao.stream.udp/make-port composes over. All protocol
   logic -- fragmentation, reassembly, the deposit shape -- stays in
   dao.stream.udp; this adapter only owns the socket and its thread."
  (:import (java.net DatagramPacket DatagramSocket InetAddress)))


(defn bind!
  "Bind a DatagramSocket at `port` (0 for an ephemeral port) and start
   its daemon receiver thread. Returns {:socket s :local-port p :send!
   f :close! f}; `:send!` is `(send! host dest-port bytes) -> nil`,
   the port composition's host seam."
  ([receive-fn] (bind! receive-fn 0))
  ([receive-fn port]
   (let [socket (DatagramSocket. (int port))
         running (atom true)
         thread
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
                   (let [n (.getLength packet)
                         bytes (java.util.Arrays/copyOfRange
                                 (.getData packet) 0 n)
                         host (.getHostAddress (.getAddress packet))
                         src-port (.getPort packet)]
                     (try (receive-fn host src-port bytes)
                          (catch Exception _ nil))))))))]
     (.setDaemon thread true)
     (.start thread)
     {:socket socket
      :local-port (.getLocalPort socket)
      :send! (fn [host dest-port bytes]
               (.send socket
                      (DatagramPacket. bytes (alength bytes)
                                       (InetAddress/getByName host)
                                       (int dest-port)))
               nil)
      :close! (fn []
                (reset! running false)
                (.close socket)
                nil)})))
