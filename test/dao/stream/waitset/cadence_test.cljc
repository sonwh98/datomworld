(ns dao.stream.waitset.cadence-test
  "The pure idle curve, on every host with no fixture at all.

   `cadence-step` never calls `check`, reads no clock, and touches no
   stream — these assertions are arithmetic on values the test supplied, so
   a clock or a medium appearing in here would itself be the defect being
   looked for."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream.waitset.cadence :as cadence]))


(defn- throws?
  "True when thunk throws. `thrown?` needs a literal class name, which
   differs per host; a thunk keeps the corpus host-neutral."
  [thunk]
  (try (thunk) false
       (catch #?(:clj Exception :cljs js/Error :cljd Object) _ true)))


(deftest a-wake-resets-the-curve-to-poll-ms
  (testing "however far the curve had climbed, a moved round arms :poll-ms"
    (let [c0 (cadence/init {:poll-ms 5, :backoff {:factor 2, :ceiling-ms 40}})
          idle (-> c0
                   (cadence/cadence-step false) :cadence-state
                   (cadence/cadence-step false) :cadence-state)]
      (is (< 5 (:current idle)) "two idle rounds climbed the curve")
      (let [{:keys [cadence-state sleep-ms]} (cadence/cadence-step idle true)]
        (is (= 5 sleep-ms) "the wake answers :poll-ms")
        (is (= 5 (:current cadence-state)) "and resets the threaded curve")
        (let [next-idle (cadence/cadence-step cadence-state false)]
          (is (= 5 (:sleep-ms next-idle))
              "the first idle round after the wake still arms :poll-ms, not a pre-climbed step"))))))


(deftest an-idle-owner-climbs-to-the-ceiling-and-stays
  (let [c0 (cadence/init {:poll-ms 5, :backoff {:factor 2, :ceiling-ms 40}})]
    (testing "a fresh owner's first idle sleep is :poll-ms itself, then it doubles"
      (let [r1 (cadence/cadence-step c0 false)
            r2 (cadence/cadence-step (:cadence-state r1) false)
            r3 (cadence/cadence-step (:cadence-state r2) false)]
        (is (= [5 10 20] [(:sleep-ms r1) (:sleep-ms r2) (:sleep-ms r3)]))))
    (testing "the ceiling holds: no further climb, no reset without a wake"
      (let [at-ceiling (-> c0
                           (cadence/cadence-step false) :cadence-state
                           (cadence/cadence-step false) :cadence-state
                           (cadence/cadence-step false) :cadence-state
                           (cadence/cadence-step false) :cadence-state)]
        (is (= 40 (:current at-ceiling)))
        (is (= 40 (:sleep-ms (cadence/cadence-step at-ceiling false))))
        (is (= 40 (-> at-ceiling
                      (cadence/cadence-step false) :cadence-state
                      (cadence/cadence-step false) :sleep-ms)))))))


(deftest a-current-above-the-ceiling-comes-back-down-to-it
  (let [c0 (assoc (cadence/init {:poll-ms 5, :backoff {:factor 2, :ceiling-ms 40}})
                  :current 1000)]
    (is (= 40 (:sleep-ms (cadence/cadence-step c0 false)))
        "the ceiling is a cap in both directions, never a lower bound to climb out of")))


(deftest without-backoff-the-curve-is-flat
  (let [c0 (cadence/init {:poll-ms 25})
        s1 (cadence/cadence-step c0 false)
        s2 (cadence/cadence-step (:cadence-state s1) false)
        s3 (cadence/cadence-step (:cadence-state s2) false)]
    (is (= 25 (:sleep-ms s1) (:sleep-ms s2) (:sleep-ms s3))
        "no :backoff means the owner polls at :poll-ms forever — the host chose a flat curve")))


(deftest a-fresh-state-wakes-at-poll-ms
  (is (= 5 (:current (cadence/init {:poll-ms 5, :backoff {:factor 2, :ceiling-ms 40}}))))
  (is (= 5 (:sleep-ms (cadence/cadence-step
                        (cadence/init {:poll-ms 5, :backoff {:factor 2, :ceiling-ms 40}})
                        false)))))


(deftest parameters-are-validated-at-init
  (testing "a defect in the host's cadence data is named before any host sleeps on it"
    (is (throws? (fn [] (cadence/init {}))))
    (is (throws? (fn [] (cadence/init {:poll-ms 0}))))
    (is (throws? (fn [] (cadence/init {:poll-ms 5, :backoff {:factor 2}}))))
    (is (throws? (fn [] (cadence/init {:poll-ms 5, :backoff {:ceiling-ms 40}}))))
    (is (= 5 (:current (cadence/init {:poll-ms 5 :backoff {:factor 2 :ceiling-ms 40}})))))
  (testing "a factor below 1 would shrink the interval toward a zero busy-loop"
    (is (throws? (fn [] (cadence/init {:poll-ms 5, :backoff {:factor 0.5, :ceiling-ms 40}}))))
    (is (throws? (fn [] (cadence/init {:poll-ms 5, :backoff {:factor 0, :ceiling-ms 40}}))))
    (is (throws? (fn [] (cadence/init {:poll-ms 5, :backoff {:factor "2", :ceiling-ms 40}}))))
    (is (= 5 (:current (cadence/init {:poll-ms 5, :backoff {:factor 1, :ceiling-ms 5}})))
        "a factor of exactly 1 and a ceiling at the base are a flat curve, not a defect"))
  (testing "a ceiling below :poll-ms would idle faster than the base interval"
    (is (throws? (fn [] (cadence/init {:poll-ms 25, :backoff {:factor 2, :ceiling-ms 20}}))))
    (is (throws? (fn [] (cadence/init {:poll-ms 25, :backoff {:factor 2, :ceiling-ms nil}}))))))
