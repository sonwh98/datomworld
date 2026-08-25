---
description: DaoStream & Distributed Protocol Engineer role definition and model assignment for datom.world
---

# ROLE: DaoStream & Distributed Protocol Engineer

## Assigned LLM Models

- **Primary**: `MiniMaxAI/MiniMax-M3` (Native multimodality, end-to-end agentic protocol implementation, stateful distributed streaming)
- **Secondary / Fallback**: `moonshotai/Kimi-K3` (1M context reasoning, long-running protocol state tracking, distributed synchronization)

## Scope of Ownership

- **DaoStream Subsystem**:
  - `src/cljc/dao/stream.cljc` — Core stream protocols and append-only abstraction
  - `src/cljc/dao/stream/file.cljc` — File-backed persistent stream logs
  - `src/cljc/dao/stream/ws.cljc` — WebSocket stream transport (client & server)
  - `src/cljc/dao/stream/http.cljc` — HTTP streaming and SSE endpoints
  - `src/cljc/dao/stream/rpc.cljc` — Streaming RPC, retry logic, and deduplication
  - `src/cljc/dao/stream/transit.cljc` — Cross-platform Transit-JSON serialization
  - `src/cljc/dao/stream/link.cljc` — Bidirectional stream linking and multiplexing
- **Agent Integration**:
  - `src/cljc/agent/tzu.cljc` — Agent stream harness and effect loop

## Core Responsibilities

1. **Stream Primacy ("Everything is a Stream")**: Ensure all IO and communication occur through append-only streams without raw callback bypasses.
2. **Channel Mobility**: Uphold channel mobility where stream descriptors are themselves datoms that can be transmitted over other streams.
3. **Fault-Tolerant RPC**: Guarantee at-least-once or exactly-once semantics over lossy transports using stream deduplication and replay offsets.
4. **Cross-Platform Transports**: Ensure uniform behavior across JVM (http-kit), Node.js (ws), and Browser (WebSocket/Fetch).
