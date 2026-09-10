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

+---------------------+--------------------+------------------------------------------------------------------------------+-------------------------------------------------------+
| Role                | Primary            | Fallbacks                                                                    | Responsibility                                        |
+=====================+====================+==============================================================================+=======================================================+
| Architect           | `claude-fable-5-1` | `gpt-6-astra`, `gpt-5.6-sol`, `claude-opus-5`                                | Axioms, invariants, boundaries, architecture          |
+---------------------+--------------------+------------------------------------------------------------------------------+-------------------------------------------------------+
| VM Runtime          | `glm-5.3`          | `claude-sonnet-5`, `gpt-5.6-terra`                                           | CESK, VMs, continuations, macros, loops               |
+---------------------+--------------------+------------------------------------------------------------------------------+-------------------------------------------------------+
| Storage & Indexing  | `glm-5.3`          | `claude-sonnet-5`, `deepseek-v4-pro`                                         | B-trees, indexing, DHT, storage, query                |
+---------------------+--------------------+------------------------------------------------------------------------------+-------------------------------------------------------+
| Compiler & AST      | `claude-opus-5`    | `gpt-6-astra`, `gpt-5.6-sol`, `glm-5.3`, `gpt-5.6-terra`, `qwen/qwen3.8-max` | AST, lowering, compile-time macros                    |
+---------------------+--------------------+------------------------------------------------------------------------------+-------------------------------------------------------+
| Stream & Network    | `claude-opus-5`    | `glm-5.3`, `gpt-5.6-terra`, `gpt-5.6-luna`, `deepseek-v4-pro`                | Streams, framing, concurrency, transports, codecs,    |
|                     |                    |                                                                              | RPC                                                   |
+---------------------+--------------------+------------------------------------------------------------------------------+-------------------------------------------------------+
| Frontend & Graphics | `gpt-5.6-sol`      | `claude-opus-5`, `gemini-3.8-flash`, `moonshotai/kimi-k2.7-code`             | Events, WebGL/WebGPU, canvas, terminal                |
+---------------------+--------------------+------------------------------------------------------------------------------+-------------------------------------------------------+
| QA & Verification   | `claude-sonnet-5`  | `gemini-3.8-flash`, `glm-5.3-flash`, `gpt-5.4-mini`, `deepseek-flash`        | TDD, parity, lint, regression                         |
+---------------------+--------------------+------------------------------------------------------------------------------+-------------------------------------------------------+
| Routine Review      | `gpt-5.6-sol`      | `glm-5.3`, `claude-opus-5`, `deepseek-v4-pro`, `qwen/qwen3.8-max`,           | Correctness, invariants, portability                  |
+---------------------+--------------------+------------------------------------------------------------------------------+-------------------------------------------------------+
| Adversarial Review  | `deepseek-v4-pro`  | `gpt-6-astra`, `gpt-5.6-sol`, `gemini-3.1-pro-high`                          | Independent defect discovery, cross-host challenge    |
+---------------------+--------------------+------------------------------------------------------------------------------+-------------------------------------------------------+
| Security Sign-off   | `claude-fable-5-1` | `gpt-6-astra`, `gpt-5.6-sol`, `claude-opus-5`                                | Capability boundaries, high-risk review               |
+---------------------+--------------------+------------------------------------------------------------------------------+-------------------------------------------------------+
| Scoped / Subagent   | `glm-5.3-flash`    | `gpt-5.6-terra`, `gemini-3.8-flash`, `gpt-5.4-mini`, `deepseek-flash`        | Bounded searches, edits, docs, lint                   |
+---------------------+--------------------+------------------------------------------------------------------------------+-------------------------------------------------------+

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

## Model Strengths and Selection Guide

The following guide details the strengths, weaknesses, and optimal use cases for each model based on their current (late 2026) technical capabilities.

+-----------------------------+-------------------------------------------------------------------------+-------------------------------------------------------------------------+
| Model                       | Strengths & When to Use                                                 | Weaknesses & When Not to Use                                            |
+=============================+=========================================================================+=========================================================================+
| `claude-fable-5-1`          | High-stakes knowledge work; rigorous self- verification; strict         | Slower inference; can be overly rigid in verifying prior assumptions.   |
|                             | adherence to boundaries and avoiding shortcuts. **Best for:** Security  | **Avoid for:** Routine fast-loop tasks; exploratory coding where strict |
|                             | sign-offs, architecture definition, and critical high-risk code         | rigor is overkill.                                                      |
|                             | boundaries.                                                             |                                                                         |
+-----------------------------+-------------------------------------------------------------------------+-------------------------------------------------------------------------+
| `gpt-6-astra`               | True "computer operator" capabilities; exceptional at multi-step        | Can be "too aligned" or overly cautious in certain complex edge cases.  |
|                             | navigation, cybersecurity, and deep reasoning. **Best for:** Complex    | **Avoid for:** Simple refactoring; unbounded exploratory coding where   |
|                             | system invariants, architectural fallback, navigating external tools.   | extreme caution hinders progress.                                       |
+-----------------------------+-------------------------------------------------------------------------+-------------------------------------------------------------------------+
| `gpt-5.6-sol`               | Very strong autonomous behavior; excellent coding and graphics          | Known for unpredictable autonomous boundary-pushing during its testing  |
|                             | capabilities; aggressive problem-solving. **Best for:** Frontend,       | phase. **Avoid for:** Tasks requiring strict alignment and extreme      |
|                             | WebGL, events, and routine complex reviews.                             | caution.                                                                |
+-----------------------------+-------------------------------------------------------------------------+-------------------------------------------------------------------------+
| `gemini-3.8-flash`          | Lightning-fast inference; highly cost- efficient; optimized for long-   | Lower reasoning ceiling on novel architectural paradoxes compared to    |
|                             | horizon software engineering workflows. **Best for:** QA, verification, | flagship models. **Avoid for:** Core security capability enforcement;   |
|                             | subagents, fast frontend fallback.                                      | top-level architecture.                                                 |
+-----------------------------+-------------------------------------------------------------------------+-------------------------------------------------------------------------+
| `glm-5.3`                   | Exceptional at complex programming and long-horizon tasks; emergent     | Heavier footprint; can overcomplicate simple data framing. **Avoid      |
|                             | cyber capabilities. **Best for:** VMs, storage, indexing, DHTs, and     | for:** UI/UX, canvas, or WebGPU tasks.                                  |
|                             | networking.                                                             |                                                                         |
+-----------------------------+-------------------------------------------------------------------------+-------------------------------------------------------------------------+
| `deepseek-v4-pro`           | Massive 1.6T MoE architecture; hybrid attention for long-context        | Community has noted occasional performance inconsistencies relative to  |
|                             | efficiency; strong frontier reasoning. **Best for:** Adversarial        | its massive parameter count. **Avoid for:** Fast latency-sensitive      |
|                             | reviews, cross-host challenges, defect discovery.                       | subagents; tasks requiring absolute predictability.                     |
+-----------------------------+-------------------------------------------------------------------------+-------------------------------------------------------------------------+
| `qwen/qwen3.8-max`          | Massive 1M-token context window; 2.4T MoE foundation; highly capable at | High compute overhead; slower response times for short prompts. **Avoid |
|                             | complex coding and research. **Best for:** Compiler lowering, AST       | for:** Low-latency bounded searches or quick edits.                     |
|                             | analysis, routine large-scale reviews.                                  |                                                                         |
+-----------------------------+-------------------------------------------------------------------------+-------------------------------------------------------------------------+
| `claude-opus-5`             | Deep context processing; expert at compilers, ASTs, and network         | Higher latency and cost compared to Sonnet or Flash variants. **Avoid   |
|                             | framing. **Best for:** AST manipulation, complex stream codecs, network | for:** Fast QA loops or simple code generation.                         |
|                             | transports.                                                             |                                                                         |
+-----------------------------+-------------------------------------------------------------------------+-------------------------------------------------------------------------+
| `claude-sonnet-5`           | High speed-to-intelligence ratio; great at TDD and test parity. **Best  | Less rigorous than Fable for security or capability bounds. **Avoid     |
|                             | for:** QA, verification, routine testing.                               | for:** Deep architectural security design.                              |
+-----------------------------+-------------------------------------------------------------------------+-------------------------------------------------------------------------+
| `gpt-5.6-terra`             | Solid mid-range performance; excellent at data streams and networking   | Struggles with the deepest compiler lowering edge cases. **Avoid for:** |
|                             | protocols. **Best for:** Network streams, VM runtimes, subagent         | Top- level system architecture.                                         |
|                             | fallback.                                                               |                                                                         |
+-----------------------------+-------------------------------------------------------------------------+-------------------------------------------------------------------------+
| `gpt-5.6-luna`              | Lightweight and very fast. **Best for:** Simple network stream framing. | Limited context depth and reasoning. **Avoid for:** Complex AST         |
|                             |                                                                         | compilation.                                                            |
+-----------------------------+-------------------------------------------------------------------------+-------------------------------------------------------------------------+
| `gpt-5.4-mini`              | Extremely fast; highly cost-effective for large-scale repetition.       | Low reasoning ceiling; struggles with multi-step logic. **Avoid for:**  |
|                             | **Best for:** TDD loops, simple linting, scoped text edits.             | Any complex logical refactoring.                                        |
+-----------------------------+-------------------------------------------------------------------------+-------------------------------------------------------------------------+
| `glm-5.3-flash`             | High performance at a lower computational cost; steep discounts during  | Limited complex reasoning on novel architectures. **Avoid for:** Peak-  |
|                             | off-peak hours. **Best for:** Scoped subagents, bounded searches.       | hour execution if budget is tight; core architecture.                   |
+-----------------------------+-------------------------------------------------------------------------+-------------------------------------------------------------------------+
| `deepseek-flash`            | Blazing fast inference; highly cost- effective and open-weights         | Can hallucinate on deep invariant constraints. **Avoid for:** Complex   |
|                             | aligned. **Best for:** QA parity checks, scoped subagent work.          | system design, AST lowering.                                            |
+-----------------------------+-------------------------------------------------------------------------+-------------------------------------------------------------------------+
| `gemini-3.1-pro-high`       | High capability reasoning; strong defect discovery. **Best for:**       | Can be overly verbose in output generation. **Avoid for:** Routine      |
|                             | Adversarial review fallback.                                            | short-horizon subagent tasks.                                           |
+-----------------------------+-------------------------------------------------------------------------+-------------------------------------------------------------------------+
| `moonshotai/kimi-k2.7-code` | Extremely long context window tailored for code. **Best for:** Context- | Niche ecosystem; less generalized reasoning outside of code. **Avoid    |
|                             | heavy frontend or terminal tasks.                                       | for:** Core VM or indexing logic requiring broad theoretical knowledge. |
+-----------------------------+-------------------------------------------------------------------------+-------------------------------------------------------------------------+
| `muse-spark-1.3-contribute` | Extremely low cost; strong multi-step agentic tasks and 1M long-context | Prompts and completions are used for Meta's training data; lower rate   |
|                             | retrieval. **Best for:** Open-source workflows, non-sensitive bulk      | limits. **Avoid for:** Any proprietary, sensitive, or confidential      |
|                             | processing, or large-scale multi-step evaluation where data privacy is  | enterprise code where data retention poses a security risk.             |
|                             | not a concern.                                                          |                                                                         |
+-----------------------------+-------------------------------------------------------------------------+-------------------------------------------------------------------------+
