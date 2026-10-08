Completed-GMT: 2026-09-12 14:09:20 GMT
Completed-Local: 2026-09-12 21:09:20 +07
Coding-Agent: claude
Session-ID: 8dbd68cd-b008-4da3-a9e9-545cf94603e8

# yin.vm.semantic on dao.stream — architectural specification

Role: Lead System Architect. Scope: design only; no repository file other
than this one was changed.

## 0. Summary of decisions

1. **The semantic VM is a linear machine over executable datoms, not a graph
   walker.** v1 `yin.vm.semantic` walked the `:yin/*` AST *graph* by entity
   lookup and was measured slower than the ast-walker (1.69x vs 1.32x of the
   register baseline, `docs/cesk-space-optimization.md`). The new VM executes
   a **code segment**: a dense vector of instruction entities whose order is
   an explicit `:yin.code/pc` fact. Sequencing is `pc + 1`; the walker's
   per-operand continuation frames disappear.
2. **The stream delivers segments; the program counter indexes a loaded
   image.** `dao.stream` has no `seek` and cursors are opaque, so a
   branch cannot be a cursor move. The unit of transfer on the program
   stream is one segment (a batch of datoms, exactly what the observer
   already hands a loader). The VM never calls `next` per instruction.
   This is `streams-all-the-way-down.md` §4 ("batches, not scalars") applied
   to code, and it is the only reading consistent with the contract.
3. **Datoms are the truth; the loaded image is a memo.** The loader turns
   `:yin.code/*` datoms into a host array of decoded instructions with
   integer opcodes (`yin.vm/opcode-table` and `opcase`, which already
   exist). The hot loop touches only the array. That is how execution reaches
   bytecode speed while every instruction remains a queryable, provenance-
   carrying datom.
4. **Lowering is a separate interpreter,** `yin.vm.linearize`: canonical
   `:yin/*` AST datoms in, `:yin.code/*` datoms out, pure, ahead-of-time at
   the load boundary. Every instruction carries `:yin.code/source`, a ref to
   the AST node it was lowered from, so the semantic view renders up from the
   executable view without loss.
5. **Everything below the instruction dispatch is shared with the ast-walker
   unchanged**: `yin.vm.engine` (effects, wait set, park/resume, run
   loop), `yin.vm.ffi` (bridge), `yin.vm.stream-observer` (program
   ingress), `yin.vm.module`, `dao.runtime`. The two evaluators are two
   interpretations of one canonical program, coexisting under
   `:vm-type`, with the walker remaining the parity oracle.
6. **A continuation is `{segment pc env stack k}`, plain data.** Segments are
   entities; a parked or shipped continuation names its segment by id and
   travels with (or is resolved against) the segment's datoms.

Everything below states the exact contracts these decisions imply.

---

## §1. Foundational axioms and invariant compliance

### 1.1 The four axioms

**Everything is a Stream.** Code arrives on a program stream as a segment
value; execution is consumption of that stream by an observer, and then
consumption of the segment by the machine. Effects the program performs
(`:stream-put`, `:stream-next`, FFI) are emissions on streams the composition
supplied, exactly as for the walker. Optional execution traces are emissions
too (§3.6). What is *not* a stream is the instruction fetch inside a loaded
segment, and this is deliberate: a boundary belongs "wherever you need
decoupling — and nowhere else" (`streams-all-the-way-down.md` §1). Fetch has
one consumer, one order, no replay need, and no host boundary; putting a
stream there is the per-value tax §4 of that note names as hopeless.

**Interpretation Creates Semantics.** `[e :yin.code/op :call]` means nothing
until `yin.vm.semantic` reads it. The same datoms can be read by a query
("all call sites of this closure"), a renderer (back to source through
`:yin.code/source`), a verifier (every block ends in a terminator), or a
different VM. The mapping from the keyword mnemonic in the datom to the
integer opcode in the image is the loader's interpretation, not a fact in
the datom. `:yin.code/tail?` is a property of a call in the datoms; only the
image decides it is a distinct `tailcall` dispatch.

**Code and State are Datoms.** Instructions are entities with attributes.
Execution state is plain data: the control is `{:segment id :pc n}`, the
environment a map, the operand stack a vector, the continuation a vector of
frames each `{:pc :env :stack-base :segment}`. The optional trace vocabulary
(§3.6) deposits exactly these as `[e a v t m]` with `t` the step counter,
the "endgame" of `cesk-space-optimization.md` §6: execute fast, emit the
machine image as datoms rather than interpret from them.

**Everything is a Continuation.** Park writes `{segment pc env stack k}` into
`:parked`; resume reads it back. Nothing in that map is a host object except
values the program itself put in `env` or on the stack (host functions
resolved from primitives, stream handles from the store), which is the same
serialization limit the walker has today. A continuation shipped through a
stream names its segment by id; the receiving host resolves the id against
segment datoms it holds or was sent alongside.

### 1.2 The six invariants

| Invariant | How the design honors it |
|---|---|
| No hidden global state | Opcode table is a value in `yin.vm`. Segments live in the VM record under `:code`. Primitives, modules, `:make-stream`, bridge handlers are all supplied at construction, as for the walker. No `defonce`, no registry. |
| No implicit control flow | Every transfer of control is an instruction datom: `:jump`, `:branch-false`, `:call`, `:return`, `:halt`. Sequencing is the explicit `:yin.code/pc` order. No fallthrough that is not a `pc + 1` visible in the datoms. |
| No callbacks | Host effects go through `engine/handle-effect` and the FFI request/response pair; a blocked read parks in the polling wait set. The VM invokes nothing and is invoked by nothing. |
| No shared mutable state | The image array is built once per segment and never written after load. The hot loop keeps registers in `loop` locals; the persistent VM record is the only state that escapes a step. No mutable deftype fields (a cljd trap on record). |
| No layer collapsing | Lowering (AST datoms → code datoms), loading (code datoms → image), and execution (image → transitions) are three functions in two namespaces with data between each. The walker keeps interpreting the AST; nothing in the walker changes. |
| No assumed graphs | Branch targets and closure bodies are refs; the loader constructs the pc index from tuples in an explicit pass and resolves refs against it. A dangling ref is a load error naming the entity, never a runtime lookup. |

---

## §2. The linear executable datom specification

### 2.1 Tuple format

Executable datoms are canonical 5-tuples `[e a v t m]`, produced the same
way `yin.vm/ast->datoms` produces AST datoms: negative tempids for `e`,
`t` = 0 unless the linearizer is given one, `m` = `dao.datom/default-op`.
Attributes live under the `:yin.code/*` namespace so that
`engine/executable-program-datom?` (which selects `:yin/*`) does not confuse
a code segment with an AST program, and a query can select either view.

### 2.2 The segment

A segment is one entity plus its instructions. It is the unit a program
stream carries (one batch value), the unit the loader accepts, and the unit
a closure or continuation names.

| Attribute | Value | Meaning |
|---|---|---|
| `:yin.code/type` | `:segment` | Marks the segment entity. |
| `:yin.code/length` | int | Number of instructions; pcs are `0 .. length-1`, dense. |
| `:yin.code/derived-from` | ref (AST root eid) | Provenance; optional when hand-assembled. |
| `:yin.code/hash` | string | Reserved: content address of the canonical instruction datoms, for dedup and verification (`streams-all-the-way-down.md` §6.3). Not required in Phase 1. |

Entry is pc 0 by rule. Making entry a fact would let a segment disagree with
itself; a rule cannot.

### 2.3 The instruction

Every instruction entity carries three structural facts and one or more
operand facts.

| Attribute | Value | Required on |
|---|---|---|
| `:yin.code/segment` | ref (segment eid) | every instruction |
| `:yin.code/pc` | int | every instruction |
| `:yin.code/op` | keyword mnemonic (below) | every instruction |
| `:yin.code/source` | ref (AST node eid) | every lowered instruction; absent when hand-assembled |

Linearity is therefore an **explicit fact**, `:yin.code/pc`, not the vector
order of the batch and not entity-id arithmetic. Entity ids are tempids that
a transactor may remap; vector order is a transport accident. The
well-formedness rule (§2.6) requires the batch to be *sorted* by pc so a
loader can stream it, but the pc fact is the truth.

Branch and closure targets are **refs to instruction entities**
(`:yin.code/target`, `:yin.code/body`), not pc integers. The loader resolves
each ref to a pc through the index it builds. This keeps the datom semantic
("this branch goes to that instruction"), survives id remapping when both
ends are remapped together, and is checkable.

### 2.4 The core semantic primitives

Machine model: an **accumulator machine with an operand stack** (§4). `val`
is the accumulator; `St` the operand stack; `K` the continuation of return
frames. This is the stack-class formulation `streams-all-the-way-down.md` §3
says survives the log-structured reading; a register model would not.

| `:yin.code/op` | Operand attributes | Effect on the machine |
|---|---|---|
| `:const` | `:yin.code/value v` | `val ← v` |
| `:var` | `:yin.code/name sym` | `val ← resolve(E, S, prims, modules, sym)` |
| `:closure` | `:yin.code/params [..]`, `:yin.code/body ref` | `val ← {:type :closure :params .. :entry pc(body) :segment seg :env E}` |
| `:push` | — | `St ← St ⧺ [val]` |
| `:call` | `:yin.code/argc n`, `:yin.code/tail? bool` | pop `n` args and the operator below them; apply (§4.3) |
| `:return` | — | pop frame from `K`; restore `pc`, `E`, stack base; `val` unchanged; empty `K` halts |
| `:jump` | `:yin.code/target ref` | `pc ← target` |
| `:branch-false` | `:yin.code/target ref` | `pc ← val ? pc+1 : target` |
| `:halt` | — | halt with `val` as the result |
| `:gensym` | `:yin.code/prefix` | `val ← fresh id`; id counter advances |
| `:store-get` | `:yin.code/key` | `val ← S[key]` |
| `:store-put` | `:yin.code/key`, `:yin.code/value` | `S[key] ← v; val ← v` |
| `:stream-make` | `:yin.code/buffer` | effect `:stream/make` via engine |
| `:stream-put` | — | target ref popped from `St`, value in `val`; effect `:stream/put` |
| `:stream-cursor` | — | source ref in `val`; effect `:stream/cursor` |
| `:stream-next` | — | cursor ref in `val`; effect `:stream/next` |
| `:stream-close` | — | source ref in `val`; effect `:stream/close` |
| `:park` | — | `engine/park-continuation` with the current frame |
| `:resume` | `:yin.code/parked-id` | resume value in `val`; `engine/resume-continuation` |
| `:current-continuation` | — | `val ← {:type :reified-continuation :segment :pc :env :stack :k}` |
| `:ffi-call` | `:yin.code/ffi-op kw`, `:yin.code/argc n` | pop `n` args; park-and-call over the FFI pair |

This is the AST vocabulary of `yin.vm/ast->datoms` made linear, one for
one, plus the three sequencing primitives (`:push`, `:jump`, `:branch-false`)
and `:halt`. `:yin/macro-expand` and `:vm/store-update` are outside both
evaluators' supported corpus and are rejected at lowering with an error
naming the node.

**Opcode integers.** `yin.vm/opcode-table` already assigns integers to
`:literal :load-var :lambda :call :return :branch :jump :gensym :store-get
:store-put :stream-make :stream-put :stream-cursor :stream-next :stream-close
:park :resume :current-cont :tailcall :dao.stream.apply/call`. The loader
maps mnemonics onto that table (`:const`→`:literal`, `:var`→`:load-var`,
`:closure`→`:lambda`, `:branch-false`→`:branch`, `:ffi-call`→
`:dao.stream.apply/call`, `:call`+`:tail? true`→`:tailcall`) and the table
gains `:push 22` and `:halt 23`. `:move` stays unused. Keeping the datom
mnemonics semantic and the image integers mechanical is the point: a query
asks for `:call`, the dispatch switches on `5` or `20`.

### 2.5 Constants and literals

A literal lives inline in the `v` slot of `:yin.code/value`, polymorphic
exactly as `:yin/value` is today (numbers, strings, keywords, vectors, maps,
sets, nil). Host functions never appear in code datoms; a primitive is named
by symbol in `:var` and resolved at run time through the supplied
`:primitives`, which is how the walker already avoids putting host objects in
the program. A shared or large constant pool (`:yin.code/const-ref` to a
constant entity) is reserved and not needed for the current corpus.

### 2.6 Well-formedness (checked by the loader, tested in Phase 0)

1. Exactly one entity with `:yin.code/type :segment` per batch.
2. Instruction pcs are exactly `0 .. length-1`, each once.
3. The batch is sorted by pc (so a loader can fill the array in one pass).
4. Every `:yin.code/target` and `:yin.code/body` resolves to an instruction
   of the same segment.
5. Every basic block ends in a terminator (`:jump`, `:return`, `:halt`) or
   falls into a labelled successor; pc `length-1` is a terminator.
6. Every `:call`/`:ffi-call` has a non-negative `:yin.code/argc`.

Violation is a load error naming the entity and rule. The loader is total
over the outcomes of its inputs; it does not guess.

### 2.7 A worked segment

`((fn [x] (+ x 1)) 10)` lowers to (pcs shown, refs written as `→pc`):

```
seg   :yin.code/type :segment   :yin.code/length 14
0  :closure   params [x]  body →6
1  :push
2  :const     10
3  :push
4  :call      argc 1  tail? false
5  :halt
6  :var       +               ; body of the lambda, out of line
7  :push
8  :var       x
9  :push
10 :const     1
11 :push
12 :call      argc 2  tail? true
13 :return
```

The main sequence (pcs 0–5) ends in `halt`; the body (pcs 6–13) ends in
`return`. In datoms, pc 4 is:

```
[-20 :yin.code/segment -1  0 :db/add]
[-20 :yin.code/pc       4  0 :db/add]
[-20 :yin.code/op   :call  0 :db/add]
[-20 :yin.code/argc     1  0 :db/add]
[-20 :yin.code/tail? false 0 :db/add]
[-20 :yin.code/source  -16 0 :db/add]   ; the :application AST node
```

Six datoms for a call site; five for most instructions. Load cost is linear
in datoms once per segment. Execution cost is per instruction, never per
datom.

---

## §3. The semantic VM as a DaoStream v2 interpreter

### 3.1 Consumption: observer, loader, image

The VM implements `IVM`; program ingress is exactly the walker's shape:

- The host composes a program medium, attaches `yin.vm.stream-observer`
  to it, and drives `observer/run-on-stream` with `engine/ready-for-ingress?`,
  a loader, and `vm/run`. The observer owns the handle, the cursor, and gap
  accounting. The VM holds no program stream.
- The loader for this evaluator is `semantic/vm-load-program`: it accepts a
  batch of `:yin.code/*` datoms, validates §2.6, builds the image, stores
  it under `:code {segment-id image}`, and sets control to
  `{:segment id :pc 0}`. A composition that carries `:yin/*` AST datoms on
  its program stream hands the observer `(comp semantic/vm-load-program
  linearize/lower)` instead; the loader is composition-supplied, so no
  evaluator learns which form travels. `yin.repl.core/make-session` is
  where that choice is made.

The step loop is then: `(aget code pc)` → integer `case` → transition. No
handle, no cursor, no `next` inside a segment.

### 3.2 Why a branch is not a cursor move

The contract's cursor is "a position, not a claim" and comes only from the
stream: minted at `:oldest`/`:newest`, returned as a successor, or recovered
from a `gap`. There is no `seek`, and reaching for a transport-owned position
anchor is forbidden for contract-generic code. Three designs were weighed:

| Design | Contract status | Cost | Verdict |
|---|---|---|---|
| Instruction stream, `next` per fetch, branch by re-minting a block's `:oldest` | legal only if every basic block is its own stream, delivered as descriptors | one stream operation per branch, one attach per closure body, per-value boundary tax on every instruction | rejected: it is the per-scalar boundary §4 of the streams note names as hopeless, and a loop body would re-mint on every iteration |
| Transport-owned position anchors as jump targets | illegal for contract-generic code (Surfaces) | — | rejected |
| Segment as batch value; pc indexes the loaded image | legal; the observer already delivers batches | one `next` per segment | **adopted** |

The cursor-as-program-counter thesis of the streams note remains true where
it applies: an execution *trace* emitted to a stream is a straight line, and
a debugger's cursor over it is a pc in time (§3.6).

### 3.3 Read outcomes at the two boundaries

*Program boundary* (observer, generic): `ok` loads and runs; `blocked` ends
the round with the cursor retained; `end` ends the session; `gap` commits
the recovery cursor and counts the loss (an evicted segment is a program
that never ran; the REPL latch stays host policy); `cursor-mismatch`,
`invalid-cursor`, `transport-error` throw naming the outcome. Unchanged from
the divergence register.

*Data boundary* (instructions, via `engine/handle-effect`): identical to the
walker because the handlers are shared.

| Instruction | `ok` | `blocked`/`full` | `end` | `gap` | terminal |
|---|---|---|---|---|---|
| `:stream-next` | `val ← value`, store cursor advanced | park with a wait entry (§3.5) | `val ← nil` | `val ← :dao.stream/gap`, cursor advanced to recovery | error naming outcome |
| `:stream-put` | `val ← value` | park (`full`) | — | — | `closed`, `invalid-value`, `transport-error` are errors |
| `:stream-cursor` | `val ← cursor-ref` | — | — | — | `invalid-anchor`, `closed`, `transport-error` are errors |
| `:stream-make` | `val ← stream-ref` | — | — | — | `invalid-spec`, `not-found`, `transport-error` are errors |
| `:stream-close` | `val ← nil` | — | — | — | `{ok}` only |

Under a ring-buffer composition `full` never occurs and loss surfaces as a
reader `gap`; the retention divergence stands as written.

### 3.4 FFI through `dao.stream.apply`

`:ffi-call` pops `argc` arguments and runs the semantic `park-and-call`:
check the pair (`ffi/require-call-pair!`) **before** parking; park the frame
`{:type :dao.stream.apply/eval-call :segment :pc (pc+1) :env :stack :next
k}`; `apply2/put-request!` on call-in; on `ok` add the call-out wait entry
correlated by the parked id; on `full` retain the request in a
`:request-sent` wait entry, as the walker does; the other outcomes fail the
call naming the outcome. The host side (`ffi/bridge-step`, `ffi/maybe-run`)
is used unchanged. `call-result` and `call-response-wait-entry` are private
in `ast_walker.cljc` today; Phase 0 lifts both into `yin.vm.ffi` so that
correlation checking is written once (this is the one edit to an existing
file the roadmap requires).

### 3.5 Scheduler, park, resume

The VM record carries the engine's scheduler fields (`:ready-queue`,
`:wait-set`, `:parked`, `:id-counter`, `:store`, `:blocked?`, `:halted?`)
with the same meanings. Wait entries built by the semantic `park-entry-fns`
are `{:segment :pc :env :stack :k :reason :cursor-ref/:stream-id}`. The
restore function is

```
semantic-restore : base entry val → base with control {:segment :pc},
                                       env, stack, k, val from entry
```

and is handed to `engine/check-wait-set`, `engine/resume-from-run-queue`,
`engine/handle-effect`, and `engine/resume-continuation` exactly as
`ast-walker-restore` is. `engine/run-loop` drives `run`. Nothing in
`engine.cljc` changes.

### 3.6 Observation and traces

Program observation is `stream-observer`, above the VM. For *execution*
observation the design reserves a trace vocabulary emitted at park points
and, when enabled, per instruction:

```
[cfg :yin.trace/segment seg t] [cfg :yin.trace/pc pc t] [cfg :yin.trace/val v t]
[cfg :yin.trace/env-ref e t]   [cfg :yin.trace/k-depth d t]
```

with `t` the step counter. This is the §6 endgame of the cesk-space plan:
the machine executes from the image and *emits* its configuration, so
`as-of` and causal debugging are queries over a stream, not interpretation
from one. `yin.vm.telemetry` is a stub that rejects a non-nil option; the
trace lands there when telemetry is designed, and nothing in Phase 1–3
depends on it. It is listed so the hot loop is written with the emission
seam in one place (the `cesk-return` equivalent) rather than retrofitted.

---

## §4. CESK semantics on linear datoms

### 4.1 Configuration

$$\langle C, E, S, K \rangle \quad\text{where}\quad
C = \langle seg, pc, val, St \rangle$$

- **C (control)**: the segment id, the program counter, the accumulator
  `val`, and the operand stack `St` (a vector; `St` is control, not store,
  because it is part of what the machine is *doing*, and it is saved in
  frames).
- **E (environment)**: a persistent map `sym → value`, extended on closure
  entry with `merge closure-env (zipmap params args)`, as the walker does.
  Lexical addressing is reserved (§6.4).
- **S (store)**: the map `key → value` holding `yin/def` results, stream
  handles, cursor entries, and the FFI pair — unchanged from `engine`.
- **K (continuation)**: a vector of frames, innermost last. Frame kinds:
  `{:type :return :segment :pc :env :stack-base}` for calls, and the
  effect-continuation frames the engine already defines
  (`:dao.stream.apply/eval-call`, `:request-sent`), extended with
  `:segment :pc :stack`.

Let `I = code[seg][pc]` be the decoded instruction and `I.x` its operands.

### 4.2 Transitions for the core instructions

$$\begin{aligned}
&\text{const}:&& \langle seg,pc,val,St,E,S,K\rangle \to \langle seg,pc{+}1,\,I.v,\,St,E,S,K\rangle\\
&\text{var}:&& \to \langle seg,pc{+}1,\,\rho(E,S,I.name),\,St,E,S,K\rangle\\
&\text{closure}:&& \to \langle seg,pc{+}1,\,\mathrm{clo}(I.params,I.entry,seg,E),\,St,E,S,K\rangle\\
&\text{push}:&& \to \langle seg,pc{+}1,\,val,\,St \Vert [val],E,S,K\rangle\\
&\text{jump}:&& \to \langle seg,\,I.target,\,val,St,E,S,K\rangle\\
&\text{branch-false}:&& \to \langle seg,\,(val\ ?\ pc{+}1 : I.target),\,val,St,E,S,K\rangle\\
&\text{halt}:&& \to \text{halted},\ \text{result} = val
\end{aligned}$$

where $\rho$ is `engine/resolve-var` (env → store → primitives → modules).

**call** with $n = I.argc$, $St = St' \Vert [f, a_1..a_n]$:

$$\begin{aligned}
&f = \mathrm{clo}(ps, e, seg', E_c),\ \neg tail:&&
\to \langle seg',\,e,\,val,\,St',\,E_c[ps \mapsto a],\,S,\,K \Vert [\mathrm{ret}(seg,pc{+}1,E,|St'|)]\rangle\\
&f = \mathrm{clo}(ps, e, seg', E_c),\ tail:&&
\to \langle seg',\,e,\,val,\,St',\,E_c[ps \mapsto a],\,S,\,K\rangle\\
&f\ \text{primitive},\ f(a)\ \text{a value } v:&&
\to \langle seg,pc{+}1,\,v,\,St',E,S,K\rangle\\
&f\ \text{primitive},\ f(a)\ \text{an effect } \epsilon:&&
\to \mathrm{effect}(\epsilon, \langle seg,pc{+}1,St',E,S,K\rangle)
\end{aligned}$$

A tail call grows neither $K$ nor $St$: the walker gets this for free
because a closure body runs against the caller's $k$; the linear machine
must state it, and does. Effect handling `effect(ε, cfg)` is
`engine/handle-effect`; its result is either a value (continue at `pc+1`)
or a park (the configuration becomes a wait entry, control goes idle).

**return** with $K = K' \Vert [\mathrm{ret}(seg',pc',E',b)]$:

$$\to \langle seg',\,pc',\,val,\,St[0..b),\,E',\,S,\,K'\rangle$$

and with $K = []$: halted with result $val$.

**stream-next** (cursor ref $c = val$), by outcome of `next` on the handle
behind $c$:

$$\begin{aligned}
&ok\ v,\ c':&& \to \langle seg,pc{+}1,\,v,\,St,E,\,S[c \mapsto c'],\,K\rangle\\
&blocked:&& \to \text{parked}\ \{seg,pc{+}1,E,St,K,\ \text{cursor-ref}\ c\}\ \in\ \text{wait-set}\\
&end:&& \to \langle seg,pc{+}1,\,\mathrm{nil},\,St,E,S,K\rangle\\
&gap\ c':&& \to \langle seg,pc{+}1,\,\text{:dao.stream/gap},\,St,E,\,S[c \mapsto c'],\,K\rangle
\end{aligned}$$

**ffi-call** ($St = St' \Vert [a_1..a_n]$): park frame
$\{\text{eval-call}, seg, pc{+}1, E, St', K\}$ under a fresh id $p$; append
$\mathrm{request}(p, I.op, a)$ to call-in; on `ok` the wait set gains a
reader entry on the call-out cursor correlated by $p$; when the correlated
response arrives, $val \leftarrow$ its `ok` value (or its `error` raises)
and control resumes at $pc{+}1$.

**park**: $K$, $E$, $St$, $seg$, $pc{+}1$ are written to `:parked` under a
fresh id; the VM halts with that record as its value.
**resume** ($val$ = resume value, $I.id$ the parked id): the parked
configuration is restored with $val$ as its accumulator.
**current-continuation**: $val \leftarrow \{seg, pc{+}1, E, St, K\}$
reified as data.

### 4.3 What the equations make visible

Compare the walker's evaluation of one operand: it allocates a frame map,
`assoc`s the frame into $k$, `conj`s onto `:evaluated`, and dispatches on a
keyword `:type` twice (node, then continuation). The linear machine does
`push` (one vector `conj`) and increments `pc`. The "continuation" of an
operand sequence is the *next pc*, a fact the lowering already computed.
This is the entire performance argument, stated as semantics rather than as
engineering (§6).

---

## §5. Lowering: Universal AST → linear datoms

### 5.1 Placement and contract

`yin.vm.linearize` (not `yang.linearize`): yang owns syntax → AST and must
stay ignorant of any evaluator; lowering AST → executable code is VM lineage
and must stay ignorant of any surface syntax. Clojure, Python and PHP front
ends all reach the linearizer through the one canonical form. The
recommendation departs from the target-file example in the task brief for
this reason.

```
lower : [:yin/* datoms] → [:yin.code/* datoms]         ; pure, total over supported node types
lower-ast : ast-map → [:yin.code/* datoms]             ; convenience = lower ∘ ast->datoms
```

Input is canonical AST datoms because that is what travels and what the
walker consumes; the implementation may reconstruct maps with
`vm/datoms->ast` internally in Phase 2 and lower from the datom index
directly later. Output is one segment per program root.

### 5.2 When: ahead-of-time at the load boundary

Compilation is AOT per segment, once, where the program crosses into the
VM: `eval` lowers its AST; a composition whose program stream carries AST
datoms composes `lower` into the loader it hands the observer; a composition
whose stream carries code datoms hands the loader alone. There is no
tiering, no recompile-at-boundary, no dirty flag: the engine's versioned
compile cache exists for programs that grow by append, and a segment is
immutable once emitted. Streaming delivery of a growing segment (a pc past
the loaded length parks on code rather than erroring) is reserved for a
later phase; it is additive because a pc beyond `length` is already a
defined load-time condition.

### 5.3 The flattening

Recursive descent over the AST in evaluation order, emitting instructions
into a growing vector and lambda bodies out of line:

| AST node | Emitted sequence |
|---|---|
| `:literal v` | `const v` |
| `:variable s` | `var s` |
| `:lambda ps body` | `closure ps →L` ; body emitted later at label L ending in `return` |
| `:application op args` (tail? τ) | `⟦op⟧ push ⟦a₁⟧ push … ⟦aₙ⟧ push call n τ` |
| `:if t c a` | `⟦t⟧ branch-false →A ⟦c⟧ jump →E A: ⟦a⟧ E:` |
| `:dao.stream.apply/call op args` | `⟦a₁⟧ push … ⟦aₙ⟧ push ffi-call op n` |
| `:stream/put target val` | `⟦target⟧ push ⟦val⟧ stream-put` |
| `:stream/cursor s`, `:stream/next s`, `:stream/close s` | `⟦s⟧ stream-cursor` etc. |
| `:stream/make b` | `stream-make b` |
| `:vm/store-get k`, `:vm/store-put k v`, `:vm/gensym p` | one instruction each |
| `:vm/park`, `:vm/current-continuation` | one instruction each |
| `:vm/resume id v` | `⟦v⟧ resume id` |
| root | main sequence ends in `halt`; bodies follow |

Evaluation order is the walker's (operator, then operands left to right),
so effect order is identical, which the parity suite checks. Tail position
is read from `:yin/tail?` as yang already marks it; `:if` branches inherit
it; nothing new is inferred. Labels are resolved to instruction refs in a
second pass; the linearizer emits refs, never pc integers.

Every instruction gets `:yin.code/source` = the AST node eid it came from,
and the segment gets `:yin.code/derived-from` = the root eid. The reverse
direction (render an instruction range back to source) is a query, not a
function in this namespace.

---

## §6. Performance architecture

### 6.1 Traversal mechanics

Walker, per AST node: read `:type` (keyword map lookup), destructure the
node (2–3 lookups), allocate a continuation frame, and later read the frame
back (`:frame`, `:operands`, `:evaluated`, `:env`, `:next`: 5 lookups) and
dispatch on `:type` of the frame. Nodes are heap-scattered persistent maps
reached by pointer from their parent.

Linear machine, per instruction: `aget` on one array, one integer `case`,
one operation on locals. Operands are fields of the decoded instruction
(array slots or record fields), read once. The next instruction is the next
array slot.

### 6.2 Memory and allocation

| Event | Walker allocations | Linear allocations |
|---|---|---|
| evaluate an operand | frame map, `assoc k`, `conj evaluated` (3) | `conj` onto `St` (1) |
| begin an application | frame map (1) | 0 |
| test an `if` | frame map (1) | 0 |
| call a closure | `zipmap`, `merge` (2) | `zipmap`, `merge`, return frame (3), 2 if tail |
| every non-hot transition | one `ASTWalkerVM` record | 0 (registers stay in `loop` locals until a park point) |

For `(+ x 1)` the walker performs roughly nine allocations and a dozen map
lookups across six transitions; the linear machine performs three vector
`conj`s and one argument vector across seven instructions. Call-heavy code
(the countdown benchmark is nothing but calls and an `if`) is where the
difference lands. The remaining allocations are the persistent `St` and the
env `merge`; both have reserved optimizations (§6.4) that require no datom
change.

### 6.3 Cache locality

The image is one contiguous array of small decoded records built in pc
order, so sequential execution walks memory sequentially; a loop body is a
few cache lines revisited. Walker nodes are wherever the reader or
`datoms->ast` allocated them, and each continuation frame is a fresh
allocation on the nursery. On V8 and Dart, where keyword `case` compiles to
hash-and-compare chains and small maps are dictionary-mode objects, the
integer switch and array indexing matter more than on HotSpot; the historic
benchmarks put semantic bytecode at 2–7x slower on those hosts precisely
because it did graph traversal and map lookups per step, both of which this
design removes.

### 6.4 Relation to bytecode, and what is reserved

The loaded image *is* bytecode. The difference from a traditional bytecode
VM is only the stored form (five datoms per instruction rather than a few
bytes) and the load step that decodes it. Execution cost is therefore
bytecode-class by construction, and queryability is not paid for on the hot
path at all — it is paid once at load. The historic 1.0–1.1x register/stack
line in `cesk-space-optimization.md` is the target; the walker sits at
1.32x on that workload, the old graph-walking semantic VM at 1.69x.

Reserved, additive, none required for Phase 1–3:

- **Lexical addressing**: `:var` gains `:yin.code/depth`/`:yin.code/index`
  when the linearizer proves the name lexically bound; frames become vectors;
  only unbound names walk store → primitives → modules.
- **Superinstructions**: `:push-const`, `:push-var` fused by the loader (not
  the linearizer, so datoms stay minimal).
- **Host-array operand stack** inside the hot loop, materialized to a vector
  only at park points ("immutability observable only at park points",
  streams note §3).
- **Content-addressed segments** (`:yin.code/hash`) for dedup across VMs and
  for verifying a shipped continuation against its code.

Acceptance in Phase 4 is measured, not asserted (§8).

---

## §7. System integration and coexistence

### 7.1 `IVM` and `IVMState`

`SemanticVM` is a record with the walker's fields minus `program`, plus
`code` (`{segment-id image}`), `segment`, `pc`, `stack`. Protocol mapping:

| Protocol fn | Semantic VM |
|---|---|
| `step` | identity when `engine/ready-for-ingress?`; else one transition |
| `run` | `ffi/maybe-run` over `engine/run-loop` with the semantic step/restore |
| `eval ast` | `lower-ast` → `vm-load-program` → `run`; `restore-initial-env` after |
| `reset` | control `{:segment last :pc 0}`, `k []`, `stack []`, halted if no segment |
| `halted?` / `blocked?` / `value` | `engine/halted-with-empty-queue?`, `engine/vm-blocked?`, `engine/vm-value` |
| `control` | `{:segment id :pc n}` or nil when idle (so `ready-for-ingress?` holds unchanged) |
| `environment` / `store` | the maps |
| `continuation` | the frame vector, innermost last |

`create-vm` takes the walker's option set (`:env :primitives :modules
:make-stream :call-in :call-out :call-capacity :bridge`), rejects
`:in-stream` and non-nil `:telemetry` identically, and is all-or-nothing on
FFI construction identically.

### 7.2 Coexistence with the walker

Both are constructors in `yin.repl.core/vm-constructors`
(`{:ast-walker … :semantic …}`), selected by `:vm-type`. The walker
**remains the default** until the Phase 4 acceptance criteria are met and
recorded, and it remains permanently as the reference evaluator: it is the
most direct interpretation of the canonical AST, it is the parity oracle,
and it is what a new host port implements first. The semantic VM is the
fast interpretation of the same truth. Two interpreters of one canonical
form is axiom 2 practised, not duplication.

The value contract differs in one place: a closure value carries
`:entry`/`:segment` instead of `:body`. The parity normalizer compares
closures by `:params` only across evaluators, and the walker-vs-v1 parity
test keeps its stricter shape. `IVMState/control` differs by protocol
design ("VM-specific: AST node, instruction pointer…").

### 7.3 Consumers

- **`yin.repl`**: `make-vm` gains the constructor entry; `make-session`
  composes `lower` into the loader when `:vm-type` is `:semantic`, because
  the REPL's program medium carries AST datoms from yang. The `(vm
  :semantic)` shell command and `vm-labels` follow. No shell behaviour
  changes.
- **`yin.demo` and the continuation-handoff demo**: a parked
  continuation is `{segment pc env stack k}`; shipping it across VMs
  requires the receiving VM to hold the segment. The demo composes this by
  sending the segment datoms on the same stream ahead of the continuation
  (they are datoms; the medium is indifferent), which is the design's own
  claim tested end to end.
- **postgraphics / CLI runners / `register_bench_cljd`**: take a
  constructor; no API change. The cljd bench gains a `:semantic` run so the
  three-host table is produced by existing harnesses.

### 7.4 Cross-platform parity

- **Arrays**: the historic `semantic.cljc` helpers (`object-array` on clj and
  cljd, `js/Array` on cljs, with `aget`/`aset` shims) are reused verbatim.
- **Dispatch**: `opcase` expands to `case` on `int`, which all three hosts
  compile to a jump table or dense switch. The macro already carries its own
  literal opcode map for the cljd host-eval pass; the two new opcodes are
  added in both places (the reader-conditional trap: `#?(:clj …)` does not
  exclude code from the cljd host pass).
- **No mutable fields**: the image is written only during load, through the
  shims, and the record holds it as an opaque value; no `^:unsynchronized-
  mutable`, which cljd handles differently.
- **Exceptions**: load and run errors are `ex-info`; the cljd
  `ExceptionInfo` catch shape used elsewhere in v2 (`#?(:cljd Object :clj
  Throwable :cljs :default)`) is the only catch form.
- **Keyword identity on cljs**: mnemonics are compared only at load; the
  hot loop compares integers.

The same `.cljc` file serves all three hosts; the test suite runs under
`clj -M:test`, shadow `:node-test` (auto-discovered `*-test` namespaces;
confirm "Testing yin.vm.semantic-test" in the node output), and the cljd
test build.

---

## §8. Phased roadmap

### Phase 0 — Spec and lowering contract

Deliverables:
- `docs/design/yin.vm.semantic.md`: §1–§6 of this document promoted, with
  the opcode and attribute tables as the contract.
- `yin.vm`: `opcode-table` and `opcase` gain `:push 22`, `:halt 23`;
  `code-schema` (`:yin.code/*` value types, `:yin.code/segment
  :yin.code/target :yin.code/body :yin.code/source :yin.code/derived-from`
  as refs).
- `yin.vm.ffi`: `call-result` and `call-response-wait-entry` lifted from
  the walker, walker updated to call them (behaviour-preserving; walker
  tests unchanged and green).
- `yin.vm.code` (or inside `semantic`): `well-formed?` returning nil or a
  `{:rule :entity}` defect map for §2.6.

Tests: `yin.vm.code-test` covering each well-formedness rule with a
hand-assembled segment. Acceptance: all existing v2 suites green on three
hosts; new tests green.

### Phase 1 — Core linear interpreter

Deliverables: `src/cljc/yin/vm/semantic.cljc` — record, `create-vm`,
loader, image builder, hot loop with the §4 transitions for `const var
closure push call return jump branch-false halt gensym store-get store-put
current-continuation park resume`, `IVM`/`IVMState`.

Tests: `test/yin/vm/semantic_test.cljc` running hand-assembled segments
(no compiler yet): arithmetic, closure call, tail-recursive countdown to
depth 10⁵ with bounded `k` and `St`, `if` both branches, park/resume,
current-continuation reification round-trip through `pr-str`/`read-string`.
Acceptance: green on three hosts; `k` depth stays 0 in the tail-recursive
test; `control` is nil when idle so `ready-for-ingress?` holds.

### Phase 2 — Lowering compiler

Deliverables: `src/cljc/yin/vm/linearize.cljc` with `lower` and
`lower-ast`; `eval` wired; `vm-load-program` accepting the linearizer's
output.

Tests: `linearize_test.cljc` — pcs dense, every ref resolves, every
lowered instruction has `:yin.code/source`, evaluation order matches the
walker on a program of `yin/def` side effects; `parity_test.cljc` gains an
`evaluator` axis running the whole corpus through both v2 evaluators with
the closure normalization of §7.2. Acceptance: corpus parity on three
hosts; unsupported nodes (`:yin/macro-expand`, `:vm/store-update`) rejected
with an error naming the node.

### Phase 3 — Streams, FFI, observer, consumers

Deliverables: `stream-make stream-put stream-cursor stream-next stream-close
ffi-call` transitions; semantic `park-and-call`; wait-entry builders and
`semantic-restore`; `yin.repl.core` constructor entry and loader
composition; continuation-handoff demo variant shipping segment + frame.

Tests: semantic twins of `ffi_test` (round trip, missing handler error,
correlation mismatch, `full` retention, gap at bridge fatal, history before
cursor not skipped), `engine_test` blocked-read and put cases,
`stream_observer_test` driven with the semantic loader, REPL shell test
selecting `:semantic`. Acceptance: every walker behaviour in the divergence
register reproduced or its difference recorded (Phase 4 amends the
register).

### Phase 4 — Benchmarks, parity suite, default decision

Deliverables: clj Criterium bench over `vm/run` alone (VM construction and
load outside the timed region, as the historic method), cljs and cljd runs
through the existing harnesses; results table appended to
`docs/design/yin.vm.semantic.md`; `yin.vm.divergence-register.md`
amended with a "semantic" section; REPL default switched if criteria hold.

Acceptance criteria (measured, all three hosts, countdown n=5000 and the
parity corpus):
- semantic ≥ 1.25x faster than the walker on JVM and no slower on any
  corpus program on any host;
- allocation per instruction (JFR on JVM) below the walker's per-transition
  figure;
- load cost linear in datoms and under 10% of a single countdown run;
- zero divergence in values, effect order, park/resume, FFI correlation, and
  gap reporting against the walker.

If a criterion fails, the walker stays default and the failing figure is
recorded; the VM still ships as a selectable evaluator.

### Reserved (not scheduled)

Streaming segments (park on code past `length`), lexical addressing,
loader-fused superinstructions, host-array stack in the hot loop,
content-addressed segments, trace emission when telemetry is designed.
Each is additive to the datom vocabulary or internal to the loader; none
changes a contract above.

---

## Appendix A. Deviations from the task brief, with reasons

- Linearizer namespace is `yin.vm.linearize`, not `yang.linearize`
  (§5.1): yang is syntax → AST and must not know evaluators.
- The stream is not consumed instruction by instruction (§3.2): the
  contract has no seek, and a per-instruction boundary is the tax the
  streams note rules out. The linear stream of datoms is real; it is the
  segment, and it is consumed as one value.
- Linearity is a `:yin.code/pc` fact, not stream offset or entity-id
  order (§2.3): offsets are transport-owned and ids are remappable.

## Appendix B. Risks the implementer should watch

- **Effect ordering** in lowering: operator before operands, left to right;
  a single swapped emission breaks parity silently on programs with
  `yin/def` side effects. The Phase 2 ordering test is the guard.
- **Stack-base restoration** on `return` after an effect parked mid-argument
  list: the wait entry must carry `St` as it was after the pops of the
  *current* instruction, never before. Test with a blocked `stream-next` as
  the second of three arguments.
- **Segment retention**: closures from an earlier REPL batch must run after
  later batches load. Phase 1 never evicts; if eviction is added later,
  reuse the `engine/pinned-compiled-versions` shape (pin by frames, parked,
  ready, wait set).
- **cljd `opcase`**: add new opcodes to the macro's internal literal map as
  well as `opcode-table`, or cljd silently dispatches to the default branch.
