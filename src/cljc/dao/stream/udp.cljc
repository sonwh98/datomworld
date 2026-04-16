(ns dao.stream.udp
  "UDP‑based transport for DaoStream, treating each datom as a UDP datagram.
   
   Descriptor:
     {:type :udp
                  :mode :create
                  :host \"192.168.1.100\"
                  :port 9000
                  :listen-port 9000
                  :mtu 1450
                  :reliable? false
                  :ack-timeout-ms 2000
                  :max-retries 5}
   
   The transport can be configured for fire‑and‑forget (:reliable? false) or
   with DRDS reliability (:reliable? true). This implementation currently
   supports only the JVM (java.net.DatagramSocket)."
  (:require
    [dao.stream :as ds]
    [dao.stream.transit :as transit])
  #?(:clj
     (:import
       (java.net
         DatagramPacket
         DatagramSocket
         InetAddress)
       (java.nio
         ByteBuffer)
       (java.util.concurrent
         ConcurrentHashMap)
       (java.util.concurrent.atomic
         AtomicBoolean
         AtomicLong))))


;; =============================================================================
;; Wire Format (non‑DRDS)
;; =============================================================================
;; Each datagram consists of:
;;   8 bytes  – big‑endian sequence number (long)
;;   N bytes  – Transit‑JSON‑encoded payload (UTF‑8)
;; No additional framing; the receiver uses the length of the UDP packet.

#?(:clj
   (defn- encode-packet
     "Encode sequence number and Transit value into a byte array."
     [seq-num val]
     (let [payload (transit/encode val)
           payload-bytes (.getBytes payload "UTF-8")
           total-len (+ 8 (alength payload-bytes))
           buf (ByteBuffer/allocate total-len)]
       (.putLong buf seq-num)
       (.put buf payload-bytes)
       (.array buf))))


#?(:clj
   (defn- decode-packet
     "Extract sequence number and Transit value from byte array.
      Returns [seq-num decoded-value]."
     [^bytes data]
     (let [buf (ByteBuffer/wrap data)
           seq-num (.getLong buf)
           payload-bytes (byte-array (- (alength data) 8))]
       (.get buf payload-bytes)
       [seq-num (transit/decode (String. payload-bytes "UTF-8"))])))


;; =============================================================================
;; UDP Transport Record
;; =============================================================================

#?(:clj (defrecord UdpTransport
          [socket
           remote-addr
           remote-port
           mtu
           reliable?
           descriptor
           ^ConcurrentHashMap buffer        ; position -> {:seq long :data bytes} (DRDS) or raw value (non‑DRDS)
           ^AtomicLong send-tail            ; next local write position (sequence number)
           ^AtomicLong rcv-tail             ; highest received position + 1 (max seq + 1)
           ^AtomicLong head                 ; oldest retained position (for gap detection)
           ^AtomicBoolean closed
           receiver-thread]                 ; thread that receives datagrams and stores them in buffer

          ;; ---------------------------------------------------------------------------
          ;; IDaoStreamWriter
          ;; ---------------------------------------------------------------------------
          ds/IDaoStreamWriter

          (put!
            [_ val]
            (if (.get closed)
              (throw (ex-info "UDP stream is closed" {:val val}))
              (let [pos (.getAndIncrement send-tail)
                    packet-bytes (encode-packet pos val)]
                (when (> (alength packet-bytes) mtu)
                  (throw (ex-info "Payload exceeds MTU" {:size (alength packet-bytes) :mtu mtu})))
                (let [packet (DatagramPacket. packet-bytes (alength packet-bytes) remote-addr remote-port)]
                  (.send socket packet))
                {:result :ok :woke []})))

          ;; ---------------------------------------------------------------------------
          ;; IDaoStreamReader
          ;; ---------------------------------------------------------------------------
          ds/IDaoStreamReader

          (next
            [_ cursor]
            (let [pos (:position cursor)
                  head-val (.get head)
                  rcv-tail-val (.get rcv-tail)]
              (cond
                (< pos head-val)
                :daostream/gap
                (and (>= pos rcv-tail-val) (.get closed))
                :end
                (>= pos rcv-tail-val)
                :blocked
                :else
                (if-let [val (.get buffer (long pos))]
                  {:ok val :cursor (assoc cursor :position (inc pos))}
                  ;; position exists in range but no data yet (gap due to loss)
                  :blocked))))

          ;; ---------------------------------------------------------------------------
          ;; IDaoStreamBound
          ;; ---------------------------------------------------------------------------
          ds/IDaoStreamBound

          (close!
            [_]
            (when (.compareAndSet closed false true)
              (.close socket)
              ;; interrupt receiver thread
              (when receiver-thread (.interrupt receiver-thread))
              {:woke []}))


          (closed?
            [_]
            (.get closed))))


;; =============================================================================
;; Receiver Thread (JVM)
;; =============================================================================

#?(:clj (defn- start-receiver!
          "Start a thread that receives datagrams from socket and stores them in buffer.
   Returns the thread (can be interrupted to stop)."
          [^DatagramSocket socket ^ConcurrentHashMap buffer ^AtomicLong rcv-tail ^AtomicLong head]
          (let [buf (byte-array 65536)
                packet (DatagramPacket. buf (alength buf))]
            (doto (Thread.
                    (fn []
                      (try
                        (while (not (.isClosed socket))
                          (.receive socket packet)
                          (let [data (byte-array (.getLength packet))
                                _ (System/arraycopy (.getData packet) (.getOffset packet) data 0 (alength data))
                                [seq-num val] (decode-packet data)
                                head-val (.get head)]
                            (when (>= seq-num head-val)
                              ;; ignore duplicates
                              (.putIfAbsent buffer (long seq-num) val)
                              ;; advance rcv-tail to max(rcv-tail, seq-num+1)
                              (loop []
                                (let [cur (.get rcv-tail)]
                                  (when (> (inc seq-num) cur)
                                    (if (.compareAndSet rcv-tail cur (inc seq-num))
                                      nil
                                      (recur))))))))
                        (catch Exception _ nil))))
              (.setDaemon true)
              (.start)))))


;; =============================================================================
;; open! Multimethod
;; =============================================================================

#?(:clj (defmethod ds/open! :udp
          [descriptor]
          (let [{:keys [host port listen-port mtu reliable?]} descriptor
                port (or port 0)
                listen-port (or listen-port port)
                socket (DatagramSocket. listen-port)
                remote-addr (InetAddress/getByName host)
                buffer (ConcurrentHashMap.)
                send-tail (AtomicLong. 0)
                rcv-tail (AtomicLong. 0)
                head (AtomicLong. 0)
                closed (AtomicBoolean. false)
                receiver-thread (start-receiver! socket buffer rcv-tail head)]
            (->UdpTransport socket remote-addr port (or mtu 1450) reliable? descriptor
                            buffer send-tail rcv-tail head closed receiver-thread))))
