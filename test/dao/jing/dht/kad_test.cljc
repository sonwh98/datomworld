(ns dao.jing.dht.kad-test
  "Contract tests for the pure Kademlia routing primitives: XOR distance
  over hex node ids, bucket indexing, and the k-bucket routing table."
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.jing.dht.kad :as kad]))


(defn- id
  "Pad a hex prefix to a full 64-char node id."
  [prefix]
  (apply str prefix (repeat (- 64 (count prefix)) "0")))


;; ---------------------------------------------------------------------------
;; distance / closer?
;; ---------------------------------------------------------------------------

(deftest distance-is-zero-at-self-and-symmetric
  (testing "distance to self is all zeros, and distance is symmetric"
    (let [a (id "ab12")
          b (id "0f")]
      (is (every? zero? (kad/distance a a)))
      (is (= (kad/distance a b) (kad/distance b a))))))


(deftest distance-orders-by-xor-magnitude
  (testing "compare on distance vectors orders ids by closeness to a target"
    (let [target (id "00")]
      (is (neg? (compare (kad/distance (id "01") target)
                         (kad/distance (id "0f") target))))
      (is (neg? (compare (kad/distance (id "0f") target)
                         (kad/distance (id "10") target)))))))


;; ---------------------------------------------------------------------------
;; bucket-index
;; ---------------------------------------------------------------------------

(deftest bucket-index-counts-shared-prefix-bits
  (testing "the bucket index is the number of leading bits shared with self"
    (is (nil? (kad/bucket-index (id "ab") (id "ab")))
        "a node never buckets itself")
    (is (= 0 (kad/bucket-index (id "00") (id "80"))))
    (is (= 3 (kad/bucket-index (id "00") (id "10"))))
    (is (= 4 (kad/bucket-index (id "00") (id "08"))))
    (is (= 7 (kad/bucket-index (id "00") (id "01"))))))


;; ---------------------------------------------------------------------------
;; observe / nearest
;; ---------------------------------------------------------------------------

(deftest nearest-sorts-by-xor-distance
  (let [self (id "00")
        table (-> {}
                  (kad/observe self {:id (id "0f"), :port 1})
                  (kad/observe self {:id (id "01"), :port 2})
                  (kad/observe self {:id (id "f0"), :port 3}))]
    (is (= [(id "01") (id "0f") (id "f0")]
           (mapv :id (kad/nearest table (id "00") 3))))
    (is (= [(id "f0")] (mapv :id (kad/nearest table (id "ff") 1))))
    (is (= 2 (count (kad/nearest table (id "00") 2))))))


(deftest nearest-on-empty-table-is-empty
  (is (= [] (kad/nearest {} (id "ab") 5))))


(deftest re-observe-does-not-duplicate
  (let [self (id "00")
        peer {:id (id "80"), :port 1}
        table (-> {}
                  (kad/observe self peer)
                  (kad/observe self peer))]
    (is (= 1 (count (kad/nearest table self 10))))))


(defn- same-bucket-peer
  "The i-th of many distinct peers that all land in bucket 0 of an all-zero
  self id (first bit set)."
  [i]
  (let [hex "0123456789abcdef"]
    {:id (id (str "8" (nth hex (quot i 16)) (nth hex (rem i 16)))), :port i}))


(deftest bucket-capacity-drops-least-recently-seen
  (let [self (id "00")
        peers (mapv same-bucket-peer (range (inc kad/k)))
        table (reduce #(kad/observe %1 self %2) {} peers)]
    (is (= kad/k (count (kad/nearest table self (* 2 kad/k)))))
    (is (not-any? #(= (:id (peers 0)) (:id %))
                  (kad/nearest table self (* 2 kad/k)))
        "the least-recently-seen peer is dropped on overflow")))


(deftest re-observe-refreshes-recency
  (let [self (id "00")
        peers (mapv same-bucket-peer (range (inc kad/k)))
        table (reduce #(kad/observe %1 self %2) {} (take kad/k peers))
        ;; touching peer 0 makes peer 1 the oldest, so the overflow
        ;; evicts peer 1 and keeps peer 0
        table (-> table
                  (kad/observe self (peers 0))
                  (kad/observe self (peers kad/k)))
        ids (set (map :id (kad/nearest table self (* 2 kad/k))))]
    (is (contains? ids (:id (peers 0))))
    (is (not (contains? ids (:id (peers 1)))))))
