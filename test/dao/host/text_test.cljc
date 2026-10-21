(ns dao.host.text-test
  "Table D's text rows (docs/design/dao.stream.host-boundary.md §4.3,
   slice H1): `utf8-encode` and `utf8-decode` over RFC 3629 valid and
   invalid sequences (overlong, encoded surrogates, above U+10FFFF,
   truncated, stray continuation), each invalid one a direct refusal and
   never a substituted character, cross-checked against the corpus
   `invalid-utf8` class; the unpaired-surrogate encode refusal; table B
   `code-points`; and `nfc` over a pinned subset of Unicode 16.0
   NormalizationTest.txt (test/resources/dao/host/nfc-16.0-subset-v1.txt:
   every @Part0 and @Part1 line, which include the Hangul syllables and
   the composition exclusions)."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [dao.host.bytes :as bytes]
    [dao.host.text :as text]
    [dao.jing.cbor-fixtures :as cfx]))


(defn- refusal
  [f]
  (try
    [:returned (f)]
    (catch #?(:cljd Object :clj Exception :cljs :default) e
      (:dao.host/refusal (ex-data e)))))


(defn- utf8
  [s]
  (bytes/to-vector (text/utf8-encode s)))


(defn- decode
  [v]
  (text/utf8-decode (bytes/from-vector v)))


(def ^:private boundaries
  "`[code-units utf-8]` at every RFC 3629 length boundary."
  [[[0x0000] [0x00]]
   [[0x007F] [0x7F]]
   [[0x0080] [0xC2 0x80]]
   [[0x07FF] [0xDF 0xBF]]
   [[0x0800] [0xE0 0xA0 0x80]]
   [[0xD7FF] [0xED 0x9F 0xBF]]
   [[0xE000] [0xEE 0x80 0x80]]
   [[0xFFFD] [0xEF 0xBF 0xBD]]
   [[0xFFFF] [0xEF 0xBF 0xBF]]
   [[0xD800 0xDC00] [0xF0 0x90 0x80 0x80]]
   [[0xDBFF 0xDFFF] [0xF4 0x8F 0xBF 0xBF]]])


(deftest valid-sequences-round-trip
  (doseq [[units bs] boundaries
          :let [s (text/units->string units)]]
    (is (= bs (utf8 s)) (pr-str units))
    (is (= s (decode bs)) (pr-str units)))
  (is (= [0x68 0xC3 0xA9 0x6C 0x6C 0x6F] (utf8 "héllo")))
  (is (= "héllo" (decode [0x68 0xC3 0xA9 0x6C 0x6C 0x6F])))
  (is (= [] (utf8 "")))
  (is (= "" (decode [])))
  (testing "a leading BOM is content on every host"
    (is (= "﻿a" (decode [0xEF 0xBB 0xBF 0x61])))
    (is (= "﻿﻿" (decode [0xEF 0xBB 0xBF 0xEF 0xBB 0xBF])))
    (is (= [0xEF 0xBB 0xBF 0x61] (utf8 "﻿a"))))
  (testing "a sub-range"
    (let [bs (bytes/from-vector [0x61 0xC3 0xA9 0x62])]
      (is (= "é" (text/utf8-decode bs 1 2)))
      (is (= "" (text/utf8-decode bs 4 0)))
      (is (= :out-of-range (refusal #(text/utf8-decode bs 3 2))))
      (is (= :out-of-range (refusal #(text/utf8-decode bs -1 1)))))))


(def ^:private invalid
  "Ill-formed UTF-8 by RFC 3629, each with what makes it so."
  [[[0x80] "stray continuation"]
   [[0xBF] "stray continuation"]
   [[0x61 0x80 0x62] "stray continuation mid-string"]
   [[0xC0 0x80] "overlong NUL"]
   [[0xC1 0xBF] "overlong two-byte"]
   [[0xE0 0x80 0x80] "overlong three-byte"]
   [[0xE0 0x9F 0xBF] "overlong three-byte, last"]
   [[0xF0 0x80 0x80 0x80] "overlong four-byte"]
   [[0xF0 0x8F 0xBF 0xBF] "overlong four-byte, last"]
   [[0xED 0xA0 0x80] "encoded high surrogate"]
   [[0xED 0xBF 0xBF] "encoded low surrogate"]
   [[0xED 0xA0 0xBD 0xED 0xB8 0x80] "encoded surrogate pair (CESU-8)"]
   [[0xF4 0x90 0x80 0x80] "above U+10FFFF"]
   [[0xF5 0x80 0x80 0x80] "lead byte F5"]
   [[0xF8 0x88 0x80 0x80 0x80] "five-byte form"]
   [[0xFE] "byte FE"]
   [[0xFF] "byte FF"]
   [[0xC3] "truncated two-byte"]
   [[0xE2 0x82] "truncated three-byte"]
   [[0xF0 0x9F 0x98] "truncated four-byte"]
   [[0xC3 0x28] "bad continuation"]
   [[0xE9] "lone Latin-1 byte"]
   [[0x61 0xE2 0x82] "truncated at the end"]])


(deftest invalid-sequences-are-refused-never-substituted
  (doseq [[v why] invalid]
    (is (= :invalid-utf8 (refusal #(decode v))) why)))


(defn- string-payload
  "The payload bytes of a top-level CBOR text string `hex` with a one-byte
   head, or nil."
  [hex]
  (let [[b & more] (cfx/hex->ints hex)]
    (when (and (<= 0x60 b 0x77) (= (- b 0x60) (count more)))
      (vec more))))


(deftest the-corpus-invalid-utf8-strings-are-refused
  (let [cases (keep (fn [c]
                      (when (= "invalid-utf8" (get c "refusal"))
                        (when-let [p (string-payload (get c "hex"))] [(get c "id") p])))
                    (cfx/cases-of-kind "decode-refusal"))]
    (is (= 5 (count cases)) "bad continuation, overlong NUL, encoded surrogate, truncated, above max")
    (doseq [[id p] cases]
      (is (= :invalid-utf8 (refusal #(decode p))) id))))


(deftest unpaired-surrogates-are-refused-on-encode
  (doseq [units [[0xD800] [0xDC00] [0x61 0xD800] [0xD800 0x61] [0x61 0xDC80 0x62]
                 [0xDE00 0xD83D] [0xDBFF] [0xD83D 0xD83D 0xDE00]]]
    (is (= :unpaired-surrogate (refusal #(text/utf8-encode (text/units->string units))))
        (pr-str units)))
  (is (= :unsupported-value (refusal #(text/utf8-encode nil))))
  (is (= [0xF0 0x9F 0x98 0x80] (utf8 (text/units->string [0xD83D 0xDE00])))))


(deftest code-points-test
  (is (= [] (text/code-points "")) "the empty string")
  (is (= [0x61 0xE9 0x4E2D] (text/code-points "aé中")) "BMP")
  (is (= [0x1F600] (text/code-points (text/units->string [0xD83D 0xDE00]))) "a non-BMP pair")
  (is (= [0xD83D 0x61] (text/code-points (text/units->string [0xD83D 0x61]))) "a lone high surrogate")
  (is (= [0x61 0xDE00] (text/code-points (text/units->string [0x61 0xDE00]))) "a lone low surrogate")
  (is (= [0xD83D] (text/code-points (text/units->string [0xD83D]))) "a lone high at the end"))


;; =============================================================================
;; NFC, Unicode 16.0
;; =============================================================================

(def ^:private nfc-path "test/resources/dao/host/nfc-16.0-subset-v1.txt")


(defn- cps->string
  [hex]
  (text/units->string
    (into []
          (mapcat (fn [h]
                    (let [cp (reduce (fn [acc d] (+ (* 16 acc) d)) 0
                                     (map #(first (cfx/hex->ints (str "0" %))) (str/lower-case h)))]
                      (if (< cp 0x10000)
                        [cp]
                        (let [c (- cp 0x10000)]
                          [(+ 0xD800 (quot c 0x400)) (+ 0xDC00 (mod c 0x400))])))))
          (str/split hex #" "))))


(def ^:private nfc-rows
  (delay
    (let [[magic & lines] (remove str/blank? (str/split-lines (cfx/read-path nfc-path)))]
      (when-not (= "nfc-16.0-subset-v1" magic)
        (throw (ex-info "not an nfc-16.0-subset-v1 fixture" {:path nfc-path})))
      ;; reduce, not `for`: ClojureDart's chunked `for` can hand the body
      ;; a nil past 32 elements
      (reduce (fn [acc l]
                (if (str/starts-with? l "#")
                  acc
                  (let [[part age cols] (str/split l #" " 3)]
                    (conj acc [part age (mapv cps->string (str/split cols #";"))]))))
              []
              lines))))


(defn- nfc-failures
  "The rows of `rows` whose NFC invariants fail: c2 = NFC(c1) = NFC(c2) =
   NFC(c3) and c4 = NFC(c4) = NFC(c5)."
  [rows]
  (into []
        (keep (fn [[part _ [c1 c2 c3 c4 c5]]]
                (when-not (and (= c2 (text/nfc c1) (text/nfc c2) (text/nfc c3))
                               (= c4 (text/nfc c4) (text/nfc c5)))
                  [part (text/code-points c1)])))
        rows))


(defn- jvm-nfc-gap
  "The first code points of the 16.0 rows this host fails, pinned: none,
   except on a JVM before 24, whose `java.text.Normalizer` is Unicode
   15.x (JDK 21: 15.0) and leaves the 16.0 canonical decompositions of
   Todhri, Tulu-Tigalari, Gurung Khema and Kirat Rai unnormalized. A known gap of the JVM lane, recorded in the
   H1 report; the pin fails as soon as the JDK changes, so it cannot go
   stale silently."
  []
  #?(:cljd []
     :clj (if (< (.feature (Runtime/version)) 24)
            [67017 67044 70531 70533 70542 70545 70597 70599 70600
             90401 90402 90403 90404 90405 90406 90407 90408
             93544 93545 93546]
            [])
     :cljs []))


(deftest nfc-pinned-unicode-16-subset
  (let [rows @nfc-rows
        by-age (group-by second rows)]
    (is (= 17130 (count rows)) "every @Part0 and @Part1 line")
    (is (= 56 (count (by-age "16.0"))) "rows with a code point first assigned in 16.0")
    (testing "rows whose code points all predate 16.0"
      (is (= [] (nfc-failures (by-age "old")))))
    (testing "rows with a 16.0 code point"
      (is (= (jvm-nfc-gap) (mapv (comp first second) (nfc-failures (by-age "16.0"))))))))
