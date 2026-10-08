(ns yin.repl.serve-test
  "The server side of the DaoStream Yin REPL, over the request-and-response
   service of docs/design/dao.stream.remote.md section 5.

   The host is the in-process loopback net (yin.repl.net-fixture): its
   `:bind!` and `:unbind!` deposit lifecycle data, and a real
   `yin.repl.connect` client attaches through its `:connect!`, so the
   endpoint's sessions, drain and stop are observed from a client on every
   host.  Nothing here binds a port.  The requests/answers interpreter is
   tested at the level serve.cljc itself owns -- the shared buffers
   `serve!` composes -- since the wire and the mirror answering it are
   dao.stream.remote-channel's own, already proven by its test suite; the
   demo REPL flow over a real socket is proven end to end in
   yin.repl.serve-connect-wire-test (JVM)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.rpc :as rpc]
            [yin.repl :as repl]
            [yin.repl.connect :as connect]
            [yin.repl.net-fixture :as fixture]
            [yin.repl.serve :as serve]))


;; =============================================================================
;; An injected host listener
;; =============================================================================

(defn- endpoint!
  ([] (endpoint! {}))
  ([extra]
   (let [h (fixture/host)]
     {:host h
      :endpoint (serve/serve! (merge {:bind-port 8080
                                      :host (:adapter h)}
                                     extra))})))


(defn- texts
  [endpoint]
  (mapv :yin.repl.serve/text (first (serve/take-outbox endpoint))))


(defn- tick
  "One server turn at `now` with the net pumped around it."
  [h endpoint now]
  (fixture/pump! h)
  (let [endpoint (serve/step endpoint now)]
    (fixture/pump! h)
    endpoint))


(defn- connect!
  "Open one client to the endpoint and drive both until its answers cursor
   is minted.  Returns [endpoint client now]."
  [h endpoint now]
  (let [c (fixture/open h (serve/url endpoint) now)]
    (is (some? c) "the client attached")
    (loop [endpoint endpoint now now left 50]
      (let [endpoint (tick h endpoint now)]
        (fixture/client-step! c now)
        (if (and (pos? left) (rpc/cursor-pending? @(:client c)))
          (recur endpoint (inc now) (dec left))
          [endpoint c (inc now)])))))


;; =============================================================================
;; Composition data ownership
;; =============================================================================

(deftest serve-returns-immediately-with-its-media-and-channel-server
  (let [{:keys [endpoint host]} (endpoint!)]
    (is (some? (:server endpoint)))
    (is (some? (:requests endpoint)))
    (is (some? (:answers endpoint)))
    (is (= "/repl" (:path endpoint)))
    (is (= :starting (:status endpoint))
        "serve! claims nothing about a bind it has not observed")
    (is (= 1 (count @(:bound host))))
    (is (nil? (:ws-endpoint endpoint)) "no transport internals above the boundary")
    (is (nil? (:acceptor endpoint)) "no transport internals above the boundary")
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


(deftest a-wildcard-bind-with-an-advertised-host-binds-the-wildcard
  (let [{:keys [endpoint host]} (endpoint! {:bind-host "0.0.0.0"
                                            :advertised-host "10.0.0.5"})]
    (is (= :starting (:status endpoint)))
    (is (= "0.0.0.0" (:bind-host (first @(:bound host)))))
    (is (= "daostream:ws://10.0.0.5:8080/repl" (serve/url endpoint)))))


(deftest the-listener-binds-the-bind-port-while-the-url-names-the-advertised-port
  (let [{:keys [endpoint host]} (endpoint! {:bind-port 8080 :advertised-port 9090})]
    (is (= :starting (:status endpoint)))
    (is (= 8080 (:bind-port (first @(:bound host)))))
    (is (= "daostream:ws://127.0.0.1:9090/repl" (serve/url endpoint)))))


(deftest an-ephemeral-bind-with-an-advertised-port-binds-port-zero
  (let [{:keys [endpoint host]} (endpoint! {:bind-port 0 :advertised-port 9090})]
    (is (= :starting (:status endpoint)))
    (is (= 0 (:bind-port (first @(:bound host)))))
    (is (= "daostream:ws://127.0.0.1:9090/repl" (serve/url endpoint)))))


(deftest serving-without-a-host-package-reports-the-seam
  (let [endpoint (serve/serve! {:bind-port 8080 :host nil})
        endpoint (serve/step endpoint 1)]
    (is (= :failed (:status endpoint)))
    (is (str/includes? (str/join " " (texts endpoint)) "no-websocket-package"))))


(deftest an-ephemeral-bind-advertises-the-bound-port
  (let [{:keys [endpoint host]} (endpoint! {:bind-port 0})]
    (is (= :starting (:status endpoint)))
    (is (= 0 (:bind-port (first @(:bound host)))))
    (is (nil? (serve/url endpoint)) "nothing to type before the bind")
    (is (nil? (:url (serve/summary endpoint))))
    (let [endpoint (tick host endpoint 1)
          port (:port (:spec (:server endpoint)))
          url (str "daostream:ws://127.0.0.1:" port "/repl")
          [lines endpoint] (serve/take-outbox endpoint)]
      (is (= :running (:status endpoint)))
      (is (and (integer? port) (pos? port)) "the fixture's allocated port")
      (is (= url (serve/url endpoint)))
      (is (= url (:url (serve/summary endpoint))))
      (is (some #{(str "Serving " url)} (mapv :yin.repl.serve/text lines)))
      (testing "a client connects over the bound URL and round-trips"
        (let [[endpoint c now] (connect! host endpoint 2)
              requested (rpc/request! @(:client c) :op/eval ["(+ 1 2)"])
              _ (reset! (:client c) (:dao.stream.rpc/state requested))
              id (:dao.stream.rpc/id requested)]
          (is (= :dao.stream.rpc/requested (:dao.stream.rpc/outcome requested)))
          (loop [endpoint endpoint now now left 50]
            (let [endpoint (tick host endpoint now)]
              (fixture/client-step! c now)
              (when (and (pos? left) (empty? (:completed @(:client c))))
                (recur endpoint (inc now) (dec left)))))
          (is (= [id] (mapv (comp rpc/request-id :dao.stream.rpc/response)
                            (:completed @(:client c))))))))))


(deftest an-ephemeral-bind-whose-host-reports-no-port-fails
  (let [h (fixture/host)
        h (assoc-in h [:adapter :bind!]
                    (fn [config]
                      (swap! (:bound h) conj config)
                      ((:deposit! config) :bind-succeeded {:host (:bind-host config)})
                      {:dao.stream/outcome :dao.stream/ok :port 0}))
        endpoint (serve/serve! {:bind-port 0 :host (:adapter h)})
        _ (is (= :starting (:status endpoint)))
        endpoint (tick h endpoint 1)]
    (is (= :failed (:status endpoint)))
    (is (str/includes? (str/join " " (texts endpoint)) "reported no bound port"))
    (is (= 1 (count @(:released h))) "the bound listener was released")
    (is (true? (serve/stopped? endpoint)))))


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
    (is (str/includes? (str/join " " (texts endpoint)) "bind-threw"))
    (let [endpoint (serve/step (serve/stop! endpoint) 2)]
      (is (= :failed (:status endpoint))
          "no host completion can follow a bind that returned no resource")
      (is (true? (serve/stopped? endpoint)))
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
    (is (str/includes? (str/join " " (texts endpoint))
                       "Endpoint stopped without host completion: unbind-failed"))))


(deftest stop-ends-the-served-media-before-it-closes-the-session
  (let [{:keys [endpoint host]} (endpoint!)
        endpoint (tick host endpoint 1)
        [endpoint c now] (connect! host endpoint 2)
        terminals (atom [])
        endpoint (serve/stop! endpoint)
        t0 (+ now 10)]
    (is (= :stopping (:status endpoint)))
    (is (false? (serve/stopped? endpoint)) "a bound listener is still owed a release")
    (let [endpoint (loop [endpoint endpoint t t0]
                     (if (> t (+ t0 490))
                       endpoint
                       (let [endpoint (tick host endpoint t)]
                         (fixture/client-step! c t)
                         (swap! terminals conj (:terminal @(:client c)))
                         (recur endpoint (+ t 10)))))]
      (is (some #{:dao.stream.rpc/ended} @terminals)
          "the client read the ended answers medium during the drain")
      (is (not-any? #{:dao.stream.rpc/detached} @terminals) "never a detach")
      (is (zero? (fixture/closed-conns host)) "the session is still open")
      (is (empty? @(:released host)) "the host is not yet released")
      (let [endpoint (tick host endpoint (+ t0 500))]
        (is (= 1 (fixture/closed-conns host)) "the session closed at +500")
        (is (= 1 (count @(:released host))) "the host was released once")
        (let [endpoint (tick host endpoint (+ t0 501))]
          (is (= :stopped (:status endpoint)))
          (is (true? (serve/stopped? endpoint)))
          (is (= 1 (count @(:released host))))
          (is (str/includes? (str/join " " (texts endpoint)) "Endpoint stopped")))))))


(deftest attachment-departure-is-noticed
  (let [{:keys [endpoint host]} (endpoint!)
        endpoint (tick host endpoint 1)
        [endpoint c now] (connect! host endpoint 2)
        [attachment] (:sessions (serve/summary endpoint))
        [_ endpoint] (serve/take-outbox endpoint)]
    (is (string? attachment))
    (swap! (:connection c) connect/close!)
    (let [endpoint (tick host endpoint now)
          endpoint (tick host endpoint (inc now))]
      (is (some #{(str ";; attachment " attachment " left")} (texts endpoint)))
      (is (empty? (:sessions (serve/summary endpoint)))))))


(deftest a-serving-gap-is-counted-and-serving-continues
  (let [{:keys [endpoint host]} (endpoint!)
        endpoint (tick host endpoint 1)
        [_ endpoint] (serve/take-outbox endpoint)]
    (dotimes [i 70] (@(:deposit host) :listener-error {:i i}))
    (let [endpoint (tick host endpoint 2)
          lines (texts endpoint)]
      (is (= :running (:status endpoint)))
      (is (= 1 (count (filter #{";; endpoint lifecycle gap"} lines))))
      (is (= {:gaps 1} (select-keys (:lifecycle (serve/summary endpoint)) [:gaps]))))))


;; =============================================================================
;; The requests/answers round trip
;; =============================================================================

(defn- eval!
  "Append one eval request directly onto the endpoint's requests medium,
   as the mirror's own `:dao.stream/append!` answer would once a
   reflection's write reaches it -- the wire and its answering are
   dao.stream.remote-channel's own concern, already proven by its test
   suite."
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


(deftest a-served-host-function-renders-as-the-local-marker
  (let [{:keys [endpoint]} (endpoint!)
        endpoint (serve/step endpoint 1)
        answers-cursor (:dao.stream/cursor (stream/cursor (:answers endpoint)
                                                          stream/anchor-oldest))
        lines ["+" "(require (quote dao.space.query))" "dao.space.query/q"]
        local (second (reduce (fn [[state texts] line]
                                (let [[state' text] (repl/eval-input state line)]
                                  [state' (conj texts text)]))
                              [(repl/create-state) []]
                              lines))
        endpoint (reduce (fn [endpoint [i line]]
                           (eval! endpoint i line)
                           (serve/step endpoint (+ 2 i)))
                         endpoint
                         (map-indexed vector lines))
        [answers _cursor] (answers-since endpoint answers-cursor)]
    (is (= "{:type :host-fn, :name '+}" (first local)))
    (is (= "{:type :host-fn, :name 'dao.space.query/q}" (last local)))
    (is (= (map-indexed rpc/success-answer local) answers)
        "a served session renders every host function as a local one does")
    (is (not-any? #(str/includes? % "#object[") (map :dao.stream.apply/ok answers)))))


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
             (:dao.stream.apply/code (rpc/answer-error answer)))
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
             (:dao.stream.apply/code (rpc/answer-error answer)))
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
    (stream/append! (:requests endpoint) {:dao.stream.apply/id 9 :not :a-request})
    (let [endpoint (serve/step endpoint 2)
          [[answer] _cursor] (answers-since endpoint answers-cursor)]
      (is (= 9 (rpc/answer-id answer)))
      (is (= :yin.repl.serve/malformed-request
             (:dao.stream.apply/code (rpc/answer-error answer)))))))


(deftest a-well-formed-request-with-an-unsafe-id-is-dropped-not-evaluated
  (let [{:keys [endpoint]} (endpoint!)
        endpoint (serve/step endpoint 1)
        answers-cursor (:dao.stream/cursor (stream/cursor (:answers endpoint)
                                                          stream/anchor-oldest))]
    (stream/append! (:requests endpoint)
                    (rpc/request-value "opaque-id" :op/eval ["(+ 1 2)"]))
    (let [endpoint (serve/step endpoint 2)
          [answers _cursor] (answers-since endpoint answers-cursor)]
      (is (empty? answers)
          "an id rpc could never have minted correlates nothing, even
           on an otherwise well-formed apply request")
      (is (str/includes? (str/join " " (texts endpoint))
                         "malformed request dropped")
          "dropped as a diagnostic -- never evaluated, never answered"))))


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
  (let [{:keys [endpoint host]} (endpoint!)
        endpoint (tick host endpoint 1)
        summary (serve/summary endpoint)]
    (is (= :running (:status summary)))
    (is (= "daostream:ws://127.0.0.1:8080/repl" (:url summary)))
    (is (true? (:serving? summary)))
    (is (= [] (:sessions summary)))
    (is (= {:gaps 0 :diagnostics 0} (:lifecycle summary)))
    (testing "a connected client is listed by its attachment"
      (let [[endpoint _c _now] (connect! host endpoint 2)
            sessions (:sessions (serve/summary endpoint))]
        (is (= 1 (count sessions)))
        (is (every? string? sessions))))))
