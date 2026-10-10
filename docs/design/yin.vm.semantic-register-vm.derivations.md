# yin.vm.semantic-register-vm.derivations: phase 5, the de Bruijn images and the scanners lowered from A

Status: **Companion design, phase 5 of `yin.vm.semantic-register-vm.md`
§10.** Rewritten 2026-10-11 (Architect: claude-fable-5-1) under the
owner's rulings of that date (§0.1), from the frozen design as amended
(`ab952ace`), the landed phase-3 code
(`src/cljc/yin/vm/semantic_register/`), the de Bruijn image formats,
validators and kernels as they stand, the linker and dependency-closure
code, and the corpora the gates run on. Earlier revisions of this
document (route (b), the adapter over the old lowerers, codex rounds
1-3) are superseded; what they settled about the scanners, the C4 gate,
public access and portability is carried over unchanged. The frozen
document is not changed by this one: §9 records findings with exact
amendment text. Subordinate to [`datom.world.md`](./datom.world.md),
including its unitarity invariant (§0.1 item 4), and to
[`yin.vm.semantic-register-vm.md`](./yin.vm.semantic-register-vm.md)
("the frozen design"); the format and rigor model is
[`yin.vm.semantic-register-vm.evaluator.md`](./yin.vm.semantic-register-vm.evaluator.md).

Every section marks its content **DECIDED** (follows from an owner
ruling, frozen text, or code that exists) or **PROPOSED** (a design
choice of this document, open to the reviewer). File references are to
the worktree at `ab952ace`; line numbers are those of that commit.

Contents: §0 rulings and the one-paragraph answer · §1 the resolved
vector · §2 A → R, linear-scan allocation over A's liveness · §3 A → H,
stackification over A's expression structure · §4 determinism and the
facts A omits · §5 the lexical-address law · §6 verification: the
execution oracle, validators, goldens · §7 the scanners and the linker
records · §8 regeneration and what keeps the old lowerers alive · §9
findings and amendments (frozen design, linker, UCF) · §10 slices and
gates · §11 owner questions · §12 portability.

---

## 0. Rulings and the one-paragraph answer

### 0.1 Owner rulings of 2026-10-11 (DECIDED, binding)

1. **R and H are lowered from A.** A, the semantic tuples, is the
   linearization of the AST; lowering from the tree would re-linearize.
   One linearization, everything else derives from it: same A, same R,
   same H.
2. **No legacy.** No version bump (`"b2"`/`"r2"` keep their names; no
   `b3`/`r3`), no re-version procedure, no lift-contract question, no
   profile-string question. The lowerers change in place. Pinned
   checksums, goldens and any descriptor hashing a lowerer's contract are
   regenerated once (§8 says which, and how a reviewer verifies the
   regeneration is mechanical).
3. **No adapter over the old lowerers.** New derivations: A → R as
   linear-scan register allocation over A's liveness (frozen §8.2 is the
   starting point), A → H as stackification over A's expression
   structure (frozen §8.3); both pure functions of A. `lower-register`,
   `lower-stack` and `debruijn-resolve` are deleted at cutover (phase 8);
   §8.3 says what keeps them alive until then.
4. **Unitarity.** `datom.world.md` (branch `worktree-invariant-unitarity`,
   invariant list): "Do not lose information (unitarity): streams are
   append-only and no datom is destroyed, only reinterpreted. The
   Universal AST is never lost: it is always retrievable wherever code is
   lowered, by address or by a query over its datoms. Every derived form
   (semantic vector, native images, indexes) is an interpretation of it
   and may drop facts only because the AST remains." No design may assume
   a receiver without a tree. The scanners and provenance may read the
   tree; the derivations of R and H do not need it. §9.2 names the
   conflicting statements in the linker and UCF designs and proposes
   amendment text.
5. **Verification replaces the byte oracle.** The old compilers are an
   execution oracle only: the corpus (B0, the register corpus, the C4
   linked-prelude corpus by name, read-only) runs through old and new
   pipelines and must give identical observable results under a stated
   normalization. Validators and liveness checks run on every output;
   hand-derived goldens pin the tricky cases; a determinism check covers
   same-A-same-bytes and ASTs differing only in facts A omits.

### 0.2 The answer

Phase 5 is three pure functions of an admitted register-shaped vector
`v` (A = `ucf/code-address v`). `resolve` (§1) rewrites every `:var` to
`:load-bound`/`:load-free` and every `:closure` to its arity, using the
closure-owner tree the validator already established; its output is the
*resolved vector*, same pcs, same virtual registers, a stage value with
no identity. `register-image` (§2) allocates physical temporaries per
body by a linear scan over intervals computed from the phase-3 liveness
`L`, fills the `live` operands with the format's one liveness function,
and emits the existing `:yin.debruijn.register/*` image; the allocation
rule is the frozen §8.2's, closed where that text left choices open and
corrected in one place (§9.1 F8). `stack-image` (§3) emits the existing
`:yin.debruijn.code/*` image by one walk over the parse trees the
validator recovers: operator, push, operands with pushes, call, labels
for conditionals, bodies out of line in FIFO order. Neither function
reads the tree, a side table, or anything but `v`; neither calls an old
lowerer. The image formats, descriptors, validators and kernels are
unchanged, so every existing refusal rule, the `live-exact` recomputation
and the kernels' execution semantics still admit and run the new images.
The hand-derived goldens of §6.3 fix the bytes for `(+ 1 2)`,
`(f (g x) y)`, `((f))`, two conditionals, a tail call, two definitions and
a terminal operand; the execution oracle of §6.1 runs every corpus
program through the old and new pipelines on the same kernels and
requires identical observable results; §4 shows, with evidence, that
the facts A omits cannot reach R or H. The scanners (§7) are unchanged
from the reviewed design.

---

## 1. The resolved vector

### 1.1 Where it lives, what it takes, what it returns

**PROPOSED.** Namespace `yin.vm.semantic-register.derive`, file
`src/cljc/yin/vm/semantic_register/derive.cljc`, beside the landed
phase-3 namespaces. It requires `yin.vm.semantic-register.code`
(`parse`, `body-ranges`, `body-index`, `rd`, `uses`, `tail-call?`),
`yin.vm.semantic-register.analysis` (`analyze`, `live`),
`yin.vm.debruijn` (`resolve-name` only), `yin.vm.debruijn-register-code`
(`body-liveness`, `live-slot-index`, `boundary-opcodes`, the image
shape it validates) and `yin.vm.debruijn-code` (nothing but the opcode
table it emits into; no function). It does **not** require
`yin.vm.debruijn-resolve`, `yin.vm.debruijn-linearize`,
`yin.vm.debruijn-register-compile`, `yin.vm.linearize`, `yin.vm.code`,
or either kernel.

```clojure
(resolve v)   ; v: a vector code/well-formed? accepted
;; => {:vector rv            ; the resolved vector: same length, same pcs, same ids
;;     :bodies bodies        ; code/parse's bodies with :expr trees, :owner, :start, :end
;;     :names  {pc name}}    ; diagnostic: the name every :load-bound replaced
```

**DECIDED** (frozen §8.2 step 1: "the output is a stage value with no
identity"): the resolved vector is never hashed, never persisted, never
served, never a fetch key. The namespace exports no digest of it.
**DECIDED** (precondition): `v` has passed `code/well-formed?`
(`code.cljc:842-853`); `resolve` re-runs `code/parse` (`:856-861`)
because `load-vector` strips the `:expr` trees (`:879`), the same stance
frozen §10 phase 3 takes for `:analysis`.

### 1.2 The closure-owner tree and the lexical chain

**DECIDED** (frozen §3.4 rule 6; `code/body-ranges`, `code.cljc:256-276`;
`partition-defect`, `:292-336`). `body-ranges` gives, in pc order, one
body per distinct `:closure` body-pc plus main, each with `:owner`, the
pc of the `:closure` naming it (nil for main); `partition-defect` has
established that every non-main body has exactly one owner in another
body and that following owners reaches main.

```
chain(main)   = []
chain(body i) = (cons params(owner_i) (chain (body-of owner_i)))
params(c)     = (nth (nth v c) 2)                 ; [:closure rd params body-pc]
body-of(pc)   = (code/body-index bodies pc)
```

`chain` is innermost-first, the `stack` argument `debruijn/resolve-name`
takes (`debruijn.cljc:617-637`).

### 1.3 The rewrite

**DECIDED** (frozen §8.2 step 1). For every pc of `v`:

| A tuple | resolved tuple |
|---|---|
| `[:var rd name]`, `(resolve-name (chain (body-of pc)) name)` = `{:bound [d q]}` | `[:load-bound rd d q]` |
| `[:var rd name]`, `{:free name}` | `[:load-free rd name]` |
| `[:closure rd params body-pc]` | `[:closure rd (count params) body-pc]` |
| every other tuple | unchanged |

Duplicate parameters and shadowing are `resolve-name`'s by construction:
rightmost wins within a frame (`debruijn.cljc:621-624`; pinned by
`debruijn_test.cljc:391-402`), innermost frame wins across frames
(`:363-389`), free names preserved exactly (`:405-410`). A's
`:closure params` is the front end's vector verbatim (item 2 `:syms`,
`code.cljc:177`; `linearize.cljc:105`), so the frames searched are the
binders the program wrote. Rule R needs no check here: item 11
(`code.cljc:663-675`) has refused any `:var` naming `yin/def`, so no
`:load-free yin/def` can be emitted; the kernels' `reserved-rule`
(`debruijn_register_code.cljc:663-675`, `debruijn_code.cljc:864`) would
refuse it anyway.

### 1.4 Parse trees and layout, carried as derived data

The resolved vector keeps A's pcs, so `code/parse`'s trees
(`{:op :pc :children}`, conditionals `{:op :if :pc :jump :end :children
[test then else]}`, `code.cljc:350-406`) and `analysis/analyze`'s
`:live`, `:successors` (`analysis.cljc:70-83`) index it directly. §2 and
§3 consume `{:vector :bodies}` plus `(analysis/analyze v)`; nothing
else.

---

## 2. A → R: linear-scan allocation over A's liveness

### 2.1 The output format, unchanged

**DECIDED.** The image is `{:bodies [{:locals n :registers k :start s :end e} …]
:instructions [tuple …]}` over `rcode/opcode-table`
(`debruijn_register_code.cljc:68-149`), `:end` **inclusive**
(`:243-245`, `body-liveness` `(range start (inc end))`, `:352`); body 0
is main with `:locals 0`; a lambda body's `:locals` is its arity and
registers `0..locals-1` are the L bank, never written or read by any
instruction (`:17-27`: `:load-bound` reads the frame chain into a fresh
temporary); a temporary `Tᵢ` is physical register `locals + i`. The
descriptor (`:190-204`), `contract-version` 4 (`:52-59`), the encoder
(`:221-254`), `register-hash` (`:261-269`) and the twelve validator rules
(`:698-705`) are **not** changed. `contract-version`'s docstring sentence
"bumped whenever this dimension's … allocation … change shape" is
superseded by ruling 2 and is amended at cutover (§8.2); the number is
not bumped.

### 2.2 Intervals

**DECIDED** by frozen §8.2 ("Intervals"), **refined** (§9.1 F8) so that
every live point is covered. For one body with pcs `[s, e)` (`e`
exclusive as `code/body-ranges` gives it), `L(p) = (analysis/live a p)`
(`analysis.cljc:86-89`), `defs(v)` the pcs whose `rd` slot names `v`
(`code/rd`), the virtual ids with at least one syntactic definition are
**intervaled**:

```
points(v) = defs(v) ∪ {p ∈ [s,e) : v ∈ L(p)}
start(v)  = (apply min points(v))
end(v)    = (apply max points(v))
```

On reachable code `start(v)` is the first syntactic definition (definite
assignment, frozen §3.4 item 8, puts every read after a write, and
`L` points before the first definition arise only in layout syntax: see
the `(if c (resume :p 1) 2)` note in §6.3 row 7). The frozen text's
"start = first syntactic definition" is therefore kept where it matters
and widened only over layout syntax, where an uncovered live point would
otherwise let the scan assign a slot the layout instruction still names.
A conditional's `rd` has one interval spanning both arms from its first
arm definition to its last use, as frozen §8.2 states. A tail `:call`'s
`rd` and the `:return r` that names it form an interval `[call-pc,
return-pc]` of a slot never written at runtime.

**Definition-less ids** (frozen §3.4 item 7: an id whose every leaf is a
`:resume`) have no interval and no slot; where layout syntax names one
(a wholly terminal body's `[:return r]`/`[:halt r]`, an enclosing
instruction's operand after a terminal child) the operand lowers to the
**structural slot** `T_k`, `k` the body's peak count (§2.3). It is never
written, never read at runtime, never in a saved window; it may appear in
the static `live` operand of an unreachable boundary instruction, a
static fact of the format and nothing more (frozen §8.2 "Definition-less
ids", retained).

### 2.3 The scan

**DECIDED** (frozen §8.2 "Scan", with its two open choices closed):

```
free   := the empty sorted set of temporary indices
next   := 0                                   ; next never-used index
peak   := 0
slot   := {}                                  ; virtual id → temporary index
for p from s to e-1:
  for each intervaled v, in increasing end(v) then increasing v,
      with end(v) < p and v not yet expired:        free := free ∪ {slot(v)}
  for each intervaled v with start(v) = p, in increasing v:
     if free is non-empty: i := (first free); free := free − {i}
     else:                 i := next; next := next + 1       ; a never-used slot
     slot(v) := i; peak := max(peak, i+1)
registers := locals + peak + (1 if the body names a definition-less id, else 0)
```

- The two loops are the frozen scan's, with their **total order**
  (increasing `end` then increasing virtual id for expiry; increasing
  virtual id for assignment) kept verbatim, so the result never depends
  on how many intervals happen to start or expire at one pc. On a
  well-formed vector at most one interval starts per pc (one instruction
  defines one register; a layout-syntax live point belongs to one
  enclosing destination), but the implementation does **not** rely on
  that: the loop runs over whatever starts. `next` advances only when a
  never-used slot is taken, so `peak = next` at the end of the scan.
- **No same-instruction source/destination reuse** (frozen): expiry is
  `end < p`, so a source whose interval ends at `p` is still assigned
  when `p`'s destination is allocated; the destination never takes a
  source's slot.
- **Lowest free index first**; `next` only when the free set is empty.
- Physical register of `v` is `locals + slot(v)`; the structural slot is
  `locals + peak`.
- Reservation (an interval holding a slot across a conditional's arms)
  decides allocation only; liveness (§2.5) comes from the format's own
  function, never from the scan (frozen §8.2 "Reservation vs liveness").

### 2.4 Emission

Each tuple of the resolved vector at pc `p` maps to one register tuple
at the same pc, every `:reg`/`:regs` operand replaced by its physical
register, every literal operand copied, and the boundary opcodes
(`rcode/boundary-opcodes`, `:156-159`) given a placeholder `live`
`[]` that §2.5 fills:

| resolved tuple | register tuple |
|---|---|
| `[:const rd v]` | `[:const R(rd) v]` |
| `[:load-bound rd d q]` / `[:load-free rd n]` | `[:load-bound R(rd) d q]` / `[:load-free R(rd) n]` |
| `[:closure rd arity body-pc]` | `[:closure R(rd) arity body-pc]` (body pcs are A's; the image's pcs are A's) |
| `[:call rd f args tail?]` | `[:call R(rd) R(f) (mapv R args) tail? live]` |
| `[:branch-false c t]` / `[:jump t]` | `[:branch-false R(c) t]` / `[:jump t]` |
| `[:return r]` / `[:halt r]` | `[:return R(r)]` / `[:halt R(r)]` |
| `[:gensym rd p]` `[:store-get rd k]` `[:store-put rd k x]` `[:stream-make rd b]` `[:stream-cursor rd s]` `[:stream-close rd s]` `[:define rd n rs]` | the same mnemonic with registers mapped |
| `[:stream-put rd s x]` `[:stream-next rd c]` `[:ffi-call rd op args]` `[:current-continuation rd]` `[:park rd]` | the same with registers mapped and a trailing `live` |
| `[:resume id x]` | `[:resume id R(x)]` |

`R(v)` is `locals + slot(v)` for an intervaled `v` and the structural
slot for a definition-less `v`. **Layout is A's**: bodies and pcs are
identical to the vector's (frozen §8.2 "Layout"), so `:bodies` is
`code/body-ranges` with `:end` decremented to the inclusive form and
`:locals`/`:registers` added. There is no `:move` (frozen §2.3).

### 2.5 `live`

**DECIDED** (register design §4.5: "exactly one definition of liveness in
the format"; frozen §8.2 step 3). After every body is emitted and the
image assembled, `live` is filled exactly as R1 fills it today: for each
body index, `(rcode/body-liveness image bi)` (`debruijn_register_code.cljc:342-385`)
gives `{boundary-pc live-vector}`, written into the tuple at
`(rcode/live-slot-index op)`. A tail `:call` therefore gets `[]` (no
successors), and the validator's `live-exact` rule (`:631-649`)
recomputes the same function on every receiving host.

**Checked, not assumed** (§6.2 item 4). For every boundary pc `p`, let
the **virtual live-out** be `⋃ { L(s) : s ∈ (analysis/successors v p) }`
over the **actual** successors of §8.1 (`analysis.cljc:15-28`: none for
a tail `:call`, `:return`, `:halt`, `:resume`; the target for `:jump`;
both for `:branch-false`; `p+1` otherwise). The filled `live` must equal
the sorted physical image of `(virtual-live-out(p) − {rd})`. For a
non-tail boundary that is `L(p+1) − {rd}`; for a **tail `:call`** the
successor set is empty, so the virtual live-out is `{}` and `live` is
`[]`, which is also what `body-liveness` returns and what
`live-tail-rule` requires (`debruijn_register_code.cljc:621-628`).
`L(p+1)` itself is **not** the right comparison for a tail call: in
`(f (g x) y)` with the inner call marked tail (§6.3 row 9) the layout
syntax after the inner call keeps `f` in `L(p+1)` while nothing can
reach it, so `L(p+1) − {rd}` would be `{f}` and the gate would wrongly
fail. The two analyses are independent (physical image with slot reuse
versus virtual ids); the argument that they agree is that all edges go
forward in pc, the slot map is injective over simultaneously intervaled
ids, and no reachable instruction reads a slot after its interval ended.
It is a property the gate checks on every corpus program, not a theorem
the implementation relies on: production `live` is `body-liveness`'s,
full stop.

### 2.6 Why this and not R1's discipline

**DECIDED** by ruling 3; recorded for the reviewer. R1 allocated by a
tree walk: destination at expression entry, lowest free, release on
consumption (`debruijn_register_compile.cljc:88-116, 152-306`). The
linear scan allocates by pc order over `L`, which is what makes it a
function of A and of the analysis phase 4 and 6 already consume (frozen
§8.1), with no tree walk and no second notion of liveness. Its bytes are
its own: R is the register kernel's bytecode and need not equal the old
image's or H (owner, 2026-10-11); under ruling 2 the pinned R values are
regenerated, not versioned.

---

## 3. A → H: stackification over A's expression structure

### 3.1 The output format, unchanged

**DECIDED.** The image is a flat vector over `dc/opcode-table`
(`debruijn_code.cljc:71`), pc-indexed, labels resolved; `dc/descriptor`
(`:117-155`), `lowering-contract-version` 2 (`:109-114`), `encode-image`
(`:533-539`), `image-hash` (`:551-567`) and `image-defect` (`:879`)
are **not** changed.

### 3.2 The walk

**DECIDED** (frozen §8.3 steps 2 and 4, with the adapter step 3 removed:
§9.1 F9). One emitter over the resolved vector's parse trees
(`(:bodies (resolve v))`, each with `:expr`). Labels are symbolic while
emitting and resolved to pcs in a second pass; bodies are emitted out of
line, main first, then a FIFO queue that every emitted `:closure`
appends to and that bodies discovered inside bodies join at the back
(frozen §3.3 item 1; this is the order A itself has, so the stack image's
body order equals A's and R's, while its pcs are its own).

```
emit(node):
  [:const rd v]                      → [:const v]
  [:load-bound rd d q]               → [:load-bound d q]
  [:load-free rd n]                  → [:load-free n]
  [:closure rd arity body-pc]        → L := fresh label; enqueue (L, body-at body-pc); [:closure arity L]
  [:call rd f args tail?]            → emit(f-child); [:push]; for each arg child: emit(child); [:push];
                                       [:call (count args) tail?]
  {:op :if …} with children [c t e]  → emit(c); [:branch-false Lelse]; emit(t); [:jump Lend];
                                       Lelse: emit(e); Lend:
  [:define rd name rs]               → emit(rs-child); [:define name]
  [:gensym rd p]                     → [:gensym p]        ; and likewise :store-get k, :store-put k x,
                                                           ; :stream-make b, :current-continuation, :park
  [:stream-put rd s x]               → emit(s); [:push]; emit(x); [:stream-put]
  [:stream-cursor rd s] / :stream-next / :stream-close
                                     → emit(s); [:stream-cursor] / [:stream-next] / [:stream-close]
  [:ffi-call rd op args]             → for each arg child: emit(child); [:push]; [:ffi-call op (count args)]
  [:resume id x]                     → emit(x); [:resume id]
body(main): emit(expr); [:halt]      body(lambda): emit(expr); [:return]
```

Children are the parse node's `:children` in order, which is `code/uses`
order, which is §3.1's evaluation order. The walk is defined by the
table above and by nothing else: H is the stack kernel's bytecode, a
function of A under these rules, and it need not equal the old
`lower-stack` output or R (owner, 2026-10-11). No test, golden or
observation in this design compares the new H with the old; the goldens
of §6.3 are derived from this table and checked against the emitter, and
the execution oracle (§6.1) is the only relation to the old pipeline.
The frozen §8.3 remark that `(f (g x) y)` needs `f` kept while `(g x)`
runs is met the only way a stack machine meets it: `f` is pushed before
the operands are emitted.

### 3.3 Side table

**PROPOSED.** `stack-image` and `register-image` each return
`{:image … :side-table {pc {:kind :closure :params […] :source pc-of-A}
pc {:kind :var :source pc-of-A}}}`, the pc-keyed diagnostic shape the
current lowerers return (`debruijn_linearize.cljc:192-209`,
`debruijn_register_compile.cljc:323-338`), with `:params` read from A's
`:closure` tuple and `:source` the A pc. Outside every hash; the lifts
that read it are phase-8 deletions (§8.3).

---

## 4. Determinism and the facts A omits

### 4.1 Same A, same bytes

**DECIDED.** `register-image` and `stack-image` read `v` and nothing
else: `code/parse`, `code/body-ranges`, `analysis/analyze` are pure
functions of `v`; `resolve-name` is pure; the scan's only state is the
sorted free set and two counters; `body-liveness` uses sorted sets
(`debruijn_register_code.cljc:272-300`). No host map iteration order
enters (§12 item 1). The gate (§6.2 item 5) computes each image twice
and compares vectors and bytes.

### 4.2 Two ASTs with the same A

**DECIDED, with the evidence the brief asked for.** Since R and H are
functions of `v` alone (§4.1), any two ASTs with the same A have the same
R and H by construction. The question is therefore only which AST facts
A omits, and whether the new lowerers could reach them by any other
route. They cannot: the lowerers take `v`; they are handed no datoms,
rows, side tables or provenance. The facts A omits, and where they are
dropped:

| Fact | Dropped by | Evidence |
|---|---|---|
| `:macro?` on a lambda | the row grammar has only `:params` and `:body` for `:lambda` (`vm.cljc:1067`); the datom lane's `flatten-tree` `:lambda` arm reads `:params` and `:body` only (`src/cljc/yin/vm/semantic_register/linearize.cljc:103-105`) | `an-explicit-macro-false-projects-like-an-absent-one` is the dormant projection's test (`debruijn_test.cljc:452`); for A, the `:vector` and `:address` of `(project (assoc lam :macro? true))` and `(project lam)` are asserted equal in §6.2 item 6 |
| shared `:eid`s (one node referenced from two sites) | occurrence expansion (frozen §3.3 item 1): `flatten-tree` lowers each reference where it is reached; `ast->semantic-bytecode` keeps one row for the shared node and `project-rows` expands it (`shared-occurrences-expand-positionally`, `test/yin/vm/semantic_register/linearize_test.cljc:107-119`) | `:shared-occurrence` with and without `:eid` on the shared call gives the same `:vector` and `:address` (§6.2 item 6) |
| `:tail?` on a non-application node | `flatten-tree` reads `:tail?` only in the `:application` arm, `(boolean (slot id :tail?))` (`semantic_register/linearize.cljc:116`; the definition arm at `:107-112` reads no tail flag) | asserted in §6.2 item 6 |
| entity ids, datom order, `t`/`m`, provenance paths | pc is the index; provenance is a side table outside the vector (`semantic_register/linearize.cljc:20-22`, UCF §7.3.2 `:393-397`) | `projection-is-deterministic` (`semantic_register/linearize_test.cljc:83`) already shuffles the datom lane |

A is **not** a key to the original AST: §4.2's whole point is that
several ASTs project to one A, which is why the unitarity association of
§9.2 maps an A to the tree(s) it was projected from and never pretends
the inverse is a function.

The old lowerers *did* read one of these through the resolved tuples:
`resolve` carries `:yin/macro?` (`debruijn_resolve.cljc:171`), though
neither `lower-stack` nor `lower-register` consults it. Under the new
derivations the question does not arise. **Finding for the report: the
facts A omits cannot affect R or H; the only artefacts they can affect
are the diagnostic side tables' `:source` columns, which are outside
every hash.**

---

## 5. The lexical-address law, made exact

### 5.1 Definition

**DECIDED** (register design §2.2, `yin.vm.debruijn.register.md:294-304`;
R1's `register-image-var-addresses`, `debruijn_register_compile_test.cljc:363-371`).
`addresses(X)` is the sequence of lexical references, in emission order,
each `[:bound d q]` or `[:free name]`:

- `addresses(A)`, the anchor: walk `v` in pc order; `[:var rd name]` at
  pc `p` contributes `(resolve-name (chain (body-of p)) name)` as
  `[:bound d q]` or `[:free name]`. Pc order is emission order (main
  first, bodies FIFO).
- `addresses(resolve(A))`: the `:load-bound`/`:load-free` tuples of the
  resolved vector in pc order.
- `addresses(R)`: `(:instructions image)` in pc order, `[:load-bound rd d q]`
  → `[:bound d q]`, `[:load-free rd n]` → `[:free n]`.
- `addresses(H)`: the stack image in pc order, `[:load-bound d q]` →
  `[:bound d q]`, `[:load-free n]` → `[:free n]`.

```
addresses(A) = addresses(resolve(A)) = addresses(R(A)) = addresses(H(A))
```

Until cutover a fifth leg compares with today's resolver, through a
walk that **mirrors definition lowering** (visits only a definition's
value operand; the existing `resolved-addresses-of`,
`debruijn_register_contract_test.cljc:256-262`, visits the operator and
would contribute a `[:free yin/def]` no image contains, so it is not
reused on a corpus with definitions). `derive/addresses` and
`derive/resolved-addresses` are the two public address-sequence readers;
neither assigns an identity (§10, 5a item 4).

### 5.2 Corpus and test shape

**PROPOSED.** The corpus `C` of §6.1.1. Test
`yin.vm.semantic-register.derive-test/lexical-address-law`: four-way
equality per program, the old-resolver leg while it exists; non-vacuity
assertions that some program yields `[:bound 1 _]` (`:nested-lambdas`),
some `[:bound 0 1]` (`:duplicate-param`), and that a definition program
(`:define-then-call`) yields no `[:free yin/def]`.

---

## 6. Verification

### 6.1 The execution oracle

**DECIDED** (ruling 5). The old pipeline is `rc/adapt`/`dl/adapt` over
`vm/ast->datoms` (`debruijn_register_compile.cljc:431-435`,
`debruijn_linearize.cljc:242-247`); the new pipeline is
`derive/register-image`/`derive/stack-image` over
`(:vector (sr-linearize/project P))`. Both feed the **same kernels**
(`rvm/create-vm`, `dvm/create-vm`) under the **same composition**, and
the observable results must be equal.

#### 6.1.1 Corpus `C`

| Source | Rows | Role |
|---|---|---|
| `yin.vm.parity-test/corpus` (`parity_test.cljc:39-115`) | 26 | the B0 corpus; runtime and bytes |
| `yin.vm.semantic-register.corpus/programs` (`corpus.cljc:54-118`) | 32 | every §3.1 production; bytes for all, runtime for the twelve selected rows (§6.1.3) |
| `yin.vm.debruijn-linearize-test`'s `corpus` (`debruijn_linearize_test.cljc:83-124`), respelled | 15 | every named mnemonic, `:definition`, `:default-gensym`, `:default-buffer`, `:duplicate-param`, `:nested-closure`; bytes |
| `address-law-extra-fixtures` (`debruijn_register_contract_test.cljc:311-320`) | 4 | shared-occurrence and duplicate-binder fixtures; bytes and §5 |
| the live fixtures A-E and `r2-lift-programs` of `debruijn_register_compile_test.cljc:608-766, 998-1011`, respelled | 5 + 12 | boundary `live` cases and the R2 nodes; bytes and validators |
| the C4 linked-prelude corpus by name (§6.1.4) | 8 modules, 6 linked programs | runtime through the linked harness, read-only |

#### 6.1.2 Observable result and normalization

A run's observable is `[halted? blocked? (native-normalize value)]` with
`halted?`/`blocked?` from `vm/halted?`/`vm/blocked?` and `value` from
`vm/value` after `vm/run`; for programs that write the store, also the
task's `:store` under `native-normalize` applied to its values.
`native-normalize`, one function in `derive-test`, applied to **both**
sides and to any pinned expectation: a host-typed value is unwrapped
(`values/payload`); a closure (host type or `{:type :closure …}` map)
becomes the keyword `:closure` (a native closure carries arity and body
pc, never the pinned `:params`/`:body`, so only closure-ness is common
to the walker, the pinned B0 column and both kernels); a continuation
`:continuation`; a `{:type :stream-ref}` or `:cursor-ref` map becomes
`{:type t :id id}`; a host fn `:host-fn`; collections recursively;
everything else as is. A throw is the observable
`[:thrown (ex-message e)]` and must be a throw with an equal message on
both pipelines.

#### 6.1.3 Runtime corpus and composition

Composition for a B0 or register-corpus program:
`{:primitives vm/primitives :make-stream tu/make-stream :contract vm/register-contract}`
(`debruijn/register.cljc` `create-vm` options) and the stack analogue
(`debruijn/stack.cljc:369-394`). Runtime rows: all 26 B0 rows, whose
observable must also equal the pinned `expected` under
`native-normalize`; and the twelve register-corpus rows that run to a
value: `:literal :define :define-call :gensym :store-ops
:lambda-application :nested-lambdas :if-in-test :define-then-call
:stream-make-default :body-queue-order :closure-in-arm-in-body`. The
other twenty are run too, on both pipelines: **nineteen throw
comparisons** (equal `[:thrown message]` observables), seventeen reading
an unbound name (`:variable :worked-example :zero-arity-call
:nested-calls :if :nested-if-same-rd :if-operand :if-in-tail
:all-terminal-arms :all-tail-arms :streams :ffi-call
:current-continuation :park :resume-arm :resume-operand
:shared-occurrence`) and two resuming an unknown parked id
(`:resume-body :resume-lambda-body`); and **one successful FFI
comparison**, `:ffi-call-no-args` (`{:op :op/ping :operands []}`), run
with an explicit zero-argument bridge `{:bridge {:op/ping (fn [] :pong)}}`
added to the composition (the parity fixture at `parity_test.cljc:163-173`
implements `:op/echo` with one argument and is the template, not the
bridge), expected observable `[true false :pong]` on both pipelines.
Equal observables is the gate; byte goldens are §6.3's business, never
this lane's.

#### 6.1.4 The C4 corpus, read-only

**PROPOSED.** The linked harness (`test/yang/python/antlr/linked_harness.cljc`)
is not edited. A new test namespace defines its own backend map in the
shape of `h/backends` (`:109-120`) with four entries, `:stack-old`,
`:stack-new`, `:register-old`, `:register-new`, each building the root
task over the program AST with `h/composition` (`:149-158`) and the
pipeline named, and drives it with `h/drive` (`:161-171`) against
`h/source` over the harness's published `py` and, for the import
programs, the guest modules it publishes itself through
`h/publish-guest-module!` (`:95-102`), exactly as `import_test.cljc`'s
private `published-guests` does (`:342-363`: the **module packets**
`k m t w e n`, `import_programs.cljc:176, 11, 225, 276, 334, 378`,
published bottom-up with `t` depending on `k`).

**Two populations, kept apart.**

*(a) Root import programs.* The nine root packets the import tests run,
each lowered with `lower/lower-packet pk {:prelude :linked}`, with the
expected outcome the C4 test pins (`import_test.cljc:400-516`), compared
old = new per backend family **and** equal to that pinned outcome:

| Root packet (`import_programs.cljc`) | C4 test | Expected `[halted? blocked? output]` |
|---|---|---|
| `o-packet` (`:410`) | `import-orders-statements-test` | `[true false {:py/out ["a" "m-body" "b"] :py/exception nil}]` |
| `q-packet` (`:997`) | `second-import-does-not-re-execute-test` | `[true false {:py/out ["m-body" "done"] :py/exception nil}]` |
| `d-packet` (`:473`) | same | `[true false {:py/out ["m-body" "7"] :py/exception nil}]` |
| `c-packet` (`:533`) | `module-dict-mutations-are-shared-test` | `[true false {:py/out ["m-body" "5" "absent" "('gone', 5)"] :py/exception nil}]` |
| `r-packet` (`:698`) | `raising-body-removes-its-entry-test` | `[true false {:py/out ["w-once" "cleaned" "w-once" "again"] :py/exception nil}]` |
| `x-packet` (`:813`) | `importer-catches-imported-function-exception-test` | `[true false {:py/out ["caught-e"] :py/exception nil}]` |
| `nn-packet` (`:1085`) | `one-image-two-names-test` | `[true false {:py/out ["n"] :py/exception nil}]` |
| the inline `main` of `one-image-two-names-test` (`import_test.cljc:497-504`: `(require 'py) (require 'pym.n) (py/init!) (py/run-main pym.n/body)`), rebuilt in the test | same | `[true false {:py/out ["__main__"] :py/exception nil}]` |
| `s-packet` (`:890`) | `preseeded-modules-resolve-test` | `[true false {:py/out ["m-body" "True" "True"] :py/exception nil}]` |
| `tt-packet` (`:1045`) | `transitive-import-delivers-test` | `[true false {:py/out ["k-body" "t-sees 3" "t-done"] :py/exception nil}]` |

Ten root programs, count asserted. Here only the **root program's**
lowering differs between old and new; the served module bodies (`py`,
the six guests) are the publisher's old images until cutover. This
population therefore covers the new lowering of **root code that links,
imports, catches cross-module exceptions and reads module state**, not
the module bodies themselves.

*(b) Module bodies, executed as root programs.* To exercise the new
lowering of the module code itself without editing C4 files or the
publisher, each of the eight module ASTs of §7.3 (`prelude/module-uast`;
the `pysp` spec's `:ast`; the six guest specs' `:ast`) is run **as a root
program** on both pipelines under the linked harness: `py`'s own body
requires nothing and defines its runtime into the store; `pysp`'s body
`(require 'py)` and defines; a guest's body requires `py` and its
imports and defines. Observable: `[halted? blocked? defined-keys]` where
`defined-keys` is the sorted set of keys the run left in the task's
store (the module's definitions, `prelude/defined-keys` of its AST being
the expected set) under `native-normalize` on the values' *types*
(closure → `:closure`, else the value); compared old = new and the key
set equal to `(prelude/defined-keys ast)` (`prelude.cljc:3416-3425`).
This runs every definition and every top-level expression of every
module body through the new lowering on both kernels.

*(c) Not covered until phase 8.* The install-child path over **newly
lowered module images** (a `require` that spawns the module's body from
a manifest whose H/R images came from `derive`) is not exercised by (a)
or (b), because publishing new images needs the production publisher to
switch (§7.6, §8.3). It is covered at phase 8 by the C4 suite itself
(`import_test`, `linked_prelude_test`, `safepoint_test`), whose pinned
outcomes then run over new images; §10.4 names that as a phase-8 gate.

Observables for (a) use `render/output` as `import_test`'s `outcome`
does (`:375-378`). `^:slow`, guarded, and it must actually run for
sign-off (§10 5b item 9).

### 6.2 Validators, liveness, determinism

Every image either pipeline produces, for every program of `C`:

1. `(rcode/register-image-defect image)` is nil (all twelve rules,
   `live-exact` included); `(dc/image-defect image)` is nil (shape, Rule
   R, scope).
2. Body descriptors: `:registers ≥` every register operand `+ 1`;
   `:locals` equals the owning `:closure`'s arity; `:start`/`:end` are
   A's body ranges (register) or the emitter's (stack).
3. Every `:load-bound d q` has `d` below its body's chain length and `q`
   below that frame's arity (the kernels' `body-scope-rule` /
   `scope-defect` check this; asserted again from `chain`).
4. **Two liveness analyses agree** (§2.5): for every boundary pc `p`,
   the filled `live` equals the sorted physical image of the virtual
   live-out over the **actual successors** `(analysis/successors v p)`
   minus `rd`; for every tail `:call` both sides are `[]`.
5. **Determinism**: `(= (register-image v) (register-image v))` and
   `(= (stack-image v) (stack-image v))` as structures and as
   `encode-register-image`/`encode-image` bytes.
6. **Same A, same images**: for the pairs (lambda with and without
   `:macro? true`; `:shared-occurrence` with and without `:eid` on the
   shared node; an application with and without `:tail? false` on a
   literal operand), the `:vector` and the `:address` of the two
   projections are equal (**not** the whole `project` result, whose
   `:provenance` legitimately differs), and therefore R and H are equal;
   asserted both ways so a future projection change that starts carrying
   one of these facts is noticed.

### 6.3 Hand-derived goldens

**PROPOSED** as pinned rows (5b and 5a). Each was derived by hand from
the A goldens of `corpus.cljc` and the rules of §2-§3; the R and H hash
strings are **not** written here and are filled by the implementer from
the pinned image through the public hash function, which is how a
reviewer verifies them (§8.1). `L` is `analysis/live`; `v` are virtual
ids; `T` temporaries. For every H image the pc of each label is stated;
labels are the only place a stack pc is not simply "the next slot".

**1. `(+ 1 2)`** (B0 `"addition"` shape). A: `[:var 1 +] [:const 2 1]
[:const 3 2] [:call 0 1 [2 3] false] [:halt 0]`. `L(4)={0} L(3)={1,2,3}
L(2)={1,2} L(1)={1} L(0)={}`. Intervals `v1:[0,3] v2:[1,3] v3:[2,3]
v0:[3,4]`. Scan: `v1→T0 v2→T1 v3→T2`; pc 3 nothing expires, `v0→T3`;
peak 4.
R image: `[[:load-free 0 +] [:const 1 1] [:const 2 2] [:call 3 0 [1 2] false []] [:halt 3]]`,
body `{:locals 0 :registers 4 :start 0 :end 4}`; `live` at pc 3 =
`L(4)` image `{T3}` minus rd `T3` = `[]`.
H image (pcs 0-7, no labels):
`[[:load-free +] [:push] [:const 1] [:push] [:const 2] [:push] [:call 2 false] [:halt]]`.

**2. `(f (g x) y)`** (`:worked-example`). A: pcs 0-6 `[:var 1 f] [:var 3 g]
[:var 4 x] [:call 2 3 [4] false] [:var 5 y] [:call 0 1 [2 5] false] [:halt 0]`.
`L(6)={0} L(5)={1,2,5} L(4)={1,2} L(3)={1,3,4} L(2)={1,3} L(1)={1}`
(`analysis_test.cljc:131-139` pins `L(4)` and `L(5)`). Intervals
`v1:[0,5] v3:[1,3] v4:[2,3] v2:[3,5] v5:[4,5] v0:[5,6]`. Scan: `v1→T0
v3→T1 v4→T2`; pc 3 `v2→T3`; pc 4 `v3,v4` expire, `v5→T1`; pc 5 `v0→T2`;
peak 4.
R image: `[[:load-free 0 f] [:load-free 1 g] [:load-free 2 x] [:call 3 1 [2] false [0]] [:load-free 1 y] [:call 2 0 [3 1] false []] [:halt 2]]`,
body `{:locals 0 :registers 4 :start 0 :end 6}`. `live` at pc 3:
`L(4)={1,2}` → `{T0,T3}` minus rd `T3` = `[0]` (`f` survives the inner
call); at pc 5: `L(6)={0}` → `{T2}` minus rd = `[]`. Cross-check with
`body-liveness` over the physical image: live-in(4) = `{0,3}`, so live
at 3 is `[0]`; live-in(6) = `{2}`, so live at 5 is `[]`.
H image (pcs 0-11, no labels):
`[[:load-free f] [:push] [:load-free g] [:push] [:load-free x] [:push] [:call 1 false] [:push] [:load-free y] [:push] [:call 2 false] [:halt]]`.

**3. `((f))`**. A: `[:var 2 f] [:call 1 2 [] false] [:call 0 1 [] false] [:halt 0]`.
`L(3)={0} L(2)={1} L(1)={2} L(0)={}`. Intervals `v2:[0,1] v1:[1,2]
v0:[2,3]`. Scan: `v2→T0`; pc 1 `v1→T1`; pc 2 `v2` expires, `v0→T0`;
peak 2.
R image: `[[:load-free 0 f] [:call 1 0 [] false []] [:call 0 1 [] false []] [:halt 0]]`,
body `{:locals 0 :registers 2 :start 0 :end 3}`; `live` at pc 1:
`L(2)={1}`→`{T1}` minus rd `T1` = `[]`; at pc 2: `{T0}` minus `T0` = `[]`.
H image (pcs 0-5, no labels):
`[[:load-free f] [:push] [:call 0 false] [:push] [:call 0 false] [:halt]]`.

**4. `(if c 1 2)`** (`:if`). A: `[:var 1 c] [:branch-false 1 4] [:const 0 1]
[:jump 5] [:const 0 2] [:halt 0]`. `L(5)={0} L(4)={} L(3)={0} L(2)={}
L(1)={1} L(0)={}`. Intervals `v1:[0,1]`, `v0`: defs 2 and 4, live at 3
and 5 → `[2,5]`. Scan: `v1→T0`; pc 2 `v1` expires, `v0→T0`; peak 1.
R image: `[[:load-free 0 c] [:branch-false 0 4] [:const 0 1] [:jump 5] [:const 0 2] [:halt 0]]`,
body `{:locals 0 :registers 1 :start 0 :end 5}`. The test register is
reused for the result: legal, the test is dead after the branch.
H image: pc 0 `[:load-free c]`, 1 `[:branch-false Lelse]`, 2 `[:const 1]`,
3 `[:jump Lend]`, **Lelse = 4** `[:const 2]`, **Lend = 5** `[:halt]`:
`[[:load-free c] [:branch-false 4] [:const 1] [:jump 5] [:const 2] [:halt]]`.

**5. `(f (if c 1 2) y)`** (`:if-operand`). A: pcs 0-8 `[:var 1 f] [:var 3 c]
[:branch-false 3 5] [:const 2 1] [:jump 6] [:const 2 2] [:var 4 y]
[:call 0 1 [2 4] false] [:halt 0]`. `L(8)={0} L(7)={1,2,4} L(6)={1,2}
L(5)={1} L(4)={1,2} L(3)={1} L(2)={1,3} L(1)={1} L(0)={}`. Intervals
`v1:[0,7] v3:[1,2]`, `v2`: defs 3,5, live 4,6,7 → `[3,7]`; `v4:[6,7]
v0:[7,8]`. Scan: `v1→T0 v3→T1`; pc 3 `v3` expires, `v2→T1`; pc 6
`v4→T2`; pc 7 `v0→T3`; peak 4.
R image: `[[:load-free 0 f] [:load-free 1 c] [:branch-false 1 5] [:const 1 1] [:jump 6] [:const 1 2] [:load-free 2 y] [:call 3 0 [1 2] false []] [:halt 3]]`,
body `{:locals 0 :registers 4 :start 0 :end 8}`; `live` at pc 7 =
`L(8)={0}`→`{T3}` minus `T3` = `[]`.
H image, pc by pc: 0 `[:load-free f]`, 1 `[:push]`, 2 `[:load-free c]`,
3 `[:branch-false Lelse]`, 4 `[:const 1]`, 5 `[:jump Lend]`, **Lelse = 6**
`[:const 2]`, **Lend = 7** `[:push]` (the conditional's value is an
operand, so the push follows the join), 8 `[:load-free y]`, 9 `[:push]`,
10 `[:call 2 false]`, 11 `[:halt]`:
`[[:load-free f] [:push] [:load-free c] [:branch-false 6] [:const 1] [:jump 7] [:const 2] [:push] [:load-free y] [:push] [:call 2 false] [:halt]]`.

**6. A tail call: `((fn [x] (+ x 1)) 10)`** (`:lambda-application`). A:
main `[:closure 1 [x] 4] [:const 2 10] [:call 0 1 [2] false] [:halt 0]`;
body `[:var 1 +] [:var 2 x] [:const 3 1] [:call 0 1 [2 3] true] [:return 0]`.
Main: `L(3)={0} L(2)={1,2} L(1)={1}`; intervals `v1:[0,2] v2:[1,2]
v0:[2,3]`; scan `v1→T0 v2→T1`, pc 2 `v0→T2`; peak 3. Body (locals 1):
`L(8)={0}`, `L(7)={1,2,3}` (a tail call has no successor, so its `L` is
its uses), `L(6)={1,2} L(5)={1} L(4)={}`; intervals `v1:[4,7] v2:[5,7]
v3:[6,7]`, `v0`: def 7 (the tail call names its `rd`), live at 8 →
`[7,8]`; scan `v1→T0 v2→T1 v3→T2`, pc 7 `v0→T3`; peak 4, registers
`1+4=5`. `x` resolves `[0 0]`.
R image: `[[:closure 0 1 4] [:const 1 10] [:call 2 0 [1] false []] [:halt 2] [:load-free 1 +] [:load-bound 2 0 0] [:const 3 1] [:call 4 1 [2 3] true []] [:return 4]]`,
bodies `[{:locals 0 :registers 3 :start 0 :end 3} {:locals 1 :registers 5 :start 4 :end 8}]`;
main `live` at pc 2 = `L(3)={0}`→`{T2}` minus `T2` = `[]`; the tail
call's `live` is `[]` by the successor rule (`live-tail-rule`,
`debruijn_register_code.cljc:621-628`).
H image, pc by pc: main 0 `[:closure 1 Lbody]`, 1 `[:push]`, 2 `[:const 10]`,
3 `[:push]`, 4 `[:call 1 false]`, 5 `[:halt]` (six instructions, so the
body starts at **Lbody = 6**); body 6 `[:load-free +]`, 7 `[:push]`,
8 `[:load-bound 0 0]`, 9 `[:push]`, 10 `[:const 1]`, 11 `[:push]`,
12 `[:call 2 true]`, 13 `[:return]`:
`[[:closure 1 6] [:push] [:const 10] [:push] [:call 1 false] [:halt] [:load-free +] [:push] [:load-bound 0 0] [:push] [:const 1] [:push] [:call 2 true] [:return]]`.
(The stack image's body pc differs from A's `4` because the stack image
has pushes A does not; §2.4 "layout is A's" is a register-image fact
only.)

**7. Definitions.** `(yin/def x 5)` (`:define`): A `[:const 1 5]
[:define 0 x 1] [:halt 0]`; `L(2)={0} L(1)={1}`; `v1:[0,1] v0:[1,2]`;
scan `v1→T0`, pc 1 `v0→T1`; peak 2.
R: `[[:const 0 5] [:define 1 x 0] [:halt 1]]`, body `{:locals 0 :registers 2 :start 0 :end 2}`.
H (pcs 0-2, no labels): `[[:const 5] [:define x] [:halt]]`.
`(yin/def y (+ 1 2))` (`:define-call`): A pcs 0-5 `[:var 2 +] [:const 3 1]
[:const 4 2] [:call 1 2 [3 4] false] [:define 0 y 1] [:halt 0]`;
`L(5)={0} L(4)={1} L(3)={2,3,4} L(2)={2,3} L(1)={2}`; intervals
`v2:[0,3] v3:[1,3] v4:[2,3] v1:[3,4] v0:[4,5]`; scan `T0 T1 T2`, pc 3
`v1→T3`, pc 4 `v2,v3,v4` expire, `v0→T0`; peak 4.
R: `[[:load-free 0 +] [:const 1 1] [:const 2 2] [:call 3 0 [1 2] false []] [:define 0 y 3] [:halt 0]]`,
body `{:locals 0 :registers 4 :start 0 :end 5}`; `live` at pc 3 =
`L(4)={1}`→`{T3}` minus `T3` = `[]`.
H (pcs 0-8, no labels): `[[:load-free +] [:push] [:const 1] [:push] [:const 2] [:push] [:call 2 false] [:define y] [:halt]]`.
A layout-syntax note for §2.2's widened `start`: `(if c (resume :p 1) 2)`
lowers to `[:var 1 c] [:branch-false 1 5] [:const 2 1] [:resume :p 2]
[:jump 6] [:const 0 2] [:halt 0]`; `L(4) = L(6) = {0}` while `v0`'s only
definition is at pc 5, so `points(v0) = {4,5,6}` and the interval is
`[4,6]`, assigned at pc 4 (no instruction there names a register, so
nothing observable changes; the rule exists so that a boundary
instruction in the same position would find its live operand's slot
assigned).

**8. A terminal operand and the structural slot: `(f (resume :p x) y)`**
(`:resume-operand`). A: pcs 0-5 `[:var 1 f] [:var 3 x] [:resume :p 3]
[:var 4 y] [:call 0 1 [2 4] false] [:halt 0]`. Successors: the `:resume`
has none, so pcs 3-5 are reachable from no path and `L(1) = L(0) = {}`:
`L(5)={0} L(4)={1,2,4} L(3)={1,2} L(2)={3} L(1)={} L(0)={}`.
Intervals: `v1`: def 0, live 3,4 → `[0,4]`; `v3:[1,2]`; `v4:[3,4]`;
`v0:[4,5]`; `v2` has no definition → structural. Scan: `v1→T0 v3→T1`;
pc 3 `v3` expires, `v4→T1`; pc 4 `v0→T2`; peak 3; structural slot `T3`;
registers 4.
R image: `[[:load-free 0 f] [:load-free 1 x] [:resume :p 1] [:load-free 1 y] [:call 2 0 [3 1] false []] [:halt 2]]`,
body `{:locals 0 :registers 4 :start 0 :end 5}`; `live` at pc 4 =
`L(5)={0}`→`{T2}` minus `T2` = `[]`. The structural `T3` is read by no
reachable instruction; `register-bounds-rule` holds (`3 < 4`);
`body-liveness` sees `T3` used at pc 4 and never defined, which affects
no boundary's `live` (the only boundary is pc 4 itself, whose live-out
is `{T2}`).
H image (pcs 0-8, no labels):
`[[:load-free f] [:push] [:load-free x] [:resume :p] [:push] [:load-free y] [:push] [:call 2 false] [:halt]]`.

**9. A tail call as an operand: `(f (g x) y)` with the inner call marked
`:tail? true`** (the shape §2.5's law must handle; not in any corpus, so
it is a new inline row). A: pcs 0-6 `[:var 1 f] [:var 3 g] [:var 4 x]
[:call 2 3 [4] true] [:var 5 y] [:call 0 1 [2 5] false] [:halt 0]` (the
validator accepts it: items 8 and 10 treat pcs 4-6 as layout syntax
after the terminator). Successors: pc 3 has none. `L(6)={0} L(5)={1,2,5}
L(4)={1,2} L(3)={3,4} L(2)={3} L(1)={} L(0)={}`. Intervals: `v1`: def 0,
live 4,5 → `[0,5]`; `v3:[1,3]`; `v4:[2,3]`; `v2`: def 3 (the tail call
names its `rd`), live 4,5 → `[3,5]`; `v5:[4,5]`; `v0:[5,6]`. Scan:
`v1→T0 v3→T1 v4→T2`; pc 3 `v2→T3`; pc 4 `v3,v4` expire, `v5→T1`; pc 5
`v0→T2`; peak 4.
R image: `[[:load-free 0 f] [:load-free 1 g] [:load-free 2 x] [:call 3 1 [2] true []] [:load-free 1 y] [:call 2 0 [3 1] false []] [:halt 2]]`,
body `{:locals 0 :registers 4 :start 0 :end 6}`. `live` at pc 3 is
**`[]`**: the tail call has no successor, so its virtual live-out is `{}`
(and `L(4) − {2}` would have been `{1}` → `[0]`, which is exactly why
§2.5 compares live-out over actual successors). `live` at pc 5 =
`L(6) − {0}` = `[]`.
H image (pcs 0-11, no labels):
`[[:load-free f] [:push] [:load-free g] [:push] [:load-free x] [:push] [:call 1 true] [:push] [:load-free y] [:push] [:call 2 false] [:halt]]`.

Every number above was derived by hand twice and, for the R rows,
cross-checked against the reviewer's independent derivations where they
exist (`(+ 1 2)`, `(f (g x) y)`, `((f))`, `(f (if c 1 2) y)`, the tail
call, both definitions, `(f (resume :p x) y)`); the two H label errors
the reviewer found in rows 5 and 6 are corrected above and every other H
pc was re-counted. None was produced by running code. The implementer's
test pins these images literally and §6.2 item 1 validates them; a
disagreement between a pinned image and the implementation is
investigated against §2-§3's rules, and this table is corrected if the
derivation, not the implementation, was wrong.

---

## 7. The scanners (UCF §7.6.1) and the linker records

Carried over from the reviewed design unchanged in substance (codex
rounds 1-3 accepted the oracles, the join comparison, the C4
discharged-name comparison and the public-access rules); restated here
so this document stands alone.

### 7.1 Where the dependency-closure walk lives today

**DECIDED, located.** UCF §7.6.1 (`yin.vm.universal-continuation-format.md:1340-1413`)
specifies the fixed point; "the scanners" are its code side. Four
implementations exist, all over the stack-shaped `"v3"` vector or the de
Bruijn images:

| Fact | Semantic `"v3"` vector | Stack image | Register image | Tree (the oracle) |
|---|---|---|---|---|
| free `:var` names, as occurrences `{:name :at :in-body?}` | `linker/semantic-free-occurrences` (`linker.cljc:224-261`), scope by `closure-spans` heuristics (`:128-168`); `completion/segment-free-names` by Datalog (`completion.cljc:173-222`) | `stack-free-occurrences` (`:470-484`), `stack-free-names` (`:96-102`) | `register-free-occurrences` (`:572-582`), `register-free-names` (`:105-112`) | `vm/free-names` (`vm.cljc:1701-1720`), `tree-free-name-occurrences` (`linker.cljc:377`) |
| definitions `{:name :at :conditional?}` | `vector-definition-occurrences` (`:497-513`, key at slot 1) | same | `register-definition-occurrences` (`:585-599`, key at slot 2) | `tree-definition-occurrences` (`:425`) |
| application sites `{:at}` | `vector-application-sites` (`:516-529`) | same | `register-application-sites` (`:602-611`) | `tree-application-sites` (`:452`) |
| store keys, ffi ops, parked ids, effect kinds | `vm/segment-requirements` (`vm.cljc:1882-1888`) over `segment-extraction-queries` (`:1833-1853`) and `footprint-table` `"v3"` (`:1733-1790`) | — | — | `vm/ast-requirements` (`:1869-1879`) over `ast-extraction-queries` (`:1805-1830`) |
| the step-5a join | `linker/undischarged` (`linker.cljc:1090-1160`), format-neutral | | | |
| UCF lift census | `completion/code-facts` (`completion.cljc:230-275`) | `handoff/free-names-v2` (`handoff.cljc:1235-1253`) | same | `walker-free-names` (`:1208-1232`) |

The `"v3"` scanners read stack-table positions (`[$code _ _ :store-get ?key]`
binds the key at the first operand, `vm.cljc:1841`; `[:var name]` at
slot 1, `linker.cljc:252-256`; `[:closure params body]` at slots 1-2,
`:238-241`; `:define` key at slot 1, `:508`; `:resume pid` at slot 1,
`vm.cljc:1847`). The §2.3 table puts `rd` first, so every position is off
by one on a `"v4"` vector and `:push` does not exist.

### 7.2 What the register-table version computes

**PROPOSED** namespace `yin.vm.semantic-register.scan`, file
`src/cljc/yin/vm/semantic_register/scan.cljc`. Pure functions of a
well-formed `"v4"` vector:

| Function | Returns | Positions (`code/operand-table`, `code.cljc:34-59`) |
|---|---|---|
| `free-occurrences v` | `[{:name sym :at pc :in-body? b} …]` in pc order | `[:var rd name]`, name at slot 2; bound iff `(resolve-name (chain (body-of pc)) name)` is `:bound` (§1.2); `:in-body?` = body index > 0 |
| `free-names v` | the set of their names | |
| `definition-occurrences v` | `[{:name key :at pc :conditional? b} …]` | `[:store-put rd key value]` key at slot 2, `[:define rd name rs]` name at slot 2; `:conditional?` when pc is in a body > 0 or strictly inside a `:jump`/`:branch-false` target range (`[:jump t]` slot 1, `[:branch-false c t]` slot 2), as `register-conditional-ranges` computes it (`linker.cljc:553-569`) |
| `application-sites v` | `[{:at pc} …]` | `:call` and `:ffi-call` pcs |
| `requirements v` | `{:store-keys :ffi-ops :parked-ids :effects}` | Datalog over `(segment-rows v)`: `[$code _ _ :store-get _ ?key]`, `[$code _ _ :store-put _ ?key _]`, `[$code _ _ :define _ ?key _]`, `[$code _ _ :ffi-call _ ?op _]`, `[$code _ _ :resume ?pid _]`; effect raisers `#{:stream-make :stream-put :stream-cursor :stream-next :stream-close :ffi-call}` normalised through `footprint-mnemonics` |
| `footprint-mnemonics` | the `"v4"` mnemonic → effect-set map | `(dissoc (get-in vm/footprint-table [vm/semantic-contract :mnemonics]) :push)`, asserted equal by a drift test; derived engine data, never canonical content; it moves into `vm/footprint-table` under `"v4"` at cutover |
| `segment-rows v` | `[[A pc mnemonic & operands] …]` | `(mapv (fn [pc t] (into [A pc] t)) (range) v)`, `A = (ucf/code-address v)`, defined in `scan`: the shape `yin.vm.code/project-segment-qualified` gives a `"v3"` vector (`code.cljc:446-467`) without requiring `yin.vm.code` |

**Imports and access, pinned.** `scan` requires exactly
`yin.vm.semantic-register.code`, `yin.vm.debruijn` (`resolve-name`),
`yin.vm` (`reserved-name?`, `footprint-table`, `semantic-contract`),
`yin.vm.ucf` (`code-address`) and `dao.space.query`. Every function in
the table is public. The scanner tests reach the `"v3"` side only through
public surface: the format records' map keys
(`(:obligations-fn linker/semantic-format)` and siblings,
`linker.cljc:692-711`; `linker/ast-format`, `:714-731`),
`linker/semantic-free-occurrences`, `linker/semantic-free-names`
(`:224-269`), `completion/segment-free-names` (`completion.cljc:195`),
`vm/segment-requirements`, `vm/ast-requirements`, and
`yin.vm.code/project-segment-qualified` for `"v3"` rows **inside the test
only** (that namespace exists until phase 8). The step-5a join is reached
through the public `linker/verify` (`linker.cljc:1363-1420`), never the
private `undischarged`. No `#'` anywhere.

**DECIDED** by UCF §7.6.1 (`:1385-1398`) and Rule R: `yin/def` is never a
free name; a `:define` key is a store key the segment writes; `:store-get`
keys are in the footprint. **DECIDED** by `undischarged`'s contract: the
three record kinds and keys are unchanged, so the join runs the new
scanners through the same function. **No degradation path**: a `"v4"`
vector reaching a scanner has passed item 6, so its owner tree is exact;
the scanners throw on a malformed vector, which `linker/scanned` turns
into the conservative degradation (`:1081-1087`).

### 7.3 Agreement: three obligations, three corpora

For `P` with `v3 = (:vector (linearize/lower-rows (vm/ast->semantic-bytecode P)))`
(`publish.cljc:164`) and `v4 = (:vector (sr-linearize/project P))`. The
three sides do not share an order: the vector scanners emit in vector
order (main first, bodies FIFO); `linker/ast-format`'s tree scanners sort
by structural path (`linker.cljc:290-375`), so for `:define-then-call`
the tree lists body `list` before main `inc` and `n`; the tree's
`tree-application-sites` counts definition applications and omits FFI
nodes.

**(i) Unordered agreement, three-way**, old = new = tree oracle:

| Fact | Old (`"v3"`) | New (`"v4"`) | Oracle | Compared as |
|---|---|---|---|---|
| free names | `(linker/semantic-free-names v3)`, `(completion/segment-free-names v3)` | `(scan/free-names v4)` | `(vm/free-names db occ root)` | set equality |
| free-name occurrences | `((:obligations-fn linker/semantic-format) v3)` | `(scan/free-occurrences v4)` | `((:obligations-fn linker/ast-format) tree)` | multiset of `[name in-body?]`, `:at` and paths dropped |
| definitions | `((:definitions-fn linker/semantic-format) v3)` | `(scan/definition-occurrences v4)` | `((:definitions-fn linker/ast-format) tree)` | multiset of `[name conditional?]` |
| store footprint, ffi ops, parked ids, effects | `(vm/segment-requirements (query/relation (code/project-segment-qualified v3)))` | `(scan/requirements v4)` | `(vm/ast-requirements (query/relation rows))` | map equality |

**(ii) Ordered agreement, three-way**, old = new = an independent
emission-order tree oracle written in `scan-test` from the map AST alone:
main expression then a FIFO queue of lambda bodies; operator before
operands; test, consequent, alternate; target then value; source; value
operand; a definition visits only its value operand; the bound set is the
enclosing lambdas' parameters. It emits `[name in-body?]` for every free
`:variable`, `[key conditional?]` for every `:vm/store-put` and definition
(`conditional?` inside a body or a conditional's consequent/alternate,
never its test), and one site per non-definition `:application` and per
`:dao.stream.apply/call`. Compared: occurrence sequence, definition
sequence, site count (definition applications excluded, FFI included, on
all three sides).

**(iii) Join agreement**, old = new, through `linker/verify`:
`(linker/verify fmt A A {A v})`, `fmt` = `linker/semantic-format` for `v3`
and `semantic-register-format` (§7.5) for `v4`, `A` the vector's segment
key. The outcome normalised to `{:status :ok :obligations [[name in-body?] …]}`
or `{:status :refused :reason r :name n}` (`:at`, `:identity`, `:address`,
`:value`, `:format` dropped) must be equal; refusals are compared, not
discarded. One row must refuse on both sides: `:use-before-definition` =
`(app (v 'list) (v 'n) (def! 'n (lit 1)))` (`undischarged`'s main-sequence
branch, `linker.cljc:1118-1135`), expected
`{:status :refused :reason :use-before-definition :name n}`.

Three corpora, each through (i)-(iii), in `yin.vm.semantic-register.scan-test`:

1. `scanners-agree-on-b0`: the 26 B0 rows plus the `:use-before-definition` row.
2. `scanners-agree-on-the-register-corpus`: the 32 rows (count asserted).
3. `scanners-agree-on-the-c4-modules` (`^:slow`, guarded): by name, over
   the module ASTs the C4 track publishes, read-only: `prelude/module-uast`
   (`py`), the `pysp` spec `(:ast (safepoint/module-spec (h/registry) a))`
   (`linked_harness.cljc:66-73`), and the six guest packets of
   `import_test.cljc:342-363` through `lower/module-spec` with a stand-in
   `:py-address` and `:deps` (`import_test.cljc:298-310`): `k m t w e n`.
   The test requires `yang.python.antlr.prelude`, `.safepoint`, `.lower`,
   `.import-programs`, `.linked-harness` and edits none; it runs no linked
   program and publishes nothing. A disagreement is reported to the C4
   owner, never repaired by weakening a declaration.
   **Declarations, separately from raw agreement.** `lower/module-spec`
   removes the module's defined keys and `yin/def`, then drops names
   covered by a pinned requirement (`lower.cljc:1864-1877`,
   `declare-free-name` `:1827-1843`); `prelude/module-spec` declares
   `module-free-names` (`prelude.cljc:3428-3430, :3445-3466`). Two more
   equalities per module:
   - raw: `(= (scan/free-names v4) (disj (set (prelude/free-names ast)) vm/definition-operator))`;
     `prelude/free-names` (`prelude.cljc:3395-3414`) visits a definition's
     `yin/def` operator through its generic `(vals node)` branch and so
     reports it, where `scan/free-names`, `vm/free-names` and the linker
     obligations exclude it (Rule R);
   - discharged: `(set (keys (:primitives spec)))` = `(scan/free-names v4)`
     minus `(conj (prelude/defined-keys ast) 'yin/def)` (`prelude.cljc:3416-3425`)
     minus every name whose namespace symbol is in the spec's `:requires`
     keys (`py`, `pym.<i>`); for `py` this is what
     `module-spec-declares-every-free-name-test` checks
     (`linked_prelude_test.cljc:167-187`).

The tree oracles (`vm/free-names`, `ast-requirements`, the `ast-format`
scanners, the emission walk) are AST-level and survive cutover; under
the unitarity invariant the tree is always there to run them.

### 7.4 "Old scanner" after cutover: pinning goldens

**PROPOSED.** Phase 8 deletes the `"v3"` scanners with `yin.vm.linearize`
and `yin.vm.code`'s stack table. Slice 5c writes one golden fixture
`test/yin/vm/semantic_register/scanner_goldens.cljc`: for every program
of corpora 1-2 and every C4 module of corpus 3, by name,
`{:free-names #{…} :occurrences [[name in-body?] …] :definitions [[name conditional?] …]
:application-count n :requirements {…} :join {:status …}}` as the `"v3"`
scanners answer it at `ab952ace`, generated once by a `scan-test` helper
on the JVM and pasted as Clojure data. `scanners-agree-*` compare the new
scanners against the fixture and against the live `"v3"` scanners while
those exist; at phase 8 the live comparison is deleted and the fixture
stays as historical evidence; the tree oracles remain the independent
check.

### 7.5 The production linker format record

**DECIDED** shape (frozen §10 phase 4 as amended by F6; `linker.cljc:655-731`;
evaluator design §3.3), **PROPOSED** scanner bindings:

```clojure
(def semantic-register-format
  {:format              :yin.semantic-register/code   ; renamed :yin.semantic/code "v4" at phase 8
   :contract            sr-code/contract              ; "v4"
   :identity-fn         jing/segment-key              ; = ucf/code-address
   :identity-matches-fn jing/segment-matches?
   :row-defect-fn       sr-code/well-formed?
   :validate-fn         sr-code/well-formed?
   :obligations-fn      scan/free-occurrences
   :definitions-fn      scan/definition-occurrences
   :applications-fn     scan/application-sites
   :parts-fn            (constantly nil)})
```

Added to `linker.cljc` beside `semantic-format` and to
`yin.repl.link/formats` (`src/cljc/yin/repl/link.cljc:81-89`), both
additively; `verify` runs steps 3-5a over it unchanged. The 4b stub
responder's `:obligations (constantly [])` fixture (evaluator design
§5.2) is replaced by this record's scanner once 5c lands (§10.4).

### 7.6 Derivation records at cutover

**DECIDED** today: `publish-closure!` mints `(ledger/derive-record
tree-address output)` for `output ∈ {A_v3, H, R}` (`publish.cljc:159-176,
183-188`); `checked-derivation` requires the record's input to be the
tree (`linker.cljc:2258-2262`); `relowered` recomputes by `adapt` from
the tree (`:2142-2160`). **During phases 5-7 nothing changes**: the
production pipeline is the old one (§8.3). **PROPOSED for phase 8**, as
the one linker change ruling 1 and the linker §3 amendment
(`yin.vm.linker.md:222-224`) require: the semantic record is
`tree → A_v4`; the de Bruijn records are `A_v4 → H` and `A_v4 → R` with
`:yin.ledger/input` A (a segment address, so `record-defect`'s check
holds, `linker.cljc:868-869`) and the unchanged profile maps
(`linker.cljc:913-932`: the profile names the lowering, which is now the
derive namespace's; its `:yin.lower/profile` strings `"stack-lowering"`
and `"register-lowering"` are kept, ruling 2); `checked-derivation` checks
a de Bruijn record's input against the manifest's semantic derivation
output; `relowered` recomputes from `(:vector (sr-linearize/project-rows tree))`
through `derive`; `publish-closure!` lowers H and R from A. Every manifest
address moves then, once, with the C4 `manifest-golden` (§8.1).

---

## 8. Regeneration, and what keeps the old lowerers alive

### 8.1 What is regenerated, once, and how a reviewer verifies it

**DECIDED** by ruling 2. Nothing is versioned. The following constants
change value exactly once, and each is verified **mechanically**: the
reviewer takes the printed intermediate (an image, a vector, a manifest
map) and recomputes the constant with the public function named; a
constant that cannot be recomputed from a printed intermediate is not
accepted.

Two moments regenerate: **slices 5a/5b** (the new lowerers get their own
goldens) and **phase 8** (the descriptors lose their lift declaration,
§8.2, which changes every H and R checksum once more, and the production
pipeline switches). Both are listed.

| Constant | Where | When | Mechanical check |
|---|---|---|---|
| R goldens of the new lowerer | new tests in `derive_test.cljc` pinning §6.3's images and their `register-hash` | 5b, and again at phase 8 (descriptor change) | `(rcode/register-hash <printed image>)` equals the pinned string; the image equals §6.3 and is unchanged by the descriptor edit |
| H goldens of the new lowerer | likewise with `image-hash` | 5a, and again at phase 8 | `(dc/image-hash <printed vector>)` equals the pinned string; the vector equals §6.3. No relation to the old `lower-stack` output is recorded or checked (owner, 2026-10-11) |
| `rcode/descriptor-hash`, `dc/descriptor-hash` | pinned in a test that survives: a **new** `test/yin/vm/debruijn_register_code_test.cljc` (`golden-descriptor-hash-test` moved out of `debruijn_register_compile_test.cljc:445-450`, which phase 8 deletes) and `debruijn_code_test.cljc:368` extended from `string?` to the pinned value | 5b (move, same value); phase 8 (new value) | `(jing/sha256 (dc/encode-scalar <printed descriptor>))` equals the pinned string (`debruijn_code.cljc:542-548`; `debruijn_register_code.cljc:257-258`); the printed descriptor differs from the previous one only by the removed `:dim/lift-to` row and, if the arity row counts it, by nothing else (`:dim/arity` counts opcode-table entries, `:196`, not descriptor rows) |
| `linker_test/pinned-identities-are-host-independent` (`linker_test.cljc:521-531`): H `52791d4a…` and R `c0aefe2f…` of `((fn [x] (+ x 1)) 10)` | the test's `stack-image`/`register-image` helpers (`:253-260`, `dl/adapt`/`rc/adapt`) switch to `derive` | phase 8 | both strings recomputed as the two rows above from §6.3 row 6's printed images, after the descriptor change |
| R and H values and register counts derived live through `rc/adapt`/`dl/adapt` (`attach_image_test`, `linked_harness`, `repl_test`, `linker_require_test`, the `yang.python.antlr.*` tests, `handoff_v1_test`, …) | computed at test time, not pinned | phase 8, when the callers switch | no constant to regenerate; the execution oracle (§6.1) and the C4 suite running over new images (§6.1.4 (c)) are the evidence |
| the twelve R hashes and images of `debruijn_register_compile_test.cljc:449-620` and every other test of `lower-register`, `lower-stack`, `resolve`, `unresolve`, the lifts | tests of deleted code | phase 8 | **deleted, not regenerated**; their programs are in `C` and covered by §6.1-6.3; `golden-descriptor-hash-test` is moved first (row above) |
| the C4 `manifest-golden` (`linked_prelude_test.cljc:229-234`, JVM golden) and every published manifest | the C4 track's test; every publisher | phase 8 | publish twice (idempotence is already asserted) and diff the manifest map against the pre-cutover one. **Final manifest shape and every affected entry**: `:yin.module/name`, `:yin.module/schema` (1), `:yin.module/exports`, `:yin.module/requires`, `:yin.module/primitives`, `:yin.module/footprint`, `:yin.module/tree` — **unchanged**; `:yin.module/contracts` — `:yin.semantic/code` `"v3"`→`"v4"`, the three others unchanged (`"v3"` AST, `"b2"`, `"r2"`); `:yin.module/derivations` — all three record addresses change (semantic: output A_v4; stack and register: input A_v4 and new checksum outputs); `:yin.module/index` — **new shape** `{:yin.debruijn.code <h-storage-address> :yin.debruijn.register <r-storage-address>}`, keyed by format (within a manifest A is fixed by the semantic record, so the linker §3 amendment's `[A f]` key collapses to `f`), replacing today's `{H addr R addr}` (`publish.cljc:189`); both storage addresses change (new image bytes). Each changed address recomputes from its printed record or image |
| `:yin.module/index` entries in every linker-local `:indexes` and the index attribute `(linker/address-attribute f)` (`linker.cljc:948-973`) | the format index | phase 8 | re-minted from the manifests; a reviewer checks `(index-from-datoms f datoms)` against the manifest's index entry for `f` |

### 8.2 Descriptors and lifts

**DECIDED** by ruling 2 and the reviewer's finding. Both descriptors
declare a lift morphism, `[:yin.debruijn.code/dimension :dim/lift-to [:yin.code/*]]`
(`debruijn_code.cljc:145`) and `[:yin.debruijn.register/dimension :dim/lift-to [:yin.code/*]]`
(`debruijn_register_code.cljc:204`), and both rows are hashed into
`descriptor-hash` and so into every H and R. The lift functions
(`dl/lift`, `rc/lift`) and the `:yin.code/*` named vector they target are
deleted at phase 8 with `yin.vm.linearize`; keeping a declaration of a
morphism that no longer exists would be legacy. **At phase 8 the
`:dim/lift-to` row is removed from both descriptors**; `lowering-contract-version`
2 and `contract-version` 4 are **not** bumped (ruling 2), so the
descriptor change is a regeneration, and every H and R checksum is
regenerated once more (§8.1 rows). During phases 5-7 the descriptors do
not change, so the 5a/5b goldens are pinned under the current descriptor
hash and re-pinned at phase 8. The `contract-version` docstring's
"bumped whenever … allocation …" sentence is amended at phase 8 to
record the owner's no-version rule.

### 8.3 What keeps the old lowerers alive until phase 8

**DECIDED.** `yin.vm.debruijn-resolve`, `yin.vm.debruijn-linearize` and
`yin.vm.debruijn-register-compile` stay because the **production**
pipeline uses them until cutover: `linker/relowered` and
`relowered-hashes` (`linker.cljc:2142-2160, 2440-2449`),
`publish-closure!` (`publish.cljc:159-160`), the REPL's native
lowering (`repl.cljc:28-30, 229`), and the module manifests already
published by those paths (the C4 `py`/`pysp` manifests pin the old R).
They are also the **execution oracle** (§6.1) and the fifth leg of the
address law (§5.1). Phase 5 adds no caller of them. At phase 8 every
production caller switches to `derive`, the three namespaces and their
tests are deleted, and `yin.vm.debruijn/resolve-name` stays (it is the
one helper both phases share, `debruijn.cljc:617-637`).

---

## 9. Findings and proposed amendments

### 9.1 To the frozen design (`yin.vm.semantic-register-vm.md`)

Reviewer rulings (codex gpt-6.1-sol, route-(a) review, 2026-10-10): F9
**ACCEPT**; F8, F10, F11, F12, F13, F14 **ACCEPT WITH CHANGES**, the
changes applied below (F8: total scan order kept, liveness comparison
over actual successors; F10: precise executable coverage; F11:
descriptors regenerated when their lift declaration goes; F12: corrected
gates and the complete scanner text; F13: descriptor-dependent checksum
changes, no H-equality claim; F14: descriptor cleanup, index and record
migration, replacement coverage before deleting old tests).

**F8 — §8.2 adopted as written, with two closures and one correction
(ACCEPT WITH CHANGES).** Evidence: §2 of this document. Replace, in §8.2
"Intervals",

> `start(v)` is the pc of its first syntactic definition in textual
> order; `end(v)` is the greatest pc at which `v ∈ L(pc)` or `v` is
> syntactically defined,

with

> `start(v)` is the least pc at which `v` is syntactically defined or
> `v ∈ L(pc)`, and `end(v)` the greatest such pc; on reachable code
> `start(v)` is the first syntactic definition, and the widening covers
> only layout-syntax live points (derivations design §2.2),

"Scan" keeps its total order verbatim (expire in increasing `end` then
increasing virtual id; assign each starting interval in increasing
virtual id; the never-used counter advances only when a never-used slot
is taken). In step 3 ("Fill `live`"), replace "`live` is the physical
image of `L(pc+1) − {rd}`, computed statically over the whole CFG of the
body, unreachable components included, and for a tail `:call` it is
empty, because its successor set is empty" with "`live` is
`body-liveness` of the emitted physical body, the format's one liveness
function; its equality with the physical image of the virtual live-out
over the instruction's **actual successors** (§8.1) minus `rd`, which is
`L(pc+1) − {rd}` for a non-tail boundary and the empty set for a tail
`:call`, is a checked property of the gate (derivations design §2.5),
not a second definition". Replace the closing paragraph "What this replaces in R1 …
`lift` changes its output contract to the §2.3 table." with: "This
replaces R1's lowerer whole: `lower-register` and its tree walk are
deleted at cutover (§10 phase 8); `body-liveness`, the validator and
the kernel are reused unchanged; the R2 descriptor loses its lift
declaration at cutover (derivations design §8.2). The lifts are deleted
with the named linearizer."

**F9 — §8.3 has no adapter and parses once (ACCEPT).** Evidence: §3.2;
both emitters own their FIFO queue and label pass. Replace §8.3 steps
1-4 with:

> 1. Resolve as in §8.2 step 1 (the resolved vector carries the parse).
> 2. **Emit** by one walk over each body's recovered tree: operator,
>    `:push`, operands with `:push` each, `:call argc tail?`, labels for
>    conditionals, `:define name` after its value, bodies out of line in
>    FIFO discovery order, each ending in `:return`, main in `:halt`;
>    labels resolved to pcs in a second pass. Body discovery order
>    agrees with A's and R's, and the stack image's pcs are its own; H
>    is the stack kernel's bytecode and need equal neither R nor any
>    earlier image. H = `image-hash` of the result, as a checksum.

**F10 — §8.4 is replaced by the execution oracle (ACCEPT WITH CHANGES).**
Replace §8.4 whole with:

> ### 8.4 Verification
>
> R and H are functions of A alone and are two kernels' bytecode: they
> need not equal each other or any earlier image, and no gate compares
> them with one. The old lowerers are an **execution** oracle: every
> program of the corpus the derivations design §6.1.1 names runs through
> the old and the new pipeline on the same kernels under the same
> composition and must give identical observable results under one
> normalization (§6.1.2), with the executable rows stated precisely
> (§6.1.3: the 26 B0 rows and twelve register-corpus rows to a value,
> nineteen rows to an equal throw, one FFI row under an explicit
> zero-argument bridge; §6.1.4: ten C4 root import programs to their
> pinned outcomes and the eight module bodies run as root programs, the
> install-child path over new module images being phase 8's). Every
> emitted image passes the format's validator, including `live-exact`;
> the tricky cases are pinned as hand-derived goldens (§6.3); and
> determinism is checked (same A, same bytes; ASTs differing only in
> facts A omits, same A). Pinned checksums are regenerated, once at the
> slice and once at cutover (§8.1); nothing is versioned.

**F11 — §6, second paragraph (ACCEPT WITH CHANGES).** Replace from "The
de Bruijn contracts `"b2"` and `"r2"` are **not re-versioned by this
document**." to the paragraph's end with:

> The de Bruijn contracts keep their names `"b2"` and `"r2"` and their
> version numbers; their lowerers change in place (owner, 2026-10-11: no
> legacy, no version). At cutover their descriptors lose the lift
> declaration `:dim/lift-to`, since the lift and its target are deleted
> with the named linearizer; the descriptor hashes and every H and R
> checksum are regenerated then, mechanically (derivations design §8.1-8.2).

**F12 — §10 phase 5 (ACCEPT WITH CHANGES).** Replace "the §8.4
byte-and-contract decision recorded with its evidence" with "the
execution oracle, validators, goldens and determinism checks of §8.4
green on three hosts, the slow C4 rows actually run"; and replace the
scanner clause from "**the dependency-closure scanners …**" to the end of
the phase with:

> **the dependency-closure scanners (UCF §7.6.1) reimplemented over the
> new table agree with the old ones and with the tree-side oracles on
> the B0 corpus, the register corpus and the C4 linked-prelude modules
> by name**, under three obligations (derivations design §7.3): (i)
> unordered agreement, old = new = the tree scanners and queries, on
> free-name sets, the multiset of free-name occurrences as
> `[name in-body?]`, the multiset of definitions as `[name conditional?]`,
> and the store-key, FFI-op, parked-id and effect-kind sets (positions
> excluded since the layouts differ; the tree scanners order by
> structural path and are compared unordered); (ii) ordered agreement,
> old = new = an independent emission-order tree oracle (main sequence
> then bodies FIFO; a definition visits only its value operand), on the
> occurrence and definition sequences and on the application-site count
> (definition applications excluded, FFI sites included); (iii) join
> agreement through `linker/verify`, old = new on the **complete
> normalised outcome**: a success with its retained obligations as
> `[name in-body?]` in order, or a refusal with its reason and name
> (`:use-before-definition` included, exercised by a row that must
> refuse on both sides). The old scanners' answers, normalised joins
> included, are pinned as goldens before cutover deletes them, since the
> C4 track's module declarations are built against those answers; the
> C4 gate is read-only and compares declarations against the discharged
> external-name set (module-defined keys and `yin/def` removed,
> requirement-covered names removed), not raw free names.

**F13 — §9 "Corpora" (ACCEPT WITH CHANGES).** Replace "H and R goldens
are preserved only if §8.4 says the bytes held." with "R and H goldens
are the new lowerers' own, pinned at the slices and re-pinned at cutover
when the descriptors lose their lift declaration (derivations design
§8.1-8.2); neither is compared with an earlier image."

**F14 — §10 phase 8 (ACCEPT WITH CHANGES).** After "remove the old H/R
request paths and the `raise` dependency," insert "remove the
`:dim/lift-to` row from both de Bruijn descriptors without a version
bump and regenerate the descriptor hashes and every pinned H and R
(derivations design §8.1-8.2); delete `yin.vm.debruijn-resolve`,
`yin.vm.debruijn-linearize`, `yin.vm.debruijn-register-compile` and their
tests only after the replacement coverage of §8.4 is green and the
descriptor-hash golden has moved to a surviving namespace; switch
`linker/relowered`, `publish-closure!` and the REPL to the A-derived
lowerings (§8.3); regenerate the manifests' derivation records as
`A → H`, `A → R` and the format index in its final shape
`{format storage-address}` (§7.6, §8.1); run the C4 suite over the newly
published images as the install-child gate (§6.1.4 (c))".

### 9.2 Statements that conflict with the unitarity invariant

**DECIDED** by ruling 4; amendment texts **PROPOSED** (not applied here).

| Document and place | Statement | Conflict | Proposed amendment |
|---|---|---|---|
**The source association, defined once and used by every row.** The
invariant asks that the AST be *retrievable*: by address, or by a query
over its datoms. It does not ask that it be fetched, and A does not
identify it (§4.2: several trees project to one A). So the association
is a **set-valued index from a segment address to tree addresses**:

```
source-index : A → #{tree-address …}      ; the trees this composition knows project to A
```

- **Carrier.** In a manifest, `:yin.module/tree` (one tree; the one the
  publisher lowered from, also the `:yin.ledger/input` of its semantic
  derivation record). In a by-identity link outcome, a new key
  `:yin.link/trees #{…}`, the serving composition's `source-index`
  entry for A. In a UCF value, `:yin.k/requires :yin.k/trees {A #{tree-address …}}`,
  one entry per address in `:yin.k/segments`. In a composition, the
  linker-local `:indexes` gain `:source-index`, populated at
  publication (`publish-closure!` knows both addresses) and at every
  verified manifest or outcome that names a tree.
- **Independence.** The canonical vector's bytes and A are unchanged by
  the association: it is an index beside the content, never inside it
  (A is `segment-key` of the vector alone, UCF §7.3.2).
- **Several trees, one A.** The set may hold several addresses; any one
  retrievable member satisfies the invariant. A derivation record names
  the one tree it was lowered from; the record's input is that tree, not
  "the" tree of A.
- **Completeness check.** A value or outcome is **complete** for the
  invariant when every segment address it carries or requires has a
  non-empty `source-index` entry. Absence of an entry is the design
  defect the owner names (the lowering site lost the tree): a manifest
  without `:yin.module/tree` for guest code is `:manifest-shape`; a UCF
  value with a segment in `:yin.k/segments` and no `:yin.k/trees` entry
  is `:yin.k/missing :yin.k/trees` and discovery `:incomplete` (§7.6.5).
- **Absence versus transient failure.** A **known** tree address whose
  read fails now (a `dao.jing` miss, a transport error) is not absence:
  the association is intact and the read is retried or reported as the
  ordinary fetch outcome of the medium (`:absent` at step 2 of the link
  it belongs to). Nothing in this design turns a failed read into a
  refusal of code that is otherwise linkable: retrievability is a
  property of the association, not of one read.

| Document and place | Statement | Conflict | Proposed amendment |
|---|---|---|---|
| `yin.vm.linker.md:216-222` (§3 amendment) | "A request names `{:format f :hash A}`; the format index maps `[A f]` to the storage address of the native image for `f` … the canonical vector stays obtainable independently of any native-image entry." | A receiver can obtain A and a native image with no path to the tree; nothing in the by-identity flow names a tree. | After "stays obtainable": "and so does a tree it was projected from: the composition's `source-index` maps A to the set of tree addresses it knows (populated at publication and from every verified manifest), and a by-identity outcome for any format carries that set as `:yin.link/trees`. A is not a key to one tree (several ASTs project to one A); the invariant is satisfied when at least one member is known. A native image for which the serving composition knows no tree is a composition defect (`:tree-unavailable` at step 2), distinct from a known tree whose read fails, which is the medium's ordinary `:absent`." |
| `yin.vm.linker.md:225-232` (§3 amendment, walker bullet) | "the verification direction is **tree → A**, and no reconstruction of a tree from A is required or assumed." | None: retrieval, not reconstruction, is what the invariant requires. Keep; add one sentence. | Append: "A tree is retrieved by address through the `source-index`, never reconstructed from A." |
| `yin.vm.linker.md:1776-1783` (§8.1, `:trusted`) | "Under `:trusted`, the linker checks only the record's input and output addresses … A composition that chooses `:trusted` has chosen to accept the publisher's lowering." | `:trusted` skips fetching the tree; nothing says the composition must still be able to retrieve it. The conflict is one of obligation, not of fetching. | Append: "`:trusted` skips **re-lowering**, never retrievability: the composition that serves under `:trusted` holds the manifest's `:yin.module/tree` address in its `source-index` and is responsible for the tree being retrievable from it (the publisher's store, or a store it can reach); no fetch is performed by the policy, and a tree whose read fails later is the medium's `:absent`, not a policy violation." |
| `yin.vm.linker.md:1984-1987` (§8.3, host modules) | "A host module is entered as an already-linked manifest with `:yin.module/tree` absent, `:yin.module/derivations {}`, and its exports listed under `:yin.module/primitives` by profile address." | Read literally, a manifest without a tree is code without an AST. | Append (the absent field stays as it is): "This is the **guest-code carve-out**: the unitarity invariant governs guest code, the code that is lowered. A host module lowers nothing, has no AST and no derivations, and declares only profiles; its manifest therefore carries no tree, and `:manifest-shape` requires that a manifest without `:yin.module/tree` also have empty `:yin.module/derivations` and no `:yin.module/index`, so a tree-less manifest can never name an image." |
| `yin.vm.universal-continuation-format.md:511` (§7.3.4) and `:528-536` (resolution order) | "`:yin.k/carried` carries vectors, not batches"; a resumer resolves an address from its index, from `:yin.k/carried`, or from `dao.jing`, and loads on the two checks (hash, grammar). | A continuation can be resumed from carried vectors alone; no tree of any carried or required segment is named in the value, so wherever a resumer lowers (a native kernel deriving R or H from a carried A) the tree may be unreachable. | After "carries vectors, not batches": "and `:yin.k/requires :yin.k/trees` maps every address in `:yin.k/segments` to the set of tree addresses the emitter's `source-index` held for it. The three-way resolution order applies to **vectors**; a tree is resolved by address through the resumer's own `source-index` or by a `dao.jing` read, and the value's `:yin.k/trees` entries are merged into that index on arrival. A segment with no `:yin.k/trees` entry is `:yin.k/missing :yin.k/trees` and discovery is `:incomplete` (§7.6.5), never a silent load; a known tree address that does not read now is a fetch outcome, not a missing association. The derivations of native images do not read the tree; the value preserves retrievability, which is what the invariant asks." |
| `yin.vm.linker.md` §8.1 manifest rules, "One tree" | "The `:yin.ast/code` image *is* this tree, so it has no derivation record." | None. Keep. | — |

### 9.3 Checked and consistent (no amendment)

Frozen §3.3 minting against the scan (pre-order ids are not pc-ordered
and the scan orders by pc, as §3.3 item 2 says); §3.4 rule 6 as the
resolution chain; §8.1 (`def`, `use`, `L`, `saved`) consumed unchanged
by §2.2; the F6 interim format name and `"v4"` contract (§7.5); the
register design §4.5's "one definition of liveness" (§2.5); the linker
§3 amendment's derivation direction tree → A → image (§7.6).

---

## 10. Slices and gates

Three slices. Verification follows `docs/agents/build-n-test.md`: G0
`clojure -M:test -n <ns>` per iteration (JVM), G1 `bb test:sub yin.vm`
at the checkpoint on three hosts, G2 `bb test:changed` before landing,
G3 `bb test` on master. New namespaces under
`src/cljc/yin/vm/semantic_register/` and `test/yin/vm/semantic_register/`
are inside the `yin.vm` subsystem by prefix; the Node log must show
`Testing yin.vm.semantic-register.<ns>` for each new test namespace.

### 10.1 Slice 5a: the resolved vector, `addresses`, A → H

**Files to create.** `src/cljc/yin/vm/semantic_register/derive.cljc`
(`resolve`, `addresses`, `resolved-addresses`, `stack-image`;
`register-image` declared and throwing `{:reason :not-in-slice-5a}`
until 5b) and `test/yin/vm/semantic_register/derive_test.cljc` (corpus
`C` respelled, `native-normalize`, the oracle runners). **Untouched:**
every existing source file and test.

**Acceptance (5a).**

1. `derive-test/resolve-rewrites-only-vars-and-closures`: for every
   `P ∈ C`, the resolved vector has A's length; every tuple is A's except
   `:var` → `:load-bound`/`:load-free` and `:closure` → arity; `:names`
   maps every `:load-bound` pc to the name it replaced.
2. `derive-test/resolution-is-resolve-names`: `:duplicate-param` → `[0 1]`;
   `:nested-closure` `a` → `[1 1]`, `b` → `[0 0]`; `:nested-lambdas` `a`
   → `[1 0]`, `b` → `[0 0]`; `y` in `:free-variable` is `:load-free`.
3. `derive-test/resolve-is-a-stage-value`: the namespace exports no
   function that assigns an executable identity (no name containing
   `hash`, `key`, `identity`, `digest`, none named `address` or
   `code-address`); the two address-sequence readers `addresses` and
   `resolved-addresses` are permitted by name and return vectors of
   `[:bound d q]`/`[:free sym]` tuples; `resolve`'s result round-trips
   `pr-str`/`read-string`.
4. `derive-test/stack-goldens`: the H images of §6.3 rows 1-9, pinned
   literally with the pc of every label, and their `image-hash` strings
   pinned from the printed vector (§8.1). The test compares the emitter
   with these rows and with nothing else: no `dl/adapt` call, no
   observation about the old stack image.
5. `derive-test/stack-images-validate`: §6.2 items 1-3 for every `P ∈ C`
   on the stack image.
6. `derive-test/stack-determinism`: §6.2 items 5-6 for the stack image
   (vectors and addresses compared, not provenance-bearing maps).
7. `derive-test/stack-execution-oracle`: §6.1 over `C`'s runtime rows on
   `dvm/create-vm` for the old and new pipelines, equal observables:
   26 B0 rows also equal to their pinned `expected` under
   `native-normalize`; twelve register rows to a value; nineteen register
   rows to an equal `[:thrown message]`; `:ffi-call-no-args` to
   `[true false :pong]` under the explicit `{:op/ping (fn [] :pong)}`
   bridge (§6.1.3); counts 26, 12, 19, 1 asserted.
8. `derive-test/lexical-address-law-stack`: §5 legs A, `resolve(A)`, H,
   and the old resolver through `resolved-addresses`, with the
   non-vacuity assertions.
9. `derive-test/register-image-is-not-in-5a`.
10. kondo clean; `bb test:sub yin.vm` green on JVM, Node, Dart; the Node
    log shows `Testing yin.vm.semantic-register.derive-test`.

### 10.2 Slice 5b: A → R

**Files to change.** `derive.cljc`: `register-image` per §2.
`derive_test.cljc`: the items below. **File to create:**
`test/yin/vm/debruijn_register_code_test.cljc` (item 10). Nothing else;
`debruijn_register_code.cljc` and the register kernel are untouched.

**Acceptance (5b).**

1. `derive-test/register-goldens`: the R images of §6.3 rows 1-9 pinned
   literally (instructions **and** body descriptors), their
   `register-hash` strings pinned from the printed image (§8.1); row 9's
   tail-call-as-operand image with `live [] ` on the tail call is among
   them.
2. `derive-test/register-images-validate`: §6.2 items 1-3 for every
   `P ∈ C`, `register-image-defect` nil including `live-exact`.
3. `derive-test/two-liveness-analyses-agree`: §6.2 item 4 on every
   boundary pc of every `P ∈ C` and of §6.3 row 9: the filled `live`
   equals the physical image of the virtual live-out over
   `analysis/successors` minus `rd`; every tail `:call` has `live []`
   on both sides.
4. `derive-test/structural-slot`: `:resume-operand`, `:all-terminal-arms`
   and `:resume-lambda-body` have `registers = peak + 1`, the structural
   slot is named only by layout-syntax operands, and it appears in no
   boundary's `live` on a reachable pc (asserted by checking it is absent
   from every `live` whose pc is in `analysis/reachable`).
5. `derive-test/register-determinism`: §6.2 items 5-6.
6. `derive-test/register-execution-oracle`: §6.1 on `rvm/create-vm`, as
   5a item 7 (counts 26, 12, 19, 1 asserted).
7. `derive-test/lexical-address-law`: all legs.
8. `derive-test/worked-example-slots`: §6.3 row 2 exactly, including
   `[:call 3 1 [2] false [0]]`.
9. `derive-test/c4-execution-oracle` (`^:slow`, guarded): §6.1.4, both
   kernel families, old = new per program: (a) the ten root import
   programs, each also equal to its pinned C4 outcome (count 10
   asserted); (b) the eight module bodies run as root programs, equal
   observables and the defined-key set equal to `prelude/defined-keys`
   (count 8 asserted); (c) is stated as not covered until phase 8.
   **Must actually run for sign-off** (`bb test:sub yin.vm --slow` or
   `clojure -M:test -i :slow -n yin.vm.semantic-register.derive-test` on
   three hosts; the report quotes the two counts it saw).
10. `debruijn-register-code-test/golden-descriptor-hash-test`: the
    descriptor-hash golden moved out of `debruijn_register_compile_test.cljc:445-450`
    into the new surviving namespace, same value (§8.1); the old copy is
    left in place until phase 8 deletes its file.
11. kondo clean; `bb test:sub yin.vm` green on three hosts.

### 10.3 Slice 5c: scanners and format record

**Files to create.** `scan.cljc`, `scan_test.cljc`, `scanner_goldens.cljc`
(§7.2-7.4). **Files to change, additively.** `linker.cljc`
(`semantic-register-format`, §7.5), `yin/repl/link.cljc:81-89`
(`formats`), `linker_test.cljc` (one deftest per existing pattern,
`semantic-register-scanners-yield-position-bearing-records`, template
`:812`; the `"v4"` row in `format-records-name-their-contract`, `:562`).
**Untouched:** `vm.cljc`, `completion.cljc`, `handoff.cljc`, `publish.cljc`,
`relowered`, the C4 track, every kernel and lowerer. **No production
pipeline switches in 5c**: that is phase 8 (§7.6, §8.3).

**Acceptance (5c).**

1. `scan-test/positions-are-the-v4-table`: on `:store-ops`, `:define-call`,
   `:ffi-call`, `:resume-body`, `:streams`, `:park`: `requirements` per
   program (`:store-ops` → `{:store-keys #{k :n 7}}`, `:define-call` →
   `#{y}`, `:ffi-call` → `{:ffi-ops #{:op/echo}}`, `:resume-body` →
   `{:parked-ids #{:p}}`, `:streams` → the five stream effects); a
   register id is never mistaken for a key, op or pid.
2. `scan-test/free-occurrences-use-the-owner-tree`, exact per fixture:
   `:nested-lambdas` `[[+ true]]`, `a`/`b` bound; `:body-queue-order`
   and `:closure-in-arm-in-body` none; `:define-then-call`
   `[[inc false] [n false] [list true]]` (pcs 3, 4, 8), `a`/`b` bound;
   `:if-in-tail` `[[< true] [loop true] [- true]]`.
3. `scan-test/definitions-and-sites`: `:define-then-call` one definition
   `[n false]` (pc 2) and three sites (pcs 5, 6, 11); `:define` `[x false]`,
   no site; `:store-ops` `[k false] [:n false]`, one site; `:if-in-tail`
   no definition, four sites (pcs 2, 7, 15, 16); `:ffi-call` one site;
   each count equals `(count ((:applications-fn linker/semantic-format) v3))`.
4. `scan-test/footprint-mnemonics-is-the-v3-table-minus-push`.
5. `scan-test/scanners-agree-on-b0` (26 rows plus `:use-before-definition`): §7.3 (i)-(iii).
6. `scan-test/scanners-agree-on-the-register-corpus` (32 rows): (i)-(iii).
7. `scan-test/scanners-agree-on-the-c4-modules` (`^:slow`, guarded): the
   eight module ASTs; (i)-(iii); the raw equality with `yin/def`
   removed; the discharged equality with the spec's `:primitives` keys.
   Must actually run for sign-off, as 5b item 9.
8. `scan-test/goldens-match`: every map of `scanner_goldens.cljc`,
   normalised join included, equals the live `"v3"` answer and the new
   answer.
9. `linker-test/semantic-register-scanners-yield-position-bearing-records`;
   `format-records-name-their-contract` with the `"v4"` row.
10. `scan-test/verify-outcomes-agree`: for every program of the three
    corpora, equal normalised `linker/verify` outcomes on the two format
    records; `:use-before-definition` refuses on both.
11. kondo clean; `bb test:sub yin.vm` and `bb test:changed` green on
    three hosts; the Node log shows `Testing yin.vm.semantic-register.scan-test`.

### 10.4 Ordering and concurrency

| | needs | concurrent with |
|---|---|---|
| 5a | phase 3 (landed) | 4a review, 4b, 5c |
| 5b | 5a (`resolve`, `native-normalize`, the runners) | 4b, 5c |
| 5c | phase 3 only | 4b, 5a, 5b |

Phase 4b touches `semantic_register.cljc`, its three test namespaces and
four backend maps (evaluator design §5.2); phase 5 touches none of them
and 4b touches none of phase 5's files, so all three slices run
concurrently with 4b in separate worktrees. After both 4b and 5c land,
the `linker_require_test.cljc` fixture comment "empty obligations until
phase 5 supplies the register-vector scanners" is retired by pointing
the backend's `:obligations` at `semantic-register-format`'s scanner, a
two-line follow-up belonging to whichever lands second. Phase 6 is the
first consumer of `scan` outside the linker (the UCF census over
register frames); `handoff.cljc`'s `free-names-v2`/`validate-closures-v2`
gain their `"v4"` arms there. Phase 8 performs §7.6, §8.1's cutover rows,
§8.2's descriptor change and §8.3's deletions, and runs the C4 suite over
newly published images as the install-child gate (§6.1.4 (c)).

---

## 11. Owner questions

**None.** Every decision above follows from the rulings of §0.1 (with
the 2026-10-11 ruling that R and H are two kernels' bytecode and need
equal neither each other nor any earlier image), the frozen design, or
code that exists. One item is flagged for the owner's attention without
needing a decision: the unitarity amendments of §9.2 are proposed text
for documents this phase does not edit, and their adoption is the
orchestrator's queue item, not this slice's.

---

## 12. Portability notes for the implementer

1. **No host map iteration order enters a derivation.** `resolve` and
   both emitters walk vectors in pc order and parse trees in child
   order; the scan's free set is a `sorted-set`; `body-liveness` uses
   sorted sets; scanners' set facts are compared as sets, sequences in pc
   order. Never `(keys m)` into an emitted structure.
2. **Reader conditionals.** Catch clauses are
   `#?(:cljd Object :clj Throwable :cljs :default)` with `:cljd` first;
   any `:clj`-only form (the golden-printing helper) is
   `#?(:cljd nil :clj …)`.
3. **Private var access (Dart).** `resolve`, `addresses`,
   `resolved-addresses`, `stack-image`, `register-image`, `native-normalize`
   (in the test namespace, public), every `scan` function and the
   `scanner_goldens` var are public; tests never reach through `#'`
   (`debruijn_register_contract_test.cljc:256-262` records why).
4. **`for` over long seqs (Dart).** The C4 `py` module has thousands of
   tuples; build with `mapv`/`into`/`reduce`, never `for`.
5. **Multi-key `assoc` on nil (Dart).** Slot maps and side tables start
   from `{}`.
6. **Protocol parameter names (Dart).** Not applicable: no protocol.
7. **EDN and whitespace before closers (Dart reader).** `scanner_goldens`
   and the pinned images are Clojure data in `.cljc`, not EDN strings.
8. **Integers only.** Slot arithmetic is `locals + i`; no float negation.
9. **Keyword identity (Node).** `case` on mnemonics and `contains?` on
   keyword sets are portable (the phase-3 validator does both,
   `code.cljc:368-398`).
10. **Slow lanes.** The C4 oracle and scanner gates are `^:slow` and
    wrapped in `dao.test-slow/guard`; `bb test:sub yin.vm --slow` runs
    them; one Dart lane at a time repo-wide.
11. **Fresh worktree.** `mise trust` and `npm ci` before the Node lane.
