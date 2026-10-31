Created-GMT: 2026-10-19 14:59:18 GMT
Created-Local: 2026-10-19 21:59:18 +07

# Task: implementation plan — the jolt host lane for yin.repl, on par with clj/cljs/cljd

Role: Lead System Architect (design authorship; per this seat's routing)

## Context and evidence base

A feasibility spike (claude-opus-5.5, 2026-10-19, session 8f45af4f) VERIFIED that
yin.repl runs under jolt (Clojure on Chez Scheme):
- Full 91-namespace graph loads with ~150 lines of overlay (4 files in
  build/jolt-spike/overlay/); guest REPL evaluates via the semantic VM
  ((+ 1 2)=>3, closures, (sq 12)=>144); content-hash bit-identical to JVM on
  6/6 sampled values.
- READ the SPIKE REPORT FIRST — it is the evidence base:
  collab/1792412960461-engineer-jolt-yin-repl-spike.claude-opus-5-5.findings.md
- Key facts: jolt reader feature set is {:jolt :clj :default} (host/chez/reader.ss:1102)
  — .cljc loads unmodified, :jolt branches slot BEFORE :clj; a .jolt file
  overrides .cljc. jolt reads the repo deps.edn (tools.deps dialect: -Sdeps/-A/-M/
  -Stree; git deps supported). Chez 10.4.1 is mise-pinned (chezscheme="latest",
  installed). jolt pinned as clone at main 96d7b866 (JOLT_CHEZ env). jolt is NOT
  a deps.edn dependency (no coords published).
- Verified gap catalog (from the spike): BigInteger helpers in dao.jing.cbor;
  yin.vm.integer.host needs ~25 branches AND bigint bit-ops >2^63 are silently
  wrong on jolt (bit-and/bit-or/bit-xor/shift truncate; must be rewritten
  arithmetic/algorithmic); dao.jing hashes (BLAKE3 pure-Clojure 70-line exists,
  matches official vectors + JVM; SHA-256 stubbed); dao.jing.cbor.boring wraps
  the Java boring jar (pure-Clojure canonical CBOR wire exists, floats
  unfinished); Arrays/parallelSort missing (dao.data.arrays:145, breaks the code
  index publish); ~15 interop-heavy namespaces unexercised (dao.jing.file,
  dao.space.store.fs, dao.data.btree, linker.sign ed25519, datagram/ws
  networking); yin.repl.frontends requires Java ANTLR (yang.python.antlr.*) so
  the standard frontend catalog cannot load — spike hand-built a Clojure-only
  catalog; (host) banner prints "JVM " (System/getProperty shim returns nil).

## Owner rulings already in force (binding)

- dao.stream is the one abstraction boundary; a host lane's boundary work is an
  IMPLEMENTATION of it (docs/design/datom.world.abi.md). The jolt lane adds no
  ABI, no second protocol.
- BLAKE3 on jolt: bind the C reference libblake3 via Chez/jolt FFI (owner
  direction); the pure-Clojure version is the correctness oracle/interim.
- Chez via mise "latest"; jolt from GitHub main (clone; no coords exist yet).
- The conformance method is host-agnostic (docs/design/yin.vm.engine.md §E.5):
  a new lane joins the differential lanes (CLJS reference oracle, Rust, wasm)
  rather than inventing its own kit.

## The plan you are to write

An implementation plan to make yin.repl work on jolt ON PAR with the clj, cljs,
cljd lanes — meaning: loads from tracked src/ with no overlay, the standard
entry paths work, the cross-host test lanes run green under jolt in the repo's
build (bb tasks), and hash/encoding parity is gated, not spot-checked.

Required plan elements:

1. **Lane definition**: what "on par" means precisely — the entry points
   (yin.repl create-state/eval-input, the -M main if any, the demo), the test
   lanes to run (which of bb test:* apply; what a test:jolt task looks like;
   what cannot apply and why), and where the lane is declared (deps/paths
   wiring for jolt — note jolt reads the root deps.edn: :paths/:aliases).
2. **The :jolt branch discipline**: the reader-conditional strategy
   (:jolt-before-:clj; .jolt override files — when to use which), the fold of
   the spike overlays into tracked src/ (cbor big-int helpers, integer/host,
   jing hashes, cbor.boring), and the branch inventory with file:line
   precision from the spike.
3. **Correctness work the spike flagged**: bigint bit-ops rewrite
   (algorithmic bigAnd/bigOr/bigXor/shifts on Chez bignums — specify the
   approach), canonical CBOR floats (f16/f64 framing per dao.jing.cbor's
   contract — match the JVM boring wire byte-for-byte), SHA-256 (FFI libcrypto
   vs pure — recommend), parallelSort replacement (stable sort equivalence —
   is determinism affected?), the (host) banner branch.
4. **BLAKE3 via C FFI**: the build/distribution story for libblake3 (vendored
   C compiled when? mise? jolt's ffi loader?), the defcfn/foreign-procedure
   binding, and the test-vector + cross-host gate (jing content-hash fixtures
   already exist? else add to the kit).
5. **The interop-heavy remainder, tiered**: for dao.jing.file / store.fs /
   btree / linker.sign / datagram-jvm/ws — decide per module: :jolt branch
   (portable equivalent), FFI binding, out-of-process adapter, or deferred
   with the lane declaring the capability gap. ed25519 (sign) is
   security-critical: specify the approach and its verification. The
   datagram/ws networking: recommend scope (the P2P/DHT surface may be
   explicitly out of the first lane cut — say so if so).
6. **Frontends**: the ANTLR problem. Evaluate: (a) out-of-process frontend
   adapter (subprocess JVM/Python speaking the frontend protocol — the repo
   has process patterns: yin.repl --serve, dht-process-test, R5 peer), (b)
   Clojure-only catalog for the jolt lane initially, (c) an ANTLR-free python
   parser path if one exists in the yang.antlr design. Recommend one, with
   the (lang :yang/python) experience as the acceptance.
7. **Toolchain and repro**: mise chezscheme pin (done), jolt pinning strategy
   (clone sha recorded where? a mise git dep? JOLT_CHEZ convention), devboot
   (optional precompile; note the xPack GCC provisioning), and the repro
   commands from the spike promoted into a doc.
8. **Phasing**: slices J0..Jn in the repo's style (each falsifiable, with
   acceptance gates; e.g. J0 = overlays folded + tracked-load green; J1 =
   hash/cbor parity fixtures green; J2 = test:jolt lane running the pure
   suites; ...). Name the completion criterion for "on par".
9. **Risks and open owner questions**: jolt single-maintainer risk,
   performance unknowns (no benchmark yet — where one belongs), bigint
   bit-op semantics divergence in jolt itself (upstream fix vs our branch),
   upstream deps.edn coordinates absence, ANTLR frontend cost, determinism
   surfaces (set printing order was noted divergent).
10. **Where the plan lives**: propose the committed doc name following the
    repo's conventions and this seat's naming doctrine (name the scope; the
    lane is a host lane for yin.vm/yin.repl — e.g. is it yin.vm.jolt.md?
    Decide and justify briefly). Do NOT write the committed doc in this task;
    write the plan to collab (below) and the distillation is a follow-up.

## Constraints

- Read-only exploration plus the single findings file. Do not edit src/, test/,
  docs/, or anything tracked. collab/ only.
- Ground every claim in the spike report or the repo; where you verify
  something new (grep/read), say so; where you assume, mark it.
- Write the plan to:
  collab/1792421958748-architect-jolt-lane-plan.gpt-6-astra.findings.md
