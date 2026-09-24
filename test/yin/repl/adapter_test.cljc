(ns yin.repl.adapter-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.apply :as apply]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.rpc :as rpc]
            [yin.repl.adapter :as adapter]))


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
        state (:yin.repl.adapter/state submitted)
        [events consumed] (adapter/take-events state)]
    (is (= :yin.repl.adapter/local-command
           (:yin.repl.adapter/outcome submitted)))
    (is (= [{:yin.repl.adapter/event :yin.repl.adapter/local-command
             :yin.repl.adapter/command 'help
             :yin.repl.adapter/input "(help)"}]
           events))
    (is (zero? (get-in state [:yin.repl.adapter/rpc :next-id])))
    (is (= :dao.stream/blocked
           (:dao.stream/outcome (stream/next request-handle (cursor request-handle)))))
    (is (empty? (:yin.repl.adapter/events consumed)))))


(deftest telemetry-is-rejected-rather-than-evaluated-anywhere
  ;; The REPL has no (telemetry) command in any form, so the adapter names
  ;; the rejection instead of running it locally or sending it to the remote.
  (let [request-handle (handle)
        response-handle (handle)
        submitted (adapter/submit-input (adapter-state request-handle response-handle)
                                        "(telemetry)")
        state (:yin.repl.adapter/state submitted)
        [events _] (adapter/take-events state)]
    (is (= :yin.repl.adapter/rejected-command
           (:yin.repl.adapter/outcome submitted)))
    (is (= [{:yin.repl.adapter/event :yin.repl.adapter/rejected-command
             :yin.repl.adapter/command 'telemetry
             :yin.repl.adapter/input "(telemetry)"}]
           events))
    (is (nil? (adapter/local-command "(telemetry)")))
    (is (zero? (get-in state [:yin.repl.adapter/rpc :next-id])))
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


(deftest ordinary-input-becomes-a-correlated-eval-request
  (let [request-handle (handle)
        response-handle (handle)
        submitted (adapter/submit-input (adapter-state request-handle response-handle)
                                        "(+ 20 22)")
        state (:yin.repl.adapter/state submitted)
        request-value (:dao.stream/value
                        (stream/next request-handle (cursor request-handle)))]
    (is (= :yin.repl.adapter/requested
           (:yin.repl.adapter/outcome submitted)))
    (is (= (apply/request 0 :op/eval ["(+ 20 22)"])
           request-value))
    (is (= {:op :op/eval :args ["(+ 20 22)"]}
           (get-in state [:yin.repl.adapter/rpc :outstanding 0])))))


(deftest polling-publishes-each-correlated-response-once
  (let [request-handle (handle)
        response-handle (handle)
        submitted (adapter/submit-input (adapter-state request-handle response-handle)
                                        "(+ 20 22)")
        _ (stream/append! response-handle (apply/success-response 0 "42"))
        polled (adapter/poll-responses (:yin.repl.adapter/state submitted))
        state (:yin.repl.adapter/state polled)
        [events consumed] (adapter/take-events state)]
    (is (= :yin.repl.adapter/responded
           (:yin.repl.adapter/outcome polled)))
    (is (= [{:yin.repl.adapter/event :yin.repl.adapter/response
             :yin.repl.adapter/id 0
             :yin.repl.adapter/op :op/eval
             :yin.repl.adapter/args ["(+ 20 22)"]
             :yin.repl.adapter/value "42"}]
           events))
    (is (empty? (get-in state [:yin.repl.adapter/rpc :completed])))
    (is (empty? (:yin.repl.adapter/events consumed)))
    (is (= :yin.repl.adapter/idle
           (:yin.repl.adapter/outcome (adapter/poll-responses consumed))))))


(deftest remote-errors-and-losses-stay-correlated-data
  (testing "an application error remains a response event rather than an exception"
    (let [request-handle (handle)
          response-handle (handle)
          submitted (adapter/submit-input (adapter-state request-handle response-handle) "bad")
          error (apply/error-response 0 :yin/eval-error "Invalid input")
          _ (stream/append! response-handle error)
          polled (adapter/poll-responses (:yin.repl.adapter/state submitted))
          [events _] (adapter/take-events (:yin.repl.adapter/state polled))]
      (is (= (apply/response-error error)
             (:yin.repl.adapter/error (first events))))))
  (testing "a detached binding emits one loss event for an outstanding input"
    (let [request-handle (handle)
          response-handle (handle)
          submitted (adapter/submit-input (adapter-state request-handle response-handle) "x")
          detached (rpc/handle-event (get-in submitted [:yin.repl.adapter/state
                                                        :yin.repl.adapter/rpc])
                                     (rpc/lifecycle-event :dao.stream.apply/detached))
          state (assoc (:yin.repl.adapter/state submitted)
                       :yin.repl.adapter/rpc (:dao.stream.rpc/state detached))
          published (adapter/publish-completions state)
          [events _] (adapter/take-events (:yin.repl.adapter/state published))]
      (is (= [{:yin.repl.adapter/event :yin.repl.adapter/lost
               :yin.repl.adapter/id 0
               :yin.repl.adapter/op :op/eval
               :yin.repl.adapter/args ["x"]
               :yin.repl.adapter/reason :dao.stream.apply/detached}]
             events)))))
