;; Run under jolt -Srepro -M:jolt by probes/run.clj (J6c). Loads
;; dao.stream.ws.crypto (whose require of dao.host.hash runs the layout and
;; provenance gate first), then checks the SHA-1 known answers and the
;; masking-key draw. Prints PASS/FAIL per check, GUEST-WORK before the first
;; digest or draw, LOADED once loading succeeded and SUMMARY last; exits
;; nonzero on any FAIL.
;;
;; With the argument `wrong-first <path>` it first loads another libcrypto
;; image at <path>, as provenance.clj does.
(ns ws-crypto
  (:require [jolt.ffi :as ffi]))


(let [[mode path] *command-line-args*]
  (when (= "wrong-first" mode)
    (ffi/load-library path)
    (println "RECORD loaded first" path)))


(require '[dao.stream.ws.crypto :as crypto] '[dao.host.bytes :as bytes]
         '[dao.host.text :as text] '[dao.stream.base64 :as base64])


(println "LOADED")
(println "GUEST-WORK")


(def failures (atom 0))


(defn- check
  [label ok? detail]
  (when-not ok? (swap! failures inc))
  (println (if ok? "PASS" "FAIL") label (pr-str detail)))


(defn- hex
  [bs]
  (apply str (map #(str (nth "0123456789abcdef" (quot % 16)) (nth "0123456789abcdef" (rem % 16)))
                  (bytes/to-vector bs))))


;; RFC 3174 §7.3 TEST1, TEST2 and TEST3, and the empty input
(doseq [[label input expected]
        [["RFC 3174 TEST1 \"abc\"" (text/utf8-encode "abc")
          "a9993e364706816aba3e25717850c26c9cd0d89d"]
         ["RFC 3174 TEST2 (448-bit message)"
          (text/utf8-encode "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")
          "84983e441c3bd26ebaae4aa1f95129e5e54670f1"]
         ["RFC 3174 TEST3 (one million \"a\")" (bytes/from-vector (vec (repeat 1000000 97)))
          "34aa973cd4c4daa4f61eeb2bdbad27316534016f"]
         ["empty input" (bytes/alloc 0) "da39a3ee5e6b4b0d3255bfef95601890afd80709"]]]
  (let [before (bytes/to-vector input)
        digest (crypto/sha1 input)]
    (check (str "sha1 " label)
           (and (= expected (hex digest)) (= 20 (bytes/length digest))
                (= before (bytes/to-vector input)))
           {:digest (hex digest)})))


;; RFC 6455 §1.3: the handshake's Sec-WebSocket-Accept known answer
(let [accept (base64/encode (crypto/sha1 (text/utf8-encode
                                           (str "dGhlIHNhbXBsZSBub25jZQ=="
                                                "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))))]
  (check "sha1 handshake KAT: Sec-WebSocket-Accept" (= "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=" accept)
         {:accept accept}))


(check "sha1 refuses a non-bytes argument"
       ;; the wrong type is the point of this check; identity keeps clj-kondo's
       ;; type inference (which would flag a string literal) out of it
       (= :threw (try (crypto/sha1 (identity "abc")) :returned (catch Exception _ :threw)))
       {})


;; masking keys: four fresh bytes per call, from the qualified CSPRNG
(let [keys (repeatedly 64 crypto/mask-key)]
  (check "mask-key: four bytes per draw, fresh host bytes each call"
         (and (every? #(and (bytes/bytes? %) (= 4 (bytes/length %))) keys)
              ;; 64 uniform 32-bit draws repeat with probability < 2^-20
              (<= 63 (count (distinct (map bytes/to-vector keys)))))
         {:distinct (count (distinct (map bytes/to-vector keys)))}))


(println "SUMMARY" (if (zero? @failures) "ok" (str @failures " failed")))
(System/exit (if (zero? @failures) 0 1))
