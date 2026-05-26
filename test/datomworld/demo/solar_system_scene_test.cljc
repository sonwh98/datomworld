(ns datomworld.demo.solar-system-scene-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [datomworld.demo.solar-system-scene :as scene]))


(defn- approx=
  [a b]
  (< (abs (- (double a) (double b))) 1.0e-9))


(defn- mesh-ops
  [frame]
  (filterv #(= :draw3d/mesh (:op/kind %)) frame))


(defn- line-ops
  [frame]
  (filterv #(= :draw3d/lines (:op/kind %)) frame))


(deftest solar-system-frame-is-shared-gpu-data
  (testing "the shared scene emits only GPU-friendly wireframe ops"
    (let [frame (scene/frame-from-state scene/initial-state)
          lines (line-ops frame)]
      (is (= [:frame/clear :camera3d/set :state/depth-test :state/depth-write
              :draw3d/lines :draw3d/lines :draw3d/lines :draw3d/lines
              :draw3d/lines :draw3d/lines]
             (mapv :op/kind frame)))
      (is (= 6 (count lines)))
      (is (every? #(vector? (:vertices %)) lines))
      (is (every? #(vector? (:edges %)) lines))
      (is (every? :color lines))
      (is (empty? (mesh-ops frame))))))


(deftest solar-system-animation-step-is-deterministic
  (testing "advancing the shared scene updates orbital state directly in data"
    (let [next-state (scene/advance-state scene/initial-state
                                          scene/frame-step-seconds)
          mercury (get-in next-state [:bodies :mercury :translate])
          earth (get-in next-state [:bodies :earth :translate])
          sun-rot (get-in next-state [:bodies :sun :rotate])
          moon-pivot-rot (get-in next-state [:bodies :moon-pivot :rotate])
          t (:t next-state)]
      (is (approx= scene/frame-step-seconds t))
      (is (approx= (* 5.5 (scene/cos (* t 3.4))) (nth mercury 0)))
      (is (approx= (* 5.5 (scene/sin (* t 3.4))) (nth mercury 2)))
      (is (approx= (* 11.0 (scene/cos (* t 1.35))) (nth earth 0)))
      (is (approx= (* 11.0 (scene/sin (* t 1.35))) (nth earth 2)))
      (is (= [0 (* t 0.9) 0] sun-rot))
      (is (= [0 (* t 5.0) 0] moon-pivot-rot)))))
