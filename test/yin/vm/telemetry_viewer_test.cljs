(ns yin.vm.telemetry-viewer-test
  (:require
    [cljs.test :refer-macros [deftest is]]
    [yin.vm.telemetry-viewer :as tv]))


(deftest test-index-datoms
  (is (= {1 {:a 10, :b 20}, 2 {:c 30}}
         (tv/index-datoms [[1 :a 10 0 0] [1 :b 20 0 0] [2 :c 30 0 0]]))))


(deftest test-parse-snapshots
  (let [datoms [[1 :vm/type :vm/snapshot 5 0]
                [2 :vm.summary/type :number 5 0]
                [3 :vm/type :vm/snapshot 6 0]]
        parsed (tv/parse-snapshots datoms)]
    (is (= 6 (apply max (keys parsed))))
    (is (= {:vm/type :vm/snapshot} (get-in parsed [5 1])))))


(deftest test-find-snapshot-root
  (let [entities {1 {:vm/type :vm/snapshot :vm/step 0}
                  2 {:vm.summary/type :number}}]
    (is (= [1 {:vm/type :vm/snapshot :vm/step 0}]
           (tv/find-snapshot-root entities)))))


(deftest test-merge-snapshots
  (let [old {5 {1 {:a 1}}}
        new {5 {1 {:b 2}, 2 {:c 3}}
             6 {3 {:d 4}}}
        merged (tv/merge-snapshots old new)]
    (is (= {1 {:a 1, :b 2}, 2 {:c 3}} (get merged 5)))
    (is (= {3 {:d 4}} (get merged 6)))))


(deftest test-merge-snapshots-multi
  (let [old {5 {1 {:item [10]}}}
        new {5 {1 {:item [20]}}}
        merged (tv/merge-snapshots old new)]
    (is (= {1 {:item [10 20]}} (get merged 5)))))
