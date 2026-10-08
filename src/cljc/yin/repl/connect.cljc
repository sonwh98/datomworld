(ns yin.repl.connect
  "The client side of the DaoStream Yin REPL, over the request-and-response
   service of docs/design/dao.stream.remote.md section 5.

   One connection is one explicit value.  `open` canonicalizes the URL to a
   portable endpoint specification `{:host :port :path}` and dials it by
   identities through `dao.stream.remote-channel`: two `dao.stream.remote`
   reflections over one channel -- one of `\"yin.repl/requests\"` (this end
   writes), one of `\"yin.repl/answers\"` (this end reads).  Both are
   attached at once, inside the dial: that is the contract's deferred remote
   confirmation, not a promise that the server has resolved anything, so a
   connection is reported `:established` immediately.  The driver's `now`
   is recorded at dialing, so a connection that never opens is lost at
   `give-up-after`.  No transport is named here.

   An eval request's `append!` answer is the channel writer's acceptance
   only (dao.stream.remote-channel, Writing through a reflection): an eval
   is confirmed solely by its correlated answer on the answers reflection,
   and an answer the server could not send reads, after `give-up-after`, as
   a detach.

   `step!` steps the dial by value at the driver's `now`; a caller that
   drops the stepped connection stops the projection.  `close!` detaches:
   it closes the connection only, so the RPC client observes the loss on
   its next poll as `/detached`, the one reattachable terminal.  `reattach`
   closes the old dial and composes a fresh one, then `rpc/rebind`s the
   retained RPC client onto the fresh pair -- which keeps its response
   cursor and its outstanding bookkeeping.  Terminal status
   (`:detached`/`:ended`/`:not-found`/`:transport-error`) is observed from
   the RPC client's own `:terminal`, the single source of terminal truth;
   a `/detached` is refined by the dial's neutral cause to `:ended` or
   `:transport-error` when the channel composition knows better, and only
   then; `observe-terminal` turns that into the one connection notice each
   terminal reason publishes exactly once."
  (:require [clojure.string :as str]
            [dao.data :as data]
            [dao.stream :as stream]
            [dao.stream.remote-channel :as remote-channel]
            [dao.stream.rpc :as rpc]
            [yin.repl.host.common :as host-common]))


;; =============================================================================
;; Composition constants
;; =============================================================================

(def url-prefix "daostream:")
(def ws-scheme "ws://")
(def secure-scheme "wss://")
(def default-port 8080)


(def repl-path
  "The path an empty URL path means for this REPL (D3).  An explicit `/`
   remains `/`; only an absent path becomes the served REPL stream."
  "/repl")


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
(def spec-key :yin.repl.connect/spec)
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
;; URL to endpoint specification
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
  "Parse one REPL connect URL into a portable endpoint specification
   `{:host h :port p :path canonical}` under `spec-key`.

   Accepted forms are `daostream:ws://host[:port][/path]` and the same URL
   without the `daostream:` prefix.  Bracketed IPv6 literals are out of this
   slice and are reported rather than silently mis-parsed."
  [url]
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
          {outcome-key :yin.repl.connect/parsed
           spec-key {:host host :port port :path (repl-target raw-path)}})))))


;; =============================================================================
;; The two-reflection boundary
;; =============================================================================

(defn- attach-outcome-message
  [url result]
  (case (:dao.stream/outcome result)
    :dao.stream/invalid-descriptor
    (str "The boundary refused the descriptor for " url)

    :dao.stream/transport-error
    (str "Could not start connecting to " url
         "; this is a local reachability failure and may succeed on retry")

    (str "Attaching to " url " answered " (pr-str (:dao.stream/outcome result)))))


(defn- dial
  "Dial `spec` by the two identities at the driver's `now`, answering
   `[dial nil]` when attached or `[nil failure]`."
  [url spec host now]
  (let [d (remote-channel/dial {:spec spec :host host :now now
                                :identities [requests-identity
                                             answers-identity]})]
    (case (:status d)
      :attached [d nil]

      :lost [nil (failure :yin.repl.connect/attach-failed
                          (attach-outcome-message url (:outcome d)))]

      [nil (failure :yin.repl.connect/no-host-adapter
                    (host-common/missing-message "(connect …) is not wired"))])))


(defn- requests-handle
  [d]
  (remote-channel/handle d requests-identity))


(defn- answers-handle
  [d]
  (remote-channel/handle d answers-identity))


(defn open
  "Dial the URL's endpoint by the two identities and return immediately.
   `:now` is the driver's clock reading, recorded on the dial so that a
   connection that never opens is lost at `give-up-after`; without it
   nothing expires.  Both reflections are attached at once -- the
   contract's deferred remote confirmation -- so a successful result is
   reported `:established` without waiting on any transport event.  The
   RPC client's response cursor is the `:dao.stream/newest` anchor;
   `rpc/poll!` resolves it, retrying while the answers reflection answers
   a retryable mint."
  [{:keys [url host now]}]
  (let [parsed (parse-url url)]
    (cond
      (not= :yin.repl.connect/parsed (get parsed outcome-key))
      parsed

      (not (host-common/adapter? host))
      (failure :yin.repl.connect/no-host-adapter
               (host-common/missing-message "(connect …) is not wired"))

      :else
      (let [spec (get parsed spec-key)
            [d refusal] (dial url spec host now)]
        (or refusal
            {outcome-key :yin.repl.connect/attached
             connection-key {:url url
                             :spec spec
                             :host host
                             :dial d
                             :status :established
                             :detached-by nil}
             client-key (rpc/client-state (requests-handle d)
                                          (answers-handle d)
                                          stream/anchor-newest)})))))


(defn step!
  "Step this connection's dial one tick at the driver's `now` and answer
   the stepped connection: its projection, so what the wire delivered
   reaches the reflections' channel ring, the link at `now` (expiry), then
   this end's own mirror step (a REPL client serves nothing through it,
   but the composition is symmetric).  The dial is a value: a caller must
   keep the returned connection and step it every tick it polls the RPC
   client, or the channel ring never receives what the server sent.  A
   nil `now` expires nothing."
  [connection now]
  (cond-> connection
    (:dial connection) (update :dial remote-channel/dial-step now)))


(def terminal-statuses
  "Statuses observed from the RPC client's own `:terminal`.  They are
   conclusions, so a later local action cannot overwrite them with an
   intention."
  #{:detached :ended :not-found :transport-error})


(defn close!
  "Disconnect.  The dial is detached: its connection closes and its
   reflections stay open, so the RPC client observes the loss on its next
   poll as `/detached`, which is what keeps the connection reattachable.

   Who asked is recorded, because the two detachments mean different things to
   the shell: an operator `(disconnect)` returns ordinary input to local
   evaluation, while an uninvited drop queues it until a reattachment decision
   is made.  The terminal fact alone cannot tell them apart.

   A status already observed is kept: `:closing` describes an intention, and
   replacing an observed ending with it would lose the only record of how the
   connection actually ended."
  ([connection] (close! connection :operator))
  ([connection by]
   (cond-> (assoc connection :detached-by by)
     (:dial connection) (update :dial remote-channel/detach!)
     (not (contains? terminal-statuses (:status connection)))
     (assoc :status :closing))))


(defn operator-detached?
  "True while this connection is detached, or detaching, at the operator's own
   request."
  [connection]
  (= :operator (:detached-by connection)))


(defn reattachable?
  "True when the binding may be reattached: the RPC client's terminal is
   the one reconnectable one and the connection's observed status did
   not refine it to a permanent conclusion."
  [connection client]
  (and (= :dao.stream.rpc/detached (:terminal client))
       (not (contains? #{:ended :not-found :transport-error}
                       (:status connection)))))


(defn reattach
  "Reattach an existing connection at the driver's `now`: the old dial is
   closed (its reflections already observed the loss), a fresh dial by the
   same identities is composed as `open` composes one, then `rpc/rebind`.

   The RPC client's response cursor and its collision-checked random id
   allocator survive; only the writer and reader reflections change."
  [connection client now]
  (if-not (reattachable? connection client)
    (failure :yin.repl.connect/not-reattachable
             (str "Only a dropped connection reattaches; this one ended as "
                  (pr-str (:terminal client))))
    (let [{:keys [url spec host]} connection
          _ (some-> (:dial connection) remote-channel/close!)
          [d refusal] (dial url spec host now)]
      (or refusal
          {outcome-key :yin.repl.connect/reattached
           connection-key (assoc connection
                                 :dial d
                                 :status :established
                                 :detached-by nil)
           client-key (rpc/rebind client (requests-handle d) (answers-handle d))}))))


;; =============================================================================
;; Terminal status, observed from the RPC client
;; =============================================================================

(defn- event
  [kind text]
  {event-key kind text-key text})


(defn- terminal-transition
  [connection terminal]
  (case terminal
    :dao.stream.rpc/detached
    [:detached (event :yin.repl.connect/detached
                      (str "Disconnected from " (:url connection)
                           "; the served stream is untouched, so (connect "
                           (pr-str (:url connection)) ") reattaches"))]

    :dao.stream.rpc/ended
    [:ended (event :yin.repl.connect/ended
                   (str "The stream served at " (:url connection)
                        " ended; there is nothing to reattach to"))]

    :dao.stream.rpc/not-found
    [:not-found (event :yin.repl.connect/not-found
                       (str "No stream is served at "
                            (:path (:spec connection))
                            ": the endpoint disclaimed it, so this is not "
                            "retried"))]

    :dao.stream.rpc/transport-error
    [:transport-error
     (event :yin.repl.connect/transport-error
            (str "Could not reach " (:url connection)
                 "; this is a reachability failure and may succeed on retry"))]

    nil))


(defn- refined-terminal
  "The RPC terminal, refined when it is /detached by what the dial
   knows: the peer's ended signal missed during the drain is the ended
   terminal; a connection that never opened is a reachability failure.
   Every other cause, and no cause, keeps the reattachable detach."
  [connection terminal]
  (if-not (= :dao.stream.rpc/detached terminal)
    terminal
    (let [d (:dial connection)]
      (case (remote-channel/cause d)
        :ended :dao.stream.rpc/ended
        :unreachable :dao.stream.rpc/transport-error
        :expired (if (remote-channel/opened? d)
                   terminal
                   :dao.stream.rpc/transport-error)
        terminal))))


(defn observe-terminal
  "Given `connection` and the RPC client's current `:terminal`, return
   `[connection event]`.  The first terminal fact is the binding's
   conclusion, published once: a connection already at a terminal status is
   left alone, since a close/error race may repeat the same terminal on a
   later poll.  A `/detached` is refined by the dial's neutral cause
   (`refined-terminal`); no cause is read before the RPC client has a
   terminal."
  [connection terminal]
  (if (or (nil? terminal) (contains? terminal-statuses (:status connection)))
    [connection nil]
    (if-let [[status ev] (terminal-transition
                           connection
                           (refined-terminal connection terminal))]
      [(assoc connection :status status) ev]
      [connection nil])))


(defn summary
  "A serializable summary for `(repl-state)`."
  [connection]
  (if-not connection
    {:connected? false}
    {:connected? (= :established (:status connection))
     :url (:url connection)
     :path (:path (:spec connection))
     :status (:status connection)
     :dial-status (:status (:dial connection))
     :attachment (some-> (:dial connection) remote-channel/attachment)}))
