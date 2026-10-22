(ns dao.data.sorted-user-type-test
  #?@(:cljd [] :cljs []
      :clj [(:require [clojure.test :refer [deftest is testing]])]))


#?(:cljd nil :cljs nil
   :clj
   (do
     (deftype UserSorted
       [xs cmp]

       clojure.lang.Seqable

       (seq [_] (seq xs))


       clojure.lang.Sorted

       (comparator [_] cmp)


       (entryKey [_ e] e)


       (seq [_ ascending] (seq (if ascending xs (reverse xs))))


       (seqFrom
         [_ k ascending]
         (seq (drop-while #(if ascending
                             (neg? (cmp % k))
                             (pos? (cmp % k)))
                          (if ascending xs (reverse xs)))))


       clojure.lang.Reversible

       (rseq [_] (seq (reverse xs))))

     (deftype Unsorted
       [])

     (deftest sorted-predicate-test
       (is (sorted? (UserSorted. [1 2 3] compare)))
       (is (sorted? (sorted-set 1)))
       (is (sorted? (sorted-map 1 2)))
       (is (false? (sorted? [1])))
       (is (false? (sorted? nil)))
       (is (false? (sorted? (Unsorted.))))
       (is (thrown? ClassCastException (subseq (Unsorted.) > 1)))
       (is (thrown? ClassCastException (rsubseq (Unsorted.) < 1))))

     (deftest bounds-test
       (doseq [cmp [compare (fn [a b] (compare b a))]]
         (let [xs (vec (sort cmp (range 0 200 3)))
               s (UserSorted. xs cmp)
               oracle (apply sorted-set-by cmp xs)]
           (doseq [k [-5 0 1 3 99 100 197 198 300]
                   bound [> >= < <=]]
             (testing (str "single bound " k)
               (is (= (seq (subseq oracle bound k)) (seq (subseq s bound k))))
               (is (= (seq (rsubseq oracle bound k)) (seq (rsubseq s bound k))))))
           (doseq [[lo hi] [[0 199] [3 99] [4 98] [-10 500] [99 99] [100 100] [98 4]]
                   [start end] [[> <] [>= <=] [> <=] [>= <]]]
             (testing (str "double bound " lo " " hi)
               (is (= (seq (subseq oracle start lo end hi))
                      (seq (subseq s start lo end hi))))
               (is (= (seq (rsubseq oracle start lo end hi))
                      (seq (rsubseq s start lo end hi)))))))))

     (deftest empty-test
       (let [s (UserSorted. [] compare)]
         (is (nil? (seq s)))
         (is (nil? (rseq s)))
         (doseq [bound [> >= < <=]]
           (is (nil? (seq (subseq s bound 1))))
           (is (nil? (seq (rsubseq s bound 1)))))
         (is (nil? (seq (subseq s >= 1 <= 3))))
         (is (nil? (seq (rsubseq s >= 1 <= 3))))))))
