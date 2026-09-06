# jolt vs jank: porting datom.world off the JVM (2026-09-06)

Follow-up to [`jolt-experiment.md`](./jolt-experiment.md) (jolt v0.8.3 test-runner
evaluation). Two questions answered here: **how** datom.world can be ported to run
on jolt, and whether **jolt or jank** is the easier port target.

## Verdict

**jolt is the easier target today, and the margin is measured, not guessed.**
244 of 252 namespaces (src + test) already *load* under jolt and the entire
failure taxonomy is ~10 JDK classes plus two jolt bugs. On jank there is zero
measured evidence for this codebase, and three structural gaps (below) mean the
same load probe would fail far earlier: no documented `.cljc`/reader-conditional
story, no classpath/deps.edn tooling, and no `java.*` shims — every host edge
becomes a C++ interop rewrite instead of a shim gap.

**jank's remaining case is C++ interop and an unproven performance ceiling —
not native delivery, which jolt also has.** `jolt build` ahead-of-time
compiles a project into a single self-contained executable (runtime,
`clojure.core`, stdlib, app, and deps.edn deps linked in — no JVM, no Chez
install, no source on disk). So jolt covers the "ship a binary" story too.
What only jank offers is C++ interop, real JVM-like threads, and a potential
performance ceiling near C++ — and that last claim is unbenchmarked (perf work
is slated for Q2 2026). jank entered alpha in January 2026, self-describes as
having "huge areas of functionality which haven't been implemented," and
porting production storage (DaoJing), a VM engine (yin.vm), and a DHT onto an
unbenchmarked alpha runtime is not a schedule anyone should commit to.

**The port work that matters is shared by both.** The v2 stream redesign's
strict host-edge layout (pure core, host-owned dispatch, adapter maps of
event→data transforms) is what makes *any* non-JVM port tractable. Every step
of the jolt plan below also de-risks a future jank port. See
[`design/datom.world.md`](./design/datom.world.md) §Host Boundaries for the
architectural rule that keeps this true: a host boundary is a stream boundary,
never a function call, and no host type crosses it.

## Part 1 — Porting datom.world to jolt

The experiment established the shape: the port is **a handful of repo-side
shims plus a small number of host-edge files**, blocked mainly on jolt-side
gaps. Phased so each milestone has a measurable payoff in the failure
taxonomy of `jolt-experiment.md`.

### M0 — Keep the smoke lane honest (now)

A `jolt smoke` task over the loadable pure namespaces (btree, yang, dao.space
matching, yin.vm front ends, dao.stream v2 core) as a conformance oracle
alongside `bb test:clj`. Zero new porting; keeps regressions visible and keeps
proving the "JVM-free core" claim of the v2 redesign. Because `jolt build`
AOT-links runtime, stdlib, app, and deps.edn deps into one self-contained
executable, a green smoke lane can ship as a single-file native test oracle —
no JVM, no Chez install, no source on disk. Includes the already-applied
workaround at `src/cljc/dao/data/btree.cljc:1236` (`array-map` for map
literals in `deftype` bodies, behavior-preserving on every host).

### M1 — Host shims outside the stream layer (~285 errors clear)

Three sites, none of which any dao.stream version will ever own:

| Site | Jolt fix | Errors cleared |
|---|---|---|
| `src/cljc/dao/jing.cljc:189` (SHA-256, already reader-conditional per host) | `:jolt` branch with pure-Clojure SHA-256 or `jolt.ffi` | 230 |
| `src/cljc/dao/data/arrays.cljc:145` (`Arrays/parallelSort`) | `:jolt` branch calling plain sort (the shim already documents JVM-specific behavior) | 25 |
| `Double/isFinite` sites (`dao.postgraphics.math`, `validation`, v2 transit, demos) | `:jolt` branch using jolt's numeric predicates | ~29 |

SHA-256 alone is the single biggest win. One caveat for content addressing:
jolt strings are codepoint-indexed Chez strings, not UTF-16 — whatever
string→bytes conversion feeds the digest must be pinned to UTF-8 explicitly on
every host, or DaoJing addresses computed on jolt will not match JVM-computed
addresses. The "missing index segment" durability flag in the experiment may
be exactly this class of divergence; investigate before trusting cross-host
content addressing.

### M2 — Transit without bytecode (60 errors + yin.repl load chain clear)

Lift the pure tree-walking Transit JSON codec at
`src/cljd/dao/stream/v2/transit/cljd.cljd` (touches only `dart:convert`) into
a portable `.cljc` and give `dao.stream.v2.transit` a `:jolt` branch that uses
it. This removes the cognitect transit-clj dependency — a Java library, so it
can never "just work" on jolt — and the same lift serves the v1
`dao.stream.transit` boundary. Requires a `:jolt` JSON parser (jolt stdlib or
a small pure parser).

### M3 — Runtime driver + WebSocket edge (3 errors + one load failure)

- `src/clj/dao/runtime/v2/driver.clj` (`LinkedBlockingQueue`, `TimeUnit`):
  a `:jolt` timer/poll-based driver, patterned on the existing cljs/cljd
  drivers that already replace the blocking queue with timers/microtasks —
  proof the queue is swappable by design.
- `src/clj/dao/stream/v2/ws/jvm.clj` (`java.net.http`, http-kit, ring):
  a jolt ws adapter supplying the `:connect!`/`:send!`/`:close!` map that
  `dao.stream.v2.ws` requires. This is the **one genuinely open item** — it
  needs a socket library on jolt (`jolt.ffi` to C, or a Gambit-backed
  library). Until it exists, ws transports simply report the design-doc's
  qualified "unsupported" outcome, which is a correct state, not a failure.

### M4 — File / log / UDP transports, then the storage layer (unlock)

v2 deliberately deferred file, UDP, log, and RPC transports. When they land
with the same host-edge layout, they absorb `RandomAccessFile` (25 errors,
`dao.stream.log` + the `dao.jing.file` `:raf` leak), `DatagramSocket` (13,
`dao.stream.udp` + the duplicated socket code in `dao.jing.dht.node`), and the
http-kit load failures (`dao.stream.http`, `world.server`, `ollama`). Two
abstraction leaks to fix on the way: `dao.jing.dht.node` should open a udp
stream instead of owning sockets, and `dao.jing.file` should stop reaching
into the log stream record.

### Upstream jolt items (not repo work)

The `deftype` map-literal analyzer bug, `subseq`/`rsubseq` not dispatching on
`clojure.lang.Sorted` (129 errors, blocks BTSet), escaped exceptions aborting
the batch, and `:jolt/provides` shims for `MessageDigest`-class needs if we
prefer shims over repo branches. Report; don't block on them.

### Known ceiling: performance

The experiment measured VM-engine suites ~50× slower and the btree property
sweep dominating its batch — but "interpreted Scheme" is the wrong diagnosis.
Chez compiles jolt-emitted code to native machine code (incrementally at load,
or ahead-of-time via `jolt build`), so the gap is **jolt runtime overhead**
(the Clojure-on-Scheme semantics layer), not interpretation; whether `jolt
build` AOT narrows it is unmeasured. A jolt lane is a **portability and
semantics oracle plus a native-delivery lane**, not yet a production runtime
for yin.vm at JVM speeds. Raw execution speed is the one argument left for
jank (Part 2) — and it is currently an unbenchmarked argument.

## Part 2 — jolt vs jank

### Facts side by side

| Dimension | jolt (v0.8.3, measured) | jank (alpha, Jan 2026, documented) |
|---|---|---|
| Host | Chez Scheme (default), Gambit→JS | LLVM JIT/AOT, C++ runtime |
| Runs datom.world? | 244/252 namespaces load; 112/115 test namespaces; core layers pass | Unknown — no probe run; see blockers below |
| JVM interop | `java.*` shims ("convincing but shallow"); gaps need `:jolt/provides` or repo branches | None; interop is C++ (`:include`, `cpp/cast`, typed exceptions, template DSL) |
| `.cljc` / reader conditionals | Reads `.clj`/`.cljc`; `:jolt` branch takes precedence over `:clj` | Not documented in the book/README; was "coming" in a Feb 2024 update — must be probed |
| Dependencies | Parsed our `deps.edn`, resolved the Maven graph itself (jars inert) | Own Cargo-inspired native build; deps.edn/Clojure CLI/Leiningen integration is roadmap, not shipped |
| Delivery | `jolt build`: single self-contained executable — runtime, stdlib, app, deps.edn deps linked; no JVM, no Chez, no source | AOT native binaries via LLVM; artifact distribution improving after LLVM 23 |
| Threading | No real JVM thread semantics; daemon flags ignored | Real native threads; `future` added with a synchronization audit; TSan tracked |
| Performance | ~50× slower on VM suites — Chez compiles native machine code, so this is jolt runtime overhead, not interpretation | Unbenchmarked vs Clojure; benchmarking scheduled Q2 2026; 0.3s startup via direct LLVM IR |
| REPL | clojure.repl shims | nREPL server written in jank, baked into the binary |
| Maturity | v0.8.x, conformance corpus vs JVM oracle, divergence registry | Alpha; "huge areas of functionality" unimplemented (its own book) |

### Why jolt is easier, structurally

1. **The gap class is smaller.** On jolt, every failure in the measured
   taxonomy is "JDK class X is not provided" or a dispatch bug — patchable
   with reader-conditional branches and shims. On jank there are no shims at
   all: `java.net.DatagramSocket`, `MessageDigest`, `RandomAccessFile` have no
   jank equivalents, so each host edge is a C++/POSIX rewrite (or a C library
   binding) — new skill surface, GC-across-ffi failure modes, typed-exception
   plumbing.
2. **The codebase shape matches.** ~90% of src is `.cljc` with
   `:clj`/`:cljs`/`:cljd` branches. jolt demonstrably processes these and
   gives `:jolt` branches precedence — the port is *branches in existing
   files*. If jank cannot process `.cljc` reader conditionals, the port starts
   with a tree-wide restructuring.
3. **The toolchain matches.** jolt resolved our Maven graph with no Java
   installed. On jank, until deps.edn/classpath lands, there is no way to even
   stage the dependency tree.

### Where jank wins, and when to switch

With `jolt build` covering self-contained executables, jank's unique offer
narrows to: **C++ interop** (fast codecs, vectorized math in postgraphics,
native storage engines — the real differentiator), **real threads**, and a
**potential performance ceiling near C++**. That last one is the argument that
would justify a jank port for yin.vm-scale workloads, and it is unproven until
jank's own Q2 2026 benchmarking against Clojure lands. jank's trajectory is
credible — full-year 2026 sponsorship, nREPL in-tree, deferred JIT halving AOT
times, GC bugs systematically retired — and its module system was explicitly
designed for JVM-classpath compatibility. The switch condition is concrete:
**when jank documents `.cljc` reader-conditional handling and a dependency
story, run the same 252-namespace load probe.** If jank loads what jolt loads
*and* benchmarks at JVM-or-better speeds, it becomes the better *runtime*
target; jolt remains the better *conformance* target either way.

### A jank port would look like

Same phased shape, different mechanics: M1 shims become C++ interop (OpenSSL
or a C SHA-256 via `:include`); M2's pure transit codec lift works verbatim
(it is the host-neutral piece by construction); M3's driver is native threads
(jank's strength — the blocking queue stays); M4's transports bind POSIX
sockets/files directly. The v2 host-edge files are the same three files,
rewritten as C++ adapters. Everything the design doc says about adapters (map
of event→data transforms, no host types crossing) is exactly the discipline
that makes a C++ boundary safe — but note the yin.repl system is datom.world's
own protocol over streams; jank's built-in nREPL does not substitute for
porting it.

## Recommendation

1. Do the jolt plan M0–M2 now (bounded, mostly repo-side, keeps the v2
   "JVM-free core" claim continuously verified).
2. Treat jolt as the portability oracle and the native-delivery lane
   (`jolt build`); not yet a production runtime for yin.vm at JVM speeds.
3. Track jank quarterly against two tripwires: `.cljc`/reader-conditional
   support and dependency tooling. Run the load probe the day both land.
4. Keep host edges strict (no abstraction leaks like the dht socket
   duplication) — it is the single practice that prices both ports down.

## Sources (jank, 2026-09-06)

- [jank is off to a great start in 2026](https://jank-lang.org/blog/2026-03-06-great-start/) — alpha status, LLVM 22/23, deferred JIT, nREPL, C++ interop, Q2 2026 perf plan
- [jank book](https://book.jank-lang.org/) — alpha scope, C++ interop focus
- [jank GitHub](https://github.com/jank-lang/jank) — `clojure-cli/`, `lein-jank/` dirs (tooling roadmap)
- [Load all the modules! (Dec 2023)](https://jank-lang.org/blog/2023-12-17-module-loading/) — module system designed for JVM classpath compatibility
- [Clojurists Together Q3 2025 funding](https://www.clojuriststogether.org/news/q3-2025-funding-announcement/) — deps.edn/Leiningen tooling as a stated goal
- [Moving to LLVM IR (Oct 2024)](https://jank-lang.org/blog/2024-10-14-llvm-ir/) — 12s → 0.3s startup
- jolt facts: [`jolt-experiment.md`](./jolt-experiment.md) and jolt
  [llms.txt](https://raw.githubusercontent.com/jolt-lang/jolt/refs/heads/main/llms.txt)
