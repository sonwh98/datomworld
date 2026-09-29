(ns dao.stream.rpc-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.apply :as apply2]
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


(defn- transport-error-reader
  "A reader whose every `next` answers `:dao.stream/transport-error` with the
   given `:dao.stream.remote/reason`, so poll-read's translation can be
   exercised without a real dao.stream.remote reflection."
  [reason]
  (reify stream/IDaoStreamReader
    (cursor [_ _] {:dao.stream/outcome :dao.stream/ok :dao.stream/cursor :c})

    (next
      [_ _]
      (cond-> {:dao.stream/outcome :dao.stream/transport-error}
        reason (assoc :dao.stream.remote/reason reason)))))


(defn- mint-answering-reader
  "A reader whose `cursor` answers `mint-result` every call and whose
   `next` fails the test if ever reached -- a terminal or still-pending
   mint must never fall through to a read."
  [mint-result]
  (reify stream/IDaoStreamReader
    (cursor [_ _] mint-result)

    (next [_ _] (throw (ex-info "next reached before the mint settled" {})))))


(deftest request-reserves-an-opaque-safe-id-and-does-not-loop-on-full
  (let [request-handle (handle)
        response-handle (handle)
        full-writer (reify stream/IDaoStreamWriter
                      (append! [_ _] {:dao.stream/outcome :dao.stream/full}))
        initial (rpc/client-state full-writer response-handle (cursor response-handle))
        blocked (rpc/request! initial :yin/eval ["(+ 1 2)"])
        state (:dao.stream.rpc/state blocked)
        unsent-id (get-in state [:unsent :id])
        retried (rpc/request! (assoc state :writer request-handle) :ignored/op [])]
    (is (= :dao.stream.rpc/pending-request
           (:dao.stream.rpc/outcome blocked)))
    (is (rpc/safe-id? unsent-id))
    (is (= :dao.stream.rpc/requested
           (:dao.stream.rpc/outcome retried)))
    (is (= unsent-id (:dao.stream.rpc/id retried)))
    (is (= {:op :yin/eval :args ["(+ 1 2)"]}
           (get-in (:dao.stream.rpc/state retried) [:outstanding unsent-id])))
    (is (= :yin/eval
           (rpc/request-op
             (:dao.stream/value (stream/next request-handle (cursor request-handle))))))))


(deftest poll-uses-the-returned-cursor-and-completes-a-matching-response-once
  (let [request-handle (handle)
        response-handle (handle)
        requested (rpc/request! (client request-handle response-handle) :math/add [20 22])
        id (:dao.stream.rpc/id requested)
        before-poll (:dao.stream.rpc/state requested)
        _ (stream/append! response-handle (rpc/success-answer id 42))
        polled (rpc/poll! before-poll)
        state (:dao.stream.rpc/state polled)
        [completions consumed] (rpc/take-completed state)]
    (is (= :dao.stream.rpc/responded (:dao.stream.rpc/outcome polled)))
    (is (empty? (:outstanding state)))
    (is (= [{:dao.stream.rpc/id id
             :dao.stream.rpc/op :math/add
             :dao.stream.rpc/args [20 22]
             :dao.stream.rpc/response (rpc/success-answer id 42)}]
           completions))
    (is (empty? (:completed consumed)))
    (is (= :dao.stream.rpc/idle
           (:dao.stream.rpc/outcome (rpc/poll! consumed))))))


(deftest unsolicited-and-malformed-responses-are-consumed-as-diagnostics
  (let [request-handle (handle)
        response-handle (handle)
        initial (client request-handle response-handle)
        _ (stream/append! response-handle (rpc/success-answer 999999 :value))
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


(deftest gap-loss-is-conservative-and-leaves-nothing-outstanding
  (let [request-handle (handle 1)
        response-handle (handle 1)
        requested (rpc/request! (client request-handle response-handle) :op/a [])
        state (:dao.stream.rpc/state requested)
        _ (stream/append! response-handle :evicted)
        _ (stream/append! response-handle :current)
        gapped (rpc/poll! state)
        lost-state (:dao.stream.rpc/state gapped)]
    (is (= :dao.stream.rpc/lost (:dao.stream.rpc/outcome gapped)))
    (is (= :dao.stream/gap (:dao.stream.rpc/reason gapped)))
    (is (empty? (:outstanding lost-state)))
    (is (= 1 (count (:completed lost-state))))))


(deftest reflection-transport-error-translates-into-the-client-terminal-vocabulary
  (let [request-handle (handle)
        requested (rpc/request! (client request-handle
                                        (transport-error-reader
                                          :dao.stream.remote/not-found))
                                :op/a [])
        state (:dao.stream.rpc/state requested)
        not-found (rpc/poll! state)
        detached (rpc/poll!
                   (rpc/client-state request-handle
                                     (transport-error-reader
                                       :dao.stream.remote/channel-gone)
                                     :c))
        ended (rpc/poll! (rpc/client-state request-handle
                                           (reify stream/IDaoStreamReader
                                             (cursor [_ _] {:dao.stream/outcome :dao.stream/ok})

                                             (next [_ _] {:dao.stream/outcome :dao.stream/end}))
                                           :c))
        unrecognized (rpc/poll!
                       (rpc/client-state request-handle
                                         (transport-error-reader nil)
                                         :c))]
    (is (= :dao.stream.rpc/lost (:dao.stream.rpc/outcome not-found)))
    (is (= :dao.stream.rpc/not-found
           (:dao.stream.rpc/reason not-found)))
    (is (= :dao.stream.rpc/not-found
           (:terminal (:dao.stream.rpc/state not-found))))
    (is (= :dao.stream.rpc/detached
           (:terminal (:dao.stream.rpc/state detached))))
    (is (= :dao.stream.rpc/ended
           (:terminal (:dao.stream.rpc/state ended))))
    (is (= :dao.stream.rpc/transport-error
           (:terminal (:dao.stream.rpc/state unrecognized))))))


(deftest a-retryable-mint-stays-idle-and-unterminal
  (let [request-handle (handle)
        retryable (mint-answering-reader
                    {:dao.stream/outcome :dao.stream/transport-error
                     :dao.stream/retry? true})
        polled (rpc/poll! (rpc/client-state request-handle retryable
                                            :dao.stream/newest))
        state (:dao.stream.rpc/state polled)]
    (is (= :dao.stream.rpc/idle (:dao.stream.rpc/outcome polled)))
    (is (nil? (:terminal state)))
    (is (= :dao.stream/newest (:cursor state))
        "the anchor stays unresolved for a later poll, never fabricated")))


(deftest a-reasoned-mint-failure-reaches-the-terminal-not-idle-forever
  (let [request-handle (handle)
        not-found (mint-answering-reader
                    {:dao.stream/outcome :dao.stream/transport-error
                     :dao.stream.remote/reason :dao.stream.remote/not-found})
        channel-gone (mint-answering-reader
                       {:dao.stream/outcome :dao.stream/transport-error
                        :dao.stream.remote/reason :dao.stream.remote/channel-gone})
        ended (mint-answering-reader {:dao.stream/outcome :dao.stream/end})
        polled-not-found (rpc/poll! (rpc/client-state request-handle not-found
                                                      :dao.stream/newest))
        polled-channel-gone (rpc/poll! (rpc/client-state request-handle
                                                         channel-gone
                                                         :dao.stream/newest))
        polled-ended (rpc/poll! (rpc/client-state request-handle ended
                                                  :dao.stream/newest))]
    (is (= :dao.stream.rpc/lost (:dao.stream.rpc/outcome polled-not-found)))
    (is (= :dao.stream.rpc/not-found
           (:terminal (:dao.stream.rpc/state polled-not-found))))
    (is (= :dao.stream.rpc/detached
           (:terminal (:dao.stream.rpc/state polled-channel-gone))))
    (is (= :dao.stream.rpc/ended
           (:terminal (:dao.stream.rpc/state polled-ended))))))


(defn- appended-ops
  [h]
  (loop [c (cursor h) ops []]
    (let [r (stream/next h c)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj ops (rpc/request-op (:dao.stream/value r))))
        ops))))


(defn- mintable-reader
  "A reader shaped like a remote reflection: minting answers `@mint` (a
   retryable transport-error until the test resets it), and once the mint is
   ok it resolves the anchor against `responses`."
  [responses mint]
  (reify stream/IDaoStreamReader
    (cursor
      [_ anchor]
      (if (= :ok @mint)
        (stream/cursor responses anchor)
        @mint))

    (next [_ c] (stream/next responses c))))


(def ^:private retryable-mint
  {:dao.stream/outcome :dao.stream/transport-error
   :dao.stream/retry? true})


(deftest request-on-an-unresolved-anchor-is-cursor-pending-and-changes-nothing
  (let [request-handle (handle)
        response-handle (handle)
        mint (atom retryable-mint)
        initial (rpc/client-state request-handle
                                  (mintable-reader response-handle mint)
                                  :dao.stream/newest)
        pending (rpc/request! initial :op/a [])]
    (is (true? (rpc/cursor-pending? initial)))
    (is (= :dao.stream.rpc/cursor-pending (:dao.stream.rpc/outcome pending)))
    (is (identical? initial (:dao.stream.rpc/state pending))
        "the identical state: no id allocated, nothing retained")
    (is (nil? (:dao.stream.rpc/id pending)))
    (is (= [] (appended-ops request-handle)) "nothing was appended")
    (testing "a retryable mint leaves the anchor pending"
      (let [polled (:dao.stream.rpc/state (rpc/poll! initial))]
        (is (true? (rpc/cursor-pending? polled)))
        (is (= :dao.stream.rpc/cursor-pending
               (:dao.stream.rpc/outcome (rpc/request! polled :op/a []))))
        (is (= [] (appended-ops request-handle)))))
    (testing "a successful mint permits exactly one request"
      (reset! mint :ok)
      (let [minted (:dao.stream.rpc/state (rpc/poll! initial))
            requested (rpc/request! minted :op/a [])]
        (is (false? (rpc/cursor-pending? minted)))
        (is (= :dao.stream.rpc/requested (:dao.stream.rpc/outcome requested)))
        (is (= [:op/a] (appended-ops request-handle)))
        (testing "and its answer, appended after the mint, completes it"
          (stream/append! response-handle
                          (rpc/success-answer (:dao.stream.rpc/id requested) :v))
          (is (= :dao.stream.rpc/responded
                 (:dao.stream.rpc/outcome
                   (rpc/poll! (:dao.stream.rpc/state requested))))))))))


(deftest an-unsent-envelope-is-not-retried-before-the-cursor-is-minted
  (let [request-handle (handle)
        response-handle (handle)
        full-writer (reify stream/IDaoStreamWriter
                      (append! [_ _] {:dao.stream/outcome :dao.stream/full}))
        unsent (:dao.stream.rpc/state
                 (rpc/request! (rpc/client-state full-writer response-handle
                                                 (cursor response-handle))
                               :op/a []))
        ;; A state holding an unsent envelope over an unresolved anchor: the
        ;; gate comes before the retry, so the envelope does not cross.
        gated (assoc unsent :writer request-handle :cursor :dao.stream/newest)
        result (rpc/request! gated :op/ignored [])]
    (is (true? (rpc/unsent? gated)))
    (is (= :dao.stream.rpc/cursor-pending (:dao.stream.rpc/outcome result)))
    (is (identical? gated (:dao.stream.rpc/state result)))
    (is (= [] (appended-ops request-handle)))))


(deftest a-terminal-mint-prevents-sending
  (let [request-handle (handle)
        not-found (mint-answering-reader
                    {:dao.stream/outcome :dao.stream/transport-error
                     :dao.stream.remote/reason :dao.stream.remote/not-found})
        initial (rpc/client-state request-handle not-found :dao.stream/newest)
        pending (rpc/request! initial :op/a [])
        polled (rpc/poll! (:dao.stream.rpc/state pending))
        state (:dao.stream.rpc/state polled)
        after (rpc/request! state :op/a [])]
    (is (= :dao.stream.rpc/cursor-pending (:dao.stream.rpc/outcome pending)))
    (is (= :dao.stream.rpc/lost (:dao.stream.rpc/outcome polled)))
    (is (= :dao.stream.rpc/not-found (:terminal state)))
    (is (empty? (:outstanding state)))
    (is (empty? (:completed state)) "nothing was ever sent, so nothing is lost")
    (is (= :dao.stream.rpc/terminal (:dao.stream.rpc/outcome after)))
    (is (= [] (appended-ops request-handle)) "no request ever crossed")))


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
        abandoned-id (get-in pending [:unsent :id])
        abandoned (rpc/abandon-unsent pending :yin.repl.driver/operator-disconnect)
        next-request (rpc/request! (assoc abandoned :writer request-handle)
                                   :yin/eval ["(+ 2 2)"])]
    (is (true? (rpc/unsent? pending)))
    (is (false? (rpc/unsent? abandoned)))
    (is (empty? (:outstanding abandoned)) "an unsent request was never outstanding")
    (is (= [{:dao.stream.rpc/id abandoned-id
             :dao.stream.rpc/op :yin/eval
             :dao.stream.rpc/args ["(+ 1 2)"]
             :dao.stream.rpc/reason :yin.repl.driver/operator-disconnect}]
           (:completed abandoned))
        "the loss is reported on the ordinary completion path, not swallowed")
    (is (= ["(+ 2 2)"]
           (rpc/request-args
             (:dao.stream/value (stream/next request-handle (cursor request-handle)))))
        "the next request is the caller's own, not the abandoned envelope")
    (is (not= abandoned-id (:dao.stream.rpc/id next-request))
        "the abandoned id is retired")
    (is (identical? abandoned (rpc/abandon-unsent abandoned))
        "abandoning nothing changes nothing")))


(deftest rebind-clears-only-a-detached-terminal-and-keeps-the-id-allocator
  (let [request-handle (handle)
        detached (rpc/client-state request-handle
                                   (transport-error-reader
                                     :dao.stream.remote/channel-gone)
                                   :c)
        polled (:dao.stream.rpc/state (rpc/poll! detached))
        rebound (rpc/rebind polled request-handle)
        allocated (rpc/request! rebound :op/b [])]
    (is (= :dao.stream.rpc/detached (:terminal polled)))
    (is (nil? (:terminal rebound)))
    (is (rpc/safe-id? (:dao.stream.rpc/id allocated)))
    (is (= (assoc polled :terminal :dao.stream.rpc/ended)
           (rpc/rebind (assoc polled :terminal :dao.stream.rpc/ended)
                       request-handle))
        "only a /detached terminal is reconnectable")))


(deftest wire-values-are-apply-envelopes-preserved-whole
  (let [request-handle (handle)
        response-handle (handle)
        requested (rpc/request! (client request-handle response-handle) :math/add [1 2])
        id (:dao.stream.rpc/id requested)
        emitted (:dao.stream/value (stream/next request-handle (cursor request-handle)))
        answer (assoc (apply2/success-response id 3) :trace/hop 2)
        _ (stream/append! response-handle answer)
        polled (rpc/poll! (:dao.stream.rpc/state requested))
        [completions _] (rpc/take-completed (:dao.stream.rpc/state polled))]
    (is (apply2/request? emitted))
    (is (= (apply2/request id :math/add [1 2]) emitted))
    (is (= :dao.stream.rpc/responded (:dao.stream.rpc/outcome polled)))
    (is (apply2/response? (:dao.stream.rpc/response polled)))
    (is (= answer (:dao.stream.rpc/response polled))
        "the accepted answer is the whole open envelope, extra keys included")
    (is (= answer (:dao.stream.rpc/response (first completions))))))


(deftest an-invalid-error-body-is-rejected-and-leaves-the-request-outstanding
  (testing "a valid apply error body completes the request: the
            invalid case below is rejected for its body, not its
            apply keys"
    (let [request-handle (handle)
          response-handle (handle)
          requested (rpc/request! (client request-handle response-handle) :op/a [])
          id (:dao.stream.rpc/id requested)
          _ (stream/append! response-handle
                            (apply2/error-response id :op/divide-by-zero "boom"))
          polled (rpc/poll! (:dao.stream.rpc/state requested))
          [completions _] (rpc/take-completed (:dao.stream.rpc/state polled))
          completion (first completions)]
      (is (= :dao.stream.rpc/responded (:dao.stream.rpc/outcome polled)))
      (is (= 1 (count completions)))
      (is (false? (rpc/answer-ok? (:dao.stream.rpc/response completion))))
      (is (= {:dao.stream.apply/code :op/divide-by-zero
              :dao.stream.apply/message "boom"}
             (rpc/answer-error (:dao.stream.rpc/response completion))))))
  (testing "an invalid error body is rejected: the request stays
            outstanding and the diagnostic is consumed exactly once"
    (let [request-handle (handle)
          response-handle (handle)
          requested (rpc/request! (client request-handle response-handle) :op/a [])
          id (:dao.stream.rpc/id requested)
          _ (stream/append! response-handle
                            {:dao.stream.apply/id id
                             :dao.stream.apply/error {:dao.stream.apply/code :unqualified
                                                      :dao.stream.apply/message "x"}})
          polled (rpc/poll! (:dao.stream.rpc/state requested))
          state (:dao.stream.rpc/state polled)]
      (is (= :dao.stream.rpc/diagnostic (:dao.stream.rpc/outcome polled)))
      (is (= :dao.stream.rpc/malformed-response
             (get-in polled [:dao.stream.rpc/diagnostic :dao.stream.rpc/code])))
      (is (contains? (:outstanding state) id))
      (is (empty? (:completed state)))
      (is (= :dao.stream.rpc/idle
             (:dao.stream.rpc/outcome (rpc/poll! state)))
          "the malformed answer is consumed exactly once"))))


(deftest an-unsafe-reply-id-is-a-diagnostic-consumed-once
  (let [request-handle (handle)
        response-handle (handle)
        requested (rpc/request! (client request-handle response-handle) :op/a [])
        id (:dao.stream.rpc/id requested)
        unsafe (apply2/success-response "opaque-id" :v)
        _ (stream/append! response-handle unsafe)
        polled (rpc/poll! (:dao.stream.rpc/state requested))
        state (:dao.stream.rpc/state polled)]
    (is (apply2/response? unsafe) "a valid apply response, outside rpc's id policy")
    (is (= :dao.stream.rpc/diagnostic (:dao.stream.rpc/outcome polled)))
    (is (= :dao.stream.rpc/unsafe-response-id
           (get-in polled [:dao.stream.rpc/diagnostic :dao.stream.rpc/code])))
    (is (contains? (:outstanding state) id) "the request is still outstanding")
    (is (= :dao.stream.rpc/idle (:dao.stream.rpc/outcome (rpc/poll! state)))
        "the unsafe reply is consumed exactly once")))


(deftest at-the-outstanding-limit-request-answers-backpressure
  (let [request-handle (handle)
        response-handle (handle)
        initial (rpc/client-state request-handle response-handle
                                  (cursor response-handle)
                                  {:max-outstanding 1})
        first-result (rpc/request! initial :op/a [])
        state (:dao.stream.rpc/state first-result)
        pressed (rpc/request! state :op/b [])]
    (is (= :dao.stream.rpc/requested (:dao.stream.rpc/outcome first-result)))
    (is (= :dao.stream.rpc/backpressure (:dao.stream.rpc/outcome pressed)))
    (is (identical? state (:dao.stream.rpc/state pressed))
        "no id allocated, no unsent retained, prior outstanding unaffected")
    (is (nil? (:dao.stream.rpc/id pressed)))
    (is (= 1 (count (:outstanding state))))
    (is (= [:op/a] (appended-ops request-handle))
        "nothing was appended for the refused request")
    (is (= rpc/default-max-outstanding
           (:max-outstanding (client request-handle response-handle))))
    (is (thrown? #?(:cljd Object :clj Exception :cljs :default)
          (rpc/client-state request-handle response-handle
                            (cursor response-handle) {:max-outstanding 0})))))


(deftest no-surface-and-oversize-keep-their-own-terminal-words
  (let [request-handle (handle)
        poll-with (fn [reason]
                    (:dao.stream.rpc/state
                      (rpc/poll! (rpc/client-state request-handle
                                                   (transport-error-reader reason)
                                                   :c))))
        no-surface (poll-with :dao.stream.remote/no-surface)
        oversize (poll-with :dao.stream.remote/oversize)
        not-found (poll-with :dao.stream.remote/not-found)
        detached (poll-with :dao.stream.remote/channel-gone)]
    (is (= :dao.stream.rpc/no-surface (:terminal no-surface)))
    (is (= :dao.stream.rpc/oversize (:terminal oversize)))
    (is (= :dao.stream.rpc/not-found (:terminal not-found)))
    (is (= :dao.stream.rpc/detached (:terminal detached)))
    (is (= 4 (count (set (map :terminal [no-surface oversize not-found detached]))))
        "each remote reason maps to its own rpc word")
    (doseq [terminal [no-surface oversize not-found]]
      (is (= terminal (rpc/rebind terminal request-handle))
          "terminal for the binding: rebind refuses it"))
    (is (nil? (:terminal (rpc/rebind detached request-handle)))
        "detached alone is rebindable")))
