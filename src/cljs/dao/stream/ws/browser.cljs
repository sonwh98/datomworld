(ns dao.stream.ws.browser
  "The browser host adapter for the DaoStream v2 WebSocket transport.

   This namespace is the host edge and nothing else.  It owns the DOM
   `WebSocket` for exactly the distance between a browser socket event and a
   `dao.stream.ws` adapter entry point, and exposes only `connect!` -- the
   `:connect!` seam of `dao.stream.ws/make-attacher`.  A browser cannot
   accept inbound connections, so there is no `listen!` here: this namespace
   satisfies `yin.repl.host.common/adapter?` and never `binder?`, which is
   exactly the distinction `host.common` draws.

   Mirrors `dao.stream.ws.node`'s `connect!`/`wire!`/`raw-socket` shape,
   substituting DOM event names (`addEventListener` and `message`/`close`
   events carrying a `MessageEvent`/`CloseEvent`) for `ws`'s `EventEmitter`
   surface.

   Frames are typed end to end: `binaryType` is pinned to `arraybuffer`, a
   text frame's string reaches `:message!`, a binary frame's bytes reach
   `:binary!` as an `ArrayBuffer` view, and this adapter never inspects
   payload content to classify either.

   Host matrix declaration (per `dao.stream.ws.md`): the DOM `WebSocket.send`
   accepts into an opaque host buffer and exposes no outbound high-water
   signal, so transient `:dao.stream/full` is excluded by nature on this host,
   exactly as on Node.  A send returns `undefined`, which the transport
   classifies as ok; while the attachment is establishing, the transport's own
   phase gate answers `full` without touching the socket."
  (:require [dao.stream.ws :as ws]))


(defn socket-url
  "The reachability URL for one WebSocket descriptor."
  [descriptor]
  (str "ws://" (:ws/host descriptor) ":" (:ws/port descriptor) (:ws/path descriptor)))


(defn wire!
  "Register the adapter callbacks as the sole subscriber of one host socket.

   The socket's `message`, `close`, and `error` events become adapter entries;
   the DOM `open` event is deliberately not subscribed because an HTTP upgrade
   is never a resolution -- the first `:ws/accept` or `:ws/disclaim` frame is.
   A `MessageEvent`'s `data` is the frame's string for a text frame and
   reaches `:message!`; anything else is an `ArrayBuffer` (`binaryType` is
   pinned in `connect!`) whose bytes reach `:binary!` as a `Uint8Array` view.
   The host `error` event carries no usable data and never crosses: the
   adapter deposits nothing itself, the transport's diagnostic entry does."
  [socket adapter]
  (.addEventListener ^js socket "message"
                     (fn [event]
                       (let [data (.-data ^js event)]
                         (if (string? data)
                           ((:message! adapter) data)
                           ((:binary! adapter) (js/Uint8Array. data))))))
  (.addEventListener ^js socket "close"
                     (fn [event]
                       ((:closed! adapter) (.-code ^js event) (.-reason ^js event))))
  (.addEventListener ^js socket "error"
                     (fn [_event] ((:error! adapter))))
  socket)


(defn raw-socket
  "The `{:send! :close!}` view of one host socket, which is the only shape
   `dao.stream.ws` accepts from a host.  `send!` takes the attachment codec's
   payload and lets the host dispatch on its type — a String sends a text
   frame, a typed array a binary frame."
  [socket]
  {:send! (fn [payload] (.send ^js socket payload))
   :close! (fn [code reason] (.close ^js socket code reason))})


(defn connect!
  "The `:connect!` host seam for `dao.stream.ws/make-attacher` in the
   browser.

   Starts connection establishment to the descriptor's URL offering exactly
   the subprotocol of the transport's selected codec profile (the adapter
   map's `:ws/codec`) — a peer that does not speak it fails the handshake —
   and synchronously returns the raw socket view; the connection resolves
   asynchronously and every socket event reaches the adapter map the
   transport built.  The deposit medium and its already-minted cursor are the
   caller's composition, not arguments here."
  [descriptor adapter]
  (let [socket (js/WebSocket. (socket-url descriptor)
                              (get-in adapter [:ws/codec :ws/subprotocol] ws/subprotocol))]
    (set! (.-binaryType ^js socket) "arraybuffer")
    (wire! socket adapter)
    (raw-socket socket)))
