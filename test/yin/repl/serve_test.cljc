(ns yin.repl.serve-test
  "The server side of the DaoStream Yin REPL, over the request-and-response
   service of docs/design/dao.stream.remote.md section 5.

   `:bind!` and `:unbind!` are ordinary functions that deposit lifecycle data;
   a connection is made by calling the transport's own upgrade entry with a
   captured socket, exactly as a real host adapter would.  Nothing here binds
   a port.  The requests/answers round trip is tested at the level serve.cljc
   itself owns -- the shared buffers `serve!` composes, never the wire -- since
   the wire and the mirror answering it are dao.stream.ws-project's and
   dao.stream.remote's own, already proven by their own test suites, and the
   demo REPL flow over a real socket is proven end to end in
   yin.repl.serve-connect-wire-test (JVM)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dao.stream :as stream]
            [dao.stream.rpc :as rpc]
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
   composition until the session is accepted.  Returns [endpoint socket]."
  [endpoint s now]
  (ws/accept-connection! (:ws-endpoint endpoint) (:path endpoint) (:socket s) now)
  (let [endpoint (serve/step endpoint now)
        endpoint (serve/step endpoint (inc now))]
    [endpoint s]))


;; =============================================================================
;; Composition data ownership
;; =============================================================================

(deftest serve-returns-immediately-with-its-lifecycle-medium-and-cursor
  (let [{:keys [endpoint host]} (endpoint!)]
    (is (some? (:lifecycle endpoint)))
    (is (some? (:lifecycle-cursor endpoint)))
    (is (some? (:requests endpoint)))
    (is (some? (:answers endpoint)))
    (is (= "/repl" (:path endpoint)))
    (is (= {"/repl" (:descriptor endpoint)} (:resolution endpoint)))
    (is (= :starting (:status endpoint))
        "serve! claims nothing about a bind it has not observed")
    (is (= 1 (count @(:bound host))))
    (let [endpoint (serve/step endpoint 1)]
      (is (= :running (:status endpoint)))
      (is (str/includes? (str/join " " (texts endpoint))
                         "Serving daostream:ws://127.0.0.1:8080/repl")))))


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


(deftest an-ephemeral-bind-names-the-limit-instead-of-a-descriptor-refusal
  (let [{:keys [endpoint host]} (endpoint! {:bind-port 0})]
    (is (= :failed (:status endpoint)))
    (is (empty? @(:bound host)) "nothing was bound")
    (let [endpoint (serve/step endpoint 1)]
      (is (str/includes? (str/join " " (texts endpoint))
                         "ephemeral-port-unsupported")))))


;; =============================================================================
;; Stop
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


(deftest stop-closes-every-accepted-sessions-socket-handle
  (let [{:keys [endpoint host]} (endpoint!)
        endpoint (serve/step endpoint 1)
        s (socket)
        [endpoint _s] (connect! endpoint s 2)
        endpoint (serve/stop! endpoint)]
    (is (= :stopping (:status endpoint)))
    (is (false? (serve/stopped? endpoint)) "a bound listener is still owed a release")
    (is (some? (:resolution endpoint)) "the entry is held until stop completes")
    ;; The socket closes only after a real-time grace period, so a client's
    ;; outstanding read of the now-ended requests/answers media can be
    ;; answered with the media's own `:dao.stream/end` before its socket
    ;; also reports closed -- see `serve/stop-grace-ms`.
    (let [endpoint (serve/step endpoint 4)
          endpoint (serve/step endpoint (+ 4 serve/stop-grace-ms -1))]
      (is (empty? @(:closed s)) "the socket has not closed yet: still in grace")
      (let [endpoint (serve/step endpoint (+ 4 serve/stop-grace-ms))]
        (is (= 1 (count @(:closed s)))
            "the accepted connection's socket handle was closed directly, since
             there is no forwarded source stream to end it")
        (is (= 1 (count @(:released host))))
        (let [endpoint (serve/step endpoint (+ 5 serve/stop-grace-ms))]
          (is (= :stopped (:status endpoint)))
          (is (nil? (:resolution endpoint)))
          (is (str/includes? (str/join " " (texts endpoint)) "Endpoint stopped")))))))


;; =============================================================================
;; The requests/answers round trip
;; =============================================================================

(defn- eval!
  "Append one eval request directly onto the endpoint's requests medium,
   as the mirror's own `:dao.stream/append!` answer would once a
   reflection's write reaches it -- the wire and its answering are
   dao.stream.ws-project's and dao.stream.remote's own concern, already
   proven by their test suites."
  [endpoint id source]
  (stream/append! (:requests endpoint) (rpc/request-value id :op/eval [source])))


(defn- answers-since
  "Every answer appended after `cursor`, oldest first, as [answers
   next-cursor]."
  [endpoint cursor]
  (loop [cursor cursor acc []]
    (let [r (stream/next (:answers endpoint) cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        [acc cursor]))))


(deftest a-request-evaluates-against-the-shared-shell-and-answers-once
  (let [{:keys [endpoint]} (endpoint!)
        endpoint (serve/step endpoint 1)
        answers-cursor (:dao.stream/cursor (stream/cursor (:answers endpoint)
                                                          stream/anchor-oldest))]
    (eval! endpoint 7 "(+ 1 2)")
    (let [endpoint (serve/step endpoint 2)
          [answers _cursor] (answers-since endpoint answers-cursor)]
      (is (= [(rpc/success-answer 7 "3")] answers)))))


(deftest the-shared-shell-is-threaded-serially-across-requests
  (let [{:keys [endpoint]} (endpoint!)
        endpoint (serve/step endpoint 1)
        answers-cursor (:dao.stream/cursor (stream/cursor (:answers endpoint)
                                                          stream/anchor-oldest))]
    (eval! endpoint 1 "(def x 41)")
    (let [endpoint (serve/step endpoint 2)]
      (eval! endpoint 2 "(+ x 1)")
      (let [endpoint (serve/step endpoint 3)
            [answers _cursor] (answers-since endpoint answers-cursor)]
        (is (= [(rpc/success-answer 1 "41") (rpc/success-answer 2 "42")]
               answers))))))


(deftest an-unknown-operation-is-a-portable-error-not-a-thrown-handler
  (let [{:keys [endpoint]} (endpoint!)
        endpoint (serve/step endpoint 1)
        answers-cursor (:dao.stream/cursor (stream/cursor (:answers endpoint)
                                                          stream/anchor-oldest))]
    (stream/append! (:requests endpoint) (rpc/request-value 3 :op/forward ["x"]))
    (let [endpoint (serve/step endpoint 2)
          [[answer] _cursor] (answers-since endpoint answers-cursor)]
      (is (= 3 (rpc/answer-id answer)))
      (is (= :yin.repl.serve/unknown-operation
             (:dao.stream.rpc/code (rpc/answer-error answer)))
          "the server evaluates locally or says it does not proxy"))))


(deftest incomplete-input-is-answered-and-never-crosses-a-callers-boundary
  (let [{:keys [endpoint]} (endpoint!)
        endpoint (serve/step endpoint 1)
        answers-cursor (:dao.stream/cursor (stream/cursor (:answers endpoint)
                                                          stream/anchor-oldest))]
    (eval! endpoint 1 "(+ 1")
    (let [endpoint (serve/step endpoint 2)
          [[answer] cursor] (answers-since endpoint answers-cursor)]
      (is (= 1 (rpc/answer-id answer)))
      (is (= :yin.repl.serve/incomplete-input
             (:dao.stream.rpc/code (rpc/answer-error answer)))
          "the envelope was well formed; refusing the fragment is this server's decision")
      (is (nil? (:pending-input (:repl endpoint)))
          "line continuation is a terminal concern; the shell retains none of it")
      (eval! endpoint 2 "(+ 2 2)")
      (let [endpoint (serve/step endpoint 3)
            [[answer'] _cursor] (answers-since endpoint cursor)]
        (is (= "4" (rpc/answer-ok answer'))
            "one caller's fragment cannot prefix another's request")))))


(deftest a-malformed-but-correlatable-request-is-answered-not-silently-dropped
  (let [{:keys [endpoint]} (endpoint!)
        endpoint (serve/step endpoint 1)
        answers-cursor (:dao.stream/cursor (stream/cursor (:answers endpoint)
                                                          stream/anchor-oldest))]
    (stream/append! (:requests endpoint) {:dao.stream.rpc/id 9 :not :a-request})
    (let [endpoint (serve/step endpoint 2)
          [[answer] _cursor] (answers-since endpoint answers-cursor)]
      (is (= 9 (rpc/answer-id answer)))
      (is (= :yin.repl.serve/malformed-request
             (:dao.stream.rpc/code (rpc/answer-error answer)))))))


(deftest an-uncorrelatable-value-is-dropped-as-a-diagnostic
  (let [{:keys [endpoint]} (endpoint!)
        endpoint (serve/step endpoint 1)
        answers-cursor (:dao.stream/cursor (stream/cursor (:answers endpoint)
                                                          stream/anchor-oldest))]
    (stream/append! (:requests endpoint) :not-even-a-map)
    (let [endpoint (serve/step endpoint 2)
          [answers _cursor] (answers-since endpoint answers-cursor)]
      (is (empty? answers))
      (is (str/includes? (str/join " " (texts endpoint)) "malformed request dropped")))))


(deftest the-summary-is-plain-data
  (let [{:keys [endpoint]} (endpoint!)
        summary (serve/summary (serve/step endpoint 1))]
    (is (= :running (:status summary)))
    (is (= "daostream:ws://127.0.0.1:8080/repl" (:url summary)))
    (is (true? (:serving? summary)))
    (is (= [] (:sessions summary)))))
