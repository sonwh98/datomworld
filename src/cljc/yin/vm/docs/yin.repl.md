# Yin REPL v2 Usage Guide

The DaoStream v2 Yin REPL: a local shell, a `connect` that reaches a remote
one over v2 WebSockets, a `serve!` that answers, and nothing else. It runs on
`yin.vm` and requires no v1 namespace. `yin.repl.md` describes the v1 REPL;
this document describes only what differs.

## Starting and connecting

The entry points are the v2 aliases; the flags behave as they do in v1:

```bash
clj -M:clj-yin-repl --port 8080 --headless
```

For ClojureDart (`cljd`), use the `v2-build` alias to compile the Dart source, and then run the generated executable natively via `dart run`:

```bash
clj -M:cljd-yin-repl-build compile
dart run lib/cljd-out/yin/repl.dart --port 8080 --headless
```

For ClojureScript (`cljs` on Node), you can run it directly using the shadow-cljs alias:

```bash
clj -M:cljs-yin-repl --port 8080 --headless
```

Or you can compile it to a standalone Node script:

```bash
npx shadow-cljs release yin-repl
node target/yin-repl.js --port 8080 --headless
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

**`(vm :type)` offers four evaluators; the default is `:semantic`.**
The shell supports `:ast-walker` (the tree walker), `:semantic` (the linear
semantic VM), `:stack` (the de Bruijn stack kernel), and `:register` (the de
Bruijn register kernel). `:semantic` is the default when no `:vm-type` is
given. Asking for any other type is an error naming what is supported.
Switching rebuilds the session and clears the value history.

Whichever VM runs, the shell never reads a value from the VM record: a
halted evaluation's value is appended to the output `dao.stream` medium as
a `:repl/result` token, after the round's prints, and the shell reads it
from there exactly as it reads printed output.

**There is no `(telemetry)` command.** Telemetry is not part of the v2 slice
in any form. `(telemetry)` is answered with a message naming the v1 REPL, and
`--telemetry` / `--telemetry-stream` are rejected rather than ignored. There
is no `ws://` telemetry sink.

**Evaluation runs on session-owned v2 program media.** The REPL session
(`make-session`), not any one VM, owns the program media for all four VMs.
Every program, including a datom program typed at the prompt, is appended
to the session's ingress ring buffer (declared capacity 4096); the expander
forwards it to the session's program medium, from which the selected VM
ingests it between batches. A gap (batches evicted before the VM saw them)
is fatal to the current evaluation:
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

## Consequences of running on `yin.vm`

These follow from the VM plan's divergence register:

- **User-defined macros evaluate correctly.** The REPL is wired through the `yin.vm.macro` stream topology. `yang.clojure` translates macros into native function calls which are intercepted by the expander on `program-in` before reaching the evaluator.
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

See [`docs/design/yin.repl.implementation-plan.md`](../../../../docs/design/yin.repl.implementation-plan.md)
for the plan this REPL implements and the contract documents it is subordinate
to.
