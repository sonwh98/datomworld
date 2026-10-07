Created-GMT: 2026-09-09 16:53:38 GMT
Created-Local: 2026-09-09 23:53:38 +0700 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: 7762fd3e-1c6b-4e95-9e2d-2032a2209d5f (resumed)
# Task: dao.space.schema plan — r3, three wording corrections to D4
Role: Lead System Architect

**Both confirm rounds passed.** `gpt-6-astra`: "r2 is implementable… no
remaining implementation blocker." `deepseek-v4-pro`: "r2 is clean. All four
of my r1 findings are settled the right way." Findings:
`collab/1788972370436-review-space-schema-v2-plan-r2.gpt-6-astra.findings.md`,
`collab/1788972370436-adversarial-space-schema-v2-plan-r2.deepseek-v4-pro.findings.md`

What remains is **three sentence-level corrections, all in D4's paragraph that
goes verbatim into `dao.space.schema.md`.** Revise the plan in place at
`collab/1788962937302-architect-space-schema-v2-plan.claude-fable-5-1.findings.md`,
change nothing else, and change no other file. This is a wording round; do not
reopen a decision.

1. **The origin-cursor clause is wrong as written** (astra, P2; plan lines
   397-400). "…or the caller held an origin cursor across the read" — holding
   one is not evidence. A caller can retain an origin cursor unused, let the
   buffer overflow, call today's `query/snapshot`, and hand schema an
   undetected suffix. `query/snapshot` takes no caller-supplied cursor at all.
   Require the caller to have **used** the origin cursor to establish that the
   supplied history has no lost prefix, and state that today's
   `query/snapshot` does not do that. Keep the deferral of a
   `snapshot-from` variant; astra agrees deferring is reasonable.

   In the paragraph that follows ("Why not fix it in `query/snapshot`"),
   avoid calling a cursor-taking snapshot a retention predicate: observing a
   `gap` through a supplied cursor is ordinary protocol use, not
   interrogating configuration. The reason to defer it is that it is query's
   API to add, not that it would be a contract violation.

2. **"Detectable by the composition" overstates** (deepseek). It is
   *knowable at wiring time*, not detectable at read time — nothing in the
   system catches the mis-wiring: no status field, no assert, no type check.
   That is the same deliberately-unchecked position as T18. Say "knowable."

3. **Record the one real softening versus T18** (deepseek). T18's declaration
   point is a single local wiring; here the snapshot value **strips
   provenance** — a `:blocked` snapshot carries `:status` and nothing about
   retention — so a caller at a distance from the stream's creation cannot
   make the declaration knowledgeably. §7 already defers the provenance/origin-cursor
   variant to query's plan, which is the right home; add the sentence naming
   the softening so the deferral reads as bounded rather than unnoticed.

Also worth one line, for the implementer rather than the design doc:
**V15 is a cross-layer pin.** Its teeth come partly from pinning
`query/snapshot`'s `:blocked` status, so a query-side refactor that changes
the status while leaving schema's limit intact would also trip it. Both
reviewers judge that acceptable — it forces the re-examination the pin wants —
but the implementer should know it before treating a V15 failure as a schema
defect.

Nothing else in the plan changes. Update the header block.
