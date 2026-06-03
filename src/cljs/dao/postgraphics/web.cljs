(ns dao.postgraphics.web
  "Browser dispatcher for dao.postgraphics. Detects WebGPU support and
   delegates to either dao.postgraphics.web.gpu (WebGPU) or
   dao.postgraphics.web.canvas (Canvas2D software)."
  (:require
    [dao.postgraphics.lowering :as lowering]
    [dao.postgraphics.terminal :as terminal]
    [dao.postgraphics.web.canvas :as canvas]
    [dao.postgraphics.web.gpu :as gpu]
    [reagent.core :as r]))


(def put-frame! terminal/put-frame!)


(defn- webgpu-supported?
  "Synchronous capability probe."
  []
  (boolean (and (exists? js/navigator) (.-gpu js/navigator))))


(defn- default-viewport-size
  [canvas]
  [(.-width canvas) (.-height canvas)])


(defn- target-id-source?
  [source]
  (and (vector? source)
       (= [:target/id] (subvec source 0 1))
       (= 2 (count source))))


(defn- backend-caps
  "Per-backend capability flags threaded into both validate-frame! and
   lower-frame. The WebGPU backend supports offscreen render targets; the
   Canvas2D software backend does not (a single canvas has no synchronous
   offscreen pass)."
  [gpu?]
  {:supports-render-targets? (boolean gpu?),
   :supports-image? true,
   :texture-source-valid? target-id-source?})


(defn- bind-frame-stream!
  [canvas frame-stream submit! gpu?
   {:keys [on-error signal-stream device-options viewport-size resolve-resource
           generation-id generation-id-fn]}]
  (let [host {:canvas canvas, :device-options device-options}
        viewport-size (or viewport-size #(default-viewport-size canvas))
        caps (backend-caps gpu?)
        binding
        (terminal/bind-stream!
          frame-stream
          {:validate-frame! (fn [frame]
                              (lowering/validate-frame! frame caps)),
           :present-frame!
           (fn [frame]
             (let [lowered (lowering/lower-frame
                             frame
                             (merge caps
                                    {:viewport-size viewport-size,
                                     :resolve-resource resolve-resource,
                                     :host host}))]
               (submit! canvas lowered))),
           :signal-stream signal-stream,
           :generation-id generation-id,
           :generation-id-fn (or generation-id-fn terminal/new-generation-id),
           :on-error on-error})]
    (merge host binding {:submit! submit!})))


(defn frame-stream-binding-test-hook
  [canvas frame-stream opts]
  (let [gpu? (webgpu-supported?)
        submit! (or (:submit! opts)
                    (if gpu? gpu/submit-webgpu! canvas/submit-software!))]
    (bind-frame-stream! canvas
                        frame-stream
                        submit!
                        gpu?
                        (dissoc opts :submit!))))


(defn postgraphics-widget
  "Returns a browser canvas widget that renders frames emitted by frame-stream.

   Options:
   - :on-error      callback receiving the exception when a frame is rejected
   - :signal-stream dao.stream to which canonical terminal signals are emitted
   - :canvas-attrs  Hiccup attrs merged onto the internal canvas
   - :viewport-size function returning [width height]
   - :resolve-resource function resolving image/texture resources
   - :device-options options passed to the WebGPU backend"
  [frame-stream & {:keys [canvas-attrs], :as opts}]
  (let [canvas-ref (atom nil)
        terminal-handle (atom nil)
        gpu? (webgpu-supported?)
        submit! (if gpu? gpu/submit-webgpu! canvas/submit-software!)]
    (r/create-class
      {:display-name "dao-postgraphics-web-widget",
       :component-did-mount
       (fn [_]
         (when-let [canvas @canvas-ref]
           (reset! terminal-handle
                   (bind-frame-stream! canvas frame-stream submit! gpu? opts)))),
       :component-will-unmount (fn [_]
                                 (when-let [handle @terminal-handle]
                                   (when-let [close! (:close! handle)]
                                     (close!)))
                                 (reset! terminal-handle nil)
                                 (reset! canvas-ref nil)),
       :reagent-render (fn []
                         [:canvas
                          (assoc (or canvas-attrs {})
                                 :ref #(reset! canvas-ref %))])})))
