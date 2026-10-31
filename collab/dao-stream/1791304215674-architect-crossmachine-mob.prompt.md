Created-GMT: 2026-10-06 16:30:15 GMT
Created-Local: 2026-10-06 23:30:15 +07
Coding-Agent: codex (gpt-6-astra) / deepseek (deepseek-v4-pro)
Session-ID: codex 59f057b9-5337-48da-ae6e-5fa9462d466b | deepseek e05ef941-b61d-448e-b8b0-f5f0a61fd7e7

# Task: Architect Mob — Cross-machine transport, dao.stream abstraction boundary, and resource bounds

Role: Lead System Architect (Team Mob: Claude Fable, Codex Astra, DeepSeek)

OWNER DIRECTIVE (verbatim, binding):
"there's too much details that i don't care to deal with. My abstraction boundary is the dao.stream. Whether TCP or UDP is used can b swapped out without the REPL and the linker caring. These are the invariants that are important to me. For details, mob with the @docs/agents/team.md to reach a concensus but show how the concensus was reached. You can add deepseek to the team too. if UDP transport is too difficult to do now, just do TCP"

Read first:
- `docs/design/datom.world.md` (the foundational axioms & 6 invariants)
- `docs/design/yin.vm.linker.dht.head.md` (Sections 5.1, 8, 8.3, 8.4, 10, 13)
- `collab/1791303261488-architect-head-crossmachine-sec83.claude-fable-5-1.findings.md` (Fable's review of the 5 Section 8.3 items)
- `src/cljc/dao/stream/remote.cljc`
- `src/cljc/dao/stream/ws_project.cljc`
- `src/cljc/yin/repl/serve.cljc`
- `src/cljc/yin/vm/linker/head/ws.cljc`
- `src/cljc/yin/vm/linker/head.cljc`
- `src/cljc/yin/repl/dht.cljc`

Context:
Claude Fable reviewed the 5 unverified items from Section 8.3 and found:
1. Session bounding does not hold: `adopt!` adds to `:sessions` without limit; half-open clients leak.
2. Step loop bounding holds only accidentally via 64-entry ring buffers; no composition budget.
3. Listener seams compose cleanly across JVM/Node/Dart, but `head.ws` lacks explicit session shutdown.
4. TCP and UDP share port spaces independently, but IPv6 URLs lack bracket formatting.
5. Half-open connection drops are undetectable because readers see `:blocked` indefinitely.

Mob Tasks & Decisions:
Evaluate each decision against the Owner's non-negotiable invariant:
**"My abstraction boundary is the dao.stream. Whether TCP or UDP is used can be swapped out without the REPL and the linker caring."**

D1. **Abstraction Boundary & Transport Independence**:
    How must `dao.stream` encapsulate transport differences (TCP WebSocket vs future UDP datagram) so that neither `yin.repl` nor `yin.vm.linker.head` has any transport-specific code or leaks?
    Should `yin.vm.linker.head.ws` be refactored or generalized into a transport-agnostic board attacher/acceptor that delegates to `dao.stream`?

D2. **Transport Selection**:
    Per owner mandate ("if UDP transport is too difficult to do now, just do TCP"), confirm TCP (WebSocket) as the transport for this milestone, and verify what contract guarantees ensure zero breakage when UDP is added later.

D3. **Resource & Liveness Bounds Location**:
    Confirm where Fable's 5 fixes belong:
    - Should `:max-sessions`, idle eviction, and step budgets live entirely inside `dao.stream.ws-project` and `dao.stream.remote`?
    - How should stream liveness (half-open detection) be abstracted so the reader checks `dao.stream` cursor movement rather than TCP-level primitives?

D4. **Advertised Host / Wildcard Bind**:
    Off loopback, when binding `0.0.0.0` or `::`, how should the token host be resolved without burdening the operator?

D5. **Implementation Slicing**:
    Confirm or refine Fable's proposed 4-stage slicing (S1: ws-project bounds -> S2: remote loop budgets -> S3: head/stream liveness & cleanup -> S4: loopback gate lift).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then provide your positions, rationale, risks, and concrete recommendations for D1–D5.
