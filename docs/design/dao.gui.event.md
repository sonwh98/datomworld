# Design: `dao.gui.event`

## Summary

`dao.gui.event` is a portable event interpreter built on DaoStream v2. In the
demo implementations, those channels are bounded `dao.stream.ringbuffer`
streams (evict-oldest). This is a direct application of Datom.world's first
axiom, "everything is a stream": host observations, presented geometry, input
profiles, timers, subscriptions, control messages, recognized events, and
diagnostics are all values carried by streams.

It consumes presented interaction geometry, normalized pointer and keyboard
events, terminal input profiles, timer results, and subscription commands from
explicit input streams. It interprets those ordered values as immutable state
transitions, then emits targeted pointer values, keyboard values, recognized
gesture values, arena decisions, and diagnostics onto explicit output streams.
The application does not call a recognizer directly and the recognizer does
not call application callbacks. Meaning is produced at the stream boundary by
an interpreter.

This makes `dao.gui.event` more than a gesture helper or callback registry. It
is a stream-to-stream interpreter: low-level observations enter as data,
recognizer machines and arbitration interpret them, and semantic interaction
values leave as data. Runtime sequence numbers, timestamps, generations, and
coordinate spaces preserve causality while the DaoStream ring buffer provides
the bounded transport, retention, and eviction boundary.

The terminal is responsible for observing host-native input. It does not define
portable gesture semantics. Android, iOS, Flutter, and mobile web all expose
different gesture facilities, but they can provide the same lower-level facts:
pointer identity, contact lifecycle, position, time, pressure, contact geometry,
and cancellation. `dao.gui.event` interprets those facts through explicit
recognizer-machine data.

The standard portable gesture vocabulary is:

- tap and repeated tap, including double tap
- long press
- pan / drag
- swipe and fling
- scale / pinch
- rotation
- combined translation, scale, and rotation
- edge pan
- pressure press when the device reports pressure

Custom recognizers use the same finite-state-machine data model as the standard
recognizers. Targeted raw pointer streams remain available when an interaction
cannot be expressed by the standard machines.

This contract cannot make application input out of events reserved by the host.
Examples include operating-system navigation gestures and browser chrome
gestures. When the host takes ownership after contact begins, the terminal emits
`:cancel`; when the host intercepts before application delivery, no application
pointer sequence exists.

## Layering

```text
[dao.gui compiler]
   │ emits dao.postgraphics frame program with interaction metadata
   ▼
[terminal]
   ├─ presents frame
   ├─ emits presented interaction geometry
   ├─ emits an immutable input profile
   ├─ normalizes host pointer and keyboard events
   └─ emits terminal/reset/input-loss/space-change signals
   ▼
[dao.gui.event runtime]
   ├─ constructs explicit hit paths and gesture arenas
   ├─ captures pointer sequences to their initial paths
   ├─ interprets recognizer-machine data
   ├─ consumes explicit timer results
   └─ emits targeted pointer, keyboard, gesture, and diagnostic values
   ▼
[application streams]
```

The separation is strict:

- `dao.gui` remains a pure compiler.
- `dao.postgraphics` remains rendering bytecode plus inert metadata carriage.
- the terminal owns host observation, presentation, and coordinate adaptation.
- `dao.gui.event` owns portable hit-testing, recognition, arbitration, capture,
  and dispatch interpretation.
- the application owns state updates and recompilation.

No frame contains callbacks. No terminal callback is a portable event API. No
gesture recognizer owns hidden global state.

### Implementation Scope

This revision defines the complete end-to-end architecture, but the current
implementation conformance claim is limited to `dao.gui.event` interpretation,
replay, and DaoStream binding over canonical runtime inputs.

The following upstream boundaries are specified but deferred:

- lowering authored `:gui/gestures`, `:on-tap`, and `:gui/touch-action` data into
  canonical `:meta/region` metadata in `dao.gui`;
- terminal production of presented geometry, input profiles, normalized pointer
  and keyboard events, coordinate-space signals, and browser policy overlays;
- Android, iOS Flutter, and mobile-web host adapters.

Consequently, this implementation does not claim that authored Hiccup currently
reaches `dao.gui.event`, nor that any existing terminal produces the canonical
runtime-input stream. Tests for the implemented scope construct canonical
metadata and runtime inputs directly. The deferred boundaries remain normative
requirements for a later end-to-end conformance claim.

## Event Boundary

One `dao.gui.event` binding consumes these streams:

- presented geometry
- terminal input profile and auxiliary terminal signals
- normalized pointer packets
- normalized keyboard events
- recognizer timer results
- subscriber registration commands

It produces these streams:

- timer requests
- contact-change, arena-merge, and arena-decision trace values
- targeted pointer dispatch
- targeted keyboard dispatch
- recognized gesture dispatch
- diagnostics

An implementation may multiplex these values onto fewer physical DaoStreams,
but every value carries a discriminator and the ordering rules in this document
still apply. Stream transport does not replace frame, generation, input-sequence,
or coordinate-space identity.

### Binding Contract

The public constructor is data-oriented. `bind` creates no ambient singleton and
does not register host callbacks:

```clojure
{:dao.gui.event/binding-version 2
 :inputs {:runtime-input runtime-input-stream}
 :outputs {:effects effect-stream
           :trace trace-stream
           :pointer pointer-stream
           :keyboard keyboard-stream
           :gesture gesture-stream
           :dispatch dispatch-stream
           :diagnostic diagnostic-stream}}
```

A binding contains its input stream and read cursor, the seven output streams, its
immutable interpreter state, an ordered pending-output queue, teardown state,
and the identity of any current parked interval. It creates no host thread and
registers no callback or waiter.

The public driver is:

```clojure
(advance binding)
;; =>
{:binding next-binding
 :status :advanced}   ; or :parked, :blocked, :end, :input-gap,
                      ; :transport-error, :closed
```

Before reading input, `advance` retries pending output in order. If the queue is
fully flushed, it reads at most one runtime input through a DaoStream `next`
outcome map. On `:dao.stream/ok` it consumes the observed value exactly once,
retains the successor cursor even if output subsequently parks, steps the
reducer, queues the resulting ordered outputs, and attempts to flush them. It
never reads a second input during the same call. An embedding runtime drives
progress by calling `advance` repeatedly.

The binding's read cursor is minted by the input stream, never fabricated:
`bind` accepts a caller-supplied `:cursor`, and otherwise the first `advance`
mints `:dao.stream/oldest` — origin observation from that mint is a timing
discipline, not a guarantee `bind` itself makes; see below. `recover-input-gap`
takes the
recovery cursor an `:input-gap` result carried, or any cursor the host minted
on the input stream; a caller never constructs cursor internals itself.

The origin-cursor guarantee is a timing discipline, not a property of `bind`
itself: it holds only when the caller either supplies a cursor minted before
production begins, or calls the first `advance` before production begins. A
binding whose first `advance` happens after values have already been produced
and evicted mints `:dao.stream/oldest` at the earliest *retained* position and
silently observes the surviving suffix, with no gap reported — a fresh oldest
cursor is a valid cursor onto that suffix, and nothing is wrong with it.
Detecting complete history is the composition's cursor-minting discipline
(see `dao.stream.md`, *Cursors* and *Complete history*), not something a
binding created after the fact can supply.

`:advanced` means one input was consumed and all outputs currently pending from
it were appended. `:parked` means the head pending output could not be appended.
`:blocked` means the input stream returned `:dao.stream/blocked`. `:end` means
it returned `:dao.stream/end` before teardown. `:input-gap` means it returned
`:dao.stream/gap`; that result also carries `:recovery-cursor`, the cursor the
gap outcome carried, while the binding's own cursor does not advance.
`:transport-error` means it returned `cursor-mismatch`, `invalid-cursor`, or
`transport-error` — a binding that retried a dead cursor forever would spin, so
the terminal read outcomes surface as their own status, and the status is
retained: later `advance` calls return `:transport-error` without reading
again, until `recover-input-gap` re-establishes the input lane. `:closed` means teardown
output has been completely flushed and all runtime-owned output streams have
been closed.

An input-stream `:end` is not implicit teardown. The binding retains its state
and leaves its outputs open, although a closed input stream cannot subsequently
supply another value.

`runtime-input-stream` carries only the canonical envelope below. The binding
owns its immutable interpreter value; each consumed input maps it to a new
interpreter value and an ordered vector of output values. An embedding runtime
may expose that total reducer directly for replay:

```clojure
{:state next-state
 :outputs [effect-or-pointer-or-keyboard-or-gesture-or-dispatch-or-diagnostic]}
```

Every output has `:runtime/seq` of the input which caused it and
`:output/seq`, starting at zero within that input. Output order is the vector
order. Every reducer output is placed in one pending record containing its
destination and value. Timer and focus requests route to `:effects`; contact-change,
arena-merge, arena-decision, and other trace-only values route to `:trace`;
`:event/kind :pointer` routes to `:pointer`; `:event/kind :gesture` routes to
`:gesture`; `:event/kind :keyboard` routes to `:keyboard`; `:dispatch/kind` routes to `:dispatch`; and `:diagnostic/kind`
routes to `:diagnostic`. Routing does not alter vector order. Append attempts
occur in complete reducer-output order even though consumers observe separate
physical streams. The effect stream carries timer and focus requests. Pointer,
keyboard, and gesture streams carry their respective event envelopes. The dispatch stream
contains fan-out values, not executable functions. A binding closes its outputs
only after it has processed one `:dao.gui.event/teardown` runtime input and
emitted all resulting cancellation and timer-cancel values.

The binding, reducer state, recognizer-machine, and trace values each carry
their own schema-version discriminator. Those fields version their individual
data shapes; they do not define one shared version for this document.

The only legal `:runtime/source` values are `:geometry`, `:profile`,
`:pointer`, `:keyboard`, `:timer`, `:subscription`, `:terminal`, and `:control`. The corresponding
`:runtime/value :message/kind` or `:input/kind` must agree with its source;
mismatch is `:dao.gui.event/unrecognized-event-kind` and has no other effect.

For `:runtime/source :keyboard`, the value must have `:input/kind :keyboard`.
For `:runtime/source :subscription`, the value must have `:subscription/op`
`:add`, `:remove`, `:focus/set`, or `:focus/clear`. Focus commands use the
forms defined in Normalized Keyboard Events.

The sole control value defined by this contract is teardown:

```clojure
{:input/kind :dao.gui.event/teardown
 :reason :binding-closed}
```

It is wrapped with `:runtime/source :control`. After one valid teardown, later
inputs are ignored and produce no output because the binding output streams are
already closed.

### Canonical Runtime Input Order

Split host streams enter the interpreter through one explicit multiplexer. Its
output is the canonical runtime-input stream:

```clojure
{:runtime/seq 1204
 :runtime/time-us 812338600
 :runtime/source :pointer
 :runtime/value <normalized-pointer-packet>}
```

`:runtime/seq` starts at zero for one runtime binding and increases by one for
every multiplexed value. `:runtime/time-us` uses the terminal's monotonic clock.
The multiplexer emits already ordered values; `dao.gui.event` does not retain a
hidden sorting buffer.

Runtime timestamps must be nondecreasing. At equal timestamps, source priority
is:

1. control values
2. terminal reset, coordinate-space, input-loss, profile, and geometry values
3. subscription and focus commands
4. keyboard events
5. pointer packets
6. timer results

`:runtime/seq` is the final tie-break inside one priority. Keyboard events are
ordered before pointer packets at equal timestamps. A value arriving with
a timestamp older than the emitted runtime prefix is a protocol error and is
dropped or cancels its affected arena. Presentation geometry and profiles must
still precede any down packet that refers to them.

This order makes timer-versus-pointer deadlines deterministic without coupling
event interpretation to the render loop. Every pointer tuple steps all relevant
machines and resolves the arena immediately; no frame-boundary commit exists.

### Reducer State And Step Order

The following is the complete logical reducer-state schema. Implementations may
use different internal representations only if trace replay yields the same
fixture projection derived below and the same output sequence:

```clojure
{:dao.gui.event/state-version 2
 :generation-id <opaque-id-or-nil>
 :last-runtime-time-us <integer-or-nil>
 :last-runtime-seq -1
 :focus {:id <focus-id-or-nil>
         :node-id <node-id-or-nil>
         :generation-id <opaque-id-or-nil>}
 :keys-down [<physical-key-codes-in-stable-edn-order>]
 :last-keyboard-seq <input-seq-or-nil>
 :active-coordinate-space-id <id-or-nil>
 :coordinate-spaces {coordinate-space-id {:viewport {:width <number>
                                                      :height <number>}}}
 :geometry {:active <presented-geometry-or-nil>}
 :profiles {profile-id <input-profile>}
 :subscriptions {subscription-id <registration>}
 :subscription-order [subscription-id]
 :pointers {pointer-id <capture>}
 :arenas {arena-id <arena>}
 :next-arena-id 0
 :timers {timer-correlation-key <timer-record>}}
```

A capture contains its origin generation, frame, coordinate space, profile,
target path, matching subscription ids, current pointer facts, and arena id. An
arena contains the snapshotted path/profile/recognizer declarations, sorted
candidates, active pointer ids, lifecycle `:open`, `:accepted`, `:ended`, or
`:cancelled`, deferred-accept records, and its creation input sequence. A candidate contains its
recognizer identity, machine state id, machine local state, bounded windows,
timer records, and decision `:possible`, `:held`, `:accepted`, or `:rejected`.
Every candidate also exposes this immutable arbitration projection after each
machine step:

```clojure
{:candidate-id [node-id recognizer-id]
 :gesture/kind <keyword>
 :declaration-rank <rank-tuple>
 :decision <decision>
 :contacts <canonical-range>
 :arbitration {:tap/count <optional-positive-integer>
               :tap/completed-count <optional-non-negative-integer>
               :tap/first-centroid <optional-position>
               :tap/last-up-time-us <optional-integer>}}
```

Only the arena reads this projection. Recognizer-local state and windows are not
cross-candidate inputs. The projection is replaced atomically after the
candidate step and before arena resolution for that runtime input.

For one canonical input the reducer performs this exact order: validate the
envelope and causal ids; update the applicable state map; derive a single
machine input per affected candidate; step candidates in candidate order; apply
arena resolution; derive pointer and gesture events; fan each event to its
snapshotted subscriptions; finally append diagnostics. Cancellation effects are
ordered before cancellation gesture/pointer events, and those events before
their dispatches. A candidate fault is transformed into reject plus diagnostic;
it never aborts the reducer.

## Authored Interaction Data

Interactive identity begins in authored Hiccup. The generic authoring surface is
`:gui/gestures`, a vector of recognizer declarations on a node with a stable
`:node-id`.

```clojure
[:gui/image
 {:node-id ::map
  :gui/gestures
  [{:recognizer/id ::map-transform
    :gesture/kind :transform
    :machine :dao.gui.event/transform
    :config {:contacts {:min 1 :max 5}}
    :arena {:priority 0 :mode :exclusive}}]
  :gui/touch-action :none}
 image]
```

`:on-tap` remains authoring shorthand, not a second runtime contract:

```clojure
{:node-id ::save
 :on-tap [:project/save project-eid]}
```

It lowers exactly as if the node declared one standard tap recognizer:

```clojure
{:recognizer/id [::save :tap]
 :gesture/kind :tap
 :machine :dao.gui.event/tap
 :config {:count 1
          :contacts 1
          :join-after-accept false
          :contact-loss :end}
 :arena {:priority 0 :mode :exclusive}}
```

The `:on-tap` value itself remains application data. It is not serialized into
`dao.postgraphics`. The application associates it with the stable node id and
interprets it after dispatch.

Every recognizer declaration has this shape:

```clojure
{:recognizer/id ::stable-recognizer
 :gesture/kind :tap
 :machine :dao.gui.event/tap
 :config {:count 1
          :contacts 1
          :join-after-accept false
          :contact-loss :end}
 :arena {:priority 0
         :mode :exclusive
         :coexistence/group nil}}
```

Rules:

- `:recognizer/id` is unique within one node.
- `:gesture/kind` is the subscriber-visible semantic kind.
- `:config` contains only data and overrides the active input profile.
- `:config :join-after-accept` defaults to `false`. An accepted winner admits a
  later contact only when this value is `true` and its machine admits the join.
- `:config :contact-loss` is one of `:end`, `:degrade`, or `:hold` and defaults
  to `:end`.
- `:arena :priority` defaults to `0`; larger values rank first.
- `:arena :mode` defaults to `:exclusive`.
- cooperative recognizers may accept together only when they carry the same
  non-nil `:coexistence/group`.
- `:arena :mode :cooperative` requires a non-nil
  `:arena :coexistence/group`. A compiler rejects an authored declaration that
  violates this rule. If such a declaration reaches runtime installation, the
  runtime omits that candidate and emits exactly one
  `:dao.gui.event/invalid-recognizer` diagnostic for that declaration with
  `:reason :cooperative-without-group`, the available node and recognizer
  identities, and severity `:error`. It is not treated as exclusive.
- an exclusive recognizer never coexists with another accepted recognizer.
- declaration vector order is semantically significant and is preserved.

Canonical recognizer data always represents `:contacts` as
`{:min <positive-integer> :max <positive-integer>}` with `min <= max`. An
authored integer `n` lowers to `{:min n :max n}` before presentation, tracing,
or validation. Standard recognizers must not interpret the authored shorthand
directly.

The target compiler lowering is a normative, closed relation. Its implementation
is deferred under Implementation Scope. Until that boundary is implemented,
canonical authored-interaction examples in this document are producer-contract
fixtures rather than a claim about the current `dao.gui` compiler. It recognizes
only `:gui/gestures`, `:gui/touch-action`, stable `:node-id`, and the documented
`:on-tap` shorthand. It validates each declaration, canonicalizes contacts and
defaults, constructs the root-to-target interactive path, and emits one
`:meta/region` carrying that canonical data. Unknown gesture authoring keys or
unknown standard machine keywords are compiler errors. Compiler implementation
code is not part of the wire conformance surface; its emitted metadata is.

## Identity And Explicit Interaction Paths

An interactive `:meta/region` carries:

- its stable `:node-id`
- its recognizer declarations
- an explicit root-to-target interaction path
- its authored browser touch policy
- visual precedence

`dao.gui` constructs the path from authored structure. It must not be inferred
later from overlapping rectangles. A generic `dao.postgraphics` producer that
wants nested interaction supplies the same metadata explicitly.

The interaction path contains interactive nodes only. Each entry repeats the
data needed to construct candidates without consulting an implicit scene graph:

```clojure
{:interaction/path
 [{:node-id ::scroll
   :recognizers [{:recognizer/id ::scroll-pan
                  :gesture/kind :pan
                  :machine :dao.gui.event/pan
                  :config {:axis :y}
                  :arena {:priority 0 :mode :exclusive}}]
   :touch-action :none}
  {:node-id ::row-button
   :recognizers [{:recognizer/id ::row-tap
                  :gesture/kind :tap
                  :machine :dao.gui.event/tap
                  :config {:count 1 :contacts 1}
                  :arena {:priority 0 :mode :exclusive}}]
   :touch-action :manipulation}]}
```

Node ids are scoped to one `dao.gui` root and one event-runtime binding. A node
id may be any EDN value with stable equality semantics. Namespaced keywords are
recommended. Duplicate logical targets with the same `(node-id,
recognizer-id)` in one compiled frame are compiler errors. Several rectangular
regions may represent one logical target.

## Presented Geometry

Presented geometry becomes authoritative only after presentation:

```text
frame program -> terminal presentation -> presented geometry -> hit index
```

The terminal emits one geometry value for every successfully presented frame,
including an explicit empty value when the frame has no interactive targets.

```clojure
{:message/kind :dao.terminal/presented-geometry
 :generation-id "c18496e9-1a16-4b1d-9028-e35ba0dc7af8"
 :frame-id 42
 :coordinate-space-id 7
 :nodes
 [{:node-id ::save
   :interaction/path
   [{:node-id ::save
     :recognizers
     [{:recognizer/id [::save :tap]
       :gesture/kind :tap
       :machine :dao.gui.event/tap
       :config {:count 1 :contacts 1}
       :arena {:priority 0 :mode :exclusive}}]
     :touch-action :manipulation}]
   :touch-action :manipulation
   :regions
   [{:bounds {:x 24 :y 16 :width 96 :height 32}
     :paint-order 73014444049}]}]}
```

The terminal resolves `:touch-action` to the effective policy for the complete
interaction path. The per-path policies remain present for diagnostics and
inspection.

Presented geometry obeys these invariants:

- `:generation-id` identifies one terminal generation.
- `:frame-id` advances once per successful presentation in that generation.
- `:coordinate-space-id` names the viewport mapping used by bounds and input.
- all bounds are final visible screen-space bounds after transforms and clips.
- coordinates are GUI Cartesian logical pixels, origin bottom-left, y upward.
- region precedence uses the canonical `dao.postgraphics` effective-z formula.
- a fully clipped target contributes no region.
- an empty `:nodes` vector replaces the prior hit index with an empty index.
- geometry for a frame is emitted before pointer input observed against it.

Rectangular hit geometry remains exact. Axis alignment uses epsilon `1e-6` and
hit containment uses `[x, x + width) x [y, y + height)`. Unsupported
non-rectangular presented regions are omitted with an explicit diagnostic.

For `dao.gui` output, the dedicated interactive `:meta/region` remains the sole
source of hit geometry. Draw ops may carry provenance identity but do not carry
recognizers.

### Region Aggregation And Overlap

Presented regions are grouped by logical target, meaning the same node id,
recognizer declarations, interaction path, and effective touch policy.

- For `dao.gui` output, one interactive `:meta/region` per logical target is
  authoritative and substitutive. Painted contributions with that identity are
  ignored for hit geometry.
- For generic producers without an authoritative region, visible painted and
  explicit region contributions with identical target metadata are additive.
- Aggregation happens after transform resolution and clipping.
- Disjoint visible rectangles remain separate. A terminal must not replace
  them with a bounding rectangle that makes an unpainted gap interactive.
- Rectangles may be coalesced only when both exact covered area and effective
  precedence relative to every other interactive region are preserved.
- Every emitted rectangle retains its own effective paint order when overlap
  could affect topmost selection.
- A fully clipped contribution is omitted; a partially clipped contribution
  emits only its visible remainder.
- A parent target does not own a child's pixels merely because the child appears
  on its interaction path. Each target owns only its explicit region geometry.

Across mixed producers, effective order is the canonical value
`(metadata-precedence << 32) | bytecode-index`. At a down position, exactly one
highest-order rectangle supplies the target path. Equal effective order is a
terminal protocol error because the canonical formula should have made order
unique within one completed frame.

## Terminal Input Profile

Gesture thresholds are explicit runtime data. A terminal emits an input profile
after reset and before the first pointer packet that refers to it.

```clojure
{:message/kind :dao.terminal/input-profile
 :generation-id "c18496e9-1a16-4b1d-9028-e35ba0dc7af8"
 :profile-id 3
 :capabilities #{:coalesced-samples :pressure :contact-geometry}
 :thresholds
 {:motion/slop 18.0
  :tap/max-duration-us 300000
  :multi-tap/max-delay-us 300000
  :multi-tap/slop 100.0
  :long-press/delay-us 500000
  :swipe/min-distance 48.0
  :swipe/max-duration-us 500000
  :swipe/min-velocity 500.0
  :fling/min-velocity 50.0
  :fling/max-velocity 8000.0
  :velocity/window-us 100000
  :edge/width 20.0
  :pressure/start-threshold 0.5
  :pressure/release-threshold 0.5}}
```

Units are logical pixels, microseconds, radians, and logical pixels per second.
The terminal should project host gesture settings where a host exposes them.
The values above are the normative fallback profile when it does not.

Optional capabilities are:

- `:coalesced-samples`
- `:predicted-samples`
- `:pressure`
- `:contact-geometry`
- `:tilt`
- `:twist`
- `:hover`
- browser terminals additionally report supported CSS policy tokens as
  `:touch-action/auto`, `:touch-action/none`, `:touch-action/manipulation`,
  `:touch-action/pan-x`, `:touch-action/pan-y`, directional pan tokens, and
  `:touch-action/pinch-zoom`

A recognizer requiring an absent capability is dormant and produces one warning
when installed. It does not invalidate other recognizers on the node. A profile
update affects pointer sequences that begin after the update; every arena
snapshots its profile and recognizer configuration at creation.

Profile ids are unique and monotonically increasing within a generation. The
runtime retains a profile while an arena snapshots it. A later packet for an
active pointer may name a newer installed profile, but recognition for that
arena continues with its origin profile. New down packets must name the latest
installed profile.

## Normalized Pointer Packets

The normative terminal input value is:

```clojure
{:input/kind :pointer
 :generation-id "c18496e9-1a16-4b1d-9028-e35ba0dc7af8"
 :frame-id 42
 :coordinate-space-id 7
 :profile-id 3
 :input-seq 918
 :pointer {:id 11
           :type :touch
           :primary? true
           :buttons 1
           :modifiers #{}}
 :phase :move
 :samples
 [{:time-us 812334500
   :position {:x 120.25 :y 380.5}
   :pressure 0.61
   :contact {:width 8.0 :height 7.5}
   :sample/kind :coalesced}
  {:time-us 812338600
   :position {:x 124.0 :y 377.0}
   :pressure 0.64
   :contact {:width 8.2 :height 7.4}
   :sample/kind :actual}]}
```

Required pointer phases are `:down`, `:move`, `:up`, and `:cancel`. `:hover` is
allowed for pointer types that support it, but touch gesture conformance does
not depend on hover.

Rules:

- `:input-seq` increases by one for every emitted pointer packet in a
  generation. Pointer and keyboard input sequences are modality-local;
  `:runtime/seq` is the single global sequence across all runtime sources.
- pointer ids are unique among active pointers and may be reused only after
  `:up` or `:cancel`.
- each packet has at least one sample, ordered by increasing `:time-us`.
- the final sample is the packet's current value and is `:actual`.
- coalesced samples precede that actual sample and participate in recognition.
- predicted samples are dispatchable for rendering feedback but never affect
  recognition, velocity, arena decisions, or emitted semantic gestures.
- unavailable optional properties are omitted, not invented.
- `:buttons` follows the Pointer Events bit convention; active touch contact
  uses `1`, while touch up and cancel use `0`.
- normalized pressure, when present, is finite and in `[0.0, 1.0]`; contact
  width and height are finite non-negative logical pixels.
- terminal-native pixel, y-axis, timestamp, and pointer-id conventions do not
  cross this boundary.

A `:hover` packet never creates, joins, captures, or steps an arena. The runtime
hit-tests its actual position against the active presented geometry and emits a
targeted raw pointer event to matching `:pointer` subscriptions for that node.
It is dropped without diagnostic when nothing is hit. A hover packet for an id
that is currently an active touch contact is a protocol error.

## Normalized Keyboard Events

Keyboard input uses the same runtime-input stream and map-shaped event
envelopes as pointer input. It does not participate in pointer hit-testing,
capture, or gesture arenas. The terminal observes host keyboard input and emits
one normalized value for each delivered key transition, including the host
focus token captured when the transition was delivered.

The normative terminal input value is:

```clojure
{:input/kind :keyboard
 :generation-id "c18496e9-1a16-4b1d-9028-e35ba0dc7af8"
 :input-seq 919
 :focus-id ::editor
 :phase :down
 :key {:code :key-a
       :logical :a
       :location :standard}
 :modifiers #{:control}
 :repeat? false
 :time-us 812338700}
```

`:phase` is `:down` or `:up`. The nested `:key` map's `:code` is the
layout-independent physical key identity; its `:logical` value is the
layout-dependent meaning produced by the terminal. Its `:location` is one of
`:standard`, `:left`, `:right`, `:numpad`, or `:unknown`. The terminal omits a
logical value when the host cannot provide one. `:modifiers` is a set of
`:shift`, `:control`, `:alt`, `:meta`,
`:caps-lock`, `:num-lock`, and `:scroll-lock`. `:repeat?` is true only for a
host-generated repeated `:down` value.

Keyboard field rules are:

- `:generation-id`, `:input-seq`, and `:time-us` are required; `:input-seq` is
  an integer increasing by one within the generation and `:time-us` is an
  integer monotonic timestamp.
- `:focus-id` is required and is either the current focus id or `nil`. A nil
  focus id is valid input but cannot produce a subscriber dispatch.
- `:phase` is required and is one of `:down` or `:up`.
- `:key` is required and contains `:code`, `:logical`, and `:location`.
- the nested `:key` map's `:code` is a keyword. The portable
  vocabulary reserves `:key-a` through `:key-z`, `:digit-0` through
  `:digit-9`, `:f1` through `:f24`, `:arrow-up`, `:arrow-down`,
  `:arrow-left`, `:arrow-right`, `:home`, `:end`, `:page-up`, `:page-down`,
  `:insert`, `:delete`, `:backspace`, `:enter`, `:escape`, `:tab`, and
  `:space`. Hosts may use additional namespaced codes. Unknown host keys use
  a namespaced `:unknown/*` code and are not discarded.
- the nested `:key` map's `:logical` is either a namespaced keyword for a non-printing logical key,
  a one-Unicode-scalar string for a printable key, or `nil` when unavailable.
- the nested `:key` map's `:location` is required and is one of `:standard`, `:left`, `:right`,
  `:numpad`, or `:unknown`.
- `:modifiers` is required, is a set, and contains only the listed modifier
  keywords. Lock modifiers describe the state after this key transition.
- `:repeat?` is required and is boolean. It is false for every `:up` value.
- unknown fields are rejected with `:dao.gui.event/malformed-keyboard-event`;
  optional host-specific data is not forwarded in this contract.

Keyboard events are routed through explicit focus data. `:focus-id` identifies
the focus record selected by the event runtime; it is not inferred from pointer
geometry. The event runtime, not the terminal, is authoritative for focus.
The terminal adapter may use the focus command to focus a host widget, but host
focus does not change runtime focus implicitly. A `nil` `:focus-id` means that
the event is unfocused and it is not delivered to a node subscription.
The application sets focus through the same explicit command stream:

```clojure
{:subscription/op :focus/set
 :focus/id ::editor
 :node-id ::editor}
```

```clojure
{:subscription/op :focus/clear
 :focus/id ::editor}
```

`:focus/id` is unique within a generation. `:subscription/op :focus/set`
replaces the current focus, and `:subscription/op :focus/clear` is idempotent.
The runtime stores the focused id, node id, and generation id in its immutable
state. A focus-set or focus-clear command emits a focus effect before any later
keyboard event is consumed:

```clojure
{:effect/kind :dao.gui.event/focus-request
 :operation :set
 :focus-id ::editor
 :node-id ::editor}
```

The clear form uses `:operation :clear` and omits `:node-id`. The terminal
adapter uses this effect to update host focus, but does not mutate runtime
focus directly. A keyboard event's `:focus-id` must equal the current focus id
or be nil; an unknown or stale focus id produces
`:dao.gui.event/focus-mismatch` and is dropped. Focus commands affect later
keyboard events and never alter an active pointer arena.

Keyboard transitions are raw input data. Text insertion, dead-key resolution,
and IME composition are not inferred from `:key`'s `:logical` value; a future text-input
value must represent committed text and composition explicitly.

The targeted raw keyboard output preserves the normalized event and adds the
focus target:

```clojure
{:event/kind :keyboard
 :runtime/seq 919
 :output/seq 0
 :event/phase :down
 :focus-id ::editor
 :node-id ::editor
 :key {:code :key-a
       :logical :a
       :location :standard}
 :modifiers #{:control}
 :repeat? false
 :time-us 812338700}
```

Keyboard output has no arena id, frame id, target path, or coordinate space.
The `:keyboard` output stream is lossless. A keyboard event without focus is
retained on the raw keyboard stream but produces no subscriber dispatch.
Lossless means the reducer never coalesces or drops a valid, non-protocol-error
keyboard value;
normal output backpressure still parks the binding until the keyboard stream
accepts the pending value. `replay` consumes the same keyboard inputs and must
produce the same focus state, `:keys-down` state, lifecycle values, and
dispatch order.

When focus is cleared while keys are held, or when terminal reset, input loss,
or teardown clears keyboard state, the runtime emits one lifecycle value before
the state transition completes. The lifecycle value is queued before the focus
effect or terminal cancellation output caused by that transition:

```clojure
{:event/kind :keyboard
 :runtime/seq 920
 :output/seq 0
 :event/phase :cancel
 :focus-id ::editor
 :node-id ::editor
 :reason :focus-lost
 :released-key-codes #{:key-a}}
```

Keyboard subscriptions receive this value when their `:keyboard/phases` includes
`:cancel`. The reasons are `:focus-lost`, `:reset`, `:input-loss`, and
`:teardown`. The lifecycle value is not a physical key-up and is not inserted
into `:keys-down`.

Keyboard lifecycle rules are:

- `:input-seq` increases by one for every keyboard event in a generation. It is
  keyboard-local; `:runtime/seq` is the single global sequence across pointer,
  keyboard, timer, terminal, geometry, and subscription values. Input-sequence
  continuity is checked independently for pointer and keyboard values. When a
  gap is detected, the runtime emits `:input-sequence-gap`, clears focus and
  held keyboard keys, emits the corresponding keyboard cancellation lifecycle
  value, and uses the received event's sequence as the new keyboard-local
  anchor. A pointer gap does not change the keyboard anchor.
- `:repeat?` may be true only on `:down`; repeated downs are forwarded and are
  not coalesced.
- the runtime records each accepted down's physical key code in `:keys-down` in stable EDN order, and removes it on up.
- a down for a code already in `:keys-down` is accepted only when `:repeat?` is
  true; otherwise it emits `:dao.gui.event/duplicate-key-down` and is dropped.
- an up for a code absent from `:keys-down` emits
  `:dao.gui.event/orphan-key-up` and is dropped.
- the runtime does not synthesize a missing up event.
- terminal reset, input loss, focus clear, and teardown clear the focused target
  and `:keys-down`; they emit the lifecycle value above but do not synthesize
  physical key-up events.
- a keyboard event received after focus clear has no subscriber dispatch until a
  later focus-set command.
- keyboard events never create, join, merge, or terminate pointer arenas.

### Terminal Adapter Contract

The terminal adapter contract is a deferred producer boundary under
Implementation Scope. It defines the canonical values that a future conforming
adapter must emit; it is not part of the current reducer-and-binding
implementation claim.

The adapter assigns `:input-seq` after it has expanded a host callback into one
portable packet. It must never split a host callback into packet ordering that
changes its actual-sample lifecycle. Android and iOS Flutter adapters map
`PointerDownEvent`, `PointerMoveEvent`, `PointerUpEvent`, and
`PointerCancelEvent` directly; Flutter coordinates are converted from its
top-left logical space by `y = viewport-height - y`. Browser adapters map
`pointerdown`, `pointermove`, `pointerup`, `pointercancel`, and `lostpointercapture`.
They call `setPointerCapture(pointerId)` after an accepted browser down and emit
one portable cancel if capture is lost.

Keyboard adapters emit one keyboard value for each host key-down or key-up
transition. They map the host physical key to `:key` `:code`, the host layout
meaning to `:key` `:logical` when available, and the host location to
`:key` `:location`. They copy the host modifier set, mark host auto-repeat as
`:repeat? true`, and convert the host monotonic timestamp to integer
microseconds. A host text or composition callback is not converted into a
keyboard event; text and IME values are outside this revision.

For all adapters, host timestamps are converted once to integer microseconds
from a monotonic origin. A host timestamp that regresses is clamped only if it
belongs to a coalesced sample in the same packet; otherwise the adapter emits
protocol-error and cancels the pointer. Browser `getCoalescedEvents()` values
are sorted by converted time, filtered to strictly preceding the actual sample,
and emitted as `:coalesced`; unsupported or invalid values are omitted with a
diagnostic. Browser predicted events are marked `:predicted`; the reducer
forwards them only to raw pointer output and excludes them from recognition.

The adapter maps every host contact id to an opaque EDN scalar stable from down
through up/cancel. It must not derive ids from array index, primary status, or
position. A Flutter cancel carries the last known actual position and zero
buttons. A browser `pointercancel` does the same. If a host omits pressure,
contact geometry, tilt, or twist, the adapter omits that key and the profile
does not advertise its capability.

## Frame And Input Causality

A down packet starts a sequence only when all of these match installed state:

- generation id
- coordinate-space id
- input profile id
- active presented frame id

For a new down packet:

- `pointer.frame == active.frame`: hit-test and create an arena.
- `pointer.frame > active.frame`: protocol error; drop it.
- `pointer.frame < active.frame`: stale uncaptured down; drop it.

After a pointer is active, its later packets may carry a newer visible frame id
than its origin frame. They remain captured to the original interaction path.
A later packet with a frame id older than the current active frame is still
valid for that captured pointer when its `:input-seq` is next in order. A packet
from a future frame remains a protocol error.

No pointer packet is buffered waiting for geometry or rebound to another frame.
Frame rejection and frame skipping leave existing geometry and captures intact.
A terminal reset cancels every active pointer and arena, clears geometry and
profiles, and begins a new generation.

`coordinate-space-id` describes only the terminal root mapping: viewport size,
device-pixel ratio, orientation, and system-view transform. Local layout,
clipping, scrolling, animation, and gesture-driven node transforms do not mint
a new coordinate-space id and do not cancel an active sequence. Pointer math
continues in the captured root Cartesian space.

## Hit Testing And Capture

On pointer down, the runtime finds every region containing the point and selects
the one with greatest effective paint precedence. That region supplies the
explicit interaction path. Tree nesting and rectangle overlap are never used to
invent additional ancestors.

A valid down that hits no region creates an uncaptured pointer record with
`:arena-id nil`; its later move packets have no output and its up/cancel removes
the record. This preserves lifecycle validation without inventing a target.
Down for an id already in the pointer map emits `:duplicate-pointer-down` and
is dropped after cancelling and removing the existing pointer record. Move, up, or
cancel for an id absent from the pointer map emits `:orphan-pointer-packet` and
is otherwise dropped.

The runtime creates candidates from every recognizer declaration on that path.
The candidate's target is the path entry that declared it. Gesture dispatch goes
only to the winning recognizer's node; there is no implicit capture or bubble
phase in the application API.

The path, geometry frame, profile, recognizer declarations, and matching
subscriber registrations are immutable snapshots for the sequence. Later
frames do not retarget or cancel it merely because a target moves or disappears.
Pointer movement outside the original target, across another region, or across
another interaction path never causes re-hit-testing or arena recomputation.

Target disappearance means that a later presented frame omits, clips, or moves
the captured node; capture persists. Runtime-binding teardown is different: it
cancels every active arena and then releases the binding's state.

An accepted recognizer logically captures its participating pointers through
`:up`, `:cancel`, or recognizer termination. Terminals also preserve physical
delivery after the pointer leaves the initial rectangle. On web, the terminal
captures the pointer on a stable terminal root after pointer down. Logical
capture does not prevent the browser or operating system from cancelling a
sequence it owns.

## Multi-Pointer Joining

A new down packet is hit-tested once and its interaction path is snapshotted.
Only that new down can connect arenas. Movement by an existing pointer never
changes candidate incidence.

A live candidate intersects the new contact when both paths contain the same
`(node-id, recognizer-id)` and generation and coordinate-space ids match.
Candidate intersection is computed only from immutable path tuples, not from
current pointer position.

Resolve accepted intersections first. An accepted arena is eligible only when
its winner declares `:join-after-accept true`, its contact range admits the
pointer, and its current machine state admits joining. If several accepted
arenas qualify, choose the highest-ranked winner, then the oldest arena; accepted
arenas never merge. The contact joins that one arena and does not also join or
merge unresolved arenas.

When no accepted arena qualifies:

- With no intersecting unresolved arena, create an independent arena.
- With one intersecting unresolved arena, join it.
- With several intersecting unresolved arenas, merge them into the oldest arena.

When a contact cannot join an accepted arena, any new or merged unresolved arena
omits that already-owned exclusive `(node-id, recognizer-id)` candidate. Other
candidates on the new path remain eligible.

An arena merge is explicit trace data:

```clojure
{:event/kind :dao.gui.event/arena-merged
 :arena-id 12
 :merged-arena-ids [12 19]
 :pointer-ids #{4 7 9}
 :candidate-ids [[::scroll ::scroll-pan]
                 [::map ::map-transform]]}
```

The lowest creation sequence, equivalently the oldest arena, supplies the
surviving `:arena-id`. Candidate identity is `(node-id, recognizer-id)`;
duplicates are retained once at their earliest declaration rank. All pointers,
snapshotted subscriptions, machine states, and outstanding timer identities are
moved into the surviving immutable arena value before recognition resumes.
Later duplicate candidate instances receive `:arena/cancelled`, and their timer
identities are invalidated rather than combined with the retained machine state.

Every join or merge produces a machine input before any candidate sees the new
pointer's down tuple. For lift or cancellation, candidates first receive the
pointer's up or cancel tuple while the ending contact is still addressable, then
receive the contact-change input for the remaining set:

```clojure
{:input/kind :dao.gui.event/contacts-changed
 :arena-id 12
 :cause :merge
 :added-pointer-ids #{9}
 :removed-pointer-ids #{}
 :pointer-ids #{4 7 9}
 :contact-count 3}
```

The event is delivered to every possible or accepted candidate in the resulting
arena. A pointer belongs to exactly one arena. Arenas never split after a lift;
candidates apply their declared `:contact-loss` policy after the contact-change
tuple updates the set.

Applications that need one-finger pan to grow into pinch or rotation should use
`:transform` with a contact range beginning at one. An already accepted
exclusive `:pan` does not silently turn into a different recognizer when another
finger arrives.

## Gesture Arena

Candidates receive the same captured pointer and timer tuples and emit explicit
decisions:

- `:hold`: remain possible
- `:accept`: claim recognition
- `:reject`: leave the arena

Candidate rank is deterministic:

1. greater explicit `:arena :priority`
2. greater zero-based path index in the presented interaction-path vector
3. earlier recognizer index in the presented declaration vector
4. stable EDN comparison of `:recognizer/id`

Every rank component therefore comes from presented trace data. Runtime
allocation order and host widget traversal never participate.

All candidate transitions caused by one input tuple are evaluated before arena
resolution. If multiple exclusive candidates accept on that tuple, the
highest-ranked candidate wins. Losing possible or accepted continuous
candidates receive cancellation. Cooperative candidates accept together only
when they name the same non-nil coexistence group and no higher-ranked exclusive
candidate accepts.

When several candidates accept on one tuple, cooperative accepters are
partitioned by their non-nil coexistence group. Each group is ranked by its
highest-ranked accepting member. The highest-ranked accepting candidate across
all exclusive candidates and cooperative groups determines the winning mode.
If it is exclusive, that candidate wins alone. If it is cooperative, every
accepting cooperative candidate in that candidate's group wins, in candidate
rank order. All other accepting or possible competitors lose according to the
normal arena-cancellation rules. Map iteration order never participates.

Candidates never receive a sibling candidate's machine state, decision, window,
or emitted payload. `:arena/accepted`, `:arena/rejected`, and
`:arena/cancelled` machine inputs describe only that candidate's own arena
lifecycle. Cross-candidate competition is interpreted by the arena from
snapshotted declaration metadata and explicit candidate decisions.

The one deferral rule is repeated tap. When a tap candidate completes count
`n`, the arena defers its acceptance while a same-node, same-contact-count tap
candidate with configured count greater than `n` remains viable. Viability is a
pure arena predicate over that higher-count candidate's declared count, completed
count, first-tap centroid, last valid up time, current contact facts, rejection
decision, and snapshotted tap thresholds. It remains viable only while it has
not rejected, has not exceeded motion or multi-tap slop, and the next valid down
can still arrive at or before `last-up-time-us + multi-tap/max-delay-us`.
Candidate-local state is not inspected. When all higher-count alternatives
reject or their `:next-tap` timers fire, the greatest completed count accepts;
rank breaks ties. Evaluation uses the original input timestamps, happens in the
same reducer step that removes the last viable alternative, and never re-defers
that completed tap.

Concretely, a tap's proposed accept is changed to candidate decision `:held` and
stored in arena `:deferred-accepts` with its complete pending recognized payload
and causal input sequence. It emits no decision or gesture yet. Revival removes
that record, accepts the candidate, and emits the stored value with the original
gesture time plus the reviving input's `:runtime/seq`. Cancellation or input
loss discards deferred accepts without semantic output.

An arena ends after all pointers end and every machine has accepted and ended or
rejected. A terminal cancel, reset, coordinate-space change, input gap, runtime
teardown, or machine fault cancels every accepted continuous gesture and rejects
every possible candidate in the affected arena.

Cancellation after acceptance is not a second winner decision. It emits exactly
one semantic `:cancel` phase for each accepted continuous gesture, propagates
`:arena/cancelled` to cooperative winners, and invalidates their timers. A
discrete gesture already emitted as `:recognized` is not retroactively revoked.

### Common competition cases

- A child tap and ancestor vertical pan both hold after down. Movement past the
  pan slop accepts the pan and cancels the tap. Up inside tap limits accepts the
  tap when the pan never accepted.
- A long press timer accepts the long press and cancels an exclusive tap. Up
  before the timer rejects long press and allows tap to resolve.
- A one-tap recognizer waits through the multi-tap deadline when a competing
  repeated-tap recognizer could still accept. It is not emitted speculatively.
- Scale and rotation declarations can accept together only when both are
  cooperative and share a coexistence group. A combined `:transform`
  recognizer avoids this coordination.

## Recognizer Machine Data

Standard and custom recognizers are immutable finite-state machines. Standard
machine keywords resolve to the total normative transition algorithms in this
document. An implementation may express or precompile those algorithms as the
machine data below, but the result must be observationally equivalent. The
transition clauses and schemas in this document are authoritative if a derived
machine-data artifact disagrees.

```clojure
{:machine/version 1
 :initial :possible
 :state {:origin nil}
 :windows {:motion {:capacity 32}}
 :states
 {:possible
  [{:on :pointer/down
    :when [:= [:contacts/count] 1]
    :actions
    [[:state/assoc :origin [:contacts/centroid]]
     [:arena/hold]]}
   {:on :pointer/move
    :when [:> [:distance [:state/get :origin]
                        [:contacts/centroid]]
              [:setting :motion/slop]]
    :actions [[:arena/reject] [:goto :rejected]]}
   {:on :pointer/up
    :when [:<= [:elapsed-us] [:setting :tap/max-duration-us]]
    :actions
    [[:arena/accept]
     [:emit :recognized
      {:position [:contacts/centroid] :count 1}]
     [:goto :ended]]}
   {:on :pointer/cancel
    :when true
    :actions [[:arena/reject] [:goto :rejected]]}]
  :ended []
  :rejected []}}
```

Machine input selectors are:

- `:pointer/down`, `:pointer/move`, `:pointer/up`, `:pointer/cancel`
- `:contacts/changed`
- `:timer/fired`
- `:arena/accepted`, `:arena/rejected`, `:arena/cancelled`

The expression vocabulary is closed:

- literals: EDN scalars, vectors, sets, and maps
- lookup: `:state/get`, `:sample/get`, `:pointer/get`, `:config/get`,
  `:profile/get`, and `:setting`
- logic: `:and`, `:or`, `:not`
- comparison: `:=`, `:not=`, `:<`, `:<=`, `:>`, `:>=`, `:contains?`
- arithmetic: `:+`, `:-`, `:*`, `:/`, `:abs`, `:min`, `:max`, `:clamp`
- contact projections: `:contacts/count`, `:contacts/centroid`,
  `:contacts/span`, `:contacts/angle`, and `:contacts/ids`
- motion projections: `:distance`, `:delta`, `:elapsed-us`, `:velocity`,
  `:direction`, and `:edge-distance`

`:setting` reads a declaration override first and the snapshotted terminal
profile second. Division by zero, a missing required value, non-finite numeric
output, or an invalid projection faults only that candidate.

Actions are:

- `[:state/assoc key expression]`
- `[:state/dissoc key]`
- `[:window/push window-id expression]`
- `[:timer/start timer-id duration-expression]`
- `[:timer/cancel timer-id]`
- `[:arena/hold]`, `[:arena/accept]`, `[:arena/reject]`
- `[:emit phase payload-expression]`
- `[:goto state-id]`

For one input, transitions are tested in declaration order and only the first
true transition runs. Actions run left to right against an immutable state
value, producing a new state and zero or more effect tuples. Windows must have a
positive compile-time capacity. Machines have no loops, recursion, host calls,
callbacks, dynamic code loading, or unbounded collections.

Validation rejects unknown operators, states, selectors, or actions; duplicate
state ids; references to undeclared windows; non-terminal states without a
cancel transition; and emissions whose phase is not legal for the declared
gesture kind.

A machine transition relation is total: its state set, initial state, input
alphabet, ordered guards, and effects are finite and explicit. After the ordered
transitions for one state are tested, an otherwise-unmatched legal input leaves
the state unchanged and emits no effect. An input outside the declared alphabet
faults the candidate. Thus no standard or custom machine relies on an undefined
state/event pair.

Machine validation also requires a finite EDN value at every literal, keyword
state ids, unique transition order within each state vector, and a declared
setting for every `:setting` lookup. `:goto` may target only a declared state.
`[:arena/accept]` and `[:arena/reject]` may occur at most once in a transition;
an `:emit` must be after `:arena/accept` in that transition or occur while the
candidate is already accepted. Machine local state values and window entries
must be finite EDN values. The compiler rejects an action that can append an
unbounded value or whose expression can return a function, stream, host object,
NaN, or infinity.

The evaluator is total. A missing optional lookup evaluates to `nil`; comparison
or arithmetic requiring a missing or non-numeric value makes its transition
false, except division by zero and non-finite arithmetic, which fault the
candidate. `:and` and `:or` short-circuit left to right. Map and vector
expressions evaluate their children in declaration order. `:window/push` drops
the oldest value when capacity is reached. `:timer/start` replaces the prior
timer of that id by first emitting its cancel effect and then a new start effect
with its incremented sequence. `:goto` changes the state only after every prior
action succeeds. A transition that faults applies no later actions.

Before each machine step the runtime provides a frozen evaluation context:

```clojure
{:candidate <candidate-before-step>
 :contacts <active-captured-contacts-sorted-by-id>
 :sample <actual-sample>
 :pointer <packet-pointer-map>
 :input <normalized-packet-or-timer-or-contact-change>
 :time-us <input-time-us>
 :config <snapshotted-config>
 :profile <snapshotted-profile>
 :coordinate-space {:id <coordinate-space-id>
                    :viewport {:width <positive-number>
                               :height <positive-number>}}}
```

Contact projections use actual samples only. `:contacts/centroid` is the
arithmetic mean of current positions. `:contacts/span` is the mean distance
from that centroid, zero for fewer than two contacts. `:contacts/angle` is the
angle from the lowest pointer id to the next-lowest pointer id, normalized to
`[-pi, pi)`. `:velocity` is least-squares velocity over the candidate window,
or zero when it contains fewer than two distinct timestamps. `:direction` is
the axis or compass keyword derived from its vector using the configured axis;
the exact boundary rule is `abs(x) >= abs(y)` selects horizontal, with zero
vector yielding `nil`.

`:edge-distance` reads the frozen context viewport and current sample position.
It returns the non-negative distance to a requested edge: x for `:left`,
`viewport.width - x` for `:right`, `viewport.height - y` for `:top`, and y for
`:bottom`. A missing viewport faults the candidate; an out-of-viewport position
is clamped only for this distance projection and remains unchanged everywhere
else.

Every motion window entry has exactly this shape:

```clojure
{:time-us <integer>
 :position {:x <finite-number> :y <finite-number>}
 :pointer-ids [<ids-in-stable-edn-order>]}
```

Entries are ordered by `(time-us, pointer-ids)` and retain only entries whose
time is at least `latest-time-us - :velocity/window-us`, subject to the declared
capacity. Velocity is the independent ordinary-least-squares slope of x and y
against seconds after subtracting the first retained timestamp. Duplicate
timestamps contribute positions but do not create elapsed time; fewer than two
distinct timestamps yields `{:x 0.0 :y 0.0}`. Pan, swipe, and fling use this one
function over identical captured actual/coalesced samples. No recognizer reads
another recognizer's window or reported velocity.

For each coalesced or actual sample, the runtime temporarily replaces that
pointer's position in the current contact set, computes the centroid, and pushes
one entry before evaluating the next sample. The actual sample becomes the
stored contact position. Predicted samples never enter this process. This rule
makes the pan, swipe, and fling windows byte-identical for the same contact
range and input packets.

## Timers

Time is an explicit effect boundary. A timer action emits:

```clojure
{:effect/kind :dao.gui.event/timer-request
 :generation-id "c18496e9-1a16-4b1d-9028-e35ba0dc7af8"
 :coordinate-space-id 7
 :arena-id 81
 :recognizer/id ::hold
 :timer-id :long-press
 :timer-seq 2
 :timer/op :start
 :deadline-us 812834500}
```

The clock/scheduler returns:

```clojure
{:input/kind :dao.gui.event/timer-fired
 :generation-id "c18496e9-1a16-4b1d-9028-e35ba0dc7af8"
 :coordinate-space-id 7
 :arena-id 81
 :recognizer/id ::hold
 :timer-id :long-press
 :timer-seq 2
 :time-us 812834500}
```

Cancellation is another timer-request value with `:timer/op :cancel`. Late
results for ended arenas, cancelled timers, or superseded timer sequences are
ignored with a warning diagnostic. `:timer-seq` increases per
`(arena-id, recognizer-id, timer-id)` and distinguishes a restarted timer from a
late result for its prior incarnation.

Restart applies only after the new duration and deadline have been evaluated
successfully. If the same candidate has a `:scheduled` prior incarnation of
that timer id, the transition atomically:

1. changes the prior timer record from `:scheduled` to `:cancelled`;
2. emits its `:timer/op :cancel` request;
3. allocates the next monotonically increasing `:timer-seq`;
4. records the new correlation key as `:scheduled`; and
5. emits its `:timer/op :start` request.

The cancel and start are consecutive timer effects of the same causing runtime
input, in that order, and receive increasing `:output/seq` values. A fault while
evaluating the restart leaves the prior timer unchanged and emits neither
effect. Cancelled and fired records may be retained for late-result
classification, but only the new scheduled key appears in the executable
fixture state's `:scheduled-timer-keys`.

The complete correlation key is `(generation-id, coordinate-space-id, arena-id,
recognizer-id, timer-id, timer-seq)`. The runtime validates every component
before delivery. Candidate rejection, semantic end/cancel, arena merge removal,
coordinate-space change, reset, and teardown invalidate the affected timer
keys. Recognition compares terminal monotonic timestamps, never wall-clock time.

The scheduler is stateless with respect to recognition. It must emit at most one
timer-fired input for one correlation key. The runtime records a timer as
`:scheduled`, `:cancelled`, or `:fired`; receiving a valid fired value changes
it from `:scheduled` to `:fired` before stepping the candidate. A timer whose
deadline is before the next pointer time is still delivered only when its
explicit timer-fired input appears. At equal timestamps canonical source order
makes the pointer step first. This intentionally makes scheduler delivery, not
an implicit clock read, the cause of long-press acceptance.

## Standard Recognizers

All standard recognizers are total normative transition algorithms expressible
with the DSL above. A repository may ship machine-data templates as compiled
artifacts; those templates are normative test inputs and must replay identically
to these clauses. Their template ids, accepted phases, and terminal transitions
are fixed by this contract. Configuration keys not listed here are invalid.

| Template | Initial state and hold | Accept transition | After acceptance | Terminal transition |
| --- | --- | --- | --- | --- |
| `:tap` | `:possible`; record first centroid/time | final required `:up` satisfies duration, slop, and count | emit `:recognized`; state `:ended` | any cancel, bad contact count, or slop breach: reject |
| `:long-press` | `:possible`; final required down starts `:long-press` timer | matching timer fires while range/slop valid | emit `:start`, then updates | final up emits `:end`; cancel/range breach emits `:cancel` |
| `:pan` | `:possible`; record centroid/time and velocity window | axis-qualified displacement strictly exceeds slop | emit `:start`, then updates | final/range-ending up emits `:end`; cancel emits `:cancel` |
| `:swipe` | `:possible`; record centroid/time/window | final up meets distance, duration, direction, velocity | emit `:recognized` | any early invalidity rejects |
| `:fling` | `:possible`; record the canonical velocity window | final up has velocity in range | emit `:recognized` | cancel or final velocity below threshold rejects |
| `:transform` | `:possible`; record centroid/span/angle | translation, absolute log-scale, or absolute rotation exceeds its configured slop | emit `:start`, then updates | below-minimum count ends; cancel emits cancel |
| `:edge-pan` | `:possible`; down is in configured edge strip | inward axis displacement exceeds slop | same as pan | same as pan |
| `:pressure-press` | `:possible`; pressure capability is present | actual pressure is at least start threshold | emit `:start`, then updates | up ends; cancel or falling below optional release threshold cancels |

For all templates, `:down` establishes a contact before stepping candidates,
`:up` contributes its final actual sample before removing that contact, and
`:cancel` does not contribute a semantic final sample. Contact-count changes
then produce one `:contacts/changed` machine input in ascending recognizer
declaration order. A candidate accepts only after its own step; arena resolution
may immediately turn a losing accepted candidate into rejected without allowing
it to emit a semantic event. The winning candidate's accept-caused start or
recognized event is emitted after the decision trace.

Unless a recognizer clause says otherwise, maximum bounds are inclusive,
minimum bounds are inclusive, and a slop threshold is crossed only by a value
strictly greater than the threshold. Exact equality therefore remains possible
for slop and satisfies duration, distance, velocity, pressure, edge-width, and
contact-count limits. For every standard machine, a legal input not matched by
the table or its kind-specific clauses holds the current state and emits
nothing; this is the total default transition.

### Tap And Repeated Tap

- Defaults: one contact and one tap.
- All required contacts must down and up within motion slop and
  `:tap/max-duration-us`.
- Repeated taps must target the same node, use the required contact count, begin
  within `:multi-tap/max-delay-us`, and remain within `:multi-tap/slop` of the
  first tap centroid.
- An unexpected join or lift before recognition rejects the tap candidate.
- Output is one `:recognized` event with `:count`, `:contacts`, and final
  `:position`. Intermediate taps are not dispatched.
- `:double-tap` is authoring shorthand for `:tap` with `:count 2`; it is not a
  different wire protocol.

The complete tap configuration is `:count` (positive integer, default `1`),
`:contacts` (canonical range with equal min/max, default one), `:max-duration-us`, `:slop`,
`:max-delay-us`, and `:multi-tap-slop`; absent threshold keys resolve through
the profile names shown above.

The normative tap machine states and local values are:

```clojure
{:states #{:awaiting-down :contacts-down :between-taps :ended :rejected}
 :initial :awaiting-down
 :state {:completed-count 0
         :current-pointer-ids #{}
         :current-down-time-us nil
         :current-origin-positions {}
         :current-positions {}
         :first-tap-centroid nil
         :last-tap-centroid nil
         :last-up-time-us nil}}
```

Its ordered transition algorithm is:

1. In `:awaiting-down`, the first down starts one tap: record its time, pointer
   id, and position, then enter `:contacts-down`. Further downs are admitted
   until the canonical contact maximum is reached. A down beyond that maximum
   rejects. Recognition duration is measured from that first down.
2. In `:contacts-down`, record each admitted pointer's down and current position. Every
   actual/coalesced sample must remain within motion slop of that same pointer's
   recorded position. Motion slop is reset for every tap. A cancel, slop breach,
   duration greater than `:tap/max-duration-us`, or a lift before the required
   minimum contact count was reached rejects.
3. After the required contact count has been reached, those contacts may lift in
   any order. The final required up completes the tap when duration is within
   the limit. Its centroid is computed from each required contact's final up
   position retained in `:current-positions`. The first completed tap stores
   `:first-tap-centroid`; every later
   centroid must be within `:multi-tap/slop` of it. Multi-tap slop never resets.
4. Increment `:completed-count` on completion and publish it to the arbitration
   projection. If it equals configured `:count`, propose accept with the complete
   recognized payload and enter `:ended`, subject to arena deferral. Otherwise,
   set `:last-up-time-us`, start or restart `:next-tap` at exactly
   `last-up-time-us + :multi-tap/max-delay-us`, clear only the per-tap pointer,
   time, and origin fields, and enter `:between-taps`.
5. In `:between-taps`, a down at or before that deadline cancels `:next-tap`,
   initializes the next tap exactly as step 1, and enters `:contacts-down`. A
   matching timer-fired input, a later down, or a down outside multi-tap slop
   rejects. The timer is restarted after every intermediate completed tap, never
   measured from the first tap.
6. `:ended` and `:rejected` are terminal. Any otherwise-unmatched legal input
   follows the standard no-op transition. Intermediate taps are never emitted.

A higher-count tap holds competing lower-count taps through the arena rule
above. When the higher-count alternative expires or rejects, the arena revives
the greatest completed deferred count; machine state is not rewound.

### Long Press

- Starts a timer on the final required down.
- Rejects before acceptance when contact count leaves its configured range or
  centroid movement exceeds motion slop.
- Accepts and emits `:start` when the timer fires.
- Emits `:update` for accepted movement, `:end` after the final up, and
  `:cancel` on cancellation.
- Leaving the configured contact range rejects before acceptance and emits
  `:cancel` after acceptance, regardless of a broader authored contact-loss
  policy.

Long-press configuration is `:contacts` as `{:min n :max n}`, `:delay-us`,
`:slop`, and `:contact-loss`. It records the centroid at the final required
down. Before acceptance every actual move is tested against that centroid;
after acceptance updates use the same centroid-relative payload as pan. Its
timer is cancelled on every terminal transition.

### Pan / Drag

- Tracks centroid motion for its configured contact range.
- Accepts when motion exceeds slop on `:x`, `:y`, or `:free` axis.
- Default start behavior is `:slop`: cumulative translation starts at the
  acceptance point. `:down` includes pre-acceptance displacement.
- Emits start/update/end/cancel with cumulative `:translation`, per-packet
  `:delta`, and bounded-window `:velocity`.
- Falling below the configured minimum contact count ends an accepted pan by
  default. `:degrade` rebases its centroid when the remaining count is still a
  machine-valid configuration; `:hold` emits no updates until the range becomes
  valid again.
- `:drag` and application scroll recognizers are configurations of `:pan`.

Pan configuration is `:contacts` as `{:min n :max n}`, `:axis` (`:x`, `:y`, or
`:free`), `:start-at` (`:slop` or `:down`), `:slop`, and `:contact-loss`. Axis
qualification requires the primary component to exceed slop and the orthogonal
component not to exceed it before acceptance. Once accepted, an axis pan zeros
the orthogonal translation, delta, and velocity components.

### Swipe And Fling

- Swipe is discrete and accepts on final up when configured direction, minimum
  distance, maximum duration, and minimum velocity all match.
- Fling is discrete and independently accepts on final up when canonical
  velocity is within the configured minimum and maximum. It never observes a
  pan candidate or consumes a pan payload.
- Swipe emits direction, displacement, duration, and velocity. Fling emits
  velocity and direction.
- A pan and fling declaration may share a cooperative coexistence group so both
  the pan end and fling value are emitted.

When cooperative pan and fling both succeed on one up, the pan `:end` event and
all of its dispatches precede the fling `:recognized` event and its dispatches.
Both report the same canonical terminal velocity. Neither suppresses or mutates
the other.

Swipe configuration is `:contacts`, `:direction` (one of `:left`, `:right`,
`:up`, `:down`, or `:any`), `:min-distance`, `:max-duration-us`, and
`:min-velocity`. Fling configuration is `:min-velocity`, `:max-velocity`, and
`:direction`. Direction uses the same horizontal-on-tie rule as the evaluator.

### Transform, Scale, And Rotation

- Transform supports a configurable contact range and may start with one
  contact when one-finger translation should grow into multi-touch transform.
- It accepts when centroid translation, span change, or angular change passes
  its configured slop.
- It emits focal point, cumulative and incremental translation, cumulative and
  incremental scale, and cumulative and incremental rotation in radians.
- Contact joins and leaves rebase the reference centroid/span/angle without a
  discontinuity in cumulative values.
- Transform uses `:contact-loss :degrade` by default while its remaining count
  is within the configured range, and ends when it falls below the minimum.
- `:scale` / `:pinch` and `:rotation` are projections of the transform template.
  They can coexist only under the arena rules.

Transform configuration is `:contacts`, `:translation-slop`, `:scale-slop`
(absolute `log(scale)`), `:rotation-slop` (absolute radians), and
`:contact-loss`. Its reference baseline is rebased after every join/lift, while
the cumulative output remains unchanged. For two or more contacts its span and
angle use the two lowest pointer ids; scale is `1.0` and rotation `0.0` until a
second contact exists. Rotation deltas are normalized to `[-pi, pi)`.

### Edge Pan

- The initial contact must begin within `:edge/width` of a configured viewport
  edge and then move inward past motion slop.
- Its lifecycle and payload otherwise match pan, with an additional `:edge`.
- A host-reserved navigation gesture may prevent delivery or cancel it; an
  application must not assume that every configured edge is interceptable.

Edge-pan configuration is `:edge` (`:left`, `:right`, `:top`, or `:bottom`),
`:contacts`, `:axis`, `:slop`, and `:contact-loss`. Inward means positive x
from left, negative x from right, negative y from top, and positive y from
bottom. The initial sample is in the edge strip when its inclusive distance to
that edge is less than or equal to `:edge/width`.

### Pressure Press

- Requires the `:pressure` capability and explicit start and optional peak
  thresholds in recognizer configuration or the terminal profile.
- Emits start/update/end/cancel with normalized pressure.
- It remains dormant with a warning when pressure is unavailable.

Pressure configuration is `:contacts`, `:start-threshold`, `:release-threshold`
(default equal to start threshold), and `:peak-threshold` (optional). Start
requires `pressure >= start-threshold`; when release is lower than start, an
accepted press remains active while pressure is at least release. `:update`
emits only when the actual normalized pressure differs from the last emitted
pressure; peak is included once when first crossed.

### Standard Gesture Payloads

Gesture payload maps are closed. They contain exactly the keys below; the
common envelope carries `:position`, `:pointer-ids`, and `:time-us`.

```clojure
{:tap {:count <positive-integer>
       :contacts <positive-integer>
       :duration-us <non-negative-integer>}
 :long-press {:translation {:x <number> :y <number>}
              :delta {:x <number> :y <number>}
              :duration-us <non-negative-integer>}
 :pan {:translation {:x <number> :y <number>}
       :delta {:x <number> :y <number>}
       :velocity {:x <number-per-second> :y <number-per-second>}}
 :swipe {:direction <direction-keyword>
         :displacement {:x <number> :y <number>}
         :distance <non-negative-number>
         :duration-us <non-negative-integer>
         :velocity {:x <number-per-second> :y <number-per-second>}}
 :fling {:direction <direction-keyword>
         :velocity {:x <number-per-second> :y <number-per-second>}
         :speed <non-negative-number-per-second>}
 :transform {:translation {:x <number> :y <number>}
             :delta {:x <number> :y <number>}
             :scale <positive-number>
             :scale-delta <positive-number>
             :rotation <radians>
             :rotation-delta <radians>}
 :edge-pan {:edge <edge-keyword>
            :translation {:x <number> :y <number>}
            :delta {:x <number> :y <number>}
            :velocity {:x <number-per-second> :y <number-per-second>}}
 :pressure-press {:pressure <number-in-zero-to-one>
                  :peak? <boolean>}}
```

`:scale` and `:rotation` projections use the corresponding closed subsets of
the transform payload. For transform, scale, and rotation, the common envelope's
`:position` is the emitted focal point, equal to the current contact centroid;
there is deliberately no duplicate focal key inside the closed payload.
Continuous `:start`, `:update`, and `:end` use the same
kind-specific shape. `:cancel` uses the last successfully emitted payload plus
`:reason`, one of `:pointer-cancel`, `:arena-lost`, `:input-loss`, `:reset`,
`:coordinate-space-change`, `:teardown`, or `:recognizer-fault`. A continuous
gesture cancelled before its first update uses the start payload. No
implementation-specific payload keys are permitted.

## Gesture Output

Every recognized semantic value has a common envelope:

```clojure
{:event/kind :gesture
 :gesture/id [81 ::map-transform]
 :gesture/kind :transform
 :phase :update
 :node-id ::map
 :recognizer/id ::map-transform
 :arena-id 81
 :generation-id "c18496e9-1a16-4b1d-9028-e35ba0dc7af8"
 :origin-frame-id 42
 :observed-frame-id 45
 :coordinate-space-id 7
 :time-us 812338600
 :pointer-ids #{11 12}
 :position {:x 180.0 :y 300.0}
 :payload
 {:translation {:x 12.0 :y -4.0}
  :delta {:x 1.5 :y -0.5}
  :scale 1.25
  :scale-delta 1.02
  :rotation 0.31
  :rotation-delta 0.02}}
```

Discrete gestures use only `:recognized`. Continuous gestures use
`:start`, zero or more `:update` values, and exactly one `:end` or `:cancel`
after start. A candidate emits nothing before acceptance unless the emitted
value is explicitly marked as targeted raw pointer data.

Targeted raw pointer output has `{:event/kind :pointer}` and preserves the
normalized packet plus `:arena-id`, `:origin-frame-id`, `:target-path`, and
current arena status. Predicted samples may appear only on this raw output.

## Subscriber Model

Subscriber interest is changed through an explicit command stream:

```clojure
{:subscription/op :add
 :subscription/id "save-handler-1"
 :subscriber/id ::project-controller
 :node-id ::save
 :event-kind :tap}
```

```clojure
{:subscription/op :remove
 :subscription/id "save-handler-1"}
```

Keyboard focus and subscription registration remain separate operations:

```clojure
{:subscription/op :add
 :subscription/id "editor-keyboard-1"
 :subscriber/id ::editor-controller
 :node-id ::editor
 :event-kind :keyboard
 :keyboard/phases #{:down :up :cancel}}
```

```clojure
{:subscription/op :focus/set
 :focus/id ::editor
 :node-id ::editor}
```

`:add` accepts optional `:gesture/phases`, a non-empty set of legal phases, and
`:raw?`, default `false`. `:event-kind` is either one declared gesture kind,
`:pointer`, or `:keyboard`; `:node-id` is required. A gesture registration
matches when node id, gesture kind, and phase match. A raw registration matches
only a targeted raw pointer event for its terminal target node. A keyboard
registration matches when its node id is the current focused node. Keyboard
registrations may optionally provide `:keyboard/phases`, a non-empty subset of
`:down`, `:up`, and `:cancel`; without it, `:down` and `:up` match. No registration is inherited
from an ancestor path entry. Unknown node ids are valid registrations and
simply match no pointer arena or focused keyboard event. `:raw?` is not valid
with `:event-kind :keyboard`; the keyboard stream is already the raw event
stream, and setting it true is a malformed subscription command.

The runtime emits one dispatch value per matching registration:

```clojure
{:dispatch/kind :dao.gui.event/subscriber
 :subscription/id "save-handler-1"
 :subscriber/id ::project-controller
 :node-id ::save
 :event-kind :tap
 :event <gesture-or-pointer-value>}
```

For a keyboard registration, `:event-kind` is `:keyboard` and `:event` is the
targeted keyboard value. The dispatch retains the subscription order and does
not execute the subscriber:

```clojure
{:dispatch/kind :dao.gui.event/subscriber
 :subscription/id "editor-keyboard-1"
 :subscriber/id ::editor-controller
 :node-id ::editor
 :event-kind :keyboard
 :event {:event/kind :keyboard
         :runtime/seq 919
         :output/seq 0
         :event/phase :down
         :focus-id ::editor
         :node-id ::editor
         :key {:code :key-a
               :logical :a
               :location :standard}
         :modifiers #{:control}
         :repeat? false
         :time-us 812338700}}
```

Rules:

- subscriptions are ordered by successful add command.
- subscription ids are unique per runtime binding.
- removing an unknown id is idempotent.
- a pointer arena snapshots matching pointer registrations at creation.
- keyboard registrations are evaluated against the current focus when each
  keyboard event is consumed.
- additions and removals affect later arenas, not an active one.
- duplicate interests require distinct subscription ids and each receives a
  dispatch value.
- no subscriber callback executes inside `dao.gui.event`.
- teardown cancels active arenas, emits their final cancellation dispatches,
  then releases the registry and closes runtime-owned outputs.

Malformed add commands, duplicate subscription ids, illegal event kinds, or
illegal gesture or keyboard phases emit a diagnostic and leave the registry
unchanged. A successful remove or focus command is applied before any keyboard
or pointer packet at the same timestamp because of canonical source order.
Dispatches are produced in registration order; one event is fully fanned out
before the next event. A full dispatch stream
parks the binding as specified in Transport, rather than changing registration
or event order.

## Mobile Web Touch Policy

Browser touch ownership is decided before application gesture recognition.
Because the web terminal paints one canvas, it realizes per-region policy as
transparent DOM policy overlays derived from presented geometry.

Accepted authored policies are:

- `:auto`
- `:none`
- `:manipulation`
- a valid set drawn from `:pan-x`, `:pan-y`, `:pan-left`, `:pan-right`,
  `:pan-up`, `:pan-down`, and `:pinch-zoom`

The default is `:auto`. Invalid combinations are compiler errors. The effective
policy is the CSS `touch-action` intersection of the explicit policies along
the interaction path.

For intersection, normalize `:pan-x` to `#{:pan-left :pan-right}`, `:pan-y` to
`#{:pan-up :pan-down}`, `:manipulation` to all four pan directions plus
`:pinch-zoom`, `:none` to the empty set, and `:auto` to an unconstrained top
value. Intersect from root to target. Serialize the empty result as `:none`, an
unchanged unconstrained result as `:auto`, the full manipulation set as
`:manipulation`, and every other result as a set of direction and pinch
keywords. This makes policy composition deterministic without constructing a
DOM ancestry graph.

Canonical CSS serialization is:

| Effective EDN value | CSS `touch-action` |
| --- | --- |
| `:auto` | `auto` |
| `:none` | `none` |
| `:manipulation` | `manipulation` |
| both horizontal directions | `pan-x` |
| `:pan-left` only | `pan-left` |
| `:pan-right` only | `pan-right` |
| both vertical directions | `pan-y` |
| `:pan-up` only | `pan-up` |
| `:pan-down` only | `pan-down` |
| `:pinch-zoom` | `pinch-zoom` |

When horizontal, vertical, and pinch permissions coexist, serialize their
canonical tokens in that order separated by spaces. A terminal advertises the
tokens it can realize in its input profile. Missing token support invokes the
diagnosed fallback below; it is not silently broadened to `auto`.

After presenting geometry, the web terminal:

- derives overlay identity from `(generation-id, frame-id, node-id,
  region-index, effective-touch-action)`
- reuses unchanged keyed overlay elements and removes only obsolete keys
- maps Cartesian logical bounds to CSS pixel bounds
- maps effective paint precedence deterministically to DOM stacking order
- assigns each overlay its effective `touch-action`
- assigns `pointer-events:auto`, `aria-hidden:true`, no accessibility role, and
  no tab stop
- commits the complete overlay projection in the same presentation batch before
  publishing the frame as interactive
- listens for Pointer Events at a stable terminal root
- uses root pointer capture after down to survive overlay replacement
- emits normalized packets regardless of which overlay supplied policy

The overlays are non-painting and carry no accessibility role or application
semantics. They exist only so browser DOM hit-testing can choose `touch-action`
before contact. `dao.gui.event`, not the DOM, remains authoritative for semantic
hit-testing and target selection.

Overlay DOM is an idempotent projection of the same canonical presented
geometry, clipping, and precedence used by semantic hit-testing. It is never a
second semantic scene graph. A frame is not input-active until that projection
commits successfully.

`:dao.gui.event/browser-policy-conflict` means the presented frame assigns two
different effective policies to the same logical `(node-id, interaction-path)`
or produces duplicate overlay identities with different bounds, precedence, or
policy. The terminal rejects that overlay batch, retains the previously active
geometry and overlays, and emits the diagnostic with both conflicting values.
It does not choose one policy. `:browser-policy-mismatch` instead means one
internally consistent policy cannot be realized by the host.

If a browser cannot express the effective policy, or the overlay batch cannot
be committed consistently, the terminal emits
`:dao.gui.event/browser-policy-mismatch` and applies `touch-action:none` to the
stable terminal root for that surface. This fallback captures more interaction
than requested and may disable native scrolling, zoom, momentum, or overscroll,
so it is always diagnostic and never silent.

When the browser takes over panning or zooming, `pointercancel` becomes a
normalized `:cancel`. Changing an overlay's policy after pointer down has no
effect on that active sequence.

## Coordinate-Space Changes

Viewport size, device-pixel ratio, orientation, or system-view mapping may
establish a new root coordinate space. Local node transforms, scroll offsets,
clips, layout, and animation do not. The terminal emits this before geometry in
the new space:

```clojure
{:message/kind :dao.terminal/coordinate-space-change
 :generation-id "c18496e9-1a16-4b1d-9028-e35ba0dc7af8"
 :old-coordinate-space-id 7
 :coordinate-space-id 8
 :viewport {:width 844.0 :height 390.0}
 :reason :orientation-change}
```

`:viewport` is required, uses finite positive GUI logical pixels, and is the
normative source for edge distances and Flutter y-axis normalization. The
initial coordinate space is established by the first coordinate-space-change
after reset, with `:old-coordinate-space-id nil`. Presented geometry and pointer
packets must name that installed space. A size change always mints a new id.
The reducer stores the id and viewport in `:coordinate-spaces`, makes the id
active before processing subsequent geometry, and snapshots that complete value
into every new arena and machine evaluation context. Reset clears the map;
active arenas retain their old snapshot only until their required cancellation
has been emitted.

The terminal first emits cancel packets for every active pointer in the old
space. The runtime then cancels any remaining arenas, clears old geometry, and
accepts no new down until presented geometry for the new space is active.
Recognizers never compute deltas across coordinate spaces.

## Transport, Coalescing, And Backpressure

Pointer lifecycle packets must not disappear silently.

- terminals may coalesce consecutive moves for one pointer into the ordered
  `:samples` vector of a later move packet
- terminals must not coalesce across down, up, cancel, generation, frame, or
  coordinate-space boundaries
- down, up, and cancel are never evicted intentionally
- a runtime whose dispatch stream is full parks its input cursor rather than
  dropping an already-produced gesture phase
- recognized semantic dispatch values are never conflated, evicted, or
  rewritten into latest-wins state

A conforming canonical runtime-input DaoStream **either refuses an append**
(`:dao.stream/full`) **— and the binding parks and retries**, because a
dispatch lane over a real outbound path may refuse transiently — **or evicts
and reports the loss as `:dao.stream/gap`** to a cursor that spans the
eviction, which is what an in-process ring buffer must do: bounded retention
that never refused would otherwise be silently lossy, and a reject-mode buffer
with no destructive take would be full forever. The recovery path for the
second case is the binding's `:input-gap` result plus `recover-input-gap` with
the canonical `:dao.terminal/input-loss` runtime input described below —
exactly what the artifact demo does in production.

The bare `:dao.stream/gap` outcome contains insufficient causal information to
construct that envelope. On receiving it, `advance` returns `:input-gap` with
the recovery cursor the outcome carried, without advancing the binding's own
cursor or synthesizing a diagnostic. Recovery requires the owning multiplexer
(or host) to supply an explicit input-loss value and a cursor — the recovery
cursor the result carried, or one the host minted on the input stream — or to
perform explicit teardown. A bare gap is therefore a transport-boundary
failure, not a replay input.

Every runtime-owned output stream is lossless from the binding's perspective.
If any `append!` returns `:dao.stream/full`, the binding retains that value as
the head pending output, retains every later output in order, and reads no
further input until pending output can be flushed. Outputs successfully
appended before the full result are removed from the pending queue and are
never appended again. An output whose `append!` returns `closed`,
`invalid-value`, or `transport-error` is gone: the binding drops that pending
value with one host-side diagnostic rather than parking on it or retrying.

Only a full dispatch stream produces
`:dao.gui.event/dispatch-backpressure`. On entry to such a parked interval, the
binding appends one diagnostic value to the tail of the pending outputs for the
causing runtime input. Its `:output/seq` follows every reducer-produced output
already assigned to that input. If the diagnostic stream is full when that
queued diagnostic reaches the head, it remains pending and parks the binding;
this does not recursively produce another backpressure diagnostic.

A parked interval is the maximal contiguous sequence of `advance` results that
are parked on the same destination stream and the same head pending value. It
ends when that value is appended or ceases to be the head pending value. A
dispatch-backpressure diagnostic is emitted at most once during that interval.
A later full result for a different dispatch value begins a new interval.

After consuming one valid teardown input, the binding reads no later input. It
flushes all cancellation effects, cancellation events, dispatches, traces, and
diagnostics in their existing order, then calls `close!` on all seven
runtime-owned output streams. It never closes the input stream.

Input loss is signalled as:

```clojure
{:message/kind :dao.terminal/input-loss
 :generation-id "c18496e9-1a16-4b1d-9028-e35ba0dc7af8"
 :after-input-seq 918
 :before-input-seq 922
 :affected-pointer-ids #{11 12}
 :reason :stream-capacity}
```

The terminal or DaoStream adapter reports packet facts only; it never names
runtime arena ids. `:after-input-seq` is the last retained packet before the
gap, and `:before-input-seq` is the first retained packet after it. Optional
`:affected-pointer-ids` is present only when the producer can prove the exact
set. The runtime maps those pointers to arenas and emits cancellation traces
naming the derived arena ids. When the set is absent, it cancels every active
arena in the generation.

The runtime does not guess missing movement, synthesize an up, or emit a
successful gesture from an incomplete sequence.

`input-seq` indexes packets the terminal actually emitted. A gap means a missing
packet index or an observed DaoStream retention gap, not elapsed silence. Contact
liveness is defined only by down, up, and cancel facts; a stationary contact
needs no heartbeat and progresses through explicit timer tuples. A device that
disappears without emitting cancellation is an unobservable host limitation and
must be listed in that terminal's capability matrix.

## Terminal Signals

Existing terminal accounting signals remain:

```clojure
{:message/kind :dao.terminal/reset
 :generation-id <opaque-id>}
```

```clojure
{:message/kind :dao.terminal/rejection
 :submission-id <integer>
 :reason <keyword>}
```

```clojure
{:message/kind :dao.terminal/frame-skipped
 :submission-id <integer>}
```

When host focus is lost without an application focus command, the terminal
emits:

```clojure
{:message/kind :dao.terminal/focus-lost
 :generation-id <opaque-id>
 :focus-id <focus-id-or-nil>
 :reason <keyword>}
```

The runtime accepts `:dao.terminal/focus-lost` only for the current generation
and current focus id, then clears focus and `:keys-down`. A stale focus-loss
value produces `:dao.gui.event/focus-mismatch` and does not alter state. Focus
loss does not synthesize keyboard up events.

Protocol errors use:

```clojure
{:message/kind :dao.terminal/protocol-error
 :error/kind <keyword>
 :generation-id <opaque-id>
 :frame-id <optional-integer>
 :input-seq <optional-integer>}
```

Frame rejection and skipping do not alter active geometry or pointer capture.
Reset, coordinate-space change, and input loss do.

## Diagnostics

Diagnostics are explicit data on a dedicated stream:

```clojure
{:diagnostic/kind :dao.gui.event/invalid-recognizer
 :severity :error
 :node-id ::map
 :recognizer/id ::map-transform
 :reason :unbounded-window}
```

Normative diagnostic kinds are:

- `:dao.gui.event/unsupported-region`
- `:dao.gui.event/invalid-recognizer`
- `:dao.gui.event/recognizer-fault`
- `:dao.gui.event/unsupported-capability`
- `:dao.gui.event/unrecognized-event-kind`
- `:dao.gui.event/malformed-keyboard-event`
- `:dao.gui.event/focus-mismatch`
- `:dao.gui.event/duplicate-key-down`
- `:dao.gui.event/orphan-key-up`
- `:dao.gui.event/no-active-frame`
- `:dao.gui.event/future-frame-input`
- `:dao.gui.event/stale-frame-input`
- `:dao.gui.event/stale-generation-input`
- `:dao.gui.event/coordinate-space-mismatch`
- `:dao.gui.event/profile-mismatch`
- `:dao.gui.event/input-sequence-gap`
- `:dao.gui.event/duplicate-pointer-down`
- `:dao.gui.event/orphan-pointer-packet`
- `:dao.gui.event/capture-lost`
- `:dao.gui.event/late-timer`
- `:dao.gui.event/late-runtime-input`
- `:dao.gui.event/browser-policy-conflict`
- `:dao.gui.event/browser-policy-mismatch`
- `:dao.gui.event/dispatch-backpressure`

Severities are `:warning` and `:error`. Warnings omit only the unsupported
candidate or optional value. Errors drop or cancel the affected pointer,
candidate, arena, or dispatch explicitly; they do not retroactively invalidate
a presented frame.

Trigger conditions are normative: unsupported or non-rectangular geometry is
`:unsupported-region`; declaration/DSL validation failure is
`:invalid-recognizer`; evaluation failure is `:recognizer-fault`; a missing
declared capability is `:unsupported-capability`; an illegal source/kind pair is
`:unrecognized-event-kind`; down without current geometry is `:no-active-frame`;
frame id greater or less than the permitted id is respectively
`:future-frame-input` or `:stale-frame-input`; generation or coordinate-space
mismatch uses its named diagnostic; a down naming an absent or non-latest
profile id is `:profile-mismatch`; a nonconsecutive `:input-seq` is
`:input-sequence-gap` (checked separately for each input modality, and after a gap the received event becomes the new modality-local sequence anchor); down for an
active id is `:duplicate-pointer-down`;
move/up/cancel for an inactive id is `:orphan-pointer-packet`; terminal lost
capture without a valid cancel is `:capture-lost`; a stale or invalidated timer
key is `:late-timer`; regressing canonical runtime time is
`:late-runtime-input`; browser policy conditions are defined above; and a
parked full dispatch stream emits `:dispatch-backpressure` once per parked
interval; a missing or wrong keyboard field is `:malformed-keyboard-event`; a
keyboard or focus-loss value naming a non-current focus is `:focus-mismatch`; a
non-repeat down for an already-held physical key is `:duplicate-key-down`; and
an up for a physical key not held in `:keys-down` is `:orphan-key-up`.

Every diagnostic must include its kind, severity, causing `:runtime/seq`, and
all available causal ids. `:reason` keywords and those fields are conformance
data. Optional human-readable `:message`, stack data, and host error text are
implementation-discretionary and are omitted from canonical traces.

## Trace And Numeric Conformance

The debug and test trace is versioned EDN:

```clojure
{:dao.gui.event.trace/version 1
 :runtime-inputs [<canonical-runtime-input>]
 :runtime-outputs [<effect-pointer-keyboard-gesture-dispatch-or-diagnostic-value>]}
```

It records the complete causally ordered values needed for replay: presented
geometry, input profiles, normalized pointer and keyboard events including
keyboard lifecycle values, focus commands and focus effects, timer
requests/results, subscription commands, contact changes, arena merges and
decisions, dispatches, and diagnostics. It contains no host callbacks or
unrecorded scheduler state.

The trace codec is a conformance surface, not a compatibility adapter for the
former tap draft. `:on-tap` is desugared before tracing, so a trace contains only
the canonical recognizer declaration and new gesture envelope.

Cross-runtime comparison requires exact equality for ids, targets, phases,
decisions, ordering, and non-numeric payloads. Numeric gesture projections are
rounded to the nearest multiple of `1e-6` before trace encoding; ties round to
the even multiple. NaN and infinite values are invalid. This is the canonical
comparison rule for CLJ, CLJS, and CLJD and replaces byte-for-byte comparison of
unrounded host floating-point results.

### Executable Fixture Contract

Each conformance fixture is one EDN value suitable for direct reducer replay:

```clojure
{:fixture/id :tap/single
 :initial-state nil
 :inputs [<canonical-runtime-input>]
 :expect {:outputs [<complete-output-values-in-order>]
          :state {:generation-id <opaque-id-or-nil>
                  :coordinate-space-id <id-or-nil>
                  :active-frame-id <id-or-nil>
                  :profile-ids [<ids-in-order>]
                  :subscription-ids [<ids-in-registration-order>]
                  :focus {:id <focus-id-or-nil>
                         :node-id <node-id-or-nil>
                         :generation-id <opaque-id-or-nil>}
                  :keys-down [<physical-key-codes-in-stable-edn-order>]
                  :last-keyboard-seq <input-seq-or-nil>
                  :active-pointer-ids [<ids-in-stable-edn-order>]
                  :active-arena-ids [<ids-in-creation-order>]
                  :scheduled-timer-keys [<keys-in-stable-edn-order>]
                  :last-runtime-seq <integer>
                  :last-runtime-time-us <integer-or-nil>
                  :next-arena-id <integer>}}}
```

`nil` initial state means the canonical empty reducer state. Fixture inputs must include
geometry, profile, and subscriptions explicitly before their first use.

Each named fixture is committed as an individual, minimal, hand-checkable EDN
value. A repository script may generate or update those files, but the committed
fixture, not the generator's current output, is the conformance artifact.
Generated expected values must be reviewed and committed explicitly; a test may
not obtain its expectation by invoking the implementation under test.

Every fixture whose owning boundary is `dao.gui.event` must replay unchanged on
CLJ, CLJS, and CLJD and must produce the same complete outputs and the
keyboard-extended state projection shown above.
Runtimes may use different resource-loading mechanisms, such
as embedding the committed EDN corpus into generated CLJC data, provided the
values are identical.

The fixture requirement applies to every in-scope reducer or binding scenario,
every diagnostic produced by `dao.gui.event`, and every invalid-machine
validation rule. Scenarios and diagnostics owned solely by the deferred compiler
or terminal boundaries are required when those boundaries are implemented and
are not reducer-conformance fixtures in this implementation pass. Separate
boundary tests, rather than reducer replay fixtures, cover DaoStream parking,
host packet normalization, presentation, and DOM overlay behavior.

Expected
outputs contain complete values after canonical numeric rounding, including
timer cancellation and diagnostics; no wildcard comparison is allowed except
`{:any-of [...]}` around an explicitly declared host-capability alternative.
The keyboard-extended `:state` map above is the complete public fixture projection; no
other reducer fields appear and none of these keys may be omitted. A fixture
fails when an additional output, missing output, different order, or a different
projected state occurs.

It is derived mechanically from the logical reducer state: coordinate-space id
is `:active-coordinate-space-id`; active frame id is
`[:geometry :active :frame-id]`; profile ids are the `:profiles` keys in numeric
ascending order; subscription ids are `:subscription-order`; pointer ids are
the `:pointers` keys in stable EDN order; arena ids are live `:arenas` keys in
creation-sequence order; scheduled timer keys are the keys whose timer record is
`:scheduled`, in stable EDN order; and the remaining scalar fields are copied
directly. The full map-shaped reducer schema is inspectable implementation
state, while this derived keyboard-extended map is the sole cross-implementation state
comparison surface.

The repository must provide fixtures for every bullet in Conformance Scenarios,
plus one minimal fixture for each diagnostic kind and each invalid-machine
validation rule. It must maintain a manifest mapping each in-scope Conformance
Scenarios bullet, diagnostic kind, and invalid-machine validation rule to its
fixture or boundary test. One minimal fixture may cover several explicitly
listed obligations, but no obligation may be implicit. It must also provide a
generator that creates finite packet
traces with up to five contacts, then asserts: every emitted gesture references
an origin capture; every continuous start has exactly one later end/cancel;
every timer-fired value has a prior start of the same key; no ended arena or
pointer remains in the projected state; and replaying the same trace is exactly
deterministic. Generated traces may not substitute for the named regression
fixtures.

## Touch Capability Matrix

The matrix describes direct touchscreen conformance. `portable` means that
identical normalized traces and profiles have the same semantics. `capability`
means that the terminal profile must advertise the required fact. `reserved`
means that host ownership may prevent delivery or produce cancellation.

| Gesture / input | Android Flutter | iOS Flutter | Mobile web |
| --- | --- | --- | --- |
| tap / repeated tap | portable | portable | portable |
| long press | portable | portable | portable |
| pan / drag / application scroll | portable | portable | portable, subject to `touch-action` |
| swipe / fling | portable | portable | portable, subject to `touch-action` |
| scale / pinch / rotation / transform | portable | portable | portable, subject to `touch-action` |
| edge pan | reserved at system edges | reserved at system edges | reserved by browser navigation where applicable |
| pressure press | capability | capability | capability |
| contact geometry / tilt / twist | capability | capability | capability |
| coalesced or predicted samples | capability | capability | capability |

Trackpad pan/zoom, wheel, text selection, native drag-and-drop, context menus,
and accessibility activation remain separate non-touch protocols. Keyboard
events are defined by this document, but do not participate in the touch arena
contract.

## Host Conformance

Host conformance is deferred until the corresponding terminal adapters exist.
The current implementation may claim deterministic behavior only after the
canonical runtime inputs described by this document have been supplied.

A conforming Android/iOS Flutter terminal:

- observes raw Flutter pointer events rather than using Flutter gesture
  callbacks as the portable contract
- maps physical coordinates to Cartesian logical pixels
- preserves pointer ids, phases, timestamps, and available contact properties
- reports a device-adapted immutable input profile
- preserves physical delivery after down and reports host cancellation

A conforming mobile web terminal:

- consumes Pointer Events, not emulated mouse events, for touch recognition
- uses `getCoalescedEvents` and predicted events only when supported and marks
  them explicitly
- realizes geometry-derived touch-policy overlays
- normalizes browser coordinates and timestamps
- turns `pointercancel` and lost capture into explicit cancellation

A conforming keyboard-capable terminal:

- emits normalized `:keyboard` values for host key-down and key-up transitions
- preserves physical code, logical key when available, location, modifiers,
  repeat status, and monotonic timestamp
- applies focus-request effects from the event runtime and emits terminal
  focus-lost values when host focus disappears independently
- clears focus on reset and input loss without fabricating key-up transitions
- keeps committed text and IME composition outside the keyboard contract

Given identical canonical runtime inputs, every conforming runtime produces the
same ids, targets, phases, arena decisions, ordering, and non-numeric semantic
values. Numeric payloads match after canonical `1e-6` trace rounding.

## Conformance Scenarios

Implementations must test at least:

- one-, two-, and three-contact taps; single, double, and repeated taps
- single tap delayed by a competing double tap
- long press acceptance, early up, movement rejection, and cancellation
- child tap competing with ancestor vertical scroll pan
- axis-constrained and free pan, including pointer leaving the hit rectangle
- pan end with and without fling
- swipe direction, distance, duration, and velocity boundaries
- one-finger transform growing into two-finger scale and rotation without value
  discontinuity
- separate cooperative scale and rotation recognizers
- one-arena contact join and `:contacts/changed` ordering
- a new down bridging several unresolved arenas into the oldest arena
- accepted-arena late join with `:join-after-accept` enabled and disabled
- path-disjoint controls operating concurrently
- contact lift under `:end`, `:degrade`, and `:hold`
- contact-count overflow and duplicate-candidate merge cancellation
- captured movement across another interactive region without re-hit-testing,
  target change, or arena change
- edge pan delivered, cancelled by the host, and never delivered because the
  host reserved it
- pressure recognition with and without pressure capability
- custom machine validation, deterministic transition order, timers, and fault
  isolation
- capture across frame presentation and target disappearance
- rejected and skipped frames while a gesture is active
- terminal reset, coordinate-space or profile mismatch, duplicate ids, orphan
  packets, and input-sequence gaps
- local scrolling and animation without coordinate-space remint
- pointer-before-timer deadline ties, stale timer sequences, and late runtime
  input
- predicted-sample miscorrection without semantic state change
- web `:auto`, `:none`, `:manipulation`, pan, and pinch policies on overlapping
  regions
- focused keyboard down and up dispatch, repeat forwarding, modifier keys,
  unfocused input, focus replacement, and idempotent focus clear
- keyboard ordering against pointer and timer values at equal timestamps
- keyboard state clearing on reset, input loss, focus clear, and teardown
- keyboard dispatch backpressure and raw keyboard-stream retention
- atomic overlay publication, stable-root capture, accessibility exclusion,
  precedence mapping, and explicit policy-mismatch fallback
- bounded input and dispatch streams without silent lifecycle loss
- one retention gap naming and cancelling several affected arenas
- stationary long press without heartbeat packets
- cross-runtime trace replay with exact structural equality and canonical
  `1e-6` numeric comparison

## Design Rules

- model host input, time, recognition, arbitration, capture, subscription, and
  diagnostics as stream data
- preserve raw facts before interpreting gestures
- construct interaction paths explicitly from tuples; never infer a graph
- snapshot causally relevant frame, path, profile, recognizer, and subscriber
  values at pointer down
- change arena membership only through explicit contact join, lift, or merge
  tuples; pointer movement never changes the captured path
- keep active pointer capture stable across recompilation
- make every loss, cancellation, unsupported capability, and protocol violation
  observable
- use bounded, total recognizer machines with no host callbacks or hidden
  effects
- keep `dao.gui` pure and `dao.postgraphics` metadata inert for painting
- prefer one combined transform recognizer when translation, scale, and rotation
  must evolve together
- treat browser and operating-system ownership as an explicit restriction, not
  a portable gesture promise
- keep event interpretation independent of render-frame commits and host widget
  hit-test graphs

## Accepted Defaults

- the terminal emits normalized pointer packets, not recognized portable
  gestures
- `dao.gui.event` owns portable gesture recognition
- the pointer envelope supports touch, pen, mouse, and unknown devices; complete
  conformance in this revision is required for touch
- the standard recognizer vocabulary is extensible through validated machine
  data and targeted raw pointer streams
- hit geometry remains exact, rectangular, clipped, and topmost-first
- hit-testing occurs once on down; active sequences are never re-hit-tested
- interaction paths are explicit metadata
- arena exclusivity is local; path-disjoint controls may recognize concurrently
- unresolved arenas connected by a new down merge explicitly into the oldest
  arena and never split afterward
- gesture arena ties are deterministic
- capture lasts until pointer or arena termination, reset, space change, input
  loss, or teardown
- gesture thresholds come from explicit terminal profiles with declaration
  overrides
- mobile web touch ownership is explicit per region and realized with terminal
  policy overlays committed before the frame becomes interactive
- subscriptions and dispatch are streams, not callback execution
- recognized semantic dispatch is lossless; only raw terminal moves may be
  explicitly coalesced before interpretation
- packet-index continuity requires no heartbeat
- direct touch and raw keyboard transitions are the conformance scope; text,
  IME, wheel, and other non-touch modalities use separate protocols
- no compatibility adapter is defined for the former bare tap wire shape
