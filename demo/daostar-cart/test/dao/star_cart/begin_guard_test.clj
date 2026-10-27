(ns dao.star-cart.begin-guard-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.star-cart.begin-guard :as guard]
            [dao.star.lease :as lease]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]))


(defn- rings
  []
  (let [r (fn []
            (:dao.stream/handle
              (ring/create! {:dao.stream/type :dao.stream/ringbuffer
                             :dao.stream.ringbuffer/capacity 64})))]
    {:router-begin (r) :driver-begin (r) :egress (r)}))


(defn- drain
  [h]
  (let [vals (atom [])]
    (loop [cursor (lease/oldest-cursor h)]
      (let [r (stream/next h cursor)]
        (if (= :dao.stream/ok (:dao.stream/outcome r))
          (do (swap! vals conj (:dao.stream/value r))
              (recur (:dao.stream/cursor r)))
          @vals)))))


(defn- router-begin-msg
  [x path]
  {:exchange x :kind :begin :token nil :lineage nil
   :event {:http/exchange x :http/event :http/request :http/method :get
           :http/path path}})


(deftest stale-sse-shaped-begins-are-answered-as-expired-event-streams
  (let [{:keys [router-begin driver-begin egress]} (rings)
        g (guard/make router-begin driver-begin)]
    (stream/append! router-begin (router-begin-msg 1 "/s/gone"))
    (stream/append! router-begin (router-begin-msg 2 "/k/also-gone"))
    (guard/step! g egress "app-body")
    (let [fx (drain egress)]
      (testing "each stale exchange gets head, chunk, end"
        (is (= 6 (count fx))))
      (testing "the head is an event-stream"
        (is (= :http/head (:http/effect (nth fx 0))))
        (is (= "text/event-stream" (get-in (nth fx 0) [:http/headers "content-type"]))))
      (testing "the chunk patches the app body with the expired notice"
        (is (str/includes? (get-in (nth fx 1) [:http/body]) "Session expired"))
        (is (str/includes? (get-in (nth fx 1) [:http/body]) "id=\"app-body\""))
        (is (str/starts-with? (get-in (nth fx 1) [:http/body]) "event: datastar-patch-elements"))
        (is (str/ends-with? (get-in (nth fx 1) [:http/body]) "\n\n")))
      (testing "both exchanges end"
        (is (= :http/end (get-in (nth fx 2) [:http/effect])))
        (is (= :http/end (get-in (nth fx 5) [:http/effect])))))
    (testing "nothing was forwarded to the driver"
      (is (empty? (drain driver-begin))))))


(deftest true-begins-are-forwarded-to-the-driver
  (let [{:keys [router-begin driver-begin egress]} (rings)
        g (guard/make router-begin driver-begin)]
    (stream/append! router-begin (router-begin-msg 7 "/"))
    (guard/step! g egress "app-body")
    (let [fwd (drain driver-begin)]
      (is (= 1 (count fwd)))
      (is (= "/" (get-in (first fwd) [:event :http/path]))))
    (is (empty? (drain egress)))))


(deftest the-grid-answers-its-own-app-id
  (let [{:keys [router-begin driver-begin egress]} (rings)
        g (guard/make router-begin driver-begin)]
    (stream/append! router-begin (router-begin-msg 9 "/s/gone"))
    (guard/step! g egress "app")
    (is (str/includes? (get-in (second (drain egress)) [:http/body]) "id=\"app\""))))
