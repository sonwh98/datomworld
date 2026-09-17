# yin.vm v1 retirement — the second and last deletion slice

Status: implementation plan for retiring the whole remaining v1 lineage —
`dao.await`, v1 `yin.repl` with its three live consumers, and the v1
ast-walker VM (`yin.vm`, `yin.vm.{ast-walker, engine, ffi, runtime-adapter,
stream-driver, telemetry}`) — by building a v2 twin for every consumer that
still has none, then deleting v1 in one change. Successor to
[`yin.vm-consumers.implementation-plan.md`](./yin.vm-consumers.implementation-plan.md)
(the first slice, 2026-09-10), which left exactly this work in its *Boundary*
table. Subordinate to [`dao.stream.md`](./dao.stream.md),
[`yin.vm.divergence-register.md`](./yin.vm.divergence-register.md),
[`yin.repl.implementation-plan.md`](./yin.repl.implementation-plan.md)
and [`dao.runtime.implementation-plan.md`](./dao.runtime.implementation-plan.md).

Drafted 2026-09-16, architect r1, against a `grep`/`git` sweep of the tree
on the `dao.stream-redesign-v2` branch at `3497fe4`. The revision history at
the end records what each round changed.

## The problem and context

The owner has decided that v1 is deprecated and is to be fully retired: v2
replacements first for every real consumer, then delete v1 entirely. The
first slice deleted the four experimental VM models and the macro engine and
migrated v1 `yin.repl` onto `:ast-walker` so its consumers kept working. Its
*Boundary* section named what it left and why: v1 `yin.repl` has three live
consumers with no v2 twin (the Flutter REPL widget and two telemetry
servers), `dao.await` had not migrated, and `yin.vm.parity-test` asserts
v2 against v1 in the same process. "No document schedules either." This is
that document.

The brief for this plan stated six facts from an evening's read-only
investigation and asked that they be verified rather than trusted. Four hold.
Two do not, and both change the shape of the work: **v2 telemetry emission
does not exist** — `yin.vm.telemetry` is a stub that *rejects* a telemetry
stream — and the v1 servers' only consumer, the browser telemetry viewer,
speaks the v1 wire. See *What the brief got wrong*.

## What is verified and what is judged

Everything below is a verified fact from the 2026-09-16 sweep unless marked
**[J]** (judgment call) or **[B]** (a claim in the brief that the sweep
corrected). Line numbers are as of the sweep. Facts a later review finds and
a later round re-verifies will be marked **[R]**.

### What the brief got right

- **`dao.await` v1 has no consumer but its own test.** The only requires of
  `dao.await` outside the file itself are `test/dao/await_test.cljc:7` and its
  `:require-macros` at `:10`. `dao.await` (`src/cljc/dao/await.cljc`,
  238 lines) requires only `dao.stream`, `yang.clojure`, `yin.vm`,
  `yin.vm.ast-walker` and `yin.vm.module` (`:36-40`), and its suite
  `test/dao/await_test.cljc` describes itself as "the v1 await suite,
  ported". The design document `docs/design/dao.await.md` was already rewritten
  to the v2 rules under the dao.runtime plan's R3 (2026-09-06).
- **Only the ast-walker slice of v1 `yin.vm` remains** — the seven source
  files named in the header, 2,562 lines — and it is required by v1 `yin.repl`
  (`repl.cljc:17-18`), `dao.await` (`await.cljc:25-27`), and a set of tests
  larger than the brief listed (*Consumer census*).
- **v1 `yin.repl` has exactly three `src/` consumers:**
  `src/cljd/yin/repl/flutter.cljd:7`, `src/clj/yin/vm/telemetry_server/jvm.clj:5`,
  `src/cljs/yin/vm/telemetry_server/node.cljs:4`. The Flutter widget is used by
  `dao_gui.cljd:7,290-296,321,326` and `solar_system.cljd:10,59,123`. Plus the
  launch surface the first slice recorded: `src/clj/yin/repl/runner.clj`,
  `bin/yin_repl_main.dart`, the `:yin-repl` shadow build (`shadow-cljs.edn:35`)
  and the aliases `:clj-yin-repl`, `:cljs-yin-repl`, `:cljd-yin-repl`,
  `:cljd-yin-repl-build` (`deps.edn:54-60,76-89`), and `:telemetry-server`
  (`deps.edn:72`; shadow build of the same name).
- **The v2 REPL stack is complete and the cljd host adapter exists.**
  `yin.repl.{core, driver, connect, serve, host, host.common}` (2,558
  lines) plus host shadows `src/cljd/yin/repl/host.cljd` (selects
  `dao.stream.ws.dart/websocket`, which supplies `:connect!`, `:bind!`,
  `:unbind!` — `dart.cljd:113,225,257,291`) and `src/cljs/yin/repl/host.cljs`
  (Node). `serve!` (`serve.cljc:184-326`) composes an endpoint from a
  `{:bind! :unbind!}` host and is tested against injected fake host functions
  with no socket (`test/yin/repl_serve_test.cljc:25-30,199-236`). A Dart
  process already serves through it: the R5 cross-host peer's `--serve` role
  (`test/yin/repl/slice_peer.cljc`, built by `bb build:yin-repl-peer`).
- **`remotes/origin/mr-clean` is noise.** It is an ancestor of `master` (284
  commits behind, 0 ahead; last commit `0745fd4`, 2026-05-21, a postgraphics
  demo). Nothing on it is unmerged. Likewise `origin/dao.await` is fully
  merged.

### What the brief got wrong

**[B] v2 telemetry emission does not exist; the stub rejects it.** The brief
said "v2 telemetry emission already exists at `src/cljc/yin/vm/telemetry.cljc`
— the gap is specifically the SERVER processes". The file is 84 lines: `enabled?`
is always false (`:37-40`), `emit-snapshot` is identity in both arities
(`:79-83`), and `install` *throws* on a supplied `:telemetry` option
(`:29-34,43-50`) — "a stub that merely recorded the model would accept a stream
and then write nothing to it forever; silent acceptance is the one way this
stub could mislead". The v2 REPL rejects `--telemetry` and `--telemetry-stream`
by naming the v1 REPL (`yin/repl.cljc:30-32,50-51`) and answers the
`(telemetry)` command with the same text (`core.cljc:78,95,589,652`). The
divergence register records this as user-visible change 5 and lists what the
real emit path still owes (`:54-57`, `:280-295`). So the v1 telemetry servers
are not "servers missing a v2 twin of thirteen lines"; they are the only place
in the tree where telemetry *emission* is reachable at all. Decision D2.

**[B] The telemetry servers' consumer speaks the v1 wire.** The brief
presumed the servers feed `telemetry_viewer.cljs`. They do, and the viewer
requires v1 `dao.stream`, `dao.stream.apply` and `dao.stream.ws` (`:3-5`),
polls with `{:position 0}` cursors (`:18-19`), and drives the v1 RPC envelope
(`:157-174`). It is a **v1 transport consumer** on the list this plan is told
to leave alone (`dao.stream.md:803-812`, "the demo and server surfaces"). Its
picker card is already commented out of the browser demo (`demo.cljs:50-53`);
only the `#telemetry` hash route and a standalone `public/telemetry-viewer.html`
still reach it. A v2 telemetry server, if one existed, could not serve it.

**The browser REPL demo is a fourth consumer, by wire.**
`src/cljs/datomworld/demo/yin_repl.cljs` requires no v1 `yin.*` namespace, so
a namespace grep misses it, but it is a browser client of the v1 REPL server
over the v1 wire (`:8-10` require `dao.stream`, `dao.stream.apply`,
`dao.stream.ws`; `:414` tells the visitor to run
`clj -M:clj-yin-repl --port 8080 --headless`). It is the live "Yin REPL" card
in the public demo picker (`demo.cljs:46-49,64,82,214`). Deleting v1 `yin.repl`
deletes the only server it can talk to. No browser adapter for
`dao.stream.ws` exists — `src/cljs/dao/stream/ws/` holds `node.cljs` only,
and no `.cljs` under `src/` names `js/WebSocket` for v2. Decision D3.

**v1 `yin.repl` loses `extra-primitives` on `(reset)`.** The widget merges
host primitives into the VM *after* `create-state` (`flutter.cljd:60-61`),
and v1's `reset` and `vm` commands rebuild the VM from `make-vm` alone
(`repl.cljc:554-556,565-568`), so a `(reset)` from the desktop silently drops
`show-sample-frame!` and friends. The v2 twin must not copy this (D1).

**The v1 VM has more test consumers than the brief's list.** Beyond the
parity test the brief named: `test/yang/{clojure,php,python}_test.clj` run
compiled programs on the v1 walker (`clojure_test.clj:7-8,25-27,34-36`,
`php_test.clj:7-8,24-26`, `python_test.clj:7-8,25-27,227`);
`test/yin/module_test.cljc:16` requires v1 `yin.vm` on `:clj` and never uses
the alias (the `:module/require` handler it tests is registered by
`yin.module` itself, `module.cljc:99-112`); and
`test/bench/stream_optimization_bench.cljc:4` is an orphan bench on the v1
engine that no `deps.edn`, `bb.edn` or `shadow-cljs.edn` entry runs.

## Consumer census

Every file that requires a namespace this plan deletes, launches deleted
code, speaks the deleted server's wire, or asserts that a deleted thing
exists — grouped by disposition. Fifty files; the brief named eleven.

### Built new (v2 twins)

| file | replaces | unit |
|---|---|---|
| `src/cljc/yin/repl/embed.cljc` | the Flutter-free half of `flutter.cljd` (server lifecycle, status, stepping) | U2 |
| `src/cljd/yin/repl/flutter.cljd` | the Flutter half of `flutter.cljd` (notifiers, timer, `info-card`) | U2 |
| `test/yin/repl_embed_test.cljc` | `test/yin/repl_test.cljc`'s `connection-status-text-test` and the widget's untested server path | U2 |
| `src/cljs/dao/stream/ws/browser.cljs` | the browser's v1 `dao.stream.ws/connect!` | U4 |
| `test/dao/stream/ws/browser_test.cljs` | — | U4 |
| `src/cljs/datomworld/demo/yin_repl.cljs` | `yin_repl.cljs` (v1 wire) | U4 |

### Migrated to keep working without v1

| file | change | unit |
|---|---|---|
| `src/cljc/yin/repl/core.cljc` | additive `:primitives` option on `create-state` (`:314-335`), stored in state and reapplied by `rebuild-session` (`:544`) so `(reset)` and `(vm …)` keep host primitives — D1 | U2 |
| `src/cljd/datomworld/demo/dao_gui.cljd` | `:7` require → `yin.repl.flutter`; `:292` "clj -M:clj-yin-repl" → `-v2`; `:293-296` connect string gains the `daostream:` prefix and `/repl` path per `connect/repl-target`; `:321,326` unchanged in shape | U2 |
| `src/cljd/datomworld/demo/solar_system.cljd` | `:10` require; `:59,123` unchanged in shape | U2 |
| `src/cljd/datomworld/demo/dao_gui.md` | `:41,181` compile command names `yin.repl` → the v2 widget namespace; `:120` desktop alias → `:clj-yin-repl`; the connect form after `:125` gains prefix and path | U2 |
| `src/cljs/datomworld/demo.cljs` | `:10` require → `yin_repl`; `:214` case branch; `:46-49` card text | U4 |
| `test/yang/clojure_test.clj`, `php_test.clj`, `python_test.clj` | requires (`:7-8` each) → `yin.vm` + `yin.vm.test-utils`; the `compile-and-run`/`run-ast` helpers rebuilt on `tu/create-vm` + `tu/make-observer-session` + `tu/queue-ast!` + `tu/run-session` so `env` and `vm-opts` still pass through; `python_test.clj:227` `vm/primitives` → `yin.vm/primitives` (`v2.cljc:101`) | U5 |
| `test/yin/vm/parity_test.cljc` | four deftests (`:142,149,162,179`) compare against v1 in-process; the v1 side becomes **pinned expected values** recorded in the corpus, computed once from v1 before deletion — D4 | U5 |
| `test/yin/module_test.cljc` | drop the unused `#?(:clj [yin.vm :as vm])` at `:16` | U5 |
| `test/yin/repl_build_test.clj` | three assertions that v1 entries are "unchanged"/"untouched"/"stays" (`deps-edn-…` testing block, `shadow-cljs-…` testing block, the `bin/yin_repl_main.dart` `.exists` check) are inverted to assert absence | U6 |
| `test/dao/test_utils.cljc` | **[J]** `make-waitable-retry-stream` loses its last user (`runtime_regression_test.cljc:16`) and is removed; `make-non-waitable-stream` stays for the `dao.runtime` driver tests until R4 | U6 |
| `src/cljc/yin/repl.cljc:30-32`, `core.cljc:95` | `telemetry-text` says "run yin.repl for telemetry"; after U6 there is no v1 REPL to run — reword to name the owed telemetry plan (D2) | U6 |
| `deps.edn` | delete `:clj-yin-repl` (`:54`), `:cljs-yin-repl` (`:55-60`), `:telemetry-server` (`:72`), `:cljd-yin-repl` (`:76-80`), `:cljd-yin-repl-build` (`:81-89`) | U6 |
| `shadow-cljs.edn` | delete `:yin-repl` and `:telemetry-server` builds; **keep** `:telemetry-viewer` (transport item) | U6 |

### Deleted with their dependency

| file | requires | why deletion, not migration |
|---|---|---|
| `src/cljc/dao/await.cljc` | v1 `yin.vm`, `ast-walker`, `engine`, `dao.stream`, `yin.module` | twin `dao.await` exists and is tested; no consumer | U1 |
| `test/dao/await_test.cljc` | `dao.await` | ported as `test/dao/await_test.cljc` | U1 |
| `src/cljc/yin/repl.cljc` (1,076 lines) | v1 VM, v1 transports | twin `yin.repl`; every consumer has a twin after U2–U4 | U6 |
| `src/cljd/yin/repl/flutter.cljd` | `yin.repl` | twin from U2 | U6 |
| `src/clj/yin/vm/telemetry_server/jvm.clj`, `src/cljs/yin/vm/telemetry_server/node.cljs` | `yin.repl`, v1 `dao.stream.ws` | **[J]** a v1 REPL server on 8090 plus a v1 telemetry sink on 8091; the REPL half's twin is `yin.repl --port --headless`, the sink half has no v2 to serve — D2 | U6 |
| `src/clj/yin/repl/runner.clj`, `bin/yin_repl_main.dart` | launch v1 `yin.repl` on Dart | twins `yin.repl.runner`, `bin/yin_repl_main.dart` exist | U6 |
| `src/cljs/datomworld/demo/yin_repl.cljs` | v1 wire | twin from U4 (D3) | U6 |
| `src/cljc/yin/vm.cljc`, `src/cljc/yin/vm/{ast_walker, engine, ffi, runtime_adapter, stream_driver, telemetry}.cljc` | v1 `dao.stream`, `dao.runtime`, `yin.module` | the lineage itself; `yin.vm/*` is the twin. `runtime_adapter.cljc` is also on `dao.runtime` R4's delete list; it goes here, and R4's list shrinks | U6 |
| `test/yin/repl_test.cljc` (22 deftests) | `yin.repl` | contract of a deleted file; v2 has `v2_core_test`, `v2_driver_test`, `v2_connect_test`, `v2_serve_test`, `v2_test`, `v2_adapter_test`, `v2_host_node_test`, `v2/host/jvm_test` | U6 |
| `test/yin/vm/{ast_walker, engine, ffi, stream_driver, telemetry, runtime_adapter, runtime_regression}_test.cljc`, `test/yin/vm/test_utils.cljc` | v1 VM | contracts of deleted files; v2 twins under `test/yin/vm/`. `runtime_adapter_test` and `runtime_regression_test` are also on R4's list; they go here | U6 |
| `test/yin/vm/ast_conversion_test.cljc` (2 deftests) | v1 `yin.vm` codec | **[J]** `test/yin/vm_test.cljc` has 24 deftests on the v2 codec; Phase 0 confirms both cases (`ast-datom-roundtrip`, `root-id-detection`) are covered there, ports any that is not, then this file goes | U5/U6 |
| `test/bench/stream_optimization_bench.cljc` | v1 `engine` | orphan bench; no build runs it | U6 |

### Unchanged, named so the reader can check

- `src/cljs/yin/vm/telemetry_viewer.cljs`, `test/yin/vm/telemetry_viewer_test.cljs`,
  the `:telemetry-viewer` shadow build, `public/telemetry-viewer.html`, and
  `demo.cljs:13,71,86,218`: v1 *transport* consumers, deleted under `dao.stream.v1-retirement.implementation-plan.md` (2026-09-17),
  not here.
- `src/cljc/yin/module.cljc`: required by `yin.io.*`, `yin.stream` and v1
  `dao.stream.ringbuffer` — the transport item. Stays.
- `src/cljc/dao/runtime.cljc` and the three `dao.runtime.driver` hosts: stay
  until `dao.runtime` R4, which this plan **opens** (Boundary).
- `test/dao/stream/transit_test.cljc:119-141` uses `:yin.repl/request` as
  keyword data in wire fixtures; not a require, not touched.
- `src/cljc/dao/stream.cljc:206`, `src/cljc/dao/stream/rpc/client.cljc:9`,
  `src/cljc/yang/clojure.cljc:22`: prose mentions of v1 in v1-transport
  docstrings; go with the transport item.

## Decisions

### D1 — the Flutter widget is a thin view over a Flutter-free `embed` namespace [J]

**What `flutter.cljd` does today.** Six module-level notifiers and atoms
(`:10-15`), a platform-dependent default port (7778 on iOS, 7777 elsewhere;
`:18`), `load-device-ip!` enumerating `NetworkInterface.list` into two
`ValueNotifier`s (`:21-41`), `stop-server!` (`:44-51`), `start-server!`
(`:54-82`) which creates v1 REPL state with the default VM, merges
`:extra-primitives` into the VM (`:60-61`), calls v1 `repl/serve!` bound to
`"0.0.0.0"` with `:on-connect`/`:on-disconnect` callbacks that maintain a
client count and set `server-status` through `repl/connection-status-text`
(`:63-81`), then loads the device IP; an `eval-input!` (`:85-89`) that no
consumer calls; and `info-card` (`:98-143`), a `Card` listing the wildcard
URL, the device-IP URL, the interface table and the status line. The two
consumers call only `default-port`, `start-server!`, `stop-server!` and
`info-card`.

**What the v2 stack changes about that shape.** v1 was callback-driven; v2 is
step-driven and its only state owner is whoever calls `serve/step`
(`serve.cljc:674-695`). There is no `:on-connect`; the session set is read from
`serve/summary` (`:698-709`, `:sessions`). `stop!` initiates and claims
nothing (`:631-651`); `stopped?` (`:616-628`) is the exit condition and a host
keeps stepping through `stop-ticks` (`v2.cljc:114-119`) until it holds. A
wildcard bind needs an explicit advertised host or the endpoint is inert
(`serve.cljc:59,202-203,228-232`); the advertised host appears only in the
descriptor and `url` (`:329-333`) — the accept path matches on `:ws/path`
alone (`ws.cljc:347`) — so any string the operator can read is correct. And the
connect URL an operator types is `daostream:ws://<ip>:<port>` with an empty
path mapped to `/repl` by `connect/repl-target`.

**Disposition.** Two new files:

- `src/cljc/yin/repl/embed.cljc` — no Flutter, no Dart, no timer. It is the
  composition an embedding host drives, and it runs on all three hosts so it
  is testable on all three:
  - `(start {:keys [port bind-host advertised-host primitives host]})` →
    endpoint value: `(serve/serve! {:bind-port port :bind-host (or bind-host
    "0.0.0.0") :advertised-host advertised-host :host host :repl
    (core/create-state {:primitives primitives})})`. `host` defaults to
    `(yin.repl.host/websocket)`; a test injects the fake.
  - `(step endpoint now)` → `[endpoint' lines]` = `serve/step` then
    `serve/take-outbox`; the caller only prints or displays.
  - `(status endpoint)` → `{:status … :clients n :url …}` from `serve/summary`;
    `(status-text status)` renders the line `info-card` shows. This replaces
    `repl/connection-status-text`.
  - `(stop endpoint)` → `serve/stop!`; `(stopped? endpoint)` → `serve/stopped?`.
- `src/cljd/yin/repl/flutter.cljd` — the notifiers, `default-port`,
  `load-device-ip!` (ported verbatim), and:
  - `start-server!` — `stop-server!` first; then **load the device IP first
    and start the endpoint in its `.then`**, so the found IP (or `"localhost"`)
    is the advertised host. One `Timer.periodic` of `yin.repl/tick-millis`
    (25 ms) owns the endpoint atom: each tick calls `embed/step`, writes
    `status-text` into `server-status`, and is the only writer of that atom.
    This is `run-dart!`'s shape (`v2.cljc:349-395`) minus the shell.
  - `stop-server!` — `embed/stop`, set status `"stopping"`, and let the same
    timer keep stepping until `stopped?` or `stop-ticks`, then cancel the
    timer and set `"stopped"`. Returns `:stopped` immediately as today;
    `dao_gui/stop!` does not read the return.
  - `info-card` — ported; the two URLs it prints become the `daostream:`
    form with `/repl`, which is what the desktop operator types.
  - `eval-input!` is not ported: no consumer calls it and a second evaluator
    of the shared shell outside the step owner is exactly the "second state
    owner" the driver forbids.

**The additive `:primitives` option in `core.cljc`.** `create-state`
(`:314-335`) takes no primitives; `make-vm` (`:258-274`) merges
`vm/primitives` with the REPL's print primitives and nothing else. The option
is: `create-state` accepts `:primitives` (a map merged *over* the REPL
primitives), stores it as `:extra-primitives`, and `make-session`/`make-vm`
and `rebuild-session` (`:544`) read it — so `(reset)` and `(vm :ast-walker)`
from the desktop keep the host functions, which v1 loses. One deftest in
`test/yin/repl_core_test.cljc` pins it: a state created with
`{:primitives {'answer (fn [] 42)}}` evaluates `(answer)` to 42 before and
after `(reset)` and after `(vm :ast-walker)`.

Why not put the timer in `embed`: a `.cljc` timer would need a host branch
per platform for something the Flutter file already owns, and the widget
would then have two tickers' worth of code to reason about. Why not keep the
v1 shape of one `flutter.cljd`: nothing in it would be testable outside a
Flutter runtime, which is the reason the first slice had to specify a manual
startup smoke as the *only* check of that file.

### D2 — the v1 telemetry servers are deleted without a telemetry twin; v2 telemetry is its own plan [J — owner decision]

**Owner decision, 2026-09-16: the default.** The two servers are deleted in
U6 with no gate; a v2 telemetry plan is owed separately and does not block
this plan's completion.

This is the one place this plan departs from "build v2 replacements for
every real consumer first", and the owner should confirm or reverse it.

Each server is one `let` (`jvm.clj:10-13`, `node.cljs:9-12`): a v1
`ws/listen!` on 8091 handed to `repl/create-state` as the telemetry stream,
and `repl/serve!` on 8090. The REPL half has a v2 twin today —
`clj -M:clj-yin-repl --port 8090 --headless` or the `:yin-repl` node
build. The telemetry half is not a server; it is the v1 VM's emit path
(`yin/vm/telemetry.cljc`, 282 lines: `snapshot-datoms`, `event-datoms`,
`emit-snapshot`, the `:vm/*` schema) reached through the REPL's
`(telemetry …)` command and `:telemetry-stream` option. v2 has none of it, by
a decision the VM plan took and the divergence register records with the
exact list of what the real emit path still owes (`:280-295`: append-outcome
handling, surface-protocol ordering, opaque-cursor summarisation, a stream
and its capacity, the test suite) — plus, on the REPL side, a served
telemetry stream, which is the forwarded second stream the REPL plan's D2
called "worthless until resumption is specified".

Building that is a VM feature plan of the size of the semantic-VM or FFI
plans, not a consumer migration, and it would not restore a working system:
the viewer that consumes the servers speaks the v1 wire and is already
delisted from the picker. So:

- **Default (this plan as written):** the two servers, their `deps.edn`
  alias and shadow build are deleted in U6 with v1 `yin.repl`. The v2
  rejection texts (`v2.cljc:30-32`, `core.cljc:95`) stop naming a v1 REPL that
  no longer exists and name the owed plan instead. `telemetry-ui-design.md`
  and `vm-telemetry-design.md` get status notes. A **v2 telemetry plan** is
  owed (Boundary table) and the first thing it builds is the emit path;
  its server is then one flag on `yin.repl --port`.
- **Alternative (Gate T):** if the owner wants telemetry parity before v1
  goes, U6 is gated on that plan landing. U1–U5 are unaffected either way.

The recommendation is the default. Recording it here is what makes the
regression explicit rather than silent; nobody has signed off on it until
the owner does.

### D3 — the browser REPL demo is ported to the v2 wire before v1 goes [J]

Precedent: the first slice's D3 refused to let the public demo lose Python
and PHP silently. The "Yin REPL" card (`demo.cljs:46-49`) is a live picker
entry whose only server is v1 `yin.repl`; deleting v1 without a port turns a
public demo into a client of nothing. The port is real but bounded:

- `src/cljs/dao/stream/ws/browser.cljs` — a `:connect!`-only adapter over
  the DOM `WebSocket`, the shape `dao.stream.ws/make-attacher` asks for
  (`host.cljc:23-27`: start connecting, never wait, synchronously return
  `{:send! … :close! …}`), mirroring `node.cljs`'s `connect!` with the DOM
  event names. No `:bind!` — a browser cannot listen — so it satisfies
  `host-common/adapter?` and not `binder?`, which is exactly the distinction
  `host.common` draws (`:28-37`).
- `src/cljs/datomworld/demo/yin_repl.cljs` — keeps the CodeMirror editor
  and history panel; replaces the v1 `put-request!`/`poll-response` pair with
  `driver/create-state {:host browser-adapter}`, `driver/submit-line!` on
  Eval, and one non-overlapping `setInterval` of `tick-millis` that owns the
  driver state and drains `driver/take-outbox` into the history — the Node
  host's composition (`v2.cljc:264-308`) with the readline replaced by the
  editor. Its instruction line names `clj -M:clj-yin-repl --port 8080
  --headless` and the `daostream:ws://…` URL.
- It composes the adapter directly rather than through `yin.repl.host`,
  because the cljs shadow of that namespace selects Node's `ws` package and
  is per-*build*, not per-platform; requiring it in the `:demo` browser build
  would pull `js/require` into the browser.

If the owner would rather retire the browser REPL card, that is their
explicit decision to record here; the plan's default is the port.

### D4 — parity against a deleted v1 becomes pinned values [J]

`yin.vm.parity-test` exists to say "v2 agrees with v1 on this corpus". Once
v1 is gone the sentence has no right-hand side. Three options: delete the
file (loses a regression corpus of roughly two dozen programs), keep v1 in `test/` only (keeps
2,562 lines of deleted code alive for one test), or **pin**: extend each
corpus row `[name ast]` to `[name ast expected]` with `expected` computed by
running v1 once before U6 and recorded in the source, and rewrite the four
deftests to compare v2 against the column. The FFI and stream round-trip
cases (`:149-196`) pin the normalized shapes the same way. The file keeps its
name; its docstring says what the column is and when it was captured. The
capture is a Phase 0 step so it is done against the tree the values come
from.

### D5 — `dao.await` is not renamed here

`dao.stream.md:790-796` decides `dao.stream` → `dao.stream` "when the last
consumer has migrated", and the dao.runtime plan's R4 recommends
`dao.runtime` → `dao.runtime` "in the same change as `dao.stream`'s
rename". `dao.await` registers module bindings under `'await` and
`'dao.await` (`v2.cljc:51-52`), so a rename changes what a program may
write, not only a require. It belongs to the same rename wave as the other
two, and this plan records it as owed rather than doing one of three renames
early. `yin.vm` and `yin.repl` are the same question and the same
answer.

### D6 — one deletion set, one change, after every twin has landed

U1 is its own commit and can land today. U2, U4 and U5 each land as their
own commit with v1 still present and every lane green, so no commit exposes
a demo or a widget without a server. U6 is one atomic commit: the delete
list, the config edits, the test inversions, and the prose that describes
the code being deleted. Splitting U6 would leave a commit where an alias
names a deleted namespace or a test asserts a deleted file exists.

## Units

### U1 — `dao.await` v1 deletion

Delete `src/cljc/dao/await.cljc` and `test/dao/await_test.cljc`. Nothing
else changes. **Criteria:** the Phase 0 `dao.await` grep returns only
`dao.await` hits and `docs/`; `clj -M:test`, the shadow `:test` build and
`clojure -M:cljd test` pass, and `Testing dao.await-test` appears in the
Node output.

### U2 — the v2 Flutter REPL widget

Per D1: the `:primitives` option in `core.cljc` with its deftest;
`embed.cljc`; `flutter.cljd`; `v2_embed_test.cljc`; the two demo consumers
and `dao_gui.md` repointed. v1 `flutter.cljd` is left in place until U6.

**Criteria:**

- `v2_embed_test.cljc` runs on clj, cljs and cljd, with the injected host
  functions `v2_serve_test.cljc:25-30` uses: `start` with a wildcard bind and
  an advertised host yields an endpoint whose first `step` reports
  `:bind-succeeded`; `status-text` reads "listening" with zero clients; a
  request through an adopted session that calls a primitive supplied via
  `:primitives` returns its value; `(reset)` through the same session keeps
  that primitive; `stop` then stepping reaches `stopped?`. `Testing
  yin.repl.embed-test` appears in the Node output.
- **Flutter startup smoke**, manual, the only check that exercises the
  `.cljd` widget itself:
  ```
  mise exec -- clj -M:cljd compile yin.repl.flutter datomworld.demo.dao-gui datomworld.demo.main
  mise exec -- flutter run
  ```
  Select **"dao.gui Prototype"**; the status line reaches "listening" and the
  card shows a `daostream:ws://<device-ip>:7777/repl` URL. From the desktop:
  `mise exec -- clj -M:clj-yin-repl`, `(connect "daostream:ws://<ip>:7777")`,
  then `(show-sample-frame!)` returns and the frame changes; `(reset)` then
  `(show-sample-frame!)` still works (the v1 defect is not reproduced). Repeat
  the selection for "Solar System" with `(bodies)`. Stopping the demo returns
  the status to "stopped" within `stop-ticks` ticks.

### U3 — the telemetry servers

Per D2, no build in this plan. The unit exists so the owner's decision has a
place to land: **default**, delete in U6 and record the owed v2 telemetry
plan; **Gate T**, block U6 on that plan. If Gate T is chosen the shape of the
twin is already determined — the emit path first, then `--telemetry-stream`
on `yin.repl` serving a second stream — and it is written as its own
document, not appended here.

### U4 — the browser REPL client on the v2 wire

Per D3: `browser.cljs`, `yin_repl.cljs`, `browser_test.cljs`, the
`demo.cljs` repoint. v1 `yin_repl.cljs` stays until U6.

**Criteria:**

- `browser_test.cljs` (Node, under the `:test` build, using the `ws` package
  as the peer since Node has no DOM socket — or a minimal `WebSocket` global
  shim) proves the adapter returns `{:send! :close!}` synchronously, deposits
  `:ws/opened` and `:ws/closed` through the boundary, and never blocks.
  `Testing dao.stream.ws.browser-test` appears in the output.
- Manual: `clj -M:clj-yin-repl --port 8080 --headless` on the desktop;
  `/demo.html#yin-repl` connects, `(+ 1 2)` prints `3`, killing the server
  prints a detached notice rather than a timeout, restarting it and
  reconnecting works.

### U5 — test ports off the v1 VM

The three `yang` tests, `module_test.cljc`, the parity pin (D4), and the
`ast_conversion_test` coverage check. Each keeps its assertions; only the
evaluator under them changes. **Criteria:** all three lanes green with v1
still present; every deftest count unchanged except where a case was
ported into `v2_test.cljc`; `test/README.md:137,143`'s template names
`yin.vm`.

### U6 — the deletion set

After U1, U2, U4, U5 (and U3's plan if Gate T). One commit:

**Delete (25 files):** `src/cljc/yin/repl.cljc`; `src/cljd/yin/repl/flutter.cljd`;
`src/clj/yin/repl/runner.clj`; `bin/yin_repl_main.dart`;
`src/clj/yin/vm/telemetry_server/jvm.clj`; `src/cljs/yin/vm/telemetry_server/node.cljs`;
`src/cljs/datomworld/demo/yin_repl.cljs`; `src/cljc/yin/vm.cljc`;
`src/cljc/yin/vm/{ast_walker, engine, ffi, runtime_adapter, stream_driver, telemetry}.cljc`;
`test/yin/repl_test.cljc`; `test/yin/vm/{ast_conversion, ast_walker, engine, ffi,
runtime_adapter, runtime_regression, stream_driver, telemetry}_test.cljc`;
`test/yin/vm/test_utils.cljc`; `test/bench/stream_optimization_bench.cljc`.

**Edit:** `deps.edn` (five aliases), `shadow-cljs.edn` (two builds),
`test/yin/repl_build_test.clj` (three inversions), `test/dao/test_utils.cljc`
(one helper), the two `telemetry-text` strings, and the prose below.

**Local hygiene, not git:** `test/cljd-out/` is untracked and regenerated by
the cljd lane, but `flutter test` runs whatever `*_test.dart` is there; delete
the stale twins of deleted tests (`repl_test`, the eight `yin/vm/*` tests,
`await_test`) before running the lane so a stale entrypoint does not import a
deleted namespace.

**Prose that describes the deleted code**, in the same commit:

- `src/cljc/yin/vm/docs/yin.repl.md` — the v1 usage guide that `README.md:163`
  links as *the* Yin REPL guide. Replace its content with the v2 guide's
  entry points or repoint the README link to `yin.repl.md`; do not leave a
  guide for a deleted program as the linked one. `README.md:114` also names
  a `:yin-repl` alias that does not exist in `deps.edn` today; fix while there.
- `docs/design/yin-repl-design.md` — v1's design; status note at the top.
- `docs/design/yin.repl.implementation-plan.md:691-697,702-708` — says v1
  `repl.cljc`, `runner.clj`, `flutter.cljd` are untouched and the Flutter
  widget is out of scope; status note that this plan did both.
- `docs/design/yin.vm-consumers.implementation-plan.md:475-483,511-520` —
  the *Boundary* rows this plan clears; status note.
- `docs/design/dao.runtime.implementation-plan.md:219-223,352-354` —
  strike `yin.repl`, `dao.await` and `yin.vm.parity-test` from the R4
  gate and record that `runtime_adapter.cljc`, `runtime_adapter_test.cljc`
  and `runtime_regression_test.cljc` were deleted here; **R4 is open**.
- `docs/design/yin.vm.divergence-register.md:26-33` — change 1 says
  "v1's REPL still has only `:ast-walker`"; there is no v1 REPL.
- `docs/design/telemetry-ui-design.md`, `docs/design/vm-telemetry-design.md`
  — status notes: the servers and the emit path are deleted; v2 telemetry is
  owed (D2).
- `src/cljc/yin/vm/docs/` — Phase 0's doc sweep lists which of the remaining
  files describe the v1 VM specifically (as opposed to the shared AST/datom
  schema) and each gets a one-line status note, not a rewrite.

**Criteria:**

- All three Phase 0 sweeps return only hits under `docs/`, `collab/`,
  `docs/orchestrator-log.md` and the documented non-hits.
- `clj -M:test`, the shadow `:test` and `:demo` builds, `clojure -M:cljd
  test` and `bb test` (which builds the R5 Dart peer first) pass; `Testing
  yin.repl.embed-test`, `yin.vm.parity-test`, `yang.clojure-test`,
  `dao.stream.ws.browser-test` appear in the Node output.
- `clj -M:clj-yin-repl`, the `:yin-repl` node build and
  `clj -M:cljd-yin-repl` start; `--telemetry` is rejected with text that
  names no v1 program.
- The U2 Flutter smoke and the U4 browser check pass against the tree with
  v1 gone.
- `dao.runtime` R4's gate condition — "v1 `yin.vm.engine` no longer requiring
  `dao.runtime`" — is true because `engine.cljc` no longer exists; nothing
  under `src/` requires `dao.runtime` except the three drivers R4 deletes.

### Phase 0 — pre-checks, before U1

1. The three sweeps, and every hit is a census row or a documented non-hit:
   ```
   # namespaces, every host and config
   grep -rnE "\b(dao\.await|yin\.repl|yin\.vm|yin\.vm\.(ast-walker|engine|ffi|runtime-adapter|stream-driver|telemetry|telemetry-server))(\s|\]|\)|/|$)" \
     src test bin deps.edn bb.edn shadow-cljs.edn public lib/main.dart \
     | grep -vE "yin\.(vm|repl)\.v2|dao\.await\.v2"
   # the wire, not the namespace: v1 RPC and ws clients that only a v1 server answers
   grep -rnE "dao\.stream\.(apply|ws|rpc)" --include='*.cljs' --include='*.cljd' src
   # launchers, aliases, and prose that claims a v1 entry exists
   grep -rnE "yin_repl_main|clj-yin-repl\b|cljs-yin-repl\b|cljd-yin-repl(-build)?\b|telemetry-server|yin\.repl for telemetry" \
     deps.edn bb.edn shadow-cljs.edn bin test src docs README.md
   ```
2. Capture the parity column (D4): run the four parity deftests' v1 side once
   and record the values in the corpus, in the U5 commit.
3. Confirm `v2_test.cljc` covers `ast_conversion_test.cljc`'s two cases.
4. Doc sweep of `src/cljc/yin/vm/docs/` for U6's status notes.
5. Record the owner's D2 (and D3) answer in this document before U6.

### Phase 2 — prose that names what was deleted, outside the deletion commit

Historical documents that cite a v1 alias or launcher as a way to run
something get a one-line status note: `docs/design/yin.vm.streams-all-the-way-down.md`,
`docs/design/yin.vm-portability.md`, `docs/agy-test.md`, and whatever the
Phase 0 doc sweep adds. Living documents are corrected in U6.

## Completion criteria

- Built (6): `embed.cljc`, `v2/flutter.cljd`, `v2_embed_test.cljc`,
  `ws/browser.cljs`, `ws/browser_test.cljs`, `yin_repl.cljs`.
- Migrated (14): `core.cljc`, `dao_gui.cljd`, `solar_system.cljd`,
  `dao_gui.md`, `demo.cljs`, three `yang` tests, `parity_test.cljc`,
  `module_test.cljc`, `v2_build_test.clj`, `test_utils.cljc`, the two
  `telemetry-text` sites.
- Deleted (27): the 2 `dao.await` files and the 25 in U6.
- Config: `deps.edn` −5 aliases; `shadow-cljs.edn` −2 builds.
- Prose: the U6 list and Phase 2.
- Unchanged but verified: `telemetry_viewer.cljs` and its build compile;
  `yin.module`, `dao.runtime` untouched; `:telemetry-viewer` build kept.
- All lanes green; the Flutter smoke and the browser check pass.
- `dao.runtime` R4 is unblocked and says so.

## Risk and scope boundaries

**Bigger than believed:** the telemetry gap (D2) and the browser REPL demo
(D3). Together they are the difference between "three consumers" and "a
feature v2 never had plus a public demo with no server". Both are surfaced
as decisions rather than absorbed. The v1 VM's test consumers add a unit
(U5) the brief did not have.

**Smaller than believed:** the widget port. The v2 stack already serves from
a Dart process, the host adapter exists, and `serve!` is testable without a
socket; `flutter.cljd` is 144 lines of which the Flutter-specific half ports
nearly verbatim. `dao.await` is exactly the no-op the brief expected.

**Manual verification, stated plainly.** Two checks cannot run in any test
lane: the Flutter widget (needs a device or emulator and a picker
selection) and the browser client's DOM socket (the Node lane has no
`WebSocket` global). Both are reduced to the smallest manual step by moving
everything else into `embed.cljc` and the adapter, which the lanes do cover.

**Ownership of shared state.** The widget's timer is the only writer of the
endpoint atom and the only caller of `embed/step`; `stop-server!` does not
step. The browser client's interval is the only owner of the driver state;
the Eval button only appends. Both are the driver's one-step-owner rule
(`yin.repl.implementation-plan.md`, *The REPL driver*), which the
review of any implementation should check first.

**Out of scope, explicitly:** the v1 transport deletion —
`dao.stream.{apply, file, file-input-stream, file-output-stream, http, link,
ringbuffer, udp, ws}`, `dao.stream.rpc.*`, their cljd siblings, `yin.io`'s
file transports, `dao.gui.event`, `dao.postgraphics.terminal`, `agent.tools`,
the telemetry viewer, `yin.module`, `datomworld.ws_demo_server`,
`ws_client_demo` — per `dao.stream.md:803-812`. This plan removes exactly
these entries from that list: the v1 VM lineage (all of it), and v1
`yin.repl` with its widget, servers and browser client. `datomworld.demo`
still requires v1 `dao.stream` through `telemetry_viewer.cljs` after this
**Status (2026-09-17):** The v1 transports and remaining consumers were deleted/migrated under `dao.stream.v1-retirement.implementation-plan.md`.

plan. Also out: the three `v2` → unsuffixed renames (D5); `dao.runtime` R4
itself (opened, not executed); a v2 walker benchmark.

## Boundary — what is owed elsewhere

| owed | by | recorded where |
|---|---|---|
| v2 telemetry: the emit path per the divergence register `:280-295`, a `:telemetry` option the v2 VMs accept, `(telemetry)` and `--telemetry-stream` on `yin.repl`, a served telemetry stream | its own plan, `yin.vm.telemetry.implementation-plan.md`, not written | D2; this table |
| the owner's answer on D2 (default vs Gate T) and D3 (port vs retire) | the owner, before U6 | Phase 0 item 5 |
| `dao.runtime` R4 — delete `dao.runtime`, its three drivers and their tests; decide the rename | the dao.runtime plan; **gate open after U6** | `dao.runtime.implementation-plan.md:352-370` |
| `dao.await`, `dao.runtime`, `dao.stream`, `yin.vm`, `yin.repl` → unsuffixed | one rename wave, when the transport item lands | D5; `dao.stream.md:790-796` |
| telemetry viewer, `yin.module`, the v1 transports | `dao.stream.v1-retirement` | done 2026-09-17 |
| status notes in `src/cljc/yin/vm/docs/` beyond the Phase 0 list | this plan, Phase 2 | above |
| `README.md:77` names `datomworld.demo.mr-clean` as the launched demo; the entry opens a picker | optional one-line fix | this table |

## Revision history

- **2026-09-16, architect r1.** First draft. Census: 50 files against the
  brief's 11. Two brief claims corrected: v2 telemetry emission is a
  rejecting stub, not an existing path (D2, owner decision), and the
  telemetry viewer plus the browser REPL demo are v1-*wire* consumers a
  namespace grep misses (D3, port before delete). Widget split into a
  Flutter-free `embed` composition and a thin view (D1), with the v1
  `(reset)`-drops-primitives defect named and not reproduced. Parity test
  pinned rather than deleted (D4). `dao.await` rename deferred to the
  rename wave (D5). `mr-clean` checked and found fully merged. Test consumers
  of the v1 VM (`yang` ×3, `module_test`, orphan bench, `ast_conversion`)
  added as U5. `dao.runtime` R4 identified as opened by U6.
