Completed-GMT: 2026-09-21 20:43:57 GMT
Completed-Local: 2026-09-22 03:43:58 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 51cbe9c5-542b-4efc-99ce-082ca4a1beb8

J0 is done on JVM: the corpus, README, generator and loader are written, and the self-tests pass there. The Node tests did not run, and the Dart lane was not run by me. Nothing is staged or committed.

## Files created (all new, all untracked)
- `test/resources/dao/jing/cbor-v1.json`: the corpus. 359 cases: 203 canonical, 27 encode refusals, 129 decode refusals. 29 equivalence groups, 25 retained-distinction groups.
- `test/resources/dao/jing/cbor-v1.README.md`: provenance, profile summary, schema, DSL, refusal classes, generation and reader method, how each host loads the file, obligations the bytes can't express, reviewers (placeholders), regeneration rules, AMBIGUITIES.
- `test/resources/dao/jing/cbor-v1.generate.py`: standard-library Python. It has an encoder, a generic CBOR reader, and a profile checker. It checks every case against itself. It has `--check` and `--inspect` modes.
- `test/dao/jing/cbor_fixtures.cljc`: `dao.jing.cbor-fixtures`, which loads the corpus on JVM, Node and Dart and provides accessors and hex→host-bytes.
- `test/dao/jing/cbor_fixtures_test.cljc`: `dao.jing.cbor-fixtures-test`, the codec-free self-tests (this is a deviation, see below).

## Case count per coverage category (a case can be in several)
| Category | Cases |
|---|---|
| collections | 38 |
| frames | 47 |
| metadata | 41 |
| unicode | 38 |
| identifiers | 46 |
| bytes | 10 |
| numerics | 124 |
| malformed | 129 |
| boring-options | 7 |
| injectivity | 84 |
| unsupported-values (extra) | 6 |

Refusal classes used: malformed-frame 32, non-canonical 31, unpaired-surrogate 12, malformed-number 11, equality-collapse 10, invalid-utf8 8, malformed-cbor 8, unknown-tag 8, unsupported-value 7, identifier-tag-39 6, native-float 6, unknown-frame-name 4, trailing-data 4, unsupported-simple 4, duplicate-key 3, duplicate-element 2.

## The DSL
Every input node is a JSON object with a type tag `t`, and all numbers are strings so no parser rounds them:
- **Scalars:** `nil`, `bool`, `int` (optional `"host":"big"`), `float64` / `float32` (IEEE bits as hex), `decimal` (exponent, mantissa), `ratio` (num, den, reduced by the constructor), `str`, `bytes` (hex).
- **Identifiers:** `keyword` / `symbol`, built from exact `ns` (null or text) and `name` components.
- **Collections:** `vector`, `list`, `seq` (a realized non-list sequence), `map` / `sorted-map` (entries in insertion order), `set` / `sorted-set`.
- **Refusal-only:** `host` (char, inst, uuid, fn, record, js-unsafe-integer).
- **Text:** a JSON string, or `{"utf16":[code units in hex]}` for surrogate cases. The JSON file is pure ASCII.
- **Metadata:** any collection or symbol may carry `"meta"`, a map node attached before stripping.

## AMBIGUITIES (full text in the README)
- **A1:** Tag 27 takes exactly two elements, `[name payload]`, as the plan says. The flattened IANA form is refused as `malformed-frame`.
- **A2:** Sets are tag 258 over a bytewise-sorted array. This comes from the Boring-produced hex in the stream fixtures; I did not re-verify it against Boring.
- **A3:** Metadata is `27(["clojure/with-meta", [meta value]])`, from the same stream fixture.
- **A4 (open):** On a list, `with-meta` wraps the list frame, not the other way round.
- **A5 (open):** Stripping removes only the unqualified keys `:line :column :end-line :end-column`, at the top level of every metadata map at any depth.
- **A6 (open):** For collapse checks, a list equals a vector (Clojure equality), so `#{[1] (1)}` is refused.
- **A7:** An empty sequence encodes as the empty list, not nil.
- **A8 (open):** `(keyword "")` is accepted provisionally.
- **A9 (left open, no fixture):** Whether a lone surrogate under a stripped reader key is rejected or silently stripped.
- **A10:** The 16 refusal class names are this corpus's own. Each decode refusal has exactly one defect, so check order can't change the class. The README lists the borderline assignments.
- **A11:** I did not check whether the tag-30 spec allows denominator 1.
- **A12:** A decimal exponent must be a plain CBOR integer (major type 0/1), so a bignum exponent is refused. Zero mantissas with different exponents stay distinct.
- **A13:** Every NaN is canonicalized on encode. On decode, other NaN bits are refused as non-canonical.
- **A14 (open):** Where a host's map or set constructor already merged two equal members, the encoder can't see the collapse.
- **A15:** I did not verify Boring's stringref, shape or index-frame tag numbers. Absence is proven by exact bytes instead.
- **A16:** On Node and Dart, a `float32` input means the exactly widened double.
- **A17:** Fixtures record the raw SHA-256 hex. Deriving the `:segment/sha256-…` address is step-3 work.

## What I ran, with exact counts
- `python3 test/resources/dao/jing/cbor-v1.generate.py > test/resources/dao/jing/cbor-v1.json` succeeded, so all of the generator's own checks passed.
- `python3 … --check` printed "cbor-v1.json matches the generator output byte for byte". I ran it once after generating.
- `python3 … --inspect` completed and printed about 106 KB. I looked at the start of the output.
- `clojure -M:test -n dao.jing.cbor-fixtures-test`: "Ran 14 tests containing 2623 assertions. 0 failures, 0 errors."
- `bb test:cljs`: the `:test` build compiled (300 files, 0 warnings). The Node run then aborted before any test executed with `Error: Cannot find module 'ws'`, because this worktree has no `node_modules`. The command exited 0, but **no CLJS test ran**.

## What I did not run, and why
- **`shasum` (twice):** blocked. Both `shasum -a 256` and plain `shasum` needed approval, so I have no shasum result. `--check` is the reproducibility evidence I have.
- **Redirecting `--inspect` output to `/tmp`:** blocked (writes outside the worktree), so I read the saved tool output instead.
- **The CLJS tests:** they need `ws` from `node_modules`. I did not install packages or set `NODE_PATH`.
- **CLJD lane, clj-kondo, cljstyle, Java 17 lane:** skipped per the brief.
- **The Dart side of the loader** (the `dart/is?` conversion, `Uint8List.fromList`, the repo-relative path under `flutter test`): written without being compiled or run. The README marks the Dart load as unverified and states what J2 must do if it fails.

## Deviations
1. **Fifth file.** The self-tests are in their own `dao.jing.cbor-fixtures-test` namespace instead of inside `dao.jing.cbor-fixtures`. The JVM runner and shadow `:node-test` only find namespaces ending in `-test`, and the brief's own verification command names this namespace.
2. **The generator prints to stdout** and never writes the resource. The JSON was produced by a shell redirect, and `--check` confirms reproducibility. This follows the architect's rule that nothing may be able to overwrite the fixtures.
3. **More stdlib modules** than the brief's list: `fractions`, `math`, `os`, `sys`.
4. **No Boring candidate path.** The architect suggested also generating candidates with pinned Boring; the brief forbids that. The only Boring-derived facts are the set and metadata shapes read from existing stream fixtures (A2, A3).
5. **Decode refusals carry `hex` and `sha256` too**, not only canonical cases. Every case has every key, with null or empty values where a key doesn't apply.
6. **Two provenance commits in the README:** the plan's last change (`c96492f`) and the tree it was read at (`0dc06197`).
