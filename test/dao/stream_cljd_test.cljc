(ns dao.stream-cljd-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
    [dao.stream.link :as link]
    [dao.stream.transport.ringbuffer]))


(defn- ringbuffer-descriptor
  ([] (ringbuffer-descriptor nil))
  ([capacity]
   {:transport {:type :ringbuffer
                :mode :create
                :capacity capacity}}))


(deftest ringbuffer-descriptor-open-contract-test
  (testing "ringbuffer descriptors are realizable via ds/open!"
    (let [stream (ds/open! (ringbuffer-descriptor 1))]
      (is (= :ok (:result (ds/put! stream :value))))
      (is (= :value (:ok (ds/drain-one! stream))))))

  (testing "higher-level link state creation can realize its remote ringbuffer"
    (let [local (ds/open! (ringbuffer-descriptor))
          state (link/make-link-state local)
          remote (:remote-stream state)]
      (is (some? remote))
      (is (= :ok (:result (ds/put! remote :payload))))
      (is (= :payload (:ok (ds/drain-one! remote)))))))
