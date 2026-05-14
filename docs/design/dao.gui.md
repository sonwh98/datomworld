# Design: `dao.gui`

## Summary

`dao.gui` is the Reagent-inspired authoring layer that compiles component
models into `dao.postgraphics` frame programs.

It is not a renderer. It is not a stream runtime.

`dao.gui` is responsible for:

- Hiccup surface syntax
- function and closure components
- internal layout solving
- compiling authored component models into `dao.postgraphics` ops
- composing those ops into complete frame programs
- watching a declared set of Clojure atoms and retriggering compilation
  when any of them changes

It does not paint, does not subscribe to `dao.stream` cursors, and does not
realize Flutter widgets. Downstream code hands its frame programs to a
graphics VM.

The intended pipeline is:

```text
[dao.gui compiler]
   │ emits dao.postgraphics frame programs
   ▼
[dao.postgraphics.flutter terminal]
   │ Flutter paint calls
   ▼
on-screen widget
```

Each arrow is either a direct function call or a `dao.stream` boundary.
Ordinary function composition wires the stages together. `dao.gui` produces
values; an application decides how to feed them downstream.

## Layering

`dao.gui` is the first stage in a UI compilation pipeline. The dependency
arrow points one way:

- `dao.gui` produces `dao.postgraphics` frame programs
- `dao.postgraphics.flutter` is the graphics VM that paints frames

Concretely:

- `dao.gui`'s semantic contract is `dao.postgraphics`, the same layer the
  Flutter VM already consumes
- `dao.gui` does not own pipeline wiring; the application that uses it does
- `dao.gui` does not paint, subscribe to streams, or realize Flutter widgets

There is no intermediate scene-graph layer in this pipeline. The scene
vocabulary family (`scene-algebra`, `dao.scene`, `scene.world`, `scene.xr`)
is reserved for retained-graphics consumers; `dao.gui` is not one of them.
See "Relationship to the Scene Vocabulary Family" below.

Stages are wired with ordinary Clojure function composition, with
`dao.stream` providing the boundary substrate where one is needed (replay,
multiple readers, decoupled producers and consumers).

## Relationship to Reagent

`dao.gui` is inspired by Reagent in the authoring model:

- Hiccup expresses structure
- functions and closures act as components
- atoms drive recomputation

But it is not React.js, not a DOM system, and not a virtual DOM runtime.

The output of `dao.gui` is graphics bytecode, not a retained tree. There is
no virtual DOM and no diffing.

V1 uses **whole-root recomputation**: any change to a watched atom retriggers
the compiler on the top-level component, producing a complete frame program.
`dao.gui` does not track which components depend on which atoms; it does
not attempt fine-grained invalidation. `dao.postgraphics` is immediate-mode
at the VM boundary, so whole-frame replacement is the expected update model
and whole-root recomputation lines up with it cleanly.

Fine-grained per-component invalidation (Reagent's `ratom` + `reaction`
pattern) is a later optimization. It requires a reactive primitive with
dependency tracking that does not yet exist in this codebase. v1 does not
include it.

More precisely, `dao.gui` is like a compiler:

- input: Reagent-like component models
- output: `dao.postgraphics` frame programs

It is not a renderer and not a DOM runtime.

## Architectural Position

`dao.gui` is the first stage in a UI compilation pipeline. It sits upstream
of the graphics VM.

It owns:

- authoring syntax
- component evaluation
- internal layout solving
- compilation from authored forms into `dao.postgraphics` ops
- atom watching and whole-root recompilation on change

It does not own:

- terminal painting
- Flutter widget realization
- stream subscription
- graphics VM execution
- pipeline wiring

The application that uses `dao.gui` decides whether to call the graphics VM
directly, write `dao.gui`'s output to a `dao.stream`, or both. That wiring
is ordinary Clojure code, not a separate infrastructure layer.

## Output Contract

The output of `dao.gui` is `dao.postgraphics` frame programs.

More precisely:

- components compile to a **layered contribution value** of the shape
  `{:flow [ops…] :overlay [ops…]}`
- as a convenience, returning a plain op-vector is interpreted as
  `{:flow that-vector :overlay []}`; most components stay in this form
- the `:flow` contribution receives ops in their natural component
  order, inside any active transform and clip brackets; composition is
  op-sequence concatenation, with `:transform/push` / `:transform/pop`
  bracketing each child's flow ops to position it in its parent's local
  frame, and `:clip/push-rect` / `:clip/pop` bracketing scroll regions
  and other clips
- the `:overlay` contribution accumulates into a single top-level
  overlay buffer that the compiler appends after all normal-flow ops at
  assembly time (see "Overlay layer" below)
- a top-level component evaluation produces one complete frame program
- the graphics VM consumes that frame program directly

This means `dao.gui` should target:

- [dao.postgraphics.md](dao.postgraphics.md)

as its semantic contract.

### Coordinate strategy: structure-preserving, not flattened

`dao.gui` uses the graphics VM's transform stack rather than flattening
layout into absolute leaf coordinates.

Concretely:

- the layout solver computes a **placement transform** for each child in
  its parent's local frame
- in v1, layout-emitted placement transforms are **translate-only** — no
  scale, no rotate, no general affine matrix
- each child's ops are bracketed by a `:transform/push :translate [x y]`
  carrying that placement and a matching `:transform/pop`
- leaf draw ops (`:draw/fill-rect`, `:draw/text`, `:draw/fill-circle`,
  `:draw/path`, …) carry coordinates **in the component's own local frame**,
  typically authored as if the component's origin is `[0 0]`
- the VM's transform stack resolves local frames to screen space at paint
  time, per the conventions in `dao.postgraphics.md`

Consequences:

- the emitted frame program mirrors the component tree: one transform
  bracket per child placed at a non-zero offset (the compiler may elide
  the bracket when the placement is `[0 0]`)
- the layout solver computes placements, not rewritten leaf coordinates
- components are translation-invariant by construction — they can be
  authored once and reused at any position by their parent

### Why translate-only placements

`dao.postgraphics`'s clip primitive (`:clip/push-rect`) is a
**screen-space axis-aligned scissor rect**, applied after the viewport
transform and not affected by the current transform stack. Under
translate-only ancestor transforms, a local rectangular scroll region
maps exactly to a screen-space axis-aligned rect; the compiler can
resolve the screen-space rect deterministically. Under non-uniform scale
or rotation, a local rect's screen-space bounding box would either
over-clip or under-clip — the scissor primitive is not expressive enough
to clip the true rotated/sheared region.

V1 chooses correctness over expressiveness: by restricting
layout-emitted placements to translates, `dao.gui` guarantees clip ops
are exact.

### Component-internal non-translation transforms

Components may still author `:transform/push` ops with scale, rotate, or
`:matrix` for their own visual effect (a rotated icon, a scaled
illustration). The constraint is narrow:

- a `:clip/push-rect` op is only valid when the **entire enclosing
  transform stack at that point is translate-only**
- equivalently: `dao.gui` must not emit a clip op inside any subtree
  whose ancestors include a non-translation transform
- components that combine scale/rotate with clipping in v1 are a
  compiler error

This matters in practice because the offending parent and the offending
child are often unrelated — a card with a wobble animation (scale +
rotate) may contain a scroll region authored by a different component.
Detection is the compiler's responsibility:

- during recursive evaluation, the compiler threads a `translate-only?`
  flag through its descent state
- the flag starts true at the root and is cleared by any
  `:transform/push` carrying scale, rotate, or `:matrix`
- when a component emits a `:clip/push-rect`, the compiler reads the
  current flag and raises a compiler error if it is false
- the error must name the offending clip-emitting component **and** the
  enclosing non-translation transform, so the author can fix one or the
  other

The check is dynamic, not static. The compiler does not analyze
component bodies for clip ops ahead of time; it validates at op-emission
during evaluation, with full dynamic context.

Lifting this restriction would require either a richer clip primitive in
`dao.postgraphics` (e.g. `:clip/push-path`) or accepting a documented
loss of clip precision. Neither is in v1 scope.

### Layout and measurement

Layout is **two-phase**: parents pass constraints down to children, and
children pass measured sizes back up. A purely bottom-up measurement
pass cannot handle text wrapping, where a child's height depends on a
width constraint imposed by its parent.

Per compilation:

1. A parent decides what constraints each child receives. In v1, a
   constraint is a pair `{:max-width w :max-height h}` where either
   value may be `:unbounded` (the parent is not imposing a maximum on
   that axis). Minimum-size constraints are deferred; v1 children may
   freely shrink below any preferred size.
2. Each child computes its own size under those constraints. For most
   components this is a function of constraints and props alone.
3. Text components compute their wrapped size by invoking a **text
   measurement capability** passed into the compiler. The capability is
   a pure function provided by the host with roughly this shape:
   `measure-text : {:text/value, :text/font-size, :text/font-family,
   :max-width} → {:width w :height h :lines [...]}`. It must return
   measurements consistent with what the graphics VM will actually
   paint.
4. The parent uses each child's measured size to compute placement
   transforms, then emits the bracketed ops.

The pure compile function takes the measurement capability as an
explicit input. v1 only requires `measure-text`; future capabilities
(image intrinsic size, icon metrics) follow the same pattern.

**Images in v1 carry explicit width and height as props.** Intrinsic-
size queries (asking the host "how big is this image source?") are
deferred to a later capability. An image component without explicit
dimensions is a compiler error in v1, not a request for measurement.

The measurement capability must be referentially transparent — calling
it with the same arguments must return the same result — so the
compiler can call it as many times as layout iteration requires. Layout
that needs multiple passes (e.g. flex-shrink resolution) is permitted
inside the compiler as long as the final emitted ops are correct.

### Overlay layer

A component nested inside a `:clip/push-rect` cannot emit ops that
escape that clip by appearing later in the op sequence: the clip is
active until its matching `:clip/pop`. Tooltips, dropdowns, and modal
overlays anchored to a clipped element need an algebraic escape hatch.

`dao.gui` provides this as an **overlay layer**, expressed through the
layered contribution shape introduced in the Output Contract above.

Components route ops into the overlay layer by returning a contribution
with a non-empty `:overlay` vector, or — more commonly — by using an
`overlay` helper that produces such a value:

```text
(overlay child-ops)
  → {:flow []
     :overlay [{:op/kind :transform/push :translate [ax ay]}
               …child-ops…
               {:op/kind :transform/pop}]}
```

At the moment the `overlay` helper is evaluated:

- the compiler resolves the current screen-space anchor `[ax ay]` by
  composing the active transform stack (allowed because ancestors must
  be translate-only here)
- the helper returns a contribution whose `:overlay` vector contains
  the anchored child ops as a self-contained bracketed sequence
- the parent component, on receiving this contribution, merges it: the
  child's `:flow` ops splice into the parent's `:flow` buffer in
  position; the child's `:overlay` ops splice into the parent's
  `:overlay` buffer unchanged
- merging is order-preserving and associative — overlays from deeper
  in the tree accumulate without losing their order

At assembly time, the top-level frame program is
`top-contribution.flow ++ top-contribution.overlay`. The overlay
section is appended after all normal-flow ops, so it paints on top.
Within the overlay section, emission order determines z-order among
overlays.

The compiler does not maintain a side-channel mutable buffer. Overlay
data is a return value, threaded through evaluation as data, then
concatenated once at the root.

Constraints on the overlay primitive in v1:

- ancestor transforms above an `(overlay ...)` call must be
  translate-only (same rule as clips, same reason)
- overlay child ops may use clips and any transforms internally; those
  brackets are emitted into the overlay buffer and balanced there
- nested overlays flatten: `(overlay ...)` inside another
  `(overlay ...)` contributes to the same single overlay buffer in v1

## Relationship to the Scene Vocabulary Family

`scene-algebra`, `dao.scene`, `scene.world`, and `scene.xr` are separate
designs for **retained graphics**. They are not part of `dao.gui`'s
pipeline.

If retained UI semantics are ever wanted — hit-testing, focus traversal,
accessibility tree, animation interpolation against a stable tree, multi-
backend retargeting against a single source — `dao.scene` is the schema
that would carry them, and a separate authoring layer or compiler stage
would target it. `dao.gui` does not.

`dao.gui` is just paint. It compiles to immediate-mode graphics bytecode and
stops there.

## Authoring Scope

For v1, `dao.gui`'s component vocabulary should be able to express the
visual structure of 2D productivity apps and 2D game UI:

- containers with stack layout
- text labels
- text input visuals
- buttons and toggles (as drawn rectangles + text, without input semantics)
- scroll region visuals (clipping, content positioning)
- overlays (rendered in a dedicated post-flow overlay layer; see
  Output Contract → "Overlay layer")
- lists and tables
- images with explicit width and height (intrinsic-size queries are
  deferred)
- HUD-style 2D game UI

This is a paint-time scope. Input dispatch, focus traversal, and
accessibility are not part of `dao.gui`. They would be handled by a
separate system, possibly using the scene vocabulary as its data layer.

## Relationship to Terminal Rendering

The terminal renderer is a separate downstream layer.

Its job is:

- read `dao.postgraphics` frames (from a `dao.stream` or by direct call)
- interpret those frames
- realize them as Flutter (or other) drawing

That terminal boundary is narrow.

`dao.gui` is not that renderer. The current `dao.postgraphics.flutter`
namespace is the v1 graphics VM; it is a peer downstream of `dao.gui`,
not the same role.

## Public Surface

`dao.gui` exposes two layers with an explicit boundary between them:

1. A **pure compiler**. Its inputs are:

   - the root component form
   - its props
   - an **atom snapshot** — a map (or equivalent indexed value) from
     watched-atom identity to its current value at the start of this
     compile; **the runtime derefs atoms and passes the snapshot in;
     the compiler does not call `deref`**
   - a **capabilities** map containing at minimum a `measure-text`
     pure function (see "Layout and measurement" above), supplied by
     the host

   Its output is a complete `dao.postgraphics` frame program. No side
   effects. No atom watching. No stream writing. No deref of live
   atoms during compilation. This is the layer most of `dao.gui` is.

   Keeping atom resolution outside the compiler is what makes
   recompilation deterministic: the same `(form, props, snapshot,
   capabilities)` always produces the same frame program.
2. A **thin reactive runtime** that wraps the compiler. Its
   responsibilities are:

   - watch a declared set of Clojure atoms via `add-watch`
   - **coalesce changes within one tick.** Multiple watched atom changes
     that fire synchronously before the next event-loop turn must
     produce exactly one recompile. The runtime schedules recompilation
     on the next available tick (microtask, animation-frame callback,
     or equivalent on the host) and discards intermediate states. This
     prevents synchronous storms from a sequence of `swap!` calls or a
     batch of network events from re-compiling the tree 10 times in one
     tick.
   - **guard against re-entrant mutation.** While a compile is in
     progress, mutating any watched atom is a hard error. The runtime
     sets a `compiling?` flag at the start of each compile and clears
     it at the end; an atom watcher that fires with the flag set raises
     an exception. This catches the common bug of mutating an atom
     inside a render function (e.g. `(swap! state inc)` written where
     `#(swap! state inc)` was intended for an event handler).

     To produce a useful error, the compiler exposes the current
     **evaluation path** — the chain of component names from the root
     to the component being evaluated — through a dynamically-bound var
     (Clojure `^:dynamic`, ClojureScript `binding`, or host equivalent)
     that the runtime reads when raising the exception. The exception
     names the offending atom and that path. This is the only
     compiler→runtime interface beyond the return value, and it exists
     specifically for diagnostics; the compiler does not otherwise
     depend on the runtime.
   - retrigger the compiler when any watched atom changes
   - hand the resulting frame program to a caller-supplied consumer
     (a function, or a `dao.stream` write)

   The runtime owns nothing else: no stream cursors, no widget
   references, no graphics VM state. The exact scheduler (microtask,
   animation frame, immediate-after-current-call) is an implementation
   choice; the contract is "coalesce within one tick, recompile once,
   reject mutation during compile."

Frame *delivery* — whether each new frame is written to a `dao.stream`,
handed directly to the Flutter VM, or both — is the application's choice.
The reactive runtime does not open streams; it just calls the consumer.

The public surface should be authoring-oriented, not VM-oriented and not
stream-oriented.

Examples of concerns that belong here:

- component forms
- op-returning helpers
- the pure compile function
- internal layout solving
- frame-program assembly
- the atom-watching reactive runtime (`add-watch` + retrigger)

Examples of concerns that do not belong here:

- `{:stream ...}` terminal widget wrappers
- graphics-VM frame listeners
- `CustomPainter` integration
- direct VM invocation
- `dao.stream` opening or cursor management

## Design Rules

- treat `dao.gui` as the authoring layer
- treat `dao.gui` as a compiler from Reagent-like component models into
  `dao.postgraphics` frame programs
- keep terminal rendering separate
- keep pipeline wiring separate
- target `dao.postgraphics` directly; no intermediate scene-graph layer
- keep the stable contract at the postgraphics layer, the only layer with a
  real VM today
- preserve Reagent-like ergonomics without importing React or DOM assumptions
- solve layout inside the compiler; use the VM's transform stack rather
  than flattening to absolute leaf coordinates
- restrict layout-emitted placement transforms to translates in v1; allow
  scale/rotate only inside leaf-component authoring, and never above a
  clip op
- keep the pure compiler and the reactive runtime as separate, named layers
- recompute the whole component tree on any watched atom change in v1; do
  not invent fine-grained invalidation before a real reactive primitive
  exists
- detect clip-under-non-translation-transform at op-emission via a
  `translate-only?` flag threaded through compiler descent; the error must
  name both the offending clip-emitting component and the enclosing
  non-translation transform
- provide an overlay primitive so deeply nested components can escape
  enclosing clips; overlay ops paint after all normal-flow ops in a
  single top-level layer
- treat layout as two-phase (constraints down, sizes up) and take text
  measurement as a pure capability supplied to the compiler as an input
- coalesce watched-atom changes within one tick into one recompile; treat
  mutation of a watched atom during compilation as a hard error
- components return a layered contribution `{:flow [...] :overlay [...]}`,
  or a plain op-vector interpreted as flow-only; the overlay layer is
  threaded through evaluation as data, never via a side-channel mutable
  buffer
- the compiler takes the atom snapshot, props, and capabilities as
  explicit inputs; only the runtime calls `deref` on watched atoms
- the compiler exposes the current evaluation path through a
  dynamically-bound var so the runtime can produce useful re-entrancy
  errors
- in v1, images carry explicit width and height; intrinsic-size queries
  are deferred

## Accepted Defaults

These choices are fixed for v1:

- `dao.gui` is the authoring layer
- `dao.gui` is inspired by Reagent
- `dao.gui` compiles Reagent-like component models into `dao.postgraphics`
  frame programs
- `dao.gui` is not the Flutter renderer
- `dao.gui` produces `dao.postgraphics` frame programs, not scene values
- there is no intermediate scene-graph stage in `dao.gui`'s pipeline
- layout solving lives inside `dao.gui`
- lowering is structure-preserving: the layout solver emits placement
  transforms (`:transform/push` / `:transform/pop`) around child ops; leaf
  draw ops use the component's local coordinates
- layout-emitted placement transforms are translate-only in v1; clip ops
  are only valid under translate-only enclosing transform stacks
- the compiler enforces the clip-under-non-translation invariant at
  op-emission via a `translate-only?` flag threaded through descent
- `dao.gui` provides an overlay primitive that emits ops into a
  post-flow overlay layer outside any enclosing clip; nested overlays
  flatten in v1
- components return a layered contribution value
  `{:flow [...] :overlay [...]}`; the compiler does not use a
  side-channel mutable overlay buffer
- the pure compiler takes (form, props, atom-snapshot, capabilities)
  as inputs; only the runtime derefs watched atoms
- the compiler publishes the current evaluation path as a
  dynamically-bound var for runtime diagnostics
- layout is two-phase (constraints down, sizes up); text measurement is
  a pure capability supplied to the compiler as an explicit input
- constraints in v1 are `{:max-width :max-height}` with either value
  permitted to be `:unbounded`; min-size constraints are deferred
- images in v1 carry explicit width and height; intrinsic-size queries
  are deferred
- recompilation is whole-root: any watched atom change retriggers the
  compiler on the top-level component
- the public surface is split into a pure compiler and a thin atom-watching
  reactive runtime, with an explicit boundary between them
- the reactive runtime coalesces watched-atom changes within one tick
  into a single recompile and rejects mutation of a watched atom during
  an in-flight compile
- pipeline wiring is ordinary function composition over `dao.stream`, not a
  separate algebra
- Flutter terminal rendering is a separate concern, owned by
  `dao.postgraphics.flutter`
- the scene vocabulary family (`scene-algebra`, `dao.scene`, `scene.world`,
  `scene.xr`) is reserved for separate retained-graphics work
