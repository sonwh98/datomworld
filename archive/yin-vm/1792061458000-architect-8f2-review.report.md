Completed-GMT: 2026-10-15 10:50:57 GMT
Completed-Local: 2026-10-15 17:50:58 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: not exposed to this session (see the harness stdout log beside the prompt)
# 8f-2 Architect review: uncommitted rename diff + ruling on the frozen old-name keywords

Reviewed: worktree `datomworld-srvm-p8f2`, branch `srvm-phase8f2` at `5804a1dc`, working tree uncommitted. Input: `collab/1792061140107-engineer-8f2.report.md` (codex). Read-only except this report; two scratch verifier files (a throwaway JVM test namespace and a perl script under collab) were created to get around the sandbox and deleted again; `git status` shows no residue.

## Independent verification

**(1) Moves are byte-identical renames; nothing else changed.** CONFIRMED. I compared every one of the 24 deleted HEAD files against its new path and every one of the 64 modified tracked files against HEAD, after applying exactly three substitutions to the HEAD side: `yin.vm.semantic-register` (not followed by `-vm`) to `yin.vm.semantic`, `yin/vm/semantic_register` to `yin/vm/semantic`, `semantic_register.cljc` to `semantic.cljc`. Result: 24 moves identical, 63 of 64 edits identical. The single residual is `src/cljc/yin/vm.cljc:978`, a docstring where the alias-style API reference `semantic-register/load-ast` / `semantic-register/load-vector` became `semantic/load-ast` / `semantic/load-vector`. That is the correct new spelling of the same API reference and is covered by the brief's "API references" clause. No logic, whitespace or pinned literal changed anywhere.

**(2) Zero live references.** CONFIRMED. `semantic[-_]register` over `src`, `test`, `bb.edn`, `deps.edn`, `shadow-cljs.edn`, `bin`, `src/dev`, `.clj-kondo`, `.github`, `package.json`, excluding the `yin.vm.semantic-register-vm*` design-doc citations, returns exactly the 74 lines the engineer tabulated: model/backend/lane keywords, prose, two deftest names, and the `semantic-registers` register-file function. Not one namespace symbol, require vector, path string or `ns-publics` lookup remains. `docs/agents/build-n-test.md:438` is prose only (no lane command names the old namespace). The only other non-doc hits are dated transcripts under `archive/`.

**(3) No hashed or pinned identity moved.** CONFIRMED. Because (1) proves the diff is substitution-only, the only way a hash could move is if a hash input contained the namespace string. The two descriptor pins (`7b7c6ae9…f3489` in `debruijn_code_test.cljc:362`, `aa757659…7e74c` in `debruijn_register_code_test.cljc:11`) are untouched and the engineer's whole JVM quick tier (4005 tests, 249867 assertions, 0 failures) passed against them, which is the executable proof. The format tag stays `:yin.semantic/code`; no golden H pair or A address literal is in the diff. The `:vm/model` telemetry value is never pinned by literal: `telemetry_test.cljc:216` asserts `[model]` symbolically from the table key.

**(4) The ten C4 files are the only test/yang edits, three substitutions each.** CONFIRMED. `git diff --numstat HEAD -- test/yang` lists exactly the ten permitted files, `3 3` each, and each passed the normalization compare in (1). No `src/yang` file changed.

Lanes: JVM quick tier and the ten C4 namespaces were run by the engineer (reported green). Node and Dart remain with the orchestrator per the brief; this is a pure rename so I do not require them before commit, but the landing rule (three hosts per slice) still applies to the 8f merge.

## Ruling: the 44 "frozen model identity and test backend/lane keyword" lines

**Decision: rename them to `:semantic` now, as a second commit inside 8f-2 (call it 8f-2b), not in 8g and not left.** Hash-free, store-free, mechanical. Reasons:

1. **`:semantic` is the documented model keyword, and it is free.** The old kernel deleted in 8f-3 was the one that emitted `:vm-model :semantic` and called `(telemetry/install :semantic)` (verified at `e1f6a580:src/cljc/yin/vm/semantic.cljc:936,949`). `docs/design/yin.vm.telemetry.implementation-plan.md:85,271-272` prescribes the two models as `:ast-walker` and `:semantic`. `:semantic-register` was a transitional name chosen only so the two kernels could coexist. The coexistence ended at 8f-3; the phase-8 plan's own stated goal is that the register kernel *is* the semantic kernel. Keeping `:semantic-register` in the kernel is the keyword-level equivalent of the compat shim the plan (Q3, Q6) rules out.
2. **`:vm/model` is persisted.** `telemetry.cljc:258-268` writes the model keyword into every root snapshot datom on the telemetry stream. A transitional name leaking into durable datoms after the rename is exactly the "legacy as a shim" argument. Dev-only repo, no deployed stores (owner rule), so there is nothing to migrate: the cost is zero today and non-zero later.
3. **The engineer's collision argument is inverted.** The report preserved `:semantic-register` "to avoid colliding with the separate existing `:semantic` backend in `rule_r_test`". That `:semantic` backend is not separate any more: since 8f-3 it builds the same kernel via `semantic/create-vm` + `semantic/load-ast` (`rule_r_test.cljc:96-100`). The same duplication exists in `continuation_invoke_test.cljc` (`:semantic-register` at 33, `:semantic` at 42), `ffi_test.cljc` (382 and 385) and `linker_require_test.cljc` (120 and 165). In `linker_require_test` the `:semantic-register` entry is additionally a stale phase-5 stub (`:obligations (constantly [])`, excluded at line 611 "until phase 5c supplies the register-vector scanners", which landed long ago) while the `:semantic` entry runs the real `linker/semantic-format` obligations. So the correct move in those four tables is to **delete the `:semantic-register` entry**, not rename it: the register kernel is already covered under `:semantic`, and C4 already uses `:semantic` for it (`e2e_c2_test.clj:39,1147`, `int_ops_test.cljc:46`, …). Coverage note: `continuation_invoke_test`'s `:semantic-register` runner stepped the machine with an explicit bounded `vm/step` loop; dropping it is acceptable because `test/yin/vm/semantic/vm_test.cljc` exercises `vm/step` on the kernel directly (12 call sites).
4. **Lane labels are test-local.** `safepoint_harness_test.cljc` derives the lane from the module format tag (`lane-of`, line 130) and the `:lane` key on roots is built by the harness itself (line 1592); `src/` has no `:lane` keyword outside `src/dev/affected.clj`, which is unrelated. Neither file uses a bare `:semantic` keyword, so a global replace is collision-free. The design doc that names the lanes (`yin.vm.semantic-register-vm.continuation.md` §5.3 line 1115, §8.5) is 8g's; 8g must update that lane name alongside the doc rename.

Why not 8g: 8g is the documentation slice and its acceptance grep is `yin.vm.semantic-register` (the namespace), which would never catch a keyword. Why not leave: the kernel would ship under one name in `ns` and another in its own telemetry forever.

### Exact edit list for codex (8f-2b; no hash, golden or `docs/design` edit)

Production:
- `src/cljc/yin/vm/semantic.cljc:388` `:vm-model :semantic-register` to `:vm-model :semantic`.
- `src/cljc/yin/vm/semantic.cljc:402` `(telemetry/install :semantic-register)` to `(telemetry/install :semantic)`.

Telemetry test (no collision):
- `test/yin/vm/telemetry_test.cljc:4` docstring `` `:semantic-register` `` to `` `:semantic` ``.
- `test/yin/vm/telemetry_test.cljc:132` table key `:semantic-register` to `:semantic`.

Backend tables (delete the duplicate; keep the existing `:semantic` entry untouched):
- `test/yin/vm/rule_r_test.cljc:123-125` delete the `:semantic-register` entry; line 207 `(select-keys backends [:ast-walker :semantic :semantic-register])` to `(select-keys backends [:ast-walker :semantic])`.
- `test/yin/vm/continuation_invoke_test.cljc:33-40` delete the `:semantic-register` runner entry (the `:semantic` runner at 42 stays).
- `test/yin/vm/ffi_test.cljc:382-383` delete the `:semantic-register` vm-kind entry (the `:semantic` entry at 385 stays).
- `test/yin/vm/linker_require_test.cljc:120-126` delete the `:semantic-register` backend entry including its two stub comments; line 611 `(doseq [backend (remove #{:semantic-register} (keys backends))]` to `(doseq [backend (keys backends)]` and drop the stale "excluded until phase 5c" comment on 610.

Lane labels (global replace `:semantic-register` to `:semantic`, both files verified collision-free):
- `test/yin/vm/semantic/safepoint_harness_test.cljc` lines 2, 97, 100, 103, 126, 136, 175, 183, 1456, 1458, 1464, 1465, 1604, 1747, 1936 (15 lines; line 1604's expected vector becomes `[:semantic :stack :register]`).
- `test/yin/vm/semantic/layout_test.cljc` lines 123, 125, 150, 300, 302, 313, 381, 419, 512, 546, 568, 576, 578, 583, 646, 660, 664, 672, 689 (19 lines).

Test names (P3, same commit since the files are open anyway):
- `test/yin/vm/linker_test.cljc:2043` `semantic-register-scanners-yield-position-bearing-records` to `semantic-scanners-yield-position-bearing-records`; `:2056` `semantic-register-format-refuses-a-malformed-vector` to `semantic-format-refuses-a-malformed-vector`.

Verification for 8f-2b: `clojure -M:test -e :slow -n yin.vm.telemetry-test -n yin.vm.rule-r-test -n yin.vm.continuation-invoke-test -n yin.vm.ffi-test -n yin.vm.linker-require-test -n yin.vm.linker-test -n yin.vm.semantic.safepoint-harness-test -n yin.vm.semantic.layout-test`, then the whole JVM quick tier, then `git grep -n ':semantic-register' -- src test` must return nothing. Pinned descriptor hashes must still read `7b7c6ae9…f3489` and `aa757659…7e74c`.

Not part of this ruling: `continuation_handoff.cljc:178,207` `semantic-registers` (the register file, correct as is); `vm-key->model` there returns `:ast-walker` and is unaffected.

## Other leftover names that will confuse after the rename

- **P2, for 8g's scope statement.** Nine code docstrings cite `docs/design/yin.vm.semantic-register-vm*.md` by file name (`semantic/analysis.cljc:3`, `semantic/code.cljc:3`, `semantic/linearize.cljc:3`, `semantic/layout.cljc:2`, `ucf/rows.cljc:233`, `test/yin/vm/semantic/corpus.cljc:2`, `layout_test.cljc:2`, `safepoint_harness_test.cljc:3`, `ucf/rows_test.cljc:359`). When 8g renames the docs (Q6 option c) these become dangling, so 8g must be allowed to touch these src/test lines even though it is "docs only". Record that in the 8g brief.
- **P3, prose pass (8g or 8f-2b, codex's choice).** 17 src lines and 6 test lines still say "semantic-register machine/kernel/evaluator/VM" in prose (`semantic/layout.cljc:18,28,716,760,781,789,954`; `ucf/rows.cljc:4,1321,1736,2245`; `stack_parity_test.cljc:6,120`; `safepoint_harness_test.cljc:1716`; `rows_support.cljc:3`; `rows_checkpoint_fixtures.cljc:7`; `ucf/holder/export_test.cljc:5`). Replace with "semantic machine/kernel". Also `semantic/layout.cljc:4,20` say "register machine" / "the register kernel" meaning this kernel (not the de Bruijn register backend), which is now genuinely ambiguous; say "the semantic kernel".
- **P3, record name.** `semantic.cljc:16` `defrecord RegisterVM` with the diagnostic "The register VM executes loaded vectors; use load-ast" (line 335, no test pins the message). `SemanticVM` was the deleted record's name and is now free. Rename in the prose pass; it is a symbol, not an identity (no `pr-str` of the record is persisted: UCF rows carry fields, not the record).
- **Fine as is.** Test aliases `sr` / `sr-linearize` (plan Q3 allows); `yin.vm/register-contract`, `derive/register-image`, `rvm` (these really are the de Bruijn register backend); `register_bench_cljd.cljd`; `docs/agents/build-n-test.md:438` prose (8g).

## Findings

- P1: none.
- P2: the four duplicate backend-table entries (ruling above) and the nine dangling doc citations 8g must own. Neither blocks this commit.
- P3: prose and `RegisterVM` leftovers listed above; two deftest names.

## Verdict

The diff on disk is exactly the specified rename, with zero live old-name references, no pinned identity moved, and the C4 freeze respected. Commit it as the first 8f-2 commit (plan Q3 commit 1 + 2 folded; reviewers can diff with `--find-renames`). Dispatch 8f-2b to codex with the edit list above as the second commit; it needs its own Architect look (small) before the 8f-2 branch merges.

SIGN-OFF
