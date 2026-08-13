(ns dao.jing.file-test
  "Tests for dao.jing.file persistent content files."
  (:require #?@(:cljd [["dart:io" :as dart-io]])
            [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.file :as jing-file]
            [dao.stream :as ds]
            [dao.stream.log]
            [dao.stream.ringbuffer]))


(defn- temp-path
  [prefix]
  (str "target/test-jing-content-" prefix "-" (random-uuid) ".log"))


(defn- cleanup-file
  [path]
  #?(:clj (let [f (java.io.File. path)] (when (.exists f) (.delete f)))
     :cljs (try (.unlinkSync (js/require "fs") path) (catch :default _))
     :cljd (try (let [f (dart-io/File path)]
                  (when (.existsSync f) (.deleteSync f)))
                (catch #?(:cljd Object
                          :default Exception)
                       _
                  nil))))


(defn- count-records
  [log]
  (loop [cursor {:position 0}
         n 0]
    (let [res (ds/next log cursor)]
      (if (and (map? res) (contains? res :ok))
        (recur (:cursor res) (inc n))
        n))))


(deftest codec-test
  (testing
    "encode-record/decode-record round trip [address payload] including nil"
    (let [cases [[(jing/segment-key {:a 1}) {:a 1}] [(jing/segment-key nil) nil]
                 [(jing/segment-key "plain") "plain"]
                 [(jing/segment-key [1 2 3]) [1 2 3]]]]
      (doseq [[address payload] cases]
        (is (= [address payload]
               (jing-file/decode-record (jing-file/encode-record address
                                                                 payload))))))))


(deftest handle-shape-test
  (testing
    "content file handle exposes log, state, lock and functions, no :stream"
    (let [path (temp-path "shape")
          handle (jing-file/create-content-file path)]
      (try (is (not (contains? handle :stream)))
           (is (= path (:path handle)))
           (is (some? (:log handle)))
           (is (= {:closed? false, :content {}} @(:state handle)))
           (is (some? (:write-lock handle)))
           (is (fn? (:put-content-fn handle)))
           (is (fn? (:get-content-fn handle)))
           (is (fn? (:close-fn handle)))
           (finally (jing/close! handle) (cleanup-file path))))))


(deftest materialize-idempotence-test
  (testing "put/get idempotence and file length unchanged on :present"
    (let [path (temp-path "idempotence")
          address (jing/segment-key {:v 1})
          payload {:v 1}
          handle (jing-file/create-content-file path)]
      (try (is (= address (jing/materialize! handle payload)))
           (is (= payload (jing/get handle address ::missing)))
           (is (= :present ((:put-content-fn handle) address payload)))
           (is (= payload (jing/get handle address ::missing)))
           (jing/close! handle)
           (let [log (ds/open! {:type :append-log, :path path})]
             (try (is (= 1 (count-records log))) (finally (ds/close! log))))
           (let [h2 (jing-file/create-content-file path)]
             (try (is (= payload (jing/get h2 address ::missing)))
                  (finally (jing/close! h2))))
           (finally (cleanup-file path))))))


(deftest close-reopen-durability-test
  (testing "payloads survive close and reopen"
    (let [path (temp-path "durability")
          a1 (jing/segment-key "alpha")
          a2 (jing/segment-key {:n 2})
          handle (jing-file/create-content-file path)]
      (jing/materialize! handle "alpha")
      (jing/materialize! handle {:n 2})
      (jing/close! handle)
      (let [h2 (jing-file/create-content-file path)]
        (try (is (= "alpha" (jing/get h2 a1 ::missing)))
             (is (= {:n 2} (jing/get h2 a2 ::missing)))
             (finally (jing/close! h2) (cleanup-file path)))))))


(deftest nil-and-absence-test
  (testing "nil payload is stored distinctly from an absent address"
    (let [path (temp-path "nil")
          address (jing/segment-key nil)
          handle (jing-file/create-content-file path)]
      (try (is (= ::missing (jing/get handle address ::missing)))
           (jing/materialize! handle nil)
           (is (nil? (jing/get handle address ::missing)))
           (is (contains? (:content @(:state handle)) address))
           (finally (jing/close! handle) (cleanup-file path))))))


(deftest observer-convergence-test
  (testing
    "two ringbuffers carrying the same payload converge on one content address"
    (let [path (temp-path "observe")
          payload {:observed true}
          handle (jing-file/create-content-file path)
          rb1 (ds/open! {:type :ringbuffer, :capacity 8})
          rb2 (ds/open! {:type :ringbuffer, :capacity 8})]
      (try (ds/append! rb1 payload)
           (ds/append! rb2 payload)
           (let [observer (jing/observer-state [rb1 rb2])
                 r1 (jing/observe-step! handle observer)
                 r2 (jing/observe-step! handle (:state r1))]
             (is (= :ok (:signal r1)))
             (is (= :ok (:signal r2)))
             (is (= (jing/segment-key payload) (:address r1)))
             (is (= (jing/segment-key payload) (:address r2)))
             (is (= 1 (count (:content @(:state handle))))))
           (is (= 1 (count-records (:log handle))))
           (finally (jing/close! handle)
                    (ds/close! rb1)
                    (ds/close! rb2)
                    (cleanup-file path))))))


(deftest raw-duplicate-recovery-test
  (testing "equal duplicate raw records recover on replay"
    (let [path (temp-path "rawdup")
          address (jing/segment-key {:k 1})
          payload {:k 1}
          log (ds/open! {:type :append-log, :path path})]
      (ds/append! log (jing-file/encode-record address payload))
      (ds/append! log (jing-file/encode-record address payload))
      (ds/close! log)
      (let [log2 (ds/open! {:type :append-log, :path path})]
        (try (is (= 2 (count-records log2))) (finally (ds/close! log2))))
      (let [handle (jing-file/create-content-file path)]
        (try (is (= payload (jing/get handle address ::missing)))
             (is (= :present ((:put-content-fn handle) address payload)))
             (finally (jing/close! handle) (cleanup-file path)))))))


(deftest corrupt-record-categories-test
  (testing "each corrupt raw record category throws on construction"
    (let [good-address (jing/segment-key "ok")
          categories
          [{:name "malformed EDN", :record (jing-file/->bytes "[[[not edn")}
           {:name "wrong shape",
            :record (jing-file/->bytes (pr-str [good-address "ok" :extra]))}
           {:name "invalid address",
            :record (jing-file/->bytes (pr-str [:root/not-content "ok"]))}
           {:name "hash mismatch",
            :record (jing-file/->bytes (pr-str [good-address "different"]))}]]
      (doseq [{:keys [name record]} categories]
        (let [path (temp-path "corrupt")
              log (ds/open! {:type :append-log, :path path})]
          (ds/append! log record)
          (ds/close! log)
          (try (is (thrown? #?(:clj Exception
                               :cljs :default
                               :cljd Object)
                     (jing-file/create-content-file path))
                   name)
               (finally (cleanup-file path))))))))


(deftest collision-test
  (testing "unequal payload for existing address throws and preserves existing"
    (let [path (temp-path "collision")
          address (jing/segment-key "original")
          handle (jing-file/create-content-file path)]
      (try (is (= :inserted ((:put-content-fn handle) address "original")))
           (is (= :present ((:put-content-fn handle) address "original")))
           (swap! (:state handle) assoc-in [:content address] "corrupted")
           (is (thrown? #?(:clj Exception
                           :cljs :default
                           :cljd Object)
                 ((:put-content-fn handle) address "original")))
           (is (= "corrupted" (jing/get handle address nil)))
           (finally (jing/close! handle) (cleanup-file path))))))


(deftest close-semantics-test
  (testing "close is idempotent and operations throw after close"
    (let [path (temp-path "close")
          payload "v"
          address (jing/segment-key payload)
          handle (jing-file/create-content-file path)]
      (jing/materialize! handle payload)
      (is (nil? (jing/close! handle)))
      (is (nil? (jing/close! handle)))
      (is (thrown? #?(:clj Exception
                      :cljs :default
                      :cljd Object)
            (jing/materialize! handle payload)))
      (is (thrown? #?(:clj Exception
                      :cljs :default
                      :cljd Object)
            (jing/get handle address nil)))
      (cleanup-file path))))


(deftest acknowledged-insert-survives-close-test
  (testing "acknowledged insert survives immediate close and reopen"
    (let [path (temp-path "ack")
          payload {:ack 1}
          address (jing/segment-key payload)
          handle (jing-file/create-content-file path)]
      (jing/materialize! handle payload)
      (jing/close! handle)
      (let [h2 (jing-file/create-content-file path)]
        (try (is (= payload (jing/get h2 address ::missing)))
             (finally (jing/close! h2) (cleanup-file path)))))))


(deftest file-content-contention-test
  (testing "concurrent puts serialize through the write lock"
    #?(:clj (let [path (temp-path "contention")
                  payload {:contended true}
                  address (jing/segment-key payload)
                  handle (jing-file/create-content-file path)]
              (try (let [results (mapv #(deref % 5000 ::timeout)
                                       (doall (repeatedly
                                                16
                                                (fn []
                                                  (future ((:put-content-fn handle)
                                                           address
                                                           payload))))))]
                     (is (not-any? #{::timeout} results))
                     (is (= 1 (count (filter #{:inserted} results))))
                     (is (= 15 (count (filter #{:present} results))))
                     (jing/close! handle)
                     (let [log (ds/open! {:type :append-log, :path path})]
                       (try (is (= 1 (count-records log)))
                            (finally (ds/close! log)))))
                   (finally (cleanup-file path))))
       :cljs (is true)
       :cljd (is true))))
