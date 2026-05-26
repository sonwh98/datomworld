(ns datomworld.demo.earth-moon
  (:require
    [dao.postgraphics.terminal :as terminal]
    [dao.postgraphics.webgpu :as pg]
    [dao.stream :as ds]
    [dao.stream.ringbuffer]
    [datomworld.demo.earth-moon-scene :as scene]
    [reagent.core :as r]))


(defonce frame-stream
  (ds/open! {:type :ringbuffer, :capacity 4, :eviction-policy :evict-oldest}))


(defonce scene-state (r/atom {:seconds 0.0, :animating? true}))


(defonce interval-id (atom nil))
(defonce started-at-ms* (atom nil))
(defonce earth-texture* (atom nil))
(defonce moon-texture* (atom nil))
(defonce ring-texture* (atom nil))


(defn- load-image-texture!
  [tex-atom asset-path]
  (when (nil? @tex-atom)
    (let [img (js/Image.)]
      (set! (.-crossOrigin img) "anonymous")
      (set! (.-onload img)
            (fn []
              (reset! tex-atom {:image img,
                                :width (.-naturalWidth img),
                                :height (.-naturalHeight img),
                                :source-id asset-path})))
      (set! (.-src img) asset-path))))


(defn- ring-darkness
  [v]
  (let [edge-fade (* 4.0 v (- 1.0 v))
        body (+ 0.55 (* 0.45 edge-fade))
        gap (cond (< (js/Math.abs (- v 0.18)) 0.025) 0.45
                  (< (js/Math.abs (- v 0.50)) 0.035) 0.30
                  (< (js/Math.abs (- v 0.78)) 0.020) 0.55
                  :else 1.0)]
    (* body gap)))


(defn- make-ring-canvas
  [width height]
  (let [canvas (.createElement js/document "canvas")
        _ (set! (.-width canvas) width)
        _ (set! (.-height canvas) height)
        ctx (.getContext canvas "2d")
        image-data (.createImageData ctx width height)
        data (.-data image-data)]
    (dotimes [y height]
      (let [v (/ y (max 1 (dec height)))
            b (ring-darkness v)
            r (int (* 255 0.92 b))
            g (int (* 255 0.84 b))
            blue (int (* 255 0.66 b))]
        (dotimes [x width]
          (let [offset (* 4 (+ (* y width) x))]
            (aset data offset r)
            (aset data (+ offset 1) g)
            (aset data (+ offset 2) blue)
            (aset data (+ offset 3) 255)))))
    (.putImageData ctx image-data 0 0)
    {:image canvas, :width width, :height height, :source-id :ring-texture}))


(defn- ensure-ring-texture!
  []
  (when (nil? @ring-texture*) (reset! ring-texture* (make-ring-canvas 4 256))))


(defn- current-textures
  []
  {:earth-tex @earth-texture*,
   :moon-tex @moon-texture*,
   :ring-tex @ring-texture*})


(defn stop!
  []
  (when-let [id @interval-id]
    (js/clearInterval id)
    (reset! interval-id nil))
  :stopped)


(defn dispose!
  []
  (stop!) :disposed)


(defn start!
  []
  (load-image-texture! earth-texture*
                       "/assets/images/earth_land_ocean_ice_1024.jpg")
  (load-image-texture! moon-texture* "/assets/images/moon_texture_512.jpg")
  (ensure-ring-texture!)
  (when-not @interval-id
    (reset! started-at-ms* (js/Date.now))
    (reset! interval-id (js/setInterval
                          (fn []
                            (let [elapsed-ms (- (js/Date.now) @started-at-ms*)
                                  seconds (if (:animating? @scene-state)
                                            (* (/ scene/frame-step-seconds
                                                  scene/frame-interval-ms)
                                               elapsed-ms)
                                            (:seconds @scene-state))]
                              (swap! scene-state assoc :seconds seconds)
                              (terminal/put-frame! frame-stream
                                                   (scene/frame-from-seconds
                                                     seconds
                                                     (current-textures)))))
                          scene/frame-interval-ms)))
  :started)


(defn- toggle-animation!
  []
  (let [animating? (:animating? @scene-state)]
    (swap! scene-state update :animating? not)
    (when-not animating?
      (let [seconds (:seconds @scene-state)
            elapsed-ms (* seconds
                          (/ scene/frame-interval-ms scene/frame-step-seconds))]
        (reset! started-at-ms* (- (js/Date.now) elapsed-ms))))))


(defn reset-scene!
  []
  (reset! started-at-ms* (js/Date.now))
  (reset! scene-state {:seconds 0.0, :animating? true})
  (terminal/put-frame! frame-stream
                       (scene/frame-from-seconds 0.0 (current-textures))))


(defn- canvas-view
  []
  (r/create-class
    {:display-name "earth-moon-postgraphics-widget",
     :component-did-mount (fn [_]
                            (start!)
                            (terminal/put-frame! frame-stream
                                                 (scene/frame-from-seconds
                                                   (:seconds @scene-state)
                                                   (current-textures)))),
     :component-will-unmount (fn [_] (dispose!)),
     :reagent-render (fn []
                       [pg/postgraphics-widget frame-stream :canvas-attrs
                        {:style {:width "min(78vw, 860px)",
                                 :height "min(76vh, 720px)",
                                 :display "block",
                                 :border "1px solid rgba(210,220,255,0.24)",
                                 :border-radius "22px",
                                 :background "#040612",
                                 :box-shadow "0 30px 100px rgba(0,0,0,0.55)"}}
                        :resolve-resource (fn [source _state] source) :on-error
                        #(js/console.error "earth/moon frame rejected" %)])}))


(defn main-view
  []
  (let [{:keys [animating?]} @scene-state]
    [:div
     {:style
      {:min-height "100vh",
       :padding "84px 28px 32px",
       :background
       "radial-gradient(circle at 70% 18%, #2a234d 0, #091123 38%, #03050d 100%)",
       :color "#edf5ff",
       :font-family "Avenir Next, ui-sans-serif, sans-serif"}}
     [:div {:style {:max-width "1160px", :margin "0 auto"}}
      [:div
       {:style {:display "flex",
                :justify-content "space-between",
                :gap "24px",
                :align-items "end",
                :margin-bottom "22px"}}
       [:div
        [:div
         {:style {:letter-spacing "0.18em",
                  :text-transform "uppercase",
                  :color "#b6c7ff",
                  :font-size "12px"}} "dao.postgraphics 3d ops"]
        [:h1
         {:style {:font-size "clamp(34px, 5vw, 64px)",
                  :line-height "0.95",
                  :margin "8px 0 0"}} "Earth and Moon"]]
       [:div {:style {:display "flex", :gap "10px"}}
        [:button
         {:on-click toggle-animation!,
          :style {:padding "10px 14px",
                  :border-radius "999px",
                  :border "1px solid #7186c7",
                  :background (if animating? "#e3e9ff" "#111a31"),
                  :color (if animating? "#081020" "#edf5ff"),
                  :cursor "pointer"}} (if animating? "Pause" "Animate")]
        [:button
         {:on-click reset-scene!,
          :style {:padding "10px 14px",
                  :border-radius "999px",
                  :border "1px solid #7186c7",
                  :background "#111a31",
                  :color "#edf5ff",
                  :cursor "pointer"}} "Reset"]]]
      [:div
       {:style {:display "grid",
                :grid-template-columns "minmax(0, 1fr) 300px",
                :gap "24px",
                :align-items "start"}} [canvas-view]
       [:aside
        {:style {:padding "18px",
                 :border "1px solid rgba(210,220,255,0.22)",
                 :border-radius "16px",
                 :background "rgba(7, 12, 28, 0.74)",
                 :line-height "1.55"}}
        [:h2 {:style {:margin "0 0 10px", :font-size "18px"}}
         "Postgraphics Program"]
        [:p {:style {:color "#c2cee8", :margin "0 0 12px"}}
         "Each tick emits a complete frame with " [:code ":camera3d/set"]
         ", lighting state, and two " [:code ":draw3d/mesh"]
         " spheres from the shared Clojure/ClojureDart producer."]
        [:p {:style {:color "#8391b7", :font-size "13px", :margin "0"}}
         "This is a 3D postgraphics producer. The browser renderer now submits the lowered mesh frame through WebGPU with depth testing and textured fragments."]]]]]))
