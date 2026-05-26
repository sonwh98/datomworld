(ns datomworld.demo.earth-moon-scene-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [datomworld.demo.earth-moon-scene :as scene]))


(defn- draw-meshes
  [frame]
  (filterv #(= :draw3d/mesh (:op/kind %)) frame))


(deftest earth-moon-frame-is-common-postgraphics-data
  (testing "the demo emits one renderer-independent frame contract"
    (let [frame (scene/frame-from-seconds 1.25)
          meshes (draw-meshes frame)
          spheres (filterv #(= 2556 (count (:vertices %))) meshes)
          rings (filterv #(not= 2556 (count (:vertices %))) meshes)]
      (is (= [:frame/clear :camera3d/set :state/depth-test :state/depth-write
              :state/lighting-enable :light/ambient :light/directional
              :draw3d/mesh :draw3d/mesh :draw3d/mesh]
             (mapv :op/kind frame)))
      (is (= 3 (count meshes)))
      (is (= 2 (count spheres)))
      (is (every? #(= 4900 (count (:indices %))) spheres))
      (is (= 1 (count rings)))
      (is (every? #(not (contains? % :texture/source)) meshes)))))


(deftest earth-moon-depth-order-is-data-driven
  (testing "the producer orders spheres from orbital state, rings last"
    (let [front-meshes (draw-meshes (scene/frame-from-seconds 1.0))
          behind-meshes (draw-meshes (scene/frame-from-seconds 5.0))
          ring-fill [0.85 0.78 0.62 1.0]]
      (is (= [[0.34 0.68 1.0 1.0] [0.72 0.72 0.68 1.0] ring-fill]
             (mapv :fill front-meshes)))
      (is (= [[0.72 0.72 0.68 1.0] [0.34 0.68 1.0 1.0] ring-fill]
             (mapv :fill behind-meshes))))))


(deftest earth-moon-textures-attach-when-provided
  (testing "textures map attaches :texture/source per mesh; absent without map"
    (let [earth-fill [0.34 0.68 1.0 1.0]
          moon-fill [0.72 0.72 0.68 1.0]
          earth-tex ::earth-stub
          moon-tex ::moon-stub
          ring-tex ::ring-stub
          meshes (draw-meshes (scene/frame-from-seconds 1.0
                                                        {:earth-tex earth-tex,
                                                         :moon-tex moon-tex,
                                                         :ring-tex ring-tex}))
          earth (some #(when (= earth-fill (:fill %)) %) meshes)
          moon (some #(when (= moon-fill (:fill %)) %) meshes)
          ring (some #(when (= ring-tex (:texture/source %)) %) meshes)]
      (is (= earth-tex (:texture/source earth)))
      (is (= moon-tex (:texture/source moon)))
      (is (some? ring))
      (is (= [1.0 1.0 1.0 1.0] (:fill ring)))
      (is (every? #(not (contains? % :texture/source))
                  (draw-meshes (scene/frame-from-seconds 1.0)))))))
