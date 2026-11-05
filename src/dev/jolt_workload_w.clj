(ns jolt-workload-w
  "J6g Workload W harness and peer cross-host integration (BRIEF-j6g.md).
   Pre-registered criterion (v3 Section 2 A1, amended by v3.1 D4):
   One jolt process serving two peers on the same machine while the REPL evaluates
   a submitted loop each round; peer A sends one binary message at the
   composition's :ws/max-frame-bytes (4,194,304 bytes); peer B exchanges 1 KiB
   request/reply messages concurrently, up to 8 outstanding, at 10 Hz, for the
   whole of A's transfer and >= 60 s (>= 600 overlapping round trips; fewer than
   500 samples is an invalid run).
   Criterion: during A's transfer, B's round-trip p99 <= 250 ms, no reply lost;
   A's message delivered once; the REPL step count advances every owner round.

   Deliverable 1: The workload W harness and sampler.
   Deliverable 2: Peer cross-host rows (hosts that dial/listen, reachability
   refusals on the REPL path).
   Deliverable 3: REPL served-stream rejoin peer probe."
  (:require [clojure.string :as str]
            [dao.host.bytes :as bytes]
            [dao.host.text :as text]
            [jolt.io-poller :as poller]
            [dao.stream :as stream]
            [dao.stream.cbor :as cbor]
            [dao.stream.remote-channel :as remote-channel]
            [dao.stream.rpc :as rpc]
            [dao.stream.waitset.driver :as wake]
            [dao.stream.ws :as ws]
            [dao.stream.ws.jolt :as wsj]
            [dao.stream.ws.rfc6455 :as rfc6455]
            [dao.stream.ws.tcp :as tcp]
            [dao.stream.ws.upgrade :as upgrade]
            [jolt.ffi :as ffi]
            [jolt.socket.native :as native]
            [yin.repl.connect :as connect]
            [yin.repl.ast-index :as repl.ast-index]
            [yin.repl.driver :as driver]
            [yin.repl.host :as host]
            [yin.repl.index :as repl.index]
            [yin.repl.main :as repl.main]
            [yin.repl.serve :as serve]))


;; =============================================================================
;; Raw TCP helpers (probe sockets outside the adapter)
;; =============================================================================

(defn- bvec
  [bs]
  (mapv #(bit-and (int %) 0xFF) (vec bs)))


(defn- octets
  [v]
  (byte-array (map unchecked-byte v)))


(defn- ascii
  [v]
  (apply str (map char v)))


(defn- with-native
  [n f]
  (let [p (ffi/alloc n)]
    (try (f p)
         (finally (ffi/free p)))))


(defn- raw-peer!
  [port]
  (let [{:keys [fd state]} (tcp/connect! (tcp/parse-address "127.0.0.1" port))]
    (when (= :connecting state)
      (native/poll-one fd native/pollout 2000)
      (tcp/connect-result fd))
    {:fd fd, :in (atom []), :end (atom nil)}))


(defn- raw-poll!
  [{:keys [fd in end]}]
  (when-not @end
    (with-native 65536
      (fn [p]
        (loop []
          (let [r (tcp/recv-chunk! fd p 65536)]
            (cond
              (= :would-block r) nil
              (= :eof r) (reset! end :eof)
              (:error r) (reset! end (:class r))
              :else (do (swap! in into (bvec (ffi/read-array p (:n r))))
                        (recur)))))))))


(defn- write-all!
  ([fd bs]
   (write-all! fd bs (+ (poller/clock-ms) 300000)))
  ([fd bs deadline]
   (let [bs (if (bytes/bytes? bs) bs (octets bs))
         n (alength bs)
         max-stalls 100]
     (with-native (max 1 n)
       (fn [p]
         (loop [off 0
                stalls 0]
           (when (>= (poller/clock-ms) deadline)
             (throw (ex-info "raw write deadline exceeded"
                             {:workload/failure :timeout, :off off, :total n})))
           (if (>= off n)
             off
             (do
               ;; A short send must resume at the unsent byte, not byte zero.
               (ffi/write-array p bs off (- n off))
               (let [r (tcp/send-chunk! fd p (- n off))]
                 (cond
                   (:error r)
                   (throw (ex-info "raw write failed" {:error r}))

                   (and (map? r) (number? (:n r)) (pos? (:n r)) (<= (:n r) (- n off)))
                   (recur (+ off (:n r)) 0)

                   (or (= :would-block r) (= 0 (:n r)))
                   (let [remaining (max 0 (long (- deadline (poller/clock-ms))))
                         _ (native/poll-one fd native/pollout (min 2000 remaining))
                         stalls (inc stalls)]
                     (if (>= stalls max-stalls)
                       (throw (ex-info "TRANSFER STALLED: raw write made no progress"
                                       {:workload/failure :transfer-stalled,
                                        :off off, :total n, :stalls stalls, :result r}))
                       (recur off stalls)))

                   :else
                   (throw (ex-info "unexpected raw write result" {:result r}))))))))))))


(defn- write-some!
  [fd bs off limit]
  (let [bs (if (bytes/bytes? bs) bs (octets bs))
        n (min (- (alength bs) off) limit)]
    (with-native n
      (fn [p]
        (ffi/write-array p bs off n)
        (let [r (tcp/send-chunk! fd p n)]
          (cond
            (= :would-block r) :would-block
            (:error r) (throw (ex-info "raw write-some failed" {:error r}))
            (map? r) (:n r)
            :else (throw (ex-info "unexpected raw write-some result" {:result r}))))))))


(defn- head-end
  [v]
  (let [n (count v)]
    (loop [i 3]
      (cond
        (>= i n) nil
        (and (= 13 (nth v (- i 3)))
             (= 10 (nth v (- i 2)))
             (= 13 (nth v (- i 1)))
             (= 10 (nth v i)))
        (inc i)
        :else (recur (inc i))))))


(defn- raw-close!
  [peer]
  (tcp/shutdown! (:fd peer) :both)
  (tcp/close! (:fd peer)))


;; =============================================================================
;; Int32 Big-Endian packing for request IDs
;; =============================================================================

(defn- int32->bytes!
  [bs off val]
  (bytes/set! bs off (bit-and (bit-shift-right val 24) 0xFF))
  (bytes/set! bs (+ off 1) (bit-and (bit-shift-right val 16) 0xFF))
  (bytes/set! bs (+ off 2) (bit-and (bit-shift-right val 8) 0xFF))
  (bytes/set! bs (+ off 3) (bit-and val 0xFF))
  bs)


(defn- bytes->int32
  [bs off]
  (bit-or (bit-shift-left (bit-and (bytes/get bs off) 0xFF) 24)
          (bit-shift-left (bit-and (bytes/get bs (+ off 1)) 0xFF) 16)
          (bit-shift-left (bit-and (bytes/get bs (+ off 2)) 0xFF) 8)
          (bit-and (bytes/get bs (+ off 3)) 0xFF)))


;; =============================================================================
;; Deliverable 1: Workload W Harness
;; =============================================================================

(defn- make-1kib-request
  [id]
  (let [bs (bytes/alloc 1024)]
    (int32->bytes! bs 0 id)
    bs))


(defn- check-deadline!
  [deadline phase]
  (when (>= (poller/clock-ms) deadline)
    (throw (ex-info "Workload W exceeded its five-minute deadline"
                    {:workload/failure :timeout, :phase phase}))))


(defn- timed-call!
  "Optional per-call trace is diagnostic: it changes the measured composition.
   Totals include all synchronous work in a phase, not just socket syscalls."
  [trace? timings round phase f]
  (let [started (poller/clock-ms)
        report! (fn [event detail]
                  (when trace?
                    (println "W-CALL" (pr-str (merge {:round round, :phase phase, :event event}
                                                     detail)))
                    (flush)))]
    (report! :enter {:at-ms started})
    (try
      (f)
      (finally
        (let [elapsed (- (poller/clock-ms) started)]
          (swap! timings update phase
                 (fn [s]
                   {:calls (inc (:calls s 0))
                    :total-ms (+ (:total-ms s 0) elapsed)
                    :max-ms (max (:max-ms s 0) elapsed)}))
          (report! :leave {:elapsed-ms elapsed}))))))


(defn- step-repl!
  [state trace? timings round]
  (let [step! #(do
                 (driver/submit-line! (:input state)
                                      "(loop [i 0] (if (< i 5) (recur (inc i)) i))")
                 (driver/repl-step state (poller/clock-ms)))]
    (timed-call! trace? timings round :repl-step
                 (fn []
                   (if-not trace?
                     (step!)
                     (let [code-step repl.index/step
                           ast-step repl.ast-index/step]
                       ;; Diagnostic wrappers stay on the single owner thread.
                       (with-redefs [repl.index/step
                                     (fn [ix r]
                                       (timed-call! true timings round :code-index
                                                    #(code-step ix r)))
                                     repl.ast-index/step
                                     (fn [ix]
                                       (timed-call! true timings round :ast-index
                                                    #(ast-step ix)))]
                         (step!))))))))


(defn run-workload-w
  "Run the pre-registered Workload W benchmark:
   - Peer A: one 4,194,304 byte binary message (:ws/max-frame-bytes) paced over >= 60 s.
   - Peer B: 1 KiB request/reply concurrent (up to 8 outstanding) at 10 Hz.
   - REPL loop: evaluates a submitted loop each round, step count advances every round.
   - Criterion: p99 <= 250 ms, no reply lost, A delivered once, REPL step advances every round.
   Run through bb workload-w:jolt for the independent five-minute watchdog.
   Returns {:pass? ... :p99 ... :samples ... :elapsed-ms ... :delivered-once? ... :repl-advances? ...}."
  [& {:keys [target-duration-ms target-samples]
      :or {target-duration-ms 60000 target-samples 600}}]
  (println "================================================================================")
  (println "J6g Workload W: Starting benchmark (target duration:" target-duration-ms
           "ms, target samples:" target-samples ")")
  (println "================================================================================")
  (let [run-start (poller/clock-ms)
        deadline (+ run-start 300000)
        w (wake/make-wake)
        server-inst (wsj/create)
        client-b-inst (wsj/create)

        ;; Peer A accounting
        a-deliveries (atom [])
        a-window (atom {})
        trace? (= "1" (System/getenv "JOLT_WORKLOAD_W_TRACE"))
        timings (atom {})

        ;; Server bind
        server-bound? (atom false)
        server-port (atom 0)
        _ (wsj/bind! server-inst
                     {:bind-host "127.0.0.1"
                      :bind-port 0
                      :ws/max-frame-bytes 4194304
                      :codecs [cbor/profile]
                      :deposit! (fn [event detail]
                                  (when (= :bind-succeeded event)
                                    (reset! server-port (:port detail))
                                    (reset! server-bound? true)))
                      :accept! (fn [path seam _now]
                                 (cond
                                   (= path "/peer-a")
                                   {:ws/adapter
                                    {:opened! (fn [])
                                     :binary! (fn [payload]
                                                (swap! a-deliveries conj (alength payload))
                                                (swap! a-window
                                                       #(if (:delivered-at %)
                                                          %
                                                          (assoc % :delivered-at (poller/clock-ms)))))
                                     :message! (fn [_])
                                     :closed! (fn [_ _])
                                     :error! (fn [_])
                                     :too-large! (fn [])}}

                                   (= path "/peer-b")
                                   (let [seam-ref (atom seam)]
                                     {:ws/adapter
                                      {:opened! (fn [])
                                       :binary! (fn [payload]
                                                  ((:send! @seam-ref) payload))
                                       :message! (fn [msg]
                                                   ((:send! @seam-ref) msg))
                                       :closed! (fn [_ _])
                                       :error! (fn [_])
                                       :too-large! (fn [])}})))})

        ;; Drive until server listener is bound
        _ (loop []
            (check-deadline! deadline :server-bind)
            (when-not @server-bound?
              (wake/sleep-fds! w 5 (wsj/host-pump server-inst) nil)
              (recur)))
        port @server-port]

    (println "Server listening on port" port)

    ;; -------------------------------------------------------------------------
    ;; Peer B setup: connects via client adapter
    ;; -------------------------------------------------------------------------
    (let [b-opened? (atom false)
          b-samples (atom [])
          b-completed (atom 0)
          b-outstanding (atom {})
          b-lost (atom 0)
          b-req-seq (atom 0)
          b-seam (atom nil)
          b-cb {:ws/codec cbor/profile
                :ws/max-frame-bytes 4194304
                :opened! #(reset! b-opened? true)
                :binary! (fn [payload]
                           (let [now (poller/clock-ms)
                                 req-id (bytes->int32 payload 0)
                                 sent-at (get @b-outstanding req-id)]
                             (if sent-at
                               (do (swap! b-outstanding dissoc req-id)
                                   (swap! b-completed inc)
                                   ;; Both ends of a measured round trip must overlap A.
                                   (let [{:keys [started-at delivered-at]} @a-window]
                                     (when (and started-at (<= started-at sent-at)
                                                (or (nil? delivered-at) (<= now delivered-at)))
                                       (swap! b-samples conj (- now sent-at))
                                       (println "W-SAMPLE" (- now sent-at))
                                       (flush))))
                               (swap! b-lost inc))))
                :message! (fn [_])
                :closed! (fn [_ _])
                :error! (fn [_])
                :too-large! (fn [])}]

      (reset! b-seam (wsj/connect! client-b-inst
                                   {:ws/host "127.0.0.1"
                                    :ws/port port
                                    :ws/path "/peer-b"}
                                   b-cb))

      ;; Drive until Peer B connection is open
      (loop []
        (check-deadline! deadline :peer-b-upgrade)
        (when-not @b-opened?
          (wake/sleep-fds! w 5 (wsj/host-pump server-inst) nil)
          (wake/sleep-fds! w 5 (wsj/host-pump client-b-inst) nil)
          (recur)))

      (println "Peer B connected and upgraded")

      ;; -------------------------------------------------------------------------
      ;; Peer A setup: connects via raw socket, upgrades to /peer-a
      ;; -------------------------------------------------------------------------
      (let [raw-a (raw-peer! port)
            upgrade-req (str "GET /peer-a HTTP/1.1\r\n"
                             "Host: 127.0.0.1:" port "\r\n"
                             "Upgrade: websocket\r\n"
                             "Connection: Upgrade\r\n"
                             "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                             "Sec-WebSocket-Version: 13\r\n"
                             "Sec-WebSocket-Protocol: dao.stream.cbor\r\n\r\n")]
        (write-all! (:fd raw-a) (text/utf8-encode upgrade-req) deadline)

        ;; Drive until Peer A upgrade response arrives
        (loop []
          (check-deadline! deadline :peer-a-upgrade)
          (raw-poll! raw-a)
          (when-not (head-end @(:in raw-a))
            (wake/sleep-fds! w 5 (wsj/host-pump server-inst) nil)
            (recur)))

        (println "Peer A connected and upgraded")

        ;; Prepare Peer A's 4,194,304-byte binary frame
        (let [a-payload (bytes/alloc 4194304)
              mask-key (byte-array [(unchecked-byte 0x37) (unchecked-byte 0xFA)
                                    (unchecked-byte 0x21) (unchecked-byte 0x3D)])
              a-frame (rfc6455/encode-frame {:opcode :binary
                                             :payload a-payload
                                             :mask-key mask-key
                                             :fin? true})
              total-a-frame-len (alength a-frame)
              a-sent (atom 0)

              ;; REPL loop setup
              repl-state (atom (repl.main/boot {:adapter (host/websocket)}))
              repl-step-count (atom 0)
              owner-round-count (atom 0)

              ;; Timing and dispatch state
              start-time (poller/clock-ms)
              last-10hz-tick (atom (- start-time 100))
              last-telemetry-tick (atom start-time)
              a-stalls (atom 0)
              a-stalled? (atom false)
              timed-out? (atom false)
              max-consecutive-stalls 500
              server-pump (wsj/host-pump server-inst)
              client-b-pump (wsj/host-pump client-b-inst)]

          (println "Frame length for Peer A:" total-a-frame-len "bytes")
          (println "Entering owner round loop...")
          (flush)

          ;; Main owner loop
          (loop []
            (let [now (poller/clock-ms)
                  elapsed (- now start-time)]

              ;; Hard timeout guard: cap whole run at 5 minutes
              (if (>= now deadline)
                (do (reset! timed-out? true)
                    (println "HARD CAP EXCEEDED: workload-w exceeded 5 minutes ("
                             (- now run-start) "ms), terminating loop."))

                (do
                  ;; 1. Advance owner round and step REPL loop
                  (swap! owner-round-count inc)
                  (when (= 1 @owner-round-count)
                    (println "W-PHASE repl-step")
                    (flush))
                  (let [before @repl-state
                        stepped (step-repl! before trace? timings @owner-round-count)
                        [entries next-st] (driver/take-outbox stepped)
                        results (filter #(= :yin.repl.driver/result (get % driver/event-key)) entries)]
                    (reset! repl-state next-st)
                    (when (and (= (inc (get-in before [:repl :round]))
                                  (get-in next-st [:repl :round]))
                               (= ["5"] (mapv #(get % driver/text-key) results)))
                      (swap! repl-step-count inc)))

                  ;; 2. Paced Peer A transfer across target-duration-ms (with no-progress guard)
                  (when (= 1 @owner-round-count)
                    (println "W-PHASE peer-a-send")
                    (flush))
                  (when-not @a-stalled?
                    (let [a-start (:started-at @a-window)
                          target-bytes (if a-start
                                         (min total-a-frame-len
                                              (inc (long (* (dec total-a-frame-len)
                                                            (/ (- (poller/clock-ms) a-start)
                                                               (double target-duration-ms))))))
                                         1)]
                      (when (< @a-sent target-bytes)
                        (let [to-write (min (- target-bytes @a-sent) 65536)
                              r (timed-call! trace? timings @owner-round-count :peer-a-send
                                             #(write-some! (:fd raw-a) a-frame @a-sent to-write))]
                          (cond
                            (or (= :would-block r) (= 0 r))
                            (let [stalls (swap! a-stalls inc)]
                              (native/poll-one (:fd raw-a) native/pollout 0)
                              (when (>= stalls max-consecutive-stalls)
                                (reset! a-stalled? true)
                                (println "TRANSFER STALLED: Peer A hit" stalls
                                         "consecutive no-progress polls at" @a-sent "/" total-a-frame-len "bytes. Aborting transfer leg.")
                                (flush)))

                            (and (number? r) (pos? r) (<= r to-write))
                            (do (reset! a-stalls 0)
                                (when-not a-start
                                  (swap! a-window assoc :started-at (poller/clock-ms)))
                                (swap! a-sent + r))

                            :else
                            (do (reset! a-stalled? true)
                                (println "TRANSFER STALLED: unexpected Peer A send result" r)
                                (flush)))))))

                  ;; 3. Peer B 10 Hz dispatch: concurrent requests up to 8 outstanding
                  (let [dispatch-at (poller/clock-ms)]
                    (when (>= (- dispatch-at @last-10hz-tick) 100)
                      ;; Keep the absolute cadence; missed slots are not sent in a burst.
                      (swap! last-10hz-tick + (* 100 (quot (- dispatch-at @last-10hz-tick) 100)))
                      (when (< (count @b-outstanding) 8)
                        (let [id (swap! b-req-seq inc)
                              req-bs (make-1kib-request id)]
                          (timed-call! trace? timings @owner-round-count :peer-b-send
                                       #(do (swap! b-outstanding assoc id (poller/clock-ms))
                                            ((:send! @b-seam) req-bs)))))))

                  ;; 4. Periodic sampler flush every 10 seconds to ensure telemetry
                  (when (>= (- now @last-telemetry-tick) 10000)
                    (reset! last-telemetry-tick now)
                    (let [sc (count @b-samples)
                          cur-p99 (when (pos? sc)
                                    (nth (sort @b-samples) (min (dec sc) (int (* 0.99 sc)))))]
                      (println (str "TELEMETRY: elapsed=" (long (/ elapsed 1000)) "s, samples=" sc
                                    ", running p99=" (if cur-p99 (str cur-p99 "ms") "ABSENT") ", A-sent=" @a-sent "/" total-a-frame-len
                                    " (" (long (* 100 (/ (double @a-sent) (double total-a-frame-len)))) "%), A-deliveries=" (count @a-deliveries)
                                    (when @a-stalled? " [A-STALLED]")))
                      (println "W-TIMINGS" (pr-str @timings))
                      (println "W-PROGRESS"
                               (pr-str {:rounds @owner-round-count, :loop-results @repl-step-count,
                                        :transactions (get-in @repl-state [:repl :indexer :transactions]),
                                        :index-cursor (get-in @repl-state [:repl :indexer :materialization :cursor]),
                                        :b-sent @b-req-seq, :b-completed @b-completed,
                                        :b-outstanding (count @b-outstanding), :a-window @a-window}))
                      (flush)))

                  ;; 5. Pump I/O for server and client B
                  (when (= 1 @owner-round-count)
                    (println "W-PHASE server-pump")
                    (flush))
                  (timed-call! trace? timings @owner-round-count :server-pump
                               #(wake/sleep-fds! w 5 server-pump nil))
                  (when (= 1 @owner-round-count)
                    (println "W-PHASE client-b-pump")
                    (flush))
                  (timed-call! trace? timings @owner-round-count :client-b-pump
                               #(wake/sleep-fds! w 5 client-b-pump nil))
                  (when (= 1 @owner-round-count)
                    (println "W-PHASE owner-round-complete")
                    (flush))

                  ;; Check exit conditions
                  (let [a-done? (or @a-stalled?
                                    (and (>= @a-sent total-a-frame-len)
                                         (seq @a-deliveries)))
                        time-done? (>= elapsed target-duration-ms)]

                    ;; Later replies cannot repair a deficient overlap sample count.
                    (if (and a-done? time-done?)
                      ;; Drain trailing in-flight requests briefly
                      (let [drain-deadline (+ (poller/clock-ms) 500)]
                        (loop []
                          (when (and (seq @b-outstanding)
                                     (< (poller/clock-ms) (min deadline drain-deadline)))
                            (wake/sleep-fds! w 5 server-pump nil)
                            (wake/sleep-fds! w 5 client-b-pump nil)
                            (recur))))
                      (recur)))))))

          ;; Cleanup sockets
          (raw-close! raw-a)
          (wsj/close-host! client-b-inst)
          (wsj/close-host! server-inst)
          (wake/close! w)

          ;; -------------------------------------------------------------------
          ;; Evaluation of pre-registered criterion
          ;; -------------------------------------------------------------------
          (let [sample-count (count @b-samples)
                elapsed-ms (- (poller/clock-ms) run-start)
                valid-run? (>= sample-count 500)
                target-samples-met? (>= sample-count target-samples)
                sorted (sort @b-samples)
                p99-idx (int (* 0.99 sample-count))
                p99 (when (and (not @timed-out?) (pos? sample-count))
                      (nth sorted (min (dec sample-count) p99-idx)))
                p99-ok? (and (some? p99) (<= p99 250))
                repl-advances? (= @owner-round-count @repl-step-count)
                overlap-ms (when (and (:started-at @a-window) (:delivered-at @a-window))
                             (- (:delivered-at @a-window) (:started-at @a-window)))
                a-deliv-count (count @a-deliveries)
                a-deliv-len (or (first @a-deliveries) 0)
                delivered-once? (and (= 1 a-deliv-count)
                                     (= 4194304 a-deliv-len))
                replies-lost (count @b-outstanding)
                no-reply-lost? (and (zero? @b-lost) (zero? replies-lost))
                all-pass? (and (not @timed-out?)
                               (not @a-stalled?)
                               valid-run?
                               target-samples-met?
                               (some? overlap-ms)
                               (>= overlap-ms target-duration-ms)
                               (not trace?)
                               p99-ok?
                               repl-advances?
                               delivered-once?
                               no-reply-lost?)]

            (println)
            (println "================================================================================")
            (println "Workload W Results:" (if all-pass? "SUCCESS" "FAILURE"))
            (println "================================================================================")
            (println "  Hard cap timed out:           " @timed-out?)
            (println "  Elapsed:                      " elapsed-ms "ms")
            (println "  Peer A transfer stalled:      " @a-stalled?)
            (println "  Transfer overlap ms:          " overlap-ms)
            (println "  Diagnostic trace enabled:     " trace?)
            (println "W-TIMINGS" (pr-str @timings))
            (println "  Sample count:                 " sample-count
                     (str "(valid >= 500: " valid-run? ", target >= " target-samples ": " target-samples-met? ")"))
            (println "  p99 round-trip latency:       " (if (some? p99) (str p99 "ms") "ABSENT")
                     (str "(criterion <= 250 ms: " p99-ok? ")"))
            (println "  Peer B replies lost:          " replies-lost "(unanswered) +" @b-lost "(corrupt)")
            (println "  Peer A delivered once:        " delivered-once?
                     (str "(deliveries: " a-deliv-count ", bytes: " a-deliv-len ", sent: " @a-sent ")"))
            (println "  REPL per-round step advance:  " repl-advances?
                     (str "(rounds: " @owner-round-count ", REPL steps: " @repl-step-count ")"))
            (println)

            ;; Required report records per BRIEF-j6g.md
            (if (some? p99)
              (println "RECORD p99" p99 "ms")
              (println "RECORD p99 ABSENT"))
            (println "RECORD sample count" sample-count)
            (println "RECORD elapsed ms" elapsed-ms)
            (println "RECORD delivered once" delivered-once?)
            (println "RECORD per-round step advance" repl-advances?)

            (if all-pass?
              (do (println "PASS workload-w")
                  {:pass? true
                   :p99 p99
                   :samples sample-count
                   :elapsed-ms elapsed-ms
                   :timed-out? @timed-out?
                   :transfer-stalled? @a-stalled?
                   :delivered-once? delivered-once?
                   :repl-advances? repl-advances?
                   :replies-lost replies-lost})
              (do (println "FAIL workload-w")
                  {:pass? false
                   :p99 p99
                   :samples sample-count
                   :elapsed-ms elapsed-ms
                   :timed-out? @timed-out?
                   :transfer-stalled? @a-stalled?
                   :delivered-once? delivered-once?
                   :repl-advances? repl-advances?
                   :replies-lost replies-lost}))))))))


;; =============================================================================
;; Deliverable 2: Peer Cross-Host Rows
;; =============================================================================

(defn run-cross-host-rows
  "Verify cross-host listener, dialer, and REPL path reachability rows:
   - Hosts that listen: 127.0.0.1, 0.0.0.0, localhost, ::1; port 0 ephemeral; port collision.
   - Hosts that dial: 127.0.0.1, localhost, [::1]; hostname refused; wss refused.
   - REPL reachability refusals: local reachability failure, reachability failure, disclaimed path."
  []
  (println "================================================================================")
  (println "J6g Cross-Host Rows: Running checks...")
  (println "================================================================================")
  (let [w (wake/make-wake)
        passed (atom 0)
        failed (atom 0)
        check! (fn [label ok? detail]
                 (if ok?
                   (do (swap! passed inc)
                       (println "  ok  " label))
                   (do (swap! failed inc)
                       (println "  FAIL" label detail))))]

    ;; 1. Hosts that listen: 127.0.0.1, 0.0.0.0, localhost, ::1
    (doseq [bind-host ["127.0.0.1" "0.0.0.0" "localhost" "::1"]]
      (let [inst (wsj/create)
            bound-port (atom nil)
            res (wsj/bind! inst {:bind-host bind-host
                                 :bind-port 0
                                 :deposit! (fn [ev detail]
                                             (when (= :bind-succeeded ev)
                                               (reset! bound-port (:port detail))))})]
        (check! (str "listen on " bind-host " binds ephemeral port")
                (and (= :dao.stream/ok (:dao.stream/outcome res))
                     (pos? (or @bound-port 0)))
                {:res res :port @bound-port})
        (wsj/close-host! inst)))

    ;; Port collision check
    (let [inst1 (wsj/create)
          inst2 (wsj/create)
          bound-port (atom nil)
          _ (wsj/bind! inst1 {:bind-host "127.0.0.1"
                              :bind-port 0
                              :deposit! (fn [ev detail]
                                          (when (= :bind-succeeded ev)
                                            (reset! bound-port (:port detail))))})
          collision-res (wsj/bind! inst2 {:bind-host "127.0.0.1"
                                          :bind-port @bound-port
                                          :deposit! (fn [_ _])})]
      (check! "listen on colliding port returns transport-error"
              (= :dao.stream/transport-error (:dao.stream/outcome collision-res))
              {:collision collision-res})
      (wsj/close-host! inst2)
      (wsj/close-host! inst1))

    ;; 2. Hosts that dial: 127.0.0.1, localhost, [::1]
    (doseq [[bind-h dial-h label] [["127.0.0.1" "127.0.0.1" "127.0.0.1"]
                                   ["127.0.0.1" "localhost" "localhost"]
                                   ["::1" "[::1]" "[::1]"]]]
      (let [si (wsj/create)
            ci (wsj/create)
            port (atom nil)
            _ (wsj/bind! si {:bind-host bind-h
                             :bind-port 0
                             :deposit! (fn [ev detail]
                                         (when (= :bind-succeeded ev)
                                           (reset! port (:port detail))))})
            opened? (atom false)
            seam (wsj/connect! ci
                               {:ws/host dial-h :ws/port @port :ws/path "/repl"}
                               {:ws/codec cbor/profile
                                :opened! #(reset! opened? true)
                                :binary! (fn [_])
                                :message! (fn [_])
                                :closed! (fn [_ _])
                                :error! (fn [_])
                                :too-large! (fn [])})
            deadline (+ (poller/clock-ms) 5000)]
        (loop []
          (when-not (or @opened? (> (poller/clock-ms) deadline))
            (wake/sleep-fds! w 5 (wsj/host-pump si) nil)
            (wake/sleep-fds! w 5 (wsj/host-pump ci) nil)
            (recur)))
        (check! (str "dial on " label " connects and opens")
                (and (some? seam) @opened?)
                {:seam seam :opened? @opened?})
        (wsj/close-host! ci)
        (wsj/close-host! si)))

    ;; Refusal of host name (resolver trap not sprung)
    (let [ci (wsj/create)
          trap (fn [& _] (throw (ex-info "resolver called" {})))]
      (with-redefs-fn {#'native/resolve-addrs trap
                       #'native/c-getaddrinfo trap}
        (fn []
          (let [seam (wsj/connect! ci
                                   {:ws/host "example.invalid"
                                    :ws/port 80
                                    :ws/path "/repl"}
                                   {:opened! (fn [])
                                    :closed! (fn [_ _])
                                    :message! (fn [_])
                                    :binary! (fn [_])})]
            (check! "dial to hostname returns nil, resolver trap not sprung"
                    (nil? seam)
                    {:seam seam}))))
      (wsj/close-host! ci))

    ;; Refusal of wss scheme
    (let [ci (wsj/create)
          seam (wsj/connect! ci
                             {:ws/scheme "wss"
                              :ws/host "127.0.0.1"
                              :ws/port 443
                              :ws/path "/repl"}
                             {:opened! (fn [])
                              :closed! (fn [_ _])
                              :message! (fn [_])
                              :binary! (fn [_])})]
      (check! "dial with wss scheme is refused explicitly (nil returned)"
              (nil? seam)
              {:seam seam})
      (wsj/close-host! ci))

    ;; 3. Reachability refusals on the REPL path (yin.repl.connect/open)
    ;; 3a. Host name yields local reachability failure text
    (let [ci (wsj/create)
          opened (connect/open {:url "daostream:ws://example.invalid:80/repl"
                                :host {:connect! (fn [d a] (wsj/connect! ci d a))}
                                :now 0})
          text (pr-str opened)]
      (check! "connect/open to hostname gives local reachability failure text"
              (str/includes? text "local reachability failure")
              {:opened text})
      (wsj/close-host! ci))

    ;; 3b. Unbound / unreachable port yields reachability failure text
    (let [ci (wsj/create)
          ;; Unbound port on loopback: dial fails immediately on connect
          unused-port 59999
          opened (connect/open {:url (str "daostream:ws://127.0.0.1:" unused-port "/repl")
                                :host (host/websocket)
                                :now 0})
          conn (connect/connection-key opened)
          client (connect/client-key opened)
          deadline (+ (poller/clock-ms) 3000)
          observed (loop [c conn cl client]
                     (let [c' (connect/step! c (poller/clock-ms))
                           cl' (:dao.stream.rpc/state (rpc/poll! cl))
                           [c'' ev] (connect/observe-terminal c' (:terminal cl'))]
                       (cond
                         ev [c'' ev]
                         (> (poller/clock-ms) deadline) [c'' nil]
                         :else (do (wake/sleep-fds! w 10 nil nil)
                                   (recur c'' cl')))))
          event (second observed)
          ev-text (connect/text-key event)]
      (check! "connect/open to unbound port observes transport-error reachability failure"
              (and (some? event)
                   (str/includes? (str ev-text) "reachability failure"))
              {:event event :text ev-text})
      (wsj/close-host! ci))

    (wake/close! w)
    (println)
    (println "Cross-host rows:" @passed "passed," @failed "failed")
    (if (zero? @failed)
      (do (println "PASS cross-host") true)
      (do (println "FAIL cross-host") false))))


;; =============================================================================
;; Deliverable 3: REPL Served-Stream Rejoin Checks
;; =============================================================================

(defn- step-peer!
  [peer now]
  (swap! (:connection peer) connect/step! now)
  (swap! (:client peer) #(:dao.stream.rpc/state (rpc/poll! %))))


(defn- drive-repl-peers!
  [w insts peers pred ms]
  (let [deadline (+ (poller/clock-ms) ms)
        hps (mapv wsj/host-pump insts)]
    (loop []
      (let [now (poller/clock-ms)]
        (doseq [p peers] (step-peer! p now))
        (doseq [hp hps] (wake/sleep-fds! w 5 hp nil))
        (cond
          (pred) true
          (> (poller/clock-ms) deadline) false
          :else (recur))))))


(defn run-served-stream-rejoin
  "Run the 4 served-stream facts over two jolt host instances:
   Fact 1: evaluation round trips and two clients get their own answers.
   Fact 2: killing connection is observable and requests are lost.
   Fact 3: reattaching resumes served stream and deposit medium.
   Fact 4: stopping endpoint ends served stream (not closes it)."
  []
  (println "================================================================================")
  (println "J6g Served-Stream Rejoin: Running 4 served-stream peer facts...")
  (println "================================================================================")
  (let [w (wake/make-wake)
        passed (atom 0)
        failed (atom 0)
        check! (fn [label ok? detail]
                 (if ok?
                   (do (swap! passed inc)
                       (println "  ok  " label))
                   (do (swap! failed inc)
                       (println "  FAIL" label detail))))]

    ;; -------------------------------------------------------------------------
    ;; Fact 1: Two clients connect and get their own answers without crosstalk
    ;; -------------------------------------------------------------------------
    (let [si (wsj/create)
          ci1 (wsj/create)
          ci2 (wsj/create)
          port (atom 0)
          server-st (atom (serve/serve! {:bind-host "127.0.0.1"
                                         :bind-port 0
                                         :host {:bind! (fn [opts]
                                                         (wsj/bind! si (assoc opts :ws.jolt/adapter-of
                                                                              #(some-> (:ws/handle %) ws/adapter))))
                                                :unbind! (fn [lh dep] (wsj/unbind! si lh dep))
                                                :connect! (fn [d a] (wsj/connect! si d a))}}))
          ;; Advance server until port bound
          _ (loop []
              (let [stepped (serve/step @server-st (poller/clock-ms))
                    [_ next-st] (serve/take-outbox stepped)]
                (reset! server-st next-st)
                (when-let [p (get-in next-st [:spec :port])]
                  (when (pos? p) (reset! port p)))
                (when (zero? @port)
                  (wake/sleep-fds! w 5 (wsj/host-pump si) nil)
                  (recur))))
          url (str "daostream:ws://127.0.0.1:" @port "/repl")
          now (poller/clock-ms)
          p1-open (connect/open {:url url :host {:connect! (fn [d a] (wsj/connect! ci1 d a))} :now now})
          p2-open (connect/open {:url url :host {:connect! (fn [d a] (wsj/connect! ci2 d a))} :now now})
          peer1 {:connection (atom (connect/connection-key p1-open))
                 :client (atom (connect/client-key p1-open))}
          peer2 {:connection (atom (connect/connection-key p2-open))
                 :client (atom (connect/client-key p2-open))}]

      ;; Send eval requests
      (let [c1 (rpc/request! @(:client peer1) 1 :op/eval "(+ 40 2)")
            c2 (rpc/request! @(:client peer2) 2 :op/eval "(+ 20 3)")]
        (reset! (:client peer1) c1)
        (reset! (:client peer2) c2)

        ;; Drive until both answers arrive
        (drive-repl-peers! w [si ci1 ci2] [peer1 peer2]
                           (fn []
                             (let [stepped (serve/step @server-st (poller/clock-ms))
                                   [_ next-st] (serve/take-outbox stepped)]
                               (reset! server-st next-st)
                               (and (some? (rpc/response @(:client peer1) 1))
                                    (some? (rpc/response @(:client peer2) 2)))))
                           5000)

        (let [ans1 (rpc/response @(:client peer1) 1)
              ans2 (rpc/response @(:client peer2) 2)]
          (check! "Fact 1: peer 1 gets 42 and peer 2 gets 23 without crosstalk"
                  (and (= "42" (rpc/answer-ok ans1))
                       (= "23" (rpc/answer-ok ans2))
                       (nil? (rpc/response @(:client peer1) 2))
                       (nil? (rpc/response @(:client peer2) 1)))
                  {:ans1 ans1 :ans2 ans2})))

      (wsj/close-host! ci1)
      (wsj/close-host! ci2)
      (wsj/close-host! si))

    ;; -------------------------------------------------------------------------
    ;; Fact 2: Killing connection while request in-flight observes request lost
    ;; -------------------------------------------------------------------------
    (let [si (wsj/create)
          ci (wsj/create)
          port (atom 0)
          server-st (atom (serve/serve! {:bind-host "127.0.0.1"
                                         :bind-port 0
                                         :host {:bind! (fn [opts]
                                                         (wsj/bind! si (assoc opts :ws.jolt/adapter-of
                                                                              #(some-> (:ws/handle %) ws/adapter))))
                                                :unbind! (fn [lh dep] (wsj/unbind! si lh dep))
                                                :connect! (fn [d a] (wsj/connect! si d a))}}))
          _ (loop []
              (let [stepped (serve/step @server-st (poller/clock-ms))
                    [_ next-st] (serve/take-outbox stepped)]
                (reset! server-st next-st)
                (when-let [p (get-in next-st [:spec :port])]
                  (when (pos? p) (reset! port p)))
                (when (zero? @port)
                  (wake/sleep-fds! w 5 (wsj/host-pump si) nil)
                  (recur))))
          url (str "daostream:ws://127.0.0.1:" @port "/repl")
          now (poller/clock-ms)
          p-open (connect/open {:url url :host {:connect! (fn [d a] (wsj/connect! ci d a))} :now now})
          peer {:connection (atom (connect/connection-key p-open))
                :client (atom (connect/client-key p-open))}]

      ;; Connect first
      (drive-repl-peers! w [si ci] [peer]
                         (fn []
                           (let [stepped (serve/step @server-st (poller/clock-ms))
                                 [_ next-st] (serve/take-outbox stepped)]
                             (reset! server-st next-st)
                             (remote-channel/opened? (:dial @(:connection peer)))))
                         3000)

      ;; Stall server driver, send request, and close client connection
      (let [c (rpc/request! @(:client peer) 99 :op/eval "(+ 1 2)")]
        (reset! (:client peer) c)
        (swap! (:connection peer) connect/close!)

        ;; Drive client until terminal observed
        (drive-repl-peers! w [ci] [peer]
                           (fn []
                             (some? (:terminal @(:client peer))))
                           3000)

        (let [[conn' ev] (connect/observe-terminal @(:connection peer) (:terminal @(:client peer)))]
          (check! "Fact 2: client observes detached and lost request"
                  (and (= :detached (:status conn'))
                       (= :dao.stream.rpc/detached (:terminal @(:client peer))))
                  {:conn conn' :ev ev :terminal (:terminal @(:client peer))})))

      (wsj/close-host! ci)
      (wsj/close-host! si))

    ;; -------------------------------------------------------------------------
    ;; Fact 3: Reattaching resumes served stream and shared shell state survives
    ;; -------------------------------------------------------------------------
    (let [si (wsj/create)
          ci (wsj/create)
          port (atom 0)
          server-st (atom (serve/serve! {:bind-host "127.0.0.1"
                                         :bind-port 0
                                         :host {:bind! (fn [opts]
                                                         (wsj/bind! si (assoc opts :ws.jolt/adapter-of
                                                                              #(some-> (:ws/handle %) ws/adapter))))
                                                :unbind! (fn [lh dep] (wsj/unbind! si lh dep))
                                                :connect! (fn [d a] (wsj/connect! si d a))}}))
          _ (loop []
              (let [stepped (serve/step @server-st (poller/clock-ms))
                    [_ next-st] (serve/take-outbox stepped)]
                (reset! server-st next-st)
                (when-let [p (get-in next-st [:spec :port])]
                  (when (pos? p) (reset! port p)))
                (when (zero? @port)
                  (wake/sleep-fds! w 5 (wsj/host-pump si) nil)
                  (recur))))
          url (str "daostream:ws://127.0.0.1:" @port "/repl")
          now (poller/clock-ms)
          client-host {:connect! (fn [d a] (wsj/connect! ci d a))}
          p-open (connect/open {:url url :host client-host :now now})
          peer {:connection (atom (connect/connection-key p-open))
                :client (atom (connect/client-key p-open))}]

      ;; 1. Define x = 10
      (let [c (rpc/request! @(:client peer) 10 :op/eval "(def x 10)")]
        (reset! (:client peer) c)
        (drive-repl-peers! w [si ci] [peer]
                           (fn []
                             (let [stepped (serve/step @server-st (poller/clock-ms))
                                   [_ next-st] (serve/take-outbox stepped)]
                               (reset! server-st next-st)
                               (some? (rpc/response @(:client peer) 10))))
                           5000)
        (check! "Fact 3: (def x 10) evaluated"
                (= "10" (rpc/answer-ok (rpc/response @(:client peer) 10)))
                {:ans (rpc/response @(:client peer) 10)}))

      ;; 2. Disconnect and reattach
      (swap! (:connection peer) connect/close!)
      (drive-repl-peers! w [ci] [peer]
                         (fn [] (some? (:terminal @(:client peer))))
                         2000)
      (let [now' (poller/clock-ms)
            reattached (connect/reattach @(:connection peer) @(:client peer) now')]
        (reset! (:connection peer) (connect/connection-key reattached))
        (reset! (:client peer) (connect/client-key reattached))

        ;; 3. Query x on reattached connection
        (let [c2 (rpc/request! @(:client peer) 11 :op/eval "x")]
          (reset! (:client peer) c2)
          (drive-repl-peers! w [si ci] [peer]
                             (fn []
                               (let [stepped (serve/step @server-st (poller/clock-ms))
                                     [_ next-st] (serve/take-outbox stepped)]
                                 (reset! server-st next-st)
                                 (some? (rpc/response @(:client peer) 11))))
                             5000)
          (check! "Fact 3: reattached connection reads x = 10"
                  (= "10" (rpc/answer-ok (rpc/response @(:client peer) 11)))
                  {:ans (rpc/response @(:client peer) 11)})))

      (wsj/close-host! ci)
      (wsj/close-host! si))

    ;; -------------------------------------------------------------------------
    ;; Fact 4: Stopping endpoint ends served stream (terminal :ended, not :detached)
    ;; -------------------------------------------------------------------------
    (let [si (wsj/create)
          ci (wsj/create)
          port (atom 0)
          server-st (atom (serve/serve! {:bind-host "127.0.0.1"
                                         :bind-port 0
                                         :host {:bind! (fn [opts]
                                                         (wsj/bind! si (assoc opts :ws.jolt/adapter-of
                                                                              #(some-> (:ws/handle %) ws/adapter))))
                                                :unbind! (fn [lh dep] (wsj/unbind! si lh dep))
                                                :connect! (fn [d a] (wsj/connect! si d a))}}))
          _ (loop []
              (let [stepped (serve/step @server-st (poller/clock-ms))
                    [_ next-st] (serve/take-outbox stepped)]
                (reset! server-st next-st)
                (when-let [p (get-in next-st [:spec :port])]
                  (when (pos? p) (reset! port p)))
                (when (zero? @port)
                  (wake/sleep-fds! w 5 (wsj/host-pump si) nil)
                  (recur))))
          url (str "daostream:ws://127.0.0.1:" @port "/repl")
          now (poller/clock-ms)
          client-host {:connect! (fn [d a] (wsj/connect! ci d a))}
          p-open (connect/open {:url url :host client-host :now now})
          peer {:connection (atom (connect/connection-key p-open))
                :client (atom (connect/client-key p-open))}]

      ;; Open connection and eval (+ 3 4)
      (let [c (rpc/request! @(:client peer) 7 :op/eval "(+ 3 4)")]
        (reset! (:client peer) c)
        (drive-repl-peers! w [si ci] [peer]
                           (fn []
                             (let [stepped (serve/step @server-st (poller/clock-ms))
                                   [_ next-st] (serve/take-outbox stepped)]
                               (reset! server-st next-st)
                               (some? (rpc/response @(:client peer) 7))))
                           5000)
        (check! "Fact 4: (+ 3 4) evaluates to 7"
                (= "7" (rpc/answer-ok (rpc/response @(:client peer) 7)))
                {:ans (rpc/response @(:client peer) 7)}))

      ;; Stop endpoint
      (swap! server-st serve/stop!)
      (drive-repl-peers! w [si ci] [peer]
                         (fn []
                           (let [stepped (serve/step @server-st (poller/clock-ms))
                                 [_ next-st] (serve/take-outbox stepped)]
                             (reset! server-st next-st)
                             (and (serve/stopped? next-st)
                                  (some? (:terminal @(:client peer))))))
                         5000)

      (let [[conn' ev] (connect/observe-terminal @(:connection peer) (:terminal @(:client peer)))]
        (check! "Fact 4: client observes :ended, not bare detached"
                (and (= :ended (:status conn'))
                     (= :dao.stream.rpc/ended (:terminal @(:client peer))))
                {:conn conn' :ev ev :terminal (:terminal @(:client peer))}))

      (wsj/close-host! ci)
      (wsj/close-host! si))

    (wake/close! w)
    (println)
    (println "Served-stream rejoin:" @passed "passed," @failed "failed")
    (if (zero? @failed)
      (do (println "PASS served-stream") true)
      (do (println "FAIL served-stream") false))))


;; =============================================================================
;; CLI Dispatcher
;; =============================================================================

(defn -main
  [& args]
  (when-not (= "1" (System/getenv "JOLT_WORKLOAD_W_SUPERVISED"))
    (println "Workload W Results: FAILURE")
    (println "Use bb workload-w:jolt so the independent watchdog owns the runtime cap.")
    (println "RECORD p99 ABSENT")
    (println "RECORD sample count 0")
    (println "RECORD elapsed ms 0")
    (println "FAIL workload-w")
    (flush)
    (System/exit 1))
  (let [cmd (or (first args) "workload-w")
        ok? (case cmd
              "workload-w" (:pass? (run-workload-w))
              "quick-w" (:pass? (run-workload-w :target-duration-ms 2000 :target-samples 20))
              "cross-host" (run-cross-host-rows)
              "served-stream" (run-served-stream-rejoin)
              "all" (and (run-cross-host-rows)
                         (run-served-stream-rejoin)
                         (:pass? (run-workload-w)))
              (do (println "Unknown command:" cmd
                           "- valid commands: workload-w, quick-w, cross-host, served-stream, all")
                  false))]
    (flush)
    (System/exit (if ok? 0 1))))


(apply -main *command-line-args*)
