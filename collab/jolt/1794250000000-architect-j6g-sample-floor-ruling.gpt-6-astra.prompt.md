Session-ID: d71e94b2-3c56-4f87-a912-0b8d6e4f5a23

# J6g Architect ruling request: the workload W criterion's sample floor vs the measured owner-round cadence

Role: Architect (gpt-6-astra). The workload W criterion FAILED in its first
clean instrumented run, and the terminal-state rule escalates the remaining
questions to you rather than another blind implementation round.

## The measured run (j6ggate23, jupiter, master current, the ruled fix (a)
incremental index materialization in place)

- Peer A: 4 MiB delivered exactly once ✓; A's window opened.
- Peer B: 68 round trips in the window, all completed except 1 outstanding.
- Peer B round-trip p99: 41.1 ms — WELL INSIDE the ≤ 250 ms criterion.
- Per-round step advance: FALSE — the REPL step count did not advance every
  owner round.
- The submitted REPL loop `(loop [i 0] (if (< i 5) (recur (inc i)) i))`
  produced ZERO verified results across all rounds (loop-results 0).
- The index materialization cursor reads NIL at
  `[:repl :indexer :materialization :cursor]` in every round.
- The run is deterministic: attempt 22 and attempt 23 both produced exactly
  68 samples. Not contention noise.

## The context

- The ruled fix (a) (incremental index materialization, ruling filed:
  collab/jolt/1794006000000-architect-j6g-ruling.gpt-6.1-sol.ruling.md)
  cut peer B's p99 from ~7.9 s to ~41 ms — the headline starvation is
  FIXED. What remains are the sample-count floor, the step-advance
  verifier, and the cursor path.
- B dispatches at most one request per returning owner round. The owner
  round is ~3.9 s (68 rounds in ~267 s of window). The pre-registered
  criterion requires ≥600 qualifying round trips (≥500 floor) with 8
  outstanding at 10 Hz — which assumes the owner loop returns ~10+ times
  per second. It does not: it returns ~0.25 times per second under this
  composition.
- Files: the harness is src/dev/jolt_workload_w.clj +
  jolt_workload_w_run.clj; the REPL owner round is
  src/cljc/yin/repl/main.cljc; the composition state the harness reads is
  src/cljc/yin/repl/index.cljc (the incremental materialization, cursor
  under :materialization).

## What the ruling must settle

1. **The sample floor vs the owner-round cadence.** The 600-sample /
   500-floor requirement was pre-registered assuming a fast owner loop.
   The measured owner round is ~3.9 s, so the 300 s window yields at most
   ~77 qualifying round trips at one per round. Rule: is the floor met by
   (a) lengthening the window proportionally (the same 10 Hz offered load,
   a longer A transfer), (b) counting every in-window round trip B
   completes (not only those in A's window), (c) lowering the floor to
   what the composition can honestly produce (with the number justified
   statistically), or (d) declaring the criterion unsatisfiable as
   registered and re-registering it? Rule on the statistical validity of
   whichever you choose (is 68-77 samples at p99 41 ms enough to bound the
   tail at the confidence the criterion wants?).
2. **The step-advance verifier and loop-results 0.** The submitted loop
   never verifies as completing. Is that (i) a harness verifier defect (it
   checks a shape the driver's outbox never produces under this
   composition), (ii) a real REPL integration failure (the submitted
   program genuinely never evaluates), or (iii) an artifact of the 4 s
   owner round (the loop needs more rounds to complete than the window
   gives)? Read the code and rule which, and what the verifier should
   assert instead.
3. **The cursor path.** `[:repl :indexer :materialization :cursor]` reads
   nil in every round while the transactions counter advances. Read
   src/cljc/yin/repl/index.cljc's materialization state shape and rule
   whether the telemetry path is wrong, the cursor is genuinely unset, or
   the incremental path is not engaged (and if it is not engaged, whether
   the p99 improvement came from the cursor-less partial implementation —
   and what that means for the ruled fix's validity).

## Constraints

- The adapter and parser remain frozen (A1-A7); the p99 ≤ 250 ms and
  delivered-once requirements stand regardless of your floor ruling.
- No code edits in this round; the deliverable is the ruling with the
  numbers justified.
- If the honest answer is "measure more first" (e.g. the cursor question
  needs a probe run), prescribe exactly that probe.

## Deliverable

The ruling to stdout (the orchestrator files it into collab/jolt/): the
floor decision with its statistical justification, the step-advance
verifier's correct assertion, the cursor disposition, and the resulting
pass/fail definition for J6g. Final line: the J6g gate's new pass
condition in one sentence.
