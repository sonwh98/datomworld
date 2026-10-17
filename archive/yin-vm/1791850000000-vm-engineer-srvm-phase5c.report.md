Completed-GMT: 2026-10-11 07:44:54 GMT
Completed-Local: 2026-10-11 14:44:54 Asia/Bangkok


Implemented slice 5c within the six allowed source/test paths. Acceptance items 1–10 pass on JVM. Node passes items 1–6 and 8–10; item 7 is incomplete because the full C4 completion query did not finish. Item 11 is incomplete because Dart test execution is sandbox-blocked, Node C4 remains incomplete, and the orchestrator's broader gates were not run. This is an implementation report, not review or sign-off. Review/sign-off remains assigned to claude-fable-5-1.

## Changed files

New:

- `src/cljc/yin/vm/semantic_register/scan.cljc`: admitted-vector scanners, exact closure-owner lexical scope, position-bearing records, segment-qualified rows, and Datalog requirements.
- `test/yin/vm/semantic_register/scan_test.cljc`: the required matrices and named gates, independent FIFO map-AST emission oracle, malformed-vector checks, nil-key and conditional-range checks, and query-equivalence checks for the performance helpers.
- `test/yin/vm/semantic_register/scanner_goldens.cljc`: literal historical v3 answers for 27 B0/refusal rows, 32 register rows, and 8 C4 module ASTs, including normalized joins.

Modified:

- `src/cljc/yin/vm/linker.cljc`: additive `semantic-register-format`, two requires, and record-count documentation.
- `src/cljc/yin/repl/link.cljc`: additive format registration and record-count documentation.
- `test/yin/vm/linker_test.cljc`: the v4 contract row and `semantic-register-scanners-yield-position-bearing-records`.

`collab/` contains this report, verification logs, the verified source backup, and diagnostic/generation scripts. Nothing was staged or committed. No design, kernel, lowerer, production publisher, completion implementation, C4 track file, or 5a file was changed. The empty-obligations backend fixture and its exclusion in `linker_require_test.cljc` remain unchanged. The conditional §10.4 follow-up was NOT applied because all tri-host gates have not passed.

## Exact verification outcomes

| Lane/check | Tests | Assertions | Failures | Errors | Evidence |
|---|---:|---:|---:|---:|---|
| Initial JVM code G0 | 8 | 89 | 0 | 0 | Initial tool transcript, `clojure -M:test -n yin.vm.semantic-register.code-test` |
| JVM broad targeted fast | 316 | 4485 | 0 | 0 | `collab/5c-jvm-final-fast.log` |
| JVM final scanner fast | 10 | 567 | 0 | 0 | `collab/5c-scan-final-fast.log` |
| JVM final linker fast | 62 | 572 | 0 | 0 | `collab/5c-linker-g0-fast.log` |
| JVM required scanner slow | 3 | 208 | 0 | 0 | `collab/5c-c4-final-jvm.log`; all eight module completion markers |
| Node broad targeted fast | 371 | 5095 | 0 | 0 | `collab/5c-node-targeted.log`; required namespaces printed |
| Node final full slow attempt | INCOMPLETE | no final count | — | — | `collab/5c-node-slow-final.log`; interrupted, exit 130, at py completion query |
| Node final scanner fast (three slow bodies skipped) | 13 | 567 | 0 | 0 | `collab/5c-node-scan-final-fast.log` |
| Node final all-corpus goldens and joins | 2 | 135 | 0 | 0 | `collab/5c-node-goldens-join-final.log` |
| Dart | NOT RUN | 0 | — | — | `collab/5c-dart.log`; compiler setup blocked before test execution |

The broad fast runs preceded the final test-only completion-address and scalar-comparison optimization. The final scanner fast runs exercise the final helper and all its fast equivalence checks; production source did not change during these test-only optimizations. The final JVM slow run used the direct, unchanged completion call, which is also the final JVM helper branch.

Earlier completed checkpoints: scanner/linker JVM G0 was 71 tests / 1136 assertions / 0 failures / 0 errors (`5c-g0.log`); the separate all-corpus golden/join JVM gate was 2 tests / 135 assertions / 0 failures / 0 errors (`5c-goldens-join-jvm.log`). The first fast scanner run was 9 tests / 564 assertions / 118 failures / 0 errors (`5c-fast-first.log`): every failure was the intentionally empty golden fixture, not a hand-derived position/count or old/new/tree disagreement. No interrupted run is credited as a passing lane.

The final Node fast log explicitly prints `Testing yin.vm.semantic-register.scan-test`. The full scanner slow attempt requires `yang.python.antlr.lower-portable-test` and `yin.vm.parity-test`; The Node full attempt executes these before C4 but has no final aggregate count. The separate final Node scanner fast and golden/join selections have their own completed counts above.

### Commands

Broad JVM:

```sh
clojure -M:test -e :slow -n yin.vm.semantic-register.scan-test -n yin.vm.semantic-register.code-test -n yin.vm.semantic-register.linearize-test -n yin.vm.semantic-register.analysis-test -n yin.vm.semantic-register.load-paths-test -n yin.vm.semantic-register.vm-test -n yin.vm.semantic-register.walker-trace-test -n yin.vm.semantic-register.parity-test -n yin.vm.semantic-register.effects-test -n yin.vm.semantic-register.link-test -n yin.vm.linker-test -n yin.vm.linker-require-test -n yin.vm.linker-step-test -n yin.vm.linker-manifest-test -n yin.vm.completion-test -n yin.vm.code-test -n yin.repl.require-test -n yin.repl.serve-test
clojure -M:test -e :slow -n yin.vm.semantic-register.scan-test
clojure -M:test -e :slow -n yin.vm.linker-test
clojure -M:test -i :slow -n yin.vm.semantic-register.scan-test
```

Broad Node and final slow Node:

```sh
clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^(yin\\.vm\\.semantic-register\\.(scan|code|linearize|analysis|load-paths|vm|walker-trace|parity|effects|link)-test|yin\\.vm\\.(linker|linker-require|linker-step|linker-manifest|completion|code)-test|yin\\.repl\\.(require|serve)-test)$" :output-to "target/5c-node-tests.js"}'
DATOM_SLOW_TESTS=1 clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin\\.vm\\.semantic-register\\.scan-test$" :output-to "target/5c-node-scanners-slow.js"}'
```

Final Node artifact selections (the artifact was compiled with the scanner namespace regexp above):

```sh
DATOM_SLOW_TESTS=0 node target/5c-node-scanners-slow.js --test=yin.vm.semantic-register.scan-test
DATOM_SLOW_TESTS=1 node target/5c-node-scanners-slow.js --test=yin.vm.semantic-register.scan-test/goldens-match,yin.vm.semantic-register.scan-test/verify-outcomes-agree
```

Dart attempt, exit 1, NOT RUN:

```sh
bb src/dev/cljd_agg.clj --only yin.vm.semantic-register.scan-test,yin.vm.semantic-register.code-test,yin.vm.semantic-register.linearize-test,yin.vm.semantic-register.analysis-test,yin.vm.semantic-register.load-paths-test,yin.vm.semantic-register.vm-test,yin.vm.semantic-register.walker-trace-test,yin.vm.semantic-register.parity-test,yin.vm.semantic-register.effects-test,yin.vm.semantic-register.link-test,yin.vm.linker-test,yin.vm.linker-require-test,yin.vm.linker-step-test,yin.vm.linker-manifest-test,yin.vm.completion-test,yin.vm.code-test,yin.repl.require-test,yin.repl.serve-test
```

Exact initial sandbox errors (repeated with other temporary-file suffixes; full output is in the log):

```text
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 71: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.stamp.tmp.40053: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 78: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.realm: Operation not permitted
Execution error at cljd.compiler/mk-live-analyzer-info (compiler.cljc:214).
EOF while reading
cljd compile failed, exit 1
```

Lint and formatting:

```sh
clj -M:kondo --lint src/cljc/yin/vm/semantic_register/scan.cljc test/yin/vm/semantic_register/scan_test.cljc test/yin/vm/semantic_register/scanner_goldens.cljc src/cljc/yin/vm/linker.cljc src/cljc/yin/repl/link.cljc test/yin/vm/linker_test.cljc
cljstyle check src/cljc/yin/vm/semantic_register/scan.cljc test/yin/vm/semantic_register/scan_test.cljc test/yin/vm/semantic_register/scanner_goldens.cljc src/cljc/yin/vm/linker.cljc src/cljc/yin/repl/link.cljc test/yin/vm/linker_test.cljc
rg -n '[ \t]+$' src/cljc/yin/vm/semantic_register/scan.cljc test/yin/vm/semantic_register/scan_test.cljc test/yin/vm/semantic_register/scanner_goldens.cljc src/cljc/yin/vm/linker.cljc src/cljc/yin/repl/link.cljc test/yin/vm/linker_test.cljc
```

Kondo: exit 0, 0 errors / 0 warnings (`5c-kondo.log`). Cljstyle: exit 0 (`5c-cljstyle.log`). Trailing-whitespace scan: no matches, expected rg exit 1 (`5c-whitespace.log`). `git diff --check` and `git diff --cached --quiet`: exit 0.

## Numbered acceptance list (§10.3)

| Item | Result | Evidence |
|---:|---|---|
| 1 | PASS, JVM/Node | `positions-are-the-v4-table`: store keys `#{k :n 7}`, define key `#{y}`, FFI `#{:op/echo}`, resume `#{:p}`, all five stream effects, empty park footprint; mutations M4–M6/M8 fail these assertions. |
| 2 | PASS, JVM/Node | `free-occurrences-use-the-owner-tree`: all five exact occurrence sequences, a/b bound, and define-then-call pcs 3/4/8. M1/M2 fail this named test. |
| 3 | PASS, JVM/Node | `definitions-and-sites`: exact definitions, pcs and site counts, including 5/6/11 and 2/7/15/16; all-corpus answer equality includes old application counts. M7 fails this test. |
| 4 | PASS, JVM/Node | `footprint-mnemonics-is-the-v3-table-minus-push`, plus its key set equals `code/mnemonics`. |
| 5 | PASS, JVM/Node | `scanners-agree-on-b0`: count 27 = 26 B0 rows plus refusal; unordered tree facts, independent ordered map-AST oracle, full/projected tree-query equivalence, original completion names, historical maps, and normalized joins. |
| 6 | PASS, JVM/Node | `scanners-agree-on-the-register-corpus`: asserted 32 rows, same comparisons. |
| 7 | FAIL tri-host / PASS JVM; Node incomplete, Dart NOT RUN | Guarded slow `scanners-agree-on-the-c4-modules` actually completed py/pysp/k/m/t/w/e/n on JVM, all comparisons plus raw names with yin/def removed and discharged names equal primitive keys; eight completion markers in the final JVM slow log; Node completed the projected tree query for py but did not complete its completion query, so no Node C4 success is claimed. |
| 8 | PASS, JVM/Node | Guarded slow `goldens-match` compares every one of the 67 historical maps with live old and new answers, normalized joins included. Fixture counts are 27/32/8 (`5c-fixture-counts.log`). |
| 9 | PASS, JVM/Node | `semantic-register-scanners-yield-position-bearing-records` and the v4 row of `format-records-name-their-contract`; linker fast 62/572, broad Node includes linker namespace. |
| 10 | PASS, JVM/Node | Guarded slow `verify-outcomes-agree` covers all 67 programs/modules; use-before-definition is explicitly `{:status :refused :reason :use-before-definition :name n}`. Refusals were retained, not filtered. |
| 11 | FAIL / incomplete tri-host gate | Changed-path kondo/format/whitespace checks clean and targeted JVM/Node lanes green, including required Node namespace marker. Node C4 is incomplete. Dart is NOT RUN. `bb test:sub yin.vm` and `bb test:changed` were not claimed or run; broader orchestrator gates remain outstanding. No full `bb test` was run. |

Every hand-derived scanner pc/count in this slice was asserted on the FIRST scanner run. None was wrong; no hand-derived expectation was silently adjusted. The empty historical fixture accounted for all first-fast-run failures.

## Tests that can fail: source mutations

Verified backup before any mutation: `collab/5c-scan.cljc.verified-backup`, copied from `src/cljc/yin/vm/semantic_register/scan.cljc` and verified with `cmp`. Script: `collab/5c-mutations.clj`; actual run `clojure -M:cljs collab/5c-mutations.clj`; log `collab/5c-mutations.log`.

| Mutant | Temporary source change | Named failing test in `yin.vm.semantic-register.scan-test` | Failing assertions | Errors | Restore |
|---|---|---|---:|---:|---|
| M1 | Replace lexical owner chain with `[]` | `free-occurrences-use-the-owner-tree` | 6 | 0 | verified |
| M2 | Force every `:in-body?` to false | `free-occurrences-use-the-owner-tree` | 3 | 0 | verified |
| M3 | Ignore conditional target ranges | `definitions-respect-conditional-ranges` | 1 | 0 | verified |
| M4 | Read store-get rd as its key | `positions-are-the-v4-table` | 1 | 0 | verified |
| M5 | Read FFI rd as its op | `positions-are-the-v4-table` | 1 | 0 | verified |
| M6 | Read resume register as its pid | `positions-are-the-v4-table` | 1 | 0 | verified |
| M7 | Exclude FFI application sites | `definitions-and-sites` | 1 | 0 | verified |
| M8 | Remove stream-close effect normalization | `positions-are-the-v4-table` | 1 | 0 | verified |
| M9 | Emit pc+1 in segment rows | `segment-rows-carry-a-and-pc` | 1 | 0 | verified |

All nine were killed by assertion failures, not compilation failures. The script restored from the backup in `finally` after EACH mutation, checked `cmp` exit 0, and checked `git diff --quiet HEAD -- src/cljc/yin/vm/semantic_register/scan.cljc` exit 0 each time. The latter has no HEAD preimage for this new/untracked source file, so the byte-for-byte `cmp` is the substantive restoration check. Final source still matches the backup. No surviving mutant.

## Ambiguities, findings, and actions

- **Historical baseline.** §7.4 names ab952ace; the authorized worktree is based at 993d5411. `git diff ab952ace HEAD -- src/cljc/yin/vm/linker.cljc src/cljc/yin/vm/completion.cljc src/cljc/yin/vm/linearize.cljc src/cljc/yin/vm/code.cljc src/cljc/yin/vm.cljc` was empty. Thus the old scanner/compiler surfaces used for generation are byte-identical to the specified baseline. Generated once on JVM through public `print-goldens`, pasted as quoted data, then formatted; no manual answer edits.
- **Tree-oracle performance reading.** A whole `vm/free-names` query on the large C4 tree was impractically slow in preliminary runs. The smallest reading taken is that a query-equivalent union of public `vm/free-names` calls remains that oracle: for each name retain every variable occurrence of it and every lambda that contains it as a parameter, preserving original ids, paths and operands. This retains every possible witness of its `occ-bound?` rule and removes no variable occurrence for that name. Every fast matrix row compares this with the full, original relation (59 cases); the separate FIFO map-AST oracle still checks all C4 facts. No design or declaration was weakened.
- **Node completion performance.** The original public completion query remained on the original, full v3 vector. The final Node-only helper temporarily specializes the public Jing hash function for that identical vector to return its already-computed default-digest address object; its two arities otherwise delegate to the original function. It also caches the original public `cbor/content-key` results for safe nonzero JavaScript integers and identifiers, using distinct keyword/symbol and namespace/name fields as cache keys; zero, unsafe numbers, numeric carriers and other values delegate directly. This avoids signed-zero and slash-crossed-identifier collisions. Address values, comparison-key values, query clauses, inputs, and completion source are unchanged. `completion-address-reuse-preserves-answers` checks original versus helper on all 59 fast cases. JVM and Dart retain the direct call; with-redefs is excluded from Dart, where unavailable. This is test instrumentation, not a production adapter or cache.
- **Measured C4 scale.** Public-surface probe (`5c-scope-cost.clj`/`.log`) measured py's original v3 vector at 26,062 instructions, 909 closures, 5,907 variable occurrences, 24,960 scope facts, maximum variable nesting depth 14. Historical py answers have 364 free names, 3,347 free occurrences, 432 definitions, 3,682 applications (`5c-c4-golden-counts.log`). The direct completion oracle was a substantial slow-gate cost, not a scanner disagreement.
- **Nil/false store keys outside the fixed corpora.** Existing v3 definition scanners use `when-let` on the extracted key and omit nil/false keys. The new scanner follows §7.2 literally and retains the key when the opcode is a write, including nil. `definitions-respect-conditional-ranges` covers nil in both definitions and requirements, on JVM and Node. Old scanners were not changed; this edge case was not inserted into historical parity corpora to manufacture agreement.
- **Prerequisite build.** A broader JVM attempt stopped before tests with `ClassNotFoundException yang.python.antlr.gen.Python3Lexer`. `bb gen:python-antlr` then failed in the sandbox with `UnresolvedAddressException`; `clojure -M:antlr-gen` was retried with approved network access. It verified pinned digests and generated four Java files/classes under ignored `build/` (`5c-antlr-build-retry.log`). Subsequent JVM tests passed.
- **Interrupted attempts, not credited.** An initial golden-helper invocation incorrectly used the test-runner alias with `-e`; it was interrupted before obtaining test counts and regenerated correctly via `-M:cljs -e`. The first Node config-merge incorrectly nested `:builds`, which started unrelated default suites; it was interrupted and replaced with the documented build-level `:ns-regexp`. Preliminary slow C4 runs were interrupted while diagnosing oracle cost, including the first Node address-reuse attempt (`5c-node-address-reuse-interrupted.log`). An unnecessary original linker's 1,000-definition slow test attempt was interrupted; its fast namespace was then run separately. These are not passing lane evidence.
- **Lint/build warnings.** An intermediate unconditional Jing test require was unused on JVM; it was changed to a cljs-only require with cljd first, and final changed-path kondo is clean. An earlier Node build reported the unchanged `yin.vm.linker.head/board` namespace/var clash; no unrelated source was edited.
- **Workflow ordering.** The initial scanner source draft preceded the new test file. The first missing-fixture red run and the nine named source-mutant failures are the negative evidence; this report does not claim initial tests-first ordering was followed.
- **Gate-command conflict/readings.** §10.3 item 11 names broad three-host commands; the task's verification brief assigns broader G2/G3 reruns to the orchestrator and asks for the explicit targeted namespace lanes. Those targeted lanes were run; item 11 is marked incomplete rather than asserting the broad commands passed. Dart was attempted exactly as requested and is honestly NOT RUN.

## Unresolved concerns and incomplete work

Node C4 completion-oracle execution, Dart compilation/test execution, full three-host subsystem/changed gates, and non-author review/sign-off remain outstanding. The task's two-line linker-require fixture follow-up is intentionally unperformed pending all gates and the orchestrator's ruling. No functional scanner mismatch was found in completed matrices; Node C4 has not been established. The original Node completion attempt, address-reuse attempt and final scalar-key-cache attempt all remained at the py completion query and were interrupted rather than weakening the gate. No assertion failure or final count is attributed to those interruptions. No production pipeline cutover was attempted.
