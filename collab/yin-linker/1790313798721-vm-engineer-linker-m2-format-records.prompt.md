Created-GMT: 2026-09-25 05:50:00 GMT
Created-Local: 2026-09-25 12:50:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (linker M2 format records)

# Task: yin.vm.linker Milestone M2 — Four Format Records

Role: VM Runtime Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: the worktree /Users/sto/workspace/datomworld-ucf-phase2
(branch ucf-phase2 @ 96657a4f; M1 rename + documentation are committed).

Implement Milestone M2 exactly as specified in
docs/design/yin.vm.linker.md. Read first, in this order:
- Section 9's M2 description (~line 1863) — the milestone contract.
- Section 5 (Format records for the four backends) — the design being
  implemented.
- Section 3 (Identities: three kinds, one address space) — identity
  semantics the records must express.
- Section 10 file box: M2 edits src/cljc/yin/vm/content.cljc (retire
  load-rows, fetch-vector) per the spec.
- Section 2 (governing invariants) and the section 10 Must-not-change
  list (absolute): image-hash, register-hash, segment-key, opcode
  tables, dao.stream, dao.jing; no kernel step function changes.
- Section 11 completion criteria — the ones M2 can satisfy must hold
  when you finish (e.g. the contract-mismatch refusal, identity
  mismatch refusal under every registered algorithm).

M2 contract (from section 9): replace `:hash-fn` with `:identity-fn`
and introduce the four format records (ast, semantic, stack, register)
as identity-directed matches with bounded parts, per section 5's exact
record shapes. One `fetch`/`verify` surface serving all four formats
with no format-specific branching outside the records. Extend the
existing test suite (test/yin/vm/linker_test.cljc) with the M2
behaviors the spec names; add no new source files beyond what the file
box allows.

Constraints:
- Pure ASCII, <= 80 columns on every line you add or edit.
- Do NOT commit or stage; do NOT run git checkout/reset/stash.
- JVM tooling under mise (mise exec -- <cmd>).
- Verify: full JVM suite in the worktree (mise exec -- clojure -M:test)
  green, 0 failures — counts may grow with new M2 tests, but no
  pre-existing test may fail or lose assertions. Report exact counts and
  name every pre-existing behavior you had to touch, if any.
- cljstyle check on touched files; kondo clean.
- If the spec's M2 description conflicts with section 5's record shapes
  or anything is ambiguous, STOP and report BLOCKED with the exact
  conflict — do not improvise design.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
