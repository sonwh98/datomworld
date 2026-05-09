# Design: `dao.postgraphics` v2 — 3D Extension

## Summary

This document extends `dao.postgraphics` v1 with an immediate mode 3D graphics
vocabulary.

V1 is 2D-only. V2 adds:

- a camera and projection op
- 3D draw primitives: lines and triangles
- an extended `:transform/push` that accepts 3D fields

The VM is one machine with one transform stack. The 3D extension adds camera
state and 3D draw ops; it does not introduce a second stack.

All v1 op kinds are supported in v2 frame programs with their v1 semantics
preserved; v2 extends `:clip/push-rect` to also apply to 3D draws (see Clip
Semantics below). 2D and 3D ops may coexist in the same frame.

## Coordinate System

3D `dao.postgraphics` uses a right-handed coordinate system:

- `x` increases to the right
- `y` increases upward
- `z` points toward the viewer (out of screen)
- origin is `[0 0 0]`

Camera and projection conventions:

- view space is right-handed; camera forward is along the **negative z-axis** in
  view space; camera up is along the **positive y-axis** in view space
- the view matrix is the inverse of the camera's world transform:
  `view = invert(T × R)`, where T is the camera translation and R is the camera
  rotation (Euler XYZ, same order as `:transform/push`)
- NDC x and y are in `[-1, 1]`; NDC z is in `[0, 1]` where `0` is the near
  plane and `1` is the far plane
- the viewport transform maps NDC to screen pixels (see the Viewport Mapping
  section for the exact formula and pixel-center convention)

The perspective projection matrix (column-major, applied to right-handed view
space, producing NDC z in `[0, 1]`) is:

```
f = 1 / tan(fov_radians / 2)   where fov_radians = fov_degrees * π / 180
a = aspect                       where aspect = viewport_width / viewport_height

column-major 16-element vector:
[ f/a, 0,  0,                    0,
  0,   f,  0,                    0,
  0,   0,  far/(near-far),      -1,
  0,   0,  near*far/(near-far),  0 ]
```

This maps view-space z = `-near` to NDC z = `0` and z = `-far` to NDC z = `1`.
The `-1` at index `[11]` produces the perspective divide and the handedness
change from right-handed view space to left-handed clip space. All conforming VM
implementations must use this exact matrix for perspective projection.

The orthographic projection matrix (column-major, applied to right-handed view
space, producing NDC z in `[0, 1]`) is:

```
column-major 16-element vector:
[ 2/(right-left),              0,                            0,                   0,
  0,                           2/(top-bottom),               0,                   0,
  0,                           0,                            1/(near-far),        0,
  -(right+left)/(right-left), -(top+bottom)/(top-bottom),    near/(near-far),     1 ]
```

This maps view-space `(left, bottom, -near)` to NDC `(-1, -1, 0)` and view-space
`(right, top, -far)` to NDC `(1, 1, 1)`. The matrix preserves `w = 1`, so no
perspective divide is performed. All conforming VM implementations must use this
exact matrix for orthographic projection.

4x4 matrices are 16-element vectors in column-major order (first 4 elements are
the first column).

The VM is responsible for the final viewport transform, including the y-flip for
backends with y-down screen coordinates.

## VM State

In addition to the v1 transform and clip stacks, the VM maintains one additional
piece of state during frame execution:

- **camera matrix** — the combined projection × view matrix, initially nil; set
  by `:camera3d/set`
- **depth-test** — boolean; whether to perform depth testing; default `false`
- **depth-write** — boolean; whether to write to the depth buffer; default `false`
- **depth buffer** — per-pixel depth values, one per frame; initialized to `1.0`
  (far plane) at the start of every frame program; `:frame/clear` also resets
  the depth buffer to `1.0` regardless of the `:color` field; `:camera3d/set`
  also resets the depth buffer to `1.0` (depth values from a previous projection
  are not comparable under a new one)

The model transform stack (governed by `:transform/push` / `:transform/pop`) is
the same stack used for 2D. In v2 the stack holds 4x4 column-major matrices
rather than 3x3.

Draw op projection rules:

- **3D ops** (`:draw3d/*`) project vertices through `MVP = camera_matrix × stack_top`.
- **2D ops** (`:draw/*`) remain in the 2D Cartesian space defined in v1. They ignore
  the 3D camera matrix. They are transformed by the 2D affine part of the
  current 4x4 stack top and then mapped to the viewport. Specifically:
  - Input `(x, y)` is treated as `(x, y, 0, 1)` and multiplied by the 4x4
    stack top.
  - The resulting `x', y'` are treated as 2D Cartesian coordinates in the
    v1 sense, which the VM then maps to screen pixels via the backend-specific
    viewport transform (including y-flip).
  - The z components of the stack top have no effect on 2D op rendering.

2D draw ops are valid only when the current stack top is a pure 2D affine
embedded in 4x4. The VM checks this by matrix value, not by how the matrix was
built. A matrix M (16-element column-major vector, `M[j*4+i]` = column j, row i)
is a valid 2D affine if all of the following hold:

- `M[2]`, `M[6]`, `M[14]` are `0` — transformed z is zero: the x, y, and
  translation columns do not contribute to z
- `M[10]` is `1` — z-scale is identity
- `M[3]`, `M[7]` are `0` and `M[15]` is `1` — transformed w is 1: no
  perspective terms; the w row does not vary with x or y input

Equivalently: for any input `(x, y, 0, 1)`, the transformed result is
`(x', y', 0, 1)` — z stays zero and w stays one. A 16-element matrix that
satisfies all six conditions is valid for 2D draws even if it was constructed
from 3D fields. A matrix that fails any condition is invalid regardless of how
it was built. The VM must reject the frame if a 2D draw op is encountered with
a non-conforming stack top.

## Clip Semantics

`:clip/push-rect` rects are in screen-space (viewport pixel) coordinates, as
defined in v1's Coordinate System section. V2 extends this to 3D draw ops:

- For 2D draw ops, the clip rect is applied after the 2D transform and viewport
  mapping (unchanged from v1).
- For 3D draw ops, the clip rect is applied after MVP projection, perspective
  divide, and viewport mapping. Fragments outside the active clip rect are
  discarded.

The clip stack is LIFO and governs both 2D and 3D ops. A clip rect pushed before
a 3D draw op clips that op's projected screen-space output exactly as it would
clip a 2D op.

## Extended `:transform/push`

V2 extends `:transform/push` to accept 3D fields. The stack now holds 4x4
column-major matrices throughout.

Fields may be supplied in 2D or 3D form; the VM infers the form from the field
shape and promotes 2D inputs to 4x4.

**`:translate`**, when present:

- 2D form: `[x y]` — promotes to 4x4 with z=0
- 3D form: `[x y z]`

**`:rotate`**, when present:

- 2D form: a scalar — counter-clockwise rotation in radians around the z-axis
- 3D form: `[rx ry rz]` — Euler XYZ intrinsic rotation in radians (`Rz × Ry × Rx`)

**`:scale`**, when present:

- 2D form: `[sx sy]` — promotes to 4x4 with sz=1
- 3D form: `[sx sy sz]`

**`:matrix`**, when present:

- 9-element form: a 3x3 affine transform matrix in row-major order, promoted to 4x4
- 16-element form: a 4x4 transform matrix in column-major order; used directly

If `:matrix` is present, it is used directly and `:translate`, `:rotate`, and
`:scale` are ignored. Otherwise the VM composes the provided
translate/rotate/scale fields onto the current transform in this fixed order:
- scale
- then rotate
- then translate

TRS composition order (when no `:matrix`): `T × R × S`.

## V2 Bytecode Vocabulary

New op kinds in v2:

- `:camera3d/set`
- `:state/depth-test`
- `:state/depth-write`
- `:draw3d/lines`
- `:draw3d/triangles`

### `:camera3d/set`

Establishes the 3D camera and projection state. Not part of the model transform
stack — this is projection × view only.

A `:camera3d/set` op may appear anywhere in the frame program. Each `:draw3d/*`
op uses the camera state established by the most recent prior `:camera3d/set`.
A `:draw3d/*` op that has no prior `:camera3d/set` in the frame is invalid.

A subsequent `:camera3d/set` replaces the previous camera state and resets the
depth buffer to `1.0` (depth values from the previous projection are not
comparable under a new one). All `:draw3d/*` ops, regardless of which
`:camera3d/set` precedes them, render to the full viewport — there is no
sub-viewport mechanism in v2. A clip rect may crop the rendered region, but
NDC always maps to the full viewport rectangle. True multi-view rendering with
sub-viewports (e.g., picture-in-picture insets at independent screen regions
without cropping artifacts) requires a viewport op that v2 does not define.

Required fields:

- `:op/kind`
- `:camera3d/projection` — one of `:perspective` or `:orthographic`

For `:perspective`:

- `:camera3d/fov` — vertical field of view in degrees
- `:camera3d/near` — near plane distance (positive number)
- `:camera3d/far` — far plane distance (positive number, greater than near)
- `:camera3d/aspect` — optional width/height ratio; if absent the VM uses the
  actual viewport ratio

For `:orthographic`:

- `:camera3d/left`, `:camera3d/right`, `:camera3d/bottom`, `:camera3d/top`
- `:camera3d/near`, `:camera3d/far`

Camera view fields (both projection types), two alternative forms:

Decomposed form:

- `:camera3d/position` — `[x y z]` world-space eye position; default `[0 0 0]`
- `:camera3d/rotation` — `[rx ry rz]` Euler XYZ angles in radians; default `[0 0 0]`

Matrix form:

- `:camera3d/view-matrix` — 16-element column-major 4x4 view matrix; if present,
  overrides position and rotation

The VM builds the camera matrix as `projection × view`. The view matrix is the
inverse of the camera's world transform.

Example:

```clojure
{:op/kind :camera3d/set
 :camera3d/projection :perspective
 :camera3d/fov 55.0
 :camera3d/near 0.1
 :camera3d/far 200.0
 :camera3d/position [0.0 16.0 34.0]
 :camera3d/rotation [-0.44 0.0 0.0]}
```

### `:state/depth-test`

Enables or disables depth testing for subsequent `:draw3d/*` ops. Has no effect
on 2D ops (`:draw/*`). 2D ops never participate in depth testing: they neither
read nor write the depth buffer, and they paint into the color buffer at their
position in the op stream. A 2D op draws over whatever color buffer contents
exist at that point (3D or 2D); a later 3D op can in turn draw over that 2D
output, subject to its own depth state.

Required fields:

- `:op/kind`
- `:enabled` — boolean

### `:state/depth-write`

Enables or disables writing to the depth buffer for subsequent `:draw3d/*` ops.
Has no effect on 2D ops; 2D ops never write to the depth buffer.

Required fields:

- `:op/kind`
- `:enabled` — boolean

### `:draw3d/lines`

Draws arbitrary 3D line geometry in local object space.

Required fields:

- `:op/kind`
- `:vertices` — vector of `[x y z]` points in local space

Optional fields:

- `:edges` — vector of `[i j]` index pairs specifying which vertices form each
  segment; if absent, vertices form a sequential open polyline
- `:color`
- `:stroke-width` — line width in **screen pixels**, applied after projection
  and viewport mapping; default `1.0`. The width does not vary with distance
  from the camera or with the current transform; a 1.0 stroke is one pixel wide
  on screen regardless of view-space depth. The same convention applies to v1
  2D `:stroke-width` fields.

  **Per-fragment depth.** A widened line is rasterized as the swept rectangle
  perpendicular to the centerline in screen space. Each fragment's depth is the
  NDC z value interpolated linearly between the two endpoints along the
  centerline at the fragment's projected position; fragments offset
  perpendicular from the centerline share the same depth as the corresponding
  centerline point. Equivalently: depth varies along the length of the line and
  is constant across its width. This makes the line's depth contribution
  deterministic and matches the natural extension of zero-width line depth.

The VM projects each vertex through `MVP = camera_matrix × stack_top` and draws
the resulting line segments.

Example:

```clojure
{:op/kind :draw3d/lines
 :vertices [[-1.0 0.0 0.0] [1.0 0.0 0.0] [0.0 1.0 0.0]]
 :edges [[0 1] [1 2] [2 0]]
 :color [0.8 0.8 0.0 1.0]}
```

### `:draw3d/triangles`

Draws filled triangles in local object space.

This is the primitive through which higher-level geometry — including meshes —
is rendered. A mesh system sitting above `dao.postgraphics` holds mesh assets in
its own memory (loaded via `dao.stream`) and lowers them into `:draw3d/triangles`
ops each frame. The VM renders the triangles and holds no memory of them between
frames. The producer re-emits the same op the next frame if the geometry is
still visible. `dao.postgraphics` never sees the concept of a mesh; it only sees
triangles.

Required fields:

- `:op/kind`
- `:vertices` — vector of `[x y z]` points in local space
- `:indices` — vector of `[i j k]` triples (one triangle per triple)

Optional fields:

- `:fill` — `[r g b a]` face color; default white
- `:normals` — per-vertex normals in local object space (the same space as
  `:vertices`); the count must equal `count(:vertices)` exactly (one normal per
  vertex); each entry is `[nx ny nz]`; normals should be unit vectors but the VM
  does not normalize them; transformed by the inverse-transpose of the upper-left
  3×3 of the model matrix when a lighting model is active

V2 3D rendering is unlit. Normals are accepted and validated for shape and
cardinality but do not affect output unless a lighting model is defined in a
later version.

Painter's algorithm (op order) is sufficient only for non-intersecting geometry
where a global depth sort is always valid. For geometry that intersects or where
cyclic overlap is possible, enable depth testing with `:state/depth-test` before
the draw ops and `:state/depth-write` if the geometry should write to the depth
buffer.

Example:

```clojure
{:op/kind :draw3d/triangles
 :vertices [[-0.5 0.0 0.0] [0.5 0.0 0.0] [0.0 1.0 0.0]]
 :indices [[0 1 2]]
 :fill [0.3 0.7 0.4 1.0]}
```

## 3D Pipeline Stages

For each `:draw3d/*` op, the VM processes geometry through these stages in order:

1. **Vertex transform.** Each input vertex `(x, y, z, 1)` in local object space
   is multiplied by `MVP = camera_matrix × stack_top` to produce a clip-space
   vertex `(x_c, y_c, z_c, w_c)`.

2. **Near-plane clipping (before perspective divide).** Primitives are clipped
   against the near plane in clip space. A vertex is in front of the near plane
   when `w_c > 0` and `z_c ≥ 0` (equivalently, view-space `z ≤ -near`).
   - For lines: if both endpoints are in front, keep the line; if both are
     behind, drop it; if one is in front and one is behind, replace the behind
     endpoint with the line's intersection with `z_c = 0`.
   - For triangles: clip against `z_c = 0`. A triangle with one vertex in front
     and two behind becomes one smaller triangle; with two in front and one
     behind, becomes two triangles; with all behind, is dropped; with all in
     front, is unchanged.
   - This handles behind-camera geometry deterministically: vertices with
     `w_c ≤ 0` are always clipped.

3. **Perspective divide.** Each surviving clip-space vertex is divided by `w_c`
   to produce an NDC vertex `(x_c/w_c, y_c/w_c, z_c/w_c)`.

4. **Viewport mapping.** NDC is mapped to screen-space pixels (see Viewport
   Mapping below). Endpoints and triangle vertices outside the lateral or far
   bounds are mapped without lateral clipping — their projected screen-space
   positions may fall outside the viewport rectangle. The screen-space geometry
   of a line is exactly the swept rectangle between its two projected endpoints
   (after near-plane clipping in stage 2). The screen-space geometry of a
   triangle is exactly the triangle formed by its three projected vertices.

5. **Lateral and far clipping (per fragment).** After the screen-space
   geometry is determined, fragments outside the NDC bounds
   `[-1, 1] × [-1, 1] × [0, 1]` are discarded. This handles the left, right,
   top, bottom, and far frustum planes uniformly for both lines and triangles.
   The active clip rect is applied at the same stage, in screen-space pixel
   coordinates.

6. **Depth test.** Per-fragment, depth testing (if enabled) is applied using
   the rule defined in Depth and Paint Order.

7. **Rasterization.** Surviving fragments are written to the framebuffer using
   the op's color or fill, and to the depth buffer if `depth-write` is enabled.

For `:draw/*` (2D) ops, only stages 4–7 apply, with the 2D Cartesian
transform replacing stages 1–3, and stage 6 (depth test) omitted entirely
(2D ops do not participate in depth testing).

**Execution order between 2D and 3D ops.** Ops execute in strict frame-program
order. There is no implicit reordering of 2D ops to a final overlay pass: a 2D
op writes to the color buffer at its position in the stream. A subsequent 3D
op can then paint over that 2D output, subject to its own depth state, since
the 2D op did not write to the depth buffer. If a producer wants 2D content to
appear on top of all 3D content, it must emit the 2D ops after all 3D ops in
the frame program.

## Viewport Mapping

NDC is mapped to screen-space pixels using the actual viewport dimensions
(`width × height`):

```
screen_x = (ndc_x + 1) × 0.5 × width
screen_y = (1 - ndc_y) × 0.5 × height   ; for y-down backends (e.g. Flutter)
screen_y = (ndc_y + 1) × 0.5 × height   ; for y-up backends
```

Pixel-center convention: pixel `(i, j)` is centered at `(i + 0.5, j + 0.5)` in
this screen-space coordinate system. A fragment at exact integer screen
coordinates lies on the corner between four pixels.

Clip rects use the same screen-space pixel coordinates: a clip rect
`[x y w h]` includes pixels whose centers satisfy `x ≤ cx < x + w` and
`y ≤ cy < y + h`.

## Rasterization Fill Rule

Triangle and line rasterization uses the **top-left fill rule** to determine
which pixel centers belong to a primitive at shared edges. This ensures
adjacent primitives neither leave cracks nor double-cover boundary pixels.

For a triangle, a pixel center is covered when it is:

- strictly inside the triangle, **or**
- exactly on a **top edge** — a horizontal edge whose interior lies below
  the edge (i.e. the triangle is below this edge), **or**
- exactly on a **left edge** — a non-horizontal edge whose interior lies to
  the right (i.e. the triangle is to the right of this edge).

Pixel centers exactly on a right edge or a bottom edge are **not** covered;
those pixels belong to the adjacent triangle that owns them as a top or left
edge. Two adjacent triangles sharing an edge therefore cover each interior
boundary pixel exactly once.

For a line of `:stroke-width` ≥ 1, the swept rectangle along the line
endpoints is rasterized using the same top-left rule. For a line of
`:stroke-width` < 1, the VM rasterizes a one-pixel-wide line and modulates the
fragment alpha by the requested width.

## Triangle Face Semantics

V2 triangles are **double-sided by default**: both the front face and back face
are rendered with the same color. There is no backface culling and no winding-
order convention in v2. Producers may emit triangles with any winding order
without affecting visibility.

A future version may add a `:state/cull-face` op and a winding-order convention
when materials and lighting are introduced. Until then, all triangles are
visible from both sides.

## Depth and Paint Order

3D frame ops execute in order. The visibility and depth-buffer-update behavior
of each `:draw3d/*` op is determined by the four combinations of `depth-test`
and `depth-write` state:

| `depth-test` | `depth-write` | Behavior |
|---|---|---|
| `false` | `false` | No depth interaction. Fragment passes; depth buffer unchanged. Equivalent to painter's order. |
| `false` | `true`  | Unconditional write. Fragment passes; depth buffer is updated with the fragment's NDC z. |
| `true`  | `false` | Test only. Fragment passes if its depth ≤ depth buffer; depth buffer unchanged. |
| `true`  | `true`  | Standard depth test. Fragment passes if its depth ≤ depth buffer; on pass, the depth buffer is updated with the fragment's depth. |

The depth comparison function is `less-or-equal`: a fragment passes the test if
its NDC z value is ≤ the current depth buffer value at that pixel. NDC z is in
`[0, 1]` per the projection convention. Fragments that fail are discarded.

When depth testing is disabled, the producer is responsible for emitting
`:draw3d/` ops in painter's order (farthest first). When depth testing is
enabled, the depth buffer handles visibility for opaque geometry including
intersecting triangles and cyclic overlaps; the producer may still sort for
performance (overdraw reduction) or to handle transparency.

`less-or-equal` is used rather than `less` so that re-drawing the same geometry
at exactly the same depth (e.g. a line overlay drawn on top of a surface mesh)
is not occluded by the previous draw. It does not eliminate z-fighting between
coplanar surfaces — fragments at nearly-equal depths can still produce flicker
under floating-point precision and rasterization.

An optional `:draw3d/depth` float may annotate any `:draw3d/` op for validation
or debug tooling. It has no effect on rendering order.

## Failure Semantics

Additional failure conditions beyond v1:

- a `:draw3d/` op appears before any `:camera3d/set` in the frame
- `:camera3d/set` specifies `:perspective` but `:camera3d/fov`, `:camera3d/near`,
  or `:camera3d/far` is missing
- `:camera3d/set` specifies `:orthographic` but any of `:camera3d/left`,
  `:camera3d/right`, `:camera3d/bottom`, `:camera3d/top`, `:camera3d/near`, or
  `:camera3d/far` is missing
- `:camera3d/near` is not positive, or `:camera3d/far` ≤ `:camera3d/near`
- `:camera3d/right` ≤ `:camera3d/left`, or `:camera3d/top` ≤ `:camera3d/bottom`
- `:camera3d/fov` is not in the open interval `(0, 180)` degrees
- `:camera3d/aspect`, when present, is not a positive number
- `:camera3d/position`, when present, is not a three-element numeric vector
- `:camera3d/rotation`, when present, is not a three-element numeric vector
- `:camera3d/view-matrix`, when present, is not a 16-element numeric vector
- `:state/depth-test` or `:state/depth-write` is missing the `:enabled` field,
  or `:enabled` is not a boolean (`true` or `false`)
- `:draw3d/lines` is missing `:vertices`, or a vertex is not a three-element
  numeric vector
- `:draw3d/lines` `:edges`, when present, contains an entry that is not a
  two-integer pair, or a pair contains an integer outside `[0, count(:vertices)−1]`
- `:draw3d/triangles` is missing `:vertices` or `:indices`
- `:draw3d/triangles` `:vertices` contains an entry that is not a three-element
  numeric vector
- `:draw3d/triangles` `:indices` contains an entry that is not a three-integer
  triple, or a triple contains an integer outside `[0, count(:vertices)−1]`
- `:draw3d/triangles` `:normals`, when present, has a length not equal to
  `count(:vertices)`, or contains an entry that is not a three-element numeric
  vector
- a 2D draw op (`:draw/*`) is issued when the current stack top fails the 2D
  affine check: `M[2]`, `M[6]`, `M[14]` not all zero; or `M[10]` not one; or
  `M[3]`, `M[7]` not zero; or `M[15]` not one

## Example Frame Program

The producer is responsible for generating geometry. A wireframe box is eight
vertices and twelve edges. A wireframe sphere is pre-computed latitude and
longitude circles passed as polylines.

```clojure
(def box-vertices
  [[-0.5 -0.5 -0.5] [ 0.5 -0.5 -0.5] [ 0.5  0.5 -0.5] [-0.5  0.5 -0.5]
   [-0.5 -0.5  0.5] [ 0.5 -0.5  0.5] [ 0.5  0.5  0.5] [-0.5  0.5  0.5]])

(def box-edges
  [[0 1] [1 2] [2 3] [3 0]
   [4 5] [5 6] [6 7] [7 4]
   [0 4] [1 5] [2 6] [3 7]])
```

```clojure
[{:op/kind :frame/clear
  :color [0.05 0.05 0.08 1.0]}

 {:op/kind :camera3d/set
  :camera3d/projection :perspective
  :camera3d/fov 55.0
  :camera3d/near 0.1
  :camera3d/far 200.0
  :camera3d/position [0.0 16.0 34.0]
  :camera3d/rotation [-0.44 0.0 0.0]}

 {:op/kind :state/depth-test :enabled true}
 {:op/kind :state/depth-write :enabled true}

 ;; sun — wireframe sphere (producer pre-computes circle vertices)
 {:op/kind :transform/push
  :scale [3.0 3.0 3.0]}
 {:op/kind :draw3d/lines
  :vertices <sphere-equator-vertices>
  :color [1.0 0.75 0.1 1.0]}
 {:op/kind :transform/pop}

 ;; earth with nested moon
 {:op/kind :transform/push
  :translate [11.0 0.0 0.0]
  :scale [0.7 0.7 0.7]}
 {:op/kind :draw3d/lines
  :vertices box-vertices
  :edges box-edges
  :color [0.2 0.5 0.9 1.0]}
 {:op/kind :transform/push
  :translate [1.5 0.0 0.0]
  :scale [0.25 0.25 0.25]}
 {:op/kind :draw3d/lines
  :vertices box-vertices
  :edges box-edges
  :color [0.75 0.75 0.78 1.0]}
 {:op/kind :transform/pop}
 {:op/kind :transform/pop}

 ;; 2D HUD — same stack, same push/pop discipline
 {:op/kind :transform/push
  :translate [8.0 8.0]}
 {:op/kind :draw/text
  :text "dao.postgraphics"
  :position [0 0]
  :color [0.6 0.6 0.7 1.0]
  :font-size 11.0}
 {:op/kind :transform/pop}]
```

## Accepted Defaults

These choices are fixed for v2:

- all v1 accepted defaults hold
- the VM has one transform stack; 2D and 3D ops share it
- the transform stack holds 4x4 column-major matrices
- the camera matrix is separate VM state, not part of the model transform stack
- 3D coordinate system is right-handed, y-up, z toward the viewer
- 3D rendering is immediate mode; no retained scene state in the VM
- depth testing is optional; default is painter's algorithm
- 2D ops remain in 2D Cartesian space, ignore the 3D camera, and do not
  participate in depth testing; 2D and 3D ops are not reordered — they execute
  in strict frame-program order
