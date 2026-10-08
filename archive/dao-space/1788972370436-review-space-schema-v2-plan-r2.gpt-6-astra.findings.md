Completed-GMT: 2026-09-09 16:53:15 GMT
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574
Role: Routine Review (confirm round on r2) | Model: gpt-6-astra

I’ll check r2 against the four findings, including the proposed design wording, test assertions, and phase arithmetic. I’ll keep this read-only.

**R2 is implementable. The four original findings are substantively discharged, with one narrow D4 wording correction still needed before copying it into the design.**

**[P2 — documentation] D4: holding an origin cursor is not itself evidence of completeness.** At [lines 397–405](/Users/sto/workspace/datomworld/collab/1788962937302-architect-space-schema-v2-plan.claude-fable-5-1.findings.md:397), the declaration permits “the caller held an origin cursor across the read.” A caller can retain that cursor unused, overflow the buffer, and call today’s `query/snapshot`; the result is still an undetected suffix.

Change that clause to require the caller to **use the origin cursor to establish that the supplied history has no lost prefix**, not merely hold it. State that today’s `query/snapshot` does not use a caller-held cursor. The following paragraph should also avoid calling a cursor-taking snapshot a retention predicate: observing `gap` through a supplied cursor is ordinary protocol use. Deferring that API remains reasonable; no implementation expansion is required.

Otherwise:

- **D4’s limit is honestly stated.** §5 explicitly requires the guarantee, failure detection, caller responsibility, and undetectable-prefix limit to appear in `dao.space.schema.md` itself. After the wording correction above, that document can stand alone.
- **V15 is a real executable pin.** With capacity four, the surviving rows are the last two `:person/friends` declarations and the two name values. The `:person/name` declaration is gone. Asserting both `:blocked` and `#{["old"] ["new"]}` demonstrates the limit through observable behavior.
- **D1 ordering is corrected.** Empty-input validation precedes the lock; closedness precedes remaining validation. The new test compares wrapper and inner behavior directly.
- **Ownership tests are no longer vacuous.** The direct inner transaction catches omitted inner close. V14’s zero-before/one-after close counter catches premature store closure, while subsequent queries pin self-containment.
- **The `finally` scope and P4 test citations are fixed.**

The arithmetic now closes:

| Check | Phase 1 | Phase 2 |
|---|---:|---:|
| Source `ds/` lines | `16 − 2 = 14` | `14 − 14 = 0` |
| Test `ds/` lines | `68 − 60 − 1 − 1 = 6` | `6 − 6 = 0` |
| Deftests | `70 − 1 + 3 = 72` | `72 − 4 + 4 = 72` |

Receipt accounting is **24 − 2 deleted = 22 rewritten**, including both T19 assertions. Overall: seven additions, five deletions, one rename. The revised dependency grep permits the required receipt keywords.

No remaining implementation blocker found. Read-only review; no files written.
