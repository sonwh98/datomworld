(ns yin.repl.connect
  "The client side of the DaoStream Yin REPL — Phase R3.

   One connection is one explicit value.  `open` canonicalizes the URL to the
   request-target form `dao.stream.ws.md` defines, creates the boundary's single
   capacity-8192 traffic medium, mints both of its cursors, composes the
   WebSocket boundary, and only then calls `attach!`, receiving a handle at
   once.  No promise, future, callback, atom, clock, or namespace global is
   created here, and no open event can race ahead of a cursor.

   The traffic medium is reused across reconnects: `disconnect` is `close!`,
   and reattaching is `attach!` plus `rebind`, which keeps the response cursor
   and takes the new attachment id.  WebSocket spellings stay confined to the
   R1 decoder in `dao.stream.rpc.ws`; everything observed here is the
   neutral `:dao.stream.apply/…` lifecycle vocabulary."
  (:require [clojure.string :as str]
            [dao.data :as data]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.rpc :as rpc]
            [dao.stream.rpc.ws :as rpc-ws]
            [dao.stream.ws :as ws]
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


(def lifecycle-budget
  "Maximum lifecycle elements one observation reads, so a noisy medium cannot
   make a single step unbounded."
  32)


(def url-prefix "daostream:")
(def ws-scheme "ws://")
(def secure-scheme "wss://")
(def default-port 8080)


(def repl-path
  "The path an empty URL path means for this REPL (D3).  An explicit `/`
   remains `/`; only an absent path becomes the served REPL stream."
  "/repl")


(def service-identity
  "The logical identity of the stream a REPL endpoint serves.  A descriptor
   names a served stream, so a client that only typed a URL still needs one;
   a stable service name keeps `attach!` honest across endpoint restarts."
  "yin.repl/repl")


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
;; The boundary
;; =============================================================================

(defn- mint
  [handle anchor]
  (let [result (stream/cursor handle anchor)]
    (when (= :dao.stream/ok (:dao.stream/outcome result))
      (:dao.stream/cursor result))))


(defn boundary
  "Create the client boundary's media and cursors, before any `attach!`.

   One traffic medium carries every deposited event for the boundary's single
   active attachment.  Two cursors are minted on it and both are owned by the
   one step owner: the RPC client reads responses through the first, and
   lifecycle observation reads through the second.  Neither is arithmetic and
   neither is fabricated later."
  ([] (boundary nil))
  ([traffic]
   (let [traffic (or traffic
                     (:dao.stream/handle
                       (ring/create! {:dao.stream/type ring/transport-type
                                      ring/capacity-key traffic-capacity})))]
     {:traffic traffic
      :response-cursor (mint traffic stream/anchor-newest)
      :lifecycle-cursor (mint traffic stream/anchor-newest)})))


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


(defn open
  "Compose one client boundary and attach, returning immediately.

   The order is the point and is observable: descriptor, then medium, then both
   cursors, then the boundary, and only then `attach!`.  A successful result
   carries the connection value and the RPC client state built from the *whole*
   attach result, so the response decoder filters on the attachment the
   transport minted."
  [{:keys [url identity host traffic]}]
  (let [parsed (parse-url url {:identity identity})]
    (if-not (= :yin.repl.connect/parsed (get parsed outcome-key))
      parsed
      (let [descriptor (get parsed descriptor-key)]
        (if-not (host-common/adapter? host)
          (failure :yin.repl.connect/no-host-adapter
                   (host-common/missing-message "(connect …) is not wired"))
          (let [{:keys [traffic response-cursor lifecycle-cursor]} (boundary traffic)
                attach! (try (attacher traffic (:connect! host))
                             (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil))]
            (cond
              (nil? attach!)
              (failure :yin.repl.connect/invalid-composition
                       "The client boundary composition was refused")

              (or (nil? response-cursor) (nil? lifecycle-cursor))
              (failure :yin.repl.connect/invalid-composition
                       "The traffic medium minted no cursor")

              :else
              (let [result (attach! descriptor)
                    attachment (:dao.stream/attachment result)]
                (if-not (= :dao.stream/ok (:dao.stream/outcome result))
                  (failure :yin.repl.connect/attach-failed
                           (attach-outcome-message url result))
                  {outcome-key :yin.repl.connect/attached
                   connection-key {:url url
                                   :descriptor descriptor
                                   :traffic traffic
                                   :response-cursor response-cursor
                                   :lifecycle-cursor lifecycle-cursor
                                   :attach! attach!
                                   :attachment attachment
                                   :handle (:dao.stream/handle result)
                                   :decode (rpc-ws/decoder attachment)
                                   :status :connecting
                                   :detached-by nil
                                   :ledger :untried}
                   client-key (rpc-ws/init-client result traffic response-cursor)})))))))))


(def terminal-statuses
  "Statuses the boundary itself reported.  They are conclusions, so a later
   local action cannot overwrite them with an intention."
  #{:detached :ended :not-found :transport-error})


(defn close!
  "Disconnect.  `close!` on the attachment handle ends the connection; the
   terminal fact still arrives as a deposited event, which is what keeps the
   connection reattachable and its cursor usable.

   Who asked is recorded, because the two detachments mean different things to
   the shell: an operator `(disconnect)` returns ordinary input to local
   evaluation, while an uninvited drop queues it until a reattachment decision
   is made.  The terminal event alone cannot tell them apart.

   A status the boundary already reported is kept: `:closing` describes an
   intention, and replacing an observed ending with it would lose the only
   record of how the connection actually ended."
  ([connection] (close! connection :operator))
  ([connection by]
   (when (:handle connection)
     (stream/close! (:handle connection)))
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
  "Reattach an existing connection: `attach!` plus `rebind`.

   The traffic medium, both cursors, and the monotonic id allocator survive;
   only the writer, the attachment id, and the decoder built around it change."
  [connection client]
  (cond
    (not (fn? (:attach! connection)))
    (failure :yin.repl.connect/invalid-composition
             "This connection has no boundary to reattach through")

    (not (reattachable? client))
    (failure :yin.repl.connect/not-reattachable
             (str "Only a dropped connection reattaches; this one ended as "
                  (pr-str (:terminal client))))

    :else
    (let [result ((:attach! connection) (:descriptor connection))
          attachment (:dao.stream/attachment result)]
      (if-not (= :dao.stream/ok (:dao.stream/outcome result))
        (failure :yin.repl.connect/attach-failed
                 (attach-outcome-message (:url connection) result))
        {outcome-key :yin.repl.connect/reattached
         connection-key (assoc connection
                               :attachment attachment
                               :handle (:dao.stream/handle result)
                               :decode (rpc-ws/decoder attachment)
                               :status :connecting
                               :detached-by nil)
         client-key (rpc-ws/rebind client result)}))))


;; =============================================================================
;; Neutral lifecycle observation
;; =============================================================================

(defn- event
  [kind text]
  {event-key kind text-key text})


(defn- transition
  [connection lifecycle]
  (if (and (contains? terminal-statuses (:status connection))
           (not= :dao.stream.apply/diagnostic lifecycle))
    ;; The first terminal fact is the binding's conclusion.  Close/error races
    ;; may deposit another lifecycle value afterwards, but it cannot rewrite
    ;; the conclusion displayed by `(repl-state)` or used by reconnect policy.
    [connection nil]
    (case lifecycle
      :dao.stream.apply/established
      (if (= :established (:status connection))
        [connection nil]
        [(assoc connection :status :established)
         (event :yin.repl.connect/connected (str "Connected to " (:url connection)))])

      :dao.stream.apply/detached
      [(assoc connection :status :detached)
       (event :yin.repl.connect/detached
              (str "Disconnected from " (:url connection)
                   "; the served stream is untouched, so (connect "
                   (pr-str (:url connection)) ") reattaches"))]

      :dao.stream.apply/ended
      [(assoc connection :status :ended)
       (event :yin.repl.connect/ended
              (str "The stream served at " (:url connection)
                   " ended; there is nothing to reattach to"))]

      :dao.stream.apply/not-found
      [(assoc connection :status :not-found)
       (event :yin.repl.connect/not-found
              (str "No stream is served at " (:ws/path (:descriptor connection))
                   ": the endpoint disclaimed it, so this is not retried"))]

      :dao.stream.apply/transport-error
      [(assoc connection :status :transport-error)
       (event :yin.repl.connect/transport-error
              (str "Could not reach " (:url connection)
                   "; this is a reachability failure and may succeed on retry"))]

      :dao.stream.apply/diagnostic
      [connection (event :yin.repl.connect/diagnostic
                         ";; connection diagnostic reported by the boundary")]

      [connection nil])))


(defn- decode-lifecycle
  [connection value]
  (try
    (let [decoded ((:decode connection) value)]
      (when (= :dao.stream.rpc/lifecycle (get decoded rpc/event-key))
        (get decoded rpc/lifecycle-key)))
    (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil)))


(defn observe
  "Advance the boundary's lifecycle cursor, returning `[connection events]`.

   This is the whole of `Connected to …` and its failures: the R1 decoder
   translates each deposited envelope, and only the neutral vocabulary is
   interpreted here.  It is total over `next`: `ok` advances to the exact
   successor, `gap` reports the loss and resumes at the recovery cursor, and
   every other outcome is recorded in the ledger and ends the observation."
  ([connection] (observe connection lifecycle-budget))
  ([connection budget]
   (if (or (nil? connection) (nil? (:lifecycle-cursor connection)))
     [connection []]
     (loop [remaining budget
            connection connection
            events []]
       (if (zero? remaining)
         [connection events]
         (let [result (stream/next (:traffic connection) (:lifecycle-cursor connection))
               outcome (:dao.stream/outcome result)]
           (case outcome
             :dao.stream/ok
             (let [connection (assoc connection
                                     :lifecycle-cursor (:dao.stream/cursor result)
                                     :ledger outcome)
                   lifecycle (decode-lifecycle connection (:dao.stream/value result))
                   [connection' produced] (if lifecycle
                                            (transition connection lifecycle)
                                            [connection nil])]
               (recur (dec remaining) connection' (cond-> events produced (conj produced))))

             :dao.stream/gap
             (recur (dec remaining)
                    (assoc connection
                           :lifecycle-cursor (:dao.stream/cursor result)
                           :ledger outcome)
                    (conj events
                          (event :yin.repl.connect/notice
                                 ";; connection events lost: resumed at the recovery cursor")))

             :dao.stream/blocked
             [(assoc connection :ledger outcome) events]

             [(assoc connection :ledger outcome)
              (conj events
                    (event :yin.repl.connect/notice
                           (str ";; connection events " (name outcome))))])))))))


(defn summary
  "A serializable summary for `(repl-state)`."
  [connection]
  (if-not connection
    {:connected? false}
    {:connected? (= :established (:status connection))
     :url (:url connection)
     :path (:ws/path (:descriptor connection))
     :identity (:dao.stream/identity (:descriptor connection))
     :attachment (:attachment connection)
     :status (:status connection)
     :last-outcome (:ledger connection)}))
