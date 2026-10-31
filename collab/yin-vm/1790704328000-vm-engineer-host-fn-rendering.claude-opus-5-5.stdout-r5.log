Completed-GMT: 2026-09-29 19:46:51 GMT
Completed-Local: 2026-09-30 02:46:51 +07
Coding-Agent: claude
Session-ID: 10c592b5-4997-47d1-8055-8010792bb808

# Report r5: gate findings P1 and P2 fixed

Both gate findings are fixed, and every lane the brief asked for passes: kondo, focused JVM, `bb test:cljs`, `bb build:yin-repl-peer` and `bb test:cljd`. All ran in the foreground.

Nothing is staged or committed. The diff is still only `src/cljc/yin/repl.cljc`, `test/yin/repl_test.cljc` and `test/yin/repl/serve_test.cljc`. `yin.vm/*`, `dao.data` and `dao.pretty` are unchanged. Q1 (`prn +` nameless) and Q2 (the display marker can be forged) are left as-is, as accepted.

## Tests first (added to `test/yin/repl_test.cljc`, run on the pre-fix code)

`a-host-function-inside-a-quoted-form-renders-as-the-marker` (P1) had 2 failures:
- `(list 'quote inc)` rendered `'#object[clojure.core$inc 0x22c545b ...]`
- `(list 'quote ['a inc {:k (list 'quote dec)}])` rendered `#object[...]` for both `inc` and the nested quoted `dec`

`map-entries-whose-rendered-keys-coincide-all-render` (P2, plus the key-text collision from r4) had 4 failures:

| Input | Rendered before the fix | Problem |
|---|---|---|
| `{inc 1, dec 2}` | `{{:type :host-fn} 2}` | entry dropped |
| `{:type :x, inc 1, dec 2}` | `{:type :x, {:type :host-fn} 2}` | entry dropped (typed-map path) |
| `{'a 1, (list 'quote 'a) 2}` | `{'a 2}` | entry dropped: a symbol and a quoted-symbol list render to equal keys |
| `{:type :x, 'a 1, (list 'quote 'a) 2}` | `{:type :x, 'a 2}` | entry dropped |

That is 6 of 6 assertions failing before the fix and 0 after. These pre-fix runs are the mutation proof for this round: the fix is exactly what separates the two runs.

## Fix (`src/cljc/yin/repl.cljc`, Rendering section)

**P1.** `quote-symbols` takes a `quoted?` flag, and `format-value` passes `false`.
- A list starting with `quote` is no longer returned untouched. It is rebuilt as `(apply list ...)`, with its contents walked in quoted mode.
- In quoted mode, symbols print as they are, so today's symbol quoting is kept. Host functions at any depth become the marker, including inside nested quotes, vectors and maps.
- Outside a quote, behaviour is unchanged.

**P2.** Maps are built by a new `render-map`:
- **Normal case:** if the rendered keys are all distinct, and their `pr-str` texts are distinct too, maps go through `typed-map` (typed) or `(into {} ...)` (untyped) as before.
- **Collision case:** otherwise the entries go to a new `display-map`:
  - Each entry becomes a `DisplayKey` (a new `deftype` with fields `text` and `rank`).
  - The text is the key's rendered form. The rank comes from a sort on `[:type first, key text, value text]`.
  - `DisplayKey`s go into a `sorted-map-by` ordered by rank. No two `DisplayKey`s compare equal, so no entry can be dropped, and the order is the same on every host.
- **Printing a `DisplayKey`:** each host prints just the text:
  - `cljd.core/IPrint` on CLJD
  - `IPrintWithWriter` on CLJS
  - a `print-method` on CLJ, written as `#?(:cljd nil :clj ...)` so it stays out of the Dart build
  - This follows the `dao/data/btree.cljc` precedent.

**Small refactor:** a new `rendered-text` helper (`str/trimr` + `pp-str`) is shared by `format-value` and `display-map`.

## Verification (all in the foreground)

| Check | Result |
|---|---|
| kondo (`clj -M:kondo --lint` on the 3 files) | 0 errors, 0 warnings |
| Focused JVM (`clj -M:test -n yin.repl-test -n yin.repl.serve-test`) | 57 tests, 283 assertions, 0 failures, 0 errors |
| `bb test:cljs` | exit 0; 2282 tests, 50924 assertions, 0 failures, 0 errors. `Testing yin.repl-test` and `Testing yin.repl.serve-test` both present. Log `target/r5-cljs.log` |
| `bb build:yin-repl-peer` | exit 0; `build/yin-repl-peer` regenerated from this source (02:41). Log `target/r5-peer-build.log` |
| `bb test:cljd` | exit 0, "All tests passed!", 2244 tests (r4: 2242, plus the 2 new tests). No `[E]` lines. Both new tests are in the compiled `test/cljd-out/yin/repl-test_test.dart`. Log `target/r5-cljd.log` |

- **Full `clj -M:test`:** not run this round; the brief left it to the orchestrator.
- **cljstyle:** not run; it was permission-blocked in r1.

**CLJD build warning.** `DYNAMIC WARNING: can't resolve member write on target type dynamic ... yin/repl.cljc line 340`. This is the `(.write sink text)` in `DisplayKey`'s `IPrint`. `dao/data/btree.cljc:1341` produces the identical warning for the same pattern. It is a dynamic call that works, which the passing `display-map` tests on CLJD confirm. I didn't add a Dart type hint because I couldn't confirm the name of the sink type.

## Notes

- **Where the collision path triggers:** only when rendered keys coincide, either as equal values or as identical `pr-str` text. Every other map renders exactly as in r4.
- **Ties:** entries whose key text and value text are both identical tie in the sort. Their relative order may vary by host, but they print identically, so the rendered text does not.
- **Residuals from r4, unchanged:**
  - Layout differs above 60 characters because of `dao.pretty`'s budget on CLJD.
  - Untyped maps with two or more entries and no key collision still print in host iteration order on CLJD.
