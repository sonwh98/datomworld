Created-GMT: 2026-10-16 18:53:56 GMT
Created-Local: 2026-10-17 01:53:56 +07
Coding-Agent: codex
Session-ID: 01a145cc-0712-76d1-bd92-811f949312c6
# Task: slice 6d-b lane-fix — Node clock cuts and Dart void binding
Role: VM Runtime
Implementers:
- Model: gpt-6.1-sol (dispatch assignment) | Assigned: 2026-10-17 02:20 +07 | Status: completed; appended and pushed | Rationale: same author fixes landing-lane test portability on top of pushed 82d48e1e

Base: `82d48e1e`. Only two test files are changed. No production change, assertion weakening, added Node gate, protected-pin change, or src/yang/test/yang edit. No collab file will be committed.

## Node root cause — candidate (a), test replacement arity shapes

Diagnosed before editing either test file. This is **test portability**, specifically a replacement function lacking the compiled arity entry point; `with-redefs` does install the replacement. It is not an ignored redefinition and not a production exception-classification divergence.

A local diagnostic runner under ignored `target/6db-lanefix/probe/lane_probe.cljs` runs the original nine-cut test and prints the failure handed to the existing `failed-stage`. It reproduces all seven landing failures at the original assertion site. Every affected cut throws:

```
yin.vm.ucf.holder.reader.replay.cljs$core$IFn$_invoke$arity$2 is not a function
```

Its ex-data is nil. The compiled production call explicitly uses `reader.replay.cljs$core$IFn$_invoke$arity$2(...)` (pre-fix generated `node-probe-out/cljs-runtime/yin.vm.ucf.holder.driver.js:2689`). The original replay function has one- and two-argument arities. The test replaces it with `(fn [& args] ...)`, which lacks that two-argument property. Consequently Node throws a JavaScript TypeError before the cut wrapper executes. The production classifier correctly treats that TypeError as `:program-failure`; real tenure exceptions in the same probe retain `{:yin.vm.ucf.holder.driver/cause :lease-bound}` and classify correctly. The seven affected cuts are replay, after-replay, emit, after-emit, read, after-read, and between. This matches the jupiter log, read directly over SSH.

A replay-only fix exposed the same latent mismatch at emit: the remaining five cuts throw `yin.vm.ucf.holder.writer.emit.cljs$core$IFn$_invoke$arity$3 is not a function`, also with nil ex-data. The compiled production call uses emit's three-argument entry; the original has two- and three-argument arities. Evidence: `node-probe-replay-only.log`. The first broader Node attempt then measured **439 tests, 27,503 assertions, 5 failures, 0 errors**, all five at the same clock-cut classification assertion; other namespaces passed.

Fix: replace replay's test wrapper with named `fn replay-cut` carrying explicit one- and two-argument arities, and emit's with `fn emit-cut` carrying two- and three-argument arities. Default arguments match the original functions. Both execute the unchanged cut logic and call the captured originals. The other replacements target single-arity functions, whose compiled calls are ordinary function calls; the static arity-entry mismatch does not apply there. All assertions and the existing cljd-first gate remain; no cljs gate was added. No production STOP is triggered because the production classifier is not divergent.

Evidence: `target/6db-lanefix/{node-probe-build,node-probe,node-final}.log`; diagnostic runner and its generated output are ignored artifacts, not committed changes.

## Dart source form and fix

Read shiva's generated Dart around line 6828. The failing expression is `$UNDERSCORE_$1=t_test.expect(...)`; its embedded source metadata names `compose_rows_test.cljc:2642`. It comes from the `_ (if child? ... (is ...))` let binding in `exclusive-fuel-boundary-and-running-install-child-handoff-test`.

Wrapped the unchanged conditional assertion block in `do` with final nil. All child-phase, child-control and root-control assertions still execute. The binding now has a nil value rather than attempting to use Dart's void-returning expect. No gate or test suppression.

## Verification

- cljstyle on the two changed files: done.
- Kondo: **0 errors, 0 warnings** (`target/6db-lanefix/kondo.log`).
- Node final focused driver namespace (all nine cuts retained): **124 registered tests, 1,741 assertions, 0 failures, 0 errors**, build exit 0 and 0 warnings (`node-driver-final.log`). The registered slow test is skipped as usual. Broader first attempt and diagnostic iterations are recorded above.
- Local cljd aggregator, all eight requested namespaces: **293 tests passed in 8 shards, exit 0** (`dart-final.log`). The original void compile error is resolved. Existing cljd-first cut-test gating and slow-test skips remain unchanged; this does not claim that the nine clock cuts ran on Dart.
- Whole JVM quick tier: **4,090 tests, 269,314 assertions, 0 failures, 0 errors**, exit 0 — unchanged from the required baseline (`jvm-quick.log`).
- `git diff --check`: clean.

## Commit / push

**fbb49cf7582dafbf05f94c593096b3971dce5bf6** — `test(yin.vm.ucf): fix Node cut arities and Dart assertion binding`.

Appended directly after `82d48e1e` and pushed successfully to `origin srvm-6d-b` as a fast-forward (`82d48e1e9..fbb49cf75`). No amend, force, or merge. The commit contains only:

- `test/yin/vm/ucf/holder/driver_test.cljc`: preserve replay/emit replacement arities on CLJS.
- `test/yin/vm/ucf/compose_rows_test.cljc`: nil-valued assertion binding for cljd.

Tracked working tree and staging are clean. The report and all target evidence remain outside commits. No production-divergence STOP and no design STOP.

Completed-GMT: 2026-10-16 19:09:23 GMT
Completed-Local: 2026-10-17 02:09:23 +07

READY
