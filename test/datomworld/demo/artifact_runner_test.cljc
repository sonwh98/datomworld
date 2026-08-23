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
        tap (runner/reduce-gesture scene/initial-state
                                   {:event/kind :gesture, :gesture/kind :tap})]
    (is (= 0.05 (:cam-rot-y pan)))
    (is (= -0.02 (:cam-rot-x pan)))
    (is (= 20.0 (:zoom zoom)))
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
    (is (= scene/phase-step (:phase next-state)))))
