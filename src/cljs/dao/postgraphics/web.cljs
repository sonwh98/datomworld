(ns dao.postgraphics.web
  "Browser dispatcher for postgraphics.  Mounts a single canvas and renders
   each frame through WebGPU when navigator.gpu is present, otherwise through
   the Canvas2D software backend (dao.postgraphics.web.canvas).  This is the
   public entry demos mount; web.gpu and web.canvas are the two submitters it
   chooses between.  The two backends need different lowering capabilities
   (the GPU path supports render targets + images; the CPU path does not), so
   the dispatcher picks the submitter and its lowering opts together."
  (:require [dao.postgraphics.lowering :as lower]
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
  "Binds the terminal to frame-stream (a DaoStream v2 handle) and returns the
   host map. The binding is held in :binding* and advanced only by `tick!`;
   binding starts no timer."
  [canvas frame-stream
   {:keys [viewport-size resolve-resource backend signal-stream], :as opts}]
  (let [{:keys [submit! supports-render-targets? supports-image?]}
        (or backend (choose-backend))
        host {:canvas canvas, :device-options (:device-options opts)}
        viewport-size (or viewport-size #(default-viewport-size canvas))
        lowering-opts {:supports-render-targets? supports-render-targets?,
                       :supports-image? supports-image?,
                       :viewport-size viewport-size,
                       :resolve-resource resolve-resource,
                       :host host}
        binding (terminal/bind
                  frame-stream
                  (merge
                    opts
                    {:signal-handle signal-stream,
                     :validate-frame! #(lower/validate-frame! % lowering-opts),
                     :present-frame!
                     (fn [frame]
                       (let [lowered (lower/lower-frame frame lowering-opts)]
                         (submit! canvas lowered)))}))]
    (merge host
           {:binding* (atom binding),
            :generation-id (:generation-id binding),
            :submit! submit!})))


(defn- tick!
  "Steps the host's binding until it is blocked (or the step bound is hit)
   and returns the last step status. The widget's interval is the only
   caller outside tests."
  [{:keys [binding*]}]
  (let [{:keys [binding status]} (terminal/step-until-blocked @binding*)]
    (reset! binding* binding)
    status))


(def ^:private frame-interval-ms 16)


(defn postgraphics-widget
  "Browser canvas widget that renders frames from frame-stream (a DaoStream
   v2 handle), dispatching to WebGPU or the Canvas2D software backend at
   mount time.  Unlike the old web.gpu widget it always mounts a canvas —
   there is no \"unsupported\" placeholder, because the software path
   renders everywhere.

  Options:
  - :on-error      callback receiving the exception when a frame is rejected
  - :signal-stream DaoStream v2 writer for canonical terminal signals
  - :canvas-attrs  Hiccup attrs merged onto the internal canvas
  - :viewport-size function returning [width height]
  - :resolve-resource function resolving image/texture resources
  - :canvas-ref callback receiving the mounted canvas or nil on teardown
  - :backend       override {:submit! :supports-render-targets? :supports-image?}"
  [frame-stream & {:keys [canvas-attrs canvas-ref], :as opts}]
  ;; Form-2 component: the constructor closes over per-instance state
  ;; (handle, set-ref!) created once; the render fn reuses the captured
  ;; opts.  The :ref callback binds and starts the frame interval on mount
  ;; (canvas non-nil) and cancels it on unmount (nil).  The interval is the
  ;; only caller of `step`: the stream wakes nothing.
  (let [handle (atom nil)
        set-ref!
        (fn [canvas]
          (if canvas
            (let [h (bind-frame-stream! canvas frame-stream opts)]
              (reset! handle
                      (assoc h
                             :interval-id
                             (js/setInterval #(tick! h) frame-interval-ms)))
              (when canvas-ref (canvas-ref canvas)))
            (do (when-let [h @handle]
                  (js/clearInterval (:interval-id h))
                  (swap! (:binding* h) terminal/close))
                (when canvas-ref (canvas-ref nil))
                (reset! handle nil))))]
    (fn [] [:canvas (assoc canvas-attrs :ref set-ref!)])))
