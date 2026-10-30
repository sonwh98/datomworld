(ns dao.stream.ws.upgrade
  "The pure HTTP/1.1 WebSocket upgrade core (RFC 6455 section 4) for hosts
   that own their sockets (docs/design/yin.repl-jolt-implementation.md
   section 9).

   No I/O, no clock, no entropy, no digest of its own: `Sec-WebSocket-Accept`
   takes an injected SHA-1 (`bytes -> bytes`), and a client key is made from
   sixteen random bytes the caller draws.  The head is read incrementally
   (`head-feed`) under an 8 KiB cap, so a stalled or oversized peer is
   refused at the chunk that crosses the cap and never accumulates more.

   Extensions are ignored by a server and none is ever offered by a client; an
   extension in a 101 response fails the client.  Header names are matched
   case-insensitively; `Connection` and `Upgrade` are token lists.  Header
   bytes outside printable ASCII (and tab) are refused as malformed."
  (:require [clojure.string :as str]
            [dao.host.bytes :as bytes]
            [dao.host.text :as text]
            [dao.stream.base64 :as base64]
            [dao.stream.codec :as codec]))


(def guid "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")


(def head-limit
  "The most head bytes (terminating blank line included) a peer may send."
  8192)


;; =============================================================================
;; Incremental head reading
;; =============================================================================

(def ^:private ascii-printable
  " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~")


(defn head-parser
  "A fresh incremental head reader."
  []
  {:bytes [] :phase :reading})


(defn- ends-with-blank-line?
  [v]
  (let [n (count v)]
    (and (>= n 4)
         (= 13 (nth v (- n 4))) (= 10 (nth v (- n 3)))
         (= 13 (nth v (- n 2))) (= 10 (nth v (- n 1))))))


(defn- head-string
  "The head text of `v`, or nil when a byte is not printable ASCII, tab, CR or LF."
  [v]
  (loop [i 0 out []]
    (if (= i (count v))
      (str/join out)
      (let [b (nth v i)]
        (cond
          (= b 13) (recur (inc i) (conj out "\r"))
          (= b 10) (recur (inc i) (conj out "\n"))
          (= b 9) (recur (inc i) (conj out "\t"))
          (<= 32 b 126) (recur (inc i) (conj out (subs ascii-printable (- b 32) (- b 31))))
          :else nil)))))


(defn head-feed
  "Feed one chunk; `[parser' result]` where result is nil (more needed),
   `{:head string :rest bytes}` (`rest` is whatever followed the blank line
   in the chunk, fresh), `{:refused :head-too-large}` or `{:refused :malformed}`.
   A refused or finished parser ignores further input."
  [state chunk]
  (if (not= :reading (:phase state))
    [state nil]
    (let [n (bytes/length chunk)]
      (loop [i 0 v (:bytes state)]
        (cond
          (>= i n) [(assoc state :bytes v) nil]

          (>= (count v) head-limit)
          [(assoc state :phase :refused :bytes []) {:refused :head-too-large}]

          :else
          (let [v' (conj v (bytes/get chunk i))]
            (if (ends-with-blank-line? v')
              (if-let [head (head-string v')]
                [(assoc state :phase :done :bytes [])
                 {:head head :rest (bytes/slice chunk (inc i) n)}]
                [(assoc state :phase :refused :bytes []) {:refused :malformed}])
              (recur (inc i) v'))))))))


;; =============================================================================
;; Head parsing
;; =============================================================================

(def ^:private token-chars
  (set (map str (seq "!#$%&'*+-.^_`|~0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"))))


(defn- token?
  "True for a non-empty RFC 9110 token."
  [s]
  (and (string? s) (seq s) (every? token-chars (map str (seq s)))))


(defn- parse-headers
  "Header lines to `{:headers {lower-name joined-value} :counts {lower-name n}}`,
   or nil when malformed: a field name is a token, and a line holds no
   residual CR or LF."
  [lines]
  (reduce (fn [acc line]
            (let [colon (str/index-of line ":")
                  name (when colon (subs line 0 colon))]
              (if (or (nil? colon) (not (token? name))
                      (str/includes? line "\r") (str/includes? line "\n"))
                (reduced nil)
                (let [name (str/lower-case name)
                      value (str/trim (subs line (inc colon)))]
                  (-> acc
                      (update-in [:headers name]
                                 #(if % (str % ", " value) value))
                      (update-in [:counts name] (fnil inc 0)))))))
          {:headers {} :counts {}} lines))


(defn- split-head
  "`[start-line header-lines]` of a head string, or nil."
  [head]
  (let [lines (str/split (subs head 0 (- (count head) 4)) #"\r\n" -1)]
    (when (and (seq lines) (not (str/blank? (first lines)))
               (not-any? #(or (str/includes? % "\r") (str/includes? % "\n")) lines))
      [(first lines) (rest lines)])))


(defn parse-request
  "`{:method :target :version :headers :counts}` of a request head, or
   `{:error :malformed}`.  `:counts` is how many times each field appeared;
   `:headers` joins repeats with a comma."
  [head]
  (let [[start lines] (split-head head)
        parts (when start (str/split start #" " -1))
        parsed (when start (parse-headers lines))]
    (if (and parts (= 3 (count parts)) (every? seq parts) parsed)
      {:method (nth parts 0) :target (nth parts 1) :version (nth parts 2)
       :headers (:headers parsed) :counts (:counts parsed)}
      {:error :malformed})))


(defn- parse-status
  [s]
  (when (and (= 3 (count s))
             (every? #(str/includes? "0123456789" %) (map str (seq s))))
    (+ (* 100 (str/index-of "0123456789" (subs s 0 1)))
       (* 10 (str/index-of "0123456789" (subs s 1 2)))
       (str/index-of "0123456789" (subs s 2 3)))))


(defn parse-response
  "`{:status :headers :counts}` of a response head, or `{:error :malformed}`."
  [head]
  (let [[start lines] (split-head head)
        parts (when start (str/split start #" " 3))
        status (when (and parts (>= (count parts) 2) (= "HTTP/1.1" (first parts)))
                 (parse-status (second parts)))
        parsed (when start (parse-headers lines))]
    (if (and status parsed)
      {:status status :headers (:headers parsed) :counts (:counts parsed)}
      {:error :malformed})))


(defn header-tokens
  "The lower-cased, trimmed, non-empty comma-separated tokens of header value
   `v` (nil is no tokens)."
  [v]
  (->> (str/split (str v) #",")
       (map (comp str/lower-case str/trim))
       (remove str/blank?)
       vec))


(defn- has-token?
  [headers name token]
  (boolean (some #{token} (header-tokens (get headers name)))))


;; =============================================================================
;; Subprotocols
;; =============================================================================

(defn offered-subprotocols
  "The upgrade request's offered subprotocol list, split and trimmed.
   `request` carries lower-cased `:headers`."
  [request]
  (->> (str/split (str (get-in request [:headers "sec-websocket-protocol"])) #",")
       (map str/trim)
       (remove str/blank?)))


(defn negotiate
  "Pick the first endpoint codec (in endpoint order) the upgrade offered, or
   nil when the offer shares no subprotocol with the endpoint.  A mismatch is
   a failed handshake, not a downgrade."
  [codecs offered]
  (some (fn [codec]
          (when (some #(= (codec/codec-name codec) %) offered)
            codec))
        codecs))


;; =============================================================================
;; Handshake
;; =============================================================================

(defn accept-key
  "`Sec-WebSocket-Accept` for `key`, from the injected `sha1` (bytes -> bytes)."
  [key sha1]
  (base64/encode (sha1 (text/utf8-encode (str key guid)))))


(defn client-key
  "A `Sec-WebSocket-Key` from sixteen random host bytes."
  [random-bytes]
  (when-not (= 16 (bytes/length random-bytes))
    (throw (ex-info "a handshake key needs 16 random bytes" {})))
  (base64/encode random-bytes))


(def ^:private host-pattern
  #"[A-Za-z0-9\-._~!$&'()*+,;=%]+|\[[0-9A-Fa-f:.]+\]")


(defn- host-authority?
  "True for a `Host` value: a host (reg-name, IPv4 or bracketed IPv6) and an
   optional `:port`."
  [v]
  (boolean
    (and (string? v)
         (or (re-matches host-pattern v)
             (when-let [colon (str/last-index-of v ":")]
               (and (re-matches host-pattern (subs v 0 colon))
                    (re-matches #"[0-9]*" (subs v (inc colon)))))))))


(def ^:private reasons
  {101 "Switching Protocols" 400 "Bad Request" 426 "Upgrade Required"
   431 "Request Header Fields Too Large"})


(defn refusal-response
  "The head text of a refusal with `status` (400, 426 or 431)."
  [status]
  (str "HTTP/1.1 " status " " (get reasons status) "\r\n"
       (when (= 426 status) "Sec-WebSocket-Version: 13\r\n")
       "Connection: close\r\nContent-Length: 0\r\n\r\n"))


(defn- refuse
  [status reason]
  {:outcome :refuse :status status :reason reason
   :response (refusal-response status)})


(defn server-response
  "Decide an upgrade request (from `parse-request`).  `opts`: `:sha1`
   (required) and `:codecs`, the endpoint's codec table (when given, an
   offer sharing no subprotocol is a 400).  Answers
   `{:outcome :accept :response head-text :subprotocol codec-or-nil}` or
   `{:outcome :refuse :status :reason :response head-text}`.  Extensions the
   client offers are ignored and none is negotiated."
  [request {:keys [sha1 codecs]}]
  (let [headers (:headers request)
        once? (fn [name] (= 1 (get (:counts request) name 0)))
        key (get headers "sec-websocket-key")
        key-bytes (when key (base64/decode key))]
    (cond
      (:error request) (refuse 400 :malformed)
      (not= "GET" (:method request)) (refuse 400 :method)
      (not= "HTTP/1.1" (:version request)) (refuse 400 :http-version)
      (not (and (once? "host") (host-authority? (get headers "host")))) (refuse 400 :host)
      (not (has-token? headers "connection" "upgrade")) (refuse 400 :connection)
      (not (has-token? headers "upgrade" "websocket")) (refuse 400 :upgrade)
      (not (and (once? "sec-websocket-key") key-bytes (= 16 (bytes/length key-bytes))))
      (refuse 400 :key)
      (> (get (:counts request) "sec-websocket-version" 0) 1) (refuse 400 :version-repeated)
      (not= "13" (get headers "sec-websocket-version")) (refuse 426 :version)
      :else
      (let [chosen (when (seq codecs)
                     (negotiate codecs (offered-subprotocols request)))]
        (if (and (seq codecs) (nil? chosen))
          (refuse 400 :subprotocol)
          {:outcome :accept
           :subprotocol chosen
           :response (str "HTTP/1.1 101 Switching Protocols\r\n"
                          "Upgrade: websocket\r\n"
                          "Connection: Upgrade\r\n"
                          "Sec-WebSocket-Accept: " (accept-key key sha1) "\r\n"
                          (when chosen
                            (str "Sec-WebSocket-Protocol: " (codec/codec-name chosen) "\r\n"))
                          "\r\n")})))))


(defn- visible-ascii?
  "True when every character of `s` is printable ASCII other than space."
  [s]
  (every? #(and (not= " " %) (str/includes? ascii-printable %)) (map str (seq s))))


(defn- request-authority
  "`host:port` with an IPv6 literal bracketed exactly once, or nil when
   `host` or `port` is not valid."
  [host port]
  (let [bare (if (and (string? host) (str/starts-with? host "[") (str/ends-with? host "]"))
               (subs host 1 (dec (count host)))
               host)
        ipv6? (and (string? bare) (str/includes? bare ":"))
        hostpart (if ipv6? (str "[" bare "]") bare)]
    (when (and (integer? port) (<= 1 port 65535)
               (string? bare) (seq bare)
               (re-matches host-pattern hostpart))
      (str hostpart ":" port))))


(defn client-request
  "`{:head text :key key}`: the upgrade request for `host`, `port`, `path`
   with `:key` (from `client-key`) and `:subprotocols` (names, offered in
   order).  No extension is offered.  Every field is checked against its
   grammar before serialization and an invalid one throws: a path is an
   absolute request target of visible ASCII with no fragment (so no space or
   control character can end the request line), the host is a name, IPv4 or
   IPv6 literal (brackets are added exactly once), the key decodes to sixteen
   bytes, and a subprotocol is a token."
  [{:keys [host port path key subprotocols]}]
  (let [authority (request-authority host port)
        key-bytes (when (string? key) (base64/decode key))]
    (when-not authority
      (throw (ex-info "invalid upgrade host or port" {:reason :host})))
    (when-not (and (string? path) (str/starts-with? path "/") (visible-ascii? path)
                   (not (str/includes? path "#")))
      (throw (ex-info "invalid upgrade request target" {:reason :path})))
    (when-not (and key-bytes (= 16 (bytes/length key-bytes)))
      (throw (ex-info "invalid upgrade key" {:reason :key})))
    (when-not (every? token? subprotocols)
      (throw (ex-info "invalid subprotocol token" {:reason :subprotocol})))
    {:key key
     :head (str "GET " path " HTTP/1.1\r\n"
                "Host: " authority "\r\n"
                "Upgrade: websocket\r\n"
                "Connection: Upgrade\r\n"
                "Sec-WebSocket-Key: " key "\r\n"
                "Sec-WebSocket-Version: 13\r\n"
                (when (seq subprotocols)
                  (str "Sec-WebSocket-Protocol: " (str/join ", " subprotocols) "\r\n"))
                "\r\n")}))


(defn check-response
  "Judge a response head (from `parse-response`) against the request's `:key`
   and `:offered` subprotocol names, with the injected `:sha1`.  Answers
   `{:outcome :ok :subprotocol name-or-nil}` or
   `{:outcome :failed :reason kw}`.  Any `Sec-WebSocket-Extensions` header
   is an unsolicited extension and fails the client."
  [response {:keys [key offered sha1]}]
  (let [headers (:headers response)
        protocol (get headers "sec-websocket-protocol")]
    (cond
      (:error response) {:outcome :failed :reason :malformed}
      (= 426 (:status response)) {:outcome :failed :reason :version-rejected}
      (not= 101 (:status response)) {:outcome :failed :reason :status}
      (not (has-token? headers "upgrade" "websocket")) {:outcome :failed :reason :upgrade}
      (not (has-token? headers "connection" "upgrade")) {:outcome :failed :reason :connection}
      (not (and (= 1 (get (:counts response) "sec-websocket-accept" 0))
                (= (accept-key key sha1) (get headers "sec-websocket-accept"))))
      {:outcome :failed :reason :accept}
      (contains? headers "sec-websocket-extensions")
      {:outcome :failed :reason :unsolicited-extension}
      (and protocol (not (some #{protocol} offered)))
      {:outcome :failed :reason :unsolicited-subprotocol}
      :else {:outcome :ok :subprotocol protocol})))


;; =============================================================================
;; Canonical request-target paths (RFC 3986 section 6.2.2 order)
;; =============================================================================

(def ^:private hex-digits "0123456789ABCDEF")


(def ^:private unreserved
  (set (map str (seq "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"))))


(defn- hex-value
  [c]
  (str/index-of hex-digits (str/upper-case c)))


(defn- normalize-escapes
  "Decode percent-encoded unreserved octets, upper-case the rest; nil on a
   malformed escape."
  [s]
  (let [n (count s)]
    (loop [i 0 out []]
      (if (>= i n)
        (str/join out)
        (let [c (subs s i (inc i))]
          (if-not (= "%" c)
            (recur (inc i) (conj out c))
            (let [hi (when (< (+ i 2) n) (hex-value (subs s (+ i 1) (+ i 2))))
                  lo (when (< (+ i 2) n) (hex-value (subs s (+ i 2) (+ i 3))))]
              (if-not (and hi lo)
                nil
                (let [code (+ (* 16 hi) lo)
                      decoded (when (<= 32 code 126)
                                (subs ascii-printable (- code 32) (- code 31)))]
                  (recur (+ i 3)
                         (conj out (if (contains? unreserved decoded)
                                     decoded
                                     (str "%" (str/upper-case (subs s (+ i 1) (+ i 3))))))))))))))))


(defn- split-slashes
  [s]
  (loop [from 0 out []]
    (if-let [at (str/index-of s "/" from)]
      (recur (inc at) (conj out (subs s from at)))
      (conj out (subs s from)))))


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
  "Canonicalize an HTTP request target for exact served-path lookup: drop
   query and fragment, decode percent-encoded unreserved octets (the rest
   upper-cased), then remove dot segments.  So `/a/%2e%2e/b` is `/b` and
   `/a%2Fb` stays one segment; repeated slashes are preserved.  Empty is `/`;
   a malformed escape or a non-absolute target is nil."
  [target]
  (when (string? target)
    (let [cut (->> [(str/index-of target "?") (str/index-of target "#")]
                   (filter some?)
                   (reduce min (count target)))
          path (subs target 0 cut)]
      (if (= "" path)
        "/"
        (when (str/starts-with? path "/")
          (when-let [decoded (normalize-escapes path)]
            (str "/" (str/join "/" (remove-dot-segments
                                     (split-slashes (subs decoded 1)))))))))))
