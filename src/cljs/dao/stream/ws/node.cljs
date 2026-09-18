(ns dao.stream.ws.node
  "The Node host adapter for the DaoStream v2 WebSocket transport.

   This namespace is the host edge and nothing else.  It requires the `ws` npm
   package (see package.json), owns raw host sockets for exactly the distance
   between a Node event and a `dao.stream.ws` adapter entry point, and
   exposes:

   * `connect!` -- the `:connect!` seam of `dao.stream.ws/make-attacher`;
   * `listen!` / `stop-listening!` -- bind a `ws` server hosting one composed
     endpoint, feeding `accept-connection!` on every conforming upgrade; and
   * `canonical-path`, `socket-url`, `wire!`, and `raw-socket` -- the pieces a
     host composition or its tests reuse directly.

   Everything else stays explicit caller state: deposit media and their
   cursors (mint the `:dao.stream/newest` cursor before calling the composed
   `attach!`), readers, endpoint state, RPC state, cadence, and retry policy.
   This adapter creates no atom, cursor, registry, promise, future, or timer,
   and never schedules `endpoint-step` -- the composition driver owns every
   tick.

   Frames are typed end to end: a text frame's UTF-8 string reaches
   `:message!`, a binary frame's bytes reach `:binary!`, and this adapter
   never inspects payload content to classify either.

   Host matrix declaration (per `dao.stream.ws.md`): Node's `ws` `send`
   accepts into an opaque host buffer and exposes no outbound high-water
   signal, so transient `:dao.stream/full` is excluded by nature on this host.
   A send returns nil, which the transport classifies as ok; while the
   attachment is establishing, the transport's own phase gate answers `full`
   without touching the socket."
  (:require ["ws" :as ws-package]
            [clojure.string :as str]
            [dao.stream.transit :as transit]
            [dao.stream.ws :as ws]))


(def subprotocol-refusal-code 1002)


(def default-codecs
  "The listener's codec table when a composition names none: Transit only."
  [transit/profile])


(defn offered-subprotocols
  "The upgrade request's offered subprotocol list, split and trimmed."
  [request]
  (->> (str/split (str (some-> ^js request .-headers (aget "sec-websocket-protocol"))) #",")
       (map str/trim)
       (remove str/blank?)))


(defn negotiate
  "Pick the first endpoint codec (in endpoint order) the upgrade offered, or
   nil when the offer shares no subprotocol with the endpoint — the
   deterministic dual-subprotocol rule.  A mismatch is a failed handshake,
   not a downgrade."
  [codecs offered]
  (some (fn [codec]
          (when (some #(= (:ws/subprotocol codec) %) offered)
            codec))
        codecs))


(def ^:private unreserved
  "The URI unreserved characters whose percent-escapes decode."
  (set (concat (map js/String.fromCharCode (concat (range 48 58)
                                                   (range 65 91)
                                                   (range 97 123)))
               ["-" "." "_" "~"])))


(defn- normalize-percent-escapes
  "Decode percent-escapes of unreserved octets and upper-case the hex digits
   of every remaining escape.  Returns nil for a malformed escape sequence."
  [s]
  (let [n (count s)
        hex-pair? (fn [pair] (boolean (and pair (re-matches #"[0-9A-Fa-f]{2}" pair))))]
    (loop [i 0
           out []]
      (if (>= i n)
        (apply str out)
        (if-not (= \% (nth s i))
          (recur (inc i) (conj out (nth s i)))
          (let [pair (when (<= (+ i 3) n) (subs s (inc i) (+ i 3)))]
            (if-not (hex-pair? pair)
              nil
              (let [decoded (js/String.fromCharCode (js/parseInt pair 16))]
                (if (contains? unreserved decoded)
                  (recur (+ i 3) (conj out decoded))
                  (recur (+ i 3) (conj out "%" (str/upper-case pair))))))))))))


(defn- remove-dot-segments
  "RFC 3986's remove_dot_segments over already-split segments.  A final `.` or
   `..` leaves the trailing slash RFC 3986 keeps — `/a/b/..` is `/a/`, not `/a`
   — which matters because a trailing slash is significant in the served-path
   lookup this feeds."
  [segments]
  (let [kept (reduce (fn [kept segment]
                       (cond
                         (= "." segment) kept
                         (= ".." segment) (if (seq kept) (pop kept) kept)
                         :else (conj kept segment)))
                     []
                     segments)]
    (if (contains? #{"." ".."} (last segments))
      (conj kept "")
      kept)))


(defn canonical-path
  "The canonical request-target form of `dao.stream.ws.md`: query and fragment
   components are ignored, dot segments are removed, percent-encoded
   unreserved octets decode, remaining escapes upper-case their hex digits, an
   empty path becomes the root, encoded slashes stay encoded, and repeated or
   trailing slashes are significant.  Returns nil for anything that cannot
   reach that form: not a string, not absolute, or a malformed escape."
  [target]
  (when (string? target)
    (let [without-fragment (first (str/split target #"#"))
          without-query (first (str/split without-fragment #"\?"))
          decoded (normalize-percent-escapes without-query)]
      (when (some? decoded)
        (cond
          ;; An empty URI path is presented as the root.
          (= "" decoded) "/"
          (str/starts-with? decoded "/")
          (str "/" (str/join "/" (remove-dot-segments
                                   ;; A trailing empty segment is a
                                   ;; significant trailing slash.
                                   (str/split (subs decoded 1) #"/" -1))))
          :else nil)))))


(defn socket-url
  "The reachability URL for one WebSocket descriptor.  A descriptor carries
   only reachability and identity; where inbound traffic lands is the host
   boundary's composition and appears in neither the URL nor this function."
  [descriptor]
  (str "ws://" (:ws/host descriptor) ":" (:ws/port descriptor) (:ws/path descriptor)))


(defn wire!
  "Register the adapter callbacks as the sole subscriber of one host socket.

   The socket's 'message', 'close', and 'error' events become adapter entries;
   the 'open' event is deliberately not subscribed because an HTTP upgrade is
   never a resolution -- the first `:ws/accept` or `:ws/disclaim` frame is.
   Text arrives as the frame's UTF-8 string through `:message!`; a binary
   frame's bytes reach `:binary!` untouched.  The host error object never
   crosses: the adapter deposits nothing itself, the transport's diagnostic
   entry does."
  [socket adapter]
  (.on ^js socket "message"
       (fn [data is-binary]
         (if is-binary
           ((:binary! adapter) data)
           ((:message! adapter) (.toString ^js data "utf8")))))
  (.on ^js socket "close"
       (fn [code _reason] ((:closed! adapter) code _reason)))
  (.on ^js socket "error"
       (fn [_error] ((:error! adapter))))
  socket)


(defn raw-socket
  "The `{:send! :close!}` view of one host socket, which is the only shape
   `dao.stream.ws` accepts from a host.  `send!` takes the attachment codec's
   payload and lets the host library dispatch on its type — a String sends a
   text frame, a Buffer or typed array a binary frame.  `send!` returns the
   host library's answer (nil on Node, classified as ok); `close!` asks the
   host for a closing handshake and never waits for its completion."
  ([socket] (raw-socket socket nil))
  ([socket subprotocol]
   {:send! (fn [payload] (.send ^js socket payload))
    :close! (fn [code reason] (.close ^js socket code reason))
    :ws/subprotocol subprotocol}))


(defn connect!
  "The `:connect!` host seam for `dao.stream.ws/make-attacher` on Node.

   Starts connection establishment to the descriptor's URL offering exactly
   the subprotocol of the transport's selected codec profile (the adapter
   map's `:ws/codec`) — a peer that does not speak it fails the handshake —
   and synchronously returns the raw socket view; the connection resolves
   asynchronously and every socket event reaches the adapter map the
   transport built.  The deposit medium and its already-minted cursor are the
   caller's composition, not arguments here: attach! deposits nothing before
   resolution and no event can outrun a cursor minted first."
  [descriptor adapter]
  (let [socket (new (.-WebSocket ws-package)
                    (socket-url descriptor)
                    (get-in adapter [:ws/codec :ws/subprotocol] ws/subprotocol))]
    (wire! socket adapter)
    (raw-socket socket)))


(defn listen!
  "Bind a Node `ws` server hosting one composed endpoint.

   `options` are `:host` (default a loopback bind), `:port` (default 0 for an
   assigned port), `:clock` (a no-argument host clock reading, default
   `js/Date.now`), `:codecs` (the endpoint's codec profile table, default
   Transit only), `:accept!` (`(fn [path socket now] …)`, default this
   endpoint's `ws/accept-connection!`, so a serving composition can inject the
   same seam it owns), `:on-listening` (called with the bound address map once
   the server reports listening) and `:on-error` (the sole observer of the
   server's 'error' event; the default no-op only prevents the host process
   from dying on an unhandled EventEmitter error).

   Every upgrade that offers any table subprotocol is negotiated
   deterministically — the first codec in table order that the client offered
   — and presented to `accept!` with the canonical request-target path, a
   host clock reading, and the negotiated subprotocol on the socket seam.  An
   upgrade offering no supported subprotocol is refused before the upgrade
   completes (HTTP 400) and never reaches the endpoint, its handoff slots, or
   the disclaimer: a mixed-version peer fails its handshake rather than being
   downgraded.

   Binding is asynchronous on Node: `:on-listening` reports it, and
   `listener-address`/`listener-port` stay nil until then; the R4 lifecycle
   medium is the durable home for bind and listener failures.  The listener
   never schedules the endpoint and retains no connection state: the driver owns
   `endpoint-step` cadence, and the returned listener is plain host data."
  ([endpoint] (listen! endpoint {}))
  ([endpoint {:keys [host port clock codecs accept! on-listening on-error]
              :or {host "127.0.0.1" port 0}}]
   (let [clock (or clock #(js/Date.now))
         codecs (vec (or (seq codecs) default-codecs))
         supported (set (map :ws/subprotocol codecs))
         accept! (or accept!
                     (fn [path socket now]
                       (ws/accept-connection! endpoint path socket now)))
         server (new (.-WebSocketServer ws-package)
                     #js {:host host
                          :port port
                          ;; The subprotocol is wire protocol, so its
                          ;; selection belongs to the endpoint's host edge:
                          ;; an unsupported offer means the upgrade is not a
                          ;; stream this endpoint speaks, and the spec refuses
                          ;; the upgrade rather than completing it and closing.
                          ;; `ws` selects the asynchronous form of this hook by
                          ;; `Function.length === 2`, so this must compile to a
                          ;; generated function of exactly two declared
                          ;; arguments: a variadic or multi-arity one reports
                          ;; length 0 and silently selects the synchronous form,
                          ;; whose refusal is a hardcoded 401 and whose return
                          ;; value here would be ignored.  `verifyClient` is
                          ;; deprecated in `ws` 8; the replacement is an
                          ;; explicit `upgrade` handler, at the next major.
                          :verifyClient (fn [info cb]
                                          (if (negotiate codecs (offered-subprotocols (.-req ^js info)))
                                            (cb true)
                                            (cb false 400
                                                "dao.stream/subprotocol-required")))
                          :handleProtocols (fn [protocols _request]
                                             (:ws/subprotocol
                                               (negotiate codecs
                                                          (array-seq (js/Array.from protocols)))))})]
     (.on ^js server "error" (fn [_error] (when (fn? on-error) (on-error))))
     (.on ^js server "listening"
          (fn [] (when (fn? on-listening) (on-listening (.address ^js server)))))
     (.on ^js server "connection"
          (fn [socket request]
            ;; Defence in depth behind `verifyClient`: a socket that reached
            ;; this event without a negotiated table subprotocol is not a
            ;; stream this endpoint speaks.
            (if (not (contains? supported (.-protocol ^js socket)))
              (.close ^js socket subprotocol-refusal-code
                      "dao.stream/subprotocol-required")
              (let [raw (raw-socket socket (.-protocol ^js socket))
                    ;; Lookup is exact on the canonical form; an
                    ;; uncanonicalizable target can match no canonical table
                    ;; entry, so the raw target reaches the same disclaimer.
                    path (or (canonical-path (.-url ^js request))
                             (.-url ^js request))
                    accepted (accept! path raw (clock))]
                ;; Only a pending acceptance owns a handle to wire; a
                ;; disclaimer, exhausted pool, or failed offer has already
                ;; been answered by the transport itself.
                (when-let [handle (:ws/handle accepted)]
                  (wire! socket (ws/adapter handle)))))))
     {:dao.stream/outcome :dao.stream/ok
      :ws.node/server server})))


(defn listener-address
  "The Node address map once the server reports listening, else nil."
  [listener]
  (try
    (.address ^js (:ws.node/server listener))
    (catch :default _ nil)))


(defn listener-port
  "The bound port once listening (including the assigned port after a bind to
   zero), else nil."
  [listener]
  (some-> (listener-address listener) (.-port)))


(defn stop-listening!
  "Close the host server.  This initiates the close and reports no completion
   itself: established attachments close through their own handles and owners,
   and the Node server waits for them per its own semantics.  `on-closed`, when
   given, is the host's close completion — the only honest source of the R4
   `:stopped` fact — and is called with no arguments."
  ([listener] (stop-listening! listener nil))
  ([listener on-closed]
   (try
     (if (fn? on-closed)
       (.close ^js (:ws.node/server listener) (fn [] (on-closed)))
       (.close ^js (:ws.node/server listener)))
     {:dao.stream/outcome :dao.stream/ok}
     (catch :default _
       {:dao.stream/outcome :dao.stream/transport-error}))))
