# yin.vm.semantic-register-vm.derivations: phase 5, the de Bruijn images and the scanners derived from A

Status: **Companion design, phase 5 of `yin.vm.semantic-register-vm.md`
§10.** Written 2026-10-10 (Architect: claude-fable-5-1) from the frozen
design as amended (`0ff25b58`), the landed phase-3 code
(`src/cljc/yin/vm/semantic_register/`), the de Bruijn resolver and
lowerers as they stand, the linker and the dependency-closure code, and
the corpora the gates run on. The frozen document is not changed by this
one: where this design closes a strategy the frozen text left open, or
finds its text wrong against the code, §8 records a finding with the
exact amendment. Subordinate to [`datom.world.md`](./datom.world.md) and
to [`yin.vm.semantic-register-vm.md`](./yin.vm.semantic-register-vm.md)
("the frozen design" below); the format and rigor model is the phase-4
companion [`yin.vm.semantic-register-vm.evaluator.md`](./yin.vm.semantic-register-vm.evaluator.md).

Every section marks its content **DECIDED** (follows from frozen text or
from code that exists) or **PROPOSED** (a design choice of this document,
open to the reviewer). File references are to the worktree at
`0ff25b58`; line numbers are those of that commit.

Contents: §1 the shared resolve-on-vector function · §2 A → H · §3 A → R,
the key decision (F8) · §4 the byte-identity and contract decision
procedure · §5 the lexical-address law · §6 the scanners and the linker
records · §7 slices and gates · §8 findings and amendments · §9 questions
· §10 portability.

---

## 0. The one-paragraph answer

Phase 5 is three functions over an admitted register-shaped vector `v`
(A = `ucf/code-address v`): `resolve-vector`, which parses `v` under the
phase-3 grammar and emits **resolved tuples in the exact shape
`yin.vm.debruijn-resolve/resolve` emits today**; `stack-image`, which is
`lower-stack` of that; and `register-image`, which is `lower-register` of
that. Nothing about either de Bruijn lowerer, validator, descriptor,
kernel or liveness function changes. H and R are therefore today's bytes
**by construction** (§3.3 gives the argument and §4 the gate that proves
it per program), `"b2"` and `"r2"` are retained, and every pinned H and R
golden in the repository, the C4 track's included, stands. The frozen
§8.2 route, a new pinned interval allocator over the vector, is shown in
§3.2 to reproduce today's R for **no program containing a call**, so
taking it would re-version `"r2"` for the sake of an allocator the
existing one already is. That is Finding F8; §8 holds the amendment text.
The scanners (§6) are the one place phase 5 writes new logic: the
dependency-closure walk's segment side reads tuple positions, and the
§2.3 table moved every operand one slot right of the stack table and
removed `:push`; a scanner namespace over the new table, a format record,
and an agreement gate against the old scanners on three corpora close it.

---

## 1. The shared resolve-on-vector function

### 1.1 Where it lives, what it takes, what it returns

**PROPOSED.** Namespace `yin.vm.semantic-register.derive`, file
`src/cljc/yin/vm/semantic_register/derive.cljc`, beside the three landed
phase-3 namespaces. It requires `yin.vm.semantic-register.code` (the
parser, `body-ranges`, `body-index`, `rd`, `uses`),
`yin.vm.debruijn` (`resolve-name` only), `yin.vm.debruijn-resolve`
(`validate-resolved` only), `yin.vm.debruijn-linearize` (`lower-stack`),
`yin.vm.debruijn-register-compile` (`lower-register`), and `yin.vm`
(`definition-operator`). It does **not** require `yin.vm.linearize`,
`yin.vm.code`, or either kernel.

```clojure
(resolve-vector v)   ; v: a vector code/well-formed? accepted
;; => {:tuples [[rid attr value 0 :db/add] …]   ; resolved tuples, B2 §3.1 shape
;;     :source {rid pc}                          ; total over the records
;;     :params {closure-rid [sym …]}}            ; every resolved lambda's params
```

**DECIDED** (frozen §8.2 step 1, "the output is a stage value with no
identity"; register design §2.1, `yin.vm.debruijn.register.md:245-265`;
stack design §3.1, `yin.vm.debruijn.stack.md:378-386`): the return value
is never hashed, never persisted, never served, never a fetch key. The
namespace exports no digest of it and no test pins its bytes. Its only
consumers are the two lowerers (§2, §3) and the address-law test (§5).

**DECIDED** (precondition): `v` has passed `code/well-formed?`
(`code.cljc:842-853`). The function assumes items 1-13 and does not
repeat them; it re-runs `code/parse` (`code.cljc:856-861`) because
`load-vector` strips the `:expr` trees from the image it returns
(`code.cljc:879`). The parse is pure and linear; recomputing it is the
same stance frozen §10 phase 3 takes for `:analysis` ("engine-local
derived data, neither parsed trees nor saved sets enter canonical
content").

### 1.2 The closure-owner tree and the lexical chain

**DECIDED** (frozen §3.4 rule 6; `code/body-ranges`, `code.cljc:256-276`;
`partition-defect`, `:292-336`). `body-ranges` returns, in pc order, one
body per distinct `:closure` body-pc plus main, each with `:owner`, the
pc of the `:closure` naming it (nil for main). `partition-defect` has
already established that every non-main body has exactly one owner, that
an owner lies in a different body, and that following owners reaches
main (`:309-323`). So:

```
chain(body i) = []                                       when i is main
              = (cons params(owner_i) chain(body-of owner_i))  otherwise
params(c)     = (nth (nth v c) 2)                        ; [:closure rd params body-pc]
body-of(pc)   = (code/body-index bodies pc)
```

`chain` is innermost-first, exactly the `stack` argument
`debruijn/resolve-name` takes (`debruijn.cljc:617-637`: "frame depth 0
is the innermost frame") and exactly what `resolve` pushes when it
enters a lambda body (`(into [params] stack)`,
`debruijn_resolve.cljc:174`).

### 1.3 Bound-variable lookup

**DECIDED.** For `[:var rd name]` at pc `p` in body `i`:
`(debruijn/resolve-name (chain i) name)` → `{:bound [depth position]}`
or `{:free name}`. No other resolution logic exists in the derive
namespace; duplicate parameters and shadowing are therefore
`resolve-name`'s by construction:

- **Duplicate parameters, rightmost wins**: `(fn [x x] x)` → `[0 1]`
  (`debruijn.cljc:621-624`, "positions search right-to-left within a
  frame"), pinned by `debruijn_test.cljc:391-402`
  (`duplicate-parameters-are-rightmost-wins`, which also proves the
  position names the argument `engine/bind-params` binds) and
  `debruijn_resolve_test.cljc:200-207`
  (`duplicate-parameter-resolves-rightmost-wins`).
- **Shadowing, innermost wins**: `debruijn_test.cljc:363-389`
  (`nearest-binder-shadows-with-frame-depth-and-position`: `[[x]]`→`[0 0]`,
  `[[y] [x]]`→`[1 0]`, inner `x` shadows outer `x`).
- **Free names preserved exactly**: `debruijn_test.cljc:405-410`.

The `:closure params` operand of A is the front end's parameter vector
verbatim (`linearize.cljc:105`, `(vec (slot id :params))`; item 2
`:syms` kind, `code.cljc:177`), so the frames `resolve-name` searches are
the same vectors `resolve` searches over named datoms. This is what makes
§1.6's equivalence hold for binders.

### 1.4 Record shapes, field by field

**DECIDED** by `validate-resolved` (`debruijn_resolve.cljc:454-492`) and
by what the two lowerers read (`debruijn_linearize.cljc:103-168`,
`debruijn_register_compile.cljc:152-306`). One resolved record per parse
node; a datom is `[rid attr value 0 :db/add]` (`resolve` uses the
source's `[t m]`, defaulting to `[0 :db/add]`, `debruijn_resolve.cljc:120`;
no lowerer reads `t` or `m`). The parse node for an instruction at pc
`p` is `{:op mnemonic :pc p :children […]}`; a conditional is
`{:op :if :pc branch-pc :jump jpc :end Lend :children [test then else]}`
(`code.cljc:350-406`). Children are in `code/uses` order, which is §3.1's
child order (`code.cljc:81-94`).

| A tuple at pc `p` (parse node) | Record attributes | Side tables |
|---|---|---|
| `[:const rd v]` | `:yin/type :literal`, `:yin/value v` | `:source {rid p}` |
| `[:var rd name]` | `:yin/type :variable`; bound → `:yin.resolved/depth d`, `:yin.resolved/position q`; free → `:yin.resolved/free name` | `:source` |
| `[:closure rd params body-pc]` | `:yin/type :lambda`, `:yin.resolved/arity (count params)`, `:yin/body <rid of the body's root expression>` | `:source`; `:params {rid params}` |
| `[:call rd f args tail?]` | `:yin/type :application`, `:yin/operator <rid f>`, `:yin/operands [<rid a₁> …]` (always present, `[]` for no operands), `:yin/tail? true` **only when** `tail?` is true | `:source` |
| `{:op :if …}` | `:yin/type :if`, `:yin/test`, `:yin/consequent`, `:yin/alternate` (rids) | `:source {rid branch-pc}` |
| `[:define rd name rs]` | `:yin/type :application`, `:yin/operator <op-rid>`, `:yin/operands [<key-rid> <rid rs>]`; plus two **synthesized** records: op-rid `{:yin/type :variable, :yin.resolved/free yin/def}`, key-rid `{:yin/type :literal, :yin/value name}` | `:source {rid p, op-rid p, key-rid p}` |
| `[:gensym rd prefix]` | `:yin/type :vm/gensym`, `:yin/prefix prefix` | |
| `[:store-get rd key]` | `:yin/type :vm/store-get`, `:yin/key key` | |
| `[:store-put rd key value]` | `:yin/type :vm/store-put`, `:yin/key key`, `:yin/value value` | |
| `[:stream-make rd buffer]` | `:yin/type :stream/make`, `:yin/buffer buffer` | |
| `[:stream-put rd s x]` | `:yin/type :stream/put`, `:yin/target <rid s>`, `:yin/val-node <rid x>` | |
| `[:stream-cursor rd s]` / `[:stream-next rd c]` / `[:stream-close rd s]` | `:yin/type :stream/cursor` / `:stream/next` / `:stream/close`, `:yin/source <rid>` | |
| `[:ffi-call rd op args]` | `:yin/type :dao.stream.apply/call`, `:yin/op op`, `:yin/operands [rids]` (always present) | |
| `[:current-continuation rd]` | `:yin/type :vm/current-continuation` | |
| `[:park rd]` | `:yin/type :vm/park` | |
| `[:resume id x]` | `:yin/type :vm/resume`, `:yin/parked-id id`, `:yin/val-node <rid x>` | |
| `[:jump t]`, `[:return r]`, `[:halt r]` | no record (the parse has no node for them; `:return`/`:halt` are the body terminators, `:jump` is inside the `:if` node) | |
| root | one extra fact `[main-root-rid :yin/root true 0 :db/add]`, last in the vector, so `vm/index-datoms` finds it (`vm.cljc:1921-1929`, the last `:yin/root` fact wins) | |

Why each shape is what it is:

- **`:yin/operands` always present.** `validate-resolved`'s
  `missing-children-defect` (`debruijn_resolve.cljc:437-451`) refuses an
  `:application` or `:dao.stream.apply/call` record with no
  `:yin/operands` datom at all, because `get-attr` answers `[]` alike for
  absent and empty. A zero-operand call (`[:call 0 1 [] false]`, golden
  `:zero-arity-call`, `corpus.cljc:142-146`) therefore emits
  `[rid :yin/operands [] 0 :db/add]`.
- **`:yin/tail?` only when true.** `resolve` emits it under `when-let`
  (`debruijn_resolve.cljc:143-144`); both lowerers read it through
  `boolean` (`debruijn_linearize.cljc:133`,
  `debruijn_register_compile.cljc:199`). Emitting `false` would be
  harmless to bytes but would make the tuple sets differ from `resolve`'s
  for no reason.
- **The definition's synthesized records.** A's `[:define rd name rs]`
  carries the key as a literal operand and no operator (frozen §2.3;
  `linearize.cljc:107-112`). Both lowerers recognise a definition by
  `resolve/definition-operator?` on the operator record
  (`debruijn_resolve.cljc:99-104`: a `:variable` whose
  `:yin.resolved/free` is `vm/definition-operator`) and read the key
  through `resolve/definition-key` → `vm/definition-name`
  (`debruijn_resolve.cljc:107-115`; `vm.cljc:199-206`), whose
  `definition-shape-defect` (`vm.cljc:185-197`) requires exactly two
  operands with the first a literal non-reserved symbol. The synthesized
  operator and key records give the lowerers exactly that; neither is
  ever lowered (`debruijn_linearize.cljc:120-126` and
  `debruijn_register_compile.cljc:178-187` lower `(second operands)`
  only). A's name operand is a symbol (item 2 `:sym`, `code.cljc:59`) and
  not reserved (item 11, `:663-675`), so `definition-name` never throws.
- **`:yin/macro?` is not emitted.** A has no macro flag (§2.3; the
  projection ignores it, `linearize.cljc:97-148`); `resolve` carries it
  when present (`debruijn_resolve.cljc:171`); neither lowerer reads it,
  so its absence changes no byte. The `unresolve` oracle, which does read
  it (`:556-557`), is not run on `resolve-vector`'s output.
- **One record per occurrence, never re-shared.** A is positionally
  expanded (frozen §3.3 item 1; `shared-occurrences-expand-positionally`,
  `linearize_test.cljc:107-119`), so every parse node is one occurrence
  and gets one record. `resolve` memoises `[source-eid stack]` and would
  share a record across equal-context repeats (`debruijn_resolve.cljc:283-286`),
  but both lowerers emit per *reference*, not per record
  (`debruijn_linearize.cljc:127-131` calls `lower-node` on each operand
  rid; `debruijn_register_compile.cljc:193-197` likewise), so sharing
  never reaches bytes. `a-shared-variable-lowers-independently-per-occurrence`
  (`debruijn_linearize_test.cljc:335`, `debruijn_register_compile_test.cljc:429-447`)
  pins this.

**PROPOSED** record ids. `rid(p) = (- -1 (* 3 p))`, `op-rid(p) = (- -2 (* 3 p))`,
`key-rid(p) = (- -3 (* 3 p))`: negative, pairwise distinct, a pure
function of position, never equal to any pc. `resolve` mints `-1, -2, …`
in first-visit order (`debruijn_resolve.cljc:326-327`); ids reach no
emitted byte in either lowerer (the images carry none; the diagnostic
side tables map rids through `:source` and `:params`, never store them),
so the choice is free, and a position-derived id needs no counter.

### 1.5 The binder side table and the `:source` table

**DECIDED.** `:params` is `{closure-rid params}` for every `:closure`
record, the exact vector from A; this is what `pc-side-table`
(`debruijn_linearize.cljc:192-209`) and `register-side-table`
(`debruijn_register_compile.cljc:323-338`) read to build their
`{pc {:kind :closure :params …}}` entries, and what both `lift`s trust
after their round-trip check (`debruijn_linearize.cljc:398-422`,
`debruijn_register_compile.cljc:819-840`). `:source` is `{rid pc}`,
**total over every record** including the two synthesized definition
records (`validate-resolved`'s `:source-total` rule, `:491-492`). A pc
is the only source A has; the lowerers' `:source` columns become pcs of
A instead of AST entity ids, a diagnostic difference outside every image
and hash (§4.1 names it as a non-byte).

### 1.6 Validation and the equivalence it rests on

**DECIDED.** `resolve-vector` runs `resolve/validate-resolved tuples
source` before returning and throws on a defect, as `resolve` does
(`debruijn_resolve.cljc:335-338`); both lowerers run it again at entry
unconditionally (`debruijn_linearize.cljc:223-225`,
`debruijn_register_compile.cljc:368-370`). For a well-formed A every
check passes: refs resolve (every child is a node), operands are present,
no record is both bound and free, the scope walk's chain is the owner
tree of §1.2, and `:source` is total.

**The equivalence claim** this design rests on, stated once so §2-§4 can
cite it:

> For every AST `P` the projection accepts, let `v = (:vector (project P))`
> and `D = (vm/ast->datoms P)`. Then `(resolve-vector v)` and
> `(resolve/resolve D)` are **lowerer-equivalent**: over every attribute
> either lowerer reads, `get-attr` answers the same value at corresponding
> records, and corresponding records are reached in the same order by the
> same walk.

Why it holds: `project`'s `flatten-tree` (`linearize.cljc:55-170`) and
`resolve`'s `emit-node!` (`debruijn_resolve.cljc:127-265`) walk one tree in
one order (operator, operands, test/consequent/alternate, target/value,
source, value operand; bodies on encounter); the parse recovers that tree
exactly (item 13, `code.cljc:804-824`: re-projecting the parse reproduces
the whole vector). The three places the inputs could differ do not:

1. **Saturation.** `ast->datoms` already materialises the gensym prefix
   (`(or (:prefix node) "id")`, `vm.cljc:841`) and the stream buffer
   (`(or (:buffer node) 1024)`, `:850`), and `project`'s `defaults`
   (`linearize.cljc:46-52`) use `"id"` and `vm/default-stream-capacity`,
   which is `1024` (`vm.cljc:552-558`); a missing `:operands` is `[]` on
   both sides (`:826`, `(mapv convert (:operands node))`;
   `linearize.cljc:51`). The golden
   `:stream-make-default` is `[[:stream-make 0 1024] [:halt 0]]`
   (`corpus.cljc:314-317`) and the stack corpus's `:default-buffer` lowers
   through the same `1024` (`debruijn_linearize_test.cljc:110`). The
   `(or … 0)` fallbacks in `lower-register`
   (`debruijn_register_compile.cljc:234, 237`) are never reached from
   either producer.
2. **Tail flags.** Both sides carry `:yin/tail?` only when true (§1.4);
   both lowerers read `boolean`.
3. **Definitions.** §1.4's synthesized records are get-attr-identical to
   `resolve`'s operator and key records for a legal definition
   (`debruijn_resolve.cljc:177-191`: the operator is visited as a
   `:variable` with `:yin.resolved/free yin/def`, the key as a `:literal`).

What differs and is **not** read by a lowerer: record ids; `t`/`m`;
`:yin/macro?`; record sharing; `:source` values. §4 turns this claim into
a per-program gate rather than trusting the argument.

---

## 2. A → H

### 2.1 The function

**PROPOSED** name, **DECIDED** composition (frozen §8.3):

```clojure
(stack-image v)   ; => {:image v-stack, :side-table st}
= (linearize/lower-stack (resolve-vector v))
```

`lower-stack` (`debruijn_linearize.cljc:212-235`) is called **unchanged**.
There is no adapter beyond `resolve-vector`: its output *is* "the exact
input `lower-stack` consumes" (`{:keys [tuples source params]}`,
`:222`). The frozen §8.3's steps 2-3 (parse, adapt to resolved tuples
plus side table, one record per occurrence, saturated defaults,
`:yin/tail?` from `tail?`, arities from `:closure`) are §1.4 line by
line.

### 2.2 Body discovery order and absolute pcs

**DECIDED, correcting a frozen sentence (§8 F9).** The adapter does
**not** order bodies and does not compute pcs. `lower-stack`'s
`flatten-resolved` keeps its own FIFO body queue, appended when a
`:lambda` record is emitted and drained in order after the main sequence
(`debruijn_linearize.cljc:98, 115-117, 173-178`); `lower-node` places
labels and `resolve-labels` turns them into absolute pcs
(`:182-189`). That queue discipline is the same as `project`'s
(`linearize.cljc:103-105, 155-161`) and the same as the frozen §3.3 item
1, so H's body order equals A's body order; but it is *the emitter's*
order, derived from the tree, not something the adapter must preserve or
hand over. The frozen §8.3 step 3's "bodies in the §3.3 queue order so
that discovery order is preserved" asks the adapter for something it
cannot supply and does not need to: the resolved tuples are a tree, not a
sequence.

### 2.3 Are H's bytes today's by construction?

**Yes, DECIDED by §1.6 plus one fact about `lower-stack`.** `lower-stack`
reads exactly: `:yin/type`, `:yin/value`, `:yin.resolved/free`,
`:yin.resolved/depth`, `:yin.resolved/position`, `:yin.resolved/arity`,
`:yin/body`, `:yin/operands`, `:yin/operator`, `:yin/tail?`, `:yin/test`,
`:yin/consequent`, `:yin/alternate`, `:yin/op`, `:yin/buffer`,
`:yin/target`, `:yin/val-node`, `:yin/source`, `:yin/prefix`, `:yin/key`,
`:yin/parked-id` (`debruijn_linearize.cljc:103-168`), every one of which
§1.6 shows equal across the two producers. The emitted image, its
`encode-image` bytes (`debruijn_code.cljc:533-539`) and `image-hash`
(`:551-567`) are functions of that image alone. So for every corpus
program `(:image (stack-image (:vector (project P))))` equals
`(:image (dl/adapt (vm/ast->datoms P)))` as a vector, as bytes and as H.
§4 gates it; §7 slice 5a item 6 is the test. The frozen §8.4's "H is
expected to hold" is confirmed and strengthened to "holds by
construction".

---

## 3. A → R: the key decision (Finding F8)

### 3.1 What R1's allocator actually is

**DECIDED, from the code.** `lower-register` (`debruijn_register_compile.cljc:358-428`)
lowers a body by `lower-node!` (`:152-306`) with a parent-supplied
`target-reg`. The discipline, read off the code:

1. The body's **result temporary is allocated first**, before any node is
   lowered (`main-temp`, `:380-383`; each lambda body's `t`, `:388`), and
   the body ends `[:return r]` / `[:halt r]` naming it.
2. For an application, the **operator's temporary is allocated, then the
   operator is lowered into it**; each operand's temporary is allocated
   immediately before that operand is lowered (`:188-197`). A
   conditional's test temporary is allocated, the test lowered,
   `:branch-false` emitted, and the test temporary **freed before either
   arm** (`:212-217`); both arms lower into the parent-supplied target.
3. `allocate-temp!` takes the **lowest freed index**, else the next
   never-used one (`:99-111`); `free-temp!` returns an index to the free
   set (`:114-116`); a parent frees its children's temporaries **after
   emitting its own instruction** (`:207-209`, `:248-250`, `:287-288`).
4. `live` operands are a separate backward pass over the finished body
   (`fill-live`, `:341-355`, calling `rcode/body-liveness`,
   `debruijn_register_code.cljc:342-385`).

Put against the frozen §3.3 minting rule, this is a precise statement:
**R1 allocates in A's pre-order minting order with a free list.** The
destination of an expression is assigned when the expression is
*entered* (before its children), which is exactly when §3.3 item 2 mints
its virtual register; the two differ only in that R1 reuses an index once
its consumer has emitted, and A never reuses. A's worked example
(`corpus.cljc:133-141`) shows it: virtual ids `f=1 g=3 x=4 (g x)=2 y=5
result=0`; R1's slots for the same tree are `f=1 g=3 x=4 (g x)=2 y=3
result=0`, identical except that `y` takes the slot `g` freed.
`fixture-a` (`debruijn_register_compile_test.cljc:608-620`) documents the
same fact in R1's own words ("reg0 is reserved for the program's own
return value before anything else is lowered").

### 3.2 Route (a): the frozen §8.2 interval allocator

**What is built.** A new namespace: the §8.2 interval construction over
`analysis/live` and the syntactic definitions (`start(v)` = first
syntactic definition, `end(v)` = last live point or definition), the
definition-less-id rule with the structural slot `Tₖ`, the pc-order scan
with `end < p` expiry and lowest-free assignment, the parameter bank
offset, the count rule, then `fill-live` over the result, then the body
descriptor. Roughly the size of `lower-register` itself, plus the
structural-slot cases, which have no counterpart in any existing code.

**What must be verified.** `rcode/register-image-defect` passes
(`debruijn_register_code.cljc:698-705`, all twelve rules including
`live-exact`); the register kernel runs every corpus program to the
pinned values; the lexical-address law (§5). None of these can be
inherited: every golden R in `debruijn_register_compile_test.cljc`
(twelve 64-hex values, `:449-620`) and every pinned register image is
regenerated.

**Where the bytes differ from today's R, exactly.** The two allocators
differ in *when* a destination is assigned. R1 assigns an expression's
destination at entry (pre-order), before any child; §8.2 assigns an
interval at its `start`, the pc of its **first syntactic definition**,
which for a call is the `:call` instruction itself, *after* every child
has been assigned. Consequently:

| Program | R1 (today) | §8.2 interval scan |
|---|---|---|
| `(+ 1 2)` (fixture-a) | `[:load-free 1 +] [:const 2 1] [:const 3 2] [:call 0 1 [2 3] false []] [:halt 0]`, 4 registers | `[:load-free 0 +] [:const 1 1] [:const 2 2] [:call 3 0 [1 2] false []] [:halt 3]`, 4 registers |
| `(f (g x) y)` (`:worked-example`) | `f=1 g=3 x=4 (g x)=2 y=3 result=0`, 5 registers | intervals `v1:[0,5] v3:[1,3] v4:[2,3] v2:[3,5] v5:[4,5] v0:[5,6]`; scan: `f=T0 g=T1 x=T2 (g x)=T3`, at pc 4 `v3,v4` expire and `y=T1`, at pc 5 `result=T2`; 4 registers |

Derivation of the second row under §8.2's own rules: at pc 3 nothing has
`end < 3` so `v2` takes `T3`; at pc 4 `v3` and `v4` (`end 3`) expire,
`v5` takes the lowest free slot `T1`; at pc 5 nothing expires (`v2`,
`v5` end at 5) and `v0` takes `T2`. Every operand of every instruction
differs from R1's, and the register count differs. The rule is general:
**under §8.2 a call's destination is numbered after its operands; under
R1 before them**, so the two agree only on bodies whose expression is a
single atom. Over the B0 corpus (`parity_test.cljc:39-115`) that is the
six literal rows, `"closure value"` and `"stream make"`; the other
eighteen differ. Over the register corpus (`corpus.cljc:54-118`) only
`:literal`, `:variable` and `:stream-make-default` agree.

**Consequence for `"r2"` under frozen §6.** The allocation rule is one of
the five things §6 names as the normative contract ("descriptors,
validators, **allocation rules**, execution and live-operand rules");
route (a) changes it, and changes the bytes on almost every program.
`"r2"` is re-versioned to `"r3"`: `rcode/contract-version` 4 → 5
(`debruijn_register_code.cljc:52-59`), `vm/register-contract`
(`vm.cljc:277-279`), the descriptor hash golden
(`golden-descriptor-hash-test`, `debruijn_register_compile_test.cljc:449`),
`register-lowering-profile` (`linker.cljc:925-932`), every manifest's
`:yin.module/contracts` entry and `:yin.module/index` R key
(`publish.cljc:170-189`), and the register design's §4.1-4.3 and §4.6
text. The C4 track's published `py` manifest carries R in its index
(`publish.cljc:189`), so its pinned manifest address
(`linked_prelude_test.cljc:229-234`) moves for a second reason beyond the
one cutover already imposes.

**Consequence for "A is the canonical projection".** Route (a) makes the
register-shaped vector a derivation *source* in the literal sense: the
allocator reads virtual ids and `L(pc)` from A. That is the frozen §8.2's
stated ambition. It buys nothing observable: the kernel executes physical
registers; the live operands come from `body-liveness` either way; and
the virtual numbering A carries is itself the image of the tree (§3.1 of
the frozen design: "a body is the image of one expression tree").

**Cost.** A new allocator with a structural-slot rule that exists for no
other reason than the interval formulation (R1 handles a wholly terminal
body by the pre-allocated result temporary: `[:const 1 "ok"] [:resume :p1 1]
[:halt 0]` with two registers, `debruijn_register_compile_test.cljc:584-595`,
and `resume-terminates-control-flow-test`, `:903`); twelve regenerated R
goldens and every register image fixture; an `"r3"` contract; a register
design §4 rewrite; a moved C4 golden; and a reviewer who must check a
specification (§8.2's reservation-versus-liveness, structural slot in
unreachable `live` operands) against code that does not yet exist.

### 3.3 Route (b): the adapter route

**What is built.**

```clojure
(register-image v)   ; => {:image {:bodies … :instructions …}, :side-table st}
= (register-compile/lower-register (resolve-vector v))
```

Nothing else. `lower-register` is called unchanged on §1's output.

**What must be verified.** The equality gate of §4: for every corpus
program the image map, its `encode-register-image` bytes
(`debruijn_register_code.cljc:248-254`) and `register-hash` (`:261-269`)
equal `rc/adapt`'s over `ast->datoms` of the same program; the validator
passes (it does today); the lexical-address law (§5); the register kernel
runs the derived image to the pinned values (§7 slice 5b).

**Where the bytes can differ from today's R.** Nowhere, by §1.6:
`lower-register` reads the same attribute set `lower-stack` reads
(§2.3's list; `debruijn_register_compile.cljc:152-306`), `resolve-vector`
answers them identically, and the allocator, `fill-live`, body
descriptors and encoder are the same functions over the same input. The
derivation is a deterministic function of A: the parse is the inverse of
the projection on well-formed vectors (item 13), `resolve-name` is pure,
and `lower-register` is pinned deterministic
(`lowering-is-deterministic-across-repeated-runs`,
`debruijn_register_compile_test.cljc:246`). §4 proves it per program
rather than asserting it.

**Consequence for `"r2"` and `"b2"`.** Retained. Bytes unchanged on the
corpus; descriptors, validators, allocation rules, execution and
live-operand rules untouched as text and as code. What changes is the
*producer* of the lowerers' input: `resolve-vector` over A instead of
`resolve` over named datoms. The register design's §4.1 sentence "The
lowerer accepts only resolved tuples from the section 2.1 resolver"
(`yin.vm.debruijn.register.md:363-364`) reads, after this phase, "from a
resolver producing §2.1 tuples: `yin.vm.debruijn-resolve/resolve` over
named datoms until cutover, `resolve-vector` over A after it"; the shape
contract and `validate-resolved` are unchanged. The de Bruijn designs'
amendments of 2026-10-10 already say this is the intended reading: "the
input to `lower-register` becomes A, with name resolution a shared
function both lowerers call whose output is a stage value with no
identity" (`yin.vm.debruijn.register.md:26-29`), and "R1's lowering walk
changes input and is not a plug-in allocator over a vector"
(`:42-44`), which is precisely why route (b) hands it a tree rather than
plugging it over the vector.

**Consequence for "A is the canonical projection" and for A as a
derivation source.** Honestly: under (b) the register lowering consults
A's virtual register ids only to *parse* (`code/uses` links children,
`code.cljc:81-94`); the physical allocation is R1's over the tree. A is
the source of the trees, and the trees are the source of R. This is the
same relationship H already has to A under the frozen §8.3, and it is a
relationship *through a bijection*: on well-formed vectors
`project ∘ parse = id` (item 13) and `parse ∘ project = id` up to the
AST facts A does not carry (`:macro?`, shared `:eid`s), so R is a
function of A. "A is the only code identity" (owner) is a statement about
names and admission, not about which fields of A an allocator reads;
"derive, don't persist" is satisfied exactly as before (nothing is
persisted; everything is recomputed from A). What (b) gives up is the
frozen §8.2's picture of the virtual-register layer *feeding* the native
allocator. What it gains is that the native formats are **invariant
under the semantic VM's rebuild**: the stack-shaped `"v3"` vector and the
register-shaped `"v4"` vector encode the same trees, so H and R do not
move at cutover, and a native kernel's bytes do not depend on which
canonical shape the semantic VM happens to execute. That is the right
dependency direction for a format that foreign kernels will execute
(`yin.vm.debruijn.targets.md`).

**Cost.** One function of about 150 lines (`resolve-vector`), two
one-line compositions, and a test namespace. No new allocator, no new
goldens, no contract move, no register design rewrite, no C4 movement.
The resolved-tuple shape stays alive as an internal stage with two
producers until cutover; §7 keeps `resolve` over named datoms as the
oracle through phase 7 and §9 item 3 asks the reviewer what to do with it
at phase 8.

### 3.4 Recommendation

**PROPOSED, as Finding F8: route (b).** The frozen §8.4 asked for the
decision to be "made from the evidence together with §6's contract
test". The evidence is §3.2: the §8.2 allocator cannot reproduce R1's
bytes on any program with a call, so under §6 it re-versions `"r2"`; the
alternative reproduces every byte with no new allocator. A re-version is
honest and permitted, but §6 permits it when a contract *has* changed;
nothing in phase 5's purpose requires the register allocation rule to
change, and the frozen design's own invariant list ("no assumed graphs",
§12) is served equally by both routes. The only argument for (a) is the
aesthetic one that A's virtual registers should be read by the allocator;
§3.3 answers it. §8 holds the amendment text for §6, §8.2, §8.4, §9 and
§10 phase 5.

---

## 4. The byte-identity and contract decision procedure

**PROPOSED** as a mechanical procedure; **DECIDED** in what it compares
(frozen §8.4: "the comparison is of the actual vectors and canonical
bytes, with the checksums as a summary, never checksums alone"; §6:
"bytes AND normative contract").

### 4.1 Inputs

For each contract `c ∈ {"b2", "r2"}` and each program `P` of the corpus
`C` (§4.2):

```
old(P) = (:image (lowerer_c (resolve/resolve (vm/ast->datoms P))))
new(P) = (:image (lowerer_c (resolve-vector (:vector (sr-linearize/project P)))))
```

with `lowerer_b2 = linearize/lower-stack`, `lowerer_r2 = register-compile/lower-register`.

Three comparisons, all asserted, in this order:

1. **Structure.** `(= old new)`: the stack vector tuple by tuple; the
   register `{:bodies :instructions}` map including every body
   descriptor's `:locals :registers :start :end`.
2. **Bytes.** `(= (dc/encode-image old) (dc/encode-image new))`
   (`debruijn_code.cljc:533-539`);
   `(= (rcode/encode-register-image old) (rcode/encode-register-image new))`
   (`debruijn_register_code.cljc:248-254`).
3. **Checksum**, as the summary only: `(= (dc/image-hash old) (dc/image-hash new))`,
   `(= (rcode/register-hash old) (rcode/register-hash new))`.

**Not compared**: the `:side-table` (`:source` holds AST entity ids on
one side and pcs on the other, §1.5; `:params` must still be equal and is
asserted separately).

### 4.2 The corpus `C`

Named, so the implementer adds nothing silently:

| Source | Rows | Why |
|---|---|---|
| `yin.vm.parity-test/corpus` (`parity_test.cljc:39-115`) | 26 | the B0 corpus both de Bruijn designs pin their laws over |
| `yin.vm.semantic-register.corpus/programs` (`corpus.cljc:54-118`) | 32 | every §3.1 production, including the terminal shapes R1 has goldens for |
| `yin.vm.debruijn-linearize-test`'s `corpus` (`debruijn_linearize_test.cljc:83-124`), respelled | 14 | every named mnemonic, `:default-gensym`, `:default-buffer`, `:duplicate-param`, `:nested-closure` |
| `yin.vm.debruijn-register-contract-test/address-law-extra-fixtures` (`debruijn_register_contract_test.cljc:311-320`) | 4 | the shared-occurrence fixtures: the one place `resolve` shares a record and A does not |
| `yin.vm.debruijn-register-compile-test`'s live fixtures A-E (`:608-766`) and `r2-lift-programs` (`:998-1011`), respelled | 5 + 12 | the hand-derived `live` sets and the R2 nodes |
| the C4 module ASTs: `prelude/module-uast`, `(:ast (safepoint/module-spec …))`, and the six guest packets' `(:ast (lower/module-spec …))` of `import_test.cljc:342-363` | 8 | the production modules the linker publishes; read-only use (§6.6) |

The shared-occurrence rows are the sharpest: `resolve` memoises
`[eid stack]` and shares a record where A has two; equality there proves
§1.4's "lowerers emit per reference".

### 4.3 What counts as a normative-contract change

A contract `c` has changed when **any** of the following files differ
from `0ff25b58` in a way that alters the value of the named thing:

| Normative element (frozen §6) | Where it is | Mechanical check |
|---|---|---|
| descriptor | `dc/descriptor` (`debruijn_code.cljc:117-155`), `rcode/descriptor` (`debruijn_register_code.cljc:190-204`) | `dc/descriptor-hash` and `rcode/descriptor-hash` equal their pinned goldens (`golden-descriptor-hash-test`, `debruijn_register_compile_test.cljc:449`; the stack descriptor's hash is folded into every pinned H) |
| validator rules and defect vocabulary | `dc/image-defect` (`:879`), `rcode/register-image-defect` (`:698-705`) and their rule vectors | the existing refusal tests pass unchanged; `git diff --stat` on the two files is empty |
| allocation rules | `lower-node!`, `allocate-temp!`, `free-temp!` (`debruijn_register_compile.cljc:88-306`) | `git diff` empty on the file |
| execution rules | `src/cljc/yin/vm/debruijn/stack.cljc`, `…/register.cljc` | `git diff` empty |
| live-operand rules | `rcode/body-liveness` (`:342-385`), the four live rules (`:588-649`) | `git diff` empty; `lowered-live-matches-freshly-recomputed-body-liveness-exactly` (`debruijn_register_compile_test.cljc:817`) passes |
| the resolution rule | `debruijn/resolve-name` (`debruijn.cljc:617-637`) | `git diff` empty; §1.3's pinned tests pass |

**Not** a contract change: which function produces the lowerers' input
(`resolve` or `resolve-vector`), provided `validate-resolved` and the
record shape (`yin.vm.debruijn.stack.md:306-387`) are unchanged; the
`:source` values of the diagnostic side tables; test-file additions.

### 4.4 The decision

```
for c in [b2 r2]:
  bytes-held   := every P in C passes 4.1 (1)-(3)
  contract-held := every row of 4.3 passes
  if bytes-held and contract-held  → RETAIN c
  else                              → RE-VERSION c (b3 / r3): bump the contract
                                      constant, the descriptor version, regenerate
                                      goldens, amend the design, as frozen §6 states
```

Under route (b) the expected outcome is RETAIN for both, and the
procedure is a **gate, not a choice**: a failing row in 4.1 is a defect in
`resolve-vector` (equality is by construction, §1.6), to be fixed, never
a reason to re-version. Under route (a) the expected outcome is
RE-VERSION `"r2"` (§3.2) and RETAIN `"b2"`.

**Who records it where.** The slice-5b implementer writes the outcome
into this document's §4.5 table (the one place this design is amended by
an implementation) with the test names, the row counts asserted, the
three hosts, and the commit; the orchestrator logs it in the migration
log against frozen §10 phase 5 ("the §8.4 byte-and-contract decision
recorded with its evidence"); the Architect reviewer (non-Claude) signs
the row. No stamp or descriptor constant moves without that row.

### 4.5 Decision record

| Contract | Bytes held on `C` | Contract held | Outcome | Evidence (tests, hosts, commit) | Recorded by |
|---|---|---|---|---|---|
| `"b2"` | *(to be filled by 5a)* | | | | |
| `"r2"` | *(to be filled by 5b)* | | | | |

---

## 5. The lexical-address law, made exact

### 5.1 Definition of `addresses`

**DECIDED** (register design §2.2, `yin.vm.debruijn.register.md:294-304`;
R0's helpers `debruijn_register_contract_test.cljc:190-268`; R1's
`register-image-var-addresses`, `debruijn_register_compile_test.cljc:363-371`).
`addresses(X)` is the **sequence** (not set) of lexical references, in
emission order, each `[:bound depth position]` or `[:free name]`:

- `addresses(H)`: read off the stack image in pc order: `[:load-bound d q]`
  → `[:bound d q]`, `[:load-free n]` → `[:free n]`
  (`stack-image-var-addresses`, `:243-253`).
- `addresses(R)`: read off `(:instructions image)` in pc order:
  `[:load-bound rd d q]` → `[:bound d q]`, `[:load-free rd n]` → `[:free n]`
  (`register-image-var-addresses`).
- `addresses(resolve-vector(A))`: `resolved-var-addresses`
  (`:190-240`) over the tuples: walk the root, operator before operands,
  test/consequent/alternate, target/value, source, value operand; lambdas
  enqueue their bodies, drained FIFO after the main walk; a `:variable`
  record contributes its resolution.
- `addresses(A)`, **new and the law's anchor**: walk `v` in pc order; for
  `[:var rd name]` at pc `p`, `(resolve-name (chain (body-of p)) name)` with
  §1.2's chain; `{:bound b}` → `(into [:bound] b)`, `{:free n}` → `[:free n]`.
  pc order is emission order because A lays the main sequence first and
  bodies in FIFO discovery order (frozen §3.3 item 1), the same order
  `lower-stack` and `lower-register` lay theirs (§2.2;
  `debruijn_register_compile.cljc:384-392`).

The law:

```
addresses(A) = addresses(resolve-vector(A)) = addresses(H(A)) = addresses(R(A))
             = addresses(resolve(ast->datoms P))        for every P with project(P) = A
```

The last equality is the bridge to today's R0/R1 laws
(`address-law-holds-over-the-b0-parity-corpus`,
`debruijn_register_contract_test.cljc:324-329`;
`address-law-holds-three-way-over-the-b0-parity-corpus`,
`debruijn_register_compile_test.cljc:376-381`), and it is what makes the
frozen §10 phase 5 clause "`addresses(H) = addresses(R) = addresses(resolve(A))`"
exact: `resolve(A)` there is `resolve-vector`.

### 5.2 Corpus and test shape

**PROPOSED.** Corpus: §4.2's `C` (the R0/R1 laws run over B0 and the
extra fixtures; this law adds the register corpus, the stack corpus and
the C4 modules). Test `yin.vm.semantic-register.derive-test/lexical-address-law`:

```clojure
(doseq [[name ast] C]
  (testing name
    (let [v    (:vector (sr-linearize/project ast))
          want (derive/addresses v)]
      (is (= want (resolved-addresses (derive/resolve-vector v))))
      (is (= want (stack-addresses (:image (derive/stack-image v)))))          ; 5a
      (is (= want (register-addresses (:image (derive/register-image v)))))    ; 5b
      (is (= want (r0/resolved-addresses-of ast)) "today's resolver agrees"))))
```

plus one non-vacuity assertion: at least one program of `C` yields a
`[:bound 1 _]` (`:nested-closure`, `:nested-lambdas`) and at least one a
`[:bound 0 1]` (`:duplicate-param`), so the law is not satisfied by empty
sequences. `r0/resolved-addresses-of` is public for exactly this reuse
(`debruijn_register_contract_test.cljc:256-262`, "never via var-quote
reflection, which does not port to ClojureDart").

---

## 6. The scanners (UCF §7.6.1) and the linker records

### 6.1 Where the dependency-closure walk lives today

**DECIDED, located.** UCF §7.6.1 (`yin.vm.universal-continuation-format.md:1340-1413`)
specifies the fixed point; "the scanners" are its *code side*: the facts
read off instructions. Four implementations exist, all over the
**stack-shaped** `"v3"` vector or the de Bruijn images:

| Fact | Semantic `"v3"` vector | Stack image | Register image | Tree (the oracle) |
|---|---|---|---|---|
| free `:var` names, as occurrences `{:name :at :in-body?}` | `linker/semantic-free-occurrences` (`linker.cljc:224-261`), scope by `closure-spans` heuristics (`:128-168`); `completion/segment-free-names` by Datalog over `$code` and `$scope` (`completion.cljc:173-222`) | `stack-free-occurrences` (`:470-484`), `stack-free-names` (`:96-102`) | `register-free-occurrences` (`:572-582`), `register-free-names` (`:105-112`) | `vm/free-names` (`vm.cljc:1701-1720`), `tree-free-name-occurrences` (`linker.cljc:377`) |
| definitions `{:name :at :conditional?}` | `vector-definition-occurrences` (`:497-513`, key at slot 1) | same function | `register-definition-occurrences` (`:585-599`, key at slot 2) | `tree-definition-occurrences` (`:425`) |
| application sites `{:at}` | `vector-application-sites` (`:516-529`) | same | `register-application-sites` (`:602-611`) | `tree-application-sites` (`:452`) |
| store keys, ffi ops, parked ids, effect kinds | `vm/segment-requirements` (`vm.cljc:1882-1888`) over `segment-extraction-queries` (`:1833-1853`) and `footprint-table` `"v3"` `:mnemonics` (`:1733-1790`) | — | — | `vm/ast-requirements` (`:1869-1879`) over `ast-extraction-queries` (`:1805-1830`) |
| the step-5a join (discharge by dominance) | `linker/undischarged` (`linker.cljc:1090-1160`), format-neutral over the three record kinds | | | |
| UCF lift census (free names of carried code; closure markers) | `completion/code-facts` (`completion.cljc:230-275`), `apply-code-facts` (`:525-541`) | `handoff/free-names-v2` (`handoff.cljc:1235-1253`, `:load-free` at slot 1) | same (`:load-free` at slot 2) | `walker-free-names` (`:1208-1232`) |

The semantic `"v3"` scanners read **tuple positions of the stack table**:
`[$code _ _ :store-get ?key]` binds the key at the first operand
(`vm.cljc:1841`), `[:var name]` at slot 1
(`linker.cljc:252-256`), `[:closure params body]` at slots 1-2
(`:238-241`; `completion.cljc:187-188`), `:define` key at slot 1
(`binding-key t 1`, `linker.cljc:508`), `:resume pid` at slot 1
(`vm.cljc:1847`). The §2.3 table puts `rd` first on every value-producing
instruction, so **every one of these positions is off by one** on a
`"v4"` vector, and `:push` (in the `"v3"` footprint table) does not
exist. This is why the frozen §10 phase 5 says "reimplemented over the
new table".

### 6.2 What the register-table version computes

**PROPOSED** namespace `yin.vm.semantic-register.scan`, file
`src/cljc/yin/vm/semantic_register/scan.cljc`, requiring
`yin.vm.semantic-register.code`, `yin.vm` (`reserved-name?`,
`definition-operator`) and `dao.space.query`. Pure functions of a
well-formed `"v4"` vector:

| Function | Returns | Positions (§2.3 table, `code/operand-table`, `code.cljc:34-59`) |
|---|---|---|
| `free-occurrences v` | `[{:name sym :at pc :in-body? b} …]` in pc order | `[:var rd name]`, name at slot 2; bound iff `(resolve-name (chain (body-of pc)) name)` is `:bound` (§1.2-1.3; the scope is the validated owner tree, not a span heuristic); `:in-body?` = body index > 0 |
| `free-names v` | the set of their names | |
| `definition-occurrences v` | `[{:name key :at pc :conditional? b} …]` | `[:store-put rd key value]` key at slot 2, `[:define rd name rs]` name at slot 2; `:conditional?` when pc is in a body > 0 or strictly inside a `:jump`/`:branch-false` target range (`[:jump t]` slot 1, `[:branch-false c t]` slot 2), as `register-conditional-ranges` computes it (`linker.cljc:553-569`) |
| `application-sites v` | `[{:at pc} …]` | `:call` and `:ffi-call` pcs |
| `requirements v` | `{:store-keys :ffi-ops :parked-ids :effects}` | Datalog over `$code` rows `[A pc mnemonic & ops]` (`code/project-segment-qualified` is generic, `code.cljc:446-467`, and applies unchanged): `[$code _ _ :store-get _ ?key]`, `[$code _ _ :store-put _ ?key _]`, `[$code _ _ :define _ ?key _]`, `[$code _ _ :ffi-call _ ?op _]`, `[$code _ _ :resume ?pid _]`, effect raisers `#{:stream-make :stream-put :stream-cursor :stream-next :stream-close :ffi-call}` normalised through `footprint-mnemonics` |
| `footprint-mnemonics` | the `"v4"` mnemonic → effect-set map | `(dissoc (get-in vm/footprint-table [vm/semantic-contract :mnemonics]) :push)`, asserted equal by a test so the two tables cannot drift; it moves into `vm/footprint-table` under `"v4"` at cutover |
| `segment-rows v` | `(code/project-segment-qualified v)` | |

**DECIDED** by UCF §7.6.1 (`:1385-1398`) and Rule R: `yin/def` is never a
free name (item 11 guarantees no `:var` names it), a `:define` key is a
store key the segment writes, like a `:store-put` key, and `:store-get`
keys are in the footprint ("which the first draft missed entirely").
**DECIDED** by `linker/undischarged`'s contract (`linker.cljc:1090-1160`):
the three record kinds and their keys are unchanged, so the step-5a join
runs the new scanners through the same function.

**No degradation path.** The `"v3"` scanners degrade to "every `:var`
free" when the span heuristic fails (`linker.cljc:162-176`, `:204-221`),
because a `"v3"` vector's layout was not validated. A `"v4"` vector
reaching a scanner has passed item 6, so its owner tree is exact; the
scanners throw on a malformed vector, which `linker/scanned` already
turns into the conservative degradation (`:1081-1087`).

### 6.3 The facts compared, and how agreement is checked

**PROPOSED** gate, **DECIDED** facts (frozen §10 phase 5 names "free
names, store footprint, discharged bodies"; §8 F11 corrects the third).
For a program `P` with `v3 = (:vector (linearize/lower-rows (vm/ast->semantic-bytecode P)))`
(the `"v3"` vector, `publish.cljc:164`) and `v4 = (:vector (sr-linearize/project P))`:

| Fact | Old (`"v3"`) | New (`"v4"`) | Oracle (tree) | Compared as |
|---|---|---|---|---|
| free names | `(set (map :name (linker/semantic-free-occurrences v3)))`, `(completion/segment-free-names v3)` | `(scan/free-names v4)` | `(vm/free-names db occ root)` | set equality, three-way |
| free-name occurrences | `semantic-free-occurrences v3` | `scan/free-occurrences v4` | `tree-free-name-occurrences` | **names and `:in-body?` flags in order**, `:at` dropped: pcs differ between the two layouts (a `"v3"` `(f x)` is four pcs with pushes, a `"v4"` one is three) |
| definitions | `vector-definition-occurrences v3` | `scan/definition-occurrences v4` | `tree-definition-occurrences` | names and `:conditional?` flags in order |
| application sites | `(count (vector-application-sites v3))` | `(count (scan/application-sites v4))` | `tree-application-sites` | counts (a `:call` is one site in both tables) |
| store footprint, ffi ops, parked ids, effects | `(vm/segment-requirements (query/relation (code/project-segment-qualified v3)))` | `(scan/requirements v4)` | `(vm/ast-requirements (query/relation rows))` | map equality, three-way |
| the step-5a join | `(:obligations (linker/undischarged old-occ old-defs old-sites))` | same over the new records | | the retained obligation **names in order** |

Three corpora, three tests in `yin.vm.semantic-register.scan-test`:

1. `scanners-agree-on-b0`: the 26 rows of `parity-test/corpus`.
2. `scanners-agree-on-the-register-corpus`: the 32 rows of
   `corpus/programs` (asserting the count, as the phase-4 lanes do).
3. `scanners-agree-on-the-c4-modules` (`^:slow`, `dao.test-slow/guard`):
   **by name**, over the module ASTs the C4 track publishes, read-only:
   `prelude/module-uast` (`py`, `linked_prelude_test.cljc:167-187` uses
   it), the `pysp` spec `(:ast (safepoint/module-spec (h/registry) a))`
   (`linked_harness.cljc:66-73`), and the six guest packets of
   `import_test.cljc:342-363` through `lower/module-spec` with a stand-in
   `:py-address` and `:deps` as `module-spec-test` does
   (`import_test.cljc:298-310`): `k m t w e n`. The test requires
   `yang.python.antlr.prelude`, `.safepoint`, `.lower`,
   `.import-programs` and `.linked-harness` (for `registry` and
   `integer-limits`) and **edits none of them**; it calls no linked run
   and publishes nothing. For every module the free-name set must also
   equal the keys of the spec's `:primitives` declaration minus the
   requirement-qualified names, which is what
   `module-spec-declares-every-free-name-test` asserts for `py` today
   (`linked_prelude_test.cljc:167-187`): the new scanner must find exactly
   what the declaration covers.

The tree oracle is the permanent one: `vm/free-names` and
`ast-requirements` are AST-level and survive cutover.

### 6.4 "Old scanner" after cutover: pinning goldens

**PROPOSED.** Phase 8 deletes `yin.vm.linearize`, `yin.vm.code`'s stack
table and the `"v3"` scanners with them (frozen §9 "Rewrite"). Before
that, slice 5c writes **one golden fixture**
`test/yin/vm/semantic_register/scanner_goldens.cljc`: for every program
of corpora 1 and 2 and every C4 module of corpus 3, by name, the map
`{:free-names #{…} :occurrences [[name in-body?] …] :definitions [[name conditional?] …]
:application-count n :requirements {…} :retained [name …]}` as the `"v3"`
scanners answer it at `0ff25b58`. The fixture is generated once by a
helper in `scan-test` that prints the map (run manually on the JVM,
pasted as Clojure data, never EDN strings), and `scanners-agree-*` compare
the new scanners against the fixture **and** against the live `"v3"`
scanners while those exist; at phase 8 the live comparison is deleted and
the fixture comparison stays. The tree oracle (§6.3) is the third leg
and needs no pinning.

### 6.5 The production linker format record

**DECIDED** shape (frozen §10 phase 4 as amended by F6; `linker.cljc:655-731`
for the four existing records; `module/IModuleKernel/link-format`,
evaluator design §3.3), **PROPOSED** scanner bindings:

```clojure
(def semantic-register-format
  "The `:yin.semantic-register/code` format record (frozen §10 phase 4,
   interim until phase 8 renames it `:yin.semantic/code` \"v4\"): the
   register-shaped canonical vector stored as the exact payload at its
   own segment address, identity and address one preimage (A)."
  {:format              :yin.semantic-register/code
   :contract            sr-code/contract                 ; "v4"
   :identity-fn         jing/segment-key                 ; = ucf/code-address
   :identity-matches-fn jing/segment-matches?
   :row-defect-fn       sr-code/well-formed?
   :validate-fn         sr-code/well-formed?
   :obligations-fn      scan/free-occurrences
   :definitions-fn      scan/definition-occurrences
   :applications-fn     scan/application-sites
   :parts-fn            (constantly nil)})
```

Added to `linker.cljc` beside `semantic-format` (additive) and to
`yin.repl.link/formats` (`src/cljc/yin/repl/link.cljc:81-89`, additive),
so a serving composition answers a register evaluator's
`{:format :yin.semantic-register/code :hash A}` request; `verify`
(`linker.cljc:1363-1420`) then runs steps 3-5a over it unchanged. The 4b
stub responder's `:obligations (constantly [])` fixture
(evaluator design §5.2) is replaced by this record's obligations once 5c
lands; that edit belongs to the slice that lands second (§7.4).

At phase 8 the record is renamed `semantic-format` with
`:format :yin.semantic/code`, `:contract "v4"`, and the `"v3"` record is
deleted; `derivation-formats` (`linker.cljc:755-758`) and
`publish/code-formats` (`publish.cljc:87-90`) are unchanged in shape.

### 6.6 Derivation records (linker §8.1) under A-only

**DECIDED** today: `publish-closure!` mints three records
`(ledger/derive-record tree-address output)` with `output ∈ {A_v3, H, R}`
and the per-format profile (`publish.cljc:172-176`, `:183-188`;
`ledger/derive-record`, `ledger.cljc:53-65`); `checked-derivation`
requires `(= tree-addr (:yin.ledger/input derivation))`
(`linker.cljc:2258-2262`); under `:verifying`, `relowered` re-lowers the
tree by `adapt` (`:2142-2160`) and compares with the record's output
(`:2217-2228`). The linker §3 amendment says the record for `f` should
"lead from the canonical tree to A and from A to the native image,
recording the checksum as evidence, not identity"
(`yin.vm.linker.md:222-224`), and defers the rewrite of §4-§8 "to a
later revision" (`:249-250`).

**PROPOSED**, in two steps that keep every manifest address stable until
cutover:

1. **Slice 5c (coexistence):** record *shapes* unchanged. `relowered`,
   `relowered-hashes` (`linker.cljc:2440-2449`) and `publish-closure!`'s
   `h-image`/`r-image` (`publish.cljc:165-168`) switch from
   `adapt ∘ ast->datoms` to `derive/stack-image` and
   `derive/register-image` over `(:vector (sr-linearize/project-rows tree))`.
   By §4 this is byte-neutral (same H, same R), so no manifest, index or
   golden moves, and the production path exercises the A-derived
   lowering for three phases before the old resolver is retired.
   `register-lowering-profile` and `stack-lowering-profile` keep their
   `:yin.lower/profile` strings (`linker.cljc:913-932`): the *profile*
   names the lowering contract, which did not change; the record's
   `:yin.ledger/function :yin.vm/lower` is unchanged.
2. **Phase 8 (cutover):** the semantic record becomes
   `tree → A_v4` under a `"v4"` profile (`ledger/lowering-profile`'s
   `:yin.code/contract` becomes `"v4"`; its `:yin.lower/profile` string
   is the owner's naming decision, §9); the two de Bruijn records become
   `A_v4 → H` and `A_v4 → R`: `:yin.ledger/input` = A (a segment address,
   so `record-defect`'s `address?` check holds, `linker.cljc:868-869`),
   `:yin.ledger/output` = the checksum, `:yin.ledger/profile` the
   unchanged `"stack-lowering"` / `"register-lowering"` profile maps, and
   `checked-derivation` checks the de Bruijn record's input against the
   manifest's **semantic derivation output** (A) rather than against the
   tree. This is the one linker change the A-only amendment requires and
   it moves every manifest address, which cutover moves anyway (the
   `:yin.module/contracts` map and the semantic output change). The C4
   track's `manifest-golden` (`linked_prelude_test.cljc:229-234`) moves at
   that point and at no other, for the reasons memory already records for
   that track; it is **not** touched by phase 5.

---

## 7. Slices and gates

Three slices. Verification follows `docs/agents/build-n-test.md`: G0
`clojure -M:test -n <ns>` per iteration (JVM only), G1 `bb test:sub yin.vm`
at the slice checkpoint on three hosts, G2 `bb test:changed` before
landing, G3 `bb test` on master. New namespaces under
`src/cljc/yin/vm/semantic_register/` and `test/yin/vm/semantic_register/`
are inside the `yin.vm` subsystem by prefix; no registration change.
Shadow auto-discovers `*-test` namespaces; the Node log must show
`Testing yin.vm.semantic-register.<ns>` for each new test namespace.

### 7.1 Slice 5a: `resolve-vector`, `addresses`, A → H

**Files to create.**

- `src/cljc/yin/vm/semantic_register/derive.cljc` (ns
  `yin.vm.semantic-register.derive`): `resolve-vector` (§1), `addresses`
  (§5.1), `stack-image` (§2.1); `register-image` is **declared and throws
  `{:reason :not-in-slice-5a}`** until 5b, so the public surface is fixed
  once.
- `test/yin/vm/semantic_register/derive_test.cljc` (ns
  `yin.vm.semantic-register.derive-test`), holding the corpus `C` of
  §4.2 respelled (the stack corpus and the compile fixtures are private
  in their files), the decision-procedure helpers of §4.1, and the
  address helpers of §5.2.

**Untouched:** every existing source file; every existing test. In
particular `debruijn_resolve.cljc`, `debruijn_linearize.cljc`,
`debruijn_register_compile.cljc`, `debruijn_code.cljc`,
`debruijn_register_code.cljc`, both kernels, `linker.cljc`,
`publish.cljc`, the phase-3 namespaces, and the C4 track.

**Acceptance list (5a).** Each item is a named test or a command; the
B0 and register-corpus items assert their row counts (26, 32) so a corpus
change is noticed.

1. `derive-test/resolve-vector-validates`: for every `P ∈ C`,
   `(resolve/validate-resolved (:tuples r) (:source r))` is nil on
   `r = (resolve-vector v)`; `(set (keys (:source r)))` equals the set of
   record ids; `(:params r)` has one entry per `:closure` tuple of `v`
   with that tuple's params vector.
2. `derive-test/record-shapes`: on the goldens `:worked-example`,
   `:define`, `:zero-arity-call`, `:if`, `:streams`, `:resume-body`
   (`corpus.cljc`), the records are exactly §1.4's table (attribute by
   attribute via `vm/index-datoms`' `get-attr`), the definition has its
   two synthesized records with `:yin.resolved/free yin/def` and the
   literal key, the zero-operand call carries `[rid :yin/operands []]`,
   and no record carries `:yin/tail?` unless the tuple's `tail?` is true.
3. `derive-test/resolution-is-resolve-names`: the `:duplicate-param`
   (`[0 1]`) and `:nested-closure` (`[1 1]` for `a`, `[0 0]` for `b`)
   rows of the stack corpus and the `:nested-lambdas` golden resolve to
   the depths and positions `debruijn_test.cljc:363-402` pins; `y` in
   `:free-variable` is `:yin.resolved/free y`.
4. `derive-test/resolve-vector-is-a-stage-value`: the namespace exports no
   function whose name contains `hash`, `address` or `key` other than
   `addresses`; `resolve-vector`'s result round-trips `pr-str`/`read-string`
   (pure data) — the "no identity" clause made testable.
5. `derive-test/stack-lowerer-equivalence`: for every `P ∈ C`, §4.1
   comparisons (1) structure, (2) `encode-image` bytes, (3) `image-hash`
   between `(stack-image v)` and `(dl/adapt (vm/ast->datoms P))`; plus
   `(= (:params old-side) (:params new-side))` on the `:closure` entries
   of the two side tables.
6. `derive-test/b2-goldens-stand`: the pinned H values the repository
   already holds are reproduced from A: the `adapting-twice-is-byte-identical`
   corpus (`debruijn_linearize_test.cljc:174-184`) through `stack-image`,
   and `structural-comparison-differs-only-at-var-and-closure`'s
   invariant (`:187-213`) re-asserted against the `"v3"` named vector.
7. `derive-test/lexical-address-law-stack`: §5.2 with the three legs that
   exist in 5a (`addresses(A)`, `resolve-vector`, `H`, today's
   `resolved-addresses-of`), with the non-vacuity assertion.
8. `derive-test/derived-stack-images-run-on-b3`: every B0 program's
   derived image loads under `dvm/create-vm` with `vm/stack-contract` and
   runs to the pinned `expected` value under `parity-test/normalize`
   (template `corpus-images-load-and-run-on-b3`,
   `debruijn_linearize_test.cljc:215-245`; the effect rows that need a
   bridge or a stream are excluded exactly as that test excludes them).
9. `derive-test/register-image-is-not-in-5a`: `register-image` throws
   `{:reason :not-in-slice-5a}`.
10. The §4.5 row for `"b2"` is filled: bytes held on every program of
    `C`, contract held (§4.3 table: empty diffs, descriptor hashes
    unchanged), outcome RETAIN.
11. `clojure -M:kondo --lint src/cljc/yin/vm/semantic_register/derive.cljc test/yin/vm/semantic_register/derive_test.cljc`
    reports no errors.
12. `bb test:sub yin.vm` green on JVM, Node and Dart; the Node log shows
    `Testing yin.vm.semantic-register.derive-test`.

### 7.2 Slice 5b: A → R

**Files to change.** `derive.cljc`: `register-image` becomes
`(register-compile/lower-register (resolve-vector v))`. `derive_test.cljc`:
the items below. Nothing else.

**Untouched:** `debruijn_register_compile.cljc`,
`debruijn_register_code.cljc`, `debruijn/register.cljc`, every golden R.

**Acceptance list (5b).**

1. `derive-test/register-lowerer-equivalence`: §4.1 (1) image map
   equality including body descriptors, (2) `encode-register-image`
   bytes, (3) `register-hash`, between `(register-image v)` and
   `(rc/adapt (vm/ast->datoms P))`, for every `P ∈ C`; side-table
   `:params` equal.
2. `derive-test/r2-goldens-stand`: the twelve pinned R values and images
   of `debruijn_register_compile_test.cljc:460-620` are reproduced from
   A: `golden-pure-r1-image-and-r-test`'s image and
   `"c0aefe2f…bb21"`, the four effect-bearing goldens, the nested
   control-flow golden `"fb762610…8852"` with its `[1 2]` live set at pc
   6, the ffi and resume goldens. The expected values are **copied
   literally** from that file, not computed.
3. `derive-test/derived-register-images-validate`:
   `(rcode/register-image-defect (:image (register-image v)))` is nil for
   every `P ∈ C` (all twelve rules, `live-exact` included).
4. `derive-test/lexical-address-law`: §5.2 complete, four legs plus
   today's resolver.
5. `derive-test/derived-register-images-run-on-r4`: every B0 program's
   derived image loads under `rvm/create-vm` with `vm/register-contract`
   and runs to the pinned value under `normalize` (template: the
   `:register` backend of `linked_harness.cljc:118-120`, without the link
   pair).
6. `derive-test/worked-example-slots`: the derived register image of
   `:worked-example` is exactly
   `[[:load-free 1 f] [:load-free 3 g] [:load-free 4 x] [:call 2 3 [4] false []] [:load-free 3 y] [:call 0 1 [2 3] false []] [:halt 0]]`
   with body `{:locals 0 :registers 5 :start 0 :end 6}`, pinning §3.1's
   statement of R1's discipline against A's `[1 3 4 2 5 0]` numbering.
7. The §4.5 row for `"r2"` is filled: bytes held, contract held, outcome
   RETAIN; the orchestrator's log records it against frozen §10 phase 5.
8. kondo clean; `bb test:sub yin.vm` green on three hosts.

### 7.3 Slice 5c: scanners, format record, linker wiring

**Files to create.**

- `src/cljc/yin/vm/semantic_register/scan.cljc` (§6.2).
- `test/yin/vm/semantic_register/scan_test.cljc` (ns
  `yin.vm.semantic-register.scan-test`; §6.3's three agreement tests and
  the golden-generation helper).
- `test/yin/vm/semantic_register/scanner_goldens.cljc` (§6.4), generated
  on the JVM and committed as Clojure data.

**Files to change, additively.**

- `src/cljc/yin/vm/linker.cljc`: `semantic-register-format` (§6.5) after
  `semantic-format`; `relowered` and `relowered-hashes` gain the A-derived
  path (§6.6 step 1).
- `src/cljc/yin/repl/link.cljc:81-89`: the record added to `formats`.
- `src/cljc/yin/vm/linker/publish.cljc:165-168`: `h-image`/`r-image`
  from `derive/stack-image` and `derive/register-image` over the
  projected tree (§6.6 step 1).
- `test/yin/vm/linker_test.cljc`: one new deftest per existing pattern,
  `semantic-register-scanners-yield-position-bearing-records` (template
  `vector-scanners-yield-position-bearing-records`, `:812`), and the
  `"v4"` row in `format-records-name-their-contract` (`:562`).

**Untouched:** `vm.cljc` (`footprint-table` gains its `"v4"` entry at
cutover, not here; §6.2 keeps the derived copy in `scan` with a drift
test), `completion.cljc`, `handoff.cljc` (the register evaluator's UCF
census is phase 6), the C4 track, every kernel and lowerer.

**Acceptance list (5c).**

1. `scan-test/positions-are-the-v4-table`: on the goldens `:store-ops`,
   `:define-call`, `:ffi-call`, `:resume-body`, `:streams`, `:park`
   (`corpus.cljc`): `requirements` yields `{:store-keys #{k :n 7 y}
   :ffi-ops #{:op/echo} :parked-ids #{:p} :effects #{:stream/make :stream/put
   :stream/cursor :stream/next :stream/close}}` per program as appropriate,
   and a register id is **never** mistaken for a key, op or pid (the
   off-by-one of §6.1, asserted negatively: no fact equals a small
   integer that is a register of the program unless the program's key is
   that integer, as `:store-ops`' `7` deliberately is).
2. `scan-test/free-occurrences-use-the-owner-tree`: over `:nested-lambdas`,
   `:body-queue-order`, `:closure-in-arm-in-body` and `:define-then-call`,
   `free-occurrences` marks `+`, `f`, `list`, `inc` free and `a b p q r x
   y z n` bound by their owning closure; `:in-body?` is true exactly for
   occurrences in bodies > 0.
3. `scan-test/definitions-and-sites`: `:define-then-call` yields a
   definition `n` with `:conditional? false` at the main sequence and
   `:if-in-tail`'s body call sites are in a body; counts equal the
   `"v3"` scanners' counts.
4. `scan-test/footprint-mnemonics-is-the-v3-table-minus-push`.
5. `scan-test/scanners-agree-on-b0` (26 rows asserted), §6.3 three-way.
6. `scan-test/scanners-agree-on-the-register-corpus` (32 rows asserted).
7. `scan-test/scanners-agree-on-the-c4-modules` (`^:slow`, guarded): the
   eight module ASTs by name; the free-name set of each equals its spec's
   declared primitive keys minus requirement-qualified names, as
   `module-spec-declares-every-free-name-test` asserts for `py`.
8. `scan-test/goldens-match`: every map of `scanner_goldens.cljc` equals
   the live `"v3"` scanners' answer **and** the new scanners' answer.
9. `linker-test/semantic-register-scanners-yield-position-bearing-records`;
   `format-records-name-their-contract` passes with the `"v4"` row.
10. `scan-test/undischarged-agrees`: for every program of corpora 1-2 the
    retained obligation names from `linker/undischarged` over `"v3"`
    records and over `"v4"` records are equal in order.
11. `linker-test` and `linker_manifest_test` unchanged and green: the
    `relowered`/`publish-closure!` switch to `derive` is byte-neutral
    (§4), so `a-prelude-sized-module-publishes-under-default-bounds`
    (`linker_test.cljc:1463`) and the C4 `manifest-address-test`
    (`linked_prelude_test.cljc:252`, slow) still pin their addresses.
12. kondo clean on the changed files; `bb test:sub yin.vm` green on
    three hosts; `bb test:changed` (G2) green on three hosts; the Node
    log shows `Testing yin.vm.semantic-register.scan-test`.

### 7.4 Ordering and concurrency

| | needs | may run concurrently with |
|---|---|---|
| 5a | phase 3 (landed) | 4a review, 4b, 5c-scanners |
| 5b | 5a (`resolve-vector`) | 4b, 5c-scanners |
| 5c scanners + format record (items 1-10) | phase 3 only | 4b, 5a, 5b |
| 5c linker wiring (`relowered`, `publish`, item 11) | 5a and 5b (byte-neutrality proven) | 4b |

**Phase 4b** touches `semantic_register.cljc`, its three test
namespaces, and four backend maps (evaluator design §5.2); phase 5
touches none of those files, and 4b touches none of phase 5's, so all of
5a, 5b and the scanner half of 5c can run **concurrently with 4b** in
separate worktrees. One edit crosses the two: after both 4b and 5c land,
the `linker_require_test.cljc` fixture comment "empty obligations until
phase 5 supplies the register-vector scanners" is retired by pointing the
backend's `:obligations` at `semantic-register-format`'s scanner; that is
a two-line follow-up belonging to whichever lands second, named here so
neither slice forgets it. Phase 6 (continuation format) is the first
consumer of `scan` outside the linker (the UCF census over register
frames), and `handoff.cljc`'s `free-names-v2`/`validate-closures-v2`
gain their `"v4"` arms there, not in phase 5.

---

## 8. Findings and proposed amendments to the frozen design

Each entry: evidence, the frozen section, the exact text to replace, the
exact replacement. F8 is the key decision; the others follow from it or
from the code. None changes a transition, a shape, or the identity rule.

### F8 — A → R by the existing lowerer, not a new interval allocator (frozen §8.2, §8.4, §6, §9, §10 phase 5)

Evidence: §3.1-3.3 of this document; `debruijn_register_compile.cljc:88-116, 152-306, 358-428`;
the two-row table of §3.2 derived under §8.2's own scan rules; the
amendments of 2026-10-10 in `yin.vm.debruijn.register.md:26-29, 42-44`.

**§8.2**, whole section. Replace the heading and body from "### 8.2 A →
R: the de Bruijn register image" through "The register image's `lift`
changes its output contract to the §2.3 table." with:

> ### 8.2 A → R: the de Bruijn register image
>
> 1. **Resolve on the vector.** Parse each body under §3.1 (the
>    validator has already established that it parses) and build the
>    closure-owner tree of §3.4 rule 6, so every body has one enclosing
>    chain of parameter vectors. Emit **resolved tuples** in the shape the
>    de Bruijn resolver emits today (register design §2.1): one record per
>    parse node (one per occurrence; A is positionally expanded), a
>    `:variable` record carrying `:yin.resolved/depth`/`:yin.resolved/position`
>    or `:yin.resolved/free` from `yin.vm.debruijn/resolve-name` against the
>    chain, a `:lambda` record carrying `:yin.resolved/arity` and its body,
>    a `:define` tuple expanded to the definition application with its
>    synthesized operator and literal-key records, every other tuple's
>    literal operands carried verbatim, `:yin/tail?` when true; a `:source`
>    table mapping every record to its pc and a `:params` table mapping
>    every closure record to its parameter vector. The output passes
>    `validate-resolved` and is a stage value with no identity. This one
>    function serves §8.3 as well.
> 2. **Lower** with the existing `lower-register`
>    (`yin.vm.debruijn-register-compile`), unchanged: its
>    target-register-passing walk over the recovered tree, its
>    lowest-free allocator with release on consumption, its `fill-live`
>    over `body-liveness`, its body descriptors. R = `register-hash` of
>    the result, as a checksum.
>
> Nothing of R1 is replaced: the input validation stays
> `validate-resolved` (now satisfied by construction from a §3.4-valid
> vector), the expression-tree walk is fed the parse, and the allocator
> is R1's. R1's discipline is A's pre-order minting with a free list (a
> destination is assigned when its expression is entered, before its
> children; an index is reused once its consumer has emitted), so the
> physical image is a deterministic function of the tree A encodes. The
> register image's `lift` and both descriptors are untouched (their
> declared lift target is hashed into H and R; see the derivations
> design, F10).

**§8.3**, step 1. Replace "1. Resolve as in §8.2 step 1." with
"1. Resolve as in §8.2 step 1: the same function, the same resolved
tuples." Step 3, replace:

> 3. **Adapt** the recovered trees to the input shape `lower-stack`
>    consumes today, resolved tuples plus side table (register design
>    §2.1): one resolved record per occurrence (occurrences stay
>    positionally expanded, never re-shared), defaults as saturated,
>    `:yin/tail?` from the `:call`'s `tail?`, binder arities from the
>    `:closure` instructions, and bodies in the §3.3 queue order so that
>    discovery order is preserved.

with:

> 3. There is no further adapter: step 1's output is the input
>    `lower-stack` consumes today (resolved tuples plus side table,
>    register design §2.1). Body order and absolute pcs are the
>    emitter's own (its FIFO body queue and label pass), recomputed from
>    the tree; they coincide with A's layout because both follow §3.3
>    item 1.

**§8.4**, replace the two sentences "The emitter walk in §8.3 is the same
walk as today's, over the same tree, so H is expected to hold; R depends
on whether §8.2's interval scan reproduces R1's reservation-and-release
timing, which is not expected to hold exactly." with:

> Both emitters are today's, over the same tree, fed by a resolver whose
> output is lowerer-equivalent to today's resolver's (the derivations
> design §1.6), so H and R hold **by construction**; the comparison is
> run per corpus program as a gate (derivations design §4), and a
> difference is a defect in the vector resolver, never a reason to
> re-version.

**§6**, replace the last sentence of the second paragraph, "What changes
here is their input (A instead of resolved tuples), which touches the
register design's §4 allocation contract (§8.2), so `"r2"` is the one
expected to move." with:

> What changes here is the producer of their input: resolved tuples
> derived from A instead of from named datoms. The register design's §4
> allocation contract is untouched (§8.2), so neither is expected to
> move; the derivations design §4 records the outcome with its evidence.

**§9**, "Rewrite" paragraph, replace "the de Bruijn resolver/lowerer
adapters (§8.2 step 1, §8.3 step 3), the register design §2 and §4
(input, allocation, validation, per §8.2) and the register `lift`'s
output contract;" with "the de Bruijn resolver's producer (§8.2 step 1;
the register design §2.1's input sentence and §4.1's first sentence name
the new producer, nothing else in §2 or §4 changes);". In "Corpora",
replace "H and R goldens are preserved only if §8.4 says the bytes held."
with "H and R goldens are preserved; the derivations design §4 gate is
the proof."

**§10 phase 5**, replace "§8.2-8.3 implemented;" with "§8.2-8.3
implemented as `resolve-vector`, `stack-image`, `register-image`
(derivations design §1-§3);".

### F9 — the adapter neither orders bodies nor computes pcs (frozen §8.3 steps 3-4)

Evidence: `debruijn_linearize.cljc:98, 115-117, 173-178, 182-189`;
`debruijn_register_compile.cljc:384-392, 399-407`. Both emitters own a
FIFO body queue and a label/offset pass; a resolved-tuple set is a tree
with no pc order to preserve. Amendment text is inside F8's §8.3 step 3
replacement; recorded separately so the reviewer can accept it even if
F8's route is overruled (under route (a) too, §8.3's stack adapter needs
no ordering).

### F10 — the lift target is hashed; "lift changes its output contract" would move H and R (frozen §8.2 last paragraph, §9)

Evidence: `dc/descriptor` includes
`[:yin.debruijn.code/dimension :dim/lift-to [:yin.code/*]]`
(`debruijn_code.cljc:145`) and feeds `descriptor-hash` (`:542-548`) and
every H (`:551-567`); `rcode/descriptor` likewise
(`debruijn_register_code.cljc:204, 257-269`). Retargeting the declared
lift to the §2.3 table would change the descriptor data and so every H
and R, forcing `"b3"`/`"r3"` under §6 with no change to any executable
byte. The lift functions themselves (`dl/lift`, `rc/lift`) produce the
`"v3"` named shape and are test oracles against `yin.vm.linearize/lower`,
which phase 8 deletes.

Amendment: F8's §8.2 replacement already drops the sentence. Add to **§10
phase 8**, after "remove the old H/R request paths and the `raise`
dependency,": "decide the lifts and the descriptors' declared lift
target: either retarget the lift functions to the §2.3 table and keep
`:dim/lift-to [:yin.code/*]` as a frozen historical label so H and R do
not move, or re-version `"b2"`/`"r2"` openly; the owner chooses
(derivations design §9 item 2)."

### F11 — "discharged bodies" is not a scanner fact (frozen §10 phase 5)

Evidence: the scanners yield free-name occurrences with `:in-body?`,
definitions with `:conditional?`, application sites, and the
requirements map (`linker.cljc:224-269, 497-529`; `vm.cljc:1830-1888`);
discharge is the step-5a join's verdict over those (`linker/undischarged`,
`linker.cljc:1090-1160`), and positions (`:at`) differ between the two
layouts by construction. Replace, in §10 phase 5:

> **the dependency-closure scanners (UCF §7.6.1) reimplemented over the
> new table answer identically to the old ones on the C4 linked-prelude
> corpus** (free names, store footprint, discharged bodies), since the
> C4 track's module layout is built against those answers.

with:

> **the dependency-closure scanners (UCF §7.6.1) reimplemented over the
> new table answer identically to the old ones and to the tree-side
> oracle on the B0 corpus, the register corpus and the C4 linked-prelude
> modules by name** (free-name sets; free-name occurrences and
> definitions compared by name and `:in-body?`/`:conditional?` flag in
> order, positions excluded since the layouts differ; application-site
> counts; store keys, FFI ops, parked ids and effect kinds; and the
> step-5a join's retained obligations in order), with the old scanners'
> answers pinned as goldens before cutover deletes them, since the C4
> track's module declarations are built against those answers.

### F12 — the frozen §8.1 is unaffected; the structural slot is retired with §8.2

Evidence: §8.1 (`def`, `use`, `L`, `saved`) is consumed by phase 4 and
phase 6 and is untouched by F8. The "definition-less ids" and "structural
slot `Tₖ`" paragraphs lived only in §8.2 and go with it: R1 already
handles a wholly terminal body by its pre-allocated result temporary
(`[:const 1 "ok"] [:resume :p1 1] [:halt 0]`, two registers,
`debruijn_register_compile_test.cljc:584-595`). No further text change;
recorded so that §3.4 item 7's "definition-less" vocabulary is understood
to remain (it is the validator's), while the allocation rule it fed is
gone.

### Checked and consistent (no amendment)

§3.3 minting against R1's allocation order (§3.1 here); §3.4 rule 6's
owner tree as the resolution chain; §7 (A over the saturated vector;
`project` and `ast->datoms` saturate identically, `vm.cljc:841, 850`,
`linearize.cljc:46-52`); §8.1 `saved` and `live` (unused by phase 5
under F8, consumed by phase 6); the F6 interim format name
`:yin.semantic-register/code` and contract `"v4"` (§6.5); the register
design's §2.1 shape contract (unchanged, now with two producers); the
linker §3 amendment's derivation-record direction (§6.6 step 2 at
cutover).

---

## 9. Questions for the owner, and what is the reviewer's

**For the owner** (decisions that are genuinely theirs):

1. **Route (b) retains `"r2"` and `"b2"` unchanged, and under it the
   native allocators never read A's virtual register ids** (they parse A
   into the tree and allocate as today). Is that acceptable as the
   phase-5 outcome, given the frozen §8.4 expected `"r2"` to move? The
   recommendation is yes (§3.3-3.4); a re-versioned `"r2"` is not needed
   and would buy only a vector-fed allocator.
2. **At phase 8**, the lifts and the descriptors' hashed `:dim/lift-to
   [:yin.code/*]` label (F10): keep the label frozen so H and R never
   move at cutover, or re-version openly? Phase 5 does not touch them
   either way.
3. **At phase 8**, the semantic derivation record's `:yin.lower/profile`
   string for the `"v4"` lowering (today `"ast-to-bytecode"` with
   `:yin.code/contract "v3"`, `ledger.cljc:40-50`): a new name or the
   same string under `"v4"`. A naming choice with no byte consequence
   before cutover.

**For the Architect reviewer** (design choices of this document):

- F8's route; F9-F12's texts.
- §1.4's record-id scheme (pc-derived versus counter).
- §4.2's corpus `C` membership and §4.4's "gate, not a choice" stance.
- §6.2's `scan` namespace keeping a derived copy of the footprint table
  with a drift test, versus adding a `"v4"` key to `vm/footprint-table`
  in 5c.
- §6.6 step 1's byte-neutral switch of `relowered`/`publish-closure!` in
  5c (versus deferring to cutover), and the fate of
  `yin.vm.debruijn-resolve/resolve` over named datoms after cutover: this
  design keeps it as the lowerer-equivalence oracle through phase 7 and
  recommends keeping it as a test-only oracle thereafter, since the
  `unresolve` law and the shared-occurrence fixtures are its and nothing
  else's.
- §7.4's follow-up edit to the 4b obligations fixture.

---

## 10. Portability notes for the implementer

Each item names the repo-known host trap it guards against.

1. **No host map iteration order enters a derivation.** `resolve-vector`
   walks the parse tree (vectors) and `body-ranges` (a vector in pc
   order); record ids are pc-derived (§1.4); `addresses` walks pcs. The
   scanners' set-valued facts are compared as sets; their sequences are
   in pc order. Never `(keys m)` into an emitted structure.
2. **Reader conditionals.** Catch clauses are
   `#?(:cljd Object :clj Throwable :cljs :default)` with `:cljd` first;
   any `:clj`-only form (the golden-generation helper's printing) is
   `#?(:cljd nil :clj …)`, because ClojureDart's host-eval pass also has
   `:clj`.
3. **Private var access (Dart).** `resolve-vector`, `addresses`,
   `stack-image`, `register-image`, every `scan` function and the
   `scanner_goldens` var are public; tests never reach through `#'`
   (`debruijn_register_contract_test.cljc:256-262` records why).
4. **`for` over long seqs (Dart).** The C4 `py` module has thousands of
   tuples; build record vectors with `mapv`/`into`, never `for`.
5. **Multi-key `assoc` on nil (Dart).** Side tables start from `{}`;
   never `(assoc nil :a 1 :b 2)`.
6. **Protocol parameter names (Dart).** Not applicable: phase 5 defines
   no protocol.
7. **EDN and whitespace before closers (Dart reader).** `scanner_goldens`
   is Clojure data in a `.cljc`, not EDN strings; `pr-str`/`read-string`
   round trips in tests are fine.
8. **Negative integers as record ids.** `(- -1 (* 3 p))` is integer
   arithmetic on every host; no float negation (`(- 0.0)` is `+0.0` on
   Dart).
9. **Keyword identity (Node).** `case` on mnemonic keywords and
   `contains?` on keyword sets are portable; the phase-3 validator
   already does both on three hosts (`code.cljc:368-398`).
10. **`sorted-set` in liveness.** Untouched: `body-liveness` is reused,
    not rewritten (`debruijn_register_code.cljc:342-385`).
11. **Slow C4 gate.** `scanners-agree-on-the-c4-modules` is `^:slow` and
    wrapped in `dao.test-slow/guard`, since publishing-sized module ASTs
    take seconds on Dart; `bb test:sub yin.vm --slow` runs it.
12. **Fresh worktree.** `mise trust` and `npm ci` before the Node lane.
