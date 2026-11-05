(ns jolt-workload-w
  "Workload W: one socket owner, a paced raw sender, and an independent B clock.
   Run through bb workload-w:jolt for the independent whole-process watchdog.
   All adapter calls and callback accounting belong to the owner. The B thread
   communicates exclusively through two streams; it never touches an adapter."
  (:require [dao.host.bytes :as bytes]
            [dao.host.text :as text]
            [dao.stream :as stream]
            [dao.stream.cbor :as cbor]
            [dao.stream.ws.crypto :as crypto]
            [dao.stream.ws.jolt :as wsj]
            [dao.stream.ws.rfc6455 :as rfc6455]
            [dao.stream.ws.tcp :as tcp]
            [dao.stream.ws.upgrade :as upgrade]
            [dao.stream.waitset.driver :as wake]
            [jolt.ffi :as ffi]
            [jolt.io-poller :as poller]
            [jolt.socket.native :as native]
            [yin.repl.driver :as driver]
            [yin.repl.host :as host]
            [yin.repl.main :as repl.main]))


(def message-bytes 4194304)
(def transfer-ms 120000)
(def offer-ms 100)
(def cohort-size 600)
(def validity-floor 500)
(def latency-limit-ms 250)
(def max-outstanding 8)
(def max-stalls 500)
(def pace-ms 10)
(def drain-ms 5000)


(defn- emit!
  [tag value]
  (println tag (pr-str value))
  (flush))


(defn- fail!
  [reason data]
  (throw (ex-info reason data)))


(defn- append!
  [medium value]
  (let [r (stream/append! medium value)]
    (when-not (= :dao.stream/ok (:dao.stream/outcome r))
      (fail! "Workload stream append failed" {:result r}))))


(defn- cursor
  [medium]
  (let [r (stream/cursor medium :dao.stream/oldest)]
    (if (= :dao.stream/ok (:dao.stream/outcome r))
      (:dao.stream/cursor r)
      (fail! "Workload stream cursor failed" {:result r}))))


(defn- read-events
  "Bounded handoff; a gap is a failed run, never silent loss."
  [medium position]
  (loop [position position events []]
    (if (= 1024 (count events))
      [position events]
      (let [r (stream/next medium position)]
        (case (:dao.stream/outcome r)
          :dao.stream/ok (recur (:dao.stream/cursor r)
                                (conj events (:dao.stream/value r)))
          :dao.stream/blocked [position events]
          (fail! "Workload stream read failed" {:result r}))))))


(defn- scheduler!
  "Each slot has origin + (id+1)*100, including missed slots. Reservations
   include offers waiting for the owner, so queued plus sent never exceeds 8.
   Late slots are missed, never replayed as a catch-up burst."
  [offers control]
  (try
    (loop [position (cursor control) origin nil id 0 pending #{}]
      (let [[position events] (read-events control position)
            stopped? (some #(= :stop (:kind %)) events)
            origin (or origin (:at (first (filter #(= :start (:kind %)) events))))
            pending (reduce (fn [p event]
                              (if (= :ack (:kind event)) (disj p (:id event)) p))
                            pending events)]
        (if stopped?
          (append! offers {:kind :scheduler-done :slots id})
          (let [due (when origin (+ origin (* (inc id) offer-ms)))
                now (poller/clock-ms)]
            (if (and due (>= now due))
              (let [reason (cond
                             (>= now (+ due offer-ms)) :scheduler-late
                             (>= (count pending) max-outstanding) :outstanding-limit)
                    event {:kind (if reason :missed :offer)
                           :id id :scheduled-at due :reason reason
                           :cohort? (< id cohort-size)}]
                (append! offers event)
                (recur position origin (inc id)
                       (if reason pending (conj pending id))))
              (do (Thread/sleep 1)
                  (recur position origin id pending)))))))
    (catch Throwable error
      (append! offers {:kind :scheduler-error :message (str error)}))))


(defn- request
  [id]
  (let [payload (bytes/alloc 1024)]
    (dotimes [i 4]
      (bytes/set! payload i (bit-and 255 (bit-shift-right id (* 8 (- 3 i))))))
    payload))


(defn- request-id
  [payload]
  (when (= 1024 (bytes/length payload))
    (reduce (fn [id i] (+ (bit-shift-left id 8) (bytes/get payload i)))
            0 (range 4))))


(defn- p99
  "Nearest-rank empirical quantile."
  [samples]
  (when (seq samples)
    (nth (vec (sort samples)) (dec (long (Math/ceil (* 0.99 (count samples))))))))


(defn- binomial-cdf
  [n k q]
  (loop [i 0 term (Math/pow (- 1.0 q) n) total 0.0]
    (let [total (+ total term)]
      (if (= i k)
        total
        (recur (inc i) (* term (/ (- n i) (double (inc i)))
                          (/ q (- 1.0 q))) total)))))


(defn- upper-bound
  "Exact one-sided 95% Clopper-Pearson upper bound. For zero violations
   this is 1 - 0.05^(1/n); otherwise invert P_q[X <= k] = 0.05."
  [n k]
  (cond
    (zero? n) nil
    (= k n) 1.0
    (zero? k) (- 1.0 (Math/pow 0.05 (/ 1.0 n)))
    :else (loop [lo 0.0 hi 1.0 iterations 0]
            (let [mid (/ (+ lo hi) 2.0)]
              (if (= iterations 70)
                hi
                (if (> (binomial-cdf n k mid) 0.05)
                  (recur mid hi (inc iterations))
                  (recur lo mid (inc iterations))))))))


(defn- samples
  [state]
  (filterv #(and (:cohort? %) (:received-at %)) (vals (:requests state))))


(defn- latencies
  [rows timestamp]
  (mapv #(- (:received-at %) (timestamp %)) rows))


(defn- progress
  [state]
  (let [rows (samples state)
        repl (:repl-state state)
        a (:a state)]
    {:owner-rounds (:rounds state)
     :b {:sent (:sent state) :completed (:completed state)
         :outstanding (count (:pending state)) :missed (:missed state)
         :cohort-samples (count rows)
         :rtt-p99-ms (p99 (latencies rows :sent-at))
         :arrival-p99-ms (p99 (latencies rows :scheduled-at))}
     :a {:sent-bytes (max 0 (- (:offset a 0) (:header-bytes a 0)))
         :percent (* 100.0 (/ (max 0 (- (:offset a 0) (:header-bytes a 0)))
                              message-bytes))
         :started-at (:started-at a) :sent-at (:sent-at a)
         :delivered-at (:delivered-at a) :deliveries (:deliveries a 0)
         :echoes (:echoes a 0) :stalls (:stalls a 0)}
     :repl-steps (:repl-steps state)
     :repl-results (:repl-results state)
     :index-materialization-cursor (get-in repl [:repl :indexer :materialization :cursor])
     :shell-index-publication-cursor (get-in repl [:repl :index-publication :cursor])}))


(defn- callbacks
  [binary! error!]
  {:opened! (fn []) :binary! binary!
   :message! (fn [_] (error! :unexpected-text))
   :closed! (fn [code reason] (error! {:closed code :reason reason}))
   :error! error! :too-large! (fn [] (error! :too-large))})


(defn- receive-b!
  [state control payload]
  (let [received (poller/clock-ms)
        id (request-id payload)
        row (get-in @state [:requests id])]
    (if (and row (:sent-at row) (not (:received-at row))
             (bytes/equal? payload (request id)))
      (let [row (assoc row :received-at received)]
        (swap! state #(-> % (assoc-in [:requests id] row)
                          (update :completed inc) (update :pending disj id)))
        (append! control {:kind :ack :id id})
        (emit! "W-SAMPLE" (assoc row
                                 :rtt-ms (- received (:sent-at row))
                                 :arrival-ms (- received (:scheduled-at row)))))
      (swap! state update :errors conj {:invalid-or-duplicate-reply id}))))


(defn- send-offers!
  [state offers control seam]
  (let [[position events] (read-events offers (:offer-cursor @state))]
    (swap! state assoc :offer-cursor position)
    (doseq [event events]
      (case (:kind event)
        :scheduler-error (fail! "B scheduler failed" event)
        :scheduler-done (swap! state assoc :scheduler-done? true :slots (:slots event))
        :missed (swap! state #(-> % (update :missed inc)
                                  (assoc-in [:requests (:id event)] event)))
        :offer
        (let [id (:id event)
              payload (request id)
              row (assoc event :sent-at (poller/clock-ms))]
          (swap! state #(-> % (assoc-in [:requests id] row)
                            (update :pending conj id)))
          (if-let [result ((:send! seam) payload)]
            (do (swap! state #(-> % (update :pending disj id)
                                  (update :errors conj {:send-failed id :result result})))
                (append! control {:kind :ack :id id}))
            (swap! state update :sent inc)))
        (fail! "Unknown B scheduler event" event)))))


(defn- owner-round!
  [state waiter pump]
  (swap! state update :rounds inc)
  (let [stepped (driver/repl-step (:repl-state @state) (poller/clock-ms))
        [entries next-state] (driver/take-outbox stepped)]
    (swap! state #(-> % (assoc :repl-state next-state)
                      (update :repl-steps inc)
                      (update :repl-results into (mapv driver/text-key entries)))))
  (wake/sleep-fds! waiter 1 pump nil)
  (let [now (poller/clock-ms)]
    (when (>= now (:next-progress @state))
      (emit! "W-STATE" (progress @state))
      (swap! state assoc :next-progress (+ now 10000)))))


(defn- with-native
  [n f]
  (let [p (ffi/alloc n)]
    (try (f p) (finally (ffi/free p)))))


(defn- send-some!
  [fd payload offset n]
  (with-native n
    (fn [p]
      (ffi/write-array p payload offset n)
      (let [r (tcp/send-chunk! fd p n)]
        (cond
          (= :would-block r) 0
          (:error r) (fail! "Raw TCP write failed" {:result r})
          (and (number? (:n r)) (<= 0 (:n r) n)) (:n r)
          :else (fail! "Unexpected raw TCP write result" {:result r}))))))


(defn- recv-some!
  [fd]
  (with-native 65536
    (fn [p]
      (let [r (tcp/recv-chunk! fd p 65536)]
        (cond
          (= :would-block r) nil
          (or (= :eof r) (:error r)) (fail! "Raw TCP read failed" {:result r})
          :else (ffi/read-array p (:n r)))))))


(defn- raw-connect!
  [port]
  (let [r (tcp/connect! (tcp/parse-address "127.0.0.1" port))]
    (when (:error r) (fail! "Raw TCP connect failed" {:result r}))
    r))


(defn- upgrade-a!
  [state fd port round!]
  (let [key "dGhlIHNhbXBsZSBub25jZQ=="
        payload (text/utf8-encode
                  (:head (upgrade/client-request
                           {:host "127.0.0.1" :port port :path "/peer-a" :key key
                            :subprotocols ["dao.stream.cbor"]})))]
    (loop [offset 0 stalls 0 head (upgrade/head-parser)]
      (round!)
      (let [n (if (< offset (alength payload))
                (send-some! fd payload offset (- (alength payload) offset)) 0)
            stalls (if (and (< offset (alength payload)) (zero? n)) (inc stalls) 0)]
        (when (>= stalls max-stalls)
          (fail! "TRANSFER STALLED: A upgrade write" {:stalls stalls}))
        (let [chunk (recv-some! fd)
              [head result] (if chunk (upgrade/head-feed head chunk) [head nil])]
          (if result
            (let [verdict (when-not (:refused result)
                            (upgrade/check-response
                              (upgrade/parse-response (:head result))
                              {:key key :offered ["dao.stream.cbor"] :sha1 crypto/sha1}))]
              (when-not (and (= :ok (:outcome verdict))
                             (= "dao.stream.cbor" (:subprotocol verdict))
                             (zero? (alength (:rest result))))
                (fail! "A upgrade refused or malformed" {:result result :verdict verdict}))
              (swap! state assoc-in [:a :upgraded?] true))
            (recur (+ offset n) stalls head)))))))


(defn- pace-a!
  "At most one small write per scheduled visit. A late owner round moves
   the next attempt into the future; it never earns a large catch-up write.
   The final byte is withheld until 120 s after the first positive write."
  [state fd frame control]
  (let [now (poller/clock-ms)
        a (:a @state)
        total (alength frame)
        quantum (long (Math/ceil (/ (* total pace-ms) (double transfer-ms))))]
    (when (and (< (:offset a) total) (>= now (:next-write a)))
      (let [started (:started-at a)
            remaining (- total (:offset a))
            allowance (if (and started (< now (+ started transfer-ms)))
                        (max 0 (dec remaining)) remaining)
            n (if (pos? allowance)
                (send-some! fd frame (:offset a) (min quantum allowance)) 0)
            stalls (if (and (pos? allowance) (zero? n)) (inc (:stalls a)) 0)]
        (when (>= stalls max-stalls)
          (emit! "TRANSFER STALLED" {:attempts stalls :sent-bytes (:offset a)})
          (fail! "TRANSFER STALLED: A made no progress" {:stalls stalls}))
        (when (and (pos? n) (nil? started))
          (append! control {:kind :start :at now}))
        (swap! state update :a
               #(cond-> (assoc % :offset (+ (:offset a) n) :stalls stalls
                               :next-write (+ now pace-ms))
                  (and (pos? n) (nil? started)) (assoc :started-at now)
                  (= total (+ (:offset a) n)) (assoc :sent-at now)))))))


(defn- read-a-echo!
  [state fd]
  (when-let [chunk (recv-some! fd)]
    (loop [parser (get-in @state [:a :parser]) offset 0]
      (let [[event parser offset] (rfc6455/parse-frame parser chunk offset)]
        (swap! state assoc-in [:a :parser] parser)
        (when event
          (if (and (= :message (:type event)) (= :binary (:opcode event))
                   (= message-bytes (bytes/length (:payload event))))
            (swap! state update-in [:a :echoes] inc)
            (fail! "Unexpected A echo event" {:type (:type event) :opcode (:opcode event)})))
        (when (and event (< offset (alength chunk)))
          (recur parser offset))))))


(defn- results
  [state]
  (let [rows (samples state)
        n (count rows)
        rtts (latencies rows :sent-at)
        arrivals (latencies rows :scheduled-at)
        violations (count (filter #(or (> (- (:received-at %) (:sent-at %)) latency-limit-ms)
                                       (> (- (:received-at %) (:scheduled-at %)) latency-limit-ms)) rows))
        q (upper-bound n violations)
        cohort (filterv :cohort? (vals (:requests state)))
        a (:a state)
        gates {:valid-sample-floor (>= n validity-floor)
               :prospective-cohort (and (= cohort-size (count cohort)) (= cohort-size n))
               :rtt-p99 (and (pos? n) (<= (p99 rtts) latency-limit-ms))
               :scheduled-arrival-p99 (and (pos? n) (<= (p99 arrivals) latency-limit-ms))
               :binomial-confidence (and q (<= q 0.01))
               :no-reply-lost (and (= (:sent state) (:completed state))
                                   (empty? (:pending state)))
               :a-delivered-once (and (= 1 (:deliveries a))
                                      (= message-bytes (:delivered-bytes a)) (= 1 (:echoes a)))
               :a-paced-120s (and (:sent-at a) (:started-at a)
                                  (>= (- (:sent-at a) (:started-at a)) transfer-ms))
               :repl-every-round (and (pos? (:rounds state))
                                      (= (:rounds state) (:repl-steps state)))
               :submitted-loop-evaluated (= ["5"] (:repl-results state))
               :scheduler-stopped (true? (:scheduler-done? state))
               :no-protocol-errors (empty? (:errors state))}]
    {:status (cond (< n validity-floor) :invalid
                   (every? true? (vals gates)) :pass
                   :else :failure)
     :gates gates :samples n :violations violations :q-upper-95 q
     :rtt-p99-ms (p99 rtts) :scheduled-arrival-p99-ms (p99 arrivals)
     :cohort-missed (count (filter #(= :missed (:kind %)) cohort))
     :progress (progress state) :errors (:errors state)
     :repl-measurement :driver-steps
     :repl-note "One finite loop submission; the frozen driver evaluates it synchronously."}))


(defn run-workload-w
  []
  (let [inst (wsj/create)
        repl-host (host/websocket)
        waiter (wake/make-wake)
        offers (driver/create-input-medium!)
        control (driver/create-input-medium!)
        state (atom {:rounds 0 :repl-steps 0 :repl-results []
                     :repl-state (repl.main/boot {:adapter repl-host})
                     :offer-cursor (cursor offers) :requests {} :pending #{}
                     :sent 0 :completed 0 :missed 0 :errors []
                     :next-progress (poller/clock-ms)
                     :a {:offset 0 :stalls 0 :next-write 0 :deliveries 0 :echoes 0
                         :parser (rfc6455/parser {:role :client :max-message-bytes message-bytes})}})
        raw (atom nil)
        scheduler (atom nil)
        error! #(swap! state update :errors conj %)
        pump (wsj/host-pump inst)
        round! #(owner-round! state waiter pump)]
    (try
      (append! (:input (:repl-state @state))
               {driver/line-key "(loop [i 0] (if (< i 5) (recur (inc i)) i))"})
      (let [port (atom nil)
            bound (wsj/bind!
                    inst
                    {:bind-host "127.0.0.1" :bind-port 0
                     :codecs [cbor/profile] :ws/max-frame-bytes message-bytes
                     :deposit! (fn [event detail]
                                 (if (= :bind-succeeded event)
                                   (reset! port (:port detail))
                                   (error! {:event event :detail detail})))
                     :accept! (fn [path seam _now]
                                (case path
                                  "/peer-a"
                                  {:ws/adapter
                                   (callbacks
                                     (fn [payload]
                                       (swap! state update :a
                                              #(-> % (update :deliveries inc)
                                                   (assoc :delivered-at (poller/clock-ms)
                                                          :delivered-bytes (alength payload))))
                                       (when-let [r ((:send! seam) payload)] (error! r)))
                                     error!)}
                                  "/peer-b"
                                  {:ws/adapter
                                   (callbacks (fn [payload]
                                                (when-let [r ((:send! seam) payload)] (error! r)))
                                              error!)}
                                  (fail! "Unexpected peer path" {:path path})))})]
        (when-not (and (= :dao.stream/ok (:dao.stream/outcome bound)) @port)
          (fail! "Server bind failed" {:result bound}))
        (let [opened (atom false)
              seam (wsj/connect! inst {:ws/host "127.0.0.1" :ws/port @port :ws/path "/peer-b"}
                                 (assoc (callbacks #(receive-b! state control %) error!)
                                        :ws/codec cbor/profile :ws/max-frame-bytes message-bytes
                                        :opened! #(reset! opened true)))]
          (when-not seam (fail! "B connect refused" {}))
          (loop []
            (when-not @opened
              (round!)
              (when (seq (:errors @state)) (fail! "B upgrade failed" {:errors (:errors @state)}))
              (recur)))
          (let [connection (raw-connect! @port)
                fd (:fd connection)]
            (reset! raw fd)
            (when (= :connecting (:state connection))
              (native/poll-one fd native/pollout 2000)
              (let [r (tcp/connect-result fd)]
                (when (:error r) (fail! "A connect failed" {:result r}))))
            (upgrade-a! state fd @port round!)
            (let [frame (rfc6455/encode-frame
                          {:opcode :binary :payload (bytes/alloc message-bytes)
                           :mask-key (bytes/from-vector [55 250 33 61])})]
              (swap! state assoc-in [:a :header-bytes] (- (alength frame) message-bytes))
              (reset! scheduler (doto (Thread. #(scheduler! offers control) "workload-w-b-clock")
                                  (.setDaemon true) (.start)))
              (loop [stopping-at nil]
                (round!)
                (send-offers! state offers control seam)
                (pace-a! state fd frame control)
                (read-a-echo! state fd)
                (let [now (poller/clock-ms)
                      stopping-at (or stopping-at
                                      (when (and (get-in @state [:a :sent-at])
                                                 (pos? (get-in @state [:a :deliveries])))
                                        (append! control {:kind :stop}) now))]
                  (if (and stopping-at
                           (or (and (:scheduler-done? @state) (empty? (:pending @state))
                                    (pos? (get-in @state [:a :echoes])))
                               (>= now (+ stopping-at drain-ms))))
                    (results @state)
                    (recur stopping-at))))))))
      (finally
        (append! control {:kind :stop})
        (when @scheduler (.join @scheduler 1000))
        (when @raw (tcp/close! @raw))
        (wsj/close-host! inst)
        ((:close-host! repl-host))
        (wake/close! waiter)
        (stream/close! offers)
        (stream/close! control)))))


(try
  (when-not (= "1" (System/getenv "JOLT_WORKLOAD_W_SUPERVISED"))
    (fail! "Run bb workload-w:jolt: an independent watchdog is required" {}))
  (when (seq *command-line-args*)
    (fail! "Workload W has no reduced-duration or alternate modes" {:args *command-line-args*}))
  (let [result (run-workload-w)]
    (emit! "W-RESULT" result)
    (println (if (= :pass (:status result)) "PASS workload-w" "FAILURE workload-w"))
    (flush)
    (System/exit (if (= :pass (:status result)) 0 1)))
  (catch Throwable error
    (emit! "W-RESULT" {:status :failure :message (str error) :data (ex-data error)})
    (println "FAILURE workload-w")
    (flush)
    (System/exit 1)))
