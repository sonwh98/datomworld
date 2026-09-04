---
description: Team roster, routing, collaboration, and artifact/session rules for datom.world
---

# DATOM.WORLD SOFTWARE ENGINEERING TEAM

Canonical guide for roster, routing, independence, briefing, artifact/session
protocol, authorization, and review. Coordination is through repository
artifacts, not hidden context. Operational CLI recipes live in
[`orchestrator.md`](./orchestrator.md#review-invocation-reference).

## Roster

The Orchestrator is deliberately absent from this table: it is not routed and has
no primary or fallback, but is whichever coding agent the user starts and assigns
the role to. See [`orchestrator.md`](./orchestrator.md).

| Role                | Primary            | Fallbacks                                                          | Responsibility                                               |
|---------------------|--------------------|--------------------------------------------------------------------|--------------------------------------------------------------|
| Architect           | `claude-fable-5-1` | `gpt-5.6-sol`, `claude-opus-5`                                     | Axioms, invariants, boundaries, architecture                 |
| VM Runtime          | `glm-5.3`          | `claude-sonnet-5`, `gpt-5.6-terra`                                 | CESK, VMs, continuations, macros, loops                      |
| Storage & Indexing  | `glm-5.3`          | `claude-sonnet-5`, `deepseek-v4-pro`                               | B-trees, indexing, DHT, storage, query                       |
| Compiler & AST      | `claude-opus-5`    | `gpt-5.6-sol`, `glm-5.3`, `gpt-5.6-terra`, `qwen/qwen3.8-max`      | AST, lowering, compile-time macros                           |
| Stream & Network    | `claude-opus-5`    | `glm-5.3`, `gpt-5.6-terra`, `gpt-5.6-luna`, `deepseek-v4-pro`      | Streams, framing, concurrency, transports, codecs, RPC       |
| Frontend & Graphics | `gpt-5.6-sol`      | `claude-opus-5`, `gemini-3.8-flash`, `moonshotai/kimi-k2.7-code`   | Events, WebGL/WebGPU, canvas, terminal                       |
| QA & Verification   | `claude-sonnet-5`  | `gemini-3.8-flash`, `gpt-5.4-mini`                                 | TDD, parity, lint, regression                                |
| Routine Review      | `gpt-5.6-sol`      | `gemini-3.8-flash`, `glm-5.3` (non-GLM only), `qwen/qwen3.8-max`   | Correctness, invariants, portability                         |
| Adversarial Review  | `deepseek-v4-pro`  | `gpt-5.6-sol`, `gemini-3.1-pro-high`                               | Independent defect discovery, cross-host challenge           |
| Security Sign-off   | `claude-fable-5-1` | `gpt-5.6-sol`, `claude-opus-5`                                     | Capability boundaries, high-risk review                      |
| Scoped / Subagent   | `gemini-3.8-flash` | `gpt-5.6-terra`, `gpt-5.4-mini`                                    | Bounded searches, edits, docs, lint                          |

Any model may fill any role; promote/demote using representative work, findings,
tests, latency, and cost. `gemini-3.8-flash` supersedes 3.7; `claude-fable-5-1`
supersedes fable-5. Use the verified `claude-sonnet-5` identifier; the reversed
`claude-5-sonnet` form is not recognized by Claude Code. The 2026-08-29 GLM
quota override expired 2026-09-01.

## Routing and independence

Prefer flat subscriptions (`claude`, `agy`, `codex`, `glm`); Command Code is
opportunistic. Reserve Muse/DeepSeek for work worth metered cost. Confirm a model
exists through its CLI and use a listed fallback—never silently substitute a
same-family reviewer.

Every change is reviewed by a different family: GPT→Gemini/Claude/GLM/Qwen,
GLM→GPT/Claude/Gemini/Qwen, Claude→GPT/Gemini/GLM/Qwen, Gemini→GPT/Claude/GLM/Qwen.
Routine review applies only when independent; architectural/security review is
mandatory when the role/risk requires it.

Provider rules: Claude only via `claude`/`agy`; Muse only via
`~/.local/bin/muse` with `muse-spark-1.3-contributor`; never use AGY
`invoke_subagent`; shell out to the listed CLIs. The interactive session is the
actual orchestrator. GLM peak hours are weekdays 14:00–18:00 UTC+8; schedule
large jobs off-peak when possible. Use the review commands and implementation
mode distinctions in [`orchestrator.md`](./orchestrator.md#review-invocation-reference).

## Collaboration contract

The orchestrator owns scope, authorization, verification, consensus, and readiness.
Each task uses tests or an equally precise contract, a timestamped and bounded
role brief when delegated, local verification of the real diff, independent
review, and evidence-backed readiness reporting. Follow the ordered
[`orchestrator workflow`](./orchestrator.md#workflow). Never stage or commit
without user instruction; when authorized, stage only requested files and commit
only staged changes.

Commit subjects use `<type>[(<scope>)]: <lowercase imperative summary>` with types
`docs|feat|fix|refactor|perf|test|build|chore`; optional body explains non-obvious
behavior/invariants. No trailing period, merge exceptions allowed, never add
`Co-Authored-By` (including LLM attribution).

Delegated claims are untrusted: verify artifacts, expected files, actual tests and
assertion counts, and fresh generated CLJD output; read unfiltered output first.
If local tests already pass, ask reviewers to use budget for static analysis rather
than rerun the full suite (security may rerun). See `build-n-test.md`.

## Artifacts and sessions

Keep all artifacts flat under `collab/`:
`<role>-<task>.prompt.md`, `.<sanitized-model>.findings.md`, `.stdout.log`,
`.heartbeat` (optional append-only `.progress.log`). Every prompt starts with:

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

Every report starts with the same Completed-GMT/Local, Coding-Agent, and exact
Session-ID fields. For an interactive orchestrator seat with no delegated CLI
session, record and repeat `Session-ID: not-applicable (interactive seat)`.
For every Claude Code-based CLI (`claude`, `glm`, `deepseek`,
and `muse`), generate the UUID before launch, put it in the prompt, and pass it
with `--session-id`; text output does not expose the ID reliably. `--name` is a
display label, not a session ID. Codex, AGY, and Command Code generate the ID
themselves; their initial prompt records `Session-ID: pending
(provider-generated)`, their structured output captures it, and every report
and follow-up uses the exact captured value. Because the agent cannot know an
ID assigned outside its turn, the orchestrator writes the promoted findings
header with the captured ID. Never record `none` merely because
plain-text output omitted session metadata. `collab/` is append-only, never
staged/committed; never delete or truncate prompts/findings. Artifacts of
committed work are moved, never deleted, into the flat gitignored `archive/`;
that lifecycle is owned by the role that commits, in
[`orchestrator.md`](./orchestrator.md). On reassignment, append a status event
for the prior implementer and an entry for the new implementer; never rewrite
the earlier entry. Promote final responses to
`.findings.md`; `.stdout.log` is only an intermediate capture. Non-trivial
delegates maintain a concise heartbeat. Use actual timestamps; never fabricate
them.

Append reassignment history inside `Implementers:` in this form:

```text
- Status-Event: <timestamp> | Model: <prior-model> | Status: <failed|timed-out|reassigned> | Rationale: <why>
- Model: <new-model> | Assigned: <timestamp> | Status: active | Rationale: <why>
```

Read-only reviewers may share the main tree. Concurrent editors use separate
worktrees from a committed base; uncommitted bases require serialization or
explicit disjoint ownership. Only one process owns the CLJD lane because
`bb test:cljd` writes shared generated output. Auxiliary worktrees lack `collab/`,
so briefs use absolute paths. Never merge/delete worktrees or branches without
user authority.

Quiet output is not failure: inspect process/heartbeat and wait for completion or
an explicit error. Batch complete briefs, reuse sessions for related follow-ups,
start new sessions for unrelated work, and never rely on `--last`.

### Session continuity

Agents preserve conversational context only when a related follow-up resumes
the exact session/conversation/thread ID. Reading earlier prompts, logs,
findings, and diffs reconstructs task context but is not equivalent to resuming
the session.

Before the first invocation of a Claude Code-based CLI (`claude`, `glm`,
`deepseek`, or `muse`), generate and record an ID:

```sh
TASK_SESSION_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
```

Write that exact value into the prompt's `Session-ID:` field, then invoke the
agent with `--session-id "$TASK_SESSION_ID"`. Instruct its report to repeat the
same value. For every correction, clarification, or verification performed by
the same agent, use `--resume "$TASK_SESSION_ID"`; do not start a new named
session. Do not combine `--session-id` and `--resume`.

Claude Code session persistence is enabled by default. Never pass
`--no-session-persistence` when the work may require review, correction, or
follow-up. If an older run failed to record its UUID, recover it from the
provider-specific Claude configuration store by matching the custom title in
the project JSONL, record an append-only provenance correction, and resume that
UUID. Do not treat the process ID, `--name`, log filename, or wrapper name as a
session ID.

Codex, AGY, and Command Code do not accept a caller-selected ID for a new run in
the installed versions. Run them with structured output, capture the generated
ID immediately, and put it in the findings before any follow-up:

```sh
# Codex JSONL: first thread.started event
TASK_SESSION_ID="$(jq -r 'select(.type == "thread.started") | .thread_id' \
  collab/<task>.<model>.stdout.log | head -1)"

# AGY JSON: top-level conversation_id
TASK_SESSION_ID="$(jq -r '.conversation_id' \
  collab/<task>.<model>.stdout.log)"

# Command Code NDJSON: final result (also present on event.run_start)
TASK_SESSION_ID="$(jq -r 'select(.type == "result") | .sessionId' \
  collab/<task>.<model>.stdout.log | tail -1)"
```

Fail the handoff if the extracted value is empty or `null`. Do not use Codex
`--ephemeral`, Command Code `--no-session`, or any provider's non-persistent
mode for follow-up-capable work. Never substitute `--last`, `--continue`, AGY
`-c`, or a display name when an exact ID is available: concurrent runs make
those selectors ambiguous.

Every resumed turn gets a new prompt and output artifact name (for example,
`<task>-r2...`); never redirect a follow-up into the prior append-only log.

Reviewer conversations are the ones most worth resuming. A reviewer that has
already examined a subsystem retains its seams, invariants, and prior findings,
so a re-review costs a delta instead of a cold re-derivation of the same
architecture. Route a later review of the same subsystem back into its existing
conversation whenever that reviewer's family is still independent of the new
change's author; start a fresh conversation only when independence or subject
actually changes.

An ID that a run failed to capture is not lost: recover it from the provider's
session store rather than paying for a cold re-review. AGY names each store
directory after its conversation ID, so the task's own brief locates it:

```sh
grep -l "<task>" ~/.gemini/antigravity-cli/brain/*/.system_generated/logs/transcript.jsonl \
  | sed 's|.*/brain/||; s|/.system_generated.*||'
```

Record a recovered value with an append-only provenance correction, as for a
Claude UUID, and resume it.

| CLI      | Session store                         | New-session ID source                 | Related follow-up                         |
|----------|---------------------------------------|---------------------------------------|-------------------------------------------|
| claude   | `~/.claude`                           | caller UUID via `--session-id`        | `--resume <uuid>`                         |
| glm      | `~/.claude-glm`                       | caller UUID via `--session-id`        | `--resume <uuid>`                         |
| deepseek | `~/.claude-deepseek`                  | caller UUID via `--session-id`        | `--resume <uuid>`                         |
| muse     | `~/.claude-muse`                      | caller UUID via `--session-id`        | `--resume <uuid>`                         |
| codex    | `~/.codex`                            | JSONL `thread.started.thread_id`       | `codex exec resume <id> --json -`          |
| agy      | `~/.gemini/antigravity-cli`           | JSON `conversation_id`                | `--conversation <id>`                     |
| cmd      | `~/.commandcode`                      | NDJSON `result.sessionId`             | `--resume <id>` or `--session <id|path>`  |

## Security

Private repository content may be sent externally only with explicit user
authorization for the exact payload/destination. Invoke from the authorized
agent; consent does not carry over. Pass prompt paths/stdin, never private diffs
or credentials in arguments. Use read-only/plan review and minimum write scope;
never bypass permission checks. Preserve unrelated changes and never expose or
commit tokens/configuration.
