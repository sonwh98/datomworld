(ns dao.stream.rpc-ws-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.apply :as apply]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.rpc :as rpc]
            [dao.stream.rpc.ws :as ws-rpc]))


(defn- handle
  ([] (handle 16))
  ([capacity]
   (:dao.stream/handle
     (ring/create! {:dao.stream/type ring/transport-type
                    ring/capacity-key capacity}))))


(defn- cursor
  [h]
  (:dao.stream/cursor (stream/cursor h :dao.stream/oldest)))


(defn- attach-result
  ([writer] (attach-result writer "attachment-1"))
  ([writer id]
   {:dao.stream/outcome :dao.stream/ok
    :dao.stream/handle writer
    :dao.stream/attachment id}))


(defn- client
  ([writer reader] (client writer reader "attachment-1"))
  ([writer reader id]
   (ws-rpc/init-client (attach-result writer id) reader (cursor reader))))


(defn- event-envelope
  [me event]
  {:ws/attachment me :ws/event event})


(defn- payload-envelope
  [me value]
  {:ws/attachment me :ws/event :ws/payload :ws/value value})


(deftest deposited-events-translate-exactly-to-the-neutral-vocabulary
  (let [decode (ws-rpc/decoder "attachment-1")]
    (testing "resolution and lifecycle kinds"
      (is (= (rpc/lifecycle-event :dao.stream.apply/established)
             (decode (event-envelope "attachment-1" :ws/opened))))
      (is (= (rpc/lifecycle-event :dao.stream.apply/established)
             (decode (event-envelope "attachment-1" :ws/accepted))))
      (is (= (rpc/lifecycle-event :dao.stream.apply/detached)
             (decode (event-envelope "attachment-1" :ws/closed))))
      (is (= (rpc/lifecycle-event :dao.stream.apply/ended)
             (decode (event-envelope "attachment-1" :ws/ended))))
      (is (= (rpc/lifecycle-event :dao.stream.apply/not-found)
             (decode (event-envelope "attachment-1" :ws/not-found))))
      (is (= (rpc/lifecycle-event :dao.stream.apply/transport-error)
             (decode (event-envelope "attachment-1" :ws/transport-error)))))
    (testing "diagnostic kind ignores extra envelope keys"
      (is (= (rpc/lifecycle-event :dao.stream.apply/diagnostic)
             (decode {:ws/attachment "attachment-1"
                      :ws/event :ws/error
                      :ws/reason :ws/decode-failure}))))
    (testing "the accepted offer's host-local handle never crosses"
      (let [decoded (decode {:ws/attachment "attachment-1"
                             :ws/event :ws/accepted
                             :ws/handle {:dao.stream/handle (handle)
                                         :dao.stream/surface #{:writer :closable}}})]
        (is (= (rpc/lifecycle-event :dao.stream.apply/established) decoded))))))


(deftest payload-frames-unwrap-to-their-carried-apply-response
  (let [decode (ws-rpc/decoder "attachment-1")]
    (is (= (apply/success-response 7 {:answer 42})
           (decode (payload-envelope "attachment-1"
                                     (apply/success-response 7 {:answer 42})))))
    (is (= (apply/error-response 8 :dao.stream.apply/handler-error "Handler failed")
           (decode (payload-envelope "attachment-1"
                                     (apply/error-response
                                       8 :dao.stream.apply/handler-error
                                       "Handler failed")))))
    (testing "nil is an ordinary carried value, distinct from a missing key"
      (is (nil? (decode (payload-envelope "attachment-1" nil))))
      (is (= (rpc/diagnostic-event ws-rpc/malformed-envelope-code
                                   {:ws/attachment "attachment-1"
                                    :ws/event :ws/payload})
             (decode {:ws/attachment "attachment-1" :ws/event :ws/payload}))))))


(deftest malformed-frames-are-consumed-once-as-diagnostic-data
  (let [writer (handle)
        reader (handle)
        state (client writer reader)
        bad [#{"not" "a" "map"}
             {:ws/attachment "attachment-1"}
             {:ws/attachment "attachment-1" :ws/event "opened"}]]
    (doseq [envelope bad]
      (stream/append! reader envelope))
    (let [polled (rpc/poll! state 3)
          state' (:dao.stream.rpc/state polled)
          [diagnostics published] (rpc/take-diagnostics state')]
      (is (= 3 (count diagnostics)))
      (is (every? #(= ws-rpc/malformed-envelope-code (:dao.stream.rpc/code %))
                  diagnostics))
      (is (= bad (mapv #(get-in % [:dao.stream.rpc/value :dao.stream.rpc/value])
                       diagnostics)))
      (is (nil? (:terminal published)))
      (is (empty? (:completed published)))
      (is (= :dao.stream.rpc/idle
             (:dao.stream.rpc/outcome (rpc/poll! published))))
      (is (empty? (first (rpc/take-diagnostics published)))
          "each malformed frame is published exactly once"))))


(deftest unknown-current-vocabulary-events-are-forwarded-as-diagnostics
  (let [writer (handle)
        reader (handle)
        requested (:dao.stream.rpc/state
                    (rpc/request! (client writer reader) :op/echo [:x]))
        future-event {:ws/attachment "attachment-1"
                      :ws/event :ws/roaming
                      :ws/value {:hint "a vocabulary this decoder predates"}}
        _ (stream/append! reader future-event)
        polled (rpc/poll! requested)
        state (:dao.stream.rpc/state polled)
        [diagnostics published] (rpc/take-diagnostics state)]
    (is (= :dao.stream.rpc/diagnostic (:dao.stream.rpc/outcome polled)))
    (is (= [{:dao.stream.rpc/code :dao.stream.rpc/unhandled-event
             :dao.stream.rpc/value
             {:dao.stream.rpc/code :dao.stream.rpc/unhandled-event
              :dao.stream.rpc/value future-event}}]
           diagnostics))
    (is (nil? (:terminal published)))
    (is (= {0 {:op :op/echo :args [:x]}} (:outstanding published))
        "an unknown event changes no request state")
    (is (empty? (:completed published)))))


(deftest foreign-attachment-traffic-is-ignored-never-treated-as-responses
  (let [writer (handle)
        reader (handle)
        requested (:dao.stream.rpc/state
                    (rpc/request! (client writer reader) :math/add [20 22]))
        _ (stream/append! reader (event-envelope "other-attachment" :ws/opened))
        _ (stream/append! reader
                          (payload-envelope "other-attachment"
                                            (apply/success-response 0 :foreign)))
        foreign-only (rpc/poll! requested 2)]
    (is (= :dao.stream.rpc/ignored (:dao.stream.rpc/outcome foreign-only)))
    (is (= {0 {:op :math/add :args [20 22]}}
           (:outstanding (:dao.stream.rpc/state foreign-only)))
        "a foreign payload carrying our own correlation id completes nothing")
    (is (empty? (:completed (:dao.stream.rpc/state foreign-only))))
    (let [_ (stream/append! reader (event-envelope "attachment-1" :ws/opened))
          _ (stream/append! reader
                            (payload-envelope "attachment-1"
                                              (apply/success-response 0 42)))
          answered (rpc/poll! (:dao.stream.rpc/state foreign-only) 2)
          state (:dao.stream.rpc/state answered)
          [completions published] (rpc/take-completed state)]
      (is (= :dao.stream.rpc/responded (:dao.stream.rpc/outcome answered)))
      (is (= 0 (:dao.stream.rpc/id answered)))
      (is (= [{:dao.stream.rpc/id 0
               :dao.stream.rpc/op :math/add
               :dao.stream.rpc/args [20 22]
               :dao.stream.rpc/response (apply/success-response 0 42)}]
             completions))
      (is (empty? (:outstanding published))))))


(deftest a-round-trip-preserves-ids-and-publishes-completions-exactly-once
  (let [writer (handle)
        reader (handle)
        requested (:dao.stream.rpc/state
                    (rpc/request! (client writer reader) :math/add [20 22]))
        _ (stream/append! reader
                          (payload-envelope "attachment-1"
                                            (apply/success-response 0 42)))
        answered (rpc/poll! requested)
        state (:dao.stream.rpc/state answered)
        [completions published] (rpc/take-completed state)
        second-request (rpc/request! published :yin/eval ["(+ 1 2)"])
        _ (stream/append! reader
                          (payload-envelope
                            "attachment-1"
                            (apply/error-response
                              1 :dao.stream.apply/unknown-operation
                              "No handler for operation")))
        errored (rpc/poll! (:dao.stream.rpc/state second-request))]
    (is (= :dao.stream.rpc/responded (:dao.stream.rpc/outcome answered)))
    (is (= 1 (count completions)))
    (is (empty? (:completed published)))
    (is (empty? (first (rpc/take-completed published)))
        "a published completion is never republished")
    (is (= 1 (:dao.stream.rpc/id second-request))
        "ids stay monotonic after earlier completions were cleared")
    (is (= 1 (:dao.stream.rpc/id errored)))
    (is (= :dao.stream.apply/unknown-operation
           (get-in (:dao.stream.rpc/response errored)
                   [:dao.stream.apply/error :dao.stream.apply/code])))))


(deftest survivable-error-precedes-one-terminal-close-completion
  (let [writer (handle)
        reader (handle)
        requested (:dao.stream.rpc/state
                    (rpc/request! (client writer reader) :op/eval ["x"]))
        _ (stream/append! reader {:ws/attachment "attachment-1"
                                  :ws/event :ws/error
                                  :ws/reason :ws/decode-failure})
        _ (stream/append! reader (event-envelope "attachment-1" :ws/closed))
        polled (rpc/poll! requested 2)
        state (:dao.stream.rpc/state polled)
        [diagnostics diagnostic-state] (rpc/take-diagnostics state)
        [completions _] (rpc/take-completed diagnostic-state)]
    (is (= :dao.stream.rpc/lost (:dao.stream.rpc/outcome polled)))
    (is (= :dao.stream.apply/detached (:dao.stream.rpc/reason polled)))
    (is (= 1 (count diagnostics)))
    (is (= :dao.stream.rpc/transport-diagnostic
           (:dao.stream.rpc/code (first diagnostics))))
    (is (= (rpc/lifecycle-event :dao.stream.apply/diagnostic)
           (get-in diagnostics [0 :dao.stream.rpc/value
                                :dao.stream.rpc/value])))
    (is (= 1 (count completions)))
    (is (= :dao.stream.apply/detached
           (get-in completions [0 :dao.stream.rpc/reason])))
    (is (empty? (:outstanding state)))
    (is (= :dao.stream.apply/detached (:terminal state)))
    (is (= :dao.stream.rpc/terminal
           (:dao.stream.rpc/outcome (rpc/poll! state))))))


(deftest a-disclaim-resolution-is-terminal-and-not-reconnectable
  (let [writer (handle)
        reader (handle)
        requested (:dao.stream.rpc/state
                    (rpc/request! (client writer reader) :op/eval ["x"]))
        _ (stream/append! reader (event-envelope "attachment-1" :ws/not-found))
        _ (stream/append! reader (event-envelope "attachment-1" :ws/closed))
        polled (rpc/poll! requested 2)
        state (:dao.stream.rpc/state polled)
        [completions _] (rpc/take-completed state)
        duplicate ((ws-rpc/decoder "attachment-1")
                   (event-envelope "attachment-1" :ws/closed))
        duplicated (rpc/handle-event state duplicate)
        dup-state (:dao.stream.rpc/state duplicated)]
    (is (= :dao.stream.rpc/lost (:dao.stream.rpc/outcome polled)))
    (is (= :dao.stream.apply/not-found (:dao.stream.rpc/reason polled)))
    (is (= 1 (count completions)))
    (is (= :dao.stream.apply/not-found (:terminal state)))
    (is (empty? (:outstanding state)))
    (testing "a duplicate post-terminal lifecycle event is diagnostic data"
      (is (= :dao.stream.rpc/diagnostic
             (:dao.stream.rpc/outcome duplicated)))
      (is (= :dao.stream.rpc/event-after-terminal
             (get-in duplicated
                     [:dao.stream.rpc/diagnostic :dao.stream.rpc/code])))
      (is (= 1 (count (:completed dup-state)))))
    (testing "only /detached may rebind"
      (let [fresh (attach-result (handle) "attachment-2")]
        (is (= state (ws-rpc/rebind state fresh)))))))


(deftest an-ended-served-stream-is-terminal-without-reconnection
  (let [writer (handle)
        reader (handle)
        requested (:dao.stream.rpc/state
                    (rpc/request! (client writer reader) :op/eval ["x"]))
        _ (stream/append! reader (event-envelope "attachment-1" :ws/ended))
        polled (rpc/poll! requested)
        state (:dao.stream.rpc/state polled)
        [completions _] (rpc/take-completed state)]
    (is (= :dao.stream.rpc/lost (:dao.stream.rpc/outcome polled)))
    (is (= :dao.stream.apply/ended (:dao.stream.rpc/reason polled)))
    (is (= :dao.stream.apply/ended (get-in completions [0 :dao.stream.rpc/reason])))
    (is (= :dao.stream.apply/ended (:terminal state)))
    (is (= state (ws-rpc/rebind state (attach-result (handle) "attachment-2"))))))


(deftest one-terminal-event-loses-every-outstanding-request-exactly-once
  (let [writer (handle)
        reader (handle)
        initial (client writer reader)
        one (:dao.stream.rpc/state (rpc/request! initial :op/a []))
        two (:dao.stream.rpc/state (rpc/request! one :op/b []))
        _ (stream/append! reader (event-envelope "attachment-1" :ws/closed))
        polled (rpc/poll! two 1)
        state (:dao.stream.rpc/state polled)
        [completions published] (rpc/take-completed state)]
    (is (= :dao.stream.rpc/lost (:dao.stream.rpc/outcome polled)))
    (is (= 2 (count completions)))
    (is (= [0 1] (mapv :dao.stream.rpc/id completions)))
    (is (empty? (:outstanding published)))
    (is (= :dao.stream.rpc/terminal
           (:dao.stream.rpc/outcome (rpc/poll! published))))
    (is (empty? (first (rpc/take-completed published))))))


(deftest rebind-reattaches-with-a-fresh-attachment-filter
  (let [writer-a (handle)
        reader (handle)
        requested (:dao.stream.rpc/state
                    (rpc/request! (client writer-a reader "attachment-1")
                                  :op/a []))
        _ (stream/append! reader (event-envelope "attachment-1" :ws/closed))
        detached (:dao.stream.rpc/state (rpc/poll! requested))
        [lost-completions consumed] (rpc/take-completed detached)
        writer-b (handle)
        rebound (ws-rpc/rebind consumed (attach-result writer-b "attachment-2"))
        re-requested (:dao.stream.rpc/state (rpc/request! rebound :op/b []))
        _ (stream/append! reader
                          (payload-envelope "attachment-1"
                                            (apply/success-response 1 :stale)))
        _ (stream/append! reader
                          (payload-envelope "attachment-2"
                                            (apply/success-response 1 :fresh)))]
    (is (= 1 (count lost-completions)))
    (is (= "attachment-2" (:me rebound)))
    (is (= writer-b (:writer rebound)))
    (is (nil? (:terminal rebound)))
    (is (= (rpc/ignore-event)
           ((:decode rebound) (payload-envelope "attachment-1" :anything)))
        "the stale attachment no longer owns the medium")
    (let [answered (rpc/poll! re-requested 2)]
      (is (= :dao.stream.rpc/responded (:dao.stream.rpc/outcome answered)))
      (is (= :fresh
             (apply/response-ok
               (:dao.stream.rpc/response answered))))
      (is (= 1 (:dao.stream.rpc/id answered))
          "the allocator survives a rebind without reusing ids"))))


(deftest a-private-medium-decodes-unattributed-envelopes
  (let [decode (ws-rpc/decoder nil)]
    (is (= (apply/success-response 0 :v)
           (decode {:ws/event :ws/payload
                    :ws/value (apply/success-response 0 :v)})))
    (is (= (rpc/lifecycle-event :dao.stream.apply/detached)
           (decode {:ws/event :ws/closed})))
    (is (= (rpc/diagnostic-event ws-rpc/malformed-envelope-code {:ws/event nil})
           (decode {:ws/event nil})))))


(deftest init-client-consumes-the-whole-attach-result-or-refuses-it
  (let [writer (handle)
        reader (handle)
        state (client writer reader)]
    (is (= writer (:writer state)))
    (is (= "attachment-1" (:me state)))
    (is (fn? (:decode state)))
    (is (= 0 (:next-id state)))
    (is (map? (:cursor state)))
    (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! writer :x))))
    (let [refused (try
                    (ws-rpc/init-client {:dao.stream/outcome :dao.stream/transport-error}
                                        reader (cursor reader))
                    (catch #?(:cljd Object :clj Throwable :cljs js/Error :default Throwable) _
                      :refused))]
      (is (= :refused refused)))
    (testing "an ok result without the contract-required handle is a defect"
      (let [handleless (try
                         (ws-rpc/init-client {:dao.stream/outcome :dao.stream/ok}
                                             reader (cursor reader))
                         (catch #?(:cljd Object :clj Throwable :cljs js/Error :default Throwable) _
                           :refused))]
        (is (= :refused handleless))))))
