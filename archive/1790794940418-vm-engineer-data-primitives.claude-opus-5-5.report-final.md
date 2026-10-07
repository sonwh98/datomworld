Completed-GMT: 2026-09-30 19:52:15 GMT
Completed-Local: 2026-10-01 02:52:15 +07
Coding-Agent: claude
Session-ID: 2cce7933-39ab-4cc2-93e3-b194fc58b9de

# Report: pure `data` host module (collections + strings)

Nothing staged or committed. No D4 code and no existing file modified; engine.cljc/module.cljc untouched (clean merge with cell slice 1).

## Changed files (both new)

- `src/cljc/yin/vm/data.cljc` — namespace `yin.vm.data`: the exports, `data-module` (symbol → host fn), `data-profiles` (all `:pure`, effects `#{}`, host-state `:none`), `register-data-module`, `module-name`, `refusal-message`.
- `test/yin/vm/data_test.cljc` — namespace `yin.vm.data-test`, 19 tests / 304 assertions.

The "registration wiring" is just `register-data-module` in the new namespace, which calls D4's `module/register-host-module`. No existing composition installs it.

## Module name

`data`. It doesn't collide with anything: the registered host modules are `stream`, `await`, `dao.await` and `dao.space.query`, and there are no `'data` references in src/ or test/. Yin source calls the exports as `data/<name>`.

## Exports

Index arguments accept any number with an integral value within ±(2^53−1), so `1.0` is index 1 on the JVM, the same as on JS and Dart. Anything else is a `:wrong-type` refusal.

Every export checks its argument count against its declared arities. A wrong count raises an `:arity` refusal, never a host ArityException.

| Export | Arities | Semantics |
|---|---|---|
| `count` | 1 | Size of a vector, map, set or seq; nil → 0. A string is refused (use `str-length`). |
| `nth` | 2 | Vector element at an index; out-of-range and non-vector are refused. |
| `contains?` | 2 | Map key, set member, or valid vector index; nil → false. |
| `dissoc` | 1+ | Map without the given keys; nil → nil. |
| `disj` | 1+ | Set without the given members; nil → nil. |
| `peek` | 1 | Last element of a vector; empty or nil → nil. |
| `pop` | 1 | Vector without its last element; empty is refused `:out-of-range`; nil → nil. |
| `subvec` | 2, 3 | Copy of `[start, end)` as a plain vector, with `0 ≤ start ≤ end ≤ count`. |
| `hash-set` | 0+ | Set of the arguments. |
| `into` | 2 | Target must be a vector or a set. A vector takes only a sequential source (or nil); a set also takes a set source. |
| `str-concat` | 0+ | Concatenation of strings only; zero arguments → "". |
| `str-length` | 1 | Number of code points. |
| `substring` | 2, 3 | Code points `[start, end)`, with `0 ≤ start ≤ end ≤ length`. |
| `str-index-of` | 2, 3 | Code-point index of the first occurrence at or after `from`, or nil. An empty needle returns `from`. `from` may be 0 to length. |
| `str-split` | 2 | Splits on a literal (non-regex) separator and keeps every empty field, Python-style (`"a,,b,"` → `["a" "" "b" ""]`). An empty separator is refused. |
| `str-join` | 2 | Joins a sequential collection of strings (or nil) with a separator. Non-strings and sets are refused. |
| `char-at` | 2 | The one-code-point string at an index. |
| `str->code-points` | 1 | Vector of integer code points. |
| `code-points->str` | 1 | String from a sequential collection of integers in 0–0x10FFFF; anything else is refused. |
| `str-compare` | 2 | Returns -1, 0 or 1 by lexicographic code-point order. |

No exports beyond the minimum list were added.

Justification for the restrictions:
- `into` refuses a host set or map source into a vector, because host iteration order differs between hosts (brief item 4). Set-into-set is allowed because the result does not depend on order.
- `str-compare` compares code points itself instead of using host `compare`. UTF-16 unit order puts U+1F600 before U+FFFF, and Python does not; the tests pin this case.

## Portability design

- **Shared decoding.** Code-point handling is written once over UTF-16 code units, read with `charAt`, `charCodeAt` or `codeUnitAt` depending on the host. The same shared code builds strings back from code units. All three hosts store strings as UTF-16, so results are identical.
- **Unpaired surrogates.** An unpaired surrogate counts as one code point, as Python treats it.
- **Refusals.** Every refusal is `(ex-info "data primitive refused" {:yin.vm.data/op sym, :yin.vm.data/reason r, ...})`, with `r` one of:
  - `:arity`, which adds `::argc`;
  - `:wrong-type`, which adds `::arg` (0-based position) and `::expected` (a keyword);
  - `:out-of-range`, which adds `::index`, `::lo` and `::hi`;
  - `:empty-separator`.

  No host exception text is used. Through the VMs the same ex-data reaches the caller unchanged; `program-runs-on-every-vm-test` asserts it on all four VMs.
- **Reader conditionals.** Every mixed-host conditional puts `:cljd` first.

## Test outcomes (all run in the foreground)

| Check | Result |
|---|---|
| `clj -M:kondo --lint src/cljc/yin/vm/data.cljc` | errors: 0, warnings: 0 |
| `clj -M:kondo --lint test/yin/vm/data_test.cljc` | errors: 0, warnings: 0 |
| `cljstyle check` on each file | **Blocked**: the harness required approval for the `cljstyle` command and the session could not grant it. Not run. |
| Focused JVM `clj -M:test -n yin.vm.data-test` | Ran 19 tests containing 304 assertions. 0 failures, 0 errors. |
| Full `clj -M:test` | Ran 2441 tests containing 185386 assertions. 0 failures, 0 errors. |
| `bb test:cljs` | "Testing yin.vm.data-test" present. Ran 2346 tests containing 51786 assertions. 0 failures, 0 errors. Build completed, 0 warnings. |
| `bb test:cljd` | Not run, per the brief (orchestrator's lane). |

The first CLJS build raised one `:invalid-arithmetic` warning in `str-split`: `+` over a nil-or-number value. I fixed it by making the internal search return -1 instead of nil. The rerun above is after that fix, and the focused and full JVM runs were also after it.

Non-BMP behaviour is identical on CLJ and CLJS: the same `non-bmp-code-points-test` assertions pass in both lanes. They cover `str-length "a😀"` = 2, code points `[97 0x1F600]`, `char-at`, `substring`, `str-index-of`, `str-split` on and around the emoji, `str-compare` of the emoji against U+FFFF, lone surrogates, and a round trip up to U+10FFFF.

## Mutation proof

Each mutation was applied temporarily, the focused JVM namespace was run, and the file was restored from a copy. Afterwards `git status` shows only the two new files and the two collab files, so `effect.cljc` and `vm.cljc` are unmodified.

| Mutation | Result | Failing tests |
|---|---|---|
| M1: decode surrogate pairs as two units (UTF-16 indexing) | 36 failures | non-bmp-code-points, program-runs-on-every-vm |
| M2: `str-compare` → host `compare` | 7 failures | code-points-str-compare, non-bmp, program-runs-on-every-vm |
| M3: `register-data-module` installs nothing | 7 failures, 92 errors | installed-only-by-composition, program-runs-on-every-vm, effect-shaped-map-is-data |
| M4: revert D4 `effect?` to "a map with `:effect`" (in effect.cljc) | 8 errors | effect-shaped-map-is-data |
| M5: refusals throw host-style `(ex-info (str op reason) {})` | 52 failures | 14 tests, including program-runs-on-every-vm (VM-path refusal) |
| M6: remove the arity check | 3 failures | arity-refusal |
| M7: JVM index accepts only `integer?` (1.0 refused) | 1 failure, 2 errors | nth, contains?, code-points-str-compare |
| M8: `into` a vector from any source (host set or map) | 2 failures | hash-set-into |
| M9: add `count` to `vm/primitives` (in vm.cljc) | 1 failure | installed-only-by-composition |
| M10: profiles `:effectful #{:x/y}` instead of `:pure #{}` | 41 failures | every-export-is-pure, installed-only-by-composition |
| M11: `str-split` drops the trailing empty field | 2 failures | str-split-join |
| M12: `substring` skips the end range check | 1 failure | substring-char-at |

## Unresolved concerns

1. **Host key equality.** `contains?`, `dissoc`, `disj`, `hash-set` and `into`-a-set compare keys with host `=`. `1` and `1.0` are distinct keys on the JVM but the same key on JS and Dart. As the Architect ruling says, the runtime profile must normalize guest keys before they reach these exports; the namespace docstring says the same. No cross-host test covers mixed int/double keys, because that is the profile's parity test.
2. **String cost.** String operations decode the whole string into a code-point vector on every call, so each call is O(n). `str-index-of` and `str-split` use a naive O(n·m) search. This is fine for the spike, but a prelude looping `char-at` over a string is O(n²). A future `str->code-points` + `nth` loop avoids that. Worth measuring along with the effect-dispatch cost the Architect flagged.
3. **`subvec` copies.** It returns a plain vector (O(k)), not a host subvector view. A view would keep the parent alive, and its host type might not encode the same way. Python slicing copies too.
4. **Strict out-of-range.** `substring`, `subvec` and `char-at` refuse out-of-range indices. Python's clamping slice semantics and negative indices are left to prelude code.
5. **Absent vs. host nil.** `str-index-of` returns host nil when nothing is found, and `peek` returns nil for an empty vector. The ruling says each language's null is its own sentinel, so the prelude has to map these nils to that sentinel.

## Incomplete work

- `cljstyle check` was not run (approval blocked, see above).
- CLJD was not built or tested here; it is the orchestrator's lane. The ClojureDart-only branches have never been compiled: `StringBuffer`/`writeCharCode`, `.codeUnitAt`, `.-isFinite`, `.round`/`.toInt` on `num`. They follow idioms already used in `dao/stream/transit.cljc` and `yin/vm/debruijn.cljc`.
- No language runtime profile installs the module yet. Wiring `register-data-module` into a Python profile composition is future spike work.

## Round 2 (gate REQUEST CHANGES + CLJD failure)

Nothing is staged or committed. The only files changed are the two new files, `src/cljc/yin/vm/data.cljc` and `test/yin/vm/data_test.cljc`.

### 1. CLJD lone-surrogate failure (fixed in the test)

- **Cause:** it was the test input, as the orchestrator read it. The test used the source literals `"\uD800a"` and `"\uDE00"`. The CLJD build writes its generated Dart source as UTF-8, where a lone surrogate cannot be encoded, so it became `?`.
- **Fix:** lone-surrogate inputs are now built at run time with a new test helper, `from-units`. It calls the host's own one-unit string constructor on each code unit (`dart:core/String.fromCharCode`, `(str (char u))` on CLJ, `String.fromCharCode` on CLJS), independent of the module.
- **Input guard:** an assertion now checks that the built input really has two UTF-16 units. A mangled input fails that assertion, which says so directly, instead of showing up as a wrong decode.
- **New assertions:** `char-at` on a lone high surrogate, and a round trip of the reversed lone pair `[0xDE00 0xD800]` through `code-points->str`/`str->code-points`, which must stay two code points.
- **Implementation on CLJD:** the decoder reads `.codeUnitAt`, and the encoder writes code units ≤ 0xFFFF with `StringBuffer.writeCharCode`, which emits a lone surrogate unit unchanged. I expect CLJD to pass, but I cannot run CLJD, so it is confirmed only once the orchestrator's CLJD lane passes.

### 2. P2: index-guard tests (added to `nth-test`)

- **Refused:** `(/ 0.0 0.0)` (NaN), `(/ 1.0 0.0)`, `(/ -1.0 0.0)`, `9007199254740992` and `-9007199254740992` are each refused with `(:wrong-type, arg 1, :integer)`.
- **Boundary:** `9007199254740991` (2^53−1) is still an index; on a one-element vector it is refused `:out-of-range` with `::index 9007199254740991`.

### 3. P3 cleanups

- **Docstring:** the ns docstring gains one bullet: every string call decodes its whole argument (O(n)) and search is a naive O(n·m) scan, so prelude loops should call `str->code-points` once and walk the vector with `nth`, never `char-at` per step.
- **`::index` rule:** `::index` is always the position the call would access.
  - For `nth`, that is the caller's index.
  - For `pop`, it is the last index, `(count v) − 1`. `pop` now goes through the same `in-range!` check as `nth`, with range `[0, (count v) − 1]`.
  - On an empty vector: `nth [] 0` gives `{::index 0, ::lo 0, ::hi -1}` and `pop []` gives `{::index -1, ::lo 0, ::hi -1}`. Both report `::hi -1`.
  - The rule is stated in the `in-range!` docstring. `pop`'s refusal data is unchanged from round 1.

### Mutation proof (round 2)

Each mutation was applied temporarily, the focused JVM namespace was run, and the file was restored. Afterwards `git status` shows no tracked file modified.

| Mutation | Result |
|---|---|
| M13: drop the ±2^53 bound | 2 failures, nth-test (±2^53 accepted) |
| M15: drop the JVM finiteness check and the bound | 4 failures, nth-test (±Inf and ±2^53) |
| M16: `pop` on an empty vector reports `::index 0` | 1 failure, peek-pop-test |
| M14: drop only the JVM `Double/isFinite` clause | **Survives, 0 failures.** It is an equivalent mutant: NaN and ±Inf already fail the `(<= (- max-safe) x max-safe)` bound comparison on every host, so the finiteness clause never changes a result. On the JVM the surrounding `or` still restricts non-integers to `Double` (Ratio, BigDecimal and Float are refused). I kept the finiteness clause because the gate called the guard clean. It can be deleted on all three hosts with no behaviour change; say if you want that. |

NaN refusal also survives removing both the finiteness check and the bound: the whole-number test `(== x (Math/rint x))` is false for NaN.

### Round 2 test outcomes (all foreground)

| Check | Result |
|---|---|
| `clj -M:kondo --lint src/cljc/yin/vm/data.cljc` | errors: 0, warnings: 0 |
| `clj -M:kondo --lint test/yin/vm/data_test.cljc` | errors: 0, warnings: 0 |
| `cljstyle check` on both files | **Blocked again**: the command requires approval here, and no cljstyle alias or task exists in deps.edn or bb.edn. Not run. |
| Focused `clj -M:test -n yin.vm.data-test` | Ran 19 tests containing 313 assertions. 0 failures, 0 errors. |
| Full `clj -M:test` | Ran 2544 tests containing 186398 assertions. 0 failures, 0 errors. |
| `bb test:cljs` | "Testing yin.vm.data-test" present. Ran 2459 tests containing 52675 assertions. 0 failures, 0 errors. Build: 0 warnings. |
| `bb test:cljd` | Not run (orchestrator's lane). Needs a re-run to confirm item 1. |

### Still open

- cljstyle has not run on either file.
- The CLJD lone-surrogate fix needs confirmation on the orchestrator's CLJD lane.
- Round 1's unresolved concerns 1–5 stand. The gate accepted concern 1 (host-`=` key equality deferred to the runtime profile), on condition that the profile spike owes the cross-host mixed-key parity test.
