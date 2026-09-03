(ns yin.repl.v2-adapter-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream.v2 :as stream]
            [dao.stream.v2.apply :as apply]
            [dao.stream.v2.ringbuffer :as ring]
            [dao.stream.v2.rpc :as rpc]
            [yin.repl.v2-adapter :as adapter]))


(defn- handle
  []
  (:dao.stream/handle
    (ring/create! {:dao.stream/type ring/transport-type
                   ring/capacity-key 8})))


(defn- cursor
  [handle]
  (:dao.stream/cursor (stream/cursor handle :dao.stream/oldest)))


(defn- adapter-state
  [request-handle response-handle]
  (adapter/state
    (rpc/client-state request-handle response-handle (cursor response-handle))))


(deftest local-commands-remain-local-events
  (let [request-handle (handle)
        response-handle (handle)
        initial (adapter-state request-handle response-handle)
        submitted (adapter/submit-input initial "(help)")
        state (:yin.repl.v2.adapter/state submitted)
        [events consumed] (adapter/take-events state)]
    (is (= :yin.repl.v2.adapter/local-command
           (:yin.repl.v2.adapter/outcome submitted)))
    (is (= [{:yin.repl.v2.adapter/event :yin.repl.v2.adapter/local-command
             :yin.repl.v2.adapter/command 'help
             :yin.repl.v2.adapter/input "(help)"}]
           events))
    (is (zero? (get-in state [:yin.repl.v2.adapter/rpc :next-id])))
    (is (= :dao.stream/blocked
           (:dao.stream/outcome (stream/next request-handle (cursor request-handle)))))
    (is (empty? (:yin.repl.v2.adapter/events consumed)))))


(deftest telemetry-is-rejected-rather-than-evaluated-anywhere
  ;; The v2 slice has no (telemetry) command in any form, so the adapter names
  ;; the rejection instead of running it locally or sending it to the remote.
  (let [request-handle (handle)
        response-handle (handle)
        submitted (adapter/submit-input (adapter-state request-handle response-handle)
                                        "(telemetry)")
        state (:yin.repl.v2.adapter/state submitted)
        [events _] (adapter/take-events state)]
    (is (= :yin.repl.v2.adapter/rejected-command
           (:yin.repl.v2.adapter/outcome submitted)))
    (is (= [{:yin.repl.v2.adapter/event :yin.repl.v2.adapter/rejected-command
             :yin.repl.v2.adapter/command 'telemetry
             :yin.repl.v2.adapter/input "(telemetry)"}]
           events))
    (is (nil? (adapter/local-command "(telemetry)")))
    (is (zero? (get-in state [:yin.repl.v2.adapter/rpc :next-id])))
    (is (= :dao.stream/blocked
           (:dao.stream/outcome (stream/next request-handle (cursor request-handle)))))))


(deftest command-sniffing-never-evaluates-its-input
  ;; Reading a line to look for a shell command must not run it.  Anything the
  ;; non-evaluating reader rejects is ordinary remote source.
  ;; Under an evaluating reader this string *becomes* the (help) command; a
  ;; non-evaluating reader simply fails and defers the line to the remote.
  (is (nil? (adapter/local-command "#=(clojure.core/list (quote help))")))
  (is (nil? (adapter/local-command "(+ 1 2)")))
  (is (= 'help (adapter/local-command "(help)"))))


(deftest ordinary-input-becomes-a-correlated-v2-eval-request
  (let [request-handle (handle)
        response-handle (handle)
        submitted (adapter/submit-input (adapter-state request-handle response-handle)
                                        "(+ 20 22)")
        state (:yin.repl.v2.adapter/state submitted)
        request-value (:dao.stream/value
                        (stream/next request-handle (cursor request-handle)))]
    (is (= :yin.repl.v2.adapter/requested
           (:yin.repl.v2.adapter/outcome submitted)))
    (is (= (apply/request 0 :op/eval ["(+ 20 22)"])
           request-value))
    (is (= {:op :op/eval :args ["(+ 20 22)"]}
           (get-in state [:yin.repl.v2.adapter/rpc :outstanding 0])))))


(deftest polling-publishes-each-correlated-response-once
  (let [request-handle (handle)
        response-handle (handle)
        submitted (adapter/submit-input (adapter-state request-handle response-handle)
                                        "(+ 20 22)")
        _ (stream/append! response-handle (apply/success-response 0 "42"))
        polled (adapter/poll-responses (:yin.repl.v2.adapter/state submitted))
        state (:yin.repl.v2.adapter/state polled)
        [events consumed] (adapter/take-events state)]
    (is (= :yin.repl.v2.adapter/responded
           (:yin.repl.v2.adapter/outcome polled)))
    (is (= [{:yin.repl.v2.adapter/event :yin.repl.v2.adapter/response
             :yin.repl.v2.adapter/id 0
             :yin.repl.v2.adapter/op :op/eval
             :yin.repl.v2.adapter/args ["(+ 20 22)"]
             :yin.repl.v2.adapter/value "42"}]
           events))
    (is (empty? (get-in state [:yin.repl.v2.adapter/rpc :completed])))
    (is (empty? (:yin.repl.v2.adapter/events consumed)))
    (is (= :yin.repl.v2.adapter/idle
           (:yin.repl.v2.adapter/outcome (adapter/poll-responses consumed))))))


(deftest remote-errors-and-losses-stay-correlated-data
  (testing "an application error remains a response event rather than an exception"
    (let [request-handle (handle)
          response-handle (handle)
          submitted (adapter/submit-input (adapter-state request-handle response-handle) "bad")
          error (apply/error-response 0 :yin/eval-error "Invalid input")
          _ (stream/append! response-handle error)
          polled (adapter/poll-responses (:yin.repl.v2.adapter/state submitted))
          [events _] (adapter/take-events (:yin.repl.v2.adapter/state polled))]
      (is (= (apply/response-error error)
             (:yin.repl.v2.adapter/error (first events))))))
  (testing "a detached binding emits one loss event for an outstanding input"
    (let [request-handle (handle)
          response-handle (handle)
          submitted (adapter/submit-input (adapter-state request-handle response-handle) "x")
          detached (rpc/handle-event (get-in submitted [:yin.repl.v2.adapter/state
                                                        :yin.repl.v2.adapter/rpc])
                                     (rpc/lifecycle-event :dao.stream.v2.apply/detached))
          state (assoc (:yin.repl.v2.adapter/state submitted)
                       :yin.repl.v2.adapter/rpc (:dao.stream.v2.rpc/state detached))
          published (adapter/publish-completions state)
          [events _] (adapter/take-events (:yin.repl.v2.adapter/state published))]
      (is (= [{:yin.repl.v2.adapter/event :yin.repl.v2.adapter/lost
               :yin.repl.v2.adapter/id 0
               :yin.repl.v2.adapter/op :op/eval
               :yin.repl.v2.adapter/args ["x"]
               :yin.repl.v2.adapter/reason :dao.stream.v2.apply/detached}]
             events)))))
