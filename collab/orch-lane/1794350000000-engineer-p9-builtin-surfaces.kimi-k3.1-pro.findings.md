# P9 builtin surfaces — engineer findings

## Landed

- Strings II: substring/search/replacement, predicates, trimming, case, repeat/pad/reverse, comparisons, strstr/strrchr, substr_count, nl2br, basic HTML and slash escaping, byte/hex conversion.
- Arrays II: explode/str_split, reverse/product/search/list checks, fill/fill_keys/combine/flip/pad/chunk/column/slice/splice/map/filter/reduce/unique/diff/intersect/range.
- Math II: abs/ceil/floor/round/sqrt/pow/min/max/is_finite/is_infinite.
- JSON: scalar/array/associative-array encoding and decoding, string escapes, pretty printing, and JSON_THROW_ON_ERROR.
- Dynamic variables (`$$`), dictionary-backed extract/compact, `$GLOBALS` reads and element writes.
- C5 in-range positive/negative string writes; C6 array_multisort refusal; C11 EXTR_REFS and dynamic-reference refusals; C12 nested destructuring-reference refusal; C13 whole-$GLOBALS refusals.
- New `programs_p9.cljc` groups and `builtin_surfaces_test.clj`; all fixture outputs hand-derived and marked `:oracle :pending`.
- Updated the stale G-str-offset-write design row.

## Remaining P9 work

- `eval` still needs the runtime compilation-request bridge. It is explicitly refused with `:yang.php/unsupported :eval`; no fake evaluator or undefined `php/eval` call remains.
- JSON objects decoded without `$associative` currently use the same array representation rather than `stdClass`; object encoding and JsonSerializable are not implemented. Control-byte escaping and Unicode surrogate pairs need completion.
- Uncovered catalogue entries include formatting (`sprintf`/`printf`/`vsprintf`, `number_format`, wordwrap/chunk_split), hashes/base conversions, recursive merge/walk, associative diff/intersect variants, iterator_to_array, and additional math functions.

## Implementation notes

- Prelude guest vectors containing expressions must be built with `php/vconj`; a raw quoted vector does not evaluate its elements.
- The JSON decoder uses one rooted cell containing `[code-points index associative? throw?]`. Object parsing must consume the opening quote before reading a key.
- String replacement bounds-checks its needle before substring reads. String offset assignment uses the first byte, matching PHP for a multi-byte value.

## Verification

- Focused P9 and required consumer namespaces run through the JVM AST-walker harness.
- Kondo: 0 errors, 0 warnings on touched source and test files.
- cljstyle: clean after formatting `scope.cljc` and `prelude_module.cljc`.
- Parser classes were generated locally with `clj -M:antlr-gen`; generated outputs are not part of the commit.
