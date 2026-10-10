# yin.vm.semantic-register-vm.continuation: phase 6, the continuation format and the row carrier

Status: **Companion design, phase 6 of `yin.vm.semantic-register-vm.md`
§10.** Written 2026-10-11 (Architect: claude-fable-5-1) from the frozen
design as amended (`8f0b9e38`), the landed 4a evaluator
(`src/cljc/yin/vm/semantic_register.cljc`) and the 4b stage-1 evaluator
on branch `srvm-phase4b` (read only), the merged phase-5 derivations
design, the UCF document and its version-2 amendment, the row-carrier
draft (`yin.vm.ucf-transport-tuples.md`, body version 3, never landed),
and the landed UCF code and harness (`src/cljc/yin/vm/ucf.cljc`,
`src/cljc/yin/vm/ucf/handoff.cljc`,
`test/yin/vm/ucf/safepoint_harness_test.cljc`). The frozen document is
not changed by this one: §10 records findings with exact amendment text,
for the frozen design, for UCF, for the row-carrier draft and for
code-as-tuples. Subordinate to [`datom.world.md`](./datom.world.md),
including its unitarity invariant, and to
[`yin.vm.semantic-register-vm.md`](./yin.vm.semantic-register-vm.md)
("the frozen design"); format and rigor model:
[`yin.vm.semantic-register-vm.evaluator.md`](./yin.vm.semantic-register-vm.evaluator.md)
and [`yin.vm.semantic-register-vm.derivations.md`](./yin.vm.semantic-register-vm.derivations.md).

Every section marks its content **DECIDED** (an owner ruling, frozen
text, or code that exists) or **PROPOSED** (a design choice of this
document, open to the reviewer). File references are to the worktree at
`8f0b9e38`; line numbers are those of that commit; the 4b branch is
cited as `srvm-phase4b:semantic_register.cljc`.

Contents: §0 rulings and the one-paragraph answer · §1 the canonical
frame · §2 the row carrier: rows, window encoding, pending, captures,
contexts, census · §3 the frame validator · §4 layouts: how the stack
and register kernels reconstruct canonical frames · §5 the safepoint
harness · §6 code in the carrier: A and the tree association · §7
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
   segment-to-tree association of the phase-5 design (§9.2 there:
   `source-index`, `:yin.k/trees`) is a given.
4. **The vector-to-datoms mapping is retired** (frozen §3.4 "One vector
   validator"; code-as-tuples §7.2 superseded below); the carrier carries
   positional tuples and rows, never datoms-from-vector.
5. **dao.stream is the complexity boundary**: this design specifies no
   transport mechanics; the carrier is data that crosses a stream.
6. **One truth, many interpretations**: the register machine's
   configuration `C = ⟨seg, pc, W⟩` with `E`, `K` is the canonical frame
   shape; the stack and register kernels reconstruct it through their
   layouts (frozen §4.3, §10 phase 6).
7. **No file of phase 4b or phase 5 is touched**, so this phase runs
   concurrently with them (§8.5 names the dependencies that remain).

### 0.2 The answer

A continuation is a value: one **canonical frame** of the register
machine, `act` or `tail`, with its saved window, its `K` of `:return`
frames, its environment, its delivery record and its pending wait
(frozen §2.4, §4.2), plus the task context it needs (store slice, module
stores, parked table, cells, code by A, the tree association). Phase 6
makes that value exact and portable. **§1** restates the frame as the
4b evaluator mints it. **§2** gives the row carrier: body version 3,
Merkle content rows under Jing's default digest, one profile (the
canonical machine's contract `"v4"`), the window as a sorted keyed row
in which a nil value is a present `[:lit nil]`, `:act`, `:tail`,
`:window`, `:kframe`, `:reified` and `:parked-table` rows replacing the
stale `:regs`/`:stack`/`:kframe`-with-stack-base rows, the pending rows
unchanged in substance, the cell census unchanged. **§3** gives the
frame validator as fourteen rules over the decoded canonical frame and
the carried code, independent of any engine: membership is recomputed
from A (`saved(pc, rd)`), sites and destinations are checked against the
instruction at `pc−1`, return frames against the non-tail call they
follow. **§4** defines the two layouts: the register kernel's physical
register `locals + slot(v)` is read off the pair of images (A and R
share pcs), the stack kernel's operand stack within an activation is the
saved window in emission order, and both kernels' positional lexical
frames become the named `E` through the owner chain of A. **§5** is the
harness: every §4.1 row, both delivery modes, nested and reified
captures, module contexts and every refusal, run on the
semantic-register kernel and, through their layouts, on the stack and
register kernels; the cross-engine law is that one program at one
safepoint yields **one canonical row set** on all three. **§6** fixes
what travels for code: canonical vectors keyed by A, the tree
association, never a native image. **§8** cuts three slices and names
phase 7's entry conditions.

---

## 1. The canonical frame

### 1.1 Shapes, as the evaluator mints them

**DECIDED** (frozen §2.4 "Delivery", §4.2; evaluator design §1.3-1.6;
`srvm-phase4b:semantic_register.cljc:61-84` for `act-payload`,
`tail-builders`; `:229-234` for the reified payload and the park). Two
closed state shapes and one closed delivery record:

```clojure
act  = {:segment seg :pc n :env E :window W :k K :deliver {:deliver :rd :rd r}}
tail = {:segment seg :site n :k K :deliver {:deliver :return}}
ret  = {:type :return :segment seg :pc n :env E :window W :rd r}     ; one frame kind of K
```

- `:pc` of an `act` is the resume pc, already `pc+1` of the parking
  instruction; `:site` of a `tail` is the tail call's own pc, provenance
  only (evaluator §1.5).
- `W` of an `act` is `saved(pc, rd) = W↾(L(pc) − {rd})`
  (`analysis/saved`, `analysis.cljc:92-97`), built with `select-keys`,
  so a nil-valued live register is a present key (frozen §4.2 "dead
  values never travel"; evaluator §8 item 4).
- `K` is a vector of `ret` frames, innermost last, or nil when empty
  (`put-registers`, `semantic_register.cljc:47-52`); each frame's window
  is `saved(frame.pc, frame.rd)` of its own body.
- A **parked record** (`:park`, and FFI bookkeeping under a call id) is
  an `act` payload plus `:type :parked-continuation :id` added by
  `engine/park-continuation` (evaluator §1.6).
- A **reified continuation** is `values/continuation owner (assoc act
  :type :reified-continuation)` (`srvm-phase4b:semantic_register.cljc:229-232`).
- The wait entry the engine holds is the `act` or `tail` payload plus
  the engine's own fields (`:reason`, `:cursor-ref`, `:stream-id`,
  `:datom`, `:call-id`, `:request-sent`, the link fields), which travel
  as the **pending** (§2.4), never as frame slots.

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
harness compares across engines. The rows of §2 are its carrier and the
two are in bijection (§2.6).

---

## 2. The row carrier

The row-carrier draft (`yin.vm.ucf-transport-tuples.md`) is adopted
**except** where it describes the stack-shaped machine: its §2
(positioning, four profiles), §5.2 (frame rows), §8 step 7 (frame
checks), the "`v3` stamp does not move" statements and §10.3-10.4
(nested projection, contract stamp) are replaced here (§10.3 gives the
text). Its §3 (rows, id spaces, census), §4 (value union), §5.1 (value
rows), §5.3 (pending), §5.4 (task context), §5.5 (root), §6 (canonical
cell numbering), §7 (equality) and §8 steps 1-6 and 8-12 stand, and are
restated only where a slot changes.

### 2.1 Positioning: body version 3, one profile

**DECIDED** by ruling 2 (one version, no older bodies) and ruling 6
(one canonical frame shape). The carrier is **body version 3**, the
number the draft reserved and never landed; versions 0-2 are the old
handoff's and are not read by the row reader (they leave with the
stack-shaped machine at phase 8; until then they belong to
`yin.vm.ucf.handoff`, untouched). The wire unit is the draft's:

```clojure
{:yin.k/handoff true  :yin.k/version 3  :yin.k/root T  :yin.k/rows [[id kind & slots] …]}
```

**PROPOSED.** The profile map a version-3 body carries is
`{:yin.code/contract "v4" :yin.k/version 1}` and nothing else. There is
**no `:yin.k/engine` key**: the frame is the canonical machine's and is
engine-independent by ruling 6; the kernel that executes it is the
receiving composition's choice (a semantic-register, stack or register
kernel all lower the same rows through their layouts, §4). A body whose
profile map differs is `:yin.k/profile-mismatch`. The old four-profile
registry (`ucf/profiles`, `ucf.cljc:51-74`) describes version-2 bodies
and is not consulted.

### 2.2 Rows, ids, census

**DECIDED** (draft §3, ruling 1). Every row is `[id kind & slots]`;
content rows have `id = (jing/segment-key body)` under Jing's default
digest, the same call as `ucf/code-address`; identity rows are cells
`:yin.k/c-<n>` numbered by the draft's §6 procedure; the root carries
the cell census `[X :cells C₁ B₁ …]`, `[Bᵢ :cell-body stream-cref position]`.
Content rows reference children by id (crefs) in typed positions; a
`[:row B]` position admits only its tabled kinds. One ordering rule:
"sorted" is by the Jing canonical bytes of the key as spelled.

### 2.3 The frame rows (replacing draft §5.2)

**DECIDED** by frozen §4.2's last paragraph ("`:regs` becomes
`[segment-A pc env-cref window-cref k-cref deliver-D]` … a sibling
`:tail-state [segment-A site k-cref deliver-D]` … `:window` is a keyed
row `[r₁ V₁ r₂ V₂ …]` sorted by `r` … `:kframe` becomes
`[segment-A pc env-cref window-cref rd]` … `:stack` and `stack-base`
are removed"). **PROPOSED** names `:act` and `:tail` for the two state
kinds (§10.1 F1):

| Kind | Slots | Notes |
|---|---|---|
| `:act` | `segment-A  pc-N  env-cref  window-cref  k-cref  rd-N` | an act state; the delivery record is `{:deliver :rd :rd rd-N}` by kind, so the kind is the discriminant and `rd` the one datum |
| `:tail` | `segment-A  site-N  k-cref` | a tail state; delivery `{:deliver :return}` by kind; **no** env, window or pc slot (frozen §2.4 "a `tail` state carries no window and no `E`") |
| `:window` | `r₁ V₁  r₂ V₂  …` sorted by `rᵢ` ascending, unique | the saved window; `rᵢ` is `N`; **a nil-valued register is present as `rᵢ [:lit nil]`**; the empty window is `[:window]` |
| `:k` | `kframe-cref …` outermost first (bottom to top) | `K`; `[:k]` is the empty continuation |
| `:kframe` | `segment-A  pc-N  env-cref  window-cref  rd-N` | one `:return` frame; `pc` is the resume pc after a non-tail call |
| `:wait` | `state-cref  pending-cref` | a blocked wait: `state-cref` is an `:act` or a `:tail` row |
| `:reified` | `act-cref` | a captured continuation value (replaces the draft's `[:reified regs-cref]`); its act's `pc−1` is a `:current-continuation` |
| `:parked-ref` | `parked-id-P` | unchanged: resolves in the parked table |
| `:env` | `bindings-cref  store-of` | unchanged (draft §5.1): `store-of` is `M` or nil |
| `:bindings` | `S₁ V₁  S₂ V₂ …` sorted by symbol | unchanged |

The window row is structural, never a `V`: it is always a row even when
it reaches no reference (the draft's "a reference-free collection must be
`[:lit d]`" rule is about guest collections, §4 of the draft, and does
not apply to `:window`, `:bindings`, `:store` or any other tabled keyed
row). `rd` is carried on `:act` so that the frozen §4.2 "destination
equality" rule (§3 rule 6) has something to compare: it must equal the
destination of the instruction at `pc−1` and is therefore redundant data
that the validator checks, exactly as `pc` is redundant with the pending
and checked.

### 2.4 Pending rows

**DECIDED** (draft §5.3, UCF §7.4.3, v2 amendment §2): one kind
`:pending`, first slot the reason, remaining slots fixed per reason;
reasons `:next :put :ffi :ffi-request :link-request :link-response
:install`; `:park` and `:call-effect` are not reasons (ucf-revisions §8).
Unchanged from the draft:

| reason | slots after the reason |
|---|---|
| `:next` | `cell-C` |
| `:put` | `stream-cref  value-V  op-id-D` |
| `:ffi` | `cell-C  call-id-D  op` |
| `:ffi-request` | `call-id-D  envelope-V  request-stream-cref  response-stream-cref  response-cell-C  op-id-D` |
| `:link-request` | `link-id-D  name-S  request-stream-cref  response-stream-cref  cell-C  envelope-D  op-id-D` |
| `:link-response` | `link-id-D  name-S  request-stream-cref  response-stream-cref  cell-C` |
| `:install` | `name-S` (never sufficient alone: the root's installs slot holds the entry) |

What changes is only **which state a pending rides on**: a `:wait` row
pairs a pending with an `:act` (write-result delivery) **or a `:tail`**
(through-return delivery), the frozen §4.1 table's two state columns.
The retained FFI request keeps the 4b evaluator's in-VM markers out of
the frame: `:request-sent`, `:call-id`, `:op`, `:datom`, `:response-cursor`
and `:response-stream` are the pending's, as today
(`handoff.cljc:702-724`, `:3219-3229`).

### 2.5 Captures, parked records, module contexts

**DECIDED** (frozen §2.4 "current-continuation", "park"; evaluator §1.6;
draft §5.1, §5.4).

- A **reified continuation** value is `[:row B]` where `B` is a
  `:reified [act-cref]` row; the act has no pending, its `pc−1` is a
  `:current-continuation`, its `rd` is that instruction's destination,
  and its window is `saved(pc, rd)` (so it is never captured inside its
  own destination, frozen §2.4).
- The **parked table** is `[:parked-table P₁ state-cref₁ P₂ state-cref₂ …]`
  sorted by `P`, each state an `:act` (an explicit park: `pc−1` is
  `:park`) **or**, for FFI bookkeeping under a live `:ffi`/`:ffi-request`
  pending whose call id is `P`, an `:act` whose `pc−1` is the `:ffi-call`,
  or a `:tail` whose site is the effectful tail `:call` (evaluator §1.6:
  "the `tail`-shaped FFI parked record is specified for closure of the
  shape set"). A root of kind `:parked` names its active record by
  `parked-id`; an active parked id may never select a bookkeeping record
  (v2 amendment §7.3, kept).
- A **module context** is `E`'s `store-of`: an `:env` row whose second
  slot is `M`; the module store travels once as `[:module-store M store-cref]`
  (draft §5.4), and every `store-of` must resolve to a carried module
  store (draft §8 step 3). Closures carry `store-of` in their `:closure`
  row as today.
- **Install children** are whole `:task` rows in the child role (draft
  §5.4, §5.5), unchanged; a child's frames are `:wait` rows of the same
  grammar.

### 2.6 The bijection with the keyed frame

**PROPOSED.** `rows->frame` and `frame->rows` are two pure functions
(`yin.vm.ucf.rows`, §8.1) and `frame->rows ∘ rows->frame = id` on valid
row sets, `rows->frame ∘ frame->rows = id` on canonical keyed frames:
`:act` ↔ `{:yin.k/state :act …}` with `:yin.k/deliver {:yin.k/deliver :rd
:yin.k/rd rd}` synthesised from the kind and the `rd` slot; `:tail` ↔
`{:yin.k/state :tail …}` with `{:yin.k/deliver :return}`; `:window` ↔
`:yin.k/window` as a map (`[:lit nil]` ↔ a nil-valued key); `:k` ↔
`:yin.k/k` in order. The validator (§3) runs on the keyed form after
`rows->frame`, which only reshapes and never decides anything the
validator decides.

---

## 3. The frame validator

### 3.1 Inputs and independence

**DECIDED** (frozen §4.2: "The window's membership is validated, not
trusted: the receiver recomputes `saved(pc, rd)` from A … the full frame
validator also checks representation, body scope, site/pc pairing,
destination equality, delivery mode, return-frame sites, `K` structure,
and pending compatibility with `reason`"). The validator is a pure
function

```clojure
(frame/defect frame code)   ; frame: a decoded canonical frame (§1.2)
                            ; code:  {A vector}, every vector code/well-formed?
;; => nil, or {:yin.k/kind k :yin.k/path p …}
```

in **PROPOSED** namespace `yin.vm.semantic-register.frame`, file
`src/cljc/yin/vm/semantic_register/frame.cljc`, requiring only
`yin.vm.semantic-register.code` (`body-ranges`, `body-index`, `rd`,
`tail-call?`, `registers`-per-body via `parse`),
`yin.vm.semantic-register.analysis` (`analyze`, `saved`) and `yin.vm`
(`reserved-name?`). It knows no kernel, no engine, no stream: a frame a
stack kernel lifted and a frame the register evaluator lifted are judged
by the same function on the same inputs.

### 3.2 The rules

Rules run in order; the first defect wins; every defect carries
`:yin.k/kind` and `:yin.k/path` (the frame key path, `[:yin.k/k i …]` for
a return frame). `a = (analysis/analyze v)` is computed once per segment.
"The instruction at `pc−1`" is `(nth v (dec pc))`; `body(p)` is
`(code/body-index (code/body-ranges v) p)`; `k(body)` is the body's
register count (`code/parse`'s `:registers`).

| # | Rule | Refusal kind |
|---|---|---|
| 1 | **Shape.** An `act` has exactly `:yin.k/state :yin.k/segment :yin.k/pc :yin.k/env :yin.k/window :yin.k/deliver :yin.k/k` and optionally `:yin.k/pending`; a `tail` exactly `:yin.k/state :yin.k/segment :yin.k/site :yin.k/deliver :yin.k/k` and optionally `:yin.k/pending`; no other key; `:yin.k/state` is `:act` or `:tail` | `:frame-shape` |
| 2 | **Segment.** `:yin.k/segment` names a key of `code`; the same for every return frame's segment | `:segment` |
| 3 | **Bounds.** `pc` is an exact integer with `0 < pc < (count v)`; a return frame's `pc` likewise; a `tail`'s `site` with `0 ≤ site < (count v)` | `:pc-bounds` |
| 4 | **Window representation.** `:yin.k/window` is a map whose keys are exact non-negative integers (on the wire: sorted ascending, unique); values are `V`; a nil value is a present key | `:window-representation` |
| 5 | **Body scope.** every window key is `< k(body(pc))` and `body(pc)` is the body of `pc−1` (a resume pc never crosses a body boundary: `pc−1` and `pc` are in one body, which holds because no body ends in a parking instruction, frozen §3.4 rule 6) | `:window-scope` |
| 6 | **Site.** the instruction at `pc−1` of an `act` is one of `:stream-next :stream-put :ffi-call :call :park :current-continuation`, and for a `:call` it is **not** a tail call (`code/tail-call?` false); a `tail`'s `site` instruction is a `:call` with `tail?` true | `:site` |
| 7 | **Destination.** `(:yin.k/rd (:yin.k/deliver act))` equals `(code/rd (nth v (dec pc)))` | `:destination` |
| 8 | **Membership.** `(set (keys window)) = (analysis/saved a pc rd)`: a key outside the set is `:window-extra`, a member missing is `:window-missing`, `rd` present is `:window-self` (checked before the set comparison so it is named separately) | `:window-extra` / `:window-missing` / `:window-self` |
| 9 | **Delivery mode.** an `act`'s deliver is exactly `{:yin.k/deliver :rd :yin.k/rd r}` with `r` an exact integer; a `tail`'s exactly `{:yin.k/deliver :return}`; a `tail` has no `:yin.k/window`, `:yin.k/env` or `:yin.k/pc` (rule 1 already) | `:deliver` |
| 10 | **Return frames.** `:yin.k/k` is a vector; each frame has exactly `:yin.k/frame-type :return :yin.k/segment :yin.k/pc :yin.k/env :yin.k/window :yin.k/rd`; the instruction at `frame.pc−1` in `frame.segment` is a `:call` with `tail?` false; `frame.rd` equals that call's `rd`; `(set (keys frame.window)) = (analysis/saved aₛ frame.pc frame.rd)` in that segment's analysis, with the three membership kinds as rule 8; window keys `< k(body(frame.pc))` | `:return-site` / `:return-destination` / `:return-window-*` |
| 11 | **Environment.** `:yin.k/env` (of an `act` and of every return frame) is `{:yin.k/bindings {S V …}}` optionally with `:yin.k/store-of M`; every binding key is a symbol and none is `yin/def` (Rule R; `vm/reserved-name?`) | `:env` / `:reserved-name` |
| 12 | **Reason compatibility.** when `:yin.k/pending` is present, its reason is admitted by the instruction at `pc−1` (`act`) or `site` (`tail`): `:stream-next` → `:next`; `:stream-put` → `:put`; `:ffi-call` → `:ffi` or `:ffi-request`; `:call` → `:next :put :ffi :ffi-request :link-request :link-response :install`; `:park` and `:current-continuation` admit **no** pending (frozen §4.1; `wire-of-kind`, `handoff.cljc:491-504`; ucf-revisions §8: the static kind gates, the observed reason decides) | `:reason-mismatch` |
| 13 | **Captured context.** when the frame is judged as a reified value (`context :captured`): it is an `act`, has no pending, and `pc−1` is `:current-continuation` | `:captured-site` |
| 14 | **Parked context.** when judged as a parked-table entry (`context :parked`): an `act` whose `pc−1` is `:park` and no pending; or, when the table key is the call id of a live `:ffi`/`:ffi-request` pending of the same task, an `act` whose `pc−1` is `:ffi-call` or a `tail` whose site's call is the parking call (bookkeeping) | `:parked-site` |

The validator takes a `context` argument, one of `:wait`, `:captured`,
`:parked`, selecting rules 12-14. Rule 8 is the frozen §4.2 membership
check; rules 5, 10 and 12 are its "body scope", "return-frame sites" and
"pending compatibility"; rules 6-7 its "site/pc pairing" and
"destination equality"; rule 9 "delivery mode". Nothing here reads
`:yin.k/live`, a stack depth or a stack base: those were the stack
machine's (v2 amendment §5.1-5.3) and have no counterpart.

### 3.3 Where the validator runs

**DECIDED** (draft §8 step 7, replaced; §10.3). On lower, after the
gate, structure, graph and hash steps (draft §8 steps 1-4) and before
canonical numbering, **every** `:act`, `:tail`, `:kframe`, `:reified`
and parked-table state is decoded to its keyed frame and judged by
`frame/defect` with its context; a defect is `:yin.k/undecodable` with
the rule's kind and the row path. On lift, the same function runs over
every frame the lift assembled before any row is emitted (a frame the
evaluator itself minted that fails is `:yin.k/non-portable` kind
`:inconsistent-state`, the existing kind). The harness (§5) also runs it
over every frame a layout reconstructs.

---

## 4. Layouts: reconstructing the canonical frame

### 4.1 What a layout is

**DECIDED** (frozen §4.3; UCF §7.4.2). A foreign engine keeps a static
safepoint map per segment and engine with `:yin.safepoint/segment A`,
`:yin.safepoint/pc`, `:yin.safepoint/engine`, the per-pc `def`, `use`,
`L(pc)` derived from A (`analysis/analyze`), `lexically-required`, and
`:yin.safepoint/layout`, "a map from physical location to virtual
register". Its obligation: from its physical state at a safepoint,
produce the canonical frame (`A`, resume `pc`, every register in
`saved(pc, rd)`, `E`, `K` with each frame's window and `rd`, the delivery
record, the pending, the store context) for an `act`; `A`, `site`, `K`,
delivery, pending for a `tail`; and `lift(lower(frame)) = frame` on
admitted canonical frames.

Two kernels are intended to reconstruct canonical frames in phase 6:
the de Bruijn register kernel over `R(A)` and the de Bruijn stack kernel
over `H(A)`, both images being pure functions of A (phase 5). The AST
walker is **not** (frozen §10 phase 6 names the three kernels; the
walker has no pc and is the oracle of the evaluator, not a frame
reconstructor).

**PROPOSED** namespace `yin.vm.semantic-register.layout`, file
`src/cljc/yin/vm/semantic_register/layout.cljc`, requiring
`yin.vm.semantic-register.code`, `.analysis`, `.derive` (phase 5:
`register-image`, `stack-image`, `resolve`), `yin.vm.debruijn`
(`resolve-name`), `yin.vm.engine` (`bind-params`), and the two kernels'
public state accessors. Four public functions:
`register-layout`, `stack-layout` (static, per segment),
`lift-frame` and `lower-frame` (per kernel, dispatching on the kernel's
`link-format`).

### 4.2 The register kernel's layout

**DECIDED** by the phase-5 design §2.4 ("Layout is A's: bodies and pcs
are identical to the vector's") and §2.3 (`R(v) = locals + slot(v)`).

*Static half.* For a segment `A` with register image `R =
(:image (derive/register-image v))`, the layout is read off the **pair**
`(v, R)` pc by pc, without any access to the allocator's internals: for
every pc `p`, the tuple `v[p]` names virtual registers in its `rd`,
`:reg` and `:regs` slots (`code/rd`, `code/uses`) and `R[p]` names the
corresponding physical registers in the same slots (same mnemonic, same
operand positions, one `live` operand appended on boundary opcodes). So

```
phys(body, v) = the physical register paired with virtual v at any pc of body
```

is a total function on the virtual registers a body names (every minted
id with a syntactic definition appears as some instruction's `rd`; a
definition-less id appears as a layout-syntax operand and pairs with the
structural slot). The layout publishes, per body, `virt→phys` and its
inverse `phys→virt` on the image of `virt→phys`; a physical slot with
two virtual preimages at one pc is impossible (one tuple, one slot), and
two virtuals sharing a slot is the allocator's reuse across disjoint
intervals, so `phys→virt` is **per pc**: `phys→virt(p, s) = the v with
phys(v) = s and p ∈ [start(v), end(v)]`, computed from `analysis/live`
without any interval machinery as "the `v ∈ L(p) ∪ def(p−1)` with
`phys(v) = s`"; uniqueness at each `p` is a property the harness
checks (§5.6 item 3), never assumed.

*Dynamic half, lift.* The register kernel's wait entry at a boundary
(`yin.vm.debruijn.register`'s payload: `:image`, `:site-pc`, `:pc`,
`:frames`, `:regs [[s V] …]`, `:live [s …]`, `:continuation [{:site-pc
:return-pc :frames :regs :live :dest} …]`, `:dest`, `:resume-mode`,
`:store-of`; v2 amendment §5.3, `handoff.cljc:945-980`) reconstructs:

- `A` = the image's A (the kernel's `:image` identity is R under the old
  contract; after phase 5 the kernel's code space is keyed by A with R as
  checksum, phase-5 §7.6; the layout maps the image row to its A).
- `pc` = the payload's `pc` **relative to the image** (the kernel
  concatenates images with offsets; `absolute-pc`/`image-row`,
  `debruijn/register.cljc:326-337`); R's pcs are A's, so the relative pc
  is the canonical pc.
- `rd` = `(code/rd v[pc−1])`; `:resume-mode :write-result` ↔ `act`,
  `:return-result` (a tail `:call`, `dest nil`) ↔ `tail`.
- `W` = `{phys→virt(pc, s) ↦ V : [s V] ∈ regs}`. The kernel's `live`
  operand is the physical image of `L(pc) − {rd}` by phase-5 §2.5, so the
  key set equals `saved(pc, rd)`; the validator's rule 8 checks it.
- `E` = §4.4 from `frames` and the owner chain at `pc`.
- `K` = each continuation frame `{site-pc return-pc regs dest frames}` ↦
  `{:return A pc=return-pc rd=dest window=(regs through phys→virt at
  return-pc) env=(frames through §4.4 at return-pc)}`.
- pending: the engine fields, exactly as today's lift maps them
  (`lift-pending!`, `handoff.cljc:674-749`).

*Dynamic half, lower.* The inverse: `regs = [[phys(v) V] …]` for `v` in
the window, sorted by physical index; `live` = the sorted physical keys
(the kernel's validator then recomputes `body-liveness` and must agree,
`live-exact-rule`); `frames` from `E` by §4.4; `dest = phys(rd)`;
`resume-mode` by state kind; continuation frames likewise; `:image` the
row of `A`; `:site-pc = pc−1`.

### 4.3 The stack kernel's layout

**DECIDED** by the phase-5 design §3.2 (the stack emitter's walk) and
the frozen §8.3 remark about pushes; **PROPOSED** as a layout.

*Static half.* `H = (:image (derive/stack-image v))`. Two tables per
segment:

1. **pc correspondence.** The stack emitter visits parse nodes in the
   same order as `project` (children, then the parent; bodies FIFO) and
   emits one instruction per A instruction **plus** a `:push` after each
   child of a call, `:stream-put` target or `:ffi-call` operand. So the
   sequence of H instructions **with every `:push` removed** is in
   one-to-one order correspondence with A's instructions: the k-th
   non-push H instruction corresponds to A's pc `k`. `h->a` and `a->h`
   are the two directions of that zip. The harness checks the mnemonic
   families agree at every k (§5.6 item 4); the design does not rely on
   the claim without the check.
2. **operand-stack layout.** For an A pc `p` that is a parking
   instruction, `stack-layout(p)` is the vector of virtual registers
   whose values the activation's operand stack holds, bottom to top,
   when control is at `a->h(p)`: walking the parse tree from the body's
   root to the node at `p`, for each ancestor `n` and the child `c` on
   the path, the pushed-and-unconsumed children of `n` before `c` are:
   for a `:call`, the operator and the operands before `c`; for a
   `:stream-put`, the target when `c` is the value; for an `:ffi-call`,
   the operands before `c`; for `:if`, `:define`, `:stream-cursor`,
   `:stream-next`, `:stream-close`, `:resume`, none (their child is
   consumed directly). The vector lists those children's `rd`s in that
   order.

   **Property checked, not assumed** (§5.6 item 5): `(set
   (stack-layout p)) = (analysis/saved a (inc p) (code/rd v[p]))`. The
   argument: the registers live after a parking instruction are exactly
   the already-evaluated siblings that an enclosing expression will
   still read, which are exactly what has been pushed and not yet
   consumed; the two worked cases are `(f (g x) y)` at the inner call
   (stack `[f]`, `saved(4, 2) = {1}`, `analysis_test.cljc:131-139`) and
   `(f (if c (g) 2) y)` at `(g)` (stack `[f]`; `saved = {1}` since the
   `if`'s `rd` is the call's own `rd`).

*Dynamic half, lift.* The stack kernel's wait entry (`:image`, `:pc`,
`:frames`, `:stack`, `:continuation [{:return-pc :frames :stack-base
:store-of} …]`, `:store-of`; v2 amendment §5.2, `handoff.cljc:897-924`)
reconstructs: `A` the image's; canonical `pc = h->a(pc_H − 1) + 1`; `rd
= (code/rd v[pc−1])`; `W` = zip of `stack-layout(pc−1)` with the
activation's operand-stack slice (from the innermost frame's
`stack-base` to the top), key `rdᵢ` ↦ value; `E` by §4.4; `K` = each
continuation frame ↦ `{:return A pc=h->a(return-pc_H − 1)+1 rd=(code/rd
v[pc−1]) window=(zip of stack-layout at that call with the slice
[stack-base_i, stack-base_{i+1}) of the operand stack) env=§4.4}`; state
kind: `act` unless the parking H instruction is a `:call` with `tail?`
true, then `tail` with `site = h->a(pc_H − 1)`. A `tail` state has no
operand slice of its own: the tail call popped its operands and no value
of the current body is read again (frozen §2.4), so the stack kernel's
stack at a tail-call park is exactly the enclosing frames' slices.

*Dynamic half, lower.* Operand stack = concatenation over `K` outermost
first of each frame's window laid out by `stack-layout` at its call,
then the current activation's window laid out at `pc−1`; `stack-base`s
are the running lengths; `pc_H = a->h(pc−1) + 1`; `frames` by §4.4.

### 4.4 Positional lexical frames and the named environment

**DECIDED** by frozen §2.1 ("Parameters live in E, not in registers …
leaves the de Bruijn register image free to map parameters to its own
`L` bank") and §2.4 (`E_c[ps ↦ a]` by `bind-params`); phase-5 §1.2 (the
owner chain). Both de Bruijn kernels hold the lexical environment as
positional frames `F = [[V …] …]` outermost first, one per enclosing
closure activation, plus a task-wide free environment (v2 amendment §2).
The canonical `E` at a pc of body `b` is

```
E = (merge free-env
           (bind-params params(owner-chain[0]) F[0])      ; outermost closure first
           …
           (bind-params params(owner-chain[last]) F[last]))  ; the current body's own
     ∪ {engine/store-of-key M}  when the activation's closure carries store-of M
```

where `params(c)` is the `:closure` tuple's parameter vector in A
(`(nth v c 2)`) for each closure pc on the owner chain of `b`, outermost
first (phase-5 §1.2's `chain` reversed), and `bind-params` is the
engine's (`engine/bind-params`: rightmost duplicate wins, nil-fill,
extra arguments dropped). This reproduces exactly the `E` the register
evaluator builds on closure entry (`semantic_register.cljc:141-143`:
`(merge (:env c) (bind-params (:params c) args))`), because `(:env c)`
is the creating activation's `E`, which is itself free-env merged with
the outer chain's bindings. The inverse (`E` → `F`) reads `F[i] =
(mapv #(get E %) params(chain[i]))`, which loses nothing the kernel
needs: a parameter shadowed by an inner frame is still read from its own
frame by depth, and the de Bruijn kernels never read a name.

**Checked, not assumed** (§5.6 item 6): the harness compares the `E` a
layout reconstructs with the `E` the register evaluator holds at the
same safepoint of the same program, for every corpus program, including
`:duplicate-param` (`(fn [x x] x)`) and `:nested-closure`.

*Closure values.* A de Bruijn closure value (positional marker: segment,
image-relative entry, arity, frames `F`, store-of) reconstructs to the
canonical closure (`{:type :closure :params :entry :segment :env}`,
evaluator §1.8): `params` from A's `:closure` tuple at the entry's owner
pc, `env` by the rule above over the closure's captured `F`, `entry` the
body pc (A's pc). The inverse is `module/lift-closure`'s positional
marker with `F` read back from `env` by the chain. The harness item 7
checks both directions on the `"closure value"` and `"higher order"` B0
rows.

### 4.5 What a layout never does

**DECIDED.** A layout reads a kernel's state and A; it never runs code,
never consults the receiver's store or free-env to fill a binding
(`E`'s free-env part is the **task's own** carried free environment,
`:free-env` of the body when the source was a de Bruijn kernel, the
`:env` the semantic-register task started with otherwise; §6.3), never
reads a native image's hash as an identity (A is the key; H and R are
checksums), and never invents a safepoint: a kernel pc that is not a
canonical safepoint successor is `:yin.k/not-at-safepoint` (UCF §7.4.2).

---

## 5. The safepoint harness

### 5.1 What it proves

**DECIDED** (frozen §10 phase 6 gate; UCF §7.11 "Safepoint conformance
harness"; §7.11.1 "Safepoint reconstruction"). For every row of the
frozen §4.1 table, both delivery modes, nested and reified captures,
module contexts and every refusal: lift at the safepoint, lower into a
fresh machine, continue, and the result and effect trace equal the
uninterrupted reference run; `lift(lower(frame)) = frame`; the frame's
reason is the observed wait's. **For every engine intended to
reconstruct canonical frames**: the semantic-register kernel, the stack
kernel through §4.3, the register kernel through §4.2.

### 5.2 Rows

Each row is a program shape, a way to park it, and a way to satisfy its
wait, built as `safepoint_harness_test.cljc` builds its rows today
(`:117-304`) over the register-shaped evaluator and the two de Bruijn
kernels fed `derive/stack-image` and `derive/register-image` of
`project`'s vector. The corpus wraps each parking expression in four
segments as today (`:92-106`: bare, as an operand, three activations
deep, inside a closure with a captured environment), so every row is
exercised with an empty `K`, a non-empty `K`, and a non-trivial `E`.

| Row | Parking instruction | State | Delivery | Pending | Template today |
|---|---|---|---|---|---|
| blocked read | `:stream-next` | act | rd | `:next` | `read-row` (`:117-133`) |
| blocked write | `:stream-put` | act | rd | `:put` with the retained value | `write-row` (`:136-153`) |
| FFI sent | `:ffi-call` → ok | act | rd | `:ffi` with the kept response cell | `sent-ffi-row` (`:168-183`) |
| FFI retained | `:ffi-call` → full | act | rd | `:ffi-request` with the envelope and both routes | `retained-ffi-row` (`:186-206`) |
| effectful call, non-tail, stream | `:call` of `stream/next!` | act | rd | `:next` | `stream-call-row false` (`:251-270`) |
| **effectful call, tail, stream** | tail `:call` of `stream/next!` | **tail** | **return** | `:next` | `stream-call-row true` |
| link request / response, non-tail | `:call` of `require` | act | rd | `:link-request` then `:link-response` | `require-row false` (`:220-244`) at both phases |
| **link, tail** | tail `:call` of `require` | **tail** | **return** | `:link-request`, `:link-response` | `require-row true` |
| install, non-tail and tail | `:call` of `require` while the child runs | act / tail | rd / return | `:install` + the root's install entry with the child task | new: park the child (its body reads a stream) so the install is live at export (`parked-installer`, `v2_support.cljc:453`) |
| explicit park | `:park` | parked record (act) | rd | none; root kind `:parked` | `park-row` (`:273-283`) |
| reified capture | `:current-continuation` then a park | a `:reified` value in `W` or `E`; `pc−1` is the capture site | rd | the enclosing park's | new: `(let [k (current-continuation)] (f k (stream-next c)))`, so the capture is live in the window of the blocked read; also the capture **invoked after lower** |
| nested capture | a reified value whose own window holds a reified value | | | | new |
| module context | a closure defined in a linked module parks inside its body | act with `store-of` in `E` | rd | `:next` | `parked-module-store-cursors` (`lift_support.cljc:310`) |
| halt | `:halt` / `:return` with empty `K` | result | | | `halt-row` (`:286-290`) |
| **tail-effect halt** | a deferred tail completion whose `K` is empty halts on delivery | tail | return | `:next` | new: `((fn [c] (stream/next! c)) cursor)` in tail position at the root; the lowered task halts with the delivered value and an env free of `store-of` (evaluator §2.2 F2) |

Refusal rows: `:yin.k/not-quiescent` (a woken reader in the ready queue,
`a-nonempty-ready-queue-is-not-quiescent`, `:591-603`);
`:yin.k/not-at-safepoint` (a layout asked to lift a kernel at a pc whose
`pc−1` is not a parking instruction: constructed by stepping the register
kernel one instruction past a `:const` and asking `lift-frame`);
`:yin.k/non-portable :reason-mismatch` (the D4 `:observe` hold,
`a-held-immediate-refuses-export`, `:675-692`); and the **frame
validator's own** refusals on tampered rows: a window with an extra key
(`:window-extra`), a missing live key (`:window-missing`), the
destination present (`:window-self`), a wrong `rd` (`:destination`), a
return frame after a tail call (`:return-site`), a reason the site does
not admit (`:reason-mismatch`), a `:tail` row carrying a window
(`:frame-shape`), a nil-valued key dropped from a window
(`:window-missing`, the portability canary), a reified act whose `pc−1`
is not a capture (`:captured-site`), a parked act whose `pc−1` is not
`:park` (`:parked-site`).

### 5.3 Engines and lanes

**DECIDED** (frozen §10 phase 6: "not the semantic profile alone").
Three lanes: `:semantic-register` (the reference: its wait entries *are*
canonical frames plus engine fields), `:stack-through-layout`,
`:register-through-layout`. Every row runs on every lane. The de Bruijn
lanes load `(:image (derive/stack-image v))` and
`(:image (derive/register-image v))` of `v = (:vector (project P))`
through `dvm/create-vm`/`rvm/create-vm` (as `v2_support/load-ast` does
with the old lowerers, `v2_support.cljc:127-141`), run to the safepoint,
and lift through `layout/lift-frame`.

### 5.4 The laws, per row and lane

1. **Reference parity.** The lowered machine, satisfied through the
   source's streams, halts with the reference run's result and effect
   trace (`reference` / `handed-off`, `:368-401`, kept).
2. **Round trip.** `lift(lower(frame)) = frame` by root id after
   stream-blinding (`v2_support/stream-blind`, `:531-543`), under the
   fresh-receiver context of the draft's §9.
3. **Observed reason.** the frame's pending reason is the wait's
   observed reason, never the static kind (ucf-revisions §8).
4. **Cross-engine canonical equality** (new, the law ruling 6 makes
   possible): for one program at one safepoint, the canonical row sets
   lifted from the three lanes are **equal by root id** after
   stream-blinding and under controlled composition identities
   (occurrence, origin, resource descriptors fixed by the harness).
   This is the strongest statement of "one truth, many interpretations"
   the phase can test: the same `act`, the same window, the same `K`,
   the same `E`, whatever kernel held it.
5. **Cross-engine lowering.** a row set lifted on one lane lowers on
   each of the other two and continues to the reference result (the
   6 ordered pairs).
6. **Validator agreement.** every frame of every lifted row set passes
   `frame/defect` in its context; every tampered row of §5.2 is refused
   with the named kind; the validator's verdict is identical whichever
   lane produced the frame.

### 5.5 Observables and normalisation

**DECIDED** (phase-5 §6.1.2 `native-normalize`, reused). Results and
effect traces are compared under one normalisation applied to every
side: host-typed values unwrapped; a closure (host type or `{:type
:closure}` map) → `:closure` (a canonical closure carries `params` and a
named `env`, a de Bruijn one arity and frames; only closure-ness is
common to a value that crossed between them); a continuation →
`:continuation`; stream and cursor refs → `{:type t :id id}`; host fns →
`:host-fn`; collections recursively. Equality of **frames** (laws 2 and
4) is by row-set root id, which is exact and needs no normalisation
beyond stream-blinding.

### 5.6 Static checks the harness runs once per corpus segment

1. `frame/defect` on every frame the reference lane mints: nil.
2. For every parking pc `p` of `v`: `(analysis/saved a (inc p) rd)` has
   no member `≥ k(body(p))` and does not contain `rd`.
3. Register layout: `phys→virt(p, s)` is single-valued at every pc `p`
   and every physical slot `s` of `L(p) ∪ def(p−1)`'s image; the live
   operand of every boundary instruction of `R` equals the sorted
   physical image of `saved(p+1, rd)`.
4. Stack layout: the k-th non-push instruction of `H` has the mnemonic
   family of `v[k]` (`:var` ↔ `:load-bound`/`:load-free`, `:closure` ↔
   `:closure`, else equal mnemonics) for every `k`; counts agree.
5. Stack layout: `(set (stack-layout p)) = (analysis/saved a (inc p) rd)`
   for every parking pc `p`.
6. `E` reconstruction: at every safepoint reached, the layout's `E`
   equals the reference lane's `E` (as maps, values normalised).
7. Closure reconstruction: canonical ↔ positional closures round-trip on
   the `"closure value"` and `"higher order"` B0 rows and on
   `:duplicate-param`.

---

## 6. Code in the carrier: A and the tree association

### 6.1 What travels for code

**DECIDED** (ruling 1, ruling 3, ruling 4; phase-5 §7.6 and §9.2; v2
amendment §4's 2026-10-10 amendment: "`:yin.k/code` and `:yin.k/requires
:yin.k/segments` are keyed by the semantic A for all profiles; H and R
are not keys"). The root's `code` slot is plain `{A canonical-vector}`:
every vector a `"v4"` register-shaped vector that `code/well-formed?`
accepts, keyed by its own `ucf/code-address`, verified on lower
(draft §8 step 4). **No native image travels**: a stack or register
receiver derives `H(A)`/`R(A)` with `derive/stack-image`/`register-image`
after admitting A, and may verify held bytes against that derivation
(the frozen §4.3 "through their layouts" and the phase-5 lowering). The
carrier therefore has one code grammar for every receiver.

`requires` carries UCF §7.6.1's map **plus** `:yin.k/trees {A #{tree-address …}}`,
one entry per address in `:yin.k/segments`, the emitter's `source-index`
entry (phase-5 §9.2: "a UCF value with a segment in `:yin.k/segments`
and no `:yin.k/trees` entry is `:yin.k/missing :yin.k/trees` and
discovery `:incomplete`"). The tree itself does not travel; it is
retrievable by address, which is what unitarity asks. The derivations of
native images do not read it; the scanners (phase 5 §7) may.

### 6.2 Rows, not datoms

**DECIDED** (ruling 4). The carrier never projects a vector to datoms
and never carries a datom batch for code. Code-as-tuples §7.2's
"projection path" (vector → datoms → loader) is retired (§10.4); the
direct path is the only path, and the two AST projection lanes plus the
direct vector load feed one validator (frozen §3.4). Walker code (AST
rows) is outside this carrier: the walker is not a frame reconstructor
(§4.1).

### 6.3 The free environment

**PROPOSED.** A canonical `E` includes the task's initial free
environment (the `:env` a `create-vm` received, `semantic_register.cljc:261`),
merged under the lexical bindings (§4.4). A de Bruijn task holds that
same map as its task-wide `:free-env` (v2 amendment §2). The carrier
carries it **inside each `E`** as today's semantic lift does (every `E`
is a complete map; sharing is Merkle, so one `:bindings` row serves
every frame with the same bindings), and carries no separate `free-env`
slot: the root of the draft §5.5 therefore has no `free-env` and no
`layout` (those were the positional profiles'). A de Bruijn receiver
lowering a canonical `E` splits it back into its frames by the chain
(§4.4) and recovers the free part as `E` minus the chain's bindings.

---

## 7. Lift and lower

### 7.1 Lift

**DECIDED** (draft §9, UCF §7.8): refuse unless quiescent
(`:yin.k/not-quiescent`); refuse the holds (`:observe`, unminted cursor,
pending close, cursorless `:link-request`) as `:yin.k/non-portable`
`:reason-mismatch` (`unliftable-hold`, `handoff.cljc:588`); classify kind
(`:parked` when `:value` is a parked record held in `:parked`;
`:halted`; `:blocked` when the wait set is non-empty; else
`:yin.k/not-at-safepoint`); run the dependency closure
(`completion/complete` over the register-shaped vectors through the
phase-5 scanners, `scan/requirements`, `scan/free-names`) and refuse
`:yin.k/unsatisfied` unless `:complete`; number cells (draft §6); walk
waits (each `act`/`tail` payload → `:act`/`:tail` rows, its engine fields
→ the `:pending` row), parked records, the store slice, module stores,
the result, install children; run `frame/defect` over every frame; emit
rows and the census; the root's id is the body address.

For a de Bruijn source, the walk is the same over the frames
`layout/lift-frame` reconstructs; the kernel's own export code
(`handoff.cljc`'s version-2 adapters) is not used.

### 7.2 Lower

**DECIDED** (draft §8 in order, with step 7 replaced by §3.3): gate
(version 3, profile `{:yin.code/contract "v4" :yin.k/version 1}`),
structure, graph, hashes (every content row, every code vector to its
A), canonical form, canonical numbering, **frames** (`frame/defect` on
every state row in its context), pending and operations, dependency
closure (`requires` and `code` agree with the census; `:yin.k/trees`
complete), custody, composition, restore. Restore builds the receiving
kernel's state: for the semantic-register kernel, `code` through
`load-vector`/`attach-image` under `code/contract`, each `:wait` back to
an engine entry (`act`/`tail` payload plus the pending's engine fields,
`lower-frame!`'s shapes, `handoff.cljc:3182-3246`, with `:deliver`
carried), parked records into `:parked`, the result into `:value`; for a
de Bruijn kernel, `derive` the native image from each A, attach it, and
`layout/lower-frame` each frame into the kernel's payload shape.
Restoration delivers no value, runs nothing, mints nothing.

---

## 8. Slices, gates, entry conditions

Verification follows `docs/agents/build-n-test.md`: G0 per iteration on
the JVM, G1 `bb test:sub yin.vm` on three hosts at the checkpoint, G2
`bb test:changed`, G3 on master. New namespaces are under `yin.vm` by
prefix; the Node log must show `Testing <ns>` for each new test
namespace.

### 8.1 Slice 6a: the frame validator and the row codec

**Files to create.**
- `src/cljc/yin/vm/semantic_register/frame.cljc` (§3): `defect`,
  `saved-of` (the recomputation), public.
- `src/cljc/yin/vm/ucf/rows.cljc` (§2): the row table as data,
  `frame->rows`, `rows->frame`, `encode-row-id` (Jing default digest),
  the cell census and numbering per the draft §6 (ported from the draft's
  specification; nothing of `handoff.cljc` is edited), `lift-task`,
  `lower-task` for the semantic-register kernel, body version 3.
- `test/yin/vm/semantic_register/frame_test.cljc`,
  `test/yin/vm/ucf/rows_test.cljc`.

**Depends on:** phase 3 (landed), 4a (landed), 4b stage 1 (streams,
park, resume, current-continuation: `srvm-phase4b`) for the live rows
it lifts; FFI and link rows wait for 4b stage 2 and are 6b's.
**Touches nothing of 4b or 5.**

**Acceptance (6a).**

1. `frame-test/rules-are-independent-of-engine`: `frame/defect` takes a
   keyed frame and a code map only; its namespace requires no kernel
   namespace (asserted by inspecting the `ns` form's requires in the
   test, a string check over the source file read as a resource on the
   JVM lane, `#?(:cljd nil :clj …)`).
2. `frame-test/every-reference-frame-validates`: for every program of
   the phase-5 corpus `C` that parks on 4b stage 1 (the blocked-read,
   blocked-write, park and capture rows over the four segments of
   §5.2), every `act`, `tail`, return frame, parked record and reified
   value the register evaluator mints passes `defect` in its context.
3. `frame-test/each-rule-refuses-its-tamper`: for each of rules 1-14 one
   tampered frame built from a valid one, refused with that rule's kind
   and a path naming the slot; fourteen rows, each named by kind.
4. `frame-test/nil-valued-register-is-a-member`: a program whose live
   register holds nil at the safepoint (`(f (stream-next c) nil-reg)`
   with `[:const r nil]` evaluated before the read) validates with the
   key present; the same frame with the key filtered out is
   `:window-missing`.
5. `frame-test/worked-example-saved`: on `:worked-example`'s vector, a
   frame at pc 4 with window `{1 <f>}` and `rd 2` validates; `{1 <f> 2 x}`
   is `:window-self`; `{}` is `:window-missing`; `{1 <f> 3 x}` is
   `:window-extra` (`saved(4, 2) = {1}`, `analysis_test.cljc:131-139`).
6. `rows-test/bijection`: `frame->rows ∘ rows->frame` and the converse
   are identities on every frame of item 2, nil-valued keys included.
7. `rows-test/row-ids-are-jing-default`: every content row's id equals
   `(jing/segment-key body)` and names Jing's default algorithm
   (`:segment/blake3-…`); no `sha256` appears in any id.
8. `rows-test/one-profile`: a body whose profile map is anything but
   `{:yin.code/contract "v4" :yin.k/version 1}`, including the four
   version-2 maps, is `:yin.k/profile-mismatch` before any row is read;
   a body with version 0, 1 or 2 likewise.
9. `rows-test/lift-lower-round-trip`: for every 4b-stage-1 row of §5.2
   over the four segments on the semantic-register kernel,
   `lift(lower(S)) = S` by root id, and the lowered task continues to the
   reference result.
10. `rows-test/window-sorted-and-unique`: a `:window` row with keys out
    of order or duplicated is `:yin.k/undecodable` at step 5 (canonical
    form) naming the row.
11. kondo clean; `bb test:sub yin.vm` green on three hosts.

### 8.2 Slice 6b: the full harness on the reference kernel

**Files to change.** `rows.cljc`: the FFI, link and install rows (the
pending rows are already tabled; the lift walks the stage-2 entries).
**Files to create.**
`test/yin/vm/semantic_register/safepoint_harness_test.cljc`: §5 for the
`:semantic-register` lane (the old harness file is not edited; it tests
the old machine until phase 8).

**Depends on:** 4b stage 2 (FFI, link, install, `module/IModuleKernel`
for the register evaluator). Touches nothing of 4b or 5.

**Acceptance (6b).**

1. `every-row-every-segment-matches-the-reference`: the §5.2 rows ×
   four segments on the reference lane; laws 1-3 and 6 of §5.4; counts
   asserted (rows 15, segments 4).
2. `tail-states-carry-no-window`: for the two tail rows and the
   tail-effect halt, the lifted state row is `:tail` with three slots,
   and the lowered task delivers through `K` (pops a frame, or halts
   with an env free of `store-of`).
3. `install-row-carries-the-child`: the install rows carry the child
   task; lowering spawns no initialization and the waiter resumes with
   the module symbol.
4. `captures-round-trip-and-invoke`: the reified and nested-capture rows
   lower, and invoking the lowered continuation delivers once into the
   captured `rd` (the evaluator's `:continuation` arm).
5. `refusals`: every refusal row of §5.2 refuses with the named status
   and kind before any attachment (counting attachments as the old
   harness does).
6. `a-response-cursor-shared-by-two-waiters-crosses-as-one-cell` and
   `a-nonempty-ready-queue-is-not-quiescent`, ported to the reference
   lane.
7. kondo clean; `bb test:sub yin.vm` green on three hosts; the Node log
   shows `Testing yin.vm.semantic-register.safepoint-harness-test`.

### 8.3 Slice 6c: the two layouts and the cross-engine lanes

**Files to create.** `src/cljc/yin/vm/semantic_register/layout.cljc`
(§4); `test/yin/vm/semantic_register/layout_test.cljc`; the two extra
lanes added to the 6b harness file.

**Depends on:** phase 5a and 5b landed (`derive/stack-image`,
`derive/register-image`); 6b. **Touches nothing of 5** (it calls
`derive`'s public functions and reads the two kernels' public state).

**Acceptance (6c).**

1. `layout-test/register-pairing-is-total`: for every `P ∈ C`, every
   virtual register of every body has a physical pair read off `(v, R)`,
   and `phys→virt(p, s)` is single-valued at every pc (§5.6 item 3).
2. `layout-test/register-live-is-the-saved-image`: §5.6 item 3, second
   clause, for every boundary pc of every `P ∈ C`.
3. `layout-test/stack-correspondence`: §5.6 item 4 for every `P ∈ C`.
4. `layout-test/stack-layout-is-the-saved-set`: §5.6 item 5 for every
   parking pc of every `P ∈ C`; and the two worked cases pinned:
   `(f (g x) y)` inner call → `[1]`; `(f (if c (g) 2) y)` at `(g)` → `[1]`.
5. `layout-test/env-reconstruction`: §5.6 item 6 over the harness rows
   and the `:duplicate-param`, `:nested-closure`, `:nested-lambdas`
   programs.
6. `layout-test/closures-round-trip`: §5.6 item 7.
7. `harness/cross-engine-canonical-equality`: §5.4 law 4 for every row ×
   segment: the three lanes' row sets are equal by root id after
   stream-blinding.
8. `harness/cross-engine-lowering`: §5.4 law 5, the six ordered lane
   pairs, every row × segment, reference parity.
9. `harness/layout-refusals`: `:yin.k/not-at-safepoint` from a kernel
   stepped to a non-safepoint pc; a kernel state whose live operand was
   tampered reconstructs a frame the validator refuses (`:window-extra`).
10. kondo clean; `bb test:sub yin.vm` green on three hosts.

### 8.4 Phase 7's entry conditions

**DECIDED** (frozen §10 phase 7: "Stage D/E rerun … over the new
transport, three hosts"). Phase 7 may start when: 6a-6c are green on
three hosts; the row carrier is the only writer and reader the
semantic-register kernel has (`rows/lift-task`, `rows/lower-task`); the
holder, checkpoint, ledger and authority paths (`yin.vm.ucf.holder.*`,
`checkpoint.cljc`, `ledger.cljc`, `authority.cljc`) have a documented
seam to call them (phase 7 wires it; phase 6 does not edit those
namespaces); and the Stage D/E scenarios
(`test/yin/vm/ucf/stage_e_scenarios.cljc`, `checkpoint_fixtures.cljc`)
have been listed with, for each, the row of §5.2 that exercises its
parking shape, so phase 7 re-encodes known shapes.

### 8.5 Ordering and concurrency

| | needs | concurrent with |
|---|---|---|
| 6a | 4b stage 1 (branch) | 4b stage 2, 5a, 5b, 5c |
| 6b | 4b stage 2 | 5a, 5b, 5c |
| 6c | 5a, 5b, 6b | 5c |

No file of 4b (`semantic_register.cljc`, its tests, the four backend
maps) or of phase 5 (`derive.cljc`, `scan.cljc`, `linker.cljc`,
`link.cljc`, their tests) is edited; `handoff.cljc` and the old harness
are not edited (they test the stack-shaped machine and leave at phase
8). The one shared seam is `module/IModuleKernel` on the register
evaluator (4b stage 2's `link-format`, `lift-closure`, `lower-closure`),
which `rows.cljc` calls and does not define.

---

## 9. Verification honesty

- **Hand-derived numbers.** `saved(4, 2) = {1}` and `L(5) = {1,2,5}` on
  the worked example are the phase-3 tests' (`analysis_test.cljc:131-139`),
  not mine. The stack layout `[1]` at the inner call of `(f (g x) y)` and
  at `(g)` in `(f (if c (g) 2) y)` were derived by hand twice from the
  §4.3 walk and checked against those saved sets; the register layout's
  `phys(1) = 0` for the worked example follows from the phase-5 §6.3
  row 2 (`f` in `T0`), itself hand-derived there and confirmed by the
  reviewer. Nothing was run.
- **Claims that are checks, not theorems.** (i) the stack operand slice
  equals the saved window as a set (§4.3; §5.6 item 5); (ii) the
  non-push H sequence corresponds one-to-one with A (§4.3; item 4);
  (iii) `phys→virt` is single-valued per pc (§4.2; item 3); (iv) the
  layout's `E` equals the evaluator's `E` (§4.4; item 6). Each has an
  argument in its section and a corpus-wide test; the implementation
  relies on none of them silently.
- **Private access.** Every function a test names is public:
  `frame/defect`, `rows/frame->rows`, `rows/rows->frame`,
  `rows/lift-task`, `rows/lower-task`, `layout/register-layout`,
  `layout/stack-layout`, `layout/lift-frame`, `layout/lower-frame`,
  `derive/register-image`, `derive/stack-image`, `analysis/saved`,
  `code/parse`; no `#'` anywhere (`debruijn_register_contract_test.cljc:256-262`
  records why).
- **Universal claims avoided.** No sentence says every kernel can
  reconstruct a canonical frame; the two that are intended to are named
  and tested; the walker is excluded with the reason.
- **Unverified.** The 4b stage-2 shapes (FFI, link, install entries on
  the register evaluator) are specified from the evaluator design and
  the stage-1 branch; stage 2 is not landed, so 6b's rows are written
  against the design's §1.4-1.5 shapes and must be re-read when stage 2
  lands.

---

## 10. Findings and proposed amendments

### 10.1 To the frozen design (`yin.vm.semantic-register-vm.md`)

**F1 — §4.2 row names (PROPOSED).** The last paragraph says "`:regs`
becomes `[segment-A pc env-cref window-cref k-cref deliver-D]` for an
`act` and a sibling `:tail-state [segment-A site k-cref deliver-D]` is
added". Replace with: "the frame rows are `:act [segment-A pc env-cref
window-cref k-cref rd]` and `:tail [segment-A site k-cref]`, the kind
being the delivery discriminant (`:act` delivers to `rd`, `:tail`
through return), so `deliver` is not a slot; `:window` is a keyed row
`[r₁ V₁ r₂ V₂ …]` sorted by `r` in which a nil-valued register is a
present `[:lit nil]`; `:kframe` is `[segment-A pc env-cref window-cref
rd]`; `:stack` and `stack-base` are removed. The amendment is
`yin.vm.semantic-register-vm.continuation.md` §2."

**F2 — §4.2 "the full frame validator" (DECIDED, made exact).** After
"and pending compatibility with `reason`" insert: "The fourteen rules,
their order and their refusal kinds are
`yin.vm.semantic-register-vm.continuation.md` §3.2; the validator is a
pure function of the decoded frame and the carried code and knows no
engine."

**F3 — §4.3 foreign engines (DECIDED, made exact).** After "`lift(lower(frame))
= frame` holds for admitted canonical frames, as today." insert: "The
register kernel's layout pairs physical and virtual registers pc by pc
from the two images `v` and `R(v)`, which share pcs; the stack kernel's
layout corresponds the non-push instructions of `H(v)` with `v` and lays
the saved window out as the activation's operand stack in emission
order; both kernels' positional frames become `E` through the owner
chain of A and `bind-params` (continuation design §4). The AST walker is
not a frame reconstructor."

**F4 — §10 phase 6 (DECIDED, made exact).** Replace "the row carrier
amended" with "the row carrier amended to body version 3 with one
profile, `{:yin.code/contract "v4" :yin.k/version 1}`, carrying
canonical vectors by A and `:yin.k/trees`, never a native image
(continuation design §2, §6)"; and after "through their layouts" insert
"(the register and stack kernels; the walker is excluded)".

### 10.2 To UCF (`yin.vm.universal-continuation-format.md`)

**U1 — §7.3.4 `:511`.** Replace "`:yin.k/carried` carries vectors, not
batches." with "`:yin.k/carried` and the body's code map carry
**register-shaped canonical vectors keyed by A**, never batches, never
datoms projected from a vector (the projection path is retired,
code-as-tuples §7.2 as amended), and never a native stack or register
image: a receiver of either native kernel derives its image from A
(`yin.vm.semantic-register-vm.derivations.md` §2-3). `:yin.k/requires
:yin.k/trees` maps every address in `:yin.k/segments` to the set of tree
addresses the emitter's `source-index` held for it (derivations design
§9.2)."

**U2 — §7.4.1.** The frozen design §4.1 rewrote this section; add at
its head: "Superseded for the register-shaped machine by
`yin.vm.semantic-register-vm.md` §4.1 (two state columns, `act` and
`tail`, and the `deliver` record); the table below describes the
stack-shaped machine, which leaves at that design's phase 8." Replace
the `:yin.k/frame` example with the §1.2 keyed form of this document.

**U3 — §7.4.2.** Replace "`[sp :yin.safepoint/stack-effect -1]`" with
"`[sp :yin.safepoint/def #{…}] [sp :yin.safepoint/use #{…}] [sp
:yin.safepoint/live #{…}]`  ; def(pc), use(pc), L(pc) derived from A
(frozen §8.1)" and the rule "`stack-effect` and `lexically-required` are
derived from the canonical vector alone" with "`def`, `use`, `live` and
`lexically-required` are derived from the canonical vector alone
(`yin.vm.semantic-register.analysis`)". Replace "E, St, and every K
frame's `:stack-base` and env travel in the value" with "E, the saved
window, and every K frame's window, `rd` and env travel in the value".
Replace "`:yin.safepoint/layout` … physical slot → canonical slot" with
"physical location → virtual register (continuation design §4)".

**U4 — §7.4.3.** Add after "Three rules make these resumable": "A
pending rides on an `act` state (write-result delivery) or a `tail`
state (through-return delivery); the variants are unchanged, and
`:yin.k/registers R` of the version-2 amendment is the `act`/`tail`
state of the frozen design §4.2." No variant changes.

**U5 — §7.6.** In §7.6.1's `:yin.k/requires` example add the line
`:yin.k/trees {A #{tree-address …}}  ; §7.6.1a, every segment's source trees`
and a new paragraph **§7.6.1a The source association**: the phase-5
§9.2 UCF row text, verbatim.

**U6 — §7.11.** In "Safepoint conformance harness" replace "including
`lift ∘ lower = id`, the static `stack-effect`/`lexically-required`
checks" with "including `lift ∘ lower = id`, the static `def`/`use`/`L`
and `lexically-required` checks, the frame validator on every frame,
and **cross-engine canonical equality**: one program at one safepoint
lifts to one canonical row set from the semantic-register kernel and
from the stack and register kernels through their layouts (continuation
design §5.4 law 4)". In §7.11.1 "Safepoint reconstruction", replace
"Separately prepare two independently implemented UCF engines" with
"Separately prepare the three engines intended to reconstruct canonical
frames" and "the frame, pending wait, next result, and effect trace must
agree in both directions" with "the canonical row set, pending wait,
next result and effect trace must agree across all three and in every
lowering direction".

### 10.3 To the row-carrier draft (`yin.vm.ucf-transport-tuples.md`)

**R1 — §2.** Replace the section with: "**Body version 3, one
profile.** The carrier is body version 3. It reads no other version
(owner, 2026-10-11: no legacy). The profile map is `{:yin.code/contract
"v4" :yin.k/version 1}`, the canonical register-shaped machine's
contract; there is no engine key, because the frame is the canonical
machine's and the kernel that executes it is the receiving composition's
choice (`yin.vm.semantic-register-vm.continuation.md` §2.1). A body
naming any other profile is `:yin.k/profile-mismatch`."

**R2 — §5.2.** Replace the table and paragraph with §2.3 of this
document (the `:act`, `:tail`, `:window`, `:k`, `:kframe`, `:wait`
rows) and its notes.

**R3 — §5.1 `:reified`.** Replace `[:reified regs-cref]` with
`[:reified act-cref]` and "its pc must be a `:current-continuation`
site's successor, not a wait or park safepoint" with "its act's `pc−1`
is a `:current-continuation` and it has no pending (frame rule 13)".

**R4 — §5.4 `:parked-table`.** Replace `regs-cref` with `state-cref`
(`:act` or `:tail`) and add frame rule 14's bookkeeping clause.

**R5 — §5.5 the root.** `contract` is the one profile map of R1; the
root has no `free-env` and no `layout` slot (continuation design §6.3);
`code` is `{A canonical-vector}` of `"v4"` vectors.

**R6 — §8 step 7.** Replace with: "**Frames.** Every `:act`, `:tail`,
`:kframe`, `:reified` and parked-table state is decoded to its keyed
frame and judged by `yin.vm.semantic-register.frame/defect` with its
context (`:wait`, `:captured`, `:parked`); a defect is
`:yin.k/undecodable` with the rule's kind and the row path
(continuation design §3)."

**R7 — "the `v3` stamp does not move" (§2, §10.4).** Delete; the code
stamp is `"v4"` and the carrier version is 3; neither statement about
versions 0-2 survives.

**R8 — §9 "Legacy correspondence" and §10.3 "the nested grammar".**
Delete: there is no legacy reader and no nested projection; rows are the
one carrier.

**R9 — §10.7 "other profile tables".** Delete: there are no other
profiles; the stack and register kernels reconstruct canonical frames
(§4).

### 10.4 To code-as-tuples (`yin.vm.code-as-tuples.md`) §7.2

**C1.** Replace the first paragraph ("This section supersedes the
sentence in UCF §7.3.4 … Everything else in §7.3.4 stands.") with: "The
vector-to-datoms projection path is **retired** (owner ruling of
2026-09-18, recorded in `yin.vm.semantic-register-vm.md` §3.4 'One vector
validator'). There is one load path for a vector, the direct one, and
two AST projection lanes (datom lane, row lane) that produce the vector;
all three feed one validator (`yin.vm.semantic-register.code/well-formed?`).
UCF §7.3.4's projection path is superseded accordingly." Replace
obligation 2's second sentence ("loading the instruction vector directly
and loading its datom projection yield the same image") with "loading
the vector produced by either AST lane and the vector loaded directly
yield the same image and address (`load_paths_test.cljc`)", and
obligation 3's "both paths refuse with the same defect" with "the
direct vector load refuses with the published defect (rule and pc) for
every rule of `yin.vm.semantic-register-vm.md` §3.4". Obligations 1 and
4 stand.

### 10.5 Checked and consistent (no amendment)

Frozen §2.4 delivery shapes against the 4b branch (`act-payload`,
`tail-builders`, `:deliver` record); frozen §4.1 two state columns; the
evaluator design §1.3-1.6 shapes; the draft's §3, §4, §5.1 (except
`:reified`), §5.3, §6, §7 and §8 steps 1-6, 8-12; v2 amendment §2's `E`
shape (reused as the `:env` row); the phase-5 §9.2 `:yin.k/trees` text.

---

## 11. Owner questions

**None.** Two design choices are the Architect reviewer's: dropping
`:yin.k/engine` from the profile map (§2.1) and carrying the free
environment inside every `E` rather than as a root slot (§6.3). Both
follow from ruling 6 as this document reads it; the reviewer may prefer
an explicit engine tag for diagnostics without changing any gate.

---

## 12. Portability notes for the implementer

1. **Nil-valued window keys (Dart, Node).** The window is built with
   `select-keys` and encoded as `[r [:lit nil]]`; never filter `some?`
   values, never `(into {} (filter val …))`; `frame-test/nil-valued-register-is-a-member`
   is the canary. `(get W r)` is nil for absent and for nil alike, so
   membership is always by `contains?`/key sets.
2. **`for` over large seqs (Dart).** Row sets of the C4 modules run to
   thousands of rows; build with `mapv`/`into`/`reduce`.
3. **Reader whitespace before closers (Dart).** Pinned row sets and
   fixtures are Clojure data in `.cljc`, never EDN strings.
4. **Duplicate protocol params (Dart).** `gc-children [_ _x]`
   (`srvm-phase4b:semantic_register.cljc:391`); any new protocol method
   in `layout` likewise.
5. **Multi-key `assoc` on nil (Dart).** Frames and windows start from
   `{}`; `(assoc nil :a 1 :b 2)` is a list on Dart (`handoff.cljc:1455-1457`
   records the trap).
6. **Reader conditionals.** `#?(:cljd Object :clj Throwable :cljs :default)`
   with `:cljd` first; `:clj`-only source-reading tests as
   `#?(:cljd nil :clj …)`.
7. **Private var access (Dart).** Every function named in §8 is public;
   no `#'`.
8. **Sorted keys.** Window rows and bindings rows are sorted by Jing
   canonical bytes of the key, never by host `sort` on mixed types;
   integers sort numerically under canonical CBOR.
9. **Numeric classes (Node).** `pc`, `rd`, `r` are exact integers;
   compare with `cbor/numeric-kind` before `=` where a value came off the
   wire (`ucf/profile-engine`, `ucf.cljc:77-91`, is the template).
10. **Fresh worktree.** `mise trust` and `npm ci` before the Node lane.
