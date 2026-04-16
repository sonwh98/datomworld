(ns dao.stream.file-input-stream-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
    [dao.stream.file-input-stream]))


(defn- bytes->vec
  [^bytes bytes]
  (mapv #(bit-and (int %) 0xFF) bytes))


(defn- write-bytes!
  [path data]
  (with-open [out (io/output-stream path)]
    (.write out ^bytes data)))


(deftest file-input-stream-reads-snapshot-in-order-test
  (let [temp-file (java.io.File/createTempFile "file-input-stream" ".bin")
        path (.getAbsolutePath temp-file)]
    (.deleteOnExit temp-file)
    (write-bytes! path (byte-array (range 10)))
    (let [stream (ds/open! {:type :file-input-stream
                            :path path
                            :chunk-size 4})]
      (testing "chunks are returned in order and rereads are non-destructive"
        (let [r0 (ds/next stream {:position 0})
              r1 (ds/next stream {:position 1})
              r2 (ds/next stream {:position 2})
              r0-again (ds/next stream {:position 0})]
          (is (= [0 1 2 3] (bytes->vec (:ok r0))))
          (is (= [4 5 6 7] (bytes->vec (:ok r1))))
          (is (= [8 9] (bytes->vec (:ok r2))))
          (is (= [0 1 2 3] (bytes->vec (:ok r0-again))))
          (is (= :end (ds/next stream {:position 3})))))

      (testing "put! is unsupported"
        (is (thrown? Exception
              (ds/put! stream (byte-array [1 2 3])))))

      (testing "close! marks stream closed but does not erase unread data"
        (is (false? (ds/closed? stream)))
        (ds/close! stream)
        (is (true? (ds/closed? stream)))
        (is (= [0 1 2 3] (bytes->vec (:ok (ds/next stream {:position 0})))))
        (is (= [4 5 6 7] (bytes->vec (:ok (ds/next stream {:position 1})))))
        (is (= :end (ds/next stream {:position 3})))))))


(deftest file-input-stream-snapshot-is-stable-after-file-mutation-test
  (let [temp-file (java.io.File/createTempFile "file-input-snapshot" ".bin")
        path (.getAbsolutePath temp-file)]
    (.deleteOnExit temp-file)
    (write-bytes! path (byte-array [10 20 30 40]))
    (let [stream (ds/open! {:type :file-input-stream
                            :path path
                            :chunk-size 4})]
      (write-bytes! path (byte-array [99 99 99 99]))
      (is (= [10 20 30 40] (bytes->vec (:ok (ds/next stream {:position 0}))))
          "snapshot bytes are unaffected by post-open file mutation")
      (ds/close! stream))))


(deftest file-input-stream-empty-file-ends-immediately-test
  (let [temp-file (java.io.File/createTempFile "file-input-empty" ".bin")
        path (.getAbsolutePath temp-file)]
    (.deleteOnExit temp-file)
    (write-bytes! path (byte-array 0))
    (let [stream (ds/open! {:type :file-input-stream
                            :path path})]
      (is (= :end (ds/next stream {:position 0})))
      (ds/close! stream))))
