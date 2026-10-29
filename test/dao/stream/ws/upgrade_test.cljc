(ns dao.stream.ws.upgrade-test
  "The pure upgrade core: the RFC 6455 handshake known answer, header
   parsing rules, 400/426/431 refusals, client response checking including
   an unsolicited extension, the incremental 8 KiB head cap, and the
   canonical request-target cases on every lane.  SHA-1 is injected; the
   tests carry a small portable SHA-1 checked against RFC 3174."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.host.bytes :as bytes]
            [dao.host.text :as text]
            [dao.stream.ws.upgrade :as up]))


;; -- A test-only portable SHA-1 (32-bit words held as non-negative numbers) --

(defn- u32
  [x]
  (mod x 4294967296))


(defn- rol
  [n x]
  ;; Mask before multiplying so every intermediate stays below 2^32, inside
  ;; the integers a JavaScript number holds exactly.
  (let [low (apply * (repeat (- 32 n) 2))]
    (+ (* (mod x low) (apply * (repeat n 2))) (quot x low))))


(defn- sha1-bytes
  [bs]
  (let [msg (bytes/to-vector bs)
        n (count msg)
        padlen (mod (- 55 n) 64)
        bits (* 8 n)
        padded (vec (concat msg [0x80] (repeat padlen 0)
                            (map #(mod (quot bits %) 256)
                                 [72057594037927936 281474976710656 1099511627776
                                  4294967296 16777216 65536 256 1])))
        word (fn [block i]
               (let [j (+ (* 64 block) (* 4 i))]
                 (+ (* 16777216 (nth padded j)) (* 65536 (nth padded (+ j 1)))
                    (* 256 (nth padded (+ j 2))) (nth padded (+ j 3)))))
        step (fn [[h0 h1 h2 h3 h4] block]
               (let [w (reduce (fn [w t]
                                 (conj w (rol 1 (u32 (bit-xor (nth w (- t 3)) (nth w (- t 8))
                                                              (nth w (- t 14)) (nth w (- t 16)))))))
                               (mapv #(word block %) (range 16))
                               (range 16 80))]
                 (loop [t 0 a h0 b h1 c h2 d h3 e h4]
                   (if (= t 80)
                     [(u32 (+ h0 a)) (u32 (+ h1 b)) (u32 (+ h2 c)) (u32 (+ h3 d)) (u32 (+ h4 e))]
                     (let [[f k] (cond
                                   (< t 20) [(u32 (bit-or (bit-and b c) (bit-and (- 4294967295 b) d))) 0x5A827999]
                                   (< t 40) [(u32 (bit-xor b c d)) 0x6ED9EBA1]
                                   (< t 60) [(u32 (bit-or (bit-and b c) (bit-and b d) (bit-and c d))) 0x8F1BBCDC]
                                   :else [(u32 (bit-xor b c d)) 0xCA62C1D6])
                           tmp (u32 (+ (rol 5 a) f e k (nth w t)))]
                       (recur (inc t) tmp a (rol 30 b) c d))))))
        hs (reduce step [0x67452301 0xEFCDAB89 0x98BADCFE 0x10325476 0xC3D2E1F0]
                   (range (quot (count padded) 64)))]
    (bytes/from-vector (vec (mapcat (fn [h] (map #(mod (quot h %) 256) [16777216 65536 256 1])) hs)))))


(defn- hex
  [bs]
  (str/join (map (fn [b]
                   (str (subs "0123456789abcdef" (quot b 16) (inc (quot b 16)))
                        (subs "0123456789abcdef" (mod b 16) (inc (mod b 16)))))
                 (bytes/to-vector bs))))


(defn- utf8
  [s]
  (text/utf8-encode s))


(defn- head-bytes
  [s]
  (utf8 s))


(deftest test-sha1-is-correct
  (is (= "a9993e364706816aba3e25717850c26c9cd0d89d" (hex (sha1-bytes (utf8 "abc")))))
  (is (= "da39a3ee5e6b4b0d3255bfef95601890afd80709" (hex (sha1-bytes (utf8 ""))))))


(def ^:private sample-key "dGhlIHNhbXBsZSBub25jZQ==")


(def ^:private sample-request
  (str "GET /chat HTTP/1.1\r\n"
       "Host: server.example.com\r\n"
       "Upgrade: websocket\r\n"
       "Connection: Upgrade\r\n"
       "Sec-WebSocket-Key: " sample-key "\r\n"
       "Origin: http://example.com\r\n"
       "Sec-WebSocket-Protocol: chat, superchat\r\n"
       "Sec-WebSocket-Version: 13\r\n"
       "\r\n"))


(defn- decide
  ([head] (decide head nil))
  ([head codecs]
   (up/server-response (up/parse-request head) {:sha1 sha1-bytes :codecs codecs})))


(deftest handshake-known-answer
  (is (= "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=" (up/accept-key sample-key sha1-bytes)))
  (let [r (decide sample-request)]
    (is (= :accept (:outcome r)))
    (is (str/starts-with? (:response r) "HTTP/1.1 101 Switching Protocols\r\n"))
    (is (str/includes? (:response r) "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n"))
    (is (str/ends-with? (:response r) "\r\n\r\n"))))


(deftest header-rules
  (testing "names are case-insensitive and Connection is a token list"
    (let [r (decide (str "GET / HTTP/1.1\r\nHOST: h\r\nupgrade: WebSocket\r\n"
                         "CONNECTION: keep-alive, Upgrade\r\n"
                         "sec-websocket-key: " sample-key "\r\n"
                         "SEC-WEBSOCKET-VERSION: 13\r\n\r\n"))]
      (is (= :accept (:outcome r)))))
  (testing "refusals"
    (let [base (fn [& drops]
                 (str "GET / HTTP/1.1\r\n"
                      (apply str (for [[k v] [["Host" "h"] ["Upgrade" "websocket"] ["Connection" "Upgrade"]
                                              ["Sec-WebSocket-Key" sample-key]
                                              ["Sec-WebSocket-Version" "13"]]
                                       :when (not (some #{k} drops))]
                                   (str k ": " v "\r\n")))
                      "\r\n"))]
      (is (= :accept (:outcome (decide (base)))))
      (doseq [k ["Host" "Upgrade" "Connection" "Sec-WebSocket-Key"]]
        (let [r (decide (base k))]
          (is (= :refuse (:outcome r)) k)
          (is (= 400 (:status r)) k)))
      (let [r (decide (base "Sec-WebSocket-Version"))]
        (is (= 426 (:status r))))))
  (testing "426 for another version, naming 13"
    (let [r (decide (str/replace sample-request "Version: 13" "Version: 8"))]
      (is (= 426 (:status r)))
      (is (str/includes? (:response r) "Sec-WebSocket-Version: 13"))))
  (testing "a key that does not decode to sixteen bytes"
    (is (= 400 (:status (decide (str/replace sample-request sample-key "AAAA"))))))
  (testing "method, version and syntax"
    (is (= 400 (:status (decide (str/replace sample-request "GET /chat" "POST /chat")))))
    (is (= 400 (:status (decide (str/replace sample-request "HTTP/1.1" "HTTP/1.0")))))
    (is (= 400 (:status (decide "GARBAGE\r\n\r\n"))))
    (is (= 400 (:status (decide "GET / HTTP/1.1\r\nno colon here\r\n\r\n")))))
  (testing "extensions are ignored and none is negotiated"
    (let [r (decide (str/replace sample-request "Origin:"
                                 "Sec-WebSocket-Extensions: permessage-deflate\r\nOrigin:"))]
      (is (= :accept (:outcome r)))
      (is (not (str/includes? (str/lower-case (:response r)) "extensions"))))))


(deftest subprotocol-negotiation
  (let [cbor {:dao.stream.codec/name "dao.stream.cbor"}
        req (fn [offer]
              (str "GET / HTTP/1.1\r\nHost: h\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                   "Sec-WebSocket-Key: " sample-key "\r\nSec-WebSocket-Version: 13\r\n"
                   (when offer (str "Sec-WebSocket-Protocol: " offer "\r\n"))
                   "\r\n"))
        r (decide (req "x, dao.stream.cbor") [cbor])]
    (is (= cbor (:subprotocol r)))
    (is (str/includes? (:response r) "Sec-WebSocket-Protocol: dao.stream.cbor\r\n"))
    (is (= 400 (:status (decide (req "other") [cbor]))))
    (is (= 400 (:status (decide (req nil) [cbor]))))
    (is (= [cbor] [(up/negotiate [cbor] ["dao.stream.cbor"])]))
    (is (= ["a" "b"] (up/offered-subprotocols {:headers {"sec-websocket-protocol" " a , b ,"}})))
    (is (= [] (up/offered-subprotocols {})))))


(deftest client-side
  (let [k (up/client-key (bytes/from-vector (vec (range 16))))
        {:keys [head key]} (up/client-request {:host "h" :port 80 :path "/repl" :key k
                                               :subprotocols ["dao.stream.cbor"]})
        ok (str "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                "Connection: Upgrade\r\nSec-WebSocket-Accept: " (up/accept-key key sha1-bytes)
                "\r\nSec-WebSocket-Protocol: dao.stream.cbor\r\n\r\n")
        judge (fn [response]
                (up/check-response (up/parse-response response)
                                   {:key key :offered ["dao.stream.cbor"] :sha1 sha1-bytes}))]
    (is (str/starts-with? head "GET /repl HTTP/1.1\r\n"))
    (is (str/includes? head "Sec-WebSocket-Version: 13\r\n"))
    (is (not (str/includes? head "Extensions")))
    (testing "the request is a valid upgrade to our own server side"
      (is (= :accept (:outcome (decide head)))))
    (is (= {:outcome :ok :subprotocol "dao.stream.cbor"} (judge ok)))
    (testing "unsolicited extension in a 101 fails the client"
      (is (= {:outcome :failed :reason :unsolicited-extension}
             (judge (str/replace ok "\r\n\r\n"
                                 "\r\nSec-WebSocket-Extensions: permessage-deflate\r\n\r\n")))))
    (testing "failures"
      (is (= :accept (:reason (judge (str/replace ok "Accept: " "Accept: x")))))
      (is (= :version-rejected (:reason (judge "HTTP/1.1 426 Upgrade Required\r\n\r\n"))))
      (is (= :status (:reason (judge "HTTP/1.1 200 OK\r\n\r\n"))))
      (is (= :malformed (:reason (judge "junk\r\n\r\n"))))
      (is (= :unsolicited-subprotocol
             (:reason (judge (str/replace ok "dao.stream.cbor\r\n" "other\r\n"))))))))


(deftest incremental-head
  (testing "a head fed in arbitrary chunks, with the bytes after it returned"
    (let [wire (head-bytes (str sample-request "XY"))
          n (bytes/length wire)
          hn (count sample-request)]
      (doseq [k [1 2 7 50 (dec n) (- n 2) hn]]
        (let [[p1 r1] (up/head-feed (up/head-parser) (bytes/slice wire 0 k))
              [_ r2] (up/head-feed p1 (bytes/slice wire k n))
              r (or r1 r2)]
          (is (= sample-request (:head r)) (str "split " k))
          ;; whatever follows the blank line within the completing chunk
          (is (= (if (>= k hn) (subvec [0x58 0x59] 0 (- k hn)) [0x58 0x59])
                 (bytes/to-vector (:rest r))))))))
  (testing "a 9 KiB head is refused at the cap, not after"
    (let [chunk (utf8 (apply str (repeat 1024 "a")))]
      (loop [p (up/head-parser) i 1]
        (let [[p' r] (up/head-feed p chunk)]
          (cond
            r (do (is (= {:refused :head-too-large} r))
                  (is (= 9 i) "refused while reading the ninth KiB")
                  (is (= [p' nil] (up/head-feed p' chunk))))
            (< i 12) (recur p' (inc i))
            :else (is false "never refused"))))))
  (testing "exactly 8192 bytes ending in the blank line is accepted"
    (let [filler (apply str (repeat (- 8192 (count "GET / HTTP/1.1\r\nX: \r\n\r\n")) "a"))
          head (str "GET / HTTP/1.1\r\nX: " filler "\r\n\r\n")
          [_ r] (up/head-feed (up/head-parser) (utf8 head))]
      (is (= 8192 (count head)))
      (is (= head (:head r)))))
  (testing "non-ASCII header bytes are malformed"
    (let [[_ r] (up/head-feed (up/head-parser) (bytes/from-vector [71 0xc3 0xa9 13 10 13 10]))]
      (is (= {:refused :malformed} r)))))


(deftest canonical-paths
  (let [c up/canonical-path]
    (is (= "/repl" (c "/a/../repl")))
    (is (= "/a/repl" (c "/a/./repl")))
    (is (= "/a//repl/" (c "/a//repl/")))
    (is (= "/a//b/" (c "/a//b/")))
    (is (= "/" (c "/a/..")))
    (is (= "/" (c "/..")))
    (is (= "/x" (c "/../../x")))
    (is (= "/repl/" (c "/repl/.")))
    (is (= "/a/" (c "/a/b/..")))
    (is (= "/repl-x" (c "/%72epl%2Dx")))
    (is (= "/a%2Fb" (c "/a%2fb")))
    (is (= "/a%20b" (c "/a%20b")))
    (is (= "/repl" (c "/repl?x=1#y")))
    (is (= "/repl/" (c "/repl/?x=1")))
    (is (= "/repl/~user/x/" (c "/repl/%7euser/a/../x/./?ignored=1")))
    (is (= "/repl/%C3%A4" (c "/repl/%c3%a4")))
    (is (= "/" (c "")))
    (is (= "/" (c "?x")))
    (testing "encoded dots decode before dot segments are removed"
      (is (= "/b" (c "/a/%2e%2e/b")))
      (is (= "/x" (c "/%2e%2e/x")))
      (is (= "/a/b" (c "/a/%2E/b"))))
    (testing "an encoded slash is one segment"
      (is (= "/a%2Fb/c" (c "/a%2Fb/c")))
      (is (= "/a%2F../c" (c "/a%2F../c"))))
    (testing "malformed escapes and relative or non-string targets are nil"
      (is (nil? (c "%zz")))
      (is (nil? (c "/bad/%xy")))
      (is (nil? (c "/repl/%E")))
      (is (nil? (c "/repl/%")))
      (is (nil? (c "relative")))
      (is (nil? (c "yin/repl")))
      (is (nil? (c 42)))
      (is (nil? (c nil))))))


(defn- request-with
  "A valid request with `extra` header text spliced in."
  [extra]
  (str "GET / HTTP/1.1\r\nHost: h\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
       "Sec-WebSocket-Key: " sample-key "\r\nSec-WebSocket-Version: 13\r\n"
       extra "\r\n"))


(deftest strict-head-syntax
  (is (= :accept (:outcome (decide (request-with "")))))
  (testing "field names are tokens"
    (doseq [bad ["X Y: a\r\n" "X\tY: a\r\n" "(x): a\r\n" ": a\r\n" "X@: a\r\n"]]
      (is (= 400 (:status (decide (request-with bad)))) bad)))
  (testing "residual CR or LF inside a line is malformed"
    (doseq [bad ["X\rInjected: yes\r\n" "X: a\nInjected: yes\r\n" "X: a\rb\r\n"]]
      (is (= {:error :malformed} (up/parse-request (request-with bad))) bad)))
  (testing "a response is held to the same rules"
    (is (= {:error :malformed}
           (up/parse-response "HTTP/1.1 101 Switching Protocols\r\nX\rY: 1\r\n\r\n")))
    (is (= {:error :malformed}
           (up/parse-response "HTTP/1.1 101 Switching Protocols\r\nX Y: 1\r\n\r\n")))))


(deftest singleton-fields-and-version
  (testing "exactly one valid Host"
    (is (= :host (:reason (decide (str/replace (request-with "") "Host: h\r\n" "")))))
    (is (= :host (:reason (decide (request-with "Host: other\r\n")))))
    (is (= :host (:reason (decide (str/replace (request-with "") "Host: h" "Host: a b")))))
    (is (= :host (:reason (decide (str/replace (request-with "") "Host: h" "Host: ::1:80")))))
    (is (= :accept (:outcome (decide (str/replace (request-with "") "Host: h"
                                                  "Host: [::1]:80")))))
    (is (= :accept (:outcome (decide (str/replace (request-with "") "Host: h"
                                                  "Host: example.org:8080"))))))
  (testing "a duplicated key is refused"
    (is (= 400 (:status (decide (request-with (str "Sec-WebSocket-Key: " sample-key "\r\n")))))))
  (testing "version: exactly 13, once"
    (is (= 400 (:status (decide (request-with "Sec-WebSocket-Version: 8\r\n")))))
    (is (= 400 (:status (decide (request-with "Sec-WebSocket-Version: 13\r\n")))))
    (is (= 426 (:status (decide (str/replace (request-with "") "Version: 13" "Version: 13, 8")))))
    (is (= 426 (:status (decide (str/replace (request-with "") "Version: 13" "Version: 8")))))))


(deftest client-request-is-validated
  (let [key (up/client-key (bytes/from-vector (vec (range 16))))
        build (fn [m]
                (up/client-request (merge {:host "h" :port 80 :path "/repl" :key key
                                           :subprotocols ["dao.stream.cbor"]}
                                          m)))
        throws? (fn [m]
                  (try (build m) false
                       (catch #?(:cljd Object :clj Exception :cljs :default) _ true)))]
    (testing "header injection through the path is refused"
      (is (throws? {:path "/ HTTP/1.1\r\nX-Injected: yes\r\nX: "}))
      (is (throws? {:path "/a b"}))
      (is (throws? {:path "/a\r\nX: y"}))
      (is (throws? {:path "/a#frag"}))
      (is (throws? {:path "relative"})))
    (testing "host, port, key and subprotocols are checked"
      (is (throws? {:host "h\r\nX: y"}))
      (is (throws? {:host "a b"}))
      (is (throws? {:host ""}))
      (is (throws? {:port 0}))
      (is (throws? {:port "80"}))
      (is (throws? {:key "AAAA"}))
      (is (throws? {:key (str key "\r\n")}))
      (is (throws? {:subprotocols ["a\r\nX: y"]}))
      (is (throws? {:subprotocols ["a b"]})))
    (testing "a valid request is a valid upgrade to the server side"
      (is (= :accept (:outcome (decide (:head (build {}))))))
      (is (= :accept (:outcome (decide (:head (build {:path "/a%2Fb?x=1"})))))))
    (testing "IPv6 literals are bracketed exactly once"
      (is (str/includes? (:head (build {:host "::1" :port 80})) "Host: [::1]:80\r\n"))
      (is (str/includes? (:head (build {:host "[::1]" :port 80})) "Host: [::1]:80\r\n"))
      (is (not (str/includes? (:head (build {:host "[::1]" :port 80})) "[[")))
      (is (str/includes? (:head (build {:host "127.0.0.1" :port 8080})) "Host: 127.0.0.1:8080\r\n"))
      (is (= :accept (:outcome (decide (:head (build {:host "::1" :port 80})))))))))
