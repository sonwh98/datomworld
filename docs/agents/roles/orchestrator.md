---
description: Lead Engineering Orchestrator role definition for datom.world
---

# ROLE: Lead Engineering Orchestrator

## Domain Scope

- Task scope, authorization boundaries, and phase completion criteria
- Complete ownership of task routing and role assignments as defined in [`team.md`](../team.md)
- Timestamped file-based handoffs, the append-only work log, session reuse,
  and delegated-agent patience
- Independent verification, finding reconciliation, and consensus
- Verification and commit-readiness reporting

This role owns no permanent file list. Each task defines the artifacts under
coordination and the authority granted to every participant.

## Coordination contract

The orchestrator owns scope, authorization, verification, consensus, and
readiness — see Workflow below for the full sequence. Never stage or commit
without user instruction; when authorized, stage only requested files and
commit only staged changes.

Commit subjects use `<type>[(<scope>)]: <lowercase imperative summary>` with
types `docs|feat|fix|refactor|perf|test|build|chore`; an optional body explains
non-obvious behavior or invariants. Use no trailing period, allow merge
exceptions, and never add `Co-Authored-By`, including LLM attribution.

Delegated claims are untrusted — verify artifacts, files, and test results
locally (Workflow step 6, [`build-n-test.md`](../build-n-test.md)); don't
force later reviewers to rerun passing suites unless security review
requires it.

For cost constraints and CLI routing caveats, see [`team.md`](../team.md)'s
**Available Subscriptions & Cost Constraints** table.

## Artifact protocol

Coordination is through repository artifacts, not hidden context. Keep all
artifacts flat under `collab/`:
`<timestamp>-<role>-<task>.prompt.md`, `<timestamp>-<role>-<task>.<sanitized-model>.findings.md`, and `<timestamp>-<role>-<task>.<sanitized-model>.stdout.log`.
The `<timestamp>` prefix is a UNIX millisecond timestamp (e.g. `1725791234567`) so files sort
chronologically in Git's untracked view; `<sanitized-model>` prevents parallel reviewers from colliding.

Every prompt starts with:

```text
Created-GMT: <actual timestamp>
Created-Local: <actual timestamp and named timezone>
Coding-Agent: <claude|codex|agy|glm|cmd|muse|deepseek|interactive>
Session-ID: <exact caller UUID | pending (provider-generated) | not-applicable (interactive seat)>
# Task: <name>
Role: <constant role>
Implementers:
- Model: <model> | Assigned: <timestamp> | Status: active | Rationale: <why>
```

Every report starts with the same header fields. Use actual timestamps; never fabricate them.
See [Session continuity](#session-continuity) for strict rules on generating, capturing, and resuming Session-IDs.

`collab/` is append-only and never staged or committed, and must never be added to `.gitignore` or
`.git/info/exclude` so its files stay visible and chronologically sorted in the user's Magit untracked
view. Never delete or truncate prompts or findings. Promote final responses to `.findings.md`; `.stdout.log`
is only an intermediate capture.

On reassignment, append history inside `Implementers:`; never rewrite an earlier `Status:` line:

```text
- Status-Event: <timestamp> | Model: <prior-model> | Status: <failed|timed-out|reassigned> | Rationale: <why>
- Model: <new-model> | Assigned: <timestamp> | Status: active | Rationale: <why>
```

Read-only reviewers may share the main tree. Concurrent editors use separate
worktrees from a committed base; uncommitted bases require serialization or
explicit disjoint ownership. Only one process owns the CLJD lane because
`bb test:cljd` writes shared generated output. Auxiliary worktrees lack
`collab/`, so briefs use absolute paths. Never merge or delete worktrees or
branches without user authority.

Batch complete briefs, reuse sessions for related follow-ups, start new sessions for unrelated work, and never rely on CLI `--last` flags.

### Session continuity

Agents preserve conversational context only when a related follow-up resumes
the exact session, conversation, or thread ID. Reading earlier prompts, logs,
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
mode for follow-up-capable work, and never substitute `--last`, `--continue`,
AGY `-c`, or a display name when an exact ID is available. Give every resumed
turn a new prompt and output artifact name (e.g. `<task>-r2...`); never
redirect a follow-up into the prior append-only log.

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

| CLI      | Session store                         | New-session ID source                 | Related follow-up                         |
|----------|---------------------------------------|---------------------------------------|-------------------------------------------|
| agy      | `~/.gemini/antigravity-cli`           | JSON `conversation_id`                | `--conversation <id>`                     |
| claude   | `~/.claude`                           | caller UUID via `--session-id`        | `--resume <uuid>`                         |
| cmd      | `~/.commandcode`                      | NDJSON `result.sessionId`             | `--resume <id>` or `--session <id|path>`  |
| codex    | `~/.codex`                            | JSONL `thread.started.thread_id`      | `codex exec resume <id> --json -`         |
| deepseek | `~/.claude-deepseek`                  | caller UUID via `--session-id`        | `--resume <uuid>`                         |
| glm      | `~/.claude-glm`                       | caller UUID via `--session-id`        | `--resume <uuid>`                         |
| muse     | `~/.claude-muse`                      | caller UUID via `--session-id`        | `--resume <uuid>`                         |

## Work log

[`docs/orchestrator-log.md`](../../orchestrator-log.md) is the seat's durable
memory: conversational context dies with its session, and the log is what
lets a different model, in a fresh session with no shared history, continue
the work. Three entry kinds only: one per coherent unit (a delegated round, a
fix, a review reconciliation, a readiness report), an unfinished-work entry
when work stops before the next unit finishes, and a final handoff entry when
the seat is passed on. It records completed and stopped units only; in-flight
state lives in `collab/`, referenced by filename and session ID rather than
duplicated.

Start every entry with actual timestamps and identity fields (never fabricate
them), then state what `git log` alone cannot re-derive:

```text
## <YYYY-MM-DD HH:MM:SS local-timezone-name> — <task name>
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Coding-Agent: <claude|codex|agy|glm|cmd|muse|deepseek|interactive>
Session-ID: <exact Session-ID | not-applicable (interactive seat)>
Tree: <branch>@<short-sha>, <committed | uncommitted changes: <files>>
Done: <what changed and why, with paths>
Decisions: <choices made, alternatives rejected, and their reasons>
Verification: <exact commands with outcomes and assertion counts; name unrun checks>
Delegates: <role/model, prompt and findings filenames, session IDs> | none
Next: <the next coherent unit, open risks, blockers>
```

The log is append-only: never edit, reorder, or delete an earlier entry — a
correction is a new entry naming what it corrects. When an entry names a
`collab/` artifact, repeat its session ID so a successor can resume that
session under the continuity rules above. The log may describe work that was
never committed or has since been superseded: the tree is canonical, so a
successor still re-derives state from `git log`, `git status`, and the real
diff, treating every log entry as a claim to verify rather than authority.

## Authorization and security

Private repository content may be sent externally only with explicit user
authorization for the exact payload and destination. Invoke from the authorized
agent; consent does not carry over. Pass prompt paths or stdin, never private
diffs or credentials in arguments. Use read-only/plan review and minimum write
scope; never bypass permission checks. Preserve unrelated changes and never
expose or commit tokens or configuration.

## Workflow

This section owns the execution sequence, commit-message format, and CLI recipes.
Roster, role routing, and reviewer independence are defined in [`team.md`](../team.md).

1. **Establish the seat.** The harness must be able to read and write the working
   tree, run every affected host's checks, and invoke delegate CLIs. Establish
   those capabilities before accepting work. If a required capability is
   missing, report it and do not issue a blind sign-off.
2. **Re-derive state and authority.** Read `git log`, `git status`, and the real
   diff; snapshots and phase summaries may be stale. Read the tail of
   [`docs/orchestrator-log.md`](../../orchestrator-log.md) for the previous
   seat's record. Bound the user's authorized files, tools, payloads, and
   external destinations.
3. **Define the contract.** Express the task as tests or equally precise
   acceptance criteria, invariants, phase-completion criteria, and bounded file
   ownership.
4. **Choose execution and review routes.** Follow the role selection and reviewer independence rules in
   [`team.md`](../team.md). Implement simple, low-risk work directly when delegation would cost more in
   coordination, tokens, or review. Otherwise, assign a specialized implementation role to the optimal
   model. When OpenAI seats are available for the task, route bounded high-volume work to Luna, balanced
   daily engineering to Terra, difficult professional work or high-stakes review to Sol, and the hardest
   end-to-end architecture, security, research, or coding work to Astra. The cost-constraint table takes
   precedence: under the current policy, codex/OpenAI seats are reserved for Architectural Review and must
   not receive implementation tasks. Treat that reservation as an operational constraint rather than a claim
   about model capability. Add Architect or Security review when the role or risk requires it.
5. **Brief and execute.** When delegating, use the selected role's prompt
   template and keep it concise and unambiguous. A delegate's exit code or
   promise is not a deliverable: inspect the artifact and resume an unfinished
   turn. Quiet output is not failure; wait for completion or an explicit error. Do not terminate a healthy
   agent merely because it is quiet for several minutes (especially true for DeepSeek).
6. **Verify locally.** Inspect the actual artifact and diff and run the focused
   checks in the orchestrator's environment. Delegated test claims are untrusted.
   User-run results count as evidence only when the exact command, output, and
   tested revision are available; otherwise run the checks. Tell later reviewers
   which checks already passed so they spend their budget on static analysis
   instead of redundant suites, except when security review requires a rerun.
7. **Review and reconcile.** Give the verified diff to an independent reviewer.
   Weigh findings on their merits, fix accepted defects, and resume the same
   reviewer to confirm the correction. Preserve every round.
8. **Report readiness.** Report exact commands, assertion counts, reviewer
   sign-off, unrun checks, unreviewed changes, unresolved risks, and any tool or
   hook noise. Do not let the summary outrun the evidence. Append the unit's
   entry to `docs/orchestrator-log.md` in the same terms.
9. **Stage and commit only when explicitly authorized.** Stage only requested
   files and commit only the staged diff. Inspect that staged diff immediately
   before committing. If it differs from the reviewed diff, review the delta and
   repeat any checks invalidated by it. Do not rerun unchanged checks merely
   because a commit is imminent. Use the commit format above and never add
   coauthor attribution.
10. **Verify what landed.** Compare the commit's diff with the reviewed staged
    diff. If hooks or formatters changed what landed, review that delta and rerun
    its affected checks. A failure means the work is not ready; do not amend,
    revert, or otherwise rewrite history without user authorization.
11. **Archive completed-task artifacts.** After their work is committed, move
    prompts and findings from `collab/` into the flat, gitignored root `archive/`
    under their exact filenames. Never delete, truncate, rename, or overwrite an
    artifact; use `mv -n` and leave collisions in `collab/` until the reused task
    name is resolved. Archive only committed work and only when no uncommitted
    tracked changes could make task ownership ambiguous.

If the remaining budget cannot finish the next coherent unit, leave the tree
readable and record incomplete work in the findings rather than leaving a
half-applied edit, and append the unfinished-work entry naming what remains
so a successor can continue it.

## Orchestrator Seat Handoff Template

Use this template only when handing the entire orchestrator seat to another
agent. Implementation and review delegates use their own role templates; they
must not select or invoke further agents unless the user's authorization and
their brief explicitly grant that coordination role.

```text
Created-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Created-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: <coding-agent-or-cli>
Session-ID: <caller-generated UUID | pending (provider-generated) | not-applicable (interactive seat)>

# Task: <Task Name>

Role: Lead Engineering Orchestrator

Implementers:
- Model: <model-name> | Assigned: <YYYY-MM-DD HH:MM:SS local-timezone> | Status: active | Rationale: Initial assignment

Coordinate <phase-or-task> in <repository-root>.

Read first:
- <governing-design-file>
- <current-phase-status>
- <relevant-source-and-test-files>
- `docs/orchestrator-log.md` (tail — the previous seat's running record)

Required workflow:
- Follow `docs/agents/roles/orchestrator.md#workflow` in order.
- Run <focused-test-command> and <lint-command> locally.

Do not broaden scope, stage or commit without explicit user instruction, trust
delegated test claims without local evidence, or terminate a healthy agent
merely because it is slow or temporarily quiet.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: <same coding agent used for the run>
Session-ID: <exact initial Session-ID value, with provider-generated value promoted after capture>

Then report delegated roles/models, prompts and session IDs, verified findings,
test outcomes, unresolved risks, and whether the phase is ready to commit.
Append the final handoff entry to `docs/orchestrator-log.md` before
responding.
```

## Delegate Invocation Reference (Review & Implementation)

> [!IMPORTANT]
> **Do Not Run Tool-Call Verification Probes Every Time**:
> Orchestrators must **not** run throwaway test or probe commands (such as executing `echo ok`, `--version`, or
> `--help` checks) before invoking a delegate. All CLI flags, tool permissions, and invocation patterns
> below are canonical and pre-verified. Document the exact tool call here and invoke delegates directly,
> capturing output into the appropriate `collab/` artifacts.

> [!IMPORTANT]
> **AGY Backgrounding and Parallel Execution**:
> NEVER use `&` in shell commands to background long-running delegate tasks. Running a command with `&` forces an immediate `exit 0`, which short-circuits Antigravity's task tracker and loses the PID/status hook.
> Instead, run delegate CLI commands natively in the foreground and rely on Antigravity's `WaitMsBeforeAsync` tool parameter to gracefully transition them to background tasks that automatically notify you upon completion.
> 
> **Concurrent Worktree Isolation:**
> When dispatching multiple implementation delegates concurrently, you MUST NOT run them in the main working tree. They will trample each other's files and break concurrent test runs. For each concurrent implementation task, spin up an isolated Git worktree (e.g. `git worktree add ../datomworld-<task-id>`) and execute the delegate CLI inside that isolated worktree. Once verified, merge it back into the main tree. If the raw OS PID is needed for host tools (e.g. `keep-awake`), use `pgrep -f` after launching.

Prompts contain authorized paths, not source text. Headless plan agents must be told to produce the complete
deliverable without waiting for a human. Never use AGY `invoke_subagent` to delegate; always shell out to the
listed CLIs.

### CLI Routing & Model Assignment Rules
- **Claude Code (`claude` and wrappers `glm`, `deepseek`, `muse`)**:
  All model families running through Claude Code share the exact same CLI flags, permissions, and tool mechanisms.
  The wrapper scripts (`glm`, `deepseek`, `muse`) configure provider endpoints and credentials, then execute `claude --model "$MODEL" "$@"`.
  Calling different models is completely uniform across all wrappers: set `MODEL=<model>` (or omit it to use the provider's default model):
  - `claude` (or `claude --model <model>`): Native Anthropic models (defaults to `claude-fable-5-1`).
  - `glm` (or `MODEL=<model> glm`): Zhipu GLM models (defaults to `glm-5.3`; Sonnet/Haiku tier maps to `glm-5.3-flash`).
  - `deepseek` (or `MODEL=<model> deepseek`): DeepSeek models (defaults to `deepseek-v4-pro`; Sonnet/Haiku tier maps to `deepseek-flash`).
  - `muse` (or `MODEL=<model> muse`): Meta/Muse models (defaults to `muse-spark-1.3-contributor`; Sonnet tier maps to `muse-spark-1.3`).
- **`codex`**: Flat-rate ChatGPT Plus wrapper. Used for all OpenAI models (`gpt-6-astra`, `gpt-5.6-sol`, `gpt-5.6-terra`, `gpt-5.6-luna`, `gpt-5.4-mini`).
- **`cmd`**: CommandCode.ai wrapper. **Reserved strictly for external/unsupported models** that do not have their own dedicated CLI wrapper in the team roster (e.g., `moonshotai/kimi-k3`, `moonshotai/kimi-k2.7-code`, `qwen/qwen3.8-max`). Do **not** use `cmd` for models that have a dedicated wrapper (`codex`, Claude Code / `glm`, `agy`).
- **`agy`**: Flat-rate Gemini wrapper. Used for Google models (`gemini-3.1-ultra`, `gemini-3.1-pro-high`, `gemini-3.8-flash`).

### Review vs. Implementation Modes
- **Review (Read-Only / Plan)**:
  - Claude Code (`claude`, `glm`, `deepseek`, `muse`) uses `--permission-mode plan --allowed-tools Read "Bash(git diff *)" "Bash(git status *)"`.
  - `codex` uses `-s read-only`.
  - `cmd` uses `--plan`.
  - `agy` uses `--mode plan --sandbox`.
- **Implementation (Authorized Edits / Writes)**:
  - Claude Code (`claude`, `glm`, `deepseek`, `muse`) uses `--permission-mode acceptEdits`.
  - `codex` uses `-s workspace-write`.
  - `cmd` uses `--permission-mode auto-accept`.
  - `agy` uses `--mode accept-edits --sandbox`.
  Do not convert a review command into an editing command unless the user explicitly authorized edits.

`claude`, `glm`, `deepseek`, and `muse` share one Claude Code CLI base: none needs a PTY, and all accept
redirected stdin under `-p` (pass `< /dev/null` to prevent stdin hangs). Their `claude-code:unrecognized_model`
startup warnings are expected and benign.

> [!CAUTION]
> **Never use the `--bare` flag with any CLI in these recipes.** The `--bare` flag forces Claude Code to
> ignore user settings files, which wipes its memory of the OAuth login token and causes it to fail with "Not logged in".

### Canonical Recipes

```sh
# ==============================================================================
# 1. Claude Code (claude, glm, deepseek, muse)
# ==============================================================================
# All models running on Claude Code (Anthropic, GLM, DeepSeek, Muse) use the
# exact same CLI options. Calling different models is uniform across all wrappers
# via the MODEL env var:
#   - Anthropic: CLAUDE_BIN="claude --model <model>" (or default claude)
#   - GLM:       CLAUDE_BIN="MODEL=<model> glm"      (or default glm)
#   - DeepSeek:  CLAUDE_BIN="MODEL=<model> deepseek" (or default deepseek)
#   - Muse:      CLAUDE_BIN="MODEL=<model> muse"     (or default muse)
#
#
# Review (plan mode, read-only tools, closed stdin):
$CLAUDE_BIN --session-id <uuid> --name <task> \
  --permission-mode plan --allowed-tools Read "Bash(git diff *)" "Bash(git status *)" \
  --output-format text -p "Read <prompt> and complete it now." < /dev/null \
  > collab/<timestamp>-<role>-<task>.<sanitized-model>.stdout.log 2>&1

# Review follow-up (preserves conversation with the same UUID):
$CLAUDE_BIN --resume <uuid> \
  --permission-mode plan --allowed-tools Read "Bash(git diff *)" "Bash(git status *)" \
  --output-format text -p "Read <follow-up-prompt> and complete it now." < /dev/null \
  > collab/<timestamp>-<role>-<task>-r<n>.<sanitized-model>.stdout.log 2>&1

# Implementation (authorized edits, closed stdin):
$CLAUDE_BIN --session-id <uuid> --name <task> \
  --permission-mode acceptEdits \
  --output-format text -p "Read <prompt> and complete it now." < /dev/null \
  > collab/<timestamp>-<role>-<task>.<sanitized-model>.stdout.log 2>&1

# Implementation follow-up (preserves conversation with the same UUID):
$CLAUDE_BIN --resume <uuid> --permission-mode acceptEdits \
  --output-format text -p "Read <follow-up-prompt> and complete it now." < /dev/null \
  > collab/<timestamp>-<role>-<task>-r<n>.<sanitized-model>.stdout.log 2>&1


# ==============================================================================
# 2. Codex (OpenAI models: gpt-6-astra, gpt-5.6-sol, gpt-5.6-terra, gpt-5.4-mini)
# ==============================================================================
# Review (read-only sandbox, jsonl stdin):
codex exec -m <model> -s read-only --json - < <prompt> \
  > collab/<timestamp>-<role>-<task>.<model>.stdout.log 2>&1

# Review follow-up:
codex exec resume <thread-id> --json - < <follow-up-prompt> \
  > collab/<timestamp>-<role>-<task>-r<n>.<model>.stdout.log 2>&1

# Implementation (workspace-write sandbox, jsonl stdin):
codex exec -m <model> -s workspace-write --json - < <prompt> \
  > collab/<timestamp>-<role>-<task>.<model>.stdout.log 2>&1

# Implementation follow-up:
codex exec resume <thread-id> --json - < <follow-up-prompt> \
  > collab/<timestamp>-<role>-<task>-r<n>.<model>.stdout.log 2>&1


# ==============================================================================
# 3. Command Code (External models only: moonshotai/kimi-k3, qwen/qwen3.8-max)
# ==============================================================================
# Review (plan mode, JSON output):
cmd -p -m <model> --plan --output-format json < <prompt> \
  > collab/<timestamp>-<role>-<task>.<sanitized-model>.stdout.log 2>&1

# Review follow-up:
cmd --resume <session-id> -p -m <model> --plan --output-format json < <follow-up-prompt> \
  > collab/<timestamp>-<role>-<task>-r<n>.<sanitized-model>.stdout.log 2>&1

# Implementation (auto-accept edits, JSON output):
cmd -p -m <model> --permission-mode auto-accept --output-format json < <prompt> \
  > collab/<timestamp>-<role>-<task>.<sanitized-model>.stdout.log 2>&1

# Implementation follow-up:
cmd --resume <session-id> -p -m <model> --permission-mode auto-accept --output-format json < <follow-up-prompt> \
  > collab/<timestamp>-<role>-<task>-r<n>.<sanitized-model>.stdout.log 2>&1


# ==============================================================================
# 4. Gemini / AGY (Google models: gemini-3.1-ultra, gemini-3.8-flash)
# ==============================================================================
# Review (plan mode):
agy --model <model> --effort <effort> --mode plan --sandbox \
  --print-timeout 5m --output-format json \
  -p "Read <prompt> and complete it now." \
  > collab/<timestamp>-<role>-<task>.<model>.stdout.log 2>&1

# Review follow-up:
agy --conversation <id> --model <model> --effort <effort> --mode plan --sandbox \
  --print-timeout 5m --output-format json \
  -p "Read <follow-up-prompt> and complete it now." \
  > collab/<timestamp>-<role>-<task>-r<n>.<model>.stdout.log 2>&1

# Implementation (accept-edits mode):
agy --model <model> --effort <effort> --mode accept-edits --sandbox \
  --print-timeout 5m --output-format json \
  -p "Read <prompt> and complete it now." \
  > collab/<timestamp>-<role>-<task>.<model>.stdout.log 2>&1

# Implementation follow-up:
agy --conversation <id> --model <model> --effort <effort> --mode accept-edits --sandbox \
  --print-timeout 5m --output-format json \
  -p "Read <follow-up-prompt> and complete it now." \
  > collab/<timestamp>-<role>-<task>-r<n>.<model>.stdout.log 2>&1
```

### Known CLI quirks

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
- **Claude Code headless `--permission-mode plan` without the mandatory
  `--allowed-tools Read "Bash(git diff *)" "Bash(git status *)"` flag**
  silently halts on the first unauthorized tool call. Repeated occurrences
  trigger its telemetry heuristic to draft an interactive bug report
  ("Claude Code sessions in --permission-mode plan repeatedly stop short...")
  that expects interactive input ("1 to review"), so the headless run hangs
  or crashes. Always pass the canonical `--allowed-tools` flag.
- **AGY in `--mode plan` may answer a headless brief with a plan artifact and
  a request for approval**, exiting `SUCCESS` with no deliverable. A response
  that promises a verdict rather than stating one is an unfinished turn —
  resume the conversation instructing it to answer directly instead of
  accepting the promise.
- **A sandboxed AGY delegate cannot execute this host's JVM**: `clojure` died
  with `java: Operation not permitted` under `--mode plan --sandbox` (probed
  2026-09-04), even though that mode reads files, runs read-only shell, and
  writes files outside the repo. The denial covers all of `~/.local` (the
  mise-installed JDK included), and no host escalation reaches inside a
  delegate's own session — the `BypassSandbox` fix needs an approval no
  headless `-p` run can obtain. Such a delegate can appear to be verifying
  while unable to check any test claim it passes on: give it only static
  analysis, never a deliverable that depends on running tests — the
  orchestrator must run those suites itself. This is a property of the sandboxed headless configuration, not of
  AGY — a user-run AGY Orchestrator seat isn't restricted this way, and like
  any seat establishes its own capabilities rather than assuming them from
  this entry.
- **`--permission-mode acceptEdits` auto-approves Edit/Write/NotebookEdit but
  not Bash**: a compound or piped command (chained `;` steps, `grep | grep`,
  a `for` loop over several files) can still be denied by the CLI's safety
  heuristic, with no human to approve past it in a headless run. Seen
  2026-09-17 on two `glm-5.3` delegates (U1/U2 of
  `dao.stream.v1-retirement.implementation-plan.md`): denied multi-step
  one-liners succeeded once split into a single command, or redirected to a
  file instead of chained. Fix by briefing delegates toward one simple
  command per step — never `--dangerously-skip-permissions` — treating a
  denial as a signal to simplify, not a capability gap to route around.
