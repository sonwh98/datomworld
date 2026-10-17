Completed-GMT: 2026-10-10 13:28:42 GMT
Completed-Local: 2026-10-10 20:28:42 Asia/Bangkok


# Phase 4a core evaluator report

Implementation is complete for slice 4a. JVM and Node gates pass. The Dart
lane is NOT RUN: its compile attempt failed on sandbox-denied Flutter cache
writes. Consequently the three-host acceptance gate is not complete.
No sign-off is claimed; the assigned independent NON-codex reviewer remains
responsible for review and sign-off.

## Changed files (all new)

- `src/cljc/yin/vm/semantic_register.cljc`: RegisterVM, image loaders,
  materialization, core hot loop, return delivery, scheduler and IVM/IVMState.
- `test/yin/vm/semantic_register/vm_test.cljc`: 18 core evaluator tests.
- `test/yin/vm/semantic_register/parity_test.cljc`: 3 parity tests; the sole
  definition of `slice-4a-eligible?`, plus shared normalization and runners.
- `test/yin/vm/semantic_register/walker_trace_test.cljc`: 3 trace tests;
  explicit destinations and tagged extraction, fuel 100000, pinned oracle rows.
- This report, `collab/1791550000000-vm-engineer-srvm-phase4a.report.md`.

Work remained in `/Users/sto/workspace/datomworld-srvm-p3`, branch
`srvm-phase4a`. `git diff --name-only` was empty: no tracked existing source,
existing test, or design document changed. Existing untracked collab files
were left alone. No staging, commits, delegated agents, or review calls.
The generated `out/test.js` was removed after the Node run.

Templates followed: `semantic.cljc:51-80` record, `:90-107`
materialization, `:224-263` operator classification and calls, `:284-292`
loop locals, `:299-361` core transitions, `:692-724` image/alias conflicts,
`:793-846` load/attach, `:853-895` protocol/reset/eval, `:898-951`
construction. Return frames replace `:237-238` stack-base with saved window
and destination; return replaces `:333-340`. Restore uses the return-delivery
reading of evaluator §2.2, with call-id handling explicitly deferred.

## Verification and exact outcomes

All commands ran in the worktree in foreground. No `bb test`, full subsystem
lane, G2, or background test lane was intentionally run. The owner's explicit
seven-namespace checkpoint overrides the broader generic gate wording.

### JVM

Initial G0 command:

```sh
clojure -M:test -n yin.vm.semantic-register.vm-test -n yin.vm.semantic-register.walker-trace-test -n yin.vm.semantic-register.parity-test
```

First attempt exited 1 at an unmatched closing delimiter in the new vm test,
before tests ran. A bracket-stack script identified the extra delimiter; it
was removed. The FIRST completed test run passed **24 tests / 402 assertions,
0 failures / 0 errors**. This is the run on which all required hand-derived
numbers were first evaluated; none of their expectations changed.

After formatting, the initial combined checkpoint passed **57 tests / 1535
assertions, 0 failures / 0 errors**. Tests were then strengthened to observe K
at every countdown instruction, check empty-K closure `:return` halt, and
assert the conditional register's taken-arm value directly. Final checkpoint:

```sh
clojure -M:test -n yin.vm.semantic-register.vm-test -n yin.vm.semantic-register.walker-trace-test -n yin.vm.semantic-register.parity-test -n yin.vm.semantic-register.code-test -n yin.vm.semantic-register.linearize-test -n yin.vm.semantic-register.analysis-test -n yin.vm.semantic-register.load-paths-test
```

**PASS: 57 tests / 1547 assertions, 0 failures / 0 errors; exit 0.** All seven
`Testing` namespace lines appeared. New tests account for **24 / 414**.

Independent phase-3 preservation check:

```sh
clojure -M:test -n yin.vm.semantic-register.code-test -n yin.vm.semantic-register.linearize-test -n yin.vm.semantic-register.analysis-test -n yin.vm.semantic-register.load-paths-test
```

**PASS: 33 tests / 1133 assertions, 0 failures / 0 errors; exit 0.**

### Node

Final compile (run again after the strengthened tests):

```sh
clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin\\.vm\\.semantic-register\\.(code-test|linearize-test|analysis-test|load-paths-test|vm-test|walker-trace-test|parity-test)$" :output-to "out/test.js" :autorun false}'
node out/test.js
```

**PASS: compile exit 0, 144 files / 3 compiled / 0 warnings. Node exit 0,
62 tests / 1607 assertions, 0 failures / 0 errors.** The log names every
requested namespace: analysis-test, code-test, linearize-test,
load-paths-test, parity-test, vm-test, walker-trace-test, all under
`yin.vm.semantic-register`. It additionally names `yin.vm.parity-test`,
required for the B0 corpus: shadow includes that dependency's 5 tests / 60
assertions. Thus the requested suite is 57 / 1547, with the unchanged
phase-3 33 / 1133 included.

Earlier mistaken compile command:

```sh
clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:builds {:test {:ns-regexp "^yin\\.vm\\.semantic-register\\.(code-test|linearize-test|analysis-test|load-paths-test|vm-test|walker-trace-test|parity-test)$" :output-to "out/test.js" :autorun false}}}'
```

The CLI merges directly into the selected build; this nested shape did not
select the intended namespaces and started unrelated tests. Interrupted with
Ctrl-C, exit 130; no suite result and no pass claimed. The corrected direct
build config above was then used. This was a command mistake, not a design
change.

### Dart — NOT RUN

Exact attempted command:

```sh
bb src/dev/cljd_agg.clj --only yin.vm.semantic-register.code-test,yin.vm.semantic-register.linearize-test,yin.vm.semantic-register.analysis-test,yin.vm.semantic-register.load-paths-test,yin.vm.semantic-register.vm-test,yin.vm.semantic-register.walker-trace-test,yin.vm.semantic-register.parity-test
```

Exit 1 during compilation, before a test result. Exact substantive errors:

```text
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 71: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.stamp.tmp.44381: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 78: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.realm: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 71: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.stamp.tmp.44404: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 78: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.realm: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 71: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.stamp.tmp.44427: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 78: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.realm: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 71: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.stamp.tmp.44450: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 78: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.realm: Operation not permitted
Execution error at cljd.compiler/mk-live-analyzer-info (compiler.cljc:214).
EOF while reading

Full report at:
/var/folders/n0/xl3_1xkn0yd5trpq843l81840000gn/T/clojure-7091304004663163218.edn
Exception in thread "Thread-0" java.lang.RuntimeException: Operation not permitted (sysctl failed)
cljd compile failed, exit 1
```

Stopped this lane; no escalation, retry outside the sandbox, inferred pass,
or Dart test/assertion count. Dart **0 executed tests; no test summary**.

### Checks

```sh
clj -M:kondo --lint src/cljc/yin/vm/semantic_register.cljc test/yin/vm/semantic_register
cljstyle check src/cljc/yin/vm/semantic_register.cljc test/yin/vm/semantic_register/vm_test.cljc test/yin/vm/semantic_register/walker_trace_test.cljc test/yin/vm/semantic_register/parity_test.cljc
rg -n '[\t ]+$' src/cljc/yin/vm/semantic_register.cljc test/yin/vm/semantic_register/vm_test.cljc test/yin/vm/semantic_register/walker_trace_test.cljc test/yin/vm/semantic_register/parity_test.cljc
```

Final kondo **PASS: exit 0, 0 errors / 0 warnings**. Final cljstyle **PASS:
exit 0, no output**. Whitespace scan **PASS: no matches** (rg no-match exit 1;
its output was empty). `cljstyle fix` was used only on the four new files.
The first lint attempt saw the same extra closing delimiter noted above;
subsequent lint passes were clean.

## Acceptance list (§5.1), item by item

PASS below means verified on JVM and Node. Dart coverage is absent throughout;
item 21 explicitly records the incomplete cross-host gate.

1. **PASS** — `vm-test/stamp-before-rules`: missing and v3 stamps against an
   invalid empty vector yield `:contract-missing` / `:contract-mismatch` first.
2. **PASS** — `loader-refusals-are-the-validators`: every public
   `code-test/refusals` row compares exact defect `{:rule :pc}`. Includes
   all four reserved-name loader refusals, as required by the Rule R pattern.
3. **PASS** — `image-shape`: exact six image keys, vector, golden address,
   analysis equality, alias, control pc 0, empty map window, nil K, length 7;
   record carries no `:stack`.
4. **PASS** — `segment-ids`: second id below loaded floor, identical address
   reuses prior id even with requested id 999, code stable, live-id conflict
   carries the required message.
5. **PASS** — `one-step-one-write`: worked-example pc 0 executes once,
   control pc 1 and window exactly `{1 f}`.
6. **PASS** — `return-frame-shape`: worked-example call at pc 3 saves
   the exact frame with resume pc 4, env, liveness-restricted `{1 f}`, rd 2;
   callee window `{}`.
7. **PASS** — `tail-call-grows-no-frame`: tail closure call preserves the
   existing one-frame K and clears W. `tail-countdown`: 100000 countdown
   iterations, observe K after every instruction, never exceeds 1, halts
   with "done", nil K.
8. **PASS** — `return-writes-rd-once`: dissociating frame rd from restored
   W gives exactly saved W; delivered nil key is present; K becomes nil.
9. **PASS** — `halt-shape`: nil/false halt results, nil control/K, empty W,
   halted flag and protocol, store-of stripped. Tail-entered closure returns
   with empty K and value 8; deferred empty-K restore also strips base env.
10. **PASS** — `tail-primitive-returns-through-k`: tail application of
    `(fn [n] (+ n 1))` to 1 yields 2, no observed body window contains key 0.
11. **PASS** — `define-routes-to-the-module-store`: closure captured with
    store-of foo defines answer=42 only in module-stores foo, not task store.
12. **PASS** — `branch-arms-share-rd`: nil/false branch tests take alternate,
    true takes consequent; if, nested-if-same-rd, if-operand assert final
    results and conditional destination's taken value. if-in-tail verifies
    keys 1..9 present before tail call and arm result/intermediate writes
    in order `[5 7 8 9 6]`.
13. **PASS** — `application-refusals`: nonfunction yields :not-applicable;
    other-owner closure yields :foreign-value, :kind :closure.
14. **PASS** — `parity-test/b0-values`: eligible count 25; each expected and
    walker/register value goes through the same normalization, both halt.
15. **PASS** — `corpus-outcomes`: eligible count 21; values, halting,
    blocking flags, normalized stores, or error messages compare equally.
16. **PASS** — `rule-r-definitions`: count 5; eligibility, pinned value and
    selected store slice pass on both machines, including closure by name,
    redefine, and reserved symbol as literal data.
17. **PASS** — `walker-trace-test/traces-align`: 51 eligible corpus rows
    (25+21+5); whole traces equal, first mismatch diagnostic available,
    fuel bound 100000, equal exhaustion still fails. Three additional 4a
    trace-only rows pin complete traces independently on both machines.
18. **PASS** — `return-is-administrative`: exact six value events on both
    machines and final halt 11, with no event for closure entry or return.
19. **PASS** — `ready-for-ingress`: true fresh and halted, K nil.
20. **PASS** — final kondo command above reports no errors or warnings.
21. **FAIL / INCOMPLETE** — owner's scoped checkpoint passes on JVM and
    Node, all required Node namespace lines present. Dart compile blocked
    before tests by sandbox cache permissions; three-host pass unavailable.
    Full `bb test:sub yin.vm` intentionally not run under this task's narrower
    explicit verification instruction.

Additional coverage: attach preserves execution and scheduler fields,
identical attach, AST datom loader and stamp, reset, eval AST refusal,
rd and return restore arms (including nil-valued saved keys), missing
delivery refusal, deferred instructions, effect-valued host call, and
continuation call all with slice refusal data as appropriate.

## Hand-derived numbers — first completed run

All confirmed on the first completed JVM run (24 / 402), then again in
final JVM and Node checkpoints. No hand-derived expectation was adjusted.

- Item 5: worked-example's first tuple is `[:var 1 f]`; pc becomes 1,
  W exactly `{1 f}`.
- Item 6: worked-example pc 3 is `[:call 2 3 [4] false]`; resume pc 4,
  saved window `{1 f}`, destination 2. Exact saved frame asserted.
- Item 12: if/ nested-if result id 0, if-operand conditional id 2;
  if-in-tail alternate arm writes 5,7,8,9,6 before its tail call,
  its runtime window contains all keys 1..9 and no body result key 0.
- Item 18: six values are closure `[x]`, 10, host `+`, x=10, 1, sum=11;
  final `[:halt 11]`. Exact event vector pinned on both machines.

## Ambiguities/findings and readings taken (design not amended)

1. §5.1 says restore “steps 2-3”, then immediately says “step 1 and the
   :call-id handling land in 4b”. Step 2 of §2.2 is precisely call-id
   removal/decoding. The explicit 4a exclusion governs: implement plain
   delivery and return delivery only; no call-id handling or retained phase.
   No 4a gate supplies such an entry. This also matches the owner's brief.
2. Acceptance item 21/generic Gate Ladder says full subsystem; the owner's
   explicit verification says the three new namespaces plus phase 3 on all
   lanes, no full bb test. Took the explicit scoped instruction; seven
   namespaces run, no broader successful subsystem claim.
3. `:park-then-halt` is expressly 4b-only. The 4a trace test contains the
   three eligible sentinel/park-shaped-value rows and classifies park by
   executed node/instruction; no 4a park trace run is claimed.
4. Actual host clock returned a completion time earlier than the task's
   Created/Assigned timestamps. Report header uses the observed host UTC
   and Bangkok time, not a fabricated later timestamp.

No design defect requiring existing source/test edits was found. No design
was amended. The four new files implement only the 4a scope; module protocol,
effects, park/resume and continuation invocation are intentionally 4b.

## Unresolved concerns and incomplete work

- Dart compile/test validation remains incomplete; orchestrator must rerun
  the exact lane with Flutter cache access. No Dart portability pass claimed.
- Acceptance 21 cannot be marked complete until that lane passes.
- Independent NON-codex review and sign-off remain outstanding; no codex
  self-review/sign-off or commit attempted.
- Workflow deviation: evaluator scaffolding was authored before the new
  tests, rather than following build-n-test's strict test-first order.
  Tests and frozen expectations were present before the first completed run.
- No other implementation work is incomplete within 4a.
