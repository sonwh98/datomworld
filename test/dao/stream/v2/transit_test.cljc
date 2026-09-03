(ns dao.stream.v2.transit-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream.v2.transit :as codec]))


(deftest portable-domain-roundtrip-test
  (testing "plain data survives the shared codec"
    (let [value {:dao.stream/type :dao.stream/ringbuffer
                 :dao.stream/identity [:stream "one" 7]
                 :ring/capacity 32
                 :ring/tags #{:a :b}}
          roundtrip (codec/decode (codec/encode value))]
      (is (= value roundtrip))
      (is (codec/portable-value? roundtrip)))))


(deftest descriptor-identity-gate-test
  (testing "descriptor encoding requires the contract identity key"
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Exception)
          (codec/encode-descriptor {:dao.stream/type :dao.stream/ringbuffer})))
    (let [descriptor {:dao.stream/type :dao.stream/ws
                      :dao.stream/identity [:stream "one"]
                      :ws/host "example.org"
                      :ws/port 443
                      :ws/path "/stream"}]
      (is (= descriptor (codec/decode-descriptor
                          (codec/encode-descriptor descriptor)))))))


(deftest nonportable-values-rejected-test
  (testing "host objects and unsafe numbers never enter the wire"
    (is (not (codec/portable-value? #?(:clj (Object.) :cljs (js-obj) :cljd (Object.)))))
    (is (not (codec/portable-value? 9007199254740992)))
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Exception)
          (codec/encode #?(:clj (Object.) :cljs (js-obj) :cljd (Object.)))))))
