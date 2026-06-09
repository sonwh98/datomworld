(ns dao.postgraphics.math-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.postgraphics.math :as math]
    [dao.postgraphics.validation :as v]))


(def eps 1.0e-6)
(def boundary-tol 1.0e-12)


(defn- approx=
  [a b]
  (let [delta (- (double a) (double b))] (< (Math/abs delta) 1.0e-6)))


(defn- approx-vec=
  [a b]
  (and (= (count a) (count b)) (every? true? (map approx= a b))))


(defn- approx-vec3=
  [a b]
  (and (approx= (nth a 0) (nth b 0))
       (approx= (nth a 1) (nth b 1))
       (approx= (nth a 2) (nth b 2))))


;; ---------------------------------------------------------------------------
;; mat4 constructors
;; ---------------------------------------------------------------------------

(deftest mat4-constructor
  (is (= [1.0 2.0 3.0 4.0 5.0 6.0 7.0 8.0 9.0 10.0 11.0 12.0 13.0 14.0 15.0
          16.0]
         (math/mat4 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16))))


(deftest identity-mat4
  (is (= [1.0 0.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 1.0]
         (math/identity-mat4))))


(deftest matrix-valid-numbers?
  (is (math/matrix-valid-numbers? [0.0 1.0 2.0 3.0] 4))
  (is (not (math/matrix-valid-numbers? [0.0 1.0 2.0] 4)))
  (is (not (math/matrix-valid-numbers? [0.0 1.0 ##NaN 3.0] 4)))
  (is (not (math/matrix-valid-numbers? [0.0 1.0 ##Inf 3.0] 4)))
  (is (not (math/matrix-valid-numbers? [0.0 1.0 ##-Inf 3.0] 4))))


(deftest approx-zero?
  (is (math/approx-zero? 0.0))
  (is (math/approx-zero? 1.0e-10))
  (is (not (math/approx-zero? 0.01))))


(deftest approx-one?
  (is (math/approx-one? 1.0))
  (is (math/approx-one? 1.0000000001))
  (is (not (math/approx-one? 1.01))))


;; ---------------------------------------------------------------------------
;; mat4 arithmetic
;; ---------------------------------------------------------------------------

(deftest mat4-mul-identity
  (let [id (math/identity-mat4)
        m (math/mat4-translation [1 2 3])]
    (is (approx-vec= m (math/mat4-mul id m)))
    (is (approx-vec= m (math/mat4-mul m id)))))


(deftest mat4-mul-two-translations
  (let [t1 (math/mat4-translation [1 2 3])
        t2 (math/mat4-translation [4 5 6])
        result (math/mat4-mul t1 t2)]
    (is (approx= (+ 1 4) (nth result 12)))
    (is (approx= (+ 2 5) (nth result 13)))
    (is (approx= (+ 3 6) (nth result 14)))))


(deftest mat4-mul-rotation-identity
  (let [id (math/identity-mat4)
        rx (math/mat4-rotation-x (* 0.5 Math/PI))]
    (is (approx-vec= rx (math/mat4-mul id rx)))
    (is (approx-vec= rx (math/mat4-mul rx id)))))


;; ---------------------------------------------------------------------------
;; mat4 inversion
;; ---------------------------------------------------------------------------

(deftest mat4-invert-identity
  (let [id (math/identity-mat4)] (is (approx-vec= id (math/mat4-invert id)))))


(deftest mat4-invert-translation
  (let [t (math/mat4-translation [3 -7 2])
        inv (math/mat4-invert t)]
    (is (approx= -3.0 (nth inv 12)))
    (is (approx= 7.0 (nth inv 13)))
    (is (approx= -2.0 (nth inv 14)))
    (is (approx-vec= (math/identity-mat4) (math/mat4-mul t inv)))))


(deftest mat4-invert-rotation
  (let [r (math/mat4-rotation-z (* 0.5 Math/PI))
        inv (math/mat4-invert r)
        prod (math/mat4-mul r inv)]
    (is (approx-vec= (math/identity-mat4) prod))))


(deftest mat4-invert-non-uniform-scale
  (let [m [2.0 0.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 0.5 0.0 0.0 0.0 0.0 1.0]
        inv (math/mat4-invert m)]
    (is (approx= 0.5 (nth inv 0)))
    (is (approx= 1.0 (nth inv 5)))
    (is (approx= 2.0 (nth inv 10)))))


;; ---------------------------------------------------------------------------
;; mat4 rotation
;; ---------------------------------------------------------------------------

(deftest mat4-rotation-x-point
  (let [r90 (math/mat4-rotation-x (* 0.5 Math/PI))
        result [(+ (* (nth r90 0) 0)
                   (* (nth r90 4) 0)
                   (* (nth r90 8) 1.0)
                   (nth r90 12))
                (+ (* (nth r90 1) 0)
                   (* (nth r90 5) 0)
                   (* (nth r90 9) 1.0)
                   (nth r90 13))
                (+ (* (nth r90 2) 0)
                   (* (nth r90 6) 0)
                   (* (nth r90 10) 1.0)
                   (nth r90 14))
                (+ (* (nth r90 3) 0)
                   (* (nth r90 7) 0)
                   (* (nth r90 11) 1.0)
                   (nth r90 15))]]
    (is (approx= 0.0 (nth result 0)))
    (is (approx= -1.0 (nth result 1)))
    (is (approx= 0.0 (nth result 2)))))


;; ---------------------------------------------------------------------------
;; op->mat4
;; ---------------------------------------------------------------------------

(deftest op->mat4-from-16-vec
  (let [m (math/op->mat4 {:matrix [1 0 0 0 0 1 0 0 0 0 1 0 4 5 6 1]})]
    (is (approx-vec= [1.0 0.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 1.0 0.0 4.0 5.0
                      6.0 1.0]
                     m))))


(deftest op->mat4-from-9-vec
  (let [m (math/op->mat4 {:matrix [1 2 3 4 5 6 7 8 9]})]
    (is (= 16 (count m)))
    (is (approx= 1.0 (nth m 0)))
    (is (approx= 4.0 (nth m 1)))
    (is (approx= 2.0 (nth m 4)))))


(deftest op->mat4-from-ops
  (let [m (math/op->mat4
            {:translate [10 20 30], :rotate (/ Math/PI 2), :scale [2 2 2]})]
    (is (= 16 (count m)))
    (is (approx= 0.0 (nth m 0))) ; cos(pi/2)*2 = 0
    (is (approx= 2.0 (nth m 1))) ; sin(pi/2)*2 = 2
    (is (approx= 10.0 (nth m 12)))))  ; tx = 10


;; ---------------------------------------------------------------------------
;; camera / projection
;; ---------------------------------------------------------------------------

(deftest build-camera-perspective
  (let [cam (math/build-camera {:camera3d/projection :perspective,
                                :camera3d/fov 45.0,
                                :camera3d/near 0.1,
                                :camera3d/far 10.0,
                                :camera3d/position [0 0 5]}
                               800
                               600)]
    (is (= 16 (count cam)))
    (is (> (Math/abs (nth cam 0)) 0.0))))


(deftest build-camera-orthographic
  (let [cam (math/build-camera {:camera3d/projection :orthographic,
                                :camera3d/left -4,
                                :camera3d/right 4,
                                :camera3d/bottom -3,
                                :camera3d/top 3,
                                :camera3d/near 0.1,
                                :camera3d/far 100.0}
                               800
                               600)]
    (is (= 16 (count cam)))))


(deftest camera-pos-from-view-matrix-identity
  (let [pos (math/camera-pos-from-view-matrix [1 0 0 0 0 1 0 0 0 0 1 0 0 0 0
                                               1])]
    (is (approx-vec= [0.0 0.0 0.0] pos))))


(deftest camera-pos-from-view-matrix-translated
  (let [pos (math/camera-pos-from-view-matrix [1 0 0 0 0 1 0 0 0 0 1 0 -3 2 -5
                                               1])]
    (is (approx-vec= [3.0 -2.0 5.0] pos))))


;; ---------------------------------------------------------------------------
;; project
;; ---------------------------------------------------------------------------

(deftest project-identity
  (let [mvp (math/identity-mat4)
        result (math/project mvp [0.5 0.5 0.0] 800 600)]
    (is (approx= 600.0 (:screen-x result)))
    (is (approx= 150.0 (:screen-y result)))
    (is (approx= 0.0 (:z-ndc result)))
    (is (approx= 1.0 (:inv-w result)))))


;; ---------------------------------------------------------------------------
;; vec3-normalize
;; ---------------------------------------------------------------------------

(deftest vec3-normalize-unit-x
  (let [v (math/vec3-normalize [5 0 0])] (is (approx-vec= [1.0 0.0 0.0] v))))


(deftest vec3-normalize-arbitrary
  (let [v (math/vec3-normalize [3 4 0])]
    (is (approx= 0.6 (nth v 0)))
    (is (approx= 0.8 (nth v 1)))
    (is (approx= 0.0 (nth v 2)))))


(deftest vec3-normalize-zero
  (let [v (math/vec3-normalize [0 0 0])] (is (approx-vec= [0.0 0.0 0.0] v))))


;; ---------------------------------------------------------------------------
;; inverse-transpose-3x3
;; ---------------------------------------------------------------------------

(deftest inverse-transpose-3x3-identity
  (let [result (math/inverse-transpose-3x3 (math/identity-mat4) [1 0 1])]
    (is (approx-vec3= [1.0 0.0 1.0] result))))


(deftest inverse-transpose-3x3-non-uniform-scale
  (let [model-m [2.0 0.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 0.5 0.0 0.0 0.0 0.0
                 1.0]
        result (math/inverse-transpose-3x3 model-m [1.0 0.0 1.0])]
    (is (approx= 0.5 (nth result 0))) ; inv(2) = 0.5
    (is (approx= 0.0 (nth result 1)))
    (is (approx= 2.0 (nth result 2)))))


;; ---------------------------------------------------------------------------
;; Near-plane clipping
;; ---------------------------------------------------------------------------

(def clipped-vertex-valid?
  (let [eps v/EPSILON]
    (fn [v]
      (and (>= (nth v 3) (- eps boundary-tol))
           (>= (nth v 2) (- eps boundary-tol))))))


(def line-outside-by-w [0.0 0.0 1.0 0.0])
(def line-inside [0.5 0.0 2.0 (* 2.0 v/EPSILON)])
(def tri-inside-a [0.0 0.0 1.0 (* 2.0 v/EPSILON)])
(def tri-inside-b [0.0 0.5 1.5 (* 3.0 v/EPSILON)])


(deftest near-plane-clipping-keeps-generated-vertices-in-front-of-both-planes
  (testing "line clipping stays valid when only w_c puts one endpoint outside"
    (let [clipped-line (math/clip-line-near line-outside-by-w line-inside)]
      (is (= 2 (count clipped-line)))
      (is (every? clipped-vertex-valid? clipped-line))
      (is (some #(= v/EPSILON (nth % 3)) clipped-line))))
  (testing "triangle clipping does not synthesize vertices with invalid w_c"
    (let [tris (math/clip-triangle-near line-outside-by-w
                                        tri-inside-a
                                        tri-inside-b)]
      (is (= 2 (count tris)))
      (is (every? clipped-vertex-valid? (mapcat seq tris)))
      (is (some #(= v/EPSILON (nth % 3)) (mapcat seq tris))))))


(deftest clip-line-near-fully-inside
  (let [result (math/clip-line-near line-inside line-inside)]
    (is (= 2 (count result)))
    (is (every? clipped-vertex-valid? result))))


(deftest clip-line-near-fully-outside
  (let [result (math/clip-line-near line-outside-by-w line-outside-by-w)]
    (is (nil? result))))


(deftest clip-triangle-near-fully-inside
  (let [result (math/clip-triangle-near tri-inside-a tri-inside-b tri-inside-b)]
    (is (= 1 (count result)))
    (is (every? clipped-vertex-valid? (mapcat seq result)))))


(deftest clip-triangle-near-fully-outside
  (let [result (math/clip-triangle-near line-outside-by-w
                                        line-outside-by-w
                                        line-outside-by-w)]
    (is (empty? result))))


;; ---------------------------------------------------------------------------
;; Affine 2D helpers
;; ---------------------------------------------------------------------------

(deftest matrix4-translation-only?
  (is (math/matrix4-translation-only? (math/mat4-translation [1 2 0])))
  (is (not (math/matrix4-translation-only? (math/mat4-rotation-z 0.5))))
  (is (math/matrix4-translation-only? (math/identity-mat4))))


(deftest matrix4->affine
  (let [m (math/mat4-translation [3 5 0])
        a (math/matrix4->affine m)]
    (is (:affine-2d? a))
    (is (true? (:translate-only? a)))
    (is (approx= 3.0 (:tx a)))
    (is (approx= 5.0 (:ty a)))))


(deftest transform-point
  (let [aff (math/affine 1 0 0 1 10 20)]
    (is (= [15.0 30.0] (math/transform-point aff [5 10])))))


(deftest cart-rect->screen
  (let [result (math/cart-rect->screen [0 80 100 20] 100)]
    (is (= [0 0 100 20] result))))


(deftest resolve-clip-rect
  (let [aff (math/affine 1 0 0 1 5 10)
        result (math/resolve-clip-rect aff [10.0 20.0 30.0 40.0] 100.0)]
    (is (approx= 15.0 (nth result 0)))
    (is (approx= 30.0 (nth result 1)))
    (is (approx= 30.0 (nth result 2)))
    (is (approx= 40.0 (nth result 3)))))


;; ---------------------------------------------------------------------------
;; affine-scale-x — screen-space stroke compensation
;; ---------------------------------------------------------------------------

(deftest affine-scale-x-identity
  (is (approx= 1.0 (math/affine-scale-x [1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1]))))


(deftest affine-scale-x-scale2
  (testing "a scale-2 transform reports scale factor 2"
    (is (approx= 2.0 (math/affine-scale-x [2 0 0 0 0 2 0 0 0 0 1 0 0 0 0 1])))))


(deftest affine-scale-x-rotation-preserves-length
  (testing "a pure rotation has unit scale (sqrt(cos^2+sin^2))"
    (let [c (Math/cos 0.7)
          s (Math/sin 0.7)]
      (is (approx= 1.0
                   (math/affine-scale-x [c s 0 0 (- s) c 0 0 0 0 1 0 0 0 0
                                         1]))))))


;; ---------------------------------------------------------------------------
;; image-fit-rect — :draw/image fit modes
;; ---------------------------------------------------------------------------

(deftest image-fit-fill
  (testing ":fill stretches source over the whole destination rect"
    (is (= [0.0 0.0 100.0 50.0 0.0 0.0 200.0 200.0]
           (mapv double
                 (math/image-fit-rect :fill [0 0 100 50] [0 0 200 200]))))))


(deftest image-fit-contain
  (testing ":contain fits the source inside dst, letterboxing the long axis"
    ;; source 200x100 into dst 100x100: scale = min(100/200,100/100)=0.5
    ;; fitted = 100x50, centred vertically -> y offset (100-50)/2 = 25
    (let [[dx dy dw dh sx sy sw sh]
          (math/image-fit-rect :contain [0 0 100 100] [0 0 200 100])]
      (is (approx= 0.0 dx))
      (is (approx= 25.0 dy))
      (is (approx= 100.0 dw))
      (is (approx= 50.0 dh))
      (is (approx= 0.0 sx))
      (is (approx= 0.0 sy))
      (is (approx= 200.0 sw))
      (is (approx= 100.0 sh)))))


(deftest image-fit-cover
  (testing ":cover fills dst and crops the source's overflowing axis"
    ;; source 200x100 into dst 100x100: scale = max(100/200,100/100)=1.0
    ;; src window = 100x100, cropped horizontally -> sx (200-100)/2 = 50
    (let [[dx dy dw dh sx sy sw sh]
          (math/image-fit-rect :cover [0 0 100 100] [0 0 200 100])]
      (is (approx= 0.0 dx))
      (is (approx= 0.0 dy))
      (is (approx= 100.0 dw))
      (is (approx= 100.0 dh))
      (is (approx= 50.0 sx))
      (is (approx= 0.0 sy))
      (is (approx= 100.0 sw))
      (is (approx= 100.0 sh)))))


(deftest image-fit-none
  (testing ":none centre-crops at source pixel size"
    ;; source 200x200 into dst 100x100: visible window = 100x100 of source,
    ;; centred in both source and dst.
    (let [[dx dy dw dh sx sy sw sh]
          (math/image-fit-rect :none [0 0 100 100] [0 0 200 200])]
      (is (approx= 0.0 dx))
      (is (approx= 0.0 dy))
      (is (approx= 100.0 dw))
      (is (approx= 100.0 dh))
      (is (approx= 50.0 sx))
      (is (approx= 50.0 sy))
      (is (approx= 100.0 sw))
      (is (approx= 100.0 sh)))))
