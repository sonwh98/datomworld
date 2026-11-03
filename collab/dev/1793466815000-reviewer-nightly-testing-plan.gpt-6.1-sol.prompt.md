Created-GMT: 2026-10-31 17:13:35 GMT
Created-Local: 2026-10-31 24:13:35 +07
Coding-Agent: interactive
Session-ID: sess_b6001731-427a-4431-b74f-97f348558fc2

# Task: review the distributed nightly testing plan (two-host split, subsystem sharding, commit pinning)

Role: independent reviewer (Codex, gpt-6.1-sol, read-only)

## Context

The repo runs a nightly test suite on two Linux servers. Both are owned by the
user; both run the full tri-host matrix (JVM/Node/Dart lanes of the same
Clojure/ClojureScript/ClojureDart codebase). Hosts:

- shiva: Threadripper 2920X, 12C/24T @3.5 GHz, NVMe, 62 GB
- jupiter: Threadripper 3960X, 24C/48T @4.57 GHz, NVMe, 62 GB; 2 idle QEMU guests

Measured 2026-10-31 (same commit, JVM quick tier sharded by subsystem):
jupiter 1,038 s (12 workers) vs shiva 1,325 s (8 workers); per-shard 20-28%
faster on jupiter. Node full tier (incl. slow): ~30,297 s on shiva (serial,
monolithic). Monolithic Node on jupiter: >10 h and still running (killed as
obsolete). Dart full: unmeasured end-to-end.

## The plan as implemented (review this)

1. **Lane-to-host split** (owner-ratified): JVM sharded lane runs on jupiter;
   Node and Dart lanes run on shiva. Rationale: per-lane measured efficiency
   (JVM 22% faster on jupiter; jupiter's monolithic Node anomalously slow),
   not total-load balancing. Both fire in the 03:00 Vietnam window (hourly
   cron with an in-script TZ guard).
2. **Subsystem sharding, all three lanes**: test namespaces are grouped by
   subsystem from `src/dev/subsystems.edn` (the registry `bb test:sub` uses).
   - JVM: existing `shard-jvm.sh` — one `clojure -M:test -n <ns>...` JVM per
     subsystem, 8-12 in parallel via xargs.
   - Node (NEW, verified live today): ONE full shadow-cljs compile
     (`--config-merge "{:autorun false}"`), then one
     `node target/node-tests.js --test=ns1,ns2,...` process per subsystem,
     6 in parallel. Runtime selection is required because shadow-cljs's
     compile-time `:ns-regexp` does NOT remove previously cached test
     namespaces from the bundle (empirically verified — the cache
     accumulates them). Partial results from the first full validation run:
     10 of 14 shards green (dao.stream 528 tests in 66 s; yin.vm.ucf 608
     tests in 625 s); 4 heavy shards still running.
   - Dart (NEW, verified per-subsystem): one full cljd compile, then per
     subsystem a shard-directory emitted by `cljd_agg.clj --only <nss>
     --shard-dir build/cljd-agg-<s> --no-compile --no-run` (new flags), and
     one `flutter test <dir>` process per subsystem, 4 in parallel.
3. **Coverage guarantee per lane, checked every run**: each script
   enumerates every test namespace on disk for its lane, diffs against the
   union of subsystem lists, and runs the leftover as an explicit `uncovered`
   residual shard. Last night: JVM all=316=276+40; Node all=274=257+17.
4. **Commit pinning across hosts** (NEW): shiva resolves origin/master first
   (its 03:00 fetch), writes the SHA to `~/ci/nightly-pin.txt`, and checks
   out that SHA. Jupiter reads the pin over SSH (retries for up to 3 min),
   verifies the object exists, and checks out the SAME SHA; on unavailability
   it falls back to its own origin/master resolution and marks its summary
   "unpinned". Host-to-host SSH verified working both directions.
5. **Summary sync** (NEW): when a host's lanes finish, its summary file is
   pushed to the other host (with a mkdir -p guard), so each host's
   nightly-results holds both hosts' summaries. Best-effort: a sync failure
   is noted in the summary, never fails the run.
6. **Known open items stated up front**: (a) shiva's Node and Dart lanes are
   still MONOLITHIC in the nightly — node-shard.sh and dart-shard.sh are
   deployed and validated but not yet wired in, pending the sharded Node
   wall-time number; (b) monolithic Node on shiva took 8.4 h, so the current
   window is bounded by it; (c) the Node sharding discovered that the slow
   tier's `:ns-regexp` compile filter has been silently running the FULL
   cached bundle on warm clones (see 2 above) — a latent test:slow:cljs
   inefficiency, not yet fixed upstream in bb.edn.

## Review questions (please answer each explicitly)

R1. Coverage: does the all-vs-union diff + residual-shard mechanism actually
    guarantee every test namespace executes on every lane, or are there gaps
    (e.g. parameterized/derived test namespaces, namespaces generated at
    build time, reader-conditional-only lanes)?
R2. Concurrency soundness: the Node design serializes compiles and
    parallelizes 6 node processes over ONE clone (shared target/, shared
    .shadow-cljs compile cache, distinct --test selections). What races or
    shared-state hazards remain (port binding, fixture files, dao.stream
    loopback endpoints, shadow-cljs server mode)?
R3. The Dart design emits per-subsystem shard dirs from a single full
    compile, then runs flutter processes in parallel. Same question — plus:
    is cljd's lib/cljd-out safe to read concurrently while nothing compiles?
R4. Commit pinning: is the shiva-authoritative pin + 3-minute retry +
    unpinned-fallback sound? What failure modes does the fallback hide, and
    should an unpinned run be treated as advisory?
R5. The lane-to-host split optimizes per-lane efficiency, not load balance
    (shiva: ~8.4 h serial Node + Dart; jupiter: ~17 min JVM). Once Node and
    Dart are sharded, both hosts' walls shrink — is there a better split we
    should move to, and what should be measured before re-splitting?
R6. Anything in this plan that would corrupt or lose test EVIDENCE (logs,
    summaries, attribution) that the repo's workflow depends on for gate
    decisions?

Verdict format: per-question PASS/CONCERN/FAIL with file-or-command-level
evidence, then an overall READY / READY-WITH-CONCERNS / REVISE for wiring
node-shard.sh and dart-shard.sh into the nightly scripts.

Your findings artifact: write nothing — reply via stdout; the orchestrator
promotes it to collab/.
