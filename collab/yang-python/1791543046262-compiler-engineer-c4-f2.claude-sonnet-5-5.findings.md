# Findings: yang.python Phase C4 Slice F2 (REPL catalog selection)

Engineer: claude-sonnet-5-5. Not committed (collab/ is never staged).

## Implemented
- `src/cljc/yin/repl/frontends.cljc` (new): `standard` builds the default
  `yang.frontend` catalog (`:yang.clojure/legacy`, `:yang.python/legacy`,
  `:yang.php/legacy`, languages `:clojure :python :php`). It is the only
  place the REPL composition requires `yang.clojure/python/php`.
  Binding shape: `{:parse f :lower f}` plus `:input :forms` for Clojure,
  whose reader stays the shell's own.
- `src/cljc/yin/repl.cljc`:
  - Dropped the static `yang.clojure/php/python` requires; requires
    `yang.frontend` and `yin.repl.frontends`.
  - `create-state` takes `:frontends` (default `(repl.frontends/standard)`),
    stored as `:frontends`, an immutable snapshot pinned by the session.
  - The closed `case` in `compile-source` / `compile-command-ast` /
    `eval-parsed*` is replaced by catalog dispatch (`select-frontend`,
    `frontend-binding`, `compile-input`).
  - `:lang` is a qualified id (`:yang.python/legacy`) or a language keyword
    (`:python`, which must name exactly one installed id).
  - An id or language not in the catalog, or a binding without parse and
    lower, throws an `unavailable-parser` diagnostic. There is no fallback.
    `(lang :yang.python/antlr)` answers `Error: unavailable-parser: ...`
    and leaves `:lang` unchanged.
- `test/yin/repl/frontend_catalog_test.cljc`: 4 tests. A toy frontend is
  selected by language and by id and evaluates on all four VMs. The
  standard languages still work. A newer revision leaves a live session
  unchanged. An unavailable id answers unavailable-parser with no legacy
  result.

## Verification
- JVM: `clojure -M:test -n yin.repl.frontend-catalog-test`: 4 tests, 26
  assertions, 0 failures. `yin.repl-test`, `main-test` and `adapter-test`:
  98 tests, 634 assertions, 0 failures.
- Node: `bb test:cljs`: 3811 tests, 108192 assertions, 0 failures. The
  frontend-catalog namespace ran.
- Dart: `bb test:cljd`: "All tests passed!". The namespace is in the
  aggregated shard list, but the compact reporter printed no per-test lines
  for it, so I could not confirm its tests individually.
- `clj -M:kondo` on the three touched files: 0 errors, 0 warnings.

## Notes and deviations
- `bb test:clj -n <ns>` does not forward `-n`: the task ignores arguments
  and runs the whole suite plus builds. I stopped it and used
  `clojure -M:test -n <ns>` for the targeted JVM run.
- Behaviour change: `(lang :bogus)` now answers
  `Error: unavailable-parser: ...` instead of `Unknown Yin REPL language ...`.
  No tests or docs referenced the old text.
- The brief's Node/Dart item "ANTLR id unavailable with no parser" is
  covered by the default catalog holding no `:yang.python/antlr`, so the
  same assertion runs on every host. Installing a real ANTLR binding into
  a composition is later work (F3/P-slices).
- Not done: no revision-pinned `:lang` form (selection without a revision
  is ambiguous if several revisions of one id are installed, and surfaces
  as unavailable-parser). The completeness probe from the spec is not wired.
- `collab/brief.md` is untracked and was left in place.
