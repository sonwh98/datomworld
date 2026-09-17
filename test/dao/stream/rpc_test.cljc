(ns dao.stream.rpc-test
  (:require [clojure.test :refer [deftest is]]
            [dao.stream :as stream]
            [dao.stream.apply :as apply]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.rpc :as rpc]))


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
        state (:dao.stream.rpc/state blocked)
        retried (rpc/request! (assoc state :writer request-handle) :ignored/op [])]
    (is (= :dao.stream.rpc/pending-request
           (:dao.stream.rpc/outcome blocked)))
    (is (= 1 (:next-id state)))
    (is (= 0 (get-in state [:unsent :id])))
    (is (= :dao.stream.rpc/requested
           (:dao.stream.rpc/outcome retried)))
    (is (= 0 (:dao.stream.rpc/id retried)))
    (is (= {:op :yin/eval :args ["(+ 1 2)"]}
           (get-in (:dao.stream.rpc/state retried) [:outstanding 0])))
    (is (= :yin/eval
           (apply/request-op
             (:dao.stream/value (stream/next request-handle (cursor request-handle))))))))


(deftest poll-uses-the-returned-cursor-and-completes-a-matching-response-once
  (let [request-handle (handle)
        response-handle (handle)
        requested (rpc/request! (client request-handle response-handle) :math/add [20 22])
        before-poll (:dao.stream.rpc/state requested)
        _ (stream/append! response-handle (apply/success-response 0 42))
        polled (rpc/poll! before-poll)
        state (:dao.stream.rpc/state polled)
        [completions consumed] (rpc/take-completed state)]
    (is (= :dao.stream.rpc/responded (:dao.stream.rpc/outcome polled)))
    (is (empty? (:outstanding state)))
    (is (= [{:dao.stream.rpc/id 0
             :dao.stream.rpc/op :math/add
             :dao.stream.rpc/args [20 22]
             :dao.stream.rpc/response (apply/success-response 0 42)}]
           completions))
    (is (empty? (:completed consumed)))
    (is (= :dao.stream.rpc/idle
           (:dao.stream.rpc/outcome (rpc/poll! consumed))))))


(deftest unsolicited-and-malformed-responses-are-consumed-as-diagnostics
  (let [request-handle (handle)
        response-handle (handle)
        initial (client request-handle response-handle)
        _ (stream/append! response-handle (apply/success-response :other :value))
        unsolicited (rpc/poll! initial)
        _ (stream/append! response-handle :not-a-response)
        malformed (rpc/poll! (:dao.stream.rpc/state unsolicited))]
    (is (= :dao.stream.rpc/diagnostic
           (:dao.stream.rpc/outcome unsolicited)))
    (is (= :dao.stream.rpc/unsolicited-response
           (get-in unsolicited [:dao.stream.rpc/diagnostic :dao.stream.rpc/code])))
    (is (= :dao.stream.rpc/diagnostic
           (:dao.stream.rpc/outcome malformed)))
    (is (= :dao.stream.rpc/malformed-response
           (get-in malformed [:dao.stream.rpc/diagnostic :dao.stream.rpc/code])))))


(deftest diagnostics-publish-exactly-once-and-do-not-accumulate
  (let [request-handle (handle)
        response-handle (handle)
        initial (client request-handle response-handle)
        _ (stream/append! response-handle :not-a-response)
        _ (stream/append! response-handle :also-not-a-response)
        polled (rpc/poll! initial 2)
        state (:dao.stream.rpc/state polled)
        [diagnostics consumed] (rpc/take-diagnostics state)]
    (is (= 2 (count diagnostics)))
    (is (= [:dao.stream.rpc/malformed-response :dao.stream.rpc/malformed-response]
           (mapv :dao.stream.rpc/code diagnostics)))
    (is (empty? (:diagnostics consumed)))
    (is (empty? (first (rpc/take-diagnostics consumed))))))


(deftest gap-and-lifecycle-loss-are-conservative-and-leave-unsent-retryable
  (let [request-handle (handle 1)
        response-handle (handle 1)
        requested (rpc/request! (client request-handle response-handle) :op/a [])
        state (:dao.stream.rpc/state requested)
        _ (stream/append! response-handle :evicted)
        _ (stream/append! response-handle :current)
        gapped (rpc/poll! state)
        lost-state (:dao.stream.rpc/state gapped)
        lifecycle (rpc/handle-event requested (rpc/lifecycle-event :dao.stream.apply/detached))]
    (is (= :dao.stream.rpc/lost (:dao.stream.rpc/outcome gapped)))
    (is (= :dao.stream/gap (:dao.stream.rpc/reason gapped)))
    (is (empty? (:outstanding lost-state)))
    (is (= 1 (count (:completed lost-state))))
    (is (= :dao.stream.rpc/lost (:dao.stream.rpc/outcome lifecycle)))
    (is (= :dao.stream.apply/detached
           (:terminal (:dao.stream.rpc/state lifecycle))))))


(deftest append-terminal-outcome-completes-unsent-without-marking-it-outstanding
  (let [closed-writer (reify stream/IDaoStreamWriter
                        (append! [_ _] {:dao.stream/outcome :dao.stream/closed}))
        response-handle (handle)
        result (rpc/request! (rpc/client-state closed-writer response-handle
                                               (cursor response-handle))
                             :yin/eval ["x"])
        state (:dao.stream.rpc/state result)]
    (is (= :dao.stream.rpc/request-undeliverable
           (:dao.stream.rpc/outcome result)))
    (is (nil? (:unsent state)))
    (is (empty? (:outstanding state)))
    (is (= :dao.stream/closed
           (get-in state [:completed 0 :dao.stream.rpc/reason])))))


(deftest an-abandoned-unsent-request-is-completed-with-its-reason-and-never-resent
  (let [request-handle (handle)
        response-handle (handle)
        full-writer (reify stream/IDaoStreamWriter
                      (append! [_ _] {:dao.stream/outcome :dao.stream/full}))
        pending (:dao.stream.rpc/state
                  (rpc/request! (rpc/client-state full-writer response-handle
                                                  (cursor response-handle))
                                :yin/eval ["(+ 1 2)"]))
        abandoned (rpc/abandon-unsent pending :yin.repl.driver/operator-disconnect)
        next-request (rpc/request! (assoc abandoned :writer request-handle)
                                   :yin/eval ["(+ 2 2)"])]
    (is (true? (rpc/unsent? pending)))
    (is (false? (rpc/unsent? abandoned)))
    (is (empty? (:outstanding abandoned)) "an unsent request was never outstanding")
    (is (= [{:dao.stream.rpc/id 0
             :dao.stream.rpc/op :yin/eval
             :dao.stream.rpc/args ["(+ 1 2)"]
             :dao.stream.rpc/reason :yin.repl.driver/operator-disconnect}]
           (:completed abandoned))
        "the loss is reported on the ordinary completion path, not swallowed")
    (is (= ["(+ 2 2)"]
           (apply/request-args
             (:dao.stream/value (stream/next request-handle (cursor request-handle)))))
        "the next request is the caller's own, not the abandoned envelope")
    (is (= 1 (:dao.stream.rpc/id next-request)) "the abandoned id is retired")
    (is (identical? abandoned (rpc/abandon-unsent abandoned))
        "abandoning nothing changes nothing")))


(deftest server-step-is-the-apply-step-and-rebind-never-reuses-ids
  (let [request-handle (handle)
        response-handle (handle)
        initial (client request-handle response-handle)
        one (:dao.stream.rpc/state (rpc/request! initial :op/a []))
        detached (:dao.stream.rpc/state
                   (rpc/handle-event one
                                     (rpc/lifecycle-event :dao.stream.apply/detached)))
        rebound (rpc/rebind detached request-handle :new-attachment)
        two (rpc/request! rebound :op/b [])]
    (is (= :new-attachment (:me rebound)))
    (is (nil? (:terminal rebound)))
    (is (= 1 (:dao.stream.rpc/id two)))
    (is (= :dao.stream.apply/responded
           (:dao.stream.apply/outcome
             (rpc/serve-once! {} request-handle response-handle
                              (rpc/server-state (cursor request-handle))))))))


(deftest allocation-failure-reports-outstanding-requests-lost-before-going-terminal
  (let [request-handle (handle)
        response-handle (handle)
        requested (rpc/request! (client request-handle response-handle) :math/add [20 22])
        outstanding (:dao.stream.rpc/state requested)
        ;; Force the next allocation onto an id already in use: :outstanding
        ;; holds 0, so rewinding :next-id collides.
        collided (rpc/request! (assoc outstanding :next-id 0) :op/b [])
        state (:dao.stream.rpc/state collided)
        [completions consumed] (rpc/take-completed state)]
    (is (= :dao.stream.rpc/allocator-error
           (:dao.stream.rpc/outcome collided)))
    (is (= :dao.stream.rpc/allocator-error (:terminal state))
        "the client is terminal, as before")
    (is (empty? (:outstanding state))
        "no id is left outstanding for a poll that will never run again")
    (is (= 0 (:next-id state))
        "the failed allocation consumes no id; ids stay monotonic")
    (is (= [{:dao.stream.rpc/id 0
             :dao.stream.rpc/op :math/add
             :dao.stream.rpc/args [20 22]
             :dao.stream.rpc/reason :dao.stream.rpc/allocator-error}]
           completions)
        "the stranded request is reported lost on the ordinary completion path")
    (is (empty? (:completed consumed)))
    (is (= {:dao.stream.rpc/code :dao.stream.rpc/id-collision
            :dao.stream.rpc/value 0}
           (:dao.stream.rpc/diagnostic collided))
        "the allocation diagnostic is preserved")))


(deftest id-exhaustion-reports-outstanding-requests-lost
  (let [request-handle (handle)
        response-handle (handle)
        requested (rpc/request! (client request-handle response-handle) :op/a [])
        outstanding (:dao.stream.rpc/state requested)
        exhausted (rpc/request! (assoc outstanding :next-id (inc rpc/max-safe-id))
                                :op/b [])
        state (:dao.stream.rpc/state exhausted)]
    (is (= :dao.stream.rpc/allocator-error
           (:dao.stream.rpc/outcome exhausted)))
    (is (= :dao.stream.rpc/allocator-error (:terminal state)))
    (is (empty? (:outstanding state)))
    (is (= (inc rpc/max-safe-id) (:next-id state))
        "the failed allocation consumes no id; ids stay monotonic")
    (is (= [{:dao.stream.rpc/id 0
             :dao.stream.rpc/op :op/a
             :dao.stream.rpc/args []
             :dao.stream.rpc/reason :dao.stream.rpc/allocator-error}]
           (:completed state)))
    (is (= :dao.stream.rpc/id-exhausted
           (:dao.stream.rpc/code (:dao.stream.rpc/diagnostic exhausted))))))


(deftest allocation-failure-with-nothing-outstanding-publishes-no-completion
  (let [request-handle (handle)
        response-handle (handle)
        initial (client request-handle response-handle)
        exhausted (rpc/request! (assoc initial :next-id (inc rpc/max-safe-id)) :op/a [])
        state (:dao.stream.rpc/state exhausted)]
    (is (= :dao.stream.rpc/allocator-error
           (:dao.stream.rpc/outcome exhausted)))
    (is (= :dao.stream.rpc/allocator-error (:terminal state)))
    (is (empty? (:completed state))
        "conservative loss reports what was owed, and nothing was owed")))
