# yin.vm.semantic-register-vm.continuation: phase 6, the continuation format and the row carrier

Status: **Companion design, phase 6 of `yin.vm.semantic-register-vm.md`
§10.** Written 2026-10-11 (Architect: claude-fable-5-1) from the frozen
design as amended (`8f0b9e38`), the landed 4a evaluator
(`src/cljc/yin/vm/semantic_register.cljc`) and the 4b stage-1 evaluator
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
and the amendment residue is closed (§10.3 R9, U7). The frozen document
is not changed by this one.
Subordinate to [`datom.world.md`](./datom.world.md), including its
unitarity invariant, and to
[`yin.vm.semantic-register-vm.md`](./yin.vm.semantic-register-vm.md)
("the frozen design"); format and rigor model:
[`yin.vm.semantic-register-vm.evaluator.md`](./yin.vm.semantic-register-vm.evaluator.md)
and [`yin.vm.semantic-register-vm.derivations.md`](./yin.vm.semantic-register-vm.derivations.md).

Every section marks its content **DECIDED** (an owner ruling, frozen
text, or code that exists) or **PROPOSED** (a design choice of this
document, open to the reviewer). File references are to the worktree at
`8f0b9e38`; line numbers are those of that commit; the 4b branch is
cited as `srvm-phase4b:semantic_register.cljc`, with the line numbers of
its stage-1 commit (`0f4522b9`) unless a citation says stage 2
(`bdf48cf4`, committed after revision 2; stage 2 moved the reified
payload to `:256`, `create-vm` to `:349-375` and `gc-roots`/`gc-children`
to `:485-488`, and changed none of the shapes this document reads).

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

### 0.2 The answer

A continuation is a value: one **canonical frame** of the register
machine, `act` or `tail`, with its saved window, its `K` of `:return`
frames, its environment, its delivery record and its pending wait
(frozen §2.4, §4.2), plus the task context it needs: the task's **free
environment**, store slice, module stores, parked table, cursor cells, code by
A and the tree association. **§1** restates the frame as the 4b
evaluator mints it. **§2** gives the row carrier: body version 3, one
profile (`"v4"`), Merkle content rows under Jing's default digest, the
window as a sorted keyed row in which a nil value is a present
`[:lit nil]`, `:act`, `:tail`, `:window`, `:kframe`, `:reified` and
`:parked-table` rows replacing the stale stack-shaped rows, a root
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
and the park). Two closed state shapes and one closed delivery record:

```clojure
act  = {:segment seg :pc n :env E :window W :k K :deliver {:deliver :rd :rd r}}
tail = {:segment seg :site n :k K :deliver {:deliver :return}}
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
`:yin.k/cc-<n>` numbered by the draft's §6 procedure; the root carries
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

### 2.6 The root (replacing draft §5.5 in two slots)

**PROPOSED**, with the reviewer's correction of the free environment:

```clojure
[T :task
   version          ; 3
   contract         ; {:yin.code/contract "v4" :yin.k/version 1}
   kind             ; :blocked | :parked | :halted
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
   frames           ; crefs to :wait rows, in wait order     (:blocked, :parked)
   parked-id        ; P or nil                                (:parked only)
   result           ; V or nil                                (:halted only)
   custody]         ; plain, draft §5.5's table
```

No `layout` slot and no native image: those were the positional
profiles' (v2 amendment §2, §4.1). The custody table of the draft §5.5
stands, with its version-0 comparison reworded (§10.3 R10).

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
;; context : :wait | :captured | :parked
;; task    : decoded task context, plain data, no engine:
;;           {:parked-key P-or-nil                 ; the parked-table key this frame sits under (:parked only)
;;            :ffi-call-ids #{…}                   ; call ids of the task's live :ffi and :ffi-request pendings
;;            :free-env {sym V …}}                 ; the root's free environment, decoded
;; => nil, or {:yin.k/kind k :yin.k/path p …}

(frame/task-defect task)
;; the once-per-body rule X1 over the decoded task context (its :free-env);
;; run before any frame of the body; => nil or {:yin.k/kind k :yin.k/path p …}
```

Namespace `yin.vm.semantic-register.frame`, file
`src/cljc/yin/vm/semantic_register/frame.cljc`, requiring only
`yin.vm.semantic-register.code`, `.analysis`, `yin.vm.debruijn`
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
| S1 | `frame` is a map; `:yin.k/state` is `:act` or `:tail`; an `act` has exactly the keys `:yin.k/state :yin.k/segment :yin.k/pc :yin.k/env :yin.k/window :yin.k/deliver :yin.k/k` plus optionally `:yin.k/pending`; a `tail` exactly `:yin.k/state :yin.k/segment :yin.k/site :yin.k/deliver :yin.k/k` plus optionally `:yin.k/pending` | `:frame-shape` |
| S2 | `:yin.k/k` is a vector; every element is a map with exactly `:yin.k/frame-type :yin.k/segment :yin.k/pc :yin.k/env :yin.k/window :yin.k/rd`, `:yin.k/frame-type` is `:return` | `:k-shape` (path `[:yin.k/k i]`) |
| S3 | every `:yin.k/window` present (the act's, each return frame's) is a map whose keys are exact non-negative integers (on the wire: sorted ascending, unique) and whose values are `V` | `:window-representation` |
| S4 | every `:yin.k/env` present is `{:yin.k/bindings {S V …}}` optionally with `:yin.k/store-of M`; `:yin.k/deliver` is a map | `:env-shape` / `:deliver-shape` |

**Code (every frame).**

| # | Rule | Kind |
|---|---|---|
| C1 | `:yin.k/segment` of the frame and of every return frame names a key of `code` | `:segment` |
| C2 | an `act`'s `pc`, every return frame's `pc` and a `tail`'s `site` are **exact non-negative integers** (`cbor/numeric-kind` `:integer` for a wire value; never a float carrier), checked **before** any indexing; then `0 < pc < (count v)` and `0 ≤ site < (count v)` | `:pc-kind` / `:pc-bounds` |
| C3 | `body(pc−1) = body(pc)` for the act and every return frame (no body ends in a parking instruction, frozen §3.4 rule 6) | `:pc-body` |

**The current activation (`act` only; a `tail` skips A1-A6).**

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
| P1 | `context :wait` requires `:yin.k/pending` present; `:captured` and `:parked` require it absent | `:pending-presence` |
| P2 | (`:wait`) the pending's reason is admitted by `inst(pc−1)` (act) or `inst(site)` (tail): `:stream-next → :next`; `:stream-put → :put`; `:ffi-call → :ffi, :ffi-request`; `:call → :next :put :ffi :ffi-request :link-request :link-response :install`; `:park` and `:current-continuation` admit none (frozen §4.1; `wire-of-kind`, `handoff.cljc:491-504`; ucf-revisions §8) | `:reason-mismatch` |
| P3 | (`:captured`) the frame is an `act` and `inst(pc−1)` is `:current-continuation` | `:captured-site` |
| P4 | (`:parked`) either `inst(pc−1)` is `:park` (an `act`), or `(:parked-key task) ∈ (:ffi-call-ids task)` and the frame is an `act` with `inst(pc−1) = :ffi-call` or a `tail` whose `inst(site)` is a tail `:call` (bookkeeping, evaluator §1.6) | `:parked-site` |

Rule E2 is the correction the review required (§4.4): a merged `E`
cannot be split into free and lexical parts without the task's free
environment, so the carrier carries it and the validator checks that
every `E` agrees with it off the lexical names. Nothing here reads a
stack depth, a stack base or a `live` vector.

### 3.3 Where and when the validator runs

**DECIDED** (carrier order kept: draft §8, step 7 after step 6; the
review's ruling). On **lower**: gate (1), structure (2), graph (3),
hashes (4), canonical form (5), canonical numbering (6), **frames (7)**,
pending and operations (8), dependency closure (9), custody (10),
composition (11), restore (12). In step 7 the body's decoded task
context is judged first by `frame/task-defect` (X1), then every `:act`,
`:tail`, `:kframe`, `:reified` and parked-table state is decoded and
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

**PROPOSED** namespace `yin.vm.semantic-register.layout`, file
`src/cljc/yin/vm/semantic_register/layout.cljc`, requiring
`yin.vm.semantic-register.code`, `.analysis`, `.derive` (phase 5),
`yin.vm.debruijn` (`resolve-name`), `yin.vm.engine` (`bind-params`), and
the two kernels' public state. Public: `register-layout`,
`stack-layout` (static, per segment), `lift-act`, `lift-tail`,
`lower-act`, `lower-tail` (per kernel, dispatching on the kernel's
`link-format`).

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
invents a safepoint: a kernel pc whose predecessor is not a parking
instruction is `:yin.k/not-at-safepoint` (UCF §7.4.2).

---

## 5. The safepoint harness

### 5.1 What it proves

**DECIDED** (frozen §10 phase 6 gate; UCF §7.11). For every row of the
expanded matrix, both delivery modes, nested and reified captures,
module contexts and every refusal: lift at the safepoint, lower into a
fresh machine, continue, and result and effect trace equal the
uninterrupted reference run; `lift(lower(frame)) = frame`; the frame's
reason is the observed wait's; on the three lanes: `:semantic-register`,
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
`y = :arg`). **Frames the wrappers add.** The harness marks `:tail?
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
| 10 | install, child running | `(require 'host.mod)` where `host.mod`'s body reads a stream the test has not fed, so the child is `:blocked` and the parent's wait is `:install` | feed the child's stream | act, `:install` + install entry, child kind `:blocked`, phase `:running` | `'host.mod` | 4b stage 2 |
| 11 | install, child parked | as 10 with the child body executing `:park` | resume the child by id | act, `:install`, child kind `:parked`, phase `:parked` | `'host.mod` | 4b stage 2 |
| 12 | install, tail | `((fn [u] (require 'host.mod)) 0)` tail with 10's child | as 10 | **tail**, `:install` | `'host.mod` through `K` | 4b stage 2 |
| 13 | explicit park | `(vm/park)` | `(vm/resume pid "back")` beside | parked record, root `:parked` | `"back"` | yes |
| 14 | reified capture live in a window | `(let1 k (vm/current-continuation) (cat (cat [] k) (next-of c)))` | append `"B"` | act, `:next`; `k` is a `:reified` value in `W` | `[:continuation "B"]` normalised | yes |
| 15 | capture invoked after lower | `(let1 k (vm/current-continuation) (if (= k :second) :done (let1 r (next-of c) (k :second))))`: on the first pass `k` is a continuation, so the test fails and the read runs and parks; after satisfaction `(k :second)` delivers `:second` into the capture's `rd` (the `let1` operand) and re-enters at the capture's successor pc, so the body is entered again with `k = :second` and the test succeeds **without repeating the read** (the invocation is abortive on every lane: `:continuation` operator kind, `srvm-phase4b:semantic_register.cljc:212-217` stage 2, `stack.cljc:760-763`). `if` is `{:type :if :test :consequent :alternate}` (`vm.cljc:1069`), a helper the new harness file adds; `=` is the primitive (`vm.cljc:387`), consumed before the read | append `"x"` after lower | act, `:next`; `k` is a `:reified` value in `E` (the closure's) | `:done` | yes |
| 16 | nested capture | `(let1 k1 (vm/current-continuation) (let1 k2 (vm/current-continuation) (cat (cat (cat [] k1) k2) (next-of c))))` | append `"B"` | act; `k2`'s act window holds `k1` (a `:reified` inside a `:reified`'s window) | `[:continuation :continuation "B"]` | yes |
| 17 | module context, blocked inside a module closure | `host.mod` exports `f = (fn [c] (stream/next! c))`; root: `(f c)` after `(require 'host.mod)` | append `"B"` | act, `:next`, `E` carries `store-of host.mod` | `"B"` | 4b stage 2 (link) |
| 18 | module-store snapshot | `parked-module-store-cursors` (`lift_support.cljc:310-333`): an explicit park whose module store holds two cursors named by a module closure | resume | parked record; `:module-store` row | the two cursor cells | yes |
| 19 | halt | `(lit 42)` | — | result | `42` | yes |
| 20 | **tail-effect halt** | run **outside the wrappers**, in two batches on one machine: a setup batch `(then (def! 's (stream/make 4)) (def! 'c (cursor-of s)))` run to halt (store writes persist across batches, as `lift_support.cljc:320-322` relies on), then the batch `(stream/next! c)` **marked `:tail? true` at the root**, `c` resolving through the store; no application precedes the call, so `K` is empty (any `let1` or wrapper would push a frame, §5.2 above) | append `"B"` | tail, `:next` | halts with `"B"` (4b: a tail effect with no frame to pop halts, `srvm-phase4b:semantic_register.cljc:205-211` stage 2; stack: the seam's empty-`K` case, §4.3), env free of `store-of` | yes |
| 21 | **free-env shadowing** (the review's row) | initial free env `{x 99}`; `(let1 f (fn [] x) ((fn [x] (f (next-of c))) 1))` | append `"B"` | act, `:next`; `E` at the read binds `x` to 1; root `free-env` carries `x 99` | the lowered `f` returns `99` (not nil, not 1) on every lane; **the one row the earlier design would have failed** | yes |
| 22 | nil-valued live register | `(let1 pair (fn [p q] (cat (cat [] p) q)) (pair nil (next-of c)))`: the `nil` operand is evaluated **before** the read and is live across it (`(cat nil (next-of c))` would give `(conj nil "B")`, the list `("B")`, so a vector-building `pair` is used; there is no `vector` primitive, `vm.cljc:380-410`, and `pair` builds through the segment's `cat`) | append `"B"` | act, `:next`; `W` has a present key with `[:lit nil]` | `[nil "B"]` | yes |
| 23 | tail FFI bookkeeping | a `tail` parked record under a call id `P ∈ :ffi-call-ids` | — | parked, context `:parked`, bookkeeping clause of P4 | validator accepts; the **same valid tail under a key not in `:ffi-call-ids`** is `:parked-site` (P4); moving `site` to a non-tail call is `:site` (T1, which runs first) | **synthetic**: no primitive returns an FFI effect through a tail `:call` today (evaluator §1.6) |
| 24 | `act` FFI bookkeeping | 3's parked bookkeeping record under its call id | — | parked, P4 bookkeeping | validator accepts | 4b stage 2 |

Refusal rows: `:yin.k/not-quiescent` (`a-nonempty-ready-queue-is-not-quiescent`,
`:591-603`); `:yin.k/not-at-safepoint` (a register kernel stepped one
instruction past a `:const`, asked to lift); `:yin.k/non-portable
:reason-mismatch` (the `:observe` hold, `:675-692`); and the validator's
**membership tampers**, each a row-set edit of a valid body before
lower: a window with an extra live key → `:window-extra`; a live key
removed → `:window-missing`; `rd` added → `:window-self`; `rd` replaced
by another register → `:destination`; a return frame's `pc` moved after
a tail call → `:return-site`; a reason the site does not admit →
`:reason-mismatch`; a `:tail` row given a fourth slot → `:frame-shape`;
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

The `:semantic-register` lane's wait entries are canonical frames plus
engine fields. The de Bruijn lanes load `(:image (derive/stack-image v))`
and `(:image (derive/register-image v))` of `v = (:vector (project P))`
through `dvm/create-vm`/`rvm/create-vm`, run to the safepoint and lift
through `layout/lift-act`/`lift-tail`; the stack lane's tail rows (6,
9, 12, 20) require the seam of §4.3 and are gated on 6c-pre.

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
traversal order** (the review's objection: map and set traversal, and
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
other identity it consumes is the stream marker in each cursor cell's initial
label (`[:body <stream-marker-bytes> <position-bytes>]`, draft §6 step
1) and in cursor-cell bodies; after the substitution those are the fixture's
logical names, so the procedure's inputs no longer depend on any lane's
identities. Unordered containers (`:set`, `:map`, `:store`,
`:bindings`, the parked table) are encoded by sorting their elements'
encodings bytewise (draft §6 step 2), never by host traversal order, so
a permutation of the erased identities cannot change the result once
the markers are substituted. It is never used outside the harness.

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
   values gives equal observables on rows 6, 9, 12, 20.
9. Slices: for every non-tail safepoint with `m` return frames, the
   `m+1` slices of §4.3 partition the stack kernel's operand stack
   exactly, and each has the length of its `stack-layout`.

---

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

### 6.2 Rows, not datoms

**DECIDED** (ruling 4). No vector is projected to datoms; the direct
load path is the only path (§10.4).

### 6.3 The free environment: carrier, source, ownership

**DECIDED** by the review's finding and §4.4. The task's initial free
environment travels **once, explicitly**, as the root's `free-env`
slot, a `:bindings` row (`[S V …]` sorted). Every `E` in the task is a
complete map and is checked by rules X1 and E2. On lower, the
semantic-register kernel receives it as the machine's base `:env`
(`create-vm`'s `:env` option, `semantic_register.cljc:261`); a de Bruijn
kernel receives it as its `:free-env` (`stack.cljc:418`) and
reconstructs positional frames from each `E` by the chain (§4.4).

**Where the lift gets it (PROPOSED, the gap the review named).** It is
**never recovered from a current `E`** (§4.4). Its source is, per
kernel:

- **de Bruijn kernels**: the kernel's own `:free-env` field, "fixed for
  this instance" (`stack.cljc:82`; `register.cljc:50`), read directly.
- **the semantic-register kernel**: the evaluator keeps only the
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

Merkle sharing means every `E` whose bindings equal another's shares one
`:bindings` row; the root slot is one more reference to a row that
usually already exists.

---

## 7. Lift and lower

### 7.1 Lift

**DECIDED** (draft §9, UCF §7.8): refuse unless quiescent; refuse the
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

---

## 8. Slices, gates, entry conditions

Verification follows `docs/agents/build-n-test.md`: G0 per iteration on
the JVM, G1 `bb test:sub yin.vm` on three hosts, G2 `bb test:changed`,
G3 on master.

### 8.1 Slice 6a: the frame validator and the row codec (independent)

**Files to create.**
- `src/cljc/yin/vm/semantic_register/frame.cljc` (§3): `defect`, public.
- `src/cljc/yin/vm/ucf/rows.cljc` (§2): the row table as data,
  `frame->rows`, `rows->frame`, row ids (Jing default), the census and
  numbering per the draft §6, `canonical-under` (§5.5, test-only but
  placed here so three test namespaces share it). **Not** `lift-task`/
  `lower-task`: full task transport is 6b.
- `test/yin/vm/semantic_register/frame_test.cljc`,
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
    shows `Testing yin.vm.semantic-register.frame-test` and
    `Testing yin.vm.ucf.rows-test`.

### 8.2 Slice 6b: full task transport and the harness on the reference lane

**Files to change.** `rows.cljc`: `lift-task`, `lower-task` (§7), the
pending rows' lift and lower, install children. **Files to create.**
`test/yin/vm/semantic_register/safepoint_harness_test.cljc` (§5, the
`:semantic-register` lane). The old harness file is not edited.

**Depends on:** the explicit `:free-env` lift argument supplied by the
harness (§6.3; the 4b retention seam is a named follow-up for the 4b
track, not a 6b prerequisite); 4b stage 2 (FFI retained→sent restoration, link
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
   phase as stated; no initialization on lower.
4. `captures`: rows 14-16 round-trip; row 15's invocation after lower
   delivers once into the captured `rd` and yields `:done`.
5. `free-env-shadowing`: row 21 yields `99` after lower.
6. `synthetic-tail-bookkeeping`: row 23's hand-built parked `tail`
   under a call id in `:ffi-call-ids` passes `defect` with context
   `:parked`; the **same valid record under a key absent from
   `:ffi-call-ids`** is `:parked-site` (P4); the record with `site` moved
   to a non-tail call is `:site` (T1 runs before P4).
7. `refusals`: every refusal row of §5.2 refuses with the named status
   and kind before any attachment; `first-defect-order`: a body with a
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

**6c proper.** Files to create: `src/cljc/yin/vm/semantic_register/layout.cljc`
(§4); `test/yin/vm/semantic_register/layout_test.cljc`; the two extra
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
   slot and is no carrier value, `:continuation-registers`,
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
10. `harness/cross-engine-canonical-equality`: law 4 for every row ×
    segment under `canonical-under`.
11. `harness/cross-engine-lowering`: law 5, six ordered pairs.
12. `harness/layout-refusals`: `:yin.k/not-at-safepoint` from a kernel
    stepped to a non-safepoint pc; a register entry whose `live` was
    tampered reconstructs a frame the validator refuses (`:window-extra`
    or `:window-missing` by the tamper's direction).
13. kondo clean; `bb test:sub yin.vm` green on three hosts.

### 8.4 Phase 7's entry conditions

**DECIDED** (frozen §10 phase 7). 6a-6c green on three hosts; the row
carrier the only writer and reader the semantic-register kernel has; a
documented seam for the holder, checkpoint, ledger and authority paths
(phase 7 wires it; phase 6 edits none of them); the Stage D/E scenarios
(`stage_e_scenarios.cljc`, `checkpoint_fixtures.cljc`) listed with, for
each, the §5.2 row that exercises its parking shape; the export-recovery
object (UCF §7.7.4, format version 1 over the old codec) re-based on the
row carrier by phase 7, since it embeds a body; and the free-environment
retention seam (§6.3) either landed by the 4b track or the explicit
`:free-env` argument threaded through the holder's export path.

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
not edited.

---

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
  exists.

---

## 10. Findings and proposed amendments

Reviewer rulings (codex gpt-6.1-sol, 2026-10-10): F1, F4, U2, U4, U5,
R1, R2, R7, C1 **ACCEPT**; F2, F3, U1, U3, U6, R3, R4, R5, R6, R8, R9
**ACCEPT WITH CHANGES**, applied below; dropping `:yin.k/engine`
**ACCEPTED**; the merged-`E` free environment **REJECTED**, replaced by
§4.4/§6.3.

### 10.1 To the frozen design (`yin.vm.semantic-register-vm.md`)

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
`(defect frame code context task)` of `yin.vm.semantic-register-vm.continuation.md`
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
validator (`yin.vm.semantic-register.code/well-formed?`). The projection
path (vector → datoms → loader) is retired (owner ruling 2026-09-18;
`yin.vm.semantic-register-vm.md` §3.4). The conformance obligation is
that the two AST projection lanes and the direct vector load yield one
image and address (`load_paths_test.cljc`)."

**U2 — §7.4.1 (ACCEPT).** At its head: "Superseded for the
register-shaped machine by `yin.vm.semantic-register-vm.md` §4.1 (two
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
(`yin.vm.semantic-register.analysis`). They are identical for every
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
re-emission (continuation design §5.5) from the semantic-register kernel
and from the stack and register kernels through their layouts, the stack
kernel with its tail-completion seam". In §7.11.1 "Safepoint
reconstruction", replace "Separately prepare two independently
implemented UCF engines" with "Separately prepare the three engines
intended to reconstruct canonical frames (the semantic-register kernel;
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
through their layouts (`yin.vm.semantic-register-vm.continuation.md`
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
`yin.vm.semantic-register.frame/defect` with its context (`:wait`,
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
**outside the three repository lanes** (the semantic-register kernel and
the stack and register kernels, whose reconstruction is the layouts of
`yin.vm.semantic-register-vm.continuation.md` §4 and needs no value
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
`yin.vm.semantic-register-vm.md` §10 phase 8. The row carrier of body
version 3 (`yin.vm.ucf-transport-tuples.md` as amended by
`yin.vm.semantic-register-vm.continuation.md`) reads no other version and
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
(`yin.vm.semantic-register-vm.md` §6), the one the version-3 profile map
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
(`yin.vm.semantic-register.code/well-formed?`). UCF §7.3.4's projection
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

---

## 11. Owner questions

**None.** The reviewer accepted the engine-less profile and rejected the
merged-`E` free environment; both are now settled in the text. Nothing
remaining is owner-level.

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
