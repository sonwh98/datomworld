# Review: the jolt host-lane implementation plan

Reviewer: claude-fable-5-1, independent (did not see the author's session).
Under review: `collab/1792421958748-architect-jolt-lane-plan.gpt-6-astra.findings.md`.
Evidence base read first: the verified spike report and its artifacts under
`build/jolt-spike/` (overlay/, blake3.clj, entry.clj, parity.clj,
parity.jolt.txt, climb.sh) and the jolt checkout at `96d7b866`.

Method: every V citation in the plan was opened at the cited line range in
this tree (src/cljc, src/clj, test/, deps.edn, bb.edn, mise.toml,
docs/design/yang.antlr.md, docs/jolt-experiment.md) and in the local jolt
checkout (host/chez/reader.ss, host/chez/loader.ss, jolt-core/jolt/deps.clj,
stdlib/jolt/ffi.clj, README.md, Makefile). No code was run; the spike's
parity trace was read as recorded.

## Verdict

**APPROVE WITH CHANGES.** The plan is sound in structure and doctrine: one
shared-source lane, no second codec, no forked engine, conformance by the
engine §E.5 oracle, FFI and workers as implementation plumbing under
dao.stream. The CBOR float correction is confirmed. The bit-op algorithm is
correct. The citations are honest: one V claim is mislabeled (uncommitted
file), one is a wrong aside about the spike, one range overshoots a file.
The changes required are: (F1) fix the CBOR failure diagnosis and the wire
writer's node contract; (F2) correct the frontend option space and owner
question 3; (F3) add an owner question on JVM sidecars at parity; (F4) state
the root-ordering rule the loader evidence already settles; plus small
completeness items.

## Findings

| # | Severity | file:line | Evidence | Correction |
|---|---|---|---|---|
| F1 | medium | plan §4 "Jing CBOR" (`plan:94-96`); `build/jolt-spike/parity.jolt.txt`; `src/cljc/dao/jing/cbor.cljc:991-1012`; `src/cljc/dao/jing/cbor/boring.cljc:58-124` | The float correction itself is right: `cbor.cljc:19-20` documents tag 27 `dao.jing/float64` over eight big-endian bytes with canonical NaN; `float-wire:991-1012` builds exactly that frame; `float64-from-bits:1448-1452` decodes it; the conformance walker rejects native floats at `cbor_conformance_test.cljc:127` (comment) and `:415` (`(<= 25 ai 27) (err! "native CBOR float")`). So "finish f16/f64 framing" must not mean shortest-width CBOR floats. **But the plan's account of the spike failure is imprecise and its replacement instruction is incomplete.** The wire layer never sees a float. The recorded trace shows the `:clj` ByteBuffer branch of `float-wire` *succeeded* on jolt and the crash is in the overlay's `enc*` at `overlay/dao/jing/cbor/boring.cljc:50`: "Don't know how to create ISeq from: java.lang.Long", i.e. `enc*` was handed the raw host `byte[]` payload of the float64 frame and tried to walk it as a node sequence. The production `boring.cljc` node constructors are all identity on host values (`null`→nil, `text`→s, `byte-string`→bs, `integer`→n, `array-node`→nodes, `frame`→`tagged-literal`), so the wire tree's vocabulary is **host values**, not tagged tuples; frame payloads from `float-wire` and `magnitude-bytes` outputs arrive as raw byte arrays. | Rewrite the §4 paragraph: (a) the spike failure was the overlay wire writer not accepting a `byte[]` where a node is expected, not an unfinished float encoding; (b) the `.jolt` wire writer must implement the same host-value vocabulary as `boring.cljc:58-132` (nil, booleans, strings, byte arrays, integers in [-2^64, 2^64), vectors, maps, `TaggedValue`-equivalent, `tagged-literal` frames) and treat a byte array as a byte string at any node position; (c) name the decode-side host branches explicitly in the J1 gate: `cbor.cljc:537` (`Long/parseUnsignedLong` + `Double/longBitsToDouble`) and `float-fields:649-652` (`Double/doubleToRawLongBits` + 64-bit `bit-and`), since "decode remains the shared parser" is only true above those two host seams. The §4 range `462-658` nominally covers them; the gate text should not. |
| F2 | medium | plan §7 and §10 Q3 (`plan:138-140, 215`); `docs/design/yang.antlr.md:496-500, 3229-3231`; `bb.edn:28-31, 42-44`; `deps.edn:39, 86-91` | The plan's reading of yang.antlr.md is correct: §4.1 (`:472-509`) specifies a stream-based local-or-service parsing contract; `:3229` says no legacy fallback; `:4004-4006` records the legacy Python frontend deleted 2026-10-18; `src/cljc/yang/python.cljc` is indeed gone; `parser.cljc:18-22` imports generated Java classes under `:clj` and `frontends.cljc:15-17` requires it unconditionally, so removing one require does not fix the graph. Rejecting a new handwritten or CPython-AST parser is right. **However the option space is under-stated.** `yang.antlr.md:3230` and the table at `:496-500` say the Python grammar is generated for JVM, Node *and* Dart, and the repo already builds a Dart AOT native binary (`bb build:yin-repl-peer`, `bb.edn:28-31`) and a Node bundle (`build:yin-repl-node`) as lane prerequisites. A Node or Dart-AOT parse worker is the same stream contract with a non-JVM runtime, already validated on its own lane. Therefore Q3's sentence "A no-JVM packaging requirement changes the parser implementation project" is wrong: it changes which existing generated target the worker runs, a configuration choice, not a parser project. | In §7 make the worker host a J5 parameter (JVM / Node / Dart AOT) over one stream contract; recommend defaulting to whichever target is already a prerequisite of the lane being run, and note the Dart AOT peer binary is the only option that yields a JVM-free, Node-free process tree. Reword Q3 to ask which worker runtime(s) to qualify first, not whether a no-JVM requirement forces a new parser. |
| F3 | medium | plan §6 WebSocket row and §10 (`plan:127, 209, 211-217`); spike §6 (`spike:90`); memory invariant "dao.stream P2P, no server/client, no privileged node" | J5 (parser) and J6 (WebSocket) both choose an out-of-process **JVM** adapter as the first production implementation, and J7 defers UDP. A J8 "parity" jolt node would then need a JVM process beside it to parse Python and to dial or serve WebSockets. This is legitimate implementation plumbing under dao.stream (the plan is careful: canonical data, identity correlation, no raw socket crosses), but it negates the spike's stated reason to prefer jolt over Rust (a standalone native binary with the same source) and weakens the P2P invariant in practice: a peer that cannot dial without a sidecar is a lesser peer. The plan only says a later direct Chez implementation "must pass the same adapter suite". | Add owner question 6: "Does host-lane parity (J8) permit JVM sidecars in the jolt process tree for parsing and WebSocket, or must J8 require the direct Chez/libsocket WebSocket implementation and a non-JVM parse worker? If sidecars are permitted, the J8 label must say so." Also state in §2 that the local-REPL milestone label must disclose "requires a configured parser worker runtime". |
| F4 | low | plan §3 (`plan:61-63`); `host/chez/loader.ss:389-395, 472-481`; `jolt-core/jolt/deps.clj:1744-1746, 1776-1782` | Reader features `("jolt" "clj" "default")` at `reader.ss:1102` and extension order `.jolt .clj .cljc` at `loader.ss:338` are confirmed. The plan's ":jolt before :clj before :default" and ":cljd first where ClojureDart needs it" are the right discipline. The plan leaves "same-file-path precedence and cross-root ordering" as a J0 probe, but the evidence it already cites settles it: `ldr-root-source` (`loader.ss:391-395`) tries the three extensions **within one root**, and `resolve-on-roots` walks roots outermost (`:474-481`); deps.clj says "the loader takes the first root that has a namespace, so the order decides which copy wins" (`:1745-1746`). So a `foo.jolt` in `src/jolt` does **not** win over `foo.cljc` in `src/cljc` unless `src/jolt` precedes `src/cljc` in the selected paths. Also `:extra-paths` always precede `:replace-paths` (`:1776-1782`). | State the rule now: in the `:jolt` alias list `src/jolt` before `src/cljc` and `src/clj`, and verify with `-Spath`; keep the J0 probe only for the `:jolt/provides` / `ldr-ns-replaced-rel?` precedence (`loader.ss:467-473`) and for built binaries (embedded roots probe first, `:474-477`). |
| F5 | low | plan §8 (`plan:150`); `mise.toml:8`; `git diff mise.toml` | `chezscheme = "latest"` is present at `mise.toml:8` but is an **uncommitted working-tree change** (the spike report §1 also says the file shows modified and it did not touch it). Labeling it V "confirms the selector" without saying it is unstaged overstates what the tree records. | Mark it "V, uncommitted"; make committing `mise.toml` part of J0 so the toolchain pin is tracked before any lane result is recorded. |
| F6 | low | plan §3 table (`plan:70`); `src/cljc/dao/jing.cljc:65, 79, 118, 131`; spike §5 item 3 | The plan says the spike "cites older line offsets" for the jing.cljc hash sites. The spike cites `jing.cljc:114,128` (the `defn` lines of `blake3-bytes` and `blake3`, current) and `:65,79` (the SHA `MessageDigest` calls, current). Both are correct today. | Drop the aside; the plan's own `59-85, 114-136` ranges are fine. |
| F7 | low | plan §3 table (`plan:76`); `src/cljc/yin/repl/host.cljc` (38 lines) | Cited `host.cljc:15-18, 22-45`; the file ends at line 38. Content described (require of `yin.repl.host.jvm` under `:clj`, `websocket` returning `(jvm/websocket)`) is correct at `:15-17` and `:20-37`. | Fix the range. Cosmetic. |
| F8 | low | plan §6 rows "WebSocket" and "UDP" (`plan:127-128`); `src/clj/dao/stream/datagram/jvm.clj`, `src/clj/dao/stream/ws/jvm.clj`, `src/clj/yin/repl/host/jvm.clj` | The plan names `dao.stream.datagram.jvm` and `host.jvm` as namespaces not to load on jolt but gives no paths. They are `.clj` files under `src/clj`, which the plan's `:replace-paths` proposal (§2) selects as "audited src/clj content (including the demo)". Pulling `src/clj` wholesale puts these three on the jolt classpath; they are reached only through `:clj` branches, so the `:jolt` arms in `yin.repl.host`, `dao.stream.datagram`, and `dao.stream.ws` are what keep them unloaded. | Cite the paths and make the J0 inventory list every `src/clj` namespace that the selected `:clj` branches reach, with its `:jolt` guard. |
| F9 | low | plan §2 "Build declaration" and §9 J2 (`plan:53, 193`); `docs/jolt-experiment.md:70`; `.cljstyle` `:files :extensions`; `.clj-kondo/config.edn` | Three lane obligations are missing. (a) The older experiment recorded that an uncaught error inside one test killed the whole `run-tests` batch (`jolt-experiment.md:70`); the J2 runner requirement "nonzero exit on load/test/error failures" does not cover "one escaped exception must not abort the batch". (b) `.cljstyle` enumerates extensions `#{"blog" "chp" "clj" "cljc" "cljd" "cljs" "edn"}`; `.jolt` files would be skipped by `bb fmt`. (c) `.clj-kondo/config.edn` has no `:jolt` feature knowledge; the kondo lane in build-n-test.md needs to be checked against `:jolt` reader-conditional branches. | Add to J2: a runner probe where one test throws and the remaining tests still run and report. Add to J0: `.jolt` in `.cljstyle` extensions and a kondo run over a file with `:jolt` arms. |
| F10 | low | plan §9 J8 and §9 last paragraph (`plan:199, 203`) | J8's acceptance includes "measured performance" while §9 says thresholds are set only after measurement. As written the gate cannot fail on performance, so it is a report, not a falsifiable gate. The rest of J0–J8 acceptance is falsifiable (named suites, counts, byte equality, exit codes). | Say plainly that J8 records benchmarks and sets thresholds; a performance regression gate becomes falsifiable only in the slice after J8. |
| F11 | info | plan §4 bit ops (`plan:86-88`); `src/cljc/yin/vm/integer/host.cljc:240-299` | Verified correct, see "Properties that pass". One omission worth a sentence: the host namespace has no `bit-not`; Python `~x` therefore reaches jolt only as `(- -1 x)`. The gate corpus should include `~x` and the identity `x XOR -1 = ~x` so a future `bit-not` helper cannot regress silently. | Add `~x` and `x XOR -1 = -(x+1)` to the §4 identities. |

## Evidence-honesty spot checks that pass

All opened at the cited lines and found as described:
`cbor.cljc:19-20, 112-250` (`big`, `b+`, `b-`, `b*`, `bquot`, `bcmp`, `bsign`,
`bgcd`, `bpow`, `b->int:223`, `magnitude-bytes:235`), `:462-470`, `:639-659`,
`:991-1012`, `:1448-1452`; `cbor_conformance_test.cljc:127, 415`;
`integer/host.cljc:27-28` (import), `:41-108`, `:115-299`;
`jing.cljc:59-85, 114-136`; `boring.cljc:32-41, 46-132`; `arrays.cljc:92`
(arraycopy), `:138-148` (`parallelSort` at `:145`); `btree.cljc:40-44`
(`:soft` on JVM, `:strong` elsewhere); `repl.cljc:275-279` (banner), `:364`
(print-method), `:814` (`create-state`), `:900-909` (empty catalog default),
`:958`, `:2095` (`eval-input`); `frontends.cljc:11-18, 54-86`;
`main.cljc:11-30` (eager `fs`, `datagram`, `sign` requires), `:199-241`,
`:718` (`--port`, no `--serve`), `:873-880`, `:951-959`, `:1212-1359` (JVM
arm, `-main` at `:1306`, `Thread`/`Runtime` use); `demo.clj:176` (`run-demo`),
`:275-281` (5050); `file.cljc:159-165` (`RandomAccessFile`);
`sign.cljc:69-72, 90, 127-136` (SunEC Ed25519); `sign_test.cljc:29-32`;
`yang/python/antlr/parser.cljc:18-22`, `repl_probe.cljc:26-29`,
`repl.cljc:26-32`; `yang.antlr.md:187-189, 472-509, 3229-3231, 4004-4006`;
`jolt-experiment.md:11-21, 60-75` (v0.8.3); `deps.edn:1-23, 30, 66`;
`bb.edn:34-90, 96-170`; `test/resources/dao/jing/{cbor-v1.json,
cbor-v1.README.md, cbor-v1.errata.md, blake3-vectors.edn, digest-table.edn}`;
`hash_registry_contract_test.cljc:20-21, 51-58`; `store/fs.cljc:1-40`
docstring (exclusive lock, temp+sync+rename, directory sync); engine.md §E.5
at `:707`; register.md §10.4 at `:1422`; `src/dev/affected.clj`,
`src/dev/subsystems.edn`, `yin/repl/serve.cljc` present; jolt checkout HEAD is
`96d7b8662d7c491722445bf976b2e25e36ce5d63`; `reader.ss:1102`;
`loader.ss:332-338`; `deps.clj:1-9, 998-1003, 1735-1794`;
`ffi.clj:17-20` (types incl. `:pointer :size_t :uint8`), `:91-101`
(RTLD_LOCAL note), `:103-145` (arenas), `:147-160` (`write-array` /
`read-array` / `read-into!` byte-array copies), `defcfn` at `:1664`;
README `:95-99` (JOLT_CHEZ precedence, make provisions 10.4.1), `:109-113`
(OpenSSL via FFI for Maven; unfetchable dep "skipped, never fatal"),
`:134-166` (`-Sdeps`, `-A`, `-M`, `-Spath`, `-Stree`, `-Srepro`, undeclared
alias warns); Makefile `:110-115` (provisioned GCC, `JOLT_CC`), `:1901-1902`
(`devboot`). The spike artifacts (`parity.clj`, `parity.jolt.txt`,
`overlay/dao/jing/cbor/boring.cljc`) match the spike's S claims; the overlay
has a dead `enc` `:map` branch that returns nil, which the plan correctly
never promotes.

The plan's proposed names (`docs/design/yin.vm.jolt.md`, `:jolt` aliases,
`bb bootstrap:jolt`, `jolt-toolchain.edn`, `build/tools/jolt`) do not exist
in the tree, consistent with their P label.

## Technical properties that pass

- **CBOR float contract.** Confirmed as in F1: tag 27 `dao.jing/float64`,
  eight big-endian bytes, NaN `7ff8000000000000`, native floats rejected.
  The plan's instruction to keep native CBOR float encodings as rejection
  fixtures and never regenerate expected bytes is the right one.
- **Exact-integer bit ops.** With `w = 1 + max(bitlen|a|, bitlen|b|)` both
  operands lie in `[-2^(w-1), 2^(w-1)-1]`, so `a mod 2^w` is the exact w-bit
  two's-complement image. Bits above `w-1` of each operand all equal its
  bit `w-1`, and AND/OR/XOR are bitwise, so the result's bits above `w-1` all
  equal the result's bit `w-1`: subtracting `2^w` when that bit is set is the
  exact sign extension. Zero (`bitlen 0 = 0`), both zero (`w = 1`), `-2^k`
  (`bitlen 2^k = k+1`, fits), mixed signs, and `x XOR x = 0`, `x AND -1 = x`
  all hold. Left shift as `a * 2^k`, right shift as truncating quotient with
  one decrement when `a < 0` and the remainder is nonzero, is floor division.
  No edge found wrong. Cost is O(w²) per op by digit consumption; the plan
  correctly defers a limb implementation behind the same tests.
- **Reader discipline.** `:cljd` first, then `:jolt`, then `:clj`, then
  `:default` is correct for every host: ClojureDart's host-eval pass only
  has `:clj`, and JVM/CLJS/CLJD readers skip the unknown `:jolt` feature.
  `.jolt` resolves before `.clj`/`.cljc` within a root (F4 for the
  cross-root rule).
- **FFI approach.** `jolt.ffi` provides `defcfn` with `:pointer`,
  `:size_t` and byte widths, confined arenas, and one-copy
  `write-array`/`read-array`; a wrapper `(in, len, out[32]) -> int` over
  libblake3 init/update/finalize is expressible with no `:string` argument
  and no guest-visible pointer. The RTLD_LOCAL note at `ffi.clj:91-101`
  supports building libblake3 as a shared library. libcrypto EVP one-shot
  `EVP_Digest` with `EVP_sha256()`, and `EVP_PKEY_new_raw_private_key`
  (Ed25519) plus `EVP_DigestSign`/`EVP_DigestVerify`, give exact raw 32-byte
  seeds and 64-byte detached signatures as `sign.cljc` requires. The plan is
  right that README:110 proves jolt dlopens *a* libcrypto, not that this
  project may rely on its version; J0 must record the path and version jolt
  actually loads (macOS ships LibreSSL and no headers).
- **Frontend reading.** Correct as far as it goes (F2 for the missing
  options).
- **Architecture.** dao.stream remains the one boundary: the worker carries
  canonical Jing values with identity correlation and no callbacks; FFI
  stays inside narrow host facades; no third codec; engine §E.5 is the only
  oracle; no forked engine; spike overlays are removed from production
  classpaths.
- **Completeness.** The entry-path table (API, standard catalog, main,
  demo, browser/Flutter demos staying on their hosts) is the right set. All
  `--vm` modes, `(host)`, `(lang …)`, reset, index publication, custody flush
  on EOF, and the pending-compilation driver state for a service-backed
  frontend are all named. Changed/subsystem lanes extend `affected.clj`
  rather than fork it. Zero-unexplained-skips with an applicability
  manifest is the right standard.
- **Two milestones.** Honest, subject to F3's disclosure of sidecar
  dependence in both labels.

## Owner questions

Q1, Q2, Q4, Q5 are the right questions. Q3 must be reworded per F2. Add Q6
per F3 (JVM sidecars at parity). Nothing else material is left unasked.
