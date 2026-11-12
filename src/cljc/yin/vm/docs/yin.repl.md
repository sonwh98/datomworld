# yin.repl: Quick Start & User Guide

`yin.repl` is the primary user interface into datom.world. It is a cross-host
shell (JVM, Node, and Dart) that evaluates code on `yin.vm`, serves and
connects to remote shells over WebSockets, and composes with `dao.jing.dht` to
publish and load modules by signed name over a distributed hash table (DHT).

---

## 1. Quick Start: Launching the REPL

### Local In-Memory REPL
Start an interactive prompt on the JVM with an in-memory index store:

```bash
clj -M:clj-yin-repl
```

> **Note for fresh clones:** `yin.repl` loads the generated ANTLR Python3 parser on startup. Run `bb gen:antlr` (or `clj -M:antlr-gen` for JVM) once before starting the REPL or running tests.

### Durable Local Store
To persist definitions and evaluated code across restarts, pass a `file:` directory store:

```bash
clj -M:clj-yin-repl --index-store file:/tmp/my-repl-store
```

### Client / Server over WebSockets
Start a headless or interactive server listening on a WebSocket port:

```bash
clj -M:clj-yin-repl --port 8080 --headless
```

From another REPL prompt, attach to the running server:

```clojure
yin> (connect "daostream:ws://localhost:8080/repl")
Attaching to daostream:ws://localhost:8080/repl
Connected to daostream:ws://localhost:8080/repl
```

- An absent URL path defaults to `/repl`; an explicit `/` remains `/`.
- The wire codec defaults to CBOR. Use `--codec <cbor|transit>` on the server, and `(connect url :codec :transit)` or `:cbor` at the prompt. Both ends must match.
- `--port` listens on all interfaces (`localhost` and the machine's IP) with **no authentication**—run it only on a trusted network. There is no `--host` flag.

### Other Hosts (Node & Dart)
`(host)` prints the runtime the shell runs on (`Host: JVM 21.0.5`, `Host: Node v25.6.1`, `Host: Dart`).

- **ClojureScript (`cljs` on Node):**
  ```bash
  clj -M:cljs-yin-repl --port 8080 --headless
  # Or compile to a standalone Node script:
  npx shadow-cljs release yin-repl
  node target/yin-repl.js --port 8080 --headless
  ```

- **ClojureDart (`cljd`):**
  ```bash
  clj -M:clojuredart:cljd-yin-repl-build compile
  dart run bin/yin_repl_main.dart --port 8080 --headless
  ```

---

## 2. Working at the Prompt

### Switching Language Frontends
The REPL ships with three frontends on every host: `:yang/clojure` (default), `:yang/php`, and `:yang/python` (ANTLR multi-target parser, revision f4). Switch languages using `(lang <id>)` with a qualified keyword:

```clojure
yin> (lang :yang/python)
"Switched to :yang/python"
yin> print("hello")
hello
```

- Selection requires a qualified id (`(lang :yang/python)` or `[id revision]`). Unqualified keywords like `(lang :python)` return `Error: unavailable-parser: ...`.
- The Python frontend displays printed output followed by the value of the last top-level bare expression statement (rendered as Python `repr`; assignments or `None` display nothing). Frontend state is preserved across submissions and cleared on `(reset)` or `(vm ...)`.
  - *Known edges:* Bare expressions inside compound statements (`if`/`for`) do not display; values captured before a later statement raises print after the error text; string `repr` escapes only code units `< 32` and `127`.

### Switching Evaluator VMs
`(vm :type)` selects one of four evaluators:
- `:semantic` (default) — linear semantic VM
- `:ast-walker` — tree walker
- `:stack` — de Bruijn stack kernel
- `:register` — de Bruijn register kernel

Switching rebuilds the session and clears value history.

---

## 3. Distributed Swarm Quick Start (DHT)

With `--index-store dht:<dir>` (or the `dht` subcommands), the REPL composes a `dao.space.dht` node over a durable, exclusively locked directory store.

A publication is acknowledged once at least two peers hold it (`ack-peers >= 2`). This walkthrough starts two storing peers (**B**, **B2**), one publisher (**A**), and one reader (**C**) on loopback:

1. **Start two storing peers (B and B2)** in separate terminals. They hold blobs while A publishes and need no key:
   ```bash
   clj -M:clj-yin-repl dht serve --name b  --listen 4002 --peer localhost:4001
   clj -M:clj-yin-repl dht serve --name b2 --listen 4003 --peer localhost:4001
   ```
   Wait for `dht: node ... listening on 127.0.0.1:4002; peers: 127.0.0.1:4001; fetch-only` (and `4003`).

2. **Start the publisher (A)** in a third terminal:
   ```bash
   clj -M:clj-yin-repl dht init --name a --listen 4001 \
       --peer localhost:4002 --peer localhost:4003
   ```
   The first run writes an owner-only Ed25519 key to `~/.yin/a.key` and prints its principal (`ed25519:9d61b19d...`). Existing key files are never overwritten.

3. **Define and publish a module** at A's prompt:
   ```clojure
   yin> (def f (fn [x] (+ x 4200)))
   yin> (require (quote yin.link))
   yin> (yin.link/publish (quote my.lib) (quote [f]))
   {:module 'my.lib, :address :segment/..., :links {...}}
   ```
   Wait for `dht: published :segment/...` and `acknowledged: sent to 2 peers`. (With only one peer, A reports `NOT acknowledged: too few peers, sent to 1 of 2`, though A's own copy remains durable.)

4. **Copy A's join token** printed after its socket binds:
   `dht: join token: yin:127.0.0.1:4001/<principal>` (printed only on loopback binds).

5. **Start a reader (C)** using the join token:
   ```bash
   clj -M:clj-yin-repl dht join --name c yin:127.0.0.1:4001/9d61b19d...
   ```
   C admits evaluation immediately. Requiring `my.lib` waits for A's head if not yet installed, fetches the module closure, and links it:
   ```clojure
   yin> (require (quote my.lib))
   'my.lib
   yin> (my.lib/f 1)
   4201
   ```

> **Note:** `dht join` is loopback-only in this version. For cross-host readers, start with `--dht-manifest` and `--dht-principal` (see CLI Flags Reference).

---

## 4. Saved State & Node Directories

A node remembers its startup flags in `~/.yin/<name>/state.edn` (`--name`, default `node`; or `--dir` to specify a directory directly). A bare `clj -M:clj-yin-repl --name b` resumes that node:

```bash
clj -M:clj-yin-repl dht serve --name b --listen 4002 --peer localhost:4001  # saved
clj -M:clj-yin-repl --name b                                                 # resumes it
clj -M:clj-yin-repl --name b --dht-peer 127.0.0.1:4003                       # updates peer & saves
```

- **What is saved:** `--index-store`, `--vm`, `--port`, `--headless`, `--dht-peer`, `--dht-publish`, `--dht-bind`, `--dht-port`, `--dht-max-inbound-bytes`, `--dht-key`, `--dht-principal`, and `--dht-follow`. (`--dht-manifest`, `--dht-keygen`, and prompt `(vm :type)` changes are not saved.)
- **Overriding & Resetting:**
  - Repeated flags (`--dht-peer`, `--dht-principal`, `--dht-follow`) replace their saved lists.
  - `dht init` saves publishing and a key; `dht serve` and `dht join` clear both.
  - `--reset` forgets saved state and starts fresh from the command line; `--no-state` neither reads nor writes `state.edn`.
- **Refusals & Locking:** Clashing flags (e.g., saved `--dht-*` flags with `--index-store mem`) refuse startup and name `--reset`. Give each process its own `--name` or `--dir`. ClojureDart has no home directory, so pass `--dir` explicitly to save state.

---

## 5. CLI Flags Reference

Run `clj -M:clj-yin-repl --help` (or `-h`) on any host for the full help text.

### General Flags
- `--index-store mem|file:<dir>|dht:<dir>` — Content store backend (`mem` by default).
- `--vm ast-walker|semantic|stack|register` — Initial evaluator (`semantic` by default).
- `--port n`, `--headless`, `--codec cbor|transit` — Serve the shared shell over WebSocket.
- `--name n`, `--dir d`, `--reset`, `--no-state` — Node directory and saved state controls.

### DHT Subcommands & Flags
Subcommands expand directly to raw `--dht-*` flags:
- `dht serve` ➔ `--index-store dht:<dir>` + `--dht-peer` + `--dht-port`
- `dht init` ➔ `dht serve` + `--dht-publish` + `--dht-key` (generates key if missing)
- `dht join <token>` ➔ `--index-store dht:<dir>` + `--dht-peer` + `--dht-principal` + `--dht-follow`
- `keygen [--name n | file]` ➔ Generates an Ed25519 key file and exits.
- `--listen [ip:]port` ➔ Shorthand for `--dht-bind` + `--dht-port`.

Raw DHT flags:
- `--dht-peer host:port` (repeatable) — Bootstrap IP literal + port. Without a peer, the node runs solo with no socket.
- `--dht-publish` — Share local store content with peers (otherwise fetch-only).
- `--dht-bind ip`, `--dht-port p` — Socket bind address (default loopback, ephemeral port).
- `--dht-key file`, `--dht-keygen file` — Publisher Ed25519 private key file.
- `--dht-principal hex` (repeatable) — 64-char lowercase hex public key of a trusted publisher.
- `--dht-follow hex@host:port` (repeatable) — Follow a declared principal's head board on loopback.
- `--dht-manifest :segment/...` — Pin and hydrate a specific remote manifest before admitting evaluation (suspends head following).
- `--dht-max-inbound-bytes n` — Max peer payload size (default 64 MiB).
- `--dht-trust` — Configure linker admission policy.

Values beginning with `--` are treated as missing values; prefix literal paths with `./` (e.g. `./--name`).

---

## 6. Protocol, Trust & Operational Notes

### Shell Protocol & VM Semantics
- **`connect` is non-blocking:** Returns `Attaching to …` immediately, then reports `Connected to …`, `/not-found`, or reachability failure.
- **Step-driven:** One `repl-step` owns all REPL and RPC state. Input typed while a remote request is outstanding is queued; local control commands (`disconnect`, `quit`, `help`, `repl-state`) bypass the queue.
- **Connection loss vs `stop!`:** Socket death detaches outstanding requests (`;; remote request N lost: …`) and allows reattaching to the same deposit medium. Server `stop!` ends the service-lifetime stream permanently.
- **Session-owned media:** The session owns the ingress ring buffer (capacity 4096) for all VMs. Because ring buffers evict rather than park on `full`, writers never block; however, evicted batches cause a fatal gap requiring `(reset)`.
- **No telemetry:** `(telemetry)`, `--telemetry`, and `--telemetry-stream` are unsupported and refused.
- **`yin.vm` divergence:** User-defined macros evaluate via `yin.vm.macro` on `program-in`; `stream/take!` is replaced by `cursor` and `next!`.

### Following Heads & Trust
- **Head board:** A publisher deposits signed HEAD traces at `/head` on its WebSocket port. Followers verify, fetch, and persist the installed head in `<dht dir>/heads.edn` before use.
- **Inspecting heads:** `(require (quote yin.head))` provides `(yin.head/heads)` and `(yin.head/moved)`.
- **Trust & verification:** Every fetched blob, manifest, tree row, and image is content-addressed and re-verified against its hash. Names resolve only from signed assertions of declared `--dht-principal` keys.
- **Pending requires:** A `require` whose closure is not yet local prints `;; require pending: ...` and parks until peers deliver it (or `(abandon)` cancels it). Switching VMs via `(vm :type)` requires re-requiring modules for the new session.
- **Plain-Clojure / Non-REPL API:** Clients can drive the DHT directly via `dao.space.dht/join`, `step`, `load-index`, `load-status`, and `yin.vm.linker.dht/names`, `load-module`, `link`. At the prompt, `(require (quote dao.space.dht))` exposes `load-index`, `load-status`, `q`, `retry`, `cancel`, `load-module`, and `module-status`.
- **Operational notes:** Keep key files outside `/tmp` and never delete a node's directory. Closing a node discards its unacknowledged retry queue. For Node process tests (`test/yin/repl/dht_process_test.clj`), build `target/yin-repl.js` with `bb build:yin-repl-node`.

---

## 7. Architecture & Composition (`yin.repl` vs `yin.shell`)

Per [`docs/design/yin.repl.dht-composition.md`](../../../../../docs/design/yin.repl.dht-composition.md), the engine (`yin.repl`) depends strictly and solely on `dao.jing` as a generic content storage contract. It has zero static or runtime dependencies on `dao.jing.dht` or any specific transport.

All DHT and network-facing capabilities are provided outside the engine by **`yin.shell.*`**:
- **`yin.shell.main`**: Outer host entry point that bundles the engine host driver with composition hooks (`deps.edn`'s `:clj-yin-repl`, shadow-cljs `:yin-repl`, and ClojureDart).
- **`yin.shell.cli`**: Command-line flag parsing for `--dht-*` flags and subcommands (`dht init|serve|join`).
- **`yin.shell.store`**: Registers and instantiates the `dht:<dir>` store scheme alongside engine-native `mem` and `file:<dir>`.
- **`yin.shell.session`**: Manages the composite session `{:engine … :node … :key …}`.
- **`yin.shell.query`**: Connects link publishing, network queries, and service adapters to the DHT node.

### Embedding the DHT Composition Programmatically

A host driver can pass the DHT composition bundle into `yin.repl.main/main!`:

```clojure
(require '[yin.repl.main :as repl.main]
         '[yin.shell.main :as shell.main])

(repl.main/main! ["--index-store" "dht:/path/to/store"
                  "--dht-listen" "127.0.0.1:4001"]
                 shell.main/composition)
```

Alternatively, open a DHT store handle directly via `yin.shell.store`:

```clojure
(require '[yin.shell.store :as shell.store])

(let [{:keys [handle node]} (shell.store/open {:type :dht :dir "/path/to/store"} opts)]
  ;; :handle is the dao.jing content store for yin.repl
  ;; :node is the composition-owned DHT node
  ...)
```

---

## 8. Design Documents

The contracts this guide follows, all under `docs/design/`:

- [`yin.repl.dht-composition.md`](../../../../../docs/design/yin.repl.dht-composition.md):
  the engine depends only on `dao.jing`; DHT composition and adapter separation.
- [`yin.vm.linker.dht.md`](../../../../../docs/design/yin.vm.linker.dht.md):
  publishing, resolving and loading modules by signed name over the DHT.
- [`yin.vm.linker.dht.head.md`](../../../../../docs/design/yin.vm.linker.dht.head.md):
  following a publisher's index HEAD by principal.
- [`yin.repl.dao.space-index.md`](../../../../../docs/design/yin.repl.dao.space-index.md):
  automatic code indexing on evaluation, and the `dht:<dir>` store.
- [`yin.repl.link-policy.md`](../../../../../docs/design/yin.repl.link-policy.md):
  what a pending `require` does when nobody answers.
- [`dao.jing.dht.md`](../../../../../docs/design/dao.jing.dht.md): the DHT node
  the store composes over.
- [`yin-repl-design.md`](../../../../../docs/design/yin-repl-design.md): the v1
  REPL's design, kept for history; the live REPL is this one.
