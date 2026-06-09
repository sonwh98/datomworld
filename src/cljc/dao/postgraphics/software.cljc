(ns dao.postgraphics.software
  "CPU shading and rasterizer core, plus shared packing logic for GPU backends.
   No platform types — pixel and depth buffers are injected via callbacks so
   each platform (web/canvas, flutter/canvas) owns its own arrays."
  (:require
    [dao.postgraphics.math :as math]
    [dao.postgraphics.validation :as v]))


;; ---------------------------------------------------------------------------
;; sRGB ↔ linear
;; ---------------------------------------------------------------------------

(defn srgb->linear
  "Convert an sRGB [r g b a] colour to linear space.  Alpha passes through."
  [[r g b a]]
  (let [f (fn [c]
            (if (<= c 0.04045)
              (/ c 12.92)
              (math/mpow (/ (+ c 0.055) 1.055) 2.4)))]
    [(f (double r)) (f (double g)) (f (double b)) (double a)]))


(defn linear->srgb
  "Convert a linear [r g b a] colour to sRGB space.  Alpha passes through."
  [[r g b a]]
  (let [f (fn [c]
            (if (<= c 0.0031308)
              (* c 12.92)
              (- (* 1.055 (math/mpow c (/ 1.0 2.4))) 0.055)))]
    [(f (double r)) (f (double g)) (f (double b)) (double a)]))


;; ---------------------------------------------------------------------------
;; Per-vertex attribute resolution
;; ---------------------------------------------------------------------------

(defn vertex-attrs
  "Returns the resolved per-vertex attributes for vertex index i of op.
   Returns [uv normal world-pos color].  Per-vertex :colors take precedence
   over op :fill.  Defaults: uv [0 0], normal = vertex position, color = fill
   or [1 1 1 1]."
  [op i]
  (let [verts (:vertices op)
        v (nth verts i)
        default-color (get op :fill [1.0 1.0 1.0 1.0])
        per-vertex-colors (:colors op)]
    [(nth (:uvs op) i [0.0 0.0]) (nth (:normals op) i v) v
     (if per-vertex-colors (nth per-vertex-colors i) default-color)]))


;; ---------------------------------------------------------------------------
;; Texture sampling
;; ---------------------------------------------------------------------------

(defn- wrap-coord
  [c mode]
  (case mode
    :repeat (let [t (mod c 1.0)] (if (neg? t) (+ t 1.0) t))
    :mirror (let [t (mod c 2.0)] (if (< t 1.0) t (- 2.0 t)))
    :clamp (max 0.0 (min 1.0 c))
    (max 0.0 (min 1.0 c))))


(defn- texel-byte
  "Reads the byte at flat index idx from an RGBA buffer.  Uses aget for JS
   typed arrays / arrays (the web ImageData buffer, which `nth` would scan
   linearly), and nth for clj vectors and Dart Uint8List."
  [rgba idx]
  #?(:cljs (if (number? (.-length rgba)) (aget rgba idx) (nth rgba idx))
     :clj (nth rgba idx)
     :cljd (nth rgba idx)))


(defn- sample-rgba-at
  "Sample a single pixel from an RGBA flat array at integer texel coords.
   The array holds 0–255 byte values (the natural image/Uint8List storage);
   the result is normalized to [0,1]."
  [rgba w x y]
  (let [idx (* 4 (+ (* (int y) (int w)) (int x)))]
    [(/ (double (texel-byte rgba idx)) 255.0)
     (/ (double (texel-byte rgba (+ idx 1))) 255.0)
     (/ (double (texel-byte rgba (+ idx 2))) 255.0)
     (/ (double (texel-byte rgba (+ idx 3))) 255.0)]))


(defn sample-texture
  "Sample {:rgba [r g b a …] :width w :height h} at UV (u, v) with given
   :wrap and :filter.  :rgba holds 0–255 byte values; returns [r g b a] in
   [0,1]."
  [{:keys [rgba width height]} u v
   {:keys [wrap filter], :or {wrap :clamp, filter :linear}}]
  (let [w (int width)
        h (int height)
        u' (wrap-coord u wrap)
        v' (wrap-coord v wrap)
        tx (* u' (dec w))
        ty (* v' (dec h))]
    (if (= filter :nearest)
      (sample-rgba-at rgba w (+ tx 0.5) (+ ty 0.5))
      (let [x0 (int tx)
            y0 (int ty)
            x1 (min (inc x0) (dec w))
            y1 (min (inc y0) (dec h))
            fx (- tx x0)
            fy (- ty y0)
            c00 (sample-rgba-at rgba w x0 y0)
            c01 (sample-rgba-at rgba w x0 y1)
            c10 (sample-rgba-at rgba w x1 y0)
            c11 (sample-rgba-at rgba w x1 y1)]
        (mapv (fn [i]
                (+ (* (nth c00 i) (- 1 fx) (- 1 fy))
                   (* (nth c01 i) (- 1 fx) fy)
                   (* (nth c10 i) fx (- 1 fy))
                   (* (nth c11 i) fx fy)))
              (range 4))))))


;; ---------------------------------------------------------------------------
;; Blinn-Phong lighting (linear space)
;; ---------------------------------------------------------------------------

(defn blinn-phong
  "Compute Blinn-Phong lighting in linear space.  Returns [r g b] (no alpha).
   material: {:diffuse [r g b] :specular [r g b]? :shininess n? :emissive [r g b]?}
   lights:   sequence of {:kind :ambient|:directional|:point …}
   normal, world-pos: vec3.  camera-pos: vec3."
  [{:keys [diffuse specular shininess emissive], :or {shininess 32.0}} lights
   camera-pos normal world-pos]
  (let [Kd (or diffuse [1.0 1.0 1.0])
        Ks (or specular [0.0 0.0 0.0])
        Ke (or emissive [0.0 0.0 0.0])
        view-dir (math/vec3-normalize (math/vec3-sub camera-pos world-pos))
        [ar ag ab dr dg db sr sg sb]
        (reduce
          (fn [[ar ag ab dr dg db sr sg sb] light]
            (let [lc (:color light)
                  intensity (double (get light :intensity 1.0))]
              (case (:kind light)
                :ambient [(+ ar (* (nth lc 0) intensity))
                          (+ ag (* (nth lc 1) intensity))
                          (+ ab (* (nth lc 2) intensity)) dr dg db sr sg sb]
                :directional (let [L (math/vec3-normalize (:direction light))
                                   n-l (max 0.0 (math/dot3 normal L))
                                   H (math/vec3-normalize
                                       (math/vec3-add L view-dir))
                                   n-h (max 0.0 (math/dot3 normal H))
                                   spec (math/mpow n-h shininess)]
                               [ar ag ab (+ dr (* (nth lc 0) n-l intensity))
                                (+ dg (* (nth lc 1) n-l intensity))
                                (+ db (* (nth lc 2) n-l intensity))
                                (+ sr (* (nth lc 0) spec intensity))
                                (+ sg (* (nth lc 1) spec intensity))
                                (+ sb (* (nth lc 2) spec intensity))])
                :point
                (let [to-light (math/vec3-sub (:position light) world-pos)
                      dist (math/msqrt (math/dot3 to-light to-light))
                      range (double (get light :range 100.0))
                      L (math/vec3-normalize to-light)
                      n-l (max 0.0 (math/dot3 normal L))
                      H (math/vec3-normalize (math/vec3-add L view-dir))
                      n-h (max 0.0 (math/dot3 normal H))
                      spec (math/mpow n-h shininess)
                      atten (let [t (- 1.0 (/ dist range))]
                              (if (pos? t) (* t t) 0.0))]
                  [ar ag ab (+ dr (* (nth lc 0) n-l intensity atten))
                   (+ dg (* (nth lc 1) n-l intensity atten))
                   (+ db (* (nth lc 2) n-l intensity atten))
                   (+ sr (* (nth lc 0) spec intensity atten))
                   (+ sg (* (nth lc 1) spec intensity atten))
                   (+ sb (* (nth lc 2) spec intensity atten))])
                [ar ag ab dr dg db sr sg sb])))
          [0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0]
          lights)]
    [(+ (* (nth Kd 0) ar) (* (nth Kd 0) dr) (* (nth Ks 0) sr) (nth Ke 0))
     (+ (* (nth Kd 1) ag) (* (nth Kd 1) dg) (* (nth Ks 1) sg) (nth Ke 1))
     (+ (* (nth Kd 2) ab) (* (nth Kd 2) db) (* (nth Ks 2) sb) (nth Ke 2))]))


;; ---------------------------------------------------------------------------
;; Fragment shading
;; ---------------------------------------------------------------------------

(defn- clamp01
  [c]
  (max 0.0 (min 1.0 (double c))))


(defn shade-mesh-fragment
  "Shade a single fragment for a mesh draw.  Returns [r g b a] in sRGB,
   clamped to [0,1] so HDR lighting does not overflow the 8-bit sink.
   draw keys used: :op, :lights, :camera-pos, :lighting-enabled, :texture.
   Texture sampling :texture/wrap and :texture/filter are read from :op."
  [draw uv normal world-pos color]
  (let [op (:op draw)
        tex-source (:texture draw)
        tex-sample (when tex-source
                     (sample-texture tex-source
                                     (nth uv 0)
                                     (nth uv 1)
                                     {:wrap (get op :texture/wrap :clamp),
                                      :filter
                                      (get op :texture/filter :linear)}))
        ;; v4 §Texture Modulation: fragment.rgba = texture.rgba ×
        ;; color.rgba
        ;; (matches the GPU shader's `texColor * input.color`).  Untextured
        ;; fragments fall through to the per-vertex/fill colour unchanged.
        tex-result (if tex-sample
                     [(* (nth tex-sample 0) (nth color 0))
                      (* (nth tex-sample 1) (nth color 1))
                      (* (nth tex-sample 2) (nth color 2))
                      (* (nth tex-sample 3) (nth color 3))]
                     color)
        linear-color (srgb->linear tex-result)]
    (if (:lighting-enabled draw)
      (let [material (cond-> {:diffuse [(nth linear-color 0)
                                        (nth linear-color 1)
                                        (nth linear-color 2)]}
                       (get op :material/specular)
                       (assoc :specular (get op :material/specular))
                       (get op :material/shininess)
                       (assoc :shininess (get op :material/shininess))
                       (get op :material/emissive)
                       (assoc :emissive
                              (srgb->linear (conj (vec (get op :material/emissive))
                                                  1.0))))
            lit (blinn-phong material
                             (:lights draw)
                             (:camera-pos draw)
                             normal
                             world-pos)
            result [(nth lit 0) (nth lit 1) (nth lit 2) (nth linear-color 3)]
            srgb (linear->srgb result)]
        [(clamp01 (nth srgb 0)) (clamp01 (nth srgb 1)) (clamp01 (nth srgb 2))
         (clamp01 (nth srgb 3))])
      [(clamp01 (nth tex-result 0)) (clamp01 (nth tex-result 1))
       (clamp01 (nth tex-result 2)) (clamp01 (nth tex-result 3))])))


;; ---------------------------------------------------------------------------
;; Uniform packing (shared by web/gpu WGSL + flutter/gpu GLSL)
;; ---------------------------------------------------------------------------
;;
;; Both GPU backends light their shaders identically to blinn-phong above, so
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
   normal matrix, matching prepare-vertex exactly."
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
   shade-mesh-fragment does on the CPU."
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
   12 per vertex, attributes resolved through vertex-attrs — into a platform
   typed array via (put! flat-index value).  Returns the float count written."
  [op put!]
  (let [verts (:vertices op)
        n (count verts)]
    (dotimes [i n]
      (let [v (nth verts i)
            attrs (vertex-attrs op i)
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


;; ---------------------------------------------------------------------------
;; Triangle rasterizer
;; ---------------------------------------------------------------------------

(defn- edge-fn
  [ax ay bx by px py]
  (- (* (- px ax) (- by ay)) (* (- py ay) (- bx ax))))


(defn rasterize-triangle!
  "Rasterize a triangle.  v0-v2 are projected vertices with :x :y :z :inv-w
   and per-vertex attributes (:uv :normal :world-pos :color).  Sink is a map
   of callbacks: {:put-pixel! :depth-get :depth-set! :viewport-w :viewport-h
   :clips :depth-test? :depth-write? :shade-fn}.  shade-fn is called as
   (shade-fn uv normal world-pos color) and must return [r g b a]."
  [v0 v1 v2
   {:keys [put-pixel! depth-get depth-set! viewport-w viewport-h clips
           depth-test? depth-write? shade-fn]}]
  (let [raw-area (edge-fn (:x v0) (:y v0) (:x v1) (:y v1) (:x v2) (:y v2))]
    (when (> (math/mabs raw-area) v/EPSILON)
      ;; Normalize to positive winding by swapping v1/v2 when needed, so
      ;; the top-left fill rule below applies consistently; both input
      ;; windings still rasterize identically.
      (let [[v0 v1 v2 area]
            (if (neg? raw-area) [v0 v2 v1 (- raw-area)] [v0 v1 v2 raw-area])
            x0 (:x v0)
            y0 (:y v0)
            z0 (:z v0)
            iw0 (:inv-w v0)
            x1 (:x v1)
            y1 (:y v1)
            z1 (:z v1)
            iw1 (:inv-w v1)
            x2 (:x v2)
            y2 (:y v2)
            z2 (:z v2)
            iw2 (:inv-w v2)
            ;; Pre-extract per-vertex attribute components outside the
            ;; pixel loop so the hot path below uses direct scalar
            ;; arithmetic — no closures, no mapv/range allocations.
            uv0-0 (nth (:uv v0) 0)
            uv0-1 (nth (:uv v0) 1)
            uv1-0 (nth (:uv v1) 0)
            uv1-1 (nth (:uv v1) 1)
            uv2-0 (nth (:uv v2) 0)
            uv2-1 (nth (:uv v2) 1)
            n0-0 (nth (:normal v0) 0)
            n0-1 (nth (:normal v0) 1)
            n0-2 (nth (:normal v0) 2)
            n1-0 (nth (:normal v1) 0)
            n1-1 (nth (:normal v1) 1)
            n1-2 (nth (:normal v1) 2)
            n2-0 (nth (:normal v2) 0)
            n2-1 (nth (:normal v2) 1)
            n2-2 (nth (:normal v2) 2)
            p0-0 (nth (:world-pos v0) 0)
            p0-1 (nth (:world-pos v0) 1)
            p0-2 (nth (:world-pos v0) 2)
            p1-0 (nth (:world-pos v1) 0)
            p1-1 (nth (:world-pos v1) 1)
            p1-2 (nth (:world-pos v1) 2)
            p2-0 (nth (:world-pos v2) 0)
            p2-1 (nth (:world-pos v2) 1)
            p2-2 (nth (:world-pos v2) 2)
            c0-0 (nth (:color v0) 0)
            c0-1 (nth (:color v0) 1)
            c0-2 (nth (:color v0) 2)
            c0-3 (nth (:color v0) 3)
            c1-0 (nth (:color v1) 0)
            c1-1 (nth (:color v1) 1)
            c1-2 (nth (:color v1) 2)
            c1-3 (nth (:color v1) 3)
            c2-0 (nth (:color v2) 0)
            c2-1 (nth (:color v2) 1)
            c2-2 (nth (:color v2) 2)
            c2-3 (nth (:color v2) 3)
            ;; axis-aligned bounding box
            min-x (max 0.0 (math/mfloor (min x0 x1 x2)))
            max-x (min (dec viewport-w) (math/mfloor (max x0 x1 x2)))
            min-y (max 0.0 (math/mfloor (min y0 y1 y2)))
            max-y (min (dec viewport-h) (math/mfloor (max y0 y1 y2)))
            ;; Top-left fill rule: boundary pixels of a shared edge belong
            ;; to exactly one of the adjacent triangles.  Include the
            ;; boundary (bias -EPSILON) for top/left edges, exclude it
            ;; (strict >, bias 0) otherwise — so seams render once, not
            ;; twice.
            top-left? (fn [dx dy] (or (> dy 0.0) (and (= dy 0.0) (< dx 0.0))))
            bias0 (if (top-left? (- x1 x2) (- y1 y2)) (- v/EPSILON) 0.0)
            bias1 (if (top-left? (- x2 x0) (- y2 y0)) (- v/EPSILON) 0.0)
            bias2 (if (top-left? (- x0 x1) (- y0 y1)) (- v/EPSILON) 0.0)]
        (loop [py (int min-y)]
          (when (<= py (int max-y))
            (loop [px (int min-x)]
              (when (<= px (int max-x))
                (let [cx (+ 0.5 (double px))
                      cy (+ 0.5 (double py))
                      w0 (edge-fn x1 y1 x2 y2 cx cy)
                      w1 (edge-fn x2 y2 x0 y0 cx cy)
                      w2 (edge-fn x0 y0 x1 y1 cx cy)]
                  (when (and (> w0 bias0) (> w1 bias1) (> w2 bias2))
                    (when (or (empty? clips)
                              (some (fn [[cx' cy' cw ch]]
                                      (and (>= px cx')
                                           (>= py cy')
                                           (< px (+ cx' cw))
                                           (< py (+ cy' ch))))
                                    clips))
                      (let [b0 (/ w0 area)
                            b1 (/ w1 area)
                            b2 (/ w2 area)
                            z-interp (+ (* b0 z0) (* b1 z1) (* b2 z2))]
                        (when (or (not depth-test?)
                                  (let [current (depth-get px py)]
                                    (< z-interp current)))
                          (when depth-write? (depth-set! px py z-interp))
                          (let [denom (+ (* b0 iw0) (* b1 iw1) (* b2 iw2))
                                c0 (/ (* b0 iw0) denom)
                                c1 (/ (* b1 iw1) denom)
                                c2 (/ (* b2 iw2) denom)
                                ;; All per-fragment attribute interpolation
                                ;; is direct scalar arithmetic — no
                                ;; closures, no mapv/range allocations in
                                ;; the hot path.
                                iu0 (+ (* c0 uv0-0) (* c1 uv1-0) (* c2 uv2-0))
                                iu1 (+ (* c0 uv0-1) (* c1 uv1-1) (* c2 uv2-1))
                                in0 (+ (* c0 n0-0) (* c1 n1-0) (* c2 n2-0))
                                in1 (+ (* c0 n0-1) (* c1 n1-1) (* c2 n2-1))
                                in2 (+ (* c0 n0-2) (* c1 n1-2) (* c2 n2-2))
                                ip0 (+ (* c0 p0-0) (* c1 p1-0) (* c2 p2-0))
                                ip1 (+ (* c0 p0-1) (* c1 p1-1) (* c2 p2-1))
                                ip2 (+ (* c0 p0-2) (* c1 p1-2) (* c2 p2-2))
                                ic0 (+ (* c0 c0-0) (* c1 c1-0) (* c2 c2-0))
                                ic1 (+ (* c0 c0-1) (* c1 c1-1) (* c2 c2-1))
                                ic2 (+ (* c0 c0-2) (* c1 c1-2) (* c2 c2-2))
                                ic3 (+ (* c0 c0-3) (* c1 c1-3) (* c2 c2-3))
                                uv [iu0 iu1]
                                normal (math/vec3-normalize [in0 in1 in2])
                                world-pos [ip0 ip1 ip2]
                                color [ic0 ic1 ic2 ic3]
                                [r g b a] (shade-fn uv normal world-pos color)]
                            (put-pixel! px py r g b a))))))
                  (recur (inc px)))))
            (recur (inc py))))))))


;; ---------------------------------------------------------------------------
;; Line rasterizer (DDA)
;; ---------------------------------------------------------------------------

(defn rasterize-line!
  "Rasterize a line segment from v0 to v1 (projected vertices).  DDA with
   depth test and write."
  [v0 v1
   {:keys [put-pixel! depth-get depth-set! viewport-w viewport-h clips
           depth-test? depth-write? color-fn]}]
  (let [x0 (:x v0)
        y0 (:y v0)
        z0 (:z v0)
        x1 (:x v1)
        y1 (:y v1)
        z1 (:z v1)
        dx (- x1 x0)
        dy (- y1 y0)
        steps (max (math/mabs dx) (math/mabs dy))
        steps (if (< steps 1.0) 1.0 steps)]
    (loop [i 0.0]
      (when (<= i steps)
        (let [t (/ i steps)
              x (+ x0 (* dx t))
              y (+ y0 (* dy t))
              z (+ z0 (* (- z1 z0) t))
              px (int x)
              py (int y)]
          (when (and (>= px 0) (< px viewport-w) (>= py 0) (< py viewport-h))
            (when (or (empty? clips)
                      (some (fn [[cx cy cw ch]]
                              (and (>= px cx)
                                   (>= py cy)
                                   (< px (+ cx cw))
                                   (< py (+ cy ch))))
                            clips))
              (when (or (not depth-test?) (< z (depth-get px py)))
                (when depth-write? (depth-set! px py z))
                (let [[r g b a] (color-fn t)] (put-pixel! px py r g b a)))))
          (recur (+ i 1.0)))))))


;; ---------------------------------------------------------------------------
;; 3D render pass
;; ---------------------------------------------------------------------------

(defn- prepare-vertex
  "Transform an object-space vertex into clip space plus its world-space
   lighting bundle [uv normal world-pos color].  world-pos uses model-m;
   normal uses normal-m (the precomputed inverse-transpose of model-m, so the
   4x4 inversion happens once per draw).  Returns {:clip [x y z w] :bundle}."
  [mvp model-m normal-m vert attrs]
  (let [[uv obj-normal _obj-pos color] attrs
        [vx vy vz] vert
        world-pos [(+ (* (nth model-m 0) vx)
                      (* (nth model-m 4) vy)
                      (* (nth model-m 8) vz)
                      (nth model-m 12))
                   (+ (* (nth model-m 1) vx)
                      (* (nth model-m 5) vy)
                      (* (nth model-m 9) vz)
                      (nth model-m 13))
                   (+ (* (nth model-m 2) vx)
                      (* (nth model-m 6) vy)
                      (* (nth model-m 10) vz)
                      (nth model-m 14))]
        normal (math/vec3-normalize (math/apply-normal-matrix normal-m
                                                              obj-normal))]
    {:clip (math/clip-coords mvp vert), :bundle [uv normal world-pos color]}))


(defn- screen-vertex
  "Assemble a rasterizer vertex from a (possibly clipped) clip-space position
   and its carried [uv normal world-pos color] bundle."
  [clip bundle viewport-w viewport-h]
  (let [proj (math/clip->screen clip viewport-w viewport-h)
        [uv normal world-pos color] bundle]
    {:x (:screen-x proj),
     :y (:screen-y proj),
     :z (:z-ndc proj),
     :inv-w (:inv-w proj),
     :uv uv,
     :normal normal,
     :world-pos world-pos,
     :color color}))


(defn render-3d!
  "Render 3D draws from a lowered pass into the injected sink.
   Sink: {:put-pixel! :depth-get :depth-set! :viewport-w :viewport-h}.
   Draw keys consumed: :pipeline :op :mvp :model-m :lights :camera-pos
   :lighting-enabled :depth-test :depth-write :clips :texture.
   Each primitive is near-plane clipped in clip space before projection."
  [pass sink]
  (let [vp-w (:viewport-w sink)
        vp-h (:viewport-h sink)
        base-sink {:put-pixel! (:put-pixel! sink),
                   :depth-get (:depth-get sink),
                   :depth-set! (:depth-set! sink),
                   :viewport-w vp-w,
                   :viewport-h vp-h}]
    (doseq [draw (:draws pass)]
      (let [pipeline (:pipeline draw)
            sink' (assoc base-sink
                         :clips (:clips draw)
                         :depth-test? (:depth-test draw)
                         :depth-write? (:depth-write draw))]
        (case pipeline
          :clear nil ; handled by caller
          :camera-reset nil
          (:mesh-3d :draw-3d)
          (let [op (:op draw)
                mvp (:mvp draw)
                model-m (:model-m draw)
                normal-m (math/normal-matrix model-m)
                verts (:vertices op)
                indices (:indices op)
                tri-sink (assoc sink'
                                :shade-fn (fn [uv n wp c]
                                            (shade-mesh-fragment draw uv n wp c)))]
            (doseq [tri indices]
              (let [p0 (prepare-vertex mvp
                                       model-m
                                       normal-m
                                       (nth verts (nth tri 0))
                                       (vertex-attrs op (nth tri 0)))
                    p1 (prepare-vertex mvp
                                       model-m
                                       normal-m
                                       (nth verts (nth tri 1))
                                       (vertex-attrs op (nth tri 1)))
                    p2 (prepare-vertex mvp
                                       model-m
                                       normal-m
                                       (nth verts (nth tri 2))
                                       (vertex-attrs op (nth tri 2)))]
                (doseq [{[c0 c1 c2] :verts, [b0 b1 b2] :attrs}
                        (math/clip-triangle-near-attrs (:clip p0)
                                                       (:clip p1)
                                                       (:clip p2)
                                                       (:bundle p0)
                                                       (:bundle p1)
                                                       (:bundle p2))]
                  (rasterize-triangle! (screen-vertex c0 b0 vp-w vp-h)
                                       (screen-vertex c1 b1 vp-w vp-h)
                                       (screen-vertex c2 b2 vp-w vp-h)
                                       tri-sink)))))
          :line-3d
          (let [op (:op draw)
                mvp (:mvp draw)
                model-m (:model-m draw)
                normal-m (math/normal-matrix model-m)
                verts (:vertices op)
                edges (:edges op)]
            (when edges
              (doseq [[i0 i1] edges]
                (let [p0 (prepare-vertex mvp
                                         model-m
                                         normal-m
                                         (nth verts i0)
                                         (vertex-attrs op i0))
                      p1 (prepare-vertex mvp
                                         model-m
                                         normal-m
                                         (nth verts i1)
                                         (vertex-attrs op i1))]
                  (when-let [{[c0 c1] :verts, [b0 b1] :attrs}
                             (math/clip-line-near-attrs (:clip p0)
                                                        (:clip p1)
                                                        (:bundle p0)
                                                        (:bundle p1))]
                    (let [col0 (nth b0 3)
                          col1 (nth b1 3)]
                      (rasterize-line!
                        (screen-vertex c0 b0 vp-w vp-h)
                        (screen-vertex c1 b1 vp-w vp-h)
                        (assoc sink'
                               :color-fn
                               (fn [t]
                                 [(+ (* (nth col0 0) (- 1 t)) (* (nth col1 0) t))
                                  (+ (* (nth col0 1) (- 1 t)) (* (nth col1 1) t))
                                  (+ (* (nth col0 2) (- 1 t)) (* (nth col1 2) t))
                                  (+ (* (nth col0 3) (- 1 t))
                                     (* (nth col1 3) t))])))))))))
          nil)))))
