# Dart per-var wrapper proof — STOP

Base `bab7640f0b162444285ab622469de1abbefbb668`, branch `fix/dart-wrapper-proof`, host shiva. **The corpus split remains blocked.** The stated requirement forbids duplicating shared generated top-level state and fixture construction across wrappers. The existing generated namespace contains a shared mutable memoizing delay; a separate isolate per var necessarily has its own copy and reconstructs its fixtures. This is a fixture-construction divergence, not evidence of differing test outcomes. Per the explicit stop instruction, no driver prototype, wrapper compilation, Flutter wrapper run, or kondo check was performed. No compile/run rc or wall time exists for those steps.

## Generated evidence and scope

Inspected `/home2/sto/ci/datomworld-nightly-dart/test/cljd-out/yang/typescript/antlr/corpus-test_test.dart` (SHA-256 `d969217e5ab7c1562ca5d277f5e524c977d34d15f7dfdab3855045d847f29e5b`), generated on Nov 25 in the nightly checkout at `9bd665fe893847943426d274db48dd90a3408ddb`. It is **not** a retained Nov 23 generated artifact; the same output path was overwritten by a later compile. `git diff --quiet 9bd665fe HEAD -- test/yang/typescript/antlr/corpus_test.cljc` returned rc=0. That establishes unchanged corpus source, not byte identity with the Nov 23 generated file or identical dependency/compiler versions. No fresh compile was performed.

Generated `main()` lines 232–243 calls these ten registration functions exactly once, in this order: `t3_bundled_matches_node_test`, `t3_linked_matches_node_test`, `t1_bundled_matches_node_test`, `t3_units_fixture_test`, `fixtures_agree_test`, `t2_linked_matches_node_test`, `no_entry_is_pending_test`, `t2_bundled_matches_node_test`, `t3_packets_parse_test`, `t1_linked_matches_node_test`. The six slow legs are only part of the inventory: a split must also retain the four other registrations exactly once. Each registration creates a Flutter test; for example lines 3005–3015 and 3061–3100 register t2-linked and invoke its slow guard and `leg` when executed.

Generated namespace top-level data includes reassignable `corpus_names$v1`, `enabled_steps$v1`, `fixtures$STAR_$v1`, `pending_steps$v1`, and `pinned_node$v1`. In particular, lines 720–736 initialize `fixtures$STAR_$v1` with `lcoc_core.Delay(null, ...)`. Its thunk reads corpus, packets, and oracle for **all three** names and constructs a map. `disagreements` dereferences that same delay at line 346; fixture-agree, no-entry-pending and t3-packets likewise dereference it. `t3-units-fixture-test` separately reads `t3_units.edn`. The source confirms this construction at `test/yang/typescript/antlr/corpus_test.cljc` (`fixtures*`, `disagreements`). The generated `lib/cljd-out/cljd/core.dart` lines 6064–6085 defines `Delay` with mutable `var val` and `var f`; dereference computes and stores `val` then clears `f`. Thus, in one isolate the first dereference realizes one shared cache, reused by later tests; in separate wrapper isolates each exercising the fixtures creates and realizes its own cache, rereading and reconstructing the nine inputs. Other imported libraries may contain additional isolate-local state; this inspection does not claim an exhaustive dependency-state audit.

## Archived comparison baseline

Archived `/home2/sto/ci/datomworld-nightly-dart/target-ci/dart-shards-20261123-2121/all.log` lines 6141–6143 and 6262–6264 contain these verbatim boundary rows:

```text
327:23 +4707 -17: /home/sto/ci/datomworld-nightly-dart/build/cljd-agg-20261123-2121-uncovered/shard_3_test.dart: null yang.typescript.antlr.corpus-test/fixtures-agree-test
327:23 +4708 -17: /home/sto/ci/datomworld-nightly-dart/build/cljd-agg-20261123-2121-uncovered/shard_3_test.dart: null yang.typescript.antlr.corpus-test/t2-linked-matches-node-test
run t2 :proto-shadow-delete
run t2 :new-member-call
440:31 +4709 -17: /home/sto/ci/datomworld-nightly-dart/build/cljd-agg-20261123-2121-uncovered/shard_3_test.dart: null yang.typescript.antlr.corpus-test/no-entry-is-pending-test
440:31 +4710 -17: /home/sto/ci/datomworld-nightly-dart/build/cljd-agg-20261123-2121-uncovered/shard_3_test.dart: null yang.typescript.antlr.corpus-test/t2-bundled-matches-node-test
```

There are 120 distinct `run t2` progress rows and no `MISMATCH t2` rows in this interval. The reporter counters are global across concurrently running shards; the subsequent `+4709` is not an independently attributed per-test assertion count. Progress lines show attempted entries, not a per-row pass/fail report, and the archived expanded log does not prove per-row assertion counts. No wrapper result exists to compare. The next displayed test is 113 minutes 8 seconds after the t2-linked line, so the previously reported 2,501-second transition is not supported by these adjacent archived timestamps (interleaving and reporter behavior also limit timing interpretation).

## Resolution needed

An owner must either approve changing the exact shared-state/fixture-construction requirement and define observable equivalence, or choose a design that actually retains the single shared cache. Only after resolving that condition should the follow-up emit the complete registration inventory, compile every wrapper, and compare a scoped slow execution against a baseline with sufficiently granular assertion and row dispositions. Nothing was merged.
