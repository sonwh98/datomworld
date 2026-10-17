Completed-GMT: 2026-10-15 20:50:39 GMT
Completed-Local: 2026-10-16 03:50:39 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: pending

# Architect review: G2 retained-FFI pre-attach refusal (uncommitted on srvm-retained-ffi)

Reviewed the 5-file diff (+72/-6) against the engineer report, gap list G2 / §1.3, `rows.cljc`, `layout.cljc`, both backend restores, and the harness. Read-only except this report and one log.

## Verification run here

| Check | Result |
| --- | --- |
| `clojure -M:test -e :slow -n yin.vm.semantic.layout-test -n yin.vm.semantic.safepoint-harness-test -n yin.vm.debruijn.register-test -n yin.vm.debruijn.stack-test -n yin.vm.semantic.effects-test` | 123 tests, 4,041 assertions, 0 failures, 0 errors, exit 0 (`collab/arch-retained-ffi-focused.log`) |
| `git diff --check` | clean |
| kondo | not rerun (needs approval in this session); engineer reports 0 errors / 0 warnings |
| Node / Dart | not run by either party; expectations in (6) |

## Findings by question

**(1) The predicate refuses exactly a retained FFI request.** Confirmed.
- A `:ffi-request` pending is minted in exactly one place, `rows.cljc:1530-1536`: a `:put` wait with `:request-sent true`. The reverse at `rows.cljc:2185-2190` restores `:request-sent true` plus `:response-cursor`/`:response-stream`. No other reason maps to `:ffi-request`.
- The predicate reads task slot 13 as `(node :wait [state pending])` (`rows.cljc:1668`) and the pending as `[reason & xs]`, the same shape `validate-frames` reads at `rows.cljc:1117-1119`. `(first (:slots (second (:slots w))))` is the reason. Correct.
- Positive control: `h/sent-ffi` parks as `:reason :next` with `:call-id` and no `:request-sent`, which `pending-node` emits as `[:ffi cell call-id op]` (`rows.cljc:1527-1528`). That is the ordinary FFI response pending, the right counter-example; the test asserts `(nil? (:request-sent …))`, `(nil? (lower-defect …))`, then a full `crossed` run to `"echo!"` with attaches > 0.
- Nothing else is newly refused. Only row 4 of the matrix builds with `retained-ffi` (`safepoint_harness_test.cljc:463`); rows 7-12 and 17 are already out of backend lanes via `link-rows`. `unlowerable` is unchanged, so the count assertions at `:1688` (16) and `:1791` (92) are untouched and pass in the run above. Slow law 5 (350 at `:1805`) is likewise unaffected; the engineer's whole `:slow` tier passed.

**(2) Vocabulary and ordering.** Confirmed. `{:yin.k/status :yin.k/unsupported :yin.k/kind :retained-ffi}` matches the `:installs` sibling exactly (status + kind, no path). The branch sits after `:free-env-value` and before the register-only `:continuation-registers`, which is the right place: a task with both installs and a retained request still reports `:installs`. `lower-task` (`layout.cljc:964-968`) decodes, throws the defect, and only then calls `rows/lower-task`/`kernel-task`; the tests' `recording-attacher` count of zero pins that no attach happens.

**(3) Semantic kernel receiver is not refused.** Confirmed, by construction rather than by `kind`.
- The `:retained-ffi` branch does not consult `kind`, so `lower-defect` itself is receiver-independent for this limit. But `lower-defect` is reached only through `layout/lower-task`, whose `kernel-kind` is `:stack` or `:register` (`layout.cljc:287-293`). Every semantic-receiver path (`lane-lower` for `:semantic`, `holder/driver.cljc:1565`, `holder/export.cljc`) calls `rows/lower-task` directly and never sees `lower-defect`.
- The semantic kernel honouring the carried pair (`semantic.cljc:105-111`) is therefore not regressed: law 5's row-4 transfers into the semantic receiver still run (92 includes them). Both backend restores (`debruijn/stack.cljc:564-570`, `debruijn/register.cljc:508-514`) re-park with `ffi/response-wait-entry` on their own call pair and drop the carried cursor/stream, which is precisely why the refusal is correct for them and only them. The gate is receiver-dependent at the right layer; no change needed. (P3-b below suggests one docstring word.)

**(4) Docs.** Accurate, one stale phrase.
- Continuation §4.6 :1103-1107 and UCF §7.11 :2924-2926 say the right thing and no more. The sentence before (:1095-1096, "row 4 is not lowered into either backend while the seam remains absent") remains true.
- P3-a: §4.6 :1097 still reads "the code's four pre-attach checks" and the "checks, in order" list at :1098-1102 enumerates four; the fifth is only in the following sentence. Minimal fix: "five pre-attach checks" and insert "a retained FFI request (`:retained-ffi`)" after `:free-env-value` in the ordered list. Non-blocking.

**(5) Harness change.** The table (`unlowerable`) and its meaning are untouched; its docstring ("Emptied when the retained-FFI seam lands") stays correct. `unlowerable-retained-ffi-is-refused` iterates `ordered-pairs` filtered by `(contains? unlowerable [4 to])`, i.e. exactly the four excluded transfers, and asserts the exact refusal map and zero attaches. It overlaps with the layout test (which covers six source/receiver pairs including same-lane) but each pins a different claim: the layout test pins the predicate and its control, the harness test pins that the matrix's own exclusion is now a named refusal. Acceptable.

**(6) Node/Dart expectations.** Both new deftests are quick-tier (not `^:slow`) and will auto-discover on Node and Dart. Every helper they use (`retained-ffi`, `sent-ffi`, `served`, `lane-lift`, `lane-lower`, `recording-attacher`, `error`, `crossed`) is already exercised cross-host by the existing row-4 law 4/5 cases, and `error` catches with the cross-host conditional. Loop sizes are 3 and 4, under the ClojureDart 32-chunk hazard. Expect: Node and Dart quick lanes green with the same per-namespace counts as JVM (layout-test +2 tests, harness-test +1 test), law 4 still 16, law 5 still 92. Lanes should be run once before merge per the one-lane-set-at-landing cadence.

## P1
None.

## P2
None.

## P3
- a. Continuation §4.6 :1097-1102: "four pre-attach checks" and the ordered enumeration omit the fifth check. Two-word fix, may land with the commit or after.
- b. `lower-defect` docstring: "whose response pair the backend's re-park cannot preserve" is right; consider "a de Bruijn backend's re-park" to make explicit that the judgement is backend-only, since the branch itself does not read `kind`.
- c. Kondo not independently rerun by the reviewer (approval-gated); engineer's 0/0 is accepted.

SIGN-OFF
