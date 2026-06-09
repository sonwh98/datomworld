(ns datomworld.demo.solar-system
  (:require
    [dao.postgraphics.terminal :as terminal]
    [dao.postgraphics.web :as pg]
    [dao.stream :as ds]
    [dao.stream.ringbuffer]
    [datomworld.demo.responsive :as responsive]
    [datomworld.demo.solar-system-scene :as scene]
    [reagent.core :as r]))


(defonce frame-stream
  (ds/open! {:type :ringbuffer, :capacity 4, :eviction-policy :evict-oldest}))


(defonce scene-state (r/atom scene/initial-state))
(defonce interval-id (atom nil))


(defn stop!
  []
  (when-let [id @interval-id]
    (js/clearInterval id)
    (reset! interval-id nil))
  :stopped)


(defn dispose!
  []
  (stop!) :disposed)


(defn- emit-frame!
  []
  (terminal/put-frame! frame-stream (scene/frame-from-state @scene-state)))


(defn start!
  []
  (when-not @interval-id
    (emit-frame!)
    (reset! interval-id (js/setInterval (fn []
                                          (swap! scene-state scene/advance-state
                                                 scene/frame-step-seconds)
                                          (emit-frame!))
                                        scene/frame-interval-ms)))
  :started)


(defn reset-scene!
  []
  (reset! scene-state scene/initial-state)
  (emit-frame!)
  :reset)


(defn- canvas-view
  []
  (r/create-class
    {:display-name "solar-system-postgraphics-widget",
     :component-did-mount (fn [_] (start!) (emit-frame!)),
     :component-will-unmount (fn [_] (dispose!)),
     :reagent-render
     (fn []
       [pg/postgraphics-widget frame-stream :canvas-attrs
        {:style (responsive/canvas-frame-style
                  {:max-width 900,
                   :min-height 300,
                   :height-vh 62,
                   :max-height 720,
                   :border-color "rgba(160,190,255,0.28)",
                   :border-radius 18,
                   :background "#050711",
                   :box-shadow "0 28px 90px rgba(0,0,0,0.48)"})}
        :on-error
        #(js/console.error "postgraphics frame rejected" %)])}))


(defn main-view
  []
  (let [animating? (:animating? @scene-state)]
    [:div
     {:style
      {:min-height "100vh",
       :padding "84px 28px 32px",
       :background
       "radial-gradient(circle at 25% 20%, #13244e 0, #070916 42%, #03040a 100%)",
       :color "#eef4ff",
       :font-family "Avenir Next, ui-sans-serif, sans-serif"}}
     [:div {:style {:max-width "1180px", :margin "0 auto"}}
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
                  :color "#8eb6ff",
                  :font-size "12px"}} "dao.postgraphics.web.gpu"]
        [:h1
         {:style {:font-size "clamp(34px, 5vw, 68px)",
                  :line-height "0.95",
                  :margin "8px 0 0"}} "Solar System"]]
       [:div {:style {:display "flex", :gap "10px", :flex-wrap "wrap"}}
        [:button
         {:on-click #(swap! scene-state update :animating? not),
          :style {:padding "10px 14px",
                  :border-radius "999px",
                  :border "1px solid #5d7cba",
                  :background (if animating? "#d8e6ff" "#111a31"),
                  :color (if animating? "#071020" "#eaf1ff"),
                  :cursor "pointer"}} (if animating? "Pause" "Animate")]
        [:button
         {:on-click reset-scene!,
          :style {:padding "10px 14px",
                  :border-radius "999px",
                  :border "1px solid #5d7cba",
                  :background "#111a31",
                  :color "#eaf1ff",
                  :cursor "pointer"}} "Reset"]]]
      [:div
       {:style {:display "grid",
                :grid-template-columns (responsive/auto-fit-grid 320),
                :gap "24px",
                :align-items "start"}} [canvas-view]
       [:aside
        {:style {:padding "18px",
                 :border "1px solid rgba(160,190,255,0.22)",
                 :border-radius "16px",
                 :background "rgba(7, 12, 28, 0.72)",
                 :line-height "1.55"}}
        [:h2 {:style {:margin "0 0 10px", :font-size "18px"}} "Frame Stream"]
        [:p {:style {:color "#b8c7e8", :margin "0 0 12px"}}
         "The browser and Flutter demos both emit the same shared "
         "postgraphics program from "
         [:code "datomworld.demo.solar-system-scene"]
         ". The browser terminal consumes that stream through "
         [:code "postgraphics-widget"] " and presents it via "
         [:code "dao.postgraphics.web.gpu/submit-webgpu!"] "."]
        [:p {:style {:color "#7f91bd", :font-size "13px", :margin "0"}}
         "This surface renders GPU wireframe line payloads only. The old "
         "canvas 2D line submitter has been removed so the scene contract stays "
         "identical across browser WebGPU and Flutter GPU."]]]]]))
