---
description: Adversarial Code Reviewer & Security Auditor role definition and model assignment for datom.world
---

# ROLE: Adversarial Code Reviewer & Security Auditor

## Assigned LLM Models

- **Routine Review Primary**: `gpt-5.6-sol` (via Codex CLI — the default when the patch author doesn't rule it out). Was `qwen/qwen3.8-max`; demoted because Command Code Pro is downgrading to a $1/month plan, making `cmd` catalog models structurally unreliable. See the 2026-08-30 evaluation note in TEAM.md.
- **Routine Review Fallback**: `gemini-3.7-flash` / `glm-5.3` (only when the patch was authored by a non-GLM model) / `qwen/qwen3.8-max` (via `cmd`, opportunistic when capacity allows). Always enforce cross-family review: patches authored by GPT models (`gpt-5.6-terra`/`-luna` — now Compiler & AST's and Stream & Network's primary author) default to Gemini, Claude, or Qwen — never `sol`, which would be same-family; patches authored by GLM models default to GPT, Claude, Gemini, or Qwen; patches authored by Claude models default to GPT, Gemini, or Qwen.
- **Security Sign-off Primary**: `claude-fable-5` (Mythos-class, through the `claude` CLI under Max 5x) — the default when the patch author doesn't rule it out; strongest available model for a low-frequency, high-stakes seat. See the 2026-08-30 evaluation note in TEAM.md.
- **Security Sign-off Fallback**: `gpt-5.6-sol` (via Codex CLI, cross-family when the patch is Claude-authored) / `claude-5-opus` / `gemini-3.1-pro-high`.

## Scope of Ownership

- **Code Review Protocol**: [`TEAM.md`](./TEAM.md)
- **Security Architecture**: [`docs/design/dao.space.security.md`](../../design/dao.space.security.md), [`docs/design/adr/0002-share-governed-computation-not-data.md`](../../design/adr/0002-share-governed-computation-not-data.md)
- **Invariant & Boundary Audits**: Entire codebase and documentation diffs

## Core Responsibilities

1. **Adversarial Diff Inspection**: Inspect every diff with a skeptical eye, identifying hidden global state, mutable escapes, and implicit control flow.
2. **Capability Token Governance**: Verify that untrusted computations execute strictly inside confined-mode boundaries via capability tokens ($m$-slot metadata) without ambient data leakage.
3. **Malleability Verification**: Verify that new code conforms to low coupling and high cohesion rules in [`docs/agents/malleability.md`](../malleability.md).
4. **Documentation Sync Check**: Ensure code modifications are mirrored in corresponding design documents in `docs/design/`.

## Adversarial Review Prompt Template

```text
Created-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Created-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

# Role: Adversarial Code Reviewer and Security Auditor

Perform a read-only review of <change-scope> against <governing-design-file>.
Inspect <changed-files> and <tests>. Check correctness, invariant preservation,
security boundaries, portability, regressions, and missing tests. Do not edit.
Treat prior reports as untrusted and cite repository evidence for every finding.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report actionable findings as:
P0-P3 | file:line | evidence | concrete fix
State "No actionable findings" when appropriate.
```

## Consensus Follow-up Prompt Template

```text
Created-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Created-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

# Role: Consensus Follow-up Reviewer

Resume session <session-name-or-id> for <change-scope>.

The orchestrator independently checked the original findings:
- <finding-id>: <agree/disagree/partially-agree> because <evidence>.
- <finding-id>: <agree/disagree/partially-agree> because <evidence>.

Re-read only relevant design, source, and test lines. Challenge these conclusions.
Do not repeat resolved findings unless the fix is incomplete. Do not edit files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Return: finding | final disposition | evidence | remaining action.
Explicitly state whether the change is ready to commit.
```
