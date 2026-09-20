(ns dao.stream.waitset.driver-test
  "The JVM wake source, over v2 ring buffers and owner loops written in the
   test — the shape the plan prescribes for the host layer.

   The driver itself is sleep machinery only, so every test here is an owner
   loop the test owns: `check` → consume `:woken` → `cadence-step` →
   `sleep!` → recur, with the waitset, the store, and the cadence state as
   loop locals. Nothing asserts on the queue's internals; everything asserts
   on what the owner observed and when."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.waitset :as waitset]
            [dao.stream.waitset.cadence :as cadence]
            [dao.stream.waitset.driver :as driver]))


;; =============================================================================
;; Fixtures: one ring buffer, one probe over it
;; =============================================================================


(defn- buffer
  [capacity]
  (:dao.stream/handle
    (ring/create! {:dao.stream/type ring/transport-type
                   ring/capacity-key capacity})))


(defn- oldest
  [handle]
  (:dao.stream/cursor (stream/cursor handle stream/anchor-oldest)))


(defn- probe-resolver
  "The non-advancing readiness probe the plan names: a `:next` entry whose
   resolve reads the consumer's own current cursor out of the store and
   whose advance is identity — the waitset commits nothing."
  [polls]
  {:resolve (fn [store _entry]
              (when polls (swap! polls inc))
              {:stream (:medium store) :cursor (:cursor store)})
   :advance (fn [store _entry _cursor] store)})


(defn- elapsed-ms
  [start-nanos]
  (quot (- (System/nanoTime) start-nanos) 1000000))


;; =============================================================================
;; Wake tokens
;; =============================================================================


(deftest repeated-nudges-coalesce-into-one-wake
  (let [wake (driver/make-wake)]
    (doseq [_ (range 3)] (driver/nudge! wake))
    (let [start (System/nanoTime)]
      (is (true? (driver/sleep! wake 5000))
          "a nudge ends a 5 s sleep at once, not at the timeout")
      (is (< (elapsed-ms start) 1000) "it did not wait the interval out")
      (is (false? (driver/sleep! wake 10))
          "the surplus tokens were drained: three nudges bought one wake, and
           the next sleep waits its own timeout"))))


(deftest a-nudge-from-another-thread-beats-the-timeout
  (let [wake (driver/make-wake)]
    (.start (Thread. ^Runnable (fn []
                                 (Thread/sleep 20)
                                 (driver/nudge! wake))))
    (let [start (System/nanoTime)]
      (is (true? (driver/sleep! wake 5000)))
      (is (< (elapsed-ms start) 1000)
          "a deposit on another thread ends the owner's idle sleep immediately"))))


;; =============================================================================
;; Owner loops
;; =============================================================================


(defn- start-probe-owner
  "An owner loop the test owns, polling one probe over `medium` with no
   nudges: check → consume → cadence-step → sleep → recur, recording each
   round. `:wake`, when given, is slept on through `sleep!` so a nudge can
   end the idle sleep; without one the owner simply sleeps the interval.
   Returns a map of the recorded atoms plus `stop!`."
  [medium & {:keys [poll-ms wake]}]
  (let [polls (atom 0)
        received (atom [])
        rounds (atom [])
        stop (atom false)
        sleep (if wake
                (fn [ms] (driver/sleep! wake ms))
                (fn [^long ms] (Thread/sleep ms)))
        runner (fn []
                 (loop [ws (waitset/park (waitset/empty-waitset)
                                         {:reason :next, :probe true})
                        cadence (cadence/init {:poll-ms poll-ms
                                               :backoff {:factor 2
                                                         :ceiling-ms 40}})
                        store {:medium medium, :cursor (oldest medium)}]
                   (when-not @stop
                     (let [result (waitset/check ws (probe-resolver polls) store)
                           woken (:woken result)
                           ws' (:waiting (:waitset result))
                           ;; The compound step is the sole authority: a woken
                           ;; probe's cursor is advisory, adopted here by the
                           ;; consumer's own step, never by the waitset.
                           store' (if (seq woken)
                                    (assoc (:store result)
                                           :cursor (:cursor (last woken)))
                                    (:store result))]
                       (doseq [w woken]
                         (swap! received conj (:value w)))
                       ;; Probe rule 1: retire on terminal, re-park after a
                       ;; non-terminal step that still needs observation.
                       (let [ws'' (if (seq woken)
                                    (waitset/park {:waiting ws'}
                                                 {:reason :next, :probe true})
                                    {:waiting ws'})
                             {:keys [cadence-state sleep-ms]}
                             (cadence/cadence-step cadence (seq woken))]
                         (swap! rounds conj {:sleep-ms sleep-ms, :woken (count woken)})
                         (sleep sleep-ms)
                         (recur ws'' cadence-state store'))))))
        thread (doto (Thread. ^Runnable runner)
                 (.start))]
    {:polls polls
     :received received
     :rounds rounds
     :stop! (fn [] (reset! stop true) (.join thread 5000))}))


(deftest a-parked-wait-set-keeps-being-polled-with-no-nudges
  ;; No wake is given: the owner sleeps each interval plainly, which is the
  ;; fact — the parked entry is polled every round and no nudge ever arrives.
  (let [medium (buffer 8)
        owner (start-probe-owner medium :poll-ms 5)]
    (try
      (Thread/sleep 120)
      (is (>= @(:polls owner) 3)
          "every round polled the parked probe — no nudge ever arrived")
      (is (= [] @(:received owner)) "nothing to read: nothing woke")
      (stream/append! medium :v)
      (let [deadline (+ (System/nanoTime) (long 5e9))]
        (while (and (empty? @(:received owner))
                    (< (System/nanoTime) deadline))
          (Thread/sleep 5))
        (is (= [:v] @(:received owner))
            "a value appended mid-flight is picked up by the next round's own
             poll, never by a notification"))
      (finally
        ((:stop! owner))))))


(deftest an-idle-owner-climbs-to-the-ceiling-and-a-wake-resets-the-curve
  (let [medium (buffer 8)
        ;; A long :poll-ms with a fast ceiling: the rounds record the armed
        ;; interval, which is the only place the curve is observable.
        owner (start-probe-owner medium :poll-ms 5)]
    (try
      (Thread/sleep 150)
      (let [sleeps (mapv :sleep-ms @(:rounds owner))]
        (is (some #{40} sleeps)
            "the idle curve climbed to the ceiling and the owner kept polling
             there — alive, not stopped")
        (is (every? #(<= % 40) sleeps) "the ceiling is a cap"))
      ;; A value appended by another hand is the wake: the next round after
      ;; it consumed the value re-arms at :poll-ms.
      (stream/append! medium :w)
      (let [deadline (+ (System/nanoTime) (long 5e9))]
        (while (and (empty? @(:received owner))
                    (< (System/nanoTime) deadline))
          (Thread/sleep 5))
        (is (= [:w] @(:received owner)))
        (let [rounds @(:rounds owner)
              woken-idx (count (take-while (comp zero? :woken) rounds))
              after (drop woken-idx (mapv :sleep-ms rounds))]
          (is (= 5 (first after))
              "the round after the wake armed :poll-ms again, not the ceiling")))
      (finally
        ((:stop! owner))))))


;; =============================================================================
;; The composition-wrapped deposit
;; =============================================================================


(deftest a-composition-wrapped-deposit-nudges-and-the-adapter-holds-no-driver-reference
  (let [medium (buffer 4)
        wake (driver/make-wake)
        received (atom [])
        stop (atom false)
        ;; The composition wraps the deposit operation it hands the adapter
        ;; with the nudge; the adapter is handed nothing else.
        deposit! (fn [value]
                   (let [result (stream/append! medium value)]
                     (driver/nudge! wake)
                     result))
        adapter {:deposit! deposit!}
        owner (future
                (loop [ws (waitset/park (waitset/empty-waitset)
                                         {:reason :next, :probe true})
                       store {:medium medium, :cursor (oldest medium)}]
                  (if @stop
                    nil
                    (let [result (waitset/check ws (probe-resolver nil) store)
                          woken (:woken result)
                          ws' (:waiting (:waitset result))
                          ;; The consumer's own step adopts the advisory
                          ;; cursor; the waitset committed nothing.
                          store' (if (seq woken)
                                   (assoc (:store result)
                                          :cursor (:cursor (last woken)))
                                   (:store result))]
                      (doseq [w woken] (swap! received conj (:value w)))
                      ;; The idle sleep is the ceiling: only the nudge can end
                      ;; it early, and that is the whole fact under test.
                      (driver/sleep! wake 5000)
                      (recur (if (seq woken)
                               (waitset/park {:waiting ws'} {:reason :next, :probe true})
                               {:waiting ws'})
                             store')))))
        start (System/nanoTime)]
    (try
      ((:deposit! adapter) :hello)
      (let [deadline (+ (System/nanoTime) (long 5e9))]
        (while (and (empty? @received)
                    (< (System/nanoTime) deadline))
          (Thread/sleep 5)))
      (is (= [:hello] @received)
          "the deposit's nudge ended the 5 s idle sleep at once")
      (is (< (elapsed-ms start) 4000) "far sooner than the armed interval")
      (is (= [:deposit!] (keys adapter))
          "the adapter holds exactly the wrapped deposit")
      (is (not-any? #(identical? % wake) (vals adapter))
          "and no reference to the wake source or the driver crossed into it")
      (finally
        (reset! stop true)
        (driver/nudge! wake)
        @owner))))
