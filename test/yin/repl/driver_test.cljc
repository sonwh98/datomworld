(ns yin.repl.driver-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.rpc :as rpc]
            [yin.repl.adapter :as adapter]
            [yin.repl.driver :as driver]
            [yin.repl.host.common :as host-common]))


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
  (mapv :yin.repl.driver/text (first (driver/take-outbox state))))


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
      (is (= ["3" "4"] (mapv :yin.repl.driver/text entries)))
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
          request (:dao.stream/value read)
          id (rpc/request-id request)]
      (is (= :dao.stream/ok (:dao.stream/outcome read)))
      (is (= :op/eval (rpc/request-op request)))
      (is (= ["(+ 1 2)"] (rpc/request-args request)))
      (is (rpc/safe-id? id))
      (is (= ["(+ 2 2)"] (:queued stepped))
          "input typed while a request is outstanding is queued, not clobbered")
      (is (= :dao.stream/blocked
             (:dao.stream/outcome (read-request fixture (:dao.stream/cursor read))))
          "only one request is in flight")

      (testing "a correlated response publishes exactly once and releases the queue"
        (stream/append! (:responses fixture) (rpc/success-answer id "3"))
        (let [stepped' (driver/repl-step stepped 1)
              read' (read-request fixture (:dao.stream/cursor read))
              [entries drained] (driver/take-outbox stepped')]
          (is (= ["3"] (mapv :yin.repl.driver/text entries)))
          (is (empty? (:queued stepped')))
          (is (= ["(+ 2 2)"] (rpc/request-args (:dao.stream/value read'))))
          (is (not= id (rpc/request-id (:dao.stream/value read')))
              "ids are allocated, never reused")
          (is (empty? (texts (driver/repl-step drained 2)))
              "a published completion is never republished"))))))


(deftest a-lost-request-is-reported-rather-than-left-pending
  (let [{:keys [state responses] :as fixture} (remote)]
    (driver/submit-line! (:input state) "(+ 1 2)")
    (let [stepped (driver/repl-step state 0)]
      (is (= :dao.stream/ok
             (:dao.stream/outcome (read-request fixture (:request-cursor fixture)))))
      (stream/close! responses)
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


(deftest the-default-host-is-ready-for-a-real-connection
  (let [state (driver/create-state)]
    (is (host-common/adapter? (:host state)))
    (is (host-common/binder? (:host state)))))


(deftest connect-without-a-url-answers-with-its-usage
  (let [state (driver/create-state)]
    (driver/submit-line! (:input state) "(connect)")
    (is (str/includes? (text-of (driver/repl-step state 0)) "daostream:ws://"))))


(defn- unminted-reflection
  "A response reader shaped like a dao.stream.remote reflection: minting the
   `newest` anchor answers the retryable transport-error until `minted?` is
   set, as it does while the server has not yet answered the cursor request.
   Once minted, it resolves `newest` against `responses` at that moment,
   exactly as the server resolves it when the request reaches it."
  [responses minted?]
  (reify stream/IDaoStreamReader
    (cursor
      [_ anchor]
      (if @minted?
        (stream/cursor responses anchor)
        {:dao.stream/outcome :dao.stream/transport-error
         :dao.stream/retry? true}))

    (next [_ c] (stream/next responses c))))


(deftest no-request-crosses-before-its-response-cursor-is-minted
  ;; The R5 flake: a request sent while `newest` was unminted was answered
  ;; at a position the later-resolved cursor lay past, so its answer was
  ;; never read.
  (let [requests (handle 8)
        responses (handle 8)
        minted? (atom false)
        state (-> (driver/create-state)
                  (driver/attach-remote
                    (adapter/state (rpc/client-state
                                     requests
                                     (unminted-reflection responses minted?)
                                     stream/anchor-newest))))]
    (driver/submit-line! (:input state) "(+ 1 2)")
    (let [stepped (driver/repl-step state 0)]
      (is (= :dao.stream/blocked
             (:dao.stream/outcome (stream/next requests (oldest requests))))
          "nothing is appended while the response cursor is an anchor")
      (is (= ["(+ 1 2)"] (:queued stepped)) "the line waits in the queue")
      (is (true? (driver/pending-write? stepped))
          "a line held for the mint keeps the cadence at the base interval")
      (testing "once the mint answers, the line is sent on that step"
        (reset! minted? true)
        (let [stepped' (driver/repl-step stepped 1)
              read (stream/next requests (oldest requests))
              id (rpc/request-id (:dao.stream/value read))]
          (is (= :dao.stream/ok (:dao.stream/outcome read)))
          (is (= ["(+ 1 2)"] (rpc/request-args (:dao.stream/value read))))
          (is (empty? (:queued stepped')))
          (testing "and its answer, appended after the mint, is read"
            (stream/append! responses (rpc/success-answer id "3"))
            (is (= ["3"] (texts (driver/repl-step stepped' 2))))))))))


(deftest pending-write?-names-every-write-the-operator-is-owed
  (testing "a fresh driver owes nothing, and neither does an idle step"
    (is (false? (driver/pending-write? (driver/create-state))))
    (is (false? (driver/pending-write? (driver/repl-step (driver/create-state) 0)))))
  (testing "a request awaiting its response holds the cadence at the base interval"
    (let [{:keys [requests request-cursor state]} (remote)]
      (driver/submit-line! (:input state) "(+ 1 2)")
      (let [stepped (driver/repl-step state 0)]
        (is (true? (driver/pending-write? stepped))
            "an outstanding request is a pending write: it must never wait out
             a backoff ceiling")
        (is (= :dao.stream/ok (:dao.stream/outcome (stream/next requests
                                                                request-cursor)))
            "the request really was sent")))))
