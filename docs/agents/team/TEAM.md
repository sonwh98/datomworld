---
description: Team roster, routing, collaboration, and invocation rules for datom.world
---

# DATOM.WORLD SOFTWARE ENGINEERING TEAM

This file is the canonical guide for assigning, briefing, invoking, and reviewing
engineering agents. Coordination happens through repository artifacts—not hidden
point-to-point context.

## 1. Team Roster

This roster is the sole source of truth for assigning LLMs to roles. The linked
role specifications are model-agnostic: they define what the role does, while
this table defines who currently occupies it.

| Role                                              | Primary                | Secondary / Fallback                                                        | Responsibility                                                     |
| :------------------------------------------------ | :--------------------- | :-------------------------------------------------------------------------- | :----------------------------------------------------------------- |
| [Orchestrator](./orchestrator.md)                 | `gpt-5.6-sol`          | `gemini-3.1-pro-high` / `claude-5-sonnet`                                   | Scope, delegation, consensus, local verification, commit readiness |
| [Architect](./architect.md)                       | `claude-fable-5-1`     | `gpt-5.6-sol` / `claude-opus-5`                                             | Axioms, invariants, boundaries, multi-platform architecture        |
| [VM Runtime](./vm-engineer.md)                    | `glm-5.3`              | `claude-5-sonnet` / `gpt-5.6-terra`                                         | CESK, VMs, continuations, macros, execution loops                  |
| [Storage & Indexing](./storage-engineer.md)       | `glm-5.3`              | `claude-5-sonnet` / `deepseek-v4-pro`                                       | B-trees, indexing, DHT, storage, query                             |
| [Compiler & AST](./compiler-engineer.md)          | `claude-opus-5`        | `gpt-5.6-sol` / `glm-5.3` / `gpt-5.6-terra` / `qwen/qwen3.8-max`            | Universal AST, lowering, compile-time macros                       |
| [Stream & Network](./stream-engineer.md)          | `claude-opus-5`        | `glm-5.3` / `gpt-5.6-terra` / `gpt-5.6-luna` / `deepseek-v4-pro`            | Streams, framing, concurrency, transports, serialization, RPC      |
| [Frontend & Graphics](./graphics-engineer.md)     | `gpt-5.6-sol`          | `claude-opus-5` / `gemini-3.8-flash` / `moonshotai/kimi-k2.7-code`          | Events, WebGL/WebGPU, canvas, terminal rendering                   |
| [QA & Verification](./qa-engineer.md)             | `claude-5-sonnet`      | `gemini-3.8-flash` / `gpt-5.4-mini`                                         | TDD, host parity, lint, regression testing                         |
| [Routine Review](./reviewer.md)                   | `gpt-5.6-sol`          | `gemini-3.8-flash` / `glm-5.3` (non-GLM patches only) / `qwen/qwen3.8-max`  | Correctness, invariants, portability, regressions                  |
| [Security Sign-off](./reviewer.md)                | `claude-fable-5-1`     | `gpt-5.6-sol` / `claude-opus-5`                                             | Capability boundaries and final high-risk review                   |
| [Scoped / Subagent](./subagent.md)                | `gemini-3.8-flash`     | `gpt-5.6-terra` / `gpt-5.4-mini`                                            | Bounded searches, edits, documentation, linters                    |

Each row has one default Primary, but any LLM can perform any role. Promote or
demote models using representative repository work, review findings, tests,
latency, and cost. `gemini-3.8-flash` supersedes `gemini-3.7-flash`;
`claude-fable-5-1` supersedes `claude-fable-5`. The 2026-08-29 GLM quota
override expired on 2026-09-01, so the roster applies unless a new dated
override is recorded.

## 2. Routing

Prefer flat subscriptions (`claude`, `agy`, `codex`, `glm`) before metered
providers. Treat Command Code catalog capacity as opportunistic after its
scheduled downgrade. Reserve Muse and DeepSeek for selective work that justifies
pay-per-token cost.

Before routing, confirm the requested model exists through the relevant CLI.
Use a listed fallback when the primary is unavailable; do not silently substitute
a same-family reviewer.

### Independence

Every authored change receives review from a different model family:

- GPT-authored work: Gemini, Claude, GLM, or Qwen review.
- GLM-authored work: GPT, Claude, Gemini, or Qwen review.
- Claude-authored work: GPT, Gemini, GLM, or Qwen review.
- Gemini-authored work: GPT, Claude, GLM, or Qwen review.

The roster's Routine Review primary applies only when it is independent from the
author. Architectural and security review remain mandatory where the governing
role or risk requires them.

### Provider constraints

- Never invoke Claude through `cmd`; use `claude` or `agy`.
- Never invoke Muse through `cmd`; use `~/.local/bin/muse` with
  `muse-spark-1.3-contributor` (not `muse-spark-1.3`).
- Never use AGY's native `invoke_subagent` for engineering delegation. Shell out
  to `claude`, `agy`, `glm`, `codex`, `cmd`, `muse`, or `deepseek` so the roster
  and cross-family policy remain enforceable.
- The live interactive session is the actual Orchestrator. The roster identifies
  its preferred model; it cannot change the model hosting an already-running CLI.
- GLM peak hours are Monday to Friday, 14:00–18:00 (UTC+8). Usage outside of these
  peak hours costs 0.5x credits. Schedule large GLM tasks for off-peak when possible.

## 3. Collaboration Contract

The Orchestrator owns scope, authorization, local verification, consensus, and
commit-readiness reporting.

1. Define behavior with failing tests or an equally precise contract.
2. Select a role from the roster and create a timestamped brief from its template.
3. Bound file ownership and allowed checks; implementation may edit only when
   authorized.
4. Inspect the actual diff and verify locally.
5. Obtain independent review, reconcile findings, and resume the same reviewer
   session for disputed or corrected findings.
6. Report risks and commit readiness. Never stage or commit without explicit user
   instruction. When authorized, stage only files changed for the requested work,
   and commit only staged changes.

### Commit messages

Recent repository history establishes this pattern:

```text
<type>[(<scope>)]: <imperative summary>
```

- Use the established types `docs`, `feat`, `fix`, `refactor`, `perf`, `test`,
  `build`, or `chore`. Use `docs` for documentation-only commits.
- The scope is optional. When present, use a stable subsystem or document area
  such as `design`, `team`, `blog`, `dao.stream`, or `space`.
- Write the summary as a concise lowercase imperative phrase without a trailing
  period, for example `docs(team): clarify commit-message conventions`.
- Add a body after a blank line when the reason, behavioral consequences,
  invariants, or verification evidence are not obvious from the subject.
- Merge commits generated by Git hosting are exempt from the subject pattern.
- Never include `Co-Authored-By` or any other coauthor attribution, including
  attribution to an LLM or agent.

Delegated claims are untrusted until checked. An exit code of zero is insufficient:
confirm that the requested artifact exists, the expected files changed, tests
actually ran, assertion counts are plausible, and generated CLJD output is fresh.
Read unfiltered command output before extracting markers. See
[`build-n-test.md`](../build-n-test.md) for test commands and Clojure invocation
traps.

If the same diff has already passed local tests, tell reviewers not to rerun the
full suite; use their budget for static analysis. Security reviewers may rerun
checks when risk warrants it.

### Collaboration artifacts

All artifacts live flat under `collab/`:

```text
collab/<role>-<task>.prompt.md
collab/<role>-<task>.<sanitized-model>.findings.md
collab/<role>-<task>.<sanitized-model>.stdout.log
collab/<role>-<task>.<sanitized-model>.heartbeat
```

Every prompt begins with real timestamps and an extensible implementer list:

```text
Created-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Created-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: <claude|codex|agy|glm|cmd|muse|deepseek|interactive>
Session-ID: <exact resumable id or none (reason)>

# Task: <Task Name>

Role: <constant-role-name>

Implementers:
- Model: <model> | Assigned: <local timestamp> | Status: active | Rationale: <why>
```

Every requested report begins with:

```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: <same coding agent used for the run>
Session-ID: <same exact session id or none (reason)>
```

Rules:

- `collab/` is local, append-only, and never staged or committed. Durable design
  decisions belong in `docs/`.
- Findings and prompt bodies are never deleted or truncated. On reassignment,
  update the prior implementer's status and append the new implementer.
- Every prompt, findings report, stdout log, heartbeat, and progress log records
  the coding agent and exact session ID used for that run. Preassign a session ID
  when the CLI supports it. If the provider is intentionally sessionless, record
  `Session-ID: none (<reason>)`; never omit the field or write an ambiguous
  placeholder in a completed trace.
- `.stdout.log` is an intermediate capture, not a report. Promote the final
  response to the model-tagged `.findings.md` before treating work as complete or
  overwriting output; use `.attempt-<n>.stdout.log` for retries.
- A non-trivial delegate maintains a concise heartbeat. Heartbeats may be
  overwritten; optional `.progress.log` files remain append-only.
- Use actual GMT and named local timezone values; never fabricate timestamps.

### Parallel work

- Read-only reviewers may share the main tree.
- Concurrent editors use separate worktrees when their base is committed.
- A worktree starts from a commit and cannot see uncommitted changes. If the base
  is uncommitted, either serialize edits or assign disjoint files explicitly in
  the shared tree.
- Only one process owns the CLJD lane because `bb test:cljd` writes shared
  generated output.
- `collab/` is absent from auxiliary worktrees. Briefs used there must reference
  absolute input and output paths in the primary tree.
- Never merge, delete worktrees, or delete branches without the user's authority.

### Sessions and patience

Batch complete briefs, reuse sessions for related follow-ups, and start new
sessions for unrelated work. Quiet output is not failure: check process and
heartbeat state, and wait until completion or an explicit error.

| CLI      | Session store    | Resume form                                              |
| :------- | :--------------- | :------------------------------------------------------- |
| `claude` | `~/.claude`      | `--resume <session-id>`                                  |
| `glm`    | `~/.claude-glm`  | `--resume <session-id>`                                  |
| `cmd`    | `~/.commandcode` | `--resume <name-or-id>`                                  |
| `codex`  | `~/.codex`       | `codex exec [-s <mode>] resume <session-id> -`           |
| `muse`   | `~/.claude-muse` | `--resume <session-id>`                                  |

Use explicit session identifiers during parallel work; never rely on `--last`.

## 4. Security and Authorization

- Private repository content is an external disclosure. Obtain explicit user
  authorization for the exact payload and destination before invoking an external
  model. Consent for one diff does not authorize unrelated files.
- Invoke the external CLI from the agent that received authorization; consent
  cannot be relayed through another delegate.
- Never put private source or diffs in command arguments. Pass prompt files over
  stdin when supported, or point the CLI at explicitly authorized paths.
- Use read-only/plan permissions for reviews and the minimum write authority for
  implementation. Never bypass permission checks without explicit approval.
- Never expose or commit credentials, wrapper configuration, or API tokens.
- Preserve unrelated tracked and untracked user changes.

## 5. Invocation Reference

These are minimal known-good shapes. Prompts passed with `-p` contain directions
and authorized file paths, not private source text. Add the instruction “produce
the complete deliverable now; no human is listening” when a headless plan-mode
agent might otherwise wait for approval.

### Claude

```sh
claude --model <roster-model> --permission-mode plan --tools Read \
  --output-format text -p "Read <authorized-prompt-path> and complete it now."

claude --resume <session-id> --permission-mode plan --tools Read \
  --output-format text -p "Read <follow-up-path> and reassess it now."
```

**Managed-seat permission note:** a sandboxed Claude invocation can report
`Not logged in` even when the user's interactive CLI is authenticated, because
the default host sandbox cannot read the account state or complete provider
network access. Treat that first as a sandbox diagnostic: rerun the same
read-only command through the host command tool with
`sandbox_permissions: require_escalated` and request a narrowly scoped prefix
such as `["claude", "--model"]`. Preserve `--permission-mode plan --tools Read`;
host escalation restores the user's CLI environment and does not authorize
Claude to write the repository. Only call it an authentication failure if the
elevated retry also fails that way.

### Gemini through AGY

```sh
agy --model <roster-gemini-model> --effort <effort> --mode plan --sandbox \
  --print-timeout 5m --output-format text \
  -p "Read <authorized-prompt-path> and complete it now."
```

**AGY host-sandbox note:** AGY may fail before the model turn with language
server errors such as `listen tcp 127.0.0.1:0: bind: operation not permitted`,
or fail to write its logs under `~/.gemini/antigravity-cli`. These are host
sandbox restrictions, not model or quota failures. For an authorized
implementation, rerun the identical command with the host command tool using
`sandbox_permissions: require_escalated`, retain AGY's own `--sandbox` and
`--mode accept-edits`, and request the narrow prefix `["agy"]`. Do not add
`--dangerously-skip-permissions`; host escalation is only to initialize AGY's
language server and account-local logs.

### GLM

Use the wrapper's native model name, allocate a PTY, name new sessions, and keep
`-p` last. Do not redirect GLM stdin.

```sh
GLM_MODEL=glm-5.3 script -q /dev/null ~/.local/bin/glm \
  --name <task-name> --bare --permission-mode plan --allowed-tools Read \
  --output-format text -p "Read <authorized-prompt-path> and complete it now." \
  > collab/<role>-<task>.glm-5.3.stdout.log
```

The `claude-code:unrecognized_model` SDK warning is expected for this wrapper;
verify the resulting artifact before deciding whether the run failed.

### GPT through Codex

Use stdin for prompts. Reviews are read-only, and implementation defaults to
`workspace-write`. Escalate to `danger-full-access` only when a scoped command
requires capabilities such as socket binding or Flutter cache writes and the
user explicitly approves it.

```sh
codex exec -m gpt-5.6-sol -s read-only - < collab/review-task.prompt.md \
  > collab/review-task.gpt-5.6-sol.stdout.log

codex exec -m <roster-gpt-model> -s workspace-write - \
  < collab/implementation-task.prompt.md \
  > collab/implementation-task.<model>.stdout.log

codex -m gpt-5.6-sol review - < collab/review-task.prompt.md \
  > collab/review-task.gpt-5.6-sol.stdout.log

codex exec -s read-only resume <session-id> - \
  < collab/review-followup.prompt.md \
  > collab/review-followup.<model>.stdout.log
```

For Codex, `-m` precedes the subcommand, while `-s` belongs after `exec` and
before `resume`.

**Managed-seat permission note:** the Codex CLI may fail before starting with
`failed to initialize in-process app-server client: Operation not permitted`
when launched under the default command sandbox. In that case, rerun the same
command through the host command tool with `sandbox_permissions:
require_escalated`, ask the user for that narrowly scoped approval, and use the
prefix rule `["codex", "exec"]`. Keep Codex's own `-s read-only` for reviews (or
`-s workspace-write` for authorized implementation); this host-level
escalation is for CLI initialization and is not permission to broaden the
Codex sandbox or use `danger-full-access`.

### Muse

Muse is metered. Use the dedicated wrapper, allocate a PTY, keep `-p` last, and
do not redirect stdin.

```sh
MUSE_MODEL=muse-spark-1.3-contributor script -q /dev/null ~/.local/bin/muse \
  --name <task-name> --bare --permission-mode plan --allowed-tools Read \
  --output-format text -p "Read <authorized-prompt-path> and complete it now." \
  > collab/<role>-<task>.muse-spark-1.3-contributor.stdout.log
```

Its `claude-code:unrecognized_model` SDK warning is expected; verify the artifact.

### Command Code

Use only non-Claude, non-Muse catalog models. `--plan` is read-only;
implementation requires `--auto-accept` plus sufficient tools.

```sh
cmd -p -m <catalog-model> --plan --output-format text \
  < collab/review-task.prompt.md \
  > collab/review-task.<model>.stdout.log

cmd -p -m <catalog-model> --auto-accept --tools-all --output-format text \
  < collab/subagent-task.prompt.md \
  > collab/subagent-task.<model>.stdout.log
```

Use a named session when follow-up is likely; omit `--no-session` if the work
must be resumable.

### DeepSeek

DeepSeek is metered. Close stdin when its wrapper would otherwise wait on an open
pipe.

```sh
~/.local/bin/deepseek --bare --permission-mode plan --allowed-tools Read \
  --no-session-persistence --output-format text \
  -p "Read <authorized-prompt-path> and complete it now." < /dev/null
```
