Created-GMT: 2026-09-04 12:34:49 GMT
Created-Local: 2026-09-04 19:34:49 Asia/Ho_Chi_Minh
Coding-Agent: agy
Session-ID: e671c7ca-f04c-4750-95ab-1178f25ba4bc

# Task: Review the GLM PTY correction in TEAM.md

Role: Routine Review

Implementers:
- Model: gemini-3.1-pro-high | Round: r9 | Assigned: 2026-09-04 19:34:49 Asia/Ho_Chi_Minh | Status: active | Rationale: Resumed; independent of both the Claude author and the GLM subject of the correction.

Answer directly. No plan artifact, no approval request.

Review `git diff docs/agents/team/TEAM.md` (uncommitted, 3 lines changed).

BACKGROUND. TEAM.md's GLM recipe was `GLM_MODEL=glm-5.3 script -q /dev/null
~/.local/bin/glm ...` commented "(PTY; keep -p last; do not redirect stdin)".
The `script` wrapper failed in this non-TTY caller with `tcgetattr/ioctl:
Operation not supported on socket`, so the earlier round worked around it by
allocating a PTY through Python's `pty.spawn` and recorded the PTY requirement
as an open, unverified caveat rather than editing the doc.

The user then supplied the missing fact: `glm` is a Claude Code-based CLI (as
TEAM.md itself groups it, with `claude`, `deepseek` and `muse`), and they have
used it successfully. Claude Code's `-p` mode needs no TTY.

EVIDENCE, both run by the orchestrator in a non-TTY shell with no `script`:
- `GLM_MODEL=glm-5.3 ~/.local/bin/glm --bare --permission-mode plan
  --allowed-tools Read --output-format text -p "..."` returned the requested
  token `PTYLESS-OK-7731`. No PTY was involved.
- The same command with `< /dev/null` returned `STDIN-CLOSED-OK-4412`,
  contradicting "do not redirect stdin".
Both emitted the expected `claude-code:unrecognized_model` warning, which the
pitfalls paragraph already documents and which corroborates the Claude Code
basis.

THE CHANGE: drops `script -q /dev/null` from both GLM invocations, recomments
them "(Claude Code-based; keep -p last)", and annotates the Muse recipe as
"(PTY unverified since GLM's proved unnecessary; keep -p last)".

Assess:
1. Is dropping the PTY justified by this evidence, and is "keep -p last"
   correctly retained as untested rather than also dropped?
2. Muse uses the identical wrapper and is also Claude Code-based, so the same is
   probably true of it — but it was NOT probed, because Muse is metered and
   TEAM.md says to reserve it for work worth the cost. Is annotating it as
   unverified the right call, or should the doc either probe-and-fix it or say
   nothing at all? Argue for one.
3. Does removing "do not redirect stdin" lose a real constraint that the two
   probes would not have exposed?
4. Wording and any inconsistency left elsewhere in the file.

Do not edit files. Do not run commands.

Begin your response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Ho_Chi_Minh>
Coding-Agent: agy
Session-ID: e671c7ca-f04c-4750-95ab-1178f25ba4bc

Then the severity-ranked table, then a final line reading exactly
`SIGN-OFF: GRANTED` or `SIGN-OFF: WITHHELD`.
