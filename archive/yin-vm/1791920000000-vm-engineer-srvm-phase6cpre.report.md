Completed-GMT: 2026-10-11 06:59:41 GMT
Completed-Local: 2026-10-11 13:59:41 Asia/Bangkok

# VM Engineer — semantic-register-vm phase 6, slice 6c-pre

Implemented in `/Users/sto/workspace/datomworld-srvm-p6cpre`, branch `srvm-phase6cpre`. No staging or commits. No design edits, semantic-register edits, register-kernel edits, or existing test edits. Sign-off remains with the designated non-author claude-fable-5-1 reviewer; this is an implementation report, not review sign-off.

## Changed files

- Modified: `src/cljc/yin/vm/debruijn/stack.cljc`. Tail `:call` effect park builders add `:resume-mode :return-result`. Restore completes the retained-FFI/response steps first, then routes marked entries through the same return transition as `:return`. That transition pops the innermost frame, truncates to its cumulative stack base, pushes the delivered value, restores caller pc/frames/store context, or halts with `[value]` and clears store context when K is empty.
- New: `test/yin/vm/debruijn/stack_tail_completion_test.cljc` — 9 tests / 25 assertions on JVM and Node.
- New: this report. Supporting command scripts, logs, mutation driver and verified backups are under `collab/6cpre-*`; they are evidence artifacts and remain unstaged.

The marker is added only by effect park builders for a tail `:call`; ordinary instruction parks, captured continuations, non-tail calls, and synchronous call results retain their existing paths.

## Exact lane outcomes

| Lane | Outcome | Counts and evidence |
|---|---|---|
| G0 JVM, new namespace | PASS after fixture correction | 9 tests, 25 assertions, 0 failures, 0 errors; `6cpre-g0.log` |
| JVM selected regression group, final source | PASS, exit 0 | 78 namespaces, 1,345 tests, 19,782 assertions, 0 failures, 0 errors; `6cpre-jvm.log` |
| Node selected regression group | PASS, exit 0 | 84 tested namespaces (78 selected plus 6 imported), 1,480 tests, 57,276 assertions, 0 failures, 0 errors; `6cpre-node.log` |
| Node new namespace, final source after redundant-let cleanup | PASS, exit 0 | 1 namespace, 9 tests, 25 assertions, 0 failures, 0 errors, 0 compiler warnings; `6cpre-node-final.log` |
| Dart selected regression group | NOT RUN: compilation blocked, exit 1 | No test count; `6cpre-dart.log`, exact command below |
| kondo, both changed code/test paths | PASS, exit 0 | 0 errors, 0 warnings; one existing info diagnostic for excluded `eval`; `6cpre-kondo.log` |
| cljstyle check, both changed code/test paths | PASS, exit 0 | Empty output; `6cpre-style.log` |
| Trailing whitespace and git diff check | PASS | No matches in changed code/test paths, `rg` exit 1 as expected; empty `6cpre-whitespace.log`; `git diff --check` exit 0 |

The JVM command excludes `:slow`; none of the requested stack, debruijn, parity, contract, semantic-FFI, or continuation-invoke suites contains a slow test. Node's imported slow Python/DHT bodies print SKIP. No full `bb test` was run. `6cpre-namespaces.txt` records all 78 selected namespaces, including the three stack suites, debruijn/debruijn-code, parity/contract, semantic-FFI/continuation-invoke, all nine semantic-register test namespaces, and the old UCF safepoint harness. Node explicitly logs `Testing yin.vm.debruijn.stack-tail-completion-test` in both runs.

Node's broad compile emitted one pre-existing warning: namespace `yin.vm.linker.head.board` clashes with var `yin.vm.linker.head/board`. The final focused compile has zero warnings. The broad Node run began before the redundant-let cleanup; the final focused run validates the final source. Both versions have identical restore semantics.

The first JVM regression attempt stopped during namespace loading with `ClassNotFoundException: yang.python.antlr.gen.Python3Lexer`, before running tests. `bb gen:python-antlr` initially failed with `UnresolvedAddressException` under restricted networking (and a Babashka shutdown `sysctl` error). The authorized escalation `clojure -M:antlr-gen` succeeded, fetching the pinned Java base files, verifying the vendored grammar hashes and generating four Java files/classes. The final JVM regression command then passed.

Dart's exact blocking errors include:

```text
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 71: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.stamp.tmp.54614: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 78: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.realm: Operation not permitted
Execution error at cljd.compiler/mk-live-analyzer-info (compiler.cljc:214).
EOF while reading
Exception in thread "Thread-0" java.lang.RuntimeException: Operation not permitted (sysctl failed)
cljd compile failed, exit 1
```

No Dart execution or portability pass is claimed. The orchestrator must rerun that lane.

## Acceptance, numbered for this report

The design's §8.3 **6c-pre** paragraph is unnumbered. The subsequent numbered “Acceptance (6c)” list belongs to **6c proper**, outside this task. These numbers enumerate the actual 6c-pre obligations without taking on the layout/carrier work.

1. **PASS — mark tail-call parks.** `tail-park-is-marked` observes an actual blocked `stream/next!` tail host call and its `:return-result` marker. `non-tail-park-keeps-successor` asserts absence of the marker on the same non-tail call.
2. **PASS — deliver through K.** `return-pops-only-inner-frame`, `return-truncates-at-caller-base`, and `return-restores-caller-environment` check inner-frame removal, retained outer frame, exact residual stack plus nil value, return pc, caller lexical frames and module store context. `tail-in-operand-position-skips-layout` also exercises a real closure caller and scheduler wake.
3. **PASS — empty K halts with the result.** `empty-k-halts-with-result` wakes an actual root tail effect with nil, checks halt/value/stack/store context, and cannot accidentally treat nil as missing delivery.
4. **PASS — tail call in operand position skips following layout syntax.** `tail-in-operand-position-skips-layout` parks at pc 10 with a saved outer operand, then returns 7 directly with stack `[:outer 7]`; following push/100/enclosing addition do not execute.
5. **PASS — retained FFI and response decoding stay before delivery (§4.3).** `retained-ffi-reparks-before-return` checks reader re-park, blocked state, preserved marker/K and removal of the writer marker; `ffi-response-decodes-before-return` checks decoded nil delivery and parked-call cleanup. These are constructed native-entry seam tests, not a claim that a new tail-FFI opcode was added.
6. **PASS — non-tail parks and old stack tests unchanged.** Final JVM selected regression group and broad Node group pass; `non-tail-park-keeps-successor` returns the old successor result `:layout` with stack `[:result :layout]`. `git diff --quiet HEAD --` checks confirm unchanged existing stack tests, semantic-register files and register kernel.
7. **PASS on JVM/Node; Dart NOT RUN — verification.** Exact lane results above. Source lint/style/whitespace checks pass. Tri-host verification remains incomplete until the orchestrator runs Dart.

## Tests that can fail: temporary source mutations

Before each mutation pass, copied the implemented source byte for byte to `collab/6cpre-stack-implemented.backup.cljc` and verified it with `cmp`. Also saved the original HEAD source to `collab/6cpre-stack-head.backup.cljc`. After **each** mutation, restored from the implemented backup and checked `cmp`; then temporarily restored from the HEAD backup, checked `cmp` and `git diff --quiet HEAD -- src/cljc/yin/vm/debruijn/stack.cljc`, and restored the implemented backup with another `cmp`. This satisfies both baseline cleanliness and final implementation preservation; the implemented source necessarily differs from HEAD. The final source matches the implemented backup. No mutant survives.

Ran all eight mutations twice, once before and once after the redundant-let cleanup. The table and log files describe the second, final-source pass. Every invocation was `clojure -M:test -n yin.vm.debruijn.stack-tail-completion-test`, exit 1. A named error is reported as an error, not mislabeled an assertion failure.

| # | Temporary source mutation | Named test that fails | Suite outcome | Evidence |
|---|---|---|---|---|
| 1 | Change minted marker to `:write-result` | `tail-park-is-marked` | 9 tests / 25 assertions; 11 failures, 0 errors | `6cpre-mutation-1.log` |
| 2 | Empty-K return sets halted false | `empty-k-halts-with-result` | 9 / 25; 2 failures, 0 errors | `6cpre-mutation-2.log` |
| 3 | Keep K instead of popping its top frame | `return-pops-only-inner-frame` | 9 / 25; 1 failure, 0 errors | `6cpre-mutation-3.log` |
| 4 | Keep full operand stack instead of truncating to base | `return-truncates-at-caller-base` | 9 / 25; 3 failures, 0 errors | `6cpre-mutation-4.log` |
| 5 | Keep callee frames instead of caller frames | `return-restores-caller-environment` | 9 / 25; 1 failure, 0 errors | `6cpre-mutation-5.log` |
| 6 | Replace tail restore's return transition with ordinary stack push | `tail-in-operand-position-skips-layout` | 9 / 25; 9 failures, 0 errors | `6cpre-mutation-6.log` |
| 7 | Bypass retained-request re-park branch | `retained-ffi-reparks-before-return` | 9 / 22; 0 failures, 1 named error | `6cpre-mutation-7.log` |
| 8 | Skip response-envelope decoding | `ffi-response-decodes-before-return` | 9 / 25; 1 failure, 0 errors | `6cpre-mutation-8.log` |

## Ambiguities, findings and handling

- **Acceptance numbering:** the task refers to a numbered acceptance list for 6c-pre, but the design gives this seam a prose contract and reserves its numbered list for 6c proper. Took the smallest reading: implement/test the seam's prose plus its binding §4.3 ordering, and report these obligations numbered above. No layout, carrier, derivation or design edits.
- **HEAD restoration check:** implemented source cannot simultaneously match its final backup and have a quiet HEAD diff. Used distinct verified HEAD and implemented backups and checked both sequentially after each mutation, preserving the implementation at the end.
- **First fixture run:** 9 tests / 22 assertions, 0 failures, 1 error: the operand-position fixture lacked `:primitives vm/primitives`, so resolving `+` failed before its numeric assertions ran. Added the standard primitive registry, reran immediately: 9 / 25 green. No hand-derived number was found wrong; the pc 10 and residual-stack assertions were verified on the first run that reached them. The initial failure is stated plainly rather than claiming all numbers ran initially.
- **Lint:** first run reported one redundant-let warning introduced in restore. Flattened the binding; final lint has 0 warnings/errors. Mutation pass repeated against that final form.
- **Portability:** new code uses vectors/maps, no cross-namespace private vars, no reader conditionals, no protocol definitions, no large `for`, no float negation, and no multi-key assoc on nil. Nil result delivery is explicitly exercised. This source inspection is not a substitute for the blocked Dart run.
- **Compiler warning and parser prerequisite:** recorded with exact outcomes above; neither required a tracked-file change.

## Unresolved concerns and incomplete work

- Dart tests did not run because the sandbox denied Flutter cache writes; orchestrator rerun required.
- Non-author review/sign-off by claude-fable-5-1 remains outstanding.
- No requested implementation work is incomplete. No mutation survived. No full UCF/phase-6 acceptance, carrier/layout laws, or three-host success is claimed.

## Exact commands

G0 and each mutation invocation:

```sh
clojure -M:test -n yin.vm.debruijn.stack-tail-completion-test
```

JVM regression command (literal `-n` per namespace; no zsh word-splitting assumption):

```sh
clojure -M:test -e :slow -n yin.vm.attach-image-test -n yin.vm.cell-test -n yin.vm.continuation-invoke-test -n yin.vm.data-test -n yin.vm.debruijn.register-test -n yin.vm.debruijn.stack-effects-test -n yin.vm.debruijn.stack-parity-test -n yin.vm.debruijn.stack-tail-completion-test -n yin.vm.debruijn.stack-test -n yin.vm.debruijn-code-test -n yin.vm.debruijn-linearize-test -n yin.vm.debruijn-register-benchmark-test -n yin.vm.debruijn-resolve-test -n yin.vm.debruijn-test -n yin.vm.debruijn-vm-contract-test -n yin.vm.effect-forgery-test -n yin.vm.engine-gate-test -n yin.vm.ffi-test -n yin.vm.heap-reclamation-test -n yin.vm.host-typed-values-test -n yin.vm.integer-test -n yin.vm.linker.dht-test -n yin.vm.linker-manifest-test -n yin.vm.linker-require-test -n yin.vm.linker-test -n yin.vm.parity-test -n yin.vm.rule-r-test -n yin.vm.semantic-ffi-test -n yin.vm.semantic-register.analysis-test -n yin.vm.semantic-register.code-test -n yin.vm.semantic-register.effects-test -n yin.vm.semantic-register.linearize-test -n yin.vm.semantic-register.link-test -n yin.vm.semantic-register.load-paths-test -n yin.vm.semantic-register.parity-test -n yin.vm.semantic-register.vm-test -n yin.vm.semantic-register.walker-trace-test -n yin.vm.stream-poll-test -n yin.vm.ucf.authority.admission-test -n yin.vm.ucf.authority.completion-fold-test -n yin.vm.ucf.authority.completion-test -n yin.vm.ucf.authority.front-test -n yin.vm.ucf.authority.grant-test -n yin.vm.ucf.authority.inherited-test -n yin.vm.ucf.authority.input-test -n yin.vm.ucf.authority.reclaim-test -n yin.vm.ucf.authority-test -n yin.vm.ucf.checkpoint-test -n yin.vm.ucf.compose-rows-test -n yin.vm.ucf.compose-test -n yin.vm.ucf.crash-cut-test -n yin.vm.ucf.custody-test -n yin.vm.ucf.durability-test -n yin.vm.ucf.fencing-rows-test -n yin.vm.ucf.handoff-test -n yin.vm.ucf.handoff-v1-test -n yin.vm.ucf.handoff-v2-authority-test -n yin.vm.ucf.handoff-v2-census-test -n yin.vm.ucf.handoff-v2-custody-test -n yin.vm.ucf.handoff-v2-gates-test -n yin.vm.ucf.handoff-v2-holder-test -n yin.vm.ucf.handoff-v2-layout-test -n yin.vm.ucf.handoff-v2-test -n yin.vm.ucf.handoff-v2-validity-test -n yin.vm.ucf.handoff-v2-values-test -n yin.vm.ucf.handoff-v2-walker-test -n yin.vm.ucf.holder.driver-test -n yin.vm.ucf.holder.evidence-test -n yin.vm.ucf.holder.export-test -n yin.vm.ucf.holder.reader-test -n yin.vm.ucf.holder.writer-test -n yin.vm.ucf.ledger-fixture-test -n yin.vm.ucf.ledger-test -n yin.vm.ucf.lift-v1-test -n yin.vm.ucf.remote-test -n yin.vm.ucf.safepoint-harness-test -n yin.vm.ucf.scalar-round-trip-test -n yin.vm.ucf.stage-e-rows-test > collab/6cpre-jvm.log 2>&1
```

Node regression command:

```sh
clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^(yin\\.vm\\.attach\\-image\\-test|yin\\.vm\\.cell\\-test|yin\\.vm\\.continuation\\-invoke\\-test|yin\\.vm\\.data\\-test|yin\\.vm\\.debruijn\\.register\\-test|yin\\.vm\\.debruijn\\.stack\\-effects\\-test|yin\\.vm\\.debruijn\\.stack\\-parity\\-test|yin\\.vm\\.debruijn\\.stack\\-tail\\-completion\\-test|yin\\.vm\\.debruijn\\.stack\\-test|yin\\.vm\\.debruijn\\-code\\-test|yin\\.vm\\.debruijn\\-linearize\\-test|yin\\.vm\\.debruijn\\-register\\-benchmark\\-test|yin\\.vm\\.debruijn\\-resolve\\-test|yin\\.vm\\.debruijn\\-test|yin\\.vm\\.debruijn\\-vm\\-contract\\-test|yin\\.vm\\.effect\\-forgery\\-test|yin\\.vm\\.engine\\-gate\\-test|yin\\.vm\\.ffi\\-test|yin\\.vm\\.heap\\-reclamation\\-test|yin\\.vm\\.host\\-typed\\-values\\-test|yin\\.vm\\.integer\\-test|yin\\.vm\\.linker\\.dht\\-test|yin\\.vm\\.linker\\-manifest\\-test|yin\\.vm\\.linker\\-require\\-test|yin\\.vm\\.linker\\-test|yin\\.vm\\.parity\\-test|yin\\.vm\\.rule\\-r\\-test|yin\\.vm\\.semantic\\-ffi\\-test|yin\\.vm\\.semantic\\-register\\.analysis\\-test|yin\\.vm\\.semantic\\-register\\.code\\-test|yin\\.vm\\.semantic\\-register\\.effects\\-test|yin\\.vm\\.semantic\\-register\\.linearize\\-test|yin\\.vm\\.semantic\\-register\\.link\\-test|yin\\.vm\\.semantic\\-register\\.load\\-paths\\-test|yin\\.vm\\.semantic\\-register\\.parity\\-test|yin\\.vm\\.semantic\\-register\\.vm\\-test|yin\\.vm\\.semantic\\-register\\.walker\\-trace\\-test|yin\\.vm\\.stream\\-poll\\-test|yin\\.vm\\.ucf\\.authority\\.admission\\-test|yin\\.vm\\.ucf\\.authority\\.completion\\-fold\\-test|yin\\.vm\\.ucf\\.authority\\.completion\\-test|yin\\.vm\\.ucf\\.authority\\.front\\-test|yin\\.vm\\.ucf\\.authority\\.grant\\-test|yin\\.vm\\.ucf\\.authority\\.inherited\\-test|yin\\.vm\\.ucf\\.authority\\.input\\-test|yin\\.vm\\.ucf\\.authority\\.reclaim\\-test|yin\\.vm\\.ucf\\.authority\\-test|yin\\.vm\\.ucf\\.checkpoint\\-test|yin\\.vm\\.ucf\\.compose\\-rows\\-test|yin\\.vm\\.ucf\\.compose\\-test|yin\\.vm\\.ucf\\.crash\\-cut\\-test|yin\\.vm\\.ucf\\.custody\\-test|yin\\.vm\\.ucf\\.durability\\-test|yin\\.vm\\.ucf\\.fencing\\-rows\\-test|yin\\.vm\\.ucf\\.handoff\\-test|yin\\.vm\\.ucf\\.handoff\\-v1\\-test|yin\\.vm\\.ucf\\.handoff\\-v2\\-authority\\-test|yin\\.vm\\.ucf\\.handoff\\-v2\\-census\\-test|yin\\.vm\\.ucf\\.handoff\\-v2\\-custody\\-test|yin\\.vm\\.ucf\\.handoff\\-v2\\-gates\\-test|yin\\.vm\\.ucf\\.handoff\\-v2\\-holder\\-test|yin\\.vm\\.ucf\\.handoff\\-v2\\-layout\\-test|yin\\.vm\\.ucf\\.handoff\\-v2\\-test|yin\\.vm\\.ucf\\.handoff\\-v2\\-validity\\-test|yin\\.vm\\.ucf\\.handoff\\-v2\\-values\\-test|yin\\.vm\\.ucf\\.handoff\\-v2\\-walker\\-test|yin\\.vm\\.ucf\\.holder\\.driver\\-test|yin\\.vm\\.ucf\\.holder\\.evidence\\-test|yin\\.vm\\.ucf\\.holder\\.export\\-test|yin\\.vm\\.ucf\\.holder\\.reader\\-test|yin\\.vm\\.ucf\\.holder\\.writer\\-test|yin\\.vm\\.ucf\\.ledger\\-fixture\\-test|yin\\.vm\\.ucf\\.ledger\\-test|yin\\.vm\\.ucf\\.lift\\-v1\\-test|yin\\.vm\\.ucf\\.remote\\-test|yin\\.vm\\.ucf\\.safepoint\\-harness\\-test|yin\\.vm\\.ucf\\.scalar\\-round\\-trip\\-test|yin\\.vm\\.ucf\\.stage\\-e\\-rows\\-test)$"}' > collab/6cpre-node.log 2>&1
```

Final-source Node command:

```sh
clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin\\.vm\\.debruijn\\.stack-tail-completion-test$"}' > collab/6cpre-node-final.log 2>&1
```

Dart attempt (NOT RUN):

```sh
bb src/dev/cljd_agg.clj --only yin.vm.attach-image-test,yin.vm.cell-test,yin.vm.continuation-invoke-test,yin.vm.data-test,yin.vm.debruijn.register-test,yin.vm.debruijn.stack-effects-test,yin.vm.debruijn.stack-parity-test,yin.vm.debruijn.stack-tail-completion-test,yin.vm.debruijn.stack-test,yin.vm.debruijn-code-test,yin.vm.debruijn-linearize-test,yin.vm.debruijn-register-benchmark-test,yin.vm.debruijn-resolve-test,yin.vm.debruijn-test,yin.vm.debruijn-vm-contract-test,yin.vm.effect-forgery-test,yin.vm.engine-gate-test,yin.vm.ffi-test,yin.vm.heap-reclamation-test,yin.vm.host-typed-values-test,yin.vm.integer-test,yin.vm.linker.dht-test,yin.vm.linker-manifest-test,yin.vm.linker-require-test,yin.vm.linker-test,yin.vm.parity-test,yin.vm.rule-r-test,yin.vm.semantic-ffi-test,yin.vm.semantic-register.analysis-test,yin.vm.semantic-register.code-test,yin.vm.semantic-register.effects-test,yin.vm.semantic-register.linearize-test,yin.vm.semantic-register.link-test,yin.vm.semantic-register.load-paths-test,yin.vm.semantic-register.parity-test,yin.vm.semantic-register.vm-test,yin.vm.semantic-register.walker-trace-test,yin.vm.stream-poll-test,yin.vm.ucf.authority.admission-test,yin.vm.ucf.authority.completion-fold-test,yin.vm.ucf.authority.completion-test,yin.vm.ucf.authority.front-test,yin.vm.ucf.authority.grant-test,yin.vm.ucf.authority.inherited-test,yin.vm.ucf.authority.input-test,yin.vm.ucf.authority.reclaim-test,yin.vm.ucf.authority-test,yin.vm.ucf.checkpoint-test,yin.vm.ucf.compose-rows-test,yin.vm.ucf.compose-test,yin.vm.ucf.crash-cut-test,yin.vm.ucf.custody-test,yin.vm.ucf.durability-test,yin.vm.ucf.fencing-rows-test,yin.vm.ucf.handoff-test,yin.vm.ucf.handoff-v1-test,yin.vm.ucf.handoff-v2-authority-test,yin.vm.ucf.handoff-v2-census-test,yin.vm.ucf.handoff-v2-custody-test,yin.vm.ucf.handoff-v2-gates-test,yin.vm.ucf.handoff-v2-holder-test,yin.vm.ucf.handoff-v2-layout-test,yin.vm.ucf.handoff-v2-test,yin.vm.ucf.handoff-v2-validity-test,yin.vm.ucf.handoff-v2-values-test,yin.vm.ucf.handoff-v2-walker-test,yin.vm.ucf.holder.driver-test,yin.vm.ucf.holder.evidence-test,yin.vm.ucf.holder.export-test,yin.vm.ucf.holder.reader-test,yin.vm.ucf.holder.writer-test,yin.vm.ucf.ledger-fixture-test,yin.vm.ucf.ledger-test,yin.vm.ucf.lift-v1-test,yin.vm.ucf.remote-test,yin.vm.ucf.safepoint-harness-test,yin.vm.ucf.scalar-round-trip-test,yin.vm.ucf.stage-e-rows-test > collab/6cpre-dart.log 2>&1
```

Static checks:

```sh
clj -M:kondo --lint src/cljc/yin/vm/debruijn/stack.cljc test/yin/vm/debruijn/stack_tail_completion_test.cljc
cljstyle check src/cljc/yin/vm/debruijn/stack.cljc test/yin/vm/debruijn/stack_tail_completion_test.cljc
rg -n '[ \t]+$' src/cljc/yin/vm/debruijn/stack.cljc test/yin/vm/debruijn/stack_tail_completion_test.cljc
git diff --check
```
