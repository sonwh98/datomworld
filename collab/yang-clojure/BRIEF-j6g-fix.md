# J6g fix round: the workload W run hangs — attribute, guard, and re-measure

(Re-pasted from the orchestrator's dispatch; the agy rounds died twice on
this unit, so this fix round is dispatched to codex per the terminal-state
rule's escalation.)

## What happened (the orchestrator's verification run, jupiter, j6ggate18)

The workload W harness compiled and ran: server listening, peer B
connected and upgraded, peer A connected and upgraded, frame length
4,194,318 bytes, "Entering owner round loop..." — then 100% CPU spin for
69 minutes, no output flush, no transfer completion. Killed at rc=143.

The pre-registered criterion (BRIEF-j6g.md, unchanged): A's 4 MiB message
delivered once within a ~60 s window while B samples p99 ≤ 250 ms with
≥ 600 samples. Nothing measurable was produced.

## Your tasks

1. **Attribute the spin.** Two hypotheses — distinguish them with reads
   (you cannot run anything):
   (a) a harness defect: `write-all!`'s retry loop (`poll-one fd pollout
   2000` then `send-chunk!`) spins if poll returns writable while send
   answers :would-block, or if the loop never advances off 0; and there
   is no max-runtime guard, so any stall hangs forever.
   (b) a real composition finding: the REPL's owner round serves peer B
   but never drains peer A's 4 MiB transfer (the exact starvation
   workload W exists to expose).
   Read src/dev/jolt_workload_w.clj's write-all!/run-workload-w loop and
   the driver's owner round in src/cljc/yin/repl/main.cljc (the
   sleep-fds! + step path). State which you conclude, with file:line.
2. **Fix the harness defects** regardless of (b):
   - a max-runtime guard: the whole workload terminates with a FAILURE
     report (p99 absent, samples counted, elapsed) after a hard cap
     (5 minutes), never hanging;
   - the write-all! loop gets a no-progress guard: N consecutive
     :would-block polls without a byte advancing = transfer stalled →
     report and abort the transfer leg (this is data: the criterion
     failing, honestly measured);
   - the sampler flushes its running p99/sample-count to stdout every
     10 s so a stuck run leaves evidence.
3. **If (b) is your conclusion** (the adapter starves A): do NOT patch
   the adapter — report it as the finding with the reads that support it.
   The Architect rules on adapter changes.
4. cljstyle clean; no collab/ path in comments.

## Reporting

Write BRIEF-j6g-fix-findings.md in this workspace with: the attribution,
the guards added, and what the next verification run should show under
each hypothesis.
