(ns dao.stream.udp.node
  "The Node host edge for dao.stream.udp: one `dgram` socket, bound
   once, whose \"message\" event feeds every datagram's source address
   and raw bytes to a composed `receive-fn` (dao.stream.udp/receive!,
   partially applied over the port), and the :send! seam
   dao.stream.udp/make-port composes over. All protocol logic --
   fragmentation, reassembly, the deposit shape -- stays in
   dao.stream.udp; this adapter only owns the socket. A Node Buffer
   already satisfies dao.stream.cbor's byte-payload? (it extends
   Uint8Array), so a received message reaches `receive-fn` unconverted."
  (:require ["dgram" :as dgram]))


(defn bind!
  "Bind a UDP socket at `port` (0 for an ephemeral port). Returns
   {:socket s :local-port p :send! f :close! f} once bound; `on-bound`
   is called with that map, because Node's bind is asynchronous."
  ([receive-fn on-bound] (bind! receive-fn 0 on-bound))
  ([receive-fn port on-bound]
   (let [socket (.createSocket dgram "udp4")]
     (.on socket "message"
          (fn [msg rinfo]
            (receive-fn (.-address rinfo) (.-port rinfo) msg)))
     (.on socket "listening"
          (fn []
            (let [address (.address socket)]
              (on-bound
                {:socket socket
                 :local-port (.-port address)
                 :send! (fn [host dest-port bytes]
                          (.send socket bytes dest-port host)
                          nil)
                 :close! (fn [] (.close socket) nil)}))))
     (.bind socket port))))
