# yin.vm/yin.repl on jolt: host-lane implementation plan

Status: design proposal, 2026-10-19; not an implementation or acceptance report.
Author: gpt-6-astra, Lead System Architect. Only this findings file is written.
Revision: r2, reconciling claude-fable-5-1 review `collab/1792422615171-architect-jolt-lane-plan-review.claude-fable-5-1.findings.md`; original plan retained unchanged. Review evidence rechecked by reading; no runtime tests run. Nothing is commissioned until the owner answers the questions in §10.

## 1. Decision, evidence and scope

Build a fourth **shared-source host lane**, not another VM implementation. Fold the spike's host adaptations into tracked source; keep the existing semantic/register/stack machinery, frontend lowering, stream contracts and conformance oracle. Use the owner-directed C reference BLAKE3 library through jolt/Chez FFI. Ship the standard frontend catalog using an explicitly composed ANTLR parsing worker (JVM, Node or Dart AOT, selected at J5); Python lowering, primitives, evaluation and session state remain on jolt. A Clojure-only catalog is a bootstrap milestone, not parity.

Proposed committed home: **`docs/design/yin.vm.jolt.md`**, titled “jolt host implementation plan for yin.vm and yin.repl.” The subject is the host lane spanning codecs, VM, shell and runtime adapters, not a source-to-jolt compiler or new `dao.stream` contract. `yin.repl.jolt.md` would obscure VM-wide integer/hash work; `yang.jolt.md` would name the wrong layer. Distillation is a separate task; this task creates no committed spec.

Evidence labels used below:

- **S — reported execution:** `collab/1792412960461-engineer-jolt-yin-repl-spike.claude-opus-5-5.findings.md`, read first and in full. The spike loaded 91 namespaces with four overlays; semantic evaluation and six content hashes passed. It did **not** prove standard catalog, CLI, durable store, signing, networking, all VM modes, full tests or performance.
- **V — newly verified by reading this tree:** citations below identify actual files/symbols, including the local jolt checkout. No new runtime experiments or benchmarks were run for this plan. Local jolt citations mean paths beneath `build/jolt-spike/jolt/` at the spike checkout; they are evidence, not proposed production paths.
- **P — proposed implementation:** file/task names, deployment choices and acceptance gates below are not existing commands unless explicitly identified as such. Exact native library revisions and platform packages must be recorded by J0; no unverified version pin is invented here.

Authorities: `docs/design/datom.world.md`, `datom.world.abi.md`, `dao.stream.md`, `yin.vm.engine.md` §E.5, `yin.vm.debruijn.register.md` §10.4, `yang.antlr.md`, `test.subsystems.md`, and the spike. One abstraction boundary, many implementations: local FFI is host implementation machinery; effects, external parsing work and peer communication remain composed through dao.stream, with canonical data, identity correlation and no privileged reader. No jolt service ABI or replacement fixture kit.

The older `docs/jolt-experiment.md:11–21,60–75` reports failures and timing for **v0.8.3**, including BTSet/subseq and interop gaps. Those are regression candidates, not measurements of `96d7b866`; the current spike has no benchmark. Do not use either report's effort estimate as a delivery commitment.

## 2. Precisely what “on par” means

There are two named milestones, with no ambiguous green badge:

1. **Local REPL lane available — requires a configured parser worker runtime (through J5):** tracked-source API, CLI, index/store and standard frontend catalog operate; every supported test runs green. Networking remains explicitly unavailable until J6/J7. This is not unqualified parity with the network-capable lanes.
2. **Host-lane parity (J8; runtime dependencies and sidecars disclosed in the label):** the entry paths and shared host-neutral test surface below pass, plus WebSocket/peer and UDP/DHT capability gates. Owner Q6 determines whether this label may include JVM sidecars; if permitted, say “requires JVM sidecar(s)” when applicable. Only inherently foreign-host build/UI/JDK tests remain excluded, with a reviewed applicability manifest. If networking is deferred by the owner, publish the narrower milestone and its gaps; do not call it full parity.

Required entry paths (V):

| Entry | Completion behavior |
|---|---|
| `yin.repl/create-state` (`src/cljc/yin/repl.cljc:814`) and `eval-input` (`:2095`) | Same options/results, output events, pending behavior and errors. Direct core creation deliberately defaults to an empty catalog (`:900–958`); preserve this API. |
| `yin.repl.frontends/standard` (`frontends.cljc:54`) and `create-state` (`:78`) | Standard Clojure, PHP and ANTLR Python registrations, including Python primitives, render/probe/lower-session. No hand-built catalog required from the caller. |
| `yin.repl.main/-main` (`main.cljc:1306`, JVM arm) | New jolt arm owns cadence/stdin/shutdown without JVM Thread/Runtime dependencies. Proposed `jolt -M:jolt:jolt-yin-repl`; same local options, VM selection and clean EOF/quit. Network flags refuse explicitly until their slice. |
| `yin.demo/-main` (`src/clj/yin/demo.clj:275`) | Existing two-VM continuation demo returns 5050 (`run-demo:176`). Keep its source root available or move only host-neutral content when authorized; do not silently substitute the spike's arithmetic script. |
| Browser/Flutter demos | Rendering remains on their existing hosts (`src/cljs/datomworld/demo/yin_repl.cljs`, CLJD demo tree). Shared REPL semantics join jolt tests; native Chez does not acquire DOM/Flutter. A graphical jolt shell is not required. |

Run every already-exposed `--vm` choice through the common shell tests, particularly semantic and register modes; the spike verified only semantic. Also cover `(host)`, `(lang ...)`, reset, definitions across submissions, output ordering, queries and successful code-index publication. `(map inc ...)` is not a jolt regression until compared against the same JVM primitive registry.

### Build declaration and task integration

V: root `deps.edn:1–23` supplies dependencies and paths; `:test` at `:30` uses Cognitect's runner, `:clj-yin-repl` at `:66` runs `yin.repl.main`. `bb.edn:34–90` defines fast/all/slow lanes, with changed/subsystem selection at `:96–170`. These commands are implementations for other runtimes, not scripts to run unchanged under jolt.

P: add a `:jolt` **host configuration alias**, not a dependency coordinate. Use root aliases with supported `:replace-paths` to select tracked `src/jolt` before `src/cljc` and audited `src/clj` content (including the demo), excluding generated Java parser classes and irrelevant JS/Dart roots. A `:replace-deps` set contains the actual portable source dependencies required by this lane, versioned from the root dependencies; do not fetch Datomic/http-kit/boring JVM classes merely because root `:clj` uses them. Verify the selected dependency closure rather than assume it is empty. Add `:jolt-test` with `test` and runner paths and `:jolt-yin-repl` with `:main-opts`. These alias mechanisms are present in local `jolt-core/jolt/deps.clj:8–9,1737–1792` (V).

`src/jolt` is a tracked host source root, not an overlay copying core namespaces. Verify resolution with `-Spath` and record which file supplies each host override. Portable `.cljc` branches must remain valid; the supported jolt require uses this ordered alias, because narrow `.jolt` implementations in `src/jolt` must take precedence over JVM-backed files in the ordinary root paths. Standard supported launch is the explicit jolt alias so dependencies and native libraries are reproducible.

Proposed tasks:

- `bb bootstrap:jolt`: materialize the recorded runtime SHA/submodules and declared native dependencies. Explicit update mode advances to a reviewed main SHA; ordinary runs never pull silently.
- `bb build:jolt-native`: deterministic local C library builds, feature/loader smoke checks; no downloads during namespace loading.
- `bb test:jolt`, `test:all:jolt`, `test:slow:jolt`: fast, all, slow selections with the same `^:slow` and `dao.test-slow/guard` semantics. Initially use a small tracked `clojure.test` runner with explicit namespace discovery and nonzero exit on load/test/error failures; prove Cognitect compatibility before choosing to reuse it. The runner is a launcher, not another conformance oracle.
- `test:changed:jolt`, `test:sub:jolt`: extend `src/dev/affected.clj` and the existing subsystem/require-closure mechanism, not a competing dependency graph. Discover `.jolt` sources, retain source-to-test edges, and treat toolchain/native/alias changes as wide changes. Keep `src/dev/subsystems.edn` as the taxonomy.
- At J8 include jolt in `bb test`, `test:all`, `test:slow`, changed/subsystem multi-lane dispatch and list output. Before then, keep an explicit experimental task; never claim the default aggregate covers a lane it omits.

`gen:antlr` remains the JVM build prerequisite for generated parsers; this build-time dependency does not require a JVM in the deployed worker process tree. The selected JVM/Node/Dart AOT worker has its own build prerequisites. Node shadow builds and Dart compilation are prerequisites when selecting those worker targets or genuine cross-host peer tests, not for pure jolt tests. JVM reflection/compiler tests, JS DOM tests, Dart compiler tests and JDK-adapter unit tests are not run as Chez code; their portable behavioral equivalents and cross-process peer legs are required. A `.cljc` test is not automatically host-neutral: inventory its selected branches and actual assertions. Every excluded namespace/test has reason, capability, replacement gate and owner; zero unexplained skips. Missing dependencies must fail prerequisites: local jolt README:110–115,160–164 says fetch failures and unknown aliases may otherwise be skipped/warned.

## 3. Reader discipline and tracked-source inventory

V: `host/chez/reader.ss:1102` defines `("jolt" "clj" "default")`; `host/chez/loader.ss:332–338` resolves `.jolt`, `.clj`, `.cljc`. First matching reader branch wins (S, corroborated reader code). Preserve `:cljd` first where required by ClojureDart host compilation, then insert **`:jolt` before `:clj` and before `:default`**. Audit namespace imports, type hints, catch classes and spliced requires, not just function bodies. Do not add `:jolt/provides` pretending a partial shim implements the JDK.

Use small `.cljc` branches for representation differences. Use `.jolt` only for a complete, narrow host implementation (CBOR wire writer, native-library facade, WebSocket/OS adapter), with the same public surface and contract tests. Never copy `yin.repl`, `yin.vm.engine` or full `dao.jing.cbor` into host shadows. V: `host/chez/loader.ss:389–395,467–481` and `jolt-core/jolt/deps.clj:1744–1746,1776–1782` settle source lookup: roots are searched outermost, and `.jolt`, `.clj`, `.cljc` are tried within each root. Put `src/jolt` before `src/cljc` and `src/clj` in the `:jolt` alias, verify the resolved order with `-Spath`, and audit aliases whose `:extra-paths` precede `:replace-paths`. A later-root `.jolt` does not beat an earlier-root `.cljc`. Keep the J0 precedence probe for `:jolt/provides` / `ldr-ns-replaced-rel?` and built binaries, whose embedded sources otherwise probe first.

| Fold/audit site (V current coordinates) | Spike evidence and required work |
|---|---|
| `src/cljc/dao/jing/cbor.cljc:112–250` | S overlay fixes `big`, `b+`, `b-`, `b*`, `bquot`, `bcmp`, `bgcd`; audit `bsign`, `bpow`, `b->int:223`, `magnitude-bytes:235` too. Use Chez exact arithmetic, no BigInteger constructor/valueOf/toByteArray. Spike's “8 helpers” count is descriptive, not a closed branch inventory. |
| `cbor.cljc:462–658,993–1012` | Newly read: predicates, float carriers/bits, decimal/ratio accessors and exact numeric comparison still contain JVM-specific representation. Gate the entire scalar surface, not only six sampled values. |
| `src/cljc/yin/vm/integer/host.cljc:27–28,41–108,115–298` | S reports ~25 insertions. V shows import, carrier predicates, promotion/demotion, truncation, arithmetic, compare, three big bit operations, shifts, magnitude length and radix output. Overlay bit operations are knowingly incorrect; do not copy them. |
| `src/cljc/dao/jing.cljc:59–85,114–136` | Actual current SHA string/bytes sites and BLAKE3 bytes/string sites. Branch to one jolt crypto facade; preserve UTF-8, byte-array semantics and lowercase digest text. No SHA stub. |
| `src/cljc/dao/jing/cbor/boring.cljc:32–40,46–132` | Replace Java boring dependency only for jolt with a narrow `.jolt` wire-node implementation. Preserve null/bool/text/bytes/integer/array/map/tag/frame/encode surface and canonical options, not a new Jing codec. |
| `src/cljc/dao/data/arrays.cljc:92,138–148` | `asort` calls parallelSort at :145; replace for jolt. Audit arraycopy, overlapping copies and clone behavior as well. |
| `src/cljc/yin/repl.cljc:275–279` | Put jolt banner branch before JVM, using recorded jolt/Chez identity; never print `JVM nil`. Audit rendering methods at :364–395. |
| `src/cljc/yin/repl/frontends.cljc:11–17,54–85` | Remove unconditional host parser dependency on jolt; preserve complete catalog and primitive injection at completion. |
| `src/cljc/yin/repl/main.cljc:11–30,199–239,873–880,951–959,1212–1364` | Additional to the spike: eager storage/network/sign dependencies, file helpers, exits and JVM cadence/stdin. Port host mechanisms without duplicating `step-all`/driver semantics. |
| `src/cljc/yin/repl/host.cljc:15–17,20–37` | Select a jolt WebSocket implementation without requiring `host.jvm`/http-kit. Early unsupported capability must be explicit and must not prevent local startup. |

J0 produces an exhaustive machine-readable inventory of selected interop branches and their tests, including every reached `src/clj` namespace and its planned `:jolt` guard. V: `src/cljc/yin/repl/host.cljc:15–17` selects `src/clj/yin/repl/host/jvm.clj`, whose require at :3 reaches `src/clj/dao/stream/ws/jvm.clj`; `src/cljc/dao/space/dht.cljc:53–57` selects `src/clj/dao/stream/datagram/jvm.clj`. Guard those selecting branches with `:jolt` before `:clj`, and audit adapter call sites and test requires. The portable `dao.stream.ws` and `dao.stream.datagram` namespaces do not themselves require these JVM implementations; their remaining host operations still need audit. Merely listing `src/clj` on the classpath must not cause JVM implementations to load.

J0 also adds `.jolt` to `.cljstyle`’s `:files :extensions` (currently absent) and verifies the formatter preserves branch order. Run the repository clj-kondo lane over representative `:jolt` arms and `.jolt` files, configuring explicit feature analysis as needed; `.clj-kondo/config.edn` currently has no `:jolt` setting. Unknown-feature skipping is not evidence those arms were linted. These verified sites are a starting set, not a claim that the unexercised 91-namespace closure is already portable. Remove all four spike overlay paths and its helper source from production/test classpaths.

## 4. Integer, encoding, ordering and presentation correctness

### Exact integer bit operations

V: `integer/host.cljc:239–298` requires infinite two's-complement AND/OR/XOR, exact left multiplication, floor right division, magnitude bit length and exact radix output. Do not use jolt's long-oriented bit functions on bignums, even after a cast appears to work.

P: initial algorithmic implementation uses exact arithmetic only. For AND/OR/XOR choose `w = 1 + max(bit-length(abs(a)), bit-length(abs(b)))`; form nonnegative residues modulo `2^w`; consume base-2 digits with `quot`/`rem`, apply the one-bit truth table, reconstruct with exact powers; convert a result with bit w−1 set back by subtracting `2^w`. This models sign extension, including negative operands and zero. A later base-2^k limb implementation may use small, proven-safe bit operations, but only after the same tests. Magnitude length uses repeated division or exact radix conversion, never a floating logarithm.

Left shift is `a * 2^k` using exponentiation by squaring and promoting arithmetic. Right shift is floor division: start with truncating `q=quot(a,2^k)` and decrement once if a<0 and rem is nonzero. Never mask shift counts modulo 32/64. Preserve the caller's count validation and host-limit behavior; huge counts must not silently truncate. Carrier promotion/demotion follows the host integer namespace's existing public laws, with explicit negative-zero/float distinctions.

Gate against JVM BigInteger and the shared integer corpus for ±(2^n−1), ±2^n, ±(2^n+1), n=31,32,53,63,64,100,127,1024; mixed signs; counts 0,1,63,64,100 and beyond operand length. Seeded randomized differential tests, De Morgan identities, x XOR x=0, x AND −1=x, Python ~x = −(x+1), x XOR −1 = ~x, negative floor shifts, ratios and division signs. V: Python `py/invert` (`src/cljc/yang/python/antlr/prelude.cljc:1507–1511`) calls `integer/bit-not`; `src/cljc/yin/vm/integer.cljc:350` implements it with two `host/sub` calls (−x−1), not a host bit-not helper. Exercise that public path as well as the identities. Include exact REPL/Python arithmetic and register scalar image hashes, not just helper unit tests. Submit minimal upstream repros separately; keep the local branch until a pinned upstream fix passes every vector. Runtime upgrades do not authorize deleting the guard tests.

### Jing CBOR: an important correction to the brief

V: `cbor.cljc:19–20,993–1012,1448–1452` represents floating content as **tag 27 `dao.jing/float64` with eight big-endian payload bytes**, canonical NaN `7ff8000000000000`. `test/dao/jing/cbor_conformance_test.cljc:127,415` explicitly rejects native CBOR floats. Thus “finish f16/f64 framing” must NOT mean shortest-width CBOR f16/f32/f64 encoding. Preserve integral floats as floats, ±0, infinities and exact float64 bits; native CBOR float encodings (additional-info 25/26/27) remain rejection fixtures. The recorded seventh-value failure is a wire-writer node-contract mismatch, not an unfinished float encoding: `build/jolt-spike/parity.jolt.txt` fails in overlay `enc*` at `overlay/dao/jing/cbor/boring.cljc:50`. The `:clj` ByteBuffer path in `float-wire` produced a raw `byte[]`; the overlay treated that byte string as a sequence of nodes, then tried to sequence a numeric byte (`java.lang.Long`).

Implement/verify a host byte extraction path (ByteBuffer shims were only partially exercised; otherwise a narrow Chez bytevector/IEEE representation helper), with explicit endian conversion and NaN normalization. Avoid routing raw 64-bit bit patterns through broken bigint bit functions. The `.jolt` wire writer must accept the same **host-value node vocabulary** as `src/cljc/dao/jing/cbor/boring.cljc:58–132`: nil, booleans, strings, byte arrays, integers in [-2^64, 2^64), vectors, maps, a `TaggedValue` equivalent for uninterpreted tags, and `tagged-literal` frames. These constructors return host values, not the overlay’s tagged tuples. A byte array at **any node position**, including float64-frame payloads and bignum magnitude payloads, is a byte string. The writer emits the existing node tree: exact integer heads, byte/text lengths in UTF-8 bytes, unsigned bytewise map-key ordering, arrays, uninterpreted tags and tag-27 frames. It must handle big-endian 64-bit unsigned heads without narrowing to signed longs. Keep decimal/rational reduction, metadata frames, Unicode/surrogate refusal, duplicate/equality-collapse rules and limits in the existing `cbor` implementation. Decode remains the shared parser/profile checker above its host seams. J1 explicitly verifies or supplies jolt branches for `cbor.cljc:537` (`float64-from-bits`: `Long/parseUnsignedLong` and `Double/longBitsToDouble`) and `float-fields:649–652` (`Double/doubleToRawLongBits` and 64-bit bit operations); successful encode does not establish decode or numeric comparison fidelity. No EDN/pr-str substitute and no third codec.

Reuse `test/resources/dao/jing/cbor-v1.json` with README/errata, `cbor-test`, `cbor-conformance-test`, `cbor-fixtures-test`, `cbor-content-equality-test`, and stream CBOR tests. Compare encoded bytes and decode/re-encode identity against JVM boring output and frozen vectors, not merely digest equality. Exercise both hash algorithms and tampered/noncanonical input refusals. Existing expected bytes never get regenerated to make jolt pass.

### Arrays, B-tree and display

P: implement `asort` as stable mergesort over a temporary vector/array and copy back into **the same input array**, returning it. Preserve cmp's negative/zero/positive result and equal-key original order; no comparator replacement. Determinism depends on comparator consistency and equal-key order, not parallel execution. Test duplicates, non-identical cmp-equal values, source array identity, sorted runs, randomized inputs and index datom ordering against JVM. Sorting unordered inputs is not a general cure for host map/set iteration differences.

Audit `btree`'s Sorted/Seqable/IFn and mutable-field behavior, soft/weak reference strategy and transient ownership. V: `src/cljc/dao/data/btree.cljc:44–50` defaults JVM to soft refs; choose an explicit jolt strong-cache default, as existing non-JVM hosts do, rather than fake SoftReference behavior. Preserve the declared explicit ref-type outcomes and ownership/restore semantics. Re-run the older experiment's subseq/rsubseq repro; treat it as unresolved until tested at the selected SHA.

Set printing order observed by S must not affect canonical bytes, addresses, id minting, persisted manifests or frontend profile identities. Compare semantic sets canonically; retain assertions for deliberately specified REPL presentation. Do not globally sort user sets or replace guest equality to hide a runtime divergence. Extend the shared deterministic traversal tests from engine §E.5; exact ids/seals and resource order remain observable.

## 5. Crypto: C BLAKE3 and shared libcrypto

P: vendor the upstream BLAKE3 **C reference sources** with license and exact upstream revision/checksum in a tracked toolchain manifest (proposed `jolt-toolchain.edn`), and build a private shared library into `build/jolt/native/<platform>/` during `build:jolt-native`. Start with the portable C implementation; SIMD is a separately tested optimization. Record compiler/version/flags and library digest; provide macOS arm64 and Linux x86_64 qualification first, with other targets explicitly unqualified. Mise provisions Chez, not magically libblake3. Do not search arbitrary system libraries during require or silently fall back in the production parity gate.

Use `jolt.ffi/defcfn` and declared `:jolt/native` loader configuration (V: local `stdlib/jolt/ffi.clj:91–100,1664`; `jolt-core/jolt/deps.clj:998–1146`). Prefer a tiny reviewed C wrapper around init/update/finalize to hide the hasher struct's size/alignment: conceptually `(input-pointer, size_t length, output-pointer[32]) -> status`. It implements no new hash algorithm. Bind pointer/native-size/status types explicitly, use a confined FFI arena, copy bytes in/out, accept zero-length and embedded-NUL input, and free on every path. Never use C-string arguments for binary data or expose C pointers as guest values. Direct Chez `foreign-procedure` is only an encapsulated fallback if the documented jolt macro cannot express the binding; it is not a parallel hashing path.

The spike's pure-Clojure BLAKE3 becomes a test/interim implementation after review, not an independently authoritative oracle. Frozen official vectors and the existing JVM implementation are primary checks. V: `test/dao/jing/hash_registry_contract_test.cljc` names `test/resources/dao/jing/blake3-vectors.edn` and `digest-table.edn`; CBOR conformance already checks resource digests. Add empty, abc, all-byte values, UTF-8, 63/64/65-byte, 1023/1024/1025-byte and multiple-chunk boundaries; compare C, pure interim and JVM, then CLJS/CLJD corpus hashes. Native loading failures fail the production task.

Recommend **libcrypto EVP SHA-256** rather than a new pure cryptographic implementation. Jolt already requires OpenSSL for Maven (V README:110), but that is not proof the needed provider/version/symbols are available to this project. Pin/test the distribution, declare its loader path and record version; use public EVP APIs via a small wrapper with explicit errors/lifetimes. Verify known vectors, binary/string variants, large inputs and full `:sha256` content-address registry behavior. Do not alias SHA to BLAKE3 or return a stub. The same vetted provider can supply Ed25519 and OS-backed randomness, reducing independent crypto dependencies.

## 6. Tiered host remainder and capability contracts

| Area | Decision / acceptance |
|---|---|
| Arrays/B-tree/index (J1–J3) | Portable algorithmic branches and narrow host type/ref support, not FFI replacement of the data structure. Run arrays/B-tree persistent, transient, async and durability fixtures, dao.space query/index numeric tests, and REPL index/query tests. No “not in published code index” warning on successful normal evaluation. |
| `dao.jing.file` (J4) | Keep record framing/recovery shared; implement random-access reads/writes/truncate/sync/close using Chez file APIs plus narrow POSIX FFI where needed. V `file.cljc:159–259` shows RandomAccessFile operations; the spike did not exercise them. Test partial writes, truncation, replay/corruption and cross-host reopen. |
| `dao.space.store.fs` (J4) | OS adapter, not slurp/spit emulation. Preserve exclusive locks, same-process duplicate-open tracking, complete temp writes, fsync, atomic same-directory HEAD rename and directory-sync capability/refusal. V namespace docstring specifies these. Use checked POSIX wrappers if Chez cannot supply a required operation. Kill/restart, competing process, stale-temp and failure-injection fixtures are completion gates. First support declared POSIX targets; Windows is a separate qualified platform. |
| `yin.vm.linker.sign` (J4) | libcrypto EVP Ed25519 with raw 32-byte seeds/public keys, detached signature bytes and exact existing message bytes (`sign.cljc:69,90,127–197`). No hand-written Ed25519, seeded non-crypto PRNG or shelling out with secrets in argv. Run existing RFC-8032 and envelope vectors (`test/yin/vm/linker/sign_test.cljc:29–35`, `sign_vectors.edn`), bidirectional JVM/Node/Dart interoperability, tampered message/key/signature and malformed lengths. Preserve envelope/trace domain separation, constant-time provider verification, fail-closed behavior and key-file validation; OS randomness failure is an error. Key bytes stay host-local, with explicit cleanup and restricted file creation. |
| `yin.repl.main`, wait/wake/stdin (J3–J4) | Keep one state owner. Host reader/signal mechanisms deposit data and nudge cadence; never evaluate from the callback/reader. Port exits, monotonic scheduling/wakes and graceful shutdown; do not run the JVM Thread/Runtime branch by accident. Include EOF while pending and outstanding custody flush cases. |
| WebSocket / `yin.repl.host` (J6) | Proposed first implementation, subject to owner Q6: explicitly composed out-of-process JVM adapter using existing host socket implementation, with canonical stream data and connection/attachment identities. No raw socket or JVM object crosses. Transport helper protocol must preserve dao.stream/ws event/outcome/lifecycle rules, including bounded acceptance, full/backpressure, close and unknown acceptance; it is implementation plumbing, not a new application ABI. Qualify real jolt↔JVM/Node/Dart peer operations. A direct Chez/libsocket implementation must pass the same adapter suite; Q6 decides whether it is required by J8 instead of a JVM sidecar. JVM implementation paths and their selecting guards are inventoried in §3. |
| `dao.stream.datagram.jvm` / UDP / P2P-DHT (J7) | Do not load the JVM socket namespace on jolt. Implement the existing datagram host seam via a narrow nonblocking POSIX socket adapter/FFI, preserving bound/send-failed/closed events, maximum datagram policy, IPv4/IPv6, and host-composed deposit medium. Existing `datagram.cljc` contract supplies semantics; do not add reliability or queues beneath it. P2P/DHT is explicitly outside the first local-lane cut. It becomes a full-parity gate, not a hidden skip. |

The exact list of ~15 heavy namespaces in S is not a validated capability matrix. J0 inventories the full standard-main and tests dependency closure, not just `yin.repl`. Host-specific imports must be guarded so absent optional networking does not break local loading. Unavailable features return their existing qualified unsupported/not-found/failure forms, never success stubs; missing prerequisites for a declared supported feature fail the test run. Storage/security are not deferred from full local CLI usability merely because the API spike used in-memory state.

## 7. Frontends: recommend the existing ANTLR pipeline through a parsing worker

V: `frontends.cljc:11–17,54–85` installs Clojure, PHP, Python and merges Python primitives. `yang/python/antlr/parser.cljc:19–25` imports generated Java recognizers; `repl_probe.cljc:27–30` also requires parser/CST. `yang/python/antlr/repl.cljc:26–32` is portable CST lowering/runtime composition. Therefore removing only one catalog require does not solve the graph.

Options:

- **(a) Generated ANTLR parsing worker — recommended completion path; host is a J5 parameter.** `yang.antlr.md:472–509` already specifies local-or-service parsing through streams. V: `yang.antlr.md:496–500,3229–3231` covers generated Python parsers for JVM, Node and Dart. `bb.edn:6–31` builds the Node bundle and Dart AOT peer; these prove available host targets, not an already implemented parsing-worker command. Qualify JVM / Node / Dart AOT over the same stream contract, defaulting to the target already a prerequisite of the lane being run. Among these choices, Dart AOT permits a JVM-free, Node-free deployed parser process tree (generation/build prerequisites remain separate). Use the pinned generated parser for that target and export the same sealed CST packets/diagnostics via the existing `yang.stage`/packet contracts. Keep scope/lowering/prelude/primitives/render/session evaluation in jolt. Also route the parser-dependent completeness probe through this worker or its equivalent diagnostic packet; otherwise multiline behavior diverges. One long-lived worker amortizes startup; no process per expression.
- **(b) Clojure-only catalog — J0/J3 bootstrap only.** Add PHP once its existing portable path passes. Selection of unprovided Python must return qualified unavailable, not silently choose another parser or language. Not the final standard catalog.
- **(c) ANTLR-free Python — reject as the parity shortcut.** Older prose in `yang.antlr.md:187` names a legacy handwritten parser, but later implementation/migration notes at :3229 and :4004–4005 explicitly remove legacy fallback. The current source inventory has `yang/python/antlr/*`; no verified replacement supplies the standard profile. A new parser is a separate language-conformance project. A CPython AST subprocess is also not a drop-in CST/profile equivalent.

P: compose source-request and response media, identity-correlated requests, canonical Jing values and bounded subprocess I/O adapters. The process boundary carries no parser objects or callbacks. Existing `yin.repl` process tests, R5 peers and `yin.repl.serve` provide lifecycle/testing patterns, not an existing ANTLR-worker command to claim as implemented. Current main advertises `--port` (`main.cljc:718`), so do not invent a functioning `--serve` main flag from the brief; any worker entry point is new planned work.

The current frontend binding exposes synchronous parse/probe functions. J5 must connect the service through an explicit driver-owned pending compilation state and response consumption; do not hide an unbounded blocking RPC in `eval-input` or let worker callbacks mutate a REPL state. Freeze this adaptation's API/trace tests before implementation: local immediate compilation remains equivalent, service-backed submission can report pending and subsequent driver ticks complete it. Reject stale responses after reset, worker exit and profile mismatch with existing qualified frontend diagnostics. Bounded buffers, timeouts as explicit host facts, restart/cancellation and graceful worker shutdown are required. No guest computation is offloaded to the worker; the worker only supplies syntax/probe facts.

Acceptance: the standard entry selects `(lang :yang/python)` successfully with its declared worker composed; `1 + 2`, definitions across submissions, multiline functions/indentation, `print`, exceptions, arbitrary integer shifts, floats and reset match the existing Python REPL suite. PHP and Clojure continue to work, and syntax-error source spans/profile identities match. Missing worker produces the specified unavailable-parser result. Service-backed Python must be labeled as requiring its bundled/configured parser worker runtime. A no-JVM runtime requirement selects Node or Dart AOT; it does not require a new parser. Qualify every advertised worker choice, without claiming the other choices tested.

## 8. Toolchain, reproducibility and commands

Owner policy: mise `chezscheme = "latest"` stays. S observed **10.4.1**; V, **uncommitted**: `mise.toml:8` contains the selector; `git diff -- mise.toml` confirms it is a working-tree addition, not yet a tracked revision of this setting. J0 includes committing this owner-directed setting before recording a qualified lane result. This is a moving selector, not a permanent exact version pin. Record the resolved Chez build/version and machine target in each lane manifest. `JOLT_CHEZ` names that installed executable explicitly; do not permit a jolt `make` invocation to silently select another Chez.

Track the full initial jolt SHA **96d7b8662d7c491722445bf976b2e25e36ce5d63**, GitHub main origin and recursive submodule SHAs in proposed `jolt-toolchain.edn`. Bootstrap a clone under `build/tools/jolt`; normal test runs use the recorded revision. An explicit update task resolves main, records the candidate and reruns the full gate before promotion. This satisfies main-tracking without making past results unreproducible. No invented Maven/git library coordinates. A mise git-plugin integration is unnecessary until proved; mise owns Chez and the repo task owns the CLI clone.

V: local jolt README:95–115 documents threaded Chez 10.x, JOLT_CHEZ precedence and OpenSSL/git prerequisites; README:130–164 documents root deps aliases, `-Srepro`, `-Spath`, `-Stree`. Record dependency resolution and fail on missing declared artifacts. `bin/jolt` runs from the checked-in seed; optional `make devboot` is in Makefile:1901–1902. The Makefile also references its provisioned GCC bundle at :111–113. **Assumption to verify at J0:** exact xPack download/compiler selection and platform behavior; inventory its build scripts before enabling it. Devboot and `jolt build` must record compiler/provisioning checksums and use the same Chez; discard caches on runtime/source changes. Source-mode execution is the required baseline; AOT packaging is an additional parity gate, not the fix for semantic failures.

Existing spike repro (S; diagnostic only, never the production lane):

```sh
cd /Users/sto/workspace/datomworld
export JOLT_CHEZ="$HOME/.local/share/mise/installs/chezscheme/10.4.1/bin/scheme"
build/jolt-spike/climb.sh yin.repl
build/jolt-spike/jolt/bin/jolt -Sdeps '{:paths ["build/jolt-spike/overlay" "build/jolt-spike" "src/cljc" "src/clj"]}' build/jolt-spike/entry.clj
build/jolt-spike/jolt/bin/jolt -Sdeps '{:paths ["build/jolt-spike/overlay" "build/jolt-spike" "src/cljc" "src/clj"]}' build/jolt-spike/parity.clj
```

Proposed supported repro after implementation (these tasks/aliases do not exist yet):

```sh
mise install chezscheme
bb bootstrap:jolt

# Proposed bootstrap writes this environment file with the resolved mise path.
. build/tools/jolt-env.sh
bb build:jolt-native
build/tools/jolt/bin/jolt -Srepro -M:jolt:jolt-yin-repl
build/tools/jolt/bin/jolt -Srepro -M:jolt -m yin.demo
bb test:jolt
bb test:all:jolt
bb test:sub:jolt yin.vm
bb test:changed:jolt
```

J0 must confirm CLI alias order and the actual subsystem spelling used by list output. Tests run without overlay directories even present; a clean checkout/workdir and clean cache repeat is mandatory. Record OS/arch, git/submodule SHAs, Chez, native compiler/libraries, aliases, resolved classpath, fixture revision and test counts with every result. Prebuilt release bundles include licenses and native-library discovery relative to the bundle; no dependence on an author's build directory.

## 9. Shared conformance, slices and completion

Use engine §E.5's one oracle/export/differential method. Jolt joins as another execution lane; it does not replace CLJS or manufacture expected values from itself. K0/W0 register fixtures remain in register §10.4. Reuse semantic corpus `test/yin/vm/semantic/corpus.cljc`, literal goldens and `test/yin/vm/debruijn/register_test.cljc`, with declared bindings and genuine parked targets; execute every arm, preserve refused cases, compare traces rather than only values. Compare raw pcs, sparse regs/live, frames/K, stores, flags, counters, wait/ready/parked entries, custody and ordered requests. Normalize private host identities only; retain owner/seal/profile/address equality for equal secrets and data. Byte/hash suites extend existing fixtures rather than a jolt-only golden corpus.

| Slice | Implementation boundary | Falsifiable acceptance |
|---|---|---|
| J0 — reproducible tracked load | Manifest/bootstrap, branch inventory, small source branches, narrow wire override, explicit provisional catalog and pure hash interim | Clean-cache require of core REPL plus standard/main dependency audit from tracked roots; no overlay. Reproduce S arithmetic and six hashes, list all remaining unsupported capabilities. Commit the currently uncommitted mise selector; inventory `src/clj` require guards, add `.jolt` formatter coverage, and verify kondo analyzes `:jolt` arms. Probe jolt test runner, FFI/native loader, `:jolt/provides` and built-binary precedence; verify the established source root order with `-Spath`. Known-wrong bit ops never promoted. This is load/smoke, not codec parity. |
| J1 — numeric/content correctness | Exact integer algorithms, full Jing representation/wire, C BLAKE3, libcrypto SHA, arrays sort | Existing numeric/CBOR/hash corpus bytes and refusals match JVM/CLJS/CLJD; official hash boundaries; all supported scalar classes and host-value wire nodes (byte arrays at any position); decode seams `cbor.cljc:537,649–652`; integer >64-bit tests and ~x identities; correct code-index sorting. No SHA stub or native-float wire shortcut. |
| J2 — pure test lane and VM conformance | `test:jolt` family, shared fixture adapter, B-tree/space/VM pure suites and runtime bug fixes | Every applicable pure test loads and passes; full semantic/register boundary traces match. No silent excludes, unchanged goldens, expected counts and nonzero failure exit. A test that throws must be reported as an error while subsequent tests still execute and report: reproduce the escaped-exception failure noted in `docs/jolt-experiment.md:70`, with sentinel tests after it and final nonzero status, not a silently aborted batch. Shared source changes pass existing three lanes. |
| J3 — local shell and demo | Jolt main/stdin/wake/banner, local catalog composition, shell driver/index/query | API and CLI run from tracked roots; demo=5050; EOF/quit, no duplicate eval, cadence/wake/pending semantics; all VM modes; published code index, no warning workaround. Clojure/PHP support explicit, Python still unavailable until J5. |
| J4 — persistence and authority | File/store FS, Ed25519/keygen/key files, lifecycle cleanup | Existing file/B-tree/store crash/recovery/lock fixtures and RFC/envelope/signature vectors pass; cross-host reopen and signing both directions; no security stubs; main local storage options and shutdown work. |
| J5 — complete standard frontends | Selected JVM/Node/Dart AOT ANTLR worker composition, pending compilation/probe integration | Standard catalog loads and `(lang :yang/python)` works; frontend_catalog/python_antlr/main suites, multiline/session/reset/errors, syntax/profile parity, worker death/backpressure/stale responses. Declare and qualify selected worker runtime(s). Local REPL milestone reached with its “requires a configured parser worker runtime” label. |
| J6 — network REPL peers | WebSocket adapter and lifecycle integration; process or direct implementation as resolved by Q6 | jolt dial/serve and real cross-host REPL/R5 peer fixtures; bounded acceptance/outbound behavior, disconnect/close/custody handoff and failure paths. Process construction uses existing contract outcomes. |
| J7 — UDP and DHT | POSIX datagram implementation; existing DHT/linker composition | Datagram/address/size/lifecycle suites, jolt legs in DHT process and signed-head tests, bind/send failures and shutdown. If deferred, full network parity remains incomplete. |
| J8 — repo lane qualification | All-task/changed/subsystem integration, clean repro, package checks and benchmark reporting | Full supported fast/all/slow suites green; reviewed host-only applicability list; all standard entry paths and J1–J7 gates pass on declared platforms; existing three lanes green. Same shared kit/version, no overlays/stubs, no unexpected skips; owner Q6 resolved and any sidecars disclosed in the parity label. Benchmark report and proposed thresholds recorded, not a speed threshold gate. |

Tests are required at the earliest owning slice and rerun as dependencies change, not postponed to J8. A namespace load pass never counts as behavioral acceptance. Add capability-qualified test reporting to list output so one can tell slow exclusion from unsupported host capability from failure.

At J8 benchmark startup cold/warm/devboot/AOT, REPL submission latency, large integer operations, hashing throughput, B-tree/index workload, Python worker startup versus steady state, long tail programs, GC/retention and cross-stream waits. Use fixed inputs, toolchain manifest and current JVM/Node/Dart baselines, keeping parse-worker time separate from VM time. J8 records benchmarks and sets proposed regression thresholds; it has no falsifiable performance threshold gate. A performance regression gate becomes falsifiable only in a subsequent, separately commissioned slice with those thresholds fixed. No speedup or universal runtime ratio is claimed by this plan.

## 10. Risks, decisions and owner questions

Decisions recommended here: shared-source lane; exact arithmetic workaround now, upstream fix separately; C reference BLAKE3; public libcrypto SHA/Ed25519 APIs; generated ANTLR syntax worker with J5 host selection; explicit local-only intermediate milestone; full networking gates before unqualified parity. No forked engine, handwritten crypto or parser, blanket exception catches, or success-only test stubs.

Risks: jolt's maintainer concentration and rapid main churn (S) require recorded revisions and upgrade gates; missing published dependency coordinates require a CLI/bootstrap lifecycle. Shim compatibility can fail deep inside executed paths after a successful require. Chez numeric carriers, Sorted dispatch, GC refs and set iteration can change observables. Native-library architecture/loader/lifetime mistakes can crash the process; isolate them in narrow reviewed wrappers and test missing symbols/incorrect versions without executing guest work. File locking/durability and Ed25519 are release gates, not “later polish.” Worker-based parsing adds startup, packaging and failure-state complexity; the deployed lane is not JVM-free when selecting the JVM worker or JVM WebSocket sidecar; disclose the selected process tree. Historical slow jolt results warrant benchmarking, not preemptive weakening of tests.

Open owner decisions for implementation commissioning:

1. Accept local REPL milestone first, with full J6/J7 network parity still scheduled, or explicitly commission only the narrower milestone? The final label must match the chosen scope.
2. Confirm initial platform support (recommended macOS arm64 + Linux x86_64); Windows and additional architectures need their own native/FS/network gates.
3. Which parser worker runtime(s) should J5 qualify first: JVM, Node or Dart AOT? Recommend the target already required by the selected test lane. A no-JVM runtime requirement changes the worker target, not the parser project; Dart AOT also removes Node from the deployed parser process tree.
4. Confirm native dependency vendoring/distribution policy and release packaging; J0 selects and records exact BLAKE3/OpenSSL source/build revisions under that policy.
5. Set update cadence and benchmark budgets after J0/J8 evidence, not from the spike's days-versus-weeks estimate.
6. Does host-lane parity (J8) permit JVM sidecars in the jolt process tree for parsing and WebSocket, or must J8 require the direct Chez/libsocket WebSocket implementation and a non-JVM parse worker? If sidecars are permitted, the J8 label must say so. This is a deployment qualification decision, not a second ABI or a privileged-peer role.

These questions do not block writing this plan or the read-only exploration. They identify implementation scope choices; nothing in this findings file claims authorization to modify source or launch that work. Nothing is commissioned until the owner answers.


## Review disposition — Fable round 1

All findings were checked against the cited tree or recorded spike artifacts; no new execution evidence is claimed.

| Finding | Disposition | Folded correction / evidence |
|---|---|---|
| F1 | Accepted | §4 and J1: overlay byte-array/node mismatch, complete host-value vocabulary, explicit decode/float-field seams at `cbor.cljc:537,649–652`. |
| F2 | Accepted | §§1, 7, J5, Q3: JVM/Node/Dart AOT worker parameter; generated-target evidence `yang.antlr.md:496–500,3229–3231`, builds `bb.edn:6–31`. |
| F3 | Accepted | §2 milestone labels, §6, J8 and Q6 disclose sidecars and leave parity deployment policy to owner. No inference that an adapter grants privileged peer status. |
| F4 | Accepted | §§2–3 and J0: source roots ordered `src/jolt` first; `loader.ss:389–395,467–481`, `deps.clj:1744–1746,1776–1782`; probes retained for provides/binaries. |
| F5 | Accepted | §8 and J0: mise selector explicitly uncommitted; committing it is future J0 work. |
| F6 | Accepted | §3: removed incorrect older-offset aside; original spike hash citations are valid. |
| F7 | Accepted | §3: host range corrected to `15–17,20–37`. |
| F8 | Modified | §3 and §6 retain all requested paths and full J0 guard inventory. Correct the review’s selecting-site claim: `yin.repl.host:16` requires `host.jvm`, which requires `ws.jvm` at `src/clj/yin/repl/host/jvm.clj:3`; `dao.space.dht:57` requires `datagram.jvm`. Portable `dao.stream.ws` / `dao.stream.datagram` do not select those JVM namespaces. |
| F9 | Accepted | §3/J0: formatter extension and explicit kondo feature coverage. J2: thrown-test sentinel probe, continued reporting and final failure exit. |
| F10 | Accepted | §9/J8: benchmark reporting and threshold setting only; future commissioned slice owns a falsifiable performance regression gate. |
| F11 | Accepted | §4/J1: ~x and XOR −1 identities; actual public path cited at Python prelude:1507–1511 and integer.cljc:350 (two subtractions implement −x−1). |
