(ns yin.vm.linker.sign
  "Ed25519 proofs for canonical module envelopes."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            #?@(:cljd [["dart:convert" :as convert]
                       ["package:ed25519_edwards/ed25519_edwards.dart" :as ed]])
            #?@(:cljs [[goog.crypt :as crypt]]))
  #?@(:cljd [(:import ["dart:typed_data" Uint8List])])
  #?@(:cljd []
      :clj [(:import [java.security KeyFactory KeyPairGenerator
                      SecureRandom Signature]
                     [java.security.spec NamedParameterSpec
                      PKCS8EncodedKeySpec X509EncodedKeySpec])]))


(def ^:private domain "yin.module/envelope:v1\n")
(def ^:private private-prefix "302e020100300506032b657004220420")
(def ^:private public-prefix "302a300506032b6570032100")
(def ^:private digits "0123456789abcdef")


(defn- hex?
  [n s]
  (and (string? s)
       (= n (count s))
       (every? #(some? (str/index-of digits (str %))) s)))


(defn- from-hex
  [s]
  (mapv (fn [[a b]]
          (+ (* 16 (str/index-of digits (str a)))
             (str/index-of digits (str b))))
        (partition 2 s)))


(defn- to-hex
  [xs]
  (apply str (mapcat (fn [x]
                       (let [n (bit-and 255 x)]
                         [(nth digits (quot n 16))
                          (nth digits (mod n 16))]))
                     xs)))


(defn- host-bytes
  [xs]
  #?(:cljd (Uint8List.fromList (vec xs))
     :clj (byte-array (map unchecked-byte xs))
     :cljs (js/Uint8Array.from (clj->js xs))))


(defn- utf8
  [s]
  #?(:cljd (convert/utf8.encode s)
     :clj (.getBytes ^String s "UTF-8")
     :cljs (crypt/stringToUtf8ByteArray s)))


(defn- message
  [canonical]
  (host-bytes (concat (utf8 domain) canonical)))


#?(:cljd nil :clj
   (defn- jvm-pair
     [seed]
     (let [bytes (host-bytes (from-hex seed))
           random (proxy [SecureRandom] []
                    (nextBytes
                      [dst]
                      (System/arraycopy bytes 0 dst 0 (alength dst))))
           ;; SunEC's generator draws the 32 seed bytes from this random.
           ;; The RFC vectors guard that provider-specific dependency.
           generator (KeyPairGenerator/getInstance "Ed25519" "SunEC")]
       (.initialize generator (NamedParameterSpec. "Ed25519") random)
       (.generateKeyPair generator))))


(defn public-of
  [seed]
  (when-not (hex? 64 seed)
    (throw (ex-info "Invalid Ed25519 seed" {:reason :invalid-seed})))
  #?(:cljd (to-hex (.-bytes (ed/public
                              (ed/newKeyFromSeed
                                (host-bytes (from-hex seed))))))
     :clj (to-hex (take-last 32 (.getEncoded (.getPublic (jvm-pair seed)))))
     :cljs (let [crypto (js/require "crypto")
                 der (js/Buffer.from (str private-prefix seed) "hex")
                 private (.createPrivateKey crypto #js {:key der :format "der"
                                                        :type "pkcs8"})
                 public (.createPublicKey crypto private)]
             (to-hex (take-last 32 (.export public
                                            #js {:format "der" :type "spki"}))))))


(defn principal
  [public]
  (when-not (hex? 64 public)
    (throw (ex-info "Invalid Ed25519 public key" {:reason :invalid-public})))
  (str "ed25519:" public))


(defn generate
  []
  #?(:cljd (let [pair (ed/generateKey)]
             {:seed (to-hex (take 32 (.-bytes (.-privateKey pair))))
              :public (to-hex (.-bytes (.-publicKey pair)))})
     :clj (let [seed (byte-array 32)]
            (.nextBytes (SecureRandom.) seed)
            (let [hex (to-hex seed)]
              {:seed hex :public (public-of hex)}))
     :cljs (let [seed (to-hex (.randomBytes (js/require "crypto") 32))]
             {:seed seed :public (public-of seed)})))


(defn- sign-bytes
  [seed bytes]
  #?(:cljd (ed/sign (ed/newKeyFromSeed (host-bytes (from-hex seed)))
                    (host-bytes bytes))
     :clj (let [factory (KeyFactory/getInstance "Ed25519" "SunEC")
                encoded (host-bytes (from-hex (str private-prefix seed)))
                private (.generatePrivate factory (PKCS8EncodedKeySpec. encoded))
                signer (Signature/getInstance "Ed25519" "SunEC")]
            (.initSign signer private)
            (.update signer (host-bytes bytes))
            (.sign signer))
     :cljs (let [crypto (js/require "crypto")
                 private (.createPrivateKey
                           crypto #js {:key (js/Buffer.from
                                              (str private-prefix seed) "hex")
                                       :format "der" :type "pkcs8"})]
             (.sign crypto nil (js/Buffer.from (host-bytes bytes)) private))))


(defn sign-message
  "Sign raw bytes given as lowercase hex. Used for RFC 8032 conformance."
  [seed message-hex]
  (when-not (and (hex? 64 seed)
                 (hex? (count message-hex) message-hex)
                 (even? (count message-hex)))
    (throw (ex-info "Invalid Ed25519 vector" {:reason :invalid-vector})))
  (to-hex (sign-bytes seed (from-hex message-hex))))


(defn sign-envelope
  [seed envelope]
  #?(:cljs (when-not (exists? js/require)
             (throw (ex-info "Ed25519 signing primitive unavailable"
                             {:reason :yin.link.sign/no-primitive}))))
  (when-not (hex? 64 seed)
    (throw (ex-info "Invalid Ed25519 seed" {:reason :invalid-seed})))
  (when-not (= (principal (public-of seed))
               (:yin.module/asserted-by envelope))
    (throw (ex-info "Envelope principal differs from key"
                    {:reason :principal-mismatch})))
  {:yin.module/signature
   (to-hex (sign-bytes seed (message (jing/canonical-bytes envelope))))})


(defn verify-envelope
  [public canonical signature]
  (try
    (if-not (and (hex? 64 public) (hex? 128 signature)
                 (= (principal public)
                    (:yin.module/asserted-by (cbor/decode canonical))))
      false
      (let [msg (message canonical)
            sig (host-bytes (from-hex signature))]
        #?(:cljd (ed/verify (ed/PublicKey. (host-bytes (from-hex public)))
                            msg sig)
           :clj (let [factory (KeyFactory/getInstance "Ed25519" "SunEC")
                      encoded (host-bytes
                                (from-hex (str public-prefix public)))
                      pub (.generatePublic factory
                                           (X509EncodedKeySpec. encoded))
                      verifier (Signature/getInstance "Ed25519" "SunEC")]
                  (.initVerify verifier pub)
                  (.update verifier msg)
                  (.verify verifier sig))
           :cljs (let [crypto (js/require "crypto")
                       pub (.createPublicKey
                             crypto #js {:key (js/Buffer.from
                                                (str public-prefix public)
                                                "hex")
                                         :format "der" :type "spki"})]
                   (.verify crypto nil (js/Buffer.from msg) pub
                            (js/Buffer.from sig))))))
    (catch #?(:cljd Object :clj Exception :cljs :default) _ false)))


(defn key-text
  [{:keys [seed public]}]
  (pr-str {:version 1 :algorithm :ed25519
           :seed seed :public public}))


(defn key-from-text
  [s]
  (try
    ;; The CLJD EDN reader rejects whitespace before a closing delimiter,
    ;; so the wraps close directly after the trimmed text.  A trailing
    ;; comment then swallows the closer and is refused on every host.
    (let [s (str/trimr s)
          forms (edn/read-string (str "[" s "]"))
          key (when (= 1 (count forms)) (first forms))
          wrapped (edn/read-string (str "{:k " s "}"))]
      (cond
        (or (not (map? key)) (not= wrapped {:k key}))
        {:status :refused :reason :yin.link.sign/malformed-key}
        (not= #{:version :algorithm :seed :public} (set (keys key)))
        {:status :refused :reason :yin.link.sign/key-shape}
        (not= 1 (:version key))
        {:status :refused :reason :yin.link.sign/version}
        (not= :ed25519 (:algorithm key))
        {:status :refused :reason :yin.link.sign/algorithm}
        (not (hex? 64 (:seed key)))
        {:status :refused :reason :yin.link.sign/seed}
        (not (hex? 64 (:public key)))
        {:status :refused :reason :yin.link.sign/public}
        (not= (:public key) (public-of (:seed key)))
        {:status :refused :reason :yin.link.sign/public-mismatch}
        :else {:seed (:seed key) :public (:public key)}))
    (catch #?(:cljd Object :clj Exception :cljs :default) _
      {:status :refused :reason :yin.link.sign/malformed-key})))
