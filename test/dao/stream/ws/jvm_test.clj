(ns dao.stream.ws.jvm-test
  "The JVM send seam: acceptance is not delivery, and failure is once.

   `send!` must return as soon as the host has taken the message and never
   join its future, or a pending send parks inside dao.stream's `append!`
   and no interpreter deadline can bound it. Every future in a chain completes
   exceptionally when its predecessor does, so a lost socket must still abort
   and report exactly once, even when the report re-enters through the
   adapter. These tests drive a reified java.net.http.WebSocket whose futures
   the test completes by hand and record host calls and adapter deposits in
   one ordered trace, so nothing here touches a network or a clock.

   One asymmetry to know: the scripted socket's `abort` only records, while a
   real `WebSocket.abort()` invokes the listener's `onError` and so deposits a
   second `:ws/error`. That extra deposit is a diagnostic the blocking client
   drops, and the terminal event stays once-only through `ws/closed!`'s own
   guard, so J5 holds either way -- but the abort-to-onError re-entry is not
   what these tests exercise."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream.ws.jvm :as jvm])
  (:import [java.net.http WebSocket]
           [java.util.concurrent CompletableFuture]))


(defn- scripted
  "A connection whose socket records into one ordered `trace` and hands back
   futures the test completes by hand. `adapter-fn` receives the trace atom
   and returns the adapter, so a test can re-enter the seam from a deposit."
  ([] (scripted nil))
  ([adapter-fn]
   (let [trace (atom [])
         futures (atom [])
         socket (reify WebSocket
                  (sendText
                    [_ data _last?]
                    (let [f (CompletableFuture.)]
                      (swap! trace conj [:send-text (str data)])
                      (swap! futures conj f)
                      f))

                  (sendClose
                    [_ code reason]
                    (let [f (CompletableFuture.)]
                      (swap! trace conj [:send-close code reason])
                      (swap! futures conj f)
                      f))

                  (abort [_] (swap! trace conj [:abort]))

                  (request [_ _n])

                  (isOutputClosed [_] false)

                  (isInputClosed [_] false)

                  (getSubprotocol [_] "")

                  (sendBinary [_ _d _l] (CompletableFuture/completedFuture nil))

                  (sendPing [_ _d] (CompletableFuture/completedFuture nil))

                  (sendPong [_ _d] (CompletableFuture/completedFuture nil)))
         connection (atom {:socket socket :future nil :close-request nil
                           :pending nil :failed? false})
         seam-box (atom nil)
         adapter (if adapter-fn
                   (adapter-fn trace seam-box)
                   {:message! (fn [m] (swap! trace conj [:message! m]))
                    :error! (fn [] (swap! trace conj [:error!]))
                    :closed! (fn [code reason]
                               (swap! trace conj [:closed! code reason]))})
         seam (jvm/client-socket connection adapter)]
     (reset! seam-box seam)
     {:trace trace :futures futures :connection connection :seam seam})))


(deftest send-returns-on-acceptance-and-serializes-behind-a-pending-future
  (testing "the submitter is never parked, and one message is in flight"
    (let [{:keys [trace futures seam]} (scripted)
          send! (:send! seam)]
      (is (nil? (send! "first"))
          "send! returns immediately rather than joining the future")
      (is (= [[:send-text "first"]] @trace))
      (is (nil? (send! "second"))
          "a second send returns immediately too")
      (is (= [[:send-text "first"]] @trace)
          "the second message is chained, not issued: java.net.http.WebSocket
           fails an overlapping sendText")
      (.complete ^CompletableFuture (first @futures) nil)
      (is (= [[:send-text "first"] [:send-text "second"]] @trace)
          "completing the first send releases the second, in submission order")
      (.complete ^CompletableFuture (second @futures) nil)
      (is (= [[:send-text "first"] [:send-text "second"]] @trace)
          "the chain is idle once both have completed"))))


(deftest a-failed-send-reports-error-then-abort-then-closed-exactly-once
  (testing "one lost socket reports once, however many sends were queued"
    (let [{:keys [trace futures seam]} (scripted)]
      ((:send! seam) "one")
      ((:send! seam) "two")
      ((:send! seam) "three")
      (is (= [[:send-text "one"]] @trace)
          "only the head was issued; the rest are chained behind it")
      (.completeExceptionally ^CompletableFuture (first @futures)
                              (ex-info "socket went away" {}))
      (is (= [[:send-text "one"]
              [:error!]
              [:abort]
              [:closed! 1006 "dao.stream/send-failed"]]
             @trace)
          "error, then abort, then the terminal event -- exactly once, though
           all three chained futures completed exceptionally, and the queued
           messages are never issued to a socket that is gone"))))


(deftest a-failed-connection-accepts-no-further-sends
  (testing "the connection stays failed rather than resuming a dead chain"
    (let [{:keys [trace futures seam]} (scripted)]
      ((:send! seam) "one")
      (.completeExceptionally ^CompletableFuture (first @futures)
                              (ex-info "gone" {}))
      (let [after @trace]
        (is (= {:dao.stream/outcome :dao.stream/closed}
               ((:send! seam) "after the failure"))
            "a later send answers closed, not the retryable full that false
             would mean: the connection is permanently gone, and a request
             retained as unsent would wait for a socket never coming back")
        (is (nil? ((:close! seam) 1000 "bye"))
            "closing something already gone is satisfied, not refused")
        (is (= after @trace)
            "and neither touches the socket or deposits anything further")))))


(deftest close-waits-its-turn-behind-a-pending-send
  (testing "a close cannot race an in-flight send into IllegalStateException"
    (let [{:keys [trace futures seam]} (scripted)]
      ((:send! seam) "in flight")
      (is (nil? ((:close! seam) 1000 "bye"))
          "close! returns immediately rather than waiting for the send")
      (is (= [[:send-text "in flight"]] @trace)
          "sendClose is chained behind the pending send")
      (.complete ^CompletableFuture (first @futures) nil)
      (is (= [[:send-text "in flight"] [:send-close 1000 "bye"]] @trace)
          "and issues once the send completes"))))


(deftest an-inline-failure-reports-without-holding-the-submission-lock
  (testing "an already-failed send runs its observer inline, off the monitor"
    (let [trace (atom [])
          holds (atom [])
          connection (atom nil)
          socket (reify WebSocket
                   (sendText
                     [_ data _last?]
                     (swap! trace conj [:send-text (str data)])
                     ;; already exceptional: whenComplete runs inline, on the
                     ;; submitting thread
                     (doto (CompletableFuture.)
                       (.completeExceptionally (ex-info "refused" {}))))

                   (sendClose
                     [_ code reason]
                     (swap! trace conj [:send-close code reason])
                     (CompletableFuture/completedFuture nil))

                   (abort [_] (swap! trace conj [:abort]))

                   (request [_ _n])

                   (isOutputClosed [_] false)

                   (isInputClosed [_] false)

                   (getSubprotocol [_] "")

                   (sendBinary [_ _d _l] (CompletableFuture/completedFuture nil))

                   (sendPing [_ _d] (CompletableFuture/completedFuture nil))

                   (sendPong [_ _d] (CompletableFuture/completedFuture nil)))
          record-hold! (fn [tag]
                         (swap! holds conj
                                [tag (Thread/holdsLock @connection)]))
          adapter {:message! (fn [m] (swap! trace conj [:message! m]))
                   :error! (fn []
                             (swap! trace conj [:error!])
                             (record-hold! :error!))
                   :closed! (fn [code reason]
                              (swap! trace conj [:closed! code reason])
                              (record-hold! :closed!))}]
      (reset! connection (atom {:socket socket :future nil :close-request nil
                                :pending nil :failed? false}))
      (let [seam (jvm/client-socket @connection adapter)]
        ((:send! seam) "doomed")
        (is (= [[:send-text "doomed"]
                [:error!]
                [:abort]
                [:closed! 1006 "dao.stream/send-failed"]]
               @trace)
            "the inline failure reports in order")
        (is (= [[:error! false] [:closed! false]] @holds)
            "and does so without holding the submission monitor, so other
             submitters are not blocked across the adapter's deposits")))))


(deftest teardown-that-re-enters-through-the-adapter-reports-once
  (testing "an adapter whose error deposit closes the seam cannot recurse"
    (let [{:keys [trace futures seam]}
          (scripted
            (fn [trace seam-box]
              {:message! (fn [m] (swap! trace conj [:message! m]))
               ;; ws/deposit! invokes the socket's close function when a
               ;; deposit itself fails; model that re-entry directly.
               :error! (fn []
                         (swap! trace conj [:error!])
                         ((:close! @seam-box) 1006 "teardown"))
               :closed! (fn [code reason]
                          (swap! trace conj [:closed! code reason]))}))]
      ((:send! seam) "one")
      (.completeExceptionally ^CompletableFuture (first @futures)
                              (ex-info "gone" {}))
      (is (= [[:send-text "one"]
              [:error!]
              [:abort]
              [:closed! 1006 "dao.stream/send-failed"]]
             @trace)
          "the re-entrant close finds the connection already failed, so it
           issues no sendClose and triggers no second report"))))


(deftest before-open-send-answers-full
  (testing "with no socket installed the seam answers false, which is full"
    (let [connection (atom {:socket nil :future nil :close-request nil
                            :pending nil :failed? false})
          trace (atom [])
          seam (jvm/client-socket
                 connection
                 {:message! (fn [m] (swap! trace conj [:message! m]))
                  :error! (fn [] (swap! trace conj [:error!]))
                  :closed! (fn [c r] (swap! trace conj [:closed! c r]))})]
      (is (false? ((:send! seam) "too early")))
      (is (nil? ((:close! seam) 1000 "bye"))
          "a close before open is retained as a close-request")
      (is (= [1000 "bye"] (:close-request @connection)))
      (is (= [] @trace) "nothing is deposited"))))
