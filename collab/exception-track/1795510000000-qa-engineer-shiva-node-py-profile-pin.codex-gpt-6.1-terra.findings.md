# Shiva node Python profile pin diagnosis

Host: shiva. Head and fetched origin/master: `0d378482933fa8cf73a2d12f144a04052176ccb8`. Fresh independent clone: `/tmp/qa-shiva-pin-fresh`. No production source, test, pin, or lane files changed.

The problem is a committed profile-lock mismatch, not evidence of a stale lane build. `src/cljc/yang/python/antlr/profiles.cljc` reconstructs the `py` manifest from current source and validates its canonical bytes against the committed `module-locks` address. In the fresh clone after `bb gen:antlr`, a diagnostic wrapper around the actual `verify-address!` calculated:

- Expected lock: `:segment/blake3-d7568c166dbcbf8ac0373bc2f1c38925c9a4f3e7c2a494a5103e164e3f9cd93a`.
- Actual reconstructed manifest: `:segment/blake3-c7d3e443e5966e5d9d6211b5ecad9bbce7abf611844c1d4b4a59fcb4921efe4c`.

The probe used the same `runtime-artifacts!` path, `jing/segment-key` on the precise value presented to `verify-address!`, and preserved the original refusal. This actual is constructed with the *committed* derivation and index locks; it is not a claim of the final address after a complete remint. The `py` prelude changed in `7692d347` without changing `profiles.cljc`. The separately cited canonical `6e60da0c…` is the linked-prelude manifest golden, not this reconstructed profile manifest. Leave remint to the owning seat.

The fetched published `origin/py-manifest-remint` was `bb4b1ec59238b53cd76e4ed465dea5cb731b9510` at inspection; its branch diff changes only `test/yang/python/antlr/linked_prelude_test.cljc`, not `profiles.cljc`. The countersign requested an amendment to five float pins. Thus the current published branch alone does not demonstrate that this distinct runtime profile-lock mismatch is fixed; the owner should include or separately route it. No claim is made about unpublished amendments.

Dart quick evidence check: the same host's `/home/sto/ci/datomworld-nightly-dart/target-ci/dart-shards-20261123-2121/all.log` reports `yang.python.antlr.descriptor-profile-test/default-selection-and-snapshot-test` throwing the identical `d7568c…` address mismatch. This is historical lane evidence, not a fresh Dart test or proof of Dart's reconstructed hash.

## Reproduction and limits

Fresh-clone setup followed `src/dev/node_shard.clj`: `npm ci --silent`, `bb build:yin-repl-node`, `bb build:yin-repl-peer`, `bb gen:antlr`, and `clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:autorun false}'` all completed rc=0. The initial ANTLR generation was a cache miss; the later invocation verified a cache hit. The test bundle was freshly compiled (675 files, 674 compiled). The Dart peer and Node test compile overlapped only after generation had completed, and the shard sequence then reran the compile successfully.

Before: `DATOM_SLOW_TESTS=1 node target/node-tests.js --test=yin.repl.python-antlr-test` reproduced the precise `d7568c…` refusal in `assignment-then-print-test` and `the-loop-prints-the-last-expression-value-test`. After more than 25 minutes and two error rows it had no summary; stopped at the requested (b) stop condition. **rc=130 (interrupted); Ran: no summary emitted.** This is not a completed namespace result and must not be reported as rc=1 or as a count. Log: `/tmp/qa-shiva-node-test.log`. An earlier quick-mode attempt was interrupted before it produced a result (rc=130, Ran: no summary); it was replaced by the lane's slow-enabled mode.

No after-fix run or two-run artifact regeneration was performed: this is not a lane-local cache fix. The fresh-source diagnostic JVM probe (`clojure -M /tmp/qa-shiva-profile-probe.clj`) exited rc=0 after deliberately catching the expected refusal; it is an address probe, not a test run.
