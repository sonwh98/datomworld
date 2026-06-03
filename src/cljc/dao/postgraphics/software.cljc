(ns dao.postgraphics.software
  "Pure CPU rasterizer for dao.postgraphics. Platform-agnostic; hot loops
   take injected depth-get/depth-set!/put-pixel! so each backend owns its
   buffers and pixel sink."
  (:require
    [dao.postgraphics.math :as math]
    [dao.postgraphics.validation :as v]))


;; --- color space ---

(defn srgb->linear
  [c]
  (if (<= c 0.04045) (/ c 12.92) (math/mpow (/ (+ c 0.055) 1.055) 2.4)))


(defn linear->srgb
  [c]
  (if (<= c 0.0031308)
    (* c 12.92)
    (- (* 1.055 (math/mpow c (/ 1.0 2.4))) 0.055)))


(defn- vec3-srgb->linear
  [[r g b]]
  [(srgb->linear (double r)) (srgb->linear (double g))
   (srgb->linear (double b))])


(defn- vec3-linear->srgb
  [[r g b]]
  [(linear->srgb (double r)) (linear->srgb (double g))
   (linear->srgb (double b))])


(defn- vec4-srgb->linear
  [[r g b a]]
  [(srgb->linear (double r)) (srgb->linear (double g)) (srgb->linear (double b))
   (double a)])


;; --- texture sampling ---

(defn- wrap-uv
  [u mode]
  (case mode
    :clamp (max 0.0 (min 1.0 u))
    :repeat (- u (math/mfloor u))
    :mirror (let [f (math/mfloor u)
                  frac (- u f)
                  even? (zero? (mod (int f) 2))]
              (if even? frac (- 1.0 frac)))
    (max 0.0 (min 1.0 u))))


(defn- sample-nearest
  [rgba width height u v]
  (let [x (int (math/mfloor (* u width)))
        y (int (math/mfloor (* v height)))
        x (max 0 (min (dec width) x))
        y (max 0 (min (dec height) y))
        idx (* (+ (* y width) x) 4)]
    [(nth rgba idx) (nth rgba (inc idx)) (nth rgba (+ idx 2))
     (nth rgba (+ idx 3))]))


(defn- sample-bilinear
  [rgba width height u v]
  (let [fx (* u width)
        fy (* v height)
        x0 (int (math/mfloor fx))
        y0 (int (math/mfloor fy))
        x1 (min (inc x0) (dec width))
        y1 (min (inc y0) (dec height))
        sx (- fx x0)
        sy (- fy y0)
        idx00 (* (+ (* y0 width) x0) 4)
        idx10 (* (+ (* y0 width) x1) 4)
        idx01 (* (+ (* y1 width) x0) 4)
        idx11 (* (+ (* y1 width) x1) 4)
        sample (fn [idx off] (nth rgba (+ idx off)))
        lerp (fn [a b t] (+ (* a (- 1.0 t)) (* b t)))]
    [(lerp (lerp (sample idx00 0) (sample idx10 0) sx)
           (lerp (sample idx01 0) (sample idx11 0) sx)
           sy)
     (lerp (lerp (sample idx00 1) (sample idx10 1) sx)
           (lerp (sample idx01 1) (sample idx11 1) sx)
           sy)
     (lerp (lerp (sample idx00 2) (sample idx10 2) sx)
           (lerp (sample idx01 2) (sample idx11 2) sx)
           sy)
     (lerp (lerp (sample idx00 3) (sample idx10 3) sx)
           (lerp (sample idx01 3) (sample idx11 3) sx)
           sy)]))


(defn sample-texture
  "Samples a texture {:rgba byte-vector :width :height} at uv with
   wrap mode (:clamp :repeat :mirror) and filter (:nearest :linear)."
  [texture u v wrap filter-mode]
  (let [{:keys [rgba width height]} texture
        u (wrap-uv (double u) wrap)
        v (wrap-uv (double v) wrap)]
    (case filter-mode
      :nearest (sample-nearest rgba width height u v)
      :linear (sample-bilinear rgba width height u v)
      (sample-bilinear rgba width height u v))))


;; --- Blinn-Phong ---

(defn blinn-phong
  "Computes lighting in linear RGB. Returns [r g b].
   fragment-pos, normal, camera-pos are vec3 in world space.
   kd-linear is diffuse [r g b] in linear space.
   ks-linear is specular [r g b] in linear space.
   ke-linear is emissive [r g b] in linear space.
   shininess is a positive double."
  [fragment-pos normal camera-pos kd-linear ks-linear ke-linear shininess
   lights]
  (let [n (math/vec3-normalize normal)
        v-dir (math/vec3-normalize (math/vec3-sub camera-pos fragment-pos))
        ambient (reduce (fn [acc light]
                          (if (= :ambient (:kind light))
                            (math/vec3-add acc
                                           (math/vec3-scale
                                             (vec3-srgb->linear (:color light))
                                             (get light :intensity 1.0)))
                            acc))
                        [0.0 0.0 0.0]
                        lights)
        result (math/vec3-add [(* (nth ambient 0) (nth kd-linear 0))
                               (* (nth ambient 1) (nth kd-linear 1))
                               (* (nth ambient 2) (nth kd-linear 2))]
                              ke-linear)]
    (reduce
      (fn [acc light]
        (case (:kind light)
          :ambient acc
          :directional
          (let [l-dir (math/vec3-normalize (math/vec3-scale (:direction light)
                                                            -1.0))
                h (math/vec3-normalize (math/vec3-add l-dir v-dir))
                ndl (max 0.0 (math/vec3-dot n l-dir))
                ndh (max 0.0 (math/vec3-dot n h))
                lc (math/vec3-scale (vec3-srgb->linear (:color light))
                                    (get light :intensity 1.0))
                diff (math/vec3-scale lc ndl)
                spec (math/vec3-scale lc (math/mpow ndh shininess))]
            (math/vec3-add acc
                           (math/vec3-add [(* (nth diff 0) (nth kd-linear 0))
                                           (* (nth diff 1) (nth kd-linear 1))
                                           (* (nth diff 2) (nth kd-linear 2))]
                                          [(* (nth spec 0) (nth ks-linear 0))
                                           (* (nth spec 1) (nth ks-linear 1))
                                           (* (nth spec 2)
                                              (nth ks-linear 2))])))
          :point
          (let [l-dir (math/vec3-sub (:position light) fragment-pos)
                d (math/vec3-length l-dir)
                range-v (get light :range 100.0)]
            (if (>= d range-v)
              acc
              (let [l-dir-n (math/vec3-normalize l-dir)
                    h (math/vec3-normalize (math/vec3-add l-dir-n v-dir))
                    ndl (max 0.0 (math/vec3-dot n l-dir-n))
                    ndh (max 0.0 (math/vec3-dot n h))
                    att (math/mpow (- 1.0 (/ d range-v)) 2.0)
                    lc (math/vec3-scale (vec3-srgb->linear (:color light))
                                        (* (get light :intensity 1.0) att))
                    diff (math/vec3-scale lc ndl)
                    spec (math/vec3-scale lc (math/mpow ndh shininess))]
                (math/vec3-add acc
                               (math/vec3-add
                                 [(* (nth diff 0) (nth kd-linear 0))
                                  (* (nth diff 1) (nth kd-linear 1))
                                  (* (nth diff 2) (nth kd-linear 2))]
                                 [(* (nth spec 0) (nth ks-linear 0))
                                  (* (nth spec 1) (nth ks-linear 1))
                                  (* (nth spec 2) (nth ks-linear 2))])))))
          acc))
      result
      lights)))


;; --- fragment shading ---

(defn shade-mesh-fragment
  "Shades one fragment. Returns [r g b a] in sRGB."
  [{:keys [uv normal world-pos color diffuse-base texture lights camera-pos
           lighting-enabled? specular shininess emissive]}]
  (let [diffuse
        (if color (vec4-srgb->linear color) (vec4-srgb->linear diffuse-base))
        tex-rgba (when texture
                   (sample-texture texture
                                   (nth uv 0)
                                   (nth uv 1)
                                   (get texture :wrap :clamp)
                                   (get texture :filter :linear)))
        tex-rgba (when tex-rgba (mapv #(/ (double %) 255.0) tex-rgba))
        diffuse (if tex-rgba
                  [(* (nth diffuse 0) (nth tex-rgba 0))
                   (* (nth diffuse 1) (nth tex-rgba 1))
                   (* (nth diffuse 2) (nth tex-rgba 2))
                   (* (nth diffuse 3) (nth tex-rgba 3))]
                  diffuse)
        [r g b a] (if lighting-enabled?
                    (let [ks (vec3-srgb->linear (or specular [0.0 0.0 0.0]))
                          ke (vec3-srgb->linear (or emissive [0.0 0.0 0.0]))
                          sh (or shininess 32.0)
                          lit (blinn-phong world-pos
                                           normal
                                           camera-pos
                                           [(nth diffuse 0) (nth diffuse 1)
                                            (nth diffuse 2)]
                                           ks
                                           ke
                                           sh
                                           lights)]
                      (conj (vec3-linear->srgb lit) (nth diffuse 3)))
                    [(nth diffuse 0) (nth diffuse 1) (nth diffuse 2)
                     (nth diffuse 3)])]
    [(max 0.0 (min 1.0 r)) (max 0.0 (min 1.0 g)) (max 0.0 (min 1.0 b))
     (max 0.0 (min 1.0 a))]))


;; --- rasterization ---

(defn- inside-clip?
  [x y w h clips]
  (every? (fn [[cx cy cw ch]]
            (and (>= x cx) (>= y cy) (< x (+ cx cw)) (< y (+ cy ch))))
          clips))


(defn- edge-function
  [ax ay bx by cx cy]
  (- (* (- bx ax) (- cy ay)) (* (- by ay) (- cx ax))))


(defn rasterize-triangle!
  "Rasterizes one triangle with perspective-correct interpolation.
   v0/v1/v2 are {:pos [sx sy z inv-w] :uv [u v] :normal [nx ny nz]
                 :world-pos [x y z] :color [r g b a]}.
   put-pixel! is called as (put-pixel! x y z-ndc rgba)."
  [v0 v1 v2 clips width height depth-get depth-set! put-pixel! shade-fn]
  (let [[x0 y0 z0 iw0] (:pos v0)
        [x1 y1 z1 iw1] (:pos v1)
        [x2 y2 z2 iw2] (:pos v2)
        min-x (max 0 (int (math/mfloor (min x0 x1 x2))))
        max-x (min (dec width) (int (math/mceil (max x0 x1 x2))))
        min-y (max 0 (int (math/mfloor (min y0 y1 y2))))
        max-y (min (dec height) (int (math/mceil (max y0 y1 y2))))
        area (edge-function x0 y0 x1 y1 x2 y2)]
    (when (> (math/mabs area) v/EPSILON)
      (doseq [py (range min-y (inc max-y))
              px (range min-x (inc max-x))
              :let [w0-div (/ (edge-function x1 y1 x2 y2 px py) area)
                    w1-div (/ (edge-function x2 y2 x0 y0 px py) area)
                    w2-div (/ (edge-function x0 y0 x1 y1 px py) area)]
              ;; Coverage uses area-normalized barycentrics so triangles of
              ;; either winding rasterize (no implicit back-face culling).
              :when (and (>= w0-div 0.0) (>= w1-div 0.0) (>= w2-div 0.0))
              :let [inv-w (+ (* w0-div iw0) (* w1-div iw1) (* w2-div iw2))
                    z-ndc (+ (* w0-div z0) (* w1-div z1) (* w2-div z2))
                    z-ndc (if (zero? inv-w) z-ndc (/ z-ndc inv-w))
                    corr (if (zero? inv-w) 1.0 (/ 1.0 inv-w))
                    l0 (* w0-div iw0 corr)
                    l1 (* w1-div iw1 corr)
                    l2 (* w2-div iw2 corr)
                    l-sum (+ l0 l1 l2)
                    l0 (if (zero? l-sum) 0.0 (/ l0 l-sum))
                    l1 (if (zero? l-sum) 0.0 (/ l1 l-sum))
                    l2 (if (zero? l-sum) 0.0 (/ l2 l-sum))
                    interp (fn [a0 a1 a2]
                             (mapv (fn [i]
                                     (+ (* l0 (nth a0 i))
                                        (* l1 (nth a1 i))
                                        (* l2 (nth a2 i))))
                                   (range (count a0))))]
              :when (and (or (nil? depth-get) (< z-ndc (depth-get px py)))
                         (inside-clip? px py 1 1 clips))]
        (let [rgba (shade-fn {:uv (interp (:uv v0) (:uv v1) (:uv v2)),
                              :normal
                              (interp (:normal v0) (:normal v1) (:normal v2)),
                              :world-pos (interp (:world-pos v0)
                                                 (:world-pos v1)
                                                 (:world-pos v2))})]
          (when depth-set! (depth-set! px py z-ndc))
          (put-pixel! px py z-ndc rgba))))))


(defn rasterize-line!
  "Rasterizes a line segment with depth testing.
   p0/p1 are [sx sy z-ndc inv-w]."
  [p0 p1 clips width height depth-get depth-set! put-pixel! color]
  (let [[x0 y0 z0 _] p0
        [x1 y1 z1 _] p1
        dx (math/mabs (- x1 x0))
        dy (math/mabs (- y1 y0))
        steep? (> dy dx)
        [x0 y0 x1 y1] (if steep? [y0 x0 y1 x1] [x0 y0 x1 y1])
        [x0 y0 x1 y1] (if (> x0 x1) [x1 y1 x0 y0] [x0 y0 x1 y1])
        dx (- x1 x0)
        dy (math/mabs (- y1 y0))
        steps (max dx dy)
        x-step (/ (- x1 x0) steps)
        y-step (/ (- y1 y0) steps)]
    (doseq [i (range (inc (int steps)))]
      (let [x (+ x0 (* i x-step))
            y (+ y0 (* i y-step))
            px (if steep? (int y) (int x))
            py (if steep? (int x) (int y))
            t (if (zero? steps) 0.0 (/ i steps))
            z-ndc (+ z0 (* t (- z1 z0)))]
        (when (and (>= px 0)
                   (< px width)
                   (>= py 0)
                   (< py height)
                   (or (nil? depth-get) (< z-ndc (depth-get px py)))
                   (inside-clip? px py 1 1 clips))
          (when depth-set! (depth-set! px py z-ndc))
          (put-pixel! px py z-ndc color))))))


;; --- 3D rendering orchestrator ---

(defn- transform-vertex
  [mvp model-m vertex normal uv color]
  (let [[sx sy z-ndc inv-w] (math/project mvp vertex 1 1)
        world-pos [(+ (* (nth model-m 0) (nth vertex 0))
                      (* (nth model-m 4) (nth vertex 1))
                      (* (nth model-m 8) (nth vertex 2))
                      (nth model-m 12))
                   (+ (* (nth model-m 1) (nth vertex 0))
                      (* (nth model-m 5) (nth vertex 1))
                      (* (nth model-m 9) (nth vertex 2))
                      (nth model-m 13))
                   (+ (* (nth model-m 2) (nth vertex 0))
                      (* (nth model-m 6) (nth vertex 1))
                      (* (nth model-m 10) (nth vertex 2))
                      (nth model-m 14))]
        it (math/inverse-transpose-3x3 model-m)
        nx (+ (* (nth it 0) (nth normal 0))
              (* (nth it 1) (nth normal 1))
              (* (nth it 2) (nth normal 2)))
        ny (+ (* (nth it 3) (nth normal 0))
              (* (nth it 4) (nth normal 1))
              (* (nth it 5) (nth normal 2)))
        nz (+ (* (nth it 6) (nth normal 0))
              (* (nth it 7) (nth normal 1))
              (* (nth it 8) (nth normal 2)))
        world-normal (math/vec3-normalize [nx ny nz])]
    {:pos [sx sy z-ndc inv-w],
     :uv (or uv [0.0 0.0]),
     :normal world-normal,
     :world-pos world-pos,
     :color color}))


(defn render-3d!
  "Renders all 3D draws in a single pass using the software rasterizer.
   `draws` is a sequence of canonical 3D draw records.
   `opts` contains :width :height :depth-get :depth-set! :put-pixel!.
   Returns nil (side effects through callbacks)."
  [draws opts]
  (let [{:keys [width height depth-get depth-set! put-pixel!]} opts]
    (doseq [draw draws]
      (case (:pipeline draw)
        :mesh-3d
        (let [{:keys [op mvp model-m lights camera-pos depth-test depth-write
                      lighting-enabled texture clips]}
              draw
              verts (:vertices op)
              indices (:indices op)
              normals (or (:normals op) (repeat (count verts) [0.0 0.0 1.0]))
              uvs (or (:uvs op) (repeat (count verts) [0.0 0.0]))
              colors (:colors op)
              fill (:fill op [1.0 1.0 1.0 1.0])
              specular (:material/specular op)
              shininess (:material/shininess op)
              emissive (:material/emissive op)
              clip-list (or clips [])
              xform (fn [v n uv c] (transform-vertex mvp model-m v n uv c))]
          (doseq [[i0 i1 i2] indices]
            (let [v0 (xform (nth verts i0)
                            (nth normals i0)
                            (nth uvs i0)
                            (when colors (nth colors i0)))
                  v1 (xform (nth verts i1)
                            (nth normals i1)
                            (nth uvs i1)
                            (when colors (nth colors i1)))
                  v2 (xform (nth verts i2)
                            (nth normals i2)
                            (nth uvs i2)
                            (when colors (nth colors i2)))
                  clip-results
                  (math/clip-triangle-near (:pos v0) (:pos v1) (:pos v2))]
              (doseq [tri clip-results]
                ;; After clipping we may have more vertices; for
                ;; simplicity we only handle the single-triangle case
                ;; (most common).
                ;; A full implementation would triangulate the clipped
                ;; polygon.
                (when (= 3 (count tri))
                  (let [t0 (assoc v0 :pos (nth tri 0))
                        t1 (assoc v1 :pos (nth tri 1))
                        t2 (assoc v2 :pos (nth tri 2))]
                    (rasterize-triangle! t0
                                         t1
                                         t2
                                         clip-list
                                         width
                                         height
                                         (when depth-test depth-get)
                                         (when depth-write depth-set!)
                                         put-pixel!
                                         (fn [attrs]
                                           (shade-mesh-fragment
                                             (assoc attrs
                                                    :diffuse-base fill
                                                    :texture texture
                                                    :lights lights
                                                    :camera-pos camera-pos
                                                    :lighting-enabled?
                                                    lighting-enabled
                                                    :specular specular
                                                    :shininess shininess
                                                    :emissive emissive))))))))))
        :draw-3d (let [{:keys [op mvp clips]} draw
                       verts (:vertices op)
                       indices (:indices op)
                       color (:fill op [1.0 1.0 1.0 1.0])
                       clip-list (or clips [])]
                   (doseq [[i0 i1 i2] indices]
                     (let [p0 (math/project mvp (nth verts i0) width height)
                           p1 (math/project mvp (nth verts i1) width height)
                           p2 (math/project mvp (nth verts i2) width height)]
                       (rasterize-triangle! {:pos p0}
                                            {:pos p1}
                                            {:pos p2}
                                            clip-list
                                            width
                                            height
                                            nil
                                            nil
                                            put-pixel!
                                            (fn [_] color)))))
        :line-3d
        (let [{:keys [op mvp clips depth-test depth-write]} draw
              verts (:vertices op)
              edges (:edges op)
              color (:color op [1.0 1.0 1.0 1.0])
              clip-list (or clips [])]
          (doseq [[i0 i1] edges]
            (let [p0 (math/project mvp (nth verts i0) width height)
                  p1 (math/project mvp (nth verts i1) width height)
                  clipped (math/clip-line-near p0 p1)]
              (when clipped
                (let [[c0 c1] clipped
                      sp0 (math/project mvp
                                        [(nth c0 0) (nth c0 1) (nth c0 2)]
                                        width
                                        height)
                      sp1 (math/project mvp
                                        [(nth c1 0) (nth c1 1) (nth c1 2)]
                                        width
                                        height)]
                  (rasterize-line! sp0
                                   sp1
                                   clip-list
                                   width
                                   height
                                   (when depth-test depth-get)
                                   (when depth-write depth-set!)
                                   put-pixel!
                                   color))))))
        nil))))
