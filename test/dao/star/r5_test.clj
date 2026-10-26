(ns dao.star.r5-test
  "Round r5: a grant deferred by an unusable reading is never established over
   a lapse that follows it on the medium."
  (:require [clojure.test :refer [deftest is]]
            [dao.lease :as dl]
            [dao.star.driver :as driver]
            [dao.star.driver-test :as h]
            [dao.star.lease :as lease]
            [dao.stream :as stream]))


(deftest a-lapse-behind-a-deferred-grant-is-read-in-order
  (let [w (h/world)]
    (stream/append! (get-in w [:ticks :exchange]) (dl/tick {:ms 10}))
    (stream/append! (get-in w [:ticks :lineage]) (dl/tick {:ms 10}))
    ;; the holder's first reading is unusable
    (stream/append! (get-in w [:ticks :driver])
                    {:dao.lease/event :dao.lease/tick :dao.lease/reading {:ms 0}})
    (let [x (h/call! w :get "/")]
      (h/pump! w)
      (let [s (first (driver/sessions (:driver w)))
            agent (driver/lineage-agent (:driver w) s)
            lease-id (some #(when (= {:dao.star/lineage s} (:dao.lease/subject %))
                              (:dao.lease/lease %))
                           (h/values (:lineage-lease w)))]
        (is (some? lease-id) "the grant is on the medium")
        (is (not (lease/granted? agent)))
        ;; the grantor retires the lease while the holder still has no usable reading
        (stream/append! (:lineage-lease w) (dl/lapsed lease-id :silence))
        (stream/append! (get-in w [:ticks :driver]) (dl/tick {:ms 10}))
        (h/pump! w)
        (is (not (lease/holding? agent)) "no tenure: the lapse was read after the grant")
        (is (:lapsed? @agent))
        (let [before (count (h/values (:facts w)))]
          (is (not (lease/renew! agent)) "no renewal")
          (is (= before (count (h/values (:facts w))))))
        (is (empty? (h/effects w x)) "no projection")))))


(deftest foreign-grants-are-not-retained
  (let [w (h/start! (h/world))
        s (h/begin-lineage! w)
        agent (driver/lineage-agent (:driver w) s)]
    (is (nil? (:pending-facts @agent)) "no pending storage exists to grow")
    (dotimes [_ 30]
      (stream/append! (:lineage-lease w)
                      (dl/grant (dl/mint-lease-id) {:dao.star/lineage "other"}
                                {:dao.star/lineage "other"} {:ms 100})))
    (h/advance! w 10)
    (is (nil? (:pending-facts @agent)))))
