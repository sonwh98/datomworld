# Orchestrator log — remote test harness track (shiva/jupiter)

## 2026-11-01 12:35:39 +07 — subsystem-sharded nightly harness for shiva/jupiter
Completed-GMT: 2026-11-01 05:35:39 GMT
Coding-Agent: interactive
Session-ID: sess_b6001731-427a-4431-b74f-97f348558fc2
Tree: master@120ff322 (clean tracked tree apart from two other seats' uncommitted
  orchestrator-logs edits and codex's in-flight edit to src/dev/dart_shard.clj)

Done: The nightly testing harness was rebuilt so each lane runs sharded by
subsystem and concurrently, and so both hosts run identical versioned code.
Landed on master: `e3704fd9` one nightly driver (`src/dev/nightly.clj`, lanes as an
argument, mise install first, pin publish/read), `4a28d183` shard output capture to
Files (unflushed Writers were silently losing every log under one buffer),
`120ff322` dao.stream diagnostics (every unclean corpus read collapsed to a bare
nil), plus earlier `a731d99e`/`e2` fixes to the Node selector and pool, `2bd42461`
cljd_agg flags, and `mise.toml` pins for babashka and flutter (`5e5b7c7f`).
`src/dev/node_shard.clj` (work-stealing pool, PAR 12, bundle-inventory validation),
`src/dev/dart_shard.clj` (one full cljd compile, per-subsystem shard dirs, ONE
flutter invocation), and `src/dev/nightly.clj` are the three drivers; both hosts
run the same blobs from the same commit.

Decisions:
- Lane split kept as the owner ratified: jupiter JVM, shiva Node, Dart on either.
- Work-stealing over waves: `partition-all` idled 4 of 6 slots when fast shards
  finished (load 3.59); the pool raised it to 10.8.
- `babashka`/`flutter` were `"latest"` in mise.toml, which resolves per host at
  install time (1.12.218 vs 1.13.225; 3.47.1 vs 3.47.8) — pinned, and the wrappers
  now run `mise install` (the step whose absence caused the drift).
- Two host-local bash wrappers were replaced by the one versioned driver; they
  still exist on disk because live runs were launched by them.
- DEVIATION, recorded honestly: harness fixes were committed under the owner's
  running instruction to make the harness work ("fix it", "yes", "delegate the fix
  to codex") rather than at each commit. From this entry forward, step 9 gating
  applies: no staging or committing without explicit instruction.

Verification:
- Jupiter JVM lane, complete: quick sharded 14/14 green 1,023 s; slow tier 52
  namespaces 5,645 s with 7 reds (all yang.python.* + yin.vm.semantic.derive-test).
- Shiva Node lane at 120ff322, in flight: 10/14 shards done, 0 failures, incl.
  dao.stream rc=0 129 s; the 4 heavy shards (yang.python, yin.repl, yin.vm,
  uncovered) still running.
- Output capture proven by experiment (131 KB captured across stdout+stderr,
  survives non-zero exit, truncates per run).
- Not verified: no complete tri-host run; the Dart lane has never executed on
  either host (jupiter failed at cljd compile, shiva never got there); the two
  XML-free measurements of Node/Dart wall time therefore do not exist yet.

Delegates: codex gpt-6.1-sol — nightly-plan review (REVISE: R1/R3/R4/R6),
  node-shard diagnosis (found the `--test` two-arg bug that made every "shard" run
  the full suite), node-shard fix (capture + apply bug in nightly.clj), and the
  in-flight dart-shard diagnosis on jupiter. kimi k3.1-pro — independent second
  opinion on the node-shard memory question (independently found `:cache-root` is
  not settable through build-level --config-merge).

Next:
1. Accept/verify codex's dart-shard diagnosis (in flight on jupiter), then the
   first real Dart-lane measurement.
2. Finish the shiva Node lane; record the wall time and whether yang.python/yin.vm
   complete or stall (their cost is real computation in dao.space.query/unify per
   the profile, not GC).
3. Run the same suite on the other host with the same driver to settle the
   Node/Dart routing question by measurement.
4. Independent review (different family from codex) of the harness diffs before
   relying on them for gate decisions.
5. Owner decision pending: re-enable the 03:00 VN cron entries (currently
   cancelled); pin or leave `android-sdk = "latest"` in mise.toml.

## 2026-11-05 01:0x:0x +07 — nightly cron installed: disjoint lanes at 04:00 Vietnam
Completed-GMT: 2026-11-04 18:0x:00 GMT
Coding-Agent: interactive (zcode, glm-5.3-flash)
Session-ID: not-applicable (interactive seat)
Tree: master@fac5f5db, no uncommitted changes this unit
Done: owner instruction "schedule the disjoint tests on shiva and jupiter at 4am
Vietnam time". Installed ~/ci/nightly-cron.sh on both hosts plus an hourly
crontab entry (0 * * * *): the wrapper guards on TZ=Asia/Ho_Chi_Minh hour == 04
(DST-immune: hosts run EST) and flock-skips a slot whose prior run is still
live. shiva: lanes node,dart, --pin-publish, fresh dedicated clone
~/ci/datomworld-nightly (origin verified github, checkout current master,
per-clone fan-out auto-creates -node/-dart); jupiter: lane jvm, --pin-read
--pin-peer shiva, clone already at datomworld-nightly. Driver window guard
changed to 04 VN hour (still --now-forceable). Logs ~/ci/nightly-cron.log,
results ~/ci/nightly-results/<stamp>/. Docs: build-n-test.md cadence +
master-only sections updated; stale nightly-full.sh/tmux mention replaced.
Closes the prior entry's open item "re-enable the 03:00 VN cron entries".
Decisions: once daily at 04:00 (owner's new schedule) replaces the twice-daily
03/17 windows; documented without supersession references per the file rule.
Verification: both wrappers self-tested outside the window (silent exit, rc=0);
crontabs confirmed installed; shiva clone currency proven by REMOTE-OK +
log --oneline matching origin/master. No live run started (first automatic
launch: tomorrow 04:00 VN).
Delegates: none
Next: check the first scheduled run's summaries on both hosts; yang-php pin
regeneration and the include-test hang remain handed off (notes filed);
slow-tier per-namespace timeout and the 10-hour uncovered-shard split stay
unplanned items.
