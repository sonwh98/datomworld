---
description: Team roster, role routing, and reviewer-independence rules for datom.world
---

# DATOM.WORLD SOFTWARE ENGINEERING TEAM

Canonical guide for the shared roster, role routing, and reviewer independence.
The Lead Engineering Orchestrator owns coordination, authorization, artifact and
session protocol, verification, and operational CLI recipes in
[`orchestrator.md`](./roles/orchestrator.md).

## Roster

The Orchestrator is deliberately absent from this table: it is not routed and has
no primary or fallback, but is whichever coding agent the user starts and assigns
the role to. See [`orchestrator.md`](./roles/orchestrator.md).

| Role                  | Primary              | Fallbacks                                                                    | Responsibility                                                 |
| --------------------- | -------------------- | ---------------------------------------------------------------------------- | -------------------------------------------------------------- |
| Architect             | `claude-fable-5-1`   | `gpt-6-astra`, `gpt-5.6-sol`, `claude-opus-5`                                | Axioms, invariants, boundaries, architecture                   |
| VM Runtime            | `glm-5.3`            | `claude-sonnet-5`, `gpt-5.6-terra`                                           | CESK, VMs, continuations, macros, loops                        |
| Storage & Indexing    | `glm-5.3`            | `claude-sonnet-5`, `deepseek-v4-pro`                                         | B-trees, indexing, DHT, storage, query                         |
| Compiler & AST        | `claude-opus-5`      | `gpt-6-astra`, `gpt-5.6-sol`, `glm-5.3`, `gpt-5.6-terra`, `qwen/qwen3.8-max` | AST, lowering, compile-time macros                             |
| Stream & Network      | `claude-opus-5`      | `glm-5.3`, `gpt-5.6-terra`, `gpt-5.6-luna`, `deepseek-v4-pro`                | Streams, framing, concurrency, transports, codecs, RPC         |
| Frontend & Graphics   | `gpt-5.6-sol`        | `claude-opus-5`, `gemini-3.8-flash`, `moonshotai/kimi-k2.7-code`             | Events, WebGL/WebGPU, canvas, terminal                         |
| QA & Verification     | `claude-sonnet-5`    | `gemini-3.8-flash`, `glm-5.3-flash`, `gpt-5.4-mini`                          | TDD, parity, lint, regression                                  |
| Routine Review        | `gpt-5.6-sol`        | `glm-5.3`, `claude-opus-5`, `deepseek-v4-pro`, `qwen/qwen3.8-max`,           | Correctness, invariants, portability                           |
| Adversarial Review    | `deepseek-v4-pro`    | `gpt-6-astra`, `gpt-5.6-sol`, `gemini-3.1-pro-high`                          | Independent defect discovery, cross-host challenge             |
| Security Sign-off     | `claude-fable-5-1`   | `gpt-6-astra`, `gpt-5.6-sol`, `claude-opus-5`                                | Capability boundaries, high-risk review                        |
| Scoped / Subagent     | `glm-5.3-flash`      | `gpt-5.6-terra`, `gemini-3.8-flash`, `gpt-5.4-mini`                          | Bounded searches, edits, docs, lint                            |

Any model may fill any role; promote or demote it using representative work,
findings, tests, latency, and cost.

## Role selection and reviewer independence

Select the listed primary when available and use a listed fallback when it is
not; never silently substitute a same-family reviewer.

Every change is reviewed by a different family: GPT→Gemini/Claude/GLM/Qwen,
GLM→GPT/Claude/Gemini/Qwen, Claude→GPT/Gemini/GLM/Qwen, Gemini→GPT/Claude/GLM/Qwen.
Routine review applies only when independent; architectural or security review
is mandatory when the role or risk requires it.

Operational routing constraints and all coordination procedures are defined by
the [`Lead Engineering Orchestrator`](./roles/orchestrator.md).
