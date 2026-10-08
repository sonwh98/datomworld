Created-GMT: 2026-10-06 15:01:24 GMT
Created-Local: 2026-10-06 22:01:24 +07

# Task: yin.repl saved-state drift fixes and dht key clearing

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-06 22:01:24 +07 | Status: active | Rationale: Independent adversarial review of Claude-authored yin.repl saved-state and key-clearing changes

Perform a read-only review of the yin.repl saved-state changes on branch `worktree-yin-repl-drifts` (commits 07252cfd and 6c751629 rebased onto master 04c5c638) against src/cljc/yin/vm/docs/yin.repl.md and docs/design/yin.vm.linker.dht.head.md.
Inspect the diff in /Users/sto/workspace/datomworld/.claude/worktrees/yin-repl-drifts (git diff 04c5c638..HEAD):
- src/cljc/yin/repl/main.cljc
- src/cljc/yin/repl/state.cljc
- src/cljc/yin/vm/docs/yin.repl.md
- test/yin/repl/main_test.cljc
- test/yin/repl/state_test.cljc

Scope and intent of change:
1. `dht serve` and `dht join` clear a saved `--dht-publish` AND a saved `--dht-key` from the node's saved state (`:unset #{"--dht-publish" "--dht-key"}`).
2. In `state/resolve-flags`, flags in `unset` are stripped from `saved` only, so an explicit `--key` provided on the command line of `dht serve` or `dht join` stands (`(merge (apply dissoc saved unset) explicit)`).
3. Documentation in `yin.repl.md` reflects that `serve` and `join` clear both saved publishing and saved key, while explicit flags stand.
4. Local verification passed:
   - JVM focused tests: 50 tests / 400 assertions green (yin.repl.main-test, yin.repl.state-test).
   - Node lane: 3,301 tests / 98,513 assertions green (`bb test:cljs`).
   - Dart lane: 3,256 tests green (`bb test:cljd`).
   - Lint: clj-kondo 0/0, cljstyle clean.

Check correctness, invariant preservation, security boundaries (key exposure, accidental publishing/signing, credential isolation), portability across hosts (CLJ, CLJS, CLJD), regressions, and missing tests. Do not edit.
Treat prior reports as untrusted and cite repository evidence for every finding.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report actionable findings as:
P0-P3 | file:line | evidence | concrete fix
State "No actionable findings" when appropriate.
