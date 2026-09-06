(ns dao.runtime.v2.driver-test
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [dao.runtime.v2 :as rt]
            [dao.runtime.v2.driver :as driver]
            [dao.stream.v2 :as stream]
            [dao.stream.v2.ringbuffer :as ringbuffer]))


(defn- new-stream
  "A v2 ring buffer handle. The v1 driver tests parked on a
   NonWaitableStream from dao.test-utils; the v2 fixture is the v2 reference
   transport, and no v2 test opens anything through v1."
  [capacity]
  (let [result (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                                    ringbuffer/capacity-key capacity})]
    (if (= :dao.stream/ok (:dao.stream/outcome result))
      (:dao.stream/handle result)
      (throw (ex-info "Test stream creation failed" {:result result})))))


(defn- oldest-cursor
  "The oldest-anchor cursor of handle, as an opaque value."
  [handle]
  (:dao.stream/cursor (stream/cursor handle stream/anchor-oldest)))


(defn- park-reader!
  "Park a reader task on an empty stream in the driver's runtime and return
   the driver atom. The state container is the test's, not the driver's."
  [driver-atom handle task]
  (let [parked (:state (rt/handle-read (:rt @driver-atom)
                                       handle
                                       (oldest-cursor handle)
                                       task))]
    (swap! driver-atom assoc :rt parked)
    driver-atom))


(deftest run-pending-drains-internally-enqueued-work-test
  (async
    done
    (testing
      "one run-pending! tick drains work a resume enqueues into the ready queue, where the v1 driver stepped one entry per tick"
      (let [seen (atom [])
            driver-atom (atom (driver/make-driver 20))
            second-task {:resume
                         (fn [rt _entry _value] (swap! seen conj :second) rt)}
            first-task {:resume (fn [rt _entry _value]
                                  (swap! seen conj :first)
                                  (rt/enqueue-ready rt [second-task]))}]
        (driver/schedule-work! driver-atom [first-task])
        (js/setTimeout (fn []
                         (is (= [:first :second] @seen))
                         (let [rt-state (:rt @driver-atom)]
                           (is (empty? (:ready-queue rt-state)))
                           (is (empty? (:wait-set rt-state))))
                         ;; Nothing parked: the driver went quiet, no
                         ;; continuation pending.
                         (is (not (:scheduled? @driver-atom)))
                         (is (not (:polling? @driver-atom)))
                         (done))
                       20)))))


(deftest schedule-work-keeps-polling-while-runtime-has-parked-waits-test
  (async
    done
    (testing
      "the poll timer keeps firing, so a reader parked on an empty stream wakes when a value is appended with no further schedule-work!"
      (let [seen (atom [])
            handle (new-stream 4)
            task {:resume (fn [rt _entry value] (swap! seen conj value) rt)}
            ;; 5 ms, not the 20 ms default: the cadence is a parameter.
            driver-atom (atom (driver/make-driver 5))]
        (park-reader! driver-atom handle task)
        (driver/schedule-work! driver-atom [])
        (js/setTimeout (fn []
                         ;; The value arrives after the driver has gone
                         ;; quiet; only the poll timer can run the reader.
                         (stream/append! handle :payload)
                         (js/setTimeout (fn [] (is (= [:payload] @seen)) (done))
                                        20))
                       0)))))


(deftest schedule-work-runs-ready-entries-without-waiting-for-poll-timer-test
  (async
    done
    (testing
      "ready entries run on the next microtask even when a wait-set poll timer is pending"
      (let [seen (atom [])
            handle (new-stream 4)
            parked-task {:resume
                         (fn [rt _entry value] (swap! seen conj value) rt)}
            ready-task {:resume
                        (fn [rt _entry _value] (swap! seen conj :ready) rt)}
            driver-atom (atom (driver/make-driver 20))]
        (park-reader! driver-atom handle parked-task)
        (driver/schedule-work! driver-atom [])
        (js/queueMicrotask
          (fn []
            (driver/schedule-work! driver-atom [ready-task])
            (js/setTimeout
              (fn []
                (is (= [:ready] @seen))
                ;; Test hygiene: resolve the parked reader and out-wait one
                ;; cadence, so this test leaves no live poll chain. A
                ;; permanently parked task polls forever, and a leaked real
                ;; tick that fires inside a later test's fake-timer window
                ;; would register there and corrupt its timer count.
                (stream/append! handle :payload)
                (js/setTimeout (fn [] (done)) 25))
              5)))))))


(defn- fake-timers!
  "Replace js/setTimeout and js/clearTimeout with a counting registry.
   Returns {:timers … :real-set-timeout … :cleanup! …}: the atom of live
   timers keyed by id, the real setTimeout captured before the swap, and a fn
   restoring the real globals."
  []
  (let [real-set-timeout js/setTimeout
        real-clear-timeout js/clearTimeout
        timer-id (atom 0)
        timers (atom {})]
    (set! js/setTimeout
          (fn [f _ms]
            (let [id (swap! timer-id inc)]
              (swap! timers assoc id f)
              #js {:id id, :unref (fn [] nil)})))
    (set! js/clearTimeout
          (fn [handle]
            (when handle (swap! timers dissoc (.-id handle)))))
    {:timers timers
     :real-set-timeout real-set-timeout
     :cleanup! (fn []
                 (set! js/setTimeout real-set-timeout)
                 (set! js/clearTimeout real-clear-timeout))}))


(deftest schedule-work-does-not-leave-stale-poll-timer-after-poll-break-test
  (async
    done
    (testing
      "expediting ready work during polling leaves exactly one live poll timer"
      (let [{:keys [timers real-set-timeout cleanup!]} (fake-timers!)
            seen (atom [])
            handle (new-stream 4)
            parked-task {:resume
                         (fn [rt _entry value] (swap! seen conj value) rt)}
            ready-task {:resume
                        (fn [rt _entry _value] (swap! seen conj :ready) rt)}
            driver-atom (atom (driver/make-driver 20))]
        (try (park-reader! driver-atom handle parked-task)
             (driver/schedule-work! driver-atom [])
             (real-set-timeout
               (fn []
                 (try (driver/schedule-work! driver-atom [ready-task])
                      (real-set-timeout
                        (fn []
                          (try (is (= [:ready] @seen))
                               (is (= 1 (count @timers))
                                   (str "expected one live poll timer, saw "
                                        (count @timers)))
                               (finally (cleanup!) (done))))
                        0)
                      (catch :default e (cleanup!) (done) (throw e))))
               0)
             (catch :default e (cleanup!) (done) (throw e)))))))


(deftest schedule-work-coalesces-poll-break-microtasks-test
  (async
    done
    (testing
      "repeated schedule-work! calls while polling coalesce into one poll-break"
      (let [{:keys [timers real-set-timeout cleanup!]} (fake-timers!)
            seen (atom [])
            handle (new-stream 4)
            parked-task {:resume
                         (fn [rt _entry value] (swap! seen conj value) rt)}
            ready-a {:resume (fn [rt _entry _value] (swap! seen conj :a) rt)}
            ready-b {:resume (fn [rt _entry _value] (swap! seen conj :b) rt)}
            driver-atom (atom (driver/make-driver 20))]
        (try
          (park-reader! driver-atom handle parked-task)
          (driver/schedule-work! driver-atom [])
          (real-set-timeout
            (fn []
              (try
                (driver/schedule-work! driver-atom [ready-a])
                (driver/schedule-work! driver-atom [ready-b])
                (real-set-timeout
                  (fn []
                    (try
                      (is (= [:a :b] @seen))
                      (is
                        (= 1 (count @timers))
                        (str
                          "expected one live poll timer after coalescing, saw "
                          (count @timers)))
                      (finally (cleanup!) (done))))
                  0)
                (catch :default e (cleanup!) (done) (throw e))))
            0)
          (catch :default e (cleanup!) (done) (throw e)))))))
