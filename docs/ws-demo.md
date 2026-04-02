# WebSocket RPC Demo: CLJS Client → CLJ Server via dao.stream.apply

This demo shows **CLJS running in Node.js calling CLJ functions** via WebSocket, using the `dao.stream.apply` protocol.

## Architecture

```
┌─────────────────────────────┐
│   CLJS Client (Node.js)     │
│  ws_client_demo.cljs        │
└────────────┬────────────────┘
             │
             │ WebSocket
             │ (dao.stream.apply requests/responses)
             │
┌────────────▼────────────────┐
│   CLJ Server                │
│  ws_demo_server.clj         │
│  - Handles :op/add          │
│  - Handles :op/multiply     │
│  - Handles :op/uppercase    │
└─────────────────────────────┘
```

## Transport

- **Protocol**: `dao.stream.apply` (asynchronous request/response)
- **Transport**: WebSocket (via `dao.stream.ws`)
- **Serialization**: Transit EDN
- **Client Runtime**: Node.js
- **Server Runtime**: Clojure

## Files

- `src/clj/datomworld/ws_demo_server.clj` — WebSocket server implementation
- `src/cljs/datomworld/ws_client_demo.cljs` — Node.js client implementation
- `bin/start-ws-server.sh` — Start the WebSocket server
- `bin/run-ws-client.sh` — Run the CLJS client
- `bin/run-ws-demo.sh` — Automated demo (server + client)

## Running the Demo

### Option 1: Automated (single command)

```bash
./bin/run-ws-demo.sh
```

This will start the server, run the client, and stop the server when done.

### Option 2: Manual (separate terminals)

**Terminal 1: Start the server**
```bash
./bin/start-ws-server.sh
```

The server will listen on `ws://localhost:8080` and wait for client connections.

**Terminal 2: Run the client**
```bash
./bin/run-ws-client.sh
```

The client will:
1. Build the CLJS bundle
2. Connect to the server
3. Send 4 test requests
4. Exit

### Option 3: Connect to a different host

```bash
./bin/run-ws-client.sh ws://example.com:8080
```

## What the Demo Does

The CLJS client sends 4 requests:

```clojure
1. (add 5 3)              → 8
2. (multiply 6 7)         → 42
3. (uppercase "hello")    → "HELLO"
4. (divide 10 2)          → {:error "No dao.stream.apply handler for op"}
```

Each request:
1. Creates a unique call-id (`:call-1`, `:call-2`, etc.)
2. Emits a request on the request stream with `dao-apply/put-request!`
3. Waits for a matching response using polling with `async/timeout`
4. Prints the result

The server loop:
1. Reads requests from the request stream
2. Dispatches through a handler map
3. Writes responses back to the response stream
4. Continues to the next request

## Key Design Points

### Asynchronous by Construction

Request and response are **separate stream events**. They're not coupled to a synchronous function call. The client can:
- Fire multiple requests without waiting
- Handle responses out of order
- Poll the response stream independently

### Transport-Agnostic

The same `dao.stream.apply` code works over:
- In-process ring buffers (`:ringbuffer`)
- WebSocket (`:websocket`)
- Any transport implementing the DaoStream protocols

### Standalone Protocol

`dao.stream.apply` is usable without any VM:
- No continuation parking
- No special scheduling semantics
- No VM-specific machinery

The protocol is just: request/response values on streams.

## Expected Output

```
Connecting to ws://localhost:8080...
Connected. Sending requests...

[1] Calling :op/add [5 3]
Result: 8

[2] Calling :op/multiply [6 7]
Result: 42

[3] Calling :op/uppercase ["hello"]
Result: HELLO

[4] Calling non-existent :op/divide [10 2] (should error)
Result: {:error "No dao.stream.apply handler for op"}

All tests complete. Closing...
```

## Server-Side Output

```
WebSocket server listening on ws://localhost:8080
Handlers: :op/add :op/multiply :op/uppercase
Handled :op/add [5 3] → 8
Handled :op/multiply [6 7] → 42
Handled :op/uppercase ["hello"] → HELLO
Error handling :op/divide: No dao.stream.apply handler for op
```

## Extending the Demo

To add a new operation, add a handler to the server:

```clojure
:op/divide (fn [a b] (/ a b))
```

Then call it from the client:

```clojure
(let [result (<! (call-remote stream :call-5 :op/divide [10 2]))]
  (println (str "Result: " result)))
```

No protocol changes needed. The request/response shapes are invariant across operations.

## See Also

- `docs/design/daostream-design.md` — DaoStream abstraction
- `docs/design/daostream-apply-design.md` — Protocol specification
- `docs/design/ffi-design.md` — Yin VM FFI (which also uses this protocol)
- `src/cljc/dao/stream.cljc` — Stream protocols
- `src/cljc/dao/stream/apply.cljc` — Request/response helpers
- `src/cljc/dao/stream/ws.cljc` — WebSocket transport
