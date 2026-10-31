Created-GMT: 2026-09-04 12:43:25 GMT
Created-Local: 2026-09-04 19:43:25 Asia/Ho_Chi_Minh
Coding-Agent: agy
Session-ID: e671c7ca-f04c-4750-95ab-1178f25ba4bc

# Task: Review the collab archiving rule in orchestrator.md

Role: Routine Review

Implementers:
- Model: gemini-3.1-pro-high | Round: r11 | Assigned: see Created-Local above | Status: active | Rationale: Resumed; holds the full context of this session's artifact and commit discipline.

Answer directly. No plan artifact, no approval request.

Review `git diff docs/agents/team/orchestrator.md` (uncommitted, +6/-1). It
expands Core Responsibility 7 ("Preserve Audit Trail") from one line into five
sub-bullets documenting how `collab/` artifacts are archived.

WHY HERE. The user states that collab files are moved to an archive
periodically, especially after a commit, and asked for it documented in
orchestrator.md because the Orchestrator is the role that usually commits.
Responsibility 7 previously said only "without deleting or purging them", which
reads as "nothing ever leaves `collab/`" — so the archiving practice looked
forbidden by the very rule meant to protect the trail.

OBSERVED FACTS, established by inspecting the repository, not assumed:
- `archive/` exists at the repository root, is flat (no subdirectories), and
  held 204 files before this sweep.
- It is gitignored at `.gitignore:55`; `git ls-files archive/` returns 0, so it
  is untracked and never committed.
- Its files carry the exact `collab/` names, including the
  `<task>.<model>.findings.md` / `.stdout.log` / `.prompt.md` forms.
- A sweep this session moved 96 files; 204 + 96 = 300 confirmed afterwards, with
  a pre-checked collision count of 0 and `mv -n`.
- Neither `TEAM.md` nor `orchestrator.md` mentioned archiving anywhere before
  this change (`grep -i archiv` found nothing).

Assess:
1. Accuracy against those facts, and whether anything is stated more strongly
   than observed. Note the "periodically, especially after a commit" cadence is
   the user's stated practice, not something measured.
2. Is orchestrator.md the right home, or does this belong in TEAM.md's
   "Artifacts and sessions" section, which currently carries the `collab/`
   naming and append-only rules? Consider Core Responsibility 12, which forbids
   duplicating TEAM.md in a role brief. If it belongs in TEAM.md instead, or in
   both with one pointing at the other, say so.
3. Does the new text contradict TEAM.md's "`collab/` is append-only, never
   staged/committed; never delete or truncate prompts/findings"? Is the
   move-not-deletion distinction drawn clearly enough to prevent a future
   orchestrator reading archiving as a purge?
4. Are the two safety conditions — collision check, and archive only committed
   work — correct and sufficient, or is a guard missing?

Do not edit files. Do not run commands.

Begin your response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Ho_Chi_Minh>
Coding-Agent: agy
Session-ID: e671c7ca-f04c-4750-95ab-1178f25ba4bc

Then the severity-ranked table, then a final line reading exactly
`SIGN-OFF: GRANTED` or `SIGN-OFF: WITHHELD`.
