# dao.agent: Autonomous Agent Coordination via Stigmergy, Register VM, and MCP

Status: design specification (2026-09-24)

## 1. Objective and Core Philosophy

This document specifies the architectural foundation for autonomous coding
agents (Claude, Codex, AGY) coordinating through generative communication
(stigmergy) over `dao.space`, governed by the de Bruijn Register VM (`yin.vm`),
and interfacing with external agent runtimes via the Model Context Protocol
(MCP).

In `datom.world`, the fundamental axiom is that **computation moves, data
stays**. Coordination among autonomous agents must not rely on fragile,
point-to-point message routing, shared mutable memory, or unmediated host
access. Instead:

1. **`yin.vm` as Substrate, `dao.agent` as Membrane**:
   The namespace `yin.vm` is strictly low-level execution substrate (ISA,
   register file, frames, memory stores, continuation packing, step budgets).
   It has zero awareness of LLMs, prompts, roles, or agents. All agent
   constructs, schemas, harnesses, and MCP protocol adapters belong to the
   canonical root namespace **`dao.agent`**.
2. **The Register VM ($R$) is the Execution Membrane**: Every coding agent
   executes inside or is strictly governed by the de Bruijn Register VM.
   The agent has zero direct host access; its only actuators are bounded
   VM instructions (`:stream-put`, `:stream-next`, `:store-write`, `:park`,
   `:resume`, `:call`, `:ffi-call`).
3. **Stigmergy over `dao.space`**: Agents do not address one another. They
   perceive the environment by matching immutable datoms in `dao.space`
   (`dao.space.query/q`), deliberate, and deposit atomic traces into their
   own single-writer append-only streams (`dao.stream`).
4. **MCP as the Wire Protocol Adapter**: The Model Context Protocol (MCP)
   provides the standard JSON-RPC tool-calling interface through which
   LLMs interact with `dao.space` / `yin.vm` and through which the Register VM
   governs external host tools.
5. **Continuation Parking Across Delays and Quotas**: External delays (network
   latency, human-in-the-loop review, rate-limit quota exhaustion) cause the
   Register VM to emit a `:park` effect, capturing a sparse, content-addressed
   continuation snapshot (`register-snapshot`) in `dao.jing`. When external
   conditions clear, `:resume` restores execution deterministically.

---

## 2. Namespace Architecture and Layer Boundaries

Autonomous agent infrastructure is organized strictly under `dao.agent.*`:

```text
+-------------------------------------------------------------------------+
|                              dao.agent                                  |
|  (Autonomous agents, stigmergy, task coordination, agent lifecycle)     |
+------------------------------------+------------------------------------+
|  dao.agent.mcp.server              |  dao.agent.mcp.client              |
|  (JSON-RPC MCP server exposing     |  (FFI client bridge invoking host  |
|   dao.space & VM tools to LLMs)    |   MCP tools from Register VM)      |
+------------------------------------+------------------------------------+
|  dao.agent.schema                  |  dao.agent.harness                 |
|  (Datom task, claim, artifact,     |  (Multi-agent transactor harness,  |
|   review, and verdict schemas)     |   stigmergic loop governor)        |
+------------------------------------+------------------------------------+
                                      |
                                      v
+-------------------------------------------------------------------------+
|                  Low-Level Substrates (Zero Agent Logic)                |
+------------------------------------+------------------------------------+
|  dao.space / dao.stream / dao.jing |  yin.vm (Register & Stack VMs)     |
|  (Datoms, streams, immutable blob) |  (Nameless bytecode, registers)    |
+------------------------------------+------------------------------------+
```

### Invariants:
1. `yin.vm` NEVER requires `dao.agent.*`.
2. `dao.agent.*` builds on top of `yin.vm`, `dao.space`, `dao.stream`, and
   `dao.jing`.
3. Agent identity, prompts, roles, and consensus policies reside exclusively in
   `dao.agent.*`.

---

## 3. Foundational Invariants

### Invariant 1: Zero Naked Host Access
An agent cannot execute naked shell commands, write raw files to the host OS
filesystem, or open unmetered network sockets. All perceptions and actions are
expressed as datoms or Register VM instructions. Host capabilities are reached
strictly through bounded `:ffi-call` descriptors governed by explicit step
budgets (`:steps-remaining`).

### Invariant 2: Single-Writer Provenance
Every agent owns exactly one append-only stream (`dao.stream.memory-log` or
durable Jing segment). All mutations to `dao.space` are appended as atomic
transaction records `[e a v t m]` carrying full author attribution and
provenance metadata in slot `m`. No agent can mutate or truncate another
agent's log.

### Invariant 3: Sparse Continuation Capture
At every boundary opcode (`:call`, `:stream-put`, `:stream-next`, `:ffi-call`,
`:current-continuation`, `:park`, `:resume`), the Register VM enforces exact
live-slot vectors (`:live-slots`). When an agent continuation is parked, only
active live registers and frame references are captured. Continuations are
first-class, content-addressed records stored in `dao.jing`.

### Invariant 4: Content-Addressed Code Artifacts
Code produced by coding agents is never stored as unversioned text files. Code
is represented as Universal AST datoms and compiled into closed, nameless de
Bruijn images ($R$ for register format, $H$ for stack format). Images are
linked, verified, and fetched over `dao.stream` via the Phase B6 linker
(`yin.vm.linker`).

---

## 4. The Stigmergic Interaction Model

Agents collaborate through traces left in the shared medium (`dao.space`):

```text
                       +-----------------------------------+
                       |      Shared Medium: dao.space     |
                       |  (Single-Writer Datom Streams)    |
                       +-----------------------------------+
                                ^                 ^
                     datoms / q |      datoms / q |
                                v                 v
                       +-------------+   +-------------+
                       | Register VM |   | Register VM |
                       |  (Claude)   |   |   (Codex)   |
                       +-------------+   +-------------+
                              |                 |
                         :ffi-call         :ffi-call
                              v                 v
                        [Claude Model]    [Codex Model]
```

The agent reasoning cycle consists of three beats:

### 4.1. Perceive: Querying the Medium
The agent inspects `dao.space` using declarative pattern matching (`q` /
`match`):
- "Are there tasks in `:task/status :ready` matching my capabilities?"
- "What review findings have been posted for image $R$?"
- "Has the test runner deposited an outcome for transaction $T$?"

In bytecode, this is executed by issuing a query effect or reading from an
ingress cursor via `:stream-next`. If no work matches, the VM yields or parks
via `:park`.

### 4.2. Decide: Suspendable Model Inference
When the agent reaches an inference point, it emits a model invocation effect:
```clojure
[:ffi-call :op/llm-infer $r_dest $r_prompt]
```
The VM suspends execution by capturing a continuation snapshot and entering
`:status :parked`. When the model completes generation, the engine supplies the
response datom and executes `:resume`.

If an agent model is quota-limited (e.g. Codex quota exhausted until reset),
the continuation remains parked in `dao.space`. Other agents continue their
independent work without blocking or polling.

### 4.3. Act: Depositing Immutable Traces
The agent records its decision by appending datoms to its own single-writer
stream via `:stream-put`:
```clojure
[:stream-put $r_stream $r_transaction_datoms]
```
The agent never addresses a recipient directly. Deposited datoms become visible
to all observing peers through `dao.space.query`.

---

## 5. Model Context Protocol (MCP) Integration

MCP (Model Context Protocol) serves as the bidirectional adapter between
industry LLM runtimes and the `datom.world` substrate.

### 5.1. The Outward Gateway: `dao.agent.mcp.server`
`dao.agent.mcp.server` exposes a standard JSON-RPC 2.0 MCP server over stdio
or WebSocket (`dao.stream.ws`). The MCP server exposes bounded tools to LLMs:

```text
+---------------------+---------------------------------------------------+
| MCP Tool Name       | Description                                       |
+---------------------+---------------------------------------------------+
| dao_space_query     | Run declarative q/match query over dao.space      |
| dao_space_deposit   | Transact datoms into the agent's single-writer log|
| debruijn_eval       | Evaluate a de Bruijn image within Register VM     |
| debruijn_link_fetch | Fetch and verify a code image via B6 linker       |
| task_claim          | Atomically claim a work task via datom assert     |
+---------------------+---------------------------------------------------+
```

Naked host access tools (`bash`, `view_file`, `write_file`) are eliminated. The
agent interacts with the codebase entirely through datom projections and
content-addressed images.

### 5.2. The Inward Bridge: `dao.agent.mcp.client`
When the Register VM must invoke host-level capabilities (e.g. running a test
runner, compiling a native binary, or interacting with a Git repository), the VM
acts as an MCP client via `dao.agent.mcp.client`:
- The VM executes `:ffi-call :mcp/invoke $r_result $r_params`.
- The engine dispatches the call to an external host MCP server.
- The engine records the tool invocation, inputs, timestamp, and outputs as
  provenance metadata (`m` slot in canonical d5 datoms).

### 5.3. Crash-Resilient MCP Tool Execution
Standard MCP clients block a thread or event loop during tool calls. In this
architecture:
1. The tool invocation triggers a VM `:park` effect.
2. The VM continuation is stored in `dao.jing` as an immutable record.
3. The host executes the external tool asynchronously.
4. Upon tool completion, the host dispatches `:resume` with the result.
5. If the host process restarts mid-execution, the parked continuation is
   reloaded from `dao.jing` and resumed without loss of state.

---

## 6. Role Specialization of the Triad

The three primary coding agents operate with specialized roles over `dao.space`:

### 6.1. Claude (Opus / Sonnet): Compiler Engineer & Synthesizer
- **Role**: Code synthesis, algorithmic implementation, refactoring.
- **Workflow**:
  1. Matches tasks in `dao.space` marked `[:task/phase :implement]`.
  2. Generates Universal AST datoms.
  3. Lowers AST to de Bruijn Register images ($R$) using
     `yin.vm.debruijn-register-compile`.
  4. Publishes closed images to `dao.jing` and links them via B6.
  5. Deposits completion datoms updating `[:task/phase :review]`.

### 6.2. Codex (`gpt-6-astra`): Adversarial Reviewer & Invariant Prover
- **Role**: Invariant auditing, defect detection, formal contract verification.
- **Workflow**:
  1. Matches tasks in `dao.space` marked `[:task/phase :review]`.
  2. Fetches candidate images via `yin.vm.linker/fetch`.
  3. Executes verification passes inside the Register VM sandbox.
  4. Deposits review verdicts: `[:verdict/status :approved]` or
     `[:verdict/status :defect]` with qualified finding tuples.

### 6.3. AGY (Antigravity): Orchestrator & Convergence Governor
- **Role**: Liveness monitoring, worktree isolation, quota scheduling.
- **Workflow**:
  1. Observes `dao.space` transaction cadence.
  2. Detects agent stalling, rate-limit locks, or contradictory verdicts.
  3. Manages continuation timers (e.g. waking parked Codex tasks upon quota
     reset).
  4. Authorizes branch merges to canonical streams upon dual sign-off.

---

## 7. Stigmergic Task Schema in `dao.agent.schema`

The coordination board is formalized as a datom schema in `dao.agent.schema`:

```clojure
;; Task definition
[:task-101 :task/title "Implement Phase R3 Benchmark Harness"]
[:task-101 :task/type :compiler-engineering]
[:task-101 :task/status :ready]
[:task-101 :task/spec-address "jing:sha256:..."]

;; Claiming work
[:claim-201 :claim/task :task-101]
[:claim-201 :claim/agent :agent/claude]
[:claim-201 :claim/timestamp #inst "2026-09-24T03:30:00Z"]

;; Artifact deposition
[:art-301 :artifact/task :task-101]
[:art-301 :artifact/format :debruijn-register]
[:art-301 :artifact/image-r "0x784ec567..."]
[:art-301 :artifact/jing-key "jing:debruijn:r:..."]

;; Review verdict
[:rev-401 :review/artifact :art-301]
[:rev-401 :review/reviewer :agent/codex]
[:rev-401 :review/verdict :approved]
[:rev-401 :review/findings-count 0]
```

Every state change is an asserted datom. Conflicts (e.g. two agents claiming the
same task) are resolved via transactor linearization rules over stream offsets.

---

## 8. Phased Implementation Roadmap

### Phase 1: `dao.agent.mcp.server`
- Implement `src/cljc/dao/agent/mcp/server.cljc`: JSON-RPC 2.0 protocol handler
  and tool dispatch table exposing `dao_space_query`, `dao_space_deposit`,
  `debruijn_eval`, `debruijn_link_fetch`, and `task_claim`.
- Implement `src/clj/dao/agent/mcp/main.clj`: CLI entry point (`-main`) for
  stdio transport.
- Implement `test/dao/agent/mcp/server_test.cljc`: comprehensive test suite
  verifying JSON-RPC lifecycle, tool registration, parameter parsing, and tool
  invocations against mock streams and `DebruijnRegisterVM`.
- Validate tool calling with Claude Code and Codex CLI running against local
  `dao.space` and `dao.stream` instances.

### Phase 2: `dao.agent.schema` & `dao.agent.harness`
- Implement the formal task schema in `src/cljc/dao/agent/schema.cljc`.
- Create the multi-agent transactor harness in
  `src/cljc/dao/agent/harness.cljc`.
- Verify a two-agent stigmergic loop (Claude synthesizes code image -> deposits
  in `dao.space` -> Codex queries image -> verifies in Register VM -> deposits
  verdict).

### Phase 3: Continuation Parking & `dao.agent.mcp.client`
- Implement `:ffi-call :mcp/invoke` in `yin.vm.debruijn.register`.
- Add automatic continuation parking (`:park`) during long-running tool calls
  and rate-limit delays.
- Verify crash resilience: killing the host process during an active tool call
  and resuming from `dao.jing` without state loss.

### Phase 4: Full Pure-Yin Agent Port (`dao.agent.core`)
- Port the inner prompt-assembly and decision loop into Universal AST datoms
  executed directly by `DebruijnRegisterVM`, achieving host-independent,
  tri-host (JVM/CLJS/CLJD) portable autonomous agents.
