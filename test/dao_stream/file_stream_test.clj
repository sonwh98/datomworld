(ns dao-stream.file-stream-test
  "Tests for FileStream implementation"
  (:require [clojure.test :refer [deftest testing is]]
            [dao-stream.protocol :as proto]
            [dao-stream.file-stream :as fs]
            [clojure.java.io :as io]))

(defn cleanup-test-streams []
  "Clean up test stream files"
  (let [test-dir (io/file "data/streams")]
    (when (.exists test-dir)
      (doseq [file (.listFiles test-dir)
              :when (and (.isFile file)
                        (or (.contains (.getName file) "test")
                            (.contains (.getName file) "user_42")))]
        (.delete file)))))

(deftest test-open-stream
  (testing "Opening a stream"
    (let [stream (fs/create-file-stream)
          result (proto/open stream {:path "/test/stream1"})]
      (is (contains? result :stream-id))
      (is (= :connected (:status result)))
      (is (= "/test/stream1" (:path result)))
      (is (= 0 (:offset result)))
      (is (nil? (:error result)))
      (proto/close stream (:stream-id result))))
  (cleanup-test-streams))

(deftest test-write-single-datom
  (testing "Writing a single datom to stream"
    (let [stream (fs/create-file-stream)
          {:keys [stream-id]} (proto/open stream {:path "/test/write-single"})
          datom (proto/datom :user/1 :name "Alice" nil {})
          write-result (proto/write stream stream-id datom)]
      (is (= :written (:status write-result)))
      (is (= 1 (:count write-result)))
      (is (= 1 (:offset write-result)))
      (is (nil? (:error write-result)))
      (proto/close stream stream-id)))
  (cleanup-test-streams))

(deftest test-write-multiple-datoms
  (testing "Writing multiple datoms to stream"
    (let [stream (fs/create-file-stream)
          {:keys [stream-id]} (proto/open stream {:path "/test/write-multiple"})
          datoms [(proto/datom :user/1 :name "Alice")
                  (proto/datom :user/1 :email "alice@example.com")
                  (proto/datom :user/1 :age 30)]
          write-result (proto/write stream stream-id datoms)]
      (is (= :written (:status write-result)))
      (is (= 3 (:count write-result)))
      (is (= 3 (:offset write-result)))
      (proto/close stream stream-id)))
  (cleanup-test-streams))

(deftest test-read-datoms
  (testing "Reading datoms from stream"
    (let [stream (fs/create-file-stream)
          {:keys [stream-id]} (proto/open stream {:path "/test/read"})
          datoms [(proto/datom :user/42 :email/subject "Test 1")
                  (proto/datom :user/42 :email/subject "Test 2")
                  (proto/datom :user/42 :email/subject "Test 3")]
          _ (proto/write stream stream-id datoms)
          read-datoms (proto/read stream stream-id)]
      (is (= 3 (count read-datoms)))
      (is (= :user/42 (proto/entity (first read-datoms))))
      (is (= :email/subject (proto/attribute (first read-datoms))))
      (is (= "Test 1" (proto/value (first read-datoms))))
      (proto/close stream stream-id)))
  (cleanup-test-streams))

(deftest test-read-with-limit
  (testing "Reading datoms with limit"
    (let [stream (fs/create-file-stream)
          {:keys [stream-id]} (proto/open stream {:path "/test/read-limit"})
          datoms [(proto/datom :user/1 :msg "One")
                  (proto/datom :user/1 :msg "Two")
                  (proto/datom :user/1 :msg "Three")
                  (proto/datom :user/1 :msg "Four")
                  (proto/datom :user/1 :msg "Five")]
          _ (proto/write stream stream-id datoms)
          read-datoms (proto/read stream stream-id {:limit 2})]
      (is (= 2 (count read-datoms)))
      (is (= "One" (proto/value (first read-datoms))))
      (is (= "Two" (proto/value (second read-datoms))))
      (proto/close stream stream-id)))
  (cleanup-test-streams))

(deftest test-status
  (testing "Getting stream status"
    (let [stream (fs/create-file-stream)
          {:keys [stream-id]} (proto/open stream {:path "/test/status"})
          datoms [(proto/datom :user/1 :x 1)
                  (proto/datom :user/1 :x 2)
                  (proto/datom :user/1 :x 3)]
          _ (proto/write stream stream-id datoms)
          status (proto/status stream stream-id)]
      (is (= :connected (:status status)))
      (is (= stream-id (:stream-id status)))
      (is (= "/test/status" (:path status)))
      (is (= 3 (:total status)))
      (is (= 0 (:lag status))) ;; No lag since we haven't read yet
      (is (= :read-write (:mode status)))
      (is (empty? (:peers status))) ;; File streams have no peers
      (proto/close stream stream-id)))
  (cleanup-test-streams))

(deftest test-close-stream
  (testing "Closing a stream"
    (let [stream (fs/create-file-stream)
          {:keys [stream-id]} (proto/open stream {:path "/test/close"})
          close-result (proto/close stream stream-id)]
      (is (= :closed (:status close-result)))
      (is (= stream-id (:stream-id close-result)))
      (is (nil? (:error close-result)))))
  (cleanup-test-streams))

(deftest test-datom-helper-functions
  (testing "Datom helper functions"
    (let [datom (proto/datom :user/42 :email/subject "Test" #inst "2025-11-30" {:origin "test"})]
      (is (proto/datom? datom))
      (is (= :user/42 (proto/entity datom)))
      (is (= :email/subject (proto/attribute datom)))
      (is (= "Test" (proto/value datom)))
      (is (= #inst "2025-11-30" (proto/time datom)))
      (is (= {:origin "test"} (proto/metadata datom))))))

(deftest test-retract-datom
  (testing "Creating retraction datoms"
    (let [original (proto/datom :user/42 :email/subject "Old Subject" #inst "2025-11-30" {})
          retraction (proto/retract-datom original #inst "2025-12-01")]
      (is (= :user/42 (proto/entity retraction)))
      (is (= :db/retract (proto/attribute retraction)))
      (is (= :email/subject (nth retraction 2))) ;; original attribute
      (is (= "Old Subject" (nth retraction 3))) ;; original value
      (is (= #inst "2025-12-01" (proto/time retraction)))
      (is (= {:operation :retract} (proto/metadata retraction))))))

(deftest test-filter-datoms-client-side
  (testing "Client-side filtering of datoms"
    (let [stream (fs/create-file-stream)
          {:keys [stream-id]} (proto/open stream {:path "/test/filter"})
          datoms [(proto/datom :user/42 :email/subject "Urgent: Meeting")
                  (proto/datom :user/42 :email/to :user/99)
                  (proto/datom :user/42 :email/subject "Regular email")
                  (proto/datom :user/42 :email/body "Content here")]
          _ (proto/write stream stream-id datoms)
          subjects (->> (proto/read stream stream-id)
                        (filter #(= (proto/attribute %) :email/subject))
                        (map proto/value))]
      (is (= 2 (count subjects)))
      (is (= #{"Urgent: Meeting" "Regular email"} (set subjects)))
      (proto/close stream stream-id)))
  (cleanup-test-streams))

(deftest test-multiple-streams
  (testing "Multiple independent streams"
    (let [stream (fs/create-file-stream)
          email-stream (proto/open stream {:path "/user/42/email"})
          calendar-stream (proto/open stream {:path "/user/42/calendar"})
          _ (proto/write stream (:stream-id email-stream)
                         (proto/datom :user/42 :email/subject "Email data"))
          _ (proto/write stream (:stream-id calendar-stream)
                         (proto/datom :event/1 :event/time #inst "2025-12-01"))
          email-datoms (proto/read stream (:stream-id email-stream))
          calendar-datoms (proto/read stream (:stream-id calendar-stream))]
      (is (= 1 (count email-datoms)))
      (is (= 1 (count calendar-datoms)))
      (is (= :email/subject (proto/attribute (first email-datoms))))
      (is (= :event/time (proto/attribute (first calendar-datoms))))
      (proto/close stream (:stream-id email-stream))
      (proto/close stream (:stream-id calendar-stream))))
  (cleanup-test-streams))

;; Run all tests
(comment
  (clojure.test/run-tests 'dao-stream.file-stream-test)
  )
