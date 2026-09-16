(ns dao.stream.v2.ws.browser
  "The browser host adapter for the DaoStream v2 WebSocket transport.

   This namespace is the host edge and nothing else.  It owns the DOM
   `WebSocket` for exactly the distance between a browser socket event and a
   `dao.stream.v2.ws` adapter entry point, and exposes only `connect!` -- the
   `:connect!` seam of `dao.stream.v2.ws/make-attacher`.  A browser cannot
   accept inbound connections, so there is no `listen!` here: this namespace
   satisfies `yin.repl.v2.host.common/adapter?` and never `binder?`, which is
   exactly the distinction `host.common` draws.

   Mirrors `dao.stream.v2.ws.node`'s `connect!`/`wire!`/`raw-socket` shape,
   substituting DOM event names (`addEventListener` and `message`/`close`
   events carrying a `MessageEvent`/`CloseEvent`) for `ws`'s `EventEmitter`
   surface.

   Host matrix declaration (per `dao.stream.ws.md`): the DOM `WebSocket.send`
   accepts into an opaque host buffer and exposes no outbound high-water
   signal, so transient `:dao.stream/full` is excluded by nature on this host,
   exactly as on Node.  A send returns `undefined`, which the transport
   classifies as ok; while the attachment is establishing, the transport's own
   phase gate answers `full` without touching the socket."
  (:require [dao.stream.v2.ws :as ws]))


(def binary-message-text
  "Routed through `:message!` in place of a binary frame's bytes.  Mirrors
   `dao.stream.v2.ws.node/binary-message-text`: a NUL byte is not valid
   Transit JSON in any position, so the transport performs its own
   decode-failure teardown (deposit plus close 4002) without this adapter
   inspecting frame content."
  (.fromCharCode js/String 0))


(defn socket-url
  "The reachability URL for one WebSocket descriptor."
  [descriptor]
  (str "ws://" (:ws/host descriptor) ":" (:ws/port descriptor) (:ws/path descriptor)))


(defn wire!
  "Register the adapter callbacks as the sole subscriber of one host socket.

   The socket's `message`, `close`, and `error` events become adapter entries;
   the DOM `open` event is deliberately not subscribed because an HTTP upgrade
   is never a resolution -- the first `:ws/accept` or `:ws/disclaim` frame is.
   A `MessageEvent`'s `data` is the frame's string for a text frame; anything
   else (an `ArrayBuffer` or `Blob`, depending on the socket's `binaryType`) is
   routed as `binary-message-text` without this adapter reading it, which is
   what keeps the classification synchronous.  The host `error` event carries
   no usable data and never crosses: the adapter deposits nothing itself, the
   transport's diagnostic entry does."
  [socket adapter]
  (.addEventListener ^js socket "message"
                     (fn [event]
                       (let [data (.-data ^js event)]
                         ((:message! adapter)
                          (if (string? data) data binary-message-text)))))
  (.addEventListener ^js socket "close"
                     (fn [event]
                       ((:closed! adapter) (.-code ^js event) (.-reason ^js event))))
  (.addEventListener ^js socket "error"
                     (fn [_event] ((:error! adapter))))
  socket)


(defn raw-socket
  "The `{:send! :close!}` view of one host socket, which is the only shape
   `dao.stream.v2.ws` accepts from a host."
  [socket]
  {:send! (fn [text] (.send ^js socket text))
   :close! (fn [code reason] (.close ^js socket code reason))})


(defn connect!
  "The `:connect!` host seam for `dao.stream.v2.ws/make-attacher` in the
   browser.

   Starts connection establishment to the descriptor's URL with the v2
   subprotocol and synchronously returns the raw socket view; the connection
   resolves asynchronously and every socket event reaches the adapter map the
   transport built.  The deposit medium and its already-minted cursor are the
   caller's composition, not arguments here."
  [descriptor adapter]
  (let [socket (js/WebSocket. (socket-url descriptor) ws/subprotocol)]
    (wire! socket adapter)
    (raw-socket socket)))
