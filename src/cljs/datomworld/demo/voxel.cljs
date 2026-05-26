(ns datomworld.demo.voxel
  (:require
    [dao.postgraphics.terminal :as terminal]
    [dao.postgraphics.webgpu :as pg]
    [dao.stream :as ds]
    [dao.stream.ringbuffer]
    [datomworld.demo.voxel-scene :as scene]
    [reagent.core :as r]))


(defonce frame-stream
  (ds/open! {:type :ringbuffer, :capacity 4, :eviction-policy :evict-oldest}))


(defonce ^:private interval-id (atom nil))
(defonce ^:private last-tick-ms* (atom nil))
(defonce ^:private player* (atom scene/default-player))
(defonce ^:private keys-down* (atom #{}))
(defonce ^:private chunk-mesh* (atom nil))
(defonce ^:private chunk-data* (atom nil))


(defn- ensure-chunk-mesh!
  []
  (when (nil? @chunk-mesh*)
    (let [chunk (scene/build-chunk)]
      (reset! chunk-data* chunk)
      (reset! chunk-mesh* (scene/chunk-mesh chunk)))))


;; --- keyboard ---

;; Browser KeyboardEvent.code → the same action vocabulary the scene's
;; integrate-motion understands. Codes are physical-key based so they
;; work regardless of OS keyboard layout.
(def ^:private code->action
  {"KeyW" :forward,
   "KeyS" :back,
   "KeyA" :left,
   "KeyD" :right,
   "Space" :up,
   "ShiftLeft" :down,
   "ShiftRight" :down,
   "ArrowLeft" :look-left,
   "ArrowRight" :look-right,
   "ArrowUp" :look-up,
   "ArrowDown" :look-down})


(defn- handle-key-down
  [^js e]
  (when-let [action (code->action (.-code e))]
    (.preventDefault e)
    (swap! keys-down* conj action)))


(defn- handle-key-up
  [^js e]
  (when-let [action (code->action (.-code e))]
    (.preventDefault e)
    (swap! keys-down* disj action)))


;; --- tick loop ---

(defn- tick!
  []
  (let [now (js/Date.now)
        last @last-tick-ms*
        dt (if last (/ (- now last) 1000.0) (/ scene/frame-interval-ms 1000.0))
        new-player
        (scene/integrate-motion @player* @keys-down* dt @chunk-data*)]
    (reset! last-tick-ms* now)
    (reset! player* new-player)
    (terminal/put-frame! frame-stream
                         (scene/frame-from-state new-player @chunk-mesh*))))


(defn stop!
  []
  (when-let [id @interval-id]
    (js/clearInterval id)
    (reset! interval-id nil))
  (reset! keys-down* #{})
  :stopped)


(defn dispose!
  []
  (stop!) :disposed)


(defn start!
  []
  (stop!)
  (ensure-chunk-mesh!)
  (reset! player* scene/default-player)
  (reset! last-tick-ms* nil)
  (tick!)
  (reset! interval-id (js/setInterval tick! scene/frame-interval-ms))
  :started)


(defn- canvas-view
  []
  (r/create-class
    {:display-name "voxel-postgraphics-widget",
     :component-did-mount
     (fn [_]
       (start!)
       (.addEventListener js/window "keydown" handle-key-down)
       (.addEventListener js/window "keyup" handle-key-up)),
     :component-will-unmount
     (fn [_]
       (.removeEventListener js/window "keydown" handle-key-down)
       (.removeEventListener js/window "keyup" handle-key-up)
       (dispose!)),
     :reagent-render
     (fn []
       [pg/postgraphics-widget frame-stream :canvas-attrs
        {:style {:width "min(78vw, 860px)",
                 :height "min(76vh, 720px)",
                 :display "block",
                 :border "1px solid rgba(210,220,255,0.24)",
                 :border-radius "22px",
                 :background "#06050f",
                 :box-shadow "0 30px 100px rgba(0,0,0,0.55)"}} :on-error
        #(js/console.error "voxel frame rejected" %)])}))


(defn main-view
  []
  [:div
   {:style
    {:min-height "100vh",
     :padding "84px 28px 32px",
     :background
     "radial-gradient(circle at 70% 18%, #2a234d 0, #091123 38%, #03050d 100%)",
     :color "#edf5ff",
     :font-family "Avenir Next, ui-sans-serif, sans-serif"}}
   [:div {:style {:max-width "1160px", :margin "0 auto"}}
    [:div {:style {:margin-bottom "22px"}}
     [:div
      {:style {:letter-spacing "0.18em",
               :text-transform "uppercase",
               :color "#b6c7ff",
               :font-size "12px"}} "dao.postgraphics 3d ops"]
     [:h1
      {:style {:font-size "clamp(34px, 5vw, 64px)",
               :line-height "0.95",
               :margin "8px 0 0"}} "Voxel Demo"]]
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
      [:h2 {:style {:margin "0 0 10px", :font-size "18px"}} "Controls"]
      [:ul {:style {:margin "0", :padding-left "20px", :color "#c2cee8"}}
       [:li [:strong "WASD"] " walk / strafe"]
       [:li [:strong "Space / Shift"] " fly up / down"]
       [:li [:strong "Arrow keys"] " look around"]]
      [:p {:style {:color "#c2cee8", :margin "16px 0 0"}} "The same "
       [:code "datomworld.demo.voxel-scene"] " builds the "
       "chunk mesh and integrates collision for both the Flutter GPU and "
       "browser frontends. The browser-side only adds keyboard wiring and "
       "a 2D Canvas painter for the lowered frame."]]]]])
