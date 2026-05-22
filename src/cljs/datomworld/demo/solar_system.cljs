(ns datomworld.demo.solar-system
  (:require
    [dao.postgraphics.terminal :as terminal]
    [dao.postgraphics.webgpu :as pg]
    [dao.stream :as ds]
    [dao.stream.ringbuffer]
    [reagent.core :as r]))


(def initial-state
  {:camera {:fov 55.0,
            :near 0.1,
            :far 200.0,
            :translate [0 16 34],
            :rotate [-0.44 0 0]},
   :animating? true,
   :bodies {:sun {:kind :sphere,
                  :translate [0 0 0],
                  :rotate [0 0 0],
                  :scale [3 3 3],
                  :color [1.0 0.75 0.1 1.0]},
            :mercury {:kind :cube,
                      :translate [5.5 0 0],
                      :scale [0.35 0.35 0.35],
                      :color [0.55 0.55 0.55 1.0]},
            :venus {:kind :cube,
                    :translate [8.0 0 0],
                    :scale [0.65 0.65 0.65],
                    :color [0.9 0.75 0.4 1.0]},
            :earth {:kind :cube,
                    :translate [11.0 0 0],
                    :scale [0.7 0.7 0.7],
                    :color [0.2 0.5 0.9 1.0]},
            :moon-pivot {:kind :group, :parent :earth, :rotate [0 0 0]},
            :moon {:kind :cube,
                   :parent :moon-pivot,
                   :translate [1.5 0 0],
                   :scale [0.25 0.25 0.25],
                   :color [0.75 0.75 0.78 1.0]},
            :mars {:kind :cube,
                   :translate [14.5 0 0],
                   :scale [0.5 0.5 0.5],
                   :color [0.75 0.25 0.1 1.0]}}})


(def ^:private cube-vertices
  [[-0.5 -0.5 -0.5] [0.5 -0.5 -0.5] [0.5 0.5 -0.5] [-0.5 0.5 -0.5]
   [-0.5 -0.5 0.5] [0.5 -0.5 0.5] [0.5 0.5 0.5] [-0.5 0.5 0.5]])


(def ^:private cube-edges
  [[0 1] [1 2] [2 3] [3 0] [4 5] [5 6] [6 7] [7 4] [0 4] [1 5] [2 6] [3 7]])


(def ^:private sphere-data
  (let [n 48
        r 0.5
        step (/ (* 2.0 js/Math.PI) n)
        circle (fn [pt-fn] (mapv (fn [i] (pt-fn (* i step))) (range n)))
        equator (circle (fn [t]
                          [(* r (js/Math.cos t)) 0.0
                           (* r (js/Math.sin t))]))
        meridian (fn [phi]
                   (circle (fn [t]
                             [(* r (js/Math.cos phi) (js/Math.cos t))
                              (* r (js/Math.sin t))
                              (* r (js/Math.sin phi) (js/Math.cos t))])))
        meridians (mapcat #(meridian (* % (/ js/Math.PI 6.0))) (range 6))
        lat-circle (fn [lat]
                     (circle
                       (fn [t]
                         [(* r (js/Math.cos lat) (js/Math.cos t))
                          (* r (js/Math.sin lat))
                          (* r (js/Math.cos lat) (js/Math.sin t))])))
        lat-circles (mapcat #(lat-circle (* % (/ js/Math.PI 6.0))) [-2 -1 1 2])
        vertices (vec (concat equator meridians lat-circles))
        ring-edges (fn [start]
                     (mapv (fn [i] [(+ start i) (+ start (mod (inc i) n))])
                           (range n)))
        edges (vec (mapcat #(ring-edges (* % n))
                           (range (quot (count vertices) n))))]
    {:vertices vertices, :edges edges}))


(defonce frame-stream
  (ds/open! {:type :ringbuffer, :capacity 4, :eviction-policy :evict-oldest}))


(defonce scene-state (r/atom initial-state))
(defonce tick-state (atom {:t 0.0}))
(defonce interval-id (atom nil))
(defonce terminal-handle (atom nil))


(defn- rotate-x
  [[x y z] a]
  (let [c (js/Math.cos a)
        s (js/Math.sin a)]
    [x (- (* y c) (* z s)) (+ (* y s) (* z c))]))


(defn- rotate-y
  [[x y z] a]
  (let [c (js/Math.cos a)
        s (js/Math.sin a)]
    [(+ (* x c) (* z s)) y (- (* z c) (* x s))]))


(defn- rotate-z
  [[x y z] a]
  (let [c (js/Math.cos a)
        s (js/Math.sin a)]
    [(- (* x c) (* y s)) (+ (* x s) (* y c)) z]))


(defn- local-transform
  [body p]
  (let [[sx sy sz] (get body :scale [1 1 1])
        [rx ry rz] (get body :rotate [0 0 0])
        [tx ty tz] (get body :translate [0 0 0])
        [x y z] (-> [(double (* sx (nth p 0))) (double (* sy (nth p 1)))
                     (double (* sz (nth p 2)))]
                    (rotate-x rx)
                    (rotate-y ry)
                    (rotate-z rz))]
    [(+ x tx) (+ y ty) (+ z tz)]))


(defn- body-transform-fn
  [bodies id]
  (let [body (get bodies id)
        parent-fn (when-let [parent (:parent body)]
                    (body-transform-fn bodies parent))]
    (fn [p]
      (let [local (local-transform body p)]
        (if parent-fn (parent-fn local) local)))))


(defn- draw-op
  [bodies id]
  (let [body (get bodies id)
        xf (body-transform-fn bodies id)]
    (case (:kind body)
      :sphere {:op/kind :draw3d/lines,
               :vertices (mapv xf (:vertices sphere-data)),
               :edges (:edges sphere-data),
               :color (:color body)}
      :cube {:op/kind :draw3d/lines,
             :vertices (mapv xf cube-vertices),
             :edges cube-edges,
             :color (:color body)}
      nil)))


(defn frame-from-state
  [{:keys [camera bodies]}]
  (let [body-ops (->> bodies
                      (remove (fn [[_ body]] (= (:kind body) :group)))
                      (sort-by (comp name first))
                      (keep (fn [[id _]] (draw-op bodies id)))
                      vec)]
    (into [{:op/kind :frame/clear, :color [0.02 0.025 0.045 1.0]}
           {:op/kind :camera3d/set,
            :camera3d/projection :perspective,
            :camera3d/fov (double (get camera :fov 55.0)),
            :camera3d/near (double (get camera :near 0.1)),
            :camera3d/far (double (get camera :far 200.0)),
            :camera3d/position (mapv double (get camera :translate [0 0 0])),
            :camera3d/rotation (mapv double (get camera :rotate [0 0 0]))}]
          body-ops)))


(defn- apply-animation!
  []
  (let [t (:t (swap! tick-state update :t + 0.016))]
    (when (:animating? @scene-state)
      (swap! scene-state (fn [state]
                           (-> state
                               (assoc-in [:bodies :mercury :translate]
                                         [(* 5.5 (js/Math.cos (* t 3.4))) 0
                                          (* 5.5 (js/Math.sin (* t 3.4)))])
                               (assoc-in [:bodies :venus :translate]
                                         [(* 8.0 (js/Math.cos (* t 2.2))) 0
                                          (* 8.0 (js/Math.sin (* t 2.2)))])
                               (assoc-in [:bodies :earth :translate]
                                         [(* 11.0 (js/Math.cos (* t 1.35))) 0
                                          (* 11.0 (js/Math.sin (* t 1.35)))])
                               (assoc-in [:bodies :mars :translate]
                                         [(* 14.5 (js/Math.cos (* t 0.75))) 0
                                          (* 14.5 (js/Math.sin (* t 0.75)))])
                               (assoc-in [:bodies :sun :rotate] [0 (* t 0.9) 0])
                               (assoc-in [:bodies :moon-pivot :rotate]
                                         [0 (* t 5.0) 0])))))))


(defn- color-css
  [[r g b a]]
  (str "rgba("
       (int (* 255 r))
       ","
       (int (* 255 g))
       ","
       (int (* 255 b))
       ","
       (or a 1.0)
       ")"))


(defn- resize-canvas!
  [canvas]
  (let [dpr (or (.-devicePixelRatio js/window) 1)
        rect (.getBoundingClientRect canvas)
        w (max 1 (int (* dpr (.-width rect))))
        h (max 1 (int (* dpr (.-height rect))))]
    (when (or (not= (.-width canvas) w) (not= (.-height canvas) h))
      (set! (.-width canvas) w)
      (set! (.-height canvas) h))
    [w h]))


(defn- project
  [camera width height p]
  (let [[cx cy cz] (get camera :camera3d/position [0 0 0])
        [rx ry rz] (get camera :camera3d/rotation [0 0 0])
        near (double (get camera :camera3d/near 0.1))
        fov (double (get camera :camera3d/fov 55.0))
        [vx vy vz] (-> [(- (nth p 0) cx) (- (nth p 1) cy) (- (nth p 2) cz)]
                       (rotate-x (- rx))
                       (rotate-y (- ry))
                       (rotate-z (- rz)))
        depth (- vz)]
    (when (> depth near)
      (let [aspect (/ width height)
            f (/ 1.0 (js/Math.tan (/ (* fov js/Math.PI) 360.0)))
            ndc-x (/ (* vx f) (* aspect depth))
            ndc-y (/ (* vy f) depth)]
        (when (and (< -2 ndc-x 2) (< -2 ndc-y 2))
          [(* (+ ndc-x 1.0) 0.5 width) (* (- 1.0 ndc-y) 0.5 height)])))))


(defn- render-lowered!
  [canvas lowered]
  (let [[width height] (resize-canvas! canvas)
        ctx (.getContext canvas "2d")]
    (set! (.-lineCap ctx) "round")
    (set! (.-lineJoin ctx) "round")
    (doseq [pass (:passes lowered)
            draw (:draws pass)]
      (case (:pipeline draw)
        :clear (let [[r g b a] (:color draw)]
                 (set! (.-fillStyle ctx) (color-css [r g b a]))
                 (.fillRect ctx 0 0 width height))
        :line-3d
        (let [camera (:camera draw)
              {:keys [vertices edges color]} (:payload draw)]
          (set! (.-strokeStyle ctx) (color-css color))
          (set! (.-lineWidth ctx) 1.4)
          (.beginPath ctx)
          (doseq [[a b] edges
                  :let [pa (project camera width height (nth vertices a))
                        pb (project camera width height (nth vertices b))]
                  :when (and pa pb)]
            (.moveTo ctx (nth pa 0) (nth pa 1))
            (.lineTo ctx (nth pb 0) (nth pb 1)))
          (.stroke ctx))
        nil))))


(defn- ensure-terminal!
  [canvas]
  (when-not @terminal-handle
    (reset! terminal-handle
            (pg/postgraphics-canvas
              canvas
              frame-stream
              :viewport-size (fn [] [(.-width canvas) (.-height canvas)])
              :submit! #(render-lowered! canvas %)
              :on-error #(js/console.error "postgraphics frame rejected" %)))))


(defn- release-terminal!
  []
  (when-let [handle @terminal-handle]
    (when-let [close! (:close! handle)] (close!))
    (reset! terminal-handle nil)))


(defn stop!
  []
  (when-let [id @interval-id]
    (js/clearInterval id)
    (reset! interval-id nil))
  :stopped)


(defn dispose!
  []
  (stop!) (release-terminal!) :disposed)


(defn start!
  []
  (when-not @interval-id
    (reset! interval-id (js/setInterval (fn []
                                          (apply-animation!)
                                          (terminal/put-frame! frame-stream
                                                               (frame-from-state
                                                                 @scene-state)))
                                        16)))
  :started)


(defn reset-scene!
  []
  (reset! tick-state {:t 0.0})
  (reset! scene-state initial-state)
  (terminal/put-frame! frame-stream (frame-from-state @scene-state)))


(defn- canvas-view
  []
  (let [canvas-ref (atom nil)]
    (r/create-class
      {:display-name "solar-system-webgpu-canvas",
       :component-did-mount (fn [_]
                              (when-let [canvas @canvas-ref]
                                (ensure-terminal! canvas)
                                (start!)
                                (terminal/put-frame! frame-stream
                                                     (frame-from-state
                                                       @scene-state)))),
       :component-will-unmount (fn [_] (dispose!)),
       :reagent-render
       (fn []
         [:canvas
          {:ref #(reset! canvas-ref %),
           :style {:width "min(78vw, 900px)",
                   :height "min(78vh, 720px)",
                   :display "block",
                   :border "1px solid rgba(160,190,255,0.28)",
                   :border-radius "18px",
                   :background "#050711",
                   :box-shadow "0 28px 90px rgba(0,0,0,0.48)"}}])})))


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
                :margin-bottom "22px"}}
       [:div
        [:div
         {:style {:letter-spacing "0.18em",
                  :text-transform "uppercase",
                  :color "#8eb6ff",
                  :font-size "12px"}} "dao.postgraphics.webgpu"]
        [:h1
         {:style {:font-size "clamp(34px, 5vw, 68px)",
                  :line-height "0.95",
                  :margin "8px 0 0"}} "Solar System"]]
       [:div {:style {:display "flex", :gap "10px"}}
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
                :grid-template-columns "minmax(0, 1fr) 280px",
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
         "The scene emits complete dao.postgraphics frames into a DaoStream. "
         "The browser terminal consumes that stream through "
         [:code "postgraphics-canvas"] " and lowers it with "
         [:code "dao.postgraphics.webgpu"] "."]
        [:p {:style {:color "#7f91bd", :font-size "13px", :margin "0"}}
         "This demo visualizes the lowered 3D line payload on a canvas-backed submitter while keeping the terminal boundary stream-based."]]]]]))
