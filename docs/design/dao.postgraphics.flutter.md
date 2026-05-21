# Design: `dao.postgraphics.flutter`

## Summary

`dao.postgraphics.flutter` is one concrete terminal host for `dao.postgraphics`.
It consumes complete frame programs and realizes them on Flutter's canvas while
preserving the bytecode model's ordering and state semantics.

`dao.postgraphics` itself is terminal-neutral. This document defines how a
Flutter-hosted VM interprets that bytecode. The WebGPU terminal is documented
separately in `dao.postgraphics.webgpu.md`.

## Responsibilities

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

Its responsibilities do not include:

- evaluating `dao.gui` components
- interpreting `dao.scene`
- owning layout or UI semantics

## Viewport Transform

For all rect-bearing ops — `:draw/fill-rect`, `:draw/stroke-rect`,
`:draw/image`, `:meta/region`, and the resolved screen-space form of
`:clip/push-rect` — the VM flips the Cartesian lower-left-corner rect into
Flutter's top-left-corner screen space. A rect `[x y width height]` therefore
maps to Flutter as `[x, viewport-height - (y + height), width, height]`. The
rect height participates in the y-flip; the VM must not treat the Cartesian
`y` value alone as the top edge. Here `viewport-height` is the current
host-provided height of the Flutter drawing surface for the frame being
painted.

For v2+ 3D NDC mapping, the y-down screen formula
`screen_y = (1 - ndc_y) × 0.5 × height` is used
(see `dao.postgraphics.v2.md` § Viewport Mapping).

## Canvas State Protocol

The Flutter VM holds two kinds of state during a paint: state on the
`Canvas` object (Flutter's own clip / transform stack) and state local to
the VM (transform stack, clip stack, software depth buffer, lighting).
These stacks are independent — the VM's stacks live in the op-loop's
locals and are never pushed onto the canvas's own state stack. The canvas
is wrapped per-op from those local values.

### VM-Local State

Maintained for the lifetime of one paint invocation:

- **`model-stack`** — list of 4×4 column-major matrices. Initialized as
  `(list identity)`. Mutated by `:transform/push` and `:transform/pop`.
- **`clip-stack`** — list of resolved screen-space `Rect`s. Initialized
  empty. Mutated by `:clip/push-rect` (which resolves the op's current-
  transform-space rect into screen-space using the translate-only
  ancestor chain) and `:clip/pop`.
- **`camera-matrix`** — the active 4×4 view-projection matrix, or `nil`
  if no camera is set. Set by `:camera3d/set`.
- **`depth-buffer`** — VM-private `Float32List` of length
  `ceil(viewport-width) × ceil(viewport-height)`. Allocated fresh per
  paint invocation, filled with `1.0`. Cleared again by `:frame/clear`
  and by `:camera3d/set` when a previous camera was active.
- **`lighting-enabled`** — boolean. Default `false`. Toggled by
  `:state/lighting-enable` per `dao.postgraphics.v4.md § Lighting Mode`.
- **`lights`** — ordered list of active lights. Empty by default.
  Appended by `:light/ambient`, `:light/directional`, `:light/point`;
  cleared by `:light/clear`. Field shapes per
  `dao.postgraphics.v4.md § Light Ops`.
- **`camera-pos`** — world-space eye position, used by the lighting
  equation. Resolved from `:camera3d/set` as follows:
  - If `:camera3d/view-matrix V` is present in the op, `camera-pos =
    (V⁻¹).translation` — that is, take entries `[12 13 14]` of the
    column-major inverse view matrix. This is the eye position
    consistent with `V` mapping world → view space (the inverse
    matrix maps view origin back to the world eye). The producer
    MUST emit an invertible 4×4 view matrix; a non-invertible
    `:camera3d/view-matrix` is rejected with `:reason
    :validation-failure`.
  - Otherwise, `camera-pos = :camera3d/position` (defaulting to
    `[0 0 0]` per v2 § `:camera3d/set`).
  - If both are present, `:camera3d/view-matrix` takes precedence
    for the projection × view product (per v2), and the same
    inverse-matrix derivation supplies `camera-pos`;
    `:camera3d/position` is ignored. This keeps the eye position
    consistent with the projection actually used by the shader.
- **`depth-test`**, **`depth-write`** — booleans. Default `false`.

`lighting-enabled`, `lights`, and `camera-pos` are all reset implicitly
each paint by allocating fresh state at the top of the op loop.

### Frame Boundary

Each `CustomPainter.paint(canvas, size)` invocation:

1. `canvas.save()` once at frame start.
2. `canvas.clipRect(Rect.fromLTWH(0, 0, viewport-width, viewport-height))`
   to clip everything to the host surface.
3. Run the op loop inside a `try`.
4. `canvas.restore()` in the `try`'s `finally`, balancing the
   frame-start `save()` whether the loop completed normally or threw.

Flutter does not auto-restore unbalanced saves left on the canvas by
`paint(...)`; the VM MUST pair the frame-start `save()` with an
explicit `restore()` in `finally`. This guarantees that a mid-loop
rejection (or any exception escaping the op handler) leaves the
canvas's own state stack at the depth it started at.

`validate-frame!` runs before the op loop starts (see § Flutter-
Specific Validation > Two-Phase Validation); a pre-paint rejection
never reaches this `try`. The `try`/`finally` here exists for
**runtime-guard** throws from inside the op loop. When such a throw
occurs, the canonical Frame Rejection Signal is emitted, the
`finally` runs the frame-level `restore()`, and the VM treats the
frame as not-presented for interactive accounting (no new presented-
frame ID is allocated, and the previous successfully presented frame
remains interactive truth). Any pixels Flutter actually flushed from
the partial paint are visually transient — they are overwritten by
the next successfully presented frame and do not become interactive
content. Combined with the Hygiene Rules below (every per-op
`save()` has a matching `restore()` before the op handler returns),
this keeps the canvas's own state stack balanced across rejection.

### Per-Op Canvas Wrapping

2D shape and image ops, text ops, and 3D ops follow three distinct
patterns.

**Pattern A — 2D shape and image ops** (`:draw/fill-rect`,
`:draw/stroke-rect`, `:draw/fill-circle`, `:draw/stroke-circle`,
`:draw/path`, `:draw/image`):

```text
canvas.save()
for r in clip-stack: canvas.clipRect(r)
canvas.translate(0, viewport-height)
canvas.scale(1, -1)
canvas.transform(model-stack.top)
<op-specific Flutter draw call in current transform space, Cartesian y-up>
canvas.restore()
```

The canvas is in Cartesian local space inside the wrap. Op handlers
issue rect/circle/path draws using the op's own coordinates directly
(see § Op Mapping). Stroke widths are divided by the resolved transform
scale `sx` so that a `:stroke-width 1.0` is one screen pixel regardless
of model scale, per `dao.postgraphics.md § Base Value Conventions`.

**Pattern B — text op** (`:draw/text`):

```text
canvas.save()
for r in clip-stack: canvas.clipRect(r)
<resolve :position through model-stack.top on the CPU>
<convert to screen coords: screen-y = viewport-height - cart-y>
<paint glyphs at screen position with no further canvas transform>
canvas.restore()
```

The canvas stays in Flutter screen space — no y-flip, no model transform
applied. Glyphs render at `:font-size` pixels per `dao.postgraphics.v3.md
§ Font Size`. The model transform's scale and rotation MUST NOT affect
glyph size or orientation; they affect only the resolved anchor position.

**Pattern C — 3D ops** (`:draw3d/mesh`, `:draw3d/lines`,
`:draw3d/triangles`):

```text
<canvas left in Flutter screen space — identity transform, no y-flip>
<MVP = camera-matrix × model-stack.top, computed on CPU>
<NDC → screen, clip-stack testing, depth-test/write all on CPU>
<rasterized pixels submitted as canvas.drawPath of 1×1 rects>
```

Clip-stack rects and the software depth buffer are applied per-fragment
during CPU rasterization, not via `canvas.clipRect` or hardware depth.
This is required because Flutter Canvas does not expose its depth buffer
to user code (see also § GPU Mesh Shading for why the rasterizer stays
in software even on the GPU path).

### Non-Drawing Op State

These ops mutate VM-local state only and MUST NOT call
`canvas.save`/`canvas.restore`:

- `:transform/push`, `:transform/pop` — mutate `model-stack`.
- `:clip/push-rect`, `:clip/pop` — mutate `clip-stack`.
- `:meta/region` — inert in v1 paint (validated for shape; no canvas
  effect; geometry-report contract is out of scope here).
- `:camera3d/set` — sets `camera-matrix`; clears `depth-buffer` when a
  prior camera was active.
- `:state/depth-test`, `:state/depth-write` — set the booleans.
- `:state/lighting-enable` — sets `lighting-enabled`.
- `:light/ambient`, `:light/directional`, `:light/point` — append to
  `lights`.
- `:light/clear` — empties `lights`.

`:frame/clear` is a drawing op that touches the canvas directly without
wrapping: it calls `canvas.drawPaint(fill-paint(:color))` and refills
`depth-buffer` with `1.0`. No `save`/`restore` is needed because
`drawPaint` does not introduce per-op state.

### Hygiene Rules

- Every per-op `canvas.save()` MUST have exactly one matching
  `canvas.restore()` before the op handler returns. No save/restore
  pairs span the boundary of a single op.
- The VM's `model-stack` and `clip-stack` MUST NOT be pushed onto the
  canvas's own state stack. The op-loop's recur values are authoritative;
  the canvas is wrapped per-op from those values.
- Pattern A and Pattern B MUST validate that `model-stack.top` is a 2D-
  affine matrix (`valid-2d-affine?`) before applying it; otherwise the
  frame is rejected with `:reason :validation-failure`.
- Pattern C MUST validate that `camera-matrix` is non-nil before issuing
  the draw; otherwise the frame is rejected with `:reason
  :validation-failure`.

## Op Mapping

This section pins how each v1 op is realized through Flutter Canvas
calls. Field semantics, required/optional fields, and Failure Semantics
are defined in `dao.postgraphics.md`; this section adds only the
Flutter-specific realization.

Draw ops execute inside the canvas wrapping defined in § Canvas State
Protocol. The "inside the wrap" notes below describe what runs between
the `save()` and `restore()` calls of Pattern A / B. Non-drawing ops
apply no wrap.

3D draw ops (`:draw3d/mesh`, `:draw3d/lines`, `:draw3d/triangles`)
follow Pattern C and are realized through a CPU rasterizer that writes
into a software depth buffer and submits results as
`canvas.drawPath(...)`. The GPU `FragmentShader` path for `:draw3d/mesh`
is described in § GPU Mesh Shading. The 3D state ops (`:camera3d/set`,
`:state/depth-test`, `:state/depth-write`, `:state/lighting-enable`,
`:light/ambient`, `:light/directional`, `:light/point`, `:light/clear`)
are non-drawing and are listed in § Canvas State Protocol > Non-Drawing
Op State. Per-op Flutter realization for 3D draws is out of scope
here.

### Paint Configuration

Two helper paints are referenced throughout:

- **`fill-paint(color)`** — `Paint` with `style = fill` and
  `color = Color.fromARGB(int(a*255), int(r*255), int(g*255),
  int(b*255))`. Colors are interpreted as sRGB straight alpha per
  `dao.postgraphics.md § Color Space` and § Alpha Convention.
- **`stroke-paint(color, width)`** — as above, plus `style = stroke`,
  `strokeWidth = width`, `strokeCap = butt`, `strokeJoin = bevel`.

For stroked ops, `width` is the op's `:stroke-width` (default `1.0`)
divided by the resolved transform scale `sx`, where `sx` is the
Euclidean length of `model-stack.top`'s first column
(`sqrt(m00² + m10²)`). This keeps on-screen stroke width invariant
under uniform model scale, per `dao.postgraphics.md § Base Value
Conventions`.

### `:frame/clear`

No wrap. Direct canvas call:

```text
canvas.drawPaint(fill-paint(:color ?? [0 0 0 1]))
depth-buffer.fillRange(0, length, 1.0)
```

`drawPaint` paints the entire surface in the current canvas transform
(identity at frame start, since `:frame/clear` runs before any wrap is
applied).

### `:transform/push` / `:transform/pop`

No canvas calls. Mutate `model-stack` only.

`:transform/push` composes the op's local transform onto
`model-stack.top` in the order `scale → rotate → translate` (per
`dao.postgraphics.md § :transform/push`), then conjs the result onto the
stack. If `:matrix` is present, the field's 3×3 affine is used directly,
promoted to 4×4 with the extra row/column being identity, per the v2
representation upgrade.

`:transform/pop` pops `model-stack`. The bottom (identity) entry is
preserved; popping below it is a frame-validation error.

### `:clip/push-rect` / `:clip/pop`

No canvas calls. Mutate `clip-stack` only.

`:clip/push-rect` resolves the op's `:rect` through `model-stack.top` to
a screen-space `Rect`. v1 restricts clip ancestors to translate-only
transforms, so the resolution is exact:

```text
[tx ty] = model-stack.top.translation     ;; entries 12, 13
[x y w h] = op.rect
screen-rect = Rect.fromLTWH(x + tx,
                             viewport-height - (y + ty + h),
                             w, h)
```

The resolved `Rect` is conjed onto `clip-stack`. The VM does not call
`canvas.clipRect` here — clip application happens inside Pattern A and
Pattern B wraps at draw time.

`:clip/pop` pops `clip-stack`. Popping when empty is a frame-validation
error.

### `:meta/region`

Inert in v1 paint. Validated for `:rect`, presence of `:op/meta`, and
translate-only ancestry. No canvas calls.

Geometry-report emission for `:meta/region` is out of scope here.

### `:draw/fill-rect`

Inside Pattern A wrap:

```text
canvas.drawRect(Rect.fromLTWH(x, y, w, h),
                fill-paint(:color ?? [1 1 1 1]))
```

`:rect` is `[x y w h]` in current-transform Cartesian space. The wrap
applies the y-flip and the model transform on the canvas, so the op
passes `:rect` through as-is.

### `:draw/stroke-rect`

Inside Pattern A wrap:

```text
canvas.drawRect(Rect.fromLTWH(x, y, w, h),
                stroke-paint(:color ?? [1 1 1 1], :stroke-width / sx))
```

### `:draw/fill-circle`

Inside Pattern A wrap:

```text
canvas.drawCircle(Offset(cx, cy), radius,
                  fill-paint(:color ?? [1 1 1 1]))
```

### `:draw/stroke-circle`

Inside Pattern A wrap:

```text
canvas.drawCircle(Offset(cx, cy), radius,
                  stroke-paint(:color ?? [1 1 1 1], :stroke-width / sx))
```

### `:draw/path`

Inside Pattern A wrap:

```text
path = build-path(:segments)
if :fill present:   canvas.drawPath(path, fill-paint(:fill))
if :stroke present: canvas.drawPath(path, stroke-paint(:stroke,
                                                       :stroke-width / sx))
```

`build-path` walks `:segments` and issues:

| Segment              | Flutter call                       |
|----------------------|------------------------------------|
| `[:move-to x y]`     | `Path.moveTo(x, y)`                |
| `[:line-to x y]`     | `Path.lineTo(x, y)`                |
| `[:quad-to cx cy x y]` | `Path.quadraticBezierTo(cx, cy, x, y)` |
| `[:cubic-to cx1 cy1 cx2 cy2 x y]` | `Path.cubicTo(cx1, cy1, cx2, cy2, x, y)` |
| `[:close]`           | `Path.close()`                      |

A path with neither `:fill` nor `:stroke`, or with an empty `:segments`
vector, issues no Flutter calls.

### `:draw/image`

Inside Pattern A wrap. Behavior depends on `:image/source`:

**`:image/placeholder`** — emit the placeholder rectangle:

```text
canvas.drawRect(Rect.fromLTWH(x, y, w, h),
                fill-paint([1 1 1 :opacity ?? 1]))
```

`:image/fit` and `:image/src-rect` have no effect.

**A Flutter `ui.Image` instance** — emit a transformed image draw using
`ImageShader`:

```text
[dst-x dst-y dst-w dst-h src-x src-y src-w src-h] =
    resolve-fit(:image/fit ?? :fill, :rect, :image/src-rect ?? image bounds)
shader-matrix = scale/translate mapping dst → src
shader = ImageShader(image, TileMode.clamp, TileMode.clamp, shader-matrix)
paint  = Paint() with .shader = shader and .filterQuality = FilterQuality.low
if :opacity < 1.0:
    paint.colorFilter = ColorFilter.matrix([
      1.0, 0.0, 0.0, 0.0,      0.0,
      0.0, 1.0, 0.0, 0.0,      0.0,
      0.0, 0.0, 1.0, 0.0,      0.0,
      0.0, 0.0, 0.0, :opacity, 0.0,
    ])
canvas.drawRect(Rect.fromLTWH(dst-x, dst-y, dst-w, dst-h), paint)
```

`TileMode.clamp` realizes `dao.postgraphics.v3.md § Source Coordinate
Clamping`: sampling outside the source rect returns the edge texel.

`FilterQuality.low` realizes v3's bilinear-sampling default.

The 20-element list passed to `ColorFilter.matrix` is the row-major 4×5
matrix Flutter's API defines: each output channel is
`out_c = m_c0*R + m_c1*G + m_c2*B + m_c3*A + m_c4` (the 5th column is a
constant offset). With the matrix above, R/G/B pass through unchanged
(rows 0-2 are identity-with-zero-offset), and A becomes `A × :opacity`
(row 3 scales A by `:opacity` with no offset). This leaves RGB untouched
and multiplies alpha by `:opacity`, which is the contract documented in
`dao.postgraphics.md § :draw/image > :opacity`.

**Any other value** (resource key, URI, asset identifier, bitmap
payload) — the VM MUST resolve the source to a `ui.Image` before this
handler runs. Producers receive an asynchronous loader pipeline through
the main spec's "async image loading" contract; while the image is
unresolved, producers emit `:image/placeholder` in its place. An op
that reaches this handler with an unresolved source is rejected with
`:reason :unloadable-image`.

### `:draw/text`

Inside Pattern B wrap (canvas stays in screen space). The text run is
laid out using Flutter's `ParagraphBuilder` / `Paragraph`:

```text
[lx ly] = :position
[tx ty] = model-stack.top.translation
cart-x  = m00*lx + m01*ly + tx
cart-y  = m10*lx + m11*ly + ty
screen-x = cart-x
screen-y = viewport-height - cart-y

style = ParagraphStyle(textDirection = ltr,
                       textAlign     = <:align ?? :start>,
                       fontSize      = :font-size ?? 14,
                       height        = 1.2)
text-style = TextStyle(color      = :color ?? [1 1 1 1],
                       fontSize   = <size>,
                       fontFamily = resolve-font-family(:font-family),
                       height     = 1.2)
paragraph = ParagraphBuilder(style).pushStyle(text-style)
                                    .addText(:text)
                                    .build()
paragraph.layout(ParagraphConstraints(width = no-wrap-width))
baseline = paragraph.alphabeticBaseline
width    = paragraph.maxIntrinsicWidth
x-off    = { :start: 0, :center: -width/2, :end: -width }[align]
canvas.drawParagraph(paragraph,
                     Offset(screen-x + x-off, screen-y - baseline))
```

The normative requirement is that **`paragraph.layout(...)` MUST be
called with a constraint width that does not cause Flutter to break the
run into multiple lines**, so that `paragraph.maxIntrinsicWidth`
returns the unwrapped advance width of the entire `:text` string and
the single-`x-off`-by-total-advance anchoring used below applies. The
producer-observable contract is this no-wrap behavior, not any specific
numeric width value passed to the engine.

`no-wrap-width` in the pseudocode above means "a width large enough
that the run remains single-line under the host text engine." A finite
constraint smaller than the natural intrinsic width would clamp
`maxIntrinsicWidth` and introduce soft wrapping, which violates v1's
"no rich inline spans, no browser text model" rule and changes the
baseline behavior of `:align`. Implementations MAY realize
`no-wrap-width` with any engine-valid value that preserves the
single-line invariant for the run being laid out. Producers that need
wrapping or line-breaking must pre-split text into multiple
`:draw/text` ops upstream, per `dao.postgraphics.md § :draw/text`.

Hard line breaks in `:text` (literal `\n`) are not addressed by v1's
text contract; behavior is implementation-defined and producers
SHOULD NOT emit them. The reference implementation passes them
through to `ParagraphBuilder.addText` unchanged, which Flutter
interprets as hard line breaks regardless of the layout width.

The `height = 1.2` line-height pins the v1 Normative Text Metrics rule
(`line-height = 1.2 × font-size`).

`resolve-font-family` returns the producer-supplied `:font-family` if
the host can guarantee metric stability for it; otherwise it falls back
to the System Monospace family per the v1 Normative Text Metrics
fallback rule.

The CPU-resolved baseline offset (`screen-y - baseline`) anchors the
alphabetic baseline at the requested `:position` after the y-flip; the
model transform's scale and rotation are intentionally ignored when
rendering glyphs, per `dao.postgraphics.v3.md § Font Size`.

## Flutter-Specific Validation

The main spec's § Failure Semantics is authoritative. This section adds
the small set of clarifications and behaviors that arise only on the
Flutter VM.

### Image Source Resolution

The main spec calls an `:image/source` "unusable" when the VM cannot
synchronously realize it at presentation time. For the Flutter VM,
"synchronously realize" means the source is one of:

- a Flutter `ui.Image` instance that is ready in memory at the moment
  `paint(...)` runs, OR
- the sentinel `:image/placeholder`.

Any other shape (resource key, URI, asset identifier, bitmap payload,
`Future<ui.Image>`, ...) MUST be resolved by the producer before
emission. An op that reaches the Flutter VM with an unresolved source
is rejected with `:reason :unloadable-image`. Producers wire
asset-status streams per the main spec's "Producer guidance for async
image loading" section.

### Disposed `ui.Image` Instances

A `ui.Image` whose `.dispose()` has been called prior to paint is
treated as unresolved: the VM rejects the frame with `:reason
:unloadable-image`. Producers are responsible for keeping referenced
images alive for the frames in which they appear.

Because Dart's `ui.Image` does not expose a synchronous `isDisposed`
getter, the VM MAY detect disposal lazily — by trapping the Flutter
runtime error that occurs when `ImageShader` is constructed against a
disposed image — and convert it to the canonical rejection. A
disposed image that survives `validate-frame!` and is detected only
during paint MUST still emit a Frame Rejection Signal and invoke
`:on-error`. The rejected frame is treated as not-presented, with the
same partial-paint semantics as any runtime-guard rejection (see
§ Two-Phase Validation below).

### NaN and Infinity in Numeric Fields

The main spec rejects "malformed" numeric coordinate fields but does
not pin floating-point edge cases. The Flutter VM:

- Does not validate matrices, vectors, or rect components against
  `NaN` or `±Inf` in v1.
- Produces undefined visual output when such values reach
  `canvas.transform`, `canvas.drawRect`, the depth buffer, or the
  GPU FragmentShader path.

Producers MUST NOT emit `NaN` or `±Inf` in any numeric field. A future
addendum may tighten this to an explicit `:reason :validation-failure`;
until then producers treat NaN/Inf as producer-side errors, not
VM-side rejections.

### Two-Phase Validation

Validation runs in two phases per accepted frame:

1. **Pre-paint validation** — `validate-frame!` walks the frame
   vector once, simulating the transform / clip stacks and checking
   every op against its Failure Semantics. A throw here emits a Frame
   Rejection Signal and the frame never reaches paint.
2. **Runtime guards during paint** — a small set of conditions (the
   2D-affine top of `model-stack` at draw time, the non-nil
   `camera-matrix` before a 3D draw, disposed `ui.Image` detection)
   are re-checked inside the op loop as a defensive measure.

A runtime-guard throw produces the same canonical Frame Rejection
Signal as a pre-paint failure and invokes the same `:on-error`
callback (see § Widget API > Options > `:on-error`). Both phases of
rejection emit the same signal shape and the same callback event —
producers cannot distinguish runtime-guard from pre-paint rejections
through the canonical contract. The op loop has already executed some
prefix of the frame's draws when a runtime guard fires, so the canvas
may carry partial drawing at the moment the guard throws. Two
producer-observable consequences follow:

- The VM treats the rejected frame as not-presented for interactive
  accounting: no new presented-frame ID is allocated, and the
  previously successfully presented frame remains the interactive
  truth.
- Any pixels Flutter actually flushed from the partial paint are
  visually transient — they are overwritten by the next successfully
  presented frame and do not become interactive content. The VM does
  not guarantee that nothing was ever visible on screen; it
  guarantees that nothing visible from a rejected frame survives past
  the next successful one.

The contract: runtime guards SHOULD duplicate checks already in
`validate-frame!`. If a producer observes a runtime-guard rejection
that `validate-frame!` did not catch, that is a VM bug — the
producer-observable behavior is the same either way (signal + on-
error), but the spec does not promise additional behaviors that depend
on which phase fired.

### Out of Scope

The following are NOT frame-validation concerns:

- `FragmentProgram` load failure (host-config error, not frame-side;
  see § GPU Mesh Shading > Failure Semantics).
- Producer-side stream errors on `frame-stream` or `:signal-stream`
  (those propagate as exceptions through `dao.stream`, not as
  rejection signals).
- Flutter framework errors during widget construction, layout, or
  paint scheduling (those propagate up the widget tree as Flutter
  errors).

## Frame ID Rules

- an accepted frame allocates exactly one new presented-frame ID; producer-side
  asset-status events and other auxiliary signals do not
- if the VM rejects an ingested frame (validation failure, unloadable image, or
  any Failure Semantics rule), the terminal MUST emit a **Frame Rejection
  Signal** (see `dao.postgraphics.md` § Canonical Signal Shapes) naming the
  rejected submission's `submission-id` and a constrained `:reason`. It does
  not allocate a new presented-frame ID and does not emit geometry for that
  submission. The last successfully presented frame remains both visually and
  interactively active. The terminal MAY emit additional implementation-
  specific diagnostics alongside the rejection signal, but the signal itself
  is mandatory for participants in `dao.gui.event`
- if the terminal accepts a submission into its ingress sequence but drops it
  before presentation, for example due to coalescing or backpressure, it emits
  a **Frame Skipped Signal** naming that skipped `submission-id`
- after a VM restart there is no surviving presented-frame generation. A new
  valid frame must be presented before interactive state becomes non-empty
- terminals that participate in `dao.gui.event` MUST emit an explicit **VM Reset
  Signal** when the presented-frame ID namespace is restarted

The Flutter VM adheres to the Canonical Signal Shapes and Axis-Alignment
Epsilon defined in `dao.postgraphics.md` § Normative v1 Protocol Contracts.
No Flutter-specific overrides or additions apply.

## Wiring

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

## Widget API

The Flutter VM is wrapped as a Flutter `Widget` via `postgraphics-widget`.

### `postgraphics-widget`

```text
(postgraphics-widget frame-stream
                     & {:keys [on-error
                               signal-stream
                               shader/strict-lights]})
  → Flutter Widget
```

Returns a Flutter `Widget` that renders `dao.postgraphics` frame programs
read from `frame-stream`.

**Arguments**

- `frame-stream` — required. A `dao.stream` cursor whose values are
  complete `dao.postgraphics` frame programs (vectors of op maps). The
  widget begins reading at position 0 of the stream.

**Options**

- `:on-error` — optional. A function `(fn [^Exception e] ...)` invoked
  for **every canonical frame rejection**, regardless of which phase
  raised it: pre-paint `validate-frame!` failures and runtime-guard
  failures during paint (see § Flutter-Specific Validation > Two-Phase
  Validation). The widget continues rendering the last accepted frame.
  If `:on-error` is `nil`, the VM emits a `dart:core/print` diagnostic
  instead. `:on-error` is advisory; the same rejection event also
  produces a Frame Rejection Signal on `:signal-stream` when one is
  provided (see § Frame ID Rules). The contract is that the callback
  and the signal fire as one event: every signal corresponds to one
  `:on-error` invocation (or one `print`) and vice versa.
- `:signal-stream` — optional. A `dao.stream` to which the VM emits
  canonical terminal signals (`:dao.terminal/reset`,
  `:dao.terminal/rejection`). Required for participants in
  `dao.gui.event`. When omitted, signals are dropped silently.
- `:shader/strict-lights` — optional, defaults to `false`. When `true`,
  3D mesh frames with more than `MAX_LIGHTS = 8` active lights are
  rejected with `:reason :validation-failure` rather than silently
  truncated. See § GPU Mesh Shading > Accepted Defaults.

### Lifecycle

- **Binding.** One call to `postgraphics-widget` creates exactly one VM
  instance, allocates one `ValueNotifier` to hold the current frame,
  and starts one reader on `frame-stream` at position 0. The binding
  emits a `:dao.terminal/reset` signal with a fresh `generation-id` at
  start.
- **Repaint.** The returned widget is a `CustomPaint` driven by the
  `ValueNotifier`. Every accepted frame replaces the notifier's value,
  which schedules a `paint(...)` invocation; that paint runs the op
  loop described in § Canvas State Protocol.
- **Rebuild.** A new call to `postgraphics-widget` constructs a new VM
  instance with a new notifier, new binding, and new `generation-id`.
  Producers that need a stable VM across parent rebuilds MUST construct
  `postgraphics-widget` once and embed the returned widget in a stable
  position in the tree rather than reconstructing it on every parent
  rebuild. (Implementations MAY wrap the binding in a stateful widget
  that reuses the VM across rebuilds; the producer-observed contract is
  the same either way: one binding, one generation, one VM.)
- **Disposal.** When the returned widget is removed from the tree, the
  reader on `frame-stream` is detached and no further signals are
  emitted from this VM instance.

### Producer Helpers

- **`(put-frame! frame-stream frame)`** — push one frame onto
  `frame-stream` and wake any reader waiting on it. Returns the
  stream's `:result` value. Equivalent to `(ds/put! frame-stream frame)`
  followed by resuming registered reader waiters; producers that
  already use `ds/put!` and manage their own readers do not need this
  helper.

### Compositional Rules

- One `postgraphics-widget` instance owns exactly one VM. Multiple
  widgets reading from the same `frame-stream` each instantiate
  independent VMs with independent `generation-id`s; the stream is
  effectively shared input.
- The widget does not own `frame-stream` or `:signal-stream`; the
  producer is responsible for their lifecycle.
- The widget is safe to embed at any level of the Flutter tree that
  accepts a `Widget`. Its size is determined by its parent's
  constraints; the VM uses the resulting `CustomPaint` size as the
  viewport dimensions for each paint invocation, with no resize
  notification emitted on the producer side. Producers that need a
  resize signal MUST wire one through their own widgets outside the
  `postgraphics-widget` boundary.

---

## GPU Mesh Shading (Flutter `FragmentShader` path)

This part of the document specifies a VM implementation upgrade, not a new
bytecode contract. It replaces v4's software per-fragment Blinn-Phong +
texture path with a Flutter `FragmentShader` running on the GPU. No new ops,
no new fields, no new producer-visible behavior — frames that compile and
render under a v4-conforming software VM compile and render under a Flutter
GPU-shaded VM with the same visual output (modulo the documented anti-aliasing
and color-space concessions inherited from v2/v4).

This upgrade exists because v4's lit-textured and vertex-color paths are not
real-time on the software VM. Each covered pixel goes through a Dart-side
`canvas.drawRect` call after a per-fragment Blinn-Phong evaluation; a 500×500
viewport with a single mid-density mesh issues tens of thousands of native
draw calls per frame. The GPU path collapses that to **one draw call per
triangle** by lifting the per-fragment computation into a GLSL fragment shader.

A Flutter VM that ships this path conforms to v1, v2, v3, and v4 **except
for v4's render-target stack** (`:target/push`, `:target/pop`,
`:target/id`, the `:depth` target sampling path). Those ops are rejected
with `:reason :unsupported-op`. See § Open Questions > Render targets
for the rationale and the plan to lift this restriction. A frame that
does not use render-target ops is fully v4-compliant under this VM.

### Motivation

The v4 dispatch in `exec-3d-mesh-op` routes `:draw3d/mesh` to one of four
paths:

| Conditions | Path | Native draw calls per mesh | Per-pixel Dart work |
|---|---|---|---|
| Unlit, no texture, no vertex colors | `rasterize-triangle` | 1 (`drawPath`) | edge tests only |
| Unlit, textured, no vertex colors | `rasterize-mesh-triangle-textured` | 1 (`drawPath` + `ImageShader`) | edge tests only |
| Lit, no texture | `rasterize-mesh-triangle-lit` | N pixels (`drawRect`) | barycentric + Blinn-Phong + sRGB↔linear |
| Lit-textured, or textured with vertex colors | `rasterize-mesh-triangle-shaded` | N pixels (`drawRect`) | barycentric + texture sample + Blinn-Phong + sRGB↔linear |

The bottom two rows dominate the cost budget on any non-trivial scene.
The two fast paths exist because Flutter natively expresses
`Path × ImageShader` as a single GPU operation, but they don't compose
with per-fragment lighting or per-vertex color interpolation — Flutter's
fixed `ColorFilter` and `BlendMode` vocabulary stops at uniform-per-paint
modulation.

`FragmentShader` (Flutter 3.7+) lifts that ceiling: the producer supplies
GLSL source, Flutter compiles it at build time, and the runtime can
attach it as `Paint.shader`. The per-fragment computation runs natively
on the GPU, and the VM submits the same one-`drawPath`-per-triangle work
unit it already uses for the fast textured path.

### Architecture

The Flutter GPU mesh path reuses the v4 software rasterizer for two reasons:

1. **Depth participation.** Flutter Canvas does not expose its depth
   buffer to user code, so the VM must keep its own software depth buffer
   to honour v2's per-fragment `:state/depth-test` / `:state/depth-write`
   semantics across textured, lit, and unlit geometry.
2. **Clipping.** Per-fragment clip-rect testing remains a software loop;
   the VM passes the final pixel set to the GPU as a `Path` of 1×1 rects.

The change is in the *color* stage. Each triangle compiles to:

```text
loop over covered pixels in software:
  depth-test
  clip-test
  if pass: add pixel to Path
end loop

paint.shader = FragmentShader(triangle-uniforms, texture sampler)
canvas.drawPath(path, paint)
```

The fragment shader runs once per emitted pixel on the GPU. It receives
each pixel's screen-space coordinate via `FlutterFragCoord()` and the
triangle's per-vertex data + material + lights as uniforms. It computes
the same v4 Blinn-Phong equation and writes the final sRGB color.

#### Pipeline Summary

```text
producer
-> dao.postgraphics frame program          (unchanged from v4)
-> Flutter graphics VM
     -> validate-frame!                    (unchanged)
     -> per op handler                     (unchanged)
     -> :draw3d/mesh dispatch              (GPU path routes the lit /
                                            vertex-color cases to the
                                            FragmentShader path instead
                                            of the software shaded
                                            rasterizer)
        -> software triangle rasterizer    (depth + clip, builds Path)
        -> FragmentShader paint            (GPU per-fragment color)
        -> canvas.drawPath
```

The dispatch table becomes:

| Conditions | Path |
|---|---|
| Unlit, no texture, no vertex colors | `rasterize-triangle` (unchanged) |
| Unlit, textured, no vertex colors | `rasterize-mesh-triangle-textured` (unchanged) |
| Lit, no texture | `rasterize-mesh-triangle-shaded-gpu` (new) |
| Lit-textured, or textured with vertex colors | `rasterize-mesh-triangle-shaded-gpu` (new) |
| Unlit, no texture, vertex colors | `rasterize-mesh-triangle-shaded-gpu` (new) |

Note that the unlit `:fill`-modulated textured path stays on the existing
`ImageShader` + `ColorFilter.matrix` route because that case is already
one GPU operation and doesn't benefit from a custom shader.

### VM State

In addition to v1–v4 VM state, a Flutter VM on this path maintains:

- **fragment-program** — the loaded `ui.FragmentProgram` compiled from
  the bundled `shaders/mesh.frag` asset. Initialized asynchronously the
  first time a mesh draw needs it; once loaded, subsequent frames reuse
  the program.
- **fragment-program-load-state** — one of `:not-started`, `:loading`,
  `:loaded`, `:failed`. The dispatch reads this to decide whether to use
  the GPU path or fall back to the v4 software path.

Both pieces of state are VM-private and not visible in any signal.

### Asset and Build

The shader source lives at `shaders/mesh.frag` in the package. Flutter's
build system compiles it to SPIR-V (Impeller) or GLSL ES 3.10 (web /
Skia) at build time when the asset is registered in `pubspec.yaml`:

```yaml
flutter:
  shaders:
    - shaders/mesh.frag
```

At runtime, the VM loads it once:

```dart
final program = await FragmentProgram.fromAsset('shaders/mesh.frag');
```

Compilation is the build tool's responsibility; the runtime sees a
ready-to-use `FragmentProgram` or a load failure.

### Fragment Shader Contract

The shader is a single GLSL fragment kernel. Its inputs (uniforms) are
arranged so that one shader handles all GPU-routed mesh draws — lit,
textured, vertex-colored, or any combination — with boolean-flag
uniforms gating the optional features.

#### Uniform Layout

Uniforms are ordered for stable indexing by the Dart side. The
`FragmentShader.setFloat(int index, double value)` API takes a flat
float index, so the layout is documented as a flat sequence:

| Index range | Field | Type | Description |
|---|---|---|---|
| `[0..1]` | `v0_screen` | vec2 | Vertex 0 screen pixel position |
| `[2..3]` | `v1_screen` | vec2 | Vertex 1 screen pixel position |
| `[4..5]` | `v2_screen` | vec2 | Vertex 2 screen pixel position |
| `[6..8]` | `v0_world` | vec3 | Vertex 0 world position |
| `[9..11]` | `v1_world` | vec3 | Vertex 1 world position |
| `[12..14]` | `v2_world` | vec3 | Vertex 2 world position |
| `[15..17]` | `v0_normal` | vec3 | Vertex 0 world normal (pre-normalized) |
| `[18..20]` | `v1_normal` | vec3 | Vertex 1 world normal |
| `[21..23]` | `v2_normal` | vec3 | Vertex 2 world normal |
| `[24..25]` | `v0_uv` | vec2 | Vertex 0 UV |
| `[26..27]` | `v1_uv` | vec2 | Vertex 1 UV |
| `[28..29]` | `v2_uv` | vec2 | Vertex 2 UV |
| `[30..33]` | `v0_color` | vec4 | Vertex 0 sRGB rgba (when has_vcols) |
| `[34..37]` | `v1_color` | vec4 | |
| `[38..41]` | `v2_color` | vec4 | |
| `[42..45]` | `fill` | vec4 | Material `:fill` sRGB rgba |
| `[46..48]` | `specular` | vec3 | `:material/specular` sRGB |
| `[49]` | `shininess` | float | `:material/shininess` |
| `[50..52]` | `emissive` | vec3 | `:material/emissive` sRGB |
| `[53..55]` | `camera_pos` | vec3 | World-space eye position |
| `[56]` | `has_texture` | float | `0.0` or `1.0` |
| `[57]` | `has_vcols` | float | `0.0` or `1.0` |
| `[58]` | `has_lighting` | float | `0.0` or `1.0` |
| `[59]` | `num_lights` | float | Active light count (≤ `MAX_LIGHTS`) |
| `[60..N]` | `lights[i]` | vec4 × 3 per light | See light packing below |

`MAX_LIGHTS` is fixed at **8**. The float-uniform budget for the
shader is `60 + 3*4*8 = 156` floats, plus one `sampler2D`. This sits
well under the ~256-float minimum guaranteed by Flutter on supported
platforms.

The single `sampler2D` is bound via `FragmentShader.setImageSampler(0,
image)`. When `has_texture == 0.0`, the producer-side binding may be a
1×1 dummy image; the shader ignores the sample.

**Per-vertex normal convention.** The `v*_normal` uniforms are
unit-length world-space normals. Producers emit raw direction vectors in
`:draw3d/mesh` `:normals` (which need only be non-zero per
`dao.postgraphics.v4.md § Normal Transformation`); the VM transforms
each vertex normal by the normal matrix
(`inverse-transpose(model-stack.top)`) on the CPU and normalizes the
result before packing it into the uniform array. The shader applies
`normalize(...)` again to the barycentric-interpolated fragment normal
because interpolation between unit vectors does not preserve unit
length.

#### Light Packing

The active light set is built by the VM from the v4 light ops:

- `:light/ambient`, `:light/directional`, `:light/point` each append
  one light of the corresponding type to the active list; `num_lights`
  reflects the count.
- `:light/clear` empties the active list; `num_lights` becomes `0`.
- `:state/lighting-enable` binds `has_lighting` (`1.0` when enabled,
  `0.0` otherwise). When `has_lighting == 0.0` the shader skips the
  light loop entirely; the active list is still uploaded but unused.
- `:camera3d/set` provides the world-space eye position bound to
  `camera_pos`.

See `dao.postgraphics.v4.md § Light Ops` and § Lighting Equation for
the producer-side contract on field shapes, color arity, and the
source-of-truth equation the shader realizes.

Each light occupies three vec4 slots:

| Offset within light | Field | Description |
|---|---|---|
| `0.xyz` | type, intensity, range | type ∈ {`0`, `1`, `2`} = {ambient, directional, point}; range only used for point |
| `1.xyz` | color | sRGB rgb |
| `2.xyz` | position-or-direction | world-space; ignored for ambient |

Lights beyond `num_lights` are skipped. Lights beyond `MAX_LIGHTS = 8`
are dropped at the VM dispatch boundary with a `:dao.terminal/rejection`
signal (`:reason :validation-failure`) when strict mode is enabled, or
silently truncated otherwise. The strict-vs-truncate choice is an
accepted default (see § Accepted Defaults).

#### Shader Logic

The kernel computes the same equation as v4's
`rasterize-mesh-triangle-shaded`. In GLSL pseudo-form:

```glsl
#version 460 core
#include <flutter/runtime_effect.glsl>

// uniforms as listed above
uniform sampler2D tex;

out vec4 fragColor;

float srgbToLinear(float v) {
  return v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4);
}

float linearToSrgb(float v) {
  return v <= 0.0031308 ? v * 12.92 : 1.055 * pow(v, 1.0/2.4) - 0.055;
}

vec3 sRgbToLin(vec3 c) {
  return vec3(srgbToLinear(c.r), srgbToLinear(c.g), srgbToLinear(c.b));
}

vec3 linToSrgb(vec3 c) {
  return vec3(linearToSrgb(c.r), linearToSrgb(c.g), linearToSrgb(c.b));
}

void main() {
  vec2 pos = FlutterFragCoord().xy;

  // Barycentric weights from the three screen-space vertices.
  vec2 e0 = v1_screen - v0_screen;
  vec2 e1 = v2_screen - v0_screen;
  vec2 e2 = pos - v0_screen;
  float denom = e0.x * e1.y - e0.y * e1.x;
  float v = (e2.x * e1.y - e2.y * e1.x) / denom;
  float w = (e0.x * e2.y - e0.y * e2.x) / denom;
  float u = 1.0 - v - w;

  // Interpolated per-fragment attributes.
  vec2 uv     = u * v0_uv     + v * v1_uv     + w * v2_uv;
  vec3 wpos   = u * v0_world  + v * v1_world  + w * v2_world;
  vec3 norm   = normalize(u * v0_normal + v * v1_normal + w * v2_normal);
  vec4 vcol   = u * v0_color  + v * v1_color  + w * v2_color;

  // Diffuse base (sRGB).
  vec3 baseRgb = mix(fill.rgb, vcol.rgb, has_vcols);
  float baseA  = mix(fill.a,   vcol.a,   has_vcols);

  // Texture × diffuse (sRGB multiplication when unlit; in linear when lit).
  vec4 texSample = texture(tex, uv);
  vec3 diffRgb = mix(baseRgb, texSample.rgb * baseRgb, has_texture);
  float diffA  = mix(baseA,   texSample.a   * baseA,   has_texture);

  vec3 outRgb;
  if (has_lighting > 0.5) {
    vec3 kd = sRgbToLin(diffRgb);
    vec3 ks = sRgbToLin(specular);
    vec3 ke = sRgbToLin(emissive);
    vec3 view = normalize(camera_pos - wpos);
    vec3 acc = ke;
    int n = int(num_lights);
    for (int i = 0; i < 8; i++) {
      if (i >= n) break;
      vec4 packed = lights[i * 3];
      float ltype   = packed.x;
      float intens  = packed.y;
      float range   = packed.z;
      vec3 lcolor   = sRgbToLin(lights[i * 3 + 1].rgb) * intens;
      vec3 lpd      = lights[i * 3 + 2].xyz;
      if (ltype < 0.5) {
        // ambient
        acc += lcolor * kd;
      } else if (ltype < 1.5) {
        // directional: lpd is direction *from* the light
        vec3 L = normalize(-lpd);
        vec3 H = normalize(L + view);
        float ndl = max(0.0, dot(norm, L));
        float ndh = max(0.0, dot(norm, H));
        acc += ndl * lcolor * kd
             + pow(ndh, shininess) * lcolor * ks;
      } else {
        // point: lpd is position
        vec3 diff = lpd - wpos;
        float d = length(diff);
        if (d < range) {
          float t = 1.0 - d / range;
          float att = t * t;
          vec3 L = diff / d;
          vec3 H = normalize(L + view);
          float ndl = max(0.0, dot(norm, L));
          float ndh = max(0.0, dot(norm, H));
          vec3 li = lcolor * att;
          acc += ndl * li * kd
               + pow(ndh, shininess) * li * ks;
        }
      }
    }
    outRgb = linToSrgb(clamp(acc, 0.0, 1.0));
  } else {
    outRgb = diffRgb;
  }

  fragColor = vec4(outRgb, clamp(diffA, 0.0, 1.0));
}
```

This is the canonical shader. A Flutter VM shipping the GPU path SHOULD
ship this exact source so that visual output matches across hosts;
differences would come only from GPU rasterizer precision, which is
already host-specific under v2's anti-aliasing concession.

**Output color space.** The shader writes the final per-fragment color
as sRGB straight alpha: `outRgb` is gamma-encoded by `linToSrgb()` when
lighting is enabled and passed through unchanged otherwise. Flutter's
Paint pipeline and Impeller do not apply any additional gamma or
color-space conversion between the FragmentShader's output and the
framebuffer write; the shader output is the final pixel value. This
matches `dao.postgraphics.md § Color Space` and § Blending, which pin
sRGB straight alpha as the v1 contract for producer-supplied and
on-screen colors.

#### Numerical Considerations

- The barycentric computation uses screen-space coordinates. For
  triangles where the screen-space area is small relative to floating-
  point precision, weights can drift; this is the same precision regime
  as the software rasterizer's `area-raw` check. The shader does not
  reject degenerate triangles — the software rasterizer already excluded
  them before issuing the draw.
- `normalize(...)` of an interpolated normal may overflow if the
  interpolated input is the zero vector (only possible with degenerate
  per-vertex normals). The producer is responsible per v4 for emitting
  non-degenerate normals.
- `pow(x, shininess)` for very small `x` and very large `shininess` may
  underflow. v4 already pins `:material/shininess > 0`; the GPU path
  inherits that bound.

### VM Dispatch

`exec-3d-mesh-op` gains one new dispatch tier above the v4 cases:

```text
cond
  textured? && unlit && no-vcols                     -> ImageShader path  (unchanged)
  textured? || lit? || has-vcols?                    -> shaded-gpu path   (was shaded software)
  unlit && no texture && no vcols                    -> flat path         (unchanged)
```

When the GPU path is selected but `fragment-program-load-state` is not
`:loaded`:

- `:not-started` → kick off `FragmentProgram.fromAsset` (sets state to
  `:loading`); render this frame's lit/shaded triangles via the v4
  software path
- `:loading` → render this frame via the v4 software path
- `:failed` → render via the v4 software path; emit a one-time warning
  via `dart:core/print`. Producers are not informed via the canonical
  signal stream because the failure is host-side, not frame-side.

Once `:loaded`, the dispatch flips permanently for the rest of the VM
lifetime. The fallback path is therefore only ever exercised during
shader-program warm-up (typically the first frame after widget mount).

### Triangle Submission Sequence

Per triangle, the GPU path runs:

1. **Software depth + clip loop.** Same as `rasterize-mesh-triangle-shaded`:
   walk pixel centers, compute barycentric weights, depth-test, depth-
   write, clip-test, accumulate covered pixels into a `Path` of 1×1 rects.
2. **Allocate a per-draw `FragmentShader`.**
   `program.fragmentShader()` returns a fresh instance bound to the
   program. The instance owns its uniform storage; reusing a single
   instance across draws within a frame is permitted but each `setFloat`
   call is cheap so a per-draw instance keeps the code simple.
3. **Set uniforms** in the documented order. Booleans become `0.0` or
   `1.0`. Light slots beyond `num_lights` are left at zero (the shader
   skips them by `break`-on-count).
4. **Bind the texture sampler.** When `has_texture == 0.0`, bind a
   shared 1×1 white image (cached once on VM init) so the sampler is
   always valid; when `has_texture == 1.0`, bind the op's `ui/Image`.
5. **Configure `Paint`.** `paint.shader = fragmentShader`. Filter
   quality, blend mode (source-over), and color-filter (none) are left
   at defaults — the shader's output is the final fragment color.
6. **`canvas.drawPath(path, paint)`.**

Per-frame cost for a mesh with `T` triangles and `P` covered pixels
becomes O(T) FragmentShader instantiations + O(T) draw calls + O(P)
software pixel walks for the depth buffer. The per-pixel cost during
rasterization is the same edge tests + depth check as the existing
fast textured path, with no Blinn-Phong evaluation in Dart.

### Implementation Plan

1. **Asset wiring.** Add `shaders/mesh.frag` to the package; declare it
   in `pubspec.yaml` under `flutter.shaders`. Initial source is the
   canonical kernel above.
2. **VM-side shader cache.**
   - `defonce ^:private fragment-program (atom {:state :not-started :program nil})`
   - `ensure-mesh-program!` — idempotent: kicks off async load on first
     call, returns the loaded program or `nil`.
3. **Texture sampler placeholder.** Build a 1×1 white `ui.Image` once
   on VM init for the `has_texture == 0.0` case.
4. **Uniform serializer.** Helper that takes a "shading context"
   (triangle screen positions, world positions, normals, UVs, vertex
   colors, material, camera, light list, flags) and applies it to a
   `FragmentShader` instance via the documented index layout. Bench-
   mark: this should be a tight, allocation-free routine.
5. **GPU rasterizer.** New `rasterize-mesh-triangle-shaded-gpu`. Reuses
   the existing software pixel-walk for depth/clip, then issues
   `drawPath` with the configured shader.
6. **Dispatch.** Update `exec-3d-mesh-op`:
   - If lighting / vcols / lit-textured: route to `*-shaded-gpu` when
     the program is loaded, else to the v4 software path.
   - Other paths (flat, unlit textured `:fill`-modulated, lit untextured
     fall back from the GPU path) unchanged.
7. **Test surface.**
   - `mesh-shading-path-test-hook [op lighting]` reports which path the
     dispatch chose (`:gpu` / `:software-shaded` / `:textured` / `:flat`)
     so regression tests can pin behavior.
   - `mesh-fragment-uniforms-test-hook [op lighting tri-context]` returns
     the uniform array the GPU path would upload, so tests can assert
     uniform packing without booting Flutter.
   - Existing `textured-mesh-shading-test-hook` continues to describe
     the *visual* contract (`:lighting-applied`, `:diffuse-rgb-source`,
     etc.) — those answers are the same on GPU and software paths.

### Failure Semantics

- The asset `shaders/mesh.frag` is missing or malformed → the VM logs a
  diagnostic and falls back to the v4 software path. This is a host
  configuration error, not a frame validation error; no canonical signal
  is emitted (the frame is still valid; only the *speed* of rendering
  differs).
- `num_lights` would exceed `MAX_LIGHTS = 8` → emit a
  `:dao.terminal/rejection` with `:reason :validation-failure` and a
  message naming the rejected light count, **only** when the VM is
  configured in strict mode. Default mode silently drops lights past
  the limit and continues. Strict mode is opted into via the
  `:shader/strict-lights true` option on `postgraphics-widget`.

The Flutter VM narrows v4 in two documented ways:

- `:target/push` and `:target/pop` are rejected with `:reason
  :unsupported-op` (see § Open Questions > Render targets and
  § Compatibility and Conformance).
- More than `MAX_LIGHTS = 8` active lights in a single frame are
  either rejected (strict mode) or silently truncated (default), per
  the `num_lights` bullet above.

No other producer-visible failure modes are added beyond v4; op
shapes, field shapes, and value ranges are otherwise identical.

### Compatibility and Conformance

- A Flutter VM shipping the GPU path SHALL produce visually identical
  output to a spec-correct v4 software VM for any frame that does not
  use the render-target ops, modulo (a) the existing anti-aliasing host
  concession, and (b) sub-pixel differences in GPU rasterization vs.
  the software Path-of-rects rasterizer.
- The Flutter VM rejects `:target/push` and `:target/pop` with
  `:reason :unsupported-op`. This is the one documented divergence from
  v4 (see § Open Questions > Render targets). Producers that need
  offscreen passes must use a different terminal or wait for that
  restriction to lift.
- A v4 frame that a v4 VM rejects (other than for render-target reasons)
  MUST also be rejected by a GPU-path Flutter VM with the same
  `:reason`.
- A GPU-path Flutter VM MAY emit additional implementation-specific
  diagnostics about shader compilation or load failures, but those MUST
  NOT travel through the canonical signal stream.
- The GLSL kernel above is normative for this path: a Flutter VM shipping
  the GPU path SHOULD ship the canonical kernel verbatim so that a
  conforming claim implies a single, agreed-upon shading implementation.
  Hosts that need to substitute the kernel (e.g., for a non-Impeller
  backend) MUST preserve the input / output contract documented in
  § Fragment Shader Contract.

### Accepted Defaults

- **`MAX_LIGHTS = 8`** — fits within Flutter's minimum guaranteed
  uniform budget while covering common cases. The number is an accepted
  default and may be revisited if a future spec adds a light-tile or
  deferred path.
- **`:shader/strict-lights` defaults to `false`** — frames with more
  than 8 lights render with the first 8 by emission order rather than
  failing. Strict mode is an opt-in tool for producers that want a
  validation signal instead of silent truncation.
- **Fallback during async program load** is automatic and silent. No
  producer-visible event marks the transition from software fallback to
  GPU rendering.
- **Per-draw `FragmentShader` instance allocation** is accepted. The
  alternative — one shared instance with uniform mutation between draws
  in the same frame — would require careful interleaving with Flutter's
  paint pipeline and is not worth the complexity for the demo case.
- **No vertex shader / no per-vertex varyings.** Per-vertex attributes
  travel as fragment-shader uniforms; barycentric interpolation happens
  inside the fragment kernel rather than via the GPU's hardware
  interpolators. This is the trade for staying within Flutter's
  `FragmentShader` API (which does not expose a vertex stage).

### Open Questions

- **Render targets.** v4's `:target/push` / `:target/pop` (and the
  associated target registry / `:depth` sampling path) are normative in
  v4 but currently rejected by this VM with `:reason :unsupported-op`.
  This is the one acknowledged gap in v4 conformance for the Flutter
  terminal. The plan to lift it is to back targets with Flutter's
  `PictureRecorder` → `Picture.toImage` flow: the software depth buffer
  can be allocated per target, the GPU shading path is target-agnostic,
  and the target registry can be a VM-local map keyed by `:target/id`.
  No bytecode or shader changes are required; the work is purely VM
  plumbing (target lifecycle, registry, depth-buffer pool, `:texture/
  source :target/id ...` resolution).
- **Mipmap quality.** v4's `:texture/mipmap` is honored via
  `Paint.filterQuality = medium` on the existing `ImageShader` path.
  The FragmentShader sampler uses Flutter's default filter for
  `texture()` calls; this remains a host concession unless future
  work plumbs `:texture/mipmap` to the sampler explicitly through a
  uniform-controlled `textureLod()`.
- **Custom shaders for producers.** This path does not expose a way for
  producers to supply their own shaders. A future bytecode version may
  add a `:shader/source` field on `:draw3d/mesh` (or a separate
  `:shader/use` state op) — that would be a producer-visible language
  extension and belongs in a separate proposal.
- **WebGPU / Skia parity.** The canonical kernel targets GLSL ES 3.10
  features that Flutter's shader toolchain compiles to SPIR-V for
  Impeller and GLSL ES 3.00 for the web Skia backend. Differences in
  precision qualifiers and `texture()` overloads between targets are
  the build tool's responsibility; this design assumes the toolchain
  hides them. A WebGPU-native rendering path is documented separately
  in `dao.postgraphics.webgpu.md`.
