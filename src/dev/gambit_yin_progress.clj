(ns gambit-yin-progress
  "Report the pinned Gambit source loader's startup trace as it arrives.")


(def ^:private trace-line #"\[\d+s\] (load|ready) (\S+)")
(def ^:private startup-namespace "jolt.gambit.yin")
(def ^:private heartbeat-ns 15000000000)


(defn- seconds
  [start end]
  (format "%.1fs" (/ (- end start) 1.0e9)))


(defn- load-line
  [{:keys [pending ready started complete?] :as state} line now verbose?]
  (if-let [[_ event namespace] (re-matches trace-line line)]
    (do
      (when verbose?
        (.println System/err line))
      (cond
        complete? state

        (= event "load")
        (let [state (update state :pending conj [namespace now])]
          (if started
            state
            (do
              (.println System/err (str "yin-load [0] " namespace " loading"))
              (assoc state :started now :last-report now))))

        (= namespace (first (peek pending)))
        (let [ready (inc ready)
              complete? (= namespace startup-namespace)]
          (.println System/err (str "yin-load [" ready "] " namespace
                                    " ready (" (seconds (second (peek pending)) now) ")"))
          (when complete?
            (.println System/err (str "yin-load complete: " ready " namespaces in "
                                      (seconds started now))))
          (assoc state :pending (pop pending) :ready ready
                 :last-report now :complete? complete?))

        :else state))
    (do (.println System/err line) state)))


(defn- heartbeat
  [{:keys [pending ready last-report] :as state} now]
  (if (and (seq pending) (>= (- now last-report) heartbeat-ns))
    (let [[namespace started] (peek pending)]
      (.println System/err (str "yin-load [" ready "] " namespace
                                " loading (" (seconds started now) " elapsed)"))
      (assoc state :last-report now))
    state))


(defn relay!
  "Forward Gambit stderr and count startup completions. Keep the raw stamped
   load/ready lines when the caller explicitly requested JOLT_TRACE_SOURCE."
  [verbose? reader]
  (let [read-line! #(future (.readLine reader))]
    (loop [state {:pending [] :ready 0}
           next-line (read-line!)]
      (let [line (if (seq (:pending state))
                   (let [remaining (- heartbeat-ns
                                      (- (System/nanoTime) (:last-report state)))]
                     (deref next-line (max 1 (quot remaining 1000000)) ::idle))
                   @next-line)
            now (System/nanoTime)]
        (cond
          (= line ::idle) (recur (heartbeat state now) next-line)
          (some? line) (recur (heartbeat (load-line state line now verbose?) now)
                              (read-line!)))))))
