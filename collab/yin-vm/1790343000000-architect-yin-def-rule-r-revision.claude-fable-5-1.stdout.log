Completed-GMT: 2026-09-25 13:56:58 GMT
Completed-Local: 2026-09-25 20:56:58 Asia/Ho_Chi_Minh

Read-only revision; no files edited. I verified every location codex cited. Codex is right on the substance of all four P1s and the P2. On two details it overstates, and I hold those with evidence below. Rule R survives and becomes complete under one condition, stated in item 5. I withdraw the validator-only M2 fallback.

## Verdicts on codex's factual claims

**PARTLY, "def is already a special form": ACCEPT.** Yang dispatches on the head symbol before local lookup (clojure.cljc:137) and then lowers to an ordinary application (clojure.cljc:157). My phrasing was "the front end treats it as special; the evaluators do not," which is codex's reading too. No revision needed beyond precision.

**TRUE, ordinary primitive: agreed.** vm.cljc:194.

**TRUE narrowly, shared resolver: ACCEPT the narrowing.** Direct store instructions never touch the resolver (walker 419 to 438; stack and register `:store-put` cases). My design already treated them separately, but the sentence "the shared resolver simply never sees the name" was too broad.

**FALSE as stated, "only primitives raise effects, all store keys are literal": PARTLY.** Two halves. First half: a program closure returning an effect-shaped map is only a value, and the walker dispatches effects only for host functions (ast_walker.cljc:189). That holds. But a composition-supplied host function is a primitive by registry, and it may return an effect with any key, since the effect predicate is just "map with an effect key" (module.cljc:77) and the dispatcher writes whatever key it is handed (engine.cljc:511). Second half: the literal-key claim holds for portable syntax only. The `:vm/store-update` node is walker-only, absent from the row grammar (vm.cljc:795 to 822), and unsupported by de Bruijn resolution (debruijn_resolve.cljc:71), so it never enters an image, but it does exist on the raw walker path. Codex's correction stands for both halves. Revised wording: every store write that can appear in an admitted image has a literal key; host-registry effects and walker-only nodes are outside the image and are guarded at dispatch.

## Verdicts on the findings

**P1, macro incoming store: ACCEPT.** `macro-of` reads the operator name straight from the context store (macro.cljc:973 to 980) and `expand-node` consults it before anything else (macro.cljc:1105). The store arrives through `make-ctx` (macro.cljc:1224), through the previous batch's next-store (macro.cljc:1161), and through `seed-store` (macro.cljc:1268); the REPL seeds one from standard forms (repl.cljc:470 to 483). Refusing at harvest only covers new definitions. Revision: `make-ctx` and `expand-batch` refuse a context whose store binds a reserved name, with reason `:reserved-name`, before any expansion; a `:next-store` carrying one is impossible once harvest refuses, but the admission check runs anyway so a restored or hand-built store is caught. Test: seed a store with a macro named `yin/def`, then expand a batch containing a definition; the batch is refused, not rewritten.

**P1, direct admission paths: ACCEPT the gap, PARTLY on the row-local point.** The walker takes datom batches through `vm-load-program` (ast_walker.cljc:709) and row sets through `vm-load-rows` (ast_walker.cljc:725), and its raw-AST fast path resolves variables directly (ast_walker.cljc:628). None passes the linker. Callers today include the REPL, dao.await, the expander's own transformer VM (macro.cljc:729), and the semantic loader (semantic.cljc:670). "Every image reaching any engine has passed linker validation" was false and is withdrawn. On the row-local point codex is right that a content-addressed `[:variable yin/def]` row is shared by every occurrence, so a per-row check cannot know the use site. But the whole-tree occurrence machinery already exists: the linker's AST scanners are occurrence-path queries over `vm/occurrences` (linker.cljc:289 to 319), and `vm/validate-rows` is the whole-tree step 4 validator. The occurrence-aware check goes there and in `semantic-bytecode->ast`, which `vm-load-rows` already runs (ast_walker.cljc:726 docstring). Revised enforcement table is in item 1.

**P1, effect dispatch and supplied stores: ACCEPT.** A host callable can emit a store-put effect keyed `yin/def` and the dispatcher accepts it (engine.cljc:511). Supplied initial stores exist on all three constructors (stack.cljc:149, register.cljc:164, vm.cljc:1693 via the pair store). Codex is also right that this cannot shadow a syntactic definition; it only broke my "store never holds the key" claim. Revision: the reserved-key check moves into `engine/handle-effect` for `:vm/store-put`, into every direct store instruction, and into each `create-vm` for a supplied store. Host effects with computed keys keep the incomplete-discovery treatment.

**P1, contract versioning for all four: ACCEPT.** UCF 7.3.3 says a changed transition or added opcode is a new revision (UCF 262 to 274). Rule R changes the walker's application transition, adds a semantic opcode, and adds a stack and register opcode. The linker records advertise "v2", "v2", "b1", "r1" (M2 linker.cljc:687, 705, 727, 747), the manifests carry them per format (linker.md 1525), and the derivation record stamps "v2" (ledger.cljc:48). All four must move. Plan in item 2. One thing I found while checking: no lowering path in the tree compares an incoming stamp against its own. The stamp is produced at lift (ucf.cljc:221) and mentioned in `completion`, but no loader refuses `:yin.k/profile-mismatch`. That is a pre-existing defect the versioning plan depends on, and I list it as such.

**P2, quoted data: ACCEPT.** A literal symbol is data (clojure.cljc:140). Banning `(quote yin/def)` was unnecessary; my earlier text also said as much in the caveat, but the headline sentence conflated the roles. The rule is restated by syntactic role in item 1.

**Codex's other remarks.** It agrees on rejecting primitives-first lookup, agrees the round 3 and 4 filters go once R is complete, agrees the round 2 and 3 recognition and position logic stay, and treats a dedicated define node as acceptable but not required. I keep the application shape, with codex's condition adopted: one shared recognizer serves the validators, the linearizers, and the walker.

## 1. Rule R, revised

`yin/def` is syntax, never a name. By syntactic role:

- **Variable occurrence.** A `:variable` row naming `yin/def` is legal only as the operator of an `:application` with exactly two operands whose first operand is a `:literal` symbol other than `yin/def`. Any other variable occurrence is refused. The same holds for a `:var yin/def` instruction, a `:load-free yin/def`, and a `[:load-free rd yin/def]`: none is legal at all after the lowering change, because the definition form lowers to `:define`.
- **Binder.** `yin/def` may not appear in any `:lambda` params, in any de Bruijn resolved frame, or in a supplied free env.
- **Store key.** `yin/def` may not be the key of `:vm/store-put`, `:vm/store-get`, `:vm/store-update`, any `:store-put` or `:store-get` instruction, any store-put effect reaching the dispatcher, or any entry in a supplied initial store or module-store snapshot.
- **Definition and macro key.** `(yin/def 'yin/def _)` is refused, and so is a harvested or stored macro under that name.
- **Quoted data.** A `:literal` whose value is the symbol `yin/def` is ordinary data and is allowed anywhere a literal is, including as the value operand of a definition.

Enforcement boundaries, each stating the same rule through one shared recognizer in `yin.vm`:

| Boundary | Where | When |
|---|---|---|
| Row set admission | `vm/validate-rows` and `semantic-bytecode->ast`, occurrence-aware | load |
| Datom batch admission | `vm/datoms->ast` followed by the same whole-tree check | load |
| Raw map AST | walker `:application`, `:variable`, `:lambda`, store node cases | transition |
| Semantic vector | `code/well-formed-vector?` for `:var`, `:store-*` keys, `:define` shape | load |
| Stack and register images | `image-defect` and `register-image-defect` | load |
| Linker | the four format records' row-defect and validate slots, which are the functions above | link, before any engine |
| De Bruijn resolution | resolver refuses a reserved param or a reserved free name outside definition shape | lowering |
| Expander | `make-ctx` and `expand-batch` refuse a store binding the name; harvest refuses the key; the transformer VM is a walker and inherits the transition checks | admission |
| Effect dispatch | `engine/handle-effect` refuses a store-put keyed `yin/def` | transition |
| Constructors | each `create-vm` refuses a supplied store, free env, or primitive registry binding the name | construction |

Load-time refusals cover everything that is a fact of the code or of supplied state. Transition-time refusals are the residue for paths with no load step: the raw map AST fast path and host effects. A transition-time refusal throws with the same `:reserved-name` data.

## 2. Contract versioning

New revisions: AST "v3", semantic "v3", stack "b2", register "r2". The revision text for each names the definition form and, for the three lowered formats, the `:define` instruction: semantic `:define key` acting on the value register, stack `[:define key]` popping the value and pushing it back, register `[:define rd key rs]`. Update: the four linker format records, the manifest's per-format contracts entry (linker.md 8.1), the derivation profile record (ledger.cljc:42 to 49), the UCF stamp constant (ucf.cljc:46), and the completion default (completion.cljc:697).

Old-contract images. For the three lowered formats an old image contains `:var yin/def` or `:load-free yin/def`, which the new validator refuses as a reserved variable occurrence, so old images are refused by validation alone, with no stamp needed. For the AST format an old image is byte-identical to a new one; only its interpretation changed, so refusal is by stamp only: the manifest's contracts entry and the linker's admission check (linker.cljc:1190) already refuse a mismatch, and a request that names no contract must now be refused rather than admitted, which is a one-line change to that check. For continuations, the lowering paths must actually compare the stamp and answer `:yin.k/profile-mismatch`, which no loader does today; that pre-existing defect becomes a blocker for this change. No compatible loader is offered. Clean break, per the no-backward-compatibility rule for this repo.

## 3. Documents to update

- UCF: 7.3.3 revision list gains reserved operators; 7.5.2 bullet and 7.11 blocker drop the `yin/def` profile; 7.6.1 discovery text for store keys.
- linker.md: 4.1 line 309, 4.2 5b, the four records in section 5, 8.1 contracts, the store-isolation clause is unchanged, section 11 criteria.
- yin.vm.semantic.md 2.4 and 4.2: new mnemonic and transition, revision "v3".
- yin.vm.debruijn.stack.md 325 to 340 and register.md 4.4: the `:define` opcode and lowering, revisions "b2" and "r2".
- yin.vm.macro.md 4.1 `macro-of` and 4.2: reserved names are never macros; context admission rule; 2.2 definition key rule.
- yin.vm.code-as-tuples.md 4.5 effect-from-profile paragraph and line 1831 "free at that site": the operator is syntax, not a free variable.
- yin.vm.engine.md: dispatcher key guard. yin.vm.ucf-revisions.md: the revision entries.

## 4. Sequencing

Accept codex: land Rule R as its own gated change before the M2 commit, and drop the validator-only fallback. The evidence is the admission table above. The walker's datom and row loaders, the REPL, dao.await, and the expander's transformer VM all run programs that never see a linker validator, and a host effect can write the reserved key regardless. The fallback's soundness claim held only for linked images and only for the store, which is too narrow to call "by construction." The two commits stay coherent as: Rule R with contracts and docs, gated on the tri-host lanes; then M2 with the round 3 and 4 filters deleted and fixtures flipped, gated once.

## 5. Sound and complete for constant keys

It holds under one condition: **the image was admitted under a Rule R contract revision, and every path that can write its store passed a Rule R boundary.** Given that, every application whose operator is `yin/def` defines its literal key on every engine, the invocation position places the write after the value operand (linker.cljc:427 to 468), and no other write can target the name. The receiver's `:shadowed-free` case for `yin/def` disappears because `yin/def` is no longer a free name.

Residual, all deferred and all over-retaining: `:vm/store-update` rows are not in the definition query and cannot enter an image anyway; export verification stays advisory; host-registry effects with computed keys stay incomplete-discovery. The missing stamp check at lowering is a defect, not deferred work.

## 6. Completion criteria, per host (JVM, Node, Dart), all four backends

1. Each row in the enforcement table has a test that fails before the change and refuses `:reserved-name` after, at the stated time (load or transition).
2. A store seeded with a macro named `yin/def` makes `make-ctx` refuse, and a batch expanded against it is refused before any rewrite.
3. A datom batch and a row set carrying a shared `[:variable yin/def]` row used once as a definition operator and once as a value are refused, proving the check is occurrence-aware.
4. A host primitive returning a store-put effect keyed `yin/def` is refused at dispatch on all four engines.
5. A supplied initial store, free env, or registry binding `yin/def` makes each constructor throw.
6. Redefining `x` twice reads the second value; a definition inside a lambda body and inside an `:if` behaves as before.
7. A lowered old-format image with `:load-free yin/def` is refused by validation; an AST request naming "v2" is `:contract-mismatch`; a request naming no contract is refused; a lifted continuation stamped "v2" is `:yin.k/profile-mismatch` at lowering.
8. The three M2 fixtures and the alias fixture refuse `:reserved-name`; `(yin/def 'x 'yin/def)` links and defines `x` as the quoted symbol.
9. Obligations of a definition-only module are empty on all four formats.
10. A continuation parked inside a definition's value operand round-trips through UCF and completes the write; the frame carries a define marker, not a function object.
11. Kondo, cljstyle, ASCII, and 80 columns clean.

Files read for this revision beyond the first round: macro.cljc context and store paths, ast_walker.cljc loaders and fast path, module.cljc, the three constructors' store options, ucf.cljc and ledger.cljc stamps, completion.cljc, the M2 format records, and every spec section codex cited. No test lane was run.
