(ns dao.data.arrays-test
  "The §2.4 laws of dao.data.arrays' M entries
   (docs/design/dao.stream.host-boundary.md, table D arrays row, slice
   H2): `asort` is stable, `acopy` behaves as memmove on one array, and a
   refused `acopy` or `aset` leaves its target unchanged on every lane."
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.data.arrays :as arr]))


(defn- snapshot
  [a]
  (mapv #(arr/aget a %) (range (arr/alength a))))


(defn- refused?
  "True when (f) throws."
  [f]
  (try (f) false
       (catch #?(:cljd Object :clj Throwable :cljs :default) _ true)))


(deftest asort-is-stable
  (testing "elements the comparator ranks equal keep their input order"
    ;; mapv, not for: ClojureDart's for over a long chunked seq can hand
    ;; its body nil
    (let [input (mapv (fn [i] [(mod (* 7 i) 5) i]) (range 200))
          a (arr/from-coll input)
          by-key (fn [x y] (compare (first x) (first y)))
          result (arr/asort a by-key)]
      (is (identical? a result) "sorts in place and returns its argument")
      ;; the oracle uses no host sort (ClojureDart's sort-by is List.sort,
      ;; which is not stable): each key's elements in input order
      (is (= (vec (mapcat (fn [k] (filter #(= k (first %)) input)) (range 5)))
             (snapshot a)))
      (doseq [[_ group] (group-by first (snapshot a))]
        (is (apply < (map second group))))))
  (testing "an empty and a one-slot array"
    (is (= [] (snapshot (arr/asort (arr/anew 0) compare))))
    (is (= [3] (snapshot (arr/asort (arr/from-coll [3]) compare))))))


(deftest acopy-overlap-is-memmove
  (testing "forward overlap (dest after src)"
    (let [a (arr/from-coll (range 10))]
      (is (nil? (arr/acopy a 1 a 3 5)))
      (is (= [0 1 2 1 2 3 4 5 8 9] (snapshot a)))))
  (testing "backward overlap (dest before src)"
    (let [a (arr/from-coll (range 10))]
      (is (nil? (arr/acopy a 3 a 1 5)))
      (is (= [0 3 4 5 6 7 6 7 8 9] (snapshot a)))))
  (testing "between two arrays, and a zero-length copy at the end"
    (let [src (arr/from-coll [:a :b :c])
          dest (arr/from-coll (range 5))]
      (arr/acopy src 0 dest 2 3)
      (is (= [0 1 :a :b :c] (snapshot dest)))
      (arr/acopy src 3 dest 5 0)
      (is (= [0 1 :a :b :c] (snapshot dest))))))


(deftest refused-writes-leave-the-target-unchanged
  (testing "acopy: every invalid index or length is refused before any write"
    (doseq [[label src-pos dest-pos len] [["source offset past the end" 4 0 3]
                                          ["negative source offset" -1 0 3]
                                          ["negative destination offset" 0 -1 3]
                                          ["destination range overflows, source valid" 0 4 3]
                                          ["length past both ends" 0 0 9]
                                          ["negative length" 0 0 -1]]]
      (let [src (arr/from-coll [:a :b :c :d :e])
            dest (arr/from-coll (range 6))
            before (snapshot dest)]
        (is (refused? #(arr/acopy src src-pos dest dest-pos len)) label)
        (is (= before (snapshot dest)) label)
        (is (= [:a :b :c :d :e] (snapshot src)) label))))
  (testing "acopy within one array: an overflowing overlap writes nothing"
    (let [a (arr/from-coll (range 6))]
      (is (refused? #(arr/acopy a 0 a 2 5)))
      (is (= [0 1 2 3 4 5] (snapshot a)))))
  (testing "aset: an index outside [0, length) is refused and writes nothing"
    (doseq [i [-1 3 4 100]]
      (let [a (arr/from-coll [:x :y :z])]
        (is (refused? #(arr/aset a i :w)) (str "index " i))
        (is (= [:x :y :z] (snapshot a)) (str "index " i))
        (is (= 3 (arr/alength a)) (str "index " i)))))
  (testing "aset returns the assigned value"
    (let [a (arr/from-coll [:x :y :z])]
      (is (= :w (arr/aset a 1 :w)))
      (is (= [:x :w :z] (snapshot a))))))
