(ns dao.postgraphics.web.canvas-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [dao.postgraphics.lowering :as lowering]
    [dao.postgraphics.web.canvas :as canvas]))


(defn- call=
  "Predicate matching a recorded call vector by its op keyword. The fake
   context records no-arg calls as bare keywords and arg calls as vectors,
   so guard against seqing a bare keyword."
  [k]
  (fn [entry] (and (vector? entry) (= k (first entry)))))


(defn- fake-canvas
  [width height]
  (let [calls (atom [])
        image-data #js {:data (js/Uint8ClampedArray. (* width height 4)),
                        :width width,
                        :height height}
        ctx
        #js
        {:createImageData (fn [w h] image-data),
         :putImageData (fn [id x y] (swap! calls conj [:putImageData x y])),
         :save (fn [] (reset! calls (conj @calls :save))),
         :restore (fn [] (swap! calls conj :restore)),
         :beginPath (fn [] (swap! calls conj :beginPath)),
         :clip (fn [] (swap! calls conj :clip)),
         :rect (fn [x y w h] (swap! calls conj [:rect x y w h])),
         :fillRect (fn [x y w h] (swap! calls conj [:fillRect x y w h])),
         :strokeRect (fn [x y w h] (swap! calls conj [:strokeRect x y w h])),
         :arc (fn [x y r s e] (swap! calls conj [:arc x y r s e])),
         :fill (fn [] (swap! calls conj :fill)),
         :stroke (fn [] (swap! calls conj :stroke)),
         :moveTo (fn [x y] (swap! calls conj [:moveTo x y])),
         :lineTo (fn [x y] (swap! calls conj [:lineTo x y])),
         :closePath (fn [] (swap! calls conj :closePath)),
         :fillText (fn [text x y] (swap! calls conj [:fillText text x y])),
         :drawImage (fn [& args]
                      (swap! calls conj (into [:drawImage] args))),
         :translate (fn [x y] (swap! calls conj [:translate x y])),
         :scale (fn [x y] (swap! calls conj [:scale x y])),
         :transform (fn [a b c d e f]
                      (swap! calls conj [:transform a b c d e f])),
         :_calls calls}
        canvas #js {:getContext (fn [kind] (when (= kind "2d") ctx)),
                    :getBoundingClientRect
                    (fn [] #js {:width width, :height height}),
                    :width width,
                    :height height,
                    :calls calls,
                    :imageData image-data,
                    :ctx ctx}]
    canvas))


(deftest software-submitter-clears-and-paints-2d-ops
  (let [c (fake-canvas 100 50)
        frame
        [{:op/kind :frame/clear, :color [0.2 0.4 0.6 1.0]}
         {:op/kind :draw/fill-rect, :rect [10 20 30 40], :color [1 0 0 1]}]
        lowered (lowering/lower-frame frame
                                      {:viewport-width 100,
                                       :viewport-height 50})]
    (try (canvas/submit-software! c lowered)
         (catch js/Error e (println "ERROR in submit-software!:" e) (throw e)))
    (let [calls @(.-calls c)]
      (is (some (call= :putImageData) calls))
      ;; The fillRect for the 2D op should appear after putImageData
      (let [put-idx (first (keep-indexed #(when ((call= :putImageData) %2) %1)
                                         calls))
            fill-idx (first (keep-indexed #(when ((call= :fillRect) %2) %1)
                                          calls))]
        (is (some? put-idx))
        (is (some? fill-idx))
        (is (> fill-idx put-idx))))))


(deftest software-submitter-paints-text
  (let [c (fake-canvas 200 100)
        frame [{:op/kind :frame/clear, :color [0 0 0 1]}
               {:op/kind :draw/text,
                :text "hello",
                :position [50 50],
                :font-size 16,
                :color [1 1 1 1]}]
        lowered (lowering/lower-frame frame
                                      {:viewport-width 200,
                                       :viewport-height 100})]
    (canvas/submit-software! c lowered)
    (let [calls @(.-calls c)]
      (is (some (call= :putImageData) calls))
      (is (some #(and (vector? %) (= :fillText (first %)) (= "hello" (nth % 1)))
                calls)))))


(deftest software-submitter-rasters-a-triangle
  (let [c (fake-canvas 64 64)
        frame [{:op/kind :camera3d/set,
                :camera3d/projection :perspective,
                :camera3d/fov 60.0,
                :camera3d/near 0.1,
                :camera3d/far 100.0,
                :camera3d/position [0.0 0.0 3.0],
                :camera3d/rotation [0.0 0.0 0.0]}
               {:op/kind :draw3d/triangles,
                :vertices [[-1.0 -1.0 0.0] [1.0 -1.0 0.0] [0.0 1.0 0.0]],
                :indices [[0 1 2]],
                :fill [1.0 0.0 0.0 1.0]}]
        lowered (lowering/lower-frame frame
                                      {:viewport-width 64,
                                       :viewport-height 64})]
    (canvas/submit-software! c lowered)
    (let [calls @(.-calls c)
          ^js image-data (.-imageData c)
          ^js data (.-data image-data)]
      ;; putImageData should have been called
      (is (some (call= :putImageData) calls))
      ;; Some pixel in the center should be reddish (non-zero red, or at
      ;; least non-clear)
      ;; The clear color is default [0 0 0 1], so any non-zero channel
      ;; means a triangle was drawn.
      (let [center-idx (* (+ (* 32 64) 32) 4)]
        (is (or (> (aget data center-idx) 0)
                (> (aget data (+ center-idx 1)) 0)
                (> (aget data (+ center-idx 2)) 0)))))))


(deftest software-submitter-applies-clip-rects
  (let [c (fake-canvas 100 100)
        frame [{:op/kind :clip/push-rect, :rect [10 10 80 80]}
               {:op/kind :draw/fill-rect, :rect [0 0 100 100], :color [1 1 1 1]}
               {:op/kind :clip/pop}]
        lowered (lowering/lower-frame frame
                                      {:viewport-width 100,
                                       :viewport-height 100})]
    (canvas/submit-software! c lowered)
    (let [calls @(.-calls c)]
      ;; Should see save, clip rect, fillRect, restore sequence
      (is (some #(= :save %) calls))
      (is (some #(= :restore %) calls))
      (is (some #(and (vector? %) (= :rect (first %)) (= 10.0 (nth % 1)))
                calls)))))
