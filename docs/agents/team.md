---
description: Team roster, role routing, and reviewer-independence rules for datom.world
---

# DATOM.WORLD SOFTWARE ENGINEERING TEAM

Canonical guide for the shared roster, role routing, and reviewer independence.
The Lead Engineering Orchestrator owns coordination, authorization, artifact
protocol, and verification in [`orchestrator.md`](./roles/orchestrator.md);
delegate session protocol and CLI recipes live in
[`delegate-invocation-reference.md`](./delegate-invocation-reference.md).

## Roster

The Orchestrator is deliberately absent from this table; it is the user-facing
agent that centrally routes tasks to these roles. Each role name links to its
delegation prompt template under `docs/agents/roles/`. Review is one role
backed by one file, `roles/reviewer.md`, covering adversarial, routine, and
security-sign-off review via its Adversarial Review and Consensus Follow-up
templates — adjust scope and rigor in the brief per flavor, not the file.

+---------------------------------------------------+--------------------------------------------------------------------------------------------------------+
| Role                                              | Responsibility                                                                                         |
+===================================================+========================================================================================================+
| [Architect](roles/architect.md)                   | Axioms, invariants, boundaries, architecture                                                           |
+---------------------------------------------------+--------------------------------------------------------------------------------------------------------+
| [Compiler & AST](roles/compiler-engineer.md)      | AST, lowering, compile-time macros                                                                     |
+---------------------------------------------------+--------------------------------------------------------------------------------------------------------+
| [Frontend & Graphics](roles/graphics-engineer.md) | Events, WebGL/WebGPU, canvas, terminal                                                                 |
+---------------------------------------------------+--------------------------------------------------------------------------------------------------------+
| [QA & Verification](roles/qa-engineer.md)         | TDD, parity, lint, regression                                                                          |
+---------------------------------------------------+--------------------------------------------------------------------------------------------------------+
| [Review](roles/reviewer.md)                       | Adversarial defect discovery, routine correctness/portability checks, and security capability sign-off |
+---------------------------------------------------+--------------------------------------------------------------------------------------------------------+
| [Scoped / Subagent](roles/subagent.md)            | Bounded searches, edits, docs, lint                                                                    |
+---------------------------------------------------+--------------------------------------------------------------------------------------------------------+
| [Storage & Indexing](roles/storage-engineer.md)   | B-trees, indexing, DHT, storage, query                                                                 |
+---------------------------------------------------+--------------------------------------------------------------------------------------------------------+
| [Stream & Network](roles/stream-engineer.md)      | Streams, framing, concurrency, transports, codecs, RPC                                                 |
+---------------------------------------------------+--------------------------------------------------------------------------------------------------------+
| [VM Runtime](roles/vm-engineer.md)                | CESK, VMs, continuations, macros, loops                                                                |
+---------------------------------------------------+--------------------------------------------------------------------------------------------------------+


## Available Subscriptions & Cost Constraints

The Orchestrator routes work using these cost profiles. Flat subscriptions are preferred; metered models are reserved for tasks explicitly requiring them.

+-----------------+----------------------------------------+-------------------------------------------------------------------------------------------------------------+
| Provider        | Cost Structure                         | Routing Notes                                                                                               |
+=================+========================================+=============================================================================================================+
| agy             | Flat: Included                         | Treated as a flat subscription. Use freely within budget.                                                   |
+-----------------+----------------------------------------+-------------------------------------------------------------------------------------------------------------+
| qwen            | Token Plan subscription (Alibaba Cloud | The `qwen` CLI is Alibaba's own coding agent (`@qwen-code/qwen-code@0.17.0`, npm-global under nvm node      |
|                 | Model Studio, ap-southeast-1)          | v26.1.0), not a Claude Code wrapper. Headless dispatch and session mechanics measured (2026-11-04): caller  |
|                 |                                        | `--session-id <uuid>`, follow-up `qwen --resume <uuid>` (round-trip verified; chats stored per project      |
|                 |                                        | under ~/.qwen/projects/). Implementation requires `--approval-mode auto-edit`: the default mode silently    |
|                 |                                        | no-ops tools headless. Config `~/.qwen/settings.json`: key in env `BAILIAN_TOKEN_PLAN_API_KEY`; gateway     |
|                 |                                        | https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1 (the key 403s at cn-beijing).        |
|                 |                                        | Measured catalog: `qwen3.8-max` (default), `qwen3.8-flash`, `qwen3.7-max`, `qwen3.6-flash`,                 |
|                 |                                        | `deepseek-v4-pro`; all others denied or absent. Routing status: available NOW (coding round-trip passed:    |
|                 |                                        | files written, assertions passed, resume verified).                                                         |
+-----------------+----------------------------------------+-------------------------------------------------------------------------------------------------------------+
| claude          | Inactive / Cancelled                   | Subscription stopped by owner. Do not route tasks here.                                                     |
+-----------------+----------------------------------------+-------------------------------------------------------------------------------------------------------------+
| claude-api      | Inactive / Cancelled                   | Anthropic API access stopped by owner. Do not route tasks here.                                             |
+-----------------+----------------------------------------+-------------------------------------------------------------------------------------------------------------+
| codex           | Flat: ChatGPT Plus ($20/mo)            | Strict rate limits. Available for any role (architecture, implementation, review, QA).                      |
|                 |                                        | All codex models (astra, sol, terra, luna) draw ONE shared pool: a cut darkens every seat at once, so       |
|                 |                                        | sequence codex dispatches rather than fanning them out. Independent parallel capacity is codex | kimi | glm.|
+-----------------+----------------------------------------+-------------------------------------------------------------------------------------------------------------+
| deepseek        | Metered: Pay-by-token                  | Flash: $0.14/M in, $0.28/M out. Pro: $0.435/M in, $0.87/M out.                                              |
|                 |                                        | Off-peak (10:00-01:00, 04:00-06:00 UTC & wknds) is 50% cheaper.                                             |
|                 |                                        | Cache hits are ~50x cheaper. Reserve for necessary work.                                                    |
+-----------------+----------------------------------------+-------------------------------------------------------------------------------------------------------------+
| glm             | Flat: Pro yearly plan ($672/yr)        | Peak hours: weekdays 14:00-18:00 UTC+8. Schedule large jobs off-peak (50% rate).                            |
+-----------------+----------------------------------------+-------------------------------------------------------------------------------------------------------------+
| kimi            | Flat: Kimi membership (Plus+)          | Invoke only through the dedicated `kimi` CLI. Monthly quotas apply.                                         |
|                 |                                        | Pro unlocks Highspeed and 1M context for K3.x.                                                              |
+-----------------+----------------------------------------+-------------------------------------------------------------------------------------------------------------+
| mistral         | Flat: Mistral Pro ($20/mo)             | Invoke via `vibe` CLI. API-live aliases (probed 2026-11-04): `mistral-4.0` (the current large-4 generation; |
|                 |                                        | use it for large-model work) and `mistral-medium-3.5`. The bare `mistral-large` alias is DEAD at the API:   |
|                 |                                        | the CLI parses it but the live API answers 400 invalid_model (same for `devstral` and `codestral`);         |
|                 |                                        | anything else (`mistral-small`, `magistral-*`, `mixtral-*`) is not even CLI-recognized.                     |
+-----------------+----------------------------------------+-------------------------------------------------------------------------------------------------------------+
| muse            | Meta login / `META_API_KEY`            | Invoke via `muse` CLI; headless form is `muse exec`. Models:                                                |
|                 |                                        | `muse-wonder-1.0` (flagship) and `muse-spark-1.3` (fast lane).                                              |
|                 |                                        | Check [`routing-status.md`](./routing-status.md) before dispatch (geo-restricted at last relay).            |
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
| `claude-opus-5.5`           | Performs at Fable 5.1 levels for most work at markedly lower cost  | Still higher latency/cost than Sonnet 5. **Avoid for:** Fast QA     |
|                             | than the prior Opus generation. **Best for:** Code migration,      | loops or simple code generation where Sonnet 5 suffices.            |
|                             | software optimization, and complex agentic coding.                 |                                                                     |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `claude-sonnet-5-5`         | Frontier agentic workhorse; substantial gains in reasoning depth,  | Can struggle with high-order axiomatic proofs or subtle security    |
|                             | instruction following, and rigorous code audits. Low latency and   | capability proofs vs Fable 5.1 / Astra. **Avoid for:** Final        |
|                             | high cost-efficiency. **Best for:** Core implementation, deep QA   | architectural sign-offs on high-risk cryptographic or consensus     |
|                             | adversarial code reviews, multi-file refactors, and test suites.   | boundaries where Opus 5.5 / Fable 5.1 / Astra is mandated.          |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `deepseek-flash`            | V4.1-Flash outpaces V4-Pro in speed, cost, and agentic benchmarks. | Can hallucinate on deep invariant constraints. **Avoid for:**       |
|                             | **Best for:** QA parity checks, scoped subagent work, and          | Complex system design, AST lowering.                                |
|                             | high-efficiency native multimodal tasks.                           |                                                                     |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `deepseek-v4-pro`           | Legacy 1.6T MoE architecture. **Best for:** Advanced logic         | Actively being superseded by V4.1-Flash; high cost and undercooked  |
|                             | workflows and adversarial reviews.                                 | relative to size. **Avoid for:** General use (use Flash instead).   |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `gemini-3.8-flash`          | Lightning-fast; highly cost-efficient; 1M+ context with native     | Can oversimplify subtle invariant edge cases vs frontier models.    |
|                             | multimodal support. **Best for:** High-volume agentic loops,       | **Avoid for:** Core security capability enforcement; top-level      |
|                             | visual/frontend inspection, rapid TDD/QA, and subagents.           | architecture.                                                       |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `gemini-4-argon`            | Frontier reasoning with configurable effort and deep synthesis     | Higher latency/cost at medium/high effort than Flash-tier models;   |
|                             | across large multi-file specs and codebases; strong architectural  | sandboxed headless CLI cannot run local JVM (`clojure`) suites.     |
|                             | judgment, invariant verification, and surgical cross-checking.     | **Avoid for:** High-volume trivial edits, mechanical sweeps, or     |
|                             | **Best for:** System architecture, design/code review, complex     | headless tasks requiring in-session JVM test execution.             |
|                             | debugging, and multi-step implementation.                          |                                                                     |
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
| `gpt-5.4-mini`              | Fast, lower-cost model for bounded, repetitive work with explicit  | Do not use as the sole judge of subtle invariants or broad design.  |
|                             | acceptance criteria. **Best for:** TDD loops, simple linting,      | Escalate multi-step refactors and disputed findings to Terra, Sol,  |
|                             | extraction, and scoped text edits.                                 | or Astra.                                                           |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `gpt-6-astra`               | Most capable end-to-end model. **Best for:** Hardest architecture, | Highest cost. Reserve for the highest-risk or most difficult work   |
|                             | security, research, coding, computer-use, and long-horizon tasks.  | under the subscription policy.                                      |
|                             | Capability includes implementation across any role.                |                                                                     |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `gpt-6-luna`                | Most affordable GPT-6 model; optimized for high-volume tasks and   | Low-cost does not mean suitable for unbounded design or the hardest |
|                             | fast response times. Improved factuality and coding. **Best for:** | invariant reasoning. Escalate complex architecture/security.        |
|                             | High-volume tasks, extraction, scoped searches, and routine QA.    |                                                                     |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `gpt-6.1-sol`               | Intelligent everyday workhorse with near-Astra intelligence, 1M+   | More expensive than Luna. Use Astra when the task is the hardest    |
|                             | context window, and 95% prompt cache discount. Upgraded reasoning  | end-to-end problem requiring frontier architectural verification.   |
|                             | effort control (low to max), +6.4% DeepSWE / +7% OSWorld gains over|                                                                     |
|                             | predecessor. **Best for:** Complex implementation, advanced coding,|                                                                     |
|                             | high-stakes security, and architectural review.                    |                                                                     |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `gpt-6.1-terra`             | Balanced intelligence and cost. **Best for:** Default production   | Use Sol or Astra for the hardest reasoning, high-risk security, or  |
|                             | engineering, integration work, network streams, VM runtimes,       | architecture decisions. Validate compiler-lowering and other        |
|                             | moderate-complexity implementation, and subagent fallback.         | specialist work with focused tests and independent review.          |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `kimi-for-coding-highspeed` | K2.7 Code Highspeed; 5-6x faster output with 256K context.         | Uses about 3x quota; fixed thinking; Pro tier required. **Avoid     |
|                             | **Best for:** Latency-sensitive routine coding and iteration.      | for:** 1M-context work or tasks needing adjustable reasoning.       |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `k3.1-pro`                  | Latest 3T-class flagship; 1M context, image/video input, and       | Higher resource use than K3 256K; thinking is mandatory; 1M         |
|                             | strongest long-horizon agentic coding. **Best for:** Hardest       | context requires Pro. **Avoid for:** Routine Clojure or latency-    |
|                             | implementation, large refactors, architecture, deep review, and    | sensitive loops; use K3 256K or Highspeed instead. Also avoid       |
|                             | difficult multi-file Clojure, compiler, VM, and invariant work.    | clients that cannot keep thinking enabled.                          |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `k3.1-pro-highspeed`        | Same K3.1 Pro capabilities with 2-3x faster output and 1M          | About 2x quota usage; Pro tier; accelerates model output, not       |
|                             | context. **Best for:** Responsive complex coding, long-horizon     | tools. **Avoid for:** Quota-sensitive or tool-bound work where      |
|                             | agents, and iterative difficult debugging.                         | regular K3.1 Pro has the same quality at lower quota cost.          |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `k3`                        | Previous 2.8T flagship; 1M context, image/video input, adjustable  | Superseded by K3.1 Pro; 1M uses about 2x the quota of K3 256K       |
|                             | thinking. **Best for:** Large-codebase analysis, multi-file        | and requires Pro. **Avoid for:** Hardest tasks when K3.1 Pro is     |
|                             | refactors, and long-document processing.                           | available, or small jobs that fit K3 256K.                          |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `k3-256k`                   | Same K3 results within 256K at about half the 1M-context quota.    | 256K limit; image input only, no video. **Avoid for:** Vast         |
|                             | Most quota-efficient Clojure choice; use low thinking for routine  | contexts, long documents, video, or difficult multi-file Clojure,   |
|                             | REPL fixes, features, and small/medium refactors. **Best for:**    | compiler, VM, and invariant work; use K3.1 Pro for those instead.   |
|                             | routine coding, code completion, Q&A, and small edits.             |                                                                     |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `mistral-4.0`               | The current large-4 generation and this lane's API-live large      | Context window not probe-verified from this seat (the reachable     |
|                             | alias (probed 2026-11-04). Strong general reasoning and code       | provider docs predate the 4.0 generation); probe before             |
|                             | generation. **Best for:** General implementation, refactors, and   | long-document work. **Avoid for:** Deep invariant proofs; use Fable |
|                             | code review on the mistral lane.                                   | 5.1/Opus 5.5 on the highest-stakes cryptographic boundaries.        |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `mistral-medium-3.5`        | API-live via `vibe` (probed 2026-11-04); the lane's efficient      | Lighter reasoning than the mistral-4.0 generation (tier             |
|                             | mid-tier. **Best for:** Routine coding, QA, extraction, and scoped | positioning; not benchmarked here). **Avoid for:** Complex          |
|                             | edits where the mistral family fits.                               | architecture or deep invariant reasoning.                           |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `codestral`                 | Low-latency code specialist: fill-in-the-middle, code correction,  | NOT dispatchable on this lane: the CLI parses `codestral` but the   |
|                             | test generation; 256K context per the provider's 25.01 model card  | API answers 400 invalid_model (probed 2026-11-03 and 11-04).        |
|                             | (source-derived, not probe-verified).                              | **Avoid for:** all routing until the lane revives it; narrow code   |
|                             |                                                                    | focus, not a reviewer.                                              |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `devstral`                  | The family's agentic software-engineering specialist (public       | NOT dispatchable on this lane: parses client-side, API 400          |
|                             | positioning; source-derived, not measured here). **Best for:**     | invalid_model (probed 2026-11-03 and 11-04). **Avoid for:** all     |
|                             | repo-level agentic coding sweeps if the lane ever revives it.      | routing today.                                                      |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `magistral-medium-latest`   | The family's reasoning-tuned medium line (source-derived, not      | NOT dispatchable via `vibe` at all: not in the CLI alias list       |
|                             | measured here). **Best for:** deliberative reasoning chains if a   | (Unknown model, probed 2026-11-04). **Avoid for:** all routing      |
|                             | lane ever wires it.                                                | today.                                                              |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `mixtral-8x7b`              | Sparse Mixture of Experts; strong coding at lower cost.            | **Avoid for:** Tasks requiring dense attention across very long     |
|                             | **Best for:** Implementation, debugging, and moderate complexity.   | contexts or fine-grained invariant verification.                   |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `muse-wonder-1.0`           | Flagship Muse (Meta) model; the model behind the Muse              | Heavier and slower than Spark. **Avoid for:** High-volume trivial   |
|                             | orchestrator seat in this repo: long-horizon agentic coding,       | edits, extraction, or latency-sensitive loops; use                  |
|                             | multi-file implementation, and deep cross-checking.                | `muse-spark-1.3` instead.                                           |
|                             | **Best for:** Orchestration, complex implementation, deep review.  |                                                                     |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `muse-spark-1.3`            | Fast Muse lane; the auxiliary/helper model inside Muse             | Lighter reasoning than Wonder. **Avoid for:** Hardest architecture, |
|                             | sessions. **Best for:** Scoped subagent work, bounded searches,    | security sign-offs, or subtle invariant proofs; escalate to         |
|                             | routine QA, extraction, and fast iteration.                        | `muse-wonder-1.0`.                                                  |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `qwen3.8-max`               | Qwen (Alibaba) flagship on the `qwen` CLI's Token Plan (serving    | Capability not benchmarked from this fleet yet (availability        |
|                             | model, probed live 2026-11-04); latest generation. **Best for:**   | measured only); treat early dispatches as candidates for the        |
|                             | Implementation and review rounds on the qwen lane, pending in-repo | standing kimi quality gate before trusting verdicts. **Avoid for:** |
|                             | evaluation.                                                        | Long-context jobs: context size not probe-verified.                 |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `qwen3.8-flash`             | Qwen fast tier on the same plan (probed live 2026-11-04). **Best   | Lighter tier than 3.8-max. **Avoid for:** Hardest reasoning or      |
|                             | for:** Latency-sensitive iteration, QA spot-checks, extraction,    | disputed findings; use `qwen3.8-max` or escalate per the ladder.    |
|                             | and this seat's own interactive runs.                              |                                                                     |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `qwen3.7-max`               | Prior-generation Qwen flagship, still served on the plan (probed   | Superseded within the lane by `qwen3.8-max`. **Avoid for:** Any     |
|                             | live). Second choice when 3.8 capacity is throttled.               | work 3.8 can take; no measured advantage over it.                   |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `qwen3.6-flash`             | Prior-generation Qwen fast tier (probed live).                     | Two generations old; lighter reasoning than either 3.8 tier.        |
|                             |                                                                    | **Avoid for:** Anything the 3.8 lane can serve.                     |
+-----------------------------+--------------------------------------------------------------------+---------------------------------------------------------------------+
| `deepseek-v4-pro` (qwen     | NOTE: the `qwen` lane's Token Plan ALSO serves `deepseek-v4-pro`   | Same-family collision: a deepseek-v4-pro author on the qwen lane    |
| lane note)                  | (probed live 2026-11-04), in addition to the Qwen tiers.           | must NOT be reviewed by the DeepSeek lane (same family).            |
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
**DeepSeek**, **Gemini**, **GLM**, **GPT**, **Kimi**, **Mistral**,
**Muse** (Meta), and **Qwen** (Alibaba). Family, not CLI, decides
independence: the `qwen` lane's `deepseek-v4-pro` is a **DeepSeek**
author for review purposes, and Qwen-authored work (including anything
by this interactive seat) must be reviewed by a different family.
Any author from one family must be reviewed by a model from a different family.

Routine review applies only when independent; architectural or security review
is mandatory when the role or risk requires it. For operational procedures, see
the [`Lead Engineering Orchestrator`](./roles/orchestrator.md).

**Reviewer bench and parallel-lane scheduling** (2026-10-26 owner ruling,
after the jolt lane's throughput review):

- **The standing reviewer bench** is wider than the implementation bench:
  `kimi` (flat membership, native Kimi models only) joins the families above
  as a standing review destination. The independence rule is unchanged: the
  reviewer must be a different model family from the author.
- **Parallel-lane scheduling**: when slice N lands on its branch, slice N+1's
  implementation dispatches immediately and slice N's review runs
  concurrently. Gate: review N must complete **before slice N+1 merges** — a
  defect found in N's review must still be fixable without N+1 having built
  on it. Judgment is required on what N+1 actually built on; tightly-coupled
  slices serialize.
- **Shared ledgers scale with concurrency**: the pre-push row-count check on
  append-only ledgers (`docs/agents/known-gaps.md`; orchestrator.md Workflow
  step 10) is more important under parallel review, not less — parallel
  review means parallel merges (the 2026-10-25 corruption precedent:
  merge 4d6fd818 silently dropped ~66 ledger rows by replacing the file).
- Never relax the independence rule or skip reviews on small slices; the fix
  for throughput is more reviewers, not fewer reviews.
