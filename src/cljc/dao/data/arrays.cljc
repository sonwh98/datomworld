(ns dao.data.arrays
  "Host-array manipulation layer for dao.data.btree
  (docs/design/dao.data.btree.md §3.2).

  Node fields hold exactly these host arrays — Object[] on the JVM,
  js/Array on ClojureScript, List on ClojureDart — so the node types and
  this layer cannot drift apart.

  `acopy` takes (src src-pos dest dest-pos len), the System/arraycopy
  shape. This is a deliberate departure from psset's end-exclusive
  (from from-start from-end to to-start) — see §3.3.3 of the design doc."
  (:refer-clojure :exclude [aget aset alength aclone]))


(defn anew
  "A new host array of n slots, all nil."
  [n]
  #?(:cljd (object-array (int n))
     :clj (object-array (int n))
     :cljs (let [a (js/Array. n)]
             (.fill a nil)
             a)))


(defn aget
  [arr i]
  #?(:cljd (clojure.core/aget arr (int i))
     :clj (clojure.core/aget ^objects arr (int i))
     :cljs (clojure.core/aget arr i)))


(defn aset
  [arr i v]
  #?(:cljd (clojure.core/aset arr (int i) v)
     :clj (clojure.core/aset ^objects arr (int i) v)
     :cljs (clojure.core/aset arr i v)))


(defn alength
  [arr]
  #?(:cljd (clojure.core/alength arr)
     :clj (clojure.core/alength ^objects arr)
     :cljs (clojure.core/alength arr)))


(defn aclone
  [arr]
  #?(:cljd (clojure.core/aclone arr)
     :clj (clojure.core/aclone ^objects arr)
     :cljs (.slice arr)))


(defn acopy
  "Copy len slots from src[src-pos ...] into dest[dest-pos ...].
   Safe for overlapping ranges within the same array."
  [src src-pos dest dest-pos len]
  #?(:clj (System/arraycopy ^objects src
                            (int src-pos)
                            ^objects dest
                            (int dest-pos)
                            (int len))
     :default (if (and (identical? src dest) (> dest-pos src-pos))
                (loop [i (dec len)]
                  (when (>= i 0)
                    (aset dest (+ dest-pos i) (aget src (+ src-pos i)))
                    (recur (dec i))))
                (loop [i 0]
                  (when (< i len)
                    (aset dest (+ dest-pos i) (aget src (+ src-pos i)))
                    (recur (inc i)))))))


(defn asub
  "New array holding arr[from ... to)."
  [arr from to]
  (let [n (- to from)
        r (anew n)]
    (acopy arr from r 0 n)
    r))


(defn aconcat
  "New array holding a[0 ... a-len) followed by b[0 ... b-len)."
  [a a-len b b-len]
  (let [r (anew (+ a-len b-len))]
    (acopy a 0 r 0 a-len)
    (acopy b 0 r a-len b-len)
    r))


(defn asort
  "Sort arr in place by cmp; returns arr."
  [arr cmp]
  #?(:cljd (do (.sort ^List arr cmp) arr)
     :clj (do (java.util.Arrays/sort ^objects arr ^java.util.Comparator cmp)
              arr)
     :cljs (do (.sort arr cmp) arr)))


(defn ainsert-at
  "New array of arr[0 ... len) with x inserted at idx (result length len+1)."
  [arr len idx x]
  (let [r (anew (inc len))]
    (acopy arr 0 r 0 idx)
    (aset r idx x)
    (acopy arr idx r (inc idx) (- len idx))
    r))


(defn aremove-at
  "New array of arr[0 ... len) with slot idx removed (result length len-1)."
  [arr len idx]
  (let [r (anew (dec len))]
    (acopy arr 0 r 0 idx)
    (acopy arr (inc idx) r idx (- len (inc idx)))
    r))


(defn from-coll
  "Host array holding the elements of coll."
  [coll]
  #?(:cljd (object-array coll)
     :clj (object-array coll)
     :cljs (into-array coll)))
