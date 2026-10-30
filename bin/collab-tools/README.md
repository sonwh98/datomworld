# collab-tools

Orchestrator helpers for the `collab/` workflow described in
[`docs/agents/roles/orchestrator.md`](../../docs/agents/roles/orchestrator.md) and
[`docs/agents/delegate-invocation-reference.md`](../../docs/agents/delegate-invocation-reference.md).
They hold no policy: roles, routing, and review rules stay in `docs/agents/`.

## `dispatch.sh <engineer|reviewer|architect> <cli> <model> <worktree> <prompt-file> [resume-session-id]`

Runs one delegate (`cli`: `claude`, or a wrapper: `glm`, `deepseek`, `muse`, `claude-cloud`, run as
`env MODEL=<model> <cli>`). Engineers get `--permission-mode acceptEdits`; reviewers and architects get
`plan` with the canonical `--allowed-tools Read "Bash(git diff *)" "Bash(git status *)"`. A new run needs a
`Session-ID: <uuid>` line in the prompt header (generate and record it first); a resume passes the id as the
last argument. The response goes to `<prompt-stem>.stdout.log`, or the next free `<stem>-r<n>.stdout.log`
if that exists, so no log is ever overwritten. Run it with the harness's background mode; never append `&`.

## `rverify.sh <host> <scratch-clone> <git-ref> <name> <files-list|-> <cmd>...`

Applies the uncommitted files named in `<files-list>` (repo-relative paths, one per line, shipped as a tar)
over `<git-ref>` in `~/ci/<scratch-clone>` on `<host>` and runs each command under the mise environment in
tmux session `<name>`. Results land in `~/ci/<name>-logs/` (`rc`, `cmdN.log`, `head`, `DONE`). A setup
failure is written to `rc` as `setup FAILED: ...` and no command runs. The clone is locked while the job runs,
so use one scratch clone per concurrent job; a previous overlay is removed before the next checkout. If a job is killed hard (SIGKILL, reboot) the lock directory `~/ci/<scratch-clone>.lock` is left behind: remove it by hand after checking nothing is running.
`rverify.sh poll <host> <name>` prints `STATE=RUNNING|DONE|NOT-STARTED-OR-DIED` and the `rc` lines.
Host conventions: [`docs/agents/build-n-test.md`](../../docs/agents/build-n-test.md) ("Remote agent
execution"); run one Dart lane per host.
