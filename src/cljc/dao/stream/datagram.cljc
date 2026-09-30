(ns dao.stream.datagram
  "The raw datagram layer of docs/design/dao.stream.datagram.md: one bound
   host socket exposed as dao.stream values. This layer carries bytes and
   addresses and nothing else -- no decoding, retry, ordering, dedup,
   fragmentation, reassembly, name resolution, NAT traversal; each of those
   is an interpreter above it.

   A datagram socket retains nothing, so it has no reader surface: a
   writer handle (#{:writer :closable}) whose append! hands one addressed
   datagram to the socket, and a traffic stream, supplied by the
   composition, onto which the host adapter deposits every received
   datagram as one event. Readers hold their own cursors on the traffic
   stream.

   Binding is a host act, not create!, and the composition proceeds in two
   explicit moves (section 3): the host seam (dao.stream.datagram.jvm,
   .node, .dart) takes {:identity id :deposit w :bind-host h :bind-port p
   :max-bytes n} -- a deposit writer and nothing else, no function to
   invoke -- and returns at once {:send! f :close! f}; the seam deposits
   :dao.stream.datagram/bound with the actual local address on every host;
   the composition reads that event and calls `writer` with the seam and
   the complete descriptor. A failed bind deposits :bind-failed and there
   is no writer.

   A send failure is the caller's outcome when the host answers at call
   time (:dao.stream/transport-error, clean: nothing was sent) and a
   :dao.stream.datagram/send-failed lifecycle event on the traffic stream
   for its other observers -- always the event for a failure the host
   reports only later, which is the channel this transport declares for an
   answer it cannot give at call time (dao.stream.md, Writing). Excluded
   append! outcomes: :full, because this layer holds no outbound queue and
   a socket under pressure loses datagrams -- the medium's declared nature,
   not a refusal; :refused, because no policy is composed in this layer.

   There is no :dao.stream/create entry. `attach!` on a datagram
   descriptor off its host is :dao.stream/not-found; on its own host it
   resolves only against sockets a composition chose to keep in its
   host-composed attach closure -- `make-attacher` is that closure over a
   composition-owned resolver, never a registry. Exposure is a real
   hazard: a raw writer handle entered in a mirror table lets whoever
   reaches it send arbitrary datagrams from this host; no composition does
   so without a gate on that entry (section 8)."

  (:require [clojure.string :as str]
            [dao.stream :as stream]
            [dao.stream.base64 :as base64])
  #?@(:cljd [(:import ["dart:typed_data" Uint8List])]))


(def transport-type
  "The dao.stream/type of a bound datagram socket's descriptor."
  :dao.stream/datagram)


(def default-max-bytes
  "The default datagram budget, composition data: 1200 clears the IPv6
   minimum path MTU with headers to spare. Symmetric -- an inbound
   datagram longer than it is dropped below the transform as protocol
   validation, never deposited."
  1200)


(defn- byte-length
  [bs]
  #?(:cljd (.-length ^Uint8List bs)
     :default (alength bs)))


;; =============================================================================
;; Addresses: IP literals and ports (section 2)
;; =============================================================================


(def ^:private octet-regex-src
  "One IPv4 octet, 0-255, no leading zero unless the octet is 0."
  "(?:0|[1-9][0-9]?|1[0-9][0-9]|2[0-4][0-9]|25[0-5])")


(def ^:private ipv4-regex
  ;; Anchored at both ends: re-matches is a true full match on the JVM but
  ;; an unanchored exec plus equality on ClojureScript, where an open
  ;; pattern happily answers a prefix ("255.255.255.25" of
  ;; "255.255.255.255") and the full-match test then fails. The anchors
  ;; make every host agree, and under the JVM's matches() they change
  ;; nothing.
  (re-pattern (str "^" octet-regex-src "(?:\\." octet-regex-src "){3}$")))


(defn- ip4-literal?
  [s]
  (boolean (re-matches ipv4-regex s)))


(def ^:private hex-group-regex
  #"^[0-9A-Fa-f]{1,4}$")


(defn- hex-group?
  [g]
  (boolean (re-matches hex-group-regex g)))


(defn- piece-groups
  "The count of groups one ::-piece contributes, its last group possibly
   an IPv4 dotted quad counting as two; nil when any group is malformed.
   An empty piece contributes nothing."
  [piece ipv4-tail-allowed?]
  (if (= "" piece)
    0
    (let [groups (str/split piece #":" -1)
          n (count groups)
          last-g (nth groups (dec n))]
      (if (str/includes? last-g ".")
        (when (and ipv4-tail-allowed? (ip4-literal? last-g))
          (when (every? hex-group? (take (dec n) groups))
            (inc n)))
        (when (every? hex-group? groups)
          n)))))


(defn ip-literal?
  "True iff `s` is an IP literal in its textual form, IPv4 or IPv6. A
   destination host that is not a literal is invalid-value: name
   resolution can wait, no operation waits, and resolution is an
   interpreter above this layer."
  [s]
  (and (string? s)
       (or (ip4-literal? s)
           (and (not (str/includes? s "%"))
                (let [pieces (str/split s #"::" -1)]
                  (case (count pieces)
                    1 (let [n (piece-groups (nth pieces 0) true)]
                        (and n (= 8 n)))
                    2 (let [head (piece-groups (nth pieces 0) false)
                            tail (piece-groups (nth pieces 1) true)]
                        (and head tail (<= (+ head tail) 7)))
                    false))))))


(defn valid-port?
  "True iff `n` is an integer in 1..65535."
  [n]
  (and (integer? n) (<= 1 n 65535)))


(defn address?
  "True iff `m` is an address value: an IP literal and a valid port."
  [m]
  (and (map? m)
       (ip-literal? (:dao.stream.datagram/host m))
       (valid-port? (:dao.stream.datagram/port m))))


(defn descriptor?
  "{:dao.stream/type :dao.stream/datagram :dao.stream/identity id
     :dao.stream.datagram/bind-host h :dao.stream.datagram/bind-port p}
   (section 3). The bind- keys name the LOCAL socket: the interface bound
   and the port actually bound, never 0 -- a different kind of address
   than the remote destination :dao.stream.udp's descriptor names."
  [d]
  (and (map? d)
       (= transport-type (:dao.stream/type d))
       (string? (:dao.stream/identity d))
       (ip-literal? (:dao.stream.datagram/bind-host d))
       (valid-port? (:dao.stream.datagram/bind-port d))))


;; =============================================================================
;; The values of section 2
;; =============================================================================


(defn datagram-event?
  "True iff `v` is one received datagram as an event: exact bytes as
   strict Base64, and the source address the socket observed."
  [v]
  (and (map? v)
       (address? (:dao.stream.datagram/source v))
       (base64/text? (:dao.stream.datagram/bytes v))))


(defn lifecycle-event?
  "True iff `v` is one host fact about the socket itself."
  [v]
  (and (map? v)
       (string? (:dao.stream.datagram/socket v))
       (keyword? (:dao.stream.datagram/event v))))


(defn bound-event
  "The bound lifecycle event: the socket exists, with its actual local
   address."
  [id host port]
  {:dao.stream.datagram/socket id
   :dao.stream.datagram/event :dao.stream.datagram/bound
   :dao.stream.datagram/local {:dao.stream.datagram/host host
                               :dao.stream.datagram/port port}})


(defn bind-failed-event
  "The failed-bind lifecycle event. There is no writer."
  [id reason]
  {:dao.stream.datagram/socket id
   :dao.stream.datagram/event :dao.stream.datagram/bind-failed
   :dao.stream.datagram/reason reason})


(defn send-failed-event
  "The send-failure lifecycle event: the channel for a send failure the
   host could not answer at call time, and the observers' copy of one it
   could."
  [id reason]
  {:dao.stream.datagram/socket id
   :dao.stream.datagram/event :dao.stream.datagram/send-failed
   :dao.stream.datagram/reason reason})


(defn closed-event
  "The closed lifecycle event, deposited while the deposit writer still
   admits it."
  [id]
  {:dao.stream.datagram/socket id
   :dao.stream.datagram/event :dao.stream.datagram/closed})


(defn datagram-event
  "One received datagram as an event: `bytes` encoded as Base64 so no host
   byte array crosses onto a stream, with the source the socket observed."
  [id host port bytes]
  {:dao.stream.datagram/socket id
   :dao.stream.datagram/source {:dao.stream.datagram/host host
                                :dao.stream.datagram/port port}
   :dao.stream.datagram/bytes (base64/encode bytes)})


;; =============================================================================
;; The writer handle (section 4)
;; =============================================================================


(defn- check-outbound
  "One outbound value of section 2 as either {:bytes bs} -- the decoded
   datagram, ready for the socket -- or {:refusal reason}: a malformed
   destination, a host that is not an IP literal, bytes that are not
   strict Base64, or a decoded length over the budget. Nothing was sent
   for a refusal."
  [v max-bytes]
  (cond
    (not (map? v))
    {:refusal "not an outbound value"}

    (not (address? (:dao.stream.datagram/destination v)))
    {:refusal "malformed destination"}

    (not (base64/text? (:dao.stream.datagram/bytes v)))
    {:refusal "bytes are not strict Base64"}

    :else
    (let [bs (base64/decode (:dao.stream.datagram/bytes v))]
      (if (> (byte-length bs) max-bytes)
        {:refusal "decoded length over :max-bytes"}
        {:bytes bs}))))


(deftype DatagramWriter
  [send-fn close-fn the-descriptor max-bytes closed]

  stream/IDaoStreamDescriptor

  (descriptor
    [_]
    {:dao.stream/outcome :dao.stream/ok
     :dao.stream/descriptor the-descriptor
     :dao.stream/identity (:dao.stream/identity the-descriptor)})


  stream/IDaoStreamWriter

  (append!
    [_ v]
    (if @closed
      {:dao.stream/outcome :dao.stream/closed}
      (let [checked (check-outbound v max-bytes)]
        (if-some [reason (:refusal checked)]
          {:dao.stream/outcome :dao.stream/invalid-value
           :dao.stream.datagram/reason reason}
          (let [destination (:dao.stream.datagram/destination v)
                r (try
                    (send-fn (:dao.stream.datagram/host destination)
                             (:dao.stream.datagram/port destination)
                             (:bytes checked))
                    (catch #?(:cljd Object :clj Throwable :cljs :default) e
                      {:dao.stream/outcome :dao.stream/transport-error
                       :dao.stream.datagram/reason (str e)}))]
            (if (and (map? r) (contains? r :dao.stream/outcome))
              (if (#{:dao.stream/ok :dao.stream/transport-error
                     :dao.stream/closed} (:dao.stream/outcome r))
                r
                {:dao.stream/outcome :dao.stream/transport-error})
              {:dao.stream/outcome :dao.stream/ok}))))))


  stream/IDaoStreamClosable

  (close!
    [_]
    (vreset! closed true)
    (try (close-fn)
         (catch #?(:cljd Object :clj Throwable :cljs :default) _
           nil))
    {:dao.stream/outcome :dao.stream/ok}))


(defn writer
  "The composition's second move (section 3): the writer handle over a
   bound `seam` ({:send! f :close! f}) and the complete `descriptor` the
   bound event reported. `max-bytes` is composition data, default 1200,
   the same budget the seam was bound with."
  ([seam descriptor]
   (writer seam descriptor default-max-bytes))
  ([seam descriptor max-bytes]
   (when-not (and (map? seam)
                  (fn? (:send! seam))
                  (fn? (:close! seam)))
     (throw (ex-info "a dao.stream.datagram seam is {:send! f :close! f}"
                     {:seam seam})))
   (when-not (descriptor? descriptor)
     (throw (ex-info "not a dao.stream.datagram descriptor"
                     {:descriptor descriptor})))
   (DatagramWriter. (:send! seam) (:close! seam) descriptor
                    (long max-bytes) (volatile! false))))


(defn make-attacher
  "The host-composed attach! closure over sockets a composition chose to
   keep: `resolver` maps a socket identity to its writer handle, or nil.
   Attaching never creates, resolves nothing ambiently -- there is no
   registry -- and the same handle is shared, not re-minted: a datagram
   socket does not distinguish attachments."
  [resolver]
  (fn attach!
    [descriptor]
    (if-not (descriptor? descriptor)
      {:dao.stream/outcome :dao.stream/invalid-descriptor}
      (if-let [handle (resolver (:dao.stream/identity descriptor))]
        (if (= (:dao.stream/identity descriptor)
               (:dao.stream/identity (stream/descriptor handle)))
          {:dao.stream/outcome :dao.stream/ok
           :dao.stream/handle handle}
          {:dao.stream/outcome :dao.stream/not-found})
        {:dao.stream/outcome :dao.stream/not-found}))))
