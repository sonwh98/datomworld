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

Contents (J1):
- `dao/host/{integer,bytes,text,float,number}.jolt`: the primitive profile
  (docs/design/dao.stream.host-boundary.md §4.2, table A) over Chez's exact
  integers, flonums, strings and jolt byte arrays.
- `clojure/data/json.jolt`: the subset of `clojure.data.json` the test
  fixtures call, because the library itself does not load on jolt.
- `yang/antlr/cst.jolt` (J0c round 2): the native-library facade over the
  ANTLR4 C++ Python3 parser (§7.3). Its contract is the JVM oracle corpus in
  `src/dev/jolt_j0c_parity.clj`.
- `dao/host/{hash,sign}.jolt` (J1b): BLAKE3, SHA-256 and Ed25519 over
  `native/jolt-crypto` (§6.2-6.3), built by `bb build:jolt-native` from the
  pinned BLAKE3 C reference and the declared system libcrypto. Contract: the
  hash and sign cases of `bb compare:jolt-host`; gates: `bb
  probe:jolt-crypto`. Where each libcrypto check runs:
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
