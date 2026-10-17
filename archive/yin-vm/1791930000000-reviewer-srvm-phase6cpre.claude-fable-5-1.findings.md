Completed-GMT: 2026-10-11 08:47:28 GMT
Completed-Local: 2026-10-11 15:47:28 Asia/Bangkok

# Adversarial review: slice 6c-pre, the stack kernel's tail-completion seam

Reviewer: claude-fable-5-1 (non-author family). Author: codex gpt-6.1-sol.
Worktree `/Users/sto/workspace/datomworld-srvm-p6cpre`, branch `srvm-phase6cpre`,
HEAD 7f1cf08e, diff `git diff master...HEAD` (two files: `stack.cljc` modified,
`stack_tail_completion_test.cljc` added). Read-only: no tracked file was
changed; every mutant was applied to `stack.cljc` from a byte-verified backup
(`collab/6cpre-reviewer-stack.backup.cljc`, `cmp` identical to HEAD's file) and
restored after each run with `cmp` plus `git diff --quiet HEAD -- src/`. Final
state: `cmp` identical, `git diff --quiet HEAD -- src/ test/ docs/` clean.

Provenance of runs. This review was done in two sittings of the same reviewer
identity on 2026-10-11 (the first hit a session limit after its mutant sweep
had completed; its logs are `collab/6cpre-reviewer-mutant-01..18.log`,
summary `collab/6cpre-reviewer-mutants.log`, baseline
`collab/6cpre-reviewer-baseline.log`, 14:04-14:37 local). The second sitting
re-verified the backup and the clean tree, ran eight further mutants
(`collab/6cpre-reviewer-mutant2-01..08.log`, summary
`collab/6cpre-reviewer-mutants2.log`), the handoff probe
(`collab/6cpre-reviewer-handoff-repro.clj`, log
`collab/6cpre-reviewer-handoff-repro.log`), kondo and cljstyle. Every mutant
run was `clojure -M:test -n yin.vm.debruijn.stack-tail-completion-test -n
yin.vm.debruijn.stack-test -n yin.vm.debruijn.stack-effects-test -n
yin.vm.debruijn.stack-parity-test -n yin.vm.semantic-ffi-test -n
yin.vm.continuation-invoke-test` (baseline: 87 tests, 821 assertions, 0
failures, 0 errors, matching the orchestrator's count). Not run by me: Dart,
Node, the full regression lane.

## Findings

### F1 — MEDIUM | `src/cljc/yin/vm/ucf/handoff.cljc:898-923, 2088-2104, 2287-2290, 2963-2981` (not in the diff) | the version-2 handoff silently drops the new marker for the stack lane

Evidence. `stack-registers-v2` (lift) builds the wire record from a fixed key
set (`:yin.k/layout :image :pc :frames :stack :continuation [:store-of]`) and
never reads `:resume-mode`; `stack-payload-v2` and the `:stack` arm of the
lower (2963) never write it back; the `:stack` validator's `closed!` set
(2287) would refuse `:yin.k/resume-mode` if it were ever carried. The register
lane does carry it (978, 2137, 2314, 3007). Reproduced end to end with
`collab/6cpre-reviewer-handoff-repro.clj` (a `(let [s …] (let [c …]
(stream/next! c)))` whose inner application is `:tail? true`, stack kernel,
lift by `v2-support/lift`, lower by `handoff/resume-task`):

```
source blocked? true | site [:call 1 true] | resume-mode :return-result | K depth 2
export status :ok
resume status :ok
resumed entry keys (:continuation :cursor-ref :format :frames :hash :image :pc :reason :segment :stack :stream-id)
RESULT marker-before :return-result marker-after nil
```

Consequence. After a v2 handoff the same tail park resumes by the old push
path and runs the layout syntax after the call (the exact behaviour §4.3 calls
non-canonical), so one blocked task now delivers differently before and after
handoff. On master both sides agreed (both pushed at pc). The slice therefore
introduces an observable divergence across the one carrier that exists today.
Not a bug in `stack.cljc`; outside the brief's file scope (the brief said
`stack.cljc` only), so the author could not fix it, but the report does not
mention it (brief rule: record ambiguities you hit; §9 verification honesty).

Correction (one of): (a) carry `:yin.k/resume-mode` on the v2 stack lane as the
register lane does (lift at ~898, validator `closed!` optional set at ~2287
plus a `contains? #{:return-result}` check mirroring 2329, lower at ~2963 and
~2088), with a `handoff-v2-*` round-trip test on a tail park; or (b) an
Architect ruling that the v2 carrier is frozen and the window until the
canonical carrier of §4.3 lands (6c) is accepted, recorded in the design or the
report. My recommendation is (a): it is four small touch points and removes a
state-dependent semantic split. Either way the decision should be explicit
before merge.

### F2 — MEDIUM | `test/yin/vm/debruijn/stack_tail_completion_test.cljc:29-37` | the empty-K obligations are not tested in a way that can fail

Evidence. `empty-k-halts-with-result` is the only test with an empty `K`, and
it delivers `nil`. The parked entry's own stack is `[]` and it has no
`:store-of` (`registers` only adds one when the vm has it). So:

- Mutant M08 (`halt :value`/`:stack` taken from `(peek (:stack vm))` instead of
  the delivered value) SURVIVED all six suites; mutant N04 (seam-only: empty-K
  halt delivers constant `nil`) SURVIVED. The design's "halts with `[value]`"
  is asserted only for `value = nil`.
- Mutant M09 (empty-K halt keeps `:store-of`) SURVIVED; N05 (seam-only: keep
  the entry's `:store-of`) SURVIVED. The `(nil? (:store-of done))` assertion is
  vacuous because the entry's `:store-of` is already nil.

The report's sentence "cannot accidentally treat nil as missing delivery" is
the wrong lesson: delivering only nil is what lets a wrong-value halt survive.
The code itself is correct (`return-transition` halts with `[val]` and
`:store-of nil`); the gap is in the test.

Correction. Add one `stack-restore` case with `:continuation []`, a non-nil
`:store-of` on the entry (as `frame-entry` already does) and a non-nil value,
asserting `:stack [7]`, `(vm/value) 7`, `:halted? true`, `:store-of nil`; or
extend `empty-k-halts-with-result` to append `7` and keep the nil case too.

### F3 — LOW | `test/yin/vm/debruijn/stack_tail_completion_test.cljc:45-46, 50-55` | the inner frame's `:return-pc 4` coincides with the parked entry's `:pc 4`

Evidence. `parked-call` parks at pc 4 (`[:call 1 tail?]` is index 3) and
`frame-entry`'s innermost frame also has `:return-pc 4`, so the assertion
`(= 4 (:pc done))` in `return-pops-only-inner-frame` holds whether or not the
return transition sets the pc. Mutant N08 (seam-only: pc left at the entry pc
after the transition) was caught only by `tail-in-operand-position-skips-layout`,
and only as an error (the machine ran on from pc 10 with a popped `K`), not by
the test that names the obligation.

Correction. Use a `:return-pc` different from 4 (e.g. 2) on the inner frame.

### F4 — INFO | `src/cljc/yin/vm/debruijn/stack.cljc:581` | pre-existing `:halted? false` on restore is untested by the six suites

Mutant M18 (drop `:halted? false` from the restored registers) SURVIVED. This
line existed before the slice (the diff only moves it) and is outside the
seam; its own comment names the scenario (a driver resuming a halted machine
through `engine/resume-continuation`). Not a 6c-pre defect; noted so the
survivor count is honest. No change requested in this slice.

### F5 — INFO | report | mutant claim is true for the eight the author ran, not in general

The author's "No mutant survives" holds for the author's eight mutants (I did
not re-run them; their logs are `collab/6cpre-mutation-1..8.log`). Of my 26,
four survived (M08, M09, M18, N04, N05 counting seam-only variants: three
distinct weaknesses, F2 and F4). The report's acceptance list is the author's
own numbering (the design's 6c-pre paragraph is prose); that reading is
correct and stated plainly. Everything else in the report that I checked is
accurate: lane counts for the six JVM suites, the Dart NOT RUN with the exact
error, the fixture correction on the first run, the redundant-let cleanup, the
"constructed native-entry" caveat on the FFI tests.

## Answers 1-5

**1. The seam.** The marker is minted in one place, `park-entry-fns`
(`stack.cljc:594-597`), from the instruction at the *current* pc (`run-effect`
passes the pre-advance vm, so `(:pc vm)` is the call site) and only when that
instruction is `[:call _ true]`. All three builders (`:stream/put`,
`:stream/next`, `:module/require`) and the engine's `:stream/poll` fallback to
the `:stream/next` builder inherit it. `stack-restore` (`:582-584`) routes a
marked entry through `return-transition`, which is the former `:return` arm
verbatim (`:pc (:return-pc frame)`, `:frames (:frames frame)`, `:stack (conj
(subvec stack 0 (:stack-base frame)) val)`, `pop` K, `:store-of (:store-of
frame)`; empty K: `:halted? true :stack [val] :value val :store-of nil`). The
`:return` arm now calls the same function with `(peek stack)`: no divergence
between the seam and `:return`. The old park/restore path (`:584`) is
byte-equivalent to the removed code for unmarked entries (the `restored` map
plus the conj). Paths where a marked entry still executes the layout syntax:
only after a v2 handoff (F1). Non-tail parks: no marker (`non-tail-park-keeps-
successor`, mutant M02 killed also by `stack-effects-test`). Captured
continuations: `:current-continuation` and `:resume` build payloads with
`registers` directly (`:862, :868`), never marked, and a `:call` of a
continuation value goes through `stack-restore` with that unmarked payload, so
it still pushes at pc. Synchronous results: the non-blocked branch of
`run-effect` and the `:host-fn` plain-result branch are untouched; a tail host
call that returns synchronously still pushes and runs the following layout
syntax (pre-existing, not changed by the design's seam, which speaks of parks
only). The `:halt` opcode is unchanged (it keeps the whole stack; only
`:return`'s empty-K halt normalises to `[val]`, as before).

**2. Retained-FFI and response decoding order.** Preserved: the `:request-sent`
branch runs first and re-parks the *same* entry via `ffi/response-wait-entry`
(marker kept, mutant M12 killed), then `:call-id` removes the parked record and
decodes via `ffi/call-result`, and only then the marked/unmarked delivery
happens (M11, M13, M17 killed). Host-fn effects are the only real producers of
the marker. FFI parks (`:ffi-call`, `:644`) use `registers` directly and are
never marked; the instruction has no tail flag. This matches the register
kernel, whose `tail?` is also `(and (= :call op) (true? (nth instruction 4)))`
(`debruijn_register_effects.cljc:100`), so there is no asymmetry. The two FFI
seam tests are therefore synthetic entries (a tail `:call` entry with
`:request-sent`/`:call-id` added by hand); the report says so. The engine's
writer retry (`apply-put`, `engine.cljc:1919-1933`) re-wakes the entry object
itself, so the marker survives an in-process retry.

**3. Mutants.** See the table. 26 applied by me; 21 killed, 5 survived (two of
which, N04/N05, are seam-only restatements of M08/M09 to localise the gap).

**4. Scope.** `git diff master...HEAD --name-status`: `M stack.cljc`, `A
stack_tail_completion_test.cljc`; nothing else. No existing test touched, no
semantic-register or register-kernel edit, no design edit. kondo on both files:
0 errors, 0 warnings (one pre-existing info). cljstyle check: exit 0.
Portability by reading: the source change uses `nth` on a three-element
instruction vector guarded by `(= :call (first site))`, `true?`, `cond->`,
`subvec`, `peek`/`pop` on vectors; no reader conditionals, no private
cross-namespace access, no `for`, no float negation, no multi-key `assoc` on
nil. The test uses `tu/make-stream`, `tu/secret`, `apply2/success-response`,
`stream/append!`, vector literals; `(subvec (:stack entry) 0 1)` on a vector.
Nothing host-specific found; Dart remains unrun (author's lane NOT RUN,
orchestrator to rerun).

**5. Design readings and honesty.** The author read the 6c-pre paragraph as
prose and self-numbered the obligations; correct, and declared. The author
read "halt with the value when K is empty" as "assert with nil"; that is the
weak reading (F2). The author did not consider the existing v2 carrier for the
marked entry (F1); the design's carried shape in §4.3 is for the canonical
lowering, so this is a gap in both the brief and the design's slicing rather
than a misreading. Report claims I checked are honest (F5).

## Mutant table

Status is against the six JVM suites named above unless noted. "Named test"
lists the failing tests; TIMEOUT means the run hung past 900 s (an infinite
loop in the shared `:return` arm) and was killed by the driver.

| # | Mutation of `stack.cljc` | Status | Named test(s) | Log |
|---|---|---|---|---|
| M01 | marker condition → `false` (marker omitted) | KILLED | tail-park-is-marked, empty-k-halts-with-result, return-*, tail-in-operand-position-skips-layout, retained-ffi-reparks-before-return, ffi-response-decodes-before-return | reviewer-mutant-01 |
| M02 | marker on every `:call` (non-tail too) | KILLED | non-tail-park-keeps-successor; stack-effects-test/a-blocking-primitive-effect-parks-after-the-call-site-test | 02 |
| M03 | marker on every effect park | KILLED | non-tail-park-keeps-successor; stack-effects-test (2) | 03 |
| M04 | `return-transition` does not pop K | KILLED (TIMEOUT: `:return` loops) | none named before hang | 04 |
| N01 | seam-only: frame not popped | KILLED | return-pops-only-inner-frame | mutant2-01 |
| M05 | base not truncated (shared) | KILLED (TIMEOUT) | escape-discards-pending-frames-test, tail-position-invocation-test before hang | 05 |
| N02 | seam-only: base not truncated | KILLED | return-truncates-at-caller-base, tail-in-operand-position-skips-layout, ffi-response-decodes-before-return | mutant2-02 |
| M06 | truncated to the outermost frame's base | KILLED | return-truncates-at-caller-base, ffi-response-decodes-before-return, stack-test/re-entry-after-return-test | 06 |
| M07 | value not pushed (shared) | KILLED | 21 tests incl. return-truncates-at-caller-base, tail-in-operand-position-skips-layout | 07 |
| N03 | seam-only: value not pushed | KILLED | empty-k-halts-with-result, return-truncates-at-caller-base, tail-in-operand-position-skips-layout, ffi-response-decodes-before-return | mutant2-03 |
| M08 | empty-K halt value from `(peek stack)` instead of `val` | **SURVIVED** | — | 08 |
| N04 | seam-only: empty-K halt delivers constant nil | **SURVIVED** | — | mutant2-04 |
| M09 | empty-K halt keeps `:store-of` | **SURVIVED** | — | 09 |
| N05 | seam-only: empty-K halt keeps the entry's `:store-of` | **SURVIVED** | — | mutant2-05 |
| M10 | delivered twice (value conj'd again after the transition) | KILLED | empty-k-halts-with-result, return-truncates-at-caller-base, tail-in-operand-position-skips-layout, ffi-response-decodes-before-return | 10 |
| M11 | retained re-park skipped (`:request-sent` branch disabled) | KILLED | retained-ffi-reparks-before-return; semantic-ffi-test/full-request-is-sent-on-retry-and-answered-test | 11 |
| M12 | retained re-park drops the marker | KILLED | retained-ffi-reparks-before-return | 12 |
| M13 | response decoding skipped | KILLED | ffi-response-decodes-before-return; semantic-ffi-test (5) | 13 |
| M14 | marked entry resumes at pc by push (layout runs) | KILLED | tail-in-operand-position-skips-layout, empty-k-halts-with-result, return-* , ffi-response-decodes-before-return | 14 |
| M15 | caller frames not restored | KILLED | return-restores-caller-environment | 15 |
| M16 | site read at the advanced pc | KILLED | tail-park-is-marked and 7 more | 16 |
| M17 | parked FFI call not removed from `:parked` | KILLED | ffi-response-decodes-before-return; semantic-ffi-test (4) | 17 |
| M18 | `:halted? false` dropped from the restored registers (pre-existing line) | **SURVIVED** | — | 18 |
| N06 | seam delivers against `base`'s registers, not the entry's | KILLED | return-pops-only-inner-frame, return-truncates-at-caller-base, return-restores-caller-environment, ffi-response-decodes-before-return | mutant2-06 |
| N07 | `return-transition` keeps the vm's `:store-of` instead of the frame's | KILLED | return-restores-caller-environment | mutant2-07 |
| N08 | seam-only: pc left at the entry pc after the transition | KILLED (by error only) | tail-in-operand-position-skips-layout | mutant2-08 |

Comparison with the author: the author's eight mutants map onto M01, M02-ish
(halted false ≈ my M18 inverse), M04, M05, M15, M14, M11, M13; all of those are
killed here too. The author did not try a wrong halt value, a kept store
context, or a pc coincidence, which is where the survivors are.

## Verdict rationale

The `stack.cljc` change is correct, minimal and matches §4.3 and §8.3 6c-pre
line by line; non-tail and existing behaviour is unchanged. Merge is blocked
on two in-scope test corrections (F2, F3: the new namespace, ten lines) and
one explicit decision outside the file scope (F1: carry the marker on the v2
stack lane now, or record the accepted window). With F2 and F3 fixed and F1
decided, re-review can be a short confirmation.

VERDICT: CHANGES_REQUESTED
