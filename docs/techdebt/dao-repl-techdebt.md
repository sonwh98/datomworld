# Dao REPL Technical Debt

## Summary
The initial implementation of the Dao REPL focuses on cross-platform parity and the "everything is data" philosophy. However, several platform-specific limitations and architectural trade-offs have been identified during the initial review.

---

## 1. Synchronous Remote Evaluation (`wait-for-response`)
The `wait-for-response` function in `src/cljc/dao/repl.cljc` currently uses synchronous polling for remote responses.
- **Problem:** On the JVM, this relies on `Thread/sleep`. On Node.js and ClojureDart, this throws an exception because blocking sleeps are not supported.
- **Impact:** Remote evaluation (`connect`) is currently only fully functional on the JVM.
- **Resolution:** Refactor the evaluation loop to be asynchronous (e.g., using `core.async` or platform-native promises/futures) across all platforms.

## 2. Namespace Collision in Platform Entry Points
Both the JVM (`src/clj/dao/repl_main.clj`) and ClojureDart (`src/cljd/dao/repl_main.cljd`) versions of the REPL entry point use the same namespace: `dao.repl-main`.
- **Problem:** This causes "redefined var" warnings in `clj-kondo` and other static analysis tools.
- **Impact:** Linter noise during CI and development.
- **Resolution:** Standardize on a platform-specific suffix (e.g., `dao.repl-main.jvm`, `dao.repl-main.cljd`) or use a shared `.cljc` wrapper that dispatches to platform-specific namespaces.

## 3. Server Mode Parity
The `serve!` function, which enables the REPL to act as a WebSocket server, is currently only implemented for the JVM.
- **Problem:** Node.js and ClojureDart implementations throw a "not supported" exception.
- **Impact:** Flutter apps and Node.js instances cannot yet be used as remote REPL targets.
- **Resolution:** Implement `serve!` for ClojureDart using `dart:io`'s `HttpServer` and for Node.js using a library like `ws`.

## 4. Discrepancies in Argument Parsing
The ClojureDart version of `parse-args` does not parse the `--port` argument into an integer, whereas the JVM and Node.js versions do.
- **Problem:** Inconsistent argument handling across platforms.
- **Impact:** Low (since server mode is currently disabled on Dart), but leads to confusion when enabling features.
- **Resolution:** Standardize `parse-args` in a `.cljc` file or ensure each platform implementation follows the same parsing logic.

## 5. Side-Effecting Requirements
In `src/cljc/dao/repl.cljc`, the `dao.stream.transport.ringbuffer` namespace is required but not explicitly referenced.
- **Problem:** This is likely used for side-effecting registration of the transport type, but it appears as an "unused requirement" to linters and other developers.
- **Impact:** Maintenance risk (accidental removal).
- **Resolution:** Add a comment explaining the side-effect or use an explicit registration function call during REPL initialization.
