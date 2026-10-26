(ns dao.star.driver-test
  "dao.star Mode 1 Completion Criteria item 1, in memory: ring buffers for
   every medium, a fake boundary implementing the inventory, the reclaim and
   the egress interpretation, and ticks appended by hand -- no clock.  The
   harness is public so the loopback test composes the same pieces."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.await :as await]
            [dao.lease :as dl]
            [dao.star.driver :as driver]
            [dao.star.facts :as facts]
            [dao.star.lease :as lease]
            [dao.star.router :as router]
            [dao.stream :as stream]
            [dao.stream.memory-log :as memory-log]
            [dao.stream.ringbuffer :as ring]))


(defn medium
  [capacity]
  (:dao.stream/handle (ring/create! {:dao.stream/type :dao.stream/ringbuffer
                                     :dao.stream.ringbuffer/capacity capacity})))


(defn facts-log
  "A complete-history session-facts medium."
  []
  (:dao.stream/handle (memory-log/create! {:dao.stream/type :dao.stream/memory-log})))


(defn values
  [handle]
  (:values (lease/drain handle (lease/oldest-cursor handle))))


(def counter-program
  "A sequential agent: emits {:count 0}, then per input adds the signal `inc`
   (default 1) and emits the new count.  Pure logic over `in` and `out`."
  (await/go*
    '[(let [ic (await/cursor in)]
        (await/>! out (assoc {} :count 0))
        (do (def lp
              (fn [n]
                (let [x (await/<! ic)
                      n2 (+ n (get (get x :signals) :inc 1))]
                  (await/>! out (assoc {} :count n2))
                  (lp n2))))
            (lp 0)))]
    {}))


(def one-shot-program
  (await/go*
    '[(let [ic (await/cursor in)]
        (await/>! out (assoc {} :count 0))
        (let [x (await/<! ic)]
          (await/>! out (assoc {} :count 99))))]
    {}))


(defn project
  "The reference SSE projection: a Datastar-shaped patch carrying the id of
   the occurrence that rendered it."
  [occurrence model]
  (str "event: datastar-patch-elements\ndata: <div id=\"app\" data-occ=\""
       occurrence "\">" (:count model) "</div>\n\n"))


(defn begin-page
  [lineage]
  (str "<!doctype html><html><body data-root=\"" lineage "\"></body></html>"))


(defn gated
  "A medium whose appends answer `full` while `failing?` holds: an atom of a
   boolean, or of a predicate on the value appended."
  [handle failing?]
  (reify
    stream/IDaoStreamDescriptor
    (descriptor [_] (stream/descriptor handle))


    stream/IDaoStreamReader

    (cursor [_ anchor] (stream/cursor handle anchor))

    (next [_ cursor] (stream/next handle cursor))


    stream/IDaoStreamWriter

    (append!
      [_ v]
      (if (let [f @failing?] (if (fn? f) (f v) f))
        {:dao.stream/outcome :dao.stream/full}
        (stream/append! handle v)))


    stream/IDaoStreamClosable

    (close! [_] (stream/close! handle))))


(def units {:ms 1})
(def duration {:ms 100})


(defn world
  "Compose the whole of Mode 1 over in-memory media.  Options: `:program`,
   `:ingress-capacity`, `:session-capacity`, `:retire?` (default true),
   `:duration`.  Answers a map of every part."
  ([] (world {}))
  ([{:keys [program ingress-capacity session-capacity retire? duration-ms subscription-max-ms
            driver-tick-capacity]
     :or {program counter-program ingress-capacity 64 session-capacity 64
          retire? true duration-ms 100 subscription-max-ms 1000000
          driver-tick-capacity 64}}]
   (let [dur {:ms duration-ms}
         interval (dl/renewal-interval units dur {:ms (quot (* 3 duration-ms) 10)}
                                       {:ms (quot duration-ms 10)})
         failing? (atom false)
         ingress (medium ingress-capacity)
         egress (medium 256)
         raw-facts (facts-log)
         facts-medium (gated raw-facts failing?)
         lineage-lease (medium 64)
         exchange-lease (medium 64)
         begin (medium 32)
         ticks {:driver (medium driver-tick-capacity) :exchange (medium 64) :lineage (medium 64)}
         open (atom #{})
         clock (atom 0)
         drv (driver/make-driver
               {:facts facts-medium :egress egress :begin begin
                :tick (:driver ticks) :lineage-lease lineage-lease
                :exchange-lease exchange-lease :program program
                :project-fn project :begin-fn begin-page :units units
                :lineage-renewal-interval interval
                :exchange-renewal-interval interval
                :session-capacity session-capacity :lease-capacity 256
                :facts-declaration (lease/facts-declaration)})
         close-exchange (fn [id]
                          (when (contains? @open id)
                            (swap! open disj id)
                            (stream/append! ingress {:http/exchange id
                                                     :http/event :http/closed})))
         xl (lease/make-exchange-lease
              {:ingress ingress :egress egress :lease-medium exchange-lease
               :tick (:exchange ticks) :open-exchanges (fn [] @open)
               :close-exchange close-exchange :units units :duration dur
               :cadence {:ms 10} :tolerance {:ms 0} :capacity 256
               :subscription-max {:ms subscription-max-ms}})
         ll (lease/make-lineage-lease
              {:facts raw-facts :lease-medium lineage-lease :tick (:lineage ticks)
               :retire! (fn [s] (when retire? (driver/retire! drv s)))
               :units units :duration dur :cadence {:ms 10} :tolerance {:ms 0}
               :facts-declaration (lease/facts-declaration)})
         rt (router/make-router
              {:ingress ingress :facts raw-facts :lease lineage-lease :begin begin
               :co-located? true :facts-declaration (lease/facts-declaration)
               :session-medium (fn [s] (driver/session-medium drv s))})
         w {:ingress ingress :egress egress :facts raw-facts :failing? failing?
            :lineage-lease lineage-lease :exchange-lease exchange-lease
            :begin begin :ticks ticks :open open :clock clock :driver drv
            :xl xl :ll ll :router rt :next-exchange (atom 0)
            :egress-cursor (atom (lease/oldest-cursor egress))
            :interval interval}]
     (assoc w :close-exchange close-exchange))))


(defn boundary-step!
  "The fake boundary's egress interpretation: an `:http/end` for an open
   exchange completes it and deposits `:http/ended`."
  [{:keys [egress egress-cursor open ingress]}]
  (let [r (lease/drain egress @egress-cursor)]
    (reset! egress-cursor (:cursor r))
    (doseq [v (:values r)
            :when (and (= :http/end (:http/effect v)) (contains? @open (:http/exchange v)))]
      (swap! open disj (:http/exchange v))
      (stream/append! ingress {:http/exchange (:http/exchange v) :http/event :http/ended}))))


(defn pump!
  "Every stepped part once, three rounds so each hand-off lands."
  [w]
  (dotimes [_ 3]
    (lease/exchange-lease-step! (:xl w))
    (lease/lineage-lease-step! (:ll w))
    (router/router-step! (:router w))
    (driver/driver-step! (:driver w))
    (boundary-step! w)))


(defn advance!
  "Move every tick stream forward by `ms` and pump."
  [w ms]
  (let [now (swap! (:clock w) + ms)]
    (doseq [t (vals (:ticks w))]
      (stream/append! t (dl/tick {:ms now})))
    (pump! w)))


(defn start!
  "The first tick, so the judges have a *now*."
  [w]
  (advance! w 10)
  w)


(defn call!
  "Deposit one request the way the boundary would; answers the exchange id."
  ([w method path] (call! w method path nil))
  ([w method path body]
   (let [id [0 (swap! (:next-exchange w) inc)]]
     (swap! (:open w) conj id)
     (stream/append! (:ingress w)
                     (cond-> {:http/exchange id :http/event :http/request
                              :http/method method :http/path path :http/headers {}}
                       body (assoc :http/body (.getBytes ^String body "UTF-8"))))
     id)))


(defn effects
  [w x]
  (filterv #(= x (:http/exchange %)) (values (:egress w))))


(defn sse-text
  [w x]
  (apply str (keep :http/body (effects w x))))


(defn occ-of
  [text]
  (second (re-find #"data-occ=\"([^\"]+)\"" text)))


(defn begin-lineage!
  "GET / and answer the new lineage's id."
  [w]
  (let [x (call! w :get "/")]
    (pump! w)
    (let [s (second (re-find #"data-root=\"([^\"]+)\"" (sse-text w x)))]
      (pump! w)
      s)))


(defn input!
  "POST /k/<token>; answers the exchange id after pumping."
  ([w token] (input! w token nil))
  ([w token body]
   (let [x (call! w :post (str "/k/" token) body)]
     (pump! w)
     x)))


(defn consumption-facts
  [w]
  (filterv #(= :consumption (facts/fact-kind %)) (values (:facts w))))


;; -----------------------------------------------------------------------------
;; Clause: a request with no token begins a lineage
;; -----------------------------------------------------------------------------

(deftest request-with-no-token-begins-a-lineage
  (let [w (start! (world))
        x (call! w :get "/")]
    (pump! w)
    (let [s (second (re-find #"data-root=\"([^\"]+)\"" (sse-text w x)))
          fx (effects w x)]
      (is (some? s))
      (is (= [:http/head :http/chunk :http/end] (mapv :http/effect fx)))
      (is (= "text/html" (get-in (first fx) [:http/headers "content-type"])))
      (is (str/starts-with? (sse-text w x) "<!doctype html>") "a complete document")
      (is (= [(facts/root s)] (filterv #(= :root (facts/fact-kind %)) (values (:facts w)))))
      (is (= #{s} (driver/sessions (:driver w))))
      (is (= {:occurrence s :model {:count 0}} (driver/latest (:driver w) s))))))


;; -----------------------------------------------------------------------------
;; Clause: agent receives input, answers head/chunks/end, parks, resumes
;; -----------------------------------------------------------------------------

(deftest agent-answers-parks-and-resumes-on-the-next-input
  (let [w (start! (world))
        s (begin-lineage! w)
        x1 (input! w s "{\"inc\":5}")
        fx1 (effects w x1)
        o1 (occ-of (sse-text w x1))
        x2 (input! w o1 "{\"inc\":2}")
        o2 (occ-of (sse-text w x2))]
    (is (= [:http/head :http/chunk :http/end] (mapv :http/effect fx1)))
    (is (= "text/event-stream" (get-in (first fx1) [:http/headers "content-type"])))
    (is (str/includes? (sse-text w x1) ">5<"))
    (is (str/includes? (sse-text w x2) ">7<") "the second answer reflects the first input")
    (is (not= s o1))
    (is (not= o1 o2))
    (is (= [(facts/consumption s o1 s) (facts/consumption s o2 o1)] (consumption-facts w)))
    (testing "the completed exchanges were ended by the boundary"
      (is (empty? @(:open w))))))


;; -----------------------------------------------------------------------------
;; Clause: subscribe consumes nothing; re-subscribe after inputs is current
;; -----------------------------------------------------------------------------

(deftest subscribe-consumes-nothing-and-resubscribe-gets-the-current-projection
  (let [w (start! (world))
        s (begin-lineage! w)
        subscribe! (fn [token]
                     (let [x (call! w :get (str "/s/" token))] (pump! w) x))
        sub0 (subscribe! s)
        o1 (occ-of (sse-text w (input! w s)))
        o2 (occ-of (sse-text w (input! w o1)))
        o3 (occ-of (sse-text w (input! w o2)))
        facts-before (count (consumption-facts w))
        sub1 (subscribe! s)]
    (is (= 3 facts-before))
    (is (= facts-before (count (consumption-facts w))) "a subscribe appends no fact")
    (is (= o3 (occ-of (sse-text w sub1))) "resolved to the current occurrence by the original URL")
    (is (str/includes? (sse-text w sub1) ">3<"))
    (is (= [:http/head :http/chunk] (mapv :http/effect (effects w sub1)))
        "a subscription exchange does not end")
    (testing "the first subscription was patched by every later input"
      (is (= [s o1 o2 o3] (mapv occ-of (keep :http/body (filter :http/body (effects w sub0)))))))
    (is (= #{sub0 sub1} (driver/subscriptions (:driver w) s)))))


;; -----------------------------------------------------------------------------
;; Clause: stale token is re-projected
;; -----------------------------------------------------------------------------

(deftest stale-token-is-reprojected-and_runs-nothing
  (let [w (start! (world))
        s (begin-lineage! w)
        o1 (occ-of (sse-text w (input! w s "{\"inc\":4}")))
        facts-before (count (consumption-facts w))
        stale (input! w s "{\"inc\":4}")]
    (is (= facts-before (count (consumption-facts w))))
    (is (= [:http/head :http/chunk :http/end] (mapv :http/effect (effects w stale))))
    (is (= o1 (occ-of (sse-text w stale))) "the projection of the current occurrence")
    (is (str/includes? (sse-text w stale) ">4<") "the continuation did not run again")))


;; -----------------------------------------------------------------------------
;; Fact before UI
;; -----------------------------------------------------------------------------

(deftest no-ui-with-the-successor-id-before-its-fact-is-accepted
  (let [w (start! (world))
        s (begin-lineage! w)
        _ (reset! (:failing? w) true)
        x (input! w s "{\"inc\":1}")]
    (testing "the fact append answered non-ok: nothing is projected"
      (is (empty? (effects w x)))
      (is (empty? (consumption-facts w)))
      (is (= {:occurrence s :model {:count 0}} (driver/latest (:driver w) s))))
    (reset! (:failing? w) false)
    (pump! w)
    (let [[fact] (consumption-facts w)
          successor (:dao.star/occurrence fact)]
      (is (some? fact))
      (is (= successor (occ-of (sse-text w x))))
      (testing "the projection came after the accepted fact, not before"
        (is (= successor (:occurrence (driver/latest (:driver w) s))))))))


;; -----------------------------------------------------------------------------
;; Liveness: silent exchange, unreturned lineage
;; -----------------------------------------------------------------------------

(deftest silent-exchange-lapses-and-closes
  (let [w (start! (world))
        x (call! w :get "/unrouted")]
    (pump! w)
    (is (contains? @(:open w) x))
    (testing "within the duration nothing happens"
      (advance! w 50)
      (is (contains? @(:open w) x)))
    (dotimes [_ 8] (advance! w 10))
    (is (not (contains? @(:open w) x)) "reclaim closed it")
    (is (some #(= {:http/exchange x :http/event :http/closed} %) (values (:ingress w))))
    (is (some #(and (= :dao.lease/lapsed (:dao.lease/status %))
                    (= :silence (:dao.lease/cause %)))
              (values (:exchange-lease w))))))


(deftest unreturned-lineage-lapses-record-retired-facts-remain
  (let [w (start! (world))
        s (begin-lineage! w)]
    (is (= #{s} (driver/sessions (:driver w))))
    (dotimes [_ 14] (advance! w 10))
    (is (empty? (driver/sessions (:driver w))) "the parked record is retired")
    (is (= [(facts/root s)] (filterv #(= :root (facts/fact-kind %)) (values (:facts w))))
        "the facts remain")
    (is (some #(= :dao.lease/lapsed (:dao.lease/status %)) (values (:lineage-lease w))))
    (testing "a token of the lapsed lineage is answered as begin"
      (let [x (input! w s)]
        (is (= "text/html" (get-in (first (effects w x)) [:http/headers "content-type"])))))))


(deftest a-driver-at-its-bound-consumes-nothing-and-begins-instead
  (let [w (start! (world))
        s (begin-lineage! w)]
    ;; Only the driver's clock moves: its holder reaches the bound while the
    ;; lineage judge has not yet lapsed the lease, so the router still says input.
    (stream/append! (get-in w [:ticks :driver]) (dl/tick {:ms 150}))
    (let [x (input! w s "{\"inc\":3}")]
      (is (empty? (consumption-facts w)) "nothing consumed past the bound")
      (is (= "text/html" (get-in (first (effects w x)) [:http/headers "content-type"]))
          "handed to the root program as begin")
      (is (= 2 (count (driver/sessions (:driver w))))))))


(deftest the-program-halting-ends-the-lineage-and-releases
  (let [w (start! (world {:program one-shot-program}))
        s (begin-lineage! w)
        x (input! w s)]
    (is (str/includes? (sse-text w x) ">99<"))
    (is (some #(= (facts/ended s :halted) %) (values (:facts w))))
    (is (some #(= :dao.lease/released (:dao.lease/status %)) (values (:facts w))))
    (is (= {:kind :ended :lineage s}
           (facts/resolve-token (router/index (:router w)) s)))))


;; -----------------------------------------------------------------------------
;; A just-published token is never answered as begin
;; -----------------------------------------------------------------------------

(defn- chase!
  "Post immediately to the token each answer publishes, `n` times."
  [w n]
  (let [s (begin-lineage! w)]
    (loop [token s i 1]
      (if (> i n)
        token
        (let [x (input! w token "{\"inc\":1}")
              text (sse-text w x)]
          (is (str/includes? text (str ">" i "<")) (str "input " i " was consumed, not begun"))
          (recur (occ-of text) (inc i)))))))


(deftest just-published-token-is-never-begin
  (let [w (start! (world))]
    (chase! w 25)
    (is (= 25 (count (consumption-facts w))))
    (is (= 1 (count (driver/sessions (:driver w)))))))


(deftest ^:slow just-published-token-is-never-begin-stress
  (let [w (start! (world {:duration-ms 1000000 :session-capacity 1024}))]
    (chase! w 400)
    (is (= 400 (count (consumption-facts w))))
    (is (= 1 (count (driver/sessions (:driver w)))))))


;; -----------------------------------------------------------------------------
;; Ingress gap: reconciliation both ways
;; -----------------------------------------------------------------------------

(deftest after-an-ingress-gap-no-exchange-unleased-and-no-closed-exchange-leased
  (let [w (start! (world {:ingress-capacity 3}))
        ids (vec (for [_ (range 6)] (call! w :get "/unrouted")))
        x (:xl w)]
    (lease/exchange-lease-step! x)
    (is (= (set ids) @(:open w)))
    (is (= (set ids) (:leased @x)) "every open exchange is leased, the evicted ones afresh")
    (let [granted (filter #(= :dao.lease/accepted (:dao.lease/status %)) (values (:exchange-lease w)))]
      (is (= 6 (count granted))))
    (testing "a closed exchange is not leased and its lease is ended by policy"
      (swap! (:open w) disj (first ids))
      (advance! w 10)
      (is (not (contains? (:leased @x) (first ids))))
      (is (= [:policy]
             (mapv :dao.lease/cause
                   (filter #(= :dao.lease/lapsed (:dao.lease/status %))
                           (values (:exchange-lease w)))))))))


;; -----------------------------------------------------------------------------
;; The driver stops renewing
;; -----------------------------------------------------------------------------

(defn- renewals
  [w]
  (count (filter #(= :dao.lease/renewal (:dao.lease/event %)) (values (:egress w)))))


(defn- subscribed-world
  [opts]
  (let [w (start! (world opts))
        s (begin-lineage! w)
        sub (call! w :get (str "/s/" s))]
    (pump! w)
    [w s sub]))


(deftest driver-renews-a-subscription-under-half-the-duration
  (let [[w _ _] (subscribed-world {})]
    (dotimes [_ 6] (advance! w 10))
    (is (pos? (renewals w)))))


(deftest driver-stops-renewing-after-observing-the-lapse
  (let [[w s sub] (subscribed-world {:duration-ms 1000})
        lease-id (some #(when (= {:http/exchange sub} (:dao.lease/subject %))
                          (:dao.lease/lease %))
                       (values (:exchange-lease w)))]
    (dotimes [_ 30] (advance! w 10))
    (is (pos? (renewals w)) "renewing while the lease is live")
    (stream/append! (:exchange-lease w) (dl/lapsed lease-id :policy))
    (pump! w)
    (is (not (contains? (driver/subscriptions (:driver w) s) sub)) "the exchange is dropped")
    (let [before (renewals w)]
      (dotimes [_ 10] (advance! w 10))
      (is (= before (renewals w)) "no renewal appended after the lapse"))))


(deftest driver-stops-renewing-after-its-own-gap
  (let [[w s _] (subscribed-world {:duration-ms 1000 :session-capacity 2})]
    (dotimes [_ 30] (advance! w 10))
    (is (pos? (renewals w)))
    (dotimes [_ 6]
      (stream/append! (driver/session-medium (:driver w) s)
                      {:exchange [9 9] :kind :terminal}))
    (driver/driver-step! (:driver w))
    (is (empty? (driver/subscriptions (:driver w) s)) "subscriptions forgotten")
    (let [before (renewals w)]
      (dotimes [_ 10] (advance! w 10))
      (is (= before (renewals w)) "no renewal appended after the gap"))))


;; -----------------------------------------------------------------------------
;; Renewal in flight counts while live; one after retirement does not revive
;; -----------------------------------------------------------------------------

(deftest renewal-in-flight-counts-while-live-and-does-not-revive-a-retired-lease
  (let [w (start! (world))
        x (call! w :get "/unrouted")
        _ (pump! w)
        lease-id (some #(when (= {:http/exchange x} (:dao.lease/subject %))
                          (:dao.lease/lease %))
                       (values (:exchange-lease w)))
        ledger #(get-in @(:xl w) [:judge :ledger])
        lapsed #(count (filter (fn [f] (= :dao.lease/lapsed (:dao.lease/status f)))
                               (values (:exchange-lease w))))]
    (is (some? lease-id))
    (dotimes [_ 9] (advance! w 10))
    (testing "a renewal in flight at 90ms of 100 counts"
      (stream/append! (:egress w) (dl/renewal lease-id))
      (dotimes [_ 3] (advance! w 10))
      (is (contains? (ledger) lease-id) "still live past the original duration")
      (is (zero? (lapsed))))
    (dotimes [_ 14] (advance! w 10))
    (is (zero? (count (filter #{lease-id} (keys (ledger))))) "retired")
    (is (= 1 (lapsed)))
    (testing "a renewal observed after retirement does not revive it"
      (stream/append! (:egress w) (dl/renewal lease-id))
      (dotimes [_ 3] (advance! w 10))
      (is (not (contains? (ledger) lease-id)))
      (is (= 1 (lapsed))))))
