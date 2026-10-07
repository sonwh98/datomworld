Created-GMT: 2026-09-30 06:52:25 GMT
Created-Local: 2026-09-30 13:52:25 +07 (+0700)
Coding-Agent: glm
Session-ID: cc0f5a21-b57a-45fc-9246-187510705e5e (resumed; slice-1 session)
# Task: Durable index store — slice 2: HEAD pointer, exclusive directory lock, startup recovery/validation

Role: Storage & Indexing Engineer

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-30 13:52:25 +07 (+0700) | Status: active | Rationale: slice-1 author, same session; team.md storage strengths

WORK TREE: /Users/sto/workspace/datomworld-durable-index (branch repl-durable-index; slice 1 committed as 155babd1).
Edit ONLY there. Do not stage or commit. Every check in the FOREGROUND.

GOVERNING DESIGN: /Users/sto/workspace/datomworld/collab/1790709703000-architect-repl-durable-index-store-startup.gpt-6-sol.findings.md
— this slice = section 2's HEAD/publication and open/validate parts, section 3 (concurrency), section 4 (hosts).
Section 2's REHYDRATION of the transaction log, indexer initialization from the recovered snapshot, and (reset)
continuity are slice 3 — build this slice so slice 3 plugs in without rework.
OWNER APPROVAL (verbatim selected option): "Approve all (Recommended)" — (1) a second REPL on the same durable
directory is refused (exclusive lock); (2) in durable mode (reset) keeps the indexed facts and t; (3) a corrupt
HEAD/manifest refuses startup rather than starting empty.

Scope:
- <dir>/HEAD: a versioned record holding the latest published manifest address. After a round drains all blobs and reads
  its manifest back, write HEAD to a temp file in the same dir, sync it, atomically rename over HEAD, sync the directory
  where the host supports it; only then report the round durably published. Mem mode unchanged (no HEAD).
- Exclusive directory lock acquired before opening content.jing or reading HEAD, held until shutdown; a second process
  fails startup naming the directory. Release on close/exit.
- On open: absent HEAD = empty index; malformed HEAD, missing/invalid manifest, or an unreadable index node (full
  read-manifest + read-datoms traversal) = startup refusal (never silently empty). An unreferenced blob after a crash is
  harmless; a crash before the HEAD rename keeps the previous snapshot.
- Host file ops (temp+rename, sync, lock) implemented explicitly for CLJ, CLJS(Node), CLJD; a host that cannot provide
  them refuses file:<dir> with the unsupported-host refusal (never memory fallback).
- Expose the recovered manifest address (and validated snapshot) so slice 3 can rehydrate from it; do NOT rehydrate here.
Allowed files: src/cljc/yin/repl/store.cljc (or a new small src/cljc/yin/repl/store/*.cljc for HEAD/lock), 
src/cljc/yin/repl/index.cljc ONLY for the post-publish HEAD write hook, src/cljc/yin/repl.cljc and main.cljc only for
lifecycle/close wiring, their tests; docs/design/yin.repl.dao.space-index.md (startup + HEAD contract). dao.jing.*,
dao.space.*: STOP and report if needed.

Acceptance (test first; each must fail if broken; prove key ones by mutation, revert, grep): HEAD written only after
manifest read-back; atomic replace (a torn/partial temp never becomes HEAD); crash-before-rename keeps the old snapshot;
absent HEAD -> empty; malformed HEAD / missing manifest / corrupt index node -> startup refusal; second process on the
same dir refused (naming the dir), first unaffected; lock released on close; all on JVM, Node and Dart where the host
supports the ops. REMEMBER the shadow-cljs trap you found: refusal helpers must return the error object and assert
ex-message/ex-data at the call site. Portable CLJC: :cljd FIRST in mixed reader conditionals; no array-map; no cross-ns
#'private.

Verify and report, in the foreground, in the worktree: kondo; cljstyle check (say if blocked); focused JVM (store,
main, repl, index, query tests); full clj -M:test; bb test:cljs; bb build:yin-repl-peer; bb test:cljd. Write
/Users/sto/workspace/datomworld/collab/1790751145000-storage-engineer-repl-durable-index-slice2.glm-5.3.report.md and give it as
your final response, beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: cc0f5a21-b57a-45fc-9246-187510705e5e

- Status-Event: 2026-09-30 15:57 +0700 | Model: glm-5.3 | Status: failed | Rationale: GLM 5-hour usage cap (429, resets 2026-09-30 18:06:17, likely UTC+8) mid-run; partial uncommitted edits left in the worktree (repl.cljc, index.cljc, main.cljc, store.cljc, new store/fs.cljc, docs, index/store tests)
- Model: claude-opus-5-5 | Assigned: 2026-09-30 15:57 +0700 | Status: active | Rationale: reassigned to keep the durable epic moving; continues from the partial edits; reviewer family remains different (gemini/gpt)

## Continuation note (orchestrator)
You are continuing another model's partially completed slice-2 work in the worktree. FIRST read the full diff (git -C
/Users/sto/workspace/datomworld-durable-index diff; plus src/cljc/yin/repl/store/fs.cljc) and the slice-1 report
(/Users/sto/workspace/datomworld/collab/1790744767000-storage-engineer-repl-durable-index-slice1.glm-5.3.report.md,
esp. its shadow-cljs fold-trap note). Keep what is correct, fix or finish what is not, and meet this brief's acceptance
in full. Report which parts you kept, changed, or completed. Coding-Agent for your report: claude; Session-ID: the new
UUID below.
Continuation Session-ID: adc2ad2b-7804-4513-bdbe-b35cd38d01bf

## Fix round 1 (2026-09-30 16:44:57 +07, orchestrator) — Architect sign-off findings (both sign-offs required)
gemini-3.1-pro-high GRANTED (collab/1790761307000-architect-repl-durable-index-slice2-signoff.gemini-3.1-pro-high.findings.md);
gpt-6-sol WITHHELD (collab/1790761307000-architect-repl-durable-index-slice2-signoff.gpt-6-sol.findings.md). The
orchestrator accepts all four gpt-6-sol findings:
1. HIGH | src/cljc/yin/repl/store/fs.cljc ~69 | Node stale-lock takeover race: two processes can both read a dead owner's
   pid; one removes the stale file and acquires; the other then removes the NEW owner's file and acquires too. Make stale
   takeover conditional on the identity of the file inspected (e.g. compare-and-remove on inode/mtime/content, or an
   exclusive create of a takeover marker), or use an OS lock primitive. Add a concurrent-takeover test.
2. HIGH | fs.cljc ~195 | Node and JVM directory-sync failures are swallowed, so a round can report published before
   HEAD's directory entry is durable. Propagate sync failures where the host supports directory sync, so the round
   reports a publication failure (keep "unsupported host op" distinct from "failed").
3. MEDIUM | src/cljc/yin/repl/index.cljc ~332 | startup validation walks only the EAVT tree; validate every index root the
   manifest names (or narrow the documented guarantee — prefer validating all). Test a corrupt node in a non-EAVT index.
4. MEDIUM | src/cljc/yin/repl/store.cljc ~344 | if closing the content handle throws, the unlock is skipped. Release the
   lock in finally. Test it.
Also: document the in-process lock registry as an explicit host-ownership exception to the no-hidden-global-state
invariant (namespace docstring + the design doc).
Test first (record failures on the current code), then fix. Same allowed files. Every check in the FOREGROUND: kondo;
focused JVM; full clj -M:test; bb test:cljs; bb build:yin-repl-peer; bb test:cljd. Report as
collab/1790751145000-storage-engineer-repl-durable-index-slice2.claude-opus-5-5.report-r2.md.

## Fix round 2 (2026-09-30 17:33:18 +07, orchestrator) — sign-off round-2 findings
gemini-3.1-pro-high re-GRANTED; gpt-6-sol WITHHELD with two new HIGH findings
(collab/1790761307000-architect-repl-durable-index-slice2-signoff-r2.gpt-6-sol.findings.md); the orchestrator accepts both:
1. HIGH | src/cljc/yin/repl/store/fs.cljc ~114 | if a Node process crashes after creating lock.takeover, every later open
   refuses until an operator removes the marker; the stale-lock contract requires recovery after the owner dies. Provide a
   race-safe way to reclaim a dead takeover marker (e.g. the marker carries the taker's pid and is itself subject to the
   same liveness + identity-checked takeover) and test a crash at that point.
2. HIGH | fs.cljc ~356 | Node calls writeSync once without checking its returned byte count; a short write could be synced
   and renamed into HEAD as a partial record. Write until all bytes are stored; rename only after a complete write and a
   successful sync. Test an injected short write. Check the JVM and Dart write paths for the same class of bug.
Test first, then fix. Same files. Every check in the FOREGROUND: kondo; focused JVM; full clj -M:test; bb test:cljs;
bb build:yin-repl-peer; bb test:cljd. Report as ...claude-opus-5-5.report-r3.md.

## Fix round 3 (2026-09-30 18:10 +07, orchestrator): findings from the r3 sign-off
- gemini-3.1-pro-high GRANTED its sign-off.
- gpt-6-sol WITHHELD its sign-off (collab/1790761307000-architect-repl-durable-index-slice2-signoff-r3.gpt-6-sol.findings.md).

1. HIGH | fs.cljc ~124 | Node worker threads share a pid but each has its own `held` registry. One worker can delete another worker's LIVE claim as an "own pid" leftover, and both then become owners. Fix: distinguish a claim's owner from the current worker. For example, a claim is "own" only if this worker's registry holds its exact uuid name. A claim with our pid that is not in our registry must be treated as live while our pid is alive. Test: two worker_threads open the same directory concurrently, and exactly one owns it.
2. MEDIUM | pid reuse keeps a dead claim "live" until the unrelated process exits. Orchestrator decision: accept this as a safe refusal and STATE IT IN THE CONTRACT. Put it in the namespace/lock docstring and the design doc, and name the operator remedy. Do not engineer around it.

Write the test first, then fix. Same files. Run every check in the FOREGROUND: kondo; focused JVM; full `clj -M:test`; `bb test:cljs`; `bb build:yin-repl-peer`; `bb test:cljd`. Report: ...claude-opus-5-5.report-r4.md.
