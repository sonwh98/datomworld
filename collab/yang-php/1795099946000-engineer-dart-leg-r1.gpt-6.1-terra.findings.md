Created-GMT: 2026-11-19
Coding-Agent: codex (gpt-6.1-terra)
Status: BLOCKED — Dart test launcher cannot bind localhost in this sandbox; no Dart row has executed.

# Dart leg r1 findings

## Compile

After removing `.clojuredart`, `test/cljd-out`, `lib/cljd-out`, and `build/cljd-agg*`, the requested compile of all ten namespaces stopped compiling `yang.php.antlr.eval-coordinator` while compiling `yang.php.antlr.cst` (log `target/dartleg-r1/compile-offline-add.log`). The compiler reported the coordinator's ns form, without a more specific symbol or reader-conditional diagnostic. The PHP CST namespace has only JVM and JS imports, `:start` and `pushMode` branches; its `PhpLexer`/`PhpParser` constructors have no Dart bindings, and the PHP grammar manifest has no Dart target. A real coordinator compile needs a Dart PHP recognizer and `:cljd` imports and implementations of these constructor/start/mode operations, or an explicitly gated/deferred parser boundary. A reader conditional alone cannot supply a working parser. This is not claimed fixed or accepted; the eval service remains a separate unit.

Separately, `lower`, `prelude`, `prelude-p9`, `prelude-module`, `siteset`, `publication`, `eval-admission`, `eval-publication`, and `eval-transport` compiled successfully (exit 0, `target/dartleg-r1/compile-without-coordinator.log`). Compilation emitted dynamic-member warnings; it is not Dart analyzer or runtime acceptance. The final shared fixture test also compiled (`linked-lane-final.log`).

## Seam and rows

`linked_harness.cljc` already existed as shared code. Added an explicit runtime argument for publication/source and a `:runtime` option to `run-fixture`, preserving the existing default. This replaces the JVM-only `with-redefs` isolation for dynamic-scope rows; cljd reported `Unknown symbol: with-redefs` in the initial test compile (`linked-lane.log`). The generated `dart_linked_packets.cljc` contains packets from the pinned JVM parser, with a regeneration script in `src/dev/yang/php/dart_linked_packets.clj`. The shared test checks all three vector VMs, outputs, diagnostics, uncaught errors, status and fatal; the two mutual-include variants additionally check zero and six sites. It includes the four constant ruling rows, uncaught/caught-finally/values/read-before-declaration/fresh-request, and six dynscope rows. The test and its packet snapshot are unverified at runtime, and must be reviewed against their source fixtures before acceptance.

Dart per-row evidence: **none**. Both scoped Flutter attempts failed before loading the test: `Failed to create server socket (OS Error: Operation not permitted, errno = 1), address = 127.0.0.1, port = 0`. See `linked-lane-3.log` and `linked-lane-final.log`. A JVM run of the new shared test was interrupted after several minutes without producing a row, so it is not a pass either (`linked-jvm.log`).

## Host and next steps

`mise ls flutter` selected 3.47.8 in the checkout; Flutter was not on PATH. Its installed cache and the default pub cache are read-only in this sandbox, and network access is restricted. A writable copy of the same Flutter SDK and cached packages under `/tmp`, analytics disabled, and offline pub resolution allowed compilation; Flutter test itself still hit the localhost socket restriction. `bb src/dev/cljd_agg.clj --slow-regex` output is in `slow-regex.log`. No manifest changes or commits.

On a host/session that permits Flutter to bind localhost, clean the specified caches again, use the pinned Flutter 3.47.8 and run `bb src/dev/cljd_agg.clj --only yang.php.antlr.dart-linked-test` with per-run logs. Inspect every row on semantic, stack, and register, fix any actual failures, and verify the JVM baseline and fixture snapshot. A green scoped test here is still one-host evidence; complete the other host's acceptance independently. The eval coordinator/parser and synchronous-JVM eval service path remain a separate follow-up.
