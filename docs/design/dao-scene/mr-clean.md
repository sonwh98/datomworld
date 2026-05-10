# Design: `mr-clean`

## Summary

`mr-clean` is the Reagent-inspired authoring layer that compiles component
models into `dao.scene` fragments.

It is not a renderer. It is not a stream runtime.

`mr-clean` is responsible for:

- Hiccup surface syntax
- function and closure components
- reactive atoms
- compiling authored component models into scene fragments
- composing those fragments into scenes

It does not paint, does not subscribe to streams, and does not realize Flutter
widgets. Downstream code lowers its scene output into graphics bytecode and
hands the result to a terminal renderer.

The intended pipeline is:

```text
[mr-clean compiler]
   │ emits dao.scene fragments
   ▼
[scene-lowering function]
   │ emits dao.postgraphics frames
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

- `mr-clean` produces `dao.scene` fragments
- a scene-lowering function turns those into `dao.postgraphics` frames
- `dao.postgraphics.flutter` is the terminal that paints frames

Concretely:

- `mr-clean`'s primary semantic contract is `dao.scene`, not graphics bytecode
- `mr-clean` does not own pipeline wiring; the application that uses it does
- `mr-clean` does not paint, subscribe to streams, or realize Flutter widgets

There is no separate workflow algebra. Stages are wired with ordinary Clojure
function composition, with `dao.stream` providing the boundary substrate
where one is needed (replay, multiple readers, decoupled producers and
consumers).

## Relationship to Reagent

`mr-clean` is inspired by Reagent in the authoring model:

- Hiccup expresses structure
- functions and closures act as components
- atoms drive recomputation

But it is not React.js, not a DOM system, and not a virtual DOM runtime.

The authored structure is a scene graph, not a browser tree.

More precisely, `mr-clean` is like a compiler:

- input: Reagent-like component models
- output: `scene-algebra` fragments and scenes

It is not a renderer and not a DOM runtime.

## Architectural Position

`mr-clean` is the first stage in a UI compilation pipeline. It sits upstream
of scene lowering and terminal rendering.

It owns:

- authoring syntax
- component evaluation
- atom-driven recomputation
- compilation from authored forms into scene fragments

It does not own:

- terminal painting
- Flutter widget realization
- stream subscription
- draw-op interpretation
- pipeline wiring

The application that uses `mr-clean` decides whether to call the lowering
function directly, write its output to a `dao.stream`, or both. That wiring
is ordinary Clojure code, not a separate infrastructure layer.

## Output Contract

The primary output of `mr-clean` is `scene-algebra` data, not graphics
bytecode.

More precisely:

- components compile to fragments
- fragment composition yields larger fragments
- completed fragments finalize into scenes
- downstream code lowers scenes into graphics bytecode

This means `mr-clean` should target:

- [scene-algebra.md](scene-algebra.md)
- [scene-vocabulary.md](scene-vocabulary.md)
- [dao.scene.md](dao.scene.md)

not graphics bytecode as its primary semantic contract.

## Relationship to Scene Algebra

`mr-clean` should produce values compatible with `scene.core`.

That means:

- fragments are the primary compositional values
- components should compile to fragments
- fragment composition should happen explicitly
- final scene assembly should happen before terminal lowering

`mr-clean` should not skip the scene layer and emit final paint commands as
its main stable contract.

## Relationship to Scene UI

For v1, `mr-clean` should primarily target `dao.scene`.

That means its authored forms should be able to express:

- containers
- text
- text input
- buttons and toggles
- scroll regions
- overlays
- lists and tables
- 2D app and 2D game UI structure

This keeps the first concrete domain focused on 2D productivity apps and 2D
games rather than trying to span UI, world simulation, and XR all at once.

## Relationship to Terminal Rendering

The terminal renderer is a separate downstream layer.

Its job is:

- read `dao.postgraphics` frames (from a `dao.stream` or by direct call)
- interpret those frames
- realize them as Flutter (or other) drawing

That terminal boundary should stay narrow.

`mr-clean` should not be defined as that renderer. The current
`dao.postgraphics.flutter` namespace is a good model for the terminal side,
but it is a peer downstream of `mr-clean`, not the same role.

## Public Surface

The public surface of `mr-clean` should be authoring-oriented, not
terminal-oriented and not stream-oriented.

Examples of concerns that belong here:

- component forms
- fragment-returning helpers
- atom-driven rerender semantics
- compilation to scene fragments
- scene assembly

Examples of concerns that do not belong here:

- `{:stream ...}` terminal widget wrappers
- frame listeners
- `CustomPainter` integration
- direct graphics-bytecode subscription
- `dao.stream` opening or cursor management

## Design Rules

- treat `mr-clean` as the authoring layer
- treat `mr-clean` as a compiler from Reagent-like component models into
  `scene-algebra`
- keep terminal rendering separate
- keep pipeline wiring separate
- target scene fragments first, not graphics bytecode
- preserve Reagent-like ergonomics without importing React or DOM assumptions
- keep the stable semantic contract at the scene layer

## Accepted Defaults

These choices are fixed for v1:

- `mr-clean` is the authoring layer
- `mr-clean` is inspired by Reagent
- `mr-clean` compiles Reagent-like component models into `scene-algebra`
- `mr-clean` is not the Flutter renderer
- `mr-clean` produces `dao.scene` fragments and scenes
- graphics bytecode is derived downstream
- pipeline wiring is ordinary function composition over `dao.stream`, not a
  separate algebra
- Flutter terminal rendering is a separate concern, owned by
  `dao.postgraphics.flutter`
