;; Gesture arena resolution and the tap machine: single and repeated taps,
;; multi-contact taps, competition, deferral, and revival. Specification:
;; docs/design/dao.gui.event.md sections Gesture Arena, Tap And Repeated
;; Tap, Standard Gesture Payloads, Gesture Output, Conformance Scenarios.
(ns dao.gui.event.arena-test
  (:require [clojure.test :refer [deftest is]]
            [dao.gui.event :as event]
            [dao.gui.event.util :as u]))


(def node-id :dao.gui.event.util/save)


(def space-input
  (u/coordinate-space-change {:old-coordinate-space-id nil,
                              :coordinate-space-id u/space-id,
                              :width 390.0,
                              :height 844.0}))


(defn- boot
  [& {:keys [recognizers subscription]}]
  (let [geometry (u/presented-geometry
                   {:node-id node-id,
                    :recognizers (or recognizers [(u/tap-decl node-id)])})
        s0 (event/initial-state)
        s1 (:state (event/step s0 (u/rt 0 u/t0 :terminal space-input)))
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


(defn- down
  [seq time-us id x y]
  (ptr seq time-us :down id x y))


(defn- move
  [seq time-us id x y]
  (ptr seq time-us :move id x y))


(defn- up
  [seq time-us id x y]
  (ptr seq time-us :up id x y))


(defn- replay
  [state inputs]
  (reduce (fn [state input] (:state (event/step state input))) state inputs))


(defn- run
  "Step inputs from state, returning all outputs in order."
  [state inputs]
  (loop [state state
         inputs inputs
         outputs []]
    (if (empty? inputs)
      outputs
      (let [{next-state :state, out :outputs} (event/step state (first inputs))]
        (recur next-state (rest inputs) (into outputs out))))))


(defn- gestures
  [outputs]
  (filter #(= :gesture (:event/kind %)) outputs))


;; ---------------------------------------------------------------------------
;; Single tap
;; ---------------------------------------------------------------------------

(deftest single-tap-emits-recognized-with-closed-payload
  (let [state (boot :subscription (u/sub-add "t1" node-id :tap))
        outputs (run state
                     [(down 10 u/t0 11 40.0 20.0)
                      (up 11 (+ u/t0 50000) 11 41.0 21.0)])
        [gesture] (gestures outputs)
        dispatch (first (filter :dispatch/kind outputs))]
    (is (some? gesture))
    (is (= :tap (:gesture/kind gesture)))
    (is (= :recognized (:phase gesture)))
    (is (= node-id (:node-id gesture)))
    ;; gesture id is [arena-id recognizer-id]
    (is (= [0 [node-id :tap]] (:gesture/id gesture)))
    (is (= {:count 1, :contacts 1, :duration-us 50000} (:payload gesture)))
    (is (= 50000 (:duration-us (:payload gesture))))
    (is (= {:x 41.0, :y 21.0} (:position gesture)))
    (is (= #{11} (:pointer-ids gesture)))
    (is (= (+ u/t0 50000) (:time-us gesture)))
    (is (= "t1" (:subscription/id dispatch)))
    ;; arena and pointer are gone after the tap resolved
    (is (= {:active-pointer-ids [], :active-arena-ids []}
           (select-keys
             (event/fixture-projection
               (:state (event/step
                         (:state (event/step state (down 10 u/t0 11 40.0 20.0)))
                         (up 11 (+ u/t0 50000) 11 41.0 21.0))))
             [:active-pointer-ids :active-arena-ids])))))


(deftest tap-slop-breach-rejects-without-gesture
  (let [state (boot)
        outputs (run state
                     [(down 10 u/t0 11 40.0 20.0)
                      (move 11 (+ u/t0 10000) 11 80.0 60.0)
                      (up 12 (+ u/t0 20000) 11 80.0 60.0)])]
    (is (= [] (gestures outputs)))))


(deftest tap-exceeding-max-duration-rejects
  (let [state (boot)
        outputs (run state
                     [(down 10 u/t0 11 40.0 20.0)
                      (up 11 (+ u/t0 400000) 11 41.0 21.0)])]
    (is (= [] (gestures outputs)))))


;; ---------------------------------------------------------------------------
;; Multi-contact tap
;; ---------------------------------------------------------------------------

(deftest two-contact-tap-recognizes-with-contacts-two
  (let [decl {:recognizer/id [node-id :tap2],
              :gesture/kind :tap,
              :machine :dao.gui.event/tap,
              :config {:count 1,
                       :contacts {:min 2, :max 2},
                       :join-after-accept false,
                       :contact-loss :end},
              :arena {:priority 0, :mode :exclusive, :coexistence/group nil}}
        state (boot :recognizers [decl]
                    :subscription (u/sub-add "t2" node-id :tap))
        outputs (run state
                     [(down 10 u/t0 11 40.0 20.0)
                      (down 11 (+ u/t0 1000) 12 60.0 25.0)
                      (up 12 (+ u/t0 5000) 11 41.0 21.0)
                      (up 13 (+ u/t0 6000) 12 61.0 26.0)])
        [gesture] (gestures outputs)]
    (is (some? gesture))
    (is (= {:count 1, :contacts 2, :duration-us 6000} (:payload gesture)))
    ;; centroid of the two final up positions
    (is (= {:x 51.0, :y 23.5} (:position gesture)))
    (is (= #{11 12} (:pointer-ids gesture)))))


;; ---------------------------------------------------------------------------
;; Repeated tap and deferral
;; ---------------------------------------------------------------------------

(defn- double-tap-decl
  []
  {:recognizer/id [node-id :tapx2],
   :gesture/kind :tap,
   :machine :dao.gui.event/tap,
   :config {:count 2,
            :contacts {:min 1, :max 1},
            :join-after-accept false,
            :contact-loss :end},
   :arena {:priority 0, :mode :exclusive, :coexistence/group nil}})


(deftest double-tap-recognizes-on-second-tap
  (let [state (boot :recognizers [(double-tap-decl)]
                    :subscription (u/sub-add "d" node-id :tap))
        outputs (run state
                     [(down 10 u/t0 11 40.0 20.0)
                      (up 11 (+ u/t0 10000) 11 40.0 20.0)
                      (down 12 (+ u/t0 50000) 11 41.0 20.5)
                      (up 13 (+ u/t0 60000) 11 41.0 20.5)])
        [gesture] (gestures outputs)]
    (is (some? gesture))
    (is (= 2 (:count (:payload gesture))))
    (is (= 60000 (:duration-us (:payload gesture))))))


(deftest single-tap-defers-to-viable-double-tap-then-revives
  (let [state (boot :recognizers [(assoc-in (u/tap-decl node-id)
                                            [:recognizer/id]
                                            [node-id :one]) (double-tap-decl)]
                    :subscription (u/sub-add "s" node-id :tap))
        first-tap-outputs (run state
                               [(down 10 u/t0 11 40.0 20.0)
                                (up 11 (+ u/t0 10000) 11 40.0 20.0)])
        ;; the double-tap machine starts its :next-tap timer on the first
        ;; up
        timer-request (first (filter :effect/kind first-tap-outputs))]
    ;; no gesture is emitted speculatively while the double tap is viable
    (is (= [] (gestures first-tap-outputs)))
    (is (some? timer-request))
    (is (= :start (:timer/op timer-request)))
    (is (= :next-tap (:timer-id timer-request)))
    ;; the timer firing rejects the double tap and revives the single tap
    (let [state' (replay state
                         [(down 10 u/t0 11 40.0 20.0)
                          (up 11 (+ u/t0 10000) 11 40.0 20.0)])
          fired (u/rt 12 (+ u/t0 320000)
                      :timer (u/timer-fired
                               {:arena-id (:arena-id timer-request),
                                :recognizer/id (:recognizer/id timer-request),
                                :timer-id :next-tap,
                                :timer-seq (:timer-seq timer-request),
                                :time-us (+ u/t0 320000)}))
          outputs (run state' [fired])
          [gesture] (gestures outputs)
          dispatch (first (filter :dispatch/kind outputs))]
      (is (some? gesture))
      (is (= 1 (:count (:payload gesture))))
      ;; the stored gesture time is the original up time, not the revival
      (is (= (+ u/t0 10000) (:time-us gesture)))
      (is (= "s" (:subscription/id dispatch)))
      (is (= {:active-arena-ids [], :active-pointer-ids []}
             (select-keys (event/fixture-projection (replay state' [fired]))
                          [:active-arena-ids :active-pointer-ids]))))))


(deftest intermediate-taps-are-never-dispatched
  (let [state (boot :recognizers [(double-tap-decl)]
                    :subscription (u/sub-add "d" node-id :tap))
        outputs (run state
                     [(down 10 u/t0 11 40.0 20.0)
                      (up 11 (+ u/t0 10000) 11 40.0 20.0)
                      (down 12 (+ u/t0 50000) 11 41.0 20.5)
                      (up 13 (+ u/t0 60000) 11 41.0 20.5)])]
    ;; exactly one dispatch: the final recognized gesture
    (is (= 1 (count (filter :dispatch/kind outputs))))
    (is (= 1 (count (gestures outputs))))))


(deftest multi-tap-slop-is-enforced-during-moves
  ;; multi-tap/slop tightened below motion/slop: a second tap that drifts
  ;; past the first centroid band rejects on the move itself
  (let [decl {:recognizer/id [node-id :tapx2],
              :gesture/kind :tap,
              :machine :dao.gui.event/tap,
              :config {:count 2,
                       :contacts {:min 1, :max 1},
                       :multi-tap-slop 6.0,
                       :slop 18.0,
                       :join-after-accept false,
                       :contact-loss :end},
              :arena {:priority 0, :mode :exclusive, :coexistence/group nil}}
        state (boot :recognizers [decl])
        outputs-1 (run state
                       [(down 10 u/t0 11 40.0 20.0)
                        (up 11 (+ u/t0 10000) 11 40.0 20.0)
                        (down 12 (+ u/t0 50000) 11 44.0 20.0)])
        moved (run (replay state
                           [(down 10 u/t0 11 40.0 20.0)
                            (up 11 (+ u/t0 10000) 11 40.0 20.0)
                            (down 12 (+ u/t0 50000) 11 44.0 20.0)])
                   [(move 13 (+ u/t0 51000) 11 47.0 20.0)
                    (up 14 (+ u/t0 52000) 11 47.0 20.0)])]
    ;; first tap completed, second tap started inside the band
    (is (= [] (gestures outputs-1)))
    ;; the drift past multi-tap slop rejects; no double tap
    (is (= [] (gestures moved)))))
