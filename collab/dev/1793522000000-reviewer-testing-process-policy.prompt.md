Created-GMT: 2026-11-01 07:05:00 GMT
Created-Local: 2026-11-01 14:05:00 +07
Coding-Agent: interactive
Session-ID: sess_b6001731-427a-4431-b74f-97f348558fc2

# Task: review the testing process we just built — give your opinion

Role: independent reviewer (Codex). Read-only. You may SSH to
shiva.stigmergy.systems and jupiter.stigmergy.systems to inspect artifacts; do not
modify anything, do not start or kill runs.

Review the PROCESS, not line-by-line code (you already reviewed the harness twice).
The owner has set a policy and we implemented it; the question is whether the policy
+ mechanism is sound, what it risks, and what you would change. Do not agree to be
agreeable — a well-argued "this is wrong because X" is the most useful output.

## The policy (owner ruling 2026-11-01, implemented)

1. **Merge gate = only the tests covering the feature being built.** No full-suite
   gate per merge. Concretely: `bb test:changed` (reverse-require closure of changed
   files + fixtures + walkers) for a slice-sized change; wide changes still route to
   the full quick lane; foundation-code changes run broad selected JVM feedback.
2. **The full suite runs exactly twice a day: 03:00 and 17:00 Asia/Ho_Chi_Minh**,
   on the two remote hosts, implemented as an hourly cron plus a Vietnam-time guard
   inside `src/dev/nightly.clj` (so DST on either host cannot move it).
   Rationale: 03:00 catches the day's work before the owner stops coding; 17:00 gives
   a result they can act on before sleeping. The owner accepts ~12h worst-case latency
   between a break landing on master and being seen.
3. **Lane split by host**: jupiter runs the JVM lane (quick sharded + full slow tier);
   shiva runs the Node and Dart lanes. Both checkout the SAME pinned SHA (`--pin-publish`
   on shiva, `--pin-read --pin-peer shiva` on jupiter), run the same versioned drivers
   from `src/dev/` (no host-local scripts), and each writes a per-run immutable
   directory.
4. **Sharding**: tests grouped by subsystem from `src/dev/subsystems.edn` (longest
   prefix), plus an `uncovered` residual shard so a registry omission cannot silently
   reduce coverage; Node runs one `--test=<list>` process per shard in a work-stealing
   pool (PAR 12); Dart compiles once and runs ONE `flutter test` over per-subsystem
   shard dirs (shared `build/test_cache`).

## The measurements the policy rests on (verify or challenge)

- Slow tier, 52 namespaces, one complete run: **5,645 s wall at PAR 12**; serial sum of
  the top 12 is ~402 minutes. Longest three: `yang.typescript.antlr.corpus-test`
  5,064 s, `yin.vm.semantic.safepoint-harness-test` 4,523 s, `yang.php.antlr.e2e-test`
  3,460 s.
- Quick tier (JVM, 14 shards, PAR 12): **976 s wall**, dominated by four shards
  (`uncovered` 976 s, `yin.vm` 602 s, `yang.python` 556 s, `yin.repl` 469 s); all
  other shards ≤ 37 s.
- Node shard walls on shiva: `yin.repl` 5,527 s, `dao.space` 899 s, `yin.vm.ucf` 824 s,
  most others 24–130 s. Two shards (`yang.python`, `yin.vm`) ran >100 min without
  output and were killed as the long set.
- A per-test profile of the pathological case showed `dao.space.query/unify`,
  `dao.jing.cbor/content=`, `cljs.core/equality` hot and only ~2.25% GC samples.

## What to assess

A. **Is the merge gate sufficient?** `test:changed` selects by static reach
   (reverse-require closure, fixtures, walkers). Name the failure classes it cannot
   see (runtime dispatch, streams/DHT, cross-host behaviour, resource/config files,
   generated goldens, anything the walker misses) and whether a 12-hour blind window on
   master is an acceptable exposure for those. If you think a cheap supplement is
   warranted (e.g. a bounded always-run smoke set, or running the quick tier on the
   changed set in both hosts), say what and its cost.

B. **Is twice-daily the right cadence**, given the long set is bounded by single
   ~84-minute tests? Would you shard long namespaces further, split the long set across
   both hosts simultaneously, or accept it? What does the owner lose at 12h latency vs
   the cost of more frequent runs?

C. **Is the host/lane split still right** now that the nightly is twice-daily and the
   merge gate is per-feature? Consider: both hosts idle most of the time; jupiter's JVM
   lane finishes in ~2h while shiva's Node+Dart runs much longer; a single host could run
   everything sequentially. Is the split adding value or just complexity? What would you
   measure to decide?

D. **Coverage/evidence integrity.** Is the `uncovered`-residual + bundle-inventory
   validation enough to claim "the union covers the suite" on all three lanes? What
   remains unproven (e.g. the Dart lane has never completed end-to-end; the Node lane
   has never completed with the long shards included)?

E. **Your opinion, plainly.** Is this a sound engineering process for a two-person
   (owner + agents) project at this maturity, or is it over-engineered / under-rigorous
   in specific places? Rank the three changes you would make first.

## Deliverable (stdout; orchestrator promotes)

Per-item verdict (A–E) with evidence, then: **ACCEPT / ACCEPT-WITH-CONCERNS /
REVISE**, the ranked top-three changes, and anything you could not verify.
