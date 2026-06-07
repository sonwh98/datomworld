(ns dao.postgraphics.math
  "Pure 16-vec (column-major) mat4 operations, camera/projection,
   near-plane clipping, and shared math. No host matrix types — every
   function takes and returns plain numeric vectors."
  (:require
    #?(:cljd ["dart:math" :as math])
    [dao.postgraphics.validation :refer [EPSILON reject!]]))


;; ---------------------------------------------------------------------------
;; Host transcendentals
;; ---------------------------------------------------------------------------

(defn- mcos
  [x]
  #?(:clj (Math/cos (double x))
     :cljs (js/Math.cos (double x))
     :cljd (math/cos (double x))))


(defn- msin
  [x]
  #?(:clj (Math/sin (double x))
     :cljs (js/Math.sin (double x))
     :cljd (math/sin (double x))))


(defn- msqrt
  [x]
  #?(:clj (Math/sqrt (double x))
     :cljs (js/Math.sqrt (double x))
     :cljd (math/sqrt (double x))))


(defn- mtan
  [x]
  #?(:clj (Math/tan (double x))
     :cljs (js/Math.tan (double x))
     :cljd (math/tan (double x))))


(defn- mabs
  [x]
  #?(:clj (Math/abs (double x))
     :cljs (js/Math.abs (double x))
     :cljd (let [d (double x)] (if (< d 0.0) (- d) d))))


(def PI
  #?(:clj Math/PI
     :cljs js/Math.PI
     :cljd math/pi))


;; ---------------------------------------------------------------------------
;; Numeric helpers
;; ---------------------------------------------------------------------------

(defn approx-zero?
  [x]
  (< (mabs (double x)) EPSILON))


(defn approx-one?
  [x]
  (< (mabs (- (double x) 1.0)) EPSILON))


;; ---------------------------------------------------------------------------
;; Mat4 constructors / predicates
;; ---------------------------------------------------------------------------

(defn mat4
  [& xs]
  (vec (map double xs)))


(defn identity-mat4
  []
  (mat4 1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1))


(defn matrix-valid-numbers?
  [m n]
  (and (vector? m)
       (= n (count m))
       (every? #?(:clj (fn [x] (Double/isFinite (double x)))
                  :cljs (fn [x] (js/isFinite (double x)))
                  :cljd (fn [x] (.-isFinite ^num (double x))))
               m)))


;; ---------------------------------------------------------------------------
;; Mat4 arithmetic
;; ---------------------------------------------------------------------------

(defn mat4-mul
  "Returns the product a·b, both column-major 16-vecs."
  [a b]
  (vec (for [col (range 4)
             row (range 4)]
         (+ (* (nth a row) (nth b (* col 4)))
            (* (nth a (+ 4 row)) (nth b (+ (* col 4) 1)))
            (* (nth a (+ 8 row)) (nth b (+ (* col 4) 2)))
            (* (nth a (+ 12 row)) (nth b (+ (* col 4) 3)))))))


(defn mat4-translation
  [[tx ty tz]]
  (mat4 1 0 0 0 0 1 0 0 0 0 1 0 (or tx 0.0) (or ty 0.0) (or tz 0.0) 1))


(defn mat4-scale
  [[sx sy sz]]
  (mat4 (or sx 1.0) 0 0 0 0 (or sy 1.0) 0 0 0 0 (or sz 1.0) 0 0 0 0 1))


(defn mat4-rotation-x
  [a]
  (let [c (mcos a) s (msin a)] (mat4 1 0 0 0 0 c s 0 0 (- s) c 0 0 0 0 1)))


(defn mat4-rotation-y
  [a]
  (let [c (mcos a) s (msin a)] (mat4 c 0 (- s) 0 0 1 0 0 s 0 c 0 0 0 0 1)))


(defn mat4-rotation-z
  [a]
  (let [c (mcos a) s (msin a)] (mat4 c s 0 0 (- s) c 0 0 0 0 1 0 0 0 0 1)))


;; ---------------------------------------------------------------------------
;; Mat4 inversion
;; ---------------------------------------------------------------------------

(defn mat4-invert
  "Returns the inverse of column-major 16-vec m, rejecting when singular."
  [m]
  (let [a00 (nth m 0)
        a01 (nth m 1)
        a02 (nth m 2)
        a03 (nth m 3)
        a10 (nth m 4)
        a11 (nth m 5)
        a12 (nth m 6)
        a13 (nth m 7)
        a20 (nth m 8)
        a21 (nth m 9)
        a22 (nth m 10)
        a23 (nth m 11)
        a30 (nth m 12)
        a31 (nth m 13)
        a32 (nth m 14)
        a33 (nth m 15)
        b00 (- (* a00 a11) (* a01 a10))
        b01 (- (* a00 a12) (* a02 a10))
        b02 (- (* a00 a13) (* a03 a10))
        b03 (- (* a01 a12) (* a02 a11))
        b04 (- (* a01 a13) (* a03 a11))
        b05 (- (* a02 a13) (* a03 a12))
        b06 (- (* a20 a31) (* a21 a30))
        b07 (- (* a20 a32) (* a22 a30))
        b08 (- (* a20 a33) (* a23 a30))
        b09 (- (* a21 a32) (* a22 a31))
        b10 (- (* a21 a33) (* a23 a31))
        b11 (- (* a22 a33) (* a23 a32))
        det (+ (* b00 b11)
               (- (* b01 b10))
               (* b02 b09)
               (* b03 b08)
               (- (* b04 b07))
               (* b05 b06))]
    (when (approx-zero? det) (reject! "4x4 matrix is not invertible"))
    (let [inv-det (/ 1.0 det)]
      (mat4 (* (- (* a11 b11) (* a12 b10) (- (* a13 b09))) inv-det)
            (* (+ (- (* a01 b11)) (* a02 b10) (- (* a03 b09))) inv-det)
            (* (- (* a31 b05) (* a32 b04) (- (* a33 b03))) inv-det)
            (* (+ (- (* a21 b05)) (* a22 b04) (- (* a23 b03))) inv-det)
            (* (+ (- (* a10 b11)) (* a12 b08) (- (* a13 b07))) inv-det)
            (* (- (* a00 b11) (* a02 b08) (- (* a03 b07))) inv-det)
            (* (+ (- (* a30 b05)) (* a32 b02) (- (* a33 b01))) inv-det)
            (* (- (* a20 b05) (* a22 b02) (- (* a23 b01))) inv-det)
            (* (- (* a10 b10) (* a11 b08) (- (* a13 b06))) inv-det)
            (* (+ (- (* a00 b10)) (* a01 b08) (- (* a03 b06))) inv-det)
            (* (- (* a30 b04) (* a31 b02) (- (* a33 b00))) inv-det)
            (* (+ (- (* a20 b04)) (* a21 b02) (- (* a23 b00))) inv-det)
            (* (+ (- (* a10 b09)) (* a11 b07) (- (* a12 b06))) inv-det)
            (* (- (* a00 b09) (* a01 b07) (- (* a02 b06))) inv-det)
            (* (+ (- (* a30 b03)) (* a31 b01) (- (* a32 b00))) inv-det)
            (* (- (* a20 b03) (* a21 b01) (- (* a22 b00))) inv-det)))))


;; ---------------------------------------------------------------------------
;; Affine 2D helpers
;; ---------------------------------------------------------------------------

(defn affine-translate-only?
  [{:keys [m00 m01 m10 m11]}]
  (and (approx-one? m00)
       (approx-zero? m01)
       (approx-zero? m10)
       (approx-one? m11)))


(defn affine
  [m00 m01 m10 m11 tx ty]
  (let [m {:m00 (double m00),
           :m01 (double m01),
           :m10 (double m10),
           :m11 (double m11),
           :tx (double tx),
           :ty (double ty),
           :affine-2d? true}]
    (assoc m :translate-only? (affine-translate-only? m))))


(defn matrix4-translation-only?
  [m]
  (and (approx-one? (nth m 0))
       (approx-zero? (nth m 1))
       (approx-zero? (nth m 2))
       (approx-zero? (nth m 3))
       (approx-zero? (nth m 4))
       (approx-one? (nth m 5))
       (approx-zero? (nth m 6))
       (approx-zero? (nth m 7))
       (approx-zero? (nth m 8))
       (approx-zero? (nth m 9))
       (approx-one? (nth m 10))
       (approx-zero? (nth m 11))
       (approx-zero? (nth m 14))
       (approx-one? (nth m 15))))


(defn matrix4-affine?
  "Non-throwing predicate: true when the 16-vec m is a valid 2D-affine
   transform (no perspective/Z coupling). Callers that want a specific
   rejection message guard on this; matrix4->affine rejects directly."
  [m]
  (and (approx-zero? (nth m 2))
       (approx-zero? (nth m 3))
       (approx-zero? (nth m 6))
       (approx-zero? (nth m 7))
       (approx-one? (nth m 10))
       (approx-zero? (nth m 11))
       (approx-zero? (nth m 14))
       (approx-one? (nth m 15))))


(defn matrix4->affine
  [m]
  (when-not (matrix4-affine? m)
    (reject! "4x4 matrix must be 2D-affine for 2D lowering"))
  (affine (nth m 0) (nth m 4) (nth m 1) (nth m 5) (nth m 12) (nth m 13)))


(defn transform-point
  [{:keys [m00 m01 m10 m11 tx ty]} [x y]]
  [(+ (* m00 x) (* m01 y) tx) (+ (* m10 x) (* m11 y) ty)])


(defn transformed-rect
  [m [x y w h]]
  (let [points [(transform-point m [x y]) (transform-point m [(+ x w) y])
                (transform-point m [(+ x w) (+ y h)])
                (transform-point m [x (+ y h)])]
        xs (map first points)
        ys (map second points)
        min-x (apply min xs)
        max-x (apply max xs)
        min-y (apply min ys)
        max-y (apply max ys)]
    [min-x min-y (- max-x min-x) (- max-y min-y)]))


(defn cart-rect->screen
  [[x y w h] viewport-height]
  [x (- viewport-height (+ y h)) w h])


(defn resolve-clip-rect
  [m rect viewport-height]
  (when-not (:translate-only? m)
    (reject! "clip/push-rect requires translate-only ancestry"))
  (let [[x y w h] rect]
    [(+ x (:tx m)) (- viewport-height (+ y (:ty m) h)) w h]))


;; ---------------------------------------------------------------------------
;; op->mat4
;; ---------------------------------------------------------------------------

(defn- matrix-op->mat4
  [mat]
  (cond
    (matrix-valid-numbers? mat 16) (mapv double mat)
    (matrix-valid-numbers? mat 9)
    (let [[m00 m01 m02 m10 m11 m12 m20 m21 m22] mat]
      (mat4 m00 m10 0.0 m20 m01 m11 0.0 m21 0.0 0.0 1.0 0.0 m02 m12 0.0 m22))
    :else (reject! "matrix must be a 9- or 16-element numeric vector")))


(defn op->mat4
  [op]
  (if-let [mat (:matrix op)]
    (matrix-op->mat4 mat)
    (let [translate (or (:translate op) [0.0 0.0 0.0])
          scale (or (:scale op) [1.0 1.0 1.0])
          rotate (let [r (:rotate op)]
                   (cond (nil? r) [0.0 0.0 0.0]
                         (number? r) [0.0 0.0 (double r)]
                         (and (vector? r) (= 3 (count r))) (mapv double r)
                         :else (reject!
                                 "rotate must be a number or [rx ry rz]")))]
      (when-not (or (and (vector? translate) (= 2 (count translate)))
                    (and (vector? translate) (= 3 (count translate))))
        (reject! "translate must be [x y] or [x y z]"))
      (when-not (or (and (vector? scale) (= 2 (count scale)))
                    (and (vector? scale) (= 3 (count scale))))
        (reject! "scale must be [x y] or [x y z]"))
      (let [[rx ry rz] rotate]
        (-> (mat4-translation translate)
            (mat4-mul (mat4-rotation-z rz))
            (mat4-mul (mat4-rotation-y ry))
            (mat4-mul (mat4-rotation-x rx))
            (mat4-mul (mat4-scale scale)))))))


;; ---------------------------------------------------------------------------
;; Camera / projection
;; ---------------------------------------------------------------------------

(defn build-camera
  [op width height]
  (let [projection (case (:camera3d/projection op)
                     :perspective (let [fov (double (:camera3d/fov op))
                                        near (double (:camera3d/near op))
                                        far (double (:camera3d/far op))
                                        aspect (double (get op
                                                            :camera3d/aspect
                                                            (/ width height)))
                                        f (/ 1.0 (mtan (/ (* fov PI) 360.0)))
                                        z-range (- near far)]
                                    (mat4 (/ f aspect)
                                          0.0
                                          0.0
                                          0.0
                                          0.0
                                          f
                                          0.0
                                          0.0
                                          0.0
                                          0.0
                                          (/ far z-range)
                                          -1.0
                                          0.0
                                          0.0
                                          (/ (* near far) z-range)
                                          0.0))
                     :orthographic (let [left (double (:camera3d/left op))
                                         right (double (:camera3d/right op))
                                         bottom (double (:camera3d/bottom op))
                                         top (double (:camera3d/top op))
                                         near (double (:camera3d/near op))
                                         far (double (:camera3d/far op))]
                                     (mat4 (/ 2.0 (- right left))
                                           0.0
                                           0.0
                                           0.0
                                           0.0
                                           (/ 2.0 (- top bottom))
                                           0.0
                                           0.0
                                           0.0
                                           0.0
                                           (/ 1.0 (- near far))
                                           0.0
                                           (- (/ (+ right left) (- right left)))
                                           (- (/ (+ top bottom) (- top bottom)))
                                           (/ near (- near far))
                                           1.0))
                     nil)
        view (if-let [vmat (:camera3d/view-matrix op)]
               (mapv double vmat)
               (let [pos (get op :camera3d/position [0.0 0.0 0.0])
                     rot (get op :camera3d/rotation [0.0 0.0 0.0])
                     [rx ry rz] rot
                     camera-world (-> (mat4-translation pos)
                                      (mat4-mul (mat4-rotation-z rz))
                                      (mat4-mul (mat4-rotation-y ry))
                                      (mat4-mul (mat4-rotation-x rx)))]
                 (mat4-invert camera-world)))]
    (mat4-mul projection view)))


(defn camera-pos-from-view-matrix
  [view-matrix]
  (let [inv (mat4-invert (mapv double view-matrix))]
    [(nth inv 12) (nth inv 13) (nth inv 14)]))


;; ---------------------------------------------------------------------------
;; Projection
;; ---------------------------------------------------------------------------

(defn project
  "Project vertex v through mvp, returning screen coords, NDC z, and 1/w
   for perspective-correct interpolation."
  [mvp v viewport-width viewport-height]
  (let [[vx vy vz] (mapv double v)
        x (+ (* (nth mvp 0) vx)
             (* (nth mvp 4) vy)
             (* (nth mvp 8) vz)
             (nth mvp 12))
        y (+ (* (nth mvp 1) vx)
             (* (nth mvp 5) vy)
             (* (nth mvp 9) vz)
             (nth mvp 13))
        z (+ (* (nth mvp 2) vx)
             (* (nth mvp 6) vy)
             (* (nth mvp 10) vz)
             (nth mvp 14))
        w (+ (* (nth mvp 3) vx)
             (* (nth mvp 7) vy)
             (* (nth mvp 11) vz)
             (nth mvp 15))
        inv-w (/ 1.0 w)
        ndc-x (* x inv-w)
        ndc-y (* y inv-w)
        ndc-z (* z inv-w)]
    {:screen-x (* (+ ndc-x 1.0) 0.5 (double viewport-width)),
     :screen-y (* (- 1.0 ndc-y) 0.5 (double viewport-height)),
     :z-ndc ndc-z,
     :inv-w inv-w}))


;; ---------------------------------------------------------------------------
;; Vec3
;; ---------------------------------------------------------------------------

(defn vec3-normalize
  [v]
  (let [x (double (nth v 0))
        y (double (nth v 1))
        z (double (nth v 2))
        len (msqrt (+ (* x x) (* y y) (* z z)))]
    (if (< len EPSILON) [0.0 0.0 0.0] [(/ x len) (/ y len) (/ z len)])))


;; ---------------------------------------------------------------------------
;; Normal transform (inverse-transpose of model 3x3)
;; ---------------------------------------------------------------------------

(defn inverse-transpose-3x3
  "Transforms a vec3 normal by the inverse-transpose of the upper-left
   3x3 of the given 16-vec model matrix.  Result is NOT renormalised."
  [m normal]
  (let [inv (mat4-invert m)
        x (double (nth normal 0))
        y (double (nth normal 1))
        z (double (nth normal 2))]
    [(+ (* (nth inv 0) x) (* (nth inv 1) y) (* (nth inv 2) z))
     (+ (* (nth inv 4) x) (* (nth inv 5) y) (* (nth inv 6) z))
     (+ (* (nth inv 8) x) (* (nth inv 9) y) (* (nth inv 10) z))]))


;; ---------------------------------------------------------------------------
;; Near-plane clipping
;; ---------------------------------------------------------------------------

(defn- interp-clip-vertex
  [va vb t]
  (let [[xa ya za wa] va
        [xb yb zb wb] vb
        td (double t)]
    [(+ (double xa) (* td (- (double xb) (double xa))))
     (+ (double ya) (* td (- (double yb) (double ya))))
     (+ (double za) (* td (- (double zb) (double za))))
     (+ (double wa) (* td (- (double wb) (double wa))))]))


(defn- w-distance
  [v]
  (- (double (nth v 3)) EPSILON))


(defn- z-distance
  [v]
  (+ (double (nth v 2)) EPSILON))


(defn- clip-line-against-plane
  [v1 v2 d-fn]
  (let [d1 (double (d-fn v1))
        d2 (double (d-fn v2))
        in1 (> d1 0.0)
        in2 (> d2 0.0)]
    (cond (and in1 in2) [v1 v2]
          (and (not in1) (not in2)) nil
          :else (let [t (/ d1 (- d1 d2))
                      vi (interp-clip-vertex v1 v2 t)]
                  (if in1 [v1 vi] [vi v2])))))


(defn clip-line-near
  [v1 v2]
  (when-let [pair (clip-line-against-plane v1 v2 w-distance)]
    (clip-line-against-plane (nth pair 0) (nth pair 1) z-distance)))


(defn- clip-triangle-against-plane
  [v1 v2 v3 d-fn]
  (let [d1 (double (d-fn v1))
        d2 (double (d-fn v2))
        d3 (double (d-fn v3))
        in1 (> d1 0.0)
        in2 (> d2 0.0)
        in3 (> d3 0.0)
        in-count (+ (if in1 1 0) (if in2 1 0) (if in3 1 0))]
    (case in-count
      3 [[v1 v2 v3]]
      0 []
      1 (let [[in-v out-v1 out-v2 d-in d-o1 d-o2] (cond in1 [v1 v2 v3 d1 d2 d3]
                                                        in2 [v2 v3 v1 d2 d3 d1]
                                                        in3 [v3 v1 v2 d3 d1 d2])
              i1 (interp-clip-vertex in-v out-v1 (/ d-in (- d-in d-o1)))
              i2 (interp-clip-vertex in-v out-v2 (/ d-in (- d-in d-o2)))]
          [[in-v i1 i2]])
      2 (let [[out-v in-v1 in-v2 d-out d-i1 d-i2]
              (cond (not in1) [v1 v2 v3 d1 d2 d3]
                    (not in2) [v2 v3 v1 d2 d3 d1]
                    (not in3) [v3 v1 v2 d3 d1 d2])
              i1 (interp-clip-vertex in-v1 out-v (/ d-i1 (- d-i1 d-out)))
              i2 (interp-clip-vertex in-v2 out-v (/ d-i2 (- d-i2 d-out)))]
          [[in-v1 i1 in-v2] [in-v2 i1 i2]]))))


(defn clip-triangle-near
  [v1 v2 v3]
  (let [after-w (clip-triangle-against-plane v1 v2 v3 w-distance)]
    (reduce (fn [acc [a b c]]
              (into acc (clip-triangle-against-plane a b c z-distance)))
            []
            after-w)))
