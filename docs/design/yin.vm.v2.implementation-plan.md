# Yin VM on DaoStream v2 — the ast-walker slice

Status: implementation plan, subordinate to `dao.stream.md` (the contract) and
`datom.world.md` (the axioms and invariants). Sibling of
`dao.stream.v2.implementation-plan.md`, which owns the transport. Prerequisite
of `yin.repl.v2.implementation-plan.md`, its first consumer.

Revised after a five-model review of 2026-09-02
(`collab/review-v2-plans-r2.*.stdout.log`), which found the first draft's
central claim false: it asserted a closed dependency closure and "six v1
idioms," having traced namespace *names* without checking whether the named code
uses features v2 removed. It does, extensively. The census below replaces that
claim and was produced by direct inspection.

**Nothing existing is modified.** `yin.vm`, everything under `src/cljc/yin/vm/`,
`dao.datom`, `dao.runtime`, `dao.stream.apply`, `yin.module` and their tests are
untouched and keep their fifteen consumers.

## Scope

**One evaluator: `ast-walker`.** The reason is the dependency closure:
`semantic.cljc:3-4` requires `dao.space.query` and `dao.space.transact` and
invokes them at 640, 694 and 1118; `ast_walker.cljc:2-8` requires neither, and
nor do `vm.cljc`, `engine.cljc`, `ffi.cljc` or `telemetry.cljc`. Building
`semantic` would redesign `dao.space` (1,885 lines) as a side effect of a VM
port. `register`, `stack` and **`space`** follow later — `yin.vm.space` is a fifth
evaluator (`space.cljc:879`, run by `parity_test.cljc` beside the other four),
not a subsystem like `macro` or `wasm` — so four remain, not three. `semantic`
brings the `dao.space` question with it.

**The default evaluator changes.** v1's REPL defaults to `:semantic`
(`repl.cljc:183`); v2 defaults to `:ast-walker`. That is a change, not a
preservation.

**A user-visible consequence that must be stated.** `yang.clojure` emits
`:yin/macro-expand` for every macro call site (`clojure.cljc:322-330`) and
treats `defmacro` as a bootstrap macro (79). `ast_walker.cljc` has **no**
`macro-expand` branch and throws "Unknown AST node type" (459-460);
`yin.vm.macro` is required only by `semantic` (`semantic.cljc:11`). So the v2
REPL evaluates `def`, `defn`, `fn`, `if`, `let`, `do`, `quote`, arithmetic,
streams, FFI and datom literals — **and throws on any `defmacro` or macro
call**.

So there are **five** user-visible changes, not one, and both plans state all
five: the evaluator set shrinks and its default changes; user-defined macros
stop evaluating; `stream/take!` is removed; programs relying on park-on-full
backpressure behave differently **under a ring-buffer composition**, since that
transport never returns `full` — the VM itself still parks when one does; and
telemetry is absent (see *The census* and *Telemetry is a stub*).

## The closure

Corrected. The first draft omitted the ring buffer and wrongly proposed reusing
`yin.module`.

| v2 namespace | ported from | lines |
|---|---|---|
| `dao.stream.v2.apply` | `dao.stream.apply` | 231 |
| `dao.runtime.v2` | `dao.runtime` | 258 |
| `yin.vm.v2.module` | `yin.module` | 114 |
| `yin.vm.v2` | `yin.vm` | 554 |
| `yin.vm.v2.telemetry` *(stub)* | `yin/vm/telemetry` | ~40 of 282 |
| `yin.vm.v2.runtime-adapter` | `yin/vm/runtime_adapter` | 47 |
| `yin.vm.v2.stream-driver` | `yin/vm/stream_driver` | 59 |
| `yin.vm.v2.engine` | `yin/vm/engine` | 671 |
| `yin.vm.v2.ffi` | `yin/vm/ffi` | 154 |
| `yin.vm.v2.ast-walker` | `yin/vm/ast_walker` | 795 |

Plus **one** hard dependency from the sibling plan: **`dao.stream.v2`**, the
protocols. Not the ring buffer — see *The host supplies streams*.

**`dao.datom` is reused, not forked.** It has no `:require` at all and no
`dao.stream` reference. The first draft forked it "to keep the v2 tree free of
v1 requires," which is self-refuting: a namespace with zero requires imports
nothing. A byte-identical copy would only add drift risk.

**`yin.module` is *not* reused.** The first draft called it free of "v1 coupling
of any kind." It is free of *stream* coupling, but `module.cljc:20-21` holds
`(defonce ^:private module-registry (atom {}))` and an `effect-registry`, read
implicitly by `resolve-module` and by effect dispatch at `engine.cljc:43-47`.
That is the hidden global state `datom.world.md` and the DaoStream contract's
*Invariants* forbid.
`yin.vm.v2.module` replaces the two atoms with a registry value carried in VM
state and supplied by composition, which changes `resolve-var` and effect
dispatch. **The registry value carries effect handlers too**: `module.cljc:97-114`
registers a `:module/require` handler at load whose clj branch calls
`clojure.core/require` from inside effect dispatch — a host call in the middle
of interpretation. In v2, `:module/require` resolves against the registry value
only, and the host `require` does not survive.

**The `stream` module has no v2 registrar.** `dao.stream.ringbuffer` requires
`yin.module` (11-12) and registers a `'stream` module — `make`, `put!`,
`cursor`, `next!`, `take!`, `close!` — at 457-464, forced on construction at
390. That load-time side effect is how Yin source reaches `(stream/…)` at all,
via `engine.cljc:43-47`. The v2 ring buffer performs no such registration and
must not: load-time registration is the retired registry pattern, and under
*The host supplies streams* the VM does not require a ring buffer at all, so
nothing would trigger it. **Registering the v2 stream module is a composition
step**, performed explicitly when a VM is constructed, and `yin.vm.v2.module`
owns its definition — which is also where the effect constructors now live,
since they belong to the VM's vocabulary rather than to any transport. A
composition that wires a VM supplies `:make-stream` and registers the module
together, or Yin source reaches no streams.

## The census

Verified by direct inspection across the ten ported namespaces. This is the
work, and it is larger than a rename.

| idiom | sites | where |
|---|---|---|
| `:position` cursor arithmetic | **34** in scope (36 less telemetry's two) | 8 of 10 files |
| `:woke` from `append!`/`close!` | 11 | runtime, engine, ffi |
| `IDaoStreamWaitable` + waiter registration | 6 | runtime, engine, **ast_walker** |
| `ds/drain-one!` destructive read | 5 | runtime, engine |
| `ds/closed?` | 1 | runtime |

Each maps to a decision, not a substitution.

**Cursors are opaque values, everywhere.** The 34 in-scope sites are the dominant cost.
They include fabricated defaults (`stream_driver.cljc:25`, `apply.cljc:170,184`),
reconstruction from the VM store (`engine.cljc:115-117,191,261`,
`runtime_adapter.cljc:11,45`, `ffi.cljc:83`), and the VM-internal cursor
representation `{:stream-id id :position n}` (`engine.cljc:168`,
`vm.cljc:542-545`; `telemetry.cljc:133,171` are in the unported summarisation
code and bind the later emit phase instead). All become
`{:stream-id id :cursor <opaque>}`; `:vm.summary/position` cannot exist; there
is no `seek`.

**Outcomes are maps.** v1 `next` returns bare `:blocked`, `:end`,
`:daostream/gap` or `{:ok v :cursor c}`, and callers dispatch on `map?` and
keyword equality. Every such site is rewritten and made total over the closed
set, including `cursor-mismatch`, `invalid-cursor` and `transport-error`.
`append!` gains a `closed` outcome where v1 threw (`dao.stream.cljc:51-52`),
adding a branch at every append site.

**Waiters are deleted, not replaced.** `check-wait-set` (`engine.cljc:240-247`)
documents itself as "the universal fallback for transports that do not support
local registration." Under v2 no transport is waitable, every `satisfies?` guard
goes false, and the existing polling wait-set is the path taken. This is a
deletion of an optimisation branch. Cadence comes from the driver above the VM,
per the DaoStream contract's *The IO Model*.

**`:stream/take` is removed from the v2 `stream` module.** Destructive read is
gone deliberately — "one reader's progress every other reader's data loss." v1's
`take!` takes a stream ref, not a cursor, so a v2 `take!` would need an implicit
per-stream reader position held somewhere — which is precisely the
reader-position-in-the-medium the contract retired. Programs use `cursor` and
`next!` instead. This is a removal, not a reinterpretation, and it is the fourth
user-visible change. An above-the-stream queue interpreter with explicit consume
accounting is the deferred way back, named here so it is not reinvented.

**Every fabricated cursor is minted `:dao.stream/oldest` the moment the VM first
holds its handle** — whether as a store entry, as the `:in-stream` field
(`stream_driver.cljc:25`, `ast_walker.cljc:785`), or at a bridge attach
(`ffi.cljc:31-32,96`, which can run on an already-built VM). `dao.stream.v2.apply`
has **no cursorless arities**, so `apply.cljc:170,184` lose their defaults
rather than gaining a mint site. The fabricated-cursor sites are all semantically "absolute position
zero", which equals `:dao.stream/oldest` on a fresh stream but diverges from
`:dao.stream/newest` the moment a stream has history before the cursor exists —
and tests pre-fill streams before constructing a VM, so this matters for parity.
Minting is a stream operation, so `:stream/cursor` gains `closed` and
`transport-error` outcomes to handle at that site; `handle-cursor` touches no
stream today.

**`closed?` becomes outcome-driven.** `runtime.cljc:115` asks; v2 operates and
reads the answer.

**Cursors have no recognition predicate — and this is deferred with telemetry.**
`telemetry.cljc:131-133,168-172` classifies a cursor structurally with
`cursor-map?`; v2 cursors are opaque and the contract offers no predicate. That
problem, and the surface-classification changes beside it, belong to the real
emit path, which this slice does not build (see *Telemetry is a stub*).

### The host supplies streams

v1 hardcodes its transport. `handle-make` builds
`{:dao.stream/type :ringbuffer, :mode :create, :capacity capacity}` and calls
`ds/open!` (`engine.cljc:131-141`), and `open-local-stream` does the same for
the FFI pair (`vm.cljc:134-139`, with a direct
`ringbuffer/make-ring-buffer-stream` on cljd). Those two sites are the *only*
reason `vm.cljc:7-9` and `engine.cljc:7` require `dao.stream.ringbuffer` at all.

**In v2 the host supplies a stream constructor, exactly as it supplies `+`.**
`vm/empty-state` already takes `:primitives` from its options
(`vm.cljc:550`), `create-vm` threads them through (`ast_walker.cljc:778`), and
the REPL merges its own `print`/`println` into that map. A stream constructor is
the same kind of thing: a capability the composition hands the interpreter, not
one the interpreter reaches for.

`create-vm` therefore takes **`:make-stream`**, a function of a capacity
returning a create outcome. `handle-make` calls it. Nothing else changes,
because `handle-make` is the only handler that needs a transport identity —
`append!` (156), `next` (192) and `close!` (237) are protocol calls on a handle
already held in the store.

Three consequences:

- **`yin.vm.v2` and `yin.vm.v2.engine` require no ring buffer**, so the closure
  is the protocols and nothing more. The dangling `dao.stream.ringbuffer`
  dependency is removed rather than substituted.
- **The VM is transport-agnostic.** A composition may hand it a ring buffer, a
  file, or anything implementing the v2 protocols. This is the contract's own
  instruction for an interpreter that needs what a transport provides: it
  "receives the operation as an ordinary argument from the composition that
  wired it… rather than on a transport it detected" (DaoStream contract,
  *Surfaces*).
- **`:make-stream` has no default.** `:primitives` has one; this must not,
  because a default would smuggle the hardcoded transport and its require back
  in. Absent a supplied constructor, `:stream/make` is unsupported and says so —
  a coherent VM that still operates streams the composition handed it, exactly
  as it runs today with `:in-stream nil`. Silent fallback to a private transport
  is the failure mode this rule exists to prevent.

**The FFI pair, precisely.** `empty-state` builds `call-in` and `call-out`
unconditionally (`vm.cljc:539-540`), and both are used without a guard —
`park-and-call` appends to `call-in` (`ast_walker.cljc:121-152`) and
`bridge-step` calls `next` on it (`ffi.cljc:95-99`). So "unsupported and says
so" needs a rule, or a `:dao.stream.apply/call` is a protocol call on nil:

- The pair comes from an explicitly supplied **`:call-in`/`:call-out`** first —
  matching v1's `(or (:call-in opts) …)` precedence at `vm.cljc:539-540`, so a
  composition handing over streams directly is never silently overridden — else
  from **`:make-stream`**; else the store holds **no pair**.
- With no pair, a non-nil `:bridge` is a **construction error**, and a
  `:dao.stream.apply/call` node fails **before `park-continuation`**
  (`ast_walker.cljc:125`). That ordering is required: the park runs nineteen
  lines before the first stream touch, so an error raised later strands a
  continuation in `:parked` and consumes an id-counter.
- **Construction is all-or-nothing.** Creating the pair calls `:make-stream`,
  whose outcomes are `ok`, `invalid-spec`, `not-found`, `transport-error`;
  minting the call-out cursor, the in-cursor (`ast_walker.cljc:786`) and the
  bridge cursor each call `cursor`, which adds `closed` and `transport-error`.
  Any non-`ok` outcome **fails construction** with an error carrying it. A VM
  cannot be half-built.
- **The bridge cursor moves.** `ffi/normalize` (`ffi.cljc:28-36`) fabricates
  `{:position 0}` from a bare handler map and never sees a handle, so under v2
  it has nothing to mint against. Minting moves to `ffi/attach`, where the store
  is visible, or `bridge-from-opts` runs after the pair exists. **`attach` runs
  on a built VM**, so it applies the same rule: with no pair it has nothing to
  mint against and errors exactly as construction does.
- **`call-in-cursor-key` is dropped.** `vm.cljc:542` writes it and nothing in
  `src` reads it — four v1 tests assert only its presence — and the bridge
  cursor already covers reading `call-in`. So the mints are three, not four,
  and the store loses a key that was never observed.

`open-local-stream`'s cljd branch goes with the require.

### The `stream` module is effect constructors, not stream access

Worth stating because it is easy to misread as an FFI concern. `dao.stream.ringbuffer`'s
"Yin VM Module API" section (`ringbuffer.cljc:420-464`) is pure data
constructors — `(defn put! [s v] {:effect :stream/put, :stream s, :val v})` —
which touch no stream. Yin source calls them through the module system, they
return effect maps, and the *engine* interprets those. Programs hold
`{:type :stream-ref, :id id}`, never a handle, so v2's opaque handles and
cursors never reach Yin code.

The FFI (`dao.stream.apply/call`) is a different path, for host functions.

So the module survives the port unchanged apart from `take!`, and only the
engine's handlers move:

| Effect | v1 | v2 |
|---|---|---|
| `:stream/make` | `ds/open!` with a hardcoded `:ringbuffer` descriptor | the host's `:make-stream`, total over `ok`, `invalid-spec`, `not-found`, `transport-error` |
| `:stream/put` | `ds/append!`, parks on `:full` | `append!`, total over `ok`, `full`, `invalid-value`, `closed`, `transport-error`; `full` **parks in the polling wait set**, which `runtime.cljc:114` already retries |
| `:stream/cursor` | fabricates `{:position 0}` | `cursor` with `:dao.stream/oldest`; adds `invalid-anchor`, `closed`, `transport-error` |
| `:stream/next` | bare `:blocked`/`:end`/`:daostream/gap` | outcome map, total over the closed set |
| `:stream/take` | `ds/drain-one!` | **removed** |
| `:stream/close` | `close!`, consumes `:woke` | `close!`, `{ok}` only |

**Nil capacity has no v2 meaning.** The module's zero-arity `make` yields
`{:capacity nil}` (`ringbuffer.cljc:427-429`), which meant unbounded in v1, and
`yang` compiles `(stream/make)` so it is reachable from source. The AST path
defaults to 1024 (`ast_walker.cljc:426`); the module path does not. `handle-make`
applies the same 1024 default to a nil capacity, so both paths agree.

**Totality is the VM's, retention is the composition's.** The engine no longer
names a transport, so it must handle every outcome the contract defines —
including `full`, which a bounded transport a composition supplies will return.
What a *given* composition observes follows from the transport it chose.

### Telemetry is a stub

`emit-snapshot` short-circuits on `(if-not (enabled? state) state …)`, and
`enabled?` is `(boolean (get-in state [:telemetry :stream]))`
(`telemetry.cljc:22-24,273-282`). With no telemetry stream installed, **every
call site in `engine`, `ffi` and `ast_walker` is already a no-op returning state
unchanged** — roughly a dozen of them, all routed through `emit-snapshot`,
`enabled?`, `install` or `type-tag`.

So `yin.vm.v2.telemetry` in this slice is a stub: `enabled?` returns false,
`emit-snapshot` is identity, `install` records the model, and `type-tag`
classifies values. Nothing appends, nothing is created, no capacity is chosen.

**`type-tag` must be real**, because `ffi.cljc:118-121` evaluates
`(mapv telemetry/type-tag request-args)` as an *argument* to `emit-snapshot`,
so it runs before the no-op check. The stub's version returns `:opaque` for any
handle rather than distinguishing surfaces. Both `emit-snapshot` arities must
survive: `ffi.cljc:118` and `ast_walker.cljc:795` use different ones.

**A supplied `:telemetry` opt is an error, not silence.** `create-vm`
(`ast_walker.cljc:779`) and `empty-state` (`vm.cljc:551`) still plumb
`(:telemetry opts)` into state, so a stub that merely records the model would
accept `{:telemetry {:stream s}}` and write nothing to that stream, forever.
The stub therefore **rejects a non-nil `:telemetry` opt** with an error naming
the deferral — matching the REPL plan's rejection of `--telemetry`. Silent
acceptance of a stream that is never written is the one way this stub can
mislead.

**What this defers**, and why it is the best cut available: reading `append!`
outcomes in the emit path; ordering all three surface protocols before the
`map?` branch so a writer-only record is not walked field by field; summarising
a cursor-ref without descent so an opaque cursor cannot re-emit a position
field; the `cursor-map?` recognition problem; one stream and its capacity; and
the telemetry test suite. All of it serves an optional REPL command, and none of
it is needed to prove the VM runs on v2 streams.

The real emit path is a later phase against the same v2 contract, and its
absence changes no evaluation result.

### The retention divergence, and a request to the sibling plan

**v1 VM streams are reject-mode.** `ringbuffer.cljc:21` sets
`default-eviction-policy :reject`; `engine.cljc:137` passes no policy, so
`:stream/make` gets it; and `engine.cljc:22,157` **parks the continuation on
`:full`**. That is real backpressure, and `engine_test.cljc:263,365` tests it.
The sibling plan's v2 ring buffer is evict-oldest with `append!` never returning
`full` (stream v2 plan, *Phase 2 — Ring buffer reference*).

Ported as-is, puts never park, slow readers get gaps instead, and Phase V5's
"same results as v1" is false for any program that relies on backpressure. The
v1 FFI pair is worse: it is *unbounded* (`vm.cljc:139`, `apply.cljc:35-36`), and
v2 has no unbounded mode, so eviction there silently loses host calls.

**v2 accepts evict-oldest, and the divergence is recorded.** An earlier draft
requested a reject-mode ring buffer from the sibling plan. That request is
withdrawn: it would have built a deadlock.

v1 backpressure is not a property of the ring buffer alone — it is reject-mode
**plus destructive take**. Capacity is freed in exactly one way:
`drain-one-state` advances `:head` (`ringbuffer.cljc:161`). `next-outcome`
advances the *cursor* and never `:head` (109-119), and `evict-oldest-state` runs
only under the evict policy. The writer-waiter wake lives inside the drain.
`engine_test.cljc:360-370` demonstrates it by hand-advancing `:head` to unblock
its parked writer.

This plan deletes destructive take. So a v2 reject-mode ring buffer, once full,
would be full forever: no operation frees a slot, and the contract forbids the
one mechanism that could — eviction waiting on a reader
(DaoStream contract, *Retention and Gaps*). A `:stream/put` parked on `full` would never wake. A
silent hang is worse than a reported `gap`.

**Consequence, for the register:** programs relying on park-on-full behave
differently, and no "same results as v1" claim covers them. Backpressure
semantics, if wanted later, come from the deferred queue interpreter or from
flow control above the stream — the same place the sibling plan sends them.

**Two capacity decisions, on the right streams.** The first draft named the
wrong one. `open-local-stream` (`vm.cljc:134-139`) creates the **FFI call-in and
call-out pair**, not the in-stream; the in-stream is caller-supplied
(`ast_walker.cljc:782`, `repl.cljc:160-166`). Both need declared capacities and
both are correctness parameters: an evicted FFI response leaves `park-and-call`
(`ast_walker.cljc:144`) parked forever, and an evicted in-stream batch is a
program the VM never ingested. Each phase that creates a stream states its
capacity and what eviction means there.

**`append!` reports no backpressure on the ring buffer specifically.** That
transport evicts oldest and never returns `full`, so under a ring-buffer
composition loss surfaces as a `gap` at the *reader's* cursor. This is a
property of the supplied transport, not of the VM: every append site is total
over the contract's five outcomes, and `full` parks. The rule binds the deferred
telemetry emit path the same way when it is built, since this slice has no
telemetry append sites.

## `dao.stream.v2.apply` owns the envelope

All reviewers converged: the VM's FFI bridge and the REPL's RPC are the same
problem — request/response over a stream pair — and v1 already had one answer.
`dao.stream.apply` defines the envelope (`apply.cljc:42-63,93-103`) and v1's RPC
client and server are its *consumers* (`client.cljc:17`, `server.cljc:15`),
adding only `{:ok}`/`{:error}` wrapping inside the response value
(`server.cljc:24-38`).

So `dao.stream.v2.apply` is the canonical owner: request, success-response and
error-response constructors and predicates, correlation-id rules, and the
endpoint pair. `dao.stream.v2.rpc.client` and `.server` **require** it and add
only correlation matching, outstanding-request tracking, conservative loss and
rebind. Two namespaces with coordinated-but-distinct keys is the duplication
both plans call a defect.

**They are socket-free, so this plan builds them.** `dao.stream.v2.rpc.client`
and `.server` move into V1 beside the envelope, carrying the REPL plan's
pending-response discipline. The REPL plan keeps only `dao.stream.v2.rpc.ws` —
the `:decode` that filters by attachment and unwraps `:ws/payload` — which is
the only part that needs a transport. This deletes the bidirectional "should
agree" and makes the sequencing honest: V1 before the REPL's R1.

It also becomes a step: `serve-once!` returns its next cursor and state rather
than looping, and retains a computed response until its append succeeds, so a
handler executes exactly once. Handler execution itself is synchronous within
that step and has no preemption budget in this slice; an unbounded host handler
can therefore stall the one driver. Multi-tick handler continuations are a
separate VM capability, not something this RPC state machine pretends to add.

**Consequence for sequencing:** the REPL plan's R1 depends on this phase.

## Prerequisites

From `dao.stream.v2.implementation-plan.md`: **Phase 1 completed** — protocols,
result convention, conformance harness — which is all `yin.vm.v2` itself
requires. **Phase 2, the ring buffer**, is needed by this plan's *tests and
compositions*, which must supply a `:make-stream`, but not by the VM namespaces.
Nothing else: no Phase 3, no Phase 4, no gates.

Neither exists yet (`src/cljc/dao/stream/v2*` is absent), so **that is the first
blocker for this plan and for the REPL's**.

## Phases

**V1 — Envelope, RPC core, and module.** `dao.stream.v2.apply` per above.
**Dependency check**: it takes handles and requires only `dao.stream.v2`, never
a transport — the ring buffers below are its *test fixture*, and a namespace
that constructs its own endpoints is how the ring buffer returns through
`yin.vm.v2.ffi`. Tested over two ring buffers, with the step-shaped `serve-once!`. `dao.stream.v2.rpc.client`
and `.server` on top of it, socket-free, with the pending-response discipline.

**The transition algebra, stated once.** The REPL plan's R1 mirrors this
contract exactly; it is specified here because V1 owns the envelope and both
RPC namespaces consume it.

Client state includes a monotonic, never-reused safe-integer `:next-id`, and
allocation reserves and increments it before append. Encountering an ID
already unsent, outstanding, or completed — or exhausting the cross-host
safe-integer range — is a terminal allocator error and never overwrites a
request. While a request is unsent, `request!` retries that identical encoded
request and accepts no new operation. `request!` is total over `append!`:
`ok` moves the request to outstanding; `full` retains the identical encoded
request and its allocated ID; `closed`, `invalid-value`, and
`transport-error` complete it terminally.

`poll!` is total over `next`. `ok` advances to the exact returned successor
before decoding; `blocked` changes nothing; `gap` advances to the recovery
cursor and reports every outstanding request lost; `end`,
`cursor-mismatch`, `invalid-cursor`, and `transport-error` terminate the
reader binding without changing its cursor and report every outstanding
request lost. In every loss case an unsent request was never accepted and
remains eligible for the identical retry specified above, or for an explicit
rebind decision by the driver.
Malformed and unsolicited responses are consumed once as diagnostics.

**Completion consumption.** `:completed` is an unpublished outbox, not request history. Every terminal transition appends its completion exactly once. During each `repl-step`, the sole state owner snapshots and publishes the current completions, then returns the next client state with `:completed []`; a later step must not republish them. The vector is therefore bounded by work admitted within one step. Never-reuse across previously published completions is guaranteed by the monotonic `:next-id` high-water mark, not by retaining completed IDs indefinitely. Collision checks cover `:unsent`, `:outstanding`, and any currently unpublished completion.

Server state includes `:request-cursor`, `:pending-response`,
`:pending-request-id`, `:pending-successor`, and `:terminal`. `serve-once!`
retries a pending response before reading another request. After a successful
`next` it retains the exact returned successor and runs the handler at most
once. `append!` `ok` advances once to that successor; `full` retains both
response and successor without advancing or re-running; `invalid-value`,
`closed`, and `transport-error` advance once, report the response
undeliverable, and terminate. Request-side `blocked`, `gap`, `end`,
`cursor-mismatch`, `invalid-cursor`, and `transport-error` follow their
corresponding unchanged, recovery-cursor (recording skipped requests), or
terminal transitions.

A malformed request never reaches a handler: with a usable ID it receives a
correlated malformed-request error; without one it produces a local
diagnostic and advances once. Envelope validation — an ID present and
non-nil, the op a keyword, the args a vector — and every request and
response constructor, predicate, correlation-ID rule, and
`:dao.stream.v2.apply/…` key belong exclusively to `dao.stream.v2.apply`.

`dao.stream.v2.apply` owns a transport-neutral lifecycle vocabulary consumed by
the client state machine: `:dao.stream.v2.apply/established` changes no request
or cursor; `/detached` loses every outstanding request and permits rebind;
`/ended`, `/not-found`, and `/transport-error` terminate the binding and lose
every outstanding request, with retry left to the driver; `/diagnostic` retains
the writer, cursor, and requests. Once terminal, later lifecycle values are
consumed as diagnostics and cannot complete a request twice. A transport
adapter such as `dao.stream.v2.rpc.ws` owns translation from its event
vocabulary to these values; neither RPC core namespace names `:ws/…`.

`yin.vm.v2.module` with an explicit registry value, and the v2 `stream` module
definition it will register. Deliverable: a request/response round trip through
both layers, and a module resolution test. No VM. Required tests: completions
publish exactly once, `:completed` is empty in the returned post-publication
state, its maximum size is bounded by one step's work budget, and IDs remain
monotonic after earlier completions have been cleared. Neutral lifecycle tests
cover every value above, repeated post-terminal values, and exactly-once
completion. WebSocket event sequencing and close codes belong to the stream
plan's Phase 4a and the REPL plan's socket-free decoder tests.

**V2 — The scheduler.** `dao.runtime.v2` and `yin.vm.v2.runtime-adapter`. This
is where waiters are deleted, `:woke` removed, `take!` **removed**, `closed?`
retired, and the wait set made cursor-opaque. It is not a leaf and does not
belong in a first phase. Deliverable: park and wake against a v2 ring buffer,
with the polling wait set as the only mechanism.

**V3 — VM kernel.** `yin.vm.v2`, including `:make-stream` as a construction
option and the FFI pair created through it when no explicit
`:call-in`/`:call-out` pair is supplied, with a declared capacity —
the once-only state machine bounds outstanding requests to one per parked call
and already-read responses are harmlessly evictable, so the rule is **capacity
at least maximum-outstanding plus one, and a `gap` at the bridge cursor is
fatal, not resumable** —
the stub `yin.vm.v2.telemetry`, and `yin.vm.v2.stream-driver`. Deliverable:
ingesting a program batch across a `gap`, and a VM that runs with telemetry
disabled on every host.

**V4 — Engine and FFI.** `yin.vm.v2.engine` with its ingress guard preserved
(`engine.cljc:310`) and module-based effect dispatch reading the registry value;
`yin.vm.v2.ffi` with an explicit once-only state machine — retain the unsent
request before parking, retain the computed response and its successor cursor
until the append succeeds, classify every terminal outcome. Deliverable: a host
call round-tripping through a v2 endpoint pair.

**V5 — The evaluator.** `yin.vm.v2.ast-walker`, including its own waiter
deletion at 139. Deliverable: `(-> (create-vm …) (vm/eval ast) (vm/run))` on all
three hosts.

**V6 — Evidence, and the divergence register.** Fresh tests for v2's scope,
**plus parity**. The first draft refused parity because loading v1 recreates the
coupling; that conflates test requires with source requires, and the end
condition governs namespaces under `yin.vm.v2` and `dao.*.v2`, not test
namespaces. The repo already co-loads five VMs in one process
(`parity_test.cljc`, `test_utils.cljc:20-27`).

**Every v2 test supplies `:make-stream`.** v1's suite calls
`(ast-walker/create-vm)` bare; the v2 side needs a test-side constructor closing
over the ring buffer, or the shared corpus cannot run.

Two forms, and the second is not optional: run a shared **macro-free** AST
corpus through v1 and v2 and compare normalized results (process-isolate if
co-loading proves awkward); and **generate the v2 expectations from v1's
recorded values** in `ast_walker_test.cljc` rather than choosing fresh ones,
because a fresh suite "covering the same programs" passes under silent
divergence. Drop the one case requiring `dao.space.transact` (4, 406).

**The divergence register** is a deliverable of this phase: every place v2
deliberately differs from v1, with the reason. It opens with the five
user-visible changes — telemetry's absence among them — then the retention
decision above, the FFI pair's bounded outstanding-call count where v1 was
unbounded, and the v1-only behaviours no v2 test can mirror — `engine_test.cljc:263,365,386,419` assert park-on-full,
close-wakes-writer and take-wakes-reader. Saying which behaviours are
deliberately not mirrored *is* the register.

## Host matrix

clj, cljs (Node) and cljd, every phase; nothing here touches a socket, so the
cost is low. `#?(:clj …)` does **not** exclude code from the cljd build —
`#?(:cljd nil :clj …)` with `:cljd` first is required, `:cljd` in tail position
silently fails. Note `vm.cljc:7-9` already carries all three branches; the bare
`#?(:clj …)` is at `vm.cljc:171`, a `defmacro` where host-eval visibility is
deliberate. Full cljd namespace compilation gates each phase.

## Boundary

**Untouched:** `yin.vm` and everything under `src/cljc/yin/vm/`, `dao.datom`
(reused as-is), `dao.runtime`, `dao.stream.apply`, `yin.module`, and their tests.
The fifteen existing `yin.vm` consumers keep using v1.

**Not in this plan:**

- the real telemetry emit path, deferred to a later phase per *Telemetry is
  a stub*; `semantic`, `register`, `stack`; `macro`, `space`, `wasm`;
  `dao.space` in any form; migration of any existing consumer; an
  above-the-stream queue interpreter to restore destructive-take semantics.
- **Internal state as streams** — the log-structured CESK end-state,
  ready-queue-as-stream, stream fusion, and the store-the-irreducible
  storage invariant explored in
  [`yin.vm.streams-all-the-way-down.md`](./yin.vm.streams-all-the-way-down.md).
  The port keeps scheduler queues as plain data by design (the note's own
  calibration: synchronous-singular consumers pay boundary tax); those
  tiers get their own plans after V6 lands.

## End condition

Complete when, on clj, cljs (Node) and cljd, `yin.vm.v2.ast-walker` evaluates a
macro-free program corpus and exercises its ingress and FFI paths over v2
streams with telemetry disabled, with parity agreeing with v1 except where the divergence
register says otherwise — and with no namespace under `yin.vm.v2` or `dao.*.v2`
requiring v1 `dao.stream`, `dao.runtime`, `dao.stream.apply` or `yin.module`.
