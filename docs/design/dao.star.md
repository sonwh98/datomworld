# dao.star: Hypermedia Web Applications in datom.world

## Core Philosophy

`dao.star` is not a library or an adapter; it is the architectural pattern for building web applications natively in `datom.world`. It emerges from the intersection of two aligned philosophies:
1. **datom.world (Backend)**: Systems emerge from interpreters of pure data flowing over explicit, append-only streams (Axioms 1 and 2). It avoids compiler magic, instead exposing the network explicitly via data.
2. **Datastar (Frontend)**: The browser should be a dumb, stateless interpreter of hypermedia (HTML) delivered over Server-Sent Events (SSE).

By treating Datastar as the browser's native interpreter for `dao.stream` over HTTP, we eliminate the need for specialized frontend state management, reactive DAG compilers (like Electric Clojure), or proprietary RPC adapters. The browser speaks the universal language of streams. 

Crucially, `dao.star` provides two distinct operational modes that can be seamlessly mixed without changing the programming model: **The Thin Client** (Arc-style sequential UI) and **The Fat Client** (Mobile continuations).

---

## Mode 1: The Thin Client (Arc-Style Sequential UI)

In the thin client model, the browser holds zero business logic and zero shared mutable state. Datastar acts as a dumb terminal rendering HTML patches, while `yin.vm` executes linear, sequential code on the server.

### Implementation Flow:
1. **Events In**: A user clicks a button (`data-on-click="@get('/action')"`). Datastar fires a standard HTTP request. The generic `datom.world` HTTP boundary receives it, assigns a correlation ID (`req-123`), and deposits it as an unjudged tuple into an ingress `dao.stream`. The boundary holds the HTTP socket open.
2. **Resuming the Continuation**: A suspended `yin.vm` agent on the server observes the ingress `dao.stream`. The incoming HTTP tuple wakes the continuation to pick up exactly where it left off, just like an Arc continuation.
3. **Semantics and State**: The `yin.vm` agent evaluates business logic linearly. Because we "Derive, don't persist," it projects the new UI state directly into Datastar's SSE wire format (e.g., `event: datastar-patch-elements\ndata: <div>...</div>`).
4. **Effects Out**: The agent appends this fully constructed SSE string to an egress `dao.stream` as an effect, tagged with `req-123`.
5. **Dumb Socket Routing**: The generic HTTP boundary observes the egress stream, matches `req-123` to the open socket, and flushes the unmodified string payload over the wire. Datastar morphs the DOM. The agent suspends again, waiting for the next input.

---

## Mode 2: The Fat Client (Mobile Continuations via UCF)

While `dao.star` champions the dumb terminal, it is not locked into it. Because `yin.vm` continuations are pure data (Universal Continuation Format), they can cross the network boundary to act as SPA-style fat clients for low-latency local interaction.

### Implementation Flow:
1. **Suspension & Serialization**: A `yin.vm` continuation running on the server yields and physically suspends its call stack. The runtime lifts it onto the wire by serializing the execution state into a canonical UCF datom.
2. **Handoff via SSE**: The server appends this UCF datom to the egress stream, enveloped in a Datastar patch. The patch instructs the browser to hand the UCF payload to a local `yin.vm` interpreter running alongside Datastar.
3. **Local Resumption**: The browser-side `yin.vm` instance unpacks the UCF datom and resumes the continuation natively in the client. Custody is exclusive: the execution state moves to the browser rather than being shared with it.
4. **Transparent Remote Access**: If the browser continuation needs to read a server-hosted database, it uses the portable descriptors it carried with it. The local runtime calls `attach!` to create a remote stream reflection over WebSockets. If the network drops, the instruction simply yields and parks until the connection returns.
5. **Migration Back**: The continuation can subsequently re-serialize itself and send itself back over the wire to resume on the server.

---

## Design Invariants Maintained

- **No Shared Mutable State**: The UI is a pure projection (Mode 1), or custody of the continuation is fully transferred to the client (Mode 2). State is never shared across the boundary; it moves.
- **Multiplexed Correlation**: We do not map connections 1:1 to streams. The generic HTTP boundary observes a shared egress effect stream and uses envelope correlation IDs to route payloads to sockets.
- **Host Isolation**: The HTTP boundary is a dumb pipe that knows nothing of "Datastar" or "yin.vm" syntax. The browser is a dumb renderer that knows nothing of backend state.
- **Data > Macros**: We reject compile-time slicing (e.g., Electric Clojure). Network boundaries are crossed dynamically at runtime via reified data on explicit streams.

## Completion Criteria
A `dao.star` implementation is considered complete when:
1. **Thin Client**: A `yin.vm` agent on the JVM successfully receives a Datastar HTTP request via `dao.stream`, suspends, and responds with an SSE-formatted raw string that the HTTP boundary flushes to morph the DOM.
2. **Fat Client Handoff**: A `yin.vm` agent can serialize its UCF state into a Datastar SSE patch, which is then successfully resumed by a CLJS `yin.vm` instance running in the browser, maintaining transparent remote access to its server-held streams via `attach!`.
