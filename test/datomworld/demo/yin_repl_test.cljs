(ns datomworld.demo.yin-repl-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datomworld.demo.yin-repl :as yin-repl]))


(deftest location->repl-url-test
  (testing "http locations use ws"
    (is (= "ws://localhost:8080"
           (yin-repl/location->repl-url {:protocol "http:"
                                         :hostname "localhost"}
                                        8080))))
  (testing "https locations use wss"
    (is (= "wss://datom.world:443"
           (yin-repl/location->repl-url {:protocol "https:"
                                         :hostname "datom.world"}
                                        443)))))


(deftest queue-and-resolve-request-test
  (let [[queued-state request-id] (yin-repl/queue-request (yin-repl/initial-state)
                                                          "(+ 1 2)")
        resolved-state (yin-repl/resolve-request queued-state request-id :ok "3")]
    (is (= "browser/request/0" request-id))
    (is (= request-id (:active-request queued-state)))
    (is (= nil (:active-request resolved-state)))
    (is (= [{:id request-id
             :input "(+ 1 2)"
             :output "3"
             :status :ok}]
           (:history resolved-state)))))


(deftest fail-active-request-test
  (let [[queued-state request-id] (yin-repl/queue-request (yin-repl/initial-state)
                                                          "(help)")
        failed-state (yin-repl/fail-active-request queued-state "Connection closed")]
    (is (= nil (:active-request failed-state)))
    (is (= [{:id request-id
             :input "(help)"
             :output "Connection closed"
             :status :error}]
           (:history failed-state)))))
