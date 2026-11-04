Created-GMT: 2026-11-04 04:14:48 GMT
Created-Local: 2026-11-04 11:14:48 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: interactive (zcode, glm-5.3-flash)
Session-ID: not-applicable (interactive seat)
# Task: nightly red handoff — yang.php golden-pin drift after the P5 merge
Role: orchestrator
To: the yang.php track seat (relay by owner)

## Source run

Jupiter standalone JVM nightly, run `20261103-1342` at master `2ec867d2`
(the commit AFTER `453aa72e` "merge: P5 — the request context" and
`399d3b36` "P7-a and P5 land, both seat-verified"). Results:
`~/ci/nightly-results/20261103-1342/` on jupiter; shard log
`target/profile/quick-sharded-1793731557170-109702d4/yang.php.log` in
`~/ci/datomworld-nightly`. Quick tier: yang.php exit=1, tests=137,
failures=10. Slow tier reds: `yang.php.antlr.e2e-test`,
`yang.php.antlr.include-test`.

## Cluster 1 — stale golden pins (10 quick failures, all in
`yang.php.antlr.e2e-test`)

The pattern is uniform: the implementation got MORE capable at P5/P7-a and
the pins still encode the older behavior. Evidence (expected vs actual):

1. `fixtures-on-the-walker-test/string-offset-write` (e2e_test.clj:53) —
   expects the refusal `{:class "Error", :message "string offset writes are
   not supported yet (yang.php known gap G-str-offset-write)"}`; actual
   `:php/uncaught` is nil, i.e. string-offset writes now WORK. Known-gap
   G-str-offset-write is apparently closed but still pinned as open.
2. `lowering-warnings-test` (e2e_test.clj:322,327) — expects
   `:yang.php/unknown-builtin` warning for `strtoupper` and the fatal
   "Call to undefined function strtoupper()"; actual: no warnings and
   `<?php echo strtoupper("a");` evaluates to "A". The prelude gained
   strtoupper; the catalogue/warning pin is stale.
3. `refusals-are-named-test` (e2e_test.clj:244-245) — `<?php $$a[0] = 1;`
   expects `:yang.php/unsupported` `:assignment-target`; actual nil — the
   variable-variable assignment target is no longer refused.
4. `phpt-selection-runs-test` (e2e_test.clj:534,536,538) — the recorded
   PHPT mismatch manifest moved: families `{gc 10, foreach-by-ref 16,
   unset-cv 4, named-params 3, other 72}` -> `{... unset-cv 3 ... other
   64}`; ~10 mismatch IDs left the fail set (unset_cv08, bug69756,
   bug71470, bug81705, bug67874, bug63206_1, bug60350, bug45877,
   bug79836_1, bug78502, named_params/internal moved categories); the
   recorded quick-tier refusal count pin 58 -> 57.

Slow tier, same cluster: `phpt-gc-023-stress-test` (e2e_test.clj:540) —
`Zend/tests/gc_023.phpt` now fails with uncaught "Call to undefined
function gc_collect_cycles()"; it was not in the previous fail set, so
either the manifest needs gc_collect_cycles accounted (unsupported?) or
the PHPT selection pin needs regeneration.

## Cluster 2 — JVM died mid-test, no summary (infra/transient suspect)

`slow-yang.php.antlr.include-test.log` is 6 lines, ending at
"Testing yang.php.antlr.include-test" with no results and no exception —
the JVM exited mid-run. Same namespace was GREEN in the prior run
(20261102-1651: "Ran 3 tests containing 293 assertions. 0 failures").
No OOM evidence on the host (62 GB, dmesg clean-readable output none).
Isolated reproduction (`clojure -M:test -i :slow -n
yang.php.antlr.include-test`, single JVM, jupiter) was still running when
this note was written; result will be appended or reported separately. If
it passes solo, treat as a 12-way-pool transient, not a code defect.

## What we are NOT asking

- The latte kernel-gate red (`unmapped` shard) is the known
  hardening3-h3-1 blocked-fixtures gate; it belongs to that track, not
  yang.php.
- No product-regression claim is made: every cluster-1 delta is the
  implementation improving against stale expectations.

## Suggested disposition (yang.php seat's call)

Regenerate/adjust the e2e pins for the P5/P7-a behavior gains (the four
pin sites above + the gc_023 manifest entry), then run ONLY the failing
namespace per the failure-reproduction rule:
`clojure -M:test -n yang.php.antlr.e2e-test` (quick) and the two slow
namespaces individually. G-str-offset-write's known-gaps row may deserve
a status update if the gap is truly closed.
