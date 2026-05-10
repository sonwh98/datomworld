# Design: `mr-clean`

## Summary

`mr-clean` is the Reagent-inspired authoring layer that compiles component
models into `dao.scene` fragments.

It is not a renderer. It is not a workflow runtime. It is not part of
`dao.flow`.

`mr-clean` is responsible for:

- Hiccup surface syntax
- function and closure components
- reactive atoms
- compiling authored component models into scene fragments
- composing those fragments into scenes

It does not paint, does not subscribe to streams, and does not realize Flutter
widgets. Downstream interpreters lower its scene output into graphics
bytecode, a `dao.flow` workflow carries that bytecode through `dao.stream`,
and a separate terminal renderer paints it.

The intended pipeline is:

```text
[mr-clean compiler]
   │ emits dao.scene fragments
   ▼
[scene-lowering interpreter]
   │ emits dao.postgraphics frames
   ▼
[dao.postgraphics.flutter terminal]
   │ Flutter paint calls
   ▼
on-screen widget
```

The boxes are stages (interpreters and a terminal). The labels on the edges
are domain vocabularies (data on the wire between stages). An application or
runner composes those stages into a workflow using `dao.flow`. `mr-clean` is
the first stage; it does not compose the workflow.

A one-line summary of the architecture:

> An application uses `dao.flow` to compose a UI compilation workflow,
> `mr-clean` → scene-lowering → `dao.postgraphics.flutter`, where `dao.scene`
> and `dao.postgraphics` are the vocabularies passed between stages.

## Layering and Dependency Direction

`mr-clean` is a client of the `dao` stack, not a layer of it. The dependency
arrow points one way:

- `mr-clean` produces values
- scene-lowering interpreters produce graphics bytecode from those values
- `dao.flow` composes workflows that transport bytecode through `dao.stream`
- `dao.postgraphics.flutter` is the terminal that paints

Concretely:

- `mr-clean` does not import `dao.flow`
- `dao.flow` does not know `mr-clean` exists
- `mr-clean`'s primary semantic contract is `dao.scene`, not `dao.flow` and
  not graphics bytecode

This matches the `dao.flow` spec, which lists `yin.vm`, `dao.postgraphics`,
`dao.gpu.compute`, UI, text, audio, telemetry, and logging as example clients,
not the definition of `dao.flow`. `mr-clean` is one such client: the UI
authoring one.

## Stages and Vocabularies

A `dao.flow` workflow is composed of stages connected by streams of values.
The values on each stream are drawn from a domain vocabulary. The two
concepts are separate, and `mr-clean` is one stage in the UI compilation
workflow, not a vocabulary.

Stages in a typical UI compilation workflow:

| Stage | Role |
|---|---|
| `mr-clean` | compiles authored components into `dao.scene` fragments |
| scene-lowering interpreter | lowers `dao.scene` values into `dao.postgraphics` frames |
| `dao.postgraphics.flutter` | terminal that realizes frames as Flutter drawing |

Vocabularies (data on the wire between stages):

| Vocabulary | Defined by | Carried between |
|---|---|---|
| `dao.scene` fragments | `dao.scene` / `scene-algebra` | `mr-clean` → scene-lowering interpreter |
| `dao.postgraphics` frames | `dao.postgraphics` | scene-lowering interpreter → terminal |

`dao.scene` and `dao.postgraphics` are vocabularies, not stages. They define
the grammar of values that cross a boundary, not a unit of work. `mr-clean`
and the lowering interpreter and the terminal are stages, not vocabularies.
They do work; they don't define wire formats.

The application composes the stages with `dao.flow` and connects them with
`dao.stream` boundaries; the vocabularies are the contract on those
boundaries.

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

`mr-clean` is the first interpreter stage in a UI compilation workflow. It
sits upstream of scene lowering, workflow composition, and terminal
rendering.

It owns:

- authoring syntax
- component evaluation
- atom-driven recomputation
- compilation from authored forms into scene fragments

It does not own:

- terminal painting
- Flutter widget realization
- frame stream subscription
- draw-op interpretation
- workflow composition

Those belong downstream or outside. Scene-lowering interpreters produce
graphics bytecode from scene values; the application uses `dao.flow` to
compose `mr-clean`, the lowering stage, and the terminal into a workflow;
a terminal such as `dao.postgraphics.flutter` realizes the resulting frames.

`mr-clean` does not compose the workflow it participates in. The application
or runner that drives the UI does.

## Output Contract

The primary output of `mr-clean` is `scene-algebra` data, not graphics
bytecode and not workflow fragments.

More precisely:

- components compile to fragments
- fragment composition yields larger fragments
- completed fragments finalize into scenes
- downstream interpreters lower scenes into graphics bytecode
- `dao.flow` workflows transport that bytecode to terminals

This means `mr-clean` should target:

- [scene-algebra.md](scene-algebra.md)
- [scene-vocabulary.md](scene-vocabulary.md)
- [dao.scene.md](dao.scene.md)

not graphics bytecode and not `dao.flow` workflow APIs as its primary semantic
contract.

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

The terminal renderer is a separate downstream layer composed via `dao.flow`.

Its job is:

- subscribe to a `dao.stream` of graphics bytecode frames
- interpret that bytecode
- realize it as Flutter (or other) drawing

That terminal boundary should stay narrow.

`mr-clean` should not be defined as that renderer. The current
`dao.postgraphics.flutter` namespace is a good model for the terminal side,
but it is a peer client of `dao.flow`, not the semantic role of `mr-clean`.

## Public Surface

The public surface of `mr-clean` should be authoring-oriented, not
terminal-oriented and not workflow-oriented.

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
- `dao.flow` workflow construction
- `dao.stream` opening or cursor management

## Design Rules

- treat `mr-clean` as the authoring/runtime layer
- treat `mr-clean` as a compiler from Reagent-like component models into
  `scene-algebra`
- keep terminal rendering separate
- keep workflow composition separate
- target scene fragments first, not graphics bytecode and not workflows
- preserve Reagent-like ergonomics without importing React or DOM assumptions
- keep the stable semantic contract at the scene layer
- never depend on `dao.flow` from `mr-clean`

## Accepted Defaults

These choices are fixed for v1:

- `mr-clean` is the authoring layer
- `mr-clean` is inspired by Reagent
- `mr-clean` compiles Reagent-like component models into `scene-algebra`
- `mr-clean` is not the Flutter renderer
- `mr-clean` is not part of `dao.flow`; it is a client whose lowered output
  flows through `dao.flow` workflows
- `mr-clean` is one stage in a UI compilation workflow, not the workflow's
  composer
- the application or runner composes the workflow using `dao.flow`
- `mr-clean` produces `dao.scene` fragments and scenes
- `dao.scene` and `dao.postgraphics` are vocabularies on the wire, not stages
- graphics bytecode is derived downstream
- workflow composition is owned by `dao.flow`, not `mr-clean`
- Flutter terminal rendering is a separate concern, owned by
  `dao.postgraphics.flutter`
