# jolt / yin.repl spike: findings (round 2, VERIFIED by running)

Result in one line: **with about 4 small overlay files, `(require 'yin.repl)` loads whole under jolt (91 namespaces), `create-state` works, and `eval-input` evaluates `(+ 1 2)` => 3 and `(sq 12)` => 144.** Content hashes match the JVM on 6 of 6 sampled values.

## 0. History
- Round 1 (acceptEdits only): BLOCKED at phase 0. curl/gh/compound commands were denied, and the brief had the wrong repo (`lotuc/jolt`). RESOLVED in round 2.
- Round 2: this report.

## 1. Environment
- jolt: https://github.com/jolt-lang/jolt, main at `96d7b8662d7c491722445bf976b2e25e36ce5d63` (2026-10-19 08:26 -0400). Cloned to `build/jolt-spike/jolt`; submodules initialised (`irregex`, `process`, `sci`).
- Chez Scheme **10.4.1**, installed by `mise install chezscheme`: `~/.local/share/mise/installs/chezscheme/10.4.1/bin/scheme`. jolt did NOT provision its own Chez.
- Run command: `JOLT_CHEZ=<that scheme> build/jolt-spike/jolt/bin/jolt ...`. `bin/jolt` runs from source with no build step, because the bootstrap seed is checked in. A trivial `-e` costs about 7s on the first run and about 0.2s once cached.
- **jolt is not a library you depend on via deps.edn.** The jolt repo has no `deps.edn`, so there are no `io.github.jolt-lang/jolt` coords, and there is no `:jolt` alias. jolt is a runtime/CLI. "Main via deps.edn" can only mean the git checkout (the clone above). I did not use brew or release binaries.
- jolt **does read this repo's `deps.edn`**: it resolved `org.replikativ/boring` from `~/.m2` and loaded the jar's `.clj` source.
- Scratch files, all under `build/jolt-spike/`:
  - `overlay/` has the patched namespaces
  - `blake3.clj`
  - `climb.sh`, `entry.clj`, `parity.clj`
  - `smoke.clj`, `smoke2.cljc`
  - `parity.*.txt`
- No tracked `src/`, `test/` or `docs/` file was modified by me. `mise.toml` and `docs/orchestrator-log.md` show as modified in git status, but I did not touch them.

## 2. jolt capability inventory (run)
Worked, as verified by `smoke.clj` and the README claims that I ran:
- `defprotocol`, `defrecord`, `deftype` (with protocol methods)
- `atom`, `volatile!`/`vswap!`
- lazy infinite seqs
- `clojure.edn/read-string`
- `ns` with `:require :as :refer`, in a `.cljc` file
- `java.util.Arrays/copyOfRange`
- `ByteBuffer/allocate`+`putDouble`
- `Integer/toHexString`
- `StandardCharsets`
- `String.` from bytes
- `System/nanoTime`
- `(byte-array ...)`/`aget`/`alength`/`bytes?`
- bignum arithmetic (`+'`, `*'`, `quot`, `rem`, `compare`)
- `.toString` of a bigint with a radix

Gaps found, with the smallest repro:
- No `java.security.MessageDigest`. Repro: `(java.security.MessageDigest/getInstance "SHA-256")` fails with "No dependency provides ... :jolt/provides (RFC 0014)".
- `BigInteger` is not a class. Repro: `(BigInteger. "12")` and `(.add ...)` fail with "no matching method add for java.lang.Long", and `BigInteger/valueOf` is missing. Bignums are native Chez integers instead.
- `bit-and`/`bit-or`/`bit-xor`/`bit-shift-left` on bigints: `(bit-and 18446744073709551615N 255N)` gives "bit operation not supported for clojure.lang.BigInt". `(bit-shift-left 1N 100)` returns 2^36, the same silent long truncation as the JVM.
- `java.util.Arrays/parallelSort` is missing (used by `dao.data.arrays`).
- `.toByteArray` on a bigint is missing.
- JVM-only Java classes cannot load. Repro: `io.github.rctcwyvrn.blake3.Blake3` fails with "Unknown class", and `org.replikativ.boring.Writer` fails with "No matching ctor found for class Writer".
- Printing of sets: iteration order differs from the JVM (`#{1 3 2}`). This is harmless unless something depends on it.

## 3. .cljc / reader conditionals
Verified by running and by reading `host/chez/reader.ss:1102`: jolt's feature set is **`{:jolt :clj :default}`**, and the first matching branch wins. So `.cljc` loads with no preprocessor, and `:cljs`/`:cljd` branches are never taken. `#?(:cljs ...)` falls through to `:default`. Put a `:jolt` branch BEFORE `:clj` in any form that needs a jolt-specific port, and `:clj` code runs unchanged where it works. A `.jolt` extension also exists and wins over `.cljc`, so a jolt override file can live next to a portable one. **No shim needed**, and the existing `#?(:cljd nil :clj ...)` discipline already puts `:jolt` insertion in a safe order.

## 4. Hash/FFI gap analysis
- Which hash library the JVM uses: BLAKE3 is `io.github.rctcwyvrn/blake3` (a Java lib), and SHA-256 is `java.security.MessageDigest`. Neither exists in jolt.
- BLAKE3: I wrote a **pure-Clojure BLAKE3 (about 70 lines, `build/jolt-spike/blake3.clj`)**. It matches the official vectors for empty input and "abc", and the JVM library for a 3000-byte multi-chunk input (`6c943946…`). It is slow (vectors of longs). A production version would want an FFI binding to C `libblake3`, or a Chez `bytevector`-based implementation.
- SHA-256: **not done.** The spike stubs it. Options are the FFI to libcrypto (jolt already loads OpenSSL by FFI for Maven) or a pure port.
- CBOR wire: `dao.jing.cbor.boring` wraps the Java `Writer` in the boring jar. I replaced it with a **pure-Clojure canonical CBOR wire (`overlay/dao/jing/cbor/boring.cljc`, about 60 lines)**: nulls, booleans, text, bytes, ints, arrays, maps with bytewise key sorting, and tags. It does not implement **floats**, which is why my parity run stopped at the 7th value `{:b [1.5 true nil]}` (a crash in my encoder, not in jolt).
- Parity result: `dao.jing/content-hash` of `nil`, `1`, `-5`, `"héllo"`, `:k/w`, and `[1 2 {:a #{1 2 3}}]` is **bit-identical to the JVM** (`parity.jolt.txt` vs `parity.jvm.txt`, lines 1 to 6). The remaining five values (float, list, 2^64-1, bytes, symbol) were not reached.

## 5. Climb log (all blockers, in order)
`build/jolt-spike/climb.sh yin.repl` run with `:paths ["overlay" "build/jolt-spike" "src/cljc" "src/clj"]`.
1. Plain `src/` (no overlay): fails at `src/cljc/dao/jing/cbor.cljc:136` with `No matching field or method: BigInteger/valueOf`. `dao.jing.hash-registry` does not exist, so I started from `yin.repl`. WORKAROUND: overlay `cbor.cljc` with `:jolt` branches before `:clj` for the 8 BigInteger helpers (`big`, `b+`, `b-`, `b*`, `bquot`, `bcmp`, `bgcd`). `b->int`, `magnitude-bytes`, `bit-length` at cbor.cljc:228-246 are still `:clj`-only and untested.
2. `cbor/boring.cljc` (`Writer` ctor at `yin/vm.cljc:375` via `dao.jing/content-hash`). WORKAROUND: the pure-Clojure wire above.
3. `dao/jing.cljc:114,128` (`Blake3/newInstance`). WORKAROUND: overlay with `(blake3/hash-bytes ...)`. SHA-256 sites at `jing.cljc:65,79` are stubbed with a throw.
4. `yin/vm/integer/host.cljc:119-298` (`BigInteger/valueOf`, add, sub, mul, quot, rem, compare, bit ops, shifts, radix string). WORKAROUND: 25 `:jolt` insertions in an overlay copy. Bit ops use `bit-and` etc., which are **incorrect for values above 2^63** on jolt (see section 2); shifts were rewritten as arithmetic but `bit-and-big`/`bit-or-big`/`bit-xor-big` were not, so those are known-wrong in the overlay.
5. After that, `(require 'yin.repl)` printed `:ok`. The other 86 namespaces needed no changes, including `yin.vm.engine`, `yin.vm.semantic`, `dao.stream.*`, `dao.space.*` and the linker. This is partly because the ones with the most `:clj` interop were not run: `dao.jing.file`, `dao.space.store.fs` and `dao.data.btree`.
6. Runtime: `Arrays/parallelSort` missing at `src/cljc/dao/data/arrays.cljc:145`. The code indexer then prints "Warning: this round's code is not in the published code index" after every eval. Not fatal, but the index is not populated. NOT worked around.

Interop density over the 91-file graph (grep of `^bytes`, `java.*`, `clojure.lang.*`, `(.method`): about 570 hits, concentrated in `dao.jing.cbor` (111), `yin.vm.integer.host` (65), `dao.jing.file` (62), `dao.space.store.fs` (92), `dao.jing`, `dao.data.btree` (41), `yin.vm.linker.sign` (22) and `dao.stream.datagram.jvm`. The other ~80 files have fewer than 20 each, and most have none. The graph also names `.cljd`/`datagram.node` files that are missing by design.

## 5b. REPL entry (phase 5): worked
`build/jolt-spike/entry.clj`: `yin.repl/create-state` with a Clojure-only frontend catalog, then `eval-input` calls. `yin.repl.frontends` cannot be used as is because it requires `yang.python.antlr.*` (Java ANTLR), so I built the catalog from `yang.clojure` the way `frontends/standard` does.
```
(+ 1 2) => 3
(defn sq [x] (* x x)) => {:type :closure}
(sq 12) => 144
(map inc [1 2 3]) => Error: Unable to resolve symbol: map in this context   (not checked against the JVM: probably the REPL's own minimal primitive set)
```
Only the semantic VM path was exercised.

## 6. Feasibility verdict
**jolt as a fourth host lane is viable, and much closer than the brief assumed.** Evidence: the full yin.repl graph (91 files, ~50k lines) loads and evaluates with about 150 lines of overlay.
What is needed to make it real:
1. Put proper `:jolt` branches into `src/` instead of overlays: cbor big-int helpers, `integer/host`, `jing` hashes. About 40 lines of source, no new files.
2. A pure `dao.jing.cbor.boring` for jolt (finish floats; `#?` `.jolt` file next to the `.cljc`). About 100 more lines.
3. BLAKE3 and SHA-256 for jolt: FFI to C libs for speed, or the pure code for portability. A day of work including vector tests.
4. A bigint bit-op fix (`bit-and` and friends) in jolt itself or in `integer/host`.
5. The ~15 interop-heavy files that were not exercised (`file`, `store/fs`, `btree`, `linker/sign`, datagram): either `:jolt` branches or `jolt.ffi` for sockets. This is the unknown part: sign needs ed25519, and the networking stack (ws/UDP, P2P NAT traversal) is jolt FFI territory.
6. A conformance run: run the repo's cross-host test lanes (`bb test:*`) on jolt, which I did not do. The 6-of-6 hash match is a spot check, not conformance.

Versus the Rust port (`docs/design/yin.vm.rust.md`): jolt gives a native standalone binary (`jolt build` links Chez into one executable) with the same source, so there is no second implementation to keep in sync. The costs are Chez's runtime size/startup (~0.17s boot floor per the README, a built binary is better), no static types, one maintainer at jolt (HN thread says largely a single developer), and the performance ceiling of Scheme-compiled Clojure vs hand-written Rust. This spike did not benchmark. My recommendation: **a jolt lane is cheap enough to pursue first as a fourth host lane** (days, not weeks) and it does not preclude Rust. Decide after a conformance run (item 6) shows how many of the cross-host tests pass.

## 7. Repro
```
cd /Users/sto/workspace/datomworld
export JOLT_CHEZ=$HOME/.local/share/mise/installs/chezscheme/10.4.1/bin/scheme
build/jolt-spike/climb.sh yin.repl                      # prints :ok
build/jolt-spike/jolt/bin/jolt -Sdeps '{:paths ["build/jolt-spike/overlay" "build/jolt-spike" "src/cljc" "src/clj"]}' build/jolt-spike/entry.clj
build/jolt-spike/jolt/bin/jolt -Sdeps '{:paths ["build/jolt-spike/overlay" "build/jolt-spike" "src/cljc" "src/clj"]}' build/jolt-spike/parity.clj
```
Overlay files: `overlay/dao/jing.cljc`, `overlay/dao/jing/cbor.cljc`, `overlay/dao/jing/cbor/boring.cljc`, `overlay/yin/vm/integer/host.cljc`; plus `blake3.clj`.
Not done: SHA-256, floats in the CBOR wire, bigint bit ops, parallelSort, the JVM check of `(map inc ...)`, and any benchmark.
