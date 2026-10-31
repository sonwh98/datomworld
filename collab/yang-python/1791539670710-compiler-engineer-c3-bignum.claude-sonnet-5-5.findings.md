Created-Local: 2026-10-09
Coding-Agent: claude (claude-sonnet-5-5)
Role: Compiler Engineer

# Findings: C3 S3-S5 (BigInt literals, promotion/demotion, operations)

## Outcome: no code written; the scope is already landed on this branch

The brief appears stale. `yang-python-c3-bignum` is at `ea247169`, and its
history already holds the work in the brief, under different slice names:

- `dedff175` C3 S2: integer literals of any magnitude lower exactly (scope item 1).
- `b5ee98dc`, `8eb80a01`, `828680f1` C3 S3-A/B/C: exact `+ - *`, comparisons,
  mixed int/float, `// % divmod **`, int/int true division, and infinite
  two's-complement bitwise ops and shifts (scope items 2 and 3).
- `817ce365` C3 S4 and `73b213a9` M4: conversions, correctly rounded division,
  `max-digits`.
- `c681ca24` S6 and `b3ae5708` S7: heap/portability and the integration gate.
- `docs/design/yang.antlr.md` §8.5.4 says the same.
  S3, S4, S6 and S7 are recorded as landed.

`lower.cljc` already carries the digit budget (`:max-digits` in the lowering
context, `:yang.python.antlr/max-digits` on packets).

The brief names `test/yang/python/antlr/exact_int_test.cljc`. That file does
not exist. The equivalents are `int_literal_test`, `int_ops_test`,
`int_conv_test`, `int_contract_test` and `c3_gate_test`.

The working tree was clean at the start. I changed nothing and made no commits.

## Verification actually run (JVM only)

`clojure -M:test -n <ns>` on each namespace:

- `yang.python.antlr.int-literal-test`: 14 tests, 364 assertions, 0 failures, 0 errors.
- `yang.python.antlr.int-ops-test`: 17 tests, 305 assertions, 0 failures, 0 errors.
- `yang.python.antlr.int-conv-test` and `c3-gate-test`: **not verified.**
  Both were still running when I stopped waiting and I have no result for them.
  I did not rerun them or investigate.

## Not run

- Node lane (`bb test:cljs`), Dart lane (`bb test:cljd`) and `clj -M:kondo`. I did
  not run them, so cross-host parity and lint are unconfirmed in this session.
- Full `bb test:clj`.

## Recommendation

Do not redispatch S3-S5 as new implementation. If a gate is still wanted, run
the two unfinished namespaces and the Node and Dart lanes on this branch, then
review. Alternatively, the orchestrator should check whether the brief was meant
for a different base commit.
