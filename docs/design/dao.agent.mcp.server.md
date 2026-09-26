# dao.agent.mcp.server: The Outward MCP Gateway

Status: design specification (2026-09-24); not implemented

Parent: `docs/design/dao.agent.md`, section 5.1. This document specifies
the outward-facing Model Context Protocol (MCP) server adapter. It is one
of four subsystem specifications under `dao.agent`; its siblings are
`dao.agent.schema.md`, `dao.agent.harness.md`, and `dao.agent.mcp.client.md`.

## 1. Objective and position

An LLM runtime (Claude Code, Codex CLI, or any MCP-capable client) has no
native way to read `dao.space` or run a de Bruijn image. The MCP server is
the adapter that gives it one, and only one: a bounded catalog of five
tools, each of which is a pure interpretation of a JSON-RPC request into
datoms, stream appends, or a Register VM run.

The server is a **transport plus an interpreter**, in the sense of
`datom.world.md`, Host Boundaries:

- The transport frames JSON-RPC 2.0 over `stdio` or WebSocket, decodes
  and validates it, and deposits one plain-data event per valid request
  onto an ingress stream. Malformed wire input is transport machinery and
  never becomes an event.
- The interpreter reads the ingress stream, runs the tool named by each
  event against explicit state, and appends one outcome per request onto
  an egress stream. The transport reads the egress stream and frames each
  outcome as a JSON-RPC response.

Neither side holds a reference to the other. Correlation is by the
JSON-RPC `id` carried in the data. This is the constructive replacement of
the callback that a conventional MCP server library would demand.

```text
  LLM client
     |  JSON-RPC 2.0 (stdio frames or WebSocket messages)
     v
  +----------------------------+
  | transport (decode/validate)|  -- deposit --> ingress stream
  +----------------------------+
                                                 |
                                                 v
  +----------------------------+        +------------------+
  | interpreter (tool dispatch)| <----- | ingress cursor   |
  |  explicit state map        | -----> | egress stream    |
  +----------------------------+        +------------------+
                                                 |
  +----------------------------+                 v
  | transport (frame/encode)   | <-- read ---- egress cursor
  +----------------------------+
     |
     v
  LLM client
```

### 1.1 What the server is not

- It is not an agent. It has no prompt, role, or reasoning loop. Those
  live in `dao.agent.harness`.
- It is not a shell. There is no tool that reads a file, runs a process,
  or opens a socket on the client's behalf (section 7).
- It is not a transactor of its own. It owns exactly one
  `dao.space.transactor` value per session, and that value is the only
  writer to the session's stream (section 4.2).

## 2. Namespace and file box

```text
New: src/cljc/dao/agent/mcp/server.cljc     protocol + tool dispatch
New: src/cljc/dao/agent/mcp/tools.cljc      tool catalog, param schemas
New: src/cljc/dao/agent/mcp/error.cljc      defect <-> JSON-RPC mapping
New: src/clj/dao/agent/mcp/main.clj         CLI -main, stdio transport
New: test/dao/agent/mcp/server_test.cljc
Existing edits: none
Depends on: dao.space.query, dao.space.transactor, dao.stream,
            dao.stream.ws, dao.jing, yin.vm.linker,
            yin.vm.debruijn.register (R4 kernel), dao.agent.schema
Must not change: yin.vm.*, dao.space.*, dao.stream.*, dao.jing.*
```

The `.cljc` files carry every rule in this document and run on JVM and
CLJS. The `.clj` file carries only the process entry point: argument
parsing, opening `stdin`/`stdout`, constructing the session state, and
running the loop. ClojureDart support is a later port of `main` alone; the
`.cljc` core must not acquire host conditionals to accommodate it.

Layer rule, restated from `dao.agent.md`: `yin.vm` never requires
`dao.agent.mcp.*`. The server requires the VM; the VM does not know the
server exists.

## 3. Wire protocol and framing

### 3.1 JSON-RPC 2.0

The server speaks JSON-RPC 2.0 as MCP requires. It accepts requests,
notifications, and batches; it emits responses and, for the one
server-initiated case below, notifications.

Supported MCP methods:

```text
+---------------------------+-----------------------------------------+
| Method                    | Behavior                                |
+---------------------------+-----------------------------------------+
| initialize                | Returns server info, protocol version,  |
|                           | and capabilities {:tools {}}            |
| notifications/initialized | Marks the session :ready                |
| tools/list                | Returns the five tool descriptors       |
| tools/call                | Dispatches to the tool catalog (sec. 4) |
| ping                      | Returns {}                              |
| shutdown                  | Marks the session :closing; drains      |
+---------------------------+-----------------------------------------+
```

Any other method answers `-32601` (method not found). Resources, prompts,
sampling, and roots capabilities are not advertised and are not
implemented.

### 3.2 stdio transport

Framing is newline-delimited JSON, one message per line, UTF-8, as the
MCP stdio transport specifies. The transport reads `stdin` line by line,
parses each line, and deposits an event. `stdout` carries only JSON-RPC
messages; every diagnostic goes to `stderr` or to the session's own
stream as a datom, never to `stdout`.

### 3.3 WebSocket transport (`dao.stream.ws`)

The WebSocket transport reuses `dao.stream.ws` unchanged. A server-hosted
stream is created per accepted connection (`dao.stream.ws.md`, "A server
that wants per-conversation state ... creates a fresh stream"). Each
inbound text message is one JSON-RPC message; each outbound message is
one framed response. The `ws` handle is writer-only, so the ingress and
egress streams of section 3.4 are `memory-log` streams the composition
creates; the socket adapter deposits into ingress and a reader over
egress writes to the socket.

### 3.4 Ingress and egress event shapes

The transport deposits exactly one of these per valid inbound message:

```clojure
{:dao.agent.mcp/event   :request
 :dao.agent.mcp/session session-id
 :dao.agent.mcp/id      rpc-id          ; nil for a notification
 :dao.agent.mcp/method  "tools/call"
 :dao.agent.mcp/params  {...}}          ; decoded JSON as EDN
```

The interpreter appends exactly one of these per request event:

```clojure
{:dao.agent.mcp/event   :response
 :dao.agent.mcp/session session-id
 :dao.agent.mcp/id      rpc-id
 :dao.agent.mcp/result  {...}}          ; or
{:dao.agent.mcp/event   :response
 :dao.agent.mcp/session session-id
 :dao.agent.mcp/id      rpc-id
 :dao.agent.mcp/error   {:code -32602 :message "..." :data {...}}}
```

A notification (nil `id`) produces no response event. A batch produces
one request event per member, and the transport reassembles the batch
response by collecting egress events whose ids belong to the batch. The
transport's only state for this is the set of outstanding batch ids,
held in the transport state map, never in a global.

### 3.5 Decode rules

Before deposit the transport checks, in order:

1. The line or message parses as JSON; otherwise reply `-32700` directly
   from the transport (no event is deposited; there is no id to correlate).
2. The object has `"jsonrpc": "2.0"`; otherwise reply `-32600`.
3. `method` is a string; otherwise reply `-32600`.
4. `id`, if present, is a string, number, or null.

Everything that passes is deposited, including unknown methods and bad
params. Those are interpreter judgments (section 6), and the transform
judges nothing semantically.

## 4. Tool catalog

Every tool is a pure function of `(state params) -> [state' outcome]`.
`state` is the explicit session state map of section 5. No tool reaches
a global, a clock, or a host resource except through a handle held in
`state`. Every tool's parameters are validated against its schema before
the function body runs; a schema failure is `-32602`.

Every tool has a **budget**: a read-set limit, a step limit, or a timeout
expressed in engine scheduler rounds. Budgets are per call, are supplied
by the composition at session construction, and are clamped by the tool
if the caller asks for more.

### 4.1 `dao_space_query`

Runs a declarative `dao.space.query/q` or `match` over the session's
readable sources.

```text
+---------------+---------+------------------------------------------+
| Parameter     | Type    | Rule                                     |
+---------------+---------+------------------------------------------+
| query         | string  | EDN of a q form or a match pattern       |
| sources       | array   | Names from state :sources; default: all  |
| as_of         | integer | Optional t bound, applied to every source|
| limit         | integer | Rows returned; clamped to :max-rows      |
+---------------+---------+------------------------------------------+
```

Rules:

- `query` is read with `edn/read-string` under `{:readers {}}` and
  `{:eof nil}`; reader macros other than the built-in literals are
  refused as `-32602`. The parsed form must be a vector beginning with
  `:find` or a match pattern; anything else is `-32602`.
- Sources are opened once at session construction and held in
  `:sources` as `{name d5-source}`. The query never opens or closes a
  source. A name not in `:sources` is `-32602`.
- Read-set limit: the engine is handed `:max-rows`; a result that would
  exceed it is truncated and the outcome carries
  `{:truncated true :limit n}` so the LLM can narrow the query.
- Timeout budget: the query runs under a step budget expressed as the
  maximum number of relation rows the engine may scan (`:max-scan`).
  Exhaustion answers the qualified defect
  `{:dao.agent.mcp/defect :budget-exhausted :budget :max-scan}`, mapped
  to `-32000` (section 6).
- Result rows are returned as EDN-printed strings inside the MCP
  `content` array with `"type": "text"`, plus a structured
  `structuredContent` object `{rows: [...], count: n, truncated: bool}`.

### 4.2 `dao_space_deposit`

Transacts datoms into the session's own single-writer stream through the
session's `dao.space.transactor` value (or its `dao.space.schema` wrapper
when the session was constructed with a schema).

```text
+---------------+---------+------------------------------------------+
| Parameter     | Type    | Rule                                     |
+---------------+---------+------------------------------------------+
| tx_data       | string  | EDN vector in the transactor vocabulary  |
| strict        | boolean | Optional; only honored if the session    |
|               |         | was constructed with a schema wrapper    |
+---------------+---------+------------------------------------------+
```

Rules:

- **Atomic schema check.** Every item must be an entity map with
  `:db/id`, a `[:db/add e a v]`, or a `[:db/retract e a]` /
  `[:db/retract e a v]`. Raw five-slot datoms are refused: `t` is the
  transactor's to allocate (`dao.space.transactor.md`, T10), and `m` is
  the server's to fill (below). One malformed item refuses the whole
  request with `-32602` and appends nothing.
- **Provenance validation.** The server fills `m` on every emitted datom
  with a reference to the session's provenance entity (section 5.3). A
  request that supplies its own `m` is refused with `-32602`: an LLM
  cannot claim an authorship it does not have.
- **Single-writer.** The only writer to the session stream is the session
  transactor. There is no parameter naming a target stream. Two sessions
  never share a transactor value (T6).
- The receipt is returned as given by the transactor:
  `{:dao.stream/outcome :dao.stream/ok :dao.space/t t ...}`, or a
  non-ok outcome as data with no `t`. A `:dao.stream/full` outcome is
  reported as an MCP error with `data.retryable = true`.
- Budget: `:max-datoms-per-tx`. A request that expands past it is refused
  before any append.

### 4.3 `debruijn_eval`

Evaluates a verified de Bruijn register image inside an isolated Register
VM frame.

```text
+---------------+---------+------------------------------------------+
| Parameter     | Type    | Rule                                     |
+---------------+---------+------------------------------------------+
| hash          | string  | R, the register image identity           |
| steps         | integer | Requested step budget; clamped           |
| store         | object  | Optional initial store map (EDN string   |
|               |         | values); host scalars only               |
+---------------+---------+------------------------------------------+
```

Rules:

- The image is obtained by `debruijn_link_fetch` semantics (section 4.4)
  before any execution. An image that does not fetch and verify is never
  run; the fetch refusal is the outcome.
- **Isolated frame.** Each call constructs a fresh kernel state
  `{:image :pc 0 :registers [] :frames [] :free-env env :continuation []
  :store store :status :running :steps-remaining n}` from the session's
  `:vm-env` (primitives and modules) and the supplied `store`. Nothing
  from a previous call is visible. The session's stream handles are not
  in the store; an image that executes `:stream-put` or `:ffi-call`
  receives the engine's qualified unsupported outcome, because no
  call-in pair and no stream ids were supplied.
- **Step budget clamping.** `:steps-remaining` is
  `(min steps (:max-steps state))`. When it reaches zero the kernel
  halts with `:status :budget-exhausted` and the outcome carries the
  partial state summary `{:pc p :steps-used n}`; nothing is retried.
- A `:park` inside the image halts the run and the outcome carries the
  parked payload as data (section 5.2.2 of
  `yin.vm.debruijn.register.md`). The server does not resume it; a
  caller that wants resumption goes through the harness.
- The outcome is `{:status :halted :value v}` or `{:status s :defect d}`
  where `s` is one of `:budget-exhausted`, `:parked`, `:error`, and `d`
  is the engine's qualified defect. The VM's value is EDN-printed.

### 4.4 `debruijn_link_fetch`

Fetches and verifies a code image through the Phase B6 linker.

```text
+---------------+---------+------------------------------------------+
| Parameter     | Type    | Rule                                     |
+---------------+---------+------------------------------------------+
| format        | string  | "yin.debruijn.register" or               |
|               |         | "yin.debruijn.code"                      |
| hash          | string  | R or H                                   |
| fallback      | string  | Optional: "trusted" or "verifying"       |
+---------------+---------+------------------------------------------+
```

Rules:

- The call is `(linker/fetch (:jing-handle state) (:index state)
  format-record hash)` with the format record chosen from
  `linker/register-format` or `linker/stack-format`. Handle and index
  are session state, supplied at construction.
- Every refusal in the B6 vocabulary (`:absent`, `:address-mismatch`,
  `:hash-mismatch`, `:descriptor-defect`, `:unresolved-free`,
  `:shadowed-free`, `:pairing-mismatch`) is returned as the tool's
  structured outcome, not as a JSON-RPC error: a refusal is a correct
  answer to a fetch, not a protocol failure.
- `fallback` applies only when `format` is the register format and the
  session's `:vm-env` has no register kernel; then same-root pairing is
  attempted per B6 section 7 and the outcome names which path ran.
- On success the outcome is `{:verified true :format f :hash h
  :body-count n :instruction-count m}`; the image bytes are not returned
  to the LLM. The LLM addresses code by hash, never by text.

### 4.5 `task_claim`

Proposes a claim on a task by asserting claim datoms into the session's
own stream. The claim is a **proposal**; the harness governor grants or
refuses it by linearizing over the board stream (`dao.agent.schema.md`,
section 5; `dao.agent.harness.md`, section 4).

```text
+---------------+---------+------------------------------------------+
| Parameter     | Type    | Rule                                     |
+---------------+---------+------------------------------------------+
| task_id       | string  | The :task/id value (globally meaningful) |
| attempt       | integer | Optional; default 1 + prior attempts by  |
|               |         | this agent on this task, from :sources   |
+---------------+---------+------------------------------------------+
```

Rules:

- The tool first runs a bounded query over `:sources` for the task's
  current status. If no task with that `:task/id` is visible, the
  outcome is `{:claimed false :reason :absent}`. If the task is not
  `:ready`, the outcome is `{:claimed false :reason :not-ready
  :status s}`. Neither is a JSON-RPC error.
- Otherwise it transacts, atomically, in the session stream:

```clojure
[{:db/id       claim-eid
  :claim/task  [:task/id task-id]      ; lookup ref by value
  :claim/agent [:agent/id agent-id]
  :claim/attempt attempt
  :claim/proposed-at t-now}]           ; from the session clock source
```

- The "CAS" of the parent document is realized as a stream-offset lease:
  the claim is an offset in the proposer's stream, and the governor's
  grant names that offset as `:claim/epoch`. The tool returns the
  transactor receipt plus `{:claimed :proposed :claim claim-eid}`. The
  grant, if any, arrives later as datoms the LLM must query for. The
  tool never blocks waiting for it.
- A second `task_claim` for the same task from the same session while a
  proposal is unanswered is refused with `{:claimed false
  :reason :pending}` and appends nothing.

## 5. Session state

### 5.1 The state map

The whole server is a value transformed by each event. One session's
state is:

```clojure
{:session-id      uuid
 :status          :new | :ready | :closing | :closed
 :agent           {:agent/id kw :agent/model str}
 :provenance-eid  eid                 ; section 5.3
 :sources         {name d5-source}    ; read-only, opened at construction
 :transactor      transactor-value    ; single writer to :own-stream
 :own-stream      handle              ; the agent's append-only log
 :jing-handle     handle              ; dao.jing get surface
 :index           index-fn-or-map     ; H/R -> jing address
 :vm-env          {:primitives m :modules m :kernel :register | :none}
 :budgets         {:max-rows n :max-scan n :max-steps n
                   :max-datoms-per-tx n}
 :clock           {:now-fn f}         ; injected; never a host global
 :pending-claims  #{task-id}
 :ingress-cursor  cursor
 :egress          handle}
```

The interpreter is one function:

```clojure
(defn step
  "One request event in, one state out plus zero or one egress event.
   Pure except for the appends performed through handles in state."
  [state event] -> [state' egress-event-or-nil])
```

and the loop is a fold of `step` over the ingress cursor. There is no
`def` holding a session, no atom shared across sessions, and no registry
of sessions; `main` holds one state map in a local binding.

### 5.2 Lifecycle

```text
:new --initialize--> :new --notifications/initialized--> :ready
:ready --shutdown--> :closing --egress drained--> :closed
```

A `tools/call` before `:ready` answers `-32002` (server not initialized,
the MCP-reserved code). After `:closing`, every request answers `-32001`
(session closing). Close order is transactor close, then egress
completion, then transport close; the local stream is the composition's
and is not closed (T7).

### 5.3 Provenance entity

At construction the session transacts one entity into its own stream:

```clojure
{:db/id             prov-eid
 :provenance/kind   :dao.agent.mcp/session
 :provenance/agent  [:agent/id agent-id]
 :provenance/model  "claude-fable-5-1"
 :provenance/transport :stdio | :ws
 :provenance/opened-at t}
```

Every datom the session emits carries `prov-eid` in `m`. This is the
`agent-smith.md` flight recorder at the coarsest grain: the identity of
the process that spoke. Per-tool-call provenance (inputs, outputs,
timestamps) is the inward client's duty, not this server's; see
`dao.agent.mcp.client.md`, section 6.

## 6. Diagnostics and error mapping

### 6.1 JSON-RPC codes

```text
+--------+---------------------------+--------------------------------+
| Code   | JSON-RPC meaning          | datom.world source             |
+--------+---------------------------+--------------------------------+
| -32700 | Parse error               | transport: undecodable JSON    |
| -32600 | Invalid request           | transport: bad envelope        |
| -32601 | Method not found          | interpreter: unknown method    |
| -32602 | Invalid params            | tool schema refusal; caller-   |
|        |                           | supplied m; raw d5; bad EDN    |
| -32603 | Internal error            | a thrown exception in a tool   |
| -32000 | Server error (budget)     | :budget-exhausted              |
| -32001 | Server error (closing)    | session :closing / :closed     |
| -32002 | Server not initialized    | tools/call before :ready       |
+--------+---------------------------+--------------------------------+
```

### 6.2 Defect maps

Every non-protocol failure is a qualified map before it is a code:

```clojure
{:dao.agent.mcp/defect  :budget-exhausted      ; the rule
 :dao.agent.mcp/tool    "dao_space_query"
 :dao.agent.mcp/session session-id
 :budget                :max-scan
 :limit                 100000}
```

The map is the `data` field of the JSON-RPC error object, keywords
rendered as namespaced strings. The mapping from defect to code is one
table in `dao.agent.mcp.error`, and the reverse mapping is not needed:
the defect map is authoritative, the code is a courtesy to generic
clients.

A thrown host exception is classified at the interpreter boundary into
`{:dao.agent.mcp/defect :internal :class "..." :message "..."}` with the
stack trace dropped. No host exception object crosses onto the egress
stream (`datom.world.md`: "no host type crosses onto the stream").

### 6.3 Diagnostics to the session stream

Each request event and each egress event is also appended to the
session's own stream as a diagnostic datom when the session was
constructed with `{:trace true}`:

```clojure
[trace-eid :mcp.trace/session session-id]
[trace-eid :mcp.trace/id      rpc-id]
[trace-eid :mcp.trace/method  "tools/call"]
[trace-eid :mcp.trace/tool    "dao_space_query"]
[trace-eid :mcp.trace/outcome :ok | :error]
[trace-eid :mcp.trace/code    -32602]
```

Trace datoms carry the same `m` as everything else. Tracing is optional
and off by default; when on, it is a fact about the session that any
observer can query, not a log file.

## 7. Host isolation

The catalog is closed. The rules that keep it closed:

1. **No filesystem tool.** There is no `read_file`, `write_file`, or
   `list_dir`. Source code is reached by `dao_space_query` over AST
   datoms and by `debruijn_link_fetch` over content-addressed images.
2. **No shell tool.** There is no `bash` or `exec`. A test run, a build,
   or a git operation is a host capability that only the harness may
   request, and it does so through the inward client
   (`dao.agent.mcp.client.md`) with a parked continuation and a
   provenance record, never through this server.
3. **No network tool.** The server holds `:jing-handle`, which may be a
   remote `content-client` over `dao.stream`; that is the only network
   reach, and it is read-only content addressing with hash verification.
4. **No ambient authority.** `main` constructs every handle from
   explicit arguments and passes them in the state map. A tool that
   needs a handle not in `state` cannot obtain one.
5. **No mutable escape through `debruijn_eval`.** The store handed to the
   kernel contains only the caller's scalars; the session's stream and
   jing handles are not in it, so image code cannot append or fetch.

An LLM that asks for a capability outside the catalog receives `-32601`
with `data {:dao.agent.mcp/defect :not-a-tool :requested "bash"}`. The
refusal is data the LLM can read and adapt to.

## 8. Portability and the CLI entry point

`src/clj/dao/agent/mcp/main.clj`:

```clojure
(defn -main [& args]
  ;; parse: --agent-id, --model, --sources <edn>, --jing <edn>,
  ;;        --index <edn>, --budgets <edn>, --schema, --trace
  ;; construct: own memory-log stream, transactor, session state
  ;; loop: read stdin lines -> deposit -> step -> write stdout
  ;; exit: on EOF or shutdown, close transactor, flush, exit 0
  ...)
```

The loop body is `(reduce step state ingress-events)` with the transport
reading `stdin` into the ingress stream and draining egress to `stdout`
between steps. The only JVM-specific code is `System/in`, `System/out`,
`System/exit`, and argument parsing. The same `step` runs under CLJS with
a Node `readline` adapter feeding the same ingress stream; that adapter
is a separate file and is not part of this phase's file box.

## 9. Test contract

`test/dao/agent/mcp/server_test.cljc` pins, over mock `memory-log`
streams and a mock jing store:

1. Lifecycle: `initialize` before anything else; `tools/call` before
   `:ready` answers `-32002`; `shutdown` drains and closes.
2. Framing: `-32700` and `-32600` come from the transport with no
   ingress event; a batch of three answers three; notifications answer
   nothing.
3. `tools/list` returns exactly five descriptors with the parameter
   schemas of section 4.
4. `dao_space_query`: a valid `q` answers rows; `limit` clamps; a scan
   past `:max-scan` answers `-32000` with the defect map; an unknown
   source answers `-32602`.
5. `dao_space_deposit`: entity maps and adds append with the session's
   `m`; a caller-supplied `m` answers `-32602` and appends nothing; a raw
   d5 answers `-32602`; the receipt is the transactor's, unchanged.
6. `debruijn_eval`: a pure image halts with its value; `steps` is
   clamped to `:max-steps`; exhaustion answers `:budget-exhausted`; an
   image issuing `:stream-put` gets the engine's unsupported outcome; two
   calls share no store.
7. `debruijn_link_fetch`: each B6 refusal is a structured outcome, not a
   JSON-RPC error; a verified fetch does not return image bytes.
8. `task_claim`: absent, not-ready, pending, and proposed outcomes; the
   proposal's datoms carry the session `m`.
9. Isolation: a `tools/call` naming `bash` answers `-32601` with the
   `:not-a-tool` defect.
10. No global state: two sessions constructed in one test process
    interleave without sharing a transactor, a cursor, or a claim set.

Verification runs the focused test on JVM and Node/CLJS, kondo, cljstyle,
and the 80-column design check over this document.

## 10. Non-goals

- Resources, prompts, sampling, roots, and any MCP capability beyond
  `tools`.
- Authentication and authorization. The transport composition decides
  who may connect; the capability-token gatekeeper of ADR 0002 is the
  future home of per-tool authority and is not designed here.
- Resuming a parked `debruijn_eval` run. That is the harness's loop.
- Any tool that is not one of the five.
