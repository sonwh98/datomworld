# Design: `mr-clean`

## Summary

`mr-clean` is the Reagent-inspired authoring layer that compiles component
models into `dao.postgraphics` frame programs.

It is not a renderer. It is not a stream runtime.

`mr-clean` is responsible for:

- Hiccup surface syntax
- function and closure components
- reactive atoms
- internal layout solving
- compiling authored component models into `dao.postgraphics` ops
- composing those ops into complete frame programs

It does not paint, does not subscribe to streams, and does not realize Flutter
widgets. Downstream code hands its frame programs to a graphics VM.

The intended pipeline is:

```text
[mr-clean compiler]
   │ emits dao.postgraphics frame programs
   ▼
[dao.postgraphics.flutter terminal]
   │ Flutter paint calls
   ▼
on-screen widget
```

Each arrow is either a direct function call or a `dao.stream` boundary.
Ordinary function composition wires the stages together. `mr-clean` produces
values; an application decides how to feed them downstream.

## Layering

`mr-clean` is the first stage in a UI compilation pipeline. The dependency
arrow points one way:

- `mr-clean` produces `dao.postgraphics` frame programs
- `dao.postgraphics.flutter` is the graphics VM that paints frames

Concretely:

- `mr-clean`'s semantic contract is `dao.postgraphics`, the same layer the
  Flutter VM already consumes
- `mr-clean` does not own pipeline wiring; the application that uses it does
- `mr-clean` does not paint, subscribe to streams, or realize Flutter widgets

There is no intermediate scene-graph layer in this pipeline. The scene
vocabulary family (`scene-algebra`, `dao.scene`, `scene.world`, `scene.xr`)
is reserved for retained-graphics consumers; `mr-clean` is not one of them.
See "Relationship to the Scene Vocabulary Family" below.

Stages are wired with ordinary Clojure function composition, with
`dao.stream` providing the boundary substrate where one is needed (replay,
multiple readers, decoupled producers and consumers).

## Relationship to Reagent

`mr-clean` is inspired by Reagent in the authoring model:

- Hiccup expresses structure
- functions and closures act as components
- atoms drive recomputation

But it is not React.js, not a DOM system, and not a virtual DOM runtime.

The output of `mr-clean` is graphics bytecode, not a retained tree. There is
no virtual DOM and no diffing. Atom changes cause `mr-clean` to re-evaluate
affected components and emit a fresh frame program; `dao.postgraphics` is
immediate-mode at the VM boundary, so whole-frame replacement is the
expected update model.

More precisely, `mr-clean` is like a compiler:

- input: Reagent-like component models
- output: `dao.postgraphics` frame programs

It is not a renderer and not a DOM runtime.

## Architectural Position

`mr-clean` is the first stage in a UI compilation pipeline. It sits upstream
of the graphics VM.

It owns:

- authoring syntax
- component evaluation
- atom-driven recomputation
- internal layout solving
- compilation from authored forms into `dao.postgraphics` ops

It does not own:

- terminal painting
- Flutter widget realization
- stream subscription
- graphics VM execution
- pipeline wiring

The application that uses `mr-clean` decides whether to call the graphics VM
directly, write `mr-clean`'s output to a `dao.stream`, or both. That wiring
is ordinary Clojure code, not a separate infrastructure layer.

## Output Contract

The output of `mr-clean` is `dao.postgraphics` frame programs.

More precisely:

- components compile to vectors of `dao.postgraphics` ops
- composition is op-sequence concatenation, with `:transform/push`,
  `:transform/pop`, `:clip/push-rect`, and `:clip/pop` bracketing child
  output where needed
- a top-level component evaluation produces one complete frame program
- the graphics VM consumes that frame program directly

This means `mr-clean` should target:

- [../dao.postgraphics.md](../dao.postgraphics.md)

as its semantic contract.

Layout (stack containers, gap, padding, align/justify) is resolved inside
`mr-clean` before emitting ops. Children carry their measured sizes back to
their parents so parents can place them; the final ops emitted to the VM
carry absolute coordinates in the conventions defined by
`dao.postgraphics.md`.

## Relationship to the Scene Vocabulary Family

`scene-algebra`, `dao.scene`, `scene.world`, and `scene.xr` are separate
designs for **retained graphics**. They are not part of `mr-clean`'s
pipeline.

If retained UI semantics are ever wanted — hit-testing, focus traversal,
accessibility tree, animation interpolation against a stable tree, multi-
backend retargeting against a single source — `dao.scene` is the schema
that would carry them, and a separate authoring layer or compiler stage
would target it. `mr-clean` does not.

`mr-clean` is just paint. It compiles to immediate-mode graphics bytecode and
stops there.

## Authoring Scope

For v1, `mr-clean`'s component vocabulary should be able to express the
visual structure of 2D productivity apps and 2D game UI:

- containers with stack layout
- text labels
- text input visuals
- buttons and toggles (as drawn rectangles + text, without input semantics)
- scroll region visuals (clipping, content positioning)
- overlays (z-order via op order)
- lists and tables
- HUD-style 2D game UI

This is a paint-time scope. Input dispatch, focus traversal, and
accessibility are not part of `mr-clean`. They would be handled by a
separate system, possibly using the scene vocabulary as its data layer.

## Relationship to Terminal Rendering

The terminal renderer is a separate downstream layer.

Its job is:

- read `dao.postgraphics` frames (from a `dao.stream` or by direct call)
- interpret those frames
- realize them as Flutter (or other) drawing

That terminal boundary is narrow.

`mr-clean` is not that renderer. The current `dao.postgraphics.flutter`
namespace is the v1 graphics VM; it is a peer downstream of `mr-clean`,
not the same role.

## Public Surface

The public surface of `mr-clean` should be authoring-oriented, not
VM-oriented and not stream-oriented.

Examples of concerns that belong here:

- component forms
- op-returning helpers
- atom-driven rerender semantics
- compilation to `dao.postgraphics` ops
- internal layout solving
- frame-program assembly

Examples of concerns that do not belong here:

- `{:stream ...}` terminal widget wrappers
- frame listeners
- `CustomPainter` integration
- direct VM invocation
- `dao.stream` opening or cursor management

## Design Rules

- treat `mr-clean` as the authoring layer
- treat `mr-clean` as a compiler from Reagent-like component models into
  `dao.postgraphics` frame programs
- keep terminal rendering separate
- keep pipeline wiring separate
- target `dao.postgraphics` directly; no intermediate scene-graph layer
- keep the stable contract at the postgraphics layer, the only layer with a
  real VM today
- preserve Reagent-like ergonomics without importing React or DOM assumptions
- solve layout inside the compiler; emit absolute-coordinate ops

## Accepted Defaults

These choices are fixed for v1:

- `mr-clean` is the authoring layer
- `mr-clean` is inspired by Reagent
- `mr-clean` compiles Reagent-like component models into `dao.postgraphics`
  frame programs
- `mr-clean` is not the Flutter renderer
- `mr-clean` produces `dao.postgraphics` frame programs, not scene values
- there is no intermediate scene-graph stage in `mr-clean`'s pipeline
- layout solving lives inside `mr-clean`
- pipeline wiring is ordinary function composition over `dao.stream`, not a
  separate algebra
- Flutter terminal rendering is a separate concern, owned by
  `dao.postgraphics.flutter`
- the scene vocabulary family (`scene-algebra`, `dao.scene`, `scene.world`,
  `scene.xr`) is reserved for separate retained-graphics work
