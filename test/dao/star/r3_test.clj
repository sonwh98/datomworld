(ns dao.star.r3-test
  "Round r3: the exchange lease's tenure cap (dead-peer bound on a host that
   reports neither close nor failed write) and the complete-history
   session-facts medium."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.star.driver :as driver]
            [dao.star.driver-test :as h]
            [dao.star.facts :as facts]
            [dao.star.lease :as lease]
            [dao.star.router :as router]
            [dao.stream :as stream]))


(defn- stream-append!
  [w v]
  (stream/append! (:facts w) v))


(defn- refused
  "The :refused key of an assembly refusal, or :assembled."
  [f]
  (try (f) :assembled
       (catch clojure.lang.ExceptionInfo e (:refused (ex-data e)))))


(def ^:private evicting {:retention :evict-oldest :capacity 16 :value-domain :portable-values})


(deftest constructors-refuse-a-facts-medium-that-is-not-complete-history
  (let [w (h/world)
        driver-config (:config @(:driver w))
        xl-config {:ingress (:ingress w) :egress (:egress w) :lease-medium (:exchange-lease w)
                   :tick (get-in w [:ticks :exchange]) :open-exchanges (constantly #{})
                   :close-exchange (constantly true) :units h/units :duration {:ms 100}
                   :cadence {:ms 10} :tolerance {:ms 0} :capacity 8
                   :subscription-max {:ms 400}}
        ll-config {:facts (:facts w) :lease-medium (:lineage-lease w)
                   :tick (get-in w [:ticks :lineage]) :retire! identity :units h/units
                   :duration {:ms 100} :cadence {:ms 10} :tolerance {:ms 0}
                   :facts-declaration (lease/facts-declaration)}
        router-config {:ingress (:ingress w) :facts (:facts w) :lease (:lineage-lease w)
                       :begin (:begin w) :co-located? true
                       :session-medium (constantly nil)
                       :facts-declaration (lease/facts-declaration)}]
    (testing "the complete declaration assembles"
      (is (= :assembled (refused #(driver/make-driver driver-config))))
      (is (= :assembled (refused #(lease/make-lineage-lease ll-config))))
      (is (= :assembled (refused #(router/make-router router-config)))))
    (testing "an :evict-oldest declaration is refused on the declaration"
      (is (= :session-facts (refused #(driver/make-driver
                                        (assoc driver-config :facts-declaration evicting)))))
      (is (= :session-facts (refused #(lease/make-lineage-lease
                                        (assoc ll-config :facts-declaration evicting)))))
      (is (= :session-facts (refused #(router/make-router
                                        (assoc router-config :facts-declaration evicting)))))
      (is (= :session-facts (refused #(router/make-router (dissoc router-config :facts-declaration))))))
    (testing "a subscription cap is required"
      (is (= :assembled (refused #(lease/make-exchange-lease xl-config))))
      (is (= :subscription-cap (refused #(lease/make-exchange-lease
                                           (dissoc xl-config :subscription-max)))))
      (is (= :subscription-cap (refused #(lease/make-exchange-lease
                                           (assoc xl-config :subscription-max {:ms 0}))))))))


(deftest session-facts-keep-the-root-past-any-ring-capacity
  (let [w (h/start! (h/world))
        s (h/begin-lineage! w)
        old-ring-capacity 256]
    (dotimes [i (* 2 old-ring-capacity)]
      (stream-append! w (facts/ended (str "other-" i) :halted)))
    (let [r (lease/drain (:facts w) (lease/oldest-cursor (:facts w)))]
      (is (not (:gap? r)) "a complete-history medium cannot gap")
      (is (> (count (:values r)) old-ring-capacity))
      (is (= (facts/root s) (first (:values r))) "the root is still readable from the origin"))
    (testing "and the router still routes the old lineage as live"
      (h/pump! w)
      (is (= :current (:kind (facts/resolve-token (router/index (:router w)) s)))))))


;; -----------------------------------------------------------------------------
;; Ruling 3: the tenure cap bounds a subscription whose driver keeps renewing
;; -----------------------------------------------------------------------------

(defn- renewals
  [w]
  (count (filter #(= :dao.lease/renewal (:dao.lease/event %)) (h/values (:egress w)))))


(deftest a-subscription-the-driver-keeps-renewing-ends-at-its-cap
  (let [w (h/start! (h/world {:duration-ms 100 :subscription-max-ms 200}))
        s (h/begin-lineage! w)
        sub (h/call! w :get (str "/s/" s))
        quiet (h/call! w :get "/unrouted")
        grant-of (fn [x]
                   (some #(when (= {:http/exchange x} (:dao.lease/subject %)) %)
                         (h/values (:exchange-lease w))))]
    (h/pump! w)
    (testing "only the subscription's grant carries the cap"
      (is (= {:ms 200} (:dao.lease/max (grant-of sub))))
      (is (nil? (:dao.lease/max (grant-of quiet)))))
    (testing "the driver renews on its ticks while the cap is not reached"
      (dotimes [_ 10] (h/advance! w 10))
      (is (pos? (renewals w)))
      (is (contains? @(:open w) sub))
      (is (contains? (driver/subscriptions (:driver w) s) sub)))
    (testing "past the cap renewal stops, the reclaim closes it, and the lapse is recorded"
      (dotimes [_ 20] (h/advance! w 10))
      (let [after (renewals w)]
        (dotimes [_ 10] (h/advance! w 10))
        (is (= after (renewals w)) "no renewal after the bound"))
      (is (not (contains? (driver/subscriptions (:driver w) s) sub)) "dropped by the driver")
      (is (not (contains? @(:open w) sub)) "close-exchange ran")
      (is (some #(= {:http/exchange sub :http/event :http/closed} %) (h/values (:ingress w)))
          ":http/closed was deposited")
      (is (some #(and (= :dao.lease/lapsed (:dao.lease/status %))
                      (= (:dao.lease/lease (grant-of sub)) (:dao.lease/lease %))
                      (= :cap (:dao.lease/cause %)))
                (h/values (:exchange-lease w)))
          ":lapsed was recorded with cause :cap"))
    (testing "the never-answered exchange still lapses by silence"
      (is (not (contains? @(:open w) quiet)))
      (is (some #(and (= (:dao.lease/lease (grant-of quiet)) (:dao.lease/lease %))
                      (= :silence (:dao.lease/cause %)))
                (h/values (:exchange-lease w)))))))
