# Jolt Experiment (2026-09-06)

Follow-up: [`jolt-vs-jank.md`](./jolt-vs-jank.md) covers the port plan to run on jolt and the jolt-vs-jank comparison.

## Overview

We evaluated whether the `dao.stream-redesign-v2` branch passes tests using `jolt` (https://github.com/jolt-lang/jolt) instead of `bb test:clj` on the JVM.

## Verdict

**jolt (v0.8.3) is not a viable replacement test runner for `bb test:clj` today — but the codebase loads far better than expected**, and the JVM baseline over the same namespaces is green, so every jolt discrepancy is a compat gap, not a branch regression.

## Evidence

| Lane | Load | Tests | Assertions | Fail | Err |
|---|---|---|---|---|---|
| JVM (`clojure -M:test`) | 112/112 | 1,272 | 164,691 | 0 | 0 |
| jolt (`jolt -A:test` + `clojure.test/run-tests`) | 112/115 | 1,254 | 158,489 | 94 | 537 |

- Core layers genuinely run: `dao.data.btree-test` passes **142,920 assertions with 5 errors**; transients are fully clean.
- The wall is JDK-class shims: `MessageDigest` (230 errors — DaoJing content addressing), jolt's `subseq` rejecting `BTSet` (129), transit (60), `RandomAccessFile`/`DatagramSocket`/`parallelSort`/`Double.isFinite`, plus http-kit making 3 namespaces unloadable. 2 namespaces crash mid-run. VM-engine tests are ~50× slower.
- Useful discovery: jolt honors **`:jolt` reader-conditional branches** (taking precedence over `:clj`) — a clean porting hatch for the shim gaps.

Namespaces that fail to **load** under jolt (3 of 115):

| Namespace | Cause |
|---|---|
| `dao.stream.http-test` | http-kit (`org.httpkit.client`) — Java bytecode; jolt trips on `Failed to parse Java version string` inside it |
| `agent.tools-test` | same root via `dao.stream.http` require chain (`java.time.format.DateTimeFormatter` unprovided) |
| `agent.tzu-test` | same chain |

(`world.server` and `world.datom.ollama`, two src namespaces, fail for the same reasons; nREPL is also unprovided for `world.server`.)

## Failure taxonomy (jolt run, by distinct cause)

| Count | Cause | Hit namespaces |
|---|---|---|
| 230 | `No dependency provides java.security.MessageDigest` (jolt RFC 0014 `:jolt/provides` needed) | dao.jing, dao.jing.file, dao.jing.dht, dao.space.* (content addressing) |
| 129 | `subseq/rsubseq require a sorted collection: class dao.data.btree.BTSet` | dao.data.btree-protocols-test, dao.data.btree-durability-test |
| 60 | `Unknown class TransitFactory$Format` | dao.stream.v2.transit-test, yin.repl.v2* |
| 29 | `No matching field or method: Double/isFinite` | dao.jing, dao.postgraphics.math |
| 25 | `No matching field or method: java.util.Arrays/parallelSort` | dao.stream.log → dao.jing.file fixture path |
| 25 | `No matching ctor found for java.io.RandomAccessFile` | dao.jing.file |
| ~20 | `No method -write in clojure.pprint/IPrettyWriter` (pprint shim gap) | yin.repl-test |
| 13 | `Unknown class ContextLogger`, `No matching ctor DatagramSocket` | dao.stream.udp / remote-stream paths |
| 3 | `No matching ctor LinkedBlockingQueue` | dao.stream v2 queues |
| 2 | `ExceptionInfo: missing index segment` (btree durability hydration) | dao.data.btree-durability-test — **flag for investigation**: may be a genuine semantic divergence (hash/identity ordering), not just a shim gap |

Everything above is a *jolt-side* gap except the last row. The JVM run of the same namespaces reports zero failures/errors, so none of these indicate a bug in the branch itself.

## What works well

- **deps.edn resolution**: jolt parsed our `deps.edn`, resolved and fetched the whole Maven graph into `~/.m2` itself (no Java), including `stigmergy/chp` and Datomic's tree. Unloadable jar bodies (Java classes) are inert rather than fatal.
- **Loading breadth**: 244 of 252 total namespaces (src + test) load; 112 of 115 test namespaces load.
- **Language coverage**: `.cljc` with `:clj` branch selection, `deftype` with `^:unsynchronized-mutable` and `set!`, defrecord, protocols, core.async (`go` blocks verified), all worked in probes. `:jolt` reader-conditional branches are **supported and take precedence over `:clj`**.
- **clojure.test works**: `deftest`/`is`/`testing`, fixtures, and `(clojure.test/run-tests 'ns …)` behave. Solo runs give exact numbers for the core layers: `dao.data.btree-test` — 15 tests, **142,920 assertions, 0 failures, 5 errors**; `dao.data.btree-transients-test` — 13 tests, 11,623 assertions, **fully clean**.

## Incompatibilities found (minimal repro included)

1. **deftype fields invisible inside map/set literals** (jolt analyzer bug — `error[analyze/unresolved-symbol]`). Repro:
   ```clojure
   (deftype T [^:unsynchronized-mutable a]
     P
     (m [this] {:k a}))   ; "Unable to resolve symbol: a in this context"
   ```
   Vectors, `(hash-map :k a)`, and `this` inside the literal all work. This blocked all of `dao.data.btree`.
   **Fix applied** (behavior-preserving on every host): `src/cljc/dao/data/btree.cljc:1236` — literal `{:address address}` → `(array-map :address address)`.
2. **jolt's `subseq`/rsubseq` don't recognize `clojure.lang.Sorted` implemented by user deftypes** — BTSet implements the full `:clj` interface set, but jolt's `subseq` rejects it. Needs a jolt fix.
3. **JDK classes must be provided by libraries** (`:jolt/provides`, RFC 0014): `MessageDigest`, transit classes, `RandomAccessFile`, `DatagramSocket`, `Arrays/parallelSort`, `Double/isFinite`, `LinkedBlockingQueue`, `java.time.format.DateTimeFormatter`.
4. **Escaped exceptions abort the whole batch**: an uncaught error inside a test (e.g. the `parallelSort` one) killed the `run-tests` process instead of being captured.
5. **Cosmetic noise**: on every startup, jolt warns about Datomic data-reader tags it cannot load (`#base64`, `#db/fn`, `#db/id`). Harmless but loud.

## Performance

The heaviest batch (`tb-am`: yin.vm semantic/space/stack tests) needed ~11 CPU-minutes for 100 tests that run in seconds on the JVM, and the 154k-assertion btree property sweep also dominated its batch. Interpreted-Scheme execution makes jolt impractical for the VM-engine and property-sweep-heavy suites even where semantics are correct. (Correction 2026-09-06: jolt runs on Chez, which compiles to native machine code, so this gap is jolt runtime overhead rather than interpretation; `jolt build` AOT was not measured. See [`jolt-vs-jank.md`](./jolt-vs-jank.md).)

## Is jolt a viable test runner for datom.world?

**As a replacement for `bb test:clj` — no.** DaoJing (MessageDigest), dao.stream (sockets/files), transit wiring, and the VM engine suites sit on unprovided JDK classes or missing dispatch, and total porting effort is jolt-runtime work, not repo work.

**As a complementary lane — plausible, with bounded investment.** The core language layers we care about most (btree, yang, yin.vm front ends, dao.space matching, dao.stream v2 semantics) mostly load and mostly pass. A realistic incremental path:

1. Report the deftype map-literal analyzer bug and the `subseq` Sorted-dispatch gap upstream.
2. Add a `:jolt` branch supply of SHA-256 (pure Clojure or `jolt.ffi`) — that alone clears 230 errors.
3. Re-evaluate after the transit and `pprint` shims land.
4. Keep it out of `bb test` until (1)–(3) land; a `jolt smoke` task covering the loadable pure namespaces is the useful interim signal.

## Is the port cost really "JVM interop behind dao.stream"?

Claim under test: most of the porting problem is JVM interop, and datom.world isolates host dependencies behind a dao.stream abstraction, so only a small interop surface needs porting. Checked against the source tree on 2026-09-06.

### Legacy `dao.stream` (v1)

Half right. I/O host dependencies are behind v1's `open!` multimethod and `defopen` registry, with `#?(:clj … :cljs … :cljd …)` branches in every transport (file, log, udp, ws, http, transit, runtime driver). Only 10 files declare Java imports and 8 of them are dao.stream or dao.runtime. But most of the jolt failure taxonomy lands outside the stream layer:

| Jolt error (count) | Source location | Behind dao.stream? |
|---|---|---|
| MessageDigest (230) | one call in `dao.jing`, already reader-conditional per host | No |
| subseq on BTSet (129) | `dao.data.btree` implements `clojure.lang.Sorted` | No, jolt dispatch bug |
| transit classes (60) | `dao.stream.transit`, `dao.stream.v2.transit` | Yes, but a Java library, not an interop line |
| Double/isFinite (29) | `dao.postgraphics.math`, `validation`, `v2.transit`, demo scenes | Mostly no |
| parallelSort (25) | `dao.data.arrays` | Already a host shim, one site |
| RandomAccessFile (25) | `dao.stream.log` only | Yes |
| DatagramSocket (13) | `dao.stream.udp` and `dao.jing.dht.node` | Half: dht.node duplicates the socket code |
| LinkedBlockingQueue (3) | `dao.runtime.driver`, `dao.runtime.v2.driver` | Yes |
| http-kit load failures | `dao.stream.http`, `world.server`, `ollama` | Yes, but a Java library |

Two abstraction leaks: `dao.jing.dht.node` reimplements sockets instead of opening a udp stream, and `dao.jing.file` reaches into the log stream record's `:raf` field to fsync.

### `dao.stream.v2`

The claim holds much better against v2, which is already built as a pure portable core plus per-host edge files:

- Core namespaces (`dao.stream.v2`, `apply`, `forward`, `ringbuffer`, `serving`, `rpc`, `rpc.ws`, `dao.runtime.v2`) contain no JDK interop; the only reader conditionals are `catch` clauses.
- Host edges are separate files selected by extension: `ws/jvm.clj`, `ws/node.cljs`, `ws/dart.cljd`, `runtime/v2/driver.{clj,cljs,cljd}`, `transit/cljd.cljd`. `dao.stream.v2.ws` knows no WebSocket library and receives `:connect!`/`:send!`/`:close!` from the adapter.
- The v2 plan removes the registry on purpose: no multimethod, no load-time side effect, dispatch is a host-owned map.
- The cljs and cljd drivers (timers/microtasks instead of a queue) prove the blocking queue is swappable.

The v2 jolt port surface is three files:

| File | JVM dependency | Jolt errors it explains |
|---|---|---|
| `src/cljc/dao/stream/v2/transit.cljc` `:clj` branch | cognitect transit-clj, ByteArray streams, `Double/isFinite` | 60 |
| `src/clj/dao/runtime/v2/driver.clj` | `LinkedBlockingQueue`, `TimeUnit` | 3 |
| `src/clj/dao/stream/v2/ws/jvm.clj` | `java.net.http` client, http-kit, ring protocols | load failure |

Because jolt reads `.clj` files and gives `:jolt` branches precedence over `:clj`, the port is a `:jolt` branch in `transit.cljc`, a `:jolt` (timer-based) branch in the driver, and a ws adapter for whatever socket library jolt provides. The transit branch has a ready donor: `src/cljd/dao/stream/v2/transit/cljd.cljd` is a pure tree-walking Transit JSON codec that touches only `dart:convert` and a few Dart types; lifted to `.cljc` it gives jolt a codec with no bytecode dependency.

### What v2 does not cover

The v2 plan explicitly defers file, UDP, log, relation and RPC transports and every consumer migration. `dao.jing`, `dao.jing.file`, `dao.jing.dht`, `dao.space` and `dao.runtime` (v1) still require legacy `dao.stream`, so the largest jolt error groups sit outside v2:

- MessageDigest (230) is one call in `dao.jing`, unrelated to any stream version.
- RandomAccessFile (25) is v1's log stream, which `dao.jing.file` also reaches into directly.
- DatagramSocket (13) is v1's udp stream, duplicated inside `dao.jing.dht.node`.
- parallelSort (25) and the BTSet `Sorted` dispatch (129) are `dao.data`, not stream.

Accurate statement: v2 has already reduced the stream-layer port to roughly three small files, but the storage and content-addressing layers have not moved onto v2 and still carry their own interop. When file and log transports land in v2, the same host-edge layout absorbs RandomAccessFile and the dht socket code. SHA-256, parallelSort and isFinite need a separate host shim in the style of `dao.data.arrays`, since they are not stream concerns and will never sit behind any dao.stream.
