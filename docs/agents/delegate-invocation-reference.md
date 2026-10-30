---
description: Session-ID mechanics, CLI flags, canonical recipes, and known quirks for invoking delegate agents (claude, claude-cloud, glm, deepseek, codex, agy, kimi)
---

# DELEGATE INVOCATION REFERENCE

Read this before shelling out to any delegate CLI — it is reference material
needed at the moment of dispatch, not on every orchestrator turn, which is
why it lives here rather than inline in
[`orchestrator.md`](roles/orchestrator.md).

## Session continuity

Agents preserve conversational context only when a follow-up resumes the
exact session, conversation, or thread ID — reading earlier prompts, logs,
findings, and diffs reconstructs task context but does not resume the session.

Before the first invocation of a Claude Code-based CLI (`claude`, `claude-cloud`,
`glm`, or `deepseek`), generate and record an ID:

```sh
TASK_SESSION_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
```

Write that value into the prompt's `Session-ID:` field, then invoke with
`--session-id "$TASK_SESSION_ID"`. For every correction, clarification, or
verification by the same agent, use `--resume "$TASK_SESSION_ID"`; never
start a new named session or combine `--session-id` with `--resume`.

Claude Code session persistence is enabled by default. Never pass
`--no-session-persistence` when work may need review, correction, or
follow-up. A process ID, `--name`, log filename, or wrapper name is never a
session ID; if a run failed to record its own, recover it from the
provider's session store (Claude Code: match the custom title in the
project JSONL within its configuration store — see below for AGY's
equivalent), record an append-only provenance correction, and resume it.

Codex, AGY, Command Code, and Kimi Code don't accept a caller-selected ID for
a new run in the installed versions — capture the generated ID immediately.
Codex, AGY, and Command Code expose it via structured output:

```sh
# Codex JSONL: first thread.started event
TASK_SESSION_ID="$(jq -r 'select(.type == "thread.started") | .thread_id' \
  collab/<timestamp>-<role>-<task>.<model>.stdout.log | head -1)"

# AGY JSON: top-level conversation_id
TASK_SESSION_ID="$(jq -r '.conversation_id' \
  collab/<timestamp>-<role>-<task>.<model>.stdout.log)"

# Command Code NDJSON: final result (also present on event.run_start)
TASK_SESSION_ID="$(jq -r 'select(.type == "result") | .sessionId' \
  collab/<timestamp>-<role>-<task>.<model>.stdout.log | tail -1)"
```

For Kimi Code, record the new session's exact ID from
`~/.kimi-code/session_index.jsonl` (or `$KIMI_CODE_HOME/session_index.jsonl`).
Sessions are grouped by working directory under `~/.kimi-code/sessions/`.

Fail the handoff if the extracted value is empty or `null`. Never use Codex
`--ephemeral`, Command Code `--no-session`, or any provider's non-persistent
mode for follow-up-capable work; never substitute `--last`, `--continue`,
AGY `-c`, or a display name when an exact ID is available. Give every resumed
turn a new prompt/artifact name (e.g. `<task>-r2...`) — never redirect a
follow-up into the prior append-only log.

Reviewer conversations are the ones most worth resuming: route a later review
of the same subsystem back into its existing conversation whenever that
reviewer's family stays independent of the new change's author, and start a
fresh conversation only when independence or subject changes.

AGY names each store directory after its conversation ID, so a lost one is
recovered by grepping the task name in its own brief:

```sh
grep -l "<task>" ~/.gemini/antigravity-cli/brain/*/.system_generated/logs/transcript.jsonl \
  | sed 's|.*/brain/||; s|/.system_generated.*||'
```

+--------------+-----------------------------+----------------------------------+------------------------------------------+
| CLI          | Session store               | New-session ID source            | Related follow-up                        |
+==============+=============================+==================================+==========================================+
| agy          | `~/.gemini/antigravity-cli` | JSON `conversation_id`           | `--conversation <id>`                    |
+--------------+-----------------------------+----------------------------------+------------------------------------------+
| claude       | `~/.claude`                 | caller UUID via `--session-id`   | `--resume <uuid>`                        |
+--------------+-----------------------------+----------------------------------+------------------------------------------+
| claude-cloud | `~/.claude`                 | caller UUID via `--session-id`   | `--resume <uuid>`                        |
+--------------+-----------------------------+----------------------------------+------------------------------------------+
+--------------+-----------------------------+----------------------------------+------------------------------------------+
| codex        | `~/.codex`                  | JSONL `thread.started.thread_id` | `codex exec resume <id> --json -`        |
+--------------+-----------------------------+----------------------------------+------------------------------------------+
| deepseek     | `~/.claude-deepseek`        | caller UUID via `--session-id`   | `--resume <uuid>`                        |
+--------------+-----------------------------+----------------------------------+------------------------------------------+
| glm          | `~/.claude-glm`             | caller UUID via `--session-id`   | `--resume <uuid>`                        |
+--------------+-----------------------------+----------------------------------+------------------------------------------+
| kimi         | `~/.kimi-code`              | `session_index.jsonl`            | `--session <id>`                         |
+--------------+-----------------------------+----------------------------------+------------------------------------------+
+--------------+-----------------------------+----------------------------------+------------------------------------------+

> [!IMPORTANT]
> **Don't probe before delegating.** Never run throwaway commands (`echo ok`, `--version`, `--help`) to
> verify a delegate CLI before invoking it — every flag and pattern below is canonical and pre-verified.
> Document the exact tool call here and invoke directly, capturing output into `collab/`.

> [!IMPORTANT]
> **Never double-background a delegate dispatch.** When the host you're operating through already tracks a
> command to its real completion (a task queue, a `WaitMsBeforeAsync`-style parameter, a `run_in_background`
> flag), pass the bare foreground delegate command and let that tracking do its job. Do not also append `&`,
> `nohup`, or a manually captured PID inside the command string — that backgrounds the delegate a second
> time from inside the shell the host is tracking, so the *tracked* process becomes the launcher shell, which
> exits almost immediately, while the real delegate keeps running detached and orphaned. The host then
> reports "completed" while the delegate has produced no output yet. This is not AGY-specific: it has been
> hit with AGY's own task tracker (below) and with an unrelated host's background-task tool in the same way.
> If a delegate ever does end up double-backgrounded, recover by polling `kill -0 <pid>` until it actually
> exits, rather than trusting the premature completion notice.
>
> **AGY specifically:** never use `&` to background a delegate task — it forces an immediate `exit 0`,
> short-circuiting Antigravity's task tracker and losing the PID/status hook. Run the CLI natively in the
> foreground and use Antigravity's `WaitMsBeforeAsync` tool parameter to background it gracefully with a
> completion notification.
>
> **Concurrent worktree isolation:** dispatching multiple implementation delegates concurrently in the main
> working tree lets them trample each other's files and break concurrent test runs. Give each concurrent
> implementation task its own worktree (`git worktree add ../datomworld-<task-id>`), run the delegate CLI
> inside it, and merge back into the main tree once verified. If a host tool needs the raw OS PID (e.g.
> `keep-awake`), get it with `pgrep -f` after launching.

Prompts contain authorized paths, not source text. Headless plan agents must be told to produce the complete
deliverable without waiting for a human. Never use AGY `invoke_subagent` to delegate; always shell out to the
listed CLIs.

## CLI reference

+----------------+----------------------------+----------------------------+----------------------+----------------------------+----------------------+--------------------+
| CLI            | Provider / models          | Model select               | Review-mode flags    | Implementation-mode flags  | Session start        | Resume             |
+================+============================+============================+======================+============================+======================+====================+
| `claude`       | Anthropic; default         | `--model <model>`          | `--permission-mode   | `--permission-mode         | `--session-id <uuid> | `--resume <uuid>`  |
|                | **claude-fable-5-1**       |                            | plan --allowed-tools | acceptEdits`               | --name <task>`       |                    |
|                |                            |                            | Read "Bash(git diff  |                            |                      |                    |
|                |                            |                            | *)" "Bash(git status |                            |                      |                    |
|                |                            |                            | *)"`                 |                            |                      |                    |
+----------------+----------------------------+----------------------------+----------------------+----------------------------+----------------------+--------------------+
| `claude-cloud` | Anthropic API (billing lane: **claude-api**) | `--model <model>`          | same as `claude`     | same as `claude`           | same as `claude`     | same as `claude`   |
|                | (metered credits); default |                            |                      |                            |                      |                    |
|                | **claude-sonnet-5-5** /    |                            |                      |                            |                      |                    |
|                | **claude-opus-5-5**        |                            |                      |                            |                      |                    |
+----------------+----------------------------+----------------------------+----------------------+----------------------------+----------------------+--------------------+
| `glm`          | Zhipu GLM; default         | `MODEL=<model> glm`        | same as `claude`     | same as `claude`           | same as `claude`     | same as `claude`   |
|                | **glm-5.3** (Sonnet/Haiku  |                            |                      |                            |                      |                    |
|                | tier: `glm-5.3-flash`)     |                            |                      |                            |                      |                    |
+----------------+----------------------------+----------------------------+----------------------+----------------------------+----------------------+--------------------+
| `deepseek`     | DeepSeek; default          | `MODEL=<model> deepseek`   | same as `claude`     | same as `claude`           | same as `claude`     | same as `claude`   |
|                | **deepseek-v4-pro**        |                            |                      |                            |                      |                    |
|                | (Sonnet/Haiku tier:        |                            |                      |                            |                      |                    |
|                | `deepseek-flash`)          |                            |                      |                            |                      |                    |
+----------------+----------------------------+----------------------------+----------------------+----------------------------+----------------------+--------------------+
| `codex`        | OpenAI, flat-rate ChatGPT  | `-m <model>`               | `-s read-only`       | `-s workspace-write`       | no caller ID;        | `codex exec resume |
|                | Plus; `gpt-6-astra`,       |                            |                      |                            | capture `thread.star | <thread-id>`       |
|                | `gpt-6.1-sol`,             |                            |                      |                            | ted.thread_id` (see  |                    |
|                | `gpt-6.1-terra`,           |                            |                      |                            | Session continuity)  |                    |
|                | `gpt-6-luna`,              |                            |                      |                            |                      |                    |
|                | `gpt-5.4-mini`             |                            |                      |                            |                      |                    |
+----------------+----------------------------+----------------------------+----------------------+----------------------------+----------------------+--------------------+
| `kimi`         | Moonshot AI; native Kimi   | `-m <model-alias>`         | `--agent <agent>` or | `-p` (auto permissions)    | no caller ID;        | `--session <id>`   |
|                | K3.1 Pro / K3 families     |                            | `--agent-file        |                            | capture session ID   |                    |
|                |                            |                            | <path>` (read-only)  |                            | from session index   |                    |
+----------------+----------------------------+----------------------------+----------------------+----------------------------+----------------------+--------------------+
| `agy`          | Gemini, flat-rate;         | `--model <model> --effort  | `--mode plan         | `--mode accept-edits       | no caller ID;        | `--conversation    |
|                | `gemini-3.1-ultra`,        | <effort>`                  | --sandbox`           | --sandbox`                 | capture              | <id>`              |
|                | `gemini-3.1-pro-high`,     |                            |                      |                            | `conversation_id`    |                    |
|                | `gemini-3.8-flash`         |                            |                      |                            |                      |                    |
+----------------+----------------------------+----------------------------+----------------------+----------------------------+----------------------+--------------------+

Naming: the **billing lane** is `claude-api` (the metered Anthropic API account, a routing-status concept); the **wrapper script** is `claude-cloud` (its on-disk name — do not rename the executable), reached via `CLAUDE_BIN="claude-cloud"`. The wrapper scripts (`claude-cloud`, `glm`, `deepseek`) configure provider endpoints and credentials, then execute
`claude --model "$MODEL" "$@"`, so `claude`/`claude-cloud`/`glm`/`deepseek` share one CLI base: none needs a PTY, all
accept redirected stdin under `-p` (pass `< /dev/null` to prevent hangs), and their startup warning
`claude-code:unrecognized_model` is expected and benign. `claude-cloud` scrubs wrapper routing overrides (`ANTHROPIC_BASE_URL`,
`ANTHROPIC_AUTH_TOKEN`) and injects the official Anthropic API key to draw directly from metered cloud credits without
interfering with `glm` or `deepseek`. Never convert a review command into an editing command unless the user explicitly
authorized edits.

**Kimi routing:** always invoke Kimi models through the dedicated `kimi` CLI.
The managed 3.x aliases are `kimi-code/k3.1-pro`,
`kimi-code/k3.1-pro-highspeed`, `kimi-code/k3`, and `kimi-code/k3-256k`; `-m`
expects a configured model alias, not a bare model ID. The alias must be the
**full provider-qualified name exactly as it appears in
`~/.kimi-code/config.toml`'s `[models."…"]` keys** — e.g. `kimi-code/kimi-for-coding`
(K2.8 Preview) and `kimi-code/kimi-for-coding-highspeed` (K2.7 Code Highspeed)
are valid entries, while the bare forms `kimi-for-coding` or `kimi-k3` fail with
`error: failed to run prompt: Model "…" is not configured in config.toml`.
`kimi --version` and `--help` print to stderr/stdout without touching a session,
so they are safe sanity checks; there is no trivial-prompt warm-up that avoids
consuming a session slot. Headless `-p` uses
`auto` permissions by default and cannot be combined with `--plan`, `--yolo`,
or `--auto`. For a read-only review, bind a read-only agent at session creation
with `--agent <read-only-agent>` or `--agent-file <path>`; for implementation,
omit those flags. Resume with `--session <id>` (equivalently `-S <id>`) and the
original `-m` alias;
the bound agent is restored, so do not pass agent-selection flags on resume.
Start a fresh session when changing models or thinking effort. K3.1 Pro models
require thinking to remain enabled.

Kimi quirks learned in service (2026-10-29):

- **A headless `-p` run holds an exclusive session lock until its process
  exits.** A second `kimi` invocation on the same session fails immediately
  with "This session is in use by another Kimi Code CLI instance". When an
  orchestrator launches `kimi -p … &` in a background shell, the harness may
  report the *shell* finished while the nohup'd kimi keeps running — check
  `pgrep -f kimi` (or whether the stdout log has stopped growing) before
  resuming; "session in use" almost always means the first run is still alive,
  not that it died.
- **`dispatch.sh` does not know `kimi`** (its cli case covers only
  claude/glm/deepseek/muse/claude-cloud). Invoke kimi directly, mirroring the
  reviewer/architect flags yourself:
  `kimi -m "kimi-code/k3.1-pro" --output-format text -p "Read <prompt> and
  complete it now." < /dev/null > <prompt-stem>.stdout.log 2>&1 &`.
  A run cut off mid-review can be resumed with `-S <sessionId>` recovered from
  `~/.kimi-code/session_index.jsonl` (last line for the working directory);
  the resumed run sees the earlier transcript, so ask it to print the complete
  deliverable rather than only the missing tail.
- **Rolling 5-hour quota**: an exhausted quota answers
  `API Error: Request rejected (429)` with the reset timestamp inline; park the
  dispatch and re-dispatch after the stated reset (the prompt file survives).

> [!CAUTION]
> **Never use the `--bare` flag with any CLI above.** It forces Claude Code to ignore user settings files,
> wiping its memory of the OAuth login token and causing it to fail with "Not logged in".

## Canonical recipes

One example per CLI. For a follow-up, swap the session-start flags for that row's Resume flag from the
table above and append `-r<n>` to the output artifact's basename.

```sh
# Claude Code family (claude, claude-cloud, glm, deepseek) — CLAUDE_BIN picks the wrapper, MODEL=<model> the model
CLAUDE_BIN="claude-cloud"   # or: claude | MODEL=<model> glm | MODEL=<model> deepseek
$CLAUDE_BIN --session-id <uuid> --name <task> <review-or-implementation-flags> \
  --output-format text -p "Read <prompt> and complete it now." < /dev/null \
  > collab/<timestamp>-<role>-<task>.<sanitized-model>.stdout.log 2>&1

# Codex
codex exec -m <model> <review-or-implementation-flag> --json - < <prompt> \
  > collab/<timestamp>-<role>-<task>.<model>.stdout.log 2>&1

# Kimi Code — native CLI for every Kimi model
# New implementation session; for a read-only review, add --agent <read-only-agent>.
kimi -m kimi-code/k3.1-pro -p "Read <prompt> and complete it now." \
  --output-format stream-json < /dev/null \
  > collab/<timestamp>-<role>-<task>.kimi-k3.1-pro.stdout.log 2>&1

# Kimi follow-up: exact generated ID, original model, new prompt and artifact.
kimi --session <session-id> -m kimi-code/k3.1-pro \
  -p "Read <follow-up-prompt> and complete it now." --output-format stream-json \
  < /dev/null > collab/<timestamp>-<role>-<task>-r2.kimi-k3.1-pro.stdout.log 2>&1

# Gemini / AGY
# No --print-timeout: it is optional and defaults to 0 (wait until the turn
# completes). Setting a short value truncates long turns mid-review (observed
# 2026-10-18: a 5m limit killed an architecture review mid-turn; see quirks).
agy --model <model> --effort <effort> <review-or-implementation-flags> \
  --output-format json -p "Read <prompt> and complete it now." \
  > collab/<timestamp>-<role>-<task>.<model>.stdout.log 2>&1
```

## Known CLI quirks

Each of these is a host or provider diagnostic, not a real capability gap.
Recognize it, apply the fix, and don't declare failure prematurely:

- **Claude `Session ID <uuid> is already in use` survives killing the holder
  process** — the id is pinned by the session file in the store (`~/.claude`),
  not by a live process, so `pgrep` clean does not free it. Killing the zombie
  and relaunching with the same id fails identically. The fix: dispatch a
  FRESH session id with a by-state resume brief (the worktree diff plus the
  original brief's path — "your work on disk is intact, resume by state"),
  and reference the dead session's artifacts from the new log entry. Observed
  2026-10-29 after killing two zombie holders of a session that backgrounded
  its own test lanes and idled.
- **A delegate that backgrounds its own test lanes and waits on completion
  notifications idles the session indefinitely** — the harness's background
  notification may never arrive, and the session sits consuming a window.
  The standing brief rule "FOREGROUND only, explicit timeouts" exists for
  this; enforce it at resume (the nudge prompt: "do NOT background anything,
  run the lanes foreground with timeouts"). Observed 2026-10-29 on the P6
  S0-S2 round (144 minutes idle).
- **AGY language-server bind/log failures** are host sandbox restrictions.
  Rerun with the same `--sandbox`/`--mode plan` policy through the host tool
  with narrowly scoped escalation. For an authorized implementation run,
  preserve `--sandbox`/`--mode accept-edits`; never add
  `--dangerously-skip-permissions`.
- **AGY `--print-timeout` is optional and defaults to 0** — wait until the
  turn completes. Prescribing a short value (the recipes here carried `5m`
  until 2026-10-18) returns partial output mid-turn ("print timeout after
  Xm with turn in progress") and loses the rest of the run; a review
  needing one conversation resume was the observed cost. Omit the flag for
  delegate dispatches; the host's background tracking owns the wait, and a
  truncated turn is recovered by `--conversation <id>` resume with a new
  prompt/artifact name, never by raising the limit as a routine.
- **Claude `Not logged in` / Codex app-server `Operation not permitted`**
  under the default command sandbox are host diagnostics: retry via host
  escalation with the documented narrow prefixes, preserving each CLI's own
  read-only/write mode.
- **`codex exec resume <id>` without `-m` can silently switch models.** On
  2026-09-28 a resumed `gpt-6-sol` Architect thread ran on `gpt-6-luna`. The
  only trace was a JSONL `item.completed` error item reading "This session was
  recorded with model `gpt-6-sol` but is resuming with `gpt-6-luna`". Always
  pass the original `-m <model>` on resume, and grep the log for `recorded with
  model` before treating the answer as that model's.
- **`codex exec resume` rejects `-s` and `-C` outright** (exit 2, "unexpected
  argument"; observed 2026-10-16): the resume subcommand takes neither the
  sandbox flag nor the working-directory flag. Run it with the shell's cwd set
  to the intended working directory (`cd <worktree> && codex exec resume <id>
  --json -m <model> - < prompt`) and write the resumed prompt's relative paths
  against that cwd; the resumed turn keeps the original thread's sandbox.
- **GLM `claude-code:unrecognized_model` startup warnings** are expected
  for their wrappers; verify the resulting artifact before declaring failure.
- **The same benign startup warning can precede a real outage, not just a
  successful fallback.** The warning alone never distinguishes them — only
  what follows it does. If it's followed by a completed response (even after
  falling back to a sibling model, e.g. `deepseek-v4-pro` silently routing to
  `deepseek-flash`), that's the normal case. If it's followed by a hard error
  instead of any response, on a destination that worked earlier in the same
  session, that's a real provider-side outage — confirm with one trivial
  prompt on a different destination before concluding this and before
  retrying the same destination more than once, then record it in
  [`routing-status.md`](routing-status.md) and route elsewhere.
- **Claude Code headless `--permission-mode plan` without the mandatory
  `--allowed-tools Read "Bash(git diff *)" "Bash(git status *)"` flag**
  silently halts on the first unauthorized tool call; repeated occurrences
  can also trigger the CLI's own bug-report telemetry, which then expects
  interactive input a headless run can never give, hanging or crashing it.
  Always pass the canonical `--allowed-tools` flag.
- **AGY in `--mode plan` may answer a headless brief with a plan artifact and
  a request for approval**, exiting `SUCCESS` with no deliverable. A response
  that promises a verdict rather than stating one is an unfinished turn —
  resume the conversation instructing it to answer directly instead of
  accepting the promise.
- **A sandboxed AGY delegate cannot execute this host's JVM**: `clojure` died
  with `java: Operation not permitted` under `--mode plan --sandbox`, even
  though that mode reads files, runs read-only shell, and writes files
  outside the repo. The denial covers all of `~/.local` (the
  mise-installed JDK included); no host escalation reaches inside a
  delegate's own session, since the `BypassSandbox` fix needs an approval no
  headless `-p` run can obtain. Such a delegate can appear to be verifying
  while unable to check any test claim it passes on: give it only static
  analysis, never a test-dependent deliverable — the orchestrator runs those
  suites itself. This is a property of the sandboxed headless configuration,
  not of AGY itself: a user-run AGY Orchestrator seat isn't restricted this
  way, and every seat establishes its own capabilities rather than assuming
  them from this entry.
- **`--permission-mode acceptEdits` auto-approves Edit/Write/NotebookEdit but
  not Bash**: a compound or piped command (chained `;` steps, `grep | grep`,
  a `for` loop over several files) can still be denied by the CLI's safety
  heuristic, with no human to approve past it in a headless run. Denied
  multi-step one-liners have succeeded once split into a single command, or
  redirected to a file instead of chained. Fix by briefing delegates toward
  one simple command per step — never `--dangerously-skip-permissions` —
  treating a denial as a signal to simplify, not a capability gap to route
  around.

**Codex session writer-lock (observed 2026-10-30):** `codex exec resume <id>` fails with
"thread already has an active writer" when the resumed thread is mid-turn in another
chat — the shared Codex engineering chat referenced by multi-owner briefs is
single-writer. Do not wait on it; dispatch the work to a fresh session with a
self-contained brief (prompt-file paths, exact revisions), and record the original
thread id for provenance.
