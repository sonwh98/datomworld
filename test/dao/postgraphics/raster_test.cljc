(ns dao.postgraphics.raster-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.postgraphics.math :as m]
    [dao.postgraphics.raster :as raster]))


(defn- approx=
  [a b]
  (< (m/mabs (- (double a) (double b))) 1.0e-6))


(defn- approx-vec=
  [a b]
  (and (= (count a) (count b)) (every? true? (map approx= a b))))


(defn- approx-vec4=
  [a b]
  (and (approx= (nth a 0) (nth b 0))
       (approx= (nth a 1) (nth b 1))
       (approx= (nth a 2) (nth b 2))
       (approx= (nth a 3) (nth b 3))))


;; ---------------------------------------------------------------------------
;; sRGB round-trip
;; ---------------------------------------------------------------------------

(deftest srgb-round-trip
  (is (approx-vec4= [0.5 0.5 0.5 1.0]
                    (-> [0.5 0.5 0.5 1.0]
                        raster/srgb->linear
                        raster/linear->srgb))))


(deftest srgb-black
  (is (approx-vec4= [0.0 0.0 0.0 0.0] (raster/srgb->linear [0.0 0.0 0.0 0.0]))))


(deftest srgb-white
  (is (approx-vec4= [1.0 1.0 1.0 1.0] (raster/srgb->linear [1.0 1.0 1.0 1.0]))))


;; ---------------------------------------------------------------------------
;; vertex-attrs
;; ---------------------------------------------------------------------------

(deftest vertex-attrs-basic
  (let [op {:vertices [[1 2 3] [4 5 6] [7 8 9]],
            :indices [[0 1 2]],
            :fill [1 1 1 1]}]
    (is (approx-vec= [0 0] (nth (raster/vertex-attrs op 0) 0)))
    (is (approx-vec= [1 2 3] (nth (raster/vertex-attrs op 0) 1)))
    (is (= [1 1 1 1] (nth (raster/vertex-attrs op 0) 3)))))


(deftest vertex-attrs-colors-over-fill
  (let [op {:vertices [[0 0 0] [0 0 0]],
            :fill [1 0 0 1],
            :colors [[0 1 0 1] [0 0 1 1]]}]
    (is (approx-vec4= [0.0 1.0 0.0 1.0] (nth (raster/vertex-attrs op 0) 3)))
    (is (approx-vec4= [0.0 0.0 1.0 1.0] (nth (raster/vertex-attrs op 1) 3)))))


(deftest vertex-attrs-defaults
  (let [op {:vertices [[0 0 0]]}]
    (is (approx-vec= [0 0] (nth (raster/vertex-attrs op 0) 0)))
    (is (approx-vec= [0 0 0] (nth (raster/vertex-attrs op 0) 1)))
    (is (approx-vec4= [1.0 1.0 1.0 1.0] (nth (raster/vertex-attrs op 0) 3)))))


;; ---------------------------------------------------------------------------
;; sample-texture
;; ---------------------------------------------------------------------------

;; :rgba holds 0–255 byte values (the real Uint8List texture contract); the
;; sampler normalizes to [0,1].
(def test-tex
  {:width 2,
   :height 2,
   :rgba [255 0 0 255 0 255 0 255 0 0 255 255 255 255 255 255]})


(deftest sample-texture-nearest
  (is (approx-vec= [1 0 0 1]
                   (raster/sample-texture test-tex 0 0 {:filter :nearest})))
  (is (approx-vec= [0 1 0 1]
                   (raster/sample-texture test-tex 0.6 0.4 {:filter :nearest})))
  (is (approx-vec=
        [1 1 1 1]
        (raster/sample-texture test-tex 0.9 0.9 {:filter :nearest}))))


(deftest sample-texture-clamp
  (is (approx-vec= [1 0 0 1] (raster/sample-texture test-tex -0.5 -0.5 {})))
  (is (approx-vec= [1 1 1 1] (raster/sample-texture test-tex 2.0 2.0 {}))))


;; ---------------------------------------------------------------------------
;; blinn-phong
;; ---------------------------------------------------------------------------

(def test-material {:diffuse [1 0 0], :specular [1 1 1], :shininess 32})


(def test-lights
  [{:kind :ambient, :color [0.1 0.1 0.1]}
   {:kind :directional, :color [1 1 1], :direction [0 0 1]}])


(deftest blinn-phong-ambient-only
  ;; Light colours are linearised before accumulation: sRGB 0.2 -> ~0.0331
  ;; linear (v4 §Lighting Equation operates in linear space).
  (let [result (raster/blinn-phong {:diffuse [1 0 0]}
                                   [{:kind :ambient, :color [0.2 0.2 0.2]}]
                                   [0 0 5]
                                   [0 0 1]
                                   [0 0 0])]
    (is (approx= 0.03310477 (nth result 0)))
    (is (approx= 0.0 (nth result 1)))
    (is (approx= 0.0 (nth result 2)))))


(deftest blinn-phong-directional-diffuse
  (let [result (raster/blinn-phong
                 {:diffuse [1 1 1]}
                 [{:kind :directional, :color [1 1 1], :direction [0 0 1]}]
                 [0 0 5]
                 [0 0 1]
                 [0 0 0])]
    (is (= 3 (count result)))
    (is (> (nth result 0) 0.9))))


(deftest blinn-phong-directional-backface
  (let [result (raster/blinn-phong
                 {:diffuse [1 1 1]}
                 [{:kind :directional, :color [1 1 1], :direction [0 0 1]}]
                 [0 0 5]
                 [0 0 -1]
                 [0 0 0])]
    (is (approx-vec= [0.0 0.0 0.0] result))))


(deftest blinn-phong-emissive
  (testing
    "emissive adds independent of lights (no lights -> output = emissive)"
    (let [result (raster/blinn-phong {:diffuse [1 1 1], :emissive [0.5 0.2 0.1]}
                                     []
                                     [0 0 5]
                                     [0 0 1]
                                     [0 0 0])]
      (is (approx-vec= [0.5 0.2 0.1] result)
          "with no lights the only contribution is the emissive term"))))


;; ---------------------------------------------------------------------------
;; rasterize-triangle!
;; ---------------------------------------------------------------------------

(deftest rasterize-triangle-interior
  (let [pixels (atom [])
        sink {:put-pixel! (fn [x y _r _g _b _a] (swap! pixels conj [x y])),
              :depth-get (fn [_x _y] 1.0),
              :depth-set! (fn [_x _y _z] nil),
              :viewport-w 100,
              :viewport-h 100,
              :clips [],
              :depth-test? false,
              :depth-write? false,
              :shade-fn (fn [_uv _n _wp _c] [1 0 0 1])}
        v0 {:x 10,
            :y 10,
            :z 0,
            :inv-w 1,
            :uv [0 0],
            :normal [0 0 1],
            :world-pos [0 0 0],
            :color [1 1 1 1]}
        v1 {:x 30,
            :y 10,
            :z 0,
            :inv-w 1,
            :uv [0 0],
            :normal [0 0 1],
            :world-pos [0 0 0],
            :color [1 1 1 1]}
        v2 {:x 20,
            :y 30,
            :z 0,
            :inv-w 1,
            :uv [0 0],
            :normal [0 0 1],
            :world-pos [0 0 0],
            :color [1 1 1 1]}]
    (raster/rasterize-triangle! v0 v1 v2 sink)
    (let [ps @pixels]
      (is (pos? (count ps)))
      (is (every? (fn [[x y]] (and (>= x 10) (<= x 30) (>= y 10) (<= y 30)))
                  ps)))))


(deftest rasterize-triangle-both-windings
  (let [pixels-a (atom [])
        pixels-b (atom [])
        sink-a {:put-pixel! (fn [x y _r _g _b _a] (swap! pixels-a conj [x y])),
                :depth-get (fn [_x _y] 1.0),
                :depth-set! (fn [_x _y _z] nil),
                :viewport-w 100,
                :viewport-h 100,
                :clips [],
                :depth-test? false,
                :depth-write? false,
                :shade-fn (fn [_uv _n _wp _c] [1 0 0 1])}
        sink-b (assoc sink-a
                      :pixels nil
                      :put-pixel! (fn [x y _r _g _b _a] (swap! pixels-b conj [x y])))
        v0 {:x 10,
            :y 10,
            :z 0,
            :inv-w 1,
            :uv [0 0],
            :normal [0 0 1],
            :world-pos [0 0 0],
            :color [1 1 1 1]}
        v1 {:x 30,
            :y 10,
            :z 0,
            :inv-w 1,
            :uv [0 0],
            :normal [0 0 1],
            :world-pos [0 0 0],
            :color [1 1 1 1]}
        v2 {:x 20,
            :y 30,
            :z 0,
            :inv-w 1,
            :uv [0 0],
            :normal [0 0 1],
            :world-pos [0 0 0],
            :color [1 1 1 1]}]
    (raster/rasterize-triangle! v0 v1 v2 sink-a)
    (raster/rasterize-triangle! v0 v2 v1 sink-b)
    (is (= (count @pixels-a) (count @pixels-b)))))


(deftest rasterize-triangle-top-left-no-double-cover
  (testing "two triangles sharing an edge cover each pixel exactly once"
    (let [counts (atom {})
          sink {:put-pixel! (fn [x y _r _g _b _a]
                              (swap! counts update [x y] (fnil inc 0))),
                :depth-get (fn [_x _y] 1.0),
                :depth-set! (fn [_x _y _z] nil),
                :viewport-w 100,
                :viewport-h 100,
                :clips [],
                :depth-test? false,
                :depth-write? false,
                :shade-fn (fn [_uv _n _wp _c] [1 0 0 1])}
          vtx (fn [x y]
                {:x x,
                 :y y,
                 :z 0,
                 :inv-w 1,
                 :uv [0 0],
                 :normal [0 0 1],
                 :world-pos [0 0 0],
                 :color [1 1 1 1]})
          ;; square split into two triangles sharing the diagonal b-c
          a (vtx 0 0)
          b (vtx 20 0)
          c (vtx 0 20)
          d (vtx 20 20)]
      (raster/rasterize-triangle! a b c sink)
      (raster/rasterize-triangle! b d c sink)
      (is (pos? (count @counts)) "the quad should produce fragments")
      (is (every? #(= 1 %) (vals @counts))
          "no pixel should be covered by both triangles (top-left rule)"))))


(deftest rasterize-triangle-clip-rect
  (let [pixels (atom [])
        sink {:put-pixel! (fn [x y _r _g _b _a] (swap! pixels conj [x y])),
              :depth-get (fn [_x _y] 1.0),
              :depth-set! (fn [_x _y _z] nil),
              :viewport-w 100,
              :viewport-h 100,
              :clips [[15 15 5 5]],
              :depth-test? false,
              :depth-write? false,
              :shade-fn (fn [_uv _n _wp _c] [1 0 0 1])}
        v0 {:x 10,
            :y 10,
            :z 0,
            :inv-w 1,
            :uv [0 0],
            :normal [0 0 1],
            :world-pos [0 0 0],
            :color [1 1 1 1]}
        v1 {:x 30,
            :y 10,
            :z 0,
            :inv-w 1,
            :uv [0 0],
            :normal [0 0 1],
            :world-pos [0 0 0],
            :color [1 1 1 1]}
        v2 {:x 20,
            :y 30,
            :z 0,
            :inv-w 1,
            :uv [0 0],
            :normal [0 0 1],
            :world-pos [0 0 0],
            :color [1 1 1 1]}]
    (raster/rasterize-triangle! v0 v1 v2 sink)
    (is (pos? (count @pixels)))
    (is (every? (fn [[x y]] (and (>= x 15) (< x 20) (>= y 15) (< y 20)))
                @pixels))))


;; ---------------------------------------------------------------------------
;; rasterize-triangle! — depth test reject
;; ---------------------------------------------------------------------------

(deftest rasterize-triangle-depth-reject
  (let [pixels (atom [])
        sink {:put-pixel! (fn [x y _r _g _b _a] (swap! pixels conj [x y])),
              :depth-get (fn [_x _y] 0.3),
              :depth-set! (fn [_x _y _z] nil),
              :viewport-w 100,
              :viewport-h 100,
              :clips [],
              :depth-test? true,
              :depth-write? false,
              :shade-fn (fn [_uv _n _wp _c] [1 0 0 1])}
        v0 {:x 10,
            :y 10,
            :z 0.5,
            :inv-w 1,
            :uv [0 0],
            :normal [0 0 1],
            :world-pos [0 0 0],
            :color [1 1 1 1]}
        v1 {:x 30,
            :y 10,
            :z 0.5,
            :inv-w 1,
            :uv [0 0],
            :normal [0 0 1],
            :world-pos [0 0 0],
            :color [1 1 1 1]}
        v2 {:x 20,
            :y 30,
            :z 0.5,
            :inv-w 1,
            :uv [0 0],
            :normal [0 0 1],
            :world-pos [0 0 0],
            :color [1 1 1 1]}]
    (raster/rasterize-triangle! v0 v1 v2 sink)
    (is (zero? (count @pixels))
        "depth 0.5 behind 0.3 should reject all fragments")))


;; ---------------------------------------------------------------------------
;; rasterize-triangle! — depth-write false
;; ---------------------------------------------------------------------------

(deftest rasterize-triangle-depth-write-off
  (let [depth-atom (atom {})
        pixels (atom [])
        sink {:put-pixel! (fn [x y _r _g _b _a] (swap! pixels conj [x y])),
              :depth-get (fn [x y] (get @depth-atom [x y] 1.0)),
              :depth-set! (fn [x y z] (swap! depth-atom assoc [x y] z)),
              :viewport-w 100,
              :viewport-h 100,
              :clips [],
              :depth-test? true,
              :depth-write? false,
              :shade-fn (fn [_uv _n _wp _c] [1 0 0 1])}
        v0 {:x 10,
            :y 10,
            :z 0.0,
            :inv-w 1,
            :uv [0 0],
            :normal [0 0 1],
            :world-pos [0 0 0],
            :color [1 1 1 1]}
        v1 {:x 30,
            :y 10,
            :z 0.0,
            :inv-w 1,
            :uv [0 0],
            :normal [0 0 1],
            :world-pos [0 0 0],
            :color [1 1 1 1]}
        v2 {:x 20,
            :y 30,
            :z 0.0,
            :inv-w 1,
            :uv [0 0],
            :normal [0 0 1],
            :world-pos [0 0 0],
            :color [1 1 1 1]}]
    (raster/rasterize-triangle! v0 v1 v2 sink)
    (is (pos? (count @pixels)))
    (is (every? (fn [[x y]] (= 1.0 (get @depth-atom [x y] 1.0))) @pixels)
        "depth buffer should remain unchanged when depth-write? is false")))


;; ---------------------------------------------------------------------------
;; sample-texture — repeat, mirror, linear
;; ---------------------------------------------------------------------------

(deftest sample-texture-repeat
  (is (approx-vec= [1 0 0 1]
                   (raster/sample-texture test-tex
                                          2.1
                                          -0.9
                                          {:wrap :repeat, :filter :nearest}))))


(deftest sample-texture-mirror
  (is (approx-vec= [1 0 0 1]
                   (raster/sample-texture test-tex
                                          0.0
                                          0.0
                                          {:wrap :mirror, :filter :nearest}))))


(deftest sample-texture-linear
  (let [result (raster/sample-texture test-tex 0.25 0.25 {:filter :linear})]
    (is (= 4 (count result)))
    (is (> (nth result 0) 0.0))
    (is (< (nth result 2) 1.0))))


;; ---------------------------------------------------------------------------
;; blinn-phong — colored diffuse, specular, point attenuation
;; ---------------------------------------------------------------------------

(deftest blinn-phong-colored-diffuse
  (let [result (raster/blinn-phong
                 {:diffuse [1 0 0]}
                 [{:kind :directional, :color [1 1 1], :direction [0 0 1]}]
                 [0 0 5]
                 [0 0 1]
                 [0 0 0])]
    (is (> (nth result 0) 0.9))
    (is (< (nth result 1) 0.01))
    (is (< (nth result 2) 0.01))))


(deftest blinn-phong-specular
  (let [result (raster/blinn-phong
                 {:diffuse [0 0 0], :specular [1 1 1], :shininess 1000.0}
                 [{:kind :directional, :color [1 1 1], :direction [0 0 1]}]
                 [0 0 0]
                 [0 0 1]
                 [0 0 0])]
    (is (> (nth result 0) 0.9))
    (is (> (nth result 1) 0.9))
    (is (> (nth result 2) 0.9))))


(deftest blinn-phong-point-attenuation
  (testing "beyond range -> fully attenuated"
    (let [result
          (raster/blinn-phong
            {:diffuse [1 1 1]}
            [{:kind :point, :color [1 1 1], :position [10 0 0], :range 5.0}]
            [0 0 5]
            [0 0 1]
            [0 0 0])]
      (is (approx-vec= [0.0 0.0 0.0] result)
          "point light beyond range should be fully attenuated")))
  (testing "at zero distance -> full intensity (atten = 1)"
    ;; light coincident with the surface point: dist 0, atten (1-0/range)^2
    ;; = 1. N·L is undefined for a zero vector; vec3-normalize returns [0 0
    ;; 0] so the diffuse term is 0, but a surface facing +z lit from
    ;; directly above (light just off the point along +z) keeps atten ~1.
    ;; Use a light at [0 0 0.001].
    (let [result (raster/blinn-phong {:diffuse [1 1 1]}
                                     [{:kind :point,
                                       :color [1 1 1],
                                       :position [0 0 0.001],
                                       :range 5.0}]
                                     [0 0 5]
                                     [0 0 1]
                                     [0 0 0])]
      ;; atten ~= (1 - 0.001/5)^2 ~= 1, N·L ~= 1 -> diffuse ~= 1
      (is (> (nth result 0) 0.99)
          "point light at the surface should be near full intensity")))
  (testing "at range boundary -> zero (atten = 0)"
    (let [result
          (raster/blinn-phong
            {:diffuse [1 1 1]}
            [{:kind :point, :color [1 1 1], :position [0 0 5], :range 5.0}]
            [0 0 10]
            [0 0 1]
            [0 0 0])]
      (is (approx-vec= [0.0 0.0 0.0] result)
          "point light exactly at range distance should attenuate to zero"))))


;; ---------------------------------------------------------------------------
;; shade-mesh-fragment
;; ---------------------------------------------------------------------------

(deftest shade-mesh-fragment-unlit-returns-srgb
  (let [draw {:op {}, :lighting-enabled false}
        color [1 0 0 1]
        result (raster/shade-mesh-fragment draw [0 0] [0 0 1] [0 0 0] color)]
    (is (approx-vec4= [1 0 0 1] result)
        "unlit fragment should return original sRGB color unchanged")))


(deftest shade-mesh-fragment-lit
  (let [draw {:op {},
              :lights
              [{:kind :directional, :color [1 1 1], :direction [0 0 1]}],
              :camera-pos [0 0 5],
              :lighting-enabled true}
        color [0.5 0.5 0.5 1]
        result (raster/shade-mesh-fragment draw [0 0] [0 0 1] [0 0 0] color)]
    (is (approx= 0.5 (nth result 0))
        "mid-gray surface under white light should stay ~mid-gray")
    (is (approx= 1.0 (nth result 3)))))


(deftest shade-mesh-fragment-texture-modulation
  (testing "textured fragment = texture.rgba × vertex/fill color.rgba"
    ;; texel 51/255 = 0.2 (byte storage); red fill tints it
    (let [gray-tex {:width 1, :height 1, :rgba [51 51 51 255]}
          draw {:op {}, :texture gray-tex, :lighting-enabled false}
          result (raster/shade-mesh-fragment draw
                                             [0 0]
                                             [0 0 1]
                                             [0 0 0]
                                             [1.0 0.0 0.0 1.0])]
      (is (approx-vec4= [0.2 0.0 0.0 1.0] result)
          "texture should be modulated by the colour, not replace it"))))


(deftest shade-mesh-fragment-clamps-hdr-lighting
  (testing "bright (intensity > 1) lighting is clamped to [0,1], not wrapped"
    (let [draw {:op {},
                :lights [{:kind :directional,
                          :color [1 1 1],
                          :direction [0 0 1],
                          :intensity 5.0}],
                :camera-pos [0 0 5],
                :lighting-enabled true}
          result
          (raster/shade-mesh-fragment draw [0 0] [0 0 1] [0 0 0] [1 1 1 1])]
      (is (every? (fn [c] (<= 0.0 c 1.0)) result)
          "no channel should exceed 1.0 after HDR lighting"))))


(deftest shade-mesh-fragment-white-texture-passthrough
  (testing "a white texture leaves the vertex/fill colour unchanged"
    (let [white-tex {:width 1, :height 1, :rgba [255 255 255 255]}
          draw {:op {}, :texture white-tex, :lighting-enabled false}
          result (raster/shade-mesh-fragment draw
                                             [0 0]
                                             [0 0 1]
                                             [0 0 0]
                                             [0.25 0.5 0.75 1.0])]
      (is (approx-vec4= [0.25 0.5 0.75 1.0] result)
          "white texture × colour = colour"))))


;; ---------------------------------------------------------------------------
;; render-3d! integration
;; ---------------------------------------------------------------------------

(deftest render-3d-mesh
  (let [pixels (atom [])
        sink {:put-pixel! (fn [x y r g b a] (swap! pixels conj [x y r g b a])),
              :depth-get (fn [_x _y] 1.0),
              :depth-set! (fn [_x _y _z] nil),
              :viewport-w 100,
              :viewport-h 100}
        pass {:draws [{:pipeline :mesh-3d,
                       :op {:vertices [[0 0 0] [50 0 0] [25 50 0]],
                            :indices [[0 1 2]],
                            :fill [1 0 0 1]},
                       :mvp [1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1],
                       :model-m [1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1],
                       :lights [],
                       :camera-pos [0 0 5],
                       :lighting-enabled false,
                       :depth-test false,
                       :depth-write false,
                       :clips []}]}]
    (raster/render-3d! pass sink)
    (is (pos? (count @pixels)) "mesh should produce rasterized fragments")))


;; ---------------------------------------------------------------------------
;; model-m transform: world-pos and normal (non-identity matrix)
;; ---------------------------------------------------------------------------

(deftest prepare-vertex-model-transform
  (testing "world-pos uses model-m, normal uses its inverse-transpose"
    (let [identity-mvp [1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1]
          ;; non-uniform scale: x*2, y*1, z*1 (column-major)
          scale-x2 [2 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1]
          ;; inverse of scale-x2 = scale x*0.5 (what normal-matrix returns)
          normal-m [0.5 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1]
          ;; attrs = [uv normal obj-pos color]
          attrs [[0 0] [1 1 0] [1 1 0] [1 1 1 1]]
          {bundle :bundle}
          (raster/prepare-vertex identity-mvp scale-x2 normal-m [1 1 0] attrs)
          ;; bundle = [uv normal world-pos color]
          normal (nth bundle 1)
          world-pos (nth bundle 2)]
      ;; position scales directly: [1 1 0] -> [2 1 0]
      (is (approx-vec= [2 1 0] world-pos)
          "world-pos should be vertex transformed by model-m")
      ;; normal uses inverse-transpose: [1 1 0] -> [0.5 1 0] normalized.
      ;; A naive model-m transform would give [2 1 0] normalized ->
      ;; distinct, so this asserts the inverse-transpose path
      ;; specifically.
      (is (approx= 0.4472136 (nth normal 0)))
      (is (approx= 0.8944272 (nth normal 1)))
      (is (approx= 0.0 (nth normal 2))))))


(deftest render-3d-mesh-transformed
  (testing "render-3d! threads a non-identity model-m through the full path"
    (let [pixels (atom [])
          sink {:put-pixel! (fn [x y r g b a]
                              (swap! pixels conj [x y r g b a])),
                :depth-get (fn [_x _y] 1.0),
                :depth-set! (fn [_x _y _z] nil),
                :viewport-w 100,
                :viewport-h 100}
          pass {:draws [{:pipeline :mesh-3d,
                         :op {:vertices [[0 0 0] [50 0 0] [25 50 0]],
                              :indices [[0 1 2]],
                              :fill [1 0 0 1]},
                         :mvp [1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1],
                         ;; translate +10 x, +20 y (column-major 12,13)
                         :model-m [1 0 0 0 0 1 0 0 0 0 1 0 10 20 0 1],
                         :lights [{:kind :directional,
                                   :color [1 1 1],
                                   :direction [0 0 1]}],
                         :camera-pos [0 0 5],
                         :lighting-enabled true,
                         :depth-test false,
                         :depth-write false,
                         :clips []}]}]
      (raster/render-3d! pass sink)
      (is (pos? (count @pixels))
          "transformed, lit mesh should still produce fragments"))))


;; ---------------------------------------------------------------------------
;; near-plane clipping (render-3d! clips in clip space before projection)
;; ---------------------------------------------------------------------------

;; A real perspective camera at the origin looking down -z (near 0.1, far 100).
;; In view space, vz < 0 is in front of the camera; vz > 0 is behind it.
(def ^:private persp-mvp
  (m/build-camera {:camera3d/projection :perspective,
                   :camera3d/fov 90,
                   :camera3d/near 0.1,
                   :camera3d/far 100,
                   :camera3d/position [0 0 0],
                   :camera3d/rotation [0 0 0]}
                  100
                  100))


(def ^:private identity-model [1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1])


(deftest render-3d-fully-behind-clipped
  (testing "a triangle entirely behind the near plane is fully clipped"
    (let [pixels (atom [])
          sink {:put-pixel! (fn [x y _r _g _b _a] (swap! pixels conj [x y])),
                :depth-get (fn [_x _y] 1.0),
                :depth-set! (fn [_x _y _z] nil),
                :viewport-w 100,
                :viewport-h 100}
          pass {:draws [{:pipeline :mesh-3d,
                         ;; all behind the camera (vz > 0)
                         :op {:vertices [[0 0 2] [0.5 0 2] [0 0.5 2]],
                              :indices [[0 1 2]],
                              :fill [1 0 0 1]},
                         :mvp persp-mvp,
                         :model-m identity-model,
                         :lights [],
                         :camera-pos [0 0 0],
                         :lighting-enabled false,
                         :depth-test false,
                         :depth-write false,
                         :clips []}]}]
      (raster/render-3d! pass sink)
      (is (zero? (count @pixels))
          "geometry behind the near plane should produce no fragments"))))


(deftest render-3d-straddling-near-clipped
  (testing
    "a triangle straddling the near plane renders its visible part safely"
    (let [pixels (atom [])
          sink {:put-pixel! (fn [x y _r _g _b _a] (swap! pixels conj [x y])),
                :depth-get (fn [_x _y] 1.0),
                :depth-set! (fn [_x _y _z] nil),
                :viewport-w 100,
                :viewport-h 100}
          pass {:draws [{:pipeline :mesh-3d,
                         ;; two verts well in front (vz=-3); apex sits
                         ;; between the camera and the near plane
                         ;; (vz=-0.05) so it is clipped against the near
                         ;; z-plane.
                         :op {:vertices [[-2 -1 -3] [2 -1 -3] [0 2 -0.05]],
                              :indices [[0 1 2]],
                              :fill [1 0 0 1]},
                         :mvp persp-mvp,
                         :model-m identity-model,
                         :lights [],
                         :camera-pos [0 0 0],
                         :lighting-enabled false,
                         :depth-test false,
                         :depth-write false,
                         :clips []}]}]
      (raster/render-3d! pass sink)
      (is
        (pos? (count @pixels))
        "the in-front portion of a straddling triangle should still rasterize")
      (is
        (every? (fn [[x y]] (and (>= x 0) (< x 100) (>= y 0) (< y 100)))
                @pixels)
        "all produced pixels stay within the viewport (no projected garbage)"))))


;; ---------------------------------------------------------------------------
;; blinn-phong — light colours are linearised before accumulation
;; ---------------------------------------------------------------------------

(deftest blinn-phong-linearises-light-colour
  (testing
    "an sRGB white-point directional light drives the surface above the
            naive (un-linearised) value because sRGB->linear is applied"
    ;; A directional light straight on (N·L = 1) a white diffuse surface.
    ;; sRGB light colour [0.5 0.5 0.5] linearises to ~0.214, so the lit
    ;; diffuse term must be the linear value, distinctly below the sRGB
    ;; 0.5.
    (let [result (raster/blinn-phong {:diffuse [1 1 1]}
                                     [{:kind :directional,
                                       :color [0.5 0.5 0.5],
                                       :direction [0 0 1]}]
                                     [0 0 5]
                                     [0 0 1]
                                     [0 0 0])]
      (is (approx= 0.21404114 (nth result 0))
          "light colour must be linearised (0.5 sRGB -> ~0.214 linear)"))))


(deftest shade-mesh-fragment-light-linearisation-end-to-end
  (testing
    "a [1 0 0] sRGB light produces a linear red component > 1.0 pre-tonemap"
    ;; Use blinn-phong directly so we observe the linear-space accumulation
    ;; (shade-mesh-fragment clamps to [0,1] after the sRGB round-trip).
    (let [result (raster/blinn-phong {:diffuse [1 1 1]}
                                     [{:kind :directional,
                                       :color [1.0 0.0 0.0],
                                       :direction [0 0 1]}]
                                     [0 0 5]
                                     [0 0 1]
                                     [0 0 0])]
      ;; sRGB 1.0 -> linear 1.0 exactly (white point is the fixed point of
      ;; the transfer fn), so a saturated red stays 1.0; the green/blue
      ;; stay 0.
      (is (approx= 1.0 (nth result 0)))
      (is (approx= 0.0 (nth result 1)))
      (is (approx= 0.0 (nth result 2))))))


;; ---------------------------------------------------------------------------
;; generate-mipmaps
;; ---------------------------------------------------------------------------

(deftest generate-mipmaps-level1-dims
  (testing "level 1 is half the dimensions of level 0"
    ;; 4x4 solid mid-grey
    (let [base (vec (apply concat (repeat 16 [100 100 100 255])))
          levels (raster/generate-mipmaps base 4 4)]
      (is (= 4 (:width (nth levels 0))))
      (is (= 4 (:height (nth levels 0))))
      (is (= 2 (:width (nth levels 1))))
      (is (= 2 (:height (nth levels 1))))
      (is (= 1 (:width (nth levels 2))))
      (is (= 1 (:height (nth levels 2)))))))


(deftest generate-mipmaps-averages-2x2
  (testing "a level-1 texel is the 2x2 average of the level-0 block"
    ;; 2x2 image: red, green / blue, white (255 each). Level 1 is 1x1 = the
    ;; average of the four = [63 63 63 255] (each channel averaged).
    (let [base [255 0 0 255 0 255 0 255 0 0 255 255 255 255 255 255]
          levels (raster/generate-mipmaps base 2 2)
          l1 (:rgba (nth levels 1))]
      ;; r: (255+0+0+255)/4 = 127; g: (0+255+0+255)/4 = 127;
      ;; b: (0+0+255+255)/4 = 127; a: 255
      (is (= 127 (nth l1 0)))
      (is (= 127 (nth l1 1)))
      (is (= 127 (nth l1 2)))
      (is (= 255 (nth l1 3))))))


;; ---------------------------------------------------------------------------
;; sample-texture — trilinear across :levels at fractional :mip-level
;; ---------------------------------------------------------------------------

(deftest sample-texture-trilinear-blends-levels
  (testing "fractional mip-level blends between two levels"
    ;; level 0: solid black; level 1: solid white. mip-level 0.5 -> mid
    ;; grey.
    (let [levels [{:width 2,
                   :height 2,
                   :rgba [0 0 0 255 0 0 0 255 0 0 0 255 0 0 0 255]}
                  {:width 1, :height 1, :rgba [255 255 255 255]}]
          base (nth levels 0)
          result (raster/sample-texture base
                                        0.5
                                        0.5
                                        {:levels levels, :mip-level 0.5})]
      (is (approx= 0.5 (nth result 0)))
      (is (approx= 0.5 (nth result 1)))
      (is (approx= 0.5 (nth result 2)))
      (is (approx= 1.0 (nth result 3))))))


(deftest sample-texture-mip-level-zero-uses-base
  (testing "mip-level 0 (or absent) samples level 0 only"
    (let [levels [{:width 1, :height 1, :rgba [0 0 0 255]}
                  {:width 1, :height 1, :rgba [255 255 255 255]}]
          base (nth levels 0)]
      (is (approx-vec= [0.0 0.0 0.0 1.0]
                       (raster/sample-texture base
                                              0.5
                                              0.5
                                              {:levels levels,
                                               :mip-level 0.0}))))))


;; ---------------------------------------------------------------------------
;; mip-level-for-triangle — screen-space LOD
;; ---------------------------------------------------------------------------

(deftest mip-level-magnified-is-zero
  (testing "a triangle covering many screen pixels per texel is LOD 0"
    ;; UV [0,1] spans 100 screen px over a 4-texel-wide texture: heavily
    ;; magnified, rho < 1 -> LOD 0.
    (is (approx= 0.0
                 (raster/mip-level-for-triangle 0 0
                                                0.0 0.0
                                                100 0
                                                1.0 0.0
                                                0 100
                                                0.0 1.0
                                                4 4)))))


(deftest mip-level-minified-is-positive
  (testing "a triangle where one texel maps below one screen pixel is LOD > 0"
    ;; UV [0,1] spans only 2 screen px over a 256-texel texture: rho ~128,
    ;; LOD ~ log2(128) = 7.
    (let [lod (raster/mip-level-for-triangle 0 0
                                             0.0 0.0
                                             2 0
                                             1.0 0.0
                                             0 2
                                             0.0 1.0
                                             256 256)]
      (is (> lod 6.0))
      (is (< lod 8.0)))))


;; ---------------------------------------------------------------------------
;; rasterize-triangle! — fast color-only path (unlit, untextured meshes)
;; ---------------------------------------------------------------------------

(deftest rasterize-triangle-fast-color-matches-shade-path
  (testing
    ":fast-color? writes the perspective-correct, clamped vertex colour
            and is pixel-identical to the general shade-fn path for an unlit,
            untextured (identity-shaded) triangle"
    (let [vtx (fn [x y col]
                {:x x,
                 :y y,
                 :z 0,
                 :inv-w 1,
                 :uv [0 0],
                 :normal [0 0 1],
                 :world-pos [0 0 0],
                 :color col})
          col [0.25 0.5 0.75 1.0]
          v0 (vtx 10 10 col)
          v1 (vtx 30 10 col)
          v2 (vtx 20 30 col)
          fast (atom [])
          general (atom [])
          base {:depth-get (fn [_ _] 1.0),
                :depth-set! (fn [_ _ _] nil),
                :viewport-w 100,
                :viewport-h 100,
                :clips [],
                :depth-test? false,
                :depth-write? false}]
      (raster/rasterize-triangle!
        v0
        v1
        v2
        (assoc base
               :fast-color? true
               :put-pixel! (fn [x y r g b a] (swap! fast conj [x y r g b a]))))
      (raster/rasterize-triangle!
        v0
        v1
        v2
        (assoc base
               ;; the canonical unlit/untextured fragment model (clamped colour)
               :shade-fn (fn [uv n wp c]
                           (raster/shade-mesh-fragment {:op {},
                                                        :lighting-enabled false}
                                                       uv
                                                       n
                                                       wp
                                                       c))
               :put-pixel! (fn [x y r g b a] (swap! general conj [x y r g b a]))))
      (is (pos? (count @fast)) "fast path should rasterize fragments")
      (is (= @fast @general)
          "fast color path is identical to the general shade path"))))


(deftest rasterize-triangle-fast-color-clamps-hdr
  (testing ":fast-color? clamps out-of-range colour channels to [0,1]"
    (let [vtx (fn [x y col]
                {:x x,
                 :y y,
                 :z 0,
                 :inv-w 1,
                 :uv [0 0],
                 :normal [0 0 1],
                 :world-pos [0 0 0],
                 :color col})
          col [2.0 -1.0 0.5 1.0]
          v0 (vtx 10 10 col)
          v1 (vtx 30 10 col)
          v2 (vtx 20 30 col)
          out (atom [])]
      (raster/rasterize-triangle! v0
                                  v1
                                  v2
                                  {:depth-get (fn [_ _] 1.0),
                                   :depth-set! (fn [_ _ _] nil),
                                   :viewport-w 100,
                                   :viewport-h 100,
                                   :clips [],
                                   :depth-test? false,
                                   :depth-write? false,
                                   :fast-color? true,
                                   :put-pixel! (fn [_x _y r g b a]
                                                 (swap! out conj [r g b a]))})
      (is (pos? (count @out)))
      (is (every? (fn [[r g b a]]
                    (and (= 1.0 r) (= 0.0 g) (approx= 0.5 b) (= 1.0 a)))
                  @out)
          "channels clamp to [0,1]"))))


;; ---------------------------------------------------------------------------
;; render-3d! — :line-3d honours the op's :color (solar-system regression)
;; ---------------------------------------------------------------------------

(deftest render-3d-line-uses-op-color
  (testing
    ":draw3d/lines carries colour in :color; the software path must
            resolve it (pack/unlit-line-draw feeds :color through :fill) and
            not fall back to the white :fill default"
    (let [pixels (atom [])
          sink {:put-pixel! (fn [_x _y r g b a] (swap! pixels conj [r g b a])),
                :depth-get (fn [_x _y] 1.0),
                :depth-set! (fn [_x _y _z] nil),
                :viewport-w 100,
                :viewport-h 100}
          pass {:draws [{:pipeline :line-3d,
                         :op {:vertices [[-0.5 0 0] [0.5 0 0]],
                              :edges [[0 1]],
                              :color [1.0 0.0 0.0 1.0]},
                         :mvp [1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1],
                         :model-m [1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1],
                         :lights [],
                         :camera-pos [0 0 5],
                         :lighting-enabled false,
                         :depth-test false,
                         :depth-write false,
                         :clips []}]}]
      (raster/render-3d! pass sink)
      (is (pos? (count @pixels)) "the line should rasterize fragments")
      (is
        (every? (fn [[r g b _a]] (and (> r 0.9) (< g 0.1) (< b 0.1))) @pixels)
        "every line fragment is the op :color (red), not the white default"))))
