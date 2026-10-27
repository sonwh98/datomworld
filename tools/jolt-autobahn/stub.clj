;; TEST PLUMBING ONLY. Not dao.stream.ws, not the J6 adapter.
;; This deliberately uses jolt.socket's shim, forbidden in the production
;; adapter. It supports only the four smoke cases, not general RFC 6455.
(require '[jolt.socket] '[clojure.string :as str])
(import '[java.net Socket ServerSocket InetSocketAddress])

(defn u32 [x] (bit-and x 4294967295))
(defn rol [x n]
  (u32 (bit-or (bit-shift-left x n) (unsigned-bit-shift-right x (- 32 n)))))

(defn sha1 [s]
  ;; Test-only ASCII opening-handshake digest. Not a production crypto path.
  (let [bs (mapv int s)
        size (* 64 (quot (+ (count bs) 72) 64))
        bits (* 8 (count bs))
        padded (into (into (conj bs 128) (repeat (- size (count bs) 9) 0))
                     (map #(bit-and 255 (unsigned-bit-shift-right bits %)) (range 56 -1 -8)))
        h (reduce
           (fn [h block]
             (let [w0 (mapv (fn [xs] (reduce #(+ (* 256 %1) %2) 0 xs)) (partition 4 block))
                   w (reduce (fn [v i] (conj v (rol (bit-xor (v (- i 3)) (v (- i 8))
                                                             (v (- i 14)) (v (- i 16))) 1))) w0 (range 16 80))
                   end (reduce (fn [[a b c d e] i]
                                 (let [[f k] (cond (< i 20) [(bit-or (bit-and b c) (bit-and (bit-not b) d)) 1518500249]
                                                   (< i 40) [(bit-xor b c d) 1859775393]
                                                   (< i 60) [(bit-or (bit-and b c) (bit-and b d) (bit-and c d)) 2400959708]
                                                   :else [(bit-xor b c d) 3395469782])]
                                   [(u32 (+ (rol a 5) f e k (w i))) a (rol b 30) c d])) h (range 80))]
               (mapv #(u32 (+ %1 %2)) h end)))
           [1732584193 4023233417 2562383102 271733878 3285377520] (partition 64 padded))]
    (vec (mapcat (fn [x] (map #(bit-and 255 (unsigned-bit-shift-right x %)) [24 16 8 0])) h))))

(def alphabet "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")
(defn b64 [bs]
  (apply str (mapcat (fn [chunk]
                      (let [[a b c] (concat chunk [0 0]) n (count chunk)
                            x (+ (* a 65536) (* b 256) c)]
                        [(nth alphabet (bit-and 63 (bit-shift-right x 18)))
                         (nth alphabet (bit-and 63 (bit-shift-right x 12)))
                         (if (> n 1) (nth alphabet (bit-and 63 (bit-shift-right x 6))) \=)
                         (if (> n 2) (nth alphabet (bit-and 63 x)) \=)])) (partition-all 3 bs))))
(defn accept-key [key] (b64 (sha1 (str key "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))))
(assert (= "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=" (accept-key "dGhlIHNhbXBsZSBub25jZQ==")))

(defn octet [in]
  (let [b (.read in)] (if (= -1 b) (throw (ex-info "EOF" {:eof true})) b)))
(defn read-n [in n] (vec (repeatedly n #(octet in))))
(defn emit [out xs] (doseq [b xs] (.write out (int b))) (.flush out))
(defn header [in]
  (loop [bs []]
    (when (> (count bs) 8192) (throw (ex-info "stub header limit" {})))
    (if (and (>= (count bs) 4) (= [13 10 13 10] (subvec bs (- (count bs) 4))))
      (apply str (map char bs))
      (recur (conj bs (octet in))))))
(defn write-frame [out client? opcode payload]
  (let [n (count payload) flag (if client? 128 0) mask [17 34 51 68]]
    (when (> n 65535) (throw (ex-info "stub payload limit" {})))
    (emit out (concat [(+ 128 opcode)] (if (< n 126) [(+ flag n)]
                                       [(+ flag 126) (quot n 256) (mod n 256)])
                      (when client? mask)
                      (if client? (map-indexed #(bit-xor %2 (nth mask (mod %1 4))) payload) payload)))))
(defn echo-loop [socket client?]
  (let [in (.getInputStream socket) out (.getOutputStream socket)]
    (loop []
      (let [a (octet in) b (octet in) op (bit-and a 15) len (bit-and b 127)
            n (cond (< len 126) len (= len 126) (+ (* 256 (octet in)) (octet in))
                    :else (throw (ex-info "stub length unsupported" {})))
            mask (when (pos? (bit-and b 128)) (read-n in 4))
            data (read-n in n)
            payload (if mask (mapv #(bit-xor %2 (nth mask (mod %1 4))) (range n) data) data)]
        (when (zero? (bit-and a 128)) (throw (ex-info "stub fragmentation unsupported" {})))
        (case op
          1 (write-frame out client? 1 payload)
          2 (write-frame out client? 2 payload)
          9 (write-frame out client? 10 payload)
          8 (write-frame out client? 8 payload)
          (throw (ex-info "stub opcode unsupported" {:opcode op})))
        (when (not= op 8) (recur))))))

(defn server-session [socket]
  (try
    (let [h (header (.getInputStream socket))
          key (second (re-find #"(?i)Sec-WebSocket-Key: *([^\r\n]+)" h))]
      (when-not key (throw (ex-info "missing key" {})))
      (emit (.getOutputStream socket)
            (map int (str "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                          (accept-key key) "\r\n\r\n")))
      (echo-loop socket false))
    (catch Exception e (when-not (:eof (ex-data e)) (println "stub session error" (str e))))
    (finally (.close socket))))
(defn client-session [port path]
  (let [socket (Socket. "127.0.0.1" port)]
    (try
      (emit (.getOutputStream socket)
            (map int (str "GET " path " HTTP/1.1\r\nHost: 127.0.0.1:" port
                          "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n")))
      (let [h (header (.getInputStream socket))]
        (when-not (and (str/starts-with? h "HTTP/1.1 101")
                       (str/includes? h "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="))
          (throw (ex-info "stub handshake refused" {:header h}))))
      (echo-loop socket true)
      (finally (.close socket)))))

(when-not (= "smoke" (System/getenv "J6_MODE"))
  (throw (ex-info "STUB PLUMBING ONLY: requires J6_MODE=smoke" {})))
(let [port (Integer/parseInt (System/getenv "J6_PORT"))]
  (println "STUB PLUMBING ONLY; no dao.stream or RFC 6455 conformance claim")
  (if (= "server" (System/getenv "J6_ROLE"))
    (let [server (ServerSocket.)]
      (.bind server (InetSocketAddress. "127.0.0.1" port))
      (loop [] (server-session (.accept server)) (recur)))
    (let [agent (System/getenv "J6_AGENT")]
      (when-not (re-matches #"[A-Za-z0-9_-]+" agent) (throw (ex-info "stub agent must be URL-safe" {})))
      (doseq [index (str/split (str/trim (slurp (System/getenv "J6_CASE_INDEXES"))) #",")]
        (client-session port (str "/runCase?case=" index "&agent=" agent)))
      (client-session port (str "/updateReports?agent=" agent)))))
