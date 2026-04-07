(ns datomworld.demo
  (:require
    [datomworld.demo.compilation-pipeline :as pipeline]
    [datomworld.demo.continuation-stream :as cont-demo]
    [datomworld.demo.equation-plotter :as plotter-demo]
    [reagent.core :as r]
    [reagent.dom :as rdom]
    [yin.vm.telemetry-viewer :as tv]))


(def demo-options
  [{:id :continuation,
    :label "Continuation Example",
    :icon "⤱",
    :desc "Step through VM execution with explicit continuations."}
   {:id :pipeline,
    :label "Pipeline Compilation",
    :icon "⚙",
    :desc "Visualize the Yin compilation pipeline from Source to Bytecode."}
   {:id :plotter,
    :label "Equation Plotter",
    :icon "📈",
    :desc "Interactive math equation plotter using the Yin engine."}
   {:id :telemetry,
    :label "VM Telemetry Viewer",
    :icon "📡",
    :desc "Live telemetry and REPL for running VMs."}])


(defn- hash->demo
  [hash-value]
  (case hash-value
    "#pipeline" :pipeline
    "#plotter" :plotter
    "#continuation" :continuation
    "#telemetry" :telemetry
    :home))


(defn- demo->hash
  [demo-id]
  (case demo-id
    :pipeline "#pipeline"
    :plotter "#plotter"
    :continuation "#continuation"
    :telemetry "#telemetry"
    "#home"))


(defonce demo-shell-state
  (r/atom {:selected-demo (hash->demo (.-hash js/location))}))


(defn sync-demo-from-hash!
  [& _]
  (swap! demo-shell-state assoc
         :selected-demo (hash->demo (.-hash js/location))))


(defn select-demo!
  [demo-id]
  (swap! demo-shell-state assoc :selected-demo demo-id)
  (set! (.-hash js/location) (demo->hash demo-id)))


(defn home-view
  []
  [:div
   {:style {:display "flex",
            :flex-direction "column",
            :align-items "center",
            :justify-content "center",
            :height "100vh",
            :background "#060817",
            :color "#f1f5ff"}}
   [:h1 {:style {:font-size "3rem", :margin-bottom "4rem"}}
    [:a {:href "https://datom.world", :style {:color "inherit", :text-decoration "none"}} "Datom.world"]
    " Demos"]
   [:div
    {:style {:display "flex", :gap "30px", :flex-wrap "wrap", :justify-content "center"}}
    (for [{:keys [id label icon desc]} demo-options]
      ^{:key id}
      [:div
       {:on-click #(select-demo! id),
        :style {:width "300px",
                :padding "30px",
                :background "#151b33",
                :border "1px solid #2d3b55",
                :border-radius "12px",
                :cursor "pointer",
                :transition "transform 0.2s, border-color 0.2s",
                :display "flex",
                :flex-direction "column",
                :align-items "center",
                :text-align "center"},
        :on-mouse-over (fn [e]
                         (set! (.. e -currentTarget -style -borderColor) "#58a6ff")
                         (set! (.. e -currentTarget -style -transform) "translateY(-5px)"))
        :on-mouse-out (fn [e]
                        (set! (.. e -currentTarget -style -borderColor) "#2d3b55")
                        (set! (.. e -currentTarget -style -transform) "none"))}
       [:div {:style {:font-size "4rem", :margin-bottom "20px"}} icon]
       [:h2 {:style {:margin-bottom "15px"}} label]
       [:p {:style {:color "#8b949e", :line-height "1.5"}} desc]])]])


(defn root-shell
  []
  (let [selected-demo (:selected-demo @demo-shell-state)]
    [:<>
     (case selected-demo
       :pipeline [pipeline/main-view]
       :plotter [plotter-demo/main-view]
       :continuation [cont-demo/main-view]
       :telemetry [tv/main-panel]
       [home-view])
     (when (not= selected-demo :home)
       [:div
        {:style {:position "fixed",
                 :top "20px",
                 :left "50%",
                 :transform "translateX(-50%)",
                 :z-index "1000",
                 :display "flex",
                 :align-items "center",
                 :gap "12px"}}
        [:a {:href "https://datom.world",
             :style {:color "#f1f5ff",
                     :text-decoration "none",
                     :font-weight "bold",
                     :font-size "14px",
                     :margin-right "8px"}}
         "Datom.world"]
        [:button
         {:on-click #(select-demo! :home),
          :style {:background "#151b33",
                  :color "#f1f5ff",
                  :border "1px solid #2d3b55",
                  :border-radius "6px",
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
                     :border-radius "6px",
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
