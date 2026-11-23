(ns dao.stream.ws.jvm
  "The JVM host edge for the DaoStream v2 WebSocket transport.

   The Java HTTP client and http-kit own their callbacks only until those
   callbacks invoke the plain adapter entries supplied by `dao.stream.ws`.
   This namespace retains no application state and never drives an endpoint.

   The JVM WebSocket APIs expose no outbound high-water signal of their own.
   The client seam counts its own send chain as `:queued-bytes`, so the
   transport's outbound bounds are exact there; http-kit's server socket has
   no such count, so a served connection uses the transport's cumulative
   quota and stays gated.  A send accepted by the host is `:dao.stream/ok`.
   Acceptance is not delivery: `send!` returns as soon as the host has taken
   the message, and a
   send that fails afterwards is reported on the stream as `:ws/error` then a
   terminal `:ws/closed`.  Nothing here joins, parks, or sleeps — the operation
   must return what is true when it is called.

   Frames are typed end to end: text payloads ride `sendText`/`onText`, binary
   payloads ride `sendBinary`/`onBinary`, and this adapter never inspects a
   payload to classify it.  Fragmented messages (text or binary) are assembled
   so the transport sees one message per callback boundary, bounded by the
   composition's `:ws/max-frame-bytes`."
  (:require [dao.stream.codec :as codec]
            [dao.stream.ws :as ws]
            [dao.stream.ws.upgrade :as upgrade]
            [org.httpkit.server :as http])
  (:import [java.io ByteArrayOutputStream]
           [java.net URI]
           [java.net.http HttpClient WebSocket WebSocket$Listener]
           [java.nio ByteBuffer]
           [java.util.concurrent CompletableFuture]
           [org.httpkit.server AsyncChannel]))


(def ^:private http-kit-default-max-ws
  "http-kit's own `:max-ws` default, 4 MiB."
  (* 4 1024 1024))


(def offered-subprotocols
  "The upgrade request's offered subprotocol list, split and trimmed."
  upgrade/offered-subprotocols)


(def negotiate
  "Pick the first endpoint codec (in endpoint order) the upgrade offered, or
   nil when the offer shares no subprotocol with the endpoint."
  upgrade/negotiate)


(def canonical-path
  "Canonicalize an HTTP request target for exact served-path lookup."
  upgrade/canonical-path)


(defn socket-url
  [descriptor]
  (str "ws://" (:ws/host descriptor) ":" (:ws/port descriptor)
       (:ws/path descriptor)))


(defn- completed
  []
  (CompletableFuture/completedFuture nil))


(def ^:private unsendable-close-codes
  "The status codes `java.net.http.WebSocket.sendClose` rejects.  Among the
   transport's teardown codes this is 1009 (frame too large) and 1013
   (pending overflow, served connections only)."
  #{1002 1003 1006 1007 1009 1010 1012 1013 1015})


(defn client-socket
  "The `{:send! :close!}` boundary over an asynchronous client connection.

   `java.net.http.WebSocket` completes a `sendText` exceptionally while an
   earlier one is still pending, so outbound messages are chained behind the
   connection's `:pending` future rather than joined: the submitter returns as
   soon as the host has accepted the message (J1, J2). A send that fails after
   acceptance is reported on the stream — `:ws/error`, then the terminal
   `:ws/closed` after the socket is aborted — never as a return value (J3).

   Two disciplines make that safe. The chain is built under `locking` rather
   than `swap!`, whose retry would issue a second `sendText` for one message.
   And failure is a once-only connection transition claimed under the same
   lock: every future in a chain completes exceptionally when its predecessor
   does, so without the claim one lost socket would abort and report N times,
   and a teardown that re-entered through the adapter would report again. The
   first claimant reports; the connection then stays failed and accepts no
   further chaining.

   The connection's `:queued` counter is the outbound backlog: raised by a
   send's payload size under the submission lock, lowered when that send's
   future completes, either way.  `:queued-bytes` reads it, which makes the
   transport's outbound bounds exact on this host.

   A close whose code `sendClose` rejects (1009 on an inbound overflow) is
   not chained: the socket is aborted at once, behind no pending send, and
   the terminal is reported locally with the requested code and reason.  The
   peer sees the connection drop without a close frame (its 1006), never a
   1009.  The abort claims the connection's failure first, so the pending
   sends it fails report nothing further."
  [connection adapter]
  (letfn [(claim-failure!
            []
            (locking connection
              (when-not (:failed? @connection)
                (swap! connection assoc :failed? true)
                true)))
          (fail!
            []
            ;; Outside the lock: the adapter entries may re-enter through
            ;; close!, which must find the connection already failed rather
            ;; than block on a lock this thread holds.
            (when (claim-failure!)
              ((:error! adapter))
              (when-let [socket (:socket @connection)]
                (.abort ^WebSocket socket))
              ((:closed! adapter) 1006 "dao.stream/send-failed")))
          (abort!
            [socket code reason]
            (when (claim-failure!)
              (.abort ^WebSocket socket)
              ((:closed! adapter) code reason)))
          (chain!
            [size f]
            ;; The successor is built and installed under the lock; its
            ;; completion observer is registered after leaving it. An
            ;; already-exceptional future runs that observer inline, and
            ;; reporting a failure while holding the submission monitor would
            ;; block every other submitter across the adapter's deposits.
            (let [next (locking connection
                         (when-not (:failed? @connection)
                           (let [pending (or (:pending @connection) (completed))
                                 next (.thenCompose
                                        ^CompletableFuture pending
                                        (reify java.util.function.Function
                                          (apply [_ _] (f))))]
                             (swap! connection #(-> %
                                                    (assoc :pending next)
                                                    (update :queued (fnil + 0) size)))
                             next)))]
              (if next
                (do (.whenComplete
                      ^CompletableFuture next
                      (reify java.util.function.BiConsumer
                        (accept
                          [_ _v error]
                          (swap! connection update :queued - size)
                          (when error (fail!)))))
                    nil)
                ::failed)))]
    {:queued-bytes #(:queued @connection 0)
     :send! (fn [payload]
              (if-let [socket (:socket @connection)]
                (let [result (chain! (if (string? payload)
                                       (count payload)
                                       (alength ^bytes payload))
                                     #(if (string? payload)
                                        (.sendText ^WebSocket socket payload true)
                                        (.sendBinary ^WebSocket socket
                                                     (ByteBuffer/wrap ^bytes payload) true)))]
                  ;; A failed connection is permanently gone, so it answers
                  ;; `closed`, not the retryable `full` that `false` would
                  ;; mean: an append that read the handle's open phase before
                  ;; the failure claim must not leave a request retained as
                  ;; unsent for a socket that is never coming back.
                  (if (= ::failed result)
                    {:dao.stream/outcome :dao.stream/closed}
                    nil))
                false))
     :close! (fn [code reason]
               (if-let [socket (:socket @connection)]
                 ;; Closing something already gone is satisfied, not
                 ;; refused, so close! answers nil either way.
                 (do (if (contains? unsendable-close-codes code)
                       (abort! socket code reason)
                       (chain! 0 #(.sendClose ^WebSocket socket code reason)))
                     nil)
                 (do (swap! connection assoc :close-request [code reason]) nil)))}))


(defn connect!
  "Start an asynchronous JVM client attachment and return its raw socket seam.

   The client offers exactly the subprotocol of the codec profile the
   transport selected (the adapter map's `:ws/codec`), so a peer that does
   not speak it fails the handshake rather than being downgraded."
  [descriptor adapter]
  (let [connection (atom {:socket nil :future nil :close-request nil
                          :pending nil :failed? false})
        text (StringBuilder.)
        binary (ByteArrayOutputStream.)
        max-frame (:ws/max-frame-bytes adapter)
        ;; Set once a message outgrows the frame bound: its remaining
        ;; fragments are dropped, not buffered, until its last one.
        discarding? (volatile! false)
        assemble! (fn [size clear! last? message!]
                    (cond
                      @discarding? (when last? (vreset! discarding? false))
                      (and max-frame (> (size) max-frame))
                      (do (clear!)
                          (vreset! discarding? (not last?))
                          ((:too-large! adapter)))
                      last? (message!)))
        listener
        (reify WebSocket$Listener
          (onOpen
            [_ socket]
            (swap! connection assoc :socket socket)
            (when-let [[code reason] (:close-request @connection)]
              (.sendClose ^WebSocket socket code reason))
            ;; There is no admission wire frame any more: the mirror
            ;; answers not-found per identity once a reflection attaches,
            ;; not this transport's own accept/disclaim handshake, so the
            ;; socket's own open is the resolution.
            ((:opened! adapter))
            (.request ^WebSocket socket 1))

          (onText
            [_ socket data last?]
            (when-not @discarding? (.append text ^CharSequence data))
            (assemble! #(.length text) #(.setLength text 0) last?
                       #(let [message (str text)]
                          (.setLength text 0)
                          ((:message! adapter) message)))
            (.request ^WebSocket socket 1)
            (completed))

          (onBinary
            [_ socket data last?]
            ;; Java may fragment one binary message across callbacks; the
            ;; assembled bytes are one `:binary!` entry, never a sentinel.
            (when-not @discarding?
              (let [chunk (byte-array (.remaining ^ByteBuffer data))]
                (.get ^ByteBuffer data chunk)
                (.write binary chunk)))
            (assemble! #(.size binary) #(.reset binary) last?
                       #(let [message (.toByteArray binary)]
                          (.reset binary)
                          ((:binary! adapter) message)))
            (.request ^WebSocket socket 1)
            (completed))

          (onPing
            [_ socket data]
            (.request ^WebSocket socket 1)
            (.sendPong ^WebSocket socket ^ByteBuffer data))

          (onPong
            [_ socket _data]
            (.request ^WebSocket socket 1)
            (completed))

          (onClose
            [_ _socket code reason]
            ((:closed! adapter) code (str reason))
            (completed))

          (onError
            [_ _socket _error]
            ((:error! adapter))))
        builder (-> (HttpClient/newHttpClient)
                    (.newWebSocketBuilder)
                    (.subprotocols (codec/codec-name (:ws/codec adapter))
                                   (make-array String 0)))
        future (.buildAsync builder (URI/create (socket-url descriptor)) listener)]
    (swap! connection assoc :future future)
    (.whenComplete future
                   (reify java.util.function.BiConsumer
                     (accept
                       [_ _socket error]
                       (when error
                         ((:closed! adapter) 1006 "dao.stream/connect-failed")))))
    (client-socket connection adapter)))


(defn- server-socket
  "The http-kit socket seam. The endpoint can reject or adopt an offer while
   the handshake is being prepared. Refuse writes until the 101 is sent and
   hold any early close so its frame follows the handshake."
  [^AsyncChannel channel lifecycle subprotocol]
  {:send! (fn [payload]
            (let [{:keys [open? close]} @lifecycle]
              (cond
                close {:dao.stream/outcome :dao.stream/closed}
                open? (.send channel payload false)
                :else false)))
   :close! (fn [code reason]
             (let [[before _] (swap-vals! lifecycle
                                          #(if (:close %) % (assoc % :close [code reason])))]
               (when (and (:open? before) (nil? (:close before)))
                 (.serverClose channel (int code) reason))))
   :ws/subprotocol subprotocol})


(defn- upgrade!
  "Install the adapter before sending the 101. http-kit's Ring WebSocket
   wrapper calls on-open after sending it, and a receive worker can run
   before on-open finishes. The transport already queues frames that arrive
   before the endpoint acknowledges its offer."
  [request accept! clock deposit! codec]
  (if-let [key (http/websocket-handshake-check request)]
    (let [^AsyncChannel channel (:async-channel request)
          subprotocol (codec/codec-name codec)
          lifecycle (atom {})
          socket (server-socket channel lifecycle subprotocol)
          adapter (try
                    (let [target (or (canonical-path (:uri request)) (:uri request))]
                      (some-> (accept! target socket (clock)) :ws/handle ws/adapter))
                    (catch Throwable _
                      (deposit! :upgrade-failed
                                {:code :yin.repl.endpoint/upgrade-threw
                                 :message "the host failed while accepting an upgrade"})
                      ((:close! socket) 1011 "dao.stream/upgrade-failed")
                      nil))]
      (when adapter
        (let [{:keys [message! binary! closed!]} adapter
              report-close! (fn [code reason]
                              (let [[before _] (swap-vals! lifecycle assoc :reported? true)]
                                (when-not (:reported? before)
                                  (closed! code reason))))]
          (.setReceiveHandler channel
                              (fn [message]
                                (if (string? message)
                                  (message! message)
                                  (binary! message))))
          (.setCloseRingHandler channel report-close!)
          ;; http-kit does not replay a close that preceded registration.
          (when (.isClosed channel)
            (report-close! 1006 ""))))
      (.sendHandshake channel {"Upgrade" "websocket"
                               "Connection" "Upgrade"
                               "Sec-WebSocket-Accept" key
                               "Sec-WebSocket-Protocol" subprotocol})
      ;; An earlier close is ours to send. A later close sees :open? itself.
      (let [[before _] (swap-vals! lifecycle assoc :open? true)]
        (when-let [[code reason] (:close before)]
          (.serverClose channel (int code) reason)))
      {:body channel})
    {:status 400 :headers {"content-type" "text/plain"}
     :body "Bad Sec-Websocket-Key header"}))


(defn listen!
  "Bind an http-kit WebSocket listener for one composed endpoint.

   `:codecs` is the endpoint's codec profile table (required).
   An upgrade offering any table subprotocol completes with the negotiated
   one; an upgrade offering none is refused before it completes — a
   mixed-version peer fails its handshake rather than being downgraded.

   `:ws/max-frame-bytes`, when given, bounds http-kit's own message
   reassembly (its `:max-ws`), so an oversize message is refused before it
   is assembled."
  [{:keys [bind-host bind-port accept! deposit! codecs] :as options}]
  (when-not (seq codecs)
    (throw (ex-info "WebSocket listener needs :codecs" {})))
  (let [clock #(System/currentTimeMillis)
        codecs (seq codecs)
        handler (fn [request]
                  (let [negotiated (negotiate codecs (offered-subprotocols request))]
                    (cond
                      (and (:websocket? request) negotiated)
                      (upgrade! request accept! clock deposit! negotiated)

                      (:websocket? request)
                      (do
                        (deposit! :upgrade-failed
                                  {:code :yin.repl.endpoint/subprotocol-required
                                   :message "the WebSocket upgrade offered no supported subprotocol"})
                        {:status 400 :headers {"content-type" "text/plain"}
                         :body "dao.stream/subprotocol-required"})

                      :else
                      {:status 400 :headers {"content-type" "text/plain"}
                       :body "dao.stream/websocket-required"})))
        server (http/run-server handler {:ip bind-host
                                         :port bind-port
                                         :legacy-return-value? false
                                         :max-ws (or (:ws/max-frame-bytes options)
                                                     http-kit-default-max-ws)
                                         :error-logger
                                         (fn [_message _error]
                                           (deposit! :listener-error
                                                     {:code :yin.repl.endpoint/listener-error
                                                      :message "the host listener reported an error"}))})]
    (deposit! :bind-succeeded {:host bind-host :port (http/server-port server)})
    {:dao.stream/outcome :dao.stream/ok
     :ws.jvm/server server
     :options options}))


(defn stop-listening!
  "Initiate listener shutdown; its completion is reported through `on-closed`."
  [listener on-closed]
  (try
    (let [server (:ws.jvm/server listener)
          stopped? (.stop ^org.httpkit.server.HttpServer server 100
                          ^Runnable (reify Runnable
                                      (run [_] (on-closed))))]
      (if stopped?
        {:dao.stream/outcome :dao.stream/ok}
        {:dao.stream/outcome :dao.stream/transport-error}))
    (catch Throwable _
      {:dao.stream/outcome :dao.stream/transport-error})))
