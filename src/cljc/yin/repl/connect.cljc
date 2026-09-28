(ns yin.repl.connect
  "The client side of the DaoStream Yin REPL, over the request-and-response
   service of docs/design/dao.stream.remote.md section 5.

   One connection is one explicit value.  `open` canonicalizes the URL to the
   request-target form `dao.stream.ws.md` defines, dials one WebSocket
   channel through `dao.stream.ws-project`, and attaches two
   `dao.stream.remote` reflections over it -- one of `\"yin.repl/requests\"`
   (this end writes), one of `\"yin.repl/answers\"` (this end reads) -- both
   sharing the one link the dial establishes.  `attach!` over
   `dao.stream.remote` answers ok at once: that is the contract's deferred
   remote confirmation, not a promise that the server has resolved anything,
   so a connection is reported `:established` immediately rather than
   waiting on an asynchronous transport event.

   The dial is reusable across reconnects: `close!` closes the underlying
   channel handle, and reattaching redials the same `attach!` and repeats
   both reflection attaches, then `rpc/rebind`s the retained RPC client onto
   the fresh pair -- which keeps its response cursor and its outstanding
   bookkeeping, exactly as before.  Terminal status
   (`:detached`/`:ended`/`:not-found`/`:transport-error`) is observed from
   the RPC client's own `:terminal`, translated by `dao.stream.rpc`'s
   reflection-read translation; `observe-terminal` turns that into the one
   connection notice each terminal reason publishes exactly once."
  (:require [clojure.string :as str]
            [dao.data :as data]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.rpc :as rpc]
            [dao.stream.ws :as ws]
            [dao.stream.ws-project :as ws-project]
            [yin.repl.host.common :as host-common]))


;; =============================================================================
;; Composition constants
;; =============================================================================

(def traffic-capacity
  "Declared capacity of the boundary's one traffic medium, in elements.  It is
   reused across reconnects because a client boundary has at most one active
   attachment at a time."
  8192)


(def traffic-admission
  {:retention :evict-oldest
   :capacity traffic-capacity
   :value-domain :portable-values})


(def url-prefix "daostream:")
(def ws-scheme "ws://")
(def secure-scheme "wss://")
(def default-port 8080)


(def repl-path
  "The path an empty URL path means for this REPL (D3).  An explicit `/`
   remains `/`; only an absent path becomes the served REPL stream."
  "/repl")


(def service-identity
  "The logical identity of the WebSocket channel a REPL endpoint serves.  A
   descriptor names a served stream, so a client that only typed a URL still
   needs one; a stable service name keeps `attach!` honest across endpoint
   restarts."
  "yin.repl/repl")


(def requests-identity
  "The mirror table identity the server enters with surface #{:writer}: a
   client's reflection of it writes eval requests."
  "yin.repl/requests")


(def answers-identity
  "The mirror table identity the server enters with surface #{:reader}: a
   client's reflection of it reads answers."
  "yin.repl/answers")


(def ^:private diagnostic-bounds
  "Bound on operator-facing diagnostic text built from unbounded internal
   state (raw connect URLs)."
  {:depth 3 :items 8 :chars 200})


(def outcome-key :yin.repl.connect/outcome)
(def connection-key :yin.repl.connect/connection)
(def client-key :yin.repl.connect/client)
(def descriptor-key :yin.repl.connect/descriptor)
(def message-key :yin.repl.connect/message)
(def event-key :yin.repl.connect/event)
(def text-key :yin.repl.connect/text)


;; =============================================================================
;; Canonical request-target paths
;; =============================================================================

(def ^:private ascii-printable
  " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~")


(def ^:private unreserved
  (set (map str (seq "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"))))


(def ^:private hex-values
  (merge (zipmap (map str (seq "0123456789")) (range 10))
         (zipmap (map str (seq "abcdef")) (range 10 16))
         (zipmap (map str (seq "ABCDEF")) (range 10 16))))


(def ^:private digits (set (map str (seq "0123456789"))))


(defn- at
  "One character of `s` as a one-character string, so nothing here depends on a
   host's character representation."
  [s i]
  (subs s i (inc i)))


(defn- octet->char
  [code]
  (when (and (<= 32 code) (<= code 126))
    (subs ascii-printable (- code 32) (- code 31))))


(defn- percent-canonical
  "Decode percent-encoded unreserved octets and upper-case every remaining
   percent hex pair.  An encoded slash is not unreserved, so it stays encoded."
  [s]
  (let [n (count s)]
    (loop [i 0
           out []]
      (if (>= i n)
        (str/join out)
        (let [c (at s i)
              hi (when (< (+ i 2) n) (get hex-values (at s (+ i 1))))
              lo (when (< (+ i 2) n) (get hex-values (at s (+ i 2))))]
          (if (and (= "%" c) hi lo)
            (let [decoded (octet->char (+ (* 16 hi) lo))]
              (if (contains? unreserved decoded)
                (recur (+ i 3) (conj out decoded))
                (recur (+ i 3) (conj out (str "%" (str/upper-case (subs s (inc i) (+ i 3))))))))
            (recur (inc i) (conj out c))))))))


(defn- drop-last-segment
  [out]
  (if-let [i (str/last-index-of out "/")]
    (subs out 0 i)
    ""))


(defn- next-segment-end
  [s]
  (or (str/index-of s "/" 1) (count s)))


(defn- remove-dot-segments
  "RFC 3986's remove_dot_segments, so repeated slashes and a trailing slash stay
   significant while `.` and `..` do not reach the exact served-path lookup."
  [path]
  (loop [in path
         out ""]
    (cond
      (= "" in) out
      (str/starts-with? in "../") (recur (subs in 3) out)
      (str/starts-with? in "./") (recur (subs in 2) out)
      (str/starts-with? in "/./") (recur (str "/" (subs in 3)) out)
      (= "/." in) (recur "/" out)
      (str/starts-with? in "/../") (recur (str "/" (subs in 4)) (drop-last-segment out))
      (= "/.." in) (recur "/" (drop-last-segment out))
      (= "." in) (recur "" out)
      (= ".." in) (recur "" out)
      :else (let [end (next-segment-end in)]
              (recur (subs in end) (str out (subs in 0 end)))))))


(defn- path-component
  "The URI path component of `raw`: everything before the first query or
   fragment delimiter."
  [raw]
  (let [raw (str raw)
        cut (->> [(str/index-of raw "?") (str/index-of raw "#")]
                 (filter some?)
                 (reduce min (count raw)))]
    (subs raw 0 cut)))


(defn canonical-path
  "The canonical, non-empty absolute request-target path of `raw`.

   Query and fragment are ignored for lookup and absent from the descriptor,
   dot segments are removed, percent-encoded unreserved octets are decoded, and
   remaining percent hex digits are upper-case.  An empty URI path becomes `/`;
   substituting the REPL's own default for an absent path is `repl-target`'s
   job, and happens before this generic canonicalizer runs."
  [raw]
  (let [path (path-component raw)
        path (if (str/starts-with? path "/") path (str "/" path))
        path (percent-canonical (remove-dot-segments path))]
    (if (= "" path) "/" path)))


(defn repl-target
  "The REPL wrapper's rule: an empty raw URL path names `/repl`, and an explicit
   `/` remains `/`.  Emptiness is a fact about the URI path component, so
   `ws://host:port?x=1` carries no path and names `/repl` too."
  [raw]
  (if (= "" (path-component raw))
    repl-path
    (canonical-path raw)))


;; =============================================================================
;; URL to descriptor
;; =============================================================================

(defn- failure
  [outcome message]
  {outcome-key outcome message-key message})


(defn- parse-port
  [text]
  (when (and (seq text) (every? #(contains? digits %) (map str (seq text))))
    (loop [i 0 acc 0]
      (if (>= i (count text))
        (when (pos? acc) acc)
        (recur (inc i) (+ (* 10 acc) (get hex-values (at text i))))))))


(defn- authority-end
  [s]
  (->> [(str/index-of s "/") (str/index-of s "?") (str/index-of s "#")]
       (filter some?)
       (reduce min (count s))))


(defn parse-url
  "Parse one REPL connect URL into a WebSocket descriptor.

   Accepted forms are `daostream:ws://host[:port][/path]` and the same URL
   without the `daostream:` prefix.  Bracketed IPv6 literals are out of this
   slice and are reported rather than silently mis-parsed."
  ([url] (parse-url url {}))
  ([url {:keys [identity]}]
   (let [url (str/trim (str url))
         body (if (str/starts-with? url url-prefix) (subs url (count url-prefix)) url)]
     (cond
       (str/starts-with? body secure-scheme)
       (failure :yin.repl.connect/invalid-url
                "wss:// has no settled descriptor form in this slice; use ws://")

       (not (str/starts-with? body ws-scheme))
       (failure :yin.repl.connect/invalid-url
                (str "connect needs a daostream:ws:// URL; got "
                     (pr-str (data/summarize url diagnostic-bounds))))

       :else
       (let [rest-url (subs body (count ws-scheme))
             end (authority-end rest-url)
             authority (subs rest-url 0 end)
             raw-path (subs rest-url end)
             colon (str/last-index-of authority ":")
             host (if colon (subs authority 0 colon) authority)
             port (if colon (parse-port (subs authority (inc colon))) default-port)]
         (cond
           (str/starts-with? authority "[")
           (failure :yin.repl.connect/invalid-url
                    "bracketed IPv6 authorities are not part of this slice")

           (str/blank? host)
           (failure :yin.repl.connect/invalid-url
                    (str "connect URL has no host: "
                         (pr-str (data/summarize url diagnostic-bounds))))

           (nil? port)
           (failure :yin.repl.connect/invalid-url
                    (str "connect URL port must be a positive integer: "
                         (pr-str (data/summarize url diagnostic-bounds))))

           :else
           (let [descriptor {:dao.stream/type ws/transport-type
                             :dao.stream/identity (or identity service-identity)
                             :ws/host host
                             :ws/port port
                             :ws/path (repl-target raw-path)}]
             (if (ws/descriptor? descriptor)
               {outcome-key :yin.repl.connect/parsed descriptor-key descriptor}
               (failure :yin.repl.connect/invalid-url
                        (str "connect URL does not name a servable stream: "
                             (pr-str (data/summarize url diagnostic-bounds))))))))))))


;; =============================================================================
;; The two-reflection boundary
;; =============================================================================

(defn- mint
  [handle anchor]
  (let [result (stream/cursor handle anchor)]
    (when (= :dao.stream/ok (:dao.stream/outcome result))
      (:dao.stream/cursor result))))


(defn- remote-descriptor
  [channel identity]
  {:dao.stream/type :dao.stream/remote
   :dao.stream/identity identity
   :dao.stream/channel channel})


(defn- attacher
  [traffic connect!]
  (ws/make-attacher {:traffic {:dao.stream/handle traffic
                               :dao.stream/surface #{:writer}}
                     :admission traffic-admission
                     :connect! connect!}))


(defn- attach-outcome-message
  [url result]
  (case (:dao.stream/outcome result)
    :dao.stream/invalid-descriptor
    (str "The boundary refused the descriptor for " url)

    :dao.stream/transport-error
    (str "Could not start connecting to " url
         "; this is a local reachability failure and may succeed on retry")

    (str "Attaching to " url " answered " (pr-str (:dao.stream/outcome result)))))


(defn- fresh-dial
  "Compose one dial over a fresh traffic medium, its own fresh `attach!`
   bound to that medium, and a fresh channel ring, before any attach.  A
   reattachment composes a fresh dial with a fresh cursor, per
   `dao.stream.ws-project/dial`'s own contract, and `attach!` is scoped to
   the traffic medium it deposits into, so it must be rebuilt alongside
   it -- reusing an old `attach!` over a new traffic medium would deposit
   into the wrong buffer."
  [host]
  (let [traffic (:dao.stream/handle
                  (ring/create! {:dao.stream/type ring/transport-type
                                 ring/capacity-key traffic-capacity}))
        attach! (try (attacher traffic (:connect! host))
                     (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil))
        cursor (mint traffic stream/anchor-newest)
        ring (:dao.stream/handle
               (ring/create! {:dao.stream/type ring/transport-type
                              ring/capacity-key traffic-capacity}))]
    (when (and (some? attach!) (some? cursor))
      (ws-project/dial {:attach! attach!
                        :traffic {:dao.stream/handle traffic}
                        :cursor cursor
                        :ring ring
                        :table {}}))))


(defn- attach-pair
  "Attach both `requests`/`answers` reflections through `dial`'s one link,
   sharing it exactly as `dao.stream.remote.cljc`'s attacher requires.
   Returns `[requests-result answers-result]`, the second nil once the
   first fails."
  [dial channel]
  (let [requests-result
        (ws-project/dial-attach! dial (remote-descriptor channel
                                                         requests-identity))]
    (if-not (= :dao.stream/ok (:dao.stream/outcome requests-result))
      [requests-result nil]
      [requests-result
       (ws-project/dial-reflect! dial (remote-descriptor channel
                                                         answers-identity))])))


(defn open
  "Dial one WebSocket channel and attach both reflections, returning
   immediately.  `attach!` over `dao.stream.remote` answers ok at once --
   the contract's deferred remote confirmation -- so a successful result
   is reported `:established` without waiting on any further transport
   event.  The RPC client's response cursor is the `:dao.stream/newest`
   anchor; `rpc/poll!` resolves it, retrying while the answers reflection
   answers a retryable mint."
  [{:keys [url identity host]}]
  (let [parsed (parse-url url {:identity identity})]
    (if-not (= :yin.repl.connect/parsed (get parsed outcome-key))
      parsed
      (let [channel (get parsed descriptor-key)]
        (if-not (host-common/adapter? host)
          (failure :yin.repl.connect/no-host-adapter
                   (host-common/missing-message "(connect …) is not wired"))
          (let [dial (fresh-dial host)]
            (if-not dial
              (failure :yin.repl.connect/invalid-composition
                       "The client boundary composition was refused")
              (let [[requests-result answers-result]
                    (attach-pair dial channel)]
                (cond
                  (not= :dao.stream/ok (:dao.stream/outcome requests-result))
                  (failure :yin.repl.connect/attach-failed
                           (attach-outcome-message url requests-result))

                  (not= :dao.stream/ok (:dao.stream/outcome answers-result))
                  (failure :yin.repl.connect/attach-failed
                           (attach-outcome-message url answers-result))

                  :else
                  {outcome-key :yin.repl.connect/attached
                   connection-key {:url url
                                   :channel channel
                                   :host host
                                   :dial dial
                                   :status :established
                                   :detached-by nil}
                   client-key (rpc/client-state
                                (:dao.stream/handle requests-result)
                                (:dao.stream/handle answers-result)
                                stream/anchor-newest)})))))))))


(defn step!
  "Drive this connection's dial one tick: its projection, so wire bytes the
   host deposited onto the traffic medium reach the reflections' local
   channel ring, then this end's own mirror step (a REPL client serves
   nothing through it, but the composition is symmetric).  A caller's own
   cadence must call this every tick it also polls the RPC client, or the
   channel ring never receives anything the server sent -- reading a
   reflection drains only the ring, never the traffic medium directly."
  [connection]
  (when-let [dial (:dial connection)]
    (ws-project/dial-step! dial))
  connection)


(def terminal-statuses
  "Statuses observed from the RPC client's own `:terminal`.  They are
   conclusions, so a later local action cannot overwrite them with an
   intention."
  #{:detached :ended :not-found :transport-error})


(defn close!
  "Disconnect.  `close!` on the dialed channel's handle ends the connection;
   the RPC client observes the loss on its next poll as `/detached`, which
   is what keeps the connection reattachable.

   Who asked is recorded, because the two detachments mean different things to
   the shell: an operator `(disconnect)` returns ordinary input to local
   evaluation, while an uninvited drop queues it until a reattachment decision
   is made.  The terminal fact alone cannot tell them apart.

   A status already observed is kept: `:closing` describes an intention, and
   replacing an observed ending with it would lose the only record of how the
   connection actually ended."
  ([connection] (close! connection :operator))
  ([connection by]
   (when-let [ch (some-> (:dial connection) ws-project/channel)]
     (stream/close! (:handle ch)))
   (cond-> (assoc connection :detached-by by)
     (not (contains? terminal-statuses (:status connection)))
     (assoc :status :closing))))


(defn operator-detached?
  "True while this connection is detached, or detaching, at the operator's own
   request."
  [connection]
  (= :operator (:detached-by connection)))


(defn reattachable?
  "True when the RPC client's terminal reason is the one reconnectable one."
  [client]
  (= :dao.stream.apply/detached (:terminal client)))


(defn reattach
  "Reattach an existing connection: a fresh dial through the same host
   adapter, both reflections attached again, then `rpc/rebind`.

   The RPC client's response cursor and its collision-checked random id
   allocator survive; only the writer and reader reflections change."
  [connection client]
  (if-not (reattachable? client)
    (failure :yin.repl.connect/not-reattachable
             (str "Only a dropped connection reattaches; this one ended as "
                  (pr-str (:terminal client))))
    (let [{:keys [url channel host]} connection
          dial (fresh-dial host)]
      (if-not dial
        (failure :yin.repl.connect/invalid-composition
                 "The traffic medium minted no cursor")
        (let [[requests-result answers-result] (attach-pair dial channel)]
          (cond
            (not= :dao.stream/ok (:dao.stream/outcome requests-result))
            (failure :yin.repl.connect/attach-failed
                     (attach-outcome-message url requests-result))

            (not= :dao.stream/ok (:dao.stream/outcome answers-result))
            (failure :yin.repl.connect/attach-failed
                     (attach-outcome-message url answers-result))

            :else
            {outcome-key :yin.repl.connect/reattached
             connection-key (assoc connection
                                   :dial dial
                                   :status :established
                                   :detached-by nil)
             client-key (rpc/rebind client
                                    (:dao.stream/handle requests-result)
                                    (:dao.stream/handle answers-result))}))))))


;; =============================================================================
;; Terminal status, observed from the RPC client
;; =============================================================================

(defn- event
  [kind text]
  {event-key kind text-key text})


(defn- terminal-transition
  [connection terminal]
  (case terminal
    :dao.stream.apply/detached
    [:detached (event :yin.repl.connect/detached
                      (str "Disconnected from " (:url connection)
                           "; the served stream is untouched, so (connect "
                           (pr-str (:url connection)) ") reattaches"))]

    :dao.stream.apply/ended
    [:ended (event :yin.repl.connect/ended
                   (str "The stream served at " (:url connection)
                        " ended; there is nothing to reattach to"))]

    :dao.stream.apply/not-found
    [:not-found (event :yin.repl.connect/not-found
                       (str "No stream is served at "
                            (:ws/path (:channel connection))
                            ": the endpoint disclaimed it, so this is not "
                            "retried"))]

    :dao.stream.apply/transport-error
    [:transport-error
     (event :yin.repl.connect/transport-error
            (str "Could not reach " (:url connection)
                 "; this is a reachability failure and may succeed on retry"))]

    nil))


(defn observe-terminal
  "Given `connection` and the RPC client's current `:terminal`, return
   `[connection event]`.  The first terminal fact is the binding's
   conclusion, published once: a connection already at a terminal status is
   left alone, since a close/error race may repeat the same terminal on a
   later poll."
  [connection terminal]
  (if (or (nil? terminal) (contains? terminal-statuses (:status connection)))
    [connection nil]
    (if-let [[status ev] (terminal-transition connection terminal)]
      [(assoc connection :status status) ev]
      [connection nil])))


(defn summary
  "A serializable summary for `(repl-state)`."
  [connection]
  (if-not connection
    {:connected? false}
    {:connected? (= :established (:status connection))
     :url (:url connection)
     :path (:ws/path (:channel connection))
     :identity (:dao.stream/identity (:channel connection))
     :status (:status connection)
     :attachment (:attachment (some-> (:dial connection) ws-project/channel))}))
