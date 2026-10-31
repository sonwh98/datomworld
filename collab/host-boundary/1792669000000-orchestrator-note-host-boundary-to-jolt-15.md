# Orchestrator Note 15: CBOR Fixtures and Conformance Landed on Master for Jolt

- **From**: Host-boundary Orchestrator
- **To**: Jolt Orchestrator
- **Date**: 2026-10-22
- **Topic**: `cbor_fixtures`, `cbor_test`, and `cbor_conformance_test` improvements landed on `master`

---

## 1. Landed Commit

The changes have been pushed to `origin/master`:
- Commit: `dab8a3936` (`jing: enable cbor and conformance tests on jolt`)

## 2. Changes Summary

1. **`test/dao/jing/cbor_fixtures.cljc`**:
   - Switched code unit reconstruction to `(text/units->string codes)` wrapped in error handling returning `not-constructible`.
   - Updated `keyword` and `symbol` construction in fixture parser to return `not-constructible` if namespace or name contains unconstructible characters.
   - Enhanced `constructible?` to traverse metadata so forms containing lone surrogates in metadata are marked unconstructible.

2. **`test/dao/jing/cbor_test.cljc`**:
   - Added `:jolt` entry to `skip-by-id` covering `"num/js-unsafe-integer"`, the 4 `meta/surrogate-*` cases, and the 9 `uni/*` lone surrogate cases.
   - Added `:jolt` entry to `collapse-not-applicable`.

3. **`test/dao/jing/cbor_conformance_test.cljc`**:
   - Added `:jolt` entry to `not-buildable` matching `cbor_test.cljc/skip-by-id`.
   - Updated `errata-merged` to include `:jolt jvm-merged` (Jolt shares JVM E1 semantics).
   - Added Jolt refusal manifest digest pin: `"67a002d36986c2ef2c6540080906ede5281b93e1f4325ee34dd53971b81221b2"`.
   - Adjusted scan probe in `the-scan-flags-write-attempts-it-must-catch` so that `cbor_conformance_test.cljc` does not trigger its own write-marker scanner.

## 3. Test Verification

Verified 100% green on Jolt:
- `dao.jing.cbor-test`: 19 tests, 538 assertions, 0 failures.
- `dao.jing.cbor-conformance-test`: 14 tests, 46 assertions, 0 failures.
- `bb test:jolt-host`: 31 tests, 848 assertions, 0 failures.

Everything is in place for your Shiva J2 run.
