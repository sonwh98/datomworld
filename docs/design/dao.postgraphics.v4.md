# Design: `dao.postgraphics` v4 — Textures, Lighting, Render Targets

## Summary

V4 adds the three subsystems that take `dao.postgraphics` from a graphics
substrate to a usable real-time 3D renderer:

- **textures** on 3D geometry, with sampling state
- **lighting** with directional, point, and ambient lights using a Blinn-Phong
  reflection model
- **render targets** for offscreen passes (shadow maps, reflections,
  post-processing)

V4 conformance implies v3 conformance, which implies v2 and v1. In particular,
v4 inherits without modification:

- v1's Metadata Carriage contract: every v4 op (including `:light/*`,
  `:state/lighting-enable`, `:target/push`, `:target/pop`, `:draw3d/mesh`) may
  carry an optional `:op/meta` field that does not affect paint semantics but
  MUST be preserved by terminals participating in geometry reporting
- v1's Normative Protocol Contracts: the Canonical Precedence Formula, the
  Axis-Alignment Epsilon (generalized by v3's `EPSILON = 1.0e-6` policy), and
  the Canonical Signal Shapes for VM Reset, Frame Rejection, Protocol Error,
  and Frame Skipped — extended to cover v4-specific failure modes (e.g.,
  unresolvable `:texture/source`, target stack imbalance) under the same
  signal shapes with `:reason :validation-failure`
- v1's Normative Text Metrics for `:draw/text` (alphabetic baseline, `1.2 ×
  font-size` line-height, System Monospace metric-parity fallback)

V4 deliberately does not introduce a programmable shader pipeline. The
lighting equation is fixed-function. Producers needing custom shading must
target a future version.

## VM State (v4 additions)

In addition to the v1, v2, and v3 VM state, a v4 VM maintains:

- **lighting-enabled** — boolean; whether subsequent `:draw3d/mesh` ops
  use the lighting equation; default `false`
- **light list** — ordered list of active lights (ambient, directional, point);
  initially empty; modified by `:light/*` ops
- **render target stack** — LIFO stack of bound render targets; the bottom of
  the stack is the default framebuffer (viewport-sized, sRGB color, depth)
- **target registry** — map of `:target/id` keyword → render target; populated
  by `:target/push` and read by texture sampling through `:texture/source`

The light list and target registry are reset to empty at the start of each
frame program. Frame programs that need to recompute lighting or offscreen
data must emit the corresponding ops every frame.

## Textures

### Texture Sources

A texture source identifies image data the VM can sample. As with
`:image/source` in v1, a texture source may be:

- a backend texture handle
- a resource key (loaded via `dao.stream`)
- a URI or asset identifier
- a bitmap payload understood by the host VM
- a `:target/id` keyword referring to a render target produced earlier in the
  same frame

Portable frame programs should prefer resource keys, URIs, or `:target/id`
references.

### Sampling State

Texture sampling parameters are specified per-draw on `:draw3d/mesh`:

- `:texture/source` — required when sampling; one of the source forms above
- `:texture/wrap` — addressing mode for source coordinates outside `[0, 1]`:
  - `:clamp` (default) — clamp to edge texels
  - `:repeat` — repeat the texture
  - `:mirror` — repeat with alternating mirror
- `:texture/filter` — sampling filter:
  - `:linear` (default) — bilinear within a mip level, trilinear with mipmaps
  - `:nearest` — nearest texel, no interpolation
- `:texture/mipmap` — boolean; whether to use mipmaps when available;
  default `true`. The VM generates mipmaps lazily for static texture sources;
  `:target/id` sources have no mipmaps unless `:target/mipmap true` was set on
  the corresponding `:target/push`.

UV coordinates come from the `:uvs` field on `:draw3d/mesh`.
When `:texture/source` is present, `:uvs` is required and must have one entry
per vertex.

### `:draw3d/mesh`

V4 introduces `:draw3d/mesh` as a first-class draw op. This supersedes v2's
triangle-only mesh-lowering rule for V4-conforming producers; `:draw3d/triangles`
remains valid, but `:draw3d/mesh` is the canonical textured and lit geometry
surface in v4.

Like `:draw3d/triangles`, a mesh draw is immediate-mode geometry. The VM does
not retain mesh assets between frames; producers re-emit the draw each frame if
the geometry remains visible.

Required fields:

- `:op/kind`
- `:vertices` — vector of `[x y z]` points in local object space
- `:indices` — vector of `[i j k]` triples (one triangle per triple)

Optional fields:

- `:fill` — `[r g b a]` diffuse base color; default opaque white
- `:normals` — vector of `[nx ny nz]` local-space vertex normals; when present,
  its length must equal `count(:vertices)` exactly
- `:uvs` — vector of `[u v]` texture coordinates; when present, its length must
  equal `count(:vertices)` exactly
- `:colors` — vector of `[r g b a]` per-vertex diffuse colors; when present,
  its length must equal `count(:vertices)` exactly
- `:texture/source`
- `:texture/wrap`
- `:texture/filter`
- `:texture/mipmap`
- `:material/specular`
- `:material/shininess`
- `:material/emissive`

Per-vertex `:colors`, when present, replace uniform `:fill` as the diffuse base
input. If both are omitted, the diffuse base defaults to opaque white.

All other v2 3D pipeline rules still apply: vertices are transformed by
`MVP = camera_matrix × stack_top`, indices define triangles, clipping and
viewport mapping happen per v2/v3, and depth testing/writing use the active
v2 state ops.

### Texture Modulation

Texture sampling produces a color `tex.rgba` per fragment. This is combined
with the diffuse color `diff.rgba` (from `:fill` or per-vertex `:colors`,
under lighting if enabled, or directly otherwise) by **multiplication**:

```
fragment.rgb = tex.rgb × diff.rgb
fragment.a   = tex.a   × diff.a
```

When `:fill` is omitted, the default is opaque white `[1 1 1 1]`, so a textured
draw with no fill simply uses the texture color directly.

When lighting is enabled (see Lighting section), the texture modulates the
diffuse term only; specular and emissive are unaffected by the texture in v4.
A future version may add specular and emissive texture maps.

## Lighting

### Color Arity for Lights and Materials

V1's failure semantics require paint color tuples to be 4-channel `[r g b a]`.
V4 light and material color fields describe emission and reflectance, not
coverage, and are therefore **3-channel `[r g b]`** (sRGB-encoded), with no
alpha component:

- `:color` on `:light/ambient`, `:light/directional`, `:light/point`
- `:material/specular`
- `:material/emissive`

These fields are exempt from v1's arity-4 rule. Paint color fields on draw ops
(`:color`, `:fill`, `:stroke`, per-vertex `:colors`) remain 4-channel `[r g b a]`
in v4 as in v1. A 3-channel value on a paint color field, or a 4-channel value
on a light/material field, is invalid.

### Lighting Mode

Lighting is off by default. To enable it, emit:

```clojure
{:op/kind :state/lighting-enable :enabled true}
```

When enabled, subsequent `:draw3d/mesh` ops are shaded using the active
light list and the Blinn-Phong reflection equation defined below. The op
must include `:normals`; a mesh draw without `:normals` under
lighting-enabled is invalid.

`:draw3d/lines` is never affected by lighting; line rendering is always
unlit, using the line's `:color` directly.

### Light Ops

Lights are added to the active list by these ops:

#### `:light/ambient`

Adds an ambient light contribution. Multiple ambient lights sum.

Required: `:op/kind`, `:color` (`[r g b]`, sRGB)

```clojure
{:op/kind :light/ambient :color [0.05 0.05 0.08]}
```

#### `:light/directional`

Adds a directional light. Light direction is in **world space**.

Required: `:op/kind`, `:direction` (`[x y z]`, the direction the light is
*coming from*; will be normalized by the VM), `:color` (`[r g b]`)

Optional: `:intensity` (default `1.0`); the effective light color is
`color × intensity`.

```clojure
{:op/kind :light/directional
 :direction [0.5 1.0 0.3]
 :color [1.0 0.95 0.85]
 :intensity 1.0}
```

#### `:light/point`

Adds a point light at a world-space position with attenuation.

Required: `:op/kind`, `:position` (`[x y z]`), `:color` (`[r g b]`)

Optional:
- `:intensity` (default `1.0`)
- `:range` — falloff radius in world units; light contribution is zero at and
  beyond `:range`; default `100.0`. Attenuation between `0` and `:range`
  follows a quadratic law:
  ```
  d = distance(fragment_world_pos, light_position)
  if d >= range: contribution = 0
  else: attenuation = (1 - d/range)^2
  ```

```clojure
{:op/kind :light/point
 :position [0.0 5.0 0.0]
 :color [1.0 1.0 1.0]
 :intensity 4.0
 :range 20.0}
```

#### `:light/clear`

Removes all lights from the active list. Subsequent `:light/*` ops add to a
fresh list. No fields beyond `:op/kind`.

### Material Fields on `:draw3d/mesh`

When lighting is enabled, `:draw3d/mesh` accepts these additional
optional fields:

- `:material/specular` — specular reflection color `[r g b]`; default
  `[0 0 0]` (no specular)
- `:material/shininess` — Blinn-Phong exponent; default `32.0`; higher values
  produce sharper highlights
- `:material/emissive` — self-illumination color `[r g b]`; default `[0 0 0]`;
  not affected by lights or textures, added directly to the fragment color

The diffuse base color is `:fill` (or per-vertex `:colors`), modulated by the
texture if present, as defined under Textures.

### Lighting Equation

For each fragment, with fragment world-space position `P`, world-space
normal `N` (transformed by the inverse-transpose of the upper-left 3×3 of the
model matrix), camera position `C` (from `:camera3d/set`), and material
diffuse `K_d`, specular `K_s`, shininess `s`, emissive `K_e`:

```
V = normalize(C - P)               ; view direction

ambient_term = sum over ambient lights of (light.color × light.intensity) × K_d

per-directional-light contribution:
  L = -normalize(light.direction)
  H = normalize(L + V)
  diffuse  = max(0, dot(N, L)) × light.color × light.intensity × K_d
  specular = pow(max(0, dot(N, H)), s) × light.color × light.intensity × K_s
  total    = diffuse + specular

per-point-light contribution:
  L = normalize(light.position - P)
  d = length(light.position - P)
  if d >= light.range: skip
  attenuation = (1 - d/light.range)^2
  H = normalize(L + V)
  diffuse  = max(0, dot(N, L)) × light.color × light.intensity × K_d × attenuation
  specular = pow(max(0, dot(N, H)), s) × light.color × light.intensity × K_s × attenuation
  total    = diffuse + specular

fragment.rgb = ambient_term + sum of light contributions + K_e
fragment.a   = K_d.a   ; alpha is taken from the diffuse base, unaffected by lighting
```

The lighting equation is computed in **linear** color space: sRGB inputs
(`:fill`, `:material/*`, light `:color`) are converted to linear before
multiplication and the equation is evaluated in linear. This preserves correct
light addition. The conversion functions are the standard sRGB transfer:

```
linear = (sRGB <= 0.04045) ? sRGB / 12.92 : ((sRGB + 0.055) / 1.055)^2.4
sRGB   = (linear <= 0.0031308) ? linear * 12.92 : 1.055 * linear^(1/2.4) - 0.055
```

The framebuffer color space then determines what happens before source-over
blending:

- For sRGB-format targets (`:rgba8`, including the default framebuffer): the
  linear lighting result is converted back to sRGB and source-over blending
  proceeds in sRGB space (v1's rule, preserved).
- For linear HDR targets (`:rgba16f`, `:rgba32f`): the linear lighting result
  is written directly; source-over blending proceeds in linear space because
  the target itself is linear. Producers that want sRGB display output from
  an HDR pass tone-map and convert to sRGB explicitly via a full-screen-quad
  post-process targeting the default framebuffer.

Outside of lighting, v1's source-over blending operates in the framebuffer's
native color space: sRGB for `:rgba8` (v1's rule, unchanged), linear for HDR
formats introduced by v4.

### Normal Transformation

When a `:draw3d/mesh` op carries `:normals` and lighting is enabled, the
VM transforms each vertex normal `N_local` by:

```
M3 = upper-left 3×3 of the model matrix (current transform stack top)
N_world = normalize(transpose(inverse(M3)) × N_local)
```

This handles non-uniform scaling correctly. For uniform scale and rotation,
`transpose(inverse(M3))` reduces to a scaled rotation; the VM may use any
mathematically equivalent fast path.

Normals are interpolated linearly across triangles in screen space and
re-normalized per fragment.

## Render Targets

Render targets enable offscreen rendering. The producer pushes a target,
issues draw ops that go into the target instead of the screen, pops the
target, and then samples the target as a texture in subsequent draws.

Targets exist for the duration of a single frame program. The target
registry is cleared at the start of every frame. To use a target's contents
in another frame, the producer must redo the offscreen pass.

### `:target/push`

Pushes a new render target onto the render target stack. Subsequent draw
ops render into this target until a matching `:target/pop`.

Required fields:

- `:op/kind`
- `:target/id` — keyword identifying the target for later sampling
- `:target/width` — positive integer pixel width
- `:target/height` — positive integer pixel height

Optional fields:

- `:target/format` — one of:
  - `:rgba8` (default) — 8-bit per channel sRGB color + 32-bit depth
  - `:rgba16f` — 16-bit float per channel linear color (HDR) + 32-bit depth
  - `:rgba32f` — 32-bit float per channel linear color (HDR) + 32-bit depth
  - `:depth` — depth-only, no color (typical for shadow maps)
- `:target/mipmap` — boolean; whether the target's color attachment should
  have mipmaps generated when popped; default `false`
- `:target/clear` — color to clear the target to on push; default
  `[0 0 0 0]` (transparent black). Clears both color and depth (depth to
  `1.0` per v2). For `:depth` targets, only the depth buffer is cleared.

The pushed target gets a fresh depth buffer initialized to `1.0`. The
camera state, transform stack, clip stack, lighting state, and depth-test/write
state are inherited from the outer scope; producers typically push a new
camera (e.g., for shadow maps) immediately after pushing a target.

The viewport for the pushed target is the full target size
`(target_width × target_height)`. The Viewport Mapping formulas from v2 use
these dimensions while the target is active.

Example:

```clojure
{:op/kind :target/push
 :target/id :shadow-map
 :target/width 1024
 :target/height 1024
 :target/format :depth
 :target/clear [1.0 1.0 1.0 1.0]}
```

### `:target/pop`

Pops the current render target. Rendering returns to the previous target
(or the default framebuffer at the bottom of the stack).

Required fields: `:op/kind`.

The popped target is **not** destroyed; it remains in the target registry
under its `:target/id` for the rest of the frame and may be sampled as a
texture by referring to its id.

If `:target/mipmap true` was set on the push, mipmaps for the color
attachment are generated at pop time.

### Sampling Targets as Textures

A previously popped (or currently inactive) target is sampled by setting
its id keyword as:

- `:texture/source` on a `:draw3d/mesh`, or
- `:image/source` on a `:draw/image`

For example:

```clojure
{:op/kind :draw3d/mesh
 :vertices [...]
 :indices [...]
 :uvs [...]
 :texture/source :shadow-map}

{:op/kind :draw/image
 :image/source :shadow-map
 :rect [0 0 512 512]}
```

Sampling a target that is currently active (i.e., on top of the render-target
stack) is invalid — a frame cannot read and write the same target
simultaneously. The VM must reject such frames.

`:depth` targets are sampled as a single-channel red texture: the depth value
is returned in the `r` channel; `g`, `b`, and `a` are `0`, `0`, `1`
respectively. This allows shadow-map comparisons in producer-side logic
(though true hardware shadow comparison with PCF is not in v4 — that's a
future addition).

### Default Framebuffer

The default framebuffer (the bottom of the render target stack) corresponds
to the host viewport. It always has format `:rgba8`. Its width and height
are determined by the host (e.g., the Flutter widget size) and are passed to
the VM at frame execution start. Producers cannot resize it.

The default framebuffer is conceptually `:target/id :default`, but
`:target/pop` cannot pop below it and it cannot be referenced by `:target/id`
in a `:texture/source`.

### Rendering Pipeline With Render Targets

The 3D pipeline stages from v2 still apply, but each stage operates on the
currently bound target:

- viewport mapping uses the active target's dimensions
- depth buffer reads/writes go to the active target's depth attachment
- color writes go to the active target's color attachment
- depth-test, depth-write, lighting state are inherited and modifiable
  while a target is active
- camera state is inherited but can be replaced (and typically is, for
  shadow-map-style passes)

A typical shadow-map pattern:

```clojure
;; 1. Render scene depth from light's perspective into a shadow map
{:op/kind :target/push :target/id :shadow-map
 :target/width 1024 :target/height 1024 :target/format :depth}
{:op/kind :camera3d/set
 :camera3d/projection :orthographic
 :camera3d/left -10 :camera3d/right 10
 :camera3d/bottom -10 :camera3d/top 10
 :camera3d/near 0.1 :camera3d/far 50
 :camera3d/position [...light position...]
 :camera3d/rotation [...looking at scene origin...]}
{:op/kind :state/depth-test :enabled true}
{:op/kind :state/depth-write :enabled true}
;; ... draw scene geometry (cheap, depth-only) ...
{:op/kind :target/pop}

;; 2. Render scene normally, sampling the shadow map for shadow tests
{:op/kind :camera3d/set ...main camera...}
{:op/kind :state/lighting-enable :enabled true}
{:op/kind :light/directional ...same direction as shadow camera...}
{:op/kind :draw3d/mesh
 :vertices [...]
 :indices [...]
 :normals [...]
 :uvs [...]
 :texture/source :shadow-map
 :fill [0.7 0.7 0.7 1.0]
 :material/specular [0.3 0.3 0.3]
 :material/shininess 64}
```

(The shadow-comparison math itself, in v4, must be done by the producer
through standard texture sampling. Hardware-accelerated shadow comparison is
deferred.)

### Post-Processing Pattern

Post-processing (bloom, tone mapping, color grading) is implemented by:

1. Pushing an HDR render target (`:target/format :rgba16f`)
2. Drawing the scene normally into it
3. Popping the target
4. Drawing a full-screen quad with `:texture/source` (for `:draw3d/mesh`) or
   `:image/source` (for `:draw/image`) set to the target's id, into the
   default framebuffer

Producers that want HDR-aware composition should use `:rgba16f` or
`:rgba32f` targets; the `:rgba8` default clamps to `[0, 1]` and loses HDR
information.

## Failure Semantics (v4 additions)

V4 adds these failure conditions:

- `:state/lighting-enable` is missing the `:enabled` field, or `:enabled` is
  not a boolean
- `:draw3d/mesh` is missing `:vertices` or `:indices`
- `:draw3d/mesh` `:vertices` contains an entry that is not a three-element
  numeric vector
- `:draw3d/mesh` `:indices` contains an entry that is not a three-integer
  vector with each index in `[0, count(:vertices))`
- `:draw3d/mesh` is rendered with lighting enabled but is missing
  `:normals`
- `:draw3d/mesh` `:normals`, when present, has a length not equal to
  `count(:vertices)`, or a normal is not a three-element numeric vector
- `:draw3d/mesh` carries `:texture/source` but is missing `:uvs`, or
  `count(:uvs) ≠ count(:vertices)`, or a uv is not a two-element numeric vector
- `:draw3d/mesh` `:colors`, when present, has a length not equal to
  `count(:vertices)`, or a color is not a four-element numeric vector
- `:texture/wrap`, when present, is not one of `:clamp`, `:repeat`, `:mirror`
- `:texture/filter`, when present, is not one of `:linear`, `:nearest`
- `:texture/mipmap`, when present, is not a boolean
- `:material/specular`, when present, is not a three-element numeric vector
- `:material/shininess`, when present, is not a positive number
- `:material/emissive`, when present, is not a three-element numeric vector
- `:light/ambient`, `:light/directional`, or `:light/point` is missing
  `:color`, or `:color` is not a three-element numeric vector
- `:light/directional` is missing `:direction`, or `:direction` is not a
  three-element numeric vector, or its magnitude is zero
- `:light/point` is missing `:position`, or `:position` is not a
  three-element numeric vector
- `:light/point` `:range`, when present, is not positive
- `:target/push` is missing `:target/id`, `:target/width`, or `:target/height`,
  or `:target/id` is not a keyword, or width/height are not positive integers
- `:target/format`, when present, is not one of `:rgba8`, `:rgba16f`,
  `:rgba32f`, `:depth`
- `:target/pop` occurs without a matching `:target/push`
- the frame ends with a non-empty render target stack
- `:texture/source` references a `:target/id` that is currently bound (top of
  render target stack), or a `:target/id` that has not been pushed in this
  frame
- an `:image/source` that is a `:target/id` references a `:depth` target on
  a `:draw/image` op (only `:draw3d/mesh` may sample depth targets in v4)

## Accepted Defaults (v4 additions)

V4 fixes:

- lighting is off by default; enabled by `:state/lighting-enable`
- light list and target registry are reset at frame start
- the lighting equation is **Blinn-Phong** evaluated in linear color space;
  sRGB inputs convert to linear via the standard sRGB transfer, and the result
  is converted back to sRGB only when writing into an sRGB-format target
- light and material color fields (`:color` on `:light/*`, `:material/specular`,
  `:material/emissive`) are **3-channel `[r g b]`**, exempt from v1's
  arity-4 paint-color rule; paint color fields on draw ops remain 4-channel
- source-over blending operates in the framebuffer's native color space: sRGB
  for `:rgba8` targets (v1's rule, unchanged), linear for HDR targets
  (`:rgba16f`, `:rgba32f`)
- texture sampling default on `:draw3d/mesh`: linear filtering, clamp-to-edge
  wrapping, mipmaps enabled when available
- `:draw/image` continues to use v3 image sampling rules (bilinear,
  clamp-to-edge); the v4 `:texture/*` sampling state applies only to
  `:draw3d/mesh`
- texture color modulates the diffuse term by multiplication
- render target default format is `:rgba8` (sRGB color + depth); HDR formats
  available via `:rgba16f` and `:rgba32f`
- render targets are per-frame; lifetimes do not span frame boundaries
- the default framebuffer is `:rgba8` and cannot be resized by the producer
- `:draw3d/lines` are always unlit; lighting state has no effect on them
- specular and emissive are not affected by texture in v4

V4 explicitly does **not** specify:

- programmable shaders (no custom material code)
- physically-based rendering (PBR) materials; v4 is Blinn-Phong only
- specular maps, emissive maps, normal maps; only base-color textures
- hardware shadow-map comparison / PCF; producers do shadow tests in user code
- compute shaders / GPGPU
- skeletal animation / skinning (still CPU-side)
- HDR tone mapping ops (the producer implements tone mapping as a
  full-screen-quad post-process)
- environment maps / image-based lighting
