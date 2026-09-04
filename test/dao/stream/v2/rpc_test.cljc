(ns dao.stream.v2.rpc-test
  (:require [clojure.test :refer [deftest is]]
            [dao.stream.v2 :as stream]
            [dao.stream.v2.apply :as apply]
            [dao.stream.v2.ringbuffer :as ring]
            [dao.stream.v2.rpc :as rpc]))


(defn- handle
  ([] (handle 8))
  ([capacity]
   (:dao.stream/handle
     (ring/create! {:dao.stream/type ring/transport-type
                    ring/capacity-key capacity}))))


(defn- cursor
  [h]
  (:dao.stream/cursor (stream/cursor h :dao.stream/oldest)))


(defn- client
  [request-handle response-handle]
  (rpc/client-state request-handle response-handle (cursor response-handle)))


(deftest request-reserves-an-opaque-safe-id-and-does-not-loop-on-full
  (let [request-handle (handle)
        response-handle (handle)
        full-writer (reify stream/IDaoStreamWriter
                      (append! [_ _] {:dao.stream/outcome :dao.stream/full}))
        initial (rpc/client-state full-writer response-handle (cursor response-handle))
        blocked (rpc/request! initial :yin/eval ["(+ 1 2)"])
        state (:dao.stream.v2.rpc/state blocked)
        retried (rpc/request! (assoc state :writer request-handle) :ignored/op [])]
    (is (= :dao.stream.v2.rpc/pending-request
           (:dao.stream.v2.rpc/outcome blocked)))
    (is (= 1 (:next-id state)))
    (is (= 0 (get-in state [:unsent :id])))
    (is (= :dao.stream.v2.rpc/requested
           (:dao.stream.v2.rpc/outcome retried)))
    (is (= 0 (:dao.stream.v2.rpc/id retried)))
    (is (= {:op :yin/eval :args ["(+ 1 2)"]}
           (get-in (:dao.stream.v2.rpc/state retried) [:outstanding 0])))
    (is (= :yin/eval
           (apply/request-op
             (:dao.stream/value (stream/next request-handle (cursor request-handle))))))))


(deftest poll-uses-the-returned-cursor-and-completes-a-matching-response-once
  (let [request-handle (handle)
        response-handle (handle)
        requested (rpc/request! (client request-handle response-handle) :math/add [20 22])
        before-poll (:dao.stream.v2.rpc/state requested)
        _ (stream/append! response-handle (apply/success-response 0 42))
        polled (rpc/poll! before-poll)
        state (:dao.stream.v2.rpc/state polled)
        [completions consumed] (rpc/take-completed state)]
    (is (= :dao.stream.v2.rpc/responded (:dao.stream.v2.rpc/outcome polled)))
    (is (empty? (:outstanding state)))
    (is (= [{:dao.stream.v2.rpc/id 0
             :dao.stream.v2.rpc/op :math/add
             :dao.stream.v2.rpc/args [20 22]
             :dao.stream.v2.rpc/response (apply/success-response 0 42)}]
           completions))
    (is (empty? (:completed consumed)))
    (is (= :dao.stream.v2.rpc/idle
           (:dao.stream.v2.rpc/outcome (rpc/poll! consumed))))))


(deftest unsolicited-and-malformed-responses-are-consumed-as-diagnostics
  (let [request-handle (handle)
        response-handle (handle)
        initial (client request-handle response-handle)
        _ (stream/append! response-handle (apply/success-response :other :value))
        unsolicited (rpc/poll! initial)
        _ (stream/append! response-handle :not-a-response)
        malformed (rpc/poll! (:dao.stream.v2.rpc/state unsolicited))]
    (is (= :dao.stream.v2.rpc/diagnostic
           (:dao.stream.v2.rpc/outcome unsolicited)))
    (is (= :dao.stream.v2.rpc/unsolicited-response
           (get-in unsolicited [:dao.stream.v2.rpc/diagnostic :dao.stream.v2.rpc/code])))
    (is (= :dao.stream.v2.rpc/diagnostic
           (:dao.stream.v2.rpc/outcome malformed)))
    (is (= :dao.stream.v2.rpc/malformed-response
           (get-in malformed [:dao.stream.v2.rpc/diagnostic :dao.stream.v2.rpc/code])))))


(deftest diagnostics-publish-exactly-once-and-do-not-accumulate
  (let [request-handle (handle)
        response-handle (handle)
        initial (client request-handle response-handle)
        _ (stream/append! response-handle :not-a-response)
        _ (stream/append! response-handle :also-not-a-response)
        polled (rpc/poll! initial 2)
        state (:dao.stream.v2.rpc/state polled)
        [diagnostics consumed] (rpc/take-diagnostics state)]
    (is (= 2 (count diagnostics)))
    (is (= [:dao.stream.v2.rpc/malformed-response :dao.stream.v2.rpc/malformed-response]
           (mapv :dao.stream.v2.rpc/code diagnostics)))
    (is (empty? (:diagnostics consumed)))
    (is (empty? (first (rpc/take-diagnostics consumed))))))


(deftest gap-and-lifecycle-loss-are-conservative-and-leave-unsent-retryable
  (let [request-handle (handle 1)
        response-handle (handle 1)
        requested (rpc/request! (client request-handle response-handle) :op/a [])
        state (:dao.stream.v2.rpc/state requested)
        _ (stream/append! response-handle :evicted)
        _ (stream/append! response-handle :current)
        gapped (rpc/poll! state)
        lost-state (:dao.stream.v2.rpc/state gapped)
        lifecycle (rpc/handle-event requested (rpc/lifecycle-event :dao.stream.v2.apply/detached))]
    (is (= :dao.stream.v2.rpc/lost (:dao.stream.v2.rpc/outcome gapped)))
    (is (= :dao.stream/gap (:dao.stream.v2.rpc/reason gapped)))
    (is (empty? (:outstanding lost-state)))
    (is (= 1 (count (:completed lost-state))))
    (is (= :dao.stream.v2.rpc/lost (:dao.stream.v2.rpc/outcome lifecycle)))
    (is (= :dao.stream.v2.apply/detached
           (:terminal (:dao.stream.v2.rpc/state lifecycle))))))


(deftest append-terminal-outcome-completes-unsent-without-marking-it-outstanding
  (let [closed-writer (reify stream/IDaoStreamWriter
                        (append! [_ _] {:dao.stream/outcome :dao.stream/closed}))
        response-handle (handle)
        result (rpc/request! (rpc/client-state closed-writer response-handle
                                               (cursor response-handle))
                             :yin/eval ["x"])
        state (:dao.stream.v2.rpc/state result)]
    (is (= :dao.stream.v2.rpc/request-undeliverable
           (:dao.stream.v2.rpc/outcome result)))
    (is (nil? (:unsent state)))
    (is (empty? (:outstanding state)))
    (is (= :dao.stream/closed
           (get-in state [:completed 0 :dao.stream.v2.rpc/reason])))))


(deftest an-abandoned-unsent-request-is-completed-with-its-reason-and-never-resent
  (let [request-handle (handle)
        response-handle (handle)
        full-writer (reify stream/IDaoStreamWriter
                      (append! [_ _] {:dao.stream/outcome :dao.stream/full}))
        pending (:dao.stream.v2.rpc/state
                  (rpc/request! (rpc/client-state full-writer response-handle
                                                  (cursor response-handle))
                                :yin/eval ["(+ 1 2)"]))
        abandoned (rpc/abandon-unsent pending :yin.repl.v2.driver/operator-disconnect)
        next-request (rpc/request! (assoc abandoned :writer request-handle)
                                   :yin/eval ["(+ 2 2)"])]
    (is (true? (rpc/unsent? pending)))
    (is (false? (rpc/unsent? abandoned)))
    (is (empty? (:outstanding abandoned)) "an unsent request was never outstanding")
    (is (= [{:dao.stream.v2.rpc/id 0
             :dao.stream.v2.rpc/op :yin/eval
             :dao.stream.v2.rpc/args ["(+ 1 2)"]
             :dao.stream.v2.rpc/reason :yin.repl.v2.driver/operator-disconnect}]
           (:completed abandoned))
        "the loss is reported on the ordinary completion path, not swallowed")
    (is (= ["(+ 2 2)"]
           (apply/request-args
             (:dao.stream/value (stream/next request-handle (cursor request-handle)))))
        "the next request is the caller's own, not the abandoned envelope")
    (is (= 1 (:dao.stream.v2.rpc/id next-request)) "the abandoned id is retired")
    (is (identical? abandoned (rpc/abandon-unsent abandoned))
        "abandoning nothing changes nothing")))


(deftest server-step-is-the-apply-step-and-rebind-never-reuses-ids
  (let [request-handle (handle)
        response-handle (handle)
        initial (client request-handle response-handle)
        one (:dao.stream.v2.rpc/state (rpc/request! initial :op/a []))
        detached (:dao.stream.v2.rpc/state
                   (rpc/handle-event one
                                     (rpc/lifecycle-event :dao.stream.v2.apply/detached)))
        rebound (rpc/rebind detached request-handle :new-attachment)
        two (rpc/request! rebound :op/b [])]
    (is (= :new-attachment (:me rebound)))
    (is (nil? (:terminal rebound)))
    (is (= 1 (:dao.stream.v2.rpc/id two)))
    (is (= :dao.stream.v2.apply/responded
           (:dao.stream.v2.apply/outcome
             (rpc/serve-once! {} request-handle response-handle
                              (rpc/server-state (cursor request-handle))))))))
