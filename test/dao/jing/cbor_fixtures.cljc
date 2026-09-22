(ns dao.jing.cbor-fixtures
  "The frozen DaoJing canonical-CBOR contract corpus (phase J0 of
   docs/design/dao.jing.cbor.md), loaded on the JVM, Node, and Dart.

   The corpus lives in test/resources/dao/jing/cbor-v1.json; its provenance,
   the input DSL, and the review rules are in cbor-v1.README.md beside it.
   Every host reads that one file by its repository-relative path: the three
   test lanes run with the repository root as their working directory.

   Cases are string-keyed maps exactly as the JSON spells them. Nothing here
   encodes or decodes CBOR, and nothing here may write the resource."
  #?@(:cljd [(:require ["dart:convert" :as dart-convert]
                       ["dart:io" :as dart-io]
                       ["dart:typed_data" :as typed]
                       [dao.jing.cbor :as cbor])
             (:import ["dart:core" BigInt DateTime String])]
      :clj [(:require [clojure.data.json :as json]
                      [dao.jing.cbor :as cbor])]
      :cljs [(:require [dao.jing.cbor :as cbor])]))


(def path
  "The corpus, relative to the repository root."
  "test/resources/dao/jing/cbor-v1.json")


(defn read-text
  "The corpus file's text."
  []
  #?(:cljd (.readAsStringSync (dart-io/File. path))
     :clj (slurp path)
     :cljs (.readFileSync (js/require "fs") path "utf8")))


(defn read-path
  "The text of any repository-relative file (the conformance gate reads the
   errata and the corpus bytes with it). Read only."
  [p]
  #?(:cljd (.readAsStringSync (dart-io/File. p))
     :clj (slurp p)
     :cljs (.readFileSync (js/require "fs") p "utf8")))


#?(:cljd
   (defn- dart->clj
     "jsonDecode yields Dart List and Map; convert them to Clojure data."
     [x]
     (cond (dart/is? x List) (mapv dart->clj x)
           (dart/is? x Map) (into {}
                                  (map (fn [entry]
                                         [(key entry) (dart->clj (val entry))]))
                                  x)
           :else x)))


(defn parse
  "JSON text to string-keyed Clojure data."
  [text]
  #?(:cljd (dart->clj (dart-convert/jsonDecode text))
     :clj (json/read-str text)
     :cljs (js->clj (js/JSON.parse text))))


(defn json-round-trip
  "The corpus data after one host JSON write and read of the parsed text."
  [text]
  #?(:cljd (dart->clj (dart-convert/jsonDecode
                        (dart-convert/jsonEncode (dart-convert/jsonDecode text))))
     :clj (json/read-str (json/write-str (json/read-str text)))
     :cljs (js->clj (js/JSON.parse (js/JSON.stringify (clj->js (parse text)))))))


(def ^:private corpus* (delay (parse (read-text))))


(defn corpus
  "The whole corpus document."
  []
  @corpus*)


(defn cases
  "Every case, in file order."
  []
  (get (corpus) "cases"))


(defn case-by-id
  [id]
  (first (filter #(= id (get % "id")) (cases))))


(defn cases-of-kind
  "Cases of one kind: \"canonical\", \"encode-refusal\", or \"decode-refusal\"."
  [kind]
  (filterv #(= kind (get % "kind")) (cases)))


(def ^:private hex-values
  {"0" 0 "1" 1 "2" 2 "3" 3 "4" 4 "5" 5 "6" 6 "7" 7
   "8" 8 "9" 9 "a" 10 "b" 11 "c" 12 "d" 13 "e" 14 "f" 15})


(defn hex?
  "True for an even-length string of lowercase hex digits (empty included)."
  [s]
  (and (string? s)
       (even? (count s))
       (every? #(contains? hex-values (subs s % (inc %))) (range (count s)))))


(defn hex->ints
  [s]
  (mapv (fn [i]
          (+ (* 16 (get hex-values (subs s i (inc i))))
             (get hex-values (subs s (inc i) (+ i 2)))))
        (range 0 (count s) 2)))


(defn hex->bytes
  "Host bytes for a hex string: byte[], Uint8Array, or Uint8List."
  [s]
  (let [ints (hex->ints s)]
    #?(:cljd (typed/Uint8List.fromList ints)
       :clj (byte-array (map unchecked-byte ints))
       :cljs (js/Uint8Array.from (clj->js ints)))))


;; =============================================================================
;; Codec-test helpers (JVM, ClojureScript and Dart). They build host values
;; from the corpus input DSL (README "Input DSL") and never compute expected
;; bytes.
;; =============================================================================

(defn bytes->hex
  "Lowercase hex of host bytes."
  [bs]
  #?(:cljd (apply str (map #(.padLeft (.toRadixString ^int % 16) 2 "0") bs))
     :default (apply str (map (fn [i]
                                (let [b #?(:clj (bit-and (aget ^bytes bs i) 0xff)
                                           :cljs (aget bs i))]
                                  (str (when (< b 16) "0")
                                       #?(:clj (Integer/toHexString b)
                                          :cljs (.toString b 16)))))
                              (range (alength bs))))))


(defrecord Probe
  [a])


(def not-constructible
  "Marks a DSL node this host cannot build (no such host type)."
  ::not-constructible)


(defn- text
  "A DSL text: a JSON string, or {\"utf16\" [hex code units]}."
  [t]
  (if (string? t)
    t
    (let [codes (mapv (fn [u] (let [[hi lo] (hex->ints u)] (+ (* 256 hi) lo)))
                      (get t "utf16"))]
      #?(:cljd (String.fromCharCodes codes)
         :clj (String. (char-array (map char codes)))
         :cljs (apply js/String.fromCharCode codes)))))


(defn- host-int
  "The host integer for a DSL int: small when it fits the host's small
   type and \"host\" is not \"big\", else the host big-integer type."
  [s big?]
  #?(:cljd (let [b (BigInt.parse s)]
             (if (and (not big?) (.-isValidInt b)) (.toInt b) b))
     :clj (let [b (bigint s)]
            (if (or big? (not (<= Long/MIN_VALUE b Long/MAX_VALUE)))
              (if big? (biginteger b) b)
              (long b)))
     :cljs (let [b (js/BigInt s)
                 n (js/Number b)]
             (if (and (not big?) (js/Number.isSafeInteger n)) n b))))


(defn- float32-widened
  "The exact float64 widening of float32 bits (8 hex digits), as the host
   would hold a native float32: a Float on the JVM; JavaScript and Dart have
   no float32 value, so the widened double (README A16)."
  [hex]
  #?(:cljd (let [bd (typed/ByteData. 4)
                 [a b c d] (hex->ints hex)]
             (.setUint8 bd 0 a)
             (.setUint8 bd 1 b)
             (.setUint8 bd 2 c)
             (.setUint8 bd 3 d)
             (.getFloat32 bd 0))
     :clj (Float/intBitsToFloat (unchecked-int (Long/parseLong hex 16)))
     :cljs (let [view (js/DataView. (js/ArrayBuffer. 4))]
             (.setUint32 view 0 (js/parseInt hex 16))
             (cbor/float64 (.getFloat32 view 0)))))


(defn- host-value
  "The unsupported host value a {\"t\":\"host\"} node names, or
   not-constructible where the host has no such type."
  [kind v]
  (case kind
    "char" #?(:cljd not-constructible :clj (first v) :cljs not-constructible)
    "inst" #?(:cljd (DateTime.parse v)
              :clj (java.util.Date/from (java.time.Instant/parse v))
              :cljs (js/Date. v))
    "uuid" #?(:cljd (uuid v) :clj (java.util.UUID/fromString v) :cljs (uuid v))
    "fn" identity
    "record" (->Probe 1)
    "js-unsafe-integer" #?(:cljd not-constructible
                           :clj not-constructible
                           :cljs (js/Number v))))


(defn fresh-list
  "A list of items with no metadata. ClojureDart's list constructor attaches
   call-site metadata (a Dart Type :tag); the plan makes the producer clear
   it, and the codec refuses it otherwise."
  [items]
  #?(:cljd (with-meta (apply list items) nil)
     :default (apply list items)))


(defn input->value
  "The host value a corpus DSL input node describes."
  [node]
  (let [t (get node "t")
        build (fn [coll]
                (if-some [m (get node "meta")]
                  (with-meta coll (input->value m))
                  coll))
        items #(mapv input->value (get node "items"))
        entries #(mapv (fn [[k v]] [(input->value k) (input->value v)])
                       (get node "entries"))]
    (case t
      "nil" nil
      "bool" (get node "v")
      "int" (host-int (get node "v") (= "big" (get node "host")))
      "float64" (cbor/float64-from-bits (get node "bits"))
      "float32" (float32-widened (get node "bits"))
      "decimal" (cbor/decimal (host-int (get node "exponent") false)
                              (get node "mantissa"))
      "ratio" (cbor/ratio (host-int (get node "num") true)
                          (host-int (get node "den") true))
      "str" (text (get node "v"))
      "bytes" (hex->bytes (get node "hex"))
      "keyword" (keyword (some-> (get node "ns") text) (text (get node "name")))
      "symbol" (build (symbol (some-> (get node "ns") text) (text (get node "name"))))
      "vector" (build (items))
      "list" (build (fresh-list (items)))
      "seq" (build (lazy-seq (seq (items))))
      "map" (build (reduce (fn [m [k v]] (assoc m k v)) {} (entries)))
      "sorted-map" (build (into (sorted-map) (entries)))
      "set" (build (reduce conj #{} (items)))
      "sorted-set" (build (into (sorted-set) (items)))
      "host" (host-value (get node "kind") (get node "v")))))


(defn constructible?
  "False when the input contains a host value this host cannot build."
  [value]
  (not (some #(= not-constructible %)
             (tree-seq coll? seq value))))


(defn dsl-member-count
  "How many members the DSL lists for a map or set input node, else nil."
  [node]
  (case (get node "t")
    ("map" "sorted-map") (count (get node "entries"))
    ("set" "sorted-set") (count (get node "items"))
    nil))
