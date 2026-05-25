(ns dao.postgraphics.webgpu-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [dao.postgraphics.terminal :as terminal]
    [dao.postgraphics.webgpu :as webgpu]
    [dao.stream :as ds]
    [dao.stream.ringbuffer]))


(defn- make-stream
  []
  (ds/open! {:type :ringbuffer, :capacity nil}))


(deftest rects-are-lowered-into-browser-screen-space
  (let [lowered (webgpu/lower-frame [{:op/kind :draw/fill-rect,
                                      :rect [10 20 30 40],
                                      :color [1 0 0 1]}]
                                    {:viewport-width 200, :viewport-height 100})
        draw (first (get-in lowered [:passes 0 :draws]))]
    (is (= :solid-2d (:pipeline draw)))
    (is (= [10 40 30 40] (:screen-rect draw)))))


(deftest clips-resolve-through-translate-only-ancestry
  (let [lowered
        (webgpu/lower-frame
          [{:op/kind :transform/push, :translate [5 10]}
           {:op/kind :clip/push-rect, :rect [10 20 30 40]}
           {:op/kind :draw/fill-rect, :rect [0 0 10 10], :color [1 1 1 1]}
           {:op/kind :clip/pop} {:op/kind :transform/pop}]
          {:viewport-width 200, :viewport-height 100})
        draw (first (get-in lowered [:passes 0 :draws]))]
    (is (= [[15 30 30 40]] (:clips draw)))))


(deftest text-anchor-is-transformed-but-glyphs-remain-screen-space
  (let [lowered (webgpu/lower-frame
                  [{:op/kind :transform/push, :translate [3 4], :scale [2 3]}
                   {:op/kind :draw/text,
                    :text "dao",
                    :position [5 6],
                    :font-size 12,
                    :align :start,
                    :color [0 0 0 1]} {:op/kind :transform/pop}]
                  {:viewport-width 200, :viewport-height 100})
        draw (first (get-in lowered [:passes 0 :draws]))]
    (is (= :text (:pipeline draw)))
    (is (= [13 78] (:screen-position draw)))
    (is (= false (:glyphs-transformed? draw)))))


(deftest render-targets-produce-addressable-passes
  (let [lowered (webgpu/lower-frame [{:op/kind :target/push,
                                      :target/id :shadow,
                                      :target/size [64 64],
                                      :target/format :rgba8unorm}
                                     {:op/kind :frame/clear, :color [0 0 0 1]}
                                     {:op/kind :target/pop}
                                     {:op/kind :draw/image,
                                      :image/source [:target/id :shadow],
                                      :rect [0 0 16 16]}]
                                    {:viewport-width 128,
                                     :viewport-height 128,
                                     :resolve-resource
                                     (fn [source _state]
                                       (when (= source [:target/id :shadow])
                                         {:resource/kind :target-texture,
                                          :target/id :shadow}))})]
    (is (= [:default :shadow] (mapv :target-id (:passes lowered))))
    (is (= :clear (get-in lowered [:passes 1 :draws 0 :pipeline])))
    (is (= :texture-2d (get-in lowered [:passes 0 :draws 0 :pipeline])))))


(deftest invalid-target-sampling-is-rejected-canonically
  (testing "missing produced target"
    (is (= :validation-failure
           (try (webgpu/validate-frame! [{:op/kind :draw/image,
                                          :image/source [:target/id :missing],
                                          :rect [0 0 10 10]}])
                nil
                (catch js/Error e (terminal/rejection-reason e))))))
  (testing "self-sampling active target"
    (is (= :validation-failure
           (try (webgpu/validate-frame! [{:op/kind :target/push,
                                          :target/id :shadow,
                                          :target/size [64 64],
                                          :target/format :rgba8unorm}
                                         {:op/kind :draw/image,
                                          :image/source [:target/id :shadow],
                                          :rect [0 0 10 10]}
                                         {:op/kind :target/pop}])
                nil
                (catch js/Error e (terminal/rejection-reason e)))))))


(deftest unresolved-image-resources-are-unloadable
  (is (= :unloadable-image
         (try (webgpu/lower-frame [{:op/kind :draw/image,
                                    :image/source :asset/not-ready,
                                    :rect [0 0 10 10]}]
                                  {:viewport-width 100, :viewport-height 100})
              nil
              (catch js/Error e (terminal/rejection-reason e))))))


(deftest postgraphics-widget-binding-submits-lowered-frames
  (let [frames (make-stream)
        signals (make-stream)
        submissions (atom [])
        errors (atom [])
        handle (webgpu/frame-stream-binding-test-hook
                 nil
                 frames
                 {:viewport-size (fn [] [100 50]),
                  :signal-stream signals,
                  :generation-id "webgpu-test",
                  :on-error #(swap! errors conj %),
                  :submit! (fn [_canvas lowered]
                             (swap! submissions conj lowered))})]
    (terminal/put-frame!
      frames
      [{:op/kind :draw/fill-rect, :rect [0 0 10 10], :color [1 1 1 1]}])
    (terminal/put-frame! frames [{:op/kind :draw/fill-rect, :rect [0 0 -1 1]}])
    (is (= "webgpu-test" (:generation-id handle)))
    (is (= 1 (count @submissions)))
    (is (= [0 40 10 10]
           (get-in @submissions [0 :passes 0 :draws 0 :screen-rect])))
    (is (= 1 (count @errors)))
    (is (= :dao.terminal/reset
           (:message/kind (:ok (ds/next signals {:position 0})))))
    (is (= {:message/kind :dao.terminal/rejection,
            :submission-id 1,
            :reason :validation-failure}
           (:ok (ds/next signals {:position 1}))))))
