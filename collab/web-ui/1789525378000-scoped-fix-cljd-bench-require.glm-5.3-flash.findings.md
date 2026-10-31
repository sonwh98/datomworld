Created-GMT: 2026-09-15 22:02:58 GMT
Created-Local: 2026-09-16 05:02:58 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: 3988f9a4-9143-4e58-8961-e92bece1ba64
# Findings: Fix ClojureDart reader-conditional splicing bug blocking bb test:cljd
Implementer: glm-5.3-flash | Status: done

## Diff (the one line, `test/bench/yin_vm_bench.cljc:20`)

```diff
-  (:require #?(:cljd [] :clj [criterium.core :as criterium])
+  (:require #?@(:cljd [] :clj [[criterium.core :as criterium]])
```

File left untracked, nothing else touched, no other files modified.

## Verification

`bb test:cljd` — the original
`Cannot invoke "clojure.lang.Named.getName()"` NPE on `bench.yin-vm-bench`
is **gone**. The compiler now reads the `ns` form cleanly and proceeds into
the file body (it emits defs before failing), proving the ns form is valid
ClojureDart. The compile lane gets far past where it previously aborted
(`agent.tools`, `bench.*`, `dao.await`, `dao.space.transactor-test`, … all
reach compilation).

## Remaining failure (pre-existing, OUT OF SCOPE — not touched)

`bb test:cljd` still exits non-zero, but with a different, unrelated error:

```
Unknown symbol: measure-ms at line: 113, column: 34, file: bench/yin_vm_bench.cljc
```

Cause: both `measure-ms` definitions are reader-conditioned to `nil` under
`:cljd` (lines 76–83 `#?(:cljd nil :clj ...)` and lines 86–101 whose `:cljd`
branch is `nil`; only `:cljs` defines one), yet `run-bench` line 113 calls
`measure-ms` unconditionally. So the file has no `measure-ms` under cljd at
all — this predates the require fix and would surface even with a perfect
ns form. Suggested fix (for whoever owns that scope): give the `:cljs`
conditional's defn a `:cljd` branch (same sampling logic — `js/performance`
exists in Dart's JS interop) or add `#?(:cljd (defn- measure-ms ...))` with
a DateTime-based sample, mirroring `yin.register-bench-cljd`.
