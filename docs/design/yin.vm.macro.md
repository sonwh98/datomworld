# yin.vm.macro — Macro Expansion as a Stream Process

Status: agreed design target (2026-09-13). This document is the macro
contract for `yin.vm.v2`. It supersedes the v1 stream model in
[`macro-design.md`](./macro-design.md), the strategy in
[`../cross-language-macro.md`](../cross-language-macro.md), and the previous
revision of this document, which integrated expansion into the evaluators;
Appendix A records what that revision proposed and why it is withdrawn. It
is subordinate to [`datom.world.md`](./datom.world.md) and
[`dao.stream.md`](./dao.stream.md). Revision 3 folds in the round-2 reviews
(`collab/1789277584941-review-macro-stream-process-r2.{gpt-6-astra,glm-5.3}.findings.md`);
Appendix C maps each finding to its resolution.

The design follows from one framing and adds nothing to it:

> Every `yin.vm` evaluator is an observer of a `dao.stream` medium. The
> writer to that medium is just another process. Macro expansion is such a
> process.

---

## 0. Decisions

1. **Evaluators know nothing about macros.** No evaluator — `ast-walker`,
   `linearize`/`semantic`, or a future stack VM — has a macro transition, a
   macro instruction, a macro flag on closures, a macro option at
   construction, or an expansion ledger. An evaluator executes semantic
   datoms and rejects, at load, any node type outside its vocabulary.
2. **Expansion is a process between two media.** The expander observes a
   *source* medium (`program-in`), rewrites each batch to a fixpoint, and
   appends the result to a *program* medium (`program-out`) that evaluators
   observe. It is a forwarder in the sense of `dao.stream.md` §Composition,
   driven by the same `dao.stream.v2.observer/run-on-stream` coordination that
   drives an evaluator.
3. **There is no phase.** "Compile time" and "runtime" named which cursor
   reached a batch first. With expansion on its own medium, the distinction
   has no referent; `:yin/phase-policy` and `:yin/phase` are retired.
4. **A macro call site is an ordinary application.** In datom form, every
   application's operands are already unevaluated syntax, so nothing marks a
   call site. The expander rewrites an `:application` whose operator
   resolves, under lexical shadowing, to a lambda carrying `:yin/macro? true`.
   `:yin/macro-expand` is retired from the Universal AST.
5. **Definitions never reach an evaluator.** The expander consumes every
   macro definition in a batch into its own store and forwards only expanded
   program. A macro name reaching an evaluator is therefore an unbound
   variable — a loud failure — never a function applied to evaluated
   arguments — or, if an older function of that name is still in the
   evaluator's store, a call to that function (§2.2). Every `yin/def` is
   authoritative for the expander's store: a plain redefinition of a macro
   name removes the macro (§3.1).
6. **Macros are closed syntax transformers.** A macro body receives operand
   ASTs bound to its parameters and returns a plain-data AST. It sees no
   program store, no captured environment, no streams. Expansion is a pure
   function of (macro lambda, operand ASTs, allocation state) on every host.
7. **Expansion is outermost-first and re-expands its own output to a
   fixpoint** under dual guards (depth, output size per expansion; steps per
   body; optional cumulative bound per batch) with cycle detection.
8. **Output batches are self-contained.** Unchanged operands are copied,
   not referenced, into `program-out`; refs across media are meaningful only
   for durable ids, and the expander does not pretend otherwise.
9. **Provenance is a third medium.** Every attempt produces a
   `:macro-expand-event`; events and the `m`-tagged output copies are
   appended to an optional *log* medium. Evaluators never see events.
   `program-out` datoms carry `default-op` only.
10. **Authority is write authority on a medium.** Whoever may append to
    `program-in` may define macros; whoever holds the `program-out` writer
    decides what programs run. No VM option encodes this.
11. **An expansion failure is data, not a throw.** A batch that fails to
    expand yields an error result: its event goes to the log, nothing goes to
    `program-out`, the source cursor advances, and the driver drains the
    error. Throws are reserved for defects of the forwarder itself.

---

## 1. Axioms and invariants

**Interpretation creates semantics.** A `:lambda` entity with
`:yin/macro? true` is an ordinary lambda. An `:application` whose operator
names it is an ordinary application. What makes the pair a macro and a call
site is *which process reads them*: the expander reads them as a rewrite
rule and a rewrite site; an evaluator, if it ever saw them, would read them
as a function and a call. The design keeps them on the expander's side of a
medium boundary, so the evaluator never has to know the difference.

**Code and state are datoms.** Definitions, call sites, expansion output,
and events are all `:yin/*` and `:macro-expand-event` datoms. Original call
datoms on `program-in` are never rewritten; `program-out` is a new, complete
program. The link from a call to its expansion is forward-only, through the
event.

**Everything is a stream.** The expander is a forwarder: one batch in, one
batch out, `full` retained for retry, cadence owned by the driver. Nothing
about it requires support from the stream contract or from any evaluator.
An evaluator observing `program-out` is exactly the evaluator observing any
program medium.

**Everything is a continuation.** Nothing here touches continuations. A
continuation captured by an evaluator references only semantic datoms,
which is why a shipped continuation needs no expander at its destination.

+--------------------------+---------------------------------------------------------------------------------------------------------------------------------------------+
| Invariant                | How the design honours it                                                                                                                   |
+==========================+=============================================================================================================================================+
| No hidden global state   | The macro environment is the expander's own store, a value in its process state. Fresh ids and gensyms come from an allocation counter      |
|                          | seeded from each batch and returned as a watermark. No registry, no `defonce`.                                                              |
+--------------------------+---------------------------------------------------------------------------------------------------------------------------------------------+
| No implicit control flow | Expansion happens in exactly one place: the expander's step. Nothing expands on lookup, load, apply, or transition.                         |
+--------------------------+---------------------------------------------------------------------------------------------------------------------------------------------+
| No callbacks             | The expander is a function over a batch and a context returning data; its driver is `run-on-stream`. The body runner is a function called   |
|                          | synchronously on a throwaway VM value.                                                                                                      |
+--------------------------+---------------------------------------------------------------------------------------------------------------------------------------------+
| No shared mutable state  | Expander state is a persistent map threaded through the step. The one local cell — a gensym counter inside one body invocation — cannot     |
|                          | escape because the output validator rejects any host value.                                                                                 |
+--------------------------+---------------------------------------------------------------------------------------------------------------------------------------------+
| No layer collapsing      | *compile* (yang) → medium → *expand* → medium → *load/execute*. Each is a function over data. The evaluator never re-enters expansion; the  |
|                          | expander never holds the program evaluator's control state (its own throwaway body runner is private to it).                                |
+--------------------------+---------------------------------------------------------------------------------------------------------------------------------------------+
| No assumed graphs        | The expander indexes each batch with `index-datoms` explicitly, checks the reachable graph acyclic and every ref resolvable at admission,   |
|                          | visits ref attributes in a fixed order, and honours the explicit `:yin/root` fact. Malformed input, dangling roots, and forged ids are      |
|                          | errors naming the entity.                                                                                                                   |
+--------------------------+---------------------------------------------------------------------------------------------------------------------------------------------+

---

## 2. Representation

### 2.1 The macro entity

+-----------------+------------------------------------------------------+-----------------------------------------------------------------------------------------------+
| Attribute       | Value                                                | Note                                                                                          |
+=================+======================================================+===============================================================================================+
| `:yin/type`     | `:lambda`                                            | structurally a lambda                                                                         |
+-----------------+------------------------------------------------------+-----------------------------------------------------------------------------------------------+
| `:yin/params`   | vector of symbols; `&` before the last permitted     | variadic binding is the expander's (§3.3)                                                     |
+-----------------+------------------------------------------------------+-----------------------------------------------------------------------------------------------+
| `:yin/body`     | ref                                                  |                                                                                               |
+-----------------+------------------------------------------------------+-----------------------------------------------------------------------------------------------+
| `:yin/macro?`   | `true`                                               | the only fact that distinguishes a macro                                                      |
+-----------------+------------------------------------------------------+-----------------------------------------------------------------------------------------------+

AST form `{:type :lambda :params [...] :body ... :macro? true}`. `yang.clojure`
lowers `(defmacro name [params] body)` — at any nesting — to
`(yin/def name <lambda macro? true>)`, exactly as `def` lowers today with the
flag added. `^{:yang/shadow-params-operand}` / `^{:yang/shadow-body-start}`
metadata is retired (§3.2 explains why outermost-first expansion makes the
hint unnecessary).

### 2.2 The definition form

A **definition** is an `:application` whose operator is
`{:type :variable :name yin/def}` and whose operands are
`[{:type :literal :value <sym>} <lambda macro? true>]` — the shape
`yang.clojure/compile-def` already emits. The expander recognises this shape
anywhere in a batch (§3.1).

**Two namespaces.** The expander's store and an evaluator's store are
disjoint and never reconciled. Defining a macro `m` does not remove an
earlier function `m` from an evaluator's store — it cannot; they are on
different media — but every later `(m …)` is rewritten before it reaches the
evaluator, so the old binding is reachable only through `m` in operand
position. Conversely, a plain `(yin/def m <lambda>)` after a `defmacro m`
removes `m` from the expander's store *and* is forwarded, so the evaluator
binds the function. Neither direction is silent: the expander's store is
authoritative for what expands, the evaluator's for what runs.

### 2.3 The call site

Any `:application` whose operator is a `:variable` naming a macro in scope.
(A `:macro? true` lambda in operator position is not a supported input:
admission rejects a macro lambda anywhere but a definition's value operand,
§3.1.) Operands are whatever the
frontend compiled them to — canonical Universal AST nodes, never
source-language forms. A Python `defn("f", ["x"], x)` and a Clojure
`(defn f [x] x)` are both `:application`s; the frontend does no discovery and
no coercion. **Contract for macro authors:** use the prelude accessors
(`yin/name-of`) for names rather than matching on `:type`, because a name
may arrive as a `:variable`, a `:literal` symbol, or a `:literal` string.

### 2.4 The root fact and the codec

`ast->datoms-with-root` appends `[root :yin/root true t default-op]`;
`index-datoms` takes the *last* `:yin/root` fact in batch order as the
root, falls back to the existing heuristic when none is present, and fails
`{:rule :dangling-root :entity e}` if the named entity has no `:yin/type`. An
explicit `:root-id` option still overrides. This is a codec improvement
independent of macros; the expander relies on it because it emits copies
bottom-up and must not depend on emission order for root discovery.

The codec (`yin.vm.v2/ast->datoms`, `datoms->ast`) keeps `:yin/macro?` on
lambdas and gains `:yin/root`. It drops `:yin/macro-expand`,
`:yin/phase-policy`, `:yin/phase`, `:yin/capability` and the
`:macro-expand-event` attributes: events are written by the expander through
its own small emitter (§4.1), not through the AST codec, because they are
not AST.

---

## 3. The expander (`yin.vm.v2.macro`)

### 3.1 Contract

```
expand-batch : datoms ctx → {:status :ok     :datoms out :root-eid r :log [...] :ctx ctx'}
                          | {:status :error  :error {:kind ..} :log [...] :ctx ctx'}
expand       : datoms ctx → out | throws                ; convenience over expand-batch
definitions  : datoms → {sym lambda-ast|nil}           ; syntactic harvest, last wins; nil = removal
drain-errors : expander → [expander' errors]           ; §5
invoke       : lambda-ast operand-asts ctx → value      ; one body, §3.3
valid-ast?   : value opts → nil | {:kind :invalid-output :at path}
mark-tail    : ast → ast                               ; §3.4, whole-tree recompute
step         : expander → expander'                    ; one forwarder step, §5
event-schema                                           ; §4.1
stdlib-forms, prelude
```

`ctx` is the expander's process state and is threaded through every call:

```
{:store    {sym lambda-ast}       ; macro environment, persisted across batches
 :alloc    {:next-eid n}          ; monotonic negative counter, never re-seeded upward
 :guards   {:max-depth 100  :max-nodes-per-expansion 10000
            :max-steps 100000  :max-datoms-per-batch nil}
 :eval     (fn [lambda-ast env prelude budget] → value)   ; §3.3
 :t        0}                     ; batch counter; incremented once per expand-batch
```

`expand-batch`:

1. **Admit.** The batch is indexed with `index-datoms` and checked as a
   whole — **every entity in the index, not only those reachable from the
   root**, because harvest (step 2) reads disconnected definitions: every
   `:yin/type` is in the closed vocabulary (§3.3), every ref resolves to an
   entity in the index, the whole graph is acyclic, every ground value is
   plain data, and every `:macro? true` lambda is the value operand of a
   `(yin/def <literal sym> …)` — a macro lambda anywhere else has no meaning
   on either side of the boundary and, forwarded, would load on the walker
   as a plain closure, the quiet case decision 5 exists to prevent — all
   *before* any recursive decoding (Appendix B). An in-memory medium can
   carry host values, and the sandbox's closed-input guarantee holds only if
   nothing else is admitted. A failure here is
   `{:status :error :error {:kind :malformed-input :reason :unknown-type |
   :dangling-ref | :cyclic | :host-value | :stray-macro-lambda :eid e}}`
   carrying a **batch-level
   event** (§4.1) — allocated from the watermark alone, since the index may
   be unusable — and the advanced `:ctx`. Diagnostics name entities, never
   carry the offending values.
2. **Harvest.** `definitions` finds every `(yin/def <literal sym> v)` in the
   batch, in batch order, last wins: `v` a `:macro? true` lambda →
   `assoc` into `:store`; `v` anything else (a plain lambda, a variable, a
   literal) → `dissoc`, so a redefinition as a non-macro stops expansion of
   that name. Harvest is *syntactic* and whole-batch by policy: a definition
   in an unexecuted branch, or after its first use in the same batch, is
   still in force for the whole batch. (Clojure defines a macro only when the
   `defmacro` form executes; this design does not, and says so.)
3. **Replace.** Step 2 also numbers every source macro definition it saw,
   in harvest order, into a batch-local **catalogue** `:declared {k {:name
   sym :lambda ast}}`. Each macro definition node is then replaced by a
   **stand-in** `{:type :yin/macro-defined :name <sym> :decl k}` — an
   internal node type of the expander's working representation, not of any
   evaluator. It carries its identity by *shape and ordinal*, so it survives
   every transformation a macro can apply to it: a macro that receives it
   as an operand and returns, wraps, or reorders it hands back the same
   shape, and nothing in the pipeline needs an eid or `m` to recognise it —
   and two definitions of the same name in one batch remain distinguishable,
   so a macro that keeps one and discards the other keeps *that one's* body.
   A fabricated stand-in can only name a catalogue entry (step 5 validates
   `k` and that `:name` matches); it can therefore move a harvested
   declaration's effective position — re-asserting it after a plain
   definition, say — but never introduce an unharvested body. That is
   permitted transformer behaviour and is stated as such. Stand-ins resolve
   exclusively against the *current* batch's fresh catalogue and carry no
   cross-batch declaration identity; marker-shaped data may well survive
   outside one expansion (it is plain data), and if replayed it selects
   whatever the current catalogue holds at that ordinal and name, or
   nothing — never a previous batch's body. Step 7 lowers
   every stand-in to `{:type :literal :value <sym>}` for `program-out`, so a
   batch whose root is a definition still forwards a complete program and a
   REPL prints the name. Plain `yin/def`s are forwarded untouched.
4. **Expand.** `expand-node` (§3.2) runs from the root over the index.
5. **Post-harvest.** Expansion can *form* definitions that did not have the
   `yin/def` shape in the source — `(defn m …)` above all, but also an
   operator that rewrites into `yin/def` — and copying along changed paths
   gives nodes fresh eids and `default-op`, so neither eid nor `m` can say
   where a definition came from. Step 5 therefore does not ask. The store
   for the **next** batch is recomputed as: start from the store *as it was
   before step 2*; walk the **final tree** in **declaration order** (below);
   at every `(yin/def <literal sym> v)` occurrence, a macro lambda `v`
   `assoc`es and anything else `dissoc`es; at every `:yin/macro-defined`
   stand-in, look up `:decl k` in this batch's catalogue and, if the entry
   exists and its `:name` equals the stand-in's, `assoc` *that entry's*
   lambda (any other stand-in — unknown `k`, mismatched name — is ignored).
   Last occurrence in declaration order wins. Position is a
   property of the tree, not of the node, so re-parenting cannot lose it;
   a definition formed by rewriting is present in the tree like any other;
   and a stand-in is recognised by shape, so passing through a macro cannot
   lose it either.

   **Declaration order** is *not* the expansion traversal order of §3.2
   (which is operator-first so that fresh ids are deterministic). yang
   lowers `(do A B)` to `((fn [_] B) A)` and `(let [x v] body)` to
   `((fn [x] body) v)`; operator-first would visit B before A. Declaration
   order is: for an `:application` whose operator is a `:lambda`, the
   operands left to right, then the lambda's body; for every other node,
   its children in the fixed order. This recovers source sequence order for
   every `do`/`let` nesting yang.clojure produces and for the Python/PHP
   suite lowerings, which use the same immediately-applied-lambda shape. It
   is deterministic, but *not* a universal source-order guarantee: a
   frontend construct lowered through a non-lambda operator (PHP's `for`,
   whose loop operator is a fixpoint) ranks operator syntax before outer
   operands. The claim made is the narrow one. **Ordering rule:** step 2's
   source-only store governs the whole current batch; step 5's result
   governs from the next batch. Consequences, stated: `(def m f)` then
   `(defmacro m …)` keeps the macro (its stand-in is later in declaration
   order); `(identity-macro (defmacro m …))` keeps it (the stand-in comes
   back by shape); `(keep-first (defmacro m … :A) (defmacro m … :B))` keeps
   **A** for the next batch — the surviving stand-in's `:decl` selects A's
   body, although B won step 2 for the current batch;
   `(defmacro m …)` then `(defn m …)` or `((choose-def) 'm f)` removes it
   (the formed plain definition is later); a source macro definition that
   an enclosing macro *discards* from its output is in force for the current
   batch (step 2) and absent from the next (not in the final tree) — the
   final tree is the program; within one batch a macro `m` and a `(defn m
   …)` both apply to their own forms in source order, as Clojure would not.
   The design accepts these one-batch divergences in exchange for a
   one-pass, deterministic rule that needs no origin tracking.
6. **Mark tail.** `mark-tail` recomputes `:tail?` over the whole final tree
   (§3.4).
7. **Emit.** Every `:yin/macro-defined` stand-in is lowered to
   `{:type :literal :value <sym>}`; output is then emitted bottom-up as a
   self-contained batch with a fresh `:yin/root` fact; the log copy carries
   `m` per §4.1. No evaluator ever sees the internal node type.
8. **Scan.** A final walk asserts no lambda with `:yin/macro? true` remains.
   With admission rejecting stray macro lambdas, step 3 replacing source
   definitions, and `:generated-macro` rejecting generated ones, this cannot
   fire; it is the guard for decision 5 and a violation is a forwarder
   defect, thrown as `{:kind :scan-failed :eid e}`.

An `:error` result carries the event (§4.1) in `:log` and the advanced
`:ctx`; `:datoms` is absent. `expand` unwraps `:ok` and throws on `:error`
for callers that want the exception. Same inputs, same output, on every
host.

**Working representation.** Traversal, cycle detection, and shadow tracking
run over *eids in the index*. Plain AST maps are materialised only at the
boundary of one body invocation — operands decoded for `invoke`, the returned
value validated and re-encoded to datoms with fresh ids, and re-expansion
continues over the index of the batch-so-far. Internal identity never
appears in the map representation macros see (`valid-ast?` rejects `:eid`).
The decode and encode at that boundary are the expander's own, aware of
`:yin/macro-defined`; the shared codec (`yin.vm.v2/ast->datoms`,
`datoms->ast`) never learns the type, which makes it a loud backstop — a
marker that somehow reached it would throw "Unknown AST node type" — rather
than a component of the inner loop. **Order-sensitive steps iterate the
datom vector, never the `group-by` index map:** harvest ordinals (step 2's
`k`) and the *first* violation admission reports (step 1's `:eid`) both
depend on it, and index-map iteration order is host-dependent (not
insertion order on cljs). The check *sets* are order-independent; the
ordinals and the reported entity are not.

**Allocation.** `:alloc` is seeded per batch to
`(min (:next-eid alloc) (dec min-eid-in-batch) (- (inc datom/first-user-id)))`
— the incoming watermark is a term, so the counter only ever decreases across
a session and event eids on the accumulated log never collide. It is returned
advanced in both `:ok` and `:error` results; a retry of a staged flush reuses
the staged allocation and never allocates again.

### 3.2 The algorithm

Traversal visits ref attributes in a **fixed order** so fresh ids and
gensyms are deterministic across hosts: `:yin/operator`, `:yin/operands`
(left to right), `:yin/test`, `:yin/consequent`, `:yin/alternate`,
`:yin/body`, `:yin/target`, `:yin/val-node`, `:yin/source`. This order is
for allocation determinism only; it is not source order (§3.1 step 5's
declaration order is). `shadow` is the set of names bound by enclosing
`:lambda :params`.

```
macro-of ctx eid shadow :
  case operator(eid)
    :variable v  → v ∉ shadow ∧ (:store ctx) v
    :lambda  l   → l when (:macro? l)     ; defensive only: unreachable after admission (§3.1) and :generated-macro
    _            → nil

expand-node ctx eid shadow depth :
  if type(eid) = :application ∧ (m = macro-of ctx eid shadow):
      ev        = fresh-event ctx eid m                  ; allocated first: every failure below is recorded on ev
      if depth ≥ :max-depth: fail ev {:kind :depth-guard :depth :chain}
      operands  = [decode c | c ∈ operands(eid)]         ; unexpanded AST maps
      out       = invoke m operands ctx                  ; §3.3 — arity, fuel, suspension fail on ev
      check valid-ast? out {:allow-macro-lambda? false}  ; node count ≤ :max-nodes-per-expansion
      root'     = encode out (fresh ids from :alloc)     ; joins the index of the batch-so-far
      record ev :yin/expansion-root root'
      return expand-node ctx root' shadow (depth+1)      ; re-expand the output under the call site's scope
  else if type(eid) = :application:
      op'       = expand-node ctx operator(eid) shadow depth        ; operator FIRST
      eid'      = if op' = operator(eid) then eid else copy eid with operator op'
      if macro-of ctx eid' shadow:
          return expand-node ctx eid' shadow depth        ; became a macro call: takes the first arm with the ORIGINAL operands
      operands' = [expand-node ctx c shadow depth | c ∈ operands(eid)]
      return if operands' = operands(eid) then eid' else copy eid' with operands'
  else:
      shadow'   = if type(eid) = :lambda then shadow ∪ params(eid) else shadow
      children' = [expand-node ctx c shadow' depth | c ∈ children(eid) in fixed order]
      return if children' = children(eid) then eid else copy eid with children'
```

`fail ev …` writes `:yin/error` on the event and unwinds to `expand-batch`,
which returns the `:error` result with that event in `:log`. Cycle and
dangling-ref detection happened at admission (§3.1 step 1), so this walk is
over a known-acyclic graph; output encoded from validated plain maps is
acyclic by construction.

The application branch expands the **operator before the operands** so that
an operator which rewrites into a macro name hands that macro its operands
*as written*: in `((choose) f [x] (x 1))` with `choose` → `defn`, `defn`
must receive `(x 1)` unexpanded so the lambda it builds can shadow `x`.
Expanding operands first would violate outermost-first for exactly the
macros (binders) that depend on it. Recognition is not an expansion — the
re-entry is at the same depth — and a successful re-check goes straight into
the invocation arm, which re-expands at `depth+1`, so a chain of generated
applications consumes depth and cannot rewrite forever at constant depth.

Properties, each a Phase 1 test:

- **Outermost first.** A macro sees its operands as written. This is what
  makes `->`-style macros expressible and what retires the shadow hints:
  in `(defn f [x] (x 1))`, `defn` expands *before* its operands are examined,
  yielding `(yin/def f (lambda [x] (x 1)))`; re-expansion then meets `(x 1)`
  under `:lambda [x]`, so `x` is shadowed and left alone even if a macro
  named `x` exists. yang's frontend hint existed only because the frontend
  had to decide this before expansion.
- **Fixpoint by construction.** Every node reachable from the final root
  was visited after its last rewrite, *including* an application whose
  operator was rewritten into a macro name by a child expansion
  (`((choose) x)` where `choose` expands to `m`): the application branch
  re-checks after expanding the operator and before touching the operands.
- **Depth** counts nesting of expansion inside expansion output. Direct
  recursion without a base case fails at 100 with
  `{:kind :depth-guard :depth 100 :chain [eid ...]}`. Siblings do not
  accumulate depth.
- **Shadowing is lexical and complete.** `let`, `do`, and every binder every
  frontend emits lower to `:lambda :params`; `yin/def` is global and is the
  store. There is no other binder in the Universal AST. The shadow set is
  extended *at the lambda node*, so an operator-position lambda's params
  shadow its body and not its sibling operands.
- **Generated macro lambdas are rejected in v1 — wherever they appear.** A
  macro whose output contains a `:macro? true` lambda, as a definition value
  *or* inline in operator position, fails `{:kind :invalid-output :reason
  :generated-macro}`; `valid-ast?` is called with `:allow-macro-lambda?
  false` on expansion output. With admission also rejecting source macro
  lambdas outside definition position, `macro-of`'s inline-lambda arm is
  unreachable and kept only as a defensive check. Macro-defining macros
  and generated inline macros are reserved together (§7); admitting the
  second without the first would need a rule for keeping a generated macro
  body as transformer syntax through re-expansion, which is the same
  problem.
- **Failure leaves a record.** The attempt event is allocated before the
  depth guard, resolution, and invocation, so every failure after that
  point is written with `:yin/error` (§4.1) and reaches the log through the
  `:error` result (§5).

### 3.3 Executing the macro lambda

`invoke` binds positional params one AST map each and `& rest` to a vector
of the remaining maps (arity mismatch is
`{:kind :arity :macro eid :expected n :got m}`) and calls
`((:eval ctx) lambda-ast env prelude budget)`.

The expander supplies its own body runner; today it is a throwaway
`yin.vm.v2.ast-walker` driven by `step` in a counted loop, because
`engine/run-loop` has no fuel and a body that loops forever would otherwise
stall the forwarder:

```
(defn run-bounded [lambda-ast env prelude budget]
  (let [vm (ast-walker/create-vm {:env env :primitives (merge vm/primitives prelude)})
        vm (ast-walker/vm-load-program vm (vm/ast->datoms (:body lambda-ast)))]
    (loop [vm vm, n 0]
      (cond (> n budget)            (throw (ex-info "Macro body exceeded step budget" {:kind :fuel-guard :steps n}))
            (vm/blocked? vm)        (throw (ex-info "Macro body suspended" {:kind :suspended}))
            (seq (:parked vm))      (throw (ex-info "Macro body parked" {:kind :suspended}))
            (seq (:ready-queue vm)) (throw (ex-info "Macro body scheduled work" {:kind :suspended}))
            (vm/halted? vm)         (vm/value vm)
            :else                   (recur (vm/step vm) (inc n))))))
```

This is a private detail of the expander, not a VM feature: the throwaway
VM is an ordinary evaluator running an ordinary lambda. It has no
`:make-stream` and no FFI pair, so stream and FFI effects fail with the
existing constructor errors; `yin/def` inside a body writes to the throwaway
store and is discarded; `require` resolves against `nil` modules and fails.
The body sees only its parameters, the prelude, and `yin.vm.v2/primitives` —
decision 6. Macro authors are told plainly: *a macro body may not call a
function the program defined, and may not call another macro: the body is
executed, not expanded, so a macro name inside it is an unbound variable.
Helpers for macros are prelude calls.* Expanding macro bodies before
storing them (which would allow macro helpers) is reserved (§7).

`:eval` and the prelude are trusted composition capabilities under a stated
contract: deterministic, no host IO, no retention of arguments. The fuel
budget bounds VM transitions, not the cost of one host primitive or the
traversal of one large literal; `:max-nodes-per-expansion` bounds AST nodes,
not literal payload size. Both are known limits of v1, recorded here rather
than solved: a composition that admits untrusted macro *authors* needs a
payload bound at admission (§3.1 step 1), which is where such a bound
belongs.

"Run a lambda under a step budget in a fresh VM" is the one capability the
expander needs that `yin.vm.v2` does not export today. It is a general
facility — any process that runs another's code wants it — and is the
reason the expander can later become a `yin.vm` program itself (§7,
Reserved) without any macro-specific primitive.

**Validation.** The same `valid-ast?` runs on what goes *in* (every operand
AST decoded for a body, and every ground value at admission) and on what
comes *out* (the body's value): a closed, plain-data Universal AST. It
enforces, per node, exactly the vocabulary `ast->datoms-with-root` emits:

+---------------------------------------------------+--------------------------------------+-----------------------------------------------------------------------------+
| `:type`                                           | Required                             | Optional                                                                    |
+===================================================+======================================+=============================================================================+
| `:literal`                                        | `:value` plain data                  | `:tail?`                                                                    |
+---------------------------------------------------+--------------------------------------+-----------------------------------------------------------------------------+
| `:yin/macro-defined`                              | `:name` symbol, `:decl` int          | `:tail?` (transiently, from step 6's recompute before step 7 lowers it) —   |
|                                                   |                                      | expander-internal, §3.1 step 3; accepted in operands and output as a        |
|                                                   |                                      | *node*, never emitted. A map of this shape nested inside any plain-data     |
|                                                   |                                      | payload (`:literal :value`, `:vm/store-put :val`) is `{:kind                |
|                                                   |                                      | :invalid-output :reason :marker-in-payload}`: payloads are emitted verbatim |
|                                                   |                                      | by the codec and would carry the internal shape to `program-out` as inert   |
|                                                   |                                      | data                                                                        |
+---------------------------------------------------+--------------------------------------+-----------------------------------------------------------------------------+
| `:variable`                                       | `:name` symbol                       | `:tail?`                                                                    |
+---------------------------------------------------+--------------------------------------+-----------------------------------------------------------------------------+
| `:lambda`                                         | `:params` vector of symbols, `:body` | `:tail?` `:macro?`                                                          |
+---------------------------------------------------+--------------------------------------+-----------------------------------------------------------------------------+
| `:application`                                    | `:operator`, `:operands` vector      | `:tail?`                                                                    |
+---------------------------------------------------+--------------------------------------+-----------------------------------------------------------------------------+
| `:if`                                             | `:test` `:consequent` `:alternate`   | `:tail?`                                                                    |
+---------------------------------------------------+--------------------------------------+-----------------------------------------------------------------------------+
| `:dao.stream.apply/call`                          | `:op` keyword, `:operands`           | `:tail?`                                                                    |
+---------------------------------------------------+--------------------------------------+-----------------------------------------------------------------------------+
| `:vm/gensym`                                      |                                      | `:prefix` string                                                            |
+---------------------------------------------------+--------------------------------------+-----------------------------------------------------------------------------+
| `:vm/store-get`                                   | `:key`                               |                                                                             |
+---------------------------------------------------+--------------------------------------+-----------------------------------------------------------------------------+
| `:vm/store-put`                                   | `:key` `:val` plain data             |                                                                             |
+---------------------------------------------------+--------------------------------------+-----------------------------------------------------------------------------+
| `:vm/park`, `:vm/current-continuation`            |                                      |                                                                             |
+---------------------------------------------------+--------------------------------------+-----------------------------------------------------------------------------+
| `:vm/resume`                                      | `:parked-id` keyword, `:val`         |                                                                             |
+---------------------------------------------------+--------------------------------------+-----------------------------------------------------------------------------+
| `:stream/make`                                    |                                      | `:buffer` int                                                               |
+---------------------------------------------------+--------------------------------------+-----------------------------------------------------------------------------+
| `:stream/put`                                     | `:target` `:val`                     |                                                                             |
+---------------------------------------------------+--------------------------------------+-----------------------------------------------------------------------------+
| `:stream/cursor`, `:stream/next`, `:stream/close` | `:source`                            |                                                                             |
+---------------------------------------------------+--------------------------------------+-----------------------------------------------------------------------------+

Any other `:type`, any key outside the row, any `:eid`, a `:macro? true`
lambda where `:allow-macro-lambda?` is false, or any non-plain value
anywhere (recursively through vectors, maps, and sets, including inside
`:literal :value`) is `{:kind :invalid-output :macro eid :at [path]}` (or
`:malformed-input` at admission).
*Plain data* is nil, booleans, numbers, strings, keywords, symbols, and
vectors/maps/sets thereof. `:vm/store-update` is absent: it is a walker-only
transition with no datom form. The walk also counts nodes for the
per-expansion bound, checked *before* emission so oversized output never
materialises.

**The prelude** — primitives merged into the throwaway VM for one body:

+-------------------------------------------------------------------------+----------------------------------------------------------------------------------------------+
| Primitive                                                               | Meaning                                                                                      |
+=========================================================================+==============================================================================================+
| `yin/node-type ast`, `yin/node-get ast k`                               | accessors                                                                                    |
+-------------------------------------------------------------------------+----------------------------------------------------------------------------------------------+
| `yin/literal v`, `yin/variable sym`                                     | leaf constructors                                                                            |
+-------------------------------------------------------------------------+----------------------------------------------------------------------------------------------+
| `yin/lambda params body`, `yin/application op operands`, `yin/if t c a` | node constructors                                                                            |
+-------------------------------------------------------------------------+----------------------------------------------------------------------------------------------+
| `yin/sequence-body [ast ...]`                                           | the `do` desugaring yang uses; empty → `nil` literal                                         |
+-------------------------------------------------------------------------+----------------------------------------------------------------------------------------------+
| `yin/make-lambda params-ast body-ast`                                   | `params-ast` is a `:literal` whose value is the param vector                                 |
+-------------------------------------------------------------------------+----------------------------------------------------------------------------------------------+
| `yin/make-def name-ast value-ast`                                       | `(yin/def <sym> value)`; `name-ast` may be `:variable`, `:literal` symbol, or `:literal`     |
|                                                                         | string                                                                                       |
+-------------------------------------------------------------------------+----------------------------------------------------------------------------------------------+
| `yin/name-of ast`                                                       | the symbol named by any of those three shapes; error otherwise                               |
+-------------------------------------------------------------------------+----------------------------------------------------------------------------------------------+
| `yin/gensym-sym prefix`                                                 | fresh symbol `prefix__<E>_<n>`, `E` = absolute event eid (unique across a session by the     |
|                                                                         | allocation rule, §3.1), `n` invocation-local                                                 |
+-------------------------------------------------------------------------+----------------------------------------------------------------------------------------------+

No constructor marks tail positions (§3.4). A macro that wants to emit a
call to another macro emits an ordinary `yin/application`; re-expansion
handles it. **Hygiene** is non-hygienic by default with `yin/gensym-sym` on
request; automatic hygiene is reserved.

`stdlib-forms` (the `defn` macro) is recovered from
`d8b27a5^:src/cljc/yin/vm/macro.cljc` with its shadow metadata removed:

```clojure
'[(defmacro defn [fn-name fn-params & body]
    (let [b (yin/sequence-body body)
          l (yin/make-lambda fn-params b)]
      (yin/make-def fn-name l)))]
```

### 3.4 Tail positions in expansion output

Tail-ness is contextual, so constructors cannot mark it, and a macro can
move syntax that *was* in tail position into an operand or a test. `mark-tail
ast` is therefore one deterministic pass over the **whole final tree** after
expansion (§3.1 step 6) that *recomputes* every `:tail?` — setting it where
the computed context is tail and **removing** it where it is not. The
context rules, one per accepted node type:

- the program root is tail;
- `:if`: `:test` is non-tail; `:consequent` and `:alternate` inherit;
- `:application`: `:operator` and every operand are non-tail; the node
  itself carries its context's flag;
- `:lambda`: the body is tail — always, relative to the lambda's own return,
  regardless of where the lambda sits. An immediately applied lambda
  (yang's `let`/`do`) is a lambda; `linearize` decides whether to inline it,
  and if it does, it owns the consequence;
- `:dao.stream.apply/call`, `:stream/put`, `:stream/cursor`, `:stream/next`,
  `:stream/close`, `:vm/resume`: every child is non-tail; the node carries
  its context's flag;
- leaves and childless nodes carry their context's flag.

The walker never reads `:tail?`; `linearize` and the stack VM do. Without
this pass a tail-position macro expanding to an ordinary application would
push one frame per iteration on the semantic VM while the walker stayed
flat; with an *additive* pass, a stale flag on syntax moved into an operand
would let the semantic VM drop a continuation it still needs. Under the
semantic spec's current lowering contract — every lambda body is emitted
out of line ending in `:return`, no inlining — the lambda rule above is
exact, not approximate; an inlining optimisation added later inherits the
obligation to recompute tail context for the spliced body.

### 3.5 Guards

+---------------------------------------+------------------------------------+-----------+-------------------------------------------------------------------------------+
| Guard                                 | Scope                              | Default   | Error                                                                         |
+=======================================+====================================+===========+===============================================================================+
| depth                                 | per expansion chain                | 100       | `{:kind :depth-guard :depth :chain}`                                          |
+---------------------------------------+------------------------------------+-----------+-------------------------------------------------------------------------------+
| nodes per expansion                   | one macro's output                 | 10,000    | `{:kind :datom-guard :scope :expansion :emitted :call}`                       |
+---------------------------------------+------------------------------------+-----------+-------------------------------------------------------------------------------+
| datoms per batch                      | cumulative over one `expand-batch` | unlimited | `{:kind :datom-guard :scope :batch :emitted}`                                 |
+---------------------------------------+------------------------------------+-----------+-------------------------------------------------------------------------------+
| steps                                 | one body invocation                | 100,000   | `{:kind :fuel-guard :steps}`                                                  |
+---------------------------------------+------------------------------------+-----------+-------------------------------------------------------------------------------+
| cyclic / dangling / host-valued input | admission                          | —         | `{:kind :malformed-input :reason :cyclic\|:dangling-ref\|:host-value :eid}`   |
+---------------------------------------+------------------------------------+-----------+-------------------------------------------------------------------------------+
| arity / output / suspended            | —                                  | —         | `{:kind :arity}` `{:kind :invalid-output :at}` `{:kind :suspended}`           |
+---------------------------------------+------------------------------------+-----------+-------------------------------------------------------------------------------+

Guard failures are `:error` results (decision 11); `:malformed-input` is
too, so a corrupt batch is logged and skipped rather than re-read forever.

---

## 4. Provenance

### 4.1 The expansion event

Every attempt, successful or not, produces one event; so does every batch
rejected at admission. Events and the `m`-tagged output are appended to the
**log** medium, when the expander has one; `program-out` receives only
program.

```
[ev :yin/type           :macro-expand-event  t default-op]
[ev :yin/source-batch   <ctx :t>             t default-op]   ; which source batch; always present
[ev :yin/source-call    <call-eid>           t default-op]   ; eid in that batch, or in the log copy of the enclosing expansion; absent on an admission failure
[ev :yin/macro-name     <sym>                t default-op]   ; when the operator was a :variable; absent for an inline macro lambda or an admission failure
[ev :yin/macro          <lambda-eid>         t default-op]   ; only when durable (§4.2)
[ev :yin/expansion-root <root'>              t default-op]   ; on success; names the LOG copy
[ev :yin/error          {:kind ...}          t default-op]   ; on failure; plain data naming entities, never carrying input values
[ev :yin/timestamp      <ctx :t>             t default-op]   ; logical, never a host clock
```

An **admission-failure event** is the same entity with only `:yin/type`,
`:yin/source-batch`, `:yin/error`, and `:yin/timestamp`: the batch had no
usable call or macro to name. It is allocated from `:alloc`'s watermark
(which needs no index), so a corrupt batch still produces exactly one
plain-data event, advances `:ctx` once, and is retried through the same
per-medium staging as any other log payload — never regenerated.

The log copy of expansion output carries `m = ev`; log copies of unchanged
operands and ancestors carry `m = default-op` (re-parenting, not macro
output). **`program-out` datoms all carry `default-op`**: `m = ev` would be
a cross-medium ref (§4.2). A nested expansion's `:yin/source-call` names a
node in the log copy of the enclosing expansion's output, which is how the
chain source → event → root → event → root is walked for generated syntax.
"Every datom this expansion produced" is `[?d _ _ _ ?ev]` on the log.

`yin.vm.v2.macro/event-schema` declares these attributes (`:yin/source-call`,
`:yin/macro`, `:yin/expansion-root` as refs) for compositions that commit a
log to `dao.space`; the transactor relocates only declared refs, so a
composition merges this fragment with `yin.vm.v2/schema` before committing.

### 4.2 Identity across media

`program-in`, `program-out`, and the log are different media. A ref written
on one that names an entity on another is meaningful only when the named
id is **durable** — a permanent positive id from a committed stream. The
transactor (`dao.space.transact`) resolves tempids within one transaction;
committing two batches separately does not connect them. Therefore:

- `program-out` batches are self-contained: unchanged subtrees are copied,
  with fresh ids, not referenced (decision 8).
- `:yin/source-call` on an event names the call's eid *as it appeared in
  the source batch*, qualified by `:yin/source-batch`; it is descriptive
  until both media are committed under one resolution. `:yin/macro` is
  written only when the macro's id is durable; otherwise `:yin/macro-name`
  carries the name. Names are metadata, never identity repair.
- `:yin/expansion-root` names the **log copy** of the output. The executed
  program on `program-out` is a distinct copy with distinct ids; this design
  promises no id-level correspondence between them. A composition that needs
  one commits program and log in one transaction (they are one batch to the
  transactor) or records the mapping itself.
- A pipeline that wants queryable provenance commits `program-in` first and
  expands against durable ids — the ordering rule of
  `cross-language-macro.md` §4, retained. `ADR 0003` makes `dao.space` the
  medium for that commit.

### 4.3 Authority

Write authority on a medium is the whole model. Defining a macro is
appending a definition to `program-in`; running a program is appending to
`program-out`; both are decided by whoever holds the writer, and
`dao.stream.md` puts that decision in the composition. A Shibi
capability, when it exists, is verified at the medium's boundary before the
append, not inside any VM. The old `:macro-authorize` VM option is gone
because there is nothing left inside a VM to authorize.

---

## 5. The expander as an observer

The expander is driven by `dao.stream.v2.observer/run-on-stream` with
its state in the `:consumer` slot — the coordination inspects no field of
the consumer, so it drives an expander as readily as a VM:

```
expander = {:ctx ctx
            :out writer        :out-staged  nil|datoms
            :log writer|nil    :log-staged  nil|datoms
            :forwarded 0       ; batches appended ok to :out since the last drain
            :errors []}        ; expansion failures since the last drain

ready?       (fn [x] (and (nil? (:out-staged x)) (nil? (:log-staged x))))
load         (fn [x batch]
               (let [r (expand-batch batch (:ctx x))]
                 (-> x (assoc :ctx (:ctx r))
                       (assoc :out-staged (when (= :ok (:status r)) (:datoms r)))
                       (assoc :log-staged (when (:log x) (:log r)))
                       (cond-> (= :error (:status r)) (update :errors conj (:error r))))))
run          flush                    ; below
drain-errors (fn [x] [(assoc x :errors [] :forwarded 0) {:errors (:errors x) :forwarded (:forwarded x)}])
```

`:errors` and `:forwarded` are **read-and-reset** by the driver through
`drain-errors`, once per driver round, after `run-on-stream` returns and
before the driver decides what to do next. Nothing else clears them; a
driver that never drains sees an ever-growing list, which is its bug, not a
stuck expander (the cursor has moved on regardless).

**Flush** delivers each staged payload to its own medium and clears *that*
slot on `ok`, independently:

+----------------------------------------------+-------------------------------------------------------------------------------------------------------------------------+
| Outcome on a medium                          | Effect on its slot                                                                                                      |
+==============================================+=========================================================================================================================+
| `ok`                                         | slot ← nil; for `:out`, `:forwarded` += 1                                                                               |
+----------------------------------------------+-------------------------------------------------------------------------------------------------------------------------+
| `full`                                       | slot kept; expander stays not-ready; the same payload is retried next round. `full` may be permanent; retention is      |
|                                              | correct, progress is the composition's to arrange                                                                       |
+----------------------------------------------+-------------------------------------------------------------------------------------------------------------------------+
| `closed`, `invalid-value`, `transport-error` | legitimate protocol outcomes that this forwarder cannot continue from: throw, preserving the original outcome and both  |
|                                              | slots' state in the thrown value, so the composition decides                                                            |
+----------------------------------------------+-------------------------------------------------------------------------------------------------------------------------+

Once a medium has answered `ok` its payload is never appended again by an
ordinary retry, so `out = ok, log = full` re-tries only the log. A batch
that expands with `:error` stages nothing for `program-out` and stages the
event for the log; the source cursor advances (the load returned), the
failure is on the log, and the driver drains it. Program and log are
independent media: this design offers *at-most-once per medium* and batch
order per medium under ordinary retries, not atomic visibility across the
two and not crash recovery; a composition that needs atomicity commits both
to one `dao.space` transaction (§4.2).

`run-on-stream`'s contract supplies the rest: a `full` leaves the expander
not-ready with the exact payload staged, so the next round retries the
append before observing again; `gap` on `program-in` is counted by the
observer and is the composition's policy, as for any observer. What remains
a *throw* out of `load` is a forwarder defect (codec failure, index
failure); the cursor does not advance and the batch is re-read, which is
the correct behaviour for a bug and the wrong one for bad input — hence
decision 11.

**Prerequisite on the observer (Phase 0).** `run-on-stream` today publishes
its successor session only on return. If it forwards A, then B's load or run
throws, the caller still holds the pre-A session and a retry forwards A again
(reproduced by the round-2 review against the current observer). This is a
defect of the generic coordination, not of this design — a VM loader that
throws on B after loading A has the same problem — and it is fixed there.
The fix **keeps the throw** and carries the partial session in the
exception's data: `(ex-info … {:session {:observer o' :consumer c'} …})`. A
return-shaped `{:observer :consumer :error}` was considered and rejected: every
existing caller reasons "a throw means the round failed", and a value-shaped
error would let an un-updated caller continue silently past a defect. With
the throw kept, recovery is opt-in: a caller that catches resumes from
`:session`; one that does not keeps today's semantics. The carried session
must distinguish *where* the failure was: a **load** failure carries the
cursor before the failing batch (it will be re-read); a **run/flush**
failure after a successful load carries the cursor *after* that batch and
the `:consumer` as the failing `run` left it (for the expander: its partially
flushed slots), so a retry neither re-reads B nor re-appends what B already
delivered. This document depends on that fix and does not work around it.

```
  yang ──▶ program-in ──observe──▶ expander ──append──▶ program-out ──observe──▶ evaluator
                                       │
                                       └──append──▶ log (events, m-tagged output)
```

Every evaluator observes `program-out` through its unchanged loader:
`ast-walker/vm-load-program`, `linearize/loader ∘ semantic`, the stack
compiler. None composes anything macro-related.

---

## 6. Compositions

### 6.1 `yin.repl.v2`

`make-session` builds `program-in`, the expander (with `stdlib-forms`
pre-loaded into its store), `program-out`, an optional log medium, and the
evaluator's observer on `program-out`. An evaluation round is: append the
frontend's datoms to `program-in`; drive the expander with `run-on-stream`
until ready; `drain-errors`; **if `:forwarded` > 0**, drive the evaluator
with `run-on-stream` on `program-out` and drain output; **if `:errors` is
non-empty**, print them. Both can hold in one round when the expander
consumed more than one source batch (A forwarded, B failed): A runs once, B
is reported once. The shell appends exactly one batch per input, so in the
ordinary case one input yields one result, and correlation needs no further
machinery. `eval-ast` takes this same path (`vm/eval` is no longer a program
path in the shell). `(compile expr)`
renders AST, source datoms, expanded datoms, and events. `(reset)` and
`(vm ...)` rebuild the whole session, which clears the expander's store with
the evaluator's. `repl-state` lists the macro names the expander's store
holds, so an "unable to resolve `defn`" is diagnosable at a glance.

The shell's frontends are untouched: `yang.clojure/compile` and
`compile-program` lose their `macro-env` arities; `yang.python` and
`yang.php` never had any and gain none.

### 6.2 `yang.clojure`

`defmacro` stays a special form lowering to `(yin/def name <lambda macro?
true>)`. Deleted: `initial-macro-env`, `shadow-macro-env`,
`compile-macro-operands`, `compile-defn`'s native fallback (the `defn` macro
is always in the expander's store), the `macro-env` parameter on every
`compile-*`, and `:yin/macro-expand` emission. `compile-program` returns
AST only.

### 6.3 Non-homoiconic frontends

`yang.python` and `yang.php` emit `:application` for every call, as they do
today. A call to a macro defined in Clojure is expanded because the
expander's store has it — cross-language macro use costs the frontend
nothing. Authoring macros from Python or PHP is the prelude behind a
`defmacro` surface and is reserved; the Python tokenizer has no list-literal
production, so a Python `defn` call is not yet expressible.

### 6.4 Build pipelines

A build composes the same forwarder between file-backed media
(`dao.stream.file.md`), committing `program-in` to `dao.space` first when it
wants durable provenance (§4.2). The expander's store can be seeded from a
committed stream with `definitions`; a DaoDB query for
`[?e :yin/macro? true]` joined to its `yin/def` is a future producer of the
same map.

### 6.5 Cross-platform

One `.cljc`; the expander is maps, vectors, and `reduce`. Symbols from
`yin/gensym-sym` are built with `symbol`. The `#?(:cljd …)`-first
conditional and `#?(:cljd Object :clj Throwable :cljs :default)` catch form
are used as elsewhere in v2. Test discovery: confirm "Testing
yin.vm.v2.macro-test" in the shadow `:node-test` output.

---

## 7. Phased implementation roadmap

### Phase 0 — Spec and codec

- This document. `macro-design.md` and `cross-language-macro.md` amended to
  point here. The divergence register's macro item becomes: "user-defined
  macros are expanded by a process between media, never by an evaluator;
  call sites are ordinary applications; `eval` does not expand." Its Macros
  section records the deviations of Appendix A.
- `yin.vm.v2`: `schema` gains `:yin/root` and `:yin/macro-name`, drops
  `:yin/phase-policy`, `:yin/phase`, `:yin/capability`, `:yin/source-call`,
  `:yin/macro`, `:yin/expansion-root`, `:yin/error` (event attributes move
  to the expander's emitter). `ast->datoms-with-root` emits the root fact;
  `index-datoms` honours it (last wins, dangling error); the codec drops the
  `:yin/macro-expand` arms and the `:phase-policy` handling.
- `ast-walker`: the `:lambda` arm and `datoms->ast` continue to ignore
  `:macro?`; nothing else changes. The namespace docstring's "no macro
  branch" remark becomes the statement of decision 1.
- `dao.stream.v2.observer/run-on-stream`: throw with the partial session in
  `ex-data` (§5 prerequisite); existing callers are unchanged. Tests: A
  forwarded, B's load throws — the carried cursor is before B and a retry
  from it leaves exactly one A on the destination; A forwarded, B's `run`
  throws — the carried cursor is after B and `:consumer` is as `run` left it; a
  terminal read after a successful batch carries the post-batch session.
- New `test/yin/vm/v2/v2_test.cljc` for the codec: root fact wins; dangling
  root errors; heuristic fallback unchanged; `compile` output unchanged
  *modulo the root fact* for macro-free programs (the fact is unconditional).

### Phase 1 — Expander and yang

Deliverables: `src/cljc/yin/vm/v2/macro.cljc` (`expand-batch`, `expand`,
`definitions`, `invoke`, `bind-params`, `valid-ast?`, `mark-tail`,
`run-bounded`, prelude, `stdlib-forms`, `step`); `yang.clojure` per §6.2.

Tests (`macro_test.cljc`): `defn` via `stdlib-forms` equals the previously
native lowering; definition consumed and replaced by a name literal; a
definition in an unexecuted branch is harvested; last definition wins;
**redefinition: `(defmacro m)` then source `(def m f)` removes the macro
in-batch; `(def m f)` then `(defmacro m)` keeps it next batch; `(defmacro
m)` then `(defn m …)` removes it next batch; an existing macro and a
separate `(defn m …)` batch removes it next batch; `(defn m [x] (wrap x))`
with `wrap` a macro (the formed definition is re-parented by a descendant
expansion) removes it next batch; `((choose-def) 'm f)` with `choose-def` →
`yin/def` (a definition formed by operator rewrite, `default-op`) removes it
next batch; each of the source cases repeated with the definition node
re-parented (fresh eid) by an unrelated expansion in the same batch; **every
ordering case run through real `compile-program`/`do`/`let` lowering,
including nested sequences, not flat definition lists**; a source macro
definition discarded by an enclosing macro is absent next batch; **an
identity macro and a wrapping macro both preserve a `defmacro` operand's
declaration for the next batch; two same-name `defmacro`s under a macro
that keeps the first yields the first's body next batch, keeps the second
yields the second's, swaps them yields the later-ranked one's, duplicates
one yields that one's; an ordinary `'m` literal declares nothing; a
fabricated stand-in with an unknown `:decl`, or a known `:decl` with a
mismatched `:name`, changes nothing; a fabricated stand-in with a valid
`:decl` placed after a plain `yin/def m` re-asserts that declaration (the
stated permitted behaviour); no `:yin/macro-defined` reaches
`program-out`**; a bare `:macro? true` lambda outside a definition fails
admission as `:stray-macro-lambda`;
outermost-first (`->`-style macro); shadowing by `let`, `fn`, and by a
`defn`'s own params without any frontend hint; `(fn [m] (m 1))` with `m` a
macro is left intact; a macro name in operand position is left as a
variable; **an operator macro returning a macro name is expanded with its
operands unexpanded (`((choose) f [x] (x 1))` with a macro `x` in scope
yields a lambda whose body still says `(x 1)`), and an operator-selected
macro that discards an operand containing a failing macro call succeeds**;
a macro emitting a macro lambda — as a definition or inline — fails
`:generated-macro`; depth guard with chain; per-expansion bound from node
count before emission; per-batch bound; step budget on an infinite body;
parked body rejected; **cyclic operand, dangling ref, unknown node type, a
host value in a literal, and a cycle inside a disconnected definition all
fail at admission before any decode, each producing one admission event
that survives a log `full`**; invalid output including a host function inside a
literal and an `:eid` key; arity and `&`; **gensyms differ across two
batches with overlapping tempids when ctx is threaded, and are equal on
replay of the same ctx**; a stream effect in a body fails with the
constructor error; `mark-tail` on `if`/`let`/lambda spines **and clears a
stale flag on marked syntax moved into an operand and into an `if` test**;
a macro-free deeply nested batch passes; determinism (two runs, equal
vectors); macro-free batch returned equal modulo the root fact; a
corpus-scale batch at 80% of a configured per-batch bound passes; the
forwarder driven through `observer/run-on-stream`: `out=ok, log=full`
retries only the log; `out=full` retries only the program; repeated `full`;
no log medium; **an expansion failure advances the cursor, stages only the
event, and appears in `drain-errors`; a failed batch then a successful one
in a single `run-on-stream` call yields `:forwarded 1` and one error; a
drained expander reports nothing on the next drain**; a forwarder-defect
throw does not advance the cursor.

### Phase 2 — REPL composition

Deliverables per §6.1. Tests: define in one input, use in the next; use
from `:python` after defining in `:clojure`; `(reset)` forgets; `(compile)`
renders four stages; a runtime error from a macro-named variable in operand
position is an unbound-variable error, not a call; a bad macro input prints
its error and the *next* input evaluates normally (no head-of-line block);
`repl-state` lists the store's macro names.

Deferred to the semantic VM's own Phases 1–2 (`yin.vm.v2.semantic` and
`linearize` do not exist yet): the `:semantic` VM type runs the same session
with equal values, and a tail-position macro in a 10⁵-iteration loop keeps
continuation depth 0 — measured by inspecting `k`, since equal values alone
do not prove it.

### Phase 3 — Cross-language verification

Tests (`python_macro_test.clj`, `php_macro_test.clj`): a scalar-signature
Clojure macro `(defmacro twice [f] (yin/application f [(yin/application f
[(yin/literal 1)])]))` called as `twice(inc_fn)` from Python and
`twice($inc_fn)` from PHP; `yin/name-of` with a Python string, a Clojure
symbol, and a `:variable`; a PHP `for`-loop variable shadowing a macro name;
the source batch unchanged after expansion; equal values on walker and
semantic. Python `defn` stays reserved until the parser has list literals.

### Reserved

The expander as a `yin.vm` program (requires a bounded-child-evaluation
primitive — the general form of `run-bounded`); macro-defining macros
(generated definitions, with a precedence rule for definitions introduced
mid-batch); macro helpers via expansion of macro bodies before storing;
payload-size bounds at admission for untrusted authors; Shibi at the medium
boundary; automatic hygiene; DaoDB-backed store seeding; durable
cross-medium provenance and program↔log correspondence as a transactor
concern; expansion memo keyed by call structure (sound because expansion is
pure, but each expansion mints its own event and gensyms); Python/PHP
`defmacro` surfaces.

---

## Appendix A. Deviations from prior documents, with reasons

From `macro-design.md` (v1):

1. **AST maps, not eids, as macro arguments.** Every frontend speaks maps;
   the datom form is the transport, not the API.
2. **Outermost-first, not post-order.** `->`-style macros are impossible
   innermost-first, and outermost-first is what makes lexical shadowing
   computable without frontend hints.
3. **Fixpoint is recursive re-expansion of output**, not "a pass emits zero
   new macro datoms" — per node rather than per sweep, which is what makes
   depth a meaningful guard.
4. **No phase policy, no `maybe-recompile-at-boundary`.** Both existed to
   describe expansion happening inside an executing VM.

From `cross-language-macro.md`:

5. **Frontends do not discover or coerce.** §3 of that document made the
   frontend responsible for knowing which names are macros and for
   normalizing arguments; both are the expander's, and a call site is an
   ordinary application. Its ordering rule (§4: commit definitions first for
   durable ids) is retained.

From the previous revision of this document — the evaluator-integrated
design, withdrawn 2026-09-13:

6. **No runtime expansion inside evaluators.** That design gave the walker
   a `:yin/macro-expand` transition, the semantic VM a `:macro-call`
   instruction with ephemeral segments and a retention problem, and the
   stack VM `OP_MACRO_EXPAND`. All of it was the cost of running a
   syntax-side process inside a semantics-side VM. With expansion on its
   own medium the machinery has nowhere to live. What runtime expansion
   bought — "the definition arrived after the program loaded" — is, in a
   stream, "another batch arrived".
7. **No ledger on the VM, no `:macro-eval` / `:macro-authorize` /
   `:capability` options, no `:macro-alloc`, no snapshot fields.** The
   expander owns its state and its log writer.
8. **No macro flags on closures and no "macro applied as function" checks**
   at three hot-loop sites (its decision 10). Definitions never reach an
   evaluator (decision 5), so there is no closure to flag; a missed macro is
   an unbound variable.
9. **No `:yin/macro-expand` node type** and no frontend `macro-env`
   threading through `yang.clojure`, `yang.python`, `yang.php`, or the
   shell. In datoms, operands are already syntax.
10. **No shadow hints** (`:yang/shadow-params-operand`,
    `:yang/shadow-body-start`, and their proposed promotion to facts).
    Outermost-first plus lexical shadow tracking in the expander subsumes
    them (§3.2).
11. **Self-contained output instead of shared subtrees by reference.** That
    design's `:existing` emitter option, structural identity rule,
    `:keep-eids?` predicate, and forged-eid check served cross-batch
    sharing that is only meaningful for durable ids. Copying is the honest
    baseline; sharing returns, if at all, as a transactor concern.
12. **Events on a log medium, not interleaved with program.** That design
    needed `index-datoms` to exclude event entities from root discovery;
    here the evaluator never sees one.
13. **Kept from that revision:** the explicit `:yin/root` fact (now a codec
    improvement on its own merits); the closed-vocabulary validator with
    recursive plain-data checks; dual guards and the fixed traversal order;
    `mark-tail` as an expander pass; the fuelled body runner; closed syntax
    transformers with no store access; `:yin/macro-name` and logical
    `:yin/timestamp`; the loader-adapter (never `comp`) correction to the
    semantic spec, which stands independently.

## Appendix B. Risks the implementer should watch

- Land `:yin/root` in the codec before the expander; until then an expanded
  batch's root is found by heuristic and copy order matters.
- Definitions must be *replaced*, not merely recorded; a forwarded
  `(yin/def defn <lambda>)` would give the evaluator a plain function named
  `defn` — the exact footgun decision 5 exists to prevent. The Phase 1 final
  scan is the guard.
- The shadow set must include the params of a lambda in *operator* position
  before descending into its body; `let` and `do` lower to exactly that
  shape.
- `mark-tail` must recurse through `:if` branches and immediately-applied
  lambdas; the semantic-VM tail-loop test is the guard.
- `run-bounded` must check `:parked` and `:ready-queue` explicitly:
  `:vm/park` sets `:halted? true` and `halted?` does not inspect them.
- Do not let `:staged` be dropped on `full`; `run-on-stream` relies on
  `ready?` staying false until the append succeeds.
- yang deletions in §6.2 remove the native `defn` lowering; every test that
  used `defn` without `stdlib-forms` now depends on the expander's seeded
  store.
- Admission must run cycle and dangling-ref checks over the *index*, never
  by calling `datoms->ast` first: the decoder is recursive and overflows on
  a cycle before any check runs.
- `mark-tail` is a recompute over the final tree, not an additive pass over
  each expansion's output; an additive pass leaves stale flags on moved
  syntax.
- Land the `run-on-stream` progress-on-error change before the REPL wires
  the expander, or a forwarder-defect throw after a successful batch
  duplicates that batch on retry.

## Appendix C. Round-2 review findings and their resolution

+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| #  | Finding (reviewer)                                                             | Resolution                                                                       |
+====+================================================================================+==================================================================================+
| 1  | Dual-medium flush duplicates the program on `out=ok, log=full` (astra P1-2,    | §5 per-medium staging; at-most-once per medium stated; atomicity explicitly not  |
|    | glm P2-2)                                                                      | promised                                                                         |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 2  | Failures never reach the log; deterministic failure head-of-line blocks        | Decision 11; §3.1 `:error` result carrying the event and `:ctx`; §5 stages only  |
|    | `program-in` (glm P2-1, astra P2-4)                                            | the log, advances, reports `:errors`; §6.1 REPL prints and continues; throw      |
|    |                                                                                | reserved for forwarder defects                                                   |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 3  | Later exception replays earlier forwarded batches (astra P1-1)                 | Accepted as a defect of `run-on-stream`, not of this design; §5 prerequisite and |
|    |                                                                                | Phase 0 deliverable on `dao.stream.v2.observer`: return progress with the error         |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 4  | Operator rewritten into a macro name is not re-expanded (astra P1-3)           | §3.2 re-checks the rebuilt node once at the same depth                           |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 5  | Generated definitions neither installed nor replaced (astra P1-3)              | §3.2 rejected in v1 (as `:generated-definition`, widened to `:generated-macro`   |
|    |                                                                                | in row 20); macro-defining macros reserved                                       |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 6  | Stale store after plain redefinition expands dead code (glm P2-3)              | §3.1 harvest is authoritative for every `yin/def`: plain lambda `dissoc`es; §2.2 |
|    |                                                                                | two-namespaces paragraph; Phase 1 test                                           |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 7  | Alloc not monotonic; gensyms repeat across batches (astra P2-5, glm P3-4)      | §3.1 seed includes `(:next-eid alloc)`; retry reuses staged allocation           |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 8  | Final scan must be scope-aware (astra P2-6, glm P3-8)                          | §3.1 step 8 scans only for `:macro?` lambdas; application fixpoint is by         |
|    |                                                                                | construction via #4                                                              |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 9  | Tail marking must clear stale flags; lambda rule ambiguous (astra P2-7)        | §3.4 whole-tree recompute with per-node context rules; lambda body always tail;  |
|    |                                                                                | `linearize` owns inlining                                                        |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 10 | Provenance identity: same tempid in two batches, generated syntax, program↔log | §4.1 `:yin/source-batch`; nested `source-call` into the log copy;                |
|    | correspondence, event schema home, anonymous macros (astra P2-8)               | `expansion-root` names the log copy, correspondence not promised;                |
|    |                                                                                | `macro/event-schema`; `:yin/macro-name` absent for inline lambdas                |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 11 | Output validation alone does not close the sandbox input (astra P2-9)          | §3.1 step 1 admission validates ground values; §3.3 validates operands going in; |
|    |                                                                                | `:eval`/prelude trust contract stated; fuel and payload limits recorded as known |
|    |                                                                                | v1 limits                                                                        |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 12 | Cycle detection after recursive decode overflows; `eid(node)` undefined on     | §3.1 admission over the index; working-representation paragraph; Appendix B      |
|    | maps (astra P2-10, glm P3-8)                                                   |                                                                                  |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 13 | "As in Clojure" is wrong for syntactic harvest; "helpers are other macros"     | §3.1 policy stated on its own terms; §3.3 helpers are prelude only, body         |
|    | unsupported; macro/runtime namespace relation unstated (astra P3-11)           | expansion reserved; §2.2 two namespaces                                          |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 14 | `program-out` `m = ev` would be a cross-medium ref; event attrs dropped from   | Decision 9 and §4.1: `program-out` carries `default-op`; `macro/event-schema`    |
|    | schema but still emitted (glm P3-5)                                            |                                                                                  |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 15 | Root fact unconditional vs "compile output unchanged" (glm P3-6)               | Phase 0 test reworded "modulo the root fact"                                     |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 16 | Phase 2 `:semantic` test has an unstated prerequisite (glm P3-7)               | Phase 2 defers it to the semantic spec's Phases 1–2, with the continuation-depth |
|    |                                                                                | measurement                                                                      |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 17 | Missed-macro diagnostics (glm P3-9)                                            | §6.1 `repl-state` lists the store's names                                        |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| —  | Qualify "never holds VM control state" (astra, layering)                       | §1.2 table: the *program* evaluator's state                                      |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| —  | Later batch ≠ expansion inside a loaded continuation (astra P3-11)             | Appendix A #6 already states the limit; decision 11 makes the consumed-batch     |
|    |                                                                                | semantics explicit                                                               |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+

Round 3 (`collab/1789279450960-review-macro-stream-process-r3.{gpt-6-astra,glm-5.3}.findings.md`), on revision 3:

+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| #  | Finding (reviewer)                                                             | Resolution                                                                       |
+====+================================================================================+==================================================================================+
| 18 | Re-check happens after operands expanded; a newly discovered binder macro gets | §3.2 application branch: operator first, re-check, then operands; test with      |
|    | rewritten operands (astra P1-1)                                                | `((choose) f [x] (x 1))`                                                         |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 19 | `(defn m …)` after `(defmacro m …)` not caught — the `yin/def` shape appears   | §3.1 step 2 `dissoc`es on any non-macro value; new step 5 post-harvest over the  |
|    | only post-expansion; non-lambda redefinitions ignored (astra P2-2)             | final tree, effective next batch; ordering rule stated                           |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 20 | Inline generated macro lambda both required by a test and forbidden by the     | Prohibition kept and widened to `:generated-macro`; the test case removed; both  |
|    | validator (astra P2-3)                                                         | reserved together                                                                |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 21 | `:errors` never drained; a stale error suppresses later evaluation;            | §5 `drain-errors` read-and-reset with `:forwarded`; §6.1 drives the evaluator on |
|    | A-ok/B-error in one call skips A (astra P2-4, glm P2-1)                        | `:forwarded > 0` independently of `:errors`                                      |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 22 | Admission checks only root-reachable, harvest decodes the whole batch (astra   | §3.1 step 1 checks every entity in the index                                     |
|    | P2-5)                                                                          |                                                                                  |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 23 | Admission failures have no event path (astra P2-6)                             | §4.1 admission-failure event allocated from the watermark, no input values in    |
|    |                                                                                | diagnostics                                                                      |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 24 | `run-on-stream` error shape: a value-shaped error lets un-updated callers      | §5 prerequisite: keep the throw, carry `:session` in `ex-data`, cursor position  |
|    | continue silently (glm P3-2); load vs flush failure must carry different       | depends on where the failure was; Phase 0 tests                                  |
|    | cursors (astra)                                                                |                                                                                  |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 25 | Admission should check the closed node vocabulary (glm P3-3)                   | §3.1 step 1 `:unknown-type`                                                      |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 26 | `closed`/`invalid-value`/`transport-error` are protocol outcomes, not defects; | §5 table reworded; outcome and both slots preserved in the throw                 |
|    | `full` may be permanent (astra)                                                |                                                                                  |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 27 | Decision 5 "unbound variable" too strong given §2.2; decisions 10/11 out of    | Decision 5 qualified; order fixed; §3.4 sentence added                           |
|    | order; §3.4 exactness under the no-inline contract (astra, glm)                |                                                                                  |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+

Round 4 (`collab/1789279972076-review-macro-stream-process-r4.{gpt-6-astra,glm-5.3}.findings.md`), on revision 4; glm approved r4:

+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| #  | Finding (reviewer)                                                             | Resolution                                                                       |
+====+================================================================================+==================================================================================+
| 28 | Post-harvest removes a later winning source macro because an earlier plain     | §3.1 step 5 is a unified last-wins over source definitions at their positions    |
|    | source `yin/def` survives in the tree (astra P2)                               | and *generated* definitions (identified by `m` = event, never eid) at their call |
|    |                                                                                | sites' positions; consequences enumerated; four acceptance cases plus            |
|    |                                                                                | re-parented variants in Phase 1                                                  |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 29 | Stray source macro lambda outside a definition shape has no disposition (glm   | §3.1 step 1 `:stray-macro-lambda` at admission; step 8 named `:scan-failed` as a |
|    | P3-1)                                                                          | forwarder defect                                                                 |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 30 | Appendix C row 5 names the retired `:generated-definition` (glm P3-2)          | Row 5 annotated                                                                  |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+

Round 5 (`collab/1789280260256-review-macro-stream-process-r5.gpt-6-astra.findings.md`), on revision 5:

+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| #  | Finding (reviewer)                                                             | Resolution                                                                       |
+====+================================================================================+==================================================================================+
| 31 | `m` is emission provenance, not definition origin: a re-parented generated     | §3.1 step 5 ranks every `yin/def` occurrence in the *final tree* in traversal    |
|    | definition carries `default-op`, and an operator rewrite forms a `yin/def`     | order, with step 3's recorded stand-in literals (`:replaced`) representing       |
|    | under `default-op`; both escape post-harvest (astra P2)                        | source macro definitions; no origin tracking, no use of eid or `m`;              |
|    |                                                                                | discarded-definition consequence stated; both examples in Phase 1                |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 32 | `macro-of`'s inline macro-lambda arm is unreachable after admission (astra P3) | §2.3 drops the inline case as supported input; §3.2 marks the arm defensive      |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+

Round 6 (`collab/1789280492428-review-macro-stream-process-r6.gpt-6-astra.findings.md`), on revision 6:

+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| #  | Finding (reviewer)                                                             | Resolution                                                                       |
+====+================================================================================+==================================================================================+
| 33 | Operator-first traversal visits `((fn [_] B) A)` as B then A, inverting        | §3.1 step 5 defines **declaration order** separately from expansion traversal:   |
|    | `do`/`let` declaration precedence (astra P2-1)                                 | immediately-applied lambdas rank operands before body; §3.2 notes its order is   |
|    |                                                                                | for allocation only; Phase 1 tests run through real lowering with nested         |
|    |                                                                                | sequences                                                                        |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 34 | Stand-in literal loses its recorded eid when a macro decodes and re-encodes it | Step 3 stand-in is an internal node type `:yin/macro-defined` recognised by      |
|    | (astra P2-2)                                                                   | shape; `:replaced` removed; validator accepts it on both sides; step 7 lowers it |
|    |                                                                                | to a literal for `program-out`; fabricated stand-ins are inert;                  |
|    |                                                                                | identity/wrapper/discard/plain-literal cases in Phase 1                          |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+

Round 7 (`collab/1789280741657-review-macro-stream-process-r7.gpt-6-astra.findings.md`), on revision 7:

+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| #  | Finding (reviewer)                                                             | Resolution                                                                       |
+====+================================================================================+==================================================================================+
| 35 | Name-only stand-ins cannot distinguish two same-name source definitions; a     | Step 2 numbers definitions into a batch-local catalogue; stand-in carries `:decl |
|    | macro keeping the first reinstalls the second (astra P2)                       | k`; step 5 resolves `k` and checks the name;                                     |
|    |                                                                                | keep-first/keep-second/swap/duplicate cases in Phase 1                           |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 36 | "Fabrication gains nothing" too broad — a valid fabricated stand-in moves a    | Step 3 states it: may move, never introduce a body; Phase 1 test                 |
|    | declaration's precedence (astra)                                               |                                                                                  |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 37 | Declaration order is not a universal source-order guarantee (PHP `for`'s       | Step 5 narrows the claim to `do`/`let` and the Python/PHP suite shape, and names |
|    | fixpoint operator) (astra)                                                     | the exception                                                                    |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+

Round 8 (`collab/1789281040289-review-macro-stream-process-r8.gpt-6-astra.findings.md`), on revision 8 — **APPROVE** (gpt-6-astra); glm-5.3 approved at round 4 (`collab/1789279972076-review-macro-stream-process-r4.glm-5.3.findings.md`):

+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| #  | Finding (reviewer)                                                             | Resolution                                                                       |
+====+================================================================================+==================================================================================+
| 38 | Clarify catalogue lifetime vs marker lifetime; do not claim marker data cannot | Step 3 states: stand-ins resolve only against the current batch's catalogue and  |
|    | survive an expansion (astra P3)                                                | carry no cross-batch identity; replayed markers select the current entry or      |
|    |                                                                                | nothing                                                                          |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+

glm-5.3 confirmation of r8 (`collab/1789281332481-review-macro-stream-process-r8.glm-5.3.findings.md`) — **APPROVE**:

+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| #  | Finding (reviewer)                                                             | Resolution                                                                       |
+====+================================================================================+==================================================================================+
| 39 | Marker shape nested in a `:literal :value` passes the plain-data rule and      | §3.3 validator row: `:marker-in-payload` rejected in any payload                 |
|    | reaches `program-out` verbatim (glm P3-1)                                      |                                                                                  |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 40 | Harvest ordinals and admission's first-error selection must iterate the datom  | §3.1 working-representation paragraph pins both                                  |
|    | vector, not the index map (glm P3-2)                                           |                                                                                  |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
| 41 | Validator row lacks transient `:tail?`; invoke-boundary decode/encode are the  | Row and paragraph updated                                                        |
|    | expander's own, codec is the backstop (glm P3-3)                               |                                                                                  |
+----+--------------------------------------------------------------------------------+----------------------------------------------------------------------------------+
