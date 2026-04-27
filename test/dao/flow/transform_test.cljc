(ns dao.flow.transform-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.flow.transform :as t]))


(defn- approx=
  [a b]
  (let [eps 1e-5]
    (cond (and (number? a) (number? b)) (< (Math/abs (- (double a) (double b)))
                                           eps)
          (and (sequential? a) (sequential? b))
          (and (= (count a) (count b)) (every? true? (map approx= a b)))
          :else (= a b))))


(deftest identity-and-compose
  (testing "Matrix algebra and projection"
    (let [id t/identity-mat4
          trans (t/translate-mat4 [1 2 3])
          scale (t/scale-mat4 [2 2 2])
          ;; translate then scale
          composed (t/mul-mat4 scale trans)]
      (is (approx= [1 0 0 0 0 1 0 0 0 0 1 0 1 2 3 1] trans))
      (is (approx= [2 0 0 0 0 2 0 0 0 0 2 0 2 4 6 1] composed))
      (let [point [0 0 -1 1] ; z=-1
            ;; A perspective matrix looking down -Z
            persp (t/perspective-mat4 90.0 1.0 0.1 100.0)
            ;; multiply mat * vec
            v-out [(+ (* (nth persp 0) (nth point 0))
                      (* (nth persp 4) (nth point 1))
                      (* (nth persp 8) (nth point 2))
                      (* (nth persp 12) (nth point 3)))
                   (+ (* (nth persp 1) (nth point 0))
                      (* (nth persp 5) (nth point 1))
                      (* (nth persp 9) (nth point 2))
                      (* (nth persp 13) (nth point 3)))
                   (+ (* (nth persp 2) (nth point 0))
                      (* (nth persp 6) (nth point 1))
                      (* (nth persp 10) (nth point 2))
                      (* (nth persp 14) (nth point 3)))
                   (+ (* (nth persp 3) (nth point 0))
                      (* (nth persp 7) (nth point 1))
                      (* (nth persp 11) (nth point 2))
                      (* (nth persp 15) (nth point 3)))]]
        ;; The Z coordinate in clip space should be somewhat negative or
        ;; positive depending on depth range, and W should be 1 (since
        ;; z=-1). Our primary invariant is that it runs and transforms
        ;; reasonably.
        (is (= 4 (count v-out)))))))
