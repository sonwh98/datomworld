Created-GMT: 2026-11-06 2x:xx:00 GMT
Created-Local: 2026-11-07 0x:xx +07
Coding-Agent: interactive (zcode, glm-5.3-flash)
Session-ID: not-applicable (interactive seat)
# Task: nightly red handoff - cross-VM index-publication determinism + dart flutter failures
Role: orchestrator
To: the yang.clojure track seat (relay by owner); the manifest-address divergence also touches dao.stream's publication path

## Finding 1 (NEW, confirmed on BOTH lanes): index-publication manifest-address diverges across VMs

The repl-state parity test (yang/clojure/stream_eval_test.cljc:444,
"every-vm-answers-the-same-stream-script") now fails on BOTH execution lanes
with the same signature: the four VMs' repl-states agree on every field
(transactions, cursors, positions, telemetry note) EXCEPT
:index-publication :manifest-address - four DIFFERENT blake3 hashes for the
identical 15-transaction, 75-row index. Evidence:
- shiva node lane, run 20261106-0944 at 8d0a4ffb:
  ~/ci/datomworld-nightly-node/target-ci/node-shards-20261104-2345-era log
  (yang.base shard, 181s red) - hashes 290876bb/7e57be75/7043f3a6/139678fe.
- jupiter jvm slow tier, same run-id: slow-yang.clojure.stream-eval-test.log -
  hashes 6434f85a/6dcec888/077211ce/972ce5d2 (a DIFFERENT set of four hashes
  per run - the divergence is nondeterministic across runs too).
This is a content-address determinism bug in the index publication path (same
logical content hashing differently per VM instance), not a test flake: both
runs at the SAME commit 8d0a4ffb produce fresh divergent hash sets. It
invalidates the publication-address contract the medium layers rely on.
Reproduce: clojure -M:test -n yang.clojure.stream-eval-test (jupiter) and the
node form (shiva, node target/node-tests.js --test=yang.base).

## Finding 2: dart lane progress + remaining red

The cljd compile break from the earlier handoff is FIXED on master (8d0a4ffb
compiles green, 409s; build:yin-repl-peer rc=0) - the reader-conditional fix
worked. Remaining: flutter rc=1 wall 29,999s: "497:24 +4564 -7: Some tests
failed" - 7 failing dart tests on the same commit. NOTE: the run's shard log
directory on shiva was rotated/deleted before the failing test IDs could be
extracted (another seat's cleanup ran between two reads); the owning track
should get the IDs from a solo rerun: cd ~/ci/datomworld-nightly-dart &&
eval "$(mise env -s bash)" && flutter test (or bb cljd_agg --only <ns>) and
pull the [E] lines. 4,564 passing tests, so the 7 failures are a narrow set.

## Also red this run (for completeness, not this track)
- dao.host quick gate: 168-file pin vs 174 actual (pin-stale; the 6 profile
  files added since H3 need the gate count updated or justified).
- yin.repl quick corpus: "<?php echo 1 + 2;" answers StackOverflowError on
  walker+semantic (stack-limit leak through the REPL, same territory as the
  yang.python recursion regression).
- yin.repl.dht-test (jvm slow): dht_test.cljc:2105 expects 8 distinct
  ack-timings, got 2 - new this run; the python dht-import red from the
  previous run did NOT recur on jvm this time.
