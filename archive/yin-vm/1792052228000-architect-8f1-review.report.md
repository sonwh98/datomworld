Completed-GMT: 2026-10-15 08:17:08 GMT
Completed-Local: 2026-10-15 15:17:08 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: pending (claude -p; the harness does not expose it)

# 8f-1 Architect review of the uncommitted diff on srvm-phase8f1 @ d43afbe3

Read-only; this report is the only artifact. Everything below was re-verified against the working tree and HEAD with git/grep, not taken from the engineer reports.

## 1. What I checked

| gate | result |
|---|---|
| Steps 0-5 of the ruling, file by file | every prescribed edit present; deviations D1-D3 only (verdicts in §2) |
| Three pinned descriptor/golden literals | byte-identical at HEAD and in the working tree: `7b7c6ae9…f3489` (`debruijn_code_test:362`), `aa757659…7e74c` (`debruijn_register_code_test:11`), four golden H values (`debruijn_code_test:314-321`); `debruijn_register_code_test.cljc` wholly unchanged (`git diff --quiet` exit 0) |
| Literal 23-mnemonic table vs the derivation | compared entry by entry against HEAD `yin.vm.code/vector-operand-table` (22 rows, `code.cljc:226-247`): the 20 carried rows match slot count, order, kind and name with only the namespace moved to `yin.debruijn.code`; `:var` is absent; `:load-bound`, `:load-free`, `:closure` are the three redefined rows with the same slots as the old inline literal. 20 + 3 = 23. `terminators` `#{:jump :return :halt}` equals HEAD `code/terminators` (`code.cljc:26-28`). No gap; the passing `descriptor-declares-the-contract` pin is the independent proof |
| Deftest name-set diff (D3) | see §2 D3; nothing vanished beyond the ledger |
| Scope | `git status`: no `src/yang`, no `docs/`, no `.md`; the only C4 file is `test/yang/python/antlr/int_heap_test.cljc` (require + first `run-all` block, as ruled) |
| Removed v3 facts live where ruled | `scan_test/assert-agreement` keeps `(is (= (:free-names new) tree-names))` and `(is (= (:requirements new) (vm/ast-requirements rows)))`; `cell_test/lift-of-a-bare-cell-ref-is-refused-test` present; `debruijn_code_test:627-629` inlines the three FFI pair keys; `publish_test` asserts the manifest's footprint effects directly |
| Leftover references to deleted code | none: `yin.vm.completion`, `segment-requirements`, `segment-extraction-queries`, `ucf.handoff`, `ucf.checkpoint`, `renamespace-slots`, `carried-mnemonics` have no code reader under `src test`. The `completion/` hits under `yin/vm/ucf/authority/*` are the unrelated `yin.vm.ucf.authority.completion` alias (`front.cljc:108`). Two stale docstrings remain (P3 below) |
| `ast-requirements` docstring | byte-identical to HEAD; it never had the "segment twin" sentence my ruling told the engineer to drop. Engineer's call to leave it is correct |
| Hygiene | `git diff --check` clean; index empty; deletions are filesystem unlinks (orchestrator: `git add -A` on the listed paths stages them as deletions) |

## 2. Deviation verdicts

**D1 — `vm/opcode-table` and `opcase` not deleted: CONFIRMED.** My ruling R3 was wrong to put them in step 3. `semantic.cljc` reads `vm/opcode-table` at :563, :624, :634, :760, :767, :768, :770 and `semantic_test.cljc:118-125` reads it six times; `opcase` is `semantic.cljc`'s only. Deleting them in 8f-1 cannot compile while `semantic.cljc` is retained. They die in 8f-3 in the same commit as `semantic.cljc`, `semantic_test` and `linearize.cljc`. The 8f-3 ledger gains: `vm.cljc:612-680` (`opcode-table` :619, `opcase` :647). The optimisation note `yin/vm/docs/bytecode-vm-optimization.md:336` that mentions `vm/opcase` is 8g's.

**D2 — `query_test/segment-db` kept for `code-joins-store-put-key-to-ast-by-value`: CONFIRMED, with the disposition for 8f-3 fixed now.** My ruling enumerated two callers; the third is real (`query_test:1855-1866`). The old-code dependency in that test is exactly two things: `code/project-segment-qualified` (the v3 positional segment projection) and `linearize/lower-rows` (the v3 lowerer), both in `segment-db` (`query_test:1813-1818`). The test itself asserts a dao.space fact (U6: a `$ast`/`$code` join by value across relations that share no ids), not an old-kernel fact, so it must be PORTED in 8f-3, not dropped. The port is mechanical: `segment-db` becomes `(rel (scan/segment-rows (:vector (sr-linearize/project ast))))` (`scan/segment-rows` is the v4 twin, ruling fact 6), and because the register `:store-put` row carries `rd` before `key` (`semantic_register/code.cljc:45`, row shape `[a pc :store-put rd key value]`) the pattern changes from `[$code _ _ :store-put ?key _]` to `[$code _ _ :store-put _ ?key & _]`. With that, the `yin.vm.code` and `yin.vm.linearize` requires of `query_test` go in 8f-3. Retaining instead of porting in 8f-1 is the right call under minimal diff: 8f-3 already edits `query_test` for the same two requires, and a new v4 join assertion belongs in the slice whose three-host lane validates the register path.

**D3 — completion_test had 18 deftests, not 19: CONFIRMED; nothing else vanished.** `git show HEAD:test/yin/vm/completion_test.cljc` contains 18 `(deftest` forms; my 19 was a miscount. Name-set diff over `test/**/*.{clj,cljc,cljs,cljd}` (HEAD vs working tree, same extraction both sides, 4396 → 4374 by my regex; the engineer's regex gave 4440 → 4418; both deltas −22):

- removed, 24 names: the 18 completion_test names (`blocked-reader-wait-entry-is-a-root`, `caller-fetch-answers-what-the-vm-does-not-hold`, `code-facts-from-tree-and-segment-are-equal`, `code-side-missing-pid-is-incomplete`, `def-recursion-terminates`, `early-refusals`, `env-binding-does-not-discharge-a-free-name`, `ffi-pair-key-operand-is-refused`, `host-functions-in-values`, `loaded-images-answer-fetch`, `module-names`, `present-parked-record-is-pulled`, `primitive-without-a-profile-is-incomplete`, `segment-free-names-cases`, `stored-closure-is-a-work-item-and-its-names-are-discharged`, `two-closures-one-segment-two-envs`, `unfetchable-segment-blocks-and-the-rest-is-walked`, `value-side-missing-pid-is-refused`); the four single deletions `canonicalize-verifies-the-batch-stamp` (rule_r), `completion-address-reuse-preserves-answers` (scan), `completion-over-a-cell-is-not-complete-test` (cell), `requirement-sets-from-tree-and-segment-are-equal` (query); and the two renamed names `footprint-mnemonics-is-the-v3-table-minus-push`, `opcode-table-derives-from-the-named-table`.
- added, 2 names: `footprint-mnemonics-is-the-v4-table`, `opcode-table-is-this-dimensions-own-data`.

24 − 2 = 22. Every removed name is accounted for by the ruling's ledger; no deftest outside it disappeared.

## 3. Findings

**P1 (blocking):** none.

**P2 (should fix before commit):** none.

**P3 (nits; fix in 8f-1 if the orchestrator wants a clean slice, otherwise ledgered):**

1. `test/yin/vm/linker/publish_test.cljc:173` — the deftest is still named `published-footprint-completes-module-discovery` but now asserts only that the published manifest carries `[:yin.module/footprint :effects]` = `#{}`. The name describes the dropped `completion/complete` fact. Suggested name: `published-manifest-carries-the-footprint-effects`.
2. `test/yin/vm/debruijn_code_test.cljc:338,345` — `(is (not (contains? dc/opcode-table :var)))` appears twice (my ruling prescribed it in the new first block while the kept second block already had it). Harmless duplication; drop the first.
3. `src/cljc/yin/vm/ucf.cljc:326` — the `parking-kinds` docstring names `yin.vm.ucf.handoff`, deleted in this slice. R6 says a docstring naming a deleted var is fixed in the deleting step; `parking-kinds` itself dies in 8f-3 step 4b, so deletion there is an acceptable fix. Ledger it for 8f-3 (and 8g's grep gate) if not touched now.
4. `test/yin/vm/linker_test.cljc:263,315` — prose says `yin.vm.code/materialize-vector!` / `yin.vm.code`; the file's `code` alias is already `yin.vm.semantic-register.code` (:22). My R4 missed these two prose sites. Fix with 8f-3's `yin.vm.code` deletion (or in the 8f-2 rename, where the alias target moves anyway).
5. `src/cljc/yin/vm/ucf/rows/checkpoint.cljc:4` says it "replaces `yin.vm.ucf.checkpoint`", now historical prose. 8g.

Verification status as reported by the engineer and not re-run here: JVM quick tier 4068 tests / 251041 assertions / 0 failures with `-e :slow`, kondo diagnostics unchanged against a frozen baseline. Node and Dart lanes are unrun; the three-host lane (`pinned-identities-are-host-independent`, `register-determinism`) is the ruling's landing gate and the standing commit rule (sign-off + green lanes), so the orchestrator runs them before committing. Nothing in this diff is host-specific beyond what those lanes already cover.

## 4. Ledger additions for 8f-3 (from this review)

- Delete `vm.cljc` `opcode-table` (:619) and `opcase` (:647) with `semantic.cljc` (D1).
- Port `query_test/segment-db` to `scan/segment-rows` over `sr-linearize/project` and change the join pattern to `[$code _ _ :store-put _ ?key & _]`; then drop `yin.vm.code` and `yin.vm.linearize` from `query_test` (D2).
- Fix or delete the stale prose at `ucf.cljc:326`, `linker_test.cljc:263,315`.

## 5. Invariants

A is the only code identity: unchanged, H stays a derived checksum. Tree always available: completion's tree-less segment scoping is what died; the scanner's agreement tests still compare against the tree. Derive, don't persist: the one new literal replaces a derivation whose source dies; no new table beyond the private `terminators` set. Minimal diff: 73 insertions against 5794 deletions, zero new namespaces. No backend widening: 23 mnemonics in, 23 out, `lowering-contract-version` stays 2.

SIGN-OFF
