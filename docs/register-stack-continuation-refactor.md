# Register/Stack Continuation Refactor Plan

## Goal

Unify the continuation implementation for the bytecode VMs, stack and register, as much as possible without forcing the semantic VM or AST walker into the same runtime shape.

The target is shared implementation code for:

- continuation frame snapshotting
- continuation frame restoration
- run-queue resumption
- blocked stream wait-entry construction
- reified continuation values
- parked continuation round-trips
- dao.stream.apply continuation parking/resume mechanics for bytecode VMs

The target is not a single universal VM representation. The two bytecode VMs should share the same continuation substrate while keeping their execution payloads explicit.

## Why This Refactor Is Worth Doing

Today the engine/scheduler layer is already shared in [engine.cljc](../src/cljc/yin/vm/engine.cljc), but the bytecode VMs still duplicate most continuation lifecycle code:

- Stack snapshot/restore:
  [stack.cljc:605](../src/cljc/yin/vm/stack.cljc#L605),
  [stack.cljc:635](../src/cljc/yin/vm/stack.cljc#L635)
- Register snapshot/restore:
  [register.cljc:655](../src/cljc/yin/vm/register.cljc#L655),
  [register.cljc:683](../src/cljc/yin/vm/register.cljc#L683)
- Stack run-queue resume:
  [stack.cljc:941](../src/cljc/yin/vm/stack.cljc#L941)
- Register run-queue resume:
  [register.cljc:1045](../src/cljc/yin/vm/register.cljc#L1045)
- Stack dao.stream.apply park path:
  [stack.cljc:892](../src/cljc/yin/vm/stack.cljc#L892)
- Register dao.stream.apply park path:
  [register.cljc:897](../src/cljc/yin/vm/register.cljc#L897)

This duplication has real cost:

- the same scheduler bug must be fixed multiple times
- stack/register continuation behavior can drift silently
- bytecode VM tests end up proving the same invariants twice
- refactors that should be local to "bytecode continuation lifecycle" get spread across two files

The recent dao.stream.apply regressions are a concrete example: the same broken continuation parking logic was copied into both bytecode VMs.

## Scope

### In Scope

- Stack VM continuation frames
- Register VM continuation frames
- Shared wait-entry construction for `:stream/put` and `:stream/next`
- Shared run-queue resume logic for bytecode continuations
- Shared parked continuation helpers for `:park`, `:resume`, and `:current-cont`
- Shared dao.stream.apply parking/resume helper for stack/register

### Out of Scope

- semantic VM continuation representation
- AST walker continuation representation
- bytecode instruction selection / assembler
- optimizer / JIT work
- a giant engine-level abstraction spanning every VM

## Design Constraint

Do not hide VM differences behind opaque abstraction.

The stack VM and register VM do not execute the same machine model:

- stack carries `:stack` and `:call-stack`
- register carries `:regs`, `:k`, and a result destination register

The refactor should share lifecycle code while keeping those payload differences explicit as plain data.

## Proposed Shape

Introduce a new namespace:

```clojure
src/cljc/yin/vm/bytecode_continuation.cljc
```

This namespace should hold pure helpers for bytecode continuation frames.

### Shared Frame Shape

Use one explicit frame map for both bytecode VMs:

```clojure
{:vm-kind :stack | :register
 :pc <resume-pc>
 :env <lexical-env>
 :bytecode <bytecode-vector>
 :pool <constant-pool>
 :compiled-version <version-or-nil>
 :payload {...}
 :result-target nil | {:type :register :rd <int>}}
```

Where `:payload` stays VM-specific:

```clojure
;; stack payload
{:stack <operand-stack>
 :call-stack <return-frame-stack>}

;; register payload
{:regs <register-vector>
 :k <continuation-stack>}
```

Important points:

- `:pc` must always mean the resume point, not the location of the instruction that created the continuation.
- `:result-target` is explicit and optional.
- The frame remains plain data and can still be parked, reified, queued, or attached to stream wait entries.

## Shared API

The new namespace should expose small, plain functions, not a protocol hierarchy.

Suggested API:

```clojure
(snapshot-frame state {:vm-kind ... :pc ... :payload ... :result-target ...})
(restore-frame state frame)
(restore-frame-with-value state frame value)
(stream-put-entry frame stream-id datom)
(stream-next-entry frame cursor-ref stream-id)
(reified-continuation frame)
(resume-run-queue-entry state entry)
```

The API should not know how to execute bytecode. It should only know how to:

- carry continuation data
- attach scheduler metadata
- restore VM state from that data

## Adapter Boundary

Each bytecode VM should contribute only the payload-specific operations.

Suggested adapter data:

```clojure
{:vm-kind :stack | :register
 :snapshot-payload (fn [state opts] ...)
 :apply-payload (fn [state payload] ...)
 :apply-result (fn [state result-target value] ...)}
```

Examples:

- Stack `:snapshot-payload` captures `:stack` and `:call-stack`
- Stack `:apply-result` pushes onto `:stack`
- Register `:snapshot-payload` captures `:regs` and `:k`
- Register `:apply-result` writes into the destination register

This keeps the shared code explicit:

- common frame fields live in one place
- payload-specific state application stays local

## Normalization Before Extraction

Before moving code, normalize the semantics that currently differ only because of local implementation choices.

### 1. Normalize Resume PC Semantics

The stack VM currently uses two different conventions:

- normal call snapshots store the next PC
- `:park` snapshots the current PC and compensates on resume with `(inc (:pc parked))`

See:

- [stack.cljc:917](../src/cljc/yin/vm/stack.cljc#L917)
- [stack.cljc:922](../src/cljc/yin/vm/stack.cljc#L922)

This should be normalized first.

Plan:

- make every continuation snapshot store resume PC
- remove stack-specific post-resume PC adjustment

This single rule is necessary if stack/register are going to share restore logic cleanly.

### 2. Normalize dao.stream.apply Resume Path

dao.stream.apply should resume through the same continuation restoration path as every other blocked bytecode effect.

That means:

- no ad hoc restoration logic in stack/register
- no duplicated response-value extraction logic if it can be shared safely
- no separate continuation lifecycle for dao.stream.apply

## Proposed Phases

### Phase 1: Extract Shared Frame Data Helpers

Create `yin.vm.bytecode-continuation` with:

- common frame shape
- snapshot helper
- wait-entry helpers
- reified continuation helper

At this phase, stack/register can still keep their own `restore-frame` if needed.

Acceptance:

- local frame map construction in stack/register becomes a thin wrapper or disappears
- stream wait-entry construction no longer duplicates the same merge logic in two files

### Phase 2: Extract Shared Restore Logic

Move frame restoration into the shared namespace by using VM-specific adapters for payload application and result placement.

Acceptance:

- delete local `restore-frame` from stack/register
- shared helper handles both "restore without value" and "restore with value"

### Phase 3: Unify Park/Resume/Reified Continuation

Migrate these bytecode operations to the shared continuation helpers:

- `:park`
- `:resume`
- `:current-cont`
- run-queue resumption

Acceptance:

- stack/register no longer carry separate `resume-from-run-queue` implementations
- stack/register no longer hand-roll reified continuation maps

### Phase 4: Unify dao.stream.apply for Bytecode VMs

After the shared continuation layer exists, move the bytecode dao.stream.apply park path onto it.

The shared helper should:

- snapshot bytecode continuation frame
- construct the response waiter entry
- handle non-waitable fallback correctly
- handle full `call-in` backpressure correctly
- restore via the same shared continuation path

This is where the continuation refactor pays off immediately: one dao.stream.apply fix instead of two.

Acceptance:

- stack/register share the same dao.stream.apply continuation helper
- the recent dao.stream.apply regression tests pass in both namespaces

### Phase 5: Delete Redundant Local Helpers

After migration:

- remove duplicate `snap-frame`
- remove duplicate `restore-frame`
- remove duplicate bytecode-specific wait-entry assembly
- remove duplicate dao.stream.apply continuation parking code

## Test Plan

Add shared continuation contract tests in a new namespace, for example:

```clojure
test/yin/vm/bytecode_continuation_test.cljc
```

These tests should run the same continuation contract against both adapters.

### Shared Contract Cases

- snapshot/restore round trip preserves bytecode continuation state
- restore with value places result correctly
- reified continuation preserves resume PC semantics
- blocked `:stream/put` wait entry retains frame data
- blocked `:stream/next` wait entry retains frame data
- parked continuation resumes and is removed from `:parked`
- dao.stream.apply with non-waitable response stream resumes through wait-set polling
- dao.stream.apply with full request stream parks as a writer waiter

### Existing Namespace Coverage

Keep the VM-specific tests in:

- [stack_test.cljc](../test/yin/vm/stack_test.cljc)
- [register_test.cljc](../test/yin/vm/register_test.cljc)

Those tests should remain the end-to-end proof that the shared continuation substrate still matches each VM's instruction semantics.

## Risks

### Off-by-One PC Errors

The largest correctness risk is PC normalization, especially in stack `:park` / `:resume`.

Mitigation:

- normalize resume PC semantics before broader extraction
- add dedicated tests for parked continuation resume location

### Result Placement Drift

Stack and register do not materialize return values the same way.

Mitigation:

- keep result placement in a VM-specific adapter function
- test value placement directly

### Compiled Artifact Drift

Frames also carry:

- `:bytecode`
- `:pool`
- `:compiled-version`

That data must stay intact across park/resume and recompilation boundaries.

Mitigation:

- include these fields in shared continuation contract tests
- test resume after a recompilation boundary in existing VM tests

### Over-Abstraction

The wrong version of this refactor turns into a generic continuation framework that is harder to reason about than the current duplication.

Mitigation:

- keep the shared namespace small
- keep frame maps plain
- use explicit payload adapters, not deep protocols or macros
- stop at stack/register

## Done Criteria

This refactor is complete when all of the following are true:

- stack/register use one shared bytecode continuation frame shape
- stack/register use one shared snapshot/restore implementation
- stack/register use one shared run-queue resume implementation
- stack/register use one shared stream wait-entry construction path
- stack/register use one shared dao.stream.apply continuation helper
- local continuation helper duplication is deleted from both VM files
- shared continuation contract tests pass
- existing stack/register end-to-end VM tests pass

## Recommended Implementation Order

The safest order is:

1. Normalize stack resume PC semantics
2. Extract shared frame data helpers
3. Extract shared restore logic
4. Share run-queue resume
5. Share dao.stream.apply park/resume
6. Delete old local helpers
7. Add and keep shared continuation contract tests

This keeps the refactor incremental and gives a working checkpoint after each step.
