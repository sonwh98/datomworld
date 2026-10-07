Created-GMT: 2026-09-24 06:13:11 GMT
Created-Local: 2026-09-24 13:13:11 +0700
Coding-Agent: claude
Session-ID: 39c82503-e332-4931-a960-19916506d8cf

# Task: Author 4 Dedicated dao.agent Subsystem Design Documents

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-24 13:13:11 +0700 | Status: active | Rationale: Decompose master dao.agent blueprint into four granular subsystem design specifications

## Objective

Author four comprehensive, standalone architectural design specifications
under `docs/design/` detailing the subsystems in `docs/design/dao.agent.md`:

1. `docs/design/dao.agent.mcp.server.md`
2. `docs/design/dao.agent.schema.md`
3. `docs/design/dao.agent.harness.md`
4. `docs/design/dao.agent.mcp.client.md`

## Foundational Reading & Context

Before drafting, study these foundational documents:
1. `docs/design/datom.world.md`: Core axioms ("computation moves, data stays",
   the 6 non-negotiable invariants, layer boundaries).
2. `docs/design/dao.agent.md`: The master stigmergy blueprint and namespace
   organization.
3. `src/cljc/yin/vm/docs/co-routines.md`: Continuation mechanics in Yin VM:
   - Model 1: Stream-based coroutines (in-VM backpressure via put/next).
   - Model 2: Park/resume, host-driven (VM halts with parked descriptor, host
     does async work, resumes VM with value).
   - Model 3: First-class continuations (reified values stored in dao.jing).
4. `docs/design/yin.vm.debruijn.register.md`: Register VM execution kernel,
   opcodes (`:park`, `:resume`, `:stream-put`, `:stream-next`, `:ffi-call`),
   sparse continuation capture, live slot metadata.
5. `docs/design/dao.space.schema.md`: Datom space publication and attribute
   contracts.
6. `docs/design/dao.space.transactor.md`: Transactor linearization over
   single-writer streams.
7. `docs/design/yin.vm.debruijn.linker.md`: Format-neutral image linking and
   verification (Phase B6).
8. `docs/ideas/agent-smith.md`: Containment through structure, provenance in the
   `m` slot of datoms.

## Subsystem Design Requirements

### 1. `docs/design/dao.agent.mcp.server.md`
- **Scope**: Outward-facing Model Context Protocol (MCP) server adapter.
- **Wire Protocol & Framing**: JSON-RPC 2.0 framing over `stdio` and WebSocket
  (`dao.stream.ws`).
- **Tool Catalog Specification**:
  - `dao_space_query`: Parameter schemas, read-set limits, timeout budgets.
  - `dao_space_deposit`: Single-writer provenance validation, atomic
    `[e a v t m]` schema checking.
  - `debruijn_eval`: Step budget clamping (`:steps-remaining`), isolated
    register execution frame.
  - `debruijn_link_fetch`: B6 format-neutral Jing resolution and verification.
  - `task_claim`: Atomic CAS / stream offset lease acquisition.
- **Diagnostics & Error Mapping**: Standard JSON-RPC error codes (-32700 to
  -32603) mapped to qualified `datom.world` defect maps.
- **Host Isolation**: Zero unmediated filesystem or shell access.
- **Portability**: CLI `-main` in `src/clj/dao/agent/mcp/main.clj`, core logic
  in `.cljc` portable across JVM/CLJS.

### 2. `docs/design/dao.agent.schema.md`
- **Scope**: Datom coordination schema in `dao.space`.
- **Attribute Dictionary**: Full schema for:
  - `:task/*` (`:task/id`, `:task/title`, `:task/type`, `:task/status`,
    `:task/spec-address`, `:task/phase`, `:task/created-at`).
  - `:claim/*` (`:claim/task`, `:claim/agent`, `:claim/lease-expiry`,
    `:claim/epoch`, `:claim/attempt`).
  - `:artifact/*` (`:artifact/task`, `:artifact/format`, `:artifact/image-r`,
    `:artifact/jing-key`, `:artifact/author`).
  - `:review/*` & `:verdict/*` (`:review/artifact`, `:review/reviewer`,
    `:review/verdict`, `:review/findings-count`, `:review/findings-key`).
- **State Machine Invariants**: Directed acyclic lifecycle transitions:
  `:ready` -> `:claimed` -> `:implemented` -> `:review` -> `:approved` /
  `:defect`.
- **Linearization & Conflict Rules**: Transactor resolution when concurrent
  claims target the same task offset.

### 3. `docs/design/dao.agent.harness.md`
- **Scope**: Multi-agent stigmergy loop and coordination governor.
- **Agent Reasoning Cycle**: The 3 beats (Perceive -> Decide -> Act) anchored in
  `co-routines.md` Model 1 (stream backpressure) and Model 2 (park/resume).
- **Stream Cursor Management**: Ingress reading over agent append-only logs
  without polling or locks.
- **Triad Consensus Policy**: Claude (Synthesizer), Codex (Adversarial
  Reviewer), AGY (Orchestrator/Governor). Dual sign-off requirement before
  merge authorization.
- **Liveness & Deadlock Detection**: Stalled claims, expired leases, and quota
  exhaustion monitoring.

### 4. `docs/design/dao.agent.mcp.client.md`
- **Scope**: Inward-facing FFI bridge for Register VM to invoke external host
  capabilities.
- **FFI Boundary**: Register VM opcode lowering for `[:ffi-call :mcp/invoke
  $r_dest $r_params]`.
- **Continuation Parking & Resumption**: Anchored in `co-routines.md` Model 2:
  VM emits `:park`, captures sparse continuation snapshot in `dao.jing`, host
  executes tool asynchronously, dispatches `:resume` with result.
- **Crash Resilience**: Rehydrating parked agent continuations from `dao.jing`
  after abrupt process termination.
- **Provenance Flight Recorder**: Every tool invocation inputs, outputs, and
  timestamps recorded in the `m` slot of datoms.

## Document Invariants (Strict)

- Pure Markdown (`.md`).
- Line length strictly <= 80 columns.
- 100% pure ASCII only (no Unicode quotes, em-dashes, or symbols).
- Section headers use `##` and `###`.
- Code blocks use triple-backtick fences with a language tag.
- Tables formatted with ASCII box-drawing (spaces/hyphens/pipes).
- Explicit state and control flow (no hidden global state or singletons).

Begin your final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
