(ns dao.data.arrays
  "Host-array manipulation layer for dao.data.btree
  (docs/design/dao.data.btree.md §3.2).

  Node fields hold exactly these host arrays — Object[] on the JVM,
  js/Array on ClojureScript, List on ClojureDart — so the node types and
  this layer cannot drift apart.

  `acopy` takes (src src-pos dest dest-pos len), the System/arraycopy
  shape. This is a deliberate departure from psset's end-exclusive
  (from from-start from-end to to-start) — see §3.3.3 of the design doc.

  Index/length params carry JVM-only `^long` hints (`#?(:cljd x :clj ^long x
  :cljs x)` — `:cljd` MUST come first: ClojureDart's host-eval pass also
  matches `:clj` branches, so a `:clj`/`:default` pair leaks the JVM tag
  into cljd and fails with \"Can't resolve type long\"; elided to a plain
  symbol on cljs/cljd, where the tag is inert metadata off the JVM, never
  a coercion, so this changes nothing there once ordered correctly). JFR
  profiling of dao.data.btree (docs/design/dao.data.btree.md §6 Phase 3
  benchmark record) found ~80% of the tree's own `java.lang.Long`
  allocation traced to `search` boxing its loop-control longs at every
  call boundary into this namespace; hinting the boundary here is
  required for the hint at the call site to actually stick — an unhinted
  `aget`/`aset`/etc. re-boxes on the way in."
  (:refer-clojure :exclude [aget aset alength aclone]))


(defn anew
  "A new host array of n slots, all nil."
  [#?(:cljd n
      :clj ^long n
      :cljs n)]
  #?(:cljd (object-array (int n))
     :clj (object-array (int n))
     :cljs (let [a (js/Array. n)]
             (.fill a nil)
             a)))


(defn aget
  [arr
   #?(:cljd i
      :clj ^long i
      :cljs i)]
  #?(:cljd (clojure.core/aget arr (int i))
     :clj (clojure.core/aget ^objects arr (int i))
     :cljs (clojure.core/aget arr i)))


(defn- refuse
  [entry detail]
  (throw (ex-info (str "dao.data.arrays refused: " entry " out-of-range (" detail ")")
                  {:dao.host/refusal :out-of-range :dao.host/entry entry :detail detail})))


#?(:cljs
   (defn- index?
     "True when i is an integer Number in [0, n)."
     [i n]
     (and (js/Number.isInteger i) (<= 0 i) (< i n))))


(defn aset
  "Kind M (docs/design/dao.stream.host-boundary.md §2.4): writes v into
   slot i of arr and returns v; an index outside [0, length) is refused
   before any write, so the array is unchanged (the JVM and Dart refuse
   natively; on JS, where an out-of-range write would extend the array
   or set a property, the index is checked first)."
  [arr
   #?(:cljd i
      :clj ^long i
      :cljs i) v]
  #?(:cljd (clojure.core/aset arr (int i) v)
     :clj (clojure.core/aset ^objects arr (int i) v)
     :cljs (if (index? i (.-length arr))
             (clojure.core/aset arr i v)
             (refuse 'aset (str "index " i " of " (.-length arr))))))


(defn alength
  #?(:cljd [arr]
     :clj ^long [arr]
     :cljs [arr])
  #?(:cljd (clojure.core/alength arr)
     :clj (clojure.core/alength ^objects arr)
     :cljs (clojure.core/alength arr)))


(defn aclone
  [arr]
  #?(:cljd (clojure.core/aclone arr)
     :clj (clojure.core/aclone ^objects arr)
     :cljs (.slice arr)))


#_{:clj-kondo/ignore [:unused-private-var]} ; JS and Dart only
(defn- check-range!
  "Refuses unless pos and len are integers with 0 <= pos, 0 <= len and
   pos + len <= n."
  [what pos len n]
  (when-not (and (int? pos) (int? len) (<= 0 pos) (<= 0 len) (<= (+ pos len) n))
    (refuse 'acopy (str what " range " pos "+" len " of " n))))


(defn acopy
  "Kind M (§2.4): copy len slots from src[src-pos ...] into
   dest[dest-pos ...]; returns nil. Every index and length is checked
   before any write, so a refused call leaves dest unchanged (the JVM's
   `System/arraycopy` checks first; JS and Dart check here). Overlapping
   ranges within the same array behave as memmove. 5 params: over
   Clojure's 4-arg ceiling for primitive-typed args (\"fns taking
   primitives support only 4 or fewer args\"), so this stays boxed at its
   own boundary — its per-call cost is amortized over the copy length,
   unlike the per-element cost in search's own arithmetic, which is where
   the hinting actually pays off (see the ns docstring). stitch-all/
   stitch-one in dao.data.btree carry their running offset on a StitchAt
   deftype's `^long` field instead — a different mechanism, not this one,
   since they hit the same 4-arg ceiling `acopy` does and a name-tagged
   return-only hint (the escape hatch that works for `acopy`-arity
   functions in general) turned out to produce no primitive interface
   for a function with zero primitive-tagged args (verified via
   `(supers (class f))`)."
  [src src-pos dest dest-pos len]
  #?(:cljd (do (check-range! "source" src-pos len (alength src))
               (check-range! "destination" dest-pos len (alength dest))
               (if (and (identical? src dest) (> dest-pos src-pos))
                 (loop [i (dec len)]
                   (when (>= i 0)
                     (aset dest (+ dest-pos i) (aget src (+ src-pos i)))
                     (recur (dec i))))
                 (loop [i 0]
                   (when (< i len)
                     (aset dest (+ dest-pos i) (aget src (+ src-pos i)))
                     (recur (inc i))))))
     :clj (System/arraycopy ^objects src
                            (int src-pos)
                            ^objects dest
                            (int dest-pos)
                            (int len))
     :cljs (do (check-range! "source" src-pos len (alength src))
               (check-range! "destination" dest-pos len (alength dest))
               (if (and (identical? src dest) (> dest-pos src-pos))
                 (loop [i (dec len)]
                   (when (>= i 0)
                     (clojure.core/aset dest (+ dest-pos i) (clojure.core/aget src (+ src-pos i)))
                     (recur (dec i))))
                 (loop [i 0]
                   (when (< i len)
                     (clojure.core/aset dest (+ dest-pos i) (clojure.core/aget src (+ src-pos i)))
                     (recur (inc i))))))))


(defn asub
  "New array holding arr[from ... to)."
  [arr
   #?(:cljd from
      :clj ^long from
      :cljs from)
   #?(:cljd to
      :clj ^long to
      :cljs to)]
  (let [n (- to from)
        r (anew n)]
    (acopy arr from r 0 n)
    r))


(defn aconcat
  "New array holding a[0 ... a-len) followed by b[0 ... b-len)."
  [a
   #?(:cljd a-len
      :clj ^long a-len
      :cljs a-len) b
   #?(:cljd b-len
      :clj ^long b-len
      :cljs b-len)]
  (let [r (anew (+ a-len b-len))]
    (acopy a 0 r 0 a-len)
    (acopy b 0 r a-len b-len)
    r))


#?(:cljd
   (defn- merge-sort!
     "Stable merge sort of arr in place by cmp (Dart's List.sort is not
      stable): bottom-up runs merged through one scratch array."
     [arr cmp]
     (let [n (alength arr)]
       (loop [width 1
              from arr
              to (anew n)]
         (if (>= width n)
           (when-not (identical? from arr) (acopy from 0 arr 0 n))
           (do (loop [lo 0]
                 (when (< lo n)
                   (let [mid (min n (+ lo width))
                         hi (min n (+ lo width width))]
                     (loop [i lo j mid k lo]
                       (when (< k hi)
                         (if (and (< i mid)
                                  (or (>= j hi)
                                      (<= (cmp (aget from i) (aget from j)) 0)))
                           (do (aset to k (aget from i)) (recur (inc i) j (inc k)))
                           (do (aset to k (aget from j)) (recur i (inc j) (inc k))))))
                     (recur hi))))
               (recur (* 2 width) to from)))))))


(defn asort
  "Kind M (§2.4): sort arr in place by cmp (a comparator answering a
   negative, zero or positive number) and return arr. Stable on every
   host: elements cmp ranks equal keep their input order. The JVM uses
   `Arrays/sort` (TimSort, stable by contract), JS `Array.prototype.sort`
   (stable since ES2019), Dart a merge sort here, because `List.sort` is
   not stable."
  [arr cmp]
  #?(:cljd (do (merge-sort! arr cmp) arr)
     :clj (do (java.util.Arrays/sort ^objects arr ^java.util.Comparator cmp)
              arr)
     :cljs (do (.sort arr cmp) arr)))


(defn ainsert-at
  "New array of arr[0 ... len) with x inserted at idx (result length len+1)."
  [arr
   #?(:cljd len
      :clj ^long len
      :cljs len)
   #?(:cljd idx
      :clj ^long idx
      :cljs idx) x]
  (let [r (anew (inc len))]
    (acopy arr 0 r 0 idx)
    (aset r idx x)
    (acopy arr idx r (inc idx) (- len idx))
    r))


(defn aremove-at
  "New array of arr[0 ... len) with slot idx removed (result length len-1)."
  [arr
   #?(:cljd len
      :clj ^long len
      :cljs len)
   #?(:cljd idx
      :clj ^long idx
      :cljs idx)]
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
