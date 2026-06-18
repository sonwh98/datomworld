(ns dao.stream.file-test
  (:require
    #?@(:cljd [["dart:io" :as dart-io]
               [clojure.test :refer [deftest is testing]]]
        :cljs [[cljs.test :refer [deftest is testing async]]]
        :clj [[clojure.java.io :as io]
              [clojure.test :refer [deftest is testing]]])
    [dao.stream :as ds]
    [dao.stream.file :as file]))


;; ---------------------------------------------------------------------------
;; Host-conditional helpers (temp paths, byte arrays, raw file reads)
;; ---------------------------------------------------------------------------

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
     :cljd (dart-io/File. "")))                ; unused on cljd test path


(defn- b->vec
  [bytes]
  #?(:clj (mapv #(bit-and (int %) 0xFF) bytes)
     :cljs (vec (js/Array.from bytes))
     :cljd (vec bytes)))


#?(:clj (defn- write-file!
          [path ints]
          (with-open [out (io/output-stream path)]
            (.write out (byte-array ints)))))


#?(:cljs (defn- read-file-bytes
           [path]
           (let [fs (js/require "fs")]
             (js/Uint8Array. (.readFileSync fs path)))))


;; ---------------------------------------------------------------------------
;; 1. tail-from-now — pre-existing content is NOT visible
;; ---------------------------------------------------------------------------

(deftest tail-from-now-test
  (testing "a reader at position 0 sees only post-open writes"
    #?(:clj (let [path (temp-path "dsf-tail-")]
              (write-file! path [9 9 9]) ; pre-existing history
              (let [s (ds/open! {:type :file, :path path})]
                (is (= :blocked (ds/next s {:position 0})))
                (ds/put! s (->bytes [1 2 3]))
                (let [r (ds/next s {:position 0})]
                  (is (map? r))
                  (is (= [1 2 3] (b->vec (:ok r)))))
                (ds/close! s)))
       :default (let [path (temp-path "dsf-tail-")
                      s (ds/open! {:type :file, :path path})]
                  (is (= :blocked (ds/next s {:position 0})))
                  (ds/put! s (->bytes [1 2 3]))
                  (is (= [1 2 3] (b->vec (:ok (ds/next s {:position 0})))))
                  (ds/close! s)))))


;; ---------------------------------------------------------------------------
;; 2. multiple cursors advance independently and non-destructively
;; ---------------------------------------------------------------------------

(deftest multi-cursor-test
  (testing "two cursors read the same post-open writes independently"
    (let [s (ds/open! {:type :file, :path (temp-path "dsf-multi-")})]
      (ds/put! s (->bytes [1]))
      (ds/put! s (->bytes [2]))
      (let [a0 (ds/next s {:position 0})
            a1 (ds/next s (:cursor a0))
            b0 (ds/next s {:position 0})]
        (is (= [1] (b->vec (:ok a0))))
        (is (= [2] (b->vec (:ok a1))))
        (is (= [1] (b->vec (:ok b0)))) ; second cursor still sees pos 0
        (is (= :blocked (ds/next s (:cursor a1)))))
      (ds/close! s))))


;; ---------------------------------------------------------------------------
;; 3. live tail — put! of any size is visible immediately
;; ---------------------------------------------------------------------------

(deftest live-tail-test
  (testing "a large value put! is visible to next immediately"
    (let [s (ds/open! {:type :file, :path (temp-path "dsf-live-")})
          payload (mapv #(mod % 256) (range 1000))]
      (ds/put! s (->bytes payload))
      (is (= payload (b->vec (:ok (ds/next s {:position 0})))))
      (ds/close! s))))


;; ---------------------------------------------------------------------------
;; 4. :blocked while open and caught up; :end after close!
;; ---------------------------------------------------------------------------

(deftest blocked-then-end-test
  (testing "blocked at the tail while open, end after close"
    (let [s (ds/open! {:type :file, :path (temp-path "dsf-end-")})]
      (ds/put! s (->bytes [1]))
      (let [r (ds/next s {:position 0})]
        (is (= :blocked (ds/next s (:cursor r))))
        (ds/close! s)
        (is (= :end (ds/next s (:cursor r))))))))


;; ---------------------------------------------------------------------------
;; 5. eviction — a cursor behind the window gets :daostream/gap; tail advances
;; ---------------------------------------------------------------------------

(deftest eviction-test
  (testing "capacity 2: after 3 writes, position 0 is gapped"
    (let [s (ds/open!
              {:type :file, :path (temp-path "dsf-evict-"), :capacity 2})]
      (ds/put! s (->bytes [1]))
      (ds/put! s (->bytes [2]))
      (ds/put! s (->bytes [3]))
      (is (= :daostream/gap (ds/next s {:position 0})))
      (testing "resync at the live tail"
        (is (= 3 (file/tail-position s)))
        (is (= :blocked (ds/next s {:position (file/tail-position s)})))
        (is (= [2] (b->vec (:ok (ds/next s {:position 1})))))) ; still
      ;; retained
      (ds/close! s))))


;; ---------------------------------------------------------------------------
;; 6. put! is non-blocking (returns :ok immediately)
;; ---------------------------------------------------------------------------

(deftest non-blocking-put-test
  (testing "put! returns {:result :ok ...} without waiting on disk"
    (let [s (ds/open! {:type :file, :path (temp-path "dsf-nb-")})
          r (ds/put! s (->bytes [1 2 3]))]
      (is (= :ok (:result r)))
      (ds/close! s))))


;; ---------------------------------------------------------------------------
;; 7. put! after close throws; non-byte-array throws; poison fails fast
;; ---------------------------------------------------------------------------

(deftest put-throws-test
  (testing "put! of a non-byte-array throws"
    (let [s (ds/open! {:type :file, :path (temp-path "dsf-nb2-")})]
      (is (thrown? #?(:clj Exception
                      :cljs js/Error
                      :cljd Exception)
            (ds/put! s "not-bytes")))
      (ds/close! s)))
  (testing "put! after close! throws"
    (let [s (ds/open! {:type :file, :path (temp-path "dsf-closed-")})]
      (ds/close! s)
      (is (thrown? #?(:clj Exception
                      :cljs js/Error
                      :cljd Exception)
            (ds/put! s (->bytes [1]))))))
  ;; The poison fail-fast is deterministically forced on clj, where close!
  ;; also genuinely blocks: break the underlying handle, let the async
  ;; write fail, then assert the next put! throws the real cause and close!
  ;; surfaces it too.
  #?(:clj (testing "a poisoned writer fails fast with the real cause"
            (let [s (ds/open! {:type :file, :path (temp-path "dsf-poison-")})
                  writer (:writer s)]
              (.close (:out writer))  ; break the underlying handle
              ;; >8KB overflows the buffer and flushes through the closed
              ;; stream, forcing a genuine async IOException into the
              ;; poison cell.
              (ds/put! s (->bytes (vec (repeat 9000 1))))
              (await (:agent writer)) ; let the failure land in the cell
              (let [cause
                    (try (ds/put! s (->bytes [2])) nil (catch Throwable t t))]
                (is (instance? java.io.IOException cause))) ; real cause, not
              ;; opaque
              (is (thrown? java.io.IOException (ds/close! s)))))))


;; ---------------------------------------------------------------------------
;; 8. disk persistence — appends persist; close! reconciles durability
;; ---------------------------------------------------------------------------

#?(:clj (deftest disk-persistence-test
          (testing
            "creates the file if missing; post-open writes persist after close"
            (let [path (temp-path "dsf-disk-")
                  s (ds/open! {:type :file, :path path})]
              (ds/put! s (->bytes [1 2 3]))
              (ds/put! s (->bytes [4 5]))
              (ds/close! s) ; blocks until flushed
              (with-open [in (io/input-stream path)]
                (is (= [1 2 3 4 5] (b->vec (.readAllBytes in)))))))))


#?(:cljs (deftest disk-persistence-test
           (testing "post-open writes persist after the close flush resolves"
             (async done
                    (let [path (temp-path "dsf-disk-")
                          s (ds/open! {:type :file, :path path})]
                      (ds/put! s (->bytes [1 2 3]))
                      (ds/put! s (->bytes [4 5]))
                      (ds/close! s)
                      (-> (file/flushed s)
                          (.then (fn [_]
                                   (is (= [1 2 3 4 5]
                                          (b->vec (read-file-bytes path))))
                                   (done)))))))))


;; ---------------------------------------------------------------------------
;; 9. close! on a poisoned stream still releases the
;; file handle (Node)
;; ---------------------------------------------------------------------------

#?(:cljs (deftest poison-releases-handle-test
           (testing "a poisoned close! still releases the fd via .finally"
             (async done
                    (let [path (temp-path "dsf-pfd-")
                          s (ds/open! {:type :file, :path path})]
                      (if-not (:node (:writer s))
                        (do (is true) (done))
                        (let [writer (:writer s)
                              fs ^js (:fs writer)
                              fd (:fd writer)]
                          (reset! (:chain writer) (js/Promise.reject
                                                    (js/Error. "poison")))
                          (reset! (:error writer) (js/Error. "poison"))
                          (ds/close! s)
                          (-> (file/flushed s)
                              (.catch (fn [_] :poisoned))
                              (.then
                                (fn [_]
                                  (js/Promise.
                                    (fn [resolve]
                                      (.fstat
                                        fs
                                        fd
                                        (fn [err _]
                                          (is
                                            (some? err)
                                            "fd should be closed after close!")
                                          (resolve)))))))
                              (.then (fn [_] (done)))))))))))
