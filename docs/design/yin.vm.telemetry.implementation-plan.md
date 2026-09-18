# yin.vm.telemetry — the v2 implementation plan

Status: implementation plan for the shared CLJC telemetry layer that publishes
live VM state as datoms onto an explicit `dao.stream` sink. Subordinate to
[`vm-telemetry-design.md`](./vm-telemetry-design.md) (the canonical schema and
boundaries) and [`telemetry-ui-design.md`](./telemetry-ui-design.md) (the
viewer, which is *not* built here — see *Boundary*).

The v1 implementation and its servers were deleted by
[`yin.vm.v1-retirement.implementation-plan.md`](./yin.vm.v1-retirement.implementation-plan.md)
(D2) and `dao.stream.v1-retirement.implementation-plan.md` (U1). This plan is
the "own plan" `vm-telemetry-design.md:4-5` owes v2.

## The problem and context

`vm-telemetry-design.md` specifies a shared-first, opt-in telemetry layer: every
CLJC VM publishes full state snapshots at runtime boundaries so downstream JIT
and GC interpreters consume one canonical datom stream. The design's hook
points, schema, and test plan are already written there; what does not exist is
the emit path.

What the tree already holds is the *negative* of that: `yin.vm.telemetry` is a
stub (`src/cljc/yin/vm/telemetry.cljc`) whose `enabled?` is always false,
`emit-snapshot` is identity on state, and `reject-telemetry-opt!` throws when a
`:telemetry` option is supplied. Every call site named in the design already
exists and is already a no-op. So this plan is not "add the hook points" — it is
"replace the stub body with the real emit path, and stop rejecting the option",
plus the tests the design prescribes.

### The design's VM list is stale

`vm-telemetry-design.md:9-10` says v1 covered `ast-walker`, `semantic`, `stack`,
`register`, and `wasm`. `yin.vm.v2-consumers.implementation-plan.md` deleted the
last three (and `space`) as experimental models with no v2 twin, and `wasm` was
deleted earlier in `b8a6fce`. Only two CLJC VMs remain and are in scope:
`yin.vm.ast-walker` and `yin.vm.semantic`. The plan corrects the design's VM
list rather than resurrecting deleted models.

## What is verified and what is judged

Everything below is a verified fact from reading the tree on 2026-09-18 unless
marked **[J]** (judgment call). Line numbers are as of the read.

### The emit hook points already exist

`telemetry/emit-snapshot` is called from exactly the boundaries the design lists,
all currently no-ops:

| boundary | phase | site |
|---|---|---|
| VM construction | `:init` | `ast_walker.cljc:850`, `semantic.cljc:849` |
| after a successful step | `:step` | `ast_walker.cljc:781`, `semantic.cljc:785` |
| after a handled effect | `:effect` | `engine.cljc:499` (2-arity, `{:effect-type …}`) |
| after park | `:park` | `engine.cljc:399` (2-arity, `{:parked-id …}`) |
| after resume | `:resume` | `engine.cljc:408` (2-arity, `{:parked-id …}`) |
| run loop leaves blocked | `:blocked` | `engine.cljc:356` |
| run loop leaves halted | `:halt` | `engine.cljc:360` |
| bridge dispatch | `:bridge` | `ffi.cljc:210` (2-arity, `{:bridge-op … :arg-shape …}`) |

Two additional `:bridge` sites sit in the walker's own FFI request path, not in
`ffi/bridge-step`: `ast_walker.cljc:153` and `:172` (the `:dao.stream/ok` and
`:dao.stream/full` arms of a `put-request!`), and `ast_walker.cljc:276`. They
are already wired and stay; they just become live once `emit-snapshot` is.

Both `emit-snapshot` arities are used and must survive: 1-arity (`:init`,
`:step`, `:blocked`, `:halt`) and 2-arity (`:effect`, `:park`, `:resume`,
`:bridge`).

### The stub is the whole current implementation

`telemetry.cljc` today holds five publics:

- `reject-telemetry-opt!` — throws on a non-nil `:telemetry` option. Called once,
  from `yin.vm/empty-state` (`vm.cljc:1616`).
- `enabled?` — always `false`.
- `install` — records `:vm-model`, `:telemetry-step`, `:telemetry-t`; rejects a
  supplied `:telemetry`. Called from both `create-vm`s after the state map is
  built.
- `next-telemetry-state` — identity.
- `emit-snapshot` — identity (both arities).

`empty-state` (`vm.cljc:1590-1701`) already carries the two counters
(`:telemetry-step 0`, `:telemetry-t 0`) and `:vm-model`, but hardcodes
`:telemetry nil` and rejects the option. `create-vm` in both VMs already passes
`:telemetry (:telemetry opts)` and `:vm-model :ast-walker`/`:semantic` through
to `empty-state`, then calls `telemetry/install`.

### The summarization primitive already exists

`dao.data/tag` (`dao/data.cljc:14-33`) classifies a value as one of
`:nil :boolean :number :string :keyword :symbol :fn :stream :vector :set :map
:sequence :opaque`, checking `stream/descriptor?` before `map?`/`sequential?` so
a handle is never mis-tagged as a collection. `dao.data/summarize`
(`dao/data.cljc:109-128`) turns any value into a `{:depth :items :chars}`-bounded
tree of `{:dao.data/type … :dao.data/value …}` maps; no function, stream handle,
or host object survives. This is exactly the "raw host objects are never pushed
directly" invariant the design requires (`vm-telemetry-design.md:36-38`), and it
is already host-uniform CLJC. The plan reuses it rather than writing a parallel
summarizer.

### The datom shape is fixed

A datom is `[e a v t m]` (`dao.datom.cljc:41-57`): `e` and `t` non-negative
integers, `a` a namespaced keyword, `m` an integer metadata-entity reference
(`:db/assert` = 1 by default). `e` is reserved `0`–`15`
(`dao.datom/first-user-id` = 16). `dao.stream/append!` is the writer surface
(`dao/stream.cljc:174-177`). `dao.stream.memory-log` and
`dao.stream.ringbuffer` are the in-test transports;
`test/yin/vm/test_utils.cljc:27-48` already exposes `make-stream`/`new-stream`
over the ring buffer.

## Decisions

### D1 — reuse `dao.data/summarize`; `:vm.summary/*` is a thin datom projection [J]

`vm-telemetry-design.md:36-38` names two requirements the design sketches two
ways: "functions and opaque host values become tagged summaries such as
`:vm.summary/host-fn` or `:vm.summary/opaque`", while `telemetry-ui-design.md`
sketches a `:vm.summary/type`-keyed recursive renderer. `dao.data/summarize`
already produces bounded plain data with exactly the recursive shape the viewer
renders, under `:dao.data/*` keys.

**Disposition:** the four `summarize-*` helpers are thin wrappers over
`dao.data/summarize` that (a) fix bounds, and (b) apply the two bespoke rules
the design requires that `dao.data` does not know about — store summaries that
preserve stream IDs and cursor positions, and continuation summaries that
preserve parked-continuation identities. Their output is emitted as datoms under
a `:vm.summary/*` vocabulary that mirrors `:dao.data/*` one-to-one:
`:vm.summary/type` carries the tag (with `:fn` emitted as `:vm.summary/host-fn`
and `:opaque` as `:vm.summary/opaque`, matching the design's naming),
`:vm.summary/value` a scalar, `:vm.summary/count`, `:vm.summary/item` (many
refs), `:vm.summary/key` and `:vm.summary/value-ref` for map entries. There is
no re-implementation of bounded traversal; only a projection of `dao.data`'s
result onto the `:vm.summary/*` attr names the design commits to.

The alternative — emitting `dao.data`'s `:dao.data/*` maps verbatim as datom
values — would work and is less code, but it makes the stream contract depend on
`dao.data`'s internal key vocabulary, which the design deliberately names its
own (`:vm.summary/*`). The projection is the stable contract.

### D2 — `empty-state` accepts `:telemetry`; `install` validates it [J]

`empty-state` is the single choke point both `create-vm`s go through, and it is
where the two counters and `:vm-model` already live. The plan moves
`:telemetry` handling there:

- Delete the `reject-telemetry-opt!` call (`vm.cljc:1616`) and the hardcoded
  `:telemetry nil` (`vm.cljc:1698`); store `:telemetry (:telemetry opts)`.
- `telemetry/install` — now called *after* `empty-state` has stored the config —
  validates the shape: when `:telemetry` is present, `:stream` must be present
  and `stream/writer?`; a missing stream is a construction error, not a silent
  no-op (the stub's own docstring names silent acceptance as its one way to
  mislead). `:vm-id`, when supplied, is kept; when absent, `install` mints a
  stable per-instance id via `(random-uuid)` (CLJC-portable on clj/cljs/cljd)
  and records it under `:vm-id` on the state. `:telemetry-step` and
  `:telemetry-t` keep their `empty-state` seeds.
- `enabled?` becomes "does the state carry a telemetry stream":
  `(boolean (get-in state [:telemetry :stream]))`. This is the predicate every
  existing `emit-snapshot` short-circuits on; the stub's `if-not (enabled? …)`
  shape is kept, only the predicate becomes real.

Consequence: a VM built without `:telemetry` is byte-for-byte behaviorally
identical to today — `enabled?` is false, `emit-snapshot` is identity, nothing
appends. The opt-in invariant (`vm-telemetry-design.md:98`) holds because
`empty-state` stores `:telemetry nil` by default and every emit site already
guards on `enabled?`.

### D3 — full snapshots; one `t` per snapshot; a third counter for entity ids [J]

The design commits to full snapshots, not deltas (`vm-telemetry-design.md:39-50`).
Each snapshot is therefore a fresh, self-contained set of entities, and the only
continuity a downstream analyzer needs is the monotonic `t` and `:vm/step`.

- `:vm/step` is the snapshot ordinal, read from `:telemetry-step`.
- `t` is `:telemetry-t`, and **one snapshot = one `t`**: every datom in a
  snapshot shares the same `t`, and `next-telemetry-state` increments it once
  per emitted snapshot.
- Entity ids are minted per snapshot from a dedicated `:telemetry-eid` counter
  seeded at `datom/first-user-id` (16) and advanced by the number of entities
  the snapshot mints (root + component/summary entities, in emission order).
  This is a third counter beyond the two the design names
  (`vm-telemetry-design.md:18-21`); it is necessary because `e` must stay in
  user space (≥ 16) and must not collide with reserved ids, and neither
  `:telemetry-step` (an ordinal, not a count of entities) nor `:telemetry-t` (a
  transaction id in a different slot) can double as it without either
  overcounting or colliding with reserved `e` space on the first snapshot.

The snapshot-datoms shape, from the design (`vm-telemetry-design.md:23-35`):

- Root entity: `:vm/type :vm/snapshot`, `:vm/vm-id`, `:vm/model`, `:vm/step`,
  `:vm/phase`, `:vm/value` (the summarized current value), `:vm/blocked?`,
  `:vm/halted?`, and refs `:vm/control`, `:vm/environment`, `:vm/store`,
  `:vm/continuation`.
- Component entities: each a child entity of the root carrying its
  `:vm.summary/*` projection (D1). A component that is absent (e.g. no
  continuation) is omitted, not emitted as a nil ref.

`snapshot-datoms` is a pure function: given the CESK projection (via
`IVMState`, the design's stated source of truth at `vm-telemetry-design.md:72`)
and the phase/opts, it returns the ordered datom vector. `emit-snapshot` then
appends each datom with `stream/append!` and folds `next-telemetry-state` over
the result. Reading `append!` outcomes is the one thing the stub's docstring
defers (`telemetry.cljc:21-24`); v1 reads `:dao.stream/ok` and treats `full` as
a drop-with-no-retry (telemetry is lossy by design — an observability side
channel must not park or fail the VM), and terminal outcomes are ignored rather
than raised.

### D4 — phase opts become extra root/component facts, not new schema [J]

The 2-arity `opts` (`{:effect-type …}`, `{:parked-id …}`, `{:bridge-op …
:arg-shape …}`) are boundary-specific context the design's schema does not
carve a slot for. Rather than grow the `:vm/*` schema per boundary, each opt is
emitted as one extra fact on the root (e.g. `:vm/effect-type`, `:vm/bridge-op`)
or, where it describes a component (a parked continuation id), folded into the
`summarize-continuation` output. The phase keyword itself is always the primary
discriminator; opts are provenance, not schema. This keeps the canonical schema
closed while preserving the information the design's "preserve parked
continuation identities" requirement (`vm-telemetry-design.md:47`) needs.

## Phases

### Phase 0 — pre-checks, no edits

1. Re-run the hook-point census above against the current tree and confirm every
   site still calls `telemetry/emit-snapshot` with the phase listed, and that no
   new `emit-snapshot`/`install`/`reject-telemetry-opt!` caller has appeared.
   ```
   grep -rn "telemetry/emit-snapshot\|telemetry/install\|reject-telemetry-opt!\|next-telemetry-state" src test
   ```
2. Confirm `dao.data/summarize` and `dao.data/tag` behave as documented on a
   stream handle and a host function (the two "raw object" cases the design
   bans) — i.e. `(tag handle) => :stream` and `(tag f) => :fn`, and
   `summarize` of each returns plain data with no handle/function inside.
3. Confirm `dao.stream.memory-log` and `dao.stream.ringbuffer` both implement
   `IDaoStreamWriter` (`stream/writer?`), so either can be the test sink.

### Phase 1 — the emit path

Rewrite `src/cljc/yin/vm/telemetry.cljc` and edit `src/cljc/yin/vm.cljc`
`empty-state`, per D1–D4:

- `telemetry.cljc`: real `enabled?`, `install` (validate + mint `:vm-id`),
  `next-telemetry-state` (increment `:telemetry-step`, `:telemetry-t`, advance
  `:telemetry-eid`), `emit-snapshot` (both arities; append datoms when enabled,
  identity otherwise), `snapshot-datoms`, and the four `summarize-*` helpers.
  `reject-telemetry-opt!` is deleted; its one caller (`empty-state`) is edited
  in the same change.
- `vm.cljc` `empty-state`: drop the `reject-telemetry-opt!` call and the
  `:telemetry nil` literal; store `:telemetry (:telemetry opts)`; add
  `:telemetry-eid` seeded at `datom/first-user-id`.

No edit to the two VMs or to `engine`/`ffi`: their hook points, `:telemetry`
pass-through, `:vm-model`, and `install`/`emit-snapshot` calls are already
correct and only become live.

**Criteria:**

- `clj -M:test` passes with the existing suites; a VM built without
  `:telemetry` still emits nothing (no behavior change).
- A VM built with `:telemetry {:stream s}` appends datoms to `s` at `:init`,
  `:step`, and `:halt` for a tiny program; datoms are all
  `datom/local-datom?`-shaped; `t` is monotonic and constant within a snapshot.

### Phase 2 — tests, written before implementation lands

Add the tests the design prescribes (`vm-telemetry-design.md:74-93`), reduced to
the two live VMs:

- `test/yin/vm/telemetry_test.cljc` (recreated — the v1 file was deleted): the
  disabled/no-op, enabled-writes-datoms, root-facts-plus-component-refs, and
  monotonic-counters tests, each parameterized over `:ast-walker` and
  `:semantic` (not the four/five models the design lists).
- `test/yin/vm/engine_test.cljc` additions: `:effect`, `:park`, `:resume`
  phases, and single terminal `:blocked`/`:halt` emission from `run-loop`.
- Per-VM smoke tests: one `deftest` each for `ast_walker` and `semantic`
  asserting `:init`, `:step`, `:halt` appear for a tiny program.
- Bridge test: the `dao.stream.apply` path emits `:bridge` and preserves
  resumed state.
- Serialization tests: store summaries never embed a raw stream handle or host
  function; cursor/stream summaries preserve the IDs and positions an analyzer
  needs (D1's two bespoke rules).

Run across hosts: `clj -M:test` (JVM), the shadow `:test` node build (cljs), and
`clojure -M:cljd test` (cljd), matching the repo's standard green gates.

**Criteria:** all new tests pass on all three hosts; the design's "disabled VMs
emit nothing by default" test passes with no `:telemetry` key at all.

### Phase 3 — prose that names the change

- `docs/design/vm-telemetry-design.md`: correct the VM list (`:4` and the
  summary) to `ast-walker` and `semantic` only, and note this plan supersedes
  its "owed to its own plan" status note.
- `docs/design/telemetry-ui-design.md`: its status note already records the
  viewer file is deleted; add that the emit path is now real and the viewer, if
  rebuilt, consumes the `:vm.summary/*` projection named in D1. No viewer code is
  written here.
- `src/cljc/yin/vm/telemetry.cljc` docstring: replace the stub's "deferred"
  narrative with the real contract.

## Completion criteria

- `telemetry.cljc` implements `enabled?`, `install`, `next-telemetry-state`,
  `emit-snapshot` (both arities), `snapshot-datoms`, and `summarize-control`/
  `summarize-environment`/`summarize-store`/`summarize-continuation`; the stub
  and `reject-telemetry-opt!` are gone.
- `empty-state` stores `:telemetry` and seeds `:telemetry-eid`; `:telemetry`
  absent ⇒ behaviorally unchanged from today.
- Both VMs, `engine`, and `ffi` are unchanged except where a shared helper
  signature forced it (none is expected).
- Build green on clj, cljs (`:test`), cljd; the new telemetry/engine/serialization
  tests pass on all three hosts.
- No datom pushed carries a function, stream handle, or host object; every
  datom is `datom/local-datom?`-shaped with `e ≥ 16`, namespaced-keyword `a`,
  and a monotonic `t`.

## Boundary — what is owed elsewhere

- **The viewer is a separate plan.** `telemetry-ui-design.md` specifies a
  Reagent app, a Node server (`yin.vm.telemetry_server.node`), and WebSocket
  channels; all were deleted and are not rebuilt here. This plan only makes the
  datom stream real on the VM side, which the viewer would consume.
- **GC reachability is out of scope.** The design caps v1 at "allocation and
  roots only" (`vm-telemetry-design.md:47-51`); the snapshots preserve enough
  structure for a downstream analyzer to infer membership and roots, but the VM
  computes no full reachability edges.
- **Dart is out of scope.** `vm-telemetry-design.md:97` scopes this to CLJC VMs.
- **Compression / delta mode** is explicitly deferred behind the same
  `:telemetry` surface (`vm-telemetry-design.md:100-101`).

## Revision history

- **2026-09-18, architect r1.** First draft. Verified the hook-point census and
  the stub state against the tree; corrected the design's stale five-model list
  to the two live VMs (`ast-walker`, `semantic`); reused `dao.data/summarize`
  rather than reimplementing a summarizer; made four decisions (D1–D4) covering
  the `:vm.summary/*` projection, `empty-state`/`install` validation, the
  snapshot/entity-id/counter scheme, and the phase-opts treatment.
