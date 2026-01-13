(ns dao-stream.file-stream-node-test
  "Tests for Node.js file stream implementation"
  (:require [cljs.test :refer-macros [deftest testing is use-fixtures]]
            [dao-stream.file-stream-node :as fs]
            [dao-stream.protocol :as proto]))

;; Node.js fs module for cleanup
(def node-fs (js/require "fs"))
(def node-path (js/require "path"))

(defn cleanup-test-streams
  "Clean up test data files"
  []
  (let [test-dir "data/streams"]
    (when (.existsSync node-fs test-dir)
      (let [files (.readdirSync node-fs test-dir)]
        (doseq [file files]
          (when (re-find #"^_test" file)
            (let [filepath (.join node-path test-dir file)]
              (.unlinkSync node-fs filepath))))))))

(use-fixtures :each
  {:before (fn [] (cleanup-test-streams))
   :after (fn [] (cleanup-test-streams))})

(deftest test-create-stream
  (testing "Creating a file stream instance"
    (let [stream (fs/create-file-stream)]
      (is (satisfies? proto/IStream stream))
      (is (not (nil? stream))))))

(deftest test-open-stream
  (testing "Opening a stream"
    (let [stream (fs/create-file-stream)
          result (proto/open stream {:path "/test/open"})]
      (is (string? (:stream-id result)))
      (is (= :connected (:status result)))
      (is (= "/test/open" (:path result)))
      (is (zero? (:offset result)))
      (is (nil? (:error result)))
      (proto/close stream (:stream-id result)))))

(deftest test-write-single-datom
  (testing "Writing a single datom to stream"
    (let [stream (fs/create-file-stream)
          {:keys [stream-id]} (proto/open stream {:path "/test/write-single"})
          datom (proto/datom :user/u1 :name "Alice" nil {})
          write-result (proto/write stream stream-id datom)]
      (is (= :written (:status write-result)))
      (is (= 1 (:count write-result)))
      (is (= 1 (:offset write-result)))
      (is (nil? (:error write-result)))
      (proto/close stream stream-id))))

(deftest test-write-multiple-datoms
  (testing "Writing multiple datoms to stream"
    (let [stream (fs/create-file-stream)
          {:keys [stream-id]} (proto/open stream {:path "/test/write-multiple"})
          datoms [(proto/datom :user/u1 :name "Alice")
                  (proto/datom :user/u1 :email "alice@example.com")
                  (proto/datom :user/u1 :age 30)]
          write-result (proto/write stream stream-id datoms)]
      (is (= :written (:status write-result)))
      (is (= 3 (:count write-result)))
      (is (= 3 (:offset write-result)))
      (proto/close stream stream-id))))

(deftest test-read-datoms
  (testing "Reading datoms from stream"
    (let [stream (fs/create-file-stream)
          {:keys [stream-id]} (proto/open stream {:path "/test/read"})
          datoms [(proto/datom :user/u42 :email/subject "Test 1")
                  (proto/datom :user/u42 :email/subject "Test 2")
                  (proto/datom :user/u42 :email/subject "Test 3")]
          _ (proto/write stream stream-id datoms)
          read-datoms (proto/read stream stream-id)]
      (is (= 3 (count read-datoms)))
      (is (= :user/u42 (proto/entity (first read-datoms))))
      (is (= :email/subject (proto/attribute (first read-datoms))))
      (is (= "Test 1" (proto/value (first read-datoms))))
      (proto/close stream stream-id))))

(deftest test-read-with-limit
  (testing "Reading datoms with limit"
    (let [stream (fs/create-file-stream)
          {:keys [stream-id]} (proto/open stream {:path "/test/read-limit"})
          datoms [(proto/datom :user/u1 :msg "One")
                  (proto/datom :user/u1 :msg "Two")
                  (proto/datom :user/u1 :msg "Three")
                  (proto/datom :user/u1 :msg "Four")
                  (proto/datom :user/u1 :msg "Five")]
          _ (proto/write stream stream-id datoms)
          read-datoms (proto/read stream stream-id {:limit 2})]
      (is (= 2 (count read-datoms)))
      (is (= "One" (proto/value (first read-datoms))))
      (is (= "Two" (proto/value (second read-datoms))))
      (proto/close stream stream-id))))

(deftest test-status
  (testing "Getting stream status"
    (let [stream (fs/create-file-stream)
          {:keys [stream-id]} (proto/open stream {:path "/test/status"})
          datoms [(proto/datom :user/u1 :x 1)
                  (proto/datom :user/u1 :x 2)
                  (proto/datom :user/u1 :x 3)]
          _ (proto/write stream stream-id datoms)
          status (proto/status stream stream-id)]
      (is (= :connected (:status status)))
      (is (= stream-id (:stream-id status)))
      (is (= "/test/status" (:path status)))
      (is (= 3 (:total status)))
      (is (= 0 (:lag status))) ;; No lag since we haven't read yet
      (is (= :read-write (:mode status)))
      (proto/close stream stream-id))))

(deftest test-close-stream
  (testing "Closing a stream"
    (let [stream (fs/create-file-stream)
          {:keys [stream-id]} (proto/open stream {:path "/test/close"})
          close-result (proto/close stream stream-id)]
      (is (= :closed (:status close-result)))
      (is (= stream-id (:stream-id close-result)))
      (is (nil? (:error close-result))))))

(deftest test-datom-helper-functions
  (testing "Datom helper functions"
    (let [datom (proto/datom :user/u42 :email/subject "Test" (js/Date. "2025-11-30") {:origin "test"})]
      (is (proto/datom? datom))
      (is (= :user/u42 (proto/entity datom)))
      (is (= :email/subject (proto/attribute datom)))
      (is (= "Test" (proto/value datom)))
      (is (instance? js/Date (proto/time datom)))
      (is (= {:origin "test"} (proto/metadata datom))))))

(deftest test-retract-datom
  (testing "Creating retraction datoms"
    (let [original (proto/datom :user/u42 :email/subject "Old Subject" (js/Date. "2025-11-30") {})
          retraction (proto/retract-datom original (js/Date. "2025-12-01"))]
      (is (= :user/u42 (proto/entity retraction)))
      (is (= :db/retract (proto/attribute retraction)))
      (is (= [:email/subject "Old Subject"] (proto/value retraction))) ;; value is [attribute value] pair
      (is (instance? js/Date (proto/time retraction)))
      (is (= {:operation :retract} (proto/metadata retraction))))))

(deftest test-filter-datoms-client-side
  (testing "Client-side filtering of datoms"
    (let [stream (fs/create-file-stream)
          {:keys [stream-id]} (proto/open stream {:path "/test/filter"})
          datoms [(proto/datom :user/u42 :email/subject "Urgent: Meeting")
                  (proto/datom :user/u42 :email/to :user/u99)
                  (proto/datom :user/u42 :email/subject "Regular email")
                  (proto/datom :user/u42 :email/body "Content here")]
          _ (proto/write stream stream-id datoms)
          subjects (->> (proto/read stream stream-id)
                        (filter #(= (proto/attribute %) :email/subject))
                        (map proto/value))]
      (is (= 2 (count subjects)))
      (is (= #{"Urgent: Meeting" "Regular email"} (set subjects)))
      (proto/close stream stream-id))))

(deftest test-multiple-streams
  (testing "Multiple independent streams"
    (let [stream (fs/create-file-stream)
          email-stream (proto/open stream {:path "/test/user/42/email"})
          calendar-stream (proto/open stream {:path "/test/user/42/calendar"})
          _ (proto/write stream (:stream-id email-stream)
                         (proto/datom :user/u42 :email/subject "Email data"))
          _ (proto/write stream (:stream-id calendar-stream)
                         (proto/datom :event/e1 :event/time (js/Date. "2025-12-01")))
          email-datoms (proto/read stream (:stream-id email-stream))
          calendar-datoms (proto/read stream (:stream-id calendar-stream))]
      (is (= 1 (count email-datoms)))
      (is (= 1 (count calendar-datoms)))
      (is (= :email/subject (proto/attribute (first email-datoms))))
      (is (= :event/time (proto/attribute (first calendar-datoms))))
      (proto/close stream (:stream-id email-stream))
      (proto/close stream (:stream-id calendar-stream)))))

(deftest test-automatic-directory-creation
  (testing "Automatic creation of nested directories"
    (let [stream (fs/create-file-stream)
          {:keys [stream-id]} (proto/open stream {:path "/test/deep/nested/path"})
          datom (proto/datom :test/id :value 123)
          _ (proto/write stream stream-id datom)]
      (is (.existsSync node-fs "data/streams"))
      (proto/close stream stream-id))))

(deftest test-persistence-across-instances
  (testing "Data persists across stream instances"
    (let [stream1 (fs/create-file-stream)
          {:keys [stream-id]} (proto/open stream1 {:path "/test/persist"})
          datom (proto/datom :persist/key :persist/value "persistent-data")
          _ (proto/write stream1 stream-id datom)
          _ (proto/close stream1 stream-id)
          ;; Create new stream instance
          stream2 (fs/create-file-stream)
          result2 (proto/open stream2 {:path "/test/persist"})
          read-datoms (proto/read stream2 (:stream-id result2))]
      (is (= 1 (count read-datoms)))
      (is (= :persist/value (proto/value (first read-datoms))))
      (proto/close stream2 (:stream-id result2)))))

;; Run all tests
(defn -main []
  (cljs.test/run-tests 'dao-stream.file-stream-node-test))
