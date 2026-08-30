---
description: Software engineering roles, model routing, collaboration protocols, and agent invocation guide for datom.world
---

# DATOM.WORLD SOFTWARE ENGINEERING TEAM

This is the canonical guide for selecting, briefing, invoking, and reviewing
specialized software-engineering agents for **datom.world**. Team members
coordinate through repository artifacts—designs, tests, source changes, and
review findings—rather than relying on hidden point-to-point context.

## 1. Team Roster and Model Assignments

| Role                                              | Primary Model               | Secondary / Fallback                                          | Key Responsibilities                                                                               |
| :-------------------------------------------------| :---------------------------| :-------------------------------------------------------------| :--------------------------------------------------------------------------------------------------|
| **[Orchestrator](./orchestrator.md)**             | `gpt-5.6-sol`               | `gemini-3.1-pro-high` / `claude-5-sonnet`                     | Scope, decomposition, delegation, consensus, local verification, commit readiness                  |
| **[Architect](./architect.md)**                   | `claude-fable-5`            | `gpt-5.6-sol` / `claude-5-opus`                               | Foundational axioms, invariants, subsystem boundaries, multi-platform architecture                 |
| **[VM Runtime](./vm-engineer.md)**                | `glm-5.3` †                 | `claude-5-sonnet` / `gpt-5.6-terra`                           | CESK machine, VMs, continuations, runtime macros, execution loop optimizations                     |
| **[Storage & Indexing](./storage-engineer.md)**   | `glm-5.3` †                 | `claude-5-sonnet` / `deepseek-v4-pro`                         | B-trees, indexing, DHT, content-addressed storage, query                                           |
| **[Compiler & AST](./compiler-engineer.md)**      | `gpt-5.6-terra`             | `gpt-5.6-luna` / `qwen/qwen3.8-max`                           | Universal AST, multi-language lowering, compile-time macros                                        |
| **[Stream & Network](./stream-engineer.md)**      | `gpt-5.6-luna`              | `glm-5.3` † / `gpt-5.6-terra`                                 | Stream framing, concurrency & lock-free invariants, state machines, transports, serialization, RPC |
| **[Frontend & Graphics](./graphics-engineer.md)** | `gemini-3.7-flash`          | `moonshotai/kimi-k2.7-code` / `minimax/minimax-m3-free`       | Event systems, WebGL/WebGPU, canvas and terminal rendering                                         |
| **[QA & Verification](./qa-engineer.md)**         | `claude-5-sonnet`           | `gemini-3.7-flash` / `gpt-5.4-mini`                           | TDD, CLJ/CLJS/CLJD parity, lint and regression testing                                             |
| **[Routine Review](./reviewer.md)**               | `gpt-5.6-sol`               | `gemini-3.7-flash` / `glm-5.3` (non-GLM only) / `qwen/qwen3.8-max` | Correctness, invariants, portability, diff audits, regressions                                     |
| **[Security Sign-off](./reviewer.md)**            | `claude-fable-5`            | `gpt-5.6-sol` / `claude-5-opus`                               | Capability boundaries, high-risk invariants, final security review                                 |
| **[Scoped / Subagent](./subagent.md)**            | `gemini-3.7-flash`          | `gpt-5.6-terra` / `gpt-5.4-mini`                              | Targeted searches, scoped edits, documentation, and linters                                        |

† Quota override active — see the routing override below.

Each role name links to its specification: scope, responsibilities, and the
reusable delegation prompt template. Each row names exactly one Primary
model — the seat's single default route, not a set to pick from. Where
routing legitimately depends on context (Routine Review and Security
Sign-off must be cross-family from whoever authored the patch under
review), that logic lives in prose — see *Stigmergic coordination* in
Section 3 — and the listed Primary is the default when the author's
family doesn't rule it out. Prefixed model ids (`qwen/…`, `minimax/…`,
`moonshotai/…`) are `cmd` catalog names, not typos.

These are default routes, not claims of inherent subsystem expertise. Promote
or demote a model only from repository-specific evaluations using representative
prompts, expected findings, edit quality, test outcomes, latency, and effective
cost. Test execution itself is a property of the agent harness and granted
tools, not of the underlying model.

**Evaluation note, 2026-08-30 (Orchestrator + Architect).** Two linked
changes, superseding an earlier same-day promotion of Orchestrator to
`claude-5-sonnet`. Orchestrator primary is `gpt-5.6-sol`: the seat's real
work is skeptical verification and consensus arbitration (see the
DaoStream redesign audit trail in `collab/architect-dao-stream-*`), which
is judgment work, not mechanical dispatch — GPT's reasoning tier (`sol`),
not its implementation tier (`terra`/`luna`), fits that profile. Architect
primary is `claude-fable-5`, the strongest available model for
architecture — its own DaoStream contract, reviewed adversarially by
`gpt-5.6-sol` across six convergence rounds, is the repository-specific
evidence for both halves of this split. Fixing the two seats to different
providers gives cross-family review structurally, without depending on
routing rules to detect who authored a given patch. `gemini-3.7-flash` is
not part of this seat's Primary or Fallback tier — it is a scoped tool the
seat may use for lightweight dispatch sub-tasks needing no judgment; see
orchestrator.md.

**Harness caveat**: "Orchestrator" means the live interactive session —
whichever CLI the user is actually driving. A GPT-family orchestrator
therefore implies driving from an interactive `codex` session (it has one,
not just `exec`), with Claude Code as a delegate rather than the reverse.
If the daily driver stays Claude Code, the model actually answering in
that seat remains Claude regardless of what this table names as primary.

**Evaluation note, 2026-08-30 (Scoped / Subagent).** `muse-spark-1.2-contributor`
demoted out of this seat's routing entirely: it is metered, pay-per-token
Meta API access, not a flat subscription like `agy`, `glm`, `codex`, and
`claude` — every call costs real money regardless of quota. Routing the
highest-call-volume seat on the roster ("High-Throughput") through the one
metered route was backwards under the doc's own policy ("use subscription
quotas before metered providers... reserve metered providers for
high-value... work, not routine work"), the same policy already applied to
DeepSeek.

`gemini-3.7-flash` is primary (not `gpt-5.6-terra`), revising a same-day
placement. Two reasons: the seat's own scope ("bounded searches," "small,
explicitly scoped" edits) is narrower than `terra`'s agentic-coding profile
suggests, and flash fits it directly; and `agy` carries the most headroom
of the flat subscriptions, while GPT/codex is already the roster's most
loaded family (Orchestrator primary, Stream & Network primary, and several
fallbacks) — concentrating this seat's high-throughput dispatch there too
would contend with higher-value judgment work on the same route. `terra`
remains Fallback for scoped edits that need more capability than flash.
`muse` remains documented (Section 5) for selective, high-value use only.

**Evaluation note, 2026-08-30 (Security Sign-off).** Promoted `claude-fable-5`
(Mythos-class, via the `claude` CLI) to sole primary, with `gpt-5.6-sol`
leading Secondary/Fallback for cross-family review when the patch under
review is Claude-authored (see *Stigmergic coordination*, Section 3). Basis:
the seat is the lowest-frequency, highest-stakes review on the roster, which
is where the most capable model belongs under Max 5x headroom; and
sol-as-sole-primary put GPT-authored patches (the primary implementation
route) under a same-family security review. Confirm headless availability
per CLI Routing Rule 3 before routing.

**Evaluation note, 2026-08-30 (QA & Verification).** Promoted `claude-5-sonnet`
to primary, demoting `gemini-3.7-flash` to fallback. Basis: the seat is
mixed — lint and regression-suite dispatch is mechanical (flash fits), but
TDD test design and CLJ/CLJS/CLJD parity verification is judgment work.
Cross-host parity bugs are silent and host-specific by nature (see the
project's own documented traps: the ClojureDart `:clj` reader-conditional
trap, cljs keyword-identity issues, `dart:core` aliasing) — exactly the
failure mode a low-judgment model won't catch. `gemini-3.7-flash` remains
available in Fallback specifically for the mechanical lint/regression loop.

**Active routing override, 2026-08-29.** The table's defaults name `glm-5.3` as
primary for VM Runtime and Storage & Indexing. GLM has 13% of its quota left
until the 2026-09-01 reset, so until then those two rows route to `codex`
(`gpt-5.6-luna`) with `claude-5-sonnet` as fallback, using each row's
Secondary/Fallback entries rather than the listed Primary. (Stream & Network's
table entry already reflects this outcome directly — `gpt-5.6-luna` is its
Primary, not an override — so no override applies there.) Claude moved to Max 5x on the
same date and is the highest-headroom route, so it absorbs review and
architecture load. This is a quota override, not an evaluation: restore the
defaults after the reset unless a repository-specific evaluation says otherwise.

**Evaluation note, 2026-08-30 (Compiler & AST, Frontend & Graphics).**
Command Code Pro is being downgraded to a $1/month plan, making `cmd`
catalog models (`qwen`, `kimi`, non-free MiniMax) structurally unreliable
as primaries, not just temporarily exhausted — the earlier framing of this
as a "quota override" to restore later no longer applies. Compiler & AST
primary is now `gpt-5.6-terra` (was Secondary/Fallback); Frontend &
Graphics primary is now `gemini-3.7-flash` (was Secondary/Fallback), both
flat-fee. `qwen` and `kimi` remain in each row's Fallback for opportunistic
use when `cmd` happens to have capacity. Frontend & Graphics capability fit
is unverified either direction — no repo-internal evidence for `flash` on
WebGL/WebGPU/canvas work, unlike the judgment-based swaps elsewhere in this
log. Routine Review primary is `gpt-5.6-sol`, replacing `qwen`
(demoted to opportunistic Fallback with the other two). **Scoping caveat,
not optional**: `sol` is GPT-family, and GPT (`terra`/`luna`) now authors
Compiler & AST and Stream & Network primarily, plus Subagent's fallback —
`sol` is the default only when the patch author's family doesn't rule it
out. Per *Stigmergic coordination* (Section 3), a GPT-authored patch
defaults to `gemini-3.7-flash` or other non-GPT review instead; using `sol`
there would recreate the same-family blind spot fixed for Security
Sign-off. This also concentrates more load on GPT/`codex`, already the
roster's most-loaded family (Orchestrator, Compiler & AST, Stream &
Network) — worth watching for contention as usage grows.

## 2. Provider Routing and Cost Policy
 
Use subscription quotas before metered providers. The user currently has:
 
- **Claude Max (5x), from 2026-08-29**: the Claude Pro plan was upgraded to Max 5x, so Claude is now the highest-headroom subscription rather than a rationed one. Route high-value architectural reasoning, formal proofs, routine and adversarial reviews, and security audits through `claude` (Claude Code CLI for `claude-5-opus` / `claude-5-sonnet`), and prefer it over metered routes for sustained review work. `agy`'s Claude models (`claude-opus-4-6-thinking`, `claude-3.7-sonnet`) draw on the separate `agy` plan quota;
- **MiniMax Free Access via `minimax/minimax-m3-free` (through September 5th)**: MiniMax is [free on Command Code through September 5th](https://commandcode.ai/docs/resources/pricing-limits#minimax-free). **CRITICAL ROUTING NOTE**: Always specify the exact model ID `minimax/minimax-m3-free` via `cmd --yolo -m minimax/minimax-m3-free`. Do NOT invoke `minimaxai/minimax-m3` (which draws against paid platform limits). Route GUI/postgraphics rendering, event streams, and broad test expansions through this free route;
- a yearly [Z.AI GLM Pro subscription](https://z.ai/subscribe) for `glm-5.3`. **Quota check 2026-08-29: 13% remaining until the 2026-09-01 reset.** Do not route new implementation work to GLM before the reset; reserve the remainder for work only GLM can do, and prefer `codex` or `claude`;
- an `agy` plan with included Claude and Gemini models, and a separate [Claude Max 5x subscription](https://claude.ai) providing direct access to Claude via the `claude` CLI;
- a USD 20/month [Command Code Pro plan](https://commandcode.ai/docs/resources/usage-limits) with access to its non-Claude catalog models (e.g. `qwen/qwen3.8-max`, `moonshotai/kimi-k2.7-code`; budget carefully as paid catalog models draw from platform limits);
- a dedicated [Meta Model API access](https://api.meta.ai) for `muse-spark-1.2-contributor` via `~/.local/bin/muse` (Claude Code wrapper targeting Meta's Anthropic-compatible endpoint). **This is metered, pay-per-token access, not a flat subscription** — unlike `agy`, `glm`, `codex`, and `claude`, every call costs money regardless of quota. Reserve it for selective, high-value use the flat subscriptions cannot cover, the same policy as DeepSeek; and
- metered DeepSeek access whose after-business-hours pricing is cheaper but is not free, reserved for selective execution-loop and data-structure analysis.
 
Use the `claude`, `agy`, `codex`, `glm`, and `cmd` subscriptions for routine work.
Reserve metered providers and pay-per-token frontier models for high-value
independent reasoning, high-stakes architectural proofs, and final security
review. Reuse related Claude, GLM, Muse, and Command Code sessions to reduce cold-start and
quota overhead.
 
| Model Family          | CLI              | Plan / Quota                                                        | Preferred Work                                                              |
| :---------------------| :----------------| :-------------------------------------------------------------------| :---------------------------------------------------------------------------|
| Claude                | `claude` / `agy` | Claude Max 5x / `agy` plan                                          | Architecture, formal invariants, adversarial reviews, security sign-off     |
| Gemini                | `agy`            | Google AI Pro ($19.99/mo)                                           | QA, TDD, cross-platform parity, daily orchestrator driver                   |
| GLM                   | `glm`            | Yearly GLM Pro (13% quota left)                                     | Storage, B-trees, DaoStream, RPC, DHT, VM runtime (fallback until reset)    |
| GPT                   | `codex`          | Codex Plus ($20/mo)                                                 | Primary implementation (DaoStream, RPC, state machines), compiler, lowering |
| Muse Spark            | `muse`           | Meta Model API (metered)                                            | Selective, high-value scoped edits the flat subscriptions cannot cover      |
| MiniMax / Qwen / Kimi | `cmd`            | Command Code Pro ($20/mo; **weekly** limit, confirm before routing) | Compiler lowering, AST transforms, cross-family review (`qwen3.8-max`)      |
| DeepSeek              | `deepseek`       | Metered (off-peak)                                                  | Selective data structures and VM execution-loop analysis                    |

### CLI Routing Rules

1. **Never invoke Claude through `cmd`**: Its pay-per-token use rapidly exhausts Command Code credits. Invoke Claude directly through `claude` (Claude Code) or `agy` using the Claude Max subscription.
2. **Never invoke Muse Spark through `cmd`**: Route Muse Spark exclusively through `~/.local/bin/muse` (which uses Claude Code configured with Meta Model API's Anthropic-compatible endpoint). Use the `contrib` model (`muse-spark-1.2-contributor`) — the cheapest Meta API tier, but still metered per-call; prefer a flat subscription (`agy`, `glm`, `codex`, `claude`) for any routine or high-volume work.
3. **Model Availability**: Before assigning work, confirm route availability with `agy models`, `cmd --list-models`, or the relevant CLI's model listing. If a model is absent in Codex or `agy`, use the next listed fallback instead of routing it through pay-per-token Command Code credits.

### External CLI Shell-Out Requirement (No Native Subagents)

Delegated engineering tasks must never be routed through AGY's native subagent tool (`invoke_subagent`). The explicit reason is that **AGY native subagents are limited strictly to models provided by AGY (the Gemini family)**. 

To execute the team roster with true multi-provider model diversity, leverage the user's active subscriptions (Claude Max 5x, yearly GLM Pro, OpenAI/Codex Plus, and Command Code Pro), and guarantee genuine cross-family adversarial reviews, the Orchestrator must shell out directly to external CLI coding agents (`claude`, `glm`, `codex`, `cmd`, `muse`) via shell execution tools (`run_command`).

Provider routing establishes **where** work is assigned and which model plans are utilized. Section 3 defines **how** team members collaborate through persistent artifacts, verify diffs, and report commit readiness.

## 3. Collaboration Lifecycle

The orchestrator owns scope, local verification, consensus, and reporting commit readiness.
Delegated claims remain untrusted until checked against repository state. **The orchestrator
must never stage (`git add`) or commit (`git commit`) changes unless explicitly instructed by the user.**

1. **Write the contract first.** Express the next behavior as failing tests or
   an equally precise design contract.
2. **Delegate through an artifact.** Select the role and a suitable model, then
   create a timestamped prompt file from that role's template under `collab/<role>-`.
3. **Implement and verify locally.** Let the implementation agent edit only
   when authorized, inspect its actual diff, and run the relevant tests.
4. **Review adversarially.** Use a different model family to identify concrete
   correctness, architecture, security, portability, and coverage issues.
5. **Reach consensus.** Verify findings, explain disagreements, resume the same
   reviewer session, and fix accepted issues.
6. **Verify and report commit readiness.** Verify all phase tests and diffs, report
   commit readiness and risk assessment to the user, and stage/commit only when
   explicitly instructed to do so.

### Stigmergic coordination

- Communicate through specifications, tests, source diffs, and persisted
  findings—not private conversational context.
- Prefer sibling agents in separate working copies for independent parallel
  work. Use parent-child chains when serialization is inherent.
- Have a different model family review whichever model authored the patch.
  Patches authored by GPT models (`gpt-5.6-terra`/`-luna`) default to `gemini-3.7-flash`
  or non-GPT review; patches authored by GLM models default to non-GLM review;
  patches or designs authored by Claude models (including `claude-fable-5` as
  Architect) default to `gpt-5.6-sol` or other non-Claude review.
- Inspect every cited location and rerun relevant tests. Never accept another
  CLI's self-reported test result as proof.
- **Avoid redundant test execution by delegated agents**: If the orchestrator has
  already run and verified the tests before delegating, the delegation brief must
  explicitly state that the identical diff has already been verified locally and
  instruct the delegated agent **NOT** to re-run the full test suite. Re-running
  test suites across external CLIs wastes time, tokens, and subscription resources.
  Delegated reviewers and architects must focus on static analysis, contract
  reasoning, invariant proofs, and diff auditing (Security Sign-off auditors
  remain free to re-verify if high-risk invariant boundaries demand it).

### Verifying a shell-out actually ran

**A reported zero exit code is not evidence of work.** Each of these
accomplished nothing and was caught only by inspecting the work product:

| Shape                  | What it looked like                              | How it was caught                                        |
| :----------------------| :------------------------------------------------| :--------------------------------------------------------|
| CLI usage error        | Help text where the report should be             | No findings file was written                             |
| Wrong flag position    | `error: unexpected argument '-s' found`          | Report file absent                                       |
| Zero tests executed    | `clojure -M:test -n <ns>` exit 0, "0 tests"      | Namespace was `:cljd`-guarded, so nothing ran on the JVM |
| Stale build output     | `bb test:cljd` green, including a *deleted* test | Generated Dart predated the source edit                  |
| Sandboxed verification | "0 failures" with silently skipped socket tests  | Assertion count far below a local run                    |

After every delegation, check the artifact, not the status: does the findings
file exist, does the diff contain the change, did the reported test counts move
in the direction the task predicted?

**Distinguish the status a command prints from the status its caller sees.**
Both `codex` and `muse` exit `2` on a usage error, so the failures above were
legible at the source; what hid them was reading the wrong status.

```sh
# The echo DOES print the CLI's real status (exit=2) into the captured output.
# The trap is that the list's own status is echo's, i.e. 0 — so an outer
# wrapper, harness, or `&&` chain reports success for a run that failed.
codex exec ... > run.log 2>&1; echo "exit=$?"

# Propagate the CLI's status as the list's status instead:
codex exec ... > run.log 2>&1; rc=$?; echo "exit=$rc"; exit $rc
```

So: read the printed status and the log the CLI actually wrote, not a wrapper's
summary of the compound command — and when a wrapper is the only thing you will
see, make the CLI's status the list's status.

**Findings files often must be written by the orchestrator.** A reviewer run
under `--permission-mode plan`, `-s read-only`, or `--disable-write` *cannot*
write its own report no matter what the brief says. Save its stdout to the
conventional path yourself rather than losing the audit trail.

### Running `clojure` / `clj` in this repository

Three invocation traps here have each produced a result that looked like a
finding but was a broken command. All three were caught by reading output the
run had actually written, not by the exit code.

**An alias with `:main-opts` hijacks your `-i` / `-e`.** `:test` carries
`["-m" "cognitect.test-runner"]` and `:dep-graph` carries `["-m" "dep-graph"]`,
so `clojure -M:test -e '<form>'` runs the *test runner*, not your form — and
reports "Ran 0 tests", which reads like an answer. To evaluate your own code
against the project classpath, define a throwaway alias that has no
`:main-opts`:

```sh
clojure -Sdeps '{:aliases {:hunt {:extra-paths ["test" "src/dev"]
                                  :extra-deps {org.clojure/tools.namespace
                                               {:mvn/version "1.5.0"}}}}}' \
  -M:hunt -i script.clj
```

**`-A` with main opts is deprecated.** `-A` is for REPL aliases; `-M` invokes
`clojure.main`. Passing `-i`/`-e`/`-m` alongside `-A` still works by an
implicit fallthrough, but warns:

```text
WARNING: Implicit use of clojure.main with options is deprecated, use -M -i <file>
```

Note that `-A` also applies the alias's `:main-opts` despite `clojure --help`
describing it as classpath-only, so switching `-A` to `-M` does not by itself
escape the hijack above — the alias must have no `:main-opts`.

**`-e "$(cat script.clj)"` corrupts Clojure source.** The shell expands inside
double quotes, and `#"-test$"` contains `$"`, which bash treats specially — the
regex arrives mangled and the script silently matches nothing. Pass the file
with `-i` instead of interpolating it.

**Do not filter a run's output down to the lines you expect.**
`… 2>&1 | grep MARKER` discards the deprecation warning, the stack trace, and
the "0 tests" line that would have told you the command did something other
than what you asked. Read the tail of the real output first, then filter.

### Concurrency limits when agents share one working tree

Isolated worktrees remain the default for concurrent **edits**; the shared-tree
mode below is the documented exception, not a replacement, and it applies only
when the work under review is uncommitted (see the caveat in *Speculative
Parallelism* below, which a worktree cannot carry).

Order of preference when several delegations must run at once:

1. **Read-only reviewers may always share the tree.** They take no edit lock and
   need no isolation.
2. **Concurrent editors go in separate worktrees** whenever the base is
   committed.
3. **Only when the base is uncommitted** may concurrent editors share one tree,
   and then only under strict file-ownership partitioning: every brief names the
   exact files it may touch and forbids all others, and no two concurrent briefs
   name the same file. If two tasks need the same file, serialize them.
4. If neither isolation nor a clean partition is achievable, commit the work in
   progress first (with the user's instruction) and return to worktrees.

Under mode 3, two further constraints apply:

1. **Only one agent may run a ClojureDart build at a time.** `bb test:cljd`
   writes `test/cljd-out/`; concurrent builds clobber it and corrupt everyone's
   evidence. Name one delegation the owner of the `:cljd` lane and forbid the
   rest, or defer all `:cljd` verification to a single consolidated pass.
2. **Say which host suites a delegate may run.** Prefer focused
   `clojure -M:test -n <ns>` commands in briefs, and state the current baselines
   so the agent can tell a real change from noise.

### File-based collaboration and timestamps

Prompt, contract, review, and findings files are the canonical handoff. They avoid
process-table exposure and shell escaping limits while leaving a persistent audit trail.

All inter-agent collaboration artifacts live **flat** in `collab/` — no
subdirectories. The directory is working-tree-only and must not be staged or
committed (see *Permanence and locality* below); keeping it flat means one
`ls`, or one magit hunk, shows every brief and finding in flight.

The role is a filename prefix rather than a directory, so the listing still
sorts by role:

```text
collab/<role>-<task>.prompt.md
collab/<role>-<task>.<model>.findings.md
```

| Role prefix     | Artifacts                                                                     |
| :---------------| :-----------------------------------------------------------------------------|
| `orchestrator-` | Plan decomposition, phase status, handoff briefs, commit readiness            |
| `architect-`    | Architectural designs, axiom reviews, subsystem boundary specs, ADR proposals |
| `review-`       | Adversarial code reviews, security sign-offs, diff audits, consensus logs     |
| `qa-`           | TDD specifications, verification test plans, regression audit logs            |
| `vm-`           | CESK machine, bytecode dispatch, continuation runtime briefs and findings     |
| `storage-`      | B-tree, indexing, DaoSpace storage briefs and findings                        |
| `compiler-`     | Yang AST lowering, parser, macro expansion briefs and findings                |
| `stream-`       | DaoStream framing, network transport, RPC briefs and findings                 |
| `graphics-`     | DaoGUI, WebGL/WebGPU, canvas rendering briefs and findings                    |
| `subagent-`     | Scoped tool/linter runs, file searches, worker outputs                        |

Copy the prompt template from the selected role file into `collab/` under that
prefix and replace every placeholder. **All files generated by agents or human
operators MUST begin with timestamp headers in both GMT and Local Timezone:**

Every prompt file must begin with:

```text
Created-GMT: 2026-08-26 05:16:00 GMT
Created-Local: 2026-08-26 12:16:00 Asia/Ho_Chi_Minh

# Task: <Task Name>

Role: <Role Name>

Implementers:
- Model: <model-name> | Assigned: <YYYY-MM-DD HH:MM:SS local-timezone> | Status: <active|completed|timed-out|failed> | Rationale: <reason>
```

The **Role** remains constant throughout the task's lifetime (defining the engineering scope and subsystem responsibility), while the **Implementers** list grows as models are assigned or reassigned.

Use the actual local timezone name (e.g., `Asia/Ho_Chi_Minh`), not merely a numeric offset.
Every prompt must require the delegated response / report to begin with:

```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
```

An unfilled template or missing timestamp is not a valid brief. Preserve both timestamp
pairs in all prompt and findings artifacts.

**Model-Tagged Findings Files (`<task>.<model>.findings.md`)**:
All findings, reports, and review deliverables produced by a delegated model MUST be named with the responding model's identifier in the filename suffix:
```text
collab/<role>-<task>.<sanitized-model-name>.findings.md
```
Examples:
- `collab/review-dao-stream-core-adversarial-review.claude-5-sonnet.findings.md`
- `collab/architect-jank-jolt-compatibility-analysis.claude-5-opus.findings.md`
- `collab/vm-fix-yin-vm-test-failures.glm-5.3.findings.md`

This naming convention enables **speculative parallel exploration**: the orchestrator can dispatch the exact same prompt to multiple different models concurrently (e.g. `claude-5-sonnet` and `gpt-5.6-sol`), producing independent, non-colliding findings side-by-side for comparison.

**`.stdout.log` files are intermediate, not reports.** Redirecting a run to
`collab/<role>-<task>.<model>.stdout.log` captures the transcript; it does not
satisfy the contract above. Whenever a delegate does not write its own
`.findings.md` — which a read-only or write-denied reviewer *cannot* do, however
the brief is worded — the orchestrator must promote the agent's final response
into `collab/<role>-<task>.<model>.findings.md`, preserving its
`Completed-GMT` / `Completed-Local` header. Verify the findings file exists
before treating a delegation as complete; four of five delegations in one
recorded round did not write their own.

**Permanence and locality (DO NOT DELETE, DO NOT COMMIT)**: Files created in
`collab/` must never be deleted, truncated, or purged. Three narrow exceptions,
none of which destroys a report: `<task>.<model>.heartbeat` is a live state
snapshot and is overwritten in place by design (see *Stigmergic Heartbeat
Protocol*); a prompt's `Implementers:`
entries may have their `Status:` field updated in place on reassignment (see
*Task Reassignments*); and `.stdout.log` files are intermediate captures rather
than reports and may be overwritten — but only after their response has been
promoted into a `.findings.md`, or under an attempt-qualified name
(`<task>.<model>.attempt-2.stdout.log`) when an earlier attempt's record must
survive. Findings bodies, prompt bodies, and `.progress.log` files remain
strictly append-only: never delete or truncate their content.

They are the project audit trail for tracing requirements, architectural decisions, model deliberations,
consensus resolutions, and test verification logs.

They are also **local to the working tree**. Do not `git add` or commit them:
when staging a change, stage source, tests, and docs, and leave `collab/`
untracked. Two consequences follow, and both matter:

- Because git is not backing these files up, deleting one destroys it outright.
  There is no `git checkout` to recover from. Treat the directory as
  append-only.
- A fresh clone, a `git clean -fdx`, or a different machine has no `collab/` at
  all. Anything another party must be able to read — a decision, an invariant,
  a migration rule — belongs in `docs/`, not only in `collab/`.

`collab/` is deliberately **not** in `.gitignore`: leaving it untracked-but-visible
keeps the live briefs, findings and heartbeats in view in `git status` and magit,
which is the point of the directory. That means the rule is discipline, not
enforcement — when staging, name paths explicitly (`git add -- src test docs`)
rather than reaching for `git add -A`, which would sweep the whole audit trail
into the commit. The ~100 artifacts committed under an earlier policy have been
untracked; nothing under `collab/` is in the index any more.

**Task Reassignments & Growing Implementer List**: Whenever a task fails, times out, or is handed over to a different model, the orchestrator updates the previous implementer's status (e.g., `Status: timed-out` or `Status: failed`) and appends a new implementer entry to the `Implementers:` list in the `.prompt.md` file, recording the new assignment timestamp, model name, and handoff rationale.

### Stigmergic Heartbeat Protocol

To allow the orchestrator and human operator to track real-time agent liveness across long-running tasks:

1. **Heartbeat File (`.heartbeat`)**: For any non-trivial task (spanning multiple file reads, edits, or verification passes), the delegated agent maintains an in-place live state snapshot at:
   ```text
   collab/<role>-<task>.<model>.heartbeat
   ```
2. **Snapshot Format (Overwritten In-Place)**: Rather than an append-only log, the heartbeat is a concise state snapshot overwritten by the agent on each operational boundary:
   ```text
   Timestamp-GMT: 2026-08-27 05:15:00 GMT
   Timestamp-Local: 2026-08-27 12:15:00 Asia/Ho_Chi_Minh
   Model: claude-5-sonnet
   Status: in-progress
   Current-Activity: Applying read-authority check order in src/cljc/dao/stream/reference.cljc (step 2/4)
   ```
3. **Progress Log (`.progress.log`)**: If an append-only historical trace of individual sub-steps is desired, the agent may optionally maintain `collab/<role>-<task>.<model>.progress.log`.
4. **Universality**: This protocol is file-based and operates identically across `claude` (Claude Code), `codex`, `cmd` (Command Code), `muse` (Muse Code), and `agy` (Antigravity).
5. **Watchdog Verification**: The orchestrator checks the `.heartbeat` file's timestamp and `Current-Activity` to distinguish active deep computation from stalled or hung processes.

### Speculative Parallelism & Git Worktree Isolation Protocol

When multiple LLM agents are dispatched to implement or explore solutions for the same task concurrently:

> **Caveat — worktrees branch from a commit.** `git worktree add` gives the
> agent a tree at `HEAD` (or `--worktree-base`), so it does **not** carry
> uncommitted staged or working-tree changes. When the work under review is
> uncommitted — the common case for a review-and-fix round — a worktree hands
> the agent a tree without the code it was asked to change. Partition by file
> ownership instead, and see *Concurrency limits when agents share one working
> tree* above.

1. **Conflict Prevention via Git Worktrees (`.workspaces/`)**:
   - Never allow two concurrent agents to edit files in the same working tree
     simultaneously, except under the file-ownership partitioning of
     *Concurrency limits when agents share one working tree* (mode 3), which
     exists only because a worktree cannot carry uncommitted work.
   - Use Git Worktrees (`git worktree add`) to spawn lightweight, isolated filesystem workspaces under `.workspaces/` (excluded from git via `.gitignore`), sharing the central `.git` database without storage bloat.

2. **Worktree Lifecycle**:
   - **Creation**:
     ```bash
     git worktree add .workspaces/<agent-name>-<task> -b exp/<agent-name>-<task>
     ```
   - **Execution**: Each agent is invoked with `Cwd` pointing to its dedicated `.workspaces/<agent-name>-<task>` directory.

     > **`collab/` does not exist in a worktree.** It is gitignored, so
     > `git worktree add` produces a tree with no `collab/` at all: the brief
     > the agent was told to read is absent, and any findings it writes land in
     > the auxiliary tree, stay invisible to the primary tree, and leave
     > untracked files that make `git worktree remove` refuse without
     > `--force`.
     >
     > Therefore every worktree brief must reference **absolute paths in the
     > primary tree** for both input and output, e.g.
     > `/Users/<you>/workspace/datomworld/collab/<role>-<task>.prompt.md` and
     > `.../collab/<role>-<task>.<model>.findings.md`. Never use a
     > `collab/`-relative path in a worktree brief. If an agent nonetheless
     > writes findings inside its worktree, copy them back into the primary
     > `collab/` before pruning, and verify the copy landed before running
     > `git worktree remove`.
   - **Evaluation & Merge**: The orchestrator runs local verification suites (`bb test:clj`) across worktrees, inspects the diff (`git diff exp/<model1> exp/<model2>`), merges the winning implementation into the integration branch, and prunes the temporary worktrees:
     ```bash
     git merge exp/<winning-model>-<task>
     git worktree remove .workspaces/<agent-name>-<task>
     git branch -D exp/<agent-name>-<task>
     ```

### Sessions, patience, and follow-up

- Batch a complete brief into each invocation rather than paying repeated
  cold-start costs for quick questions.
- Reuse a session for related follow-ups with `--resume`; start a new session
  only for unrelated work.
- Expect rounds to take three or more minutes. A healthy agent can be silent
  for more than five minutes while reading, editing, or validating.
- Never terminate an agent because it is slow or quiet. Poll process health and
  continue until it returns, exits, or emits an explicit process error. Report
  the error before choosing a fallback.

| Target   | Session store    | Follow-up                                                                                                                        |
| :--------| :----------------| :--------------------------------------------------------------------------------------------------------------------------------|
| `claude` | `~/.claude`      | `--resume <session-id>`                                                                                                          |
| `glm`    | `~/.claude-glm`  | `--resume <session-id>`                                                                                                          |
| `cmd`    | `~/.commandcode` | `--resume <name-or-id>`                                                                                                          |
| `codex`  | `~/.codex`       | `codex exec [-s <mode>] resume <session-uuid>` (flags before `resume`; `--last` only when no other `codex` session is in flight) |
| `muse`   | `~/.claude-muse` | `--resume <session-id>`                                                                                                          |

## 4. Security, Privacy, and Sandboxing

### Explicit authorization

- Private repository content is an external disclosure. Obtain direct user
  authorization for the exact payload and destination before transmission.
- Never broaden authorization for a staged diff into permission to disclose
  unstaged files or the general workspace.
- Invoke the external CLI from the agent that received authorization. A
  delegated agent cannot rely on consent relayed through another agent.

### Payload protection

- Never put private diffs or source directly in `-p "<payload>"`; command
  arguments can appear in process tables and shell logs.
- Put detailed prompts in files and pass them over stdin, or point the agent at
  explicitly authorized paths.
- Use an isolated working directory for file-scoped reviews when practical.

```sh
(cd /private/tmp && ~/.local/bin/glm --name btree-review --bare \
  --permission-mode plan --allowed-tools Read --output-format text \
  -p "Read /Users/sto/workspace/datomworld/src/cljc/dao/data/btree.cljc. Check positional datom index realization.")
```

### Permissions and credentials

- Run `agy` reviews under `--mode plan --sandbox`; grant only required host
  state permissions.
- Run wrapper-based read-only reviews with
  `--permission-mode plan --allowed-tools Read`.
- Never use `--dangerously-skip-permissions` without explicit approval.
- Never print, copy, echo, or commit wrapper configuration, API tokens, or
  local credentials.

## 5. Invocation Guide

### Claude through `claude` or `agy`

With the Claude Max subscription, use the official `claude` CLI (Claude Code) for architectural arbitration, formal invariant verification, and security reviews:

```sh
claude --permission-mode plan \
  --tools Read \
  --output-format text \
  -p "You are the Lead System Architect. Review docs/design/dao.stream.md against core axioms."

claude --permission-mode plan \
  --tools Read \
  --output-format text \
  -p "You are the Security Auditor. Review the supplied diff for capability-token leaks."
```

For follow-up consensus and reviews, resume the session in `~/.claude`:

```sh
claude --resume <session-id> --permission-mode plan \
  --tools Read \
  --output-format text \
  -p "Read the consensus follow-up file and reassess only disputed findings."
```

Alternatively, invoke Claude via `agy`:

```sh
agy --model claude-opus-4-6-thinking --effort high --mode plan --sandbox \
  --print-timeout 5m --output-format text \
  -p "Review the supplied diff for capability-token leaks."
```

Do not substitute `cmd` for Claude invocations to avoid metered pay-per-token charges.

### Gemini through `agy`

Reasoning models such as `gemini-3.7-flash` require
`--effort <low|medium|high>`.

```sh
agy --model gemini-3.7-flash --effort medium --mode plan --sandbox \
  --print-timeout 5m --output-format text \
  -p "You are the QA & Verification Engineer. Perform a read-only TDD audit."

agy --model gemini-3.1-pro-high --mode plan --sandbox \
  --print-timeout 5m --output-format text \
  -p "Verify invariants across docs/design/dao.stream.md."
```

### GLM through `glm`

Use the absolute wrapper path, native model code (`glm-5.3`), explicit session name, and no
stdin redirection. Keep `-p` as the final option before the prompt; arguments
after it may be consumed as prompt text.

> **GLM model naming**: The catalog label `zai-org/GLM-5.3` is not the wrapper model name. The local wrapper uses `glm-5.3`; never pass the catalog label as its `--model` value. Set `GLM_MODEL=glm-5.3` and give non-interactive sessions a `--name`.

```sh
GLM_MODEL=glm-5.3 script -q /dev/null ~/.local/bin/glm \
  --name dao-stream-review --bare \
  --permission-mode plan --allowed-tools Read \
  --output-format text \
  -p "Read <paths>. <question and exit criteria>. Return file:line, claim, and suggested fix." \
  > collab/stream-dao-stream-review.md
```

`glm` may emit the following expected, non-fatal SDK warning:

```text
[claude-code:unrecognized_model] {"model":"glm-5.3","query_source":"sdk"}
```

Do not infer failure or terminate from this warning. Delegated runs can be
slower because each process pays startup, endpoint authentication, prompt
ingestion, SDK initialization, sandbox checks, and buffered-output latency.
Passing `--name`, allocating a PTY, and resuming the session mitigate those
costs. Do not redirect GLM stdin or run it inside a sandbox that blocks its
model endpoint.

```sh
GLM_MODEL=glm-5.3 script -q /dev/null ~/.local/bin/glm \
  --resume <session-id> --bare \
  --permission-mode plan --allowed-tools Read \
  --output-format text \
  -p "Read the consensus follow-up file and reassess only disputed findings."
```

### GPT through `codex`

Prefer the stdin form (`-`) over an inline prompt string: Section 4 forbids
private payloads in command arguments, and every `codex` subcommand that takes a
prompt accepts `-` to read it from stdin.

**Project policy: invoke `codex` with `-s danger-full-access` for implementation.**
The workspace sandbox costs more in false findings than it buys here -- it
silently blocks socket binding and the Flutter cache, so a delegate reports
failures that are artefacts of the sandbox rather than of its change, and the
orchestrator cannot tell the two apart without re-running everything locally.
Reviews stay `read-only`, because a reviewer has no reason to write.

```sh
# Headless review (read-only)
codex exec -m gpt-5.6-sol -s read-only - < collab/review-task.prompt.md \
  > collab/review-task.<model>.stdout.log

# Implementation (edits allowed, unsandboxed per project policy)
codex exec -m gpt-5.6-luna -s danger-full-access - < collab/stream-task.prompt.md \
  > collab/stream-task.<model>.stdout.log

# Purpose-built code review; prefer over `exec` for review tasks.
# -m is a GLOBAL codex option and must precede the subcommand: `review` has
# none of its own, so omitting it silently inherits the locally configured
# model, mislabelling the artifact and bypassing the routing policy.
codex -m gpt-5.6-sol review - < collab/review-task.prompt.md \
  > collab/review-task.gpt-5.6-sol.stdout.log
```

Always redirect to the conventional path: a review whose output is not persisted
leaves no artifact, and a read-only reviewer cannot write its own findings file.
Because `>` truncates, promote the response into `.findings.md` before re-running
the same task/model, or redirect the retry to
`<task>.<model>.attempt-<n>.stdout.log`.

Sandbox modes are `read-only`, `workspace-write`, and `danger-full-access`.

**Consensus follow-up** resumes the prior session so the reviewer keeps its own
findings in context:

```sh
# Safe under parallelism: name the session explicitly
codex exec -s read-only resume <session-uuid> - < collab/review-followup.prompt.md \
  > collab/review-followup.<model>.stdout.log
```

> **`--last` is unsafe during parallel delegation.** It resumes the most recent
> recorded session, which is whichever `codex` invocation started last — not
> necessarily the reviewer you mean to continue. Since speculative parallelism
> is an encouraged workflow here, pass the explicit session UUID or thread name
> and reserve `--last` for a run you know is the only `codex` session in flight.

> **Flag-order trap**: `-s` belongs to `exec`, never to `resume`, whose only
> arguments are `[SESSION_ID] [PROMPT]`. Writing
> `codex exec resume --last -s read-only` fails with
> `error: unexpected argument '-s' found` and exits `2` — which is easy to miss
> if a wrapper reports the status of a later command in the list. See
> *Verifying a shell-out actually ran* in Section 3.

`danger-full-access` runs the delegate unsandboxed: it can write anywhere the
user can, not merely inside the repository. Brief it to the files it owns, and
read its diff rather than its summary.

Under `workspace-write` instead, two limits apply, recorded because they have
each produced a false finding:

- **Socket binding is off by default**, so JVM tests that bind localhost fail as
  a sandbox artefact. It is a config toggle rather than a hard limit -- add
  `-c sandbox_workspace_write.network_access=true`, or set
  `[sandbox_workspace_write] network_access = true` in `~/.codex/config.toml`.
  The emitted seatbelt policy then carries
  `(allow network-bind (local ip "*:*"))` and
  `(allow network-inbound (local ip "localhost:*"))`. Verified 2026-08-29 by
  binding `127.0.0.1:0` inside `codex exec -s workspace-write`.
- **The Flutter cache cannot be written**, so ClojureDart builds fail before
  enumeration. No toggle for this one; `danger-full-access` is what clears it.

Two traps found while checking the first: the standalone `codex sandbox`
subcommand does **not** honour that config key -- it takes sandbox-state flags
(`--sandbox-state-disable-network`) instead, so a test there reports a false
negative; and `--strict-config` is an `exec` flag, not a `sandbox` one.

### Muse Spark through `muse` (Claude Code wrapper)

`~/.local/bin/muse` launches Claude Code configured with the Meta Model API's Anthropic-compatible endpoint (`https://api.meta.ai/v1`) using the user's Meta API key and an isolated session configuration directory in `~/.claude-muse`.

Use the wrapper path, default to the cost-effective contributor model (`muse-spark-1.2-contributor`), explicit session name, and no stdin redirection. Keep `-p` as the final option before the prompt:

```sh
MUSE_MODEL=muse-spark-1.2-contributor script -q /dev/null ~/.local/bin/muse \
  --name subagent-task --bare \
  --permission-mode plan --allowed-tools Read \
  --output-format text \
  -p "Read <paths>. <question and exit criteria>." \
  > collab/subagent-task.md
```

`muse` may emit the expected, non-fatal SDK warning:

```text
[claude-code:unrecognized_model] {"model":"muse-spark-1.2-contributor","query_source":"sdk"}
```

Do not infer failure from this warning.

To resume an existing session:

```sh
MUSE_MODEL=muse-spark-1.2-contributor script -q /dev/null ~/.local/bin/muse \
  --resume <session-id> --bare \
  --permission-mode plan --allowed-tools Read \
  --output-format text \
  -p "Read the consensus follow-up file and reassess findings."
```

### Catalog models through `cmd`

Use `cmd -m <model>` for non-Claude, non-Muse catalog models (models listed at https://commandcode.ai/docs/reference/cli/models) for read-only review and autonomous implementation.

Key flags:

- `-p, --print` runs headlessly, reading stdin when no prompt string follows.
- `-m, --model <model>` selects a non-Claude catalog model.
- `-n, --name <name>` creates a resumable named session.
- `--resume`, `--continue`, `--session`, and `--fork-session` control sessions.
- `--plan` is read-only and disables edits and command side effects.
- `--tools-all` exposes all tools; `--tools-enable` provides an allowlist.
- `--auto-accept` approves actions but does not expose withheld tools.
- `--no-session` prevents transcript persistence and therefore resumption.
- `--output-format <text|json>` selects text or NDJSON events.
- `--effort <low|medium|high>` controls supported models' reasoning effort.
- `-t, --trust` accepts the repository trust prompt.

Canonical file-based review:

```sh
cmd -p \
  -m minimax/minimax-m3-free \
  --plan --no-session --output-format text \
  < collab/review-task_prompt.md \
  > collab/review-task_findings.md
```

Canonical file-based implementation:

```sh
cmd -p \
  -m minimax/minimax-m3-free \
  --auto-accept --tools-all --output-format text \
  < collab/subagent-task_prompt.md \
  > collab/subagent-task_findings.md
```

Implementation must not use `--plan`; it needs `--tools-all` or a sufficient
`--tools-enable` allowlist in addition to `--auto-accept`. A text response with
no requested source changes is incomplete even if the agent reports success.

For related work, omit `--no-session` and resume the same context:

```sh
cmd -n "dao-stream-audit" \
  -m minimax/minimax-m3-free --plan \
  -p "Read the timestamped prompt file and perform the review."

cmd --resume "dao-stream-audit" --plan \
  -p "Read the consensus follow-up file and reassess the findings."
```

Use `--fork-session` only for an intentional independent branch. Start a new
session for unrelated work.

### DeepSeek through `deepseek`

Unlike GLM, wrappers that pause on an open pipe may need stdin closed:

```sh
~/.local/bin/deepseek --bare --permission-mode plan \
  --allowed-tools Read --no-session-persistence --output-format text \
  -p "Read docs/design/dao.stream.md only. Return a concise review." \
  < /dev/null
```
