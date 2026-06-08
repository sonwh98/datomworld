(ns dao.postgraphics.web.canvas-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [dao.postgraphics.lowering :as lower]
    [dao.postgraphics.web.canvas :as canvas]))


;; ---------------------------------------------------------------------------
;; A fake Canvas2D context that records the calls submit-software! makes and
;; hands back a real Uint8ClampedArray-backed ImageData so we can inspect the
;; blitted pixels.
;; ---------------------------------------------------------------------------

(defn- fake-canvas
  [w h]
  (let [calls (atom [])
        record! (fn [& parts] (swap! calls conj (vec parts)))
        last-image (atom nil)
        ctx #js {:createImageData (fn [iw ih]
                                    (let [img #js {:data (js/Uint8ClampedArray.
                                                           (* iw ih 4)),
                                                   :width iw,
                                                   :height ih}]
                                      (reset! last-image img)
                                      img)),
                 :putImageData (fn [img x y] (record! :putImageData img x y)),
                 :save (fn [] (record! :save)),
                 :restore (fn [] (record! :restore)),
                 :translate (fn [x y] (record! :translate x y)),
                 :scale (fn [x y] (record! :scale x y)),
                 :transform (fn [a b c d e f] (record! :transform a b c d e f)),
                 :beginPath (fn [] (record! :beginPath)),
                 :rect (fn [x y w h] (record! :rect x y w h)),
                 :clip (fn [] (record! :clip)),
                 :arc (fn [x y r s e] (record! :arc x y r s e)),
                 :fill (fn [] (record! :fill)),
                 :stroke (fn [] (record! :stroke)),
                 :fillRect (fn [x y w h] (record! :fillRect x y w h)),
                 :strokeRect (fn [x y w h] (record! :strokeRect x y w h)),
                 :fillText (fn [t x y] (record! :fillText t x y)),
                 :moveTo (fn [x y] (record! :moveTo x y)),
                 :lineTo (fn [x y] (record! :lineTo x y)),
                 :quadraticCurveTo (fn [a b c d]
                                     (record! :quadraticCurveTo a b c d)),
                 :bezierCurveTo (fn [a b c d e f]
                                  (record! :bezierCurveTo a b c d e f)),
                 :closePath (fn [] (record! :closePath)),
                 :drawImage (fn [img x y w h] (record! :drawImage img x y w h))}
        canvas #js {:width w, :height h, :getContext (fn [_kind] ctx)}]
    {:canvas canvas, :ctx ctx, :calls calls, :image last-image}))


(defn- call-names
  [calls]
  (set (map first @calls)))


(defn- any-red-pixel?
  "True when any pixel in the ImageData has a non-zero red channel."
  [^js image]
  (let [data (.-data image)
        n (.-length data)]
    (loop [i 0]
      (cond (>= i n) false
            (pos? (aget data i)) true
            :else (recur (+ i 4))))))


(def ^:private identity-mvp [1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1])


;; ---------------------------------------------------------------------------
;; 3D software path: render + blit
;; ---------------------------------------------------------------------------

(deftest submit-software-blits-3d-mesh
  (testing "a red mesh is rasterized into the ImageData and blitted once"
    (let [{:keys [canvas calls image]} (fake-canvas 100 100)
          lowered {:passes [{:draws [{:pipeline :mesh-3d,
                                      :op {:vertices [[0 0 0] [50 0 0]
                                                      [25 50 0]],
                                           :indices [[0 1 2]],
                                           :fill [1 0 0 1]},
                                      :mvp identity-mvp,
                                      :model-m identity-mvp,
                                      :lights [],
                                      :camera-pos [0 0 5],
                                      :lighting-enabled false,
                                      :depth-test false,
                                      :depth-write false,
                                      :clips []}]}]}]
      (canvas/submit-software! canvas lowered)
      (is (contains? (call-names calls) :putImageData)
          "the colour buffer should be blitted exactly via putImageData")
      (is (= 1 (count (filter #(= :putImageData (first %)) @calls)))
          "blit happens once, not per draw")
      (is (any-red-pixel? @image)
          "the rasterized triangle should leave red fragments in the buffer"))))


(deftest submit-software-clears-to-clear-color
  (testing "the :clear color fills the buffer before drawing"
    (let [{:keys [canvas image]} (fake-canvas 4 4)
          lowered {:passes [{:draws [{:pipeline :clear, :color [1 0 0 1]}]}]}]
      (canvas/submit-software! canvas lowered)
      ;; every pixel's red channel = 255 (the clear color), green = 0
      (let [data (.-data @image)]
        (is (= 255 (aget data 0)))
        (is (= 0 (aget data 1)))
        (is (= 255 (aget data 4)))))))


;; ---------------------------------------------------------------------------
;; 2D overlay path
;; ---------------------------------------------------------------------------

(deftest submit-software-paints-2d-fill-rect
  (testing "a :draw-2d fill-rect paints with native fillRect over the blit"
    (let [{:keys [canvas calls]} (fake-canvas 100 100)
          lowered {:passes [{:draws [{:pipeline :draw-2d,
                                      :op {:op/kind :draw/fill-rect,
                                           :rect [10 20 30 40],
                                           :color [0 1 0 1]},
                                      :model-m identity-mvp,
                                      :clips []}]}]}]
      (canvas/submit-software! canvas lowered)
      (let [names (call-names calls)]
        (is (contains? names :putImageData) "3D buffer still blitted first")
        (is (contains? names :fillRect) "2D rect drawn natively")
        (is (contains? names :transform)
            "the lowered model matrix is applied as a 2D affine")))))


(deftest submit-software-paints-path
  (testing "a :draw/path traces segments and fills/strokes"
    (let [{:keys [canvas calls]} (fake-canvas 100 100)
          lowered {:passes [{:draws [{:pipeline :draw-2d,
                                      :op {:op/kind :draw/path,
                                           :segments [[:move-to 0 0]
                                                      [:line-to 10 0]
                                                      [:cubic-to 12 4 14 8 16
                                                       10] [:close]],
                                           :fill [1 0 0 1],
                                           :stroke [0 0 1 1],
                                           :stroke-width 2},
                                      :model-m identity-mvp,
                                      :clips []}]}]}]
      (canvas/submit-software! canvas lowered)
      (let [names (call-names calls)]
        (is (contains? names :moveTo))
        (is (contains? names :lineTo))
        (is (contains? names :bezierCurveTo))
        (is (contains? names :closePath))
        (is (contains? names :fill) "path with :fill is filled")
        (is (contains? names :stroke) "path with :stroke is stroked")))))


(deftest submit-software-paints-image-placeholder
  (testing "a :draw/image placeholder resource paints a white rect"
    (let [{:keys [canvas calls]} (fake-canvas 100 100)
          lowered {:passes [{:draws [{:pipeline :draw-2d,
                                      :op {:op/kind :draw/image,
                                           :rect [5 5 20 20],
                                           :opacity 0.5,
                                           :resource {:resource/kind
                                                      :placeholder}},
                                      :model-m identity-mvp,
                                      :clips []}]}]}]
      (canvas/submit-software! canvas lowered)
      (let [fill-rects (filter #(= :fillRect (first %)) @calls)]
        (is (= 1 (count fill-rects)))
        (is (= [5 5 20 20] (rest (first fill-rects)))
            "placeholder fills the image rect")))))


(deftest submit-software-paints-text
  (testing "a :text draw is painted with fillText at its screen baseline"
    (let [{:keys [canvas calls]} (fake-canvas 100 100)
          lowered {:passes [{:draws [{:pipeline :text,
                                      :text "dao",
                                      :color [1 1 1 1],
                                      :font-size 12,
                                      :screen-x 5,
                                      :screen-y 30,
                                      :clips []}]}]}]
      (canvas/submit-software! canvas lowered)
      (let [text-calls (filter #(= :fillText (first %)) @calls)]
        (is (= 1 (count text-calls)))
        (is (= ["dao" 5 30] (rest (first text-calls)))
            "text drawn at the lowered screen baseline")))))


;; ---------------------------------------------------------------------------
;; Integration: real lower-frame -> submit-software!
;; ---------------------------------------------------------------------------

(deftest submit-software-from-lowered-2d-frame
  (testing "a lowered 2D frame flows through submit-software! end to end"
    (let [{:keys [canvas calls]} (fake-canvas 200 100)
          lowered (lower/lower-frame [{:op/kind :draw/fill-rect,
                                       :rect [10 20 30 40],
                                       :color [1 0 0 1]}]
                                     {:viewport-width 200,
                                      :viewport-height 100,
                                      :supports-render-targets? false,
                                      :supports-image? false})]
      (canvas/submit-software! canvas lowered)
      (is (contains? (call-names calls) :fillRect)
          "lowered :draw-2d reaches the native painter"))))
