Completed-GMT: 2026-09-30 10:57:11 GMT
Completed-Local: 2026-09-30 17:57:11 +07
Coding-Agent: claude
Session-ID: adc2ad2b-7804-4513-bdbe-b35cd38d01bf

# Durable index store, slice 2: fix round 2 (gpt-6-sol round-2 findings)

Worktree `/Users/sto/workspace/datomworld-durable-index`, branch `repl-durable-index`. Nothing is staged or committed. Both HIGH findings are fixed, and every lane is green.

I wrote the tests first and ran them against the current code. `bb test:cljs` gave **2 failures, 0 errors**, both as predicted:
- `a-takeover-that-crashed-midway-does-not-brick-the-directory`: a dead `lock` plus a dead `lock.takeover` was refused with "…a takeover of its dead owner's lock died midway; remove …/lock.takeover…".
- `a-short-write-never-becomes-a-partial-head`: with the host storing at most 3 bytes per `writeSync`, HEAD became `"{:v"`. This confirms the finding exactly: a partial record was synced and renamed into HEAD.

Both pass after the fix.

## 1. HIGH: a crashed takeover marker bricked the directory (Node)

**Why not reclaim the marker.** The finding suggests making the marker itself reclaimable, subject to a liveness and identity check. That reproduces the round-1 race one level down. Removing a file after checking it is not atomic in POSIX: a contender that saw a dead marker can remove a newer, live one. Each fix would need another marker. So I replaced the Node lock file and the marker with a protocol that has no remove-a-shared-name step (`node-lock!` in `src/cljc/yin/repl/store/fs.cljc`).

**The claim-entry protocol:**
- Each contender creates its own uniquely named claim entry, `<dir>/lock.<pid>.<uuid>` (exclusive create), and only then lists the other `lock.<pid>.<nonce>` entries.
- **Live entry** (an owner or a rival contender): the contender withdraws its own entry and is refused, naming the directory.
- **Dead entry** (an owner that crashed, or a contender that crashed at any point in its own claim): removed. This removal is race-free because the name is unique: no later process ever creates that name again, so removing it can never remove a newer live claim.
- **Entry naming this process's own pid:** a previous process's leftover. The in-process registry has already established that this process doesn't hold the directory, so it is removed too.
- **Mutual exclusion:** every contender creates before it reads, so of two contenders at most one reads no live rival. The worst case is two exactly simultaneous starts both refusing; two owners is not possible.
- The established owner never re-reads, so it is unaffected by a refused contender.
- **Crash recovery needs no operator step at any point:** whatever a crashed process leaves behind is its own unique, dead entry.
- File contents no longer matter (the pid is in the name), so a half-written lock file is no longer a case.

The JVM and Dart are unchanged: they still take an operating-system lock on `<dir>/lock`.

**Tests (Node), rewritten for the claim entries:**
- `a-live-foreign-owner-is-refused-and-a-dead-ones-lock-is-replaced`:
  - A live foreign claim (the parent pid) is refused, naming the directory; that claim is left as it was, and the refused contender's own entry is withdrawn.
  - A crashed owner's claim is removed, leaving exactly one claim, this process's own.
  - `close!` leaves no claims.
- `a-concurrent-takeover-of-a-dead-owners-lock-leaves-one-owner`:
  - The seam (`:claimed`, runs after this process's own claim, before it reads the others) lets a live rival claim the directory in that window.
  - The contender is refused, naming the directory. Only the rival's claim remains: the dead owner's entry is reclaimed and the contender's own is withdrawn.
- `a-takeover-that-crashed-midway-does-not-brick-the-directory`: the crash-at-that-point case.
  - The directory holds a crashed owner's claim plus a contender that crashed mid-claim.
  - Open succeeds, and both dead claims are gone, with no operator step.
  - The round-2 test-first failure above is the same scenario in the old protocol's files (`lock` plus `lock.takeover`). After the redesign, the test states it in the new protocol's files.
- **Mutation:** the contender never refuses live claims → the live-foreign and concurrent tests fail (5 failures, 1 error). Reverted, and confirmed byte-identical with `cmp`.

## 2. HIGH: short writes could become a partial HEAD (Node)

**Fix:**
- `write-fully!` writes a `Buffer` of the record with `writeSync(fd, buf, offset, remaining)`, advancing by each call's returned byte count until every byte is stored. A write that stores 0 bytes throws instead of looping.
- Then the temp file is fsynced, and the fd is closed in a `finally`. Only after that does the rename happen, followed by the directory sync.
- Any failure throws before the rename, so HEAD keeps its previous whole record.
- The old Node lock file's single `writeSync` of the pid is gone: claim entries hold no content.

**Test:** `a-short-write-never-becomes-a-partial-head` (Node). It patches `fs.writeSync` to store at most 3 bytes per call, as a real `writeSync` may. HEAD must then equal the full 102-byte record.
- Before the fix: `"{:v"`. After: the whole record.

**Other hosts checked for the same class of bug:**
- **JVM:** `FileOutputStream.write(byte[])`. The `OutputStream.write(byte[])` contract writes all `b.length` bytes or throws, so there is no short-write path.
- **Dart:** `File.writeAsStringSync(… flush: true)` writes through `writeFromSync`, which writes the whole list or throws.

Neither host exposes a partial-count API on the paths we use, so neither needs a loop. This is recorded in `write-fully!`'s docstring.

## Documentation

- The `yin.repl.store.fs` namespace docstring, the `lock-name` docstring, and `docs/design/yin.repl.dao.space-index.md` now describe:
  - the claim-entry protocol and why its dead-entry removal is race-free;
  - no operator step after any crash;
  - that two simultaneous starts may both refuse, but never both own;
  - that every byte of the temp record is written before the sync and rename.
- The round-1 marker text is removed.
- The in-process registry's documentation as a host-ownership exception is unchanged.

## Verification (foreground, in the worktree)

| Check | Result |
|---|---|
| `clj -M:kondo --lint` on the 7 changed Clojure files | 0 errors, 0 warnings |
| Focused JVM: store, main, repl, index, query | 112 tests, 826 assertions, 0 failures, 0 errors |
| Full `clj -M:test` | 2411 tests, 184,678 assertions, 0 failures, 0 errors |
| `bb test:cljs` | 2319 tests, 51,114 assertions, 0 failures, 0 errors; `Testing yin.repl.store-test` appears |
| `bb build:yin-repl-peer` | built `build/yin-repl-peer` |
| `bb test:cljd` | "All tests passed!" (+2278). Also ran `flutter test --reporter expanded` on the compiled store test alone: all 23 store tests listed by name, all passed. |
| cljstyle | still blocked by the approval gate. Mechanical checks are clean: no trailing whitespace, no tabs, no run of more than 2 blank lines, each file ends in a single newline. |

## Unchanged (for the orchestrator)

- **Slice 3 must land before the branch ships.** A restarted session's first publication still moves HEAD off the recovered snapshot.
- **Remaining Node limit:** a dead claim whose pid has been reused by an unrelated live process is taken as live, so startup refuses until that process exits. That is the safe direction, and inherent to pid liveness.
- Files touched stay within the allowed list. Scratch logs and backups are only in the ignored `target/` directory.
