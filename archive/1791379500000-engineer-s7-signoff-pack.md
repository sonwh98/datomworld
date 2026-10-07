Created-GMT: 2026-10-07 14:45:00 GMT
Coding-Agent: claude (opus-5-5)
Role: Lead Implementation Engineer
Task: engineer-s7-signoff-pack
Responds to: collab/1791377000000-architect-s7-signoff.gpt-6-astra.findings.md

# Python C3 Slice S7: Sign-Off Pack

Tree: branch yang-python-c3-s7, HEAD 49960017 (= origin/master at
review), S7 uncommitted: the 16 modified and 5 untracked non-collab paths
`git status` lists (prelude.cljc, data.cljc, yang.antlr.md, the c3 corpus,
gate, programs and generator files, and the migrated test compositions).
Every number below was produced in this run on that tree. Every mutation
was reverted before the gate runs. After the last revert,
`git diff -- src/cljc/yin/vm/engine.cljc src/cljc/yin/vm/integer.cljc
src/cljc/dao/jing/cbor.cljc` is empty and the scratch harness is deleted.

## Summary

| Finding | Status | Evidence |
| --- | --- | --- |
| S7-1 integration gate | Closed | `bb test:all` on the final tree, slow bodies on every host, 0 failures on all three lanes. Fresh `bb test:changed`. Focused JVM gate run. |
| S7-2 mutation ledger | Closed | Seven mutations, each red on a named host and test, each reverted. Five go red through a C3 detector program: a and b on Node, c, d and f on the JVM. e and g go red through the codec/golden and heap tests the design assigns them. |
| S7-3 heap composition | Closed | `int_heap_test.cljc` registers `data/max-items` and calls `prelude/admit`. It ran in the slow gate on all three hosts. |

## S7-1: integration gate, final tree

All runs below were on the final tree, after the last mutation revert.
Logs are in `target/` of this worktree.

| Run | Lane | Tests | Assertions | Result | Log |
| --- | --- | ---: | ---: | --- | --- |
| `bb test:all` (every test, `^:slow` and guarded bodies included) | JVM `clojure -M:test` | 3,740 | 241,544 | 0 failures, 0 errors | target/s7-test-all.log:615 |
| | Node (`DATOM_SLOW_TESTS=1`) | 3,490 | 104,962 | 0 failures, 0 errors | target/s7-test-all.log:1184 |
| | Dart (`DATOM_SLOW_TESTS=1`, aggregated shards) | 3,445 | n/a (flutter reports tests) | All tests passed, exit 0 | target/s7-test-all.log tail |
| `bb test:changed` (fast) | JVM | 254 (23 ns) | 7,923 | 0 failures, 0 errors | target/profile/changed-clj.log |
| | Node | 227 (16 ns) | 3,124 | 0 failures, 0 errors | target/profile/changed-cljs.log |
| | Dart | 207 (16 ns) | n/a | All tests passed | target/profile/changed-cljd.log |
| Focused JVM: `clojure -M:test -n yang.python.antlr.c3-gate-test -n yang.python.antlr.c3-gate-parser-test -n yang.python.antlr.int-conv-test` | JVM | 17 | 461 | 0 failures, 0 errors (13 m 49 s) | run output |

Executed, not skipped:
- `grep -c "SKIP slow" target/s7-test-all.log` = 0. No guarded body was
  skipped on Node or Dart.
- `c3-gate-test` (`^:slow`, `slow/guard`) ran on all three hosts. It is
  `Testing yang.python.antlr.c3-gate-test` at log lines 224 (JVM) and 823
  (Node), and `c3-gate-test/c3-gate-test` in Dart shard 1 (log line 1840
  on). It runs the ten corpus programs on four VMs (ast-walker, semantic,
  stack, register) per host. Each asserts that exact stdout equals
  `c3-corpus-v1.txt`, with no exception.
- `c3-gate-parser-test` (JVM, line 222) ran
  `c3-sources-on-every-vm-test` (`^:slow`), the ten sources naive and
  under no-op hooks. It also ran the packet/parser/docstring binding.
- `int-heap-test` ran with its `^:slow` tests on the JVM (line 242) and
  Node (line 833). On Dart, its generated file
  `test/cljd-out/yang/python/antlr/int-heap-test_test.dart` was
  recompiled in this run (23:07). It is among the 3,445 passing tests.
  Flutter's progress lines only name the test running at each tick, so it
  is not named in the log.
- `bb test:all` covers `clojure -M:test -i :slow -n <ns>` for every
  changed Python namespace, `bb test:slow:cljs` and `bb test:slow:cljd`.
  Those are subsets of it, so they were not run separately.

Corpus result. All ten C3 programs (promotion, demotion, keys, divmod,
shifts, power, conversions, signed-zero, limits, small) produce
byte-for-byte the stdout in `c3-corpus-v1.txt` on four VMs on all three
hosts. For the eight `cpython` programs, that is CPython 3.9.6's output.
The architect independently regenerated it under `/usr/bin/python3 -I -B`
(SHA-256 1b430c4c...; int-conv-v1 5164c8fe...). Neither fixture changed
after that. `limits` and `small` are labelled hand pins of the profile,
not CPython measurements.

Corrections to the brief this pack was asked to record:
- The brief cited `bb test:cljd` (3,445 tests) as the Dart evidence.
  `bb test:cljd` is the fast lane and prints SKIP for `c3-gate-test`, so
  it cannot demonstrate the corpus on Dart. The 3,445 above come from the
  Dart lane of `bb test:all` with `DATOM_SLOW_TESTS=1`. The count matches
  because guarded tests are registered in both.
- The brief's Node focused run (36 tests / 631 assertions) was not
  reproduced in this run. It is superseded by the Node lane of
  `bb test:all`.
- The brief's `test:changed` JVM figure (7,831 assertions) came from the
  16:19 logs, which predate the heap migration. The final-tree figure is
  7,923.

## S7-2: ruling 14 mutation ledger (red, then reverted)

Method. Each mutation was one edit to production source. The S7 detector
programs ran through a scratch test namespace (deleted afterwards). Its
assertion is the one `c3-gate-test/gate` makes,
`(= {:py/out stdout, :py/exception nil} (run-program pk profile))`. It
calls `c3-gate-test/run-program` and `program` on the checked-in
`c3_programs.cljc` packets and `c3-corpus-v1.txt`, on all four VMs. It
also prints the first differing stdout line. Where the program alone does
not go red on the JVM, the ledger says so and names the host or test that
does. Each mutation was reverted before the next was applied.

| # | Mutation (file: edit) | Host | Detector | Observed failure |
| --- | --- | --- | --- | --- |
| a | Disabled promotion. `integer.cljc` `fast-add`/`fast-sub`: return the unchecked native sum/difference, dropping the `safe-result?` fallback to the big path | Node | `promotion` program | Red on all 4 VMs (ast-walker, semantic, stack, register): `[:thrown "integer primitive refused"]` (an unsafe double reaches the next kernel). |
| a | (same) | JVM | `promotion` program | Green, 4/4. The fast path only accepts operands within +/-(2^53 - 1), so a JVM long sum cannot wrap, and a long of 2^53 or 2^54 is already the canonical JVM carrier (`demote` keeps longs below 2^63). The mutation has no effect on the JVM. Node is its detector host, as ruling 14 expects. |
| b | Skipped demotion. `integer.cljc` `out`: return the big carrier (`BigInt` on JVM, `bigint` on JS) instead of `(host/demote b)` | Node | `demotion` program | Red on semantic, stack and register: `[:thrown "Cannot lower a host value into :yin.code/value"]` / `"Cannot resolve a host value into :yin/value"`. ast-walker stayed green. `int-ops-test` was red in the same run (`index-range-repeat-test`, `range-small-profile-test`, `exact-division-power-test`, `exact-bitwise-shifts-test`), and so was `lower-portable-test/hand-packet-golden-test`. |
| b | (same) | JVM | `yin.vm.integer-test`, `int-literal-test` | 83 failures: `carrier-boundary-test`, `division-test`, `bits-test`, `digit-limit-test` and others in `integer_test.cljc`, and 17 in `int-literal-test/decoded-rows-are-canonical-carriers-test`. The `demotion` and `promotion` programs, `int-contract-test` and `int-ops-test` stayed green on the JVM (a small `BigInt` is `=` to its long, and stdout is the same). |
| c | Double-coerced keys. `prelude.cljc` `py/key`: an integer key becomes `(py/float-key (data/float-value (integer/to-float n)))` instead of its exact hex `finite-key` | JVM | `keys` program | Red on all 4 VMs. First differing line: expected `{0.5: 'half', 9007199254740993: 'odd', 9007199254740992.0: 'even'}`, got `{0.5: 'half', 9007199254740993: 'even'}` (2^53 + 1 collides with 2^53.0). |
| d | Omitted floor adjustment. `integer.cljc` `int-floor-div-mod`: `adjust? false` | JVM | `divmod` program | Red on all 4 VMs. First differing line (`7 // -2, 7 % -2, divmod(7, -2)`): expected `-4 -1 (-4, -1)`, got `-3 1 (-3, 1)` (truncation, not floor). |
| e | `abs(n)` for tag 3. `dao/jing/cbor.cljc` `int-wire`: tag 3 payload `(b- zero b)` (= abs(n)) instead of `(b- (b- zero b) one)` (= -1 - n) | JVM | S6 T4 `yin.vm.ucf.scalar-round-trip-test`, `int-contract-test`, `int-literal-test` | 10 failures, 1 error in 28 tests: `scalar-round-trip-test` (`scalar_round_trip_test.cljc:50` golden bytes, `:65` address), `int-contract-test/integer-rows-recompute-on-this-host`, `the-file-holds-exactly-the-rendered-text`, `text-round-trips-through-the-module` (x2), `guest-values-on-every-vm-test` (x4, all VMs), and an ERROR in `int-literal-test/decoded-rows-are-canonical-carriers-test`. Note: `int-contract-test/canonical-widths-at-the-boundaries` stayed green. It compares frozen fixture columns, not codec output, so it is not this mutation's detector. The design (2.1) names it as one, and that should be corrected to the tests above. |
| f | Narrowed shift count. `integer.cljc` `int-shift-left`: count `(bit-and n 63)` | JVM | `shifts` program | Red on all 4 VMs. First differing line: expected `18446744073709551616 -18446744073709551616 ...` (`1 << 64`, `-1 << 64`), got `1 -1 ...`. |
| g | Host `number?` as the only scalar gate (S6's mutation). `engine.cljc` `scalar?`: drop `(integer-host/big-carrier? x)` | Node (slow bodies on) | S6 `int-heap-test` | Re-executed here, because the S6 report is not reachable from this worktree. 2 errors: `big-integer-images-test` and `cell-refusal-test`. The co-run `c3-gate-test` stayed green under this mutation, so the heap tests are its detector, as S6 designed. |

Reverts. Each edit was restored by the inverse edit. The final
`git diff --quiet` on engine.cljc, integer.cljc and cbor.cljc succeeded.
The `py/key` hunk of prelude.cljc was restored to its S7 text. Restoration
was then confirmed by the final-tree runs in S7-1, which ran after the
last revert and are green.

Corrections to the brief this pack was asked to record. Its wording did
not match what was observed:
- (a) The red host is Node, not the JVM, and the detector is the
  promotion program, not int-literal or checked-add at 2^63.
- (b) On the JVM, `int_contract_test` did not detect skipped demotion.
  The JVM detectors are `yin.vm.integer-test` and the int-literal carrier
  test. On Node, the demotion program and `int-ops-test` detect it.
- (c) The detector is the `keys` program (2^53 + 1 vs 2^53.0), not
  `int_ops_test`.
- (d) The observed row is `7 // -2`, the first floored row.
  `divmod(-7, 3)` is not in the corpus.
- (f) The detector is the `shifts` program on `1 << 64`, not
  `int_ops_test` on `1 << 200`.

## S7-3: heap-test composition migrated

`test/yang/python/antlr/int_heap_test.cljc:30-41` (`opts`) now composes:

```clojure
:modules (-> (module/empty-registry)
             module/register-cell-module
             (data/register-data-module {::data/max-items 1048576})
             (prelude/register-integer-module
               {::integer/max-bits 100000, ::integer/max-digits 4300})
             prelude/admit)
```

`data/max-items` is registered, and `prelude/admit` runs after the integer
registrar, as the universal admission contract requires. The namespace ran
in the final slow verification (S7-1, `bb test:all`, all three hosts, slow
bodies enabled).

## Completion wording

- `docs/design/yang.antlr.md:2430` says the mutation evidence is in the
  S7 engineer's report. This pack is that evidence.
  `collab/1791271000000-compiler-engineer-python-c3-s7.findings.md`
  (IN PROGRESS) is superseded by this pack.
- `docs/design/yang.antlr.md:1978` lists S7 as landed. That is true only
  once the S7 commit lands; it is left as the commit's own wording.
- `docs/design/yang.antlr.md:2431` says S6's report holds the scalar-gate
  mutation. Row g above re-executes it on this tree.
- Design 2.1 names S0 `canonical-widths-at-the-boundaries` as a tag-3
  detector. It is not one (row e). The detectors are S6 T4 and the
  `int-contract-test` recompute and round-trip tests.
