# xlang slice 2 findings

## Implemented

- Generalized `yang.call.conformance` with frontend profiles for TypeScript, PHP, and Python. Publication, runtime initialization, donor execution, export reads, caller projection, linking, and rendering all use the shared profile path.
- Added Python <-> TypeScript and Python <-> PHP in both directions. Every new leg passes a callback into the foreign function, stores it, checks same-cell identity, calls it back, and asserts the result.
- Python integer results are bigint values at the JavaScript boundary. The TypeScript leg converts explicitly with `Number`; the Python donor converts the TypeScript callback's integer result with `float` before adding a JavaScript-compatible number. PHP's integer path needs no conversion.
- Python `print` caller sources include the parser-required final newline.

## Named skip

- `yang.clojure <-> TypeScript`, both directions: `yang.clojure` has convention-wired application lowering, but no published runtime module, `run-main` entry, or runtime global namespace through which the shared helper can execute a donor and project exports. Supporting these legs requires frontend/runtime work rather than a minimal test-helper generalization.

## Gates

- `clojure -M:test -n yang.cross-lang-test`: pending final run.
- `clojure -M:kondo --lint test/yang/call/conformance.clj test/yang/cross_lang_test.clj`: 0 errors, 0 warnings.
- `mise exec -- cljstyle check test/yang/call/conformance.clj test/yang/cross_lang_test.clj`: passed.
