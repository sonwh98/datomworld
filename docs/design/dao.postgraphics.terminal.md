# Design: `dao.postgraphics.terminal`

## Summary

`dao.postgraphics.terminal` is the shared terminal-VM contract for concrete
hosts of `dao.postgraphics`.

It defines the interpreter responsibilities that every terminal must implement
regardless of host realization:

- bytecode ingress
- stream-bound construction
- validation
- VM-local execution state
- screen-space resolution
- canonical terminal signals
- frame accounting
- presentation / rejection semantics

Concrete hosts such as Flutter and WebGPU add only host-specific realization
details on top of this contract.

The current shared runtime realization of this contract lives in
`src/cljc/dao/postgraphics/terminal.cljc`. Host docs may define richer local
APIs, but stream binding, canonical signal helpers, and submission-sequence
handling should reuse that namespace rather than being re-invented per host.

## Responsibilities

Every conforming terminal MUST:

- expose a constructor / binding function that takes an abstract `dao.stream`
  cursor of complete `dao.postgraphics` frame programs and starts one terminal
  VM listening on that stream
- consume complete frame programs from that bound `dao.stream`
- validate each submitted frame before presentation
- maintain enough frame accounting to preserve the "accepted vs rejected vs
  skipped" semantics defined by `dao.postgraphics.md`; terminals that
  participate in `dao.gui.event` or expose presented-frame identity MUST assign
  a monotonically increasing presented-frame ID to each accepted frame
- hold the last accepted frame as the current visual and interactive truth
- repaint or resubmit when a new accepted frame arrives
- interpret op maps directly in bytecode order
- apply the final Cartesian-to-screen viewport transform appropriate to its
  host
- resolve translate-only clip rects into screen-space rectangles using the
  active transform stack
- when participating in `dao.gui.event` or another geometry-reporting surface,
  resolve interactive regions into screen-space rectangles using the active
  transform stack

Every conforming terminal MUST NOT:

- evaluate `dao.gui` components
- interpret `dao.scene`
- own layout or UI semantics

Concrete hosts MAY additionally expose direct-call helpers for tests, local
composition, or manual frame submission, but those helpers are optional
conveniences. They do not replace the required stream-bound constructor.

## Terminal Lifecycle

A terminal processes each submitted frame through the same abstract phases:

1. construct one terminal binding from a `dao.stream` cursor of complete frame
   programs
2. accept each stream submission into a terminal-local ingress sequence
3. allocate fresh frame-local VM state
4. validate the frame program against the base spec and any active versioned
   addenda
5. interpret ops in bytecode order, mutating VM-local state and producing
   host-specific draw intents
6. present the accepted result on the host surface
7. publish canonical signals and any opted-into geometry-report data derived
   from the presented frame

The constructor / binding function owns exactly one VM instance, one
`generation-id` namespace, and one reader on the supplied stream. A new
constructor call creates a new binding unless a host doc explicitly defines
stateful reuse behind the same constructor surface.

If validation or interpretation fails before presentation, the terminal emits a
Frame Rejection Signal and does not advance presented-frame identity where that
surface exists. The last successfully presented frame remains both visually and
interactively active.

Validation MAY be realized in one phase or multiple phases. A host that uses
runtime guards after an initial validation pass still owes the same producer-
visible contract: an invalid frame emits the canonical rejection signal, does
not become the new interactive truth, and triggers the host's documented error
hook if one exists.

## VM-Local State

The terminal VM is defined by host-independent interpretation state. A concrete
host may cache extra runtime objects, but those are implementation details, not
part of the producer-visible contract.

All state slots reset to their default at the start of each accepted frame's
interpretation. Defaults are part of the abstract contract and apply to every
conforming host.

### Core v1 State

- **`model-stack`** - LIFO stack of transforms. Default: `(identity)` (one
  identity matrix on the stack). The identity bottom is permanent;
  `:transform/pop` on a stack of size 1 is a frame-validation error.
- **`clip-stack`** - LIFO stack of resolved screen-space clip rects.
  Default: empty.
- **`submission-id`** - terminal-local ingress sequence number. Default: the
  next integer after the most recently accepted submission, or `0` after a
  terminal reset.
- **`presented-frame-id`** - monotonically increasing ID of the most recently
  accepted and presented frame, when the host participates in
  `dao.gui.event` or otherwise exposes presented-frame identity. Default
  (when the surface exists): the next integer after the most recently
  presented frame, or `0` after a terminal reset.
- **`generation-id`** - terminal-reset namespace token used by canonical
  signals. Default: a fresh, terminal-chosen identifier per binding /
  reset.

### v2 State

- **`camera-matrix`** - active projection × view matrix. Default: `nil`
  (no camera set). 3D draw ops require a non-nil camera-matrix; a 3D draw
  with `camera-matrix = nil` is rejected.
- **`depth-test`**, **`depth-write`** - active 3D depth booleans.
  Default: `false` for both.
- **depth attachment / depth buffer state** - whichever host mechanism
  realizes v2 depth semantics. Default: cleared to the far value at the
  start of each frame and on `:camera3d/set` when a previous camera was
  active.

### v4 State

- **`lighting-enabled`** - active lighting mode. Default: `false`.
- **`lights`** - ordered active light list. Default: empty.
- **`camera-pos`** - world-space eye position used by the lighting
  equation. Default: derived from the active `:camera3d/set` (host docs
  pin the derivation); undefined when no camera is set, which is fine
  because lighting requires a 3D draw which requires a camera.
- **`render-target-stack`** - active nested target stack. Default: a stack
  of size 1 whose bottom is the default framebuffer (the host surface).
  `:target/pop` on a stack of size 1 is a frame-validation error.
- **`target-registry`** - map of produced target IDs visible to later ops in
  the same frame. Default: empty; populated by `:target/push` and consulted
  by `:texture/source :target/id ...` lookups within the same frame.

## Abstract Terminal Operations

Concrete terminals may expose any public API they want, but each host
implementation must effectively provide these abstract operations.

### `bind-terminal`

Input:

- host surface / host options as needed
- one abstract `dao.stream` cursor of complete `dao.postgraphics` frame
  programs

Output:

- one terminal binding / handle / widget / host object, depending on the host

Responsibility:

- allocate one VM instance
- allocate a fresh `generation-id`
- attach one reader to the supplied stream
- begin consuming submitted frame programs in stream order
- emit a VM Reset Signal when the host exposes canonical signals

Hosts MAY expose extra direct-call helpers for tests, local composition, or
manual frame submission, but a conforming terminal surface always includes
`bind-terminal` or an equivalent constructor.

### `validate-frame!`

Input:

- frame program
- active language version surface (v1, v2, v3, v4)
- host capabilities when relevant

Output:

- accepted frame, or canonical rejection with constrained `:reason`

Responsibility:

- enforce base Failure Semantics
- enforce stack-balance rules
- enforce op-field and type constraints from v2-v4
- reject unsupported ops only when the host explicitly does not implement them

### `init-frame-state`

Produces fresh frame-local interpreter state:

- stacks reset to defaults
- per-frame light / camera / target state cleared
- host-specific transient lowering state empty

### `exec-op` / `lower-op`

Consumes:

- one op
- current VM-local state

Produces:

- next VM-local state
- zero or more host draw intents
- zero or more resolved geometry-report facts when the host supports that
  surface

The exact realization differs by host:

- Flutter wraps a canvas and emits direct draw calls
- WebGPU lowers to pass / pipeline / buffer commands

The bytecode-order semantics are shared.

### `resolve-clip-rect`

Takes a `:clip/push-rect` rect in current transform space and resolves it into
screen space using the active transform stack.

For v1/v2 portability, clip ancestry must remain translate-only, so resolution
is exact rather than approximate.

### `resolve-region`

When the host participates in `dao.gui.event` or another geometry-reporting
surface, `resolve-region` takes a `:meta/region` op and derives the presented
screen-space interaction region.

The existence, transport, and exact shape of that geometry-report stream are
host-defined unless and until a separate shared contract pins them. Geometry
reporting is therefore not part of v1's canonical signal contract.

### `resolve-resource`

Resolves image / texture / target references into synchronously usable host
resources at presentation time.

If a resource required by the frame is not synchronously realizable, the
terminal rejects the frame with `:reason :unloadable-image`.

### `present-frame!`

Turns lowered host work into the next presented frame.

Successful presentation:

- advances `presented-frame-id` when that surface exists
- makes the frame the new visual and interactive truth
- permits corresponding geometry to be published when the host supports that
  surface

Failed presentation due to invalid frame content:

- emits canonical rejection
- does not advance `presented-frame-id`

Host-runtime failures that are not attributable to invalid frame content remain
host diagnostics, not new canonical rejection reasons.

### `emit-terminal-signal!`

Emits only the canonical signal vocabulary defined in `dao.postgraphics.md`:

- VM Reset
- Frame Rejection
- Protocol Error
- Frame Skipped

Host-specific diagnostics may exist alongside these, but do not extend their
shape or reason vocabulary.

Hosts that do not participate in `dao.gui.event` or another signal-consuming
surface MAY leave some signals unobservable to producers, but they must still
preserve the underlying accounting semantics those signals name.

## Coordinate Resolution

The shared coordinate-resolution contract is:

- producers author 2D coordinates in Cartesian y-up space
- terminals resolve those coordinates into host screen space
- rect-bearing ops use `[x y width height]`
- the terminal's final viewport mapping is host-specific, but not
  producer-visible as a semantic difference

This means:

- a y-down host flips y during final screen-space realization
- a host with native depth attachments may realize v2 depth in hardware
- a host without native depth participation may realize v2 depth in software

Those are realization differences, not language differences.

## Presentation Semantics

An accepted frame:

- allocates exactly one new presented-frame ID when that surface exists
- becomes the current visual truth
- becomes the current interactive truth

A rejected frame:

- does not allocate a new presented-frame ID when that surface exists
- does not publish geometry for that submission
- leaves the previous accepted frame as the interactive truth

If a host observes a late runtime-guard failure after some drawing work has
already begun, the terminal still treats the frame as rejected and not
presented for accounting purposes. A host may transiently display partial
content depending on host paint / submission semantics, but that content does
not become the new interactive truth.

## Frame ID Rules

These rules apply to every terminal. Rules below distinguish between the
unconditional accounting contract (which every terminal MUST preserve) and
the producer-observable signal surface (which is conditional on
`dao.gui.event` participation or another signal-consuming surface).

Unconditional accounting:

- every terminal preserves the "accepted vs rejected vs skipped" distinction
  for each submission, even when no producer-observable signal is emitted
- after a terminal reset there is no surviving presented-frame generation
- if the terminal rejects an ingested frame, the rejection is canonical (uses
  a constrained `:reason` from the v1 vocabulary) regardless of whether it is
  emitted as a signal

Conditional on `dao.gui.event` participation or another signal-consuming
surface:

- a terminal that exposes presented-frame identity MUST allocate exactly one
  new presented-frame ID per accepted frame; producer-side asset-status
  events and other auxiliary signals MUST NOT advance that ID
- a terminal that exposes a signal stream MUST emit a Frame Rejection Signal
  for every rejected frame, naming the rejected `submission-id` and the
  constrained `:reason`
- a terminal that exposes a signal stream MUST emit a Frame Skipped Signal
  when a submission is accepted into the ingress sequence but dropped before
  presentation (for example due to coalescing or backpressure)
- a terminal that exposes a signal stream MUST emit an explicit VM Reset
  Signal when the generation namespace restarts

## Unsupported Surface Carve-Out

A host MAY reject a versioned op with `:reason :unsupported-op` when that host
does not implement part of the language surface, provided that:

- the gap is documented explicitly in the host-specific design
- all other accepted parts of the surface continue to obey the shared terminal
  contract
- the rejection remains canonical rather than host-specific

This carve-out exists so terminals can document partial v4 support, such as a
host that implements v4 lighting and meshes before render targets.

## Extraction Boundary

The shared abstraction boundary is:

- **terminal interpreter**
  - validation
  - stack semantics
  - canonical signals
  - frame accounting
  - coordinate / clip / region resolution
  - versioned language semantics
- **host backend**
  - canvas calls, GPU passes, or equivalent draw realization
  - text engine
  - image / texture upload path
  - shader / pipeline details
  - render-target allocation strategy

This lets hosts vary their realization without forking the bytecode contract.

## Concrete Hosts

Concrete designs built on this contract:

- `dao.postgraphics.flutter.md`
- `dao.postgraphics.webgpu.md`
