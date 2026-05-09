# Design: `mr-clean`

## Summary

`mr-clean` is the Reagent-inspired authoring compiler for `dao.flow`.

It is not the Flutter terminal renderer.

`mr-clean` is responsible for:

- Hiccup surface syntax
- function and closure components
- reactive atoms
- compiling authored component models into scene fragments
- composing those fragments into scenes

It does not paint directly in Flutter. Downstream interpreters lower its scene
output into graphics bytecode, and a separate terminal renderer interprets that
bytecode in Flutter.

The intended pipeline is:

```text
mr-clean
-> dao.scene fragments
-> assembled scene
-> graphics bytecode
-> Flutter terminal
```

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

`mr-clean` sits upstream of scene lowering and terminal rendering.

It owns:

- authoring syntax
- component evaluation
- atom-driven recomputation
- compilation from authored forms into scene fragments

It does not own:

- terminal painting
- Flutter widget realization
- frame subscription
- draw-op interpretation

Those belong to the terminal renderer, for example a `dao.flow.flutter`-style
backend.

## Output Contract

The primary output of `mr-clean` is `scene-algebra` data, not graphics
bytecode.

More precisely:

- components compile to fragments
- fragment composition yields larger fragments
- completed fragments finalize into scenes
- downstream interpreters lower scenes into graphics bytecode

This means `mr-clean` should target:

- [scene-algebra.md](scene-algebra.md)
- [scene-vocabulary.md](scene-vocabulary.md)
- [dao.scene.md](dao.scene.md)

not graphics bytecode directly as its primary semantic contract.

## Relationship to Scene Algebra

`mr-clean` should produce values compatible with `scene.core`.

That means:

- fragments are the primary compositional values
- components should compile to fragments
- fragment composition should happen explicitly
- final scene assembly should happen before terminal lowering

`mr-clean` should not skip the scene layer and emit final paint commands as its
main stable contract.

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

- subscribe to a `dao.stream`
- read graphics bytecode frames
- interpret that bytecode
- paint in Flutter

That terminal boundary should stay narrow.

`mr-clean` should not be defined as that renderer.

The current `dao.flow.flutter` pattern is a good model for the terminal side,
but it is not the semantic role of `mr-clean`.

## Public Surface

The public surface of `mr-clean` should be authoring-oriented, not
terminal-oriented.

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

## Design Rules

- treat `mr-clean` as the authoring/runtime layer
- treat `mr-clean` as a compiler from Reagent-like component models into
  `scene-algebra`
- keep terminal rendering separate
- target scene fragments first, not graphics bytecode first
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
- Flutter terminal rendering is a separate concern
