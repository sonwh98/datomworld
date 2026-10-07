## P2 fix — exact reply envelope

`/Users/sto/workspace/datomworld/src/cljc/yin/vm/linker.cljc:1170` (`answer-text`, check at lines 1178-1179): the acceptance predicate now requires the exact key set, present and no extras, in addition to the existing `:jing/found?` true and `:jing/bytes` string checks:

```clojure
(= #{:jing/request :jing/found? :jing/bytes}
   (set (keys answer)))
```

This matches the old linker's exact-envelope rule (`presence-envelope?` at HEAD) and the stepped client's `get-answer?` at `src/cljc/dao/jing/content/step.cljc:327-333`. An answer with an extra key now yields the `missing` sentinel, which `checked-part` (linker.cljc:1281-1282) refuses as `:absent` — the malformed reply can no longer flip from `:absent` to a successful link. The docstring (lines 1171-1177) names the exact shape.

## Test added

`/Users/sto/workspace/datomworld/test/yin/vm/linker_test.cljc`:
- `answering-runtime` helper at line 487: a runtime shaped like `local-runtime` whose drive answers each raw request with a test-supplied envelope, so hostile replies ride the same medium as honest ones.
- `a-found-answer-with-an-extra-key-is-absent` at line 814, over all four formats: a found answer carrying an extra `:value` key (the old wire's key) pins `{:status :refused, :reason :absent, :address ...}` (line 835); the exact three-key shape on the same medium still links (`linker/ok?`, line 841).

## Lane counts (sequential, solo; JVM then Node then Dart, `bb` task order, mise JVM tooling)

- JVM: 2,266 tests / 183,205 assertions / 0 failures
- Node: 2,175 tests / 49,865 assertions / 0 failures
- Dart: `+2135: All tests passed!`

Ledger vs the brief's baselines (2,254/183,121, 2,163/49,781, 2,123): my change is exactly +1 test / +8 assertions (one deftest, 2 is-forms x 4 formats). The remaining +11 tests / +76 assertions on all three lanes come from `test/yin/repl/require_test.cljc`, which gained exactly 11 deftests vs HEAD (8 -> 19) and was modified at 16:27 local by parallel repl work, after the brief's 16:24 snapshot. Arithmetic closes exactly on all three lanes: 2,254+12=2,266 / 183,121+84=183,205; 2,163+12=2,175 / 49,781+84=49,865; 2,123+12=2,135. (A parallel task ran `bb test:cljd` 16:35-16:40 local; I waited for it to finish before my Node and Dart lanes.)

## Constraints

- Touched only `src/cljc/yin/vm/linker.cljc` and `test/yin/vm/linker_test.cljc`; nothing staged, no commit/stage/checkout/reset/stash.
- cljstyle check exit 0 on both files; clj-kondo v2026.08.04: 0 errors, 0 warnings.
- Pure ASCII, every added/edited line <= 80 columns; no diagnostics or debug leftovers.

Status: COMPLETE