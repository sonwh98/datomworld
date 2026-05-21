# Design: `dao.postgraphics.webgpu`

## Summary

`dao.postgraphics.webgpu` is one concrete terminal host for
`dao.postgraphics`. It consumes complete frame programs and realizes them on a
browser `GPUCanvasContext` via WebGPU while preserving the bytecode model's
ordering and state semantics.

`dao.postgraphics` itself is terminal-neutral. The shared terminal-VM contract
is defined in `dao.postgraphics.terminal.md`. This document defines the
WebGPU-specific realization of that contract. The Flutter terminal is documented
separately in `dao.postgraphics.flutter.md`.

Shared stream-binding, generation-ID, canonical-signal, and skipped-submission
accounting behavior should reuse `src/cljc/dao/postgraphics/terminal.cljc`
rather than re-defining those concerns in the WebGPU host layer.

The WebGPU terminal is intended to reach feature parity with the Flutter
terminal's v1-v4 surface:

- v1 2D draw ops, clip stack, metadata carriage, canonical signals
- v2 camera / depth / 3D lines / 3D triangles
- v3 text, image, and stroke precision contracts
- v4 lighting, textured meshes, and render targets

## Responsibilities

The shared terminal responsibilities, exclusions, lifecycle, abstract
operations, and frame-accounting rules are defined in
`dao.postgraphics.terminal.md`.

This document defines only the WebGPU-specific realization details:

- the final Cartesian-to-browser-canvas viewport transform
- lowering from bytecode order into GPU passes and draw batches
- WebGPU-native depth, textures, shaders, and render targets
- browser / device / canvas binding behavior

## Viewport Transform

For all rect-bearing ops - `:draw/fill-rect`, `:draw/stroke-rect`,
`:draw/image`, `:meta/region`, and the resolved screen-space form of
`:clip/push-rect` - the VM flips the Cartesian lower-left-corner rect into the
browser canvas's top-left-corner screen space. A rect `[x y width height]`
therefore maps to screen space as
`[x, viewport-height - (y + height), width, height]`. The rect height
participates in the y-flip; the VM must not treat the Cartesian `y` value alone
as the top edge.

For v2+ 3D NDC mapping, the y-down screen formula
`screen_y = (1 - ndc_y) * 0.5 * height` is used
(see `dao.postgraphics.v2.md` section Viewport Mapping).

## Command Model

Unlike the Flutter terminal, which wraps an imperative canvas object per op,
the WebGPU terminal lowers each frame into one or more GPU passes. The bytecode
contract is still immediate-mode and ordered; the difference is only in host
realization.

The VM therefore has two layers of state during a presentation:

- VM-local interpretation state: transform stack, clip stack, camera, lighting,
  render target stack, presented-frame bookkeeping
- GPU execution state: command encoder, bind groups, pipelines, transient
  vertex / index / uniform buffers, and texture attachments for the current
  frame

The VM-local state is authoritative. GPU state is derived from it while the
frame is lowered and submitted.

## VM-Local State

The abstract state slots are defined in `dao.postgraphics.terminal.md`.
For the WebGPU terminal, they are realized as follows for the lifetime of one
submitted frame:

- **`model-stack`** and **`clip-stack`** - held in the lowering state while ops
  are interpreted into passes and draw batches.
- **`camera-matrix`** - held as the active 4x4 view-projection matrix or `nil`.
- **`camera-pos`** - follows the shared terminal rule from
  `dao.postgraphics.terminal.md` and is lowered into the active lighting
  uniforms / bind data when v4 lighting is enabled.
- **depth state** - realized through the active depth attachment and per-draw
  depth-test / depth-write state in the encoded render passes.
- **lighting state** - realized as lowering-time light lists plus shader
  uniforms / bind data for the active draw.
- **render-target state** - realized as a VM-local target stack plus GPU
  texture attachments / views in the current frame.

The WebGPU terminal also maintains transient lowering products for the current
frame:

- **`draw-batches`** - ordered list of GPU draws for the current pass
- **`geometry-report`** - optional list of resolved interactive regions for
  `dao.gui.event`
- **`submission-id`** - terminal-local ingress sequence number

## Frame Boundary

Each accepted frame submission runs this sequence:

1. allocate a fresh frame-local VM state
2. run `validate-frame!` on the bytecode vector
3. lower ops in bytecode order into render-target passes and draw batches
4. encode GPU commands from the lowered representation
5. submit the command buffer to the WebGPU queue
6. after successful submission, advance presented-frame ID when this
   terminal exposes presented-frame identity, and publish geometry /
   signals when this terminal exposes the corresponding surface; in all
   cases the underlying accounting is preserved per
   `dao.postgraphics.terminal.md § Frame ID Rules`

If pre-submit validation or lowering fails, the terminal emits a Frame
Rejection Signal, does not submit a new GPU command buffer, and applies the
shared terminal presentation semantics for a rejected frame.

If host-side GPU device loss or command submission failure occurs after the
frame has passed validation, the error is a host/runtime failure, not a frame
validation failure. The terminal MAY emit implementation diagnostics, but the
canonical rejection signal vocabulary remains constrained by
`dao.postgraphics.md`.

## Lowering Patterns

The WebGPU VM uses three realization patterns.

### Pattern A - 2D shape and image ops

Ops:

- `:draw/fill-rect`
- `:draw/stroke-rect`
- `:draw/fill-circle`
- `:draw/stroke-circle`
- `:draw/path`
- `:draw/image`

Lowering contract:

```text
resolve clip-stack to screen-space scissor / clip uniforms
resolve model-stack.top to 2D affine transform
convert op geometry to GPU-local instance data
append one draw item to the active color pass
```

The draw item carries:

- pipeline kind
- transformed or transformable geometry payload
- paint data
- active clip list or equivalent scissor decomposition
- source texture handle for image draws when applicable

Implementations MAY use instancing, tessellation, SDF quads, or other GPU-local
encodings so long as the producer-visible result matches the v1-v3 contract.

### Pattern B - text op

Op:

- `:draw/text`

Lowering contract:

```text
resolve :position through model-stack.top on the CPU
convert to screen coords: screen-y = viewport-height - cart-y
shape / atlas-resolve the text run
append glyph quads or equivalent text primitives to the active color pass
```

As in the Flutter terminal and `dao.postgraphics.v3.md`, the current transform's
scale and rotation affect only the resolved anchor position, not glyph size or
orientation. `:font-size` is in screen pixels.

### Pattern C - 3D ops

Ops:

- `:draw3d/lines`
- `:draw3d/triangles`
- `:draw3d/mesh`

Lowering contract:

```text
require non-nil camera-matrix
compute MVP = camera-matrix * model-stack.top
emit GPU draw payload in object / world / clip-space form
attach depth state, light state, material state, and target state
```

Unlike the Flutter terminal, the WebGPU terminal uses the GPU's native depth
buffer and fragment stage for the main 3D path. Clip-stack rects still apply in
screen space after projection. Because the active clip surface is a stack of
axis-aligned screen-space rects, the terminal may intersect the active
clip-stack into one axis-aligned rect and realize that intersection as one
hardware scissor. Shader discard is also permitted, but not required by the
documented clip contract.

## Non-Drawing Op State

These ops mutate VM-local state and emit no draw directly:

- `:transform/push`, `:transform/pop`
- `:clip/push-rect`, `:clip/pop`
- `:meta/region`
- `:camera3d/set`
- `:state/depth-test`, `:state/depth-write`
- `:state/lighting-enable`
- `:light/ambient`, `:light/directional`, `:light/point`, `:light/clear`
- `:target/push`, `:target/pop`

`:frame/clear` is a drawing op. When it appears at the beginning of a pass, the
terminal MAY realize it with pass-level clear operations. When it appears later
in bytecode order, the terminal MUST still preserve bytecode semantics by
splitting the pass or otherwise issuing an equivalent ordered clear of the
active color attachment and depth attachment at that point in the frame.

## Op Mapping

This section pins how each bytecode op is realized through WebGPU concepts.
Field semantics, required/optional fields, and Failure Semantics are defined in
`dao.postgraphics.md` and the v2-v4 addenda; this section adds only
WebGPU-specific realization.

### Paint Configuration

2D paint payloads carry:

- fill color or stroke color as straight-alpha sRGB rgba
- stroke width in screen pixels
- optional image sampler state for `:draw/image`

3D paint payloads carry:

- diffuse `:fill`
- optional per-vertex `:colors`
- optional texture source and sampling state
- optional material fields for v4 lighting

### `:frame/clear`

When `:frame/clear` is the first drawing op affecting the active target since
that target became current, the terminal MAY realize it with the render-pass
clear fields:

```text
colorAttachment.loadOp = "clear"
colorAttachment.clearValue = :color ?? [0 0 0 1]
depthAttachment.depthLoadOp = "clear"
depthAttachment.depthClearValue = 1.0
```

When `:frame/clear` occurs later in bytecode order, the terminal MUST preserve
the same producer-visible result by ending the current pass if necessary and
issuing an equivalent ordered clear before subsequent draws. Equivalent here
means:

- the active target's visible color contents become exactly `:color ?? [0 0 0 1]`
- the active target's depth contents become exactly `1.0`
- subsequent ops observe the cleared state
- prior ops remain visible only up to the point of the clear

If the frame omits `:frame/clear`, the default framebuffer behavior is whatever
the host previously configured for an empty frame, per the base spec.

### `:transform/push` / `:transform/pop`

No GPU calls directly. Mutate `model-stack` only.

`:transform/push` composes the op's local transform onto `model-stack.top` in
the order `scale -> rotate -> translate`, or uses `:matrix` directly when
present, promoted to 4x4 per v2.

### `:clip/push-rect` / `:clip/pop`

No GPU calls directly. Mutate `clip-stack` only.

`:clip/push-rect` resolves the op's rect through the active transform into a
screen-space rect. Because v1/v2 portability requires clip ancestry to be
translate-only, the resolution is exact:

```text
[tx ty] = model-stack.top.translation
[x y w h] = op.rect
screen-rect = [x + tx,
               viewport-height - (y + ty + h),
               w,
               h]
```

The resolved rect is pushed on `clip-stack`. Later draw lowering intersects all
active clip rects against the draw's screen-space coverage.

### `:meta/region`

Inert for paint. The WebGPU terminal validates shape and translate-only ancestry
and MAY emit geometry-report payloads for participants in `dao.gui.event`.

### `:draw/fill-rect`

Lower one filled rectangle in current-transform Cartesian space. The final
screen-space mapping is the same as the Flutter terminal: the op's rect is
transformed by the active 2D affine and then y-flipped into browser screen
space.

Implementations MAY realize this as:

- one instanced quad with affine transform uniforms
- one already-transformed quad in clip space
- one SDF rectangle primitive

### `:draw/stroke-rect`

Lower one stroked rectangle. Stroke width is in screen pixels per the base
spec's Base Value Conventions. If the implementation uses local-space expansion,
it MUST compensate for the active transform so that the visible stroke width
matches the requested screen-pixel width.

### `:draw/fill-circle`

Lower one filled circle centered at `[cx cy]` with radius in current transform
space. The terminal MAY use tessellated triangles or an SDF circle pipeline.

### `:draw/stroke-circle`

Lower one stroked circle. Visible stroke width obeys the same screen-pixel rule
as `:draw/stroke-rect`.

### `:draw/path`

Lower the segment list into one path payload. Valid segment vocabulary matches
the base spec:

- `[:move-to x y]`
- `[:line-to x y]`
- `[:quad-to cx cy x y]`
- `[:cubic-to cx1 cy1 cx2 cy2 x y]`
- `[:close]`

The implementation MAY use:

- CPU tessellation into triangles
- GPU path coverage from a path-specific pipeline
- SDF-based path realization

Whatever representation is chosen, the producer-visible fill / stroke result
must match the v3 precision contract for joins, caps, tessellation tolerance,
and fill/stroke composition.

### `:draw/image`

Behavior depends on `:image/source`.

**`:image/placeholder`**

Emit a filled rect with color `[1 1 1 (:opacity ?? 1)]`. `:image/fit` and
`:image/src-rect` have no effect.

**Resolved image / GPU texture**

Lower one textured quad using:

- source texture view
- sampler state realizing v3's default bilinear behavior
- clamp-to-edge behavior for source coordinates outside bounds
- `:opacity` multiplying sampled alpha

The fit calculation is the same producer-visible contract as v3:

```text
[dst-x dst-y dst-w dst-h src-x src-y src-w src-h] =
    resolve-fit(:image/fit ?? :fill, :rect, :image/src-rect ?? image bounds)
```

The terminal MAY realize opacity either by fragment shader multiplication of the
sampled alpha or by an equivalent pipeline constant; RGB must remain untouched
and alpha must become `sampled_alpha * :opacity`.

**Any other value**

As with the Flutter terminal, any unresolved resource key, URI, asset
identifier, or payload reaching this stage is rejected with
`:reason :unloadable-image`.

### `:draw/text`

The text run is laid out and realized as glyph primitives in screen space.

Pseudocode:

```text
[lx ly] = :position
[tx ty] = model-stack.top.translation
cart-x  = m00*lx + m01*ly + tx
cart-y  = m10*lx + m11*ly + ty
screen-x = cart-x
screen-y = viewport-height - cart-y

shape text run
measure unwrapped advance width
x-off = { :start: 0, :center: -width/2, :end: -width }[align]
emit glyph quads anchored at [screen-x + x-off, screen-y - baseline]
```

Normative rules:

- `:font-size` is in screen pixels
- glyph size and orientation are not scaled or rotated by the active transform
- `:position` is the alphabetic baseline origin after transform and y-flip
- the run is single-line unless the producer explicitly emits multiple ops
- hard line breaks inside `:text` are implementation-defined and producers
  SHOULD NOT rely on them

The normative requirement is that the host's shaping / measurement path MUST
measure the unwrapped advance width of the entire run used for `:align`
resolution. Any host API that introduces wrapping, width clamping, or automatic
line breaking before width measurement is non-conforming for this op. The
producer-observable contract is the same single-line, total-advance anchoring
documented in `dao.postgraphics.v3.md` and the Flutter terminal.

The host may use MSDF, bitmap atlases, browser shaping plus atlas upload, or an
equivalent strategy, so long as the producer-visible contract matches v3.

### `:draw3d/lines`

Lower line geometry into one or more GPU draws with:

- current `MVP`
- active depth-test / depth-write state
- active clip stack
- screen-pixel stroke width semantics per v2

### `:draw3d/triangles`

Lower triangle geometry into one or more GPU draws with:

- current `MVP`
- current target's depth attachment
- active clip stack
- uniform color from the op

### `:draw3d/mesh`

Lower indexed mesh geometry into one GPU draw or one tightly grouped draw set
with:

- vertex buffer for positions
- optional buffers for normals, UVs, colors
- index buffer
- active texture bindings
- active lighting state
- active render target attachments

The producer-visible lighting and texture contract is exactly v4:

- diffuse is `:fill` unless per-vertex colors are present
- textures modulate diffuse only
- specular / emissive are unaffected by texture
- lighting uses the Blinn-Phong equation from `dao.postgraphics.v4.md`

## Render Targets

Unlike the current Flutter terminal, the WebGPU terminal is expected to support
v4 render targets natively.

### `:target/push`

Pushes a new render target onto `render-target-stack`.

Lowering effect:

- allocate or reuse a color attachment and depth attachment with the requested
  format / size
- begin a new active pass targeting that attachment set
- inherit camera, transform, clip, lighting, and depth state from the outer
  scope, per v4
- register the target under `:target/id` for later sampling

If the browser / adapter cannot create or use the requested v4 target format
for the current device, the terminal MUST reject the frame with
`:reason :unsupported-op`. This keeps the failure canonical and makes the
available render-target surface explicit to producers.

### `:target/pop`

Ends the active target pass and restores the previous target on the stack.

If the popped target requested mipmaps, the terminal MAY schedule mip
generation before that target is sampled later in the frame.

### Sampling target textures

When `:texture/source` refers to a `:target/id`, the terminal binds the popped
target's texture view as the sampled source. v4's restrictions still apply:

- a currently bound target cannot be sampled from itself
- a missing target ID is a validation failure
- depth targets are only sampled where v4 permits them

## WebGPU-Specific Validation

The main spec's Failure Semantics remains authoritative. This section adds the
small set of clarifications specific to the WebGPU host.

### Texture Source Resolution

The WebGPU terminal considers an image / texture source synchronously realizable
at presentation time when it is one of:

- a ready `GPUTexture` / texture view or host wrapper around one
- a browser image source that has already been uploaded into GPU memory
- the sentinel `:image/placeholder`
- a `:target/id` that names an already-produced render target, where v4 allows
  that sampling form

Any unresolved resource key, URI, asset identifier, or pending upload reaching
draw lowering is rejected with `:reason :unloadable-image`.

### Device Loss and Surface Errors

WebGPU device loss, canvas reconfiguration failure, or command submission
failure are host/runtime failures, not frame-validation failures. The terminal
MAY log or emit implementation diagnostics, but these do not introduce new
canonical rejection reasons.

### NaN and Infinity

As with the Flutter terminal, producers MUST NOT emit `NaN` or `±Inf` in any
numeric field. A WebGPU host SHOULD reject such values during validation with
`:reason :validation-failure` when practical, because they are especially likely
to poison matrix math, clip-space transforms, or buffer contents.

### Two-Phase Validation

Validation runs in two phases:

1. **Pre-encode validation** - `validate-frame!` walks the frame vector,
   simulates stack behavior, and checks all explicit failure semantics.
2. **Runtime guards during lowering / submission** - a small set of host-side
   checks may still fail later, such as texture upload availability, device
   state, or a render-target allocation failure.

Runtime-guard failures that are attributable to invalid frame content MUST be
converted to the same canonical Frame Rejection Signal as pre-encode failures.
Host-runtime failures that are not attributable to invalid frame content remain
host diagnostics.

## Frame ID Rules

The shared frame-accounting and canonical-signal rules are defined in
`dao.postgraphics.terminal.md` and `dao.postgraphics.md`.

WebGPU-specific note:

- this host is intended to expose the fuller terminal surface, including render
  targets and optional geometry reporting, but the exact browser-facing stream
  and callback API remains host-defined

## Wiring

A typical rendering workflow wires stages with `dao.stream` and ordinary
function composition:

```text
dao.scene stream
-> lowering function
-> dao.postgraphics stream
-> WebGPU graphics VM
```

Producers and consumers communicate via `dao.stream` cursors. Any direct-use
entrypoint a WebGPU implementation exposes is an optional helper layered on top
of the required stream-bound constructor, not a replacement for it.

In browser deployments, `dao.stream` remains the abstract stream surface. The
browser host may realize that surface in multiple ways:

- when the `dao.postgraphics` producer is remote, the browser-side stream is
  typically fed over WebSocket
- when the producer is local to the browser runtime, the stream is typically
  realized as an in-memory ring buffer

These are host transport choices for `dao.stream`, not extensions of the
`dao.postgraphics` bytecode contract.

## Host API

The browser host is exposed as a canvas binding rather than a Flutter widget.

### `postgraphics-canvas`

```text
(postgraphics-canvas gpu-canvas frame-stream
                     & {:keys [on-error
                               signal-stream
                               device-options]})
  -> host-handle
```

Arguments:

- `gpu-canvas` - an HTML canvas or wrapper from which the VM obtains a
  `GPUCanvasContext`
- `frame-stream` - required `dao.stream` cursor of complete frame programs

`frame-stream` is transport-neutral at the API boundary. In a browser host it
may be backed by:

- a WebSocket-fed browser stream implementation when the producer is remote
- an in-browser ring buffer when the producer is local

The WebGPU terminal consumes the cursor, not the transport.

Options:

- `:on-error` - optional callback invoked for every canonical frame rejection
- `:signal-stream` - optional stream for canonical terminal signals
- `:device-options` - optional adapter / device / format preferences

The returned `host-handle` owns:

- the WebGPU device and queue bindings used by the VM
- the stream reader
- the current presented-frame state
- any persistent GPU resources such as text atlases, cached pipelines, and
  target pools

### Lifecycle

- **Binding.** One call to `postgraphics-canvas` creates one VM instance and
  emits a VM Reset Signal with a fresh generation ID.
- **Resize.** When the canvas backing size changes, the host reconfigures the
  `GPUCanvasContext` and uses the new size as the viewport on the next paint.
  Producers that need explicit resize events must wire them separately.
- **Transport/runtime failure.** WebSocket disconnects, reconnects, browser ring
  buffer lifecycle, and other `dao.stream` transport/runtime concerns are host
  runtime behavior, not frame-validation failures.
- **Disposal.** Disposing the handle detaches the stream reader and releases
  GPU resources according to browser lifetime rules.

## Pipeline Realization Notes

The spec does not require one exact WebGPU implementation, but a conforming
host will typically use a small set of fixed pipelines:

- 2D solid-color pipeline for rects, circles, and tessellated paths
- 2D textured pipeline for `:draw/image`
- text pipeline using atlas-backed glyph quads
- 3D flat-color pipeline
- 3D lit / textured mesh pipeline
- optional post-process or blit pipeline for target-to-target copies

This is an implementation note, not an extension of the bytecode language.

## Compatibility and Conformance

- A WebGPU VM SHOULD produce visually identical output to a spec-correct
  Flutter software / shader VM for any frame, modulo documented host
  concessions such as text rasterizer differences and anti-aliasing precision.
- A frame that a v1-v4-conforming terminal rejects MUST also be rejected by the
  WebGPU VM with the same canonical `:reason`.
- The WebGPU VM is expected to implement the full v4 render-target surface,
  unlike the current Flutter terminal's documented limitation.
- The host MAY emit additional implementation diagnostics about adapter
  selection, device loss, shader compilation, or swapchain reconfiguration, but
  those diagnostics MUST NOT alter the canonical signal vocabulary.

## Accepted Defaults

- **Full v4 render-target support is the target design.** If an implementation
  ships temporarily without one piece of that surface, the limitation must be
  documented explicitly in the implementation notes, not hidden in the host
  contract.
- **Pipeline caching is permitted.** Cached `GPURenderPipeline`s, bind-group
  layouts, and sampler objects are host-private optimizations.
- **Instancing, tessellation, or SDF realization are all permitted** for 2D
  ops, so long as the producer-visible result matches the bytecode contract.
- **Text atlas strategy is host-private.** MSDF is recommended, not mandated.

## Open Questions

- **Geometry reporting path.** Whether the host should emit geometry from CPU
  lowering or from a GPU-readable side buffer is left open. Both can satisfy
  `dao.gui.event`; the trade is simplicity versus exact post-clip coverage.
- **Exact text raster parity.** As in v3 generally, WebGPU text remains
  host-specific unless a future addendum pins a canonical font + shaping stack.
- **2D clip realization.** A stack of arbitrary nested screen-space clip rects
  can be represented either by pre-intersection into one scissor when exact, or
  by shader clip tests. The host contract is the intersection semantics, not
  the chosen GPU mechanism.
- **HDR targets.** v4 permits `:rgba16f` and `:rgba32f`; a browser host must
  account for platform support and texture-format feature negotiation.
