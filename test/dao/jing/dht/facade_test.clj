(ns dao.jing.dht.facade-test
  "The JVM blocking facade (docs/design/dao.jing.dht.md section 5.2): one
   driver thread is the DHT state's one step owner and the tick cadence; a
   get-bytes-fn miss waits on dao.jing.content.driver to its deadline; the
   put-bytes-fn returns the local verdict without waiting."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.dht :as dht]
            [dao.jing.dht.facade :as facade]
            [dao.jing.dht.mesh :as mesh]))


(deftest a-miss-waits-on-the-driver-and-one-thread-steps
  (let [net (mesh/mesh)
        stepping (atom #{})
        in-flight (atom {})
        peak (atom {})
        real-step dht/step
        v {:facade "remote"}
        address (jing/segment-key v)]
    ;; step entry and exit per facade, keyed by node id: a second concurrent
    ;; step on one state would raise that facade's peak above one
    (with-redefs [dht/step (fn [state budget]
                             (let [id (::dht/id state)
                                   n (get (swap! in-flight update id
                                                 (fnil inc 0))
                                          id)]
                               (swap! peak update id (fnil max 0) n)
                               (swap! stepping conj (Thread/currentThread))
                               (try (Thread/yield)
                                    (real-step state budget)
                                    (finally
                                      (swap! in-flight update id dec)))))]
      (let [b (facade/start! (mesh/join! net 2 {::dht/publish? true})
                             {:poll-interval-ms 1})
            a (facade/start! (mesh/join! net 1 {::dht/bootstrap
                                                [(mesh/contact 2)]})
                             {:poll-interval-ms 1
                              :request-timeout-ms 5000})]
        (try
          (is (= :inserted ((:put-bytes-fn b) address
                                              (jing/canonical-bytes v)))
              "the local verdict, at once")
          (is (= v (jing/get a address nil)) "a remote miss waits and finds")
          (is (= v (jing/get (:local a) address nil)) "and is cached locally")
          (finally
            (jing/close! a)
            (jing/close! b)))
        (testing "each facade's maximum concurrent step count is one"
          (is (= {(mesh/node-id 1) 1, (mesh/node-id 2) 1} @peak)))
        (testing "each state has one step owner, never the caller"
          (is (= 2 (count @stepping)))
          (is (not (contains? @stepping (Thread/currentThread)))))))))


(deftest a-miss-respects-the-driver-deadline
  (let [a (facade/start! (mesh/join! (mesh/mesh) 1
                                     {::dht/bootstrap [(mesh/contact 9)]})
                         {:poll-interval-ms 1
                          :request-timeout-ms 150})
        started (System/nanoTime)]
    (try
      (let [data (try (jing/get a (jing/segment-key [:nowhere]) nil)
                      nil
                      (catch Exception e (ex-data e)))
            waited-ms (quot (- (System/nanoTime) started) 1000000)]
        (is (= 150 (:timeout-ms data)))
        (is (< waited-ms 2000) "the driver deadline, not get-ticks"))
      (finally (jing/close! a)))))


(deftest solo-misses-answer-at-once
  (let [a (facade/start! (mesh/solo) {:poll-interval-ms 1})]
    (try
      (is (= ::none (jing/get a (jing/segment-key [:absent]) ::none)))
      (finally (jing/close! a)))))
