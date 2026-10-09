# yin.vm.semantic-register-vm: the canonical projection rebuilt register-shaped

Status: **Design draft, not implemented.** Written 2026-10-10 from three
owner rulings of that date and the codex Architect review
`collab/1791510000000-architect-semantic-register-vm.gpt-6.1-sol.findings.md`
(PROCEED_WITH_CHANGES; its six required changes are the spine of §§3-8).
Subordinate to [`datom.world.md`](./datom.world.md). Replaces §2, §4 and
§5 of [`yin.vm.semantic.md`](./yin.vm.semantic.md) when it lands; until
then that document describes the running machine. `semantic-register-vm`
is a working name: on cutover (§10 phase 8) the old evaluator is deleted
and this one takes the name `yin.vm.semantic`.

## 1. Stance, in the owner's words

> "The AST is the source of truth but the semantic vm is the canonical
> projection of it. the ast-walker and the semantic-vm are isopmorphic.
> we just need to rebuild the smantic-vm as register-shaped vm instead
> of a stack-shaped vm"

> "yes, A is the only code identity"

> "rebuilding the semantic-vm as as register shaped instead of
> stack-shaped is the right way to go, delaying this will only add tech
> debt in the future" (with virtual registers: "yes, dispatch the brief
> with virtual registers. once we build semantic-register-vm we can
> delete semantic-vm and rename semantic-register-vm to sematic-vm")

Consequences this document takes as fixed:

1. The Universal AST is the one truth. The semantic VM is its
   **canonical projection**: the one executable form, whose segment
   address A (`ucf/code-address`, UCF §7.3.2) is the only code identity.
   The de Bruijn stack and register images are lowerings of A
   (amendments of 2026-10-10 in `yin.vm.debruijn.stack.md`,
   `yin.vm.debruijn.register.md`, `yin.vm.debruijn.targets.md`,
   `yin.vm.linker.md` §3, UCF v2 amendment §4).
2. The AST walker and the semantic VM are **isomorphic**: equal
   observable behaviour on every program over the shared supported AST
   vocabulary (`yin.vm.semantic.md` §2.4; the walker's `:vm/store-update`
   arm is outside it and lowering rejects it). The walker is the oracle
   this rebuild must match, and the register vector is the *image of the
   walker's expression evaluation*: one register per value the walker
   holds in flight (`:value`, the `:evaluated` vectors of its frames).
   That is what makes the grammar of §3 expression-structured rather
   than an arbitrary three-address program. "Isomorphic" is a
   correspondence of observable behaviour and of evaluation structure
   (§2.5), not a literal step-for-step state bijection: administrative
   steps on either side (the walker's frame pushes, the register
   machine's jumps) have no single-step counterpart.
3. This is one rebuild of the canonical projection: not a new VM
   family, not a fifth UCF profile. "One truth, many interpretations"
   (axiom 2) is not violated; the walker, this VM, and the de Bruijn
   kernels remain interpretations of the one AST.
4. **Virtual registers.** The canonical vector names an unbounded,
   never-reused register file. No allocation, no temp bank, no spill, no
   live sets in A. Everything an allocator or a kernel needs beyond that
   is derived from A (§8) and never persisted in it ("derive, don't
   persist").

## 2. The machine

### 2.1 Configuration

$$\langle C, E, S, K\rangle,\qquad C = \langle seg,\ pc,\ W\rangle$$

- **C**: the segment, the program counter, and the **window** `W`, a
  sparse map from virtual register ids to values for the current
  activation. `W` is control, not store: it is what the activation is
  in the middle of computing. `val` and `St` of the stack machine do not
  exist; their contents are registers in `W`.
- **E**: unchanged. A persistent map `sym → value`, extended on closure
  entry with `merge closure-env (bind-params params args)`, nil-filling
  as today; carries `engine/store-of-key` for module context exactly as
  today. **Parameters live in E, not in registers**: `:var rd name`
  reads E through `ρ` (`engine/resolve-var`: env → store → primitives →
  modules) and writes `rd`. Registers hold only expression
  intermediates. This keeps the walker isomorphism visible (the walker
  has no registers; it has `:value` and `:evaluated`) and leaves the de
  Bruijn register image free to map parameters to its own `L` bank.
- **S**: unchanged.
- **K**: a vector of frames, innermost last. One frame kind for calls,
  `{:type :return :segment :pc :env :window :rd}` (§2.4), and the
  engine's effect-continuation frames (`:dao.stream.apply/eval-call`,
  `:request-sent`) extended with `:segment :pc :window :rd` in place of
  `:segment :pc :stack`.

### 2.2 Registers

A register id is a non-negative integer, **body-local**: the main
sequence and each out-of-line lambda body number their registers from
0 independently (§3.3). A register is written by exactly one instruction
in program order except that the two arms of a conditional write the
same register (exclusive arm definitions, §3.2). A register is never
written twice on one path and is never read before it is written on any
path (definite assignment, §3.4). The register file is unbounded; the
validator records each body's register count as a derived fact (§8).

### 2.3 Instruction table

Positional tuples, one per instruction, pc as index, in the form UCF
§7.3.2 fixes. The table is the de Bruijn register design's §4.4 with
named lexical addressing and **no `live` operands**: `live` is derived
(§8.1), as a foreign engine's layout is, and does not enter A.

```
:const                [op rd value]
:var                  [op rd name]
:closure              [op rd params body-pc]
:call                 [op rd fn-reg arg-regs tail?]
:branch-false         [op cond-reg target]
:jump                 [op target]
:return               [op value-reg]
:halt                 [op value-reg]
:gensym               [op rd prefix]
:store-get            [op rd key]
:store-put            [op rd key value]
:stream-make          [op rd buffer]
:stream-put           [op rd stream-reg value-reg]
:stream-cursor        [op rd stream-reg]
:stream-next          [op rd cursor-reg]
:stream-close         [op rd stream-reg]
:ffi-call             [op rd ffi-op arg-regs]
:current-continuation [op rd]
:park                 [op rd]
:resume               [op parked-id value-reg]
:define               [op rd name rs]
```

Operand kinds are those of `code/vector-operand-table` plus `:reg` and
`:regs` (non-negative integers, vectors of them), as the register
design's R2 descriptor already names them. There is no `:push` and no
`:move`: a conditional's arms target the conditional's own `rd`
directly, so no value is ever relocated. `:resume` has no destination:
it transfers control to a parked activation and never reaches its own
successor. Every other value-producing instruction names `rd`.

### 2.4 Transitions

Let `I = code[seg][pc]`, `W[r]` the value of register `r`,
`W[r ← v]` the window with `r` written. $\rho$ is `engine/resolve-var`.

$$\begin{aligned}
&\text{const}:&& \langle seg,pc,W,E,S,K\rangle \to \langle seg,pc{+}1,\,W[rd \leftarrow I.v],E,S,K\rangle\\
&\text{var}:&& \to \langle seg,pc{+}1,\,W[rd \leftarrow \rho(E,S,I.name)],E,S,K\rangle\\
&\text{closure}:&& \to \langle seg,pc{+}1,\,W[rd \leftarrow \mathrm{clo}(I.params,I.body,seg,E)],E,S,K\rangle\\
&\text{jump}:&& \to \langle seg,\,I.target,\,W,E,S,K\rangle\\
&\text{branch-false}:&& \to \langle seg,\,(W[cond]\ ?\ pc{+}1 : I.target),\,W,E,S,K\rangle\\
&\text{define}:&& \to \langle seg,pc{+}1,\,W[rd \leftarrow W[rs]],E,\,S[I.name \mapsto W[rs]],K\rangle\\
&\text{halt}:&& \to \text{halted},\ \text{result} = W[I.value\text{-}reg]
\end{aligned}$$

`define` writes through `engine/store-put`, which refuses the reserved
key (Rule R unchanged); the definition operator is never resolved.

**The saved window.** Wherever a transition saves the current
activation for later delivery of a value into `rd`, the window it saves
is

$$\mathrm{saved}(p, rd) = W\!\restriction\!(L(p) \setminus \{rd\})$$

where `L(p)` is the standard live-in set at the resume pc `p` (§8.1),
which may well contain `rd` (later instructions read the delivered
value), so the subtraction is explicit and is the rule everywhere:
call frames, parks, captures, waits, foreign-engine reconstruction and
the `live` operands of §8.2. Nothing else is ever saved from `W`.

**call** with `f = W[fn-reg]`, `a = [W[r] for r in arg-regs]`:

$$\begin{aligned}
&f = \mathrm{clo}(ps,b,seg',E_c),\ \neg tail:&&
\to \langle seg',\,b,\,\{\},\,E_c[ps \mapsto a],\,S,\,K \Vert [\mathrm{ret}(seg,pc{+}1,E,\,\mathrm{saved}(pc{+}1,rd),\,rd)]\rangle\\
&f = \mathrm{clo}(ps,b,seg',E_c),\ tail:&&
\to \langle seg',\,b,\,\{\},\,E_c[ps \mapsto a],\,S,\,K\rangle\\
&f\ \text{primitive},\ f(a) = v,\ \neg tail:&&
\to \langle seg,pc{+}1,\,W[rd \leftarrow v],E,S,K\rangle\\
&f\ \text{primitive},\ f(a) = v,\ tail:&&
\to \mathrm{return}(v)\ \text{(below: the value goes to }K\text{'s top frame, never to }rd)\\
&f\ \text{primitive},\ f(a) = \epsilon,\ \neg tail:&&
\to \mathrm{effect}(\epsilon,\ \mathrm{act}(seg,pc{+}1,E,\mathrm{saved}(pc{+}1,rd),K),\ \mathrm{deliver}(\mathrm{rd}, rd))\\
&f\ \text{primitive},\ f(a) = \epsilon,\ tail:&&
\to \mathrm{effect}(\epsilon,\ \mathrm{tail}(seg,pc,K),\ \mathrm{deliver}(\mathrm{return}))\\
&f = \kappa\ \text{(a reified continuation)}:&&
\to \text{restore }\kappa\text{ and deliver }a_1\text{ per }\kappa\text{'s own deliver; the caller's activation and }K\text{ are discarded}\\
&f\ \text{not callable}:&& \to \text{error, as today}
\end{aligned}$$

A callee activation starts with an **empty window**; its parameters are
in `E` (duplicate-parameter resolution, nil-fill and extra-argument
dropping are `bind-params`' and are unchanged). A non-tail call saves
the caller's window restricted by the saved-window rule, with the
delivery destination `rd`. **A tail call never writes `rd` and never
resumes its own activation**: whatever the callee produces, a closure's
eventual `:return`, a pure primitive's immediate value, or an effect's
completion, is delivered through `K`. This makes tail completion
uniform and makes the instructions textually after a tail call layout
syntax (§3.3 item 4). Invoking a reified continuation is abortive, as
`semantic/apply-call`'s `:continuation` arm is today: the argument is
validated (exactly one), ownership of `κ` is checked under the UCF
rules for source-owned continuation values, the current activation and
`K` are discarded, `κ`'s captured state is restored, and the argument is
delivered exactly once per `κ`'s recorded `deliver`.

**return** with `K = K' ∥ [ret(seg', pc', E', W', rd')]`:

$$\to \langle seg',\,pc',\,W'[rd' \leftarrow v],\,E',\,S,\,K'\rangle,\qquad v = W[I.value\text{-}reg]$$

and with `K = []`: halted with result `v`. The result is written
**exactly once**, into the popped frame's destination. `return(v)` as
used above is this transition applied to a value that did not come from
the current window.

**Delivery.** Every suspended or deferred computation records where its
result goes, as a closed typed record:

```clojure
{:deliver :rd     :rd r}        ; write-result: v → W[r], continue at the recorded pc
{:deliver :return}              ; through-return: return(v) against K
```

and the suspended state it belongs to is one of two closed shapes:

```clojure
act  = {seg pc E window K}      ; a resumable activation; window = saved(pc, rd)
tail = {seg site K}             ; no activation: the tail site (for diagnostics
                                ;   and provenance), K, and nothing to resume into
```

A `tail` state carries no window and no `E` of its own; the store
context any later code needs is in the frames of `K`. Delivery of `v`
to an `act` with `{:deliver :rd r}` restores it, writes `W[r ← v]`, and
continues at `pc`; delivery to a `tail` with `{:deliver :return}` runs
`return(v)` against `K`, which pops a frame or halts. The engine seam
already supports this: `handle-effect`'s `restore-fn(base, entry, value)`
treats the VM payload as data and does not require a current
activation, so the semantic restore helper decodes the completion and
either resumes an `act` or runs `return(v)`; no trampoline activation
is synthesized. The two shapes cover primitive effects, `:park`,
`:current-continuation`, stream and FFI waits (including the
`request-sent` phase, which advances the FFI protocol without
delivering anything to guest code), link waits (whose three phases
preserve the delivery record until install completes), and halting. An
effectful tail call therefore completes by return, never by writing a
register of a body that no longer runs.

**stream-next** (cursor ref `c = W[cursor-reg]`), by outcome:

$$\begin{aligned}
&ok\ v,\ c':&& \to \langle seg,pc{+}1,\,W[rd \leftarrow v],E,\,S[c \mapsto c'],K\rangle\\
&blocked:&& \to \text{wait}\ \{\mathrm{act}(seg,pc{+}1,E,\mathrm{saved}(pc{+}1,rd),K),\ \{\text{:deliver :rd :rd } rd\},\ \text{pending: cell } c\}\\
&end:&& \to \langle seg,pc{+}1,\,W[rd \leftarrow \mathrm{nil}],E,S,K\rangle\\
&gap\ c':&& \to \langle seg,pc{+}1,\,W[rd \leftarrow \text{:dao.stream/gap}],E,\,S[c \mapsto c'],K\rangle
\end{aligned}$$

`stream-put`, `stream-make`, `stream-cursor`, `stream-close`, `gensym`,
`store-get`, `store-put`: as today, reading their register operands
from `W` and their literal operands from the instruction, and writing
`rd`. A blocking `stream-put` waits as `act(seg, pc+1, E,
saved(pc+1, rd), K)` with `{:deliver :rd}` and the **retained value
`W[value-reg]` in the pending record**, where it belongs even though
`value-reg` may be absent from the saved window.

**ffi-call** (`a = [W[r] for r in arg-regs]`): the wait
`act(seg, pc+1, E, saved(pc+1, rd), K)` with `{:deliver :rd}` is
recorded under a fresh call id `p` (`eval-call`); `request(p, I.op, a)`
is appended; a `full` append keeps the request retained
(`request-sent` phase, which when it later appends advances to the
response wait and delivers nothing to guest code); on the correlated
response its `ok` value is delivered to `rd` at `pc+1`, its `error`
raises, and the parked bookkeeping for `p` is removed.

**park**: the record `act(seg, pc+1, E, saved(pc+1, rd), K)` with
`{:deliver :rd}` is written to `:parked` under a fresh id; the task
halts with that record as its value. The not-yet-produced value of
`rd` is excluded from the saved window by the saved-window rule.
Explicit park raises **no wait entry** (UCF §7.4.3 r5: the parked
record is the no-wait shape).
**resume** (`v = W[value-reg]`, `I.id` the parked id): the parked
`act` is restored and `v` is delivered per its `deliver`. `:resume`
ends its own path.
**current-continuation**: `W[rd ← κ]` where
`κ = act(seg, pc+1, E, saved(pc+1, rd), K)` with `{:deliver :rd}`,
reified as data. `κ` is a captured value, never a safepoint. Invoking
it (`:call` arm above) delivers the argument to `rd` in the captured
window and continues at `pc+1`; `κ` is never captured inside its own
destination because the saved-window rule excludes `rd`.

### 2.5 Correspondence with the walker

The correspondence is per supported walker frame and effect phase, and
it allows administrative steps on both sides:

| walker state | register machine |
|---|---|
| `:value` after evaluating a node | the node's `rd`, written |
| `eval-operator`, `eval-operand` frames with their `:evaluated` prefix | the pc inside the call's operand sequence, plus the operator and argument registers already written in `W` |
| `eval-test` | the test's `rd` written, then `:branch-false` |
| `eval-stream-put-target` / `eval-stream-put-val` | the pc before the value expression, with the target register written; then the value register, then `:stream-put` |
| stream source and cursor frames | the pc before the corresponding stream instruction |
| `eval-define`, `eval-resume-val` | the child's `rd` written, then `:define` / `:resume` |
| a non-tail call's continuation frame (saved env, `:evaluated` prefix) | a `ret` frame: saved `E`, `saved(pc+1, rd)` as the window, `rd` |
| `request-sent` | the retained-request phase: the FFI protocol advances, nothing is delivered to guest code |
| `eval-call` completion | correlated response decoding, parked bookkeeping removal, delivery to `rd` |
| walker frame pushes/pops; register `:jump` / `:return` administrative steps | no single-step counterpart; the correspondence holds at the next value-producing step |

Expression frames correspond to **pc and `W`**, not to `ret` frames; a
`ret` frame corresponds only to a suspended caller activation. The
parity lane (§10, phase 4) runs every corpus program on both machines
and compares values, store, effect traces and halting; a trace test
aligns walker value-producing steps with register writes under the
table above, administrative steps skipped.

## 3. The canonical grammar

### 3.1 Expression-structured by construction

A body is the image of one expression tree under the lowering walk of
§3.3. The validator does not accept arbitrary programs over the
instruction table: it **parses** each body under the recoverable
grammar below, and a body that does not parse is refused
(`:not-expression-structured`). This is the restriction the Architect
required so that the stack lowering (§8.3) is well defined, and it is
exactly the grammar the de Bruijn register `lift` already recovers
(`debruijn_register_compile.cljc`, "the register image, viewed per body,
is a flat instruction sequence with a recoverable grammar").

The grammar is exhaustive over §2.3: every opcode appears in exactly
one production. `expr(rd)` denotes an expression whose **outcome** is
either a *result* delivered into `rd` or *terminal* (control leaves the
path and `rd` is never written). The outcome relation is defined
recursively, not by "the last instruction writes `rd`", so that a
conditional (which ends in a `:jump` or a label) and a nested
conditional both have a result destination, and so that a `resume`
anywhere in an expression makes the enclosing path terminal from that
point. Three notions are kept distinct throughout: a register id is
**minted** by exactly one expression (§3.3; every expression mints,
`resume` included); an id has zero or more **syntactic definitions**,
the instructions whose `rd` slot names it (a tail `:call` names its
`rd` syntactically though it never writes it at runtime; a `:resume`
has no `rd` slot and so gives its minted id no syntactic definition);
and an id has zero or more **runtime writes**, the reachable
instructions that actually write it (a reachable tail `:call` names
its `rd` and never writes it, so it is a syntactic definition and not a
runtime write; every other reachable syntactic definition is a runtime
write). "Terminal" is about runtime writes, never about syntax.

```
body         ::= expr(r) [:return r]                ; non-main bodies
               | expr(r) [:halt r]                  ; the main sequence
expr(rd)     ::= atom(rd) | call(rd) | if(rd) | define(rd)
               | effect(rd) | resume(rd)            ; resume: terminal outcome (below)

atom(rd)     ::= [:const rd v]
               | [:var rd name]
               | [:closure rd params body-pc]
               | [:gensym rd prefix]                 ; zero children, literal operand
               | [:store-get rd key]                 ; zero children, literal operand
               | [:store-put rd key value]           ; zero children, two literal operands
               | [:stream-make rd buffer]            ; zero children, literal operand
               | [:current-continuation rd]
               | [:park rd]

call(rd)     ::= expr(f) expr(a₁) … expr(aₙ) [:call rd f [a₁ … aₙ] tail?]      ; n ≥ 0
if(rd)       ::= expr(c) [:branch-false c Lalt]
                 expr(rd) [:jump Lend]
                 Lalt: expr(rd)
                 Lend:                                ; result destination: rd, by this rule
define(rd)   ::= expr(rs) [:define rd name rs]
effect(rd)   ::= expr(s) [:stream-cursor rd s]
               | expr(s) [:stream-close rd s]
               | expr(c) [:stream-next rd c]
               | expr(s) expr(v) [:stream-put rd s v]
               | expr(a₁) … expr(aₙ) [:ffi-call rd op [a₁ … aₙ]]              ; n ≥ 0
resume(rd)   ::= expr(v) [:resume parked-id v]        ; terminal: rd is minted, never written
```

Children appear in the order written, which is the evaluation order
(§3.3). A literal operand (`v`, `name`, `params`, `body-pc`, `prefix`,
`key`, `value`, `buffer`, `op`, `parked-id`, `tail?`) is carried in the
instruction and is never a child expression. `if(rd)` has result
destination `rd` by definition, and both arms are `expr(rd)`, so a
nested conditional in an arm is itself an `if(rd)` into the same
register.

**Outcomes.** An expression's outcome on a path is *result* if
evaluation of that path ends with a runtime write of its `rd` and
continues to the successor, and *terminal* if control leaves the path
before that (no normal result on that path). Composition is conditional
on children continuing:

- `atom(rd)`: result.
- `define(rd)`, `effect(rd)`: terminal if a child is terminal, else
  result.
- `call(rd)`: children are evaluated in order; if any child is
  terminal the call is terminal from that child onward; otherwise
  result when `tail?` is false and terminal when `tail?` is true (§2.4:
  a tail call completes through `K`, so its syntactic `rd` is never a
  runtime write).
- `if(rd)`: terminal if the test is terminal; otherwise result if at
  least one arm has a result outcome, terminal if both arms are
  terminal. Mixed arms are a result outcome on the continuing arm and
  terminal on the other, which is exactly what definite assignment
  (§3.4 item 8) handles by excluding terminal arms from the join.
- `resume(rd)`: terminal.

An expression containing a terminal child is terminal **from that
child onward**: the remaining children and the expression's own
instruction are layout syntax on that path (§3.4 item 10), and any
syntactic definitions among them are not runtime writes on that path.
A **wholly terminal body** (`body ::= expr(r) [:return r]` with
`expr(r)` terminal on every path) still carries its structural
`[:return r]` as layout syntax; `r` is minted, may have syntactic
definitions (a tail call's `rd`) or none (a `resume`'s), and has no
runtime write. Every supported AST program is accepted; terminal
outcomes narrow nothing, they only mark what runtime cannot reach.

### 3.2 Exclusive definitions by structured paths

A register has at most one definition on any path. Static definitions
of one register may be several, one per *leaf arm* of a (possibly
nested) conditional whose result destination is that register, and
these are on pairwise exclusive paths by the structure of §3.1. The
validator does not check pairs; it walks the structure: for each
register, the set of its syntactic definitions must be exactly the set
of leaf results of one `expr(rd)` subtree (a single instruction, or the
leaf arms of nested `if(rd)`s), and no instruction outside that subtree
defines it. That set may be **empty**: a subtree every leaf of which is
a `resume` (a bare `resume(rd)`, or an `if(rd)` whose arms are both
such subtrees) supplies no syntactic definition of `rd` at all, and the
rule is satisfied with the empty set.

### 3.3 The lowering walk and the minting order

The lowering `project : AST → vector` is the walker's evaluation order,
pinned here so that A is a function of the AST and this walk alone:

1. **Order**: operator, then operands left to right; a conditional's
   test, then its consequent, then its alternate (both emitted; one
   runs); `define`'s value operand; a stream instruction's operands in
   the order §3.1 writes them. **Occurrences expand positionally**: an
   AST node referenced from two sites is lowered twice, at two places,
   as today's linearizer and both de Bruijn lowerers do; nothing is
   deduplicated. **Saturation**: omitted operands take the loader's
   defaults before lowering (`gensym`'s `"id"` prefix, `call`'s
   `tail? false`, `ffi-call`'s argc), so the vector is saturated as
   UCF §7.3.2 requires. Lambda bodies are **out of line, after the
   sequence that references them, in queue order**: a `:closure`
   emitted anywhere (in the main sequence or inside a body being
   emitted) appends its body to one FIFO queue; the main sequence is
   emitted first, then bodies are dequeued and emitted one at a time,
   each a contiguous range ending in `:return`; bodies discovered while
   emitting a queued body go to the back of the same queue. The main
   sequence ends in `:halt`.
2. **Minting**: registers are **body-local**, numbered from 0 in the
   order destinations are minted. A destination is minted **when its
   expression is entered, before any of its children** (pre-order),
   with one refinement: an arm of a conditional is lowered *into* the
   conditional's existing `rd` and mints no destination of its own
   (its children still mint theirs). `resume` mints its destination
   like every other expression, although no instruction will ever name
   it syntactically (§3.1); this keeps minting uniform and canonical
   numbering a function of the tree alone. So for
   `(f (g x) y)`: `rd(f-call)=0`, then `rd(f)=1`, then `rd(g-call)=2`,
   `rd(g)=3`, `rd(x)=4`, then `rd(y)=5`; the emitted order is
   `[:var 1 f] [:var 3 g] [:var 4 x] [:call 2 3 [4] false] [:var 5 y]
   [:call 0 1 [2 5] tail?]`. Note that pre-order ids are therefore
   **not** in definition (pc) order; §8.2's allocator orders by pc, not
   by id. A zero-child atom mints its `rd` on entry like any other
   expression. Pre-order is chosen over post-order for one reason: it
   is uniform across every node type, including the conditional, which
   must have its destination before either arm is lowered.
3. **Every expression receives a destination**, used or not, written
   or not. There are no discarded expressions in the current AST (no
   sequencing form); if one is added, its non-final forms still mint and
   write registers, and the saved-window rule keeps them out of frames.
4. **Tail flag**: copied from `:yin/tail?` as the front end marks it.
   A tail `:call` completes through `K` (§2.4) and so is a **runtime
   terminator** of its path. Instructions that textually follow it on
   that path (an arm's `:jump Lend`, the body's `:return r`) are
   **layout syntax**: the grammar requires them so that every body
   parses uniformly and arms delimit uniformly, the validator treats
   them as structurally present and runtime-unreachable, and the
   liveness of §8.1 is computed over the real successor relation, in
   which a tail `:call` has no successor. The alternative, dropping the
   structural `:return`, was rejected (§11.1).
5. **No moves, folds, or rewrites** are permitted in lowering. The
   projection emits exactly what the walk visits.

A is `(jing/segment-key vector)` over the saturated positional vector
exactly as UCF §7.3.2 states, with provenance in a side table outside
the hash, as today. Because every input to the walk is fixed above
(order, occurrence expansion, saturation, queue order, minting
including the arm and `resume` refinements) and nothing depends on
allocation, scheduling, host iteration order, or names of anything but
what the AST already names, **A is a deterministic function of the AST
and this walk**. "Canonical vector" means **the unique projection**,
not any expression-structured vector with contiguous ids: the validator
enforces this by the round-trip rule of §3.4 item 13 (re-number the
parsed tree under §3.3 and compare). A remains an exact
identity, not an alpha-equivalence: binder names are in the vector
(`:var name`, `:closure params`), so renaming a binder changes A while
the derived H and R, which resolve names away, may stay equal. That
asymmetry is the existing one and is preserved.

### 3.4 Well-formedness

The loader's rules, replacing `yin.vm.semantic.md` §2.6 items 6-8 and
adding register rules; items 1-5 (one segment, instruction shape, pcs
`0..n-1`, sorted, refs resolve) stand:

6. **Body partition and ownership.** The pcs `0..n-1` are partitioned
   into contiguous bodies: the main sequence at 0 and one body per
   `:closure` instruction, beginning at that instruction's `body-pc`.
   Every pc belongs to exactly one body; every non-main body is the
   `body-pc` of **exactly one** `:closure` (its owner); the owner
   relation is a tree rooted at the main sequence (acyclic: a body's
   owner is in a different body, and following owners reaches main);
   no `:jump`/`:branch-false` target and no `body-pc` crosses a body
   boundary except the `:closure`'s own `body-pc`. Each body ends in
   `:return` (`:halt` for main) and parses under §3.1.
7. Register ids in a body are exactly `0..k-1` for some `k`, each
   minted by exactly one expression of the body's tree (§3.3). Each id
   has at least one syntactic definition unless its entire result
   subtree supplies none (§3.2: every leaf of the subtree is a
   `resume`), in which case it is **definition-less**. A runtime read of an id
   requires a runtime write before it on every path (item 8); an id
   with syntactic definitions but no runtime write (a tail call's `rd`,
   a destination inside layout syntax) is legal and is never read at
   runtime. A body never names another body's register.
8. **Definite assignment**: on every runtime path from the body's start
   to an instruction, every register it reads has been written. Checked
   by a forward walk over the structured control flow of §3.1: the
   assigned set after a conditional is the intersection of the assigned
   sets of its continuing arms, an arm that ends in a runtime terminator
   (tail `:call`, `:resume`) being excluded from the intersection; the
   walk follows the real successor relation, so layout-syntax
   instructions after a runtime terminator are not on any path and are
   validated structurally (item 10) rather than for assignment.
9. **Exclusive definitions by structured paths** (§3.2).
10. `:call` and `:ffi-call` argument vectors contain registers only;
    `tail?` is a boolean. **Layout syntax**: the instructions textually
    following a runtime terminator (a tail `:call`, a `:resume`) on its
    path, up to the end of the enclosing structures, are exactly what
    §3.1's enclosing productions require (the rest of an enclosing
    call's operands and its `:call`, an arm's `:jump`, a body's
    `:return`) and nothing else; they are validated for shape and
    kinds here and are unreachable **from that terminal path** (they
    may still be reachable from a continuing arm through a shared
    join, which is why liveness is computed over the stated CFG edges
    rather than by "no predecessors").
11. Rule R (`:reserved-name`): no `:var` names `yin/def`, no `:closure`
    binds it, no `:store-get`/`:store-put` key names it, every `:define`
    names a symbol other than `yin/def`. Unchanged.
12. `:resume`'s `parked-id` and `value-reg` are well kinded; `:resume`
    is a runtime terminator, and the layout syntax after it is as in
    item 10, wherever it occurs (as a body's whole expression, an arm,
    or an operand).
13. **Canonical numbering**: re-projecting the parsed tree of each body
    under §3.3 reproduces the body's register ids exactly
    (`:noncanonical-registers` otherwise). This is what makes A the
    unique projection rather than one of several equivalent spellings.

Violation is a load error naming the pc and rule, first defect wins,
as today. Both load paths (direct vector; projection to datoms) run the
same rules (code-as-tuples §7.2 unchanged).

## 4. Safepoints and the continuation format

### 4.1 The safepoint table (UCF §7.4.1, rewritten)

A safepoint is a transition at which the machine parks. The semantic
kinds are unchanged; the state columns are new.

Every wait is one of the two closed states of §2.4 (`act` with
`{:deliver :rd}` or `tail` with `{:deliver :return}`); an effectful
tail call raises the `tail` form of whichever row its effect falls in.
`saved` is `saved(pc+1, rd)` of §2.4.

| Safepoint kind | Raised by | State | Delivery |
|---|---|---|---|
| blocked read | `:stream-next rd c` → `blocked` | `act`, pending cell `c` | `rd` ← read value; nil on `end`; `:dao.stream/gap` on gap |
| blocked write | `:stream-put rd s v` → `full` | `act`, pending retains `W[v]` and the stream | `rd` ← written value on retry `ok` |
| FFI call, sent | `:ffi-call rd op args` → `ok` | `act`, pending `:ffi` (call id, response cell) | `rd` ← correlated `ok` value; `error` raises; bookkeeping removed |
| FFI call, retained | `:ffi-call` → `full` | `act`, pending `:ffi-request` (envelope verbatim) | none at this phase; on append `ok` the entry becomes the sent phase |
| effectful call, non-tail | `:call rd f args false` whose operator yields a blocking effect | `act`, pending per the effect kind | per the kind's row |
| effectful call, tail | `:call rd f args true` likewise | `tail`, pending per the effect kind | **through return** against `K` |
| link request | `:module/require` miss, request in hand | `act` or `tail`, pending `:link-request` | none at this phase; on append `ok` the entry becomes link response |
| link response | request appended; polling for the correlated response | same state, pending `:link-response` | none at this phase; a matching `ok` makes it install; refusal raises |
| install | the child runs | same state, pending `:install` + the body's install entry | on `linked`: the module symbol to `rd` or through return; on `refused`: raises |
| halt | `:halt r`, or `:return r` with empty `K` | — | not resumable; a `:yin.k/result` travels |

Two cases sit outside the table: an **explicit park** (`:park rd`) is a
**no-wait safepoint**: it is a migration safepoint like every row
above, but it raises no wait entry; its `act` record is the body's
parked record and a body of kind `:parked` names it by id (UCF §7.4.3
r5, "explicit park is the no-wait shape"). A **reified continuation**
(`:current-continuation`) is the one thing here that is *not* a
safepoint: it is a captured value, never a task state.
Whole-task quiescence (empty ready queue, UCF §7.4.1) and the existing
refusal conditions (unsupported observation states, pending close) are
unchanged.

The "effectful call, tail" row is the one place the rewrite is more
than notation: in the stack machine a tail effect's resume value landed
in `val` and the body's `:return` then delivered it; here the body has
no activation to resume into, so §2.4's `tail` state routes delivery
through `K`. Write-result and through-return delivery are distinguished
by the closed `deliver` record.

### 4.2 The UCF frame

`:yin.k/frame` (UCF §7.4.1) becomes, for the semantic profile:

```clojure
;; an act state (write-result delivery)
{:yin.k/state    :act
 :yin.k/segment  A
 :yin.k/pc       n                       ; the resume pc, already pc+1
 :yin.k/reason   …                       ; unchanged
 :yin.k/window   {r (encoded) …}         ; saved(pc, rd), sorted by r
 :yin.k/deliver  {:yin.k/deliver :rd :yin.k/rd r}
 :yin.k/env      {sym encoded …}         ; E, unchanged
 :yin.k/k        [ {:yin.k/frame-type :return
                    :yin.k/segment A :yin.k/pc n
                    :yin.k/env {…} :yin.k/window {…} :yin.k/rd r} … ]
 :yin.k/pending  {…}}                    ; unchanged, §7.4.3

;; a tail state (through-return delivery)
{:yin.k/state    :tail
 :yin.k/segment  A :yin.k/site n         ; the tail call site, for provenance only
 :yin.k/reason   …
 :yin.k/deliver  {:yin.k/deliver :return}
 :yin.k/k        [ … ]                   ; the frames delivery will pop
 :yin.k/pending  {…}}
```

`:yin.k/val`, `:yin.k/stack` and `:stack-base` are gone. The window's
**membership is validated**, not trusted: the receiver recomputes
`saved(pc, rd)` from A (§8.1) and refuses a window with a register
outside it (`:window-extra`), missing from it (`:window-missing`), or
containing the delivery destination (`:window-self`). Those three are
the membership diagnostics; the full frame validator also checks
representation (integer ids, sorted unique keys), body scope (every
register of the window belongs to the body `pc` is in), site/pc pairing
(`pc` is a safepoint successor of the right kind for `reason`;
`site` is a tail call), destination equality (`rd` is the `rd` of the
instruction at `pc−1`), delivery mode (`:act` carries `:rd`, `:tail`
carries `:return` and no window or env), return-frame sites (each
`:return` frame's `pc` follows a non-tail call whose `rd` it names, and
its window is `saved(pc, rd)` of that body), `K` structure (frames
innermost last, each well-formed), and pending compatibility with
`reason`. Two captures of equal state therefore encode identically, and
dead values never travel.

The row carrier (`yin.vm.ucf-transport-tuples.md` §5.2) changes
accordingly: `:regs` becomes `[segment-A pc env-cref window-cref
k-cref deliver-D]` for an `act` and a sibling `:tail-state [segment-A
site k-cref deliver-D]` is added; `deliver` is structural data of the
closed shape above; `:window` is a keyed row `[r₁ V₁ r₂ V₂ …]` sorted by
`r`; `:kframe` becomes `[segment-A pc env-cref window-cref rd]`; and
`:stack` and `stack-base` are removed. That amendment is written when
this design lands (§10 phase 6).

### 4.3 Foreign engines (UCF §7.4.2)

A foreign engine's static safepoint map keeps `:yin.safepoint/segment`,
`:yin.safepoint/pc` and `:yin.safepoint/engine`, replaces
`:stack-effect` with the per-pc **def and use sets** and `L(pc)` derived
from A (§8.1), and keeps `:yin.safepoint/layout` as a map from physical
location to virtual register. The reconstruction obligation is: for an
`act` state, produce the segment `A` and resume `pc`, every register in
`saved(pc, rd) = L(pc) − {rd}` (never the not-yet-produced
destination), `E`, `K` with each frame's validated window and `rd`, the
delivery record, the pending state, and the captured store context; for
a `tail` state, produce the segment and the tail `site`, `K` with its
validated frames, the delivery record and the pending state, and **no**
current window or `E`. A physical-slot map alone does not discharge it
when values are spilled, rematerialized, or shared between locations;
the engine owes the values, however it kept them. `lift(lower(frame)) =
frame` holds for admitted canonical frames, as today.

## 5. Scheduler, park, resume, FFI, modules

Unchanged in meaning (`yin.vm.semantic.md` §3.4, §3.5; UCF §7.6.3):
the wait set, `:parked`, the fresh-name counter, the FFI pair, the
module registry and `store-of` context all keep their semantics. What
changes is only what a parked record and a wait entry hold: a window and
a delivery record in place of an accumulator, a stack and a stack base.
Gensym is unaffected: register minting is compile-time numbering,
runtime `gensym` still advances the task's counter. FFI requests and
responses remain pending data, not register-map substitutes. The
module-store context travels in `E` across calls, returns, captures and
migration exactly as today; nothing in `C`'s change touches it.

## 6. Contract stamp and versions

The semantic execution contract changes: grammar, transitions,
restoration and wait shapes all differ. The stamp moves from `"v3"` to
**`"v4"`** (`vm/semantic-contract`); a `"v3"` vector is refused by
stamp (`:contract-mismatch`), with no migration path, per the owner's
clean-break rule. UCF's `:yin.code/contract` inside `:yin.k/contract`
follows. The UCF handoff body gains the frame shape of §4.2 under a new
body version (the row carrier's version 3 is revised in place before it
is implemented, since nothing of version 3 has landed; versions 0-2 keep
their bytes as historical contracts and describe the old machine).

The de Bruijn contracts `"b2"` and `"r2"` are **not re-versioned by
this document**. They are retained only if **both** their bytes on the
corpus (§8.4) **and** their normative contracts (descriptors,
validators, allocation rules, execution and live-operand rules) remain
unchanged; a changed contract is versioned even when the corpus
goldens happen to match, and a changed byte is versioned even when the
contract text did not move. What changes here is their input (A
instead of resolved tuples), which touches the register design's §4
allocation contract (§8.2), so `"r2"` is the one expected to move.

## 7. What A is, restated

A = `(jing/segment-key saturated-vector)`, Jing's default digest, over
the §2.3 positional tuples of one segment, body-local register ids
included, `live` sets and provenance excluded. Everything UCF §7.3
says about A (content address of the loader-resolved interpretation,
two load paths, projection to datoms with eid `(inc pc)`) stands with
the new table substituted. A is the only code identity; H and R are
checksums of §8's lowerings.

## 8. Derivations from A

Everything in this section is **derived, never persisted in A**, and
every derivation is a deterministic function of A under a pinned
algorithm, so that a receiver can recompute and verify it.

### 8.1 Static facts per pc: def, use, live

For each body, from the vector alone:

- `def(pc)` = `{rd}` for a value-producing instruction, `{}` otherwise;
  `use(pc)` = its register operands.
- `L(pc)`, the **live-in set** at `pc`: standard backward liveness over
  the body's explicit control-flow graph, in which the successors of
  `pc` are: `pc+1` for a non-terminator; the two targets of
  `:branch-false`; the target of `:jump`; **none** for `:return`,
  `:halt`, `:resume`, and a `:call` with `tail? true` (a runtime
  terminator). Layout-syntax instructions after a runtime terminator
  are nodes of the graph unreachable **from that terminal path**; they
  may have predecessors through a continuing arm's join or through
  other unreachable nodes, and their `L` is computed over the stated
  edges like any other node's and is never consulted on the terminal
  path. The fixed
  point is reached in one pass per nesting level because the graph is
  structured. At a `:return r`/`:halt r`, `L = {r}`.
- The **saved-window rule** (§2.4): a frame, wait, parked record or
  capture whose delivery is `{:deliver :rd r}` at resume pc `p` carries
  exactly `W↾(L(p) − {r})`. `L(p)` normally *does* contain `r` (later
  instructions read the delivered value); the subtraction is explicit,
  never assumed. A `tail` state carries no window.

These replace UCF §7.4.2's `stack-effect` static fact with `def`, `use`
and `L`; `lexically-required` (names a pc can read from `E`) is
unchanged.

### 8.2 A → R: the de Bruijn register image

1. **Resolve on the vector.** Build the closure-owner tree: each
   `:closure rd params body-pc` is the unique site owning body `body-pc`
   (§3.4 rule 6), so every body has one enclosing chain of parameter
   vectors. Rewrite `[:var rd name]` to `[:load-bound rd depth position]`
   when `name` is bound in the chain (innermost first), else
   `[:load-free rd name]`; rewrite `[:closure rd params body-pc]` to
   `[:closure rd arity body-pc]`; names go to the diagnostic side table.
   This is `yin.vm.debruijn/resolve-name` applied per `:var`, and the
   output is a stage value with no identity.
2. **Allocate per body**, by a pinned interval algorithm over **pc
   order** (pre-order ids are not pc-ordered, §3.3):
   - *Intervals.* Each virtual register `v` with at least one syntactic
     definition has one interval `[start(v), end(v)]` in pcs: `start(v)`
     is the pc of its first syntactic definition in textual order;
     `end(v)` is the greatest pc at which `v ∈ L(pc)` or `v` is
     syntactically defined, so an interval covers every definition
     (layout-syntax ones included, since they are emitted and must be
     in bounds) and every live point. A register with several leaf
     definitions (a conditional's `rd`, §3.2) therefore has **one
     interval spanning the whole `if(rd)` subtree** from its first arm
     definition to its last use: one physical slot is reserved across
     the entire structured extent, so an alternate arm's own values can
     never collide with the shared destination.
   - *Definition-less ids.* A minted id with no syntactic definition
     (§3.4 item 7) has no interval and no slot of its own. Where layout
     syntax must still name it (a wholly terminal body's structural
     `[:return r]`; an enclosing instruction in layout syntax after a
     terminal operand), the operand lowers to the **structural slot**
     `Tₖ`, where `k` is the scan's **peak** count of simultaneously
     assigned temporaries; the body's temporary count is then `k+1` so
     the operand is in bounds. The structural slot is never written,
     never read at runtime, and never a saved runtime value: saved
     windows are `saved(p, rd)` at *reachable* safepoints (§8.1), and
     no reachable instruction names a definition-less id. It **may**
     appear in a static `live` operand of an instruction in an
     unreachable CFG component (step 3), because those operands are
     computed over the whole graph; that membership is a static fact
     with no runtime capture. Deterministic by construction.
   - *Scan.* Walk pcs in increasing order. At pc `p`: first expire every
     interval with `end < p` (freeing its slot; among several, in
     increasing `end`, then increasing virtual id); then for each
     interval starting at `p` (in increasing virtual id), assign the
     lowest-numbered free temporary `Tᵢ`. **No same-instruction
     source/destination reuse**: a source whose interval ends at `p`
     is still assigned when `p`'s destination is allocated (expiry is
     `end < p`), so the destination never takes a source's slot. This
     is the conservative rule, chosen because it is fully specified
     here and affects emitted bytes; the kernel's read-before-write
     order is not relied upon.
   - *Reservation vs liveness.* An interval reserves a slot across its
     whole extent, which can exceed runtime liveness (a shared
     destination's slot is held through both arms). Reservation
     decides allocation; it never decides what is live. The `live`
     operands (step 3) and every saved window come from §8.1's
     liveness, and the physical-image `body-liveness` check stays an
     acceptance gate so that the two are never confused.
   - *Parameters.* `L0..Lₙ₋₁` are reserved and never coalesced with
     temporaries (register design §4.2); canonical parameters stay in
     `E` and are read through `:load-bound`.
   - *Count.* Physical temporaries = the maximum number of
     simultaneously assigned slots, plus one when the body has a
     structural slot; recorded in the body descriptor.
   No host map iteration order enters the result; every tie is broken
   by pc, then virtual id.
3. **Fill `live`.** Exactly the r2 boundary opcodes carry a `live`
   operand (register design §4.4: `:call`, `:stream-put`, `:stream-next`,
   `:ffi-call`, `:current-continuation`, `:park`). For each, `live` is
   the physical image of `L(pc+1) − {rd}`, computed **statically over
   the whole CFG of the body, unreachable components included**, and
   for a tail `:call` it is empty, because its successor set is empty.
   This is exactly what the register design's `body-liveness` computes
   over the physical image (it excludes the destination and treats
   tail calls as successor-less), and that function is the acceptance
   gate for the operands. A `live` operand on an instruction that is
   unreachable from every path is a static fact of the native format
   and never a runtime saved window; the two notions are kept apart as
   the reservation-vs-liveness note above keeps reservation apart from
   both.
4. **Layout.** Bodies and pcs keep A's layout; the register image's
   `{:bodies … :instructions …}` shape is filled from the body ranges.
   R = `register-hash` of the result, as a checksum.

What this replaces in R1 (register design §4.1-4.6): the input
validation (now §3.4), the expression-tree walk and its
target-register-passing recursion (now steps 1-2 over a vector), and
the consumption-event allocator (now explicit last-use linear scan).
`body-liveness` and the R2 descriptor are reused. The register image's
`lift` changes its output contract to the §2.3 table.

### 8.3 A → H: the de Bruijn stack image

1. Resolve as in §8.2 step 1.
2. **Parse** each body under §3.1 into its expression tree; the
   validator has already established that it parses.
3. **Adapt** the recovered trees to the input shape `lower-stack`
   consumes today, resolved tuples plus side table (register design
   §2.1): one resolved record per occurrence (occurrences stay
   positionally expanded, never re-shared), defaults as saturated,
   `:yin/tail?` from the `:call`'s `tail?`, binder arities from the
   `:closure` instructions, and bodies in the §3.3 queue order so that
   discovery order is preserved.
4. **Emit** with the existing `lower-stack` walk
   (`yin.vm.debruijn-linearize`), which is the named linearizer's
   flattening: operator, `:push`, operands with `:push` each, `:call
   argc tail?`, labels for conditionals, bodies out of line in
   discovery order. Absolute pcs are recomputed by that emission (the
   stack image's layout is its own). H = `image-hash` of the result, as
   a checksum.

This is a whole-body lowering by the recovered grammar, not an
instruction-by-instruction expansion; the Architect's finding that
`(f (g x) y)` needs `f` kept while `(g x)` runs is met because the
parse hands the emitter the tree and the emitter's own walk places the
pushes.

### 8.4 The byte-identity question

For every corpus program `P`: are the emitted vectors and their encoded
bytes of `lower-stack(adapt(parse(resolve(A(P)))))` equal to today's
`lower-stack(resolve(P))`, and likewise for the register image? The
comparison is of the actual vectors and canonical bytes, with the
checksums as a summary, never checksums alone. The emitter walk in
§8.3 is the same walk as today's, over the same tree, so H is expected
to hold; R depends on whether §8.2's interval scan reproduces R1's
reservation-and-release timing, which is not expected to hold exactly.
The decision is made from the evidence (§10 phase 5) **together with
§6's contract test**: a contract is kept only if bytes and normative
rules are both unchanged; otherwise it is re-versioned plainly, with
its goldens regenerated, and nothing pretends otherwise.

## 9. Blast radius

Per the Architect's enumeration, with this design's confirmation:

**Rewrite.** `yin.vm.semantic.md` §2, §4, §5 (this document replaces
them); `yin.vm` (contract and schema declarations, `semantic-contract`,
`semantic-bytecode-grammar`); `yin.vm.linearize` (both lanes); the
semantic kernel (`yin.vm.semantic`); `yin.vm.code` (operand table,
rules); UCF §7.3.2 (table), §7.4.1-7.4.2 (as §4 here), the value
grammar's frame and closure-marker arms and reified-continuation
encoding, the dependency census over frames; the v2 amendment's
semantic registers and frames (§5.1) and identity sections; the row
carrier §5.2 and its validation; the de Bruijn resolver/lowerer
adapters (§8.2 step 1, §8.3 step 3), the register design §2 and §4
(input, allocation, validation, per §8.2) and the register `lift`'s
output contract; the targets design's §3.7 reasons; the pipeline
composition that wires lowerings; the linker's derivation verification
(`yin.vm.linker.md` §3 amendment, §5.5) for the new lowerings; `ucf`,
`ucf.handoff`, `completion`; the semantic, code, linearizer,
continuation-invoke, safepoint, lift, handoff and cross-engine tests.

**Notational or focused.** code-as-tuples §5 (instruction contract) and
§7.2 (validator interface); UCF revision history and implementation
plan; the stack and register native VM documentation and restore
adapters, **only if their contracts survive §8.4** (otherwise they are
rewrites under a new version); blog Part Three's `St` paragraph;
REPL/session composition, encoder loaders, telemetry and benchmark
fixtures that name the old shape.

**Corpora.** Source programs, expected results, errors and effect
traces are preserved. Every semantic vector, A, pc, saved frame and
transported body is regenerated. Stage D/E scenarios and handoff/
checkpoint fixtures are re-encoded. H and R goldens are preserved only
if §8.4 says the bytes held.

**Untouched in substance.** The Universal AST and front ends; Rule R;
the stream protocol; FFI correlation; lease, custody, fencing,
custody/resource admission policy, durable dedup (executable admission,
by contrast, changed under phase 1 and is not untouched); integer and
scalar encoding; Stage E's holder/authority logic (its integration
evidence is invalid until rerun over the new transport, especially
crash cuts and successor publication).

## 10. Migration

Eight phases, each gated; the old evaluator survives only as an oracle
until phase 8.

1. **Identity.** Amend the A-only identity contracts. **Complete**
   (merged to master 2026-10-10, `2daa17fb`). Independent of this VM.
2. **Freeze.** This document's §3 (grammar, minting), §4 (windows,
   delivery, safepoint table), §6 (stamps), §3.4 (rejection rules) are
   reviewed and frozen. Gate: Architect sign-off on the frozen text.
3. **Linearizer, validator, static analysis.** `project` per §3.3, the
   loader rules of §3.4 (both load paths), **and the §8.1 analysis
   (def, use, `L`, `saved`)**, which the phase-4 evaluator needs for
   every call save and capture and so cannot wait for phase 5. Gate:
   deterministic vectors across JVM/Node/Dart on the corpus;
   malformed-input refusal rows for every rule including item 13;
   direct and datom load paths agree (code-as-tuples §7.2 law); `L`
   agrees with a reference implementation on the corpus.
4. **Evaluator.** `yin.vm.semantic-register` beside `yin.vm.semantic`.
   Gate: walker parity on the full B0 corpus (values, store, effects,
   halting) under the §2.5 correspondence; Rule R rows; effects,
   non-tail and tail recursion, pure and effectful tail completion,
   continuation invocation (including abortive invoke and ownership
   refusal), park/resume, gensym, module `store-of`, FFI
   retained-request and response phases.
5. **Derivations.** §8.2-8.3 implemented; the lexical-address law
   (`addresses(H) = addresses(R) = addresses(resolve(A))`) and
   behavioural parity of all three kernels on the corpus; the §8.4
   byte-and-contract decision recorded with its evidence.
6. **Continuation format.** §4.2 frames and both state shapes, the
   full frame validator, the safepoint harness of §4.1 (bidirectional
   lift/lower rows for every kind and both delivery modes, pending
   waits, nested and reified captures, module contexts, refusals); the
   row carrier amended. Gate: UCF §7.11's harness green **for every
   engine intended to reconstruct canonical frames** (the semantic
   kernel, the stack and register kernels through their layouts), not
   the semantic profile alone.
7. **Stage D/E rerun.** Handoff, fencing, custody, durability,
   crash-cut and cross-process acceptance over the new transport, three
   hosts.
8. **Cutover.** Delete `yin.vm.semantic` (old), rename
   `yin.vm.semantic-register` to `yin.vm.semantic`, remove the old H/R
   request paths and the `raise` dependency, finalize documentation.
   No compatibility shim.

## 11. Questions resolved by the freeze review

All four were answered by the Architect (phase-2 review, item 10) and
the answers are adopted above:

1. **The structural `:return` after a tail call**: kept, defined as
   layout syntax rather than "invariably unreachable" (§3.3 item 4);
   structural branch delimiters after terminal arms are likewise
   permitted and validated structurally (§3.4 items 8, 10).
2. **Body-local register ids**: kept, with validated body ownership and
   scope (§3.4 item 6).
3. **A carried live claim**: not carried; the window's keys are the
   membership claim and are validated by recomputation (§4.2).
4. **Tail effects**: routed directly through `K`; the engine seam's
   `restore-fn(base, entry, value)` needs no current activation, so no
   trampoline; the closed `tail` state and the semantic restore helper
   are specified in §2.4.

Nothing remains open in this document before freeze. The one item
that belongs to the phase-3 implementation brief rather than to the
design is the choice of the independent reference implementation that
§8.1's `L` is checked against.

## 12. What this does not change

The Universal AST; code identity (A, as ruled); the walker; the stream
protocol and every outcome vocabulary; custody and admission; the de
Bruijn descriptors (pending §8.4); the six invariants' satisfaction, in
the Architect's reading: no hidden global state (register counters are
lowering data; windows, destinations and saved state are explicit), no
implicit control flow, no callbacks, no shared mutable state (windows
are immutable values), interpretation separate from execution, and no
assumed graphs (the def/use graph is constructed from validated tuples,
never from provenance or an unavailable AST).
