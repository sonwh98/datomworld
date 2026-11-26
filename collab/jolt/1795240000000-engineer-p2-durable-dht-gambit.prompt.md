Created-GMT: 2026-11-26 11:19:29 GMT
Created-Local: 2026-11-22 16:1x:xx ICT (+07)
Session-ID: pending (new thread; continue the p1 lineage)
# Task: P2 — durable DHT stores on the jolt Gambit backend (owner-directed feature parity)

Role: VM Runtime Engineer (implementation)
Implementers:
- Model: gpt-6.2-sol | Assigned: see Created-Local

## Goal (owner directive, verbatim)

"do this. yin.repl should be consistently feature-wise on all supported host.
if it is not, then let me know" — referring to durable DHT stores on Gambit,
previously refused. The end state: `bb yin-repl --backend jolt-gambit
--index-store dht:<dir>` (and `file:<dir>`) opens a DURABLE store and the DHT
session works with it, feature-matched to the Chez/JVM/Node/Dart backends.

## The boundary nuance (read carefully — this is the design key)

The current refusal (host/gambit/yin/dao/posix/file.jolt:6-25 — the overlay
"runs no file FFI") is a SCOPE refusal from stage-1, not a fundamental one.
The C4 ruling forbids GC-UNSAFE BLOCKING calls — the network/sleep class
where a parked thread freezes the VM. Regular local-disk file I/O is a
different class, and Gambit's NATIVE I/O facilities are already used inside
the runtime itself (the boot loads Scheme sources from disk; the P1e probe
wrote fixture files via Gambit I/O without issue). So the design key:

- Prefer GAMBIT-NATIVE I/O (call-with-input-file, open-input/output-file,
  with-exception-continuation etc.) for the store's file operations — no FFI
  at all where native I/O suffices. This sidesteps the C4 question entirely
  for most rows.
- Only where native I/O cannot express the operation (e.g. flock-style
  locking, fsync-class durability if the store needs it, realpath) consider
  new FFI rows — and each must be genuinely non-blocking (regular local
  files; document why per row). A row that requires GC-unsafe blocking gets
  REFUSED with the documented message and the finding states it — same
  honesty rule as always.

## What the durable open path needs (from the P1e static trace — verify each)

src/cljc/dao/space/store.cljc:98-126, 225-247, 281-309 (durable open: check
directory, acquire store lock, open content log); the Jolt lock uses
dao.posix.file/realpath then lock-file! (src/cljc/dao/space/store/fs.cljc:
181-188, 222-226, 246-266); HEAD read/write and directory sync behind the
overlay refusal (host/gambit/yin/dao/posix/file.jolt:6-25). Map EVERY
operation the durable path touches: realpath, lock-file!/unlock, content-log
create/append/read, HEAD get/set, directory sync/flush. For each: native-I/O
mapping or new-row justification or honest refusal.

## Work dir

Worktree off gambit-ffi-p1f (the DHT slice):
cd ~/workspace/jolt && git worktree add /Users/sto/workspace/jolt-ffi-p2 -b
gambit-ffi-p2 gambit-ffi-p1f. Commit there; never push; nothing outside the
worktree. Gambit binaries via the absolute mise paths; read-only references
allowed elsewhere.

## Acceptance

- `bb yin-repl --backend jolt-gambit --index-store file:<dir>` opens a
  durable file store, put/get survives a session restart (persistence
  proof), state.edn resume works.
- `--index-store dht:<dir>` opens the durable-backed DHT handle (per the
  DHT composition: dao.space.store/open then node join), put/replicate/get
  works against the durable store, session survives.
- Restart persistence proof: session 1 writes; session 2 (new process) reads
  back the same values from both file: and dht: stores.
- The store lock: concurrent-second-launch behavior verified (a second
  launcher on the same dir must get the documented lock refusal, not
  corrupt).
- Stage-1 + P1f matrix green (gambitffi, gambitcheck, gambitkernel,
  gambiteval, gambityinrepl, gambitsourceload, seed/gen, emittedheads,
  deadhost) — nothing regressed.
- New gates: durable-store persistence rows added to the session corpus or a
  new `gambitdht-durable` gate; state-derived verdicts, watchdogged.
- Capability notes updated honestly: the startup/--help text must now say
  file:/dht: durable stores work (no longer "Local --index-store mem
  session").

## C4 constraints (still binding)

No GC-unsafe blocking FFI. No foreign-thread callbacks. Any operation that
cannot be done without them gets the documented refusal and a finding entry
— with the analysis of why native I/O could not express it.

## Deliverables

Findings: /Users/sto/workspace/datomworld/collab/jolt/1795240000000-engineer-p2-durable-dht-gambit.gpt-6.2-sol.findings.md
(standard header; the operation→implementation map; persistence proofs
verbatim; the capability-note before/after; ends DONE). Final response:
Completed-* header + per-operation map summary + gate line.
