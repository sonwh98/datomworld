# yin.vm.v2 — the divergence register

Status: deliverable of phase V6 of
[`yin.vm.v2.implementation-plan.md`](./yin.vm.v2.implementation-plan.md).
Subordinate to [`dao.stream.md`](./dao.stream.md) and
[`datom.world.md`](./datom.world.md).

Every place `yin.vm.v2` deliberately differs from `yin.vm`, with the reason.
Saying which v1 behaviours are deliberately not mirrored *is* the register: a
v2 suite that "covers the same programs" proves nothing unless the places it
cannot cover are named.

Scope is the ast-walker slice. `semantic`, `register`, `stack`, `space`,
`macro` and `wasm` are not ported, so nothing here speaks for them.

## The five user-visible changes

1. **The evaluator set shrinks and its default changes.** v2 ships one
   evaluator, `yin.vm.v2.ast-walker`. v1's REPL defaults to `:semantic`
   (`repl.cljc:183`); there is no `:semantic` here to default to.
2. **User-defined macros stop evaluating.** `yang.clojure` emits
   `:yin/macro-expand` for every macro call site. `ast-walker` has no
   `macro-expand` branch — in v1 or in v2 — and `yin.vm.macro` was required
   only by `semantic`. So `defmacro` and every macro call throw "Unknown AST
   node type". The v2 corpus is macro-free by construction.
3. **`stream/take!` is removed.** Destructive read is one reader's progress
   and every other reader's data loss, and v1's `take!` took a stream ref
   rather than a cursor, so a v2 `take!` would need the
   reader-position-in-the-medium the contract retired. Programs use `cursor`
   and `next!`. `yin.vm.v2.module/stream-module` has five bindings, not six;
   `yin.vm.v2.engine` has no `:stream/take` branch and no `handle-take`.
   An above-the-stream queue interpreter with explicit consume accounting is
   the deferred way back.
4. **Park-on-full behaves differently under a ring-buffer composition.** See
   *The retention divergence* below.
5. **Telemetry is absent.** `yin.vm.v2.telemetry` is a stub: `enabled?` is
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
  wait set (`dao.runtime.v2/write-outcome->task`). What a given composition
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
`yin.vm.v2.ffi-test/history-before-the-cursor-is-not-skipped-test` pins it.

**Three mints, not four.** `call-in-cursor-key` (`vm.cljc:542`) is dropped:
nothing in `src` read it, and the bridge cursor already covers reading
`call-in`. Four v1 tests asserted only its presence. The three mints are the
call-out cursor, the ingress cursor, and the bridge cursor.

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
apply `yin.vm.v2/default-stream-capacity` (1024), so they agree.

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
  module is a composition step, and `yin.vm.v2.module` owns the definition.
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
  A `dao.stream.v2.apply` response carries exactly one of `ok` or `error`.
  A missing handler was a host throw in v1's `dispatch-call`; it is now an
  `:dao.stream.v2.apply/unknown-operation` error response that the parked
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

## Ingress

v1 threw on `:daostream/gap` at ingress, because its transport could not
evict. Under an evict-oldest v2 transport a gap is reachable, and a lost batch
is a program the VM never ingested.

v2 **advances to the recovery cursor, counts the loss in `:ingress-gaps`, and
continues** with the next retained batch. The alternative — pretending the
stream was contiguous — hides a missing program. Terminal read outcomes
(`cursor-mismatch`, `invalid-cursor`, `transport-error`) are still errors.

Capacity on the ingress stream is a correctness parameter, not a tuning knob:
an evicted batch is a program that never ran.

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
`yin.vm.v2.parity-test`, against values produced by v1 in the same run rather
than against values chosen freshly.
