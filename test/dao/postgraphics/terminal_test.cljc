(ns dao.postgraphics.terminal-test
  (:require
    [clojure.test :refer [deftest is]]
    [dao.postgraphics.terminal :as term]
    [dao.stream :as ds]
    [dao.stream.ringbuffer]))


(defn- make-stream
  ([] (ds/open! {:type :ringbuffer, :capacity nil}))
  ([capacity eviction-policy]
   (ds/open! {:type :ringbuffer,
              :capacity capacity,
              :eviction-policy eviction-policy})))


(defn- validating-presenter
  [accepted]
  {:validate-frame!
   (fn [frame]
     (when (= :reject frame)
       (throw (ex-info "rejected"
                       {:dao.postgraphics/reason :validation-failure})))
     (when (= :unsupported frame)
       (throw (ex-info "unsupported"
                       {:dao.postgraphics/reason :unsupported-op})))),
   :present-frame! (fn [frame] (swap! accepted conj frame))})


(deftest bind-stream-emits-reset-and-presents-accepted-frames
  (let [frames (make-stream)
        accepted (atom [])
        signals (make-stream)]
    (term/bind-stream! frames
                       (merge (validating-presenter accepted)
                              {:signal-stream signals, :generation-id "gen-a"}))
    (is (= :ok (term/put-frame! frames [:frame/a])))
    (is (= [[:frame/a]] @accepted))
    (let [signal (:ok (ds/next signals {:position 0}))]
      (is (= :dao.terminal/reset (:message/kind signal)))
      (is (= "gen-a" (:generation-id signal))))))


(deftest bind-stream-emits-canonical-rejection
  (let [frames (make-stream)
        accepted (atom [])
        signals (make-stream)
        errors (atom [])]
    (term/bind-stream! frames
                       (merge (validating-presenter accepted)
                              {:signal-stream signals,
                               :on-error #(swap! errors conj %)}))
    (term/put-frame! frames :reject)
    (is (empty? @accepted))
    (is (= 1 (count @errors)))
    (is (= {:message/kind :dao.terminal/rejection,
            :submission-id 0,
            :reason :validation-failure}
           (:ok (ds/next signals {:position 1}))))))


(deftest bind-stream-emits-frame-skipped-on-gap
  (let [frames (make-stream 1 :evict-oldest)
        accepted (atom [])
        signals (make-stream)]
    (ds/put! frames [:frame/evicted])
    (ds/put! frames [:frame/presented])
    (term/bind-stream! frames
                       (merge (validating-presenter accepted)
                              {:signal-stream signals,
                               :generation-id-fn (fn [] "gen-b")}))
    (is (= [[:frame/presented]] @accepted))
    (let [signal (:ok (ds/next signals {:position 0}))]
      (is (= :dao.terminal/reset (:message/kind signal)))
      (is (= "gen-b" (:generation-id signal))))
    (is (= {:message/kind :dao.terminal/frame-skipped, :submission-id 0}
           (:ok (ds/next signals {:position 1}))))
    (is (= :blocked (ds/next signals {:position 2})))))


(deftest closed-binding-stops-presenting-frames
  (let [frames (make-stream)
        accepted (atom [])
        handle (term/bind-stream! frames (validating-presenter accepted))]
    ((:close! handle))
    (is (= :ok (term/put-frame! frames [:frame/after-close])))
    (is (empty? @accepted))))


;; =============================================================================
;; Geometry & tap emission
;; =============================================================================

(deftest present-frame-returns-geometry-emits-on-geometry-stream
  (let [frames (make-stream)
        geometry (make-stream)
        signals (make-stream)
        presented-geom (atom nil)
        presenter {:validate-frame! identity
                   :present-frame!
                   (fn [frame]
                     {:frame-id 0
                      :nodes [{:node-id ::btn
                               :event :tap
                               :regions [{:bounds [10 20 100 50]
                                          :paint-order 1}]}]})}]
    (term/bind-stream! frames
                       (merge presenter
                              {:geometry-stream geometry
                               :signal-stream signals}))
    (term/put-frame! frames [:frame/a])
    (let [geom (:ok (ds/next geometry {:position 0}))]
      (is (= 0 (:frame-id geom)))
      (is (= 1 (count (:nodes geom))))
      (is (= ::btn (:node-id (first (:nodes geom))))))))

(deftest present-frame-returns-nil-emits-empty-geometry
  (let [frames (make-stream)
        geometry (make-stream)
        signals (make-stream)
        presenter {:validate-frame! identity
                   :present-frame! (fn [frame] nil)}]
    (term/bind-stream! frames
                       (merge presenter
                              {:geometry-stream geometry
                               :signal-stream signals}))
    (term/put-frame! frames [:frame/a])
    (let [geom (:ok (ds/next geometry {:position 0}))]
      (is (= 0 (:frame-id geom)))
      (is (= [] (:nodes geom))
          "empty :nodes when present-frame! returns nil"))))

(deftest accept-tap-emits-tap-on-tap-stream
  (let [frames (make-stream)
        taps (make-stream)
        signals (make-stream)
        presenter {:validate-frame! identity
                   :present-frame! (fn [frame] nil)}
        handle (term/bind-stream! frames
                                 (merge presenter
                                        {:tap-stream taps
                                         :signal-stream signals}))]
    (term/put-frame! frames [:frame/a])
    ((:accept-tap! handle) {:x 42 :y 17})
    (let [tap (:ok (ds/next taps {:position 0}))]
      (is (= 0 (:frame-id tap)))
      (is (= {:x 42 :y 17} (:position tap))))))


(deftest accept-input-tags-events-with-presented-frame-test
  (let [frames (make-stream)
        inputs (make-stream)
        presenter {:validate-frame! identity
                   :present-frame! (fn [_] nil)}
        handle (term/bind-stream! frames
                                  (assoc presenter :input-stream inputs))]
    (term/put-frame! frames [:frame/a])
    ((:accept-input! handle)
     {:input/kind :pointer-down
      :pointer/id 7
      :position {:x 20 :y 30}})
    (is (= {:frame-id 0
            :input/kind :pointer-down
            :pointer/id 7
            :position {:x 20 :y 30}}
           (:ok (ds/next inputs {:position 0}))))))


(deftest geometry-report-converts-interactive-events-test
  (is (= {:nodes
          [{:node-id :play
            :event :tap
            :regions [{:bounds [10 20 30 40]
                       :paint-order 0
                       :bytecode-index 0}]}
           {:node-id :seek
            :event :pointer-down
            :regions [{:bounds [0 0 100 12]
                       :paint-order 1
                       :bytecode-index 1}]}]}
         (term/geometry-from-report
           [{:screen-rect [10 20 30 40]
             :op/meta {:node-id :play :interactive-events #{:tap}}}
            {:screen-rect [0 0 100 12]
             :op/meta {:node-id :seek
                       :interactive-events #{:pointer-down}}}]))))


(deftest frame-id-increments-on-success-not-rejection
  (let [frames (make-stream)
        geometry (make-stream)
        signals (make-stream)
        accepted (atom [])
        presenter {:validate-frame! identity
                   :present-frame!
                   (fn [frame]
                     (when (= :reject frame)
                       (throw (ex-info "rejected"
                                       {:dao.postgraphics/reason :validation-failure})))
                     (swap! accepted conj frame)
                     {:nodes []})}]
    (term/bind-stream! frames
                       (merge presenter
                              {:geometry-stream geometry
                               :signal-stream signals}))
    ;; First frame: succeeds, frame-id should be 0
    (term/put-frame! frames [:frame/ok-1])
    (is (= 0 (:frame-id (:ok (ds/next geometry {:position 0}))))
        "first successful frame gets frame-id 0")
    ;; Second frame: rejected, frame-id should NOT advance
    (term/put-frame! frames :reject)
    ;; Third frame: succeeds, frame-id should be 1
    (term/put-frame! frames [:frame/ok-2])
    ;; Third frame succeeds, so geometry IS at position 1
    (let [geom2 (:ok (ds/next geometry {:position 1}))]
      (is geom2 "geometry emitted for third frame")
      (is (= 1 (:frame-id geom2))
          "third successful frame gets frame-id 1 (skipping rejected submission)"))
    (is (= :blocked (ds/next geometry {:position 2}))
        "no geometry beyond what was presented")))

(deftest geometry-emitted-before-tap
  (let [frames (make-stream)
        geometry (make-stream)
        taps (make-stream)
        signals (make-stream)
        presenter {:validate-frame! identity
                   :present-frame! (fn [frame] {:nodes []})}
        handle (term/bind-stream! frames
                                 (merge presenter
                                        {:geometry-stream geometry
                                         :tap-stream taps
                                         :signal-stream signals}))]
    (term/put-frame! frames [:frame/a])
    ;; Geometry should be at position 0, tap at position 0 after accept-tap!
    ((:accept-tap! handle) {:x 10 :y 10})
    (let [geom (:ok (ds/next geometry {:position 0}))]
      (is (= 0 (:frame-id geom))))
    (let [tap (:ok (ds/next taps {:position 0}))]
      (is (= 0 (:frame-id tap))
          "tap should be tagged with current presented-frame-id"))))
