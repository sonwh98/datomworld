# yin.vm.macro — Row-native macro expansion as a stream process

Status: owner ruling, 2026-09-18. This is the macro contract for `yin.vm`.
It supersedes every earlier macro design and is subordinate to
[`datom.world.md`](./datom.world.md), [`dao.stream.md`](./dao.stream.md), and
the canonical tuple grammar in
[`yin.vm.code-as-tuples.md`](./yin.vm.code-as-tuples.md).

**Supersedes.** Within `yin.vm.code-as-tuples.md`, this contract supersedes
§8.4's expansion-event shape, §8.5's map batch envelope, and §9.1's
datom-batch adapter row. References there to earlier `yin.vm.macro.md` section
or line numbers are historical, not normative. In particular, the row-native
batch below intentionally drops occurrence groups: every definition occurrence
has its own harvest row and ordinal, even when several occurrences resolve to
the same content-addressed row. No legacy datom-batch adapter is part of this
contract.

> The macro expander is row-native from its first boundary. Programs enter it,
> move through it, and leave it as canonical content-addressed tuples.

---

## 0. Decisions

1. **Evaluators know nothing about macros.** No evaluator has a macro
   transition, instruction, flag, option, or expansion ledger. An evaluator
   observes expanded program rows and rejects tags outside its vocabulary.
2. **Expansion is a process between two media.** The expander observes
   `program-in`, rewrites each batch to a fixpoint, and appends one canonical
   tree packet to `program-out`.
3. **Code is rows end to end.** A node is `[address tag & slots]`; children are
   named by content address; a tree packet is `[root-address rows]`. Neither
   transport nor expansion allocates node identities.
4. **There is no phase.** Expansion is stream topology, not an evaluator mode.
5. **A macro call is an ordinary application row.** The expander rewrites an
   `:application` whose operator is an unshadowed `:variable` naming a macro.
   The tuple grammar has no macro-call tag.
6. **Macro declarations are occurrence facts beside code.** Macro-ness is not
   a lambda slot: the same lambda content may be a transformer or a value.
7. **Definitions never reach an evaluator as macro definitions.** Declared
   definitions become temporary stand-ins and finally name literals. Plain
   definitions remain program syntax and remove a same-name macro binding.
8. **Macros are closed row transformers.** A macro receives operand tree
   packets and returns one tree packet. It sees no program store, captured
   environment, streams, or host IO.
9. **Expansion is outermost-first and reaches a fixpoint.** Output is
   immediately reconsidered under the call site's lexical scope. Depth, body
   steps, result rows, and cumulative batch rows are guarded.
10. **Provenance is rows on a third medium.** Each attempt produces one
    immutable event row naming code by root address and occurrence path.
11. **Authority is write authority on a medium.** Whoever can append a valid
    input batch may declare macros. Whoever holds `program-out` decides what
    programs run. No VM option duplicates that authority.
12. **Expansion failure is data.** It produces an error event, no program
    output, advances the source cursor, and appears in `drain-errors`. Throws
    are reserved for defects and terminal stream outcomes.

---

## 1. Canonical values and invariants

### 1.1 Code row

A canonical code row is:

```clojure
[address tag & slots]
```

Its body is `[tag & slots]` and its address is
`(dao.jing/segment-key body)`. The tag fixes the number, order, and kind of
slots. A `node` slot contains one child address; a `nodes` slot contains an
ordered vector of child addresses; other slots contain the plain value fixed
by the canonical grammar.

```clojure
[A :lambda [x] B]
[B :application C [D E] true]
[C :variable +]
[D :variable x]
[E :literal 1]
```

Rows form a flat relation. Addresses describe the tree-shaped value; rows are
never nested to represent it. Identical subtrees share an address and may
share one row.

### 1.2 Tree packet

A tree packet is:

```clojure
[root-address rows]
```

`rows` contains exactly the canonical rows reachable from `root-address`.
Physical row order has no semantic meaning. A packet is self-contained: every
child resolves inside it, every row is reachable, every address matches its
body, and the child relation is acyclic.

### 1.3 Interpretation creates semantics

A lambda row is only a lambda row and an application row only an application
row. Macro meaning comes from occurrence declarations in the input batch and
the expander's interpretation of an unshadowed call. One content address may
participate in different occurrences with different meanings without changing
the canonical tree.

### 1.4 Invariants

| Invariant | Consequence |
|---|---|
| No hidden global state | Macro store, clock, attempt counter, and incarnation are explicit `ctx` fields. |
| No implicit control flow | Expansion occurs only in `expand-batch`; evaluators never re-enter it. |
| No callbacks | The expander consumes and emits stream values; its body runner is synchronous and private. |
| No shared mutable state | Persistent row indexes and maps are threaded through the step. |
| No layer collapse | Frontend/encoder → row medium → expander → row medium → evaluator. |
| No assumed graph | Admission indexes rows and validates slots, addresses, reachability, and cycles. |
| Derive, do not persist | Ancestry and declaration ordinals derive from rows plus occurrence paths; canonical code has no macro flag. |

---

## 2. Stream protocols

### 2.1 Input batch

`program-in` carries one tuple per batch:

```clojure
[:yin.program/batch
 [tree-0 tree-1 ... tree-n]
 run-index
 declaration-rows
 harvest-rows]
```

Each `tree-j` is `[root-address rows]`. `run-index` selects the tree that
becomes the program. Other trees admit disconnected definitions and supporting
syntax; they are expander input and are never independently forwarded.

Declaration rows are:

```clojure
[:yin.macro/definition tree-index path]
```

Harvest rows are:

```clojure
[:yin.macro/harvest ordinal tree-index path]
```

`path` is a vector of slot coordinates from a tree root. Full rows are indexed
from zero: position `0` is the address, position `1` is the tag, and positions
`2` onward are the grammar slots. A `node` coordinate is its zero-based
full-row position; a `nodes` coordinate is `[slot-position child-index]`, with
the child index also zero-based. Address and tag positions are never path
coordinates. `[]` names the root occurrence. The canonical grammar fixes slot
positions, so paths mean the same thing on every host. `yin/slot` uses this
same zero-based full-row slot coordinate.

For the §1.1 sample, the paths are:

```clojure
[]            ; A, the :lambda root
[3]           ; B, A's body
[3 2]         ; C, B's operator
[3 [3 0]]     ; D, B's first operand
[3 [3 1]]     ; E, B's second operand
```

Thus `[3 [3 0]]` resolves by reading `A[3] = B`, then the first child of
`B[3] = [D E]`. This is the same coordinate base used by the canonical tuple
grammar; it does not renumber slots after removing the address and tag.

Harvest rows are the sole definition order. Ordinals are contiguous from zero,
unique, and ascending in the batch. Every definition occurrence appears
exactly once. A producer writes them in source declaration order. Traversal
order and physical row order have no authority over redefinition.

Declaration rows are a subset of harvested occurrences. A matching occurrence
is a macro definition; one without a declaration is plain. The batch envelope
is coordination data, not another representation of code: all code fields are
canonical tree packets.

### 2.2 Definition shape

A harvested path must resolve to an `:application` equivalent to:

```clojure
(yin/def <literal-symbol> value)
```

It has an operator `[:variable yin/def]`, exactly two operands, a first operand
`[:literal sym]` where `sym` is a symbol, and a value subtree rooted at the
second operand. A declared occurrence additionally requires a `:lambda` value
root. No `:macro?` slot exists.

Every definition path must be harvested, including definitions in unexecuted
branches and non-running trees. Every harvest entry must resolve to this shape.
Missing, duplicate, out-of-range, or non-definition entries fail with
`:harvest-catalogue`; a declaration outside the catalogue or on a non-lambda
definition fails with `:stray-macro-declaration`.

### 2.3 Call site

A call site is an occurrence of `:application` whose operator occurrence is a
`:variable` naming a stored macro and is not shadowed by an enclosing lambda's
parameters. Operands are canonical syntax trees. Frontends do no macro lookup,
discovery, or argument coercion. A macro name in operand position is an
ordinary variable; a lambda in operator position is an ordinary lambda.

### 2.4 Output

On success, `program-out` receives exactly one tree packet:

```clojure
[expanded-root expanded-rows]
```

It contains the selected tree after expansion, tail recomputation, and
stand-in lowering. It carries no declarations, harvest catalogue, attempts,
or source coordinates. An unchanged subtree retains its address. A changed
path receives new addresses because changed row bodies hash differently; no
identity repair rule exists or is needed.

---

## 3. Expander contract

### 3.1 Surface and state

```clojure
expand-batch : batch ctx
            → {:status :ok :tree tree-packet :log log-packet :ctx ctx'}
            | {:status :error :error error-data :log log-packet :ctx ctx'}
expand       : batch ctx → tree-packet | throws
definitions  : batch → ordered definition records
invoke       : macro-tree operand-trees ctx → tree-packet
valid-tree?  : tree-packet opts → nil | error-data
mark-tail    : tree-packet → tree-packet
step         : expander → expander
drain-errors : expander → [expander' summary]
```

```clojure
{:store       {sym macro-tree}
 :incarnation {:yin.expander/token uuid}
 :attempt     0
 :source-medium opaque-medium-token
 :t           0
 :guards      {:max-depth 100
               :max-rows-per-expansion 10000
               :max-rows-per-batch nil
               :max-steps 100000}
 :eval        bounded-row-evaluator}
```

The composition mints the incarnation token and supplies `:source-medium`, an
opaque plain-data identity token for the `program-in` medium. The observer does
not discover or interpret its medium; the composition that wires the observer
to that medium injects the token into `ctx`. The token is stable for that
medium's lifetime and distinct from every other source medium whose events may
share a log; minting and preserving that identity are the composition's
warranty. `:attempt` advances for every
initial, nested, successful, failed, or admission-failure attempt. `:t`
advances per consumed batch and is the batch coordinate within that source
medium. A staged retry reuses the already-produced state and payload. The store
maps a symbol to a self-contained lambda tree packet.

### 3.2 Batch pipeline

`expand-batch` performs:

1. **Admit.** Validate batch shape, tree packets, the closed tag vocabulary,
   slot arities and kinds, address integrity, children, cycles, reachability,
   plain values, run index, paths, harvest catalogue, and declarations.
   Validation indexes rows before any recursive reconstruction.
2. **Harvest.** Iterate harvest ordinals. A declared occurrence installs its
   lambda packet; a plain occurrence removes the name whatever its value.
   Last occurrence wins for the entire current batch. This is intentionally
   syntactic and whole-batch.
3. **Catalogue and replace.** Derive a catalogue containing only declared
   harvest entries, in harvest order. At each declared path replace the
   definition with the working row `[S :yin.macro/defined name k]`, where `k`
   is its index and `S` hashes the row body.
4. **Expand.** Run `expand-node` from the selected root, outermost first, until
   no macro call remains.
5. **Post-harvest.** Walk definitions and stand-ins in the final occurrence
   tree's declaration order to compute the next batch's store.
6. **Mark tail.** Recompute all observable tail slots, clearing stale marks.
7. **Lower stand-ins.** Replace each working stand-in by a canonical literal
   row containing its name.
8. **Validate and emit.** Require canonical rows, correct addresses, a closed
   self-contained tree, and no remaining stand-in. Return the tree and log.

An error returns no `:tree`, returns accumulated event rows, and advances
`ctx`. Diagnostics name addresses, paths, ordinals, and attempts, never
rejected host values.

### 3.3 Immutable path rebuilding

Replacing an occurrence interns its replacement body, substitutes that address
in its parent body, interns the changed parent, and continues to the root.
Multiple replacements below one parent are combined before it is interned.
They are ordered deepest path first, then lexicographically, independent of
map iteration order.

Content sharing never merges occurrences. If an address appears at two paths
and one is replaced, only that path's ancestor chain changes.

### 3.4 Catalogue and stand-ins

A catalogue entry is:

```clojure
{:name sym :macro-tree lambda-tree :source [tree-index path]}
```

It lasts one batch. The stand-in carries name and catalogue index, so two
same-name definitions remain distinguishable. A macro may keep, discard,
duplicate, reorder, or wrap a stand-in received as operand syntax.

A stand-in resolves only when `k` exists and its catalogue name matches. An
unknown or mismatched stand-in has no store effect. A valid fabricated
stand-in may move an admitted declaration's effective position but cannot
introduce a body not declared by the batch; this is permitted behavior.

Stand-ins may occur as working nodes in operand and result packets, never as
canonical output. A stand-in-shaped vector inside a literal payload is rejected
as `:marker-in-payload`.

### 3.5 Store ordering

Initial harvest controls all calls in the current batch. Post-harvest controls
the next batch; there is no mid-batch mutation.

Post-harvest starts from the store before initial harvest and walks the final
occurrence tree. A plain `(yin/def <literal sym> value)` removes `sym`; a valid
stand-in installs its catalogue entry; later occurrences win.

Declaration order is:

- for an application whose operator is a lambda, operands left to right and
  then the lambda body; and
- otherwise, child slots in grammar order and `nodes` children left to right.

This recovers source order for the immediately-applied-lambda lowering used by
`do`, `let`, and the reference frontend suites. A new lowering must state how
it preserves declaration order; arbitrary operator syntax is not assumed to
encode universal source order.

A declaration discarded by a macro is active now and absent next batch. An
identity or wrapper preserves it. Keeping the first of two same-name stand-ins
keeps the first body. A later generated or source plain definition removes the
transformer for the next batch.

---

## 4. Expansion algorithm

### 4.1 Index and recognition

For each packet the expander builds `address → row`. The canonical grammar
identifies every `node` and `nodes` slot. Fixed slot order supplies traversal
order; physical row order never does. `shadow` is the set of symbols bound by
enclosing lambda parameter slots, and `path` is the occurrence path.

```text
macro-of(ctx, operator-address, shadow):
  row = index[operator-address]
  when row.tag == :variable and row.name not in shadow:
    return ctx.store[row.name]
  otherwise return nil
```

There is no inline macro-lambda case. Transformer authority comes only from a
validated declaration harvested into the store.

### 4.2 `expand-node`

```text
expand-node(state, address, path, shadow, depth, origin):
  row = state.index[address]

  if row.tag == :application and macro-of(ctx, row.operator, shadow):
    attempt = allocate-attempt(ctx)
    event   = begin-event(attempt, origin, state.root, path, macro-root)
    if depth >= max-depth:
      fail(event, {:kind :depth-guard, :depth depth, :path path})
    operands = [subtree-packet(index, child) for child in row.operands]
    output   = invoke(macro-tree, operands, ctx)
    validate output, allowing working stand-ins but no batch metadata
    guard output row count
    merge rows into the working index, rejecting address/body conflict
    finish event with output.root
    return expand-node(state, output.root, path, shadow, depth + 1,
                       [:expansion event.address])

  if row.tag == :application:
    operator' = expand-node(state, row.operator, operator-path,
                            shadow, depth, origin)
    app' = rebuild row when operator' changed
    if macro-of(ctx, app'.operator, shadow):
      return expand-node(state, app'.address, path, shadow, depth, origin)
    operands' = expand operands left to right under the same shadow
    return rebuilt application, or app' when unchanged

  shadow' = shadow union row.params when row.tag == :lambda
  children' = expand child occurrences in grammar order under shadow'
  return rebuilt address, or address when unchanged
```

The operator is expanded before operands. If it becomes a macro name, that
macro receives the original operands. This is required for binder macros: a
`defn`-like transformer must establish parameters before its body is inspected
for same-name calls.

Re-entry after invocation increments depth. Re-check after an operator rewrite
does not, because recognition is not expansion. A generated chain therefore
consumes depth and cannot rewrite forever at a constant value.

### 4.3 Properties

- **Outermost first:** a transformer sees operands as written.
- **Fixpoint:** each returned tree is reconsidered, including applications
  whose operator becomes a macro name.
- **Lexical shadowing:** lambda parameters shadow macro names only in their
  body, never in sibling operands of the lambda's application occurrence.
- **Occurrence correctness:** paths distinguish one shared row under different
  lexical ancestors.
- **Determinism:** grammar order, operand order, harvest ordinal, and explicit
  attempt state are the only orders used.
- **Failure record:** the event begins before guards and invocation, so every
  recognized call records success or error.

---

## 5. Executing a macro body

### 5.1 Invocation

`invoke` binds positional parameters to operand tree packets. With `[a & rest]`,
`rest` is a vector of packets. Arity failure is:

```clojure
{:kind :arity :macro macro-root :expected n :got m}
```

The body runner receives the macro tree, parameter environment, prelude, and
step budget. It executes a fresh throwaway `yin.vm` loaded from rows. It has no
program store, module resolver, stream constructors, or host FFI. Definitions
inside it affect only the throwaway machine.

A macro body cannot call a program-defined function or invoke another macro by
name. It may return an application naming another macro; the expansion loop
will recognize it.

### 5.2 Bounded runner

The runner counts VM transitions and rejects:

- more than `:max-steps` as `:fuel-guard`;
- blocked or parked execution as `:suspended`;
- scheduled work outside the single body continuation as `:suspended`; and
- any effect constructor absent from the closed body environment.

This is a private expander capability, not an evaluator macro feature. Its
contract is deterministic, host-independent, no host IO, and no retention of
arguments.

### 5.3 Row-native prelude

The prelude consumes and returns tree packets. Constructors intern canonical
row bodies and return self-contained packets.

| Primitive | Meaning |
|---|---|
| `yin/tag tree` | Root tag. |
| `yin/slot tree n` | Root slot at zero-based full-row position `n`; a child slot returns a tree packet. Positions `0` and `1` are invalid. |
| `yin/literal value`, `yin/variable sym` | Leaf constructors. |
| `yin/lambda params body` | Lambda constructor. |
| `yin/application operator operands` | Application with `tail? false`. |
| `yin/if test consequent alternate` | Conditional constructor. |
| `yin/sequence-body trees` | Reference `do` lowering; empty becomes nil. |
| `yin/make-lambda params-tree body` | Reads a literal parameter vector. |
| `yin/make-def name-tree value-tree` | Constructs ordinary `yin/def` syntax. |
| `yin/name-of tree` | Reads a variable, literal symbol, or literal string name. |
| `yin/gensym-sym prefix` | Returns `prefix__<incarnation>_<attempt>_<n>`. |

Merging packets verifies that one address denotes one body. Packets are closed
plain data; a host object or function in any slot is invalid. Invocation-local
`n` starts at zero and escapes only through generated symbols.

### 5.4 Standard forms and hygiene

`stdlib-forms` is supplied as a row-native batch whose `defn` definition is
declared and harvested. Conceptually it represents:

```clojure
(defmacro defn [fn-name fn-params & body]
  (let [b (yin/sequence-body body)
        l (yin/make-lambda fn-params b)]
    (yin/make-def fn-name l)))
```

The composition seeds the store through the same batch contract or from that
validated canonical lambda packet. There is no privileged `defn` branch.

Expansion is non-hygienic by default. `yin/gensym-sym` supplies explicit
hygiene. Attempt identity makes gensyms distinct across executions while a
staged retry reuses already-produced symbols.

---

## 6. Tail positions

Tail-ness is contextual. `mark-tail` recomputes the whole final occurrence
tree and rebuilds rows whose observable `tail?` slot changes. Under the
canonical grammar only `:application` carries that slot:

- the program root is in tail context;
- an `:if` test is non-tail and its branches inherit the surrounding context;
- an application operator and operands are non-tail, while the application
  records the surrounding context;
- a lambda body is tail relative to the lambda's return; and
- children of effect and control nodes are non-tail unless their grammar rule
  explicitly says otherwise.

Tags without a tail slot never carry one. The pass both sets and clears marks.
A macro can move a tail application into an operand or test, so an additive
pass is unsound.

---

## 7. Validation and guards

### 7.1 Admission

| Rule | Failure |
|---|---|
| batch tuple, run index, side-row arity | `{:kind :malformed-input :reason :batch-shape}` |
| closed tag vocabulary | `{:kind :malformed-input :reason :unknown-tag :address a}` |
| row arity and slot kinds | `{:kind :malformed-input :reason :row-shape :address a}` |
| address matches body | `{:kind :malformed-input :reason :address-mismatch :address a}` |
| child resolves internally | `{:kind :malformed-input :reason :dangling-child :address a :child c}` |
| child relation acyclic | `{:kind :malformed-input :reason :cyclic :address a :path p}` |
| all rows root-reachable | `{:kind :malformed-input :reason :unreachable-row :address a}` |
| recursively plain values | `{:kind :malformed-input :reason :host-value :address a}` |
| complete ordered harvest | `{:kind :malformed-input :reason :harvest-catalogue :ordinal h}` |
| declared lambda definition | `{:kind :malformed-input :reason :stray-macro-declaration :tree j :path p}` |

The first error is deterministic: trees by index, catalogue problems by
ordinal, otherwise addresses in canonical byte order and slots in grammar
order.

### 7.2 Expansion output

A result must be one valid packet. It may contain canonical rows and valid
working stand-ins, but not batch, declaration, harvest, event, or source rows;
host values; address conflicts; unreachable rows; dangling children; cycles;
unknown tags or slots; or marker-shaped data in a value slot.

```clojure
{:kind :invalid-output :macro macro-root :path path :reason reason}
```

### 7.3 Guards

| Guard | Scope | Default | Error |
|---|---|---:|---|
| depth | generated expansion chain | 100 | `{:kind :depth-guard :depth d :path p}` |
| rows | one macro result closure | 10,000 | `{:kind :row-guard :scope :expansion :rows n}` |
| rows | cumulative distinct batch workspace | unlimited | `{:kind :row-guard :scope :batch :rows n}` |
| VM steps | one body invocation | 100,000 | `{:kind :fuel-guard :steps n}` |
| arity | one invocation | — | `{:kind :arity ...}` |
| suspension | one invocation | — | `{:kind :suspended}` |

Row guards count distinct rows in the relevant closure. Literal byte size is
not bounded by row count; an untrusted-author composition needs a separate
payload-size admission policy.

---

## 8. Provenance rows

### 8.1 Attempt identity

Every attempt has the plain-data identity `[incarnation-token counter]`. The
counter is unique within an expander incarnation and the token is minted by the
composition. Admission failure consumes an identity. A staged retry retains
it; a newly constructed expander has a new incarnation.

### 8.2 Expansion event

Each attempt emits one content-addressed row:

```clojure
[event-address :yin.macro/expand
 attempt                 ; [incarnation counter]
 source-batch            ; logical ctx :t
 origin                  ; [:source source-medium source-batch tree-index] | nil
 parent-event            ; parent event address | nil
 input-root              ; call tree root | nil on admission failure
 call-path               ; occurrence path | nil on admission failure
 macro-root              ; lambda root | nil on admission failure
 output-root             ; successful result root | nil on failure
 error]                  ; plain error map | nil on success
```

`event-address` hashes the row body. `attempt` makes identical expansions in
two attempts distinct events. `source-batch` is the pre-increment logical
`ctx :t`; there is no duplicate timestamp slot and no host clock. An initial
expansion carries `[:source (:source-medium ctx) source-batch tree-index]` and
nil `parent-event`. A nested expansion carries its enclosing event address and
nil `origin`. Success carries an output and nil error; failure does the
inverse.

An admission failure carries its allocated `attempt`, the same
`source-batch`, `origin` as
`[:source (:source-medium ctx) source-batch nil]`, the plain admission error,
and nil for `parent-event`, `input-root`, `call-path`, `macro-root`, and
`output-root`. The nil tree index states that the rejected batch could not
safely identify a member tree; the source medium and batch remain observable.

The root slots are content addresses with the same meaning on every medium.
`call-path` distinguishes shared application occurrences. No medium-local code
identity or identity repair appears in provenance.

### 8.3 Log packet

When a log writer exists, it receives:

```clojure
[:yin.macro/log event-rows produced-trees]
```

Events are in attempt order. `produced-trees` contains the self-contained
packets named by successful events, deduplicated by root address in first-use
order. This makes the log independently inspectable. The program medium still
receives only the final program tree packet.

The portable chain is:

```text
source occurrence → event → output root → child event → output root
```

No provenance slot exists in canonical code and no event is interleaved with
program rows.

---

## 9. Expander observer

The expander is an ordinary `dao.stream.observer/run-on-stream` consumer:

```clojure
{:ctx ctx
 :out writer          :out-staged nil|tree-packet
 :log writer|nil      :log-staged nil|log-packet
 :forwarded 0         :errors []}
```

It is ready only when both staged slots are nil. `load` calls `expand-batch`
once, stores the returned context, stages the tree only on success, stages the
log when a writer exists, and accumulates returned errors.

`flush` handles destinations independently:

| Append outcome | Effect |
|---|---|
| `ok` | Clear only that slot; increment `:forwarded` when output clears. |
| `full` | Retain the exact payload, remain not-ready, and retry before reading input. |
| `closed`, `invalid-value`, `transport-error` | Throw with the outcome and both staged slots preserved. |

Thus `out=ok, log=full` retries only the log. A failed expansion stages no
program value, but its error log flushes and the source cursor advances.
Program and log media are not atomically visible unless their composition
provides an atomic enclosing medium.

`drain-errors` reads and resets `:errors` and `:forwarded`, returning:

```clojure
[expander' {:errors errors :forwarded forwarded}]
```

If observer coordination throws after prior progress, its exception must carry
the partial successor session. A load failure carries the cursor before the
failing value. A flush failure after load carries the cursor after it and the
consumer with remaining staged slots. Recovery from that session neither
re-reads accepted input nor republishes a cleared slot.

```text
frontend/encoder → program-in → expander → program-out → evaluator
                                      └→ macro-log
```

Every arrow carrying code carries canonical rows.

---

## 10. Compositions

### 10.1 REPL

`make-session` builds `program-in`, an expander seeded with standard forms,
`program-out`, an optional log, and an evaluator observer. For each input:

1. the frontend produces its internal Universal AST;
2. the encoder projects it to canonical rows and supplies harvest and
   declaration rows;
3. the shell appends the batch tuple to `program-in`;
4. it drives the expander and drains its summary;
5. when `:forwarded` is positive, it drives the evaluator; and
6. it prints drained expansion errors.

The stream boundary begins at canonical rows; no syntax object crosses
`program-in`. `(compile expr)` may render frontend syntax, input row batch,
expanded tree packet, and event rows. `(reset)` rebuilds expander and evaluator
state. `repl-state` lists macro names and lambda root addresses.

### 10.2 Frontends and encoder

Frontends identify macro-defining source forms. The encoder projects every
tree to rows, emits one packet per root or disconnected definition tree, emits
one harvest row per definition in source declaration order, and emits one
declaration row per macro definition.

`yang.clojure` lowers `defmacro` to ordinary `yin/def` syntax and supplies the
declaration occurrence; it puts no macro metadata in the lambda row.
`yang.python` and `yang.php` require no lookup: every call is an ordinary
application row. A future macro-definition surface in either language need
only produce the same declaration and harvest rows.

### 10.3 Builds and cross-platform behavior

A build composes the same observer between file-backed media. Addresses remain
stable across storage, streams, expansion, and evaluation. Persistent
provenance stores log packets unchanged or indexes event rows as a separate
interpretation. Store seeding folds any validated sequence of input batches in
order; querying is optional and never part of expansion.

One `.cljc` implementation uses vectors, maps, sets, reduction, and the
canonical address function. Hosts must agree on address calculation, harvest
order, slot traversal, paths, attempts, gensyms, events, and error data.

---

## 11. Implementation roadmap and acceptance

### Phase 0 — Row protocol

- Freeze the canonical row grammar and address encoding.
- Implement packet validation and occurrence-path navigation.
- Implement input batches, declaration rows, and harvest rows.
- Make frontends plus encoder emit complete harvest catalogues in source order.
- Make observer errors preserve partial successor sessions.

Acceptance requires exact canonical round trips; correct distinction of a
shared row at two paths; deterministic rejection of malformed addresses,
children, cycles, reachability, paths, declarations, and harvest entries; and
no program medium accepting a non-row syntax carrier.

### Phase 1 — Expander

Implement harvest, immutable path rebuilding, stand-ins, post-harvest,
`expand-node`, invocation, bounded execution, the row-native prelude, tail
marking, event rows, staging, and error draining.

Required tests include:

- standard `defn` equals its direct lambda/definition tree;
- definitions in unexecuted or disconnected trees are harvested;
- harvest ordinal, never row or map order, decides last-wins;
- declared definitions are replaced and no stand-in reaches output;
- plain redefinition removes a macro;
- identity, wrap, discard, reorder, duplicate, keep-first, and keep-second
  transformations preserve the specified catalogue entry;
- unknown and mismatched stand-ins have no store effect, while a valid moved
  stand-in changes precedence only at its final position;
- outermost-first binders, operator-to-macro rewriting, and lexical shadowing;
- macro names in operand position remain ordinary variables;
- depth, row, fuel, arity, and suspension guards;
- invalid host values, address conflicts, marker payloads, and malformed output;
- whole-tree tail recomputation sets and clears marks after syntax moves;
- equal input and context yield equal trees and events;
- distinct attempts may share an output root but never an event address;
- staged retries reuse attempt identity and gensyms;
- per-destination retry after `full`;
- an expansion error advances the cursor and the next batch can succeed; and
- a forwarder defect does not silently advance input.

### Phase 2 — REPL and builds

- Route shell evaluation through encoder → input rows → expander → output rows
  → evaluator.
- Seed standard forms through the row-native store.
- Render input rows, expanded rows, and events in `(compile ...)`.
- Show macro names and addresses in `repl-state`.
- Verify reset, cross-language calls, file-backed media, and continued
  evaluation after expansion failure.

### Reserved

Automatic hygiene; expansion of macro bodies before storage; macro-defining
macros with a stated mid-batch rule; payload-byte guards; memoized expansion
that still emits fresh attempt events; a portable expander written in
`yin.vm`; and query-backed store seeding.

---

## Appendix A. Consequences of the ruling

1. Node identity is content address. No node allocation counter exists.
2. Unchanged syntax retains its address; changed ancestors hash to new ones.
3. Occurrence identity is `[origin root-address path]`, never address alone.
4. Macro-ness is an occurrence declaration, not lambda content.
5. Harvest order is explicit producer data, never reconstructed from storage.
6. Macro operands and results are tree packets; invocation needs no conversion.
7. Provenance names roots and paths directly across media.
8. Guards count rows and VM steps.
9. Evaluator input is already the canonical program artifact.

## Appendix B. Implementation risks

- Never use a row address alone as an occurrence key.
- Never infer harvest order from traversal or physical row order.
- Never mutate a row; rebuild and intern the occurrence path.
- Never forward declaration, harvest, log, or working rows as program code.
- Never allow a stand-in in a data payload or after final lowering.
- Re-check a rewritten operator before expanding its operands.
- Recompute tail marks; do not merely add them.
- Retry staged event rows exactly; do not regenerate attempts.
- Validate addresses, children, and cycles before recursive reconstruction.
- Never add macro behavior to an evaluator to compensate for missed expansion.
