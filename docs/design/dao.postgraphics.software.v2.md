# dao.postgraphics Software Backend v2

## Context

The old `old_software_rasterizer.cljd` was a monolithic Flutter-only painter
(~2942 lines). The current architecture splits the software path into:

- `src/cljc/dao/postgraphics/raster.cljc` — shared triangle/line rasterizer,
  Blinn-Phong shading, texture sampling
- `src/cljc/dao/postgraphics/math.cljc` — pure 16-vec math, near-plane clipping
- `src/cljc/dao/postgraphics/lowering.cljc` — frame → lowered pass/draw IR
- `src/cljd/dao/postgraphics/flutter/canvas.cljd` — Flutter pixel/depth sink
- `src/cljs/dao/postgraphics/web/canvas.cljs` — Web Canvas2D pixel/depth sink

Most of the old rasterizer's heavy machinery is already present in the shared
`cljc` core. This document captures the remaining gaps — optimizations and
behavioral fixes that should be ported forward.

---

## Goals

1. Textured mesh draws work on the Flutter software fallback (they are
   currently no-ops because no texture bytes reach the rasterizer).
2. Textured meshes use mipmapped trilinear filtering when the texture carries
   mips, improving quality and performance for minified surfaces.
3. `:draw/image` honours `:image/fit` and `:image/src-rect` on both backends.
4. 2D stroke widths remain constant in screen space under scale transforms.
5. Flutter text rendering uses the true alphabetic baseline instead of an
   approximate `font-size` offset.
6. Light colours are converted to linear space before accumulation, matching the
   v4 §Lighting Equation.

---

## 1. Flutter Texture Preparation (Critical Gap)

### Problem

`flutter/canvas.cljd` never resolves `:texture` draws into CPU-samplable bytes.
`raster/render-3d!` receives `nil` for `:texture` on the Flutter software path,
so textured meshes fall back to flat shading.

### What to Port

The old rasterizer's `ensure-image-levels!` + `Expando` cache:

- `ui/Image.toByteData` is async. Kick it off eagerly; the first frame where
  bytes are missing skips the textured path (matching the old behaviour).
- Cache the resulting `ByteData` mip pyramid on the `ui/Image` instance via
  `dart/Expando` so decoded bytes are GC-eligible with the image.

### New File

`src/cljd/dao/postgraphics/flutter/texture.cljd` (or extend existing
`src/cljd/dao/postgraphics/flutter/texture.cljd` if present).

Provide a single public function:

```clojure
(defn cpu-texture
  "Given a lowered draw's :texture value, returns a CPU-samplable
   {:rgba ByteData :width int :height int :levels [ByteData ...]} map,
   or nil if the source image is not yet decoded. Caches decoded bytes
   and generated mip pyramids in a dart/Expando keyed by ui/Image."
  [texture-draw-value])
```

### Changes to `flutter/canvas.cljd`

- Add a `prepare-textures` step (mirroring `web/canvas.cljs`) that walks
  `:passes`/`:draws` and replaces `:texture` with the CPU-samplable map.
- The `TextureResource` record from the old code collapses to the same plain
  map shape the web backend already uses: `{:rgba [...] :width :height}`.

### Changes to `raster.cljc`

- `sample-texture` currently takes `{:rgba [...] :width :height}`. Extend it
  to accept an optional `:levels` vector and `:mip-level` float.  When
  `:levels` is present and `:mip-level > 0`, perform trilinear interpolation
  across the two nearest levels.  The caller (rasterizer) computes the
  per-triangle LOD via `mip-level-for-triangle` (§2) and passes it in; the
  function itself does not compute derivatives.

---

## 2. Mipmap Generation + Trilinear Filtering

### Problem

`raster/sample-texture` only samples level 0 with bilinear filtering. Minified
surfaces alias and shimmer.

### What to Port

From the old rasterizer:

1. `generate-mipmaps` — given base `ByteData` (or JS `Uint8ClampedArray`) and
   dimensions, produce a vector of successively halved levels by averaging
   2×2 blocks.
2. `mip-level-for-triangle` — compute screen-space UV derivatives at the
   triangle centroid to derive a floating-point LOD (`rho = max(|du/dx|, |dv/dy|)`).

### Interface Changes

```clojure
;; In raster.cljc
(defn sample-texture
  "Sample texture at (u,v). opts: :wrap :filter :mip-level :levels.
   When :levels is present and :mip-level > 0, trilinear-interpolate
   across the two nearest levels."
  [texture u v opts])
```

The web backend's `cpu-texture` in `web/canvas.cljs` should also generate mips
once per cached image, so both backends benefit.

### Flutter-Specific Notes

The old code stored mip levels as a vector of `ByteData` objects in the
`Expando`. Replicate exactly. Each level's dimensions are
`(max 1 (quot w 2^n))` by `(max 1 (quot h 2^n))`.

---

## 3. `:draw/image` Fit Modes

### Problem

Both backends only render `:placeholder` for `:draw/image`. Real images with
`:image/fit` and `:image/src-rect` are ignored.

### What to Port

The rectangle algebra from the old `exec-2d-op` `:draw/image` branch
(lines ~1370–1390):

```clojure
(case fit
  :fill   [dst-x dst-y dst-w dst-h src-x src-y src-w src-h]
  :contain [x' y' w' h' sx sy sw sh]  ;; scale to fit inside, letterbox
  :cover   [x' y' w' h' sx' sy' sw' sh'] ;; scale to cover, crop excess
  :none    [x' y' vw vh sx' sy' vw vh])  ;; centre-crop to image size
```

### Backend-Specific Rendering

- **Flutter**: Use `ui/ImageShader` with `TileMode.clamp` and a computed
  4×4 column-major matrix mapping screen pixels to source pixels. Apply
  `ColorFilter.matrix` for `:opacity` modulation. Skip the filter when
  opacity is 1.0 (identity optimisation from old code).
- **Web Canvas2D**: Use `ctx.drawImage(image, sx, sy, sw, sh, dx, dy, dw, dh)`
  directly; no shader matrix needed. Respect `:opacity` via `globalAlpha`.

### Files to Change

- `src/cljd/dao/postgraphics/flutter.cljd` — `paint-2d-draw!` `:draw/image` case
- `src/cljs/dao/postgraphics/web/canvas.cljs` — `paint-2d-draw!` `:draw/image` case
- `src/cljc/dao/postgraphics/lowering.cljc` — already lowers `:image/fit` and
  `:image/src-rect` into the draw map; no change needed

---

## 4. Screen-Space Stroke Width Compensation

### Problem

A 2D scale transform multiplies stroke widths visually because the backends
apply the matrix and then stroke in the transformed coordinate system. The old
rasterizer divided `stroke-width` by the extracted scale `sx` to keep strokes
constant in screen pixels.

### What to Port

In both `paint-2d-draw!` implementations:

1. Extract the scale factor from the affine matrix:
   `sx = sqrt(m00² + m01²)`
2. Divide the op's `stroke-width` by `sx` before applying it to the paint /
   Canvas2D context.

### Files to Change

- `src/cljd/dao/postgraphics/flutter.cljd` — `paint-2d-draw!`
- `src/cljs/dao/postgraphics/web/canvas.cljs` — `paint-2d-draw!`

---

## 5. Accurate Text Baseline (Flutter)

### Problem

`flutter.cljd`'s shared `paint-text!` (used by both GPU and canvas overlay paths)
offsets by `font-size` to place the baseline at `screen-y`. This is an
approximation; real baselines vary by font metrics.

### What to Port

The old code builds a `Paragraph`, calls `.layout`, then reads
`(.alphabeticBaseline para)` and offsets by that exact value.

### Change to `flutter.cljd`

In `paint-text!`:

```clojuren(let [^ui/Paragraph para (.build builder)]  (.layout para (ui/ParagraphConstraints. .width 1.0e6))  (.save canvas)  ...  (.drawParagraph canvas para (ui/Offset. screen-x                        (- screen-y (.alphabeticBaseline para))))  (.restore canvas))
```

Remove the current `(- screen-y font-size)` approximation.

---

## 6. Light Colour sRGB → Linear Conversion

### Problem

`raster/shade-mesh-fragment` already linearises the texture-modulated diffuse
colour and the emissive term before passing them to `blinn-phong`. However,
`blinn-phong` itself uses light `:color` values directly without converting
them from sRGB to linear.  The old rasterizer called `srgb-rgb-to-linear` on
every light colour before multiplying with material diffuse/specular.

### Decision

Port the conversion.  The v4 spec defines the lighting equation in linear
space; light colours in the frame format are sRGB-encoded, so every channel
must be linearised before accumulation.

### Change to `raster.cljc`

Inside `blinn-phong`, before using `(:color light)`, convert it:

```clojure
(let [lc (raster/srgb->linear (:color light))
      ...]
  ;; use (nth lc 0) (nth lc 1) (nth lc 2) for accumulation
  )
```

This is a single-line change with large correctness impact.

---

## Non-Goals (Do Not Port)

| Old Feature | Why Not |
|---|---|
| Span-based `ui.Path` rasterization (1×1 rects per pixel) | The current edge-function pixel buffer is faster, more correct, and cross-platform |
| `ImageShader` + `drawVertices` fast path for unlit textured triangles | Belongs in the GPU submitter, not the software fallback; mixing it into the software path created structural confusion |
| `vector_math` `Matrix4` host types | The pure 16-vec `cljc` math is portable and testable on JVM/JS/Dart |
| Monolithic painter architecture | Already decomposed into lowering / raster / platform sinks; do not regress |
| `Expando` image cache on web | JS `WeakMap` is preferable; the web backend already uses an `atom` cache keyed by `:source-id` which is sufficient |

---

## Implementation Order

1. **Flutter texture preparation** — unblocks textured mesh rendering on the
   most important fallback path.
2. **Mipmap generation + trilinear sampling** — improves quality; builds on #1.
3. **`:draw/image` fit modes** — completes the 2D API surface.
4. **Screen-space stroke width** — small behavioural fix, low risk.
5. **Accurate text baseline** — small behavioural fix, low risk.
6. **Light colour linearisation** — one-line correctness fix.

---

## Testing Strategy

- For each change, add a test in `test/dao/postgraphics/` or the platform
  counterpart before modifying implementation (TDD per AGENTS.md).
- Texture sampling: test `sample-texture` with `:levels` present at fractional
  `mip-level` produces blended colours.
- Mipmap generation: test that level-1 dimensions are `(quot w 2)` and pixel
  values are the 2×2 average of level 0.
- Image fit modes: test the rectangle algebra with known inputs/outputs
  (pure function, no rendering needed).
- Stroke width: test that a scale-2 transform halves the effective stroke width.
- Text baseline: mock a Paragraph with a known `alphabeticBaseline` and assert
  the draw offset.
- Light colour: assert that a light with sRGB colour `[1.0 0.0 0.0]` produces
  `[1.0 0.0 0.0]` in linear space (1.0 is the fixed point of the transfer
  function).  A second test with `[0.5 0.5 0.5]` asserts that each channel
  linearises to ~0.214, proving the conversion actually runs on mid-tones.
