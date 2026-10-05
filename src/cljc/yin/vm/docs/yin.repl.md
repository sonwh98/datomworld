# yin.repl: the Interface to datom.world

`yin.repl` is the primary user interface into datom.world. It started as a
REPL and still opens with a prompt, but it is more than a REPL: one program
that is a shell evaluating code on `yin.vm`, an endpoint (`serve!` answers
other shells, `connect` reaches a remote one over WebSockets), and a node
that publishes code by signed name and loads other nodes' code over the
DHT. It runs on `yin.vm`, requires no legacy namespaces, and runs on the
JVM, Node and Dart.

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

## Saved state

A node remembers how it was started. The flags it starts with are saved to
`state.edn` in its node directory, `~/.yin/<name>` (`--name`, default `node`;
`--dir` names the directory instead), and a bare `yin-repl` starts that node
again:

```bash
yin-repl dht serve --name b --listen 4002 --peer localhost:4001   # saved
yin-repl --name b                                                  # resumes it
yin-repl --name b --dht-peer 127.0.0.1:4003                        # changes the peer
```

The banner says what happened: `state: saved to ...` on a first run,
`state: resumed from ... (--index-store dht:..., ...)` on a restart, and
`; the command line changed --dht-peer; saved` when flags overrode it. The
file is plain EDN and may be edited by hand:

```clojure
{:version 1
 :config {:index-store "dht:/home/me/.yin/b"
          :dht-peer ["127.0.0.1:4003"]
          :dht-port "4002"}}
```

- **What is saved:** `--index-store`, `--vm`, `--port`, `--headless`,
  `--dht-peer`, `--dht-publish`, `--dht-bind`, `--dht-port`,
  `--dht-max-inbound-bytes`, `--dht-key` and `--dht-principal`, which is
  what the `dht` subcommands expand to. The one-shot `--dht-manifest` and
  `--dht-keygen` are never saved. Definitions are not saved here: a durable
  index store (`file:` or `dht:`) already keeps what was evaluated. The
  evaluator chosen at the prompt with `(vm :type)` is not saved; start with
  `--vm` to save one.
- **Changing it:** a flag overrides the saved value for that setting, and the
  result is saved. A repeated flag (`--dht-peer`, `--dht-principal`)
  replaces all of its saved values rather than adding to them. `dht init`
  saves publishing; `dht serve` and `dht join` clear it. A bare switch
  (`--headless`, `--dht-publish`) cannot be turned off by a flag, so use
  `--reset`.
- **`--reset`** forgets the saved state and starts from the command line
  alone; the result is saved. **`--no-state`** neither reads nor writes it,
  for a one-off run.
- **Refusals:** a state file that cannot be read, or saved flags that clash
  with the command line (saved `--dht-*` flags with `--index-store mem`), refuse
  startup, naming the file and `--reset`. A state file that cannot be written
  is a warning, and the node still starts.
- **Hosts:** ClojureDart has no home directory, so a bare `yin-repl` there
  saves nothing unless `--dir` names the node directory.
- **One node per name:** the state file is only as protected as its
  directory. `dht init|serve|join` put the store in the node directory, so the
  store's lock also keeps one process per state file. A node whose store lives
  elsewhere (`--index-store file:/tmp/a`, or the in-memory default) is not
  covered: two such processes with the same `--name` or `--dir` overwrite each
  other's file, last writer wins, and no process notices. Give each its own
  node directory: a distinct `--name`, or a distinct `--dir` when you pass one.
- **Values:** a flag's value that begins with `--` is taken as a missing
  value and refused (`--dht-peer --dht-publish`), so a path that really
  begins with `--` is written `./--name`.

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
`--port` listens on all interfaces, so the server answers on `localhost`
and on the machine's own IP, and its startup banner names both. There is
no authentication: anyone who can reach the port can evaluate code in the
shared shell, so run it on a network you trust. There is no `--host`; it is
refused rather than ignored.

## Publishing and loading code over the DHT

With `--index-store dht:<dir>` the code index is the durable, exclusively
locked directory store with a `dao.space.dht` node composed over it: the
node joins the DHT, shares the store's content when told to, and fetches
what a `require` asks for and the node does not hold. The subcommands
`dht init`, `dht serve` and `dht join` set this up without the flags
(`yin.repl.main/expand-args`); **The flags** below are the full surface
they expand to.

### Quick start

A node is given a fixed `--listen [ip:]port` and the peers it should
contact, `--peer localhost:port` or an IP literal. A contact that does not
answer does not stop the node from binding and listening, so nodes may
start in any order; a node with no peer is solo and opens no socket. State
lives in `~/.yin/<name>` (`--name`; `--dir` and `--key` override, and cljd,
which has no home directory, needs them). A publication is acknowledged
once two peers hold it (`ack-peers`, at least 2), so this walk-through puts
two storing peers **B** and **B2** on ports 4002 and 4003 and a publisher
**A** on port 4001, all on one machine. With one storing peer everything
below still works, but A reports `NOT acknowledged: too few peers, sent to
1 of 2`; readers can still fetch from A, whose own copy is durable.

1. Start **B** and **B2**, the storing peers, each in its own terminal.
   They hold other nodes' blobs while A publishes, and need no key. Each
   names A's port as its contact although A is not up yet.

   ```bash
   clj -M:clj-yin-repl dht serve --name b  --listen 4002 --peer localhost:4001
   clj -M:clj-yin-repl dht serve --name b2 --listen 4003 --peer localhost:4001
   ```

   Wait for `dht: node ... listening on 127.0.0.1:4002; peers:
   127.0.0.1:4001; fetch-only` (and 4003).

2. Start **A**, the publisher, in another terminal, naming both.

   ```bash
   clj -M:clj-yin-repl dht init --name a --listen 4001 \
       --peer localhost:4002 --peer localhost:4003
   ```

   The first run writes `~/.yin/a.key`, owner-only (directories included),
   and prints `dht: wrote a new Ed25519 key to ...; its principal is
   ed25519:9d61b19d...`. The principal is `ed25519:` followed by the key's
   public half, the `:public` value in the key file; the `:seed` is the
   private half and is never printed. An existing key file is never
   overwritten. The banner must then say that publishing is ON and name the
   same principal. `yin-repl keygen [--name n | file]` makes a key without
   starting a node.

3. At A's prompt, define and publish a module:

   ```clojure
   yin> (def f (fn [x] (+ x 4200)))
   yin> (require (quote yin.link))
   yin> (yin.link/publish (quote my.lib) (quote [f]))
   ```

   Wait for `acknowledged: sent to 2 peers`; the prompt answers at once,
   but the `dht: published` lines follow only after the acknowledgement
   deadline, up to about a minute. A line that says PARTIAL or NOT
   acknowledged is not replicated yet; if it says it is retrying, leave A
   open. A's own copy is durable in its directory either way.

4. Take the **join token** A prints with those lines, its last `dht: join
   token: yin:127.0.0.1:4001/<principal>/segment/...`. It bundles A's
   address, the principal and the index manifest (what A records in
   `~/.yin/a/HEAD`), so nothing is copied separately; use the latest one.
   It carries the address A bound, so give `--listen` a routable `ip` when
   readers are on other machines.

5. Start **C**, a reader with an empty directory, with the token:

   ```bash
   clj -M:clj-yin-repl dht join --name c yin:127.0.0.1:4001/9d61b19d.../segment/...
   ```

   After `dht: hydrated ...` and `evaluation admitted`, `(require (quote
   my.lib))` followed by `(my.lib/f 1)` answers `4201`.

Nodes may run on different hosts: a reader on the JVM, Node or Dart joins
a publisher on any of them. To use more nodes, give each its own state and
port and list any live node with `--peer`; a node may list several.

### The flags

`yin-repl --help` (or `-h`) prints every subcommand and flag and exits
with status 0, on every host.

Every node takes these, with or without a DHT: `--port n` and `--headless`
(serve the shell; see Server behavior), `--index-store
mem|file:<dir>|dht:<dir>`, `--vm ast-walker|semantic|stack|register` (the
evaluator the shell starts with, `semantic` by default), and the saved-state
controls `--name n` and `--dir d` (the node directory), `--reset` and
`--no-state` (see Saved state).

The subcommands map onto the DHT flags. `dht serve` is
`--index-store dht:<dir>` with `--dht-peer` and `--dht-port`; `dht init`
adds `--dht-publish` and `--dht-key`, making the key first as `--dht-keygen` does; `dht join` adds
the token's peer, `--dht-manifest` and `--dht-principal` (the token
accepts the principal with or without `ed25519:`). `--listen` is
`--dht-bind` and `--dht-port` together. Any other flag (`--port`,
`--headless`, `--dht-max-inbound-bytes`) passes through after the
subcommand.

`--dht-peer host:port` (repeatable) names the bootstrap
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
clj -M:clj-yin-repl --index-store dht:$HOME/.yin/a \
    --dht-peer 127.0.0.1:4002 --dht-peer 127.0.0.1:4003 --dht-port 4001 \
    --dht-publish --dht-key ~/.yin/a.key
clj -M:clj-yin-repl --index-store dht:$HOME/.yin/c --dht-peer 127.0.0.1:4001 \
    --dht-manifest :segment/... --dht-principal 9d61b19d...
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

### Publishing, loading and trust

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

**Loading by name.** A reader started with `dht join` (step 5) hydrates
before the first evaluation: typed lines wait in the input medium until
the reader prints `dht: hydrated :<manifest>`, with the datom and blob
counts, and `evaluation admitted`. Then require by name:

```clojure
yin> (require (quote my.lib))
;; require pending: my.lib (link N); lines typed meanwhile ...
yin> 'my.lib
yin> (my.lib/f 1)
4201
```

A fetch-only reader also prints `dht: published ... NOT acknowledged:
publication is off ... not retrying` lines as it records what it loads in
its own directory. They are expected: the reader shares nothing.

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

**Operational notes.** Keep the key file somewhere that lasts (not
`/tmp`): a lost key can never sign again, and a new key is a new
principal. Never delete a node's directory: it holds the node's copy.
Closing a node discards its retry queue, so a publication that was not
yet acknowledged is not retried after a restart; publish it again. Receivers verify names against
the publisher's principal, not against where the bytes came from, so
hand the principal out with the network address and replace the
declaration when a key rotates. The Node reader the process tests spawn
is a build artifact: `bb build:yin-repl-node` writes `target/yin-repl.js`,
and a missing build fails `test/yin/repl/dht_process_test.clj`. A fresh
worktree must run `clj -M:antlr-gen` before JVM test runs: the generated
parser path, `build/antlr/python3/classes`, is on the classpath and
absent until generated.

## Design documents

The contracts this guide follows, all under `docs/design/`:

- [`yin.vm.linker.dht.md`](../../../../../docs/design/yin.vm.linker.dht.md):
  publishing, resolving and loading modules by signed name over the DHT.
- [`yin.repl.dao.space-index.md`](../../../../../docs/design/yin.repl.dao.space-index.md):
  automatic code indexing on evaluation, and the `dht:<dir>` store.
- [`yin.repl.link-policy.md`](../../../../../docs/design/yin.repl.link-policy.md):
  what a pending `require` does when nobody answers.
- [`dao.jing.dht.md`](../../../../../docs/design/dao.jing.dht.md): the DHT node
  the store composes over.
- [`yin-repl-design.md`](../../../../../docs/design/yin-repl-design.md): the v1
  REPL's design, kept for history; the live REPL is this one.
