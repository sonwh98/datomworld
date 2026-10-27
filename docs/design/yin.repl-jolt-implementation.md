# yin.repl on jolt: implementation plan for the jolt host lane

This plan adds jolt (Clojure on Chez Scheme) as a fourth shared-source host lane
for yin.vm and yin.repl, next to the JVM, Node and Dart lanes. It is not another
VM, not a source-to-jolt compiler and not a new dao.stream contract. The
existing semantic, register and stack machinery, frontend lowering, stream
contracts and conformance oracle stay shared. jolt branches are added only where
the host differs: exact integers, hashing, CBOR host values, files, signing,
sockets, UDP and WebSocket. The standard frontend catalog is served by an
in-process ANTLR4 C++ parser over FFI, with no fallback. The first milestone is
a local REPL (through J5). Host-lane parity, with native WebSocket and UDP/DHT,
follows at J8.

Status (2026-10-24): implementation in progress; local REPL and host-lane
parity are not yet qualified. Landed on master: J0a toolchain/bootstrap
(`9f1f9144`, Debian prerequisites through `d5b4ce95`); J0c C++ parser probe
(`9fbee557`, probe-only); J1 dao.host overrides (`0d07e55d`); J1b hash/sign
with pinned libblake3 and qualified system libcrypto (`97c7b902`); linear text
walks (`f6e29391`); J2 test lane, CBOR host seam and libcrypto random bytes
(wrapper layout 3, `a0a49b8e`); CBOR override drift guard (`4682c583`). J0b is
not merged: the UDP probe stays on `jolt-j0b` (`6fa97400`). J2 is closed
(2026-10-24, master `f4d6a284`): J2 rerun 3 on shiva at `078e1e43` ran all 150
included core namespaces with 0 load failures, 0 timeouts and 0 errors (202,787
of 202,791 assertions; the 4 failures are the owner-accepted jolt-lang reader
failures in `yin.repl-test`, known-gaps P1); the
`yin.vm.semantic.safepoint-harness-test` passed on jolt (60 tests, 265,225
assertions, 0 failures) at `a0a49b8e`, test file unchanged since; the exclusion
table landed in `9c2d8da0` per the owner's J4 ruling as amended ("both sets",
2026-10-23: 10 whole-namespace J4 exclusions plus jolt-only file guards in 7
suites, known-gaps rows 61-63); gate (f) is green on all three lanes: the JVM
baseline of rerun 3, Dart `bb test:cljd` 4,113 tests on `f4d6a284`, and one
complete Node lane on shiva (scratch clone of origin/master `f4d6a284`, rc=0,
242 namespaces compiled and run, 0 failures, 0 errors; complete, so it includes
`yin.vm.linker.dht-test`, where a laptop run had stalled, and
`dao.jing.dht-test`); the laptop stall was environmental, corroborated by the
host-boundary seat's independent focused run (16/217/0). After J2 (2026-10-24):
the transit-via-`yin.repl` suites rejoined `test:jolt-core` (linker-require,
linker.head, linker.publish, linker.admission); `yin.vm.host-typed-values-test`
rejoined too on the jolt fork re-pin `b389f1a7` (assoc on a non-Associative
deftype now throws, as on the JVM; 156 included / 53 excluded); J2b is closed
(2026-10-24, macOS arm64 and Linux x86_64, JVM canonical): the restricted
lowering comparison holds and every host (JVM, Node, Dart, jolt) meets the JVM
parser oracle with zero pins, after the Node/Dart lazy-buffer first-failure fix
and the JavaScript/Dart lexer-helper manifest patches. J2b closing is necessary
for J5 acceptance, not sufficient. Slice states are in §10.1.

This document consolidates the architect plan r7
(`archive/jolt/1792427226000-architect-jolt-lane-plan-r7.gpt-6-astra.findings.md`),
its independent review
(`archive/jolt/1792427469000-architect-jolt-lane-plan-review-r7.claude-opus-5-5.findings.md`,
findings W1-W9) and the owner decisions of 2026-10-19. The four design points
the review left open are ruled on in §13.4 (Architect, 2026-10-20; the owner
may overrule). Later owner rulings are recorded in §13.5. The codec
track ([dao.stream.codec.md](./dao.stream.codec.md)) is independent of this
document and keeps its own owner questions; this document states only J6's
dependency on it (§9.4).

Change note 2026-10-20 (Architect: claude-fable-5-1,
`archive/jolt/1792454059000-architect-jolt-design-points.claude-fable-5-1.findings.md`):
§13.4 becomes "Architect rulings (owner may overrule)" and rules on the
Transit require, the WebSocket substrate, the 1009/1006 expectation and the
initial pin; §4.2, §4.4, §5, §6.4, §9.2-§9.4, §9.6, §10.1, §12, Appendix A and
Appendix B are updated to match. Licensing ("local builds only for now; revisit
before any distribution") and wss:// ("out; ws:// only") are recorded as
owner-settled in §13.1 and removed from §13.3. Nothing else changed; nothing
was commissioned.

Change note 2026-10-20, later (Architect: claude-fable-5-1,
`archive/dao-stream/1792454808000-architect-transit-decoupling.claude-fable-5-1.findings.md`):
the owner redirected ruling 1 with "we should rethink dependency on transit
namespace. it should not require transit". Ruling 1 is replaced: no shared
namespace requires `dao.stream.transit`; the portable domain moves to a
codec-independent namespace, every codec is composition data, and the Transit
default disappears from `dao.stream.ws`, the host adapters and
`dao.stream.remote-channel`. This is a shared-code change on all three lanes,
sequenced as a new prerequisite slice T0 in §10.1. §4.2, §4.4, §9.4, §9.6,
§10.1, §10.2, §12, §13.3, §13.4 and the appendices are updated. One item is
the owner's and is flagged, not decided: which codec profile the yin.repl
network compositions select by default once no shared default exists.

Change note 2026-10-20, third (Architect: claude-fable-5-1,
`archive/dao-stream/1792455434000-architect-stream-codec-design.claude-fable-5-1.findings.md`):
the owner widened the requirement to peer codecs ("cbor and transit are peer
encoding ... open to ... protocol buffers or apache arrow") and then separated
the tracks ("the codec redesign should be independent of jolt design doc"). The
codec design lives in [dao.stream.codec.md](./dao.stream.codec.md), an
independent track that never mentions this one. Ruling 1 and slice T0 here are
withdrawn; this document now states one dependency (J6 needs the codec track's
C0-C4 landed; jolt requires nothing else of it) and the jolt-side consequences
only (§9.4, §10.1). Three review corrections from gpt-6-astra
(`archive/jolt/1792454716000-architect-jolt-design-doc-review.gpt-6-astra.findings.md`)
are folded in: A4 (the 1009/1006 ruling asserts the diagnostic at the
detecting endpoint and one terminal event plus sender-specific wire behaviour
at the peer, not identical observables at both ends; §9.6, §13.4 ruling 3), A5
(the pin ruling restores Q5's "prefer a suitable release tag, allow an
explicitly qualified untagged fix" and no longer mandates the newest release;
§6.4, §13.4 ruling 4) and the Autobahn point (the case list and explicit
pass/exception criteria are pinned before J6 is commissioned; recording reasons
for non-strict results is not acceptance; §9.5, §9.6, J6 row). Astra's A1-A3
concerned the withdrawn ruling-1 shape and are moot here.

Change note 2026-10-20, fourth (Engineer: claude-opus-5-5, owner-directed,
`archive/jolt/1792489765000-engineer-jolt-j0a-os-packages.claude-opus-5-5.findings.md`):
the owner decided "lets assume debian distribution and apt get for dependency
management of package", then "yes to 1, 2, 3 and 4 as recommended". Linux
means the Debian family with apt: host packages are declared in
`jolt-toolchain.edn` `:os-packages` and checked by `bb bootstrap:jolt` before
any mise build, and Linux libcrypto is the apt-provided system library with
the version check retained. macOS assumes Homebrew, unenforced. Amended:
§6.1, §6.3 (Linux bullet) and §13.1 (new row). Trigger: on shiva the mise Chez
build failed with "cannot find -lncurses" until `libncurses-dev` was installed.
Nothing else changed.

Change note 2026-10-22 (Engineer: codex, docs only): refreshed landed slice
states and open gates, the owner-fork pin (`63301597`) and the owner's dated
rulings (§13.5). Acceptance text and historical measurements are retained.

Authorities:
- [datom.world.md](./datom.world.md) and
  [datom.world.abi.md](./datom.world.abi.md): axioms and host-boundary doctrine
  ("implementations do not add contracts").
- [dao.stream.md](./dao.stream.md) and
  [dao.stream.datagram.md](./dao.stream.datagram.md): the stream abstraction and
  the landed datagram contract.
- [yin.vm.engine.md](./yin.vm.engine.md): shared engine semantics and the
  one-oracle differential method (§E.5).
- [yin.vm.debruijn.register.md](./yin.vm.debruijn.register.md): register
  contract and the K0/W0 fixtures (§10.4).
- [yang.antlr.md](./yang.antlr.md): CST packet, worker isolation, generation
  targets and the helper-licensing gate.
- [test.subsystems.md](./test.subsystems.md) and
  [build-n-test.md](../agents/build-n-test.md): test lanes, subsystem taxonomy
  and the shiva scratch-clone CI.
- The spike:
  `archive/jolt/1792412960461-engineer-jolt-yin-repl-spike.claude-opus-5-5.findings.md`
  (evidence label S).

---

## 1. Objective, scope, non-goals

Objective. A jolt lane that:

1. loads the tracked yin.vm/yin.repl sources under jolt with no overlays, stubs
   or copied core namespaces;
2. passes the same shared test surface as the other lanes, against the same
   oracle, with a reviewed list of host-only exclusions;
3. runs the standard REPL entry paths (§3) with the standard frontend catalog,
   local storage and signing (local REPL milestone, J5);
4. then joins the network as an ordinary peer: native WebSocket and UDP/DHT,
   with no helper process (host-lane parity, J8).

Scope. The host adaptations in §5, the native libraries and toolchain policy in
§6, the parser in §7, UDP in §8, WebSocket in §9, the build aliases and `bb`
tasks that run the lane, and the slices in §10. Platforms are macOS arm64 and
Linux x86_64 (owner Q2).

Non-goals.

- No forked engine, second VM, second codec, second conformance kit or jolt-only
  golden corpus.
- No jolt service ABI. Local FFI is host implementation machinery behind
  existing seams; effects, parser source/CST interpretation and peer
  communication stay composed through dao.stream.
- No handwritten cryptography, no handwritten parser, no SHA stub, no
  success-only test stub, no exception catch that hides an error.
- No Windows (out of Q2 scope). No wss://: ws:// only (owner-settled, §13.1).
- No parser fallback (owner Q3). No WebSocket sidecar (owner Q6).
- No performance budget. Benchmarks are reported from J0 on; thresholds are
  proposed at J8 and gated only by a later, separately commissioned slice.
- No graphical jolt shell. Browser and Flutter demos keep rendering on their own
  hosts.
- Effort estimates in the spike or in `docs/jolt-experiment.md` are not delivery
  commitments.

## 2. Evidence labels

Every claim carries the plan's label.

+-------+----------------------------------------------------------------------------------------------------------------------------------------------------------------+
| Label | Meaning                                                                                                                                                        |
+=======+================================================================================================================================================================+
| V     | Verified by reading this tree or the local jolt checkout (`build/jolt-spike/jolt/` at `96d7b866`). Read, not executed. Local jolt paths are evidence, not      |
|       | proposed production paths.                                                                                                                                     |
+-------+----------------------------------------------------------------------------------------------------------------------------------------------------------------+
| S     | Spike-verified: reported execution in the spike findings. The spike loaded 91 namespaces with four overlays; semantic evaluation and six content hashes        |
|       | passed. It did not prove the standard catalog, CLI, durable store, signing, networking, all VM modes, the full test suite or performance.                      |
+-------+----------------------------------------------------------------------------------------------------------------------------------------------------------------+
| R     | Read, not run. Declarations, source control flow, documentation or specifications establish groundwork, not runtime capability. Every socket, poller, FFI,     |
|       | ANTLR C++, RFC and licensing claim is R until a probe runs.                                                                                                    |
+-------+----------------------------------------------------------------------------------------------------------------------------------------------------------------+
| P     | Proposed. File names, task names, aliases, deployment choices and acceptance gates that do not exist yet. Exact native revisions and platform packages are     |
|       | recorded by J0; this document invents no version pin.                                                                                                          |
+-------+----------------------------------------------------------------------------------------------------------------------------------------------------------------+

Rows measured at the old pin `96d7b866` are historical measurements.

Citation status. The repository lines this document reopened while being written
are listed in Appendix B; one drift was found and corrected (`btree.cljc`). All
other line cites are carried from r7 and its review and were not rechecked here.
No build, probe, test or benchmark was run for this document. The 2026-10-20
rulings reopened the lines listed under "Reopened 2026-10-20" in Appendix B and
ran read-only `git log`/`git tag`/`git describe` in the jolt checkout; nothing
else was executed. The 2026-10-22 refresh read the landed commits, current
source/toolchain declarations and the cited collab reports; no runtime gate
was rerun for this documentation change.

`docs/jolt-experiment.md:11-21,60-75` reports failures and timings for jolt
v0.8.3 (BTSet/subseq, interop gaps). Those are regression candidates, not
measurements of `96d7b866`.

## 3. Milestones, labels and entry paths

Two named milestones. Neither is reported as an unqualified green badge.

1. **Local REPL lane, native C++ parser library required (through J5).**
   Tracked-source API, CLI, index/store and the standard frontend catalog
   operate; every supported test is green. Networking refuses explicitly until
   J6/J7. Not parity with the network-capable lanes.
2. **Host-lane parity, native WebSocket and C++ parser libraries (J8; ws://
   only, owner-settled).** The entry paths and the shared host-neutral
   test surface pass, plus the WebSocket/peer and UDP/DHT capability gates. No
   network helper process; parsing is in-process C++. Build-time JVM generation
   and cross-host test peers are not part of the deployed process tree. Only
   inherently foreign-host tests (build, UI, JDK adapters) are excluded, through
   a reviewed applicability manifest. If the owner defers networking, the
   narrower milestone is published with its gaps and is not called parity.

Required entry paths (V; cites from r7):

+-----------------------------------------------------------------------------------+------------------------------------------------------------------------------------+
| Entry                                                                             | Completion behaviour                                                               |
+===================================================================================+====================================================================================+
| `yin.repl/create-state` (`src/cljc/yin/repl.cljc:814`) and `eval-input` (`:2095`) | Same options, results, output events, pending behaviour and errors. Direct core    |
|                                                                                   | creation defaults to an empty catalog (`:900-958`); keep that API.                 |
+-----------------------------------------------------------------------------------+------------------------------------------------------------------------------------+
| `yin.repl.frontends/standard` (`frontends.cljc:54`) and `create-state` (`:78`)    | Standard Clojure, PHP and ANTLR Python registrations, including Python primitives  |
|                                                                                   | and render/probe/lower-session. No hand-built catalog from the caller.             |
+-----------------------------------------------------------------------------------+------------------------------------------------------------------------------------+
| `yin.repl.main/-main` (`main.cljc:1306`, JVM arm)                                 | A new jolt arm owns cadence, stdin and shutdown without JVM Thread/Runtime.        |
|                                                                                   | Proposed `jolt -M:jolt:jolt-yin-repl`; same local options, VM selection and clean  |
|                                                                                   | EOF/quit. Network flags refuse explicitly until their slice.                       |
+-----------------------------------------------------------------------------------+------------------------------------------------------------------------------------+
| `yin.demo/-main` (`src/clj/yin/demo.clj:275`)                                     | The two-VM continuation demo returns 5050 (`run-demo:176`). Keep its source root   |
|                                                                                   | available, or move only host-neutral content when authorized; never substitute the |
|                                                                                   | spike's arithmetic script.                                                         |
+-----------------------------------------------------------------------------------+------------------------------------------------------------------------------------+
| Browser and Flutter demos                                                         | Rendering stays on the existing hosts. Shared REPL semantics join the jolt tests;  |
|                                                                                   | Chez acquires no DOM or Flutter.                                                   |
+-----------------------------------------------------------------------------------+------------------------------------------------------------------------------------+

Every exposed `--vm` choice runs through the common shell tests (the spike
verified only semantic). Also cover `(host)`, `(lang ...)`, reset, definitions
across submissions, output ordering, queries and code-index publication. `(map
inc ...)` is not a jolt regression until it is compared against the same JVM
primitive registry.

## 4. Shared-source lane architecture

### 4.1 Reader features and branches

V: jolt reads `("jolt" "clj" "default")` (`host/chez/reader.ss:1102`) and
resolves `.jolt`, `.clj`, `.cljc` (`host/chez/loader.ss:332-338`). The first
matching reader branch wins. Where ClojureDart compilation needs it, `:cljd`
stays first; then **`:jolt` goes before `:clj` and before `:default`**. Audit
namespace imports, type hints, catch classes and spliced requires, not only
function bodies. Do not add `:jolt/provides` to pretend a partial shim
implements the JDK.

- Small `.cljc` branches carry representation differences.
- A `.jolt` file is used only for a complete, narrow host implementation (CBOR
  wire writer, native-library facade, WebSocket/OS adapter) with the same public
  surface and contract tests.
- `yin.repl`, `yin.vm.engine` and the full `dao.jing.cbor` are never copied into
  host shadows.

### 4.2 Source roots: `src/jolt` first

V: roots are searched outermost-first, and `.jolt`, `.clj`, `.cljc` are tried
within each root (`host/chez/loader.ss:389-395,467-481`;
`jolt-core/jolt/deps.clj:1744-1746,1776-1782`). A later-root `.jolt` does not
beat an earlier-root `.cljc`. So `src/jolt` (a new tracked host source root, not
an overlay) goes before `src/cljc` and `src/clj` in the `:jolt` alias. Verify
the resolved order with `-Spath`, record which file supplies each host override,
and audit aliases whose `:extra-paths` precede `:replace-paths`. J0a keeps a
precedence probe for `:jolt/provides` / `ldr-ns-replaced-rel?` and for built
binaries, whose embedded sources otherwise probe first.

Listing `src/clj` on the classpath must not load JVM implementations. V:
`yin.repl.host` selects `yin.repl.host.jvm` under `:clj`
(`src/cljc/yin/repl/host.cljc:15-17`), which reaches `dao.stream.ws.jvm`;
`dao.space.dht` selects `dao.stream.datagram.jvm` under `:clj`
(`src/cljc/dao/space/dht.cljc:53-57`). Each selecting branch gets a `:jolt`
guard before `:clj`. The portable `dao.stream.ws` and `dao.stream.datagram` do
not themselves select these JVM namespaces, but they have other host operations
to audit; `dao.stream.ws` also requires `dao.stream.transit` unconditionally
today; removing that is the codec track's work
([dao.stream.codec.md](./dao.stream.codec.md)), on which J6 depends (§9.4).

### 4.3 Build declaration and tasks

V: root `deps.edn:1-23` supplies dependencies and paths, `:test` (`:30`) uses
Cognitect's runner, `:clj-yin-repl` (`:66`) runs `yin.repl.main`; `bb.edn:34-90`
defines the fast/all/slow lanes and `:96-170` changed/subsystem selection. These
are implementations for other runtimes, not scripts to run unchanged under jolt.

P: a `:jolt` **host configuration alias**, not a dependency coordinate.
`:replace-paths` selects `src/jolt` before `src/cljc` and audited `src/clj`
content (including the demo), excluding generated Java parser classes and
JS/Dart roots. `:replace-deps` holds the portable source dependencies this lane
needs, versioned from the root; it does not fetch Datomic, http-kit or boring
JVM classes. The resolved closure is verified, not assumed empty. `:jolt-test`
adds test and runner paths; `:jolt-yin-repl` adds `:main-opts`. The alias
mechanisms exist in jolt (`jolt-core/jolt/deps.clj:8-9,1737-1792`, V). The
supported launch is always through the explicit alias so dependencies and native
libraries are reproducible.

Current implementation (V): `deps.edn` and `bb.edn` now contain the jolt host
and test aliases, bootstrap/native tasks and J2 lane (`0d07e55d`, `a0a49b8e`).
`bb test:jolt-core` runs every included test, including `^:slow` (§13.5);
`probe:jolt-j2`, `test:jolt-j2-jvm` and `report:jolt-j2` expose load order,
JVM counts and comparison. The remaining task family below is the plan (P).

Proposed tasks (P):

- `bb bootstrap:jolt`: materialize the recorded jolt SHA, submodules and
  declared native dependencies. Explicit update mode advances to a reviewed full
  SHA through `qualify:jolt-upgrade`; ordinary runs never pull.
- `bb build:jolt-native`: mise-driven, pinned-CMake vendored C/C++ builds into
  `build/native/<platform>/`, checked system/Homebrew libcrypto loading, feature
  and loader smoke checks. No download during namespace loading.
- `bb test:jolt`, `test:all:jolt`, `test:slow:jolt`: same `^:slow` and
  `dao.test-slow/guard` semantics. Start with a small tracked `clojure.test`
  runner (explicit namespace discovery, nonzero exit on load/test/error
  failures); prove Cognitect runner compatibility before reusing it. The runner
  is a launcher, not an oracle.
- `test:changed:jolt`, `test:sub:jolt`: extend `src/dev/affected.clj` and the
  existing require-closure mechanism; discover `.jolt` sources; treat
  toolchain/native/alias changes as wide changes. `src/dev/subsystems.edn` stays
  the taxonomy.
- At J8, jolt joins `bb test`, `test:all`, `test:slow` and the changed/subsystem
  dispatch and list output. Before then it is an explicit experimental task; no
  aggregate claims a lane it omits.

`gen:antlr` remains the JVM build step for generated parsers and gains the
pinned C++ target (§7). Node shadow builds and Dart compilation remain
prerequisites for oracle and cross-host peer tests only. Missing dependencies
fail prerequisites: jolt's README (`:110-115,160-164`) says fetch failures and
unknown aliases may otherwise be skipped or warned.

### 4.4 Fold and audit sites

J0a produces an exhaustive, machine-readable inventory of selected interop
branches and their tests, including every reached `src/clj` namespace and its
planned `:jolt` guard, and every runtime-unexercised dependency the closure
loads. `cognitect.transit` (W2; reached today through `dao.data`,
`dao.stream.cbor`, `dao.jing.stream` and `dao.stream.ws` -> `dao.stream.transit`,
V) must be **absent** from the jolt closure once the codec track has landed
C0-C4 (§9.4), after which no shared namespace requires `dao.stream.transit`;
the inventory then proves the absence rather than listing it as unexercised. If
J0a runs first, the inventory lists `cognitect.transit` as a runtime-unexercised
dependency and names the codec track as what removes it. The sites below are a
verified starting set, not a claim that the 91-namespace closure is portable.

+-----------------------------------------------------------------------+------------------------------------------------------------------------------------------------+
| Site (r7 coordinates)                                                 | Required work                                                                                  |
+=======================================================================+================================================================================================+
| `src/cljc/dao/jing/cbor.cljc:112-250`                                 | The spike overlay fixed `big`, `b+`, `b-`, `b*`, `bquot`, `bcmp`, `bgcd`; also audit `bsign`,  |
|                                                                       | `bpow`, `b->int:223`, `magnitude-bytes:235`. Chez exact arithmetic; no BigInteger              |
|                                                                       | constructor/valueOf/toByteArray.                                                               |
+-----------------------------------------------------------------------+------------------------------------------------------------------------------------------------+
| `cbor.cljc:462-658,993-1012`                                          | Predicates, float carriers and bits, decimal/ratio accessors and exact numeric comparison      |
|                                                                       | still carry JVM representation. Gate the whole scalar surface (§5.2).                          |
+-----------------------------------------------------------------------+------------------------------------------------------------------------------------------------+
| `src/cljc/yin/vm/integer/host.cljc:27-28,41-108,115-298`              | Import, carrier predicates, promotion/demotion, truncation, arithmetic, compare, three big bit |
|                                                                       | operations, shifts, magnitude length, radix output. The overlay's bit operations are knowingly |
|                                                                       | wrong; do not copy them (§5.1).                                                                |
+-----------------------------------------------------------------------+------------------------------------------------------------------------------------------------+
| `src/cljc/dao/jing.cljc:59-85,114-136`                                | SHA and BLAKE3 string/bytes sites branch to one jolt crypto facade; preserve UTF-8, byte-array |
|                                                                       | semantics and lowercase digest text.                                                           |
+-----------------------------------------------------------------------+------------------------------------------------------------------------------------------------+
| `src/cljc/dao/jing/cbor/boring.cljc:32-40,46-132`                     | Replace the Java boring dependency, for jolt only, with a narrow `.jolt` wire-node writer      |
|                                                                       | (§5.2).                                                                                        |
+-----------------------------------------------------------------------+------------------------------------------------------------------------------------------------+
| `src/cljc/dao/data/arrays.cljc:92,138-148`                            | `asort` calls `parallelSort` (`:145`, V); replace for jolt. Audit arraycopy, overlapping       |
|                                                                       | copies and clone.                                                                              |
+-----------------------------------------------------------------------+------------------------------------------------------------------------------------------------+
| `src/cljc/yin/repl.cljc:275-279`                                      | Jolt banner branch before the JVM arm, with recorded jolt/Chez identity; never print `JVM      |
|                                                                       | nil`. Audit rendering at `:364-395`.                                                           |
+-----------------------------------------------------------------------+------------------------------------------------------------------------------------------------+
| `src/cljc/yin/repl/frontends.cljc:11-17,54-85`                        | Remove the unconditional host-parser dependency for jolt; keep the complete catalog and        |
|                                                                       | primitive injection.                                                                           |
+-----------------------------------------------------------------------+------------------------------------------------------------------------------------------------+
| `src/cljc/yin/repl/main.cljc:11-30,199-239,873-880,951-959,1212-1364` | Eager storage/network/sign requires, file helpers, exits, JVM cadence/stdin. Port host         |
|                                                                       | mechanisms without duplicating `step-all` or driver semantics.                                 |
+-----------------------------------------------------------------------+------------------------------------------------------------------------------------------------+
| `src/cljc/yin/repl/host.cljc:15-17,20-37`                             | Select a jolt WebSocket adapter without requiring `host.jvm`/http-kit. An unsupported          |
|                                                                       | capability is explicit and does not block local startup.                                       |
+-----------------------------------------------------------------------+------------------------------------------------------------------------------------------------+

J0a also adds `.jolt` to `.cljstyle`'s `:files :extensions` (V: absent,
`.cljstyle:14`) and checks that the formatter keeps branch order; runs the
clj-kondo lane over representative `:jolt` arms and `.jolt` files with explicit
feature analysis (V: `.clj-kondo/config.edn` has no `:jolt` setting;
unknown-feature skipping is not linting); and removes all four spike overlay
paths and helper sources from production and test classpaths.

## 5. Host adaptations

+--------------------------------+-------------------------------------------------------------------------------------------------------------------------+-------------+
| Area                           | Jolt adaptation                                                                                                         | Slice       |
+================================+=========================================================================================================================+=============+
| Integers and bit operations    | Exact-arithmetic algorithms in `:jolt` branches of `integer/host.cljc`; never jolt's long-oriented bit functions on     | J1          |
|                                | bignums (§5.1).                                                                                                         |             |
+--------------------------------+-------------------------------------------------------------------------------------------------------------------------+-------------+
| Hashing                        | C reference BLAKE3 and libcrypto EVP SHA-256 through a narrow FFI facade (§6.2, §6.3). The spike's pure BLAKE3 becomes  | J0a, J1     |
|                                | a reviewed test/interim implementation, not an oracle.                                                                  |             |
+--------------------------------+-------------------------------------------------------------------------------------------------------------------------+-------------+
| CBOR host values               | `.jolt` wire-node writer over the existing host-value vocabulary; tag-27 float64 frames; decode stays shared (§5.2).    | J1          |
+--------------------------------+-------------------------------------------------------------------------------------------------------------------------+-------------+
| Arrays, B-tree, display        | Stable mergesort `asort`; explicit strong-cache B-tree default; canonical set comparison (§5.3).                        | J1-J3       |
+--------------------------------+-------------------------------------------------------------------------------------------------------------------------+-------------+
| Files (`dao.jing.file`)        | Keep framing/recovery shared; random-access read/write/truncate/sync/close via Chez file APIs plus narrow POSIX FFI     | J4          |
|                                | where needed (V `file.cljc:159-259` shows RandomAccessFile use; the spike did not exercise it). Test partial writes,    |             |
|                                | truncation, replay/corruption, cross-host reopen.                                                                       |             |
|                                | J4a/J4c: `:jolt` arms over `dao.posix.file` (pread/pwrite loops, fstat, ftruncate, fsync, one close; s32BE prefix in    |             |
|                                | C). Jolt-only divergence (review F3): after a torn append or a failed fsync the jolt handle is fail-closed, refusing    |             |
|                                | every later append with an ex-info naming the path and the failed operation, while get keeps serving the in-memory map  |             |
|                                | and close closes once; a reopen's validated recovery decides what stands. The JVM, Node and Dart handles stay usable    |             |
|                                | after a failed write. A failed fsync is never retried: on Linux it may drop the dirty pages it could not write, so a    |             |
|                                | retry can answer success for data that never reached storage.                                                           |             |
+--------------------------------+-------------------------------------------------------------------------------------------------------------------------+-------------+
| Store (`dao.space.store.fs`)   | OS adapter, not slurp/spit emulation: exclusive locks, same-process duplicate-open tracking, complete temp writes,      | J4          |
|                                | fsync, atomic same-directory HEAD rename, directory-sync capability or refusal. Kill/restart, competing-process,        |             |
|                                | stale-temp and failure-injection fixtures gate completion.                                                              |             |
|                                | J4b: `:jolt` arms over `dao.posix.file` (native/jolt-file layout 2): a whole-file fcntl(F_SETLK) write lock,            |             |
|                                | close-on-exec, the JVM's and Dart's lock family; the journal's `:lock-kind` reads the `:clj` arm (`:os-lock`, correct   |             |
|                                | for fcntl, no edit); `directory-sync-supported?` is declared true on jolt, and a directory-sync error fails the         |             |
|                                | replacement (never flips the capability); the HEAD temp is O_EXCL, mode 0666 & ~umask; bounded reads classify, open     |             |
|                                | O_NONBLOCK and recheck by fstat; strict UTF-8 via `text.jolt`. Node/OS-lock mixed ownership is the known-gaps P2 row,   |             |
|                                | not claimed.                                                                                                            |             |
+--------------------------------+-------------------------------------------------------------------------------------------------------------------------+-------------+
| Signing (`yin.vm.linker.sign`) | libcrypto EVP Ed25519 with raw 32-byte seeds/keys, detached signatures and the exact existing message bytes             | J4          |
|                                | (`sign.cljc:69,90,127-197`). RFC 8032 and envelope vectors (`sign_test.cljc:29-35`, `sign_vectors.edn`), bidirectional  |             |
|                                | JVM/Node/Dart interop, tamper and malformed-length cases. Domain separation, constant-time provider verification,       |             |
|                                | fail-closed, key-file validation; OS randomness failure is an error; restricted key-file creation. No handwritten       |             |
|                                | Ed25519, no non-crypto PRNG, no secrets in argv.                                                                        |             |
|                                | J4d: on jolt a key file is created by one O_CREAT+O_EXCL open at mode 0600 (native/jolt-file layout 3), written whole,  |             |
|                                | fsynced and closed checked; an existing file is refused byte-identical; load-key's strict read refuses an absent,       |             |
|                                | malformed or mismatched file naming the path and never the seed.                                                        |             |
+--------------------------------+-------------------------------------------------------------------------------------------------------------------------+-------------+
| Main, wait/wake, stdin         | One state owner. Reader/signal mechanisms deposit data and nudge cadence; nothing evaluates from a callback. Exits,     | J3-J4       |
|                                | monotonic wakes, graceful shutdown, EOF while pending, custody flush.                                                   |             |
|                                | J4d: the jolt -main persists its flags in <node dir>/state.edn like the JVM (a bare start resumes, --reset forgets,     |             |
|                                | --no-state neither reads nor writes); a state file that cannot be read, a dangling link or invalid UTF-8 refuses        |             |
|                                | startup; custody journals close on the exit path.                                                                       |             |
+--------------------------------+-------------------------------------------------------------------------------------------------------------------------+-------------+
| Sockets                        | Native POSIX sockets through `jolt.socket.native` FFI and `jolt.io-poller` directly (UDP: S on macOS, J0b; TCP: R).    | J0b, J6, J7 |
|                                | The `jolt.socket` java.net shim is not a substrate for either transport (ruling 2, §13.4).                              |             |
+--------------------------------+-------------------------------------------------------------------------------------------------------------------------+-------------+
| UDP                            | Narrow nonblocking SOCK_DGRAM adapter under the landed datagram contract (§8).                                          | J0b, J7     |
+--------------------------------+-------------------------------------------------------------------------------------------------------------------------+-------------+
| WebSocket                      | Native RFC 6455 host adapter supplying the full host contract, CBOR subprotocol only (§9).                              | J6          |
+--------------------------------+-------------------------------------------------------------------------------------------------------------------------+-------------+

Unavailable features return their existing qualified
unsupported/not-found/failure forms, never success. Missing prerequisites for a
declared supported feature fail the run. Host-specific imports are guarded so
absent networking does not break local loading. Storage and signing are not
deferred out of the local milestone because the spike used in-memory state. The
spike's list of about 15 heavy namespaces is not a validated capability matrix;
J0a inventories the full standard-main and test closure.

### 5.1 Exact integer bit operations

V (r7): `integer/host.cljc:239-298` needs infinite two's-complement AND/OR/XOR,
exact left multiplication, floor right division, magnitude bit length and exact
radix output.

V: the dao.host integer override landed in `0d07e55d` over Chez exact
primitives (`jolt.scheme`); for in-range values, `big-carrier?` is host-defined
(owner Q1 version B, §13.5).

P (original arithmetic proposal; the landed override uses Chez primitives):

- AND/OR/XOR: take `w = 1 + max(bit-length(|a|), bit-length(|b|))`; form
  nonnegative residues modulo `2^w`; consume base-2 digits with `quot`/`rem`,
  apply the one-bit truth table, rebuild with exact powers; a result with bit
  `w-1` set has `2^w` subtracted. This models sign extension, including
  negatives and zero. A later base-`2^k` limb version may use small proven-safe
  bit operations only after passing the same tests.
- Magnitude length: repeated division or exact radix conversion, never a
  floating logarithm.
- Left shift: `a * 2^k` by exponentiation by squaring with promoting arithmetic.
- Right shift: floor division; take `q = quot(a, 2^k)` and decrement once when
  `a < 0` and the remainder is nonzero.
- Shift counts are never masked modulo 32/64. Caller count validation and
  host-limit behaviour are kept; huge counts never truncate silently. Carrier
  promotion/demotion follows the host integer namespace's public laws, including
  negative-zero/float distinctions.

Gates: JVM BigInteger and the shared integer corpus for `±(2^n-1)`, `±2^n`,
`±(2^n+1)` with n = 31, 32, 53, 63, 64, 100, 127, 1024; mixed signs; counts 0,
1, 63, 64, 100 and beyond operand length; seeded randomized differentials; De
Morgan, `x XOR x = 0`, `x AND -1 = x`, Python `~x = -(x+1)`, `x XOR -1 = ~x`,
negative floor shifts, ratios and division signs. Python `py/invert`
(`prelude.cljc:1507-1511`, r7 cite) calls `integer/bit-not`, which
`src/cljc/yin/vm/integer.cljc:348-350` (V) implements as two `host/sub` calls;
exercise that public path as well. Include exact REPL/Python arithmetic and
register scalar image hashes. Minimal upstream repros are submitted separately;
the local branch stays until a pinned upstream fix passes every vector, and
runtime upgrades never delete the guard tests.

### 5.2 Jing CBOR host values

V: floats are **tag 27 `dao.jing/float64` over eight big-endian bytes**, every
NaN made `7ff8000000000000` (`cbor.cljc:19-20`; also `:993-1012,1448-1452` per
r7). Native CBOR floats are rejection fixtures
(`test/dao/jing/cbor_conformance_test.cljc:127,415`, r7 cite). "Finishing
f16/f64 framing" therefore never means shortest-width CBOR float encoding. Keep
integral floats as floats, ±0, infinities and exact float64 bits.

The spike's seventh-value failure (S: `build/jolt-spike/parity.jolt.txt`,
overlay `boring.cljc:50`) is a wire-writer node-contract mismatch: the `:clj`
ByteBuffer path in `float-wire` produced a raw `byte[]`, which the overlay
walked as a sequence of nodes.

P: the `.jolt` wire writer accepts the same **host-value node vocabulary** as
`boring.cljc:58-132`: nil, booleans, strings, byte arrays, integers in `[-2^64,
2^64)`, vectors, maps, a `TaggedValue` equivalent for uninterpreted tags and
`tagged-literal` frames. Constructors return host values, not tagged tuples. A
byte array at any node position (float64 payloads, bignum magnitudes) is a byte
string. The writer emits exact integer heads, UTF-8 byte lengths, unsigned
bytewise map-key order, arrays, uninterpreted tags and tag-27 frames, and
handles big-endian 64-bit unsigned heads without narrowing to signed longs.
Decimal/ratio reduction, metadata frames, Unicode/surrogate refusal,
duplicate/equality-collapse rules and limits stay in shared `cbor`. Decode stays
shared above its host seams. Float byte extraction uses a host path with
explicit endian conversion and NaN normalization (ByteBuffer shims were only
partly exercised; otherwise a narrow Chez bytevector helper), never raw 64-bit
patterns through broken bigint bit functions. J1 verifies or supplies jolt
branches for `cbor.cljc:537` (`float64-from-bits`: `Long/parseUnsignedLong`,
`Double/longBitsToDouble`; V) and `float-fields:649-652`
(`Double/doubleToRawLongBits` and 64-bit bit operations). Encode success does
not establish decode or numeric-comparison fidelity. No EDN/pr-str substitute,
no third codec.

Gates reuse `test/resources/dao/jing/cbor-v1.json` with its README/errata,
`cbor-test`, `cbor-conformance-test`, `cbor-fixtures-test`,
`cbor-content-equality-test` and the stream CBOR tests. Compare encoded bytes
and decode/re-encode identity against JVM boring output and frozen vectors, not
digests alone; exercise both hash algorithms and tampered/noncanonical refusals.
Expected bytes are never regenerated to make jolt pass.

Current seam (V, `a0a49b8e`): `src/jolt/dao/stream/cbor/boring.jolt` is the
host-value writer; `src/jolt/dao/stream/cbor.jolt` is a whole-file override of
the stream codec facade, with the jolt require as its host seam. The shared
Jing codec stays shared. `4682c583` adds the drift guard and reviewed digests
(`src/jolt/dao/stream/cbor.reviewed.edn`); changes to the shared facade require
review and guard renewal. Libcrypto random bytes and wrapper layout 3 also
landed in J2; this does not close J4's file or authority integration gates.

### 5.3 Arrays, B-tree and display

- `asort` (P): stable mergesort over a temporary array, copied back into **the
  same input array**, which is returned. Keep the comparator's sign contract and
  equal-key original order. Test duplicates, cmp-equal non-identical values,
  array identity, sorted runs, random inputs and index datom order against JVM.
  Sorting is not a cure for host map/set iteration differences.
- B-tree: audit Sorted/Seqable/IFn, mutable fields, ref strategy and transient
  ownership. V: the default ref type is `:soft` on JVM and `:strong` on
  CLJS/CLJD (`src/cljc/dao/data/btree.cljc:38-43`; r7 cited `:44-50`, which has
  since drifted). Jolt gets an explicit `:strong` default like the other non-JVM
  hosts, not fake SoftReference behaviour. Re-run the v0.8.3 subseq/rsubseq
  repro; it is unresolved until tested at the pinned SHA.
- Display: set printing order (S) must not affect canonical bytes, addresses, id
  minting, persisted manifests or frontend profile identities. Compare sets
  canonically; keep assertions for deliberately specified REPL presentation;
  never sort user sets globally or replace guest equality to hide a runtime
  divergence. Extend the deterministic traversal tests of engine §E.5.

## 6. Native dependencies and toolchain policy

### 6.1 The toolchain file and mise-driven builds

Owner Q4 policy (settled): mise-driven vendored builds of the upstream **BLAKE3
C reference sources** and the **ANTLR4 C++ runtime**, each with license, pinned
source revision and hash in the tracked toolchain file
`jolt-toolchain.edn`, built with a mise-pinned CMake into
`build/native/<platform>/` by `bb build:jolt-native`, for macOS arm64 and Linux
x86_64. System/Homebrew OpenSSL libcrypto stays external, with a pre-digest path
and version check.

Host operating-system packages (owner, 2026-10-20, §13.1 "Linux packages"):
Linux means the **Debian family with apt**. The host packages the lane needs
(for example `libncurses-dev`, which the mise Chez source build links against:
on shiva it failed with "cannot find -lncurses" until installed) are declared
by apt name in `jolt-toolchain.edn` `:os-packages :debian`, each with the step
that needs it. Only packages a step has been shown to need are `:required`;
candidates are listed `:unverified` and not checked. On a host whose
`/etc/os-release` ID or ID_LIKE names debian, `bb bootstrap:jolt` checks the
required set with `dpkg-query` before mise builds anything, and fails naming
the missing packages and the exact `sudo apt-get install -y …` command. It
never runs sudo or apt itself. On macOS, Homebrew is assumed and not enforced.
Chez and CMake remain mise-provided on both platforms.

`jolt-toolchain.edn` (V; J0a, J1b and the fork re-pin) records:

- the full jolt SHA `b389f1a7fb553f63a3494d71f8fe3251790bf4d3` (§6.4), the owner
  fork origin `https://github.com/sonwh98/jolt` and recursive submodule SHAs;
- the Chez version actually resolved, and `JOLT_CHEZ` set explicitly;
- libblake3 and ANTLR4 C++ runtime source revisions and hashes; ANTLR tool and
  runtime both 4.13.2;
- the exact mise CMake pin (`4.4.4`), compiler
  versions and flags, and build output digests per platform;
- the expected libcrypto path and version per platform;
- the Debian apt packages the host needs (`:os-packages`), each with the step
  that needs it, and the macOS Homebrew assumption;
- any local jolt patches, declared and verified under the accepted §13.2
  policy; currently `:patches []` because the fork pin carries the Sorted fix.

Mise provisions Chez and CMake; the repo task compiles the vendored libraries
(there is no presumed mise libblake3 package). A mise git plugin for jolt is
unnecessary until proved: the repo task owns the jolt CLI clone under
`build/tools/jolt`. No namespace load searches arbitrary system libraries or
downloads anything, and the production gate never falls back silently. J0a
records the exact CMake version, revisions, hashes and output locations; this
document invents none.

V (r7): jolt's README (`:95-115`) documents threaded Chez 10.x, `JOLT_CHEZ`
precedence and OpenSSL/git prerequisites, and (`:130-164`) the deps aliases,
`-Srepro`, `-Spath`, `-Stree`. `bin/jolt` runs from the checked-in seed; `make
devboot` is optional (`Makefile:1901-1902`), and the Makefile references a
provisioned GCC bundle (`:111-113`). Assumption to verify at J0: the xPack
download/compiler selection; inventory its scripts before enabling it. Devboot
and `jolt build` record compiler/provisioning checksums, use the same Chez and
discard caches on runtime/source change. Source-mode execution is the baseline;
AOT packaging is an additional gate, never the fix for a semantic failure.

### 6.2 BLAKE3 over FFI

P: bind through `jolt.ffi/defcfn` and declared `:jolt/native` loader
configuration (R/V: `stdlib/jolt/ffi.clj:91-100,1664`;
`jolt-core/jolt/deps.clj:998-1146`). A tiny reviewed C wrapper over
init/update/finalize hides the hasher struct's size and alignment: conceptually
`(input pointer, size_t length, output pointer[32]) -> status`. It implements no
hash algorithm. Pointer/size/status types are explicit; a confined FFI arena
copies bytes in and out; zero-length and embedded-NUL input are accepted; memory
is freed on every path. Never C-string arguments for binary data, never C
pointers as guest values. Direct Chez `foreign-procedure` is only an
encapsulated fallback if `defcfn` cannot express the binding. Start with the
portable C implementation; SIMD is a separately tested optimization.

Checks: frozen official vectors and the existing JVM implementation are primary
(`test/dao/jing/hash_registry_contract_test.cljc`,
`test/resources/dao/jing/blake3-vectors.edn`, `digest-table.edn`, r7 cites). Add
empty, `abc`, all byte values, UTF-8, 63/64/65 and 1023/1024/1025 bytes and
multi-chunk inputs; compare C, the pure interim and JVM, then CLJS/CLJD corpus
hashes. A native loading failure fails the production task.

### 6.3 libcrypto: SHA-256, Ed25519, SHA-1, randomness

P: libcrypto EVP SHA-256 through public EVP APIs in a small wrapper with
explicit errors and lifetimes. Known vectors, binary/string variants, large
inputs and full `:sha256` content-address registry behaviour. Never alias SHA to
BLAKE3 or return a stub. The same vetted provider supplies Ed25519 (§5), the
WebSocket handshake's SHA-1 (§9) and OS-backed randomness.

OpenSSL provenance (R, Q4 platform qualification). jolt's own conformance code
records that on macOS a bare source path picks up whatever libcrypto the loader
finds, which is Apple's BoringSSL, and that it **aborts the process inside
`EVP_Digest`** (`test/conformance/libs/common.clj:116-120`, V);
`CHANGELOG.md:11264-11274` records global-symbol re-promotion and the
scoped-loader correction (r7 cite). Therefore:

- macOS arm64: **Homebrew `openssl@3` only**, never Apple's libcrypto;
- Linux x86_64: declared system **`libcrypto.so.3`**, installed with apt on
  the Debian family (owner 2026-10-20) and declared in `:os-packages`; the
  path and version check above is retained, so an apt upgrade that moves or
  changes it fails until requalified;
- absolute paths in a per-OS `:jolt/native` declaration, with the wrapper
  explicitly linked (install name/rpath); EVP is never resolved against the
  process-global namespace.

This chooses safe members of the Q4 system/Homebrew policy; it does not change
the policy. Unavailability fails qualification. Homebrew/system updates can
invalidate the recorded path or version and must fail until requalified.

J0a gate (P): before any digest or sign call the wrapper reports
`OpenSSL_version_num()` and the `dladdr` provenance of the resolved
`EVP_DigestInit_ex`, compared with the expected path and version. Qualification
and wrong-library-first negative tests run in child processes so an abort cannot
erase the report. Known-answer tests follow. jolt's first-party jolt-crypto
exists (R: `.github/workflows/release.yml:642`) but is unaudited; reusing it
would need the same tests and a reviewed simplification, not a second unverified
crypto path.

Collect-safety (P): long BLAKE3/SHA calls are `:blocking` with owned
pointer/length arguments, not `:string`. J0a shows a full collection on another
thread completing while a long hash call is active. Collect-safety does not
prevent fiber carrier pinning; §7.5 covers scheduling.

### 6.4 Pins and the upgrade gate (owner Q5)

Owner: "record the mob's answer", then "opus when needed". Jolt is pinned by
full SHA in `jolt-toolchain.edn`; a suitable release-tagged SHA is preferred;
main remains the upstream development source; nothing updates silently. The
owner or orchestrator triggers the proposed `bb qualify:jolt-upgrade` when an
upstream fix we need lands, when the resolved Chez version changes, or when the
pin is older than about 30 days. Age triggers evaluation, not promotion; a
failing candidate leaves the last passing pin.

The gate runs on macOS arm64 and Linux x86_64: clean tracked load and resolved
paths; relevant fast/all/slow tests with counts; J1 integer/CBOR/hash fixtures;
shared VM boundary traces; J0b UDP and J0c parser/GC/cache/lifetime regressions;
native-library provenance; J6/J7 peers once they exist. It records candidate,
base and submodule SHAs, exact Chez, patches, native versions, commands, results
and rollback coordinates. No unavailable test passes silently. Early pins
qualify only for the implemented milestone; J8 promotion needs every parity
gate. No numeric JVM-ratio budget exists before J8 evidence.

Historical pin and tag (W7; §13.4 ruling 4, 2026-10-20). V (read-only git
in the local checkout, 2026-10-20): `96d7b866` is the checkout's `main`/`origin/main` head,
committed 2026-10-19, carries **no tag**, and `git describe --tags` answers
`vnightly-16-g96d7b866`; the nearest release tag the checkout knows is
`v0.8.23` (`a0ac71c3`, 2026-10-17), with `vnightly` (`5ae108e5`, 2026-10-18) a
moving nightly tag. The checkout's tag list is only what it has fetched. All S
and J0b evidence describes `96d7b866`. **J0a keeps `96d7b866`** and records it
in `jolt-toolchain.edn` with "tag: none; nearest release tag v0.8.23
(a0ac71c3); describe vnightly-16-g96d7b866". No S claim is re-established.
From the first run of `qualify:jolt-upgrade` onward, Q5 governs as the owner
stated it: a **suitable** release-tagged SHA is preferred, and an untagged SHA
is admissible when it carries a needed upstream fix and is explicitly qualified
through the gate (astra A5). No rule here mandates the newest release. A re-pin
before that first gate run happens only under the reversal condition in §13.4.

Pin and tag (owner decision, 2026-10-22; landed `63301597`). The current
toolchain pins the owner fork `https://github.com/sonwh98/jolt` at
`697b91eefabf3e964acea787cbc9cb1b6b85a28c`
(2026-10-22T15:11:38+07:00; tag none; describe `vnightly-16-g697b91ee`;
nearest release `v0.8.23`, `a0ac71c3f638fe55158bd01b4ecbb0dd3d7545e5`).
Fork PR `sonwh98/jolt#2` carries the Sorted fix `7b7100c1` and regenerated
seeds `697b91ee` on upstream `75c8b610`; origin/main head `d69e5eaa`
(PR #2 merge, tree identical to `697b91ee`); `vnightly` is now `cd4b7743`.
The former `0001-sorted-user-types` patch against `96d7b866` is retired;
`:patches []` applies nothing and re-mints nothing. The patch mechanism
(`patches/jolt/<sha>/`, `apply-patches!` in `src/dev/jolt_bootstrap.clj`,
`:patches` in `jolt-toolchain.edn`) remains for future patches. Seven
submodule SHAs are unchanged. Seed re-mint with Chez 10.4.1 is deterministic
across macOS and Linux (Linux digest record `e7a39cb9`; fork seed commit
`697b91ee`). Historical S and J0b evidence still describes `96d7b866` and is
not re-established by this pin move.

Re-pin 2026-10-24 (owner: "the JVM should be canonical. the other divergent
hosts should be patched"; jolt-lang is patched through the fork, the same
explicit exception as 2026-10-22). The toolchain now pins
`b389f1a7fb553f63a3494d71f8fe3251790bf4d3` (2026-10-24T13:55:46+07:00; tag
none; describe `vnightly-17-gb389f1a7`), fork branch `assoc-deftype-refusal`,
one commit on `697b91ee`: `assoc` on a deftype that is not Associative throws
`ClassCastException` naming the class, as on the JVM, where it had answered a
fresh instance (`host/chez/records-coll.ss`, the record arm of `jolt-assoc1`
now requires `jrec-record?`). The seed re-mint reaches its byte-fixpoint with
no seed change; submodule SHAs are unchanged; `:patches []` still applies
nothing. The upstream report text is in
`collab/1792824446211-engineer-canonical-adjudication-fixes.claude-opus-5-5.findings.md`
(the owner submits it).

This re-pin precedes the first `qualify:jolt-upgrade` run under an explicit
owner ruling (§13.5). It was requalified by the manual gate in
`collab/1792692000000-architect-jolt-pin-fork-review.claude-fable-5-1.findings.md`
section 6: local macOS bootstrap and negative gates passed; Linux
requalification is UNRUN until that checklist passes. Q5's suitable
release-tag preference and explicit qualification still apply; the automated
upgrade task remains proposed.

Chez status (V): `mise.toml` pins Chez `10.4.1` (J0a, `9f1f9144`); default 3
was accepted (§13.2). Every result records the resolved version and path;
a Chez change remains an upgrade-gate trigger.

### 6.5 Commands

Spike repro (S; diagnostic only, never the production lane):

```sh
cd /Users/sto/workspace/datomworld
export JOLT_CHEZ="$HOME/.local/share/mise/installs/chezscheme/10.4.1/bin/scheme"
build/jolt-spike/climb.sh yin.repl
build/jolt-spike/jolt/bin/jolt -Sdeps '{:paths ["build/jolt-spike/overlay" "build/jolt-spike" "src/cljc" "src/clj"]}' build/jolt-spike/entry.clj
build/jolt-spike/jolt/bin/jolt -Sdeps '{:paths ["build/jolt-spike/overlay" "build/jolt-spike" "src/cljc" "src/clj"]}' build/jolt-spike/parity.clj
```

Proposed supported repro (P; none of these tasks or aliases exist yet):

```sh
mise install chezscheme cmake
bb bootstrap:jolt
. build/tools/jolt-env.sh        # written by bootstrap with the resolved mise path
bb build:jolt-native
build/tools/jolt/bin/jolt -Srepro -M:jolt:jolt-yin-repl
build/tools/jolt/bin/jolt -Srepro -M:jolt -m yin.demo
bb test:jolt
bb test:all:jolt
bb test:sub:jolt yin.vm
bb test:changed:jolt
```

J0 confirms alias order and the subsystem spelling used by list output. Tests
run with no overlay directory present; a clean checkout and a clean-cache repeat
are mandatory. Every result records OS/arch, git and submodule SHAs, Chez,
native compiler and libraries, aliases, resolved classpath, fixture revision and
test counts. Local-build packages carry notices and relative native-library
discovery, with no dependence on an author's build directory.

## 7. The parser: in-process ANTLR4 C++ over FFI

### 7.1 Decision and history

Owner Q3: "Q3 is not relevant if we use FFI with antlr C++ generated parser"; on
fallback, "no fall back". The parser is in-process ANTLR4 C++ over FFI. The J0c
probe is required and early. If it fails, the orchestrator returns to the owner
with the failure evidence; there is no pre-committed or automatic fallback. J5
completes the same implementation.

Decision history only: JVM, Node and Dart AOT parser workers (reusing existing
targets, with process isolation) were set aside by the owner's selection and are
not on the commissioning path. Existing hosts remain conformance oracles. A
Clojure-only catalog is a bootstrap milestone (J0/J3), never a substitute for
Python after a failed probe. The removed legacy Python parser and CPython's AST
are not CST/profile-equivalent shortcuts (`yang.antlr.md:3229,4004-4005`, r7
cite).

V: `frontends.cljc:11-17` requires the Clojure, PHP and Python frontends
together, including `yang.python.antlr.parser`; r7 reports that
`parser.cljc:19-25` imports generated Java recognizers and
`repl_probe.cljc:27-30` also requires parser/CST, while
`yang/python/antlr/repl.cljc:26-32` is portable lowering. Removing one catalog
require does not solve the graph.

### 7.2 A new generation target

R: ANTLR4 supports `-Dlanguage=Cpp`; its official target list has C++ and no C
target ([4.13.2 C++
target](https://github.com/antlr/antlr4/blob/4.13.2/doc/cpp-target.md), [4.13.2
target list](https://github.com/antlr/antlr4/blob/4.13.2/README.md)). The
runtime is C++17 with CMake builds. This is ANTLR4 C++, not the ANTLR3 C
runtime.

R: `deps.edn:12-14,74-77` pins the tool and Java runtime to 4.13.2;
`yang.antlr.md:530-534`, `antlr/python3/manifest.edn` and
`src/dev/yang_antlr_gen.clj` generate Java, JavaScript and Dart only. C++ is a
**new generation and export target**, not an existing port to enable. Keep the
same pinned grammar revision and 4.13.2 tool; pin the C++ runtime source to the
matching release; record source digests, flags, compiler/CMake versions,
architecture, generated output hashes, helper patches and linkage. A new target
never authorizes changing grammar or profile identity to hide a parity failure.

P: build the runtime, the generated Python parser, the target-specific
lexer/parser helpers and the wrapper for both platforms. Port indentation,
synthetic NEWLINE/DEDENT positioning and predicate helpers from the pinned
grammar package; the manifest already patches these for existing targets, and
selecting Cpp alone does not make Python work. Extend `gen:antlr` and the
manifest with a `:cpp` target, the positioned NEWLINE/DEDENT patches, helper
provenance and generated-file digests. Choose static runtime linkage inside the
wrapper or a separately packaged runtime explicitly; neither yields a single
self-contained executable. Generation may still need the JVM build tool;
deployed parsing needs neither JVM nor Node.

### 7.3 Wrapper ABI and the shared exporter

P: `src/jolt/yang/antlr/cst.jolt` implements the existing host surface:
`make-worker`, `parse-unit`, `parse-transform`, `utf8-bytes`, `first-failure`. R
(r7): `src/cljc/yang/antlr/export.cljc:1-14,84-160` owns packet construction;
`src/clj/yang/antlr/cst.clj:78-154,168-221` supplies host-tree access and
failure information; `yang.antlr.md:655-661` requires identical packet values.
**Neither the exporter nor the Jing encoder is ported to C++.** The jolt facade
calls the shared `export/export-nodes`, `export/result`, `export/failure-kind`
and error-span helpers.

The `extern "C"` entry takes source UTF-8 pointer and length plus host-only
mode/profile inputs, and returns a status and one bounded private fixed-layout
marshalling buffer:

- preorder token and rule records: symbolic token type or rule name/index plus
  table, code-point start/stop, UTF-8 text, child count, context start;
- raw listener errors: message, line/column, offending-token start/stop or lexer
  token-start/input index;
- first-failure evidence: error kind, offending and last-buffered indices,
  lexical marker, real non-EOF token index/start/stop.

Fields have explicit widths, lengths and offsets. Arithmetic, bounds and
topology are checked before an `ops` view is handed to shared export code.
Spans, synthetic tokens and packets are normalized in shared code, not C++.

This layout is process-local marshalling like a sockaddr, not a value codec; it
never enters a stream or crosses a trust domain. Only packet values from the
jolt/shared exporter enter source/CST streams, and canonical encoding stays
Jing. Records are copied into host-owned values and the native buffer is freed
through its matching wrapper entry on every path. No pointer enters guest
values, packets, stores or continuations. Every exported entry catches
`std::exception` and `catch (...)` and returns an explicit bounded error, with a
nonallocating failure status where needed. No C++ class, layout or exception
crosses the C boundary.

### 7.4 Per-worker caches

P: an opaque native worker handle, owned only by the jolt host facade.
`make-worker` creates it; its parser stage owns it and disposes it at
close/reset, including temporary convenience-call workers. Mutable DFA vectors
and PredictionContextCaches belong to the worker: worker-owned
LexerATNSimulator/ParserATNSimulator instances replace the generated defaults
before recognition, with correct cleanup. Per-call parser, token and tree state
dies after marshalling; caches may survive calls. Immutable generated tables may
be shared; mutable caches may not. A worker is used by one thread at a time;
thread-local caches alone do not isolate two workers on one thread.

R: `yang.antlr.md:551-569` requires isolation and JVM `cst.clj:43-71,133-150`
implements it. The [4.13.2 C++ codegen
template](https://raw.githubusercontent.com/antlr/antlr4/4.13.2/tool/resources/org/antlr/v4/tool/templates/codegen/Cpp/Cpp.stg)
(read, not run) wires static DFA/context caches into the generated simulators.
J0c audits the generated code and helper caches, interleaves two workers on one
thread, runs independent workers on separate threads, requires packets equal to
isolated runs, and instruments the generated static caches to show no growth
during recognition after initialization. Fresh parser construction alone is not
proof.

### 7.5 Synchronous surface and collect-safe calls

The jolt `yang.antlr.cst` surface is **synchronous, as on every other host**
(W5). V: `repl_probe` calls `cst/first-failure` inline
(`src/cljc/yang/python/antlr/repl_probe.cljc:163,168`), and per r7
`parse-transform` runs `parse-unit` inside `packet/transform`. A jolt
completeness probe must never become a pending driver state, because shell
behaviour would then differ from the other three hosts.

P: parse and completeness-probe entries are `:blocking` with arena/pointer
arguments only (R: `jolt/ffi.clj:1502-1522` describes collect-safety and
excludes `:string` with `:blocking`; `io_poller.clj:5-20` describes fiber
carrier pinning). The rule:

- the jolt shell driver runs on a plain, non-fiber host thread and calls the
  `:blocking` entry directly; collect-safety already covers GC;
- only a fiber caller hands off to an owned non-fiber parser thread, and that
  handoff blocks the calling thread until the result returns; it never
  introduces a new pending state;
- REPL state stays driver-owned. Native worker lifecycle is required; subprocess
  lifecycle is not.

J0c and J5 assert probe and pending sequences identical to the JVM shell tests,
and that a full collection started on another thread completes while a long
parse is active, with unrelated-thread progress.

### 7.6 Bounded recursion and crash blast radius

J0c records the actual thread and stack configuration on both platforms, not an
assumed stack size. Child-process stress tests find a conservative safe
nesting/resource budget for that exact thread and build across bracket,
indentation and other recursive grammar shapes. J5 enforces a pre-parse bound
with the existing qualified frontend failure outcomes, tested below, at and
above the bound. Bracket counting alone does not bound every recursion path;
reviewed guards go before unsafe recursion where needed. If safe enforcement
cannot be shown, feasibility fails and goes back to the owner.

Enforced (J5, known-gaps row 59, glm-5.3 ruling `1793096000000` §2). The C++
wrapper refuses before any ANTLR object exists, at the top of
`jolt_parser_export` (`native/jolt-parser/jolt_parser.cpp`, "THE BUDGET"):

- input: more than 4 MiB of source is status -6 (`input <measured> <bound>`);
  `yang.antlr.cst` mirrors the check before the FFI call;
- nesting: one flat O(bytes), O(1)-space prescan charges each recursive
  carrier of the pinned grammar one level and refuses when the worst
  simultaneous sum exceeds the depth bound, status -7
  (`nesting <measured> <bound>`). Strings and comments are skipped; explicit
  line joins are followed.

| carrier | counted                                                            |
|---------|--------------------------------------------------------------------|
| bracket | `(` `[` `{` open depth                                             |
| unary   | a run of `-` `+` `~` `not` in operand position                     |
| power   | `**` per logical line                                              |
| ternary | `if` after an operand, per logical line                            |
| lambda  | `lambda` per logical line                                          |
| indent  | INDENT levels from leading whitespace (tabs to 8), counted, never inferred from size |

Derivation: the shallowest r5 crash bracket on the 512 KiB macOS jolt thread
is 512 levels (call, unary; parens/list 544), about 1 KiB a level at the
worst per-level cost, which every carrier is charged. `N_depth` = 256 is a 2x
margin under it at the 512 KiB basis; a thread with a smaller stack (the
production `pthread_get_stacksize_np`/`pthread_getattr_np` read) clamps the
bound down in proportion, never up. Time is derived, not interrupted: the
worst at-bound legal parse on macOS arm64 is unary at 256 (about 3.5 s; 4 MiB
of comment 1.6 s), Linux in the J5 findings. A refusal is operational:
`{:diagnostic :parser-budget :axis :input|:nesting :bound n :measured m
:lang :yang/python}` through the `:unavailable-parser` channel, thrown at the
parse stage, never a syntax error, no partial packet, the session survives.
The JVM, Node and Dart parsers have no such bound: an above-bound source the
JVM parses is refused on jolt (a recorded divergence, not a parity pin).

Blast radius. Catches contain catchable C++ exceptions only. An access
violation, abort, stack exhaustion or fatal runtime/parser bug takes down the
whole jolt process. A timeout does not make native termination safe, stack
faults are not assumed catchable, and no asynchronous cancellation of an
arbitrary native call is promised. Process crash isolation is documented as
absent; there is no worker-restart acceptance claim. Performance is unmeasured:
the native tree is still marshalled, so no speed advantage is claimed.

### 7.7 Packaging and licensing: local builds only

R/V: `antlr/python3/manifest.edn:127-144` marks the Java, JavaScript and Dart
helper provenance UNRESOLVED and sets `:generated-artifacts :none` with
redistribution blocked until it is resolved (V); `yang.antlr.md:617-621`
requires that gate (r7 cite). Q4 authorizes the pinned build policy, not a
licensing waiver, and runtime licensing grants no helper rights. J5 packaging is
**local-build only**: no prebuilt generated-parser/helper bundle is released
until provenance is resolved and the manifest permits it. J0c records whether
C++ helpers exist at the pinned grammar revision, with digests and notices (not
yet verified). If none exist, any hand-port is disclosed and reviewed against
the Java helper behaviour; it is a helper port, not a handwritten parser.

### 7.8 J0c probe and J5 gate

Current state (V): `9fbee557` landed the C++ parser probe only. Node/Dart
oracle comparison and restricted lowering comparison remain open in
`docs/agents/known-gaps.md`; the owner split these into J2b on 2026-10-22
(§13.5). Both must close before J5 acceptance. The gates below remain intact.

J0c (P, required, both platforms):

- generate the actual pinned Python grammar with Cpp, build and load wrapper and
  runtime;
- parse a fixed set: arithmetic, nested indentation/dedent, empty input/EOF,
  non-ASCII and astral characters, malformed syntax, incomplete multiline input;
- marshal into the jolt adapter, run the shared exporter, and require canonical
  CST/diagnostic packet equality with the JVM/Node/Dart oracle for identical
  source/unit/profile, then identical completeness-probe results;
- lowering comparison restricted to a fixed corpus with no integer beyond 64
  bits and no floats, so a J1 numeric gap cannot masquerade as parser
  infeasibility (W8); any J1-attributable diff is reported separately from
  parser feasibility;
- observe C++ token-buffer behaviour rather than assume Java equivalence (JVM
  lazy-buffer NoViableAlt handling differs from JS/Dart, `cst.clj:168-221`);
  require identical first-failure (`:end`/`:early`/nil) and probe results for
  open blocks, brackets, triple quotes and an early error under an open bracket;
- embedded NUL and invalid input policy, bounded-response failure, injected C++
  exceptions, repeated parse/free cycles, sanitizer leak/lifetime checks; no
  exception or pointer escapes, no failed parse publishes a partial successful
  packet;
- two-worker/cache isolation (§7.4), collect-safety and thread/stack evidence
  (§7.5, §7.6), helper provenance and generated digests;
- record library versions, target/helper hashes, commands, result diffs, whether
  unrelated jolt work progresses during a parse, and how limits are enforced.
  Unsupported interruption is disclosed, not passed as cancellation. A toy
  grammar does not count.

J0c start criterion (W8): J0c starts once the minimum J0a baseline exists,
namely the pinned jolt and Chez are bootstrapped, the `:jolt` alias loads
`yang.antlr.export` and `yang.antlr.packet` without overlays, and one `defcfn
:blocking` smoke call passes. It need not wait for unrelated UDP or codec work.
J0c compares exporter packet values only; J1 owns broad Jing byte/hash parity
and J5 full integration.

J5 (P, required): extend the probe to the full existing ANTLR/frontend corpus
and standard `(lang :yang/python)` acceptance: byte-span, diagnostic and profile
parity; complete/incomplete/error probes; reset/session behaviour and
malformed-input limits; worker-cache and reset isolation; local-built package
loading from a clean deployment with no JVM/Node process;
missing/incompatible-library refusals; deterministic repeats; lifetime and
exception fault injection from an external harness so a crash fails the test
rather than erasing its report. Acceptance: `1 + 2`, definitions across
submissions, multiline functions, `print`, exceptions, arbitrary integer shifts,
floats and reset match the existing Python REPL suite; PHP and Clojure keep
working; syntax-error spans and profile identities match the oracle. A missing
or incompatible library fails prerequisites for the declared lane and yields the
existing qualified unavailable-parser result at the API seam, never another
parser. Any failure blocks the gate and goes to the owner; no fallback.

The parser interpreter composes over in-memory source/CST streams with canonical
data and identity correlation; the C wrapper is private linkage behind it, and
no native pointer or callback enters those media. Python lowering, prelude,
primitives and evaluation stay in jolt.

## 8. UDP

### 8.1 The landed datagram contract (owner Q7)

Owner: "keep landed contract". V: `src/cljc/dao/stream/datagram.cljc:25-33`
makes a send failure a clean `:dao.stream/transport-error` (nothing sent) plus a
`:dao.stream.datagram/send-failed` lifecycle event, and excludes `:full` (the
layer holds no outbound queue) and `:refused`; `dao.stream.datagram.md:141`
agrees (r7 cite). So EAGAIN/EWOULDBLOCK, and ENOBUFS on macOS, produce
transport-error plus send-failed: never `:full`, a hidden queue, a parked
`append!` or a retry. Attribution: the owner's original instruction was only
"yes, have the architect add the UDP probe to J0"; the earlier `:full` mapping
came from the orchestrator and is withdrawn.

### 8.2 Groundwork (R)

`build/jolt-spike/jolt/stdlib/jolt/socket/native.clj:190-208,221-256` declares
socket/bind/setsockopt/getsockname/close/poll and ioctl/fcntl through
`ffi/defcfn`, capturing native errors; `:457-537` supplies sockaddr helpers. Its
`sock-stream` constant (`:67,127`) and constructor (`:434`) target TCP. No
SOCK_DGRAM constant, sendto/recvfrom/recvmsg/sendmsg binding or DatagramSocket
was found. `stdlib/jolt/socket.clj:12-23` (V) documents a java.net-style
nonblocking TCP layer over `jolt.io-poller` that reads a recv error as EOF;
`io_poller.clj:120,626,643,921` provides `nonblock!`, `cancel!`, `forget!`,
`wait-ready`. Reuse the poller, not the TCP byte-stream/EOF semantics: a
zero-byte UDP packet is a packet, and a receive error is an error. Windows
declarations (`native.clj:221-233`, WSAPoll) are evidence only.

P: add and verify SOCK_DGRAM, EMSGSIZE and the macOS ENOBUFS constants from
target headers, and narrow sendto/recvfrom bindings with target-correct sizes,
sockaddr lengths, captured errno and buffer ownership. Reuse address,
nonblocking and cleanup helpers; never a helper that hardcodes SOCK_STREAM.

### 8.3 J0b probe (owner addition, 2026-10-19)

Separate macOS arm64 and Linux x86_64 records are required. It establishes raw
transport feasibility only, not datagram/DHT conformance.

1. Bind nonblocking SOCK_DGRAM loopback sockets through FFI and read back the
   ephemeral port. Send/receive via sendto/recvfrom; compare payload bytes and
   source address for zero-length, embedded-NUL, distinct consecutive and
   limit-sized packets. One successful send submits one whole packet; one
   successful receive consumes at most one packet. A short send is an error,
   never success and never permission to send the rest as a second packet.
2. N = 1200, the landed default (V: `datagram.cljc:55-60`), plus one configured
   larger N below the observed platform send maximum; receive buffers hold at
   least N+1 bytes. Record platform limits and sysctls rather than assume them.
   Verify SOCK_DGRAM and EMSGSIZE from headers (review-reported 2 on both,
   EMSGSIZE 40 macOS / 90 Linux; R until checked). Above-platform-limit send
   failure (EMSGSIZE) is tested separately and is an error, not a truncation.
   Send N, N+1 and greater than the receive buffer; any length over N surfaces
   an oversize/truncation error, never a successful prefix. If recvmsg/MSG_TRUNC
   is used instead, bind and verify it on both platforms.
3. Send EAGAIN/EWOULDBLOCK (and macOS ENOBUFS) appear as a distinct raw result,
   e.g. `:would-block`, with errno and no accepted packet. At the datagram
   writer surface the outcome is the landed clean transport-error with reason
   plus the send-failed event (§8.1). Where loopback cannot induce EAGAIN, use
   deterministic native-seam injection, labelled separately from natural
   observations, and also exercise real successful syscalls.
4. An empty receive waits through the poller, yielding the fiber instead of
   spinning or blocking the scheduler; another fiber progresses; a packet wakes
   the waiter. Send-side readiness waits and harness retries are probe
   observations only, never synchronous `append!` behaviour or an adapter queue.
   Test cancellation, close and cleanup.
5. Record toolchain/runtime SHA, architecture, commands, packet lengths and
   counts, error/outcome traces, poller wakeup evidence and cleanup results. An
   unavailable machine is an incomplete gate, not a pass. The Linux x86_64
   machine is shiva (§10.2).

### 8.4 J7 integration

J7 integrates the probed transport through the datagram host seam:
bound/send-failed/closed events, the maximum-datagram policy, IPv4/IPv6 and the
host-composed deposit medium. No reliability or queue is added beneath it. The
datagram-contract and DHT/linker composition are the main effort:
datagram/address/size/lifecycle suites, jolt legs in the DHT process and
signed-head tests, bind/send failures and shutdown. P2P/DHT is outside the local
milestone and is a parity gate, not a hidden skip.

## 9. Native WebSocket

### 9.1 Decisions

Owner Q6: "native websocket, no sidecar". Owner on W2: "CBOR-only websocket, no
Transit port": the jolt WebSocket composes only the `dao.stream.cbor` binary
subprotocol. No helper process is a supported deployment. `dao.stream.ws` keeps
all public semantics, outcomes, bounds, custody and lifecycle; the jolt host
adapter owns only the socket, the HTTP upgrade and RFC 6455 framing. The earlier
recommendation to permit a JVM sidecar is decision history only.

### 9.2 The host contract (W1)

V: `dao.stream.ws` knows no WebSocket library; a host adapter supplies small
synchronous functions and socket callbacks enter only through an adapter map
(`src/cljc/dao/stream/ws.cljc:1-15`). A jolt build supplies the whole host
contract, not only the per-connection map:

+------------------------------------------------------------------+-----------------------------------------------------------------------------------------------------+
| Part                                                             | Contract                                                                                            |
+==================================================================+=====================================================================================================+
| Host value                                                       | `{:connect! :bind! :unbind!}` (V: `yin.repl.host` docstring and `websocket` at                      |
|                                                                  | `host.cljc:4-14,20-37`; `adapter?`/`binder?` at `host/common.cljc:28-37`).                          |
+------------------------------------------------------------------+-----------------------------------------------------------------------------------------------------+
| `:connect!`                                                      | `(fn [descriptor adapter] ...)` per `make-attacher`: starts connecting, never waits, synchronously  |
|                                                                  | returns the per-connection `{:send! :close!}` map, with `:queued-bytes` where the host can report   |
|                                                                  | exact backlog.                                                                                      |
+------------------------------------------------------------------+-----------------------------------------------------------------------------------------------------+
| Adapter callbacks                                                | The adapter map the host must call (V: `ws.cljc:493-501`): `:opened!`, `:message!`, `:binary!`,     |
|                                                                  | `:closed!`, `:too-large!`, `:error!`, plus `:ws/codec` and `:ws/max-frame-bytes`.                   |
+------------------------------------------------------------------+-----------------------------------------------------------------------------------------------------+
| `:bind!`                                                         | `(fn [{:keys [endpoint bind-host bind-port path accept! deposit!]}] ...)`: binds a listener, routes |
|                                                                  | each upgrade through `(accept! request-path socket now)` with its own clock reading, reports every  |
|                                                                  | lifecycle fact through `deposit!` as plain data, including a bind-succeeded event with the actual   |
|                                                                  | port. Server seams carry `:ws/subprotocol` (`jvm.clj:347-359`, review cite).                        |
+------------------------------------------------------------------+-----------------------------------------------------------------------------------------------------+
| `:unbind!`                                                       | `(fn [resources deposit!] ...)`: releases the listener; its close completion deposits `:stopped`.   |
+------------------------------------------------------------------+-----------------------------------------------------------------------------------------------------+
| Listener behaviour (from `jvm.clj:66-120,410-471`, review cites) | The client offers exactly one subprotocol; the listener picks by endpoint order; a mismatch is a    |
|                                                                  | failed handshake, never a downgrade. HTTP 400 refusals `dao.stream/subprotocol-required` and        |
|                                                                  | `websocket-required`. Canonical request-target path (percent-decoding, dot segments) is host code.  |
+------------------------------------------------------------------+-----------------------------------------------------------------------------------------------------+
| Existing behaviours to reproduce                                 | A send on a failed connection answers `{:dao.stream/outcome :dao.stream/closed}`, never the         |
|                                                                  | retryable `false`/`full` (V: `jvm.clj:231-239`). The JVM client cannot send 1009 (V:                |
|                                                                  | `unsendable-close-codes`, `jvm.clj:134-138`), so its peers see an abort; the jolt seam sends every  |
|                                                                  | code the transport asks for, 1009 included, and never imitates another host's limitation (ruling 3, |
|                                                                  | §13.4; the per-pair expectations are in §9.6).                                                      |
+------------------------------------------------------------------+-----------------------------------------------------------------------------------------------------+

Send-result semantics (R, r7: `ws.cljc:170-218,505-523`): immediate acceptance
or nonacceptance truth; exact host outbound backlog through `:queued-bytes` so
shared high-water/full and hard-limit/closed decisions work. WebSocket's `full`
is permitted; UDP's is excluded. Partial TCP writes go to bounded
connection-owned pending buffers without duplicating a frame or reordering
messages; readiness wakes draining work, never a blocked `append!`. Backlog
accounting changes correctly on drain, error and close; protocol/socket errors
and close completion travel through the existing plain-data lifecycle entries.
Abnormal termination is never reported as a clean WebSocket close.

### 9.3 Scope and cost (W3)

The JVM host adapter is 471 lines (V: `src/clj/dao/stream/ws/jvm.clj`), but that
is glue over `java.net.http` and http-kit, which do the upgrade, framing,
masking, fragmentation and ping/pong. The 471-line figure in the mob synthesis
is therefore not a cost estimate for a native host. A native jolt host writes:

- an HTTP/1.1 upgrade client and server: request line, case-insensitive headers,
  `Connection` token lists, `Sec-WebSocket-Version: 13`, 426 on version
  mismatch, the 400 refusals above;
- RFC 6455 framing: text/binary frames, masking (client keys unpredictable, RFC
  6455 §5.3, so an entropy source), fragmentation, ping/pong and control frames,
  configured message/frame bounds enforced during assembly;
- a nonblocking partial-write queue with exact `:queued-bytes`;
- the close state machine, including close races and abnormal EOF;
- the full host contract of §9.2, with per-connection ownership and no callback
  driving guest evaluation.

Crypto reuse, no new crypto: the handshake's SHA-1 through the qualified
libcrypto facade (§6.3; not a Jing content hash), base64 through
`dao.stream.base64`, masking keys from libcrypto randomness or jolt's native
SecureRandom.

Substrate (ruled, §13.4 ruling 2): the jolt WebSocket host adapter is built on
`jolt.socket.native` (FFI bindings) plus `jolt.io-poller` (readiness) directly,
as J0b was, and does not use the `jolt.socket` java.net shim. V: the shim's
documented divergence reads a recv error as EOF (`socket.clj:12-23`), and its
write path loops over `c-send` behind `poller/wait-ready fd :write`
(`socket.clj:349,719-724`), so a write through it waits until the socket drains.
`dao.stream` forbids a waiting operation: `append!` must answer `full` or `ok`
now, from an exact `:queued-bytes`, which only the adapter's own nonblocking
partial-write queue can supply. V: `jolt.socket.native` already binds
`socket`/`bind`/`listen`/`accept`(`accept4`)/`connect`/`send`/`recv`/`poll`,
`sock-stream`, `eagain?` and `connect-pending?` (`native.clj:60-70,127,236-275`;
read, not run for TCP). The owner's words ("native websocket, no sidecar") do
not name the substrate; r7's "over jolt.socket" is withdrawn.

### 9.4 CBOR-only subprotocol and the codec-track dependency

V: the subprotocol is a composition choice. `dao.stream.ws` names
`dao.stream.transit-json` (text) and `dao.stream.cbor` (binary); a client offers
exactly its selected subprotocol and an endpoint serves exactly the profiles it
was composed with (`ws.cljc:10-15`). Per owner W2, the jolt host composes only
`dao.stream.cbor`. A jolt client offers only `dao.stream.cbor`; a jolt listener
serves only it; a peer offering Transit gets the existing subprotocol refusal,
never a downgrade. Every J6 cross-host peer suite configures CBOR on the
JVM/Node/Dart peer. The parity label discloses "CBOR subprotocol only".

Dependency statement. **J6 (native WebSocket) needs the codec track
([dao.stream.codec.md](./dao.stream.codec.md)) to have landed C0-C4; jolt
requires nothing else of it.** That track is independent of this document and
is run by its own orchestrator; its design is not restated here. J0a, J0b, J0c
and J1-J5 do not depend on it (the local milestone composes no WebSocket).

Jolt-side consequences (P unless labelled):

- Jolt compositions pass `dao.stream.cbor/profile` wherever a codec is
  selected (the remote channel and so the endpoint, the host `:bind!` and the
  attacher). Nothing on jolt requires `dao.stream.transit`, so once the codec
  track has landed, `cognitect.transit`, transit-clj and transit-java are
  absent from the resolved jolt closure; J0a proves the absence (§4.4).
- The `.jolt` CBOR wire-node writer of §5.2 is the jolt implementation of the
  CBOR codec's host seam; it is unchanged by the codec track.
- The `:jolt` reader arm of the host number predicate used by every codec
  (today `finite-number?` at `transit.cljc:19-31`, V; wherever the codec track
  places it) is J1 work, because `dao.stream.cbor` uses it on every host.
- Shared tests that exercise the Transit codec are excluded on jolt with the
  reason "Transit codec, owner-ruled out on jolt" in the J2 applicability
  manifest; codec-neutral shared suites run on jolt over the CBOR profile. No
  shared expected value changes.


### 9.5 RFC 6455 scope and Autobahn

Required protocol behaviour follows [RFC 6455
§§4-7](https://www.rfc-editor.org/rfc/rfc6455.html) (read, not implemented or
tested). Because a section reference is not a falsifiable gate (W4), the
**Autobahn Testsuite** fuzzingclient and fuzzingserver are the named conformance
oracle, for both roles on both platforms. Required case groups: 1-7 (framing,
pings, reserved bits, opcodes, fragmentation, UTF-8, close) and 9 under the
configured bounds. Autobahn is a test-time peer outside the deployed process
tree, consistent with Q6. Case numbering is from the reviewer's knowledge and
is R until checked against the pinned Autobahn release.

Acceptance criteria are pinned **before J6 is commissioned** (astra): the J6
brief names the Autobahn release, the exact case list (every case id in groups
1-7 and 9 at that release), and for each case the accepted verdict: `OK`, or
a listed exception with the behaviour the adapter exhibits instead and the
reason it is correct under `dao.stream.ws.md` (for example a close code this
transport substitutes). Any case outside the list, any `FAILED`, and any
`NON-STRICT` or `INFORMATIONAL` verdict not on the exception list fails the
gate. Recording a reason for a non-strict result at run time is a report, not
acceptance.

The groups cover, among others: RSV1-3 set with no extension; reserved/unknown
opcodes; control frames over 125 bytes or fragmented; a continuation with no
start; a new data frame inside a fragmented message; non-minimal length
encoding; a 64-bit length with the MSB set; a close payload of length 1; invalid
wire close codes (<1000, 1004-1006, 1015, 1016-2999); invalid UTF-8 split across
fragments; a peer that never answers close.

### 9.6 J6 gates (both platforms)

- Handshake gate (extensions): the client never offers an extension and fails an
  unsolicited extension in the 101; the server ignores offers and negotiates
  none; RSV bits set without negotiation close 1002.
- Handshake: SHA-1 known-answer; invalid handshakes; version mismatch (426);
  subprotocol negotiation exactness (exact offer, endpoint-order pick, mismatch
  fails, no downgrade); the 400 refusals; canonical-path cases.
- Listener: bind with port 0 reports the actual port; listener stop completes
  with `:stopped`.
- Protocol: Autobahn groups 1-7 and 9 against the pinned case list and
  exception list (§9.5); text/binary and length-boundary framing; masking
  direction; fragmented messages with interleaved ping/pong; over-bound
  messages.
- I/O: partial reads and writes; stalled-reader backpressure and drain recovery
  with bounded memory and exact queued-byte accounting; close races and abnormal
  EOF; send after failure answers `closed`.
- Codec: CBOR profile fixtures encode and decode; a Transit offer is refused as
  a subprotocol mismatch; `dao.stream.transit` is absent from the jolt closure
  and a codec-less composition throws at composition (codec-track dependency,
  §9.4).
- Peers: existing REPL/stream peer suites with jolt dialing and listening
  against JVM/Node/Dart (configured for CBOR), custody and exactly-once terminal
  events, and the frame-too-large matrix below (ruling 3).
- Deployment: native process tree only; a failing native implementation is a
  failed gate. ws:// only (owner-settled); wss is refused explicitly and never
  downgraded.

Frame-too-large across host pairs (ruling 3, §13.4). The shared transport asks
every seam for 1009 `dao.stream/frame-too-large` (V: `ws.cljc:355-357`), and
the shared test exercises the **detecting** endpoint only: the `:ws/error
:ws/frame-too-large` diagnostic, the rejected payload never decoded or
delivered, exactly one terminal `:ws/closed`, `append!` answering `closed` (V:
`ws_test.cljc:396-415`; `jvm_test.clj:667-689` is the same endpoint's view).
The **peer** has no diagnostic to deposit: it sees only what the sender's seam
put on the wire, a close frame or a bare EOF/reset, which is a property of the
**sending** host documented per host in `dao.stream.ws.md` ("Host wire
codes"). Cross-host suites therefore assert two different things (astra A4):
at the detecting endpoint, the diagnostic and the rejected payload; at the
peer, the sender-specific wire behaviour and exactly one terminal event. The
jolt seam sends the code it is asked for.

+------------------------------+-------------------------------------------------------------------------------------------------------------+---------+
| Direction (sender -> peer)   | What the peer observes                                                                                      | Label   |
+==============================+=============================================================================================================+=========+
| jolt -> JVM client, Node,    | A close frame 1009 with reason `dao.stream/frame-too-large`, then TCP close; the peer deposits one          | P       |
| Dart, browser, JVM http-kit  | `:ws/closed`.                                                                                               |         |
+------------------------------+-------------------------------------------------------------------------------------------------------------+---------+
| JVM client -> jolt           | No close frame: the socket is aborted (V: `jvm.clj:165-170,188-192`; `jvm_test.clj:667-689` asserts the    | V (JVM) |
|                              | peer receives zero close frames and sees EOF/reset). jolt must read abnormal EOF/reset as its 1006, deposit | P (jolt)|
|                              | one `:ws/closed`, and never synthesize a clean close.                                                       |         |
+------------------------------+-------------------------------------------------------------------------------------------------------------+---------+
| browser -> jolt              | Close 1000 with reason `dao.stream/frame-too-large` (V: `browser.cljs:84`, `dao.stream.ws.md` Host wire    | V/R     |
|                              | codes); jolt deposits one `:ws/closed`. Browser peers stay gated; this row is recorded, not a J6 gate.      |         |
+------------------------------+-------------------------------------------------------------------------------------------------------------+---------+
| Node -> jolt, Dart -> jolt,  | Whatever that seam's own library or seam sends (Node `ws` `maxPayload` closes 1009, R: `node.cljs:33`;     | R       |
| JVM http-kit -> jolt         | http-kit `:max-ws` and Dart to be read at J6). J6 records each seam's actual code before writing the        |         |
|                              | assertion; the assertion encodes the recorded behaviour, never a guess.                                     |         |
+------------------------------+-------------------------------------------------------------------------------------------------------------+---------+

Falsifiable: a J6 suite that expects 1006 from a jolt sender, or 1009 from a
JVM-client sender, is wrong by construction and fails. The peer-side assertion
is the same in every row: exactly one terminal event, no payload delivered, no
clean close synthesized from a drop; the diagnostic is asserted only at the
detecting endpoint.

R: the JVM listener has no TLS setup (`jvm.clj:443-452`, V), but the JVM client
uses `buildAsync` (`:336`), so listener absence is not evidence about client
TLS. wss:// is owner-settled out (§13.1); if the owner later widens scope, TLS
verification, certificate and failure gates come first, not a URL spelling
change.

## 10. Slices

### 10.1 Slice table

States describe landed code and current work, not acceptance closure. The J0a
pin cell below records its original gate; the current pin is in §6.4. J1/J1b
landings and J0c's probe do not discharge J2b or J5 acceptance.

+---------------------------------------------+-------------------+-----------------------------------------------------------------------------------------------------+
| Slice                                       | Boundary          | Falsifiable acceptance                                                                              |
+=============================================+===================+=====================================================================================================+
| External dependency: the codec track        | Not this track    | [dao.stream.codec.md](./dao.stream.codec.md) must have landed its slices C0-C4 on master before J6  |
|                                             |                   | is commissioned; jolt requires nothing else of it (§9.4). Not jolt work; not restated here.          |
+---------------------------------------------+-------------------+-----------------------------------------------------------------------------------------------------+
| J0a: tracked load and toolchain             | Manifest and      | Precondition: the owner's disposition of the §13.2 defaults that J0a would apply is recorded.       |
| landed                                      | bootstrap, branch | Clean-cache core require without overlays; standard/main dependency audit, naming                   |
|                                             | inventory, narrow | runtime-unexercised dependencies; after C0-C4, proving `cognitect.transit`/transit-clj/transit-java  |
|                                             | source            | absent from the jolt closure (ruling 1); pin `96d7b866` recorded with "tag: none; nearest v0.8.23"   |
|                                             |                   | (ruling 4); S arithmetic and six hashes                                                             |
|                                             |                   | reproduced; gaps listed. Commit the selected Chez selector and an exact CMake pin in `mise.toml`.   |
|                                             | adaptations,      | Vendored source hashes and build paths; guarded `src/clj` loading; source/provides/binary           |
|                                             | formatter/linter  | precedence; `.jolt` formatting and kondo; runner; FFI smoke. libcrypto provenance and negative      |
|                                             | and loader gates  | tests; long-hash collect-safety. Platforms reported independently; both needed for platform         |
|                                             |                   | qualification.                                                                                      |
+---------------------------------------------+-------------------+-----------------------------------------------------------------------------------------------------+
| J0b: UDP FFI feasibility                    | Small raw         | §8.3 on both platforms: packet boundaries, N=1200 and configured-N truncation sentinel, EMSGSIZE,   |
| probe-only; not merged                      | transport on      | landed transport-error and send-failed on EAGAIN/EWOULDBLOCK and macOS ENOBUFS, nonblocking         |
|                                             | existing          | receive, cleanup. Injected versus natural results labelled. No `full`, no send wait inside          |
|                                             | socket/poller     | `append!`.                                                                                          |
|                                             | groundwork        |                                                                                                     |
+---------------------------------------------+-------------------+-----------------------------------------------------------------------------------------------------+
| J0c: early C++ feasibility                  | Python Cpp target | Starts at the §7.8 minimum baseline. §7.8 on both platforms: packet and completeness parity,        |
| probe-only; landed                          | and helpers,      | restricted lowering corpus, two-worker/cache isolation, collect-safe calls, thread/stack budget,    |
|                                             | raw-tree wrapper, | exception/lifetime tests, helper provenance, generated digests. No C++ Jing encoder. Failure        |
|                                             | jolt cst adapter  | returns to the owner; no fallback.                                                                  |
|                                             | over the shared   |                                                                                                     |
|                                             | exporter, native  |                                                                                                     |
|                                             | worker isolation  |                                                                                                     |
+---------------------------------------------+-------------------+-----------------------------------------------------------------------------------------------------+
| J1: numeric and content correctness         | Exact integer     | Existing numeric/CBOR/hash corpus bytes and refusals match JVM/CLJS/CLJD; official hash boundaries; |
| landed                                      | algorithms, full  | every scalar class and host-value wire node (byte arrays anywhere); decode seams                    |
|                                             | Jing              | `cbor.cljc:537,649-652`; >64-bit integers and `~x` identities; correct code-index sort. No SHA      |
|                                             | representation    | stub, no native-float wire shortcut.                                                                |
|                                             | and wire, C       |                                                                                                     |
|                                             | BLAKE3, libcrypto |                                                                                                     |
|                                             | SHA, `asort`      |                                                                                                     |
+---------------------------------------------+-------------------+-----------------------------------------------------------------------------------------------------+
| J1b: hash/sign                              | Native crypto     | Pinned libblake3 and qualified system libcrypto; J1 content/hash gates remain above.                |
| landed                                      |                   | Landed `97c7b902`; no change to J4 authority acceptance.                                            |
+---------------------------------------------+-------------------+-----------------------------------------------------------------------------------------------------+
| J2: pure test lane and VM conformance       | `test:jolt`       | Every applicable pure test loads and passes; semantic and register boundary traces match. No silent |
| closed                                      | family, shared    | excludes, unchanged goldens, expected counts, nonzero failure exit. A throwing test is an error and |
|                                             | fixture adapter,  | later tests still run: reproduce `docs/jolt-experiment.md:70` with sentinel tests after it and a    |
|                                             | B-tree/space/VM   | nonzero final status. Shared source changes keep the existing three lanes green. On jolt:           |
|                                             | pure suites,      | `dao.stream.transit` is not loaded; codec-less `make-attacher`/`make-endpoint` throw at             |
|                                             | runtime bug fixes | composition; Transit-codec shared tests excluded with reason; codec-neutral suites run over the     |
|                                             |                   | CBOR profile (§9.4).                                                                                |
|                                             |                   | Closed on `f4d6a284`: rerun 3 (`078e1e43`, shiva) 150 namespaces, 0 load failures, 0 errors, 4      |
|                                             |                   | owner-accepted reader failures; safepoint harness 60/265,225/0 (`a0a49b8e`); J4 table `9c2d8da0`;   |
|                                             |                   | gate (f): Dart 4,113 passed, Node on shiva rc=0, 242 namespaces, 0 failures/errors.                 |
+---------------------------------------------+-------------------+-----------------------------------------------------------------------------------------------------+
| J2b: deferred comparisons                   | Oracle/lowering   | Node/Dart oracle comparison and restricted lowering comparison (§7.8); both close before J5. Owner  |
| closed (zero pins, JVM canonical)           |                   | split from J2, 2026-10-22 (§13.5); JVM canonical, divergent hosts patched (owner 2026-10-24).       |
|                                             |                   | Closed 2026-10-24: `yang.python.antlr.oracle-parity-test` passes on the JVM, Node, Dart and jolt    |
|                                             |                   | with zero pins on macOS arm64 and Linux x86_64 (64 corpus entries, 40 probe texts, every ok         |
|                                             |                   | packet's lowering); fixes: the Node/Dart lazy-buffer first-failure rule and the JavaScript/Dart     |
|                                             |                   | lexer-helper manifest patches (known-gaps).                                                         |
+---------------------------------------------+-------------------+-----------------------------------------------------------------------------------------------------+
| J3: local shell and demo                    | Jolt main/stdin/w | API and CLI from tracked roots; demo = 5050; EOF/quit; no duplicate eval; cadence/wake/pending      |
| implemented; gates open                     | ake/banner, local | semantics; all VM modes; published code index without workaround. Clojure/PHP explicit; Python      |
|                                             | catalog, shell    | selectable since J5 (the native C++ parser, §7.6 budget).                                           |
|                                             | driver, index,    |                                                                                                     |
|                                             | query             |                                                                                                     |
+---------------------------------------------+-------------------+-----------------------------------------------------------------------------------------------------+
| J4: persistence and authority               | File/store FS,    | Existing file/B-tree/store crash, recovery and lock fixtures and RFC/envelope/signature vectors     |
| J4a-J4d landed                              | Ed25519 keygen    | pass; cross-host reopen and two-way signing; no security stubs; local storage options and shutdown  |
| (890dc68f, 9c4ce1df,                        | and key files,    | work. J4d: the file: store opens on jolt (only dht: refuses, at composition, J7); saved flags       |
| f5397a15, J4d merge);                       | lifecycle cleanup | persist through the jolt -main (--reset, --no-state); key files are created O_EXCL at 0600 and      |
| J4 closed 2026-10-28;                       |                   | never overwritten; no J4-owned entry remains in jolt-applicability.edn or the J2 lane; cross-host   |
| reviews fable + codex/astra,                |                   | reopen JVM, Node and Dart <-> jolt.                                                                 |
| both APPROVE                                |                   |                                                                                                     |
+---------------------------------------------+-------------------+-----------------------------------------------------------------------------------------------------+
| J5: complete standard frontends             | The selected      | §7.8 J5 gate. Standard catalog loads and `(lang :yang/python)` works;                               |
| landed on jolt-j5, review pending           | in-process C++    | frontend_catalog/python_antlr/main suites; synchronous probe sequences equal the JVM's;             |
|                                             | parser, standard  | local-build-only packaging. **Local REPL milestone**, native C++ parser library disclosed; no       |
|                                             | catalog, complete | process crash isolation claimed.                                                                    |
|                                             | ness-probe        | Amended (row 59): enforced input/nesting budget, stage-owned worker lifecycle and the               |
|                                             | integration       | unavailable-helper answer (§7.6); bb probe:jolt-j5, bb test:jolt-j5 on both platforms.              |
+---------------------------------------------+-------------------+-----------------------------------------------------------------------------------------------------+
| J6: native WebSocket and network REPL peers | RFC 6455 host     | Preconditions: C0-C4 on master; the Autobahn release, case list and exception list pinned in the    |
| not started                                 | adapter on the    | brief (§9.5). §9.6 on both platforms, including the frame-too-large matrix (ruling 3, A4 wording).  |
|                                             | native socket +   | Native deployment only. ws:// only (owner-settled). The adapter's closure contains                  |
|                                             | poller substrate  | `jolt.socket.native`, `jolt.io-poller` and `jolt.ffi` and not `jolt.socket` (ruling 2); `send!`     |
|                                             |                   | never waits; `:queued-bytes` is exact and drains on readiness.                                      |
|                                             | (§9.3), full §9.2 | **2026-10-27 proposal, owner ruling pending:** pinned harness/case policy; see note below.             |
|                                             | contract, CBOR    |                                                                                                     |
|                                             | subprotocol only  |                                                                                                     |
+---------------------------------------------+-------------------+-----------------------------------------------------------------------------------------------------+
| J7: UDP and DHT                             | Integrate the J0b | §8.4; J0b regressions retained; would-block/ENOBUFS is transport-error plus send-failed; no `full`, |
| not started                                 | transport;        | no hidden retries. If deferred, network parity stays incomplete.                                    |
|                                             | datagram contract |                                                                                                     |
|                                             | and DHT/linker    |                                                                                                     |
|                                             | composition       |                                                                                                     |
+---------------------------------------------+-------------------+-----------------------------------------------------------------------------------------------------+
| J8: repo lane qualification                 | All-task, changed | Full supported fast/all/slow suites green; reviewed host-only applicability list; every entry path  |
| not started                                 | and subsystem     | and J1-J7 gate on both platforms; existing three lanes green; same shared kit, no overlays/stubs,   |
|                                             | integration,      | no unexpected skips; native WebSocket deployment, no JVM network helper; "ws:// only, CBOR          |
|                                             | clean repro,      | subprotocol only" stated. Benchmarks and proposed thresholds recorded; no speed gate. **Host-lane   |
|                                             | package checks,   | parity.**                                                                                           |
|                                             | benchmark         |                                                                                                     |
|                                             | reporting         |                                                                                                     |
+---------------------------------------------+-------------------+-----------------------------------------------------------------------------------------------------+

**J6 harness amendment proposal (2026-10-27; not an adopted gate change).**
The [Autobahn prep and proposed gate](../../tools/jolt-autobahn/README.md#5-proposed-j6-amendment-for-owner-ruling)
pins official 25.10.1 by image digest and inventories 517 exact cases: 298
must-pass, three assertion-constrained informational cases (7.1.6, 7.13.1, 7.13.2), 216 compression
cases not applicable without extension negotiation (offers ignored, none negotiated). The proposed gate runs
301 cases in each native testee role on both platforms, zero unlisted
non-strict outcomes, with raw evidence retained; all existing §9.6 and real
jolt↔JVM/Node/Dart peer gates remain. Re-pinned 2026-10-28 (J6a) from 299/2/216
to 298/3/216: upstream 7.1.6 labels both behaviors INFORMATIONAL unconditionally. The included jolt echo stub verifies
harness plumbing only and makes no RFC 6455 or J6 conformance claim. Owner
approval is required for the case/exception policy. This proposal does not
transfer the J7 UDP no-`full` policy to WebSocket or change WS backpressure.


### 10.2 Order, dependencies and platforms

J0a, J0b and J0c are independently commissionable and reportable; their
current states are in §10.1. The codec track is external (§9.4): J6 depends on
its C0-C4 having landed, nothing else here does, and it runs on its own schedule.
J0c starts at its minimum baseline (§7.8), before unrelated
UDP/codec work, because its no-fallback failure is the earliest owner decision.
J1 depends on J0a; J5 on J0c plus the correctness and CLI slices; J7 on J0b. A
J0b or J0c failure does not erase a tracked-load result.

Every slice needs both macOS arm64 and Linux x86_64 to close. A one-platform J0a
report is useful partial evidence. A macOS-first partial report for J0b/J0c is
proposed default 1 (§13.2). The Linux x86_64 machine of record is
**`shiva.stigmergy.systems`** (owner: "leave jupiter out, use shiva"), run
through the script-driven `~/ci/` scratch-clone setup in
`docs/agents/build-n-test.md` (V: `:301-344`); there is no hosted CI. shiva's
mise snapshot does not list Chez or CMake (V: `:309`), so provisioning them
there is part of J0a. An unavailable machine leaves the slice incomplete, not
passed.

Tests land in their earliest owning slice and rerun as dependencies change. A
namespace load never counts as behavioural acceptance. List output gains
capability-qualified reporting so a slow exclusion, an unsupported host
capability and a failure are distinguishable.

J3 implementation note (2026-10-22; macOS only, Linux unrun): the tracked
local CLI selects a Clojure/PHP catalog, refuses Python until J5 (J5 lifts
it: the standard catalog installs Python when the native parser loads) and
durable stores until J4/J7, and runs one plain-thread owner through `run-owner!`.
The wake implementation multiplexes stdin and a self-pipe using jolt's existing
native `poll` binding: `io-poller/wait-ready` accepts one fd only. It uses
poller monotonic deadlines and checks Chez buffered input before fd readiness.
Partial lines block until newline; no line editing or signal drain is claimed.
The five tracked namespace probes load with Transit absent. Shared reader
failures, the demo's JVM-and-jolt foreign-task closure failure, and H0's
frozen host-arm/effect-count admission leave J3 acceptance open; see the
engineer findings for the current gate evidence. No H0 assertion was changed.
The `yin.repl`, host, catalog, codec-selection and startup-store fold sites in
§4.4 now have J3 arms; file, signing and network mechanisms remain their owning
slices' work. No Linux qualification is inferred from the macOS evidence.

## 11. Test and conformance strategy

One oracle, one kit. Engine §E.5's oracle/export/differential method applies
unchanged: jolt joins as another execution lane, does not replace CLJS and never
manufactures expected values from itself. K0/W0 register fixtures stay in
register §10.4. Reuse the semantic corpus `test/yin/vm/semantic/corpus.cljc`,
literal goldens and `test/yin/vm/debruijn/register_test.cljc` with declared
bindings and genuine parked targets; execute every arm, keep refused cases,
compare traces rather than values: raw pcs, sparse regs/live, frames/K, stores,
flags, counters, wait/ready/parked entries, custody and ordered requests.
Normalize private host identities only; owner/seal/profile/address equality
stays. Byte and hash suites extend the existing fixtures; there is no jolt-only
golden corpus and no second conformance kit.

Host-neutrality is audited, not assumed: a `.cljc` test is inventoried for its
selected branches and actual assertions. JVM reflection/compiler tests, JS DOM
tests, Dart compiler tests and JDK-adapter unit tests are not run as Chez code;
their portable behavioural equivalents and cross-process peer legs are required.
Every excluded namespace or test has a reason, capability, replacement gate and
owner; zero unexplained skips. The owner moved the 14 J4-blocked namespaces to
the exclusion table with owner J4 (§13.5); applying that ruling to the landed
lane table remains open. `test:jolt-core` includes `^:slow`; J2 gate (b) remains
open pending the rerun and expected counts, and the safepoint harness result is
pending (`a0a49b8e`; `docs/agents/known-gaps.md`, J2 classification rows).

External oracles are test-time peers only: JVM/Node/Dart for packets, bytes,
hashes and peer suites; official BLAKE3, SHA, RFC 8032 vectors; Autobahn for
RFC 6455. None is part of the deployed jolt process tree.

Native code is tested from outside: libcrypto negative tests, parser fault
injection and recursion stress run in child processes or an external harness, so
an abort or crash fails a test instead of erasing the report.

Measurement. From J0a/J0c: cold/warm startup, first REPL latency, native parser
timing and memory on each available platform, compared with JVM without a ratio
budget. At J8: startup cold/warm/devboot/AOT, submission latency, large-integer
operations, hashing throughput, B-tree/index workload, parser library load and
cold versus steady parse, long-tail programs, GC/retention and cross-stream
waits, with native parse/marshalling and shared export time separated from VM
time. J8 records thresholds; a later commissioned slice gates them.

## 12. Risks

- **Upstream churn.** jolt has a single maintainer and fast main churn (989
  commits since 2026-09-19 and 122 tags, the orchestrator's count in the mob
  synthesis).
  Recorded revisions and the upgrade gate (§6.4) contain it; no published
  dependency coordinates, so the CLI/bootstrap lifecycle is needed.
- **Late shim failures.** JDK-shim compatibility can fail deep in executed paths
  after a successful require (the Transit path was the known case; once the
  codec track lands, `dao.stream.transit` is out of the jolt closure, §9.4).
  Ruling 2 keeps the `jolt.socket` java.net shim out of the network adapters
  for the same reason.
- **Codec-track dependency.** If the codec track slips, J6 is blocked and the
  local milestone is not (§9.4, §10.2).
- **Observable divergence.** Chez numeric carriers, Sorted dispatch, GC refs and
  set iteration can change observables (§5).
- **Native crashes.** Architecture, loader or lifetime mistakes in native
  libraries crash the process. Narrow reviewed wrappers, missing-symbol and
  wrong-version tests without running guest work. The in-process parser has no
  crash isolation (§7.6).
- **libcrypto drift.** A Homebrew `openssl@3` or system `libcrypto.so.3` update
  invalidates the recorded path/version and fails until requalified; Apple's
  libcrypto aborts in `EVP_Digest` (§6.3).
- **Parser infeasibility.** New C++ target, helpers, raw-tree adapter, packaging
  and lifetime risk; failure returns to the owner without fallback, which is why
  J0c runs early.
- **WebSocket cost.** Native RFC 6455 is substantially larger than the JVM glue
  (§9.3): conformance, partial I/O, backlog accounting and close races. TCP
  availability is not evidence those pass. SHA-1 provenance and handshake
  fixtures need qualification.
- **CBOR-only reach.** Every default-configured JVM/Node/Dart peer speaks
  Transit-JSON (per the review), so jolt peers only reach peers composed with
  CBOR; the parity label discloses it.
- **Release gates, not polish.** File locking/durability and Ed25519 are release
  gates.
- **Licensing.** Jolt is EPL-2.0 (V: `LICENSE:1`, not a legal clearance for a
  combined package); Chez notices are unaudited; generated-parser redistribution
  is blocked by the manifest (§7.7). Owner-settled: local builds only for now,
  revisit before any distribution (§13.1).
- **Historic slowness.** Old jolt timings warrant benchmarking, not preemptively
  weakened tests.

## 13. Decision record

### 13.1 Settled owner decisions (2026-10-19)

These are the original scope decisions; implementation status is in §10.1.

+------------+---------------------------------------+------------------------------------------------------------------------------------------------------------------+
| Id         | Owner's words                         | Recorded meaning                                                                                                 |
+============+=======================================+==================================================================================================================+
| Q1         | "aceept local-REPL milestone first"   | The local REPL milestone (through J5) comes first; J6/J7 are later parity work.                                  |
+------------+---------------------------------------+------------------------------------------------------------------------------------------------------------------+
| Q2         | "mac arm and linux", then "x86-64"    | macOS arm64 and Linux x86_64. Windows out; widening needs a further owner decision and platform gates.           |
+------------+---------------------------------------+------------------------------------------------------------------------------------------------------------------+
| Q3         | "Q3 is not relevant if we use FFI     | In-process ANTLR4 C++ parser over FFI. J0c required; on failure the orchestrator returns to the owner with the   |
|            | with antlr C++ generated parser"; on  | evidence. No fallback.                                                                                           |
|            | fallback: "no fall back"              |                                                                                                                  |
+------------+---------------------------------------+------------------------------------------------------------------------------------------------------------------+
| Q4         | "let's go with your proposal"; for    | Mise-driven, pinned, hash-checked vendored builds of libblake3 and the ANTLR4 C++ runtime with a mise-pinned     |
|            | the ANTLR C++ runtime: "yes, same     | CMake into `build/native/<platform>/`; system/Homebrew OpenSSL libcrypto with a pre-digest path/version check    |
|            | policy as libblake3"                  | that fails on mismatch.                                                                                          |
+------------+---------------------------------------+------------------------------------------------------------------------------------------------------------------+
| Q5         | "record the mob's answer", then "opus | Pin jolt by full SHA in `jolt-toolchain.edn`; prefer release tags; upgrade through a named two-platform gate     |
|            | when needed"                          | when a needed upstream fix lands, the resolved Chez version changes, or the pin is older than about 30 days;     |
|            |                                       | never silently. No numeric JVM-ratio budgets until J8 evidence.                                                  |
+------------+---------------------------------------+------------------------------------------------------------------------------------------------------------------+
| Q6         | "native websocket, no sidecar"        | Native WebSocket in the jolt host for J6/J8; no helper process.                                                  |
+------------+---------------------------------------+------------------------------------------------------------------------------------------------------------------+
| Q7         | "keep landed contract"                | No UDP `full`. Would-block (EAGAIN/EWOULDBLOCK, macOS ENOBUFS) is transport-error plus the send-failed event; no |
|            |                                       | queue, park or retry in `append!`.                                                                               |
+------------+---------------------------------------+------------------------------------------------------------------------------------------------------------------+
| W2         | "CBOR-only websocket, no Transit      | The jolt WebSocket composes only the `dao.stream.cbor` subprotocol.                                              |
|            | port"                                 |                                                                                                                  |
+------------+---------------------------------------+------------------------------------------------------------------------------------------------------------------+
| Linux host | "leave jupiter out, use shiva"        | Linux x86_64 qualification runs on `shiva.stigmergy.systems` through the `~/ci/` scratch-clone setup of          |
|            |                                       | `build-n-test.md`; no hosted CI exists.                                                                          |
+------------+---------------------------------------+------------------------------------------------------------------------------------------------------------------+
| Licensing  | "local builds only for now; revisit   | Local-build packages only (§7.7 stays); no redistribution of jolt, Chez or generated-parser artifacts until the  |
| (2026-10-  | before any distribution"              | owner revisits; the manifest's blocked-until gate is unchanged. Recorded 2026-10-20.                             |
| 20)        |                                       |                                                                                                                  |
+------------+---------------------------------------+------------------------------------------------------------------------------------------------------------------+
| wss://     | "out; ws:// only"                     | ws:// only in every milestone label; wss is refused explicitly and never downgraded. Widening needs a further    |
| (2026-10-  |                                       | owner decision and the TLS gates of §9.6. Recorded 2026-10-20.                                                   |
| 20)        |                                       |                                                                                                                  |
+------------+---------------------------------------+------------------------------------------------------------------------------------------------------------------+
| Linux      | "lets assume debian distribution and  | Linux x86_64 means the Debian family with apt. Host packages are declared by apt name in `jolt-toolchain.edn`    |
| packages   | apt get for dependency management of  | `:os-packages` and checked by `bb bootstrap:jolt` (dpkg-query) before any mise build; a missing one fails with   |
| (2026-10-  | package"; then "yes to 1, 2, 3 and 4  | the exact `sudo apt-get install -y ...` command, never run by the task. Linux libcrypto comes via apt (Q4 stays; |
| 20)        | as recommended"                       | the path/version check is retained). macOS: Homebrew assumed, not enforced. §6.1, §6.3. Recorded 2026-10-20.     |
+------------+---------------------------------------+------------------------------------------------------------------------------------------------------------------+

Also recorded as the owner's original instruction: "yes, have the architect add
the UDP probe to J0" (J0b).

### 13.2 Proposed defaults awaiting owner veto

Orchestrator proposals. **Not owner decisions and not mob consensus.**

1. **Platform reporting.** macOS arm64 may report first on J0b/J0c, as already
   allowed for J0a. The report is marked partial; Linux x86_64 evidence is
   required before the slice closes. A real failure is reported immediately
   (J0c: to the owner, no fallback).
2. **Local patches.** Patches on the pinned jolt SHA only if the toolchain file
   records patch files, digests, order, rationale and the upstream issue or
   submission; upstreamed in parallel; the patched tree qualifies through the
   same gate. No silent fork or dirty checkout.
3. **Chez pin.** An exact Chez version in `mise.toml` and the toolchain file
   instead of `latest`; a Chez change is an upgrade-gate trigger however it was
   resolved.

**Owner disposition, recorded 2026-10-20.** The owner confirmed all three
defaults in their own words: "confirm defaults, licensing local only, ws only.
commission J0b" (2026-10-19). Defaults 1 to 3 therefore take effect; they stay
listed here as the record of what was confirmed, and are not reopened.

One rule for all three (W6): a default takes effect only once the owner's
acceptance or veto is recorded in this section. Recording it is a precondition
to commissioning the slice that would apply it (J0a for default 3; J0b/J0c for
default 1; the first recorded patch for default 2), not an acceptance item of
that slice. This is the rule r7 already states for the Chez pin ("record its
acceptance/veto before applying it"), applied uniformly.

### 13.3 Open owner questions

None at 2026-10-20. Licensing and wss:// were answered by the owner and are
recorded in §13.1. The codec track's owner questions (the yin.repl default
profile and others) belong to that track's document, not to this one.

### 13.4 Architect rulings (owner may overrule)

Ruled 2026-10-20 by the Architect (claude-fable-5-1; rationale in
`archive/jolt/1792454059000-architect-jolt-design-points.claude-fable-5-1.findings.md`)
on the four points the r7 review left open. Each ruling states its reasons,
the falsifiable consequences the slices must show, and the condition that
reverses it. The owner may overrule any of them; an overruling is recorded in
§13.1 and the touched sections are re-edited.

**Ruling 1 (withdrawn 2026-10-20): the Transit question is not this track's.**
It is owned by the independent codec track
([dao.stream.codec.md](./dao.stream.codec.md)); this document states only the
dependency and the jolt-side consequences in §9.4. History, for the record: the
first ruling 1 (a shared predicate namespace plus an empty `:jolt` arm in
`dao.stream.transit`) was rejected by the owner as jolt-only ("it should not
require transit"); the second (a shared-code decoupling slice T0 inside this
document) was widened by the owner to peer codecs open to future encodings and
then separated into its own track ("an independent orchestrator should be able
to work on it independent of this track").

- Falsifiable consequences for jolt. J0a, once the codec track has landed:
  `cognitect.transit`, transit-clj and transit-java are absent from the
  resolved jolt closure. J2: codec-less compositions fail at composition on
  jolt as on every lane. J6: CBOR fixtures round-trip; a Transit offer is
  refused.
- Reversal. If the codec track is abandoned, J6 cannot be commissioned as
  specified and the question returns to the owner; nothing in J0a-J5 changes.

**Ruling 2: the WebSocket substrate is `jolt.socket.native` plus
`jolt.io-poller` directly; the `jolt.socket` java.net shim is not used.**
Applied in §5 and §9.3.

- Reasons. (i) The shim's write path waits on `wait-ready fd :write`
  (`socket.clj:349,719-724`, V), so a `send!` over it would park inside
  `append!`; the contract forbids a waiting operation and the transport's
  `full`/`:queued-bytes` semantics need immediate acceptance truth. (ii) The
  shim reads a recv error as EOF (V), which would report abnormal termination
  as a clean peer close, forbidden by §9.2. (iii) J0b already exercised the
  native+poller substrate on macOS for UDP: nonblocking fds, parking, wakeup,
  cancellation and cleanup (S, 36 assertions). (iv) The shim is a JDK
  compatibility object model; relying on it imports the "late shim failure"
  risk class into the network adapter. The shim is itself built on
  native+poller (V: `socket.clj` requires), which is evidence the substrate
  carries TCP, R until J6 runs it.
- Falsifiable consequences. J6: the adapter's require closure contains
  `jolt.socket.native`, `jolt.io-poller` and `jolt.ffi` and not `jolt.socket`;
  `send!` returns without waiting on a full socket and the pending bytes appear
  in `:queued-bytes`; readiness drains them; a recv error produces the
  abnormal-close path, never `:ws/closed` with a clean code. Missing TCP
  bindings are added as narrow `defcfn`s in the adapter, never by reaching for
  the shim.
- Reversal. If J6 finds a capability the poller cannot provide for a
  non-fiber driver (§7.5) and the shim can, the Architect reconsiders with that
  evidence; the shim's waiting write and EOF divergence must still be shown
  absent on the chosen path.

**Ruling 3 (A4 wording, 2026-10-20): cross-host suites assert the diagnostic
and the rejected payload at the detecting endpoint, and the sender-specific
wire behaviour plus exactly one terminal event at the peer; jolt sends 1009 and
never imitates a peer's limitation.** Applied in §9.2 and §9.6 (matrix).

- Reasons. (i) The shared transport asks every seam for 1009 and the shared
  test exercises the detecting endpoint only (V, §9.6); the wire code is a
  seam property that `dao.stream.ws.md` already documents per host. (ii)
  Encoding 1006 as the universal expectation would write the JVM client's
  `sendClose` limitation into the parity suite, which is what W1 warned
  against; encoding 1009 universally would fail the JVM-client sender by
  construction. (iii) The two ends observe different things: the detector has
  a diagnostic and a rejected payload, the peer has only what reached the
  wire; "identical observables at both ends" was wrong (astra A4) and is
  withdrawn. The peer's duty is the same in every row: one terminal, no
  payload, no clean close synthesized from a drop.
- Falsifiable consequences. The §9.6 matrix: a J6 suite expecting 1006 from a
  jolt sender, or a close frame from a JVM-client sender, fails; a suite
  asserting the `:ws/frame-too-large` diagnostic at the peer fails. The Node,
  Dart and http-kit sender rows are R and are filled from the seams' recorded
  behaviour before their assertions are written.
- Reversal. A repaired JVM client seam (one that can send 1009) changes its
  row only; nothing on the jolt side changes.

**Ruling 4 (A5 wording, 2026-10-20): J0a keeps `96d7b866`; from the first
upgrade-gate run onward Q5 governs as stated, a suitable release tag preferred
and an explicitly qualified untagged fix admissible.** Applied in §6.4 and the
J0a row. Historical: superseded for the current pin by the owner ruling of
2026-10-22 (§13.5); retained as the original qualification policy.

- Reasons. (i) `96d7b866` is untagged (V, `git describe`:
  `vnightly-16-g96d7b866`; nearest release tag `v0.8.23`, two days older) and
  all S and J0b evidence describes it; re-pinning now would discard the only
  executed evidence for a tag with no identified fix we need. (ii) Q5 prefers
  release tags but subordinates everything to "never silently" and the gate;
  a full SHA is already an exact pin, and at jolt's churn rate (§12) a tag is
  not materially more stable than a SHA. (iii) The tag preference is honoured
  from the first gate run onward, so nothing in Q5 is set aside, only
  sequenced; and Q5's allowance for an untagged SHA that carries a needed fix
  stands (the earlier "targets the newest release tag" was an architect-added
  restriction and is withdrawn, astra A5).
- Falsifiable consequences. `jolt-toolchain.edn` records the full SHA, "tag:
  none", the nearest release tag and the describe string; J0a reproduces the S
  claims at that SHA without re-establishment work; every later gate record
  states whether the candidate is tagged and, if not, which needed fix
  qualifies it.
- Reversal. A J0a or J1 blocker whose fix exists only in a later tag, or an
  owner instruction that pins must be tags from the start. Either way the
  re-pin goes through the gate and the S claims are re-established at the new
  pin, as §6.4 already requires.

### 13.5 Owner rulings (2026-10-22)

These rulings update the implementation scope and gates; they do not claim
closure. Evidence: `a0a49b8e` (J2 lane and its owner-ruling comments),
`9460bcb8` (`docs/agents/known-gaps.md`, J2 classification and patch ruling),
`63301597` (fork re-pin), the pin reports cited in §6.4, and the owner-directed
refresh brief `collab/1792740000000-engineer-jolt-design-refresh.codex.prompt.md`
(J2b split and PHP reaffirmation).

1. **Q1 version B, integer carriers (2026-10-22).** For in-range values,
   whether `big-carrier?` answers true is host-defined. Portable code must not
   rely on it. This carrier ruling is distinct from the milestone Q1 in §13.1.
2. **J2b split (2026-10-22).** Node/Dart oracle comparison and restricted
   lowering comparison move out of J2 into J2b. Both must close before J5
   acceptance (§7.8); landing J0c probe-only did not waive either comparison.
3. **Full core lane (2026-10-22).** `test:jolt-core` runs everything in its
   included surface, including `^:slow`; no fast-tier shortcut.
4. **Pinned jolt patches (2026-10-22).** jolt-lang patches are allowed under
   the pinned-SHA policy (§13.2), now carried via the owner's fork. The current
   fork pin and retired Sorted patch are recorded in §6.4. The re-pin is an
   explicit exception ahead of the first `qualify:jolt-upgrade` run, with the
   manual review section 6 checklist as its gate.
5. **J4 exclusions (2026-10-22).** The 14 namespaces blocked on J4 file seams
   move to the exclusion table with owner J4, rather than pulling those seams
   into J2. File random access, locking and UTF-8 decoding stay J4 work. The
   landed table still needs the ruling applied; J2 gate (b) remains open.
6. **PHP (2026-10-22).** PHP stays supported in the standard catalog and
   local shell/frontends acceptance (§3, J3/J5); no scope reduction.
7. **Mixed lock families — documented limitation (2026-10-25).** Owner
   ruling (option "document it" of the design's three-way, §3 cross-host
   limit, review F5): a Node session holding a store directory via its
   claim file and an OS-lock host (jolt, JVM, Dart) holding via fcntl are
   invisible to each other; simultaneous use of one store directory across
   the two families can double-own. Sequential reopen is unaffected and
   tested. The limitation is pre-existing (JVM↔Node) and recorded in
   known-gaps; J4b's acceptance must not claim universal cross-runtime
   exclusion. A future common-lock design or the best-effort Node-claim
   refusal remain available supersessions, not J4b scope.

---

## Appendix A. Review findings and where this document handles them

R1-R10 are the Opus review of r5
(`archive/jolt/1792425688000-architect-jolt-lane-plan-review-r5.claude-opus-5-5.findings.md`),
as disposed of in r7. W1-W9 are the Opus review of r7.

+---------+----------------------------------------------------------------------------------------+--------------------------------------------------------------------+
| Finding | Subject                                                                                | Handled in                                                         |
+=========+========================================================================================+====================================================================+
| R1      | Reuse the shared CST exporter through a jolt cst adapter; only raw ops-level data      | §7.3, §7.8, J0c                                                    |
|         | crosses FFI; no C++ Jing encoder                                                       |                                                                    |
+---------+----------------------------------------------------------------------------------------+--------------------------------------------------------------------+
| R2      | Stage-owned native worker, disposal, one-thread use, private simulators/DFA/caches,    | §7.4, J0c, J5                                                      |
|         | static-cache no-growth tests                                                           |                                                                    |
+---------+----------------------------------------------------------------------------------------+--------------------------------------------------------------------+
| R3      | UDP attribution corrected; landed transport-error plus lifecycle event; no             | §8.1, §8.3, J0b, J7, Q7                                            |
|         | queue/full/send wait                                                                   |                                                                    |
+---------+----------------------------------------------------------------------------------------+--------------------------------------------------------------------+
| R4      | Explicit Homebrew `openssl@3` / system `libcrypto.so.3` paths and linkage, pre-digest  | §6.3, J0a                                                          |
|         | version/`dladdr`, child-process negative tests                                         |                                                                    |
+---------+----------------------------------------------------------------------------------------+--------------------------------------------------------------------+
| R5      | `:blocking` pointer calls, concurrent full-GC tests, measured stack budget and         | §6.3, §7.5, §7.6, J0c, J5 (as modified by W5)                      |
|         | recursion guards                                                                       |                                                                    |
+---------+----------------------------------------------------------------------------------------+--------------------------------------------------------------------+
| R6      | Local-build-only generated-parser packaging; helper provenance, digests, notices;      | §7.2, §7.7, §6.5                                                   |
|         | `:cpp` positioned-token patches                                                        |                                                                    |
+---------+----------------------------------------------------------------------------------------+--------------------------------------------------------------------+
| R7      | Independently commissionable J0a/J0b/J0c; J0c early; both platforms to close           | §10.1, §10.2                                                       |
+---------+----------------------------------------------------------------------------------------+--------------------------------------------------------------------+
| R8      | Observe C++ token buffering; `:end`/`:early`/nil first-failure and probe parity        | §7.8                                                               |
+---------+----------------------------------------------------------------------------------------+--------------------------------------------------------------------+
| R9      | N=1200 plus a larger configured N, header constants and platform limits recorded,      | §8.3                                                               |
|         | EMSGSIZE distinct from truncation                                                      |                                                                    |
+---------+----------------------------------------------------------------------------------------+--------------------------------------------------------------------+
| R10     | Exact CMake pin at J0a; Q5-Q7 recorded; worker lifecycle replaces lifecycle denial     | §6.1, J0a, §13.1, §7.4                                             |
+---------+----------------------------------------------------------------------------------------+--------------------------------------------------------------------+
| W1      | Full WebSocket host contract and listener-side J6 gates                                | §9.2, §9.6; the 1009/1006 expectation is ruled (§13.4 ruling 3,    |
|         |                                                                                        | §9.6 matrix)                                                       |
+---------+----------------------------------------------------------------------------------------+--------------------------------------------------------------------+
| W2      | Transit missing from the plan                                                          | Owner chose CBOR-only (§13.1); the Transit coupling itself moved to |
|         |                                                                                        | the independent codec track (dependency statement §9.4, §10.1,     |
|         |                                                                                        | §13.4 ruling 1 withdrawn); J0a inventory, J2, J6, §12              |
+---------+----------------------------------------------------------------------------------------+--------------------------------------------------------------------+
| W3      | Native WebSocket cost understated; substrate below the shim                            | §9.3, §12; the substrate is ruled (§13.4 ruling 2)                 |
+---------+----------------------------------------------------------------------------------------+--------------------------------------------------------------------+
| W4      | RFC scope not falsifiable; missing protocol cases                                      | §9.5 (Autobahn oracle, groups 1-7 and 9; case and exception lists  |
|         |                                                                                        | pinned before J6, per astra), §9.6, J6 row                         |
+---------+----------------------------------------------------------------------------------------+--------------------------------------------------------------------+
| W5      | Keep the jolt cst surface synchronous; no new pending state                            | §7.5, J5                                                           |
+---------+----------------------------------------------------------------------------------------+--------------------------------------------------------------------+
| W6      | One rule for the three defaults                                                        | §13.2 (recorded disposition before applying; precondition, not     |
|         |                                                                                        | acceptance item); J0a precondition                                 |
+---------+----------------------------------------------------------------------------------------+--------------------------------------------------------------------+
| W7      | Initial pin is a main SHA; tag re-pin invalidates S evidence                           | §6.4 (untagged, nearest v0.8.23); keep ruled (§13.4 ruling 4)      |
+---------+----------------------------------------------------------------------------------------+--------------------------------------------------------------------+
| W8      | J0c start criterion and lowering scope                                                 | §7.8 (minimum baseline; restricted lowering corpus; J1 diffs       |
|         |                                                                                        | reported separately), J0c                                          |
+---------+----------------------------------------------------------------------------------------+--------------------------------------------------------------------+
| W9      | Linux x86_64 machine named as a precondition                                           | §10.2 and §13.1 (shiva via `~/ci/` scratch clones), §8.3 item 5    |
+---------+----------------------------------------------------------------------------------------+--------------------------------------------------------------------+

## Appendix B. Citations reopened for this document

Read on 2026-10-19 while writing; nothing was executed.

+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| Cite                                                 | Finding                                                                                                         |
+======================================================+=================================================================================================================+
| `src/cljc/dao/stream/ws.cljc:1-18`                   | Library-free boundary docstring; two subprotocols, exact offer; `dao.stream.transit` required at `:17`.         |
|                                                      | Confirmed.                                                                                                      |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljc/dao/stream/ws.cljc:493-501`                | Adapter map entries `:opened! :message! :binary! :closed! :too-large! :ws/codec :ws/max-frame-bytes :error!`.   |
|                                                      | Confirmed (review cited `:495-501`).                                                                            |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljc/dao/stream/transit.cljc:7-12`              | `:clj`, `:cljs` and `:default` select `cognitect.transit`; `:cljd` selects the port. Confirmed.                 |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljc/dao/data.cljc:11`                          | Requires `dao.stream.transit`. Confirmed.                                                                       |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljc/yin/repl/host.cljc:4-17,20-37`             | `{:connect! :bind! :unbind!}` contract; `:clj` selects `yin.repl.host.jvm`. Confirmed.                          |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljc/yin/repl/host/common.cljc:24-37`           | Missing-adapter text, `adapter?`, `binder?`. Confirmed.                                                         |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/clj/dao/stream/ws/jvm.clj`                      | 471 lines; `:134-138` unsendable close codes incl. 1009; `:231-239` failed connection answers `closed`;         |
|                                                      | `:443-452` `run-server` with no TLS options. Confirmed.                                                         |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljd/dao/stream/transit/cljd.cljd`              | 571 lines. Confirmed.                                                                                           |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljc/dao/stream/datagram.cljc:25-33,55-60`      | Send failure is transport-error plus send-failed; `:full` and `:refused` excluded; default 1200. Confirmed.     |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `mise.toml:8`                                        | `chezscheme = "latest"`; no CMake entry. Confirmed.                                                             |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljc/dao/data/btree.cljc:38-43`                 | Default ref type `:soft` on JVM, `:strong` on CLJS/CLJD. Confirmed at these lines; r7's `:44-50` has drifted.   |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljc/yin/vm/integer.cljc:348-350`               | `int-bit-not` as two `host/sub` calls. Confirmed.                                                               |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljc/dao/space/dht.cljc:53-57`                  | `:clj` selects `dao.stream.datagram.jvm`. Confirmed.                                                            |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljc/dao/data/arrays.cljc:145`                  | `java.util.Arrays/parallelSort`. Confirmed.                                                                     |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljc/dao/jing/cbor.cljc:19-20,537`              | Tag-27 float64, NaN `7ff8000000000000`; `Long/parseUnsignedLong` + `Double/longBitsToDouble`. Confirmed.        |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljc/yin/repl.cljc:275-279,814,2095`            | JVM banner arm; `create-state`; `eval-input`. Confirmed.                                                        |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljc/yin/repl/main.cljc:1306`                   | `-main`. Confirmed.                                                                                             |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljc/yin/repl/frontends.cljc:11-17`             | Requires include `yang.python.antlr.parser`. Confirmed.                                                         |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljc/yang/python/antlr/repl_probe.cljc:163,168` | Inline `cst/first-failure` calls. Confirmed.                                                                    |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `antlr/python3/manifest.edn:127-144`                 | Helper provenance UNRESOLVED; `:generated-artifacts :none`; blocked-until. Confirmed.                           |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `.cljstyle:14`                                       | Extensions lack `jolt`. Confirmed; `.clj-kondo/config.edn` has no `jolt` match.                                 |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `docs/agents/build-n-test.md:301-344`                | shiva and jupiter; `~/ci/` scratch clones; shiva snapshot lists no Chez or CMake. Confirmed.                    |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| jolt `host/chez/reader.ss:1102`                      | `rdr-features '("jolt" "clj" "default")`. Confirmed.                                                            |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| jolt `stdlib/jolt/socket.clj:12-23`                  | Recv error reads as EOF; nonblocking over `jolt.io-poller`. Confirmed.                                          |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| jolt `test/conformance/libs/common.clj:116-120`      | Apple BoringSSL aborts inside `EVP_Digest`. Confirmed.                                                          |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| jolt `LICENSE:1`                                     | Eclipse Public License v2.0. Confirmed.                                                                         |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+

Reopened 2026-10-20 for the §13.4 rulings (read; the only commands run were
read-only `git log -1`, `git tag --contains`, `git describe --tags` and
`git for-each-ref` in the jolt checkout).

+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| Cite                                                 | Finding                                                                                                         |
+======================================================+=================================================================================================================+
| `src/cljc/dao/stream/transit.cljc:1-156`             | `:cljd`/`:clj`/`:cljs`/`:default` require arms; `safe-number?`, `portable-value?`, `validate-portable`,         |
|                                                      | `valid-descriptor?` are codec-independent; `encode`/`decode` `:default` arms throw "no DaoStream v2 Transit     |
|                                                      | implementation" (`:112,123`); `profile` at `:136-145`.                                                          |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljc/dao/stream/ws.cljc:44,75,92,557,647`       | `transit/profile` as the composition default in `checked-codec`, one-arity `descriptor?`/                       |
|                                                      | `servable-descriptor?`, `make-endpoint` and the no-offer arm of `accept-connection!`; `:355-357` asks the seam  |
|                                                      | for 1009 `dao.stream/frame-too-large`; `:167-180` `send-result`; `:196-219` bounds over `:queued-bytes`.        |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljc/dao/stream/cbor.cljc:20,64`                | Requires `dao.stream.transit` for `safe-number?` only.                                                          |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljc/dao/jing/stream.cljc:26,37-39,57-67`       | Requires `dao.stream.transit` for `transit-lane?` (`(:ws/subprotocol transit/profile)`); unknown profile        |
|                                                      | throws.                                                                                                         |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljc/dao/data.cljc:11,58`                       | Requires `dao.stream.transit` for `portable-value?` only.                                                       |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/clj/dao/stream/ws/jvm.clj:134-138,165-192`      | `unsendable-close-codes` includes 1009; `abort!` reports the requested code locally and aborts the socket.      |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `test/dao/stream/ws_test.cljc:391-415`               | Shared too-large test: local `:ws/error :ws/frame-too-large`, `[1009 "dao.stream/frame-too-large"]` asked of    |
|                                                      | the seam, `append!` answers `closed`; frames built with `transit/encode`.                                       |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `test/dao/stream/ws/jvm_test.clj:667-689`            | JVM client oversize: peer receives zero close frames and sees `:eof`/`:reset`.                                  |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljs/dao/stream/ws/browser.cljs:84`,            | Browser sends 1008/1009/1013 as 1000 with the reason; Node `ws` `maxPayload` closes 1009 (docstring, R).        |
| `src/cljs/dao/stream/ws/node.cljs:33`                |                                                                                                                 |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| jolt `stdlib/jolt/socket.clj:1-30,349,719-724`       | Shim is the java.net object model over `jolt.socket.native` + `jolt.io-poller`; write loops over `c-send`       |
|                                                      | behind `poller/wait-ready fd :write`; recv error reads as EOF.                                                  |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| jolt                                                 | | `sock-stream`, `c-send`/`c-recv`/`c-accept`(`accept4`)/`c-poll`/`c-ioctl`/`c-fcntl` `defcfn`s, `eagain?`,     |
| `stdlib/jolt/socket/native.clj:60-70,127,236-275     | `connect-pending?`. Read, not run for TCP.                                                                      |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| jolt checkout git (read-only)                        | `96d7b866` = `main`/`origin/main` head, 2026-10-19, no tag; `describe` `vnightly-16-g96d7b866`; tags known to   |
|                                                      | the checkout: `vnightly` (5ae108e5, 2026-10-18), `v0.8.23` (a0ac71c3, 2026-10-17), `v0.8.22`, `v0.8.21`,        |
|                                                      | `v0.8.20`; working tree clean.                                                                                  |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+

Reopened 2026-10-20, later, for the Transit decoupling and then the codec
design (read only; `grep` over `src/` and `test/`; the codec design's own
citations are in `dao.stream.codec.md` Appendix A). Also read:
`archive/jolt/1792454716000-architect-jolt-design-doc-review.gpt-6-astra.findings.md`
(A1-A5 and the Autobahn point).

+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| Cite                                                 | Finding                                                                                                         |
+======================================================+=================================================================================================================+
| `src/clj/dao/stream/ws/jvm.clj:25,36-38,333-334,`    | Requires Transit; `default-codecs [transit/profile]`; `ws/subprotocol` fallback; `listen!` `(or (seq codecs)    |
| `413-436`                                            | default-codecs)`; a no-offer upgrade is refused before completion.                                              |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljs/dao/stream/ws/node.cljs:36,48-50,210,`     | Same shape; `verifyClient` answers 400 `dao.stream/subprotocol-required` when nothing negotiates.               |
| `249-282`                                            |                                                                                                                 |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljd/dao/stream/ws/dart.cljd:22,26-28,143,`     | Same shape; `handle-request!` rejects an upgrade that negotiates nothing.                                       |
| `221-238`                                            |                                                                                                                 |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljs/dao/stream/ws/browser.cljs:108`            | `ws/subprotocol` fallback only; no Transit require.                                                             |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljc/dao/stream/remote_channel.cljc:339-351,`   | `make-endpoint` without `:codecs`; `(:bind! host)` without `:codecs`; `make-attacher` without `:codec`.         |
| `385-394,823-827`                                    |                                                                                                                 |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljc/yin/repl/serve.cljc:240-247`,              | The production `remote-channel/serve` and `dial` call sites; no codec in either spec.                           |
| `src/cljc/yin/repl/connect.cljc:302-304`             |                                                                                                                 |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljc/dao/stream/ws.cljc:504-542,554-588,`       | `make-attacher` "`:codec` ... (default: Transit)"; `make-endpoint` `(or codecs [transit/profile])`;             |
| `625-652`                                            | `accept-connection!` nil offer -> `transit/profile`, unknown offer -> `:ws/unsupported-subprotocol`.            |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `src/cljc/dao/stream/cbor.cljc:1-78,115`             | Own widened `portable-value?`; uses `transit/safe-number?` only; literal subprotocol string.                    |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `test/` grep for `dao.stream.transit`                | 22 requiring test namespaces (not 20 as an earlier brief said); 13 namespaces call                         |
|                                                      | `make-attacher`/`make-endpoint` (59 sites); 7 set a seam `:ws/subprotocol`; `loopback_net.cljc:164` encodes     |
|                                                      | Transit and sets no seam subprotocol.                                                                           |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `test/dao/stream/transit_test.cljc:15-57`            | Predicate cases (portable domain, safe-integer bounds) that move to `dao.stream.portable-test`.                 |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
| `test/dao/stream/journal_test.cljc:6,354`            | Uses `dao.stream.cbor/validate-portable`; unaffected.                                                           |
+------------------------------------------------------+-----------------------------------------------------------------------------------------------------------------+
