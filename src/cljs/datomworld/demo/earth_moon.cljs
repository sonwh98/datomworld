(ns datomworld.demo.earth-moon
  (:require
    [dao.postgraphics.terminal :as terminal]
    [dao.postgraphics.webgpu :as pg]
    [dao.stream :as ds]
    [dao.stream.ringbuffer]
    [reagent.core :as r]))


(defonce frame-stream
  (ds/open! {:type :ringbuffer, :capacity 4, :eviction-policy :evict-oldest}))


(defonce scene-state (r/atom {:seconds 0.0, :animating? true}))


(defonce interval-id (atom nil))
(defonce texture-cache (atom {}))


(def ^:private earth-texture-path
  "/assets/images/earth_land_ocean_ice_1024.jpg")


(def ^:private moon-texture-path "/assets/images/moon_texture_512.jpg")


(def ^:private stars
  [[-6.6 4.8 -16.0] [-5.1 2.9 -13.0] [-4.4 -3.9 -15.0] [-3.2 4.1 -18.0]
   [-2.0 -4.5 -14.0] [-0.9 5.2 -17.0] [0.8 3.8 -12.0] [2.4 5.0 -16.0]
   [3.4 2.7 -13.0] [5.2 4.3 -17.0] [6.2 -2.4 -14.0] [-6.2 -1.8 -12.0]
   [-4.8 0.8 -19.0] [-2.8 1.5 -11.0] [1.7 -3.7 -16.0] [4.8 -0.8 -15.0]
   [6.8 1.2 -20.0] [-1.6 -2.5 -13.0] [3.0 -4.8 -18.0] [5.8 -4.2 -16.0]])


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


(defn- v+
  [a b]
  [(+ (nth a 0) (nth b 0)) (+ (nth a 1) (nth b 1)) (+ (nth a 2) (nth b 2))])


(defn- v*
  [s v]
  [(* s (nth v 0)) (* s (nth v 1)) (* s (nth v 2))])


(defn- sphere-mesh
  [lat-count lon-count]
  (let [lon-stride (inc lon-count)
        vertices (vec (for [lat (range (inc lat-count))
                            lon (range lon-stride)
                            :let [v (- (/ lat lat-count) 0.5)
                                  theta (* v js/Math.PI)
                                  phi (* 2.0 js/Math.PI (/ lon lon-count))
                                  ct (js/Math.cos theta)]]
                        [(* ct (js/Math.cos phi)) (js/Math.sin theta)
                         (* ct (js/Math.sin phi))]))
        uvs (vec (for [lat (range (inc lat-count))
                       lon (range lon-stride)]
                   [(/ lon lon-count) (- 1.0 (/ lat lat-count))]))
        idx (fn [lat lon] (+ (* lat lon-stride) lon))
        indices (vec (mapcat (fn [lat]
                               (mapcat (fn [lon]
                                         (let [a (idx lat lon)
                                               b (idx lat (inc lon))
                                               c (idx (inc lat) lon)
                                               d (idx (inc lat) (inc lon))]
                                           [[a c b] [b c d]]))
                                       (range lon-count)))
                             (range lat-count)))]
    {:vertices vertices, :normals vertices, :uvs uvs, :indices indices}))


(def ^:private unit-sphere (sphere-mesh 35 70))


(defn- transform-sphere
  [{:keys [radius translate rotate]} mesh]
  (let [[rx ry rz] (or rotate [0 0 0])
        xf-point (fn [p]
                   (-> (v* radius p)
                       (rotate-x rx)
                       (rotate-y ry)
                       (rotate-z rz)
                       (v+ translate)))
        xf-normal (fn [p]
                    (-> p
                        (rotate-x rx)
                        (rotate-y ry)
                        (rotate-z rz)))]
    (-> mesh
        (update :vertices #(mapv xf-point %))
        (update :normals #(mapv xf-normal %)))))


(defn- star-lines
  []
  (let [vertices (vec (mapcat (fn [[x y z]]
                                [[(- x 0.035) y z]
                                 [(+ x 0.035) y z]])
                              stars))
        edges (mapv (fn [i] [(* 2 i) (inc (* 2 i))]) (range (count stars)))]
    {:op/kind :draw3d/lines,
     :vertices vertices,
     :edges edges,
     :color [0.86 0.92 1.0 0.95]}))


(defn frame-from-seconds
  [seconds]
  (let [earth (transform-sphere {:radius 2.0,
                                 :translate [0.0 0.0 0.0],
                                 :rotate [-0.41 seconds 0.0]}
                                unit-sphere)
        orbit-a (* seconds 0.82)
        moon-pos [(* 4.7 (js/Math.cos orbit-a)) (* 0.42 (js/Math.sin orbit-a))
                  (* 2.0 (js/Math.sin orbit-a))]
        moon-behind-earth? (neg? (nth moon-pos 2))
        moon (transform-sphere {:radius 0.54,
                                :translate moon-pos,
                                :rotate [0 (* seconds 1.3) 0]}
                               unit-sphere)
        moon-orbit (let [segments 96
                         vertices
                         (mapv (fn [i]
                                 (let [t (* 2.0 js/Math.PI (/ i segments))]
                                   [(* 4.7 (js/Math.cos t))
                                    (* 0.42 (js/Math.sin t))
                                    (* 2.0 (js/Math.sin t))]))
                               (range segments))
                         edges (mapv (fn [i] [i (mod (inc i) segments)])
                                     (range segments))]
                     {:op/kind :draw3d/lines,
                      :vertices vertices,
                      :edges edges,
                      :color [0.35 0.5 0.8 0.42]})
        base-frame [{:op/kind :frame/clear, :color [0.015 0.018 0.04 1.0]}
                    {:op/kind :camera3d/set,
                     :camera3d/projection :perspective,
                     :camera3d/fov 48.0,
                     :camera3d/near 0.1,
                     :camera3d/far 80.0,
                     :camera3d/position [0.0 2.8 11.0],
                     :camera3d/rotation [-0.24 0.0 0.0]}
                    {:op/kind :state/depth-test, :enabled true}
                    {:op/kind :state/depth-write, :enabled true}
                    {:op/kind :state/lighting-enable, :enabled true}
                    {:op/kind :light/ambient, :color [0.08 0.1 0.14]}
                    {:op/kind :light/directional,
                     :direction [-0.4 0.7 0.9],
                     :color [1.0 0.96 0.86],
                     :intensity 1.55} (star-lines) moon-orbit]
        earth-op (assoc earth
                        :op/kind :draw3d/mesh
                        :fill [0.34 0.68 1.0 1.0]
                        :texture/source earth-texture-path
                        :texture/filter :linear
                        :texture/wrap :repeat
                        :texture/mipmap false
                        :material/specular [0.2 0.26 0.32]
                        :material/shininess 20.0)
        moon-op (assoc moon
                       :op/kind :draw3d/mesh
                       :fill [0.72 0.72 0.68 1.0]
                       :texture/source moon-texture-path
                       :texture/filter :linear
                       :texture/wrap :repeat
                       :texture/mipmap false
                       :material/specular [0.08 0.08 0.08]
                       :material/shininess 8.0)]
    (into base-frame
          (if moon-behind-earth? [moon-op earth-op] [earth-op moon-op]))))


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
        fov (double (get camera :camera3d/fov 48.0))
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
          {:xy [(* (+ ndc-x 1.0) 0.5 width) (* (- 1.0 ndc-y) 0.5 height)],
           :depth depth})))))


(defn- face-shade
  [vertices tri]
  (let [[a b c] (map #(nth vertices %) tri)
        ux (- (nth b 0) (nth a 0))
        uy (- (nth b 1) (nth a 1))
        uz (- (nth b 2) (nth a 2))
        vx (- (nth c 0) (nth a 0))
        vy (- (nth c 1) (nth a 1))
        vz (- (nth c 2) (nth a 2))
        nx (- (* uy vz) (* uz vy))
        ny (- (* uz vx) (* ux vz))
        nz (- (* ux vy) (* uy vx))
        len (max 0.0001 (js/Math.sqrt (+ (* nx nx) (* ny ny) (* nz nz))))
        light [0.35 0.65 0.68]
        dot (/
              (+ (* nx (nth light 0)) (* ny (nth light 1)) (* nz (nth light 2)))
              len)]
    (+ 0.48 (* 0.52 (max 0.0 dot)))))


(defn- shade-color
  [[r g b a] shade]
  (color-css [(* r shade) (* g shade) (* b shade) a]))


(defn- load-texture!
  [source]
  (when-not (contains? @texture-cache source)
    (let [img (js/Image.)]
      (swap! texture-cache assoc source {:status :loading, :image img})
      (set! (.-onload img)
            (fn []
              (let [canvas (.createElement js/document "canvas")
                    width (.-naturalWidth img)
                    height (.-naturalHeight img)
                    ctx (.getContext canvas "2d")]
                (set! (.-width canvas) width)
                (set! (.-height canvas) height)
                (.drawImage ctx img 0 0 width height)
                (swap! texture-cache assoc
                       source
                       {:status :ready,
                        :image img,
                        :width width,
                        :height height,
                        :data (.-data (.getImageData ctx 0 0 width height))}))))
      (set! (.-onerror img)
            (fn [] (swap! texture-cache assoc source {:status :failed})))
      (set! (.-src img) source))))


(defn- resolve-texture-resource
  [source _state]
  (when (#{earth-texture-path moon-texture-path} source)
    (load-texture! source)
    {:resource/kind :image-texture, :source source}))


(defn- texture-sample
  [source [u v]]
  (let [{:keys [status width height data]} (get @texture-cache source)]
    (if (= status :ready)
      (let [uu (- u (js/Math.floor u))
            vv (- v (js/Math.floor v))
            x (min (dec width) (max 0 (int (* uu width))))
            y (min (dec height) (max 0 (int (* vv height))))
            i (* 4 (+ x (* y width)))]
        [(/ (aget data i) 255.0) (/ (aget data (+ i 1)) 255.0)
         (/ (aget data (+ i 2)) 255.0) (/ (aget data (+ i 3)) 255.0)])
      [1.0 1.0 1.0 1.0])))


(defn- texture-ready
  [source]
  (let [entry (get @texture-cache source)]
    (when (= :ready (:status entry)) entry)))


(defn- texture-tri-color
  [payload tri]
  (if-let [source (:texture/source payload)]
    (let [uvs (:uvs payload)
          [a b c] tri
          uv [(/ (+ (nth (nth uvs a) 0) (nth (nth uvs b) 0) (nth (nth uvs c) 0))
                 3.0)
              (/ (+ (nth (nth uvs a) 1) (nth (nth uvs b) 1) (nth (nth uvs c) 1))
                 3.0)]
          [tr tg tb ta] (texture-sample source uv)
          [fr fg fb fa] (:fill payload [1 1 1 1])]
      [(* tr fr) (* tg fg) (* tb fb) (* ta fa)])
    (:fill payload [1 1 1 1])))


(defn- affine-from-tri
  [[[u0 v0] [u1 v1] [u2 v2]] [[x0 y0] [x1 y1] [x2 y2]]]
  (let [denom (+ (* u0 (- v1 v2)) (* u1 (- v2 v0)) (* u2 (- v0 v1)))]
    (when (> (js/Math.abs denom) 0.000001)
      (let [a (/ (+ (* x0 (- v1 v2)) (* x1 (- v2 v0)) (* x2 (- v0 v1))) denom)
            b (/ (+ (* y0 (- v1 v2)) (* y1 (- v2 v0)) (* y2 (- v0 v1))) denom)
            c (/ (+ (* x0 (- u2 u1)) (* x1 (- u0 u2)) (* x2 (- u1 u0))) denom)
            d (/ (+ (* y0 (- u2 u1)) (* y1 (- u0 u2)) (* y2 (- u1 u0))) denom)
            e (/ (+ (* x0 (- (* u1 v2) (* u2 v1)))
                    (* x1 (- (* u2 v0) (* u0 v2)))
                    (* x2 (- (* u0 v1) (* u1 v0))))
                 denom)
            f (/ (+ (* y0 (- (* u1 v2) (* u2 v1)))
                    (* y1 (- (* u2 v0) (* u0 v2)))
                    (* y2 (- (* u0 v1) (* u1 v0))))
                 denom)]
        [a b c d e f]))))


(defn- draw-textured-triangle!
  [ctx texture payload tri points shade]
  (let [{:keys [image width height]} texture
        uvs (:uvs payload)
        source-points (mapv (fn [i]
                              [(* width (nth (nth uvs i) 0))
                               (* height (nth (nth uvs i) 1))])
                            tri)]
    (when-let [[a b c d e f] (affine-from-tri source-points points)]
      (.save ctx)
      (let [[[x1 y1] [x2 y2] [x3 y3]] points]
        (.beginPath ctx)
        (.moveTo ctx x1 y1)
        (.lineTo ctx x2 y2)
        (.lineTo ctx x3 y3)
        (.closePath ctx)
        (.clip ctx)
        (.setTransform ctx a b c d e f)
        (.drawImage ctx image 0 0)
        (.restore ctx)
        (set! (.-fillStyle ctx) (str "rgba(0,0,0," (- 1.0 shade) ")"))
        (.beginPath ctx)
        (.moveTo ctx x1 y1)
        (.lineTo ctx x2 y2)
        (.lineTo ctx x3 y3)
        (.closePath ctx)
        (.fill ctx)))))


(defn- render-mesh!
  [ctx camera width height {:keys [vertices indices], :as payload}]
  (let [projected (mapv #(project camera width height %) vertices)
        texture (when-let [source (:texture/source payload)]
                  (texture-ready source))
        tris (->> indices
                  (keep (fn [tri]
                          (let [ps (mapv projected tri)]
                            (when (every? some? ps)
                              {:tri tri,
                               :points (mapv :xy ps),
                               :depth (/ (reduce + (map :depth ps)) 3.0)}))))
                  (sort-by :depth >))]
    (doseq [{:keys [tri points]} tris
            :let [[[x1 y1] [x2 y2] [x3 y3]] points
                  shade (face-shade vertices tri)
                  color (texture-tri-color payload tri)]]
      (if texture
        (draw-textured-triangle! ctx texture payload tri points shade)
        (do (.beginPath ctx)
            (.moveTo ctx x1 y1)
            (.lineTo ctx x2 y2)
            (.lineTo ctx x3 y3)
            (.closePath ctx)
            (set! (.-fillStyle ctx) (shade-color color shade))
            (.fill ctx))))))


(defn- render-lines!
  [ctx camera width height {:keys [vertices edges color]}]
  (set! (.-strokeStyle ctx) (color-css color))
  (set! (.-lineWidth ctx) 1.2)
  (.beginPath ctx)
  (doseq [[a b] edges
          :let [pa (project camera width height (nth vertices a))
                pb (project camera width height (nth vertices b))]
          :when (and pa pb)]
    (let [[x1 y1] (:xy pa)
          [x2 y2] (:xy pb)]
      (.moveTo ctx x1 y1)
      (.lineTo ctx x2 y2)))
  (.stroke ctx))


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
        :line-3d (render-lines! ctx (:camera draw) width height (:payload draw))
        (:mesh-3d :mesh-textured-3d)
        (render-mesh! ctx (:camera draw) width height (:payload draw))
        nil))))


(defn stop!
  []
  (when-let [id @interval-id]
    (js/clearInterval id)
    (reset! interval-id nil))
  :stopped)


(defn dispose!
  []
  (stop!) :disposed)


(defn start!
  []
  (when-not @interval-id
    (reset! interval-id
            (js/setInterval
              (fn []
                (swap! scene-state update
                       :seconds
                       (fn [seconds]
                         (if (:animating? @scene-state) (+ seconds 0.0242) seconds)))
                (terminal/put-frame! frame-stream
                                     (frame-from-seconds (:seconds @scene-state))))
              16)))
  :started)


(defn reset-scene!
  []
  (reset! scene-state {:seconds 0.0, :animating? true})
  (terminal/put-frame! frame-stream (frame-from-seconds 0.0)))


(defn- canvas-view
  []
  (r/create-class
    {:display-name "earth-moon-postgraphics-widget",
     :component-did-mount (fn [_]
                            (start!)
                            (terminal/put-frame! frame-stream
                                                 (frame-from-seconds
                                                   (:seconds @scene-state)))),
     :component-will-unmount (fn [_] (dispose!)),
     :reagent-render
     (fn []
       [pg/postgraphics-widget frame-stream :canvas-attrs
        {:style {:width "min(78vw, 860px)",
                 :height "min(76vh, 720px)",
                 :display "block",
                 :border "1px solid rgba(210,220,255,0.24)",
                 :border-radius "22px",
                 :background "#040612",
                 :box-shadow "0 30px 100px rgba(0,0,0,0.55)"}}
        :resolve-resource resolve-texture-resource :submit!
        render-lowered! :on-error
        #(js/console.error "earth/moon frame rejected" %)])}))


(defn main-view
  []
  (let [{:keys [animating?]} @scene-state]
    [:div
     {:style
      {:min-height "100vh",
       :padding "84px 28px 32px",
       :background
       "radial-gradient(circle at 70% 18%, #2a234d 0, #091123 38%, #03050d 100%)",
       :color "#edf5ff",
       :font-family "Avenir Next, ui-sans-serif, sans-serif"}}
     [:div {:style {:max-width "1160px", :margin "0 auto"}}
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
                  :color "#b6c7ff",
                  :font-size "12px"}} "dao.postgraphics 3d ops"]
        [:h1
         {:style {:font-size "clamp(34px, 5vw, 64px)",
                  :line-height "0.95",
                  :margin "8px 0 0"}} "Earth and Moon"]]
       [:div {:style {:display "flex", :gap "10px"}}
        [:button
         {:on-click #(swap! scene-state update :animating? not),
          :style {:padding "10px 14px",
                  :border-radius "999px",
                  :border "1px solid #7186c7",
                  :background (if animating? "#e3e9ff" "#111a31"),
                  :color (if animating? "#081020" "#edf5ff"),
                  :cursor "pointer"}} (if animating? "Pause" "Animate")]
        [:button
         {:on-click reset-scene!,
          :style {:padding "10px 14px",
                  :border-radius "999px",
                  :border "1px solid #7186c7",
                  :background "#111a31",
                  :color "#edf5ff",
                  :cursor "pointer"}} "Reset"]]]
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
        [:h2 {:style {:margin "0 0 10px", :font-size "18px"}}
         "Postgraphics Program"]
        [:p {:style {:color "#c2cee8", :margin "0 0 12px"}}
         "Each tick emits a complete frame with " [:code ":camera3d/set"]
         ", lighting state, two " [:code ":draw3d/mesh"] " spheres, and "
         [:code ":draw3d/lines"] " for rings, orbit, and stars."]
        [:p {:style {:color "#8391b7", :font-size "13px", :margin "0"}}
         "This is a 3D postgraphics producer. The current browser submitter paints the lowered mesh and line intents to canvas while the WebGPU VM owns validation, stream binding, and lowering."]]]]]))
