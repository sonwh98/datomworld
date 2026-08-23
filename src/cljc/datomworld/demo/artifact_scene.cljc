(ns datomworld.demo.artifact-scene
  #?(:cljd (:require ["dart:math" :as math])))


(def min-zoom 3.0)
(def max-zoom 30.0)
(def pulse-decay-factor 0.9)
(def phase-step 0.08)


(def initial-state
  {:cam-rot-x 0.0, :cam-rot-y 0.0, :zoom 10.0, :pulse 0.0, :phase 0.0})


(defn- sin
  [x]
  #?(:clj (Math/sin x)
     :cljs (js/Math.sin x)
     :cljd (math/sin x)))


(defn- cos
  [x]
  #?(:clj (Math/cos x)
     :cljs (js/Math.cos x)
     :cljd (math/cos x)))


(defn- finite-number?
  [x]
  (and (number? x)
       #?(:cljs (js/isFinite x)
          :cljd (.-isFinite ^num x)
          :clj (Double/isFinite (double x)))))


(defn- finite-number
  [x fallback]
  (if (finite-number? x) (double x) fallback))


(defn- clamp
  [x lo hi]
  (max lo (min hi x)))


(defn- cube-mesh
  [sx sy sz]
  (let [x (/ sx 2.0)
        y (/ sy 2.0)
        z (/ sz 2.0)
        faces [[[[(- x) (- y) (- z)] [x (- y) (- z)] [x y (- z)]
                 [(- x) y (- z)]] [0.0 0.0 -1.0]]
               [[[(- x) (- y) z] [x (- y) z] [x y z] [(- x) y z]] [0.0 0.0 1.0]]
               [[[(- x) (- y) (- z)] [x (- y) (- z)] [x (- y) z]
                 [(- x) (- y) z]] [0.0 -1.0 0.0]]
               [[[(- x) y (- z)] [(- x) y z] [x y z] [x y (- z)]] [0.0 1.0 0.0]]
               [[[x (- y) (- z)] [x y (- z)] [x y z] [x (- y) z]] [1.0 0.0 0.0]]
               [[[(- x) (- y) (- z)] [(- x) (- y) z] [(- x) y z]
                 [(- x) y (- z)]] [-1.0 0.0 0.0]]]
        vertices (vec (mapcat first faces))
        normals (vec (mapcat (fn [[_ normal]] (repeat 4 normal)) faces))
        indices (vec (mapcat (fn [face]
                               (let [base (* 4 face)]
                                 [[base (+ base 2) (+ base 1)]
                                  [base (+ base 3) (+ base 2)]]))
                             (range (count faces))))]
    {:vertices vertices, :normals normals, :indices indices}))


(defn- translate-mesh
  [mesh [tx ty tz]]
  (update mesh
          :vertices
          (fn [vertices]
            (mapv (fn [[x y z]] [(+ x tx) (+ y ty) (+ z tz)]) vertices))))


(defn- octahedron-mesh
  [r]
  (let [top [0.0 r 0.0]
        bottom [0.0 (- r) 0.0]
        front [0.0 0.0 r]
        back [0.0 0.0 (- r)]
        right [r 0.0 0.0]
        left [(- r) 0.0 0.0]
        faces [[top front right] [top right back] [top back left]
               [top left front] [bottom right front] [bottom back right]
               [bottom left back] [bottom front left]]
        cross (fn [[x0 y0 z0] [x1 y1 z1] [x2 y2 z2]]
                (let [ux (- x1 x0)
                      uy (- y1 y0)
                      uz (- z1 z0)
                      vx (- x2 x0)
                      vy (- y2 y0)
                      vz (- z2 z0)
                      nx (- (* uy vz) (* uz vy))
                      ny (- (* uz vx) (* ux vz))
                      nz (- (* ux vy) (* uy vx))
                      l #?(:clj (Math/sqrt (+ (* nx nx) (* ny ny) (* nz nz)))
                           :cljs (js/Math.sqrt
                                   (+ (* nx nx) (* ny ny) (* nz nz)))
                           :cljd (math/sqrt (+ (* nx nx) (* ny ny) (* nz nz))))]
                  [(/ nx l) (/ ny l) (/ nz l)]))
        vertices (vec (mapcat identity faces))
        normals (vec (mapcat (fn [face] (let [n (apply cross face)] [n n n]))
                             faces))
        indices (vec (map (fn [i] [i (+ i 1) (+ i 2)])
                          (range 0 (count vertices) 3)))]
    {:vertices vertices, :normals normals, :indices indices}))


(def ^:private floor-mesh (cube-mesh 18.0 0.2 18.0))
(def ^:private halo-mesh (cube-mesh 3.2 4.2 0.08))
(def ^:private artifact-mesh (octahedron-mesh 1.5))


(defn- safe-pulse
  [state]
  (clamp (finite-number (:pulse state) 0.0) 0.0 1.0))


(defn build-frame
  [state viewport-size]
  (let [[width height] viewport-size
        width (max 1.0 (finite-number width 1.0))
        height (max 1.0 (finite-number height 1.0))
        pitch (clamp (finite-number (:cam-rot-x state) 0.0) -1.4835 1.4835)
        yaw (finite-number (:cam-rot-y state) 0.0)
        zoom (clamp (finite-number (:zoom state) 10.0) min-zoom max-zoom)
        pulse (safe-pulse state)
        aspect (/ width height)
        position [(* zoom (cos pitch) (sin yaw)) (* zoom (sin pitch))
                  (* zoom (cos pitch) (cos yaw))]
        camera {:op/kind :camera3d/set,
                :camera3d/projection :perspective,
                :camera3d/fov 55.0,
                :camera3d/near 0.1,
                :camera3d/far 200.0,
                :camera3d/aspect aspect,
                :camera3d/position (mapv double position),
                :camera3d/rotation [(- pitch) yaw 0.0]}
        artifact (assoc (translate-mesh artifact-mesh [0.0 1.5 0.0])
                        :op/kind :draw3d/mesh
                        :fill [(+ 0.02 (* 0.08 pulse)) (+ 0.12 (* 0.35 pulse))
                               (+ 0.22 (* 0.55 pulse)) 1.0]
                        :material/specular [0.75 0.85 0.95]
                        :material/shininess 32.0
                        :material/emissive [0.0
                                            (clamp (+ 0.10 (* 0.70 pulse)) 0.0 1.0)
                                            (clamp (+ 0.25 (* 0.75 pulse)) 0.0 1.0)])
        halo (assoc (translate-mesh halo-mesh [0.0 1.5 -1.05])
                    :op/kind :draw3d/mesh
                    :fill [(* 0.08 pulse) (+ 0.16 (* 0.38 pulse))
                           (+ 0.30 (* 0.62 pulse)) 1.0]
                    :material/specular [0.0 0.0 0.0]
                    :material/shininess 1.0
                    :material/emissive [0.0 (* 0.45 pulse) pulse])
        floor (assoc (translate-mesh floor-mesh [0.0 -0.1 0.0])
                     :op/kind :draw3d/mesh
                     :fill [0.35 0.32 0.28 1.0]
                     :material/specular [0.4 0.38 0.35]
                     :material/shininess 16.0)]
    [{:op/kind :frame/clear, :color [0.015 0.018 0.04 1.0]} camera
     {:op/kind :state/depth-test, :enabled true}
     {:op/kind :state/depth-write, :enabled true}
     {:op/kind :state/lighting-enable, :enabled true}
     {:op/kind :light/ambient, :color [0.14 0.18 0.26]}
     {:op/kind :light/point,
      :position [2.5 5.0 4.0],
      :color [0.7 0.85 1.0],
      :intensity 2.0,
      :range 30.0} floor halo artifact]))
