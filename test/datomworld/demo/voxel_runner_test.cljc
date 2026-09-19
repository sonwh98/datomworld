(ns datomworld.demo.voxel-runner-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [datomworld.demo.voxel-controls :as controls]
            [datomworld.demo.voxel-input :as input]
            [datomworld.demo.voxel-runner :as runner]))


(use-fixtures :each
  (fn [run]
    (runner/stop-input!)
    (input/resize! 720.0 540.0)
    (run)
    (runner/stop-input!)
    (input/resize! 720.0 540.0)))


(def ^:private size {:width 720.0, :height 540.0})


(defn- center
  [action]
  (let [{:keys [x y width height]}
        (first (filter #(= action (:action %)) (controls/layout size)))]
    {:x (+ x (/ width 2.0)), :y (+ y (/ height 2.0))}))


(defn- finger
  [phase id action]
  {:phase phase,
   :id id,
   :type :touch,
   :buttons (if (= :up phase) 0 1),
   :position (center action)})


(defn- player-pos
  []
  (:pos @@#'runner/player*))


(deftest a-pressed-button-is-held-until-the-finger-lifts
  (runner/start-input!)
  (runner/pointer-input! (finger :down 1 :forward))
  (is (= #{:forward} (runner/pressed-controls)))
  (runner/pointer-input! (finger :up 1 :forward))
  (is (= #{} (runner/pressed-controls))))


(deftest a-cancelled-touch-releases-its-button
  (runner/start-input!)
  (runner/pointer-input! (finger :down 1 :left))
  (is (= #{:left} (runner/pressed-controls)))
  (runner/pointer-input! (finger :cancel 1 :left))
  (is (= #{} (runner/pressed-controls))))


(deftest fingers-hold-separate-buttons
  (runner/start-input!)
  (runner/pointer-input! (finger :down 1 :forward))
  (runner/pointer-input! (finger :down 2 :up))
  (is (= #{:forward :up} (runner/pressed-controls)))
  (runner/pointer-input! (finger :up 1 :forward))
  (is (= #{:up} (runner/pressed-controls))))


(deftest pressed-buttons-end-when-input-stops-or-the-surface-moves
  (runner/start-input!)
  (runner/pointer-input! (finger :down 1 :forward))
  (runner/resize-input! 375.0 300.0)
  (is (= #{} (runner/pressed-controls)))
  (runner/pointer-input! (assoc (finger :down 2 :back)
                                :position (center :back)))
  (runner/stop-input!)
  (is (= #{} (runner/pressed-controls))))


(deftest a-pressed-button-moves-the-player
  (runner/ensure-chunk-mesh!)
  (runner/reset-player!)
  (runner/start-input!)
  (let [start (player-pos)]
    (runner/tick! 0.25)
    (is (= start (player-pos)) "nothing pressed, nothing moves")
    (runner/pointer-input! (finger :down 1 :forward))
    (runner/tick! 0.25)
    (is (not= start (player-pos)) "the forward button moves the player")
    (let [moved (player-pos)]
      (runner/pointer-input! (finger :up 1 :forward))
      (runner/tick! 0.25)
      (is (= moved (player-pos)) "lifting the finger stops the player"))))


(deftest buttons-and-keys-move-together
  (runner/ensure-chunk-mesh!)
  (runner/reset-player!)
  (runner/start-input!)
  (let [start (player-pos)]
    (runner/key-input! {:phase :down, :code :space, :time-us 1})
    (runner/pointer-input! (finger :down 1 :forward))
    (runner/tick! 0.25)
    (let [[_ y z] (player-pos)
          [_ sy sz] start]
      (is (> y sy) "the key flew up")
      (is (not= z sz) "the button walked forward"))))
