(ns yang.correspondence-batch-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [yang.correspondence-batch :as batch]))


(deftest progress-is-resumable-and-refusals-are-explicit
  (let [file (str "target/d1-batch-test-" (random-uuid) ".edn")
        origin [:source :program ["recorded-run" 0] 0]]
    (try
      (batch/initialize! file [:a :b])
      (is (= :not-run (get-in (batch/read-progress file) [:outcomes :a :status])))
      (batch/record! file :a {:status :pass :admission-origin origin :storage-bytes 12})
      (batch/record! file :b {:status :refused :stage :site-declarations})
      (is (= :pass (get-in (batch/read-progress file) [:outcomes :a :status])))
      (is (= :site-declarations (get-in (batch/read-progress file) [:outcomes :b :stage])))
      (is (thrown? Exception (batch/record! file :missing {:status :refused :stage :inputs})))
      (is (thrown? Exception (batch/record! file :b {:status :refused})))
      (is (= :site-declarations (get-in (batch/read-progress file) [:outcomes :b :stage])))
      (finally (.delete (io/file file))))))
