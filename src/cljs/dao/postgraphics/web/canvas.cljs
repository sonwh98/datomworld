(ns dao.postgraphics.web.canvas
  "Browser software backend: rasterizes the lowered frame's 3D draws on the
   CPU through the shared dao.postgraphics.raster core into an ImageData
   colour buffer + a Float32Array depth buffer, blits once with putImageData,
   then paints :draw-2d / :text overlays with native Canvas2D.  The 3D core is
   the same code the Flutter software backend drives — only the pixel sink and
   the 2D overlay calls are platform-specific."
  (:require
    [dao.postgraphics.packing :as pack]
    [dao.postgraphics.raster :as raster]))


;; ---------------------------------------------------------------------------
;; Pixel + depth sink (ImageData.data is a Uint8ClampedArray, so byte writes
;; auto-clamp; the shared shader already clamps to [0,1]).
;; ---------------------------------------------------------------------------

(defn- put-pixel!
  [^js rgba w x y r g b a]
  (let [o (* 4 (+ (* y w) x))]
    (aset rgba o (* 255.0 r))
    (aset rgba (+ o 1) (* 255.0 g))
    (aset rgba (+ o 2) (* 255.0 b))
    (aset rgba (+ o 3) (* 255.0 a))))


(defn- depth-get
  [^js depth w x y]
  (aget depth (+ (* y w) x)))


(defn- depth-set!
  [^js depth w x y z]
  (aset depth (+ (* y w) x) z))


(defn- clear!
  [^js rgba ^js depth w h [r g b a]]
  (let [ir (* 255.0 (double r))
        ig (* 255.0 (double g))
        ib (* 255.0 (double b))
        ia (* 255.0 (double (or a 1.0)))]
    (dotimes [i (* w h)]
      (let [o (* 4 i)]
        (aset rgba o ir)
        (aset rgba (+ o 1) ig)
        (aset rgba (+ o 2) ib)
        (aset rgba (+ o 3) ia))))
  (.fill depth 1.0))


;; ---------------------------------------------------------------------------
;; CPU-samplable textures.  Mesh :texture/source on the web arrives as
;; {:image <HTMLImageElement|HTMLCanvasElement|ImageData> :width :height
;; :source-id}; the software sampler needs raw 0–255 :rgba bytes.  Convert once
;; (reading the image through an offscreen 2D context) and cache by :source-id.
;; ---------------------------------------------------------------------------

(defonce ^:private cpu-texture-cache (atom {}))


(defn- image->rgba
  [^js image width height]
  (if (some? (.-data image))
    ;; ImageData (or ImageData-like): bytes are already available.
    (.-data image)
    (let [^js c (.createElement js/document "canvas")]
      (set! (.-width c) width)
      (set! (.-height c) height)
      (let [^js cx (.getContext c "2d")]
        (.drawImage cx image 0 0 width height)
        (.-data (.getImageData cx 0 0 width height))))))


(defn- cpu-texture
  "Ensures a texture map carries CPU-samplable :rgba bytes for the software
   sampler.  Passes through textures that already have :rgba; converts an
   :image source once and caches the result by :source-id."
  [texture]
  (cond (or (nil? texture) (:rgba texture)) texture
        (:image texture)
        (let [{:keys [source-id image width height]} texture]
          (or (and source-id (get @cpu-texture-cache source-id))
              (let [cpu {:rgba (image->rgba image width height),
                         :width width,
                         :height height}]
                (when source-id (swap! cpu-texture-cache assoc source-id cpu))
                cpu)))
        :else texture))


(defn- prepare-textures
  "Replaces each draw's :texture with a CPU-samplable version before the
   software rasterizer reads it."
  [lowered]
  (update lowered
          :passes
          (fn [passes]
            (mapv (fn [pass]
                    (update pass
                            :draws
                            (fn [draws]
                              (mapv (fn [d]
                                      (if (:texture d)
                                        (update d :texture cpu-texture)
                                        d))
                                    draws))))
                  passes))))


;; ---------------------------------------------------------------------------
;; 2D / text overlay (native Canvas2D, mirroring flutter.cljd's painter)
;; ---------------------------------------------------------------------------

(defn- ->css-color
  [[r g b a]]
  (str "rgba("
       (int (* 255.0 (double r)))
       ","
       (int (* 255.0 (double g)))
       ","
       (int (* 255.0 (double b)))
       ","
       (double (or a 1.0))
       ")"))


(defn- apply-clips!
  [^js ctx clips]
  (doseq [[x y w h] clips]
    (.beginPath ctx)
    (.rect ctx x y w h)
    (.clip ctx)))


(defn- trace-path!
  "Traces a :draw/path :segments vector onto the context's current path.
   Segment vocabulary: [:move-to x y] [:line-to x y] [:quad-to cx cy x y]
   [:cubic-to cx1 cy1 cx2 cy2 x y] [:close]."
  [^js ctx segments]
  (.beginPath ctx)
  (doseq [seg segments]
    (case (first seg)
      :move-to (.moveTo ctx (nth seg 1) (nth seg 2))
      :line-to (.lineTo ctx (nth seg 1) (nth seg 2))
      :quad-to
      (.quadraticCurveTo ctx (nth seg 1) (nth seg 2) (nth seg 3) (nth seg 4))
      :cubic-to (.bezierCurveTo ctx
                                (nth seg 1)
                                (nth seg 2)
                                (nth seg 3)
                                (nth seg 4)
                                (nth seg 5)
                                (nth seg 6))
      :close (.closePath ctx)
      nil)))


(defn- paint-2d-draw!
  "Renders one :draw-2d op.  Clips are screen-space (the lowering already
   y-flipped them); then the context is y-flipped into the IR's Cartesian
   convention and the lowered model matrix (a 16-vec) is applied as a 2D
   affine so the op draws in its own coordinates."
  [^js ctx draw height]
  (let [op (:op draw)
        kind (:op/kind op)
        m (:model-m draw)]
    (.save ctx)
    (apply-clips! ctx (:clips draw))
    (.translate ctx 0 height)
    (.scale ctx 1 -1)
    ;; column-major 4x4 -> Canvas2D transform(a b c d e f)
    (.transform ctx
                (nth m 0)
                (nth m 1)
                (nth m 4)
                (nth m 5)
                (nth m 12)
                (nth m 13))
    (case kind
      :draw/fill-rect (let [[x y w h] (:rect op)]
                        (set! (.-fillStyle ctx) (->css-color (:color op)))
                        (.fillRect ctx x y w h))
      :draw/stroke-rect (let [[x y w h] (:rect op)]
                          (set! (.-strokeStyle ctx) (->css-color (:color op)))
                          (set! (.-lineWidth ctx)
                                (double (get op :stroke-width 1.0)))
                          (.strokeRect ctx x y w h))
      :draw/fill-circle (let [[cx cy] (:center op)]
                          (set! (.-fillStyle ctx) (->css-color (:color op)))
                          (.beginPath ctx)
                          (.arc ctx cx cy (:radius op) 0 (* 2 js/Math.PI))
                          (.fill ctx))
      :draw/stroke-circle (let [[cx cy] (:center op)]
                            (set! (.-strokeStyle ctx) (->css-color (:color op)))
                            (set! (.-lineWidth ctx)
                                  (double (get op :stroke-width 1.0)))
                            (.beginPath ctx)
                            (.arc ctx cx cy (:radius op) 0 (* 2 js/Math.PI))
                            (.stroke ctx))
      :draw/path (do (trace-path! ctx (:segments op))
                     (when-let [fill (:fill op)]
                       (set! (.-fillStyle ctx) (->css-color fill))
                       (.fill ctx))
                     (when-let [stroke (:stroke op)]
                       (set! (.-strokeStyle ctx) (->css-color stroke))
                       (set! (.-lineWidth ctx)
                             (double (get op :stroke-width 1.0)))
                       (.stroke ctx)))
      :draw/image (let [[x y w h] (:rect op)
                        opacity (double (get op :opacity 1.0))]
                    ;; Only the :placeholder resource is concretely
                    ;; specified for the software path today (white fill);
                    ;; real image sources await the web software
                    ;; resolve-resource contract (orientation under the
                    ;; y-flip, :image/fit, and :image/src-rect handled
                    ;; then).
                    (set! (.-globalAlpha ctx) opacity)
                    (when (= :placeholder (:resource/kind (:resource op)))
                      (set! (.-fillStyle ctx) (->css-color [1.0 1.0 1.0 1.0]))
                      (.fillRect ctx x y w h))
                    (set! (.-globalAlpha ctx) 1.0))
      nil)
    (.restore ctx)))


(defn- paint-text!
  "Renders one :text op.  screen-x/screen-y are already in y-down screen
   space; Canvas2D's alphabetic baseline rests at screen-y directly."
  [^js ctx draw]
  (.save ctx)
  (apply-clips! ctx (:clips draw))
  (set! (.-fillStyle ctx) (->css-color (or (:color draw) [0.0 0.0 0.0 1.0])))
  (set! (.-font ctx)
        (str (double (get draw :font-size 14.0))
             "px "
             (or (:font-family draw) "monospace")))
  (set! (.-textBaseline ctx) "alphabetic")
  (.fillText ctx (str (:text draw)) (:screen-x draw) (:screen-y draw))
  (.restore ctx))


;; ---------------------------------------------------------------------------
;; Submitter
;; ---------------------------------------------------------------------------

(defn submit-software!
  "Software :submit! for postgraphics-widget — the Canvas2D counterpart to
   web.gpu/submit-webgpu! and flutter.canvas/paint-3d!.  Renders the lowered
   frame's 3D draws into an ImageData via the shared software core, blits once,
   then overlays :draw-2d / :text with native Canvas2D."
  [^js canvas lowered]
  (let [^js ctx (.getContext canvas "2d")
        w (int (.-width canvas))
        h (int (.-height canvas))]
    (when (and ctx (pos? w) (pos? h))
      (let [lowered (prepare-textures lowered)
            image (.createImageData ctx w h)
            rgba (.-data image)
            depth (js/Float32Array. (* w h))]
        (clear! rgba depth w h (pack/clear-color lowered))
        (doseq [pass (:passes lowered)]
          (raster/render-3d!
            pass
            {:put-pixel! (fn [x y r g b a] (put-pixel! rgba w x y r g b a)),
             :depth-get (fn [x y] (depth-get depth w x y)),
             :depth-set! (fn [x y z] (depth-set! depth w x y z)),
             :viewport-w w,
             :viewport-h h}))
        (.putImageData ctx image 0 0)
        (doseq [pass (:passes lowered)
                draw (:draws pass)]
          (case (:pipeline draw)
            :draw-2d (paint-2d-draw! ctx draw h)
            :text (paint-text! ctx draw)
            nil))
        nil))))
