Created-GMT: 2026-10-16 22:40:00 GMT
Coding-Agent: claude (orchestrator, semantic-register-vm track)
# DRAFT entries for docs/agents/routing-status.md (append under "Current status"); NOT committed; owner approves first
Times are approximate where the owner relayed them without a clock time; verify before relying.

### 2026-10-15 — owner relay: GLM credits to be spent before the 2026-10-16 03:14 reset; then "use GLM until it runs out"
Owner, verbatim: "GLM has credits left and will reset 2026-10-16 03:14 so make use of GLM credits so it doesn't go to waste", then "actually use GLM until it runs out of credit". Apply: route reviews and implementation to glm-5.3 (`MODEL=glm-5.3 glm --session-id <uuid> --name <t> --permission-mode acceptEdits -p ...`) until it reports a limit; fall back to codex. Outcome: glm-5.3 hit its weekly limit (HTTP 429, "limit resets 2026-10-16 03:14:01") in the middle of the 8g documentation draft (about 23:15 +07 on 2026-10-15); unavailable until that reset. Re-verify after the reset with a trivial prompt.

### 2026-10-15 — codex usage limit and return
codex (gpt-6.1-sol) hit its usage limit mid 8f-2b (about 21:10 +07; its message said it retries at 6:17 PM, timezone unclear); owner relay "codex is back" the same evening. Apply: codex is the default implementer again; interim work went to glm-5.3. Lesson: a codex run killed by a usage limit leaves a partial uncommitted diff and no report: audit the worktree before re-dispatching.

### 2026-10-16 — owner standing rulings relevant to routing and merging
Owner, verbatim: "if an architect has reviewed and signed off, you can merge without my explicit permission" (merge to master on an Architect sign-off of the exact head, lanes green); "go ahead with the worktree cleanup" and "delete the merged remote branches after muse rebases" (merged branches and worktrees of the orchestrator's own slices: delete with that sign; others' stay). Seat: agy out of credits, the yang.python/C4 seat delegated to muse (see 2026-10-16 02:11 entry).
