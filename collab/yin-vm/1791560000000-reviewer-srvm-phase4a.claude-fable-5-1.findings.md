Completed-GMT: 2026-10-10 15:23:07 GMT
Completed-Local: 2026-10-10 22:23:07 Asia/Bangkok

# Adversarial review: semantic-register-vm slice 4a (commit 9a8e7915)

Reviewer: claude-fable-5-1. Read-only review of
`src/cljc/yin/vm/semantic_register.cljc`, the three new test namespaces,
the codex report, evaluator design §1-§5.1, frozen design §2.2-§2.5, §8.1,
`semantic.cljc`, `engine.cljc` (seams used), `ast_walker.cljc` (closure
application), `analysis.cljc`, `linearize.cljc`, `corpus.cljc`,
`parity_test.cljc` (B0 corpus), `code.cljc:864-879`, `code_test.cljc:60-72`.

What was run: `git show --stat HEAD`, `git diff --stat 0ff25b58 HEAD`
(four new files, 760 insertions, nothing else touched), grep/sed reads.
No test lane was re-run (the orchestrator's three-host run is taken as
given). Two attempts at an ad-hoc `clojure -e` probe were refused by the
sandbox and one was mis-routed into the test runner (its `-e` is
"exclude"); no probe output was obtained, so every count and mutant
below is established by reading the code and the corpora, as the brief
allows. No file other than this one was created or modified.

## Findings

severity | file:line | evidence | recommended correction
---|---|---|---
GAP (fix before merge, test-only) | test/yin/vm/semantic_register/vm_test.cljc:146-155 | Acceptance item 8 is vacuous as specified and as implemented. For `((fn [x] x) nil)` the frame's saved window is `{}` (L(3) = {0}, saved = {}), the frame `rd` is 0 and the body's result register is also 0, so `(dissoc W' 0) = {}` holds for a `:return` that writes into the callee's window instead of the frame's, and for one that writes `rd` twice. Mutants M6 and M7 below survive the entire suite. No other test runs a non-tail closure call with a non-empty saved window to its `:return` and asserts the exact window after it. | Replace the program with one whose saved window is non-empty, whose frame `rd` differs from the body's result register and whose callee window has more than one key, e.g. `(app (v 'f) (app (lam '[z] (app (v 'g) (v 'z))) (v 'x)) (v 'y))` with `f`=`vector`, `g`=`identity`, `x`=nil, `y`=2 (the worked-example shape with a real lambda for the inner call: frame window `{1 f}`, rd 2, callee window `{1 g 2 z 0 v}`). Assert `(= (assoc (:window frame) (:rd frame) v) (:window after))` exactly, then `(= [nil 2] (vm/value (vm/run after)))`.
GAP (4b brief) | docs/design/yin.vm.semantic-register-vm.evaluator.md:917-919 | "`register-restore` with steps 2-3 of §2.2 (step 1 and the `:call-id` handling land in 4b)" is self-contradictory: step 2 of §2.2 (lines 431-439) is nothing but the `:call-id` handling. Codex implemented step 3 only and said so. | Amend to "step 3 of §2.2 (steps 1-2, the FFI phases, land in 4b)". 4b's brief must list step 2 explicitly.
GAP (4b brief) | test/yin/vm/semantic_register/walker_trace_test.cljc:23-25 | The oracle's empty-`K0` tail branch (`{:event? true :value (:value after)}`) is reached by no traced row: no eligible row of the three corpora has a tail `:call` in the main sequence (B0 has no `:tail?`; every corpus tail is inside a lambda body; definition-programs have none). The branch is live only in `vm-test/tail-primitive-returns-through-k`, which does not trace. | Add a 4a trace-only row `(tail (app (v '+) (lit 1) (lit 2)))` with pinned `[[:value :host-fn] [:value 1] [:value 2] [:value 3] [:halt 3]]`.
GAP (4b brief) | test/yin/vm/semantic_register/walker_trace_test.cljc:27 | `extract` has no `:continuation` arm (§4.3 table row 5) and no `:resume` arm; both fall to `:else {:event? false}`. Correct for 4a (both throw before `extract`), silent in 4b. | 4b must add both arms, reading `κ`'s `:deliver :rd` / the parked record from the pre-state, as §4.3 specifies, and pin a trace row for each.
GAP (corpus) | test/yin/vm/semantic_register/parity_test.cljc, corpus.cljc | No program binds a parameter that shadows an outer binding, so the `(merge (:env c) (bind-params …))` order (`semantic_register.cljc:142`) is untested. The walker uses the same order (`ast_walker.cljc:285-287`), so the machines agree, but a reversed merge would survive. | Add `((fn [x] ((fn [x] x) 2)) 1)` → 2 to the eligible corpus.
NOTE | src/cljc/yin/vm/semantic_register.cljc:158 | `run-loop`'s active predicate is `#(some? (:control %))`; the template uses `engine/active-continuation?` (`semantic.cljc:553`). Equivalent in every state 4a can reach (`:control` is nil exactly when halted or blocked), but it diverges from the engine's own definition and from the design's "as `semantic.cljc:885`". | Use `engine/active-continuation?` in 4b for uniformity.
NOTE | src/cljc/yin/vm/semantic_register.cljc:80-83 | `scheduler-round` re-implements `engine/scheduler-round` (`engine.cljc:2259-2270`), whose docstring exists precisely because "every VM was writing this composition by hand". | Call `(engine/scheduler-round machine register-restore)`.
NOTE | src/cljc/yin/vm/semantic_register.cljc:116-127, 136 | `:define`, `:gensym`, `:store-put` and every `:call` materialize through `put-registers` before calling the engine; §1.8 says the pure arms "do not materialize" (`put-active`, `gensym`, `active-store`, `operator-kind` read only `:store`, `:module-stores`, `:id-counter`, `:owner`). Harmless (the exit `put-registers` overwrites every register), one extra record `assoc` per such instruction. | Optional: drop the four materializations, or keep and amend §1.8. Not a correctness issue.
NOTE | src/cljc/yin/vm/semantic_register.cljc:139-140 | The `ret` frame (and its `analysis/saved` lookup at `(inc pc)`) is built for a tail closure call too and then discarded. `(inc pc)` is always in range (a tail call is never a body's last instruction), so it cannot throw. | Build the frame only under `(not tail?)`.
NOTE | src/cljc/yin/vm/semantic_register.cljc:96 | `image` is re-fetched from `(:code machine)` on every instruction rather than held as a loop local as `semantic.cljc:291` does. Correct; a small per-instruction map lookup. | Optional.
NOTE (mutant equivalence) | src/cljc/yin/vm/semantic_register.cljc:140 | Replacing `analysis/saved` by `analysis/live` (saved window includes `rd`) is an equivalent mutant in this grammar: by definite assignment the saving instruction is `rd`'s only writer on the path, so `rd ∉ dom(W)` at every call site and `W↾L(p) = W↾(L(p) − {rd})`. `return-frame-shape` compares the frame against `analysis/saved` of the same image, so it would not notice either. The subtraction matters only for the frozen design's foreign-engine reconstruction, where `L` is a static set, not a window. | No change; record in 4b brief so nobody "tests" the subtraction at a call frame.
NOTE (report) | collab/…phase4a.report.md item 12 | "arm result/intermediate writes in order `[5 7 8 9 6]`" is a static read of the golden vector (`vm_test.cljc:216-218` maps `second` over `(subvec (golden :if-in-tail) 11 16)`), not an observation of the machine's write order. The runtime half of that assertion is the key-set check at line 215. | Reword in the report; optionally assert the order from the stepped windows.
NOTE (report) | collab/…phase4a.report.md item 8 | "dissociating frame rd from restored W gives exactly saved W" is literally true and proves nothing, as the first finding shows. | Covered by the first correction.
NOTE (design) | docs/design/yin.vm.semantic-register-vm.evaluator.md:33-41 | §1.1 says the namespace requires `yin.vm.ucf`; the code does not and need not (the address comes from `code/load-vector`). | Doc nit; drop `yin.vm.ucf` from the list or say "may".

No DEFECT in `semantic_register.cljc` was found: every 4a arm matches the
frozen §2.4 equation (details under A).

## Mutation table

mutation | caught by | or SURVIVES
---|---|---
M1 saved window includes `rd` (`analysis/live` for `analysis/saved`) | — | SURVIVES, but equivalent (rd ∉ dom W at a call; see NOTE). Not a gate failure.
M2 saved window keeps all of W (`:window W`) | `vm-test/return-frame-shape` (literal `{1 vector}` and the `saved` comparison both fail: W is `{1 f 3 g 4 x}`) | caught. `return-writes-rd-once` would NOT catch it (its frame window is `{}` either way).
M3 callee inherits the caller's window (`W` for `{}` at closure entry) | `return-frame-shape` `(= {} (:window after))`; `tail-call-grows-no-frame` same assertion | caught
M4 tail closure call pushes a frame (`(conj K frame)` unconditionally) | `tail-call-grows-no-frame` `(= (:k before) (:k after))`; `tail-countdown` K-bound | caught. Values and traces are unchanged by this mutant (the extra frame's `rd` equals the following `:return`'s register), so only these two tests gate it.
M5 tail primitive writes `rd` and continues at pc+1 | `tail-primitive-returns-through-k` (body window would hold key 0) | caught. Final value and trace are unchanged (the body's `:return 0` relocates the same value, and `extract` reads register 0 either way), so this one test is the sole gate.
M6 `:return` writes `rd` into the callee's window instead of the frame's (`(assoc W (:rd frame) v)`) | — | SURVIVES. `return-writes-rd-once`: frame window `{}`, callee W `{0 nil}`, rd 0 → `(dissoc {0 nil} 0) = {}` passes. Every eligible corpus row with a non-tail closure call either has an empty saved window (`lambda application`, `arity-0`, `higher order`, `nested-lambdas` pc 2 and 4, `define then read`, `redefine x twice`) or, in the one row with a saved register (`a stored closure is called by name`, saved `{1 (fn [ignored] r)}`), the clobbered register 1 holds `*`, and `(* 10)` = 10 = the expected value, so value, store slice and trace all agree. Hand-verified.
M7 `:return` writes twice (`rd` and the body's own result register into the restored window) | — | SURVIVES. Same reason: in `return-writes-rd-once` both keys are 0; elsewhere the stale key is never read.
M8 `:define` writes the task store (`assoc :store`) regardless of `store-of` | `define-routes-to-the-module-store` (module-stores foo lacks answer; store has it) | caught
M9 oracle treats a returned `:none` as "no event" | `trace-oracle-pinned-values` `:literal-none` (`[[:halt :none]]` ≠ pinned) | caught, on both sides at once
M10 oracle classifies park from the value's shape | `trace-oracle-pinned-values` `:parked-shaped-literal` (`[:park …]` ≠ pinned `[:value …]`) | caught
M11 equal fuel exhaustion counted as a pass | `traces-align` second `is` (`not-any? :fuel-exhausted`) | caught (the assertion is live even though no row exhausts)
M12 `:branch-false` inverted | `branch-arms-share-rd` (`:if {'c nil}` → 2), B0 if rows | caught
M13 `:halt` keeps `store-of` in `:env` | `halt-shape` `(= {'x 1} (:env after))` | caught
M14 `register-restore` `:return` empty-K halt uses `(:env entry)` not `(:env base)` | `halt-shape` third block (entry has no env → `(:env after)` nil ≠ `{'x 1}`) | caught
M15 `load-vector` ignores `:id` | `segment-ids` (no "already holds different code" throw → `re-find` on "" fails) | caught
M16 callee env merge order reversed | — | SURVIVES (no shadowing program); walker agrees with the current order, so parity is not a gate either. GAP above.
M17 `extract` reads `(get (:window after) 0)` for an empty-`K0` tail primitive | — | SURVIVES: branch never executed by a traced row. GAP above.
M18 effect-valued host-fn result delivered as a value instead of `:not-in-slice-4a` | `deferred-arms` (`require` call expects `{:reason :not-in-slice-4a :op :call}`) | caught
M19 continuation operator treated as closure | `deferred-arms` (`k` call) and, through `values/payload`, would throw differently | caught

## A. Semantics, transition by transition (against frozen §2.4, evaluator §1.3, §1.8, §1.9, §2.2)

- `:const`, `:var`, `:closure`, `:jump`, `:branch-false`: exact (`semantic_register.cljc:101-115`). `:var` passes `E`, `active-store` under `env-store-of E`, primitives, modules, name, as §1.8. `:closure` payload keys unchanged (`:type :params :entry :segment :env`), owner `(:owner machine)`.
- `:define`: `put-active` under `(engine/env-store-of E)` with `W[rs]`, then `W[rd ← W[rs]]` (`:116-119`). Exact; F7 honoured; `store-put`'s reserved-key refusal is reached through `put-active`.
- `:gensym`, `:store-get`, `:store-put`: exact (`:120-127`).
- `:halt`: `put-registers nil nil {} (without-store-of E) nil` + `:value (get W r)` (`:128`). Exact.
- `:return`: pop → `⟨frame.segment, frame.pc, (assoc frame.window frame.rd v), frame.env, (pop K)⟩`; empty K → halt as `:halt` (`:129-133`). Exact, one write.
- `:call`, closure, non-tail: `E' = (merge (:env c) (bind-params …))`, frame `{:type :return :segment seg :pc (inc pc) :env E :window (select-keys W (analysis/saved a (inc pc) rd)) :rd rd}`, callee window `{}` (`:138-143`). Exact. Tail: `K` unchanged, `rd` never written. Exact.
- `:call`, host-fn value, non-tail: `W[rd ← v]`, pc+1. Tail: `return(v)` inline against `K` (pop or halt with `(without-store-of E)`, the "immediate completion" rule of §2.2/F2). Exact; `rd` never written on the tail path.
- `:call`, host-fn effect: throws `{:reason :not-in-slice-4a :op :call}` before `tail?` is consulted. `:continuation`: same throw. Non-callable: `engine/operator-kind` throws `:not-applicable` / `:foreign-value` before the `case` (ownership checked against `(:owner materialized)` = `(:owner machine)`).
- Unknown mnemonic (`:park`, `:resume`, stream ops, `:ffi-call`, `:current-continuation`): `{:reason :not-in-slice-4a :op op}`.
- `register-restore`: step 3 exactly as §2.2 (`:rd` arm one write at `(:rd (:deliver entry))`; `:return` arm pops or halts with `(without-store-of (:env base))`; missing record throws `{:rule :deliver}`). Steps 1-2 absent (see E).
- `put-registers`: §1.7's shape; `:halted? (and (not blocked?) (nil? seg))`, `:k` nil when empty; `:value` written only at exits. Exact.
- Loader/attach/load-ast/reset/eval/create-vm: match the §1.7 table and the `semantic.cljc` templates; `:id` precedence `alias > :id > fresh`; identical reload accepted; conflict message as required; `attach-image` of an aliased address is a no-op by construction.
- Record: `SemanticVM`'s field list with `stack` replaced by `window`, verified against `semantic.cljc:51-80`.

## B. Do the tests gate?

See the mutation table. Summary: 15 of 19 mutants are caught, four survive. Two of the survivors (M1, M16) are equivalent or agreed-with-the-walker and are at most corpus gaps. Two (M6, M7) are real: the suite does not pin the exact post-return window for a `:return` whose frame carries a saved register and whose `rd` differs from the body's result register. This is acceptance item 8, which the design itself specified with a program for which the check is vacuous; codex implemented the specification faithfully, and the code under it is correct by reading. M4 and M5 are each gated by exactly one test, as the design intended; value parity and the trace lane cannot see them.

## C. The oracle

- Tagged results: `extract` returns `{:event? true :value v}` / `{:event? false}` (`walker_trace_test.cljc:13-27`); the loop reads `:event?` first and `:value` only then (`:53-54`). A produced `nil`, `false`, `:none` is recorded. Matches §4.3.
- Destinations: explicit only — `rd` at tuple position 1 for productions, `rd` for a non-tail host-fn call, `(:rd (peek K0))` or `(:value after)` for a tail host-fn call, nothing for closure calls, `:return`, `:halt`, `:jump`, `:branch-false`. No window diffing. Matches §4.3. Blocked steps excluded first.
- Park: register side by `(= :park (first inst))`, walker by `(:type (:control machine))` before the step. Matches §4.1/§4.3. Fuel bound 100000; `[:fuel-exhausted n]` emitted at `n = fuel-bound`; `traces-align` fails on exhaustion even when equal.
- Walker events: `(and (nil? (:control after)) (not (:blocked? after)))`, value `(:value after)`. Matches §4.1.
- Pinned rows: I derived all three and the six-event lambda independently from the vectors and the walker transitions; they agree with the pinned columns (`:literal-none` 1 event; `:literal-none-in-call` closure, operand `:none`, body `x` → `:none`, halt; lambda application: closure, 10, `+`, 10, 1, 11, halt 11). They were also copied from the design table, but the derivation holds, and the pinned test is run against both machines rather than against the design.
- Could the oracle pass two disagreeing machines? Yes for anything not carried by an event value: K depth (M4), the destination of a tail primitive when the body's result register coincides with the frame's `rd` (M5), stale window entries never read again (M6, M7), the store (covered by `corpus-outcomes` and `rule-r-definitions`), the env (covered only by `halt-shape`). The trace lane is a production/delivery lane by design; the window-shape gates belong to `vm-test`, which is where the hole is.

## D. The eligibility gate

- `slice-4a-eligible?` is defined once (`parity_test.cljc:19-22`) and required by `walker-trace-test` and `vm-test`; the predicate text is the design's verbatim.
- Counts by hand from the corpora: B0 26 rows, only `"stream make"` projects a `:stream-make` → 25. `corpus/programs` 32 rows; the 11 named in §5.1 (`:streams :stream-make-default :ffi-call :ffi-call-no-args :current-continuation :park :resume-body :resume-arm :resume-operand :resume-lambda-body :all-terminal-arms`) each project a non-4a mnemonic → 21. `definition-programs` 5 → 5. The tests assert 25, 21, 5 and 51 at run time and the orchestrator's three-lane run passed, so the counts are doubly confirmed.
- Dynamic effect primitives: the eligible rows reference `+ - * / = < > first rest conj list inc vector identity loop f g h k x y c a b s`; none is an effect primitive, and the unbound ones throw "Unable to resolve symbol" on both sides before any call.
- Swallowing: `outcome` and `trace-machine` compare `ex-message`. A `:not-in-slice-4a` throw yields "Instruction is not in slice 4a", which no walker path can produce, so it can never be matched silently; it would surface as an outcome or trace mismatch. Not a hole.

## E. Honesty of the report

- Items 1-7, 9-20: PASS claims are supported by the named tests as read (counts of deftests 18/3/3 confirmed; item 3's six keys and no `:stack`; item 4's three cases; item 6's exact frame; item 7's K ≤ 1 at every step and nil K at halt; item 9's three halts; item 10's key-0 absence; item 11's module store; item 13; items 14-16 counts and normalisation on both sides; item 18's six events; item 19).
- Item 8: marked PASS; the test passes but proves nothing (first finding). The report's sentence is accurate and the conclusion is not.
- Item 12: the `[5 7 8 9 6]` claim is a static read of the golden, not a runtime write order (NOTE above). The runtime part (keys 1..9 present, no key 0, taken-arm values) is real.
- Item 21: honestly marked incomplete; closed by the orchestrator.
- Dart NOT RUN, the Ctrl-C'd shadow run, the delimiter fix, test-after-scaffold: all disclosed. Nothing claimed that the code does not support, beyond items 8 and 12 as noted.
- "Restore steps 2-3" ruling: §2.2 step 2 is `call-id := (:call-id entry); dissoc :parked; ffi/call-result`. That is the `:call-id` handling, and §5.1 defers the `:call-id` handling to 4b. Codex's reading (implement step 3; defer 1 and 2) is the only consistent one; the design sentence is the defect. Ruled correct; §5.1 to be amended.

## F. Portability and repo rules

- Reader conditionals: all three sites are `#?(:cljd Object :clj Throwable :cljs :default)`, `:cljd` first.
- No `for`; `mapv`/`keep`/`doseq` only. No float negation. `assoc` on a possibly-nil map occurs only in `register-restore` `(assoc (:window entry) rd v)` with one key, which is safe on Dart; `W` is never nil in the loop (initialised `{}`, frames via `select-keys`). Protocol methods use distinct parameter names. No EDN reading. `case` on keyword literals is host-safe.
- `git diff --stat 0ff25b58 HEAD`: four new files only. No existing file modified. Nothing from 4b (effects, waits, FFI phases, continuations, module kernel) leaked in; `register-restore` has no `:request-sent`/`:call-id` code.
- Namespaces follow `yin.vm.semantic-register[.x-test]`; shadow's `*-test` discovery and `cljd_agg --only` both pick them up (orchestrator's logs). `walker-trace-test`'s require of `yin.vm.parity-test` drags its five deftests into the Node run; harmless, disclosed.
- kondo/cljstyle/whitespace: orchestrator-verified; nothing in the source suggests otherwise (no unused requires on read).

## G. What the design should have said

1. Acceptance item 8 needs a program with a non-empty saved window, `rd ≠` the body's result register, and a multi-key callee window, plus an exact post-return window assertion (first finding). As written it cannot fail.
2. §5.1 "steps 2-3" → "step 3" (second finding).
3. A 4a trace-only row with a main-level tail primitive so the oracle's empty-`K0` branch runs; a 4b row for the `:continuation` arm and one for `:resume`, both with `extract` arms that do not exist yet.
4. A parameter-shadowing corpus row.
5. State that at a call frame the saved-window subtraction is unobservable in this grammar (equivalent mutant), so the 4b/phase-6 gates test it where it bites: the foreign-engine `live` operands and the `κ` capture (`rd` is also absent there; the only observable place is a static `L` published on the wire).
6. Pin the active predicate (`engine/active-continuation?`) and `engine/scheduler-round` as the composition, so 4b's blocked/parked states are judged by the engine's flags rather than by `:control`.
7. §1.8's materialization rule and the code disagree on the pure arms; amend one.
8. §1.1's require list includes `yin.vm.ucf`, which 4a does not need.

VERDICT: CHANGES_REQUESTED — (1) strengthen `vm-test/return-writes-rd-once` as in the first finding (test-only; no source change; rerun the three lanes); (2) amend evaluator §5.1 "steps 2-3" to "step 3" and note steps 1-2 for 4b; (3) carry the remaining GAPS and NOTES into the 4b brief.
