(ns dao.star.r2-test
  "Review round r2 for dao.star Mode 1: staging until grants are observed,
   fail-closed tenure checks, consumption independent of emission, gap
   handling on every medium, grants published before a holder's first tick,
   failed-release retry, and the strengthened order and bound tests."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.await :as await]
            [dao.lease :as dl]
            [dao.star.driver :as driver]
            [dao.star.driver-test :as h]
            [dao.star.facts :as facts]
            [dao.star.lease :as lease]
            [dao.star.router :as router]
            [dao.stream :as stream]))


(defn- tick-only!
  "Move only the named tick streams (keys of `(:ticks w)`) to `now` and pump."
  [w ks now]
  (doseq [k ks]
    (stream/append! (get-in w [:ticks k]) (dl/tick {:ms now})))
  (h/pump! w))


(defn- pass!
  "Exactly one of every stepped part, no repetition."
  [w]
  (lease/exchange-lease-step! (:xl w))
  (lease/lineage-lease-step! (:ll w))
  (router/router-step! (:router w))
  (driver/driver-step! (:driver w))
  (h/boundary-step! w))


(defn- renewals
  [w]
  (count (filter #(= :dao.lease/renewal (:dao.lease/event %)) (h/values (:egress w)))))


(defn- agent-of
  [w s]
  (driver/lineage-agent (:driver w) s))


;; -----------------------------------------------------------------------------
;; Finding 5: stage until the grant is observed; fail closed
;; -----------------------------------------------------------------------------

(deftest the-begin-page-waits-for-the-lineage-grant-to-be-observed
  (let [w (h/world)]
    ;; the judges tick, the driver has no reading yet
    (tick-only! w [:exchange :lineage] 10)
    (let [x (h/call! w :get "/")]
      (h/pump! w)
      (is (= 1 (count (driver/sessions (:driver w)))) "the root fact was appended")
      (is (empty? (h/effects w x)) "no begin page before the grant is observed")
      (tick-only! w [:driver] 10)
      (is (= [:http/head :http/chunk :http/end] (mapv :http/effect (h/effects w x)))
          "answered once the grant is observed"))))


(deftest an-input-waits-while-the-holder-has-no-reading
  (let [w (h/start! (h/world))
        s (h/begin-lineage! w)
        w2 (h/world)]
    (is (some? s))
    (testing "a session whose driver never ticks consumes nothing"
      (tick-only! w2 [:exchange :lineage] 10)
      (let [x (h/call! w2 :get "/")]
        (h/pump! w2)
        (let [s2 (first (driver/sessions (:driver w2)))
              y (h/call! w2 :post (str "/k/" s2) "{}")]
          (h/pump! w2)
          (is (empty? (h/effects w2 y)))
          (is (empty? (filter #(= :consumption (facts/fact-kind %)) (h/values (:facts w2)))))
          (is (empty? (h/effects w2 x))))))))


(deftest a-subscription-projection-waits-for-its-exchange-grant
  (let [w (h/start! (h/world))
        s (h/begin-lineage! w)
        sub (h/call! w :get (str "/s/" s))]
    ;; router and driver only: the exchange judge has not granted yet
    (router/router-step! (:router w))
    (driver/driver-step! (:driver w))
    (is (contains? (driver/subscriptions (:driver w) s) sub))
    (is (empty? (h/effects w sub)) "no head or projection before the grant")
    (h/pump! w)
    (is (= [:http/head :http/chunk] (mapv :http/effect (h/effects w sub))))))


;; -----------------------------------------------------------------------------
;; Finding 6: pending publication respects the bound
;; -----------------------------------------------------------------------------

(defn- staged-world
  "A session with one input staged: the facts medium refused its fact."
  []
  (let [w (h/start! (h/world))
        s (h/begin-lineage! w)
        _ (reset! (:failing? w) true)
        x (h/input! w s "{\"inc\":1}")]
    (is (some? (get-in @(:driver w) [:sessions s :unpublished])) "staged")
    [w s x]))


(deftest a-staged-transition-is-dropped-past-the-bound
  (let [[w s x] (staged-world)]
    (reset! (:failing? w) false)
    ;; only the driver's clock moves: its holder reaches the bound
    (stream/append! (get-in w [:ticks :driver]) (dl/tick {:ms 200}))
    (driver/driver-step! (:driver w))
    (is (nil? (get-in @(:driver w) [:sessions s :unpublished])) "dropped")
    (is (empty? (h/consumption-facts w)))
    (is (empty? (h/effects w x)) "the requester sees silence")))


(deftest a-staged-transition-is-dropped-after-the-lease-lapses
  (let [[w s x] (staged-world)]
    (reset! (:failing? w) false)
    (let [lease-id (some #(when (= {:dao.star/lineage s} (:dao.lease/subject %))
                            (:dao.lease/lease %))
                         (h/values (:lineage-lease w)))]
      (stream/append! (:lineage-lease w) (dl/lapsed lease-id :silence))
      (driver/driver-step! (:driver w)))
    (is (nil? (get-in @(:driver w) [:sessions s :unpublished])))
    (is (empty? (h/consumption-facts w)))
    (is (empty? (h/effects w x)))))


(deftest a-staged-transition-waits-through-a-gap-and-never-publishes
  (let [[w s x] (staged-world)]
    (reset! (:failing? w) false)
    (dotimes [_ 70] (stream/append! (:lineage-lease w) {:junk true}))
    (driver/driver-step! (:driver w))
    (is (lease/gapped? (agent-of w s)))
    (h/pump! w)
    (is (some? (get-in @(:driver w) [:sessions s :unpublished])) "still staged")
    (is (empty? (h/consumption-facts w)) "a gapped holder publishes nothing")
    (is (empty? (h/effects w x)))))


;; -----------------------------------------------------------------------------
;; Finding 7: consumption independent of emission
;; -----------------------------------------------------------------------------

(def every-other-program
  "Consumes two inputs per emitted model."
  (await/go*
    '[(let [ic (await/cursor in)]
        (await/>! out (assoc {} :count 0))
        (do (def lp
              (fn [k total]
                (let [x (await/<! ic)
                      t (+ total (get (get x :signals) :inc 1))]
                  (if (= k 1)
                    (do (await/>! out (assoc {} :count t)) (lp 0 t))
                    (lp 1 t)))))
            (lp 0 0)))]
    {}))


(deftest every-consumed-input-yields-a-successor-whether-or-not-the-program-emitted
  (let [w (h/start! (h/world {:program every-other-program}))
        s (h/begin-lineage! w)
        x1 (h/input! w s)
        o1 (h/occ-of (h/sse-text w x1))]
    (testing "no emission: the previous model is retained under a new occurrence"
      (is (some? o1))
      (is (not= s o1))
      (is (str/includes? (h/sse-text w x1) ">0<"))
      (is (= [(facts/consumption s o1 s)] (h/consumption-facts w))))
    (testing "replaying the consumed token runs nothing"
      (let [stale (h/input! w s)]
        (is (str/includes? (h/sse-text w stale) ">0<"))
        (is (= o1 (h/occ-of (h/sse-text w stale))))
        (is (= 1 (count (h/consumption-facts w))))))
    (testing "the second input reaches the emission"
      (let [x2 (h/input! w o1)
            o2 (h/occ-of (h/sse-text w x2))]
        (is (str/includes? (h/sse-text w x2) ">2<"))
        (is (= [(facts/consumption s o1 s) (facts/consumption s o2 o1)]
               (h/consumption-facts w)))))))


;; -----------------------------------------------------------------------------
;; Finding 8: a gap on every medium the driver reads
;; -----------------------------------------------------------------------------

(defn- subscribed
  [opts]
  (let [w (h/start! (h/world (merge {:duration-ms 1000} opts)))
        s (h/begin-lineage! w)
        sub (h/call! w :get (str "/s/" s))]
    (h/pump! w)
    (dotimes [_ 40] (h/advance! w 10))
    [w s sub]))


(defn- flood-ticks!
  [w n]
  (dotimes [_ n]
    (let [now (swap! (:clock w) inc)]
      (stream/append! (get-in w [:ticks :driver]) (dl/tick {:ms now})))))


(deftest a-gap-on-any-medium-forgets-subscriptions-and-stops-renewals
  (doseq [[medium opts trigger]
          [[:tick {:driver-tick-capacity 8}
            (fn [w _] (flood-ticks! w 20))]
           [:lineage-lease {}
            (fn [w _] (dotimes [_ 70] (stream/append! (:lineage-lease w) {:junk 1})))]
           [:exchange-lease {}
            (fn [w _] (dotimes [_ 70] (stream/append! (:exchange-lease w) {:junk 1})))]
           [:view {:session-capacity 8}
            (fn [w s]
              (let [out (get-in @(:driver w) [:sessions s :vm-out])]
                (dotimes [_ 20] (stream/append! out (assoc {} :count 0)))
                (h/input! w s)))]
           [:begin {}
            (fn [w _] (dotimes [_ 40] (stream/append! (:begin w) {:junk 1})))]]]
    (let [[w s sub] (subscribed opts)]
      (is (pos? (renewals w)) (str medium ": renewing while healthy"))
      (is (contains? (driver/subscriptions (:driver w) s) sub))
      (trigger w s)
      (driver/driver-step! (:driver w))
      (is (empty? (driver/subscriptions (:driver w) s)) (str medium ": subscriptions forgotten"))
      (let [before (renewals w)]
        (dotimes [_ 10] (h/advance! w 10))
        (is (= before (renewals w)) (str medium ": no renewal after the gap"))))))


;; -----------------------------------------------------------------------------
;; Finding 9: a grant published before the holder's first tick is not lost
;; -----------------------------------------------------------------------------

(deftest a-grant-published-before-the-holders-first-tick-is-observed-later
  (let [w (h/world)]
    (tick-only! w [:exchange :lineage] 10)
    (h/call! w :get "/")
    (h/pump! w)
    (let [s (first (driver/sessions (:driver w)))
          agent (agent-of w s)]
      (is (some #(= :dao.lease/accepted (:dao.lease/status %)) (h/values (:lineage-lease w)))
          "the judge has published the grant")
      (is (not (lease/granted? agent)) "the holder has no reading yet")
      (tick-only! w [:driver] 10)
      (is (lease/granted? agent) "observed once a reading exists"))))


;; -----------------------------------------------------------------------------
;; Finding 10: a failed release is retried
;; -----------------------------------------------------------------------------

(deftest a-failed-release-is-retried-on-later-passes
  (let [w (h/start! (h/world {:program h/one-shot-program}))
        s (h/begin-lineage! w)
        agent (agent-of w s)
        released? #(some (fn [f] (= :dao.lease/released (:dao.lease/status f)))
                         (h/values (:facts w)))]
    (reset! (:failing? w) #(= :dao.lease/released (:dao.lease/status %)))
    (h/input! w s)
    (is (some #(= (facts/ended s :halted) %) (h/values (:facts w))) "the lineage ended")
    (is (not (released?)) "the release append failed")
    (is (lease/release-pending? agent))
    (reset! (:failing? w) false)
    (h/pump! w)
    (is (released?) "retried and appended")
    (is (not (lease/release-pending? agent)))))


;; -----------------------------------------------------------------------------
;; Finding 11: tests that fail if the order is reversed
;; -----------------------------------------------------------------------------

(defn- hooked
  "A reader/writer over `handle` that runs `hook!` once, before serving its
   first read."
  [handle hook!]
  (let [fired (atom false)]
    (reify
      stream/IDaoStreamDescriptor
      (descriptor [_] (stream/descriptor handle))


      stream/IDaoStreamReader

      (cursor [_ anchor] (stream/cursor handle anchor))

      (next
        [_ cursor]
        (when (compare-and-set! fired false true) (hook!))
        (stream/next handle cursor))


      stream/IDaoStreamWriter

      (append! [_ v] (stream/append! handle v)))))


(deftest the-fact-is-published-and-its-request-deposited-inside-one-pass
  (let [ingress (h/medium 16) facts-m (h/facts-log) lease-m (h/medium 16)
        begin (h/medium 16) session (h/medium 16)
        hooked-ingress (hooked ingress
                               (fn []
                                 ;; during the router's read of the ingress: the
                                 ;; fact is accepted, then its request deposited
                                 (stream/append! facts-m (facts/root "s"))
                                 (stream/append! ingress
                                                 {:http/exchange [1 1] :http/event :http/request
                                                  :http/method :post :http/path "/k/s"})))
        r (router/make-router {:ingress hooked-ingress :facts facts-m :lease lease-m
                               :begin begin :co-located? true
                               :facts-declaration (lease/facts-declaration)
                               :session-medium (constantly session)})]
    (router/router-step! r)
    (is (empty? (h/values begin)) "never begin")
    (is (= [:input] (mapv :kind (h/values session)))
        "ingress is read first, then the fact is drained, then the request classified")))


(deftest a-token-is-chased-with-single-passes
  (let [w (h/start! (h/world {:duration-ms 1000000}))
        s (h/begin-lineage! w)]
    (loop [token s i 1]
      (when (<= i 15)
        (let [x (h/call! w :post (str "/k/" token) "{\"inc\":1}")
              text (loop [n 0]
                     (pass! w)
                     (let [t (h/sse-text w x)]
                       (if (or (str/includes? t "data-occ") (> n 20)) t (recur (inc n)))))]
          (is (str/includes? text (str ">" i "<")) (str "input " i " consumed in single passes"))
          (recur (h/occ-of text) (inc i)))))))


(deftest renewals-land-at-the-interval-and-under-half-the-duration
  (let [w (h/start! (h/world {:duration-ms 100}))
        s (h/begin-lineage! w)
        _ (h/call! w :get (str "/s/" s))
        _ (h/pump! w)
        interval (get (:interval w) :ms)
        marks (loop [i 0 last-count (renewals w) out []]
                (if (= i 40)
                  out
                  (do (h/advance! w 10)
                      (let [c (renewals w)]
                        (recur (inc i) c (if (> c last-count) (conj out @(:clock w)) out))))))
        gaps (map - (rest marks) marks)]
    (is (>= (count marks) 3) "renewing repeatedly")
    (is (= 30 interval) "the interval is sized by lease/renewal-interval")
    (doseq [g gaps]
      (is (>= g interval) "never before the interval has elapsed")
      (is (< g 50) "strictly under half the 100 ms duration"))))
