(ns yin.io-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [dao.stream.file-input-stream]
    [dao.stream.file-output-stream]
    [yin.io :as yin.io]
    [dao.stream.ringbuffer :as stream]
    [yin.vm :as vm]
    [yin.vm.engine :as engine]))


(defn- bytes->vec
  [^bytes bytes]
  (mapv #(bit-and (int %) 0xFF) bytes))


(defn- write-bytes!
  [path data]
  (with-open [out (io/output-stream path)]
    (.write out ^bytes data)))


(defn- read-bytes
  [path]
  (with-open [in (io/input-stream path)
              out (java.io.ByteArrayOutputStream.)]
    (io/copy in out)
    (.toByteArray out)))


(defn- run-effect
  [state effect]
  (engine/handle-effect state effect {:park-entry-fns {}}))


(deftest file-input-stream-effect-opens-readable-stream-test
  (let [temp-file (java.io.File/createTempFile "yin-stream-input" ".bin")
        path (.getAbsolutePath temp-file)]
    (.deleteOnExit temp-file)
    (write-bytes! path (byte-array [10 11 12 13]))
    (let [{state-1 :state stream-ref :value}
          (run-effect (vm/empty-state)
                      (yin.io/input-stream path 2))
          {state-2 :state cursor-ref :value}
          (run-effect state-1 (stream/cursor stream-ref))
          next-1 (run-effect state-2 (stream/next! cursor-ref))
          next-2 (run-effect (:state next-1) (stream/next! cursor-ref))
          next-3 (run-effect (:state next-2) (stream/next! cursor-ref))]
      (testing "yin io effects read file chunks in order"
        (is (= [10 11] (bytes->vec (:value next-1))))
        (is (= [12 13] (bytes->vec (:value next-2))))
        (is (nil? (:value next-3)))))))


(deftest file-output-stream-effect-opens-append-writer-test
  (let [temp-dir (java.nio.file.Files/createTempDirectory
                   "yin-stream-output"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        path (str (.resolve temp-dir "out.bin"))
        {state-1 :state stream-ref :value}
        (run-effect (vm/empty-state)
                    (yin.io/output-stream path))
        put-1 (run-effect state-1 (stream/put! stream-ref (byte-array [1 2])))
        put-2 (run-effect (:state put-1) (stream/put! stream-ref (byte-array [3 4])))]
    (testing "yin io effects append bytes through the output stream"
      (is (= [1 2 3 4] (bytes->vec (read-bytes path)))))
    (run-effect (:state put-2) (stream/close! stream-ref))))
