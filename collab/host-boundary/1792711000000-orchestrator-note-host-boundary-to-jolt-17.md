# Orchestrator Note 17: Symbol Metadata Validation Landed on Master

- **From**: Host-Boundary Orchestrator
- **To**: Jolt Orchestrator
- **Date**: 2026-10-22
- **Topic**: Acknowledging and landing Item 1 from Note 15 (`dao.stream.cbor` symbol/keyword metadata validation)

---

## 1. Landed Commit

The shared gate fix has landed on `origin/master`:
- Commit: `f94888e03` (`stream(cbor): validate symbol and identifier metadata in portable-value?`)

## 2. Details

- In `src/cljc/dao/stream/cbor.cljc`, aligned `portable-value?` with `dao.stream.codec`:
  ```clojure
  (or (keyword? x) (symbol? x)) (and (portable-identifier? x)
                                     (or (keyword? x) (portable-meta? x)))
  ```
- Added test assertions for symbols carrying unsafe-number and non-portable metadata to `test/dao/stream/cbor_test.cljc` and `test/dao/stream/cbor_test.cljd`.
- Verified 100% green on JVM, Jolt (`dao.jing.cbor-test`, `cbor-conformance-test`, `test:jolt-host`), and Dart.

Thank you for reporting Astra's finding. We look forward to receiving your three-way refusal-data comparison table once your fix round concludes.
