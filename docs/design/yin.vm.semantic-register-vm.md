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
   observable behaviour on every program. The walker is the oracle this
   rebuild must match, and the register vector is the *image of the
   walker's expression evaluation*: one register per value the walker
   holds in flight (`:value`, the `:evaluated` vectors of its frames).
   That is what makes the grammar of §3 expression-structured rather
   than an arbitrary three-address program.
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

**call** with `f = W[fn-reg]`, `a = [W[r] for r in arg-regs]`:

$$\begin{aligned}
&f = \mathrm{clo}(ps,b,seg',E_c),\ \neg tail:&&
\to \langle seg',\,b,\,\{\},\,E_c[ps \mapsto a],\,S,\,K \Vert [\mathrm{ret}(seg,pc{+}1,E,\,W\!\restriction\!L(pc{+}1),\,rd)]\rangle\\
&f = \mathrm{clo}(ps,b,seg',E_c),\ tail:&&
\to \langle seg',\,b,\,\{\},\,E_c[ps \mapsto a],\,S,\,K\rangle\\
&f\ \text{primitive},\ f(a) = v:&&
\to \langle seg,pc{+}1,\,W[rd \leftarrow v],E,S,K\rangle\\
&f\ \text{primitive},\ f(a) = \epsilon:&&
\to \mathrm{effect}(\epsilon,\ \langle seg,pc{+}1,\,W\!\restriction\!L(pc{+}1),\,E,S,K\rangle,\ \mathrm{deliver}(rd, tail))
\end{aligned}$$

A callee activation starts with an **empty window**; its parameters are
in `E`. A non-tail call saves the caller's window restricted to the
registers live at the successor, `W↾L(pc+1)` (§8.1), together with the
delivery destination `rd`. A tail call saves nothing: the callee's
eventual return delivers through the frame already on `K`.

**return** with `K = K' ∥ [ret(seg', pc', E', W', rd')]`:

$$\to \langle seg',\,pc',\,W'[rd' \leftarrow W[I.value\text{-}reg]],\,E',\,S,\,K'\rangle$$

and with `K = []`: halted with result `W[value-reg]`. The result is
written **exactly once**, into the saved frame's destination, as the
frame is popped.

**Delivery.** Every suspended or deferred computation records where its
result goes, as `deliver(rd, tail)`: when `tail` is false the value is
written to `rd` in the restored window and control continues at the
recorded pc; when `tail` is true there is no current activation to
write into, and the value is delivered **through the return
transition** to the frame on top of `K` (or halts the task when `K` is
empty). This single rule covers primitive effects, `:park`,
`:current-continuation`, stream and FFI waits, and the walker's
`eval-call` completion. An effectful tail call therefore completes by
return, never by writing a register of a body that no longer runs.

**stream-next** (cursor ref `c = W[cursor-reg]`), by outcome:

$$\begin{aligned}
&ok\ v,\ c':&& \to \langle seg,pc{+}1,\,W[rd \leftarrow v],E,\,S[c \mapsto c'],K\rangle\\
&blocked:&& \to \text{parked}\ \{seg,\ pc{+}1,\ E,\ W\!\restriction\!L(pc{+}1),\ K,\ \mathrm{deliver}(rd,\mathrm{false}),\ \text{cell}\ c\}\\
&end:&& \to \langle seg,pc{+}1,\,W[rd \leftarrow \mathrm{nil}],E,S,K\rangle\\
&gap\ c':&& \to \langle seg,pc{+}1,\,W[rd \leftarrow \text{:dao.stream/gap}],E,\,S[c \mapsto c'],K\rangle
\end{aligned}$$

`stream-put`, `stream-make`, `stream-cursor`, `stream-close`, `gensym`,
`store-get`, `store-put`: as today, reading their operands from `W` and
writing `rd`; a blocking `stream-put` parks with
`deliver(rd, false)` and the retained value `W[value-reg]`.

**ffi-call** (`a = [W[r] for r in arg-regs]`): park frame
`{eval-call, seg, pc+1, E, W↾L(pc+1), K, deliver(rd, false)}` under a
fresh id `p`; append `request(p, I.op, a)`; on the correlated response,
its `ok` value is delivered to `rd` (or its `error` raises) at `pc+1`.

**park**: the record `{seg, pc+1, E, W↾L(pc+1), K, deliver(rd, false)}`
is written to `:parked` under a fresh id; the task halts with that
record as its value. The not-yet-produced value of `rd` is excluded from
the saved window by construction, since `rd` is not live-in at `pc+1`
(it is defined there by the delivery).
**resume** (`v = W[value-reg]`, `I.id` the parked id): the parked
configuration is restored and `v` is delivered per its recorded
`deliver`.
**current-continuation**: `W[rd ← κ]` where
`κ = {seg, pc+1, E, W↾L(pc+1), K, deliver(rd, false)}` reified as data.
Invoking `κ` with a value delivers that value to `rd` in the captured
window and continues at `pc+1`; `κ` itself is never captured inside its
own destination, because `rd` is excluded from `W↾L(pc+1)`.

### 2.5 Isomorphism with the walker

Each register corresponds to one walker in-flight value: an
application's operator value and each operand value are the walker's
`:evaluated` entries; the application's `rd` is the walker's `:value`
on return from the call; a conditional's `rd` is the walker's `:value`
after either arm. A `ret` frame corresponds to the walker's continuation
frame with its saved env and `:evaluated` prefix (the window) and the
place the result goes (`rd`). The parity test lane (§10, phase 4) runs
every corpus program on both and compares values, store, effect traces
and halting; the structural correspondence above is also checked by a
trace test that aligns walker steps with register writes.

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

```
body        ::= expr terminator
terminator  ::= [:return r] | [:halt r]            ; r = the body expr's rd
expr(rd)    ::= atom(rd)
              | call(rd)
              | if(rd)
              | define(rd)
              | effect(rd)
atom(rd)    ::= [:const rd v] | [:var rd name] | [:closure rd params body-pc]
call(rd)    ::= expr(f) expr(a₁) … expr(aₙ) [:call rd f [a₁ … aₙ] tail?]
if(rd)      ::= expr(c) [:branch-false c Lalt]
                expr-into(rd) [:jump Lend]
                Lalt: expr-into(rd)
                Lend:
define(rd)  ::= expr(rs) [:define rd name rs]
effect(rd)  ::= expr(args…) [<stream-or-ffi-op> rd …]
              | [:current-continuation rd] | [:park rd]
              | expr(v) [:resume parked-id v]        ; no rd; terminates the path
expr-into(rd) ::= an expr whose last instruction writes rd
```

`expr-into(rd)` is how both arms of a conditional write the same
register: the arm's own top-level expression is lowered with `rd` as its
destination. Nested conditionals compose (an arm may itself be an `if`
into the same `rd`).

### 3.2 Exclusive arm definitions

A register has at most one definition on any path. The two arms of a
conditional are the only case of two static definitions of one
register, and they are on exclusive paths. The validator checks:
(a) every definition of a register is either unique in the body or is
the last instruction of one arm of a conditional whose other arm's last
instruction defines the same register; (b) no instruction after `Lend`
redefines it.

### 3.3 The lowering walk and the minting order

The lowering `project : AST → vector` is the walker's evaluation order,
pinned here so that A is a function of the AST and this walk alone:

1. **Order**: operator, then operands left to right; a conditional's
   test, then its consequent, then its alternate (both emitted; one
   runs); `define`'s value operand; lambda bodies **out of line, after
   the sequence that references them, in discovery order** (first
   `:closure` emitted, first body placed), each body a contiguous range
   ending in `:return`; the main sequence ends in `:halt`.
2. **Minting**: registers are **body-local**, numbered from 0 in the
   order destinations are minted. A destination is minted **when its
   expression is entered, before any of its children** (pre-order). So
   for `(f (g x) y)`: `rd(f-call)=0`, then `rd(f)=1`, then
   `rd(g-call)=2`, `rd(g)=3`, `rd(x)=4`, then `rd(y)=5`; the emitted
   order is `[:var 1 f] [:var 3 g] [:var 4 x] [:call 2 3 [4] false]
   [:var 5 y] [:call 0 1 [2 5] tail?]`. A conditional's `rd` is minted
   on entry, before its test, and both arms are lowered `expr-into(rd)`.
   Pre-order is chosen over post-order for one reason: it is uniform
   across every node type, including the conditional, which must have
   its destination before either arm is lowered. The alternative (mint
   after children, special-case `if`) was rejected as two rules where
   one suffices.
3. **Every expression receives a destination**, used or not. There are
   no discarded expressions in the current AST (no sequencing form); if
   one is added, its non-final forms still mint and write registers,
   and the window rule (§8.1) keeps them out of frames.
4. **Tail flag**: copied from `:yin/tail?` as the front end marks it,
   as today. A tail `:call` is a terminator of its path: nothing follows
   it in its arm or body except the structural `:return` the grammar
   requires, which is unreachable at runtime and retained so that every
   body parses uniformly. (Open question §11.1 asks whether to drop it.)
5. **No moves, folds, or rewrites** are permitted in lowering. The
   projection emits exactly what the walk visits.

A is `(jing/segment-key vector)` over the saturated positional vector
exactly as UCF §7.3.2 states, with provenance in a side table outside
the hash, as today. Because every input to the walk is fixed above and
nothing depends on allocation, scheduling, host iteration order, or
names of anything but what the AST already names, **A is a
deterministic function of the AST and this walk**. A remains an exact
identity, not an alpha-equivalence: binder names are in the vector
(`:var name`, `:closure params`), so renaming a binder changes A while
the derived H and R, which resolve names away, may stay equal. That
asymmetry is the existing one and is preserved.

### 3.4 Well-formedness

The loader's rules, replacing `yin.vm.semantic.md` §2.6 items 6-8 and
adding register rules; items 1-5 (one segment, instruction shape, pcs
`0..n-1`, sorted, refs resolve) stand:

6. Every body is a contiguous pc range beginning at a `:closure`'s
   `body-pc` (or 0 for the main sequence), ending in `:return` (`:halt`
   for main), and parsing under §3.1. Bodies do not overlap or nest.
7. Register ids in a body are exactly `0..k-1` for some `k`, each with a
   definition; a body never names another body's register.
8. **Definite assignment**: on every path from the body's start to an
   instruction, every register it reads has been written. Checked by a
   forward walk over the structured control flow (§3.1 gives the only
   branching form).
9. **Exclusive arm definitions** (§3.2).
10. `:call` and `:ffi-call` argument vectors contain registers only;
    `tail?` is a boolean; a tail `:call` is followed in its path only by
    the structural terminator.
11. Rule R (`:reserved-name`): no `:var` names `yin/def`, no `:closure`
    binds it, no `:store-get`/`:store-put` key names it, every `:define`
    names a symbol other than `yin/def`. Unchanged.
12. `:resume`'s `parked-id` and `value-reg` are well kinded; `:resume`
    ends its path.

Violation is a load error naming the pc and rule, first defect wins,
as today. Both load paths (direct vector; projection to datoms) run the
same rules (code-as-tuples §7.2 unchanged).

## 4. Safepoints and the continuation format

### 4.1 The safepoint table (UCF §7.4.1, rewritten)

A safepoint is a transition at which the machine parks. The semantic
kinds are unchanged; the state columns are new.

| Safepoint kind | Raised by | Resume pc | Window saved | Delivery |
|---|---|---|---|---|
| explicit park | `:park rd` | `pc+1` | `W↾L(pc+1)` | `rd` ← resume value |
| blocked read | `:stream-next rd c` → `blocked` | `pc+1` | `W↾L(pc+1)` | `rd` ← read value; nil on `end`; `:dao.stream/gap` on gap |
| blocked write | `:stream-put rd s v` → `full` | `pc+1` | `W↾L(pc+1)`; retained value `W[v]` in pending | `rd` ← written value on retry `ok` |
| FFI call, sent | `:ffi-call rd op args` → `ok` | `pc+1` | `W↾L(pc+1)` | `rd` ← correlated `ok` value; `error` raises |
| FFI call, retained | `:ffi-call` → `full` | `pc+1` | as sent; envelope retained verbatim | as sent, once appended and correlated |
| effectful call, non-tail | `:call rd f args false` whose operator yields a blocking effect | `pc+1` | `W↾L(pc+1)` | `rd` ← the effect's resume value, per its kind |
| effectful call, tail | `:call rd f args true` likewise | — (no current activation) | none | **through return**: the value is delivered to the top frame of `K` as `:return` would deliver it |
| link request / response / install | the `:module/require` effect's three wait states | `pc+1` | `W↾L(pc+1)` | `rd` ← the module symbol on `linked`; refusal raises |
| halt | `:halt r`, or `:return r` with empty `K` | — | — | not resumable; a `:yin.k/result` travels |

`:current-continuation` is not a safepoint (it reifies and continues),
unchanged. The "effectful call, tail" row is new as a row: in the stack
machine a tail effect's resume value landed in `val` and the body's
`:return` then delivered it; here the body has no activation to resume
into, so the delivery rule of §2.4 routes it through `K`. This is the
one place the rewrite is more than notation, and the Architect's
requirement that "write-result" and "tail-return" delivery be
distinguished is met by the `deliver(rd, tail)` record.

### 4.2 The UCF frame

`:yin.k/frame` (UCF §7.4.1) becomes, for the semantic profile:

```clojure
{:yin.k/segment  A
 :yin.k/pc       n                       ; the resume pc, already pc+1
 :yin.k/reason   …                       ; unchanged
 :yin.k/window   {r (encoded) …}         ; W↾L(pc), sorted by r
 :yin.k/deliver  {:yin.k/rd r}           ; or {:yin.k/through-return true}
 :yin.k/env      {sym encoded …}         ; E, unchanged
 :yin.k/k        [ {:yin.k/frame-type :return
                    :yin.k/segment A :yin.k/pc n
                    :yin.k/env {…} :yin.k/window {…} :yin.k/rd r} … ]
 :yin.k/pending  {…}}                    ; unchanged, §7.4.3
```

`:yin.k/val` and `:yin.k/stack` are gone; `:stack-base` is gone. The
window's **membership is validated**, not trusted: the receiver
recomputes `L(pc)` from A (§8.1) and refuses a window with a register
outside it (`:window-extra`), missing from it (`:window-missing`), or
containing the delivery destination (`:window-self`). Two captures of
equal state therefore encode identically, and dead values never travel.

The row carrier (`yin.vm.ucf-transport-tuples.md` §5.2) changes
accordingly: `:regs` becomes `[segment-A pc env-cref window-cref
deliver]`, `:window` is a keyed row `[r₁ V₁ r₂ V₂ …]` sorted by `r`,
`:kframe` becomes `[segment-A pc env-cref window-cref rd]`, and
`:stack` and `stack-base` are removed. That amendment is written when
this design lands (§10 phase 6).

### 4.3 Foreign engines (UCF §7.4.2)

A foreign engine's static safepoint map keeps `:yin.safepoint/segment`,
`:yin.safepoint/pc` and `:yin.safepoint/engine`, replaces
`:stack-effect` with the per-pc **def and use sets** and `L(pc)` derived
from A (§8.1), and keeps `:yin.safepoint/layout` as a map from physical
location to virtual register. The reconstruction obligation is: at a
safepoint, produce every register in `L(pc)`, `E`, `K` with each frame's
window and `rd`, the delivery record, the pending state, and the
captured store context. A physical-slot map alone does not discharge it
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
this document**. Their descriptors and execution contracts are
unchanged; what changes is their input (A instead of resolved tuples).
Whether their bytes survive is the byte-identity question of §8.4,
decided by evidence, and a re-version follows only if the evidence says
the bytes move.

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
- `L(pc)`, the **live-in set** at `pc`: registers read at or after `pc`
  on some path without an intervening write, computed by standard
  backward liveness over the body's structured control flow (§3.1 gives
  the only branching form, so the fixed point is reached in one pass
  per nesting level). At a `:return`/`:halt`, `L` is `{value-reg}`; at
  a tail `:call`, `L` after it is `{}`.
- The **window rule**: a frame or capture at resume pc `p` carries
  exactly `W↾L(p)`, and `rd ∉ L(p)` always holds for the instruction at
  `p−1` that delivers to `rd`, because `rd` is defined, not used, by the
  delivery.

These replace UCF §7.4.2's `stack-effect` and `lexically-required`
static facts; `lexically-required` (names a pc can read from `E`) is
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
2. **Allocate per body.** Virtual registers are definition-ordered by
   construction (pre-order minting). Linear scan: process pcs in order;
   at a definition assign the lowest physical temporary `Tᵢ` not live;
   a register's lifetime ends at its last use (from §8.1); the two
   exclusive arm definitions of one virtual register receive the same
   physical register (they are one lifetime); parameters are `L0..Lₙ₋₁`
   and are never coalesced with temporaries (register design §4.2).
   Tie-breaks: lowest virtual id. Physical count = `n + max live
   temporaries`, recorded in the body descriptor.
3. **Fill `live`.** For every boundary instruction (`:call`, stream
   ops, `:ffi-call`, `:current-continuation`, `:park`), the `live`
   operand is `L(pc+1)` mapped to physical registers, which is what the
   register design's `body-liveness` computes today.
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
3. **Emit** the tree with the existing `lower-stack` walk
   (`yin.vm.debruijn-linearize`), which is the named linearizer's
   flattening: operator, `:push`, operands with `:push` each, `:call
   argc tail?`, labels for conditionals, bodies out of line in discovery
   order. H = `image-hash` of the result, as a checksum.

This is a whole-body lowering by the recovered grammar, not an
instruction-by-instruction expansion; the Architect's finding that
`(f (g x) y)` needs `f` kept while `(g x)` runs is met because the
parse hands the emitter the tree and the emitter's own walk places the
pushes.

### 8.4 The byte-identity question

For every corpus program `P`: does `H(lower-stack(parse(resolve(A(P)))))`
equal today's `H(lower-stack(resolve(P)))`, and does R likewise? The
emitter walk in §8.3 is the same walk as today's, over the same tree, so
H is expected to hold; R depends on whether §8.2's explicit linear scan
reproduces R1's reservation-and-release timing, which is not expected
to hold exactly. The decision is made from the evidence (§10 phase 5):
equal bytes keep `"b2"`/`"r2"` as they are; unequal bytes re-version the
affected contract plainly, with its goldens regenerated, and nothing
pretends otherwise.

## 9. Blast radius

Per the Architect's enumeration, with this design's confirmation:

**Rewrite.** `yin.vm.semantic.md` §2, §4, §5 (this document replaces
them); `yin.vm.linearize` (both lanes); the semantic kernel
(`yin.vm.semantic`); `yin.vm.code` (operand table, rules); UCF §7.3.2
(table), §7.4.1-7.4.2 (as §4 here), the value grammar's frame arms;
the v2 amendment's semantic registers and frames (§5.1) and identity
sections; the row carrier §5.2 and its validation; the de Bruijn
register design §2 and §4 (input and lowering, per §8.2) and the
register `lift`'s output contract; the targets design's §3.7 reasons;
`ucf`, `ucf.handoff`, `completion`; the semantic, code, linearizer,
continuation-invoke, safepoint, lift, handoff and cross-engine tests.

**Notational or focused.** code-as-tuples §5 (instruction contract) and
§7.2 (validator interface); UCF revision history and implementation
plan; the stack and register native VM documentation and restore
adapters; blog Part Three's `St` paragraph; REPL/session composition,
encoder loaders, telemetry and benchmark fixtures that name the old
shape.

**Corpora.** Source programs, expected results, errors and effect
traces are preserved. Every semantic vector, A, pc, saved frame and
transported body is regenerated. Stage D/E scenarios and handoff/
checkpoint fixtures are re-encoded. H and R goldens are preserved only
if §8.4 says the bytes held.

**Untouched in substance.** The Universal AST and front ends; Rule R;
the stream protocol; FFI correlation; lease, custody, fencing,
admission, durable dedup; integer and scalar encoding; Stage E's
holder/authority logic (its integration evidence is invalid until rerun
over the new transport, especially crash cuts and successor
publication).

## 10. Migration

Eight phases, each gated; the old evaluator survives only as an oracle
until phase 8. (Phase 1 is in flight as of this writing.)

1. **Identity.** Amend the A-only identity contracts (done:
   `a-only-code-identity` branch). Independent of this VM.
2. **Freeze.** This document's §3 (grammar, minting), §4 (windows,
   delivery, safepoint table), §6 (stamps), §3.4 (rejection rules) are
   reviewed and frozen. Gate: Architect sign-off on the frozen text.
3. **Linearizer and validator.** `project` per §3.3 and the loader
   rules of §3.4, both load paths. Gate: deterministic vectors across
   JVM/Node/Dart on the corpus; malformed-input refusal rows for every
   rule; direct and datom load paths agree (code-as-tuples §7.2 law).
4. **Evaluator.** `yin.vm.semantic-register` beside `yin.vm.semantic`.
   Gate: walker parity on the full B0 corpus (values, store, effects,
   halting); Rule R rows; effects, non-tail and tail recursion,
   continuation invocation, park/resume, gensym, module `store-of`.
5. **Derivations.** §8.1-8.3 implemented; the lexical-address law
   (`addresses(H) = addresses(R) = addresses(resolve(A))`) and
   behavioural parity of all three kernels on the corpus; the §8.4
   byte-identity decision recorded with its evidence.
6. **Continuation format.** §4.2 frames, window validation, the
   safepoint harness of §4.1 (bidirectional lift/lower rows for every
   kind, pending waits, nested and reified captures, module contexts,
   refusals); the row carrier amended. Gate: UCF §7.11's harness green
   for the semantic profile.
7. **Stage D/E rerun.** Handoff, fencing, durability, crash-cut and
   cross-process acceptance over the new transport, three hosts.
8. **Cutover.** Delete `yin.vm.semantic` (old), rename
   `yin.vm.semantic-register` to `yin.vm.semantic`, remove the old H/R
   request paths and the `raise` dependency, finalize documentation.
   No compatibility shim.

## 11. Open questions

1. **The structural `:return` after a tail call** (§3.3 item 4). Keeping
   it makes every body parse uniformly and keeps §3.4 rule 6 simple;
   dropping it saves one unreachable instruction per tail-position body.
   Recommendation: keep; uniformity of the grammar is worth more than
   one tuple.
2. **Body-local vs segment-wide register ids** (§2.2). Body-local was
   chosen so that windows are per activation and small and so that the
   de Bruijn register image's per-body banks map directly. The
   alternative, segment-wide ids, would let a validator check
   cross-body misuse by range alone but makes windows carry an
   activation's position in a global space. Recommendation: body-local,
   as written.
3. **Whether `L(pc)` should be carried in UCF frames as a checkable
   claim** (a `:yin.k/live` key the receiver verifies) or purely
   recomputed. Recommendation: recomputed; a claim adds bytes and a
   second way to be wrong.
4. **Delivery through return for tail effects** (§2.4): confirm that
   the engine's `handle-effect` restore path can route a resume value to
   `K`'s top frame without a current activation, or whether the wait
   entry should synthesize a one-instruction trampoline activation.
   Recommendation: route through `K`; the trampoline is the stack
   machine's shape reappearing.

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
