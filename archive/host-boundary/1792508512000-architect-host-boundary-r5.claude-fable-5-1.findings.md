Created-GMT: 2026-10-20 15:10:00 GMT
Created-Local: 2026-10-20 22:10:00 +07
Coding-Agent: claude
Model: claude-fable-5-1
Session-ID: 36134bec-9cbd-4852-b78c-4901af66fd02
Task: host-boundary design r5, apply astra's mechanical corrections M1-M4
Role: Architect
Deliverable: docs/design/dao.stream.host-boundary.md edited in place (r5); no src edits, nothing staged.
Labels: V verified by reading. No lane run. Astra's SIGNED OFF WITH CHANGES covers §2 plus H0-H4 only; the M1-M4 text should be confirmed by a non-Claude reviewer before H0 is dispatched.

# Rationale

Each correction was verified before editing. M2 is real: a literal grep over
`src/cljc` for the H2 class names matches six files, including the docstring
at `dao/data.cljc:54` and the out-of-scope `dao/postgraphics/lowering.cljc`
(`rgba8unorm`); the gate is now a reader-level scan of closure-tool-tagged
files (§8.3), with codec files unscanned and scan errors distinct from "no
hits". M1 splits ownership transfer (a fresh result gives the caller the
exclusive mutation right) from freezing publication (append, nesting, seam
handoff, content handle); the four obligations read unchanged. M3 marks the
seven `dao.host.*` files as planned additions outside the H0 baseline, lists
the Dart Jing writer under migration sources rather than seams, and
subtracts both deleted writers from the final list. M4 reconciles the
counts (16 edited, 3 deleted), drops `aset` from the behaviour changes (two,
not three, in §6, §10 and §14), updates the appendix totals to 142/140/141
with 133 shared, and the header to r5 with the sign-off line. Scope is
unchanged; OQ7 and OQ8 stay open.

Note: the document was being edited concurrently by another session while
this round ran; the final text was re-read and reconciled (no duplicate
paragraphs, one H3 cell).

# Disposition

- M1: accepted; §2.4 bullets "Exclusive mutation right, ownership transfer, and freezing publication" (lines 226-244).
- M2: accepted; new §8.3 (lines 801-819), H2 and H3 acceptance cells (lines 714-734), §6 (lines 665-671).
- M3: accepted; §8.2 intro (lines 746-753), profile and seams rows, first row, "Baseline versus additions and deletions" paragraph (lines 789-800).
- M4: accepted; §10 cost and risk (lines 927-945), §14 (line 1113), appendix closure row, header (lines 13-21).
