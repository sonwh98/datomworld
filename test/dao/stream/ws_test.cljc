(ns dao.stream.ws-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
    [dao.stream.link :as link]
    [dao.stream.ringbuffer]
    [dao.stream.transit :as transit]
    [dao.stream.ws]))


(defn- make-stream
  []
  (ds/open! {:type :ringbuffer, :capacity nil}))


(deftest reconnect-reopens-remote-stream-at-current-position-test
  (testing
    "reconnect keeps the inbound cursor position while reopening the remote stream"
    (let [stream (#'dao.stream.ws/make-ws-stream (make-stream))
          send-fn (fn [_] nil)]
      (#'dao.stream.ws/on-open! stream send-fn)
      (#'dao.stream.ws/on-message!
       stream
       (transit/encode (link/put-msg [{:type :repl/request, :value "first"}]
                                     0)))
      (is (= {:ok {:type :repl/request, :value "first"}, :cursor {:position 1}}
             (ds/next stream {:position 0})))
      (#'dao.stream.ws/on-close! stream)
      (is (= :end (ds/next stream {:position 1})))
      (#'dao.stream.ws/on-open! stream send-fn)
      (is (= :daostream/gap (ds/next stream {:position 0})))
      (is (= :blocked (ds/next stream {:position 1})))
      (#'dao.stream.ws/on-message!
       stream
       (transit/encode (link/put-msg [{:type :repl/request, :value "second"}]
                                     1)))
      (is (= {:ok {:type :repl/request, :value "second"}, :cursor {:position 2}}
             (ds/next stream {:position 1}))))))


(deftest on-open-flushes-local-puts-made-before-socket-open-test
  (testing
    "puts made before WebSocket open are emitted when the send function appears"
    (let [stream (#'dao.stream.ws/make-ws-stream (make-stream))
          sent (atom [])]
      (ds/put! stream {:type :repl/request, :value "early"})
      (#'dao.stream.ws/on-open! stream #(swap! sent conj (transit/decode %)))
      (is (= [:datom/sync-request :datom/put] (mapv :type @sent)))
      (is (= [{:type :repl/request, :value "early"}] (:datoms (second @sent))))
      (is (= 0 (:from-pos (second @sent)))))))


(deftest reader-waiter-on-ws-stream-fires-when-remote-put-arrives-test
  (testing
    "register-reader-waiter! on a WebSocketStream delegates to remote-stream
     and the waiter's :resume fires when an inbound :put message arrives"
    (let [stream (#'dao.stream.ws/make-ws-stream (make-stream))
          send-fn (fn [_] nil)
          woke (atom [])
          entry {:resume (fn [_ entry value]
                           (swap! woke conj
                                  {:position (:position entry), :value value}))}]
      (#'dao.stream.ws/on-open! stream send-fn)
      (ds/register-reader-waiter! stream 0 entry)
      (is (empty? @woke) "no wake before any remote put")
      (#'dao.stream.ws/on-message!
       stream
       (transit/encode (link/put-msg [{:type :repl/request, :value "hi"}] 0)))
      (is (= [{:position 0, :value {:type :repl/request, :value "hi"}}]
             @woke)))))
