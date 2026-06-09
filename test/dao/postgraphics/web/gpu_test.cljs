(ns dao.postgraphics.web.gpu-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [dao.postgraphics.lowering :as lower]
    [dao.postgraphics.terminal :as terminal]
    [dao.postgraphics.web.gpu :as webgpu]))


(defn- approx=
  [a b]
  (< (js/Math.abs (- (double a) (double b))) 1.0e-6))


(defn- approx-vec=
  [a b]
  (and (= (count a) (count b)) (every? true? (map approx= a b))))


(defn- lower-opts
  ([] (lower-opts 200 100))
  ([w h]
   {:viewport-width w,
    :viewport-height h,
    :supports-render-targets? true,
    :supports-image? true}))


(deftest rects-are-lowered-into-browser-screen-space
  (let [lowered (lower/lower-frame [{:op/kind :draw/fill-rect,
                                     :rect [10 20 30 40],
                                     :color [1 0 0 1]}]
                                   (lower-opts))
        draw (first (get-in lowered [:passes 0 :draws]))]
    (is (= :draw-2d (:pipeline draw)))
    (is (vector? (:model-m draw)))
    (is (= 16 (count (:model-m draw))))))


(deftest clips-resolve-through-translate-only-ancestry
  (let [lowered
        (lower/lower-frame
          [{:op/kind :transform/push, :translate [5 10]}
           {:op/kind :clip/push-rect, :rect [10 20 30 40]}
           {:op/kind :draw/fill-rect, :rect [0 0 10 10], :color [1 1 1 1]}
           {:op/kind :clip/pop} {:op/kind :transform/pop}]
          (lower-opts))
        draw (first (get-in lowered [:passes 0 :draws]))]
    (is (= [[15 30 30 40]] (:clips draw)))))


(deftest text-anchor-is-transformed-but-glyphs-remain-screen-space
  (let [lowered (lower/lower-frame
                  [{:op/kind :transform/push, :translate [3 4], :scale [2 3]}
                   {:op/kind :draw/text,
                    :text "dao",
                    :position [5 6],
                    :font-size 12,
                    :align :start,
                    :color [0 0 0 1]} {:op/kind :transform/pop}]
                  (lower-opts))
        draw (first (get-in lowered [:passes 0 :draws]))]
    (is (= :text (:pipeline draw)))
    (is (approx= 13 (:screen-x draw)))
    (is (approx= 78 (:screen-y draw)))
    (is (= false (:glyphs-transformed? draw)))))


(deftest three-d-lowering-resolves-camera-and-model-like-flutter
  (let [frame [{:op/kind :camera3d/set,
                :camera3d/projection :perspective,
                :camera3d/fov 45.0,
                :camera3d/near 0.1,
                :camera3d/far 10.0,
                :camera3d/position [0.0 0.0 5.0],
                :camera3d/rotation [0.0 0.0 0.0]}
               {:op/kind :state/depth-test, :enabled true}
               {:op/kind :state/depth-write, :enabled true}
               {:op/kind :state/lighting-enable, :enabled true}
               {:op/kind :light/ambient, :color [0.1 0.2 0.3]}
               {:op/kind :transform/push, :translate [1.0 2.0 3.0]}
               {:op/kind :draw3d/mesh,
                :vertices [[0.0 0.0 0.0] [1.0 0.0 0.0] [0.0 1.0 0.0]],
                :indices [[0 1 2]],
                :normals [[0.0 0.0 1.0] [0.0 0.0 1.0] [0.0 0.0 1.0]],
                :fill [1.0 1.0 1.0 1.0]} {:op/kind :transform/pop}]
        lowered (lower/lower-frame frame
                                   {:viewport-width 100, :viewport-height 50})
        draw (second (get-in lowered [:passes 0 :draws]))]
    (is (= :camera-reset (get-in lowered [:passes 0 :draws 0 :pipeline])))
    (is (= :mesh-3d (:pipeline draw)))
    (is (= true (:depth-test draw)))
    (is (= true (:depth-write draw)))
    (is (= {:enabled true,
            :lights [{:kind :ambient, :color [0.1 0.2 0.3]}],
            :camera-pos [0.0 0.0 5.0]}
           (:lighting-state draw)))
    (is (approx-vec= [1.0 0.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 1.0 0.0 1.0 2.0
                      3.0 1.0]
                     (:model-m draw)))
    (is (vector? (:mvp draw)))
    (is (= 16 (count (:mvp draw))))))


(deftest custom-view-matrix-drives-camera-position
  (let [view [1.0 0.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 1.0 0.0 -3.0 2.0 -5.0 1.0]
        lowered (lower/lower-frame
                  [{:op/kind :camera3d/set,
                    :camera3d/projection :perspective,
                    :camera3d/fov 45.0,
                    :camera3d/near 0.1,
                    :camera3d/far 10.0,
                    :camera3d/view-matrix view}
                   {:op/kind :state/lighting-enable, :enabled true}
                   {:op/kind :draw3d/mesh,
                    :vertices [[0.0 0.0 0.0] [1.0 0.0 0.0] [0.0 1.0 0.0]],
                    :indices [[0 1 2]],
                    :normals [[0.0 0.0 1.0] [0.0 0.0 1.0] [0.0 0.0 1.0]],
                    :fill [1.0 1.0 1.0 1.0]}]
                  {:viewport-width 100, :viewport-height 50})
        draw (second (get-in lowered [:passes 0 :draws]))]
    (is (= [3.0 -2.0 5.0] (get-in draw [:lighting-state :camera-pos])))))


(deftest render-targets-produce-addressable-passes
  (let [lowered (lower/lower-frame [{:op/kind :target/push,
                                     :target/id :shadow,
                                     :target/size [64 64],
                                     :target/format :rgba8unorm}
                                    {:op/kind :frame/clear, :color [0 0 0 1]}
                                    {:op/kind :target/pop}
                                    {:op/kind :draw/image,
                                     :image/source [:target/id :shadow],
                                     :rect [0 0 16 16]}]
                                   {:viewport-width 128,
                                    :viewport-height 128,
                                    :supports-render-targets? true,
                                    :supports-image? true,
                                    :resolve-resource
                                    (fn [source _state]
                                      (when (= source [:target/id :shadow])
                                        {:resource/kind :target-texture,
                                         :target/id :shadow}))})]
    (is (= [:default :shadow] (mapv :target-id (:passes lowered))))
    (is (= :clear (get-in lowered [:passes 1 :draws 0 :pipeline])))
    (is (= :draw-2d (get-in lowered [:passes 0 :draws 0 :pipeline])))))


(deftest invalid-target-sampling-is-rejected-canonically
  (testing "missing produced target"
    (is (= :validation-failure
           (try (lower/validate-frame! [{:op/kind :draw/image,
                                         :image/source [:target/id :missing],
                                         :rect [0 0 10 10]}]
                                       {:supports-image? true})
                nil
                (catch js/Error e (terminal/rejection-reason e))))))
  (testing "self-sampling active target"
    (is (= :validation-failure
           (try (lower/validate-frame!
                  [{:op/kind :target/push,
                    :target/id :shadow,
                    :target/size [64 64],
                    :target/format :rgba8unorm}
                   {:op/kind :draw/image,
                    :image/source [:target/id :shadow],
                    :rect [0 0 10 10]} {:op/kind :target/pop}]
                  {:supports-render-targets? true, :supports-image? true})
                nil
                (catch js/Error e (terminal/rejection-reason e)))))))


(deftest unresolved-image-resources-are-unloadable
  (is (= :unloadable-image
         (try (lower/lower-frame [{:op/kind :draw/image,
                                   :image/source :asset/not-ready,
                                   :rect [0 0 10 10]}]
                                 {:viewport-width 100,
                                  :viewport-height 100,
                                  :supports-image? true})
              nil
              (catch js/Error e (terminal/rejection-reason e))))))


(deftest texture-upload-mode-prefers-automatic-rasterization-for-image-sources
  (is (= :white (webgpu/texture-upload-mode nil)))
  (is (= :white (webgpu/texture-upload-mode {:image :x})))
  (is (= :rgba
         (webgpu/texture-upload-mode
           {:rgba (js/Uint8Array. #js [1 2 3 4]), :width 1, :height 1})))
  (is (= :image (webgpu/texture-upload-mode {:image {}, :width 8, :height 4}))))


(deftest interleave-packs-shared-vertex-attrs
  (testing
    "GPU vertex packing delegates to packing/pack-vertex-floats! (colors > fill)"
    (let [data (webgpu/interleave-vertex-data {:vertices [[1 2 3]],
                                               :uvs [[0.5 0.25]],
                                               :normals [[0 0 1]],
                                               :fill [0.1 0.1 0.1 1],
                                               :colors [[0.9 0.8 0.7 0.6]]})]
      (is (= 12 (.-length data))
          "one vertex => 12 floats (pos uv normal color)")
      ;; position
      (is
        (and (= 1.0 (aget data 0)) (= 2.0 (aget data 1)) (= 3.0 (aget data 2))))
      ;; uv
      (is (and (approx= 0.5 (aget data 3)) (approx= 0.25 (aget data 4))))
      ;; normal
      (is
        (and (= 0.0 (aget data 5)) (= 0.0 (aget data 6)) (= 1.0 (aget data 7))))
      ;; colour resolved by vertex-attrs: per-vertex :colors win over :fill
      (is (approx= 0.9 (aget data 8)))
      (is (approx= 0.8 (aget data 9)))
      (is (approx= 0.7 (aget data 10)))
      (is (approx= 0.6 (aget data 11)))))
  (testing
    "missing normals default to the vertex position (matches web+flutter)"
    (let [data (webgpu/interleave-vertex-data {:vertices [[4 5 6]]})]
      ;; default normal = vertex position
      (is
        (and (= 4.0 (aget data 5)) (= 5.0 (aget data 6)) (= 6.0 (aget data 7))))
      ;; default colour = white
      (is (and (= 1.0 (aget data 8)) (= 1.0 (aget data 11)))))))
