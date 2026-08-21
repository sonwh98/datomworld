;; Pointer packet lifecycle: hit testing, capture, joining, uncaptured
;; pointers, duplicates, orphans, hover, and input-sequence gaps.
;; Specification: docs/design/dao.gui.event.md sections Normalized Pointer
;; Packets, Frame And Input Causality, Hit Testing And Capture,
;; Multi-Pointer Joining, Gesture Output, Subscriber Model.
(ns dao.gui.event.pointer-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.gui.event :as event]
            [dao.gui.event.util :as u]))


(def space-input
  (u/coordinate-space-change {:old-coordinate-space-id nil,
                              :coordinate-space-id u/space-id,
                              :width 390.0,
                              :height 844.0}))


(defn- step*
  [state input]
  (event/step state input))


(defn- boot-state
  "Space, geometry with one tap target at [24,120)x[16,48), profile. All at
  one timestamp: equal runtime time is legal."
  []
  (let [s1 (:state (step* (event/initial-state)
                          (u/rt 0 u/t0 :terminal space-input)))
        s2 (:state (step* s1 (u/rt 1 u/t0 :geometry (u/presented-geometry {}))))
        s3 (:state (step* s2 (u/rt 2 u/t0 :profile (u/input-profile {}))))]
    s3))


(defn- boot-with-subs
  [& subscription-commands]
  (reduce (fn [state [i command]]
            (:state (step* state (u/rt i u/t0 :subscription command))))
          (boot-state)
          (map-indexed vector subscription-commands)))


(defn- pointer-input
  [seq time-us packet]
  (u/rt seq time-us :pointer packet))


(defn- down
  ([id x y] (down 10 u/t0 id x y))
  ([seq time-us id x y]
   (pointer-input
     seq
     time-us
     (u/pointer-packet
       {:phase :down, :id id, :x x, :y y, :time-us time-us, :input-seq seq})))
  ([seq time-us id x y input-seq]
   (pointer-input seq
                  time-us
                  (u/pointer-packet {:phase :down,
                                     :id id,
                                     :x x,
                                     :y y,
                                     :time-us time-us,
                                     :input-seq input-seq}))))


(defn- move
  [seq time-us id x y]
  (pointer-input
    seq
    time-us
    (u/pointer-packet
      {:phase :move, :id id, :x x, :y y, :time-us time-us, :input-seq seq})))


(defn- up
  [seq time-us id x y]
  (pointer-input seq
                 time-us
                 (u/pointer-packet {:phase :up,
                                    :id id,
                                    :x x,
                                    :y y,
                                    :time-us time-us,
                                    :input-seq seq,
                                    :buttons 0})))


(defn- cancel
  [seq time-us id x y]
  (pointer-input seq
                 time-us
                 (u/pointer-packet {:phase :cancel,
                                    :id id,
                                    :x x,
                                    :y y,
                                    :time-us time-us,
                                    :input-seq seq,
                                    :buttons 0})))


(defn- kinds
  [outputs]
  (mapv (juxt :event/kind :dispatch/kind :effect/kind :diagnostic/kind)
        outputs))


(defn- first-of
  [outputs k]
  (first (filter k outputs)))


;; ---------------------------------------------------------------------------
;; Capture
;; ---------------------------------------------------------------------------

(deftest down-inside-region-captures-and-fans-raw-pointer
  (let [state (boot-with-subs
                (assoc (u/sub-add "raw" :dao.gui.event.util/save :pointer)
                       :raw? true))
        {:keys [state outputs]} (step* state (down 10 u/t0 11 40.0 20.0))
        pointer-event (first-of outputs :event/kind)
        dispatch (first-of outputs :dispatch/kind)]
    (is (= [11] (:active-pointer-ids (event/fixture-projection state))))
    (is (= [0] (:active-arena-ids (event/fixture-projection state))))
    (is (some? pointer-event))
    (is (= :pointer (:event/kind pointer-event)))
    (is (= 0 (:arena-id pointer-event)))
    (is (= u/frame-id (:origin-frame-id pointer-event)))
    (is (= :dao.gui.event.util/save (first (:target-path pointer-event))))
    (is (= "raw" (:subscription/id dispatch)))
    (is (= :dao.gui.event.util/save (:node-id dispatch)))))


(deftest down-without-matching-subscription-emits-no-dispatch
  (let [state (boot-state)
        {:keys [outputs]} (step* state (down 10 u/t0 11 40.0 20.0))]
    (is (nil? (first-of outputs :dispatch/kind)))))


(deftest down-outside-any-region-is-uncaptured
  (let [state (boot-state)
        {:keys [outputs]} (step* state (down 10 u/t0 11 400.0 700.0))
        projection (event/fixture-projection
                     (:state (step* state (down 10 u/t0 11 400.0 700.0))))]
    (is (= [] (kinds outputs)))
    ;; pointer record exists with no arena
    (is (= [11] (:active-pointer-ids projection)))
    (is (= [] (:active-arena-ids projection)))
    ;; later moves produce no output
    (let [moved (step* (:state (step* state (down 10 u/t0 11 400.0 700.0)))
                       (move 11 (+ u/t0 1) 11 401.0 701.0))]
      (is (= [] (:outputs moved))))
    ;; up removes the record
    (let [lifted (step* (:state (step* state (down 10 u/t0 11 400.0 700.0)))
                        (up 12 (+ u/t0 2) 11 401.0 701.0))]
      (is (= []
             (:active-pointer-ids (event/fixture-projection (:state
                                                              lifted))))))))


(deftest boundary-containment-is-half-open
  (testing "left and bottom edges are inside"
    (let [state (boot-state)
          {:keys [outputs]} (step* state (down 10 u/t0 11 24.0 16.0))]
      (is (some #(= :pointer (:event/kind %)) outputs))))
  (testing "right and top edges are outside"
    (let [state (boot-state)
          {:keys [outputs]} (step* state (down 10 u/t0 11 120.0 48.0))]
      (is (= [] (kinds outputs))))))


(deftest topmost-target-selection
  (let [back {:message/kind :dao.terminal/presented-geometry,
              :generation-id u/generation,
              :frame-id 43,
              :coordinate-space-id u/space-id,
              :nodes [{:node-id ::back,
                       :interaction/path [{:node-id ::back,
                                           :recognizers [(u/tap-decl ::back)],
                                           :touch-action :none}],
                       :touch-action :none,
                       :regions [{:bounds {:x 0, :y 0, :width 200, :height 200},
                                  :paint-order 50}]}]}
        front {:message/kind :dao.terminal/presented-geometry,
               :generation-id u/generation,
               :frame-id 44,
               :coordinate-space-id u/space-id,
               :nodes [{:node-id ::front,
                        :interaction/path [{:node-id ::front,
                                            :recognizers [(u/tap-decl ::front)],
                                            :touch-action :none}],
                        :touch-action :none,
                        :regions [{:bounds
                                   {:x 100, :y 100, :width 100, :height 100},
                                   :paint-order 60}]}]}
        base (boot-state)
        s1 (:state (step* base (u/rt 3 (+ u/t0 3) :geometry back)))
        s2 (:state (step* s1 (u/rt 4 (+ u/t0 4) :geometry front)))
        {:keys [outputs]} (step* s2
                                 (pointer-input 10
                                                (+ u/t0 10)
                                                (u/pointer-packet
                                                  {:phase :down,
                                                   :id 11,
                                                   :x 150.0,
                                                   :y 150.0,
                                                   :time-us (+ u/t0 10),
                                                   :input-seq 10,
                                                   :frame-id 44})))
        pointer-event (first-of outputs :event/kind)]
    (is (= ::front (first (:target-path pointer-event))))))


;; ---------------------------------------------------------------------------
;; Validation and causality
;; ---------------------------------------------------------------------------

(deftest down-without-active-geometry-diagnoses-no-active-frame
  (let [s1 (:state (step* (event/initial-state)
                          (u/rt 0 u/t0 :terminal space-input)))
        s2 (:state (step* s1 (u/rt 1 (+ u/t0 1) :profile (u/input-profile {}))))
        {:keys [outputs]} (step* s2 (down 10 (+ u/t0 10) 11 40.0 20.0))]
    (is (= [:dao.gui.event/no-active-frame] (keep :diagnostic/kind outputs)))))


(deftest future-and-stale-frames-diagnose
  (let [state (boot-state)]
    (testing "future frame"
      (let [{:keys [outputs]} (step* state
                                     (pointer-input 10
                                                    u/t0
                                                    (u/pointer-packet
                                                      {:phase :down,
                                                       :id 11,
                                                       :x 40,
                                                       :y 20,
                                                       :frame-id 99,
                                                       :input-seq 10})))]
        (is (= [:dao.gui.event/future-frame-input]
               (keep :diagnostic/kind outputs)))))
    (testing "stale frame"
      (let [{:keys [outputs]} (step* state
                                     (pointer-input 10
                                                    u/t0
                                                    (u/pointer-packet
                                                      {:phase :down,
                                                       :id 11,
                                                       :x 40,
                                                       :y 20,
                                                       :frame-id 9,
                                                       :input-seq 10})))]
        (is (= [:dao.gui.event/stale-frame-input]
               (keep :diagnostic/kind outputs)))))))


(deftest profile-and-space-and-generation-mismatch-diagnose
  (let [state (boot-state)]
    (testing "profile mismatch"
      (let [{:keys [outputs]} (step* state
                                     (pointer-input 10
                                                    u/t0
                                                    (u/pointer-packet
                                                      {:phase :down,
                                                       :id 11,
                                                       :x 40,
                                                       :y 20,
                                                       :profile-id 999,
                                                       :input-seq 10})))]
        (is (= [:dao.gui.event/profile-mismatch]
               (keep :diagnostic/kind outputs)))))
    (testing "coordinate space mismatch"
      (let [{:keys [outputs]} (step* state
                                     (pointer-input 10
                                                    u/t0
                                                    (u/pointer-packet
                                                      {:phase :down,
                                                       :id 11,
                                                       :x 40,
                                                       :y 20,
                                                       :coordinate-space-id 8,
                                                       :input-seq 10})))]
        (is (= [:dao.gui.event/coordinate-space-mismatch]
               (keep :diagnostic/kind outputs)))))
    (testing "generation mismatch"
      (let [{:keys [outputs]} (step* state
                                     (pointer-input 10
                                                    u/t0
                                                    (u/pointer-packet
                                                      {:phase :down,
                                                       :id 11,
                                                       :x 40,
                                                       :y 20,
                                                       :generation-id "other",
                                                       :input-seq 10})))]
        (is (= [:dao.gui.event/stale-generation-input]
               (keep :diagnostic/kind outputs)))))))


(deftest duplicate-down-cancels-and-removes-existing-pointer
  (let [state (boot-state)
        after-down (:state (step* state (down 10 u/t0 11 40.0 20.0)))
        {:keys [outputs]} (step* after-down (down 11 (+ u/t0 1) 11 60.0 30.0))]
    (is (= [:dao.gui.event/duplicate-pointer-down]
           (keep :diagnostic/kind outputs)))
    (is (= []
           (:active-pointer-ids
             (event/fixture-projection
               (:state (step* after-down
                              (down 11 (+ u/t0 1) 11 60.0 30.0)))))))))


(deftest move-and-up-for-unknown-id-diagnose-orphan
  (let [state (boot-state)]
    (testing "orphan move"
      (let [{:keys [outputs]} (step* state (move 10 u/t0 11 50.0 25.0))]
        (is (= [:dao.gui.event/orphan-pointer-packet]
               (keep :diagnostic/kind outputs)))))
    (testing "orphan up"
      (let [{:keys [outputs]} (step* state (up 10 u/t0 11 50.0 25.0))]
        (is (= [:dao.gui.event/orphan-pointer-packet]
               (keep :diagnostic/kind outputs)))))))


(deftest input-sequence-gap-diagnoses-and-cancels-arenas
  (let [state (boot-state)
        ;; down establishes input-seq 100 (default), then jump to 105
        after-down (:state (step* state (down 10 u/t0 11 40.0 20.0)))
        gap (pointer-input 11
                           (+ u/t0 1)
                           (u/pointer-packet {:phase :move,
                                              :id 11,
                                              :x 41.0,
                                              :y 21.0,
                                              :time-us (+ u/t0 1),
                                              :input-seq 105}))
        {:keys [outputs]} (step* after-down gap)]
    ;; the gap cancels the arena; the move for the now-unknown pointer is
    ;; then an orphan packet
    (is (= [:dao.gui.event/input-sequence-gap
            :dao.gui.event/orphan-pointer-packet]
           (keep :diagnostic/kind outputs)))
    ;; the arena created by the down is cancelled: no arenas remain
    (is (= []
           (:active-arena-ids (event/fixture-projection
                                (:state (step* after-down gap))))))))


;; ---------------------------------------------------------------------------
;; Hover
;; ---------------------------------------------------------------------------

(deftest hover-hit-emits-targeted-pointer-without-arena
  (let [state (boot-with-subs
                (assoc (u/sub-add "raw" :dao.gui.event.util/save :pointer)
                       :raw? true))
        hover (pointer-input 10
                             u/t0
                             (u/pointer-packet {:phase :hover,
                                                :id 4,
                                                :x 40.0,
                                                :y 20.0,
                                                :time-us u/t0,
                                                :input-seq 10}))
        {:keys [outputs]} (step* state hover)
        pointer-event (first-of outputs :event/kind)]
    (is (some? pointer-event))
    (is (nil? (:arena-id pointer-event)))
    (is (= []
           (:active-pointer-ids (event/fixture-projection
                                  (:state (step* state hover))))))
    (is (= []
           (:active-arena-ids (event/fixture-projection
                                (:state (step* state hover))))))
    (is (= "raw" (:subscription/id (first-of outputs :dispatch/kind))))))


(deftest hover-miss-is-silent
  (let [state (boot-state)
        hover (pointer-input 10
                             u/t0
                             (u/pointer-packet {:phase :hover,
                                                :id 4,
                                                :x 300.0,
                                                :y 300.0,
                                                :time-us u/t0,
                                                :input-seq 10}))]
    (is (= [] (:outputs (step* state hover))))))


(deftest hover-for-active-contact-is-protocol-error
  (let [state (boot-state)
        after-down (:state (step* state (down 10 u/t0 11 40.0 20.0)))
        hover (pointer-input 11
                             (+ u/t0 1)
                             (u/pointer-packet {:phase :hover,
                                                :id 11,
                                                :x 45.0,
                                                :y 25.0,
                                                :time-us (+ u/t0 1),
                                                :input-seq 11}))
        {:keys [outputs]} (step* after-down hover)]
    (is (= [:dao.gui.event/duplicate-pointer-down]
           (keep :diagnostic/kind outputs)))))


;; ---------------------------------------------------------------------------
;; Cancel
;; ---------------------------------------------------------------------------

(deftest cancel-packet-removes-pointer-and-arena
  (let [state (boot-state)
        after-down (:state (step* state (down 10 u/t0 11 40.0 20.0)))
        cancelled (step* after-down (cancel 11 (+ u/t0 1) 11 42.0 22.0))]
    (is (= []
           (:active-pointer-ids (event/fixture-projection (:state cancelled)))))
    (is (= []
           (:active-arena-ids (event/fixture-projection (:state cancelled)))))))
