(ns dao.host.bytes-test
  "Table D's bytes row (docs/design/dao.stream.host-boundary.md §4.3,
   slice H1): `dao.host.bytes` round trip, slice freshness, `copy!`
   overlap as memmove in both directions, out-of-range refusal with no
   partial write (the target byte-identical after a refused `set!` or
   `copy!`), and `equal?` on equal and unequal content. The P-entry
   argument-immutability property is in `dao.host.conformance-test`."
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.host.bytes :as bytes]))


(defn- refusal
  [f]
  (try
    [:returned (f)]
    (catch #?(:cljd Object :clj Exception :cljs :default) e
      (:dao.host/refusal (ex-data e)))))


(deftest round-trip-test
  (doseq [v [[] [0] [255] [0 1 127 128 254 255] (vec (range 256))]]
    (let [bs (bytes/from-vector v)]
      (is (bytes/bytes? bs))
      (is (= (count v) (bytes/length bs)))
      (is (= v (bytes/to-vector bs)))
      (is (= v (mapv #(bytes/get bs %) (range (count v)))))))
  (is (= [0 0 0 0] (bytes/to-vector (bytes/alloc 4))))
  (is (= 0 (bytes/length (bytes/alloc 0))))
  (is (not (bytes/bytes? [1 2])))
  (is (not (bytes/bytes? "ab")))
  (is (not (bytes/bytes? nil))))


(deftest slice-test
  (let [bs (bytes/from-vector [10 20 30 40 50])]
    (is (= [20 30 40] (bytes/to-vector (bytes/slice bs 1 4))))
    (is (= [] (bytes/to-vector (bytes/slice bs 5 5))))
    (is (= [10 20 30 40 50] (bytes/to-vector (bytes/slice bs 0 5))))
    (testing "fresh: writing the slice leaves the source, and the reverse"
      (let [s (bytes/slice bs 1 4)]
        (bytes/set! s 0 99)
        (is (= [10 20 30 40 50] (bytes/to-vector bs)))
        (bytes/set! bs 2 77)
        (is (= [99 30 40] (bytes/to-vector s)))))
    (testing "a range outside the array, or reversed, is refused"
      (is (= :out-of-range (refusal #(bytes/slice bs 0 6))))
      (is (= :out-of-range (refusal #(bytes/slice bs -1 2))))
      (is (= :out-of-range (refusal #(bytes/slice bs 3 2)))))))


(deftest equal-test
  (is (bytes/equal? (bytes/from-vector [1 2 3]) (bytes/from-vector [1 2 3])))
  (is (bytes/equal? (bytes/alloc 0) (bytes/from-vector [])))
  (is (not (bytes/equal? (bytes/from-vector [1 2 3]) (bytes/from-vector [1 2 4]))))
  (is (not (bytes/equal? (bytes/from-vector [1 2 3]) (bytes/from-vector [1 2]))))
  (is (not (bytes/equal? (bytes/from-vector [255]) (bytes/from-vector [0])))))


(deftest copy-test
  (testing "between arrays"
    (let [src (bytes/from-vector [1 2 3 4])
          dst (bytes/alloc 6)]
      (is (nil? (bytes/copy! src 1 dst 2 3)))
      (is (= [0 0 2 3 4 0] (bytes/to-vector dst)))
      (is (= [1 2 3 4] (bytes/to-vector src)))))
  (testing "overlap, forward: memmove"
    (let [bs (bytes/from-vector [1 2 3 4 5 6])]
      (bytes/copy! bs 0 bs 2 4)
      (is (= [1 2 1 2 3 4] (bytes/to-vector bs)))))
  (testing "overlap, backward: memmove"
    (let [bs (bytes/from-vector [1 2 3 4 5 6])]
      (bytes/copy! bs 2 bs 0 4)
      (is (= [3 4 5 6 5 6] (bytes/to-vector bs)))))
  (testing "zero length at the end is allowed"
    (let [bs (bytes/from-vector [1 2])]
      (bytes/copy! bs 2 bs 2 0)
      (is (= [1 2] (bytes/to-vector bs))))))


(deftest set-test
  (let [bs (bytes/alloc 3)]
    (is (nil? (bytes/set! bs 0 255)))
    (bytes/set! bs 2 128)
    (is (= [255 0 128] (bytes/to-vector bs)))
    (is (= 255 (bytes/get bs 0)))))


(deftest refusals-leave-the-target-byte-identical
  (let [fresh #(bytes/from-vector [1 2 3 4 5])]
    (testing "set!: a bad index or a non-byte value"
      ;; JS cannot tell 1.0 from 1, so a float index is a JVM and Dart case
      (doseq [[i v] (cond-> [[5 0] [-1 0] [0 256] [0 -1] [0 1.5] [nil 1]]
                      (not= :js #?(:cljd :dart :clj :jvm :cljs :js)) (conj [1.0 1]))]
        (let [bs (fresh)]
          (is (= :out-of-range (refusal #(bytes/set! bs i v))) (pr-str [i v]))
          (is (= [1 2 3 4 5] (bytes/to-vector bs)) (pr-str [i v])))))
    (testing "copy!: a source or destination range outside its array, a bad length"
      (doseq [[sp dp len] [[0 3 3] [3 0 3] [0 0 6] [-1 0 1] [0 -1 1] [0 0 -1] [4 0 2]]]
        (let [bs (fresh)
              dst (fresh)]
          (is (= :out-of-range (refusal #(bytes/copy! bs sp dst dp len))) (pr-str [sp dp len]))
          (is (= :out-of-range (refusal #(bytes/copy! bs sp bs dp len))) (pr-str [sp dp len]))
          (is (= [1 2 3 4 5] (bytes/to-vector bs)))
          (is (= [1 2 3 4 5] (bytes/to-vector dst))))))
    (testing "the destination is checked before the first write, even when the source range is valid"
      (let [src (bytes/from-vector [9 9 9])
            dst (bytes/from-vector [1 2])]
        (is (= :out-of-range (refusal #(bytes/copy! src 0 dst 0 3))))
        (is (= [1 2] (bytes/to-vector dst))))))
  (testing "P entries refuse a bad argument"
    (is (= :out-of-range (refusal #(bytes/get (bytes/alloc 2) 2))))
    (is (= :out-of-range (refusal #(bytes/get (bytes/alloc 0) 0))))
    (is (= :out-of-range (refusal #(bytes/alloc -1))))
    (is (= :out-of-range (refusal #(bytes/from-vector [1 256]))))
    (is (= :out-of-range (refusal #(bytes/from-vector [-1]))))
    (is (= :unsupported-value (refusal #(bytes/from-vector '(1 2)))))
    (is (= :unsupported-value (refusal #(bytes/length [1 2]))))))
