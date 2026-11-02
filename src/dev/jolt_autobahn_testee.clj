;; The J6f Autobahn conformance testee: the production jolt WebSocket host
;; adapter (dao.stream.ws.jolt, J6e) on its own driver loop, in the
;; standalone C14 shape -- no REPL composition, no yin.repl.host shadow, and
;; :codecs nil so any or no subprotocol is accepted. The only test hook is
;; the echo: decoded text/binary messages are echoed at the adapter seam
;; before any codec admission (none is composed here), so the same native
;; frame parser, UTF-8 validator, masking, fragmentation, close and
;; socket/poller machinery as production is what the suite exercises
;; (tools/jolt-autobahn/README.md §2).
;;
;; Launch contract (harness.py run, argv after --): J6_ROLE, J6_PORT,
;; J6_URL, J6_AGENT, J6_CASE_INDEXES, J6_MODE, J6_FRAME_LIMIT and
;; J6_MESSAGE_LIMIT. The declared limits are applied on the adapter: the
;; listener's :ws/max-frame-bytes takes the frame limit and feeds the
;; parser's message bound too (the adapter has one knob there; the suite's
;; two declarations are equal at 64 MiB), and the dialer side applies the
;; frame limit per connection. Reported on the startup line.
;;
;; Readiness (J6 plan §5): the server-role testee prints "READY <port>" on
;; stdout only after the adapter deposits :bind-succeeded inside bind! (the
;; kernel is accepting by then), or "FAILED <reason>" and exits nonzero; a
;; launcher that reads READY before publishing the port cannot hand the
;; harness's wait_port a tunnel that connects before the destination
;; listens. The client role binds nothing; readiness belongs to the
;; harness's own fuzzing server. J6_DELAY_BIND_MS (sleep before bind!) and
;; J6_FORCE_BIND_FAIL (bind port 1, EACCES as non-root) exist so a
;; launcher's readiness barrier is testable end to end.
;;
;; The server role stays alive until the harness stops its process group:
;; jolt has no signal hook, so the harness's SIGTERM is the defined stop
;; and the kernel reclaims the sockets; the clean unbind!/close-host!
;; shutdown path is J6e's C7 gate, not this harness path. The client role
;; walks J6_CASE_INDEXES one connection at a time (each /runCase until its
;; terminal), then /updateReports, awaits its terminal and exits zero.
;;
;; Run under the repo root:
;;   jolt -Srepro -M:jolt src/dev/jolt_autobahn_testee.clj
;; (bb autobahn:jolt supplies this plus the harness; tools/jolt-autobahn/
;; launch.sh and remote-testee.sh are the two launch routes.)

(ns jolt-autobahn-testee
  "The Autobahn echo testee over dao.stream.ws.jolt. Prints READY/FAILED
   for the launcher barrier; every other line is evidence for testee.log.")


(require '[clojure.string :as str]
         '[dao.stream.waitset.driver :as wake]
         '[dao.stream.ws.jolt :as wsj]
         '[jolt.io-poller :as poller])


(def ^:private cadence-ms 5)
(def ^:private case-timeout-ms 300000)


(defn- env
  ([name]
   (env name ""))
  ([name default]
   (or (System/getenv name) default)))


(defn- env-int
  [name default]
  (let [v (str/trim (env name))]
    (if (str/blank? v) default (Integer/parseInt v))))


(defn- say
  [& xs]
  (apply println xs)
  (flush))


(defn- fail!
  [& xs]
  (apply say xs)
  (System/exit 2))


(defn- echo-callbacks
  "The caller-supplied callback map in the dao.stream.ws/adapter key shape:
   the echo hook itself. Text answers text, binary answers binary, at the
   seam (before any codec admission; none is composed)."
  ([send] (echo-callbacks send (atom [])))
  ([send log]
   {:opened! #(swap! log conj [:opened])
    :message! (fn [text] (send text))
    :binary! (fn [octets] (send octets))
    :closed! (fn [code reason] (swap! log conj [:closed code reason]))
    :too-large! #(swap! log conj [:too-large])
    :ws/log log}))


(defn- rounds!
  "Owner rounds over `inst`'s pump until `(pred)` or `timeout-ms` pass
   (jolt.io-poller/clock-ms domain). Answers whether `pred` held. The
   deadline folding inside sleep-fds! wakes a round for the pump's own
   deadlines (upgrade, close, unbind) before the cadence."
  [w inst pred timeout-ms]
  (let [hp (wsj/host-pump inst)
        end (+ (poller/clock-ms) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (> (poller/clock-ms) end) false
        :else (do (wake/sleep-fds! w cadence-ms hp nil)
                  (recur))))))


;; =============================================================================
;; Server role: bind, echo, stay alive until stopped
;; =============================================================================


(defn- run-server
  [w inst port frame-limit message-limit bind-host]
  (let [deposits (atom 0)
        accept-all (fn [path seam _now]
                     (let [send (:send! seam)]
                       ;; every connection is accepted on any path; the
                       ;; production adapter handles the close handshake
                       {:ws/adapter (echo-callbacks send)}))
        deposit! (fn [kind value]
                   (swap! deposits inc)
                   (when (= :bind-succeeded kind)
                     ;; the barrier line: only after the adapter's own
                     ;; bind-succeeded deposit, inside bind!, kernel already
                     ;; accepting on the kernel-chosen or given port
                     (say "READY" (:port value)))
                   (when (#{:listener-error :upgrade-failed} kind)
                     (say "TESTEE-EVENT" (name kind) (pr-str value))))
        bind-port (if (= "1" (env "J6_FORCE_BIND_FAIL")) 1 port)
        bound (wsj/bind! inst {:bind-host bind-host
                               :bind-port bind-port
                               :codecs nil
                               :ws/max-frame-bytes frame-limit
                               :accept! accept-all
                               :deposit! deposit!})]
    (when-not (= :dao.stream/ok (:dao.stream/outcome bound))
      (say "FAILED" "bind" bind-port
           (if (= 1 bind-port) "forced bind failure (J6_FORCE_BIND_FAIL)" "transport-error"))
      (System/exit 1))
    (say "TESTEE serving role=server port=" port "frame-limit=" frame-limit
         "message-limit=" message-limit "codecs=nil subprotocol=any-or-none")
    (loop []
      (wake/sleep-fds! w cadence-ms (wsj/host-pump inst) nil)
      (recur))))


;; =============================================================================
;; Client role: walk the case indexes, then updateReports, then exit 0
;; =============================================================================


(defn- run-one-case
  "One connection to `path`: echo until its one terminal. Answers the
   [code reason] of the terminal, or nil on timeout."
  [w inst port path frame-limit]
  (let [seam-box (atom nil)
        log (atom [])
        terminal (atom nil)
        send (fn [payload]
               (when-let [seam @seam-box]
                 ((:send! seam) payload)))
        cb (assoc (echo-callbacks send log)
                  :ws/max-frame-bytes frame-limit
                  :closed! (fn [code reason]
                             (swap! log conj [:closed code reason])
                             (when (nil? @terminal)
                               (reset! terminal [code reason]))))
        seam (wsj/connect! inst {:ws/host "127.0.0.1", :ws/port port, :ws/path path}
                           cb)]
    (if (nil? seam)
      [:connect-refused]
      (do (reset! seam-box seam)
          (if (rounds! w inst #(some? @terminal) case-timeout-ms)
            @terminal
            (do (say "TESTEE-EVENT case-timeout" path)
                nil))))))


(defn- run-client
  [w inst port agent frame-limit]
  (let [indexes (str/split (str/trim (slurp (env "J6_CASE_INDEXES"))) #",")
        run (fn [path]
              (let [[code reason :as terminal] (run-one-case w inst port path frame-limit)]
                (when (or (nil? terminal) (= :connect-refused code))
                  (say "FAILED" "case" path (pr-str terminal))
                  (System/exit 1))
                (say "TESTEE-CASE" path "closed" code (pr-str reason))))
        _ (doseq [index indexes]
            (when-not (str/blank? index)
              (run (str "/runCase?case=" index "&agent=" agent))))
        _ (run (str "/updateReports?agent=" agent))]
    ;; let owed reports leave, then forced cleanup must find nothing
    (wake/sleep-fds! w cadence-ms (wsj/host-pump inst) nil)
    (wsj/close-host! inst)
    (say "TESTEE client done agent=" agent "cases=" (count indexes))
    (System/exit 0)))


;; =============================================================================
;; Entry
;; =============================================================================


(defn -main
  []
  (let [role (env "J6_ROLE")
        mode (env "J6_MODE")
        agent (env "J6_AGENT")
        port (env-int "J6_PORT" 0)
        frame-limit (env-int "J6_FRAME_LIMIT" 0)
        message-limit (env-int "J6_MESSAGE_LIMIT" 0)
        bind-host (env "J6_HOST" "127.0.0.1")]
    (when-not (#{"server" "client"} role) (fail! "FAILED bad-role" (pr-str role)))
    (when-not (re-matches #"[A-Za-z0-9_-]+" agent) (fail! "FAILED bad-agent" (pr-str agent)))
    (when-not (and (pos? frame-limit) (pos? message-limit))
      (fail! "FAILED limits-undeclared" frame-limit message-limit))
    (when-not (and (int? port) (<= 1024 port 65535))
      (fail! "FAILED bad-port" port))
    (when (and (= "client" role) (str/blank? (str/trim (env "J6_CASE_INDEXES"))))
      (fail! "FAILED no-case-indexes"))
    (when-let [delay-ms (env-int "J6_DELAY_BIND_MS" 0)]
      (when (pos? delay-ms)
        (say "TESTEE delaying bind" delay-ms "ms")
        (let [end (+ (poller/clock-ms) delay-ms)]
          (while (< (poller/clock-ms) end)
            (wake/sleep-fds! (wake/make-wake) 50 nil nil)))))
    (let [w (wake/make-wake)
          inst (wsj/create)]
      (say "TESTEE jolt role=" role "mode=" mode "agent=" agent
           "frame-limit=" frame-limit "message-limit=" message-limit
           "adapter=dao.stream.ws.jolt")
      (if (= "server" role)
        (run-server w inst port frame-limit message-limit bind-host)
        (run-client w inst port agent frame-limit)))))


(-main)
