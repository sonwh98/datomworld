---
description: Team roster, routing, collaboration, and invocation rules for datom.world
---

# DATOM.WORLD SOFTWARE ENGINEERING TEAM

Canonical guide for assigning, briefing, invoking, reviewing, and recording
agents. Coordination is through repository artifacts, not hidden context.

## Roster

| Role                | Primary            | Fallbacks                                                          | Responsibility                                               |
|---------------------|--------------------|--------------------------------------------------------------------|--------------------------------------------------------------|
| Orchestrator        | `gpt-5.6-sol`      | `gemini-3.1-pro-high`, `claude-5-sonnet`                           | Scope, delegation, consensus, verification, commit readiness |
| Architect           | `claude-fable-5-1` | `gpt-5.6-sol`, `claude-opus-5`                                     | Axioms, invariants, boundaries, architecture                 |
| VM Runtime          | `glm-5.3`          | `claude-5-sonnet`, `gpt-5.6-terra`                                 | CESK, VMs, continuations, macros, loops                      |
| Storage & Indexing  | `glm-5.3`          | `claude-5-sonnet`, `deepseek-v4-pro`                               | B-trees, indexing, DHT, storage, query                       |
| Compiler & AST      | `claude-opus-5`    | `gpt-5.6-sol`, `glm-5.3`, `gpt-5.6-terra`, `qwen/qwen3.8-max`      | AST, lowering, compile-time macros                           |
| Stream & Network    | `claude-opus-5`    | `glm-5.3`, `gpt-5.6-terra`, `gpt-5.6-luna`, `deepseek-v4-pro`      | Streams, framing, concurrency, transports, codecs, RPC       |
| Frontend & Graphics | `gpt-5.6-sol`      | `claude-opus-5`, `gemini-3.8-flash`, `moonshotai/kimi-k2.7-code`   | Events, WebGL/WebGPU, canvas, terminal                       |
| QA & Verification   | `claude-5-sonnet`  | `gemini-3.8-flash`, `gpt-5.4-mini`                                 | TDD, parity, lint, regression                                |
| Routine Review      | `gpt-5.6-sol`      | `gemini-3.8-flash`, `glm-5.3` (non-GLM only), `qwen/qwen3.8-max`   | Correctness, invariants, portability                         |
| Adversarial Review  | `deepseek-v4-pro`  | `gpt-5.6-sol`, `gemini-3.1-pro-high`                               | Independent defect discovery, cross-host challenge           |
| Security Sign-off   | `claude-fable-5-1` | `gpt-5.6-sol`, `claude-opus-5`                                     | Capability boundaries, high-risk review                      |
| Scoped / Subagent   | `gemini-3.8-flash` | `gpt-5.6-terra`, `gpt-5.4-mini`                                    | Bounded searches, edits, docs, lint                          |

Any model may fill any role; promote/demote using representative work, findings,
tests, latency, and cost. `gemini-3.8-flash` supersedes 3.7; `claude-fable-5-1`
supersedes fable-5. The 2026-08-29 GLM quota override expired 2026-09-01.

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
Session-ID: <exact id or none (reason)>
# Task: <name>
Role: <constant role>
Implementers:
- Model: <model> | Assigned: <timestamp> | Status: active | Rationale: <why>
```

Every report starts with the same Completed-GMT/Local, Coding-Agent, and exact
Session-ID fields. `collab/` is append-only, never staged/committed; never delete
or truncate prompts/findings. On reassignment update status and append an
implementer. Promote final responses to `.findings.md`; `.stdout.log` is only an
intermediate capture. Non-trivial delegates maintain a concise heartbeat. Use
actual timestamps; never fabricate them.

Read-only reviewers may share the main tree. Concurrent editors use separate
worktrees from a committed base; uncommitted bases require serialization or
explicit disjoint ownership. Only one process owns the CLJD lane because
`bb test:cljd` writes shared generated output. Auxiliary worktrees lack `collab/`,
so briefs use absolute paths. Never merge/delete worktrees or branches without
user authority.

Quiet output is not failure: inspect process/heartbeat and wait for completion or
an explicit error. Batch complete briefs, reuse sessions for related follow-ups,
start new sessions for unrelated work, and never rely on `--last`.

| CLI    | Store            | Resume                                 |
|--------|------------------|----------------------------------------|
| claude | `~/.claude`      | `--resume <id>`                        |
| glm    | `~/.claude-glm`  | `--resume <id>`                        |
| cmd    | `~/.commandcode` | `--resume <name-or-id>`                |
| codex  | `~/.codex`       | `codex exec [-s <mode>] resume <id> -` |
| muse   | `~/.claude-muse` | `--resume <id>`                        |

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

```sh
# Claude
claude --model <model> --permission-mode plan --tools Read \
  --output-format text -p "Read <prompt> and complete it now."

# Gemini / AGY
agy --model <model> --effort <effort> --mode plan --sandbox \
  --print-timeout 5m --output-format text -p "Read <prompt> and complete it now."

# GLM (PTY; keep -p last; do not redirect stdin)
GLM_MODEL=glm-5.3 script -q /dev/null ~/.local/bin/glm \
  --name <task> --bare --permission-mode plan --allowed-tools Read \
  --output-format text -p "Read <prompt> and complete it now." > collab/<task>.glm-5.3.stdout.log

# Codex review (stdin; read-only)
codex exec -m gpt-5.6-sol -s read-only - < <prompt> > collab/<task>.gpt-5.6-sol.stdout.log

# Muse (PTY; keep -p last; do not redirect stdin)
MUSE_MODEL=muse-spark-1.3-contributor script -q /dev/null ~/.local/bin/muse \
  --name <task> --bare --permission-mode plan --allowed-tools Read \
  --output-format text -p "Read <prompt> and complete it now." > collab/<task>.muse-spark-1.3-contributor.stdout.log

# Command Code
cmd -p -m <model> --plan --output-format text < <prompt> > collab/<task>.<model>.stdout.log

# DeepSeek (close stdin; quiet startup is normal)
~/.local/bin/deepseek --bare --permission-mode plan --allowed-tools Read \
  --no-session-persistence --output-format text \
  -p "Read <prompt> and complete it now." < /dev/null
```

AGY language-server bind/log failures are host sandbox restrictions: rerun the
identical command through the host tool with narrowly scoped escalation and keep
AGY `--sandbox`/`--mode accept-edits`; never add `--dangerously-skip-permissions`.
Claude `Not logged in` and Codex app-server `Operation not permitted` under the
default command sandbox are likewise host diagnostics: retry via host escalation
with the documented narrow prefixes, preserving their own read-only/write modes.
GLM and Muse `claude-code:unrecognized_model` startup warnings are expected for
their wrappers; verify the resulting artifact before declaring failure. DeepSeek
may also be quiet for several minutes; do not kill it absent process exit or an
explicit error.
