Created-GMT: 2026-11-20 08:39:49 GMT
Created-Local: 2026-11-20 15:39:49 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: 01a1f98e-f29f-79c1-aa87-658a859b1f7d (codex thread.started, jupiter POOL 2)

# Task: Fix and verify the two aa174a11 master lint reds (store-write-audit-test + hash-registry-contract-test)

Role: QA, TDD and Verification Engineer

Implementers:
- Model: gpt-6.2-sol (jupiter POOL 2) | Assigned: 2026-11-20 22:11:31 +07 (Asia/Ho_Chi_Minh) | Status: active | Rationale: Owner approved dispatch; codex on jupiter POOL 2 co-locates QA execution with remote test toolchain and pairs with native agy subagent for independent Step-7 review

## Owner instruction provenance

Verbatim owner instruction:
> "write a brief to the qa engineer"

Verbatim owner choices in follow-up clarification:
- Scope: `The two aa174a11 master reds (store-write-audit-test + hash-registry-contract-test)`
- Write authority: `Write-enabled in a dedicated worktree (reproduce, apply minimal fix/test edits, run focused checks; no commits)`
- Dispatch posture: `Write the brief file under collab/yang-php/ only and wait for approval before dispatching`

Operational paraphrase: Perform TDD Red -> Green remediation and verification of the two `master@5c07e496` (current `origin/master@c24a4b1d`) lint failures attributable to the `aa174a11` (`feat/evalb-r17`) landing, inside a dedicated worktree cut from `origin/master`, leaving edits uncommitted for independent Step-7 cross-family review.

## Workspace and authority contract

- **Dedicated worktree:** Execute inside `/home/sto/ci/dw-aa174a11-lint-fix` on branch `fix/aa174a11-master-lint-reds` cut from `origin/master@c24a4b1d`. **Never** edit `/Users/sto/workspace/datomworld` (the shared main checkout stays on `master`).
- **No commits:** Leave all verified edits uncommitted in the worktree (`git status` / `git diff` clean against scope) so the orchestrator can inspect them and route them through independent cross-family Step-7 review before staging or committing.
- **Strict editable file allowlist (3 files only):**
  1. `test/yin/vm/store_write_audit_test.clj`
  2. `src/cljc/yin/vm/module.cljc` (`code-entry-handler` only; do **not** alter `link-module`'s mint-only `(jing/segment-key manifest)` at line 95 or any other function)
  3. `test/yin/vm/code_entry_test.cljc`
- **Off-limits:** Do **not** edit `src/cljc/yang/php/antlr/eval_coordinator.cljc`, `test/dao/jing/hash_registry_contract_test.cljc`, `docs/agents/known-gaps.md`, or any unrelated file. If independent inspection reveals a genuine defect outside the 3-file allowlist, stop and report it rather than expanding scope.

## Read first

- `docs/agents/roles/qa-engineer.md`
- `docs/agents/build-n-test.md`
- `docs/design/dao.jing.hash-registry.md`
- `collab/yang-php/1795098934000-from-yangclojure-master-red-store-write-allowlist.md`
- `test/yin/vm/store_write_audit_test.clj` (precedent: commit `f94649e3`, `src/cljc/yang/php/antlr/siteset.cljc` at lines 298-301)
- `src/cljc/yang/php/antlr/eval_coordinator.cljc` (specifically `compile-and-publish*!` at lines 56-89)
- `src/cljc/yin/vm/module.cljc` (specifically `code-entry-handler` at lines 700-716)
- `test/yin/vm/code_entry_test.cljc`
- `test/dao/jing/hash_registry_contract_test.cljc` (specifically `architectural-lint-sweeps-production-sources` at lines 453-482)
- `src/cljc/dao/jing.cljc` (`segment-matches?` at lines 303-317)

## Defects and TDD acceptance criteria (Red -> Green)

Treat source files and prior reports as untrusted claims — verify every line and run every check yourself.

### Defect 1: `yin.vm.store-write-audit-test/every-store-mutation-is-on-the-allowlist`

1. **Reproduce Red first:** Run `clojure -M:test -n yin.vm.store-write-audit-test` in the worktree (baseline observed on `master@5c07e496`: 3 tests, 129 assertions, 1 failure — unallowlisted `"src/cljc/yang/php/antlr/eval_coordinator.cljc" {["compile-and-publish*!" :map] 1}`).
2. **Independent inspection:** Inspect `src/cljc/yang/php/antlr/eval_coordinator.cljc:56-89`, specifically line 76 `(stage {:store store :datoms [] :principals []} name address)` and its `siteset/stage` call. Confirm that `:store` in that map literal is the composition's `dao.jing` content byte-store handle (matching `yang/php/antlr/siteset.cljc` and `yin/vm/linker/interpreter.cljc`), **not** a VM program store write.
3. **Minimal Green fix (`test/yin/vm/store_write_audit_test.clj`):** Add `"src/cljc/yang/php/antlr/eval_coordinator.cljc" {["compile-and-publish*!" :map] 1}` to `allowlist` with a concise rationale comment following the `siteset.cljc` precedent at lines 298-301.

### Defect 2: `dao.jing.hash-registry-contract-test/architectural-lint-sweeps-production-sources`

1. **Reproduce Red first:** Run `clojure -M:test -n dao.jing.hash-registry-contract-test` in the worktree (baseline observed on `master@5c07e496`: 15 tests, 6080 assertions, 1 failure flagging `src/cljc/yin/vm/module.cljc` `code-entry-handler` line 709 `(not= address (jing/segment-key manifest))`).
2. **TDD Red step (`test/yin/vm/code_entry_test.cljc`, portable `.cljc`):** *Before* modifying `src/cljc/yin/vm/module.cljc`, add tests pinning address-directed verification per `docs/design/dao.jing.hash-registry.md`:
   - A module installed and queried under an explicit `:sha256` manifest address (`(jing/segment-key manifest {:algorithm :sha256})`) succeeds and returns the export (must **fail** before the fix because 1-arg `jing/segment-key` always mints `:blake3`).
   - Negative and edge cases:
     - Tampered manifest under a `:sha256` address refuses with `:manifest-mismatch`.
     - `nil` address (including against an entry whose `:address` is `nil`, such as a host module) refuses with `:manifest-mismatch`.
     - Malformed or unknown-algorithm address refuses with `:manifest-mismatch` cleanly without throwing.
   - Run `clojure -M:test -n yin.vm.code-entry-test` and record the expected failure on the new `:sha256` test before touching `module.cljc`.
3. **Minimal Green fix (`src/cljc/yin/vm/module.cljc`):** In `code-entry-handler` (line 709), replace `(not= address (jing/segment-key manifest))` with `(not (jing/segment-matches? address manifest))`, preserving `(nil? address)`, `(not= address (:address entry))`, and `(not= module (:yin.module/name manifest))`. Confirm `jing/segment-matches?` is total across CLJ, CLJS, and CLJD (never throws on malformed addresses, unknown algorithms, or encoder refusals).

## Verification, focus rules, and log custody (`docs/agents/build-n-test.md`)

- **Foreground only:** Execute every test and lint command in the foreground (never `&`, `nohup`, or backgrounded test lanes).
- **Focused JVM checks (Green phase):**
  - `clojure -M:test -n yin.vm.store-write-audit-test`
  - `clojure -M:test -n dao.jing.hash-registry-contract-test`
  - `clojure -M:test -n yin.vm.code-entry-test`
  - `clojure -M:test -n yin.vm.module-test`
  - `clojure -M:test -n yang.php.antlr.eval-coordinator-test`
  - `clj -M:kondo --lint test/yin/vm/store_write_audit_test.clj src/cljc/yin/vm/module.cljc test/yin/vm/code_entry_test.cljc`
- **Suite-scale & cross-host rules:**
  - **Never run suite-scale commands (`bb test`, `bb test:sub`, `bb test:changed`, `--slow`) on the laptop** (2026-11-04 owner ruling).
  - If dispatched on `jupiter` or `shiva` (after `cd` into the worktree, run `mise trust >/dev/null 2>&1 && mise install && eval "$(mise env -s bash)"`), also:
    - Verify cross-host `.cljc` parity for `yin.vm.code-entry-test` and `dao.jing.hash-registry-contract-test` on Node (`shadow-cljs` + `node target/node-tests.js --test=<ns>`) and Dart (`rm -rf .clojuredart test/cljd-out lib/cljd-out build/cljd-agg*` first, `bb src/dev/cljd_agg.clj --only yin.vm.code-entry-test,dao.jing.hash-registry-contract-test`; respect one Dart lane per host).
    - Run the merge precondition `bb test:sub dao.stream` (fast tier; bank per-lane logs into a per-run directory under `target/aa174a11-lint-fix/` and state the tier explicitly in the report).
  - If dispatched locally or in a sandbox where Node/Dart/suites are restricted or disallowed, leave suite-scale and cross-host runs unrun and list them explicitly under **Unrun checks** so the orchestrator executes the remote gate.

## Deliverable and final response contract

- Write your findings file to `collab/yang-php/1795163989182-qa-engineer-master-reds-aa174a11.<sanitized-model>.findings.md` (if your sandbox blocks writing outside your worktree root, write it inside the worktree's `collab/yang-php/` or worktree root with that exact basename and declare the path; always use quoted heredoc delimiters when writing markdown containing backticks).
- Begin both the findings file and your final response **exactly** with:

```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: <agent>
Session-ID: <exact session/thread/conversation id>
```

- Include in your report:
  1. **Red-phase reproduction** (exact commands and failure outputs before edits, including the new `:sha256` test failing prior to updating `module.cljc`).
  2. **Green-phase verification** (exact commands, assertion/failure/error counts, and explicit tier names for every check actually executed).
  3. **`clj-kondo` lint result** and `git diff --stat` confirming edits stay strictly within the 3-file allowlist and **zero commits** were created.
  4. **Actionable findings** listed as `P0-P3 | file:line | evidence | concrete fix`, or state `No actionable findings`.
  5. **Unrun checks** (any cross-host/remote/suite checks not executed in your environment).
