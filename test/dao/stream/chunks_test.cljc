(ns dao.stream.chunks-test
  (:require [clojure.test :refer [deftest is]]
            [dao.stream.chunks :as chunks]
            [dao.stream.cbor :as cbor]))


(deftest split-and-absorb-out-of-order
  (let [payload (cbor/encode (apply str (repeat 3000 "x")))
        envelope (fn [part parts bytes] {:part part :parts parts :bytes bytes})
        frames (vec (chunks/split payload 1200 envelope cbor/encode))
        pieces (reverse (map-indexed (fn [i frame] [i frame]) frames))
        result (reduce (fn [s [_ frame]]
                         (first (chunks/absorb s :peer (:part frame) (:parts frame)
                                               (:bytes frame) 65536 64)))
                       {:partial {} :partial-order []} pieces)]
    (is (> (count frames) 1))
    (is (every? #(<= (alength (cbor/encode %)) 1200) frames))
    (is (empty? (:partial result)))))


(deftest malformed-duplicate-inconsistent-and-overbound-pieces
  (let [b (cbor/encode :piece)
        initial {:partial {} :partial-order []}
        [held _] (chunks/absorb initial :a 0 2 b 20 1)
        [duplicate _] (chunks/absorb held :a 0 2 b 20 1)
        [inconsistent _] (chunks/absorb held :a 1 3 b 20 1)
        [malformed _] (chunks/absorb held :a -1 2 b 20 1)
        [evicted _] (chunks/absorb held :b 0 2 b 20 1)]
    (is (= held duplicate inconsistent malformed))
    (is (= 1 (count (:partial evicted))))
    (is (contains? (:partial evicted) :b))
    (is (not (contains? (:partial evicted) :a)))
    (is (empty? (:partial (first (chunks/absorb held :a 1 2 b 10 1)))))))
