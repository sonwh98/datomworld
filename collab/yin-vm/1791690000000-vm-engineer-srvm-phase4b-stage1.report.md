Completed-GMT: 2026-10-10 18:03:33 GMT
Completed-Local: 2026-10-11 01:03:33 Asia/Bangkok


# Phase 4b — Stage 1 only

Stage-1 implementation and JVM/Node verification are complete. Eight
Stage-1 mutants were caught. Dart tests are NOT RUN: compilation failed on
sandbox-denied Flutter cache writes. Stage 2 has not started. Independent
NON-codex review/sign-off remains outstanding; no commit or staging attempted.

## Changed files

New:

- `test/yin/vm/semantic_register/effects_test.cljc`: 20 tests for streams,
  exact act/tail entry and delivery shapes, park/resume, continuation
  invocation/ownership/arity/multiple re-entry, gensym, and GC roots.
- `collab/1791690000000-vm-engineer-srvm-phase4b-stage1.report.md` (this report).

Modified, explicitly authorized evaluator and slice-4a tests:

- `src/cljc/yin/vm/semantic_register.cljc`: five stream arms,
  host-fn stream/cell effects, continuation capture/invocation, park/resume,
  act/tail builders, shared return-window write, GC protocol methods;
  engine scheduler composition and removal of redundant pure-arm
  materializations. FFI/link remain deferred.
- `test/yin/vm/semantic_register/walker_trace_test.cljc`: stage-1 eligibility,
  production extraction for effects/capture, pre-state continuation and
  resume destinations, pinned continuation/resume/park traces.
- `test/yin/vm/semantic_register/parity_test.cljc`: stage-1 eligibility
  (all instructions except FFI), counts 26/30/5, caller-env restoration row.
- `test/yin/vm/semantic_register/vm_test.cljc`: replace obsolete 4a deferral
  expectations with the remaining stage-2 FFI/link deferrals.

Modified-existing exception suites: **NONE in Stage 1**. The four additive
backend registrations (`continuation_invoke_test`, `ffi_test`, `rule_r_test`,
`linker_require_test`) belong to Stage 2 and are untouched. The report
template's "exactly four" existing exception files applies to that stage,
not a mandate to modify them during Stage 1.

No phase-3 source/test/corpus, other existing source/test, or design document
was edited. No `link_test.cljc` was created, since link is Stage 2. All work
stayed in `/Users/sto/workspace/datomworld-srvm-p3`, branch `srvm-phase4b`.
Pre-existing untracked collab artifacts were left alone. No agent delegation,
background shell lane, unquoted namespace variable, staging, or commit.
Generated `out/test.js` was removed after Node verification.

## Implementation evidence and templates

Templates in `semantic.cljc`, as specified by the evaluator design:
`:184-201` entry builders; `:204-221` effect boundary; `:224-263` operator
arms; `:333-340` return; `:363-397` capture/park/resume; `:423-464` stream
instructions; `:1052-1058` GC roots/children. The restored activation reads
window/env/K from returned engine state. Effect dispatch, park and resume
materialize locals; pure define/gensym/store-put and pure call paths do not.

`return-to [frame value]` is the sole saved-window write for all three pops:
`:return`, immediate tail-host completion, and deferred `register-restore`
`:return`. It retains the frame window and adds exactly its rd. A sibling
tail-host test with a non-empty saved window additionally gates that path.
Tail closure calls now build a return frame only when non-tail. Image lookup
still occurs per instruction; no performance claim is made.

`IModuleKernel` installs **only GC methods** in Stage 1. Its seven link
methods remain unimplemented until Stage 2; host `require` is explicitly
refused before dispatch. The entry builders have stream keys only;
`:module/require` builder is also Stage 2. Restore remains step 3 only;
steps 1-2 are FFI-specific and deferred to Stage 2 by the explicit stage split.

## 26-item acceptance list

PASS means Stage-1 evidence on JVM and Node, not a Dart pass. DEFERRED items
are intentionally incomplete under the owner's two-stage instruction.

1. **PASS** — `non-parking-stream-ops`: inline make/cursor/close, sealed
   stream/cursor refs, nil close result and halting.
2. **PASS** — `blocked-read-entry-shape`: exact nine-field act entry,
   pc+1, env, saved window, K, complete delivery, reason/cursor/stream;
   EDN round-trip, no parked bookkeeping, nil active control/K.
3. **PASS** — `saved-window-membership`: exact analysis-selected window
   `{1 vector, 2 nil}`, nil-valued key present, destination absent.
4. **PASS** — `wake-delivers-to-rd`: append, engine wake/restore, exact
   post-delivery window, pc, empty ready queue, final `[nil 7 2]`, no waits.
5. **PASS** — `blocked-put`: actual full transport, retained :datom
   :second, empty saved window, exact delivered destination and final value.
6. **PASS** — `terminal-wake-raises`: closed retained append throws
   "Stream append failed" on wake and immediate put. Engine performs the
   terminal check before the restore seam; no shared engine changes.
7. **PASS** — `tail-effect-returns-through-k`: exact tail-key set, return
   delivery and no pc/env/window; one-frame and empty-K cases. Wake restores
   exactly the caller window or halts immediately, leaving no store-of.
8. **PASS** — `tail-primitive-pops-frame`: non-empty saved window,
   exact restoration with value 8, final `[8 2]`; shared return-to helper.
9. **PASS** — `park-resume`: exact pure-data simple parked record with
   id :parked-0, pc 1, map window and complete rd delivery; EDN round-trip;
   richer saved-window park resumed from a second segment gives `[nil 7 2]`,
   parked table empty; missing id message contains "not found".
10. **PASS** — `resume-return-mode`: manually parked return delivery with
    frame resumes through it to `[:saved 9]`; empty-K return resumes to
    a halted value 9, no loop re-entry into nil control.
11. **PASS** — `reified-shape`: host continuation with exact payload,
    pc 1, saved `{}`, K [], complete rd 0 delivery; payload EDN round-trip.
12. **PASS for Stage-1 programs** — all five original continuation
    programs respelled in effects-test: escape=9, tail invoke=8, repeated
    re-entry=[30 3], store persistence=[1 :written], arity 0/2 gives
    `[:thrown "Continuation expects exactly one argument"]`. Global fifth
    backend registration in the existing suite remains Stage 2.
13. **PASS** — `foreign-continuation-refused`: exact foreign-value / kind
    continuation refusal for another owner's continuation.
14. **DEFERRED to Stage 2** — FFI sent/response bookkeeping and decoding.
15. **DEFERRED to Stage 2** — retained FFI request phase, restore step 1.
16. **DEFERRED to Stage 2** — lowered retained response route.
17. **DEFERRED to Stage 2** — FFI error envelope/restore step 2.
18. **PASS** — `store-and-gensym-test`: id-0, stream-1, g-2 and counter 3;
    phase-4a store tests also remain green.
19. **DEFERRED to Stage 2** — link install and fifth backend registration.
20. **DEFERRED to Stage 2** — tail require delivery.
21. **DEFERRED to Stage 2** — linked module-store integration rows.
22. **DEFERRED to Stage 2** — install-path refusals. Existing public-loader
    stamp/refusal tests continue passing but do not prove install refusal.
23. **DEFERRED to Stage 2** — closure marker lift/lower checks.
24. **PASS** — `window-is-a-gc-root`: only W holds keep-cell; collect retains
    :keep and discards :junk; clearing W leaves no cell survivors.
25. **PARTIAL, full item deferred to Stage 2** — stage-1 eligibility covers
    B0 26, register corpus 30 (only two FFI rows excluded), Rule R 5;
    61 corpus traces plus six inline pinned rows and a resume delivery test.
    Park-then-halt pins `[[:park :parked-0]]`. All five continuation programs
    are run, but full trace corpus 3/full count 32 awaits Stage 2.
26. **PARTIAL, full item deferred to Stage 2/orchestrator** — changed-path
    lint/style/whitespace and explicit JVM/Node checkpoint pass. Dart blocked.
    No link namespace yet; no full subsystem, G2, G3 or bb test run.

Additional stream coverage (`stream-outcomes-and-edn-wake`) respells the
semantic-engine rows: unchanged blocked polling, an EDN-revived wait entry
wakes, stored cursor advances, end=nil, gap value, inline put reaches stream,
and an unknown reference is refused. Terminal closed-put is covered above.

## §5.3 carried requirements

1. **Stage 2 deferred:** restore retained-request step 1 and call-id/decode
   step 2. Stage 1 remains step 3; no claim they landed.
2. **DONE:** continuation and resume extract arms read PRE-state payloads;
   each has a whole pinned trace; park is recognized by executed instruction
   or node, not result shape. Tagged extraction only, fuel 100000.
3. **DONE:** engine/active-continuation? predicate and engine/scheduler-round.
4. **DONE:** removed the four redundant pure-arm materializations, retaining
   effect, explicit park and resume boundaries. Design not amended.
5. **OBSERVED:** no attempt to distinguish analysis/live from analysis/saved
   at a call frame or capture. No equivalent-mutant gate added.
6. **PRESERVED:** main-tail-primitive and parameter-shadowing rows stay green.
7. **DONE:** shared return-to helper across all three pops; extra sibling
   tail primitive exact-window test with a non-empty saved window. Mutated
   shared write and caught the sibling test experimentally.
8. **DONE:** caller-env-after-return extra row `((fn [x] (list ((fn [x] 2)
   9) x)) 1)` -> `(2 1)`, raw/normalized values on walker/register. Test
   composition explicitly binds list; it is not a published default primitive.
9. **RECORDED:** original nil fixture cannot expose wrong return source;
   optional primary x=7 variant not added. Sibling tail-host test uses x=7
   but is not claimed to prove the primary return source. Old [5 7 8 9 6]
   check is static golden tuple order, not runtime write order. Tail closure
   discarded-frame work removed; per-instruction image lookup retained.

## Mutation table — experimental failures, byte-for-byte restoration

Each mutation changed only `semantic_register.cljc`, independently. Every
run used literal `clojure -M:test -n yin.vm.semantic-register.effects-test`.
The test process exit was 1 in all eight cases. The enclosing Python process
exited 0 after restoring in finally; that is not a test-pass claim. Logs and
full assertion lists are `/private/tmp/srvm-stage1-mutant-<name>.log` and
`/private/tmp/srvm-stage1-mutants.json`.

| Mutation | Named failure and assertion | Exact test summary |
|---|---|---|
| wrong-rd: rd delivery writes key 999 | `wake-delivers-to-rd`: `(= expected (:window restored))` | Ran 20 tests containing 87 assertions. 11 failures, 0 errors. Exit 1. |
| save-too-much: act payload saves all W | `saved-window-membership`: `(= expected (:window entry))` | Ran 20 tests containing 90 assertions. 5 failures, 0 errors. Exit 1. |
| save-too-little: act payload saves {} | `saved-window-membership`: `(= expected (:window entry))` | Ran 20 tests containing 78 assertions. 7 failures, 2 errors. Exit 1. |
| tail-restores-window: tail gains pc/env/window and rd delivery | `tail-effect-returns-through-k`: `(= #{:cursor-ref :deliver :site :k :stream-id :reason :segment} (set (keys entry)))` | Ran 20 tests containing 90 assertions. 7 failures, 0 errors. Exit 1. |
| deliver-extra-register: rd delivery writes an extra key 999 | `wake-delivers-to-rd`: `(= expected (:window restored))` | Ran 20 tests containing 90 assertions. 2 failures, 0 errors. Exit 1. |
| non-abortive-invoke: invoke continues caller instead of restoring capture | `escape-discards-pending-frames-test`: `(= expected (vm/value done))` | Ran 20 tests containing 90 assertions. 3 failures, 0 errors. Exit 1. |
| window-not-root: gc-roots omits W | `window-is-a-gc-root`: `(= #{:keep} (set (map :value (vals (:heap collected)))))` | Ran 20 tests containing 90 assertions. 1 failures, 0 errors. Exit 1. |
| tail-pop-extra-key: shared return-to writes extra result key 0 | `tail-primitive-pops-frame`: `(= (assoc (:window frame) (:rd frame) 8) (:window after))` | Ran 20 tests containing 90 assertions. 2 failures, 0 errors. Exit 1. |


No Stage-1 mutant survived. Requested retained-request-delivers-to-guest and
link-phase-loses-delivery-record mutations are **DEFERRED, NOT APPLIED**:
there is no Stage-1 implementation of those phases to mutate. The explicit
Stage-1-only instruction takes precedence over the full-slice mutation list.
The table includes both oversized/undersized saves and the additional shared
return-pop mutation, without claiming they substitute for Stage-2 mutations.

Each finally block wrote the original source bytes back and asserted equality.
Final SHA256 agrees with the pre-mutation baseline:

```text
55667c45017ce1fe732d84c9f2d231af4589dba8f6d436c80a08af972d2cc075
```

`git diff -- src/` is intentionally non-empty for the authorized real Stage-1
implementation. Restoration means byte equality with the implemented
pre-mutation baseline, not reverting Stage-1 work to HEAD.

## Verification commands and exact outcomes

### JVM — PASS

```sh
clojure -M:test -n yin.vm.semantic-register.effects-test -n yin.vm.semantic-register.vm-test -n yin.vm.semantic-register.walker-trace-test -n yin.vm.semantic-register.parity-test -n yin.vm.semantic-register.code-test -n yin.vm.semantic-register.linearize-test -n yin.vm.semantic-register.analysis-test -n yin.vm.semantic-register.load-paths-test
```

Exit 0: **79 tests / 1703 assertions, 0 failures / 0 errors**. All eight
namespace Testing lines present. Stage-1/4a: 46 / 570; phase 3: 33 / 1133.
Independent phase-3 run also performed:

```sh
clojure -M:test -n yin.vm.semantic-register.code-test -n yin.vm.semantic-register.linearize-test -n yin.vm.semantic-register.analysis-test -n yin.vm.semantic-register.load-paths-test
```

Exit 0: **33 / 1133, 0 failures / 0 errors**.

Unchanged regression suites (including the four whose extension is Stage 2):

```sh
clojure -M:test -n yin.vm.continuation-invoke-test -n yin.vm.ffi-test -n yin.vm.rule-r-test -n yin.vm.linker-require-test -n yin.vm.semantic-test -n yin.vm.semantic-engine-test -n yin.vm.semantic-ffi-test -n yin.vm.parity-test
```

Exit 0: **122 tests / 1226 assertions, 0 failures / 0 errors**; all eight
namespace lines confirmed. Log: `/private/tmp/srvm-stage1-regression.log`.
This is existing backend coverage, not fifth-backend FFI/link coverage.

G0 history, honestly including failures:

- New effects tests first ran against unmodified 4a: 19 tests / 22
  assertions, 6 failures / 12 errors (red).
- First effects run after implementation: 19 / 47, 0 failures / 7 errors;
  test fixtures lacked vector bindings, plus a hand-constructed return frame
  used a nil lookup for vector. No expected register/id number was adjusted.
- Fixed fixture compositions: 19 / 81, 0 failures / 1 error. Plain park EDN
  fixture had inadvertently inherited host vector in env. Switched that
  round-trip fixture to a fresh empty-env VM, rather than serializing host fn.
- New trace/parity/vm-test run: **26 / 480, 0 failures / 0 errors**.
- Final standalone effects-test: **20 / 90, 0 failures / 0 errors**.

### Node — PASS

```sh
clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin\\.vm\\.semantic-register\\.(code-test|linearize-test|analysis-test|load-paths-test|vm-test|walker-trace-test|parity-test|effects-test)$" :output-to "out/test.js" :autorun false}'
node out/test.js
```

Compile exit 0: **154 files / 7 compiled / 0 warnings**. Node exit 0:
**84 tests / 1763 assertions, 0 failures / 0 errors**. All eight requested
Testing lines appeared, including `yin.vm.semantic-register.effects-test`.
Required B0 data namespace `yin.vm.parity-test` also runs its 5 tests / 60
assertions, accounting for the difference from JVM. No link-test exists yet.

### Dart — NOT RUN

Exact attempted command:

```sh
bb src/dev/cljd_agg.clj --only yin.vm.semantic-register.code-test,yin.vm.semantic-register.linearize-test,yin.vm.semantic-register.analysis-test,yin.vm.semantic-register.load-paths-test,yin.vm.semantic-register.vm-test,yin.vm.semantic-register.walker-trace-test,yin.vm.semantic-register.parity-test,yin.vm.semantic-register.effects-test
```

Exit 1 during compilation, before tests. Exact substantive errors:

```text
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 71: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.stamp.tmp.6726: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 78: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.realm: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 71: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.stamp.tmp.6749: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 78: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.realm: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 71: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.stamp.tmp.6772: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 78: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.realm: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 71: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.stamp.tmp.6796: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 78: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.realm: Operation not permitted
Execution error at cljd.compiler/mk-live-analyzer-info (compiler.cljc:214).
EOF while reading

Full report at:
/var/folders/n0/xl3_1xkn0yd5trpq843l81840000gn/T/clojure-12213458023828564103.edn
Exception in thread "Thread-0" java.lang.RuntimeException: Operation not permitted (sysctl failed)
cljd compile failed, exit 1
```

Stopped the lane, no escalation or inferred pass. No Dart test summary/count.
Dart portability of the Stage-1 implementation, including partial GC protocol
extension, remains for the orchestrator's independently accessible lane.

### Checks — PASS

```sh
clj -M:kondo --lint src/cljc/yin/vm/semantic_register.cljc test/yin/vm/semantic_register/effects_test.cljc test/yin/vm/semantic_register/vm_test.cljc test/yin/vm/semantic_register/walker_trace_test.cljc test/yin/vm/semantic_register/parity_test.cljc
cljstyle check src/cljc/yin/vm/semantic_register.cljc test/yin/vm/semantic_register/effects_test.cljc test/yin/vm/semantic_register/vm_test.cljc test/yin/vm/semantic_register/walker_trace_test.cljc test/yin/vm/semantic_register/parity_test.cljc
rg -n '[\t ]+$' src/cljc/yin/vm/semantic_register.cljc test/yin/vm/semantic_register/effects_test.cljc test/yin/vm/semantic_register/vm_test.cljc test/yin/vm/semantic_register/walker_trace_test.cljc test/yin/vm/semantic_register/parity_test.cljc
```

Final kondo exit 0, **0 errors / 0 warnings**; cljstyle exit 0, no output;
whitespace no matches, rg exit 1 (no-match). Initial kondo reported two unused
requires in the new effects test; removed them. Cljstyle fix only touched
explicitly authorized changed paths. No full bb test, G2 or G3 run.

## First-run hand-derived values and traces

No design-marked number or trace was silently adjusted. Existing 4a register
numbers and six-event lambda trace remain green. New register/id pins:

- Live read fixture saves register 1 vector and register 2 nil. It was not
  reached until vector was explicitly bound; on its first reachable run the
  exact map, present nil key and excluded rd passed. The initial nil primitive
  lookup was a fixture error, not a derived register-number change.
- Simple capture: segment's pc 0 writes rd 0, payload pc 1, window {}, K [].
  These exact shape assertions passed on the first implementation run.
- Pure park: pc 1, rd 0, id :parked-0, K []; exact map/EDN checks pass.
- Gensym/stream/gensym: id-0, stream-1, g-2, counter 3. On the first run that
  reached this program (after fixing the vector binding), all four passed.
- Main tail primitive pinned trace remains host-fn, 1, 2, 3, halt 3.
- Park-then-halt actual/pinned trace `[[:park :parked-0]]` passed on the
  first trace run; no parked-shaped-value classifier is used.
- Resume delivery actual/pinned trace `[[:value 9] [:value 9] [:halt 9]]`
  passed on the first trace run on both machines. The first value is its
  operand; the second is delivery to the consumed entry's own destination.
- Continuation delivery whole trace passed on the first trace run on both:

```clojure
[[:value {:type :closure :params [r]}] [:value :continuation]
 [:value :host-fn] [:value :continuation] [:value 7] [:value false]
 [:value :continuation] [:value 7] [:value 7]
 [:value :host-fn] [:value 7] [:value 7] [:value true] [:value 7] [:halt 7]]
```

The ninth event is the abortive delivery into the captured destination. The
next equality test takes the true branch after re-entry. Closure routing,
return/halt relocation and branch routing add no value event.

## Ambiguities, findings and incomplete work

1. Full-slice wording asks for restore steps 1-2, link protocol and four
   registrations, but the explicit Stage-1-only definition excludes all FFI
   and link work. Took the narrower, newer stage definition: defer them,
   including their mutation tests. No design amended.
2. The report template's four modified-existing exception suites is likewise
   Stage-2 work; modifying them now would violate the explicit stage split.
3. Acceptance 24 requires GC roots before link protocol work. Implemented the
   two GC methods of the existing protocol, and refused require explicitly;
   did not supply a linking implementation or artificial install fixture.
4. Fixed test composition errors (vector/list are not default published
   primitives; stream host calls use registered stream/next!); the codebase's
   primitive/module registries were not changed. EDN fixtures avoid host fn
   in their payloads. No frozen shape was changed.
5. Stage-1 mutations all failed named assertions. No surviving Stage-1 mutant
   found; no claim about deferred-phase mutations or repeated same-key/same-
   value assoc operations indistinguishable in an immutable state.

Incomplete: Stage-2 acceptance 14-17, 19-23, full 25-26, restore 1-2,
module/require builder, seven link protocol methods, FFI arm, link_test,
four backend registrations and two phase-specific mutation probes; Dart
verification; independent NON-codex review/sign-off. G2/G3 and commits remain
with the orchestrator. No Stage-2 work started. Host clock supplies the report
header; no timestamp inferred from the task's Created text.
