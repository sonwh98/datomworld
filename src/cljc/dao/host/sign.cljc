(ns dao.host.sign
  "The Ed25519 digital signature functions of the primitive profile
   (docs/design/dao.stream.host-boundary.md §4.2, table A).

   P entries (§2.4):
   - `keypair-from-seed`: takes 32-byte seed (host bytes), returns
     `{:seed <32 bytes>, :public <32 bytes>}` as fresh host bytes.
   - `sign`: takes 32-byte seed and arbitrary message (host bytes),
     returns 64-byte Ed25519 signature as fresh host bytes.
   - `verify`: takes 32-byte public key, arbitrary message, and 64-byte signature
     (all host bytes), returns boolean (true if valid, false otherwise).

   Deterministic seed replay: SunEC KeyPairGenerator on JVM using proxy SecureRandom,
   Node crypto Ed25519 PKCS8/SPKI derivation, and package:ed25519_edwards on Dart.
   Fresh entropy key generation (`generate`) is deferred (§9.1) and remains in
   `yin.vm.linker.sign`."
  (:require
    [dao.host.bytes :as bytes]
    #?@(:cljd [["package:ed25519_edwards/ed25519_edwards.dart" :as ed]])
    #?@(:cljs []))
  #?(:cljd (:import ["dart:typed_data" Uint8List])
     :clj  (:import [java.security KeyFactory KeyPairGenerator SecureRandom Signature]
                    [java.security.spec NamedParameterSpec PKCS8EncodedKeySpec X509EncodedKeySpec])))


(def ^:private pkcs8-prefix
  ;; 16 bytes ASN.1 header for Ed25519 private key: 302e020100300506032b657004220420
  (bytes/from-vector [0x30 0x2e 0x02 0x01 0x00 0x30 0x05 0x06 0x03 0x2b 0x65 0x70 0x04 0x22 0x04 0x20]))

(def ^:private spki-prefix
  ;; 12 bytes ASN.1 header for Ed25519 public key: 302a300506032b6570032100
  (bytes/from-vector [0x30 0x2a 0x30 0x05 0x06 0x03 0x2b 0x65 0x70 0x03 0x21 0x00]))


(defn- check-bytes!
  [entry name-kw bs expected-len]
  (when-not (bytes/bytes? bs)
    (throw (ex-info (str "dao.host refused: " entry " unsupported-value (expected " (name name-kw) " host bytes)")
                    {:dao.host/refusal :unsupported-value
                     :dao.host/entry entry
                     :target name-kw})))
  (when (and expected-len (not= (bytes/length bs) expected-len))
    (throw (ex-info (str "dao.host refused: " entry " out-of-range (expected " expected-len " bytes, got " (bytes/length bs) ")")
                    {:dao.host/refusal :out-of-range
                     :dao.host/entry entry
                     :expected expected-len
                     :actual (bytes/length bs)}))))


#?(:cljd nil
   :cljs nil
   :clj
   (defn- jvm-pair
     [^bytes seed]
     (let [seed-copy (bytes/slice seed 0 32)
           random (proxy [SecureRandom] []
                    (nextBytes [dst]
                      (bytes/copy! seed-copy 0 dst 0 (bytes/length dst))))
           generator (KeyPairGenerator/getInstance "Ed25519" "SunEC")]
       (.initialize generator (NamedParameterSpec. "Ed25519") random)
       (.generateKeyPair generator))))


(defn keypair-from-seed
  "P. Deterministic Ed25519 keypair from 32-byte seed `seed-bytes`.
   Returns `{:seed <32-byte host bytes>, :public <32-byte host bytes>}`."
  [seed-bytes]
  (check-bytes! 'keypair-from-seed :seed seed-bytes 32)
  (let [seed-copy (bytes/slice seed-bytes 0 32)]
    #?(:cljd (let [pair (ed/newKeyFromSeed seed-copy)
                   pub-bytes (Uint8List.fromList (.-bytes (ed/public pair)))]
               {:seed seed-copy
                :public pub-bytes})
       :clj (let [pair (jvm-pair seed-copy)
                  encoded (.getEncoded (.getPublic pair))
                  pub-bytes (bytes/slice encoded (- (alength encoded) 32) (alength encoded))]
              {:seed seed-copy
               :public pub-bytes})
       :cljs (let [crypto (js/require "crypto")
                   der (bytes/alloc 48)]
               (bytes/copy! pkcs8-prefix 0 der 0 16)
               (bytes/copy! seed-copy 0 der 16 32)
               (let [priv-key (.createPrivateKey crypto #js {:key (js/Buffer.from der)
                                                             :format "der"
                                                             :type "pkcs8"})
                     pub-key (.createPublicKey crypto priv-key)
                     spki (.export pub-key #js {:format "der" :type "spki"})
                     pub-bytes (bytes/slice spki (- (alength spki) 32) (alength spki))]
                 {:seed seed-copy
                  :public pub-bytes})))))


(defn sign
  "P. Sign message `msg-bytes` with 32-byte `seed-bytes`.
   Returns 64-byte Ed25519 signature as fresh host bytes."
  [seed-bytes msg-bytes]
  (check-bytes! 'sign :seed seed-bytes 32)
  (check-bytes! 'sign :message msg-bytes nil)
  #?(:cljd (let [key (ed/newKeyFromSeed (bytes/slice seed-bytes 0 32))
                 sig (ed/sign key (bytes/slice msg-bytes 0 (bytes/length msg-bytes)))]
             (Uint8List.fromList sig))
     :clj (let [factory (KeyFactory/getInstance "Ed25519" "SunEC")
                pkcs8 (bytes/alloc 48)]
            (bytes/copy! pkcs8-prefix 0 pkcs8 0 16)
            (bytes/copy! seed-bytes 0 pkcs8 16 32)
            (let [priv (.generatePrivate factory (PKCS8EncodedKeySpec. pkcs8))
                  signer (Signature/getInstance "Ed25519" "SunEC")]
              (.initSign signer priv)
              (.update signer ^bytes msg-bytes)
              (.sign signer)))
     :cljs (let [crypto (js/require "crypto")
                 pkcs8 (bytes/alloc 48)]
             (bytes/copy! pkcs8-prefix 0 pkcs8 0 16)
             (bytes/copy! seed-bytes 0 pkcs8 16 32)
             (let [priv-key (.createPrivateKey crypto #js {:key (js/Buffer.from pkcs8)
                                                           :format "der"
                                                           :type "pkcs8"})
                   sig (.sign crypto nil (js/Buffer.from msg-bytes) priv-key)]
               (js/Uint8Array.from sig)))))


(defn verify
  "P. Verify 64-byte `sig-bytes` against `msg-bytes` and 32-byte `public-key-bytes`.
   Returns boolean."
  [public-key-bytes msg-bytes sig-bytes]
  (check-bytes! 'verify :public-key public-key-bytes 32)
  (check-bytes! 'verify :message msg-bytes nil)
  (check-bytes! 'verify :signature sig-bytes 64)
  #?(:cljd (try (ed/verify (ed/PublicKey. (bytes/slice public-key-bytes 0 32))
                           (bytes/slice msg-bytes 0 (bytes/length msg-bytes))
                           (bytes/slice sig-bytes 0 64))
                (catch Object _ false))
     :clj (try (let [factory (KeyFactory/getInstance "Ed25519" "SunEC")
                     spki (bytes/alloc 44)]
                 (bytes/copy! spki-prefix 0 spki 0 12)
                 (bytes/copy! public-key-bytes 0 spki 12 32)
                 (let [pub (.generatePublic factory (X509EncodedKeySpec. spki))
                       verifier (Signature/getInstance "Ed25519" "SunEC")]
                   (.initVerify verifier pub)
                   (.update verifier ^bytes msg-bytes)
                   (.verify verifier ^bytes sig-bytes)))
               (catch Exception _ false))
     :cljs (try (let [crypto (js/require "crypto")
                      spki (bytes/alloc 44)]
                  (bytes/copy! spki-prefix 0 spki 0 12)
                  (bytes/copy! public-key-bytes 0 spki 12 32)
                  (let [pub-key (.createPublicKey crypto #js {:key (js/Buffer.from spki)
                                                              :format "der"
                                                              :type "spki"})]
                    (boolean (.verify crypto nil (js/Buffer.from msg-bytes) pub-key
                                      (js/Buffer.from sig-bytes)))))
                (catch :default _ false))))
