# Store swap Slice 2 findings

Branch `store-swap-p1`. The implementation and tests are uncommitted.

## Changes

- `src/cljc/yin/shell/cli.cljc:544` normalizes prompt keyword options through the existing startup validators. `parse-store-command` at line 608 checks arity, option pairs, and target compatibility, rejects the startup manifest pin with the `dao.space.dht/load-index` hint, and loads an explicitly supplied key after spec validation. The hook at line 777 supplies the checked spec, options, and display label to the existing driver.
- `src/cljc/yin/shell/dht.cljc:663` checks the initial datagram lifecycle event immediately after a peer-enabled join. A synchronous bind refusal throws within the new node's cleanup scope, which closes its socket and durable directory before the driver sees the failure. The tick owner continues to observe successful and delayed bind events.
- `test/yin/repl/dht_test.cljc:320` exercises the parser and pin refusal. Line 890 exercises the delayed startup failure path. Lines 1138, 1166, and 1245 exercise solo swaps, publication and fetch between swapped-in peers, resource release, and preservation of the active store on failed swaps.

## Design and verification decisions

- The approved Slice 1 driver hook already accepts `:spec`, `:opts`, and `:label`. Slice 2 supplies its parser through `yin.shell.cli/hooks`.
- Join tokens declare and follow the publisher as startup does. Admission uses explicit `:trust`, as exercised by the reader test. The ring test uses the repository's in-process datagram and WebSocket seams and fetches a signed module through the real composition tick.
- The existing startup refusal test now delivers its bind event after `boot`. A synchronously delivered refusal is covered by the swap rollback test.
- The memory startup banner assertion at `test/yin/repl/dht_test.cljc:452` checks for DHT disclosure lines. This preserves the intended check when Chez adds host and native parser information.

## Verification

### Focused DHT namespace

```sh
timeout 180s clojure -M:test -n yin.repl.dht-test
PATH="/Users/sto/.local/share/mise/installs/java/21.0.2/bin:$PATH" timeout 240s clojure -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin[.]repl[.]dht-test$" :autorun false}'
timeout 180s node target/node-tests.js --test=yin.repl.dht-test
DATOM_SLOW_TESTS=1 timeout 240s node target/node-tests.js --test=yin.repl.dht-test
```

The tests-first JVM run exited 1 with 50 tests, 523 assertions, 14 failures, and zero errors. The implemented JVM run exited 0 with 50 tests, 572 assertions, zero failures, and zero errors. The selected Node run exited 0 with 50 tests, 518 assertions, zero failures, and zero errors, with three guarded bodies skipped at the default setting. Enabling the guards gave 50 tests and 572 assertions, all passing.

The first Node compile used `--config-merge '{:ns-regexp "^yin[.]repl[.]dht-test$"}'` with the build's default autorun. Shadow also ran six transitive test namespaces, for 164 tests and 1,852 assertions total, all passing. The corrected command disables autorun and uses the runtime selector for each individual namespace.

```sh
export PATH="$PWD/build/tools/offline-bin:$PWD/build/tools/flutter/bin:$PATH" PUB_CACHE="$PWD/build/tools/pub-cache" FLUTTER_SUPPRESS_ANALYTICS=true DASH__SUPPRESS_ANALYTICS=true
timeout 90s flutter pub get --offline --enforce-lockfile
timeout 180s clojure -M:clojuredart:cljd compile --offline yin.repl.dht-test
DATOM_SLOW_TESTS=1 timeout 300s dart --suppress-analytics test --reporter expanded test/cljd-out/yin/repl/dht-test_test.dart
```

Dart setup and compilation exited 0. The Dart VM ran all 50 tests with the guarded bodies enabled and exited 0. Its runner reports tests without assertion counts. Setup used cached dependencies and a worktree-local copy of the pinned Flutter SDK, with the offline wrapper for the compiler's analyzer download command.

After the banner assertion was narrowed, these commands recompiled the namespace and selected the changed test under the same environment.

```sh
timeout 180s clojure -M:clojuredart:cljd compile --offline yin.repl.dht-test && DATOM_SLOW_TESTS=1 timeout 300s dart --suppress-analytics test --reporter expanded --plain-name the-banner-states-what-will-be-shared-before-anything-is test/cljd-out/yin/repl/dht-test_test.dart
```

The selected test exited 0.

### Lint and Gate A

```sh
timeout 180s clj -M:kondo --lint src/cljc/yin/shell/cli.cljc src/cljc/yin/shell/dht.cljc test/yin/repl/dht_test.cljc
timeout 15s rg --hidden --no-ignore -n -F -- 'dao.jing.dht' src/cljc/yin/repl/ src/cljc/yin/repl.cljc
pattern='dao\.jing\.dht|dao\.space\.dht|yin\.repl\.dht|yin\.vm\.linker\.(dht|head)|:dht\b|--dht-'
timeout 15s rg --hidden --no-ignore -n -- "$pattern" src/cljc/yin/repl/ src/cljc/yin/repl.cljc | wc -l
git diff -- src/cljc/yin/repl/ src/cljc/yin/repl.cljc | timeout 15s rg '^\+[^+]' | timeout 15s rg -- "$pattern"
timeout 15s rg -n --pcre2 --hidden --no-ignore -- 'dao\.jing\.dht|dao\.space\.dht|yin\.repl\.dht(?!-composition\.md\b)|yin\.vm\.linker\.(dht|head)|:dht\b|--dht-' src/cljc/yin/repl/ src/cljc/yin/repl.cljc
git diff --check
```

The initial lint reported one unused `parse-flag` argument. Naming it `_args` gave exit 0 with zero errors and zero warnings. The fixed-string and namespace-aware audits exited 1 with zero matches. The combined raw audit counted the 32 inherited design-document citations. The added-line scan exited 1 with zero new matches. `git diff --check` passed.

### Gate B

```sh
timeout 90s clojure -M -e '(let [_ ((requiring-resolve (quote yin.repl.main/boot)) {}) forbidden (filterv #(re-find #"^(dao\.(jing|space)\.dht|yin\.repl\.dht|yin\.vm\.linker\.(dht|head|interpreter))(\.|$)" (str %)) (loaded-libs))] (println "Forbidden loaded namespaces" forbidden) (System/exit (if (empty? forbidden) 0 1)))'
```

The fresh engine-only JVM boot printed `Forbidden loaded namespaces []` and exited 0. The focused proof below uses the existing injected memory and file fixtures for evaluator, index, query, recovery, and linking coverage.

```sh
DATOM_SLOW_TESTS=1 timeout 240s clojure -M:test -n yin.repl-test
DATOM_SLOW_TESTS=1 timeout 240s clojure -M:test -n yin.repl.ast-index-test
DATOM_SLOW_TESTS=1 timeout 240s clojure -M:test -n yin.repl.index-test
DATOM_SLOW_TESTS=1 timeout 240s clojure -M:test -n yin.repl.query-test
DATOM_SLOW_TESTS=1 timeout 240s clojure -M:test -n yin.repl.require-test
DATOM_SLOW_TESTS=1 timeout 240s clojure -M:test -n yin.repl.store-test
```

| Namespace | JVM tests | JVM assertions |
|---|---:|---:|
| `yin.repl-test` | 52 | 268 |
| `yin.repl.ast-index-test` | 13 | 138 |
| `yin.repl.index-test` | 32 | 686 |
| `yin.repl.query-test` | 25 | 400 |
| `yin.repl.require-test` | 20 | 209 |
| `yin.repl.store-test` | 31 | 219 |

All six JVM invocations exited 0 with zero failures and zero errors.

The same six namespaces were selected individually on Node, with guarded bodies enabled.

```sh
PATH="/Users/sto/.local/share/mise/installs/java/21.0.2/bin:$PATH" timeout 240s clojure -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin[.]repl-test$" :autorun false}' && DATOM_SLOW_TESTS=1 timeout 240s node target/node-tests.js --test=yin.repl-test
PATH="/Users/sto/.local/share/mise/installs/java/21.0.2/bin:$PATH" timeout 240s clojure -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin[.]repl[.]ast-index-test$" :autorun false}' && DATOM_SLOW_TESTS=1 timeout 240s node target/node-tests.js --test=yin.repl.ast-index-test
PATH="/Users/sto/.local/share/mise/installs/java/21.0.2/bin:$PATH" timeout 240s clojure -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin[.]repl[.]index-test$" :autorun false}' && DATOM_SLOW_TESTS=1 timeout 240s node target/node-tests.js --test=yin.repl.index-test
PATH="/Users/sto/.local/share/mise/installs/java/21.0.2/bin:$PATH" timeout 240s clojure -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin[.]repl[.]query-test$" :autorun false}' && DATOM_SLOW_TESTS=1 timeout 240s node target/node-tests.js --test=yin.repl.query-test
PATH="/Users/sto/.local/share/mise/installs/java/21.0.2/bin:$PATH" timeout 240s clojure -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin[.]repl[.]require-test$" :autorun false}' && DATOM_SLOW_TESTS=1 timeout 240s node target/node-tests.js --test=yin.repl.require-test
PATH="/Users/sto/.local/share/mise/installs/java/21.0.2/bin:$PATH" timeout 240s clojure -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin[.]repl[.]store-test$" :autorun false}' && DATOM_SLOW_TESTS=1 timeout 240s node target/node-tests.js --test=yin.repl.store-test
```

| Namespace | Node tests | Node assertions |
|---|---:|---:|
| `yin.repl-test` | 52 | 267 |
| `yin.repl.ast-index-test` | 13 | 138 |
| `yin.repl.index-test` | 32 | 686 |
| `yin.repl.query-test` | 25 | 400 |
| `yin.repl.require-test` | 20 | 209 |
| `yin.repl.store-test` | 35 | 233 |

All six builds and runtime invocations exited 0 with zero test failures and zero test errors.

The focused Dart Gate B invocations used the exported environment from the DHT run above.

```sh
timeout 180s clojure -M:clojuredart:cljd compile --offline yin.repl-test && DATOM_SLOW_TESTS=1 timeout 300s dart --suppress-analytics test --reporter expanded test/cljd-out/yin/repl-test_test.dart
timeout 180s clojure -M:clojuredart:cljd compile --offline yin.repl.ast-index-test && DATOM_SLOW_TESTS=1 timeout 300s dart --suppress-analytics test --reporter expanded test/cljd-out/yin/repl/ast-index-test_test.dart
timeout 180s clojure -M:clojuredart:cljd compile --offline yin.repl.index-test && DATOM_SLOW_TESTS=1 timeout 300s dart --suppress-analytics test --reporter expanded test/cljd-out/yin/repl/index-test_test.dart
timeout 180s clojure -M:clojuredart:cljd compile --offline yin.repl.query-test && DATOM_SLOW_TESTS=1 timeout 300s dart --suppress-analytics test --reporter expanded test/cljd-out/yin/repl/query-test_test.dart
timeout 180s clojure -M:clojuredart:cljd compile --offline yin.repl.require-test && DATOM_SLOW_TESTS=1 timeout 300s dart --suppress-analytics test --reporter expanded test/cljd-out/yin/repl/require-test_test.dart
timeout 180s clojure -M:clojuredart:cljd compile --offline yin.repl.store-test && DATOM_SLOW_TESTS=1 timeout 300s dart --suppress-analytics test --reporter expanded test/cljd-out/yin/repl/store-test_test.dart
```

| Namespace | Dart tests |
|---|---:|
| `yin.repl-test` | 52 |
| `yin.repl.ast-index-test` | 13 |
| `yin.repl.index-test` | 32 |
| `yin.repl.query-test` | 25 |
| `yin.repl.require-test` | 20 |
| `yin.repl.store-test` | 31 |

All six Dart invocations exited 0 with the guards enabled. The commands cover §10's listed decisive namespaces and the fresh engine-only dependency audit.

## Open questions

The remaining verification scope is the whole retained-engine suite at landing.
