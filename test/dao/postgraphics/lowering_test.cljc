(ns dao.postgraphics.lowering-test
  "JVM-only lowering tests (CLJS lowering tests live in web/gpu_test.cljs)."
  (:require
    [clojure.test :refer [deftest is]]
    [dao.postgraphics.lowering :as lower]))


(defn- approx=
  [a b]
  (< (Math/abs (- (double a) (double b))) 1.0e-6))


(defn- approx-vec=
  [a b]
  (and (= (count a) (count b)) (every? true? (map approx= a b))))


;; ---------------------------------------------------------------------------
;; validate-frame!
;; ---------------------------------------------------------------------------

(deftest accepts-valid-empty-frame (is (vector? (lower/validate-frame! []))))


(deftest rejects-non-vector-frame
  (is (thrown? #?(:clj Exception
                  :default :default)
        (lower/validate-frame! "not-a-frame"))))


(deftest rejects-op-without-kind
  (is (thrown? #?(:clj Exception
                  :default :default)
        (lower/validate-frame! [{}]))))


(deftest rejects-non-map-op
  (is (thrown? #?(:clj Exception
                  :default :default)
        (lower/validate-frame! [42]))))


(deftest accepts-clear-with-valid-color
  (is (vector? (lower/validate-frame! [{:op/kind :frame/clear,
                                        :color [0.1 0.2 0.3 0.4]}]))))


(deftest rejects-clear-with-bad-color
  (is (thrown? #?(:clj Exception
                  :default :default)
        (lower/validate-frame! [{:op/kind :frame/clear,
                                 :color [2 0 0 1]}]))))


(deftest accepts-transform-push-pop
  (is (vector? (lower/validate-frame! [{:op/kind :transform/push,
                                        :translate [1 2 3]}
                                       {:op/kind :transform/pop}]))))


(deftest rejects-transform-pop-without-push
  (is (thrown? #?(:clj Exception
                  :default :default)
        (lower/validate-frame! [{:op/kind :transform/pop}]))))


(deftest rejects-stray-transform
  (is (thrown? #?(:clj Exception
                  :default :default)
        (lower/validate-frame! [{:op/kind :transform/push,
                                 :translate [1 2 3]}]))))


(deftest accepts-clip-push-pop
  (is (vector? (lower/validate-frame! [{:op/kind :clip/push-rect,
                                        :rect [0 0 10 10]}
                                       {:op/kind :clip/pop}]))))


(deftest rejects-clip-pop-without-push
  (is (thrown? #?(:clj Exception
                  :default :default)
        (lower/validate-frame! [{:op/kind :clip/pop}]))))


(deftest accepts-camera-perspective
  (is (vector? (lower/validate-frame! [{:op/kind :camera3d/set,
                                        :camera3d/projection :perspective,
                                        :camera3d/fov 45.0,
                                        :camera3d/near 0.1,
                                        :camera3d/far 10.0}]))))


(deftest accepts-mesh-draw
  (is (vector? (lower/validate-frame! [{:op/kind :camera3d/set,
                                        :camera3d/projection :perspective,
                                        :camera3d/fov 45.0,
                                        :camera3d/near 0.1,
                                        :camera3d/far 10.0}
                                       {:op/kind :draw3d/mesh,
                                        :vertices [[0 0 0] [1 0 0] [0 1 0]],
                                        :indices [[0 1 2]],
                                        :fill [1 1 1 1]}]))))


;; ---------------------------------------------------------------------------
;; validate-frame! with options
;; ---------------------------------------------------------------------------

(deftest rejects-target-push-when-not-supported
  (is (thrown? #?(:clj Exception
                  :default :default)
        (lower/validate-frame! [{:op/kind :target/push,
                                 :target/id :shadow,
                                 :target/size [64 64]}]))))


(deftest accepts-target-push-when-supported
  (is (vector? (lower/validate-frame! [{:op/kind :target/push,
                                        :target/id :shadow,
                                        :target/size [64 64]}
                                       {:op/kind :target/pop}]
                                      {:supports-render-targets? true}))))


(deftest rejects-draw-image-when-not-supported
  (is (thrown? #?(:clj Exception
                  :default :default)
        (lower/validate-frame! [{:op/kind :draw/image,
                                 :image/source :anything,
                                 :rect [0 0 10 10]}]
                               {:supports-image? false}))))


(deftest accepts-draw-image-when-supported
  (is (vector? (lower/validate-frame! [{:op/kind :draw/image,
                                        :image/source :anything,
                                        :rect [0 0 10 10]}]
                                      {:supports-image? true}))))


(deftest texture-source-valid-hook-is-called
  (let [calls (atom [])]
    (is (vector? (lower/validate-frame!
                   [{:op/kind :camera3d/set,
                     :camera3d/projection :perspective,
                     :camera3d/fov 45.0,
                     :camera3d/near 0.1,
                     :camera3d/far 10.0}
                    {:op/kind :draw3d/mesh,
                     :vertices [[0 0 0] [1 0 0] [0 1 0]],
                     :indices [[0 1 2]],
                     :fill [1 1 1 1],
                     :uvs [[0 0] [1 0] [0 1]],
                     :texture/source :my-texture}]
                   {:texture-source-valid?
                    (fn [s] (swap! calls conj s) true)})))
    (is (= [:my-texture] @calls))))


(deftest texture-source-valid-rejects-invalid-source
  (is (thrown? #?(:clj Exception
                  :default :default)
        (lower/validate-frame! [{:op/kind :camera3d/set,
                                 :camera3d/projection :perspective,
                                 :camera3d/fov 45.0,
                                 :camera3d/near 0.1,
                                 :camera3d/far 10.0}
                                {:op/kind :draw3d/mesh,
                                 :vertices [[0 0 0] [1 0 0] [0 1 0]],
                                 :indices [[0 1 2]],
                                 :fill [1 1 1 1],
                                 :uvs [[0 0] [1 0] [0 1]],
                                 :texture/source :bad-texture}]
                               {:texture-source-valid? (constantly
                                                         false)}))))


;; ---------------------------------------------------------------------------
;; lower-frame shape
;; ---------------------------------------------------------------------------

(deftest lower-frame-produces-canonical-shape
  (let [result (lower/lower-frame [{:op/kind :frame/clear, :color [0 0 0 1]}
                                   {:op/kind :camera3d/set,
                                    :camera3d/projection :perspective,
                                    :camera3d/fov 45.0,
                                    :camera3d/near 0.1,
                                    :camera3d/far 10.0}
                                   {:op/kind :draw3d/mesh,
                                    :vertices [[0 0 0] [1 0 0] [0 1 0]],
                                    :indices [[0 1 2]],
                                    :fill [1 1 1 1]}]
                                  {:viewport-width 100, :viewport-height 50})]
    (is (map? result))
    (is (contains? result :viewport))
    (is (contains? result :passes))
    (is (contains? result :target-registry))
    (is (vector? (:passes result)))
    (is (pos? (count (:passes result))))
    (is (= :default (:target-id (first (:passes result)))))))


(deftest lower-frame-clears-and-camera-reset
  (let [result (lower/lower-frame [{:op/kind :camera3d/set,
                                    :camera3d/projection :perspective,
                                    :camera3d/fov 45.0,
                                    :camera3d/near 0.1,
                                    :camera3d/far 10.0}]
                                  {:viewport-width 100, :viewport-height 50})
        draws (get-in result [:passes 0 :draws])]
    (is (= :camera-reset (:pipeline (first draws))))))


(deftest lower-frame-3d-draw-has-mvp-and-model-m
  (let [result (lower/lower-frame [{:op/kind :camera3d/set,
                                    :camera3d/projection :perspective,
                                    :camera3d/fov 45.0,
                                    :camera3d/near 0.1,
                                    :camera3d/far 10.0}
                                   {:op/kind :draw3d/mesh,
                                    :vertices [[0 0 0] [1 0 0] [0 1 0]],
                                    :indices [[0 1 2]],
                                    :fill [1 1 1 1]}]
                                  {:viewport-width 100, :viewport-height 50})
        draws (get-in result [:passes 0 :draws])
        mesh-draw (second draws)]
    (is (= :mesh-3d (:pipeline mesh-draw)))
    (is (vector? (:mvp mesh-draw)))
    (is (= 16 (count (:mvp mesh-draw))))
    (is (vector? (:model-m mesh-draw)))
    (is (= 16 (count (:model-m mesh-draw))))))


(deftest lower-frame-with-render-targets-produces-multiple-passes
  (let [result (lower/lower-frame [{:op/kind :target/push,
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
    (is (= [:default :shadow] (mapv :target-id (:passes result))))
    (is (contains? (:target-registry result) :shadow))
    (is (= 64 (get-in result [:target-registry :shadow :size 0])))
    (is (= :clear (get-in result [:passes 1 :draws 0 :pipeline])))))


(deftest lower-frame-2d-rect
  (let [result (lower/lower-frame [{:op/kind :draw/fill-rect,
                                    :rect [10 20 30 40],
                                    :color [1 0 0 1]}]
                                  {:viewport-width 200, :viewport-height 100})
        draw (first (get-in result [:passes 0 :draws]))]
    (is (= :draw-2d (:pipeline draw)))
    (is (vector? (:model-m draw)))
    (is (= 16 (count (:model-m draw))))))


(deftest lower-frame-clip-rect
  (let [result
        (lower/lower-frame
          [{:op/kind :transform/push, :translate [5 10]}
           {:op/kind :clip/push-rect, :rect [10 20 30 40]}
           {:op/kind :draw/fill-rect, :rect [0 0 10 10], :color [1 1 1 1]}
           {:op/kind :clip/pop} {:op/kind :transform/pop}]
          {:viewport-width 200, :viewport-height 100})
        draw (first (get-in result [:passes 0 :draws]))]
    (is (approx-vec= [15.0 30.0 30.0 40.0] (first (:clips draw))))))


(deftest lower-frame-text
  (let [result (lower/lower-frame [{:op/kind :draw/text,
                                    :text "hello",
                                    :position [5 6],
                                    :font-size 12,
                                    :align :start,
                                    :color [0 0 0 1]}]
                                  {:viewport-width 200, :viewport-height 100})
        draw (first (get-in result [:passes 0 :draws]))]
    (is (= :text (:pipeline draw)))
    (is (approx= 5.0 (:screen-x draw)))
    (is (approx= 94.0 (:screen-y draw)))))
