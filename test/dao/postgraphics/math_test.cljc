(ns dao.postgraphics.math-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.postgraphics.math :as m]))


;; --- helpers ---

(defn- approx=
  [a b]
  (< (Math/abs (- (double a) (double b))) 1.0e-5))


(defn- approx-vec=
  [a b]
  (and (= (count a) (count b)) (every? true? (map approx= a b))))


(deftest mat4-mul-identity
  (let [a (m/mat4 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16)
        i (m/identity-mat4)]
    (is (= a (m/mat4-mul a i)))
    (is (= a (m/mat4-mul i a)))))


(deftest mat4-mul-known-product
  (let [a (m/mat4 1 0 0 0 0 2 0 0 0 0 3 0 0 0 0 1)
        b (m/mat4 1 0 0 0 0 1 0 0 0 0 1 0 4 5 6 1)
        r (m/mat4-mul a b)]
    (is (approx= 4.0 (nth r 12)))
    (is (approx= 10.0 (nth r 13)))
    (is (approx= 18.0 (nth r 14)))))


(deftest mat4-invert-roundtrip
  (let [tr (m/mat4-mul (m/mat4-translation [3 4 5]) (m/mat4-rotation-z 0.5))
        inv (m/mat4-invert tr)
        prod (m/mat4-mul tr inv)]
    (is (approx-vec= (m/identity-mat4) prod))))


(deftest inverse-transpose-3x3-under-non-uniform-scale
  (let [s (m/mat4-scale [2.0 3.0 4.0])
        it (m/inverse-transpose-3x3 s)
        expected [0.5 0.0 0.0 0.0 (/ 1.0 3.0) 0.0 0.0 0.0 0.25]]
    (is (approx-vec= expected it))))


(deftest project-known-mvp
  (let [mvp (m/mat4 1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1)
        [sx sy z inv-w] (m/project mvp [0 0 0] 100 50)]
    (is (approx= 50.0 sx))
    (is (approx= 25.0 sy))
    (is (approx= 0.0 z))
    (is (approx= 1.0 inv-w))))


(deftest clip-line-near-parity
  (is (= [[0 0 1 2] [0 0 1 1]] (m/clip-line-near [0 0 1 2] [0 0 1 1])))
  (is (nil? (m/clip-line-near [0 0 -1 1] [0 0 -1 1])))
  (let [result (m/clip-line-near [0 0 1 2] [0 0 -1 1])]
    (is (= 2 (count result)))
    (is (approx= 1.0 (nth (first result) 2)))
    (is (approx= 0.0 (nth (second result) 2)))))


(deftest clip-triangle-near-parity
  (is (= [[[0 0 1 1] [1 0 1 1] [0 1 1 1]]]
         (m/clip-triangle-near [0 0 1 1] [1 0 1 1] [0 1 1 1])))
  (is (empty? (m/clip-triangle-near [0 0 -1 1] [1 0 -1 1] [0 1 -1 1])))
  (let [result (m/clip-triangle-near [0 0 1 1] [1 0 1 1] [0 1 -1 1])]
    ;; One vertex behind near plane produces 2 triangles after w- and
    ;; z-clipping
    (is (= 2 (count result)))
    (is (= 3 (count (first result))))
    (is (= 3 (count (second result))))))
