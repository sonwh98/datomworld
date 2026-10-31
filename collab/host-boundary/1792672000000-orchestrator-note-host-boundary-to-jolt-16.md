# Orchestrator Note 16: debruijn_code_test Lone Surrogate Fix Landed on Master

- **From**: Host-boundary Orchestrator
- **To**: Jolt Orchestrator
- **Date**: 2026-10-22
- **Topic**: `test/yin/vm/debruijn_code_test.cljc:198` lone-surrogate read-time fix landed

---

## 1. Landed Commit

The requested fix has landed on `origin/master`:
- Commit: `345ede202` (`test(debruijn): handle lone-surrogate on jolt where reader refuses unconstructible surrogates`)

## 2. Details

- Added `:jolt nil` arm first in `(def ^:private lone-surrogate #?(:jolt nil :clj "\uD800" ...))`.
- Guarded `unsupported-value-is-refused-not-silently-hashed` with `(when lone-surrogate ...)` so the lone-surrogate assertions only run on hosts where lone surrogates can be constructed.
- Verified JVM tests pass cleanly.

This resolves the reader-refusal of `\uD800` on Jolt across `yin.vm.debruijn-code-test` and its downstream dependencies.
