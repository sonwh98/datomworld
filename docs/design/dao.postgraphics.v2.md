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

All v1 ops remain valid in v2 frame programs. 2D and 3D ops may coexist in the
same frame.

## Coordinate System

3D `dao.postgraphics` uses a right-handed coordinate system:

- `x` increases to the right
- `y` increases upward
- `z` points toward the viewer (out of screen)
- origin is `[0 0 0]`

Camera convention:

- view space is right-handed
- camera forward is along the **negative z-axis**
- camera up is along the **positive y-axis**

Projection convention:

- NDC (Normalized Device Coordinate) space is `[-1, 1]` for x and y
- NDC `z` is `[0, 1]` where 0 is the near plane and 1 is the far plane
- depth testing (when enabled) uses this `[0, 1]` range

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

The model transform stack (governed by `:transform/push` / `:transform/pop`) is
the same stack used for 2D. In v2 the stack holds 4x4 column-major matrices
rather than 3x3.

Draw op projection rules:

- **3D ops** (`:draw3d/*`) project vertices through `MVP = camera_matrix × stack_top`.
- **2D ops** (`:draw/*`) ignore the camera matrix. They are rendered into the
  2D orthographic overlay established by the viewport. They use the current
  stack top as an affine transform: `(x, y, 0, 1)` is transformed by the 4x4
  matrix, and the resulting `x, y` are used as the 2D position. Rotation and
  scaling are extracted from the 3x3 basis of the 4x4 matrix. If the 4x4 matrix
  contains non-planar components, the 2D op projection is ill-defined and the
  VM may skip the op.

3D draw ops combine the 4x4 stack top with the camera matrix to compute the
final vertex positions.

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

TRS composition order (when no `:matrix`): `T × R × S`.

## V2 Bytecode Vocabulary

New op kinds in v2:

- `:camera3d/set`
- `:state/depth-test`
- `:state/depth-write`
- `:draw3d/lines`
- `:draw3d/triangles`

### `:camera3d/set`

Establishes the 3D camera and projection state for the frame. Not part of the
model transform stack — this is projection × view only. A second `:camera3d/set`
in the same frame replaces the first. Must appear before any `:draw3d/` op.

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

Enables or disables depth testing for subsequent draw ops.

Required fields:

- `:op/kind`
- `:enabled` — boolean

### `:state/depth-write`

Enables or disables writing to the depth buffer for subsequent draw ops.

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
- `:stroke-width`

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
- `:normals` — vector of `[nx ny nz]` per vertex; stored in the frame, may be
  used by the VM for lighting when lighting state is configured

V2 3D rendering is unlit. Normals are accepted but do not affect output unless
a lighting model is defined in a later version.

Example:

```clojure
{:op/kind :draw3d/triangles
 :vertices [[-0.5 0.0 0.0] [0.5 0.0 0.0] [0.0 1.0 0.0]]
 :indices [[0 1 2]]
 :fill [0.3 0.7 0.4 1.0]}
```

## Depth and Paint Order

By default, 3D frame ops execute in order; later ops paint over earlier ones.
The producer is responsible for emitting `:draw3d/` ops in painter's order
(farthest first) when depth testing is disabled.

When `:state/depth-test` is enabled, the VM uses the depth buffer to determine
visibility based on the projected NDC `z` value `[0, 1]`. The producer may still
prefer to sort for performance (to reduce overdraw) or to handle transparency,
but the depth buffer handles visibility for opaque geometry including
intersecting triangles and cyclic overlaps.

An optional `:draw3d/depth` float may annotate any `:draw3d/` op for validation
or debug tooling. It has no effect on rendering order.

## Failure Semantics

Additional failure conditions beyond v1:

- a `:draw3d/` op appears before any `:camera3d/set` in the frame
- `:camera3d/set` specifies `:perspective` but `:camera3d/fov`, `:camera3d/near`,
  or `:camera3d/far` is missing
- `:camera3d/near` is not positive, or `:camera3d/far` ≤ `:camera3d/near`
- `:state/depth-test` or `:state/depth-write` is missing the `:enabled` field
- `:draw3d/lines` is missing `:vertices`, or a vertex is not a three-element vector
- `:draw3d/triangles` is missing `:vertices` or `:indices`
- the frame ends with a non-empty transform stack (v1 rule; applies to 4x4 stack)

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
- 2D ops are rendered in a screen-space overlay and ignore the 3D camera
