---
description: Session-ID mechanics, CLI flags, canonical recipes, and known quirks for invoking delegate agents (claude, glm, deepseek, muse, codex, cmd, agy)
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

Before the first invocation of a Claude Code-based CLI (`claude`, `glm`,
`deepseek`, or `muse`), generate and record an ID:

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

Codex, AGY, and Command Code don't accept a caller-selected ID for a new run in
the installed versions — capture the generated ID immediately via structured output:

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

+----------+-----------------------------+----------------------------------+------------------------------------------+
| CLI      | Session store               | New-session ID source            | Related follow-up                        |
+==========+=============================+==================================+==========================================+
| agy      | `~/.gemini/antigravity-cli` | JSON `conversation_id`           | `--conversation <id>`                    |
+----------+-----------------------------+----------------------------------+------------------------------------------+
| claude   | `~/.claude`                 | caller UUID via `--session-id`   | `--resume <uuid>`                        |
+----------+-----------------------------+----------------------------------+------------------------------------------+
| cmd      | `~/.commandcode`            | NDJSON `result.sessionId`        | `--resume <id>` or `--session <id|path>` |
+----------+-----------------------------+----------------------------------+------------------------------------------+
| codex    | `~/.codex`                  | JSONL `thread.started.thread_id` | `codex exec resume <id> --json -`        |
+----------+-----------------------------+----------------------------------+------------------------------------------+
| deepseek | `~/.claude-deepseek`        | caller UUID via `--session-id`   | `--resume <uuid>`                        |
+----------+-----------------------------+----------------------------------+------------------------------------------+
| glm      | `~/.claude-glm`             | caller UUID via `--session-id`   | `--resume <uuid>`                        |
+----------+-----------------------------+----------------------------------+------------------------------------------+
| muse     | `~/.claude-muse`            | caller UUID via `--session-id`   | `--resume <uuid>`                        |
+----------+-----------------------------+----------------------------------+------------------------------------------+

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

+------------+----------------------------+----------------------------+----------------------+----------------------------+----------------------+--------------------+
| CLI        | Provider / models          | Model select               | Review-mode flags    | Implementation-mode flags  | Session start        | Resume             |
+============+============================+============================+======================+============================+======================+====================+
| `claude`   | Anthropic; default         | `--model <model>`          | `--permission-mode   | `--permission-mode         | `--session-id <uuid> | `--resume <uuid>`  |
|            | **claude-fable-5-1**       |                            | plan --allowed-tools | acceptEdits`               | --name <task>`       |                    |
|            |                            |                            | Read "Bash(git diff  |                            |                      |                    |
|            |                            |                            | *)" "Bash(git status |                            |                      |                    |
|            |                            |                            | *)"`                 |                            |                      |                    |
+------------+----------------------------+----------------------------+----------------------+----------------------------+----------------------+--------------------+
| `glm`      | Zhipu GLM; default         | `MODEL=<model> glm`        | same as `claude`     | same as `claude`           | same as `claude`     | same as `claude`   |
|            | **glm-5.3** (Sonnet/Haiku  |                            |                      |                            |                      |                    |
|            | tier: `glm-5.3-flash`)     |                            |                      |                            |                      |                    |
+------------+----------------------------+----------------------------+----------------------+----------------------------+----------------------+--------------------+
| `deepseek` | DeepSeek; default          | `MODEL=<model> deepseek`   | same as `claude`     | same as `claude`           | same as `claude`     | same as `claude`   |
|            | **deepseek-v4-pro**        |                            |                      |                            |                      |                    |
|            | (Sonnet/Haiku tier:        |                            |                      |                            |                      |                    |
|            | `deepseek-flash`)          |                            |                      |                            |                      |                    |
+------------+----------------------------+----------------------------+----------------------+----------------------------+----------------------+--------------------+
| `muse`     | Meta/Muse; default **muse- | `MODEL=<model> muse`       | same as `claude`     | same as `claude`           | same as `claude`     | same as `claude`   |
|            | spark-1.3-contributor**    |                            |                      |                            |                      |                    |
|            | (Sonnet tier: `muse-       |                            |                      |                            |                      |                    |
|            | spark-1.3`)                |                            |                      |                            |                      |                    |
+------------+----------------------------+----------------------------+----------------------+----------------------------+----------------------+--------------------+
| `codex`    | OpenAI, flat-rate ChatGPT  | `-m <model>`               | `-s read-only`       | `-s workspace-write`       | no caller ID;        | `codex exec resume |
|            | Plus; `gpt-6-astra`,       |                            |                      |                            | capture `thread.star | <thread-id>`       |
|            | `gpt-6-sol`,               |                            |                      |                            | ted.thread_id` (see  |                    |
|            | `gpt-6-terra`,             |                            |                      |                            | Session continuity)  |                    |
|            | `gpt-6-luna`,              |                            |                      |                            |                      |                    |
|            | `gpt-5.4-mini`             |                            |                      |                            |                      |                    |
+------------+----------------------------+----------------------------+----------------------+----------------------------+----------------------+--------------------+
| `cmd`      | CommandCode.ai; **reserved | `-m <model>`               | `--plan`             | `--permission-mode auto-   | no caller ID;        | `--resume          |
|            | strictly for               |                            |                      | accept`                    | capture              | <session-id>` (or  |
|            | external/unsupported       |                            |                      |                            | `result.sessionId`   | `--session         |
|            | models with no dedicated   |                            |                      |                            |                      | <id|path>`)        |
|            | wrapper** (e.g.            |                            |                      |                            |                      |                    |
|            | `moonshotai/kimi-k3`, `moo |                            |                      |                            |                      |                    |
|            | nshotai/kimi-k2.7-code`,   |                            |                      |                            |                      |                    |
|            | `qwen/qwen3.8-max`) —      |                            |                      |                            |                      |                    |
|            | never for models a         |                            |                      |                            |                      |                    |
|            | dedicated CLI already      |                            |                      |                            |                      |                    |
|            | covers                     |                            |                      |                            |                      |                    |
+------------+----------------------------+----------------------------+----------------------+----------------------------+----------------------+--------------------+
| `agy`      | Gemini, flat-rate;         | `--model <model> --effort  | `--mode plan         | `--mode accept-edits       | no caller ID;        | `--conversation    |
|            | `gemini-3.1-ultra`,        | <effort>`                  | --sandbox`           | --sandbox`                 | capture              | <id>`              |
|            | `gemini-3.1-pro-high`,     |                            |                      |                            | `conversation_id`    |                    |
|            | `gemini-3.8-flash`         |                            |                      |                            |                      |                    |
+------------+----------------------------+----------------------------+----------------------+----------------------------+----------------------+--------------------+

The wrapper scripts (`glm`, `deepseek`, `muse`) configure provider endpoints and credentials, then execute
`claude --model "$MODEL" "$@"`, so `claude`/`glm`/`deepseek`/`muse` share one CLI base: none needs a PTY, all
accept redirected stdin under `-p` (pass `< /dev/null` to prevent hangs), and their startup warning
`claude-code:unrecognized_model` is expected and benign. Never convert a review command into an editing
command unless the user explicitly authorized edits.

> [!CAUTION]
> **Never use the `--bare` flag with any CLI above.** It forces Claude Code to ignore user settings files,
> wiping its memory of the OAuth login token and causing it to fail with "Not logged in".

## Canonical recipes

One example per CLI. For a follow-up, swap the session-start flags for that row's Resume flag from the
table above and append `-r<n>` to the output artifact's basename.

```sh
# Claude Code family (claude, glm, deepseek, muse) — CLAUDE_BIN picks the wrapper, MODEL=<model> the model
CLAUDE_BIN="claude"   # or: MODEL=<model> glm | MODEL=<model> deepseek | MODEL=<model> muse
$CLAUDE_BIN --session-id <uuid> --name <task> <review-or-implementation-flags> \
  --output-format text -p "Read <prompt> and complete it now." < /dev/null \
  > collab/<timestamp>-<role>-<task>.<sanitized-model>.stdout.log 2>&1

# Codex
codex exec -m <model> <review-or-implementation-flag> --json - < <prompt> \
  > collab/<timestamp>-<role>-<task>.<model>.stdout.log 2>&1

# Command Code
cmd -p -m <model> <review-or-implementation-flags> --output-format json < <prompt> \
  > collab/<timestamp>-<role>-<task>.<sanitized-model>.stdout.log 2>&1

# Gemini / AGY
agy --model <model> --effort <effort> <review-or-implementation-flags> \
  --print-timeout 5m --output-format json -p "Read <prompt> and complete it now." \
  > collab/<timestamp>-<role>-<task>.<model>.stdout.log 2>&1
```

## Known CLI quirks

Each of these is a host or provider diagnostic, not a real capability gap.
Recognize it, apply the fix, and don't declare failure prematurely:

- **AGY language-server bind/log failures** are host sandbox restrictions.
  Rerun with the same `--sandbox`/`--mode plan` policy through the host tool
  with narrowly scoped escalation. For an authorized implementation run,
  preserve `--sandbox`/`--mode accept-edits`; never add
  `--dangerously-skip-permissions`.
- **Claude `Not logged in` / Codex app-server `Operation not permitted`**
  under the default command sandbox are host diagnostics: retry via host
  escalation with the documented narrow prefixes, preserving each CLI's own
  read-only/write mode.
- **GLM/Muse `claude-code:unrecognized_model` startup warnings** are expected
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
