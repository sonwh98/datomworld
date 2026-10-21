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
