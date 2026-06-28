(ns dao.postgraphics.packing-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.postgraphics.math :as m]
    [dao.postgraphics.packing :as pack]))


(defn- approx=
  [a b]
  (< (m/mabs (- (double a) (double b))) 1.0e-6))


(defn- approx-vec=
  [a b]
  (and (= (count a) (count b)) (every? true? (map approx= a b))))


;; ---------------------------------------------------------------------------
;; Uniform packing (shared by both backends)
;; ---------------------------------------------------------------------------

(deftest packed-vertex-uniforms-layout
  (testing "mvp ++ model ++ inverse(model), 48 doubles"
    (let [model (m/mat4-translation [2.0 3.0 4.0])
          mvp (m/mat4-scale [2.0 2.0 2.0])
          u (pack/packed-vertex-uniforms {:mvp mvp, :model-m model})]
      (is (= 48 (count u)))
      (is (approx-vec= mvp (subvec u 0 16)))
      (is (approx-vec= model (subvec u 16 32)))
      (is (approx-vec= (m/mat4-invert model) (subvec u 32 48))))))


(deftest packed-lighting-block-layout
  (testing "head packs camera, material, lighting flag; light count is clamped"
    (let [draw {:op {:material/specular [0.1 0.2 0.3],
                     :material/shininess 16.0,
                     :material/emissive [0.4 0.5 0.6]},
                :camera-pos [1.0 2.0 3.0],
                :lighting-enabled true,
                :lights [{:kind :ambient, :color [0.2 0.2 0.2]}
                         {:kind :directional,
                          :color [1.0 0.9 0.8],
                          :direction [0.0 -1.0 0.0],
                          :intensity 0.7}
                         {:kind :point,
                          :color [0.5 0.5 0.5],
                          :position [3.0 4.0 5.0],
                          :intensity 2.0,
                          :range 50.0}]}
          b (pack/packed-lighting-block draw)]
      (is (= (+ 12 (* 12 pack/max-packed-lights)) (count b)))
      ;; camera xyz + light count
      (is (approx-vec= [1.0 2.0 3.0 3.0] (subvec b 0 4)))
      ;; specular + shininess
      (is (approx-vec= [0.1 0.2 0.3 16.0] (subvec b 4 8)))
      ;; emissive + lighting-enabled
      (is (approx-vec= [0.4 0.5 0.6 1.0] (subvec b 8 12)))
      ;; light 0: ambient (intensity default 1, kind 0)
      (is (approx-vec= [0.2 0.2 0.2 1.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
                       (subvec b 12 24)))
      ;; light 1: directional (kind 1, range 0)
      (is (approx-vec= [1.0 0.9 0.8 0.7 0.0 -1.0 0.0 0.0 1.0 0.0 0.0 0.0]
                       (subvec b 24 36)))
      ;; light 2: point (kind 2, carries position + range)
      (is (approx-vec= [0.5 0.5 0.5 2.0 3.0 4.0 5.0 50.0 2.0 0.0 0.0 0.0]
                       (subvec b 36 48))))))


(deftest packed-lighting-block-defaults
  (testing "absent material + lights → zeros, shininess 32, lighting off"
    (let [b (pack/packed-lighting-block {:op {}, :lights []})]
      (is (approx= 0.0 (nth b 3)))  ; light count
      (is (approx-vec= [0.0 0.0 0.0 32.0] (subvec b 4 8)))
      (is (approx= 0.0 (nth b 11))) ; lighting-enabled off
      (is (every? zero? (subvec b 12))))))


(deftest packed-lighting-block-truncates
  (testing "more lights than max-packed-lights are truncated to the cap"
    (let [many (vec (repeat (+ 3 pack/max-packed-lights)
                            {:kind :ambient, :color [0.1 0.1 0.1]}))
          b (pack/packed-lighting-block
              {:op {}, :lighting-enabled true, :lights many})]
      (is (approx= (double pack/max-packed-lights) (nth b 3))))))


;; ---------------------------------------------------------------------------
;; Buffer packing (shared layout helpers)
;; ---------------------------------------------------------------------------

(defn- collect-into
  "Drives a put!-style packer into a vector of size n (filled with 0)."
  [n f]
  (let [a (atom (vec (repeat n 0.0)))]
    (f (fn [i x] (swap! a assoc i x)))
    @a))


(deftest pack-vertex-floats-layout
  (testing "12 interleaved floats per vertex, attrs resolved via vertex-attrs"
    (let [op {:vertices [[1 2 3] [4 5 6]],
              :uvs [[0.1 0.2] [0.3 0.4]],
              :normals [[0 1 0] [1 0 0]],
              :colors [[0.5 0.6 0.7 0.8] [0.9 1.0 0.1 0.2]]}
          out (collect-into 24 (fn [put!] (pack/pack-vertex-floats! op put!)))]
      (is (approx-vec= [1 2 3 0.1 0.2 0 1 0 0.5 0.6 0.7 0.8] (subvec out 0 12)))
      (is (approx-vec= [4 5 6 0.3 0.4 1 0 0 0.9 1.0 0.1 0.2]
                       (subvec out 12 24))))))


(deftest pack-vertex-floats-returns-count
  (let [op {:vertices [[0 0 0] [1 1 1] [2 2 2]]}]
    (is (= 36 (pack/pack-vertex-floats! op (fn [_ _] nil))))))


(deftest pack-indices-flattens-any-arity
  (testing "triangles"
    (is (= [0 1 2 3 4 5]
           (collect-into 6
                         (fn [put!]
                           (pack/pack-indices! [[0 1 2] [3 4 5]] put!))))))
  (testing "edges"
    (is (= [0 1 1 2]
           (collect-into 4
                         (fn [put!] (pack/pack-indices! [[0 1] [1 2]] put!))))))
  (testing "returns total written"
    (is (= 6 (pack/pack-indices! [[0 1 2] [3 4 5]] (fn [_ _] nil))))))


(deftest unlit-line-draw-collapses-op
  (let [draw {:lighting-enabled true,
              :op {:color [0.2 0.4 0.6 1.0],
                   :colors [[1 0 0 1]],
                   :normals [[0 1 0]],
                   :uvs [[0 0]],
                   :vertices [[0 0 0]]}}
        out (pack/unlit-line-draw draw)]
    (is (false? (:lighting-enabled out)))
    (is (= [0.2 0.4 0.6 1.0] (:fill (:op out))))
    (is (nil? (:colors (:op out))))
    (is (nil? (:normals (:op out))))
    (is (nil? (:uvs (:op out))))
    (is (= [[0 0 0]] (:vertices (:op out))))))


(deftest unlit-line-draw-color-default
  (let [out (pack/unlit-line-draw {:op {:vertices [[0 0 0]]}})]
    (is (= [1.0 1.0 1.0 1.0] (:fill (:op out))))))


(deftest clear-color-first-clear
  (is (= [0.1 0.2 0.3 1.0]
         (pack/clear-color
           {:passes [{:draws [{:pipeline :camera-reset}
                              {:pipeline :clear, :color [0.1 0.2 0.3 1.0]}
                              {:pipeline :clear, :color [9 9 9 9]}]}]}))))


(deftest clear-color-default-black
  (is (= [0.0 0.0 0.0 1.0]
         (pack/clear-color {:passes [{:draws [{:pipeline :mesh-3d}]}]}))))
