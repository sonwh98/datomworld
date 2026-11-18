# cc4 partial findings — acceptance remains open

Base `2687ab0cf03ebaa232be49a2333347d12a71d9a3`, branch
`cc4-fourhost-equality`. No commits. This is not the §5.3 acceptance bundle.

A new `pinned-replay-test` records the root and the entire sorted eight-key
C1 occurrence vector for a native `compile-program` fixture containing a
float, metadata and repeated call subtrees, under supplied origin
`[:source :program "batch-1" 0]`. The initial placeholder expectations
failed on the JVM, then passed after recording that host's values. Expanding
the fixture caused the old pin to fail, and the updated pin passed.
The test additionally compares the marker set to the canonical eligible set.
This is a partial cohort: it does not establish macro expansion identities,
published module side-table addresses, wrapper/prelude coverage, the negative
admission fixture, or equality on another host.

| Host | Evidence | Status |
|---|---|---|
| JVM | `clojure -M:test -n yang.clojure-callsite-test -v yang.clojure-callsite-test/pinned-replay-test`: 1 test, 3 assertions, 0 failures/errors for updated pin | Partial only |
| JVM battery | `clojure -M:test -n yang.clojure-callsite-test`: 44 tests, 586 assertions, 0 failures/errors, **before** the fixture was expanded | Partial only |
| Node | Not run | Open |
| Dart | Not run | Open; existing `:cljd nil` branches including the `with-redefs` spy remain untouched |
| Jolt | Not run; namespace not added to J2 manifest | Open |

`bb gen:antlr` initially failed because network access was unavailable in the
sandbox; the retried run with network access generated the pinned parser inputs.
`clj -M:kondo --lint test/yang/clojure_callsite_test.cljc` reported 0 errors,
1 pre-existing unused `yin.repl.query` require warning, exit 2.

No budgets were measured or ceilings declared. Both pre-port and post-port B/S
experiments, cross-host measurements, artifact/provenance inventory, legacy-load
proof, refusal inventory and the full equality report remain outstanding. No
conclusion about cross-host divergence or budget compliance is supported by this
partial evidence. The v2 cc3 ceilings cannot simply be presented as measurements
for this different corpus and holder.
