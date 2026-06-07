(ns dao.postgraphics.web.gpu
  (:require
    [dao.postgraphics.lowering :as lower]
    [dao.postgraphics.terminal :as terminal]
    [reagent.core :as r]))


;; --- WebGPU submitter ---
;;
;; The browser-side counterpart to dao.postgraphics.flutter/submit-gpu!.
;; Consumes the lowered frame produced above and encodes a render pass
;; per pass entry; each :mesh-3d draw becomes a drawIndexed of an
;; interleaved (pos, uv, normal, color) vertex buffer with a per-draw
;; uniform holding the mvp matrix. Per-canvas state (device, context,
;; pipeline, depth texture, texture cache) is stashed on the canvas DOM
;; node so multiple postgraphics widgets do not collide and so a single
;; widget keeps its GPU resources across React re-renders.

(def ^:private webgpu-shader
  "struct Uniforms {
     mvp : mat4x4<f32>,
   };

   @group(0) @binding(0) var<uniform> uniforms : Uniforms;
   @group(0) @binding(1) var tex : texture_2d<f32>;
   @group(0) @binding(2) var samp : sampler;

   struct VSIn {
     @location(0) position : vec3<f32>,
     @location(1) uv : vec2<f32>,
     @location(2) normal : vec3<f32>,
     @location(3) color : vec4<f32>,
   };

   struct VSOut {
     @builtin(position) position : vec4<f32>,
     @location(0) uv : vec2<f32>,
     @location(1) normal : vec3<f32>,
     @location(2) color : vec4<f32>,
   };

   @vertex
   fn vs_main(input : VSIn) -> VSOut {
     var out : VSOut;
     out.position = uniforms.mvp * vec4<f32>(input.position, 1.0);
     out.uv = input.uv;
     out.normal = input.normal;
     out.color = input.color;
     return out;
   }

   @fragment
   fn fs_main(input : VSOut) -> @location(0) vec4<f32> {
     let texColor = textureSample(tex, samp, input.uv);
     return texColor * input.color;
   }")


(defn- canvas-gpu-state
  "Returns (creating if needed) the per-canvas atom holding WebGPU state.
   Keyed off a property on the canvas DOM node so each postgraphics widget
   owns its own device/context/pipeline."
  [^js canvas]
  (or (.-_daoPgWebgpuState canvas)
      (let [s (atom {:state :idle})]
        (set! (.-_daoPgWebgpuState canvas) s)
        s)))


(defn- resize-canvas!
  [^js canvas]
  (let [dpr (or (.-devicePixelRatio js/window) 1)
        rect (.getBoundingClientRect canvas)
        w (max 1 (int (* dpr (.-width rect))))
        h (max 1 (int (* dpr (.-height rect))))]
    (when (or (not= (.-width canvas) w) (not= (.-height canvas) h))
      (set! (.-width canvas) w)
      (set! (.-height canvas) h))
    [w h]))


(defn- write-buffer!
  [^js device usage data]
  (let [^js buffer (.createBuffer device
                                  #js {:size (.-byteLength data), :usage usage})
        ^js queue (.-queue device)]
    (.writeBuffer queue buffer 0 data)
    buffer))


(defn- interleave-vertex-data
  "Packs the mesh into the (pos, uv, normal, color) vertex format the
   shader expects. Per-vertex :colors take precedence over :fill so the
   voxel demo's pre-baked face shading reaches the GPU; flutter.cljd
   already honours this precedence."
  [{:keys [vertices normals uvs colors fill]}]
  (let [n (count vertices)
        data (js/Float32Array. (* n 12))
        [fr fg fb fa] (or fill [1.0 1.0 1.0 1.0])]
    (dotimes [i n]
      (let [[x y z] (nth vertices i)
            [u v] (nth uvs i [0.0 0.0])
            [nx ny nz] (nth normals i (nth vertices i))
            [cr cg cb ca] (or (and colors (nth colors i nil)) [fr fg fb fa])
            o (* i 12)]
        (aset data o (double x))
        (aset data (+ o 1) (double y))
        (aset data (+ o 2) (double z))
        (aset data (+ o 3) (double u))
        (aset data (+ o 4) (double v))
        (aset data (+ o 5) (double nx))
        (aset data (+ o 6) (double ny))
        (aset data (+ o 7) (double nz))
        (aset data (+ o 8) (double cr))
        (aset data (+ o 9) (double cg))
        (aset data (+ o 10) (double cb))
        (aset data (+ o 11) (double ca))))
    data))


(defn- index-data
  "Uint32 indices: 65535 vertices is too tight for voxel chunks that
   approach 16^3 * 6 faces if heavily exposed."
  [indices]
  (let [data (js/Uint32Array. (* 3 (count indices)))]
    (dotimes [i (count indices)]
      (let [[a b c] (nth indices i)
            o (* i 3)]
        (aset data o a)
        (aset data (+ o 1) b)
        (aset data (+ o 2) c)))
    data))


(defn- line-index-data
  [edges]
  (let [data (js/Uint32Array. (* 2 (count edges)))]
    (dotimes [i (count edges)]
      (let [[a b] (nth edges i)
            o (* i 2)]
        (aset data o a)
        (aset data (+ o 1) b)))
    data))


(defn texture-upload-mode
  [texture]
  (cond (or (nil? texture) (nil? (:width texture)) (nil? (:height texture)))
        :white
        (:rgba texture) :rgba
        (:image texture) :image
        :else :white))


(defn- image-source->rgba
  [image width height]
  (if (and (exists? js/ImageData) (instance? js/ImageData image))
    (.-data image)
    (let [canvas (.createElement js/document "canvas")
          _ (set! (.-width canvas) width)
          _ (set! (.-height canvas) height)
          ctx (.getContext canvas "2d")]
      (.drawImage ctx image 0 0 width height)
      (.-data (.getImageData ctx 0 0 width height)))))


(defn- texture-entry!
  [state-atom texture]
  (let [state @state-atom
        ^js device (:device state)
        ^js white-texture (:white-texture state)
        texture-cache (:texture-cache state)
        source-id (:source-id texture)
        upload-mode (texture-upload-mode texture)]
    (cond (= :white upload-mode) {:texture white-texture,
                                  :view (.createView white-texture)}
          (and source-id (get texture-cache source-id)) (get texture-cache
                                                             source-id)
          :else
          (let [{:keys [image width height rgba]} texture
                ^js queue (.-queue device)
                rgba (if (= :image upload-mode)
                       (image-source->rgba image width height)
                       rgba)
                ^js gpu-texture
                (.createTexture
                  device
                  #js {:size #js [(int width) (int height) 1],
                       :format "rgba8unorm",
                       :usage (bit-or (.-TEXTURE_BINDING js/GPUTextureUsage)
                                      (.-COPY_DST js/GPUTextureUsage))})
                _ (.writeTexture queue
                                 #js {:texture gpu-texture}
                                 rgba
                                 #js {:bytesPerRow (* 4 (int width)),
                                      :rowsPerImage (int height)}
                                 #js {:width (int width),
                                      :height (int height),
                                      :depthOrArrayLayers 1})
                entry {:texture gpu-texture, :view (.createView gpu-texture)}]
            (when source-id
              (swap! state-atom assoc-in [:texture-cache source-id] entry))
            entry))))


(defn- start-webgpu-init!
  [state-atom ^js canvas]
  (let [gpu (.-gpu js/navigator)]
    (cond
      (nil? gpu) (swap! state-atom assoc :state :unsupported)
      :else
      (do
        (swap! state-atom assoc :state :initializing)
        (->
          (.requestAdapter gpu)
          (.then
            (fn [^js adapter]
              (if-not adapter
                (swap! state-atom assoc :state :failed)
                (->
                  (.requestDevice adapter)
                  (.then
                    (fn [^js device]
                      (let [^js context (.getContext canvas "webgpu")
                            format (.getPreferredCanvasFormat gpu)
                            ^js shader (.createShaderModule
                                         device
                                         #js {:code webgpu-shader})
                            ^js pipeline
                            (.createRenderPipeline
                              device
                              #js
                              {:layout "auto",
                               :vertex
                               #js {:module shader,
                                    :entryPoint "vs_main",
                                    :buffers
                                    #js
                                    [#js
                                     {:arrayStride 48,
                                      :attributes
                                      #js
                                      [#js {:shaderLocation 0,
                                            :offset 0,
                                            :format "float32x3"}
                                       #js {:shaderLocation 1,
                                            :offset 12,
                                            :format "float32x2"}
                                       #js {:shaderLocation 2,
                                            :offset 20,
                                            :format "float32x3"}
                                       #js {:shaderLocation 3,
                                            :offset 32,
                                            :format
                                            "float32x4"}]}]},
                               :fragment
                               #js {:module shader,
                                    :entryPoint "fs_main",
                                    :targets #js [#js {:format format}]},
                               :primitive #js {:topology "triangle-list",
                                               :cullMode "none"},
                               :depthStencil #js {:format "depth24plus",
                                                  :depthWriteEnabled true,
                                                  :depthCompare "less"}})
                            ^js line-pipeline
                            (.createRenderPipeline
                              device
                              #js
                              {:layout "auto",
                               :vertex
                               #js {:module shader,
                                    :entryPoint "vs_main",
                                    :buffers
                                    #js
                                    [#js
                                     {:arrayStride 48,
                                      :attributes
                                      #js
                                      [#js {:shaderLocation 0,
                                            :offset 0,
                                            :format "float32x3"}
                                       #js {:shaderLocation 1,
                                            :offset 12,
                                            :format "float32x2"}
                                       #js {:shaderLocation 2,
                                            :offset 20,
                                            :format "float32x3"}
                                       #js {:shaderLocation 3,
                                            :offset 32,
                                            :format
                                            "float32x4"}]}]},
                               :fragment
                               #js {:module shader,
                                    :entryPoint "fs_main",
                                    :targets #js [#js {:format format}]},
                               :primitive #js {:topology "line-list",
                                               :cullMode "none"},
                               :depthStencil #js {:format "depth24plus",
                                                  :depthWriteEnabled true,
                                                  :depthCompare "less"}})
                            ^js sampler (.createSampler
                                          device
                                          #js {:magFilter "linear",
                                               :minFilter "linear",
                                               :addressModeU "repeat",
                                               :addressModeV "repeat"})
                            ^js white-texture
                            (.createTexture
                              device
                              #js {:size #js [1 1 1],
                                   :format "rgba8unorm",
                                   :usage (bit-or (.-TEXTURE_BINDING
                                                    js/GPUTextureUsage)
                                                  (.-COPY_DST
                                                    js/GPUTextureUsage))})]
                        (.writeTexture
                          (.-queue device)
                          #js {:texture white-texture}
                          (js/Uint8Array. #js [255 255 255 255])
                          #js {:bytesPerRow 4,
                               :width 1,
                               :height 1,
                               :depthOrArrayLayers 1}
                          #js {:width 1, :height 1, :depthOrArrayLayers 1})
                        (.configure context
                                    #js {:device device,
                                         :format format,
                                         :alphaMode "opaque"})
                        (swap! state-atom assoc
                               :state :ready
                               :device device
                               :context context
                               :format format
                               :pipeline pipeline
                               :line-pipeline line-pipeline
                               :sampler sampler
                               :white-texture white-texture
                               :depth-texture nil
                               :depth-size [0 0]
                               :texture-cache {}
                               :canvas canvas))))
                  (.catch (fn [err]
                            (js/console.error "WebGPU device init failed" err)
                            (swap! state-atom assoc :state :failed)))))))
          (.catch (fn [err]
                    (js/console.error "WebGPU adapter init failed" err)
                    (swap! state-atom assoc :state :failed))))))))


(defn- ensure-depth-texture!
  [state-atom width height]
  (let [{:keys [device depth-texture depth-size]} @state-atom]
    (if (and depth-texture (= depth-size [width height]))
      depth-texture
      (let [^js tex (.createTexture device
                                    #js {:size #js [(int width) (int height) 1],
                                         :format "depth24plus",
                                         :usage (.-RENDER_ATTACHMENT
                                                  js/GPUTextureUsage)})]
        (swap! state-atom assoc :depth-texture tex :depth-size [width height])
        tex))))


(defn- gpu-clear-color
  [lowered]
  (or (some (fn [pass]
              (some (fn [draw] (when (= :clear (:pipeline draw)) (:color draw)))
                    (:draws pass)))
            (:passes lowered))
      [0.0 0.0 0.0 1.0]))


(defn- encode-mesh!
  [state-atom ^js pass ^js device draw]
  (let [{:keys [payload texture mvp]} draw
        v-data (interleave-vertex-data payload)
        i-data (index-data (:indices payload))
        u-data (js/Float32Array. (clj->js mvp))
        v-buffer (write-buffer! device
                                (bit-or (.-VERTEX js/GPUBufferUsage)
                                        (.-COPY_DST js/GPUBufferUsage))
                                v-data)
        i-buffer (write-buffer! device
                                (bit-or (.-INDEX js/GPUBufferUsage)
                                        (.-COPY_DST js/GPUBufferUsage))
                                i-data)
        u-buffer (write-buffer! device
                                (bit-or (.-UNIFORM js/GPUBufferUsage)
                                        (.-COPY_DST js/GPUBufferUsage))
                                u-data)
        state @state-atom
        ^js pipeline (:pipeline state)
        sampler (:sampler state)
        {:keys [view]} (texture-entry! state-atom texture)
        bind-group (.createBindGroup
                     device
                     #js {:layout (.getBindGroupLayout pipeline 0),
                          :entries #js [#js {:binding 0,
                                             :resource #js {:buffer u-buffer}}
                                        #js {:binding 1, :resource view}
                                        #js {:binding 2, :resource sampler}]})]
    (.setPipeline pass pipeline)
    (.setBindGroup pass 0 bind-group)
    (.setVertexBuffer pass 0 v-buffer)
    (.setIndexBuffer pass i-buffer "uint32")
    (.drawIndexed pass (.-length i-data) 1 0 0 0)))


(defn- encode-lines!
  [state-atom ^js pass ^js device draw]
  (let [{:keys [payload mvp]} draw
        v-data (interleave-vertex-data (assoc payload
                                              :fill (:color payload)
                                              :normals nil
                                              :uvs nil
                                              :colors nil))
        i-data (line-index-data (:edges payload))
        u-data (js/Float32Array. (clj->js mvp))
        v-buffer (write-buffer! device
                                (bit-or (.-VERTEX js/GPUBufferUsage)
                                        (.-COPY_DST js/GPUBufferUsage))
                                v-data)
        i-buffer (write-buffer! device
                                (bit-or (.-INDEX js/GPUBufferUsage)
                                        (.-COPY_DST js/GPUBufferUsage))
                                i-data)
        u-buffer (write-buffer! device
                                (bit-or (.-UNIFORM js/GPUBufferUsage)
                                        (.-COPY_DST js/GPUBufferUsage))
                                u-data)
        state @state-atom
        ^js pipeline (:line-pipeline state)
        sampler (:sampler state)
        {:keys [view]} (texture-entry! state-atom nil)
        bind-group (.createBindGroup
                     device
                     #js {:layout (.getBindGroupLayout pipeline 0),
                          :entries #js [#js {:binding 0,
                                             :resource #js {:buffer u-buffer}}
                                        #js {:binding 1, :resource view}
                                        #js {:binding 2, :resource sampler}]})]
    (.setPipeline pass pipeline)
    (.setBindGroup pass 0 bind-group)
    (.setVertexBuffer pass 0 v-buffer)
    (.setIndexBuffer pass i-buffer "uint32")
    (.drawIndexed pass (.-length i-data) 1 0 0 0)))


(defn submit-webgpu!
  "WebGPU :submit! for postgraphics-widget. Lazily acquires the GPU
   device on the first call per canvas; subsequent calls reuse the
   cached pipeline + context. Clear color comes from the first :clear
   draw in any pass (matching flutter.cljd's gpu-clear-color)."
  [canvas lowered]
  (let [state-atom (canvas-gpu-state canvas)
        {:keys [state]} @state-atom]
    (case state
      :idle (start-webgpu-init! state-atom canvas)
      (:unsupported :failed :initializing) nil
      :ready
      (let [state @state-atom
            ^js device (:device state)
            ^js context (:context state)
            [width height] (resize-canvas! canvas)
            [r g b a] (gpu-clear-color lowered)
            ^js current-texture (.getCurrentTexture context)
            color-view (.createView current-texture)
            ^js depth-texture (ensure-depth-texture! state-atom width height)
            depth-view (.createView depth-texture)
            ^js encoder (.createCommandEncoder device)
            ^js pass
            (.beginRenderPass
              encoder
              #js {:colorAttachments
                   #js [#js {:view color-view,
                             :clearValue #js {:r r, :g g, :b b, :a a},
                             :loadOp "clear",
                             :storeOp "store"}],
                   :depthStencilAttachment #js {:view depth-view,
                                                :depthClearValue 1.0,
                                                :depthLoadOp "clear",
                                                :depthStoreOp "store"}})]
        (doseq [pass-data (:passes lowered)
                draw (:draws pass-data)]
          (case (:pipeline draw)
            (:mesh-3d :mesh-textured-3d)
            (encode-mesh! state-atom pass device draw)
            :line-3d (encode-lines! state-atom pass device draw)
            nil))
        (.end pass)
        (.submit (.-queue device) #js [(.finish encoder)])
        nil))))


(defn- default-submit!
  [canvas lowered]
  (submit-webgpu! canvas lowered))


(defn gpu-available?
  "Synchronous capability probe, the browser counterpart to
   dao.postgraphics.flutter.gpu/gpu-available?. The WebGPU spec exposes the
   `gpu` getter on `navigator` only when the browser ships an implementation,
   so a nil-check is enough to detect the unsupported case without going async."
  []
  (boolean (and (exists? js/navigator) (.-gpu js/navigator))))


(defn- webgpu-unsupported-view
  "Inline notice rendered in place of the canvas when navigator.gpu is
   missing. Minimum browser versions are spelled out so the user knows
   what to upgrade to."
  [canvas-attrs]
  (let [base-style {:padding "20px 22px",
                    :border "1px solid rgba(255,140,140,0.35)",
                    :border-radius "16px",
                    :background "rgba(40,12,12,0.6)",
                    :color "#ffe4e4",
                    :font-family "system-ui, ui-sans-serif, sans-serif",
                    :line-height "1.55",
                    :box-sizing "border-box"}
        style (merge base-style (:style canvas-attrs))]
    [:div {:style style}
     [:strong
      {:style {:display "block",
               :font-size "18px",
               :margin-bottom "8px",
               :color "#ffd4d4"}} "WebGPU not available"]
     [:p {:style {:margin "0 0 10px"}}
      "This demo renders through WebGPU and your browser does not expose "
      [:code "navigator.gpu"] ". You can run it in:"]
     [:ul {:style {:margin "0", :padding-left "20px"}}
      [:li "Chrome or Edge " [:strong "113"] " or newer (May 2023)"]
      [:li "Safari " [:strong "18"]
       " or newer (macOS Sequoia / iOS 18, Sept 2024)"]
      [:li "Firefox " [:strong "141"] " or newer on Windows (July 2025); "
       "other platforms still rolling out, set " [:code "dom.webgpu.enabled"]
       " in " [:code "about:config"] " on older Nightly builds"]]]))


(def put-frame! terminal/put-frame!)


(defn- default-viewport-size
  [canvas]
  [(.-width canvas) (.-height canvas)])


(defn- lowering-opts
  [viewport-size resolve-resource host]
  {:supports-render-targets? true,
   :supports-image? true,
   :viewport-size viewport-size,
   :resolve-resource resolve-resource,
   :host host})


(defn- bind-frame-stream!
  [canvas frame-stream
   {:keys [on-error signal-stream device-options viewport-size submit!
           resolve-resource generation-id generation-id-fn],
    :or {submit! default-submit!}}]
  (let [host {:canvas canvas, :device-options device-options}
        viewport-size (or viewport-size #(default-viewport-size canvas))
        lowering-opts (lowering-opts viewport-size resolve-resource host)
        binding (terminal/bind-stream!
                  frame-stream
                  {:validate-frame! #(lower/validate-frame! % lowering-opts),
                   :present-frame!
                   (fn [frame]
                     (let [lowered (lower/lower-frame frame lowering-opts)]
                       (submit! canvas lowered))),
                   :signal-stream signal-stream,
                   :generation-id generation-id,
                   :generation-id-fn (or generation-id-fn
                                         terminal/new-generation-id),
                   :on-error on-error})]
    (merge host binding {:submit! submit!})))


(defn frame-stream-binding-test-hook
  [canvas frame-stream opts]
  (bind-frame-stream! canvas frame-stream opts))


(defn postgraphics-widget
  "Returns a browser canvas widget that renders frames emitted by frame-stream.

  Options:
  - :on-error      callback receiving the exception when a frame is rejected
  - :signal-stream dao.stream to which canonical terminal signals
                   (:dao.terminal/reset, :dao.terminal/rejection) are emitted;
                   required for participants in dao.gui.event

  Web options:
  - :canvas-attrs     Hiccup attrs merged onto the internal canvas
  - :submit!          host encoder called as (submit! canvas lowered-frame)
  - :viewport-size    function returning [width height]
  - :resolve-resource function resolving image/texture resources"
  [frame-stream & {:keys [canvas-attrs], :as opts}]
  (let [canvas-ref (atom nil)
        terminal-handle (atom nil)]
    (r/create-class
      {:display-name "dao-postgraphics-web-gpu-widget",
       :component-did-mount (fn [_]
                              (when-let [canvas @canvas-ref]
                                (reset! terminal-handle (bind-frame-stream!
                                                          canvas
                                                          frame-stream
                                                          opts)))),
       :component-will-unmount (fn [_]
                                 (when-let [handle @terminal-handle]
                                   (when-let [close! (:close! handle)]
                                     (close!)))
                                 (reset! terminal-handle nil)
                                 (reset! canvas-ref nil)),
       :reagent-render (fn []
                         (if (gpu-available?)
                           [:canvas
                            (assoc canvas-attrs :ref #(reset! canvas-ref %))]
                           [webgpu-unsupported-view canvas-attrs]))})))
