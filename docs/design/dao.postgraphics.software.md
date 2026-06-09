# Design: `dao.postgraphics` software rendering + shared backend core

## Goal

Make `dao.postgraphics` render **without a GPU**, at full v4 fidelity, on both
platforms, and do it by **sharing as much code as possible** between the four
backends — *especially the math*.

Concretely:

- New CPU backends `dao.postgraphics.web.canvas` (Canvas2D) and
  `dao.postgraphics.flutter.canvas` (`ui/Canvas`) render the full pipeline in
  software and **share one rasterizer/shading core**.
- The two GPU backends `dao.postgraphics.web.gpu` (WebGPU) and
  `dao.postgraphics.flutter.gpu` (Flutter GPU) **share the same lowering and
  math** as the software backends and as each other.
- Any logic that is currently duplicated across the GPU backends, or that the
  software path also needs (matrix math, projection, near-plane clipping,
  per-vertex attribute resolution, clear-color extraction, color precedence),
  is extracted into shared `.cljc` (`math.cljc` / `raster.cljc` / `packing.cljc`)
  and the GPU backends call into it instead of re-implementing it.

The single change that unlocks all of this: **one matrix representation
everywhere — a 16-element column-major vector of doubles.** Today web uses
16-vecs and Flutter uses Dart's `vm/Matrix4`; that fork is the only reason the
math can't already be shared. Flutter converts 16-vec → `Float32List` /
`Matrix4` *only* at the GPU/Dart boundary inside `flutter/gpu.cljd`.

## Status

A prior attempt (`c78f7fb`) introduced shared lowering + software rendering and
was **reverted** (`fa9b792`). What survives in the tree today:

- `src/cljs/dao/postgraphics/web/gpu.cljs` — WebGPU backend, but it still holds
  its **own** `validate-frame!`, `lower-frame`, and all `mat4-*` math (16-vec).
- `src/cljd/dao/postgraphics/flutter.cljd` — Flutter dispatcher + lowering +
  validation built on `vm/Matrix4`, plus the 2D/text Canvas overlay painters.
- `src/cljd/dao/postgraphics/flutter/gpu.cljd` — Flutter GPU submit + textures
  (freshly extracted; a straight move, not yet using shared math).

The shared `.cljc` core and both `*.canvas` backends do **not** exist yet. This
document supersedes the reverted plan and folds in its hard-won lessons as
explicit design decisions (see *Cross-cutting decisions*), most importantly the
perf failure that sank the last attempt: a per-pixel `drawRect` software path is
too slow. This design rasterizes into a pixel buffer and blits **once**.

`yin/repl/flutter.cljd` is the REPL-server widget and is not involved.

## Target architecture

| Namespace | File | Kind | Role |
|---|---|---|---|
| `dao.postgraphics.validation` | `src/cljc/.../validation.cljc` | existing | numeric predicates (`vec3?`, `valid-color?`, `EPSILON`, `reject!`) |
| `dao.postgraphics.terminal` | `src/cljc/.../terminal.cljc` | existing | stream binding / signals |
| `dao.postgraphics.math` | `src/cljc/.../math.cljc` | new | pure 16-vec mat4 ops, projection, normal inverse-transpose, near-plane clip (attribute-carrying) |
| `dao.postgraphics.lowering` | `src/cljc/.../lowering.cljc` | new | parameterized `validate-frame!` + `lower-frame` → canonical lowered frame |
| `dao.postgraphics.raster` | `src/cljc/.../raster.cljc` | new | CPU shading + rasterizer core: Blinn-Phong, texture sample, sRGB, `vertex-attrs`, triangle/line raster into an **injected pixel/depth sink** |
| `dao.postgraphics.packing` | `src/cljc/.../packing.cljc` | new | GPU data contract: uniform packing (`packed-vertex-uniforms` / `packed-lighting-block`) + buffer packing (`pack-vertex-floats!` / `pack-indices!`), `unlit-line-draw`, `clear-color`; depends on `raster` for `vertex-attrs` |
| `dao.postgraphics.web` | `src/cljs/.../web.cljs` | new | dispatcher: WebGPU probe, public API, mounts gpu or canvas |
| `dao.postgraphics.web.gpu` | `src/cljs/.../web/gpu.cljs` | refactor | WebGPU submit; uses shared math + lowering; drops local copies |
| `dao.postgraphics.web.canvas` | `src/cljs/.../web/canvas.cljs` | new | browser software backend (Canvas2D + `ImageData`) |
| `dao.postgraphics.flutter` | `src/cljd/.../flutter.cljd` | refactor | dispatcher: GPU detect, public API, `PostgraphicsPainter` picks path, shared 2D/text overlay painters |
| `dao.postgraphics.flutter.gpu` | `src/cljd/.../flutter/gpu.cljd` | refactor | Flutter GPU submit; consumes canonical 16-vec frame, converts to `Float32List` at the boundary |
| `dao.postgraphics.flutter.canvas` | `src/cljd/.../flutter/canvas.cljd` | new | software backend: drives `raster.cljc` into a `Uint8List`, blits one `ui.Image` |
| `dao.postgraphics.flutter.texture` | `src/cljd/.../flutter/texture.cljd` | new | `PgTexture` handle carrying both the GPU texture and CPU-readable RGBA8 bytes |

Each platform's **dispatcher** keeps the stable public API
(`postgraphics-widget`, `put-frame!`). cljd demos already require
`dao.postgraphics.flutter` (unchanged). cljs demos (`solar_system`, `voxel`,
`earth_moon`) currently `:require [dao.postgraphics.web.gpu :as pg]`; they
switch to `[dao.postgraphics.web :as pg]` — same `pg/postgraphics-widget` call
site.

## The canonical lowered frame (the contract)

`lower-frame` emits one platform-neutral data structure that every backend
consumes. Matrices are 16-vecs; nothing carries a host matrix type.

```
{:viewport {:width W :height H}
 :target-registry {kw target}                 ; render targets (GPU only)
 :passes [{:target-id :default|kw :target {…}? :draws [draw …]}]}

draw =
 {:pipeline :clear        :color [r g b a] :clear-depth 1.0}
 {:pipeline :camera-reset}
 {:pipeline :mesh-3d|:draw-3d|:line-3d
   :op <op> :mvp <16> :model-m <16>
   :lights [...] :camera-pos [...]
   :depth-test :depth-write :lighting-enabled
   :clips [[x y w h] …]}
 {:pipeline :draw-2d :op <op> :model-m <16> :clips […]}   ; rect/circle/path/image
 {:pipeline :text    :op <op> :screen-x :screen-y :clips […]}
```

Notes:
- 2D ops carry the **full model matrix** (not a lossy AABB). web/gpu's old AABB
  form is dropped; its submitter ignores 2D anyway, and every Canvas backend can
  now transform 2D faithfully.
- Render targets stay in the canonical form, but software backends pass
  `:supports-render-targets? false` and reject those ops (documented Flutter
  Canvas limitation, `dao.postgraphics.flutter.md`). WebGPU keeps target support.
  "Canonical" therefore means *superset*; consumers reject what they can't do.

## Shared `.cljc` layer

### `dao.postgraphics.math`
Pure numeric. Reader conditionals only for the host transcendentals:

```clojure
(defn- mcos [x] #?(:clj (Math/cos x) :cljs (js/Math.cos x) :cljd (math/cos x)))
;; likewise msin, msqrt, mtan, mabs, mpow, mfloor, mceil
```

(`:cljd` needs `["dart:math" :as math]` in the ns and these helpers because bare
`Math/*` is JVM-only and won't compile under ClojureDart — a portability fix the
prior attempt discovered.)

Contents, ported from the two existing copies (web/gpu's `mat4-*` and
flutter.cljd's `op->matrix` / `build-camera` / `clip-*-near` / `vec3-normalize`):
`mat4-mul`, `mat4-translation/scale/rotation-{x,y,z}`, `mat4-invert`,
`op->mat4` (the `:matrix`/`:translate`/`:rotate`/`:scale` op reducer),
`build-camera` (perspective + orthographic + view), `camera-pos-from-view-matrix`,
`inverse-transpose-3x3` (normal transform), `project` (mvp·v → clip → NDC →
screen, returning `z-ndc` and `inv-w` for perspective-correct interp), and
attribute-carrying near-plane clipping (`clip-triangle-near` / `clip-line-near`,
Sutherland–Hodgman against the w- and z- half-spaces, identical math to
flutter.cljd's current `clip-*-near`). The clip routines interpolate an opaque
per-vertex **attribute payload** alongside `[x y z w]` so the same code clips
bare lines and fully-attributed mesh fragments (uv / normal / world-pos / color).

### `dao.postgraphics.lowering`
One `validate-frame!` and one `lower-frame` for all four backends.

- **`validate-frame!`** is parameterized so the few real platform divergences
  stay correct without forking the walker:
  `{:texture-source-valid? fn :supports-render-targets? bool :supports-image? bool}`.
  It reuses `validation.cljc` predicates and calls the injected texture-source
  check (cljd GPU: `PgTexture`/`gpu/Texture`; web: `[:target/id …]` data;
  software: CPU-readable handle). web/gpu's existing `validate-frame!` is the
  most complete (render targets, image ops) and is the basis; flutter's stricter
  `valid-2d-affine?` rule becomes one of the injected predicates.
- **`lower-frame`** produces the canonical frame above. Camera / clip /
  model-stack walking is platform-neutral once matrices are 16-vecs.
- Lowering note carried over from the prior attempt: `:light/ambient` carries
  `:intensity` (default 1.0) so the v4 ambient term `color × intensity × K_d`
  works uniformly.

### `dao.postgraphics.raster`
Pure shading + rasterizer core. **No platform types.** Hot loops take injected
`depth-get` / `depth-set!` / `put-pixel!` so each platform owns its arrays and
pixel sink; the core never knows whether it's filling an `ImageData` or a
`Uint8List`.

- `srgb->linear` / `linear->srgb` (v4).
- `blinn-phong` (v4, linear space): ambient + per-light diffuse `max(0,N·L)` +
  specular `pow(max(0,N·H), shininess)` + point attenuation `(1−d/range)²`.
- `sample-texture {:rgba :width :height}` × uv × wrap(`:clamp`/`:repeat`/`:mirror`)
  × filter(`:nearest`/`:linear`).
- **Shared per-vertex attribute resolution** `vertex-attrs` — default uv
  `[0 0]`, default normal, and `:colors`-over-`:fill` precedence (v4). This is
  the one helper *both* the rasterizer (for interpolation) and the GPU vertex
  packers (web `interleave-vertex-data`, flutter `vertices->float32-list`, both
  via `packing/pack-vertex-floats!`) call, so the precedence rule lives in
  exactly one place.
- `rasterize-triangle!` — edge-function coverage (area-normalized so **both
  windings** render — a bug from the prior attempt), perspective-correct
  barycentric interp of uv/normal/world-pos/color, per-fragment depth test/write
  + clip-rect test, `shade-fragment` → `put-pixel!`. Runs `clip-triangle-near`
  first.
- `rasterize-line!` — DDA, depth-tested.
- `shade-mesh-fragment` — texture sample (diffuse, v4) × Blinn-Phong × sRGB
  encode, honoring specular/shininess and `:lighting-enabled`.
- `render-3d!` — orchestrates a pass's 3D draws over the injected sink. (Watch
  the `case` balance: the prior attempt shipped a bug where `:draw-3d`/`:line-3d`
  fell outside the `case`.)

### `dao.postgraphics.packing`
The GPU data contract: turns a canonical lowered draw into the uniform and
buffer layouts both GPU backends upload. Pure data; depends on `raster` only for
`vertex-attrs`. Layout lives here once and the platform injects a `put!` writer,
mirroring the rasterizer's pixel sinks. The lighting uniforms reproduce
`raster/blinn-phong`, so the shaders match the CPU path (Phase 6 / decision 3).

- `packed-vertex-uniforms` — 48 doubles: mvp ++ model ++ modelInv (the vertex
  stage; modelInv lets the shader build the inverse-transpose normal matrix).
- `packed-lighting-block` — 108 doubles: camera / material / `max-packed-lights`
  light slots (the fragment stage).
- `pack-vertex-floats!` / `pack-indices!` — interleaved vertex + flattened index
  buffers, written through an injected `put!` (web `interleave-vertex-data`,
  flutter `vertices->float32-list`).
- `unlit-line-draw` — collapse a line draw to its unlit single-colour form.
- `clear-color` — first `:clear` colour (also used by both software backends to
  init their pixel buffers).

## Backends

### `web/gpu.cljs` (refactor — slim down)
Delete the local `validate-frame!`, `lower-frame`, and every `mat4-*`; require
the shared namespaces (`lowering/validate-frame!` with
`:supports-render-targets? true`, target-id `:texture-source-valid?`,
`:supports-image? true`). `interleave-vertex-data` keeps building the
`js/Float32Array` but packs the shared 12-float layout through
`packing/pack-vertex-floats!`; `index-data` flattens via `packing/pack-indices!`
and the clear value comes from `packing/clear-color`. The WGSL shader is lit
(Phase 6 / decision 3): it reproduces `raster/shade-mesh-fragment` and reads the
expanded uniform buffer (`packing/packed-vertex-uniforms` ++ `gpu-lighting-block`);
pipeline/`submit-webgpu!` are otherwise unchanged. Drop the standalone
`webgpu-unsupported-view`; the dispatcher now decides what to mount.

### `web/canvas.cljs` (new — browser software)
`submit-software!`: acquire the `"2d"` context; allocate a `js/Float32Array`
depth buffer + an `ImageData` color buffer; init to the `:clear` color; per pass
run `raster/render-3d!` with `put-pixel!` writing the `ImageData`; `putImageData`
once; then draw `:draw-2d` / `:text` over it with native Canvas2D
(`fillRect`/`arc`/`Path2D`/`drawImage`/`fillText`), honoring `:clips` via
`ctx.save`/`clip`.

### `flutter/gpu.cljd` (refactor — use shared, convert at boundary)
Keep `init-gpu!`, `render-mesh-gpu!`, `render-lines-gpu!`, `submit-gpu!`
(returns `ui.Image`), texture creation. Changes: (1) consume the canonical
`:mvp`/`:model-m` **16-vecs**, converting to `Float32List` for uniforms
(replacing today's `vm/Matrix4 .-storage`); (2) pack vertices/indices through the
shared `packing/pack-vertex-floats!` / `pack-indices!` so packing matches web;
(3) the GLSL shader is lit (Phase 6 / decision 3) — it reproduces
`raster/shade-mesh-fragment`, so each draw binds two stage buffers: the
vertex-stage `Uniforms` (`packing/packed-vertex-uniforms`: mvp/model/modelInv) and
the fragment-stage `Frag` block (`packing/packed-lighting-block`: camera/material/
lights). The `simple_mesh` bundle is recompiled via `bin/compile-shaders.sh`.
Add `gpu-available?` (runs init once, checks `:context`).

### `flutter/canvas.cljd` (new — software, the perf-critical one)
`paint-3d! [canvas lowered width height]`: allocate a `Float32List` depth buffer
and a `Uint8List` RGBA color buffer; run the **shared** `raster/render-3d!`
with `put-pixel!` writing the `Uint8List`; then realize the buffer as **one**
`ui.Image` (via `decodeImageFromPixels`) and `drawImage` it. **No per-pixel
`drawRect`.** This is the explicit fix for why the prior attempt abandoned
sharing on Flutter — the cost there was one `Paint`/`drawRect` per covered pixel,
which saturated the UI thread on dense meshes (earth_moon ≈ 4,900 tris) and
rendered black. Rasterizing to a buffer + single blit makes the shared core
viable on Flutter too. (See *Risks* for the `decodeImageFromPixels` async caveat
and its fallback.)

### `web.cljs` / `flutter.cljd` (dispatchers)
- **web.cljs**: public `postgraphics-widget`, `put-frame!`,
  `frame-stream-binding-test-hook`; a Reagent class that probes `navigator.gpu`
  on mount and binds either the `web.gpu` or `web.canvas` submitter (always a
  `<canvas>`). The shared `bind-frame-stream!` / viewport / resource plumbing
  (factored out of today's web/gpu widget) lives here.
- **flutter.cljd**: keeps public API + the cljd-only 2D/text overlay painters
  (`->paint`, `apply-clips!`, `paint-2d-draw!`, `paint-text!`,
  `resolve-font-family`, `LINE_HEIGHT`) used by both paths.
  `PostgraphicsPainter.paint`: `lower-frame`; per pass, `use-gpu?` →
  `gpu/submit-gpu!` → `drawImage`; else `drawColor(clear)` + `canvas/paint-3d!`;
  then both run the 2D/text overlay. Update `gpu-renderer-policy-test-hook` →
  `:software-renderer? true`, `:canvas-fallback? true`.

### Web single-context constraint (unchanged, still load-bearing)
An HTML `<canvas>` holds exactly one context type for its lifetime: once
`getContext("webgpu")` is called, `getContext("2d")` returns null, and vice
versa. Consequences:
- `web.gpu` holds the `"webgpu"` context, which has no 2D methods, so it stays
  **3D-only** (2D/text ignored, as today).
- `web.canvas` holds the `"2d"` context and does **everything**: the 3D is
  rasterized on the CPU into `ImageData` and blitted, then 2D/text draw on top.
  One context suffices only because software rasterization already reduced 3D to
  pixels.

This is why Flutter's single `ui/Canvas` *can* composite a GPU image + 2D + text
(its GPU path overlays 2D) but the web GPU path cannot. That asymmetry is the
only reason `web.gpu` is 3D-only while `web.canvas` is full 2D+3D.

## Cross-cutting decisions (lessons from the reverted attempt, made explicit)

1. **Pixel buffer + single blit, never per-pixel draw.** Both software backends
   rasterize into a typed pixel buffer and blit once (`putImageData` /
   `decodeImageFromPixels`+`drawImage`). This is the design's load-bearing perf
   decision and the reason the shared rasterizer is viable on both platforms.

2. **CPU-samplable textures.** GPU textures aren't CPU-readable, so the software
   path can't sample them. `dao.postgraphics.flutter.texture/PgTexture {gpu rgba
   width height}` carries both; `create-rgba-texture!` /
   `load-rgba-texture-from-asset!` return one. GPU path binds `:gpu`; software
   path samples `:rgba`. (Hint carefully: a `^PgTexture` cast over `nil` for
   untextured meshes is a real crash the prior attempt hit on the GPU path too.)

3. **Lighting parity is a renderer concern, never a scene-data hack.** The prior
   attempt left the GPU shaders unlit while software did Blinn-Phong, then
   *flipped a scene's light direction* so the lit side faced the camera under
   software. That couples scene content to a backend accident and breaks the
   moment the GPU shader is lit. **Decision (settled in Phase 6):** scene data is
   authoritative and backend-agnostic, and we reach parity by **lighting the GPU
   shaders** (the preferred option). Both WebGPU (WGSL) and Flutter GPU (GLSL)
   shaders now reproduce `raster/shade-mesh-fragment` exactly — Blinn-Phong in
   linear space with the same sRGB encode — driven by the shared uniform layout
   in `packing/packed-vertex-uniforms` + `packing/packed-lighting-block`. No scene is
   edited to compensate. The one accepted divergence is a fixed light cap
   (`packing/max-packed-lights`, 8) on the GPU paths; software has no cap.

4. **GPU detection is "does it render," not "does it init."** Flutter GPU
   (Impeller) initializes on Android emulators but silently renders no custom
   mesh pipelines. `gpu-available?` returning true there keeps a broken path.
   Mitigation (kept from the prior attempt, isolated behind one probe): an
   `isEmulator` `MethodChannel` in `MainActivity.kt`, probed once at bind
   (cached), with `use-gpu? = (and (not emulator?) gpu-available?)` decided per
   paint so the async probe takes effect as soon as it resolves. Treat this as a
   known-fragile boundary, not a general capability story.

## TDD (tests first, per CLAUDE.md)

1. `test/dao/postgraphics/math_test.cljc` (JVM): mat4 mul/invert, `project`
   against a known mvp, `inverse-transpose-3x3` under non-uniform scale,
   attribute-carrying near-clip parity with flutter's existing
   `clip-*-near-test-hook` expectations.
2. `test/dao/postgraphics/raster_test.cljc` (JVM): sRGB round-trip;
   `blinn-phong` (ambient / directional `N·L` / specular `N·H` / point
   attenuation at 0/range/beyond); `rasterize-triangle!` interior + depth reject
   + `depth-write? false` + clip-rect exclusion + **both windings**;
   `sample-texture` wrap×filter at edges; `vertex-attrs` colors-over-fill.
   `test/dao/postgraphics/packing_test.cljc` (JVM): `packed-vertex-uniforms` /
   `packed-lighting-block` layout + light truncation; `pack-vertex-floats!` /
   `pack-indices!` layout; `unlit-line-draw`; `clear-color`.
3. `test/dao/postgraphics/lowering_test.cljc` (JVM): canonical passes/draws
   shape; parameterized validation accepts/rejects targets+image per options.
4. Browser `test/dao/postgraphics/web_canvas_test.cljs` (register in
   `src/cljs/test_runner.cljs`): 3D frame → `lower-frame` →
   `web.canvas/submit-software!` against a fake 2d-context; assert pixels + 2D
   calls. Keep `web_gpu_test.cljs` green against the refactor.
5. Flutter `test/dao/postgraphics/flutter_cljd_test.cljd`: updated policy hook;
   clip/normal/camera hooks now backed by shared ns; a raster hook exercising
   `flutter.canvas` on a small mesh.
6. Implement `math` → `raster` → `packing` → `lowering` → backends until green.

## Phasing (large refactor — land reviewably)

Each phase is independently reviewable and leaves the tree green.

- **Phase 1 — `math.cljc`.** Build it + tests; point existing web/gpu and
  flutter code at it, **including converting flutter lowering off `vm/Matrix4`
  to 16-vecs** (the enabling step). No behavior change.
- **Phase 2 — `lowering.cljc`.** Build it + tests; switch both GPU backends to
  it (still GPU-only, green).
- **Phase 3 — shading/raster core.** Shading, raster, `vertex-attrs` + tests.
  (Originally one `software.cljc`; later split into `raster.cljc` for the
  shade/rasterize core and `packing.cljc` for the GPU uniform/buffer contract.)
- **Phase 4 — Web split.** `web/canvas.cljs` + `web.cljs` dispatcher; demos +
  test_runner updated.
- **Phase 5 — Flutter split.** `flutter/canvas.cljd` + `flutter.texture` +
  dispatcher detection; GPU path converts at the boundary.
- **Phase 6 — Lighting parity.** ✅ Light the GPU shaders (decision 3, preferred
  option). Both GPU shaders now do Blinn-Phong + sRGB matching software, fed by
  shared uniform packing (`packing/packed-vertex-uniforms` + `packed-lighting-block`);
  the Flutter `simple_mesh` bundle is recompiled via `bin/compile-shaders.sh`.
  Policy hooks gain `:gpu-lighting? true`.

## Verification

- `clj -M:test` — math/raster/packing/lowering tests pass.
- `clj -M:cljs -m shadow.cljs.devtools.cli compile test && node target/node-tests.js`
  — refactored web.gpu + new web.canvas tests pass.
- `clj -M:cljd compile` — dispatcher + both Flutter components compile; cljd
  suite green.
- `clj -M:kondo --lint` on each new/changed file (bracket balance per CLAUDE.md).
- Manual: a 3D demo (`voxel` / `solar_system` / `earth_moon`) renders the
  lit/textured scene through the web dispatcher (WebGPU when present, Canvas2D
  software when `navigator.gpu` is disabled) and through the Flutter dispatcher's
  software path on a profile without working Flutter GPU; both GPU paths render
  unchanged.

## Risks / open questions

- **`decodeImageFromPixels` is async.** `flutter/canvas.cljd` builds the blit
  image off a callback, but `CustomPainter.paint` is synchronous. Plan: keep the
  last realized `ui.Image` and present it while the next is decoding (one frame
  of latency under motion). If that proves unacceptable, fall back to the proven
  pre-GPU cljd rasterizer (span-rect `drawPath`, not per-pixel) for Flutter only
  — sharing the math/shading core but not the pixel-sink driver. This is the one
  place the "share everything" goal may bend, and only for Flutter blitting.
- **Lighting parity** (decision 3): *settled* — the GPU shaders are lit to match
  software. Remaining caveat: the GPU light array is fixed at
  `packing/max-packed-lights` (8); scenes with more lights are truncated on the GPU
  paths only.
- **Software textures are single-level** (no mipmaps) → minification aliasing at
  distance.
- **Web dispatch is the sync `navigator.gpu` probe only**; async device-init
  failure → canvas fallback is a noted future enhancement.
