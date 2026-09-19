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
| codex           | Flat: ChatGPT Plus ($20/mo)            | Strict rate limits. Reserved strictly for Architectural Review roles. Do not assign implementation tasks.   |
+-----------------+----------------------------------------+-------------------------------------------------------------------------------------------------------------+
| deepseek        | Metered: Pay-by-token                  | Flash: $0.14/M in, $0.28/M out. Pro: $0.435/M in, $0.87/M out.                                              |
|                 |                                        | Off-peak (10:00-01:00, 04:00-06:00 UTC & wknds) is 50% cheaper.                                             |
|                 |                                        | Cache hits are ~50x cheaper. Reserve for necessary work.                                                    |
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
| `claude-fable-5-1`          | Highest capability for long-horizon agentic work, complex research,| Slower inference; resource hog (rate limits); mandatory             |
|                             | strict boundaries, and managing vast context (efficient caching).  | watermarking. **Avoid for:** Routine fast-loop tasks; precise       |
|                             | **Best for:** Architecture definition, security sign-offs.         | one-shot implementations where strict rigor is overkill.            |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `claude-opus-5`             | Enterprise flagship; exceptional at complex agentic coding, multi- | Higher latency and cost than Sonnet; falls short of Fable on the    |
|                             | step problem-solving, and compilers. **Best for:** AST             | most extreme long-horizon tasks. **Avoid for:** Fast QA loops or    |
|                             | manipulation, complex stream codecs, network transports.           | simple code generation.                                             |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `claude-sonnet-5`           | Optimal blend of speed, intelligence, and price. **Best for:**     | Less rigorous for security or capability bounds. **Avoid for:**     |
|                             | Everyday coding, QA, verification, and general-purpose tasks.      | Deep architectural security design; extremely complex agentic work. |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `deepseek-flash`            | V4.1-Flash outpaces V4-Pro in speed, cost, and agentic benchmarks. | Can hallucinate on deep invariant constraints. **Avoid for:**       |
|                             | **Best for:** QA parity checks, scoped subagent work, and          | Complex system design, AST lowering.                                |
|                             | high-efficiency native multimodal tasks.                           |                                                                     |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `deepseek-v4-pro`           | Legacy 1.6T MoE architecture. **Best for:** Advanced logic         | Actively being superseded by V4.1-Flash; high cost and undercooked  |
|                             | workflows and adversarial reviews.                                 | relative to size. **Avoid for:** General use (use Flash instead).   |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `gemini-3.1-pro-high`       | Native 2M token context; deep reasoning. **Best for:** Massive     | High context overhead makes it computationally heavy compared to    |
|                             | document/repo analysis, system-wide root-cause debugging, complex  | newer, specialized models. **Avoid for:** Simple, high-volume       |
|                             | multimodal knowledge synthesis, and adversarial review fallback.   | repetitive coding loops where deep context is unnecessary.          |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `gemini-3.8-flash`          | Lightning-fast; highly cost-efficient; 1M+ context with native     | Can oversimplify subtle invariant edge cases vs frontier models.    |
|                             | multimodal support. **Best for:** High-volume agentic loops,       | **Avoid for:** Core security capability enforcement; top-level      |
|                             | visual/frontend inspection, rapid TDD/QA, and subagents.           | architecture.                                                       |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `glm-5.3`                   | Scaled post-training for exceptional long-horizon tasks and        | Heavier footprint; can overcomplicate simple data framing. **Avoid  |
|                             | cybersecurity. **Best for:** Complex programming workflows, VMs,   | for:** UI/UX, canvas, or WebGPU tasks.                              |
|                             | storage, indexing, DHTs, and networking.                           |                                                                     |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `glm-5.3-flash`             | High performance at a lower computational cost; steep discounts    | Limited complex reasoning on novel architectures; self-assessment   |
|                             | off-peak and weekend boosts. Excels when claims are checkable      | is unreliable — judge it behaviorally. **Avoid for:** Core          |
|                             | against an authority: evidence gathering, citation verification,   | architecture, unbounded design authorship, peak-hour execution if   |
|                             | reconciliation, protocol discipline. **Best for:** Scoped          | budget is tight.                                                    |
|                             | subagents, bounded searches, QA spot-checks, verification seats.   |                                                                     |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `gpt-5.4-mini`              | Extremely fast; highly cost-effective for large-scale repetition.  | Low reasoning ceiling; struggles with multi-step logic. **Avoid     |
|                             | **Best for:** TDD loops, simple linting, scoped text edits.        | for:** Any complex logical refactoring.                             |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `gpt-5.6-luna`              | High-frequency, latency-sensitive tasks. **Best for:** Simple      | Poor long-form context processing and reasoning. **Avoid for:**     |
|                             | network stream framing, classification, and extraction.            | Complex AST compilation; long-running agentic tasks.                |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `gpt-5.6-sol`               | Flagship tier for complex reasoning and advanced coding agents.    | Strict rate limits. **Avoid for:** Implementation tasks. Reserved   |
|                             | **Best for:** High-stakes security, routine complex reviews.       | exclusively for Architectural Review.                               |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `gpt-5.6-terra`             | The "default" balanced model for daily workflows. **Best for:**    | Struggles with the deepest compiler lowering edge cases. **Avoid    |
|                             | Daily business workflows, production engineering, network streams, | for:** Top-level system architecture; extreme specialized reasoning.|
|                             | VM runtimes, subagent fallback.                                    |                                                                     |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `gpt-6-astra`               | True "computer operator" capabilities; generational leap in deep   | Strict rate limits. **Avoid for:** Implementation tasks. Reserved   |
|                             | reasoning and safety. **Best for:** Autonomous multi-step          | exclusively for Architectural Review.                               |
|                             | workflows, one-shot feature implementations, general daily tasks.  |                                                                     |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `minimax/m3`                | Natively multimodal with 1M context. **Best for:** Large codebase  | Can fall into thinking loops; gives up early on difficult tasks.    |
|                             | comprehension, orchestrating subagents, iterative refactoring.     | **Avoid for:** One-shot mission critical coding, unbounded tasks.   |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `mimo-v2.5-pro`             | 1.02T MoE tailored for IDE integration. **Best for:** IDE-         | Struggles with "lost in the middle" in massive contexts; can        |
|                             | integrated coding agents, high-volume automation, complex design.  | spoil stable code. **Avoid for:** Verification-poor environments.   |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `moonshotai/kimi-k2.7-code` | Extremely long context window tailored for SWE and long-horizon    | Niche ecosystem; less generalized reasoning outside of code.        |
|                             | coding. **Best for:** Context-heavy frontend or terminal tasks.    | **Avoid for:** Core VM or indexing logic requiring broad            |
|                             |                                                                    | theoretical knowledge.                                              |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `moonshotai/kimi-k3`        | Flagship 2.8T MoE; massive open-weight scale. **Best for:** Long-  | Higher hallucination rates on factual Q&A compared to top frontier  |
|                             | horizon SWE tasks, multi-modal context (1M tokens), navigating     | models; potential inconsistency across benchmarks. **Avoid for:**   |
|                             | vast codebases, and cost-efficient complex reasoning.              | Tasks requiring zero hallucination tolerance or strict verification.|
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `muse-spark-1.3-contribute` | Extremely low cost; strong multi-step agentic tasks and 1M long-   | Trails frontier models on complex agent benchmarks (e.g. OSWorld);  |
|                             | context retrieval. **Best for:** Open-source workflows, efficient  | restricted access to "max" reasoning; mixed coding consistency.     |
|                             | bulk processing, or large-scale multi-step evaluation.             | **Avoid for:** Extreme autonomous coding needing verified max logic.|
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `inclusionai/ling-3.0-sante`| Lightweight MoE model capable of coding with health/medicine focus.| Narrower general knowledge base outside its tuning. **Avoid for:**  |
|                             | **Best for:** Cost-effective, specialized workflows.               | Complex multi-step autonomous architecture building.                |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `meituan/longcat-2.0`       | 1T parameter agentic model with 1M token context. **Best for:**    | Context cache can be expensive. **Avoid for:** Extremely short      |
|                             | Processing massive codebases, deep refactors, long-horizon tasks.  | latency-sensitive queries or tasks requiring strictly bounded logic.|
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `poolside/laguna-s-2.1`     | Built specifically for software engineering and coding benchmarks. | Can struggle with non-coding reasoning or creative tasks. **Avoid   |
|                             | **Best for:** Fast, accurate code generation and test generation.  | for:** Pure analytical reasoning outside of standard SWE patterns.  |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `qwen/qwen3.8-max`          | 2.4T MoE foundation; strong multimodal and agentic benchmarks.     | High compute overhead; slower response times for short prompts;     |
|                             | **Best for:** Compiler lowering, AST analysis, routine large-      | vendor-reported benchmarks. **Avoid for:** Low-latency bounded      |
|                             | scale reviews.                                                     | searches or quick edits.                                            |
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
**DeepSeek**, **Gemini**, **GLM**, **GPT**, **InclusionAI**, **Kimi**, **Laguna**, **LongCat**, **MiMo**, **MiniMax**, **Muse**, and **Qwen**.
Any author from one family must be reviewed by a model from a different family.

Routine review applies only when independent; architectural or security review
is mandatory when the role or risk requires it. For operational procedures, see
the [`Lead Engineering Orchestrator`](./roles/orchestrator.md).
