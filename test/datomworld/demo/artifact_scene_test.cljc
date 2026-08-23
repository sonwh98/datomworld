(ns datomworld.demo.artifact-scene-test
  (:require [clojure.test :refer [deftest is]]
            [dao.postgraphics.math :as pgmath]
            [datomworld.demo.artifact-scene :as scene]))


(defn- op-kinds
  [frame]
  (mapv :op/kind frame))


(deftest frame-has-canonical-order-and-shape
  (let [frame (scene/build-frame scene/initial-state [800.0 600.0])
        camera (second frame)
        meshes (filter #(= :draw3d/mesh (:op/kind %)) frame)]
    (is (= [:frame/clear :camera3d/set :state/depth-test :state/depth-write
            :state/lighting-enable :light/ambient :light/point :draw3d/mesh
            :draw3d/mesh :draw3d/mesh]
           (op-kinds frame)))
    (is (= [0.0 0.0 10.0] (:camera3d/position camera)))
    (is (= [(- 0.0) 0.0 0.0] (:camera3d/rotation camera)))
    (is (= 3 (count meshes)))
    (is (= 24 (count (:vertices (first meshes)))))
    (is (every? #(= 4 (count (:fill %))) meshes))))


(deftest camera-and-state-boundaries-are-stable
  (let [quarter (scene/build-frame (assoc scene/initial-state
                                          :cam-rot-y (/ pgmath/PI 2))
                                   [1.0 1.0])
        clamped (scene/build-frame (assoc scene/initial-state
                                          :cam-rot-x 9.0
                                          :zoom 100.0)
                                   [1.0 1.0])
        q-camera (second quarter)
        c-camera (second clamped)]
    (is (< (pgmath/mabs (double (nth (:camera3d/position q-camera) 2))) 1.0e-9))
    (is (> (double (nth (:camera3d/position q-camera) 0)) 9.9))
    (is (> (nth (:camera3d/position c-camera) 1) 29.8))
    (is (< (nth (:camera3d/rotation c-camera) 0) -1.4))))


(deftest pulse-and-targets
  (let [frame (scene/build-frame (assoc scene/initial-state :pulse 2.0)
                                 [10.0 10.0])
        artifact (last frame)]
    (is (> (nth (:material/emissive artifact) 1) 0.79))
    (is (= 1.0 (nth (:material/emissive artifact) 2)))
    (is (not-any? #(#{:target/push :target/pop} (:op/kind %)) frame))))


(deftest artifact-glows-only-on-pulse
  (let [initial-artifact (last (scene/build-frame scene/initial-state
                                                  [10.0 10.0]))
        shifted-artifact (last (scene/build-frame (assoc scene/initial-state
                                                         :phase (/ pgmath/PI 2.0))
                                                  [10.0 10.0]))
        pulsed-artifact (last (scene/build-frame (assoc scene/initial-state
                                                        :pulse 1.0)
                                                 [10.0 10.0]))
        initial-emissive (:material/emissive initial-artifact)
        shifted-emissive (:material/emissive shifted-artifact)
        pulsed-emissive (:material/emissive pulsed-artifact)]
    (is (= initial-emissive shifted-emissive))
    (is (not= initial-emissive pulsed-emissive))
    (is (> (nth pulsed-emissive 2) 0.8))))
