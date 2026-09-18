(ns dao.jing.stream-test
  "The Jing/transport boundary adapter (U10, D7's condition): Jing's
   canonical bytes cross either stream profile losslessly — as a CBOR byte
   string on `dao.stream.cbor`, as the named vector of octets on
   `dao.stream.transit-json` — and they are the addressed bytes: the digest
   of what unwraps is the digest `content-hash` computed."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.stream :as jing-stream]
            [dao.stream.cbor :as cbor]
            [dao.stream.transit :as transit]))


(def ^:private payloads
  [(keyword "segment" "sha256-0000000000000000000000000000000000000000000000000000000000000000")
   42
   "text with unicode: λ"
   [:literal [1 2]]
   [:literal {:b 2, :a #{1 (with-meta (list 3) {:pos [1]})}}]
   {:op :vm/store-put, :key :k, :val [1 {:b 2}]}])


(deftest canonical-bytes-are-the-addressed-bytes
  (testing "sha256-bytes over canonical-bytes is content-hash, on every payload"
    (doseq [v payloads]
      (is (= (jing/content-hash v)
             (jing/sha256-bytes (jing/canonical-bytes v)))))))


(defn- carried?
  "The bytes survived one lane's encode/decode and unwrap: compared by
   digest, since host byte payloads are not = values."
  [profile codec-encode codec-decode bs]
  (let [wire (codec-encode (jing-stream/wrap-bytes profile bs))
        back (jing-stream/unwrap-bytes profile (codec-decode wire))]
    (= (jing/sha256-bytes bs) (jing/sha256-bytes back))))


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
    (is (thrown-with-msg? #?(:cljd Object :clj Exception :cljs :default) #"not a known stream codec profile"
          (jing-stream/wrap-bytes {:ws/subprotocol "dao.stream.base64"} nil)))))
