(ns dao.postgraphics.packing
  "The GPU data contract: turns a canonical lowered draw into the uniform and
   buffer layouts both GPU backends upload (web/gpu WGSL + flutter/gpu GLSL).
   The byte layout lives here once; each platform owns the destination typed
   array and injects a put! writer, mirroring the rasterizer's pixel sinks.
   Lighting uniforms reproduce raster/blinn-phong, so the shaders match the CPU."
  (:require
    [dao.postgraphics.math :as math]
    [dao.postgraphics.raster :as raster]))


;; ---------------------------------------------------------------------------
;; Uniform packing (shared by web/gpu WGSL + flutter/gpu GLSL)
;; ---------------------------------------------------------------------------
;;
;; Both GPU backends light their shaders identically to raster/blinn-phong, so
;; the uniform byte layout lives here once.  Two blocks:
;;   packed-vertex-uniforms  → mvp, model, modelInv (the vertex stage)
;;   packed-lighting-block   → camera, material, lights (the fragment stage)
;; WGSL packs both into one struct; Impeller binds them as two stage buffers.

(def max-packed-lights
  "GPU shaders carry a fixed-size light array; scenes with more lights than this
   are truncated on the GPU paths (a documented limitation — the software path
   has no cap).  Eight covers every bundled demo."
  8)


(defn- light-kind-code
  "Float discriminator the shaders branch on: 0 ambient, 1 directional, 2 point."
  [kind]
  (case kind
    :directional 1.0
    :point 2.0
    0.0))


(defn packed-vertex-uniforms
  "48 doubles for the vertex stage: mvp(16) ++ model(16) ++ modelInv(16).
   modelInv = mat4-invert(model) so the shader builds the inverse-transpose
   normal matrix, matching raster/prepare-vertex exactly."
  [draw]
  (let [model (:model-m draw)]
    (-> (mapv double (:mvp draw))
        (into (map double model))
        (into (map double (math/mat4-invert model))))))


(defn packed-lighting-block
  "108 doubles for the fragment stage, encoding a draw's lighting state so the
   shader can reproduce blinn-phong + sRGB.  Layout:
     [cx cy cz lightCount   KsR KsG KsB shininess   KeR KeG KeB lightingEnabled
      <max-packed-lights × 12>]
   each light slot: [cR cG cB intensity  vX vY vZ range  kind 0 0 0]
   (v = direction for directional, position for point).  Emissive (Ke) and the
   per-vertex colour stay sRGB-encoded; the shader linearises them, just as
   raster/shade-mesh-fragment does on the CPU."
  [draw]
  (let [op (:op draw)
        [cx cy cz] (or (:camera-pos draw) [0.0 0.0 0.0])
        lights (vec (:lights draw))
        n (min (count lights) max-packed-lights)
        [ksr ksg ksb] (get op :material/specular [0.0 0.0 0.0])
        shininess (double (get op :material/shininess 32.0))
        [ker keg keb] (get op :material/emissive [0.0 0.0 0.0])
        lit (if (:lighting-enabled draw) 1.0 0.0)
        head [(double cx) (double cy) (double cz) (double n) (double ksr)
              (double ksg) (double ksb) shininess (double ker) (double keg)
              (double keb) lit]]
    (reduce (fn [acc i]
              (if (< i n)
                (let [l (nth lights i)
                      [lr lg lb] (:color l)
                      intensity (double (get l :intensity 1.0))
                      kind (:kind l)
                      [vx vy vz range]
                      (case kind
                        :directional (let [[dx dy dz] (:direction l)]
                                       [dx dy dz 0.0])
                        :point (let [[px py pz] (:position l)]
                                 [px py pz (double (get l :range 100.0))])
                        [0.0 0.0 0.0 0.0])]
                  (conj acc
                        (double lr)
                        (double lg)
                        (double lb)
                        intensity
                        (double vx)
                        (double vy)
                        (double vz)
                        (double range)
                        (light-kind-code kind)
                        0.0
                        0.0
                        0.0))
                (conj acc 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0)))
            head
            (range max-packed-lights))))


;; ---------------------------------------------------------------------------
;; Buffer packing (shared layout, platform-owned typed arrays)
;; ---------------------------------------------------------------------------
;;
;; The interleaved vertex layout, index flattening, line-draw collapse, and
;; clear-colour scan are identical across both GPU backends.  They differ only
;; in the destination typed array (js/Float32Array vs Dart Float32List), so the
;; layout logic lives here and the platform injects a put! writer, mirroring
;; the
;; rasterizer's put-pixel!/depth-set! sinks.

(defn pack-vertex-floats!
  "Writes op's interleaved (pos.xyz uv.xy normal.xyz color.rgba) vertex floats —
   12 per vertex, attributes resolved through raster/vertex-attrs — into a
   platform typed array via (put! flat-index value).  Returns the float count."
  [op put!]
  (let [verts (:vertices op)
        n (count verts)]
    (dotimes [i n]
      (let [v (nth verts i)
            attrs (raster/vertex-attrs op i)
            uv (nth attrs 0)
            nrm (nth attrs 1)
            col (nth attrs 3)
            o (* i 12)]
        (put! o (double (nth v 0)))
        (put! (+ o 1) (double (nth v 1)))
        (put! (+ o 2) (double (nth v 2)))
        (put! (+ o 3) (double (nth uv 0)))
        (put! (+ o 4) (double (nth uv 1)))
        (put! (+ o 5) (double (nth nrm 0)))
        (put! (+ o 6) (double (nth nrm 1)))
        (put! (+ o 7) (double (nth nrm 2)))
        (put! (+ o 8) (double (nth col 0)))
        (put! (+ o 9) (double (nth col 1)))
        (put! (+ o 10) (double (nth col 2)))
        (put! (+ o 11) (double (nth col 3)))))
    (* n 12)))


(defn pack-indices!
  "Writes a sequence of index tuples (triangles [a b c] or edges [a b]) into a
   platform typed array via (put! flat-index value), flattened in order.  Any
   tuple arity is handled.  Returns the index count written."
  [tuples put!]
  (loop [i 0
         ts (seq tuples)]
    (if ts
      (let [t (first ts)
            c (count t)]
        (dotimes [j c] (put! (+ i j) (int (nth t j))))
        (recur (+ i c) (next ts)))
      i)))


(defn unlit-line-draw
  "Collapse a lowered line draw into the unlit, single-colour form both GPU
   backends submit: lighting forced off, and the op flattened so every vertex
   takes the op's :color via :fill (no per-vertex normals/uvs/colours)."
  [draw]
  (let [op (:op draw)]
    (assoc draw
           :lighting-enabled false
           :op (assoc op
                      :fill (get op :color [1.0 1.0 1.0 1.0])
                      :colors nil
                      :normals nil
                      :uvs nil))))


(defn clear-color
  "The clear colour for a lowered frame: the first :clear draw's colour in pass
   order, or opaque black when none is present.  Used by every backend (GPU
   render-pass clear value and software buffer init alike)."
  [lowered]
  (or (some (fn [pass]
              (some (fn [draw] (when (= :clear (:pipeline draw)) (:color draw)))
                    (:draws pass)))
            (:passes lowered))
      [0.0 0.0 0.0 1.0]))
