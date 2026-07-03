(ns dao.stream.log-test
  (:require #?@(:cljd [["dart:io" :as dart-io] ["dart:typed_data" :as typed]
                       [clojure.test :refer [deftest is testing]]]
                :cljs [[cljs.test :refer [deftest is testing]]]
                :clj [[clojure.java.io :as io]
                      [clojure.test :refer [deftest is testing]]])
            [dao.stream :as ds]
            [dao.stream.log]))


(defn- temp-path
  [prefix]
  #?(:clj (str (System/getProperty "java.io.tmpdir")
               "/"
               prefix
               (java.util.UUID/randomUUID)
               ".log")
     :cljs (let [os (js/require "os")
                 path (js/require "path")]
             (.join path (.tmpdir os) (str prefix (random-uuid) ".log")))
     :cljd (str "/tmp/" prefix (random-uuid) ".log")))


(defn- ->bytes
  [ints]
  #?(:clj (byte-array ints)
     :cljs (js/Uint8Array. (clj->js ints))
     :cljd (typed/Uint8List.fromList ints)))


(defn- b->vec
  [bytes]
  #?(:clj (mapv #(bit-and (int %) 0xFF) bytes)
     :cljs (vec (js/Array.from bytes))
     :cljd (vec bytes)))


(deftest log-round-trip-test
  (testing "put! returns a cursor and next reads that exact framed payload"
    (let [path (temp-path "dsl-rt-")
          s (ds/open! {:type :append-log, :path path})]
      (is (= :blocked (ds/next s {:position 0})))
      (let [payload (->bytes [1 2 3 4])
            {res :result, cursor :cursor} (ds/put! s payload)]
        (is (= :ok res))
        (is (map? cursor))
        (is (zero? (:position cursor)))
        (let [{ok :ok, next-cursor :cursor} (ds/next s {:position 0})]
          (is (= [1 2 3 4] (b->vec ok)))
          (is (= 8 (:position next-cursor)))))
      (ds/close! s))))


(deftest log-positional-read-test
  (testing "next at a stored cursor re-reads that exact record (get path)"
    (let [path (temp-path "dsl-pos-")
          s (ds/open! {:type :append-log, :path path})
          c1 (:cursor (ds/put! s (->bytes [1])))
          c2 (:cursor (ds/put! s (->bytes [2])))
          c3 (:cursor (ds/put! s (->bytes [3])))]
      (is (= [2] (b->vec (:ok (ds/next s c2)))))
      (is (= [1] (b->vec (:ok (ds/next s c1)))))
      (is (= [3] (b->vec (:ok (ds/next s c3)))))
      (ds/close! s))))


(deftest multi-cursor-test
  (testing "Multiple independent cursors advance without consuming"
    (let [path (temp-path "dsl-mc-")
          s (ds/open! {:type :append-log, :path path})
          _ (ds/put! s (->bytes [10]))
          _ (ds/put! s (->bytes [20]))
          a0 {:position 0}
          b0 {:position 0}
          r-a0 (ds/next s a0)
          r-a1 (ds/next s (:cursor r-a0))]
      (is (= [10] (b->vec (:ok r-a0))))
      (is (= [20] (b->vec (:ok r-a1))))
      ;; b0 is independent
      (let [r-b0 (ds/next s b0)] (is (= [10] (b->vec (:ok r-b0)))))
      (ds/close! s))))


(deftest reopen-replay-test
  (testing "Reopen replays persisted records from 0"
    (let [path (temp-path "dsl-ro-")
          s1 (ds/open! {:type :append-log, :path path})
          _ (ds/put! s1 (->bytes [55]))
          _ (ds/put! s1 (->bytes [66]))]
      (ds/close! s1)
      (let [s2 (ds/open! {:type :append-log, :path path})
            r1 (ds/next s2 {:position 0})
            r2 (ds/next s2 (:cursor r1))]
        (is (= [55] (b->vec (:ok r1))))
        (is (= [66] (b->vec (:ok r2))))
        (is (= :blocked (ds/next s2 (:cursor r2))))
        (ds/close! s2)))))


(deftest torn-tail-test
  (testing "Torn-tail truncation cleans up partial writes"
    (let [path (temp-path "dsl-torn-")]
      (let [s (ds/open! {:type :append-log, :path path})]
        (ds/put! s (->bytes [11 22]))
        (ds/close! s))
      ;; Hand-write a torn record (int32 length 999, but only 2 bytes
      ;; payload)
      #?(:clj (with-open [raf (java.io.RandomAccessFile. (io/file path) "rw")]
                (.seek raf (.length raf))
                (.writeInt raf 999)
                (.write raf (byte-array [1 2]))))
      #?(:cljs (let [fs (js/require "fs")
                     fd (.openSync fs path "a")
                     head (js/Buffer.alloc 4)]
                 (.writeInt32BE head 999 0)
                 (.writeSync fs fd head)
                 (.writeSync fs fd (js/Buffer.from (clj->js [1 2])))
                 (.closeSync fs fd)))
      #?(:cljd (let [f (dart-io/File. path)
                     raf (.openSync f .mode dart-io/FileMode.append)
                     head (typed/ByteData. 4)]
                 (.setInt32 head 0 999)
                 (.writeFromSync raf (.asUint8List (.-buffer head)))
                 (.writeFromSync raf (typed/Uint8List.fromList [1 2]))
                 (.closeSync raf)))
      ;; Reopen and assert we only see the first record, and we can append
      ;; safely
      (let [s2 (ds/open! {:type :append-log, :path path})
            r1 (ds/next s2 {:position 0})]
        (is (= [11 22] (b->vec (:ok r1))))
        (is (= :blocked (ds/next s2 (:cursor r1))))
        ;; Append lands clean
        (ds/put! s2 (->bytes [33]))
        (is (= [33] (b->vec (:ok (ds/next s2 (:cursor r1))))))
        (ds/close! s2)))))


(deftest close-test
  (testing "close!/closed? behave; next on closed returns :end"
    (let [path (temp-path "dsl-close-")
          s (ds/open! {:type :append-log, :path path})]
      (is (not (ds/closed? s)))
      (ds/close! s)
      (is (ds/closed? s))
      (is (= :end (ds/next s {:position 0}))))))


(deftest fd-lock-concurrency-test
  #?(:clj
     (testing
       "JVM transport fd lock: concurrent positional reads and appends stay consistent"
       (let [path (temp-path "dsl-conc-")
             s (ds/open! {:type :append-log, :path path})
             ;; pre-populate some data
             cursors (vec (for [i (range 50)]
                            (:cursor (ds/put! s (->bytes [i])))))]
         (let [readers (repeatedly 20
                                   #(future (dotimes [_ 100]
                                              (let [idx (rand-int 50)
                                                    c (nth cursors idx)
                                                    val (:ok (ds/next s c))]
                                                (is (= [idx]
                                                       (b->vec val)))))))
               writers (repeatedly 5
                                   #(future (dotimes [_ 100]
                                              (ds/put! s (->bytes [99])))))]
           (run! deref readers)
           (run! deref writers))
         (ds/close! s)))))
