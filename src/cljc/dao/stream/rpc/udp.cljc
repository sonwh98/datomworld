(ns dao.stream.rpc.udp
  "UDP entry point for dao.stream.rpc -- the peer of dao.stream.rpc.ws.
   Use this when your transport is UDP; use dao.stream.rpc.client/init-client
   and dao.stream.rpc.server/serve-connection! directly for any other
   IDaoStream transport. The RPC core stays transport-agnostic either way.

   Unlike rpc.ws, both sides here are preconfigured peers: dao.stream.udp's
   transport replies to the fixed :host/:port given at open! time, never to a
   datagram's source address, so a UDP RPC server serves exactly one known
   peer per socket -- there is no accept-arbitrary-clients mode to mirror
   from rpc.ws/start!. (Source-address replies and per-sender demux are the
   DRDS/dht.node territory docs/design/daostream-udp-design.md covers.)

   Because UDP silently drops datagrams, this namespace wires in the
   reliability the bare primitives leave opt-in: connect! stores a default
   :tries on the client (same 3-attempt default as dao.jing.dht.node's
   request!), call! resends through dao.stream.rpc.retry, and serve!
   deduplicates resent req-ids through dao.stream.rpc.dedup so retries are
   safe for non-idempotent handlers.

   One datagram per append!, no fragmentation: a request or response whose
   encoded size exceeds the transport's :mtu (default 1450 bytes) throws
   immediately rather than failing on a retry-recoverable timeout.

   :clj-only: dao.stream.udp is JVM-only today."
  (:require [dao.stream :as ds]
            [dao.stream.udp]
            [dao.stream.rpc.client :as rpc-client]
            [dao.stream.rpc.server :as rpc-server]
            [dao.stream.rpc.retry :as retry]
            [dao.stream.rpc.dedup :as dedup]))


#?(:clj (defn- open-stream!
          [{:keys [peer-host peer-port listen-port mtu]}]
          (ds/open! (cond-> {:type :udp,
                             :host peer-host,
                             :port peer-port,
                             :listen-port listen-port}
                      mtu (assoc :mtu mtu)))))


#?(:clj
   (defn connect!
     "Open the calling side of a preconfigured UDP RPC pair. Returns an
      rpc-client client map; pass it to call! (retrying) or
      dao.stream.rpc.client/call! (single attempt).

      opts:
        :peer-host          - the serving peer's host (required)
        :peer-port          - the serving peer's listen-port (required)
        :listen-port        - local port to receive responses on (required;
                               the serving peer must be configured to send
                               here)
        :tries              - default total attempts per call! (default 3,
                               matching dao.jing.dht.node's request!)
        :request-timeout-ms - per-attempt timeout (default 500, matching
                               dao.jing.dht.node; the bare init-client
                               default of 5000 suits reliable transports,
                               not lossy ones)
        :mtu                - passed through to the :udp descriptor"
     [{:keys [tries request-timeout-ms],
       :or {tries 3, request-timeout-ms 500},
       :as opts}]
     (-> (rpc-client/init-client (open-stream! opts)
                                 {:request-timeout-ms request-timeout-ms})
         (assoc :tries tries))))


#?(:clj
   (defn call!
     "Send an op/args request over a connect!-ed client and wait for the
      response, resending on timeout up to the client's :tries total
      attempts (dao.stream.rpc.retry; the serve! side dedups resent
      req-ids, so this is safe for non-idempotent handlers)."
     [client op args]
     (retry/call-with-retry! client op args {:tries (get client :tries 1)})))


#?(:clj
   (defn serve!
     "Open the serving side of a preconfigured UDP RPC pair and serve
      handlers over it, deduplicating resent req-ids
      (dao.stream.rpc.dedup) so client retries never re-execute a handler.

      handlers - a {op-keyword fn} map built in trusted process code; the
                 op keyword arriving over the wire is only ever a lookup
                 key into this map.
      opts:
        :listen-port    - local port to receive requests on (required)
        :peer-host      - the calling peer's host (required)
        :peer-port      - the calling peer's listen-port (required; this is
                           where responses are sent)
        :max-cache-size - dedup cache bound (default 1024)
        :mtu            - passed through to the :udp descriptor

      Returns {:stream <stream> :stop! <fn>}."
     [handlers {:keys [max-cache-size], :or {max-cache-size 1024}, :as opts}]
     (let [stream (open-stream! opts)
           stop-atom (atom false)]
       (rpc-server/serve-connection!
         handlers
         stream
         stop-atom
         {:wrap-dispatch #(dedup/wrap-dedup % {:max-size max-cache-size})})
       {:stream stream,
        :stop! (fn [] (reset! stop-atom true) (ds/close! stream))})))


#?(:clj (defn stop!
          "Stop a running serve! handle."
          [server]
          ((:stop! server))))


;; =============================================================================
;; Loopback convenience
;; =============================================================================

#?(:clj
   (defn- random-port
     "10000-59999 overlaps the OS ephemeral port range (typically
      32768/49152-60999/65535 depending on platform), so a draw can
      collide with an OS-assigned port and fail with a BindException --
      pre-existing risk across this test suite's other random-port helpers,
      just worth naming now that it lives in src/."
     []
     (+ 10000 (rand-int 50000))))


#?(:clj
   (defn distinct-ports
     "Returns [port-a port-b], guaranteed distinct -- two independent random
      draws can collide and fail with a confusing BindException instead of a
      clear test failure. Public so loopback callers (tests, REPL) can draw
      ports for an explicit connect!/serve! pair."
     []
     (loop []
       (let [p1 (random-port)
             p2 (random-port)]
         (if (= p1 p2) (recur) [p1 p2])))))


#?(:clj
   (defn open-pair!
     "Open both raw streams of a loopback UDP pair in this process -- for
      tests or REPL exploration that drive the bare primitives directly.
      Draws two distinct ports automatically. For the assembled experience
      (retry, dedup) use connect! + serve! instead.

      opts: :host (default \"127.0.0.1\").

      Returns {:server-stream ... :client-stream ...}."
     ([] (open-pair! {}))
     ([{:keys [host], :or {host "127.0.0.1"}}]
      (let [[server-port client-port] (distinct-ports)]
        {:server-stream (open-stream! {:peer-host host,
                                       :peer-port client-port,
                                       :listen-port server-port}),
         :client-stream (open-stream! {:peer-host host,
                                       :peer-port server-port,
                                       :listen-port client-port})}))))
