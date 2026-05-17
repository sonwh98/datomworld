# Design: `dao.postgraphics`

## Summary

This document defines `dao.postgraphics` as a graphics VM and graphics bytecode
model.

The core idea is:

- `dao.postgraphics` is not only a graphics API
- `dao.postgraphics` defines a bytecode vocabulary
- a graphics VM interprets that bytecode on a host backend
- that graphics bytecode is separate from `yin.vm` bytecode
- that graphics VM is separate from `yin.vm`

`dao.postgraphics` borrows ideas from PostScript, PDF content streams, and
display-list interpreters, and extends that model beyond classic 2D page
graphics toward a broader 2D and 3D graphics substrate.

It should be thought of as a graphics substrate:

- higher-level engines can be built on top of it
- scene editors and DCC tools can be built on top of it
- render pipelines can lower into it

In that sense, systems analogous to Blender or Unreal are not peers of
`dao.postgraphics`. They are tooling and engine layers that could be built on
top of `dao.postgraphics` bytecode and graphics VMs.

In v1:

- producers emit `dao.postgraphics` frame programs
- retained producers such as `dao.scene` lower into `dao.postgraphics`
- immediate producers may emit `dao.postgraphics` directly
- backend-specific graphics VMs interpret `dao.postgraphics` on Flutter, GPU, or
  other terminals

V1 is 2D-first, but the architecture is intended to cover both 2D and 3D
rendering.

## Architecture

The intended layering is:

```text
authoring / scene values
-> lowering
-> dao.postgraphics bytecode
-> graphics VM
-> host backend
```

Higher-level stack:

```text
tooling / engines / editors
-> scene models / animation / materials / UI
-> lowering
-> dao.postgraphics bytecode
-> graphics VM
-> host backend
```

Examples:

Retained path:

```text
dao.scene
-> lower-scene->ops
-> dao.postgraphics
-> Flutter graphics VM
-> Flutter canvas
```

Immediate path:

```text
authoring code
-> dao.postgraphics
-> graphics VM
-> backend
```

The pipeline is wired with ordinary function composition over `dao.stream`:
each stage reads from one stream and writes to another, and an application
opens the streams it needs.

## What `dao.postgraphics` Is

`dao.postgraphics` is:

- a graphics bytecode vocabulary
- a frame-oriented executable IR for drawing
- a backend-neutral immediate-mode rendering language
- the instruction set executed by graphics VMs
- a substrate on which higher-level graphics tooling and engines may be built

`dao.postgraphics` is not:

- the scene algebra
- the authoring model
- the host backend
- a retained scene graph
- a browser DOM or widget system
- `yin.vm` bytecode
- `yin.vm`

## Relationship to `dao.scene`

For retained-mode rendering:

- `dao.scene` describes semantic structure
- `dao.postgraphics` describes executable drawing
- a graphics VM executes that drawing program

So:

```text
dao.scene -> dao.postgraphics -> graphics VM
```

This means `dao.postgraphics` is a derived execution form for retained
producers.

For immediate-mode rendering, `dao.postgraphics` can also be the primary authoring
surface at the rendering boundary.

For higher-level engines and tools, `dao.postgraphics` is the execution substrate
underneath richer scene, animation, material, and interaction systems.

## Graphics VM

A graphics VM is the machine that interprets `dao.postgraphics` bytecode.

Its responsibilities are:

- read one frame program
- interpret bytecode ops in order
- maintain VM-local execution state such as transform stack and clip stack
- map bytecode coordinates and colors into the host backend
- realize drawing effects on the backend

Its responsibilities do not include:

- scene interpretation
- layout
- component evaluation
- reconstructing higher-level UI semantics

Examples of graphics VM hosts:

- Flutter canvas backend
- GPU-backed renderer
- SVG emitter
- PDF emitter
- software raster backend

Each host is a different terminal for the same bytecode model.

`yin.vm` may still be used elsewhere in the system to produce, transform, or
schedule `dao.postgraphics` programs, but it is not the graphics VM defined here.

Likewise, higher-level tooling or engine systems may sit above `dao.postgraphics`,
but they are not the bytecode or VM themselves.

## Bytecode Representation

V1 `dao.postgraphics` programs are complete frame values.

One frame program is one vector of op maps:

```clojure
[op*]
```

Each op is a map containing at least:

```clojure
{:op/kind keyword}
```

Any op may additionally carry arbitrary metadata under `:op/meta`.

One stream emission or one direct invocation may carry one complete frame
program.

V1 does not use incremental patching. A new frame program replaces the old one
at the VM boundary.

## Bytecode Execution Model

The graphics VM interprets a frame program sequentially.

Rules:

- ops execute in order
- later draw ops paint over earlier draw ops
- transform ops affect only subsequent ops until popped
- clip ops affect only subsequent ops until popped
- VM-local stacks such as transform and clip are LIFO
- malformed programs are rejected explicitly

No hidden reordering is allowed in v1.

## Metadata Carriage

`dao.postgraphics` is rendering bytecode, but v1 permits arbitrary metadata to
travel alongside that bytecode. This is a general carriage mechanism for higher
layers to preserve semantic structure without affecting paint.

Rules:

- any op may include an optional `:op/meta` field
- `:op/meta`, when present, is an arbitrary EDN value
- `:op/meta` does not change the rendering meaning of the op
- a compliant graphics VM must ignore `:op/meta` for paint semantics
- if a terminal participates in downstream geometry reporting or hit-testing,
  it MUST preserve and re-emit `:op/meta` for those consumers

### Standard Metadata Schema (Interaction)

While `:op/meta` is generic, v1 defines a standard schema for terminals that
participate in hit-geometry emission:

- a node participates in v1 hit-geometry emission when its metadata includes
  a non-empty `:interactive-events` set (e.g., `#{:tap}`)
- `:node-id` is used to carry authored identity downstream for stable lookup
- unrecognized event kinds in `:interactive-events` are ignored in v1; terminals
  SHOULD emit a warning diagnostic and continue with recognized kinds

**Standard `dao.gui` Convention:**

Interactive UI lowered through `dao.gui` uses a specialized sub-schema:

- `draw` ops carry only provenance identity (`{:node-id some-id}`) and MUST NOT
  carry `:interactive-events`
- hit-testing is governed by the dedicated `:meta/region` op, which is the sole
  carrier of `:interactive-events` for that target

Example of a `dao.gui` draw op with provenance identity:

```clojure
{:op/kind :draw/fill-rect
 :rect [24 16 96 32]
 :op/meta {:node-id ::save-button}}
```

The companion `:meta/region` op carries the interactive declaration:

```clojure
{:op/kind :meta/region
 :rect [24 16 96 32]
 :op/meta {:node-id ::save-button
           :interactive-events #{:tap}
           :op/precedence 17}}
```

## Normative v1 Protocol Contracts

To ensure interoperability across different VM and runtime implementations, v1
defines these strict operational contracts. All compliant terminals and
runtimes MUST adhere to these rules.

### Canonical Precedence Formula

Precedence across mixed producer families MUST be normalized using this formula:

`effective-z = (metadata-precedence << 32) | bytecode-index`

- **`:op/precedence`** — A 32-bit integer carried in `:op/meta`, defaulting
  to `0` if absent. This establishes structured "Layers".
- **`bytecode-index`** — The 0-based position of the op in the fully assembled
  frame vector.

`:op/precedence` MUST fit in `[0, 2^32)`. `bytecode-index` is always
non-negative, and frames with `2^32` or more ops are not supported in v1.

This formula ensures that structured layers always outrank generic bytecode,
while bytecode order remains the internal tie-breaker. Implementations MUST NOT
use raw integer magnitude from metadata as globally authoritative without this
normalization.

### Axis-Alignment Epsilon

All coordinate resolution and axis-alignment checks MUST use a shared tolerance
of **`1e-6`**. Any rotation or shear component in a resolved transform matrix
with an absolute value less than `1e-6` is treated as `0.0`.

### Canonical Signal Shapes

Auxiliary protocol signals on the geometry/input streams MUST use these
normative EDN shapes:

- **VM Reset:** `{:message/kind :dao.terminal/reset :generation-id <string-uuid>}`.
  Clears all downstream hit-generation state.
- **Frame Rejection:** `{:message/kind :dao.terminal/rejection :submission-id <int> :reason <keyword>}`.
  Notifies that a submitted frame was rejected; `:submission-id` names the
  rejected terminal-local ingress sequence number, and interactive truth
  remains at the previous presented ID.
- **Protocol Error:** `{:message/kind :dao.terminal/protocol-error :error/kind <keyword> :frame-id <int>}`.
  Signals an invalid state transition (e.g., `:future-frame-tap`).
- **Frame Skipped:** `{:message/kind :dao.terminal/frame-skipped :submission-id <int>}`.
  Makes an explicit gap in the terminal's submission sequence visible
  downstream when submission `N` never becomes a presented frame.

For v1, signal keyword vocabularies are constrained:

- **Frame Rejection `:reason`** MUST be one of `:validation-failure`,
  `:unloadable-image`, or `:unsupported-op`
- **Protocol Error `:error/kind`** MUST be one of `:future-frame-tap`,
  `:out-of-order-frame-events`, or `:stale-generation-tap`.
  `:future-frame-tap` is emitted when a tap is tagged with a frame-id
  greater than any presented frame in the current generation.
  `:out-of-order-frame-events` is emitted when geometry or tap events for
  frame `N` arrive after events for a later frame on the same generation.
  `:stale-generation-tap` is emitted when a tap is tagged with a frame-id
  whose generation has been superseded by a VM Reset Signal.

`:dao.gui.event/no-active-frame` is not a protocol error. It is an event-runtime
diagnostic kind used when a tap arrives before any frame in the current
generation has been presented; the terminal protocol is intact in that case,
but the event runtime has nothing to dispatch against.

## Coordinate System

V1 `dao.postgraphics` uses 2D Cartesian coordinates.

Rules:

- origin is `[0 0]`
- `x` increases to the right
- `y` increases upward
- rect-bearing ops (`:draw/fill-rect`, `:draw/stroke-rect`, `:draw/image`,
  `:meta/region`, `:clip/push-rect`) take `:rect` as `[x y width height]`
  where `[x y]` is the lower-left corner in the current transform space.
  Width and height are strictly positive in v1
- points (e.g. `:position`, `:center`) are `[x y]` in the current transform space
- clip rects on `:clip/push-rect` are in the **current transform space**,
  matching the rest of `dao.postgraphics`'s Cartesian convention. They are
  resolved to screen-space axis-aligned rectangles by the VM using the active
  transform stack. Because v1 restricts clips to translate-only ancestry,
  this mapping is deterministic and exact.
- z-order is represented by op order

Backend-specific graphics VMs are responsible for the final viewport transform.

Example:

- a Flutter graphics VM flips the y-axis into Flutter's top-left, y-down canvas
  space

V1 coordinates are not required to be globally flattened. A frame program may
establish local coordinate systems through transform ops, and subsequent draw
ops are interpreted relative to the current transform.

## Base Value Conventions

Shared conventions:

- colors are `[r g b a]` with each channel in `[0.0, 1.0]`
- stroke widths are non-negative numbers measured in screen pixels, applied
  after the viewport transform; a stroke width of `1.0` is one pixel wide on
  screen regardless of the current transform
- image rects are `[x y width height]`
- omitted optional fields fall back to VM defaults

Notes:

- three-channel colors `[r g b]` are not valid in v1; alpha must always be
  explicit
- the constant-screen-pixel stroke rule is intentional in v1. Scaling a shape
  does not scale its stroke width; authors who want visually thicker lines under
  zoom must emit a larger explicit `:stroke-width`

V1 VM defaults:

- fill color: `[1.0 1.0 1.0 1.0]`
- stroke color: `[1.0 1.0 1.0 1.0]`
- stroke width: `1.0`
- text color: `[1.0 1.0 1.0 1.0]`
- font size: `14`
- transform stack: identity
- clip stack: empty

## Rendering Conventions

These conventions apply to all draw ops in v1 and to both 2D and 3D draw ops in
v2. They define the contract that two compliant VMs must agree on for the same
input frame.

### Color Space

Color components in `[r g b a]` tuples are interpreted as **sRGB-encoded
values** in `[0.0, 1.0]`. The alpha component is linear (not gamma-encoded).

Blending is performed in **sRGB space**: the source-over formula below operates
on sRGB color components directly without linearization. This matches the
default behavior of common 2D graphics backends including Flutter Canvas and
HTML Canvas. A future version may add a state op to opt into linear-space
blending; v1 does not.

### Alpha Convention

Color values use **straight (non-pre-multiplied) alpha**. The producer specifies
RGB and A independently; `[1 1 1 0.5]` is half-transparent white, not "white at
half intensity." The VM performs pre-multiplication internally if its backend
requires it.

The `:opacity` field on `:draw/image` multiplies the image's per-pixel alpha
before blending. It composes with any alpha already in the image source.

### Blending

The default and only blend mode is **source-over** with straight alpha:

```
result.rgb = src.rgb × src.a + dst.rgb × (1 − src.a)
result.a   = src.a + dst.a × (1 − src.a)
```

The destination is the current frame buffer contents at the pixel. The source
is the draw op's color (or sampled image color) at the pixel, including any
alpha modulation from anti-aliasing coverage.

Blending is applied after rasterization and (in v2) after the depth test. It
applies uniformly to 2D and 3D draw ops.

There is no blend-mode state op in v1 or v2. All draws use source-over.

### Anti-Aliasing

Edges of 2D primitives (rectangles, circles, paths, lines, text) are
anti-aliased using **analytical coverage**: a pixel partially covered by a
primitive's edge contributes its color modulated by the coverage fraction —
equivalently, the effective source alpha for that pixel is
`src.a × coverage_fraction` under the source-over rule above. Pixel centers
fully inside a primitive have coverage `1.0`; pixel centers fully outside have
coverage `0.0`.

For 3D primitives (`:draw3d/lines`, `:draw3d/triangles` in v2), edges should be
anti-aliased where the host backend supports it (typically via MSAA, supersampling,
or analytical coverage). VMs without anti-aliased 3D rasterization may produce
aliased 3D edges; this is the only host-specific quality concession in the
contract.

Anti-aliasing cannot be disabled. There is no aliased-rendering mode in v1 or v2.

The top-left fill rule (defined in v2 for 3D triangles) determines pixel
*ownership* at shared edges; analytical coverage determines partial-pixel alpha
at the *outer* edges of each primitive. The two rules compose: a pixel exactly
on a top or left edge is owned by that triangle (coverage `1.0`); a pixel
exactly on a right or bottom edge is owned by the adjacent triangle (coverage
`0.0` for this triangle).

## V1 Bytecode Vocabulary

V1 `dao.postgraphics` supports exactly these op kinds:

- `:frame/clear`
- `:transform/push`
- `:transform/pop`
- `:clip/push-rect`
- `:clip/pop`
- `:meta/region`
- `:draw/fill-rect`
- `:draw/stroke-rect`
- `:draw/fill-circle`
- `:draw/stroke-circle`
- `:draw/text`
- `:draw/path`
- `:draw/image`

These v1 ops are intentionally 2D-first, but the architecture is intended to
cover both 2D and 3D rendering. See `dao.postgraphics.v2.md` for the 3D
extension.

Later versions should extend the graphics bytecode and graphics VM with 3D
rendering concerns such as:

- camera and projection ops
- viewport ops
- depth and blend state ops
- mesh or primitive draw ops
- material or shading state ops

Those should remain part of `dao.postgraphics` rather than being borrowed from
`yin.vm`.

That is important if `dao.postgraphics` is to serve as a substrate for richer
systems comparable in role to game engines or content tools.

### `:frame/clear`

Clears the frame background.

Producers SHOULD emit `:frame/clear` as the first op of a frame unless they
explicitly want backend-defined background behavior.

Required fields:

- `:op/kind`

Optional fields:

- `:color`

Example:

```clojure
{:op/kind :frame/clear
 :color [0.08 0.08 0.10 1.0]}
```

### `:transform/push`

Pushes a new transform onto the transform stack.

Required fields:

- `:op/kind`

Optional fields:

- `:translate`
- `:rotate`
- `:scale`
- `:matrix`

`:translate`, when present, is `[x y]`.

`:rotate`, when present, is a counter-clockwise rotation in radians around the
current origin. This follows the standard mathematical convention for a y-up
Cartesian coordinate system.

`:scale`, when present, is `[sx sy]`.

`:matrix`, when present, is a 3x3 affine transform matrix in row-major order:

```clojure
[m00 m01 m02
 m10 m11 m12
 m20 m21 m22]
```

If `:matrix` is present, it is used directly and `:translate`, `:rotate`, and
`:scale` are ignored. Otherwise the VM composes the provided
translate/rotate/scale fields onto the current transform in this fixed order:

- scale
- then rotate
- then translate

Equivalently, for column-vector interpretation, the composed local transform is
`T * R * S`.

An empty `:transform/push` with none of `:translate`, `:rotate`, `:scale`, or
`:matrix` present is valid and means push the identity transform.

### `:transform/pop`

Pops the most recent transform.

Required fields:

- `:op/kind`

### `:clip/push-rect`

Pushes a rectangular clip region onto the clip stack.

Required fields:

- `:op/kind`
- `:rect`

Example:

```clojure
{:op/kind :clip/push-rect
 :rect [16 16 320 240]}
```

### `:clip/pop`

Pops the most recent clip region.

Required fields:

- `:op/kind`

Example:

```clojure
{:op/kind :clip/pop}
```

### `:meta/region`

Defines a non-drawing interactive region in the current transform space.

Required fields:

- `:op/kind`
- `:rect`
- `:op/meta`

Optional fields:
- none

Example:

```clojure
{:op/kind :meta/region
 :rect [0 0 100 100]
 :op/meta {:node-id ::invisible-hit-slop
           :interactive-events #{:tap}}}
```

The graphics VM must not paint anything for this op. It is used exclusively to
associate metadata with a spatial region in the current transform and clip
stacks. This is the preferred way to define hit-testing areas for nodes that do
not have a single 1:1 draw-op representation (e.g. text blocks, small icons with
large hit slop, or structural containers).

Its `:rect` is expressed in the current transform space, not in screen-space.
Like `:clip/push-rect`, it is resolved through the active transform stack. The
difference is semantic: `:clip/push-rect` affects subsequent paint and clip
state, while `:meta/region` contributes resolved geometry for downstream
hit-testing.

For `dao.gui.event` purposes, `:meta/region` participates in geometry emission
using the same metadata contract as draw ops:

- it must carry `:op/meta` with `:node-id` and non-empty `:interactive-events`
- its emitted region is derived from its rect after transform and clipping
- because it is non-drawing, it may produce interactive geometry even when no
  visible pixels were painted by draw ops with the same identity
- if higher layers rely on region precedence for hit routing, they may attach
  explicit precedence metadata without affecting paint semantics

A `:meta/region` with empty metadata or without a non-empty
`:interactive-events` set is still a valid bytecode op, but it is inert for v1
hit-geometry emission.

### `:draw/fill-rect`

Draws a filled rectangle.

Required fields:

- `:op/kind`
- `:rect`

Optional fields:

- `:color`

### `:draw/stroke-rect`

Draws a stroked rectangle.

Required fields:

- `:op/kind`
- `:rect`

Optional fields:

- `:color`
- `:stroke-width`

### `:draw/fill-circle`

Draws a filled circle.

Required fields:

- `:op/kind`
- `:center`
- `:radius`

Optional fields:

- `:color`

### `:draw/stroke-circle`

Draws a stroked circle.

Required fields:

- `:op/kind`
- `:center`
- `:radius`

Optional fields:

- `:color`
- `:stroke-width`

### `:draw/text`

Draws one text run.

Required fields:

- `:op/kind`
- `:text`
- `:position`

Optional fields:

- `:color`
- `:font-size`
- `:font-family`
- `:align`

`:position` is the text baseline origin in Cartesian coordinates. In v1, this
is the **alphabetic baseline origin** (bottom-left of a typical 'M' or 'A',
excluding descenders).

`:align`, when present, determines how the text run is anchored to `:position`:
- `:start`: The run starts at `:position` (default).
- `:center`: The run is centered horizontally on `:position`.
- `:end`: The run ends at `:position`.

V1 text bytecode is intentionally simple:

- one op draws one text run
- no rich inline spans
- no browser text model
- wrapping and line breaking should be resolved upstream where practical
- v1 text is single-style per text run; bidi, RTL, mixed-script shaping, and
  per-character styling are out of scope

**Normative Text Metrics:**

- To ensure layout stability, v1 producers and VMs MUST agree on the
  alphabetic baseline as the primary vertical anchor.
- Standard line-height in v1 is pinned to **1.2x font-size** unless explicitly
  overridden by a higher-level layout capability.
- If a graphics VM backend cannot guarantee pixel-perfect metric parity with
  the upstream measurement capability for a requested `:font-family`, it MUST
  fall back to a **System Monospace** font where metrics are strictly known
  and stable.
- Authors should treat text as a spatial block whose height is `font-size * 1.2`.

### `:draw/path`

Draws a pre-lowered path.

Required fields:

- `:op/kind`
- `:segments`

Optional fields:

- `:fill`
- `:stroke`
- `:stroke-width`

`:fill`, when present, is a color value `[r g b a]`.

`:stroke`, when present, is a color value `[r g b a]`.

V1 path filling uses the non-zero winding rule.

A path with no `:fill` and no `:stroke` is a no-op. A path with an empty
`:segments` vector is also a no-op.

`:segments` is a vector of path commands:

- `[:move-to x y]`
- `[:line-to x y]`
- `[:quad-to cx cy x y]`
- `[:cubic-to cx1 cy1 cx2 cy2 x y]`
- `[:close]`

### `:draw/image`

Draws a raster image or bitmap into a destination rect.

Required fields:

- `:op/kind`
- `:image/source`
- `:rect`

Optional fields:

- `:image/src-rect`
- `:image/fit`
- `:opacity`

`:opacity`, when present, is an image-only opacity override in the range
`[0.0, 1.0]`. If omitted, it defaults to `1.0`.

`:image/source` may be:

- a backend image handle
- a resource key
- a URI or asset identifier
- a bitmap payload understood by the current graphics VM host
- a sentinel value `:image/placeholder`, which the VM renders as an opaque
  rectangle filling `:rect` in the VM default fill color `[1.0 1.0 1.0 1.0]`

If `:image/source` is `:image/placeholder`, `:image/fit` has no effect in v1;
the placeholder simply fills `:rect`.
If `:image/source` is `:image/placeholder`, `:image/src-rect` also has no
effect in v1.
If `:image/source` is `:image/placeholder`, `:opacity` still applies and
modulates the placeholder rectangle the same way it would modulate a real image.

Portable frame programs should prefer:

- resource keys
- URIs or asset identifiers
- portable bitmap payloads

Frame programs that use backend image handles are host-specific and are not
portable across different graphics VM implementations. This is an allowed v1
escape hatch, but it weakens backend-neutral replay and transport.

`:image/src-rect`, when present, is `[x y width height]` in source-image
coordinates.

`:image/fit`, when present, is one of:

- `:contain`
- `:cover`
- `:fill`
- `:none`

## Failure Semantics

A frame program is invalid when:

- it is not a vector
- an op is not a map
- an op is missing `:op/kind`
- an op kind is unsupported
- required fields for an op kind are missing
- a `:meta/region` op is missing `:op/meta`
- a transform pop occurs without a matching push
- a clip pop occurs without a matching push
- the frame ends with a non-empty transform stack
- the frame ends with a non-empty clip stack
- numeric coordinate fields are malformed
- a color tuple does not have arity 4
- a color component is outside `[0.0, 1.0]`
- an `:opacity` value is outside `[0.0, 1.0]`
- a `:stroke-width` value is negative
- a `:font-size` value is non-positive
- a rect-bearing op (`:clip/push-rect`, `:meta/region`, `:draw/fill-rect`,
  `:draw/stroke-rect`, `:draw/image`) has non-positive width or height
- a circle op has a non-positive radius
- image fields are malformed or reference an unusable source. In v1, a source
  is unusable whenever the VM cannot synchronously realize it at presentation
  time. This includes a resource key that is still loading unless the producer
  substituted `:image/placeholder` first. (Note: with async-loaded resource
  keys, an unloadable image makes the whole frame invalid, effectively freezing
  the UI on the previous valid frame. Producers should either ensure image
  availability before emission or use the `:image/placeholder` sentinel for
  still-loading content.)

An empty frame program `[]` is valid in v1. It presents whatever the backend
draws for an empty op sequence; producers that want a defined background should
emit `:frame/clear` explicitly.
Overlay-only composition over separately rendered foreign content requires a
compositing model outside one standalone `dao.postgraphics` frame program, such
as another render pass or a layered terminal arrangement. This does not
conflict with `dao.gui`'s in-frame `flow ++ overlay` assembly for ordinary HUD
and overlay UI authored inside one `dao.postgraphics` frame.

Producer guidance for async image loading:

- producers track resource-load state as a `dao.stream` of asset-status
  events: each event is a value such as `{:resource k :status :loading}`,
  `{:resource k :status :loaded}`, or `{:resource k :status :error :reason r}`.
  The producer composes that stream into its frame-emission pipeline, so a
  status transition is itself the signal that drives the next frame
- until the latest status for a resource is `:loaded`, the producer emits
  `:image/placeholder` in place of the final `:image/source`; once it
  transitions to `:loaded`, the next emitted frame uses the real source
- v1 does not define a built-in `image-loaded?` capability. The asset-status
  stream, its producer, and its wiring into the frame producer are an
  application responsibility, consistent with `dao.postgraphics`'s "side
  effects appear as stream emissions" model.

## Flutter Graphics VM

The Flutter graphics VM is one concrete terminal host for `dao.postgraphics`.
It consumes complete frame programs and realizes them on Flutter's canvas while
preserving the bytecode model's ordering and state semantics.

Its responsibilities are:

- consume complete frame programs from a `dao.stream` or direct invocation
- validate each submitted frame before presentation
- assign a monotonically increasing presented-frame ID to each accepted frame
- hold the last accepted frame as the current visual and interactive truth
- repaint when a new accepted frame arrives
- interpret op maps directly in bytecode order
- apply the final Cartesian-to-Flutter viewport transform, including the y-axis
  flip from Cartesian `y`-up into Flutter's top-left, `y`-down canvas space
- resolve translate-only clip rects and interactive regions into screen-space
  rectangles using the active transform stack

For all rect-bearing ops in Flutter — `:draw/fill-rect`,
`:draw/stroke-rect`, `:draw/image`, `:meta/region`, and the resolved
screen-space form of `:clip/push-rect` — the VM flips the Cartesian
lower-left-corner rect into Flutter's top-left-corner screen space. A rect
`[x y width height]` therefore maps to Flutter as
`[x, viewport-height - (y + height), width, height]`. The rect height
participates in the y-flip; the VM must not treat the Cartesian `y` value
alone as the top edge. Here `viewport-height` is the current host-provided
height of the Flutter drawing surface for the frame being painted.

Frame ID rules:

- an accepted frame allocates exactly one new presented-frame ID; producer-side
  asset-status events and other auxiliary signals do not
- if the VM rejects an ingested frame (validation failure, unloadable image, or
  any Failure Semantics rule), the terminal MUST emit a **Frame Rejection
  Signal** (see §Canonical Signal Shapes) naming the rejected submission's
  `submission-id` and a constrained `:reason`. It does not allocate a new
  presented-frame ID and does not emit geometry for that submission. The last
  successfully presented frame remains both visually and interactively active.
  Terminals MAY emit additional implementation-specific diagnostics alongside
  the rejection signal, but the signal itself is mandatory for participants in
  `dao.gui.event`
- if the terminal accepts a submission into its ingress sequence but drops it
  before presentation, for example due to coalescing or backpressure, it emits
  a **Frame Skipped Signal** naming that skipped `submission-id`
- after a VM restart there is no surviving presented-frame generation. A new
  valid frame must be presented before interactive state becomes non-empty
- terminals that participate in `dao.gui.event` MUST emit an explicit **VM Reset
  Signal** when the presented-frame ID namespace is restarted

The Flutter VM adheres to the Canonical Signal Shapes and Axis-Alignment Epsilon
defined in §Normative v1 Protocol Contracts above. No Flutter-specific overrides
or additions apply in v1.

Its responsibilities do not include:

- evaluating `dao.gui` components
- interpreting `dao.scene`
- owning layout or UI semantics

A typical rendering workflow wires stages with `dao.stream` and ordinary
function composition:

```text
dao.scene stream
-> lowering function
-> dao.postgraphics stream
-> Flutter graphics VM
```

Producers and consumers communicate via `dao.stream` cursors. The Flutter
graphics VM can also be used directly if a producer hands it frame programs
without an intermediate stream.

## Example Frame Program

```clojure
[{:op/kind :frame/clear
 :color [0.09 0.09 0.11 1.0]}
 {:op/kind :transform/push
  :translate [16 16]}
 {:op/kind :draw/fill-rect
  :rect [0 0 320 240]
  :color [0.14 0.14 0.18 1.0]}
 {:op/kind :draw/stroke-rect
  :rect [0 0 320 240]
  :color [0.45 0.45 0.55 1.0]
  :stroke-width 1.0}
 {:op/kind :draw/text
  :text "Inventory"
  :position [16 24]
  :color [1.0 1.0 1.0 1.0]
  :font-size 20}
 ;; clip rect is in the current transform space (the :translate above does apply)
 ;; the VM resolves to screen-space using the active translate-only transform stack
 {:op/kind :clip/push-rect
  :rect [8 56 304 168]}
 {:op/kind :draw/text
  :text "Potion"
  :position [16 80]}
 {:op/kind :draw/text
  :text "Sword"
  :position [16 108]}
 {:op/kind :draw/image
  :image/source :icon/potion
  :rect [264 72 24 24]
  :image/fit :contain}
 {:op/kind :clip/pop}
 {:op/kind :transform/pop}]
```

## Immediate and Retained Relationship

`dao.postgraphics` is the shared executable rendering contract for both retained and
immediate graphics.

Retained path:

```text
dao.scene
-> dao.postgraphics
-> graphics VM
```

Immediate path:

```text
authoring code
-> dao.postgraphics
-> graphics VM
```

That means retained and immediate modes differ at the producer side, not at the
graphics VM boundary.

Above that boundary, richer systems may exist:

- scene editors
- animation tooling
- material systems
- world or level runtimes
- engine frameworks

Those systems should be thought of as building on `dao.postgraphics`, not replacing
the bytecode+VM layer.

## Accepted Defaults

These choices are fixed for v1:

- `dao.postgraphics` is a graphics bytecode vocabulary executed by graphics VMs
- frame programs are vectors of op maps; one vector is one complete frame
- op order defines paint order
- `dao.postgraphics` uses 2D Cartesian coordinates (y-up, bottom-left origin)
- **Canonical Precedence Formula:** `effective-z = (metadata-precedence << 32) | bytecode-index`.
- **Axis-Alignment Epsilon:** `1e-6` tolerance for coordinate resolution
- **Canonical Protocol Signals:** Normative EDN shapes for VM Reset, Frame Rejection, Protocol Error, and Frame Skipped signals
- v1 is 2D-first, but `dao.postgraphics` is intended to extend to 3D
- Flutter is one graphics VM host, not the definition of the layer
- `dao.postgraphics` bytecode is separate from `yin.vm` bytecode
