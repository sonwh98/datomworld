(ns yin.vm.integer-v3-fixtures
  "The float rows of `integer` module version 3, loaded on the JVM, Node
   and Dart.

   test/resources/yin/vm/integer-v3.txt pins CPython 3.9.6's
   `float(int)`, `int / int`, int-float comparison and `int(float)` over
   powers of two, ties, the overflow edge, subnormal quotients and huge
   ratios. integer-v3.generate.py beside it wrote the file; nothing here
   writes one. Integers are decimal text and floats the 16 hex digits of
   their IEEE-754 bits, so no row passes through a host's float reader."
  #?@(:cljd [(:require ["dart:io" :as dart-io]
                       [clojure.string :as str])
             (:import ["dart:typed_data" ByteData])]
      :default [(:require [clojure.string :as str])]))


(def path
  "The fixture, relative to the repository root."
  "test/resources/yin/vm/integer-v3.txt")


(defn- read-text
  []
  #?(:cljd (.readAsStringSync (dart-io/File. path))
     :clj (slurp path)
     :cljs (.readFileSync (js/require "fs") path "utf8")))


(defn read-file
  "The fixture as `{:generator g, :rows {op [[arg ...] ...]}}`, each row
   the words after its op. Blank lines are ignored (Dart keeps a trailing
   one)."
  []
  (let [[magic gen & lines] (remove str/blank? (str/split-lines (read-text)))]
    (when-not (= "integer-v3" magic)
      (throw (ex-info "not an integer-v3 fixture" {:path path})))
    {:generator (subs gen (count "generator ")),
     ;; reduce, not `for`: ClojureDart's chunked `for` can hand the body
     ;; a nil past 32 elements
     :rows (reduce (fn [acc l]
                     (let [[op & args] (str/split l #" ")]
                       (update acc op (fnil conj []) (vec args))))
                   {}
                   lines)}))


#?(:cljd
   (defn- hex-int
     "The value of a short lowercase hex string."
     [h]
     (reduce (fn [acc i]
               (+ (* 16 acc)
                  (str/index-of "0123456789abcdef" (subs h i (inc i)))))
             0
             (range (count h)))))


(defn bits->double
  "The host double with IEEE-754 bits `hex` (16 hex digits)."
  [hex]
  #?(:cljd (let [bd (ByteData. 8)]
             (.setUint32 bd 0 (hex-int (subs hex 0 8)))
             (.setUint32 bd 4 (hex-int (subs hex 8 16)))
             (.getFloat64 bd 0))
     :clj (Double/longBitsToDouble (Long/parseUnsignedLong hex 16))
     :cljs (let [view (js/DataView. (js/ArrayBuffer. 8))]
             (.setUint32 view 0 (js/parseInt (subs hex 0 8) 16))
             (.setUint32 view 4 (js/parseInt (subs hex 8 16) 16))
             (.getFloat64 view 0))))


(defn- pad8
  [s]
  (str (subs "00000000" (count s)) s))


(defn double->bits
  "The 16 lowercase hex digits of host double `x`'s IEEE-754 bits."
  [x]
  #?(:cljd (let [bd (ByteData. 8)]
             (.setFloat64 bd 0 x)
             (str (pad8 (.toRadixString (.getUint32 bd 0) 16))
                  (pad8 (.toRadixString (.getUint32 bd 4) 16))))
     :clj (let [b (Double/doubleToRawLongBits (double x))]
            (str (pad8 (Long/toHexString (unsigned-bit-shift-right b 32)))
                 (pad8 (Long/toHexString (bit-and b 0xffffffff)))))
     :cljs (let [view (js/DataView. (js/ArrayBuffer. 8))]
             (.setFloat64 view 0 x)
             (str (pad8 (.toString (.getUint32 view 0) 16))
                  (pad8 (.toString (.getUint32 view 4) 16))))))
