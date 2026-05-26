(ns datomworld.demo.voxel-scene
  "Pure-data scene for the voxel demo. Builds a face-culled chunk mesh
   from a block grid and emits the postgraphics frame program for a
   first-person camera + that single chunk."
  #?(:cljd
     (:require
       ["dart:math" :as math])))


(def frame-interval-ms 16)


(def chunk-size 16)


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


;; --- block table ---

(def air 0)
(def stone 1)
(def grass 2)
(def dirt 3)


(def ^:private block-color
  {stone [0.55 0.55 0.58 1.0],
   grass [0.34 0.66 0.30 1.0],
   dirt [0.45 0.32 0.21 1.0]})


;; Per-face brightness fakes ambient occlusion under the no-lighting shader:
;; top faces bright, sides medium, bottom dark.
(def ^:private face-shade
  {:top 1.00, :north 0.78, :south 0.78, :east 0.86, :west 0.86, :bottom 0.55})


;; --- demo chunk ---

(defn- block-at
  "Procedural terrain over a chunk-size cube: a flat dirt layer with a
   sine-wave grass surface and stone beneath."
  [x y z]
  (let [height
        (+ 6 (int (* 1.5 (sin (* 0.5 x)))) (int (* 1.5 (cos (* 0.4 z)))))]
    (cond (> y height) air
          (= y height) grass
          (> y (- height 3)) dirt
          :else stone)))


(defn- ->index
  [x y z]
  (+ (* x chunk-size chunk-size) (* y chunk-size) z))


(defn build-chunk
  "Returns a flat int vector of (chunk-size)^3 block ids."
  []
  (vec (for [x (range chunk-size)
             y (range chunk-size)
             z (range chunk-size)]
         (block-at x y z))))


(defn- block-at-in
  [chunk x y z]
  (if (or (< x 0)
          (< y 0)
          (< z 0)
          (>= x chunk-size)
          (>= y chunk-size)
          (>= z chunk-size))
    air
    (nth chunk (->index x y z))))


(defn- solid?
  [chunk x y z]
  (not= air (block-at-in chunk x y z)))


;; --- collision ---

(defn- floor-int
  [x]
  #?(:clj (long (Math/floor x))
     :cljs (js/Math.floor x)
     :cljd (.floor ^num x)))


(def ^:private player-half-width 0.3)
(def ^:private player-eye-height 1.5)
(def ^:private player-head-clearance 0.2)


(defn- aabb-overlaps-solid?
  "True when any block cell whose unit cube overlaps the AABB is solid.
   The AABB is given as [min-x min-y min-z max-x max-y max-z]. Block (i j k)
   spans [i,i+1)×[j,j+1)×[k,k+1), so cell ranges are floor(min) … floor(max-ε)."
  [chunk [x0 y0 z0 x1 y1 z1]]
  (let [eps 1.0e-6
        ix0 (floor-int x0)
        iy0 (floor-int y0)
        iz0 (floor-int z0)
        ix1 (floor-int (- x1 eps))
        iy1 (floor-int (- y1 eps))
        iz1 (floor-int (- z1 eps))]
    (loop [x ix0]
      (if (> x ix1)
        false
        (if (loop [y iy0]
              (if (> y iy1)
                false
                (if (loop [z iz0]
                      (if (> z iz1)
                        false
                        (if (solid? chunk x y z) true (recur (inc z)))))
                  true
                  (recur (inc y)))))
          true
          (recur (inc x)))))))


(defn- player-aabb-at
  [[x y z]]
  (let [hw player-half-width
        feet-y (- y player-eye-height)
        head-y (+ y player-head-clearance)]
    [(- x hw) feet-y (- z hw) (+ x hw) head-y (+ z hw)]))


(defn- collide-axis
  "Returns new-pos after attempting to move along axis (0 x, 1 y, 2 z) by
   delta from start-pos. Reverts the move on that axis if the player AABB
   would intersect a solid block. Chunk may be nil to disable collision."
  [start-pos chunk axis delta]
  (let [trial (assoc start-pos axis (+ (nth start-pos axis) delta))]
    (if (or (nil? chunk) (zero? delta))
      trial
      (if (aabb-overlaps-solid? chunk (player-aabb-at trial))
        start-pos
        trial))))


;; Face quads are emitted in CCW order so the renderer treats them as
;; front-facing under its default winding. Each face entry is
;; [normal corner0 corner1 corner2 corner3] in local cube coordinates
;; (cube spans [0,1]^3).
(def ^:private faces
  {:top {:normal [0.0 1.0 0.0], :quad [[0 1 0] [0 1 1] [1 1 1] [1 1 0]]},
   :bottom {:normal [0.0 -1.0 0.0], :quad [[0 0 1] [0 0 0] [1 0 0] [1 0 1]]},
   :north {:normal [0.0 0.0 -1.0], :quad [[1 0 0] [0 0 0] [0 1 0] [1 1 0]]},
   :south {:normal [0.0 0.0 1.0], :quad [[0 0 1] [1 0 1] [1 1 1] [0 1 1]]},
   :east {:normal [1.0 0.0 0.0], :quad [[1 0 1] [1 0 0] [1 1 0] [1 1 1]]},
   :west {:normal [-1.0 0.0 0.0], :quad [[0 0 0] [0 0 1] [0 1 1] [0 1 0]]}})


(def ^:private face-neighbour
  {:top [0 1 0],
   :bottom [0 -1 0],
   :north [0 0 -1],
   :south [0 0 1],
   :east [1 0 0],
   :west [-1 0 0]})


(defn- shade-color
  [[r g b a] k]
  (let [s (face-shade k)] [(* r s) (* g s) (* b s) a]))


(defn chunk-mesh
  "Face-culled mesh for the chunk. Skips faces between two solid voxels.
   Returns {:vertices :uvs :normals :colors :indices}, all as plain
   vectors so the renderer can consume them as data."
  [chunk]
  (loop [coords (for [x (range chunk-size)
                      y (range chunk-size)
                      z (range chunk-size)]
                  [x y z])
         vertices []
         uvs []
         normals []
         colors []
         indices []]
    (if-let [[x y z] (first coords)]
      (let [block (block-at-in chunk x y z)]
        (if (= air block)
          (recur (rest coords) vertices uvs normals colors indices)
          (let [base-color (block-color block [1.0 0.0 1.0 1.0])
                [vs uvs' ns cs is]
                (reduce
                  (fn [[vs uvs' ns cs is] [face-key {:keys [normal quad]}]]
                    (let [[nx ny nz] (face-neighbour face-key)]
                      (if (solid? chunk (+ x nx) (+ y ny) (+ z nz))
                        [vs uvs' ns cs is]
                        (let [base (count vs)
                              col (shade-color base-color face-key)
                              add-vert
                              (fn [[vs uvs' ns cs] [cx cy cz] uv]
                                [(conj vs
                                       [(double (+ x cx)) (double (+ y cy))
                                        (double (+ z cz))]) (conj uvs' uv)
                                 (conj ns normal) (conj cs col)])
                              [v0 v1 v2 v3] quad
                              [vs uvs' ns cs] (-> [vs uvs' ns cs]
                                                  (add-vert v0 [0.0 0.0])
                                                  (add-vert v1 [1.0 0.0])
                                                  (add-vert v2 [1.0 1.0])
                                                  (add-vert v3 [0.0 1.0]))]
                          [vs uvs' ns cs
                           (conj is
                                 [base (+ base 1) (+ base 2)]
                                 [base (+ base 2) (+ base 3)])]))))
                  [vertices uvs normals colors indices]
                  faces)]
            (recur (rest coords) vs uvs' ns cs is))))
      {:vertices vertices,
       :uvs uvs,
       :normals normals,
       :colors colors,
       :indices indices})))


;; --- player / camera ---

(defn forward-vec
  "Horizontal forward vector (no pitch) for the given yaw. Used to apply
   WASD motion relative to where the camera is facing."
  [yaw]
  [(sin yaw) 0.0 (- (cos yaw))])


(defn right-vec
  "Horizontal right vector for the given yaw."
  [yaw]
  [(cos yaw) 0.0 (sin yaw)])


(defn integrate-motion
  "Advances player state by dt seconds. keys-down is a set of keywords:
   :forward :back :left :right :up :down :look-left :look-right :look-up
   :look-down. When chunk is supplied, motion is clamped per-axis so the
   player AABB does not intersect any solid block (passing nil disables
   collision)."
  ([player keys-down dt] (integrate-motion player keys-down dt nil))
  ([{:keys [pos yaw pitch], :as player} keys-down dt chunk]
   (let [move-speed 6.0
         look-speed 1.6
         [fx _ fz] (forward-vec yaw)
         [rx _ rz] (right-vec yaw)
         dz (cond-> 0.0
              (keys-down :forward) (+ 1.0)
              (keys-down :back) (- 1.0))
         dx (cond-> 0.0
              (keys-down :right) (+ 1.0)
              (keys-down :left) (- 1.0))
         dy (cond-> 0.0
              (keys-down :up) (+ 1.0)
              (keys-down :down) (- 1.0))
         dyaw (cond-> 0.0
                (keys-down :look-right) (+ 1.0)
                (keys-down :look-left) (- 1.0))
         dpitch (cond-> 0.0
                  (keys-down :look-up) (+ 1.0)
                  (keys-down :look-down) (- 1.0))
         step (* move-speed dt)
         move-x (+ (* fx dz step) (* rx dx step))
         move-y (* dy step)
         move-z (+ (* fz dz step) (* rz dx step))
         new-yaw (+ yaw (* dyaw look-speed dt))
         max-pitch (* 0.49 pi)
         new-pitch (-> (+ pitch (* dpitch look-speed dt))
                       (max (- max-pitch))
                       (min max-pitch))
         new-pos (-> pos
                     (collide-axis chunk 1 move-y)
                     (collide-axis chunk 0 move-x)
                     (collide-axis chunk 2 move-z))]
     (assoc player
            :pos new-pos
            :yaw new-yaw
            :pitch new-pitch))))


(def default-player {:pos [8.0 12.0 24.0], :yaw 0.0, :pitch -0.35})


(defn frame-from-state
  "Builds the postgraphics frame program from player state and a prebuilt
   chunk mesh."
  [{:keys [pos yaw pitch]} mesh]
  [{:op/kind :frame/clear, :color [0.55 0.72 0.92 1.0]}
   {:op/kind :camera3d/set,
    :camera3d/projection :perspective,
    :camera3d/fov 70.0,
    :camera3d/near 0.05,
    :camera3d/far 200.0,
    :camera3d/position pos,
    :camera3d/rotation [pitch yaw 0.0]}
   {:op/kind :state/depth-test, :enabled true}
   {:op/kind :state/depth-write, :enabled true}
   (assoc mesh
          :op/kind :draw3d/mesh
          :fill [1.0 1.0 1.0 1.0])])
