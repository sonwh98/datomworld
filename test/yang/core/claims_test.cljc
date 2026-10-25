(ns yang.core.claims-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream.ringbuffer :as ring]
    [yang.core.claims :as claims]
    [yin.vm.test-utils :as tu]))


(defn- claims-stream
  []
  (:dao.stream/handle
    (ring/create! {:dao.stream/type ring/transport-type,
                   ring/capacity-key 8})))


(defn- count-claims
  [h]
  (count (tu/drain h)))


(deftest append-claims-evidence-test
  (testing "a failed evidence record appends no claims"
    (let [h (claims-stream)
          r (claims/append-claims!
              h
              {:find-evidence (constantly nil),
               :record-evidence! (constantly
                                   {:dao.stream/outcome :dao.stream/full})}
              :root
              [{:c 1}])]
      (is (= :refused (:status r)))
      (is (= :yang.claims/evidence-failed (:reason r)))
      (is (zero? (count-claims h)))))
  (testing "a recorded evidence appends the claims"
    (let [h (claims-stream)
          r (claims/append-claims!
              h
              {:find-evidence (constantly nil),
               :record-evidence! (constantly
                                   {:dao.stream/outcome :dao.stream/ok})}
              :root
              [{:c 1} {:c 2}])]
      (is (= :ok (:status r)))
      (is (= 2 (count-claims h)))))
  (testing "existing evidence appends directly"
    (let [h (claims-stream)
          r (claims/append-claims!
              h
              {:find-evidence (constantly {:e 1}),
               :record-evidence! (fn [_] (throw (ex-info "no" {})))}
              :root
              [{:c 1}])]
      (is (= :ok (:status r)))
      (is (= 1 (count-claims h))))))
