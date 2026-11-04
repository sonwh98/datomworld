Session-ID: 9c4e7a12-6d83-4b25-9e51-2f84c0d7b645

# J6g diagnostic round (argon tier): why does peer B starve and A stall under workload W?

Role: diagnostician (agy gemini-4-argon-high). This is a HARD PROBLEM
round — the terminal-state rule escalates it to you after two failed
implementation rounds. Read-only on the adapter: NO code changes to
src/jolt/** or src/cljc/** — your deliverable is the diagnosis and, if the
root cause is in the harness, the harness fix. Edits only; the
orchestrator verifies remotely.

## The measured facts (both runs on jupiter, master current)

- Attempt 18 (agy's unguarded harness): server listening, peer B upgraded,
  peer A upgraded (frame 4,194,318 bytes), "Entering owner round loop..."
  — then 100% CPU spin for 69 minutes, no output flush, no completion.
  Killed rc=143 (4,165 s).
- Attempt 21 (codex's guarded harness): the watchdog terminated at exactly
  the 5-minute hard cap with `:hard-cap`, `p99 ABSENT`, `sample count 70`,
  A's transfer stalled. B's round trips starve (70 samples vs ≥600)
  AND A's transfer makes no progress.
- Codex's static reads (its fix round) say: the owner polls the adapter
  after stepping; the pump visits ready descriptors in rotation, at most
  one read chunk per visit. The runtime evidence contradicts that —
  neither A's transfer nor B's round trips progress.

## The question

Why do BOTH peers stall while the process spins at 100% CPU? Candidates
the evidence leaves open (you may find others):

1. The pump's visit rotation starves one descriptor (fairness) — but at
   one chunk per visit, B's 1 KiB exchanges should still progress.
2. The 4 MiB message's decode fires synchronously in the composition
   (plan v3 §2 P2: codec decode enters the round) — if A's final chunk
   decode runs on the owner thread every visit, the round time explodes
   and B's deadline is missed.
3. The harness's peer A write loop spins without ever completing the
   write (its own :would-block handling), consuming the single owner
   thread — a harness defect wearing the adapter's face.
4. The driver's sleep-fds!/poll deadline logic (R8/R9) never wakes for
   the descriptors that matter.

Read: src/jolt/dao/stream/ws/pump.jolt (visit rotation, read chunking),
src/jolt/dao/stream/ws/jolt.jolt (the echo callbacks, :ws/max-frame-bytes
handling), src/cljc/yin/repl/main.cljc's owner round, and the harness
(src/dev/jolt_workload_w.clj + jolt_workload_w_run.clj). State the root
cause with file:line, and whether it is adapter, composition, or harness.

## Deliverable

- The diagnosis (root cause, file:line, and the mechanism).
- If the root cause is the HARNESS: fix it in place.
- If it is the ADAPTER or COMPOSITION: a written ruling proposal (the
  exact change, where, and why it preserves A1-A7) — the orchestrator
  routes it to the Architect for a ruling before any edit.
- If it is the CRITERION itself (workload W is unsatisfiable as
  pre-registered): say so explicitly with the mechanism.
- cljstyle clean on anything you touch; no collab/ path in comments.
