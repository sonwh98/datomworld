Completed-GMT: 2026-10-15 06:56:41 GMT
Completed-Local: 2026-10-15 13:56:41 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: ebcb694d-e093-4672-8de5-bbb9cafbef8e

# 8e Architect ruling: the NaN row-collision stop, and review of the uncommitted 8e diff

Worktree /Users/sto/workspace/datomworld-srvm-p8e, branch srvm-phase8e, codex's uncommitted diff (50 paths: 41 modified, 9 deleted). Read-only: nothing edited but this report.

Verdict in one line: **route (a)**. Fix the collision guard in `yin.vm/ast->semantic-bytecode`, nothing else in addressing moves. The diff itself is sound and in scope; it needs the NaN fix, one dead helper removed, and its final gates run. **CHANGES-REQUIRED**, not BLOCKED.

---

## PART 1 — Ruling: fix the collision comparison (route a)

### Facts the ruling rests on (all verified in this worktree)

1. **Every encoder below the guard already identifies all NaNs.**
   * `dao.jing.cbor/float-wire` (src/cljc/dao/jing/cbor.cljc:990-1011) writes `7ff8000000000000` for every NaN on JVM, Node and Dart. `jing/segment-key` hashes those bytes, so the row id is NaN-payload-blind and host-stable by construction. The repro log shows exactly that: both rows carry the same id.
   * `ucf/code-address` is `jing/segment-key` (src/cljc/yin/vm/ucf.cljc:247-251), so A is likewise blind to NaN payload and sign.
   * `yin.vm.debruijn-code/double-le-hex` (src/cljc/yin/vm/debruijn_code.cljc:232-250), which feeds H and R, maps every NaN to one quiet NaN via `(not= v v)`.
   Consequence: the NaN row's address is *already* stable across hosts, and the NaN canonicalisation that applies is the one the whole system already uses: one quiet NaN, `7ff8000000000000`, sign and payload discarded. The fix changes no id, no A, no H, no R, no golden.

2. **The guard is the only NaN-hostile point.** src/cljc/yin/vm.cljc:1214-1217 re-checks `(= prior row)` with host `=`, and `=` is not reflexive for NaN on any of the three hosts. The guard's documented purpose (docstring, lines 1182-1188) is to catch what the encoder does *not* hash: scalar metadata. Same id already means same canonical bytes; the `=` precheck is not load-bearing beyond that.

3. **Route (b) would hide a production defect.** The row lane is the production path: `yin.vm.linker` (src/cljc/yin/vm/linker.cljc:2131,2134), `yin.vm.linker.publish` (:50, :157, :253), `yin.vm.encoder`, `yin.repl` (:156) and `yin.vm.macro` all go through `ast->semantic-bytecode`. Today any program with two NaN literals cannot be published or linked. Switching the ten C4 builders to `project-datoms` would make the tests green while leaving publish broken.

### Invariants

* **A is the only code identity.** Identity is decided by the canonical encoding, not by host `=`. Two NaN literals are one row because their canonical bytes are one; the guard must agree with the encoder, not overrule it.
* **Tree always available.** Untouched: the row set still carries the literal; nothing is lowered from A alone.
* **Derive, don't persist.** No new row structure, no NaN side table. Keep the first-seen row; both candidates are NaN and every encoder in the system makes them the same bytes.
* **Minimal diff.** One predicate changes; the rest of the guard, its throw and its ex-data stay.

### Fix spec (codex implements; file: src/cljc/yin/vm.cljc only)

1. Rename `same-meta?` to `same-content?` (private) and make it the whole content check, not a metadata supplement:
   * **Leaf rule:** `(or (= a b) (and (not= a a) (not= b b)))`. NaN is the only value that is not `=` to itself on JVM, Node and Dart, so this is exactly "equal, or both NaN". Precedent: debruijn_code.cljc:241. Do not use `Double/isNaN` or host conditionals; do not use unary minus anywhere (ClojureDart trap).
   * **Counts:** maps, sets and sequentials must have equal `count`; the map branch checks `(contains? b k)` before `find`. (Today a shorter `b` could pass the `every?` over `a`; the dropped `=` precheck used to cover that.)
   * **Metadata rule:** unchanged (`=` metadata at every depth, recursing through metadata values).
   * **Scope boundary to document:** a NaN as a map key or set element inside a `data` payload is out of scope and fails closed (lookup misses, the guard throws), alongside the existing comparator-set note.
2. The guard becomes `(when (and prior (not (same-content? prior row))) (throw ...))`. Keep the message `"Semantic bytecode address collision"` and the `{:id :row :prior}` ex-data.
3. Docstring of `ast->semantic-bytecode`: add one sentence to the known-limitation paragraph: the encoder makes every NaN one quiet NaN, so two NaN scalars are one row value and the content check is NaN-reflexive. No design-doc edit: no doc states the collision rule (grep over docs/design finds none), and docs are frozen for 8e anyway. vm.cljc is not on 8e's forbidden list (forbidden: yin.vm.semantic, linearize, code, docs, src/yang).

### Tests (codex adds to test/yin/vm_test.cljc, beside `semantic-bytecode-structural-sharing`; this file compiles for all three lanes)

* **T1 two separately produced NaN literals mint one row.** Produce two distinct NaN values arithmetically so they are separate boxes on the JVM: `(* 1.0 ##NaN)` and `(* -1.0 ##NaN)` (multiplication, never unary minus). AST: `{:type :application :operator {:type :variable :name 'vector} :operands [{:type :literal :value a} {:type :literal :value b}]}`. Assert: no throw; exactly one `:literal` row in `(:rows bc)`; its id is `:segment/blake3-88491ea0d131d67ecf50c6a1714868257c996f9518a139262e049ebd428e55b7` (the value from nan-repro.log, pinned so the cross-host and payload-blind claims are checked on every lane); the stored value slot satisfies `(not= v v)`.
* **T2 nested payload.** Two literal nodes whose `:value` is `[a 1]` and `[b 1]` with `a`,`b` the NaNs above: one row, no throw. Exercises the recursive leaf.
* **T3 regression for the guard.** Literal values `^{:meaning 1} x` and `^{:meaning 2} x` still throw `"Semantic bytecode address collision"`. No test pins this today (grep over test/ finds none); add it so the NaN change cannot silently widen into "never throw".
* **T4 cite, do not add:** `yang.python.antlr.float-address-test` (signed zero kept, 1 vs 1.0 distinct) must stay green; those values mint distinct ids and never reach the guard.
* **T5 acceptance:** `clojure -M:test -n yang.python.antlr.int-ops-test` green including `nan-comparisons-test` and `float-division-edge-test` on `:stack` and `:register`.

### Gates before sign-off (foreground, sequential)

1. `clj -M:kondo --lint src/cljc/yin/vm.cljc test/yin/vm_test.cljc` plus the changed files: 0 errors, 0 warnings.
2. `clojure -M:test -n yin.vm-test` and T5.
3. The report's full 35-namespace `-n ... -e :slow` command, final run after all fixes (the last one in the report still shows the 6 binder-path failures that were fixed afterwards).
4. The whole `yin.vm.*` quick tier, run where the loopback bind is allowed (the one `head-board-test` error was a sandbox denial, not a defect).
5. Node and Dart lanes on `yin.vm-test` and the debruijn/derive namespaces: orchestrator. The pinned id in T1 is the cross-host claim; it is not proven until the Dart and Node lanes run it.

---

## PART 2 — Review of the uncommitted 8e diff

### Scope audit: clean

* Under test/yang only the ten authorised C4 files changed (git status). Nothing under src/yang or docs/. `src/cljc/yin/vm/{semantic,linearize,code}.cljc` untouched (diff stat). No staging, no commit, no golden regenerated, no rename.
* Source edits are prose only: register.cljc and stack.cljc eval-refusal messages, debruijn_register_code.cljc comments, and the removal of `derive/resolved-addresses` (the old-tuple diagnostic reader). No backend widened.
* No live reference to the deleted namespaces remains in src/ or test/ (rg). The only hits are a historical comment in debruijn_register_code_test.cljc:155 and the forbidden-namespace regex in production_pipeline_test.clj:12, which is a guard, not a dependency.

### Ledger: complete

The six deleted files held 11+3+37+9+14+10 = 84 deftests at HEAD, matching the 84-row ledger one for one. Spot checks of the "fact lives here now" column against the diff hold (normalizer and B0 fixtures in debruijn_code_test; R0 contract, address law, validator mutations, resume CFG and body-local liveness in debruijn_register_code_test; corpus validation, determinism, goldens, liveness and the benchmark values in derive_test; `native-corpus-covers-opcodes` replaces corpus-reaches-every-named-mnemonic exactly). The retirements (resolved-tuple validators, H/R-to-named lifts, benchmark timing table) are the right calls: they test a representation that no longer exists and the lifts were an inverse A makes unnecessary. The old-vs-new `(is (= old new))` legs in derive_test necessarily go with the old pipeline; their value checks are kept.

### Findings

**P1**

* **P1-1 NaN collision in the row lane** (src/cljc/yin/vm.cljc:1214). Four C4 failures on the native backends; a production defect in publish/link, not a test-harness choice. Fix per Part 1. Blocking.
* **P1-2 Final gates not run.** The last broad and changed-namespace runs predate the binder-path and register-destination fixes; the report says so truthfully. Rerun per the gate list above. Blocking by process, not by defect.

**P2**

* **P2-1 `tu/resolved-of` is dead** (test/yin/vm/test_utils.cljc:200-211). No caller remains (rg); its docstring claims a sharing purpose nobody uses, and it drags a `last-resolved` atom along. Delete the function and the atom. Required: small, and it is a misleading API otherwise.
* **P2-2 Dropped descriptor facts are a doc gap, not a code gap.** `source=resolved-tuples` and `lift=to-named-semantics` were rows of the *test's* data table only; the real descriptor (src/cljc/yin/vm/debruijn_register_code.cljc:192-205) never carried them, so `golden-descriptor-hash-test` is untouched and there is no src/test mismatch. But docs/design/yin.vm.debruijn.register.md §2, §2.1 still define the register image as derived from resolved tuples. Docs are frozen for 8e; this goes to docs/agents/known-gaps.md for the docs slice (orchestrator records it; consistent with the owner ruling that the native kernels are backends and their docs need amending).
* **P2-3 Native binder refusal now collapses to one shape** (test/yin/vm/host_typed_values_test.cljc:242-247). The stack and register legs assert the projection's `{:rule :slot-kind :path [1 1]}` for every bad binder; the kernel-side `:non-symbol-parameter`/`kind` defence is no longer exercised on native input. Acceptable because the projection is the sole native entry and the walker/semantic legs keep the kind check. Record in known-gaps as reduced defence-in-depth coverage; no code change required.

**P3**

* **P3-1 Require blocks.** In roughly 25 files the two new requires were inserted first, before `clojure.test`, with misaligned indentation, and a blank line was left where `dl`/`rc` used to be (e.g. cell_test.cljc:7-13, stream_poll_test.cljc:7-13). Cosmetic; tidy while touching files for P1-1, optional otherwise.
* **P3-2 Stale prose** in debruijn_register_code_test.cljc:140-145 ("once a register lowerer exists; ... none exists yet") and :155 (names the deleted test namespaces). Rewrite in two lines; optional.
* **P3-3 Missing trailing newline** in debruijn_register_code_test.cljc. Optional.
* **P3-4 Double projection** in test/yang/python/antlr/e2e_test.clj:191: `projected` is a thunk invoked once for stack and once for register, so every program projects twice where `resolved-of` used to cache. Slow-tier cost only; bind the vector once with `let` if the lane time matters. Optional.
* **P3-5 Two blank lines** left in linker_test.cljc where the lift assertions were removed (around :1995). Optional.
* **P3-6** production_pipeline_test.clj:12 regex still names three namespaces that no longer exist. Harmless guard; may be trimmed later. Optional.

### What I checked and found correct

* e2e_c2_test's runner map changed arity usage from `(fn [_ datoms])` to `(fn [ast _])`; both call sites (:1174, :1239) pass `ast` and datoms, so the change is safe.
* debruijn_register_effects_test selects the non-tail call by non-empty live set instead of the old allocator's `dest = 3`, and checks `:dest`, `:live` and `:regs` against the chosen instruction and the test's own register file. Real checks, not tautologies; every defect mutation is still present.
* remote_transfer_test and linker_test execute fetched H and R directly on their kernels in place of the deleted lifts. This is the correct consequence of "A is the only code identity": the native images are checksummed backends, not a second route back to named semantics.
* `resolution-preserves-scalars-and-contexts` hard-codes the expected address sequences for the two shared-context fixtures; those are the exact facts the deleted B2 shared-occurrence tests pinned.

---

## Decision summary for codex

Required before resubmission: P1-1 fix and tests T1-T3 as specified; P2-1 deletion; gates 1-4. Optional: P3-1..P3-6. Orchestrator: run gate 5 (Node, Dart) and record P2-2 and P2-3 in docs/agents/known-gaps.md.

CHANGES-REQUIRED
