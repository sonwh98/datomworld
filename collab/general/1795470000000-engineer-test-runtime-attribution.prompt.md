Created-GMT: 2026-11-23 12:4x GMT
Created-Local: 2026-11-23 19:4x +0700
Dispatched-By: master-green orchestrator seat (interactive; owner-directed consult)
Destination: codex exec -m gpt-6.1-terra on jupiter (ENGINEER lane)
Task-ID: 1795470000000-engineer-test-runtime-attribution

# ENGINEER: empirical attribution — why do the hot lanes take so long?

Owner directive: speed up the tests taking 80% of wall time. An architect round
(gemini-4-argon) runs in parallel on strategy; YOUR job is measured attribution, not
opinion. Read-only except for running scoped profiling commands and writing findings.

## Known hot spots (nightly 20261123-0018, jupiter, master `e4f2cb89`, 48 cores)

- `uncovered` shard: 12,419s wall / 489 tests / 14,087 assertions — the critical path.
  For contrast: yang.base = 734s for 93k assertions; dao.data = 17s for 159k assertions.
- yang.python shard: 1,468s / 287 tests. yin.repl: 964s. Slow tier: 60+ single-JVM lanes.
- Startup overhead floor: every JVM lane pays toolchain+compile (est. 15-30s/lane).

## Mission

In your OWN worktree clone on jupiter (`/tmp/perf-attribution`, base origin/master):

1. Identify which namespaces/deftests compose the `uncovered` shard (read
   `src/dev/nightly.clj` / the shard script's partition logic), then time them at
   reduced scope: per-namespace isolated runs with wall-clock stamps, or a cheap
   per-test harness. Attribute the 12,419s to: (a) specific namespaces/rows,
   (b) fixture setup vs assertion work, (c) sleeps/timeouts/polling, (d) process
   spawn/teardown (cross-node style fixtures), (e) JVM startup amortization.
2. Same pass, lighter touch, for yang.python and yin.repl shards.
3. Check prior audits first (grep collab/ docs/ orchestrator-logs/ for test-perf
   audits) — reuse, don't re-derive; note which prior recommendations were applied.
4. Constraints: run at most ~6 scoped test invocations (they're expensive — that's
   the point of this audit); prefer `-n` namespace-level timing over per-deftest
   instrumentation unless a namespace alone exceeds ~10min; NEVER run
   yang.call.exception-contract-test; no full-suite runs (a nightly is already
   in flight on this host — your worktree is separate, CPU contention acceptable
   on 48 cores but keep concurrency <= 4).

## Deliverable

Branch-less; findings file in your worktree at
`collab/general/1795470000000-engineer-test-runtime-attribution.codex-gpt-6.1-terra.findings.md`
(every command run with wall times; a ranked table: lane/ns → seconds → attributed
cause → concrete fix candidate → expected saving). The orchestrator synthesizes with
the architect round. Never merge to master yourself.

Begin the final response exactly with:
Completed-GMT / Completed-Local lines.
