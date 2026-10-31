Completed-GMT: 2026-09-30 20:33:13 GMT
Completed-Local: 2026-10-01 03:33:13 +07
Coding-Agent: claude
Session-ID: f8eef849-bc12-4f36-87ee-4ae5da8aaa8c

# Architect design: heap reclamation for task-heap cells

Read-only; nothing edited or executed. Evidence is master `fe8bce4a`: `engine.cljc:274-308` (sealed refs, heap liveness), `:1877-1892` (cell effect arms), `ast_walker.cljc:60-135` (positional record), `completion.cljc:98-104`, `module.cljc:374-407` (`IModuleKernel`).

## Recommended design

**Deterministic, allocation-triggered, stop-the-world mark-sweep over `:heap`, as a pure engine function, with snapshot-at-the-beginning marking so the same code can later run budgeted.**

- **State:** one new VM field `:gc {:since n :threshold t :pinned #{id}}`, declared as a record field wherever the kernel is a positional record.
- **Trigger:** the `:cell/new` arm counts allocations; when `since ≥ threshold` it calls `collect` on the state it holds. Every VM reaches that arm with a consistent, superset state, so no other trigger point is needed.
- **Roots:** the kernel answers `gc-roots` (a new `IModuleKernel` method); the engine traces plain data itself and asks the kernel `gc-children` for kernel-shaped values (frames, continuations, closures).
- **Sweep:** remove every heap id that is unmarked and was allocated before the cycle began. Ids are never reused.
- **Adaptive threshold:** after a collection, `threshold = max(base, 2 × live)`, so total work stays linear in allocations.
- **Stream-carried refs are pinned** at `:stream/put` and at FFI request time, because the VM cannot see inside a medium.

## Q1. Roots and tracing

| Category | Ruling |
|---|---|
| Kernel registers: walker `control/env/k/value`; semantic `control/stack/env/k/value`; stack VM `stack/frames/continuation`; register VM `registers/frames/continuation` | **Roots**, enumerated by the kernel. |
| `:store`, every `:module-stores` entry | Roots. |
| `:parked`, `:wait-set`, `:ready-queue` | Roots. Wait entries carry `:k`, `:env`, `:stack`, `:regs` and a retried `:datom`. |
| FFI in-flight | The parked continuation is in `:parked` (a root). Request args already appended to the call-in stream are invisible: pin at `park-and-call` (`ast_walker.cljc:201-244`). |
| `:resources` | Not traced. Stream handles and cursor cells hold no cell refs; the values inside a stream are the medium's, not the VM's. Hence pinning. |
| `:heap` itself | Traced only through marked ids. |
| Code: `:rows`, `:row-nodes`, `:program`, images, segments, `:modules` registry, primitives, `:callable-effects`, telemetry | Excluded. A literal can never be an authentic ref (the seal needs the task secret). |

**Tracing rule (engine):** scalars contribute nothing; vectors, sets, lists and map keys and values are walked; a `:cell-ref` marks its id and, if newly marked, pushes the heap entry's `:value`; a closure contributes its captured environment only, never its body; a continuation contributes what the kernel's `gc-children` returns.

**Why `gc-children` is a kernel method.** The walker's frames mix code and runtime values: `:frame` is the application node with `:evaluated` and `:fn` assoc'd in at runtime (`ast_walker.cljc:288-290`, `:308`). A generic walk would traverse the operand subtrees on every cycle; a naive skip would miss the evaluated operands and free live cells. Only the kernel knows which keys are runtime.

**Missing a root frees a live cell, so the design is miss-safe by construction:** the engine traces whatever the kernel hands it, and the kernel's `gc-roots` returns whole registers, not selected keys.

## Q2. Algorithm and trigger

- **Mark-sweep, stop-the-world, in slice 1.** `collect : vm → vm'` is a pure function; the VM stays a value.
- **Snapshot-at-the-beginning from day one.** Marking runs over the heap value as it was when the cycle began, and the sweep spares every id allocated after that point (ids are ordered by the counter). Because the heap is a persistent map, the snapshot is a reference, not a copy. This is exactly what a budgeted marker needs: the same `mark` can later run k steps per scheduler round, with `{:snapshot :counter-at-start :marked :worklist}` held in `:gc`. Slice 1 runs it to completion; the incremental form is an implementation gap, not a redesign.
- **Trigger in the `:cell/new` arm** (`engine.cljc:1877-1882`). At that point the walker has materialized its host locals (`handle-primitive-result` runs on `cesk-return`'d state), the semantic VM has called `put-registers`, and the stack and register VMs pass `vm` with the operand stack and registers still intact. The effect descriptor's `:val` is the value being boxed and must be traced as an extra root.
- **Not in the driver.** `run` on the walker does not return between transitions, so a long loop would never collect. Halt and park are additional quiescent points where the driver may also call `collect`; they are not the mechanism.
- **Not an effect.** A program-visible `gc/collect` would let guest code observe reclamation timing. The trigger is machine state, like `:vm/gensym`'s counter.
- **Bounded work:** O(live + heap) per collection, amortized O(1) per allocation under threshold doubling.

## Q3. Determinism and semantics

- **Pure function of state, triggered by counts.** Never by host memory, time, or a host GC hook. Two runs collect at the same allocation and produce identical heaps.
- **Results unchanged.** An unreachable cell cannot be read by the program. The one observable would be id reuse, so **ids are never reused**: `:id-counter` stays monotonic and shared with `gensym`.
- **A freed id fails closed.** `authentic-ref?` fails on `contains?` (`engine.cljc:297-302`). Today that reports "Forged resource reference". A ref to a swept cell can only arise through a stream (pinned) or a collector bug, so the refusal should say `:dead-or-forged-reference`, and a test must pin that it is never silently reallocated.
- **Box semantics and multi-shot:** `:parked` and every reachable `:reified-continuation` are traced through their captured `env`, `k`, `stack` and `regs`, so a continuation held in a cell or parked keeps its cells alive. Re-entry after a collection sees the same heap, as before.
- **`:id-counter` is the epoch:** "allocated before the cycle began" is `id < counter-at-start`.

## Q4. Four-VM parity and portability

- **Engine (shared, `cljc`):** `trace`, `mark`, `sweep`, `collect`, the trigger, pinning, the adaptive threshold. Persistent collections only; nothing host-specific.
- **Kernel (per VM):** `gc-roots` and `gc-children`, added to `IModuleKernel` beside `lift-closure` and `lower-closure` (`module.cljc:374-407`). Closure and frame shapes already differ per kernel there.
- **ASTWalkerVM positional-record trap:** `cesk-return` rebuilds the record positionally (`ast_walker.cljc:88-135`), so a `:gc` key added by `assoc` is dropped on the next transition. `:gc` must be a declared field, as `heap` was. The other three records take `assoc` on named keys, but declare the field there too for uniformity.
- **Parity test shape:** the same program on four VMs and three hosts yields the same heap key set after each collection.

## Q5. Interaction with later work

| Later work | Interaction |
|---|---|
| Copy-on-lift (slice 2) | The heap pull is the same reachability with a different root set (one closure's environment, or a continuation) and a different action (encode instead of mark). Share `trace` and `gc-children`; the pull reserves the portable id before descending, per the mutable-objects ruling. |
| Dependency completion | `abstract-value` (`completion.cljc:74-115`) keeps its own abstraction, but its future cell arm should call `gc-children` rather than restate frame shapes. |
| `weakref`, `__del__` | Not provided. Never calling `__del__` and never clearing a weakref conform to the Python reference. Finalizer hooks need a sweep callback, which would be a program-visible effect ordering; leave it out until asked. |
| D7 host-typed closures and continuations | Root enumeration must not read continuation maps generically. `gc-children` is the seam: a host type either implements it or the kernel's method knows it. Rule: **any host-typed VM value must expose its ref-bearing children as data.** |
| Spike phase B | Flag cells per capture point and frame cells are exactly the short-lived garbage this reclaims. |

## Q6. Minimal first slice and acceptance tests

**Slice 1:**
1. `:gc {:since :threshold :pinned}` declared on all four VM records; empty in every constructor and `spawn-module` child.
2. Engine: `trace`, `mark`, `sweep`, `collect`, trigger in `:cell/new`, adaptive threshold, pin at `:stream/put` and FFI request.
3. `IModuleKernel`: `gc-roots`, `gc-children`, implemented by four kernels.
4. Refusal reason `:dead-or-forged-reference` for an absent heap id.
5. Docs: `ast.md`/`state.md` heap section; the cell ruling's "reclamation can wait" note superseded.

**Acceptance tests, four VMs × CLJ/CLJS/CLJD:**
- A loop allocating N cells with one live at a time keeps `(count :heap)` under `threshold + live`.
- Cells reachable only through each root survive: `:parked`; a wait-set entry's `:datom`; a ready-queue entry; the store; a module store; a closure's environment; a nested value inside another cell; a walker frame's `:evaluated` operand; a register VM live register; the `:val` of the allocating effect itself.
- A ref to a swept cell is refused as `:dead-or-forged-reference`; a new allocation never reuses the id.
- A ref put on an in-task stream and read back after a collection is still authentic.
- Determinism: two runs give identical heap key sets and results.
- Semantics: the spike's e2e corpus produces identical output at `threshold = 1` and `threshold = ∞`.

## Owner decisions

1. **Stream-carried refs: pin forever (recommended) or refuse `:stream/put` of a value containing a cell ref.** Pinning is a bounded leak proportional to distinct refs ever appended; refusing breaks the in-task coroutine-over-streams pattern with objects.
2. **Stop-the-world first, budgeted marking later (recommended), or budgeted now.** The snapshot design makes the second a follow-up; doing it now delays the spike's unblocking.
3. **Default threshold and doubling policy** as a composition parameter. I recommend base 4096 allocations, `max(base, 2 × live)`.
4. **Refusal vocabulary:** one new reason `:dead-or-forged-reference`, or keep the existing message. I recommend the new reason.

## Findings

| Severity | File:line | Evidence | Correction |
|---|---|---|---|
| medium, trap | `src/cljc/yin/vm/ast_walker.cljc:88-135` | `cesk-return` rebuilds the record positionally; an `assoc`'d `:gc` key is dropped on the next transition. | Declare `gc` as a record field, as `heap` is. |
| medium, correctness | `src/cljc/yin/vm/ast_walker.cljc:288-290`, `:308` | Walker frames hold runtime values (`:evaluated`, `:fn`) inside AST nodes. | `gc-children` for walker frames traces those keys and skips operand subtrees. |
| medium, correctness | `src/cljc/yin/vm/engine.cljc:435-449`, `ast_walker.cljc:201-244` | Values appended to a stream or an FFI request leave the VM's view; a ref inside one is unreachable to the tracer. | Pin refs at append time (owner decision 1). |
| low, diagnostics | `src/cljc/yin/vm/engine.cljc:297-308` | An absent heap id reports "Forged resource reference". | Add `:dead-or-forged-reference`. |
| low, reuse | `src/cljc/yin/vm/completion.cljc:98-104` | Cell contents are refused rather than traversed; slice 2 will need the same traversal the collector builds. | Share `trace`/`gc-children`. |
| info | `src/cljc/yin/vm/engine.cljc:1877-1882` | The allocation arm is the one place every VM passes a consistent state with the new value in hand. | Put the trigger there. |
