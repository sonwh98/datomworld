(ns yin.repl.v2-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [yin.repl.v2 :as repl]
            [yin.repl.v2.driver :as driver]))


(deftest arguments-behave-as-they-do-in-v1-except-telemetry
  (let [opts (repl/parse-args ["--port" "8080" "--host" "0.0.0.0" "--headless"])]
    (is (= 8080 (:port opts)))
    (is (= "0.0.0.0" (:host opts)))
    (is (true? (:headless? opts)))
    (is (empty? (:rejected opts))))
  (testing "telemetry is rejected rather than ignored"
    (let [opts (repl/parse-args ["--telemetry-stream" "daostream:ws://x" "--telemetry"])]
      (is (= ["--telemetry-stream" "--telemetry"] (:rejected opts)))
      (is (str/includes? (first (repl/banner opts)) "yin.repl")))))


(deftest booting-yields-one-shell-one-input-medium-and-one-cursor
  (let [state (repl/boot (repl/parse-args []))]
    (is (some? (:input state)))
    (is (some? (:input-cursor state)))
    (is (true? (:running? state)))
    (is (empty? (repl/banner (repl/parse-args []))))
    (testing "the composition is drivable without any host loop"
      (driver/submit-line! (:input state) "(+ 40 2)")
      (let [[entries _] (driver/take-outbox (driver/repl-step state 0))]
        (is (= ["42"] (mapv :yin.repl.v2.driver/text entries)))))))


(deftest serving-is-announced-as-an-unmet-prerequisite
  (let [opts (repl/parse-args ["--port" "8080"])]
    (is (str/includes? (str/join " " (repl/banner opts)) "dao.stream.v2.rpc.ws"))))
