Completed-GMT: 2026-09-30 06:49:43 GMT
Completed-Local: 2026-09-30 13:49:43 +07
Coding-Agent: claude
Session-ID: d4ec2cf2-f45d-4f5a-8a43-572555b2a216

# $ast row relation, slice 2: the q bridge supplies $ast / $occ when they are named in :in

Status: done, not staged or committed. Every check below is green, except cljstyle, which was blocked (see Verification).
`dao.space.query` needed no change: a plain `query/relation` of `[id tag & slots]` rows or `[root path node]` tuples
binds to a named `$ast`/`$occ` source, and the engine matches it at exact arity.

## Changes (only the allowed files)

- `src/cljc/yin/repl/query.cljc`
  - `ast-sources` `#{$ast $occ}`. `caller-patterns` now removes `$` and exactly those two symbols. `%` and every other
    pattern are still supplied by the caller. The new `ast-patterns` returns the AST sources a query declares, in
    declared order.
  - `with-index` rewrites `:in` as `$`, then the declared AST sources, then the caller patterns (vector and map forms).
    A query without `:in` is unchanged and gets only the implicit `$`.
  - New `ast-relations`: it returns `[]` when the query names no AST source, so datom-only queries never consult the
    AST indexer. Otherwise it builds `query/relation` values from `ast-index/relations` in declared order. If the AST
    indexer is absent, lost or failed, it returns a `::index-unavailable` refusal ("$ast and $occ are unavailable:
    …; (reset) rebuilds them"); the reason comes from `ast-index/status`.
  - `answer` gains an arity `[indexer ast-indexer limits request]`. The old 3-arity passes nil, so an AST query sent
    through it is refused. AST relations are checked after split/view/db and passed ahead of the caller inputs. The
    `:view` interpreter still wraps only the `$` db, so `$ast`/`$occ` are the same under `:current` and `:history`.
    Row and byte limits run on the collected result, as before, so they apply to mixed-source queries unchanged.
  - `serve` takes `:ast-indexer`. The ns docstring describes the new surface.
- `src/cljc/yin/repl.cljc`: `drive-links` passes `:ast-indexer (:ast-indexer state)` to `query/serve`. This is the
  state current at serve time, the same way `:indexer` is read, never a value captured at construction. The session
  docstring notes this.
- `test/yin/repl/query_test.cljc`: 5 new deftests. The shell-level ones run on all four VMs.
  - `ast-and-occ-are-supplied-when-named-in-in`: after `(defn inc [i] (+ i 1))`, the `$ast` variable names include
    `yin/def`, `+` and `i`. They are the same under `{:view :history}` and in the map query form. A caller input after
    a source works (`:in $ast ?n` → `:literal`). The `$occ` count (`:with ?root`) equals the indexer's `:occ` size.
    `$occ`⋈`$ast` finds the single place of `i` (`[[3 1] 3 [3 0]]`). A `$ $ast $occ ?n` join and `:in ?n $occ $ast`
    (sources declared after an input) give the same answer.
  - `ast-sources-are-not-caller-inputs`: too few or too many inputs → `query-failed` "input arity". A query without
    `:in` that uses `$ast` → `#{}`: it is not supplied, which is today's behavior.
  - `an-unavailable-ast-index-refuses-only-ast-queries`: covers a lost indexer (a real gap through a lossy observer)
    and a failed one. `$ast` and `$occ` queries → `index-unavailable`; a `$`-only query still answers, followed by the
    round's slice-1 warning line.
  - `ast-results-over-a-limit-refuse-naming-it`: the shell row limit applies to a `$ast` query (1100 literals). At the
    unit level, the row and byte limits both apply to a `$ $ast $occ` query, and a result within both limits passes.
  - `ast-sources-are-read-when-the-call-is-answered`: `answer` reflects the AST indexer it is handed (a later state
    shows `y`, an earlier one does not). With no AST indexer, the call is refused.

## Test-first and mutation proof

Before the bridge change, the new tests failed: 85 failures and 5 errors across all five new deftests. After the
change: 23 tests, 339 assertions, 0 failures. I applied each mutation in turn, ran `yin.repl.query-test`, and restored
the file; the final diff contains none of them. All 7 mutants were killed:

| Mutant | Result |
|---|---|
| M1 `caller-patterns` removes only `$` | 77 failures (all 5 new deftests) |
| M2 `with-index` omits the AST patterns | 28 failures |
| M3 no refusal when lost/failed | 32 failures (unavailable test) |
| M4 datom-only queries also consult the AST indexer | 16 failures (4 existing datom tests + unavailable test) |
| M5 `repl.cljc` hands serve no `:ast-indexer` | 24 failures |
| M6 row limit not checked | 19 failures (new and existing limit tests) |
| M7 `$ast` supplied the `$occ` tuples | 23 failures |

## Verification (all run in the foreground; see note)

- kondo `clj -M:kondo --lint` on the 3 files: 0 errors, 0 warnings.
- cljstyle: **blocked**. Both `cljstyle check` and `cljstyle fix` were refused by the permission gate in this session.
  I re-indented the new `answer` arity by hand to the file's style, but it is unverified. Run
  `cljstyle check src/cljc/yin/repl/query.cljc src/cljc/yin/repl.cljc test/yin/repl/query_test.cljc` before committing.
- Focused JVM (`yin.repl.query-test`, `yin.repl.ast-index-test`, `yin.repl-test`, `dao.space.query-test`): 146 tests,
  973 assertions, 0 failures, 0 errors.
- Full `clj -M:test`: 2403 tests, 184760 assertions, 0 failures, 0 errors.
- `bb test:cljs`: 2308 tests, 51210 assertions, 0 failures, 0 errors; the output includes "Testing yin.repl.query-test".
- `bb test:cljd`: exit 0, "+2270: All tests passed!". All five new deftests are in
  `test/cljd-out/yin/repl/query-test_test.dart`.

Note: the mutation run went past the tool's 600s foreground cap, and the harness moved it to the background. I waited
for it to finish and did no other work until it was done. Every other check ran in the foreground.

## Portability

The change is plain CLJC. It adds no reader conditionals, uses no array-map and no cross-namespace `#'` access. The
bridge only reads the AST indexer through the public `ast-index/relations` and `ast-index/status`.
