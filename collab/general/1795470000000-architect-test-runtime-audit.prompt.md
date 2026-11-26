Created-GMT: 2026-11-23 12:4x GMT
Created-Local: 2026-11-23 19:4x +0700
Dispatched-By: master-green orchestrator seat (interactive; owner-directed consult)
Destination: codex exec -m gemini-4-argon-high on jupiter (ARCHITECT lane)
Task-ID: 1795470000000-architect-test-runtime-audit

# ARCHITECT AUDIT: full-suite wall time — where it goes and how to cut the 80%

Owner directive: "tests taking too long is a big problem preventing fast iterations…
a full audit needs to be done (may have been done before)… then speed up the tests
taking 80% of the time." You are the architect lane; a codex engineer round runs in
parallel on empirical attribution. Read-only; findings file only.

## Measured wall-time evidence (nightly run 20261123-0018, jupiter, master `e4f2cb89`)

Quick tier (sharded by subsystem, 48-core host, par 12):
- uncovered: 12,419s / 489 tests / 14,087 assertions — THE critical path (total quick wall 12,472s)
- yang.python: 1,468s / 287 tests; yin.repl: 964s / 411; yang.base: 734s / 203;
  yin.vm: 576s / 1,308; yin.vm.ucf: 112s; dao.space: 107s; everything else < 30s
Slow tier: 60+ namespaces, one fresh JVM each (namespaces derived from ^:slow tags)
Node/dart (shiva): cljd compile-all 377s + shard run; node lane similar shape.

## Read first

1. `docs/design/datom.world.md` — architecture, invariants
2. `docs/agents/build-n-test.md` — verification modes, the non-weakening merge bar
3. `src/dev/nightly.clj`, `bb.edn` test tasks, `~/ci/shard-jvm.sh` (jupiter) — how lanes partition
4. Prior audit if one exists: search `collab/`, `docs/`, `orchestrator-logs/` for
   earlier test-runtime/perf audits and reuse their baselines rather than re-deriving.

## Deliverable

Findings file `collab/general/1795470000000-architect-test-runtime-audit.gemini-4-argon-high.findings.md`:

1. **Audit**: full decomposition of wall time across quick/slow/node/dart tiers; why the
   `uncovered` lane takes 3.5h for 14k assertions while `yang.base` does 93k in 12min
   (fixture-driven? cross-node process orchestration? sleeps/timeouts? serial structure?);
   whether prior audit findings exist and which were applied/dropped.
2. **The 80% plan**: concrete, ranked interventions for the top time sinks with
   expected savings and risk per item (e.g. sharding the uncovered lane by namespace,
   process-pool reuse, timeout/sleep audit, JVM sharing strategies, lane re-partitioning).
3. **Non-weakening bar**: for each intervention, state what evidence strength is preserved
   (the isolated cold-JVM AND subsystem-order modes both stand; no skips, no omissions).
4. **Verification**: how each change proves itself (before/after wall times on the same SHA).

Read-only: no source edits, no test runs beyond reading recorded evidence; name any
scoped probe command you need and the orchestrator executes it. An engineer round
(codex terra) runs in parallel — its findings land in the same collab directory; treat
its output as a peer input, not authority.

Begin the final response exactly with:
Completed-GMT / Completed-Local lines.
