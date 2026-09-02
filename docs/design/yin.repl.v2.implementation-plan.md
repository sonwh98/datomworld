# Yin REPL on DaoStream v2 — the remote REPL slice

Status: implementation plan, derived from and subordinate to `dao.stream.md`
(the contract) and `dao.stream.ws.md` (the WebSocket specification). Where this
plan and either document disagree, they win. It is a sibling of
`dao.stream.v2.implementation-plan.md` and depends on part of it; where the two
overlap, that plan owns the transport and this one owns the RPC layer and the
REPL. This document is transient: it is consumed as its phases complete.

Revised against a five-model review of 2026-09-02
(`collab/review-yin-repl-v2-plan.*.stdout.log`), which found the first draft
unexecutable on two counts: it claimed a v1-free REPL while `yin.vm` consumes
its streams through v1 protocols, and it claimed the REPL "already owns a
polling loop" when v1's RPC client owned it. Both are settled below.

**Nothing existing is modified.** Every deliverable here is a new namespace
alongside the one it replaces. `dao.stream`, `dao.stream.ws`,
`dao.stream.rpc.*`, `yin.repl` and their tests are untouched, keep running, and
keep their consumers. This is not a refactor; it is a second implementation
built beside the first, and the two coexist until each consumer migrates under
its own plan. The one exception is `shadow-cljs.edn`, discussed under
*Namespaces and files*, which cannot be avoided and is additive.

## The whole of it

**A local Yin REPL, a `connect` that reaches a remote one over v2 WebSockets, a
`serve!` that answers, and nothing else.**

The commands that must work: `(help)`, `(repl-state)`, `(vm :type)` — for
`:ast-walker`, see *The VM* — `(lang :lang)`, `(compile …)`, `(reset)`,
`(connect "url")`, `(disconnect)`, `(quit)`, ordinary source evaluation
locally, datom-literal evaluation, and ordinary source evaluation forwarded to
a connected remote. `--port`, `--host` and `--headless` behave as they do
today.

It must do that on **all three hosts — clj, cljs (Node), and cljd** — because
the REPL is the one surface people actually run on each of them, and because a
contract whose whole justification is meaning the same thing everywhere is not
demonstrated by two hosts out of three.

This plan is finished when someone can start a v2 REPL server, attach to it from
a second process, evaluate, disconnect, and reattach — on each host.

## The VM

`yin.vm` consumes streams through v1 protocols: `engine/run-on-stream` polls the
VM's `:in-stream` with v1 `ds/next` seeding `{:position 0}`
(`yin/vm/stream_driver.cljc:25-26`), and `yin/vm/telemetry.cljc:281` appends
with v1 `ds/append!`. A v2 handle implements none of that, so handing the v1 VM
a v2 stream throws on the first evaluation.

**The v2 REPL therefore runs on `yin.vm.v2`**, specified in
[`yin.vm.v2.implementation-plan.md`](./yin.vm.v2.implementation-plan.md) and a
prerequisite of this plan. That is a second VM beside the first, ~3,142 lines,
built on v2 streams throughout, requiring no v1 namespace.

What follows from it:

- **Datom-literal evaluation works**, because the v2 VM's in-stream is a v2 ring
  buffer the REPL can append to.
- **`(telemetry)` is out**, in every form. The VM plan ships a stub telemetry
  namespace — every `emit-snapshot` is already a no-op when no stream is
  installed — which defers the emit path's stream work and its classification
  problems out of the slice. `--telemetry` and `--telemetry-stream` are
  rejected with an error naming the v1 REPL, and there is no `ws://` sink.
- **`(vm :type)` accepts only `:ast-walker`**, and **the default changes** from
  v1's `:semantic` (`repl.cljc:183`). Four evaluators remain to port, not three:
  `semantic`, `register`, `stack` and `space`.
- **User-defined macros stop evaluating.** `yang.clojure` compiles every macro
  call site to `:yin/macro-expand`; the ast-walker has no such branch and
  throws. `defn` survives via its native compile path.
- **`stream/take!` is removed** from the v2 `stream` module; programs use
  `cursor` and `next!`.
- **Park-on-full backpressure is gone under this composition.** The REPL
  supplies a ring buffer, which evicts rather than returning `full`, so writers
  never park. The VM itself is still total over `full` and parks when a
  transport returns it; v1's reject-mode behaviour additionally depended on
  destructive take, which v2 does not have.

  All five are scope decisions in the VM plan, not limitations of the transport,
  and all five are in its divergence register.

An earlier draft of this plan took the opposite route — constructing the v1 VM
with `:in-stream nil`, which `engine.cljc:310` guards on, and dropping
datom-eval and telemetry from the slice. That works and is recorded here because
it remains the fallback if `yin.vm.v2` slips: the REPL can ship transport-first
with a v1 VM and no VM-owned streams, then gain both features when the v2 VM
lands. It is not the plan.

## Namespaces and files

| New | File |
|-----|------|
| `dao.stream.v2.rpc.client` | `src/cljc/dao/stream/v2/rpc/client.cljc` |
| `dao.stream.v2.rpc.server` | `src/cljc/dao/stream/v2/rpc/server.cljc` |
| `dao.stream.v2.rpc.ws` | `src/cljc/dao/stream/v2/rpc/ws.cljc` |
| `yin.repl.v2.core` | `src/cljc/yin/repl/v2/core.cljc` |
| `yin.repl.v2.driver` | `src/cljc/yin/repl/v2/driver.cljc` |
| `yin.repl.v2` | `src/cljc/yin/repl/v2.cljc` |
| `yin.repl.v2.runner` | `src/clj/yin/repl/v2/runner.clj` |

`dao.stream.v2`, `dao.stream.v2.ringbuffer` and `dao.stream.v2.ws` are the
sibling plan's deliverables. The cljd half of `dao.stream.v2.ws` is this plan's,
per *Prerequisites*.

**Build configuration.** `deps.edn` gains `:clj-yin-repl-v2`,
`:cljs-yin-repl-v2`, `:cljd-yin-repl-v2` and `:cljd-yin-repl-v2-build` beside
the existing set. But `:cljs-yin-repl` is only
`shadow.cljs.devtools.cli run yin.repl/-main`, which executes a Clojure function
on the JVM; the Node REPL is the `:yin-repl` **`:node-script`** build in
`shadow-cljs.edn`. So the cljs deliverable needs a `:yin-repl-v2` build added
there. **This is the one edit to an existing file this plan makes**: it is
purely additive, touches no existing build, and there is no way to define a
shadow build without it. `bin/` gains a v2 Dart entry importing
`lib/cljd-out/yin/repl/v2.dart`, since `runner.clj:13` runs
`bin/yin_repl_main.dart` rather than the alias's `:output-dir`.

**cljd file layout, decided now.** v1's precedent is a *shadow*
`src/cljd/dao/stream/ws.cljd` hand-synced with the `.cljc`, because a single
`.cljc` leaks `:clj` branches into cljd's host-eval pass. `#?(:clj …)` does not
exclude code from the cljd build; `#?(:cljd nil :clj …)` with `:cljd` **first**
is required, and `:cljd` in tail position silently fails. Every new `.cljc` file
here is written that way from the start, and full cljd namespace compilation is
a gate on each phase. The verbatim copy from `yin/repl.cljc` contains bare
`#?(:clj …)` blocks (lines 63, 836 among others) that must be rewritten as they
are copied; a shadow `.cljd` is the fallback if any file resists.

**On duplication.** `yin.repl.v2` cannot share code with `yin.repl`, because
sharing would mean editing it, and the helpers are `defn-` so they cannot be
reached anyway. Reviewers disagreed on the size — one counted ~350 of 1085 lines
as verbatim-copyable, another 620–680 — and agreed on the shape: **the split is
sync versus async, not stream versus non-stream.** Functions that straddle
include `emit-output!` (113), `make-repl-primitives` (120), `make-vm` (160),
`create-state` (175, inline `{:position 0}` cursors), `stream-status` and
`repl-state` (213–299, uses `ds/closed?`), the `collect-*` and `finalize-eval`
chain (381–451), `eval-datoms` (454), `handle-command` (552), and the whole
`eval-input` chain (652–731).

R2's first deliverable is therefore a **function-level inventory** of all 1085
lines marked *copy verbatim*, *copy and adapt*, or *rewrite*. `yin.repl.v2.core`
holds the verbatim set; `yin.repl.v2` holds the rest. Guessing the boundary is
what produced the wrong estimate twice.

## The v2 RPC layer

`dao.stream.rpc.*` cannot be ported. The diagnosis was checked against the code
by four reviewers and holds: v1 reads responses from the same duplex handle it
wrote to (`rpc/client.cljc:155` through `WebSocketStream`'s hidden
`:remote-stream` inbox, `ws.cljc:68` — the very thing `dao.stream.ws.md:160`
forbids); its cursors are `(:position …)` arithmetic seeded from
`(atom {:position 0})` (`rpc/client.cljc:29-42, 129`); and it returns a Promise
on cljs and a Future on cljd (`rpc/client.cljc:55-57, 108-111`).

**A client is constructed from two handles, not one** — a writer for requests,
and a reader plus cursor for the response medium. That asymmetry is the v2
shape: under a transport with no reader surface the two directions are two
streams, and pretending otherwise is what produced the hidden inbox.

**Client state, explicitly:**

```clojure
{:writer      <handle>          ; request path
 :reader      <handle>          ; response medium
 :cursor      <cursor>          ; the successor next returned; never arithmetic
 :me          <attachment-id>   ; nil on a private medium
 :decode      <fn>              ; envelope -> response, or nil for bare values
 :outstanding {id {:op … :args …}}
 :unsent      nil-or-{:id … :op … :args …}
 :completed   [ … ]}
```

- **`request!`** appends and returns the next state. On `:dao.stream/full`
  nothing was appended: the request and **its already-allocated id** are
  retained in `:unsent`, and the next `request!` retries that one rather than
  minting a new id. `closed`, `invalid-value` and `transport-error` are terminal
  for that request.
- **`poll!`** drains the response medium up to a budget through its own cursor,
  classifies each element, matches responses to `:outstanding`, and returns the
  next state plus completions. `:dao.stream/blocked` returns to the caller; that
  is the yield, and the caller must then return to its host loop.
- **One driver owns client state and threads it serially.** Two callers holding
  the same immutable state mint the same next id and produce divergent states.
  Several requests may be outstanding at once — ids are matched at drain time,
  which removes the whole bug class v1's `advance-cursor!` monotonic-max surgery
  exists to patch — but only under one poller.
- **Timeouts are the driver's, not the layer's.** A step-driven layer has no
  clock; deadlines are the caller's, and `now` is passed in if the layer needs
  it at all.
- **Loss is conservative.** On a `:dao.stream/gap` the recovery cursor says
  nothing about which responses were skipped, so **every outstanding request is
  reported lost**, not a computed subset. The same applies on any terminal
  lifecycle event for this attachment — `:ws/closed`, `:ws/not-found`,
  `:ws/transport-error` — which must be reported as loss rather than left to
  time out.
- **Reattachment is a rebind.** `rebind` swaps a dead writer for a fresh
  `attach!` result and takes the new `:me`, keeping the cursor. Without it the
  client filters on a stale attachment id and drops everything the new boundary
  deposits.

**The response medium carries envelopes, not responses.** On a real socket every
element is `{:ws/attachment … :ws/event … :ws/value …}`, mixed across
attachments and interleaved with `:ws/opened`, `:ws/closed`, `:ws/ended`,
`:ws/not-found` and `:ws/transport-error`. `poll!` must filter to `:me`, advance
past other attachments' events without treating them as responses, unwrap only
`:ws/payload`, and convert lifecycle events into client transitions.
`dao.stream.v2.rpc.ws` supplies that `:decode` and consumes the **whole**
`attach!` result including `:dao.stream/attachment`. R1 tested over bare ring
buffers passes and then breaks at R3 unless this seam is built in R1.

**Server state, explicitly:**

```clojure
{:request-cursor      <cursor>
 :pending-response    nil-or-<encoded>
 :pending-request-id  nil-or-<id>}
```

`serve-step` retries `:pending-response` **before** reading another request. A
response `append!` that returns `full` must not advance the request cursor — and
must not re-run the handler either, because Yin evaluation is stateful and
re-execution is wrong. v1 sidesteps this by ignoring the write result
(`rpc/server.cljc:65`); that cannot be copied into an API claiming totality.
Each request advances the cursor once and executes its handler once.

**The envelope is fixed in R1**: request and response value shapes, correlation
id representation, and handler-error encoding, under `:dao.stream.v2.rpc/…`
keys. v1's `dao.stream.apply` maps (`apply.cljc:42-63`) and its `{:error msg}`
wrapping (`rpc/server.cljc:24-38`) are the obvious model, copied under v2 keys
rather than required from v1.

**Not in the v2 layer:** `retry` and `dedup`, which exist for the lossy UDP
transport that v2 does not have. They stay on v1 with `dao.stream.rpc.udp`.

## The REPL driver

The first draft asserted that the REPL "already owns a polling loop." It does
not, and never did: `rpc-client/wait-for-response` owned it
(`rpc/client.cljc:77-111`) and the REPL consumed its Promise or Future
(`repl.cljc:526-546`, with the cljs main's re-prompt promise-chained at 903 and
the cljd main's at 1044). The three main loops are **line-event loops**, not
response-poll loops.

So the driver is a deliverable, not a caller convenience. `yin.repl.v2.driver`
owns one step:

```clojure
;; Called once per externally driven tick; never loops on :blocked.
(defn repl-step [state now] …)
```

which drains the output stream, polls the RPC client, advances any server
sessions, publishes completions to the printer, and returns the next state. It
never loops on `blocked`.

**Per host, because cadence is the runtime's** (`dao.stream.md:178-180`):

- **clj** — `run-cli!` parks in `read-line` (`repl.cljc:856`). One owned poller
  thread runs `repl-step` on an interval so `:ws/opened` and remote results
  print when they arrive rather than after the user's next keystroke. A caller
  wanting a blocking wait for one response may write a sleep loop; that is
  ordinary control flow on a host that can park.
- **cljs (Node)** — the readline line handler must **return** after `request!`.
  A single `setInterval` owns `repl-step`, printing, and the prompt discipline,
  replacing the promise chain at `repl.cljc:903`.
- **cljd** — identically, with `Timer.periodic`, replacing the chain at
  `repl.cljc:1044`. A synchronous `(loop [] (poll!) (recur))` deadlocks the Dart
  event loop: ws IO never progresses, so `blocked` never clears.

**One poller, one client state.** Two tickers on one client deliver completions
to the wrong consumer.

**`eval-input`'s contract changes and must be stated**: local evaluation returns
a value; remote evaluation returns immediately with a request id, and the result
is printed by the driver when it completes. Input typed while a request is
outstanding is queued, not evaluated — v1 has a clobber race there
(`repl.cljc:1044-1047`) that must not be copied.

The claim that host divergence disappears is **withdrawn**. It moves: out of the
RPC layer, which now has one shape everywhere, and into one small named driver
per host. That is the reduction this plan actually delivers.

## Decisions

**D2 — a reply travels on the connection's server-side handle. Settled.**
The accepted-connection handle is writer+closable with its writer surface on
that connection's ordered outbound path (`dao.stream.ws.md:44-48`), so replies
are private by construction. The alternative — a per-conversation served stream
driven by `forward-step` — buys an outbound replay history that is worthless
until resumption is specified, and resumption is deferred. `forward-step` (4b)
is therefore genuinely unnecessary here.

**D3 — `/repl` names a service-lifetime stream. Settled now, not deferred.**
The first draft chose D2a and left D3 open, which does not work: a descriptor
names a server-hosted stream that exists independently of connections and that
the same descriptor reaches every time (`dao.stream.ws.md:9-16`), so with
nothing served the handshake must authoritatively disclaim `/repl` and *every*
`connect` deposits `:ws/not-found`.

So the endpoint `create!`s **one composition-owned ring buffer at start**,
registers it in the resolution table under `/repl`, and both the client handle
and each accepted-connection handle answer `descriptor` with it
(`dao.stream.ws.md:38-42`). It carries no ordinary outbound values in this
slice — nothing is forwarded into it, which is composition policy and not a
spec violation. Closing it is the endpoint's `stop!`, which closes each
attachment with the ended-stream close code, and that is what makes R5's fourth
fact implementable at all.

Only the descriptor **key names** wait for the gate. The shape is settled here.
A pathless `daostream:ws://host:port` normalizes to the `/repl` path.

**D4 — one shared shell, and evaluation is serialized. Settled.**
v1 builds every handler over one `state-atom` (`repl.cljc:782`) and the `:op/eval`
handler reads and resets it (`repl.cljc:734`). Independent per-connection loops
would race on VM state; correlating replies by attachment does not prevent that.
The v2 server therefore runs **one driver** that fairly advances every session
and threads a single explicit REPL state serially. Connecting clients share one
remote shell, which is what v1 does today and what the existing tests assume —
they send sequentially (`repl_test.cljc:581`), so they never settled it.

**Chain-forwarding is out.** v1's `:op/eval` handler forwards to the server's
own remote endpoint when one is connected, and that is tested
(`repl_test.cljc:320`). A step-driven handler cannot produce that answer
synchronously without a multi-stage handler state. The v2 server evaluates
locally or reports that it does not proxy.

## Prerequisites

From [`yin.vm.v2.implementation-plan.md`](./yin.vm.v2.implementation-plan.md):

- **Through Phase V5** — `yin.vm.v2.ast-walker` and its closure. It needs only
  the sibling stream plan's Phases 1 and 2.
- **V1 specifically gates this plan's R1**, because `dao.stream.v2.apply` owns
  the request/response envelope and `dao.stream.v2.rpc.client`/`.server` are
  built there, socket-free. R1 is therefore **blocked**, not merely sequenced —
  the earlier draft's "executable today" was wrong on two counts: no
  `dao.stream.v2*` exists, and the envelope has an owner elsewhere.

From `dao.stream.v2.implementation-plan.md`:

- **Phase 1 and Phase 2, completed** — the protocols, the result convention, and
  the ring buffer, including the conformance harness that Phase 1 defines and
  Phase 2 first runs. If R1 depends on a completed Phase 2 then it depends on
  the harness too; the earlier draft's exclusion of it was wrong.
- **Phase 4a, the ws transport, both ends**, on clj and cljs.
- **The cljd v2 ws transport**, which the sibling plan defers and **this plan
  owns**, over `dart:io`. It owes the same manifest and conformance evidence as
  the other two hosts.
- **Both decision gates**: the descriptor key set, and the wire contract.

**Not prerequisites:** Phase 3 (the REPL attaches to a ws endpoint by URL, never
to a ring buffer by descriptor), 4b (dead under D2a), 4c (R4 builds the REPL's
own serving composition, which re-derives 4c's list minus the forwarder), and
Phase 5 (this plan is that proof).

**Blocked, not merely sequenced.** Three answers do not exist in any document
today, and R3 and R4 cannot be built without them. Each needs an amendment to
`dao.stream.ws.md`, which this plan cannot make while subordinate to it:

1. **Server-side attachment identity** — the contract defines
   `:dao.stream/attachment` only in an `attach!` success map, and a server
   handle is minted from a handed-over socket with no `attach!` call.
2. **Accept notification** — how a composition learns a connection was accepted,
   and receives its writer handle, without the `:on-connect` callback the spec
   forbids. This is more basic than identity and nothing addresses it.
3. **The wire contract's contents** — handshake presentation, disclaimer form,
   ended-stream close code, value codec, decode-failure behaviour.

**Nothing here is executable today.** R1 is blocked on the VM plan's V1, R2 on
its V5, and both on the sibling stream plan's Phases 1 and 2 — none of which
exists: `src/cljc/dao/stream/v2*` is absent from the tree. R3 through R5 are
additionally gated on the three spec answers above. The plan states this rather
than sequencing past it.

## Phase R1 — `dao.stream.v2.rpc.ws`

The envelope, the client and the server are the VM plan's V1. What is left here
is the one piece that needs a transport.

- `ws.cljc`: the `:decode` that filters deposited envelopes by `:me`, advances
  past other attachments' events without treating them as responses, unwraps
  only `:ws/payload`, and converts `:ws/opened`, `:ws/closed`, `:ws/not-found`
  and `:ws/transport-error` into client transitions.
- `init-client` from the whole `attach!` result, so `:dao.stream/attachment`
  becomes `:me`.
- Tested with hand-built envelopes over a ring buffer, so it needs no socket and
  can be written before R3.

Deliverable: envelope decode and client construction on clj, cljs and cljd,
against the V1 client with one shape on all three.

## Phase R2 — `yin.repl.v2` and its driver, local only

No socket, no wire, no RPC.

- **The function-level inventory** of `yin/repl.cljc` first; then
  `yin.repl.v2.core` and `yin.repl.v2`.
- `make-vm` constructs a `yin.vm.v2.ast-walker`, supplies `:make-stream` bound
  to the v2 ring buffer, registers the v2 `stream` module, and hands it a v2
  in-stream, so `eval-datoms` appends to it. The REPL is the composition that
  chooses the VM's transport; the VM requires none. No telemetry stream is installed and the
  `(telemetry)` command is absent, per *The VM*. `vm-constructors` has one
  entry.
- The output stream is a v2 ring buffer with a **declared capacity of 4096
  elements**; its drain loop mints a cursor with `cursor` and advances by the
  successor `next` returns. A `gap` there prints an explicit loss notice and
  resumes at the recovery cursor. `end`, `cursor-mismatch`, `invalid-cursor` and
  `transport-error` each get a stated response. (The sibling Phase 2 declares
  only evict-oldest with declared capacity; nothing here needs an unbounded
  mode, so none is requested.)
- **`repl-state` without `closed?`.** The state carries a per-stream last-outcome
  ledger with an explicit `:untried` value, since an idle stream has no last
  operation and there is nothing to ask.
- `yin.repl.v2.driver` with `repl-step`, and the per-host tickers.
- The four `deps.edn` aliases and the `shadow-cljs.edn` build **land here**, not
  in R5, because R2's deliverable is stated in terms of them.

Deliverable: a working local REPL on all three hosts — `clj -M:clj-yin-repl-v2`,
the `:yin-repl-v2` node build, `clj -M:cljd-yin-repl-v2` — requiring no v1
namespace.

## Phase R3 — The client side

- `connect` normalizes the URL to a descriptor whose keys come from the settled
  gate, calls `attach!`, and gets a handle at once. No promise on any host.
- The composition wires the deposit medium — **one medium per boundary**,
  capacity 8192, matching 4a and demultiplexed by `:ws/attachment`, which the
  client already does. Per-attachment media would need the boundary-level
  announcement stream `dao.stream.ws.md:140-143` requires, which 4a does not
  build.
- The RPC client is built from the ws handle, that medium, a minted cursor
  anchored at `:dao.stream/newest`, and the whole `attach!` result for `:me`.
- **`Connected to …` moves** to when the driver observes `:ws/opened`.
  `:ws/not-found` reports an authoritative disclaimer and is not retried;
  `:ws/transport-error` reports a reachability failure that may succeed on retry.
- `disconnect` is `close!`. Reattaching is `attach!` plus `rebind`, keeping the
  deposit cursor and taking the new attachment id.

## Phase R4 — The server side

- The endpoint creates its `/repl` service-lifetime stream at start (D3) and
  registers it in a host-owned resolution table.
- One request medium per boundary, capacity 8192, demultiplexed by
  `:ws/attachment`.
- One server driver advances every session against a single serially-threaded
  REPL state (D4), running `serve-step` per session.
- Serving lifecycle: start, stop, and who owns them, replacing the `:conns` atom
  and the `:on-connect`/`:on-disconnect` callbacks. `stop!` closes the service
  stream, which closes each attachment with the ended-stream code.
- **On cljd**, `HttpServer.bind` is asynchronous, so `serve!` returns before the
  port is bound. Where bind success, bind failure, upgrade failure, listener
  error, and asynchronous close completion appear **as data** must be defined
  here. Node's `ws` server fails the same way; http-kit throws synchronously.
  v1's cljd `listen!` never sets `:socket-close-fn` (`ws.cljc:396-408`), so
  server-side `close!` never closes the socket — do not copy that.

## Phase R5 — End to end

Per host, then across hosts.

`clj -M:clj-yin-repl-v2 --port 8080 --headless`; from a second process
`(connect "daostream:ws://localhost:8080/repl")`; evaluate; disconnect;
reconnect. Verify:

1. Evaluation round-trips, and two clients each get their own answers.
2. Killing the connection is observable on both sides: the client handle answers
   `closed`, the server's boundary deposits the departure, and every outstanding
   request is reported lost rather than timing out.
3. Reattaching with the same descriptor reaches the same served stream, and the
   client's cursor on its own deposit medium resumes across the socket's death.
4. The endpoint's `stop!` closes the service stream, and the client deposits
   `:ws/ended`, not `:ws/closed`.

Then the cross-host pair: a cljd client against a JVM server, and a Node client
against a cljd server. The descriptor crossed a codec and the wire is the same
wire; if that fails, the contract was implemented three times rather than once.

`src/cljc/yin/vm/docs/yin.repl.v2.md` is written here. The existing
`yin.repl.md` is left alone. The new document states what differs: `connect`
returns immediately and reports its outcome when known, `(vm :type)` offers
`:ast-walker` only, and there is no `(telemetry)` command.

## Host matrix

All three hosts, first class, every phase.

- **Declare the `append!` outcome subset per host, with reasons**, which is what
  the contract asks (`dao.stream.md:417-424`). Transient `full` may not be
  detectable anywhere: `java.net.http sendText` buffers, Node's `ws.send`
  buffers, and Dart's `WebSocket.add` buffers, none exposing backpressure. If
  `full` is excluded by nature on a host, say so with the reason rather than
  inventing a signal.
- On cljd, `add` after close throws `StateError` synchronously and must be
  classified to `:dao.stream/closed`. The codec exists at
  `src/cljd/dao/stream/transit.cljd`.
- Full cljd namespace compilation gates each phase, per *Namespaces and files*.

## Boundary of this plan

**Untouched** — no edits, no deletions, no deprecation markers: `dao.stream.cljc`
and everything under `src/cljc/dao/stream/`; `yin/repl.cljc`,
`src/clj/yin/repl/runner.clj`, `src/cljd/yin/repl/flutter.cljd`; their tests;
the existing `deps.edn` aliases and `shadow-cljs.edn` builds. `dao.jing.remote`
keeps working on v1 and gets its own plan; it is the harder migration, since its
`call!` is synchronous-on-JVM and `jing/materialize!` and `jing/get` dispatch
through it as ordinary value-returning calls.

**Deliberately out of this slice:** telemetry in every form, per *The VM* —
which also removes the `ws://` sink that would have been a second ws client
proving nothing `connect` does not; the `:semantic`, `:register` and `:stack`
evaluators, which follow in the VM plan; chain-forwarding through a
server's own remote (D4); the Flutter widget; `retry`, `dedup` and a v2 UDP
transport; ws-level resumption; flow control; and authentication — `--host
127.0.0.1` remains the only boundary, as in v1.

## End condition

Complete when, on each of clj, cljs (Node) and cljd, a v2 REPL server accepts a
connection from a second process, evaluates forms sent to it, and survives a
disconnect and reattach — with `yin.repl.v2` requiring no v1 namespace, which
running on `yin.vm.v2` makes true rather than aspirational. R5's four facts plus
the cross-host pair are the test.

Coexistence is the expected end state. Both REPLs ship, both alias sets work,
and `dao.stream.rpc.*` keeps serving `dao.jing.remote`. Deleting v1 is the
stream plan's end condition, when its last consumer has migrated — of which this
is the first.
