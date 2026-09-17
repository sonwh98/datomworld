# Yin REPL on DaoStream v2 — the remote REPL slice

Status: implementation plan, derived from and subordinate to `dao.stream.md`
(the contract) and `dao.stream.ws.md` (the WebSocket specification). Where this
plan and either document disagree, they win. It is a sibling of
`dao.stream.implementation-plan.md` and depends on part of it; that sibling
owns the transport, the VM plan's V1 owns `dao.stream.apply` and the
socket-free RPC core, and this plan owns only the WebSocket RPC decoder and the
REPL. This document is transient: it is consumed as its phases complete.

Revised against a five-model review of 2026-09-02
(`collab/review-yin-repl-plan.*.stdout.log`), which found the first draft
unexecutable on two counts: it claimed a v1-free REPL while `yin.vm` consumes
its streams through v1 protocols, and it claimed the REPL "already owns a
polling loop" when v1's RPC client owned it. Both are settled below.

**No existing implementation is modified.** Every implementation deliverable
here is a new namespace alongside the one it replaces. `dao.stream`,
`dao.stream.ws`, `dao.stream.rpc.*`, `yin.repl` and their tests are untouched,
keep running, and keep their consumers. This is not a refactor; it is a second
implementation built beside the first, and the two coexist until each consumer
migrates under its own plan. There are exactly **two additive build-configuration
exceptions**: a new `:yin-repl` `:node-script` build in `shadow-cljs.edn` and
four new aliases in `deps.edn`, both discussed under *Namespaces and files*.
No existing alias or build is changed or removed.

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

**The v2 REPL therefore runs on `yin.vm`**, specified in
[`yin.vm.implementation-plan.md`](./yin.vm.implementation-plan.md) and a
prerequisite of this plan. That is a second VM beside the first, ~3,142 lines,
built on v2 streams throughout, requiring no v1 namespace.

What follows from it:

- **Datom-literal evaluation works**, because the shell owns a v2 program ring
  buffer and appends datom batches through its writer. Since the VM plan's V7,
  the shell — not the VM — owns that medium: it composes the descriptor, the
  unary attacher, and an attached `dao.stream.observer` beside the VM,
  and datom evaluation drives `observer/run-on-stream`; the VM accepts no
  `:in-stream` of its own.
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
it remains the fallback if `yin.vm` slips: the REPL can ship transport-first
with a v1 VM and no VM-owned streams, then gain both features when the v2 VM
lands. It is not the plan.

## Namespaces and files

| New | File |
|-----|------|
| `dao.stream.rpc.ws` | `src/cljc/dao/stream/rpc/ws.cljc` |
| `yin.repl.core` | `src/cljc/yin/repl/core.cljc` |
| `yin.repl.driver` | `src/cljc/yin/repl/driver.cljc` |
| `yin.repl` | `src/cljc/yin/repl.cljc` |
| `yin.repl.runner` | `src/clj/yin/repl/runner.clj` |

`dao.stream`, `dao.stream.ringbuffer` and `dao.stream.ws` are the
sibling plan's deliverables. The cljd half of `dao.stream.ws` is this plan's,
per *Prerequisites*. `dao.stream.apply`, `dao.stream.rpc.client`, and
`dao.stream.rpc.server` belong to the VM plan's V1 and are dependencies,
not files owned here.

**Build configuration — the two additive exceptions.** `deps.edn` gains
`:clj-yin-repl`,
`:cljs-yin-repl`, `:cljd-yin-repl` and `:cljd-yin-repl-build` beside
the existing set. But `:cljs-yin-repl` is only
`shadow.cljs.devtools.cli run yin.repl/-main`, which executes a Clojure function
on the JVM; the Node REPL is the `:yin-repl` **`:node-script`** build in
`shadow-cljs.edn`. So the cljs deliverable needs a `:yin-repl` build added
there. These are the plan's only edits to existing files. Both are purely
additive: no existing alias or build is changed, removed, or repointed. The
aliases are required to name the three new host entry points and the cljd build;
the Shadow build is required because a Node artifact cannot be defined outside
`shadow-cljs.edn`. `bin/` gains a v2 Dart entry importing
`lib/cljd-out/yin/repl.dart`, since `runner.clj:13` runs
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

**On duplication.** `yin.repl` cannot share code with `yin.repl`, because
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
lines marked *copy verbatim*, *copy and adapt*, or *rewrite*. `yin.repl.core`
holds the verbatim set; `yin.repl` holds the rest. Guessing the boundary is
what produced the wrong estimate twice.

## The v2 RPC layer

`dao.stream.rpc.*` cannot be ported. The diagnosis was checked against the code
by four reviewers and holds: v1 reads responses from the same duplex handle it
wrote to (`rpc/client.cljc:155` through `WebSocketStream`'s hidden
`:remote-stream` inbox, `ws.cljc:68` — the very thing the WebSocket spec's
*The Duplex Model* forbids); its cursors are `(:position …)` arithmetic seeded from
`(atom {:position 0})` (`rpc/client.cljc:29-42, 129`); and it returns a Promise
on cljs and a Future on cljd (`rpc/client.cljc:55-57, 108-111`).

**A client is constructed from two handles, not one** — a writer for requests,
and a reader plus cursor for the response medium. That asymmetry is the v2
shape: under a transport with no reader surface the two directions are two
streams, and pretending otherwise is what produced the hidden inbox.

**The RPC core consumes a transport-neutral lifecycle vocabulary owned by
`dao.stream.apply`:** `:dao.stream.apply/established`, `/detached`,
`/ended`, `/not-found`, `/transport-error`, and `/diagnostic` (the leading
namespace is elided after the first spelling). Only
`dao.stream.rpc.ws` knows `:ws/…`; its decoder translates WebSocket events
to these values before the core transition algebra sees them.

**Client state, explicitly:**

```clojure
{:writer      <handle>          ; request path
 :reader      <handle>          ; response medium
 :cursor      <cursor>          ; the successor next returned; never arithmetic
 :me          <attachment-id>   ; nil on a private medium
 :decode      <fn>              ; envelope -> response, or nil for bare values
 :next-id     <integer>         ; next never-before-issued safe integer
 :outstanding {id {:op … :args …}}
 :unsent      nil-or-{:id … :op … :args …}
 :completed   [ … ]} ; unpublished completions since the last repl-step publication
```

- **IDs are allocated, not inferred.** `init-client` sets `:next-id` to zero.
  Allocation reserves that safe non-negative integer, increments `:next-id`,
  and never reuses an id during the client state's lifetime. Encountering an id
  already in `:unsent`, `:outstanding`, or `:completed`, or exhausting the
  cross-host safe-integer range, is a terminal local allocator error; it never
  overwrites the earlier request. Only the single `repl-step` owner may allocate.
- **`request!` is total over `append!`.** With no `:unsent`, it first allocates
  and encodes one request. With an `:unsent`, it retries that exact encoded
  request and accepts no new operation. `:dao.stream/ok` clears `:unsent` and
  installs the request in `:outstanding`; `:dao.stream/full` retains the same
  request and already-allocated id for a later step; `:dao.stream/closed`,
  `:dao.stream/invalid-value`, and `:dao.stream/transport-error` clear it and
  append a terminal completion for that request. No append outcome mints a
  replacement id or reports a request outstanding unless it was accepted.
- **`poll!`** drains the response medium up to a budget through its own cursor,
  classifies each element, matches responses to `:outstanding`, and returns the
  next state plus completions. `:dao.stream/blocked` returns to the caller; that
  is the yield, and the caller must then return to its host loop.
- **`poll!` is total over `next`.** `:dao.stream/ok` always advances to the
  exact returned successor before interpreting the element; a valid response
  completes and removes its matching outstanding request, while a response for
  an unknown id becomes an unsolicited-response diagnostic and changes no
  request. `:dao.stream/blocked` leaves the cursor and requests unchanged.
  `:dao.stream/gap` advances to its exact recovery cursor and reports every
  outstanding request lost. `:dao.stream/end`, `:dao.stream/cursor-mismatch`,
  `:dao.stream/invalid-cursor`, and `:dao.stream/transport-error` leave the
  cursor unchanged, report every outstanding request lost, and terminate that
  reader binding. In all loss cases, `:unsent` was never accepted and remains
  eligible only for an explicit rebind/retry decision by the driver.
- **Timeouts are the driver's, not the layer's.** A step-driven layer has no
  clock; deadlines are the caller's, and `now` is passed in if the layer needs
  it at all.
- **Loss is conservative.** On a `:dao.stream/gap` the recovery cursor says
  nothing about which responses were skipped, so **every outstanding request is
  reported lost**, not a computed subset. The neutral `/detached` event is terminal for the
  current attachment but reconnectable: it loses every outstanding request and
  permits `rebind`. `/ended` is terminal for the served stream, loses every
  outstanding request, and is not converted into reconnectable closure.
  `/not-found` and `/transport-error` are terminal resolution failures and
  likewise lose every outstanding request. By contrast, `/diagnostic` is a
  diagnostic: it is forwarded as a non-terminal
  diagnostic and retains the writer, cursor, and requests.
- **Reattachment is a rebind.** `rebind` swaps a dead writer for a fresh
  `attach!` result and takes the new `:me`, keeping the cursor. Without it the
  client filters on a stale attachment id and drops everything the new boundary
  deposits.

- **Completion consumption.** `:completed` is an unpublished outbox, not request history. Every terminal transition appends its completion exactly once. During each `repl-step`, the sole state owner snapshots and publishes the current completions, then returns the next client state with `:completed []`; a later step must not republish them. The vector is therefore bounded by work admitted within one step. Never-reuse across previously published completions is guaranteed by the monotonic `:next-id` high-water mark, not by retaining completed IDs indefinitely. Collision checks cover `:unsent`, `:outstanding`, and any currently unpublished completion.

**The response medium carries envelopes, not responses.** On a real socket every
element is `{:ws/attachment … :ws/event … :ws/value …}`, mixed across
attachments and interleaved with `:ws/opened`, `:ws/closed`, `:ws/ended`,
`:ws/error`, `:ws/not-found` and `:ws/transport-error`. `poll!` must filter to
`:me`, advance past other attachments' events without treating them as
responses, unwrap only `:ws/payload`, translate the six known lifecycle kinds
to the neutral vocabulary above, and apply the core transitions. A well-formed current-vocabulary event that RPC does not know is
forwarded as an unhandled-event diagnostic with no RPC-state change; malformed
envelopes and malformed payload responses are likewise consumed once and
forwarded as diagnostics, never retried or mistaken for responses. This keeps
the decoder open to additive vocabulary without coupling it to the pending ws
amendment.
`dao.stream.rpc.ws` supplies that `:decode` and consumes the **whole**
`attach!` result including `:dao.stream/attachment`. R1 tested over bare ring
buffers passes and then breaks at R3 unless this seam is built in R1.

**Server state, explicitly:**

```clojure
{:request-cursor      <cursor>
 :pending-response    nil-or-<encoded>
 :pending-request-id  nil-or-<id>
 :pending-successor   nil-or-<cursor>
 :terminal            nil-or-<reason>}
```

`serve-once!` first retries `:pending-response`; while one exists it reads no
request. On `next` `:dao.stream/ok`, it retains the exact returned successor,
validates the request, and invokes a handler at most once. A valid request
produces a success response; a thrown handler or unknown operation produces an
error response. A malformed request never reaches a handler: when it has a
usable id it produces a correlated malformed-request error, and when no usable
id exists it emits a local diagnostic and advances directly to the successor.
Extra qualified keys are ignored. Thus a malformed element is consumed once
and cannot poison the cursor.

For a pending response, `append!` `:dao.stream/ok` advances
`:request-cursor` once to the retained exact successor and clears all pending
fields. `:dao.stream/full` changes neither cursor nor pending fields, so a later
step retries the identical encoded response without re-running the handler.
`:dao.stream/invalid-value`, `:dao.stream/closed`, and
`:dao.stream/transport-error` advance once to that successor, clear the pending
fields, record the terminal reason, and end the session; the response is
reported locally as undeliverable. For request `next`, `:dao.stream/blocked`
changes nothing; `:dao.stream/gap` records skipped requests and advances to the
exact recovery cursor; and `:dao.stream/end`,
`:dao.stream/cursor-mismatch`, `:dao.stream/invalid-cursor`, and
`:dao.stream/transport-error` record the terminal reason without changing the
cursor. These are exhaustive over the contract's `next` and `append!` outcomes.

**V1's `dao.stream.apply` is the sole envelope owner.** R1 mirrors and
requires it; neither R1 nor either RPC namespace defines competing keys. The
request is `{:dao.stream.apply/id id :dao.stream.apply/op op
:dao.stream.apply/args args}`. Success and error responses preserve that id
and carry exactly one of `:dao.stream.apply/ok` or
`:dao.stream.apply/error`; the latter is plain data with a qualified code
and message, never a host exception. An id must be present and non-nil, the op
must be a keyword, and args must be a vector. Predicates, constructors,
correlation-id representation and validation all belong to V1. This section is
the precise transition-algebra mirror that V1 must adopt; R1 consumes it.

**Not in the v2 layer:** `retry` and `dedup`, which exist for the lossy UDP
transport that v2 does not have. They stay on v1 with `dao.stream.rpc.udp`.

## The REPL driver

The first draft asserted that the REPL "already owns a polling loop." It does
not, and never did: `rpc-client/wait-for-response` owned it
(`rpc/client.cljc:77-111`) and the REPL consumed its Promise or Future
(`repl.cljc:526-546`, with the cljs main's re-prompt promise-chained at 903 and
the cljd main's at 1044). The three main loops are **line-event loops**, not
response-poll loops.

So the driver is a deliverable, not a caller convenience. `yin.repl.driver`
owns one step:

```clojure
;; Called once per externally driven tick; never loops on :blocked.
(defn repl-step [state now] …)
```

which is the **only owner of REPL and RPC state on every host**. It reads and
advances the input-medium cursor, evaluates eligible input, calls `request!`
and `poll!`, drains the output stream, advances any server sessions, publishes
completions, updates prompt state, and returns the next state. It never loops on
`blocked`. Input adapters have no access to this state.

Every host gets one composition-owned input ring buffer of capacity 1024 and a
cursor held only by `repl-step`. A line producer appends
`{:yin.repl.input/line <string>}` and returns; it does not evaluate, request,
poll, print, prompt, or mutate a completion. The ring buffer makes the handoff
thread-safe and explicit. A `gap` means typed lines were evicted: `repl-step`
prints a loss notice, resumes at the recovery cursor, and evaluates none of the
missing input. Other `next` outcomes follow the same total cursor discipline as
the output drain.

**Per host, because cadence is the runtime's** (DaoStream contract, *The IO Model*):

- **clj** — the main reader thread parks in `read-line` (`repl.cljc:856`) and
  only appends each returned line to the input medium. One separate owned
  poller thread carries the state value through serial `repl-step` calls on an
  interval, so neutral `/established` and remote results print without waiting for the
  user's next keystroke. The reader never reads or updates REPL/RPC state.
- **cljs (Node)** — the readline line handler only appends the line and returns.
  A single non-overlapping `setInterval` owns serial `repl-step` calls,
  evaluation, printing, and prompt discipline, replacing the promise chain at
  `repl.cljc:903`.
- **cljd** — the line handler likewise only appends and returns; one
  non-overlapping `Timer.periodic` owner calls `repl-step`, replacing the chain at
  `repl.cljc:1044`. A synchronous `(loop [] (poll!) (recur))` deadlocks the Dart
  event loop: ws IO never progresses, so `blocked` never clears.

**One step owner, one state.** Two tickers or any direct line-handler call to
`eval-input`, `request!`, or `poll!` violate the design and can allocate a
duplicate id, advance the wrong cursor, or deliver a completion to the wrong
consumer.

**`eval-input`'s contract changes and must be stated**: local evaluation returns
a value; remote evaluation returns immediately with a request id, and the result
is printed by the driver when it completes. Remote evaluation input typed while
a request is outstanding is queued, not evaluated — v1 has a clobber race there
(`repl.cljc:1044-1047`) that must not be copied. Local control commands
`disconnect`, `quit`, `help`, and `repl-state` bypass that queue so a stuck or
slow remote request cannot trap the operator.

The claim that host divergence disappears is **withdrawn**. It moves: out of the
RPC layer, which now has one shape everywhere, and into one small named driver
per host. That is the reduction this plan actually delivers.

## Decisions

**D2 — a reply travels on the connection's server-side handle. Settled.**
The accepted-connection handle is writer+closable with its writer surface on
that connection's ordered outbound path (WebSocket spec, *Surfaces*), so replies
are private by construction. The alternative — a per-conversation served stream
driven by `forward-step` — buys an outbound replay history that is worthless
until resumption is specified, and resumption is deferred. `forward-step` (4b)
is therefore genuinely unnecessary here.

**D3 — `/repl` names a service-lifetime stream. Settled now, not deferred.**
The first draft chose D2a and left D3 open, which does not work: a descriptor
names a server-hosted stream that exists independently of connections and that
the same descriptor reaches every time (WebSocket spec, *What the Descriptor
Names*), so with
nothing served the handshake must authoritatively disclaim `/repl` and *every*
`connect` deposits `:ws/not-found`.

So the endpoint `create!`s **one composition-owned ring buffer of capacity 1 at
start**,
registers it in the resolution table under `/repl`, and every client and
accepted-connection descriptor carries that stream's unchanged
`:dao.stream/identity` alongside endpoint-specific reachability. It carries no
ordinary outbound values in this slice — nothing is forwarded into it, which is composition policy and not a
spec violation. Closing it is the endpoint's `stop!`, which closes each
attachment with the ended-stream close code, and that is what makes R5's fourth
fact implementable at all. Because this slice appends no ordinary value to that
stream, capacity 1 cannot evict in a correct composition. Nothing reads this
identity-anchor stream, so no gap policy is claimed; a direct composition test
instead proves that ordinary append is absent and only owner `close!` changes
it.

Only the descriptor's transport-specific reachability key names wait for the
gate; `:dao.stream/identity` is already fixed. The shape is settled here.
The REPL wrapper detects an empty raw URL path and substitutes `/repl` before
the generic WebSocket canonicalizer runs; an explicit `/` remains `/`.

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

From [`yin.vm.implementation-plan.md`](./yin.vm.implementation-plan.md):

- **Through Phase V5** — `yin.vm.ast-walker` and its closure. It needs only
  the sibling stream plan's Phases 1 and 2.
- **V1 specifically gates this plan's R1**, because `dao.stream.apply` owns
  the request/response envelope and `dao.stream.rpc.client`/`.server` are
  built there, socket-free. R1 is therefore **blocked**, not merely sequenced —
  the earlier draft's "executable today" was wrong on two counts: no
  `dao.stream*` exists, and the envelope has an owner elsewhere.

From `dao.stream.implementation-plan.md`:

- **Phase 1 and Phase 2, completed** — the protocols, the result convention, and
  the ring buffer, including the conformance harness that Phase 1 defines and
  Phase 2 first runs. If R1 depends on a completed Phase 2 then it depends on
  the harness too; the earlier draft's exclusion of it was wrong.
- **Phase 4a, the ws transport, both ends**, on clj and cljs.
- **The cljd v2 ws transport**, which the sibling plan defers and **this plan
  owns**, over `dart:io`. It owes the same manifest and conformance evidence as
  the other two hosts.
- **Phase 3's descriptor/codec gate** and the wire gate: the descriptor key set
  (still open), and the wire contract (settled in `dao.stream.ws.md`,
  2026-09-03).

**Not prerequisites:** Phase 3's test-only local ring-buffer directory beyond
the descriptor/codec gate, 4b (dead under D2a), 4c (R4 builds the REPL's
own serving composition, which re-derives 4c's list minus the forwarder), and
Phase 5 (this plan is that proof).

**The three spec answers, settled (2026-09-03).** The amendments this plan
waited on are now in `dao.stream.ws.md`:

1. **Server-side attachment identity** — the contract authorizes
   transport-minted identities for handles no `attach!` produced, and the ws
   spec defines the minting rule; the acceptance offer carries the value
   under `:ws/attachment`.
2. **Accept notification** — acceptance is a bounded, acknowledged stream
   handoff: a fixed pool of handoff slots, each a capacity-one offer and
   acknowledgement pair holding at most one pending connection, the offer
   carrying the writer handle under `:ws/handle`, the wire `:ws/accept`
   frame sent only after the composition's acknowledgement. R4's serving
   composition consumes this handoff (see Phase R4).
3. **The wire contract's contents** — request-target presentation, the
   `:ws/accept`/`:ws/disclaim` first frame, close codes 4000/4002/4004, the
   Transit-JSON codec with its portable value domain, and decode-failure
   behaviour are specified in the Handshake and Elements and Serialization
   sections.

**Nothing here is executable today.** R1 is blocked on the VM plan's V1, R2 on
its V5, and both on the sibling stream plan's Phases 1 and 2 — none of which
exists: `src/cljc/dao/stream*` is absent from the tree. R3 through R5 are
additionally gated on Phase 4a of the sibling plan. The plan states this rather
than sequencing past it.

## Phase R1 — `dao.stream.rpc.ws`

The envelope, the client and the server are the VM plan's V1. What is left here
is the one piece that needs a transport.

- `ws.cljc`: the `:decode` that filters deposited envelopes by `:me`, advances
  past other attachments' events without treating them as responses, unwraps
  only `:ws/payload`, distinguishes reconnectable `:ws/closed` from terminal
  `:ws/ended`, preserves requests across survivable `:ws/error`, converts all
  known lifecycle and diagnostic kinds to the transport-neutral
  `:dao.stream.apply/…` vocabulary, and forwards unknown
  current-vocabulary events as non-terminal diagnostics.
- `init-client` from the whole `attach!` result, so `:dao.stream/attachment`
  becomes `:me`.
- Tested with hand-built envelopes over a ring buffer, so it needs no socket and
  can be written before R3.

Deliverable: envelope decode and client construction on clj, cljs and cljd,
against the V1 client with one shape on all three. Required tests: completions
publish exactly once, `:completed` is empty in the returned post-publication
state, its maximum size is bounded by one step's work budget, and IDs remain
monotonic after earlier completions have been cleared. Socket-free decoder
tests inject the sequences `:ws/error` then `:ws/closed`, `:ws/not-found` then
`:ws/closed`, and a duplicate post-terminal lifecycle event; they prove exact
neutral translation, one terminal completion, and no retained outstanding
request. Actual close-code and two-endpoint behavior belongs to the sibling
stream plan's Phase 4a wire-close conformance suite.

## Phase R2 — `yin.repl` and its driver, local only

No socket, no wire, no RPC.

- **The function-level inventory** of `yin/repl.cljc` first; then
  `yin.repl.core` and `yin.repl`.
- `make-vm` constructs a `yin.vm.ast-walker`, supplies `:make-stream` bound
  to the v2 ring buffer, and registers the v2 `stream` module. Per the VM
  plan's V7 it hands the VM **no program stream**: instead the shell creates
  the program ring buffer with a **declared capacity of 4096 elements**,
  retains its writer handle, builds the resolver and unary attacher beside
  that medium, attaches a `dao.stream.observer` through the composed
  descriptor-only entry, and stores `:program-stream`, `:observer`, and `:vm`
  separately in shell state. `eval-datoms` appends through the writer and
  drives `run-on-stream` with `engine/ready-for-ingress?`,
  `ast-walker/vm-load-program`, and the VM's runner; source and AST
  evaluation use direct `eval`. Reset and `(vm …)` selection rebuild the
  medium, descriptor, resolver, attacher, observer, and VM together, and the
  attachment capability is bound once per medium lifetime. The REPL is the
  composition that chooses the VM's transport; the VM requires none. Its one
  step owner prevents intentional producer overrun. A `gap` nevertheless
  means one or more program batches were never observed and is fatal to the
  current evaluation: the observer recovers its cursor on its own, but the
  shell reads the gap count around the round, reports the loss, and requires
  `(reset)` before accepting more evaluation, rather than resuming as if
  execution were complete. No telemetry stream is installed and the
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
- `yin.repl.driver` with `repl-step`, and the per-host tickers.
- The four `deps.edn` aliases and the `shadow-cljs.edn` build **land here**, not
  in R5, because R2's deliverable is stated in terms of them.

Deliverable: a working local REPL on all three hosts — `clj -M:clj-yin-repl`,
the `:yin-repl` node build, `clj -M:cljd-yin-repl` — requiring no v1
namespace.

## Phase R3 — The client side

- `connect` normalizes the URL to the canonical path defined by the WebSocket
  spec; only the REPL wrapper maps an empty URL path to `/repl`. It creates its
  deposit medium, mints a `:dao.stream/newest` cursor, composes the boundary,
  and only then calls `attach!`, receiving a handle at once. No promise on any
  host and no open event can race ahead of the cursor.
- The client boundary has one capacity-8192 traffic medium reused across
  reconnects, with at most one active attachment and demultiplexing by
  `:ws/attachment`.
- The RPC client is built from the ws handle, the already-minted cursor and
  medium, and the whole `attach!` result for `:me`.
- **`Connected to …` moves** to when the driver observes neutral
  `/established`. `/not-found` reports an authoritative disclaimer and is not
  retried; `/transport-error` reports a reachability failure that may succeed
  on retry. The WebSocket spellings are confined to the R1 decoder.
- `disconnect` is `close!`. Reattaching is `attach!` plus `rebind`, keeping the
  deposit cursor and taking the new attachment id.

## Phase R4 — The server side

- The endpoint creates its `/repl` service-lifetime stream at start (D3) and
  registers its capacity-1 handle in a host-owned resolution table. It accepts
  separate bind and advertised host/port configuration; bind defaults to
  `127.0.0.1`, and descriptors use the advertised values.
- The WebSocket boundary control medium is a capacity-1024 ring buffer. Its
  `:dao.stream/newest` cursor is minted and stored before listener bind begins;
  it carries pre-accept diagnostics and terminal events, never application
  payload.
- One request medium per accepted attachment, capacity 8192. After reading an
  offer, the server creates the medium, mints its `:dao.stream/newest` cursor,
  stores both in session state, and includes the writer plus admission
  declaration in the acknowledgement. Only then can `endpoint-step` send
  `:ws/accept` and enable payload delivery. One client's eviction pressure
  cannot create another client's request gap. The conforming REPL client sends
  at most one outstanding request. If a raw or defective peer nevertheless
  produces a request-medium `gap`, the server records it and closes that
  attachment; the resulting `/detached` transition reports the client's
  outstanding request lost instead of leaving it pending forever.
- The server driver reads the boundary control medium as well as each accepted
  attachment medium. Pre-accept terminal events retire pending offers from the
  control path; post-accept terminal events retire established sessions from
  their per-attachment path, so the acknowledgement race has no orphan state.
- **Acceptance handoff is consumed, not reinvented.** The composition
  supplies the endpoint's acceptance handoff as a fixed pool of 8 slots
  (configurable at `serve!`, minimum 1), per `dao.stream.ws.md`: each
  slot's offer and acknowledgement media are capacity-1 v2 ring buffers,
  both reader cursors are minted and stored before listener bind begins,
  and at most one pending connection occupies a slot — pool exhaustion is
  the bounded admission control the spec requires, not an error. The server
  driver polls the offer slots; on an offer it retains the writer handle under
  `:ws/handle` and the new request-medium reader and cursor in session state
  keyed by `:ws/attachment`, then appends the full acknowledgement specified in
  the WebSocket spec, including the request-medium writer and admission
  declaration, to that slot's acknowledgement medium. No
  session is served before its acknowledgement — the spec sends no wire
  `:ws/accept` and enables no value delivery before it. Slot exhaustion is
  admission control, not an error: the transport closes the new connection
  pre-acceptance and the client resolves `:ws/transport-error`.
  A matching but malformed acknowledgement releases the slot and closes the
  pending connection; a wrong-identity stale acknowledgement changes nothing.
- One server driver advances every session against a single serially-threaded
  REPL state (D4), running V1's `serve-once!` per session.
- **Serving lifecycle is composition-owned data.** The composition creates a
  lifecycle ring buffer (capacity 256), mints its `:dao.stream/newest` cursor,
  and stores both before `serve!` may begin binding. `serve!` returns
  immediately with an endpoint value containing that medium and cursor, the
  service handle, and host resources. Host
  callbacks only transform and deposit envelopes shaped
  `{:yin.repl.endpoint/event <kind> :yin.repl.endpoint/value <plain-data>}`.
  The fixed event set is `:bind-succeeded` (bound host and port),
  `:bind-failed` (qualified code and message), `:upgrade-failed` (plain request
  summary, qualified code and message), `:listener-error` (qualified code and
  message), and `:stopped` (reason). No host error object crosses the boundary.
  The one server driver owns the lifecycle cursor and is the only code that
  changes endpoint/REPL state. Unknown qualified envelope keys are ignored; an
  unknown event kind is surfaced as a diagnostic. A lifecycle `gap` is a fatal
  endpoint-observability failure and triggers shutdown.
- `stop!` initiates closing the service stream, listener, and attachments but
  does not claim completion. Closing the service stream causes client
  `:ws/ended` and the ended-stream close code; only the host close-completion
  callback deposits `:stopped`, and only the server driver consuming that event
  marks stop complete and releases the resolution-table entry. This vocabulary
  legitimately lives here because it describes R4's REPL serving composition,
  not the subordinate WebSocket transport; defining it here avoids inventing a
  fourth ws-spec amendment gate.
- **On cljd**, `HttpServer.bind` is asynchronous, so `serve!` returns before the
  port is bound and the result arrives as the lifecycle data above. Node's `ws`
  server fails the same way; a synchronous http-kit throw is caught, classified,
  and deposited as `:bind-failed` before `serve!` returns. In every host the
  lifecycle medium is the sole observation channel.
  v1's cljd `listen!` never sets `:socket-close-fn` (`ws.cljc:396-408`), so
  server-side `close!` never closes the socket — do not copy that.
- The server driver calls transport-owned `endpoint-step state now` once per
  tick before advancing accepted sessions. It is the sole owner of endpoint
  state, cadence, admission expiry, and stale-ack cleanup.

## Phase R5 — End to end

Per host, then across hosts.

`clj -M:clj-yin-repl --port 8080 --headless`; from a second process
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

`src/cljc/yin/vm/docs/yin.repl.md` is written here. The existing
`yin.repl.md` is left alone. The new document states what differs: `connect`
returns immediately and reports its outcome when known, `(vm :type)` offers
`:ast-walker` only, and there is no `(telemetry)` command.

## Host matrix

All three hosts, first class, every phase.

- **Declare the `append!` outcome subset per host, with reasons**, which is what
  the contract's Surfaces section asks. Transient `full` may not be
  detectable anywhere: `java.net.http sendText` buffers, Node's `ws.send`
  buffers, and Dart's `WebSocket.add` buffers, none exposing backpressure. If
  `full` is excluded by nature on a host, say so with the reason rather than
  inventing a signal.
- On cljd, `add` after close throws `StateError` synchronously and must be
  classified to `:dao.stream/closed`. The v2 codec is owned by
  `dao.stream.transit`; its cljd implementation may adapt algorithms from
  `src/cljd/dao/stream/transit.cljd` but must not require that legacy namespace.
- Full cljd namespace compilation gates each phase, per *Namespaces and files*.

## Boundary of this plan

> **Status (2026-09-16):** `yin.vm.v1-retirement.implementation-plan.md` did
> both things this boundary held back: its U2 built the v2 Flutter widget and
> its U6 deleted `yin/repl.cljc`, `runner.clj`, `flutter.cljd`, their tests,
> and the v1 `deps.edn` aliases and `shadow-cljs.edn` builds.

**Untouched implementations** — no edits, no deletions, no deprecation markers:
`dao.stream.cljc`
and everything under `src/cljc/dao/stream/`; `yin/repl.cljc`,
`src/clj/yin/repl/runner.clj`, `src/cljd/yin/repl/flutter.cljd`; their tests;
and every existing `deps.edn` alias and `shadow-cljs.edn` build. The two
build-configuration files receive only the additive entries declared above.
`dao.jing.remote`
keeps working on v1 and gets its own plan; it is the harder migration, since its
`call!` is synchronous-on-JVM and `jing/materialize!` and `jing/get` dispatch
through it as ordinary value-returning calls.

**Deliberately out of this slice:** telemetry in every form, per *The VM* —
which also removes the `ws://` sink that would have been a second ws client
proving nothing `connect` does not; the `:semantic`, `:register` and `:stack`
and `:space` evaluators, which follow in the VM plan; chain-forwarding through a
server's own remote (D4); the Flutter widget; `retry`, `dedup` and a v2 UDP
transport; ws-level resumption; flow control; and authentication — `--host
127.0.0.1` remains the only boundary, as in v1.

**Accepted operational limits:** a request already taken from its medium may
finish mutating the one shared shell after its client disconnects; reporting it
lost is conservative observation, not cancellation. A handler that never
returns stalls the serial server driver because V1 has no multi-tick handler
continuation. These are explicit consequences of D4 and the V1 handler shape,
not promises of cancellation or isolation. Without the deferred liveness
protocol, an accepted but silent session can remain allocated indefinitely;
the bounded handoff limits pending acceptance, not established-session count.

## End condition

Complete when, on each of clj, cljs (Node) and cljd, a v2 REPL server accepts a
connection from a second process, evaluates forms sent to it, and survives a
disconnect and reattach — with `yin.repl` requiring no v1 namespace, which
running on `yin.vm` makes true rather than aspirational. R5's four facts plus
the cross-host pair are the test.

Coexistence is the expected end state. Both REPLs ship, both alias sets work,
and `dao.stream.rpc.*` keeps serving `dao.jing.remote`. Deleting v1 is the
stream plan's end condition, when its last consumer has migrated — of which this
is the first.
