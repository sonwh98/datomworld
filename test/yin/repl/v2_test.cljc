(ns yin.repl.v2-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [yin.repl.v2 :as repl]
            [yin.repl.v2.driver :as driver]
            [yin.repl.v2.host :as host]
            [yin.repl.v2.serve :as serve]))


(defn- host-adapter
  "An injected host listener: `:bind!` and `:unbind!` are ordinary functions
   that deposit lifecycle data.  Nothing here binds a port."
  []
  {:bind! (fn [config]
            ((:deposit! config) :bind-succeeded
                                {:host (:bind-host config) :port (:bind-port config)})
            {:listener :injected})
   :unbind! (fn [_resources deposit!]
              (deposit! :stopped {:reason :requested})
              nil)})


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
    (is (host/adapter? (:host state)))
    (is (true? (:running? state)))
    (is (empty? (repl/banner (repl/parse-args []))))
    (testing "the composition is drivable without any host loop"
      (driver/submit-line! (:input state) "(+ 40 2)")
      (let [[entries _] (driver/take-outbox (driver/repl-step state 0))]
        (is (= ["42"] (mapv :yin.repl.v2.driver/text entries)))))))


(deftest an-uncomposed-port-reports-the-missing-host
  (let [opts (repl/parse-args ["--port" "8080"])
        server (serve/serve! {:bind-port 8080 :host nil})]
    (is (some? (:lifecycle server)) "serve! returns immediately with its medium")
    (is (= :failed (:status server)))
    (testing "and the ticker prints why, without a banner guessing at it"
      (let [[_ server' lines] (repl/step-all (repl/boot opts) server 0)]
        (is (str/includes? (str/join " " lines) "no host WebSocket package"))
        (is (= :failed (:status server')))))
    (is (nil? (repl/boot-server (repl/parse-args []))))))


(deftest the-explicit-stop-trigger-is-a-line-producer
  (let [state (repl/boot (repl/parse-args ["--headless"]))]
    (repl/request-stop! state)
    (is (true? (:running? state)) "appending stops nothing by itself")
    (is (false? (:running? (driver/repl-step state 0)))
        "the one step owner performs the shutdown, as it does for a typed quit")))


(deftest a-quit-shell-stops-the-endpoint-before-the-host-exits
  (let [server (serve/serve! {:bind-port 8080 :host (host-adapter)})
        server (serve/step server 1)]
    (is (= :running (:status server)))
    (let [[server _lines stopped?] (repl/stop-tick (serve/stop! server) 2)]
      (is (= :stopping (:status server)))
      (is (false? stopped?)
          "stop! initiates; only the host close completion is the stopped fact")
      (let [[server' lines stopped?'] (repl/stop-tick server 3)]
        (is (true? stopped?'))
        (is (= :stopped (:status server')))
        (is (str/includes? (str/join " " lines) "Endpoint stopped"))))))


(deftest an-endpoint-that-never-bound-is-not-waited-on
  (let [server (serve/serve! {:bind-port 8080 :host nil})
        [server' lines stopped?] (repl/stop-tick (serve/stop! server) 1)]
    (is (true? stopped?)
        "no host close completion can arrive for a listener that never bound")
    (is (= :failed (:status server'))
        "and the endpoint still says what happened to it, rather than :stopped")
    (is (str/includes? (str/join " " lines) "no host WebSocket package"))))


#?(:cljd nil
   :clj
   (deftest a-typed-quit-exits-without-waiting-for-end-of-input
     (let [state (repl/boot {})
           exits (atom 0)]
       (driver/submit-line! (:input state) "(quit)")
       (repl/poll-loop! state nil false (fn [] (swap! exits inc)))
       (is (= 1 @exits)
           "the step owner exits; the reader is still parked in read-line"))))


(deftest an-ephemeral-bind-is-refused-with-its-reason
  (let [server (serve/serve! {:bind-port 0 :host (host-adapter)})
        [_ server' lines] (repl/step-all (repl/boot {}) server 0)]
    (is (= :failed (:status server')))
    (is (str/includes? (str/join " " lines) "ephemeral-port-unsupported"))))
