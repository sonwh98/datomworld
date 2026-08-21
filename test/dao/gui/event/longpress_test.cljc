;; Timers and the long-press machine: timer request/fired effects, timer
;; sequence invalidation, pointer-before-timer ties, and the long-press
;; lifecycle. Specification: docs/design/dao.gui.event.md sections Timers,
;; Long Press, Standard Gesture Payloads.
(ns dao.gui.event.longpress-test
  (:require [clojure.test :refer [deftest is]]
            [dao.gui.event :as event]
            [dao.gui.event.util :as u]))


(def node-id :dao.gui.event.util/save)
(def delay-us 500000)


(def space-input
  (u/coordinate-space-change {:old-coordinate-space-id nil,
                              :coordinate-space-id u/space-id,
                              :width 390.0,
                              :height 844.0}))


(defn- long-press-decl
  []
  {:recognizer/id [node-id :hold],
   :gesture/kind :long-press,
   :machine :dao.gui.event/long-press,
   :config {:contacts {:min 1, :max 1}, :contact-loss :end},
   :arena {:priority 0, :mode :exclusive, :coexistence/group nil}})


(defn- boot
  [& {:keys [recognizers subscription]}]
  (let [geometry (u/presented-geometry {:node-id node-id,
                                        :recognizers (or recognizers
                                                         [(long-press-decl)])})
        s1 (:state (event/step (event/initial-state)
                               (u/rt 0 u/t0 :terminal space-input)))
        s2 (:state (event/step s1 (u/rt 1 u/t0 :geometry geometry)))
        s3 (:state (event/step s2 (u/rt 2 u/t0 :profile (u/input-profile {}))))]
    (if subscription
      (:state (event/step s3 (u/rt 3 u/t0 :subscription subscription)))
      s3)))


(defn- ptr
  [seq time-us phase id x y]
  (u/rt
    seq
    time-us
    :pointer
    (u/pointer-packet
      {:phase phase, :id id, :x x, :y y, :time-us time-us, :input-seq seq})))


(defn- run
  [state inputs]
  (loop [state state
         inputs inputs
         outputs []]
    (if (empty? inputs)
      {:state state, :outputs outputs}
      (let [{next-state :state, out :outputs} (event/step state (first inputs))]
        (recur next-state (rest inputs) (into outputs out))))))


(defn- gestures
  [outputs]
  (filter #(= :gesture (:event/kind %)) outputs))


(defn- phases
  [outputs]
  (mapv :phase (gestures outputs)))


(defn- timer-effects
  [outputs]
  (filter :effect/kind outputs))


(defn- fired-for
  [timer-request time-us & {:keys [timer-seq]}]
  (u/rt 90 time-us
        :timer (u/timer-fired {:arena-id (:arena-id timer-request),
                               :recognizer/id (:recognizer/id timer-request),
                               :timer-id (:timer-id timer-request),
                               :timer-seq (or timer-seq
                                              (:timer-seq timer-request)),
                               :time-us time-us})))


;; ---------------------------------------------------------------------------
;; Timer requests
;; ---------------------------------------------------------------------------

(deftest final-down-starts-long-press-timer
  (let [state (boot)
        {:keys [outputs]} (run state [(ptr 10 u/t0 :down 11 40.0 20.0)])
        request (first (timer-effects outputs))]
    (is (some? request))
    (is (= :start (:timer/op request)))
    (is (= :long-press (:timer-id request)))
    (is (= 1 (:timer-seq request)))
    (is (= (+ u/t0 delay-us) (:deadline-us request)))
    (is (= [node-id :hold] (:recognizer/id request)))))


;; ---------------------------------------------------------------------------
;; Acceptance and lifecycle
;; ---------------------------------------------------------------------------

(deftest long-press-timer-fires-starts-and-ends
  (let [state (boot :subscription (u/sub-add "lp" node-id :long-press))
        run-result
        (run
          state
          [(ptr 10 u/t0 :down 11 40.0 20.0)
           (fired-for
             (first (timer-effects
                      (:outputs
                        (event/step state (ptr 10 u/t0 :down 11 40.0 20.0)))))
             (+ u/t0 delay-us)) (ptr 11 (+ u/t0 510000) :move 11 50.0 25.0)
           (ptr 12 (+ u/t0 520000) :up 11 50.0 25.0)])
        outputs (:outputs run-result)
        events (gestures outputs)
        dispatches (filter :dispatch/kind outputs)]
    (is (= [:start :update :end] (phases outputs)))
    (let [[start update end] events]
      (is (= {:translation {:x 0.0, :y 0.0},
              :delta {:x 0.0, :y 0.0},
              :duration-us delay-us}
             (:payload start)))
      (is (= {:translation {:x 10.0, :y 5.0},
              :delta {:x 10.0, :y 5.0},
              :duration-us 510000}
             (:payload update)))
      (is (= {:translation {:x 10.0, :y 5.0},
              :delta {:x 0.0, :y 0.0},
              :duration-us 520000}
             (:payload end))))
    ;; every phase fans out to the subscriber
    (is (= [:start :update :end] (mapv (comp :phase :event) dispatches)))
    (is (= {:active-arena-ids [], :active-pointer-ids []}
           (select-keys (event/fixture-projection (:state run-result))
                        [:active-arena-ids :active-pointer-ids])))))


(deftest early-up-rejects-and-cancels-timer
  (let [booted (boot)
        down (event/step booted (ptr 10 u/t0 :down 11 40.0 20.0))
        request (first (timer-effects (:outputs down)))
        run-result (run (:state down)
                        [(ptr 11 (+ u/t0 100000) :up 11 40.0 20.0)])
        outputs (:outputs run-result)
        after-up (:state run-result)
        cancel-effect (first (filter #(= :cancel (:timer/op %))
                                     (:outputs (event/step (:state down)
                                                           (ptr 11 (+ u/t0 100000)
                                                                :up 11
                                                                40.0 20.0)))))]
    (is (= [] (gestures outputs)))
    (is (some? cancel-effect))
    (is (= (:timer-seq request) (:timer-seq cancel-effect)))
    ;; the late timer result is ignored with a warning
    (let [late (event/step after-up (fired-for request (+ u/t0 400000)))]
      (is (= [:dao.gui.event/late-timer]
             (mapv :diagnostic/kind (filter :diagnostic/kind (:outputs late)))))
      (is (= :warning
             (:severity (first (filter :diagnostic/kind (:outputs late)))))))))


(deftest movement-beyond-slop-before-acceptance-rejects
  (let [state (boot)
        {:keys [outputs]} (run state
                               [(ptr 10 u/t0 :down 11 40.0 20.0)
                                (ptr 11 (+ u/t0 100000) :move 11 80.0 60.0)])]
    (is (= [] (gestures outputs)))
    (is (= {:active-arena-ids [], :active-pointer-ids []}
           (select-keys (event/fixture-projection
                          (:state
                            (run state
                                 [(ptr 10 u/t0 :down 11 40.0 20.0)
                                  (ptr 11 (+ u/t0 100000) :move 11 80.0 60.0)
                                  (ptr 12 (+ u/t0 110000) :up 11 80.0 60.0)])))
                        [:active-arena-ids :active-pointer-ids])))))


(deftest pointer-cancel-after-acceptance-emits-cancel-phase
  (let [state (boot)
        down (event/step state (ptr 10 u/t0 :down 11 40.0 20.0))
        request (first (timer-effects (:outputs down)))
        started (run (:state down) [(fired-for request (+ u/t0 delay-us))])
        {:keys [outputs]} (run (:state started)
                               [(ptr 11 (+ u/t0 510000) :cancel 11 44.0 22.0)])
        [cancel-event] (gestures outputs)]
    (is (= [:cancel] (phases outputs)))
    (is (= :pointer-cancel (:reason (:payload cancel-event)))
        (str "payload: " (:payload cancel-event)))))


;; ---------------------------------------------------------------------------
;; Timer sequence rules
;; ---------------------------------------------------------------------------

(deftest stale-timer-sequence-is-late-timer
  (let [state (boot)
        down (event/step state (ptr 10 u/t0 :down 11 40.0 20.0))
        request (first (timer-effects (:outputs down)))
        stale (event/step (:state down)
                          (fired-for request (+ u/t0 delay-us) :timer-seq 99))]
    (is (= [:dao.gui.event/late-timer]
           (mapv :diagnostic/kind
                 (filter :diagnostic/kind (:outputs stale)))))))


(deftest pointer-before-timer-at-equal-timestamp
  (let [state (boot)
        down (event/step state (ptr 10 u/t0 :down 11 40.0 20.0))
        request (first (timer-effects (:outputs down)))
        ;; the up arrives before the timer result at the same timestamp:
        ;; canonical source order steps the pointer first, the long press
        ;; rejects, and the timer result is late
        {:keys [outputs]} (run (:state down)
                               [(ptr 11 (+ u/t0 delay-us) :up 11 40.0 20.0)
                                (fired-for request (+ u/t0 delay-us))])]
    (is (= [] (gestures outputs)))
    (is (some #(= :dao.gui.event/late-timer (:diagnostic/kind %)) outputs))))


(deftest long-press-with-range-starts-timer-at-minimum
  (let [decl {:recognizer/id [node-id :hold-range],
              :gesture/kind :long-press,
              :machine :dao.gui.event/long-press,
              :config {:contacts {:min 1, :max 3}, :contact-loss :end},
              :arena {:priority 0, :mode :exclusive, :coexistence/group nil}}
        state (boot :recognizers [decl])
        down (event/step state (ptr 10 u/t0 :down 11 40.0 20.0))]
    ;; one finger is already the full required set: the timer starts now
    (is (some #(and (:effect/kind %) (= :start (:timer/op %)))
              (:outputs down)))))
