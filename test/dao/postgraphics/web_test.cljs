(ns dao.postgraphics.web-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [dao.postgraphics.terminal :as terminal]
    [dao.postgraphics.web :as web]
    [dao.stream :as ds]
    [dao.stream.ringbuffer]))


(defn- make-stream
  []
  (ds/open! {:type :ringbuffer, :capacity nil}))


(defn- fake-canvas
  [w h]
  (let [calls (atom [])
        record! (fn [& parts] (swap! calls conj (vec parts)))
        ctx #js {:createImageData (fn [iw ih]
                                    #js {:data (js/Uint8ClampedArray.
                                                 (* iw ih 4)),
                                         :width iw,
                                         :height ih}),
                 :putImageData (fn [img x y] (record! :putImageData img x y)),
                 :save (fn [] (record! :save)),
                 :restore (fn [] (record! :restore)),
                 :translate (fn [x y] (record! :translate x y)),
                 :scale (fn [x y] (record! :scale x y)),
                 :transform (fn [a b c d e f] (record! :transform a b c d e f)),
                 :beginPath (fn [] (record! :beginPath)),
                 :rect (fn [x y w h] (record! :rect x y w h)),
                 :clip (fn [] (record! :clip)),
                 :fillRect (fn [x y w h] (record! :fillRect x y w h))}
        canvas #js {:width w, :height h, :getContext (fn [_kind] ctx)}]
    {:canvas canvas, :calls calls}))


;; ---------------------------------------------------------------------------
;; Backend selection (node has no navigator.gpu -> software)
;; ---------------------------------------------------------------------------

(deftest no-webgpu-in-node
  (is (false? (web/gpu-available?)) "node has no navigator.gpu"))


(deftest chooses-software-backend-without-gpu
  (testing "without WebGPU the dispatcher picks the CPU backend + its opts"
    (let [{:keys [supports-render-targets? supports-image? submit!]}
          (#'web/choose-backend)]
      (is (false? supports-render-targets?)
          "software path cannot render to GPU targets")
      (is (false? supports-image?) "software path has no GPU image upload")
      (is (fn? submit!)))))


;; ---------------------------------------------------------------------------
;; End-to-end: dispatcher binds a stream and renders through the software path
;; ---------------------------------------------------------------------------

(deftest dispatch-renders-frame-through-software
  (testing "a bound frame stream lowers + submits via the software backend"
    (let [{:keys [canvas calls]} (fake-canvas 100 100)
          frames (make-stream)
          handle (#'web/bind-frame-stream! canvas frames {})]
      (terminal/put-frame!
        frames
        [{:op/kind :draw/fill-rect, :rect [10 10 20 20], :color [1 0 0 1]}])
      (let [names (set (map first @calls))]
        (is (contains? names :putImageData) "software backend blits its buffer")
        (is (contains? names :fillRect)
            "the 2D rect reaches the native painter via the dispatcher"))
      (when-let [close! (:close! handle)] (close!)))))
