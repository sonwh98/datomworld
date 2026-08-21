;; Conformance utilities: canonical 1e-6 numeric rounding, the total EDN
;; ordering used by candidate ranking and stable key ordering, and fixture
;; comparison. Specification: docs/design/dao.gui.event.md sections Trace And
;; Numeric Conformance and Recognizer Machine Data.
(ns dao.gui.event.trace
  (:require [clojure.walk :as walk]))


;; ---------------------------------------------------------------------------
;; Canonical numeric rounding
;; ---------------------------------------------------------------------------

(defn- floor-double
  "Portable floor for finite doubles."
  [x]
  (let [l (long x)] (if (< x l) (dec l) l)))


(defn round-1e-6
  "Round to the nearest multiple of 1e-6, ties to the even multiple."
  [v]
  (let [scaled (* (double v) 1e6)
        f (floor-double scaled)
        d (- scaled f)
        rounded (cond (< d 0.5) f
                      (> d 0.5) (inc f)
                      (even? f) f
                      :else (inc f))
        back (/ rounded 1e6)]
    (if (= back (floor-double back)) (long back) back)))


(defn canonical-round
  "Round one numeric leaf for trace encoding. Integers pass through
  unchanged; non-integral numbers are rounded to 1e-6 and collapsed to a
  long when the result is integral."
  [v]
  (cond (or (not (number? v)) (integer? v)) v
        (not= v v) v ; NaN stays visible to comparisons
        :else (round-1e-6 v)))


(defn round-deep
  "Apply canonical-round to every numeric leaf of an EDN value."
  [x]
  (walk/postwalk canonical-round x))


(defn matches?
  "Canonical fixture comparison: structural equality after rounding."
  [expected actual]
  (= (round-deep expected) (round-deep actual)))


;; ---------------------------------------------------------------------------
;; Total EDN ordering
;; ---------------------------------------------------------------------------

(defn- type-rank
  [x]
  (cond (nil? x) 0
        (false? x) 1
        (true? x) 2
        (number? x) 3
        (string? x) 4
        (keyword? x) 5
        (symbol? x) 6
        (vector? x) 7
        (seq? x) 7
        (map? x) 8
        (set? x) 9
        :else 10))


(defn- keyword-parts
  [k]
  [(namespace k) (name k)])


(defn edn-compare
  "A total ordering over EDN values: by type rank first, then by value.
  Vectors compare lexicographically, maps and sets by their sorted
  elements. Host objects that are not EDN fall back to string order."
  [a b]
  (let [ra (type-rank a)
        rb (type-rank b)]
    (if (not= ra rb)
      (compare ra rb)
      (case ra
        3 (compare (double a) (double b))
        4 (compare a b)
        5 (let [[an a-n] (keyword-parts a)
                [bn b-n] (keyword-parts b)
                c (compare (or an "") (or bn ""))]
            (if (zero? c) (compare a-n b-n) c))
        6 (let [c (compare (or (namespace a) "") (or (namespace b) ""))]
            (if (zero? c) (compare (name a) (name b)) c))
        7 (let [av (vec a)
                bv (vec b)
                c (compare (count av) (count bv))]
            (if (zero? c)
              (or (some (fn [[x y]]
                          (let [d (edn-compare x y)] (when (not (zero? d)) d)))
                        (map vector av bv))
                  0)
              c))
        8 (let [c (compare (count a) (count b))]
            (if (zero? c)
              (edn-compare (sort edn-compare (seq a))
                           (sort edn-compare (seq b)))
              c))
        9 (let [c (compare (count a) (count b))]
            (if (zero? c)
              (edn-compare (sort edn-compare (seq a))
                           (sort edn-compare (seq b)))
              c))
        10 (compare (str a) (str b))
        0 0
        1 0
        2 0))))


(defn edn-sort
  [coll]
  (sort edn-compare coll))
