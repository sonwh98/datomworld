(ns yang.correspondence-text-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [yang.correspondence-text :as text]
            [yang.clojure :as yang]
            [yin.vm :as vm]))


(deftest profile-fidelity-and-reader-refusals
  (testing "fidelity preserves syntax kinds, numeric types, metadata, and iteration order"
    (is (text/faithful? (list 'a 'b) (list 'a 'b)))
    (is (not (text/faithful? '(a b) '[a b])))
    (is (not (text/faithful? (with-meta '[a] {:m 1}) '[a])))
    (is (text/faithful? (with-meta (vector 'a) {:line (int 3)})
                        (with-meta (vector 'a) {:line (long 3)})))
    (is (not (text/faithful? (with-meta (vector 'a) {:line (int 3)})
                             (with-meta (vector 'a) {:line (long 4)}))))
    (is (not (text/faithful? 1 1N)))
    (doseq [a [(byte 1) (short 1) (int 1) (long 1)]
            b [(byte 1) (short 1) (int 1) (long 1)]]
      (is (text/faithful? a b)))
    (is (not (text/faithful? (int 1) (long 2))))
    (is (text/faithful? Double/NaN Double/NaN))
    (is (not (text/faithful? 0.0 -0.0)))
    (is (not (text/faithful? 1.0M 1.00M)))
    (is (not (text/faithful? (array-map :a 1 :b 2) (array-map :b 2 :a 1))))
    (is (not (text/faithful? (sorted-set 1 2) (sorted-set-by > 1 2)))))
  (testing "the reader consumes exactly one value and refuses tags"
    (is (= '[a b] (text/read-one! :case "[a b]")))
    (doseq [source ["" "1 2" "#unknown 1" "#inst \"2020-01-01\""]]
      (is (thrown? Exception (text/read-one! :case source)))))
  (testing "a supported value round trips through the pinned printer and reader"
    (let [original (with-meta (vector 'x 2) {:marker "kept"})
          source (text/capture! :fixture original)]
      (is (text/faithful? original (text/read-one! :fixture source)))))
  (testing "unsupported objects and metadata loss refuse with stage and compilation"
    (let [failure (try (text/capture! :fixture (Object.)) nil
                       (catch Exception e (ex-data e)))]
      (is (= :fixture (:compilation failure)))
      (is (contains? #{:print :read :fidelity} (:stage failure))))
    (let [source (text/capture! :fixture (with-meta (vector 'a) {:line (int 3)}))]
      (is (string? source))))
  (testing "fidelity diagnostics identify the metadata key"
    (let [original (with-meta (vector 'a) (array-map :line (int 3)))
          changed (with-meta (vector 'a) (array-map :line (long 4)))
          failure (with-redefs [text/read-one! (fn [_ _] changed)]
                    (try (text/capture! :fixture original) nil
                         (catch Exception e (ex-data e))))]
      (is (= :fidelity (:stage failure)))
      (is (= [:metadata [:value 0 :line]] (get-in failure [:difference :path])))))
  (testing "the profile is a content-addressed data value"
    (let [handle (mem/create-content-mem)
          address (jing/materialize! handle text/profile)]
      (is (= :fixed-jvm-integer-wrappers-v1 (:fidelity-rule text/profile)))
      (is (jing/segment-address? address))
      (is (= text/profile (jing/get handle address nil))))))


(deftest decoded-dynamic-map-is-not-authoritative-source
  (let [source (array-map :b 'b :a 'a)
        handle (mem/create-content-mem)
        decoded (jing/get handle (jing/materialize! handle source) nil)
        root #(-> % yang/compile-form vm/ast->semantic-bytecode :root)]
    (is (= source decoded))
    (is (not (text/faithful? source decoded)))
    (is (not= (root source) (root decoded)))))
