# yin.repl: Quick Start & User Guide

`yin.repl` is the primary user interface into datom.world. It is a cross-host
shell (JVM, Node, Dart, and Jolt) that evaluates code on `yin.vm`, serves and
connects to remote shells over WebSockets, and composes with `dao.jing.dht` to
publish and load modules by signed name over a distributed hash table (DHT).
The same CLI, flags and index-store schemes work on every host; only the
launch command differs (section 1.1).

---

## 1. Quick Start: Launching the REPL

### Launch on each host

The engine, flags and `--index-store` schemes are identical on all four hosts;
each host differs only in how the shell starts.

**JVM** (the reference host; every flag is exercised here first):

```bash
clj -M:clj-yin-repl
```

**Node** (a self-contained `node-script` build; the first build needs the
generated JS parser, which `bb build:yin-repl-node` produces):

```bash
bb build:yin-repl-node     # compiles the shadow-cljs :yin-repl build
node target/yin-repl.js --index-store mem
```

**Dart** (ClojureDart compiles the shell to a Dart script):

```bash
clj -M:clojuredart:cljd-yin-repl-build    # emits build/yin-repl/yin_repl.dart
dart run build/yin-repl/yin_repl.dart --index-store mem
```

**Jolt** (the native Chez host; the bootstrap materializes the pinned jolt and
its stdlib — run it once per checkout, then always launch through
`build/tools/jolt-env.sh` so `$JOLT_HOME/bin` leads PATH; a system/Homebrew
jolt lacks the J6-era stdlib modules):

```bash
bb bootstrap:jolt
. build/tools/jolt-env.sh
export PATH="$JOLT_HOME/bin:$PATH"
jolt -Srepro -M:jolt:jolt-yin-repl --index-store mem
```

Per-host store status for `--index-store`: `mem` everywhere; `file:<dir>` on
JVM, Node, Dart and Jolt (J4+); `dht:<dir>` on JVM, Node, Dart, and on Jolt
since the J7 slices 1-2 (2026-11-15; the sections 6-7 process/interop gate of
yin.repl-jolt-implementation.md §10.1 remains open). `--help` works on every
host.

### Uniform launcher: `bb yin-repl` (P1d, 2026-11-20)

One entry point for all backends — prerequisites are built automatically
(artifact-gated: warm launches are fast), every child prints a target identity
line, and `bb yin-repl --help` lists each backend's capability notes:

```bash
bb yin-repl --backend jvm         # default; the canonical JVM REPL
bb yin-repl --backend node        # builds target/yin-repl.js on first use
bb yin-repl --backend dart        # builds build/yin-repl-peer on first use
bb yin-repl --backend jolt-chez   # bootstraps jolt if needed; identity-gated
bb yin-repl --backend jolt-gambit # compiled Yin boot cached in build/gambit-yin/
```

`jolt-gambit` runs a REAL Yin session under the Gambit backend (the compiled
boot; interactive terminal passthrough verified on a genuine PTY), with honest
capability degradation per the C4 ruling: no `:yang/python` frontend
(`unavailable-parser` refusal), blocking FFI refuses inline with the exact
documented message, and the session survives both. The qualified FFI tier
(loader/memory/ordinary calls/errno — two-platform: macOS arm64 and Linux
x86_64) is callable in-session. Evidence: `make gambityinrepl` in the jolt
clone (both-platform PASS transcripts); the acceptance gate is
`host/gambit/yin-repl-session.sh` — a 12-step ordered state machine over real
`yin>` replies. Yin args pass through (`--index-store mem`, etc.). Per-backend
capability summary: JVM/Node/Dart = full (all frontends, all stores);
`jolt-chez` = full minus native-parser availability as provisioned;
`jolt-gambit` = core Yin (Clojure frontend, yin.vm, qualified FFI) with
subprocess/blocking-capability frontends refusing inline.

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

### Switching Index Stores: `(use-store ...)`
At a quiesced local `yin>` prompt, `(use-store <target> & <opts>)` switches the session's active `dao.jing` code-index store without restarting the REPL process:

```clojure
;; Engine-native targets:
yin> (use-store "mem")                     ; or (use-store :mem)
Switched index store to mem (code index cleared)

yin> (use-store "file:/tmp/my-repl-store")
Switched index store to file:/tmp/my-repl-store (empty code index)

;; ...evaluate and publish rounds on the store; each published round advances HEAD.
;; Reopening an existing durable store recovers its HEAD and transactions:
yin> (reset)
SemanticVM reset
yin> (use-store "file:/tmp/my-repl-store")
Switched index store to file:/tmp/my-repl-store (recovered HEAD :segment/..., 3 transactions)
```

- **Quiesce requirement (`(reset)` before switching):**
  - A store switch requires a quiesced session: no program evaluated since startup, `(reset)`, `(vm ...)`, or the last `(use-store ...)`, no parked `(require ...)`, no unwritten AST rows, and no pending publication or hydration. Read-only inspection commands (`(help)`, `(repl-state)`, `(host)`, `(trusted)`, `(compile ...)`, `(lang ...)`) leave the session quiesced.
  - If the session holds evaluated state (including after an evaluation error), `(use-store ...)` refuses immediately and leaves all bindings untouched:
    `Error: cannot switch index store while the session holds evaluated state; run (reset) first`
  - If a `(require ...)` is parked, it refuses with:
    `Error: cannot switch index store while a require is pending; wait for it to complete or run (abandon) and (reset) first`
  - Independent stores own disjoint transaction (`t`) and entity (`e`) histories. Switching rebuilds the session over the target store's own `:recovery` snapshot without copying index state across stores, while preserving `:lang`, `:frontends`, `:vm-type`, `:extra-primitives`, `:link-policy`, `:custody`, and `:shell-token`.
  - Once on a durable store (`file:<dir>` or `dht:<dir>`), subsequent `(reset)` and `(vm :type)` commands retain the store's published code index (`yin.repl.store/retain-on-reset?`); on `mem`, `(reset)` and `(vm :type)` start an empty code index.

- **Strict `open-first` ordering & `mem` ↔ `file:<dir>` ↔ `dht:<dir>` workflows:**
  - The replacement store and transport session are parsed, validated, locked, recovered, and installed into the engine **before** the previous transport session and store handle are closed. If any step fails (unknown scheme, bad option, missing key file, locked or unwritable directory, corrupt `HEAD` or `heads.edn`, or synchronous UDP bind failure), the partial replacement is released and the current store, lock, and session remain active.
  - Because `open-first` acquires the new lock before releasing the old one, switching directly between `file:<dir>` and `dht:<dir>` on the **exact same `<dir>`** hits the in-process directory lock and reports `(currently held by this session; switch to mem first to release the directory lock)`. To transition the same directory between `file:<dir>` and `dht:<dir>`, bounce through `mem` first:
    ```clojure
    yin> (reset)
    yin> (use-store "mem")                     ; releases <dir>/lock
    yin> (use-store "dht:/tmp/my-repl-store"
                    :peers ["127.0.0.1:4002" "127.0.0.1:4003"]
                    :listen "127.0.0.1:4001"
                    :publish true
                    :key "/path/to/publisher.key")
    ```
    Opening `dht:<dir>` re-locks `<dir>`, validates and rehydrates `<dir>/HEAD`, prints the DHT disclosure banner, installs the DHT host modules (`dao.space.dht`, `yin.link`, `yin.head`), and attaches the transport session.
  - Swapping out of `dht:<dir>` (e.g. `(reset)` then `(use-store "mem")`) stops and closes follower WebSocket dials, the publisher head board listener, the UDP socket, and `<dir>/lock`, clears `:session`, and removes the DHT host modules.

- **DHT options on `(use-store "dht:<dir>" & opts)`:**
  - Trailing keyword-value options apply only to `dht:<dir>` (passing options with `"mem"` or `"file:<dir>"` refuses with `Error: store options require dht:<dir>`). Without options, `(use-store "dht:<dir>")` opens in solo mode (no peers, no socket, no key — local-only until peers are given).
  - `:peers ["127.0.0.1:4002" ...]` or repeatable `:peer "127.0.0.1:4002"` — bootstrap peer contact(s) (`localhost:` normalizes to `127.0.0.1:`).
  - `:listen "127.0.0.1:4001"` (or `"[::1]:4001"` or `"4001"`), or `:bind "127.0.0.1"` and `:port 4001` — local UDP bind address and port (requires at least one peer).
  - `:publish true` — boolean (`false` by default); share local store content with peers.
  - `:key "/path/to/publisher.key"` — existing Ed25519 private key file for signing published names.
  - `:principals ["<64-lowercase-hex>" ...]` or repeatable `:principal "<64-lowercase-hex>"` — declared publisher principal(s) whose signed name assertions to honor.
  - `:trust ["ed25519:<64-lowercase-hex>" ...]` — vector/sequence of trusted principal IDs for module admission (restored on subsequent `(reset)` while on this store).
  - `:follow ["<64-lowercase-hex>@127.0.0.1:4001" ...]` — vector/sequence of loopback publisher head boards to follow (each principal must also be declared via `:principal`/`:principals` or `:join`).
  - `:join "yin:127.0.0.1:4001/<64-lowercase-hex>"` — reader shorthand (equivalent to `yin-repl dht join <token>`): adds the peer contact, declares the publisher principal, and follows its loopback head board. Pair with `:trust ["ed25519:<64-lowercase-hex>"]` to admit modules signed by that publisher.
  - `:max-inbound-bytes 67108864` — nonnegative integer inbound `:store` payload ceiling.
  - `:manifest` (`--dht-manifest`) is a startup-only pin and is refused in-session: switch to `dht:<dir>` first, then require `dao.space.dht` and call `(dao.space.dht/load-index <manifest>)`.

- **Local-only command & non-interaction with `state.edn`:**
  - `(use-store ...)` always executes on the local driver (even while attached to a remote server via `(connect ...)`) and is refused over remote `--port` evaluation requests (`Error: (use-store ...) is a local host command and cannot be run over a remote endpoint`).
  - Like `(vm :type)` and `(trust ...)` / `(untrust ...)`, `(use-store ...)` never reads or writes `<node-dir>/state.edn`, so saved startup profiles are never clobbered by exploratory in-session store switches.

- **Gambit boundary (`jolt-gambit`, owner ruling C4):**
  - On `jolt-gambit`, `(use-store "mem")` (or `:mem`) on a quiesced session opens a fresh in-memory store and preserves the Gambit host modules (`jolt.ffi` and `dao.jing.mem`; re-require them in the rebuilt session before calling their exports).
  - Durable targets `(use-store "file:<dir>")` and `(use-store "dht:<dir>" ...)` hit the C4 blocking-FFI refusal inline during `open-first`:
    `Error: blocking ffi is unsupported on the gambit target — GC-safe blocking is unqualified on Gambit 4.9.8 (no verified release/claim transition)`
  - The active `:mem` store and REPL session survive the refusal intact.

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
