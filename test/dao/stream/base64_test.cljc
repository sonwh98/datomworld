(ns dao.stream.base64-test
  "The strict Base64 codec of the raw datagram layer
   (docs/design/dao.stream.datagram.md 2): padded standard alphabet, no
   whitespace, no URL-safe alphabet, the empty string for zero bytes --
   byte-for-byte the format dao.jing's frozen functions already implement
   (dao.jing.dht.md 10, Base64 seam)."

  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.stream.base64 :as base64]
            [dao.stream.cbor :as cbor])
  #?(:cljd (:import ["dart:typed_data" Uint8List])))


(defn- to-hex
  [bs]
  #?(:cljd (apply str (map (fn [b]
                             (let [h (.toRadixString b 16)]
                               (if (< b 16) (str "0" h) h)))
                           bs))
     :clj (apply str (map #(let [h (format "%02x" %)]
                             (if (< (count h) 2) (str "0" h) h))
                          bs))
     :cljs (apply str (map (fn [b]
                             (let [h (.toString b 16)]
                               (if (< b 16) (str "0" h) h)))
                           bs))))


(defn- bytes=
  [a b]
  (and (cbor/byte-payload? a)
       (cbor/byte-payload? b)
       (= (to-hex a) (to-hex b))))


(defn- b
  [& xs]
  #?(:cljd (Uint8List.fromList (vec xs))
     :clj (byte-array (mapv unchecked-byte xs))
     :cljs (js/Uint8Array.from (to-array (vec xs)))))


(def rfc-4648-vectors
  "The RFC 4648 test vectors for the standard alphabet, the empty string
   first: a zero-length datagram is the empty string."
  [[[]       ""]
   [[0xfb]   "+w=="]
   [[0xfb 0xef]    "++8="]
   [[0xfb 0xef 0xbf] "+++/"]
   [[0x66 0x6f 0x6f] "Zm9v"]
   [[0x66 0x6f 0x6f 0x62 0x61 0x72] "Zm9vYmFy"]
   [[0x66 0x6f 0x6f 0x20 0x62 0x61 0x72 0x20 0x62 0x61 0x7a]
    "Zm9vIGJhciBiYXo="]])


(deftest rfc-4648-round-trips
  (doseq [[octets text] rfc-4648-vectors]
    (is (= text (base64/encode (apply b octets))))
    (is (= text (jing/bytes->base64 (apply b octets))))
    (is (bytes= (apply b octets) (base64/decode text)))
    (is (bytes= (base64/decode text) (jing/base64->bytes text)))))


(deftest decode-is-total-and-strict
  (testing "decode answers nil, never throws, for anything not strict
            padded standard-alphabet text; a length-4k+2 tail takes two
            pads and a length-4k+3 tail takes one, and no other shape is
            text"
    (doseq [bad [nil
                 42
                 :keyword
                 " "
                 "Zm9vYmFy\n"                       ; trailing newline
                 " Zm9vYmFy"                        ; leading space
                 "Zm9v YmFy"                        ; interior space
                 "Zg\n=="                           ; interior newline
                 "Zm9vYmF"                          ; missing padding
                 "Zm9vYmFy="                        ; stray pad
                 "Zm9vYmFy=="
                 "A"                                ; length 1
                 "AAAAA"                            ; length 5
                 "A==="                             ; one char, three pads
                 "-_8="                             ; URL-safe alphabet
                 "Zm9vY G Fy"]]
      (is (nil? (base64/decode bad)) (pr-str bad))
      (is (not (base64/text? bad)) (pr-str bad)))
    (is (base64/text? "") "a zero-length datagram is the empty string")
    (is (base64/text? "Zm9vYmFy"))
    (is (base64/text? "Zg=="))
    (is (base64/text? "Zm8="))))


(deftest every-length-round-trips
  (testing "zero through sixty-four bytes, every boundary of the 3:4 ratio"
    (dotimes [n 65]
      (let [octets (range n)
            encoded (base64/encode (apply b octets))]
        (is (base64/text? encoded))
        (is (bytes= (apply b octets) (base64/decode encoded)))))))
