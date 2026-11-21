Completed-GMT: 2026-11-21 18:07:05 GMT
Completed-Local: 2026-11-22 01:07:05 ICT (+07)
Created-GMT: 2026-11-21 17:10:21 GMT
Created-Local: 2026-11-22 00:10:21 ICT (+07)
Coding-Agent: Codex (gpt-6.2-sol)
Session-ID: 01a1ff16-8664-7961-8de5-d59245d34ee7
Role: VM Runtime Engineer (implementation)

# P1f — memory-backed dao.jing.dht on Jolt Gambit

Local branch `gambit-ffi-p1f` in `/Users/sto/workspace/jolt-ffi-p1f`, based on
`gambit-ffi-p1e` at `0aa7f8a470beeb33988583a1eaf6d069718c4bea`.
Committed `bbd35983ee5d5f7c3816a3fcd7540039aecbb8c4` locally. Datomworld input `533216bec2354d9263a8a17a146a3e8172b39a64`.

| Blocker | Status | Implementation and evidence |
| --- | --- | --- |
| Missing shim rows and byte copies | Fixed | `host/gambit/ffi/shapes.json:326` declares four finite tuples. `inet_pton` uses three arguments and `inet_ntop` uses four, including its destination pointer. `sendto` and `recvfrom` each use six arguments with invocation-local errno capture. The generated shims, C fixtures, source digests, and local toolchain provenance were verified. `host/gambit/ffi/gambitffi-abi.ss:242` checks output pointers, wide size/result values, unsigned socklen, and errno. `host/gambit/host-ffi.ss:229` uses the existing u8vector bulk-copy bridge for two-argument `read-array` and `write-array`. |
| Socket close | Datagram cleanup works through a native worker. Inline close stays refused. | `stdlib/jolt/socket/native.clj:267` keeps the `:blocking` binding. The Gambit datagram branch at line 615 checks the local family and socket type before calling the worker. The C worker at `host/gambit/ffi/joltffi-bridge.scm:98` captures the syscall result and errno immediately. It owns only C memory and the descriptor. Gambit parks between polls at line 277. `host/gambit/ffi/gambitffi-abi.ss:280` checks success, verifies that the descriptor is closed, and checks the worker's EBADF. |
| Session source `ffi/defcfn` | Fixed | `host/gambit/yin-repl-driver.ss:75` leaves only `clojure.core` in the booted set, so the source loader loads canonical `stdlib/jolt/ffi.clj`. `host/gambit/source-loader.ss:87` adds the Gambit reader feature while evaluating a source namespace. The source gate expands the canonical declaration macro at `host/gambit/source-loader-test.ss:57`. |
| `java.io.File` | Constructor fixed. File I/O stays refused. | `host/gambit/host-statics.ss:199` registers the path constructor and `getPath`. The same shim sends `exists`, `isDirectory`, `canWrite`, and `mkdirs` to the C4 refusal. The durable store's POSIX file overlay remains behind that refusal. |
| `ClassCastException` | Fixed | `host/chez/collections.ss:184` and `:271` check `rrbnode?` when selecting the relaxed traversal. Gambit represents an RRB record as a vector. The old dense fast path read the record's tag, `16`, and passed it to `vector-ref` as a child. P1e also recognized the plain vector `#(16 99)` as an RRB record. `host/gambit/prelude-shims.ss:100` assigns private identity tags, with the corresponding identity-hash check at `host/gambit/hasheq.ss:553`. |
| Store admission and composition | Memory-backed `dht:` admitted. Durable `dht:<dir>` remains Chez-only. | `host/gambit/yin/jolt/gambit/yin.jolt:34` joins the portable Dao DHT node over a memory store and supplies its handle and message-derived payload budget to Yin. The CLI accepts the exact `dht:` argument at line 56. The profile opens a solo node. The loopback gate exercises the native UDP transport in that session. |

## Close evidence and platform detection

The descriptor flag alone cannot prove a bounded close. Darwin's XNU close
path checks for a content filter independently of linger, and its UDP filter
path can wait for a filter to detach. Linux's UDP close handler calls
`sk_common_release` without consuming the linger timeout. This evidence
supports keeping synchronous socket close behind C4 and using the separate
native worker for datagram cleanup. The worker enters no Gambit code and
performs no GC release or claim. [XNU socket close](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/kern/uipc_socket.c#L1040-L1073), [XNU UDP filter close](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/net/content_filter.c#L6374-L6392), [Linux UDP close](https://github.com/torvalds/linux/blob/master/include/net/udp.h#L301-L304)

The Gambit OS adapter had returned Linux on every host. On Darwin that caused
the socket namespace to read the BSD sockaddr header as a 16-bit Linux family.
`host/gambit/scheme-adapter-runtime.ss:261` derives Darwin from Gambit's
configured system triple. The adapter gate compares it with the native
bridge's compile-time platform.

## Verification

Darwin arm64, native Gambit 4.9.8. The stage-1 matrix passed
`gambitffi`, `gambitcheck`, `gambitkernel`, `gambiteval`,
`gambitgencheck`, `gambitseedcheck`, `emittedheads`, and `deadhost`.
The ABI gate ran 92 checks and the public FFI gate ran 50, with zero
failures. `gambitsourceload` also passed. The shared Chez RRB property
gate passed 11,170 checks. Native Darwin IPv6 address conversion returned
`["::1" 12345 28]`. Linux execution is the orchestrator's follow-up.

`make gambitdht` builds the bridge and compiled boot and runs both halves of
`test/gambit/yin-dht-probe.sh`. In this run the stock and diagnostic halves
ran as separate foreground invocations with a 2,400-second watchdog per
launcher. The diagnostic copy adds one test host module around the same
production store opener. The final transcript assertions were applied to
the original diagnostic receipt after accepting the vector delimiter
spacing emitted by Yin. Both launchers exited 0 and the diagnostic store
directory stayed empty.

```text
yin> 42
yin> Bye
stock launcher exit 0 (785s)
```

```text
yin> 42
yin> 'p1e.probe
yin> [ :dht-index-put-get :segment/blake3-9ae30c66b5af21de9431ed9aca9a48028df9cd2df20c2f7defb74f8740110de9 {:p1e "yin-dht-local-round-trip", :count 3} ]
yin> Error: blocking ffi is unsupported on the gambit target — GC-safe blocking is unqualified on Gambit 4.9.8 (no verified release/claim transition)
yin> :blocked-no-durable-dht-handle
yin> :blocked-no-durable-dht-handle
yin> Error: blocking ffi is unsupported on the gambit target — GC-safe blocking is unqualified on Gambit 4.9.8 (no verified release/claim transition)
yin> :memory-dht-handle-opened
yin> :segment/blake3-9ae30c66b5af21de9431ed9aca9a48028df9cd2df20c2f7defb74f8740110de9
yin> {:p1e "yin-dht-local-round-trip", :count 3}
yin> true
yin> [:udp-loopback-ok "Kv8A" :closed]
yin> :socket-native-loaded
yin> Error: blocking ffi is unsupported on the gambit target — GC-safe blocking is unqualified on Gambit 4.9.8 (no verified release/claim transition)
yin> :closed
yin> 43
yin> Bye
diagnostics launcher exit 0 (2093s)
```

Receipts are under `build/gambit-dht/run.V058gh/`, `build/gambit-dht/run.TnOT3j/`, and
`build/p1f/matrix-verified.log` in the P1f worktree.

DONE
