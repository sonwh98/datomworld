(ns dao.host.bytes
  "The host byte arrays of the primitive profile
   (docs/design/dao.stream.host-boundary.md §4.2, table A): `byte[]` on
   the JVM, `Uint8Array` on JS, `Uint8List` on Dart. A byte is read and
   written as a native integer 0 to 255 on every host.

   P entries (§2.4): `bytes?`, `alloc`, `length`, `get`, `slice`,
   `equal?`, `from-vector`, `to-vector`. They leave every argument
   byte-identical; `alloc`, `slice` and `from-vector` return fresh
   storage, never a view, and the caller holds the exclusive mutation
   right to it until it is published (§2.4).

   M entries: `set!` and `copy!` mutate exactly their named target, never
   retain it, and return nil. Every index, length and byte value is
   checked before any write, so a refused call leaves the target
   byte-identical. `copy!` takes `(src src-pos dest dest-pos len)`, the
   `System/arraycopy` shape of `dao.data.arrays/acopy`, and behaves as
   memmove when `src` and `dest` are one array.

   Oracle: `dao.host.bytes-test` (table D). A refusal throws `ex-info`
   whose data carries `:dao.host/refusal` (`:out-of-range` for an index,
   length or byte value, `:unsupported-value` for a non-byte-array) and
   `:dao.host/entry`. Every reader conditional names `:cljd` first,
   because the ClojureDart build also reads `:clj`."
  (:refer-clojure :exclude [bytes? get set!])
  #?(:cljd (:import ["dart:typed_data" Uint8List])))


(defn- refuse
  [entry cls detail]
  (throw (ex-info (str "dao.host refused: " entry " " (name cls) " (" detail ")")
                  {:dao.host/refusal cls :dao.host/entry entry :detail detail})))


(defn bytes?
  "P. True when `x` is a host byte array."
  [x]
  #?(:cljd (dart/is? x Uint8List)
     :clj (clojure.core/bytes? x)
     :cljs (instance? js/Uint8Array x)))


(defn- native-int?
  [x]
  #?(:cljd (dart/is? x int)
     :clj (or (instance? Long x) (instance? Integer x)
              (instance? Short x) (instance? Byte x))
     :cljs (js/Number.isSafeInteger x)))


(defn- check-bytes!
  [entry bs]
  (when-not (bytes? bs)
    (refuse entry :unsupported-value "not a host byte array")))


(defn- check-index!
  "Refuse unless native integer `i` lies in [lo, hi]."
  [entry i lo hi]
  (when-not (and (native-int? i) (<= lo i) (<= i hi))
    (refuse entry :out-of-range (str i " not in [" lo ", " hi "]"))))


(defn- check-byte!
  [entry v]
  (when-not (and (native-int? v) (<= 0 v) (<= v 255))
    (refuse entry :out-of-range (str v " is not a byte 0-255"))))


(defn length
  "P. The number of bytes in `bs`."
  [bs]
  (check-bytes! 'length bs)
  #?(:cljd (.-length ^Uint8List bs)
     :clj (alength ^bytes bs)
     :cljs (.-length bs)))


(defn alloc
  "P, fresh. A new host byte array of `n` zero bytes, `n` a native
   integer from 0."
  [n]
  (when-not (and (native-int? n) (<= 0 n))
    (refuse 'alloc :out-of-range (str n " is not a length")))
  #?(:cljd (Uint8List. n)
     :clj (byte-array (int n))
     :cljs (js/Uint8Array. n)))


(defn- byte-at
  "The byte at an index already checked."
  [bs i]
  #?(:cljd (. ^Uint8List bs "[]" i)
     :clj (bit-and (aget ^bytes bs (int i)) 0xff)
     :cljs (aget bs i)))


(defn get
  "P. The byte at index `i` of `bs`, 0 to 255."
  [bs i]
  (check-index! 'get i 0 (dec (length bs)))
  (byte-at bs i))


(defn slice
  "P, fresh. A new host byte array holding `bs[start, end)`, for
   0 <= start <= end <= length."
  [bs start end]
  (let [n (length bs)]
    (check-index! 'slice start 0 n)
    (check-index! 'slice end start n))
  #?(:cljd (Uint8List.fromList (.sublist ^Uint8List bs start end))
     :clj (java.util.Arrays/copyOfRange ^bytes bs (int start) (int end))
     :cljs (.slice bs start end)))


(defn equal?
  "P. True when byte arrays `a` and `b` hold the same bytes."
  [a b]
  (let [n (length a)]
    (and (= n (length b))
         (loop [i 0]
           (or (= i n)
               (and (= (byte-at a i) (byte-at b i))
                    (recur (inc i))))))))


(defn from-vector
  "P, fresh. A new host byte array of `xs`, a vector of native integers
   0 to 255; any other element is refused `:out-of-range` before
   anything is built."
  [xs]
  (when-not (vector? xs)
    (refuse 'from-vector :unsupported-value "not a vector"))
  (run! #(check-byte! 'from-vector %) xs)
  #?(:cljd (Uint8List.fromList xs)
     :clj (byte-array (map unchecked-byte xs))
     :cljs (js/Uint8Array.from (into-array xs))))


(defn to-vector
  "P. The bytes of `bs` as a vector of native integers 0 to 255."
  [bs]
  (let [n (length bs)]
    (loop [i 0
           out (transient [])]
      (if (< i n)
        (recur (inc i) (conj! out (byte-at bs i)))
        (persistent! out)))))


(defn set!
  "M. Write byte `v`, 0 to 255, at index `i` of target `bs`; nil. A bad
   index or byte is refused before the write."
  [bs i v]
  (check-index! 'set! i 0 (dec (length bs)))
  (check-byte! 'set! v)
  #?(:cljd (. ^Uint8List bs "[]=" i v)
     :clj (aset ^bytes bs (int i) (unchecked-byte v))
     :cljs (aset bs i v))
  nil)


(defn copy!
  "M. Copy `len` bytes from `src[src-pos ...]` into target
   `dest[dest-pos ...]`; nil. Both ranges are checked before any write,
   and overlapping ranges in one array copy as memmove."
  [src src-pos dest dest-pos len]
  (let [sn (length src)
        dn (length dest)]
    (check-index! 'copy! len 0 (min sn dn))
    (check-index! 'copy! src-pos 0 (- sn len))
    (check-index! 'copy! dest-pos 0 (- dn len)))
  #?(:cljd (.setRange ^Uint8List dest dest-pos (+ dest-pos len)
                      (if (identical? src dest)
                        (.sublist ^Uint8List src src-pos (+ src-pos len))
                        src)
                      (if (identical? src dest) 0 src-pos))
     :clj (System/arraycopy ^bytes src (int src-pos) ^bytes dest (int dest-pos) (int len))
     :cljs (.set dest (.subarray src src-pos (+ src-pos len)) dest-pos))
  nil)
