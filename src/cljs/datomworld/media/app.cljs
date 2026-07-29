(ns datomworld.media.app
  (:require
    [dao.gui.event :as gui-event]
    [dao.postgraphics.terminal :as pg-terminal]
    [dao.postgraphics.web :as pg]
    [dao.stream :as ds]
    [dao.stream.ringbuffer]
    [datomworld.media.controller :as controller]
    [datomworld.media.datom-log :as datom-log]
    [datomworld.media.view :as view]
    [datomworld.media.web-terminal :as web-terminal]
    [reagent.core :as r]
    [reagent.dom :as rdom]))


(defn- stream
  ([] (stream nil nil))
  ([capacity eviction-policy]
   (ds/open! (cond-> {:type :ringbuffer, :capacity capacity}
               eviction-policy (assoc :eviction-policy eviction-policy)))))


(defn- write!
  [target value]
  (ds/put! target value))


(defn- drain!
  [source position consume!]
  (loop []
    (let [result (ds/next source {:position @position})]
      (when (map? result)
        (consume! (:ok result))
        (swap! position inc)
        (recur)))))


(defn- viewport
  [container]
  (if container
    (let [rect (.getBoundingClientRect container)]
      {:viewport-width (max 320 (.-width rect))
       :viewport-height (max 240 (.-height rect))})
    {:viewport-width 960 :viewport-height 540}))


(defn- keyboard-event
  [state key]
  (case (.toLowerCase key)
    " " {:player.event/kind :ui/toggle-play}
    "k" {:player.event/kind :ui/toggle-play}
    "arrowleft" {:player.event/kind :ui/seek
                 :media/position-seconds
                 (- (:player/position-seconds state) 5)}
    "arrowright" {:player.event/kind :ui/seek
                  :media/position-seconds
                  (+ (:player/position-seconds state) 5)}
    "arrowup" {:player.event/kind :ui/set-volume
               :media/volume (+ (:player/volume state) 0.05)}
    "arrowdown" {:player.event/kind :ui/set-volume
                 :media/volume (- (:player/volume state) 0.05)}
    "m" {:player.event/kind :ui/toggle-muted}
    "f" {:player.event/kind :ui/toggle-fullscreen}
    nil))


(defn- datom-copy
  [[entity attribute value transaction metadata]]
  (str "[" entity " " attribute " " (pr-str value)
       " " transaction " " metadata "]"))


(defn player-app
  []
  (let [event-stream (stream)
        media-command-stream (stream)
        settings-command-stream (stream)
        view-model-stream (stream)
        datom-stream (stream)
        frame-stream (stream 3 :evict-oldest)
        geometry-stream (stream 3 :evict-oldest)
        tap-stream (stream)
        input-stream (stream 32 :evict-oldest)
        signal-stream (stream)
        event-position (atom 0)
        view-position (atom 0)
        datom-position (atom 0)
        next-transaction (atom 0)
        controller-state (atom controller/initial-state)
        visible-state (r/atom controller/initial-state)
        visible-datoms (r/atom [])
        media-ref (atom nil)
        container-ref (atom nil)
        file-input-ref (atom nil)
        runtime (atom nil)
        raf-id (atom nil)
        disposed? (atom false)
        emit! #(write! event-stream %)
        record!
        (fn [origin value]
          (let [transaction (swap! next-transaction inc)]
            (doseq [datom (datom-log/value->datoms
                            transaction origin value)]
              (write! datom-stream datom))))
        present-datom!
        (fn [datom]
          (swap! visible-datoms
                 (fn [datoms]
                   (let [next-datoms (conj datoms datom)]
                     (if (> (count next-datoms) 96)
                       (subvec next-datoms (- (count next-datoms) 96))
                       next-datoms)))))
        open-picker! (fn []
                       (when-let [input @file-input-ref] (.click input)))
        select-file! (fn [file]
                       (when (and file @runtime)
                         ((get-in @runtime [:source :register-file!]) file)))
        convert-current!
        (fn []
          (when-let [convert! (get-in @runtime
                                      [:source :convert-source!])]
            (when-let [source-id
                       (get-in @visible-state
                               [:player/source :media/source-id])]
              (convert!
                source-id
                {:media/duration-seconds
                 (:player/duration-seconds @visible-state)}))))
        render-view!
        (fn [state]
          (reset! visible-state state)
          (pg-terminal/put-frame!
            frame-stream
            (view/frame state (viewport @container-ref))))
        process-event!
        (fn [event]
          (let [before @controller-state
                {:keys [state media-commands settings-commands view-model]}
                (controller/run-transition before event)]
            (record! :event-stream event)
            (reset! controller-state state)
            (when (not= before state)
              (let [transaction (swap! next-transaction inc)]
                (doseq [datom (datom-log/changes->datoms
                                transaction before state)]
                  (write! datom-stream datom))))
            (doseq [command media-commands]
              (record! :media-command-stream command)
              (write! media-command-stream command))
            (doseq [command settings-commands]
              (record! :settings-command-stream command)
              (write! settings-command-stream command))
            (write! view-model-stream view-model)))
        seek-from-position!
        (fn [{:keys [position]}]
          (let [{:keys [controls/x controls/width]}
                (view/layout @visible-state (viewport @container-ref))
                track-x (+ x 16.0)
                track-width (- width 32.0)
                fraction (max 0.0
                              (min 1.0
                                   (/ (- (:x position) track-x)
                                      (max 1.0 track-width))))]
            (emit! {:player.event/kind :ui/seek
                    :media/position-seconds
                    (* fraction (:player/duration-seconds @visible-state))})))
        cycle-rate!
        (fn []
          (let [rates [0.5 1.0 1.5 2.0]
                current (:player/rate @visible-state)
                next-rate (or (second (drop-while #(not= % current) rates))
                              0.5)]
            (emit! {:player.event/kind :ui/set-rate
                    :media/rate next-rate})))
        start!
        (fn []
          (let [source (web-terminal/create-source-registry!
                         {:event-stream event-stream})
                media (web-terminal/bind-media!
                        {:media-element @media-ref
                         :command-stream media-command-stream
                         :event-stream event-stream
                         :fullscreen-target @container-ref
                         :resolve-source (:resolve-source source)})
                settings (web-terminal/create-settings-terminal!
                           {:command-stream settings-command-stream
                            :event-stream event-stream})
                events (gui-event/create-runtime!
                         {:geometry-stream geometry-stream
                          :tap-stream tap-stream
                          :signal-stream signal-stream})]
            (gui-event/register! events :player/play-pause :tap
                                 (fn [_] (emit!
                                          {:player.event/kind
                                           :ui/toggle-play})))
            (gui-event/register! events :player/seek :tap seek-from-position!)
            (gui-event/register! events :player/mute :tap
                                 (fn [_] (emit!
                                          {:player.event/kind
                                           :ui/toggle-muted})))
            (gui-event/register! events :player/rate :tap
                                 (fn [_] (cycle-rate!)))
            (gui-event/register! events :player/open-file :tap
                                 (fn [_] (open-picker!)))
            (gui-event/register! events :player/fullscreen :tap
                                 (fn [_]
                                   ((:perform-gesture! media)
                                    (if (:player/fullscreen? @visible-state)
                                      :exit-fullscreen
                                      :request-fullscreen))))
            (reset! runtime {:source source
                             :media media
                             :settings settings
                             :events events})
            (record! :lifecycle-stream
                     {:player.event/kind :lifecycle/boot
                      :player/runtime :yin-register-vm})
            ((:load! settings))
            (write! view-model-stream controller/initial-state)
            (letfn [(tick []
                      (when-not @disposed?
                        ((:process-pending! media))
                        ((:process-pending! settings))
                        (drain! event-stream event-position process-event!)
                        ((:process-pending! media))
                        ((:process-pending! settings))
                        (drain! view-model-stream view-position render-view!)
                        (drain! datom-stream datom-position present-datom!)
                        (gui-event/process-pending! events)
                        (reset! raf-id (js/requestAnimationFrame tick))))]
              (tick))))
        dispose!
        (fn []
          (reset! disposed? true)
          (when-let [id @raf-id] (js/cancelAnimationFrame id))
          (when-let [{:keys [source media events]} @runtime]
            ((:dispose! media))
            ((:dispose! source))
            (gui-event/dispose! events))
          (reset! runtime nil))
        handle-file-change!
        (fn [event]
          (let [files (some-> event .-target .-files)]
            (when (and files (pos? (.-length files)))
              (select-file! (.item files 0)))))
        handle-drop!
        (fn [event]
          (.preventDefault event)
          (let [files (some-> event .-dataTransfer .-files)]
            (when (and files (pos? (.-length files)))
              (select-file! (.item files 0)))))
        emit-fullscreen!
        (fn []
          (when-let [media (get-in @runtime [:media])]
            ((:perform-gesture! media)
             (if (:player/fullscreen? @visible-state)
               :exit-fullscreen
               :request-fullscreen))))]
    (r/create-class
      {:display-name "datomworld-media-player"
       :component-did-mount (fn [_] (start!))
       :component-will-unmount (fn [_] (dispose!))
       :reagent-render
       (fn []
         (let [state @visible-state
               duration (max 0 (:player/duration-seconds state))
               position (min duration (:player/position-seconds state))
               source-container
               (get-in state [:player/source :media/container])
               conversion-label
               (case (:player/media-kind state)
                 :video "Export .datmov"
                 :audio "Export .datmus"
                 "Export datom media")]
           [:main.media-app
            {:on-key-down
             (fn [event]
               (when-let [player-event
                          (keyboard-event state (.-key event))]
                 (.preventDefault event)
                 (if (= :ui/toggle-fullscreen
                        (:player.event/kind player-event))
                   (emit-fullscreen!)
                   (emit! player-event))))}
            [:header.media-header
             [:div
              [:span.media-kicker "DATOM.WORLD"]
              [:h1 "Local Media Terminal"]]
             [:div.media-header-side
              [:p "Native decoding. Yin.VM control. PostGraphics interface."]
              [:div.media-header-actions
               [:button {:type "button" :on-click open-picker!}
                "Open media"]
               [:button
                {:type "button"
                 :disabled (not= source-container :native)
                 :on-click convert-current!}
                (if (contains? #{:datmov :datmus} source-container)
                  "Datom container loaded"
                  conversion-label)]]]]
            [:section.media-workspace
             [:section.media-stage
              {:ref #(reset! container-ref %)
               :tab-index 0
               :on-drag-over #(.preventDefault %)
               :on-drop handle-drop!}
              [:video.media-plane
               {:ref #(reset! media-ref %)
                :preload "metadata"
                :plays-inline true}]
              [pg/postgraphics-widget
               frame-stream
               :canvas-attrs {:class "media-postgraphics"
                              :aria-hidden true}
               :geometry-stream geometry-stream
               :tap-stream tap-stream
               :input-stream input-stream
               :signal-stream signal-stream
               :backend (pg/software-backend)]
              [:input
               {:ref #(reset! file-input-ref %)
                :class "media-file-input"
                :type "file"
                :accept "audio/*,video/*,.datmov,.datmus"
                :on-change handle-file-change!}]
              [:div.media-semantic-controls
               {:aria-label "Media controls"}
               [:button {:type "button" :on-click open-picker!} "Open file"]
               [:button
                {:type "button"
                 :disabled (nil? (:player/source state))
                 :on-click #(emit! {:player.event/kind :ui/toggle-play})}
                (if (= :playing (:player/status state)) "Pause" "Play")]
               [:label
                [:span "Seek"]
                [:input
                 {:type "range"
                  :min 0
                  :max duration
                  :step 0.01
                  :value position
                  :disabled (zero? duration)
                  :on-change
                  #(emit! {:player.event/kind :ui/seek
                           :media/position-seconds
                           (js/parseFloat (.. % -target -value))})}]]
               [:button
                {:type "button"
                 :on-click #(emit! {:player.event/kind :ui/toggle-muted})}
                (if (:player/muted? state) "Unmute" "Mute")]
               [:label
                [:span "Volume"]
                [:input
                 {:type "range"
                  :min 0 :max 1 :step 0.01
                  :value (:player/volume state)
                  :on-change
                  #(emit! {:player.event/kind :ui/set-volume
                           :media/volume
                           (js/parseFloat (.. % -target -value))})}]]
               [:label
                [:span "Speed"]
                [:select
                 {:value (:player/rate state)
                  :on-change
                  #(emit! {:player.event/kind :ui/set-rate
                           :media/rate
                           (js/parseFloat (.. % -target -value))})}
                 (for [rate [0.5 1 1.5 2]]
                   ^{:key rate} [:option {:value rate} (str rate "x")])]]
               [:button {:type "button" :on-click emit-fullscreen!}
                (if (:player/fullscreen? state)
                  "Exit fullscreen"
                  "Fullscreen")]]]
             [:aside.datom-monitor
              {:aria-label "Live datom stream"}
              [:header.datom-monitor-header
               [:div
                [:span.datom-live-dot]
                [:strong "LIVE DATOM STREAM"]]
               [:span (str (count @visible-datoms) " facts")]]
              [:p.datom-schema "[e a v t m] · local assertions · newest first"]
              [:ol.datom-list
               (for [[index datom]
                     (map-indexed vector (reverse @visible-datoms))]
                 ^{:key (str (nth datom 3) "-" (nth datom 1) "-" index)}
                 [:li
                  [:span.datom-transaction
                   (str "t" (nth datom 3))]
                  [:code (datom-copy datom)]])]]]
            [:footer.media-footer
             "Files stay on this device. Datom conversion wraps the original "
             "payload without transcoding or quality loss."]]))})))


(defn init
  []
  (when (and (exists? js/navigator) (.-serviceWorker js/navigator))
    (.register (.-serviceWorker js/navigator) "/media-player-sw.js"))
  (when-let [root (.getElementById js/document "media-player-root")]
    (rdom/render [player-app] root)))
