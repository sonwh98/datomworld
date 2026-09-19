(ns datomworld.demo.voxel
  (:require
    [dao.postgraphics.web :as pg]
    [datomworld.demo.responsive :as responsive]
    [datomworld.demo.voxel-controls :as controls]
    [datomworld.demo.voxel-input :as input]
    [datomworld.demo.voxel-runner :as runner]
    [datomworld.demo.voxel-scene :as scene]
    [reagent.core :as r]))


(defonce ^:private interval-id (atom nil))
(defonce ^:private last-tick-ms* (atom nil))


;; What the on-screen buttons need to draw: the canvas size their layout is
;; computed for, and which of them are held.
(defonce ^:private controls-view
  (r/atom {:width 720.0, :height 540.0, :pressed #{}}))


;; --- keyboard ---

;; Browser KeyboardEvent -> the fields of a dao.gui.event keyboard packet.
;; Codes are physical-key based so they work regardless of OS keyboard layout.
(def ^:private locations {1 :left, 2 :right, 3 :numpad})


(defn- native-key
  [^js e phase code]
  {:phase phase,
   :code code,
   :logical (input/logical-key (.-key e)),
   :location (get locations (.-location e) :standard),
   :modifiers (cond-> #{}
                (.-altKey e) (conj :alt)
                (.-ctrlKey e) (conj :control)
                (.-metaKey e) (conj :meta)
                (.-shiftKey e) (conj :shift)),
   :repeat? (.-repeat e),
   :time-us (js/Math.floor (* 1000 (.-timeStamp e)))})


(defn- key-handler
  [phase]
  (fn [^js e]
    (let [code (input/browser-code (.-code e))]
      (when (input/code->action code)
        (.preventDefault e)
        (runner/key-input! (native-key e phase code))))))


(def ^:private window-listeners
  {"keydown" (key-handler :down),
   "keyup" (key-handler :up),
   "focus" (fn [_] (runner/focus-input! true)),
   "blur" (fn [_] (runner/focus-input! false))})


;; --- pointer ---

(def ^:private pointer-phases
  {"pointerdown" :down,
   "pointermove" :move,
   "pointerup" :up,
   "pointercancel" :cancel,
   "lostpointercapture" :cancel})


(defn- pointer-type
  [^js e]
  (case (.-pointerType e)
    "touch" :touch
    "pen" :stylus
    :mouse))


(defn- native-pointer
  [^js e phase ^js canvas]
  (let [rect (.getBoundingClientRect canvas)]
    {:phase phase,
     :id (.-pointerId e),
     :type (pointer-type e),
     :buttons (.-buttons e),
     :position {:x (- (.-clientX e) (.-left rect)),
                :y (- (.-clientY e) (.-top rect))},
     :pressure (.-pressure e),
     :time-us (js/Math.floor (* 1000 (.-timeStamp e)))}))


(defonce ^:private canvas-cleanup* (atom nil))


(defn- install-canvas!
  "Canvas ref: wires pointer input and the surface size to the mounted canvas
   and unwires them when it unmounts (canvas is nil)."
  [^js canvas]
  (when-let [cleanup @canvas-cleanup*]
    (cleanup)
    (reset! canvas-cleanup* nil))
  (when canvas
    (let [handlers (into {}
                         (map (fn [[kind phase]]
                                [kind
                                 (fn [^js e]
                                   (when (= :down phase)
                                     (.setPointerCapture canvas (.-pointerId e)))
                                   (runner/pointer-input!
                                     (native-pointer e phase canvas)))]))
                         pointer-phases)
          resize! (fn []
                    (let [width (.-clientWidth canvas)
                          height (.-clientHeight canvas)]
                      (swap! controls-view assoc :width width :height height)
                      (runner/resize-input! width height)))
          observer (when (exists? js/ResizeObserver)
                     (doto (js/ResizeObserver. (fn [_] (resize!)))
                       (.observe canvas)))]
      (set! (.. canvas -style -touchAction) "none")
      (resize!)
      (doseq [[kind handler] handlers]
        (.addEventListener canvas kind handler))
      (reset! canvas-cleanup*
              (fn []
                (doseq [[kind handler] handlers]
                  (.removeEventListener canvas kind handler))
                (when observer (.disconnect observer)))))))


;; --- tick loop ---

(defn- tick!
  []
  (let [now (js/Date.now)
        last @last-tick-ms*
        dt (if last (/ (- now last) 1000.0) (/ scene/frame-interval-ms 1000.0))]
    (reset! last-tick-ms* now)
    (runner/tick! dt)
    (let [pressed (runner/pressed-controls)]
      (when (not= pressed (:pressed @controls-view))
        (swap! controls-view assoc :pressed pressed)))))


(defn stop!
  []
  (when-let [id @interval-id]
    (js/clearInterval id)
    (reset! interval-id nil))
  (runner/stop-input!)
  :stopped)


(defn dispose!
  []
  (stop!) :disposed)


(defn start!
  []
  (stop!)
  (runner/ensure-chunk-mesh!)
  (runner/reset-player!)
  (runner/start-input!)
  (reset! last-tick-ms* nil)
  (tick!)
  (reset! interval-id (js/setInterval tick! scene/frame-interval-ms))
  :started)


(defn- controls-overlay
  "Draws the on-screen buttons from the shared layout. It is inert
   (pointer-events: none): touches reach the canvas and dao.gui.event decides
   which button, if any, they landed on."
  []
  (let [{:keys [width height pressed]} @controls-view]
    (into [:div
           {:style {:position "absolute",
                    :top 0,
                    :left 0,
                    :width "100%",
                    :height "100%",
                    :pointer-events "none"}}]
          (map (fn [{:keys [action label x y], w :width, h :height}]
                 [:div
                  {:key (name action),
                   :style {:position "absolute",
                           :left (str x "px"),
                           :top (str y "px"),
                           :width (str w "px"),
                           :height (str h "px"),
                           :box-sizing "border-box",
                           :display "flex",
                           :align-items "center",
                           :justify-content "center",
                           :border (if (contains? pressed action)
                                     "2px solid #ffd54a"
                                     "2px solid rgba(255,255,255,0.8)"),
                           :border-radius "16px",
                           :background (if (contains? pressed action)
                                         "rgba(8,10,24,0.85)"
                                         "rgba(8,10,24,0.5)"),
                           :color (if (contains? pressed action)
                                    "#ffd54a"
                                    "#ffffff"),
                           :text-shadow "0 1px 3px rgba(0,0,0,0.6)",
                           :font-size (str (* 0.34 h) "px"),
                           :font-weight "600",
                           :user-select "none",
                           :-webkit-user-select "none"}} label]))
          (controls/layout {:width width, :height height}))))


(defn- canvas-view
  []
  (r/create-class
    {:display-name "voxel-postgraphics-widget",
     :component-did-mount
     (fn [_]
       (start!)
       (doseq [[kind handler] window-listeners]
         (.addEventListener js/window kind handler))),
     :component-will-unmount
     (fn [_]
       (doseq [[kind handler] window-listeners]
         (.removeEventListener js/window kind handler))
       (dispose!)),
     :reagent-render
     (fn []
       [:div {:style {:position "relative", :width "100%", :max-width "860px"}}
        [pg/postgraphics-widget runner/frame-stream :canvas-attrs
         {:style (assoc (responsive/canvas-frame-style
                          {:max-width 860,
                           :min-height 300,
                           :height-vh 60,
                           :max-height 720,
                           :border-color "rgba(210,220,255,0.24)",
                           :border-radius 22,
                           :background "#06050f",
                           :box-shadow "0 30px 100px rgba(0,0,0,0.55)"})
                        :cursor "grab")}
         :canvas-ref install-canvas! :on-error
         #(js/console.error "voxel frame rejected" %)] [controls-overlay]])}))


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
              :grid-template-columns (responsive/auto-fit-grid 320),
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
       [:li [:strong "WASD"] " or the " [:strong "on-screen pad"]
        " walk / strafe"]
       [:li [:strong "Space / Shift"] " or " [:strong "UP / DN"]
        " fly up / down"]
       [:li [:strong "Arrow keys"] " or " [:strong "click-drag"]
        " look around"]]
      [:h2 {:style {:margin "20px 0 10px", :font-size "18px"}}
       "How input works"]
      [:p {:style {:color "#c2cee8", :margin "0 0 12px"}}
       "Everything is a stream. Key, pointer, focus and geometry values "
       "travel through a bounded " [:code "dao.stream"] " ring buffer into "
       [:code "dao.gui.event"] ", a total reducer: " [:code "(step state input)"]
       " returns the next state and its outputs. The browser and Flutter "
       "frontends only translate a native event into one of those values."]
      [:ul
       {:style {:color "#8391b7",
                :font-size "14px",
                :margin "0 0 12px",
                :padding-left "20px"}}
       [:li "The canvas is a node in the presented geometry with a "
        [:code "pan"] " recognizer. A drag past 4px becomes " [:code ":pan"]
        " gestures whose deltas turn the camera."]
       [:li "The on-screen buttons are nodes above the canvas. Hit-testing "
        "happens once, on press, so a finger on a button never starts a "
        "look; its raw pointer events hold the button until the finger "
        "lifts."]
       [:li "Keys are routed through the runtime's focus record. Losing "
        "focus cancels every held key, so none stick."]
       [:li "The demo subscribes to what the interpreter delivers, key "
        "transitions, pan gestures and button presses, and folds them into "
        "held keys and camera yaw and pitch."]]
      [:p {:style {:color "#c2cee8", :margin "0"}}
       [:code "datomworld.demo.voxel-input"] " holds this wiring, "
       [:code "voxel-runner"] " the per-tick state, and "
       [:code "voxel-scene"] " the geometry and collision; one copy of that "
       "code drives both frontends. Rendering is separate: frames go to "
       [:code "dao.postgraphics"] ", which draws them with WebGPU when "
       "available and a software canvas otherwise."]]]]])
