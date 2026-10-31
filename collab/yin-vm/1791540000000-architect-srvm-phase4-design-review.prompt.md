Created-GMT: 2026-10-10 12:55:00 GMT
Created-Local: 2026-10-10 19:55:00 Asia/Bangkok

# Task: Architecture review of the phase-4 evaluator design (authored by claude-fable-5-1)

Role: Lead System Architect (continuing thread 01a121d7-5ba4-71b1-a70b-f6b4cf3d056a)

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-10 19:55 +07 | Status: active | Rationale: the design was authored by a Claude Architect delegate (fable-5-1) and committed by a Claude orchestrator; sign-off must come from a non-Claude family. Owner instruction, verbatim: "delegate to fable-5.1 to complete phase 4 design, have codex review the phase 4 design".

Perform a read-only review of
`docs/design/yin.vm.semantic-register-vm.evaluator.md` (1126 lines) at
commit `d9fe3ab9` on branch `srvm-phase4-design` (one commit on master
d21ee43f). You are working in the worktree
`/Users/sto/workspace/datomworld-srvm-p4-design`; its `collab/` holds the
author's report `collab/1791540000000-architect-srvm-phase4-design.report.md`
(read it first). The frozen design you signed off is
`docs/design/yin.vm.semantic-register-vm.md` and must stay frozen; the
author proposes SEVEN amendments (its §6, F1-F7), which are yours to
rule on, not the author's or the orchestrator's.

Read: the report; the evaluator design in full; the frozen design §2.1,
§2.4, §2.5, §3.4, §4.1, §4.2, §8.1, §10 phase 4, §11; and the engine
and VM code the design cites (`src/cljc/yin/vm/engine.cljc`,
`semantic.cljc`, `ast_walker.cljc`, `ffi.cljc`, `completion.cljc`,
`linker*.cljc`, the de Bruijn register kernel's `write-back`
`:return-result` path) plus the landed phase-3 code
`src/cljc/yin/vm/semantic_register/{code,analysis,linearize}.cljc`.

## Evaluate

1. **Grounding.** Spot-check at least 15 of the design's file:line
   references across all sections (shapes, restore seam, link, trace
   test) against the code at d21ee43f. Report each mismatch. The author
   states it did NOT run tests and derived some numbers by hand (the
   six-event count in 4a acceptance item 18; register numbers in items
   5-6, from `analysis_test.cljc:131-139` and the corpus goldens): verify
   those specifically.
2. **F1, the central finding.** The author claims the frozen design's
   delivery argument is wrong about the seam: restore happens in
   `engine/resume-from-run-queue` and `engine/resume-continuation`, not in
   `handle-effect` (cites `engine.cljc:2224-2256`, `:2299-2309`,
   `:2565-2683`), while the substantive claim (a `tail` completion needs
   no current activation) holds. Verify. Is the design's
   `register-restore` (twelve lines per the report) correct for every
   pending reason in the §2 table, including FFI retained-request
   re-park, bookkeeping removal before `ffi/call-result` can throw,
   exactly-once delivery, and the empty-K halt? Is the precedent it cites
   (the de Bruijn register kernel's `write-back` `:return-result`) actually
   equivalent?
3. **F2-F7.** For each: is the evidence correct, is the proposed text the
   right amendment, and should it amend the frozen design (and which
   section) or stay in this document? In particular F2 (the `tail`
   state's empty-K halt must leave an `:env`: the blocked machine's own,
   cleared of the module-store key), F3 (K has ONE frame kind; eval-call
   and request-sent are wait-entry markers, not frames), F5 (`:return r`
   administrative; a new row in §2.5), F6 (an interim link format keyword
   `:yin.semantic-register/code` "v4", retired at phase 8, because
   `:yin.semantic/code` is bound to the "v3" stack vector until
   cutover).
4. **The author's two questions to you:** the F6 keyword spelling; and
   whether slice 4b may add one entry to each of FOUR existing backend
   maps (`continuation_invoke_test`, `ffi_test`, `rule_r_test`,
   `linker_require_test`) instead of duplicating those suites -- weigh
   against the rule that phase 4 leaves the old VM and its existing tests
   untouched.
5. **Machine and shapes (§1).** Is every shape sufficient that an
   implementer needs no further shape decision? Any engine key collision
   (`:deliver :window :site`, `(:pc entry)` reads)? The author did that
   check by grep; redo it.
6. **The walker trace test (§4).** Is the algorithm precise (a walker
   step is value-producing iff its post-state has `:control nil` and is
   not blocked; a register step iff it writes a fresh value; `:return r`
   and `:halt r` administrative; `return(v)` of a fresh value an event)?
   Can it misalign (blocked/park/effect steps, tail calls, conditionals,
   `define`, effects that resume)? Is the mismatch rule sound? Is the
   named corpus real?
7. **Slices (§5).** Is 4a genuinely shippable alone (no engine waits)?
   Are both acceptance lists (21 and 26 items) mechanically checkable by
   a reviewer who has not read the design? Is anything gated in the
   wrong slice?
8. **The unread input.** The author could not read the phase-3
   implementation report (the sandbox blocked reads outside its
   worktree), which records seven ambiguity readings the Architect
   already accepted. Read
   `collab/1791530000000-vm-engineer-srvm-phase3.report.md` (copied into
   this worktree's `collab/` for you; the original is in the phase-3
   worktree) yourself and say whether any reading changes the evaluator design (for
   example `minted`'s nil fallback for a `:resume` whose parent names a
   non-register).
9. **Portability (§8)** against the repo's host traps.
10. **Invariants.** The six, against the evaluator design.

Distinguish defects (must fix before the design is accepted), gaps
(must be filled before the corresponding slice's implementation brief)
and notes. Do not edit files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then report: severity | file:line | evidence | recommended correction.
Then your answers to items 1-10, with an explicit RULING per finding F1-F7
(ACCEPT / ACCEPT WITH CHANGES / REJECT, and which document it amends).
End with one line: VERDICT: READY_TO_MERGE | CHANGES_REQUESTED, with the
changes in the order they should be applied.
