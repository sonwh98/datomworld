# src/jolt

The jolt host source root (docs/design/yin.repl-jolt-implementation.md §4.1-4.2).
The `:jolt` alias in `deps.edn` puts it before `src/cljc`, so a namespace here
overrides the portable one on jolt only.

Only complete, narrow host implementations live here, as `.jolt` files with the
same public surface and contract tests as the portable namespace they replace.
Small representation differences go in `#?(:jolt ...)` branches of the shared
`.cljc` source instead (`:cljd` first, then `:jolt`, then `:clj`/`:default`),
except where the owner has ruled otherwise: the `dao.host.*` profile takes no
`:jolt` arm in its shared files, and jolt supplies whole-file overrides here
(J1). Never a copy of `yin.repl`, `yin.vm.engine` or `dao.jing.cbor`.

The `.jolt` extension keeps these files out of JVM, ClojureScript and Dart
tooling (clj-kondo does not lint them as `.cljc`); `.cljstyle` lists `jolt`
among its extensions so the formatter still checks them.

Contents (J0c–J2):
- `dao/host/{integer,bytes,text,float,number}.jolt`: the primitive profile
  (docs/design/dao.stream.host-boundary.md §4.2, table A) over Chez's exact
  integers, flonums, strings and jolt byte arrays.
  `bb test:jolt-text-perf` guards that `text`'s code-unit walks stay linear.
- `clojure/data/json.jolt`: the subset of `clojure.data.json` the test
  fixtures call, because the library itself does not load on jolt.
- `yang/antlr/cst.jolt` (J0c round 2): the native-library facade over the
  ANTLR4 C++ Python3 parser (§7.3). Its contract is the JVM oracle corpus in
  `src/dev/jolt_j0c_parity.clj`.
- `dao/host/{hash,sign}.jolt` (J1b): BLAKE3, SHA-256 and Ed25519 over
  `native/jolt-crypto` (§6.2-6.3), built by `bb build:jolt-native` from the
  pinned BLAKE3 C reference and the declared system libcrypto. Contract: the
  hash and sign cases of `bb compare:jolt-host`; gates: `bb
  probe:jolt-crypto`. The wrapper exports nine narrow entries (the J1b eight
  plus `jolt_crypto_random`, J2); its layout version is 3. Where each
  libcrypto check runs:
  - `bb bootstrap:jolt`: the Debian `:os-packages :debian :required` set
    only (`libssl-dev` among them), with dpkg-query;
  - `bb build:jolt-native`: the declared path and headers exist; the Debian
    runtime package (`libssl3t64` or `libssl3`) is installed; the library's
    canonical path and file SHA-256 equal `:native :libcrypto :qualified`;
  - loading `dao.host.hash`: the wrapper's layout, then the library its EVP
    entries resolved to must equal `:qualified` by canonical path, exact
    version and file SHA-256, the declared path must resolve to it, and its
    version must be at least `:min-version`.
  A changed library is requalified with `bb build:jolt-native --requalify`
  and `bb qualify:jolt-libcrypto` (the procedure is in `jolt-toolchain.edn`).

- `yin/vm/linker/random.jolt` (J2): `rand-bytes` over the wrapper's
  `jolt_crypto_random` (libcrypto RAND_bytes), used by
  `yin.vm.linker.sign/generate` on jolt. Requires `dao.host.hash` first so
  the provenance gate passes before any draw; entropy failure throws.
- `dao/stream/cbor/boring.jolt` (J2): canonical CBOR writer and strict
  decoder over jolt primitives, replacing the JVM Boring adapter. The
  decoder re-encodes to refuse non-canonical bytes; `bb test:jolt-cbor-parity`
  compares the JVM and jolt bytes against the same cases.

The toolchain pins the owner's fork, `https://github.com/sonwh98/jolt`, at
`697b91eefabf3e964acea787cbc9cb1b6b85a28c` (`jolt-toolchain.edn`). Fork PR
`sonwh98/jolt#2` carries the Sorted user-type fix (source `7b7100c1`, regenerated
seeds `697b91ee`), so bootstrap applies no patches and does no re-mint. The former
`0001-sorted-user-types` patch against `96d7b866` is retired; the verified patch
mechanism remains available for future patches. `bb test:jolt-bootstrap-neg`
uses its own temporary synthetic patch to exercise the refusal gates.

J2 round-3 safety: `dao/stream/cbor.jolt` overrides the public gate; its admission
exceptions are recorded in [`dao/stream/cbor.reviewed.edn`](dao/stream/cbor.reviewed.edn)
under `:parity-exceptions`. Canonical keys/elements retain their
encoded bytes and must increase strictly, including byte-array duplicates.
`src/dev/jolt_cbor_safety.clj` exercises performance and main/worker boundaries.
The crypto provenance gate now checks all 19 libcrypto symbol origins, including
`RAND_bytes`; the exported wrapper surface remains nine entries, layout 3.
Seeds use native nonallocating cleanse before free, including failed draws.

`bb test:jolt-cbor-override` is a babashka-only review gate, also run before
J2 list/core/probe/jvm work: it checks the exact shared-source SHA-256 recorded
in `dao/stream/cbor.reviewed.edn`, public vars and literal argument vectors,
and every public def initializer. Extraction reads top-level defn/def forms for each
host reader branch without evaluating them; it cannot infer macro-generated
vars or alias arities. On drift, diff the whole-file override, run
`bb test:jolt-cbor-parity` (emit/check) and `dao.stream.cbor-test` on jolt,
review admission/refusal parity including the recorded `:parity-exceptions`, then
acknowledge that review with `bb test:jolt-cbor-override --update "<who, date>"`. Document any
API exception with exact shared/jolt signatures and a reason in the explicit
`:api-allowlist` (currently empty).

J3 local shell (macOS verification; Linux unrun):

```sh
bb bootstrap:jolt
bb build:jolt-native
. build/tools/jolt-env.sh
"$JOLT_HOME/bin/jolt" -Srepro -M:jolt:jolt-yin-repl
bb demo:jolt
bb test:jolt-shell
bb test:jolt-core --only '^yin\.repl'
```

`dao/stream/waitset/driver.jolt` owns a nonblocking self-pipe. One plain
thread owns the shell; native poll waits for stdin or a wake at the cadence
deadline. Chez's buffered stdin is checked before waiting on fd 0. A partial
line without newline blocks that owner until newline. Ctrl-C exits without a
graceful drain, as on the JVM. There is no line editor.

The J3 catalog installs Clojure and PHP. Python refuses explicitly until J5;
file:/dht: stores refuse before composition until J4/J7. Saved CLI state is
not enabled by the Jolt entry point until J4. Networking has no host adapter
until J6/J7. Source startup is measured by the transcript runner; no speed
gate is applied. The same corpus has a JVM process test. Capability-only
cases identify their host in the corpus, rather than binding a JVM socket or
opening a durable JVM store just to prove a Jolt refusal.

Per-deftest decisions: `test/resources/yin/repl/jolt-applicability.edn`.
The shared reader tests remain active: extra source metadata and accepted
unknown tags/reader conditionals are failures, not accepted divergences.
Only the two explicitly tagged transcript reader rows (small `N` literals
and lone surrogate escapes) have host-specific expected results.
