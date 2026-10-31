Completed-GMT: 2026-10-10 12:55:00 GMT
Completed-Local: 2026-10-10 19:55:00 Asia/Bangkok

# Report: phase-4 evaluator design, revision 2 (codex review applied)

Role: Lead System Architect (claude-fable-5-1), continuing session.
Worktree `/Users/sto/workspace/datomworld-srvm-p4-design`, branch
`srvm-phase4-design`. Edited only
`docs/design/yin.vm.semantic-register-vm.evaluator.md` (now 1467 lines,
tracked since the orchestrator's round-1 commit; shows as modified).
The frozen design was not touched. Nothing staged or committed.

## Findings and items → section changed → what was re-grounded

| Codex finding / brief item | Section changed | Recount or re-grounding, and result |
|---|---|---|
| High: trace event definition (item 1) | §4.1 rewritten; F5 in §6 | Event defined by semantic role: **production** (an expression's result transition) or **delivery** (to a popped frame, a captured destination, the halt); **relocation** (`:return r`, `:halt r`, walker frame pops) and routing are administrative. Value equality is not consulted, so `:define`, repeated literals and primitives returning an existing value all count. Both machines use the one definition; the walker's post-state criterion (`:control` nil, not blocked) is shown to pick out exactly the producing and delivering transitions (`ast_walker.cljc:306-612`). |
| High: trace extraction (item 2) | §4.3 rewritten (algorithm + extraction table), §4.4 | Extraction by explicit destination only: production instructions → `rd` at tuple position 1; host-fn non-tail call → `rd`; host-fn tail call → `(:rd (peek K0))` of the **pre-state** `K`, or `(:value vm')` when `K0` is empty; continuation invoke → `(:rd (:deliver (values/payload κ)))` with `κ` read from the pre-state window; `:resume` → the parked record read before the step. Window differences and `K` length changes are never used. Fuel bound `100000` steps, `[:fuel-exhausted n]` event, whole-trace equality with first-index diagnostics kept; fuel exhaustion on both sides is still a failure. Blocked programs stay out of the step trace; the run-to-completion lane is named for their effects and resumption. |
| Medium: 4a gate contradiction (item 3) | §5.1 (new predicate, table, rows), items 12, 14, 15, 17; §4.5 | Chose **deferral**, not implementation, of `:park`/`:resume` in 4a, with one static predicate `slice-4a-eligible?` over the projected vector's mnemonics, shared by the value, outcome and trace gates. Recounted eligibility from `corpus.cljc:54-118`: 11 register-corpus rows excluded (`:streams :stream-make-default :ffi-call :ffi-call-no-args :current-continuation :park :resume-body :resume-arm :resume-operand :resume-lambda-body :all-terminal-arms`), 21 eligible; the review had named two of the resume rows, the predicate catches all four plus `:all-terminal-arms`. Item 12 qualified: arms may write intermediates (checked on the `:if-in-tail` golden: 5, 7, 8, 9, 6 before the tail call), their result definitions share `rd`. |
| Medium: counts and normalization (item 4) | §4.2, §4.5, §5.1, 4b item 25 | Recounted from the files: `parity_test.cljc:42-115` has **26** rows (the review is right; I had 28), `corpus.cljc:56-118` has **32** (the review is right; I had 33); so 4a has **25** B0 rows. Added `trace-normalize`, applied to the pinned `expected` column too; its `"closure value"` row carries `:body` (`parity_test.cljc:76-78`), which neither the register closure nor the walker-side normalizer can reproduce, so `parity-test/normalize` (`:122-134`, which keeps `:body`) is extended, not reused. Count assertions added to the gate tests. |
| Medium: FFI cleanup claim (item 5) | §2.2 step 2 comment, §2.5, 4b item 17 | Confirmed: `base` is a local immutable value; when `ffi/call-result` throws (`ffi.cljc:170-187`) no machine is returned and `semantic_ffi_test.cljc:105-118` asserts a throw only. Removal-before-decoding order kept; the claim that callers receive a cleaned machine removed; sentence added that the wording must not imply mutation of a machine the caller holds; recoverable error delivery named as a separate decision not taken. |
| Medium: 4b item 22 stamp (item 6) | 4b item 22 | Confirmed: the stub response carries only `:image {:value v}` (`linker_require_test.cljc:262-267`); `spawn-module` supplies `code/contract` itself (§3.3). Split into vector refusal through the install path (malformed or stack-shaped vector fails §3.4 item 2; `start-install` catches, waiter refused at `:loading`, `engine.cljc:1445-1456`) and stamp refusal through the public `load-vector` only (4a item 1). |
| Medium: effect-boundary materialization (item 7) | §1.8 (new "materialization rule" paragraph, stream rows), §1.9 (effect arms, builders note), §1.7 (arity table) | Every engine boundary now states `put-registers` first (template lines `semantic.cljc:215, 247, 373, 383, 477`), with the three dependents named: `engine/store-context`/`put-active` read `:env` (`engine.cljc:197-224`), `:cell/new` → `collect` → `module/gc-roots` reads `:window`, and the blocked `base` of §2.2. Every `act-builders` call now passes `(:analysis image)`. Pinned: `create-vm [] [opts]`, `load-vector [vm v stamp] [vm v stamp opts]` with `opts` default `{}` admitting `:id`, `attach-image [vm v stamp]`, `load-ast [vm datoms stamp]` (renamed from `ast-loader`, which in `linearize` is a factory), `register-restore` two arities, and `reset` clearing `:window` to `{}`. |
| Low/editorial F1 and F2 text (item 8) | §2.1, §2.2 (env paragraph), §2.5, §6 F1/F2 | Terminal checking qualified as the run-queue path's only; `resume-continuation` performs none (`engine.cljc:2299-2309`) and is called from an active `:resume` with a materialized active base; neither path **requires** an activation; the `:resume` arm handles the returned halted state (§1.8 row). Exactly-once restated as one delivery per consumed entry or per continuation invocation, with `continuation_invoke_test.cljc:155-181` (three re-entries) as the multi-shot witness. F2: deferred completion leaves `(without-store-of (:env base))`, immediate tail completion the current `E`; the two are explicitly not equated. |
| Editorial (item 9) | §1.6, §1.8, §2.1 | κ's record written in full `{:deliver :rd :rd rd}` with a sentence that no abbreviated shape is accepted; "§2.3 step 6" corrected to "§2.2 step 3, `:return` arm, empty `K`"; the de Bruijn `write-back` precedent qualified: supports return-mode restoration, not an identical validation precedent (it also checks format identity, payload defects and plain-data resume values, `register.cljc:490-507`). |
| §6 amendments (item 10) | §6 rewritten | F1-F7 each give the frozen section, the exact text to replace (quoted from `yin.vm.semantic-register-vm.md` as it stands) and the exact replacement, per the rulings: F1/F2/F5 with the review's changes, F3/F4/F6/F7 as accepted. F3 notes §4.2 needs no text change (its example already shows one frame kind). |
| The two approvals | §7, §5.2 | Recorded: `:yin.semantic-register/code` "v4" interim format; additive registration in the four suites as a narrow exception, including the Rule R `select-keys` change at `rule_r_test.cljc:223`, with empty obligations marked as an install-mechanics fixture in the `linker_require_test` entry's comment. |

## Where I disagreed with the review

Nowhere on substance. Two points of emphasis, both consistent with the
review's text:

- On item 3 the review offered "defer or implement"; I chose deferral
  for `:park`/`:resume` and say why in §5.1 (their semantics is the
  `:return` arm plus the halted-state re-entry, which 4b's park/resume
  rows test in one place). The predicate also excludes `:all-terminal-arms`
  and `:resume-operand`, which the review did not list but which reach
  `:resume` equally.
- On the FFI cleanup the review is right that the existing test proves a
  throw only; I kept the dissoc-before-decode order (the review asked for
  that) and say explicitly that it is not a guarantee to callers.

## Now grounded that round 1 could not

The phase-3 implementation report is present in this worktree as
`collab/1791530000000-vm-engineer-srvm-phase3.report.md` and was read in
full. Its seven readings (rule order of items 10/12, rule names, the
`minted` id and its ill-kinded fallback, `def` of a tail call as a
syntactic `{rd}`, fixed-point liveness, the two load lanes, the image
shape) require no evaluator change: the fallback concerns malformed
input only, and the image shape is what §1.7 wraps. The review's item 8
reached the same conclusion.

## Not grounded

- No tests were run (docs-only brief). The counts above were taken by
  grep over the two corpus files at the current tree; the register
  numbers in §5.1 items 5-6 and 12 were re-derived by hand from the
  goldens and `analysis_test.cljc:131-139`.
- The clock on this machine read 12:54 GMT at the last check; the header
  rounds to the completion minute.

## Files touched

- modified `docs/design/yin.vm.semantic-register-vm.evaluator.md`
- created `collab/1791540000000-architect-srvm-phase4-design-r2.report.md` (this file; `collab/` is never staged)
