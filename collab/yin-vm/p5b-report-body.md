Author implementation report; review and sign-off remain assigned to claude-fable-5-1 (non-author family). Worktree /Users/sto/workspace/datomworld-srvm-p5b, branch srvm-phase5b. Nothing staged or committed. Governing design and kernels were not changed.

Changed files

- Modified: src/cljc/yin/vm/semantic_register/derive.cljc — register-image lowers A alone, uses resolved vector and phase-3 analysis, allocates per body with total-order expiry/assignment and lowest free slot, retains A layout, reserves structural slots, fills live using rcode/body-liveness. No old compiler or kernel dependency was added to production.
- Modified: test/yin/vm/semantic_register/derive_test.cljc — replaced obsolete 5a stub refusal; literal R goldens and checksums, structural/layout/scope checks, independently reconstructed slot maps and virtual/physical liveness agreement, structural-slot checks, determinism and all address-law legs, execution oracle, FFI/stream operand goldens and successful runtime rows, guarded slow C4 oracle with four backends, numeric interval checks.
- New: test/yin/vm/debruijn_register_code_test.cljc — surviving descriptor-hash golden; old copy left untouched.
- collab/: report, verified source backup, mutation driver and verification logs only.

Lane outcomes

- Initial derive G0 after implementation: 11 tests / 2,956 assertions / 1 failure / 0 errors, solely obsolete register-image-is-not-in-5a; replaced as required.
- First R-golden/validator/runtime/liveness run: 19 tests / 6,177 assertions / 0 failures / 0 errors. All literal design R images matched the first run. Later combined G0 before numeric interval additions: 20 tests / 6,178 assertions / 0 failures / 0 errors.
- Final changed-namespace G0: clojure -M:test -e :slow -n yin.vm.semantic-register.derive-test -n yin.vm.debruijn-register-code-test — 22 tests / 6,200 assertions / 0 failures / 0 errors (collab/p5b-g0-final.log).
- Standalone descriptor G0: clojure -M:test -n yin.vm.debruijn-register-code-test — 1 test / 1 assertion / 0 failures / 0 errors (collab/p5b-descriptor-g0.log).
- Broader JVM fast lane: final 321 tests / 10,150 assertions / 0 failures / 0 errors (collab/p5b-jvm-final.log). Earlier run was 319 / 10,138 / 0 / 0 before the two additional numeric tests.
- JVM C4 slow: PENDING.
- Targeted Node (DATOM_SLOW_TESTS=1): PENDING. Confirmed Testing yin.vm.semantic-register.derive-test and Testing yin.vm.debruijn-register-code-test in collab/p5b-node-targeted.log. Required namespaces plus transitive test dependencies run.
- Dart: NOT RUN. Attempt exited 1 before tests. Exact command below; errors: Flutter internal/update_engine_version.sh could not write bin/cache/engine.realm and engine.stamp.tmp.79085 (Operation not permitted); cljd.compiler/mk-live-analyzer-info reported EOF while reading; Thread-0 RuntimeException: Operation not permitted (sysctl failed); cljd compile failed, exit 1. Full output: collab/p5b-dart.log. No Dart tests/assertions executed; no three-host-green claim.
- Final kondo: clj -M:kondo --lint on all three changed source/test paths — errors 0, warnings 0. cljstyle check on those paths — exit 0. git diff --check — exit 0. rg -n '[ \t]+$' on those paths — exit 1 (no matches).
- No full bb test or G2/G3 was run. Those remain the orchestrator's lanes.

Numbered acceptance (§10.2)

1. PASS: register-goldens has all ten images for rows 1–9 (row 7 has two), instructions and descriptors literal; ten public register-hash pins independently printed from those literal images; tail-operand call live is [].
2. PASS on JVM: register-images-validate runs all 94 C rows; validator nil; independent body count/ranges/arity/final terminator, no move, every operand within temporary bank, lexical depth/position checks.
3. PASS on JVM: two-liveness-analyses-agree independently pairs semantic and emitted operands to reconstruct slots, compares physicalized virtual live-out minus rd at every boundary over actual successors, and adds row 9. Tail-call expected and actual live both [].
4. PASS on JVM: structural-slot covers the three named fixtures; independently computed highest real temporary plus one reservation, definition-less operands mapped to structural slot only at unreachable PCs, absent from every reachable boundary live.
5. PASS on JVM: register-determinism compares structures, encoded bytes, hashes for every C row, and same-A/address on all three omitted-fact pairs.
6. PASS on JVM: register-execution-oracle asserts 26 B0, 12 register value rows, 19 equal throws, 1 successful zero-arg FFI; adds successful two-operand :op/echo bridge and stream-put onto a made stream on both pipelines.
7. PASS on JVM: lexical-address-law compares A, resolved A, R, H, old resolved tuple reader for all 94 rows; existing bound-depth/duplicate-binder guards retained.
8. PASS on JVM: worked-example-slots pins row 2 and [:call 3 1 [2] false [0]] literally.
9. PENDING: guarded ^:slow c4-execution-oracle, ten pinned root imports and eight module-root bodies, both kernel families. New-image install-child path explicitly remains phase 8.
10. PASS on JVM: surviving golden-descriptor-hash-test pins 7eacba41f9f1548d18b724cbd2db87945f09ec7b4ab2f303399e9b6497007632; old copy unchanged.
11. FAIL as a three-host gate: kondo clean and required JVM lane green; Dart blocked before test execution. Node PENDING. bb test:sub yin.vm itself not invoked; user-requested explicit scoped namespace lanes used instead.

Tests that can fail

Verified backup FIRST: cp derive.cljc collab/p5b-derive.verified-backup.cljc, then cmp (exit 0). collab/p5b-mutations.clj asserts equality before mutation; each mutation is applied to actual source, load-file reloads it, a named test runs with fresh report counters; finally copies verified backup back with REPLACE_EXISTING, asserts equality, and reloads restored source. Driver exit 0. Final cmp exit 0. Every test name below is in yin.vm.semantic-register.derive-test; full failures are retained in collab/p5b-mutations.log.

| Mutation | Named test | Assertion failures | Errors | Outcome |
|---|---|---:|---:|---|
| inclusive-expiry | register-goldens | 9 | 0 | KILLED |
| highest-free-slot | worked-example-slots | 1 | 0 | KILLED |
| drop-live-points | register-goldens | 9 | 0 | KILLED |
| overwrite-local-bank | register-goldens | 1 | 0 | KILLED |
| omit-structural-reservation | structural-slot | 14 | 0 | KILLED |
| drop-saved-window | two-liveness-analyses-agree | 19 | 0 | KILLED |
| reverse-ffi-operands | register-operand-goldens | 1 | 0 | KILLED |
| swap-stream-put-operands | register-operand-goldens | 1 | 0 | KILLED |
| shift-body-and-branch-targets | register-images-validate | 0 | 1 | KILLED |

Eight mutants fail assertions; the ninth target-shift mutant produces a structural test error. No surviving mutants. The required git diff --quiet HEAD -- src/cljc/yin/vm/semantic_register/derive.cljc was executed after restoration and returned 1: the intentional, unstaged slice implementation differs from HEAD. It cannot establish restoration to an uncommitted implementation. The verified backup cmp (exit 0) and driver equality assertions establish byte-for-byte restoration; HEAD was not rewritten and changes were not staged to fabricate a clean comparison.

Ambiguities, findings, and actions

- SHA-256 ruling versus unchanged public checksum: owner says only existing descriptor-hash may use SHA-256, while §2.1/§8.1/acceptance 1 require untouched rcode/register-hash and its public pins. Landed debruijn_register_code.cljc:261–270 uses jing/sha256 inside register-hash as well as descriptor-hash. Smallest scope reading: A remains the sole identity (existing BLAKE3 projection unchanged), R pins are existing format checksums, and the out-of-scope hash function stays untouched. This conflict is reported for owner/reviewer resolution; no new digest function or identity was added.
- HEAD restoration-check conflict: deliberate implementation changes are unstaged, so git diff --quiet HEAD necessarily returns 1 after correct mutant restoration. Used the requested check and reported its result, with verified byte comparison to the pre-mutation implementation as restoration evidence.
- §6.3 numbers: literal R PCs, bodies, locals/register counts, physical slots and lives all matched FIRST golden run. Existing hand-derived-liveness runs all stated L values; new hand-derived-register-intervals pins all stated intervals, including lambda body; widened-layout-interval verifies the {4,5,6} note and output. No wrong design number found or silently changed. A missing quote on the extra local test literal caused an intermediate unresolved-symbol compilation error, then was fixed without changing any number.
- Production derives R solely from A; old compilers appear only in execution/address test oracles. Facts A omits cannot affect R/H: same-vector and address assertions cover macro flag, shared eid and non-application tail false, with byte/hash equality. Diagnostics remain outside identity.
- Initial Node config-merge mistakenly nested :builds/:test and selected unrelated namespaces. Interrupted that run with Ctrl-C (exit 130); not counted as a passing lane. pkill diagnosis was blocked by process-list sandbox access; no unrelated process was stopped. Corrected to documented top-level :ns-regexp and recorded targeted output.
- JVM C4 publication is expensive in existing code. Read-only process diagnostics showed CPU activity; a nonterminating SIGQUIT dump identified dao.jing.cbor decoding under yin.vm.linker.closure/walk-images and publish-guest-module!, rather than new allocation. jcmd attach was attempted but could not attach within 10,500 ms. No out-of-scope optimization was made.

Unresolved concerns / incomplete work

- Dart lane requires orchestrator execution outside the sandbox. Review/sign-off by claude-fable-5-1 remains outstanding. G2/G3 remain orchestrator work.
- SHA-256 wording conflict above requires review; implementation respects the explicit file scope and unchanged formats.
- C4 (c), newly published module image install-child execution, remains phase 8 by design.
- Pending C4/Node counts will be replaced with observed outcomes before delivery.

Exact final scoped lane commands

```sh
clojure -M:test -e :slow -n yin.vm.semantic-register.code-test -n yin.vm.semantic-register.linearize-test -n yin.vm.semantic-register.analysis-test -n yin.vm.semantic-register.load-paths-test -n yin.vm.semantic-register.vm-test -n yin.vm.semantic-register.walker-trace-test -n yin.vm.semantic-register.parity-test -n yin.vm.semantic-register.effects-test -n yin.vm.semantic-register.link-test -n yin.vm.semantic-register.derive-test -n yin.vm.debruijn-register-code-test -n yin.vm.debruijn-register-compile-test -n yin.vm.debruijn-register-contract-test -n yin.vm.debruijn-code-test -n yin.vm.debruijn-linearize-test -n yin.vm.debruijn-resolve-test -n yin.vm.debruijn.register-test -n yin.vm.debruijn.stack-test -n yin.vm.debruijn-register-effects-test -n yin.vm.debruijn.stack-effects-test -n yin.vm.debruijn.stack-parity-test
clojure -M:test -i :slow -n yin.vm.semantic-register.derive-test
DATOM_SLOW_TESTS=1 clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^(yin\\.vm\\.(semantic-register\\.(code|linearize|analysis|load-paths|vm|walker-trace|parity|effects|link|derive)|debruijn-register-(code|compile|contract|effects)|debruijn-(code|linearize|resolve)|debruijn\\.(register|stack|stack-effects|stack-parity))-test)$"}'
bb src/dev/cljd_agg.clj --only yin.vm.semantic-register.derive-test,yin.vm.debruijn-register-code-test,yin.vm.semantic-register.code-test,yin.vm.semantic-register.linearize-test,yin.vm.semantic-register.analysis-test,yin.vm.semantic-register.load-paths-test,yin.vm.semantic-register.vm-test,yin.vm.semantic-register.walker-trace-test,yin.vm.semantic-register.parity-test,yin.vm.semantic-register.effects-test,yin.vm.semantic-register.link-test,yin.vm.debruijn-register-compile-test,yin.vm.debruijn-register-contract-test,yin.vm.debruijn-code-test,yin.vm.debruijn-linearize-test,yin.vm.debruijn-resolve-test,yin.vm.debruijn.register-test,yin.vm.debruijn.stack-test,yin.vm.debruijn-register-effects-test,yin.vm.debruijn.stack-effects-test,yin.vm.debruijn.stack-parity-test
clj -M:kondo --lint src/cljc/yin/vm/semantic_register/derive.cljc test/yin/vm/semantic_register/derive_test.cljc test/yin/vm/debruijn_register_code_test.cljc
cljstyle check src/cljc/yin/vm/semantic_register/derive.cljc test/yin/vm/semantic_register/derive_test.cljc test/yin/vm/debruijn_register_code_test.cljc
```
