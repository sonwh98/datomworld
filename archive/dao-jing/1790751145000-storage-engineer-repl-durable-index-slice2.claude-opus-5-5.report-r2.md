Completed-GMT: 2026-09-30 10:07:57 GMT
Completed-Local: 2026-09-30 17:07:57 +07
Coding-Agent: claude
Session-ID: adc2ad2b-7804-4513-bdbe-b35cd38d01bf

# Durable index store, slice 2: fix round 1 (gpt-6-sol sign-off findings)

Worktree `/Users/sto/workspace/datomworld-durable-index`, branch `repl-durable-index`. Nothing is staged or committed. All four findings and the documentation item are fixed, and every lane is green.

I wrote the tests first and ran them against the unfixed code. The only additions to that code were seams, with no behaviour change:
- a `dead-owner-seen` hook on `fs/lock!`;
- a public `fs/directory-sync-supported?`;
- the handle's close composition extracted as `store/lock-releasing-close`, with its old semantics.

Recorded failures before the fix: JVM `clj -M:test -n yin.repl.store-test` gave 7 failures. Node `bb test:cljs` gave 12 failures and 1 error: the same 7, plus 5 failures and 1 error in the Node-only takeover test. Every one passes after the fix.

## Findings → fix → evidence

**1. HIGH: race in taking over a dead owner's lock on Node** (`src/cljc/yin/repl/store/fs.cljc`)
- **Fix:** takeovers are serialized by an exclusive-create marker, `<dir>/lock.takeover`, holding the taker's pid (`take-over-dead-owner!`).
  - Only the marker's holder re-reads the lock file, and it removes the file only while it still names a dead owner. It then creates its own lock with `wx`, and the marker is removed in `finally`.
  - Normal acquisition never removes anything: it is `wx` create only, and a lost `wx` race is refused.
  - So a contender that inspected the dead owner earlier can never remove a lock that a faster contender has since taken.
  - A marker naming a live pid is a takeover in progress; it is refused as "locked", naming the directory.
  - A marker naming a dead pid is a takeover that crashed midway. It is refused with a message naming the marker file for the operator to remove. That needs a crash inside a takeover of a crashed owner's lock, which is rare, and removing the marker automatically would reintroduce the same race.
  - A lock file that is present but not yet readable as a pid (its creator is still writing) is treated as owned.
- **Test:** `a-concurrent-takeover-of-a-dead-owners-lock-leaves-one-owner` (Node).
  - The seam reproduces gpt-6-sol's interleaving exactly. The contender sees the dead owner; a rival (a live foreign pid) then completes its own takeover; the contender continues.
  - The test asserts that the contender is refused, the refusal names the directory, and the rival's lock file survives.
  - It also covers a live marker (refused naming the directory), a dead marker (refused naming the marker file), and that neither refusal touched the lock file.
- **Before the fix:** the contender acquired the lock and removed the rival's file (5 failures, 1 error).

**2. HIGH: directory-sync failures were swallowed** (`fs.cljc`)
- **Fix:** `sync-directory!` on Node and the JVM runs only where `directory-sync-supported?` is true (POSIX Node and JVM). There, a failure now throws "cannot sync the directory … after the rename". That fails `atomic-replace!`, so `:head-fn` throws, and the round reports a publication failure.
- "Unsupported" stays distinct from "failed": Dart's core and Windows report unsupported and are not failures.
- On Node the fd is now closed in a `finally`.
- **Tests:**
  - `a-failed-directory-sync-fails-the-head-replacement` removes read permission from the directory. The rename still works, since that needs write and search permission, but the directory can't be opened for sync, so this is a real host failure, not a stub.
  - The test asserts a refusal mentioning "sync" where the host supports directory sync, and success where it doesn't (Dart).
  - `a-round-whose-head-cannot-be-made-durable-is-not-published` runs the same condition end to end: `:published?` is false on the JVM and Node, and true on Dart.
- **Before the fix:** 3 failures on each of the JVM and Node.
- The syncs of the JVM and Node directories also run on every normal publication in the green suites, so on macOS they succeed normally.
- **Documented nuance:** after a failed directory sync, the renamed HEAD may already be visible. It still names a manifest the store answered, so a later open recovers either the old or the new snapshot, never a torn one.

**3. MEDIUM: startup validation walked only EAVT** (`src/cljc/yin/repl/store.cljc`)
- **Fix:** `validated-snapshot` now calls `read-manifest`, then walks every index root the manifest names (EAVT, AEVT, AVET, VAET) with the public `dao.space.index/walk-index-datoms`. Each tree must cover exactly the manifest's `:count` datoms. The EAVT datoms are still what `:recovery` exposes.
- No `dao.space.*` file changed.
- **Test:** `an-unreadable-node-in-any-index-refuses-startup`, once each for AEVT, AVET, and VAET.
  - It removes that tree's root frame from `content.jing`.
  - It proves the EAVT tree still reads, so only the non-EAVT tree is damaged.
  - It asserts a "corrupt" startup refusal.
- **Before the fix:** 3 failures (the open succeeded).

**4. MEDIUM: a throwing content close skipped the unlock** (`store.cljc`)
- **Fix:** `lock-releasing-close` runs `close-content!` in a `try` and releases the lock in `finally`.
- **Same failure class, fixed alongside:** a refused open (for example a corrupt HEAD) now also closes the content log it had opened before releasing the lock. Before, only the lock was released and the file handle leaked.
- **Test:** `a-failing-content-close-still-releases-the-lock`. A close that throws is still reported, and the directory then opens again.
- **Before the fix:** 1 failure (the reopen was refused as locked).

**Documentation: the registry as an explicit exception.** The in-process lock registry (`held`) is now documented as an explicit host-ownership exception to the "no hidden global state" invariant, in three places:
- the `yin.repl.store.fs` namespace docstring;
- a comment on the `defonce`;
- `docs/design/yin.repl.dao.space-index.md`.

The rationale: it mirrors a fact the host already keeps per process (which files this process has locked), so it can be no narrower than the process. It holds only the canonical paths of the directories this process currently owns. The design doc also now describes the takeover marker, the propagation of directory-sync failures, and validation of all four indexes.

The one new kondo suppression, `#_{:clj-kondo/ignore [:unused-binding]}` on `host-lock!` (its parameter is Node-only), follows `dao.jing.file/with-lock`'s idiom.

## Verification (foreground, in the worktree)

| Check | Result |
|---|---|
| `clj -M:kondo --lint` on the 7 changed Clojure files | 0 errors, 0 warnings |
| `cljstyle check` | still blocked by the approval gate (not required this round). Mechanical checks are clean: no trailing whitespace, no tabs, no run of more than 2 blank lines, each file ends in a single newline. |
| Focused JVM: store, main, repl, index, query | 112 tests, 826 assertions, 0 failures, 0 errors |
| Full `clj -M:test` | 2411 tests, 184,678 assertions, 0 failures, 0 errors |
| `bb test:cljs` | 2317 tests, 51,111 assertions, 0 failures, 0 errors; `Testing yin.repl.store-test` appears |
| `bb build:yin-repl-peer` | built `build/yin-repl-peer` |
| `bb test:cljd` | "All tests passed!" (+2278). I also ran `flutter test --reporter expanded` on the compiled store test: all passed, and every store test (all 23, including the 5 new ones) is listed by name. |

## Unchanged from round 0 (for the orchestrator)

- **Slice 3 must land before this branch ships.** A restarted or reset session's first publication still moves HEAD off the recovered snapshot. gpt-6-sol repeats this, and the doc states it.
- **Remaining Node limit:** a reused pid would be taken for a live owner. This is inherent to a pid-file lock; the JVM and Dart use real operating-system locks.
- Files touched stay within the allowed list. Scratch logs are only in the ignored `target/` directory.
