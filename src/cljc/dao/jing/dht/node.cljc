(ns dao.jing.dht.node
  "UDP Kademlia peer implementing dao.jing.dht/IDhtNet.

  Wire format: one Transit-JSON message map per datagram. Requests carry
  {:op ... :rpc n :from peer}; replies carry {:op :reply :rpc n :from peer
  :value ...}. The wire ops are :ping, :find-node, :store-content, and
  :fetch-content; there are no roots, no CAS records, and no deletes.
  Request-response reliability is owned by the client as timeouts and
  retries (design doc, Transport: chunk fetch is not gossip). Every
  received :from is observed into the k-bucket table, so routing knowledge
  accretes from traffic itself.

  Limits, all named in docs/design/dao.jing.dht.md:
  - JVM only today (java.net.DatagramSocket); no browser/Node peer. The
    file stays .cljc to match dao.stream.udp's convention and to leave
    room for non-JVM transport branches; on other platforms it defines
    nothing.
  - One datagram per message: anything beyond max-datagram is refused on
    send and dropped on reply, never torn. Segments that exceed the budget
    stay local-only until DRDS fragmentation exists (store degrades to
    best-effort, fetch times out).
  - Peers are untrusted: incoming :store-content requests re-verify the
    content address against the payload before writing. No address
    validation or rate limiting yet (Operational reality, UDP
    amplification)."
  #?(:clj (:require [dao.jing :as jing]
                    [dao.jing.mem :as mem]
                    [dao.jing.dht :as dht]
                    [dao.jing.dht.kad :as kad]
                    [dao.stream.transit :as transit]))
  ;; :cljd nil must come FIRST: reader conditionals take the first
  ;; matching branch, and the cljd host pass also matches :clj, so with
  ;; :clj written first the import still reaches the Dart compiler.
  ;; The :namespaces rule is disabled project-wide (see root .cljstyle)
  ;; because it would otherwise reorder this to :clj-first on every
  ;; `cljstyle fix`, silently re-breaking the guard.
  #?(:cljd nil
     :clj (:import (java.net DatagramPacket DatagramSocket InetAddress)
                   (java.util.concurrent ConcurrentHashMap)
                   (java.util.concurrent.atomic AtomicBoolean AtomicLong))))


#?(:cljd nil
   :clj
   (def max-datagram
     "Conservative single-datagram payload budget. IPv6 guarantees only a
     1280-byte path MTU; headers leave roughly this much (design doc,
     Transport: 'MTU 1450 is optimistic')."
     1200))


;; =============================================================================
;; Codec
;; =============================================================================

#?(:cljd nil
   :clj (defn- encode
          ^bytes [msg]
          (.getBytes ^String (transit/encode msg) "UTF-8")))


#?(:cljd nil
   :clj (defn- decode
          [^DatagramPacket packet]
          (transit/decode (String. (.getData packet)
                                   (.getOffset packet)
                                   (.getLength packet)
                                   "UTF-8"))))


#?(:cljd nil
   :clj
   (defn- send-datagram!
     [^DatagramSocket socket ^bytes payload ^InetAddress addr port]
     (when (> (alength payload) max-datagram)
       (throw
         (ex-info
           "payload exceeds the datagram budget; DRDS fragmentation is not implemented"
           {:size (alength payload), :max max-datagram})))
     (.send socket
            (DatagramPacket. payload (alength payload) addr (int port)))))


;; =============================================================================
;; Request handling (the server half)
;; =============================================================================

#?(:cljd nil
   :clj
   (def ^:private missing
     "Internal not-found sentinel for served :fetch-content reads, never
      exposed. An opaque per-host identity object, never a keyword: a
      keyword sentinel could be confused with a genuinely stored payload,
      and nil is a legal one."
     (Object.)))


#?(:cljd nil
   :clj
   (defn- handle
     "Serve one request against this node's local content store."
     [local table msg]
     (case (:op msg)
       :ping {:pong true}
       :find-node {:peers (kad/nearest @table (:target msg) kad/k)}
       ;; peers are untrusted: the address must be the exact strict
       ;; segment address for the payload, never a bare hash, a
       ;; foreign namespace, or a mismatched value, and the local
       ;; backend's verdict must be an explicit :inserted/:present
       :store-content
       (let [{:keys [address v]} msg]
         (try
           (if (and (jing/segment-address? address)
                    (= (jing/segment-hash address) (jing/content-hash v)))
             (let [result ((:put-content-fn local) address v)]
               (if (#{:inserted :present} result) {:ok true} {:ok false}))
             {:ok false})
           (catch Exception _ {:ok false})))
       ;; :found travels explicitly on both reads: values are opaque
       ;; and nil is a legal one, so it cannot be inferred from :v
       :fetch-content
       (let [v (jing/get local (:address msg) missing)]
         (if (identical? v missing) {:found false} {:found true, :v v}))
       ;; no root or CAS op exists on this wire; unknown ops are
       ;; thrown and answered as {:error ...} by the receiver
       (throw (ex-info "dao.jing.dht.node: unknown op" {:op (:op msg)})))))


#?(:cljd nil
   :clj
   (defn- start-receiver!
     "One daemon thread per node: decodes datagrams, observes senders into
      the routing table, delivers replies to pending requests, and serves
      requests. Replies go to the datagram's source address, not the claimed
      :from."
     [^DatagramSocket socket local table self ^ConcurrentHashMap pending]
     (doto
       (Thread.
         (fn []
           (let [buf (byte-array 65536)
                 packet (DatagramPacket. buf (alength buf))]
             (while (not (.isClosed socket))
               ;; three failure domains, contained separately so no
               ;; single datagram or transient fault can kill the
               ;; loop: the loop exits only when the socket closes
               (when (try (.receive socket packet)
                          true
                          (catch Exception _
                            ;; socket closed -> while exits; any
                            ;; other receive fault: back off instead
                            ;; of spinning hot. 10ms is not tuned,
                            ;; only bounded: short enough to shrug
                            ;; off a transient fault, long enough to
                            ;; cap a persistent one at ~100
                            ;; attempts/sec
                            (when-not (.isClosed socket) (Thread/sleep 10))
                            false))
                 (let [msg (try (decode packet) (catch Exception _ nil))]
                   (when (map? msg)
                     (try (when-let [from (:from msg)]
                            (when (not= (:id from) (:id self))
                              (swap! table kad/observe (:id self) from)))
                          (if (= :reply (:op msg))
                            (when-let [p (.remove pending (:rpc msg))]
                              (deliver p (:value msg)))
                            (let [value (try (handle local table msg)
                                             (catch Exception e
                                               {:error (str (type e))}))
                                  reply (encode {:op :reply,
                                                 :rpc (:rpc msg),
                                                 :from self,
                                                 :value value})]
                              ;; an oversized reply is dropped, not
                              ;; torn; the requester times out and
                              ;; treats this peer as not holding the
                              ;; value
                              (when (<= (alength reply) max-datagram)
                                (.send socket
                                       (DatagramPacket. reply
                                                        (alength reply)
                                                        (.getAddress packet)
                                                        (.getPort packet))))))
                          ;; a hostile message (e.g. malformed :from)
                          ;; is dropped, never fatal
                          (catch Exception _ nil)))))))))
       (.setDaemon true)
       (.start))))


;; =============================================================================
;; Requests (the client half)
;; =============================================================================

#?(:cljd nil
   :clj (defn- attempt!
          [{:keys [^DatagramSocket socket ^ConcurrentHashMap pending
                   ^AtomicLong rpc-counter peer]} to msg timeout-ms]
          (let [rpc (.getAndIncrement rpc-counter)
                p (promise)]
            (.put pending rpc p)
            (try (send-datagram! socket
                                 (encode (assoc msg
                                                :rpc rpc
                                                :from peer))
                                 (InetAddress/getByName (:host to))
                                 (:port to))
                 (deref p timeout-ms ::timeout)
                 ;; an unresolvable host, a socket fault, or an oversized
                 ;; payload is the same fact as silence: the peer is
                 ;; unreachable, and the
                 ;; IDhtNet contract wants nil, not a throw
                 (catch Exception _ ::timeout)
                 (finally (.remove pending rpc))))))


#?(:cljd nil
   :clj
   (defn- request!
     "Request-response over fire-and-forget UDP: this client owns timeouts
      and retries. Returns the reply value, or nil when the peer stays
      unreachable."
     [node to msg]
     (let [{:keys [timeout-ms tries], :or {timeout-ms 500, tries 3}} (:opts
                                                                       node)]
       (loop [n tries]
         (when (pos? n)
           (let [res (attempt! node to msg timeout-ms)]
             (if (= ::timeout res) (recur (dec n)) res)))))))


;; =============================================================================
;; The node
;; =============================================================================

#?(:cljd nil
   :clj (defrecord UdpNode
          [^DatagramSocket socket peer local table
           ^ConcurrentHashMap pending ^AtomicLong rpc-counter
           ^AtomicBoolean closed receiver opts]

          dht/IDhtNet

          (self-peer [_] peer)


          (known-peers [_ target-id n] (kad/nearest @table target-id n))


          (find-closer
            [this to target-id]
            (when-let [res (request! this
                                     to
                                     {:op :find-node, :target target-id})]
              (let [found (remove #(= (:id %) (:id peer)) (:peers res))]
                (doseq [q found] (swap! table kad/observe (:id peer) q))
                found)))


          (store-content!
            [this to address payload]
            (try (boolean (:ok (request! this
                                         to
                                         {:op :store-content,
                                          :address address,
                                          :v payload})))
                 (catch Exception _ false)))


          (fetch-content
            [this to address]
            (when-let [res (request! this
                                     to
                                     {:op :fetch-content, :address address})]
              {:found? (boolean (:found res)), :value (:v res)}))


          (close-net!
            [_]
            (when (.compareAndSet closed false true) (.close socket))
            nil)))


#?(:cljd nil
   :clj
   (defn create-node
     "Open a UDP Kademlia peer implementing dht/IDhtNet. opts:
        :host        bind/advertised address (default \"127.0.0.1\")
        :port        listen port (default 0 = ephemeral)
        :local       the content handle this peer serves (default
                     dao.jing.mem/create-content-mem)
        :bootstrap   seq of {:host :port} of existing peers
        :timeout-ms  per-attempt reply timeout (default 500)
        :tries       attempts per request (default 3)"
     [{:keys [host port local bootstrap], :as opts}]
     (let [host (or host "127.0.0.1")
           socket (DatagramSocket. (int (or port 0)))
           port (.getLocalPort socket)
           local (or local (mem/create-content-mem))
           peer {:id (dht/node-id host port), :host host, :port port}
           table (atom {})
           pending (ConcurrentHashMap.)
           receiver (start-receiver! socket local table peer pending)
           node (->UdpNode socket
                           peer
                           local
                           table
                           pending
                           (AtomicLong. 0)
                           (AtomicBoolean. false)
                           receiver
                           (select-keys opts [:timeout-ms :tries]))]
       (doseq [b bootstrap]
         ;; the ping reply's :from carries the bootstrap peer's id, which
         ;; the receiver observes into the routing table
         (request! node b {:op :ping}))
       (when (seq bootstrap)
         ;; iterative self-lookup populates the neighborhood
         (dht/lookup node (:id peer)))
       node)))


#?(:cljd nil
   :clj
   (defn create-content-dht-udp
     "Convenience: open a UDP node and wrap it as a DHT content-store
      handle. The node and the store share `local`, so this peer serves the
      same bytes it reads."
     [opts]
     (let [local (or (:local opts) (mem/create-content-mem))
           node (create-node (assoc opts :local local))]
       (dht/create-content-dht {:net node, :local local}))))
