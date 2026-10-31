I’ll read the author’s report and full design, then check the proposed amendments, restore paths, and acceptance gates against the frozen design and landed code. I’ll keep the review read-only.
The restore seam is correctly identified, and the worked-example register numbers match the landed liveness test. I’m checking two areas more closely: whether the trace procedure can identify writes from public states, and what cleanup remains observable when FFI decoding throws.
I found problems in the proposed test gates: the B0 corpus has 26 rows, two reachable `resume` cases remain in the 4a corpus, and continuation invocation can replace several window entries at once. Those need corrections in the companion design. The restore approach and one-kind `K` correction still hold.
Completed-GMT: 2026-10-10 12:44:10 GMT
Completed-Local: 2026-10-10 19:44:10 Asia/Bangkok

The evaluator architecture is sound, but the companion design needs corrections before acceptance. The main problems concern the trace oracle, slice gates, and claims about observable cleanup after exceptions. No files were edited and no tests were run.

| Severity | file:line | Evidence | Recommended correction |
|---|---|---|---|
| High — design defect | docs/design/yin.vm.semantic-register-vm.evaluator.md:573 | “Value not already in a register” contradicts counting `:define`, repeated literals, and primitives returning an existing value. | Define events by semantic production or delivery, independent of value equality. Exclude only administrative relocation such as `:return r`. |
| High — design defect | docs/design/yin.vm.semantic-register-vm.evaluator.md:648 | Window differences do not identify one write on continuation invocation: restoring a captured activation can replace several entries, grow or discard K, and cross bodies. | Extract ordinary results by instruction destination; continuation invocation by the captured delivery record; tail primitive results by the popped frame or halt value. Do not infer the destination from map differences or K shrinkage alone. |
| Medium — gate contradiction | docs/design/yin.vm.semantic-register-vm.evaluator.md:745 | 4a omits `:resume`, but its non-deferred corpus includes `:resume-body` and `:resume-lambda-body`, which reach it and should throw “not found,” rather than `:not-in-slice-4a`. | Explicitly defer every reachable unsupported operation, including these cases, or implement its semantics in 4a. Use one shared eligibility predicate for value and trace gates. |
| Medium — unsupported guarantee | docs/design/yin.vm.semantic-register-vm.evaluator.md:370 | Dissociation updates a local immutable `base`. If `ffi/call-result` throws, no updated machine is returned. The existing FFI test asserts a throw, not an observable cleaned machine. | Preserve removal-before-decoding order, but remove the claim that callers receive cleaned state after a throw. Restate 4b item 17 accordingly; introducing recoverable error-state delivery would require a separate decision. |
| Medium — gate contradiction | docs/design/yin.vm.semantic-register-vm.evaluator.md:678 | B0 contains **26**, not 28 rows; the register corpus contains **32**, not 33. Thus 4a has **25**, not 27 B0 rows after excluding “stream make.” Pinned closure expectations also contain `:body`, whereas the proposed normalization removes it. | Correct counts and normalize expected values using the same comparison representation as actual results. |
| Medium — grounding error | docs/design/yin.vm.semantic-register-vm.evaluator.md:977 | 4b item 22 describes a `"v3"` vector refused by `spawn-module`’s stamp check. Vectors contain no stamp; `spawn-module` supplies `"v4"` itself, and the cited stub response supplies only `:image :value`. | Separate malformed/old-shape vector refusal from explicit stamp refusal. Test stamp mismatch through the public loader; do not attribute that check to this install response path. |
| Medium — specification gap | docs/design/yin.vm.semantic-register-vm.evaluator.md:265 | The effect-call table omits materializing current hot-loop registers before engine dispatch, although its template does this. Engine store context, GC roots, and F2 depend on it. The shown `act-builders` call also omits its `analysis` argument. | State the materialization step explicitly for every engine effect boundary and correct builder calls. |
| Low — editorial | docs/design/yin.vm.semantic-register-vm.evaluator.md:1006 | `resume-continuation` performs no terminal-outcome check and can be called from an active `:resume` instruction. Its base is not necessarily blocked with nil control/K. | Qualify terminal checking as the run-queue path’s behavior; say neither restore path *requires* an activation. Also fix §1.6’s abbreviated delivery record to include `:rd r`, and §1.8’s nonexistent “§2.3 step 6” reference. |

1. **Grounding.** I checked more than fifteen cited locations. The following references support their stated mechanisms: semantic record fields `51–80`, register materialization `90–107`, return frame `237–238`, call dispatch `224–263`, return/halt `330–340`, define `356–361`, capture/park/resume `363–397`, FFI `466–518`, image attachment `821–846`, kernel methods `972–1058`; engine ingress `264–281`, ready-entry construction `420–458`, run loop `2149–2163`, run-queue restore `2224–2256`, explicit resume `2299–2309`, effect dispatch `2565–2683`; FFI reader construction `148–167`; module require `546–593`; linker format `692–712`; phase-3 admission `864–879` and saved sets `92–97`.

   The substantive mismatches are listed above. The cited FFI error test proves throwing only. The six-event lambda example is correct: closure, `10`, `+`, `x`, `1`, `11`, then halt. The worked-example numbers are correct: first write is register 1; pc 3’s call destination is 2; `saved(4,2) = #{1}`.

2. **F1 and restore. RULING: ACCEPT WITH CHANGES — frozen §2.4 and §11 item 4.** Restore belongs to `resume-from-run-queue` and `resume-continuation`, not `handle-effect`. Correct the proposed qualification about base state and terminal checks.

   The helper’s successful paths are sound: retained FFI requests re-park without delivery; sent responses decode before delivery; act completion writes its recorded destination; tail completion restores one caller frame or halts. Link phase transitions preserve the payload. Explicit resume must handle the returned halted state.

   The de Bruijn precedent supports return-mode restoration without a running activation. It is not an identical validation precedent: that kernel also checks format, payload, and resume-value constraints. Exactly-once means one delivery per consumed entry or continuation invocation, not single-use reified continuations. The exception-cleanup guarantee needs the correction above.

3. **F2–F7 rulings.**

   - **F2: ACCEPT WITH CHANGES — frozen §2.4.** Empty-K deferred completion leaves `(without-store-of (:env base))`; immediate tail completion uses current E. Do not universally equate scheduler `base.env` with the originating tail site’s E: another continuation can have run meanwhile.
   - **F3: ACCEPT — frozen §2.1 and dependent §4.2 commentary.** Semantic K has one return-frame kind. Walker FFI frames correspond to protocol markers on semantic wait entries.
   - **F4: ACCEPT — frozen §4.1 notation.** Distinguish in-VM `:next`/`:put` markers from wire `:ffi`/`:ffi-request`.
   - **F5: ACCEPT WITH CHANGES — frozen §2.5 clarification and companion §4.** The frozen table already calls `:return` administrative. Clarify production versus relocation, without defining freshness by value novelty.
   - **F6: ACCEPT — frozen §10 phase 4.** Use `:yin.semantic-register/code`, contract `"v4"`, during coexistence. Retire it at phase 8; phase 5 supplies production linker scanners.
   - **F7: ACCEPT — frozen §2.4.** `put-active` routes the write and ultimately calls `store-put`; Rule R remains unchanged.

4. **The two questions.** Approve the proposed F6 spelling. Approve additive backend registration in the four existing suites as an explicit, narrow exception to the untouched-tests constraint. Existing backend entries and assertions must retain their behavior. Include the required Rule R backend selection change; four map insertions alone do not cover that row. Empty obligations remain a marked install-mechanics fixture, not evidence of dependency-scanner correctness.

5. **Machine and shapes.** The record, image, return frame, act/tail payloads, and delivery records provide the required coordinates. Repeating the engine/module/FFI/linker key search found no competing `:deliver`, `:window`, `:site`, or `(:pc entry)` use in those seams.

   Before the briefs, pin public loader arities/default options, reset’s window clearing, and the effect materialization step. Keep nil K and map-valued windows. The abbreviated κ delivery record must not become an alternative accepted shape.

6. **Walker trace.** The walker post-state criterion works for the selected non-blocking transitions. Six events for the cited lambda are correct. Conditional routing and closure entry are administrative; define produces a second event on both machines.

   The register criterion and extraction algorithm need the table’s corrections, especially for repeated values and abortive invocation. Blocked programs are intentionally outside this step trace; run-to-completion parity must cover their effects and resumption. Whole-trace equality with first-index diagnostics is sound once classification is fixed. Add a fuel bound so divergence becomes a diagnostic rather than a hanging test. The named corpora exist, with corrected counts.

7. **Slices and gates.** 4a can ship independently with explicit unsupported-operation deferrals. It does not complete the frozen phase-4 gate. Its current acceptance list cannot pass as written because of resume deferrals, counts, and closure expectations.

   Also qualify “arms write no other register” in item 12: arm expressions may write intermediate registers; their result definitions share rd. The 4b install tests can precede scanners using the declared stub restriction. Production linking and dependency completeness remain phase-5 obligations.

8. **Previously unread report.** Its seven accepted readings require no evaluator redesign. The `minted` fallback concerns malformed input and validator diagnostic ordering; admitted vectors cannot expose that fallback as an execution choice. Tail syntactic definitions, static unreachable liveness, whole-vector canonical checks, and derived image analysis remain compatible.

9. **Portability.** The listed host precautions are appropriate: reader-condition ordering, map-valued windows, `mapv`, preserving nil-valued keys, distinct protocol parameters, public test seams, and EDN formatting. Ensure trace extraction uses explicit destinations rather than host map iteration order. Apply the same portable exception handling to the new trace harness. Three-host execution remains an implementation gate, not evidence supplied by this design.

10. **Six invariants.** No architectural violation found: state and destinations are explicit; effects retain stream boundaries; windows are persistent values; lowering remains outside evaluation; CFG and liveness derive from admitted tuples; no global allocator or hidden scheduling state is introduced. Engine-local analysis remains derived data. The throw-cleanup wording must not imply mutation of a machine already held by its caller.

VERDICT: CHANGES_REQUESTED — fix trace classification/extraction; correct exception and stamp guarantees; repair corpus eligibility, counts, and normalization; specify effect materialization and helper calls; apply the qualified F1–F7 amendments and backend-registration exception.
