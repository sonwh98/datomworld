# Relay Note: `yin.repl` ↔ `dao.jing.dht` Boundary Refactor Landed

- **Date:** 2026-11-13 00:17 +07
- **From:** Lead Engineering Orchestrator (`agy` / Gemini, `yin.repl` refactor seat)
- **To:** `yang-clojure` orchestrator, `yang-php` orchestrator, `yang-typescript` orchestrator, Exception Track stewards
- **Topic:** Landed decoupling of `yin.repl` from `dao.jing.dht` and relocation of shell query adapters

---

### Key Architectural & Namespace Updates
The architectural refactor to decouple `yin.repl` from `dao.jing.dht` has completed, passed independent architectural review (`deepseek-4.1-flash`), verified all test suites on Shiva (Gate A literal and companion audits: 0 matches), and landed on `master`.

1. **`yin.repl` depends strictly and solely on `dao.jing`:**
   - Zero hardcoded imports or runtime couplings to `dao.jing.dht`, `dao.space.dht`, or companion DHT symbols in `src/cljc/yin/repl/`.
2. **Relocation of Linker & Query Adapters:**
   - Linker, query, and session composition adapters have been extracted out of `yin.repl` to `yin.shell.*`.
   - Specifically:
     - `yin.repl.query/link-answer` ➔ `yin.shell.query/link-answer`
     - DHT session lifecycle & storage ➔ `yin.shell.dht`, `yin.shell.store`, `yin.shell.session`
     - Shipped host composition entry point ➔ `yin.shell.main`
3. **`test/yang/clojure_callsite_test.cljc` (`repl-query-link-forwarding-test`):**
   - The test has been updated to query `yin.shell.query/link-answer` (with `[yin.shell.query :as shell-query]`).
   - The ClojureDart exclusion guard `#?(:cljd nil :default ...)` introduced for `with-redefs` portability has been preserved.

Any upcoming callsite or link-query tests should reference `yin.shell.query` directly.
