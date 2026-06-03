(ns dao.postgraphics.web.canvas
  "Canvas2D software backend for dao.postgraphics. Rasters 3D via
   dao.postgraphics.software into an ImageData buffer, then paints 2D
   and text on top with the Canvas2D API."
  (:require
    [dao.postgraphics.software :as software]))


(defn- resize-canvas!
  [^js canvas]
  (let [dpr (or (when (exists? js/window) (.-devicePixelRatio js/window)) 1)
        rect (.getBoundingClientRect canvas)
        w (max 1 (int (* dpr (.-width rect))))
        h (max 1 (int (* dpr (.-height rect))))]
    (when (or (not= (.-width canvas) w) (not= (.-height canvas) h))
      (set! (.-width canvas) w)
      (set! (.-height canvas) h))
    [w h]))


(defn- clear-image-data!
  [^js image-data [r g b a]]
  (let [^js data (.-data image-data)
        n (.-length data)
        ri (int (* 255.0 (double r)))
        gi (int (* 255.0 (double g)))
        bi (int (* 255.0 (double b)))
        ai (int (* 255.0 (double a)))]
    (loop [i 0]
      (when (< i n)
        (aset data i ri)
        (aset data (+ i 1) gi)
        (aset data (+ i 2) bi)
        (aset data (+ i 3) ai)
        (recur (+ i 4))))))


(defn- clear-depth!
  [^js depth-array]
  (let [n (.-length depth-array)]
    (loop [i 0] (when (< i n) (aset depth-array i 1.0) (recur (inc i))))))


(defn- make-put-pixel!
  "Returns a put-pixel! fn that writes [r g b a] floats into an ImageData."
  [^js image-data]
  (let [^js data (.-data image-data)
        width (.-width image-data)]
    (fn [x y _z-ndc [r g b a]]
      (let [x (int x)
            y (int y)
            idx (* (+ (* y width) x) 4)
            ri (max 0 (min 255 (int (* 255.0 (double r)))))
            gi (max 0 (min 255 (int (* 255.0 (double g)))))
            bi (max 0 (min 255 (int (* 255.0 (double b)))))
            ai (max 0 (min 255 (int (* 255.0 (double a)))))]
        (aset data idx ri)
        (aset data (+ idx 1) gi)
        (aset data (+ idx 2) bi)
        (aset data (+ idx 3) ai)))))


(defn- make-depth-get
  [^js depth-array width]
  (fn [x y] (aget depth-array (+ (* (int y) width) (int x)))))


(defn- make-depth-set!
  [^js depth-array width]
  (fn [x y z] (aset depth-array (+ (* (int y) width) (int x)) (double z))))


;; --- 2D rendering ---

(defn- apply-clips!
  [^js ctx clips]
  (doseq [[x y w h] clips]
    (.beginPath ctx)
    (.rect ctx (double x) (double y) (double w) (double h))
    (.clip ctx)))


(defn- draw-2d-op!
  [^js ctx draw height]
  (try
    (let [op (:op draw)
          kind (:op/kind op)
          model-m (:model-m draw)
          ;; Extract 2D affine params from the 16-vec column-major matrix
          [m00 m10 m01 m11 m02 m12] [(nth model-m 0) (nth model-m 1)
                                     (nth model-m 4) (nth model-m 5)
                                     (nth model-m 12) (nth model-m 13)]]
      (.save ctx)
      (apply-clips! ctx (:clips draw))
      ;; Transform into Cartesian space, then apply model matrix
      (.translate ctx 0 height)
      (.scale ctx 1 -1)
      (.transform ctx m00 m10 m01 m11 m02 m12)
      (case kind
        :draw/fill-rect
        (let [[r g b a] (:color op [0 0 0 1])
              [x y w h] (:rect op)]
          (set! (.-fillStyle ctx)
                (str "rgba("
                     (int (* 255 r))
                     ","
                     (int (* 255 g))
                     ","
                     (int (* 255 b))
                     ","
                     a
                     ")"))
          (.fillRect ctx (double x) (double y) (double w) (double h)))
        :draw/stroke-rect
        (let [[r g b a] (:color op [0 0 0 1])
              [x y w h] (:rect op)
              sw (:stroke-width op 1.0)]
          (set! (.-strokeStyle ctx)
                (str "rgba("
                     (int (* 255 r))
                     ","
                     (int (* 255 g))
                     ","
                     (int (* 255 b))
                     ","
                     a
                     ")"))
          (set! (.-lineWidth ctx) (double sw))
          (.strokeRect ctx (double x) (double y) (double w) (double h)))
        :draw/fill-circle (let [[r g b a] (:color op [0 0 0 1])
                                [cx cy] (:center op)
                                radius (:radius op)]
                            (set! (.-fillStyle ctx)
                                  (str "rgba("
                                       (int (* 255 r))
                                       ","
                                       (int (* 255 g))
                                       ","
                                       (int (* 255 b))
                                       ","
                                       a
                                       ")"))
                            (.beginPath ctx)
                            (.arc ctx
                                  (double cx)
                                  (double cy)
                                  (double radius)
                                  0
                                  (* 2 js/Math.PI))
                            (.fill ctx))
        :draw/stroke-circle (let [[r g b a] (:color op [0 0 0 1])
                                  [cx cy] (:center op)
                                  radius (:radius op)
                                  sw (:stroke-width op 1.0)]
                              (set! (.-strokeStyle ctx)
                                    (str "rgba("
                                         (int (* 255 r))
                                         ","
                                         (int (* 255 g))
                                         ","
                                         (int (* 255 b))
                                         ","
                                         a
                                         ")"))
                              (set! (.-lineWidth ctx) (double sw))
                              (.beginPath ctx)
                              (.arc ctx
                                    (double cx)
                                    (double cy)
                                    (double radius)
                                    0
                                    (* 2 js/Math.PI))
                              (.stroke ctx))
        :draw/path
        (let [[fr fg fb fa] (:fill op [0 0 0 0])
              [sr sg sb sa] (:stroke op [0 0 0 1])
              sw (:stroke-width op 1.0)
              segments (:segments op)]
          (.beginPath ctx)
          (doseq [seg segments]
            (case (first seg)
              :move-to (.moveTo ctx (double (nth seg 1)) (double (nth seg 2)))
              :line-to (.lineTo ctx (double (nth seg 1)) (double (nth seg 2)))
              :close (.closePath ctx)
              nil))
          (when (and (pos? fa) (or (pos? fr) (pos? fg) (pos? fb)))
            (set! (.-fillStyle ctx)
                  (str "rgba("
                       (int (* 255 fr))
                       ","
                       (int (* 255 fg))
                       ","
                       (int (* 255 fb))
                       ","
                       fa
                       ")"))
            (.fill ctx))
          (when (and (pos? sa) (or (pos? sr) (pos? sg) (pos? sb)))
            (set! (.-strokeStyle ctx)
                  (str "rgba("
                       (int (* 255 sr))
                       ","
                       (int (* 255 sg))
                       ","
                       (int (* 255 sb))
                       ","
                       sa
                       ")"))
            (set! (.-lineWidth ctx) (double sw))
            (.stroke ctx)))
        :draw/image
        ;; Software backend: draw/image is supported when
        ;; resolve-resource provides a JS Image or ImageData. We draw via
        ;; drawImage.
        (let [resource (:resource draw)
              [x y w h] (:rect op)]
          (when-let [img (case (:resource/kind resource)
                           :texture (:source resource)
                           :target-texture nil ; not supported in canvas
                           nil)]
            (try
              (.drawImage ctx img (double x) (double y) (double w) (double h))
              (catch js/Error _ nil))))
        nil)
      (.restore ctx))
    (catch js/Error e
      (println "DRAW-2D ERROR:" e)
      (.log js/console e)
      (throw e))))


(defn- draw-text-op!
  [^js ctx draw]
  (let [op (:op draw)
        text (:text op)
        font-size (double (get op :font-size 14.0))
        font-family (or (:font-family op) "sans-serif")
        [r g b a] (or (:color op) [0.0 0.0 0.0 1.0])
        screen-x (double (:screen-x draw))
        screen-y (double (:screen-y draw))
        align (name (get op :align :start))]
    (.save ctx)
    (apply-clips! ctx (:clips draw))
    (set! (.-fillStyle ctx)
          (str "rgba("
               (int (* 255 r))
               ","
               (int (* 255 g))
               ","
               (int (* 255 b))
               ","
               a
               ")"))
    (set! (.-font ctx) (str (int font-size) "px " font-family))
    (set! (.-textBaseline ctx) "top")
    (set! (.-textAlign ctx) align)
    (.fillText ctx text screen-x screen-y)
    (.restore ctx)))


;; --- public submitter ---

(defn submit-software!
  "Consumes a canonical lowered frame and renders it via Canvas2D.
   3D draws are rasterized on the CPU into an ImageData buffer;
   2D and text are painted on top with the Canvas2D API."
  [canvas lowered]
  (let [^js ctx (.getContext canvas "2d")
        [width height] (resize-canvas! canvas)
        image-data (.createImageData ctx width height)
        depth-array (js/Float32Array. (* width height))]
    ;; Clear image data and depth
    (let [clear-color (or (some (fn [pass]
                                  (some (fn [draw]
                                          (when (= :clear (:pipeline draw))
                                            (:color draw)))
                                        (:draws pass)))
                                (:passes lowered))
                          [0.0 0.0 0.0 1.0])]
      (clear-image-data! image-data clear-color)
      (clear-depth! depth-array))
    ;; Render each pass. Software backend only supports :default target.
    (doseq [pass (:passes lowered)
            :when (= :default (:target-id pass))]
      ;; Separate 3D draws from 2D/text draws
      (let [draws (:draws pass)
            three-d (filter #(#{:mesh-3d :draw-3d :line-3d} (:pipeline %))
                            draws)
            two-d (filter #(= :draw-2d (:pipeline %)) draws)
            texts (filter #(= :text (:pipeline %)) draws)]
        ;; Rasterize 3D into ImageData
        (when (seq three-d)
          (software/render-3d! three-d
                               {:width width,
                                :height height,
                                :depth-get (make-depth-get depth-array width),
                                :depth-set! (make-depth-set! depth-array width),
                                :put-pixel! (make-put-pixel! image-data)}))
        ;; Blit 3D result
        (.putImageData ctx image-data 0 0)
        ;; Paint 2D + text on top
        (doseq [draw two-d] (draw-2d-op! ctx draw height))
        (doseq [draw texts] (draw-text-op! ctx draw))))
    nil))
