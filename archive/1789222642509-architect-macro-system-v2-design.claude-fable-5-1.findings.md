Completed-GMT: 2026-09-12 14:18:45 GMT
Completed-Local: 2026-09-12 21:18:45 +07
Coding-Agent: claude
Session-ID: fc5dc6a9-b701-4ba8-b862-8dad76ada2e3

# Unified compile-time and runtime macros on yin.vm — architectural specification

Role: Lead System Architect. Scope: design only; no repository file other
than this one was changed.

Companion to `collab/1789221648668-architect-semantic-vm-v2-design.claude-fable-5-1.findings.md`
(the semantic VM), which this document cites as "the semantic spec". Where
the two disagree, the later document (this one) states the reconciliation
explicitly in Appendix A.

## 0. Summary of decisions

1. **One expander, three phases of use.** `yin.vm.macro` is a single
   pure engine over canonical `:yin/*` datoms. It runs ahead of time as a
   batch-to-batch pass (compile time), and it is called at a boundary by each
   evaluator (runtime). There is no second implementation of expansion
   anywhere; evaluators own only the *splice* of an expansion back into their
   own control representation.
2. **A macro body is executed by the reference evaluator on AST, always.** A
   macro lambda is a Universal AST lambda. Its body runs on a throwaway
   `yin.vm.ast-walker` instance, whether the surrounding program is being
   expanded ahead of time, or is running on the walker, the semantic VM, or a
   future stack VM. Macro bodies are small, rare, and their semantics *are*
   AST semantics; lowering them buys nothing and would put a second evaluator
   on the path of every expansion. The evaluator used for macro bodies is
   supplied by the composition (`:macro-eval`), never resolved from a global.
3. **Macro arguments are AST values, not entity ids.** A macro receives its
   operands as Universal AST maps bound to its parameters (variadic `&`
   supported), and returns a Universal AST map. v1 passed entity ids and a
   `get-attr` closure; that coupled macro bodies to a datom index and made
   them unwritable from a non-Clojure frontend. Maps are what every frontend
   already produces and what every evaluator already consumes. Unchanged
   operand subtrees are still *shared*, not copied, because reconstructed
   operand maps carry their `:eid` and the emitter references an existing
   entity instead of re-emitting it (§3.5).
4. **Expansion is outermost-first and re-expands its own output to a
   fixpoint.** A macro sees its operands unexpanded (so a macro can rewrite
   forms that themselves contain macro calls), and the expander then walks the
   returned AST and expands whatever it contains. Fixpoint is reached when the
   output tree contains no `:yin/macro-expand` node whose macro is enabled for
   the current phase. Depth (nesting of expansion inside expansion output) is
   guarded at 100 and emitted datoms per expansion transaction at 10,000.
5. **Compile-time output is 100% macro-free for the current phase, and the
   root is an explicit fact.** The expanded batch carries
   `[root :yin/root true]`, and `yin.vm/index-datoms` honours it. Without
   it the existing "last unreferenced typed entity" heuristic would select the
   *unexpanded* original root, because expansion is non-destructive and the
   original root stays in the batch. Every consumer (walker loader,
   linearizer, future stack compiler) reads the same fact.
6. **Runtime expansion is a transition that ends by splicing, and its record
   is data on the VM value.** In the walker the spliced thing is an AST node
   (`cesk-return state expanded-ast env k nil`). In the semantic VM it is an
   ephemeral segment lowered on the spot and *called* with a return frame. In
   both, the expansion event and its output datoms are appended to a
   `:macro-expansions` ledger on the VM value, which the composition drains to
   any stream it chooses, exactly as the REPL drains output. Nothing about an
   expansion is hidden in a host object.
7. **Authority is supplied, not assumed.** Runtime expansion consults a
   composition-supplied `:macro-authorize` function with the phase, macro
   entity, program root and the presented capability. Absent authorizer means
   *deny*: a runtime macro on a VM built without one is a hard error, never a
   silent expansion. Compile-time expansion is allowed by default because the
   build context is trusted; a composition may still restrict it.
8. **A macro closure applied as a function is an error.** `:lambda` nodes
   carrying `:yin/macro?` produce closure values carrying `:macro?`, and
   `apply-function` refuses them. A missed expansion therefore fails loudly
   rather than evaluating operands that were meant to be syntax.

The rest of this document states the contracts these decisions imply.

---

## §1. Foundational axioms and invariant governance

### 1.1 The three axioms the brief names, plus the fourth

**Interpretation creates semantics.** A `:yin/macro-expand` datom is syntax.
`yin.vm.macro/expand` is the interpreter that gives it the meaning "replace
me with what this lambda returns when handed my operands as data". The same
datom read by a query means "a call site of macro *m*"; read by a renderer it
means the original source form; read by `linearize` under a `:runtime` policy
it means "emit a `:macro-call` instruction". The macro lambda itself is an
ordinary `:lambda` entity whose only difference from a function is two facts
(`:yin/macro?`, `:yin/phase-policy`), and it is the *expander's* reading of
those facts — not anything in the walker's closure application — that makes
it a macro. The expander runs the body with the walker and interprets the
returned value as an AST; the walker does not know it is expanding anything.

**Code and state are datoms.** Macro definitions are `:lambda` entities.
Call sites are `:yin/macro-expand` entities with `:yin/operator` and
`:yin/operands` refs. Every expansion produces a `:macro-expand-event`
entity and output entities with `m` = the event eid, appended to the same
bounded batch (compile time) or to the VM's expansion ledger (runtime). The
original call datoms are never retracted or rewritten; the expanded program
is a *new* root that shares every unchanged subtree by reference. A query can
therefore answer "what did this call expand to", "which macro produced this
node", and "what did the program look like before expansion" from the facts,
without the expander.

**Everything is a stream.** Compile-time expansion is an interpreter in the
sense of `dao.stream.md` §Composition: it reads one batch and produces one
batch, and lives entirely outside the stream contract. It is composed either
into a loader (`(comp vm-load-program macro/expand-batch)`) or as a forwarder
step between a program-in and a program-out medium (§3.7). It is not
per-datom and not per-node: a batch is the unit, as `streams-all-the-way-down.md`
§4 requires. Runtime expansion is a boundary: it ends the current transition
with a record appended to the ledger and control transferred to the splice.
The ledger is drained by the composition as a batch; no callback, no host
object, no stream handle inside the transition (§4.4 says why the transition
does not append to a stream itself).

**Everything is a continuation.** A runtime expansion in the walker is
`cesk-return` with the expanded AST as control and the *same* `env` and `k`;
a parked continuation captured after an expansion names AST that is fully
present in the VM value. In the semantic VM an expansion is a segment call: the
frame pushed is `{:segment :pc :env :stack-base}` like any other, and the
ephemeral segment is stored under `:code` by id, so a shipped continuation
resolves it the way it resolves any segment (semantic spec §1.1). Nothing
about a macro escapes into a host closure.

### 1.2 The six invariants

| Invariant | How the design honours it |
|---|---|
| No hidden global state | No registry, no `defonce`. The macro environment is a value threaded through `yang.*/compile-program` and `macro/expand`. The bootstrap macros (`defmacro`) are a value in `yin.vm.macro` passed in `opts`; the evaluator for macro bodies is `:macro-eval`, supplied at construction; the authorizer is `:macro-authorize`, supplied at construction. Fresh entity ids and gensym symbols come from counters threaded in the expansion context, seeded from the batch, not from a host counter. |
| No implicit control flow | Expansion happens at exactly two named places: the `expand-batch` pass and the `:yin/macro-expand` transition (walker) or `:macro-call` instruction (semantic). Nothing expands lazily on lookup, on load, or on `apply`. A macro-flagged closure reaching `apply-function` is an error, not an implicit expansion. |
| No callbacks | The expander returns data. Runtime expansion records data on the VM. The composition drains the ledger with an ordinary step it owns. The macro body evaluator is a function *supplied to* the VM, called synchronously as a subroutine on a throwaway VM value; it cannot call back into the executing VM because it is never given it. |
| No shared mutable state | The expansion context `{:datoms :by-entity :next-eid :next-sym :depth :emitted}` is a persistent map threaded through `loop`/`reduce`. The one local mutable cell — the gensym counter inside a single macro-body invocation (§2.4) — is created per invocation, closed over only by that invocation's `yin/gensym-sym` primitive, and unreachable after it returns. The throwaway walker used for a macro body is a fresh value with a fresh store. |
| No layer collapsing | Four functions with data between each: *compile* (yang: source → AST), *expand* (`macro`: datoms → datoms), *lower/load* (`linearize`, `vm-load-program`), *execute*. Runtime expansion re-enters *expand* as a subroutine from *execute* and re-enters *lower* for the semantic VM, but each remains a separate function over data; the executing VM never interprets the macro body and the expander never touches VM control state. |
| No assumed graphs | The expander constructs its index from the batch with `index-datoms` in an explicit pass; the root is the explicit `:yin/root` fact; a `:yin/operator` that resolves to no macro is an error naming the call eid; a `:macro-call` instruction whose macro cannot be resolved at run time is an error naming the pc and the source eid. |

---

## §2. Macro representation and language integration

### 2.1 The macro entity

Unchanged from `docs/design/macro-design.md` and already round-tripped by
`yin.vm/ast->datoms` and `datoms->ast`:

| Attribute | Value | Note |
|---|---|---|
| `:yin/type` | `:lambda` | a macro is structurally a lambda |
| `:yin/params` | vector of symbols, `&` permitted before the last | variadic binding is the expander's, §2.4 |
| `:yin/body` | ref | |
| `:yin/macro?` | `true` | required on a macro; absent on a function |
| `:yin/phase-policy` | `:compile` \| `:runtime` \| `:both` | required when `:yin/macro?` is true; `ast->datoms` defaults it to `:compile` |

AST map form: `{:type :lambda :params [...] :body ... :macro? true
:phase-policy :compile}`, with an optional `:eid` when the frontend
pre-allocates an id (yang.clojure does, so the `yin/def` operand and every
call-site operator that embeds the same map become one entity).

### 2.2 The call node

```
{:type :yin/macro-expand
 :operator <macro-ast>        ; a :lambda map (macro? true) or a :variable map
 :operands [<ast> ...]        ; unevaluated Universal AST nodes
 :tail? true|false}           ; as yang marks it
```

Datoms: `:yin/type :yin/macro-expand`, `:yin/operator ref`, `:yin/operands
[ref ...]`, optional `:yin/tail?`. The operator is either the macro lambda
entity itself (the eid *is* the macro) or a `:variable` naming it; §2.7
defines resolution for the second form.

### 2.3 Macro values at run time

Two additions so that runtime resolution of a `:variable` operator works
through the ordinary store, with no macro registry:

1. **The walker's `:lambda` transition copies the flags into the closure.**
   `{:type :closure :params :body :env}` becomes
   `{:type :closure :params :body :env :macro? true :phase-policy p :eid e}`
   when the node is a macro (three `assoc`s taken only on macro lambdas; the
   hot path for functions is untouched). `:eid` is present because the
   walker's loader keeps `:eid` on macro lambda nodes only (§3.5 on
   `datoms->ast` with `:keep-eids`), so runtime provenance can name the macro
   entity.
2. **`apply-function` rejects `:macro?` closures** with
   `ex-info "Macro applied as a function" {:macro eid :name sym}`. This is the
   guard behind decision 8.

`(yin/def name <macro-lambda>)` therefore puts a macro closure in the store
under `name`, and a later `:yin/macro-expand` whose operator is
`{:variable name}` resolves it with `engine/resolve-var` exactly like any
variable. The semantic VM's `:closure` instruction carries the same three
facts as operand attributes (`:yin.code/macro?`, `:yin.code/phase-policy`,
`:yin.code/macro-ast`) — §4.2 explains why the last one is inlined.

### 2.4 The argument protocol and the macro prelude

**Binding.** `(bind-params params operand-asts)` produces the env for the
macro body: positional params bind one AST map each; `& rest` binds a vector
of the remaining AST maps; arity mismatch is a hard expansion error naming
the call eid (`{:kind :arity :expected n :got m}`). This binding is the
expander's, so the walker's `zipmap` closure application (no `&`) is not
involved and does not change.

**Return.** The body's value must be a Universal AST map. `macro/valid-ast?`
walks it and requires: every node a map with a keyword `:type` in the
canonical set (`:literal :variable :lambda :application :if
:dao.stream.apply/call :vm/* :stream/* :yin/macro-expand`); ref-valued keys
(`:body :operator :test :consequent :alternate :target :val :source`) hold
maps; `:operands` a vector of maps; `:params` a vector of symbols. Any
other value is a hard error (`{:kind :invalid-output}`) — no coercion of a
bare number or symbol into a literal, because "no silent fallback" is the
rule and the prelude makes constructing the right node one call.

**The prelude** is the set of primitives merged into the throwaway VM's
`:primitives` for the duration of one macro-body invocation. They are pure
constructors and accessors over AST maps:

| Primitive | Meaning |
|---|---|
| `yin/node-type ast` | `(:type ast)` |
| `yin/node-get ast k` | `(get ast k)`; the only accessor a macro needs |
| `yin/literal v`, `yin/variable sym` | leaf constructors |
| `yin/lambda params body`, `yin/application op operands`, `yin/if t c a` | node constructors; `params` is a vector of symbols |
| `yin/sequence-body [ast ...]` | the `do` desugaring yang uses (nested `(fn [_] ...)` applications); empty → `nil` literal |
| `yin/make-lambda params-ast body-ast` | compatibility with the v1 stdlib: `params-ast` is a `:literal` whose value is the param vector; marks tail positions in `body-ast` exactly as v1's `mark-tail!` did (application and macro-expand nodes in tail position of the body, recursing through `:if` branches and immediately-applied lambdas) so tail calls in macro-generated bodies still get the walker's TCO |
| `yin/make-def name-ast value-ast` | `(yin/def <sym> value)` application; `name-ast` may be `:variable`, a `:literal` symbol, or a `:literal` string (§2.6 on why all three) |
| `yin/name-of ast` | the symbol named by any of those three shapes; error otherwise |
| `yin/macro-call op-ast operands` | constructs a `:yin/macro-expand` node — how a macro emits a call to another (or the same) macro |
| `yin/gensym-sym prefix` | a fresh symbol `prefix__<E>_<n>` where `E` is the absolute value of the expansion-event eid and `n` an invocation-local counter; unique across expansions without cross-invocation state |

Plus the ordinary `yin.vm/primitives` (arithmetic, `first`, `rest`,
`conj`, `assoc`, `get`, `vec`, `=`, `nil?`, `empty?`) so a macro can walk and
build vectors. `yin/def` inside a macro body writes to the throwaway store
and is discarded; `require` resolves against the throwaway VM's `:modules`,
which is `nil` — a macro body that needs a module is an error, by design.

The throwaway VM is built with `:make-stream` absent and no FFI pair: a
macro body that reaches a stream or FFI effect fails with the existing
"constructed without :make-stream" / "no call pair" errors. A macro body that
parks (`:vm/park`) or blocks is an expansion error `{:kind :suspended}`.
Macros are total, synchronous transformations of syntax; anything else is a
function.

**Hygiene** is v1's: non-hygienic by default, `yin/gensym-sym` on request.
Full hygiene stays out of scope.

### 2.5 `yang.clojure`

Already emits the representation above; three changes:

1. `compile-program` gains a third arity taking an initial `macro-env`
   (default `initial-macro-env`), so a caller that has macros from earlier
   units (the REPL across evaluations, a build across files) can seed it.
   `compile-program` also *returns* the final macro-env when asked
   (`compile-program* forms tail? macro-env → {:ast :macro-env}`), so the
   caller can persist it. The existing arities are unchanged.
2. Non-top-level `(defmacro ...)` goes through the bootstrap `defmacro`
   macro (`:yin/macro-expand` with operator `{:variable defmacro}` and
   operands `[name params body*]`), and the expander's bootstrap table
   expands it to `(yin/def name <lambda macro? true>)` — v1's `defmacro-fn`
   over AST maps instead of eids. Top-level `defmacro` stays lowered directly
   as today. A `defmacro` whose name symbol carries `^{:phase-policy
   :runtime}` (or `:both`) metadata sets the lambda's policy; the default
   stays `:compile`.
3. `stdlib-forms` (the `defn` macro) moves from the deleted `yin.vm.macro`
   to `yin.vm.macro/stdlib-forms`, unchanged in text; yang's docstring
   already points there by name.

### 2.6 Non-homoiconic frontends (`yang.python`, `yang.php`)

`compile` gains an arity `(compile source macro-env)` where `macro-env` is a
map `symbol → {:type :user :eid e :lambda-ast ast}` (the same shape yang.clojure
threads). In `compile-stmt`, a `:call` whose function is a `:variable` naming
a key of `macro-env` emits

```
{:type :yin/macro-expand
 :operator {:type :variable :name sym}
 :operands (mapv #(compile-stmt % false) args)}
```

instead of `:application`. Names shadowed by an enclosing `lambda`/`def`
parameter are removed from the env for that scope, as yang.clojure does.

**Normalization is what the frontend already does.** `compile-stmt` on each
argument produces a canonical node: a Python identifier is a `:variable`, a
string is a `:literal` string, a number a `:literal`, a lambda a `:lambda`.
`docs/cross-language-macro.md` §3 proposed that the frontend additionally
lower `"my_func"` to a `:variable` node for `defn`'s benefit. This design
rejects that: a frontend must not know a macro's signature (that is a hidden
coupling from syntax to a particular macro), and it cannot know it for a
macro defined in another language. Instead the prelude's `yin/make-def` and
`yin/name-of` accept a `:variable`, a `:literal` symbol, or a `:literal`
string. A Python `defn("my_func", ...)` and a Clojure `(defn my-func ...)`
therefore reach the same macro with different canonical shapes, and the macro
is written to accept both — which is a one-line accessor, not a frontend
rule. The contract for macro authors: *arguments arrive as whatever canonical
node the caller's frontend produces for that source form; use the prelude
accessors, not `:type` pattern matching, for names.*

Python and PHP remain *consumers* of macros in this design. Authoring from
those languages (`docs/cross-language-macro.md` §2, the direct AST API) is
the prelude itself — a Python program can call `yin.make_lambda(...)` once a
Python `defmacro` surface exists — and is reserved; nothing here prevents it.

### 2.7 Discovery: local and cross-unit

A frontend needs to know which names are macros *before* emitting. Two
sources, one shape:

- **Same-unit (local).** yang.clojure's threaded `macro-env` today; the
  Python/PHP frontends have no macro-defining form, so their local env is
  always what they were given.
- **Cross-unit.** The composition supplies `macro-env`. Where it comes from
  is the composition's business: the REPL builds it by scanning each
  evaluated batch (`macro/definitions datoms → {sym {:eid :lambda-ast
  :phase-policy}}`, which finds `(yin/def sym <lambda macro? true>)`
  applications, last-write-wins in batch order, exactly v1's
  `find-macro-lambda-by-name` inverted into a map); a build tool builds it
  from previously committed streams; DaoDB, when it can answer
  `[?e :yin/macro? true]` joined to its `yin/def`, is a third producer of the
  same map. No frontend queries anything itself.

The expander resolves a `:variable` operator by the same env plus the batch
scan, in this order: batch definitions (same-unit wins) → supplied
`:macro-env` → bootstrap table (`defmacro`) → error `{:kind :unresolved
:name sym :call eid}`. At runtime (§4) the store replaces the first two.

---

## §3. The compile-time expander (`yin.vm.macro`)

### 3.1 Contract

```
expand-batch : datoms opts → {:datoms datoms' :root-eid r :events [eid ...] :deferred [eid ...]}
expand       : datoms opts → datoms'                    ; = (:datoms (expand-batch ...))
definitions  : datoms → {sym {:eid :lambda-ast :phase-policy}}
invoke       : macro-ast operand-asts ctx → ast          ; one macro body, §3.2
valid-ast?   : value → nil | {:kind :invalid-output :at path}
stdlib-forms : the defn macro source
bootstrap    : {'defmacro (fn [operand-asts ctx] → ast)}
```

`opts`: `:macro-eval` (required — the composition supplies it; see §3.2),
`:macro-env` (default `{}`), `:bootstrap` (default `bootstrap`), `:phase`
(default `:compile`), `:max-depth` 100, `:max-datoms` 10000, `:t` (the
transaction slot written on emitted datoms, default 0), `:authorize`
(default `(constantly true)` at compile time — §5.3).

`datoms'` is `datoms` followed by the appended event and output datoms; the
input vector is a prefix of the output, byte for byte. Pure: same inputs,
same output, on every host.

### 3.2 Executing the macro lambda

`invoke` builds `env = (bind-params params operand-asts)` and calls

```
(:macro-eval opts) lambda-ast env prelude → value
```

where the composition's `:macro-eval` is, for every current composition,
`yin.vm.ast-walker/macro-eval`:

```
(defn macro-eval [lambda-ast env prelude]
  (let [vm (create-vm {:env env
                       :primitives (merge vm/primitives prelude)})
        result (vm/eval vm (:body lambda-ast))]
    (cond (vm/blocked? result) (throw (ex-info "Macro body suspended" {...}))
          (not (vm/halted? result)) (throw ...)
          :else (vm/value result))))
```

`yin.vm.macro` does **not** require `yin.vm.ast-walker`; the walker
requires `macro` (for its runtime branch) and exports `macro-eval` for
compositions to hand back in. This breaks the require cycle and keeps the
choice of body evaluator where every other evaluator choice lives — the
composition (`yin.repl.core/make-vm`, test utilities, build tools). A
composition may supply any function of the same signature; the semantic VM
is *not* an acceptable default for it (decision 2), but a host that ports
only a walker can still expand macros.

The throwaway VM is constructed per invocation. Construction cost (a record
and a map) is negligible next to running a body, and per-invocation
construction is what makes the "no shared mutable state" row of §1.2 true
without argument.

### 3.3 The algorithm

Post-order over the *tree reachable from the root*, with outermost-first
expansion at each macro node and immediate re-expansion of the result:

```
expand-node ctx eid :
  node-type = type(eid)
  if node-type = :yin/macro-expand and enabled?(ctx, macro(eid)):
      guard depth
      operands  = [reconstruct ctx o | o ∈ operands(eid)]      ; AST maps with :eid kept
      macro     = resolve ctx operator(eid)                    ; §2.7
      event-eid = fresh ctx
      out-ast   = invoke macro operands ctx'                   ; ctx' carries event-eid for gensym
      check valid-ast? out-ast
      [root', ctx''] = emit ctx' out-ast m=event-eid           ; §3.5
      ctx''' = emit-event ctx'' event-eid eid macro root'
      guard datoms
      return expand-node (ctx''' with depth+1) root'           ; re-expand the output
  else:
      for each ref attribute a of eid, child c:
          [c', ctx] = expand-node ctx c
      if any c' ≠ c: copy eid to fresh eid' with substituted refs, m = default-op
      return [eid or eid', ctx]
```

Properties, each of which is a test in Phase 1:

- **Fixpoint by construction.** Every node reachable from the final root has
  been visited after its last rewrite; a `:yin/macro-expand` remains only if
  `enabled?` was false for it (a `:runtime`-policy macro at compile time),
  and each such node is listed in `:deferred`. A final scan of the output
  tree asserts this and is cheap (linear in the output).
- **Outermost first.** `operands` are reconstructed *unexpanded*. A macro
  such as `->` or `when-let` that rewrites forms containing other macro calls
  sees them as written. This differs from v1's innermost-first `transform`
  (Appendix A.2).
- **Depth** counts nested re-expansion: a macro whose output contains a macro
  call whose output contains a macro call ... is depth 3. Direct recursion
  (`(defmacro loop* ... (yin/macro-call loop* ...))`) without a base case
  hits the guard at 100 with `{:kind :depth-guard :depth 100 :chain [eid ...]}`
  — the chain of call eids is in the error, so the offending macro is
  nameable from the error alone. Sibling expansions do not accumulate depth.
- **Sharing.** An operand that a macro returns unchanged (the same map, still
  carrying its `:eid`) is referenced, not copied (§3.5). The typical `defn`
  expansion therefore emits the new `yin/def` application and lambda nodes
  and *refs* the body the user wrote; only the ancestor chain from the call
  site up to the root is copied, as in v1.
- **Phase.** `enabled?` is: `:compile` or `:both` when `(:phase opts)` is
  `:compile`; `:runtime` or `:both` when it is `:runtime` (the runtime callers
  in §4 use the same function with `:phase :runtime` and a single node). A
  `:compile` macro encountered with `:phase :runtime` is a hard error
  `{:kind :phase-violation}`: it means the compile pass was skipped, and
  silently expanding it at runtime would hide that.

### 3.4 Guards and failure

| Guard | Default | Error data |
|---|---|---|
| depth | 100 | `{:kind :depth-guard :depth d :chain [call-eid ...]}` |
| emitted datoms per `expand-batch` call | 10,000 | `{:kind :datom-guard :emitted n :call call-eid}` |
| arity | — | `{:kind :arity :macro eid :expected :got}` |
| output shape | — | `{:kind :invalid-output :macro eid :at [path]}` |
| unresolved macro | — | `{:kind :unresolved :name sym :call eid}` |
| phase | — | `{:kind :phase-violation :macro eid :policy p :phase ph}` |
| suspended body | — | `{:kind :suspended :macro eid}` |
| authority | — | `{:kind :unauthorized :macro eid :phase ph}` |

Every failure is thrown as `ex-info` whose data is the map above under
`:yin/error`, **and** — when an event eid was already minted — the expander
first appends the event entity with `:yin/error <that map>` and no
`:yin/expansion-root`, so a failed expansion is queryable (macro-design.md
lists `:yin/error` as an event attribute for this reason). `expand-batch`
itself does not return partial output: the throw carries `:datoms-so-far`
for diagnosis only.

### 3.5 Output, the root fact, and eid discipline

**Emission.** `emit` is `vm/ast->datoms-with-root` with three options that
Phase 0 adds to `yin.vm`:

- `:m event-eid` — already supported; every output datom carries it.
- `:id-start` — already supported; the context's next fresh eid, below the
  batch minimum (v1's `make-eid-counter!` rule: strictly below every eid in
  the input so tempids never collide).
- `:existing #{eid ...}` — **new**: a node carrying `:eid` in this set is
  returned as a reference and *not* re-emitted. This is the sharing rule.
  `datoms->ast` gains the inverse option `:keep-eids? true` and the expander
  reconstructs operands with it. `vm-load-program` uses `:keep-eids?` only
  for macro lambdas (so closures can carry `:eid`, §2.3) — ordinary nodes stay
  eid-free on the walker's hot path and in the `compile` command's output.

**The root fact.** After the top-level `expand-node` returns `root'`, the
expander appends `[root' :yin/root true t default-op]` (retracting nothing —
an older `:yin/root` on the original root, if a previous pass wrote one, is
superseded by "last `:yin/root` fact in batch order wins"). `index-datoms`
gains: if any `:yin/root` fact exists, the root is the entity of the last one;
otherwise the existing heuristic, restricted to entities whose `:yin/type` is
not `:macro-expand-event`. Both are one-line changes in `yin.vm` and the
second is required even for batches with no `:yin/root`, because an event
entity is typed and unreferenced and would otherwise win the heuristic.
`:yin/root` joins `schema` as a boolean.

**Eid discipline across batches.** Within one `expand-batch` call, all refs
are within one batch and valid as tempids. A macro resolved through
`:macro-env` from another unit has an eid that belongs to that unit's batch;
the event's `:yin/macro` ref is then meaningful only once both batches are
committed to the same DaoDB with tempids resolved — the pipeline
`docs/cross-language-macro.md` §4 describes. The expander records the fact
honestly and also writes `:yin/macro-name sym` on the event, so the link
is recoverable by name even when the ref is cross-batch. `:yin/macro-name`
is the only attribute this design adds to the event beyond macro-design.md.

### 3.6 Feeding the three evaluators

The output of `expand-batch` is a batch of `:yin/*` datoms with an explicit
root and no compile-enabled macro nodes. Each consumer takes it unchanged:

1. **`yin.vm.ast-walker`**: `vm-load-program` calls `datoms->ast`, which
   now honours `:yin/root`; the walker never sees a `:yin/macro-expand`
   node unless the batch carried a `:runtime`/`:both` macro that was
   deferred — and then it takes the §4.1 transition. Composition:
   `(comp ast-walker/vm-load-program (macro/expand-with opts))` handed to
   `observer/run-on-stream`, or `macro/expand` applied to `(vm/ast->datoms
   ast)` before `vm-load-program` in a direct evaluation path.
2. **`yin.vm.linearize` → `yin.vm.semantic`**: `lower` reads the same
   root fact; its existing rejection of `:yin/macro-expand` becomes the
   §4.2 rule (reject `:compile`-policy nodes with `:phase-violation`, emit
   `:macro-call` for the rest). Composition: `(comp semantic/vm-load-program
   linearize/lower (macro/expand-with opts))`.
3. **`yin.vm.stack`** (future): the bytecode compiler consumes the same
   batch through `datoms->ast` or the datom index; deferred nodes become
   `OP_MACRO_EXPAND` (§4.3).

"Zero runtime overhead" is literal: after the pass, no evaluator has a macro
node on its path, and the walker's inlined hot loop (`ast-walker-run-active-continuation`)
does not gain a case — the `:yin/macro-expand` branch lives in
`cesk-transition`, which the hot loop reaches only through its default arm.

### 3.7 As a stream interpreter

`expand-with opts` returns a unary `batch → batch'` for loader composition.
For a build pipeline that keeps expansion out of the loader, `macro/forward-step`
is one step of a forwarder: read one batch at a cursor on a program-in
handle, `expand-batch`, `append!` to a program-out handle, return the
successor cursor and the append outcome. It is exactly the forwarder shape
`dao.stream.md` §Composition describes ("a single step, not a callback
loop"); cadence is the driver's; a `full` on program-out retains the batch
and returns `:retry`. This is optional composition, not part of the
evaluators, and is listed so that "compile-time expansion as a
stream-transducer pass" has one concrete function to point at.

---

## §4. Runtime macro execution across evaluators

A runtime expansion is `expand-node` from §3.3 applied to a single call node
with `:phase :runtime`, where *resolve* consults the VM's store instead of the
batch, *invoke* is the same, *authorize* is the VM's `:macro-authorize`, and
*emit* targets the VM's ledger rather than the batch. The evaluator-specific
part is only how the result is spliced into control.

### 4.1 `yin.vm.ast-walker`

**Loader.** `vm-load-program` records `(:min-eid batch)` on the VM (one
`reduce` over the batch, done inside the existing `datoms->ast` index pass)
so runtime-minted eids continue strictly below the loaded program's.

**Transition.** A new arm in `cesk-transition`'s node `case`
(`ast_walker.cljc:390-500`), not in the inlined hot loop:

```
:yin/macro-expand
(let [{:keys [operator operands]} node
      macro   (resolve-macro state operator env)          ; (a)
      _       (check-phase macro :runtime)                 ; (b)
      _       (authorize state macro)                      ; (c)
      {:keys [ast events state']} (macro/expand-runtime state macro operands node) ; (d)
  (cesk-return state' ast env k nil))                      ; (e)
```

(a) `resolve-macro`: an operator node of type `:lambda` with `:macro?` is
built into a closure as the `:lambda` arm would (its `:eid` carried); an
operator of type `:variable` is `engine/resolve-var` on `env store primitives
modules`, and the result must be a closure with `:macro?` (a function or
primitive here is `{:kind :not-a-macro}`); anything else is `{:kind
:unresolved}`.

(b) `:phase-policy` must be `:runtime` or `:both`; `:compile` is
`{:kind :phase-violation}` — the compile pass was skipped (§3.3).

(c) §5.3.

(d) `macro/expand-runtime` runs §3.3's `expand-node` on the single node with
operands taken *as AST maps directly from the node* — they are already maps
in the walker, no reconstruction — and `:phase :runtime`, minting eids from
`(:macro-eid state)` (initialised to `(dec (:min-eid state))` at load) and
threading the counter back. It returns the expanded AST map, the datoms for
the event and the output (with `m` = event eid), and the state with
`:macro-expansions` extended by `{:event event-eid :datoms [...]}` and the
counter advanced. The output AST is macro-free for `:runtime`: nested
runtime macro calls in the output were expanded recursively under the same
depth guard; nested `:compile`-policy calls in the output are
`{:kind :phase-violation}` (a runtime macro may not manufacture compile-time
work).

(e) The expanded AST becomes control with the same `env` and `k`. The
operands were never evaluated: what the brief calls "evaluating unevaluated
AST operands" is, precisely, the identity — the operand nodes are bound as
data, per macro-design.md "Argument semantics". `val` is `nil` because the
expansion produced no value; the expansion's value is whatever the spliced
AST computes. Tail position needs no treatment: the spliced AST runs against
the caller's `k`, which is how the walker already gets TCO.

CESK, with $M$ the ledger and $\mu$ the eid counter (both fields of the state
record, not of $S$):

$$\langle \text{macro-expand}(op, args), E, S, K, M, \mu\rangle
\to \langle \mathcal{E}\llbracket m \rrbracket(args), E, S, K, M \Vert [\text{ev}], \mu' \rangle
\quad \text{where } m = \rho_{macro}(op, E, S)$$

and $\mathcal{E}$ is the expander; the transition is a hard error if
$\rho_{macro}$ fails, the phase or authority check fails, or $\mathcal{E}$
raises. Exactly one `ASTWalkerVM` allocation, as every other arm.

**Blocked and parked states** are unaffected: expansion is synchronous
inside one transition; the throwaway VM's blocking is an error (§2.4), never
a park of the outer VM.

**`vm-eval` without a compile pass.** `eval` converts, loads and runs; it does
*not* expand. A `:compile`-policy macro node therefore reaches (b) and fails
with `:phase-violation`. This is deliberate: `eval` is "direct evaluation of
supplied work", and a composition that wants compile-time macros on that path
composes `macro/expand` before it (the REPL does, §6.2).

### 4.2 `yin.vm.semantic` (linear code segments)

**Representation.** One new instruction and three operand attributes:

| `:yin.code/op` | Operand attributes | Effect |
|---|---|---|
| `:macro-call` | `:yin.code/macro-ref ref` **or** `:yin.code/name sym`; `:yin.code/call-ast <map>`; `:yin.code/tail? bool`; `:yin.code/source ref` | expand and call the ephemeral segment (below) |
| `:closure` (extended) | `:yin.code/macro? true`, `:yin.code/phase-policy kw`, `:yin.code/macro-ast <map>` — on macro lambdas only | `val ← closure with :macro? :phase-policy :macro-ast :eid` |

`:yin.code/call-ast` is the `:yin/macro-expand` node *as an AST map value*
(operator and operands inline), and `:yin.code/macro-ast` is the macro
lambda as a map. These are inlined as values rather than left as refs because
a segment travels alone as one batch and a continuation names its segment by
id (semantic spec §2.2, §7.3): the segment must be self-contained, and the
executor needs *syntax* at these two points because the instruction's meaning
is "interpret this syntax". They are also the only two places the code
vocabulary holds AST, and both are absent from any segment lowered from a
macro-free batch. Opcode: `:macro-call 24` in `opcode-table` and in
`opcase`'s literal map (the cljd trap, semantic spec Appendix B).

**Lowering rule** (`linearize`): a `:yin/macro-expand` node with policy
`:compile` (resolved through the batch and the supplied `:macro-env`) is a
lowering error `{:kind :phase-violation}`; with `:runtime`/`:both` it lowers
to one `:macro-call` whose `:yin.code/macro-ref` is the lambda entity when the
operator is an embedded lambda, or `:yin.code/name` when it is a variable.
Tail position is `:yin/tail?` as for `:call`. The lowered *segment* for a
program with deferred macros is otherwise ordinary.

**Loader.** Builds `:macro-sites {pc {:call-ast :macro-ref/:name :tail?}}`
beside the image; the decoded instruction holds only the pc. Macro-lambda
closure instructions decode their three extra fields.

**Dynamic lowering and splice.** On `:macro-call` at `(seg, pc)`:

1. Resolve: `:yin.code/macro-ref` → the closure value produced by that
   lambda's `:closure` instruction is not available by ref at run time (a
   closure is a value made when the instruction executes), so the loader
   resolves the ref to the *instruction* and the executor reads its
   `:macro-ast` directly — a macro named by entity is static. `:yin.code/name`
   → `engine/resolve-var`; must be a closure with `:macro?` (its
   `:macro-ast` is the lambda).
2. Check phase and authority as in §4.1 (b), (c).
3. `macro/expand-runtime` with the operands from `:call-ast`, `:phase
   :runtime`, eids below `(:min-eid vm)` (recorded by the loader as for the
   walker), producing `ast'`, the ledger entry, and the counter.
4. `linearize/lower-ast ast' {:terminator :return :derived-from root' :m
   event-eid}` → an **ephemeral segment** $\sigma$: a fresh segment entity
   whose main sequence ends in `:return` instead of `:halt`, whose
   instructions carry `:yin.code/source` refs into the expansion output and
   `m` = the event eid, and whose datoms are appended to the ledger entry
   (so the ledger holds both the AST-level and the code-level record of the
   expansion, and a query can render either view).
5. Load $\sigma$ into `:code` under its id (never evicted in Phase 3; if
   eviction is added it pins by frames as the semantic spec's Appendix B
   says).
6. Splice by *segment call*, which binds nothing:

$$\begin{aligned}
&\neg tail:&& \langle seg,pc,val,St,E,S,K\rangle \to \langle \sigma,\,0,\,val,\,St,\,E,\,S,\,K \Vert [\mathrm{ret}(seg,pc{+}1,E,|St|)]\rangle\\
&tail:&& \to \langle \sigma,\,0,\,val,\,St,\,E,\,S,\,K\rangle
\end{aligned}$$

The `:return` at the end of $\sigma$ pops the frame and continues at
`pc+1` with `val` = the expansion's result, exactly as a closure return
would; in tail position no frame is pushed, so a runtime macro in tail
position of a loop does not grow $K$ — the same rule as `tailcall`. The env
is the *caller's* $E$, unchanged, because the expanded code is the caller's
code; it is not a closure body. `St` is untouched: the linearizer emits the
`:macro-call` where the walker would have had the node, i.e. in value
position, and the expansion's value lands in `val` where the enclosing
sequence expects it.

The image of $\sigma$ is built once per expansion. A `:macro-call` in a loop
body re-expands on every iteration; a memo keyed by `[pc call-eid]` is
*reserved* (Appendix B), not default, because a runtime macro may read the
store and must be allowed to see it change.

### 4.3 `yin.vm.stack` (future bytecode VM)

Per the semantic spec §6.4 the loaded segment image *is* bytecode; a stack
VM differs in stored form only. The contract for it, so that its designer
inherits this document rather than re-deriving it:

- `OP_MACRO_EXPAND` = 24 (shared `opcode-table`), operand = an index into the
  code object's constant pool holding the `:yin/macro-expand` AST map and the
  resolution key (`{:macro-ref e}` or `{:name sym}`), plus a tail flag.
- Execution: resolve, check phase and authority, `macro/expand-runtime`,
  compile `ast'` to an ephemeral code object with a `RETURN` terminator,
  push a return frame unless tail, jump. Identical to §4.2 with "segment"
  read as "code object".
- Compile-time: none needed; the compiler consumes the expanded batch (§3.6).
- Parity: a corpus program with a runtime macro must produce the same value,
  effect order and ledger events on all three evaluators; the walker is the
  oracle.

### 4.4 Why the transition does not append to a stream

`streams-all-the-way-down.md` §1 puts a boundary "wherever you need
decoupling — and nowhere else". An expansion record has one consumer (the
composition that persists or displays it), needs no replay inside the VM,
and crosses no host boundary at the moment it is produced. Appending it from
inside the transition would put a stream handle in the executor's path and
force the transition to be total over `full` (park the *expansion*?) for a
record that is not the program's own effect. The ledger is a field on the VM
value — like `:parked`, `:wait-set`, and the reserved trace vocabulary of the
semantic spec §3.6 — and `macro/drain-expansions vm → [vm' datoms]` hands the
composition one batch to append wherever it likes. A composition that wants
the expansion log on a stream composes that in one step, as the REPL composes
output. This is the same decision, for the same reason, as the semantic spec
makes for traces.

---

## §5. Provenance, causality and capability security

### 5.1 Non-destructive expansion

Original call datoms are never rewritten. At compile time the input vector
is a prefix of the output; at runtime the program the loader received is
untouched and the ledger holds only appended facts. The *link* from an
original call to its expansion is forward-only: `event.:yin/source-call →
call`, never a fact on the call itself. The expanded program is a new root
that references unchanged subtrees; the ancestor chain from a call site to
the root is copied with fresh eids and `m = default-op` (they are not macro
output; they are re-parenting, and their provenance is structural).

### 5.2 The expansion event

```
[ev :yin/type           :macro-expand-event  t default-op]
[ev :yin/source-call    <call-eid>           t default-op]
[ev :yin/macro          <macro-lambda-eid>   t default-op]   ; omitted for bootstrap macros (no entity)
[ev :yin/macro-name     <sym>                t default-op]   ; always present
[ev :yin/phase          :compile | :runtime  t default-op]
[ev :yin/expansion-root <root'>              t default-op]   ; omitted on failure
[ev :yin/error          {:kind ...}          t default-op]   ; on failure only
[ev :yin/capability     <token-ref>          t default-op]   ; runtime, when a token was presented
[ev :yin/timestamp      <logical-t>          t default-op]   ; optional, §5.2.1
```

and every output datom (AST nodes, and for the semantic VM the ephemeral
segment's instructions) carries `m = ev`. The event's own datoms carry
`default-op` in `m`, as v1's did: an event is not the output of itself.

The query chain macro-design.md requires — source-call → event →
expansion-root — is three joins on refs. "Every datom this macro ever
produced" is `[?ev :yin/macro ?m] [?d _ _ _ ?ev]`; "what was here before
expansion" is the subtree under `:yin/source-call`.

**5.2.1 `:yin/timestamp`.** The brief lists it; macro-design.md does not.
It is included as an *optional logical* value: the `:t` the caller passed
to `expand-batch` (or the VM's step counter at runtime, once the trace
vocabulary of the semantic spec §3.6 exists). It is never a host clock.
Reading a clock inside the expander would make expansion non-deterministic
and the "compile/runtime parity for the same macro and input" test of
macro-design.md unrunnable. A composition that wants wall time writes it on
the batch it commits.

### 5.3 Authority

The check is a supplied function, so that the Shibi design (macaroon-style
attenuable tokens, `dao.space.security.md`, ADR-0002) can land without
reopening this file:

```
:macro-authorize : (fn [{:keys [phase macro-eid macro-name program-root capability]}]
                     → true | {:kind :unauthorized ...})
```

- **Compile time** (`expand-batch`): `:authorize` defaults to
  `(constantly true)` — the build context is trusted, per macro-design.md.
  A composition may pass a stricter one (a build that only permits macros
  from a namespace allowlist).
- **Runtime** (walker, semantic, stack): `:macro-authorize` is a `create-vm`
  option. **No default.** A VM constructed without one denies every runtime
  expansion with `{:kind :unauthorized :reason :no-authorizer}`. The
  `:capability` it is handed is the value under store key `:yin/capability`
  if the composition put one there (`create-vm` option `:capability`, stored
  like the FFI pair), else `nil`. `program-root` is the loaded root eid
  (`:program-root` recorded by the loader). The token, when present, is also
  written on the event as `:yin/capability`, so every runtime expansion's
  authority is auditable from the facts.
- **What a Shibi authorizer will check** (not implemented, as macro-design.md
  says): authority `macro/expand`, scope = the program root, phase
  `:runtime`, and an allowed macro entity or namespace — each a caveat on
  the token. The interface above carries exactly those four inputs and
  nothing else, so the caveat set is closed.

The REPL supplies `(constantly true)` for the local shell (a user typing at
their own shell is the trusted build context of §5.3 bullet one), and states
so in `repl-state` under `:macros {:runtime-authorized? true}`.

---

## §6. Coexistence and migration

### 6.1 The divergence register

`docs/design/yin.vm.divergence-register.md` changes in two places (Phase
0 writes the text; the phases that follow make it true):

- "The five user-visible changes", item 2, becomes: *"User-defined macros
  are expanded by `yin.vm.macro`, not by an evaluator. `:compile`
  (default) and `:both` policies are expanded by a batch pass composed into
  the loader; `:runtime` and `:both` are expanded at a `:yin/macro-expand`
  transition (walker) or `:macro-call` instruction (semantic) under a
  supplied authorizer. v1's evaluator-embedded expansion (`semantic`,
  `register`, `stack`, `space` each calling `yin.vm.macro`) has no v2
  counterpart. Direct `eval` does not expand; the composition does."*
- A new section **Macros** recording: expansion order is outermost-first
  where v1 was innermost-first; macro arguments are AST maps where v1's were
  eids with `get-attr`; the root is an explicit `:yin/root` fact; a macro
  closure applied as a function is an error where v1 would have evaluated
  its operands; runtime expansion requires an authorizer where v1 had a TODO;
  bootstrap `defmacro` events carry `:yin/macro-name` and no `:yin/macro`.
- The scope sentence "`macro` … not ported" is removed from the header.

`parity_test.cljc`'s docstring ("the corpus is macro-free because neither
evaluator has a `macro-expand` branch") is updated to say the corpus is
macro-free because v1's walker has none; the v2 macro corpus lives in its
own suites (§7).

### 6.2 `yin.repl`

- `make-vm` passes `:macro-eval ast-walker/macro-eval` and
  `:macro-authorize (constantly true)` to the constructor.
- The shell state gains `:macro-env {}`. After every successful evaluation
  the shell merges `(macro/definitions expanded-batch)` into it. That env is
  handed to `yang.clojure/compile-program` (new arity, §2.5) and to
  `yang.python/compile` and `yang.php/compile` (§2.6), and to
  `macro/expand-with` as `:macro-env`. `(reset)` and `(vm ...)` clear it with
  the store, since the macro closures live in the store.
- **One program path.** `eval-ast` stops calling `vm/eval` and instead
  converts the AST to datoms and takes the `eval-datoms` path: append to the
  program medium, `run-on-stream` with the loader
  `(comp <evaluator-loader> (macro/expand-with {:macro-eval … :macro-env …}))`.
  This is the change that makes compile-time expansion a loader composition
  in the shell rather than a special case, and it removes the shell's second
  evaluation path. `vm/eval` remains for direct callers and tests.
- `(compile expr)` renders AST, datoms, and — new — the expanded datoms and
  the events, so a user can see an expansion without running it.
- `defmacro` at the prompt is just a form: top-level → yang lowers it to
  `yin/def`; the batch runs; the store now holds a macro closure; the shell's
  `:macro-env` learns the name from `definitions`. The next input that calls
  it is compiled with that env and emits `:yin/macro-expand`.
- `help-text` and `repl-state` mention macros; no other shell behaviour
  changes.

### 6.3 Cross-platform portability

- **One `.cljc` per namespace**, no host branches in the expander: it is
  maps, vectors and `reduce`.
- **cljd**: the `opcase` literal map gains `:macro-call 24` alongside
  `opcode-table` (semantic spec Appendix B); `catch` uses the
  `#?(:cljd Object :clj Throwable :cljs :default)` form; no `^:unsynchronized-mutable`
  fields — the ledger and counters are record fields updated by `assoc`; any
  reader conditional excluding host code puts `:cljd` first (the
  `#?(:clj …)` trap: it does not exclude code from the cljd host-eval pass).
- **cljs**: keyword mnemonics are compared only in `linearize` and the
  loader; the expander compares `:type` keywords with `=` on maps it built
  itself; symbols from `yin/gensym-sym` are built with `symbol`, not string
  identity.
- **Test discovery**: shadow `:node-test` auto-discovers `*-test`
  namespaces; each new suite's "Testing yin.vm.macro-test" line is
  confirmed in the node output as part of acceptance.
- The prelude's `yin/make-lambda` tail-marking and `yin/sequence-body` are
  ported from the deleted v1 code as map operations; v1's datom-emitting
  versions are not reused.

---

## §7. Phased implementation roadmap

### Phase 0 — Spec and contract

Deliverables:
- `docs/design/macro-design.md` amended to v2: §2–§5 of this document
  promoted (argument protocol, prelude table, outermost-first order, root
  fact, event attributes including `:yin/macro-name`, authorizer interface);
  the "assumes register/stack" sentence and `maybe-recompile-at-boundary`
  runtime flow removed (the semantic VM's segments are immutable; runtime
  splicing is §4.2, not recompilation).
- `docs/cross-language-macro.md` status line changed from "target deleted"
  to "target is `yin.vm.macro/expand-batch`", §3 normalization paragraph
  replaced by §2.6's rule.
- Divergence register text (§6.1).
- `yin.vm`: `schema` gains `:yin/root` (boolean) and `:yin/macro-name`
  (symbol); `index-datoms` honours `:yin/root` and excludes event entities
  from the heuristic; `ast->datoms-with-root` gains `:existing`;
  `datoms->ast` gains `:keep-eids?`; `opcode-table` and `opcase` gain
  `:macro-call 24`.

Tests: `yin.vm-test` additions — root fact wins over heuristic; event
entity never chosen as root; `:existing` yields a ref and no datoms;
`:keep-eids?` round-trips `:eid`. Acceptance: all existing v2 suites green
on three hosts; walker `compile` output unchanged (no `:eid` leaks).

### Phase 1 — Compile-time expander

Deliverables: `src/cljc/yin/vm/macro.cljc` — `expand-batch`, `expand`,
`expand-with`, `definitions`, `invoke`, `bind-params`, `valid-ast?`,
prelude, `bootstrap` (`defmacro`), `stdlib-forms`, `forward-step`;
`yin.vm.ast-walker/macro-eval` (the one walker change in this phase —
additive, exported, no transition touched).

Tests: `test/yin/vm/macro_test.cljc`:
- `defn` from `stdlib-forms` expands a yang.clojure program; the walker runs
  the expanded batch and the value matches the same program compiled with
  yang's native `defn` fallback (parity of the two `defn` paths).
- Provenance: event shape; every output datom's `m` is the event; the
  original call datoms are present and unchanged (vector prefix equality);
  the query chain source-call → event → root resolves.
- Sharing: an operand returned unchanged is referenced, not copied (count
  of output datoms equals the macro's own emission).
- Outermost-first: a macro that inspects an operand containing a macro call
  sees `:yin/macro-expand`.
- Fixpoint: a macro whose output calls another macro yields a tree with no
  enabled macro node; the final scan finds none.
- Guards: direct recursion trips `:depth-guard` at 100 with the chain in the
  error; a macro emitting a 10,001-node vector trips `:datom-guard`.
- Phase: a `:runtime` macro is listed in `:deferred` and left intact; a
  `:compile` macro under `:phase :runtime` fails `:phase-violation`.
- Invalid output (a bare symbol) fails `:invalid-output` with a path; the
  failed event with `:yin/error` is in the returned diagnostic datoms.
- Arity and variadic `&` binding; `yin/gensym-sym` uniqueness across two
  expansions in one batch; a body that calls `stream/make` fails with the
  existing constructor error.
- `definitions` finds a `yin/def` macro and prefers the last in batch order.
- Bootstrap `defmacro` (non-top-level) expands to a `yin/def` of a macro
  lambda with the requested policy.

Acceptance: green on three hosts; `expand-batch` output is deterministic
(two runs, equal vectors); a batch without macro nodes is returned unchanged
(no root fact, no allocation beyond the scan).

### Phase 2 — AST-walker runtime integration

Deliverables: `ast_walker.cljc` — `:yin/macro-expand` arm in
`cesk-transition`; `:lambda` arm copies macro flags; `apply-function` rejects
macro closures; loader records `:min-eid` and `:program-root` and keeps
`:eid` on macro lambdas; `create-vm` accepts `:macro-eval`,
`:macro-authorize`, `:capability`; `:macro-expansions` and `:macro-eid`
fields; `macro/drain-expansions`. `yang.clojure`: `defmacro` policy
metadata; `compile-program` macro-env arity and `compile-program*`.

Tests: `ast_walker_test.cljc` additions:
- A `:runtime` macro defined and called in one program expands and runs;
  the ledger holds one event with `:yin/phase :runtime` and output datoms
  with `m` = event.
- A `:both` macro expanded at compile time leaves the ledger empty (zero
  runtime overhead is observable).
- No authorizer → `:unauthorized`; `(constantly true)` → expands; an
  authorizer returning a map → that map in the error and on the event.
- `:compile` macro reaching the transition (via `vm/eval` with no pass) →
  `:phase-violation`.
- A macro closure applied as a function → error.
- A runtime macro inside a loop of 10³ iterations expands each time and the
  ledger has 10³ events; `drain-expansions` empties it and returns them.
- Park after an expansion, `pr-str`/`read-string` the VM value, resume: the
  spliced AST is in the value; nothing is a host object.
- Hot-loop parity: the countdown benchmark program's step count and value
  are unchanged before and after this phase (the inlined loop did not change).

Acceptance: green on three hosts; walker macro-free corpus unchanged;
parity test docstring updated.

### Phase 3 — Semantic VM runtime integration

Prerequisite: semantic spec Phases 1–2 (the VM and `linearize` exist).

Deliverables: `linearize.cljc` — `:macro-call` emission, `:closure` macro
attributes, `lower-ast` options `:terminator :return`, `:derived-from`, `:m`,
`:phase-violation` on `:compile` nodes; `semantic.cljc` — `:macro-call`
transition (resolve, phase, authority, expand, lower, load, segment call),
`:macro-sites`, `:min-eid`, ledger fields, `create-vm` options as the
walker's; `yin.repl.core` — `make-session` composes
`(comp semantic/vm-load-program linearize/lower (macro/expand-with …))` for
`:semantic`.

Tests: `semantic_test.cljc` / `linearize_test.cljc` additions:
- Lowering a deferred macro yields exactly one `:macro-call` with an inline
  `:yin.code/call-ast` equal to the node map; a `:compile` node fails.
- The ephemeral segment ends in `:return`; its instructions carry `m` =
  event and `:yin.code/source` into the expansion output; it is present in
  the ledger entry.
- Non-tail `:macro-call`: `k` grows by one frame during the expansion and
  returns to `pc+1` with the expansion's value; tail `:macro-call` in a
  tail-recursive loop keeps `k` depth 0 over 10⁵ iterations.
- Parity axis: every Phase 2 walker macro test runs on `:semantic` with the
  closure normalization of the semantic spec §7.2; values, effect order,
  and ledger events (modulo the extra segment datoms) are equal.
- A shipped continuation captured inside an ephemeral segment resumes on a
  second VM that received the segment datoms ahead of it (the semantic
  spec's own handoff demo, with a macro).

Acceptance: green on three hosts; the macro-free semantic corpus unchanged.

### Phase 4 — Cross-language verification and tests

Deliverables: `yang.python/compile` and `yang.php/compile` macro-env
arities with `:call` interception and shadowing; REPL `:macro-env`
persistence and the single program path (§6.2); `(compile …)` rendering of
expansions; `python_macro_test.clj` and `php_macro_test.clj`.

Tests:
- The Python parser has no list literal, so a `defn("double", ["x"], …)`
  call cannot be written today; the cross-language test therefore defines a
  small Clojure macro with a scalar signature,
  `(defmacro twice [f] (yin/application f [(yin/application f [(yin/literal 1)])]))`,
  and calls `twice(inc_fn)` from Python and `twice($inc_fn)` from PHP. The
  three frontends' `:yin/macro-expand` nodes, expanded in one batch after
  composing the streams as `docs/cross-language-macro.md` §4 prescribes,
  produce equal values on the walker and the semantic VM. A second test
  exercises `yin/name-of` with a Python string, a Clojure symbol, and a
  Clojure `:variable` reaching one macro. A `defn` from Python is reserved
  until the parser gains list literals.
- Original call datoms from the Python batch are present and unchanged
  after expansion; the event's `:yin/macro-name` is `twice` and
  `:yin/macro` refs the Clojure lambda's eid.
- REPL shell test: `(defmacro …)` then a call in the next input expands;
  `(lang :python)` then a call to the same macro expands; `(reset)` forgets
  it and the call fails `:unresolved`.
- REPL `(compile …)` shows expanded datoms and events.

Acceptance: green on three hosts; `docs/cross-language-macro.md` status
updated to "implemented, Phase 4"; divergence register's Macros section
matches observed behaviour.

### Reserved (not scheduled)

Runtime expansion memo keyed by call site; expansion log as a stream
composed by the VM's constructor; Python/PHP `defmacro` surfaces over the
prelude; DaoDB-backed `macro-env` producer; Shibi authorizer; automatic
hygiene; the stack VM's `OP_MACRO_EXPAND` (with the stack VM).

---

## Appendix A. Deviations from the brief and from the prior documents, with reasons

1. **Macro arguments are AST maps, not eids** (vs v1 `yin.vm.macro` and the
   `ctx` of macro-design.md). Reason: every frontend and evaluator already
   speaks maps; eids coupled macro bodies to a datom index and to Clojure.
   Sharing of unchanged subtrees is preserved by carrying `:eid` (§3.5), so
   the provenance and datom-count properties v1 had are kept.
2. **Outermost-first expansion** (vs v1's post-order `transform`). Reason: a
   macro must be able to see and rewrite operand forms that contain macro
   calls; innermost-first makes `->`-style macros impossible. Fixpoint and
   guards are unaffected.
3. **Frontends do not coerce arguments for a macro's benefit** (vs
   `docs/cross-language-macro.md` §3). Reason: it couples a frontend to
   macro signatures it cannot know; the prelude accepts the canonical
   shapes instead.
4. **The root is an explicit fact** (new). Reason: non-destructive expansion
   leaves the old root unreferenced and the existing heuristic would run the
   unexpanded program. This is "no assumed graphs" applied to the batch.
5. **Runtime expansion in the semantic VM is a segment call, not a
   recompile-at-boundary** (vs macro-design.md "Runtime flow"). Reason: v2
   segments are immutable (semantic spec §5.2); `maybe-recompile-at-boundary`
   was v1's mechanism for programs that grow by append, and a spliced
   ephemeral segment is both simpler and exactly what a continuation can
   name.
6. **The expansion record is a VM-value ledger, not a stream append inside
   the transition** (interpreting "explicit boundary effects"). Reason: §4.4.
7. **`:yin/timestamp` is logical and optional** (the brief lists it as an
   event attribute). Reason: a host clock inside the expander destroys
   determinism and the parity test; §5.2.1.
8. **`:yin/macro-name` is added to the event.** Reason: bootstrap macros
   have no entity, and cross-batch `:yin/macro` refs are unresolvable until
   commit; a name makes both cases queryable.
9. **`eval` does not expand.** Reason: the divergence register already
   defines `eval` as direct evaluation of supplied work; composing the pass
   in front of it is one `comp`, and a hidden pass inside `eval` would be
   the implicit control flow the invariants forbid.
10. **`macro-eval` is supplied, not required by `macro`.** Reason: the require
    cycle `ast-walker → macro → ast-walker`, and the rule that evaluator
    choices belong to the composition.

## Appendix B. Risks the implementer should watch

- **Root selection.** Until Phase 0's `index-datoms` change lands, any
  expanded batch runs the *unexpanded* program silently. Land the root fact
  and its test first, before the expander exists.
- **`:eid` leakage into the walker's hot path.** `:keep-eids?` must be used
  only for macro lambdas in the loader and for operand reconstruction in the
  expander. An `:eid` on every node costs a map entry per node on the hot
  path and changes the `compile` command's rendered AST; the Phase 0
  acceptance test guards it.
- **Tail marking in `yin/make-lambda`.** v1's `mark-tail!` recursed through
  `:if` and immediately-applied lambdas; missing a case loses TCO in
  macro-generated bodies and shows up only as stack growth on deep
  recursion. The Phase 3 tail test (10⁵ iterations, `k` depth 0) is the
  guard; run it on the walker too.
- **Ephemeral segment ordering.** `lower-ast` for an expansion must emit the
  main sequence ending in `:return`, then out-of-line lambda bodies; a `:halt`
  left in by reuse of the program lowering path halts the outer VM inside a
  macro. Test by expanding a macro whose output contains a lambda.
- **Ledger growth.** A long-lived VM running a runtime macro in a loop
  accumulates events until the composition drains. The REPL drains after
  each evaluation; a daemon composition must drain on its own cadence. The
  10³-iteration test in Phase 2 documents the cost.
- **Authorizer absent in tests.** Every runtime-macro test must construct
  the VM with `:macro-authorize`; a test that forgets sees `:unauthorized`
  and may be "fixed" by adding a default. Do not add a default.
- **cljd `opcase` literal map** — `:macro-call 24` in both places, or the
  semantic VM dispatches a `:macro-call` to the default arm.
- **Outermost-first and `compile-macro-operands`.** yang.clojure's
  `:yang/shadow-params-operand` hint compiles later operands with macro
  names shadowed; that logic is unchanged, but it means the *frontend*
  decides some shadowing that an outermost-first expander would otherwise
  see as macro calls. Keep the hint semantics documented next to §3.3 so no
  one "simplifies" them away.
