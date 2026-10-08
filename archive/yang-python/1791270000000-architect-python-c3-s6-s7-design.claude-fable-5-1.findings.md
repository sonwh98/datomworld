Completed-GMT: 2026-10-07 07:40:00 GMT
Completed-Local: 2026-10-07 14:40:00 +07

# Architect design: Python C3 slices S6 and S7 (fable-5-1)

Read-only: nothing edited, no suites run. Code claims below come from reading master edaf571c. Two defect claims are hypotheses from reading, not from a failing run; the briefs make the engineer write the Node and Dart test first and fix only what goes red.

## Verdicts

1. **S6 is a kernel-and-tests slice.** No prelude, integer module, or Jing change. It pins ruling 4 (carriers are scalars) at the four-VM and UCF level on three hosts, and fixes two likely scalar-boundary gaps found by reading: the UCF handoff decoder has no Jing-numeric arm, and `vm/machine-data?` has no big-carrier arm.
2. **Sequence-size limit (item j):** an explicit `data` module limit, `:yin.vm.data/max-items`, exported as `data/max-items`, checked in `py/repeat` before any loop. A breach is `MemoryError` with empty args. Python test compositions use 1048576. The 2^53-1 constant in `py/repeat` is deleted.
3. **Profile mismatch is refused at admission by name.** S7 adds `prelude/admit`, which checks every `host-names` symbol against the registry. A version-3 integer module and an unlimited data module are both refused there, before any program runs.
4. **S6 and S7 run in parallel** on disjoint files. S6 to codex gpt-6.1-sol, S7 to claude opus, each one engineer round, each gated by glm. S6 lands first; S7 rebases.
5. **Ledger:** `round()` error order, `pow`/`round` keyword calls, lint docstring, `0.5 ** -2000` row: fix in S7. Left-shift class (e) and `float('-nan')` sign: accepted forever. Unicode printable repr: later, recorded beside ruling 13's unclaimed `format`, `%`, `round(x, n)`.

## 1. S6: heap and portability

### 1.1 What the four words mean today

| Word | Meaning today | Already covered | Missing |
|---|---|---|---|
| Collection | `engine/collect` marks from roots through `scalar?` (engine.cljc:798, includes `big-carrier?` and `float64?`); a bignum is a leaf, never traced | `heap-trace-treats-big-integers-as-leaves-test` (unit, integer_test, 3 hosts) | No guest program on the four VMs under a small `:gc-threshold` with bignums inside cells and garbage |
| Pinning | `engine/pin-refs` (engine.cljc:2547): a value put on a stream or carried by FFI has its cell refs pinned for the task's life; scalars short-circuit | `pin-refs-treats-big-integers-as-scalars-test` (unit) | No guest put of a bignum-and-cell value through all four VMs; the register VM's `machine-data?` check on put datoms (debruijn_register_effects.cljc:202, 399) uses `vm/machine-data?`, which has no `big-carrier?` arm (vm.cljc:990), so a bignum put from the register VM on JS/Dart is suspected refused |
| Scalar UCF round trips | Module lift: engine `scalar?` admits carriers; `encoder-lifts-big-integers-as-scalars-test`. Task handoff: handoff.cljc's own `scalar?` (line 139) is `number?`-based; the encoder has a `jing.cbor/numeric?` arm gated on version 1 (line 323); the decoder (line 1431) has only `scalar?`, maps, vectors, sets, seqs, else `:host-object` undecodable | Floats: `floats-signed-zero-nan-and-nested-maps-lift-canonically-test`, float_address `closure-and-continuation-images-keep-float64-content-test` | A version-1 body holding a bignum decodes on the JVM (BigInt is `number?`) and is undecodable on JS/Dart (BigInt is not). Same for a Jing float64 carrier on JS. No golden bytes of a scalar body checked on each host. No bignum twin of the closure/continuation image test |
| Honest refusal of cell lift | `engine/lift-slice` refuses `:cell-ref` as `{:yin.k/status :yin.k/non-portable :yin.k/kind :cell}` (engine.cljc:912); completion refuses the same | cell_test lift and completion tests | Not shown with Python values: a list cell holding a bignum must refuse `:cell`, never `:host-object` |
| Honest refusal of raw transport | `dao.stream.cbor/portable-value?` rejects a bignum through `transit/safe-number?`; `encode` throws with `{:error :non-portable-value}`; journal refuses without a write | `non-portable-value-is-refused-without-a-write` (generic value) | No bignum or float64-carrier row; no round trip of a bignum through `dao.jing.stream` wrap/unwrap compared to the S0 `cbor` column |

Inconsistency to fix in handoff: on the JVM a bignum passes `scalar?` and enters a version-0 body, whose stream codec then throws a bare ex-info at `child-bytes`; on JS/Dart the same value takes the numeric arm and refuses `:host-object` when not version 1. Rule: handoff's `scalar?` excludes big carriers (`(and (number? x) (not (integer-host/big-carrier? x)))`), so the numeric arm decides on every host: version 1 carries it as Jing content, version 0 refuses `:non-portable` kind `:host-object` on every host. No new kind vocabulary. The decoder gains `(jing.cbor/numeric? x) x` before the map arm.

CPython is not an oracle in S6. Oracles: the S0 `cbor`, `dec`, `hex` columns of `int-contract-v1.txt` (31 integer names, `f:2^53`, `f:2^63`), S4 float bits via `float-bits`, and the golden body bytes S6 mints once.

### 1.2 Files

- src (only if the red tests prove it): `src/cljc/yin/vm/ucf/handoff.cljc` (`scalar?` arm, decoder numeric arm), `src/cljc/yin/vm.cljc` (`machine-data?` gains `integer-host/big-carrier?`). Nothing else under src. `linker/portable-scalar?` (link ids) is out of scope: ids are never bignums.
- tests: new `test/yang/python/antlr/int_heap_test.cljc` (T1, T2, T3, T5), new `test/yin/vm/ucf/scalar_round_trip_test.cljc` (T4, T7), rows added to `test/dao/stream/journal_test.cljc` and `test/dao/jing/stream_test.cljc` (T6), new fixture `test/resources/yin/vm/ucf/scalars-v1.txt`.
- Does not touch `int_ops_test.cljc` (S7 edits it). `int_heap_test` builds its own opts: cell, data, `prelude/register-integer-module` under `{max-bits 100000, max-digits 4300}` plus `:gc-threshold`.
- Doc: the ruling 4 paragraph (handoff decoder, `machine-data?`) and the ruling 12 paragraph (version-0 refusal, golden bytes) in 8.5.4, and the S6 row marked landed. Nothing else in the doc, so S7's doc edits do not conflict.

### 1.3 Tests (all three hosts unless stated; four VMs where "guest")

- **T1 collection (guest, 4 VMs).** Prelude-notation program under `:gc-threshold 2`: builds a Python list of 40 integers 2^64+i through `py/int-lit` hex, allocating a garbage list cell per step. Assert: the result's `str` equals the exact decimal computed host-side through `integer/format` (40 x 2^64 + 780), every element equal under `canon-ints`, and `(count (:heap final))` equals the count after a forced `engine/collect` of the final state (no garbage retained). The heap-bounded pattern follows `a-loop-with-one-live-cell-keeps-the-heap-bounded-test`.
- **T2 pinning (guest, 4 VMs).** Program makes a stream, puts a Python tuple holding 2^64 and a list cell, allocates garbage past the threshold, reads the tuple back: the list cell is live, the integer exact. Template: `a-ref-put-on-a-stream-stays-authentic-test`. Suspected red: register VM on Node and Dart (`machine-data?`).
- **T3 images (4 kernels).** Twin of `closure-and-continuation-images-keep-float64-content-test` with the S0 boundary integers 2^53, 2^63, 2^64, -2^64-1, 2^100: continuation payload is `machine-data?` and keeps the values; closure lifts through `lift-slice`, is received into task-b, and returns them equal under `canon-ints`.
- **T4 scalar handoff round trip and golden bytes (semantic VM via `lift_support`).** Halted result: a literal map holding every S0 integer name, `f:2^53`, `f:2^63`, 0.0, -0.0, nan (as inf - inf), inf, 5e-324, two strings (one astral), True, None. `lift` with `(header 0)` (version 1; a nil header is version 0 and must not be used here). Assert: `:ok`, version 1, bytes equal `scalars-v1.txt` hex, address equal the fixture, `resume-task` on a fresh machine halts with values equal under `canon-ints` and `float-bits`, and each integer's own `jing.cbor/encode` equals the S0 `cbor` column. The fixture is minted once on the JVM; Node and Dart verify it (ruling 14's golden-on-each-host mechanism).
- **T5 cell lift refusal with Python values (guest, 4 VMs).** A halted Python program whose result is a list holding 2^64: `export-task` refuses `:non-portable :cell`; a module `lift-slice` of a store holding a Python dict refuses `:cell`; a tuple (no cell) holding 2^64 lifts. The kind is `:cell` on every host, never `:host-object`.
- **T6 raw transport.** `dao.stream.cbor/validate-portable` of 2^64 and -2^64-1 answers `{:error :non-portable-value}`; journal append of a bignum and of a float64 carrier refuses without a write; `dao.jing.stream` wrap/unwrap of the Jing bytes of 2^64 and -2^64-1 round-trips, bytes equal the S0 column. Jing stays passive: no codec code changes.
- **T7 version-0 handoff.** The T4 machine lifted with a nil header refuses `:non-portable` kind `:host-object` on every host (today the JVM would throw a bare ex-info).

**Mutations (each red once, reverted, recorded):** drop `big-carrier?` from engine `scalar?` (T1, T2 red on Node/Dart; this is ruling 14's "host `number?` as the only scalar gate", owned by S6); remove the decoder numeric arm (T4 red on Node/Dart); remove the `machine-data?` arm (T2 register VM red on Node/Dart, if it was red to begin with); corrupt one byte of `scalars-v1.txt` (T4 red everywhere).

Size: about 10 src lines, 500 to 700 test lines, one fixture. One round.

## 2. S7: integration gate

### 2.1 The corpus and the ruling 14 detectors

The C1 corpus is already run by `e2e_test`, `e2e_c1_test`, `e2e_c2_test` (JVM, source-level, four VMs) and the C3 corpora by `int_ops_test`, `int_conv_test`, `int_contract_test`, `prelude_parity_test` (prelude notation, three hosts) plus `int_ops_parser_test` (source, JVM). S7 adds the ruling 14 detectors as **source programs** and runs them on three hosts:

- New `test/yang/python/antlr/c3_programs.cljc`: about ten programs as CST packets (the `safepoint_programs.cljc` mechanism, `packet` from `lower_portable_test`), one per theme: promotion then cancellation, demotion (`2**64 - 2**64 == 0`, `is`, dict key), numeric keys and `hash(-1)`, `divmod` and floor sign, shifts (`1 >> 2**70` is 0, `-1 >> 2**70` is -1, `1 << 2**70` is `MemoryError`), power, conversions (`int float str repr hex oct bin round`), signed zero (the C1 cases), resource limits, and the `small` profile. Each program prints its lines; at most 400 rows per program for Dart. The engineer generates packets with the JVM parser and pastes them; the drift test (`portable-packets-are-the-parsers-test` pattern) binds each packet to the parser.
- New `test/resources/yang/python/c3-corpus-v1.txt` and `c3-corpus-v1.generate.py` (asserts 3.9.6, writes from the repo root): program name, CPython stdout. The limit and `small` programs are hand pins, labelled: CPython has no such limits. The orchestrator regenerates at landing as for `int-conv-v1`.
- New `c3_gate_test.cljc` (three hosts, four VMs, `^:slow` under `slow/guard`): each packet lowered and run, output equal to the fixture. New `c3_gate_parser_test.clj` (JVM): the same sources through `e2e/every-vm=` naive and hooked, plus the packet drift check.

**Mutation evidence (ruling 14's seven, each red once, recorded in the S7 report):** disabled promotion (integer fast path) and skipped demotion (`normalize`) against the promotion and demotion programs; double-coerced keys (`py/key-of` through a float) against the keys program; omitted floor adjustment against the divmod program; `abs(n)` for tag 3 (Jing int-wire, reverted) against S0 `canonical-widths-at-the-boundaries` and S6 T4; host `number?` only: cite S6's evidence; narrowed shift count against the shifts program.

### 2.2 Profile mismatch fixtures

- **PM1 admission by name.** New `prelude/admit`: `(admit registry)` returns the registry or throws `{:yang.python.antlr/refusal :yang.python.antlr/host-names, :yang.python.antlr/missing [...]}` listing every `host-names` symbol `module/resolve-module` cannot find. Every Python test composition calls it after its registrars. Fixtures (three hosts, `int_contract_test`): an integer module built as `(module/register-host-module reg 'integer (dissoc (integer/integer-module limits) 'float-digits 'decimal->float 'max-digits) integer/integer-profiles)` is refused naming exactly those three (the version-3 composition); a data module registered without limits is refused naming `data/max-items`; the full composition admits; `register-integer-module` under 52 bits still refuses `:max-bits` first.
- **PM2 cross-profile carry.** Lift (version-1 header) a halted result holding 2^100 under the wide composition; resume under a `small` (60 bits, 5 digits) composition: it succeeds and the value is intact. The body carries no limits; the doc records that profile identity in the record is L-a's. The guest outcomes of that value under `small` are the S0 `small` column, already pinned by `int_contract_test`; S7 adds one assertion tying the resumed value to that column.
- **PM3 lowering budget.** `decimal-digit-budget-test` already covers a literal refused under a smaller `max-digits`; cite, do not duplicate.

### 2.3 Resource limits and item (j)

**Ruling.** The sequence-size limit is an explicit composition datum of the `data` module: `:yin.vm.data/max-items`, a native integer, the largest item or character count one repetition may produce.

- `data/register-data-module` gains a two-arity `(register-data-module registry {::data/max-items n})` that adds the export `max-items [0]`, built like `integer/max-digits`. The one-arity stays for non-Python compositions and exports no `max-items`; the Python profile then refuses it in `admit`. No default anywhere.
- `py/repeat` order: non-int → `TypeError`; count outside Py_ssize_t → `OverflowError` "cannot fit 'int' into an index-sized integer" (CPython's own 64-bit model, kept); negative → count 0; then `(integer/mul size k)` exactly, compared to `(data/max-items)` through `integer/compare`; greater → `MemoryError` with empty args, as `py/int-result` builds it; only then the loop. The 9007199254740991 constant and the `:bit-limit` detour are deleted. `*=` goes through `py/repeat` already.
- Scope: repetition only (`*`, `*=` on str, list, tuple). `list(range(n))`, appends in a loop and comprehension growth are incremental and interruptible at safepoints; they stay operational (ruling 11's last sentence). No per-append check.
- Python test compositions use `max-items` 1048576, written explicitly at every registration site (about twelve test files plus `e2e/host-registry`).
- Fixture rows (hand pins, labelled, in the resource program and `int_conv_test`): `'a' * (2**53-1)` → `MemoryError` caught, promptly; `[0] * (2**62)` → `MemoryError`; `'' * (2**62)` → `''`; `'ab' * 3` → `'ababab'`; `[1, 2] * 524288` succeeds, `* 524289` fails; `'a' * (2**63)` → `OverflowError`; under `small`, `'a' * (2**59)` → `MemoryError` (the bit limit breaches first; same class). Plus the existing limit detectors from source: `2 ** 100000` under 4096 bits → `MemoryError`; `str(10**4300)` and `int('9' * 4301)` → `ValueError`; `print` of a breach prints nothing; a float key under `small` → `MemoryError`.

### 2.4 Remaining S4 ledger

| Item | Ruling |
|---|---|
| `round(x, ndigits)` error order | Fix in S7: raise `py/index-type-error` for a non-int, non-None `ndigits` before dispatching on `x`; fixture row via the generator |
| `pow`, `round` accept keywords | Fix in S7: `:no-kw true` on both specs (the flag exists); `int(x=...)` stays record-only as ruled |
| `py/exc-matches` lint scope | Fix in S7: docstring states the scope is `function-definitions` |
| `0.5 ** -2000` generator row | Fix in S7: add to `int-conv-v1.generate.py`, regenerate |
| Left-shift `MemoryError` past allocation scale (e) | Accepted forever: the profile bit limit is the allocation model |
| `float('-nan')` sign bit | Accepted forever in C3; revisit only with a bits-to-float `data` export (kernel seat) |
| Unicode printable repr escapes | Later: needs a Unicode table in renderer and prelude; record beside ruling 13's unclaimed `format`, `%`, `round(x, n)` |

### 2.5 Files

- src: `prelude.cljc` (`py/repeat`, `round` order, `:no-kw`, `host-names` gains `data/max-items`, `admit`, docstrings), `src/cljc/yin/vm/data.cljc` (limits arity, `max-items` export and profile).
- tests: `c3_programs.cljc`, `c3_gate_test.cljc`, `c3_gate_parser_test.clj`, `c3-corpus-v1.{txt,generate.py}`, `int_conv_test.cljc` (ledger rows), `int_contract_test.cljc` (PM1, PM2), `data_test.cljc` (two-arity, export), every Python test file registering `data` (limits plus `admit`), `int-conv-v1` regeneration.
- Goldens re-minted once, against the final prelude: `float_address_test` prelude root, A, A', record, prelude subtree. Hook prelude address unchanged. `safepoint_test`, `lower_test`, `lower_portable_test`: expected unchanged (S4 found none), checked.
- Doc: S6 and S7 rows landed; ruling 11 paragraph (the `data/max-items` datum, its scope, `MemoryError`); ruling 14 (detectors as programs, mutation evidence location); conversions list (round order, `pow`/`round` positional-only); the PM2 sentence; the three accepted divergences; the prelude docstring.

### 2.6 The gate

S7's landing run is the C3 integration gate: `bb test:changed` three lanes, `clojure -M:test -i :slow -n <ns>` for every changed Python namespace, `bb test:slow:cljs` and `bb test:slow:cljd`, and once, `bb test:all` (every lane, slow included), counts recorded in the report. Ruling 14 is then met: four VMs on three hosts, goldens on each host, C1 corpus, generated operands with CPython expectations, detectors, seven mutations.

## 3. Order and dispatch

- Parallel: yes. S6 touches handoff, vm.cljc, UCF and stream tests, `int_heap_test`; S7 touches prelude, data, Python tests, docs rows. S6 lands first (smaller, no goldens); S7 rebases, adds `max-items` and `admit` to S6's `int_heap_test` composition.
- S6: codex gpt-6.1-sol, one round, glm gate. S7: claude opus, one round (a fix round is likely given the registration fan-out), glm gate; the orchestrator runs both Python generators at landing, as for S4.
- Each brief: foreground lanes, `< /dev/null`, iterate on `bb test:clj`, full three-host lanes once per round at landing, one lane set at a time.

## 4. Linked prelude (P1, P2 after S7)

S4 design section 5 stands. In addition:
- S7's limit reaches the prelude only through `data/max-items`; no composition datum in a row.
- `admit` is host-side `.cljc`, not rows, and is exactly what L-a's requirement discovery replaces: one function to delete.
- S7's expectations are stdout text and S0 columns, never code addresses, so P1 and P2 re-mint nothing of S7's.
- S6 adds no UCF marker and no Python awareness to handoff; the numeric arm is Jing-kind based. The `scalars-v1` body holds no prelude, so P1 and P2 do not move it.
- Completion criteria that must still hold after P1/P2: every S7 program prints the same text linked as bundled; `admit`'s refusals become L-a's manifest refusals with the same names.

## 5. Traps for the briefs

- No `0.0` or `1.0` literal; build floats with `data/float-value` or from bits; NaN as inf - inf, never `##NaN`.
- `#?(:cljd ... :clj ...)` with `:cljd` first; no `for`/`doseq` over more than 32 elements on Dart; start maps from `{}`; no `[_ _]` protocol params; negate floats with `(* -1.0 x)`.
- Integer literals at or past 2^53 only through `exact-literals` or `py/int-lit` hex; compare through `canon-ints`, floats through `float-bits` (public in `int_ops_test`, do not copy, no `#'`).
- Integral doubles on Dart: `integer/from-float`, never `py/floor`; never host float parse or print.
- One Dart guest program at most 400 rows; `^:slow` plus `slow/guard` for corpus tests.
- S6's T4 must lift with a version-1 header; a nil header is version 0 (T7).
- Delegates: close stdin, foreground lanes, no backgrounding; `bb` tasks rather than inline env vars.
