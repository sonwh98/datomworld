Completed-GMT: 2026-10-03 07:30:11 GMT
Completed-Local: 2026-10-03 14:30:11 +07
Coding-Agent: claude
Session-ID: 5face18f-26de-4829-962a-42049a75c13f

# Float-fix round 3: findings

Tree: /Users/sto/workspace/datomworld-py-floatfix (yang-python-floatfix, on master fb690ae6), uncommitted and
unstaged. The turn was interrupted twice; this round audited the tree against the brief item by item and kept
the on-disk work where it was correct.

## Changed files by commit group

### Group A: carrier hardening (commit first, separately)
- src/cljc/dao/jing/cbor.cljc: the JS `Float64` deftype gets a throwing `valueOf` in its `Object` block. It raises
  through Jing's existing `refuse` helper with the class `:carrier-coercion`, not `:yin.k/non-portable`.
  `toString`, the IEquiv, IHash and IPrintWithWriter implementations, encoding, NaN normalization, signed zero
  and the fixtures are unchanged. This is the only edit to cbor.cljc.
- test/dao/jing/cbor_test.cljc: new `float64-carrier-refuses-coercion-test`.
  - On JS, each of `+ - * / < > <= >=` applied to a carrier throws `:carrier-coercion`, with the carrier first,
    second and at a later variadic position (24 cases).
  - The error's data has `::cbor/refusal :carrier-coercion` and no `:yin.k/status`.
  - Raw JS `'' + carrier` and `Number(carrier)` refuse, while `String(c)` and `.toString()` work.
  - `pr-str` is `#dao.jing/float64 2`.
  - On the JVM and Dart the same 24 cases compute (the carrier is a plain double), so the asymmetry is itself a
    test.
  - Pins on every host: `(str c)`, `(str "a" c)`, `=`, `(cbor/float64 c)`, `hash`, map and set keys, canonical
    hex.
  - `identity` hides the carrier's inferred type so ClojureScript does not emit 25 `:invalid-arithmetic`
    warnings.
- docs/design/dao.jing.cbor.md: one new bullet after the float64-carrier bullet. It records the coercion refusal
  and the `:carrier-coercion` class (not a corpus class, not a lift outcome), the preserved printing, equality,
  hash, bytes and fixtures, the Float64-only scope, and the remaining limitation that generic arithmetic over
  decoded carriers is not portable (the JVM and Dart compute, JS refuses). I removed my earlier round-1 sentence
  under *Numeric identity*, because master already carries the producer-obligation sentence in the carrier
  bullet.

### Group B: everything else (float-fix)
- src/cljc/yin/vm.cljc:
  - Ruling item 1: `carrier-refusing` and `refuse-carrier` are removed, and the registry is restored, including
    `/` -> `checked-divide`. `=`, `==` and `!=` are untouched, and nothing unwraps.
  - What remains is the round-1 admission at `plain-data?` and `machine-data?`.
- test/yin/vm/completion_test.cljc: the round-2 change is reverted (no diff against master).
- src/cljc/yin/vm/debruijn.cljc and debruijn_code.cljc (P1): explicit `:cljd` branches now come first in
  - both `double-le-hex` encoders,
  - `host-double?` (debruijn_code ~350),
  - and, in the same classification paths, `host-long?` (debruijn_code ~333) and `numeric-class`
    (debruijn ~230).

  The kondo-ignore comment on the cbor require now reads "CLJS branch".
- src/cljc/yang/python/antlr/render.cljc (P3): `(Double/isNaN x)`, with the redundant coercion dropped.
- src/cljc/yang/python/antlr/prelude.cljc (P3): the four flagged lines are wrapped. The prelude address is
  unchanged by this; only formatting moved.
- src/cljc/yin/vm/data.cljc: the interim comment on `numeric-key` from round 2 is kept. No guard was added (P3
  accepted as-is).
- test/yang/python/antlr/float_address_test.cljc:
  - `generic-yin-refuses-the-carrier-test` is removed.
  - New `decoded-float-under-bare-plus-test`, with a comment stating it is the documented non-portability. A row
    holding a decoded 2.0 meets a bare `(+ acc 1)`: on Node it throws `:carrier-coercion` (found through the
    ex-cause chain); on the JVM and Dart it gives 3.0. It runs on all four VMs.
  - New `nan-test` covers four things:
    - `"nan"` repr, both for a carrier and for a decoded one;
    - the one-quiet-NaN canonical golden, plus inf - inf (a host-computed NaN) encoding to the same bytes;
    - `py/finite?` on all four VMs;
    - a lowered program `print(1e309 - 1e309, 1e309, -1e309)` printing `nan inf -inf` on all four VMs.
  - New `lift-admits-float64-content-test`, `pin-and-heap-admit-float64-content-test` and
    `closure-and-continuation-images-keep-float64-content-test`. These cover integral floats, ±0.0, NaN and
    ±infinity: direct `lift-slice` admission with canonical bytes preserved, `pin-refs` leaf admission, the
    `collect` heap trace, a captured continuation's machine payload, and a closure lifted and lowered into a
    second task on all four VMs, which returns the floats byte for byte.
  - Goldens were re-minted after the rebase (see below), and the harness lines over 80 columns were wrapped.
- test/yang/python/antlr/prelude_parity_test.cljc and test/yin/vm/data_test.cljc: P3 line wrapping only.
- docs/design/yang.antlr.md:
  - My unnumbered §8.5 float paragraph is deleted.
  - §8.5.5 now has one text: a status line saying the float-fix slice implements the ruling; the codec-constraint
    bullet amended for the carrier's behavior only; and three new subparagraphs, "Implementation", "The dict key"
    (numeric-key interim, C3-S2 replaces and deletes it) and "Generic arithmetic (converged refusal ruling)"
    (unchanged primitive bindings, the carrier hardening, Python's explicit `data/float-value` seam, the
    disclosed limitation, the tests that pin it).
  - The release-gate paragraph now names the tests that carry it.
- docs/design/yin.vm.universal-continuation-format.md: the scalar-arm paragraph now states that admission and
  round-trip preservation of a numeric scalar do not imply that every primitive accepts it. It adds that JS
  primitives meet the carrier's coercion refusal while the JVM and Dart compute, that this is Jing's
  `:carrier-coercion` and not a lift refusal (section 7.9), and that only a profile with an explicit seam
  computes on floats portably.

## Lanes and checks (all foreground, chunked under the 10-minute cap)
- `mise exec -- bb gen:python-antlr`: ok. `mise exec -- bb build:yin-repl-node`: ok. `bb build:yin-repl-peer`
  (a dependency of test:cljd): rebuilt, ok.
- JVM, `clojure -M:test` chunked by `-r` / `-v`. Every chunk had 0 failures and 0 errors:

  | Chunk | Tests | Assertions | Note |
  |---|---|---|---|
  | dao.* | 1349 | 211599 | 1 pre-existing skip: ws-project Dart peer absent |
  | datomworld.*, yang.clojure*, yang.php-test | 109 | 865 | |
  | yang.python.antlr cst-export + e2e-c1 | 31 | 274 | |
  | e2e-c2 | 17 | 108 | |
  | e2e-test, 29 of 30 vars | 29 | 373 | |
  | remaining yang.python* + yang.safepoint | 97 | 692 | |
  | yin.repl a-l | 168 | 1392 | |
  | yin.repl m-z | 112 | 909 | |
  | yin.vm a-c | 136 | 1067 | |
  | yin.vm d | 302 | 3014 | |
  | yin.vm e-k | 143 | 1520 | |
  | yin.vm l | 226 | 3534 | |
  | yin.vm m-z | 202 | 1849 | |
  | **Total** | **2921** | **227196** | |

  - Coverage check: all 186 test namespaces were reported except `dao.gui.compiler-cljd-test`, whose tests are
    `:cljd`-only.
  - `dao.jing.cbor-test` was re-run on the JVM after its last edit: 19 tests, 521 assertions, 0 failures.
  - **NOT GREEN: `yang.python.antlr.e2e-test/long-loops-test`** did not finish within 570 s (`timeout` exit 124;
    no orphan JVM left). This is the blocker master's handoff fb690ae6 records ("long-loops-test HANGS on the
    post-rebase trees ... pre-rebase trees were green"). Its program is integer-only. My round-2 pre-rebase JVM
    lane ran it green. I have not diagnosed it and do not claim it is unrelated beyond that evidence.
- Node: compiled with `--config-merge target/no-autorun.edn` (0 warnings), then ran
  `node target/node-tests.js`: **2718 tests, 92053 assertions, 0 failures, 0 errors**.
- Dart: one full `clojure -M:clojuredart:cljd test` compile, bounded so the compile completed. A first attempt
  restricted to one namespace regenerated `cljd.core` with only that namespace's mixins and broke the other
  generated files, so I did not use namespace-restricted runs. The tests then ran with `flutter test` per
  directory:

  | Chunk | Tests |
  |---|---|
  | yang + datomworld + dao/jing | 346 |
  | dao data/gui/postgraphics/space + top-level files | 796 |
  | dao/stream | 264 |
  | yin/repl | 265 |
  | yin/vm | 1002 |
  | **Total** | **2673**, all passed |

  An expanded-reporter run confirmed `float64-carrier-refuses-coercion-test` and every new float-address test
  by name on Dart.
- kondo: 0 errors on all changed files. The four `yin/vm.cljc` warnings (unused private var and three unused
  bindings, around lines 1323-1501) predate this work. cljstyle check is clean on all changed files.

## Disagreements and deviations (with evidence)
- **Continuation round trip.** A reified continuation does not lift: `lift-slice` refuses it as
  `{:yin.k/status :yin.k/non-portable, :yin.k/kind :non-canonicalizable, :yin.k/hint :reified-continuation}`
  whatever it holds (JVM run, engine.cljc `fail`). The continuation part is therefore admission plus payload
  bytes (`machine-data?` and the floats in its payload byte-identical). Only the closure goes through a full
  lift and lower round trip.
- **"Snapshot".** There is no separate snapshot encoder test. Snapshots are covered through the heap trace
  (`collect`), decoded-program execution and the printed `py/snapshot` output. Name the API if a direct snapshot
  round trip is wanted.
- **P1 scope.** Besides the three cited sites I reordered `host-long?` and `numeric-class`, because they are the
  same float classification paths the reviewer named ("throughout these classification and encoding paths").
- **Address goldens moved with the rebase.** Master commit c3f2da8f (C2-S2 generator send/throw/close) changed
  prelude.cljc, so the bundled-prelude, A, A' and record goldens were re-minted on the JVM. The new values pass
  unchanged on Node and Dart. The hook-prelude, byte and NaN goldens did not move.
- No disagreement with the converged ruling itself.

## Unfinished
- `long-loops-test` (above): the master blocker, not resolved here.
- Nothing staged or committed. collab/ in the main tree was not touched.
