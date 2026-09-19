(ns datomworld.demo.voxel-scene-test
  (:require [clojure.test :refer [deftest is]]
            [datomworld.demo.voxel-scene :as scene]))


(def ^:private player {:pos [1.0 2.0 3.0], :yaw 0.0, :pitch 0.0})


(deftest look-turns-with-the-drag
  (let [looked (scene/look player {:x 10.0, :y -20.0})]
    (is (= [1.0 2.0 3.0] (:pos looked)))
    (is (= (* scene/look-sensitivity 10.0) (:yaw looked)))
    (is (= (* scene/look-sensitivity 20.0) (:pitch looked)))))


(deftest look-clamps-pitch-short-of-vertical
  (is (= scene/max-pitch
         (:pitch (scene/look player {:x 0.0, :y -1.0e6}))))
  (is (= (- scene/max-pitch)
         (:pitch (scene/look player {:x 0.0, :y 1.0e6})))))
