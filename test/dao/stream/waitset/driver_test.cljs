(ns dao.stream.waitset.driver-test
  "The Node wake source, over v2 ring buffers and owner ticks written in the
   test — the shape the plan prescribes for the host layer.

   The driver itself is one timer and a pending flag, so every test here is
   an owner tick the test owns: `check` → consume `:woken` → `cadence-step`
   → `arm!`, with the state box the tick alone reads and writes. Nothing
   asserts on the timer id; everything asserts on what the tick observed
   and when."
  (:require [cljs.test :refer [async deftest is]]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.waitset :as waitset]
            [dao.stream.waitset.cadence :as cadence]
            [dao.stream.waitset.driver :as driver]))


;; =============================================================================
;; Fixtures
;; =============================================================================


(defn- buffer
  [capacity]
  (:dao.stream/handle
    (ring/create! {:dao.stream/type ring/transport-type
                   ring/capacity-key capacity})))


(defn- oldest
  [handle]
  (:dao.stream/cursor (stream/cursor handle stream/anchor-oldest)))


(defn- wait-for
  "Poll `pred` every 10ms until it returns truthy or `deadline-ms` passes.
   The callback receives the value or nil, exactly once."
  [pred deadline-ms cb]
  (let [deadline (+ (.now js/Date) deadline-ms)]
    (letfn [(poll
              []
              (let [v (try (pred) (catch :default _ nil))]
                (cond
                  v (cb v)
                  (< deadline (.now js/Date)) (cb nil)
                  :else (js/setTimeout poll 10))))]
      (poll))))


(defn- after-ms
  "Invoke `(cb ticks-then)` after `ms`, on a timer the test owns."
  [ms cb]
  (js/setTimeout cb ms))


;; =============================================================================
;; The wake source
;; =============================================================================


(deftest an-armed-timer-fires-the-tick-and-only-the-tick
  (async done
    (let [ticks (atom 0)
          wake-ref (volatile! nil)
          wake (driver/make-wake
                 (fn []
                   (swap! ticks inc)
                   ;; The owner re-arms itself; three ticks are enough.
                   (when (< @ticks 3)
                     (driver/arm! @wake-ref 5))))
          start (.now js/Date)]
      (vreset! wake-ref wake)
      (driver/arm! wake 5)
      (wait-for #(>= @ticks 3) 2000
        (fn [reached]
          (is (some? reached) "three timer-driven ticks happened")
          (is (= 3 @ticks) "the timer fired the composition root's own tick")
          (is (<= 10 (- (.now js/Date) start))
              "spaced by the armed intervals, not a busy loop: three 5 ms
               ticks take at least a few real milliseconds")
          (done))))))


(deftest a-nudge-beats-the-armed-timer
  (async done
    (let [ticks (atom 0)
          fired-at (atom nil)
          start (.now js/Date)
          wake (driver/make-wake (fn [] (swap! ticks inc)
                                         (reset! fired-at (- (.now js/Date) start))))]
      ;; A long interval is armed; the nudge must end it far sooner.
      (driver/arm! wake 300)
      (driver/nudge! wake)
      (after-ms 50
        (fn []
          (is (= 1 @ticks) "the nudge ran the tick once, at once")
          (is (< @fired-at 100) "far before the 300 ms interval")
          (done))))))


(deftest repeated-nudges-coalesce-into-one-tick
  (async done
    (let [ticks (atom 0)
          wake (driver/make-wake (fn [] (swap! ticks inc)))]
      (driver/arm! wake 300)
      ;; Three nudges land before the armed timer could fire.
      (driver/nudge! wake)
      (driver/nudge! wake)
      (driver/nudge! wake)
      (after-ms 50
        (fn []
          (is (= 1 @ticks) "three nudges bought exactly one tick")
          (done))))))


(deftest a-nudge-from-inside-a-tick-does-not-re-enter-it
  (async done
    (let [ticks (atom 0)
          wake-ref (volatile! nil)
          tick (fn []
                 (swap! ticks inc)
                 ;; A transition the tick itself caused wants a re-check —
                 ;; the one shape that could re-enter if it were allowed to.
                 (driver/nudge! @wake-ref))
          wake (driver/make-wake tick)]
      (vreset! wake-ref wake)
      (driver/nudge! wake)
      (after-ms 50
        (fn []
          (is (= 1 @ticks)
              "the in-tick nudge scheduled nothing: no re-entry, and the tick
               never re-armed a second timer beside itself")
          (done))))))


;; =============================================================================
;; The owner tick: waitset + cadence, kept alive by the timer alone
;; =============================================================================


(deftest a-parked-wait-set-keeps-being-polled-with-no-nudges
  (async done
    (let [medium (buffer 8)
          polls (atom 0)
          received (atom [])
          box (atom {:waitset (waitset/park (waitset/empty-waitset)
                                            {:reason :next, :probe true})
                     :store {:medium medium, :cursor (oldest medium)}
                     :cadence (cadence/init {:poll-ms 5})})
          wake-ref (volatile! nil)
          wake (driver/make-wake
                 (fn []
                   (let [{:keys [waitset store cadence]} @box
                         _ (swap! polls inc)
                         result (waitset/check waitset
                                               {:resolve (fn [s _e] {:stream (:medium s)
                                                                     :cursor (:cursor s)})
                                                :advance (fn [s _e _c] s)}
                                               store)
                         woken (:woken result)
                         ws' (:waiting (:waitset result))
                         ;; The consumer's own step adopts the advisory
                         ;; cursor; the waitset committed nothing.
                         store' (if (seq woken)
                                  (assoc (:store result)
                                         :cursor (:cursor (last woken)))
                                  (:store result))
                         ws'' (if (seq woken)
                                (waitset/park {:waiting ws'}
                                              {:reason :next, :probe true})
                                {:waiting ws'})
                         {:keys [cadence-state sleep-ms]}
                         (cadence/cadence-step cadence (seq woken))]
                     (doseq [w woken]
                       (swap! received conj (:value w)))
                     (reset! box {:waitset ws''
                                  :store store'
                                  :cadence cadence-state})
                     (driver/arm! @wake-ref sleep-ms))))]
      (vreset! wake-ref wake)
      (driver/arm! wake 5)
      ;; Idle first: the parked probe is polled every round, no nudge ever.
      (after-ms 120
        (fn []
          (is (>= @polls 3) "every round polled; nothing but the timer ran it")
          (is (= [] @received))
          (stream/append! medium :v)
          (wait-for #(= [:v] @received) 2000
            (fn [arrived]
              (is (some? arrived)
                  "a value appended mid-flight is picked up by the next round's
                   own poll, never by a notification")
              (is (= [:v] @received))
              (driver/disarm! @wake-ref)
              (done))))))))


;; =============================================================================
;; The composition-wrapped deposit
;; =============================================================================


(deftest a-composition-wrapped-deposit-nudges-and-the-adapter-holds-no-driver-reference
  (async done
    (let [medium (buffer 4)
          received (atom [])
          store (atom {:medium medium, :cursor (oldest medium)})
          ws (atom (waitset/park (waitset/empty-waitset)
                                 {:reason :next, :probe true}))
          wake-ref (volatile! nil)
          wake (driver/make-wake
                 (fn []
                   (let [result (waitset/check @ws
                                               {:resolve (fn [s _e] {:stream (:medium s)
                                                                     :cursor (:cursor s)})
                                                :advance (fn [s _e _c] s)}
                                               @store)
                         woken (:woken result)
                         ws' (:waiting (:waitset result))]
                     (doseq [w woken]
                       (swap! received conj (:value w)))
                     (when (seq woken)
                       (reset! ws (waitset/park {:waiting ws'}
                                                {:reason :next, :probe true}))
                       (reset! store (assoc @store :cursor (:cursor (last woken))))
                       ;; A wake resets the curve; the next idle sleep is the
                       ;; base interval again.
                       (driver/arm! @wake-ref 25))
                     ;; Idle with nothing moving: the backoff ceiling, which
                     ;; only a nudge can end early.
                     (when (empty? woken)
                       (driver/arm! @wake-ref 5000)))))
          ;; The composition wraps the deposit operation it hands the adapter
          ;; with the nudge; the adapter is handed nothing else.
          deposit! (fn [value]
                     (let [result (stream/append! medium value)]
                       (driver/nudge! @wake-ref)
                       result))
          adapter {:deposit! deposit!}]
      (vreset! wake-ref wake)
      ;; The first arm is a long idle interval: only the nudge can end it.
      (driver/arm! wake 5000)
      ((:deposit! adapter) :hello)
      (wait-for #(= [:hello] @received) 2000
        (fn [arrived]
          (is (some? arrived)
              "the deposit's nudge ended the idle sleep at once, not at the ceiling")
          (is (= [:hello] @received))
          (is (= [:deposit!] (keys adapter))
              "the adapter holds exactly the wrapped deposit")
          (is (not-any? #(= % @wake-ref) (vals adapter))
              "and no reference to the wake source or the driver crossed into it")
          (driver/disarm! @wake-ref)
          (done))))))


;; =============================================================================
;; Liveness: a failing tick and a self-nudging tick never kill the owner
;; =============================================================================


(deftest a-tick-whose-body-fails-before-its-own-arm-still-ticks-again
  (async done
    ;; The shell tick's shape (yin.repl's run-node!): arm a fallback first
    ;; and let the body's own arm! replace it. A body that fails before its
    ;; own arm — the exact state a throw leaves behind — must not kill the
    ;; owner: the interval timer this driver replaced fired again whatever
    ;; happened, and the fallback is what keeps that promise. (A literal
    ;; throw from a timer callback is an uncaughtException on this host, so
    ;; the failed body is simulated by arming nothing.)
    (let [calls (atom 0)
          wake-ref (volatile! nil)
          tick (fn []
                 (swap! calls inc)
                 ;; the shell's guard: the fallback first
                 (driver/arm! @wake-ref 5)
                 ;; the body: its first round fails before its own arm
                 (when (> @calls 1)
                   (driver/arm! @wake-ref 20)))
          wake (driver/make-wake tick)]
      (vreset! wake-ref wake)
      (driver/arm! wake 5)
      (wait-for #(>= @calls 3) 2000
        (fn [reached]
          (is (some? reached) "the failed body did not kill the owner")
          (is (>= @calls 3))
          (driver/disarm! @wake-ref)
          (done))))))


(deftest a-nudge-from-inside-a-tick-leaves-the-timer-it-armed-alone
  (async done
    ;; A composition whose tick arms its next round and then deposits — the
    ;; deposit nudging — must not lose the timer the tick just armed: while
    ;; the pending flag is up, a nudge touches nothing.
    (let [ticks (atom 0)
          wake-ref (volatile! nil)
          tick (fn []
                 (swap! ticks inc)
                 (driver/arm! @wake-ref 10)
                 (driver/nudge! @wake-ref))
          wake (driver/make-wake tick)]
      (vreset! wake-ref wake)
      ;; Start the owner from a nudge, exactly as a deposit would.
      (driver/nudge! wake)
      (wait-for #(>= @ticks 3) 2000
        (fn [reached]
          (is (some? reached)
              "the tick kept its armed round: the in-tick nudge neither
               disarmed it nor stranded the owner with neither timer nor
               microtask")
          (is (>= @ticks 3))
          (driver/disarm! @wake-ref)
          (done))))))
