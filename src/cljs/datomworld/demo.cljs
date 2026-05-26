(ns datomworld.demo
  (:require
    [datomworld.demo.compilation-pipeline :as pipeline]
    [datomworld.demo.continuation-stream :as cont-demo]
    [datomworld.demo.earth-moon :as earth-moon-demo]
    [datomworld.demo.equation-plotter :as plotter-demo]
    [datomworld.demo.responsive :as responsive]
    [datomworld.demo.solar-system :as solar-demo]
    [datomworld.demo.voxel :as voxel-demo]
    [datomworld.demo.yin-repl :as yin-repl-demo]
    [reagent.core :as r]
    [reagent.dom :as rdom]
    [yin.vm.telemetry-viewer :as tv]))


(def demo-options
  [{:id :yin-repl,
    :label "Yin REPL",
    :icon "λ",
    :desc "Browser CodeMirror client for a remote Yin REPL over WebSockets."}
   {:id :continuation,
    :label "Continuation Example",
    :icon "⤱",
    :desc
    "Step through a single continuation executed by two different VM backends: Register and Stack."}
   {:id :pipeline,
    :label "Pipeline Compilation",
    :icon "⚙",
    :desc "Visualize the Yin compilation pipeline from Source to Bytecode."}
   {:id :plotter,
    :label "Equation Plotter",
    :icon "📈",
    :desc
    "Math equation plotter demonstrating FFI from Yin.VM to functions implemented in ClojureScript"}
   {:id :telemetry,
    :label "VM Telemetry Viewer",
    :icon "📡",
    :desc "Live telemetry and REPL for running VMs."}
   {:id :solar-system,
    :label "Solar System",
    :icon "☉",
    :desc "Postgraphics solar system ported to the browser WebGPU terminal."}
   {:id :earth-moon,
    :label "Earth and Moon",
    :icon "◐",
    :desc "3D postgraphics Earth/Moon scene using mesh and line ops."}
   {:id :voxel,
    :label "Voxel",
    :icon "▣",
    :desc
    "First-person voxel chunk: WASD/arrows to fly through the same postgraphics frame program rendered on both Flutter GPU and browser canvas."}])


(defn- hash->demo
  [hash-value]
  (case hash-value
    "#yin-repl" :yin-repl
    "#pipeline" :pipeline
    "#plotter" :plotter
    "#continuation" :continuation
    "#telemetry" :telemetry
    "#solar-system" :solar-system
    "#earth-moon" :earth-moon
    "#voxel" :voxel
    :home))


(defn- demo->hash
  [demo-id]
  (case demo-id
    :yin-repl "#yin-repl"
    :pipeline "#pipeline"
    :plotter "#plotter"
    :continuation "#continuation"
    :telemetry "#telemetry"
    :solar-system "#solar-system"
    :earth-moon "#earth-moon"
    :voxel "#voxel"
    "#home"))


(defonce demo-shell-state
  (r/atom {:selected-demo (hash->demo (.-hash js/location))}))


(defn sync-demo-from-hash!
  [& _]
  (let [selected-demo (hash->demo (.-hash js/location))]
    (when (not= selected-demo (:selected-demo @demo-shell-state))
      (swap! demo-shell-state assoc :selected-demo selected-demo))))


(defn- dispose-demo!
  [demo-id]
  (case demo-id
    :solar-system (solar-demo/dispose!)
    :earth-moon (earth-moon-demo/dispose!)
    :voxel (voxel-demo/dispose!)
    nil))


(defn select-demo!
  [demo-id]
  (let [current-demo (:selected-demo @demo-shell-state)]
    (when (not= current-demo demo-id)
      (dispose-demo! current-demo)
      (r/flush)
      (set! (.-hash js/location) (demo->hash demo-id))
      (sync-demo-from-hash!))))


(defn home-view
  []
  [:div
   {:style {:min-height "100vh",
            :background
            "radial-gradient(circle at top, #13244e 0%, #070916 52%, #04050d 100%)",
            :color "#f1f5ff",
            :padding "72px 20px 40px",
            :box-sizing "border-box"}}
   [:div
    {:style {:width "min(1180px, 100%)",
             :margin "0 auto",
             :display "flex",
             :flex-direction "column",
             :gap "28px"}}
    [:div
     {:style {:max-width "760px"}}
     [:div {:style {:color "#8eb6ff",
                    :font-size "12px",
                    :font-weight "700",
                    :letter-spacing "0.16em",
                    :text-transform "uppercase",
                    :margin-bottom "12px"}}
      "Interactive stream frontends"]
     [:h1 {:style {:font-size "clamp(2.4rem, 6vw, 4.8rem)",
                   :line-height "0.94",
                   :margin "0 0 12px"}}
      [:a
       {:href "https://datom.world",
        :style {:color "inherit", :text-decoration "none"}} "Datom.world"]
      " Demos"]
     [:p {:style {:margin 0,
                  :color "#b8c7e8",
                  :font-size "16px",
                  :line-height "1.65"}}
      "Each demo must work as a readable small-screen stream boundary and as a spacious large-screen instrument panel. Pick a surface and the layout adapts around the same runtime data."]]
    [:div
     {:style {:display "grid",
              :grid-template-columns (responsive/auto-fit-grid 260),
              :gap "20px"}}
     (for [{:keys [id label icon desc]} demo-options]
       ^{:key id}
       [:div
        {:on-click #(select-demo! id),
         :style {:min-height "220px",
                 :padding "24px",
                 :background "rgba(10, 16, 34, 0.82)",
                 :border "1px solid #2d3b55",
                 :border-radius "18px",
                 :cursor "pointer",
                 :transition "transform 0.2s, border-color 0.2s",
                 :display "flex",
                 :flex-direction "column",
                 :gap "14px",
                 :justify-content "space-between"},
         :on-mouse-over
         (fn [e]
           (set! (.. e -currentTarget -style -borderColor) "#58a6ff")
           (set! (.. e -currentTarget -style -transform) "translateY(-5px)")),
         :on-mouse-out (fn [e]
                         (set! (.. e -currentTarget -style -borderColor)
                               "#2d3b55")
                         (set! (.. e -currentTarget -style -transform) "none"))}
        [:div {:style {:font-size "3rem"}} icon]
        [:div
         [:h2 {:style {:margin "0 0 10px", :font-size "1.35rem"}} label]
         [:p {:style {:margin 0, :color "#8b949e", :line-height "1.6"}} desc]]])]]])


(defn root-shell
  []
  (let [selected-demo (:selected-demo @demo-shell-state)]
    [:<>
     (case selected-demo
       :yin-repl [yin-repl-demo/main-view]
       :pipeline [pipeline/main-view]
       :plotter [plotter-demo/main-view]
       :continuation [cont-demo/main-view]
       :telemetry [tv/main-panel]
       :solar-system [solar-demo/main-view]
       :earth-moon [earth-moon-demo/main-view]
       :voxel [voxel-demo/main-view]
       [home-view])
     (when (not= selected-demo :home)
       [:div
        {:style {:position "fixed",
                 :top "16px",
                 :left "50%",
                 :transform "translateX(-50%)",
                 :z-index "1000",
                 :display "flex",
                 :align-items "center",
                 :justify-content "center",
                 :flex-wrap "wrap",
                 :gap "10px",
                 :width "min(960px, calc(100vw - 24px))",
                 :padding "10px 12px",
                 :border "1px solid rgba(88, 166, 255, 0.18)",
                 :border-radius "18px",
                 :background "rgba(6, 8, 23, 0.84)",
                 :backdrop-filter "blur(12px)",
                 :box-sizing "border-box"}}
        [:a
         {:href "https://datom.world",
          :style {:color "#f1f5ff",
                  :text-decoration "none",
                  :font-weight "bold",
                  :font-size "14px",
                  :margin-right "8px"}} "Datom.world"]
        [:button
         {:on-click #(select-demo! :home),
          :style {:background "#151b33",
                  :color "#f1f5ff",
                  :border "1px solid #2d3b55",
                  :border-radius "999px",
                  :padding "8px 12px",
                  :cursor "pointer",
                  :font-size "13px"}} "🏠 Back to Demos"]
        (when (= selected-demo :pipeline)
          [:<>
           [:button
            {:on-click #(pipeline/show-explainer-video!),
             :style {:background "#1f6feb",
                     :color "#f1f5ff",
                     :border "1px solid #2d3b55",
                     :border-radius "999px",
                     :padding "8px 12px",
                     :cursor "pointer",
                     :font-size "13px"}} "Explainer Video"]
           [pipeline/layout-controls pipeline/app-state]])])]))


(defn mount-root!
  [& _]
  (let [app (js/document.getElementById "app")]
    (when app (rdom/render [root-shell] app))))


(defonce hash-listener-installed? (atom false))


(defn init
  []
  (when-not @hash-listener-installed?
    (js/window.addEventListener "hashchange" sync-demo-from-hash!)
    (reset! hash-listener-installed? true))
  (sync-demo-from-hash!)
  (mount-root!))
