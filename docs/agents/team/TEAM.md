---
description: Team roster, routing, collaboration, and invocation rules for datom.world
---

# DATOM.WORLD SOFTWARE ENGINEERING TEAM

Canonical guide for assigning, briefing, invoking, reviewing, and recording
agents. Coordination is through repository artifacts, not hidden context.

## Roster

| Role                | Primary            | Fallbacks                                                          | Responsibility                                               |
|---------------------|--------------------|--------------------------------------------------------------------|--------------------------------------------------------------|
| Orchestrator        | `gpt-5.6-sol`      | `claude-sonnet-5`                                                  | Scope, delegation, consensus, verification, commit readiness |
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
quota override expired 2026-09-01. `gemini-3.1-pro-high` was removed from the
Orchestrator fallbacks on 2026-09-04: it runs only through sandboxed AGY, which
cannot execute this host's JVM, so it cannot verify a test result — see the
pitfall in the invocation reference before restoring it.

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
large jobs off-peak when possible.

## Collaboration contract

The orchestrator owns scope, authorization, verification, consensus, and readiness.

1. Define behavior with failing tests or an equally precise contract.
2. Create a timestamped brief from the role template; bound files and checks.
3. Inspect the real diff and verify locally.
4. Obtain independent review; reconcile findings and resume the same reviewer for corrections.
5. Report risks/readiness. Never stage/commit without user instruction; when authorized,
   stage only requested files and commit only staged changes.

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
Session-ID: <exact caller UUID | pending (provider-generated)>
# Task: <name>
Role: <constant role>
Implementers:
- Model: <model> | Assigned: <timestamp> | Status: active | Rationale: <why>
```

Every report starts with the same Completed-GMT/Local, Coding-Agent, and exact
Session-ID fields. For every Claude Code-based CLI (`claude`, `glm`, `deepseek`,
and `muse`), generate the UUID before launch, put it in the prompt, and pass it
with `--session-id`; text output does not expose the ID reliably. `--name` is a
display label, not a session ID. Codex, AGY, and Command Code generate the ID
themselves; their initial prompt records `Session-ID: pending
(provider-generated)`, their structured output captures it, and every report
and follow-up uses the exact captured value. Because the agent cannot know an
ID assigned outside its turn, the orchestrator writes the promoted findings
header with the captured ID. Never record `none` merely because
plain-text output omitted session metadata. `collab/` is append-only, never
staged/committed; never delete or truncate prompts/findings. On reassignment
update status and append an implementer. Promote final responses to
`.findings.md`; `.stdout.log` is only an intermediate capture. Non-trivial
delegates maintain a concise heartbeat. Use actual timestamps; never fabricate
them.

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

Before the first invocation, generate and record an ID:

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

## Invocation reference

Prompts contain authorized paths, not source text. Headless plan agents must be
told to produce the complete deliverable without waiting for a human.

`claude`, `glm`, `deepseek` and `muse` are one Claude Code CLI behind different
model wrappers, so none of them needs a PTY and all accept redirected stdin
under `-p`; the `script -q /dev/null` prefix once carried here was unnecessary.
Verified 2026-09-04 against `glm`, which ran headless with no PTY and again with
`< /dev/null`; the `deepseek` recipe below already closes stdin without a PTY.
Their `claude-code:unrecognized_model` startup warnings follow from that shared
basis.

```sh
# Claude
claude --model <model> --session-id <uuid> --name <task> \
  --permission-mode plan --tools Read \
  --output-format text -p "Read <prompt> and complete it now."

# Claude follow-up: preserve the original model conversation
claude --resume <uuid> --permission-mode plan --tools Read \
  --output-format text -p "Read <follow-up-prompt> and complete it now."

# Gemini / AGY
agy --model <model> --effort <effort> --mode plan --sandbox \
  --print-timeout 5m --output-format json \
  -p "Read <prompt> and complete it now." > collab/<task>.<model>.stdout.log

# AGY follow-up: preserve the generated conversation ID
agy --conversation <id> --model <model> --effort <effort> \
  --mode plan --sandbox --print-timeout 5m --output-format json \
  -p "Read <follow-up-prompt> and complete it now." > collab/<task>-r<n>.<model>.stdout.log

# GLM (Claude Code-based; keep -p last)
GLM_MODEL=glm-5.3 ~/.local/bin/glm \
  --session-id <uuid> --name <task> --bare \
  --permission-mode plan --allowed-tools Read \
  --output-format text -p "Read <prompt> and complete it now." > collab/<task>.glm-5.3.stdout.log

# GLM follow-up: keep GLM_MODEL and the original UUID
GLM_MODEL=glm-5.3 ~/.local/bin/glm \
  --resume <uuid> --bare --permission-mode plan --allowed-tools Read \
  --output-format text -p "Read <follow-up-prompt> and complete it now." > collab/<task>-r<n>.glm-5.3.stdout.log

# Codex review (stdin; read-only; JSONL captures thread_id)
codex exec -m gpt-5.6-sol -s read-only --json - < <prompt> \
  > collab/<task>.gpt-5.6-sol.stdout.log

# Codex follow-up: `resume` exposes no sandbox flag. Resume only a thread that
# was created read-only; if its effective policy cannot be verified, start a
# new read-only review thread instead of implying an override here.
codex exec resume <id> --json - \
  < <follow-up-prompt> > collab/<task>-r<n>.gpt-5.6-sol.stdout.log

# Muse (Claude Code-based; keep -p last)
MUSE_MODEL=muse-spark-1.3-contributor ~/.local/bin/muse \
  --session-id <uuid> --name <task> --bare \
  --permission-mode plan --allowed-tools Read \
  --output-format text -p "Read <prompt> and complete it now." > collab/<task>.muse-spark-1.3-contributor.stdout.log

# Muse follow-up: keep MUSE_MODEL and the original UUID
MUSE_MODEL=muse-spark-1.3-contributor ~/.local/bin/muse \
  --resume <uuid> --bare --permission-mode plan --allowed-tools Read \
  --output-format text -p "Read <follow-up-prompt> and complete it now." > collab/<task>-r<n>.muse-spark-1.3-contributor.stdout.log

# Command Code
cmd -p -m <model> --plan --output-format json < <prompt> \
  > collab/<task>.<model>.stdout.log

# Command Code follow-up: preserve the generated session ID
cmd --resume <id> -p -m <model> --plan --output-format json \
  < <follow-up-prompt> > collab/<task>-r<n>.<model>.stdout.log

# DeepSeek (close stdin; quiet startup is normal)
~/.local/bin/deepseek --bare --permission-mode plan --allowed-tools Read \
  --session-id <uuid> --name <task> --output-format text \
  -p "Read <prompt> and complete it now." < /dev/null

# DeepSeek follow-up: close stdin and resume the original UUID
~/.local/bin/deepseek --resume <uuid> --bare --permission-mode plan \
  --allowed-tools Read --output-format text \
  -p "Read <follow-up-prompt> and complete it now." < /dev/null
```

AGY language-server bind/log failures are host sandbox restrictions: rerun the
identical command through the host tool with narrowly scoped escalation and keep
AGY `--sandbox`/`--mode accept-edits`; never add `--dangerously-skip-permissions`.
Claude `Not logged in` and Codex app-server `Operation not permitted` under the
default command sandbox are likewise host diagnostics: retry via host escalation
with the documented narrow prefixes, preserving their own read-only/write modes.
GLM and Muse `claude-code:unrecognized_model` startup warnings are expected for
their wrappers; verify the resulting artifact before declaring failure. AGY in
`--mode plan` may answer a headless brief with a plan artifact and a request for
approval, exiting `SUCCESS` with no deliverable; a response that promises a
verdict rather than stating one is an unfinished turn, so resume that
conversation instructing it to answer directly instead of accepting the promise.
A sandboxed AGY could not execute this host's JVM: `clojure` died with `java:
Operation not permitted` when probed 2026-09-04 under `--mode plan --sandbox`,
which does read files, run read-only shell, and write files even outside the
repository; by AGY's own account the `BypassSandbox` that would lift the block
needs an approval no headless `-p` run can obtain. Unlike the host diagnostics
above, no host escalation reaches inside a delegate's own session, so the
orchestrator must run such suites itself. Such an agent can therefore appear to
be verifying while unable to check any Clojure test claim it passes on: give it
static analysis, never a deliverable that depends on running tests, and never
the Orchestrator seat.
DeepSeek may also be quiet for several minutes; do not kill it absent process
exit or an explicit error.
