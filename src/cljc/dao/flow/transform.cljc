(ns dao.flow.transform
  #?(:cljd
     (:require
       ["dart:math" :as math])
     :default
     (:require
       [clojure.math :as math])))


(def identity-mat4
  [1.0 0.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 1.0])


(defn translate-mat4
  [[x y z]]
  [1.0 0.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 1.0 0.0 (double x) (double y)
   (double (or z 0.0)) 1.0])


(defn scale-mat4
  [[x y z]]
  [(double x) 0.0 0.0 0.0 0.0 (double y) 0.0 0.0 0.0 0.0 (double (or z 1.0)) 0.0
   0.0 0.0 0.0 1.0])


(defn rotate-x-mat4
  [angle]
  (let [c (math/cos angle)
        s (math/sin angle)]
    [1.0 0.0 0.0 0.0 0.0 c s 0.0 0.0 (- s) c 0.0 0.0 0.0 0.0 1.0]))


(defn rotate-y-mat4
  [angle]
  (let [c (math/cos angle)
        s (math/sin angle)]
    [c 0.0 (- s) 0.0 0.0 1.0 0.0 0.0 s 0.0 c 0.0 0.0 0.0 0.0 1.0]))


(defn rotate-z-mat4
  [angle]
  (let [c (math/cos angle)
        s (math/sin angle)]
    [c s 0.0 0.0 (- s) c 0.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 1.0]))


(defn mul-mat4
  [a b]
  (let [a00 (nth a 0)
        a01 (nth a 1)
        a02 (nth a 2)
        a03 (nth a 3)
        a10 (nth a 4)
        a11 (nth a 5)
        a12 (nth a 6)
        a13 (nth a 7)
        a20 (nth a 8)
        a21 (nth a 9)
        a22 (nth a 10)
        a23 (nth a 11)
        a30 (nth a 12)
        a31 (nth a 13)
        a32 (nth a 14)
        a33 (nth a 15)
        b00 (nth b 0)
        b01 (nth b 1)
        b02 (nth b 2)
        b03 (nth b 3)
        b10 (nth b 4)
        b11 (nth b 5)
        b12 (nth b 6)
        b13 (nth b 7)
        b20 (nth b 8)
        b21 (nth b 9)
        b22 (nth b 10)
        b23 (nth b 11)
        b30 (nth b 12)
        b31 (nth b 13)
        b32 (nth b 14)
        b33 (nth b 15)]
    [(+ (* a00 b00) (* a10 b01) (* a20 b02) (* a30 b03))
     (+ (* a01 b00) (* a11 b01) (* a21 b02) (* a31 b03))
     (+ (* a02 b00) (* a12 b01) (* a22 b02) (* a32 b03))
     (+ (* a03 b00) (* a13 b01) (* a23 b02) (* a33 b03))
     (+ (* a00 b10) (* a10 b11) (* a20 b12) (* a30 b13))
     (+ (* a01 b10) (* a11 b11) (* a21 b12) (* a31 b13))
     (+ (* a02 b10) (* a12 b11) (* a22 b12) (* a32 b13))
     (+ (* a03 b10) (* a13 b11) (* a23 b12) (* a33 b13))
     (+ (* a00 b20) (* a10 b21) (* a20 b22) (* a30 b23))
     (+ (* a01 b20) (* a11 b21) (* a21 b22) (* a31 b23))
     (+ (* a02 b20) (* a12 b21) (* a22 b22) (* a32 b23))
     (+ (* a03 b20) (* a13 b21) (* a23 b22) (* a33 b23))
     (+ (* a00 b30) (* a10 b31) (* a20 b32) (* a30 b33))
     (+ (* a01 b30) (* a11 b31) (* a21 b32) (* a31 b33))
     (+ (* a02 b30) (* a12 b31) (* a22 b32) (* a32 b33))
     (+ (* a03 b30) (* a13 b31) (* a23 b32) (* a33 b33))]))


(defn rotate-euler-mat4
  [[x y z]]
  (let [rx (rotate-x-mat4 (or x 0.0))
        ry (rotate-y-mat4 (or y 0.0))
        rz (rotate-z-mat4 (or z 0.0))]
    (mul-mat4 (mul-mat4 rz ry) rx)))


(defn compose-trs
  [t r s]
  (let [tm (if t (translate-mat4 t) identity-mat4)
        rm (if r (rotate-euler-mat4 r) identity-mat4)
        sm (if s (scale-mat4 s) identity-mat4)]
    (mul-mat4 tm (mul-mat4 rm sm))))


(defn perspective-mat4
  [fov-deg aspect near far]
  (let [f (/ 1.0
             (math/tan (/ (* fov-deg
                             (/ #?(:cljd math/pi
                                   :default math/PI)
                                180.0))
                          2.0)))
        nf (/ 1.0 (- near far))]
    [(/ f aspect) 0.0 0.0 0.0 0.0 f 0.0 0.0 0.0 0.0 (* (+ far near) nf) -1.0 0.0
     0.0 (* 2.0 far near nf) 0.0]))


(defn orthographic-mat4
  [left right bottom top near far]
  (let [lr (/ 1.0 (- left right))
        bt (/ 1.0 (- bottom top))
        nf (/ 1.0 (- near far))]
    [(* -2.0 lr) 0.0 0.0 0.0 0.0 (* -2.0 bt) 0.0 0.0 0.0 0.0 (* 2.0 nf) 0.0
     (* (+ left right) lr) (* (+ top bottom) bt) (* (+ far near) nf) 1.0]))
