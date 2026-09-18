# dao.stream v1 retirement — the transport item

Status: implementation plan for retiring legacy `dao.stream` — the v1
protocols in `src/cljc/dao/stream.cljc` and every v1 transport
(`dao.stream.{apply, file, file-input-stream, file-output-stream, http, link,
ringbuffer, udp, ws}`, `dao.stream.rpc.*`, the host `transit`/`udp`/`ws`
siblings) — by giving every live consumer a v2 shape, deleting every dead one,
and then deleting v1 in one change. It is the last gate on the rename wave that
[`dao.stream.md`](./dao.stream.md) (*The v2 namespace is transient*),
[`yin.vm.v1-retirement.implementation-plan.md`](./yin.vm.v1-retirement.implementation-plan.md)
D5, and [`dao.runtime.implementation-plan.md`](./dao.runtime.implementation-plan.md)
R4 all defer to it: `dao.stream`, `dao.runtime`, `dao.await`,
`yin.vm`, `yin.repl` lose their suffix together, "when the last consumer
has migrated." Subordinate to `dao.stream.md`, which is the authority on what
any v1 replacement must uphold.

Drafted 2026-09-17, architect r1, against a `grep`/`git` sweep of the tree on
the `dao.stream-redesign-v2` branch at `bb765bf` (the R4 close-out). The
revision history at the end records what each round changed.

## The problem and context

Three retirement plans have run in sequence: the v1 VM's experimental models
(2026-09-10), the v1 VM and REPL lineage (2026-09-16), and the v1 scheduler
(R4, 2026-09-17). Each was mostly deletion: a v2 twin existed for nearly every
consumer, and the plan's job was to find the ones that did not and build them.
This plan is different in kind, and the brief says so: **the two consumers
that matter — `dao.postgraphics.terminal` and `dao.gui.event` — have no v2
twin and are not renames.** Each is built on a v1 mechanism the v2 contract
lists under *Explicitly Absent*: the terminal on waiter registration and
synchronous wake-on-append, the event interpreter on caller-fabricated
`{:position n}` cursors and a reject-mode ring buffer that returns `full`.
Porting either is design work against `dao.stream.md`, and the plan treats it
that way.

The rest of the census is smaller than the brief feared, for a reason the
prior plans established: once the v1 VM went, a good deal of what still
*requires* v1 `dao.stream` no longer has anything reading it. `yin.io`'s file
handlers register into a `yin.module` registry that no evaluator consults.
`agent.tools` has one consumer, `agent.tzu`, which has none. The telemetry
viewer polls servers deleted on 2026-09-16. The WebSocket demo pair and the
v1 continuation transport each have a v2 twin already in the tree. Those are
deletions, not migrations, and most can land today.

## What is verified and what is judged

Everything below is a verified fact from the 2026-09-17 sweep unless marked
**[J]** (judgment call) or **[B]** (a claim in the brief that the sweep
corrected). Line numbers are as of the sweep.

### What the brief got right

- **The v1 implementation is exactly the 21 files the brief listed**, 4,354
  lines: `stream.cljc` (293) and its protocols `IDaoStreamReader`,
  `IDaoStreamWriter`, `IDaoStreamBound`, `IDaoStreamWaitable`,
  `IDaoStreamDrainable`, the `open!` multimethod and `defopen` macro
  (`stream.cljc:36-149`); the nine `.cljc` transports; six `rpc/*` files;
  `src/clj/dao/stream/transit.clj`, `src/cljd/dao/stream/{transit,udp,ws}.cljd`,
  `src/cljs/dao/stream/transit.cljs`. Nothing under `src/` outside that tree
  requires `dao.stream.{link, udp, rpc.*}` or the host transit files — those
  are reached only by the v1 transports themselves and by their tests.
- **`dao.space` and `dao.jing` are done.** Every `dao.stream` token in
  `dao.space.{index, query, schema, transactor}`, `dao.jing`, `dao.jing.remote`,
  `dao.data` is a `:dao.stream/…` outcome keyword or docstring prose; their
  requires name `dao.stream` or no stream namespace. `dao.jing.file`
  states in its own docstring that "no dao.stream namespace is required"
  (`file.cljc:21-24`). Not scheduled.
- **`telemetry_viewer.cljs` is dead** — confirmed and expanded under D1.
- **`agent.tzu` has no caller** — confirmed and expanded under D2.
- **The `dao.postgraphics.{v2,v3,v4}.md` documents are a false lead.** They
  specify the 3D vocabulary, precision pass, and textures/lighting of the
  graphics *language*; they mention `dao.stream` zero, one and one times, in
  passing. Nothing in them describes a stream migration. Not scheduled.
- **The v2 demo surfaces exist where the brief hoped**: `dao.stream.ws.browser`
  (U4 of the VM plan) and `yin_repl.cljs` are in the tree, and
  `datomworld.demo.continuation-transport` is a complete v2 twin of
  `datomworld.continuation-transport` whose docstring names the three v1
  idioms it drops (`continuation_transport.cljc:2-24`).

### What the brief got wrong

**[B] `yin.io.*` is not a migration target; it is dead code with a dead
registry.** The brief listed `yin.io.{file, file-input-stream,
file-output-stream}` as consumers with "no v2 twin yet". They are effect
handlers: each `ds/open!`s a v1 file transport and registers itself into
`yin.module`'s two `defonce` atoms at load time (`file.cljc:20,36`,
`file_input_stream.cljc:18,32`, `file_output_stream.cljc:18,29`). The only
reader of that registry was the v1 VM's `handle-effect`, deleted on
2026-09-16. Today `module/get-effect-handler` and `module/resolve-module` are
called from `test/yin/module_test.cljc` and nowhere else in `src/`; the v2 VM
carries its own registry *value* (`yin.vm.module`, "nothing in this
namespace runs at load time, and nothing registers itself") and registers a
`stream` module explicitly with no `io` module at all
(`v2/module.cljc:125-135`). `yin.stream` (22 lines) registers a `'yin.stream`
module into the same dead registry, and v1 `dao.stream.ringbuffer` registers
a `'stream` module there too (`ringbuffer.cljc:458`). So the whole
`yin.module` family — `yin.module`, `yin.stream`, the three `yin.io` handlers,
`module_test` — is a v1 VM artifact that outlived its VM. The VM plan
explicitly left `yin.module` to "the transport item" (`:197-198`). Decision
D3.

**[B] `agent.tools` has no consumer but `agent.tzu`.** The brief listed it
as its own migration target with "no design doc found under that name". Its
design doc is `agent.tzu.dao.stream.md` (it is section 3 there), and its only
`src/` require is `agent/tzu.cljc:5`. If `agent.tzu` deletes, `agent.tools`
deletes with it; if `agent.tzu` were kept, `agent.tools` is the larger of the
two ports. Decision D2 takes them together.

**Two files the brief did not list change under this plan without requiring
v1.** `src/cljd/dao/postgraphics/flutter.cljd:361` and
`src/cljs/dao/postgraphics/web.cljs:82` call `terminal/bind-stream!`, whose
whole mechanism is v1's `register-reader-waiter!` (`terminal.cljc:106-112`)
and the `:woke` list `put-frame!` resumes synchronously (`:48-57`). A
namespace grep misses them; they are the two host halves of the terminal port
(D4) and are on the census.

**The WebSocket demo pair and the v1 continuation transport are superseded,
not unmigrated.** `datomworld.continuation-transport` has exactly one
consumer, its own test; the live continuation demo already runs on the v2
twin (`continuation_stream.cljs:38`, `continuation_handoff_test.cljc:6`).
`ws_demo_server.clj` and `ws_client_demo.cljs` demonstrate v1 `dao.stream.apply`
over v1 `dao.stream.ws`; the same demonstration exists on v2 as the R5
cross-host slice (`test/dao/stream/slice_peer.cljc`, `slice_test.{clj,cljs,cljd}`,
built by `bb build:yin-repl-peer`) and as `yin.repl --port --headless`
with the browser client. Decision D6.

**Config and launch surfaces the brief did not name**: the `:bench`,
`:ws-client-demo` and `:telemetry-viewer` shadow builds (`shadow-cljs.edn:22-50`),
the `:atzu` alias (`deps.edn:85`), `bin/{run-ws-demo, start-ws-server,
run-ws-client}.sh`, `public/telemetry-viewer.html`, `docs/ws-demo.md`,
`README.md:156`, and `src/cljc/agent/{llm-configuration.md, env.example.sh}`.

**Test consumers outside `test/dao/stream*`**: `test/dao/gui/event/bind_test.cljc`
(v1 ring buffers, `drain-one!` to free slots — `:12-13,37-41`),
`test/dao/postgraphics/terminal_test.cljc`, `test/dao/postgraphics/web_test.cljs`,
`test/agent/{tools,tzu}_test.cljc`, `test/datomworld/continuation_transport_test.cljc`,
`test/datomworld/demo/artifact_stream_test.cljs` (14 lines asserting v1
ring-buffer eviction), `test/yin/module_test.cljc`, and
`test/dao/test_utils.cljc`, which R4 left with no user: its
`make-non-waitable-stream` is named only in three v2 driver-test docstrings
as the thing they replaced.

**A non-hit that will trip a careless grep**: `yang.clojure` and `yang.python`
compile `dao.stream.apply/call` forms to the `:dao.stream.apply/call` AST node
(`clojure.cljc:140,200-211,319-320`, `python.cljc:378-414`), and the v2 VM
dispatches on that node (`v2.cljc:260,452-453,543,599`, `ast_walker.cljc:383`,
`semantic.cljc:509`, `linearize.cljc:118,237`). That is AST vocabulary shared
by v1 and v2, not a namespace require. It stays, and Phase 0's sweep excludes
it by name.

## Consumer census

Every file that requires a v1 `dao.stream*` namespace, launches or serves
one, consumes a v1-only mechanism through another consumer, or asserts that
a deleted thing exists — grouped by disposition. Roughly eighty files; the
brief named about forty.

### Built new (v2 shapes)

| file | replaces | unit |
|---|---|---|
| `src/cljc/dao/postgraphics/terminal.cljc` (rewritten in place, see D4) | v1 `bind-stream!`/`put-frame!` on waiters and `:woke` | U3 |
| `test/dao/postgraphics/terminal_test.cljc` (rewritten) | v1 fixtures and the wake-on-put assertions | U3 |
| a scripted-handle fixture for `full`/`transport-error` in `test/dao/gui/event/` (name per implementer; the runtime plan's *Fixtures* decision is the precedent) | `drain-one!`-based slot freeing in `bind_test` | U4 |

### Migrated to keep working without v1

| file | change | unit |
|---|---|---|
| `src/cljd/dao/postgraphics/flutter.cljd` | `:361-381` `bind-stream!` → the D4 binding plus a ticker that owns `step`; `put-frame!` alias (`:313`) keeps its name over `append!` | U3 |
| `src/cljs/dao/postgraphics/web.cljs` | `:70-92` same; `:15` alias; `:102` docstring | U3 |
| `src/cljs/dao/postgraphics/web/gpu.cljs:545` | alias only | U3 |
| `test/dao/postgraphics/web_test.cljs` | `:5-6,11,102-107` v2 ring buffer and minted cursors | U3 |
| `docs/design/dao.postgraphics.terminal.md` | `:32-35,66,150-175` "takes a `dao.stream` cursor" → the D4 binding and step; a *Cadence* paragraph | U3 |
| `src/cljc/dao/gui/event.cljc` | `:18` require; `:738-741` `full` by outcome map; `:749` `close!`; `:772` cursor minted, not `{:position 0}`; `:788-793` outcome-map dispatch and the gap's recovery cursor returned; `:839` initial cursor `nil` until first `advance`; `recover-input-gap` (`:796-812`) takes the recovery cursor — D5 | U4 |
| `test/dao/gui/event/bind_test.cljc` | `:12-13` requires; `streams` over `ringbuffer/create!`; `drain` over minted cursors; `consume-count` replaced by scripted handles for the park cases | U4 |
| `docs/design/dao.gui.event.md` | `:5-6,25,154-180,1896-1925` — D5's prose: outcome maps, minted cursors, "refuses or evicts and reports" in place of "reject rather than `:evict-oldest`" | U4 |
| `src/cljc/datomworld/demo/earth_moon_runner.cljc:9-10,15-18` | `ringbuffer/create!` with `capacity-key 4`; `put-frame!` unchanged in shape | U5 |
| `src/cljc/datomworld/demo/voxel_runner.cljc:7-8,12-15` | same | U5 |
| `src/cljs/datomworld/demo/solar_system.cljs:3-5,11-14` | same | U5 |
| `src/cljd/datomworld/demo/solar_system.cljd:7-8,13-16` | same | U5 |
| `src/cljd/datomworld/demo/dao_gui.cljd:5-6,10-13` | same (capacity 8) | U5 |
| `src/cljd/datomworld/demo/postgraphics.cljd:7-8,104-106` | same | U5 |
| `src/cljs/datomworld/demo/artifact.cljs` | `:5-6` requires; `:13-16,36-39,49-51,66-73` five ring buffers → `create!`; `:88-89,111-115` `rb/tail-position` fabrications → the recovery cursor D5 returns; `:99-116` output drains over minted cursors and outcome maps; `:129` append outcome — D6 | U5 |
| `src/cljd/datomworld/demo/artifact.cljd` | `:7-8,13-16,35-37,47-49,60-67,76-94` same, without the gap branch it never had | U5 |
| `src/cljs/datomworld/demo.cljs` | `:13` require, `:50-53` commented card, `:71,86,218` telemetry route and branch — deleted with D1 | U1 |
| `shadow-cljs.edn` | delete `:bench` (`:22-24`), `:ws-client-demo` (`:38-40`), `:telemetry-viewer` (`:42-50`) | U1 |
| `deps.edn:85` | delete `:atzu` | U2 |
| `README.md:156` | drop the `agent.tzu` launch line | U2 |

### Deleted with their dependency

| file | requires | why deletion, not migration | unit |
|---|---|---|---|
| `src/cljs/yin/vm/telemetry_viewer.cljs` (329), `test/yin/vm/telemetry_viewer_test.cljs`, `public/telemetry-viewer.html` | v1 `dao.stream`, `apply`, `ws` | D1: its servers were deleted 2026-09-16; already delisted from the picker | U1 |
| `src/cljc/datomworld/continuation_transport.cljc` (99), `test/datomworld/continuation_transport_test.cljc` | v1 ring buffer | twin `demo/continuation_transport.cljc` is the live demo's transport; no other consumer | U1 |
| `src/clj/datomworld/ws_demo_server.clj`, `src/cljs/datomworld/ws_client_demo.cljs`, `bin/{run-ws-demo, start-ws-server, run-ws-client}.sh`, `docs/ws-demo.md` | v1 `apply` over v1 `ws` | D6: the R5 slice and `yin.repl` demonstrate the same thing on v2 | U1 |
| `test/dao/test_utils.cljc` | v1 `dao.stream` | orphaned by R4 | U1 |
| `test/datomworld/demo/artifact_stream_test.cljs` | v1 ring buffer | asserts v1 eviction semantics; `test/dao/stream/ringbuffer_test.cljc` covers the v2 ones | U1 |
| `test/dao/stream_bench.cljc` | v1 `dao.stream` | orphan bench of a deleted implementation; `test/bench/yin_vm_bench.cljc` is the live bench | U1 |
| `src/cljc/agent/tzu.cljc` (397), `src/cljc/agent/tools.cljc` (236), `test/agent/{tzu,tools}_test.cljc`, `src/cljc/agent/{llm-configuration.md, env.example.sh}` | v1 `http` via blocking `take!!`, v1 `ringbuffer`, `file-*-stream` | **[J — owner decision]** D2 | U2 |
| `src/cljc/yin/module.cljc` (114), `src/cljc/yin/stream.cljc` (22), `src/cljc/yin/io/{file, file_input_stream, file_output_stream}.cljc`, `test/yin/module_test.cljc` | v1 `file*` transports; a registry nothing reads | **[J — owner decision]** D3 | U2 |
| `src/cljc/dao/stream.cljc` and the twenty transport files named in the header | — | the implementation itself; `dao.stream/*` is the twin | U6 |
| `test/dao/stream_test.cljc`, `test/dao/stream/{apply, file, http, link, rpc, transit, ws}_test.cljc`, `test/dao/stream/{file_input_stream, file_output_stream}_test.clj`, `test/dao/stream/rpc/retry_dedup_test.cljc` (2,058 lines) | v1 | contracts of deleted files; `test/dao/stream/**` (19 files) is the twin suite | U6 |

### Unchanged, named so the reader can check

- `yang.clojure`, `yang.python`, `yin.vm.*`: the `:dao.stream.apply/call`
  AST node, above.
- `dao.space.*`, `dao.jing.*`, `dao.data`, `dao.runtime`, `dao.await`,
  `yin.repl.*`, `yin.vm.*`, every `*_v2` demo: `:dao.stream/…`
  keywords only.
- `src/cljc/datomworld/demo/artifact_runner.cljc`: requires `dao.gui.event`
  and no stream namespace; `advance-until-progress` reads only `:status`
  values, which D5 keeps.
- `src/cljd/datomworld/demo/{earth_moon, voxel}.cljd`, `src/cljs/datomworld/demo/{earth_moon, voxel}.cljs`:
  hand `runner/frame-stream` to the widget and never touch a stream operation.
- `docs/design/dao.stream.ws.md`: already the v2 transport's specification;
  `:640` is its only `connect!` mention and is v2's.
- `docs/design/dao.stream.discovery.md`: a proposal that names v1's `open!`
  as its baseline (`:7-8`); status note in Phase 2, not a rewrite.

## Decisions

### D1 — the telemetry viewer is deleted, not migrated [J]

Verified: it requires v1 `dao.stream`, `dao.stream.apply` and `dao.stream.ws`
(`telemetry_viewer.cljs:3-5`), polls `{:position 0}` cursors (`:18-19`), and
`connect!` opens two v1 sockets to ports 8090 and 8091 (`:183-184`) — the
REPL and telemetry sinks that `yin.vm.v1-retirement` U6 deleted with
`src/clj/yin/vm/telemetry_server/jvm.clj` and its Node twin. Nothing in the
tree serves either port on the v1 wire. Its picker card has been commented
out since before that plan (`demo.cljs:50-53`); `telemetry-ui-design.md`
already carries the 2026-09-16 status note saying "nothing serves it".
Migrating a client of nothing onto v2 would produce a v2 client of nothing:
v2 telemetry emission does not exist (VM plan D2) and is owed to its own
plan, and when that plan builds a viewer it will build it against the v2
wire and `yin.repl`'s served stream, not port this file.

Deletion set: the two `.cljs` files, `public/telemetry-viewer.html`, the
`:telemetry-viewer` shadow build, the `demo.cljs` require, card, hash route
and render branch. `telemetry-ui-design.md`'s status note gains one line
saying the viewer is gone too.

### D2 — `agent.tzu` and `agent.tools` are deleted [Owner: delete, 2026-09-17]

**Answered.** The owner chose deletion, the recommendation below, over
keeping a v2-seed subset of `agent.tools`' stream tools with no consumer
until the harness exists. U2 may proceed on this half.

Verified: `agent.tzu` is required by nothing under `src/` or `bin/`; it is
reachable only through the `:atzu` alias (`deps.edn:85`), its own `-main`
REPL (`tzu.cljc:359-392`), and `test/agent/tzu_test.cljc`. Its last
substantive change was 2026-06-01 (`b9a532c`); the two later touches were
tree-wide renames. `agent.tools` is required only by `tzu.cljc:5` and its own
test. The orchestrator's standing note that this module is unused is
confirmed.

What migrating would mean, so the size is not understated:

- **`chat-completion` is blocking IO.** `(ds/take!! (ds/open! {:dao.stream/type
  :http …}))` (`tzu.cljc:68-73`) parks the JVM thread until the response
  arrives; `take!!` is JVM-only by construction (`stream.cljc:272-293`). v2
  has no HTTP transport and, under *The IO Model*, could not offer a blocking
  one. The honest v2 shape is the one `agent.tzu.yin.vm.md` already
  describes: the LLM call becomes a boundary adapter depositing onto a
  stream, and the agent loop becomes a Yin program that parks on `next`.
  That is the agent-harness workload `agent.harness.md:310` names as a
  "candidate first workload" — a plan of its own, not a port of this file.
- **`agent.tools`' stream tools expose v1 cursor internals to the LLM.**
  `stream_read` takes an integer `position` and returns `:next-position`
  (`tools.cljc:39-50,122-131`); the model does cursor arithmetic. v2 cursors
  are opaque and transport-owned (*Cursors*: "consumers never construct
  cursor internals or fabricate positions"). The tool surface would have to
  hold cursors server-side and hand the model opaque tokens — a redesign of
  the tool vocabulary, its JSON schema, and every test that asserts on
  positions.
- **`file_read`, `file_write`, `http_fetch`** open v1 `file-input-stream`,
  `file-output-stream` and `http` transports (`:170,198,215-219`), none of
  which has a v2 twin. Reading a whole file into a string is not a stream
  operation and would be plain host IO in any rewrite.

So the port is three new designs (an HTTP boundary adapter, an opaque-cursor
tool protocol, host file IO) for a module with no caller. **Default:** delete
`agent.tzu`, `agent.tools`, both tests, `src/cljc/agent/{llm-configuration.md,
env.example.sh}`, the `:atzu` alias, and `README.md:156`; status notes on
`agent.tzu.md`, `agent.tzu.dao.stream.md`, `agent.tzu.yin.vm.md` saying the
code is deleted and the Yin-native agent is owed to the harness plan.
**Alternative:** keep `agent.tools`' stream tools only, on v2 with opaque
cursor tokens and no file/http tools, as a seed for the harness — a small
unit, but one with no consumer until the harness exists, which is the
"deliverable by silence" the runtime plan refused to invent.

### D3 — the `yin.module` family is deleted; no v2 file transport is built here [Owner: delete, 2026-09-17]

**Answered.** The owner chose deletion, the recommendation below, over
designing a v2 `io` module and file transport now. This retires the
capability (Yin programs opening files through `(yin.io/file …)` effects)
with no v2 replacement owed by this plan — a future `io` module, if
wanted, is its own plan against a v2 file transport nobody has designed.
U2 may proceed on this half.

Verified in *What the brief got wrong*: the registry `yin.io.*` and
`yin.stream` write into has no reader since the v1 VM went; the v2 VM's
registry is a value the composition supplies, with a `stream` module and no
`io` module. `dao.stream.file.md` is the v1 design of the live-tail file
transport `yin.io.file` opens; it names `yin.io.file-output-stream` and the
`register-module!` calls as its integration (`:302-307,417`). v2 has no
file transport. What v2 has instead is `dao.stream.memory-log` for the
complete-history local log and `dao.jing.file` for durability, and
`dao.jing.file`'s own docstring is explicit that "the durable log is not a
stream". A v2 file *transport* is therefore not a gap this plan is filling;
it is a transport nobody has designed and no consumer asks for.

**Default:** delete `yin.module`, `yin.stream`, the three `yin.io` handlers,
`test/yin/module_test.cljc`; status note on `dao.stream.file.md` (v1 design,
implementation deleted, no v2 twin owed by this plan). **Alternative:** keep
the three `yin.io` namespaces as prose-only placeholders — rejected as
written, because a namespace that registers into nothing is exactly the
load-time side effect `yin.vm.module` was written to remove. If the owner
wants file IO for Yin programs, the shape is an `io` module in
`yin.vm.module` whose effect handler opens a **v2** file transport; both
halves are new, and they are a plan of their own (Boundary).

### D4 — the terminal becomes a step-driven binding; the host owns cadence [J]

**What `terminal.cljc` does today.** `put-frame!` (`:48-57`) appends and then
*synchronously invokes* every `:resume` in the returned `:woke` list — the
producer's thread runs the consumer's validate-and-present. `bind-stream!`
(`:60-122`) reads from `{:position 0}` until `:blocked`, then calls
`ds/register-reader-waiter!` with a `resume` closure, so the next
`put-frame!` wakes it; on `:daostream/gap` it emits a `frame-skipped` signal
and fabricates `(update cursor :position inc)`. Every one of those is on the
v2 contract's absent list: waiter registration (no readiness extension),
callback invocation from inside an operation (*The IO Model*), fabricated
positions (*Cursors*). The two hosts wrap this: `flutter.cljd:361-381`
hands `bind-stream!` a `present-frame!` that resets an atom and bumps a
sequence the `:watch` repaints on; `web.cljs:82-92` hands it one that lowers
and submits to the canvas.

**What the contract leaves as the only shape.** The consumer holds a cursor
and calls `next` when *it* chooses; "cadence belongs to the runtime driving
the interpreters" (*What it costs*). In a Flutter widget or a browser
component, the thing that drives is a ticker. The precedent is the VM plan's
D1: `yin.repl.flutter` owns one `Timer.periodic` that is the only caller
of `embed/step`. The terminal is the same problem one layer down.

**Disposition.** `dao.postgraphics.terminal` is rewritten in place — the
namespace keeps its name and its signal vocabulary
(`reset-signal`, `rejection-signal`, `frame-skipped-signal`,
`protocol-error-signal`, `:dao.terminal/*` kinds are all data and all
survive) — with this surface:

- `(put-frame! frame-handle frame)` → `(stream/append! frame-handle frame)`,
  returning the outcome map. It wakes nothing. Every producer in the tree
  already ignores or `=`-checks the return, so the change is the return's
  shape only.
- `(bind frame-handle {:validate-frame! :present-frame! :signal-handle
  :generation-id :generation-id-fn :on-error})` → a binding **value**
  `{:frame-handle h :cursor c :generation-id g :closed? false}` with the
  cursor minted at `:dao.stream/newest` — the terminal shows what arrives
  after it binds, which is what `{:position 0}` on a fresh capacity-4
  buffer meant in practice — and the reset signal appended to the signal
  handle. No callback is registered anywhere.
- `(step binding)` → `{:binding binding' :status s}` with `s` one of
  `:presented`, `:rejected`, `:blocked`, `:end`, `:gap`, `:error`, `:closed`.
  One `next` per call, so one frame per tick: on `ok` validate and present
  (rejection appends a `rejection-signal` and calls `on-error`); on `gap`
  append `frame-skipped-signal` and **adopt the recovery cursor the outcome
  carries** (the contract's promise to a kept cursor; no `inc`); on `end`
  or a terminal outcome append `protocol-error-signal` and stop. Hosts that
  want to drain a burst call `step` until `:blocked`; a `step-until-blocked`
  helper with a bound is fine and is the same one-step-owner shape as
  `dao.gui.event/advance`.
- `(close binding)` marks closed; nothing to unregister.
- The signal handle is optional as today and is any v2 writer; its
  `append!` outcome is not inspected (a full or closed signal lane drops the
  signal, which is what v1 did by accident).

Hosts: `flutter.cljd` owns one `Timer.periodic` per widget at the frame
interval the demos already tick (16 ms), calling `step` until `:blocked`,
cancelled on dispose; `web.cljs` uses `requestAnimationFrame` or
`setInterval` the same way, cancelled on unmount. Latency cost: at most one
tick between append and present, on hosts that already render on ticks.

**Not adopted:** `dao.stream.observer/run-on-stream`. It is the right
loop for VM-shaped consumers (attach capability, descriptor, batch
semantics, `ready?`/`load`/`run`); the terminal holds a handle it was given
and reads one value at a time. Composing the observer here would import a
descriptor and an attacher the demos have no reason to construct.

**Why not keep the v1 shape and hide the ticker inside `put-frame!`.** A
producer that steps the consumer after appending is the callback inversion
with a different name, and it makes `put-frame!` non-total the moment a
consumer throws. The whole reason for the reader-surface rule is that the
producer does not know who is listening.

### D5 — `dao.gui.event` keeps its driver shape and adopts the contract's data [J]

**Smaller than it looks, in the code.** `dao.gui.event` is 839 lines, and the
binding driver was already written to the v2 discipline: "creates no ambient
singleton, host thread, callback, or waiter registration" (`:816-821`),
`advance` reads at most one input per call and retains the cursor as a value
(`:753-793`). Its v1 dependence is confined to `flush-pending` (`:738-741`,
`{:result :full}`), `close-outputs` (`:749`), `advance`'s read (`:772`,
`{:position 0}`; `:788-793`, bare keywords and `:daostream/gap`), and
`bind`'s initial cursor (`:839`). The 11 test files under
`test/dao/gui/event/` drive the *reducer* through `step` and `u/…` fixtures;
only `bind_test` touches a stream.

**Larger than it looks, in the specification.** `dao.gui.event.md:1896-1913`
says "a conforming canonical runtime-input DaoStream uses reject/backpressure
rather than `:evict-oldest`", and `:1914-1925` specifies the parked interval
on `{:result :full}`. The v2 ring buffer is evict-oldest and never returns
`full` — and the stream plan decided that a reject-mode buffer with no
destructive take would be full forever, so it is not a deferred variant
(runtime plan, *Divergence register*, row 6). `bind_test` frees slots with
`drain-one!` (`:37-41`) to exercise parking, which no v2 transport offers.

**Disposition.**

- `advance` dispatches on `:dao.stream/outcome`: `ok` as today; `blocked`,
  `end` as today; `gap` returns `{:status :input-gap :recovery-cursor c}`
  **with the cursor the outcome carried**, still without advancing the
  binding's own cursor — the spec's rule that a bare gap cannot construct the
  input-loss envelope is unchanged, and the caller now has the cursor it
  needs rather than fabricating one from `rb/tail-position`. Terminal
  outcomes (`cursor-mismatch`, `invalid-cursor`, `transport-error`) return
  a new `:status :transport-error` rather than falling into `:blocked`
  (`:793`'s `:else`), because a binding that retries a mismatched cursor
  forever is the spin the runtime plan's classifier forbids.
- `flush-pending` parks on `:dao.stream/full` exactly as it parks on
  `{:result :full}` today, and treats `closed`, `invalid-value` and
  `transport-error` on an output as the *output* being gone: the value is
  dropped from pending with one diagnostic, not retried. The park mechanism
  stays because the contract permits transports that return `full`
  (`append!` table) and a real dispatch lane may be one — a WebSocket
  outbound path can refuse. What changes is the claim that an in-process
  ring buffer is such a transport.
- `bind` accepts `:cursor` and otherwise mints `:dao.stream/oldest` on the
  first `advance` (so a binding created before the input handle has any
  value still observes from its origin — the origin-cursor rule).
  `recover-input-gap` takes the recovery cursor or any cursor the host
  minted; it never receives a `{:position n}` map.
- `bind_test`: ring buffers via `ringbuffer/create!`; the parked-interval
  cases over a scripted handle that answers `full` for `n` appends and `ok`
  after — the runtime plan's fixture rule — so the tests become *stronger*
  (they no longer depend on a drain the spec never promised).
- `dao.gui.event.md`: the *Binding Contract* paragraph (`:154-180`) restates
  the outcomes as maps; *Transport, Coalescing, And Backpressure*
  (`:1896-1925`) is rewritten to say a conforming input stream **either
  refuses (`full`) and the binding parks, or evicts and reports `gap`, and
  the binding's `:input-gap` plus `recover-input-gap` with a
  `:dao.terminal/input-loss` envelope is the recovery path** — which is
  precisely what `artifact.cljs:81-96` already does in production. The
  sentence preferring reject to evict-oldest goes.

### D6 — the demos move onto v2 ring buffers; two demo surfaces are deleted as superseded [J]

Every remaining demo stream is an in-process ring buffer: frame streams of
capacity 4 or 8 evict-oldest, the artifact demos' runtime-input (1024),
output (64) and signal (32, **`:reject`**) buffers. All become
`ringbuffer/create!` with `capacity-key`. The two `:reject` buffers become
evict-oldest, because that is the only v2 ring buffer; for a 32-slot signal
lane nothing reads in a tight loop, eviction over refusal changes nothing an
operator can see. The artifact demos' fabricated `{:position (rb/tail-position s)}`
cursors (`artifact.cljs:88-89,115`) become the recovery cursor D5 returns or
a freshly minted `:newest`.

Deleted rather than migrated: `datomworld.continuation-transport` (v2 twin
in use, one consumer which is its own test) and the WebSocket demo pair with
its scripts and `docs/ws-demo.md`. The latter demonstrated "CLJS in Node
calling CLJ over a socket"; the R5 slice test does exactly that across all
three hosts on the v2 wire, and `yin.repl --port --headless` with the
browser client is the same demonstration a visitor can run. Rebuilding a
third copy on `dao.stream.apply` would be a fourth v2 RPC demo.

### D7 — one deletion commit, then one rename commit; the wire-keyword question is the owner's [second half answered: clean break, 2026-09-17]

U1 lands today as independent commits. U2 is unblocked: the owner answered
D2 and D3, both deletion. U3 and U4 each land as their own commit with v1
still present and every lane green. U5 lands per demo pair after both. U6
is one atomic commit: the delete list, the config edits, the prose.
Splitting U6 would leave a commit where a test requires a deleted
namespace.

U7 — the rename wave — is its own commit after U6, and it is bigger than
"only requires change" (R4's phrasing) suggests. Ninety-nine files under
`src/` and `test/` require `dao.stream`; sixty-four require one of the
other four. That part is mechanical. What is not mechanical: the v2 apply
and RPC protocols put the namespace in their **wire keywords**
(`:dao.stream.apply/id`, `/op`, `/args`, `/ok`, `/error`, `/request`,
`/response` — `apply.cljc:15-21,119-120`; `:dao.stream.rpc/*` —
`rpc.cljc:21-25`; `:dao.stream.rpc.ws/malformed-envelope`), and the ws
transport names its subprotocol `"dao.stream.transit-json"` (`ws.cljc:15`).
`dao.await` registers module bindings under `'dao.await`, which is
what a Yin program writes. **Answered:** the owner chose the clean break —
rename the wire vocabulary with the namespaces, correct while nothing
outside this tree speaks the wire — over freezing the `v2` inside the
keywords and the subprotocol string as a protocol-version marker. Taken in
the same U7 commit, with the conformance and R5 slice suites as the proof
that both peers moved. U7 is a namespace-and-string sweep, not a redesign,
and it needs no document beyond this section.

### D8 — v1 design documents get status notes, not rewrites

`dao.stream.file.md`, `dao.stream.apply.md`, `daostream-udp-design.md`,
`dao.stream.discovery.md`, `docs/ws-demo.md` (deleted), and the three
`agent.tzu*.md` files describe v1 code. Each gets a one-line status note at
the top naming this plan and the date; none is rewritten. The v2 apply
protocol is specified by its own namespace docstring and tests; a v2 UDP or
HTTP transport is owed to nobody until a consumer asks.

## Units

### Phase 0 — pre-checks, before U1

1. The sweeps, and every hit is a census row or a documented non-hit:
   ```
   # v1 namespace requires, every host
   grep -rnE "\[dao\.stream(\.[a-z._-]+)?( |\]|$)" src test bin deps.edn bb.edn shadow-cljs.edn \
     --include='*.clj' --include='*.cljc' --include='*.cljs' --include='*.cljd' --include='*.edn' \
     | grep -v "dao\.stream\.v2"
   # v1 mechanisms by name, wherever they hide (aliases, docstrings, fabricated cursors,
   # and the consumer-level entry points a namespace grep misses)
   grep -rnE "register-(reader|writer)-waiter!|drain-one!|take!!|\bds/open!|defopen|closed\?|:daostream/gap|\{:position [0-9a-z(]|bind-stream!|put-frame!|tail-position|make-ring-buffer-stream|->seq|strict-vec|:woke" src test
   # the dead registry and its writers
   grep -rnE "\[(yin\.module|yin\.stream|yin\.io[a-z.-]*|agent\.tools|agent\.tzu|yin\.vm\.telemetry-viewer|datomworld\.(ws-demo-server|ws-client-demo|continuation-transport))( |\]|$)" src test bin deps.edn bb.edn shadow-cljs.edn
   # launchers, builds, aliases, pages
   grep -rnE "ws-client-demo|ws_demo|telemetry-viewer|dao\.stream-bench|:atzu|agent\.tzu" deps.edn bb.edn shadow-cljs.edn bin public README.md docs
   ```
   Expected non-hits: the `:dao.stream.apply/call` AST node in `yang.*` and
   `yin.vm.*`; `:dao.stream/…` outcome keywords everywhere; the three v2
   driver-test docstrings that name `dao.test-utils` as history; the
   `:woke` mentions in `dao.runtime`, `dao.await` and
   `dao/await_test` docstrings, which describe v1 as absent; the
   "tail-position flag" comment at `yin/vm.cljc:329`, which is the
   `:yin/tail?` attribute and not the ring-buffer function. After U3,
   `put-frame!` is a v2 name by D4 and its hits are census rows, not v1;
   `bind-stream!` must return nothing after U3.
2. ~~Record the owner's answers to D2, D3 and D7's second half in this
   document.~~ Done 2026-09-17: all three answered deletion/clean break,
   recorded in place in D2, D3, D7.
3. Confirm the two manual surfaces still run before anything moves: the
   Flutter picker's *Artifact*, *Solar System*, *Earth/Moon*, *Voxel*,
   *dao.gui Prototype* entries, and the browser `#artifact`, `#solar-system`,
   `#earth-moon`, `#voxel` routes. These are the U5 acceptance baseline.

### U1 — orphan deletions (no decision needed; can land today)

Each its own commit; no order between them.

- **Telemetry viewer (D1):** `telemetry_viewer.cljs`, its test,
  `public/telemetry-viewer.html`, the `:telemetry-viewer` build, `demo.cljs`
  `:13,50-53,71,86,218`; one line on `telemetry-ui-design.md`'s status note.
- **Continuation transport v1:** `continuation_transport.cljc` and its test.
- **WebSocket demo pair (D6):** the two sources, the three `bin/` scripts, the
  `:ws-client-demo` build, `docs/ws-demo.md`.
- **Orphan tests:** `test/dao/test_utils.cljc`, `test/datomworld/demo/artifact_stream_test.cljs`,
  `test/dao/stream_bench.cljc` with the `:bench` build.

**Criteria:** the Phase 0 sweeps lose exactly these rows; `clj -M:test`, the
shadow `:test` and `:demo` builds, `clojure -M:cljd test` pass; `demo.cljs`
compiles with no telemetry branch; `bb test` passes.

### U2 — decision-gated deletions

After the owner's D2 and D3 answers. Two commits.

- **`agent.*` (D2 default):** `tzu.cljc`, `tools.cljc`, both tests, the two
  prose files under `src/cljc/agent/`, `:atzu`, `README.md:156`; status
  notes on the three `agent.tzu*.md` files.
- **`yin.module` family (D3 default):** `yin/module.cljc`, `yin/stream.cljc`,
  `yin/io/{file, file_input_stream, file_output_stream}.cljc`,
  `test/yin/module_test.cljc`; status note on `dao.stream.file.md`. Note that
  v1 `dao.stream.ringbuffer:12,458` requires `yin.module`; it keeps compiling
  only if this commit lands *after* U6 or the require is dropped here. Drop
  the require and the `init-module!` delay in the same commit — v1 ring
  buffer's module registration has had no reader since 2026-09-16 either.

**Criteria:** the third Phase 0 sweep returns nothing under `src/`; all
lanes green; `Testing yin.vm.module-test` (or whichever v2 suite covers
the registry value) still appears in the Node output so the deletion did not
take the v2 registry's tests with it.

### U3 — `dao.postgraphics.terminal` on v2 (D4)

`terminal.cljc` rewritten; `flutter.cljd` and `web.cljs` bindings on a
ticker; `web/gpu.cljs:545` alias; `terminal_test.cljc` and `web_test.cljs`
rewritten; `dao.postgraphics.terminal.md` prose. After this commit the two
host widgets accept only a v2 handle, so every frame stream handed to them
moves in the same commit: the eight `ds/open!` → `ringbuffer/create!` edits
in `earth_moon_runner`, `voxel_runner`, both `solar_system`, `dao_gui.cljd`,
`postgraphics.cljd`, and the *frame* stream of both `artifact.*` files. The
artifact demos' event streams stay on v1 until U4; `dao.gui.event` and the
terminal never share a stream, so the two halves of that demo can be on
different generations for the interval between the two commits. Keeping a
v1 host binding alongside the new one to avoid this was considered and
rejected: two terminal implementations in one tree is the coexistence the
contract calls a defect. Phase 0's manual baseline is re-run after U3 on
all five picker entries.

**Criteria:**

- `terminal_test.cljc` on clj, cljs and cljd, over a v2 ring buffer and a
  scripted handle: `bind` appends exactly one reset signal and mints at
  `:newest`; a frame appended after bind is presented on the next `step`,
  one appended before is not; a rejected frame appends a rejection signal
  and calls `on-error`; a capacity-1 buffer with two appends yields
  `:gap` then `:presented` with a `frame-skipped` signal between; `end`
  yields `:end` once and `:closed` after; a scripted `transport-error`
  yields `:error` with a `protocol-error` signal. `Testing
  dao.postgraphics.terminal-test` appears in the Node output.
- `web_test.cljs` asserts the same through the canvas widget's binding.
- **Flutter smoke**, manual: *Solar System* animates; pausing holds the last
  frame; *Earth/Moon* and *Voxel* run; *dao.gui Prototype* draws the sample
  frame and the REPL's `(show-sample-frame!)` still changes it. Frame rate
  visibly unchanged at 16 ms ticks.
- **Browser check**, manual: `#solar-system`, `#earth-moon`, `#voxel` render
  and animate.

### U4 — `dao.gui.event` on v2 (D5)

Parallel with U3. `event.cljc` per D5; `bind_test.cljc` and the
scripted-handle fixture; `dao.gui.event.md` prose. After this commit
`advance` calls v2 `stream/next` on whatever handle the binding holds, so
the artifact demos' input, output and signal streams move to `create!` in
the same commit, with their gap branch rewritten onto the recovery cursor
(`artifact.cljs:81-96,111-115`). Their *frame* stream belongs to U3; if U4
lands first it stays v1 here and moves under U3. The two units edit
different regions of the two `artifact.*` files and commute.

**Criteria:**

- All eleven `test/dao/gui/event/*_test.cljc` files green on three hosts
  with deftest counts unchanged, except `bind_test`, whose parked-interval
  cases now run over the scripted handle and whose `drain`/`consume-count`
  helpers are gone. A new case: a `gap` on the input returns `:input-gap`
  with a `:recovery-cursor` that, passed to `recover-input-gap` with an
  input-loss envelope, resumes at the next retained input. A new case: a
  `transport-error` on the input returns `:transport-error` and does not
  re-read.
- **Browser check**, manual: `#artifact` — drag rotates, keyboard navigates,
  and the console shows `dao.stream output gap; resuming` recovery when the
  pointer is spammed past capacity. **Flutter smoke:** *Artifact* drags.

### U5 — the remaining demo surfaces (D6)

Whatever U3 and U4 did not already move: by construction, after both land,
this is a verification unit. Re-run Phase 0's manual baseline on every
picker entry and browser route; confirm no `src/` file outside
`src/*/dao/stream/` requires v1 (`grep` 1 of Phase 0 returns only the v1
tree and its tests).

### U6 — the deletion set

After U1–U5 and the owner's answers. One commit.

**Delete (32 files):** `src/cljc/dao/stream.cljc`; `src/cljc/dao/stream/{apply,
file, file_input_stream, file_output_stream, http, link, ringbuffer, udp, ws}.cljc`;
`src/cljc/dao/stream/rpc/{client, dedup, retry, server, udp, ws}.cljc`;
`src/clj/dao/stream/transit.clj`; `src/cljd/dao/stream/{transit, udp, ws}.cljd`;
`src/cljs/dao/stream/transit.cljs`; `test/dao/stream_test.cljc`;
`test/dao/stream/{apply, file, http, link, rpc, transit, ws}_test.cljc`;
`test/dao/stream/{file_input_stream, file_output_stream}_test.clj`;
`test/dao/stream/rpc/retry_dedup_test.cljc`.

**Edit:** nothing in `deps.edn` or `shadow-cljs.edn` — U1 and U2 took every
config row. `test/README.md` if it names a v1 stream test as a template.

**Local hygiene, not git:** `test/cljd-out/` and `lib/cljd-out/` are
untracked and regenerated, but `flutter test` runs whatever `*_test.dart`
is there; delete the stale twins (`test/cljd-out/dao/stream-test_test.dart`,
`test/cljd-out/dao/stream/{apply,file,http,link,rpc,transit,ws}-test_test.dart`,
`test/cljd-out/dao/stream/rpc/`, `test/cljd-out/agent/`,
`test/cljd-out/datomworld/continuation-transport-test_test.dart`) before
running the lane.

**Prose that describes the deleted code**, in the same commit:

- `docs/design/dao.stream.md:790-816` — *The v2 namespace is transient*:
  the "remaining v1 consumers" paragraph becomes a statement that the last
  consumer migrated under this plan on the commit's date, and that the
  rename is now due (U7).
- `docs/design/dao.runtime.implementation-plan.md:390-403` — the R4
  status note says the trigger "is not close"; replace with "reached, see
  `dao.stream.v1-retirement`".
- `docs/design/yin.vm.v1-retirement.implementation-plan.md:191-196,608-618,627-628`
  — the *Unchanged* rows and *Out of scope* list that name the transport
  item; status notes.
- `docs/design/{dao.stream.file, dao.stream.apply, daostream-udp-design,
  dao.stream.discovery}.md` — D8 status notes.
- `docs/design/yin.vm.streams-all-the-way-down.md:18-31` already says
  "written against v1"; add that v1 is deleted.

**Criteria:**

- All four Phase 0 sweeps return only hits under `docs/`, `collab/`,
  `docs/orchestrator-log.md` and the documented non-hits.
- `clj -M:test`, the shadow `:test` and `:demo` builds, `clojure -M:cljd test`
  and `bb test` pass; `Testing dao.stream-test`,
  `dao.postgraphics.terminal-test`, `dao.gui.event.bind-test` appear in the
  Node output.
- Every U3/U4 manual check passes against the tree with v1 gone.
- `dao.stream.md`'s rename trigger is satisfied and the document says so.

### U7 — the rename wave

After U6, one commit, per D7: `dao.stream` → `dao.stream` (directory
`src/*/dao/stream/**` → `src/*/dao/stream/**`, tests likewise),
`dao.runtime` → `dao.runtime`, `dao.await` → `dao.await` (with its
`'dao.await` module name), `yin.vm` → `yin.vm`, `yin.repl` →
`yin.repl` (with the `:clj-yin-repl`, `:cljd-yin-repl`,
`:cljd-yin-repl-build` aliases, the `:yin-repl` and `:slice-peer`
builds, `bin/yin_repl_main.dart`, and `bb build:yin-repl-peer`), the
`*_v2` demo files and their picker ids, and — per the owner's D7 answer —
the wire keywords and subprotocol string. The `docs/design/*.v2.*` and
`*-v2*` document names are left alone: they are history.

**Criteria:** no `\.v2\b` or `_v2\b` or `-v2\b` token remains under `src/`,
`test/`, `bin/`, `deps.edn`, `bb.edn`, `shadow-cljs.edn` except inside
strings the owner chose to freeze; all lanes green; the R5 slice passes
across three hosts, which proves both wire peers moved together.

### Phase 2 — prose outside the deletion commit

Historical documents that cite v1 by name as a way to do something get a
one-line status note: `docs/design/yin-repl-design.md`, `yin.vm-portability.md`,
`yin.vm.ffi.md`, `yin.vm.macro.md`, `yin.vm.semantic.md`,
`yin.vm.universal-continuation-format.md`, `yin.vm.code-as-tuples.md`,
`dao.space.v0.md`, `dao.fs.md`, `docs/agy-test.md`, `docs/bootstrap.md`,
`docs/handoff.md`, and `src/cljc/yin/vm/docs/{ast, yin-defmacro}.md`.
`docs/ideas/*` and `docs/thetao.md` are ideas and are not annotated.

## Dependency order and parallelism

```
Phase 0 ─┬─ U1 (4 independent commits, today)
         ├─ U2 (after D2/D3 answers; independent of everything else)
         ├─ U3 ──┐
         └─ U4 ──┴─ U5 ── U6 ── U7
```

U1, U2, U3 and U4 are mutually independent and can run as four parallel
lanes. U3 and U4 both edit the two `artifact.*` demo files (different
regions: the frame stream vs. the event streams); whichever lands second
rebases one hunk. U5 is verification only once both are in. U6 waits on all
of U1–U5 and both owner answers. U7 waits on U6 and the D7 answer.

## Completion criteria

- Built (3): the D4 terminal binding (in place), its test (in place), the
  scripted-handle fixture.
- Migrated (19): `flutter.cljd`, `web.cljs`, `web/gpu.cljs`, `web_test.cljs`,
  `terminal.md`, `event.cljc`, `bind_test.cljc`, `gui.event.md`, the eight
  demo files, `demo.cljs`, `shadow-cljs.edn`, `deps.edn`, `README.md`.
- Deleted (58): 14 in U1, 12 in U2, 32 in U6.
- Config: `shadow-cljs.edn` −3 builds; `deps.edn` −1 alias; `bin/` −3 scripts;
  `public/` −1 page.
- Prose: the U6 list, D8's notes, Phase 2.
- All lanes green; the Flutter smoke and browser checks pass on all five
  demos with v1 gone.
- `dao.stream.md`'s rename trigger is met and U7 has a decision to execute.

## Scope, effort, and whether to start now

**Start now.** Nothing in this plan waits on another plan: the VM, REPL,
and scheduler retirements are complete, `dao.space` and `dao.jing` are on v2,
and every v2 primitive the ports need (`ringbuffer/create!`, minted cursors,
the outcome vocabulary, the scripted-handle fixture pattern) exists and is
conformance-tested. U1 can land in an afternoon and removes eleven files and
three builds with no design content. The two owner questions (D2, D3) gate
only U2 and can be answered while U3 and U4 are in progress.

**But it is the biggest plan in the series, and the size is in two units.**
The VM retirement built six files and migrated fourteen, and its two design
units (the widget split, the browser wire) were bounded by an existing v2
stack on the other side. Here:

- **U3 is a real design** (D4): a consumer written to invert control is
  rewritten to be polled, and two host widgets that never owned a timer now
  own one. The code is small (122 lines plus two ~30-line host bindings) but
  the verification is manual across five Flutter entries and four browser
  routes, on two hosts. Risk: a frame-pacing regression that only a human
  can see. Budget it at the size of the VM plan's U2 and U4 together.
- **U4 is a real design in the specification** (D5): the code delta is
  perhaps sixty lines across two files, but `dao.gui.event.md` is 2,362
  lines whose backpressure section was written against a transport v2
  refuses to build, and the port changes what "conforming" means for an
  input stream. Risk: someone rewrites the section as if `full` were gone —
  it is not; only the claim that an in-process ring buffer produces it is.
  The reducer and its ten test files are untouched, which is what keeps this
  bounded.
- **U7 is larger than R4 estimated** and carries the one decision (wire
  keywords) that touches a protocol rather than a namespace. It is
  mechanical once decided, and the R5 slice is its proof.

Everything else — U1, U2, U5, U6 — is deletion and verification, and totals
more files than the two prior retirement plans combined without carrying
their risk.

**Sequencing recommendation, one line:** land U1 today, get D2/D3 answered
and land U2 this week, run U3 and U4 as two parallel lanes with the artifact
demo as their shared integration test, then U5→U6→U7 in a single sitting so
the tree never sits with two stream namespaces longer than it must.

## Risk and scope boundaries

**Bigger than believed:** none of the individual consumers, but the terminal
(D4) is a control-flow inversion, not a data change, and the gui.event
*spec* (D5) has more v1 in it than the gui.event *code*.

**Smaller than believed:** `yin.io` (dead registry), `agent.tools` (one dead
consumer), the WebSocket demo and continuation transport (v2 twins in use),
and the demo runners (one `create!` each).

**Manual verification, stated plainly.** Five Flutter picker entries and
four browser routes have no automated frame-level check; U3 and U4 reduce
what they must prove to "the ticker calls `step` and frames appear" by
moving every decision into `terminal_test` and `bind_test`, which the lanes
cover on three hosts.

**Ownership of shared state.** Each terminal widget's ticker is the only
caller of `step` for its binding; `put-frame!` never steps. The artifact
demos' `advance!` is the only caller of `event/advance` for their binding
and is invoked from host callbacks after an append — that is composition
calling step, not a stream calling back, and it stays.

**Out of scope, explicitly:** a v2 file, HTTP or UDP transport
(`dao.stream.file.md`, `daostream-udp-design.md` are retired v1 designs; a
v2 twin is built when a consumer needs one); a v2 `agent.tools` or a
Yin-native agent (the harness plan); v2 telemetry (owed since the VM plan's
D2); a readiness extension; stream discovery.

## Boundary — what is owed elsewhere

| owed | by | recorded where |
|---|---|---|
| ~~the owner's answers on D2, D3, D7~~ — answered 2026-09-17: delete, delete, clean break | — | D2, D3, D7 |
| a Yin-native agent over the harness, replacing `agent.tzu` | `agent.harness.md`'s plan | D2 |
| an `io` module for `yin.vm.module` over a v2 file transport, if wanted | its own plan, not written | D3 |
| v2 telemetry emission and a viewer on the v2 wire | `yin.vm.telemetry.implementation-plan.md`, not written | D1; VM plan D2 |
| the rename wave | U7 of this plan | D7; `dao.stream.md:790-796`; VM plan D5; runtime plan R4 |
| status notes on the Phase 2 list | this plan, Phase 2 | above |

## Revision history

- **2026-09-17, architect r1.** First draft. Census: roughly 80 files
  against the brief's ~40. Four brief claims corrected: `yin.io.*` registers into a dead
  registry and is a delete (D3), `agent.tools` has only `agent.tzu` as a
  consumer (D2), the continuation transport and the WebSocket demo pair
  already have v2 twins in use (D6), and the postgraphics `v2/v3/v4`
  documents are a false lead. Two files the brief missed added as the host
  halves of the terminal port (`flutter.cljd`, `web.cljs`). Telemetry viewer
  confirmed dead (D1). The terminal redesigned as a step-driven binding with
  host-owned cadence (D4); `dao.gui.event` kept as-is in shape with the
  contract's data and a rewritten backpressure section (D5). Rename wave
  sized at 163 files plus a wire-keyword decision (D7). Seven units plus
  Phase 0/2; U1 startable today.
- **2026-09-17, architect r2.** Phase 0's mechanism sweep gained
  `bind-stream!|put-frame!` — the review
  (`collab/1789630998716-review-dao-stream-v1-retirement-plan.gemini-3.1-pro-high.findings.md`)
  found the sweep omitted the two terminal entry points the census had to
  add by hand, so a new `bind-stream!` call before U3 would go undetected.
  The same check found four more names the census located by hand and the
  sweep did not cover: `tail-position` (`artifact.cljs:89,115`,
  `bind_test.cljc:239`), `make-ring-buffer-stream` (`bind_test.cljc:23,115,121,359`),
  `->seq` (`continuation_transport_test.cljc:23,66`), and `:woke`
  (the v1 protocol's own outcome key, e.g. `ringbuffer.cljc:103,135,175`;
  `terminal.cljc:50` destructures the same value as the bare symbol `woke`,
  not the literal keyword, so the term does not itself cover that call site
  — `put-frame!`, added above, already does); all added, with their v2
  docstring non-hits listed.
  No other section changed.
- **2026-09-17, owner decisions recorded.** D2, D3, and D7's second half
  answered: delete `agent.tzu`/`agent.tools`, delete the `yin.module`
  family, clean-break the wire keywords in U7. U2 is unblocked; only U1's
  own criteria (Phase 0's sweeps) remain before execution starts.
