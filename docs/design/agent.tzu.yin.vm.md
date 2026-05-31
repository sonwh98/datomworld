# Plan: Porting Agent Tzu to Yin.VM

## Objective
Transition `agent.tzu` from running as a host-level Clojure process to running entirely inside the `Yin.VM` execution substrate as a first-class continuation.

This is a **Pure Yin** port. The agent's logic—including JSON parsing, tool routing, and the recursive LLM loop—will be authored in a high-level language (Clojure), compiled via **Yang** to **Universal AST datoms**, and executed by the **Yin.VM**.

## Background
Currently, `agent.tzu` manages its conversation loop using host-level Clojure threads, blocking I/O (`ds/take!!`), and native JSON libraries. To align with the `datom.world` core philosophy, the agent must interact with the world solely through **data effects** (stigmergy). 

By moving the agent into the VM, its network requests, file access, and internal reasoning state become asynchronous, suspendable, and content-addressable datom streams.

## Phased Implementation Plan

### Phase 1: Yin.VM Capabilities (Foundation)
The VM requires specific primitive effects to support the agent's needs:
1.  **`yin.io.http` Effect Handler**: 
    - Implement a host-side handler that maps Yin effect maps to `dao.stream.http`.
    - This allows the VM to suspend execution when an HTTP request is made and resume once the response is received.
2.  **JSON Primitives**:
    - Implement `json/encode` and `json/decode` as host-backed VM effects. 
    - While a pure Yang JSON parser is possible, leveraging host-native speed for serialization is preferred for the initial port.

### Phase 2: Agent Tools (AST Port)
- Port the tool routing logic from `agent.tools/execute-tool-call` to a format compatible with Yang compilation.
- The logic will pattern-match on tool names (`stream_read`, `file_write`, etc.) and emit the corresponding Yin effects.
- Direct host-calls in the current tools (like `clojure.java.io`) will be replaced with Yin-native stream primitives.

### Phase 3: The Autonomous Loop (AST Port)
- Port `run-agent` and `chat-completion` logic to Yang.
- **Async Transformation**: Replace the blocking `ds/take!!` pattern with a Yin continuation yield. The agent will automatically "park" its state in `dao.stream` while waiting for the LLM API response.
- The loop will maintain the `messages` history vector as a value within the VM's environment.

### Phase 4: Host Harness & Substrate
- Create a launcher that:
    1.  Compiles the agent logic into AST datoms using Yang.
    2.  Sets up the `dao.stream` execution substrate (the "execution stream").
    3.  Enqueues the initial agent continuation.
    4.  Runs the VM loop to process the agent's reasoning steps.

## Architectural Benefits
- **Portability**: The agent logic becomes platform-agnostic. The same AST datoms can run on JVM, JS, or Dart hosts.
- **Observability**: Every step of the agent's reasoning and every external interaction is recorded as a discrete event in a datom stream.
- **Resilience**: Agent continuations can be serialized and "parked" in a database. If the host process restarts, the agent can resume exactly where it left off, even mid-HTTP request.

## Verification
- **Unit Tests**: Verify the new HTTP and JSON effect handlers in the VM.
- **Integration Test**: Run a compiled Agent Tzu AST through a complete "Read File -> Prompt LLM -> Write Stream" cycle entirely within the `Yin.VM` substrate.
