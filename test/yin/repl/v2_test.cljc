(ns yin.repl.v2-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            #?@(:cljd []
                :clj [[clojure.java.io :as io]
                      [yin.repl.v2.host :as host]]
                :cljs [[yin.repl.v2.connect :as connect]])
            [dao.stream.v2.apply :as apply]
            [dao.stream.v2.transit :as transit]
            [dao.stream.v2.ws :as ws]
            [yin.repl.v2 :as repl]
            [yin.repl.v2.driver :as driver]
            [yin.repl.v2.host.common :as host-common]
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
      (is (str/includes? (first (repl/banner opts))
                         "yin.vm.v2.telemetry.implementation-plan.md"))
      (is (not (str/includes? (first (repl/banner opts)) "yin.repl"))))))


(deftest booting-yields-one-shell-one-input-medium-and-one-cursor
  (let [state (repl/boot (repl/parse-args []))]
    (is (some? (:input state)))
    (is (some? (:input-cursor state)))
    (is (host-common/adapter? (:host state)))
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


;; =============================================================================
;; One shared shell — `--port` serves the shell the local prompt evaluates
;; against, as v1's atom did.  The composition is the real one: `repl/boot`
;; beside `serve!`, both advanced only by `step-all`, with a captured socket
;; standing in for the remote client.
;; =============================================================================

(defn- socket
  "A captured socket: every frame the endpoint sends."
  []
  (let [sent (atom [])]
    {:sent sent
     :socket {:send! (fn [text] (swap! sent conj text) nil)}}))


(defn- reply-values
  "The `:ws/value` frames a captured socket received, decoded."
  [s]
  (->> @(:sent s)
       (mapv transit/decode)
       (filter #(= :ws/value (:ws/frame %)))
       (mapv :ws/value)))


(defn- remote-request!
  "Deliver one `:op/eval` request through a connected socket handle."
  [handle id source]
  (ws/receive! handle (transit/encode
                        {:ws/frame :ws/value
                         :ws/value (apply/request id :op/eval [source])})))


(deftest the-served-endpoint-shares-the-local-shells-shell
  (let [server (serve/serve! {:bind-port 8080 :host (host-adapter)})
        state (repl/boot {:adapter (host-adapter)})
        [_ server _] (repl/step-all state server 0)
        s (socket)
        accepted (ws/accept-connection! (:ws-endpoint server)
                                        (:path server)
                                        (:socket s)
                                        2)
        ;; Adoption is observed on the step after the upgrade, as the serve
        ;; composition's own tests drive it.
        [_ server _] (repl/step-all state server 3)
        [state server _] (repl/step-all state server 4)]
    (is (some? (:ws/handle accepted)))
    (is (contains? (:sessions server) (:ws/attachment accepted)))
    (driver/submit-line! (:input state) "(defn twice [x] (* 2 x))")
    (let [[state server lines] (repl/step-all state server 5)]
      (is (str/includes? (str/join " " lines) ":closure")
          "the local prompt defined the function against its own shell")
      (testing "a definition typed at the local prompt answers a remote request"
        (remote-request! (:ws/handle accepted) 0 "(twice 21)")
        (let [[state server _] (repl/step-all state server 6)
              response (last (reply-values s))]
          (is (= 0 (apply/response-id response)))
          (is (= "42" (apply/response-ok response))
              "the endpoint must evaluate against the shell the local prompt shares, not a private one")
          (testing "a remote definition answers the local prompt on a later tick"
            (remote-request! (:ws/handle accepted) 1 "(def answer 7)")
            (let [[state server _] (repl/step-all state server 7)]
              (is (= "7" (apply/response-ok (last (reply-values s)))))
              (driver/submit-line! (:input state) "answer")
              (let [[_state _server lines] (repl/step-all state server 8)]
                (is (some #(= "7" %) lines)
                    "the local prompt must see what a remote client defined")))))))))


;; =============================================================================
;; Phase R5 — the slice, end to end, across two operating-system processes
;;
;; This namespace is process A: the headless server, composed through the real
;; `serve!` and stepped by one ticker thread exactly as `-main --headless`
;; steps it.  Process B is `yin.repl.v2.slice-peer`, spawned as a real child
;; JVM: a whole REPL client that shares nothing with A but bytes on a socket
;; and bytes on a pipe.  Nothing B knows about A arrives except as data — the
;; URL an operator would type.
;;
;; Facts 1-3 share one process A through the `:once` fixture, because fact 3
;; is precisely that the served stream survives a connection's death: a
;; per-test server would make survival unfalsifiable.  Fact 4 stops its
;; endpoint, so it composes — and stops — its own.
;; =============================================================================

#?(:cljd nil
   :clj
   (do
     ;; Deadlines.  A peer is a whole JVM, so its first breath is slow while
     ;; every later exchange is a pipe round trip.
     (def ^:private peer-ready-ms 60000)
     (def ^:private reply-ms 15000)
     (def ^:private event-ms 15000)


     ;; =========================================================================
     ;; Process A: one headless v2 REPL endpoint
     ;; =========================================================================

     (defn- free-port!
       "One currently-free TCP port.  `serve!` fixes its descriptor at
        composition, so an ephemeral bind cannot serve; the race between this
        close and the fixture's bind is accepted and a collision fails the
        fixture loudly with the endpoint's own bind-failed notice."
       []
       (let [socket (java.net.ServerSocket. 0)]
         (try (.getLocalPort socket)
              (finally (.close socket)))))


     (def ^:private process-a (atom nil))


     (defn- start-attempt!
       "One bind attempt.  Answers the process map, or ::bind-failed after
        shutting its own ticker down so a retry leaks no thread."
       []
       (let [port (free-port!)
             endpoint (atom (serve/serve! {:bind-port port :host (host/websocket)}))
             paused (atom false)
             running (atom true)
             notices (atom [])
             ticker (doto (Thread.
                            (fn []
                              (while @running
                                ;; `paused` is the test's stall of the one
                                ;; server driver: host socket threads keep
                                ;; depositing, so a request can sit accepted
                                ;; but unanswered while the driver holds.
                                (when-not @paused
                                  ;; One atomic read-step-write.  A `reset!`
                                  ;; of a value derived from an earlier
                                  ;; `@endpoint` silently loses whatever a
                                  ;; test wrote in between — a `stop!` from
                                  ;; the test thread would vanish and the
                                  ;; endpoint would stay :running.  `swap!`
                                  ;; re-runs on contention, so the step is
                                  ;; always applied to the value that wins.
                                  (let [drained (volatile! nil)]
                                    (swap! endpoint
                                           (fn [ep]
                                             (let [stepped (serve/step
                                                             ep
                                                             (System/currentTimeMillis))
                                                   [entries next]
                                                   (serve/take-outbox stepped)]
                                               (vreset! drained entries)
                                               next)))
                                    (swap! notices
                                           into
                                           (keep serve/text-key @drained))))
                                (Thread/sleep 5)))
                            "yin-repl-v2-slice-server")
                      (.setDaemon true)
                      (.start))
             deadline (+ (System/currentTimeMillis) 10000)
             abandon! (fn []
                        (reset! running false)
                        (.join ^Thread ticker 2000))]
         (loop []
           (let [status (:status @endpoint)]
             (cond
               (= :running status)
               {:port port
                :url (str "daostream:ws://127.0.0.1:" port "/repl")
                :endpoint endpoint
                :paused paused
                :running running
                :ticker ticker
                :notices notices}

               ;; A lost free-port! race, not a defect under test: the probe
               ;; socket is closed before serve! binds, so another process can
               ;; take the port in between.  Abandon this attempt and let the
               ;; caller retry on a fresh one.
               (= :failed status) (do (abandon!) ::bind-failed)

               (< deadline (System/currentTimeMillis))
               (do (abandon!)
                   (throw (ex-info "the R5 endpoint never reported :running"
                                   {:status status, :notices @notices})))

               :else (do (Thread/sleep 20) (recur)))))))


     (defn- start-process-a!
       "Bind an endpoint, retrying a lost port race with a fresh port.  The
        collision used to fail the fixture loudly, which made every test in
        this namespace intermittently red for a reason that has nothing to do
        with what they assert."
       []
       (loop [attempts 5]
         (let [a (start-attempt!)]
           (cond
             (not= ::bind-failed a) a
             (pos? attempts) (do (Thread/sleep 25) (recur (dec attempts)))
             :else (throw (ex-info "no free port survived five bind attempts"
                                   {}))))))


     (defn- stop-process-a!
       "Stop the ticker, then drive the endpoint to :stopped so its listening
        socket is released before the next fixture binds a port.  The old
        loop gave up silently after 200 steps and left the port bound, which
        a later `free-port!` could then hand out; it now reports what it was
        still waiting on.  Always writes the final endpoint back, so a caller
        that inspects it after the stop sees the stopped value."
       [a]
       (reset! (:running a) false)
       (.join ^Thread (:ticker a) 2000)
       (let [deadline (+ (System/currentTimeMillis) 5000)
             final (loop [endpoint (serve/stop! @(:endpoint a))]
                     (let [endpoint' (serve/step endpoint
                                                 (System/currentTimeMillis))]
                       (cond
                         (serve/stopped? endpoint') endpoint'
                         (< deadline (System/currentTimeMillis))
                         (throw (ex-info
                                  "the endpoint never reached :stopped; its port may still be bound"
                                  {:status (:status endpoint')
                                   :port (:port a)
                                   :notices @(:notices a)}))
                         :else (do (Thread/sleep 5) (recur endpoint')))))]
         (reset! (:endpoint a) final)
         final))


     (clojure.test/use-fixtures :once
       (fn [run]
         (reset! process-a (start-process-a!))
         (try (run)
              (finally
                (stop-process-a! @process-a)
                (reset! process-a nil)))))


     ;; =========================================================================
     ;; Process B: a real child process, spoken to only over pipes
     ;; =========================================================================

     (defn- pump!
       [reader on-line]
       (doto (Thread.
               (fn []
                 (try
                   (loop []
                     (when-let [line (.readLine ^java.io.BufferedReader reader)]
                       (on-line line)
                       (recur)))
                   (catch Exception _ nil))))
         (.setDaemon true)
         (.start)))


     (defn- default-peer-command
       "Process B as a JVM on this process's own classpath, as the stream slice
        does: the same tree A runs, one JVM boot instead of a dependency
        resolution."
       []
       ["java" "-cp" (System/getProperty "java.class.path")
        "clojure.main" "-m" "yin.repl.v2.slice-peer"])


     (def ^:private dart-peer-exe "build/yin-repl-v2-peer")


     (defn- start-peer!
       "Spawn process B from `command`.  The command is a JVM by default; the
        cross-host pair substitutes the compiled Dart peer, which speaks the
        identical pipe protocol."
       ([]
        (start-peer! (default-peer-command)))
       ([command]
        (let [process (.start (ProcessBuilder. ^java.util.List command))
              replies (atom [])
              errors (atom [])
              peer {:process process
                    :in (io/writer (.getOutputStream process))
                    :replies replies
                    :errors errors
                    :seen (atom [])
                    :consumed (atom 0)}]
          ;; Loading B's namespaces prints to B's stdout before the peer
          ;; protocol starts, so a line that is not a Transit reply is noise
          ;; rather than a protocol failure.
          (pump! (io/reader (.getInputStream process))
                 (fn [line]
                   (let [reply (try (transit/decode line) (catch Exception _ nil))]
                     (if (:reply reply)
                       (swap! replies conj reply)
                       (swap! errors conj line)))))
          ;; stderr must be drained too: an unread pipe fills and would block
          ;; the child mid-report.
          (pump! (io/reader (.getErrorStream process))
                 (fn [line] (swap! errors conj line)))
          peer)))


     (defn- stop-peer!
       [peer]
       (try (.destroy ^Process (:process peer)) (catch Exception _ nil)))


     (defn- take-reply!
       "Consume the first reply of `kind`, or nil at the deadline."
       [peer kind timeout-ms]
       (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
         (loop []
           (let [index (first (keep-indexed (fn [i r] (when (= kind (:reply r)) i))
                                            @(:replies peer)))]
             (cond
               index (let [reply (nth @(:replies peer) index)]
                       (swap! (:replies peer)
                              (fn [rs]
                                (vec (concat (subvec rs 0 index)
                                             (subvec rs (inc index))))))
                       reply)
               (< deadline (System/currentTimeMillis)) nil
               :else (do (Thread/sleep 5) (recur)))))))


     (defn- ask!
       [peer command kind timeout-ms]
       (let [writer ^java.io.Writer (:in peer)]
         (.write writer (str (transit/encode command) "\n"))
         (.flush writer))
       (take-reply! peer kind timeout-ms))


     (defn- poll-events!
       "Ask B for whatever its driver has published since the last ask, and
        remember it."
       [peer]
       (when-let [reply (ask! peer {:cmd :events} :events reply-ms)]
         (swap! (:seen peer) into (:events reply)))
       @(:seen peer))


     (defn- fresh-events!
       "The events a waiting helper has not yet observed.  Awaiting consumes:
        a response seen by one `await-event` is never returned to the next."
       [peer]
       (let [all (poll-events! peer)
             n @(:consumed peer)]
         (when (> (count all) n)
           (reset! (:consumed peer) (count all))
           (subvec all n))))


     (defn- seen
       [peer pred]
       (some pred @(:seen peer)))


     (defn- text-starts-with
       [prefix]
       (fn [event] (str/starts-with? (str (:text event)) prefix)))


     (defn- text-contains
       [needle]
       (fn [event] (str/includes? (str (:text event)) needle)))


     (defn- text-is
       "Exact equality, for assertions a substring cannot make: a served port
        can contain any digits, so `42` matching the URL of `…:42913/repl` is
        a false cross-talk result, not an answer."
       [expected]
       (fn [event] (= expected (:text event))))


     (defn- await-event
       "The first not-yet-consumed event satisfying `pred`, or nil at the
        deadline.  Returns the event itself, never the predicate's value."
       [peer pred timeout-ms]
       (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
         (loop []
           (or (first (filter pred (fresh-events! peer)))
               (when-not (< deadline (System/currentTimeMillis))
                 (Thread/sleep 20)
                 (recur))))))


     (defn- await-outstanding
       "Probe until the peer's driver holds a request it has sent and not had
        answered.  This is what makes fact 2 and fact 3's drop deterministic:
        the kill happens while the request is provably outstanding, not a sleep
        after the submit."
       [peer timeout-ms]
       (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
         (loop []
           (or (when-let [probe-reply (ask! peer {:cmd :probe} :probe reply-ms)]
                 (when (:outstanding probe-reply) probe-reply))
               (when-not (< deadline (System/currentTimeMillis))
                 (Thread/sleep 20)
                 (recur))))))


     (defn- await-server-notice
       "Poll process A's own publication outbox until one notice contains
        `needle`, or nil at the deadline."
       [a needle timeout-ms]
       (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
         (loop []
           (or (some #(str/includes? % needle) @(:notices a))
               (when-not (< deadline (System/currentTimeMillis))
                 (Thread/sleep 20)
                 (recur))))))


     (defn- attach-peer!
       "Spawn B, wait for its bootstrap, and have it `(connect …)` exactly the
        way an operator does: as a typed line, not an API call."
       ([a]
        (attach-peer! a (default-peer-command)))
       ([a command]
        (let [peer (start-peer! command)
              ready (take-reply! peer :ready peer-ready-ms)]
          (is (some? ready)
              (str "process B never reported its bootstrap; its other output was "
                   (pr-str (take 5 @(:errors peer)))))
          (ask! peer {:cmd :line :line (str "(connect \"" (:url a) "\")")}
                :line reply-ms)
          (let [established (await-event peer
                                         #(= :yin.repl.v2.connect/connected (:event %))
                                         event-ms)]
            (is (some? established)
                (str "the peer never observed /established; its events were "
                     (pr-str @(:seen peer)))))
          peer)))


     (defn- remote-value
       "Evaluate one source line on the peer's remote shell, returning the
        response the driver published."
       [peer line]
       (ask! peer {:cmd :line :line line} :line reply-ms)
       (when-let [response (await-event peer
                                        #(= :yin.repl.v2.driver/response (:event %))
                                        event-ms)]
         (:text response)))


     (defn- drop-with-outstanding-request!
       "Stall A's driver, let the peer send one request it can never have
        answered, and disconnect while it is outstanding.  Returns nil; the
        client-side loss report is what the caller awaits."
       [a peer]
       (reset! (:paused a) true)
       (ask! peer {:cmd :line :line "(+ 40 2)"} :line reply-ms)
       (is (some? (await-outstanding peer event-ms))
           "the request never became outstanding, so the drop would prove nothing")
       (ask! peer {:cmd :line :line "(disconnect)"} :line reply-ms))


     ;; =========================================================================
     ;; The four facts
     ;; =========================================================================

     (deftest evaluation-round-trips-and-two-clients-get-their-own-answers
       ;; Fact 1.
       (let [a @process-a
             peer-1 (attach-peer! a)
             peer-2 (attach-peer! a)]
         (try
           (is (= "42" (remote-value peer-1 "(+ 40 2)")))
           (is (= "23" (remote-value peer-2 "(+ 20 3)")))
           (testing "each client's publication carries only its own answers"
             (poll-events! peer-1)
             (poll-events! peer-2)
             (is (nil? (seen peer-1 (text-is "23"))))
             (is (nil? (seen peer-2 (text-is "42")))))
           (finally
             (stop-peer! peer-1)
             (stop-peer! peer-2)))))


     (deftest killing-the-connection-is-observable-and-requests-are-lost
       ;; Fact 2.
       (let [a @process-a
             peer (attach-peer! a)]
         (try
           (is (= "3" (remote-value peer "(+ 1 2)")))
           (drop-with-outstanding-request! a peer)
           (testing "the outstanding request is reported lost, not timed out"
             (let [lost (await-event peer
                                     (text-starts-with ";; remote request")
                                     event-ms)]
               (is (some? lost)
                   (str "no loss report arrived; events were "
                        (pr-str @(:seen peer))))))
           (testing "the client side observes the death"
             (let [probe-reply (ask! peer {:cmd :probe} :probe reply-ms)]
               (is (= :detached (get-in probe-reply [:connection :status])))
               (is (= :dao.stream/closed (:handle-outcome probe-reply))
                   "the attachment handle answers closed")
               (is (nil? (:outstanding probe-reply))
                   "a reported-lost request is no longer outstanding")))
           ;; The server driver was stalled, so its notice can only arrive now.
           (reset! (:paused a) false)
           (testing "the server boundary deposits the departure"
             (is (some? (await-server-notice a "left" event-ms))))
           (finally
             (reset! (:paused a) false)
             (stop-peer! peer)))))


     (deftest reattaching-resumes-the-served-stream-and-the-deposit-medium
       ;; Fact 3.
       (let [a @process-a
             peer (attach-peer! a)]
         (try
           (is (= "10" (remote-value peer "(def x 10)")))
           (let [before (ask! peer {:cmd :probe} :probe reply-ms)
                 first-attachment (get-in before [:connection :attachment])]
             (is (some? first-attachment))
             (drop-with-outstanding-request! a peer)
             (is (some? (await-event peer (text-contains "lost") event-ms))
                 "the drop never reported its outstanding request")
             (reset! (:paused a) false)
             (is (some? (await-server-notice a "left" event-ms)))
             (testing "the same descriptor reattaches to the same served stream"
               (ask! peer {:cmd :line :line (str "(connect \"" (:url a) "\")")}
                     :line reply-ms)
               (is (some? (await-event peer
                                       #(= :yin.repl.v2.connect/connected (:event %))
                                       event-ms))
                   "the reattachment never established")
               (let [after (ask! peer {:cmd :probe} :probe reply-ms)]
                 (is (true? (:traffic-retained? after))
                     "the deposit medium did not survive the socket's death")
                 (is (not= first-attachment
                           (get-in after [:connection :attachment]))
                     "a new attachment id should name the new boundary"))
               (is (= "10" (remote-value peer "x"))
                   "the shared shell the served stream owns did not survive")))
           (finally
             (reset! (:paused a) false)
             (stop-peer! peer)))))


     (deftest stopping-the-endpoint-ends-the-served-stream-not-closes-it
       ;; Fact 4.  This fact stops its endpoint, so it composes its own.
       (let [a (start-process-a!)]
         (try
           (let [peer (attach-peer! a)]
             (try
               (is (= "7" (remote-value peer "(+ 3 4)")))
               (swap! (:endpoint a) serve/stop!)
               (is (some? (await-server-notice a "Endpoint stopped" event-ms))
                   "the endpoint never completed its stop")
               (is (= :stopped (:status @(:endpoint a))))
               (testing "the client deposits :ws/ended, not :ws/closed"
                 (is (some? (await-event peer
                                         #(= :yin.repl.v2.connect/ended (:event %))
                                         event-ms))
                     (str "no ended event arrived; events were "
                          (pr-str @(:seen peer))))
                 (is (nil? (seen peer
                                 #(= :yin.repl.v2.connect/detached (:event %))))
                     "a detached event would mean the socket closed without the
                      served stream ending")
                 (let [probe-reply (ask! peer {:cmd :probe} :probe reply-ms)]
                   (is (= :ended (get-in probe-reply [:connection :status])))))
               (finally
                 (stop-peer! peer))))
           (finally
             (stop-process-a! a)))))


     ;; =========================================================================
     ;; The cross-host pairs
     ;;
     ;; "The descriptor crossed a codec and the wire is the same wire; if that
     ;; fails, the contract was implemented three times rather than once."
     ;; The Dart peer is one program in two roles (`build/yin-repl-v2-peer`,
     ;; from `bb build:yin-repl-v2-peer`): the client role here, the server
     ;; role for the Node parent in this namespace's cljs half.  A lane run
     ;; without the exe skips loudly rather than failing or passing silently.
     ;; =========================================================================

     (deftest a-dart-client-attaches-to-this-jvm-server
       ;; Cross-host pair 1: cljd client against a JVM server.
       (if-not (.exists (io/file dart-peer-exe))
         (println ";; SKIPPED a-dart-client-attaches-to-this-jvm-server:"
                  dart-peer-exe "is absent — run bb build:yin-repl-v2-peer (or bb test) first")
         (let [a @process-a
               peer (attach-peer! a [dart-peer-exe])]
           (try
             (is (= "42" (remote-value peer "(+ 40 2)"))
                 "the wire round trip must not depend on the client's host")
             (drop-with-outstanding-request! a peer)
             (testing "the loss report crosses the wire too"
               (is (some? (await-event peer
                                       (text-starts-with ";; remote request")
                                       event-ms))))
             (let [probe-reply (ask! peer {:cmd :probe} :probe reply-ms)]
               (is (= :detached (get-in probe-reply [:connection :status])))
               (is (= :dao.stream/closed (:handle-outcome probe-reply))
                   "the Dart attachment handle answers closed"))
             (reset! (:paused a) false)
             (is (some? (await-server-notice a "left" event-ms))
                 "the JVM boundary deposited the Dart client's departure")
             (finally
               (reset! (:paused a) false)
               (stop-peer! peer))))))))


;; =============================================================================
;; Cross-host pair 2: this Node process is the client; the server is the Dart
;; peer's `--serve` role.  The client composition is the real one — `repl/boot`
;; with one interval step owner, exactly as the Node host runs it — and the
;; server is a real Dart process sharing nothing but bytes on a socket.
;; =============================================================================

#?(:cljs
   (do
     (def ^:private cross-bind-ms 30000)
     (def ^:private cross-event-ms 20000)

     (def ^:private dart-peer-exe "build/yin-repl-v2-peer")


     (defn- poll-until
       "Resolve with the first truthy value `pred` returns, or reject at the
        deadline.  Polling is the only honest wait here: no operation blocks,
        and nothing notifies."
       [pred timeout-ms message]
       (js/Promise.
         (fn [resolve reject]
           (let [deadline (+ (js/Date.now) timeout-ms)]
             (letfn [(tick
                       []
                       (let [value (try (pred) (catch :default _ nil))]
                         (cond
                           value (resolve value)
                           (< deadline (js/Date.now)) (reject (js/Error. message))
                           :else (js/setTimeout tick 20))))]
               (tick))))))


     (defn- line-reader
       [readable on-line]
       (let [pending (atom "")]
         (.setEncoding ^js readable "utf8")
         (.on ^js readable "data"
              (fn [chunk]
                (let [parts (str/split (str @pending chunk) #"\n" -1)]
                  (reset! pending (last parts))
                  (doseq [line (butlast parts)
                          :when (not (str/blank? line))]
                    (on-line line)))))))


     (defn- free-port!
       "One currently-free TCP port, handed to `on-port` once the probe socket
        is closed.  `serve!` fixes its descriptor at composition, so an
        ephemeral bind cannot serve."
       [on-port]
       (let [net (js/require "net")
             server (.createServer net)]
         (.listen server 0 "127.0.0.1"
                  (fn []
                    (let [port (.-port (.address server))]
                      (.close server (fn [] (on-port port))))))))


     (defn- boot-client!
       "The Node client composition: one shell, one input medium, one interval
        step owner that collects what the driver publishes.  Nothing here
        differs from the real Node host except where the text goes."
       []
       (let [box (atom (repl/boot {}))
             out (atom [])
             timer (js/setInterval
                     (fn []
                       (let [stepped (driver/repl-step @box (js/Date.now))
                             [entries next] (driver/take-outbox stepped)]
                         (reset! box next)
                         (doseq [entry entries]
                           (swap! out conj {:event (get entry driver/event-key)
                                            :text (get entry driver/text-key)}))))
                     5)]
         {:box box
          :out out
          :stop! (fn [] (js/clearInterval timer))}))


     (defn- published
       "Substring over published texts, for notices whose shape is fixed."
       [client needle]
       (some (fn [entry] (str/includes? (str (:text entry)) needle)) @(:out client)))


     (defn- published-exactly
       "Exact text equality, for answers a substring cannot tell apart from a
        served port's digits."
       [client expected]
       (some (fn [entry] (= expected (:text entry))) @(:out client)))


     (deftest a-node-client-attaches-to-a-dart-server
       (cljs.test/async done
                        (if-not (.existsSync (js/require "fs") dart-peer-exe)
                          (do (println ";; SKIPPED a-node-client-attaches-to-a-dart-server:"
                                       dart-peer-exe
                                       "is absent — run bb build:yin-repl-v2-peer (or bb test) first")
                              (done))
                          (free-port!
                            (fn [port]
                              (let [child-process (js/require "child_process")
                                    server (.spawn child-process dart-peer-exe
                                                   #js ["--serve" "127.0.0.1" (str port)]
                                                   #js {:stdio #js ["pipe" "pipe" "pipe"]})
                                    server-lines (atom [])
                                    server-errors (atom [])
                                    client (boot-client!)
                                    finish! (fn []
                                              ((:stop! client))
                                              (.kill ^js server)
                                              (done))]
                                (line-reader (.-stdout server) #(swap! server-lines conj %))
                                (line-reader (.-stderr server) #(swap! server-errors conj %))
                                (-> (poll-until #(some (fn [line] (str/includes? line "Serving"))
                                                       @server-lines)
                                                cross-bind-ms
                                                (str "the Dart server never bound; its output was "
                                                     (pr-str @server-errors)))
                                    (.then (fn [_]
                                             (driver/submit-line!
                                               (:input @(:box client))
                                               (str "(connect \"daostream:ws://127.0.0.1:" port "/repl\")"))
                                             (poll-until #(published client "Connected to")
                                                         cross-event-ms
                                                         "the Node client never observed /established")))
                                    (.then (fn [_]
                                             (driver/submit-line! (:input @(:box client)) "(+ 40 2)")
                                             (poll-until #(published-exactly client "42")
                                                         cross-event-ms
                                                         "the cross-host evaluation never arrived")))
                                    (.then (fn [_]
                                             (is (published-exactly client "42")
                                                 "the wire round trip must not depend on either host")
                                             (.write ^js (.-stdin server)
                                                     (str (transit/encode {:cmd :stop}) "\n"))
                                             (poll-until (fn []
                                                           (= :ended (:status (connect/summary
                                                                                (:connection @(:box client))))))
                                                         cross-event-ms
                                                         "the client never observed the served stream ending")))
                                    (.then (fn [_]
                                             (is (= :ended
                                                    (:status (connect/summary (:connection @(:box client)))))
                                                 ":ws/ended, not :ws/closed, is what stop! deposits")
                                             (is (nil? (published client "Disconnected from"))
                                                 "a detach would mean the socket closed without the stream ending")))
                                    (.then (fn [_] nil)
                                           (fn [error]
                                             (is false (str "cross-host slice failure: "
                                                            (pr-str error)))))
                                    (.then (fn [_] (finish!))))))))))))
