(ns dao.postgraphics.web.gpu
  (:require
    [dao.postgraphics.packing :as pack]
    [dao.postgraphics.terminal :as terminal]))


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

;; The fragment shader reproduces dao.postgraphics.raster/shade-mesh-fragment
;; (Blinn-Phong in linear space, sRGB encode) so the WebGPU path lights exactly
;; like the software path.  The uniform layout matches
;; packing/packed-vertex-uniforms
;; ++ packing/packed-lighting-block: three matrices, then
;; camera/material/lights.
;; The canvas target is a non-sRGB format, so the shader writes sRGB bytes
;; itself
;; (no automatic linear→sRGB), just as the software path writes into ImageData.
;; The lights array is fixed at packing/max-packed-lights (8).
(def ^:private webgpu-shader
  "struct Light {
     color : vec4<f32>,      // rgb, intensity (w)
     vec : vec4<f32>,        // direction|position (xyz), range (w)
     kind : vec4<f32>,       // 0 ambient, 1 directional, 2 point
   };

   struct Uniforms {
     mvp : mat4x4<f32>,
     model : mat4x4<f32>,
     modelInv : mat4x4<f32>,
     cameraPos : vec4<f32>,   // xyz, lightCount (w)
     material0 : vec4<f32>,   // specular rgb, shininess (w)
     material1 : vec4<f32>,   // emissive rgb (sRGB), lightingEnabled (w)
     lights : array<Light, 8>,
   };

   @group(0) @binding(0) var<uniform> u : Uniforms;
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
     @location(3) worldPos : vec3<f32>,
   };

   fn srgbToLinear(c : vec3<f32>) -> vec3<f32> {
     let lo = c / 12.92;
     let hi = pow((c + vec3<f32>(0.055)) / 1.055, vec3<f32>(2.4));
     return select(hi, lo, c <= vec3<f32>(0.04045));
   }

   fn linearToSrgb(c : vec3<f32>) -> vec3<f32> {
     let lo = c * 12.92;
     let hi = 1.055 * pow(c, vec3<f32>(1.0 / 2.4)) - vec3<f32>(0.055);
     return select(hi, lo, c <= vec3<f32>(0.0031308));
   }

   @vertex
   fn vs_main(input : VSIn) -> VSOut {
     var out : VSOut;
     out.position = u.mvp * vec4<f32>(input.position, 1.0);
     out.worldPos = (u.model * vec4<f32>(input.position, 1.0)).xyz;
     let nm = transpose(mat3x3<f32>(u.modelInv[0].xyz,
                                    u.modelInv[1].xyz,
                                    u.modelInv[2].xyz));
     out.normal = normalize(nm * input.normal);
     out.uv = input.uv;
     out.color = input.color;
     return out;
   }

   @fragment
   fn fs_main(input : VSOut) -> @location(0) vec4<f32> {
     let texColor = textureSample(tex, samp, input.uv);
     let texResult = texColor * input.color;
     if (u.material1.w < 0.5) {
       return clamp(texResult, vec4<f32>(0.0), vec4<f32>(1.0));
     }
     let Kd = srgbToLinear(texResult.rgb);
     let Ks = u.material0.rgb;
     let shininess = u.material0.w;
     let Ke = srgbToLinear(u.material1.rgb);
     let N = input.normal;
     let viewDir = normalize(u.cameraPos.xyz - input.worldPos);
     var amb = vec3<f32>(0.0);
     var dif = vec3<f32>(0.0);
     var spec = vec3<f32>(0.0);
     let count = i32(u.cameraPos.w);
     for (var i = 0; i < count; i = i + 1) {
       let L = u.lights[i];
       let lc = L.color.rgb;
       let intensity = L.color.w;
       let kind = L.kind.x;
       if (kind < 0.5) {
         amb = amb + lc * intensity;
       } else if (kind < 1.5) {
         let Ldir = normalize(L.vec.xyz);
         let nl = max(0.0, dot(N, Ldir));
         let H = normalize(Ldir + viewDir);
         let nh = max(0.0, dot(N, H));
         let sp = pow(nh, shininess);
         dif = dif + lc * nl * intensity;
         spec = spec + lc * sp * intensity;
       } else {
         let toLight = L.vec.xyz - input.worldPos;
         let dist = length(toLight);
         let Ldir = normalize(toLight);
         let nl = max(0.0, dot(N, Ldir));
         let H = normalize(Ldir + viewDir);
         let nh = max(0.0, dot(N, H));
         let sp = pow(nh, shininess);
         let t = 1.0 - dist / L.vec.w;
         let atten = select(0.0, t * t, t > 0.0);
         dif = dif + lc * nl * intensity * atten;
         spec = spec + lc * sp * intensity * atten;
       }
     }
     let lit = Kd * amb + Kd * dif + Ks * spec + Ke;
     let outRgb = clamp(linearToSrgb(lit), vec3<f32>(0.0), vec3<f32>(1.0));
     return vec4<f32>(outRgb, clamp(texResult.a, 0.0, 1.0));
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


(defn interleave-vertex-data
  "Packs the mesh op into the (pos, uv, normal, color) vertex format the
   shader expects.  The 12-float layout (attributes resolved via vertex-attrs)
   is shared with the Flutter backend through packing/pack-vertex-floats!."
  [op]
  (let [data (js/Float32Array. (* (count (:vertices op)) 12))]
    (pack/pack-vertex-floats! op (fn [i x] (aset data i x)))
    data))


(defn- index-data
  "Uint32 indices (32-bit because voxel chunks approach 16^3 * 6 faces, too
   tight for 65535).  tuples are triangles [a b c] or edges [a b]."
  [tuples width]
  (let [data (js/Uint32Array. (* width (count tuples)))]
    (pack/pack-indices! tuples (fn [i x] (aset data i x)))
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


(defn- encode-mesh!
  [state-atom ^js pass ^js device draw]
  (let [{:keys [payload texture]} draw
        v-data (interleave-vertex-data payload)
        i-data (index-data (:indices payload) 3)
        u-data (js/Float32Array. (clj->js (into
                                            (pack/packed-vertex-uniforms draw)
                                            (pack/packed-lighting-block draw))))
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
  (let [;; lines are unlit and single-coloured; the shared collapse forces
        ;; lighting off and feeds :color through :fill for every vertex.
        line-draw (pack/unlit-line-draw draw)
        line-op (:op line-draw)
        v-data (interleave-vertex-data line-op)
        i-data (index-data (:edges line-op) 2)
        u-data (js/Float32Array.
                 (clj->js (into (pack/packed-vertex-uniforms line-draw)
                                (pack/packed-lighting-block line-draw))))
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
   draw in any pass (via the shared packing/clear-color)."
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
            [r g b a] (pack/clear-color lowered)
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


(defn gpu-available?
  "Synchronous capability probe, the browser counterpart to
   dao.postgraphics.flutter.gpu/gpu-available?. The WebGPU spec exposes the
   `gpu` getter on `navigator` only when the browser ships an implementation,
   so a nil-check is enough to detect the unsupported case without going async."
  []
  (boolean (and (exists? js/navigator) (.-gpu js/navigator))))


(def put-frame! terminal/put-frame!)
