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
commit only staged changes. No commit is made until independent review
(Workflow step 7) has completed and its findings are reconciled, or the user
explicitly instructs a commit without waiting for review (2026-09-23, owner
instruction, reversing the commit-then-review rule this same day had
introduced). Local verification (step 6) passing alone is not grounds to
commit. If a commit is made before review completes without that explicit
instruction, undo it (a local, unpushed, unmerged commit can be soft-reset)
rather than let review happen after the fact.

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
`collab/`, so briefs use absolute paths, and delegates there often stage
their own reference copies of prompts/findings in a local `collab/`. Before
removing a worktree, diff its `collab/` against the main tree's and copy
over anything unique — a worktree merge moves only committed content, so
untracked `collab/` files are otherwise lost with the worktree. Never merge
or delete worktrees or branches without user authority.

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
7. **Review and reconcile.** Give the verified diff to an independent reviewer
   before committing, unless the user has explicitly instructed a commit
   without waiting for review. Weigh findings on their merits, fix accepted
   defects, and resume the same reviewer to confirm the correction. Preserve
   every round.
8. **Report readiness.** Report exact commands, assertion counts, reviewer
   sign-off, unrun checks, unreviewed changes, unresolved risks, and any tool or
   hook noise. Do not let the summary outrun the evidence. Append the unit's
   entry to `docs/orchestrator-log.md` in the same terms.
9. **Stage and commit only when explicitly authorized.** Stage only requested
   files and commit only the staged diff. Inspect that staged diff immediately
   before committing. Do not commit on step 6 alone: work that gets an
   independent review (step 7) is not ready to commit until that review has
   completed and its findings are reconciled, unless the user explicitly
   instructed a commit without waiting for review. Use the commit format above
   and never add coauthor attribution.
10. **Verify what landed.** Compare the commit's diff with the reviewed staged
    diff (or the diff the user explicitly authorized, if committed without
    review). If hooks or formatters changed what landed, review that delta and
    rerun its affected checks. A failure means the work is not ready; do not
    amend, revert, or otherwise rewrite history without user authorization.
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
> **Don't probe before delegating.** Never run throwaway commands (`echo ok`, `--version`, `--help`) to
> verify a delegate CLI before invoking it — every flag and pattern below is canonical and pre-verified.
> Document the exact tool call here and invoke directly, capturing output into `collab/`.

> [!IMPORTANT]
> **AGY backgrounding:** never use `&` to background a delegate task — it forces an immediate `exit 0`,
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

### CLI reference

| CLI | Provider / models | Model select | Review-mode flags | Implementation-mode flags | Session start | Resume |
|---|---|---|---|---|---|---|
| `claude` | Anthropic; default **claude-fable-5-1** | `--model <model>` | `--permission-mode plan --allowed-tools Read "Bash(git diff *)" "Bash(git status *)"` | `--permission-mode acceptEdits` | `--session-id <uuid> --name <task>` | `--resume <uuid>` |
| `glm` | Zhipu GLM; default **glm-5.3** (Sonnet/Haiku tier: `glm-5.3-flash`) | `MODEL=<model> glm` | same as `claude` | same as `claude` | same as `claude` | same as `claude` |
| `deepseek` | DeepSeek; default **deepseek-v4-pro** (Sonnet/Haiku tier: `deepseek-flash`) | `MODEL=<model> deepseek` | same as `claude` | same as `claude` | same as `claude` | same as `claude` |
| `muse` | Meta/Muse; default **muse-spark-1.3-contributor** (Sonnet tier: `muse-spark-1.3`) | `MODEL=<model> muse` | same as `claude` | same as `claude` | same as `claude` | same as `claude` |
| `codex` | OpenAI, flat-rate ChatGPT Plus; `gpt-6-astra`, `gpt-5.6-sol`, `gpt-5.6-terra`, `gpt-5.6-luna`, `gpt-5.4-mini` | `-m <model>` | `-s read-only` | `-s workspace-write` | no caller ID; capture `thread.started.thread_id` (see Session continuity) | `codex exec resume <thread-id>` |
| `cmd` | CommandCode.ai; **reserved strictly for external/unsupported models with no dedicated wrapper** (e.g. `moonshotai/kimi-k3`, `moonshotai/kimi-k2.7-code`, `qwen/qwen3.8-max`) — never for models a dedicated CLI already covers | `-m <model>` | `--plan` | `--permission-mode auto-accept` | no caller ID; capture `result.sessionId` | `--resume <session-id>` (or `--session <id\|path>`) |
| `agy` | Gemini, flat-rate; `gemini-3.1-ultra`, `gemini-3.1-pro-high`, `gemini-3.8-flash` | `--model <model> --effort <effort>` | `--mode plan --sandbox` | `--mode accept-edits --sandbox` | no caller ID; capture `conversation_id` | `--conversation <id>` |

The wrapper scripts (`glm`, `deepseek`, `muse`) configure provider endpoints and credentials, then execute
`claude --model "$MODEL" "$@"`, so `claude`/`glm`/`deepseek`/`muse` share one CLI base: none needs a PTY, all
accept redirected stdin under `-p` (pass `< /dev/null` to prevent hangs), and their startup warning
`claude-code:unrecognized_model` is expected and benign. Never convert a review command into an editing
command unless the user explicitly authorized edits.

> [!CAUTION]
> **Never use the `--bare` flag with any CLI above.** It forces Claude Code to ignore user settings files,
> wiping its memory of the OAuth login token and causing it to fail with "Not logged in".

### Canonical recipes

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
  heuristic, with no human to approve past it in a headless run. Seen
  2026-09-17 on two `glm-5.3` delegates (U1/U2 of
  `dao.stream.v1-retirement.implementation-plan.md`): denied multi-step
  one-liners succeeded once split into a single command, or redirected to a
  file instead of chained. Fix by briefing delegates toward one simple
  command per step — never `--dangerously-skip-permissions` — treating a
  denial as a signal to simplify, not a capability gap to route around.
