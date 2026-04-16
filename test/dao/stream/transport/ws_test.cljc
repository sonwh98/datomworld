(ns dao.stream.transport.ws-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
    [dao.stream.link :as link]
    [dao.stream.transit :as transit]
    [dao.stream.transport.ringbuffer]
    [dao.stream.transport.ws]))


(defn- make-stream
  []
  (ds/open! {:type :ringbuffer :capacity nil}))


(deftest reconnect-reopens-remote-stream-at-current-position-test
  (testing "reconnect keeps the inbound cursor position while reopening the remote stream"
    (let [stream (#'dao.stream.transport.ws/make-ws-stream (make-stream))
          send-fn (fn [_] nil)]
      (#'dao.stream.transport.ws/on-open! stream send-fn)
      (#'dao.stream.transport.ws/on-message!
       stream
       (transit/encode (link/put-msg [{:type :repl/request
                                       :value "first"}]
                                     0)))

      (is (= {:ok {:type :repl/request
                   :value "first"}
              :cursor {:position 1}}
             (ds/next stream {:position 0})))

      (#'dao.stream.transport.ws/on-close! stream)

      (is (= :end
             (ds/next stream {:position 1})))

      (#'dao.stream.transport.ws/on-open! stream send-fn)

      (is (= :daostream/gap
             (ds/next stream {:position 0})))
      (is (= :blocked
             (ds/next stream {:position 1})))

      (#'dao.stream.transport.ws/on-message!
       stream
       (transit/encode (link/put-msg [{:type :repl/request
                                       :value "second"}]
                                     1)))

      (is (= {:ok {:type :repl/request
                   :value "second"}
              :cursor {:position 2}}
             (ds/next stream {:position 1}))))))
