# Design: `dao.gui.event`

## Summary

`dao.gui.event` semantics do not belong in `dao.gui`, and do not belong in
`dao.postgraphics`.

`dao.gui` is fixed as a pure compiler from Hiccup-like authored UI to
`dao.postgraphics` frame programs. The terminal is the runtime that renders
those frames and is also the place where host-native input first appears.

`dao.gui.event` is a separate runtime layer downstream of terminal
presentation. In v1 it handles only tap events.

It is responsible for:

- consuming terminal-emitted presented geometry for a specific frame
- deriving a frame-local hit index from that geometry
- consuming terminal-emitted tap input
- adapting tap coordinates into GUI Cartesian coordinates when needed
- hit-testing taps against the active hit index
- notifying subscribers that have declared interest in a node id and event kind

It is not responsible for:

- changing the `dao.gui` compile contract
- embedding event semantics in `dao.postgraphics`
- painting pixels directly
- turning `dao.gui` into a stream runtime
- introducing retained-scene semantics into `dao.gui`

## Layering

The layers remain:

```text
[dao.gui compiler]
   │ emits dao.postgraphics frame program
   ▼
[terminal]
   ├─ presents frame
   ├─ emits presented-frame geometry
   └─ emits native tap input
   ▼
[dao.gui.event runtime]
   ├─ derives frame-local hit index
   ├─ normalizes tap coordinates into GUI space
   ├─ hit-tests taps
   └─ dispatches tap signals to subscribers
   ▼
application
```

Key point:

- `dao.gui` compiles authored UI into a frame program
- the terminal is authoritative for what was actually presented
- presented geometry is only final after presentation
- `dao.gui.event` interprets that presented geometry to build hit regions
- subscribers are notified downstream of explicit hit-testing

Neither `dao.gui` nor `dao.postgraphics` absorbs these runtime concerns.

## Why Not `dao.gui`

`dao.gui.md` fixes `dao.gui` as a pure compiler. `dao.gui.event` must stay
outside that boundary because terminal input, presented geometry, and
subscription dispatch are runtime concerns that occur after compilation and
presentation.

`dao.gui` produces the frame program, then downstream runtime layers take over.
`dao.gui.event` is one of those downstream layers.

## Why Not `dao.postgraphics`

`dao.postgraphics` remains rendering bytecode plus graphics VM contract. It
should stay about:

- draw ops
- transform ops
- clip ops
- viewport semantics
- graphics VM execution

Tap dispatch, subscriber routing, hit-testing policy, and frame-local hit
indexes are not rendering bytecode concerns. They are runtime interpretation
above the graphics VM.

## Event Boundary

The stable v1 boundary is:

- presented geometry in
- tap input in
- subscriber-interest declarations in
- tap dispatch out

Concretely, the `dao.gui.event` runtime accepts:

- terminal-emitted geometry for a presented frame generation
- terminal-emitted tap input
- a subscriber registry keyed by node id and event kind

and produces:

- tap dispatch to matching subscribers
- optional emitted signals or stream values, if the application chooses that
  integration style

The important point is that subscriber notification happens after explicit
hit-testing against explicit geometry derived from a frame the terminal has
already presented.

## Identity Propagation

Interactive identity originates in authored Hiccup.

The downstream contract is:

- authored UI declares stable node identity
- `dao.gui` lowers that identity into `dao.postgraphics` op metadata; for
  `dao.gui`-authored interactive targets in v1, `:interactive-events` is
  lowered onto the dedicated `:meta/region` op only, while draw ops may carry
  `:node-id` for provenance but never `:interactive-events`
- for `dao.gui`-authored interactive targets in v1, `dao.gui` also lowers one
  authoritative `:meta/region` per `(node-id, event-kind)`
- the terminal preserves that metadata while presenting the frame
- terminal-emitted presented geometry reports the same node identity
- `dao.gui.event` dispatches subscriber-interest against that reported identity

`dao.gui.event` does not invent node ids and the terminal does not reconstruct
them from paint geometry alone. Identity is carried downstream explicitly.

## Presented Geometry

Presented geometry is authoritative only after the terminal has actually
presented the frame.

This is the causal rule:

```text
frame program -> terminal presentation -> presented geometry -> hit index
```

Not:

```text
planned frame -> speculative geometry -> hit index
```

This avoids a race where a future frame's hit regions become active before that
frame is visible on screen.

So the terminal should:

- present frame `N`
- resolve the geometry for the actually presented frame `N`
- if frame `N` contains explicitly interactive metadata for v1, emit the
  resulting interactive geometry tagged with frame id `N`
- if frame `N` contains no explicitly interactive metadata for v1, emit an
  explicit empty geometry value (`{:frame-id N :nodes []}`) for frame `N`, so stale hit regions are cleared
- if a submitted frame is rejected by the graphics VM before presentation,
  there is no new presented-frame generation. The terminal MUST emit a
  **Frame Rejection Signal** (`{:message/kind :dao.terminal/rejection :submission-id N :reason reason}`), where `N` is the rejected submission's terminal-local ingress sequence number. `dao.gui.event`
  continues to use the geometry and hit index of the last successfully
  presented frame, because event state advances only when presentation
  advances. This ensures interactive truth remains anchored to the visible
  stale pixels.
- presented-frame identity is scoped to one terminal instance. A VM reset
  signal is therefore a hard generation boundary, not merely a dropped-frame
  notification

Then `dao.gui.event` should:

- consume that presented-geometry value
- derive the active hit index for the same frame id

The active hit index must always correspond to the last successfully presented
frame.

## Transport

`dao.stream` is the canonical integration shape for terminal-emitted geometry
and terminal-emitted input.

Conceptually:

- a frame stream carries compiled `dao.postgraphics` frames
- a geometry stream carries terminal-emitted presented geometry for interactive
  targets, or an explicit empty-hit-generation value when the presented frame
  has no interactive targets
- an input stream carries terminal-emitted tap events
- an optional downstream event stream may carry dispatch results

Wire-level discrimination is explicit in v1: geometry and tap values carry no
`:message/kind`; auxiliary protocol signals always do.

Presented-frame IDs and `submission-id` values are independent monotonic
counters in v1. Presented-frame IDs advance only on successful presentation;
submission IDs advance on terminal ingress.

Direct function-call wiring is still possible, but the design center is
stream-shaped because it preserves explicit causality and keeps the terminal and
event runtime decoupled.

Sequential stream ordering alone is not enough. The required invariant is
**frame-causality**:

- geometry for frame `N` must be installed before any tap attributed to frame
  `N` is dispatched
- if geometry and tap input travel on separate streams, tap events must carry a
  presented-frame id
- terminals MUST emit presented geometry for frame `N` before emitting any tap
  tagged with frame `N`, regardless of whether geometry and taps travel on one
  combined stream or separate streams
- `dao.gui.event` must not dispatch a tap for frame `N` until geometry for
  frame `N` is active
- if geometry and tap input travel on one stream, the stream order must still
  satisfy the same rule: geometry event for frame `N` before any tap event for
  frame `N`
- a well-behaved terminal tags each tap with the frame generation that was
  actually visible when that tap was recognized; future-frame taps are therefore
  a protocol error rather than a normal waiting case
- if a transport violates frame order across split streams, `dao.gui.event`
  does not repair that by inference. Out-of-order geometry or taps are terminal
  protocol errors and should be dropped or surfaced diagnostically rather than
  rebound to a different frame generation
- a tap for a frame that never became a presented generation is likewise a
  protocol error and must be dropped immediately rather than buffered

`dao.stream` gives sequential ordering. This design additionally requires
cross-event causality by frame generation.

## Geometry Shape

The terminal emits presented geometry as plain data. The exact schema is an
implementation choice, but it must support derivation of interactive screen
regions for a specific presented frame.

Geometry emission is conditional, not universal:

- if a presented frame contains no explicit interactive metadata for v1, the
  terminal need not emit region-bearing geometry for that frame, but it must
  still advance hit-state generation with an explicit empty geometry value (`{:frame-id N :nodes []}`)
- if a presented frame contains explicit interactive metadata for v1, the
  terminal must emit geometry for the interactive targets that survive
  presentation, clipping, and v1 geometry restrictions

The emitted geometry must already be normalized into the same Cartesian event
space used by `dao.gui.event`. Backend-native coordinate systems do not cross
the terminal boundary.

Illustrative shape:

```clojure
{:frame-id 42
 :nodes
 [{:node-id ::save-button
   :event :tap
   :regions [{:bounds {:x 24 :y 16 :width 96 :height 32}
              :paint-order 17}]}]}
```

This shape is illustrative, not normative. The invariants are:

- the geometry value is data, not callbacks
- it is tagged to exactly one presented frame generation
- it includes authored node identity preserved through `dao.postgraphics`
  metadata
- it includes only nodes whose metadata declares interactive interest for v1
- it carries enough realized screen geometry to derive rectangular hit regions
- emitted bounds reflect the final visible screen-space result after clipping,
  not merely logical authored bounds
- emitted bounds are expressed in GUI Cartesian screen coordinates, with origin
  at the bottom-left and `y` increasing upward
- non-drawing `:meta/region` ops are also eligible geometry sources when they
  carry interactive metadata
- it preserves deterministic visual precedence information matching final
  presented paint order
- node ids are stable enough for subscriber lookup

Consequences:

- the terminal emits geometry only for nodes that are explicitly interactive in
  v1 and that contribute either visible presented geometry or explicit
  non-drawing `:meta/region` geometry
- a frame with no explicitly interactive nodes installs an empty hit generation
- a node fully clipped out of the presented frame emits no interactive region
- a partially clipped node emits only its visible remainder (Note: `:meta/region` hit slop is also subject to ancestor `:clip/push-rect` bounds and cannot extend outside a scroll view)
- hit-testing uses presented visible geometry, not unclipped logical bounds

## Region Aggregation

One authored interactive node may contribute multiple graphics ops to the same
presented frame.

For `dao.gui`-authored interactive nodes specifically, the explicit
`:meta/region` is authoritative in v1. Terminals should derive the target's hit
geometry from that `:meta/region`, not from re-aggregating the node's painted
primitives. The more general aggregation rules below remain available to other
`dao.postgraphics` producers that do not come from `dao.gui`.
The terminal heuristic is:

- if a `(node-id, event-kind)` has an explicit interactive `:meta/region` and
  no painted ops carry `:interactive-events` for that same key, that
  `:meta/region` is authoritative for that target
- otherwise, the terminal falls back to the general aggregation rules for
  painted contributions

For v1, terminal-emitted geometry aggregates by `(node-id, event-kind)`:

- for generic producers, all visible painted contributions and explicit
  `:meta/region` contributions carrying the same `node-id` and event kind
  belong to the same interactive target
- aggregation happens after clipping and after final paint resolution
- if those visible contributions form several disjoint axis-aligned rectangles,
  the terminal should emit several regions for that same node id
- if those visible contributions can be represented exactly as one
  axis-aligned rectangle, the terminal may emit one region
- the terminal must not inflate several disjoint visible areas into one larger
  rectangle that covers pixels the node did not actually paint
- explicit `:meta/region` contributions are additive for generic producers,
  but for `dao.gui`-authored interactive targets, the `:meta/region` is
  **substitutive**: it is the sole authoritative source of hit geometry, and
  painted contributions with the same identity are ignored for hit-testing
  purposes (consistent with the rule that `dao.gui` draw ops MUST NOT carry
  `:interactive-events`)

So the conceptual shape is:

```clojure
{:frame-id 42
 :nodes
 [{:node-id ::save-button
   :event :tap
   :regions [{:bounds {:x 24 :y 16 :width 96 :height 32}
              :paint-order 17}]}]}
```

V1 semantics are region-per-visible-rectangle, grouped by `(node-id, event-kind)`.

When one logical target contributes several regions with different final paint
orders, those regions retain their individual `:paint-order` values. The
terminal must not collapse them into one target-level order that loses overlap
information.

More generally, regions sharing a `node-id` and event kind may be coalesced
only when coalescing preserves both:

- the exact covered area
- the effective paint precedence of that area relative to all other interactive
  geometry

If coalescing would erase precedence distinctions that matter for topmost-wins
hit-testing, the terminal must emit the regions separately.

For explicit `:meta/region` contributions, `:paint-order` is derived from
explicit precedence metadata when present. For `dao.gui`-authored targets,
that precedence metadata is mandatory and represents the target's visual
stacking in the fully assembled frame. Generic producers may omit explicit
precedence metadata, in which case terminals fall back to bytecode op order.

Across mixed producers, terminals MUST normalize precedence into one effective
precedence scale for the whole presented frame using the **Canonical Precedence
Formula** defined in `dao.postgraphics.md`:

`effective-z = (metadata-precedence << 32) | bytecode-index`

This formula ensures that structured layers (established by metadata) always
outrank generic bytecode, while bytecode order remains the stable internal
tie-breaker within any given layer.

## Hit Index

`dao.gui.event` derives a frame-local hit index from presented geometry.

The hit index is:

- dynamic
- ephemeral
- replaced when presented geometry changes
- optimized for hit-testing

Its concrete representation is an implementation choice.

Possible representations include:

- a simple vector of rectangular regions for small region counts
- a z-sorted vector for topmost-first hit testing
- a map keyed by node id for subscriber association
- a spatial hash, grid, or similar structure if region counts grow

The semantic source of truth is the presented geometry, not the hit index
itself.

## Coordinate Contract

`dao.gui.event` uses the same Cartesian coordinate system as
`dao.postgraphics`:

- origin at viewport bottom-left
- `x` increases right
- `y` increases up

The terminal is responsible for adapting both:

- native input into that space before `dao.gui.event` consumes it
- emitted presented geometry into that same space before it leaves the terminal
  boundary

No terminal-native coordinate system should leak upward into event semantics.

## Hit Geometry

V1 is deliberately narrow:

- tap only
- rectangular hit regions only
- screen-space resolved regions only
- exact hit-testing only

Because `dao.gui.md` allows visual non-translation transforms inside authored
components, this document must stay explicit about interaction geometry:

- a v1 interactive region is valid only when the presented geometry resolves to
  an exact axis-aligned screen-space rectangle
- clipping is resolved before emission, so the region represents only the
  visible screen-space remainder
- translate-only ancestry is always valid
- if visual realization would make the interactive area non-rectangular or
  otherwise inexact in screen space, that node is not interactive in v1 and
  the terminal should emit a warning diagnostic identifying the node id and
  reason, then omit that node from emitted interactive geometry

Axis alignment is evaluated with a shared tolerance of **`1e-6`** for all
coordinate resolution. V1 terminals MUST treat sub-pixel deviations from
pure translation as still axis-aligned when they are within this epsilon.
This ensures interaction doesn't break due to infinitesimal floating-point
noise in transform realization.

Rectangular hit-testing in v1 uses a **half-open boundary convention**:
`[x, x + width) × [y, y + height)`. The lower-left corner is inclusive; the
upper and right edges are exclusive. Two abutting rectangles therefore do not
both claim the shared upper or right edge — each pixel along a shared edge
belongs to exactly one rectangle.

Exact axis-aligned geometry may come from either:

- visible painted contributions after transform and clipping
- explicit `:meta/region` ops after transform and clipping

V1 chooses correctness over approximate hit-testing.

## Ordering And Overlap

Overlap policy must match what the user sees on screen.

For v1:

- hit precedence is determined by explicit visual ordering in the presented
  geometry
- the topmost eligible region at the tap point wins
- if two regions overlap, later paint precedence wins

The precedence source is not arbitrary. It must correspond to the final
presented paint order of the assembled `dao.postgraphics` frame program,
including `dao.gui`'s `flow ++ overlay` assembly rule.

This matters because `dao.gui.md` defines a post-flow overlay layer. A region
associated with a visual overlay must also outrank the regions it visually
covers. Tree nesting alone is not a sufficient rule.

For nested interactive identities:

- a node owns only the visible painted contributions whose ops carry that same
  node's identity
- a parent interactive node does not implicitly own pixels painted by an
  interactive child with a different node id
- if parent and child both emit interactive geometry and overlap, normal
  presented paint precedence decides the winner at the tap point

Interactive `node-id` values are scoped to one `dao.gui` root / one
`dao.gui.event` instance. Authors use `:node-id` exclusively for interaction;
persistent widget state (scroll position, etc.) is keyed separately by `:gui/id`
in the application state. For `dao.gui`-authored interactive targets in v1,
duplicate `(node-id, event-kind)` values referring to different logical targets
within one presented frame are a compiler error. Aggregation of multiple regions
for one `(node-id, event-kind)` is valid only when those regions belong to the
same logical target.

A `node-id` may be any EDN value with stable equality semantics. Namespaced
keywords are recommended for portability and readability.

This uniqueness guarantee is only enforceable within one producer's completed
frame artifact. If an application mixes `dao.gui` output with geometry from
other producers, cross-producer `node-id` namespace coordination is an
application responsibility.

For `dao.gui`, detecting that duplicate-target error is a compiler obligation.
Because collisions may arise in unrelated subtrees, the compiler performs this
check in a post-pass over the assembled interactive `:meta/region` ops for the
completed frame.

## Subscriber Model

Dispatch is subscription-driven.

Applications declare subscriber-interest in a node id and an event kind. In
v1 the event kind is just `:tap`.

Conceptually:

```clojure
{[::save-button :tap] [subscriber-a subscriber-b]
 [::cancel-button :tap] [subscriber-c]}
```

This shape is illustrative. The invariants are:

- subscriptions are keyed by stable node id and event kind
- registering a subscriber for an unrecognized event kind in v1 is allowed but
  dormant; the runtime should emit a warning diagnostic at registration time
- dispatch is explicit lookup, not implicit callback execution hidden in frame
  data
- zero, one, or many subscribers may exist for a given `(node-id, event-kind)`
- if no subscriber is registered, the tap is ignored after hit-testing
- when many subscribers are registered for the same `(node-id, event-kind)`,
  they are invoked synchronously in registration order. A long-running subscriber will block subsequent dispatch on later taps
- taps that arrive while a subscriber dispatch is still running are governed by
  upstream `dao.stream` backpressure and eviction policy in v1
- regardless of upstream buffering policy, `dao.gui.event` MUST NOT rebind a
  delayed tap to newer geometry after the fact
- when upstream policy reports or otherwise makes visible that a tap was evicted
  before dispatch, `dao.gui.event` MUST emit `:dao.gui.event/dispatch-busy`
  so the drop is explicit to the application
- duplicate registrations are preserved in registration order; registering the
  same subscriber twice causes two notifications
- the subscriber list for one dispatch is snapshotted at dispatch start;
  additions or removals made by a subscriber take effect only on later dispatches
- unsubscribe, when provided by an implementation, removes one registration
  instance at a time; API surface for registration ownership remains
  application-defined in v1
- one subscriber failure should not prevent later subscribers from being
  notified; failures are surfaced separately as diagnostics or error events
- tearing down one `dao.gui.event` instance releases its subscriber registry
  after any already-snapshotted dispatch completes
- if terminal-emitted geometry or metadata carries unrecognized event kinds in
  v1, `dao.gui.event` filters them out, emits a warning diagnostic, and
  continues processing any recognized kinds such as `:tap`

This synchronous dispatch rule is intentional in v1. Subscriber invocation is
part of the explicit causal chain, not a hidden queued runtime. Authors should
keep subscribers small and non-blocking; expensive work should hand off to a
stream or separate worker boundary explicitly.

## Diagnostics

Diagnostics are explicit data, not implicit side effects.

When this design says a terminal or `dao.gui.event` runtime should emit or
surface a diagnostic, the canonical form is a diagnostic event on a dedicated
stream or equivalent explicit callback boundary.

Normal control-flow values are not diagnostics. In particular, an explicit empty
geometry / empty-hit-generation update for a non-interactive frame is ordinary
state advancement, not a warning or error.

The normative wire shape for v1 is:

```clojure
{:diagnostic/kind :dao.gui.event/unsupported-region
 :severity :warning
 :frame-id 42
 :node-id ::save-button
 :reason :non-rectangular-screen-geometry}
```

`:frame-id` is optional on diagnostics that do not arise from one concrete
presented frame, such as registration-time warnings. Diagnostic kinds use the
consumer-layer namespace (`:dao.gui.event/...`) regardless of whether the
terminal or the event runtime emitted the value.

Normative diagnostic kinds in v1 include:

- `:dao.gui.event/unsupported-region` — warning; a would-be interactive region
  could not be represented as an exact v1 rectangle
- `:dao.gui.event/dispatch-busy` — error; a later tap was backpressured or
  dropped before `dao.gui.event` could dispatch it because synchronous
  subscriber dispatch was still running and upstream `dao.stream` policy
  applied backpressure or eviction
- `:dao.gui.event/unrecognized-event-kind` — warning; registration or emitted
  metadata referenced an event kind not supported in v1
- `:dao.gui.event/no-active-frame` — error; a tap arrived before any frame in
  the current generation had become active and was dropped
- `:dao.gui.event/future-frame-tap` — error; a tap carried a presented-frame ID
  greater than the active frame in the current generation
- `:dao.gui.event/stale-frame-tap` — error; a tap carried a presented-frame ID
  older than the active frame in the current generation and was dropped
- `:dao.gui.event/out-of-order-frame-events` — error; geometry or tap events
  arrived in an order that violates frame-causality within one generation
- `:dao.gui.event/stale-generation-tap` — error; a tap referred to a generation
  that had already been superseded by a VM Reset Signal

The invariants are:

- diagnostics are emitted as explicit data values
- diagnostics do not change rendering semantics
- warning diagnostics do not abort frame presentation or subscriber dispatch by
  themselves
- in v1, the only severities are `:warning` and `:error`
- `:warning` preserves normal control flow
- `:error` indicates a dropped event or omitted interactive target, but still
  does not retroactively invalidate an already presented frame
- dropped taps, omitted unsupported regions, and subscriber failures may all be
  surfaced through this same diagnostic boundary

This preserves the project's invariants:

- event routing is data-driven
- causality is explicit:
  frame -> presentation -> geometry -> hit index -> tap -> subscriber
  notification
- `dao.gui` stays pure

## Tap Semantics

V1 tap semantics are intentionally concrete:

- the terminal recognizes a host-native tap gesture
- the terminal emits one tap event for that recognized gesture
- the tap event is tagged with the presented frame id it belongs to
- the normative tap wire shape in v1 is
  `{:frame-id <int> :position {:x <number> :y <number>}}`, where `:position`
  is in `dao.gui.event`'s Cartesian coordinate space (origin bottom-left,
  `y` upward)
- `dao.gui.event` receives that tap event, not a down/up public sequence
- before any frame has been successfully presented in the current generation,
  the active hit index is empty and all taps are dropped with an
  diagnostic of kind `:dao.gui.event/no-active-frame`
- tap dispatch follows a strict three-sided rule against the active geometry frame:
- `tap.frame == active`: dispatch immediately.
- `tap.frame > active`: treat as a terminal protocol error, emit a diagnostic of
  kind `:dao.gui.event/future-frame-tap` or a **Protocol Error Signal**, and
  drop immediately.
- `tap.frame < active`: drop with a diagnostic of kind
  `:dao.gui.event/stale-frame-tap` (never rebound to newer geometry).
- well-formed terminal integrations do not normally produce `tap.frame > active`, because taps are tagged with the frame that was actually visible when the gesture was recognized.
- if the terminal / VM instance restarts, any in-flight taps carrying frame IDs
  from the previous instance are dropped with a diagnostic; VM-assigned frame
  IDs are not stable across restarts
- after a VM restart the active hit index is empty until the new VM emits
  geometry for its first presented frame; taps during that interval are dropped
  under the same three-sided rule
- if the viewport changes after frame `N` was presented, a tap tagged with
  frame `N` still dispatches against frame `N`'s geometry and coordinate space;
  later resizes affect only later presented frames
- after a VM restart, recovery requires a newly presented valid frame. The
  event runtime does not synthesize continuity from pre-restart generations
- a **VM Reset Signal** (`{:message/kind :dao.terminal/reset :generation-id <string-uuid>}`) clears the active
  presented-frame generation immediately; after reset, comparison against
  pre-reset frame IDs is invalid. V1 does not define a causal tap buffer, so
  there is no buffered tap state to preserve across reset.
- `generation-id` is an opaque namespace token chosen by the terminal. Consumers
  use it only to distinguish one presented-frame namespace from another. In
  effect, a terminal-scoped frame identity is `(generation-id, frame-id)`, but
  v1 transports that identity as one reset token plus later integer `frame-id`
  values. Geometry and taps compare only by `frame-id` within the current
  generation; `generation-id` changes only on reset boundaries
- the **Protocol Error Signal** drives concrete state in v1 according to its
  `:error/kind`: `:future-frame-tap` is dropped immediately under the
  three-sided rule, while `:out-of-order-frame-events` and
  `:stale-generation-tap` are treated as terminal protocol faults and surfaced
  as `:dao.gui.event/out-of-order-frame-events` and
  `:dao.gui.event/stale-generation-tap` diagnostics respectively, without
  rebinding any tap or geometry to a different frame or generation
- the **Frame Skipped Signal** (`{:message/kind :dao.terminal/frame-skipped :submission-id <int>}`) is informational in v1. `dao.gui.event` keeps no buffer of pending taps awaiting a future frame and maintains no `submission-id`-to-`frame-id` mapping, so the signal triggers no state transition inside the event runtime. It exists to make a gap in the terminal's submission sequence visible to upstream observers (frame producers, diagnostics consumers) that may correlate submission IDs with their own bookkeeping
- a **Frame Skipped Signal** is emitted when the terminal's submission sequence
  advances past frame submission `N` without ever presenting it, for example
  due to coalescing, backpressure, or replacement by a newer submission before
  presentation. After that signal, geometry for `N` can never arrive in v1
- dispatch targets the single highest-precedence region containing the tap point
- dispatch kind is `:tap`

Drag, hover, capture, keyboard routing, and focus are deferred.

## Relationship To the Render Loop

The application drives the render loop. As defined in `dao.gui.md`, the
application decides when to compile and hands completed frame programs to
the terminal.

`dao.gui.event` composes downstream of that boundary:

- `dao.gui` emits frame programs
- the terminal presents them
- the terminal emits presented geometry for interactive targets, or an explicit
  empty-hit-generation update when the presented frame has none, and emits tap
  input when host-native taps occur
- `dao.gui.event` consumes those downstream values
- `dao.gui.event` derives the hit index and dispatches to subscribers

This keeps the contracts aligned:

- compilation remains deterministic
- terminal rendering remains terminal-specific
- event dispatch remains a separate runtime concern

## V1 Scope

V1 solves only:

- terminal-originated tap events
- post-presentation geometry emission
- frame-local rectangular hit regions
- dynamic hit-index derivation
- Cartesian coordinate normalization
- subscription lookup by node id and event kind
- deterministic topmost-wins dispatch

Deferred:

- pointer down / up as separate public events
- drag
- hover
- enter / leave
- focus traversal
- pointer capture
- keyboard routing
- gesture recognition beyond terminal-recognized tap
- non-rectangular hit regions
- accessibility semantics

## Design Rules

- keep `dao.gui` as a pure compiler from authored UI to `dao.postgraphics`
- keep `dao.postgraphics` as rendering bytecode only
- treat terminal presentation as the point where geometry becomes
  authoritative
- have the terminal emit presented geometry tagged to a frame generation
- consume that geometry downstream in `dao.gui.event`, canonically via
  `dao.stream`
- derive a dynamic frame-local hit index from presented geometry
- represent geometry and hit data as plain data, not callbacks
- dispatch by subscription interest in `(node-id, event-kind)`
- **Pinned Precedence:** Use the **Canonical Precedence Formula** (`metadata << 32 | index`) for all hit routing.
- **Axis-Alignment Epsilon:** Reject interaction geometry only when deviations from pure translation exceed **`1e-6`**.
- **Canonical Signaling:** Use normative EDN message shapes for Reset, Rejection, Protocol Error, and Frame Skipped signals.
- **Boundary Convention:** Hit-testing uses half-open rectangles.
- preserve explicit causality from frame delivery through tap dispatch

## Accepted Defaults

These choices are fixed for v1:

- `dao.gui.event` is not part of `dao.gui`
- `dao.gui.event` is not part of `dao.postgraphics`
- the terminal is where native events first appear
- the terminal is authoritative for presented-frame geometry
- presented geometry is emitted after presentation, not before
- `dao.stream` is the canonical transport for geometry and tap events
- taps are frame-tagged and follow the strict three-sided dispatch rule
- the only public event kind is `:tap`
- hit regions are derived from presented geometry normalized to a shared **`1e-6`** epsilon
- the hit index is dynamic runtime data ordered by the **Canonical Precedence Formula**
- subscriptions are keyed by node id and event kind
- dispatch is topmost-wins among hit regions at the tap point
