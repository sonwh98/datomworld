Created-GMT: 2026-11-22 15:19:45 GMT
Created-Local: 2026-11-22 22:19:45 ICT (+07)
Completed-GMT: 2026-11-22 15:32:00 GMT
Completed-Local: 2026-11-22 22:32:00 ICT (+07)
Coding-Agent: agy
Session-ID: 29f740e7-285e-4b94-a559-27455325c321

# Task: in-session dao.jing store switching for yin.repl — Architect design round

Role: Lead System Architect

Implementers:
- Model: gemini-4-argon-high | Assigned: 2026-11-22 22:19 ICT | Status: completed | Rationale: architecture/design decisions with real fork-in-the-road choices; argon is the architecture strength per team.md; flat subscription

---

## Executive Summary & Verdict Table

This design decision record adjudicates the five architectural questions for in-session `dao.jing` store switching in `yin.repl` (`collab/yin-repl/1795224000000-brief-in-session-store-switch.md`), preserving the engine/composition separation of `docs/design/yin.repl.dht-composition.md` (Gates A and B), the foundational axioms of `docs/design/datom.world.md`, and the Gambit C4/P1e capability boundary.

| # | Decision Area | Verdict | Core Invariant / Grounding |
|---|---|---|---|
| **1** | **Swap semantics for the live VM** | **(a) Quiesce** — require a clean/reset session (`:quiesced? true`, no evaluated programs since boot/reset, no parked run or pending publication); rebuild the session over the target store's own `:recovery` snapshot with no cross-store index copy. | **Unitarity & Explicit Causality** (`datom.world.md`); `dao.jing` has no blob enumeration API (`yin.repl.dht-composition.md` §4); independent stores own disjoint `t` and entity `e` timelines. |
| **2** | **Ordering** | **`open-first` (strict, unconditional)** — parse, validate, lock, recover, and compose the new store and transport session completely before closing the old transport session and store handle. | **P1e failure-survival invariant** (`1795213000000-engineer-p1e-dht-gambit-probe`): zero no-store window; any open refusal leaves the old store and session untouched. |
| **3** | **Meaning for `dht:`** | **Full composition transition** — swapping into `dht:<dir>` validates join options (`:peers`, `:listen`/`:bind`/`:port`, `:publish`, `:key`, `:principals`, `:trust`, `:follow`, `:join`), prints the disclosure banner, installs DHT host modules and link source, and attaches `:session`; swapping out drains/closes follower dials, head board, UDP socket, and lock, clearing `:session` and DHT modules. Startup pin `--dht-manifest` (`:manifest`) is refused in-session in favor of `(dao.space.dht/load-index ...)`. | **Ownership split & Gate A** (`yin.repl.dht-composition.md` §§2, 3, 6, 7, 10): the engine never names `dht:` or holds `:node`; composition owns transport lifecycle and disclosure. |
| **4** | **Command surface & `state.edn`** | **`(use-store <target> & <opts>)` at the `yin>` prompt only**; local driver/shell command (refused over remote `--port` `:op/eval`); **does not write `<node-dir>/state.edn`**. | Matches `(vm :type)` and `(trust ...)` (`yin.repl.state`: `state.edn` records startup CLI flags, not in-session commands). |
| **5** | **Gambit boundary (C4)** | **Honest degradation (`mem` → `mem` works; durable targets `file:<dir>` and `dht:<dir>` hit C4 blocking-FFI refusal inline and the session survives)**. Do not qualify blocking file FFI. | **Owner ruling C4** (`1795040000000-reviewer-p1a-confirm-codex`) & **P1e pattern** (`host/gambit/yin/dao/posix/file.jolt`). |

---

## Decision 1: Swap Semantics for the Live VM

### Chosen Option
**Option (a): Quiesce** — Require an empty or freshly `(reset)` session before permitting a store swap, and rebuild the session over the newly opened store's own validated `:recovery` snapshot (`{:manifest ... :datoms ...}`), without copying or migrating index state across stores.

### Rationale (Grounded in Invariants and Existing Code)
1. **Unitarity ("The Universal AST is never lost") and "Rows every round" (`docs/design/datom.world.md:32`, `docs/design/yin.repl.dao.space-index.md:53-65`)**:
   - In `yin.repl`, every evaluated program's expanded rows are materialized into the active `:index-store` (`yin.repl.index/write-rows`, `src/cljc/yin/repl/index.cljc:362-376`), its datom projection is committed to the indexer's `:local` log, and its admission-scoped call provenance is materialized into `:index-store` and tracked in `:call-provenance-handles` (`src/cljc/yin/repl.cljc:232-272`).
   - If the underlying `:index-store` were swapped out beneath a live VM without resetting the VM (`:vm`, `:expander`, `:ast-indexer`, `:call-site-tables`, `:call-site-admissions`, `:call-provenance-handles`), the live VM would retain lowered closures, macros, and provenance handles whose Universal AST rows and provenance records do not exist in the new `:index-store`. That directly violates **Unitarity** and breaks `(yin.link/publish ...)` (`module-from-index` reads AST rows from `:index-store`).
2. **`dao.jing` is passive syntax with no enumeration contract (`docs/design/datom.world.md:75`, `docs/design/yin.repl.dht-composition.md` §4)**:
   - The approved `dao.jing` surface (`src/cljc/yin/repl/store.cljc:16-18`, `docs/design/yin.repl.dht-composition.md:148-168`) is `{:put-bytes-fn :get-bytes-fn :close-fn}`. It deliberately provides no key enumeration or dump operation.
   - Furthermore, once `yin.repl.index/write-rows` (`src/cljc/yin/repl/index.cljc:375`) materializes a program's row set into `:content-store`, it removes that row set from `:unwritten`. Option (b) (Migrate) would require either retaining every raw row set in RAM forever or walking and re-encoding every historical AST and provenance graph from the old store into the new store, while also re-cutting B-tree index pages whenever `:publication-policy` (`:max-payload-bytes`) changes between `mem`/`file:` and `dht:` (`docs/design/yin.repl.dht-composition.md` §5).
3. **Disjoint causal histories cannot be merged implicitly (`Explicit causality > implicit assumptions`)**:
   - A target durable store (`file:<dir>` or `dht:<dir>`) that already has a `HEAD` owns an authoritative transaction sequence (`t = 1..k`) and entity allocator (`e = 1024..n`) recovered by `yin.repl.index/rehydrate` (`src/cljc/yin/repl/index.cljc:268-302`).
   - A live session that has also allocated `t = 1..j` and `e = 1024..m` cannot copy its index into a non-empty target store without either colliding on `t` and `e`, overwriting the target store's `HEAD` (destroying prior durable facts), or rewriting `t` and `e` (invalidating signed name assertions `:yin.module/envelope` and content hashes).
   - Note that the workflow where an operator populates `file:<dir>` and later exposes that same directory on the DHT ring (`file:<dir>` → `mem` → `dht:<dir>`) requires **no migration protocol at all** under Option (a): `<dir>/content.jing` and `<dir>/HEAD` are already durable on disk in `<dir>`, and opening `dht:<dir>` validates `<dir>/HEAD` and rehydrates the session's indexer from `<dir>`'s durable snapshot automatically.
4. **Why Option (c) (Hybrid) is rejected**:
   - "Restrictions are a feature" (`docs/design/datom.world.md:12`). Supporting a per-swap migration flag imports all of Option (b)'s partial-copy failure modes, policy-rebuild complexity, and history-collision hazards into the engine.
5. **Pre-existing bug to fix alongside Decision 1 (`src/cljc/yin/repl.cljc:2511, 2569`)**:
   - `yin.repl/rebuild-session` (`src/cljc/yin/repl.cljc:2511`) and `handle-command`'s `vm` branch (`src/cljc/yin/repl.cljc:2569`) currently check `(store/durable? (:index-store state))` instead of `(store/retain-on-reset? (:index-store state))`. Because `yin.shell.dht/compose` (`src/cljc/yin/shell/dht.cljc:580-594`) wraps `(dht/store node)` (which has no `:durable-dir` key) and declares `:retain-on-reset? true` explicitly, `rebuild-session` and `(vm ...)` must call `(store/retain-on-reset? (:index-store state))` as designed in `src/cljc/yin/repl/store.cljc:164-180`.

### Exact Quiesce Contract & Reset Semantics
- **Session quiescence tracking (`:quiesced?`)**:
  - `make-session` (`src/cljc/yin/repl.cljc:942`) initializes `:quiesced? true`.
  - Consequently, a freshly booted session, a session immediately after `(reset)`, a session immediately after `(vm :type)`, and a session immediately after a successful `(use-store ...)` all have `:quiesced? true`.
  - Entering `eval-program` (`src/cljc/yin/repl.cljc:2264`) marks `:quiesced? false` on the resulting state (whether the program halts, parks on a link/query, fails macro expansion, or throws at evaluation time).
  - Read-only / inspection commands (`(help)`, `(repl-state)`, `(host)`, `(trusted)`, `(compile ...)`, `(lang ...)`) do not evaluate programs on the VM and leave `:quiesced?` unchanged.
- **Quiesce gate predicate (`yin.repl/quiesced?`)**:
  ```clojure
  (defn quiesced?
    "True when the session holds no evaluated VM/macro state since startup
     or the last (reset)/(vm ...)/(use-store ...), no parked evaluation,
     no unwritten AST rows, and no unsettled local publication request."
    [state]
    (and (boolean (:quiesced? state))
         (nil? (:pending-run state))
         (empty? (:unwritten (:indexer state)))
         (empty? (index/pending-publications (:indexer state)))))
  ```
  On a composition session (`yin.repl.main`), the composition check additionally requires `(empty? (:awaiting (:session driver-state)))` and `(composition-admitting? driver-state)`.
- **What changes on a successful swap**:
  - The session is rebuilt via `make-session` over the new `:index-store`, new `:link-source`, new `:publication` (`{:publication-policy ... :media ... :local-service ...}`), and new `:host-modules`, and its fresh `:indexer` is seeded via `(index/rehydrate indexer new-recovery)`.
  - Preserved across the swap: `:lang`, `:frontends`, `:vm-type`, `:extra-primitives`, `:output-stream`, `:output-cursor`, `:link-policy`, `:custody`, `:shell-token`, and `:round` (incremented monotonically so output tokens never collide).
  - Subsequent `(reset)` or `(vm :type)` commands in the session consult `(store/retain-on-reset? (:index-store state))` on the **new** store: `true` for `file:<dir>` and `dht:<dir>` (retaining the durable index across reset), `false` for `mem` (rebuilding an empty index on reset).

### Failure Story
- If the operator runs `(use-store ...)` when `(not (quiesced? state))` (e.g. after evaluating `(def x 1)` without running `(reset)`):
  - The command refuses immediately before parsing or opening any target store:
    `"Error: cannot switch index store while the session holds evaluated state; run (reset) first"`
  - If a `(require ...)` is currently parked (`(:pending-run state)` is non-nil), it refuses with:
    `"Error: cannot switch index store while a require is pending; wait for it to complete or run (abandon) and (reset) first"`
  - The current store handle, VM bindings, value history (`*1`, `*2`, `*3`), macro expander, and transport session remain 100% untouched.

---

## Decision 2: Ordering (`open-first` vs `close-first`)

### Chosen Option
**`open-first` (strict, unconditional)** — The target store spec is parsed, validated, opened, locked, recovered, and its transport session (if any) constructed completely **before** the old transport session is stopped or the old store handle is closed.

### Rationale (Grounded in Invariants and Existing Code)
1. **The P1e lesson — failed operations must never kill a live session (`collab/jolt/1795213000000-engineer-p1e-dht-gambit-probe.gpt-6.2-sol.findings.md`)**:
   - Under `close-first`, closing the active store before opening the replacement creates a window where the session has no store (and has released its exclusive directory lock `<dir>/lock` and closed its UDP socket / head board). If the new open then fails (e.g., invalid flag, bad directory permissions, corrupt target `HEAD`, locked target directory, missing `--dht-key`, or Gambit C4 blocking-FFI refusal), the session is either broken or must attempt to reopen the old store (which can fail if another process grabbed the lock or port).
   - Under `open-first`, every validation and resource acquisition for the new store happens while the old store and old transport session are still live and untouched. If any step of opening the new store throws, the new open cleans up only its own partial acquisitions (as `dao.space.store/open` at `src/cljc/dao/space/store.cljc:251-265` and `yin.shell.dht/open` at `src/cljc/yin/shell/dht.cljc:657-672` already do), and the old store remains active with zero interruption.
2. **Same-directory lock contention (`dao.space.store.fs/held`, `src/cljc/dao/space/store/fs.cljc:174-272`)**:
   - Because `dao.space.store.fs/lock!` maintains the in-process `held` atom of canonical directory paths (to prevent POSIX `fcntl` self-unlocking on a second handle in the same process), an `open-first` attempt to open `<dir>` while the current session already holds `<dir>` open (e.g., swapping `file:<dir>` → `file:<dir>` or `file:<dir>` → `dht:<dir>` on the exact same `<dir>`) will be refused by `fs/lock!` (`"<dir> is locked by another process"`).
   - We deliberately **do not** weaken `open-first` with a `close-first` exception for same-directory swaps. Introducing a `close-first` path for `file:<dir>` → `dht:<dir>` on the same `<dir>` would unlock `<dir>` before `yin.shell.dht/open` validates `heads.edn`, loads `--dht-key`, or binds the UDP socket — leaving the session with no store if any of those steps fails.
   - Instead, strict `open-first` holds unconditionally: to transition the same physical `<dir>` between `file:<dir>` and `dht:<dir>`, the operator either bounces through `mem` (`(use-store "mem")` which releases `<dir>`'s lock, then `(use-store "dht:<dir>" ...)`), or switches to a distinct directory. When the swap handler catches a lock refusal on a directory matching `(:durable-dir (:index-store repl))` (or `(:dir (:index-store-spec repl))`), it appends the actionable hint: `"(currently held by this session; switch to mem first to release the directory lock)"`.

### Exact Execution Sequence for `open-first` Swap
1. **Pre-check**: Verify `(quiesced? repl)` (and composition quiescence if `:session` is present). If not quiesced, return refusal immediately.
2. **Parse & validate**: Parse the `(use-store ...)` arguments into a checked store spec and composition options (loading any `:key` file via `load-key` before opening).
3. **Open new store (`open-first`)**:
   - For a composition spec (`dht:<dir>`): call `yin.shell.store/open` to open `<dir>`, validate `HEAD` and `heads.edn`, join the `dao.space.dht` node, compose `:engine-inputs` and `:session`.
   - For an engine spec (`:mem` or `{:type :file :dir dir}`): call `yin.repl.store/open` (or composition's `:open` on Gambit).
4. **Install into engine**:
   - Build the new session state in `:repl` via `yin.repl/install-store` (wrapped in `try/catch`; if engine installation throws, call `release-store!` on the newly opened store/session and rethrow to the command error handler so the old store remains active).
5. **Retire & close old resources (commit point passed)**:
   - If the old state had a composition `:session` (`old-session`), call `((:close! composition) old-session)` (`yin.shell.dht/close!`, which calls `stop!` to close all follower WebSocket dials and stop/step the head board server).
   - Call `((or (:close-store! composition) store/close!) old-index-store)` (closing the old DHT node's UDP socket and/or the old durable store's content log and releasing its `<dir>/lock`). Wrap old-resource cleanup in `quietly!` (`try/catch` swallowing cleanup errors) so a close error on the retired store never aborts the already-installed new store.
6. **Install new transport session**:
   - Associate the new `:session` (or `dissoc` `:session` when switching to `mem` or `file:<dir>`) on the driver state and emit the confirmation and disclosure lines.

### Failure Story
- Failure at Step 1 (not quiesced), Step 2 (bad syntax/unknown scheme/bad flag/missing key file), Step 3 (directory unwritable, locked by another process or self, corrupt `HEAD`, unreadable index B-tree, corrupt `heads.edn`, Gambit C4 blocking-FFI refusal), or Step 4 (engine session installation):
  - Any resources acquired in Step 3 are released immediately by `open` / `release-store!`.
  - Step 5 is **never reached**.
  - The old `:index-store`, old `<dir>/lock`, old DHT `:node` (UDP socket, head board, follower dials), and old `:repl` state remain untouched and live.
  - The command returns `Error: <reason>` and the prompt continues on the old store.

---

## Decision 3: The Swap's Meaning for `dht:`

### Chosen Option
**Full Network-Identity & Composition Lifecycle Transition** — Swapping into `dht:<dir>` accepts DHT join parameters in `(use-store "dht:<dir>" & opts)`, validates them through `yin.shell.cli`'s rules, opens the durable directory and joins the ring (`yin.shell.store/open`), prints the disclosure banner (`banner-lines` + `follow-lines`), installs `dao.space.dht`/`yin.link`/`yin.head` host modules and the signed-name `:link-source`, and attaches `:session` to the host driver. Swapping out of `dht:<dir>` leaves the ring by stopping and closing follower dials, the head board server, the UDP socket, and the directory lock, removing `:session`, and rebuilding `:repl` without the DHT host modules or DHT link source. The startup-only pin `--dht-manifest` (`:manifest`) is refused on in-session `(use-store ...)` in favor of `(dao.space.dht/load-index <manifest>)`.

### Rationale (Grounded in Invariants and Existing Code)
1. **Engine/Composition Dependency Rule — Gate A (`docs/design/yin.repl.dht-composition.md` §§2, 3, 6, 7, 10)**:
   - Gate A enforces zero occurrences of `dao.jing.dht`, `dao.space.dht`, `yin.repl.dht`, `yin.vm.linker.(dht|head)`, `:dht`, or `--dht-` inside `src/cljc/yin/repl/` and `src/cljc/yin/repl.cljc`.
   - Therefore, all `dht:` argument parsing, validation, node construction, disclosure banner formatting, and transport teardown for `(use-store ...)` **must live in `yin.shell.*`** (`yin.shell.cli`, `yin.shell.store`, `yin.shell.dht`, `yin.shell.main`) and reach `yin.repl.main` / `yin.repl.driver` solely as plain-data hooks in `composition` (`:swap-store` or `:parse-store-cmd`, `:open`, `:close!`, `:close-store!`).
2. **Joining a ring is network identity and capability registration, not just a byte handle (`src/cljc/yin/shell/store.cljc:104-122`, `src/cljc/yin/shell/dht.cljc:540-595`)**:
   - When `yin.shell.store/open` opens a `dht:<dir>` spec, it produces:
     1. `:index-store`: the DHT-wrapped `dao.jing` handle with `:publication-policy {:branching-factor 512 :max-payload-bytes (dht/payload-budget node)}`, `:head-fn` (which writes `<dir>/HEAD`, calls `dht/announce!`, and deposits the signed head trace on the publisher's board), `:recovery`, and `:retain-on-reset? true`.
     2. `:engine-inputs`: `{:link-source (shell.dht/link-source spec {:key key :principals principals :admission admission}) :host-modules shell.query/registrations}` — registering `dao.space.dht`, `yin.link`, and `yin.head` in the VM's module registry and configuring `:registered-ops` so `yin.repl.query/serve` defers their effect calls to `yin.shell.query/serve-services`.
     3. `:session`: `(session/transport-state (:node opened) key)` — the transport state stepped by `yin.repl.main/step-all` on every tick.
   - Without join parameters (`:peers`, `:listen`/`:bind`/`:port`, `:publish`, `:key`, `:principals`, `:trust`, `:follow`, or `:join`), a `dht:<dir>` store is restricted to solo mode (`peers []`, `publish? false`, no socket, no key). Accepting these join options in `(use-store "dht:<dir>" ...)` allows a running REPL to join a ring as a storing peer (`serve`), a publisher (`init`), or a follower (`join`).
3. **Why `--dht-manifest` (`:manifest`) is excluded from in-session `(use-store ...)`**:
   - At CLI startup, `--dht-manifest` puts the node into `::hydrating` (`admitting? => false`), blocking all prompt evaluation until peers deliver the manifest, and terminating the REPL process with exit code 1 (`step-all` line 851) if hydration fails.
   - Inside an already-running REPL session, blocking the prompt or killing the process on a peer timeout violates the P1e session-survival principle, and is unnecessary because `(require 'dao.space.dht)` followed by `(dao.space.dht/load-index <manifest>)` and `(dao.space.dht/q <manifest> ...)` is already available at the prompt (`src/cljc/yin/shell/query.cljc:48-67`).
   - Refusing `:manifest` / `--dht-manifest` on `(use-store ...)` with a message directing the user to `(dao.space.dht/load-index <manifest>)` eliminates in-session hydration lockups and process-exit hazards completely.
4. **Synchronous bind-failure detection and non-fatal post-swap transport errors**:
   - On synchronous datagram hosts (JVM, Jolt/Chez), `(bind! ...)` in `dao.space.dht/node-over` (`src/cljc/dao/space/dht.cljc:283-289`) attempts the UDP bind immediately and deposits `:dao.stream.datagram/bound` or `:dao.stream.datagram/bind-failed` onto the node's `traffic` ring buffer before `dht/join` returns.
   - By checking `traffic` for an immediate `:dao.stream.datagram/bind-failed` inside `yin.shell.dht/open` (or running one `bind-step` probe on `node` during `open` when `peers` is non-empty), a synchronous port-in-use or bind failure is caught **during `open`**, closes `<dir>`, and throws an `ex-info` refusal — so under `open-first`, the swap refuses cleanly and the previous store remains live!
   - For asynchronous datagram hosts (Node, Dart) where `:bind-failed` may arrive on a tick after `(use-store "dht:<dir>" ...)`: in `yin.repl.main/step-all` (`src/cljc/yin/repl/main.cljc:851-852`), if `(composition-refusal state)` fires on a store that was switched in-session (`(:swapped-in-session? state)`), `step-all` must **not** set `:running? false` (which would kill the REPL process); instead, it prints the refusal line, closes the failed DHT transport session, and falls back to a fresh `:mem` store (or marks the node solo/inert) so the interactive session survives and the operator can issue another `(use-store ...)`.

### Leaving the Ring (Swapping OUT of `dht:<dir>`)
When the active store is `dht:<dir>` (`(:session driver-state)` is non-nil) and `(use-store <new-target> ...)` succeeds past Step 4 (`open-first`):
1. `yin.shell.dht/close!` (`src/cljc/yin/shell/dht.cljc:718-738`) is called on `old-session`:
   - Calls `(stop! old-session)`: closes every active follower WebSocket dial (`head.board/close!`), clears `[::follow :links]`, and asks the publisher's head board server (`head.board/stop!`) to stop.
   - Steps the stopping head board server once (`head.board/serve-step`) if not yet `stopped?`.
2. `yin.shell.store/close!` (`src/cljc/yin/shell/store.cljc:149-154`) is called on `old-index-store`:
   - Invokes the DHT store handle's `:close-fn` (`src/cljc/dao/space/dht.cljc:331-336`), which closes the UDP datagram seam (`((:close! seam))`) and closes the local `dao.space.store` (`(jing/close! local)`), closing `<dir>/content.jing` and releasing `<dir>/lock`.
   - Any unacknowledged repair/replicate queues on the old node are discarded (per `yin.repl.md:284`).
3. If the new target is `mem` or `file:<dir>`, `:session` on `driver-state` becomes `nil`, `:host-modules` in `:repl` becomes `{}`, `:registered-ops` becomes `#{}`, `:link-source` becomes the local non-DHT link source, and `:publication-policy` becomes `nil`.

---

## Decision 4: Command Surface, Argument Grammar, and `state.edn` Interaction

### Chosen Option
- **Surface**: `(use-store <target> & <opts>)` at the `yin>` prompt only (no new launcher subcommand).
- **Routing**: Recognized in `yin.repl/command-heads` and `yin.repl.adapter/local-command-heads`; executed locally by `yin.repl.driver` (with composition hooks when present) or `yin.repl` (in standalone engine tests); explicitly refused on remote WebSocket `--port` requests in `yin.repl.serve`.
- **`state.edn` interaction**: In-session `(use-store ...)` **never reads or writes `<node-dir>/state.edn`**.

### Argument Grammar
At the `yin>` prompt:
```clojure
;; Engine-native targets (available on all hosts; durable open subject to host capability):
(use-store "mem")                     ; or (use-store :mem)
(use-store "file:/path/to/dir")

;; DHT composition targets (available on builds carrying yin.shell.main/composition):
(use-store "dht:/path/to/dir")        ; solo mode (peers [], publish? false, no socket)

;; DHT with keyword join options:
(use-store "dht:/path/to/dir"
           :peers ["127.0.0.1:4002" "127.0.0.1:4003"] ; or repeatable :peer "host:port"
           :listen "127.0.0.1:4001"                   ; or :bind "127.0.0.1" :port 4001
           :publish true                              ; boolean (default false)
           :key "/path/to/publisher.key"              ; existing Ed25519 key file
           :principals ["<64-lowercase-hex>"]         ; or repeatable :principal "<64-hex>"
           :trust ["ed25519:<64-lowercase-hex>"]      ; trusted principals for module admission
           :follow ["<64-hex>@127.0.0.1:4001"]        ; loopback head board(s) to follow
           :max-inbound-bytes 67108864)               ; nonnegative integer

;; DHT reader shorthand via join token (equivalent to `yin-repl dht join <token>`):
(use-store "dht:/path/to/dir"
           :join "yin:127.0.0.1:4001/<64-lowercase-hex>")
```

#### Grammar Validation Rules
1. **Arity & target**:
   - `(use-store)` with no arguments refuses: `"Error: (use-store <store> ...) needs a store: mem, file:<dir>"` (or `"mem, file:<dir>, dht:<dir>"` when `yin.shell.store` is composed).
   - `<target>` must be a string (or `:mem`, normalized to `"mem"`). Any other type refuses naming the supported forms.
   - `<target>` is parsed by `(:parse-store-arg composition)` (which is `yin.shell.store/parse-arg` in `yin.shell.main`, `jolt.gambit.yin/parse-store-arg` on Gambit, and `yin.repl.store/parse-arg` in engine-only mode).
2. **Trailing `<opts>`**:
   - Must be an even number of keyword-value pairs. An odd trailing argument or non-keyword option key refuses with usage text.
   - In engine-only mode (no DHT composition) or when `<target>` parses to `:mem` or `{:type :file :dir dir}`, any trailing options are refused:
     `"Error: store options require dht:<dir>"` (in `yin.shell.cli`) or `"Error: (use-store ...) takes one store argument: mem, file:<dir>"` (in engine-only `yin.repl.store`).
   - In `yin.shell.cli/parse-store-command`, the keyword options are validated through the existing functions (`parse-peer`, `peer-text`, `parse-principal`, `parse-trust`, `parse-follow`, `parse-token`, `load-key`, and `dht-spec`), ensuring 100% parity with CLI startup validation.
   - Passing `:manifest` refuses with:
     `"Error: :manifest (--dht-manifest) is a startup pin; switch to dht:<dir> first, then call (dao.space.dht/load-index <manifest>)"`.
3. **Output format**:
   - Swapping to `mem`:
     `"Switched index store to mem (code index cleared)"`
   - Swapping to `file:<dir>`:
     - When `<dir>/HEAD` is absent (empty index):
       `"Switched index store to file:<dir> (empty code index)"`
     - When `<dir>/HEAD` is recovered (`:manifest` present in `:recovery`):
       `"Switched index store to file:<dir> (recovered HEAD <manifest>, <n> transactions)"`
   - Swapping to `dht:<dir>`:
     - Header line: `"Switched index store to dht:<dir>"` (with `(recovered HEAD <manifest>, <n> transactions)` or `(empty code index)`), followed by the composition's disclosure lines from `yin.shell.cli/banner-lines` and `yin.shell.dht/follow-lines`.

### Rationale for `state.edn` Non-Interaction
1. **`state.edn` is CLI startup configuration, not runtime session state (`src/cljc/yin/repl/state.cljc:1-19`)**:
   - `yin.repl.state` persists the resolved command-line flags once during `yin.repl.main/startup` (`save-state!`, `src/cljc/yin/repl/main.cljc:553, 667`). Neither `yin.repl` nor `yin.repl.driver` holds `node-dir` after startup.
   - Existing in-session reconfiguration commands — `(vm :type)` and `(trust p)` / `(untrust p)` — explicitly do **not** write `state.edn` (`src/cljc/yin/vm/docs/yin.repl.md:226`, `src/cljc/yin/shell/cli.cljc:624`).
2. **Preventing destructive clobbering of saved node profiles**:
   - A node started with `--name a` whose `state.edn` holds `--index-store dht:... --dht-peer ... --dht-key ... --dht-publish` would have its saved DHT configuration destroyed (or rendered invalid because `--dht-*` flags clash with `--index-store mem`) if an exploratory in-session `(use-store "mem")` rewrote `state.edn`.
   - Keeping `(use-store ...)` ephemeral to the running process while durable index facts persist in `<dir>/content.jing`, `<dir>/HEAD`, and `<dir>/heads.edn` is both consistent and safe.
   - Note on `(reset)` after `(use-store ...)`: When `(use-store ...)` installs a new store in the session, it updates `:initial-admission` on `:repl` to the admission policy configured for that new store (e.g. `{:trusted ...}` from `:trust` options on `dht:`, or `{}` on `mem`/`file:`), so a subsequent `(reset)` while on that store restores that store's configured admission set.

---

## Decision 5: Gambit Boundary (Owner Ruling C4, 2026-11-19)

### Chosen Option
**Honest Capability Degradation** — On Gambit (`host/gambit/yin/jolt/gambit/yin.jolt`), `(use-store "mem")` (on a quiesced session) succeeds and preserves Gambit's host modules (`jolt.ffi` and `dao.jing.mem`). Swapping to any durable target (`(use-store "file:<dir>")` or `(use-store "dht:<dir>" ...)`) routes to the existing Gambit C4 blocking-FFI refusal (`dao.posix.file` → `joltffi-public-blocking-raise`) during the `open-first` step, prints `Error: blocking ffi is unsupported on the gambit target — GC-safe blocking is unqualified on Gambit 4.9.8 (no verified release/claim transition)`, and leaves the running Gambit `:mem` session alive and untouched. **No blocking file FFI operations are qualified or shimmed.**

### Rationale & Root-Cause Finding from P1e
1. **Why a specific Gambit wiring change is required (`collab/jolt/1795213000000-engineer-p1e-dht-gambit-probe.gpt-6.2-sol.findings.md:18-20, 98, 183`)**:
   - In P1e, probing `dao.space.store/open` and `yin.shell.store/open` on Gambit revealed two pre-C4 failures that prevented the clean C4 message from appearing:
     1. `dao.space.store/dir-refusal` (`src/cljc/dao/space/store.cljc:114-126`) has no `:jolt` reader-conditional branch, so it falls through to `:clj` and calls `(java.io.File. dir)`. On Gambit, `java.io.File` is not registered in `host/gambit/host-statics.ss`, throwing `Error: (new java.io.File) is unsupported on the gambit target` **before** `open-locked-dir` reaches `fs/lock!` → `dao.posix.file/realpath` (which raises the C4 blocking-FFI refusal in `host/gambit/yin/dao/posix/file.jolt`).
     2. Loading `yin.shell.store` / `yin.shell.dht` / `dao.space.dht` on Gambit eagerly loads `dao.stream.udp` → `jolt.socket.native` → `jolt.winsock`, which fails at load time with `Error: No such var: ffi/defcfn` (`1795213000000-engineer-p1e-dht-gambit-probe:20, 92`).
   - Meanwhile, `host/gambit/yin/dao/posix/file.jolt` (`build/tools/jolt/host/gambit/yin/dao/posix/file.jolt:1-25`) was explicitly written for Gambit Yin REPL with docstring:
     `"Deferred capability refusal for durable storage in the local Gambit profile. The in-memory store shares callers with the durable store."`
     Every function in `host/gambit/yin/dao/posix/file.jolt` calls `(refuse-blocking)` (`(scheme/proc "joltffi-public-blocking-raise")`).
2. **How to make Gambit `(use-store ...)` hit the exact C4 refusal cleanly without loading `yin.shell.dht` or `java.io.File`**:
   - In `host/gambit/yin/jolt/gambit/yin.jolt`:
     - Require `[dao.posix.file :as posix-file]`.
     - Define the plain-data `dht-scheme` descriptor `{:prefix "dht:" :label "dht:<dir>" :type :dht :parse (fn [dir] {:type :dht :dir dir})}` locally (no require of `yin.shell.dht` or `dao.stream.udp`).
     - Keep CLI startup `:parse-store-arg` as `parse-store` (so CLI `--index-store file:...` or `dht:...` still fails startup with status 1 as P1e verified).
     - Provide `:parse-swap-store` (or `:swap-store` / `:open` over `:composition-spec? (constantly true)`) in `jolt.gambit.yin`'s composition map:
       - When `spec` is `:mem`: `(open-memory spec opts)` opens a fresh `(store/open :mem)` and attaches `:engine-inputs {:host-modules {'jolt.ffi activate-ffi 'dao.jing.mem activate-store}}`.
       - When `spec` is `{:type :file :dir dir}` or `{:type :dht :dir dir}`: before any `java.io.File` call, invoke `(posix-file/realpath dir)` (or `(posix-file/lock-file! dir)`), which immediately executes `host/gambit/yin/dao/posix/file.jolt`'s `(refuse-blocking)` and raises:
         `UnsupportedOperationException: blocking ffi is unsupported on the gambit target — GC-safe blocking is unqualified on Gambit 4.9.8 (no verified release/claim transition)`!
   - Furthermore, in `yin.repl` / `yin.repl.main`, the swap command's `try/catch` must catch `#?(:cljd Object :clj Throwable :cljs :default)` (not just `(when (ex-data e) ...)`!), because `joltffi-public-blocking-raise` throws a host `UnsupportedOperationException` (where `(ex-data e)` is `nil`), not an `ex-info`. Catching `Throwable` / `Exception` and formatting with `(repl/format-error e)` prints:
     `yin> Error: blocking ffi is unsupported on the gambit target — GC-safe blocking is unqualified on Gambit 4.9.8 (no verified release/claim transition)`
     and returns the existing state unchanged.

### Failure Story on Gambit
- Operator on Gambit (`bb yin-repl --backend jolt-gambit`) runs:
  1. `(def x 41)` → `41`
  2. `(use-store "file:/tmp/gambit-store")` (while dirty/unreset) → refuses with quiesce error (`run (reset) first`); `(+ x 1)` still evaluates to `42`.
  3. `(reset)` → `SemanticVM reset`
  4. `(use-store "file:/tmp/gambit-store")` → `open-first` calls `jolt.gambit.yin`'s `:open`, which calls `(posix-file/realpath "/tmp/gambit-store")` → raises C4 refusal → caught by swap handler → prints:
     `Error: blocking ffi is unsupported on the gambit target — GC-safe blocking is unqualified on Gambit 4.9.8 (no verified release/claim transition)`
  5. `(use-store "dht:/tmp/gambit-dht")` → same C4 refusal, printed inline; existing `:mem` store and host modules (`jolt.ffi`, `dao.jing.mem`) remain active.
  6. `(use-store "mem")` → opens fresh `:mem` store, preserves `jolt.ffi` and `dao.jing.mem` host modules, closes old `:mem` store, prints `Switched index store to mem (code index cleared)`.
  7. `(+ 21 22)` → `43`; `(jolt.ffi/sizeof :int32)` → `4`; `(quit)` → `Bye` (exit 0).

---

## What Changes Where (File-by-File Specification)

### 1. `src/cljc/yin/repl/store.cljc`
- Add `(describe-spec [spec])`: total helper returning `"mem"`, `"file:<dir>"`, or `nil` for engine specs, so confirmation and error messages format specs consistently.
- Keep `parse-arg`, `checked-spec`, `open`, `durable?`, `retain-on-reset?`, and `close!` pure and free of any DHT reference (Gate A).

### 2. `src/cljc/yin/repl.cljc`
- **`command-heads` (`:420`)**: Add `'use-store`.
- **`help-text` (`:429`)**: Document `(use-store "mem" | "file:<dir>")` (and composition stores).
- **Quiescence tracking**:
  - In `make-session` (`:942`): include `:quiesced? true` in the returned session map.
  - In `eval-program` (`:2264`): associate `:quiesced? false` on the state when a program batch enters evaluation.
  - Add public `(quiesced? [state])` and `(swap-refusal [state])`:
    - When `(:pending-run state)` is non-nil: `"cannot switch index store while a require is pending; wait for it to complete or run (abandon) and (reset) first"`.
    - When `(not (quiesced? state))`: `"cannot switch index store while the session holds evaluated state; run (reset) first"`.
- **Fix `retain-on-reset?` call sites (`:2511`, `:2569`)**:
  - Replace `(store/durable? (:index-store state))` with `(store/retain-on-reset? (:index-store state))` in `rebuild-session` (`:2511`) and `handle-command`'s `vm` branch (`:2569`).
- **Store installation & standalone swap**:
  - Extract common store/publication/link-source assembly from `create-state` (`:1154-1215`) into a helper so `create-state` and `(install-store [state opened-inputs])` share one path.
  - `(install-store [state {:keys [index-store index-store-spec recovery publication-policy publication-media link-source host-modules principals follow publisher-key admission]}])`:
    Rebuilds the session (`make-session`) over the new store and inputs, rehydrates `:indexer` with `recovery`, sets `:quiesced? true`, updates `:index-store`, `:index-store-spec`, `:publication`, `:link-source`, `:host-modules`, `:registered-ops`, `:index-recovery`, `:admission`, `:initial-admission`, clears value history (`*1`, `*2`, `*3`) and `:pending-run`, and returns `state'`.
  - `(swap-store [state args])` (used when `handle-command` handles `(use-store ...)` directly on a standalone `yin.repl` state without an outer driver composition):
    1. Check `(swap-refusal state)`.
    2. Validate that `args` has exactly 1 target (`"mem"`, `:mem`, or `"file:<dir>"`) via `store/parse-arg`.
    3. `open-first`: call `(store/open spec)`.
    4. Call `(install-store state {:index-store handle :index-store-spec spec :recovery (:recovery handle) :publication-policy (:publication-policy handle)})`.
    5. Close old `(:index-store state)` via `(store/close! old-store)` in a `try/catch`.
    6. Return `[state' (swap-summary spec (:recovery handle) nil)]`.

### 3. `src/cljc/yin/repl/adapter.cljc`, `src/cljc/yin/repl/driver.cljc`, `src/cljc/yin/repl/serve.cljc`
- **`src/cljc/yin/repl/adapter.cljc`**:
  - Add `'use-store` to `local-command-heads` (`:19-23`) so `(use-store ...)` is always treated as a local driver command, never forwarded as `:op/eval` to a remote RPC server when connected via `(connect ...)`.
- **`src/cljc/yin/repl/serve.cljc`**:
  - In `evaluate` (`:338-367`), refuse `(use-store ...)` if received over the unauthenticated remote `--port` endpoint (`"Error: (use-store ...) is a local host command and cannot be run over a remote endpoint"`).
- **`src/cljc/yin/repl/driver.cljc`**:
  - In `handle-line` (`:424-470`), add a branch for `(= 'use-store command)`:
    - If `(:swap-store state)` (a hook installed on driver `state` by `yin.repl.main/boot`) is present, call `((:swap-store state) state (rest (adapter/command-form line)))`, which returns `[state' text]` and publishes `text` to `:outbox` under `:yin.repl.driver/result`.
    - Otherwise (a standalone `driver/create-state` without `yin.repl.main/boot`), delegate to `(evaluate-locally state line)` which invokes `yin.repl/handle-command` → `yin.repl/swap-store`.

### 4. `src/cljc/yin/repl/main.cljc`
- Add `(swap-store [state args])` (installed on the driver `state` in `boot` as `:swap-store swap-store`):
  1. Check `(repl/swap-refusal (:repl state))` and composition quiescence (`(not (composition-admitting? state))` or `(seq (:awaiting (:session state)))`).
  2. Parse `(use-store ...)` `args`:
     - If `(:parse-store-command (:composition state))` exists, call it on `args` to obtain `{:spec spec :opts opts :label label}`.
     - Else validate 1 argument and parse with `(or (:parse-store-arg (:composition state)) store/parse-arg)`.
  3. **Open-first**:
     - If `composition` claims `spec` via `((:composition-spec? composition) spec)`:
       Call `((:open composition) spec (assoc opts :ws-host (:host state)))` to obtain `comp-store` (`{:index-store ... :recovery ... :publication-policy ... :engine-inputs ... :session ...}`).
     - Else open via `(store/open spec)`.
  4. **Install into `:repl`**:
     - Build `repl'` via `(repl/install-store (:repl state) ...)`. If this throws, call `(release-store! composition comp-store)` and rethrow.
  5. **Close old resources**:
     - Call `(release-store! composition {:session (:session state) :index-store (get-in state [:repl :index-store])})`.
  6. **Install new `:repl` and `:session`**:
     - Update `state` with `:repl repl'`, `:session (:session comp-store)`, and `:swapped-in-session? true`.
     - Format confirmation line plus any composition disclosure lines (`(:banner-lines composition)` and `(:startup-lines composition)`).
  7. Catch `#?(:cljd Object :clj Throwable :cljs :default) e`:
     - If `e` is a lock conflict on the currently open directory, append `"(currently held by this session; switch to mem first to release the directory lock)"`.
     - Return `[state (repl/format-error e)]`.
- In `step-all` (`:851-852`):
  - When `(composition-refusal state)` is non-nil and `(:swapped-in-session? state)` is true (an async post-swap UDP bind failure on Node/Dart), do **not** terminate the REPL (`:running? false`); instead, release the failed `:session` and `:index-store`, install a fresh `:mem` store into `:repl`, clear `:session`, and append a recovery notice line to `comp-lines`.

### 5. `src/cljc/yin/shell/cli.cljc`, `src/cljc/yin/shell/store.cljc`, `src/cljc/yin/shell/dht.cljc`, `src/cljc/yin/shell/main.cljc`
- **`src/cljc/yin/shell/cli.cljc`**:
  - Add `(parse-store-command [args])`:
    - Parses `(use-store <target> & <opts>)` where `<target>` is `"mem"`, `:mem`, `"file:<dir>"`, or `"dht:<dir>"`.
    - Validates trailing keyword options (`:peer`, `:peers`, `:publish`, `:bind`, `:port`, `:listen`, `:max-inbound-bytes`, `:key`, `:principal`, `:principals`, `:trust`, `:follow`, `:join`), refusing `:manifest` with the `(dao.space.dht/load-index ...)` hint and refusing DHT options on non-`dht:` targets.
    - Loads `:key` via `load-key` when specified.
    - Folds options through `dht-spec` (`:238-294`) and returns `{:spec checked-spec :opts {:publisher-key key :publisher-key-file key-path :principals principals :admission admission :index-store-spec checked-spec}}`.
  - Include `:parse-store-command parse-store-command` in `(hooks)` (`:679`).
- **`src/cljc/yin/shell/dht.cljc`**:
  - In `open` (`:635-672`): after `dht/join`, if `(seq peers)` is true, run a non-blocking check on the node's `traffic` ring buffer (via `bind-step` or inspecting the first event if already deposited on synchronous hosts). If `:dao.stream.datagram/bind-failed` is already present, close `node` and throw `(ex-info (str "dht: refused: the socket could not bind: " reason) {:dir dir :reason reason})` synchronously during `open`, so `open-first` aborts before touching the old store.

### 6. `host/gambit/yin/jolt/gambit/yin.jolt` + Gambit Verification (`build/tools/jolt` / fork `gambit-ffi-p1`)
- **`host/gambit/yin/jolt/gambit/yin.jolt`**:
  - Require `[dao.posix.file :as posix-file]`.
  - Add `dht-scheme` (`{:prefix "dht:" :label "dht:<dir>" :type :dht :parse (fn [dir] {:type :dht :dir dir})}`).
  - Add `parse-swap-command [args]` using `(store/parse-arg target [dht-scheme])` (refusing extra trailing options on `:mem` and passing `:file` / `:dht` specs through to `:open`).
  - Update `:composition-spec?` to `(constantly true)` and `:open` to `open-gambit`:
    - When `(= :mem spec)`: call `(open-memory spec opts)` (opening a fresh `:mem` store and preserving `jolt.ffi` and `dao.jing.mem` host modules).
    - When `(:dir spec)` is present (`:file` or `:dht`): call `(posix-file/realpath (:dir spec))`, which invokes `host/gambit/yin/dao/posix/file.jolt`'s `(refuse-blocking)` (`joltffi-public-blocking-raise`) and raises the exact C4 refusal message without touching `java.io.File` or loading `yin.shell.dht` / `jolt.winsock`.
- **`test/gambit/yin-repl-session.sh`**:
  - Extend the ordered state machine transcript with:
    - Unreset `(use-store "file:/tmp/gambit-nope")` → quiesce refusal; session state survives.
    - `(reset)` → `SemanticVM reset`.
    - `(use-store "file:/tmp/gambit-nope")` → `Error: blocking ffi is unsupported on the gambit target...`.
    - `(use-store "dht:/tmp/gambit-nope")` → `Error: blocking ffi is unsupported on the gambit target...`.
    - `(use-store "mem")` → `Switched index store to mem (code index cleared)`.
    - Post-swap evaluation `(+ 21 22)` → `43` and `(jolt.ffi/sizeof :int32)` → `4`.

### 7. Documentation (`src/cljc/yin/vm/docs/yin.repl.md` & `docs/design/yin.repl.dao.space-index.md`)
- Update `src/cljc/yin/vm/docs/yin.repl.md` §2 ("Working at the Prompt") to document `(use-store ...)`, the quiesce requirement (`(reset)` before switching), the `mem` ↔ `file:<dir>` ↔ `dht:<dir>` workflows, the `:peers` / `:listen` / `:publish` / `:key` / `:join` options for `dht:`, the non-interaction with `state.edn`, and the Gambit C4 honest refusal behavior.
- Update `docs/design/yin.repl.dao.space-index.md` ("Durable store: startup, HEAD, and the directory lock") to replace "There is no runtime switching" with the `(use-store ...)` quiesced `open-first` switching contract.

---

## Implementation Slice Plan

The implementation decomposes into four sequential, independently verifiable slices:

### Slice 1: Engine Quiescence & `mem` ↔ `file:<dir>` Store Swap (`yin.repl` & `yin.repl.store`)
- **Scope**:
  - `src/cljc/yin/repl/store.cljc` (`describe-spec`)
  - `src/cljc/yin/repl.cljc` (`:quiesced?` tracking, `quiesced?`, `swap-refusal`, `install-store`, `swap-store`, `use-store` in `command-heads` and `help-text`, fix `store/durable?` → `store/retain-on-reset?` at lines 2511 and 2569)
  - `src/cljc/yin/repl/adapter.cljc` (`'use-store` in `local-command-heads`)
  - `src/cljc/yin/repl/serve.cljc` (refuse `use-store` over remote `--port`)
  - `src/cljc/yin/repl/driver.cljc` & `src/cljc/yin/repl/main.cljc` (`use-store` dispatch and `open-first` driver swap hook)
  - `test/yin/repl/store_test.cljc`
- **Exit gate**:
  - Focused `yin.repl.store-test` passes on JVM, Node, Dart, and Jolt/Chez.
  - Gate A regex audit passes with zero matches in `src/cljc/yin/repl/` and `src/cljc/yin/repl.cljc`.

### Slice 2: DHT Composition Store Swap (`yin.shell.*`)
- **Scope**:
  - `src/cljc/yin/shell/cli.cljc` (`parse-store-command` and keyword option normalization into `dht-spec`; wire into `hooks`)
  - `src/cljc/yin/shell/dht.cljc` (synchronous `traffic` `:bind-failed` check in `open`)
  - `test/yin/repl/dht_test.cljc` (in-session swap tests: `mem` → `dht:<dir>` solo and with `:peers`/`:publish`/`:key`/`:join`, `dht:<dir>` → `mem` ring departure and resource release, failed `dht:` swap controls leaving old store intact)
- **Exit gate**:
  - Focused `yin.repl.dht-test` passes on JVM, Node, Dart, and Jolt/Chez.
  - Gate A & Gate B audits pass.

### Slice 3: Gambit Honest Degradation (`jolt` fork `gambit-ffi-p1` lineage + pin update)
- **Scope**:
  - In the Jolt repository (`host/gambit/yin/jolt/gambit/yin.jolt`, `test/gambit/yin-repl-session.sh`): wire `parse-swap-command` and `open-gambit` so `(use-store "mem")` swaps memory stores preserving `jolt.ffi`/`dao.jing.mem`, and `(use-store "file:<dir>")` / `(use-store "dht:<dir>")` invoke `dao.posix.file/realpath` to trigger the C4 blocking-FFI refusal inline while the session survives.
  - Advance `jolt-toolchain.edn` pin via the normal upgrade gate once verified on both platforms (macOS arm64 and Linux x86_64).
- **Exit gate**:
  - `make gambityinrepl` passes on both macOS arm64 and Linux x86_64 with the extended transcript.

### Slice 4: Documentation & Tri-Host Landing Gate
- **Scope**:
  - `src/cljc/yin/vm/docs/yin.repl.md`
  - `docs/design/yin.repl.dao.space-index.md`
- **Exit gate**:
  - Tri-host landing gate (JVM, Node, Dart, Jolt/Chez, Jolt/Gambit) + cross-family review sign-off.

---

## Extended Verification Bar

1. **Round-trip `mem` → `file:<dir>` → `mem` → `file:<dir>` (JVM, Node, Dart, Jolt/Chez)**:
   - Start on `mem`; evaluate `(def a 1)`; attempt `(use-store "file:<dir>")` without reset → verify refusal and that `a` is still `1`.
   - Run `(reset)`; run `(use-store "file:<dir>")` → succeeds with empty index.
   - Evaluate `(def persisted 42)`; require `dao.space.query` and verify `q` finds `persisted` and `<dir>/HEAD` exists on disk.
   - Run `(reset)` (verify durable index is retained across reset on `file:<dir>`); run `(use-store "mem")` → succeeds, `<dir>/lock` is released (verified by opening `<dir>` from a second store handle), and `q` on `mem` is empty.
   - Run `(reset)` and `(use-store "file:<dir>")` → reopens `<dir>`, validates and recovers `<dir>/HEAD`, rehydrates `:indexer`, and `q` immediately answers `persisted`'s AST datoms; evaluating `(def second-tx 99)` advances `t` and `e` monotonically without collision.
2. **DHT Ring Join and Departure `mem` → `dht:<dir>` → `mem` (JVM, Node, Dart, Jolt/Chez)**:
   - Start on `mem`; swap to `(use-store "dht:<dir-a>" :peers [...] :listen "127.0.0.1:<port>" :publish true :key "<key-file>")` → verify disclosure banner lines print, `yin.link`/`dao.space.dht`/`yin.head` modules become require-able, and a published module is fetchable by a peer.
   - Run `(reset)` and `(use-store "mem")` → verify follower dials, head board server, UDP socket, and `<dir-a>/lock` are all closed, `:session` is `nil`, and `(require 'dao.space.dht)` is no longer registered on `mem`.
3. **Failed-Swap Controls (All Backends — P1e Gate)**:
   - While on `file:<dir-1>` with committed facts, attempt `(use-store ...)` to:
     - An unknown scheme (`"s3:bucket"`)
     - A path occupied by a regular file (`"file:<blocker-file>"`)
     - A directory locked by another store handle (`"file:<locked-dir>"`)
     - The same currently held directory (`"file:<dir-1>"` or `"dht:<dir-1>"`)
     - A directory with a corrupt `HEAD` or truncated `content.jing`
     - A `dht:<dir-2>` with a missing `--dht-key` file, corrupt `heads.edn`, or `:manifest` pin flag
   - Verify that every single failure prints `Error: ...`, leaves `file:<dir-1>` locked and active, preserves `file:<dir-1>`'s rehydrated index in `q`, and allows subsequent evaluation and publication to `file:<dir-1>`.
4. **Remote `--port` Isolation Control**:
   - Connect a client via `(connect "daostream:ws://...")` to a `--port` server; verify that typing `(use-store ...)` on the client executes locally on the client's driver (never sent over RPC), and that a raw `:op/eval` request containing `"(use-store \"mem\")"` sent directly to `yin.repl.serve` is refused without mutating the server's store.
5. **Gambit C4 Refusal-Then-Survive (`jolt-gambit` on macOS arm64 & Linux x86_64)**:
   - In `test/gambit/yin-repl-session.sh`, verify `(use-store "file:<dir>")` and `(use-store "dht:<dir>")` refuse with `Error: blocking ffi is unsupported on the gambit target — GC-safe blocking is unqualified on Gambit 4.9.8 (no verified release/claim transition)`, `(use-store "mem")` succeeds and retains `jolt.ffi` and `dao.jing.mem`, and subsequent evaluations and `(quit)` succeed with exit status 0.
6. **Gate A & Gate B Boundary Audit**:
   - Run both Gate A `rg` checks from `docs/design/yin.repl.dht-composition.md` §10.1 over `src/cljc/yin/repl/` and `src/cljc/yin/repl.cljc` (zero matches).

---

## Risks and Open Questions for the Owner

1. **Same-directory `file:<dir>` ↔ `dht:<dir>` transition (Two-Step via `mem` vs In-Place Lock Handoff)**:
   - Under strict `open-first` (Decision 2), switching directly from `file:<dir>` to `dht:<dir>` on the **exact same `<dir>`** in one command hits `dao.space.store.fs/lock!`'s in-process lock check because `file:<dir>` still holds `<dir>`'s lock while `dht:<dir>` is opening. The operator instead runs `(use-store "mem")` (releasing `<dir>`'s lock) followed by `(use-store "dht:<dir>" ...)` (which re-locks `<dir>` and recovers `<dir>/HEAD`).
   - *Recommendation*: Keep strict `open-first` (the two-step bounce through `mem` for same-directory transitions) so there is never a `close-first` window where `<dir>`'s lock is dropped before `dht:` join validation finishes. If the owner prefers a single-command same-directory upgrade (`file:<dir>` → `dht:<dir>`), `yin.shell.dht/open` already accepts `:open-store` (`src/cljc/yin/shell/dht.cljc:628`) and can be passed a lock-transferring wrapper after all non-lock validations (`checked-spec`, `checked-follow`, `load-key`, `heads-records`) succeed.
2. **In-session key generation vs pre-existing key files for `dht:`**:
   - This design requires `:key "<path>"` in `(use-store "dht:<dir>" ...)` to reference an existing Ed25519 key file (created via `yin-repl keygen` or an earlier `yin-repl dht init`), refusing if the file does not exist (`yin.shell.cli/load-key`). If the owner wants an in-session `(keygen "<path>")` command or `:init-key "<path>"` option on `(use-store ...)`, it can be added as an explicit opt-in in Slice 2.
