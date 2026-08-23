(ns datomworld.demo.artifact-runner-test
  (:require [clojure.test :refer [deftest is]]
            [datomworld.demo.artifact-runner :as runner]
            [datomworld.demo.artifact-scene :as scene]))


(deftest gestures-update-immutable-state
  (let [pan (runner/reduce-gesture scene/initial-state
                                   {:event/kind :gesture,
                                    :gesture/kind :pan,
                                    :payload {:delta {:x 10.0, :y -4.0}}})
        zoom (runner/reduce-gesture scene/initial-state
                                    {:event/kind :gesture,
                                     :gesture/kind :scale,
                                     :payload {:scale-delta 2.0}})
        keyboard-zoom-in (runner/reduce-keyboard scene/initial-state
                                                 {:event/kind :keyboard,
                                                  :event/phase :down,
                                                  :key {:logical "+"}})
        keyboard-zoom-out (runner/reduce-keyboard scene/initial-state
                                                  {:event/kind :keyboard,
                                                   :event/phase :down,
                                                   :key {:logical "-"}})
        keyboard-w (runner/reduce-keyboard scene/initial-state
                                           {:event/kind :keyboard,
                                            :event/phase :down,
                                            :key {:code :key-w}})
        keyboard-a (runner/reduce-keyboard scene/initial-state
                                           {:event/kind :keyboard,
                                            :event/phase :down,
                                            :key {:code :key-a}})
        tap (runner/reduce-gesture scene/initial-state
                                   {:event/kind :gesture, :gesture/kind :tap})]
    (is (= 0.05 (:cam-rot-y pan)))
    (is (= -0.02 (:cam-rot-x pan)))
    (is (= 20.0 (:zoom zoom)))
    (is (= 9.0 (:zoom keyboard-zoom-in)))
    (is (= 11.0 (:zoom keyboard-zoom-out)))
    (is (= (- runner/keyboard-rotation-step) (:cam-rot-x keyboard-w)))
    (is (= (- runner/keyboard-rotation-step) (:cam-rot-y keyboard-a)))
    (is (= 1.0 (:pulse tap)))
    (is (= scene/initial-state
           (runner/reduce-gesture scene/initial-state
                                  {:event/kind :gesture,
                                   :gesture/kind :fling})))))


(deftest profile-and-runtime-envelope
  (is (= {:message/kind :dao.terminal/input-profile,
          :generation-id 2,
          :profile-id 3,
          :capabilities #{:touch},
          :thresholds runner/profile-thresholds}
         (runner/input-profile
           {:generation-id 2, :profile-id 3, :capabilities [:touch]})))
  (is (= {:runtime/seq 4,
          :runtime/time-us 99,
          :runtime/source :pointer,
          :runtime/value {:input/kind :pointer}}
         (runner/pointer-runtime-input {:runtime-seq 4,
                                        :runtime-time-us 99,
                                        :packet {:input/kind :pointer}}))))


(deftest keyboard-runtime-envelope
  (is (= {:runtime/seq 5,
          :runtime/time-us 101,
          :runtime/source :keyboard,
          :runtime/value {:input/kind :keyboard}}
         (runner/keyboard-runtime-input {:runtime-seq 5,
                                         :runtime-time-us 101,
                                         :packet {:input/kind :keyboard}}))))


(deftest browser-key-codes-are-canonical-keywords
  (is (= :equal (runner/keyboard-code "Equal")))
  (is (= :minus (runner/keyboard-code "Minus")))
  (is (= :numpad-add (runner/keyboard-code "NumpadAdd")))
  (is (= :key-a (runner/keyboard-code "KeyA"))))


(deftest boot-values-install-geometry-profile-and-subscriptions
  (let [values (runner/boot-values {:generation-id 1,
                                    :frame-id 2,
                                    :coordinate-space-id 3,
                                    :profile-id 4,
                                    :width 800.0,
                                    :height 600.0})]
    (is (= [:terminal :geometry :profile :subscription :subscription
            :subscription]
           (mapv :runtime/source values)))
    (is (= :dao.terminal/presented-geometry
           (get-in (second values) [:runtime/value :message/kind])))
    (is (= #{:pan :scale :tap}
           (set (map #(get-in % [:runtime/value :event-kind])
                     (drop 3 values)))))))


(deftest tick-decays-pulse
  (let [next-state (runner/tick-state (assoc scene/initial-state :pulse 0.7))]
    (is (= (* 0.7 scene/pulse-decay-factor) (:pulse next-state)))
    (is (= scene/phase-step (:phase next-state)))
    (is (= 0.0 (:cam-rot-x next-state)))
    (is (= 0.0 (:cam-rot-y next-state)))))


(deftest pointer-capture-keeps-drag-alive-outside-canvas
  (is (= {"pointerdown" :down,
          "pointermove" :move,
          "pointerup" :up,
          "pointercancel" :cancel,
          "lostpointercapture" :cancel}
         runner/pointer-event-phases))
  (is (not (contains? runner/pointer-event-phases "pointerout")))
  (is (not (contains? runner/pointer-event-phases "pointerleave"))))


(deftest drag-gesture-kinds-are-recognized
  (is (runner/drag-gesture? {:gesture/kind :pan}))
  (is (runner/drag-gesture? {:gesture/kind :drag}))
  (is (not (runner/drag-gesture? {:gesture/kind :tap}))))
