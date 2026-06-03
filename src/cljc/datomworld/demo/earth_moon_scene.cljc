(ns datomworld.demo.earth-moon-scene
  #?(:cljd
     (:require
       ["dart:math" :as math])))


(def frame-interval-ms 16)
(def frame-step-seconds 0.01815)
(def animation-seconds-per-second 1.134375)


(def ^:private pi
  #?(:clj Math/PI
     :cljs js/Math.PI
     :cljd math/pi))


(defn- cos
  [x]
  #?(:clj (Math/cos x)
     :cljs (js/Math.cos x)
     :cljd (math/cos x)))


(defn- sin
  [x]
  #?(:clj (Math/sin x)
     :cljs (js/Math.sin x)
     :cljd (math/sin x)))


(defn- rotate-x
  [[x y z] a]
  (let [c (cos a) s (sin a)] [x (- (* y c) (* z s)) (+ (* y s) (* z c))]))


(defn- rotate-y
  [[x y z] a]
  (let [c (cos a) s (sin a)] [(+ (* x c) (* z s)) y (- (* z c) (* x s))]))


(defn- rotate-z
  [[x y z] a]
  (let [c (cos a) s (sin a)] [(- (* x c) (* y s)) (+ (* x s) (* y c)) z]))


(defn- v+
  [a b]
  [(+ (nth a 0) (nth b 0)) (+ (nth a 1) (nth b 1)) (+ (nth a 2) (nth b 2))])


(defn- v*
  [s v]
  [(* s (nth v 0)) (* s (nth v 1)) (* s (nth v 2))])


(defn sphere-mesh
  "Returns an indexed unit sphere mesh with vertices, normals, UVs, and
   triangle indices. It is plain data so every renderer receives the same
   geometry."
  [lat-count lon-count]
  (let [lon-stride (inc lon-count)
        vertices (vec (for [lat (range (inc lat-count))
                            lon (range lon-stride)
                            :let [v (- (/ lat lat-count) 0.5)
                                  theta (* v pi)
                                  phi (* 2.0 pi (/ lon lon-count))
                                  ct (cos theta)]]
                        [(* ct (cos phi)) (sin theta) (* ct (sin phi))]))
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


(defn ring-mesh
  "Returns an indexed flat ring (annulus) mesh in the XZ plane. Normals point
   up the +Y axis. UVs map radial position to v (0 at inner edge, 1 at outer)
   and angular position to u (one full turn = 0..1)."
  [inner-radius outer-radius segments]
  (let [seg-count (inc segments)
        vertices (vec (for [i (range seg-count)
                            r [inner-radius outer-radius]]
                        (let [theta (* 2.0 pi (/ i segments))
                              cx (cos theta)
                              sz (sin theta)]
                          [(* r cx) 0.0 (* r sz)])))
        normals (vec (repeat (count vertices) [0.0 1.0 0.0]))
        uvs (vec (for [i (range seg-count) v [0.0 1.0]] [(/ i segments) v]))
        indices (vec (mapcat (fn [i]
                               (let [a (* i 2)
                                     b (inc a)
                                     c (+ a 2)
                                     d (+ a 3)]
                                 [[a b d] [a d c]]))
                             (range segments)))]
    {:vertices vertices, :normals normals, :uvs uvs, :indices indices}))


(def ^:private unit-sphere (sphere-mesh 35 70))
(def ^:private earth-rings (ring-mesh 2.6 3.8 96))


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


(defn frame-from-seconds
  "Builds the platform-independent postgraphics program for the Earth/Moon
   demo. The optional textures map passes opaque per-host texture handles
   (e.g. Flutter GPU textures) through to the mesh ops as :texture/source.
   When called without textures, the meshes render with their :fill color."
  ([seconds] (frame-from-seconds seconds nil))
  ([seconds {:keys [earth-tex moon-tex ring-tex]}]
   (let [earth (transform-sphere {:radius 2.0,
                                  :translate [0.0 0.0 0.0],
                                  :rotate [-0.41 seconds 0.0]}
                                 unit-sphere)
         orbit-a (* seconds 0.82)
         moon-pos [(* 4.7 (cos orbit-a)) (* 0.42 (sin orbit-a))
                   (* 2.0 (sin orbit-a))]
         moon-behind-earth? (neg? (nth moon-pos 2))
         moon (transform-sphere {:radius 0.54,
                                 :translate moon-pos,
                                 :rotate [0 (* seconds 1.3) 0]}
                                unit-sphere)
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
                      ;; Light travels toward -z so the camera-facing
                      ;; hemisphere (the day side) is lit under the
                      ;; software renderer. The GPU shaders are unlit and
                      ;; unaffected.
                      :direction [-0.4 0.7 -0.9],
                      :color [1.0 0.96 0.86],
                      :intensity 1.55}]
         earth-op (cond-> (assoc earth
                                 :op/kind :draw3d/mesh
                                 :fill [0.34 0.68 1.0 1.0]
                                 :material/specular [0.2 0.26 0.32]
                                 :material/shininess 20.0)
                    earth-tex (assoc :texture/source earth-tex))
         moon-op (cond-> (assoc moon
                                :op/kind :draw3d/mesh
                                :fill [0.72 0.72 0.68 1.0]
                                :material/specular [0.08 0.08 0.08]
                                :material/shininess 8.0)
                   moon-tex (assoc :texture/source moon-tex))
         ;; Rings sit in the XZ plane by construction; tilt around Z by
         ;; ~80° to bring them upright (the ring axis is then nearly
         ;; horizontal, so the loop appears as a vertical ellipse around
         ;; earth).
         rings (transform-sphere
                 {:radius 1.0, :translate [0.0 0.0 0.0], :rotate [0.0 0.0 1.4]}
                 earth-rings)
         rings-op (cond-> (assoc rings
                                 :op/kind :draw3d/mesh
                                 :fill [0.85 0.78 0.62 1.0]
                                 :material/specular [0.06 0.06 0.04]
                                 :material/shininess 4.0)
                    ;; When a procedural ring texture is supplied the band
                    ;; structure comes from the texture; set :fill to white
                    ;; so the texture's tan body shows through unscaled.
                    ring-tex (assoc :texture/source
                                    ring-tex :fill
                                    [1.0 1.0 1.0 1.0]))]
     (into base-frame
           (conj (if moon-behind-earth? [moon-op earth-op] [earth-op moon-op])
                 rings-op)))))
