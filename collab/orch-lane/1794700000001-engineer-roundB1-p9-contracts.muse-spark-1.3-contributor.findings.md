# Findings: Round B1 P9 contract completions (prelude_p9.cljc + builtins.cljc)

Engineer: muse-spark-1.3-contributor · 2026-11-05 · branch `feat/roundB1-p9-contracts`

Slice: ONLY `src/cljc/yang/php/antlr/prelude_p9.cljc` + `src/cljc/yang/php/antlr/builtins.cljc`
(plus the two files the slice necessarily touches: `prelude_module.cljc`
extract/compact bodies the review cites, and the one-line `lower.cljc:1853`
name-resolution guard).

## What changed

Strings (`prelude_p9.cljc`):
- `str_replace`/`str_ireplace` with an empty search now return the subject
  unchanged (was: empty output / nontermination for the `i` variant).
- Full operand shapes: search/replacement arrays applied in turn, subject
  arrays mapped; a scalar replacement is replicated over every search (PHP
  semantics — pairwise-with-`""` applies only to replacement arrays).
- The 4th-arg `$count` is now a real by-reference output (`:by-ref [3]` in
  `builtins.cljc`; the guest builtins answer `[ret [count]]`).
- `str_pad`: over-length input returns unchanged (was: truncated);
  empty pad string with padding to do throws `ValueError` (was: hang).
- `strrpos`: non-negative offset is the search's lower bound, negative
  offset counts the end of the search back from the end (was: subtracted).
- `trim`/`ltrim`/`rtrim` accept a character mask with `a..z` ranges.
- `substr_count` accepts offset/length.

Arrays:
- `array_map`: single-array calls preserve keys; further arrays make it
  variadic (longest wins, short arrays read null, result is a list); a null
  callback zips.
- `array_unique` defaults to `SORT_STRING` comparison (was: loose equality);
  the flags argument is honored (regular/numeric/locale-string).
- `array_diff`/`array_intersect`/`array_diff_key`/`array_intersect_key` take
  further arrays (intersect = present in ALL, diff = absent from ANY).
- `array_splice` normalizes bounds (negative/clamped offset and length);
  omitted length runs to the end; omitted replacement is an empty PHP array
  (was: a host vector that became `[[]]`).
- `range` with step `0` throws `ValueError` (was: hang).

JSON:
- Encoding escapes other control bytes as `\u00XX` (was: `?`); `INF` now
  also fails encoding; without `JSON_THROW_ON_ERROR` failures warn and
  answer `false` (whole encoding fails, not a partial string); with the flag
  they throw `JsonException`. Nesting past 512 fails ("Maximum stack depth
  exceeded").
- Decoding rejects trailing commas, non-grammar escapes, unquoted keys,
  missing colons, and empty number positions; enforces the `$depth` argument
  (default 512, `< 1` is a `ValueError`); `JSON_THROW_ON_ERROR` throws
  `JsonException`, otherwise the failure escapes the whole parse with null
  via `php/call-ec` (continuing on the failure value crashed the host with
  "data primitive refused"). `\u` escapes are hex-validated.
- Objects still decode to arrays: kept disclosure, unfinished parity.

extract/compact (`prelude_module.cljc`, `builtins.cljc`):
- Constants corrected per PHP: `EXTR_PREFIX_INVALID` 4,
  `EXTR_PREFIX_IF_EXISTS` 5, `EXTR_IF_EXISTS` 6,
  `EXTR_PREFIX_ONLY_IF_EXISTS` 7; removed nonexistent `EXTR_INVALID`,
  `EXTR_PREFIX_IF_EXISTS` 7, `EXTR_ONLY_IF_EXISTS`.
- Prefixed names insert the mandatory underscore (`p_` + `z` = `$p__z`).
- Existence uses binding existence (null-valued variables exist); all modes
  0–7 implemented (unknown flags import nothing — PHP's exact diagnostic
  for those is still open, see below).
- `compact` keeps null-valued variables, warns
  `compact(): Undefined variable $name` on missing names, skips non-strings.

Lowering (`lower.cljc`): the `extract`/`compact` builtin lowering now
applies only when the call resolves to that builtin
(`(case (if user-lc ::shadowed lc) ...)`); namespaced user functions win.

## Tests

- New `p9-contracts-test` in `builtin_surfaces_test.clj` (41 assertions):
  every item above incl. the named counterexamples
  (`str_ireplace('', 'x', 'a')`, `str_pad('abcd', 2)`, `range(1, 3, 0)`).
- Corrected two fixtures per the review table: `:extract-skip-and-prefix`
  now expects out `15` + the `$p_z` warning diag; `:compact-nested` keeps
  `3,4` + the missing-variable warning diag.
- Gate: `clojure -M:test -n yang.php.antlr.builtin-surfaces-test` green
  (3 tests, 135 assertions). `clj-kondo` on the touched files: 0/0.
  Neighbor suites `scope-test` (26) and `exceptions-test` (25) green.
- `cljstyle` could not run: no binary in the environment and the network is
  blocked; edits follow the files' existing style.
- `e2e-test` could not run here: it needs the TypeScript ANTLR parser and
  `clj -M:antlr-gen --grammar typescript` requires network fetches (blocked);
  the PHP grammar regenerated fine offline.
- `prelude-test` has 1 failure (`array-path-assign-test`, string-offset
  writes) that also fails on the clean tree — pre-existing, Round A scope.

## Open / out of scope

- The multibyte string-offset-write warning fixture (`programs_p9.cljc:28`)
  lives in `prelude.cljc`: left for the parallel round.
- `extract` with an out-of-range flag value imports nothing; PHP's exact
  behavior there is unverified (no PHP runtime in this environment — all
  expectations are per php.net/docs knowledge, cross-checked against the
  review's table).
- `htmlspecialchars` flags/encoding/`double_encode` and other remaining
  optional args are still accepted-and-ignored; `json_last_error` is not
  tracked.
