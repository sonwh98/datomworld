(ns dao.gui.event-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.gui.event :as event]
    [dao.stream :as ds]
    [dao.stream.ringbuffer]))


(defn- make-stream
  ([] (ds/open! {:type :ringbuffer, :capacity nil}))
  ([capacity eviction-policy]
   (ds/open! {:type :ringbuffer,
              :capacity capacity,
              :eviction-policy eviction-policy})))

(defn- put-geometry!
  [geom-stream frame-id nodes]
  (ds/put! geom-stream {:frame-id frame-id, :nodes nodes}))

(defn- put-tap!
  [tap-stream frame-id x y]
  (ds/put! tap-stream {:frame-id frame-id, :position {:x x, :y y}}))


;; =============================================================================
;; Hit-testing
;; =============================================================================

(deftest tap-inside-rect-dispatches
  (let [geom-stream (make-stream)
        tap-stream (make-stream)
        signals (make-stream)
        dispatched (atom [])
        rt (event/create-runtime! {:geometry-stream geom-stream
                                    :tap-stream tap-stream
                                    :signal-stream signals})]
    (event/register! rt ::btn :tap #(swap! dispatched conj %))
    (put-geometry! geom-stream 0
                   [{:node-id ::btn :event :tap
                     :regions [{:bounds [10 20 100 50] :paint-order 1}]}])
    (put-tap! tap-stream 0 60 45)
    (event/process-pending! rt)
    (is (= 1 (count @dispatched)))
    (is (= {:node-id ::btn :event-kind :tap :position {:x 60 :y 45}}
           (first @dispatched)))))

(deftest tap-outside-rect-is-silent
  (let [geom-stream (make-stream)
        tap-stream (make-stream)
        signals (make-stream)
        dispatched (atom [])
        rt (event/create-runtime! {:geometry-stream geom-stream
                                    :tap-stream tap-stream
                                    :signal-stream signals})]
    (event/register! rt ::btn :tap #(swap! dispatched conj %))
    (put-geometry! geom-stream 0
                   [{:node-id ::btn :event :tap
                     :regions [{:bounds [10 20 100 50] :paint-order 1}]}])
    (put-tap! tap-stream 0 5 5)
    (event/process-pending! rt)
    (is (empty? @dispatched) "tap outside rect should not dispatch")))

(deftest topmost-region-wins
  (let [geom-stream (make-stream)
        tap-stream (make-stream)
        signals (make-stream)
        dispatched (atom [])
        rt (event/create-runtime! {:geometry-stream geom-stream
                                    :tap-stream tap-stream
                                    :signal-stream signals})]
    (event/register! rt ::back :tap #(swap! dispatched conj {:node ::back}))
    (event/register! rt ::front :tap #(swap! dispatched conj {:node ::front}))
    (put-geometry! geom-stream 0
                   [{:node-id ::back :event :tap
                     :regions [{:bounds [0 0 100 100] :paint-order 1}]}
                    {:node-id ::front :event :tap
                     :regions [{:bounds [25 25 50 50] :paint-order 2}]}])
    (put-tap! tap-stream 0 50 50)
    (event/process-pending! rt)
    (is (= 1 (count @dispatched)))
    (is (= ::front (:node (first @dispatched))))))

(deftest half-open-boundary-right-edge
  (let [geom-stream (make-stream)
        tap-stream (make-stream)
        signals (make-stream)
        dispatched (atom [])
        rt (event/create-runtime! {:geometry-stream geom-stream
                                    :tap-stream tap-stream
                                    :signal-stream signals})]
    (event/register! rt ::btn :tap #(swap! dispatched conj %))
    (put-geometry! geom-stream 0
                   [{:node-id ::btn :event :tap
                     :regions [{:bounds [0 0 10 10] :paint-order 1}]}])
    (put-tap! tap-stream 0 10 5)
    (event/process-pending! rt)
    (is (empty? @dispatched) "tap at [x+w, y] (right edge) should be outside")))

(deftest half-open-boundary-top-edge
  (let [geom-stream (make-stream)
        tap-stream (make-stream)
        signals (make-stream)
        dispatched (atom [])
        rt (event/create-runtime! {:geometry-stream geom-stream
                                    :tap-stream tap-stream
                                    :signal-stream signals})]
    (event/register! rt ::btn :tap #(swap! dispatched conj %))
    (put-geometry! geom-stream 0
                   [{:node-id ::btn :event :tap
                     :regions [{:bounds [0 0 10 10] :paint-order 1}]}])
    (put-tap! tap-stream 0 5 10)
    (event/process-pending! rt)
    (is (empty? @dispatched) "tap at [x, y+h] (top edge) should be outside")))


;; =============================================================================
;; Frame-causality rules
;; =============================================================================

(deftest stale-frame-tap-is-dropped
  (let [geom-stream (make-stream)
        tap-stream (make-stream)
        signals (make-stream)
        rt (event/create-runtime! {:geometry-stream geom-stream
                                    :tap-stream tap-stream
                                    :signal-stream signals})]
    (event/register! rt ::btn :tap (fn [_]))
    (put-geometry! geom-stream 0 [{:node-id ::btn :event :tap
                                    :regions [{:bounds [0 0 100 100] :paint-order 1}]}])
    (put-geometry! geom-stream 1 [{:node-id ::btn :event :tap
                                    :regions [{:bounds [0 0 100 100] :paint-order 1}]}])
    (event/process-pending! rt)
    (put-tap! tap-stream 0 50 50)
    (event/process-pending! rt)
    (let [diag (:ok (ds/next signals {:position 0}))]
      (is (= :dao.gui.event/stale-frame-tap (:diagnostic/kind diag)))
      (is (= :error (:severity diag))))))

(deftest future-frame-tap-is-dropped
  (let [geom-stream (make-stream)
        tap-stream (make-stream)
        signals (make-stream)
        rt (event/create-runtime! {:geometry-stream geom-stream
                                    :tap-stream tap-stream
                                    :signal-stream signals})]
    (event/register! rt ::btn :tap (fn [_]))
    (put-geometry! geom-stream 0 [{:node-id ::btn :event :tap
                                    :regions [{:bounds [0 0 100 100] :paint-order 1}]}])
    (event/process-pending! rt)
    (put-tap! tap-stream 9 50 50)
    (event/process-pending! rt)
    (let [diag (:ok (ds/next signals {:position 0}))]
      (is (= :dao.gui.event/future-frame-tap (:diagnostic/kind diag)))
      (is (= :error (:severity diag))))))

(deftest no-active-frame-tap-is-dropped
  (let [geom-stream (make-stream)
        tap-stream (make-stream)
        signals (make-stream)
        rt (event/create-runtime! {:geometry-stream geom-stream
                                    :tap-stream tap-stream
                                    :signal-stream signals})]
    (event/register! rt ::btn :tap (fn [_]))
    (put-tap! tap-stream 0 50 50)
    (event/process-pending! rt)
    (let [diag (:ok (ds/next signals {:position 0}))]
      (is (= :dao.gui.event/no-active-frame (:diagnostic/kind diag)))
      (is (= :error (:severity diag))))))


;; =============================================================================
;; Subscriber model
;; =============================================================================

(deftest multiple-subscribers-all-invoked
  (let [geom-stream (make-stream)
        tap-stream (make-stream)
        signals (make-stream)
        calls (atom [])
        sub-a (fn [e] (swap! calls conj :a))
        sub-b (fn [e] (swap! calls conj :b))
        rt (event/create-runtime! {:geometry-stream geom-stream
                                    :tap-stream tap-stream
                                    :signal-stream signals})]
    (event/register! rt ::btn :tap sub-a)
    (event/register! rt ::btn :tap sub-b)
    (put-geometry! geom-stream 0 [{:node-id ::btn :event :tap
                                    :regions [{:bounds [0 0 100 100] :paint-order 1}]}])
    (put-tap! tap-stream 0 50 50)
    (event/process-pending! rt)
    (is (= [:a :b] @calls))))

(deftest subscriber-failure-does-not-prevent-later-subscribers
  (let [geom-stream (make-stream)
        tap-stream (make-stream)
        signals (make-stream)
        calls (atom [])
        sub-a (fn [e] (throw (ex-info "boom" {})))
        sub-b (fn [e] (swap! calls conj :b))
        rt (event/create-runtime! {:geometry-stream geom-stream
                                    :tap-stream tap-stream
                                    :signal-stream signals})]
    (event/register! rt ::btn :tap sub-a)
    (event/register! rt ::btn :tap sub-b)
    (put-geometry! geom-stream 0 [{:node-id ::btn :event :tap
                                    :regions [{:bounds [0 0 100 100] :paint-order 1}]}])
    (put-tap! tap-stream 0 50 50)
    (event/process-pending! rt)
    (is (= [:b] @calls))))

(deftest no-subscriber-silences-tap
  (let [geom-stream (make-stream)
        tap-stream (make-stream)
        signals (make-stream)
        rt (event/create-runtime! {:geometry-stream geom-stream
                                    :tap-stream tap-stream
                                    :signal-stream signals})]
    (put-geometry! geom-stream 0 [{:node-id ::btn :event :tap
                                    :regions [{:bounds [0 0 100 100] :paint-order 1}]}])
    (put-tap! tap-stream 0 50 50)
    (event/process-pending! rt)
    (is (= :blocked (ds/next signals {:position 0}))
        "no diagnostic when no subscriber for a hit target")))

(deftest unregister-removes-subscriber
  (let [geom-stream (make-stream)
        tap-stream (make-stream)
        signals (make-stream)
        dispatched-a (atom [])
        dispatched-b (atom [])
        sub-a (fn [e] (swap! dispatched-a conj e))
        sub-b (fn [e] (swap! dispatched-b conj e))
        rt (event/create-runtime! {:geometry-stream geom-stream
                                    :tap-stream tap-stream
                                    :signal-stream signals})]
    (event/register! rt ::btn :tap sub-a)
    (event/register! rt ::btn :tap sub-b)
    (event/unregister! rt ::btn :tap sub-a)
    (put-geometry! geom-stream 0 [{:node-id ::btn :event :tap
                                    :regions [{:bounds [0 0 100 100] :paint-order 1}]}])
    (put-tap! tap-stream 0 50 50)
    (event/process-pending! rt)
    (is (empty? @dispatched-a) "unregistered subscriber not called")
    (is (= 1 (count @dispatched-b)) "remaining subscriber called")))

(deftest dispose-stops-processing
  (let [geom-stream (make-stream)
        tap-stream (make-stream)
        signals (make-stream)
        dispatched (atom [])
        rt (event/create-runtime! {:geometry-stream geom-stream
                                    :tap-stream tap-stream
                                    :signal-stream signals})]
    (event/register! rt ::btn :tap #(swap! dispatched conj %))
    (put-geometry! geom-stream 0 [{:node-id ::btn :event :tap
                                    :regions [{:bounds [0 0 100 100] :paint-order 1}]}])
    (event/process-pending! rt)
    (event/dispose! rt)
    (put-tap! tap-stream 0 50 50)
    (event/process-pending! rt)
    (is (empty? @dispatched) "dispatch should not occur after dispose")))


;; =============================================================================
;; Geometry updates
;; =============================================================================

(deftest new-geometry-replaces-hit-index
  (let [geom-stream (make-stream)
        tap-stream (make-stream)
        signals (make-stream)
        dispatched (atom [])
        rt (event/create-runtime! {:geometry-stream geom-stream
                                    :tap-stream tap-stream
                                    :signal-stream signals})]
    (event/register! rt ::btn-a :tap #(swap! dispatched conj :a))
    (event/register! rt ::btn-b :tap #(swap! dispatched conj :b))
    (put-geometry! geom-stream 0 [{:node-id ::btn-a :event :tap
                                    :regions [{:bounds [0 0 50 50] :paint-order 1}]}])
    (put-geometry! geom-stream 1 [{:node-id ::btn-b :event :tap
                                    :regions [{:bounds [0 0 50 50] :paint-order 1}]}])
    (put-tap! tap-stream 1 25 25)
    (event/process-pending! rt)
    (is (= [:b] @dispatched)
        "only btn-b should dispatch since btn-a is not in frame 1")))

(deftest empty-geometry-clears-hit-index
  (let [geom-stream (make-stream)
        tap-stream (make-stream)
        signals (make-stream)
        dispatched (atom [])
        rt (event/create-runtime! {:geometry-stream geom-stream
                                    :tap-stream tap-stream
                                    :signal-stream signals})]
    (event/register! rt ::btn :tap #(swap! dispatched conj %))
    (put-geometry! geom-stream 0 [{:node-id ::btn :event :tap
                                    :regions [{:bounds [0 0 50 50] :paint-order 1}]}])
    (put-geometry! geom-stream 1 [])
    (put-tap! tap-stream 1 25 25)
    (event/process-pending! rt)
    (is (empty? @dispatched) "empty geometry should result in no dispatch")))

(deftest unrecognized-event-kind-warning
  (let [geom-stream (make-stream)
        tap-stream (make-stream)
        signals (make-stream)
        rt (event/create-runtime! {:geometry-stream geom-stream
                                    :tap-stream tap-stream
                                    :signal-stream signals})]
    (event/register! rt ::btn :hover (fn [_]))
    (event/process-pending! rt)
    (let [diag (:ok (ds/next signals {:position 0}))]
      (is (= :dao.gui.event/unrecognized-event-kind (:diagnostic/kind diag)))
      (is (= :warning (:severity diag))))))
