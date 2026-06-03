(ns dao.postgraphics.software-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.postgraphics.software :as sw]))


;; --- helpers ---

(defn- approx=
  [a b]
  (< (Math/abs (- (double a) (double b))) 1.0e-5))


(deftest srgb-round-trip
  (is (approx= 0.5 (sw/linear->srgb (sw/srgb->linear 0.5))))
  (is (approx= 1.0 (sw/linear->srgb (sw/srgb->linear 1.0))))
  (is (approx= 0.0 (sw/linear->srgb (sw/srgb->linear 0.0))))
  (is (approx= 0.75 (sw/linear->srgb (sw/srgb->linear 0.75)))))


(deftest blinn-phong-ambient-only
  (let [result (sw/blinn-phong [0 0 0]
                               [0 0 1]
                               [0 0 5]
                               [0.5 0.5 0.5]
                               [0 0 0]
                               [0 0 0]
                               32.0
                               [{:kind :ambient, :color [0.2 0.2 0.2]}])
        ;; 0.2 sRGB -> linear ~0.01655, * kd 0.5 = ~0.00828
        expected (* 0.5 (sw/srgb->linear 0.2))]
    (is (approx= expected (nth result 0)))
    (is (approx= expected (nth result 1)))
    (is (approx= expected (nth result 2)))))


(deftest blinn-phong-directional-diffuse
  (let [result (sw/blinn-phong [0 0 0]
                               [0 0 1]
                               [0 0 5]
                               [0.5 0.5 0.5]
                               [0 0 0]
                               [0 0 0]
                               32.0
                               [{:kind :directional,
                                 :color [1.0 1.0 1.0],
                                 :direction [0 0 -1],
                                 :intensity 1.0}])]
    ;; N = [0 0 1], L = [0 0 1], N·L = 1, diffuse = 0.5
    (is (approx= 0.5 (nth result 0)))
    (is (approx= 0.5 (nth result 1)))
    (is (approx= 0.5 (nth result 2)))))


(deftest blinn-phong-point-attenuation
  (let [at-zero (sw/blinn-phong [0 0 0]
                                [0 0 1]
                                [0 0 5]
                                [0.5 0.5 0.5]
                                [0 0 0]
                                [0 0 0]
                                32.0
                                [{:kind :point,
                                  :color [1.0 1.0 1.0],
                                  :position [0 0 1],
                                  :intensity 1.0,
                                  :range 10.0}])
        at-range (sw/blinn-phong [0 0 10]
                                 [0 0 1]
                                 [0 0 5]
                                 [0.5 0.5 0.5]
                                 [0 0 0]
                                 [0 0 0]
                                 32.0
                                 [{:kind :point,
                                   :color [1.0 1.0 1.0],
                                   :position [0 0 1],
                                   :intensity 1.0,
                                   :range 10.0}])
        beyond (sw/blinn-phong [0 0 11]
                               [0 0 1]
                               [0 0 5]
                               [0.5 0.5 0.5]
                               [0 0 0]
                               [0 0 0]
                               32.0
                               [{:kind :point,
                                 :color [1.0 1.0 1.0],
                                 :position [0 0 0],
                                 :intensity 1.0,
                                 :range 10.0}])]
    ;; At zero distance: attenuation = 1
    (is (> (nth at-zero 0) 0.4))
    ;; At range boundary: attenuation = 0
    (is (approx= 0.0 (nth at-range 0)))
    ;; Beyond range: no contribution
    (is (approx= 0.0 (nth beyond 0)))))


(deftest sample-texture-nearest
  (let [texture {:rgba [255 0 0 255 0 255 0 255 0 0 255 255 255 255 255 255],
                 :width 2,
                 :height 2}]
    (is (= [1.0 0.0 0.0 1.0]
           (mapv #(/ % 255.0)
                 (sw/sample-texture texture 0.0 0.0 :clamp :nearest))))
    (is (= [0.0 1.0 0.0 1.0]
           (mapv #(/ % 255.0)
                 (sw/sample-texture texture 1.0 0.0 :clamp :nearest))))))


(deftest sample-texture-wrap-repeat
  (let [texture {:rgba [255 0 0 255], :width 1, :height 1}]
    (is (= [1.0 0.0 0.0 1.0]
           (mapv #(/ % 255.0)
                 (sw/sample-texture texture 2.5 3.5 :repeat :nearest))))))


(deftest rasterize-triangle-interior-pixel
  (let [pixels (atom [])
        depth (atom {})
        v0 {:pos [10.0 10.0 0.5 1.0],
            :uv [0.0 0.0],
            :normal [0.0 0.0 1.0],
            :world-pos [0.0 0.0 0.0],
            :color nil}
        v1 {:pos [20.0 10.0 0.5 1.0],
            :uv [1.0 0.0],
            :normal [0.0 0.0 1.0],
            :world-pos [1.0 0.0 0.0],
            :color nil}
        v2 {:pos [15.0 20.0 0.5 1.0],
            :uv [0.5 1.0],
            :normal [0.0 0.0 1.0],
            :world-pos [0.5 1.0 0.0],
            :color nil}
        shade-fn (fn [attrs] [1.0 0.0 0.0 1.0])]
    (sw/rasterize-triangle! v0
                            v1
                            v2
                            []
                            100
                            100
                            (fn [x y] (get @depth [x y] 1.0))
                            (fn [x y z] (swap! depth assoc [x y] z))
                            (fn [x y z rgba] (swap! pixels conj [x y rgba]))
                            shade-fn)
    ;; The triangle should cover some pixels
    (is (pos? (count @pixels)))
    ;; Pixel [15 15] should be inside
    (is (some #(and (= 15 (first %)) (= 15 (second %))) @pixels))))


(deftest rasterize-triangle-depth-test-reject
  (let [pixels (atom [])
        depth (atom {[15 15] 0.1})
        v0 {:pos [10.0 10.0 0.5 1.0],
            :uv [0.0 0.0],
            :normal [0.0 0.0 1.0],
            :world-pos [0.0 0.0 0.0],
            :color nil}
        v1 {:pos [20.0 10.0 0.5 1.0],
            :uv [1.0 0.0],
            :normal [0.0 0.0 1.0],
            :world-pos [1.0 0.0 0.0],
            :color nil}
        v2 {:pos [15.0 20.0 0.5 1.0],
            :uv [0.5 1.0],
            :normal [0.0 0.0 1.0],
            :world-pos [0.5 1.0 0.0],
            :color nil}
        shade-fn (fn [attrs] [1.0 0.0 0.0 1.0])]
    (sw/rasterize-triangle! v0
                            v1
                            v2
                            []
                            100
                            100
                            (fn [x y] (get @depth [x y] 1.0))
                            (fn [x y z] (swap! depth assoc [x y] z))
                            (fn [x y z rgba] (swap! pixels conj [x y rgba]))
                            shade-fn)
    ;; Pixel [15 15] has existing depth 0.1 < 0.5, so should be rejected
    (is (not (some #(and (= 15 (first %)) (= 15 (second %))) @pixels)))))


(deftest rasterize-triangle-clip-rect-exclusion
  (let [pixels (atom [])
        depth (atom {})
        v0 {:pos [10.0 10.0 0.5 1.0],
            :uv [0.0 0.0],
            :normal [0.0 0.0 1.0],
            :world-pos [0.0 0.0 0.0],
            :color nil}
        v1 {:pos [20.0 10.0 0.5 1.0],
            :uv [1.0 0.0],
            :normal [0.0 0.0 1.0],
            :world-pos [1.0 0.0 0.0],
            :color nil}
        v2 {:pos [15.0 20.0 0.5 1.0],
            :uv [0.5 1.0],
            :normal [0.0 0.0 1.0],
            :world-pos [0.5 1.0 0.0],
            :color nil}
        shade-fn (fn [attrs] [1.0 0.0 0.0 1.0])]
    (sw/rasterize-triangle! v0
                            v1
                            v2
                            [[0 0 5 5]]
                            100
                            100
                            (fn [x y] (get @depth [x y] 1.0))
                            (fn [x y z] (swap! depth assoc [x y] z))
                            (fn [x y z rgba] (swap! pixels conj [x y rgba]))
                            shade-fn)
    ;; Pixels inside [0 0 5 5] should be excluded
    (is (not (some #(and (< (first %) 5) (< (second %) 5)) @pixels)))))
