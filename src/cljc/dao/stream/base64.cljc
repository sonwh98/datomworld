(ns dao.stream.base64
  "The strict Base64 codec of the raw datagram layer
   (docs/design/dao.stream.datagram.md 2): padded standard alphabet, no
   whitespace, no URL-safe alphabet, the empty string for zero bytes.

   Table B portable implementation (docs/design/dao.stream.host-boundary.md):
   Pure, deterministic codec over dao.host.bytes without any host library
   (replaces goog.crypt.base64, dart:convert base64Encode/Decode, and java.util.Base64).
   Nothing here knows a datagram: it is bytes in, text out, and the exact
   inverse under text?."
  (:require
    [dao.host.bytes :as bytes]))


(def ^:private text-pattern
  "Padded standard-alphabet Base64, anchored at both ends:
   no whitespace, no URL-safe alphabet. A length-4k tail is 4 alphabet characters,
   a length-4k+2 tail is two characters and two pads, a length-4k+3 tail is three
   characters and one pad, and no other shape is text."
  #"^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$")


(def ^:private ^String alphabet
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")


(def ^:private decode-table
  (let [tbl (into {} (map-indexed (fn [idx ch] [ch idx]) alphabet))]
    tbl))


(defn text?
  "True iff `s` is strict padded standard-alphabet Base64 text, the empty
   string included: exactly what a datagram event's :bytes field carries."
  [s]
  (and (string? s) (boolean (re-matches text-pattern s))))


(defn encode
  "The Base64 text of host bytes `bs` (byte[] / Uint8Array / Uint8List):
   the transport representation of a datagram payload on any stream, so
   that a traffic stream survives any dao.stream codec."
  [bs]
  (let [n (bytes/length bs)]
    (if (zero? n)
      ""
      (let [sb #?(:cljd (StringBuffer.)
                  :clj (StringBuilder.)
                  :cljs (js/Array.))
            append! #?(:cljd (fn [^String s] (.write ^StringBuffer sb s))
                       :clj (fn [^String s] (.append ^StringBuilder sb s))
                       :cljs (fn [s] (.push sb s)))
            full-triples (quot n 3)
            remainder (rem n 3)]
        (dotimes [t full-triples]
          (let [i (* t 3)
                b0 (bytes/get bs i)
                b1 (bytes/get bs (+ i 1))
                b2 (bytes/get bs (+ i 2))
                triple (bit-or (bit-shift-left b0 16)
                               (bit-shift-left b1 8)
                               b2)
                c0 (nth alphabet (bit-and (unsigned-bit-shift-right triple 18) 63))
                c1 (nth alphabet (bit-and (unsigned-bit-shift-right triple 12) 63))
                c2 (nth alphabet (bit-and (unsigned-bit-shift-right triple 6) 63))
                c3 (nth alphabet (bit-and triple 63))]
            (append! (str c0 c1 c2 c3))))
        (when (pos? remainder)
          (let [i (* full-triples 3)]
            (if (= remainder 1)
              (let [b0 (bytes/get bs i)
                    triple (bit-shift-left b0 16)
                    c0 (nth alphabet (bit-and (unsigned-bit-shift-right triple 18) 63))
                    c1 (nth alphabet (bit-and (unsigned-bit-shift-right triple 12) 63))]
                (append! (str c0 c1 "==")))
              (let [b0 (bytes/get bs i)
                    b1 (bytes/get bs (+ i 1))
                    triple (bit-or (bit-shift-left b0 16) (bit-shift-left b1 8))
                    c0 (nth alphabet (bit-and (unsigned-bit-shift-right triple 18) 63))
                    c1 (nth alphabet (bit-and (unsigned-bit-shift-right triple 12) 63))
                    c2 (nth alphabet (bit-and (unsigned-bit-shift-right triple 6) 63))]
                (append! (str c0 c1 c2 "="))))))
        #?(:cljd (.toString ^StringBuffer sb)
           :clj (.toString ^StringBuilder sb)
           :cljs (.join sb ""))))))


(defn decode
  "The host bytes of Base64 `s`, decoded strictly -- nil, never a throw,
   for anything that is not strict text: a layer that refuses a value
   answers an outcome, and its codec is total like it."
  [s]
  (when (text? s)
    (let [len (count s)]
      (if (zero? len)
        (bytes/alloc 0)
        (let [pad (cond
                    (and (>= len 2) (= \= (nth s (- len 1))) (= \= (nth s (- len 2)))) 2
                    (and (>= len 1) (= \= (nth s (- len 1)))) 1
                    :else 0)
              out-len (- (* (quot len 4) 3) pad)
              blocks (quot len 4)
              byte-vec
              (loop [b 0
                     acc (transient [])]
                (if (< b blocks)
                  (let [i (* b 4)
                        c0 (nth s i)
                        c1 (nth s (+ i 1))
                        c2 (nth s (+ i 2))
                        c3 (nth s (+ i 3))
                        v0 (decode-table c0)
                        v1 (decode-table c1)
                        v2 (if (= c2 \=) 0 (decode-table c2))
                        v3 (if (= c3 \=) 0 (decode-table c3))
                        triple (bit-or (bit-shift-left v0 18)
                                       (bit-shift-left v1 12)
                                       (bit-shift-left v2 6)
                                       v3)
                        b0 (bit-and (unsigned-bit-shift-right triple 16) 0xff)
                        b1 (bit-and (unsigned-bit-shift-right triple 8) 0xff)
                        b2 (bit-and triple 0xff)
                        idx (* b 3)]
                    (cond
                      (<= out-len idx)
                      (recur (inc b) acc)

                      (= out-len (+ idx 1))
                      (recur (inc b) (conj! acc b0))

                      (= out-len (+ idx 2))
                      (recur (inc b) (conj! (conj! acc b0) b1))

                      :else
                      (recur (inc b) (conj! (conj! (conj! acc b0) b1) b2))))
                  (persistent! acc)))]
          (bytes/from-vector byte-vec))))))
