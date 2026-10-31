Created-GMT: 2026-10-06 16:14:21 GMT
Created-Local: 2026-10-06 23:14:21 +07
Coding-Agent: claude
Session-ID: 642b5698-f626-4028-9068-0704d093d269

# Task: Architect evaluation of published head trace cross-machine step off loopback (Design Section 8.3)

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-06 23:14:21 +07 | Status: active | Rationale: Architecture evaluation of the 5 unverified items in Section 8.3 before engineering slicing for cross-machine WebSocket transport off loopback

Perform a read-only architecture review and verification of the 5 unverified items for stepping off loopback in `docs/design/yin.vm.linker.dht.head.md` Section 8.3:

Read first:
- `docs/design/datom.world.md`
- `docs/design/yin.vm.linker.dht.head.md` (Sections 5.1, 8, 8.3, 8.4, 10, 13)
- `src/cljc/dao/stream/remote.cljc`
- `src/cljc/dao/stream/ws_project.cljc`
- `src/cljc/yin/repl/serve.cljc`
- `src/cljc/yin/vm/linker/head/ws.cljc`
- `src/cljc/yin/repl/host.cljc` and its host implementations (`src/clj/yin/repl/host/jvm.clj`, `src/cljs/yin/repl/host.cljs`, `src/cljd/yin/repl/host.cljd`)
- `src/cljc/dao/space/dht.cljc`

Context:
The owner has approved Path 1 (the step off loopback over WebSocket per Section 8.3, accepting Questions 4 & 5: WebSocket as initial cross-machine transport, and safety without a progress guarantee).
Before cutting engineering slices, the 5 unverified items named in Section 8.3 must be rigorously evaluated across JVM, Node, and ClojureDart:

1. **Acceptor session bounding**: Does `ws-project/make-acceptor` or `remote/acceptor` bound concurrent sessions under many held/abusive connections? (Note: handoff has 8 slots; verify whether `:sessions` is bounded or whether unbounded map growth occurs).
2. **Step duration & loop bounding**: Can a single connection make a step unacceptably long? (`mirror-step` loops to `:blocked`; does a composition budget or max iterations exist, or could a slow/flooding peer stall the host thread?).
3. **Listener seams outside `yin.repl.serve`**: Do the listener seams in `yin.repl.host` compose cleanly outside `yin.repl.serve` on all three hosts (JVM, Node, Dart) without code movement, or is restructuring needed?
4. **TCP/UDP port sharing across hosts**: Can a TCP listener bind at the UDP socket's port number on each host (JVM, Node, Dart), including after an ephemeral UDP bind? What are the platform constraints (SO_REUSEADDR, OS-level port space separation between TCP and UDP, IPv4 vs IPv6 dual-stack traps)?
5. **Lost-request detection on attached reflections**: How are lost requests and half-open TCP connections detected on an attached reflection off loopback? Contrast loopback socket drop vs remote half-open stall. What timeout or ping/heartbeat mechanisms exist or are needed?

Evaluate foundational invariants, ownership boundaries, explicit state and control flow, concurrency and linearization, host isolation, CLJ/CLJS/CLJD portability, migration risk, and completion criteria. Distinguish architectural defects from implementation gaps or intentionally deferred work. Do not edit repo files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report:
1. Executive Summary & Verdict (Ready for Slicing vs Blockers).
2. Detailed architectural analysis for each of the 5 items (severity | file:line | invariant/evidence | recommended contract/fix).
3. Recommended Slicing & Engineering Boundary for the implementation.
