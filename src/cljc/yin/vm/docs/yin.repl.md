# Yin REPL Usage Guide

The DaoStream Yin REPL: a local shell, a `connect` that reaches a remote
one over WebSockets, a `serve!` that answers, and nothing else. It runs on
`yin.vm` and requires no legacy namespaces.

## Starting and connecting

The entry points are the REPL aliases:

```bash
clj -M:clj-yin-repl --port 8080 --headless
```

For ClojureDart (`cljd`), use the `cljd-yin-repl-build` alias to compile the Dart source, and then run the generated executable natively via `dart run`:

```bash
clj -M:clojuredart:cljd-yin-repl-build compile
dart run bin/yin_repl_main.dart --port 8080 --headless
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

## Protocol and Architecture

**`connect` returns immediately and reports its outcome when known.**
`connect` composes the client boundary, attaches, and answers at once with
`Attaching to …`; the driver prints `Connected to …` when the boundary
reports `/established`, and reports `/not-found` (an authoritative disclaimer,
not retried) or a reachability failure (which may succeed on retry) when that
is what happened. No evaluation blocks on a promise, on any host.

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

**There is no `(telemetry)` command.** Telemetry is not part of this
REPL architecture in any form. `(telemetry)` is answered with an unsupported
notice, and `--telemetry` / `--telemetry-stream` are rejected rather than
ignored. There is no `ws://` telemetry sink.

**Evaluation runs on session-owned program media.** The REPL session
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
- **`stream/take!` is gone.** Programs use `cursor` and `next!` on the
  `stream` module, which this REPL registers.
- **Park-on-full backpressure is absent under this composition.** The media
  this REPL supplies are ring buffers that evict rather than answering `full`,
  so writers never park. The VM itself remains total over `full`.

## Server behavior

`--port` serves one shared shell: every connected client
evaluates against one serially threaded REPL state, and two clients each get
their own answers. That shell is the server's own local prompt's shell — the
one step owner threads the same value through both, so a definition typed at
the server's `yin>` prompt answers a remote request in the same tick, and a
definition a remote client makes is visible at the local prompt on the next
tick. The endpoint evaluates locally or reports that it does not proxy.
`--host 127.0.0.1` remains the default boundary.

## Publishing and loading code over the DHT

With `--index-store dht:<dir>` the code index is the durable, exclusively
locked directory store with a `dao.space.dht` node composed over it: the
node joins the DHT, shares the store's content when told to, and fetches
what a `require` asks for and the node does not hold. The `--dht-*`
flags are refused with any other store, with one exception:
`--dht-keygen` composes nothing, writes its key file, and exits, so it
needs no `--index-store` at all.

**The flags.** `--dht-peer host:port` (repeatable) names the bootstrap
contacts; the host must be an IP literal, and no peer means a solo node
that opens no socket, whose publications stay durable in the directory
and acknowledged by no one. `--dht-publish` shares everything the
directory holds with any peer that asks for an address; without it the
node fetches only. `--dht-bind ip` and `--dht-port p` set the socket's
address, loopback and ephemeral unless given, and need a peer;
`--dht-max-inbound-bytes n` bounds the payload the node accepts from
peers, 64 MiB by default. `--dht-manifest :segment/...`, with or without
its leading colon, hands the node a remote index manifest to hydrate
before the first evaluation, and needs a peer too. `--dht-key file` loads
the publisher's stable Ed25519 key file, and `--dht-principal hex`
(repeatable) declares a publisher whose signed names this node honors:
the public key's 64 lowercase hexadecimal digits alone, without the
`ed25519:` prefix the banner prints before them. `--dht-keygen file`
writes a new key file, from the host CSPRNG, and exits with status 0.
A bind that fails, or a hydration that cannot complete, refuses the
shell with exit status 1 instead of composing over an empty index.

```bash
clj -M:clj-yin-repl --index-store dht:/tmp/yin-a --dht-peer 127.0.0.1:4001 \
    --dht-publish --dht-key /tmp/yin-a.key
```

The banner states, before the node steps once and so before anything is
shared, what will be shared: that publishing is on and everything in the
directory's content path will be shared, or that the node fetches only.
With `--dht-key` it also names the principal that signs every name
published here, as `ed25519:` before the very digits `--dht-principal`
takes (`dht: names published here are signed by principal ed25519:...
(key from <file>)`); without one, that `(yin.link/publish ...)` is
refused while names are still read, resolved, loaded and linked. The
node's first ticks print where the socket bound (`dht: node ...
listening on 127.0.0.1:53812; peers: ...; publishing`, or `fetch-only`).

**Publishing a module by signed name.** `(require (quote yin.link))`
installs the host module. `(yin.link/publish (quote my.lib) (quote [f]))`
derives the module from this session's indexed code, so every export
must already be defined at the prompt: an export with no defining
program refuses `:yin.link.publish/undefined-export`. The defining
programs of the exports and, to a fixed point, of the free names they
read are minted into all four code formats under one schema-1 manifest
and materialized into the index store content-addressed. The answer
names the module, its manifest address, and the linker's own verdict per
format:

```clojure
yin> (def f (fn [x] (+ x 4200)))
yin> (require (quote yin.link))
yin> (yin.link/publish (quote my.lib) (quote [f]))
{:module 'my.lib, :address :segment/..., :links {...}}
```

The name binding is a set of Ed25519-signed envelopes binding the name to
that manifest address, committed through the session's indexer as one
transaction whose HEAD write announces the publication. The node then
reports the result: `dht: published :segment/... (N blobs)` followed by
`acknowledged: sent to N peers`, or a PARTIAL or NOT acknowledged line
naming the count of blobs not sent, the first reason, and whether it is
retrying. A retrying publication repairs in the background while the
node is open; a line that says `not retrying` will not repair: a
publication with no retryable failures is final, and an older repair can
be displaced by a newer one. Republishing a name at another manifest
retracts the standing assertion first. `:links` is per format,
and a format it refused at publish time (an export reading a module-level
definition from inside a lambda refuses the tree format) refuses at
require time too.

**Loading it in another yin.repl.** Start the reader against a peer, with
the publisher's index manifest and its principal:

```bash
clj -M:clj-yin-repl --index-store dht:/tmp/yin-b --dht-peer 127.0.0.1:53812 \
    --dht-manifest :segment/... --dht-principal 9d61b19d...
```

The principal on the command line is the publisher's 64 hex digits
alone, without the banner's `ed25519:` prefix. Hydration runs before the
first evaluation: typed lines wait in the input
medium until the reader prints `dht: hydrated :<manifest>`, with the
datom and blob counts, and `evaluation admitted`. Then require by name:

```clojure
yin> (require (quote my.lib))
;; require pending: my.lib (link N); lines typed meanwhile ...
yin> 'my.lib
yin> (my.lib/f 1)
4201
```

A require whose closure the node does not hold prints the
`;; require pending: ...` line and parks: the node fetches the module's
closure from the peers, the parked require is re-checked when the load
ends, lines typed meanwhile run when it completes, and `(abandon)` gives
it up. The require answers the module's name and the export evaluates.
Each VM's session links the module itself, so after `(vm :type)` require
the name again: the same published name evaluates on all four VMs.
`(yin.link/names)` answers the names this node resolves, each with its
manifest address and the principal that asserted it.

**Why a required name can be trusted.** Content is content-addressed,
and every hop re-verifies it: a blob fetched from a peer is checked
against the address it was asked for before it enters the local store,
and the closure walk re-checks the manifest, every tree row, every
derivation record and every image against its own address. A manifest
links at a name only if it declares that name. Names resolve only from
signed assertions of declared principals: an undeclared publisher, or a
proof that does not verify, refuses the require `:absent` with the
reasons as data. The same address asserted by several declared
principals resolves with every asserter named; two distinct addresses
refuse `:ambiguous-name`. A failed load is refused and forgotten, so a
later require starts a new load. The link source reads only the node's
own store, and the directory's lock keeps one owner per directory. A
retraction whose target no loaded snapshot holds is reported once,
globally, by `(yin.link/names)`, never as a per-name diagnostic.
`docs/design/yin.vm.linker.dht.md` is the contract for all of it.

**The plain-Clojure path.** A non-REPL client takes the same path through
plain functions: `dao.space.dht/join` over a store, `load-index` for a
manifest address (step the node with `dao.space.dht/step`; `load-status`
answers `:loading`, `:loaded`, or `:failed` with the reason as data),
`yin.vm.linker.dht/names` to resolve names under an authority of
declared principals (`yin.vm.linker.dht/authority`),
`yin.vm.linker.dht/load-module` for a module's closure, and
`yin.vm.linker.dht/link` for one format's image; every failure answers
the data of that design's section 9. At the prompt,
`(require (quote dao.space.dht))` answers `load-index`, `load-status`,
`q`, `retry`, `cancel`, `load-module` and `module-status` as host
functions over the shell's own node; the name fold itself is the
prompt's `(yin.link/names)`.

**Operational notes.** Keep the key file: a lost key can never sign
again, and a new key is a new principal. Receivers verify names against
the publisher's principal, not against where the bytes came from, so
hand the principal out with the network address and replace the
declaration when a key rotates. The Node reader the process tests spawn
is a build artifact: `bb build:yin-repl-node` writes `target/yin-repl.js`,
and a missing build fails `test/yin/repl/dht_process_test.clj`. A fresh
worktree must run `clj -M:antlr-gen` before JVM test runs: the generated
parser path, `build/antlr/python3/classes`, is on the classpath and
absent until generated.

See [`docs/design/yin.repl.implementation-plan.md`](../../../../docs/design/yin.repl.implementation-plan.md)
for the plan this REPL implements and the contract documents it is subordinate
to.
