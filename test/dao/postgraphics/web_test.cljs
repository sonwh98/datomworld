(ns dao.postgraphics.web-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [dao.postgraphics.terminal :as terminal]
            [dao.postgraphics.web :as web]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as rb]))


(defn- make-stream
  ([] (make-stream 64))
  ([capacity]
   (:dao.stream/handle (rb/create! {:dao.stream/type rb/transport-type,
                                    rb/capacity-key capacity}))))


(defn- values
  "Every value retained on handle, oldest first."
  [handle]
  (loop [cursor (:dao.stream/cursor (stream/cursor handle :dao.stream/oldest))
         acc []]
    (let [read (stream/next handle cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome read))
        (recur (:dao.stream/cursor read) (conj acc (:dao.stream/value read)))
        acc))))


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
      (is (empty? @calls) "put-frame! wakes nothing; the host tick presents")
      (is (= :blocked (#'web/tick! handle)))
      (let [names (set (map first @calls))]
        (is (contains? names :putImageData) "software backend blits its buffer")
        (is (contains? names :fillRect)
            "the 2D rect reaches the native painter via the dispatcher"))
      (swap! (:binding* handle) terminal/close)
      (is (= :closed (#'web/tick! handle))))))


(deftest binding-lowers-valid-frames-and-signals-rejections
  (testing
    "the dispatcher binding lowers valid frames to the backend submit!
            and emits reset/rejection signals (backend overridden to capture)"
    (let [frames (make-stream)
          signals (make-stream)
          submissions (atom [])
          errors (atom [])
          handle (#'web/bind-frame-stream!
                  nil
                  frames
                  {:viewport-size (fn [] [100 50]),
                   :signal-stream signals,
                   :generation-id "web-test",
                   :on-error #(swap! errors conj %),
                   :backend {:submit! (fn [_canvas lowered]
                                        (swap! submissions conj lowered)),
                             :supports-render-targets? true,
                             :supports-image? true}})]
      (terminal/put-frame!
        frames
        [{:op/kind :draw/fill-rect, :rect [0 0 10 10], :color [1 1 1 1]}])
      (terminal/put-frame! frames
                           [{:op/kind :draw/fill-rect, :rect [0 0 -1 1]}])
      (is (empty? @submissions) "nothing is presented before the host ticks")
      (is (= :blocked (#'web/tick! handle)))
      (is (= "web-test" (:generation-id handle)))
      (is (= 1 (count @submissions)) "only the valid frame is submitted")
      (is (= :draw-2d (get-in @submissions [0 :passes 0 :draws 0 :pipeline])))
      (is (= 1 (count @errors)) "the invalid frame triggers on-error")
      (is (= [{:message/kind :dao.terminal/reset, :generation-id "web-test"}
              {:message/kind :dao.terminal/rejection,
               :submission-id 1,
               :reason :validation-failure}]
             (values signals))))))


(deftest binding-mints-at-newest-and-signals-gaps
  (testing "a frame put before binding is not presented; an eviction the
            cursor spans yields a frame-skipped signal, then presents"
    (let [frames (make-stream 1)
          signals (make-stream)
          submissions (atom [])
          frame [{:op/kind :draw/fill-rect, :rect [0 0 10 10], :color [1 1 1 1]}]
          _ (terminal/put-frame! frames frame)
          handle (#'web/bind-frame-stream!
                  nil
                  frames
                  {:viewport-size (fn [] [100 50]),
                   :signal-stream signals,
                   :backend {:submit! (fn [_canvas lowered]
                                        (swap! submissions conj lowered)),
                             :supports-render-targets? true,
                             :supports-image? true}})]
      (is (= :blocked (#'web/tick! handle)))
      (is (empty? @submissions) "the frame put before bind is not presented")
      (terminal/put-frame! frames frame)
      (terminal/put-frame! frames frame)
      (is (= :blocked (#'web/tick! handle)))
      (is (= 1 (count @submissions)))
      (is (= [:dao.terminal/reset :dao.terminal/frame-skipped]
             (map :message/kind (values signals)))))))
