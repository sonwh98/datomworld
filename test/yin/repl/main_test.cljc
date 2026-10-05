(ns yin.repl.main-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            #?@(:cljd [["dart:io" :as dart-io]]
                :clj [[clojure.java.io :as io]
                      [yin.repl.host :as host]]
                :cljs [[yin.repl.connect :as connect]])
            [dao.stream :as stream]
            [dao.stream.rpc :as rpc]
            [dao.stream.transit :as transit]
            [dao.stream.ws :as ws]
            [dao.stream.ws-project :as ws-project]
            [yin.repl.main :as repl]
            [yin.repl.dht :as repl.dht]
            [yin.repl :as shell]
            [yin.repl.driver :as driver]
            [yin.repl.host.common :as host-common]
            [yin.repl.serve :as serve]
            [yin.vm.linker.sign :as sign]))


;; =============================================================================
;; Host scratch paths for the startup-contract tests below.
;; =============================================================================

(defn- temp-dir
  []
  (str "target/test-main-index-" (random-uuid)))


(defn- temp-file
  []
  (str "target/test-main-blocker-" (random-uuid) ".tmp"))


(defn- write-file!
  [path]
  #?(:cljd (let [f (dart-io/File. path)]
             (when-not (.existsSync (.-parent f))
               (.createSync (.-parent f) .recursive true))
             (.writeAsStringSync f ""))
     :clj (do (.mkdirs (.getParentFile (java.io.File. path)))
              (spit path ""))
     :cljs (let [fs (js/require "fs")
                 path-module (js/require "path")]
             (.mkdirSync fs (.dirname path-module path) #js {:recursive true})
             (.writeFileSync fs path ""))))


(defn- cleanup-file!
  [path]
  #?(:cljd (try (let [f (dart-io/File. path)]
                  (when (.existsSync f) (.deleteSync f)))
                (catch Object _ nil))
     :clj (let [f (java.io.File. path)]
            (when (.exists f) (.delete f)))
     :cljs (try (.unlinkSync (js/require "fs") path)
                (catch :default _ nil))))


(defn- cleanup-dir!
  [dir]
  #?(:cljd (try (.deleteSync (dart-io/Directory. dir) .recursive true)
                (catch Object _ nil))
     :clj (let [f (java.io.File. dir)]
            (when (.isDirectory f)
              (doseq [child (.listFiles f)]
                (.delete child))
              (.delete f)))
     :cljs (try (.rmSync (js/require "fs") dir #js {:recursive true :force true})
                (catch :default _ nil))))


(defn- refusal-of
  "The error `thunk` throws, or nil when it does not.  The error itself,
   not its message: on the shadow-cljs test build, `(str (this-helper …))`
   at an assertion site is compile-time evaluated and its constant
   embedded, so a refusal's message would never be checked at runtime —
   `(ex-message (refusal-of …))`, with the helper one level down, is the
   shape that runs."
  [thunk]
  (try (thunk)
       nil
       (catch #?(:cljd Object
                 :clj Exception
                 :cljs :default)
              e
         e)))


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
  (let [opts (repl/parse-args ["--port" "8080" "--headless"])]
    (is (= 8080 (:port opts)))
    (is (true? (:headless? opts)))
    (is (empty? (:rejected opts))))
  (testing "--host is refused, not silently ignored"
    (is (str/includes? (ex-message (refusal-of #(repl/parse-args
                                                  ["--port" "8080" "--host"
                                                   "127.0.0.1"])))
                       "--host is gone")))
  (testing "the advertised address prefers the LAN over a tunnel interface"
    (is (= "192.168.1.2" (repl/pick-ip ["198.18.0.1" "192.168.1.2"])))
    (is (= "10.0.0.5" (repl/pick-ip ["198.18.0.1" "10.0.0.5"])))
    (is (= "172.20.1.1" (repl/pick-ip ["172.32.0.1" "172.20.1.1"])))
    (is (= "198.18.0.1" (repl/pick-ip ["198.18.0.1"])))
    (is (= "127.0.0.1" (repl/pick-ip []))))
  (testing "--port serves on all interfaces and the banner says so"
    (is (= "0.0.0.0" repl/bind-all-host))
    (let [banner (str/join "\n" (repl/banner (repl/parse-args ["--port" "8080"])))]
      (is (str/includes? banner "ws://127.0.0.1:8080"))
      (is (str/includes? banner (str "ws://" (repl/local-ip) ":8080")))
      (is (str/includes? banner "no authentication"))))
  (testing "telemetry is rejected rather than ignored"
    (let [opts (repl/parse-args ["--telemetry-stream" "daostream:ws://x" "--telemetry"])]
      (is (= ["--telemetry-stream" "--telemetry"] (:rejected opts)))
      (is (str/includes? (first (repl/banner opts))
                         "yin.vm.telemetry.implementation-plan.md"))
      (is (not (str/includes? (first (repl/banner opts)) "yin.repl"))))))


;; =============================================================================
;; The durable index store's startup contract: one flag, parsed by the one
;; shared parser every host's -main uses, resolved once before any shell,
;; server, or host loop composes (yin.repl.store).
;; =============================================================================

(deftest the-index-store-flag-parses-on-every-hosts-arguments
  (testing "omission means mem, today's behaviour"
    (is (= :mem (:index-store-spec (repl/parse-args [])))))
  (testing "the flag's values parse to specs"
    (is (= :mem (:index-store-spec (repl/parse-args ["--index-store" "mem"]))))
    (is (= {:type :file :dir "idx"}
           (:index-store-spec (repl/parse-args ["--index-store" "file:idx"])))))
  (testing "a missing value, an unknown scheme, and an empty dir are refused"
    (is (str/includes? (ex-message (refusal-of #(repl/parse-args
                                                  ["--index-store"])))
                       "--index-store needs a value"))
    (is (str/includes? (ex-message (refusal-of
                                     #(repl/parse-args
                                        ["--index-store" "bogus"])))
                       "Unknown --index-store"))
    (is (str/includes? (ex-message (refusal-of
                                     #(repl/parse-args
                                        ["--index-store" "file:"])))
                       "--index-store file:<dir> needs a directory"))))


(deftest startup-composes-or-refuses-before-any-shell-or-server
  (testing "no flag composes the memory store of today"
    (let [started (repl/startup [])]
      (is (nil? (:refusal started)))
      (is (= :mem (get-in started [:state :repl :index-store-spec])))
      (is (some? (get-in started [:state :input])))
      (is (nil? (:server started)) "no port was asked for")))
  (testing "every refused spec composes nothing"
    (doseq [args [["--index-store"]
                  ["--index-store" "bogus"]
                  ["--index-store" "file:"]]]
      (let [started (repl/startup args)]
        (is (string? (:refusal started)) (pr-str args))
        (is (nil? (:state started)) (pr-str args))
        (is (nil? (:server started)) (pr-str args))
        (is (str/includes? (:refusal started) "--index-store") (pr-str args)))))
  (testing "an unopenable directory refuses startup the same way"
    (let [blocker (temp-file)]
      (try
        (write-file! blocker)
        (let [started (repl/startup ["--index-store" (str "file:" blocker)])]
          (is (str/includes? (:refusal started) "not a directory"))
          (is (nil? (:state started))))
        (finally
          (cleanup-file! blocker)))))
  (testing "a valid file:<dir> composes a shell whose store is that file"
    (let [dir (temp-dir)]
      (try
        (let [started (repl/startup ["--index-store" (str "file:" dir)])]
          (is (nil? (:refusal started)))
          (is (= {:type :file :dir dir}
                 (get-in started [:state :repl :index-store-spec]))))
        (finally
          (cleanup-dir! dir))))))


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
        (is (= ["42"] (mapv :yin.repl.driver/text entries)))))))


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


;; =============================================================================
;; L4: the publisher's key file (docs/design/yin.vm.linker.dht.md 6.5)
;; =============================================================================

(defn- write-text!
  [path text]
  #?(:cljd (let [f (dart-io/File. path)]
             (when-not (.existsSync (.-parent f))
               (.createSync (.-parent f) .recursive true))
             (.writeAsStringSync f text))
     :clj (do (.mkdirs (.getParentFile (java.io.File. path)))
              (spit path text))
     :cljs (let [fs (js/require "fs")
                 path-module (js/require "path")]
             (.mkdirSync fs (.dirname path-module path) #js {:recursive true})
             (.writeFileSync fs path text))))


(defn- read-text
  [path]
  #?(:cljd (.readAsStringSync (dart-io/File. path))
     :clj (slurp path)
     :cljs (.readFileSync (js/require "fs") path "utf8")))


(defn- key-start
  "Startup over a fresh dht:<dir> with `--dht-key path`; the directory is
   removed afterwards."
  [path]
  (let [dir (temp-dir)]
    (try
      (let [started (repl/startup ["--index-store" (str "dht:" dir) "--dht-key" path])]
        (some-> (:state started) repl/close-index-store!)
        started)
      (finally
        (cleanup-dir! dir)))))


(deftest a-key-file-loads-the-stable-key-or-refuses-startup-with-its-reason
  (let [key (sign/generate)
        principal (sign/principal (:public key))
        good (temp-file)
        malformed (temp-file)
        mismatched (temp-file)
        absent (temp-file)]
    (try
      (write-text! good (sign/key-text key))
      (write-text! malformed "{:version 1 :algorithm :ed25519")
      (write-text! mismatched (sign/key-text (assoc key :public
                                                    (:public (sign/generate)))))
      (testing "a well-formed file is the node's key; the banner prints the principal"
        (let [started (key-start good)]
          (is (nil? (:refusal started)))
          (is (= key (get-in started [:state :repl :dht-key])))
          (is (some #(str/includes? % principal) (repl/banner (:opts started))))
          (is (not-any? #(str/includes? % (:seed key))
                        (repl/banner (:opts started))))))
      (doseq [[path reason] [[absent "does not exist"]
                             [malformed "malformed-key"]
                             [mismatched "public-mismatch"]]]
        (testing reason
          (let [started (key-start path)]
            (is (nil? (:state started)) "nothing falls back to a fresh key")
            (is (str/includes? (str (:refusal started)) reason) (:refusal started))
            (is (str/includes? (str (:refusal started)) path)))))
      (testing "the seed of a refused file appears in no output"
        (is (not (str/includes? (str (:refusal (key-start mismatched)))
                                (:seed key)))))
      (testing "--dht-key needs the dht store"
        (is (str/includes? (str (:refusal (repl/startup ["--dht-key" good])))
                           "dht:<dir>")))
      (finally
        (run! cleanup-file! [good malformed mismatched absent])))))


(deftest keygen-writes-a-new-key-file-and-never-overwrites-one
  (let [path (temp-file)]
    (try
      (let [made (repl/startup ["--dht-keygen" path])
            text (read-text path)
            key (sign/key-from-text text)]
        (is (nil? (:refusal made)))
        (is (nil? (:state made)) "keygen composes no shell: it writes and exits")
        (is (= 0 (:exit made)))
        (is (string? (:seed key)) "the file is a well-formed key")
        (is (some #(str/includes? % (sign/principal (:public key))) (:lines made)))
        (is (not-any? #(str/includes? % (:seed key)) (:lines made))
            "the seed is never printed")
        (testing "a host that cannot set the file's permissions says what to do"
          (is (= #?(:cljd true :default false)
                 (boolean (some #(str/includes? % (str "chmod 600 " path))
                                (:lines made))))))
        (testing "a second keygen refuses to overwrite it"
          (let [again (repl/startup ["--dht-keygen" path])]
            (is (str/includes? (str (:refusal again)) "exists"))
            (is (= text (read-text path)) "the file is unchanged"))))
      (finally
        (cleanup-file! path)))))


(deftest declared-principals-are-strict-hex-and-reach-the-composition
  (let [public (:public (sign/generate))
        dir (temp-dir)]
    (try
      (let [started (repl/startup ["--index-store" (str "dht:" dir)
                                   "--dht-principal" public])]
        (is (= [public] (get-in started [:state :repl :link-source :principals])))
        (repl/close-index-store! (:state started)))
      (doseq [bad [(str/upper-case public) (subs public 1) (str public "0") "xyz"]]
        (is (str/includes? (str (:refusal (repl/startup ["--index-store" (str "dht:" dir)
                                                         "--dht-principal" bad])))
                           "--dht-principal")
            bad))
      (finally
        (cleanup-dir! dir)))))


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


(defn- answer-values
  "Every answer appended to the endpoint's shared answers medium, oldest
   first."
  [server]
  (loop [cursor (:dao.stream/cursor (stream/cursor (:answers server)
                                                   stream/anchor-oldest))
         acc []]
    (let [r (stream/next (:answers server) cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(defn- remote-request!
  "Append one `:op/eval` request directly onto the endpoint's requests
   medium, as the mirror's own `:dao.stream/append!` answer would once a
   reflection's write reaches it."
  [server id source]
  (stream/append! (:requests server) (rpc/request-value id :op/eval [source])))


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
    (is (contains? (ws-project/sessions (:acceptor server)) (:ws/attachment accepted)))
    (driver/submit-line! (:input state) "(defn twice [x] (* 2 x))")
    (let [[state server lines] (repl/step-all state server 5)]
      (is (str/includes? (str/join " " lines) ":closure")
          "the local prompt defined the function against its own shell")
      (testing "a definition typed at the local prompt answers a remote request"
        (remote-request! server 0 "(twice 21)")
        (let [[state server _] (repl/step-all state server 6)
              response (last (answer-values server))]
          (is (= 0 (rpc/answer-id response)))
          (is (= "42" (rpc/answer-ok response))
              "the endpoint must evaluate against the shell the local prompt shares, not a private one")
          (testing "a remote definition answers the local prompt on a later tick"
            (remote-request! server 1 "(def answer 7)")
            (let [[state server _] (repl/step-all state server 7)]
              (is (= "7" (rpc/answer-ok (last (answer-values server)))))
              (driver/submit-line! (:input state) "answer")
              (let [[_state _server lines] (repl/step-all state server 8)]
                (is (some #(= "7" %) lines)
                    "the local prompt must see what a remote client defined")))))))))


;; =============================================================================
;; Phase R5 — the slice, end to end, across two operating-system processes
;;
;; This namespace is process A: the headless server, composed through the real
;; `serve!` and stepped by one ticker thread exactly as `-main --headless`
;; steps it.  Process B is `yin.repl.slice-peer`, spawned as a real child
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
     ;; Process A: one headless REPL endpoint
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
                            "yin-repl-slice-server")
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
        "clojure.main" "-m" "yin.repl.slice-peer"])


     (def ^:private dart-peer-exe "build/yin-repl-peer")


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
                                         #(= :yin.repl.connect/connected (:event %))
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
                                        #(= :yin.repl.driver/response (:event %))
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

     (deftest
       ^:slow evaluation-round-trips-and-two-clients-get-their-own-answers
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
                                       #(= :yin.repl.connect/connected (:event %))
                                       event-ms))
                   "the reattachment never established")
               (let [after (ask! peer {:cmd :probe} :probe reply-ms)]
                 (is (false? (:traffic-retained? after))
                     "a reattachment composes a fresh dial with a fresh cursor
                      (dao.stream.ws-project/dial's own contract), never
                      reusing the old one")
                 (is (not= first-attachment
                           (get-in after [:connection :attachment]))
                     "a new attachment id should name the new boundary"))
               (is (= "10" (remote-value peer "x"))
                   "the shared shell the served stream owns did not survive")))
           (finally
             (reset! (:paused a) false)
             (stop-peer! peer)))))


     (deftest ^:slow stopping-the-endpoint-ends-the-served-stream-not-closes-it
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
                                         #(= :yin.repl.connect/ended (:event %))
                                         event-ms))
                     (str "no ended event arrived; events were "
                          (pr-str @(:seen peer))))
                 (is (nil? (seen peer
                                 #(= :yin.repl.connect/detached (:event %))))
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
     ;; The Dart peer is one program in two roles (`build/yin-repl-peer`,
     ;; from `bb build:yin-repl-peer`): the client role here, the server
     ;; role for the Node parent in this namespace's cljs half.  A lane run
     ;; without the exe skips loudly rather than failing or passing silently.
     ;; =========================================================================

     (deftest a-dart-client-attaches-to-this-jvm-server
       ;; Cross-host pair 1: cljd client against a JVM server.
       (if-not (.exists (io/file dart-peer-exe))
         (println ";; SKIPPED a-dart-client-attaches-to-this-jvm-server:"
                  dart-peer-exe "is absent — run bb build:yin-repl-peer (or bb test) first")
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

     (def ^:private dart-peer-exe "build/yin-repl-peer")


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
                                       "is absent — run bb build:yin-repl-peer (or bb test) first")
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


;; =============================================================================
;; The tick owner's cadence bit (W4): what keeps a shell off the idle curve
;; =============================================================================


(deftest moved?-is-true-for-lines-pending-writes-and-endpoint-movement
  (let [state (repl/boot {})
        idle (repl/step-all state nil 0)]
    (is (false? (repl/moved? (first idle) (second idle) (nth idle 2)))
        "a round that printed nothing and owes nothing may idle"))
  (testing "lines to print move the round"
    (let [state (repl/boot {})]
      (driver/submit-line! (:input state) "(+ 1 2)")
      (let [[state' server' lines] (repl/step-all state nil 0)]
        (is (true? (repl/moved? state' server' lines))))))
  (testing "an endpoint holding a pending response never idles"
    (let [server (serve/serve! {:bind-port 8080 :host (host-adapter)})
          [state server _] (repl/step-all (repl/boot {}) server 0)
          s (socket)
          accepted (ws/accept-connection! (:ws-endpoint server)
                                          (:path server)
                                          (:socket s)
                                          2)
          [_ server _] (repl/step-all state server 3)
          [state server _] (repl/step-all state server 4)]
      (is (some? (:ws/handle accepted)))
      (remote-request! server 0 "(+ 1 2)")
      ;; Nothing has stepped yet to notice the appended request, so the
      ;; endpoint's own moved? — one input to the owner's bit — holds.
      (is (false? (serve/moved? server))
          "an empty idle endpoint reports no movement")
      (let [[_ server' _] (repl/step-all state server 5)]
        (is (= "3" (rpc/answer-ok (last (answer-values server'))))
            "sanity: the request was served in the same step")
        (is (true? (serve/moved? server'))
            "the round that served moved — a probe woke and a notice printed")
        (let [[_ server'' _] (repl/step-all state server' 6)]
          (is (false? (serve/moved? server''))
              "the next round owes nothing, so the endpoint may idle"))))))


;; =============================================================================
;; All four evaluators behind the same dao.stream boundary
;; =============================================================================

(def ^:private vm-types [:ast-walker :semantic :stack :register])


(defn- run-lines
  "Submit each line to the input medium and step the driver once per line,
   returning the final driver state and the published texts in order."
  [state lines]
  (reduce (fn [[state texts] [tick line]]
            (driver/submit-line! (:input state) line)
            (let [[entries state'] (driver/take-outbox
                                     (driver/repl-step state tick))]
              [state' (into texts (map :yin.repl.driver/text) entries)]))
          [state []]
          (map-indexed vector lines)))


(defn- output-elements
  "Every element on a shell's output medium, read from its oldest one."
  [repl-state]
  (let [output (:output-stream repl-state)]
    (loop [cursor (:dao.stream/cursor
                    (stream/cursor output :dao.stream/oldest))
           values []]
      (let [result (stream/next output cursor)]
        (if (= :dao.stream/ok (:dao.stream/outcome result))
          (recur (:dao.stream/cursor result)
                 (conj values (:dao.stream/value result)))
          values)))))


(deftest every-vm-is-selectable-and-answers-through-the-streams
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[state texts]
            (run-lines (driver/create-state {:host (host-adapter)})
                       [(str "(vm " vm-type ")")
                        "(+ 1 2)"
                        "((fn [x] (* x x)) 7)"
                        "(let [x 2 y 3] (* x y))"
                        "(if (< 1 2) :yes :no)"
                        "(println \"hi\" 1)"
                        "(def inc100 (fn [x] (+ x 100)))"
                        "(inc100 1)"
                        (str "(defn sum-to [n]"
                             " (if (= n 0) 0 (+ n (sum-to (- n 1)))))")
                        "(sum-to 10)"])]
        (is (= [(str "Switched to " (get shell/vm-labels vm-type)
                     " (store cleared)")
                "3" "49" "6" ":yes" "hi 1\nnil"]
               (subvec texts 0 6)))
        (is (= ["101" "55"] [(nth texts 7) (nth texts 9)])
            "a function defined by one input is called by a later one")
        (is (= vm-type (:vm-type (:repl state))))))))


(deftest results-leave-the-vm-on-the-output-medium
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[state _] (shell/eval-input (shell/create-state {:vm-type vm-type})
                                        "(do (print \"a\") (+ 20 22))")]
        (is (= [{:type :repl/output :op :print :text "a"}
                {:type :repl/result
                 :round [(:shell-token state) 1]
                 :value 42}]
               (output-elements state))
            "the value follows the round's prints on the same stream")))))


(deftest value-history-commands-and-error-recovery-on-every-vm
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [texts (second
                    (reduce (fn [[state texts] line]
                              (let [[state' text] (shell/eval-input state line)]
                                [state' (conj texts text)]))
                            [(shell/create-state {:vm-type vm-type}) []]
                            ["1" "2" "3"
                             "(+ (* 100 *3) (* 10 *2) *1)"
                             "(undefined-thing 1)"
                             "(+ *1 1)"
                             "(help)"
                             "(repl-state)"
                             "(reset)"
                             "*1"]))]
        (is (= "123" (nth texts 3)) "*1, *2, and *3 name the last three")
        (is (str/starts-with? (nth texts 4) "Error: "))
        (is (= "124" (nth texts 5))
            "a failed input neither breaks the shell nor enters the history")
        (is (str/includes? (nth texts 6) ":stack | :register"))
        (is (str/includes? (nth texts 7) (str ":type " vm-type)))
        (is (= (str (get shell/vm-labels vm-type) " reset") (nth texts 8)))
        (is (= "nil" (nth texts 9)) "(reset) clears the value history")))))


;; =============================================================================
;; The subcommand surface: keygen and dht init|serve|join expand to the
;; legacy flags.
;; =============================================================================

(def ^:private hex64 (apply str (repeat 64 "a")))


(deftest dht-join-expands-a-token-to-peer-principal-and-follow
  (let [token (str "yin:127.0.0.1:4001/" hex64)
        [args extra] (repl/expand-args ["dht" "join" token "--dir" "d"
                                        "--listen" "4003"])
        opts (repl/parse-args args)
        spec (:index-store-spec opts)]
    (is (= [{:host "127.0.0.1" :port 4001}] (:peers spec)))
    (is (nil? (:manifest spec)) "join passes no --dht-manifest")
    (is (not (some #{"--dht-manifest"} args)))
    (is (= [{:principal hex64 :host "127.0.0.1" :port 4001}] (:follow spec)))
    (is (= 4003 (:bind-port spec)))
    (is (= [hex64] (:principals opts)))
    (is (not (:publish? spec)))
    (is (= #{"--dht-publish" "--dht-key"} (:unset extra)))))


(deftest a-printed-join-token-is-what-join-parses
  (let [token (repl.dht/join-token "127.0.0.1" 4001 hex64)]
    (is (= (str "yin:127.0.0.1:4001/" hex64) token))
    (is (= ["127.0.0.1:4001" hex64] (repl/parse-token token)))
    (is (= ["127.0.0.1:4001" hex64]
           (repl/parse-token (str "yin:127.0.0.1:4001/ed25519:" hex64)))
        "the principal accepts its ed25519: prefix")
    (is (= (:index-store-spec
             (repl/parse-args ["--index-store" "dht:d" "--dht-peer"
                               "127.0.0.1:4001" "--dht-principal" hex64
                               "--dht-follow" (str hex64 "@127.0.0.1:4001")]))
           (:index-store-spec
             (repl/parse-args (first (repl/expand-args
                                       ["dht" "join" token "--dir" "d"]))))))))


(deftest a-token-with-a-third-part-is-refused-with-the-pin-message
  (doseq [token [(str "yin:127.0.0.1:4001/" hex64 "/segment/blake3-" hex64)
                 (str "yin:127.0.0.1:4001/" hex64 "/")]]
    (is (thrown-with-msg? #?(:cljd Object :clj Exception :cljs js/Error)
                          #"a manifest is a pin and belongs to --dht-manifest"
          (repl/parse-token token))
        token)
    (testing "and startup answers it as a refusal, data"
      (is (re-find #"belongs to --dht-manifest"
                   (str (:refusal (repl/startup ["dht" "join" token
                                                 "--dir" "d"]))))))))


(deftest dht-follow-parses-a-principal-at-a-loopback-address
  (is (= {:principal hex64 :host "127.0.0.1" :port 4001}
         (repl/parse-follow (str hex64 "@127.0.0.1:4001"))))
  (is (= {:principal hex64 :host "127.0.0.1" :port 4001}
         (repl/parse-follow (str hex64 "@localhost:4001"))))
  (is (= {:principal hex64 :host "::1" :port 4001}
         (repl/parse-follow (str hex64 "@[::1]:4001"))))
  (doseq [[text re] [[(str hex64 "@10.0.0.2:4001") #"loopback only"]
                     [(str hex64 "@127.0.0.1:0") #"port from 1 to 65535"]
                     [(str hex64 "@127.0.0.1:99999") #"port from 1 to 65535"]
                     ["abc@127.0.0.1:4001" #"takes <64 lowercase hex>"]
                     [(str hex64 "127.0.0.1:4001") #"takes <64 lowercase hex>"]
                     [nil #"takes <64 lowercase hex>"]]]
    (is (thrown-with-msg? #?(:cljd Object :clj Exception :cljs js/Error) re
          (repl/parse-follow text))
        (pr-str text))))


(deftest dht-follow-of-an-undeclared-principal-refuses-startup
  (let [follow (str hex64 "@127.0.0.1:4001")
        base ["--index-store" "dht:d" "--dht-peer" "127.0.0.1:4001"]]
    (is (re-find #"not declared: add --dht-principal"
                 (str (:refusal (repl/startup (into base ["--dht-follow"
                                                          follow])))))
        "a followed principal must be declared")
    (is (re-find #"needs a --dht-peer"
                 (str (:refusal (repl/startup ["--index-store" "dht:d"
                                               "--dht-principal" hex64
                                               "--dht-follow" follow])))))
    (is (re-find #"each principal once"
                 (str (:refusal (repl/startup
                                  (into base ["--dht-principal" hex64
                                              "--dht-follow" follow
                                              "--dht-follow"
                                              (str hex64 "@127.0.0.1:4002")]))))))
    (is (re-find #"need --index-store dht:<dir>"
                 (str (:refusal (repl/startup ["--dht-follow" follow]))))
        "following means nothing to another store")
    (testing "a declared principal composes the spec"
      (is (= [{:principal hex64 :host "127.0.0.1" :port 4001}]
             (get-in (repl/parse-args (into base ["--dht-principal" hex64
                                                  "--dht-follow" follow]))
                     [:index-store-spec :follow]))))))


(deftest dht-manifest-suspends-following-for-the-run
  (let [follow (str hex64 "@127.0.0.1:4001")
        manifest (str ":segment/blake3-" hex64)
        opts (repl/parse-args ["--index-store" "dht:d"
                               "--dht-peer" "127.0.0.1:4001"
                               "--dht-principal" hex64 "--dht-follow" follow
                               "--dht-manifest" manifest])
        spec (:index-store-spec opts)]
    (is (nil? (:follow spec)) "a pinned run follows nothing")
    (is (true? (:follow-suspended spec)))
    (is (some #(str/includes? % "following is suspended") (repl/banner opts))
        "and the banner says so")))


(deftest dht-init-publishes-with-a-key-and-localhost-peer
  (let [[args extra] (repl/expand-args ["dht" "init" "--dir" "d" "--key" "k"
                                        "--peer" "localhost:4002"
                                        "--listen" "4001"])
        opts (repl/parse-args args)]
    (is (= "k" (:new-key extra)))
    (is (nil? (:unset extra)) "init publishes, so it unsets nothing")
    (is (= "k" (:dht-key-file opts)))
    (is (true? (get-in opts [:index-store-spec :publish?])))
    (is (= [{:host "127.0.0.1" :port 4002}]
           (get-in opts [:index-store-spec :peers])))))


(deftest dht-serve-fetches-only
  (let [[args extra] (repl/expand-args ["dht" "serve" "--dir" "d" "--peer"
                                        "127.0.0.1:4001" "--listen" "4002"])
        spec (:index-store-spec (repl/parse-args args))]
    (is (nil? (:new-key extra)))
    (is (= #{"--dht-publish" "--dht-key"} (:unset extra))
        "serve clears a saved --dht-publish and --dht-key")
    (is (false? (:publish? spec)))
    (is (= 4002 (:bind-port spec)))))


(deftest bad-token-and-verb-are-refused-with-usage
  (is (thrown-with-msg? #?(:cljd Object :clj Exception :cljs js/Error)
                        #"join token looks like"
        (repl/expand-args ["dht" "join" "nonsense" "--dir" "d"])))
  (is (thrown-with-msg? #?(:cljd Object :clj Exception :cljs js/Error)
                        #"usage: yin-repl dht"
        (repl/expand-args ["dht" "frob"]))))


(deftest keygen-subcommand-takes-a-file
  (is (= ["--dht-keygen" "k.key"] (first (repl/expand-args ["keygen" "k.key"])))))


(deftest help-answers-the-usage-and-exits-zero-without-composing
  (doseq [args [["--help"] ["-h"] ["dht" "init" "--help"]
                ["--port" "8080" "-h"]]]
    (let [started (repl/startup args)]
      (is (= 0 (:exit started)) (pr-str args))
      (is (= repl/help-lines (:lines started)))
      (is (not (contains? started :state))))))


(deftest help-documents-every-flag-and-subcommand
  (let [text (str/join "\n" repl/help-lines)]
    (doseq [word ["--vm" "--reset" "--no-state" "state.edn" "--port"
                  "--headless" "--index-store" "--help"
                  "--dht-peer" "--dht-publish" "--dht-bind" "--dht-port"
                  "--dht-max-inbound-bytes" "--dht-manifest" "--dht-key"
                  "--dht-principal" "--dht-follow" "heads.edn"
                  "--dht-keygen" "--name" "--dir" "--key"
                  "--listen" "--peer" "--telemetry" "dht init" "dht serve"
                  "dht join" "keygen"]]
      (is (str/includes? text word) word))))


(deftest an-oversized-number-is-a-refusal-on-every-numeric-flag
  (let [huge "9999999999999999999999999999"
        follow (str hex64 "@127.0.0.1:" huge)
        base ["--index-store" "dht:d" "--dht-peer" "127.0.0.1:4001"]]
    (doseq [[args re]
            [[(into base ["--dht-principal" hex64 "--dht-follow" follow])
              #"--dht-follow takes a port from 1 to 65535"]
             [(into base ["--dht-principal" hex64 "--dht-follow"
                          (str hex64 "@127.0.0.1:70000")])
              #"--dht-follow takes a port from 1 to 65535"]
             [["--index-store" "dht:d" "--dht-peer" (str "127.0.0.1:" huge)]
              #"--dht-peer takes host:port with a port from 1 to 65535"]
             [(into base ["--dht-port" huge]) #"--dht-port takes an integer"]
             [(into base ["--dht-max-inbound-bytes" huge])
              #"--dht-max-inbound-bytes takes an integer"]
             [["--port" huge] #"--port takes a port from 0 to 65535"]
             [["--port" "70000"] #"--port takes a port from 0 to 65535"]
             [["--port" "abc"] #"--port takes a port from 0 to 65535"]
             [["dht" "join" (str "yin:127.0.0.1:" huge "/" hex64) "--dir" "d"]
              #"--dht-peer takes host:port with a port from 1 to 65535"]
             [["dht" "join" (str "yin:127.0.0.1:4001/" hex64) "--dir" "d"
               "--listen" huge]
              #"--dht-port takes an integer"]]]
      (let [started (repl/startup args)]
        (is (re-find re (str (:refusal started))) (pr-str args started))
        (is (nil? (:state started)))))))
