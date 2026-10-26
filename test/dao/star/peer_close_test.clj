(ns dao.star.peer-close-test
  "A peer's close, deposited as `:http/closed`, is read by the driver as data."
  (:require [clojure.test :refer [deftest is]]
            [dao.star.driver :as driver]
            [dao.star.driver-test :as h]
            [dao.stream :as stream]))


(deftest a-peer-close-is-read-as-data-and-drops-the-subscription
  (let [w (h/start! (h/world))
        s (h/begin-lineage! w)
        sub (h/call! w :get (str "/s/" s))]
    (h/pump! w)
    (is (contains? (driver/subscriptions (:driver w) s) sub))
    (swap! (:open w) disj sub)
    (stream/append! (:ingress w) {:http/exchange sub :http/event :http/closed})
    (h/pump! w)
    (is (not (contains? (driver/subscriptions (:driver w) s) sub)))
    (let [before (count (h/effects w sub))]
      (h/input! w s)
      (is (= before (count (h/effects w sub))) "no patch is written to a dropped exchange"))))
