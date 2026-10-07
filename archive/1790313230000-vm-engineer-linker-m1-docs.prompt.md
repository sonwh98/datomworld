Created-GMT: 2026-09-25 05:25:00 GMT
Created-Local: 2026-09-25 12:25:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (linker M1 documentation round)

# Task: yin.vm.linker Milestone M1 — documentation half

Role: Docs / Scoped Subagent (ZCode subagent, GLM-5.3-Flash)

Repository: the worktree /Users/sto/workspace/datomworld-ucf-phase2
(branch ucf-phase2; the M1 rename is committed as f8d051b9).

The rename milestone's documentation half, per docs/design/yin.vm.linker.md
section 9 (lines ~1855-1861, read it first):

1. Update the five design documents that name the old namespace
   `yin.vm.debruijn-linker` — docs/design/yin.vm.debruijn.stack.md,
   docs/design/yin.vm.debruijn.register.md, docs/design/dao.agent.md,
   docs/design/dao.agent.harness.md, docs/design/dao.agent.mcp.server.md —
   replacing namespace/code references with `yin.vm.linker`. Grep each
   file for both spellings first; change only namespace/code references,
   NOT historical prose that legitimately discusses the old design era
   (when unsure, prefer precision: a reference to the module's code name
   updates; a sentence narrating design history stays).
2. Mark docs/design/yin.vm.debruijn.linker.md superseded in its status
   line (line 3): "Status: superseded by docs/design/yin.vm.linker.md
   (yin.vm.linker, M1 rename, 2026-09-25); design revised, not
   implemented" — adapt to the document's own header format, keeping the
   original status text's meaning (superseded, not deleted).
3. Do NOT touch docs/orchestrator-log.md (the orchestrator records the
   rename centrally) and do NOT touch any file under src/ or test/.

Constraints:
- Pure ASCII, <= 80 columns on every line you add or edit.
- Do NOT commit or stage; do NOT run git checkout/reset/stash.
- Verify: after your edits, grep the worktree's docs/design for
  `yin.vm.debruijn-linker` — remaining hits must be ONLY the superseded
  document's own self-references and any legitimately historical prose
  you deliberately kept (report the exact list and your reasoning for
  each kept hit).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
