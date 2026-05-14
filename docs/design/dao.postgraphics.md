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

## Coordinate System

V1 `dao.postgraphics` uses 2D Cartesian coordinates.

Rules:

- origin is `[0 0]`
- `x` increases to the right
- `y` increases upward
- draw rects (e.g. on `:draw/fill-rect`, `:draw/stroke-rect`, `:draw/image`) are
  `[x y width height]` where `[x y]` is the lower-left corner in the current
  transform space
- points (e.g. `:position`, `:center`) are `[x y]` in the current transform space
- clip rects on `:clip/push-rect` are an exception: they are always in
  screen-space (viewport pixel) coordinates, applied after the viewport
  transform, and not affected by the current transform stack — this matches
  the standard scissor-rect behavior of graphics APIs
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

` :translate`, when present, is `[x y]`.

` :rotate`, when present, is a counter-clockwise rotation in radians around the
current origin. This follows the standard mathematical convention for a y-up
Cartesian coordinate system.

` :scale`, when present, is `[sx sy]`.

` :matrix`, when present, is a 3x3 affine transform matrix in row-major order:

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

` :position` is the text baseline origin in Cartesian coordinates.

` :align`, when present, is one of:

- `:start`
- `:center`
- `:end`

V1 text bytecode is intentionally simple:

- one op draws one text run
- no rich inline spans
- no browser text model
- wrapping and line breaking should be resolved upstream where practical

### `:draw/path`

Draws a pre-lowered path.

Required fields:

- `:op/kind`
- `:segments`

Optional fields:

- `:fill`
- `:stroke`
- `:stroke-width`

` :fill`, when present, is a color value `[r g b a]`.

` :stroke`, when present, is a color value `[r g b a]`.

V1 path filling uses the non-zero winding rule.

` :segments` is a vector of path commands:

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

` :opacity`, when present, is an image-only opacity override in the range
`[0.0, 1.0]`. If omitted, it defaults to `1.0`.

` :image/source` may be:

- a backend image handle
- a resource key
- a URI or asset identifier
- a bitmap payload understood by the current graphics VM host

Portable frame programs should prefer:

- resource keys
- URIs or asset identifiers
- portable bitmap payloads

Frame programs that use backend image handles are host-specific and are not
portable across different graphics VM implementations. This is an allowed v1
escape hatch, but it weakens backend-neutral replay and transport.

` :image/src-rect`, when present, is `[x y width height]` in source-image
coordinates.

` :image/fit`, when present, is one of:

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
- a transform pop occurs without a matching push
- a clip pop occurs without a matching push
- the frame ends with a non-empty transform stack
- the frame ends with a non-empty clip stack
- numeric coordinate fields are malformed
- image fields are malformed or reference an unusable source

Graphics VM behavior on invalid frame:

- reject the frame explicitly
- keep the last valid frame visible if the VM is stateful across frames
- do not partially apply malformed bytecode

## Flutter Graphics VM

The Flutter implementation is one graphics VM host for `dao.postgraphics`.

Its responsibilities are:

- consume complete frame programs
- hold the last accepted frame in local state
- repaint when a new frame arrives
- interpret op maps directly
- apply the Cartesian-to-Flutter transform before drawing

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
 ;; clip rect is in screen-space pixels (not affected by the :translate above)
 {:op/kind :clip/push-rect
  :rect [24 72 304 168]}
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

- `dao.postgraphics` is a graphics bytecode vocabulary
- `dao.postgraphics` is executed by graphics VMs
- `dao.postgraphics` bytecode is separate from `yin.vm` bytecode
- `dao.postgraphics` VM is separate from `yin.vm`
- frame programs are vectors of op maps
- one frame program is one complete frame
- op order defines paint order
- `dao.postgraphics` uses 2D Cartesian coordinates
- Flutter is one graphics VM host, not the definition of the layer
- v1 is 2D-first, but `dao.postgraphics` is intended to extend to 3D
