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
                       ["dart:typed_data" :as typed])]
      :clj [(:require [clojure.data.json :as json])]))


(def path
  "The corpus, relative to the repository root."
  "test/resources/dao/jing/cbor-v1.json")


(defn read-text
  "The corpus file's text."
  []
  #?(:cljd (.readAsStringSync (dart-io/File. path))
     :clj (slurp path)
     :cljs (.readFileSync (js/require "fs") path "utf8")))


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
