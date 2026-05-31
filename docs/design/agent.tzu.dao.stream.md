# Design: Agent Tzu Tool Usage & dao.stream Integration

## Context
Agent Tzu (`agent.tzu`) initially operated as a text-to-datom extraction engine. While it could generate complex knowledge graphs, it lacked the ability to interact with the environment or external state during its reasoning process.

This design extends Agent Tzu with **autonomous tool usage**, enabling it to read from and write to `dao.stream` instances and the web via OpenAI-compatible function calling.

## Core Capabilities
Agent Tzu is provided with a suite of tools to interact with its environment:
1.  **`stream_list`**: Discover available streams in the registry.
2.  **`stream_read`**: Inspect the contents of a named stream at a specific cursor position.
3.  **`stream_write`**: Append new EDN values to a named stream.
4.  **`http_fetch`**: Retrieve external web resources (HTML, JSON, etc.) via the system's HTTP stream implementation.

## Architecture

### 1. Multi-LLM Support (OpenAI-Compatible)
The transport layer is generalized to support any OpenAI-compatible Chat Completions API. Configuration is handled via environment variables:
- `OPENAI_API_KEY`: Authentication.
- `OPENAI_BASE_URL`: The endpoint (defaults to OpenAI).
- `OPENAI_MODEL`: The model identifier (defaults to `gpt-4o`).

### 2. Completion Layer (`agent.tzu`)
Concerns are separated into three layers:
- `chat-completion`: Low-level function handling the raw HTTP request and JSON encoding/decoding.
- `prompt`: High-level wrapper for single-shot text interactions (maintains backward compatibility).
- `run-agent`: Autonomous execution loop.

### 3. Tool Execution Layer (`agent.tools`)
Tool definitions and routing logic are decoupled from the agent loop:
- `stream-tools`: JSON schemas for function calling.
- `execute-tool-call`: Interpreter that maps LLM data effects (JSON) to system effects (EDN/Streams).

### 4. Autonomous Execution Loop (`run-agent`)
The `run-agent` function manages conversation state and recursive tool execution:

```clojure
(defn run-agent
  "Execute an autonomous Agent Tzu loop with access to a registry of streams."
  [prompt-txt stream-registry & [api-key]]
  ;; ... recursive loop that handles finish_reason: tool_calls ...
  )
```

**The Loop Lifecycle:**
1.  **Request**: Send message history + tool definitions to the LLM.
2.  **Analysis**: Inspect `finish_reason`.
3.  **Execution**: If the LLM requests tool calls, resolve them against the `stream-registry` or the host system.
4.  **Feedback**: Append tool results to the history as `role: tool` messages.
5.  **Iteration**: Re-submit the expanded history to the LLM until it returns a final text response (`"finish_reason": "stop"`).

## Tool Definitions

### `stream_list`
- **Description**: List available streams. Use this first for discovery.
- **Output**: A sorted vector of stream IDs (deterministic).

### `stream_read`
- **Description**: Read next value from a stream at a given position.
- **Output**: `{:value any, :next-position integer}` or a status string like `(end of stream)`.

### `stream_write`
- **Description**: Append a value (as an EDN string) to a stream.
- **Robustness**: Explicitly handles malformed EDN and returns `(full: retry later)` for capacity-limited streams.

### `http_fetch`
- **Description**: Perform HTTP GET/POST/PUT/DELETE requests.
- **Output**: `{:status integer, :body string}` or error details.

## Interactive REPL
A command-line entry point is provided in `agent.tzu/-main` that initializes a default `io` stream and launches an unconditional `run-agent` loop for interactive debugging and use.

## Verification Plan

### Automated Tests
- **`test/agent/tzu_test.cljc`**: Exercises the agent loop, recursion, and backward-compatible prompt logic.
- **`test/agent/tools_test.cljc`**: Thoroughly tests each tool's routing, parameter parsing, and edge cases (gaps, full buffers, malformed EDN).

### Manual Smoke Test
1.  Source configuration: `source env.sh`.
2.  Launch REPL: `clj -M -m agent.tzu`.
3.  Prompt: `"List available streams, then write the number 42 to the 'io' stream."`
4.  Verify: Check that `(ds/take!! io-stream)` returns `42`.
