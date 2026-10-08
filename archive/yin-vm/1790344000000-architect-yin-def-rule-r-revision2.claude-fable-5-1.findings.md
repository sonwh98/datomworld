Completed-GMT: 2026-09-25 14:41:16 GMT
Completed-Local: 2026-09-25 21:41:16 Asia/Ho_Chi_Minh

Read-only revision; no files edited. Codex's four new P1s are correct on the tree, and I accept the three-change split. The orchestrator's question is the important one, and the answer changes the shape of the design: the boundary table was enumerating boundaries for the wrong property. Soundness rests on two functions that already exist, not on a list of loaders. Details below, then the revised items.

## Verdicts on the new P1s and the three partly-resolved items

**P1, semantic datom-code loader: ACCEPT.** `semantic/vm-load-program` decodes a datom batch through `load-image` (semantic.cljc:670, 529), which validates with `code/well-formed?` (code.cljc:162), a different rule set from `code/well-formed-vector?` (code.cljc:370). Its `:var` at run time resolves through the shared resolver (semantic.cljc:263). Both validators gain the reserved-operand and `:define` rules. Under the structural argument below, that is early refusal, not the proof.

**P1, module-store snapshots and scheduler updates: PARTLY.** Snapshots: there is no code. A search of the source tree for `module-stores` or `store-of` finds nothing; the restore into `:module-stores` exists only in the spec (linker.md 1342). Codex says as much. It becomes a stated obligation on the M4 implementer, discharged by the store-write choke point below rather than by a new check. Scheduler updates: the merge at engine.cljc:425 takes `:store-updates` whose keys are either cursor ids the engine minted through `gen-id` as keywords (engine.cljc:79, 150) or transport-supplied entries keyed by those same ids. dao.await's keys are minted keywords too (await.cljc:178). No program symbol can enter that path, so no shadow route exists there. I add one assertion at the merge as a regression tripwire, not as a boundary. Carried environments: a restored env binding `yin/def` is inert once the resolver refuses the name before reading env, so no env check is needed for soundness.

**P1, old-image refusal: ACCEPT, and I retract the sentence.** "An old lowered image contains `:var yin/def`" holds only for images with a definition. Further, the stack loader does not validate at all (stack.cljc:104 assigns the segment directly; only the linker's admission runs `image-defect`), the register loader validates structure only (register.cljc:106), the semantic loaders take no contract (semantic.cljc:713, 728), and the M2 fetch admits an omitted contract (linker.cljc:1190). Plan in item 3.

**P1, UCF criteria: ACCEPT.** The UCF namespace holds canonicalization and safepoint helpers only; its public functions end at `unsatisfied-names` (ucf.cljc:387) and there is no lift or lower anywhere in the tree. The ledger's profile comparison (ledger.cljc:197) is a derivation check, not a continuation check. Criteria 7's continuation half and criterion 10 move to M4. A local parked-during-definition resume test replaces 10 in this gate.

**Direct admission, partly resolved: ACCEPT** as above. **Effect and store ingress: PARTLY** as above. **Contract revisions: ACCEPT**, plan in item 3.

## 1. The choke-point question

The orchestrator is right, and codex's rounds show why: each round found another loader because the design was trying to prove soundness at admission, and admission has many doors. The two facts Rule R needs are owned by two functions that every door already leads to.

**Fact one, no shadowing.** Every name resolution in every engine goes through `engine/resolve-var`: the walker (ast_walker.cljc:379 and 628), the semantic VM (semantic.cljc:265), the stack VM (stack.cljc:386), the register VM (register.cljc:390), and the UCF conformance check (ucf.cljc:387). Round one verified this, codex confirmed it. If `resolve-var` refuses a reserved name before it consults env, then no loader, no supplied env, no store entry, no ready-queue merge, and no snapshot can make a reserved name resolve to anything, on any path, admitted or not. A lambda parameter named `yin/def` becomes unreadable rather than dangerous. The definition transition never calls the resolver for its operator. Those two changes are the whole soundness proof. The table of loaders becomes early, friendlier refusal, and a loader that is missing from it costs a late error, not a hole.

**Fact two, the store never holds the key.** This is a tidiness invariant, and codex already agreed it cannot affect a syntactic definition. It has one honest shape: a single `engine/store-put` function that refuses reserved keys, used by `handle-effect` (engine.cljc:511) and by the four direct instructions that today assoc into the store themselves (walker 419 to 438, semantic opcode 11 at semantic.cljc:303, stack.cljc:482, register.cljc:481). The ingress merges (constructors, the ready-queue merge, dao.await, the future module-store lower) are state constructors, not code paths, and are closed by a lint rule: no `assoc` into `:store` outside `yin.vm.engine`. That rule is checkable by grep in the gate, so the invariant does not depend on anyone remembering a boundary.

Why there is no fourth round: the table no longer carries the proof. A new loader, engine, or restore path cannot reintroduce shadowing without either bypassing `resolve-var`, which would already break every other name, or writing the store outside `engine/store-put`, which the lint refuses.

## 2. Revised Rule R and completeness condition

The rule by role is unchanged from revision one: a `yin/def` variable occurrence is legal only as the operator of a two-operand application with a literal-symbol key other than `yin/def`; the name is not a binder, not a store key, not a definition or macro key; quoted data is allowed.

Enforcement, now in three tiers:

- **Proof tier, transition time.** `resolve-var` refuses the name before env lookup. The definition transition in each engine, and the `:define` opcodes, never resolve the operator. `engine/store-put` refuses the key. These three are the completeness condition.
- **Early-refusal tier, load time.** `vm/validate-rows` and `semantic-bytecode->ast` (occurrence-aware, whole tree), `code/well-formed?` and `code/well-formed-vector?`, `image-defect` and `register-image-defect`, and the four linker records that wrap them. Stack `load-image` starts calling `image-defect`, a pre-existing defect fixed here.
- **Ingress tier, construction time.** Each `create-vm` refuses a supplied store, env, or registry binding the name; the expander's `make-ctx` and `expand-batch` refuse a store binding it; harvest refuses the key; the ready-queue merge asserts non-symbol keys; the M4 module-store lower goes through `engine/store-put`.

Completeness condition, stated once: **a program is sound with respect to definitions if every name it resolves passes through `resolve-var` and every store write passes through `engine/store-put`.** Both are true of the tree by inspection today for resolution, and by the lint after the four direct writes are rerouted.

## 3. Old-image refusal

Stamps are required wherever persistent code is admitted. The runtime backstop is the resolver: an old image with a definition throws `:reserved-name` at its first `:var yin/def`, so a mis-stamped image cannot shadow, only fail.

- **Linker fetch.** An omitted contract becomes `:invalid-request`; a differing one stays `:contract-mismatch`. Callers pass the record's contract explicitly.
- **Manifests and derivations.** `:yin.module/contracts` and the ledger profile move to "v3", "v3", "b2", "r2"; a manifest naming an old revision is refused at the existing 8.1 check.
- **Direct loaders.** The walker's datom and row loaders, the semantic datom and vector loaders, and the stack and register `load-image` each take a required `:contract` and refuse `:contract-missing` or `:contract-mismatch` before validation. A datom batch carries it on the segment entity beside the existing hash claim.
- **Fresh source.** Yang, the linearizers, and the expander stamp their output with the current constant in `yin.vm`. A loader call without a stamp is refused, not defaulted, so fresh source is an explicit producer-stamps path, distinct from any claim about old images.
- **Old images with no definition.** Refused by stamp. Their bytes may be interpretable, but a revision is a property of interpretation, and refusing is cheaper than proving harmlessness per image.
- **Continuations.** Stamp comparison at lower is M4, since lower does not exist.

## 4. The three-change split: ACCEPT

**Change A, compatible core.** `resolve-var` reads reserved names from the registry only, skipping env and store; `engine/store-put` introduced and used by all five write sites, refusing the key; constructor, expander-context, and harvest refusals; the ready-queue assertion; the lint. `yin/def` stays in the registry so definitions still work. One caveat held on evidence: strictly, UCF 7.3.3 calls a resolution-order change a new revision. The only programs that observe change A are ones that exercised a shadow route, which had no defined meaning. I recommend recording A as an erratum to the current revisions; if codex holds to the strict reading, A folds into B and the split is two. Gate: tri-host lanes; tests per host: the shadow, store-put, computed-key, and alias fixtures all leave a later definition intact; a supplied store or env with the name is refused; a seeded macro named `yin/def` is refused at context construction; the lint passes.

**Change B, atomic cutover.** Definition transition in all four engines, `:define` opcodes, `yin/def` removed from the registry, `resolve-var` refusing unconditionally before env, the load-time validators, required loader contracts, stamps to "v3", "v3", "b2", "r2" in `yin.vm`, ledger, and UCF constant, producer stamping, stack loader validation, `free-names` excluding the operator, all docs. Gate: tri-host lanes and the parity harness. Tests per host: bare `yin/def` in value position, as parameter, as key, computed key, wrong arity, `(yin/def 'yin/def _)` each refused at load on every loader including the semantic datom loader and the raw walker path; the same programs on all four backends produce identical stores; a lowered image with `:load-free yin/def` is refused; an old-stamped image with no definition is `:contract-mismatch`; an unstamped load is `:contract-missing`; a parked read inside a definition's value operand resumes in-process and writes the store, on the walker and semantic VM; redefining `x` twice reads the second value.

**Change C, M2.** The four format records take the new contract names and the fetch requires one; the round 3 and 4 filters and `:yin-def?` bookkeeping go; the vector definitions scanner reads `:define`; obligations exclude the operator; the three fixtures and the alias fixture refuse `:reserved-name`; `(yin/def 'x 'yin/def)` links. Gate: the existing M2 gate, run once.

**Moved to M4.** UCF frame encoding of a define frame, stamp refusal at lower, module-store snapshot lowering through `engine/store-put`, and the round-trip test.

## 5. Docs and defects

Docs, in addition to revision one's list: yin.vm.engine.md for the resolver rule and the single write function; yin.vm.semantic.md 2.6 for datom-side reserved operands and `:define`; code-as-tuples 7.5 for the vector rules; debruijn.stack.md for loader validation; linker.md 6.3 and 6.4 for the required contract; yin-repl-design for producer stamping; the UCF revisions log for the erratum and the four new revisions; datom.world.md gains one line under design principles naming the store-write lint.

Defects, pre-existing and surfaced here: the stack loader admits without validation; no loader compares a contract; the M2 fetch admits an omitted contract. Deferred, unchanged: store-update discovery, advisory exports, host effects with computed keys under incomplete discovery, and everything listed under M4.
