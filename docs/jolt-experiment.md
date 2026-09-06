# Jolt Experiment (2026-09-06)

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

The heaviest batch (`tb-am`: yin.vm semantic/space/stack tests) needed ~11 CPU-minutes for 100 tests that run in seconds on the JVM, and the 154k-assertion btree property sweep also dominated its batch. Interpreted-Scheme execution makes jolt impractical for the VM-engine and property-sweep-heavy suites even where semantics are correct.

## Is jolt a viable test runner for datom.world?

**As a replacement for `bb test:clj` — no.** DaoJing (MessageDigest), dao.stream (sockets/files), transit wiring, and the VM engine suites sit on unprovided JDK classes or missing dispatch, and total porting effort is jolt-runtime work, not repo work.

**As a complementary lane — plausible, with bounded investment.** The core language layers we care about most (btree, yang, yin.vm front ends, dao.space matching, dao.stream v2 semantics) mostly load and mostly pass. A realistic incremental path:

1. Report the deftype map-literal analyzer bug and the `subseq` Sorted-dispatch gap upstream.
2. Add a `:jolt` branch supply of SHA-256 (pure Clojure or `jolt.ffi`) — that alone clears 230 errors.
3. Re-evaluate after the transit and `pprint` shims land.
4. Keep it out of `bb test` until (1)–(3) land; a `jolt smoke` task covering the loadable pure namespaces is the useful interim signal.
