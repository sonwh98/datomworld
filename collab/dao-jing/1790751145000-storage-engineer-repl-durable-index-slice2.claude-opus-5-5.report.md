Completed-GMT: 2026-09-30 09:30:56 GMT
Completed-Local: 2026-09-30 16:30:56 +07
Coding-Agent: claude
Session-ID: adc2ad2b-7804-4513-bdbe-b35cd38d01bf

# Durable index store, slice 2: HEAD pointer, exclusive directory lock, startup recovery and validation

Worktree `/Users/sto/workspace/datomworld-durable-index`, branch `repl-durable-index` (on top of 155babd1). Nothing is staged or committed. I continued from glm-5.3's partial edits, which stopped at its usage cap. Every check below ran in the foreground, in the worktree.

## Kept, changed, completed

**Kept as written (reviewed and correct):**
- `store.cljc`:
  - The HEAD record `{:version 1 :manifest <addr>}`.
  - `recovered-head`: an absent HEAD means an empty index; a malformed HEAD is a refusal.
  - `validated-snapshot`: an eager `read-datoms` walk, where any failure is a "corrupt" refusal naming the directory.
  - `open-locked-dir`: the lock is taken before `content.jing` or HEAD is touched, and it is released on any refusal after that.
  - The durable handle carries `:head-fn`, `:recovery` (`{:manifest :datoms}`, the handoff to slice 3), `:durable-dir`, and a `:close-fn` that also unlocks. There is also `close!`.
- `index.cljc`: the `:after-publish` hook runs after `read-manifest`, and before the round is recorded as published.
- `repl.cljc`: `:head-fn` is wired into `make-indexer`, and `:index-recovery` is exposed on the shell state.
- `main.cljc`: `close-index-store!` runs on the CLJ, Node, and Dart exit paths.
- The doc section on startup, HEAD, and the lock.
- Most of the new tests: missing/corrupt manifest, unreadable index node (a frame spliced out), corrupt content log, unreferenced blob, crash-before-rename leftover, and a real second JVM process.

**Changed:**
- **Second owner inside the same process (a real bug on Dart).** The JVM's and Dart's operating-system locks are POSIX `fcntl` locks, which never refuse their own process. On Dart, opening the same directory twice in one process would have succeeded. Also, closing any second handle on the lock file drops the process's `fcntl` lock.
  - Fix: `fs/lock!` now keeps a registry of the directories this process holds, keyed by canonical path. It refuses a second owner before any handle is opened.
  - The release is idempotent, so closing a store twice never releases a later owner's lock.
  - The host locks moved into a private `host-lock!`.
- **Node lock liveness:** `live-pid?` treated `EPERM` as dead. `EPERM` means the process exists but belongs to someone else, so a live owner's lock could have been stolen. It now counts as alive.
- **JVM directory sync:** after the rename, the JVM now fsyncs the directory (best effort, via `FileChannel/open` on the directory). Before, only Node synced it.
- **Reader-conditional order:** every mixed conditional in `fs.cljc`, `store_test.cljc`, and `index_test.cljc` had `:clj` first, which is the ClojureDart trap. All now put `:cljd` first.
- **index_test:** each durable test now closes its store in `finally` (they leaked locks and handles). The HEAD-write-failure case also asserts that the unpublished round reports no manifest.

**Completed (missing from the partial work):**
- **Proof that a torn or partial temp never becomes HEAD.**
  - `fs/atomic-replace!` gained an optional `{:before-rename (fn [temp])}` seam. It runs after the temp is synced and before the rename, which is the only window a crash can interrupt.
  - Test: HEAD is made read-only, and a replacement still succeeds. Only a rename can do that; writing in place would fail.
  - Test: an interrupted replacement tears the temp and throws, and HEAD keeps its previous whole record.
- **End-to-end interrupted publication:** a round drains its blobs, reads its manifest back, and then crashes inside the HEAD replacement. The round reports not published, and a reopen recovers the previous snapshot and its datoms.
- **Node cross-process lock:** a lock file naming another live process (the test's parent pid) refuses startup, names the directory, and leaves that lock file untouched. A lock file naming a dead pid (one `spawnSync("true")` produced) is replaced. `close!` removes the lock file.
- **Dart cross-process lock:** the test starts a real `dart run` child that holds the lock with `lockSync`. A second open is refused, naming the directory. After the child is killed, the directory opens again.
- **Doc additions:** the in-process owner check, which hosts sync the directory, and the interim limitation described below.

## Acceptance → evidence

Each row names the test, and the mutation that proves the test fails when the property breaks. Every mutation was reverted, and I confirmed the files matched their backups byte for byte afterwards.

| Property | Test | Mutation → result |
|---|---|---|
| HEAD is written only after the manifest read-back | `index-test/a-durable-round-writes-head-only-after-the-manifest-read-back` | Write HEAD before `read-manifest` → FAIL (`nil? (head-text dir)`) |
| Replacement is atomic; a torn temp never becomes HEAD | `store-test/head-replacement-is-a-rename-of-a-synced-temp-never-a-partial-head`, `…/a-publication-interrupted-before-the-rename-reopens-the-previous-snapshot` | Temp path = target (write in place) → 1 error + 2 FAIL |
| A crash before the rename keeps the old snapshot | the two above, plus `a-crash-before-the-rename-keeps-the-previous-snapshot` | same |
| Absent HEAD → empty index | `a-durable-store-carries-its-lock-head-and-recovery`, and the last step of the malformed-HEAD test | n/a |
| Malformed HEAD → startup refusal | `a-malformed-head-refuses-startup-and-leaks-no-lock` (6 malformed shapes, plus a well-formed address that points at nothing) | Swallow `recovered-head` errors → 6 FAIL + 3 ERROR |
| Missing manifest or corrupt index node → refusal | `a-missing-manifest-refuses-startup`, `an-unreadable-index-node-refuses-startup`, `a-corrupt-content-log-refuses-startup` | Skip the snapshot walk → FAIL/ERROR in the missing-manifest and unreadable-node tests (plus the recovery tests) |
| Second process refused, naming the dir; the first is unaffected | JVM: `a-real-second-jvm-process-is-refused-and-its-death-releases`; Node: `a-live-foreign-owner-is-refused-and-a-dead-ones-lock-is-replaced`; Dart: `a-real-second-dart-process-is-refused-and-its-death-releases`; all hosts in-process: `a-second-owner-of-the-directory-is-refused-and-the-first-is-unaffected` | No OS lock → JVM cross-process test ERROR. `live-pid?` always false → Node test 1 FAIL + 3 ERROR. No in-process registry → **Dart** in-process test FAILs (JVM stays green by design: its `OverlappingFileLockException` also refuses in-process) |
| Lock released on close | the second-owner test (reopen after close), plus the `close!` step in the Node test | `close-fn` without unlock → 4 FAIL + 5 ERROR across the reopen tests |

**Dart failures really do fail the run.** On one Dart run I combined a deliberate failing `(is …)` in the Dart cross-process test with the no-registry mutation. The run reported `Some tests failed` with both tests marked `[E]`, and printed the child's real pid (`PROBE-HOLDER-PID 39543`). So cljd assertion failures propagate, and the `dart run` child really does hold the lock. Both changes were reverted.

**Portability checks:**
- Refusal tests use the error-object shape (`refusal-of` returns the error; `ex-message` is applied at the call site), per slice 1's shadow-cljs fold trap.
- Every mixed reader conditional puts `:cljd` first.
- A grep found no `array-map` and no cross-namespace `#'` in the changed files.

## Verification

| Check | Result |
|---|---|
| `clj -M:kondo --lint` on the 7 changed Clojure files | 0 errors, 0 warnings |
| `cljstyle check` | **Blocked.** The binary (mise, 0.17.642) needs an approval this session can't grant. As an approximation: no trailing whitespace, no tabs, no run of more than 2 blank lines, and each file ends in a single newline. All clean. |
| Focused JVM: store, main, repl, index, query | 108 tests, 814 assertions, 0 failures, 0 errors (re-run after all reverts) |
| Full `clj -M:test` | 2407 tests, 184,663 assertions, 0 failures, 0 errors |
| `bb test:cljs` | 2312 tests, 51,093 assertions, 0 failures, 0 errors; `Testing yin.repl.store-test` and `yin.repl.index-test` both appear |
| `bb build:yin-repl-peer` | built `build/yin-repl-peer` |
| `bb test:cljd` | "All tests passed!" (+2274). The piped log drops per-test lines, so I also ran `flutter test --reporter expanded` on the compiled store and index tests: all passed, and the new durable tests, including the Dart cross-process one, appear by name. |

## For the orchestrator

- **Interim hazard until slice 3 lands.** Recovery is exposed but not installed. A restarted or `(reset)` durable session starts with an empty indexer, so its first publication covers only its own facts and moves HEAD off the previous snapshot. The older blobs stay in `content.jing`, unreferenced. This is written in the doc and in the `yin.repl.store` docstring. Slice 3 (rehydrating from `:recovery` / `:index-recovery`, and the indexer's initial manifest) removes it. Don't ship slice 2 alone as "durable".
- **The seam for slice 3:** `(:recovery store)` and `(:index-recovery state)` are `{:manifest <addr|nil> :datoms <vector|nil>}`, walked and validated at open. `make-indexer` already takes `:after-publish`; slice 3 only needs to seed `:manifest-address`, the transaction counts, and `:local` from that recovery.
- **Node lock limits.** The Node lock is a create-exclusive file holding the owner's pid (Node's core has no `flock`). Two things are not handled:
  - A reused pid would be taken for the old owner.
  - Two processes clearing the same dead owner's lock at the same moment could race.
  
  Both are narrow, and the JVM and Dart use real operating-system locks.
- **Leaked lock if composition fails mid-startup.** If `driver/create-state` threw after the store opened, `startup` would not close it. The process exits right away in that case, which releases the JVM and Dart locks; on Node, the next start replaces the dead pid's lock. I left it alone to keep the diff small.
- I touched no files outside the allowed list. `dao.jing.*` and `dao.space.*` needed no changes. Temporary probes and backups are only in the ignored `target/` directory.
