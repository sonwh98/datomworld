Completed-GMT: 2026-10-11 03:40:30 GMT
Completed-Local: 2026-10-11 10:40:30 Asia/Bangkok

# Adversarial review: slice 4b Stage 2 (commit bdf48cf4 on srvm-phase4b)

Reviewer: claude-fable-5-1 (non-author family; author codex gpt-6.1-sol).

What was read: the Stage 2 brief, both codex reports (Stage 2 and r2),
`git diff 0f4522b9 HEAD --stat` (ten files), the full source diff, the
diffs of the four extended existing suites and of the three modified
semantic-register tests, the Stage 2 additions to `effects_test.cljc`, the
whole of `link_test.cljc`, evaluator §2.2, §3, §5.2 items 14-26, §5.3, the
stack VM's `IModuleKernel` implementation (`semantic.cljc:954-1058`), the
protocol (`module.cljc:395-461`), `ffi/call-id`, `ffi/response-wait-entry`,
`ffi/call-result`, `ffi/require-call-pair!`, `ffi/put-request`, the
engine's `spawn-child`, `link-install`, `receive-module`, `start-install`,
`encoder` (ownership check), `heap_reclamation_test.cljc` (deftest list).

What was run (JVM, foreground, literal `-n` lists): nineteen mutant runs
of `effects-test link-test vm-test walker-trace-test parity-test` (76
tests / 813 assertions when nothing fails), one mutant at a time, each a
textual edit to `semantic_register.cljc` reverted by the exact reverse
edit; after every revert `git diff --quiet HEAD --
src/cljc/yin/vm/semantic_register.cljc` returned 0, and `git status` shows
no tracked file modified at the end. Node and Dart were not run by me (the
orchestrator's Node 114 / 2006 and Dart 181 are taken as given). No file
other than this one was created.

## Findings

severity | file:line | evidence | correction
---|---|---|---
GAP, close before merge (test-only) | src/cljc/yin/vm/semantic_register.cljc:237; test/yin/vm/semantic_register/effects_test.cljc | Mutant R17 (`engine/pin-refs` dropped from the `:ffi-call` arm) passes all 813 assertions. §1.10 names the pin explicitly (`(put-registers (engine/pin-refs vm args) …)`) and the engine's docstring states why: a value carried by an FFI request leaves the VM's view, so no trace can find a ref inside it (owner decision 1). Without the pin, a cell ref passed to a host call is swept while the host still holds it. No row passes a cell ref over FFI and collects. | Add to `effects-test`: `(ffi :op/echo (cell/new :keep))` with the cell module registered; after the call blocks, assert the cell's id is in `(get-in blocked [:gc :pinned])`, and that `(engine/collect (assoc blocked :window {} :env {}))` keeps the cell. Template: `heap_reclamation_test/a-ref-put-on-a-stream-stays-authentic-test` (`:391-403`), the stream-side twin of this rule.
GAP (same pass) | src/cljc/yin/vm/semantic_register.cljc:409-427; test/yin/vm/semantic_register/link_test.cljc:137-156 | Mutant R12b (`spawn-module` gives the child the parent's `:window`) survives; only the variant that also shares `:env` is killed (R12, by errors in `tail-require` and `require-installs-and-preserves-delivery`). §3.4: "no window crosses between tasks". By definite assignment the stale keys are unread, so the share is unobservable to the program; it is observable to GC (the parent's refs become child roots) and to `vm/control`/`:window` inspection. | In `image-and-store-context`, assert `(= {} (:window child))` and `(nil? (:control child))` is already implied by `load-vector`'s control; one assertion closes it.
NOTE (single gate) | effects_test.cljc:457 | Mutant R11 (composite call id built without the caller token) is killed by exactly one assertion in the semantic-register suites (`ffi-sent` `[:caller :parked-0]`). The existing `ffi_test/every-vm-kind-parks-and-resumes-under-a-composite-id-test` on the fifth kind is the second gate; I did not run that suite per mutant. | None required.
NOTE (oracle, by construction) | test/yin/vm/semantic_register/walker_trace_test.cljc:22-24 | `:ffi-call` was added to the production set, but in the step trace an FFI call always blocks (the bridge is driven outside the step loop), so the production branch is never reached and `(:blocked? after)` always wins. Every traced FFI row ends at the block (`:ffi-call-no-args` → `[]` on both machines; `:ffi-call` → `[[:value 1] [:error …x…]]`). The classification matches §4.1 ("completing in this step → yes; if the step parks or blocks → no"); it is simply untestable here. The run lane (`ffi-round-trip-parity`, the bridge rows) carries FFI delivery. | Record in the 4b closing note; no test change.
NOTE (divergence) | src/cljc/yin/vm/semantic_register.cljc:454-456 | `lower-closure`'s binding/format refusal throws `{:reason :binding-mismatch :marker marker}`; the template's `binding-mismatch!` carries `{:reason :binding-mismatch :binding :format :expected-format}` (`semantic.cljc:964-970`). Same `:reason`, different ex-data; `marker-checks` asserts only `:reason`. | Align the ex-data with the template (optional).
NOTE (design wording) | docs/design/yin.vm.semantic-register-vm.evaluator.md §5.2 "fourteen bridge rows" | `semantic_ffi_test.cljc` has 13 deftests; all 13 are respelled. The author did not invent a fourteenth. | Correct the count in the design when it is next touched.
NOTE (authorized) | test/yin/vm/linker_require_test.cljc:612-613 | The one exclusion is exactly as authorized: the register backend is removed from a single scanner-dependent test with an explanatory comment; no assertion text changed. The empty-obligations fixture carries the design's required comment. | Phase 5c retires both.

No DEFECT in `semantic_register.cljc`. Every Stage 2 arm matches §1.10,
§2.2 steps 1-3 and §3.3 (details under 3 and 5 below).

## 1. Acceptance items 14-17, 19-23, 25-26 and my mutants

- 14 `ffi-sent` (+ `bridge-manual-bridge-step-test`): exact composite id, `:reason :next`, window `{1 vector 2 nil}`, `{:deliver :rd :rd 3}`, `(inc pc)`, no writer fields, parked payload equality, decoded exact window, empty `:parked`. Kills R3, R11, R16.
- 15 `ffi-retained` (+ three bridge `full` rows): request identity/op/args in `:datom`, re-park without a guest write (blocked, control/k nil, window unchanged, `:yin/blocked`), reader keys, parked retained, request still readable on call-in, then completion `[nil 7 2]`. Kills R1 (23 assertions), R4, R18.
- 16 `retained-response-route`: default and carried route, payload preserved, `:parked` untouched, control/k nil.
- 17 `ffi-error-raises` (+ `bridge-error-responses…`): "FFI call failed" on an error envelope; `ffi-response-validation` adds malformed and mis-correlated envelopes and the nil-ok delivery `{0 nil}` with `:parked` emptied.
- 19 `require-installs-and-preserves-delivery` (+ `walker-link-parity`, + the fifth backend in `a-require-links-installs-and-resumes-with-the-module-test`): `:link-response` entry with `{:deliver :rd :rd 5}`, `:install` entry carrying the same payload, request name/format, `[nil 42 2]`, no waits/installs, slice marker. Kills R2 (errors: "Entry carries no delivery record") and R12.
- 20 `tail-require`: `{:deliver :return}`, K count 0/1, no `:window :env :pc`, `'foo` / `[nil 'foo 2]`, env without store-of.
- 21 the three fifth-backend store tests + `image-and-store-context`. Kills R13, R14, R19.
- 22 `install-refusals` (+ fifth backend `an-install-refuses…`): `:export-missing`, malformed and v3 vectors refused at phase `:loading` with `:status :refused`, `:absent` link refusal. Kills R5 (6 assertions).
- 23 `marker-checks`: params, entry, format, detached origin, unrooted body. Kills R6, R7, R8, R9, one assertion each.
- 25 counts 26 / 32 / 5 / 5 asserted, 68 traces aligned, six pinned rows kept, `continuation-arity-two-trace`, FFI and stream run parity, linked walker parity.
- 26 orchestrator's three lanes; kondo/cljstyle per the reports.
- M-P: `declared-profile-refusal` kills R10 (`:yin.k/undeclared-effect`).

Author's 23-mutant table versus mine: every overlapping mutant agrees
(retained-writes-guest = R1, link-loses-delivery = R2, call-id-not-removed
= R3, decode-before-retained = R4, install-loader-validation-bypassed ≈ R5,
three marker mutants = R6-R8, M-P = R10, sent-reader-loses-window = R16,
store-context-omitted = R13, image-identity-not-verified ≈ R19). Mine add:
the origin check (R9, killed), the composite id (R11, killed), the
`:datom` of the retained writer (R18, killed), the lifted env not stripped
(R14, killed), the child window share (R12 killed via env, R12b survives)
and `pin-refs` (R17, survives). The author's one survivor, swapping the
`base`/`value` let bindings in step 2, is an equivalent mutant: `ffi/call-result`
takes no machine, `base` is a local immutable value, and on a throw no
machine is returned, so the relative order of removal and decode is
unobservable; §2.2 itself says the order "is not a cleanup guarantee to
callers". Judgement: equivalent, correctly recorded, no gate wanted.

## 2. The four registrations and scope

`continuation_invoke_test`: one new `:semantic-register` runner (bounded,
blocked → throw), the three existing runners untouched. `ffi_test`: one new
`:semantic-register` kind. `rule_r_test`: one new backend and the authorized
`select-keys` extension at `a-parked-read-in-a-definition-resumes-and-writes`.
`linker_require_test`: one new backend map with the design's `(constantly
[])` and its required comment, plus the single authorized exclusion. No
other line in those files changed. `git diff 0f4522b9 HEAD --stat` lists
exactly ten files: the source, the four above, the five semantic-register
test files (`effects_test`, new `link_test`, `parity_test`, `vm_test`,
`walker_trace_test`). Nothing else in the repo changed.

## 3. `IModuleKernel`, nine methods against the template

`link-format` (interim keyword, `code/contract` "v4"; F6 permits the
interim format), `spawn-module` (same composition keys as
`semantic.cljc:978-993`, through `create-vm` + `load-vector`; no
`:bridge`/`:call-in` passed, as the template), `image-identity`
(`jing/segment-key`), `image-holds?` (`jing/segment-matches?`),
`attach-module` (`attach-image` under `code/contract`), `lift-closure`
(address by alias column, `:unrooted-body` when none, env encoded without
store-of, `:yin.k/store-of` when present, no ownership check — the engine's
`encoder` refuses foreign values before calling it, `engine.cljc:883`, as
for the template), `lower-closure` (binding/format → `:binding-mismatch`;
attached-image and entry range → `:origin-not-attached`; `check-params!`;
a `:closure` tuple with matching entry at position 3 and params at position
2 → else `marker-mismatch!`; store-of restored into `E`), `gc-roots`
(`:window` for `:stack`), `gc-children` nil. All match §3.3. The only
divergence is the ex-data shape of the binding refusal (NOTE above).

## 4. Trace oracle and FFI

Tagged results, park by executed instruction, destinations explicit:
unchanged from Stage 1; `:ffi-call` joins the production set behind the
`(:blocked? after)` guard. Independent derivation, `ffi-round-trip-parity`'s
program `(:dao.stream.apply/call :op/echo [42])` traced: register
`[:const 1 42] [:ffi-call 0 :op/echo [1]] [:halt 0]` → `[:value 42]`, then
the FFI step blocks → `{:event? false}` → the loop stops at `:blocked?` →
`[[:value 42]]`. Walker: the operand literal → `[:value 42]`; `eval-call`
blocks → stop → `[[:value 42]]`. Equal, and no delivery event is ever
traced for FFI (NOTE above). The linked trace of `walker-link-parity` (a
registry hit, i.e. an effect completing inline): `[:var 1 require]` →
`:host-fn`; `[:const 2 foo]` → `foo`; `[:call 0 1 [2] false]` → the effect
completes inline, non-tail host-fn production → `foo`; `[:halt 0]` →
`[:halt foo]`: `[[:value :host-fn] [:value foo] [:value foo] [:halt foo]]`,
matching the pinned column on both machines.

## 5. Design readings and the two implementer choices

- Require rd 5: derived independently. `(app (v vector) (lit nil) (then
  (require 'foo) (foo/f)) (lit 2))` projects to `[:var 1 vector] [:const 2
  nil] [:closure 4 [ignored] B] [:var 6 require] [:const 7 foo] [:call 5 6
  [7] false] [:call 3 4 [5] false] [:const 8 2] [:call 0 1 [2 3 8] false]
  [:halt 0]`; the require call's destination is 5. Justified; the report
  says so plainly and the first-run correction is recorded.
- Parked-child fixture: the module body ends in a blocking stream read so
  the `:install` phase is observable and the child's own wait set is driven
  (§3.4). Justified; it also gives the install-entry payload equality a
  real intermediate state to compare against.
- Readings: the interim format keyword `:yin.semantic-register/code` (F6);
  13 versus "fourteen" bridge rows (count wording, NOTE); the authorized
  single exclusion; `not-in-slice!`/`:not-in-stage1` replaced by one
  "Unknown register instruction" throw (naming unified, as the brief asked).
  No design text was read differently from how I read it.

## 6. Scope leak and weakened assertions

None. `stage1-eligible?` and `stage2-deferred-arms` were stage artifacts
whose behaviours are now implemented and tested; their deletion removes no
behavioural assertion. `trace-oracle-pinned-values` dropped only the
stage-eligibility guard; all six pinned rows and `return-is-administrative`
remain. Counts rose (25→26, 30→32, 61→68, 3→4 rows) with every prior row
kept. The 4a and Stage 1 tests are unchanged.

## Mutant table

id | mutation (one edit, reverted byte for byte) | result
---|---|---
R1 | retained-request step delivers the request into `rd` | KILLED: 23 failures (`ffi-retained` 499-514, `retained-response-route` 525-530 ×2, `bridge-full-request-is-sent-on-retry-and-answered-test` 809-819)
R2 | `:module/require` builder drops `:deliver` | KILLED: 3 errors "Entry carries no delivery record" (`require-installs-and-preserves-delivery`, `tail-require`, `walker-link-parity`)
R3 | `:parked` not dissoc'd before decoding | KILLED: 7 (`ffi-sent` 474, `ffi-retained` 514, `ffi-response-validation` 549, `bridge-manual-bridge-step-test` 635, `bridge-full-request…` 819, `bridge-completed-calls…` 876/880)
R4 | decode attempted before the retained-request check | KILLED: 3 errors at `ffi.cljc:178` (malformed envelope) in `retained-response-route`, `ffi-retained`, `bridge-full-request…`
R5 | `spawn-module` loads a valid stand-in when the supplied image is defective | KILLED: `install-refusals` 114/117/118 for malformed and v3 (6 failures)
R6 | marker params not checked | KILLED: `marker-checks` 130
R7 | marker entry not checked | KILLED: `marker-checks` 131
R8 | marker format not checked | KILLED: `marker-checks` 132
R9 | origin-image attachment not checked | KILLED: `marker-checks` 133
R10 | M-P `check-callee-effect!` dropped | KILLED: `declared-profile-refusal` 566
R11 | composite call id without the caller token | KILLED: `ffi-sent` 457 (single assertion here; `ffi_test` composite row is the second gate, not run per mutant)
R12 | child inherits parent `:window` and `:env` | KILLED: 2 errors (`tail-require`, `require-installs…`, `engine.cljc:52`), via the env
R12b | child inherits parent `:window` only | SURVIVES. Finding 2.
R13 | `:yin.k/store-of` not written on lift | KILLED: `image-and-store-context` 150/152
R14 | lifted env keeps the store-of key | KILLED: `image-and-store-context` 151
R16 | sent-call reader loses the saved window | KILLED: `ffi-sent` 459/464/467 + 2 errors
R17 | `engine/pin-refs` dropped from `:ffi-call` | SURVIVES. Finding 1.
R18 | retained writer loses `:datom` | KILLED: 13 (`ffi-retained` 491-493, `bridge-full-retains-the-unsent-request-test` 793-801, `bridge-full-request…` 815-819)
R19 | `image-holds?` always true | KILLED: `image-and-store-context` 147

Seventeen of nineteen killed. The two survivors are the pin of FFI
arguments (a design-named step with a real failure mode, no gate) and the
child-window share (unobservable to programs, one assertion closes it).

VERDICT: CHANGES_REQUESTED — test-only: (1) add the `pin-refs` gate (cell ref passed over FFI stays pinned and survives `engine/collect` on the blocked machine, template `heap_reclamation_test:391-403`), apply R17 and confirm it fails; (2) assert `(= {} (:window child))` in `image-and-store-context`; rerun the three lanes; carry the four NOTES (single composite-id gate, FFI production branch unreachable in the step trace, binding-mismatch ex-data, "fourteen" → 13) into the closing note.
