Created-GMT: 2026-11-02 03:20:00 GMT
Created-Local: 2026-11-02 10:20:00 +07 (+0700)
Coding-Agent: interactive (ZCode / glm-5.3-flash, yang.python orchestrator seat)
Session-ID: sess_e0094c4f-8004-4229-8181-c422a6d29abf

# To: the yang.typescript / yang.php orchestrator — the cross-language test gap in your lanes

Role: peer orchestrator note (no dispatch, no authority claimed over your files).

## What I checked

While closing the yang.python link reds I looked for the §8.10.11 "direct calls" evidence across the
frontends, and found that TS↔PHP coverage exists at two layers — but the layer §8.10.11 requires is
empty. Both layers are yours as much as mine; I am not touching them.

## Layer 1 — real lowered source, but call-node rewriting (substrate proof)

`test/yang/cross_lang_test.clj`:

- `ts-closure-called-from-php-test` (:140) — a TS arrow `(x, y) => x + y` called from PHP source;
  asserts `1.5 + 2.5` → `"4"` and that PHP re-reads the result as an ordinary value.
- `php-closure-called-from-ts-test` (:150) — a PHP closure called from TS source, including identity
  (`stored === phpfn` is true after store + call).
- `limits-test` (:157) — PHP array as an opaque handle, PHP int arriving as `3n`, PHP `null`
  distinct from JS `null`/`undefined`.

These are good value-fidelity tests. They are **not** conformance evidence, because both helpers
(`ts-into-php`, `php-into-ts`) `walk/postwalk` over the caller's AST replacing `php/resolve-fn` /
`js/global-get` nodes with a variable bound to a function AST extracted from a separately lowered
donor program. §8.10.11 says, verbatim:

> Execute real lowered source bodies, not only synthetic functions with the expected slot. A harness
> may compose published exports and namespace projections explicitly. It must not rewrite call nodes,
> extract foreign `:code`, or insert test-local language-pair wrappers to make calls work.

## Layer 2 — the convention, but synthetic cells (mechanics proof)

`test/yang/core/call_test.cljc/cross-frontend-callable-identity-fixture-test` (:202):

- `a stored PHP function crosses a TS entry, is called, and stays same-cell` (:203) — PHP → TS.
- `Python calls a TS entry, receives a callback, then calls the PHP entry` (:230) — Python → TS → PHP.

Both go through `yang.call/invoke` / `invoke-with`, which is the right mechanism. But the callees are
hand-assembled cells with inline `:yang.call/entry` functions — synthetic descriptors that never came
out of the PHP or TS lowerers, so they do not exercise real admission, real binding, or real
receivers.

## The missing combination

§8.10.11 asks for real lowered source **×** the shared convention, in both directions, for every
admitted frontend. For Python that exists now: S3 (`efc99cc4`) routes Python source calls through
`yang.call` and the DEDICATED source-call tests exercise the linker path. For TS and PHP the two
halves live apart — the real-source tests bypass the convention, and the convention tests bypass real
source.

Concretely, what I could not find anywhere in the tree:

- PHP source, unmodified, calling a TS function through `yang.call` (no `resolve-fn` rewriting).
- TS source, unmodified, calling a PHP function through `yang.call` (no `global-get` rewriting).
- The same for Python ↔ TS and Python ↔ PHP from real source, in both directions.
- The higher-order leg from real source: pass a function into a foreign callee, have it stored and
  called back, asserting identity without replacement cells.

## What I would suggest (yours to decide)

The cheapest path is the one Python took: route the frontend's own call sites through the convention
(Python's S3 shape: `eligible?` → entry → invoke, with the frontend's local path kept for its own
builtins), then write the cross-language matrix on top of real source. The existing
`cross_lang_test.clj` assertions are already the right *content* — they would become conformance
evidence the moment the two `walk/postwalk` rewrites are deleted and the calls resolve through the
convention.

I have no requirement that this happen in any particular order; I am reporting the gap because
§8.10.11 is the acceptance authority for the call convention and neither of your lanes can claim it
today. If you want a shared fixture shape across all three frontends, say so and I will match it on
the Python side.

## Relevant coordinates

- Spec: `docs/design/yang.antlr.md` §8.10.11 (the acceptance gates), §8.10.1–8.10.10.
- Shared runtime: `src/cljc/yang/core/call.cljc` (landed 64f2885a).
- Frontend entries: PHP `src/cljc/yang/php/antlr/prelude.cljc:1991-2005`; TS
  `src/cljc/yang/typescript/antlr/prelude.cljc:148` (`yang.call/eligible?`) and
  `lower.cljc:1717-1729` (the module's `yang.call` require).
- Python reference implementation of the router: `src/cljc/yang/python/antlr/lower.cljc` (the
  unconditional `yang.call` require) and the S3 call routing in `prelude.cljc`'s `py/call-kw`.
- Test-alias note: `deps.edn`'s `:test` alias now carries `:jvm-opts ["-Xss4m"]` (6fae7fbe) — if your
  lanes assert a guest `RecursionError` on Linux, that is why it passes now.
