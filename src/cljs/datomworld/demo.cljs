(ns datomworld.demo
  (:require [datomworld.demo.compilation-pipeline-v2 :as pipeline-v2]
            [datomworld.demo.continuation-stream-v2 :as cont-demo-v2]
            [datomworld.demo.artifact :as artifact-demo]
            [datomworld.demo.earth-moon :as earth-moon-demo]
            [datomworld.demo.equation-plotter-v2 :as plotter-demo-v2]
            [datomworld.demo.responsive :as responsive]
            [datomworld.demo.solar-system :as solar-demo]
            [datomworld.demo.voxel :as voxel-demo]
            [datomworld.demo.yin-repl-v2 :as yin-repl-demo]
            [reagent.core :as r]
            [reagent.dom :as rdom]))


(def demo-options
  [{:id :solar-system,
    :label "Solar System",
    :icon "☉",
    :desc "Postgraphics solar system ported to the browser WebGPU terminal."}
   {:id :earth-moon,
    :label "Earth and Moon",
    :icon "◐",
    :desc "3D postgraphics Earth/Moon scene using mesh and line ops."}
   {:id :artifact,
    :label "Glowing Artifact",
    :icon "✦",
    :desc "Cross-platform postgraphics artifact scene."}
   {:id :voxel,
    :label "Voxel",
    :icon "▣",
    :desc
    "First-person voxel chunk: WASD/arrows to fly through the same postgraphics frame program rendered on both Flutter GPU and browser canvas."}
   {:id :pipeline-v2,
    :label "Pipeline v2",
    :icon "⚙",
    :desc "The Yin compilation pipeline on Yin VM v2 and DaoStream v2: Source -> AST -> canonical datoms -> ast-walker execution."}
   {:id :continuation-v2,
    :label "Continuation v2",
    :icon "⤱",
    :desc "Two Yin VM v2 evaluators share one continuation across a DaoStream v2 medium."}
   {:id :plotter-v2,
    :label "Equation Plotter v2",
    :icon "📈",
    :desc "Equation plotter on Yin VM v2: the dao.stream.apply bridge dispatches through explicit v2 FFI state."}
   {:id :yin-repl,
    :label "Yin REPL",
    :icon "λ",
    :desc "Browser CodeMirror client for a remote Yin REPL over WebSockets."}])


(defn- hash->demo
  "#pipeline, #plotter and #continuation are kept as aliases into their -v2
   picker entries: yin.vm.v2-consumers.implementation-plan.md D5 deletes the
   v1 demos those hashes used to name, and public/chp/yin.chp:18 still links
   to /demo.html#pipeline, so the URL keeps resolving even though the
   address bar does not rewrite itself to the -v2 hash on arrival."
  [hash-value]
  (case hash-value
    "#yin-repl" :yin-repl
    "#pipeline" :pipeline-v2
    "#pipeline-v2" :pipeline-v2
    "#plotter" :plotter-v2
    "#plotter-v2" :plotter-v2
    "#continuation" :continuation-v2
    "#continuation-v2" :continuation-v2
    "#solar-system" :solar-system
    "#earth-moon" :earth-moon
    "#artifact" :artifact
    "#voxel" :voxel
    :home))


(defn- demo->hash
  [demo-id]
  (case demo-id
    :yin-repl "#yin-repl"
    :pipeline-v2 "#pipeline-v2"
    :plotter-v2 "#plotter-v2"
    :continuation-v2 "#continuation-v2"
    :solar-system "#solar-system"
    :earth-moon "#earth-moon"
    :artifact "#artifact"
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
    :artifact (artifact-demo/dispose!)
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
   {:style
    {:min-height "100vh",
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
    [:div {:style {:max-width "760px"}}
     [:div
      {:style {:color "#8eb6ff",
               :font-size "12px",
               :font-weight "700",
               :letter-spacing "0.16em",
               :text-transform "uppercase",
               :margin-bottom "12px"}} "Interactive stream frontends"]
     [:h1
      {:style {:font-size "clamp(2.4rem, 6vw, 4.8rem)",
               :line-height "0.94",
               :margin "0 0 12px"}}
      [:a
       {:href "https://datom.world",
        :style {:color "inherit", :text-decoration "none"}} "Datom.world"]
      " Demos"]
     [:p
      {:style
       {:margin 0, :color "#b8c7e8", :font-size "16px", :line-height "1.65"}}
      "Everything is a stream. Everything is a continuation. Meaning is constructed by the interpreter."]
     [:div
      {:style
       {:margin-top "18px", :display "flex", :gap "24px", :flex-wrap "wrap"}}
      [:a
       {:href "/blog/structure-vs-interpretation.blog",
        :style {:color "#8eb6ff",
                :text-decoration "none",
                :font-size "14px",
                :font-weight "600"}} "→ Structure vs Interpretation"]
      [:a
       {:href "/blog/semantics-structure-interpretation.blog",
        :style {:color "#8eb6ff",
                :text-decoration "none",
                :font-size "14px",
                :font-weight "600"}} "→ Semantics as Structure"]]]
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
        [:div [:h2 {:style {:margin "0 0 10px", :font-size "1.35rem"}} label]
         [:p {:style {:margin 0, :color "#8b949e", :line-height "1.6"}}
          desc]]])]]])


(defn root-shell
  []
  (let [selected-demo (:selected-demo @demo-shell-state)]
    [:<>
     (case selected-demo
       :yin-repl [yin-repl-demo/main-view]
       :pipeline-v2 [pipeline-v2/main-view]
       :plotter-v2 [plotter-demo-v2/main-view]
       :continuation-v2 [cont-demo-v2/main-view]
       :solar-system [solar-demo/main-view]
       :earth-moon [earth-moon-demo/main-view]
       :artifact [artifact-demo/main-view]
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
                  :font-size "13px"}} "🏠 Back to Demos"]])]))


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
