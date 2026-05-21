# Design: `dao.gui`

## Summary

`dao.gui` is an **immediate-mode hiccup compiler**. It is a pure function
from authored components to `dao.postgraphics` frame programs, parameterized
by application state held in `dao.db`.

It is not a renderer. It is not a reactive runtime. It is not a stream
runtime. It retains no state between compile calls.

`dao.gui` is responsible for:

- Hiccup surface syntax
- function and closure components
- a normative built-in keyword tag set
- internal layout solving
- compiling authored component models into `dao.postgraphics` ops
- composing those ops into one complete frame program per compile call

It does not paint, does not subscribe to `dao.stream` cursors, does not
realize Flutter widgets, does not own application state, does not manage
re-render scheduling. The application drives the render loop. The Flutter
terminal paints the frames.

The intended pipeline is:

```text
[application]
   │ holds db value, drives the render loop
   ▼
[dao.gui compile]
   │ pure fn (form, props, db, capabilities) → {:frame ... :diagnostics ...}
   ▼
[frame stream / direct call]
   │ downstream consumes `:frame`; application also receives `:diagnostics`
   ▼
[dao.postgraphics.flutter terminal]
   │ Flutter paint calls
   ▼
on-screen widget
```

Each arrow is either a direct function call or a `dao.stream` boundary.
Ordinary function composition wires the stages together. `dao.gui`
produces values; the application decides when to call it and how to feed
the resulting frame program downstream.

## Layering and Related Systems

`dao.gui` is the first stage in a UI compilation pipeline. The dependency
arrow points one way:

- `dao.gui` produces `dao.postgraphics` frame programs
- `dao.postgraphics.flutter` is the v1 graphics VM that paints frames

`dao.gui`'s semantic contract is `dao.postgraphics`, the same layer the terminal consumes. `dao.gui` does not own pipeline wiring; the application that uses it does.

**Terminal Rendering:** The Flutter terminal (`dao.postgraphics.flutter`) is a separate downstream layer that reads frames, realizes them as Flutter drawing, and emits presented geometry and native tap input back to the event runtime. `dao.gui` is not that renderer. While multiple terminals (software raster, SVG/PDF emitter) could consume these frames, v1 specifies only the Flutter terminal.

**Event Routing:** `dao.gui.event` is a separate runtime layer downstream of terminal presentation. It consumes terminal-emitted geometry, derives a hit index, and emits dispatch events back to the application. `dao.gui` only lowers identity into `:meta/region` ops; it does not handle hit-testing or dispatch. See `dao.gui.event.md` for the full contract.

**Retained Graphics (Scene Vocabulary):** The scene vocabulary family (`scene-algebra`, `dao.scene`, `scene.world`, `scene.xr`) is reserved for separate retained-graphics work. There is no intermediate scene-graph layer in `dao.gui`'s pipeline. It compiles to immediate-mode graphics bytecode and stops there.

## Immediate-Mode Rendering Model

`dao.gui` is immediate-mode in the precise technical sense:

- every compile call regenerates the **entire** hiccup tree from `(form,
  props, db)` and emits a complete frame program
- there is **no virtual DOM**, no widget tree, no retained scene
- there is **no diffing** between frames
- the library retains **nothing** between compile calls
- the previous frame program is irrelevant to producing the next one

This is the same rendering-model shape as Dear ImGui, applied to hiccup-as-description and `dao.postgraphics`-as-output. Components are pure functions; state is the application's; frames are values; the library is stateless.

While `dao.gui` borrows three authoring conveniences from Reagent — hiccup, function components, and a familiar keyword-tag vocabulary — it does **not** borrow Reagent's rendering model. There is no `ratom`, no `reaction`, no reactive cell, no implicit dependency tracking, no component-local atoms, and no `add-watch` infrastructure inside the library. Reactivity is the application's concern, not `dao.gui`'s.

## Public Surface

`dao.gui` exposes a single layer: **the pure compile function.** There is
no reactive runtime, no atom watcher, no scheduler. The application drives
the loop.

### The compile function

Inputs:

- the root component form (hiccup, or a function-component invocation)
- its props (a Clojure value)
- a `dao.db` value (the database value as of the start of this compile)
- a **capabilities** map containing at minimum:
  - `measure-text` — pure text measurement function (see "Layout and measurement")
  - optionally `db-pull`, `db-q` — pure reader functions over the current `db` value

Output:

- a result map containing a complete `dao.postgraphics` frame program under
  `:frame`
- a diagnostics vector under `:diagnostics` (possibly empty)

The compile function does no I/O, does not call `deref` on any reference, does not transact to `dao.db`, does not write to any stream, and does not retain anything after returning. It is strictly deterministic in `(form, props, db, capabilities)`, assuming the supplied capabilities are themselves pure.

For v1, the compile function is **total at the result boundary**: ordinary
compile failures are surfaced in `:diagnostics`, not thrown out-of-band. When
the compiler cannot produce a meaningful frame, it returns a valid empty frame
program `[]` plus one or more `:error` diagnostics. The application may choose
to keep showing the previous frame, but that retention policy is outside
`dao.gui`; the compiler itself returns only data.

### Diagnostics

Compiler diagnostics are returned as part of the compile result, not
delivered via a side-effecting sink. The compile function returns:

```clojure
{:frame       [op …]                    ; the frame program
 :diagnostics [diagnostic-map …]}        ; possibly empty
```

Normative diagnostic kinds in v1:

- `:dao.gui/layout-non-convergence` — warning; layout did not stabilize within
  the 3-pass cap. The pass-3 snapshot is returned as the frame.
- `:dao.gui/inert-meta-region` — warning; the compiler produced a
  `:meta/region` without a non-empty `:interactive-events` set, so the region
  is non-drawing and inert for v1 hit dispatch.
- `:dao.gui/duplicate-interactive-target` — error; two distinct logical targets
  share `(node-id, event-kind)` within one completed frame.
- `:dao.gui/translate-only-violation` — error; a `:clip/push-rect`, an
  interactive `:meta/region`, or an `(overlay ...)` call was emitted inside a
  subtree whose enclosing transform stack is not translate-only. The
  diagnostic names the offending op-emitting component **and** the enclosing
  non-translation transform.
- `:dao.gui/component-exception` — error; a function-component evaluation
  threw. The diagnostic carries the originating component and the captured
  exception value.
- `:dao.gui/invalid-contribution` — error; a component returned a shape that
  is not a layered contribution, plain op-vector, or empty value.
- `:dao.gui/missing-capability` — error; a required capability such as
  `measure-text` was absent from the capabilities map.
- `:dao.gui/expansion-limit` — error; component expansion exceeded the
  implementation's runaway-expansion threshold.

Severities in v1 are `:warning` and `:error`. A warning does not suppress the
frame. An error is reported through the diagnostic boundary and may be either
local or fatal:

- a **local** error omits only the offending subtree or contribution while the
  rest of the frame is still returned
- a **fatal** error returns the valid empty frame program `[]` plus the error
  diagnostic(s), because the compiler could not produce a meaningful whole-frame
  result

Normative error handling rules:

- exceptions thrown by function-component evaluation are caught and returned as
  `:error` diagnostics, for example `:dao.gui/component-exception`
- a component that returns an invalid contribution shape yields an `:error`
  diagnostic, for example `:dao.gui/invalid-contribution`, and does not
  contribute ops
- `:dao.gui/duplicate-interactive-target` is fatal in v1, because the compiler
  cannot derive one unambiguous hit target for the completed frame
- `:dao.gui/translate-only-violation` is local in v1: the offending clip,
  interactive region, or overlay contribution is omitted, while unaffected
  subtrees remain in the returned frame
- if an authored interactive construct would lower to an inert `:meta/region`,
  the compiler SHOULD emit `:dao.gui/inert-meta-region` as a warning
- if required capabilities such as `measure-text` are absent, compile returns
  a fatal `:error` diagnostic, for example `:dao.gui/missing-capability`
- `db` may be `nil`; it is treated as an ordinary immutable input value. Any
  higher-level reader that cannot interpret `nil` must surface that failure
  through the same compile diagnostic boundary
- the 3-pass layout cap uses the deterministic pass-3 snapshot as the returned
  frame and emits `:dao.gui/layout-non-convergence` as a warning
- implementations SHOULD detect runaway component expansion and return an
  fatal `:error` diagnostic such as `:dao.gui/expansion-limit`; unrecoverable
  host failures such as process OOM remain outside the v1 language contract

Returning `nil` or `[]` from the root is valid in v1 and produces the valid
empty frame program `[]`.

## State Model: `dao.db` as the canonical substrate

Application state lives in `dao.db`. The compiler reads it; the compiler never writes it. Mutations are values that the application's event handlers return; the application transacts them; the next compile sees the new db.

- a `dao.db` value is **immutable** and **temporal** — a value-of-the-database at one point in time
- `dao.db` is **graph-shaped** (datoms) — no idents to invent, no normalization layer needed
- queries are pure Clojure projections of the db value (e.g., `d/pull`, `d/q`)
- mutations are tx-data values — no mutation registry, no named-operation lifecycle
- time travel is `(d/as-of db t)`
- persistence and replay are properties of `dao.db`, not of `dao.gui`

### `db` as compile input

The `dao.db` value is constant for the duration of one compile call. Components access it via either explicit threading (taking `db` as an argument) or via reader functions from the capabilities map (`db-pull`, `db-q`) that close over the current-compile's `db` value. Both forms are valid and pure. Pure presentational components reference neither.

### View functions: the convention for shape transformation

Datom shapes and render shapes are different. The bridge is a **view function**: a plain Clojure function of `(db, args...)` returning a render-ready value. View functions are a **convention**, not a framework concept. `dao.gui` does not define a view registry or subscription graph.

### Mutations as tx-data values

Components describe interaction by attaching tx-data values to event attributes (e.g., `:on-tap [:project/save project-eid]`). The `:on-tap` value is a piece of data, not a callback. The application's event handler interprets the value, computes the tx-data, and transacts to `dao.db`.

In v1, `:on-tap` is authoring syntax above the bytecode boundary:

- a component that accepts `:on-tap` MUST also provide or derive a stable
  `:node-id`
- the compiler consumes `:on-tap` only to mark the target as interactive,
  lowering one authoritative `:meta/region` with `:interactive-events #{:tap}`
- the tx-data payload itself is not serialized into `dao.postgraphics`
- `dao.gui.event` dispatches only by `(node-id, :tap)`; the application is
  responsible for registering a subscriber for that key which closes over or
  otherwise looks up the authored `:on-tap` value
- the canonical application pattern is to maintain its own `(node-id -> handler
  or tx-data)` registry alongside render state, refresh that registry from the
  same authored tree being compiled, and register `dao.gui.event` subscribers
  from that application-owned table

Built-in widgets may treat `:on-tap` as convenient syntax. User-authored
components may lower interaction manually as long as they produce the same
`(node-id, event-kind)` contract.

### Widget-ephemeral state

State like scroll position, animation tween phase, text-input cursor, and hover is lifted into application state, keyed by `:gui/id`.

The identity strategy in `dao.gui` separates roles to ensure architectural stability:
- **`:gui/id`** — Keys persistent widget state. Authors MUST provide a stable `:gui/id` for components that carry state across recompiles.
- **`:node-id`** — Keys interaction and hit-testing. It is lowered into `:op/meta` for tap routing.

While often sharing the same value, decoupling these keys allows a component to change its interaction target (re-nesting, hit-slop adjustment) without losing its ephemeral state (scroll position, text focus). `:gui/id` must be domain-stable rather than evaluation-path-derived: moving a component between flow and overlay, or changing subtree traversal order, must not by itself change the key used for persistent widget state.

Shared `:gui/id` with distinct `:node-id` values is valid. Several interactive affordances may intentionally project onto one retained widget state while still dispatching taps as separate targets. The application's event-handling step function updates that state in response to dispatched events; the next compile projects it back into the relevant components.

For `:gui/scroll` in v1, that state is programmatic rather than terminal-driven:
the scroll offset lives in application state keyed by `:gui/id`, and changing
that value requires an application-level state update followed by recompilation.
V1 does not define a public scroll event kind, drag gesture, or native
scroll-wheel routing in `dao.gui.event`.

**V1 lowering of `:gui/scroll`.** A `:gui/scroll` container with viewport rect
`[vx vy vw vh]`, content in its own local frame, and current scroll offset
`[ox oy]` (read from application state by `:gui/id`) lowers to:

```text
:clip/push-rect      :rect [vx vy vw vh]    ; clip viewport in local frame
:transform/push      :translate [(- ox) (- oy)]
  …content ops…                              ; child draws in content frame
:transform/pop
:clip/pop
```

The clip bracket fixes the visible viewport in the parent's local frame. The
inner translate offsets the content by the negative scroll offset, so a
positive `oy` reveals content lower in the natural reading direction. Children
inside the bracket render in their own translated content frame, so their
draws and any nested `:meta/region` ops use the same translate-only ancestry
rule as elsewhere in `dao.gui`.

Native scroll gestures (touch drag, scroll wheel, two-finger gestures) are
out of scope for v1. The Flutter terminal drops these gestures at the
terminal boundary in v1 and does not forward them as `:tap` events. An
application that wants user-driven scrolling in v1 must drive `oy` updates
through some other input mechanism (button widgets that change offset,
keyboard handlers wired by the host, etc.) until a future revision defines
scroll events through `dao.gui.event`.

## The render loop

The application decides when to compile. There is no framework-managed loop. Common patterns include **transaction-driven** (recompile when `dao.db` transactions land) and **animation-frame-driven** (recompile on every Flutter frame). The application manages coalescing. The library has no `dispose`, no `compiling?` flag, and no evaluation-path dynamic var.

## Output Contract

The output of a compile call is a `dao.postgraphics` frame program.

- components compile to a **layered contribution value** of the shape `{:flow [ops…] :overlay [ops…]}`
- as a convenience, returning a plain op-vector is interpreted as `{:flow that-vector :overlay []}`
- the `:flow` contribution receives ops in their natural component order, inside any active transform and clip brackets
- the `:overlay` contribution accumulates into a single top-level overlay buffer appended after all normal-flow ops at assembly time

Valid contribution forms in v1 are:

- `nil`, interpreted as `{:flow [] :overlay []}`
- a plain op vector, interpreted as `{:flow that-vector :overlay []}`
- a map whose recognized keys are `:flow` and `:overlay`

For contribution maps, omitted recognized keys default to empty vectors.
Unrecognized extra keys are ignored. A contribution is invalid only when these
recognized fields are malformed, for example non-vector `:flow` or `:overlay`
values.

### Compiler Metadata Obligations

The compiler MUST propagate authored identity into the frame program.
For interactive authoring in v1:

- each interactive `(node-id, event-kind)` lowers to exactly one explicit non-drawing `:meta/region` op that defines the authoritative hit geometry
- downstream `dao.gui.event` hit geometry for `dao.gui`-authored interactive nodes comes from the explicit `:meta/region` op
- draw ops MUST NOT carry `:interactive-events` in their `:op/meta`
- the compiler MUST emit that authoritative `:meta/region` inside the same enclosing transform and clip brackets as the node's draw ops
- the compiler MUST attach explicit hit-precedence metadata to that authoritative `:meta/region`. For ordinary flow regions, precedence is derived from the assembled frame's visual stacking. For overlay regions whose ordering is not pinned by higher-level application conventions, precedence is assigned by the rule defined in the Overlay layer section below, and that assignment is itself the source of visual stacking for those overlays. In neither case may precedence be left to incidental compiler traversal order without being materialized into `:op/precedence`
- duplicate `(node-id, event-kind)` values for different logical interactive targets within one completed frame are a compiler error (detected in a post-pass)

Because `dao.gui`-authored draw ops never carry `:interactive-events`, that
explicit `:meta/region` is the sole authoritative source of hit geometry for
the target at the terminal. See `dao.gui.event.md`'s Region Aggregation rules.

### Coordinate strategy: structure-preserving, not flattened

`dao.gui` uses the graphics VM's transform stack rather than flattening layout into absolute leaf coordinates. The layout solver computes a **placement transform** for each child in its parent's local frame. Leaf draw ops carry coordinates **in the component's own local frame**. The emitted frame program mirrors the component tree with bracketed `:transform/push` and `:transform/pop` ops.

### Why translate-only placements

In v1, layout-emitted placement transforms are **translate-only** — no scale, no rotate, no general affine matrix.
`dao.postgraphics`'s clip primitive (`:clip/push-rect`) is an axis-aligned scissor rect. By restricting layout-emitted placements to translates, a local rectangular clip region remains an axis-aligned rectangle in screen-space, allowing for deterministic and exact hit-testing. Components emit `:clip/push-rect` in their own **local coordinate frame**, matching the convention for draw ops; the VM resolves them against the transform stack.

### Component-internal non-translation transforms

Components may still author `:transform/push` ops with scale, rotate, or `:matrix` for their own visual effect. The constraint is: a `:clip/push-rect` op or an interactive `:meta/region` is only valid when the **entire enclosing transform stack at that point is translate-only**. Equivalently, `dao.gui` must not emit a clip op, interactive region, or overlay inside any subtree whose ancestors include a non-translation transform. This guarantees that all clips and hit regions resolve to exact screen-space rectangles. The compiler threads a `translate-only?` flag through its descent state to dynamically validate this constraint.

In v1, an authored raw `:matrix` is treated as opaque for this check. Even if a particular matrix numerically represents a pure translation, emitting it through `:matrix` clears the `translate-only?` flag. Authors who want to preserve clip and interaction validity must use the explicit `:translate` form rather than relying on matrix analysis.

### Layout and measurement

Layout is **two-phase**: parents pass constraints down to children, and children pass measured sizes back up.

- A parent passes constraints `{:max-width w :max-height h}` (either may be `:unbounded`). Minimum-size constraints are deferred.
- Each child computes its own size under those constraints.
- Text components compute their wrapped size by invoking the `measure-text` capability passed into the compiler. It returns measurements consistent with terminal painting, with `:position` as the **Cartesian alphabetic baseline origin**.
- The parent uses each child's measured size to compute placement transforms.

`measure-text` and `dao.postgraphics` `:draw/text` are two views of one terminal-defined text contract. The terminal family that paints a text run also defines the font resolution, shaping, baseline, ascent, descent, and line-height semantics used by `measure-text`, so compile-time layout and paint-time realization share one source of truth.

`measure-text` MUST be referentially transparent in v1: calling it with the
same arguments must return the same result for the duration of any compile that
uses it. This is part of the compile function's determinism contract.

The exact return shape of `measure-text` is application-coordinated in v1. For
portability, implementations SHOULD expose at least `:width` and `:height`,
and MAY include richer fields such as line metrics when both compiler and
terminal agree on them.

**Constraint Semantics & Overflow:** An `:unbounded` constraint means measure intrinsic size. If a child measures larger than the parent's constraints, it will visually overflow unless clipped. Sibling positioning ignores overflow and uses requested size. Layout iteration is capped at 3 passes in v1 to guarantee termination.

**Images in v1 carry explicit width and height as props.** Intrinsic-size queries are deferred.

**Font Availability:** The terminal-defined text contract must be stable within
one compile. Across recompiles, font availability may change. If a terminal
cannot guarantee the same font metrics for measurement and paint, it MUST use a
deterministic fallback metric contract, such as System Monospace, for both.
Visible reflow on a later compile after a font becomes available is allowed in
v1; hidden metric drift within one compile is not.

### Overlay layer

`dao.gui` provides an **overlay layer** as an algebraic escape hatch for tooltips, dropdowns, and modals to escape enclosing clips. Components route ops into this layer using an `(overlay child-ops)` helper, which resolves the anchored placement and returns a contribution with a self-contained bracketed sequence in its `:overlay` vector.

The overlay layer is not subtree-local. It is an explicit whole-frame portal layer with global precedence above normal flow. Nested overlays flatten into the single root overlay buffer in v1, and downstream hit precedence is computed against that final assembled layer rather than tree nesting. Overlay child ops may use clips and transforms internally, but ancestor transforms above the `(overlay ...)` call must be translate-only.

Overlay ordering must not depend on incidental subtree traversal alone. Each root overlay contribution carries explicit precedence metadata in the assembled frame. When several unrelated components emit overlays, their relative stacking is determined by that explicit precedence, not by whichever subtree happened to compile first.

In v1, there is no separate standardized overlay-helper precedence argument.
When unrelated overlay roots do not provide explicit higher-level ordering via
application conventions that the compiler understands, the compiler assigns
their relative overlay precedence by
deterministic depth-first encounter order over the authored tree and
materializes that order into explicit `:op/precedence` metadata. Traversal
order therefore remains the default source, but only after being frozen into
data rather than left implicit.

Overlay anchors are resolved during compile from the same projected geometry used for the rest of the frame. Therefore any application state that changes an anchored overlay's screen placement, including scroll offset, is render-driving state and must cause recompilation if the overlay is expected to track the moving anchor in v1.

**Performance Warning (Scroll-Anchored Overlays):**
Because overlays are resolved at compile-time, high-frequency scroll tracking
for anchored tooltips or menus requires high-frequency recompilation of the
entire component tree. Implementers should be aware of this CPU cost when
designing complex scrolling UIs with many overlays.

## Authoring vocabulary

`dao.gui` v1 supports function/closure components and a normative built-in keyword tag set: `:gui/text`, `:gui/text-input`, `:gui/button`, `:gui/toggle`, `:gui/stack`, `:gui/row`, `:gui/column`, `:gui/scroll`, `:gui/image`, `:gui/list`, `:gui/table`, `:gui/overlay`. Function components are the portable authoring surface in v1.

**Authoring Scope:** Expresses the visual structure of 2D productivity apps and 2D game UI. Input dispatch, focus traversal, and accessibility are outside of `dao.gui`'s paint-time scope.

`:gui/scroll` in v1 is a clipping and offsetting authoring construct, not a
complete interactive scroll subsystem. User-driven scrolling is deferred; only
programmatic offset changes via application state are defined.

## Architecture & V1 Constraints

These accepted defaults and design rules define the architectural bounds for `dao.gui` v1:

- `dao.gui` compiles hiccup-shaped component models into `dao.postgraphics` frame programs via a pure compile function `(form, props, db, capabilities) → {:frame [...] :diagnostics [...]}`.
- Application state lives in `dao.db`, accessed mutably only by application event handlers emitting tx-data.
- **Separated Identity:** Components use domain-stable `:gui/id` values for state retention and `:node-id` for interaction.
- Layout solving lives inside `dao.gui`, is two-phase (constraints down, sizes up), and caps at 3 passes. Text measurement is an explicit pure capability using a shared terminal-defined text contract rooted at the **alphabetic baseline**. Images require explicit width and height.
- The structure is preserved using the VM's transform stack, restricted to **translate-only** placements emitted by layout. `:clip/push-rect` stays in local/current transform space; the compiler does not flatten clips into absolute screen-space, and the VM resolves them against the active transform stack.
- The compiler enforces a **translate-only ancestry invariant** for clips, overlays, and interactive regions via a threaded flag.
- **Explicit Hit Precedence:** Interactive targets carry explicit precedence metadata derived from final visual stacking, so hit routing does not depend on sibling-overlap prohibitions or incidental compiler traversal order.
- **Whole-Program Overlay Assembly:** Overlays flatten into a single post-flow layer with explicit precedence metadata; local subtree changes do not determine overlay stacking by traversal accident.
- Output uses a layered contribution `{:flow [...] :overlay [...]}`; the overlay is assembled as a post-flow layer without side-channel mutation.
- The application owns the render loop, pipeline wiring, and state. There is no intermediate scene-graph stage and no Reagent-like reactive state within the components.
