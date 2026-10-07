Created-GMT: 2026-09-07 10:29:55 GMT
Created-Local: 2026-09-07 17:29:55 +07 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a0776c-dbcf-7343-a6de-ef18f705fec7
# Task: dao.jing invariants — completeness against the tests being deleted
Role: Routine Reviewer (correctness, invariants, portability)
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-07 17:29:55 +07 | Status: active | Rationale: resumed session; same subsystem, GPT family independent of the Claude author, and cold to the invariants list

**This is not a plan review.** It is one mechanical question with high stakes.

## The situation

`docs/design/dao.jing.implementation-plan.md` (revision 9) rebuilds `dao.jing`
on `dao.stream`. The project has nothing in production, so the old
implementation and its tests carry no authority and **will be deleted**, not
ported. The plan's contract is instead an explicit list of 41 invariants in
groups A-G, each marked with its source:

- **[D]** stated in `dao.jing.md`
- **[T]** pinned by an existing test
- **[T→D]** pinned by a test, not stated in the design, judged a real
  invariant that the plan adds to `dao.jing.md`
- **[T✗]** pinned by a test, judged an accident of the old implementation,
  and dropped

The risk this creates: several requirements have only ever been written down
as test code. If an invariant sits in those tests and did not make it into the
list, it disappears when the tests are deleted, and **nothing downstream will
ever catch it** — the new tests are written from the list.

## The question, and only this question

**Walk all 54 `deftest` forms in the three files being replaced and map each
to an invariant.** Report every test that maps to nothing.

- `test/dao/jing_test.cljc` — 31
- `test/dao/jing/mem_test.cljc` — 11
- `test/dao/jing/file_test.cljc` — 12

For each unmapped test, say what it pins and judge it: a real invariant the
list is missing, or an accident the plan is right to drop silently. Where a
test maps to an invariant only partially — the invariant is weaker than the
test — say so; a weakened invariant loses coverage just as a missing one does.

Then two secondary checks:

1. **The four `[T✗]` drops are right.** They are B7 (the
   `:dao.jing/content-missing` keyword as a legal payload), D4 (the handle's
   `:state` atom shape and the absence of a `:stream` key), E7 (strict `A B A
   B` interleave of two ready members), F7 (the 4-byte big-endian length
   prefix, the `pr-str` framing, the `:log` and `:write-lock` keys). For each:
   does dropping it lose a property that matters, or is the plan right that it
   is implementation rather than requirement?
2. **The `[T→D]` promotions are genuinely absent from `dao.jing.md`.** They
   are A3 (distinct values address distinctly), A4 (minted addresses are
   readable EDN), B5 (`nil` is a legal payload distinct from absence), B6 (a
   backend validates address-against-payload before it writes), D3 (close is
   idempotent and every entry point throws after it), F6 (the same for the
   file backend). If any is in fact stated in the design, the marking is wrong
   and should be `[D]`.

Read: `docs/design/dao.jing.implementation-plan.md` (the invariants section
and Decision 1), `docs/design/dao.jing.md`, and the three test files.

## Out of scope

Do not review the plan's decisions, its phasing, the shared observation core,
the deferred remote store, or its prose. Do not re-open Decision 2, on which
your dissent is recorded and closed. If something outside this question looks
wrong, note it in one line at the end and move on.

Report as a table or list: `test name | invariant | verdict`, with verdicts
`mapped`, `partially mapped (what is lost)`, or `UNMAPPED (what it pins, and
whether it matters)`. Then the two secondary checks. Then one line: is the
list complete enough to delete the old tests against.

You have no authority to run tests or edit files. Produce the complete
response in this run.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0776c-dbcf-7343-a6de-ef18f705fec7
