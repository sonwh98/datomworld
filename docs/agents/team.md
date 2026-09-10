---
description: Team roster, role routing, and reviewer-independence rules for datom.world
---

# DATOM.WORLD SOFTWARE ENGINEERING TEAM

Canonical guide for the shared roster, role routing, and reviewer independence.
The Lead Engineering Orchestrator owns coordination, authorization, artifact and
session protocol, verification, and operational CLI recipes in
[`orchestrator.md`](./roles/orchestrator.md).

## Roster

The Orchestrator is deliberately absent from this table; it is the user-facing
agent that centrally routes tasks to these roles.

+------------------------+-----------------------------------------------------------------------------------------------------------------------------------------------+
| Role                   | Responsibility                                                                                                                                |
+========================+===============================================================================================================================================+
| Adversarial Review     | Independent defect discovery, cross-host challenge                                                                                            |
+------------------------+-----------------------------------------------------------------------------------------------------------------------------------------------+
| Architect              | Axioms, invariants, boundaries, architecture                                                                                                  |
+------------------------+-----------------------------------------------------------------------------------------------------------------------------------------------+
| Compiler & AST         | AST, lowering, compile-time macros                                                                                                            |
+------------------------+-----------------------------------------------------------------------------------------------------------------------------------------------+
| Frontend & Graphics    | Events, WebGL/WebGPU, canvas, terminal                                                                                                        |
+------------------------+-----------------------------------------------------------------------------------------------------------------------------------------------+
| QA & Verification      | TDD, parity, lint, regression                                                                                                                 |
+------------------------+-----------------------------------------------------------------------------------------------------------------------------------------------+
| Routine Review         | Correctness, invariants, portability                                                                                                          |
+------------------------+-----------------------------------------------------------------------------------------------------------------------------------------------+
| Scoped / Subagent      | Bounded searches, edits, docs, lint                                                                                                           |
+------------------------+-----------------------------------------------------------------------------------------------------------------------------------------------+
| Security Sign-off      | Capability boundaries, high-risk review                                                                                                       |
+------------------------+-----------------------------------------------------------------------------------------------------------------------------------------------+
| Storage & Indexing     | B-trees, indexing, DHT, storage, query                                                                                                        |
+------------------------+-----------------------------------------------------------------------------------------------------------------------------------------------+
| Stream & Network       | Streams, framing, concurrency, transports, codecs, RPC                                                                                        |
+------------------------+-----------------------------------------------------------------------------------------------------------------------------------------------+
| VM Runtime             | CESK, VMs, continuations, macros, loops                                                                                                       |
+------------------------+-----------------------------------------------------------------------------------------------------------------------------------------------+


## Available Subscriptions & Cost Constraints

The Orchestrator routes work using these cost profiles. Flat subscriptions are preferred; metered models are reserved for tasks explicitly requiring them.

+-----------------+----------------------------------------+-------------------------------------------------------------------------------------------------------------+
| Provider        | Cost Structure                         | Routing Notes                                                                                               |
+=================+========================================+=============================================================================================================+
| agy             | Flat: Included                         | Treated as a flat subscription. Use freely within budget.                                                   |
+-----------------+----------------------------------------+-------------------------------------------------------------------------------------------------------------+
| claude          | Flat: Pro Max plan ($100/mo)           | Use freely within budget. Invoked only through `claude` or `agy`.                                           |
+-----------------+----------------------------------------+-------------------------------------------------------------------------------------------------------------+
| cmd             | Flat: CommandCode.ai Go plan ($1/mo)   | Use freely within budget. Used to invoke any LLMs not directly listed in this table.                        |
+-----------------+----------------------------------------+-------------------------------------------------------------------------------------------------------------+
| codex           | Flat: ChatGPT Plus ($20/mo)            | Use freely within budget.                                                                                   |
+-----------------+----------------------------------------+-------------------------------------------------------------------------------------------------------------+
| deepseek        | Metered: Pay-by-token                  | Variable cost. Reserve for work worth the expense. V4-Pro is being superseded by V4.1 Flash; prefer Flash.  |
+-----------------+----------------------------------------+-------------------------------------------------------------------------------------------------------------+
| glm             | Flat: Pro yearly plan ($672/yr)        | Peak hours: weekdays 14:00-18:00 UTC+8. Schedule large jobs off-peak (50% rate).                            |
+-----------------+----------------------------------------+-------------------------------------------------------------------------------------------------------------+
| muse            | Metered: Pay-by-token                  | Variable cost. Reserve for work worth the expense. Invoked only via `~/.local/bin/muse` with `muse-         |
|                 |                                        | spark-1.3-contributor`.                                                                                     |
+-----------------+----------------------------------------+-------------------------------------------------------------------------------------------------------------+

## Model Strengths and Selection Guide

The following guide details the strengths, weaknesses, and optimal use cases for each model based on their current (late 2026) technical capabilities.

+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| Model                       | Strengths & When to Use                                            | Weaknesses & When Not to Use                                        |
+=============================+====================================================================+=====================================================================+
| `claude-fable-5-1`          | High-stakes knowledge work; rigorous self- verification; strict    | Slower inference; can be overly rigid in verifying prior            |
|                             | adherence to boundaries and avoiding shortcuts. **Best for:**      | assumptions. **Avoid for:** Routine fast-loop tasks; exploratory    |
|                             | Security sign-offs, architecture definition, and critical high-    | coding where strict rigor is overkill.                              |
|                             | risk code boundaries.                                              |                                                                     |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `claude-opus-5`             | Deep context processing; expert at compilers, ASTs, and network    | Higher latency and cost compared to Sonnet or Flash variants.       |
|                             | framing. **Best for:** AST manipulation, complex stream codecs,    | **Avoid for:** Fast QA loops or simple code generation.             |
|                             | network transports.                                                |                                                                     |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `claude-sonnet-5`           | High speed-to-intelligence ratio; great at TDD and test parity.    | Less rigorous than Fable for security or capability bounds. **Avoid |
|                             | **Best for:** QA, verification, routine testing.                   | for:** Deep architectural security design.                          |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `deepseek-flash`            | Blazing fast inference; highly cost- effective and open-weights    | Can hallucinate on deep invariant constraints. **Avoid for:**       |
|                             | aligned. **Best for:** QA parity checks, scoped subagent work.     | Complex system design, AST lowering.                                |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `deepseek-v4-pro`           | Massive 1.6T MoE architecture; 1M long-context via Hybrid          | High operational cost; "undercooked" performance relative to its    |
|                             | Attention. Best for: Advanced logic workflows, multi-step          | 1.6T size. Avoid for: General use, as DeepSeek is actively          |
|                             | reasoning, and adversarial reviews.                                | replacing it with the much more efficient V4.1 Flash.               |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `gemini-3.1-pro-high`       | High capability reasoning; strong defect discovery. **Best for:**  | Can be overly verbose in output generation. **Avoid for:** Routine  |
|                             | Adversarial review fallback.                                       | short-horizon subagent tasks.                                       |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `gemini-3.8-flash`          | Lightning-fast inference; highly cost- efficient; optimized for    | Lower reasoning ceiling on novel architectural paradoxes compared   |
|                             | long- horizon software engineering workflows. **Best for:** QA,    | to flagship models. **Avoid for:** Core security capability         |
|                             | verification, subagents, fast frontend fallback.                   | enforcement; top-level architecture.                                |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `glm-5.3`                   | Exceptional at complex programming and long-horizon tasks;         | Heavier footprint; can overcomplicate simple data framing. **Avoid  |
|                             | emergent cyber capabilities. **Best for:** VMs, storage, indexing, | for:** UI/UX, canvas, or WebGPU tasks.                              |
|                             | DHTs, and networking.                                              |                                                                     |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `glm-5.3-flash`             | High performance at a lower computational cost; steep discounts    | Limited complex reasoning on novel architectures. **Avoid for:**    |
|                             | during off-peak hours. **Best for:** Scoped subagents, bounded     | Peak- hour execution if budget is tight; core architecture.         |
|                             | searches.                                                          |                                                                     |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `gpt-5.4-mini`              | Extremely fast; highly cost-effective for large-scale repetition.  | Low reasoning ceiling; struggles with multi-step logic. **Avoid     |
|                             | **Best for:** TDD loops, simple linting, scoped text edits.        | for:** Any complex logical refactoring.                             |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `gpt-5.6-luna`              | Lightweight and very fast. **Best for:** Simple network stream     | Limited context depth and reasoning. **Avoid for:** Complex AST     |
|                             | framing.                                                           | compilation.                                                        |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `gpt-5.6-sol`               | Very strong autonomous behavior; excellent coding and graphics     | Known for unpredictable autonomous boundary-pushing during its      |
|                             | capabilities; aggressive problem-solving. **Best for:** Frontend,  | testing phase. **Avoid for:** Tasks requiring strict alignment and  |
|                             | WebGL, events, and routine complex reviews.                        | extreme caution.                                                    |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `gpt-5.6-terra`             | Solid mid-range performance; excellent at data streams and         | Struggles with the deepest compiler lowering edge cases. **Avoid    |
|                             | networking protocols. **Best for:** Network streams, VM runtimes,  | for:** Top- level system architecture.                              |
|                             | subagent fallback.                                                 |                                                                     |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `gpt-6-astra`               | True "computer operator" capabilities; exceptional at multi-step   | Can be "too aligned" or overly cautious in certain complex edge     |
|                             | navigation, cybersecurity, and deep reasoning. **Best for:**       | cases. **Avoid for:** Simple refactoring; unbounded exploratory     |
|                             | Complex system invariants, architectural fallback, navigating      | coding where extreme caution hinders progress.                      |
|                             | external tools.                                                    |                                                                     |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `moonshotai/kimi-k2.7-code` | Extremely long context window tailored for code. **Best for:**     | Niche ecosystem; less generalized reasoning outside of code.        |
|                             | Context- heavy frontend or terminal tasks.                         | **Avoid for:** Core VM or indexing logic requiring broad            |
|                             |                                                                    | theoretical knowledge.                                              |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `muse-spark-1.3-contribute` | Extremely low cost; strong multi-step agentic tasks and 1M long-   | Prompts and completions are used for Meta's training data; lower    |
|                             | context retrieval. **Best for:** Open-source workflows, non-       | rate limits. **Avoid for:** Any proprietary, sensitive, or          |
|                             | sensitive bulk processing, or large-scale multi-step evaluation    | confidential enterprise code where data retention poses a security  |
|                             | where data privacy is not a concern.                               | risk.                                                               |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `qwen/qwen3.8-max`          | Massive 1M-token context window; 2.4T MoE foundation; highly       | High compute overhead; slower response times for short prompts.     |
|                             | capable at complex coding and research. **Best for:** Compiler     | **Avoid for:** Low-latency bounded searches or quick edits.         |
|                             | lowering, AST analysis, routine large-scale reviews.               |                                                                     |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+

## Role Selection & Reviewer Independence

The Orchestrator is completely responsible for selecting the implementer for a given role. It dynamically
cross-references the task's requirements against the **Model Strengths and Selection Guide** and the
**Available Subscriptions & Cost Constraints**. Any model may fill any role provided its technical profile and
cost align with the task.

**Reviewer Independence Rules:**
Every change must be reviewed by a model from a **different family** to ensure
independent defect discovery. Never silently substitute a same-family reviewer.
The families represented in the Model Strengths table are: **Claude**,
**DeepSeek**, **Gemini**, **GLM**, **GPT**, **Kimi**, **Muse**, and **Qwen**.
Any author from one family must be reviewed by a model from a different family.

Routine review applies only when independent; architectural or security review
is mandatory when the role or risk requires it. For operational procedures, see
the [`Lead Engineering Orchestrator`](./roles/orchestrator.md).
