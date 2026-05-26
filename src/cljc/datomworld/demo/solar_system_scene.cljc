(ns datomworld.demo.solar-system-scene
  #?(:cljd
     (:require
       ["dart:math" :as math])))


(def frame-interval-ms 16)
(def frame-step-seconds 0.016)


(defn cos
  [x]
  #?(:clj (Math/cos x)
     :cljs (js/Math.cos x)
     :cljd (math/cos x)))


(defn sin
  [x]
  #?(:clj (Math/sin x)
     :cljs (js/Math.sin x)
     :cljd (math/sin x)))


(def initial-state
  {:t 0.0,
   :animating? true,
   :camera {:fov 55.0,
            :near 0.1,
            :far 200.0,
            :translate [0 16 34],
            :rotate [-0.44 0 0]},
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


(def ^:private cube-wireframe
  {:vertices [[-0.5 -0.5 -0.5]
              [0.5 -0.5 -0.5]
              [0.5 0.5 -0.5]
              [-0.5 0.5 -0.5]
              [-0.5 -0.5 0.5]
              [0.5 -0.5 0.5]
              [0.5 0.5 0.5]
              [-0.5 0.5 0.5]],
   :edges [[0 1] [1 2] [2 3] [3 0]
           [4 5] [5 6] [6 7] [7 4]
           [0 4] [1 5] [2 6] [3 7]]})


(defn- sphere-wireframe
  [segment-count]
  (let [n segment-count
        r 0.5
        step (/ (* 2.0 #?(:clj Math/PI :cljs js/Math.PI :cljd math/pi)) n)
        circle (fn [point-fn] (mapv (fn [i] (point-fn (* i step))) (range n)))
        equator (circle (fn [t] [(* r (cos t)) 0.0 (* r (sin t))]))
        meridian (fn [phi]
                   (circle (fn [t]
                             [(* r (cos phi) (cos t))
                              (* r (sin t))
                              (* r (sin phi) (cos t))])))
        meridians (mapcat #(meridian (* % (/ #?(:clj Math/PI
                                                :cljs js/Math.PI
                                                :cljd math/pi)
                                             6.0)))
                          (range 6))
        lat-circle (fn [lat]
                     (circle (fn [t]
                               [(* r (cos lat) (cos t))
                                (* r (sin lat))
                                (* r (cos lat) (sin t))])))
        lat-circles (mapcat #(lat-circle (* % (/ #?(:clj Math/PI
                                                    :cljs js/Math.PI
                                                    :cljd math/pi)
                                                 6.0)))
                            [-2 -1 1 2])
        vertices (vec (concat equator meridians lat-circles))
        ring-edges (fn [start]
                     (mapv (fn [i] [(+ start i) (+ start (mod (inc i) n))])
                           (range n)))
        edges (vec (mapcat #(ring-edges (* % n))
                           (range (quot (count vertices) n))))]
    {:vertices vertices, :edges edges}))


(def ^:private planet-sphere-wireframe (sphere-wireframe 48))


(defn- rotate-x
  [[x y z] a]
  (let [c (cos a)
        s (sin a)]
    [x (- (* y c) (* z s)) (+ (* y s) (* z c))]))


(defn- rotate-y
  [[x y z] a]
  (let [c (cos a)
        s (sin a)]
    [(+ (* x c) (* z s)) y (- (* z c) (* x s))]))


(defn- rotate-z
  [[x y z] a]
  (let [c (cos a)
        s (sin a)]
    [(- (* x c) (* y s)) (+ (* x s) (* y c)) z]))


(defn- local-transform
  [body p]
  (let [[sx sy sz] (get body :scale [1 1 1])
        [rx ry rz] (get body :rotate [0 0 0])
        [tx ty tz] (get body :translate [0 0 0])
        [x y z] (-> [(double (* sx (nth p 0)))
                     (double (* sy (nth p 1)))
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
        (if parent-fn
          (parent-fn local)
          local)))))


(defn- draw-op
  [bodies id]
  (let [body (get bodies id)
        xf (body-transform-fn bodies id)
        shape (case (:kind body)
                :sphere planet-sphere-wireframe
                :cube cube-wireframe
                nil)]
    (when shape
      {:op/kind :draw3d/lines,
       :vertices (mapv xf (:vertices shape)),
       :edges (:edges shape),
       :color (:color body)})))


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
            :camera3d/rotation (mapv double (get camera :rotate [0 0 0]))}
           {:op/kind :state/depth-test, :enabled true}
           {:op/kind :state/depth-write, :enabled true}]
          body-ops)))


(defn advance-state
  [state dt]
  (if-not (:animating? state)
    state
    (let [t (+ (double (:t state 0.0)) (double dt))]
      (-> state
          (assoc :t t)
          (assoc-in [:bodies :mercury :translate]
                    [(* 5.5 (cos (* t 3.4))) 0 (* 5.5 (sin (* t 3.4)))])
          (assoc-in [:bodies :venus :translate]
                    [(* 8.0 (cos (* t 2.2))) 0 (* 8.0 (sin (* t 2.2)))])
          (assoc-in [:bodies :earth :translate]
                    [(* 11.0 (cos (* t 1.35))) 0 (* 11.0 (sin (* t 1.35)))])
          (assoc-in [:bodies :mars :translate]
                    [(* 14.5 (cos (* t 0.75))) 0 (* 14.5 (sin (* t 0.75)))])
          (assoc-in [:bodies :sun :rotate] [0 (* t 0.9) 0])
          (assoc-in [:bodies :moon-pivot :rotate] [0 (* t 5.0) 0])))))
