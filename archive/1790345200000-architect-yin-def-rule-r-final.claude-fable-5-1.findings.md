Completed-GMT: 2026-09-25 14:52:46 GMT
Completed-Local: 2026-09-25 21:52:46 Asia/Ho_Chi_Minh

Consensus confirmed. Codex granted sign-off on the design, with one binding ruling and three non-blocking corrections. I verified each against the tree and accept all four. The consolidated design follows; it stands alone.

## Rulings on codex's final points

**Fold Change A into B: ACCEPT.** UCF section 7.3.3 puts resolver precedence inside the stamped contract and says a changed resolution order is a new revision (UCF 262 to 274). A resolver that skips env and store for one name is a precedence change, so it cannot ship as an erratum. Codex's second reason also holds: while `yin/def` stays a registry entry, `empty-state` accepts a composition-supplied registry (vm.cljc:1623), so registry-only lookup still does not fix its meaning. Two commits.

**P2, store-write lint: ACCEPT.** My "no assoc into the store outside the engine" rule was wrong as a proof. The tree has eleven store-write sites: the dispatcher (engine.cljc:512), the ready-queue merge (engine.cljc:425), four direct instructions (walker 419 to 438, semantic.cljc:303, stack.cljc:486, register.cljc:486), two constructor merges (stack.cljc:149, register.cljc:164), dao.await (await.cljc:222), the REPL's history keys (repl.cljc:600), and the handoff demo (continuation_handoff.cljc:208). The rule becomes an exact allowlist audit, stated in the design below.

**P2, producers: ACCEPT.** The walker's own `vm/eval` builds datoms from a map AST and loads them (ast_walker.cljc:765 to 775), and the expander's transformer runner builds a row set and loads it (macro.cljc:723 to 732). Both are trusted fresh-code producers and must pass the current contract explicitly.

**P2, raw control: ACCEPT.** A walker stepped through `cesk-transition` with supplied control has no load event (ast_walker.cljc:688), so its refusals are transition-time by definition, including a reserved store-get or store-update key.

**Correction to the proof wording: ACCEPT.** `unsatisfied-names` takes a supplied callback (ucf.cljc:387 to 395); it is a static check, not a resolver caller. The proof covers every executing VM variable lookup.

**Walker datom loader gap: ACCEPT.** `datoms->ast` reconstructs without whole-tree validation (vm.cljc:646), so the walker's datom loader is a second unvalidated admission path beside the stack loader.

Nothing material remains in dispute between codex and me.

## Final design: yin/def is syntax, never a name

**Diagnosis.** The front end already treats `def` as a special form, but lowers it to an ordinary call of a primitive named `yin/def`, and every engine resolves that name through env, then store, then primitives, so a parameter, a store write, a computed key, or a passed-around primitive value can change what a later definition does. The M2 linker recognizes definitions by that syntactic shape, so the shadowing makes its discharge of use-before-definition obligations unsound, and four gate rounds each found a new route.

**Rule R, by syntactic role.**

- A `:variable` naming `yin/def` is legal only as the operator of a two-operand application whose first operand is a literal symbol other than `yin/def`. Every other variable occurrence is refused.
- `yin/def` is not a binder: not a lambda parameter, not a supplied env key.
- `yin/def` is not a store key: not in a store-put, store-get, or store-update node or instruction, not in a store-put effect, not in a supplied or restored store.
- `yin/def` is not a definition key or a macro name.
- A literal whose value is the symbol `yin/def` is ordinary data and is allowed anywhere.
- `yin/def` is not a primitive and has no profile. A registry containing it is refused.
- Redefining any other name stays allowed.

**Scope.** `yin/def` only. `require` is dynamic and not statically interpreted, so shadowing it is not a soundness hole. The reserved set is a one-entry contract table.

**The two-function proof.** Every executing VM variable lookup goes through `engine/resolve-var`: the walker at two sites, the semantic, stack, and register VMs at one each. If that function refuses `yin/def` before it consults env or store, and the definition transition in each engine never resolves its operator, then no loader, supplied env, store entry, scheduler merge, or restored snapshot can redirect a definition. Completeness condition: **a program is sound with respect to definitions when every executing variable lookup passes through `resolve-var` and every valid definition form writes its literal key without resolving its operator.** That condition holds by inspection of the four engines today for the first half and by the transition change for the second. Load-time validation and the store-key invariant are additional requirements, not the proof.

**Load-time refusal, still required.** Whole-tree, occurrence-aware checks in `validate-rows` and `semantic-bytecode->ast`; reserved-operand and `:define` rules in `code/well-formed?` and `code/well-formed-vector?`; the stack and register image validators; the walker's datom loader and the stack loader start validating; the four linker format records wrap these. Raw control stepped directly refuses at transition time.

**Store-key invariant, separate and audited.** Program writes, meaning the dispatcher and the four direct instructions, route through one `engine/store-put` that refuses the reserved key. The remaining sites are state construction and are an exact allowlist: the ready-queue merge (asserted to carry only engine-minted keyword keys), the two constructor merges (checked), dao.await (minted keyword keys, checked), the REPL history keys (fixed symbols, not reserved), the handoff demo, and the M4 module-store lower. The gate greps for store writes and fails on any site outside the allowlist.

**Expander.** Context construction and batch expansion refuse a store binding the name; harvest refuses the key. The transformer runner is a walker and inherits the transition checks.

**Contract stamps.** AST and semantic move to "v3", stack to "b2", register to "r2". Every persistent-code admission requires a stamp and compares it: the linker fetch refuses an omitted contract as `:invalid-request`; manifests and derivation records carry the new names; the walker's datom and row loaders, the semantic datom and vector loaders, and the stack and register loaders take a required contract and refuse `:contract-missing` or `:contract-mismatch` before validation. An old-stamped image with no definition fails by stamp, not by grammar. Fresh code is a separate, explicit path: yang, the linearizers, the expander, `vm/eval`, and the transformer runner supply the current constant from `yin.vm` themselves. Nothing ever assigns a stamp to externally supplied datoms or rows.

**Commit one, atomic Rule R.** Scope: resolver refusal before env; definition transition in all four engines; `:define` opcodes (semantic on the value register, stack popping the value, register with destination and source); `yin/def` removed from the registry and profiles; `engine/store-put` and the allowlist audit; every validator and loader above; required stamps and producer stamping; `free-names` excluding the definition operator; expander refusals; constructor checks; the docs below. Gate: JVM, Node, and Dart lanes, the parity harness, kondo, cljstyle, the store-write audit. Tests per host: each reserved role refused at load on every loader, including the semantic datom loader and the walker datom loader; the same refused at transition on raw control, including store-get and store-update keys; a supplied store, env, or registry binding the name refused at construction; a seeded macro named `yin/def` refused at context construction; identical stores across all four backends for the same definition programs; a lowered image with the old call shape refused; an old-stamped image with no definition refused by stamp; an unstamped load refused; a `vm/eval` and a transformer run succeed under the current stamp; a parked read inside a definition's value operand resumes in-process and writes the store on the walker and semantic VM; redefining `x` twice reads the second value.

**Commit two, M2.** Scope: format records take the new contract names and the fetch requires one; the round 3 and 4 shadow filters and the `:yin-def?` bookkeeping are deleted; constant-key recognition, invocation position, dominance, and the earlier bound fixes stay; the vector definitions scanner reads `:define`; obligations exclude the operator; the rebinding, direct store-put, computed-key, and alias fixtures refuse `:reserved-name`; a definition whose value is the quoted symbol links. Gate: the existing M2 gate, run once.

**M4 bucket, explicit obligations not passing today.** UCF frame encoding of a define frame; stamp comparison at continuation lowering; module-store snapshot lowering through `engine/store-put`; the lift and lower round trip.

**Docs.** UCF 7.3.3, 7.5.2, 7.6.1, 7.11 and the revisions log; linker.md 4.1, 4.2, section 5 records, 6.3, 6.4, 8.1, section 11; yin.vm.semantic.md 2.4, 2.6, 4.2; code-as-tuples 4.5, 7.5, and the "free at that site" clause near line 1831; yin.vm.macro.md 2.2, 4.1, 4.2; debruijn.stack.md loader and instruction sections; debruijn.register.md 4.4; yin.vm.engine.md; yin-repl-design; datom.world.md, one line naming the store-write audit.

**Defects versus deferred.** Defects, pre-existing: the stack loader admits without validation; the walker datom loader admits without whole-tree validation; no loader compares a contract; the M2 fetch admits an omitted contract. Deferred, admissible: store-update discovery, advisory export verification, host effects with computed keys under incomplete discovery, and the M4 bucket.

**Residual risks.** A foreign engine that claims the new contract but resolves names outside the rule is caught only by the conformance corpus, not by construction. The producer-stamps rule depends on discipline in trusted code; the gate tests it but cannot prove it for future producers. Bumping four contracts invalidates every stored image and manifest; this repo has none deployed.

**Owner decisions still open.**

1. Whether `require` joins the reserved set now or waits until it gains static discovery. The design says wait.
2. Whether the four contract bumps may invalidate every existing stored image and manifest without a migration path. The design assumes yes, per the no-backward-compatibility rule.
3. Whether M2 stays uncommitted until commit one lands, which is what the two-commit order requires.
