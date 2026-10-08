<!-- Promoted from collab/reviewer-team-glm-pty-correction-r9.gemini-3.1-pro-high.stdout.log.
     Conversation e671c7ca-f04c-4750-95ab-1178f25ba4bc (resumed). -->

Completed-GMT: 2026-09-04 12:38:00 GMT
Completed-Local: 2026-09-04 19:38:00 Asia/Ho_Chi_Minh
Coding-Agent: agy
Session-ID: e671c7ca-f04c-4750-95ab-1178f25ba4bc

1. **GLM PTY removal**: Dropping the `script` PTY wrapper for GLM is fully justified by the explicit `PTYLESS-OK-7731` evidence. Retaining "keep -p last" is also correct; the orchestrator didn't test argument parsing brittleness, so leaving the unverified constraint intact strictly adheres to the team's evidence discipline.
2. **Muse annotation**: Annotating Muse as unverified is the best call. It perfectly balances the repository's cost policy (don't waste metered tokens on docs tests) with its evidence policy (don't assert a command works until you run it). By stating the limit of its evidence, it safely guides a future orchestrator who actually has the budget to use Muse.
3. **GLM stdin constraint**: Removing "do not redirect stdin" for GLM is safe because the `STDIN-CLOSED-OK-4412` probe proved the wrapper handles closed input gracefully when operating in headless `-p` mode.
4. **Inconsistencies**: The amendment incorrectly drops the "do not redirect stdin" warning from the *Muse* comment. Since Muse was not probed, and it still relies on the `script` command (which manages TTYs and interacts poorly with redirected input), dropping the warning without evidence is an unchecked assertion that risks breaking the command.

| Severity | File:Line | Evidence | Correction |
| :--- | :--- | :--- | :--- |
| Medium | `docs/agents/team/TEAM.md`:256 | The "do not redirect stdin" warning was removed from the Muse comment without probing Muse. Because the command still uses the `script` wrapper—which is the likely reason stdin redirection was forbidden in the first place—this risks breaking the fallback. | Restore "; do not redirect stdin" to the Muse comment. It must remain until Muse is probed and its `script` wrapper is proven unnecessary. |

SIGN-OFF: WITHHELD

