Created-GMT: 2026-11-06 1x:xx:00 GMT
Created-Local: 2026-11-07 0x:xx +07 (Asia/Ho_Chi_Minh)
Coding-Agent: interactive (zcode, glm-5.3-flash)
Session-ID: not-applicable (interactive seat)
# Task: QA - make the nightly pair green (run 20261106-0944, master 8d0a4ffb)
Role: qa-engineer
To: the QA orchestrator seat (relay by owner)

## Scope

You own making the disjoint nightly pair (jupiter=jvm lane, shiva=node+dart
lanes) green. Below is the complete failing-test inventory of the latest
completed run, with evidence paths on the hosts, failure signatures, and the
solo-reproduce command for each (per the failure-reproduction rule: run ONLY
the failing test, never the suite). Some of these already have per-track
handoff notes filed by this seat; those are cross-referenced - reconcile with
them rather than re-diagnosing from scratch. Product regressions (real code
faults) route BACK to the owning track seat; pin/gate/flaky-infra issues are
yours to fix directly.

## Hosts and clones

- jupiter: ~/ci/datomworld-nightly (jvm lane); results
  ~/ci/nightly-results/20261106-0944/
- shiva: ~/ci/datomworld-nightly-node and -dart (per-clone fan-out); results
  ~/ci/nightly-results/20261106-0944/; shard logs under <clone>/target-ci/
  NOTE: the shiva dart run's shard directory was rotated before the failing
  flutter test IDs were extracted (another seat's cleanup raced the triage).

## Inventory

### 1. Cross-VM index-publication determinism (TOP PRIORITY - confirmed on BOTH lanes)

- Failing: yang.clojure.stream-eval-test / every-vm-answers-the-same-stream-script
  (jupiter jvm slow tier) and yang.base shard (shiva node lane) - the same
  test file: yang/clojure/stream_eval_test.cljc:444.
- Signature: the four VMs' repl-states agree on every field EXCEPT
  :index-publication :manifest-address - four DIFFERENT blake3 hashes for the
  IDENTICAL 15-transaction, 75-row index. The hash set also differs run to run
  (jupiter: 6434f85a/6dcec888/077211ce/972ce5d2; shiva:
  290876bb/7e57be75/7043f3a6/139678fe) - so it is nondeterministic per VM
  instance AND per run. Same logical content must publish to the same
  content-address; something in the publication input (ordering? a timestamp?
  a host-bound field? nondeterministic map iteration feeding the hash?)
  varies per instance.
- Also red with it: shiva yang.base rc=1 (181s), shiva yin.repl/yang.python/
  uncovered (likely downstream of the same divergence if they assert on
  published addresses).
- Reproduce: jupiter `cd ~/ci/datomworld-nightly && eval "$(mise env -s
  bash)" && clojure -M:test -n yang.clojure.stream-eval-test`; shiva node
  `node target/node-tests.js --test=yang.base` in datomworld-nightly-node.
- Disposition: if the fix is in publication/serialization code, route the
  product change to the yang.clojure or dao.stream seat; the QA seat owns
  the diagnosis and the verification.

### 2. dao.host H3 gate pin stale (quick tier, 33 failures)

- Signature: gate_test.clj:1010/1011 - expects 168 closure files, actual 174
  (six profile files added since H3 without updating the gate count).
- Reproduce: jupiter `clojure -M:test -n dao.host.gate-test` (or the
  quick-shard name from $d/jvm-quick.log).
- Disposition: yours - either the new files belong in the gate (update the
  pin with provenance) or they must be excluded (justify). Check whether
  master's latest commits already fixed this before editing.

### 3. yin.repl quick corpus: StackOverflowError through the PHP REPL (1 failure)

- Signature: shell_session_test.clj - prompt "<?php echo 1 + 2;" expects
  "3", actual "Error: java.lang.StackOverflowError" on BOTH :ast-walker and
  :semantic VMs. Same territory as the yang.python recursion-limit regression
  (Node lane leaked the JS "Maximum call stack size exceeded"; the JVM lane
  now leaks java.lang.StackOverflowError through the REPL corpus).
- Reproduce: jupiter `clojure -M:test -n yin.repl.shell-session-test` (name
  from the quick log).
- Disposition: the guest-language stack limit is not enforced before the
  host stack exhausts. Likely one fix covers python recursion + this corpus.
  Product fix routes to the yin.vm / safepoint owner; cross-reference the
  yang-python handoff note (collab/yang-python/1793765788000).

### 4. yin.repl.dht-test (jvm slow tier, NEW signature this run)

- dht_test.cljc:2105 rounds-larger-than-the-pending-write-bound...: expects
  (= 8 (count (distinct ms))), actual 2 - the ack timings collapsed to two
  distinct values instead of eight.
- Reproduce: jupiter `clojure -M:test -n yin.repl.dht-test` (after
  bb build:yin-repl-node).
- Disposition: could be timing-resolution (the test asserting on a coarse
  clock) or a real scheduling collapse. Measure first; do not loosen the
  pin reflexively (measure-then-pin rule).

### 5. yang.php.antlr.e2e-test (jvm slow tier)

- STILL red after the P8 manifest regeneration landed (master 8d0a4ffb
  includes "fix(dart)" and the manifest final state). Re-check which
  assertions fail NOW: the pre-regeneration reds were gc_023.phpt and
  string-offset-write pins (collab/yang-php/1793765688000 handoff). If the
  failures are the same pins, the yang.php seat's regeneration is
  incomplete; if new, re-file.
- Reproduce: jupiter `clojure -M:test -i :slow -n yang.php.antlr.e2e-test`.

### 6. yang.python.antlr.linked-prelude-test (jvm slow tier, died mid-test)

- 15-line log, no results: same die-mid-test shape as the earlier
  include-test/safepoint JVM deaths. Solo rerun decides hang vs crash:
  `clojure -M:test -i :slow -n yang.python.antlr.linked-prelude-test` with a
  generous timeout; if it wedges, that is the no-per-namespace-timeout gap -
  report it rather than leaving it running.

### 7. unmapped shard: latte kernel gate (quick tier)

- latte_oracle_test.clj:2438 - kernel gate :blocked vs :pass. KNOWN: the fix
  lives on the unmerged hardening3-h3-1 branch. Not a QA fix; listed so the
  nightly's FAIL is not mistaken for a new defect. Confirm the branch state
  before every nightly verdict.

### 8. dart lane: 7 flutter failures (IDs lost to log rotation)

- flutter rc=1 wall 29,999s: "497:24 +4564 -7: Some tests failed." Compile
  and peer build are GREEN (the cljd break is fixed on master). The failing
  IDs were lost when the shard dir was rotated before triage; get them from
  a solo rerun: shiva `cd ~/ci/datomworld-nightly-dart && eval "$(mise env
  -s bash)" && flutter test` and pull the [E] lines.

## Standing caveats

- shiva node lane also carries the ~10h uncovered residual and yang.python
  (~5.5h) - the two wall-clock setters. A per-namespace timeout in the slow
  pool (and the uncovered shard split) are pre-approved improvement items if
  you want them; neither exists yet.
- Every fix lands per the commit-authority rules; the nightly reruns on your
  signal (cron currently DISABLED per the owner 2026-11-06; relaunch with the
  one-line crontab or a manual --now run).
