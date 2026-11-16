(ns yang.correspondence-batch-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [yang.correspondence-batch :as batch]))


(deftest progress-is-resumable-and-refusals-are-explicit
  (let [file (str "target/d1-batch-test-" (random-uuid) ".edn")
        origin [:source :program ["recorded-run" 0] 0]
        address (jing/materialize! (mem/create-content-mem) {:test true})
        addresses (zipmap [:input :snapshot :evidence :profile :root :stamped-root] (repeat address))]
    (try
      (batch/initialize! file [:a :b])
      (is (thrown? Exception (batch/record! file :a {:status :pass :admission-origin origin :storage-bytes 12})))
      (is (= :not-run (get-in (batch/read-progress file) [:outcomes :a :status])))
      (batch/record! file :a {:status :pass :admission-origin origin :storage-bytes 12 :addresses addresses})
      (is (= addresses (get-in (batch/read-progress file) [:outcomes :a :addresses])))
      (batch/initialize! (str file ".resume") [:a])
      (batch/record! (str file ".resume") :a {:status :pass :admission-origin origin :storage-bytes 0 :addresses addresses})
      (is (= 0 (get-in (batch/read-progress (str file ".resume")) [:outcomes :a :storage-bytes])))
      (batch/record! file :b {:status :refused :stage :site-declarations})
      (is (= :pass (get-in (batch/read-progress file) [:outcomes :a :status])))
      (is (= :site-declarations (get-in (batch/read-progress file) [:outcomes :b :stage])))
      (is (thrown? Exception (batch/record! file :missing {:status :refused :stage :inputs})))
      (is (thrown? Exception (batch/record! file :b {:status :refused})))
      (is (= :site-declarations (get-in (batch/read-progress file) [:outcomes :b :stage])))
      (finally (.delete (io/file file)) (.delete (io/file (str file ".resume")))))))

(deftest corpus-index-requires-retained-matching-content
  (let [store (mem/create-content-mem)
        put #(jing/materialize! store %)
        origin [:source :program ["run" 0] 0]
        root (put [:root])
        stamped (put [:stamped])
        evidence (put {:version 1 :fixtures {:a {}}})
        profile (put {:profile 1})
        input (put {:fixture {:id :a} :admission-origin origin})
        snapshot (put {:fixture :a :origin origin :root root :stamped-root stamped})
        addresses {:input input :snapshot snapshot :root root :stamped-root stamped
                   :evidence evidence :profile profile}
        progress {:fixture-count 1 :outcomes {:a {:status :pass :storage-bytes 1
                                                   :admission-origin origin :addresses addresses}}}]
    (is (= addresses (get-in (batch/corpus-index! store progress) [:fixtures :a :addresses])))
    (is (thrown? Exception (batch/corpus-index! store (assoc-in progress [:outcomes :a :status] :not-run))))
    (is (thrown? Exception (batch/corpus-index! store (assoc-in progress [:outcomes :a :admission-origin]
                                                                  [:source :program ["other" 0] 0]))))
    (is (thrown? Exception (batch/corpus-index! store (assoc progress :fixture-count 2))))
    (is (thrown? Exception (batch/corpus-index! store (assoc-in progress [:outcomes :a :addresses :root] evidence))))))
