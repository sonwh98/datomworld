(ns dao.jing.stream-test
  "The Jing/transport boundary adapter (U10, D7's condition): Jing's
   canonical bytes cross either stream profile losslessly — as a CBOR byte
   string on `dao.stream.cbor`, as the named vector of octets on
   `dao.stream.transit-json` — and they are the addressed bytes: the digest
   of what unwraps is the digest `content-hash` computed."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.cbor-fixtures :as fx]
            [dao.jing.stream :as jing-stream]
            [dao.stream.cbor :as cbor]
            [dao.stream.codec :as codec]
            [dao.stream.transit :as transit]
            [yang.python.antlr.int-contract-fixtures :as f]))


(def ^:private payloads
  [(keyword "segment" "sha256-0000000000000000000000000000000000000000000000000000000000000000")
   42
   "text with unicode: λ"
   [:literal [1 2]]
   [:literal {:b 2, :a #{1 (with-meta (list 3) {:pos [1]})}}]
   {:op :vm/store-put, :key :k, :val [1 {:b 2}]}])


(deftest canonical-bytes-are-the-addressed-bytes
  (testing
    "digest-bytes over canonical-bytes matches content-hash, on every payload"
    (doseq [v payloads]
      (is (= (jing/content-hash v)
             (jing/blake3-bytes (jing/canonical-bytes v))))
      (is (= (jing/content-hash v {:algorithm :sha256})
             (jing/sha256-bytes (jing/canonical-bytes v)))))))


(defn- carried?
  "The bytes survived one lane's encode/decode and unwrap: compared by
   digest, since host byte payloads are not = values."
  [profile codec-encode codec-decode bs]
  (let [wire (codec-encode (jing-stream/wrap-bytes profile bs))
        back (jing-stream/unwrap-bytes profile (codec-decode wire))]
    (= (jing/blake3-bytes bs) (jing/blake3-bytes back))))


(deftest jing-bytes-cross-both-stream-profiles-losslessly
  (testing "the CBOR lane carries them as a byte string, canonically"
    (doseq [v payloads]
      (let [bs (jing/canonical-bytes v)
            wrapped (jing-stream/wrap-bytes cbor/profile bs)]
        (is (map? wrapped))
        (is (cbor/byte-payload? (:dao.jing/canonical-bytes wrapped))
            "on the CBOR lane the named carrier is the byte string itself")
        (is (carried? cbor/profile cbor/encode cbor/decode bs)
            (str "bytes survived the cbor lane for " (pr-str v))))))

  (testing "the Transit lane carries them as the named vector of octets"
    (doseq [v payloads]
      (let [bs (jing/canonical-bytes v)
            wrapped (jing-stream/wrap-bytes transit/profile bs)]
        (is (= {:dao.jing/canonical-bytes
                (mapv #(bit-and 0xff %) (jing/canonical-bytes v))}
               wrapped)
            "the transit representation is the named lossless octet vector")
        (is (carried? transit/profile transit/encode transit/decode bs)
            (str "bytes survived the transit lane for " (pr-str v))))))

  (testing "wrap-payload is the same boundary with the value in hand"
    (is (carried? cbor/profile cbor/encode cbor/decode
                  (jing/canonical-bytes [:literal 1])))
    (is (carried? transit/profile transit/encode transit/decode
                  (jing/canonical-bytes [:literal 1])))))


(deftest the-boundary-is-explicit
  (testing "a byte string that crossed the wrong lane is refused"
    (let [bs (jing/canonical-bytes [:literal 1])
          cbor-wrapped (jing-stream/wrap-bytes cbor/profile bs)
          transit-wrapped (jing-stream/wrap-bytes transit/profile bs)]
      (is (thrown-with-msg? #?(:cljd Object :clj Exception :cljs :default) #"does not carry Jing bytes for this profile"
            (jing-stream/unwrap-bytes transit/profile cbor-wrapped)))
      (is (thrown-with-msg? #?(:cljd Object :clj Exception :cljs :default) #"does not carry Jing bytes for this profile"
            (jing-stream/unwrap-bytes cbor/profile transit-wrapped)))))

  (testing "an unnamed or foreign value is refused"
    (is (thrown-with-msg? #?(:cljd Object :clj Exception :cljs :default) #"does not carry Jing bytes"
          (jing-stream/unwrap-bytes cbor/profile {:ws/frame :ws/value})))
    (is (thrown-with-msg? #?(:cljd Object :clj Exception :cljs :default) #"does not carry Jing bytes"
          (jing-stream/unwrap-bytes transit/profile
                                    {:dao.jing/canonical-bytes [999 -1]}))
        "octets are 0-255 integers only"))

  (testing "an unknown profile is refused, never a silent downgrade"
    (is (thrown-with-msg? #?(:cljd Object :clj Exception :cljs :default) #"not a valid stream codec profile"
          (jing-stream/wrap-bytes {:ws/subprotocol "dao.stream.base64"} nil)))))


(deftest big-integer-bytes-cross-the-transport-boundary
  (let [fixture (f/read-file)]
    (doseq [s ["2^64" "-2^64-1"]]
      (let [bs (jing/canonical-bytes (f/value s))]
        (is (= (f/column fixture s "cbor") (fx/bytes->hex bs)))
        (is (carried? cbor/profile cbor/encode cbor/decode bs))
        (is (carried? transit/profile transit/encode transit/decode bs))))))


(defn- stub-profile
  "A complete modern profile under an unfamiliar name: the adapter must pick
   its lane from the declared capability, never from a known name."
  [bytes?]
  {:dao.stream.codec/name "dao.stream.custom-stub"
   :dao.stream.codec/frame-kind (if bytes? :binary :text)
   :dao.stream.codec/portable-value? codec/portable-value?
   :dao.stream.codec/encode identity
   :dao.stream.codec/decode identity
   :dao.stream.codec/bytes? bytes?})


(deftest malformed-profiles-are-rejected-before-any-lane
  (let [bs (jing/canonical-bytes [:literal 1])
        good (stub-profile true)
        bad [nil
             {}
             (dissoc good :dao.stream.codec/encode)
             (dissoc good :dao.stream.codec/bytes?)
             (assoc good :dao.stream.codec/bytes? :yes)
             (assoc good :dao.stream.codec/name "not a token")]]
    (doseq [p bad]
      (is (thrown-with-msg? #?(:cljd Object :clj Exception :cljs :default)
                            #"not a valid stream codec profile"
            (jing-stream/wrap-bytes p bs))
          (pr-str p))
      (is (thrown-with-msg? #?(:cljd Object :clj Exception :cljs :default)
                            #"not a valid stream codec profile"
            (jing-stream/unwrap-bytes p {:dao.jing/canonical-bytes bs}))
          (pr-str p)))))


(deftest the-lane-follows-the-bytes-capability-not-the-name
  (let [bs (jing/canonical-bytes [:literal 1])]
    (testing "bytes? true: the byte string itself"
      (let [p (stub-profile true)
            w (jing-stream/wrap-bytes p bs)]
        (is (codec/host-bytes? (:dao.jing/canonical-bytes w)))
        (is (carried? p identity identity bs))))
    (testing "bytes? false: the named vector of octets"
      (let [p (stub-profile false)
            w (jing-stream/wrap-bytes p bs)]
        (is (= (mapv #(bit-and 0xff %) bs) (:dao.jing/canonical-bytes w)))
        (is (carried? p identity identity bs))))))
