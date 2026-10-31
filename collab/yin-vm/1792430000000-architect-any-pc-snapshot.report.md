Created-Local: 2026-10-13 18:40 +07
# Any-pc snapshots of the semantic-register VM: assessment (Architect, design note only)

Author: claude-fable-5-1. Worktree `srvm-phase7-plan` at `781a9ba8` (phases 1-6 merged). Read: `yin.vm.semantic-register-vm.continuation.md` (§3, §4, §5.2, §5.6, §7, §8.3, §11, every "Amended after 6b/6c" clause), the frozen design §4.1-4.3 and §10, `layout.cljc`, `derive.cljc`, `frame.cljc`, `rows.cljc` (`lift-task*`, `lower-task`, `live-pcs`), `semantic_register.cljc`, `engine.cljc` (the sweep, the ready queue, `handle-put`, `advance-install`, `run-loop`), `debruijn/stack.cljc` and `debruijn/register.cljc` (read only), `safepoint_harness_test.cljc`. One throwaway JVM probe was run over the harness corpus and deleted (its numbers are in §C.5); no code or doc was edited. Vocabulary per UCF §7.5.3: a *cursor* is the immutable dao.stream value, a *cursor cell* the carrier's box, a *cursor-ref* the sealed reference, a *cell* only the heap box.

## Summary for the owner (15 lines)

1. **The owner is right on the main claim.** Every instruction boundary of the semantic-register VM is a state the carrier can express canonically: `⟨A, pc, W↾L(pc), E, K⟩` plus the task context phase 6 already carries. The kernel already materializes exactly this state at fuel exhaustion (`vm-hot`, `semantic_register.cljc:142-143`), and `vm/step` is `vm-hot` with fuel 1 (`:331-332`).
2. **The static correspondence holds at every pc, not only at parking pcs.** Linear-scan allocation gives distinct physical slots to any two virtuals both in `L(p) ∪ def(p)` (`derive.cljc:173-189`), and `stack-layout` is computed for every pc (`layout.cljc:118-131`); the stack kernel's operand stack at A pc `p` is `stack-layout(p) ++ uses(p)`. Both are checks the harness must run, not theorems.
3. **No half-issued effect exists at a boundary**, because a step is atomic. But the owner's wording "performing it is the driver's job" describes the custody-gated machine only (`handle-put` under the `:running` gate parks without observing, `engine.cljc:532-533`); the ungated step performs the observe half inside the step (`observe-put`, `:498`; `ffi/put-request` inside `:ffi-call`, `semantic_register.cljc:243`). The conclusion survives; the sentence does not.
4. **The ready queue is scheduler state, not a one-task-per-carrier rule.** A ready entry is a performed effect's result (a woken `:put` has appended; a woken `:next` holds the successor cursor in `:resource-updates`) that no row kind carries. The engine's own API never leaves one at rest: `run-loop` and `scheduler-round` drain it in the same call (`engine.cljc:2171-2179`, `:2286-2287`), and an organic machine has at most one waiter. Keep the refusal; a caller crosses it with one `vm/step`.
5. **Nothing else about the outside world lives at a non-parked pc.** Call ids and park ids come from the carried `id-counter`; `:yin.k/issue` numbers are minted at park time into the pending; the custody gate is phase 7's and orthogonal; streams, cursor cells and heap cells are exactly today's cases.
6. **What changes:** one new state kind `:run` (`[segment-A pc env-cref window-cref k-cref]`, no deliver, no pending), one new root kind `:running`, validator rules R1-R3 beside A1-A6 (window = `L(pc)` exactly; `0 ≤ pc`; no body-continuity rule), the lift's `:control` branch becomes a kind instead of a refusal, the layouts gain a running-state entry point reading the kernels' live fields instead of a wait entry, and the harness gains a stepping driver.
7. **Numbering, discharge and the tail seam need no change.** Canonical cursor-cell numbering walks the state (draft §6); `live-pcs` takes the `:run` pc as `at`; the tail-completion seam concerns delivering a `tail` wait and is untouched.
8. **Real obstructions, all per-lane and all honest:** the register kernel refuses a primitive function in a register (`:continuation-registers`), and at any pc that is every boundary between loading a primitive operator and calling it; a `:push` pc of H is no A pc and maps to its successor; at a tail-call pc the stack kernel holds dead layout syntax below the operands, which the lift drops and the lower refills with nil (today's "unobservable" rule generalized).
9. **The harness at every pc:** the reference matrix is 89 cases over 106 vectors with 2,986 reachable pcs (784 parking); dynamic steps to the park are 13-44 per case (rows 1 and 22 under the four wrappers), so roughly 2,500-3,500 pre-park snapshot points plus a comparable post-park tail. Every pc is a slow-tier law; the quick tier samples five structural pcs per bare row.
10. **Value:** checkpoint at fuel exhaustion and preemption of a long pure computation (today a program with no effect for minutes has no safepoint at all), migration of a running task, step-level time travel where consecutive snapshots share most Merkle rows. **Derive, don't persist** answers the storage question: persist the last safepoint plus a step count, derive the `:run` state by replay (deterministic: no backward jumps, pure steps, effects only at parks); the `:run` row kind is still needed as the receiver-side and cross-engine canonical form.
11. **Cost:** reference-lane half (validator, row, lift/lower, laws 1/2/6 at every step) about 2 opus days plus 2-3 codex rounds; cross-engine half (layouts' running mode, the two static checks at every pc, laws 4/5 at every step, the register-kernel refusal allowlist) about 2.5 days plus 3 rounds; docs 0.5 day plus one round. About 8-9 days in total, 4-5 if the cross-engine half is dropped.
12. **Order:** after phase 7, before phase 8, as slice 6d. Phase 7 owns `rows.cljc` on the critical path; 6d would conflict with 7a and gains nothing from custody. The cross-engine half is worth doing only if the de Bruijn kernels survive phase 8 (see 13).
13. **One discrepancy to rule on:** the brief says the old stack and register kernels die at phase 8. Frozen §10 phase 8 deletes `yin.vm.semantic` (old), `debruijn-resolve`, `debruijn-linearize` and `debruijn-register-compile`, and switches the REPL and linker to A-derived lowerings; it does not list `debruijn/stack.cljc` or `debruijn/register.cljc`. If the kernels keep executing published H/R images, the layouts and the cross-engine any-pc lanes are not dead work.
14. **Recommendation:** adopt the generalization in the design now (amendment text in §F), implement the reference-lane half as slice 6d after phase 7, implement the cross-engine half only after the owner rules on 13, and never carry ready-queue entries.
15. **Verdict:** NOT READY as a slice (two questions block: the phase-8 fate of the kernels, and whether a `:running` root may enter the holder's custody path in phase 7 or stays fork-only until 8); READY as a design amendment.

---

## A. Is every instruction boundary a canonical state?

### A.1 The state the kernel holds between instructions

`vm-hot` (`semantic_register.cljc:134-268`) keeps the activation in loop locals `seg pc W E K` and, when fuel runs out, materializes `(put-registers machine seg pc W E K)` (`:142-143`, `:50-55`): `:control {:segment seg :pc pc}`, `:window W`, `:env E`, `:k K` (nil when empty). `vm/step` is `(vm-hot machine 1)` (`:331-332`). So the kernel already produces the any-pc state; what phase 6 lacks is a carrier kind for it. `W` is the untrimmed body-local window (every register the body has assigned so far); the canonical form trims it to `L(pc)` (`analysis/live`), which is sufficient by the definition of liveness: a register outside `L(pc)` is never read before it is written again. Law 1 on the reference lane (lower the trimmed window, run to the end, compare) is what proves sufficiency; it is a check, not an assumption.

The canonical any-pc state, keyed form:

```clojure
{:yin.k/state :run
 :yin.k/segment A  :yin.k/pc n          ; the instruction about to execute
 :yin.k/env E  :yin.k/window {r V …}    ; exactly L(n), sorted on the wire, nil present as [:lit nil]
 :yin.k/k [ …return frames… ]}          ; no deliver, no pending
```

It is distinct from an `act` at the same pc: an `act` at `n` is "resume at `n` once `rd` of `n−1` arrives" (window `saved(n, rd)`, `rd` absent); a `run` at `n` is "execute `n`" (window `L(n)`, which may contain that `rd`). Both are legitimate states at one pc and the kind tells them apart; the parking instruction at `n−1` has either been executed (act) or not (run at `n−1`).

### A.2 The instruction classes, the state before each, the layouts, obstructions

Notation: the boundary is **before** instruction `p` executes; `W_p = W↾L(p)`; the register kernel holds a dense `:registers` vector, `:frames`, `:continuation`, `:store-of`, `:pc` (`register.cljc:627-630`); the stack kernel holds `:stack`, `:frames`, `:continuation`, `:store-of`, `:pc` (`stack.cljc:683-686`).

| Class | A instructions | State before `p` | Register layout at `p` | Stack layout at `p` | Obstruction |
|---|---|---|---|---|---|
| pure register ops | `:const :var :closure :jump :branch-false :define :gensym :store-get :store-put` | `⟨A, p, W_p, E, K⟩`; `L(p) = use(p) ∪ (L(succ) − def(p))` | `{virt_L(p)(s) ↦ registers[s]}` over the physical image of `L(p)`, injective (A.3) | `St = K-slices ++ stack-layout(p) ++ [W[u] for u in uses(p)]` (A.4) | none. A `:var` of a primitive leaves a host function in `rd`; the register kernel refuses it on lower (`:continuation-registers`, `layout.cljc:916-947`; `register-restore` → `effects/continuation-defect`), so every pc between that `:var` and its `:call` is unlowerable **into the register kernel** (not uncanonical: the reference carrier has `:prim` rows, the stack kernel holds it) |
| non-tail `:call`, closure operand | `:call rd f args false` | before: as above with `uses = [f & args]`; after: `⟨A', entry, {}, merge(E_c, bind-params), K ++ [ret]⟩`, a **body-start** state | before: as row 1; after: `registers` = locals ++ nils (`register.cljc:683-687`), window `{}` or the params' virtuals, `L(entry)` | after: `St` = K slices only; the new frame's base is the caller's residual height (`stack.cljc:748-750`) | the validator's C2 (`0 < pc`) and C3 (`body(pc−1) = body(pc)`) refuse a body-start state; both are act-only rules and must not apply to `:run` |
| non-tail `:call`, host-fn operand, plain result | same | before: as row 1; after: `⟨A, p+1, W[rd ↦ v], E, K⟩` | same | same | none; `(apply f args)` is a pure primitive application unless the primitive is `:host`-class, which discharge already refuses |
| non-tail `:call`, host-fn yielding an effect | same | before: as row 1; after: today's `act` wait (`act-builders`, `:81-83`) or value | — | — | the observe half runs inside the step (A.5); never half-done at a boundary |
| non-tail `:call`, continuation operand | same | before: as row 1; after: `register-restore` of the captured `act` with `rd` written (`:213-218`) | same | same | none |
| tail `:call` | `:call rd f args true` | before: `⟨A, p, W_p, E, K⟩` with `L(p) = use(p)` (no successor, `analysis.cljc:27`); after: callee body-start with `K` unchanged, or the `tail` wait, or (host-fn, plain) a return into `K` | `registers` over `use(p)`'s image; the kernel's `live` operand is `[]` (unused here) | `St` = K slices ++ **dead layout syntax of the enclosing operand position** ++ `[f & args]` (`stack-layout(p)` may be non-empty, continuation §4.3) | the stack lift must drop `stack-layout(p)` at a tail site (dead in A) and the lower must refill it with nil, checked unobservable (§5.6 item 8 generalized); the `:run` at a tail pc has a window while the `tail` wait has none, and the two are different kinds |
| `:return` | `:return r` | before: `L = {r}` ∪ nothing; after: caller's `⟨pc_k, W_k[rd_k ↦ v], E_k, pop K⟩`, which is a `:run` at the caller's `pc_k` | `return-transition` writes `dest` into the restored dense vector (`register.cljc:418-423`) | truncate to the frame's base and push (`stack.cljc:522`) | none |
| `:halt` | `:halt r` | before: `L = {r}`; after: halted (today's `:halted` root) | — | — | none |
| branches | `:branch-false r t`, `:jump t` | before: `L(branch) = {r} ∪ L(p+1) ∪ L(t)`; both successors' live sets are unioned (`analysis.cljc:26`), so a value live on only one arm travels on the other too (dead there; harmless, `L` is what the design says the window is) | as row 1 | H's `branch-false` pops the test (`stack.cljc:789`); before it, the test is the stack top = `uses(p)` | none |
| closures | `:closure rd params entry` | before: `L(p)` = ancestors' pending; after: a closure value capturing `E` (`:156-161`) | the positional closure's `frames` ↔ `E` by the chain, unchanged (§4.4) | same | none |
| `:stream-next :stream-put :stream-cursor :stream-make :stream-close` | | before: `⟨A, p, W_p, E, K⟩`, `L(p) = use(p) ∪ saved(p+1, rd)`; after: value at `p+1` or today's `act` wait | as row 1 | `St` ends in `uses(p)` (`:stream-put`: target then value, the emitter's order, `derive.cljc:134-135`) | none; A.5 for where the observe half runs |
| `:ffi-call` | `:ffi-call rd op args` | before: as above; after: today's parked bookkeeping under the call id plus the `:ffi`/`:ffi-request` wait, both minted **inside one step** (`:237-254`) | as row 1 | `St` ends in `args` | none at the boundary; the call id comes from `park-id` over the carried `id-counter` (`engine.cljc:2290-2294`) |
| `:park` | `:park rd` | before: `L(p) = saved(p+1, rd)`; after: today's parked record | as row 1 | as row 1 | none |
| `:current-continuation` | `:current-continuation rd` | before: `L(p) = saved(p+1, rd)`; after: a reified value in `rd` (`:255-258`) | as row 1 | as row 1 | none; the reified `act` is validated in `:captured` context as today |
| `:resume` | `:resume rd pid r` | before: `L(p) ∋ r`; after: the parked record restored with `rd` written | as row 1 | H's `[:resume pid]` reads the top | none |
| `:act` vs `:tail` frames (K) | | every `K[i]` is a return frame at a non-tail call site, whatever the current pc; K1-K3 apply unchanged | unchanged (`register-kframe`, `layout.cljc:464-473`) | unchanged (slices `[b_{i−1}, b_i)`) | none |
| install children | | the parent is blocked on `:install`; `advance-install` runs the child with `vm/run` inside the sweep to a halt or a park (`engine.cljc:1437-1446`); a parent snapshot at any pc never sees a child mid-body | — | — | a child's own any-pc states are unreachable from outside the sweep unless the sweep takes fuel; not needed, note only |

### A.3 The register pairing at every pc (static, a check)

`allocation` (`derive.cljc:161-189`) builds one interval per virtual from `min` to `max` of its live points, where the points are `L(pc) ∪ def(pc)` over the body (`:165-168`); it frees a slot only when the interval's end is `< pc` (`:178-180`) and assigns a fresh or freed slot at each start. Two virtuals both in `L(p) ∪ def(p)` have `p` inside both intervals, so both are active at `p` and hold distinct slots. Hence `virt(p, s)` restricted to the physical image of `L(p)` is single-valued at **every** pc, not only at boundary pcs, and `phys` is total on the body's named virtuals as today. Today's `virt` (`layout.cljc:224-237`) takes the preimage set `live-out*(p)`; the any-pc version takes `L(p)` (and for a `:run` at `p` the window is `L(p)`, no destination subtracted). The harness's §5.6 item 3 generalizes to every reachable pc and remains a check (verification honesty, §9 of the continuation design).

### A.4 The stack operand layout at every pc (static, a check)

`stack-layouts` (`layout.cljc:118-131`) assigns every parse node its ancestors' pending pushes, so it is defined at every pc already. At the boundary before `p`, H has additionally evaluated `p`'s children, each landing on the stack (every value-producing H opcode conjes its result, `stack.cljc:719-731`), so `St[current] = stack-layout(p) ++ [W[u] for u in uses(p)]` with `uses` in table order, which is the emitter's child order (`derive.cljc:130-141`; `:stream-put` pushes target then value, `:call` operator then operands, `:ffi-call` the operands). The generalization of §5.6 item 5 is

```
(set (stack-layout p)) ∪ (set (uses v[p])) = L(p)        for every reachable non-tail pc p
(set (uses v[p]))                          = L(p)        for every reachable tail-call pc p
```

and `|stack-layout(p)| + |uses(p)| = |St[current]|` at the kernel's state. A `:push` pc of H is a no-op pc advance (`stack.cljc:730-731`) and has no A pc; a stack-kernel snapshot at a `:push` is the snapshot at its successor (`h->a(h+1)`), which is never another `:push`. Duplicate uses (`(f x x)`) push one value twice; the set equation holds and the lift checks the duplicates' values agree.

### A.5 Where the owner's monad sentence is true and where it is not

The design intent (frozen §4.1: a wait is a pending entry; the sweep retries it, `check-wait-set`, `engine.cljc:2126-2160`) and the custody-gated machine (`handle-put` under `:running`: "nothing is observed: the put parks as it would on full", `:527-533`) match the owner's statement. The ungated step does not: `:stream-put`'s first attempt appends inside the step (`observe-put`, `:495-498`, via `handle-effect` from `run-effect`, `semantic_register.cljc:91-98`), `:stream-next` reads inside the step, and `:ffi-call` mints the park and appends the request in one arm (`:237-254`). None of this creates a half-issued effect at an instruction boundary, because each arm is one pure function of the pre-state and one observation's outcome: either the pre-state (an exception leaves the locals behind) or the post-state is what a caller sees. So claim (2) should be restated: **a step is atomic; effects are observed only inside a step or inside the sweep; every state between steps is pure data.** Claim (3) follows from that restatement.

### A.6 Cross-engine lowering at every pc (laws 4 and 5)

Law 4 (canonical equality) at step `i`: define the stop condition per kernel as "after `i` A-instructions": the reference kernel's `vm/step` count; the register kernel's step count (R is 1:1 with A, `register-image`, `derive.cljc:200-214`); the stack kernel's step count minus the `:push` steps (`correspondence`, `layout.cljc:97-108`). Parks and resumes happen at the same `i` on every lane (same program, same effects), so the three states at `i` lift to one root after `canonical-under`, by the same argument as today, plus A.3 and A.4. Law 5 (six ordered pairs) holds with one allowlisted refusal family: a lowering **into the register kernel** at a pc where a primitive function sits in `L(pc)` is `:yin.k/unsupported :continuation-registers` before any attachment, as `layout-refusals` already pins for parks (`safepoint_harness_test.cljc:1901-1925`). The harness must count those refusals per case instead of treating them as failures. There is no analogous refusal on the stack or reference lanes.

**What survives phase 8 of the cross-engine value** depends on §13 of the summary. If the kernels die with the lanes, what survives is the two static checks (A.3, A.4), which read only `derive.cljc` and `layout.cljc`'s static half, and the reference-lane laws. If the kernels survive to execute published H/R images, the layouts survive and so does the dynamic half.

---

## B. The two remaining questions

### B.1 The ready queue: the true reason for "not quiescent"

`lift-task*` refuses a non-empty ready queue first (`rows.cljc:1456-1457`). A ready entry (`make-woken-run-queue-entries`, `engine.cljc:442-463`) is the wait entry plus `:value`, `:status`, `:cursor` and `:resource-updates` (the successor cursor the sweep already took, `:453-456`), with the handle dropped. Three facts decide the question:

- **It is a performed effect's result, not a pending one.** A woken `:put` has appended (the datom is on the stream); a woken `:next` has advanced the cursor in the resource table the engine will merge on resume (`:2266-2268`). Putting the entry back as a wait would re-append the datom; dropping it would lose the read. So the queue cannot be normalized away by "undoing the wake"; this is the one place where the outside world has moved between two states the carrier might see.
- **It is representable.** The entry is pure data by design (`run-loop` docstring, `:2166-2168`); a `:ready` row `[state-cref value-V status resource-updates-cref]` would carry it, and resuming it is `register-restore` (`semantic_register.cljc:101-126`), a pure function. A terminal outcome (`terminal-resume-outcome`, `:2194-2213`) raises on resume and is not a task state the carrier expresses, so such an entry stays refused.
- **The engine never leaves it at rest.** `run-loop` sweeps and resumes in one call (`:2173-2179`); `scheduler-round` likewise (`:2286-2287`); `vm/step` on a blocked machine runs `scheduler-round`. A non-empty queue is observable only when a caller invokes `engine/check-wait-set` directly (the harness's refusal test does, `:1046`) or, with any-pc snapshots, **mid-run when two waiters woke in one sweep** and the second is queued while the first runs. An organic semantic-register machine has one control and parks by clearing it (`:control nil` on every park), so its wait set holds at most one entry and the queue at most one woken entry, which the same `run-loop` iteration resumes; the two-waiter machines in the harness are built by hand (`two-callers`, `:1055-1062`).

So the refusal is about **scheduler state with a performed effect inside it**, not about one task per carrier and not about a pending wake. Recommendation: keep `:yin.k/not-quiescent` for any non-empty queue, including under any-pc; a composition that wants to snapshot after a sweep steps once (`vm/step`, which resumes the entry) and snapshots the resulting `:run` state. The `:ready` row kind is specified here only so the choice is explicit; it is not recommended, because no engine path needs it and it would carry the one state in which an effect's outcome exists outside both the pending and the window.

### B.2 Anything else genuinely about the outside world at a non-parked pc

- **Operation ids.** `:yin.k/issue` is minted at park time under the `:running` gate onto the `:put` entry (`engine.cljc:2629-2633`); op-ids on `:put`/`:ffi-request`/`:link-request` pendings are phase 7's slots. A `:run` state has no pending, hence no op-id; the counter that will mint the next one is custody state (phase 7's root slot), unaffected.
- **Custody and the gate.** `:yin.k/gate`/`:yin.k/custody` are holder state the row carrier does not carry (phase 7 plan §3.1: lease, epoch, holder never enter a body). A `:run` snapshot under a holder is a preemption checkpoint; whether the holder may export a running task is a phase 7 policy question (§E.2, Q2), not a carrier obstruction.
- **Call ids and park ids** derive from `id-counter`, which travels (`rows.cljc:1495`).
- **Streams, cursor cells, cursor-refs, heap cells, host objects** are exactly today's cases (`lift-task*`'s walk; `:cell` and `:host-object` are refused as now).
- **Telemetry, gc pins, callable-effects index** are engine-local derived data, recomputed by the receiver, as today.
- **Host-fn application** (`(apply f args)`) is inside a step; a `:host`-class primitive is refused by discharge at lift (`:1418-1420`) whatever the pc.

Nothing at a non-parked boundary reaches the outside world that a parked boundary does not already reach. The owner's claim (3) stands with the restatement of A.5.

---

## C. What must change

### C.1 The validator (`frame.cljc`)

A third `:yin.k/state`, `:run`, with required keys `:yin.k/state :yin.k/segment :yin.k/pc :yin.k/env :yin.k/window :yin.k/k` and no optional key (S1 extended); a new context `:running` (P1: `:run` requires no pending; `:wait`, `:captured`, `:parked` never admit a `:run`). Rules, in the existing order:

| # | Rule for `:run` | Kind |
|---|---|---|
| C2′ | `pc` is an exact non-negative integer and `0 ≤ pc < (count v)` (a body-start pc is legal; today's `0 < pc` at `frame.cljc:104` is act-only) | `:pc-kind` / `:pc-bounds` |
| C3′ | no body-continuity rule (`body(pc−1) = body(pc)` is meaningless at a body start); `pc` is inside some body by construction | — |
| R1 | every window key `< k(body(pc))` | `:window-scope` |
| R2 | `(set (keys window)) = (analysis/live a pc)` exactly, reported as `:window-extra` / `:window-missing` (`window-defect`, `:47-59`, parameterized by the wanted set: `saved` for act and kframes, `live` for run) | as named |
| R3 | no `:yin.k/deliver` key (a `:run` delivers nothing) | `:frame-shape` |
| K1-K3, E1-E2, X1 | unchanged; a `:run`'s `E` is checked at its own `pc`'s chain | |

`:window-self` has no `:run` analogue (there is no destination). A `:run` at a parking pc `p` and an `act` at `p+1` are both valid and distinct; a tamper that relabels one as the other trips R2 or A5/A6.

### C.2 Lift and lower (`rows.cljc`, `layout.cljc`)

- **Kind.** `lift-task*` step 1 keeps the ready-queue and hold refusals; the `(some? (:control m))` refusal (`:1460-1461`) becomes kind `:running` when `:control` is set, `:blocked?` false, `:wait-set` empty and the queue empty; otherwise `:yin.k/not-at-safepoint` as now. The frozen §4.1 sentence "whole-task quiescence (empty ready queue)" stays; "quiescent" no longer implies "parked".
- **The walk.** The control's state node: `{:segment (A of (:segment control)) :pc pc :env (:env m) :window (select-keys (:window m) (analysis/live a pc)) :k (or (:k m) [])}`; the lift trims, the kernel does not. It contributes the context `(A, pc, E)` to discharge (`live-pcs` from `pc`, unchanged, `:1386-1399`).
- **Rows.** New kind `:run [segment-A pc-N env-cref window-cref k-cref]` (five slots; `:act` has six with `rd`). Root `kind` admits `:running`; `frames` holds one `:run` cref for that kind; `check-pendings!` adds "`:running` has exactly one frame and it is a `:run`". `validate-frames` judges it in context `:running`. `state-prim`/`lower-defect` (`layout.cljc:863-874`, `:916-947`) get the `:run` arm (window plus lexical bindings, as `:act`).
- **Restore.** `restore-task` sets `:control {:segment local :pc pc} :window W :env E :k (when (seq K) K) :halted? false :blocked? false :value nil`; nothing runs. `vm/run` then continues from `pc`.
- **Layouts.** A running kernel has no wait entry; `lift-state` reads entries. Add `lift-running` / `lower-running` reading and writing the kernels' live fields: register `{:pc :registers :frames :continuation :store-of}` → `virt` over `L(p)` (A.3), K frames as today; stack `{:pc :stack :frames :continuation :store-of}` → `h->a` with the `:push` rule (A.4), slices as today plus the current activation's `stack-layout(p) ++ uses(p)`, the dead `stack-layout(p)` dropped at a tail pc; the inverse fills `registers` to the body's count with nil outside `L(p)` (as `write-back` does, `register.cljc:473-476`) and refills a tail pc's dead layout syntax with nil. `safepoint!` (`layout.cljc:318-330`) gains a `:run` mode that accepts any pc inside a body; `canonical-task`'s `active-continuation?` refusal (`:724-725`) becomes the `:running` kind. The harness's ad-hoc read of a stepped kernel's fields (`:1870-1872`) is the shape to formalize.
- **The `:act` row and pc.** An `:act` already carries its resume pc (`:act [segment-A pc …]`, continuation §2.3); no change. There is **one** new row kind, `:run`; no `:ready` (B.1).
- **The tail-completion seam** (`stack-restore` `:return-result`, `stack.cljc:582-584`) is untouched: it delivers a `tail` wait. A `:run` at a tail-call pc is lowered as a running state and executes the tail call itself.
- **Numbering and carrier.** Nothing in draft §6's canonical cursor-cell numbering or in `emit` depends on parking; a `:run` row is walked where a `:wait`'s state is. `:yin.k/trees`, `:source-index`, discharge, module stores: unchanged. Custody slot: nil in phase 6; phase 7's exclusive arm must list `:running` in its kind table or refuse it (§E.2 Q2).

### C.3 The harness laws at every pc

- **Stepping driver.** For a case, build the world's program on the reference lane and step it: `S_0, S_1, …` by `vm/step` until the park (then satisfy and continue to the halt, stepping again). At each `S_i` with `:control` set: lift → lower into a fresh machine (same composition, streams served as today) → continue to the end → **law 1** (result and effect trace equal the reference) → relift → **law 2** (root equality under `canonical-under`) → **law 6** (every frame validates; the window tampered by one key of `L(pc)` removed or one dead register added is refused `:window-missing` / `:window-extra`). Law 3 does not apply (no pending).
- **Laws 4 and 5** on the lane fragment: the stack and register kernels stepped to the same `i` (A.6); equality after re-emission; lowering across the six ordered pairs with the register-target refusals at primitive-live pcs counted and asserted non-zero on rows that load `cat`/`conj` (every row does).
- **Static checks** once per corpus segment: A.3 (injectivity over `L(p)` at every reachable pc) and A.4 (`stack-layout(p) ∪ uses(p) = L(p)`), on `layout-corpus` (`:1806-1811`) and the matrix's vectors.
- **Matrix size** (the JVM probe over the harness's own builders, 2026-10-13): reference matrix 89 cases, 106 distinct vectors, 2,999 pcs of which 2,986 reachable and 784 parking; lane fragment 61 cases, 62 vectors, 2,068 pcs (2,063 reachable, 557 parking). Dynamic steps to the park: row 1 under `:bare/:operand/:depth-3/:captured` = 13/15/28/40 over vectors of 32/35/53/53 pcs; row 22 = 17/19/32/44 over 36/39/57/57. So every pc of the corpus is about 2,500-3,500 pre-park snapshot points for laws 1/2/6 plus a post-park tail of similar size, and about 2,000 for laws 4/5. Each point is one lift, one lower and one run to the end; today's 89-case slow test is the same work per point, so **every pc is slow-tier only** (hours, not minutes, on three hosts).
- **Sampling for the quick tier.** Per bare row (22 rows), five structural pcs: the first body-start reached, the pc after the first primitive `:var` (a host function live), the pc before the parking instruction, the first `:jump` or `:branch-false` reached (rows 15, 21), and the pc of the last `:return` before the halt; plus every step of rows 1 and 20 (tail, empty `K`). About 150 points, within the quick tier's budget.

### C.4 What does not change

`analysis.cljc` (`live` exists), `derive.cljc`, `code.cljc`, the register and stack kernels (no kernel edit: the layouts read fields the kernels already expose), `register-restore`, the tail seam, the cursor-cell numbering, `canonical-under`, `discharge!`, `check-stores!`, `check-dependencies!`, `check-custody!`, the attachment order, every pinned root id (no existing row changes shape).

---

## D. Value and cost

### D.1 What any-pc snapshots buy, against "derive, don't persist"

| Use | With `:run` snapshots | With replay from the last safepoint plus a step count |
|---|---|---|
| checkpoint at fuel exhaustion / preemption | immediate: `(vm-hot m n)` already materializes the state; lift it | not possible without the row kind: the last safepoint plus `n` is persisted, but the **receiver** must have the `:run` kind to restart without replaying, and replay is O(n) with `n` unbounded (a pure computation with no effect has no safepoint at all today) |
| migration of a running task | lift, lower, continue; the canonical form is engine-independent | replay on the receiver is also engine-independent (law 1), so this works too, at O(n) |
| time travel and debugging | every `S_i` is a root; consecutive roots share almost every row (store, free-env, code, K frames); a diff of two roots is a diff of two small row sets | `(safepoint, i)` pairs are smaller still and are what should be **persisted**; the `:run` form is what a debugger **shows** |
| replay speed | restart at `S_i` in O(size of S_i) | O(i) steps on the same kernel; deterministic because A admits no backward jump, steps are pure, effects happen only at parks, and the sweep is driven by the same durable streams |
| ledger / checkpoint size | one root per step if persisted naively; prohibitive | one safepoint per park plus integers |

The decisive reading of "derive, don't persist" (owner, standing): persist safepoints and step counts; **derive** the `:run` state by replay when it is needed in storage terms. The `:run` kind is nevertheless required as the **transient canonical form**: it is what a lift at `S_i` produces, what the cross-engine laws compare, what a receiver that cannot afford replay restores, and what the validator judges. The two are complementary: the row kind is the derived fact's shape; the ledger never stores it.

### D.2 Cost

| Part | Files | Opus days | Codex rounds (2-4 observed) | Days |
|---|---|---|---|---|
| reference-lane half: validator R-rules, `:run` row, `:running` kind, lift/lower, stepping driver, laws 1/2/6 at every step (slow) and the quick-tier sample, tampers | `frame.cljc`, `rows.cljc`, `frame_test`, `rows_test`, the harness | 2 | 2-3 | 3.5 |
| cross-engine half: `lift-running`/`lower-running`, `virt` over `L(p)`, the `:push` and tail-pc rules, static checks A.3/A.4, laws 4/5 at every step, the register-target refusal allowlist | `layout.cljc`, `layout_test`, the harness | 2.5 | 3 | 4 |
| docs: the amendment of §F into the continuation design, frozen §4.1 and UCF §7.4.2 | three design docs | 0.5 | 1 | 1 |
| **Total** | | **5** | | **8.5**, or **4.5** without the cross-engine half |

### D.3 Order relative to phases 7 and 8

- **Not before or inside phase 7.** Phase 7's 7a edits `rows.cljc` (custody stage, sorted emit) and regenerates fixtures; a concurrent `:run` row would conflict on the same file and the same kind table, and custody gains nothing from it (Stage E has no running-task scenario).
- **After 7, before 8, as slice 6d** for the reference-lane half: it depends on nothing of 8 and gives phase 8's cutover a kernel whose snapshot form is complete.
- **The cross-engine half before 8 only if the kernels die at 8**, and then only if the owner values the old engines as an any-pc oracle for a week; otherwise after 8 with the kernels surviving (summary 13), or never.

### D.4 Risks

1. **Register-target refusals dominate law 5.** Every row loads `cat` and `conj`; between the `:var` and the `:call` the register kernel refuses the lowering. The allowlist must be exact (the pc set where a `:prim` is in `L(pc)`), or law 5 degenerates into "refused everywhere". Mitigation: assert the refusal count equals the computed pc set per case.
2. **Slow-tier time.** Thousands of lift/lower/run triples on three hosts; Dart is the slowest lane. Mitigation: every-pc only under `DATOM_SLOW_TESTS=1`, the quick tier samples.
3. **A liveness bug surfaces.** If `L(p)` ever under-approximates, law 1 fails at some pc where today's parks never looked. That is a finding, not a risk of the slice; it is the main reason to run the reference-lane half at all.
4. **Phase 7 custody.** If a `:running` root may be exported by a holder, phase 7's inspector and the ledger's `:yin.k/kind` set (`#{:blocked :parked}` in `ledger/offer-defect`, phase 7 plan §3.3) must admit it, or refuse it explicitly. Decide before 6d, not during.
5. **Dart traps** (`for` over chunked seqs on thousands of points, multi-key `assoc` on nil when building `:run` frames from `{}`): known, listed in continuation §12.

---

## E. Recommendation and slice plan

### E.1 Recommendation

**Adopt the generalization in the design now** (amendment text in §F): safepoints become the delivery states of a more general snapshot relation, and the restriction to parking pcs is stated as phase 6's scope, not as a property of the machine. **Implement the reference-lane half as slice 6d after phase 7 and before phase 8.** **Defer the cross-engine half** until the owner rules whether the de Bruijn kernels survive phase 8; if they die, do it only as a one-week oracle before 8 or not at all; if they survive, do it after 8 beside the surviving layouts. **Never** carry ready-queue entries (B.1). Run the two static checks A.3 and A.4 at every pc as soon as convenient (an hour in `layout_test`, no kernel involved, survives everything).

### E.2 Blocking questions

- **Q1.** Do `debruijn/stack.cljc` and `debruijn/register.cljc` die at phase 8 (the brief's premise), or do they survive to execute published H/R images (frozen §10 phase 8 names the compilers and resolvers for deletion, not the kernels; the harness docstring at `:1423-1424` says the lanes go "with their kernels")? This decides whether the cross-engine half is dead work.
- **Q2.** May a `:running` root enter the holder's custody path in phase 7 (preemption checkpoints under a lease), or is `:running` fork-only until phase 8? This decides whether 7a's kind table and the ledger's `:yin.k/kind` set must list it.

### E.3 Slice 6d (reference lane), in the phase 7 plan's shape

**Change.** `src/cljc/yin/vm/semantic_register/frame.cljc` (state `:run`, context `:running`, rules C2′, R1-R3; `window-defect` parameterized by the wanted set); `src/cljc/yin/vm/ucf/rows.cljc` (row kind `:run`, root kind `:running`, `lift-task*` kind step, the control's state node with the trimmed window, `check-pendings!` kind rule, `restore-task` arm, `validate-frames` context); `src/cljc/yin/vm/semantic_register/layout.cljc` (`state-prim` `:run` arm only; the layouts' running mode is the deferred half); `test/yin/vm/semantic_register/frame_test.cljc`, `test/yin/vm/ucf/rows_test.cljc`, `test/yin/vm/semantic_register/safepoint_harness_test.cljc` (the stepping driver, laws at every pc, the quick-tier sample, tampers, the refusal rows). Docs: `yin.vm.semantic-register-vm.continuation.md` (§1.2, §2.3, §2.6, §3.2, §5.4, §7.1-7.2, a §10.7 inventory, §11), frozen §4.1 and UCF §7.4.2 (§F).

**Depends on.** Phase 7 merged (shared `rows.cljc`); Q2 answered.

**Acceptance.**
1. `frame-test/run-state-rules`: a `:run` built from `vm/step` at every pc of `:worked-example` validates in context `:running`; one tamper per rule C2′ (a float pc; `pc = (count v)`), R1, R2 (one live key removed → `:window-missing`; one dead register added → `:window-extra`), R3 (a deliver key present → `:frame-shape`), P1 (`:run` with a pending → `:pending-presence`; `:run` in context `:wait` → `:pending-presence`); a body-start pc validates; `act` at `p+1` and `run` at `p+1` with `rd` present both validate and each is refused under the other's kind.
2. `rows-test/run-row-bijection`: `frame->rows ∘ rows->frame` identity on every `:run` of item 1; `:run` is five slots; a sixth slot is `:row-shape` at step 2 (raw row edit with rehash, B3's rule).
3. `rows-test/running-kind`: a stepped machine lifts to kind `:running` with one `:run` frame and no pending; lowering restores `:control` at the pc with the trimmed window, runs nothing, and `vm/run` reaches the reference result; the relift's root equals the lift's under a fresh receiver (law 2); a `:running` root with two frames, with a `:wait` frame, or with a pending is `:yin.k/undecodable :kind` before any attachment (attach count zero).
4. `harness/every-pc-matches-the-reference` (`^:slow`, `slow/guard`): the stepping driver over the 89 cases; at every `S_i` laws 1, 2 and 6; the step count per case pinned (rows 1 and 22 under the four wrappers: 13/15/28/40 and 17/19/32/44 to the park); the total point count asserted.
5. `harness/sampled-pcs-match-the-reference` (quick): the five structural pcs per bare row plus every step of rows 1 and 20; laws 1, 2, 6.
6. `harness/run-refusals`: a non-empty ready queue after a hand-run `check-wait-set` is still `:yin.k/not-quiescent` with `:control` nil and with `:control` set (the two-waiter machine mid-run); the same machine after one `vm/step` lifts as `:running`; a `:run` window with a cursor-ref, a reified continuation and a nil-valued live register round-trips (rows 14, 16, 22 at the pc after the capture or the nil load).
7. `layout-test/static-checks-at-every-pc`: A.3 (injectivity over `L(p)` at every reachable pc of `layout-corpus` and the matrix's vectors) and A.4 (`stack-layout(p) ∪ uses(p) = L(p)` at every reachable non-tail pc; `uses(p) = L(p)` at every tail pc).
8. kondo clean; `bb test:sub yin.vm` green on three hosts; the Node log shows the three test namespaces; the slow tier run once at landing.

**Hosts.** JVM, Node, Dart.

**Days.** 2 implementation + 1.5 review (3 rounds) = 3.5; docs 1 beside it.

### E.4 Slice 6e (cross-engine), only after Q1

**Change.** `layout.cljc` (`lift-running`, `lower-running`, `virt` over a given virtual set, the `:push` and tail-pc rules, `safepoint!` run mode, `canonical-task`/`kernel-task` for `:running`), `layout_test.cljc`, the harness (laws 4 and 5 at every step on the 61 lane cases; the register-target refusal allowlist asserted equal to the computed primitive-live pc set per case). **Acceptance** mirrors §8.3 items 10-12 at every pc. **Days.** 2.5 + 1.5 (3 rounds) = 4.

---

## F. Wording I disagree with, and the amendment text

**Disagreements with the owner's argument.**
1. "IO is modeled as a monad … performing it is the driver's job." True of the custody-gated machine and of every retry; false of the ungated first attempt, which the step performs (`engine.cljc:498`, `semantic_register.cljc:243`). Replace by: a step is atomic; effects are observed inside a step or inside the sweep; every state between steps is pure data.
2. "Can be lowered to the register VM and the stack VM at any point in time." True of the state shape; not true of every value on the register lane (a primitive in a live register is refused by that kernel's own restore), and a `:push` pc of H is not a point of A. State it as: every A boundary has a canonical state; each kernel's layout reconstructs it at every pc it reaches, with that kernel's value refusals.
3. "Safe points are a scoping choice, not a necessity." Agreed, with one necessity kept: a woken effect's result that sits in the ready queue is a performed effect outside both the pending and the window; it is representable but is refused by choice (B.1).

**Amendment to `yin.vm.semantic-register-vm.continuation.md` (new §1.3, "States between instructions", PROPOSED; and one sentence in §4.5).**

> **1.3 States between instructions.** The two delivery states of §1.1 are the states at which the evaluator parks. They are special cases of the evaluator's general state between any two instructions, `⟨A, pc, W↾L(pc), E, K⟩`, which `vm-hot` materializes whenever fuel runs out (`semantic_register.cljc:142-143`) and which the carrier expresses as the keyed form `{:yin.k/state :run :yin.k/segment A :yin.k/pc n :yin.k/env E :yin.k/window {r V …} :yin.k/k […]}` with no `deliver` and no `pending`: the window is exactly `L(n)`, the instruction at `n` has not executed, and a `:run` at `n` differs by kind from an `act` at `n` (which carries `saved(n, rd)` and awaits `rd`). A step is atomic: effects are observed only inside a step or inside the scheduler's sweep, so no state between steps holds a half-issued effect, and the restriction of lift and lower to parking pcs (§3.2 rules A1 and C3, §4.5, `:yin.k/not-at-safepoint`) is phase 6's scope, not a property of the machine. The static correspondence of §4 holds at every pc: distinct virtuals in `L(p) ∪ def(p)` hold distinct physical registers (`derive/allocation`), and the stack kernel's operand stack at A pc `p` is `stack-layout(p) ++ uses(p)`; both are checks (§5.6). The one task state the carrier refuses by choice is a non-empty ready queue: a woken entry is a performed effect's result that lives in neither a pending nor a window, and the engine itself never leaves one at rest (`run-loop`), so a caller crosses it with one `vm/step`. Slice 6d (§8.6) lands the `:run` kind, root kind `:running`, rules C2′ and R1-R3, and the harness's laws at every pc on the reference lane; the layouts' running mode is slice 6e.

> **§4.5, replace the last clause** "never invents a safepoint: a kernel pc whose predecessor is not a parking instruction is `:yin.k/not-at-safepoint` (UCF §7.4.2)" with "never invents a state: until slice 6e, a kernel pc whose predecessor is not a parking instruction is `:yin.k/not-at-safepoint` (UCF §7.4.2); after it, such a pc lifts as a `:run` state (§1.3) and only a pc outside every body, or a `:push` pc of H, is refused".

**Amendment to the frozen design §4.1, first sentence.** Replace "A safepoint is a transition at which the machine parks." with "A safepoint is a transition at which the machine parks; it is the delivery-bearing case of the general state between instructions (continuation design §1.3), which is canonical at every pc and lifted from phase 6's slice 6d on."

**Amendment to UCF §7.4.2, after the static map.** "A foreign engine's reconstruction obligation extends to every pc of A once the receiving carrier admits `:run` states: at pc `p` it produces `A`, `p`, every register of `L(p)`, `E` and `K`; a physical location outside `L(p)`'s image is never read, and an engine that cannot hold a value of `L(p)` refuses before attachment (the register kernel's `:continuation-registers`)."

---

## Verdict

**NOT READY** as an implementation slice: **Q1** (do the de Bruijn kernels die at phase 8, which decides whether the cross-engine half is dead work) and **Q2** (may a `:running` root enter phase 7's custody path, which decides the kind tables 7a writes) must be answered first; the reference-lane slice 6d is fully specified above and can be briefed the day phase 7 merges. **READY** as a design amendment: the owner's claims (1) and (3) are confirmed by the code with the restatement of (2) in §F, and the amendment text above can be applied to the continuation design, the frozen §4.1 and UCF §7.4.2 now.
