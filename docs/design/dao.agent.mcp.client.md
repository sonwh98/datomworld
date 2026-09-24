# dao.agent.mcp.client: The Inward FFI Bridge

Status: design specification (2026-09-24); not implemented

Parent: `docs/design/dao.agent.md`, sections 5.2 and 5.3. This document
specifies how a Register VM program invokes an external MCP tool: opcode
lowering, continuation parking and resumption, crash resilience, and
the provenance flight recorder. Siblings: `dao.agent.mcp.server.md`,
`dao.agent.schema.md`, `dao.agent.harness.md`.

## 1. Objective and position

The Register VM has no host access. When an agent program needs a
capability the host owns (a model inference, a test runner, a compiler,
a git operation) the program emits an `:ffi-call` and parks. The inward
client is the bridge that reads that request from the VM's call-in
stream, speaks MCP to an external server, and writes the answer to the
VM's call-out stream. The VM resumes with the answer as data.

This is the existing `yin.vm.ffi` bridge (`docs/design/yin.vm.ffi.md`,
`dao.stream.apply.md`) with one new handler family, `:mcp/invoke`, and
three disciplines the FFI bridge alone does not impose:

1. Every parked continuation is persisted to `dao.jing` before the
   tool runs (section 5).
2. Every invocation is recorded as provenance in the `m` slot of the
   datoms it produces (section 6).
3. Every tool the bridge may reach is named in an explicit allowlist
   held in the bridge state (section 7).

```text
  Register VM (R4)                      host
  +----------------------+     +---------------------------------+
  | :ffi-call :mcp/invoke|     |  dao.agent.mcp.client           |
  |   -> request on      |---->|  1. read call-in                |
  |      call-in         |     |  2. persist snapshot to jing    |
  |   -> park (sparse    |     |  3. record :invocation datoms   |
  |      snapshot)       |     |  4. JSON-RPC tools/call to the  |
  |                      |     |     external MCP server         |
  |   <- resume value    |<----|  5. write response on call-out  |
  |      from call-out   |     |  6. record completion datoms    |
  +----------------------+     +---------------------------------+
                                        |
                                        v
                               external MCP server
                               (test runner, compiler, model,
                                git, ...) over stdio or ws
```

The VM does not know MCP exists. It emits `dao.stream.apply` requests
and reads `dao.stream.apply` responses; `:mcp/invoke` is one op name in
the bridge's handler map.

## 2. Namespace and file box

```text
New: src/cljc/dao/agent/mcp/client.cljc      bridge state, handler,
                                              persist, provenance
New: src/cljc/dao/agent/mcp/client/rpc.cljc  JSON-RPC 2.0 client
                                              framing (stdio, ws)
New: test/dao/agent/mcp/client_test.cljc
Existing edits: none
Depends on: yin.vm.ffi (require-call-pair!, call-result,
            response-wait-entry), dao.stream.apply, dao.stream,
            dao.stream.ws, dao.jing, dao.space.transactor,
            yin.vm.debruijn-register-effects (payload validator),
            yin.vm.debruijn.register (R4 kernel), dao.agent.schema
Must not change: yin.vm.*, dao.stream.*, dao.jing.*, dao.space.*
```

The parent document's Phase 3 says "Implement `:ffi-call :mcp/invoke`
in `yin.vm.debruijn.register`". This document narrows that: the kernel
needs no change. `:ffi-call` already takes any `:ffi-op` keyword
(`yin.vm.debruijn.register.md`, section 4.4) and dispatches through the
call-in stream. `:mcp/invoke` is a bridge handler, not a kernel opcode.
The invariant "`yin.vm` never requires `dao.agent.*`" holds by
construction.

## 3. FFI boundary: opcode lowering

### 3.1 The source form

An agent program written in the named AST invokes a host tool as:

```clojure
{:type :dao.stream.apply/call
 :op   :mcp/invoke
 :args [{:tool "run-tests"
         :args {:image "R:..." :suite "yin.vm.debruijn"}
         :server :host/test-runner}]}
```

`:server` names an entry in the bridge allowlist (section 7). `:tool`
and `:args` are passed through to the MCP `tools/call` request.

### 3.2 The register lowering

R2 lowers `:dao.stream.apply/call` as (`yin.vm.debruijn.register.md`,
section 4.6):

```text
lower operands left to right into temporaries r_p ...;
[:ffi-call rd :mcp/invoke [r_p] live]
```

`live` is the section 4.5 live set at that pc, computed by
`body-liveness` and verified on every receiving host. `rd` receives the
tool result on resume. Nothing about `:mcp/invoke` is special to the
lowerer; it is a `:kw` operand checked by the descriptor.

### 3.3 Kernel execution of `:ffi-call`

Per section 5.2.1 of the register design, unchanged:

1. `ffi/require-call-pair!` confirms the store holds both call-in and
   call-out handles; a VM without a pair refuses before parking. The
   `debruijn_eval` tool of the outward server constructs kernels
   without a pair, so images run there cannot reach any host tool.
2. Arguments are read from `arg-regs` in order.
3. The request envelope is built:

```clojure
{:dao.stream.apply/id   parked-id
 :dao.stream.apply/op   :mcp/invoke
 :dao.stream.apply/args [{:tool ... :args ... :server ...}]}
```

4. The continuation is parked as an FFI writer if call-in answers
   `:dao.stream/full`, else appended and parked as an FFI reader on
   call-out at the current cursor. The wait entry shapes are the
   register design's section 5.2.3 forms; the sparse payload contains
   exactly the live registers.

The parked id is the correlation id for the whole invocation, through
the bridge, the external server, provenance, and resume.

## 4. Continuation parking and resumption (Model 2)

The interaction is `co-routines.md` Model 2, host-driven park/resume,
with the FFI wait set as the parking mechanism:

```text
Cycle n:   VM runs, reaches :ffi-call, appends request to call-in,
           parks as FFI reader. scheduler-round finds nothing runnable;
           the VM value is the parked descriptor set.

Host:      bridge step (section 4.1) reads call-in, persists, records,
           invokes the tool. This may take milliseconds or days.

Cycle n+1: bridge writes the response to call-out. The FFI reader's
           cursor observes it; scheduler-round promotes the entry;
           register-restore writes the unwrapped value to rd and
           continues.
```

### 4.1 The bridge step

The bridge is a value and one function:

```clojure
{:handlers    {:mcp/invoke handler}      ; the only handler here
 :cursor      call-in-cursor
 :servers     {server-kw server-state}   ; section 7
 :jing-handle handle
 :transactor  transactor-value           ; bridge's own stream
 :agent       {:agent/id kw}
 :clock       {:now-fn f}
 :inflight    {call-id inflight-state}}

(defn step
  "Read at most one request from call-in; if present, run sections 4.2
   through 4.4 and return [bridge' effect-or-nil]. Pure except for the
   appends and the external call performed through handles in bridge."
  [bridge vm] -> [bridge' vm'])
```

The harness calls `step` between VM scheduler rounds. It never runs on
its own thread and never holds a callback into the VM.

### 4.2 Before the tool runs: persist and record

In this order, each step appending one atomic record:

1. **Snapshot to jing.** The parked payload for `parked-id` is read
   from the VM's wait set, validated with the R2 payload validator
   (`continuation-defect`), and materialized into `dao.jing`. Its
   `segment-key` is `snapshot-key`. The payload is pure data with no
   handles (register design, section 5.2.3), so it serializes.
2. **Request to jing.** The request envelope's `:args` value is
   materialized; its key is `args-key`.
3. **Invocation datoms** in the bridge's own stream (section 6.1), with
   `:invocation/status :started`.

Only after all three land does the external call begin. If the process
dies between step 3 and the response, the invocation is recoverable
(section 5).

### 4.3 The external call

`client/rpc` frames one JSON-RPC 2.0 request:

```json
{"jsonrpc": "2.0",
 "id": "<parked-id>",
 "method": "tools/call",
 "params": {"name": "run-tests",
            "arguments": {"image": "R:...", "suite": "..."}}}
```

over the server's transport (stdio child process or `dao.stream.ws`).
The server state holds the transport handle, an `initialize`d flag, and
a `:pending` map from request id to `{:started reading}`. The transport
adapter for responses is an "events in" adapter: each inbound message
becomes one datom on the bridge's response stream, and the bridge reads
that stream on its next step. No response is delivered by callback.

The bridge does not block on the response. A step that has sent a
request returns with the invocation in `:inflight`; a later step, on
reading a response datom whose id matches, proceeds to section 4.4.

### 4.4 After the tool returns: record and resume

1. **Result to jing.** The MCP `result` (or `error`) object is
   materialized; its key is `result-key`.
2. **Completion datoms** in the bridge's stream (section 6.1), with
   `:invocation/status :completed` or `:failed`.
3. **Response to call-out.** The bridge appends:

```clojure
{:dao.stream.apply/id    parked-id
 :dao.stream.apply/value result-value}            ; or
{:dao.stream.apply/id    parked-id
 :dao.stream.apply/error {:kind kw :message str :data {...}}}
```

The VM's `ffi/call-result` unwraps this on the reader path. A value is
written to `rd` by `register-restore`; an error is raised as the
engine's qualified FFI error at the resume point. No error register,
no raw exception (register design, section 5.2.3).

4. **Remove from `:inflight`.** The bridge state forgets the call.

`:inflight` is bookkeeping for the running process only; the durable
record is the invocation datoms plus the jing keys. A bridge rebuilt
after a crash has an empty `:inflight` and reconstructs what it needs
from its stream (section 5).

### 4.5 Model 1 interplay

If call-in is full when the VM issues `:ffi-call`, the VM parks as an
FFI writer (Model 1 backpressure). The bridge draining call-in is what
unblocks it; the identical request is then appended and the entry
converts to a reader with every stale wake key removed
(`ffi/response-wait-entry`). The bridge never sees a half-sent request.

If call-out is full when the bridge writes a response, the bridge's
append answers `:dao.stream/full` and the bridge keeps the invocation
in `:inflight` with `:phase :response-pending`, retrying on its next
step. The response value is already in jing under `result-key`, so a
crash here loses nothing.

## 5. Crash resilience

### 5.1 What survives

After an abrupt process termination, the following exist in `dao.jing`
and in published indexes of the bridge's stream:

- The sparse continuation snapshot, by `snapshot-key`.
- The request arguments, by `args-key`.
- The result, by `result-key`, if the tool had returned.
- The invocation datoms with their `:invocation/status`.

The VM's in-memory wait set, the `memory-log` streams, and `:inflight`
are gone. That is the expected shape (`dao.space.transactor.md`, "Where
durability lives"): the durable record is what publication put in jing.
The bridge therefore publishes its stream after every completion
record (`transactor/publish!`), and the harness composition is
responsible for publishing after every started record as well when the
tool's expected duration exceeds the composition's tolerance for
re-running it.

### 5.2 Rehydration

On restart the harness constructs a fresh bridge and VM, then runs
`rehydrate`:

```clojure
(defn rehydrate
  "Query the bridge's published index for invocations not :completed
   or :failed. For each, load the snapshot from jing, validate it, and
   return the set of parked entries to install plus the invocations to
   re-issue or to complete."
  [bridge index] -> {:install [wait-entry ...]
                     :reissue [invocation ...]
                     :complete [invocation ...]})
```

Per unfinished invocation:

```text
+-----------------------------+----------------------------------------+
| State found                 | Action                                 |
+-----------------------------+----------------------------------------+
| :started, no result-key     | Install the parked reader from the     |
|                             | snapshot; re-issue the request with the|
|                             | same parked-id (idempotency key) and   |
|                             | :invocation/attempt + 1                |
+-----------------------------+----------------------------------------+
| :started, result-key present| Install the parked reader; write the   |
| (crashed between 4.4.1 and  | response from result-key to call-out;  |
| 4.4.3)                      | record :completed                      |
+-----------------------------+----------------------------------------+
| snapshot fails validation   | Record :invocation/status :unrecoverable|
| (foreign R, live mismatch)  | with the defect; the seat's claim is   |
|                             | released by the harness                |
+-----------------------------+----------------------------------------+
```

Installing a parked reader means adding the validated payload plus the
FFI reader keys to the VM's wait set at the call-out cursor the new
process minted. `register-restore` then works exactly as it would have
without the crash: it reads only the validated payload and the
documented FFI keys, never anything from the dead process.

### 5.3 Idempotency of re-issue

The parked id is the JSON-RPC id and is carried in the request
arguments as `_dao_call_id` so a tool that supports idempotency can
deduplicate. Whether the external tool is idempotent is a property of
that tool, declared in the allowlist entry as `:idempotent true|false`.
A non-idempotent tool with a `:started` invocation and no result is not
re-issued automatically; `rehydrate` returns it under `:needs-operator`
and the harness records `:liveness/manual-recovery` for the operator to
answer with a datom. Test runners and compilers are idempotent; a git
push is not.

### 5.4 Quota and rate-limit responses

An MCP error whose server-declared class is a rate limit is classified
by the handler as:

```clojure
{:dao.stream.apply/error {:kind :quota :until reading :retryable true}}
```

The bridge does not write this to call-out. Instead it leaves the VM
parked, records `:invocation/status :quota-parked` with `:until`, and
lets the harness governor wake the seat when a tick passes `:until`
(`dao.agent.harness.md`, section 6.3), at which point the request is
re-issued as in 5.2. The parked continuation stays in jing throughout.

## 6. Provenance flight recorder

### 6.1 Invocation datoms

Every invocation is an entity in the bridge's own stream:

```text
+---------------------------+-----------+------+-----------------------+
| Attribute                 | valueType | card | Meaning               |
+---------------------------+-----------+------+-----------------------+
| :invocation/id            | string    | one  | parked-id, unique     |
| :invocation/agent         | keyword   | one  | :agent/id of the seat |
| :invocation/server        | keyword   | one  | allowlist key         |
| :invocation/tool          | string    | one  | MCP tool name         |
| :invocation/args-key      | string    | one  | jing key of args      |
| :invocation/snapshot-key  | string    | one  | jing key of the       |
|                           |           |      | parked continuation   |
| :invocation/image-hash    | string    | one  | R of the parked image |
| :invocation/site-pc       | long      | one  | pc of the :ffi-call   |
| :invocation/status        | keyword   | one  | :started | :completed |
|                           |           |      | | :failed |           |
|                           |           |      | :quota-parked |       |
|                           |           |      | :unrecoverable        |
| :invocation/attempt       | long      | one  | 1, 2, ...             |
| :invocation/result-key    | string    | one  | jing key of result    |
| :invocation/error-kind    | keyword   | one  | on :failed            |
| :invocation/started-at    | long      | one  | ms, from bridge clock |
| :invocation/completed-at  | long      | one  | ms, from bridge clock |
+---------------------------+-----------+------+-----------------------+
```

`:started` and `:completed` are two transactions on one entity;
`schema/current` collapses `:invocation/status` to the latest. The
history view keeps both, so "how long did this take" is a query over
the two `t`s' rows and their `*-at` values, and "what did it see" is a
`jing/get` of `args-key` and `result-key`.

Inputs and outputs are never inlined as datom values. They may be large,
they may contain host-shaped strings, and they are the tool's syntax,
not the board's. The board holds the keys.

### 6.2 The `m` slot

Every datom the resumed VM subsequently causes the seat to append (an
artifact, a review, a claim release) carries in `m` a reference to the
invocation entity that produced the value it acted on. The seat's Act
beat receives the resumed value together with its `parked-id`, and the
harness sets the seat transactor's `m` for that transaction to the
lookup ref `[:invocation/id parked-id]` resolved in the bridge's stream
by value (cross-stream, so the seat copies the string into
`:provenance/invocation` on a small metadata entity in its own stream
and points `m` at that entity).

This is `agent-smith.md`'s flight recorder made concrete: "every effect
emitted by an agent can have its `m` point to the specific reasoning
trace that produced it." Here the trace is the invocation entity, and
through it the exact prompt (args-key), the exact response
(result-key), the exact continuation state at the call (snapshot-key),
and the exact program (image-hash, site-pc).

`m` never participates in temporal ordering or card-one collapse
(`dao.space.schema.md`, section 4). It is provenance only.

### 6.3 What the recorder cannot see

The recorder sees the boundary: what went into the tool and what came
out. It does not see what the tool did on the host. A test runner that
reads a file the agent did not name is invisible here; that is the
external server's containment problem, and the allowlist (section 7)
is the bridge's only lever over it.

## 7. The allowlist

The bridge reaches only servers named in its state:

```clojure
{:host/test-runner {:transport :stdio
                    :command   ["clojure" "-M:test-mcp"]
                    :tools     #{"run-tests" "run-suite"}
                    :idempotent true
                    :budget    {:timeout-ticks 600}}
 :host/model       {:transport :ws
                    :descriptor {...}                ; dao.stream.ws
                    :tools     #{"llm-infer"}
                    :idempotent true
                    :budget    {:timeout-ticks 1200}}
 :host/git         {:transport :stdio
                    :command   [...]
                    :tools     #{"commit" "push"}
                    :idempotent false
                    :budget    {:timeout-ticks 120}}}
```

A request naming a server not in the map, or a tool not in that
server's `:tools`, is answered on call-out with
`{:kind :not-permitted}` and recorded as `:failed` with
`:invocation/error-kind :not-permitted`. It is never sent. The allowlist
is composition data passed to the bridge constructor; the bridge cannot
extend it.

`:budget :timeout-ticks` is judged by the harness governor against the
tick stream, not by the bridge: an invocation `:started` more than that
many ticks ago with no result is recorded `:failed` with
`:invocation/error-kind :timeout` and the VM is resumed with that error.
The bridge has no clock of its own beyond the injected `:now-fn` used
for the diagnostic `*-at` values.

## 8. Test contract

`test/dao/agent/mcp/client_test.cljc` pins, over an R4 kernel, in-memory
call-in and call-out streams, a mock jing store, and a scripted mock
MCP server:

1. Round trip: an image executing `:ffi-call :mcp/invoke` parks; one
   bridge step persists snapshot and args, records `:started`, and
   sends a well-formed `tools/call`; a scripted response and a second
   step record `:completed`, write call-out, and the next scheduler
   round writes the value to `rd`; the program halts with the expected
   value.
2. Order: the snapshot and `:started` datoms exist in jing and the
   bridge stream before the mock server observes the request.
3. Sparse snapshot: the persisted payload's `:regs` indices equal the
   image's `live` operand at `:site-pc`; a dead register's value is
   absent.
4. Crash before response: drop the VM and bridge after step 1;
   `rehydrate` from the published index installs one parked reader and
   re-issues with attempt 2 and the same id; the program completes with
   the same value as test 1.
5. Crash after result, before call-out: `rehydrate` completes from
   `result-key` without contacting the mock server.
6. Non-idempotent tool crash: `rehydrate` returns the invocation under
   `:needs-operator`; nothing is re-issued.
7. Quota: a scripted rate-limit error leaves the VM parked, records
   `:quota-parked`, and a governor wake re-issues.
8. Allowlist: an unknown server and a known server with an unlisted
   tool both answer `:not-permitted` on call-out and send nothing.
9. Provenance: the datom the resumed program appends carries an `m`
   whose metadata entity names the invocation id; following it yields
   args-key, result-key, snapshot-key, image-hash, and site-pc.
10. Backpressure: a capacity-1 call-in with two concurrent `:ffi-call`s
    parks the second as a writer; after the bridge drains, both
    complete; request bytes of the retried writer are identical.
11. No host type on any stream: every value on call-in, call-out, the
    bridge stream, and the response stream satisfies the plain-data
    predicate; a mock server that throws is classified to
    `{:kind :internal}` with no exception object.
12. Purity: `step` over equal bridge and VM values and equal stream
    contents yields equal results.

Verification runs the focused test on JVM and Node/CLJS, the R2 effects
tests unchanged, kondo, cljstyle, and the 80-column design check.

## 9. Non-goals

- Changing `yin.vm.debruijn.register` or `yin.vm.ffi`. `:mcp/invoke` is
  a handler.
- MCP capabilities beyond `tools/call` on the client side (no resources,
  prompts, sampling).
- Cross-model continuation transport. A register snapshot resumes only
  under a register kernel with the same R (register design, section
  5.1).
- Sandboxing the external tool's own host behavior.
- Containing what a tool does once invoked; the allowlist bounds which
  tools can be invoked, not what they do.
