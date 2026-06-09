(ns datomworld.demo.earth-moon
  (:require
    [dao.postgraphics.web :as pg]
    [datomworld.demo.earth-moon-runner :as runner]
    [datomworld.demo.earth-moon-scene :as scene]
    [datomworld.demo.responsive :as responsive]
    [reagent.core :as r]))


(defonce ^:private interval-id (atom nil))


(defn- load-image-texture!
  [setter! asset-path]
  (let [img (js/Image.)]
    (set! (.-crossOrigin img) "anonymous")
    (set! (.-onload img)
          (fn []
            (setter! {:image img,
                      :width (.-naturalWidth img),
                      :height (.-naturalHeight img),
                      :source-id asset-path})))
    (set! (.-src img) asset-path)))


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
            b (runner/ring-darkness v)
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


(defn- load-ring-texture!
  []
  (runner/set-ring-texture! (make-ring-canvas 4 256)))


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
  (load-image-texture! runner/set-earth-texture!
                       "/assets/images/earth_land_ocean_ice_1024.jpg")
  (load-image-texture! runner/set-moon-texture!
                       "/assets/images/moon_texture_512.jpg")
  (load-ring-texture!)
  (when-not @interval-id
    (runner/reset-scene! (js/Date.now))
    (reset! interval-id (js/setInterval #(runner/tick! (js/Date.now))
                                        scene/frame-interval-ms)))
  :started)


(defn- toggle-animation!
  []
  (runner/toggle-animation! (js/Date.now)))


(defn- reset-scene!
  []
  (runner/reset-scene! (js/Date.now)))


(defn- canvas-view
  []
  (r/create-class
    {:display-name "earth-moon-postgraphics-widget",
     :component-did-mount (fn [_] (start!) (runner/tick! (js/Date.now))),
     :component-will-unmount (fn [_] (dispose!)),
     :reagent-render
     (fn []
       [pg/postgraphics-widget runner/frame-stream :canvas-attrs
        {:style (responsive/canvas-frame-style
                  {:max-width 860,
                   :min-height 300,
                   :height-vh 60,
                   :max-height 720,
                   :border-color "rgba(210,220,255,0.24)",
                   :border-radius 22,
                   :background "#040612",
                   :box-shadow "0 30px 100px rgba(0,0,0,0.55)"})}
        :resolve-resource (fn [source _state] source) :on-error
        #(js/console.error "earth/moon frame rejected" %)])}))


(defn main-view
  []
  (let [{:keys [animating?]} @runner/scene-state]
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
                :flex-wrap "wrap",
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
       [:div {:style {:display "flex", :gap "10px", :flex-wrap "wrap"}}
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
                :grid-template-columns (responsive/auto-fit-grid 320),
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
         " spheres from a shared " [:code "datomworld.demo.earth-moon-runner"]
         " (frame stream, scene state, animation gating) plus "
         [:code "earth-moon-scene"] " (geometry). The browser side adds "
         "image-texture loading and the interval timer; the Flutter side "
         "adds asset-bundle texture loading and a Dart Timer."]
        [:p {:style {:color "#8391b7", :font-size "13px", :margin "0"}}
         "The browser renderer submits the lowered mesh frame through WebGPU "
         "with depth testing and textured fragments."]]]]]))
