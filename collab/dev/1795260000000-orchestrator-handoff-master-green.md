Created-GMT: 2026-11-07 07:3x GMT
Created-Local: 2026-11-07 14:3x +07 (+0700)
Coding-Agent: interactive (ZCode / glm-5.3-flash, yang.python orchestrator seat — OUTGOING for master-green)

# HANDOFF — master-green recovery: you own it; the yang.python seat stays on yang.python only

Owner directive (2026-11-07): the yang.python seat refocuses on its track's own surfaces; a separate orchestrator (you) owns getting master green again. This dossier is everything you need.

## The situation

Nightly run **20261106-0944** on shiva (pinned `8d0a4ffb`, Nov 6 — predates the exception track) was RED on both hosts:

| Lane | Reds | Cause (diagnosed) |
|---|---|---|
| yang.python (node) | 7F / 4 distinct | (1) manifest-address-test: stale inline golden — FIXED; (2) recursion-error ×2 + generator-rebase: Node V8 stack (984 KiB default) exhausts before the guest 1000-frame guard — FIXED |
| yin.repl (node) | 8F / 3 distinct | (3) php frontend genuinely missing from the Node composition — FIXED (this also caused (4)); (4) `(compile ...)` "Unexpected EOF" — failed php selection read as Clojure — FIXED by (3); (5) dht ack collapse: 512-key index pages exceeded the 64 KiB DHT message bound — FIXED (64-key pages) |
| yang.base (node) | 1F | stream_eval_test.cljc:444 — dao.stream memory-log manifest addresses differ ACROSS VMs for identical content — NOT FIXED, relayed to the jolt seat (collab/jolt/1795240000000-yangpython-relay-stream-eval-red.md); the same canonicalization pattern as fix (1) may apply |
| uncovered (node) | 3E | string-forms RangeErrors — same family as (2), expected covered by the stack fix, unverified |
| dart | 7F | detail NEVER CAPTURED (the lane also hit its 30000s wall) — a dedicated dart run with retained output is required; may share the manifest/stack families |

## The fix branch: `nightly-reds-fix` (PUSHED, tip `4f772d61`)

Five commits, gpt-6.1-sol round (thread 01a1b4b7; log collab/yang-python/1795220000000-engineer-nightly-reds-fix.gpt-6.1-sol.stdout.log; report in the f12 worktree collab/master-green/):

1. `21350ec5` — the stale golden corrected to `d9aead51…` + iteration-order checks (linked_prelude_test.cljc:229)
2. `0053411a` — the node-test target re-execs with `--stack-size=6144` (shadow-cljs.edn:20, the :prepend trick; covers autorun and shard invocations)
3. `28bdc3d0` — the php frontend installed on Node (deps.edn :cljs gains `build/antlr/php/js`; `yang/php/antlr/cst.clj` → `cst.cljc`; frontends.cljc catalog)
4. `83d41b55` — the compile-EOF regression pin (repl_test.cljc:186)
5. `4f772d61` — the DHT-backed REPL index pages capped at 64 keys (yin/repl/dht.cljc:351)

Local JVM verification by the round: 20 tests / 270 assertions green across eight namespaces; the golden generator ran twice byte-identical. The exception-track files are untouched by the branch.

## WHERE I LEFT IT — the open verification problem (your first task)

I ran the scoped node-shard verification on shiva (`~/ci/datomworld-nightly-node` @ 4f772d61, `bash ~/ci/node-shard.sh ~/ci/datomworld-nightly-node 12`). It FAILED SPURIOUSLY, twice, including once after `rm -rf .shadow-cljs`:

- The v3 runner's compile step "succeeds" (rc=0, ~57s) but the produced `target/node-tests.js` bundle knows only ~a handful of namespaces — the R1 inventory check then correctly refuses every shard whose namespaces the bundle does not declare ("requests namespaces absent from the compiled bundle"), yang.python's shard runs 0 tests, and the run dies at "expected 14 shards, completed 1".
- The bundle-list output (node-shards-20261107-0210/bundle-list.txt) starts at `dao.await-test` — note `dao.data-test` appears while the dao.data shard's fine-grained namespaces do not; the list looks TRUNCATED at 5187 lines. The compile log shows "Build completed. (636 files, 635 compiled, 1 warnings, 45.71s)" — one :invalid-arithmetic warning (dao.jing number print forms, harmless-looking).
- SUSPECT: the runner's `--list` invocation or the bundle is being cut short (a crash mid-list with a nonzero-but-swallowed exit? output buffering? the R1 regex parsing the truncated list?). The nightly's own runs (e.g. 20261106-0944) did NOT hit this at the same commit lineage — so either my invocation differs from the nightly driver's (compare `src/dev/nightly.clj`'s node-lane path — the cron runs `bb src/dev/nightly.clj ... node,dart`, which may set config the bare node-shard.sh does not), or the branch's `:prepend` re-exec breaks the `--list` mode (the re-exec spawnSync runs on EVERY node invocation including `--list`; if `--list` output is expected on stdout, the prepend may be interfering — CHECK THIS FIRST: run `node target/node-tests.js --list | wc -l` manually and look at the head/tail of the output).
- The evidence dirs: `~/ci/datomworld-nightly-node/target-ci/node-shards-20261107-0205` and `-0210`; my runner logs `/tmp/nrf-shard.log`, `/tmp/nrf-shard2.log`.

If the prepend is the culprit, the minimal fix is to guard the re-exec so `--list` passes through without spawning a child (or move the stack raise to the shard script's node invocations instead of the bundle).

## The remaining program (yours)

1. Resolve the bundle/--list problem above; get the scoped node-shard verification green on 4f772d61.
2. Merge `nightly-reds-fix` to master when the node shards agree green (each fix already carries its focused pin; the review bar is the runner's own R6 accounting).
3. The dart lane: run a dedicated dart pass with RETAINED output (the nightly's summary lost the 7 failure names), on master-after-merge or the branch; triage whether the failures share the manifest/stack families; dispatch fixes accordingly.
4. The yang.base stream_eval red: the jolt seat was relayed (see above); chase their diagnosis or take it over with them — it is on the master-green critical path per the owner.
5. Re-run the full nightly (the cron at 04:00 VN fires `bb src/dev/nightly.clj ... node,dart` on its own; `~/ci/nightly-manual.sh` exists for manual runs) and confirm master green end-to-end before declaring it to the owner.

## Operational notes

- Hosts: `ssh shiva.stigmergy.systems` (jupiter exists for comparison runs; the owner uses darkstar locally). The owner's rule stands: no slow tests locally on the macOS machine.
- The nightly clones: `~/ci/datomworld-nightly{,-node,-dart}`; results land in `~/ci/nightly-results/<stamp>/`; per-shard evidence in the clone's `target-ci/node-shards-<stamp>/`.
- The exception track is PAUSED and is now a separate orchestrator's problem (the handoff: collab/exception-track/1795250000000-orchestrator-handoff-dossier.md) — do not dispatch into it; its ^:slow-gated red suites on master are EXPECTED and not yours to fix.
- The yang.python seat keeps its track surfaces; if your fixes touch yang.python fixtures/prelude (fix 1 did), coordinate through a collab note rather than diverging — the branch already carries those commits, so the coordination point is only for anything NEW.

— the yang.python orchestrator seat
