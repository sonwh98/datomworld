Created-GMT: 2026-11-01 04:05:00 GMT
Created-Local: 2026-11-01 11:05:00 +07
Coding-Agent: interactive
Session-ID: sess_b6001731-427a-4431-b74f-97f348558fc2

# Task: diagnosis review — why the subsystem-sharded Node lane stalls (memory vs something else), and is a per-shard bundle the right fix?

Role: independent diagnostician (Codex, gpt-6.1-sol, read-only). You have SSH access
to both hosts (verified working). Do not write files on them; read and measure only.

## What we are trying to do

Run the ClojureScript test suite on one host, sharded by subsystem, so shards run
CONCURRENTLY. Driver: src/dev/node_shard.clj (babashka) — one shadow-cljs compile of
the `test` build (`--config-merge "{:autorun false}"`), then up to PAR `node
target/node-tests.js --test=<comma-separated namespaces>` processes in parallel waves.
Selection is runtime (`--test`) because shadow's compile-time `:ns-regexp` does not
remove previously cached test namespaces from the bundle.

## The failure, with measurements

Host shiva.stigmergy.systems (24 threads, 62 GB). Run started 16:09 local, killed at
22:41 (6 h 29 m). Six shard processes, each `node target/node-tests.js --test=...`.

Observed at the time of the kill (please re-measure or take my numbers, they are from
/proc and ps):
- Each process: RSS ~3.1-3.3 GB, %CPU 114-115 (i.e. more than one core each), elapsed 6h29m.
- Log files stopped growing at 19:58 (2.7 h before the kill). Log sizes sit exactly at
  122880 / 131072 bytes — buffer boundaries — and each ends mid-line inside a huge
  Clojure value dump (`{:type :closure}` maps, `:py/uninit` windows, etc.).
- 60-second sample: total log bytes 753664 -> 753664 (ZERO growth) while one process's
  CPU time advanced 07:27:11 -> 07:28:23 (72 s consumed).
- Host memory at the time: 41 GB used of 62, 1 GB free, 20 GB buff/cache, swap 1 GB full.
- Node's default heap limit is ~4 GB on x64 (v8.getHeapStatistics().heap_size_limit).

Earlier, single-process (monolithic) Node runs of the same suite also appeared to "hang":
one took 30,297 s (8.4 h) and finished; another was killed after 10 h+ at ~120% CPU with
no output progress.

## My working diagnosis (verify or refute)

The bundle `target/node-tests.js` contains ALL ~274 test namespaces, so each
`--test=<subset>` process must load and hold the ENTIRE bundle in memory (~3.2 GB), and
6 such processes therefore need ~19 GB. Each is near Node's ~4 GB heap ceiling and spends
its time in GC rather than executing — hence 114% CPU with zero output progress. Under
this theory parallelism is MEMORY-bound, not CPU-bound, and PAR must not be raised.

Counter-hypotheses I could not rule out: (a) a genuine infinite loop/spin in a specific
test (the yang.python tests emit multi-KB VM dumps that fill logs), (b) shadow-cljs's
node-test runner holding references to all test vars and their generated data, (c) the
`--test` selector's own bookkeeping on a large bundle, (d) an interaction with the huge
`:ns-regexp`-less bundle plus `DATOM_SLOW_TESTS=1` enabling very long bodies.

## What I need from you

1. VERDICT on the diagnosis: is this memory-bound GC thrash, or a spin/hang in specific
   tests? Cite evidence you gathered (e.g. `/proc/<pid>/status` VmRSS, VmHWM, a sample of
   CPU time vs output growth, `node --v8-options | grep max-old-space`, the bundle's size,
   and what fraction of the bundle is test namespaces vs library code). If you can
   reproduce cheaply, do; if not, say what you could not verify.

2. Is the proposed fix sound? Compile ONE SMALL BUNDLE PER SHARD instead of one big one:
   `clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge
   "{:ns-regexp \"^(ns1|ns2)\" :cache-root \".shadow-cljs-shard-<s>\" :output-to
   \"target/node-tests-<s>.js\" :autorun false}"`. Specific questions:
   - Does shadow-cljs honor `:cache-root` and `:output-to` through `--config-merge` on
     build `:test` (check the shadow-cljs docs/source or a cheap experiment)?
   - Does a per-shard `:cache-root` really prevent the cached-namespace leakage we saw
     (where `:ns-regexp` alone left every previously compiled namespace in the bundle)?
   - Would each resulting bundle then contain only that shard's namespaces, and does the
     runner still work with `--test=` inside such a bundle?
   - Cost: how long is one such compile warm vs cold, and is 14 of them acceptable versus
     the memory win?

3. If you think the per-shard bundle is wrong or insufficient, name the better approach
   (e.g. `--max-old-space-size` tuning, fewer workers, splitting the bundle by :modules,
   running shards across BOTH hosts, or a different runner) and why.

4. Anything that makes the measured 30,297 s / 8.4 h monolithic Node time and the 10 h+
   no-progress runs EXPLAINABLE (are those the same phenomenon?).

Reply via stdout with evidence; the orchestrator promotes the artifact. A verdict of
"diagnosis refuted, cause is X" is as welcome as confirmation — do not agree to be
agreeable.
