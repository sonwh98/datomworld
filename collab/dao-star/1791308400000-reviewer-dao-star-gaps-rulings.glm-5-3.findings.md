# Plan: collab/1791308400000-reviewer-dao-star-gaps-rulings.prompt.md

## Context

The orchestrator brief asks for a **read-only adversarial review** of the Architect's
rulings in `collab/1791307819601-architect-dao-star-gaps.claude-fable-5-1.findings.md`
against `docs/design/dao.star.md` (main tree, uncommitted) and the cited design docs.
"Verify, do not trust, the findings." Deliverable = a report beginning with a
Completed-GMT/Completed-Local/Coding-Agent/Session-ID header; no files may be edited.
Session was opened in plan mode; the task itself is read-only, so it is completed
entirely with read-only tools (direct Reads/greps of primary sources — no subagent
summaries, since citation verification demands primary text). The report is delivered
as the final message text (the orchestrator captures it to the stdout.log).

## Status: review complete — no implementation phase exists

Nothing to implement: the brief forbids edits. The full report is in the final
message. Summary of rulings:

1. **Citations**: every cited section exists and says what the Architect claims —
   but they live in `docs/design/yin.vm.universal-continuation-format.md`, not the
   `yin.vm.code-as-tuples.md` the brief (and the architect's brief) named. The one
   loose mapping: open question 4's "this is OD-4" (OD-4 is a logical stream's
   end-of-existence, not parked-continuation reclamation — adjacent, not the same).
2. **Paste-ready Gaps 1–5**: pass — no contradiction with the six invariants,
   P2P/no-privilege (`dao.stream.remote.md` §1), clock-free fetch + lease liveness
   (remote §2.5/§6, `dao.lease.md`), or apply-independent-of-rpc (`dao.stream.md` OD-5).
   Four minor wording notes (duration `{:ms 1 :s 1000}` units-table phrasing;
   "arrive on the holder's own medium" is a dao.star gate, not UCF §7.7; "parked
   occurrence" for never-exported Mode 1 parks; per-effect outcome coarsening).
3. **High defect items**: all four confirmed real (response head unspecified;
   custody asserted not obtained; migration back has no admission rule; no
   routing/ordering of requests).
4. **Missed defects**: two D-class (no first-load/document-serving story; fat
   client's local UI projection path unspecified) + reporting notes (verdict
   sentence says "four architectural defects" while the table has seven class-A
   rows; the blog's "waking" prose shares the locution flagged High at :21).
5. **Open questions**: Q1 largely answerable from the docs (version-0 fork exists
   today; exclusive needs v1 + transactor); Q2/Q5 partially (dao.shibi.md makes
   allow-list/pre-shared the only sanctioned interim gate); Q3/Q4/Q6 genuinely open.

## Verification (of this review itself)

Cross-checked every quote against: `dao.star.md` (all 51 lines), `datom.world.md`,
`yin.vm.universal-continuation-format.md` (§7.1, §7.2, §7.7.1–7.7.5, §7.7.8 excerpts,
§7.8, status block incl. r5/r6/r7), `dao.lease.md` (all), `dao.stream.md` (Invariants,
Result Convention, Creation/Attachment, Envelopes, Host Dispatch, Surfaces, Reading,
Writing, Retention and Gaps, Composition, Explicitly Absent, OD-4, OD-5),
`dao.stream.remote.md` (§1, §2.4, §2.5, §6, §7), `dao.stream.ws.md` (Deposit
Admission), `dao.space.security.md` (all), `dao.shibi.md` (all), and the blog post.
Next step after plan approval: none — report stands as delivered.
