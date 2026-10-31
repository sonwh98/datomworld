Completed-GMT: 2026-10-11 16:36:03 GMT
Completed-Local: 2026-10-11 23:36:03 Asia/Bangkok

Test-only round 2 on branch srvm-phase5b, HEAD b2eadc83. No git add or commit. Read reviewer F1 and design §2 before deriving the image.

Changed test/yin/vm/semantic_register/derive_test.cljc: retained the existing widened-layout-interval case, corrected its comment to describe numeric-note image coverage, and added a second case for (if c (resume :p 1) (g 2)). It pins both A and R. The new testing label states that the widened start reserves T0 before the else-arm temporaries.

Independent §2 derivation: definitions are v1 at 0, v2 at 2, v3 at 5, v4 at 6, v0 at 7. L(4)={0}, and the halt at pc 8 uses v0, so the intervals are v1:[0,1], v2:[2,3], v0:[4,8], v3:[5,7], v4:[6,7]. The requested/reviewer's [4,7] endpoint omits the halt live point; the correct endpoint under §2.2 is 8. This does not change the allocation or image. With strict end < pc expiry, v1 and v2 reuse T0; v0 takes T0 at pc 4; v3 takes T1 at pc 5; v4 takes T2 at pc 6. No slots expire at call pc 7. Locals=0, peak=3, no definition-less ids. The call's live operand is [] because its live-out destination is removed.

Derived image:
```clojure
{:bodies [{:locals 0 :registers 3 :start 0 :end 8}]
 :instructions [[:load-free 0 c] [:branch-false 0 5] [:const 0 1]
                [:resume :p 0] [:jump 8] [:load-free 1 g] [:const 2 2]
                [:call 0 1 [2] false []] [:halt 0]]}
```
The derived image matched the reviewer's probe exactly and matched the emitter's output in the passing baseline and restored runs.

Before mutation, copied derive.cljc to collab/p5b-r2-derive.verified-backup.cljc, verified cmp exit 0 and matching SHA-256 0eeb943f952f95f81cd0654dd785bc81f9bcbef5a9e836ac5d91108dea7e65b4. Verified git diff --quiet HEAD -- src/ exit 0.

Applied exactly reviewer's M06: interval start changed from minimum of all points to minimum of syntactic definition points; end remained widened. Named run:
`clojure -M:test -e :slow -n yin.vm.semantic-register.derive-test -v yin.vm.semantic-register.derive-test/widened-layout-interval`
Result: 1 test, 3 assertions, 1 failure, 0 errors, exit 1. Failing test widened-layout-interval at derive_test.cljc:706, label “§2.2 widened start reserves T0 before the else-arm temporaries”. Actual mutant image has [:load-free 0 g], [:const 1 2], [:call 2 0 [1] false []], [:halt 2]: exactly g→T0, 2→T1, v0→T2. Log: collab/p5b-r2-m06.log. An initial attempt used an unqualified -v name and failed var resolution before tests ran; reran with the qualified var shown above.

Restored derive.cljc from the verified byte backup. cmp exit 0 and git diff --quiet HEAD -- src/ exit 0 after restoration and again at final verification. Only tracked change is derive_test.cljc (13 insertions, 2 deletions).

All commands foreground; slow tests excluded; every -n namespace written literally.

| Lane | Exact command | Result |
|---|---|---|
| JVM baseline | clojure -M:test -e :slow -n yin.vm.semantic-register.derive-test -n yin.vm.debruijn-register-code-test | 22 tests, 6202 assertions, 0 failures, 0 errors; exit 0 |
| JVM restored final | clojure -M:test -e :slow -n yin.vm.semantic-register.derive-test -n yin.vm.debruijn-register-code-test | 22 tests, 6202 assertions, 0 failures, 0 errors; exit 0 |
| M06 named test | command above | 1 test, 3 assertions, 1 failure, 0 errors; exit 1 |
| kondo | clj -M:kondo --lint test/yin/vm/semantic_register/derive_test.cljc | 0 errors, 0 warnings; exit 0 |
| cljstyle | cljstyle check test/yin/vm/semantic_register/derive_test.cljc | exit 0, no output |
| Node / Dart | Orchestrator-owned per task | Not run in this session |

Logs: collab/p5b-r2-jvm-baseline.log, collab/p5b-r2-jvm-final.log, collab/p5b-r2-m06.log, collab/p5b-r2-kondo.log, collab/p5b-r2-cljstyle.log.
