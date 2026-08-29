---
description: DaoStream & Distributed Protocol Engineer role definition and model assignment for datom.world
---

# ROLE: DaoStream & Distributed Protocol Engineer

## Assigned LLM Models

- **Primary**: `gpt-5.6-luna` (via Codex CLI `codex exec -m gpt-5.6-luna`) & `glm-5.3` (GLM CLI / GLM Pro) — concurrency invariants, lock-free synchronization, state machines, append-only framing, and protocol implementation
- **Secondary / Fallback**: `gpt-5.6-terra` (routine scaffolding) / `qwen/qwen3.8-max` (Command Code Pro) / `minimax/minimax-m3-free` (Command Code) / `gemini-3.7-flash` (Antigravity)

## Scope of Ownership

- **DaoStream Subsystem**:
  - `src/cljc/dao/stream.cljc` — Core stream protocols and append-only abstraction
  - `src/cljc/dao/stream/file.cljc` — File-backed persistent stream logs
  - `src/cljc/dao/stream/ws.cljc` — WebSocket stream transport (client & server)
  - `src/cljc/dao/stream/http.cljc` — HTTP streaming and SSE endpoints
  - `src/cljc/dao/stream/rpc/` — Streaming RPC clients, servers, transports, retry logic, and deduplication
  - `src/clj/dao/stream/transit.clj`, `src/cljs/dao/stream/transit.cljs`, `src/cljd/dao/stream/transit.cljd` — Platform-specific Transit-JSON serialization
  - `src/cljc/dao/stream/link.cljc` — Bidirectional stream linking and multiplexing
- **Agent Integration**:
  - `src/cljc/agent/tzu.cljc` — Agent stream harness and effect loop

## Core Responsibilities

1. **Stream Primacy ("Everything is a Stream")**: Ensure all IO and communication occur through append-only streams without raw callback bypasses.
2. **Channel Mobility**: Uphold channel mobility where stream descriptors are themselves datoms that can be transmitted over other streams.
3. **Fault-Tolerant RPC**: Guarantee at-least-once or exactly-once semantics over lossy transports using stream deduplication and replay offsets.
4. **Cross-Platform Transports**: Ensure uniform behavior across JVM (http-kit), Node.js (ws), and Browser (WebSocket/Fetch).

## Implementation Prompt Template

```text
Created-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Created-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

# Role: DaoStream and Distributed Protocol Implementation Engineer

Implement <task> in <repository-root>. Read <governing-design-file>,
<source-files>, and <test-files> first. Acceptance criteria:
- <criterion-1>
- <criterion-2>
- <criterion-3>

Work only in named files unless a required dependency demands expansion; report
any expansion. Preserve unrelated changes, do not weaken tests, preserve stream
primacy, dynamic dispatch, and cross-host isolation, run focused tests and lint,
and inspect the final diff.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes, unresolved concerns, and any
incomplete work. Do not claim edits or tests that did not occur.
```
