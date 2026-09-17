# yin.vm — the divergence register

Status: deliverable of phase V6 of
[`yin.vm.implementation-plan.md`](./yin.vm.implementation-plan.md),
amended by its phase V7. Subordinate to [`dao.stream.md`](./dao.stream.md)
and [`datom.world.md`](./datom.world.md).

Every place `yin.vm` deliberately differs from `yin.vm`, with the reason.
Saying which v1 behaviours are deliberately not mirrored *is* the register: a
v2 suite that "covers the same programs" proves nothing unless the places it
cannot cover are named.

The sections below record the original V6 baseline except where a *V7* note
amends them: V7 moved program observation out of the VM (see
[Program observation](#program-observation)), obsoleted the `:in-stream`
construction option, and decoupled direct `eval` from queued program input.
Each such note states what changed after the baseline, not a second
baseline.

Scope is the ast-walker slice against v1. The v2 linear semantic VM
(`yin.vm.semantic.md`) is recorded against the v2 ast-walker in
[its own section](#the-semantic-vm-against-the-ast-walker). `register`,
`stack`, `space`, `macro` and `wasm` are not ported, so nothing here speaks
for them.

## The five user-visible changes

1. **The evaluator set shrinks and its default changes.** v2 ships one
   evaluator, `yin.vm.ast-walker`. v1's REPL defaulted to `:semantic`
   until `yin.vm-consumers.implementation-plan.md` (2026-09-10) deleted
   `:semantic`/`:register`/`:stack`/`:space` and migrated v1's default to
   `:ast-walker` too (`repl.cljc:174`); there is now only one evaluator on
   either REPL.
   *Semantic Phase 4:* v2 now ships a second evaluator,
   `yin.vm.semantic`, and `yin.repl.core` defaults to it
   (`yin.vm.semantic.md` §8). v1's REPL was deleted on 2026-09-16
   (`yin.vm.v1-retirement.implementation-plan.md`); there is no v1 REPL.
2. **User-defined macros stop evaluating.** `yang.clojure` emits
   `:yin/macro-expand` for every macro call site. `ast-walker` has no
   `macro-expand` branch — in v1 or in v2 — and `yin.vm.macro` was required
   by all four experimental models (`semantic`, `register`, `stack`,
   `space`), all since deleted alongside it. So `defmacro` and every macro
   call throw "Unknown AST node type". The v2 corpus is macro-free by
   construction.
3. **`stream/take!` is removed.** Destructive read is one reader's progress
   and every other reader's data loss, and v1's `take!` took a stream ref
   rather than a cursor, so a v2 `take!` would need the
   reader-position-in-the-medium the contract retired. Programs use `cursor`
   and `next!`. `yin.vm.module/stream-module` has five bindings, not six;
   `yin.vm.engine` has no `:stream/take` branch and no `handle-take`.
   An above-the-stream queue interpreter with explicit consume accounting is
   the deferred way back.
4. **Park-on-full behaves differently under a ring-buffer composition.** See
   *The retention divergence* below.
5. **Telemetry is absent.** `yin.vm.telemetry` is a stub: `enabled?` is
   false, `emit-snapshot` is identity, `type-tag` is real. A non-nil
   `:telemetry` option is **rejected**, not ignored, because a stub that
   merely recorded the model would accept a stream and then write nothing to
   it forever.

## The retention divergence

**v1 VM streams are reject-mode with destructive take.**
`ringbuffer.cljc:21` sets `:reject`; `engine.cljc:137` passes no policy;
`engine.cljc:22,157` parks the continuation on `:full`. Capacity is freed in
exactly one way — `drain-one-state` advances `:head` — and the writer wake
lives inside that drain.

**v2 accepts evict-oldest.** This plan deletes destructive take, so a v2
reject-mode ring buffer, once full, would be full forever: no operation frees
a slot, and the contract forbids eviction waiting on a reader. A `:stream/put`
parked on `full` would never wake. A silent hang is worse than a reported gap.

Consequences:

- Under a ring-buffer composition, `append!` never returns `full`, so puts
  never park and loss surfaces as a `gap` at the *reader's* cursor instead.
- The VM is still total over `full`, and `full` still parks in the polling
  wait set (`dao.runtime/write-outcome->task`). What a given composition
  observes follows from the transport it chose, not from the VM.
- No "same results as v1" claim covers a program that relies on backpressure.
  Backpressure semantics, if wanted later, come from the deferred queue
  interpreter or from flow control above the stream.

**v1-only behaviours no v2 test can mirror.** `engine_test.cljc:263,365` assert
park-on-full; `:386` asserts close-wakes-writer; `:419` asserts
take-wakes-reader. All three depend on reject-mode plus destructive take plus
transport-local waiters. They are deliberately not mirrored.

## Cursors

| v1 | v2 |
|---|---|
| store cursor entry `{:stream-id id :position n}` | `{:stream-id id :cursor <opaque>}` |
| fabricated `{:position 0}` at four sites | minted `:dao.stream/oldest` the moment the VM first holds the handle |
| `(inc position)` on wake | the exact successor the transport returned |
| `:vm.summary/position` in telemetry | cannot exist |
| `seek` | none |

**Minting is a stream operation, and this is observable.** Every fabricated v1
cursor meant absolute position zero, which equals `:dao.stream/oldest` on a
fresh stream but diverges from `:dao.stream/newest` the moment a stream has
history before the cursor exists. Tests pre-fill streams before constructing a
VM, so v2 chooses `oldest` deliberately and
`yin.vm.ffi-test/history-before-the-cursor-is-not-skipped-test` pins it.

**Three mints, not four.** `call-in-cursor-key` (`vm.cljc:542`) is dropped:
nothing in `src` read it, and the bridge cursor already covers reading
`call-in`. Four v1 tests asserted only its presence. In the V6 baseline the
three mints were the call-out cursor, the ingress cursor, and the bridge
cursor.

*V7:* the VM's mints are the call-out cursor (construction) and the bridge
cursor (`ffi/attach`). The program cursor belongs to observer attachment and
is minted inside `dao.stream.observer/attach`, which requires only
`dao.stream` rather than `vm/mint-oldest`.

**Construction is all-or-nothing.** Creating the FFI pair and minting its
cursors are stream operations with their own outcomes; any non-`ok` outcome
fails construction. A VM cannot be half-built.

## Outcomes

v1 `next` returned bare `:blocked`, `:end`, `:daostream/gap` or
`{:ok v :cursor c}` and callers dispatched on `map?`. Every such site is
rewritten and made total.

| operation | v1 | v2 |
|---|---|---|
| `:stream/make` | `ds/open!` with a hardcoded `:ringbuffer` descriptor | the host's `:make-stream`; `invalid-spec`, `not-found`, `transport-error` fail construction |
| `:stream/put` | `ds/append!`, parks on `:full`, threw on a closed stream | total over five outcomes; `full` parks; `closed`, `invalid-value`, `transport-error` are errors naming the outcome, as v1's throw was |
| `:stream/cursor` | fabricated `{:position 0}`, touched no stream | mints at `:dao.stream/oldest`; `invalid-anchor`, `closed`, `transport-error` are errors |
| `:stream/next` | bare tags | total over seven outcomes; `end` yields nil as v1 did; `gap` yields `:dao.stream/gap` and advances to the recovery cursor; the three terminal outcomes are errors |
| `:stream/take` | `ds/drain-one!` | removed |
| `:stream/close` | `close!`, consumed `:woke` | `close!`, `{ok}` only; wakes nothing |

**The gap keyword changed.** v1's reserved value was `:daostream/gap`; v2's is
`:dao.stream/gap`, the contract's own keyword. v1 could not produce it.

**Nil capacity has no v2 meaning.** v1's module-path zero-arity `make` yielded
`{:capacity nil}`, meaning unbounded. Both the AST path and the module path now
apply `yin.vm/default-stream-capacity` (1024), so they agree.

## Waiters

`IDaoStreamWaitable`, `register-reader-waiter!`, `register-writer-waiter!` and
the `:woke` entries an `append!`/`close!` returned have no v2 counterpart: no
v2 transport is waitable. v1's `check-wait-set` described itself as "the
universal fallback"; in v2 the polling wait set is the only mechanism, and
cadence comes from the driver above the VM.

This is a deletion of an optimisation branch at six sites — `runtime`,
`engine` and `ast_walker` — not a substitution. `park-and-call`
(`ast_walker.cljc:139`) no longer registers a reader waiter; it places its
continuation in the wait set like every other blocked read.

`closed?` is retired with them (`runtime.cljc:115`): v2 operates and reads the
answer from the outcome.

## The module registry

v1 held `module-registry` and `effect-registry` as `defonce` atoms mutated at
load time (`module.cljc:20-21`), read implicitly by `resolve-module` and by
effect dispatch (`engine.cljc:43-47`). That is the hidden global state the
axioms forbid.

- The registry is a **value** in VM state, supplied by the composition.
  `resolve-var` takes it as an argument.
- **`:module/require` resolves against the registry value only.** v1's clj
  branch called `clojure.core/require` from inside effect dispatch — a host
  call in the middle of interpretation. It does not survive: a module is
  either in the registry the composition supplied or it is not.
- **The `stream` module has no v2 registrar.** `dao.stream.ringbuffer`
  registered a `'stream` module at load (`ringbuffer.cljc:457-464`, forced at
  390). The v2 ring buffer performs no such registration. Registering the
  module is a composition step, and `yin.vm.module` owns the definition.
  A composition registers it and supplies `:make-stream` together, or Yin
  source reaches no streams.

## The host supplies streams

v1 hardcoded its transport at two sites — `handle-make` (`engine.cljc:131-141`)
and `open-local-stream` (`vm.cljc:134-139`) — and those were the only reason
`vm.cljc:7-9` and `engine.cljc:7` required `dao.stream.ringbuffer`.

- `create-vm` takes **`:make-stream`**, a function of a capacity returning a
  create outcome. `handle-make` calls it.
- **`:make-stream` has no default.** `:primitives` has one; this must not,
  because a default would smuggle the hardcoded transport and its require back
  in. Absent a supplied constructor, `:stream/make` is unsupported and says
  so. Silent fallback to a private transport is the failure mode the rule
  exists to prevent.
- `open-local-stream`'s cljd branch goes with the require.

## The FFI

- **The pair comes from explicit `:call-in`/`:call-out` first**, matching v1's
  precedence at `vm.cljc:539-540`, else from `:make-stream`, else the store
  holds no pair. A VM with no pair is coherent — it still operates streams the
  composition handed it — but a non-nil `:bridge` on one is a construction
  error and a `:dao.stream.apply/call` fails **before `park-continuation`**.
  That ordering is required: the park runs before the first stream touch, so
  an error raised later would strand a continuation in `:parked` and consume
  an id counter.
- **The pair is bounded where v1's was unbounded** (`vm.cljc:139`,
  `apply.cljc:35-36`). v2 has no unbounded mode. The once-only state machine
  bounds outstanding requests to one per parked call and an already-read
  response is harmlessly evictable, so the rule is *capacity at least maximum
  outstanding plus one*, and **a gap at the bridge cursor is fatal, not
  resumable**: an evicted request is a parked call that can never be answered.
- **The envelope changed shape.** v1's response was
  `{:dao.stream.apply/id :dao.stream.apply/value}` and could only succeed.
  A `dao.stream.apply` response carries exactly one of `ok` or `error`.
  A missing handler was a host throw in v1's `dispatch-call`; it is now an
  `:dao.stream.apply/unknown-operation` error response that the parked
  continuation raises when it unwraps. An exception still reaches the caller;
  it no longer escapes into the polling driver.
- **Correlation is checked, not assumed.** v1 read whatever response arrived
  next on `call-out`. v2 compares the response id against the parked call's id
  and raises on a mismatch. For a single outstanding call — every v1 test and
  every v2 test — the behaviour is identical; where v1 would have silently
  misrouted, v2 reports.
- **The step is once-only.** A computed response and the successor cursor it
  belongs to are retained until the append succeeds, so a handler executes
  exactly once per request. `full` retains without advancing;
  `invalid-value`, `closed` and `transport-error` advance once, report the
  response undeliverable, and terminate the bridge.
- **Minting moved to `attach`.** `ffi/normalize` (`ffi.cljc:28-36`) fabricated
  `{:position 0}` from a bare handler map and never saw a handle, so it has
  nothing to mint against. `attach` runs where the store is visible — and on
  a built VM, so with no pair it errors exactly as construction does.

## Program observation

v1 polled its `:in-stream` from inside the VM: `engine/run-on-stream` drove
ingestion between evaluations, the VM carried `:in-stream`, `:in-cursor`, and
`:ingress-gaps`, and `step` consumed a queued batch on an idle VM.

*V7 moved this out of the VM.* `dao.stream.observer` owns the attached
program handle, the program cursor, and the gap count; host composition
attaches it through a unary capability and a portable descriptor, and drives
it with `run-on-stream` over a `{:observer observer :consumer vm}` session using the
evaluator's own readiness predicate, loader, and runner. Three consequences
are divergences in their own right:

- **Observer-owned program cursors and gaps.** `ASTWalkerVM` has no
  `:in-stream`, `:in-cursor`, or `:ingress-gaps` field, and no constructor
  accepts `:in-stream`: the obsolete option is rejected before FFI resource
  allocation rather than silently ignored. Walker `step` and `run` execute
  already-loaded work only; an idle `step` is the identity under
  `engine/ready-for-ingress?`.
- **Direct `eval` is decoupled from queued program input.** v1's `eval`
  drained the program stream as a side effect of `run`. v2's `eval` converts
  its supplied AST, loads it, and runs it; independently queued batches reach
  the VM only through explicit observer coordination. A program that used to
  observe the queue as an evaluation side effect cannot be mirrored.
- **Gap handling splits by owner.** The generic observer treats a `gap` as
  recoverable: it commits the recovery cursor, counts the loss, and continues
  with the next retained batch. Terminal read outcomes (`cursor-mismatch`,
  `invalid-cursor`, `transport-error`) are errors naming their outcome. A
  *host policy* may be stricter: the REPL shell reads the gap count around
  each round, reports the loss, and refuses further evaluation until reset.
  That latch is shell behaviour about evaluation completeness, not a second
  observer semantics — an evicted batch is a program that never ran either
  way.

Capacity on the program medium is a correctness parameter, not a tuning knob:
an evicted batch is a program that never ran. The medium is created and owned
by the host composition; the VM requires none.

### The V6 baseline record

v1 threw on `:daostream/gap` at ingress, because its transport could not
evict. Under an evict-oldest v2 transport a gap is reachable, and a lost batch
is a program the VM never ingested. The V6 port therefore advanced to the
recovery cursor, counted the loss in the VM's `:ingress-gaps`, and continued
with the next retained batch, inside the VM. V7 kept that recovery rule
unchanged and moved where it lives; nothing in the generic machinery recovers
differently than the baseline described.

## Telemetry, precisely

`enabled?` returns false, `emit-snapshot` is identity in both arities
(`ffi.cljc:118` and `ast_walker.cljc:795` use different ones), `install`
records the model, and `type-tag` is real — `ffi` evaluates
`(mapv type-tag request-args)` as an *argument* to `emit-snapshot`, so it runs
before the no-op check. The stub's `type-tag` returns `:opaque` for any
handle rather than distinguishing surfaces.

What the real emit path defers: reading `append!` outcomes; ordering all three
surface protocols before the `map?` branch so a writer-only record is not
walked field by field; summarising a cursor-ref without descent so an opaque
cursor cannot re-emit a position field; the `cursor-map?` recognition problem
(`telemetry.cljc:131-133,168-172` — v2 cursors are opaque and the contract
offers no predicate); one stream and its capacity; and the telemetry test
suite. None of it changes an evaluation result.

## What is unchanged

The CESK machine itself. Control is the AST node, the environment is a
persistent map, the store is a map, the continuation is a linked list of
frames, and every transition allocates exactly one VM value through
`cesk-return`. The scheduler is still a ready queue plus a wait set, and
`resume-from-run-queue` still merges `:store-updates` before restoring.
`dao.datom` is reused, not forked: it has no `:require` at all, so a copy
would only add drift risk.

Parity over the macro-free corpus is asserted directly, in one process, by
`yin.vm.parity-test`, against values produced by v1 in the same run rather
than against values chosen freshly.

## The semantic VM against the ast-walker

Deliverable of Phase 4 of [`yin.vm.semantic.md`](./yin.vm.semantic.md).
Both evaluators run on `yin.vm`, share `engine`, `ffi`, `module` and the
observer, and are measured against each other in §8 there. Everything above
this section applies to both. This section lists only where they differ.

**Program values: same.** A REPL corpus produced byte-identical output under
both evaluators. It covered arithmetic, `def`/`defn`, `*1` history, `let`,
`loop`/`recur`, 10⁵ tail calls, 2·10⁴-deep non-tail recursion, stream
make/put/cursor/next, Python and PHP input, datom literals, `compile`, and
the error text for an unbound symbol, a non-function, division by zero, and
an undefined macro. The differences below are in what a composition or an
inspector can observe, not in the values programs compute.

1. **`eval` refuses an AST.** The walker's `vm/eval` converts, loads, and
   runs an AST. The semantic VM executes only `:yin.code/*` segments and
   throws on a non-nil AST. Lowering (`yin.vm.linearize`) belongs to the
   composition: `linearize/ast-loader` wraps `vm-load-program` at the
   observer boundary, and `yin.repl.core/eval-ast` sends semantic-VM
   input through the program medium rather than calling `eval`.
2. **Closures print differently.** A walker closure carries its AST
   (`{:type :closure :params :body :env}`). A semantic closure carries a code
   address (`{:type :closure :params :entry :segment :env}`). `(fn [x] x)`
   at the REPL renders the two shapes. The shapes are not interchangeable:
   a closure from one evaluator cannot be applied by the other.
3. **Machine state has a different shape.** `vm/control` is
   `{:segment id :pc n}` rather than an AST node. The operand stack is a
   `:stack` register. `vm/continuation` is a vector of return frames
   (innermost last), not a linked frame list. Parked and wait-set entries,
   and reified continuations, carry `{segment pc env stack k}`. A
   continuation from one evaluator cannot resume on the other.
4. **`step` is one instruction.** The walker steps one AST transition. The
   semantic VM steps one instruction of the lowered segment (`step` is the
   hot loop with a fuel of one). Step counts and intermediate states
   therefore differ for the same program. Final values do not.
5. **Unsupported nodes fail at lowering, not at run.** `:yin/macro-expand`
   and `:vm/store-update` are outside both corpora. The walker in v2 reports
   `Unknown AST node type` when it reaches the node. The linearizer throws
   `Cannot lower unsupported node <type>` before any instruction runs, so a
   program containing such a node executes none of its prefix. No v2 surface
   compiler emits `:vm/store-update`, so only hand-built ASTs can observe
   this.
6. **Segment identity is load-checked.** Loading different code under a
   segment id already in `:code` is a load error; an identical reload is
   accepted. The walker has no code table and no such conflict.
   `ast-loader` lowers each batch below every loaded segment, so REPL
   batches never collide.
7. **Continuation handoff is code plus registers.** The semantic handoff
   demo (`datomworld.demo.continuation-handoff`) ships the segment's code
   datoms with the EDN-encoded registers. A continuation that holds a live
   stream resource is refused for shipment rather than shipped with a
   dangling handle.

**Streams and FFI: no divergence found.** Both evaluators dispatch effects
through `engine/handle-effect` over the same outcome tables above. The
retention divergence, gap handling, once-only FFI bridge, the call-pair check
before parking, and correlation checking apply to both unchanged.
`semantic_ffi_test`, `semantic_engine_test`, and
`semantic_stream_observer_test` mirror the walker's suites on the semantic
VM. The semantic VM's `:request-sent` wait entry is how a retained
full-buffer request becomes a response reader. It is a representation of the
walker's behaviour, not a different behaviour.

**Shared, not a divergence:** neither `reset` restores `:env`. Each keeps the
environment the last run ended in.
