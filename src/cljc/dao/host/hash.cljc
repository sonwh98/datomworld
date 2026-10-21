(ns dao.host.hash
  "The cryptographic hash functions of the primitive profile
   (docs/design/dao.stream.host-boundary.md §4.2, table A).

   P entries (§2.4): `blake3-bytes` and `sha256-bytes`. They take host
   bytes (byte[] on JVM, Uint8Array on JS, Uint8List on Dart) and return
   fresh host bytes representing the 32-byte digest (256 bits). They leave
   their arguments unmodified.

   String digesting is a table B portable composition over `dao.host.text`
   and these byte primitives (`blake3`, `sha256`, `digest-string`,
   `digest-bytes`), hosted in `dao.jing`."
  (:require
    [dao.host.bytes :as bytes]
    #?@(:cljd [["package:blake3_dart/blake3_dart.dart" :as blake3-dart]
               ["package:crypto/crypto.dart" :as crypto]])
    #?@(:cljs [["@noble/hashes/blake3.js" :as noble-blake3]
               ["@noble/hashes/sha2.js" :as noble-sha256]]))
  #?(:cljd (:import ["dart:typed_data" Uint8List])
     :clj  (:import [io.github.rctcwyvrn.blake3 Blake3]
                    [java.security MessageDigest])))


(defn- check-bytes!
  [entry bs]
  (when-not (bytes/bytes? bs)
    (throw (ex-info (str "dao.host refused: " entry " unsupported-value (expected host bytes)")
                    {:dao.host/refusal :unsupported-value
                     :dao.host/entry entry}))))


(defn blake3-bytes
  "P. 32-byte BLAKE3 digest of host bytes `bs`, returning fresh host bytes."
  [bs]
  (check-bytes! 'blake3-bytes bs)
  #?(:cljd (let [^Uint8List u8 (if (instance? Uint8List bs)
                                 bs
                                 (Uint8List.fromList bs))]
             (Uint8List.fromList (blake3-dart/blake3 u8)))
     :clj (let [b (Blake3/newInstance)]
            (.update b ^bytes bs)
            (.digest b))
     :cljs (let [out (noble-blake3/blake3 bs)]
             (js/Uint8Array.from out))))


(defn sha256-bytes
  "P. 32-byte SHA-256 digest of host bytes `bs`, returning fresh host bytes."
  [bs]
  (check-bytes! 'sha256-bytes bs)
  #?(:cljd (let [digest (.convert crypto/sha256 bs)]
             (Uint8List.fromList (.-bytes digest)))
     :clj (let [md (MessageDigest/getInstance "SHA-256")]
            (.digest md ^bytes bs))
     :cljs (let [out (noble-sha256/sha256 bs)]
             (js/Uint8Array.from out))))
