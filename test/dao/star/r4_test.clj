(ns dao.star.r4-test
  "Round r4: tenure enforced on every projection path, a gap on any exchange
   holder cleaning the whole session, and grants retained until a usable
   reading can observe them.  Holder ticks and judge ticks are scheduled
   independently."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.lease :as dl]
            [dao.star.driver :as driver]
            [dao.star.driver-test :as h]
            [dao.star.lease :as lease]
            [dao.star.router :as router]
            [dao.stream :as stream]))


(defn- driver-tick!
  "Move only the driver's tick stream: the holders' clock, not the judges'."
  [w ms]
  (stream/append! (get-in w [:ticks :driver]) (dl/tick {:ms ms})))


(defn- chunks
  [w x]
  (count (filter #(= :http/chunk (:http/effect %)) (h/effects w x))))


(defn- renewals
  [w]
  (count (filter #(= :dao.lease/renewal (:dao.lease/event %)) (h/values (:egress w)))))


(deftest an-input-triggered-patch-needs-the-exchange-holder-in-tenure
  (let [w (h/start! (h/world {:duration-ms 100 :subscription-max-ms 200}))
        s (h/begin-lineage! w)
        sub (h/call! w :get (str "/s/" s))]
    (h/pump! w)
    (dotimes [_ 17] (h/advance! w 10))
    (is (contains? (driver/subscriptions (:driver w) s) sub) "live below its cap")
    (let [before (chunks w sub)]
      ;; the driver's clock alone reaches the exchange cap; the lineage, renewed
      ;; a moment ago, is still live
      (driver-tick! w 240)
      (let [x (h/call! w :post (str "/k/" s) "{}")]
        (router/router-step! (:router w))
        (driver/driver-step! (:driver w))
        (is (= 1 (count (h/consumption-facts w))) "the input was consumed: the lineage is live")
        (is (seq (h/effects w x)) "and answered")
        (is (= before (chunks w sub)) "but no patch reached the subscription past its cap")
        (is (not (contains? (driver/subscriptions (:driver w) s) sub)))))))


(deftest a-staged-subscription-is-not-projected-after-the-lineage-bound
  (let [w (h/start! (h/world {:duration-ms 100}))
        s (h/begin-lineage! w)
        sub (h/call! w :get (str "/s/" s))]
    ;; router and driver only: the subscription is staged, its exchange grant
    ;; has not been published
    (router/router-step! (:router w))
    (driver/driver-step! (:driver w))
    (is (contains? (driver/subscriptions (:driver w) s) sub))
    ;; the driver's clock passes the lineage bound, then the exchange grant lands
    (driver-tick! w 210)
    (lease/exchange-lease-step! (:xl w))
    (driver/driver-step! (:driver w))
    (is (lease/granted? (get-in @(:driver w) [:sessions s :subs sub]))
        "the exchange holder has observed its grant and is holding")
    (is (empty? (h/effects w sub)) "yet nothing is projected: the lineage is past its bound")))


(deftest an-exchange-holder-gap-cleans-the-whole-session
  (let [w (h/start! (h/world {:duration-ms 1000}))
        s (h/begin-lineage! w)
        sub1 (h/call! w :get (str "/s/" s))
        _ (h/pump! w)
        sub2 (h/call! w :get (str "/s/" s))
        _ (h/pump! w)
        agent-of #(get-in @(:driver w) [:sessions s :subs %])
        flood! #(dotimes [_ 40] (stream/append! (:exchange-lease w) {:junk 1}))]
    (dotimes [_ 40] (h/advance! w 10))
    (is (pos? (renewals w)))
    (is (= #{sub1 sub2} (driver/subscriptions (:driver w) s)))
    (flood!)
    (lease/step! (agent-of sub2))             ; sub2's cursor moves up, sub1's does not
    (flood!)
    (lease/step! (agent-of sub2))
    (is (not (lease/gapped? (agent-of sub2))) "the sibling's cursor is newer and clean")
    (driver/driver-step! (:driver w))
    (is (empty? (driver/subscriptions (:driver w) s)) "the gap cleans every subscription")
    (is (lease/gapped? (driver/lineage-agent (:driver w) s)) "and the lineage renews nothing")
    (let [before (renewals w)]
      (dotimes [_ 10] (h/advance! w 10))
      (is (= before (renewals w))))))


(deftest an-unusable-first-reading-does-not-discard-the-grant
  (let [w (h/world)]
    (stream/append! (get-in w [:ticks :exchange]) (dl/tick {:ms 10}))
    (stream/append! (get-in w [:ticks :lineage]) (dl/tick {:ms 10}))
    ;; the holder's first reading is not a usable duration
    (stream/append! (get-in w [:ticks :driver])
                    {:dao.lease/event :dao.lease/tick :dao.lease/reading {:ms 0}})
    (let [x (h/call! w :get "/")]
      (h/pump! w)
      (let [s (first (driver/sessions (:driver w)))
            agent (driver/lineage-agent (:driver w) s)]
        (is (some #(= :dao.lease/accepted (:dao.lease/status %)) (h/values (:lineage-lease w))))
        (is (not (lease/granted? agent)) "an unusable reading observes nothing")
        (is (empty? (h/effects w x)))
        (testing "a usable reading later observes the retained grant, and the holder proceeds"
          (driver-tick! w 10)
          (h/pump! w)
          (is (lease/granted? agent))
          (is (= [:http/head :http/chunk :http/end] (mapv :http/effect (h/effects w x)))))))))
