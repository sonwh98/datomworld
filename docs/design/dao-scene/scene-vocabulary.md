# Design: Scene Vocabulary Family

## Summary

This document defines the layered family of scene vocabularies for
**retained-graphics** consumers — systems that want a stable, queryable tree
of scene values between authoring and rendering.

The intended architecture is:

```text
retained authoring
-> scene.core
-> dao.scene / scene.world / scene.xr
-> graphics bytecode
-> terminal renderer
```

The key design rule is:

- `scene.core` defines how scene values compose
- domain vocabularies define what kinds of things exist
- graphics bytecode defines how those things are realized by a backend

This avoids overloading one vocabulary with every semantic concern from
productivity UI through immersive 3D.

**`mr-clean` is not a consumer of this vocabulary family.** `mr-clean` is the
Reagent-inspired authoring layer for paint-only UI; it compiles directly to
`dao.postgraphics` frame programs and skips the scene stage entirely. See
[mr-clean.md](mr-clean.md). The scene vocabulary family is reserved for
future authoring layers or tooling that need retained semantics (hit-testing,
focus traversal, accessibility tree, multi-backend retargeting, animation
interpolation).

## Layering

### `scene.core`

`scene.core` is the algebraic substrate.

It defines:

- fragments
- scenes
- rooted composition
- common structural fields
- transforms
- style shell
- generic container, group, and viewport structure

It does not attempt to be the full semantic vocabulary for either UI or world
simulation.

See [scene-algebra.md](scene-algebra.md).

### `dao.scene`

`dao.scene` extends `scene.core` for productivity apps and general application
UI.

It should define semantics for:

- text blocks and inline text
- text input and editing
- scrolling
- clipping
- focusable nodes
- selection
- tab order
- lists and tables
- buttons and menus
- overlays and popups
- accessibility roles and labels

`dao.scene` answers:

- how does application UI behave semantically before terminal rendering?

See [dao.scene.md](dao.scene.md).

See [dao.postgraphics.md](../dao.postgraphics.md) for the terminal bytecode
contract that `dao.scene` lowers into.

### `scene.world`

`scene.world` extends `scene.core` for 3D and game-world semantics.

It should define semantics for:

- cameras
- lights
- meshes
- materials
- skeletal animation
- collision volumes
- physics bodies
- navigation markers
- audio emitters
- instancing and spatial grouping

`scene.world` answers:

- how does a world compose semantically before backend-specific rendering?

### `scene.xr`

`scene.xr` extends `scene.world` and, where needed, `dao.scene` for immersive
XR semantics.

It should define semantics for:

- reference spaces
- tracked head pose anchors
- controllers and hands
- interaction rays
- diegetic UI anchors
- haptic targets
- stereo or eye-specific rendering anchors

`scene.xr` answers:

- what extra semantics exist in immersive interaction that are not captured by
  generic world or UI structure?

## Why This Split Exists

One scene vocabulary is not a realistic final target for:

- productivity UI
- 2D application graphics
- 3D world simulation
- VR and XR interaction

Those domains share compositional structure, but they do not share one complete
semantic node catalog.

So the correct split is:

- one shared composition algebra
- multiple domain vocabularies on top of it

## Authoring Boundary

A **retained** authoring layer should compile into scene fragments and scene
values, not directly into terminal graphics bytecode. The Reagent-inspired
authoring layer for `mr-clean` is **not** retained — it compiles straight to
`dao.postgraphics` ops. A different, future authoring layer (or a tooling
system that wants to inspect, animate, or retarget a stable tree) is the
intended consumer of this boundary.

A retained authoring layer may use:

- Hiccup surface syntax
- functions and closures as components
- reactive atoms

But once retained authoring evaluation completes, the result should live in
`scene.core` plus one or more domain extensions.

## Lowering Boundary

Domain scene values lower into terminal graphics bytecode.

Examples:

- `dao.scene -> ui graphics bytecode`
- `scene.world -> world graphics bytecode`
- `scene.xr -> xr graphics bytecode`

Hybrid applications may use mixed lowering paths.

The terminal renderer should consume graphics bytecode, not scene values.

## V1 Scope

V1 should focus on:

- `scene.core`
- `dao.scene` as the first concrete domain layer

Recommended order:

1. stabilize `scene.core`
2. define `dao.scene` as the first domain extension for 2D apps and games
3. define the UI graphics bytecode for `dao.scene`
4. keep `scene.world` and `scene.xr` as later extensions

## Design Rules

- do not put domain-specific semantics into `scene.core` unless they are truly
  universal
- do not collapse domain semantics directly into graphics bytecode as the primary
  stable contract
- do not make the terminal renderer interpret scene semantics
- keep `scene.core` algebraic and compositional
- keep domain layers explicit and separately documented

## Next Documents

The intended document split is:

- [scene-algebra.md](scene-algebra.md)
  - fragment algebra and common structure
- [dao.scene.md](dao.scene.md)
  - productivity and app UI semantics
- [dao.postgraphics.md](../dao.postgraphics.md)
  - terminal rendering bytecode contract
- future `scene-world.md`
  - 3D world semantics
- future `scene-xr.md`
  - immersive interaction semantics

## Accepted Defaults

These choices are fixed unless replaced by a more specific design:

- `scene.core` is the shared algebraic substrate
- domain semantics belong in `dao.scene`, `scene.world`, or `scene.xr`
- graphics bytecode is derived from domain scene values
- the terminal renderer consumes graphics bytecode only
- one shared composition algebra is preferred over one giant universal scene
  schema
