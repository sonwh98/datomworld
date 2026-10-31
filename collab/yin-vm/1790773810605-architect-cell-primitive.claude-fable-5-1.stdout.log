Completed-GMT: 2026-09-30 13:15:39 GMT
Completed-Local: 2026-09-30 20:15:39 +07
Coding-Agent: claude
Session-ID: f8eef849-bc12-4f36-87ee-4ae5da8aaa8c

# Architect ruling: the cell primitive

Read-only review; no repo file was edited and nothing was executed, so every claim below is from reading the code. The session was in plan mode, so a condensed copy of this ruling is also at `/Users/sto/.claude/plans/read-collab-1790773810605-architect-cell-cosmic-harp.md`.

The cited Rule R ruling (`collab/1790345200000-architect-yin-def-rule-r-final.claude-fable-5-1.findings.md`) does not exist in `collab/`. I took Rule R from the code instead (`src/cljc/yin/vm.cljc:99-226`, `src/cljc/yin/vm/engine.cljc:77-123`).

## Recommendation (one design)

**Cells are ordinary sealed reference values over a task-scoped heap, reached through a host module of effect constructors.** No new AST tag is needed, and `yin/def`, Rule R, `:vm/store-put` and the canonical grammar stay as they are.

- **Surface:** a host module `cell` with `cell/new`, `cell/get`, `cell/set!`, registered through `module/register-host-module` exactly as the `stream` module is (`src/cljc/yin/vm/module.cljc:253-301`). Each export is an `:effectful` constructor returning plain effect data.
- **Value:** `{:type :cell-ref :id :cell-N :seal s}`, issued by `engine/issue-ref`, with the id from the existing `:id-counter`.
- **State:** one new explicit VM-state field, `:heap {id value}`, task-scoped like `:resources` and `:parked`.
- **Evaluation:** three arms in `engine/handle-effect` (`engine.cljc:1755`). All four VMs already send a primitive's effect result there (`ast_walker.cljc:160`, `semantic.cljc:239`, `debruijn/stack.cljc:592`, `debruijn/register.cljc:610`), so one engine edit gives four-VM parity.
- **Semantics:** Scheme box. Cells are shared across continuation re-entries and never rolled back.
- **Boundary:** copy on lift, with sharing preserved inside one lift; the receiver mints fresh ids and re-seals. There is no cross-task shared cell.

This restores the classic CESK "address" for the variables that need one. yin.vm collapsed env to name→value; the named store stays statically keyed, and the heap is the dynamically allocated half.

## Q1. Is a cell primitive needed?

**Yes. A runtime-allocated mutable location is needed; a new AST tag is not.**

| Alternative | Ruling | Why |
|---|---|---|
| Threaded heap / CPS with state | Reject as baseline | It forces a whole-program ABI on every guest function and every cross-language call (`yang.antlr.md` §8.10). Under continuation-lowered control flow, every continuation argument must also carry the heap, or mutations before a `raise` vanish at the handler. No VM change, but it costs an allocation per call and the heap map never shrinks. |
| Streams as cells | Reject | `:stream/next` on an empty stream parks instead of answering, cursors mint only at `:dao.stream/oldest`, and there is no overwrite. Each local would also cross the host `:make-stream` boundary, and in-memory handles refuse to lift (`engine.cljc:603`). |
| Gensym'd store key | Not expressible | There is no runtime-keyed read: `:variable` and `:vm/store-get` keys are literal. A runtime-keyed write exists only by accident (finding F1). Building on it would break "every definition key is literal". |
| Literal key per variable | Wrong | Two activations share the key, so recursion and closure instances break. |
| Pure lambda plus continuations | Impossible | Multi-shot continuations without a store give no state. |

Two facts strengthen the need beyond the orchestrator's analysis:

- **Continuations capture the environment too.** A reified continuation is `{:type :reified-continuation :k k :env env}` (`ast_walker.cljc:530-535`), and invoking it restores `(:env fn-value)` (`:261-267`). A local assigned between capture and invoke rewinds. So "not captured by any closure → rebinding" is unsound once `try`/`raise` and `break` lower to continuations. `x = 1; try: x = 2; raise E; except: print(x)` prints 1 under SSA rebinding. A raise from a callee cannot pass the caller's locals as the continuation argument, so those locals need cells even with no closure anywhere.
- **Generators need a cell regardless.** A generator object is a mutable identity whose saved continuation changes at each `yield`.

Cost to the lowering is one local rewrite per variable (`cell/new` at the binder, `cell/get` at reads, `cell/set!` at writes). Cost to evaluation is one effect dispatch per operation on the slow path, identical on all four VMs.

## Q2. Shape

**(c), cells as ordinary ref values.**

- **(a) a tag trio is rejected for now.** It touches the §2.3 table, codec, validators, linearizer, semantic bytecode grammar, both de Bruijn compilers, the footprint table, the contract stamp and four kernels. "Derive, don't persist" and "do not optimize prematurely" both say no. Streams already exist in both forms (tags and module), so promotion later on measured hot-path cost has a precedent.
- **(b) a runtime key on `yin/def` or `:vm/store-put` is rejected outright.** It weakens Rule R's literal-key shape and destroys static store-slice extraction. It is also wrong under module-store routing: the key would land in whichever store is active at the writer, and a reader in another module's closure would look in a different store.
- **Rule R is untouched.** `cell/*` are ordinary free names resolved through the module registry (`engine.cljc:95-101`), and `reserved-names` does not grow. They are shadowable like `stream/make`; the frontend owns generated names and guest identifiers are never namespaced symbols.
- **Sealing is required.** A forged `{:type :cell-ref :id :cell-3}` would read another closure's private local, which is the same threat r10 closed for stream refs. `authentic-ref?` (`engine.cljc:287-299`) must test cell liveness with `contains?`, because a cell may hold nil.
- **`cell/get` is the safe container.** `handle-effect`'s `:value` is not re-interpreted, so a cell holding an effect-shaped guest map returns it as data.

## Q3. Store slice, dependency completion, migration

- **Where it lives:** a separate task heap.
  - Not the named store: collision with literal keys, forgeable by `:vm/store-get`, mis-routed by `:store-of`.
  - Not the continuation: that would give rollback semantics and break sharing between sibling closures.
- **Static extraction is unchanged.** Cell ids never appear in code, so no code address changes and "every definition key is literal" (`code-as-tuples.md:1381`) stays true. `cell/new` is a free-name obligation discharged as a module, with its effects read from the profile (§7.7.2).
- **Heap slice is pulled by value reachability.** It is the same rule as stream and cursor ids (`dependency-completion.md` §7.1 rule 3): `abstract({:type :cell-ref}) → [:cell id]`, contents pulled once and memoized by id, then abstracted. The memo is mandatory because cells introduce cycles (a nested function that refers to itself through a captured cell).
- **Lift:** `:yin.k/heap {lift-local-id encoded}` beside `:yin.k/store`, with the id minted before the content is encoded, mirroring `cell-for!` (`engine.cljc:606-618`). Lower mints fresh ids and re-seals, as `lower-resources` does.
- **Park and in-process serialization:** the heap is plain data in the VM value and travels with it as the store does.
- **Suspended generators do not migrate yet.** A cell whose content is a reified continuation refuses the lift as `:non-canonicalizable` (`engine.cljc:658-659`). That is an existing implementation gap, independent of cells, and the completion design already abstracts `[:k …]`.
- **Two silent failures must be closed in slice 1** (finding F3).

## Q4. Multi-shot continuations

**Confirmed: box semantics.** Invocation passes the VM state through and replaces only control, env and k, and the semantic, stack and register kernels state "the store is not a register". A heap field in that state behaves the same.

- **Exceptions:** mutations before a `raise` persist at the handler, which is the correct Python/PHP/JS semantics and falls out for free.
- **Generators:** the resume continuation lives in a cell. The lowering invokes each captured continuation at most once, so env-bound locals never visibly rewind.
- **Loops:** lower to recursive lambdas. Use continuations only for the escapes (`break`, `continue`, `return`), because a continuation takes exactly one argument (`engine.cljc:65-74`).
- **Mixed semantics, stated as a frontend obligation:** env-bound locals rewind on re-entry and cell-bound ones do not. A frontend that exposes multi-shot continuations to guest code must box every assigned variable.
- **Spike rule:** box every reassigned local. SSA rebinding is a later optimization for locals proven not live across any capture.

## Q5. Module stores, peer observers, invariants

- **Module stores (linker §7.3):** cells are reached by reference, so `:store-of` routing is irrelevant to them. Module-level variables and `global x` stay `(yin/def x v)` into the module store.
- **Exported closures over cells:** after slice 2, a module exporting one gives each receiving task its own copy, seeded at the child's halt-time value. This matches the per-task instantiation of module stores.
- **Known wart for slice 2:** a repeated `receive-module` re-lowers the slice while keeping the held module store (`engine.cljc:847-859`). Cursor cells have this today, and heap cells would inherit it.
- **Peer observers:** cell operations are ordinary `:application` rows. `dao.space` indexes them as syntax and never sees a heap, and the evaluator never consults an index. Symmetric ignorance holds.
- **No hidden global state:** `:heap` is a named field of the VM value, per task.
- **No shared mutable state:** nothing host-mutable is introduced; a write yields a new VM value. Aliasing is logical identity inside one owning interpreter (`yang.antlr.md:1003-1005`). A cell-ref that crosses a stream raw fails closed at the receiver, whose secret differs.
- **Axiom 3:** a cell is an entity with one attribute over time, and the heap is the "latest value" interpretation of that history. A datom projection is derivable later and adds nothing to persist now.

## Q6. Minimal first slice

**Slice 1:**
1. `yin.vm.module`: `cell-module`, `cell-profiles`, `register-cell-module`.
2. `yin.vm.engine`: the three `handle-effect` arms, one heap write function, and `authentic-ref?`/`check-ref!` extended to `:cell-ref` against `:heap`.
3. Explicit fail-closed arms in the encoder and in `completion/abstract-value`.
4. A fresh `:heap` in the VM constructors and in `spawn-module` children.
5. The box-every-reassigned-local rule in the frontend.
6. Parity tests on four VMs across CLJ, CLJS and CLJD:
   - a counter shared by two closures;
   - distinct cells per activation;
   - mutation surviving continuation re-entry and an abortive raise;
   - a forged ref refused;
   - a cell holding nil;
   - a cell holding an effect-shaped map;
   - lift of a closure over a cell refused.

**Can wait:** heap slice in lift/lower and the UCF 7.5/7.6 amendment; heap reclamation (cells are never freed in slice 1, so long-running tasks grow); dedicated tags or opcodes; datom projection; reified-continuation encoding.

## Owner decisions

1. **Copy-on-lift semantics.** A closure over a cell that crosses a task boundary gets an independent copy. I recommend confirming; it follows from the no-shared-mutable-state invariant.
2. **Naming.** "Cell" collides with the existing "cursor cell" and `:yin.k/cell` vocabulary (`engine.cljc:606-618`, UCF 7.5.3). I recommend keeping `cell/*` for the surface and naming the state `:heap` and `:yin.k/heap`. The alternative is `box/*`.
3. **Amend `yang.antlr.md` §8.1.** The baseline changes from "immutable heap state is threaded" to a task heap of cells (finding F2).
4. **Schedule F1 before guest dict values flow through the spike,** or accept the risk for the spike.
5. **Acknowledge the consequence of continuation-only control flow.** Locals assigned inside `try` bodies and continuation-exited loops need cells. The alternative is explicit completion records (`yang.antlr.md:999-1000`), which contradicts your direction; I recommend keeping continuations.

## Findings

| ID | Severity | File:line | Invariant / evidence | Recommended correction |
|---|---|---|---|---|
| F1 | medium, architectural defect, pre-existing | `src/cljc/yin/vm/module.cljc:231-234`; `vm.cljc:373-377`; `engine.cljc:1770-1775` | Effect detection is by result shape. `:pure` primitives with declared effects `#{}` (`first`, `conj`, `assoc`, `get`) can return guest data carrying `:effect` and raise any engine effect, including a runtime-keyed `:vm/store-put`. This contradicts §7.7.2 (effects are read from the profile) and the warning at `yang.antlr.md:1016-1018`. | Decide effect-ness from the callee's profile class, not the result's shape. The mechanism is an Engineer/Architect follow-up, since a per-call reverse lookup is too slow for the hot path. |
| F2 | medium, design contradiction | `docs/design/yang.antlr.md:992-1001` vs `:1059`, `:1115`, `:1308-1314` | §8.1 prescribes a threaded immutable heap, while §8.3, §8.5 and §9.4 say "store cells" and "binding cells", and no store cell exists. | Amend §8.1 to the task heap; define "store cell" as a `cell` module ref. |
| F3 | medium, implementation gap, slice 1 | `src/cljc/yin/vm/completion.cljc:112-113`; `engine.cljc:660-664` | A `:cell-ref` falls into the generic map arm of `abstract-value`, so its contents are dropped and `:complete` is unsound. The encoder's default arm would encode it as a literal carrying the emitter's seal. | Add explicit arms that refuse (`:yin.k/non-portable`, kind `:cell`) in slice 1 and pull in slice 2. |
| F4 | low, analysis correction | `src/cljc/yin/vm/ast_walker.cljc:261-267`, `:530-535` | The orchestrator's "uncaptured locals → rebinding" ignores that a continuation captures env. | Apply the Q4 rule; box-all for the spike. |
| F5 | low, doc | `docs/design/yin.vm.dependency-completion.md:121-124`; `code-as-tuples.md` §7.7.1 | "Cycles only arise through the store" becomes false with a heap, and the footprint text has no cell rows. | Amend with slice 2; no table rows are needed while cells are profile effects. |
| F6 | low, stale doc | `src/cljc/yin/vm/docs/assignments.md:391-515` | It recommends literal-key store operations for locals, which two activations would share, and cites nodes that do not exist. | Mark superseded by this ruling. |
| F7 | info, brief citations | prompt lines 29 and 37 | `:vm/gensym` is at `ast_walker.cljc:502-504`; store-node routing is at `:505-518`. | None. |
