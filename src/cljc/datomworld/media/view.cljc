(ns datomworld.media.view
  "Pure player view-model interpreter: player state -> DaoGUI -> PostGraphics."
  (:require
    [dao.gui.compiler :as gui]))


(def colors
  {:surface [0.035 0.045 0.065 0.94]
   :surface-soft [0.08 0.10 0.14 0.88]
   :text [0.96 0.97 1.0 1.0]
   :muted [0.62 0.67 0.75 1.0]
   :accent [0.30 0.70 1.0 1.0]
   :buffered [0.30 0.36 0.44 1.0]
   :track [0.14 0.17 0.22 1.0]
   :danger [1.0 0.35 0.38 1.0]})


(defn layout
  [_state {:keys [viewport-width viewport-height]}]
  (let [w (max 320.0 (double (or viewport-width 320)))
        h (max 240.0 (double (or viewport-height 240)))
        mode (if (< w 640.0) :compact :wide)
        controls-height (if (= mode :compact) 132.0 104.0)]
    {:layout/mode mode
     :viewport/width w
     :viewport/height h
     :controls/x 12.0
     :controls/y (- h controls-height 12.0)
     :controls/width (- w 24.0)
     :controls/height controls-height}))


(defn- pad2
  [n]
  (if (< n 10) (str "0" n) (str n)))


(defn format-time
  [seconds]
  (let [total (max 0 (long (or seconds 0)))
        hours (quot total 3600)
        minutes (quot (mod total 3600) 60)
        secs (mod total 60)]
    (if (pos? hours)
      (str hours ":" (pad2 minutes) ":" (pad2 secs))
      (str minutes ":" (pad2 secs)))))


(defn- measure-text
  [{:keys [text/value text/font-size]}]
  {:width (* (count (str value)) (double (or font-size 14)) 0.58)
   :height (* (double (or font-size 14)) 1.2)})


(defn- translated
  [x y child]
  [:transform {:translate [x y]} child])


(defn- label
  [x y value size color]
  (translated x y
              [:text {:value value
                      :font-size size
                      :font-family "ui-sans-serif"
                      :color color}]))


(defn- button
  [node-id x y width text]
  (translated
    x y
    [:stack
     [:rect {:width width
             :height 38
             :color (:surface-soft colors)
             :node-id node-id
             :interactive-events #{:tap}}]
     (label 12 12 text 13 (:text colors))]))


(defn- progress-form
  [state x y width]
  (let [duration (max 0.0 (double (:player/duration-seconds state 0.0)))
        position (max 0.0 (double (:player/position-seconds state 0.0)))
        fraction (if (pos? duration) (min 1.0 (/ position duration)) 0.0)
        buffered-end (double
                       (or (some-> state :player/buffered first second) 0.0))
        buffered-fraction (if (pos? duration)
                            (min 1.0 (/ buffered-end duration))
                            0.0)
        played-width (* width fraction)
        buffered-width (* width buffered-fraction)]
    (translated
      x y
      [:stack
       [:rect {:width width
               :height 12
               :color (:track colors)
               :node-id :player/seek
               :interactive-events #{:tap}}]
       (when (pos? buffered-width)
         [:rect {:width buffered-width
                 :height 12
                 :color (:buffered colors)}])
       (when (pos? played-width)
         [:rect {:width played-width
                 :height 12
                 :color (:accent colors)
                 :node-id :player/progress}])])))


(defn- status-copy
  [state]
  (case (:player/status state)
    :empty "Choose a local audio or video file"
    :loading "Loading metadata"
    :waiting "Buffering"
    :ended "Playback ended"
    :error (str "Cannot play this file: "
                (name (or (:player/error state) :media-error)))
    :playing "Playing"
    :paused "Paused"
    :ready "Ready"
    ""))


(defn ui-form
  [state viewport]
  (let [layout* (layout state viewport)
        mode (:layout/mode layout*)
        viewport-width (:viewport/width layout*)
        viewport-height (:viewport/height layout*)
        x (:controls/x layout*)
        y (:controls/y layout*)
        width (:controls/width layout*)
        height (:controls/height layout*)
        source-name (get-in state [:player/source :media/name])
        compact? (= mode :compact)
        button-y (+ y (if compact? 78.0 54.0))
        seek-width (- width 32.0)
        play-label (if (= :playing (:player/status state)) "Pause" "Play")
        mute-label (if (:player/muted? state) "Unmute" "Mute")
        rate-label (str (:player/rate state 1.0) "x")
        time-copy (str (format-time (:player/position-seconds state))
                       " / "
                       (format-time (:player/duration-seconds state)))]
    [:stack
     [{:op/kind :frame/clear :color [0.0 0.0 0.0 0.0]}]
     (when (= :empty (:player/status state))
       [:rect {:width viewport-width :height viewport-height
               :color [0.018 0.025 0.040 1.0]}])
     (label 24 30 (or source-name "datom.world media") 18 (:text colors))
     (label 24 56 (status-copy state) 13
            (if (= :error (:player/status state))
              (:danger colors)
              (:muted colors)))
     (button :player/open-file
             (- viewport-width 132.0)
             20.0
             108.0
             "Open file")
     (translated
       x y
       [:rect {:width width :height height :color (:surface colors)}])
     (progress-form state (+ x 16.0) (+ y 18.0) seek-width)
     (label (+ x 16.0) (+ y 42.0) time-copy 12 (:muted colors))
     (button :player/play-pause (+ x 16.0) button-y 72.0 play-label)
     (button :player/mute (+ x 96.0) button-y 72.0 mute-label)
     (button :player/rate (+ x 176.0) button-y 64.0 rate-label)
     (button :player/fullscreen (- (+ x width) 104.0)
             button-y
             88.0
             (if (:player/fullscreen? state) "Exit full" "Fullscreen"))]))


(defn frame
  [state viewport]
  (gui/compile-ui (ui-form state viewport)
                  nil
                  {}
                  {:measure-text measure-text}))
