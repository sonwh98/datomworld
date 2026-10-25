(ns dao.star.router-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.star.facts :as facts]
            [dao.star.lease :as lease]
            [dao.star.router :as router]
            [dao.stream :as stream]
            [dao.stream.memory-log :as memory-log]
            [dao.stream.ringbuffer :as ring]))


(defn- medium
  [capacity]
  (:dao.stream/handle (ring/create! {:dao.stream/type :dao.stream/ringbuffer
                                     :dao.stream.ringbuffer/capacity capacity})))


(defn- facts-log
  []
  (:dao.stream/handle (memory-log/create! {:dao.stream/type :dao.stream/memory-log})))


(defn- values
  [handle]
  (:values (lease/drain handle (lease/oldest-cursor handle))))


(defn- request
  [x path]
  {:http/exchange x :http/event :http/request :http/method :post :http/path path})


(defn- setup
  ([] (setup {}))
  ([{:keys [ingress-capacity] :or {ingress-capacity 16}}]
   (let [m {:ingress (medium ingress-capacity) :facts (facts-log) :lease (medium 16)
            :begin (medium 16) :session (medium 16)}]
     (assoc m :router (router/make-router
                        {:ingress (:ingress m) :facts (:facts m) :lease (:lease m)
                         :begin (:begin m) :co-located? true
                         :facts-declaration (lease/facts-declaration)
                         :session-medium (fn [s] (when (= s "s") (:session m)))})))))


(deftest classification-table
  (let [index (reduce facts/index-step facts/empty-index
                      [(facts/root "s") (facts/consumption "s" "o2" "s")
                       (facts/root "h") (facts/ended "h" :halted)])]
    (doseq [[path kind] [["/" :begin] ["/s/o2" :subscribe] ["/s/s" :subscribe]
                         ["/k/o2" :input] ["/k/s" :stale] ["/k/h" :begin]
                         ["/s/h" :begin] ["/k/zzz" :begin] ["/s/zzz" :begin]
                         ["/other" :unrouted] ["/k/" :unrouted]]]
      (is (= kind (:kind (router/classify index path))) path))))


(deftest co-location-is-asserted-at-assembly
  (let [{:keys [ingress facts lease begin]} (setup)
        refused (fn [config]
                  (try (router/make-router config) :assembled
                       (catch clojure.lang.ExceptionInfo e (:refused (ex-data e)))))
        base {:ingress ingress :facts facts :lease lease :begin begin
              :session-medium (constantly nil)
              :facts-declaration (lease/facts-declaration)}]
    (is (= :co-located (refused base)))
    (is (= :co-located (refused (assoc base :co-located? false))))
    (is (= :assembled (refused (assoc base :co-located? true))))))


(deftest facts-and-leases-drain-before-classification
  (testing "a request naming a token whose fact is on the medium is an input, not a begin"
    (let [{:keys [ingress facts begin session router]} (setup)]
      (stream/append! facts (facts/root "s"))
      (stream/append! ingress (request [1 1] "/k/s"))
      (router/router-step! router)
      (is (empty? (values begin)))
      (is (= :input (:kind (first (values session))))))))


(deftest unknown-is-judged-after-both-drains
  (let [{:keys [ingress facts lease begin session router]} (setup)]
    (testing "an unknown token is begin"
      (stream/append! ingress (request [1 1] "/k/zzz"))
      (router/router-step! router)
      (is (= 1 (count (values begin)))))
    (testing "a lineage lapsed on the lease medium is begin"
      (stream/append! facts (facts/root "s"))
      (stream/append! lease {:dao.lease/status :dao.lease/accepted :dao.lease/lease "L"
                             :dao.lease/subject {:dao.star/lineage "s"}
                             :dao.lease/holder {:dao.star/lineage "s"}
                             :dao.lease/duration {:ms 1}})
      (stream/append! lease {:dao.lease/status :dao.lease/lapsed :dao.lease/lease "L"
                             :dao.lease/cause :silence})
      (stream/append! ingress (request [1 2] "/k/s"))
      (router/router-step! router)
      (is (= 2 (count (values begin))))
      (is (empty? (values session))))))


(deftest terminal-events-follow-their-exchange
  (let [{:keys [ingress facts session router]} (setup)]
    (stream/append! facts (facts/root "s"))
    (stream/append! ingress (request [1 1] "/s/s"))
    (stream/append! ingress {:http/exchange [1 1] :http/event :http/closed})
    (stream/append! ingress {:http/exchange [9 9] :http/event :http/closed})
    (router/router-step! router)
    (is (= [:subscribe :terminal] (mapv :kind (values session))))
    (testing "a terminal event for an unrouted exchange is ignored"
      (is (= 2 (count (values session)))))))


(deftest a-gap-forwards-nothing-about-itself
  (let [{:keys [ingress facts session router]} (setup {:ingress-capacity 2})]
    (stream/append! facts (facts/root "s"))
    (dotimes [i 5] (stream/append! ingress (request [1 i] "/k/s")))
    (router/router-step! router)
    (is (= 1 (:gaps @router)))
    (is (= 2 (count (values session))) "only the retained window is routed")))


(deftest a-failed-forward-is-dropped-silently
  (let [full (reify
               stream/IDaoStreamWriter
               (append! [_ _] {:dao.stream/outcome :dao.stream/full}))
        ingress (medium 8) facts (facts-log)
        r (router/make-router {:ingress ingress :facts facts :lease (medium 8)
                               :begin (medium 8) :co-located? true
                               :facts-declaration (lease/facts-declaration)
                               :session-medium (constantly full)})]
    (stream/append! facts (facts/root "s"))
    (stream/append! ingress (request [1 1] "/k/s"))
    (stream/append! ingress {:http/exchange [1 1] :http/event :http/closed})
    (router/router-step! r)
    (is (empty? (:routes @r)) "an unrouted request leaves no route")))
