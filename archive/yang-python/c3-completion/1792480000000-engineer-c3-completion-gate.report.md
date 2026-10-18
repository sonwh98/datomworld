# C3 completion gate report (ruling 14), head 79db800e, branch c3-completion

Verification only: no commits, source restored after every mutation (git status shows only untracked collab/).
CPython 3.9.6 (default, May 22 2026). Scratch tooling: collab/c3gate/ (bb.edn, regen*.py).

## 1. Twelve lanes (slow-guarded bodies ON; fast lanes skip c3-gate-test)
| Lane | Result |
|---|---|
| JVM, 4 VMs (source-level) | 221 tests / 5793 assertions / 0 failures (15 python namespaces incl. c3-gate-test, c3-gate-parser-test, e2e-c1, e2e, int-*, float-*, prelude-parity, yang.python-test, yin.repl.python-antlr-test); c3-gate-test alone 3 / 70 / 0 |
| Node, 4 VMs (parserless) | 322 tests / 42358 assertions / 5 failures (see findings F1, F2); c3-gate-test clean |
| Dart VM, 4 VMs (parserless) | 276 tests: 274 pass, 2 fail (same F1, F2); c3-gate-test clean (Dart prints tests, not assertions) |
The four VMs (:ast-walker :semantic :stack :register) are run per host by c3_gate_test.
Dart-to-JS not claimed/run.

## 2. Golden byte identity (Jing + C3 goldens)
JVM 69/4405/0; Node 99/7077/0; Dart 81 tests, all pass. CONFORMANCE-DIGEST identical on all three hosts:
canonical n=208 8268b4d3...c9c31; refusal n=154 915515bc...ff22daa. int-contract/int-heap pass on all three.
Working tree has no fixture diffs. Jing fixture files were changed after S1 by non-C3 commits (c11e1b953 BLAKE3 digests,
8416a60bb equality-collapse refusals, ac9ecb8b9); all hosts pass against the current committed pins.

## 3. Full C1 corpus
e2e-c1-test (JVM-only .clj) inside the 221/5793/0 JVM run. No Node/Dart form of C1 exists beyond the shared int-ops/prelude-parity namespaces.

## 4. Operand table
No RNG: the "table" is c3-corpus-v1 (10 programs) plus int-ops-v1/v2/v3, int-conv-v1, int-contract-v1, float-text-v1.
All seven .generate.py scripts re-run under CPython 3.9.6 reproduce their checked-in .txt byte for byte (IDENTICAL).
Coverage read in c3_programs/generate.py: 2^53 (promotion, keys), 2^63 (promotion), 2^64 (all), promotion then cancellation
(n-n, s-5*2**64, m//2 loop), divmod, shifts (incl. 2**70 counts), power, conversions, keys, hash(-1), signed zero.
The limits and small programs are hand pins, not CPython (labelled).

## 5. Mutations (all temporary, restored by git checkout)
1. Disabled promotion: integer.cljc fast-add -> `(if (and (instance? Long a) (instance? Long b)) (unchecked-add ..) ..)`.
   c3-gate-test: 4 failures, `promotion` x 4 VMs; `y = 2**63-1; print(y+1, y+1-1==y, ..)` gave
   "-9223372036854775808 False -9223372036854775809" vs expected "9223372036854775808 True ...".
2. Skipped demotion: integer.cljc `out` returns `clojure.lang.BigInt/fromBigInteger b` instead of host/demote.
   c3-gate-test on JVM: 0 failures (NOT detected; see F3). Detected by int-literal-test: 28 failures
   (decoded-rows-are-canonical-carriers-test 17, evaluated-literals-test 10, lowered-rows-hold-only-safe-integers-test 1).
3. Double-coerced keys: prelude py/key int arm -> `(py/float-key (integer/to-float n))`.
   c3-gate-test `keys` x 4 VMs: `{.. 9007199254740993:'odd', 9007199254740992.0:'even'}` collapsed to one entry; `len(e)` 4 -> 2.
4. Omitted floor adjustment: int-floor-div-mod `adjust? false`. c3-gate-test 8 failures: `divmod` x4 and `keys` x4
   (e.g. divmod(7,-2) printed (-3, 1)-class truncating results).
5. abs(n) for tag 3: cbor.cljc int-wire `(magnitude-bytes (b- zero b))`. c3-gate-test passes (codec not exercised);
   int-contract-test 8 failures: integer-rows-recompute-on-this-host ("-2^64-1 cbor" c349010000000000000000 vs ...01),
   text-round-trips-through-the-module x2, the-file-holds-exactly-the-rendered-text, guest-values-on-every-vm-test x4.
6. Host number? as only scalar gate: removed big-carrier? from data-number?, engine scalar?, vm machine-data?, values/kind-of.
   JVM: 0 failures in c3-gate/int-heap/int-contract (BigInt is a java Number; not observable, as the spec says only JS/Dart fail number?).
   Node (c3-gate+int-heap+int-contract): 326 failures + 4 errors in 40 tests (c3-gate-test 40, int-ops cpython fixtures 232,
   int-heap 9, ...), e.g. `promotion :ast-walker`.
7. Prematurely narrowed shift count: count! returns `(host/from-native (.longValue n))`.
   c3-gate-test 8 failures: `shifts` x4 and `limits` x4 (1 >> 2**70 etc.).

## Findings
F1. dht-import-test-test fails on Node and Dart (dht_import_test.cljc:368): own publication differs from the JVM artifact in key
    ":importers". C4/publication area, not a C3 detector; JVM passes.
F2. yin.repl.python-antlr-test/vm-rebuild-is-a-new-process-test fails on Node (4 assertions, reproduced standalone) and Dart
    (expected :register vs actual :semantic). JVM passes. Not a C3 detector; looks like a vm-type default/REPL issue.
F3. Skipped-demotion mutation is invisible to the C3 gate on the JVM (BigInt = long under Clojure =); the gate catches it only
    on Node/Dart. On the JVM only int-literal-test catches it. The ruling "each mutation shown failing its detector" holds only
    per-host for #2 and #6; no JVM-side c3 gate detector for demotion.
F4. Mutation 5 (tag 3) is not caught by the C3 gate programs, only by int-contract-test (consistent with the doc, which says so).
F5. Spec 8.5.4 names yin/vm/ucf/scalars-v1.txt as the pinned scalar body bytes; no such file exists in the tree
    (test/resources/yin/vm/ucf has only ledger-v2.txt and rows-checkpoint-v3.txt). Scalar bytes are pinned in int-contract-v1.txt.
F6. The fast lanes (bb test:clj/cljs/cljd) skip c3-gate-test (^:slow / guarded); the gate only runs under test:slow or test:all.
F7. Node `bb test:slow:cljs` runs every guard namespace and was killed at the 2h background cap in the semantic namespaces
    (python namespaces had all completed); I reran the python/C3 namespaces with a scoped ns-regexp for the counts.
    Concurrent bb test:cljs and test:cljd collided in gen:antlr ("Generated sources differ from the recorded digests");
    rm -rf build/antlr lib/antlr and sequential runs fixed it.
    The cljd run used --only for the python/C3 namespaces, not the full aggregator; full cljd suite not run.
