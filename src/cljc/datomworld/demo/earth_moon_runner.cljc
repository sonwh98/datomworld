(ns datomworld.demo.earth-moon-runner
  "Shared runtime state and per-tick logic for the Earth/Moon demo. The
   Flutter (CLJD) and browser (CLJS) wrappers own only their platform-
   specific texture loading, timer, time source, asset paths, and widget
   tree; the frame stream, scene state, texture atoms, animation gating,
   and the simulated-seconds math all live here so behavior stays in
   sync between frontends."
  (:require
    [dao.postgraphics.terminal :as terminal]
    [dao.stream :as ds]
    [dao.stream.ringbuffer]
    [datomworld.demo.earth-moon-scene :as scene]
    #?(:cljs [reagent.core :as r])))


(defonce frame-stream
  (ds/open! {:type :ringbuffer, :capacity 4, :eviction-policy :evict-oldest}))


;; scene-state must be a Reagent atom in CLJS so the Pause/Animate button
;; label re-renders on toggle; in CLJD there is no Reagent and no UI button
;; reading it, so a plain atom suffices.
(defonce scene-state
  #?(:cljs (r/atom {:seconds 0.0, :animating? true})
     :default (atom {:seconds 0.0, :animating? true})))


(defonce started-at-ms* (atom nil))


(defonce ^:private earth-texture* (atom nil))
(defonce ^:private moon-texture* (atom nil))
(defonce ^:private ring-texture* (atom nil))


(defn set-earth-texture!
  [tex]
  (reset! earth-texture* tex))


(defn set-moon-texture!
  [tex]
  (reset! moon-texture* tex))


(defn set-ring-texture!
  [tex]
  (reset! ring-texture* tex))


(defn current-textures
  []
  {:earth-tex @earth-texture*,
   :moon-tex @moon-texture*,
   :ring-tex @ring-texture*})


(defn- abs-d
  [x]
  (let [x (double x)] (if (< x 0.0) (- x) x)))


(defn ring-darkness
  "Radial brightness profile across the ring (v: 0 inner edge → 1 outer).
   Smooth tan body with three darker gap bands suggestive of Saturn's
   Cassini-style divisions, plus a parabolic fade toward the edges."
  [v]
  (let [v (double v)
        edge-fade (* 4.0 v (- 1.0 v))
        body (+ 0.55 (* 0.45 edge-fade))
        gap (cond (< (abs-d (- v 0.18)) 0.025) 0.45
                  (< (abs-d (- v 0.50)) 0.035) 0.30
                  (< (abs-d (- v 0.78)) 0.020) 0.55
                  :else 1.0)]
    (* body gap)))


(defn tick!
  "Advances simulated time and pushes a frame onto frame-stream. now-ms is
   the caller's wall-clock millisecond timestamp; when paused, :seconds is
   held flat so newly-loaded textures still propagate without resuming."
  [now-ms]
  (let [last-start @started-at-ms*
        seconds (if (:animating? @scene-state)
                  (let [elapsed-ms (- now-ms (or last-start now-ms))]
                    (* (/ scene/frame-step-seconds scene/frame-interval-ms)
                       elapsed-ms))
                  (:seconds @scene-state))]
    (swap! scene-state assoc :seconds seconds)
    (terminal/put-frame! frame-stream
                         (scene/frame-from-seconds seconds
                                                   (current-textures)))))


(defn toggle-animation!
  "Pauses or resumes animation. On resume, back-dates started-at-ms* so the
   next tick's elapsed-ms picks up at the paused :seconds value instead of
   restarting at 0."
  [now-ms]
  (let [animating? (:animating? @scene-state)]
    (swap! scene-state update :animating? not)
    (when-not animating?
      (let [seconds (:seconds @scene-state)
            elapsed-ms (* seconds
                          (/ scene/frame-interval-ms scene/frame-step-seconds))]
        (reset! started-at-ms* (- now-ms elapsed-ms))))))


(defn reset-scene!
  "Resets to t=0, marks animating, and emits an initial frame at the
   reset point."
  [now-ms]
  (reset! started-at-ms* now-ms)
  (reset! scene-state {:seconds 0.0, :animating? true})
  (terminal/put-frame! frame-stream
                       (scene/frame-from-seconds 0.0 (current-textures))))
