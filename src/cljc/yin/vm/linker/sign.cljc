(ns yin.vm.linker.sign
  "Ed25519 proofs for canonical module envelopes and published head
   traces, each under its own domain-separation prefix.

   Cryptographic operations delegate to `dao.host.sign` (Slice H3):
     `keypair-from-seed`, `sign`, `verify`.
   String UTF-8 encoding delegates to `dao.host.text/utf8-encode`.
   Fresh key generation (`generate`) remains here pending deferred
   entropy design (§9.1); on jolt its `:jolt` arm draws the seed from the
   qualified libcrypto through `yin.vm.linker.random` (the jolt-only
   bridge over native/jolt-crypto's random entry), so entropy sits behind
   the same provenance gate as every other crypto call."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dao.host.bytes :as bytes]
            [dao.host.sign :as host-sign]
            [dao.host.text :as text]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            #?@(:cljd [["package:ed25519_edwards/ed25519_edwards.dart" :as ed]]
                :jolt [[yin.vm.linker.random :as jolt-random]]))
  #?@(:cljd []
      :jolt []
      :clj [(:import [java.security SecureRandom])]))


(def ^:private domain "yin.module/envelope:v1\n")


;; A published head trace's prefix (yin.vm.linker.dht.head.md 5.2): domain
;; separation, so a name envelope's signature is never a head proof and a
;; head proof never a name envelope's.
(def ^:private head-domain "yin.head/trace:v1\n")
(def ^:private digits "0123456789abcdef")


(defn- hex?
  [n s]
  (and (string? s)
       (= n (count s))
       (every? #(some? (str/index-of digits (str %))) s)))


(defn- from-hex
  [s]
  (bytes/from-vector
    (mapv (fn [[a b]]
            (+ (* 16 (str/index-of digits (str a)))
               (str/index-of digits (str b))))
          (partition 2 s))))


(defn- to-hex
  [bs]
  (let [n (bytes/length bs)
        sb #?(:cljd (StringBuffer.)
              :clj (StringBuilder.)
              :cljs (js/Array.))
        append! #?(:cljd (fn [^String s] (.write ^StringBuffer sb s))
                   :clj (fn [^String s] (.append ^StringBuilder sb s))
                   :cljs (fn [s] (.push sb s)))]
    (dotimes [i n]
      (let [b (bytes/get bs i)]
        (append! (str (nth digits (quot b 16))
                      (nth digits (rem b 16))))))
    #?(:cljd (.toString ^StringBuffer sb)
       :clj (.toString ^StringBuilder sb)
       :cljs (.join sb ""))))


(defn- host-bytes
  "Fresh host bytes of `xs`: a host byte array, or a seq of byte values
   (signed JVM bytes read as their unsigned value)."
  [xs]
  (if (bytes/bytes? xs)
    (bytes/slice xs 0 (bytes/length xs))
    (bytes/from-vector (mapv #(bit-and % 0xff) xs))))


(defn- utf8
  [s]
  (text/utf8-encode s))


(defn- message
  ([canonical] (message domain canonical))
  ([prefix canonical]
   (let [p-bytes (utf8 prefix)
         c-bytes (host-bytes canonical)
         p-len (bytes/length p-bytes)
         c-len (bytes/length c-bytes)
         p-vec (bytes/to-vector p-bytes)
         c-vec (bytes/to-vector c-bytes)]
     (bytes/from-vector (into p-vec c-vec)))))


(defn public-of
  [seed]
  (when-not (hex? 64 seed)
    (throw (ex-info "Invalid Ed25519 seed" {:reason :invalid-seed})))
  (let [seed-bytes (from-hex seed)
        pair (host-sign/keypair-from-seed seed-bytes)]
    (to-hex (:public pair))))


(defn principal
  [public]
  (when-not (hex? 64 public)
    (throw (ex-info "Invalid Ed25519 public key" {:reason :invalid-public})))
  (str "ed25519:" public))


(defn generate
  []
  #?(:cljd (let [pair (ed/generateKey)]
             {:seed (to-hex (bytes/from-vector (vec (take 32 (.-bytes (.-privateKey pair))))))
              :public (to-hex (bytes/from-vector (vec (.-bytes (.-publicKey pair)))))})
     :jolt (let [seed (jolt-random/rand-bytes 32)]
             (let [hex (to-hex seed)]
               {:seed hex :public (public-of hex)}))
     :clj (let [seed (byte-array 32)]
            (.nextBytes (SecureRandom.) seed)
            (let [hex (to-hex seed)]
              {:seed hex :public (public-of hex)}))
     :cljs (let [seed (to-hex (.randomBytes (js/require "crypto") 32))]
             {:seed seed :public (public-of seed)})))


(defn- sign-bytes
  [seed msg-bytes]
  (host-sign/sign (from-hex seed) (host-bytes msg-bytes)))


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


(defn- verify-message
  "True when `signature` (128 hex) is `public`'s Ed25519 signature of the
   host bytes `msg`."
  [public msg signature]
  (let [pub-bytes (from-hex public)
        sig-bytes (from-hex signature)]
    (host-sign/verify pub-bytes (host-bytes msg) sig-bytes)))


(defn verify-envelope
  [public canonical signature]
  (try
    (if-not (and (hex? 64 public) (hex? 128 signature)
                 (= (principal public)
                    (:yin.module/asserted-by (cbor/decode canonical))))
      false
      (verify-message public (message canonical) signature))
    (catch #?(:cljd Object :clj Exception :cljs :default) _ false)))


(defn sign-trace
  "Sign a head trace envelope (yin.vm.linker.dht.head.md 5.2): the
   message is `yin.head/trace:v1\\n` as UTF-8 followed by the envelope's
   canonical bytes. Answers the proof map. The key must be the
   envelope's own principal."
  [seed envelope]
  #?(:cljs (when-not (exists? js/require)
             (throw (ex-info "Ed25519 signing primitive unavailable"
                             {:reason :yin.link.sign/no-primitive}))))
  (when-not (hex? 64 seed)
    (throw (ex-info "Invalid Ed25519 seed" {:reason :invalid-seed})))
  (when-not (= (principal (public-of seed)) (:yin.head/principal envelope))
    (throw (ex-info "Trace principal differs from key"
                    {:reason :principal-mismatch})))
  {:yin.head/signature
   (to-hex (sign-bytes seed (message head-domain
                                     (jing/canonical-bytes envelope))))})


(defn verify-trace
  "True when `signature` is `public`'s head proof over the envelope whose
   canonical bytes are `canonical`, and that envelope's principal is
   `public`'s. False otherwise; never throws."
  [public canonical signature]
  (try
    (if-not (and (hex? 64 public) (hex? 128 signature)
                 (= (principal public)
                    (:yin.head/principal (cbor/decode canonical))))
      false
      (verify-message public (message head-domain canonical) signature))
    (catch #?(:cljd Object :clj Exception :cljs :default) _ false)))


(defn key-text
  [{:keys [seed public]}]
  (pr-str {:version 1 :algorithm :ed25519
           :seed seed :public public}))


(defn key-from-text
  [s]
  (try
    ;; The CLJD EDN reader rejects whitespace before a closing delimiter,
    ;; so the wraps close directly after the trimmed text. A trailing
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
