(ns dao.postgraphics.web
  "Browser dispatcher for postgraphics.  Mounts a single canvas and renders
   each frame through WebGPU when navigator.gpu is present, otherwise through
   the Canvas2D software backend (dao.postgraphics.web.canvas).  This is the
   public entry demos mount; web.gpu and web.canvas are the two submitters it
   chooses between.  The two backends need different lowering capabilities
   (the GPU path supports render targets + images; the CPU path does not), so
   the dispatcher picks the submitter and its lowering opts together."
  (:require
    [dao.postgraphics.lowering :as lower]
    [dao.postgraphics.terminal :as terminal]
    [dao.postgraphics.web.canvas :as sw]
    [dao.postgraphics.web.gpu :as gpu]))


(def put-frame! terminal/put-frame!)


(defn gpu-available?
  "True when WebGPU is exposed (navigator.gpu).  Re-exported so callers can
   distinguish the GPU path from the always-available software path."
  []
  (gpu/gpu-available?))


;; ---------------------------------------------------------------------------
;; Canvas sizing (DPR), applied for the software path; the WebGPU submitter
;; resizes its own canvas internally.
;; ---------------------------------------------------------------------------

(defn- resize-canvas!
  [^js canvas]
  (when (and (exists? js/window) (.-getBoundingClientRect canvas))
    (let [dpr (or (.-devicePixelRatio js/window) 1)
          rect (.getBoundingClientRect canvas)
          w (max 1 (int (* dpr (.-width rect))))
          h (max 1 (int (* dpr (.-height rect))))]
      (when (or (not= (.-width canvas) w) (not= (.-height canvas) h))
        (set! (.-width canvas) w)
        (set! (.-height canvas) h)))))


(defn- present-software!
  [^js canvas lowered]
  (resize-canvas! canvas)
  (sw/submit-software! canvas lowered))


(defn- choose-backend
  "Returns {:submit! :supports-render-targets? :supports-image?} for the
   active renderer, chosen synchronously from WebGPU availability."
  []
  (if (gpu/gpu-available?)
    {:submit! gpu/submit-webgpu!,
     :supports-render-targets? true,
     :supports-image? true}
    {:submit! present-software!,
     :supports-render-targets? false,
     :supports-image? false}))


;; ---------------------------------------------------------------------------
;; Frame-stream binding
;; ---------------------------------------------------------------------------

(defn- default-viewport-size
  [^js canvas]
  [(.-width canvas) (.-height canvas)])


(defn- bind-frame-stream!
  [canvas frame-stream
   {:keys [viewport-size resolve-resource backend], :as opts}]
  (let [{:keys [submit! supports-render-targets? supports-image?]}
        (or backend (choose-backend))
        host {:canvas canvas, :device-options (:device-options opts)}
        viewport-size (or viewport-size #(default-viewport-size canvas))
        lowering-opts {:supports-render-targets? supports-render-targets?,
                       :supports-image? supports-image?,
                       :viewport-size viewport-size,
                       :resolve-resource resolve-resource,
                       :host host}
        binding (terminal/bind-stream!
                  frame-stream
                  (merge
                    opts
                    {:validate-frame! #(lower/validate-frame! % lowering-opts),
                     :present-frame!
                     (fn [frame]
                       (let [lowered (lower/lower-frame frame lowering-opts)]
                         (submit! canvas lowered)))}))]
    (merge host binding {:submit! submit!})))


(defn postgraphics-widget
  "Browser canvas widget that renders frames from frame-stream, dispatching to
   WebGPU or the Canvas2D software backend at mount time.  Unlike the old
   web.gpu widget it always mounts a canvas — there is no \"unsupported\"
   placeholder, because the software path renders everywhere.

  Options:
  - :on-error      callback receiving the exception when a frame is rejected
  - :signal-stream dao.stream for canonical terminal signals
  - :canvas-attrs  Hiccup attrs merged onto the internal canvas
  - :viewport-size function returning [width height]
  - :resolve-resource function resolving image/texture resources
  - :backend       override {:submit! :supports-render-targets? :supports-image?}"
  [frame-stream & {:keys [canvas-attrs], :as opts}]
  ;; Form-2 component: the constructor closes over per-instance state
  ;; (handle, set-ref!) created once; the render fn reuses the captured
  ;; opts.  The :ref callback binds on mount (canvas non-nil) and tears
  ;; down on unmount (nil).
  (let [handle (atom nil)
        set-ref!
        (fn [canvas]
          (if canvas
            (reset! handle (bind-frame-stream! canvas frame-stream opts))
            (do (when-let [h @handle]
                  (when-let [close! (:close! h)] (close!)))
                (reset! handle nil))))]
    (fn [] [:canvas (assoc canvas-attrs :ref set-ref!)])))
