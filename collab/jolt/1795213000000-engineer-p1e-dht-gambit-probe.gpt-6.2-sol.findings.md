Completed-GMT: 2026-11-21 14:47:02 GMT
Completed-Local: 2026-11-21 21:47:02 ICT (+07)
Created-GMT: 2026-11-21 14:13:12 GMT
Created-Local: 2026-11-21 21:13:12 ICT (+07)
Coding-Agent: Codex (gpt-6.2-sol)
Session-ID: 01a1fe75-bb77-71f3-8194-8a33c3f33950
Role: VM Runtime Engineer (evidence-only)

# P1e — dao.jing.dht on Jolt Gambit

**MIXED.** A memory-backed DHT handle opens and reads, including an existing value seeded in its local backing store. The stock Gambit launcher refuses `dht:`. Caught defects block the composed directory store, a portable DHT put, and UDP. The live Yin session continued and quit cleanly.

Base `gambit-ffi-p1c` at `f22df3c70dce4e7f725df580daa8991a41420660`. The two probe files are committed locally on `gambit-ffi-p1e` at `0aa7f8a470beeb33988583a1eaf6d069718c4bea` in `/Users/sto/workspace/jolt-ffi-p1e`. Source paths below are relative to Jolt (`host/`, `stdlib/`, `jolt-core/`) or Datomworld (`src/`).

## Static trace

- The native launcher searches the Gambit overlays first, Datomworld source next, then the fork's stdlib and core (`host/gambit/yin-repl-driver.ss:75–84`). It marks `clojure.core` and `jolt.ffi` as already supplied by the boot. The loader skips those names in `host/gambit/source-loader.ss:65–75`. The shared reader enables `:jolt`, `:clj`, and `:default` in `host/chez/reader.ss:1106–1126` and takes the first matching branch in source order.
- The stock entry's `parse-store` calls the engine parser without extra schemes and only admits `:mem` (`host/gambit/yin/jolt/gambit/yin.jolt:26–41`). The engine parser throws `Unknown --index-store …; supported: mem, file:<dir>` for `dht:` (`src/cljc/yin/repl/store.cljc:68–106`). The generic supported-list text includes `file:<dir>`. Gambit's next check refuses it. The startup driver reports operator refusals with status 1 before entering its input loop (`src/cljc/yin/repl/main.cljc:588–597, 675–691, 1206–1225`).
- The shipped DHT composition registers `dht:` in `src/cljc/yin/shell/store.cljc:24–48`. Its `open` delegates to `yin.shell.dht/open` at lines 102–112. That factory opens `dao.space.store/open` before it joins a node (`src/cljc/yin/shell/dht.cljc:635–656`). The durable open checks the directory, then acquires the store lock before opening the content log (`src/cljc/dao/space/store.cljc:98–126, 225–247, 281–309`). The Jolt lock first canonicalizes with `dao.posix.file/realpath`, then uses `lock-file!` (`src/cljc/dao/space/store/fs.cljc:181–188, 222–226, 246–266`). The active Gambit POSIX file overlay puts `realpath`, `lock-file!`, content-log operations, HEAD read/write and directory sync behind the same C4 blocking refusal (`host/gambit/yin/dao/posix/file.jolt:6–25`). The overlay runs no file FFI.
- The composition defaults to `peers []`, `publish? false`, loopback and ephemeral port (`src/cljc/yin/shell/dht.cljc:228–237`). A solo join constructs no socket. With peers, `dao.space.dht/node-over` invokes its default `bind!` (`src/cljc/dao/space/dht.cljc:135–142, 254–289`). That namespace requires the UDP host eagerly under `:jolt` at lines 52–69, even for a solo store. The UDP namespace requires `jolt.io-poller` and `jolt.socket.native` (`src/jolt/dao/stream/udp.jolt:7–15`). The poller also requires socket-native (`stdlib/jolt/io_poller.clj:50–55`). Socket-native requires `jolt.winsock` at `stdlib/jolt/socket/native.clj:34–36`. Its `WSAStartup` declaration is unconditional, with the actual Windows call guarded later (`stdlib/jolt/winsock.clj:34–54`). `ffi/defcfn` comes from `stdlib/jolt/ffi.clj:1751–1771`, which the booted-namespace shortcut skips. The Gambit FFI overlay supplies loader and memory vars, while the declaration macro is missing. The stock terminal wake and cadence use the Scheme waitset overlay, which bypasses the Datomworld POSIX pipe/read driver (`host/gambit/yin/dao/stream/waitset/driver.jolt:1–18`).
- The portable `dao.jing.dht` namespace depends on the datagram stream protocol and wire encoding in `src/cljc/dao/jing/dht.cljc:28–35`. Its `store-handle` at lines 1365–1393 delegates to the supplied local store and appends a replicate request on put. Its get reads that local store directly. Default address generation uses the Gambit Scheme BLAKE3 overlay. The SHA-256 variant raises the C4 refusal (`host/gambit/yin/dao/host/hash.jolt:7–23`).

### Native rows on the transport path

The table describes the ABI registry independently of source loading. “Qualified shape” means that the manifest has a matching ABI tuple. Live UDP requires the namespace to load and the exercised operations to complete. The inventory is `origin/gambit-ffi-inv` at `98925c18`, `p1b-inv/CONSUMERS.md` §4.2–4.3. Its shape IDs refer to `p1b-inv/shim-registry.edn`. The implemented registry is `host/gambit/ffi/shapes.json` and its generated lookup at `host/gambit/ffi/generated-shims.scm:339–382`. Matching includes result type, capture mode and variadic boundary. The runtime's unmatched-shape error is at `host/gambit/scheme-adapter-runtime.ss:494–500, 535–541, 561–567`. The compiler routes marked blocking bindings to the C4 refusal before consulting the registry (`jolt-core/jolt/backend_scheme/ffi.clj:463–471`, `host/gambit/scheme-adapter-runtime.ss:622–633`).

| Row | Source declaration or use | Gambit classification |
| --- | --- | --- |
| Winsock declaration in the source-load path | `stdlib/jolt/winsock.clj:41` | Not yet implemented in the booted namespace. Loading stops while resolving `ffi/defcfn`; the platform-guarded foreign call is never attempted. |
| Global loader and native buffers, strings and scalar reads/writes | `stdlib/jolt/socket/native.clj:205`, `src/jolt/dao/stream/udp.jolt:116–161` | Qualified stage-1 primitives in `host/gambit/host-ffi.ss:413–447`. |
| `socket`, `bind`, `getsockname`, IPv6 `setsockopt` | `stdlib/jolt/socket/native.clj:216–226`, `src/jolt/dao/stream/udp.jolt:223–268` | Qualified capture shapes S26, S26b, S26e, S26d. |
| `fcntl` for close-on-exec and nonblocking flags | `stdlib/jolt/socket/native.clj:284, 433–457` | Qualified S22b, captured, two fixed arguments. The poller’s ordinary `fcntl` declaration at `stdlib/jolt/io_poller.clj:90` is S22. |
| `inet_pton` for the loopback literal, `inet_ntop` for IPv6 address formatting | `stdlib/jolt/socket/native.clj:232–233, 552–566`, `src/jolt/dao/stream/udp.jolt:145–185` | Not yet implemented. The ordinary `int, pointer, pointer → int` and `int, pointer, uint → pointer` tuples are absent. IPv4 return formatting uses scalar byte reads. |
| UDP `sendto`, `recvfrom` | `src/jolt/dao/stream/udp.jolt:102–109, 300–303, 330–336` | Not yet implemented. Both six-argument errno-capture tuples are absent. They are declared without `:blocking` and run over an explicitly nonblocking fd. They are different bindings from the stdlib's marked `send` and `recv`. |
| UDP payload copies `write-array`, `read-array` | `src/jolt/dao/stream/udp.jolt:300–301, 373–375` | Not yet implemented. Public calls explicitly refuse in `host/gambit/host-ffi.ss:391–399`. |
| UDP cleanup `close` | `stdlib/jolt/socket/native.clj:267`, `src/jolt/dao/stream/udp.jolt:229–233, 270–276` | Blocking-refused. This close is marked blocking plus capture. Poller `cancel!` and `forget!` update wait state and wake existing waiters (`stdlib/jolt/io_poller.clj:456–488`). |
| Poller readiness `getsockopt` | `stdlib/jolt/io_poller.clj:93`, `stdlib/jolt/socket/native.clj:224–225` | Qualified capture shape S23. |
| Poller wait and related socket rows declared by dependencies | `stdlib/jolt/io_poller.clj:96–105`, `stdlib/jolt/socket/native.clj:219–220, 230, 235–237, 262–278` | `kevent` wait, `epoll_wait`, `connect`, `getaddrinfo`, `getnameinfo`, `recv`, `send`, `accept` and `poll` are blocking-refused if constructed. The Darwin `kqueue` tuple is S01; ordinary captured `kevent` change and ordinary `epoll_create1` / `epoll_ctl` tuples are absent. The UDP receive loop uses its own bounded nonblocking drain. |

The inventory lists the captured `kevent` change under S23. Its six-argument source tuple differs from the five-argument S23 tuple in the implemented manifest.

The injected fixture invokes the real shell-store factory and real UDP seam lazily. Its portable DHT handle uses a fresh local in-memory DaoJing store and request ring. It derives the lookup key independently and inspects the ring after the write. Loopback, if reached, binds `127.0.0.1:0` and sends a one-byte payload to the socket's own address. Each session runs the existing native Yin launcher and watchdog. Source traces and full stdout, stderr, and exit status are retained separately.

## Session receipts

Darwin arm64, macOS 26.6.2, native Gambit 4.9.8, Datomworld `533216bec2354d9263a8a17a146a3e8172b39a64`. The harness ran each launcher in the foreground with a 900-second watchdog and redirected stdout and stderr to separate files. The worktree's pinned irregex input came from the existing local copy at `c948a704fc732914a243c1643bfe359913d11c7b`. The existing bridge build verified all seven toolchain inputs before compiling.

From the P1e worktree, with the existing bridge and compiled boot built using the normal `gambityinrepl` prerequisites:

```sh
sh test/gambit/yin-dht-probe.sh \
  /Users/sto/.local/share/mise/installs/gambit/4.9.8/bin/gsi \
  /Users/sto/workspace/datomworld \
  "$(cat build/p1e/boot.path)"
```

The full raw receipts are under `build/p1e/run.3TmmZx/` in the P1e worktree. `build/p1e/probe-run.log` records both launch results and elapsed time. The stderr source trace uses `JOLT_TRACE_SOURCE=1`.

### Stock entry, `--no-state --index-store dht:<dir>`

Stdin contained `(+ 20 22)` and `(quit)`. The parser refused before the session loop, with zero prompts and launcher status 1. The directory was pre-created and empty.

```text
boot: backend target -> :gambit
Unknown --index-store "dht:/Users/sto/workspace/jolt-ffi-p1e/build/p1e/run.3TmmZx/store"; supported: mem, file:<dir>
stock launcher exit 1 (670s)
```

The stock trace reached the following namespaces. Its source-load list had no `dao.jing.dht`, `dao.stream.udp`, or native socket entries.

```text
[1795271015s] ready dao.jing.cbor
[1795271062s] ready dao.posix.file
[1795271064s] ready dao.jing.file
[1795271314s] ready dao.space.store
[1795271643s] ready yin.repl.main
[1795271643s] ready jolt.gambit.yin
```

### Live Yin diagnostics

The fixture registers one host module in a scratch copy of the Gambit memory composition. The production startup input above and the operations below therefore have separate process receipts. The diagnostic session lazily invokes the actual shell store factory, the durable directory open, the portable DHT handle, and the real UDP entry. The script checked that all 16 prompts appeared, the probe required successfully, and the final arithmetic and quit replies arrived.

```text
boot: backend target -> :gambit
Host: jolt / Gambit v4.9.8
python: unavailable-parser (the native parser library is missing or incompatible; bb build:jolt-native)
yin> 42
Warning: this round's code is not in the published code index (project failed)
yin> 'p1e.probe
Warning: this round's code is not in the published code index (project failed)
yin> Error: No such var: ffi/defcfn
Warning: this round's code is not in the published code index (project failed)
yin> :blocked-no-durable-dht-handle
Warning: this round's code is not in the published code index (project failed)
yin> :blocked-no-durable-dht-handle
Warning: this round's code is not in the published code index (project failed)
yin> Error: (new java.io.File) is unsupported on the gambit target: no shim registers the class (host/gambit/host-statics.ss)
Warning: this round's code is not in the published code index (project failed)
yin> :memory-dht-handle-opened
Warning: this round's code is not in the published code index (project failed)
yin> Error: java.lang.ClassCastException: (Argument 1, vector) VECTOR expected (vector-ref 16 0)
Warning: this round's code is not in the published code index (project failed)
yin> :missing
Warning: this round's code is not in the published code index (project failed)
yin> nil
Warning: this round's code is not in the published code index (project failed)
yin> Error: Earlier namespace load failed: dao.stream.udp
Warning: this round's code is not in the published code index (project failed)
yin> Error: Earlier namespace load failed: jolt.socket.native
Warning: this round's code is not in the published code index (project failed)
yin> Error: blocking ffi is unsupported on the gambit target — GC-safe blocking is unqualified on Gambit 4.9.8 (no verified release/claim transition)
Warning: this round's code is not in the published code index (project failed)
yin> :closed
Warning: this round's code is not in the published code index (project failed)
yin> 43
Warning: this round's code is not in the published code index (project failed)
yin> Bye

diagnostics launcher exit 0 (730s)
```

The source trace shows the initial shell open entering the UDP dependency chain and the independent portable DHT load reaching ready.

```text
[1795272321s] load yin.shell.store
[1795272321s] load yin.shell.dht
[1795272322s] load dao.space.dht
[1795272322s] load dao.stream.udp
[1795272322s] load dao.stream.datagram
[1795272324s] ready dao.stream.datagram
[1795272324s] load dao.stream.udp.common
[1795272327s] ready dao.stream.udp.common
[1795272327s] load jolt.io-poller
[1795272327s] load jolt.socket.native
[1795272327s] load jolt.winsock
[1795272337s] load dao.jing.dht
[1795272338s] load dao.jing.dht.kad
[1795272338s] ready dao.jing.dht.kad
[1795272351s] ready dao.jing.dht
```

The directory remained empty. The UDP attempt ended before socket creation, so it could not test a bind, send, receive, or cleanup. The direct component probe repeated the original `No such var: ffi/defcfn` error on a fresh UDP load. In the live session the subsequent UDP and socket attempts returned the cached load failures. The Yin driver also emitted code-index projection warnings during this run. The probe recorded them without assigning them a cause.

### Component isolation on the same native boot

Smaller watchdogged source-loader runs isolated the file, transport, CBOR and vector paths. The first reused the diagnostic fixture's operation bodies. The second used the same payload and content address, called `dao.jing.dht/store-message-size` directly, then preseeded the local memory backing and read through the DHT handle. The component receipts are in `build/p1e/diagnostic-evidence/`.

```text
address: :segment/blake3-9ae30c66b5af21de9431ed9aca9a48028df9cd2df20c2f7defb74f8740110de9
bytes-match: true
bytes-alloc: 16
bytes-to-vector: [0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0]
wire-int: [16]
wire-bytes: [80 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0]
wire-store-size: (Argument 1, vector) VECTOR expected
(vector-ref 16 0)
dht-put: (Argument 1, vector) VECTOR expected
(vector-ref 16 0)
dht-read-after-dht-put: :missing
local-put: :segment/blake3-9ae30c66b5af21de9431ed9aca9a48028df9cd2df20c2f7defb74f8740110de9
local-read: {:p1e "yin-dht-local-round-trip", :count 3}
dht-read-preseeded: {:p1e "yin-dht-local-round-trip", :count 3}
```

The failing put passes address and byte validation. It fails when sizing the encoded store envelope, before the local write or request append (`src/cljc/dao/jing/dht.cljc:228–240, 1381–1395`). The wire encoder for Jolt is `src/jolt/dao/stream/cbor.jolt:49–54` and `src/jolt/dao/stream/cbor/boring.jolt:414–468`. The same error reproduced for `(wire/encode {:id (bytes/alloc 32)})`. Encoding the bare 32-byte value returned 34 bytes. The map encoder prepends the map header with `into` at `src/jolt/dao/stream/cbor/boring.jolt:450–459`. A smaller source-loaded Jolt reproduction pinned the problem to vector concatenation and reading its result.

```clojure
(let [v (into [161] (vec (range 33)))]
  [(count v) (nth v 0)])
```

```text
prepend-to-32: [33 161]
prepend-to-33: (Argument 1, vector) VECTOR expected
(vector-ref 16 0)
prepend-to-40: (Argument 1, vector) VECTOR expected
(vector-ref 16 0)
```

The vector/vector arm of `into` calls `pvec-catvec` (`host/chez/seq.ss:2157–2164`, `host/chez/collections.ss:815–830`). The Gambit boot includes that collections source at `host/gambit/boot.ss:37`.

The independent directory open reaches the generic `java.io.File` constructor fallback (`src/cljc/dao/space/store.cljc:60–66, 114–126`, `host/gambit/host-statics.ss:185–195`). The Jolt reader selects the `:clj` directory preflight. It fails before the Gambit `realpath` overlay, which produces the documented C4 refusal when called directly. The UDP path fails while loading the eager Winsock declaration. These are separate from the missing ABI shapes listed above.

## Capability rows

| Operation | Result | Witness |
| --- | --- | --- |
| Stock CLI `--index-store dht:<dir>` | Refuses with the startup parser's documented message | `Unknown --index-store …`. Status 1 before the first prompt. |
| Open a `dht:` spec through the actual Yin shell store factory | DEFECT, caught | Eager Winsock dependency reports `No such var: ffi/defcfn`. |
| Durable DHT put | Blocked by open | `:blocked-no-durable-dht-handle`. No backend write dispatched. |
| Durable DHT get | Blocked by open | `:blocked-no-durable-dht-handle`. No backend read dispatched. |
| Open the durable directory independently | DEFECT, caught | Generic unsupported `java.io.File` constructor message before directory canonicalization or locking. |
| Construct a DHT handle over a memory store and a request ring | WORKS | `:memory-dht-handle-opened`. |
| Put through the portable memory-backed DHT handle | DEFECT, caught | `ClassCastException` sizing the wire envelope. The local store and replication ring received no write from that put. |
| Get through the portable handle after the failed put | WORKS for a miss | The independent address returns the supplied `:missing` sentinel. |
| Read an existing payload through the portable handle | WORKS | The isolated control preseeded the local memory store and recovered the full payload through the DHT handle. |
| Inspect the replication request after the failed put | Blocked by put | The request ring read yields `nil`. |
| Native loopback UDP at `127.0.0.1:0` | DEFECT, caught during source load | The fresh UDP attempt encounters unresolved `ffi/defcfn`. The live retry reports the earlier `dao.stream.udp` load failure. |
| Load `jolt.socket.native` independently at the prompt | DEFECT, caught | `Earlier namespace load failed: jolt.socket.native` after the original Winsock error. |
| Direct file-overlay `realpath` control | Refuses with documented C4 message | `blocking ffi is unsupported on the gambit target` with the GC-safe explanation. |
| Close the memory-backed handle | WORKS | `:closed`. |
| Evaluate after the failures | WORKS | `yin> 43`. |
| Quit the same session | WORKS | `yin> Bye`, status 0. |

The runtime verdict covers this Darwin arm64 run. Both main-session processes completed within their watchdog deadlines. The C4 control verified refusal and session survival.

DONE
