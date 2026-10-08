Completed-GMT: 2026-10-04 06:44:51 GMT
Completed-Local: 2026-10-04 13:44:51 +07 (Asia/Bangkok, +0700)
Coding-Agent: claude
Session-ID: 0f00061f-cf68-4097-8bb6-b07c7dd44e0c

# Findings: Python C4 slice F1, the yang.frontend catalog

## Changed files
- NEW src/cljc/yang/frontend.cljc (pure cljc, no reader conditionals)
- NEW test/yang/frontend_test.cljc (pure cljc, no reader conditionals)
- docs/design/yang.antlr.md: the 8.5.6 slice-table F1 row now reads
  "`yang.frontend` catalog and manifest validation; landed in
  `src/cljc/yang/frontend.cljc`" (one sentence, wrapped over two table
  lines, every line still 80 columns).

No prelude, yin.repl, VM, or golden was touched; no re-mint needed.

## What landed
- `empty-catalog` = `{:yang.frontend/entries {}}`; entries keyed by
  `[id revision]` -> `{:manifest m :binding b}`. Plain value, no global
  registry, no hidden state.
- `validate m` -> `{:status :ok :manifest m}` or a refusal. Order:
  not-a-map, then non-portable (a deep walk: only nil, boolean, string,
  keyword, symbol, number, char, and maps/vectors/lists/sets of them;
  records, functions, atoms and any other host object are refused with
  the `:path` to them), then the 3.7 pinned keys, then the grammar map,
  then the SPI revision against `supported-spi` #{1}.
- `install catalog manifest binding` -> catalog' or a refusal (invalid
  manifest, or `:yang.frontend/revision-conflict`). Never mutates its
  argument. Re-installing an identical entry returns the catalog itself.
- `revisions catalog id` -> sorted vector.
- `select catalog request`: request holds `:yang.frontend/id`, optional
  `:yang.frontend/revision`, and optional pins on any other manifest key
  (3.7). Refusals: `:yang.frontend/unknown` (no such id/revision),
  `:yang.frontend/incompatible` (candidates exist, none matches the pins),
  `:yang.frontend/ambiguous` with the `:revisions` choice record (more
  than one matches; 3.3 "rather than silently choosing").
- Outcome shape follows yin.vm.linker: `{:status :refused :reason r}`
  merged with evidence, plus `refused?`/`ok?`; reasons are qualified
  keywords per section 10.2, enumerated in `refusal-reasons`.

## Tests (run, foreground)
- `clojure -M:test -n yang.frontend-test`: Ran 3 tests containing 27
  assertions. 0 failures, 0 errors.
- kondo (`clj -M:kondo --lint` on both files): errors 0, warnings 0.
- `mise exec -- cljstyle fix` then `check` on both files: clean.
- ASCII only; no line over 80 columns in the new files.
- NOT run by me (orchestrator's lanes): Node (`bb test:cljs`) and Dart.
  The test is host-neutral; every core predicate used (`record?`,
  `boolean?`, `char?`, `qualified-keyword?`, `int?`) already appears in
  shared src/cljc code, but I did not compile the namespace for CLJS or
  CLJD.

## Acceptance mapping (8.5.6 F1)
- Two revisions of one id installed into an empty catalog are both
  selectable (`two-revisions-coexist-test`), each returning its own
  binding by identity; `empty-catalog` and the intermediate one-revision
  catalog are unchanged (the older snapshot answers `:unknown` for r2).
- A manifest holding a function is refused with a qualified outcome
  carrying the path (`manifest-holding-a-function-is-refused-test`), at
  both a nested and a top-level position; an atom is refused likewise.

## Decisions the doc leaves open (smallest choice taken; questions)
1. Required keys: all nine 3.7-pinned manifest keys (id, spi, revision,
   language, grammar, options-schema, lowering-, runtime-,
   support-profile). Grammar requires package, non-empty entries, and
   export-profile; `:yang.grammar/lexer`/`parser` are optional because
   3.6 says the SPI does not require ANTLR. Should a reader-based
   frontend (Clojure) carry a grammar map at all, or is grammar optional?
2. A content address is accepted as a keyword (the repo's
   `:segment/blake3-...` form) or a non-empty string (the doc's
   example). No digest-format check. Should it be keyword-only?
3. Extra manifest keys are allowed (still checked for portability);
   no closed key set, since the doc does not give one.
4. Re-installing an existing `[id revision]`: identical manifest and
   binding (by `=`, so the same function objects) is the identity;
   anything else is refused `:revision-conflict`. Rebinding the same
   revision to a new host binding therefore needs a fresh catalog. OK?
5. `select` with no revision succeeds only when exactly one installed
   revision matches the pins; otherwise `:ambiguous` with the choices.
   "Selection by profile" is implemented as pins on manifest keys
   (equality), not as a support-claim query, since 3.4's claim content
   is not yet defined; F2 may want more.
6. The binding is opaque in F1 (any value, never inspected); F2 defines
   the parse/lower/completeness shape.
7. Set members have no stable position, so a non-portable value inside a
   set reports the path to the set.

## Deviations / not done
- Section 8.5.6's intro still says "none of it has landed"; changing it
  is outside the allowed one-sentence F1-row edit. Orchestrator may
  want to amend it.
- No git operations performed.
