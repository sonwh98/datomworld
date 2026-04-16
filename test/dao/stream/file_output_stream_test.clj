(ns dao.stream.file-output-stream-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
    [dao.stream.file-output-stream]))


(defn- bytes->vec
  [^bytes bytes]
  (mapv #(bit-and (int %) 0xFF) bytes))


(defn- read-bytes
  [path]
  (with-open [in (io/input-stream path)
              out (java.io.ByteArrayOutputStream.)]
    (io/copy in out)
    (.toByteArray out)))


(deftest file-output-stream-appends-bytes-test
  (let [temp-dir (java.nio.file.Files/createTempDirectory
                   "file-output-stream"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        path (str (.resolve temp-dir "out.bin"))]
    (let [stream (ds/open! {:type :file-output-stream
                            :path path})]
      (testing "writes append in order and create the file if needed"
        (is (= :ok (:result (ds/put! stream (byte-array [1 2 3])))))
        (is (= :ok (:result (ds/put! stream (byte-array [4 5])))))
        (is (= [1 2 3 4 5] (bytes->vec (read-bytes path)))))

      (testing "next is unsupported"
        (is (thrown? Exception
              (ds/next stream {:position 0}))))

      (testing "put! after close throws"
        (ds/close! stream)
        (is (true? (ds/closed? stream)))
        (is (thrown? Exception
              (ds/put! stream (byte-array [6]))))))))
