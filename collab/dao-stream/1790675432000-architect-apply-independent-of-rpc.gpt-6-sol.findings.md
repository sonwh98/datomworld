Coding-Agent: codex
Session-ID: 01a0ec92-d475-7172-ade6-a6321090db0b
Model: gpt-6-sol

Completed-GMT: 2026-09-29 09:51:32 GMT
Completed-Local: 2026-09-29 16:51:32 Asia/Ho_Chi_Minh

## Verdict

**`dao.stream.apply` defines application request and response values and an explicit step for serving them over `dao.stream` handles. It has no dependency on, or vocabulary for, RPC.** An RPC client may use apply envelopes unchanged as its wire values, but its lifecycle and completion reasons belong to `dao.stream.rpc`. This corrects my earlier ruling that assigned those reasons apply-qualified names. Master is `643b1ba6b97b7a19bb76e0ef73527b175f26dc38`.

### 1. Apply boundary

Keep the open request, success, and error shapes; endpoint descriptors; append and read helpers; and `serve-once!`. Request/response pairing does not make `serve-once!` RPC-specific: it is one interpretation of two caller-supplied stream handles, with explicit state, a retained response on `full`, and no scheduler or client allocation policy ([apply.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/apply.cljc:15), [apply.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/apply.cljc:119), [apply.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/apply.cljc:301)). It can run over a framebuffer-like medium or a local pair.

Its own `invalid-request`, `invalid-response`, `malformed-request`, `unknown-operation`, `handler-error`, `idle`, `responded`, `pending-response`, `response-undeliverable`, `terminal`, `gap`, and `request-gap` describe envelope validation or this server step’s observation of a medium. **Keep `gap`:** it records `dao.stream/gap` on the server’s request cursor; the remote responder separately chooses to treat that observation as terminal loss ([apply.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/apply.cljc:341), [responder.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ffi/remote_serve/responder.cljc:181)). Raw `:dao.stream/transport-error` is likewise a handle outcome, not an apply-defined error code.

Remove RPC and transport terminology from apply’s namespace documentation: “transport-neutral” at line 2 and the RPC client comparison in `correlation-id?` at lines 24–26. State simply that the ID is any non-nil opaque value and allocation policy belongs to its user. The remaining descriptions of streams, cursors, handlers, and server state describe apply’s actual operations ([apply.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/apply.cljc:1), [apply.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/apply.cljc:24)).

### 2. Reason ownership

Use distinct **RPC completion and terminal reasons** `:dao.stream.rpc/not-found`, `/detached`, `/ended`, `/no-surface`, `/oversize`, and `/transport-error`. Translate remote protocol reasons at the RPC boundary: remote `not-found`, `channel-gone`, `no-surface`, and `oversize` map respectively to the first, second, fourth, and fifth words; stream `end` maps to RPC `ended`; unclassified failure maps to RPC `transport-error` ([rpc.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/rpc.cljc:427), [rpc.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/rpc.cljc:446)). Keep `:dao.stream.remote/*` for the remote protocol’s own errors; do not rename them into apply. Preserve `:dao.stream/gap` as the distinct medium loss reason already used by RPC polling ([rpc.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/rpc.cljc:501)). Only RPC `detached` permits `rebind`; all other terminal reasons remain terminal for that binding ([rpc.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/rpc.cljc:569)).

An apply error response may carry a **qualified code owned by the application or interpreter that generated the response**, including a truthful loss report. Apply validates and carries that code; it does not define transport failure codes or synthesize a response when a client fails to receive one. Its error predicate already accepts any qualified code and a string message ([apply.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/apply.cljc:63)). Keep RPC events, diagnostics, and completions separate from the unchanged apply wire envelopes ([rpc.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/rpc.cljc:9)).

### 3. VM FFI loss

Change the locally generated loss error code from `:dao.stream.apply/ended` to **`:yin.vm.ffi/response-lost`**. Retain `:yin.vm.ffi/loss` with `:dao.stream/end` or `:dao.stream/gap`, the observed fact that distinguishes the cases. The VM knows its call-out reader ended or skipped values; it does not know the responder’s underlying cause. Preserve the existing plain error data and portable exception path ([ffi.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ffi.cljc:91), [ffi.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ffi.cljc:115)). Update the responder’s explanation accordingly ([responder.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ffi/remote_serve/responder.cljc:29)).

### 4. Pending response cursor

Fix this in `rpc/request!` in the same breaking change. If the response cursor is still a standard anchor, return **`:dao.stream.rpc/cursor-pending` with the identical state**, no ID, no allocation, and no append. Resolve the anchor through `rpc/poll!`; a retryable mint leaves it pending, while a terminal mint completes outstanding work through the existing terminal path ([rpc.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/rpc.cljc:279), [rpc.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/rpc.cljc:464), [rpc.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/rpc.cljc:531)). Apply the gate before retrying any `:unsent` envelope as well, so no request crosses before the reader position is established.

Map the new result in the REPL adapter and let the driver keep the line queued and its cadence active. Remove the driver’s independent *send-safety* guard once the RPC rule and tests pass; its queue and cadence logic may still inspect pending state, preferably through an RPC predicate, to schedule work. The current guard documents the lost-answer race and blocks submission in two paths ([driver.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/driver.cljc:197), [driver.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/driver.cljc:447), [driver.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/driver.cljc:548)); the adapter needs a new outcome mapping ([adapter.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/adapter.cljc:110)).

### 5. Migration and acceptance

Use **one breaking commit**. The code and tests must agree on reason names and cursor behavior at every intermediate point; no compatibility aliases are needed.

Change these source files: [apply.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/apply.cljc:1), [rpc.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/rpc.cljc:427), [adapter.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/adapter.cljc:110), [driver.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/driver.cljc:197), [connect.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/connect.cljc:478), [ffi.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ffi.cljc:97), and [responder.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ffi/remote_serve/responder.cljc:29). Review [serve.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/serve.cljc:577) for its reason description; it has no matching reason literal. [observe.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/observe.cljc:23) needs no behavior change: it already names apply and RPC as separate interpreters.

Change these assertion files: [rpc_test.cljc](/Users/sto/workspace/datomworld/test/dao/stream/rpc_test.cljc:148), [apply_test.cljc](/Users/sto/workspace/datomworld/test/dao/stream/apply_test.cljc:1), [adapter_test.cljc](/Users/sto/workspace/datomworld/test/yin/repl/adapter_test.cljc:148), [driver_test.cljc](/Users/sto/workspace/datomworld/test/yin/repl/driver_test.cljc:210), [connect_test.cljc](/Users/sto/workspace/datomworld/test/yin/repl/connect_test.cljc:146), [serve_connect_wire_test.clj](/Users/sto/workspace/datomworld/test/yin/repl/serve_connect_wire_test.clj:118), [ffi_test.cljc](/Users/sto/workspace/datomworld/test/yin/vm/ffi_test.cljc:470), and [responder_test.cljc](/Users/sto/workspace/datomworld/test/yin/vm/ffi/remote_serve/responder_test.cljc:589). Update the stream design text where this boundary is described in [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:923), and correct the stale apply-qualified examples in [dao.jing.remote.implementation-plan.md](/Users/sto/workspace/datomworld/docs/design/dao.jing.remote.implementation-plan.md:749).

Acceptance tests must establish:

- A source/documentation check finds no RPC or transport vocabulary in `dao.stream.apply`’s namespace text and docstrings, and no RPC dependency.
- Apply request, response, and `serve-once!` work over a local framebuffer-like or plain stream pair with no RPC namespace in scope; opaque non-numeric IDs remain valid.
- Each remote reason maps to its distinct RPC completion and terminal reason; gap stays distinct; only detached rebinds.
- `request!` on an unresolved anchor returns `cursor-pending` without allocating, appending, or changing state; retryable mint remains pending; successful mint permits one request; terminal mint prevents sending. Preserve the REPL race regression ([driver_test.cljc](/Users/sto/workspace/datomworld/test/yin/repl/driver_test.cljc:210)).
- VM response end and gap raise the VM-owned code with their distinct loss facts, and remote serving still transports the original apply request and correlated response.

### 6. Owner decisions

- **Governing:** `dao.stream.apply` is independent of the concept of RPC and can use a framebuffer. This supersedes my earlier apply-qualified reason ruling.
- **Retained from that ruling:** one apply envelope as RPC’s wire value; separate namespace ownership; unchanged VM request map; non-nil opaque apply correlation IDs; distinct loss reasons; detached alone rebindable.
- **Resolved here for implementation:** RPC owns client completion reasons and the pending-cursor gate; the VM owns its observed FFI loss code; apply’s server step and request-gap observation remain medium-neutral.

## Ordered migration plan

1. Rename RPC terminal and completion reasons and update `rebind`, REPL connection handling, and their assertions.
2. Add the RPC pending-cursor gate, adapter outcome, and RPC plus driver race tests; then remove the driver’s duplicate send guard while preserving queue cadence.
3. Re-home the VM FFI loss code and update responder documentation and tests.
4. Clean apply documentation, add the namespace-vocabulary and local-medium acceptance tests, and update the design and historical-plan text.
5. Run the affected stream, REPL, and FFI suites and inspect the final diff as one breaking commit.

This is an architectural ruling only; no files were edited or tests run.
