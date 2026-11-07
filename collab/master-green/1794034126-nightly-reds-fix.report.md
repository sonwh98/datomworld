# master-green: Python and REPL nightly reds

Created-GMT: 2026-11-07 06:48 GMT
Created-Local: 2026-11-07 13:48 +07
Coding-Agent: codex
Session-ID: 01a1b4b7-752d-79e2-8170-b3aa6b149a54
Session-ID source: runtime CODEX_THREAD_ID (the thread.started event is not exposed by these tools)
Branch: nightly-reds-fix
Base: 5f99d070 (the supplied worktree HEAD)

The exception track remains paused. No exception suite, R1–R5 work, stream identity code, or Dart surface was changed. No SSH was used.

## Causes and changes

1. **Python manifest** — `test/yang/python/antlr/linked_prelude_test.cljc:229` still pinned `34893d59…`. The current JVM publication, existing checked-in DHT fixture, and local Node publication all name `d9aead51dc2da70f8dcd8935f2a8c3bc052037967064b45d1914897ad03a6a9c`. The current footprint includes `:cell/authentic?` and the pinned shared call runtime. Jing already canonically sorts map keys and set elements (`src/cljc/dao/jing/cbor.cljc:794`); no serialization or prelude change was needed. The inline golden now matches the JVM. The focused test also rebuilds manifest maps and footprint sets in ascending and descending iteration order and checks their addresses. The historical Node `34893d59…` publication was not reproduced on current HEAD; the shiva check must confirm this on its clean build.

2. **Node stack** — V8's approximately 984 KiB default stack exhausted during recursive guest continuations before Python's guard could answer. `shadow-cljs.edn:20` now re-executes the test entry with `--stack-size=6144` before loading ClojureScript. This covers both shadow autorun and direct shard invocations; the pinned shadow 3.3.5 node-test target hard-codes `["node" output]` and has no execArgv setting. Six MiB leaves native headroom under Linux's usual eight MiB stack. The guest's 1000-frame bound and its exact RecursionError tests remain unchanged; `safepoint_test.cljc:476` pins the Node launch configuration. A launch probe verified arguments, DATOM_SLOW_TESTS, and child exit status are preserved. Local Node diagnostic calls completed recursion-error and generator-rebase without failures; linked recursion and the TypeScript string-forms family remain for shiva.

3. **PHP catalog** — `src/cljc/yin/repl/frontends.cljc:14,87` explicitly installed PHP only on the JVM. The PHP manifest had only a Java generation target, and its CST wrapper lived under src/clj. `antlr/php/manifest.edn:83` now records the JavaScript target, helper and generated BLAKE3 digests, with the same grammar patches as Java. The MIT-noticed local Node helper port preserves positioned heredoc ends. The CST profile moved to `src/cljc/yang/php/antlr/cst.cljc`, uses portable UTF-8 line offsets, and imports each host's generated recognizers. deps.edn includes the generated JS classpath root, and the standard Node catalog installs the binding. New `yin.repl.php-parser-test` pins arithmetic, interpolation, heredoc/nowdoc, terminator spans, and UTF-8 line offsets.

4. **Compile EOF** — The original focused test passed on the JVM (1 test, 6 assertions). On Node, failed PHP selection retained Clojure mode; reading the PHP source `1 + 2;` as Clojure consumed the reader's closing vector bracket as a comment, producing EOF. Installing PHP fixes this route. `test/yin/repl_test.cljc:186` now asserts successful PHP selection and the retained language before previewing actual PHP source, then checks all preview sections and absence of an error. No reader substitution was needed.

5. **DHT rounds** — The failing assertion at `test/yin/repl/dht_test.cljc:2105` counted distinct manifest addresses, not acknowledgement times. Reproduction on the JVM found round 3's index publication failing with `dao.jing.dht: the store message exceeds max-message-bytes`; subsequent publications retained earlier manifests. Generic 512-key index pages exceeded the DHT's 64 KiB store-message bound. `src/cljc/yin/repl/dht.cljc:351` supplies `{:branching-factor 64}` for DHT-backed REPL indexes; `src/cljc/yin/repl.cljc:792` threads the store's publish options into the indexer. Individually oversized values still receive the existing explicit store refusal. The original eight-round test now checks each submission and publication for errors, the configured page factor, eight distinct manifests, every round larger than the pending-write window, and eight acknowledged reports. No clock or acknowledgement-time arithmetic changed.

## Golden regeneration

Ran the checked-in generator twice on the JVM:

```sh
clojure -Sdeps '{:aliases {:t {:extra-paths ["test"] :jvm-opts ["-Xss64m"]}}}' -M:t test/resources/yang/python/dht-import-v1.generate.clj
```

The two outputs were byte-identical (`cmp`), SHA-256:
`49336ba81297c1d597e515463880da43d59c0f7de34f7590f6c305b34bed8200`.
The resource was already current and has no Git diff. Only the inline manifest golden changed. PHP generated source digests were recorded and successfully rechecked with `clojure -M:antlr-gen --grammar php --target javascript`.

## Local verification

Final focused JVM counts (these are selected regressions, not full subsystem gates):

| Namespace | Tests | Assertions | Result |
|---|---:|---:|---|
| yang.python.antlr.linked-prelude-test | 1 | 9 | pass |
| yang.python.antlr.linked-safepoint-test | 1 | 3 | pass |
| yang.python.antlr.safepoint-test | 2 | 8 | pass |
| yin.repl.main-test | 1 | 12 | pass |
| yin.repl-test | 1 | 9 | pass |
| yin.repl.dht-test | 1 | 21 | pass |
| yin.repl.php-parser-test | 3 | 12 | pass |
| yang.php.antlr.cst-test | 10 | 196 | pass |

**Total: 20 tests, 270 assertions, zero failures/errors.**

The main composition test needed a local socket; rerunning outside the filesystem sandbox passed. Logs are in target/nightly-*-jvm.log and target/nightly-dht-fixed.log. Local Node php-parser-test: 3 tests, 12 assertions, zero failures/errors. Scoped Node compilation: zero warnings. The Node bootstrap probe preserved a deliberately nonzero child exit (7). `git diff --check` passed.

## Orchestrator verification still required

No remote gate was run. Run the one authorized scoped Node verification on shiva after a clean parser generation/build, including:

- yang.python.antlr.linked-prelude-test
- yang.python.antlr.safepoint-test
- yang.python.antlr.linked-safepoint-test
- yin.repl-test
- yin.repl.main-test
- yin.repl.dht-test
- yin.repl.php-parser-test (new)
- yang.typescript.antlr.string-forms-test (the uncovered stack family)

Use DATOM_SLOW_TESTS=1 and the runner's single `--test=<comma-separated-namespaces>` argument. The generated entry supplies its own stack flag, including for direct `node target/node-tests.js` invocations. Dart verification remains with the dedicated later run; the broader G1/G2/G3 gates were not run on the laptop.
