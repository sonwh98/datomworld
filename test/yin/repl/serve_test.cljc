(ns yin.repl.serve-test
  "Phase R4 — the serving composition, with the host listener injected.

   `:bind!` and `:unbind!` are ordinary functions that deposit lifecycle data;
   connections are made by calling the transport's own upgrade entry with a
   captured socket.  Nothing here binds a port."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.stream.apply :as apply]
            [dao.stream.transit :as transit]
            [dao.stream.ws :as ws]
            [yin.repl.serve :as serve]))


;; =============================================================================
;; An injected host listener
;; =============================================================================

(defn- host
  []
  (let [bound (atom [])
        released (atom [])]
    {:bound bound
     :released released
     :adapter {:bind! (fn [config]
                        (swap! bound conj config)
                        ((:deposit! config) :bind-succeeded
                                            {:host (:bind-host config) :port (:bind-port config)})
                        {:listener :injected})
               :unbind! (fn [resources deposit!]
                          (swap! released conj resources)
                          ;; The host close completion is what deposits
                          ;; `:stopped`; the driver, not `stop!`, marks it done.
                          (deposit! :stopped {:reason :requested})
                          nil)}}))


(defn- socket
  "A captured socket: every frame the endpoint sends and every close it makes."
  []
  (let [sent (atom [])
        closed (atom [])]
    {:sent sent
     :closed closed
     :socket {:send! (fn [text] (swap! sent conj text) nil)
              :close! (fn [code reason] (swap! closed conj [code reason]) nil)}}))


(defn- frames
  [s]
  (mapv transit/decode @(:sent s)))


(defn- values
  [s]
  (->> (frames s)
       (filter #(= :ws/value (:ws/frame %)))
       (mapv :ws/value)))


(defn- endpoint!
  ([] (endpoint! {}))
  ([extra]
   (let [h (host)]
     {:host h
      :endpoint (serve/serve! (merge {:bind-port 8080
                                      :host (:adapter h)}
                                     extra))})))


(defn- texts
  [endpoint]
  (mapv :yin.repl.serve/text (first (serve/take-outbox endpoint))))


(defn- connect!
  "Make one connection through the transport's upgrade entry and drive the
   composition until the session is accepted.  Returns [endpoint socket handle]."
  [endpoint s now]
  (let [accepted (ws/accept-connection! (:ws-endpoint endpoint)
                                        (:path endpoint)
                                        (:socket s)
                                        now)
        endpoint (serve/step endpoint now)
        endpoint (serve/step endpoint (inc now))]
    [endpoint (:ws/handle accepted) (:ws/attachment accepted)]))


(defn- request!
  [handle id source]
  (ws/receive! handle (transit/encode
                        {:ws/frame :ws/value
                         :ws/value (apply/request id :op/eval [source])})))


;; =============================================================================
;; Composition data ownership
;; =============================================================================

(deftest serve-returns-immediately-with-its-lifecycle-medium-and-cursor
  (let [{:keys [endpoint host]} (endpoint!)]
    (is (some? (:lifecycle endpoint)))
    (is (some? (:lifecycle-cursor endpoint)))
    (is (some? (:service endpoint)))
    (is (= "/repl" (:path endpoint)))
    (is (= {"/repl" (:descriptor endpoint)} (:resolution endpoint)))
    (is (= :starting (:status endpoint))
        "serve! claims nothing about a bind it has not observed")
    (is (= 1 (count @(:bound host))))
    (testing "the bind result is a fact the driver reads from the medium"
      (let [endpoint (serve/step endpoint 1)]
        (is (= :running (:status endpoint)))
        (is (str/includes? (str/join " " (texts endpoint))
                           "Serving daostream:ws://127.0.0.1:8080/repl"))))))


(deftest a-wildcard-bind-needs-an-explicit-advertised-host
  (let [{:keys [endpoint host]} (endpoint! {:bind-host "0.0.0.0"})]
    (is (= :failed (:status endpoint)))
    (is (empty? @(:bound host)) "nothing was bound")
    (let [endpoint (serve/step endpoint 1)]
      (is (str/includes? (str/join " " (texts endpoint)) "advertised-host-required")))))


(deftest serving-without-a-host-package-reports-the-seam
  (let [endpoint (serve/serve! {:bind-port 8080 :host nil})
        endpoint (serve/step endpoint 1)]
    (is (= :failed (:status endpoint)))
    (is (str/includes? (str/join " " (texts endpoint)) "no-websocket-package"))))


(deftest stop-closes-the-service-stream-and-completes-only-when-told
  (let [{:keys [endpoint host]} (endpoint!)
        endpoint (serve/step endpoint 1)
        s (socket)
        [endpoint _handle _attachment] (connect! endpoint s 2)
        endpoint (serve/stop! endpoint)]
    (is (= :stopping (:status endpoint)))
    (is (false? (serve/stopped? endpoint)) "a bound listener is still owed a release")
    (is (some? (:resolution endpoint)) "the entry is held until stop completes")
    (let [endpoint (serve/step endpoint 4)]
      (is (= [4000 "dao.stream/ended"] (last @(:closed s)))
          "a closed service stream ends the attachment, it does not merely drop it")
      (is (= 1 (count @(:released host))))
      (let [endpoint (serve/step endpoint 5)]
        (is (= :stopped (:status endpoint)))
        (is (nil? (:resolution endpoint)))
        (is (str/includes? (str/join " " (texts endpoint)) "Endpoint stopped"))))))


;; =============================================================================
;; One request/response round trip
;; =============================================================================

(deftest stopping-an-endpoint-that-never-bound-claims-nothing
  (let [endpoint (serve/serve! {:bind-port 8080 :host nil})
        stopped (serve/stop! endpoint)]
    (is (= :failed (:status stopped))
        "an endpoint with no listener never became :stopping, and never will stop")
    (is (true? (serve/stopped? stopped))
        "nothing is owed, so a shutdown must not spend its budget waiting")))


(deftest stopping-after-bind-threw-never-calls-unbind-or-waits
  (let [unbinds (atom 0)
        endpoint (serve/serve!
                   {:bind-port 8080
                    :host {:bind! (fn [_config] (throw (ex-info "bind" {})))
                           :unbind! (fn [_resources _deposit!]
                                      (swap! unbinds inc)
                                      (throw (ex-info "must not run" {})))}})
        endpoint (serve/step endpoint 1)]
    (is (= :failed (:status endpoint)))
    (let [endpoint (serve/step (serve/stop! endpoint) 2)]
      (is (= :stopped (:status endpoint))
          "no host completion can follow a bind that returned no resource")
      (is (true? (serve/stopped? endpoint)))
      (is (nil? (:resolution endpoint)))
      (is (zero? @unbinds) "nil resources are never handed to the host"))))


(deftest a-synchronous-unbind-failure-resolves-stop-locally
  (let [endpoint (serve/serve!
                   {:bind-port 8080
                    :host {:bind! (fn [config]
                                    ((:deposit! config) :bind-succeeded
                                                        {:host "127.0.0.1" :port 8080})
                                    {:listener :injected})
                           :unbind! (fn [_resources _deposit!]
                                      (throw (ex-info "unbind" {})))}})
        endpoint (serve/step endpoint 1)
        endpoint (serve/step (serve/stop! endpoint) 2)]
    (is (= :stopped (:status endpoint)))
    (is (true? (serve/stopped? endpoint)))
    (is (nil? (:resolution endpoint)))
    (is (str/includes? (str/join " " (texts endpoint)) "unbind-threw"))))


(deftest a-request-round-trips-through-injected-host-functions
  (let [{:keys [endpoint]} (endpoint!)
        endpoint (serve/step endpoint 1)
        s (socket)
        [endpoint handle attachment] (connect! endpoint s 2)]
    (is (= [{:ws/frame :ws/accept}] (frames s))
        "no value is delivered before the acknowledged accept")
    (is (contains? (:sessions endpoint) attachment))
    (request! handle 0 "(+ 1 2)")
    (let [endpoint (serve/step endpoint 4)
          response (last (values s))]
      (is (= 0 (apply/response-id response)))
      (is (= "3" (apply/response-ok response)))
      (testing "the shared shell is threaded serially across requests"
        (request! handle 1 "(def x 41)")
        (let [endpoint (serve/step endpoint 5)]
          (request! handle 2 "(+ x 1)")
          (let [endpoint (serve/step endpoint 6)]
            (is (= "42" (apply/response-ok (last (values s)))))
            (is (= 3 (count (values s))))
            (is (= :running (:status endpoint)))))))))


(deftest two-clients-each-get-their-own-answers
  (let [{:keys [endpoint]} (endpoint!)
        endpoint (serve/step endpoint 1)
        a (socket)
        b (socket)
        [endpoint handle-a _] (connect! endpoint a 2)
        [endpoint handle-b _] (connect! endpoint b 4)]
    (request! handle-a 7 "(+ 1 1)")
    (request! handle-b 9 "(+ 2 2)")
    (let [endpoint (serve/step endpoint 6)
          endpoint (serve/step endpoint 7)]
      (is (= 2 (count (:sessions endpoint))))
      (is (= [{:dao.stream.apply/id 7 :dao.stream.apply/ok "2"}] (values a)))
      (is (= [{:dao.stream.apply/id 9 :dao.stream.apply/ok "4"}] (values b))))))


(deftest an-unknown-operation-is-a-portable-error-not-a-thrown-handler
  (let [{:keys [endpoint]} (endpoint!)
        endpoint (serve/step endpoint 1)
        s (socket)
        [endpoint handle _] (connect! endpoint s 2)]
    (ws/receive! handle (transit/encode
                          {:ws/frame :ws/value
                           :ws/value (apply/request 3 :op/forward ["x"])}))
    (let [_ (serve/step endpoint 4)
          response (last (values s))]
      (is (= 3 (apply/response-id response)))
      (is (= :yin.repl.serve/unknown-operation
             (:dao.stream.apply/code (apply/response-error response)))
          "the v2 server evaluates locally or says it does not proxy"))))


(deftest incomplete-input-is-answered-and-never-crosses-an-attachment-boundary
  (let [{:keys [endpoint]} (endpoint!)
        endpoint (serve/step endpoint 1)
        a (socket)
        b (socket)
        [endpoint handle-a _] (connect! endpoint a 2)
        [endpoint handle-b _] (connect! endpoint b 4)]
    (request! handle-a 1 "(+ 1")
    (let [endpoint (serve/step endpoint 6)
          response (last (values a))]
      (is (= 1 (apply/response-id response)))
      (is (= :yin.repl.serve/incomplete-input
             (:dao.stream.apply/code (apply/response-error response)))
          "the envelope was well formed; refusing the fragment is this server's decision")
      (is (nil? (:pending-input (:repl endpoint)))
          "line continuation is a terminal concern; the shell retains none of it")
      (request! handle-b 2 "(+ 2 2)")
      (serve/step endpoint 7)
      (is (= "4" (apply/response-ok (last (values b))))
          "one attachment's fragment cannot prefix another's request"))))


(deftest an-ephemeral-bind-names-the-limit-instead-of-a-descriptor-refusal
  (let [{:keys [endpoint host]} (endpoint! {:bind-port 0})]
    (is (= :failed (:status endpoint)))
    (is (empty? @(:bound host)) "nothing was bound")
    (let [endpoint (serve/step endpoint 1)]
      (is (str/includes? (str/join " " (texts endpoint))
                         "ephemeral-port-unsupported")))))


(deftest a-departed-attachment-retires-its-session
  (let [{:keys [endpoint]} (endpoint!)
        endpoint (serve/step endpoint 1)
        s (socket)
        [endpoint handle attachment] (connect! endpoint s 2)]
    (is (contains? (:sessions endpoint) attachment))
    (ws/closed! handle 1000 "peer")
    (let [endpoint (serve/step endpoint 4)]
      (is (empty? (:sessions endpoint)))
      (is (str/includes? (str/join " " (texts endpoint)) "left")))))


(deftest the-summary-is-plain-data
  (let [{:keys [endpoint]} (endpoint!)
        summary (serve/summary (serve/step endpoint 1))]
    (is (= :running (:status summary)))
    (is (= "daostream:ws://127.0.0.1:8080/repl" (:url summary)))
    (is (true? (:serving? summary)))
    (is (= [] (:sessions summary)))))
