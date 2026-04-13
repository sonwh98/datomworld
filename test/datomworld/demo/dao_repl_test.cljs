(ns datomworld.demo.dao-repl-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datomworld.demo.dao-repl :as dao-repl]))


(deftest location->repl-url-test
  (testing "http locations use ws"
    (is (= "ws://localhost:8080"
           (dao-repl/location->repl-url {:protocol "http:"
                                         :hostname "localhost"}
                                        8080))))
  (testing "https locations use wss"
    (is (= "wss://datom.world:443"
           (dao-repl/location->repl-url {:protocol "https:"
                                         :hostname "datom.world"}
                                        443)))))


(deftest queue-and-resolve-request-test
  (let [[queued-state request-id] (dao-repl/queue-request (dao-repl/initial-state)
                                                          "(+ 1 2)")
        resolved-state (dao-repl/resolve-request queued-state request-id :ok "3")]
    (is (= "browser/request/0" request-id))
    (is (= request-id (:active-request queued-state)))
    (is (= nil (:active-request resolved-state)))
    (is (= [{:id request-id
             :input "(+ 1 2)"
             :output "3"
             :status :ok}]
           (:history resolved-state)))))


(deftest fail-active-request-test
  (let [[queued-state request-id] (dao-repl/queue-request (dao-repl/initial-state)
                                                          "(help)")
        failed-state (dao-repl/fail-active-request queued-state "Connection closed")]
    (is (= nil (:active-request failed-state)))
    (is (= [{:id request-id
             :input "(help)"
             :output "Connection closed"
             :status :error}]
           (:history failed-state)))))
