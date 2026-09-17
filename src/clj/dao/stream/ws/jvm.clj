(ns dao.stream.ws.jvm
  "The JVM host edge for the DaoStream v2 WebSocket transport.

   The Java HTTP client and http-kit own their callbacks only until those
   callbacks invoke the plain adapter entries supplied by `dao.stream.ws`.
   This namespace retains no application state and never drives an endpoint.

   The JVM WebSocket APIs expose no useful outbound high-water signal.  A send
   accepted by the host is therefore `:dao.stream/ok`; transient
   `:dao.stream/full` is excluded by nature on this host.  Acceptance is not
   delivery: `send!` returns as soon as the host has taken the message, and a
   send that fails afterwards is reported on the stream as `:ws/error` then a
   terminal `:ws/closed`.  Nothing here joins, parks, or sleeps — the operation
   must return what is true when it is called."
  (:require [clojure.string :as str]
            [dao.stream.ws :as ws]
            [org.httpkit.server :as http]
            [ring.websocket.protocols :as wsp])
  (:import [java.net URI]
           [java.net.http HttpClient WebSocket WebSocket$Listener]
           [java.nio ByteBuffer]
           [java.util.concurrent CompletableFuture]))


(def binary-message-text
  "A text value guaranteed to fail Transit JSON decoding."
  "\u0000")


(def ^:private unreserved?
  (set (concat (map char (range (int \0) (inc (int \9))))
               (map char (range (int \A) (inc (int \Z))))
               (map char (range (int \a) (inc (int \z))))
               [\- \. \_ \~])))


(defn- normalize-percent-escapes
  [s]
  (let [n (count s)]
    (loop [i 0, out (StringBuilder.)]
      (if (>= i n)
        (str out)
        (if-not (= \% (.charAt ^String s i))
          (recur (inc i) (.append out (.charAt ^String s i)))
          (if (> (+ i 3) n)
            nil
            (let [pair (subs s (inc i) (+ i 3))]
              (if-not (re-matches #"[0-9A-Fa-f]{2}" pair)
                nil
                (let [decoded (char (Integer/parseInt pair 16))]
                  (recur (+ i 3)
                         (.append out
                                  (if (contains? unreserved? decoded)
                                    (str decoded)
                                    (str "%" (str/upper-case pair))))))))))))))


(defn- remove-dot-segments
  [segments]
  (let [kept (reduce (fn [xs segment]
                       (cond
                         (= "." segment) xs
                         (= ".." segment) (if (seq xs) (pop xs) xs)
                         :else (conj xs segment)))
                     [] segments)]
    (if (contains? #{"." ".."} (last segments))
      (conj kept "")
      kept)))


(defn canonical-path
  "Canonicalize an HTTP request target for exact served-path lookup."
  [target]
  (when (string? target)
    (let [without-fragment (first (str/split target #"#" 2))
          without-query (first (str/split without-fragment #"\?" 2))
          decoded (normalize-percent-escapes without-query)]
      (when (some? decoded)
        (cond
          (empty? decoded) "/"
          (str/starts-with? decoded "/")
          (str "/" (str/join "/" (remove-dot-segments
                                   (str/split (subs decoded 1) #"/" -1))))
          :else nil)))))


(defn socket-url
  [descriptor]
  (str "ws://" (:ws/host descriptor) ":" (:ws/port descriptor)
       (:ws/path descriptor)))


(defn- completed
  []
  (CompletableFuture/completedFuture nil))


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
   further chaining."
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
          (chain!
            [f]
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
                             (swap! connection assoc :pending next)
                             next)))]
              (if next
                (do (.whenComplete
                      ^CompletableFuture next
                      (reify java.util.function.BiConsumer
                        (accept [_ _v error] (when error (fail!)))))
                    nil)
                ::failed)))]
    {:send! (fn [message]
              (if-let [socket (:socket @connection)]
                (let [result (chain! #(.sendText ^WebSocket socket message true))]
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
                 (do (chain! #(.sendClose ^WebSocket socket code reason)) nil)
                 (do (swap! connection assoc :close-request [code reason]) nil)))}))


(defn connect!
  "Start an asynchronous JVM client attachment and return its raw socket seam."
  [descriptor adapter]
  (let [connection (atom {:socket nil :future nil :close-request nil
                          :pending nil :failed? false})
        text (StringBuilder.)
        listener
        (reify WebSocket$Listener
          (onOpen
            [_ socket]
            (swap! connection assoc :socket socket)
            (when-let [[code reason] (:close-request @connection)]
              (.sendClose ^WebSocket socket code reason))
            (.request ^WebSocket socket 1))

          (onText
            [_ socket data last?]
            (.append text ^CharSequence data)
            (when last?
              (let [message (str text)]
                (.setLength text 0)
                ((:message! adapter) message)))
            (.request ^WebSocket socket 1)
            (completed))

          (onBinary
            [_ socket _data last?]
            ;; Java may fragment one binary message across callbacks.  The
            ;; transport must see one rejected message, not one per fragment.
            (when last?
              ((:message! adapter) binary-message-text))
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
                    (.subprotocols ws/subprotocol (make-array String 0)))
        future (.buildAsync builder (URI/create (socket-url descriptor)) listener)]
    (swap! connection assoc :future future)
    (.whenComplete future
                   (reify java.util.function.BiConsumer
                     (accept
                       [_ _socket error]
                       (when error
                         ((:closed! adapter) 1006 "dao.stream/connect-failed")))))
    (client-socket connection adapter)))


(defn- offered-subprotocol?
  [request]
  (boolean
    (some #(= ws/subprotocol (str/trim %))
          (str/split (str (get-in request [:headers "sec-websocket-protocol"])) #","))))


(defn- server-socket
  [socket]
  {:send! (fn [message] (wsp/-send socket message))
   :close! (fn [code reason] (wsp/-close socket code reason))})


(defn- listener
  [request accept! clock deposit!]
  (let [adapter (atom nil)]
    (reify wsp/Listener
      (on-open
        [_ socket]
        (try
          (let [target (or (canonical-path (:uri request)) (:uri request))
                result (accept! target (server-socket socket) (clock))]
            (when-let [handle (:ws/handle result)]
              (reset! adapter (ws/adapter handle))))
          (catch Throwable _
            (deposit! :upgrade-failed
                      {:code :yin.repl.endpoint/upgrade-threw
                       :message "the host failed while accepting an upgrade"})
            (wsp/-close socket 1011 "dao.stream/upgrade-failed"))))

      (on-message
        [_ _socket message]
        (when-let [a @adapter]
          ((:message! a) (if (string? message) message binary-message-text))))

      (on-pong [_ _socket _data] nil)

      (on-error
        [_ _socket _error]
        (if-let [a @adapter]
          ((:error! a))
          (deposit! :upgrade-failed
                    {:code :yin.repl.endpoint/socket-error
                     :message "the upgraded socket failed before acceptance"})))

      (on-close
        [_ _socket code reason]
        (when-let [a @adapter]
          ((:closed! a) code reason))))))


(defn listen!
  "Bind an http-kit WebSocket listener for one composed endpoint."
  [{:keys [bind-host bind-port accept! deposit!] :as options}]
  (let [clock #(System/currentTimeMillis)
        handler (fn [request]
                  (cond
                    (and (:websocket? request) (offered-subprotocol? request))
                    {:ring.websocket/listener
                     (listener request accept! clock deposit!)
                     :ring.websocket/protocol ws/subprotocol}

                    (:websocket? request)
                    (do
                      (deposit! :upgrade-failed
                                {:code :yin.repl.endpoint/subprotocol-required
                                 :message "the WebSocket upgrade omitted the v2 subprotocol"})
                      {:status 400 :headers {"content-type" "text/plain"}
                       :body "dao.stream/subprotocol-required"})

                    :else
                    {:status 400 :headers {"content-type" "text/plain"}
                     :body "dao.stream/websocket-required"}))
        server (http/run-server handler {:ip bind-host
                                         :port bind-port
                                         :legacy-return-value? false
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
