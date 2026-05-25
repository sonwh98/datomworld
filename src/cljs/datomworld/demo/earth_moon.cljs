(ns datomworld.demo.earth-moon
  (:require
    [dao.postgraphics.terminal :as terminal]
    [dao.postgraphics.webgpu :as pg]
    [dao.stream :as ds]
    [dao.stream.ringbuffer]
    [datomworld.demo.earth-moon-scene :as scene]
    [reagent.core :as r]))


(defonce frame-stream
  (ds/open! {:type :ringbuffer, :capacity 4, :eviction-policy :evict-oldest}))


(defonce scene-state (r/atom {:seconds 0.0, :animating? true}))


(defonce interval-id (atom nil))
(defonce started-at-ms* (atom nil))
(defonce earth-texture* (atom nil))
(defonce moon-texture* (atom nil))
(defonce ring-texture* (atom nil))
(defonce webgpu-state* (atom nil))


(defn- load-image-texture!
  [tex-atom asset-path]
  (when (nil? @tex-atom)
    (let [img (js/Image.)]
      (set! (.-crossOrigin img) "anonymous")
      (set! (.-onload img)
            (fn []
              (reset! tex-atom {:image img,
                                :width (.-naturalWidth img),
                                :height (.-naturalHeight img),
                                :source-id asset-path})))
      (set! (.-src img) asset-path))))


(defn- ring-darkness
  [v]
  (let [edge-fade (* 4.0 v (- 1.0 v))
        body (+ 0.55 (* 0.45 edge-fade))
        gap (cond (< (js/Math.abs (- v 0.18)) 0.025) 0.45
                  (< (js/Math.abs (- v 0.50)) 0.035) 0.30
                  (< (js/Math.abs (- v 0.78)) 0.020) 0.55
                  :else 1.0)]
    (* body gap)))


(defn- make-ring-canvas
  [width height]
  (let [canvas (.createElement js/document "canvas")
        _ (set! (.-width canvas) width)
        _ (set! (.-height canvas) height)
        ctx (.getContext canvas "2d")
        image-data (.createImageData ctx width height)
        data (.-data image-data)]
    (dotimes [y height]
      (let [v (/ y (max 1 (dec height)))
            b (ring-darkness v)
            r (int (* 255 0.92 b))
            g (int (* 255 0.84 b))
            blue (int (* 255 0.66 b))]
        (dotimes [x width]
          (let [offset (* 4 (+ (* y width) x))]
            (aset data offset r)
            (aset data (+ offset 1) g)
            (aset data (+ offset 2) blue)
            (aset data (+ offset 3) 255)))))
    (.putImageData ctx image-data 0 0)
    {:image canvas, :width width, :height height, :source-id :ring-texture}))


(defn- ensure-ring-texture!
  []
  (when (nil? @ring-texture*) (reset! ring-texture* (make-ring-canvas 4 256))))


(defn- current-textures
  []
  {:earth-tex @earth-texture*,
   :moon-tex @moon-texture*,
   :ring-tex @ring-texture*})


(defn- mat4
  [& xs]
  (js/Float32Array. (clj->js xs)))


(defn- mat4-mul
  [a b]
  (let [out (js/Float32Array. 16)]
    (dotimes [col 4]
      (dotimes [row 4]
        (let [i (+ (* col 4) row)]
          (aset out
                i
                (+ (* (aget a row) (aget b (* col 4)))
                   (* (aget a (+ 4 row)) (aget b (+ (* col 4) 1)))
                   (* (aget a (+ 8 row)) (aget b (+ (* col 4) 2)))
                   (* (aget a (+ 12 row)) (aget b (+ (* col 4) 3))))))))
    out))


(defn- mat4-translation
  [[tx ty tz]]
  (mat4 1 0 0 0 0 1 0 0 0 0 1 0 tx ty tz 1))


(defn- mat4-rotation-x
  [a]
  (let [c (js/Math.cos a)
        s (js/Math.sin a)]
    (mat4 1 0 0 0 0 c s 0 0 (- s) c 0 0 0 0 1)))


(defn- mat4-rotation-y
  [a]
  (let [c (js/Math.cos a)
        s (js/Math.sin a)]
    (mat4 c 0 (- s) 0 0 1 0 0 s 0 c 0 0 0 0 1)))


(defn- mat4-rotation-z
  [a]
  (let [c (js/Math.cos a)
        s (js/Math.sin a)]
    (mat4 c s 0 0 (- s) c 0 0 0 0 1 0 0 0 0 1)))


(defn- mat4-perspective
  [fov-deg aspect near far]
  (let [f (/ 1.0 (js/Math.tan (/ (* fov-deg js/Math.PI) 360.0)))
        z-range (- near far)]
    (mat4 (/ f aspect)
          0
          0
          0
          0
          f
          0
          0
          0
          0
          (/ far z-range)
          -1
          0
          0
          (/ (* near far) z-range)
          0)))


(defn- camera-view-matrix
  [{position :camera3d/position, rotation :camera3d/rotation}]
  (let [[cx cy cz] (or position [0.0 0.0 0.0])
        [rx ry rz] (or rotation [0.0 0.0 0.0])]
    (-> (mat4-rotation-z (- rz))
        (mat4-mul (mat4-rotation-y (- ry)))
        (mat4-mul (mat4-rotation-x (- rx)))
        (mat4-mul (mat4-translation [(- cx) (- cy) (- cz)])))))


(defn- camera-projection-matrix
  [{fov :camera3d/fov, near :camera3d/near, far :camera3d/far} width height]
  (mat4-perspective (or fov 48.0) (/ width height) (or near 0.1) (or far 80.0)))


(defn- write-buffer!
  [^js device usage data]
  (let [^js buffer (.createBuffer device
                                  #js {:size (.-byteLength data), :usage usage})
        ^js queue (.-queue device)]
    (.writeBuffer queue buffer 0 data)
    buffer))


(defn- interleave-vertex-data
  [{:keys [vertices normals uvs fill]}]
  (let [n (count vertices)
        data (js/Float32Array. (* n 12))
        [fr fg fb fa] (or fill [1.0 1.0 1.0 1.0])]
    (dotimes [i n]
      (let [[x y z] (nth vertices i)
            [u v] (nth uvs i [0.0 0.0])
            [nx ny nz] (nth normals i (nth vertices i))
            o (* i 12)]
        (aset data o (double x))
        (aset data (+ o 1) (double y))
        (aset data (+ o 2) (double z))
        (aset data (+ o 3) (double u))
        (aset data (+ o 4) (double v))
        (aset data (+ o 5) (double nx))
        (aset data (+ o 6) (double ny))
        (aset data (+ o 7) (double nz))
        (aset data (+ o 8) (double fr))
        (aset data (+ o 9) (double fg))
        (aset data (+ o 10) (double fb))
        (aset data (+ o 11) (double fa))))
    data))


(defn- index-data
  [indices]
  (let [data (js/Uint16Array. (* 3 (count indices)))]
    (dotimes [i (count indices)]
      (let [[a b c] (nth indices i)
            o (* i 3)]
        (aset data o a)
        (aset data (+ o 1) b)
        (aset data (+ o 2) c)))
    data))


(def ^:private webgpu-shader
  "struct Uniforms {
     mvp : mat4x4<f32>,
     ambient : vec4<f32>,
     lightColor : vec4<f32>,
     lightDir : vec4<f32>,
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


(declare resize-canvas!)


(defn- ensure-webgpu-state!
  [canvas]
  (when (and canvas (not (:ready? @webgpu-state*)))
    (let [gpu (.-gpu js/navigator)]
      (cond
        (nil? gpu) (swap! webgpu-state* assoc
                          :initializing? false
                          :failed? true
                          :unsupported? true)
        (not (:initializing? @webgpu-state*))
        (do
          (swap! webgpu-state* assoc :initializing? true :canvas canvas)
          (let [adapter-promise (.requestAdapter gpu)]
            (.then
              adapter-promise
              (fn [^js adapter]
                (if-not adapter
                  (swap! webgpu-state* assoc
                         :initializing? false
                         :failed? true)
                  (let [device-promise (.requestDevice adapter)]
                    (.then
                      device-promise
                      (fn [^js device]
                        (let [^js context (.getContext canvas "webgpu")
                              format (.getPreferredCanvasFormat gpu)
                              ^js shader-module (.createShaderModule
                                                  device
                                                  #js {:code webgpu-shader})
                              ^js pipeline
                              (.createRenderPipeline
                                device
                                #js
                                {:layout "auto",
                                 :vertex
                                 #js {:module shader-module,
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
                                 :fragment #js {:module shader-module,
                                                :entryPoint "fs_main",
                                                :targets
                                                #js [#js {:format
                                                          format}]},
                                 :primitive #js {:topology "triangle-list",
                                                 :cullMode "none"},
                                 :depthStencil #js {:format "depth24plus",
                                                    :depthWriteEnabled
                                                    true,
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
                                                      js/GPUTextureUsage))})
                              white-bytes (js/Uint8Array. #js [255 255 255
                                                               255])
                              ^js queue (.-queue device)]
                          (.writeTexture
                            queue
                            #js {:texture white-texture}
                            white-bytes
                            #js {:bytesPerRow 4,
                                 :width 1,
                                 :height 1,
                                 :depthOrArrayLayers 1}
                            #js {:width 1, :height 1, :depthOrArrayLayers 1})
                          (.configure context
                                      #js {:device device,
                                           :format format,
                                           :alphaMode "opaque"})
                          (swap! webgpu-state* assoc
                                 :adapter adapter
                                 :device device
                                 :context context
                                 :format format
                                 :pipeline pipeline
                                 :sampler sampler
                                 :white-texture white-texture
                                 :texture-cache {}
                                 :canvas canvas
                                 :ready? true
                                 :initializing? false)))
                      (.catch device-promise
                              (fn [err]
                                (js/console.error "WebGPU device init failed"
                                                  err)
                                (swap! webgpu-state* assoc
                                       :initializing? false
                                       :failed? true)))))))
              (.catch adapter-promise
                      (fn [err]
                        (js/console.error "WebGPU adapter init failed" err)
                        (swap! webgpu-state* assoc
                               :initializing? false
                               :failed? true))))))))))


(defn- texture-entry!
  [texture]
  (let [{:keys [texture-cache]} @webgpu-state*
        ^js device (:device @webgpu-state*)
        ^js white-texture (:white-texture @webgpu-state*)]
    (when device
      (let [source-id (:source-id texture)]
        (cond
          (nil? texture) {:texture white-texture,
                          :view (.createView white-texture)}
          (and source-id (get texture-cache source-id)) (get texture-cache
                                                             source-id)
          (or (nil? (:image texture))
              (nil? (:width texture))
              (nil? (:height texture)))
          {:texture white-texture, :view (.createView white-texture)}
          :else
          (let [{:keys [image width height]} texture
                ^js queue (.-queue device)
                ^js gpu-texture
                (.createTexture
                  device
                  #js {:size #js [(int width) (int height) 1],
                       :format "rgba8unorm",
                       :usage (bit-or (.-TEXTURE_BINDING js/GPUTextureUsage)
                                      (.-COPY_DST js/GPUTextureUsage))})
                _ (.copyExternalImageToTexture queue
                                               #js {:source image}
                                               #js {:texture gpu-texture}
                                               #js {:width (int width),
                                                    :height (int height),
                                                    :depthOrArrayLayers 1})
                entry {:texture gpu-texture, :view (.createView gpu-texture)}]
            (swap! webgpu-state* assoc
                   :texture-cache
                   (assoc texture-cache source-id entry))
            entry))))))


(defn- encode-mesh!
  [^js pass ^js device draw width height]
  (let [{:keys [payload texture camera lights]} draw
        {:keys [indices]} payload
        ambient-light (or (some #(when (= :ambient (:kind %)) %) lights)
                          {:color [0.08 0.1 0.14]})
        directional-light (or (some #(when (= :directional (:kind %)) %) lights)
                              {:color [1.0 0.96 0.86],
                               :direction [-0.4 0.7 0.9],
                               :intensity 1.55})
        [ax ay az] (:color ambient-light)
        [lx ly lz] (:direction directional-light)
        intensity (double (get directional-light :intensity 1.0))
        [lcx lcy lcz] (:color directional-light)
        ambient (js/Float32Array. #js [ax ay az 1.0])
        light-color (js/Float32Array. #js [(* intensity lcx) (* intensity lcy)
                                           (* intensity lcz) 1.0])
        light-dir (js/Float32Array. #js [lx ly lz 0.0])
        v-data (interleave-vertex-data payload)
        i-data (index-data indices)
        vp (if (vector? (:mvp draw))
             (js/Float32Array. (clj->js (:mvp draw)))
             (let [camera-matrix (camera-view-matrix camera)
                   projection (camera-projection-matrix camera width height)]
               (mat4-mul projection camera-matrix)))
        u-data (js/Float32Array. 28)
        _ (.set u-data vp 0)
        _ (.set u-data ambient 16)
        _ (.set u-data light-color 20)
        _ (.set u-data light-dir 24)
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
        texture-entry (texture-entry! texture)
        ^js pipeline (:pipeline @webgpu-state*)
        ^js gpu-texture (:texture texture-entry)
        bind-group
        (.createBindGroup
          device
          #js {:layout (.getBindGroupLayout pipeline 0),
               :entries
               #js [#js {:binding 0, :resource #js {:buffer u-buffer}}
                    #js {:binding 1, :resource (.createView gpu-texture)}
                    #js {:binding 2,
                         :resource (:sampler @webgpu-state*)}]})]
    (.setPipeline pass pipeline)
    (.setBindGroup pass 0 bind-group)
    (.setVertexBuffer pass 0 v-buffer)
    (.setIndexBuffer pass i-buffer "uint16")
    (.drawIndexed pass (.-length i-data) 1 0 0 0)))


(defn- submit-webgpu!
  [canvas lowered]
  (ensure-webgpu-state! canvas)
  (let [{:keys [ready?]} @webgpu-state*
        ^js device (:device @webgpu-state*)
        ^js context (:context @webgpu-state*)
        ^js pipeline (:pipeline @webgpu-state*)]
    (when (and ready? device context pipeline)
      (let [[width height] (resize-canvas! canvas)
            ^js current-texture (.getCurrentTexture context)
            color-view (.createView current-texture)
            ^js depth-texture (.createTexture
                                device
                                #js {:size #js [(int width) (int height) 1],
                                     :format "depth24plus",
                                     :usage (.-RENDER_ATTACHMENT
                                              js/GPUTextureUsage)})
            depth-view (.createView depth-texture)
            ^js encoder (.createCommandEncoder device)
            ^js pass
            (.beginRenderPass
              encoder
              #js {:colorAttachments
                   #js [#js {:view color-view,
                             :clearValue
                             #js {:r 0.015, :g 0.018, :b 0.04, :a 1.0},
                             :loadOp "clear",
                             :storeOp "store"}],
                   :depthStencilAttachment #js {:view depth-view,
                                                :depthClearValue 1.0,
                                                :depthLoadOp "clear",
                                                :depthStoreOp "store"}})]
        (doseq [pass-data (:passes lowered)
                draw (:draws pass-data)]
          (case (:pipeline draw)
            :clear nil
            (:mesh-3d :mesh-textured-3d)
            (encode-mesh! pass device draw width height)
            nil))
        (.end pass)
        (let [^js queue (.-queue device)]
          (.submit queue #js [(.finish encoder)]))
        nil))))


(declare render-lowered!)


(defn- submit-browser!
  [canvas lowered]
  (let [{:keys [failed? unsupported? ready?]} @webgpu-state*]
    (cond (or failed? unsupported?) (render-lowered! canvas lowered)
          ready? (submit-webgpu! canvas lowered)
          :else (do (ensure-webgpu-state! canvas)
                    (let [{:keys [failed? unsupported? ready?]} @webgpu-state*]
                      (if (or failed? unsupported?)
                        (render-lowered! canvas lowered)
                        (when ready? (submit-webgpu! canvas lowered))))))))


(defn- rotate-x
  [[x y z] a]
  (let [c (js/Math.cos a)
        s (js/Math.sin a)]
    [x (- (* y c) (* z s)) (+ (* y s) (* z c))]))


(defn- rotate-y
  [[x y z] a]
  (let [c (js/Math.cos a)
        s (js/Math.sin a)]
    [(+ (* x c) (* z s)) y (- (* z c) (* x s))]))


(defn- rotate-z
  [[x y z] a]
  (let [c (js/Math.cos a)
        s (js/Math.sin a)]
    [(- (* x c) (* y s)) (+ (* x s) (* y c)) z]))


(defn- color-css
  [[r g b a]]
  (str "rgba("
       (int (* 255 r))
       ","
       (int (* 255 g))
       ","
       (int (* 255 b))
       ","
       (or a 1.0)
       ")"))


(defn- resize-canvas!
  [canvas]
  (let [dpr (or (.-devicePixelRatio js/window) 1)
        rect (.getBoundingClientRect canvas)
        w (max 1 (int (* dpr (.-width rect))))
        h (max 1 (int (* dpr (.-height rect))))]
    (when (or (not= (.-width canvas) w) (not= (.-height canvas) h))
      (set! (.-width canvas) w)
      (set! (.-height canvas) h))
    [w h]))


(defn- project
  [camera width height p]
  (let [[cx cy cz] (get camera :camera3d/position [0 0 0])
        [rx ry rz] (get camera :camera3d/rotation [0 0 0])
        near (double (get camera :camera3d/near 0.1))
        fov (double (get camera :camera3d/fov 48.0))
        [vx vy vz] (-> [(- (nth p 0) cx) (- (nth p 1) cy) (- (nth p 2) cz)]
                       (rotate-x (- rx))
                       (rotate-y (- ry))
                       (rotate-z (- rz)))
        depth (- vz)]
    (when (> depth near)
      (let [aspect (/ width height)
            f (/ 1.0 (js/Math.tan (/ (* fov js/Math.PI) 360.0)))
            ndc-x (/ (* vx f) (* aspect depth))
            ndc-y (/ (* vy f) depth)]
        (when (and (< -2 ndc-x 2) (< -2 ndc-y 2))
          {:xy [(* (+ ndc-x 1.0) 0.5 width) (* (- 1.0 ndc-y) 0.5 height)],
           :depth depth})))))


(defn- face-shade
  [vertices tri]
  (let [[a b c] (map #(nth vertices %) tri)
        ux (- (nth b 0) (nth a 0))
        uy (- (nth b 1) (nth a 1))
        uz (- (nth b 2) (nth a 2))
        vx (- (nth c 0) (nth a 0))
        vy (- (nth c 1) (nth a 1))
        vz (- (nth c 2) (nth a 2))
        nx (- (* uy vz) (* uz vy))
        ny (- (* uz vx) (* ux vz))
        nz (- (* ux vy) (* uy vx))
        len (max 0.0001 (js/Math.sqrt (+ (* nx nx) (* ny ny) (* nz nz))))
        light [0.35 0.65 0.68]
        dot (/
              (+ (* nx (nth light 0)) (* ny (nth light 1)) (* nz (nth light 2)))
              len)]
    (+ 0.48 (* 0.52 (max 0.0 dot)))))


(defn- shade-color
  [[r g b a] shade]
  (color-css [(* r shade) (* g shade) (* b shade) a]))


(defn- texture-tri-color
  [payload _tri]
  (:fill payload [1 1 1 1]))


(defn- affine-from-tri
  [[[u0 v0] [u1 v1] [u2 v2]] [[x0 y0] [x1 y1] [x2 y2]]]
  (let [denom (+ (* u0 (- v1 v2)) (* u1 (- v2 v0)) (* u2 (- v0 v1)))]
    (when (> (js/Math.abs denom) 0.000001)
      (let [a (/ (+ (* x0 (- v1 v2)) (* x1 (- v2 v0)) (* x2 (- v0 v1))) denom)
            b (/ (+ (* y0 (- v1 v2)) (* y1 (- v2 v0)) (* y2 (- v0 v1))) denom)
            c (/ (+ (* x0 (- u2 u1)) (* x1 (- u0 u2)) (* x2 (- u1 u0))) denom)
            d (/ (+ (* y0 (- u2 u1)) (* y1 (- u0 u2)) (* y2 (- u1 u0))) denom)
            e (/ (+ (* x0 (- (* u1 v2) (* u2 v1)))
                    (* x1 (- (* u2 v0) (* u0 v2)))
                    (* x2 (- (* u0 v1) (* u1 v0))))
                 denom)
            f (/ (+ (* y0 (- (* u1 v2) (* u2 v1)))
                    (* y1 (- (* u2 v0) (* u0 v2)))
                    (* y2 (- (* u0 v1) (* u1 v0))))
                 denom)]
        [a b c d e f]))))


(defn- draw-textured-triangle!
  [ctx texture payload tri points shade]
  (let [{:keys [image width height]} texture
        uvs (:uvs payload)
        source-points (mapv (fn [i]
                              [(* width (nth (nth uvs i) 0))
                               (* height (nth (nth uvs i) 1))])
                            tri)]
    (when-let [[a b c d e f] (affine-from-tri source-points points)]
      (.save ctx)
      (let [[[x1 y1] [x2 y2] [x3 y3]] points]
        (.beginPath ctx)
        (.moveTo ctx x1 y1)
        (.lineTo ctx x2 y2)
        (.lineTo ctx x3 y3)
        (.closePath ctx)
        (.clip ctx)
        (.setTransform ctx a b c d e f)
        (.drawImage ctx image 0 0)
        (.restore ctx)
        (set! (.-fillStyle ctx) (str "rgba(0,0,0," (- 1.0 shade) ")"))
        (.beginPath ctx)
        (.moveTo ctx x1 y1)
        (.lineTo ctx x2 y2)
        (.lineTo ctx x3 y3)
        (.closePath ctx)
        (.fill ctx)))))


(defn- render-mesh!
  [ctx camera width height {:keys [vertices indices], :as payload} texture]
  (let [projected (mapv #(project camera width height %) vertices)
        tris (->> indices
                  (keep (fn [tri]
                          (let [ps (mapv projected tri)]
                            (when (every? some? ps)
                              {:tri tri,
                               :points (mapv :xy ps),
                               :depth (/ (reduce + (map :depth ps)) 3.0)}))))
                  (sort-by :depth >))]
    (doseq [{:keys [tri points]} tris
            :let [[[x1 y1] [x2 y2] [x3 y3]] points
                  shade (face-shade vertices tri)
                  color (texture-tri-color payload tri)]]
      (if texture
        (draw-textured-triangle! ctx texture payload tri points shade)
        (do (.beginPath ctx)
            (.moveTo ctx x1 y1)
            (.lineTo ctx x2 y2)
            (.lineTo ctx x3 y3)
            (.closePath ctx)
            (set! (.-fillStyle ctx) (shade-color color shade))
            (.fill ctx))))))


(defn- render-lines!
  [ctx camera width height {:keys [vertices edges color]}]
  (set! (.-strokeStyle ctx) (color-css color))
  (set! (.-lineWidth ctx) 1.2)
  (.beginPath ctx)
  (doseq [[a b] edges
          :let [pa (project camera width height (nth vertices a))
                pb (project camera width height (nth vertices b))]
          :when (and pa pb)]
    (let [[x1 y1] (:xy pa)
          [x2 y2] (:xy pb)]
      (.moveTo ctx x1 y1)
      (.lineTo ctx x2 y2)))
  (.stroke ctx))


(defn- render-lowered!
  [canvas lowered]
  (let [[width height] (resize-canvas! canvas)
        ctx (.getContext canvas "2d")]
    (set! (.-lineCap ctx) "round")
    (set! (.-lineJoin ctx) "round")
    (doseq [pass (:passes lowered)
            draw (:draws pass)]
      (case (:pipeline draw)
        :clear (let [[r g b a] (:color draw)]
                 (set! (.-fillStyle ctx) (color-css [r g b a]))
                 (.fillRect ctx 0 0 width height))
        :line-3d (render-lines! ctx (:camera draw) width height (:payload draw))
        (:mesh-3d :mesh-textured-3d) (render-mesh! ctx
                                                   (:camera draw)
                                                   width
                                                   height
                                                   (:payload draw)
                                                   (:texture draw))
        nil))))


(defn stop!
  []
  (when-let [id @interval-id]
    (js/clearInterval id)
    (reset! interval-id nil))
  :stopped)


(defn dispose!
  []
  (stop!) :disposed)


(defn start!
  []
  (load-image-texture! earth-texture*
                       "/assets/images/earth_land_ocean_ice_1024.jpg")
  (load-image-texture! moon-texture* "/assets/images/moon_texture_512.jpg")
  (ensure-ring-texture!)
  (when-not @interval-id
    (reset! started-at-ms* (js/Date.now))
    (reset! interval-id (js/setInterval
                          (fn []
                            (let [elapsed-ms (- (js/Date.now) @started-at-ms*)
                                  seconds (if (:animating? @scene-state)
                                            (* (/ scene/frame-step-seconds
                                                  scene/frame-interval-ms)
                                               elapsed-ms)
                                            (:seconds @scene-state))]
                              (swap! scene-state assoc :seconds seconds)
                              (terminal/put-frame! frame-stream
                                                   (scene/frame-from-seconds
                                                     seconds
                                                     (current-textures)))))
                          scene/frame-interval-ms)))
  :started)


(defn- toggle-animation!
  []
  (let [animating? (:animating? @scene-state)]
    (swap! scene-state update :animating? not)
    (when-not animating? (reset! started-at-ms* (js/Date.now)))))


(defn reset-scene!
  []
  (reset! started-at-ms* (js/Date.now))
  (reset! scene-state {:seconds 0.0, :animating? true})
  (terminal/put-frame! frame-stream
                       (scene/frame-from-seconds 0.0 (current-textures))))


(defn- canvas-view
  []
  (r/create-class
    {:display-name "earth-moon-postgraphics-widget",
     :component-did-mount (fn [_]
                            (start!)
                            (terminal/put-frame! frame-stream
                                                 (scene/frame-from-seconds
                                                   (:seconds @scene-state)
                                                   (current-textures)))),
     :component-will-unmount (fn [_] (dispose!)),
     :reagent-render
     (fn []
       [pg/postgraphics-widget frame-stream :canvas-attrs
        {:style {:width "min(78vw, 860px)",
                 :height "min(76vh, 720px)",
                 :display "block",
                 :border "1px solid rgba(210,220,255,0.24)",
                 :border-radius "22px",
                 :background "#040612",
                 :box-shadow "0 30px 100px rgba(0,0,0,0.55)"}} :submit!
        submit-browser! :resolve-resource (fn [source _state] source)
        :on-error #(js/console.error "earth/moon frame rejected" %)])}))


(defn main-view
  []
  (let [{:keys [animating?]} @scene-state]
    [:div
     {:style
      {:min-height "100vh",
       :padding "84px 28px 32px",
       :background
       "radial-gradient(circle at 70% 18%, #2a234d 0, #091123 38%, #03050d 100%)",
       :color "#edf5ff",
       :font-family "Avenir Next, ui-sans-serif, sans-serif"}}
     [:div {:style {:max-width "1160px", :margin "0 auto"}}
      [:div
       {:style {:display "flex",
                :justify-content "space-between",
                :gap "24px",
                :align-items "end",
                :margin-bottom "22px"}}
       [:div
        [:div
         {:style {:letter-spacing "0.18em",
                  :text-transform "uppercase",
                  :color "#b6c7ff",
                  :font-size "12px"}} "dao.postgraphics 3d ops"]
        [:h1
         {:style {:font-size "clamp(34px, 5vw, 64px)",
                  :line-height "0.95",
                  :margin "8px 0 0"}} "Earth and Moon"]]
       [:div {:style {:display "flex", :gap "10px"}}
        [:button
         {:on-click toggle-animation!,
          :style {:padding "10px 14px",
                  :border-radius "999px",
                  :border "1px solid #7186c7",
                  :background (if animating? "#e3e9ff" "#111a31"),
                  :color (if animating? "#081020" "#edf5ff"),
                  :cursor "pointer"}} (if animating? "Pause" "Animate")]
        [:button
         {:on-click reset-scene!,
          :style {:padding "10px 14px",
                  :border-radius "999px",
                  :border "1px solid #7186c7",
                  :background "#111a31",
                  :color "#edf5ff",
                  :cursor "pointer"}} "Reset"]]]
      [:div
       {:style {:display "grid",
                :grid-template-columns "minmax(0, 1fr) 300px",
                :gap "24px",
                :align-items "start"}} [canvas-view]
       [:aside
        {:style {:padding "18px",
                 :border "1px solid rgba(210,220,255,0.22)",
                 :border-radius "16px",
                 :background "rgba(7, 12, 28, 0.74)",
                 :line-height "1.55"}}
        [:h2 {:style {:margin "0 0 10px", :font-size "18px"}}
         "Postgraphics Program"]
        [:p {:style {:color "#c2cee8", :margin "0 0 12px"}}
         "Each tick emits a complete frame with " [:code ":camera3d/set"]
         ", lighting state, and two " [:code ":draw3d/mesh"]
         " spheres from the shared Clojure/ClojureDart producer."]
        [:p {:style {:color "#8391b7", :font-size "13px", :margin "0"}}
         "This is a 3D postgraphics producer. The browser renderer now submits the lowered mesh frame through WebGPU with depth testing and textured fragments."]]]]]))
