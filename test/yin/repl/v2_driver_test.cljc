(ns yin.repl.v2-driver-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.stream.v2 :as stream]
            [dao.stream.v2.apply :as apply]
            [dao.stream.v2.ringbuffer :as ring]
            [dao.stream.v2.rpc :as rpc]
            [yin.repl.v2-adapter :as adapter]
            [yin.repl.v2.driver :as driver]))


(defn- handle
  [capacity]
  (:dao.stream/handle
    (ring/create! {:dao.stream/type ring/transport-type
                   ring/capacity-key capacity})))


(defn- oldest
  [h]
  (:dao.stream/cursor (stream/cursor h :dao.stream/oldest)))


(defn- texts
  [state]
  (mapv :yin.repl.v2.driver/text (first (driver/take-outbox state))))


(defn- text-of
  [state]
  (str/join "\n" (texts state)))


(defn- remote
  "A driver connected to hand-built request and response media.  No socket, no
   scheduler, no callback: the test plays the server between steps."
  []
  (let [requests (handle 8)
        responses (handle 8)
        state (-> (driver/create-state)
                  (driver/attach-remote
                    (adapter/state (rpc/client-state requests responses
                                                     (oldest responses)))))]
    {:requests requests
     :responses responses
     :request-cursor (oldest requests)
     :state state}))


(defn- read-request
  [{:keys [requests]} cursor]
  (stream/next requests cursor))


(deftest a-line-producer-only-appends
  (let [state (driver/create-state)]
    (driver/submit-line! (:input state) "(+ 1 2)")
    (is (empty? (texts state)) "appending a line evaluates nothing")
    (let [stepped (driver/repl-step state 0)]
      (is (= ["3"] (texts stepped)))
      (is (= 0 (:last-tick stepped))))))


(deftest lines-are-evaluated-in-order-and-published-once
  (let [state (driver/create-state)]
    (driver/submit-line! (:input state) "(+ 1 2)")
    (driver/submit-line! (:input state) "(+ 2 2)")
    (let [stepped (driver/repl-step state 0)
          [entries drained] (driver/take-outbox stepped)]
      (is (= ["3" "4"] (mapv :yin.repl.v2.driver/text entries)))
      (is (empty? (first (driver/take-outbox drained))))
      (is (empty? (texts (driver/repl-step drained 1)))
          "a later step must not republish an earlier result"))))


(deftest blank-lines-produce-no-output
  (let [state (driver/create-state)]
    (driver/submit-line! (:input state) "   ")
    (is (empty? (texts (driver/repl-step state 0))))))


(deftest quit-stops-the-driver
  (let [state (driver/create-state)]
    (driver/submit-line! (:input state) "(quit)")
    (driver/submit-line! (:input state) "(+ 1 2)")
    (let [stepped (driver/repl-step state 0)]
      (is (false? (:running? stepped)))
      (is (= ["Bye"] (texts stepped))
          "input after a quit is not evaluated"))))


(deftest the-input-drain-is-total-over-next
  (let [input (handle 2)
        state (driver/create-state {:input input :input-cursor (oldest input)})]
    (doseq [line ["(+ 1 1)" "(+ 2 2)" "(+ 3 3)" "(+ 4 4)"]]
      (driver/submit-line! input line))
    (let [stepped (driver/repl-step state 0)
          entries (texts stepped)]
      (is (str/includes? (first entries) "input lost"))
      (is (= ["6" "8"] (vec (rest entries)))
          "evicted lines are never evaluated")
      (is (= :dao.stream/blocked (:input-ledger stepped))))))


(deftest ordinary-source-is-forwarded-while-one-request-is-outstanding
  (let [{:keys [state] :as fixture} (remote)]
    (driver/submit-line! (:input state) "(+ 1 2)")
    (driver/submit-line! (:input state) "(+ 2 2)")
    (let [stepped (driver/repl-step state 0)
          read (read-request fixture (:request-cursor fixture))
          request (:dao.stream/value read)]
      (is (= :dao.stream/ok (:dao.stream/outcome read)))
      (is (= :op/eval (apply/request-op request)))
      (is (= ["(+ 1 2)"] (apply/request-args request)))
      (is (= 0 (apply/request-id request)))
      (is (= ["(+ 2 2)"] (:queued stepped))
          "input typed while a request is outstanding is queued, not clobbered")
      (is (= :dao.stream/blocked
             (:dao.stream/outcome (read-request fixture (:dao.stream/cursor read))))
          "only one request is in flight")

      (testing "a correlated response publishes exactly once and releases the queue"
        (stream/append! (:responses fixture) (apply/success-response 0 "3"))
        (let [stepped' (driver/repl-step stepped 1)
              read' (read-request fixture (:dao.stream/cursor read))
              [entries drained] (driver/take-outbox stepped')]
          (is (= ["3"] (mapv :yin.repl.v2.driver/text entries)))
          (is (empty? (:queued stepped')))
          (is (= ["(+ 2 2)"] (apply/request-args (:dao.stream/value read'))))
          (is (= 1 (apply/request-id (:dao.stream/value read')))
              "ids are allocated, never reused")
          (is (empty? (texts (driver/repl-step drained 2)))
              "a published completion is never republished"))))))


(deftest a-lost-request-is-reported-rather-than-left-pending
  (let [{:keys [state responses] :as fixture} (remote)]
    (driver/submit-line! (:input state) "(+ 1 2)")
    (let [stepped (driver/repl-step state 0)]
      (is (= :dao.stream/ok
             (:dao.stream/outcome (read-request fixture (:request-cursor fixture)))))
      (stream/append! responses :dao.stream.v2.apply/detached)
      (let [stepped' (driver/repl-step stepped 1)]
        (is (str/includes? (text-of stepped') "lost"))
        (is (nil? (:outstanding stepped')))))))


(deftest local-commands-bypass-the-remote-queue
  (let [{:keys [state] :as fixture} (remote)]
    (driver/submit-line! (:input state) "(+ 1 2)")
    (let [stepped (driver/repl-step state 0)
          _ (driver/submit-line! (:input stepped) "(help)")
          stepped' (driver/repl-step stepped 1)
          text (text-of stepped')]
      (is (str/includes? text "Commands:"))
      (is (empty? (:queued stepped'))
          "a stuck remote request must not trap the operator")
      (is (= :dao.stream/blocked
             (:dao.stream/outcome
               (read-request fixture
                             (:dao.stream/cursor
                               (read-request fixture (:request-cursor fixture))))))
          "a local command sends nothing to the remote"))))


(deftest disconnect-detaches-without-a-socket
  (let [{:keys [state]} (remote)]
    (driver/submit-line! (:input state) "(disconnect)")
    (let [stepped (driver/repl-step state 0)]
      (is (nil? (:adapter stepped)))
      (is (str/includes? (text-of stepped) "Disconnected"))))
  (testing "disconnecting when local says so"
    (let [state (driver/create-state)]
      (driver/submit-line! (:input state) "(disconnect)")
      (is (str/includes? (text-of (driver/repl-step state 0))
                         "Not connected")))))


(deftest connect-reports-the-unmet-host-prerequisite
  (let [state (driver/create-state)]
    (is (nil? (:host state)) "no host WebSocket package is composed in this tree")
    (driver/submit-line! (:input state) "(connect \"daostream:ws://localhost:8080\")")
    (let [stepped (driver/repl-step state 0)]
      (is (nil? (:adapter stepped)))
      (is (nil? (:connection stepped)))
      (is (str/includes? (text-of stepped) "no host WebSocket package")))))


(deftest connect-without-a-url-answers-with-its-usage
  (let [state (driver/create-state)]
    (driver/submit-line! (:input state) "(connect)")
    (is (str/includes? (text-of (driver/repl-step state 0)) "daostream:ws://"))))
