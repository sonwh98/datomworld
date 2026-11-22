# Store swap Slice 1 findings

Branch `store-swap-p1`. The implementation and tests are uncommitted.

## Changes

- `src/cljc/yin/repl/store.cljc:45` adds `describe-spec` for the engine's memory and file targets.
- `src/cljc/yin/repl.cljc:421` registers and documents `(use-store ...)`. Session construction starts quiesced, and `eval-program` at line 2331 clears quiescence at evaluation entry, including failed rounds. The swap gate at line 2521 also checks for a parked run, unwritten rows, and unsettled publications.
- `src/cljc/yin/repl.cljc:1043` shares startup and swap assembly. `install-store` at line 2562 uses the replacement's recovery and publication inputs, clears evaluation state and old provenance handles, and advances the round while preserving the shell token and output cursor. The standalone command at line 2602 prepares the new session before retiring the active handle. Reset and VM selection use the store's declared retention policy at lines 2510 and 2673.
- `src/cljc/yin/repl/adapter.cljc:19` and `src/cljc/yin/repl/driver.cljc:445` route the store command to the local driver, including while connected to another endpoint. `src/cljc/yin/repl/serve.cljc:338` supplies the remote refusal text to the engine's command dispatch at `src/cljc/yin/repl.cljc:2924`.
- `src/cljc/yin/repl/main.cljc:257` installs the driver hook. The replacement path at line 449 opens and installs before it retires the prior transport and store. It releases the replacement if installation fails. `step-all` at line 886 restores a fresh memory store on a late composition refusal after a swap and updates the shared endpoint engine in the same tick.
- `test/yin/repl/store_test.cljc:1218` covers the memory and file round-trip, quiescence, recovery, directory locks, continued transaction and entity allocation, and failed opens. `test/yin/repl/serve_test.cljc:318` covers local routing over an in-process connection, rejection of raw eval requests, failed-install cleanup, and late-refusal recovery.

## Design decisions in this slice

- The startup parser can recognize composition-only targets before Slice 2 adds its dedicated command parser. The driver consults the startup parser as specified and limits its result to engine targets. The explicit `:parse-store-command` hook reserves composition-only targets for the subsequent slice.
- The served endpoint passes a refusal into the shared REPL parser. This applies the same command classification to remote input and keeps the endpoint's existing eval-result convention for user-visible `Error: ...` text.
- Late-refusal recovery uses the same open-first replacement path to prepare memory before retiring the refused store. The swap path performs no startup configuration writes.

## Verification

Each test command targeted one namespace and ran in the foreground with a timeout.

### JVM

```sh
timeout 180s clojure -M:test -n yin.repl.store-test
timeout 180s clojure -M:test -n yin.repl.serve-test
```

Both exited 0 on the final tree. `yin.repl.store-test` ran 31 tests and 219 assertions. `yin.repl.serve-test` ran 27 tests and 118 assertions. Both reported zero failures and zero errors.

The worktree initially lacked generated Java parsers and failed to load `yang.php.antlr.gen.PhpLexer`. The JVM runs used the existing generated PHP, Python, and TypeScript classes from the main checkout as read-only build inputs. The pre-implementation store regression run exited 1 with 31 tests, 219 assertions, 43 failures, and 2 errors.

### Node

```sh
timeout 120s clojure -M:antlr-gen --grammar php --target javascript
PATH="/Users/sto/.local/share/mise/installs/java/21.0.2/bin:$PATH" timeout 180s clojure -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin[.]repl[.]store-test$"}'
PATH="/Users/sto/.local/share/mise/installs/java/21.0.2/bin:$PATH" timeout 180s clojure -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin[.]repl[.]serve-test$"}'
```

PHP generation exited 0. `yin.repl.store-test` passed with 35 tests and 233 assertions, and `yin.repl.serve-test` passed with 27 tests and 118 assertions. Both exited 0 with zero failures and zero errors. The first compile with the default Java 17 and a retry setting only `JAVA_HOME` exited 1 before tests because Closure Compiler requires Java 21. Prepending Java 21 to `PATH` selected the required runtime.

### Jolt/Chez

```sh
JOLT_CHEZ=/Users/sto/.local/share/mise/installs/chezscheme/10.4.1/bin/scheme timeout 220s /Users/sto/workspace/datomworld/build/tools/jolt/bin/jolt -Srepro -M:jolt:jolt-test src/dev/jolt_test_runner.clj yin.repl.store-test
```

The pinned Jolt run exited 0 with 31 tests, 219 assertions, zero load failures, zero assertion failures, and zero errors. The initial 180-second invocation exited 1 while loading `dao.host.hash` because the native wrapper was absent. A 220-second retry with the cached native wrapper exited 1 because the wrapper reported layout 3 and this branch requires layout 4. I built the current `native/jolt-crypto` and `native/jolt-file` wrappers in the worktree, using the pinned libcrypto and the existing BLAKE3 build as inputs, before the passing run.

### Dart

```sh
PATH="$PWD/build/tools/flutter/bin:$PATH" PUB_CACHE="$PWD/build/tools/pub-cache" FLUTTER_SUPPRESS_ANALYTICS=true timeout 90s flutter pub get --offline --enforce-lockfile
PATH="$PWD/build/tools/offline-bin:$PWD/build/tools/flutter/bin:$PATH" PUB_CACHE="$PWD/build/tools/pub-cache" FLUTTER_SUPPRESS_ANALYTICS=true timeout 180s clojure -M:clojuredart:cljd compile --offline yin.repl.store-test
PATH="$PWD/build/tools/offline-bin:$PWD/build/tools/flutter/bin:$PATH" PUB_CACHE="$PWD/build/tools/pub-cache" DASH__SUPPRESS_ANALYTICS=true timeout 180s dart --suppress-analytics test --reporter expanded test/cljd-out/yin/repl/store-test_test.dart
PATH="$PWD/build/tools/offline-bin:$PWD/build/tools/flutter/bin:$PATH" PUB_CACHE="$PWD/build/tools/pub-cache" FLUTTER_SUPPRESS_ANALYTICS=true timeout 180s clojure -M:clojuredart:cljd compile --offline yin.repl.serve-test
PATH="$PWD/build/tools/offline-bin:$PWD/build/tools/flutter/bin:$PATH" PUB_CACHE="$PWD/build/tools/pub-cache" DASH__SUPPRESS_ANALYTICS=true timeout 180s dart --suppress-analytics test --reporter expanded test/cljd-out/yin/repl/serve-test_test.dart
```

Dependency setup and both compilations exited 0. The Dart VM runner exited 0 for both namespaces with 31 store tests and 27 serve tests passed. It reports test counts without assertion counts. Setup used a worktree-local clone of the pinned Flutter SDK, cached packages, and an offline wrapper for the compiler's analyzer `flutter pub add` step. The `flutter test --no-pub --reporter expanded` attempt exited 1 at load because creating its loopback control socket was denied. The generated namespaces ran directly under `dart test` with analytics suppressed.

### Lint

```sh
timeout 180s clj -M:kondo --lint src/cljc/yin/repl.cljc src/cljc/yin/repl/
timeout 180s clj -M:kondo --lint src/cljc/yin/repl.cljc src/cljc/yin/repl/store.cljc src/cljc/yin/repl/adapter.cljc src/cljc/yin/repl/driver.cljc src/cljc/yin/repl/main.cljc src/cljc/yin/repl/serve.cljc
git diff --check
```

The requested lint exited 3 with one error and zero warnings. `src/cljc/yin/repl/host.cljc:15:4` reported `Invalid require: no libs specified to load`. That file matches `HEAD` and is outside this slice. Linting the six edited source files exited 0 with zero errors and zero warnings. `git diff --check` exited 0.

### Gate A

```sh
timeout 15s rg --hidden --no-ignore -n -F -- 'dao.jing.dht' src/cljc/yin/repl/ src/cljc/yin/repl.cljc
timeout 15s rg -n -- 'dao\.space\.dht|yin\.repl\.dht|yin\.vm\.linker\.(dht|head)|:dht\b|--dht-' src/cljc/yin/repl/ src/cljc/yin/repl.cljc
timeout 15s rg -n --pcre2 --hidden --no-ignore -- 'dao\.jing\.dht|dao\.space\.dht|yin\.repl\.dht(?!-composition\.md\b)|yin\.vm\.linker\.(dht|head)|:dht\b|--dht-' src/cljc/yin/repl/ src/cljc/yin/repl.cljc
git grep -n -E 'dao\.space\.dht|yin\.repl\.dht|yin\.vm\.linker\.(dht|head)|:dht\b|--dht-' HEAD -- src/cljc/yin/repl/ src/cljc/yin/repl.cljc
```

The fixed-string check and the namespace-aware check each returned zero matches with `rg` exit 1. The literal companion audit returned 32 matches with exit 0, all references to the design document `yin.repl.dht-composition.md` in comments or docstrings. The baseline had 33 of these references. The shared assembly refactor removed one and introduced zero. Some remaining citations are in files outside the slice's edit scope, so the literal zero-match gate remains open.

## Open questions

- If Gate A must match the design filename literally, the existing citations in the rest of the engine tree need a separate scope for cleanup.
- The inherited `host.cljc` finding also needs a separate scope for a clean full-tree lint result.

