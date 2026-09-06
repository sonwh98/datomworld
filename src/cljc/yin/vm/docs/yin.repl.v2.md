# Yin REPL v2 Usage Guide

The DaoStream v2 Yin REPL: a local shell, a `connect` that reaches a remote
one over v2 WebSockets, a `serve!` that answers, and nothing else. It runs on
`yin.vm.v2` and requires no v1 namespace. `yin.repl.md` describes the v1 REPL;
this document describes only what differs.

## Starting and connecting

The entry points are the v2 aliases; the flags behave as they do in v1:

```bash
clj -M:clj-yin-repl-v2 --port 8080 --headless
```

For ClojureDart (`cljd`), use the `v2-build` alias to compile the Dart source, and then run the generated executable natively via `dart run`:

```bash
clj -M:cljd-yin-repl-v2-build compile
dart run lib/cljd-out/yin/repl/v2.dart --port 8080 --headless
```

For ClojureScript (`cljs` on Node), you can run it directly using the shadow-cljs alias:

```bash
clj -M:cljs-yin-repl-v2 --port 8080 --headless
```

Or you can compile it to a standalone Node script:

```bash
npx shadow-cljs release yin-repl-v2
node target/yin-repl-v2.js --port 8080 --headless
```

```clojure
yin> (connect "daostream:ws://localhost:8080/repl")
Attaching to daostream:ws://localhost:8080/repl
Connected to daostream:ws://localhost:8080/repl
```

An absent URL path means `/repl`; an explicit `/` remains `/`.

## What differs from v1

**`connect` returns immediately and reports its outcome when known.**
v1's `connect` resolved over a promise (JVM) or future (Dart) and printed
`Connected to …` only once the round trip completed. v2's `connect` composes
the client boundary, attaches, and answers at once with `Attaching to …`; the
driver prints `Connected to …` when the boundary reports `/established`, and
reports `/not-found` (an authoritative disclaimer, not retried) or a
reachability failure (which may succeed on retry) when that is what happened.
No evaluation blocks on a promise, on any host.

**`(vm :type)` offers `:ast-walker` only, and the default changed.**
v1 defaulted to `:semantic`. The remaining evaluators (`:semantic`,
`:register`, `:stack`, `:space`) follow in the `yin.vm.v2` plan; until they
land, asking for one is an error naming what is supported.

**There is no `(telemetry)` command.** Telemetry is not part of the v2 slice
in any form. `(telemetry)` is answered with a message naming the v1 REPL, and
`--telemetry` / `--telemetry-stream` are rejected rather than ignored. There
is no `ws://` telemetry sink.

**Datom-literal evaluation runs on a VM-owned v2 ingress medium.** A datom
program typed at the prompt is appended to the ast-walker's ingress ring
buffer (declared capacity 4096) and the VM ingests it between batches. A gap —
batches evicted before the VM saw them — is fatal to the current evaluation:
the loss is reported and the shell refuses further evaluation until `(reset)`,
rather than resuming as if execution were complete.

**The evaluator is step-driven everywhere.** One `repl-step` owns all REPL and
RPC state on every host; input adapters only append lines to the composition's
input medium. A remote evaluation returns immediately with a request id, and
the result prints when it completes. Input typed while a request is
outstanding is queued, not evaluated; local control commands (`disconnect`,
`quit`, `help`, `repl-state`) bypass that queue so a slow remote cannot trap
the operator.

**A killed connection is reported, never timed out.** Losing the connection
detaches every outstanding request: the driver prints
`;; remote request N lost: …` rather than holding the request against a
deadline, because the layer has no clock. The served stream is untouched by a
detach; typing `(connect …)` with the same URL reattaches through the same
client medium — the deposit medium and its cursor survive the socket's death,
and only the attachment id changes.

**`stop!` ends the served stream.** A server shutdown closes the
service-lifetime stream, and a connected client observes the ended stream —
`The stream served at … ended` — which is terminal: there is nothing to
reattach to. An ordinary socket death without the stream ending reports a
detach instead, which does reattach.

## Consequences of running on `yin.vm.v2`

These follow from the VM plan's divergence register:

- **User-defined macros stop evaluating.** `yang.clojure` compiles macro call
  sites to `:yin/macro-expand`; the ast-walker has no such branch and throws.
  `defn` still works through its native compile path.
- **`stream/take!` is gone.** Programs use `cursor` and `next!` on the v2
  `stream` module, which this REPL registers.
- **Park-on-full backpressure is absent under this composition.** The media
  this REPL supplies are ring buffers that evict rather than answering `full`,
  so writers never park. The VM itself remains total over `full`.

## Server behavior

`--port` serves one shared shell (as v1 does): every connected client
evaluates against one serially threaded REPL state, and two clients each get
their own answers. That shell is the server's own local prompt's shell — the
one step owner threads the same value through both, so a definition typed at
the server's `yin>` prompt answers a remote request in the same tick, and a
definition a remote client makes is visible at the local prompt on the next
tick. The endpoint evaluates locally or reports that it does not
proxy — v1's chain-forwarding through a server's own remote connection is not
part of this slice. `--host 127.0.0.1` remains the only boundary, as in v1.

## Coexistence

Both REPLs ship and both alias sets work: `yin.repl` and its aliases are
untouched, and `dao.stream.rpc.*` keeps serving its existing consumers.
Deleting v1 is the stream plan's end condition, once its last consumer has
migrated.

See [`docs/design/yin.repl.v2.implementation-plan.md`](../../../../docs/design/yin.repl.v2.implementation-plan.md)
for the plan this REPL implements and the contract documents it is subordinate
to.
