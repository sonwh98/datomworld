# F1 and F5 findings

- `recheck-on-tick` now accumulates drained output across productive rounds, retaining nil when no output is produced.
- Reviewed `step-all`, both DHT `step-world` helpers, and admission `settle`: each prints or accumulates the returned string and accepts multi-round text.
- Applied the requested indentation and testing-label polish. The existing shell-session test is a `.clj` process corpus, not a `.cljc` unit test; no optional assertion was added.
- On jupiter, the requested gate/REPL suite passed: 85 tests, 877 assertions, 0 failures, 0 errors. The slow linker suite passed: 45 tests, 647 assertions, 0 failures, 0 errors.
- The requested forbidden-reference search returned no matches.
