(ns datomworld.demo.artifact
  (:require [dao.gui.event :as event]
            [dao.postgraphics.terminal :as terminal]
            [dao.postgraphics.web :as pg]
            [dao.stream :as ds]
            [dao.stream.ringbuffer :as rb]
            [datomworld.demo.artifact-runner :as runner]
            [datomworld.demo.artifact-scene :as scene]
            [datomworld.demo.responsive :as responsive]
            [reagent.core :as r]))


(defonce frame-stream
  (ds/open! {:dao.stream/type :ringbuffer,
             :capacity 4,
             :eviction-policy :evict-oldest}))


(defonce scene-state (r/atom runner/initial-state))
(defonce interval-id (atom nil))


(defonce runtime-input-stream
  (ds/open! {:dao.stream/type :ringbuffer,
             :capacity 1024,
             :eviction-policy :evict-oldest}))


(defonce event-binding* (atom nil))
(defonce output-streams* (atom nil))
(defonce output-cursors* (atom {}))
(defonce runtime-seq* (atom 0))
(defonce keyboard-seq* (atom -1))
(defonce runtime-time* (atom -1))


(defonce ids*
  (atom {:generation-id 1, :frame-id 1, :coordinate-space-id 1, :profile-id 1}))


(defonce viewport* (r/atom [1.0 1.0]))


(defonce signal-stream
  (ds/open!
    {:dao.stream/type :ringbuffer, :capacity 32, :eviction-policy :reject}))


(defonce active-pointers* (atom {}))
(defonce canvas-listeners* (atom nil))
(defonce keyboard-listeners* (atom nil))
(defonce canvas* (atom nil))
(defonce resize-observer* (atom nil))


(def output-keys
  [:effects :trace :pointer :keyboard :gesture :dispatch :diagnostic])


(defn- open-output-streams
  []
  (into {}
        (map (fn [k]
               [k
                (ds/open! {:dao.stream/type :ringbuffer,
                           :capacity 64,
                           :eviction-policy :evict-oldest})])
             output-keys)))


(defn- advance!
  []
  (when-let [binding @event-binding*]
    (let [result (runner/advance-until-progress binding 64)]
      (reset! event-binding*
              (if (some #{:input-gap} (:statuses result))
                (let [state (get-in result [:binding :state])
                      runtime-seq (inc (long (or (:last-runtime-seq state) -1)))
                      runtime-time-us (inc (long (or (:last-runtime-time-us state)
                                                     -1)))]
                  (event/recover-input-gap
                    (:binding result)
                    {:position (rb/tail-position runtime-input-stream)}
                    {:runtime/seq runtime-seq,
                     :runtime/time-us runtime-time-us,
                     :runtime/source :terminal,
                     :runtime/value {:message/kind :dao.terminal/input-loss,
                                     :generation-id (:generation-id state),
                                     :reason :stream-capacity}}))
                (:binding result)))
      (doseq [[k stream] @output-streams*]
        (loop [cursor (get @output-cursors* k {:position 0})]
          (let [read (ds/next stream cursor)]
            (if (map? read)
              (let [value (:ok read)]
                (when (and (= k :gesture) (runner/drag-gesture? value))
                  (prn "dao.gui.event drag -> dao.stream" value))
                (when (= k :gesture)
                  (swap! scene-state runner/reduce-gesture value))
                (when (= k :keyboard)
                  (prn "dao.stream keyboard -> scene" value)
                  (swap! scene-state runner/reduce-keyboard value))
                (recur (:cursor read)))
              (if (= :daostream/gap read)
                (do (prn "dao.stream output gap; resuming at tail" k)
                    (swap! output-cursors* assoc
                           k
                           {:position (rb/tail-position stream)}))
                (swap! output-cursors* assoc k cursor)))))))))


(defn- append-runtime!
  [value]
  (let [seq (inc @runtime-seq*)
        requested-time (or (:runtime/time-us value) seq)
        time-us (max (inc @runtime-time*) (long requested-time))
        envelope (assoc value
                        :runtime/seq seq
                        :runtime/time-us time-us)]
    (prn "dao.stream <-" envelope)
    (when (= :ok (:result (ds/append! runtime-input-stream envelope)))
      (reset! runtime-seq* seq)
      (reset! runtime-time* time-us)
      (advance!))))


(defn- boot-events!
  []
  (let [{:keys [generation-id frame-id coordinate-space-id profile-id]} @ids*
        [width height] @viewport*
        outputs (open-output-streams)]
    (reset! output-streams* outputs)
    (reset! event-binding* (event/bind {:inputs {:runtime-input
                                                 runtime-input-stream},
                                        :outputs outputs}))
    (doseq [value (runner/boot-values {:generation-id generation-id,
                                       :frame-id frame-id,
                                       :coordinate-space-id coordinate-space-id,
                                       :profile-id profile-id,
                                       :width width,
                                       :height height})]
      (append-runtime! value))))


(defn- canvas-size
  [canvas]
  [(max 1.0 (double (.-clientWidth canvas)))
   (max 1.0 (double (.-clientHeight canvas)))])


(defn- resize!
  [size]
  (when (not= size @viewport*)
    (reset! viewport* size)
    (let [ids (swap! ids* (fn [ids]
                            (-> ids
                                (update :frame-id inc)
                                (update :coordinate-space-id inc))))
          [width height] size]
      (doseq [value [{:runtime/source :terminal,
                      :runtime/value
                      {:message/kind :dao.terminal/coordinate-space-change,
                       :generation-id (:generation-id ids),
                       :coordinate-space-id (:coordinate-space-id ids),
                       :viewport {:width width, :height height}}}
                     {:runtime/source :geometry,
                      :runtime/value (runner/presented-geometry
                                       (:generation-id ids)
                                       (:frame-id ids)
                                       (:coordinate-space-id ids)
                                       width
                                       height)}]]
        (append-runtime! value)))))


(defn- pointer-kind
  [event]
  (case (.-pointerType event)
    "touch" :touch
    "pen" :stylus
    :mouse))


(defn- pointer-packet
  [event phase runtime-seq]
  (let [rect (.getBoundingClientRect (.-target event))
        id (.-pointerId event)
        active (assoc @active-pointers* id true)
        primary-id (first (sort (keys active)))]
    (when (#{:up :cancel :out :leave} phase) (swap! active-pointers* dissoc id))
    {:input/kind :pointer,
     :generation-id (:generation-id @ids*),
     :frame-id (:frame-id @ids*),
     :coordinate-space-id (:coordinate-space-id @ids*),
     :profile-id (:profile-id @ids*),
     :input-seq runtime-seq,
     :pointer {:id id,
               :type (pointer-kind event),
               :primary? (= id primary-id),
               :buttons (.-buttons event),
               :modifiers #{},
               :pressure (.-pressure event)},
     :phase phase,
     :samples [{:time-us (js/Math.floor (* 1000 (.-timeStamp event))),
                :position {:x (- (.-clientX event) (.-left rect)),
                           :y (- (.-clientY event) (.-top rect))},
                :sample/kind :actual}]}))


(defn- keyboard-modifiers
  [event]
  (cond-> #{}
    (.-altKey event) (conj :alt)
    (.-ctrlKey event) (conj :control)
    (.-metaKey event) (conj :meta)
    (.-shiftKey event) (conj :shift)))


(defn- keyboard-packet
  [event phase input-seq]
  {:input/kind :keyboard,
   :generation-id (:generation-id @ids*),
   :input-seq input-seq,
   :time-us (js/Math.floor (* 1000 (.-timeStamp event))),
   :phase phase,
   :focus-id nil,
   :repeat? (boolean (.-repeat event)),
   :modifiers (keyboard-modifiers event),
   :key {:code (runner/keyboard-code (.-code event)),
         :logical (.-key event),
         :location :standard}})


(defn- zoom-key?
  [event]
  (or (contains? #{"+" "-"} (.-key event))
      (contains? #{"NumpadAdd" "NumpadSubtract"} (.-code event))))


(defn- rotation-key?
  [event]
  (contains? #{"KeyW" "KeyA" "KeyS" "KeyD"} (.-code event)))


(defn- install-pointer-listeners!
  [canvas]
  (when-let [old @canvas-listeners*]
    (when-let [mounted @canvas*]
      (doseq [[kind handler] old] (.removeEventListener mounted kind handler))))
  (when-let [old @keyboard-listeners*]
    (doseq [[kind handler] old] (.removeEventListener js/window kind handler)))
  (if (nil? canvas)
    (do (reset! canvas-listeners* nil)
        (reset! keyboard-listeners* nil)
        (reset! canvas* nil))
    (let [runtime-pointer
          (fn [event phase]
            (let [seq (inc @runtime-seq*)
                  target (.-target event)]
              (when (and (= phase :down) (fn? (.-setPointerCapture target)))
                (.setPointerCapture target (.-pointerId event)))
              (when (and (#{:up :cancel} phase)
                         (fn? (.-releasePointerCapture target)))
                (.releasePointerCapture target (.-pointerId event)))
              (append-runtime! (runner/pointer-runtime-input
                                 {:runtime-seq seq,
                                  :runtime-time-us seq,
                                  :packet
                                  (pointer-packet event phase seq)}))))
          handlers (into {}
                         (map (fn [[kind phase]]
                                [kind
                                 #(runtime-pointer % phase)])
                              runner/pointer-event-phases))
          runtime-keyboard
          (fn [event phase]
            (when (and (or (zoom-key? event) (rotation-key? event))
                       (not (.-altKey event))
                       (not (.-ctrlKey event))
                       (not (.-metaKey event)))
              (.preventDefault event)
              (let [input-seq (swap! keyboard-seq* inc)]
                (append-runtime!
                  (runner/keyboard-runtime-input
                    {:runtime-seq (inc @runtime-seq*),
                     :runtime-time-us (.-timeStamp event),
                     :packet (keyboard-packet event phase input-seq)})))))
          keyboard-handlers {"keydown" #(runtime-keyboard % :down),
                             "keyup" #(runtime-keyboard % :up)}]
      (resize! (canvas-size canvas))
      (when (exists? js/ResizeObserver)
        (let [observer (js/ResizeObserver. (fn [_]
                                             (resize! (canvas-size canvas))))]
          (.observe observer canvas)
          (reset! resize-observer* observer)))
      (set! (.. canvas -style -touchAction) "none")
      (doseq [[kind handler] handlers] (.addEventListener canvas kind handler))
      (doseq [[kind handler] keyboard-handlers]
        (.addEventListener js/window kind handler))
      (reset! canvas-listeners* handlers)
      (reset! keyboard-listeners* keyboard-handlers)
      (reset! canvas* canvas))))


(defn- emit-frame!
  []
  (terminal/put-frame! frame-stream
                       (scene/build-frame @scene-state @viewport*)))


(defn stop!
  []
  (when-let [id @interval-id]
    (js/clearInterval id)
    (reset! interval-id nil))
  :stopped)


(defn dispose!
  []
  (stop!)
  (append-runtime! {:runtime/source :control,
                    :runtime/value {:input/kind :dao.gui.event/teardown}})
  (when-let [observer @resize-observer*]
    (.disconnect observer)
    (reset! resize-observer* nil))
  (install-pointer-listeners! nil)
  (when @event-binding* (reset! event-binding* nil))
  (ds/close! runtime-input-stream)
  (ds/close! signal-stream)
  (ds/close! frame-stream)
  :disposed)


(defn start!
  []
  (when-not @interval-id
    (boot-events!)
    (emit-frame!)
    (reset! interval-id (js/setInterval (fn []
                                          (swap! scene-state runner/tick-state)
                                          (emit-frame!))
                                        16)))
  :started)


(defn- canvas-view
  []
  (r/create-class
    {:display-name "artifact-postgraphics-widget",
     :component-did-mount (fn [_] (start!)),
     :component-will-unmount (fn [_] (dispose!)),
     :reagent-render
     (fn []
       [pg/postgraphics-widget frame-stream :canvas-attrs
        {:style (responsive/canvas-frame-style
                  {:max-width 860,
                   :min-height 300,
                   :height-vh 60,
                   :max-height 720,
                   :border-color "rgba(210,220,255,0.24)",
                   :border-radius 22,
                   :background "#040612",
                   :box-shadow "0 30px 100px rgba(0,0,0,0.55)"})}
        :canvas-ref install-pointer-listeners! :viewport-size
        (fn [] @viewport*) :signal-stream signal-stream :on-error
        (fn [error] (prn "artifact frame rejected" error))
        :on-paint-error
        (fn [error] (prn "artifact paint failed" error))])}))


(defn- reset-scene!
  []
  (reset! scene-state runner/initial-state))


(defn main-view
  []
  [:div
   {:style
    {:min-height "100vh",
     :padding "84px 28px 32px",
     :background
     "radial-gradient(circle at 50% 50%, #0d2138 0, #040812 50%, #010205 100%)",
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
                :font-size "12px"}} "dao.postgraphics + dao.gui.event"]
      [:h1
       {:style {:font-size "clamp(34px, 5vw, 64px)",
                :line-height "0.95",
                :margin "8px 0 0"}} "The Glowing Artifact"]]
     [:div {:style {:display "flex", :gap "10px", :flex-wrap "wrap"}}
      [:button
       {:on-click reset-scene!,
        :style {:padding "10px 14px",
                :border-radius "999px",
                :border "1px solid #7186c7",
                :background "#111a31",
                :color "#edf5ff",
                :cursor "pointer"}} "Reset Camera"]]]
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
       "Interactive 3D Sandbox"]
      [:p {:style {:color "#c2cee8", :margin "0 0 12px"}}
       "This demo illustrates the first axiom, "
       [:strong "everything is a stream"] ". Pointer, keyboard, geometry, "
       "timer, and subscription values travel through bounded "
       [:code "dao.stream.ring-buffer"] " streams into " [:code "dao.gui.event"]
       ". The interpreter emits semantic interaction "
       "values, which update immutable camera state and the next frame."]
      [:ul
       {:style {:color "#8391b7",
                :font-size "14px",
                :margin "0",
                :padding-left "20px"}}
       [:li "Click and drag to " [:strong "rotate"] "."]
       [:li "Pinch or scroll to " [:strong "zoom"] "."]
       [:li "Press " [:strong "+"] " or " [:strong "-"] " to zoom."]
       [:li "Press " [:strong "W/A/S/D"] " to rotate."]
       [:li "Click or tap to " [:strong "pulse"]
        " the artifact's emissive material."]]]]]])
