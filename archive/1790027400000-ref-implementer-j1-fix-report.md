Completed-GMT: 2026-09-21 22:28:40 GMT
Completed-Local: 2026-09-22 05:28:40 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 51cbe9c5-542b-4efc-99ce-082ca4a1beb8

All agreed findings are applied, and both lanes pass: JVM 32 tests, 0 failures; the whole Node suite 1607 tests, 0 failures. While checking a reviewer claim I found it was wrong: on Node, symbols also collide by joined name, not only keywords. The errata records the corrected scope. Nothing is staged or committed. The system clock read 22:28 GMT, earlier than the brief's creation time of 22:50; I report what the clock gave.

## Per finding
- **Depth limit (both reviewers' P2): agree.**
  - `max-depth` is 128, documented as J1 policy pending architect ratification.
  - Depth counts CBOR item nesting: the top-level item is depth 1, and every array, map or tag adds one. A list frame costs three levels.
  - Encode and decode count the same way, so anything encode emits, decode accepts.
  - The reader refuses `:malformed-cbor` before it recurses. The encoder refuses `:unsupported-value`.
  - I did not add a separate counter to `item->value`. It only walks the tree the reader built, which is already capped, so a second counter could never fire; the reason is in the `read-item` docstring.
  - Test runners now catch `Throwable` on the JVM. The decode-refusal loop is now guarded per case.
- **Decimal exponent window (qwen P2-2): agree.** One shared check now applies to both hosts, with public `decimal-exponent-min` and `decimal-exponent-max`.
- **Unsafe JS integers (qwen P3-3 / deepseek P3): agree.** `exact` refuses them on Node with `:unsupported-value`, so `num=`, `num-hash` and `num-compare` never run on a silently rounded value.
- **JVM `big` (qwen P3-2): agree.** It refuses non-integral numbers instead of truncating them; the Node branch now refuses the same way instead of throwing a raw RangeError.
- **Hand-built Rational (qwen P3-1): agree.** `exact` now reduces the ratio and normalizes its sign. Encoding a ratio also goes through `exact`, so a hand-built `(->Rational 2 4)` encodes as `d81e820102`.
- **Keyword metadata (deepseek P3):** verified that neither host allows metadata on a keyword; `with-meta` throws on both. I added a comment where keywords are encoded and a test pinning the throw.
- **Test tightening (qwen P3-5): agree.**
  - The print-settings proof now runs over all canonical cases.
  - The N/A skip lists are narrowed to the merges I actually observed: on the JVM signed-zero, decimal-scale, vector-list and integer-width; on Node vector-list and the two NaN cases.
  - The `exact-key` docstring now says hash values are host-specific.
  - A huge tag is reported with its real number instead of "tag -1".
- **`collapse?` worst case O(n²): declined.** It is bounded by the input and only reachable with crafted hash collisions. It is recorded in the errata as E5.
- **Correction to a review claim (qwen 3b).** qwen said ClojureScript symbols compare by fields. Node shows `(= (symbol nil "a/b") (symbol "a" "b"))` is true. I fixed errata E2, pinned the behavior in the Node-only test, and added a test on both hosts: a set holding those two symbols decodes on the JVM and is refused `:host-collapse` on Node.

## Files changed
- `src/cljc/dao/jing/cbor.cljc`
- `test/dao/jing/cbor_test.cljc`
- New `test/resources/dao/jing/cbor-v1.errata.md`, headed "Status: PENDING architect ratification". It is pure ASCII with no line over 80 columns, and has five entries:
  - **E1:** the corrected per-host N/A table.
  - **E2:** the `host-collapse` class, covering keywords and symbols.
  - **E3:** the decimal exponent window.
  - **E4:** `max-depth`.
  - **E5:** the O(n²) note and the hash-portability note.

`cbor/boring.cljc` and the fixture helpers are unchanged this turn. The frozen corpus, README, generator and `cbor_fixtures_test.cljc` are untouched.

## The decimal window
The exponent must lie in [-2147483647, 2147483648], that is [-(2^31 - 1), 2^31]. This is the JVM `BigDecimal` scale range, which is a 32-bit int. Both hosts give the same outcome for tag 4 over `[exponent 1]`:

| Hex | Exponent | Outcome |
|---|---|---|
| `c4821a8000000001` | 2^31 | accepted |
| `c4821a8000000101` | 2^31 + 1 | `:malformed-number` |
| `c4823a7ffffffe01` | -(2^31 - 1) | accepted |
| `c4823a7fffffff01` | -2^31 | `:malformed-number` |

## New tests
- `nesting-depth-is-bounded-on-encode-and-decode`: exactly 128 nested arrays pass, 129 are refused, on both encode and decode. 42 nested list frames pass, 43 are refused. 10 000 nested arrays or list frames are refused with the expected class on both paths, with no stack overflow.
- `decimal-exponent-window-is-host-independent`
- `numeric-guards-refuse-instead-of-rounding` (the unsafe-integer part is Node only)
- `hand-built-unreduced-rational-behaves-as-reduced`
- `keywords-cannot-carry-metadata`
- `slash-crossed-symbol-set-is-held-or-refused-never-merged`
- The Node-only evidence test now also asserts that symbols compare by joined name.

## What I ran
- `python3 test/resources/dao/jing/cbor-v1.generate.py --check`: "cbor-v1.json matches the generator output byte for byte".
- `clojure -M:test -n dao.jing.cbor-fixtures-test -n dao.jing.cbor-test` (final): 32 tests, 4446 assertions, 0 failures, 0 errors. Skipped by id: 4 observed host merges, plus `num/js-unsafe-integer`.
- `bb test:cljs`, whole Node suite:
  - First run: 1606 tests, 43536 assertions, 1 failure. That was my assertion of qwen's symbol claim, which proved false.
  - Final run: 1607 tests, 43537 assertions, 0 failures, 0 errors, 0 compile warnings.
  - Skipped on Node: `coll/map-both-slash-keywords`, the three observed merges, and the two character cases.
- A grep for non-ASCII characters in the Clojure files found none; a Python check confirmed the errata is ASCII within 80 columns.

## What I did not run
Per the brief, I did not run kondo, cljstyle, the Java 17 lane or the CLJD lane. I followed the orchestrator's formatting conventions but did not run the tools, so style cleanliness is unverified.
