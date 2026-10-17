# yin.vm.semantic.continuation: phase 6, the continuation format and the row carrier

Status: **Companion design, phase 6 of `yin.vm.semantic.md`
§10.** Written 2026-10-11 (Architect: claude-fable-5-1) from the frozen
design as amended (`8f0b9e38`), the landed 4a evaluator
(`src/cljc/yin/vm/semantic.cljc`) and the 4b stage-1 evaluator
on branch `srvm-phase4b` (read only), the merged phase-5 derivations
design, the UCF document and its version-2 amendment, the row-carrier
draft (`yin.vm.ucf-transport-tuples.md`, body version 3, never landed),
the two de Bruijn kernels, and the landed UCF code and harness
(`src/cljc/yin/vm/ucf.cljc`, `src/cljc/yin/vm/ucf/handoff.cljc`,
`test/yin/vm/ucf/safepoint_harness_test.cljc`). Revision 2 (2026-10-11)
applies the codex review
`collab/1791760000000-architect-srvm-phase6-design-review.gpt-6.1-sol.findings.md`
(CHANGES_REQUESTED): the task's free environment is carried explicitly
(§4.4, §6.3), the stack kernel's K slices and tail completion are
corrected and gated (§4.3, §8.3), register return destinations are
recovered from A (§4.2), `act` and `tail` have separate algorithms, the
validator has a complete pure input contract and the carrier's order
(§3), the static laws use actual-successor live-out (§5.6), blinding is
a specified re-emission (§5.5), the harness matrix is expanded (§5.2),
6a is the independent codec (§8.1), and the amendment inventory is
exhaustive (§10). Revision 3 (2026-10-11) applies the codex round-2
review
`collab/1791800000000-architect-srvm-phase6-design-review-r2.gpt-6.1-sol.findings.md`
(CHANGES_REQUESTED): stack lowering bases are cumulative through each
slice (§4.3), the live operand subtracts the virtual destination before
mapping (§4.2, §5.6), the free environment's source on lift is specified
with its owner (§6.3), E2 requires lexical presence and X1 validates the
root's free environment (§3.2), the harness oracles of rows 15, 20, 22
and 23 and the segment-wrapper results are corrected (§5.2), the
cross-lane re-emission takes an explicit stream correspondence (§5.5),
and the amendment residue is closed (§10.3 R9, U7). **Revision 4
(2026-10-13, Architect: claude-fable-5-1)** amends the text after slice
6b merged (`1db08f7d`: `c3d37586` plus the fixes `a396f996`, `f650e046`,
`bafdec74`), so that §§2.5-2.6, 5.2, 6.1, 6.3, 7, 8.2 state what
`src/cljc/yin/vm/ucf/rows.cljc` (`lift-task`, `lower-task`,
`check-stores!`, `check-pendings!`, `check-dependencies!`,
`check-custody!`, `discharge!`, `live-pcs`) does; every amended clause
is marked "**Amended after 6b**" with what changed and why, §10.6
inventories them, and §11 lists the design choices the code made that
the owner has not ruled on. Sources: the engineer report
`collab/1792270000000-engineer-6b-task-transport.report.md` (design bugs
B1-B4, deviations 1-10) and the three codex reviews
(`collab/1792280000000-…`, `1792300000000-…`, `1792320000000-…`). The
frozen document is not changed by this one. **Revision 5 (2026-10-13,
Architect: claude-fable-5-1)** amends the text after slice 6c merged
(`781a9ba8`: `47ddb1d9` plus the fix round `dc545aaa`, `30730c51`), so
that §§4.1-4.3, 5.2, 5.3, 5.5, 5.6, 6.3, 7, 8.3, 8.5, 9 state what
`src/cljc/yin/vm/semantic/layout.cljc` (`segment`, `space`,
`phys`, `virt`, `lift-state`, `lower-state`, `canonical-task`,
`kernel-task`, `lower-defect`, `lift-task`, `lower-task`) and the 6c
tests (`layout_test.cljc`, the harness's cross-engine lanes,
`store_write_audit_test.clj`) do; every amended clause is marked
"**Amended after 6c (2026-10-13)**" with what changed and why, §10.7
inventories them, and §11 gains the three questions slice 6c left for
the owner, one of them answered. Sources: the
engineer report `collab/1792340000000-engineer-6c-layouts.report.md`
(deviations 1-8, design defects D1-D5), the codex review
`collab/1792350000000-review-6c-layouts.gpt-6.1-sol.findings.md` (F1-F6,
D1-D5) and the Architect's rulings
`collab/1792360000000-architect-6c-rulings.report.md` (R1-R6), whose
wording is applied verbatim where it says so. **Revision 6 (2026-10-13,
Architect: claude-fable-5-1)** corrects every clause that called the de
Bruijn kernels, the native lanes or their refusals short-lived: by the
owner's ruling of 2026-10-13 the two de Bruijn kernels are **backends**
(lowerings of the semantic bytecode, A to H and A to R), are
not deleted at phase 8, support a documented fragment that is not
widened, and refuse before any attach what they cannot represent (§4.6);
each corrected clause is marked "**Amended 2026-10-13 (backends)**", 6c
Q1's rationale is restated and 6c Q2 is closed as rejected (§11).
Subordinate to [`datom.world.md`](./datom.world.md), including its
unitarity invariant, and to
[`yin.vm.semantic.md`](./yin.vm.semantic.md)
("the frozen design"); format and rigor model:
[`yin.vm.semantic.evaluator.md`](./yin.vm.semantic.evaluator.md)
and [`yin.vm.semantic.derivations.md`](./yin.vm.semantic.derivations.md).

Every section marks its content **DECIDED** (an owner ruling, frozen
text, or code that exists) or **PROPOSED** (a design choice of this
document, open to the reviewer). File references are to the worktree at
`8f0b9e38`; line numbers are those of that commit; the 4b branch is
cited as `srvm-phase4b:semantic_register.cljc`, with the line numbers of
its stage-1 commit (`0f4522b9`) unless a citation says stage 2
(`bdf48cf4`, committed after revision 2; stage 2 moved the reified
payload to `:256`, `create-vm` to `:349-375` and `gc-roots`/`gc-children`
to `:485-488`, and changed none of the shapes this document reads).

**Cutover note (2026-10-15).** Full source/test paths and live namespace
citations use the 8f-2 names. Commit-qualified references and the
coexistence-era comparisons below retain their historical meaning; the old
stack-shaped evaluator and its APIs were deleted. See the frozen design
§10 for the completed gates.

Contents: §0 rulings and the one-paragraph answer · §1 the canonical
frame · §2 the row carrier · §3 the frame validator · §4 layouts · §5
the safepoint harness · §6 code and task context in the carrier · §7
lift and lower · §8 slices, gates and phase 7's entry conditions · §9
verification honesty · §10 findings and amendments · §11 owner
questions · §12 portability.

---

## 0. Rulings and the one-paragraph answer

### 0.1 Rulings that bind this design (DECIDED)

1. **A is the only code identity**; new identities digest under Jing's
   default (BLAKE3), never SHA-256 (`ucf/code-address` is
   `jing/segment-key`, `ucf.cljc:247-251`).
2. **No legacy**: one body version, no reader for older bodies, no
   compat shim, no migration path; clean breaks.
3. **Unitarity**: the AST is never lost and is always retrievable
   wherever code is lowered; no design assumes a tree-less receiver; the
   segment-to-tree association of the phase-5 design (§9.2 there) is a
   given, including its completeness rule: an absent entry **or an empty
   tree set** is incomplete.
4. **The vector-to-datoms mapping is retired**; the carrier carries
   positional tuples and rows, never datoms-from-vector.
5. **dao.stream is the complexity boundary**: no transport mechanics.
6. **One truth, many interpretations**: the register machine's
   configuration `C = ⟨seg, pc, W⟩` with `E`, `K` is the canonical frame
   shape; the stack and register kernels reconstruct it through their
   layouts (frozen §4.3, §10 phase 6).
7. **No file of phase 4b or phase 5 is touched**, so this phase runs
   concurrently with them (§8.5 names the dependencies that remain, and
   the one old-kernel file 6c must change).
8. **The de Bruijn kernels are backends** (owner, 2026-10-13; **Amended
   2026-10-13 (backends)**: ruling added). `debruijn/stack.cljc` and
   `debruijn/register.cljc` execute lowerings of the semantic
   bytecode (A to H, A to R; derivations design §8.3); they are not
   peers of the canonical kernel (written 2026-10-13 as
   `yin.vm.semantic-register`, its name until the phase-8 cutover) and
   are **not deleted at phase
   8** (frozen §10 phase 8 deletes only the old evaluator and the old
   lowerers). Each supports a documented fragment (§4.6), behaves
   identically to the reference on what it accepts, refuses before any
   attach what it cannot represent, and is not widened.

**Amended 2026-10-16 (any-pc, owner invariant 2026-10-17)**

9. **Preemptive migration at any pc is a primary invariant of `yin.vm`** (owner, 2026-10-17: "preemptive migration, the ability to pause and continue at any PC, is a primary invariant of yin.vm. It is why yin.vm is defined as a continuation VM based on the CESK model. The UCF being only defined at a wait or parked state is a violation of this invariant"). The carrier expresses the evaluator's complete task state between **any** two instructions, including the scheduler's products (remaining waits, completed wakes), not only its two delivery states; a safepoint is a boundary at which the program stops by itself. Slices 6d-a (carrier, reference lane), 6d-b (holder, custody, expiry) and 6e (backends) land it.

### 0.2 The answer

A continuation is a value: one **canonical frame** of the register machine, which is a `run` state `⟨A, pc, W↾L(pc), E, K⟩` at any instruction boundary (§1.3) or one of its two delivery states, `act` or `tail`, with the saved window, the delivery record and the pending wait the evaluator mints when it parks (frozen §2.4, §4.2); and a task is the product of its activation, its ordered waits and its ordered completed wakes (§2.6); plus the task context it needs: the task's **free
environment**, store slice, module stores, parked table, cursor cells, code by
A and the tree association. **§1** restates the frame as the 4b
evaluator mints it. **§2** gives the row carrier: body version 3, one
profile (`"v4"`), Merkle content rows under Jing's default digest, the
window as a sorted keyed row in which a nil value is a present
`[:lit nil]`, `:act`, `:tail`, `:window`, `:kframe`, `:reified` and
`:parked-table` rows, the `:run`, `:ready` and `:completion` rows (6d) replacing the stale stack-shaped rows, a root
`free-env` slot, the pending rows and census unchanged. **§3** gives the
validator as an ordered set of rules over the decoded frame, the carried
code and decoded task context, engine-independent, run after canonical
numbering as the carrier orders it. **§4** defines the two layouts with
separate `act` and `tail` algorithms: the register kernel's physical
registers are paired with A's virtual ones pc by pc and its return
destinations recovered from A's call; the stack kernel's operand stack
is sliced `[previous-base, base_i)` per frame, and its tail completion is
a kernel seam this phase must add and gate. **§5** is the harness over
three lanes with an expanded, concrete matrix and a specified
re-emission procedure for cross-lane equality. **§6** fixes what travels
for code and task context. **§8** cuts the slices: 6a the independent
frame and row codec, 6b full task transport (needs phase 5's scanners
and 4b stage 2), 6c the layouts and the stack tail-completion seam
(needs 5a/5b).

---

## 1. The canonical frame

### 1.1 Shapes, as the evaluator mints them

**DECIDED** (frozen §2.4 "Delivery", §4.2; evaluator design §1.3-1.6;
`srvm-phase4b:semantic_register.cljc:61-84` for `act-payload`,
`entry-builders`, `tail-builders`; `:229-234` for the reified payload
and the park). Three closed state shapes and one closed delivery record:

```clojure
act  = {:segment seg :pc n :env E :window W :k K :deliver {:deliver :rd :rd r}}
tail = {:segment seg :site n :k K :deliver {:deliver :return}}
run  = {:segment seg :pc n :env E :window W :k K}      ; between instructions; no deliver, no pending
ret  = {:type :return :segment seg :pc n :env E :window W :rd r}     ; one frame kind of K
```

- `:pc` of an `act` is the resume pc, already `pc+1` of the parking
  instruction; `:site` of a `tail` is the tail call's own pc (evaluator
  §1.5).
- `W` of an `act` is `saved(pc, rd) = W↾(L(pc) − {rd})`
  (`analysis/saved`, `analysis.cljc:92-97`), built with `select-keys`,
  so a nil-valued live register is a present key.
- `K` is a vector of `ret` frames, innermost last, or nil when empty
  (`put-registers`, `semantic_register.cljc:47-52`); each frame's window
  is `saved(frame.pc, frame.rd)` of its own body.
- A **parked record** (`:park`, and FFI bookkeeping under a call id) is
  an `act` payload plus `:type :parked-continuation :id`
  (`engine/park-continuation`; evaluator §1.6); the FFI bookkeeping
  record of an effectful **tail** call is a `tail` payload (evaluator
  §1.6).
- A **reified continuation** is `values/continuation owner (assoc act
  :type :reified-continuation)` (`srvm-phase4b:semantic_register.cljc:229-232`).
- The wait entry the engine holds is the `act` or `tail` payload plus
  the engine's fields (`:reason`, `:cursor-ref`, `:stream-id`, `:datom`,
  `:call-id`, `:request-sent`, the link fields), which travel as the
  **pending** (§2.4), never as frame slots.
- The task's **free environment** is the `:env` a `create-vm` received
  (`semantic_register.cljc:261`): every activation's `E` is that map
  merged under the lexical chain's bindings (§4.4), and a de Bruijn
  kernel holds the same map as its task-wide `:free-env`
  (`debruijn/stack.cljc:82, 418, 683`; `debruijn/register.cljc:50`).

### 1.2 The UCF frame, keyed form

**DECIDED** (frozen §4.2), restated with one naming decision (§10.1 F1):

```clojure
;; act state
{:yin.k/state :act
 :yin.k/segment A  :yin.k/pc n
 :yin.k/env   {:yin.k/bindings {sym V …} :yin.k/store-of M?}
 :yin.k/window {r V …}                      ; exactly saved(pc, rd); sorted by r on the wire
 :yin.k/deliver {:yin.k/deliver :rd :yin.k/rd r}
 :yin.k/k [{:yin.k/frame-type :return :yin.k/segment A :yin.k/pc n
            :yin.k/env E :yin.k/window {r V …} :yin.k/rd r} …]
 :yin.k/pending {…}}                        ; absent on a parked record and a reified value

;; tail state
{:yin.k/state :tail
 :yin.k/segment A  :yin.k/site n
 :yin.k/deliver {:yin.k/deliver :return}
 :yin.k/k [ … ]
 :yin.k/pending {…}}
```

This keyed form is the **decoded canonical frame**: what the validator
of §3 judges, what the layouts of §4 produce and consume, and what the
harness compares across lanes. The rows of §2 are its carrier and the
two are in bijection (§2.6).

---

**Amended 2026-10-16 (any-pc, owner invariant 2026-10-17)**

```clojure
;; run state (6d)
{:yin.k/state :run
 :yin.k/segment A :yin.k/pc n
 :yin.k/env {:yin.k/bindings {sym V …} :yin.k/store-of M?}
 :yin.k/window {r V …} ; exactly L(n), retaining nil-valued keys
 :yin.k/k […]}        ; no deliver, no pending
```

### 1.3 States between instructions, and the task product

The two delivery states of §1.1 are the states at which the evaluator parks by itself. They are special cases of its general state between any two instructions, `⟨A, pc, W↾L(pc), E, K⟩`, which `vm-hot` materializes whenever fuel runs out (`semantic.cljc:142-143`) and which the carrier expresses as the `run` state of §1.2. A step is atomic: effects are observed only inside a step or inside the scheduler's sweep, so no state between steps holds a half-issued effect; atomicity of a step is not transactionality of the outside world (an append performed before a host exception is not undone). A task is a **product**: at most one active activation (the `run`), its ordered pending waits, its ordered completed wakes (the ready queue: a woken wait with the value, status and cursor update its effect produced, carried by the `:ready` row of §2.3), its parked table, and its stores. The scheduler constructs every combination: resuming one of several woken waits leaves the others queued and the unresolved ones pending (`engine.cljc:2294-2312`, `:2305`). Every such state is **representable**. The lift refuses only what is not **portable** (a value with no wire form), not **satisfied** (code or dependencies the receiver lacks), or a **hold** (a driver-internal observation in flight: §7.1 step 1), never a pc. The static correspondence of §4 holds at every pc of a body: distinct virtuals in `L(p) ∪ def(p)` hold distinct physical registers (`derive/allocation`), and the stack kernel's operand region at A pc `p` is `stack-layout(p) ++ uses(p)` above a dead prefix (§4.3); both are checks (§5.6), not theorems. A root whose task has an active activation has kind `:running` (§2.6) and is a custody subject like a blocked or parked root (§7.3).


## 2. The row carrier

The row-carrier draft (`yin.vm.ucf-transport-tuples.md`) is adopted
**except** where it describes the stack-shaped machine or legacy
readers; §10.3 lists every replaced or deleted clause. Its §3 (rows, id
spaces, census, the explicit "version 3 verifies under BLAKE3
permanently" rule), §4 (value union), §5.1 (value rows, except
`:reified`), §5.3 (pending), §5.4 (task context, except
`:parked-table`), §6 (canonical cursor-cell numbering), §7 (equality) and §8
steps 1-6 and 8-12 stand.

### 2.1 Positioning: body version 3, one profile

**DECIDED** by ruling 2 and ruling 6. The carrier is **body version 3**.
Versions 0-2 are the old handoff's; the row reader refuses them at the
gate (`:yin.k/profile-mismatch`) and never reads, normalises or upgrades
one. Their reader (`yin.vm.ucf.handoff`) is untouched by this phase and
leaves with the stack-shaped machine at phase 8; that coexistence is
sequencing, not permission for the row reader to accept a legacy body.
The wire unit is the draft's:

```clojure
{:yin.k/handoff true  :yin.k/version 3  :yin.k/root T  :yin.k/rows [[id kind & slots] …]}
```

**PROPOSED** (reviewer: ACCEPTED). The profile map is
`{:yin.code/contract "v4" :yin.k/version 1}` and nothing else; there is
**no `:yin.k/engine` key**. The frame is the canonical machine's and
engine-independent by ruling 6; the kernel that executes it is the
receiving composition's choice. The version-2 registry
(`ucf/profiles`, `ucf.cljc:51-74`) is not consulted.

### 2.2 Rows, ids, census

**DECIDED** (draft §3, ruling 1). Every row is `[id kind & slots]`;
content rows have `id = (jing/segment-key body)` under Jing's default
digest, BLAKE3 for version 3 permanently; identity rows are cursor cells
`:yin.k/cc-<n>` numbered by discovery order (draft §6 as amended by
`yin.vm.semantic.cursor-cell-numbering.md`); the root carries
the cursor-cell census. (The words cursor, cursor cell, cursor-ref and cell
are used exactly as defined in UCF §7.5.3, "Terminology: cursor, cursor
cell, cursor-ref, cell". Before 2026-10-12 the cursor cells were called
"cells" and spelled `:yin.k/c-<n>`, `:cells`, `:cell-body`; the version-2
handoff carrier keeps those spellings.) One ordering rule: "sorted" is by the Jing canonical
bytes of the key as spelled.

### 2.3 The frame rows (replacing draft §5.2)

**DECIDED** by frozen §4.2's last paragraph; **PROPOSED** names `:act`
and `:tail` (§10.1 F1, reviewer: ACCEPTED):

| Kind | Slots | Notes |
|---|---|---|
| `:act` | `segment-A  pc-N  env-cref  window-cref  k-cref  rd-N` | the delivery record is `{:deliver :rd :rd rd-N}` by kind |
| `:run` | `segment-A pc-N env-cref window-cref k-cref` | between instructions; no delivery, no pending |
| `:ready` | `state-cref completion-cref value optional-status updates-cref optional-refusal` | an ordered completed outcome |
| `:completion` | discriminated by the table below | completed operations, separate from outstanding pendings |
| `:tail` | `segment-A  site-N  k-cref` | delivery `{:deliver :return}` by kind; **no** env, window or pc slot |
| `:window` | `r₁ V₁  r₂ V₂  …` sorted by `rᵢ` ascending, unique | `rᵢ` is `N`; **a nil-valued register is present as `rᵢ [:lit nil]`**; the empty window is `[:window]` |
| `:k` | `kframe-cref …` outermost first | `[:k]` is the empty continuation |
| `:kframe` | `segment-A  pc-N  env-cref  window-cref  rd-N` | one `:return` frame |
| `:wait` | `state-cref  pending-cref` | `state-cref` is an `:act` or a `:tail` row |
| `:reified` | `act-cref` | a captured continuation value; its act's `pc−1` is a `:current-continuation` |
| `:parked-ref` | `parked-id-P` | unchanged |
| `:env` | `bindings-cref  store-of` | unchanged (draft §5.1) |
| `:bindings` | `S₁ V₁  S₂ V₂ …` sorted by symbol | unchanged |

`:window`, `:bindings`, `:store` and every other tabled keyed row are
structural rows, never a `V`; the draft's "a reference-free collection
must be `[:lit d]`" rule is about guest collections. `rd` on `:act` is
redundant with the instruction at `pc−1` and is checked (rule A2), as
`pc` is redundant with the pending and checked.

**Amended 2026-10-16 (any-pc, owner invariant 2026-10-17)**

A ready row contains a delivery-state reference, completed-operation descriptor, outcome value, optional status, cursor-update row and optional raised-refusal value. Its delivery state is act or tail, never run. It is judged in context `:ready`, without an outstanding pending.

Completed descriptors cover ordinary reads and writes, FFI response and request-append outcomes, link-request and link-response outcomes, and install outcomes. Link/install completion does not require the request transport or install entry that existed before completion. A successful link/install outcome may have no status. Try failures return their carried value; ordinary require failures restore `:link-refused` and refusal data for the terminal-resume path.

Slot 9 is the sole cell census. A ready update pairs an existing canonical C with its independently emitted cell body. One C may name different bodies across the census and ordered update rows. Restore maps C through the census’s fresh-key map and applies the update body carried by that ready row.

| Completion tag | Slots after the tag | Restored engine meaning |
|---|---|---|
| `:next` | `C` | Ordinary completed reader; reconstruct cursor-ref and stream-id through the census. |
| `:put` | `stream-cref, datom-V, optional-op-id-D` | Ordinary completed append outcome; never append again. |
| `:ffi` | `C, call-id-D, op-D` | Completed FFI response reader; restore engine reason `:next` and correlation fields. |
| `:ffi-request` | `call-id-D, envelope-V, request-stream-cref, response-stream-cref, C, optional-op-id-D` | Completed request append; restore `:request-sent true`, so successful resume creates the response wait rather than delivering the append result to the guest. |
| `:link-request` | `link-id-D, module-S, try?-D, optional-op-id-D` | Completed link-request outcome; no outstanding request or response transport is required. |
| `:link-response` | `link-id-D, module-S, try?-D` | Completed link-response outcome. |
| `:install` | `module-S, try?-D` | Completed initializer outcome; no remaining install entry is required. |


### 2.4 Pending rows

**DECIDED** (draft §5.3, UCF §7.4.3, v2 amendment §2): one kind
`:pending`; reasons `:next :put :ffi :ffi-request :link-request
:link-response :install` with the draft's slots, unchanged. What
changes is only which state a pending rides on: a `:wait` pairs a
pending with an `:act` (write-result) **or a `:tail`** (through-return),
the frozen §4.1 table's two state columns. The 4b evaluator's in-VM
markers (`:request-sent`, `:call-id`, `:op`, `:datom`,
`:response-cursor`, `:response-stream`) are the pending's
(`handoff.cljc:702-724`, `:3219-3229`).

### 2.5 Captures, parked records, module contexts, the parked table

**DECIDED** (frozen §2.4; evaluator §1.6; draft §5.1, §5.4).

- A **reified continuation** value is `[:row B]`, `B` a `:reified
  [act-cref]` row; the act has no pending, its `pc−1` is a
  `:current-continuation`, `rd` is that instruction's destination, the
  window is `saved(pc, rd)`.
- The **parked table** is `[:parked-table P₁ state-cref₁ P₂ state-cref₂ …]`
  sorted by `P`; a state is an `:act` whose `pc−1` is `:park` (an
  explicit park), **or** FFI bookkeeping under a call id `P` that a live
  `:ffi`/`:ffi-request` pending of the same task carries: an `:act` whose
  `pc−1` is `:ffi-call`, or a `:tail` whose site is the parking tail
  `:call`. An active `parked-id` never selects a bookkeeping record (v2
  amendment §7.3, kept).
- A **module context** is `E`'s `store-of`; the module store travels as
  `[:module-store M store-cref]`; every `store-of` resolves to a carried
  module store (draft §8 step 3).
- **Install children** are whole `:task` rows in the child role (draft
  §5.4, §5.5), unchanged.

**Amended after 6b (2026-10-13), what travels and what does not**
(engineer deviations 4, 6, 7; codex round 1 accepted all three):

- **The store slice is the whole root store.** `lift-task` walks every
  entry of `(:store m)` (`store-node`), and every parked record travels
  (`parked-table` over all of `(:parked m)`), each judged by P4. No
  reachability pruning of the store is performed. *Why:* the kernel
  holds no liveness information for store entries; the conservative
  slice is correct and the draft's "exactly the reachable records" was
  never implemented.
- **Module stores travel by reachability.** A `:module-store` row is
  emitted for every `store-of` an `E` or closure names, transitively
  through the values of stores already discovered (`module-store-nodes`);
  a named store the kernel lacks is `:yin.k/non-portable
  :missing-module-store`.
- **Linked registry entries do not travel.** Their binding facts travel in
  `requires`: `:yin.k/modules {name address-keyword-or-nil}` and
  `:yin.k/host-exports {qualified-symbol profile-keyword}` for used host
  exports only. Both maps are always present, including `{}`. The receiver's
  registry stays as given and must satisfy exact address equality and each
  used host export's profile equality before any stream attaches.
- **Amended after 7 (2026-10-14)** — Reason: phase 7 admits exclusive custody on rows.
  Fork custody is nil on every task row. An exclusive blocked or parked
  root carries policy, occurrence, arbitration and next-op-seq, with
  origin on a successor; a halted exclusive root carries origin only.
  Every child has nil custody and is checked in the root's context.

### 2.6 The root (replacing draft §5.5 in two slots)

**PROPOSED**, with the reviewer's correction of the free environment:

```clojure
[T :task
   version          ; 3
   contract         ; {:yin.code/contract "v4" :yin.k/version 1}
   kind             ; :running | :blocked | :parked | :halted
   role             ; :root | :child
   id-counter       ; N
   free-env-cref    ; NEW: a :bindings row, the task's initial free environment (§6.3)
   store-cref
   module-stores    ; crefs sorted by M
   parked-cref      ; :parked-table
   cursor-cells-cref       ; :cursor-cells census
   requires         ; plain: UCF §7.6.1's map plus :yin.k/trees (§6.1)
   code             ; plain: {A canonical-vector …}, "v4" vectors verified to hash to their key
   installs         ; crefs to :install rows, sorted by module name
   frames           ; crefs to :wait rows, in wait order     (:running, :blocked, :parked)
   parked-id        ; P or nil                                (:parked only)
   result           ; V or nil                                (:halted only)
   custody          ; plain, draft §5.5's table
   run              ; slot 17: crefs to :run rows
   ready]           ; slot 18: crefs to :ready rows, in queue order
```

No `layout` slot and no native image: those were the positional
profiles' (v2 amendment §2, §4.1).

**Amended after 7 (2026-10-14)** — Reason: the owner chose nil fork custody and phase 7 enforces the slot grammar.
The custody slot admits only `:yin.k/policy`, `:yin.k/occurrence`,
`:yin.k/arbitration`, `:yin.k/next-op-seq` and `:yin.k/origin`.
Fork roots of every kind and all children carry nil. Exclusive running, blocked
or parked roots require the first four keys, with policy
`:yin.k/exclusive`, and carry origin when present; halted exclusive
roots carry origin only. A header-bearing halt without origin refuses.
No current lease, epoch or holder enters the body; the predecessor lease
inside origin is lineage evidence.

**Amended 2026-10-16 (any-pc, owner invariant 2026-10-17)**

| Kind | Machine classification | Waits | Run | Ready | Parked-id | Result |
|---|---|---:|---:|---:|---|---|
| `:running` | `:control` set | 0..n | exactly one | 0..n | absent | absent |
| `:parked` | control nil; value names a held parked record | 0..n | none | 0..n | table key | absent |
| `:blocked` | control nil; not parked; waits or ready nonempty | 0..n | none | 0..n | absent | absent |
| `:halted` | control nil; halted; waits and ready empty | none | none | none | absent | present |

A halted activation beside queued work classifies `:blocked`: the scheduler processes the queue before returning the halt result. A halted activation with outstanding waits and an empty queue also classifies `:blocked`, dropping its halt value as today. The latter is an existing carrier normalization, not a claim that the source halt value is unobservable; acceptance pins it separately.


### 2.7 The bijection with the keyed frame

**PROPOSED.** `rows->frame` and `frame->rows` (`yin.vm.ucf.rows`, §8.1)
are inverse on valid row sets and canonical keyed frames: `:act` ↔
`{:yin.k/state :act …}` with the deliver record synthesised from the
kind and `rd`; `:tail` ↔ `{:yin.k/state :tail …}`; `:window` ↔
`:yin.k/window` as a map (`[:lit nil]` ↔ a nil-valued key); `:k` ↔
`:yin.k/k` in order. `rows->frame` reshapes and decides nothing the
validator decides.

---

## 3. The frame validator

### 3.1 The pure input contract

**DECIDED** (frozen §4.2: membership recomputed from A; representation,
body scope, site/pc pairing, destination equality, delivery mode,
return-frame sites, `K` structure, pending compatibility). **PROPOSED**
(reviewer: publish the complete contract):

```clojure
(frame/defect frame code context task)
;; frame   : a decoded canonical frame (§1.2), any shape (the rules judge it)
;; code    : {A vector}, every vector already code/well-formed? (step 4 of the carrier ran)
;; context : :wait | :captured | :parked | :running | :ready
;; task    : decoded task context, plain data, no engine:
;;           {:parked-key P-or-nil                 ; the parked-table key this frame sits under (:parked only)
;;            :ffi-call-ids #{…}                   ; call ids of the task's outstanding and completed FFI call ids
;;            :free-env {sym V …}}                 ; the root's free environment, decoded
;; => nil, or {:yin.k/kind k :yin.k/path p …}

(frame/task-defect task)
;; the once-per-body rule X1 over the decoded task context (its :free-env);
;; run before any frame of the body; => nil or {:yin.k/kind k :yin.k/path p …}
```

Namespace `yin.vm.semantic.frame`, file
`src/cljc/yin/vm/semantic/frame.cljc`, requiring only
`yin.vm.semantic.code`, `.analysis`, `yin.vm.debruijn`
(`resolve-name`, for the chain of rule E2) and `yin.vm`
(`reserved-name?`). It knows no kernel and reads no engine: `task` is
decoded from the rows (the parked table's key, the pendings' call ids,
the root's `free-env`), never from a running machine. A frame a stack
kernel lifted and one the register evaluator lifted are judged by the
same function on the same inputs.

### 3.2 The rules

Rules run in the order listed; the first defect wins; every defect
carries `:yin.k/kind` and `:yin.k/path`. **Structural rules S1-S4 run
before any dependent read**, so later rules may index freely. `a =
(analysis/analyze v)` per segment; `body(p) = (code/body-index
(code/body-ranges v) p)`; `k(body)` the body's register count
(`code/parse`); `inst(p) = (nth v p)`; `chain(b)` the owner chain of
body `b` as phase-5 §1.2 (closure pcs innermost first) and `params(c) =
(nth (nth v c) 2)`.

**Structure (every frame).**

| # | Rule | Kind |
|---|---|---|
| S1 | States are `:act`, `:tail`, `:run`; a run has exactly state, segment, pc, env, window and k. Delivery states keep their existing exact key sets. | `:frame-shape` |
| S2 | `:yin.k/k` is a vector; every element is a map with exactly `:yin.k/frame-type :yin.k/segment :yin.k/pc :yin.k/env :yin.k/window :yin.k/rd`, `:yin.k/frame-type` is `:return` | `:k-shape` (path `[:yin.k/k i]`) |
| S3 | every `:yin.k/window` present (the act's, each return frame's) is a map whose keys are exact non-negative integers (on the wire: sorted ascending, unique) and whose values are `V` | `:window-representation` |
| S4 | every `:yin.k/env` present is `{:yin.k/bindings {S V …}}` optionally with `:yin.k/store-of M`; `:yin.k/deliver` is a map | `:env-shape` / `:deliver-shape` |

**Code (every frame).**

| # | Rule | Kind |
|---|---|---|
| C1 | `:yin.k/segment` of the frame and of every return frame names a key of `code` | `:segment` |
| C2 | Exact integer PC; act and return-frame PCs are positive and in bounds; run PC may be zero. | `:pc-kind`, `:pc-bounds` |
| C3 | Act and return frames retain body continuity; the run itself skips that predecessor check. | `:pc-body` |

**The current activation (`act` only; `tail` and `run` skip A1-A6).**

| # | Rule | Kind |
|---|---|---|
| A1 | `inst(pc−1)` is one of `:stream-next :stream-put :ffi-call :park :current-continuation`, or a `:call` with `tail?` false | `:site` |
| A2 | `(:yin.k/rd deliver) = (code/rd inst(pc−1))` and `deliver = {:yin.k/deliver :rd :yin.k/rd r}` exactly | `:destination` / `:deliver` |
| A3 | every window key `< k(body(pc))` | `:window-scope` |
| A4 | `rd ∉ (keys window)` | `:window-self` |
| A5 | `(set (keys window)) ⊆ (analysis/saved a pc rd)` | `:window-extra` |
| A6 | `(analysis/saved a pc rd) ⊆ (set (keys window))` | `:window-missing` |

**The tail site (`tail` only).**

| # | Rule | Kind |
|---|---|---|
| T1 | `inst(site)` is a `:call` with `tail?` true; `deliver = {:yin.k/deliver :return}` exactly | `:site` / `:deliver` |

**Return frames (every frame, each `K[i]`).**

| # | Rule | Kind |
|---|---|---|
| K1 | `inst(frame.pc−1)` in `frame.segment` is a `:call` with `tail?` false | `:return-site` |
| K2 | `frame.rd = (code/rd inst(frame.pc−1))` | `:return-destination` |
| K3 | every key of `frame.window` `< k(body(frame.pc))`; `frame.rd ∉ keys`; `(set (keys frame.window)) = (analysis/saved aₛ frame.pc frame.rd)` in that segment's analysis, reported as `:return-window-scope` / `:return-window-self` / `:return-window-extra` / `:return-window-missing` | as named |

**Environments (every `E`: the act's and each return frame's).**

| # | Rule | Kind |
|---|---|---|
| E1 | no binding key is reserved (`vm/reserved-name?`) | `:reserved-name` |
| E2 | **`E` is the free environment under the lexical chain.** With `C = (set (mapcat params (chain (body pc))))` the lexically bound names at that pc (a return frame's chain is that of its own `pc`): (a) **every name of `C` is a key of `bindings`**, a nil binding being a present key (`bind-params` nil-fills, so a lexical name is never absent; an absent one would be nil-filled on lower and break the canonical round trip); (b) `(apply dissoc bindings C) = (apply dissoc (:free-env task) C)` as maps of `V`: a key outside `C` the free environment lacks, a free key `E` lacks, or a differing value, is a defect | `:free-env-mismatch` |

**Task (once per body, before any frame; `frame/task-defect`).**

| # | Rule | Kind |
|---|---|---|
| X1 | the root's `free-env` decodes to a map `{S V …}` with symbol keys, sorted and unique on the wire, none reserved (`vm/reserved-name?`, the same check `create-vm` runs on its `:env`, `vm/check-bindings!`, `vm.cljc:161-168`); it is the `:free-env` every E2 compares against | `:free-env-shape` / `:reserved-name` (path `[:free-env]`) |

**Pending and contexts.**

| # | Rule | Kind |
|---|---|---|
| P1 | `:wait` requires pending; `:captured`, `:parked`, `:running`, `:ready` require pending absent. Run is admitted only in `:running`; `:ready` admits only act/tail. | `:pending-presence` |
| P2 | (`:wait`) the pending's reason is admitted by `inst(pc−1)` (act) or `inst(site)` (tail): `:stream-next → :next`; `:stream-put → :put`; `:ffi-call → :ffi, :ffi-request`; `:call → :next :put :ffi :ffi-request :link-request :link-response :install`; `:park` and `:current-continuation` admit none (frozen §4.1; `wire-of-kind`, `handoff.cljc:491-504`; ucf-revisions §8) | `:reason-mismatch` |
| P3 | (`:captured`) the frame is an `act` and `inst(pc−1)` is `:current-continuation` | `:captured-site` |
| P4 | (`:parked`) either `inst(pc−1)` is `:park` (an `act`), or `(:parked-key task) ∈ (:ffi-call-ids task)` and the frame is an `act` with `inst(pc−1) = :ffi-call` or a `tail` whose `inst(site)` is a tail `:call` (bookkeeping, evaluator §1.6) | `:parked-site` |

Rule E2 is the correction the review required (§4.4): a merged `E`
cannot be split into free and lexical parts without the task's free
environment, so the carrier carries it and the validator checks that
every `E` agrees with it off the lexical names. Nothing here reads a
stack depth, a stack base or a `live` vector.

**Amended 2026-10-16 (any-pc, owner invariant 2026-10-17)**

| Rule | Requirement | Diagnostic |
|---|---|---|
| R1 | Run window keys are in the body’s register scope. | `:window-scope` |
| R2 | Run window keys equal `L(pc)`; act/return windows retain `saved(pc, rd)`. | `:window-extra`, `:window-missing` |
| Q1 | A ready completion’s tag must be compatible with the delivery site, using the same site/reason relation as waits. | `:reason-mismatch` |
| Q2 | Link/install completion `try?` is boolean; completed install does not require membership in the task’s installs. | `:pending-try` |
| Q3 | Ready status is nil or a keyword; `:link-refused` requires link/install completion, `try? false`, and refusal data; other outcomes have no refusal slot. | `:ready-outcome` |
| Q4 | Every update key names a cell in this task’s slot-9 census; update rows have unique keys and valid bodies. | Existing missing-cell/duplicate-key diagnostics |
| Q5 | Every present completed operation ID passes shape-only validation. | `:malformed-op-id`, `:inexact` |

Q1 and Q2 receive the completion descriptor through the validation context; they do not fabricate a pending field on the delivery frame. Ready FFI descriptors contribute their call IDs to task context alongside outstanding FFI waits, so parked FFI records remain valid while their response or sent-request outcome is queued.


`window-defect` is parameterised by the wanted set: `saved(pc, rd)` for act/return, `live(pc)` for run. A run has no window-self analogue.

Validate every task context first; then judge outstanding waits in `:wait`, active run states in `:running`, ready delivery states in `:ready`, parked states in `:parked`, and reified states in `:captured`. Ready descriptors receive completed-operation validation separately from pending validation. FFI call IDs are collected from outstanding waits and completed outcomes.

### 3.3 Where and when the validator runs

**DECIDED** (carrier order kept: draft §8, step 7 after step 6; the
review's ruling). On **lower**: gate (1), structure (2), graph (3),
hashes (4), canonical form (5), canonical numbering (6), **frames (7)**,
pending and operations (8), dependency closure (9), custody (10),
composition (11), restore (12). In step 7 the body's decoded task
context is judged first by `frame/task-defect` (X1), then every `:act`,
`:tail`, `:run`, `:kframe`, `:reified`, ready delivery and parked-table state is decoded and
judged by `frame/defect` with its context and that task context; a defect
is `:yin.k/undecodable` with the rule's kind and the row path. A
first-defect test therefore sees a numbering defect
(`:noncanonical-cursor-cells`) before a frame defect on a body that has both,
and §8's acceptance items say so. On **lift**: the same function runs
over every assembled frame before any row is emitted; a frame the
evaluator itself minted that fails is `:yin.k/non-portable`
`:inconsistent-state`. The harness runs it over every frame a layout
reconstructs.

---

## 4. Layouts: reconstructing the canonical frame

### 4.1 What a layout is

**DECIDED** (frozen §4.3; UCF §7.4.2). A foreign engine publishes a
static map per segment and engine (`A`, resume pcs, `def`/`use`/`L`
from `analysis/analyze`, `lexically-required`, and a layout from
physical location to virtual register) and reconstructs the canonical
frame from its state at a safepoint; `lift(lower(frame)) = frame` on
admitted canonical frames. Two kernels are intended to reconstruct
canonical frames: the de Bruijn register kernel over `R(A)` and the de
Bruijn stack kernel over `H(A)` (phase 5). The AST walker is not (frozen
§10 phase 6 names the three kernels; the walker has no pc).

**Amended after 6c (2026-10-13), identity under image collision (R1,
replacing codex's F2 amendment).** A is the code identity even when
distinct A derive identical H or R. Native-image hashes remain checksums
and must not select a source segment. The de Bruijn kernels' offset
tables carry only the image checksum and refuse a second attach of a
known checksum, so a de Bruijn kernel cannot hold two segments of one
image. **Amended after 7 (2026-10-14)** — Reason: the owner retains the de Bruijn backends on the same fragment (§4.6).
A de Bruijn receiver refuses a carried task whose code holds two A of one
image before any stream is attached (`:yin.k/unsupported :ambiguous-image`), and a de Bruijn source
whose space resolves one image to several A refuses the lift
(`:yin.k/non-portable :ambiguous-image`); no enumeration order selects
an A. The semantic kernel keys code by A and holds
alpha-equivalent segments side by side; this limit is the native
kernels', not the carrier's. (**Amended 2026-10-13 (backends)**: was
"until phase 8 removes those kernels"; the kernels are backends kept
after phase 8 and this refusal is a documented limit of their fragment,
§4.6, not widened.) *What the code does:* `layout/space`
indexes segments by A and, as checksums, by `:register-id` and
`:stack-id`; `image-segment` refuses `:yin.k/non-portable
:ambiguous-image {:yin.k/image ident}` when an identity names more than
one A and `:unknown-image` when it names none, so a lift that resolves a
kernel pc, a return pc or a positional closure's body pc through a
collided image refuses inside `canonical-task`, before `rows/lift-task`
serves anything. `lower-defect`'s first clause (`image-collision`)
derives the receiver's image for every carried vector and refuses
`:yin.k/unsupported :ambiguous-image {:yin.k/image ident :yin.k/segments
#{A …}}` for the first identity two A share (identities ordered by their
printed form), before `rows/lower-task` and before any attach. *Why:*
codex F2 showed `(fn [x] x)` and `(fn [y] y)` have distinct A and one R
and one H. **Amended after 7 (2026-10-14)** — Reason: kernel retention preserves the documented backend limit.
Editing both kernels' offset tables is outside the retained backend
fragment (§4.6). Tested by
`layout-test/alpha-equivalent-segments`, including the reference-lane
survivor round trip (laws 1 and 2 over both closures).

**PROPOSED** namespace `yin.vm.semantic.layout`, file
`src/cljc/yin/vm/semantic/layout.cljc`, requiring
`yin.vm.semantic.code`, `.analysis`, `.derive` (phase 5),
`yin.vm.debruijn` (`resolve-name`), `yin.vm.engine` (`bind-params`), and
the two kernels' public state. Public: `register-layout`,
`stack-layout` (static, per segment), `lift-act`, `lift-tail`,
`lower-act`, `lower-tail` (per kernel, dispatching on the kernel's
`link-format`). **Amended after 6c (2026-10-13), the public API as
landed (engineer deviation 1, codex: acceptable).** The namespace and
file are as proposed; the requires are `dao.jing`,
`yin.vm.debruijn-code`, `yin.vm.debruijn-register-code`, the two kernels
(`yin.vm.debruijn.register`, `yin.vm.debruijn.stack`), `yin.vm`,
`yin.vm.engine`, `yin.vm.module`, `.analysis`, `.code`, `.derive`,
`yin.vm.ucf.rows` and `yin.vm.values`; `yin.vm.debruijn` is not
required (the chain is read from A's `:closure` owners, not through
`resolve-name`). The static half is `segment` (one map per A: `:pairs`,
`:phys`, `:a->h`, `:h->a`, `:layouts`, `:register`, `:stack`, the two
image ids), `space`, `segment-of`, `register-pairs`, `correspondence`,
`phys`, `virt`, `live-out`, `register-live`, `stack-layout`, `chain`,
`env-of`, `frames-of`, `slices`, `offset`, `kernel-kind`. The dynamic
half is one dispatching function each way, `lift-state` and
`lower-state` (contexts from `lift-context` and `lower-context`), with
the `act` and `tail` algorithms separate inside; `canonical-value` and
`positional-value` convert closures and continuations through guest
collections. The task level is `canonical-task`, `kernel-task`,
`attach-space`, `machine-space`, `lower-defect`, `lift-task` and
`lower-task` (§7). *Why:* one entry per direction keeps the kernel
dispatch in one place; the algorithms of §4.2-4.3 are unchanged.

### 4.2 The register kernel's layout

**DECIDED** by phase-5 §2.4 (R's bodies and pcs are A's) and §2.3
(`R(v) = locals + slot(v)`); the kernel's payload shape from
`debruijn/register.cljc:690-704` (call frame: `:site-pc :return-pc
:frames :regs :live :dest :store-of`) and `write-back` (`:455-485`:
`:write-result` rebuilds a dense register vector from `regs` and writes
`dest`; `:return-result` runs the return transition).

**Static half.** For segment `A` with `R = (:image (derive/register-image
v))`: for every pc `p`, `v[p]` names virtual registers in its `rd`,
`:reg` and `:regs` slots and `R[p]` names physical registers in the same
slots. `phys(body, v)` is read off any pc that names `v`; it is total on
the virtuals a body names. Its inverse is **restricted**: `virt(p, s)`
is defined only for `s` in the physical image of
`live-out*(p) = ⋃ L(q) for q ∈ (analysis/successors v p)` (the actual
successors, phase-5 §2.5; empty for a tail call) at a **reachable**
boundary pc `p` (`analysis/reachable`); there it is "the `v ∈ live-out*(p)`
with `phys(v) = s`", single-valued because the allocator's intervals
cover every live point and distinct live virtuals hold distinct slots
(a property the harness checks, §5.6 item 3, never assumed). Physical
operands of **unreachable** layout-syntax instructions (the structural
slot shared by several definition-less ids, phase-5 §2.2) are not
inverted; they are validated separately by the kernel's own
`register-image-defect` and by phase-5's gates, and never reach a
canonical frame (no reachable instruction reads them).

**Lift, `act`.** From the kernel's wait entry with `:resume-mode
:write-result` (payload `:image :site-pc :pc :frames :regs :live
:continuation :dest :store-of`, v2 amendment §5.3):

1. `A` = the A the image row of the entry's `:image` was derived from
   (the kernel's code space is keyed by A after phase 5; R is its
   checksum).
2. relative `pc` = the entry's `pc` minus the image row's offset
   (`image-row`/`absolute-pc`, `debruijn/register.cljc:326-337`); R's pcs
   are A's, so this is the canonical pc.
3. `rd = (code/rd (nth v (dec pc)))` **from A**, never the physical
   `:dest` (which is `phys(rd)`).
4. `W = {virt(pc−1, s) ↦ V : [s V] ∈ regs}`; the kernel's `live` is
   `(sort (map phys (disj live-out*(pc−1) rd)))` (the subtraction is on
   **virtual** ids before mapping: on the worked example `live-out*(3) =
   L(4) = {1 2}`, `(disj … 2) = {1}`, `phys → [0]`; subtracting the
   physical `3` from `{1 2}` would wrongly keep `2`), so `(keys W) =
   saved(pc, rd)`, which rule A5/A6 then checks.
5. `E` = §4.4 from the entry's `frames` and `store-of`, at `body(pc)`.
6. `K`: for each continuation frame `f`, **independently**: its
   `return-pc` is absolute in the kernel's concatenated code space, so
   first its image row `rowₖ = (image-row images return-pc)` and relative
   `pcₖ = return-pc − offsetₖ`, then `Aₖ` of that row (frames may cross
   images: a module closure called from another image returns into its
   caller's image), `rdₖ = (code/rd (nth vₖ (dec pcₖ)))` from `Aₖ`, never
   `f.dest`; `Wₖ = {virt_{Aₖ}(pcₖ−1, s) ↦ V : [s V] ∈ f.regs}`; `Eₖ` = §4.4
   from `f.frames` and `f.store-of` at `body(pcₖ)` in `Aₖ`.
7. pending: the engine fields of the entry, as `lift-pending!` maps them
   (`handoff.cljc:674-749`).

**Lift, `tail`.** From an entry with `:resume-mode :return-result`
(`dest nil`, `live []`): `A` and the relative `site = site-pc` as in 1-2
(the entry's `site-pc` is the tail call; its `pc` is `site+1`); `K` as
in 6; pending as in 7; **no** `W`, `E` or `pc` are read: the entry's
`regs` (empty by `live []`) and `frames` are discarded (frozen §2.4: a
tail state carries no window and no `E`).

**Lower, `act`.** Given a canonical `act` and the receiver's attached
image for `A` (derived by `derive/register-image` from the carried
vector, offset `off`): `:image` the row of `A`; `:site-pc = off + pc − 1`;
`:pc = off + pc`; `:regs = (sort-by first [[phys(v) V] for [v V] in W])`;
`:live = (sort (map phys (keys W)))` (the kernel's `live-exact` rule
recomputes `body-liveness` and must agree); `:dest = phys(rd)`;
`:resume-mode :write-result`; `:frames` by §4.4 from `E`; `:store-of`
from `E`; `:continuation` = for each `K[i]`, with its own `Aₖ`'s image
row `offₖ`: `{:site-pc (+ offₖ pcₖ −1) :return-pc (+ offₖ pcₖ) :regs
… :live … :dest (phys_{Aₖ} rdₖ) :frames (§4.4 from Eₖ) :store-of …}`;
`:format reffects/format-tag`, `:segment`/`:hash` the receiver's current
aggregate (never carried). Engine fields from the pending
(`lower-frame!`, `handoff.cljc:3182-3246`), `:deliver` carried.

**Lower, `tail`.** `:image` the row of `A`; `:site-pc = off + site`;
`:pc = off + site + 1`; `:regs []`; `:live []`; `:dest nil`;
`:resume-mode :return-result`; `:frames []` and `:store-of nil`
(unused: `write-back`'s `:return-result` branch reads `:frames` and
`:store-of` only to assign them before the return transition replaces
them from the popped frame, `register.cljc:461-468`; the harness checks
that the values chosen here are never observable, §5.6 item 8);
`:continuation` as for `act`. The register kernel already supports this
delivery (`:return-result`, `register.cljc:458-468`).

**Amended after 6c (2026-10-13), the unique-preimage rule for `virt`
(codex D3, adopted verbatim by R5).** At a reachable boundary, virt
first considers preimages in live-out*. Exactly one is returned. If
there are none, it may return the body's sole named virtual preimage,
solely to reconstruct a tampered window for validation. Zero or
multiple such preimages, or multiple live preimages, are refused as
:yin.k/non-portable :register-layout; no enumeration order selects a
preimage. The harness checks an ambiguous dead physical slot is
refused, a unique dead slot yields :window-extra through the frame
validator, and a missing live slot yields :window-missing. *What the
code does:* `layout/virt` filters the body's `phys` map for `s` over
the sorted `live-out*` set, answers the one live preimage, else the one
named preimage when no live one exists, else refuses with `{:yin.k/pc p
:yin.k/register s}`. *Why:* the restricted inverse of the paragraph
above left a tampered-upward `live` with no reconstruction at all
(engineer deviation 7), and codex's M9 (take the first of several
preimages) survived the suite until `harness/layout-refusals` pinned
the ambiguous case over C's reused slots (§8.3 item 12).
**Amended after 7 (2026-10-14)** — Reason: the register backend and its layout remain after phase 8.
The fallback remains a diagnostic extension of the retained register
backend (§4.6), not a layout claim.

**Amended after 6c (2026-10-13), primitives in the register kernel's
payload (codex D4, adopted verbatim by R6).** A primitive function is
an admitted carrier value (:prim). The current de Bruijn register
kernel cannot restore a primitive function reachable through its saved
register or lexical-frame payload, including nested guest collections,
closure captures and reified continuation state. Lowering a task with
such a wait or parked-state payload into that kernel refuses
:yin.k/unsupported :continuation-registers, with the payload path,
before stream attachment. This restriction does not prohibit
primitives in the task-wide free environment or in carrier values a
kernel can otherwise hold. Cross-engine acceptance explicitly records
unsupported payload cases; expanding this receiver domain requires a
separate kernel seam, and none is planned: this is a documented limit
of the register backend's fragment (**Amended 2026-10-13 (backends)**,
§4.6; the fragment is not widened). *What the code does:* `lower-defect`'s register
clause (`state-prim` over every `:wait` row's state, path `[:frames i
…]`, then every parked-table state, path `[:parked P …]`) finds the
first `:prim` row reachable through a window (`… :window r`), a lexical
binding of an `:env` (`… :env sym`, only the names the chain binds at
that pc; the free part of `E` is the kernel's `:free-env` and is
exempt), a closure's lexical bindings (`… :env sym …`), a reified
state's act, and the elements of `:vec`, `:list`, `:set` and `:map`
rows (index appended). The stack kernel has no such limit and holds
the same carriers (`harness/layout-refusals` runs each refused program
into the stack lane and checks the reference value). *Why:* the register
kernel's restore refuses primitives in registers (`vm/machine-data?`,
`:continuation-registers`); the refusal belongs before attachment and
in the layout, not in the carrier (engineer deviation 8).

### 4.3 The stack kernel's layout

**DECIDED** by phase-5 §3.2 (the emitter's walk) and the kernel's call
and return arms (`debruijn/stack.cljc:716-776`): a non-tail `:call`
pops `f` and the `argc` operands, leaving `stack'`, and pushes
`{:return-pc (inc pc) :frames frames :stack-base (count stack')}`
(`:728-733`); `:return` truncates to the frame's `stack-base` and pushes
the value (`:766-772`); a host-function effect parks with the
continuation after the call site and `stack-restore` resumes by conjing
the value at the entry's `pc` (`:748-752`, `:555-568`).

**Static half.**

1. **pc correspondence.** The stack emitter visits parse nodes in
   `project`'s order and emits one instruction per A instruction plus a
   `:push` after each child of a call, each `:stream-put` target and
   each `:ffi-call` operand (phase-5 §3.2). So H **with every `:push`
   removed** corresponds one-to-one, in order, with A: the k-th non-push
   H instruction ↔ A pc `k`; `h->a`, `a->h` are the two directions. The
   harness checks mnemonic families agree at every k (§5.6 item 4).
2. **operand layout.** For an A pc `p` and the parse node at `p`,
   `stack-layout(p)` is the vector, bottom to top, of the virtual
   registers whose values are pushed and not yet consumed while control
   is inside `p`'s expression: for each ancestor `n` and the child `c` on
   the path to `p`, the children of `n` before `c` that `n` pushes (a
   `:call`: the operator and the operands before `c`; a `:stream-put`:
   the target when `c` is the value; an `:ffi-call`: the operands before
   `c`; `:if`, `:define`, `:stream-cursor/next/close`, `:resume`: none).
   **Checked** (§5.6 item 5): for a **non-tail** parking instruction,
   `(set (stack-layout p)) = (analysis/saved a (inc p) (code/rd v[p]))`.
   For a **tail** `:call` the canonical state has no window and
   `stack-layout(p)` is not consulted (it may be non-empty for a tail
   call in operand position, whose pushed siblings are dead layout
   syntax).

**Amended 2026-10-16 (any-pc, owner invariant 2026-10-17).** Static layouts are defined at every pc of every body. For a conditional's jump pc `j`, `stack-layout(j) = stack-layout(b) ++ [rd(if)]`, where `b` is its branch pc and both arms define `rd(if)`. A body's `:return` or `:halt` terminator has layout `[]`.

Let `register-sequence(p) = layout(p) ++ uses-in-order(p)` and `expected-values(p) = [W[r] for r in register-sequence(p)]`. Compare ordered region length and contents to that sequence, including repeated-register agreement. Separately check `set(layout(p)) ∪ set(uses(p)) = L(p)` at reachable non-tail PCs except the four enumerated dead ambient operand PCs in §8.6 item 9, and `set(uses(p)) = L(p)` at reachable tail-call PCs. Ordered multiplicity is not the cardinality of `L(p)`. Retain the parked-window check.

**Amended 2026-10-17 (any-pc, 6e).** The dead-prefix rule is implemented: a tail call keeps the values below `layout(p) ++ uses(p)` without pushing a frame, so a region entered through a tail call may hold a dead prefix; `lift-running` takes the top `|stack-layout(p)| + |uses(p)|` values of the current region and the top `|stack-layout(pc_i − 1)|` of each frame's slice, drops the rest and records `:yin.k/dead-prefix n` in the lift's diagnostics, never in the frame; `lower-running` produces no prefix and recomputes bases cumulatively; a dead-ambient layout entry outside `L(p)` is not carried by the run window, nor is its value walked (only live positions are canonicalized, so the stack lane refuses nothing the reference lane's `L(p)` window would not), and the lower fills its region position with nil (write-back's discipline). The repeated-register agreement refuses on divergent values; no well-formed vector repeats a register in the sequence (§3.4 item 6 refuses the literal `[:call r f [x x]]` shape, and linearize gives every operand a fresh register), so the clause is pinned on a hand-built layout.

**K slices (corrected).** Let the kernel's continuation be
`[f₀ … f_{m−1}]` outermost first with bases `b₀ … b_{m−1}` and the
operand stack `St` of length `n`. Because `bᵢ = (count stack')` is the
caller's residual height **after** popping the callee's operands, the
slice of operands belonging to activation `i` is

```
slice(f₀)      = St[0, b₀)
slice(fᵢ)      = St[b_{i−1}, bᵢ)        for 0 < i < m
slice(current) = St[b_{m−1}, n)         ; or St[0, n) when K is empty
```

(the review's correction: `[previous-base, base_i)` with previous-base
0, and the current activation `[last-base, count)`).

**Lift, `act`.** From a wait entry `{:image :pc :frames :stack
:continuation :store-of}` (v2 amendment §5.2) whose `pc−1` in H is **not**
a tail `:call`:

1. `A` from the image row; relative H pc `pcₕ = pc − off`; canonical
   `pc = h->a(pcₕ − 1) + 1`; `rd = (code/rd v[pc−1])`.
2. `W = (zipmap (stack-layout (dec pc)) slice(current))`; the two have
   equal length (checked) and `(keys W) = saved(pc, rd)` (rule A5/A6).
3. `E` = §4.4 from `frames` and `store-of` at `body(pc)`.
4. `K`: for `fᵢ` **independently**: its `return-pc` is absolute; row and
   relative `pcₕᵢ` through the stack kernel's own offset table
   (`debruijn/stack.cljc:355`, `absolute-pc`'s inverse); `Aᵢ` of that row;
   `pcᵢ = h->a_{Aᵢ}(pcₕᵢ − 1) + 1`; `rdᵢ = (code/rd vᵢ[pcᵢ−1])`;
   `Wᵢ = (zipmap (stack-layout_{Aᵢ} (dec pcᵢ)) slice(fᵢ))`; `Eᵢ` = §4.4
   from `fᵢ.frames` and `fᵢ.store-of`.
5. pending from the engine fields.

**Lift, `tail`.** From a wait entry whose `pc−1` in H is a tail `:call`:
`A` and `site = h->a(pcₕ − 1)`; `K` as in 4; pending; the entry's
`frames`, `store-of` and `slice(current)` are **discarded** (dead
layout syntax; frozen §2.4).

**Lower, `act`.** `:image` the row of `A`; `:pc = off + a->h(pc−1) + 1`;
`:frames` by §4.4 from `E`; `:store-of` from `E`; `:stack` = the
concatenation over `K` outermost first of each `slice(fᵢ)` =
`(mapv Wᵢ (stack-layout_{Aᵢ} (dec pcᵢ)))`, then `slice(current) =
(mapv W (stack-layout (dec pc)))`; `:continuation` = for each `fᵢ`
`{:return-pc (+ offᵢ (a->h_{Aᵢ} (dec pcᵢ)) 1) :frames (§4.4 from Eᵢ)
:stack-base bᵢ :store-of …}` where **`bᵢ` is the cumulative length of
the slices up to and including `slice(fᵢ)`**: `b₀ = |slice(f₀)|`, `bᵢ =
b_{i−1} + |slice(fᵢ)|`. This is the inverse of the lift's
`slice(fᵢ) = St[b_{i−1}, bᵢ)`: the kernel's base is the caller's
residual height, which is exactly the end of the caller's own slice
(`stack.cljc:731-733`). On the multi-frame fixture of §8.3 item 6
(five return frames, slices `[0,0) [0,0) [0,0) [0,2) [2,4)`) this emits
bases `0, 0, 0, 2, 4`; the before-slice rule would emit `0, 0, 0, 0, 2`
and the kernel would truncate to the wrong height on return. `:format
stack/format-tag`; `:segment`/`:hash` the receiver's. `stack-restore`
then conjes the delivered value onto `:stack` at `:pc`, the `:push`-free
position after the parking instruction, exactly where H expects the
result.

**Lower, `tail`, and the tail-completion seam.** Today the stack kernel
resumes a parked host effect at the entry's `pc` by pushing the value
(`stack-restore`, `:566`); for a **tail** call that resumes the body at
`a->h(site)+1`, which then runs the layout syntax after the tail call
(for a tail call in operand position: the remaining pushes and the
enclosing call) **before** reaching `:return`. That is not the canonical
through-return delivery (frozen §2.4, §4.1 "effectful call, tail":
delivery **through return** against `K`). The layout therefore cannot
lower a canonical `tail` into today's stack kernel. **PROPOSED, gated
(§8.3 slice 6c-pre):** the stack kernel gains a tail-completion seam:
its park-entry builders mark an entry whose parking instruction is a
tail `:call` with `:resume-mode :return-result` (the register kernel's
existing vocabulary, `register.cljc:458-468`), and `stack-restore`
delivers such an entry by the **return transition** (pop the top frame,
truncate `:stack` to its base, push the value, restore its `frames`,
`store-of` and `return-pc`; halt with the value when `K` is empty) instead
of resuming at `pc`; the retained-FFI re-park and the response decoding
(`stack-restore`'s `:request-sent` and `:call-id` steps, `:548-557`) keep
their order before the final delivery, as in the register kernel.
Lowering a canonical `tail` then produces
`{:image row-of-A :pc (+ off (a->h site) 1) :resume-mode :return-result
:stack (concat of the K slices, in order) :frames [] :store-of nil
:continuation …}` with each continuation frame's `:stack-base bᵢ` the
**same cumulative-including rule as the `act` lowering** (the last
frame's base equals the stack's length, since the current activation
contributes no slice); the `:frames`/`:store-of` values are unobservable
(the return transition replaces them) and the harness checks that (§5.6
item 8). File:
`src/cljc/yin/vm/debruijn/stack.cljc` (not a 4b or phase-5 file); the
register kernel needs no change.

**Amended after 6c (2026-10-13), the seam has landed; what the code
does for a tail.** 6c-pre merged (`296f75fd`,
`stack_tail_completion_test.cljc`); the paragraph above is now a reading
of code. `lower-state` for a canonical `tail` into the stack kernel emits
`{:pc (+ off (a->h site) 1) :resume-mode :return-result :stack (the K
slices, in order) :frames (get fill :frames []) :continuation …}`, with
`:store-of` only when `fill` names one; into the register kernel `{:site-pc
(+ off site) :pc (+ off site 1) :regs [] :live [] :dest nil :resume-mode
:return-result :frames (get fill :frames [])}` likewise. The lift of a
stack entry decides `tail?` by `(code/tail-call? (inst seg site))` at the
A site, the register lift by the entry's `:resume-mode`;
`layout-test/tail-lowering-is-unobservable` lowers rows 6 and 20 into
both kernels with `[]`/nil and with junk `:frames`/`:store-of` and sees
equal observables (§5.6 item 8, four cases). *Why:* the seam was a
specification when this section was written; it is now code, and the
layout's tail shapes are stated as emitted.

**Amended after 6c (2026-10-13), the retained-FFI response-pair seam
(codex D2, accepted by R6 as a seam specification; the last sentence is
R2's). Amended 2026-10-13 (backends): the seam is REJECTED by the owner
(§11, 6c Q2); the specification below is kept as the record of what was
proposed and is not to be built.** On completion of a retained FFI request, each de Bruijn kernel
preserves the carried response cursor and response stream when
constructing the response wait. If both are present, response-wait-entry
is overridden with cursor-ref {:type :cursor-ref :id response-cursor}
and stream-id response-stream. A partial carried pair is rejected; the
kernel's composition-owned response pair is used only for entries that
carry neither field. Response decoding precedes delivery, including tail
delivery. Row 4 must continue on all six ordered engine pairs with the
receiving composition's call pair distinct from the source's pair. This
seam was slice 6c-post-D2, a kernel edit of its own, gated by the owner;
it was not a prerequisite of 6c. **Amended 2026-10-13 (backends):** the
owner rejected it. Retained FFI across a de Bruijn receiver is outside
the backends' documented fragment (§4.6); `unlowerable` stays `#{[4
:stack] [4 :register]}` and the law-5 counts stay 350 and 92 (was "when
it lands, `unlowerable` is emptied and the counts rise to 366 and 96").
*What the code does:*
both kernels' `:request-sent` re-park builds `ffi/response-wait-entry`
over the composition's own call pair (`register.cljc:508-514`,
`stack.cljc:564-570`), so a carried row 4 never wakes on a de Bruijn
receiver (codex probe 3 reproduced it on all four such pairs); the
harness's `unlowerable` set `#{[4 :stack] [4 :register]}` keeps those
transfers out of law 5 (§5.3). The semantic kernel's
`register-restore` already honours the carried pair
(`semantic_register.cljc:105-111`). *Why:* phase 6 edits no kernel but
6c-pre's one seam (§8.5).
**Amended after 7 (2026-10-14)** — Reason: the owner retains the backends without widening their fragment.
This second seam remains outside the documented fragment (§4.6), with
6c Q2 left open; retaining the kernels does not implement it.

### 4.4 Positional lexical frames, the free environment and `E`

**DECIDED** by frozen §2.1 and §2.4 (`E_c[ps ↦ a]` by `bind-params`),
phase-5 §1.2 (the owner chain), and the kernels' resolution of free
names through the task-wide `free-env` (`stack.cljc:683`:
`(engine/resolve-var free-env …)`). Both de Bruijn kernels hold the
lexical environment as positional frames `F = [[V …] …]` outermost first,
one per enclosing closure activation, plus the task-wide `free-env`.

**Forward (F → E), lossless.** At a pc of body `b` with
`chain = [c₀ … c_{j}]` outermost first (phase-5's `chain` reversed):

```
E = (merge free-env
           (bind-params params(c₀) F[0])
           …
           (bind-params params(c_j) F[j]))
    ∪ {engine/store-of-key M}  when the activation's closure carries store-of M
```

This reproduces the register evaluator's `E` on closure entry
(`semantic_register.cljc:141-143`: `(merge (:env c) (bind-params
(:params c) args))`, `(:env c)` being the creating activation's `E`,
itself `free-env` merged with the outer chain), including rightmost-wins
duplicates, nil-fill and extra-argument dropping, which are
`engine/bind-params`'s.

**Inverse (E → F), and why the free environment travels.** The reviewer's
counterexample, with initial free `x = 99`:

```clojure
(let [f (fn [] x)]
  ((fn [x] (f (stream-next c))) 1))
```

At the blocked read the current `E` binds `x` to 1 (the parameter). The
earlier revision recovered the receiver's free environment as "`E` minus
the chain's names", which drops `x` entirely, and the lowered de Bruijn
closure `f`, whose body is `[:load-free x]`, resolves `x` through the
task-wide `free-env` and loses 99. **So the task's free environment is
carried explicitly** (root slot `free-env`, §2.6; §6.3), every `E` is
checked against it off the lexical names (rule E2), and the inverse is:

```
free-env  := the root's free-env row (never derived from any E)
F[i]      := (mapv #(get E %) params(cᵢ))      for i = 0 … j
```

`F[i]` for an **outer** frame whose parameter is shadowed by an inner
frame receives the inner value, not the outer one; that value is
unobservable from `body(pc)` (resolution picks the innermost binder,
`resolve-name`, `debruijn.cljc:617-637`), and the canonical frame does
not carry it (frozen §2.1: parameters live in `E`, and `E` holds one
value per name). The round-trip law is therefore stated on **canonical
frames** (`lift(lower(frame)) = frame`), not on kernel state, and the
harness's `E` check (§5.6 item 6) compares the layout's `E` with the
evaluator's; shadowed outer positional values are not a claim of this
design. The free bindings that *are* observable, by any closure's
`:load-free`, are preserved exactly by the root slot.

**Closure values.** A de Bruijn closure (positional marker: segment,
entry, arity, frames `F`, store-of) reconstructs to the canonical
closure (`{:type :closure :params :entry :segment :env}`, evaluator
§1.8): `params` from A's `:closure` tuple at the owner pc of the entry
body, `env` by the forward rule over the closure's captured `F` and the
task's `free-env`, `entry` the body pc. The inverse reads `F` back from
`env` by the chain. Checked both ways (§5.6 item 7).

### 4.5 What a layout never does

**DECIDED.** A layout reads a kernel's state, A and the carried task
context; it never runs code, never fills a binding from the receiver's
store or free environment (the free environment is the **carried** one,
§6.3), never reads a native image's hash as an identity, and never
invents a state: a kernel pc outside every body is
`:yin.k/not-at-safepoint` (UCF §7.4.2, as amended; the name is
historical); every other kernel pc lifts, as a delivery state when the
kernel is parked and as a `run` state when it is between instructions
(6e); a `:push` pc of H is relabelled to its successor — its state is
its successor's but for pc — without running anything. The relabel is
the run state's only: a delivery state's parking instruction or a
closure's entry is never a `:push`, and one named there refuses
`:yin.k/not-at-safepoint` rather than borrow its successor's pc.
**Amended 2026-10-17 (any-pc, 6e).**

### 4.6 The de Bruijn kernels are backends

**DECIDED** (owner ruling, 2026-10-13; **Amended 2026-10-13
(backends)**: section added because earlier revisions called these
kernels, their lanes and their refusals short-lived).

**The model.** `yin.vm.semantic` is the canonical semantic VM;
it is register-shaped because a register-shaped canonical machine is
easier to lower than the old stack-shaped one. `debruijn/stack.cljc`
and `debruijn/register.cljc` are **backends** of it: they execute the
lowerings `A → H` (the stack image) and `A → R` (the register image) of
the derivations design §8.2-8.3, and they are not peers of the
canonical kernel. Frozen §10 phase 8 deletes the old `yin.vm.semantic`,
`yin.vm.debruijn-resolve`, `debruijn-linearize` and
`debruijn-register-compile` (and their tests after replacement
coverage), removes `:dim/lift-to` from both de Bruijn descriptors,
regenerates H and R, and switches the REPL, the linker and
`publish-closure!` to the A-derived lowerings; **it does not delete the
two kernels.** Every clause of this document that says otherwise is
corrected and marked.

**The fragment.** A backend supports a **documented fragment** of what
the carrier and the canonical kernel admit, behaves identically to the
reference on everything it accepts (laws 1, 4, 5 of §5.4 on the shared
fragment), and refuses before any attach, with a named kind and path,
what it cannot represent. The fragment is **not widened**: no seam is
planned for any of the five limits. The five documented limits are:

1. **Ambiguous images** (§4.1): two A of one H or R. Receiver refuses
   `:yin.k/unsupported :ambiguous-image` before attach; source refuses
   `:yin.k/non-portable :ambiguous-image` before serving.
2. **Closures and continuations in the free environment** (§6.3, R3):
   no finite canonical form through a single task-wide `free-env`.
   Receiver refuses `:yin.k/unsupported :free-env-value` with the path
   before attach; source refuses at lift.
3. **Primitives in the register kernel's payload** (§4.2, D4): the
   register backend refuses `:yin.k/unsupported
   :continuation-registers` with the path before attach; the stack
   backend holds them.
4. **Link and install rows** (§5.3, R2; matrix rows 7-12 and 17): a
   backend links a native image while module responses carry A, and §4
   defines no layout for install children. These rows are outside the
   native-lane matrix; the reference lane runs them.
5. **Retained FFI across a de Bruijn receiver** (§4.3, 6c Q2
   rejected; matrix row 4): the backend's re-park names its own call
   pair. Row 4 lifts from a de Bruijn source and is not lowered into a
   de Bruijn receiver (`unlowerable`).

Each limit is pinned by a refusal test (§8.3 items 12, 14, 15; §5.3)
and is a limit of the backends, never of the carrier or of the
canonical kernel. The owner-accepted native-lane matrix (61 law-4 cases
over three lanes, 350 law-5 transfers; 16 and 92 in the quick tier) is
the supported fragment measured, and its justification is this
section, not any shelf life (§11, 6c Q1).

**Cross-references.** Frozen design §4.3 and §10 phases 5, 6 and 8;
derivations design §8.2-8.3 (the lowerings and what phase 8 deletes);
UCF §7.4.2 (layouts); `yin.vm.ucf-transport-tuples.md` §10 item 6 (the
three repository lanes); §0.1 ruling 8, §5.3, §8.5, §9 and §11 here.

---

### 4.6 The retained backend fragment

**Amended after 7 (2026-10-14; names updated at the 2026-10-15 cutover)** — Reason: the owner retains both de Bruijn kernels as backends of the canonical semantic VM.
The stack and register kernels are the canonical kernel's two backends,
checked through the layouts on the shared fragment in §5.3. They remain
through and after phase 8; the fragment is not widened. It excludes link
and install rows 7-12 and 17, and row 4 is not lowered into either
backend while the retained-FFI seam remains absent.
**Amended after 7 (2026-10-14)** — Reason: the code's five pre-attach checks do not gate the whole harness fragment.
`lower-defect` checks, in order, image collision (`:ambiguous-image`),
install children (`:installs`), closures or continuations in the free
environment (`:free-env-value`, including nested collections), a retained
FFI request (`:retained-ffi`), and, for
the register backend, primitive functions in wait or parked-state
payloads (`:continuation-registers`). These refuse before attachment
on lower (§4.1-4.4 and §7.2). Of the excluded rows, retained FFI row 4 (limit 5)
is now refused before attach (`:yin.k/unsupported :retained-ffi`) alongside install
rows 10-12 (`:installs`); rows
7-9 and 17 are excluded by the harness only, not by a general
pre-attach fragment gate (§11 6c Q2). The harness's `link-rows` excludes
rows 7-12 and 17 from backend lanes; `unlowerable` excludes row 4 when
the receiver is a stack or register backend. The owner's
refuse-the-remainder requirement is not evidence of a general gate.
Stage E uses the canonical semantic kernel only. A is the only code identity;
native images are derived forms, and the source AST remains retrievable
through the linker's source-index, fetch and admission path (§6.1).

## 5. The safepoint harness

### 5.1 What it proves

**DECIDED** (frozen §10 phase 6 gate; UCF §7.11). For every row of the
expanded matrix, both delivery modes, nested and reified captures,
module contexts and every refusal: lift at the safepoint, lower into a
fresh machine, continue, and result and effect trace equal the
uninterrupted reference run; `lift(lower(frame)) = frame`; the frame's
reason is the observed wait's; on the three lanes: `:semantic`,
`:stack-through-layout`, `:register-through-layout`.

### 5.2 The matrix

**Expected results are stated for the bare expression.** Each row runs
under the four segment wrappers of the old harness
(`safepoint_harness_test.cljc:92-106`), whose expected results follow
mechanically from the bare result `r`: `:bare` → `r`; `:operand` →
`[:operand r]` (`(cat [:operand] r)`); `:depth-3` → `[3 [2 [1 r]]]`
(`nest` wraps `(cat [d] ((fn [x] …) d))` three times, as the old test's
pinned `[2 [1 [:two 2 "C"]]]` shows for depth 2, `:574`); `:captured` →
`[:captured :arg r]` (`(cat (cat (cat [] k) y) r)` with `k = :captured`,
`y = :arg`). **Amended after 6b (2026-10-13), one exception to the
mechanical rule (B2):** `nest`'s own lambda parameter is `x`, so under
`:depth-3` any row whose program captures the free name `x` captures the
wrapper's `x` instead. Row 21 is the one such row: its `(fn [] x)` is
created inside the innermost `nest` application where `x = 1`, so the
reference run itself answers `[3 [2 [1 1]]]`, not `[3 [2 [1 99]]]`, and
the harness pins `1` for that one case (`safepoint_harness_test.cljc`,
the `(and (= 21 n) (= :depth-3 label))` branch). The bare, `:operand`
and `:captured` results of row 21 keep `99`. The wrapper rule is
otherwise unchanged; the exception is stated rather than the wrapper
renamed, because the old harness's segments are shared and the lowered
run matches the reference either way (law 1 holds on the row). **Frames
the wrappers add.** The harness marks `:tail?
true` only where a row says so (`:228`, `:256`; `ast->semantic-bytecode`
defaults it to false, `vm.cljc:1089`; `mark-tail` is the expander's and
is not run here), so every `let1` and every `nest` application is a
non-tail call that pushes a return frame. `with-cat` (`:69-76`,
outermost in every segment, `:96`) adds **one** frame below everything
the row builds, with `stack-base 0` and an empty slice on the stack lane
(nothing is pushed before its application). On top of it `:bare` and
`:operand` add none of their own (the row is the operand), `:depth-3`
adds three (`nest`'s applications, each with two values, `cat` and
`[d]`, pushed under it), and `:captured` adds three (`let1 k`, `let1 f`,
`(f :arg)`). A row's own `let1`s add theirs. Row 20 alone runs outside
the wrappers.

**Amended after 6c (2026-10-13), row 21's expectation on every lane
(codex D5, accepted verbatim by R6).** Under :depth-3, the closure
captures the innermost wrapper binding x = 1; the task's free-env
retains x = 99. The blocked read's E therefore binds x to 1, and all
three lanes agree on that lexical result. The bare row tests the free
binding 99. This wrapper-induced lexical capture is intentional and is
pinned separately from free-environment preservation. *What the code
does:* `layout-test/env-reconstruction`'s row-21 clause lifts the bare
row on all three lanes (`free-env-world`) and asserts `(get-in p [:env
'x])` is `1` and the machine's `:free-env` is `{x 99}`; its matrix clause
compares every `E` of every lane case (61 cases plus the three B2 env
programs, times two de Bruijn lanes: 128 comparisons) with the reference
lane's. *Why:* B2 was carried from 6b and the cross-engine lanes could
have hidden a lane that agreed on the free binding but not the lexical
one; no production change.

Programs use `v2_support`'s AST helpers (`v2_support.cljc:56-104`:
`lit v lam app def! then cursor-of next-of let1`) and the four segments
of the old harness (`safepoint_harness_test.cljc:92-106`: `:bare`,
`:operand`, `:depth-3`, `:captured`); `cat` is the closure `(fn [p q]
(conj p q))` bound around every body (`:69-81`). "Expected" is the
reference run's normalised result; where a row says "executable" it runs
on all three lanes; "synthetic" means the frame is built by hand and
judged by `frame/defect` only, because no program reaches it on the
landed or stage-2 evaluator.

| # | Row | Program (inside each segment) | How parked / satisfied | State, pending | Expected after satisfy | Executable? |
|---|---|---|---|---|---|---|
| 1 | blocked read | `(let1 s (stream/make 4) (let1 c (cursor-of s) (let1 a (next-of c) (cat (cat [] a) (next-of c)))))`, `"A"` appended before the run | append `"B"` | act, `:next` | `["A" "B"]` | yes |
| 2 | blocked write | `(stream-put w "v")` on a one-slot stream warmed with `:warmed` | take one | act, `:put` with retained `"v"` | `"v"` landed | yes |
| 3 | FFI sent | `(dao.stream.apply/call :op/echo "hello")` | append the success response | act, `:ffi` | `"echo!"` | 4b stage 2 |
| 4 | FFI retained | as 3 with a one-slot, warmed call-in | free the slot, then answer | act, `:ffi-request` | `"echo!"` | 4b stage 2 |
| 5 | effectful call, non-tail, stream | `(cat [:ordinary] (stream/next! c))` | append `"B"` | act, `:next` | `[:ordinary "B"]` | yes (stage 1 has streams; `stream/next!` is the stream module's) |
| 6 | **effectful call, tail, stream** | `((fn [u] (stream/next! c)) 0)` with the inner call `:tail? true` | append `"B"` | **tail**, `:next` | `"B"` delivered through `K` | yes |
| 7 | link request phase | `(require 'host.mod)` parked **before** the request append succeeds (one-slot, warmed request stream) | free the slot, then answer `:ok` | act, `:link-request` | `'host.mod` | 4b stage 2 |
| 8 | link response phase | `(require 'host.mod)` polling (request appended) | append the `:ok` response | act, `:link-response` | `'host.mod` | 4b stage 2 |
| 9 | **link, tail** | `((fn [u] (require 'host.mod)) 0)` tail | as 8 | **tail**, `:link-response` | `'host.mod` through `K` | 4b stage 2 |
| 10 | install, blocked child (**amended after 6b**) | `(require 'host.mod)` where `host.mod`'s body reads a stream the test has not fed, so the child is `:blocked` and the parent's wait is `:install` | feed the child's stream | act, `:install` + install entry, child kind `:blocked`, **phase `:parked`** | `'host.mod` | yes |
| 11 | install at a running child's fuel boundary | row 10's child is fed and advanced by `run-fuel 1`; its active control is carried | as 10 | act, `:install`, child kind `:running`, phase `:running` | `'host.mod` | the running child is obtained by execution |
| 12 | install, tail | `((fn [u] (require 'host.mod)) 0)` tail with 10's child | as 10 | **tail**, `:install` | `'host.mod` through `K` | 4b stage 2 |
| 13 | explicit park | `(vm/park)` | `(vm/resume pid "back")` beside | parked record, root `:parked` | `"back"` | yes |
| 14 | reified capture live in a window | `(let1 k (vm/current-continuation) (cat (cat [] k) (next-of c)))` | append `"B"` | act, `:next`; `k` is a `:reified` value in `W` | `[:continuation "B"]` normalised | yes |
| 15 | capture invoked after lower | `(let1 k (vm/current-continuation) (if (= k :second) :done (let1 r (next-of c) (k :second))))`: on the first pass `k` is a continuation, so the test fails and the read runs and parks; after satisfaction `(k :second)` delivers `:second` into the capture's `rd` (the `let1` operand) and re-enters at the capture's successor pc, so the body is entered again with `k = :second` and the test succeeds **without repeating the read** (the invocation is abortive on every lane: `:continuation` operator kind, `srvm-phase4b:semantic_register.cljc:212-217` stage 2, `stack.cljc:760-763`). `if` is `{:type :if :test :consequent :alternate}` (`vm.cljc:1069`), a helper the new harness file adds; `=` is the primitive (`vm.cljc:387`), consumed before the read | append `"x"` after lower | act, `:next`; `k` is a `:reified` value in `E` (the closure's) | `:done` | yes |
| 16 | nested capture | `(let1 k1 (vm/current-continuation) (let1 k2 (vm/current-continuation) (cat (cat (cat [] k1) k2) (next-of c))))` | append `"B"` | act; `k2`'s act window holds `k1` (a `:reified` inside a `:reified`'s window) | `[:continuation :continuation "B"]` | yes |
| 17 | module context, blocked inside a module closure | `host.mod` exports `f = (fn [c] (stream/next! c))`; root: `(f c)` after `(require 'host.mod)` | append `"B"` | act, `:next`, `E` carries `store-of host.mod` | `"B"` | yes; **amended after 6b:** the receiver composition is given the source's registry entry for `host.mod`, a composition prerequisite (§2.5): linked registry entries do not travel |
| 18 | module-store snapshot | `parked-module-store-cursors` (`lift_support.cljc:310-333`): an explicit park whose module store holds two cursors named by a module closure | resume | parked record; `:module-store` row | the two cursor cells | yes; **amended after 6b:** the fixture's module store is hand-built and keyed by the **symbol** `host.mod`, as `frame_test` does, while the kernel keys installed stores by module address; row 17 exercises a real installation |
| 19 | halt | `(lit 42)` | — | result | `42` | yes |
| 20 | **tail-effect halt** | run **outside the wrappers**, in two batches on one machine: a setup batch `(then (def! 's (stream/make 4)) (def! 'c (cursor-of s)))` run to halt (store writes persist across batches, as `lift_support.cljc:320-322` relies on), then the batch `(stream/next! c)` **marked `:tail? true` at the root**, `c` resolving through the store; no application precedes the call, so `K` is empty (any `let1` or wrapper would push a frame, §5.2 above) | append `"B"` | tail, `:next` | halts with `"B"` (4b: a tail effect with no frame to pop halts, `srvm-phase4b:semantic_register.cljc:205-211` stage 2; stack: the seam's empty-`K` case, §4.3), env free of `store-of` | yes |
| 21 | **free-env shadowing** (the review's row) | initial free env `{x 99}`; `(let1 f (fn [] x) ((fn [x] (f (next-of c))) 1))` | append `"B"` | act, `:next`; `E` at the read binds `x` to 1; root `free-env` carries `x 99` | the lowered `f` returns `99` (not nil, not 1) on every lane; **the one row the earlier design would have failed** | yes |
| 22 | nil-valued live register | `(let1 pair (fn [p q] (cat (cat [] p) q)) (pair nil (next-of c)))`: the `nil` operand is evaluated **before** the read and is live across it (`(cat nil (next-of c))` would give `(conj nil "B")`, the list `("B")`, so a vector-building `pair` is used; there is no `vector` primitive, `vm.cljc:380-410`, and `pair` builds through the segment's `cat`) | append `"B"` | act, `:next`; `W` has a present key with `[:lit nil]` | `[nil "B"]` | yes |
| 23 | tail FFI bookkeeping | a `tail` parked record under a call id `P ∈ :ffi-call-ids` | — | parked, context `:parked`, bookkeeping clause of P4 | validator accepts; the **same valid tail under a key not in `:ffi-call-ids`** is `:parked-site` (P4); moving `site` to a non-tail call is `:site` (T1, which runs first) | **synthetic**: no primitive returns an FFI effect through a tail `:call` today (evaluator §1.6) |
| 24 | `act` FFI bookkeeping | 3's parked bookkeeping record under its call id | — | parked, P4 bookkeeping | validator accepts | 4b stage 2 |

**Amended after 6b (2026-10-13), rows 10-12 and the install contract
(B1).** The earlier text promised a child "`:blocked`, phase `:running`"
(row 10) and a child "executing `:park`, kind `:parked`" (row 11).
The running child is an engine state at a fuel boundary; the parked child is blocked at a parking boundary. `advance-install`
(`engine.cljc:1421-1454`) runs the child inside the sweep and then
records it: a **blocked** child is retained at phase **`:parked`**
(`:1444-1446`); a **halted** child goes straight to `link-install` in the
same sweep (`:1447-1452`), so it is never retained; and a child that
executes `:park` is `:halted?` with a parked value, so it too goes to
`link-install`, which completes the install if the exports are already
defined and otherwise refuses it `:export-missing`. **Amended 2026-10-16 (any-pc, owner invariant 2026-10-17).** A budgeted sweep can retain a child with active control at phase `:running`. Row 11 now obtains that state by real execution. The lower requires a `:running` child at phase `:running` and a `:blocked` child at phase `:parked`. Row 12 is row 10's child under tail delivery. No row promises an executable kind-`:parked` install child on
the current engine (owner question §11 Q1). **The install validation
the lower enforces** (`check-install!`, before any attachment, §7.2):
the phase is one of `#{:running :parked}` (`:install-phase`); the
retained response is a map with `:status :ok`, a `:manifest` map whose
`:yin.module/name` equals the install's module name and whose
`:yin.module/exports` is a collection of symbols, and an `:image` map
whose `:value` the receiving kernel's loader admits (`:install-response`);
and the carried child task is **`:blocked`** at wire phase `:parked`, or **`:running`** at wire phase `:running` (`:install-child`); a halted child is linked in the sweep it halts in. On lift, `install-node` refuses a phase outside the set as
`:yin.k/non-portable :incomplete-install` and a response CBOR cannot
encode as `:yin.k/non-portable :install-response`.

Historical phase-6 refusal rows (superseded by the completed carrier): `:yin.k/not-quiescent` (`a-nonempty-ready-queue-is-not-quiescent`,
`:591-603`); `:yin.k/not-at-safepoint` (a register kernel stepped one
instruction past a `:const`, asked to lift — from 6e a stepped kernel
lifts as a `run` state and only a pc outside every body refuses,
§4.5); `:yin.k/non-portable
:reason-mismatch` (the `:observe` hold, `:675-692`); and the validator's
**membership tampers**, each a row-set edit of a valid body before
lower: a window with an extra live key → `:window-extra`; a live key
removed → `:window-missing`; `rd` added → `:window-self`; `rd` replaced
by another register → `:destination`; a return frame's `pc` moved after
a tail call → `:return-site`; a reason the site does not admit →
`:reason-mismatch`; a `:tail` row given a fourth slot → **`:row-shape`
at the codec's structure step (2), not `:frame-shape`** (amended after
6b, B3: the row table fixes `:tail` at three slots, so the wire never
reaches rule S1 with a fourth slot; S1 still governs an abstract frame
handed to `frame/defect` directly; and because `emit`/`emit-numbered`
silently drop extra slots from an abstract node, mapping over the
shorter descriptor list, this tamper must be a **raw row edit with a
rehash**, never an abstract-task edit);
the `[:lit nil]` of row 22 dropped → `:window-missing`; a `:reified`
act whose `pc−1` is not a capture → `:captured-site`; a parked act whose
`pc−1` is not `:park` → `:parked-site`; for E2, with row 21's initial
free environment extended to `{x 99 y 7}`, the **unshadowed** free
binding `y` in the act's `E` changed to `8` → `:free-env-mismatch`
(changing the lexical `x`, which is `1` at the read, trips nothing: E2
compares off the lexical names), and the lexical `x` **removed** from
`E` → `:free-env-mismatch` (presence clause (a)); the root `free-env`
row given a reserved key → `:reserved-name` (X1). **A changed live value**
(e.g. `"A"` → `"Z"` in row 1's window) is not a membership defect and is
not expected to be refused: it is tested by **execution parity** (the
lowered run's result differs from the reference), as the review ruled.

### 5.3 Lanes

The `:semantic` lane's wait entries are canonical frames plus
engine fields. The de Bruijn lanes load `(:image (derive/stack-image v))`
and `(:image (derive/register-image v))` of `v = (:vector (project P))`
through `dvm/create-vm`/`rvm/create-vm`, run to the safepoint and lift
through `layout/lift-act`/`lift-tail`; the stack lane's tail rows (6,
9, 12, 20) require the seam of §4.3 and are gated on 6c-pre.

**Amended after 6c (2026-10-13), the shared fragment (R2; count
amendment ACCEPTED by the owner on 2026-10-13, §11 6c Q1).** The de
Bruijn lanes execute the shared fragment of the matrix: rows 1-6, 13-16,
18-19, 21-22 and 24 under the four wrappers, and row 20. Rows 7-12 and
17 are not built on them: a de Bruijn kernel links a native image while
module responses carry A, and §4 defines no layout for install children.
Row 4 is lifted from a de Bruijn source but not lowered into a de Bruijn
receiver until the retained-FFI seam (§4, 6c-post-D2) lands: the
kernel's re-park names its own call pair. **Amended after 7 (2026-10-14)** — Reason: the owner retains the de Bruijn kernels as backends after phase 8.
These lanes check the canonical frame on the documented backend fragment
(§4.6); they remain after phase 8 and are not widened. The canonical
lane runs the whole matrix. *What the code does:* the
harness's `*lane*` (`:semantic`, `:stack`, `:register`) selects
the kernel `machine` builds; `load-program` on a de Bruijn lane attaches
`v`'s derived image beside what the kernel holds and starts it at the
image's offset (`kernel-load`), so parked records keep naming their
rows; `lane-lift` is `rows/lift-task` on the reference lane and
`layout/lift-task` over the world's `kernel-space` otherwise;
`lane-lower` is `rows/lower-task` or `layout/lower-task` with a fresh
scratch machine. `link-rows` is `#{7 8 9 10 11 12 17}`, `lane-cases`
filters `matrix-cases` by it (61 cases), `unlowerable` is `#{[4 :stack]
[4 :register]}`. Every world's layout lookups run inside `scoped`: a
`binding`-scoped atom `*projected*` records the vectors that world
projected and `kernel-space` reads that set, so an alpha-equivalent A an
unrelated fixture projected widens no lookup; `kernel-space` throws when
unbound, and only `file-wide` reads the whole file's `code-index`, in
the one fixture that shows the contamination (§8.3 item 14). *Why:*
link rows need the harness to answer a `require` in
the kernel's own format (H or R) while module responses carry A, install
children are whole kernels the layout would lift and lower recursively,
and the retained-FFI re-park is a kernel edit (§4.3); 2-3 days of
harness and layout code was excluded by the fragment ruling (R2,
updated by §4.6). The sentence above on `lift-act`/`lift-tail` is superseded by
§4.1: the lift is `lift-state`, dispatching on the kernel's kind.

### 5.4 Laws

1. **Reference parity**: result and effect trace of the lowered,
   satisfied machine equal the reference run (`reference`/`handed-off`,
   `:368-401`).
2. **Round trip**: `lift(lower(S)) = S` by root id under the
   fresh-receiver context of the draft's §9, after the re-emission of
   §5.5.
3. **Observed reason**: the pending's reason is the wait's, never the
   static kind (ucf-revisions §8).
4. **Cross-engine canonical equality**: one program at one safepoint
   lifts to row sets whose root ids are **equal after the re-emission of
   §5.5** on all three lanes.
5. **Cross-engine lowering**: a row set lifted on one lane lowers on
   each of the other two (six ordered pairs) and continues to the
   reference result.
6. **Validator agreement**: every lifted frame passes `frame/defect` in
   its context; every tamper of §5.2 is refused with its kind; the
   verdict is lane-independent.

### 5.5 Re-emission for equality (replacing "stream-blinding")

**PROPOSED** (reviewer: `stream-blind` rewrites decoded maps and does
not recompute Merkle ids, so it cannot establish root-id equality). The
harness first **fixes identities where it can**: all three lanes serve
their streams through one toy channel (`v2_support/toy`,
`served-peer`), so stream identities and descriptors are equal across
lanes by construction where the same logical stream is lifted. Where a
lane mints its own (fresh cursor cells on relift, the receiver's own call
pair), the comparison uses `rows/canonical-under`, a **test-only**
transformation whose placeholder assignment is **never derived from
*host* traversal order** (the canonical walk of draft §6 is a function
of the state and is what the procedure reads; the review's objection: map and set traversal, and
"cursor cells by body", can depend on the very identities being erased). It
takes an **explicit logical-stream correspondence** from the fixture:
the harness created every stream the row reaches, so it supplies
`{lane-identity → logical-name}` (e.g. `{id-of-s :s, id-of-call-out
:call-out}`) for each lane; `canonical-under` decodes the row set to the
abstract task (draft §6's input), replaces each stream marker's identity
and descriptor by the logical name the map gives it (a marker the map
does not cover is a test failure, never a guess), runs the canonical
cursor-cell numbering (draft §6), re-emits every row and recomputes every id,
and compares root ids. Draft §6 is invariant under renaming of the
**cursor cells** (its labels never encode an incoming cursor-cell id), and the only
other identity it consumes is the stream marker in each cursor cell's body
label (`[:body [:stream identity descriptor] position]`, draft §6 as
amended, spelled by rank inside keys) and in cursor-cell bodies; after the substitution those are the fixture's
logical names, so the procedure's inputs no longer depend on any lane's
identities. Guest sets and maps are entered in the bytewise order of
their members' keys, and `:store`, `:bindings` and the parked table in
the byte order of their structural keys (draft §6 W1), never in host
enumeration order, so
a permutation of the erased identities cannot change the result once
the markers are substituted. It is never used outside the harness.

**Amended after 6c (2026-10-13), positions carry identities and what
`canonical-under` may erase (codex D1, accepted verbatim by R4;
replacing the claim above that after marker substitution the
procedure's inputs no longer depend on any lane's identities).** A
cursor position may contain a stream identity. The fixture supplies an
explicit, injective logical-stream correspondence and a
transport-specific position renaming operation. That operation replaces
only identity-bearing fields, preserves every nonidentity field
including offsets, and rejects missing coverage or disagreement with the
cursor body's stream. Stream descriptors must be shown equivalent under
the same correspondence before comparison; replacing a whole descriptor
with a logical name alone does not prove equivalence. After these
checks, canonical-under re-emits and renumbers the task. The harness
includes negative controls proving that a changed position offset,
descriptor nonidentity field, saved value or lexical value changes the
compared state, and asserts exactly the three distinct kernel lanes for
every law-4 case. *What the code does:* `canonical-under` erases exactly
one thing, a stream marker's identity and descriptor, replaced by the
logical name (`rows.cljc:971-974`); a `dao.stream.ringbuffer` cursor
position embeds its stream's identity, which that erasure does not
reach. The harness's `logical-positions` rewrites only
`:dao.stream.ringbuffer/identity` inside each `:cursor-cell-body`'s
position under `ring-names` (resource key per stream identity) and keeps
every other field, the offset included; `logical-names` (served identity
to resource key) and `ring-names` both pass through `injective`, which
throws `{:law-4 :injective}` when two identities share a name;
`task-root` asserts that no lane identity survives in the emitted rows;
`logical-descriptors` renames the identities inside each stream's
`stream/descriptor` under the same correspondence and
`canonical-equality` throws `{:law-4 :descriptors}` unless the three
lanes' maps are equal; `canonical-equality` asserts `(= 3 (count
roots))` and `(= [:semantic :stack :register] (mapv :lane
roots))` for every case. `harness/law-4-controls` (quick tier, row 1
bare) pins: a cursor position's offset incremented on one lane changes
the root; a stream only the register lane holds fails with
`:descriptors`; two served identities under one logical name fail with
`:injective`; a saved window value and a lexical value replaced on one
lane each change the root. *Why:* codex M11 (compare one lane) and M12
(replace every position by a constant) survived the suite, and probe 4
showed a descriptor field added on one lane was invisible to
`canonical-under`; the erasure is design-sanctioned and stays, and what
it cannot prove the harness proves separately (R4).

### 5.6 Static checks the harness runs once per corpus segment

1. `frame/defect` on every frame the reference lane mints: nil.
2. For every **reachable** parking pc `p` of `v`: `(analysis/saved a
   (inc p) rd)` has no member `≥ k(body(p))` and does not contain `rd`.
3. Register layout: at every reachable boundary pc `p`, `virt(p, s)` is
   single-valued on the physical image of `live-out*(p)`; the `live`
   operand of `R[p]` equals `(sort (map phys (disj live-out*(p) rd)))`,
   the destination removed as a **virtual** id before mapping, which is
   `[]` for a tail call (phase-5 §2.5). Worked example, inner call at
   `p = 3`: `L(4) = {1 2}`, minus `rd 2` → `{1}` → `[0]`, the pinned
   `live` of phase-5 §6.3 row 2.
4. Stack layout: the k-th non-push instruction of `H` has the mnemonic
   family of `v[k]` for every `k`; counts agree.
5. Stack layout: `(set (stack-layout p)) = (analysis/saved a (inc p)
   rd)` for every reachable **non-tail** parking pc `p`.
6. `E` reconstruction: at every safepoint reached, the layout's `E`
   equals the reference lane's `E` (values normalised), including rows
   21 and 22 and the `:duplicate-param`, `:nested-closure`,
   `:nested-lambdas` programs.
7. Closure reconstruction: canonical ↔ positional closures round-trip on
   the `"closure value"` and `"higher order"` B0 rows and
   `:duplicate-param`.
8. Tail lowering: the `:frames`/`:store-of` chosen for a lowered `tail`
   entry are unobservable: lowering with `[]`/`nil` and with arbitrary
   values gives equal observables on rows 6, 9, 12, 20. **Amended after
   6c (2026-10-13)**, R2: rows 6 and 20 on the native lanes (four cases,
   `layout-test/tail-lowering-is-unobservable`); rows 9 and 12 are link
   rows and run on the reference lane only (§5.3).
9. Slices: for every non-tail safepoint with `m` return frames, the
   `m+1` slices of §4.3 partition the stack kernel's operand stack
   exactly, and each has the length of its `stack-layout`.

---

Check ordered region length and contents against `layout(p) ++ uses-in-order(p)`, with repeated-register agreement and the specified dead-prefix treatment. Check the non-tail and tail set equations separately. Ordered multiplicity is not the cardinality of `L(p)`. Retain the parked-window check.

### 5.7 The stepping driver

**Amended 2026-10-16 (any-pc, owner invariant 2026-10-17)**

**Order per point** (reference lane, 6d-a; backends, 6e): at boundary `S_i` of the stepping source: (1) **lift**; (2) **lower** into a fresh receiver of an **isolated world** (§ below); (3) **immediate relift** of the receiver and **law 2** (`canonical-under` root equality with the lift; no execution in between); (4) **law 6** on every lifted frame (plus the two window tampers); (5) **run the receiver to the end** in its world, **law 1** (result and effect trace equal the reference's, after `normalize`'s fresh-identity correspondence); (6) negative controls kept: cursor positions and descriptors, windows and lexical values tampered are refused or differ as today.

**Isolated worlds**: each point builds the receiver's composition and streams fresh from the case's **deterministic script** (the same scripted inputs the reference run saw, fed to the receiver's own streams); the stepping source's world is never shared with a receiver; fresh identities normalise through the existing correspondence (`normalize`, `:238`). The reference result and trace are computed once per case on a pristine world.

**Coverage.**

The stepping driver uses returned `run-fuel 1` boundaries. Its coverage classes are body entry, taken and untaken branch, jump, non-tail call, return, tail transition, capture, resume, park, FFI park, active-plus-waits, active-plus-ready, and running install child.

Blocked-plus-ready is tested through an explicit **post-sweep/pre-resume fixture**, constructed with `check-wait-set`. It is not a mandatory returned boundary of the stepping driver. The shared-cursor fixture uses this same seam.

Report driver coverage separately from constructed scheduler-product coverage. Pin per-case/per-host counts after measurement. Neither coverage inventory claims a theorem over all programs.

**Refusal oracle.**

The independent test traversal covers:

- Run windows and lexical bindings.
- Every K-frame window and lexical binding.
- Outstanding wait delivery states.
- Ready delivery states and ready outcome values.
- Optional ready refusal values and restored value-bearing completion fields.
- Parked states and recursively reached collections, closures and continuations.

For register targets, exactly the expected unsupported points refuse `:yin.k/unsupported :continuation-registers` before attachment. Every other point in the admitted backend fragment transfers and continues to the reference outcome. Include a case whose only unsupported primitive is nested in a queued outcome.

Terminal outcomes are compared as outcomes: receiver and source must raise the same classified failure with the same portable diagnostic data after identity normalization, rather than both being required to return a guest value.


## 6. Code and task context in the carrier

### 6.1 Code

**DECIDED** (rulings 1, 3, 4; phase-5 §7.6, §9.2; v2 amendment §4's
2026-10-10 note). The root's `code` slot is plain `{A canonical-vector}`
of `"v4"` vectors, verified to hash to their key and to pass
`code/well-formed?`. **No native image travels**: a stack or register
receiver derives `H(A)`/`R(A)` with `derive/stack-image`/`register-image`.
`requires` carries UCF §7.6.1's map **plus** `:yin.k/trees {A #{tree-address …}}`,
one entry per address in `:yin.k/segments`; the **complete association
rule** is phase-5's: an absent entry **or an empty set** is incomplete
(`:yin.k/missing :yin.k/trees`, discovery `:incomplete`), and a claimed
address alone does not establish retrievability (a known address whose
read fails now is the medium's `:absent`, not a missing association).

**Amended after 6b (2026-10-13), where the association comes from.**
The kernel holds no source index, so the tree association is a **lift
input**: `(rows/lift-task m serve! {:source-index {A #{tree-address …}}})`
from the composition (the harness records `A → segment-key [:yin/tree
ast]` whenever it projects a vector). After the walk has fixed the
carried segments, the lift refuses `:yin.k/unsatisfied` (`:yin.k/discovery
:incomplete`, `:yin.k/missing {:yin.k/trees #{A …}}`) for any carried
segment whose entry is absent or empty; the lower repeats the same check
over `requires`' `:yin.k/trees` against `code`'s keys
(`check-dependencies!`). No source-index slot is persisted in the
carrier. *Why:* engineer deviation 1, accepted by codex: the index
belongs to the composition, not the machine.

### 6.2 Rows, not datoms

**DECIDED** (ruling 4). No vector is projected to datoms; the direct
load path is the only path (§10.4).

### 6.3 The free environment: carrier, source, ownership

**DECIDED** by the review's finding and §4.4. The task's initial free
environment travels **once, explicitly**, as the root's `free-env`
slot, a `:bindings` row (`[S V …]` sorted). Every `E` in the task is a
complete map and is checked by rules X1 and E2. On lower, the
semantic kernel receives it as the machine's base `:env`
(`create-vm`'s `:env` option, `semantic_register.cljc:261`); a de Bruijn
kernel receives it as its `:free-env` (`stack.cljc:418`) and
reconstructs positional frames from each `E` by the chain (§4.4).

**Where the lift gets it (PROPOSED, the gap the review named).** It is
**never recovered from a current `E`** (§4.4). Its source is, per
kernel:

- **de Bruijn kernels**: the kernel's own `:free-env` field, "fixed for
  this instance" (`stack.cljc:82`; `register.cljc:50`), read directly.
- **the semantic kernel**: the evaluator keeps only the
  mutable-current `:env` (`RegisterVM` field `env`, "persistent lexical
  scope map", `srvm-phase4b:semantic_register.cljc:23`; the old semantic
  VM likewise captures the initial env only transiently in `eval`,
  `semantic.cljc:874-876`), so no field holds the initial map. The
  **composition owns it**: it is the value the composition passed as
  `create-vm`'s `:env` (`vm/check-bindings!` of it, `:261`). The lift's
  input contract therefore takes it explicitly,
  `(rows/lift-task vm serve! {:free-env E₀})`, and refuses
  `:yin.k/non-portable :missing-free-env` when the caller supplies none;
  the holder and REPL paths that call the lift pass the environment they
  constructed the task with. **Coordinated retention seam, recommended**:
  4b (stage 3, its track's file) adds an immutable `free-env` field set
  once in `create-vm` from the checked `:env` and never written by any
  transition, with `gc-roots` listing it; once it lands the lift reads
  it when `:free-env` is not supplied and the explicit argument stays as
  the override for compositions that constructed the task elsewhere.
  Phase 6 does **not** edit `semantic_register.cljc`; 6b depends on the
  explicit argument and names the seam as a follow-up (§8.2).

  **Amended after 6b (2026-10-13): the seam has landed and the lift reads
  it.** The 4b track added the immutable `free-env` field
  (`semantic_register.cljc:24`, set once in `create-vm` `:394`, a gc root
  `:489`). `lift-task*` takes `(:free-env opts)` when supplied, else the
  kernel's `(:free-env m)`, and refuses `:yin.k/non-portable
  :missing-free-env` only when neither exists. The explicit value
  **overrides** the field without comparing against it; an override the
  carried `E`s disagree with is caught by E2 when the lift validates its
  own frames (`:yin.k/non-portable :inconsistent-state`, e.g. `{x 5}`
  against an `E` holding `x 99` off the lexical names). Install children
  are lifted with the `:free-env` option removed, so a child always reads
  its own kernel field.

Merkle sharing means every `E` whose bindings equal another's shares one
`:bindings` row; the root slot is one more reference to a row that
usually already exists.

**Amended after 6c (2026-10-13), closures and continuations in the free
environment on the native lanes (R3).** A de Bruijn kernel resolves free
names through its single `free-env` while a canonical closure snapshots
its `env`; a closure or continuation held in the free environment
therefore has no finite canonical form on a de Bruijn kernel.
**Amended after 7 (2026-10-14)** — Reason: the retained backends keep the free-environment refusal.
A de Bruijn receiver (retained after phase 8, §4.6) refuses such a value
in the carried `free-env` before attach (`:yin.k/unsupported :free-env-value`, with the
path), and a de Bruijn source refuses it at lift before serving. The
carrier and the semantic kernel admit it. (**Amended
2026-10-13 (backends)**: was "until phase 8"; this is a documented
limit of the backends' fragment, kept and not widened, §4.6.) *What the
code does:*
`lower-defect`'s third clause (`free-env-procedure`) walks the root's
`free-env` bindings row in its sorted order and the `:vec`, `:list`,
`:set` and `:map` rows under each value, and refuses the first
`:closure` or `:reified` row it reaches with `:yin.k/path [:free-env sym
…]` (indices appended through collections, e.g. `[:free-env box 1 0]`);
`canonical-task` refuses `:yin.k/non-portable :free-env-value
{:yin.k/key sym}` for the first key, in `str` order, whose value is or
holds a closure or continuation (`holds-procedure?`), before any stream
is served; every other free-environment value, primitives, data and
cursor references included, is copied as it is in both directions
(`walk-value` converts only closures and continuations). Tested by
`layout-test/free-env-values` (codex probe 2 as a fixture: the reference
lane answers `"B"`, `handed-off` on the reference lane answers `"B"`,
both native receivers refuse with path `[:free-env f]` and zero
attaches, both native sources refuse with key `f` and zero serves) and
`layout-test/free-env-nested-values` (a closure and a continuation one
vector or map deep under `box` refuse both ways with the nested path or
the key `box`; a primitive and an authentic cursor reference under the
same nesting cross both ways and are used through the collection).
*Why:* codex F1 showed a carried canonical closure in `free-env` left in
canonical shape in the receiving kernel and a NullPointerException on
invocation; conversion on lower would be sound only under an E2-like
agreement condition the validator does not check (E2 checks frame
environments, never a closure value's `env`), and conversion on lift is
undefined because the canonical closure would contain itself through
the shared free environment; refuse, do not convert (R3; §11 6c Q3).

---

## 7. Lift and lower

### 7.1 Lift

**DECIDED** (draft §9, UCF §7.8): carry ordered completed outcomes; refuse the
holds (`unliftable-hold`, `handoff.cljc:588`) as `:yin.k/non-portable
:reason-mismatch`; classify kind; run the **dependency closure over the
`"v4"` vectors**, which needs the phase-5 scanners
(`scan/requirements`, `scan/free-names`, `scan/free-occurrences` over the
§2.3 table: the current `completion/code-facts` reads stack-table
positions and cannot be used), and refuse `:yin.k/unsatisfied` unless
`:complete`, `:yin.k/trees` included; number cursor cells (draft §6); walk
waits, parked records, the store slice, module stores, the free
environment (from the kernel's `:free-env` field or the lift's explicit
`:free-env` argument, §6.3, never from a current `E`), the result,
install children; run `frame/defect` over every
frame; emit rows and the census. A de Bruijn source walks the frames
`layout/lift-act`/`lift-tail` reconstruct.

**Amended after 6c (2026-10-13), the lift from a de Bruijn kernel as
`layout/lift-task` runs it (engineer deviation 2, codex: acceptable).**
A de Bruijn source is not walked by a second lift: `layout/lift-task k
space template serve! opts` is `(rows/lift-task (canonical-task k space
template) serve! opts)`, where `canonical-task` presents the quiescent
kernel as a semantic machine built on `template` (a fresh
machine of the kernel's composition), so the ten steps below run
unchanged over it. `canonical-task`, in order: refuses
`:yin.k/not-at-safepoint` when the kernel has an active continuation;
refuses `:yin.k/unsupported :installs` when it holds install children;
refuses `:yin.k/non-portable :free-env-value` for a closure or
continuation in its `:free-env` (§6.3); then, under a lift context
(`:kind` by `kernel-kind`, the kernel, the space, the kernel's
`:free-env`, the template's owner), sets `:code` to `{A {:vector :address
:analysis}}` over the space's segments with identity aliases, converts
the store, the module stores and the value through `canonical-value`,
each parked record through `lift-state` (typed `:parked-continuation`
with its id) and each wait entry through `lift-state`, and carries
`:resources`, `:capability-secret`, `:free-env` (also as `:env`),
`:ready-queue`, `:id-counter`, `:halted?` and `:blocked?`. Any
`:yin.k/non-portable :ambiguous-image` or `:unknown-image` of
`image-segment` (§4.1), `:not-at-safepoint` of `safepoint!`,
`:register-layout` of `virt` (§4.2), `:stack-layout`, `:stack-base`,
`:frame-depth`, `:closure-origin` or `:closure-binders` is raised here,
before `rows/lift-task` serves anything; the tests that pin
`:ambiguous-image`, `:free-env-value` and `:not-at-safepoint` assert
zero serves. *Why:* one carrier, one lift; the layout only
reconstructs.

**Amended after 6b (2026-10-13): the order as `lift-task*` runs it (B4),
and the discharge rule.** The paragraph above put the dependency closure
before the numbering and the walk, but the carried segments, contexts
and module stores are known only once the walk has found them, so the
closure cannot run first.

**Amended after 7 (2026-10-14)** — Reason: `lift-task` now takes `:header` and runs custody checks after the walk.
The header is nil for a fork; otherwise it contains occurrence,
arbitration, next-op-seq, optional origin and the local `:yin.k/enrolled`
set. That set never enters the pack. The linker/composition supplies
`:source-index` as `{A #{tree-address}}`; code and trees use its existing
fetch and admission path, with A the only code identity and the AST
retained. The holder retains serve answers by `[task-path resource-id]`;
encode uses those answers without serving externally.

Before entering handles in the external serving table, `lift-task*` runs with temporary plain-data identities to check portability, frames and conservative discharge. This preflight does not call the composition's `serve!` or `:serve-keyed`. A discharge refusal therefore serves zero handles. `discharge!` and its edge-reachability conservatism are unchanged.

The merged order is:

**Amended 2026-10-16 (any-pc, owner invariant 2026-10-17)**

1. **Holds.** Refuse `:yin.k/non-portable :reason-mismatch` on an observe wait, held entry, unminted cursor, pending close or link request without its cursor. These are driver-owned incomplete transitions. Their completion can depend on external acknowledgement or backpressure; there is no one-cycle resolution guarantee. There is no ready-queue quiescence refusal: completed outcomes travel in order.
2. **Kind.** `:running` when `:control` is set; else `:parked` when the value is a parked continuation the parked table holds; else `:blocked` when the wait set or the ready queue is non-empty; else `:halted` with empty wait set and queue; anything else is `:yin.k/non-portable :inconsistent-state` (unreachable by construction).
3. **Free environment** (§6.3): the option, else the kernel field, else
   `:missing-free-env`.
4. **The walk.** The active run state with its window trimmed to `L(pc)`, waits (state and pending), ordered ready outcomes, the parked table, the whole
   store, the result (halted only), install children (each a recursive
   `lift-task*` in the child role with its own accumulator), then the
   module stores by reachability. Every value is classified `[:lit x]`,
   `[:row n]` or `[:cursor-cell source]`; references are authenticated
   against the kernel's resource table before encoding
   (`:forged-resource-reference`), a foreign-owned value is
   `:foreign-value`, a heap cell `:cell`, a host object `:host-object`.
   The walk records every **segment** reached and every **context**: a
   closure's `(A, entry, E)`, an `act`'s or `run`'s `(A, pc, E)`, a return frame's
   `(A, pc, E)`. A `tail` state contributes its `K` frames' contexts and
   none for its own dead activation.
5. **Code.** `{A vector}` for the segments reached, from the kernel's
   code table through its aliases; an unaddressed segment is
   `:yin.k/non-portable :unaddressed-segment`, an address the kernel
   cannot produce a vector for is `:yin.k/unsatisfied`.
6. **Dependency closure** (`discharge!`), **per context, over live
   code.** For each recorded context `(A, at, E)`, the live pcs are
   `(live-pcs v at)`: the closure of `at` under `analysis/successors`
   plus, for every reachable `:closure` instruction, that closure's body
   from its entry (reachable code can still construct it). Every free
   occurrence (`scan/free-occurrences`) at a live pc must be **answered
   in that context**, in `resolve-var`'s order: a reserved name; the
   context's own `E`; the **one** store `engine/active-store` selects for
   that `E` (the root store when `E` has no `store-of`, else the module
   store it names, both of which travel); a profiled primitive (a
   `:host`-class profile refuses `:yin.k/non-portable
   :host-state-primitive` at that point, so a host primitive shadowed by
   `E` or the store lifts); an installing module's export (`m/x` built
   with `(symbol (name m) (name x))`); a module the registry resolves for
   a namespaced name. Any unanswered name is `:yin.k/unsatisfied`,
   `:yin.k/discovery :incomplete`, `:yin.k/missing {:obligations #{…}}`.
   *Why per context:* codex F3, the union of all module-store keys
   answered names the running closure could not resolve. *Why live
   code:* codex F6, a numeric or whole-body scan refused valid
   continuations whose unreachable branches named a missing symbol.
   The parser admits no backward jump (§3.1 of the frozen design), so no
   carried vector exercises the backward edge; `live-pcs` is public and
   is tested directly on a hand-built vector with a `:jump` to an earlier
   pc. Registry answers record the entry's own address unchanged, and host
   answers also record the used export's profile address. The lower
   re-discharges these binding facts against the receiver (§7.2).
7. **Trees** (§6.1): every carried segment has a non-empty entry in the
   `:source-index` option, else `:yin.k/unsatisfied` with `:yin.k/trees`.
8. **The task.** `requires` is `{:yin.k/cursor-profiles … :yin.k/segments
   … :yin.k/trees … :yin.k/modules … :yin.k/host-exports …}`; the abstract task is initially assembled with nil custody.
9. **Frames.** `validate-frames` over the assembled abstract task (X1,
   every wait, run state, ready delivery state and descriptor, parked state and reified value, children recursively); a
   defect is `:yin.k/non-portable :inconsistent-state` carrying it. The
   lift does not decode its own output a second time.
10. **Header and custody, then emit.** After `lift-task*` has validated
    frames, a non-nil header refuses `:op-seq-exhausted` at 2^52-1 for
    running/blocked/parked roots, checks every retained write of the tree against
    enrollment (`:unprotected-pending` when an enrolled target lacks an
    op-id; `:yin.k/unsatisfied` when an id targets an unenrolled stream),
    and writes the root's custody slot by kind. `custody-baseline` then
    validates the whole tree, including fork op-id refusal. `emit`
    numbers cursor cells canonically and sorts rows by the string of
    their id. The BLAKE3 pack address hashes the encoded whole pack;
    the root id hashes the structural task row. The walk can already
    have served streams before a custody/protection refusal; retained
    successful answers are reused on retry.

**Refusals are thrown.** Every refusal of lift and lower is an `ex-info`
whose ex-data carries `:yin.k/status`, a `:yin.k/kind` where one is
named, and the defect's data, the same convention as `decode` and `emit`;
nothing returns a `{:status …}` map (engineer deviation 2, accepted).

**Amended 2026-10-16 (any-pc, owner invariant 2026-10-17)**

Each ready entry contributes its delivery state, completed descriptor, lifted outcome and optional refusal, and explicit resource-update bodies. Completed descriptors do not re-enter outstanding-pending validation.

### 7.2 Lower

**DECIDED** (draft §8 in order; §3.3): gate, structure, graph, hashes,
canonical form, canonical numbering, **frames**, pending and operations,
dependency closure (`requires`/`code` agree with the census;
`:yin.k/trees` complete per §6.1), custody, composition, restore. Restore
builds the receiving kernel's state: semantic-register: `code` through
`load-vector`/`attach-image` under `code/contract`, the free environment
as base `:env`, each `:wait` to an engine entry (payload plus the
pending's engine fields, `lower-frame!`'s shapes, `handoff.cljc:3182-3246`,
`:deliver` carried), parked records into `:parked`, the result into
`:value`; de Bruijn: derive and attach the image for each A, set
`:free-env`, and `layout/lower-act`/`lower-tail` each frame (the stack
lane's `tail` needs 6c-pre). Restoration delivers nothing, runs nothing,
mints nothing.

**Amended after 7 (2026-10-14)** — In the hashes stage, before digest verification and numbering, a parsed non-BLAKE3 carrier row id is refused as `:yin.k/undecodable :row-algorithm`, then each remaining row id is verified under its named algorithm, with malformed ids and digest mismatches refused as `:yin.k/hash-mismatch :row`.

**Amended after 6c (2026-10-13), the lower into a de Bruijn kernel as
`layout/lower-task` runs it (engineer deviation 2, codex: acceptable;
D4 by R6).** `layout/lower-task scratch k wire attach! opts`, in order:
(i) `rows/decode` of the wire under the budget (steps 1-7 of the
carrier); (ii) `lower-defect` on the decoded task, judged by the
receiver's `kernel-kind`, **before any attach**, thrown as the carrier's
refusals are; (iii) `rows/lower-task` of the same wire into `scratch`, a
fresh semantic machine of the same composition (the carrier's
stages, attachment and restore, §7.2 above, unchanged); (iv)
`kernel-task` of the restored machine into `k`. `lower-defect`'s clauses
run in this order and the first that holds is the refusal: two carried A
whose vectors derive one image of the receiver's kind,
`:yin.k/unsupported :ambiguous-image {:yin.k/image ident :yin.k/segments
#{A …}}` (§4.1; a vector that derives no image is left to the carrier's
own checks); install children, `:yin.k/unsupported :installs`; a closure
or continuation in the free environment, `:yin.k/unsupported
:free-env-value {:yin.k/path …}` (§6.3); and, for the register kernel
only, a `:prim` row reachable through a wait's or a parked state's
window, lexical binding, closure capture, reified state or guest
collection, `:yin.k/unsupported :continuation-registers {:yin.k/path
…}` (§4.2). A primitive function is an admitted carrier value (`:prim`);
the restriction is the register kernel's, not the carrier's, and does not
reach the free environment. `kernel-task` then builds the receiver: the
space of the machine's vectors (`machine-space`, with the map from the
scratch machine's segment ids to A), every image derived and attached in
A order (`attach-space`: `stack/attach-image` under `vm/stack-contract`
or `register/attach-image` under `vm/register-contract`), and under a
lower context over that space the store, module stores, parked records
(`lower-state`, typed `:parked-continuation`), wait entries
(`lower-state`) and the value converted through `positional-value`; the
free environment set as `:free-env`; `:resources`, `:id-counter`,
`:halted?` and `:blocked?` carried, `:ready-queue` empty. It delivers
nothing and runs nothing. The scratch machine's `:env` and
`:capability-secret` are not read: refs verify only when the scratch
receiver and the kernel share a composition, which the harness
guarantees and a production caller must. The two `assoc` sites of
`canonical-task` and `kernel-task` are on the store-write audit's
allowlist (`store_write_audit_test.clj`) with the reason of
`rows/restore-task`'s entry: both copy a store whose every key already
passed the one program-write gate and convert values only, so routing
them through `store-put` would re-check what the carrier's step checked.
*Why:* restoring through the carrier's own `lower-task` reuses its
twelve steps and every validation rather than writing a second restore
(engineer deviation 2); the defects a kernel cannot hold must be named
before the first `attach!`, as every carrier defect is (§7.2 above;
codex M8, which moved `lower-defect` after `rows/lower-task`, is killed
by the attach-count assertions).

**Amended after 7 (2026-10-14)** — Reason: phase 7 adds install verification and one tree-wide custody stage.
The staged checks and refusal kinds follow the code. Steps 1-7 are `decode`
(gate, structure, graph, hashes, canonical form, numbering, frames,
§3.3). Steps 8-10 run stores, pendings/install records and dependencies,
**each over the whole task tree (root and every install child, pre-order)
before the next**, then custody **once for the whole tree**, and
**every one of them before the first `attach!`**; a defect in a child's
later stage therefore cannot be masked by the root's earlier one, and
zero streams are attached on any refusal (codex F1, F2, F4, F7; mutants
that attach early are killed by the attach-count law):

| Stage | Function | Checks, in order | Refusal |
|---|---|---|---|
| stores | `check-stores!` | no key of the root store or of any module store is a reserved name (`vm/reserved-name?`), refused here rather than by restore's `store-put` | `:yin.k/undecodable :reserved-name` (`:yin.k/role :store-key`) |
| pendings and install records | `check-pendings!` | the task's role slot equals its position (`:root`/`:child`); every `:install` pending names a carried install row; kind consistency (the complete §2.6 product table: a single run for `:running`, waits or ready for `:blocked`, neither for `:halted`, a held key for `:parked`); completed-outcome status/refusal checks and shape-only IDs at slot 18; then every install record by `check-install!` (§5.2: phase ∈ `#{:running :parked}`, the retained response and image contract, response `:yin.link/id` equals non-nil parent, manifest-named derivation record hashes correctly and connects the manifest tree to the image A, child kind `:blocked` at phase `:parked`, `:running` at phase `:running`) | `:role`, `:incomplete-install`, `:kind`, `:parked-id`, `:install-phase`, `:install-response`, `:install-link-id`, `:install-image`, `:install-child`, all `:yin.k/undecodable` |
| dependencies | `check-dependencies!` | `requires` is a map whose `:yin.k/segments` equals the key set of `code` (`:requires`); every segment has a non-empty `:yin.k/trees` entry; every `store-of` of every `:closure` and `:env` row of **this task** (children excluded, each checked in its own stage pass) names one of this task's `:module-store` rows (§2.6), so a parent's or sibling's store of the same key cannot answer (codex F7, F8); every vector attaches in the receiving kernel under its A (`attaches?`: `module/attach-module` into a throwaway copy, the result discarded) (`:segment`); every `:closure` row names a `:closure` tuple of its vector at its entry (`:closure-origin`) with the same binders (`:closure-binders`); every `:prim` names a primitive the receiver holds | `:yin.k/undecodable :requires`, `:yin.k/unsatisfied` with `:yin.k/trees` or **`:yin.k/stores`** (`:yin.k/discovery :incomplete`), `:yin.k/undecodable :segment`, `:closure-origin`, `:closure-binders`, `:yin.k/unsatisfied {:yin.k/name p}` |
| custody | `check-custody!` | `custody-baseline` over the tree; fork refused under `:exclusive`; halted exclusive needs no grant; running/blocked/parked exclusive validates grant evidence, pack checkpoint, binding, lease/holder, arbitration, epoch, live tenure and input prefix, then protection in both directions and op-id presence against enrollment; `:fenced` skips grant/protection | grammar's `:yin.k/undecodable` kinds; `:yin.k/profile-mismatch`, `:yin.k/awaiting-grant`, `:yin.k/not-holder`, `:yin.k/unsatisfied` |
| modules | `check-modules!` | Whole-tree module address and used host-export profile equality; carried install children require receiver admission; after custody, before attach | `:yin.k/unsatisfied :module-pin-mismatch`, `:host-export-mismatch`, or collected missing with `:yin.k/discovery :complete` |

**Amended 2026-10-16 (module-pins, R4).** After custody validation and
before attachment, every carried install child must have its manifest
address listed under its module name in the receiving composition's
`opts {:yin.k/admitted-modules {name manifest-address}}`. The default is empty:
an absent or different address refuses as
`{:yin.k/status :yin.k/unsatisfied
  :yin.k/discovery :complete
  :yin.k/missing {:yin.k/modules {name carried-address}}}`.
This applies to children throughout the task tree, including fenced
restoration; `holder.export/rehydrate-fenced` forwards the composition's
`:yin.k/admitted-modules` option to the lower. The lift never carries the sender's
`:yin.admit/id`, `:yin.admit/decision`,
`:yin.admit/asserters` and `:yin.admit/address` from an install
response; a carried admission field is refused as
`:yin.k/undecodable :install-response` before `link-install` can record it as receiver evidence. The
composition supplies admissions from its own policy, never from the pack.

Then **attachment** (step 11, `attach-streams!`): one `attach!` per
distinct stream identity of the whole tree, `:yin.k/unsatisfied
{:dao.stream/identity …}` when the composition declines. Then **restore**
(step 12, `restore-task`): the carried vectors attached by A into a
receiver emptied of code, so frame and closure addresses map through the
receiver's own aliases and no source-local segment number travels; fresh
resource keys per stream identity and per cursor cell; values re-sealed
under the receiver's owner; waits in `lower-frame!`'s entry shapes, a
`tail` with `{:deliver :return}` and no window, env or pc; parked records
with a cycle guard; the store through `engine/store-put`; module stores;
install children spawned by the engine's spawn discipline and then
restored recursively (the spawn's initial control is replaced, no
`vm/run`); the free environment as both `:env` and `:free-env`; the id
counter `max(local, carried)` for ordinary resume. Restore meets no carrier
defect, by
construction of the stages above. `check-modules!` checks bindings over the
whole tree after custody and before attachment: unequal held addresses or
profiles refuse terminally as `:module-pin-mismatch` or `:host-export-mismatch`;
absent bindings, absent exports and unadmitted install children are collected
in one `:yin.k/unsatisfied` with `:yin.k/discovery :complete` and
`:yin.k/missing {:yin.k/modules … :yin.k/host-exports …}` (only nonempty keys).
The receiver's registry pins and slices are kept as given. Its task-local lowered bindings are recreated through `engine/receive-module` after the carried images and stores, so they name the restored task's code coordinates and carried live stores win. The kinds `:role :custody :incomplete-install
:kind :parked-id :requires :segment :install-phase :install-response
:install-child :closure-origin :closure-binders` and the `:yin.k/stores`
census are the engineer's and the fix rounds' naming; the earlier text
named none of them (deviation 9, accepted with the requirement that every
defect be a named transport outcome before attachment).

**Amended after 7 (2026-10-14)** — Reason: fenced lowering is administrative recovery, not grant admission.
With `:fenced true`, every task is gated `:exporting`; waits move to
`:yin.k/recovery-waits`, every parked record to `:yin.k/recovery-parked`,
and the id counter is exactly the carried value. Resource keys are
associated with their cursor-cell ids in `:yin.k/recovery-cell-ids`.
Child spawning temporarily disables `:make-stream`, initializes nothing
and restores the constructor afterward. `holder.export/rehydrate-fenced`
checks the recovery header and local state before lower, moves waits and
parked records into the retained export record, and proves byte-identical
re-emission. It restores no tenure or runnable gate; recovered abort refuses.
A grant's `:checkpoint` names the pack address, never the root id.

---

**Amended 2026-10-16 (any-pc, owner invariant 2026-10-17)**

A running task restores:

```clojure
{:control {:segment local :pc pc}
 :window W
 :env E
 :k (when (seq K) K)
 :halted? false
 :blocked? false
 :value nil
 :wait-set entries}
```

Restore the ready queue in carried order using the task’s fresh resource keys. Restore each completion’s correlation and terminal-outcome fields. Nothing runs, polls or re-appends during lower. Fenced restoration retains the queue outside the scheduler, and the closed-gate guard prevents execution through every semantic execution entry.

Every kind restores slot 18 in order. Resolve the ready state and completion descriptor, reconstruct the engine entry described in §3.1, then restore its value, status, refusal and resource updates. Update keys map through the slot-9 C→fresh-resource-key map; update bodies come from the ready row, not from slot 9.

No completed operation is polled or reissued by lower. A completed FFI request append resumes through the existing response-wait transition. Link/install completion restores without resurrecting retired pending resources.

A fenced restore retains ready entries in `:yin.k/recovery-ready`, alongside recovery waits and parked records, and exposes an empty scheduler queue. Running control remains available for byte-identical relift, protected by the 6d-a closed-gate guard.


### 7.3 Custody and lifecycle of a running root

**Amended 2026-10-16 (any-pc, owner invariant 2026-10-17)**

An exclusive running, blocked or parked root carries policy, occurrence, arbitration and next-op-seq, with origin on a successor; a halted exclusive root carries origin only. A completed `:put` wake is a completed operation: its op-id is well-formed but not a retained write. Completed outcomes never acquire publication or execution authority from their history.

1. **Preemption under live tenure.** `run-cycle` uses `(if fuel (sr/run-fuel m0 fuel) (vm/run m0))` under its existing gate and halted guards. Holder `:fuel` is a positive integer or nil, checked by `check-common!`. Nil retains unrestricted behavior. After replay, emit and live read, active control leaves the holder running with `:yin.k/at-boundary true` in its status detail. The composition explicitly calls `hand-off`; no automatic export at the bound is implied.

2. **Tenure dies after a completed slice.** Stage-local execution returns the latest completed holder state explicitly on tenure failure. The ended holder retains that machine for inspection only. Recovery uses the last durable checkpoint; progress since it is lost. Inner immutable rebinding is not a channel to an outer catch.

   Each stage checks tenure against its input, executes with the existing external-seam checks, associates its returned machine and metadata, and passes that successor to the next stage. Its outcome is `{:status :ok :state completed-state}` or `{:status :failed :state latest-completed-state :failure failure}`. The stage-local catch closes over its input. The failure consumer calls `end-run` on that carried state; `:yin.k/retained-boundary` records its PC, segment, checkpoint and completed-slice count (`:yin.k/steps-since-checkpoint`, holder program steps, not a guest-instruction count). A failure before an operation returns retains the previous completed stage, never an invented partial result. External effects are not rolled back. Journal-uncertain and inbox-refusal exceptions retain their propagation rules.

3. **Expiry and recovery.** Progress since the last durable checkpoint is lost on expiry; fuel makes the computation interruptible, exporting before the bound is what preserves it. Recovery lowers the checkpoint this holder received, or a durably authorized successor. Retained local progress receives no adoption, execution or publication authority. The source remains ended; `exit-mint` and abort cannot revive its tenure.

4. **Reopen and fencing.** Direct `vm-hot`, `vm/step`, `vm/run` and `run-fuel` refuse `:exporting` and `:ended` before guest, scheduler or bridge work. The guard ships with running fenced restoration in 6d-a and remains outside the instruction loop. The refusal is an ex-info whose data is `{:rule :gate-closed :yin.k/gate mode}`. A fenced running restore preserves control, window, environment, K and ordered completed outcomes for byte-identical relift; nothing runs during lower. `rehydrate-fenced` reproduces the published pack byte for byte, including cursor updates.


### 7.4 Fuel

**`yin.vm.semantic/run-fuel [machine n]`**, public, with positive exact integer `n`.

One invocation performs:

1. Closed-gate check.
2. Exactly one raw budgeted action:
   - If control is present, `vm-hot` with fuel `n`.
   - Otherwise, one budgeted scheduler round.
3. If the result is blocked and an explicit bridge is installed, at most one `ffi/bridge-step`.
4. Return that resulting machine immediately.

The implementation does **not** wrap the raw action in unchanged `ffi/maybe-run`. A handled response or malformed-request diagnostic does not restart the runner or bridge inside the same invocation. The next caller invocation resumes progress.

The bounded bridge helper may be private to the semantic runner or a bounded variant beside `ffi/maybe-run`; its contract is the four steps above. Existing unrestricted `vm/run` bridge behavior remains unchanged for live machines.

**Budgeted scheduler round.**

Propagate the budget through `check-wait-set`, `poll-links`, `advance-installs` and `advance-install`. Each visited child is invoked once through `run-fuel child n`; nested children use the same rule. Preserve existing gate stamping and the prohibition on running children under closed gates.

After the sweep, resume at most one queued entry. Do not immediately run the resumed activation in that invocation. A running child retains phase `:running`; blocked children retain `:parked`; halted children complete installation as today.

The dispatch tests **control presence**, not merely `engine/active-continuation?`: the latter only tests blocked/halted flags and can classify a control-less ingress-ready machine as active. Therefore no `run-fuel` path falls through `vm-hot` into its unbudgeted scheduler fallback. A quiescent control-less machine takes the budgeted no-work round and returns.

**Per-tree bound.**

Each task visited during the invocation receives at most one budgeted action and at most one bridge step. Each task executes at most `n` guest instructions. For `t` distinct tasks visited during the invocation, including children created during the round, the instruction bound is `n × t`. No task is revisited with a fresh budget inside that invocation.

**The honest guarantee**, copied verbatim into the documents:

> Fuel counts completed guest instructions. One `run-fuel` invocation executes at most `n` instructions per visited task, at most one scheduler round per visited inactive task, and at most one bridge step per visited task. A bridge result never restarts execution inside that invocation. No wall-clock bound is claimed for an instruction, host function, stream constructor, variable resolution, bridge step or scheduler sweep. The VM has no clock and converts no time to instructions; `dao.lease` owns liveness and the composition chooses `n`.

`vm/step` keeps its telemetry behavior. `run-fuel` adds no per-fuel snapshot; existing park, resume and bridge telemetry remains.

Running install children use the same carrier and receiver-admission checks as other tasks. The backends continue to refuse install children.


## 8. Slices, gates, entry conditions

Verification follows `docs/agents/build-n-test.md`: G0 per iteration on
the JVM, G1 `bb test:sub yin.vm` on three hosts, G2 `bb test:changed`,
G3 on master.

### 8.1 Slice 6a: the frame validator and the row codec (independent)

**Files to create.**
- `src/cljc/yin/vm/semantic/frame.cljc` (§3): `defect`, public.
- `src/cljc/yin/vm/ucf/rows.cljc` (§2): the row table as data,
  `frame->rows`, `rows->frame`, row ids (Jing default), the census and
  numbering per the draft §6, `canonical-under` (§5.5, test-only but
  placed here so three test namespaces share it). **Not** `lift-task`/
  `lower-task`: full task transport is 6b.
- `test/yin/vm/semantic/frame_test.cljc`,
  `test/yin/vm/ucf/rows_test.cljc`.

**Depends on:** phase 3, 4a (landed), 4b stage 1 (branch) for live
frames to validate. Touches nothing of 4b or 5; needs no scanner.

**Acceptance (6a).**

1. `frame-test/contract-is-pure`: `frame/defect` takes `[frame code
   context task]`, `task` being plain data; the namespace's `ns` form
   requires no kernel and no `yin.vm.engine` (a string check over the
   source on the JVM lane, `#?(:cljd nil :clj …)`).
2. `frame-test/every-reference-frame-validates`: for every 4b-stage-1
   row of §5.2 (1, 2, 5, 6, 13-16, 18, 19, 21, 22) over the four segments,
   plus row 20 as one separate, unwrapped run (its empty `K` needs no wrapper
   frames; §8.2 6b makes the same exception), every
   `act`, `tail`, return frame, parked record and reified value the
   register evaluator mints passes `defect` in its context with the
   task context decoded from the machine's own tables.
3. `frame-test/each-rule-refuses-its-tamper`: for each rule S1-S4, C1-C3,
   A1-A6, T1, K1-K3, E1-E2, X1, P1-P4 one tampered frame built from a
   valid one, refused with that rule's kind and a path naming the slot
   (**twenty-four rules**: 4+3+6+1+3+2+1+4, the twenty-three of the
   round-1 text plus X1; **twenty-five rows**, since C2 contributes two,
   a float `pc` for `:pc-kind` and an out-of-range one for `:pc-bounds`;
   X1's row goes through `frame/task-defect`); plus
   `first-defect-is-structural`: a frame with a malformed `K` **and** a
   bad window reports `:k-shape`.
4. `frame-test/nil-valued-register-is-a-member`: row 22's frame
   validates with the nil key present; the same frame with the key
   removed is `:window-missing`.
5. `frame-test/worked-example-saved`: on `:worked-example`, a frame at pc
   4 with window `{1 <f>}` and `rd 2` validates; `{1 <f> 2 x}` is
   `:window-self`; `{}` is `:window-missing`; `{1 <f> 3 x}` is
   `:window-extra` (`saved(4, 2) = {1}`, `analysis_test.cljc:131-139`).
6. `frame-test/free-env-mismatch`: row 21's frame (initial free
   environment `{x 99 y 7}`) with `task :free-env {x 99 y 7}` validates;
   with the free environment `{x 99 y 8}` or `{x 99}` it is
   `:free-env-mismatch` (`y` is unshadowed); with the lexical `x` removed
   from `E` it is `:free-env-mismatch` (presence); changing the lexical
   `x` in `E` from `1` to `98` **does not** trip E2 (it is off the
   comparison), and the test asserts that too; a `free-env` row with the
   key `yin/def` is `:reserved-name` (X1).
7. `rows-test/bijection`: `frame->rows ∘ rows->frame` and the converse
   are identities on every frame of item 2, nil-valued keys included.
8. `rows-test/row-ids-are-jing-default`: every content row's id equals
   `(jing/segment-key body)` and names Jing's default algorithm; no
   `sha256` appears in any id.
9. `rows-test/window-sorted-and-unique`: an out-of-order or duplicated
   `:window` row is `:yin.k/undecodable` at canonical form (step 5).
10. `rows-test/canonical-under-is-renaming-invariant`: §5.5's
    transformation gives equal root ids for two emissions of one abstract
    task under permuted cursor-cell numbering and distinct stream identities,
    with the fixture's correspondence map; **and the unordered-container
    permutation**: a store value that is a set of two stream references
    and a map keyed by two cursor references, emitted twice with the two
    streams' identities swapped (and the correspondence map swapped
    accordingly), gives equal root ids; a marker absent from the map is
    a test failure.
11. kondo clean; `bb test:sub yin.vm` green on three hosts; Node log
    shows `Testing yin.vm.semantic.frame-test` and
    `Testing yin.vm.ucf.rows-test`.
12. `rows-test/discovery-order-numbering`: `canonical-numbering` returns
    the maps of the numbering note's appendix A for `fixture :s1 :s2 :x
    :y`, the review counterexample, `[x x y]`, `[x y y]`, `[#{X Y} X]`,
    the witness `[#{#{[A B] [B A]}} A]`, `[#{X Y} #{Y Z}]`, the path and
    the two frame fixtures.
13. `rows-test/occurrence-count-separates-members`: the review
    counterexample numbers `Y cc-1, X cc-2`, pinned `2d9873a8…`; the path
    pinned `726a9fbb…`; `[#{X Y} #{Y Z}]` pinned `d84480d4…`; each equal
    after renaming plus reversal of every set.
14. `rows-test/interchangeable-ties`: `#{X Y}`, `#{[X X] [Y Y]}`,
    `{X 1, Y 1}`, `#{#{X Y} #{Z W}}` number without refusal; reversal and
    renaming give equal roots; pins `07eda35d…`, `da69c679…`,
    `49d4478c…`, `18b70de6…`.
15. `rows-test/alias-pattern-is-part-of-the-key`: `#{[X X] [Y Z]}` and
    `{X X, Y Z}` give one root for both enumerations and after renaming,
    pinned `387f294a…` and `be62ba43…`; the witness numbers `B cc-1,
    A cc-2`, pinned `0193312…`, and decodes; the balanced-alias witness
    `#{[X X Y Y] [Z W Z W]}` gives one root for both enumerations, pinned
    `5376f133…`, and the key without the local index would not.
16. `rows-test/unresolved-cursor-cells-refuse`: the 2-cycle, the
    3-cycle, the loop plus 2-cycle, the seven-cell state,
    `[#{X Y} #{X Y}]`, the mixed-body, the anchored, the two-2-cycle and
    the 3-cycle-beside-fan states refuse at `emit` with
    `:yin.k/non-portable :unresolved-cursor-cells`, the paths and cells of
    the numbering note's appendix A, the same verdict and path after
    renaming plus shuffling; a carrier of each built with `emit-numbered`
    decodes to `:yin.k/undecodable :unresolved-cursor-cells` with the
    path prefixed by the root, never `:noncanonical-cursor-cells`; a
    parent whose install `'a` holds the 2-cycle refuses at `emit` with
    path `[12 a 15]` and its `emit-numbered` carrier decodes to the
    `:yin.k/undecodable` kind with path `[<root> 12 a 15]`.
17. `rows-test/canonical-roots-are-pinned`: `fab223…` and `c4a7c7…`
    unchanged; `a850fce6…` (`[#{X Y} X]`), `4993b5b1…`, `536c810b…`,
    `e67405ec…` (`[#{#{X Y} #{Z W}} X]`), `6e7c7a6b…` (ten cells) added;
    `55ff168e…` removed.
18. `rows-test/counterexample-search`: a seeded generator (at least 2000
    random value states, 200 frame-bearing, 100 with install children)
    in CI: every numbered state's carrier decodes and re-emits to its
    root; renaming every cursor cell plus shuffling every set, map and
    census gives an equal root, or the same refusal verdict and path;
    refusals are counted and reported; the largest `:work` is reported.
19. `rows-test/numbering-is-total`: a census cell the walk does not reach
    raises the totality defect, not a refusal, before any row is emitted.
20. `numbering-precedes-frame-defect`, `alias-topology-and-census`,
    `canonical-under-is-renaming-invariant`,
    `unordered-containers-are-order-free`,
    `child-scope-and-shared-child-dag` and
    `census-is-numeric-from-ten-cursor-cells` pass unchanged against the
    rewritten decoder; `numbering-precedes-frame-defect` is extended: the
    2-cycle with the same reserved-name binding, carried by
    `emit-numbered`, decodes to `:yin.k/undecodable
    :unresolved-cursor-cells`, not `:reserved-name`.
21. `rows-test/gates-and-work`: 65 cells refuse `:resource-limit
    [:cursor-cells]` under the default budget and 2 cells under
    `{:cursor-cells 1}`; `[[[[X]]]]` under `{:depth 3}` refuses
    `[:depth]`, and so do one cell inside 3000 nested vectors under
    `{:depth 3}` and under the default budget, and a literal nested 3000
    deep beside a cell (no host stack overflow on any of the three hosts;
    200 nested vectors number); a set holding a 400000-scalar literal
    refuses `[:work]` under `{:work 2000}` with the work counter at 2001
    and under `{:work 900000}` with at most 1000 estimator steps; a state
    whose bodies carry a 65536-character descriptor numbers under the
    default budget; `{:work 1}` refuses `[:work]` at `emit` and, for a
    valid carrier, at `decode`.
22. Budgets: `:label-bytes` and `:search` gone, `:work` default
    `4194304`.

### 8.2 Slice 6b: full task transport and the harness on the reference lane

**Files to change.** `rows.cljc`: `lift-task`, `lower-task` (§7), the
pending rows' lift and lower, install children. **Files to create.**
`test/yin/vm/semantic/safepoint_harness_test.cljc` (§5, the
`:semantic` lane). The old harness file is not edited.

**Depends on:** the explicit `:free-env` lift argument supplied by the
harness (§6.3; the 4b retention seam is a named follow-up for the 4b
track, not a 6b prerequisite; **amended after 6b:** the seam landed
before 6b and the lift reads the field by default); the composition's
`:source-index` (§6.1); 4b stage 2 (FFI retained→sent restoration, link
request/response/install preservation, install children, the
evaluator's `IModuleKernel` linking methods: `link-format`,
`spawn-module`, `lift-closure`, `lower-closure`); **phase 5c** (the
`scan` namespace, for the dependency closure over `"v4"` vectors, §7.1).
Touches nothing of 4b or 5.

**Acceptance (6b).**

1. `every-row-every-segment-matches-the-reference`: rows 1-19, 21, 22
   and 24 of §5.2 × four segments, plus row 20 once (outside the
   wrappers, §5.2), on the reference lane; laws 1-3 and 6; counts
   asserted (22 wrapped rows × 4 segments + 1 = 89 runs).
2. `tail-states-carry-no-window`: rows 6, 9, 12, 20 lift to `:tail`
   rows of three slots and lower to through-`K` delivery.
3. `install-rows-carry-the-child`: rows 10-12; the child's kind and
   phase as stated **in the amended rows (10: `:blocked`/`:parked`; 11:
   `:blocked`/`:running` set by hand; 12: row 10's child, tail
   delivery)**; no initialization on lower (the restored child has nil
   `:control`, is still blocked on its one `:next`, and its code is
   attached by A).
4. `captures`: rows 14-16 round-trip; row 15's invocation after lower
   delivers once into the captured `rd` and yields `:done`.
5. `free-env-shadowing`: row 21 yields `99` after lower.
6. `synthetic-tail-bookkeeping`: row 23's hand-built parked `tail`
   under a call id in `:ffi-call-ids` passes `defect` with context
   `:parked`; the **same valid record under a key absent from
   `:ffi-call-ids`** is `:parked-site` (P4); the record with `site` moved
   to a non-tail call is `:site` (T1 runs before P4).
7. `refusals`: every refusal row of §5.2 refuses with the named status
   and kind before any attachment (**amended after 6b:** the `:tail`
   fourth slot is `:row-shape`, B3; the attach count is asserted to be
   zero on every refusal and equal to the number of distinct stream
   identities on every success, the law that kills the early-attach and
   duplicate-attach mutants); `first-defect-order`: a body with a
   noncanonical cursor-cell numbering **and** a tampered window reports
   `:noncanonical-cursor-cells`.
8. `a-response-cursor-shared-by-two-waiters-crosses-as-one-cursor-cell` and
   `a-nonempty-ready-queue-is-not-quiescent`, ported.
9. kondo clean; `bb test:sub yin.vm` green on three hosts.

### 8.3 Slice 6c: the layouts, the stack seam, the cross-engine lanes

**6c-pre, the stack tail-completion seam.** File to change:
`src/cljc/yin/vm/debruijn/stack.cljc` (§4.3): park-entry builders mark a
tail-call park `:resume-mode :return-result`; `stack-restore` delivers
such an entry by the return transition. Test to create:
`test/yin/vm/debruijn/stack_tail_completion_test.cljc`: a tail
effectful call parked and resumed delivers through `K` (pops the frame,
truncates to its base, pushes the value) and halts with the value when
`K` is empty; a tail call in operand position never executes the layout
syntax after it; non-tail parks are unchanged (the old stack tests stay
green). **Depends on** nothing of 4b or 5.

**6c proper.** Files to create: `src/cljc/yin/vm/semantic/layout.cljc`
(§4); `test/yin/vm/semantic/layout_test.cljc`; the two extra
lanes added to the 6b harness file. **Depends on:** 5a, 5b (`derive`),
6b, 6c-pre.

**Acceptance (6c).**

1. `layout-test/register-pairing`: for every `P ∈ C`, `phys` is total on
   every body's named virtuals; `virt(p, s)` is single-valued at every
   reachable boundary pc (§5.6 item 3).
2. `layout-test/register-live-is-live-out`: §5.6 item 3's second clause,
   tail boundaries `[]`.
3. `layout-test/register-rd-from-a`: on `:worked-example`'s register
   lane at the inner call's park, the canonical `rd` is `2` while the
   kernel's `dest` is `3`; the K frame of a nested non-tail call in
   `:depth-3` has its `rd` from A, not its physical `dest`.
4. `layout-test/stack-correspondence`: §5.6 item 4.
5. `layout-test/stack-layout-is-the-saved-set`: §5.6 item 5 and the
   worked cases: `(f (g x) y)` at the inner call → `[1]`; `(f (if c (g)
   2) y)` at `(g)` → `[1]`.
6. `layout-test/stack-slices-partition`: §5.6 item 9; and the
   multi-frame fixture, the expression `(cat [1] ((fn [x] (cat [2] ((fn
   [y] (cat [3] (next-of c))) 2))) 1))` under the harness's standard
   binding setup: `with-cat` outermost, then `(let1 s (stream/make 4)
   (let1 c (cursor-of s) …))` around it (the stream is made inside the
   program, as every row does: that is this fixture's chosen setup. A
   primitive `cat` supplied in the free environment would sit in a live
   slot; a primitive is an admitted carrier value (`:prim`) that the
   register kernel cannot restore from its saved-register payload, so
   the register lane would refuse it `:yin.k/unsupported
   :continuation-registers` before attach (**Amended after 6c
   (2026-10-13)**, codex D4 by R6: the earlier phrase "is no carrier
   value" misplaced the limit on the carrier; §4.2, §7.2),
   `debruijn_register_effects.cljc:196`; an authentic cursor reference is
   machine data with an explicit carrier marker, `engine.cljc:899-905`, so
   in-program construction of `c` is a fixture choice, not a carrier
   restriction). **Derivation of the expression
   alone** (`cat` and `c` taken as given): the outer application of
   `(fn [x] …)` pops its closure and `1`, leaving the outer `cat` and
   `[1]` pushed, so its frame's base is `2`; the inner application leaves
   the middle `cat` and `[2]` as well, base `4`; the current activation
   has pushed its `cat` and `[3]` and the read has popped its cursor
   operand (`stack.cljc:839`), so the operand stack has six values:
   slices `[0,2)` = `[cat [1]]`, `[2,4)` = `[cat [2]]`, current `[4,6)` =
   `[cat [3]]`, each slice's `stack-layout` being `[operator-rd
   literal-rd]` of its `cat` call and equal to that activation's saved
   window. **As run**, the three binding applications each pop their
   closure and single operand off an otherwise empty stack, so `K` has
   **five** frames with bases `0, 0, 0, 2, 4`, slices `[0,0) [0,0) [0,0)
   [0,2) [2,4)`, current `[4,6)`, stack length six; the test pins these
   bases from the kernel's state, checks the partition, and checks that
   lowering the lifted frame emits the same five bases (§4.3; the
   before-slice rule would emit `0, 0, 0, 0, 2`).
7. `layout-test/env-reconstruction`: §5.6 item 6.
8. `layout-test/closures-round-trip`: §5.6 item 7.
9. `layout-test/tail-lowering-is-unobservable`: §5.6 item 8.
10. `harness/cross-engine-canonical-equality`: law 4 for every case of
    the shared fragment under `canonical-under`: 61 cases, each over
    exactly three distinct kernel lanes. (**Amended after 6c
    (2026-10-13)**, R2, ACCEPTED by the owner on 2026-10-13; was "every
    row × segment", 89. The quick tier runs
    `cross-engine-canonical-equality-bare`, the 16 bare cases; the 61
    are `^:slow` under `slow/guard`.)
11. `harness/cross-engine-lowering`: law 5, six ordered pairs over the
    shared fragment: 350 transfers; `unlowerable` stays `#{[4 :stack]
    [4 :register]}`. (**Amended after 6c (2026-10-13)**, R2;
    was every row × segment × pair, 534. The quick tier runs
    `cross-engine-lowering-bare`: 92 transfers; row
    4's two admitted pairs are asserted by count. **Amended 2026-10-13
    (backends)**: "366 once 6c-post-D2 lands" and "96 with the seam"
    withdrawn; the seam is rejected and retained FFI is outside the
    fragment, §4.6.)
12. `harness/layout-refusals`: a kernel stepped to a non-parking pc
    lifts as a `run` state on both de Bruijn lanes — kind `:running`,
    one run row, its pc the A pc the kernel stopped at (`layout-refusals`
    since 6e; a hand-built entry at a pc no delivery state of A occupies
    still refuses `:yin.k/not-at-safepoint`, and the same pc lifts as a
    run state: any pc inside a body); a register entry whose `live` was
    tampered reconstructs a frame the validator refuses (`:window-extra`
    for a unique dead slot, `:window-missing` for a dropped live slot),
    or is refused by the layout itself as `:yin.k/non-portable
    :register-layout` when the added slot is an ambiguous dead preimage;
    the test pins all three. (**Amended after 6c (2026-10-13)**, R5: the
    second clause replaced; an ambiguous tamper cannot honestly be
    promised `:window-extra`. The ambiguous fixtures are found over C by
    `ambiguous-dead-slots`, a reachable parking pc and a slot R gives two
    or more virtuals outside `live-out*` and the destination; the test
    asserts at least one exists, and the refusal carries the pc and the
    slot. The same deftest pins the four `:continuation-registers`
    refusals of §4.2: a primitive in a window, in a lexical binding, in
    a closure's capture and nested in a window's guest collection, each
    with its path and zero attaches, and each program crossing into the
    stack lane with the reference value. **Amended 2026-10-17 (any-pc,
    6e)**: the first clause is the positive running lift; the
    `:not-at-safepoint` refusal belongs to a pc outside every body, not
    to a stepped kernel.)
13. kondo clean; `bb test:sub yin.vm` green on three hosts.

**Amended after 6c (2026-10-13), the acceptance the fix round added
(R1, R3, R4; codex F6; the store-write audit).**

14. `layout-test/alpha-equivalent-segments`: `(fn [x] x)` and `(fn [y]
    y)` have distinct `:address` and equal `:register-id` and
    `:stack-id`; a de Bruijn source whose space resolves one image to two
    A refuses `:yin.k/non-portable :ambiguous-image` on both lanes with
    zero serves, the file-wide index widens the refusal and the world's
    own space lifts; a reference-lane task carrying both closures as
    store values refuses `:yin.k/unsupported :ambiguous-image` into each
    native lane with the receiver's own image identity, both A and zero
    attaches; one of the pair carried and the other only projected
    lowers with one attach and answers; the reference lane holds both
    under laws 1 and 2 (the survivor check).
15. `layout-test/free-env-values` and `free-env-nested-values`: §6.3's
    refusals both ways with their paths and keys, zero attaches and zero
    serves; a primitive and a cursor reference under the same nesting
    cross and are used.
16. `harness/law-4-controls`: the five controls of §5.5.
17. `store-write-audit-test`: the two layout `assoc` sites on the
    allowlist with their reason (§7.2); kondo reports no unused require
    in `layout_test.cljc` (codex F6).

The `^:slow` matrices and the quick-tier slices follow the test-tier
cadence: quick at every checkpoint, slow before big merges and at phase
boundaries.

### 8.4 Phase 7's entry conditions

**Met (2026-10-15, after the phase-8 cutover).** Phase 7 landed in full
(7a-7e, through `master@67b98907`, 2026-10-14): Stage E over row packs,
holder export with recovery format 2, the driver lower and the install
checks, compose and fencing on row packs, and the killed four-pair
process matrix run on jupiter (196 SIGKILLed lives); the slow tier was
green on the landed head. The REPL custody gap this section's earlier
note carried closed at 8c, when the REPL's kernel table moved to the
new kernel (renamed at 8f-2); the old codec tests and fixtures died
in 8d, and their transport sources in 8f-1 (not 8d; recorded in `yin.vm.ucf-revisions.md` I-6).
The Dart-on-master `dht-end-to-end` failure of that window was the
other seat's, pre-existing. The entry-conditions text below is the
record as it stood at 7d.

**Amended after 7 (2026-10-14)** — Reason: phases 6 and 7a-7c now provide the entry seams; 7d reports the Stage E port.
Phase 7's entry conditions are met: the phase-6 row carrier and frame/layout
harness are available; the holder, rows checkpoint inspector, ledger and
authority use packs; free-environment retention is threaded through the
lift; recovery format 2 is based on rows. The Stage D composition uses
semantic machines and the composition's function `:source-index`
seam. The 7d report records the corresponding Stage E world/scenario port
and local JVM results; 7d is still in review. Entry readiness is not
tri-host phase acceptance: Node/Dart Stage E and the killed four-pair
JVM/Node process matrix remain the orchestrator's gates. The owner accepts
the REPL custody gap until phase 8. Versions 0/1/2 remain only as direct
legacy codecs/tests until phase 8, without compatibility or version dispatch
in the row holder/authority path. The two backends remain as §4.6 specifies.

### 8.5 Ordering and concurrency

| | needs | concurrent with |
|---|---|---|
| 6a | 4b stage 1 (branch) | 4b stage 2, 5a-5c, 6c-pre |
| 6c-pre | nothing of 4b or 5 | everything |
| 6b | 4b stage 2; 5c (scanners) | 5a, 5b, 6c-pre |
| 6c | 5a, 5b, 6b, 6c-pre | 5c |

No file of 4b or phase 5 is edited. The old kernel file
`debruijn/stack.cljc` is edited by 6c-pre only, for the seam; the
register kernel is not edited. `handoff.cljc` and the old harness are
not edited. **Amended after 6c (2026-10-13):** 6c proper held to this:
it created `layout.cljc` and `layout_test.cljc`, extended the 6b harness
file and the store-write audit's allowlist, and edited no kernel, no
`rows.cljc` and no 4b or phase-5 file. The one further kernel edit this
design named, the retained-FFI response-pair seam of both de Bruijn
kernels (§4.3, 6c-post-D2), was rejected by the owner (§11, 6c Q2); this
design names no kernel edit beyond 6c-pre (**Amended 2026-10-13
(backends)**: the backends' fragment is not widened, §4.6).

---

**Dead ambient operands (static domain).** The non-tail set equation holds where the enclosing application's pending reads are still reachable. At the operand PCs of an application whose evaluation an operand-position tail call or `:resume` abandons (the corpus's `:resume-operand` and `:tail-operand` fixtures), ambient layout entries are dead per L while remaining in the region: the strongest static truth there is the inclusion `uses(p) ⊆ L(p) ⊆ layout(p) ∪ uses(p)`; the equality is permanently excluded at those pcs. 6e's dead-prefix rule governs the region after the transfer, and 6e's ordered dynamic checks — not the static set equality — carry those pcs.

## 8.6 Slice 6d-a: reference lane

**Amended 2026-10-16 (any-pc, owner invariant 2026-10-17)**

**Acceptance.**

1. **Frame rules.** Run states at worked-example boundaries validate, including body start. Pin PC kind/bounds, window scope, missing/extra keys, forbidden deliver/pending and wrong-context diagnostics. C3 failures on act/return frames are `:pc-body`. Delivery frames use `saved(pc, rd)`; running frames use `L(pc)`.

2. **Row grammar and completed validation.** Round-trip run frames; reject extra slots and incorrect reference kinds. Ready states admit act/tail only. Status is nil or keyword. Pin invalid completion tags, site mismatch, nonboolean link/install `try?`, inconsistent refusal slots, missing update-cell membership, duplicate update keys and malformed completed operation IDs. These lower refusals occur before attachment.

3. **Task-product kinds.** Retain running-only, active-plus-waits, active-plus-ready, blocked-plus-ready and halted-plus-ready cases. Add halted-plus-outstanding-wait with empty queue: pin the existing blocked normalization and dropped result separately. Retain invalid run counts, illegal result/parked-id and empty-blocked cases.

4. **Completed link/install outcomes.** Exercise success, try failure and ordinary raised failure for link/install producers. Capture completed install outcomes after removal of the install entry. Include active control beside a remaining completion. Immediate relift is equal; continuation delivers or raises once, without reconstructing retired pending resources.

5. **Shared cursor.** Two waiters share one cursor. Snapshot after the sweep before any resume. Preserve one canonical cell identity, the slot-9 current body, both values and update rows in order. Assert immediate relift equality and source/receiver continuation equality. Add an encoding case where one C pairs with different bodies across census/update rows.

6. **Completed write and terminal outcomes.** Fork and exclusive completed-write histories round-trip without enrollment or reappend. A malformed present ID refuses at the named slot-18 check. Nil IDs remain admissible where the ungated source produced none. Add a terminal-status ready case whose receiver raises identically, plus the FFI sent-request transition that creates a response wait without a second request append.

7. **Every returned boundary, slow tier.** Run the existing corpus through `run-fuel 1`; lift, lower into isolated worlds, immediately relift, validate frames and continue to the reference outcome. Pin measured counts and the driver coverage classes of §3.6. Constructed post-sweep cases are reported separately. Row 20's setup batch (`s/def!` store definitions executed at runtime) lifts only from the first boundary at which its definitions are answered in the store; the leading boundaries are excluded as a **pinned conservative-discharge false refusal of the owner-ratified Q6 family (default 5: keep edge-reachability, derive don't persist)** — recorded per case with its obligations, each of which names the case's own execution later answers — store or environment names its own later definitions execute, or module exports its own `require` installs — and zero serves, never skipped silently: the driver asserts the excluded boundaries form a contiguous leading run and that every boundary after the first successful lift lifts.

8. **Quick sample and products.** Retain v2’s structural sample and every-boundary small rows. Run the scheduler products and shared-cursor seam through the harness. Retain cursor-ref, reified-continuation, nil-live-register, `ghost` and hold-refusal cases.

9. **Layout gates.** At every reachable corpus PC, check register injectivity and jump/terminator layout availability; at every reachable tail-call PC check `set(uses(p)) = L(p)`; at every other reachable PC check the inclusion `set(uses(p)) ⊆ L(p) ⊆ set(layout(p)) ∪ set(uses(p))`, and additionally the equality `set(layout(p)) ∪ set(uses(p)) = L(p)` everywhere **except the pinned dead-ambient operand PCs — exactly `{(:resume-operand 1), (:resume-operand 2), (:tail-operand 1), (:tail-operand 2)}`** — where an operand-position `:resume` (abortive) or tail call (the enclosing application's call is unreachable) makes the enclosing application's pushed operands dead per L while they remain in the static layout and on the stack. At the pinned PCs assert the exclusion's cause: the layout entries outside `L(p)` are exactly those dead ambient operands. The exclusion set is enumerated, never predicate-based: a future corpus entry with a dead ambient layout fails the equality loudly rather than being skipped. On executed paths compare ordered region length and values to the ordered register sequence, including repeated-register agreement. Do not compare that sequence length to `|L(pc)|`. No dead-prefix lift, `slice-window` widening, or running-backend lift is implemented in 6d-a; those remain 6e item 6's scope.

10. **Budgeted children.** Preempt long and nested initializers; lift/lower running children under receiver admission; continue to linked. Count actual instruction transitions and assert at most `n` per visited task and `n × t` overall. No child receives a second budget inside one invocation.

11. **Bounded bridge execution.** A program repeatedly making bridge-served FFI calls returns from every `run-fuel` invocation. Each invocation executes at most one raw action and one bridge step. Repeated invocations reach the reference result. Diagnostic-only bridge input also returns after one step. Pin control-less ingress-ready dispatch to the budgeted round. For a pure active computation lasting at least `n` instructions, compare with `n` single-instruction steps; an earlier park/halt ends the comparison there.

12. **Fencing.** Lower a running snapshot with `{:fenced true}` and exercise direct `vm-hot`, `vm/step`, `vm/run` and `run-fuel`. Each refuses the closed gate before guest, scheduler or bridge work. Assert unchanged control/window/environment/K, unchanged ready recovery data and unchanged relift bytes. Cover `:ended` as well as `:exporting`.

13. **Ready-value portability.** The only unsupported primitive is nested in the ready outcome; register-target lower refuses before attachment. Retain the corresponding portable positive case. Test ready delivery state, outcome and restored descriptor roots independently.

14. **Landing checks.** Kondo clean; the quick tier passes on JVM, Node and Dart with every touched namespace present in logs; the slow tier runs once at landing on all three hosts. Remint fixtures from the integrated grammar. Review stops on satisfied gates, not a fixed round count.


## 8.8 Slice 6e: the cross-engine half

**Amended 2026-10-17 (any-pc).** Implemented: `lift-running`/`lower-running`
over the kernels' live fields (register `{:pc :registers :frames
:continuation :store-of}`, stack `{:pc :stack :frames :continuation
:store-of}`); `virt` and `register-window` over an explicit virtual set
(`L(p)` for a run) with nil fill outside it at the lower; `h->a`'s
`:push` stutter relabel (run states only, §4.5); the dead-prefix rule of §4.3's amendment
(`slice-window`'s `≥`, the top-take, the `:yin.k/dead-prefix`
diagnostics); `safepoint!`'s run mode (any pc inside a body);
`canonical-task` lifting an active kernel as `:running` with waits and
ready as the semantic lift reconstructs them (ready entries lowered
back by `kernel-task`, which lowers a `:running` machine into a running
kernel); backends still refuse installs. **Acceptance**: laws 4 and 5
at every aligned boundary of the 61 lane cases — one root id on three
lanes with the stack lane's `:push` stutters relabelled (each stutter
lifted and compared with its successor's site and root), taken and
untaken branches pinned (row 15), and six ordered pairs each continuing
to the reference under §3.6's full-payload refusal oracle, judged per
receiving lane (exact refusals, zero attaches; exact transfers,
byte-equal relifts; a boundary's outcome into a lane is the same
whichever lane it leaves); measured counts per case are pinned on the
JVM; ordered operand multiplicity pinned on a hand-built `(g x x)`
program; the `:push` relabel pinned on one row; `layout-refusals` per
§8.3 item 12, with a running kernel at the pc one past its last image,
and a delivery entry naming a `:push` as its parking instruction, both
refusing `:yin.k/not-at-safepoint`; item 6 resolved by the static check
— no lane-fragment tail call has a non-empty ambient layout (five
reachable tail-call pcs over the fragment's distinct vectors) — with the
rule pinned end to end on two hand-built operand-tail programs (the
prefix in the current region, and in a return frame's slice), laws 4
and 5 holding at their every boundary, and the repeated-register
agreement pinned on a hand-built layout (the literal `[:call r f [x x]]`
vector is refused by §3.4 item 6, so no well-formed vector exercises
it); the fragment unchanged (rows 7-12 and 17 excluded; row 4's
retained-FFI boundaries refuse `:retained-ffi` into both backends, and
its boundaries before the request is retained transfer). No kernel
edit: `layout.cljc` only.

## 9. Verification honesty

- **Hand-derived numbers.** `saved(4, 2) = {1}` and `L(5)` on the worked
  example are the phase-3 tests' (`analysis_test.cljc:131-139`). The
  stack slices `[f]` at the inner call of `(f (g x) y)` and at `(g)` in
  `(f (if c (g) 2) y)` were derived twice from the §4.3 walk and agreed
  with the reviewer's derivation; the register `phys(1) = 0`, `phys(2) =
  3` follow from phase-5 §6.3 row 2, whose pinned `live` at pc 3 is `[0]`
  (`{T0,T3}` minus the destination `T3`), the same set §4.2 step 4 and
  §5.6 item 3 compute by subtracting the virtual `rd 2` before mapping.
  The multi-frame bases in 6c item 6 (`2, 4` for the expression alone;
  `0, 0, 0, 2, 4` as run under the harness's bindings) were derived
  twice from the kernel's call arm (`stack.cljc:716-733`, base = height
  after popping callee and operands); the first pass forgot the pushed
  operator, and revision 2's lowering rule used the height **before**
  each slice, which the round-2 review caught and §4.3 now states as
  cumulative through the slice. Nothing was run.
- **Claims that are checks, not theorems**: the stack operand slice
  equals the saved set at non-tail safepoints (§5.6 item 5); the non-push
  H ↔ A correspondence (item 4); `virt` single-valued at reachable
  boundaries (item 3); layout `E` = evaluator `E` (item 6); the tail
  lowering's unused fields are unobservable (item 8); the slices
  partition the stack (item 9).
- **What is not claimed**: that kernel state round-trips (shadowed outer
  positional values are nil-filled; the law is on canonical frames);
  that every kernel reconstructs canonical frames (two are named; the
  walker is excluded); that today's stack kernel delivers a tail effect
  through `K` (it does not; 6c-pre adds it).
- **Private access.** Every function a test names is public; no `#'`.
- **Unverified.** 4b stage 2 is not landed: rows 3-4, 7-12, 17, 24 are
  written against the evaluator design's §1.4-1.6 shapes and the stage-1
  branch and must be re-read when stage 2 lands. The seam of §4.3 is a
  specification of a change to `stack.cljc`, not a reading of code that
  exists. **Amended after 6b (2026-10-13):** 4b stage 2 landed before
  6b, and rows 3-4, 7-12, 17 and 24 now run on the reference lane (89
  labelled row × segment executions, 85 distinct builder/wrapper setups
  since rows 3 and 24 share one builder by design); rows 10-11 were
  re-read and corrected (§5.2, B1). The 6b harness ran on the JVM only;
  Node and Dart execution of `rows.cljc`'s transport and the new harness
  file remain the three-host gate of §8.2 item 9, not established by the
  reviews.
- **Amended after 6c (2026-10-13).** 6c-pre landed (`296f75fd`) and the
  stack kernel delivers a tail effect through `K`; §4.3's seam is now a
  reading of code. The layouts' counts are the tests' own assertions,
  run on the JVM by the engineer and by codex (quick: 60 tests, 3,615
  assertions; slow: 4 tests, 2,419 assertions, before the fix round):
  law 4 on 16 bare and 61 full cases over three distinct lanes, law 5 on
  92 and 350 transfers, 128 `E` comparisons, four tail cases, more than
  twenty slice partitions and more than fifty reachable boundaries. The
  rulings' claims that are checks, not theorems: that C holds an
  ambiguous dead preimage (asserted, §8.3 item 12); that the alpha pair
  derives one R and one H (asserted, item 14). **What this document has
  no record of:** a Node or Dart run of `layout.cljc` and the 6c tests.
  Codex read the pinned ClojureDart source and found `satisfies?`,
  `memoize` and `binding` implemented; that is a source reading, not a
  run. The slice merged (`781a9ba8`) and §8.3 item 13 names the gate;
  the sources this revision read (the engineer report, the codex review,
  the rulings, the merged diff) contain no three-host result, so this
  document does not claim one.
- **Limits of the retained backends.**
  **Amended after 7 (2026-10-14)** — Reason: the kernels remain after phase 8 on the same fragment (§4.6).
  Image collision
  (§4.1), free-environment procedures on the native lanes (§6.3),
  primitives in the register kernel's payload (§4.2), the unique
  dead-preimage fallback of `virt` (§4.2) and the shared fragment (§5.3)
  remain limits of the retained de Bruijn backends after phase 8; none
  is a limit of the carrier or of the canonical semantic kernel,
  and each is pinned by a refusal test rather than left as an
  unobserved gap.

---

**Amended 2026-10-16 (any-pc, owner invariant 2026-10-17)**

**Claims that are checks**: `L(p)` is sufficient at every boundary (law 1 at every step); injectivity over `L(p)` at every pc (item 3); the ordered stack equation at every pc (item 5); the dead-prefix rule (law 1 after a tail transition); the refusal oracle equality (6e). None is a theorem about all programs; each is a gate on the corpus.

## 10. Findings and proposed amendments

**Historical amendment record.** The phase-specific findings below retain their original text. The any-PC rules in §§1–7 and §8.6 supersede earlier exhaustive state, context and eligibility lists.

Reviewer rulings (codex gpt-6.1-sol, 2026-10-10): F1, F4, U2, U4, U5,
R1, R2, R7, C1 **ACCEPT**; F2, F3, U1, U3, U6, R3, R4, R5, R6, R8, R9
**ACCEPT WITH CHANGES**, applied below; dropping `:yin.k/engine`
**ACCEPTED**; the merged-`E` free environment **REJECTED**, replaced by
§4.4/§6.3.

### 10.1 To the frozen design (`yin.vm.semantic.md`)

**F1 — §4.2 row names (ACCEPT).** Replace "`:regs` becomes `[segment-A
pc env-cref window-cref k-cref deliver-D]` for an `act` and a sibling
`:tail-state [segment-A site k-cref deliver-D]` is added" with: "the
frame rows are `:act [segment-A pc env-cref window-cref k-cref rd]` and
`:tail [segment-A site k-cref]`, the kind being the delivery
discriminant, so `deliver` is not a slot; `:window` is a keyed row
`[r₁ V₁ r₂ V₂ …]` sorted by `r` in which a nil-valued register is a
present `[:lit nil]`; `:kframe` is `[segment-A pc env-cref window-cref
rd]`; `:stack` and `stack-base` are removed; the root gains `free-env`,
the task's initial free environment (continuation design §2, §6.3)."

**F2 — §4.2 the validator (ACCEPT WITH CHANGES).** After "and pending
compatibility with `reason`" insert: "The validator is the pure function
`(defect frame code context task)` of `yin.vm.semantic.continuation.md`
§3: `task` is decoded task context (the parked key, the live FFI call
ids, the free environment), never an engine; its rules are ordered,
structural checks first, current-window rules on `act` only, return
windows separately, and every `E` is checked to agree with the task's
free environment off the lexical names; it runs after canonical
numbering, as the carrier orders it."

**F3 — §4.3 foreign engines (ACCEPT WITH CHANGES).** After
"`lift(lower(frame)) = frame` holds for admitted canonical frames, as
today." insert: "The register kernel pairs physical and virtual
registers pc by pc from `v` and `R(v)`, recovers every destination from
A's call (its `dest` is physical), and resolves each return frame's
image and relative pc independently; the stack kernel corresponds the
non-push instructions of `H(v)` with `v`, slices its operand stack
`[previous-base, base_i)` per return frame and `[last-base, count)` for
the current activation, and needs a tail-completion seam to deliver a
tail state through `K` (continuation design §4.3, slice 6c-pre); both
kernels' positional frames become `E` through the owner chain and
`bind-params`, with the task's free environment carried explicitly. The
AST walker is not a frame reconstructor."

**F4 — §10 phase 6 (ACCEPT).** Replace "the row carrier amended" with
"the row carrier amended to body version 3 with one profile,
`{:yin.code/contract "v4" :yin.k/version 1}`, carrying canonical vectors
by A, `:yin.k/trees` and the task's free environment, never a native
image (continuation design §2, §6)"; after "through their layouts"
insert "(the register and stack kernels, the latter with its
tail-completion seam; the walker is excluded)".

### 10.2 To UCF (`yin.vm.universal-continuation-format.md`)

**U1 — §7.3.4 `:505-527` (ACCEPT WITH CHANGES).** Replace `:511`
"`:yin.k/carried` carries vectors, not batches." with "`:yin.k/carried`
and the body's code map carry **register-shaped canonical vectors keyed
by A**, never batches, never datoms projected from a vector, and never a
native stack or register image: a receiver of either native kernel
derives its image from A (derivations design §2-3). `:yin.k/requires
:yin.k/trees` maps every address in `:yin.k/segments` to the non-empty
set of tree addresses the emitter's `source-index` held for it
(derivations design §9.2)." **Replace `:513-527`** ("Loading a fetched
vector has two paths … nothing may depend on which path a conforming
engine takes.") with: "Loading a fetched vector has **one path**: the
direct decode of the canonical vector into an image through the one
validator (`yin.vm.semantic.code/well-formed?`). The projection
path (vector → datoms → loader) is retired (owner ruling 2026-09-18;
`yin.vm.semantic.md` §3.4). The conformance obligation is
that the two AST projection lanes and the direct vector load yield one
image and address (`load_paths_test.cljc`)."

**U2 — §7.4.1 (ACCEPT).** At its head: "Superseded for the
register-shaped machine by `yin.vm.semantic.md` §4.1 (two
state columns, `act` and `tail`, and the `deliver` record); the table
below describes the stack-shaped machine, which leaves at that design's
phase 8." Replace the `:yin.k/frame` example with §1.2's keyed form.

**U3 — §7.4.2 (ACCEPT WITH CHANGES).** Replace, in the opening
paragraph, "absolute stack depth and environment key sets at a pc depend
on the *activation* — a closure enters with the caller's residual
operand stack and an environment formed from its captured environment
plus arguments (`apply-call`), and §2.6 enforces no equal stack heights
at joins, so the same pc is legitimately reached at many depths" with
"the saved window's values, the environment's bindings and the depth of
`K` at a pc depend on the *activation*; the same pc is legitimately
reached under many windows and environments". Replace "E, St, and every
K frame's `:stack-base` and env travel in the value" with "E, the saved
window, and every K frame's window, `rd` and env travel in the value, as
does the task's free environment". In the static map replace
`[sp :yin.safepoint/stack-effect -1]` with `[sp :yin.safepoint/def #{…}]
[sp :yin.safepoint/use #{…}] [sp :yin.safepoint/live #{…}]`; in the
rules replace "`stack-effect` and `lexically-required` are derived from
the canonical vector alone, by the same static walk the linearizer
performs (§5.3). They are identical for every engine and checkable
against the reference machine by a conformance test that runs the
segment to each safepoint and compares the observed deltas and E-reads.
Absolute depth, activation bases, captured environments, and physical
layouts are not claimed" with "`def`, `use`, `live` and
`lexically-required` are derived from the canonical vector alone
(`yin.vm.semantic.analysis`). They are identical for every
engine and checkable against the reference machine by a conformance test
that runs the segment to each safepoint and compares the saved window's
key set with `live − {rd}` and the E-reads. Window values, environments,
`K` and physical layouts are not claimed"; replace "physical slot →
canonical slot" with "physical location → virtual register
(continuation design §4)".

**U4 — §7.4.3 (ACCEPT).** After "Three rules make these resumable":
"A pending rides on an `act` state (write-result delivery) or a `tail`
state (through-return delivery); the variants are unchanged, and
`:yin.k/registers R` of the version-2 amendment is the `act`/`tail`
state of the frozen design §4.2."

**U5 — §7.6 (ACCEPT).** In §7.6.1's example add
`:yin.k/trees {A #{tree-address …}}  ; §7.6.1a` and a new §7.6.1a with
the phase-5 §9.2 UCF row text verbatim, including its completeness
clause (absent or empty is incomplete).

**U6 — §7.11 (ACCEPT WITH CHANGES).** In "Safepoint conformance
harness" replace "including `lift ∘ lower = id`, the static
`stack-effect`/`lexically-required` checks" with "including `lift ∘
lower = id` on canonical frames, the static `def`/`use`/`live` and
`lexically-required` checks, the frame validator on every frame, and
**cross-engine canonical equality**: one program at one safepoint lifts
to row sets with equal root ids after the harness's specified
re-emission (continuation design §5.5) from the semantic kernel
and from the stack and register kernels through their layouts, the stack
kernel with its tail-completion seam". In §7.11.1 "Safepoint
reconstruction", replace "Separately prepare two independently
implemented UCF engines" with "Separately prepare the three engines
intended to reconstruct canonical frames (the semantic kernel;
the register kernel; the stack kernel after its tail-completion seam)"
and "the frame, pending wait, next result, and effect trace must agree
in both directions" with "the canonical row set after re-emission, the
pending wait, next result and effect trace must agree across all three
and in every lowering direction".

### 10.3 To the row-carrier draft (`yin.vm.ucf-transport-tuples.md`)

The inventory below is every clause the grep of the draft for
"legacy", "version 0/1/2", "semantic profile only / semantic-only",
"does not translate", "unchanged / do not move", "other profile",
"registry" found operative; each is replaced or deleted.

**R1 — §2 `:50-81` (ACCEPT).** Replace the section with: "**Body
version 3, one profile.** The carrier is body version 3 and reads no
other version (owner, 2026-10-11: no legacy): a body of version 0, 1 or
2 is `:yin.k/profile-mismatch` at the gate and is never normalised or
upgraded. The profile map is `{:yin.code/contract "v4" :yin.k/version 1}`;
there is no engine key, because the frame is the canonical
register-shaped machine's and the executing kernel is the receiving
composition's choice: the stack and register kernels lower the same rows
through their layouts (`yin.vm.semantic.continuation.md`
§2.1, §4). Any other profile map is `:yin.k/profile-mismatch`."

**R1a — status paragraph `:18-20`.** Replace "Body versions 0, 1 and 2
keep their grammar, readers and bytes; this document proposes a fourth
body version whose carrier is rows" with "This document specifies body
version 3, the one carrier; older bodies are not read."

**R2 — §5.2 `:298-317` (ACCEPT).** Replace with continuation §2.3's
table and notes.

**R3 — §5.1 `:287-296` (ACCEPT WITH CHANGES).** Replace the `:reified`
row with `| :reified | act-cref | a captured continuation: the decoded
act state of frozen §4.2, no pending |`; replace "`{:yin.k/tag
:yin.k/frame :yin.k/registers R}`" and "`{:yin.k/tag :yin.k/frame
:yin.k/parked-id P}`: resolves in the task's parked table; never
duplicates `R`" with the keyed `act` state and "resolves in the task's
parked table; never duplicates the state"; replace "A reified value's
registers are validated in captured-value context (amendment §7): its pc
must be a `:current-continuation` site's successor, not a wait or park
safepoint" with "A reified value's act is validated in context
`:captured` (continuation §3.2 P3): its `pc−1` is a `:current-continuation`
and it has no pending"; and replace "a reified value becomes a
receiver-owned native continuation from `R`" with "from its act".

**R4 — §5.4 `:361` (ACCEPT WITH CHANGES).** Replace `:parked-table |
P₁ regs-cref₁ P₂ regs-cref₂ …` with `P₁ state-cref₁ …` (an `:act` or a
`:tail`), and add: "Validation of a parked state reads decoded task
context, never an engine: the table key `P` and the set of call ids the
task's live `:ffi`/`:ffi-request` pendings carry are the `task` input of
`frame/defect` (continuation §3.1), under which an `:act` at a `:park`
site is an explicit park and an `:act` at an `:ffi-call` site or a
`:tail` at a tail call under a live call id is FFI bookkeeping."

**R5 — §5.5 `:383-401` (ACCEPT WITH CHANGES).** Replace the root with
continuation §2.6 (adds `free-env-cref`; `contract` is the one profile
map; `code` is `{A "v4"-vector}`; `requires` gains `:yin.k/trees`); in
`:394` replace "`requires ; plain: UCF §7.6.1's map, unchanged`" with
"`requires ; plain: UCF §7.6.1's map plus :yin.k/trees`".

**R6 — §8 step 7 `:661-667` (ACCEPT WITH CHANGES).** Replace with:
"**Frames.** Every `:act`, `:tail`, `:kframe`, `:reified` and
parked-table state is decoded to its keyed frame and judged by
`yin.vm.semantic.frame/defect` with its context (`:wait`,
`:captured`, `:parked`) and the decoded task context (parked key, live
FFI call ids, the root's free environment); a defect is
`:yin.k/undecodable` with the rule's kind and the row path (continuation
§3). This step runs after canonical numbering (step 6) and before
pending validation (step 8)."

**R6a — §8 step 1 `:618-623`.** Replace "then every profile map against
the registry (only the semantic profile is supported, §2)" with "then
every profile map against the one profile map of §2".

**R7 — `:63-68` and §10.4 `:774-777` (ACCEPT).** Delete "Code stamps
`"v3"`, `"b2"`, `"r2"` and the inner version 1 do not move: this is a
change of carrier, not of instruction, scheduler, or value semantics,
exactly the distinction the amendment draws between its versions and
executable code." Replace §10.4 with: "**Contract stamp.** The code
contract carried is `"v4"`, the register-shaped machine's; the carrier
version is 3; the two are independent, and neither names a legacy
value."

**R8 — §9 `:737-746` "Legacy correspondence" and §10.8 `:797-801`
(ACCEPT WITH CHANGES).** Delete both: there is no legacy reader and no
normalisation tool. In §10.3 `:769-773` delete "Versions 0, 1 and 2
remain compatibility inputs. One canonical nested projection of version
3 is kept for single-message channels and must stay exactly mutually
inverse with the rows." and "Retiring old readers is an owner
version-support decision", leaving "Rows are the carrier authority."
In §9's law list delete "Exact mutual inversion is required only between
version 3 and its one canonical nested projection (§10.3)".

**R9 — §10.6 `:782-788`, §10.7 `:792-796`, §2 `:70-81`, §10's
proposals list `:821-834`, "Still open" `:836-837`, §11 `:839-852`
(ACCEPT WITH CHANGES).** §10.6, **whole item**: replace its opening
"**Foreign-engine value profiles.** Required before any implementation
brief claims heterogeneous execution conformance, and not drafted here"
with "**Foreign-engine value profiles.** Required before an
implementation brief claims execution conformance for an engine
**outside the three repository lanes** (the semantic kernel and
the stack and register kernels, whose reconstruction is the layouts of
`yin.vm.semantic.continuation.md` §4 and needs no value
profile), and not drafted here"; keep its list of what such a profile
must cover; and replace "version 3, like version 2, does
not translate between kernels" with "version 3 carries the canonical
frame; a stack or register kernel reconstructs it through its layout
(continuation §4), which is heterogeneous reconstruction, not
translation between native formats; foreign-engine *value* profiles for
engines outside this repository remain a separate document". §10.7:
delete ("there are no other profile tables"). Proposals list: delete
"semantic-only initial scope"; add "one engine-less profile (continuation
§2.1)" and "the explicit root `free-env` (§6.3)". "Still open": replace
with "none beyond the work-bound limits (§10.10)". §11: replace "Code
identity, the contract stamp and profile registry, safepoints," with
"Code identity (A), safepoints,"; delete "versions 0-2" references.

**R10 — §5.5 `:421-426` custody paragraph.** Replace "a version-3 fork
root is admitted exactly as a version-0 body is, proposes nothing, and
is never upgraded to custody" with "a fork root proposes nothing and is
never upgraded to custody; an exclusive-only receiver refuses it as
`:yin.k/profile-mismatch`".

**R11 — §3 `:115-134`.** Keep verbatim, including "**body version 3
uses BLAKE3 permanently**" and the per-id algorithm check (the reviewer
asked that this explicit rule survive).

**U7 — scope and precedence of UCF's historical legacy-body clauses
(new).** UCF keeps clauses that describe bodies the row carrier never
reads: §7.2.1 (versions 0 and 1, `:166-321`), the version-0/1 custody
gate and fork rules (`:234-290`), §7.4.3 r5's version-0 notes (`:966-970`:
"A version-0 entry without a phase reads as `:running`", "The landed
version-0 reader breaches both"), §7.7.4's `nil`-header version-0 lift
and recovery of legacy fork bytes (`:1781`, `:1806-1807`, `:1844`), and
§7.11.1's version-0/1 interoperation rows (`:3051-3057`, `:3095-3096`).
Insert at the head of §7.2.1: "**Scope (2026-10-11).** Body versions 0,
1 and 2 and every clause of this document that admits, reads, recovers
or interoperates with them are **historical**: they describe the
stack-shaped machine's handoff, which leaves at
`yin.vm.semantic.md` §10 phase 8. The row carrier of body
version 3 (`yin.vm.ucf-transport-tuples.md` as amended by
`yin.vm.semantic.continuation.md`) reads no other version and
takes **precedence** over any sentence below that implies otherwise; a
conflict is resolved in its favour without further amendment. **Not
historical**: the custody, fencing and operation-id semantics (§7.7.8,
§7.9, §7.4.3's operation ids) travel in version 3 as the root's
`custody` slot (`yin.vm.ucf-transport-tuples.md` §5.5) and stay
operative; where those sections say 'a version-1 body' read, for version
3, 'a root whose custody policy is `:yin.k/exclusive`', and where they
say 'a version-0 body' or 'fork', 'a root whose policy is `:yin.k/fork`'
(§5.5's table). The code stamp `{:yin.code/contract "v3" :yin.k/version
0}` wherever this document shows it (§7.2's example `:121`, §7.2.1
`:183`, §7.3.3 `:232-236`) is the stack-shaped machine's; the
register-shaped machine's contract is `"v4"`
(`yin.vm.semantic.md` §6), the one the version-3 profile map
names, and §7.2's envelope example is superseded for version 3 by the
row carrier's wire unit (`yin.vm.ucf-transport-tuples.md` §3, ids under
Jing's default digest)." The same grep (`legacy`, `version-0`,
`version 0`, `versions 0`, `version 1`, `recovery`) found, besides the
clauses above: the export-recovery object's version-1 format
(`:1789-1807`), which is phase 7's to re-base on the row carrier and is
named in §8.4 as an entry condition, not amended here; publication
failure recovery (`:1741`), completion recovery (`:2417`) and the
ledger's "legacy destructive readers" (`:2774-2775`), which concern the
driver, the ledger protocol and the ledger reader, not a body version,
and are untouched; and the status paragraph `:39-42`, covered by the
scope statement's "every clause".

### 10.4 To code-as-tuples (`yin.vm.code-as-tuples.md`) §7.2

**C1 (ACCEPT).** Replace the first paragraph with: "The vector-to-datoms
projection path is **retired** (owner ruling 2026-09-18; frozen §3.4
'One vector validator'). There is one load path for a vector, the direct
one, and two AST projection lanes (datom lane, row lane) that produce
the vector; all three feed one validator
(`yin.vm.semantic.code/well-formed?`). UCF §7.3.4's projection
path is superseded accordingly." Replace obligation 2's second sentence
with "loading the vector produced by either AST lane and the vector
loaded directly yield the same image and address (`load_paths_test.cljc`)",
and obligation 3's "both paths refuse with the same defect" with "the
direct vector load refuses with the published defect (rule and pc) for
every rule of the frozen §3.4". Obligations 1 and 4 stand.

### 10.5 Checked and consistent (no amendment)

Frozen §2.4 delivery shapes against the 4b branch; frozen §4.1 two state
columns; the evaluator design §1.3-1.6; the draft's §3, §4, §5.1 (except
`:reified`), §5.3, §6, §7 and §8 steps 1-6, 8-12; v2 amendment §2's `E`
shape; the phase-5 §9.2 `:yin.k/trees` text; the register kernel's
`:return-result` delivery (`register.cljc:458-468`).

### 10.6 Amendments after slice 6b (revision 4, 2026-10-13)

Each item names the clause, what changed, and why; the clause itself
carries the same marker. Sources are the engineer report's design bugs
(B1-B4) and deviations (1-10) and the three codex reviews' rulings.

| # | Clause | What changed | Why |
|---|---|---|---|
| B1 | §5.2 rows 10-12, the install paragraph; §8.2 item 3 | row 10 is child `:blocked`, phase `:parked`; row 11 is the `:running` wire phase set by hand on row 10's machine; the lower's install contract (phase set, response and image, child `:blocked`) is stated | `advance-install` retains a blocked child at `:parked` and links a halted or parked one in the same sweep; the promised states were unreachable |
| B2 | §5.2 wrapper paragraph, row 21 | the mechanical wrapper rule has one stated exception: row 21 under `:depth-3` answers `[3 [2 [1 1]]]` | `nest`'s parameter is `x` and shadows the free `x` before `f` is created; the reference run itself answers 1 |
| B3 | §5.2 refusal rows; §8.2 item 7 | the `:tail` fourth slot is `:row-shape` at structure step 2, and the tamper is a raw row edit with rehash | the row table fixes `:tail` at three slots before S1 runs; `emit` drops extra abstract slots silently |
| B4 | §7.1 | the lift's order is quiescence, kind, free environment, walk, code, dependency closure, trees, task, frames, emit | segments and contexts are known only after the walk |
| D1 | §6.1, §8.2 depends-on | `:source-index` is a lift input from the composition | the kernel holds no source index |
| D2 | §7.1 | refusals are thrown `ex-info` | consistency with `decode`/`emit` |
| D3 | §2.5, §2.6, §7.2 | custody nil at the root; non-nil is `:yin.k/unsupported :custody` at step 10 | exclusive/holder is phase 7; superseded by §10.8 P1. **Amended after 7 (2026-10-14)** — Reason: 7a implements the by-kind custody slot |
| D4 | §2.5, §5.2 row 17 | linked registry entries do not travel; the receiver's registry is a composition prerequisite and is kept as given | no registry slot in the row table |
| D5 | §7.1, §7.2 | discharge is per context over live code (`live-pcs`), answered by `E`, the one active store, primitive, installing export, registry; lift only | codex F3 (union over stores was unsound) and F6 (whole-body scan refused valid states) |
| D6 | §2.5 | the store slice is the whole root store; every parked record travels; module stores by reachability | conservative and what the code does |
| D7 | §6.3 | the retention seam landed; the lift reads the kernel's `free-env` field by default, the option overrides, E2 catches a disagreeing override | 4b stage 3 landed before 6b |
| D8 | §7.2 | the lower's four stages, each across the whole tree before the next, all before attachment; the refusal-kind table including `:yin.k/stores` | codex F1, F2, F4, F7, F8: every carrier defect must be a named refusal before any attach |
| D9 | §5.2 row 18 | the fixture's module store is symbol-keyed and hand-built | codex asked for the synthetic label |
| D10 | §9 | 4b stage 2 landed; rows 3-4, 7-12, 17, 24 run; three-host gate still open | honesty |

**Note on `handoff.cljc` (no edit).** The engineer report said
`export-task` builds installing names as `(symbol (str m "/" x))`, a
nil-namespace symbol that never equals `m/x`. Read at
`handoff.cljc:1480-1488`, the code is `(symbol (str (name m) "/" (name
x)))`. The one-argument `symbol` of Clojure, ClojureScript and the
pinned ClojureDart core splits its string at `/` into namespace and
name, so this yields the qualified `host.mod/f` with namespace
`"host.mod"`, equal to `'host.mod/f`; codex round 1 evaluated it on the
JVM and found the same. The claim is withdrawn; the old form is not a
bug. `rows.cljc`'s `installing-names` uses the two-argument `(symbol
(name mn) (name x))`, which is equivalent. `handoff.cljc` is not edited
by this phase (§8.5).

### 10.7 Amendments after slice 6c (revision 5, 2026-10-13)

Each item names the clause, what changed, and why; the clause itself
carries the marker "**Amended after 6c (2026-10-13)**". Sources are the
engineer report's deviations (1-8) and design defects (D1-D5), the codex
review (F1-F6, D1-D5) and the Architect's rulings (R1-R6), whose
wording is applied verbatim where a ruling says so.

| # | Clause | What changed | Why |
|---|---|---|---|
| R1 | §4.1 | A stays the identity under image collision; a de Bruijn receiver refuses `:yin.k/unsupported :ambiguous-image` before attach, a de Bruijn source refuses `:yin.k/non-portable :ambiguous-image` before serving; no enumeration order selects an A; the harness scopes `kernel-space` to the vectors a world projected | codex F2: `(fn [x] x)` and `(fn [y] y)` have distinct A, one R and one H; both kernels dedupe attaches by checksum; editing both kernels' offset tables is outside the retained backend fragment (§4.6); **Amended after 7 (2026-10-14)** — Reason: the owner retains both backends |
| A1 | §4.1 | the public API as landed: `segment`, `space`, `lift-state`, `lower-state`, `canonical-task`, `kernel-task`, `lower-defect`, `lift-task`, `lower-task` and the rest; `lift-act`/`lift-tail`/`lower-act`/`lower-tail` superseded | engineer deviation 1, accepted by codex: one dispatching entry per direction |
| R5 | §4.2, §8.3 item 12 | the unique-preimage rule for `virt`: live-out* first, else the sole named preimage for validation only, else `:yin.k/non-portable :register-layout`; item 12 permits that refusal for an ambiguous tamper | engineer deviation 7, codex D3 and M9 (take the first preimage survived) |
| D4 | §4.2, §7.2, §8.3 item 6 | a primitive is an admitted carrier value; the register kernel refuses one in its payload `:yin.k/unsupported :continuation-registers` with the path before attach; "is no carrier value" withdrawn; four refusal fixtures | engineer deviation 8, codex D4: the limit is the receiver's, not the carrier's |
| S1 | §4.3 | 6c-pre landed; the tail lowering shapes stated as `lower-state` emits them | the seam was a specification; it is code |
| D2 | §4.3 | the retained-FFI response-pair seam specified as slice 6c-post-D2, gated by the owner, not a prerequisite of 6c; `unlowerable` empties and the counts rise to 366/96 when it lands | engineer D2, codex probe 3: the kernels' re-park names their own call pair; a kernel edit outside the retained backend fragment (§4.6); **Amended after 7 (2026-10-14)**, reason: owner retains backends |
| D5 | §5.2 row 21 | the expectation on every lane: `E` binds `x` to 1 under `:depth-3`, the free environment keeps 99, pinned separately | codex D5; the cross-engine lanes could have hidden a lane agreeing on one and not the other |
| R2 | §5.3, §5.6 item 8, §8.3 items 10-11 | the native lanes run the shared fragment: 61 law-4 cases over three lanes, 350 law-5 transfers (92/16 in the quick tier); tail acceptance rows 6 and 20 on the native lanes; the reference lane keeps 89/534 | engineer deviations 3-4, codex F3; link and install rows need 2-3 days of code outside the retained backend fragment (§4.6); ACCEPTED by the owner on 2026-10-13 (6c Q1); **Amended after 7 (2026-10-14)**, reason: retained backend fragment |
| D1 | §5.5 | positions carry identities; the renaming operation, injective correspondence, separate descriptor equivalence and five negative controls; three distinct lanes asserted per case | engineer D1, codex F4, M11 and M12 survived, probe 4 |
| R3 | §6.3, §7.2 | a closure or continuation in the free environment is refused on the native lanes both ways (`:free-env-value`), admitted by the carrier and the reference lane; nested values included | codex F1: no finite canonical form through the shared free environment; refuse, do not convert |
| L1 | §7.1 | the lift from a de Bruijn kernel is `rows/lift-task` over `canonical-task`; the refusals it raises before serving | engineer deviation 2 |
| L2 | §7.2 | the lower is decode, `lower-defect` (collision, installs, free-env procedure, register primitives, in that order, before any attach), `rows/lower-task` into a scratch machine, `kernel-task`; the audit allowlist entries | engineer deviation 2; codex M8; the audit's precedent of `restore-task` |
| T1 | §8.3 items 14-17 | the fix round's tests named as acceptance | R1, R3, R4, F6, the audit |
| C1 | §8.5 | 6c edited no kernel; 6c-post-D2 is the one named kernel edit, its own slice | honesty |
| V1 | §9 | counts as asserted; no three-host record in the sources read; the limits of the retained backends | honesty; **Amended after 7 (2026-10-14)** — Reason: the backend limits remain after phase 8 (§4.6) |

---

### 10.8 Amendments after phase 7 (2026-10-14)

**Amended after 7 (2026-10-14)** — Reason: inventory the row custody, recovery and acceptance amendments against code.

| # | Clause | What changed | Why |
|---|---|---|---|
| P1 | §2.5-2.6; tuples §5.5 | fork nil in every row; exclusive custody by kind, only five keys | owner fork ruling and `custody-baseline` |
| P2 | §7.1 | header/enrollment checks follow walk/frame validation; retained serves survive retry | `lift-task` order, 7a D4 |
| P3 | §7.2; tuples §5.4, §8-9 | three per-tree stages, custody once, then attach; grant uses pack address; install derivation checks | 7a/7b code and zero-attach law |
| P4 | §7.2; UCF §7.7.4 | fenced lower; recovery format 2 with one pack and local metadata | no second snapshot, no runnable recovery |
| P5 | tuples §7; §11 | sorted emission, pack/root identity split, same-root reordered equal variant | root-inclusive baseline; 7a D2 |
| P6 | §8.4; UCF §7.11.1 | entry conditions met; Stage D/E port and remaining host gates distinguished | 7d report is JVM evidence only |
| P7 | §11 | 6b Q3 closed; Q4 op-ids closed, correlation left open | implemented checks, no invented validation |
| P8 | §4.6, §5.3; UCF §7.11 | canonical kernel and retained backends, documented fragment | owner ruling: kept after phase 8, not widened |

## 11. Owner questions

Revisions 2-3 left none: the reviewer accepted the engine-less profile
and rejected the merged-`E` free environment. **Revision 4 (after 6b)
lists the design choices the merged code made that no ruling covers.**
Each is stated as the code behaves today; phase-7 closures below leave
all other questions open.

**Amended after 7 (2026-10-14)** — Reason: 7a D2 corrects the plan's equal-baseline variant claim.
The baseline includes `:yin.k/root`. Re-emitting an id-counter change
changes that root and is `:variant-conflict`. The equal-baseline encoding
variant retains the same root with another row order. `emit` sorts rows;
`decode` still accepts unsorted packs, so one root/one pack is an emission
property, not a reader restriction.

- **Q1, install children, the parked-child scenario.** The engine never
  retains an install child at a quiescent safepoint except `:blocked` at
  phase `:parked` (§5.2 B1). The carrier still admits phase `:running`
  (never engine-reachable at a safepoint) and the lower refuses any
  child kind but `:blocked`. Should the row table and both checks
  narrow to the one reachable state (phase `:parked`, child `:blocked`),
  or does the owner want a reachable parked-child install scenario,
  which is an engine change (`advance-install`), with row 11 rewritten
  to it?
- **Q2, census agreement on lower.** The draft §8 step 9 requires the
  carried `requires`/`code` to agree **exactly** with a census recomputed
  from the rows (a declared segment not reached, or a reached segment
  not declared, is `:yin.k/undecodable`). `check-dependencies!` checks
  only that `:yin.k/segments` equals the key set of `code`; a vector
  carried but referenced by no row is accepted and attached. Accept the
  narrower check, or require the recomputed census?
- **Amended after 7 (2026-10-14)** — Reason: 7b implements the owner-required install checks before attach.
  **6b Q3, CLOSED as implemented: install response verification.**
  `check-install!` verifies phase, response status, manifest module/export
  shape, image admission and blocked child, then requires a non-nil parent
  equal to response `:yin.link/id`. The manifest's semantic derivation
  address must hash the response's derivation record; its input equals
  the manifest tree and its output equals the image vector's A.
  This verifies response self-consistency. Amended 2026-10-16 (R4): the
  lower additionally requires the receiver composition's explicit
  `:yin.k/admitted-modules {name manifest-address}` before attaching any stream,
  and the lift never carries sender admission evidence; a carried admission
  field is refused as `:yin.k/undecodable :install-response`.
- **Amended after 7 (2026-10-14)** — Reason: 7a implements the op-id grammar; correlation checks remain narrower.
  **6b Q4, CLOSED for operation ids; correlation agreement remains open.**
  `custody-baseline` checks retained put/FFI-request/link-request ids
  throughout the tree: exact sequences below next-op-seq, uniqueness,
  origin required, and no id naming the root's own occurrence. Fork trees
  refuse any id. Lower checks protection/enrollment in both directions
  before attachment. The broader cursor-cell stream, envelope-id and module
  correlation agreement promised by draft §8 is not implemented as a
  general check; Q3's install-parent agreement is implemented.
- **Q5, closed as C (2026-10-16, module-pins I-8).** Both pin maps
  always travel in `requires`; the lower checks exact entry address keywords
  (or nil for hosts), and profile keywords for used host exports, before attach.
  Missing keys are `:yin.k/undecodable :requires`. Terminal skew precedes
  exhaustive missing discovery, including receiver admission for install
  children. Fetch and the per-run lease remain composition work, outside this
  slice; the pure lower performs neither.
- **Q6, conservatism of the live-code rule.** `live-pcs` follows both
  successor edges of every branch and enters every reachable closure
  body, with no constant propagation. The harness's `(if false (fn []
  ghost) (next-of c))` case transports only because the lift happens at
  a safepoint **after** the branch (the resume pc is past it, so the
  dead arm is unreachable by edges); the same program lifted at a
  safepoint **before** the branch would refuse `ghost` although the arm
  can never run. Is "reachable by control-flow edges, never by value"
  the intended precision, accepting the false refusals it implies for
  constant-dead code?
- **Q7, host-class primitives behind a shadow.** `:host-state-primitive`
  refuses only when the name reaches the primitive step of the answering
  order; the same name bound in `E` or the active store lifts (codex
  round 2 confirmed). Intended, or should a host-class primitive name
  refuse regardless of shadowing?
- **Q8, preflight attachment cost.** `attaches?` validates and analyses
  every carried vector (and every install image) by attaching it into a
  throwaway receiver, and restore attaches it again. Codex found it pure
  and only duplicated CPU. Accept, or ask the kernel for a validate-only
  entry point?
- **Q9, Law 2 outside a fresh receiver.** The id counter is adopted as
  `max(local, carried)`; a receiver whose construction mints more ids
  than the carrier relifts to a different root (draft §9 already scopes
  the law to the fresh-receiver context). No harness case has a
  non-fresh receiver. Add one as a documented non-law, or leave it?
- **Q10, the `:running` wire phase's future.** If Q1 narrows the phase
  set, row 11 disappears from the matrix and the `:install` row's phase
  slot becomes a constant; if not, row 11 stays a synthetic case. Which?

**Revision 5 (after 6c) adds the three questions the rulings left
(`collab/1792360000000-architect-6c-rulings.report.md`); they are
numbered on their own, as 6c Q1-Q3, so the 6b numbering above stands.**

- **6c Q1, the native-lane matrix scope (changes acceptance counts).
  ACCEPTED by the owner on 2026-10-13.** The de Bruijn lanes run the
  shared fragment: law 4 on 61 of the 89 cases, law 5 on 350 of the 534
  transfers, tail acceptance on rows 6 and 20 only.
  **Amended after 7 (2026-10-14)** — Reason: the owner retains the backends after phase 8 on the same fragment.
  The canonical lane keeps the full matrix; the retained backends keep
  this fragment (§4.6).
  The owner confirmed the matrix of 61 law-4 cases and 350 law-5
  transfers as normative; §5.3 and §8.3 items 10-11 state it so.
- **6c Q2, the retained-FFI seam (kernel edit, own slice). Open.** Land
  6c-post-D2, the four-line response-pair seam in both de Bruijn kernels
  (§4.3; half a day), raising law 5 to 366 and 96 and emptying
  `unlowerable`.
  **Amended after 7 (2026-10-14)** — Reason: kernel retention does not widen the fragment.
  The question remains open; the phase-7 ruling retains the current
  fragment, so these extra transfers are not acceptance claims.
- **6c Q3, closures in the free environment (information; a decision
  only if the owner disagrees). Open.** Closures and continuations in
  the task-wide free environment are refused on the native lanes (§6.3,
  R3) and admitted everywhere else. If any real composition seeds a de
  Bruijn kernel's free environment with a closure, say so and R3
  becomes a design question instead of a refusal.

---

## 12. Portability notes for the implementer

1. **Nil-valued window keys (Dart, Node).** The window is built with
   `select-keys` and encoded as `[r [:lit nil]]`; never filter `some?`
   values; `frame-test/nil-valued-register-is-a-member` over row 22
   (`(pair nil (next-of c))`, the nil operand evaluated before the read)
   is the canary. Membership is always by `contains?`/key sets, never
   `(get W r)`.
2. **`for` over large seqs (Dart).** Row sets of the C4 modules run to
   thousands of rows; build with `mapv`/`into`/`reduce`.
3. **Reader whitespace before closers (Dart).** Pinned row sets are
   Clojure data in `.cljc`, never EDN strings.
4. **Duplicate protocol params (Dart).** `gc-children [_ _x]`
   (`srvm-phase4b:semantic_register.cljc:386`); any new protocol method
   in `layout` likewise.
5. **Multi-key `assoc` on nil (Dart).** Frames and windows start from
   `{}` (`handoff.cljc:1455-1457` records the trap).
6. **Reader conditionals.** `#?(:cljd Object :clj Throwable :cljs :default)`
   with `:cljd` first; `:clj`-only source-reading tests as `#?(:cljd nil :clj …)`.
7. **Private var access (Dart).** Every function named in §8 is public;
   no `#'`.
8. **Sorted keys.** Window and bindings rows are sorted by Jing
   canonical bytes of the key, never by host `sort` on mixed types.
9. **Numeric classes (Node).** `pc`, `rd`, `r`, `site` are exact
   integers; compare with `cbor/numeric-kind` before `=` on wire values
   (`ucf/profile-engine`, `ucf.cljc:77-91`, is the template).
10. **Fresh worktree.** `mise trust` and `npm ci` before the Node lane.

**Amended 2026-10-16 (any-pc, owner invariant 2026-10-17)**

**Q6 ruling.** Keep reachable by control-flow edges, never by value. At the pc before a constant-dead branch, `ghost` refuses `:yin.k/unsatisfied` with obligations `#{ghost}`. This is the conservative discharge rule, not constant propagation.
