Completed-GMT: 2026-10-03 17:17:24 GMT
Completed-Local: 2026-10-04 00:17:24 +07
Coding-Agent: claude
Session-ID: 65b24574-4b26-45d4-993a-cfccf0b2604a

# C3-S2 round 4 — rebased onto eaf7d6f0 and reconciled with float-fix

The orchestrator did step 1: rebased onto master eaf7d6f0 and re-applied the round-3 diff. I resolved
the three conflict hunks and did steps 2-4 and all lanes.

Nothing staged or committed. The index still shows `UU` for prelude.cljc and safepoint_test.cljc
(left by the orchestrator's stash pop), and `M ` staged entries for e2e_test.clj, e2e_c2_test.clj and
prelude_parity_test.cljc. I am not allowed to stage, so whoever commits must `git add` those paths;
the working-tree contents are final and marker-free.

## Changed files (vs HEAD eaf7d6f0)

| File | Change |
|---|---|
| src/cljc/yang/python/antlr/prelude.cljc | ruling-6/7 keys and hash; conflicts resolved; `data/numeric-key` removed from host-names |
| src/cljc/yin/vm/data.cljc | `numeric-key` and its export deleted |
| test/yin/vm/data_test.cljc | the numeric-key testing block and its export-set entry deleted |
| test/yang/python/antlr/float_address_test.cljc | integer module installed; dict-keys-test re-pinned to ruling-6 keys; 6 goldens re-minted |
| test/yang/python/antlr/prelude_parity_test.cljc | S2 cases follow master's float64 discipline (`with-float64`, `data/float-value` literals); NaN pin |
| test/yang/python/antlr/safepoint_test.cljc | master's form, `^:slow` tag and guard kept; adds the `yin.vm.integer` require and module registration |
| test/yang/python/antlr/e2e_test.clj, e2e_c2_test.clj | merged cleanly (integer module, 4 new e2e tests); master's `^:slow` tags intact |
| docs/design/yang.antlr.md | the 8.5.4 NaN bullet, the 8.5.5 sentence at ~2288, and the C3 status bullet |

## Reconciliation

- `py/key`'s numeric arm is the ruling-6 form, `[:py.numeric/finite "<num>" "<den>"]`, reduced and in
  exact decimal:
  - ints and bools go through `integer/format`;
  - floats go through `py/float-key (py/num k)`. `py/num` reads the payload through
    `data/float-value`, so the float64 carrier never meets host arithmetic. This covers the JS
    carrier's `valueOf` throw.
  - `py/hash` likewise reads `(py/num x)` instead of `(get x :py/float)`.
- `1`, `1.0` and `True` fold to one key; `0`, `False`, `0.0` and `-0.0` fold to `0/1`.
- The prelude gains no float literal. `prelude-float-discipline-test` passes on all three lanes.

## NaN-key decision

Every NaN is one key, `[:py.numeric/nan]` (recorded in yang.antlr.md 8.5.4). Why:
- A float here is a value with no object identity, so "the same NaN object" cannot be told from
  "another NaN".
- Jing float64 already normalizes every NaN to one canonical content, so a per-object key cannot
  survive addressing.
- The old host-double key was nondeterministic: on the JVM `(= x x)` is true for one boxed NaN and
  false for two.

This departs from CPython, where distinct NaN objects are distinct keys. It keeps
`x = nan; d[x] = 1; d[x]` working.

## Deleted

- `data/numeric-key` (data.cljc, former 452-464) and its export (`['numeric-key [1] numeric-key]`).
- The prelude host-names entry.
- data_test.cljc's numeric-key testing block (7 assertions) and its export-set entry.

## Re-minted goldens (float_address_test.cljc, JVM; Node and Dart pass the same assertions)

The prelude change moves every address that bundles the prelude. The hook prelude is unchanged.

| Golden | Old | New |
|---|---|---|
| prelude/uast root | 38a0e750… | d5ce18b63ffa126466380d80df4fcd45d9ca718511ea19b8881fce7b03e1833e |
| A root | f941b996… | eb847184473e99604ee7e58b6fb5e5d23076e1e0ed56f618f7a842a1b828d796 |
| A′ root | f05a91d4… | 612c10c41e7642d87e75b3e4a36b3513d05748158653999f1b83556c18a181f4 |
| derivation record | 39bfcf1e… | 078367c10fdd5dd33fec4516b0f28821e170f64a3202b6ec103086e5c20b298a |
| prelude subtree in A/A′ | f976da2e… | baf96f136bd9210564423327ff34fab380102903ed9bd166a011e8c1aae01e6e |
| hook prelude root | 76e1cfe8… | unchanged |

dict-keys-test: the value expectation changed from `[1 1 0 float64(0.5)]` to the four
`:py.numeric/finite` keys (1/1, 1/1, 0/1, 1/2). Its canonical-bytes base64 golden was re-minted.

## Lanes (all foreground)

| Lane | Result | Time |
|---|---|---|
| Focused, before 23:45: float-address, prelude-parity, data | 46 tests / 536 assertions, 0 failures | — |
| Focused: the 4 new e2e vars | 4 / 48, 0 failures | — |
| `bb gen:python-antlr` | OK | — |
| JVM `bb test:clj` (excludes `^:slow`) | 2906 tests / 226619 assertions, 0 failures, 0 errors | 6:27 |
| Node `bb test:cljs` | 2720 tests / 91647 assertions, 0 failures, 0 errors | 4:31 |
| Dart `bb test:cljd` (run 1) | finished, verdict lost to output truncation | 9:28 |
| Dart `bb test:cljd` (run 2) | "+2675: All tests passed!" | 7:15 |
| Slow JVM, e2e-test recursion-test and keyboard-interrupt-test (`-i :slow`) | 2 / 37, 0 failures | 0:55 |
| Slow JVM, safepoint-test/tail-preservation-test and e2e-c2-test long-generator-with-break-test and resume-continuation-length-is-stable-test | 3 / 42, 0 failures | 2:54 |
| kondo, 8 changed files | 0 errors, 0 warnings | — |
| cljstyle fix then check, 8 changed files | clean | — |

Notes on the runs:
- **Dart, run 1:** my `cut -c1-400` truncated the carriage-return-joined progress line that carries
  the verdict, so I reran rather than claim it.
- **Dart runner rule:** a Dart runner was active in the main tree (`/Users/sto/workspace/datomworld`)
  before 23:45: the orchestrator's verification. I left it alone. It had exited before my CLJD runs,
  so there were no orphans to kill.
- **long-loops-test:** excluded from the slow run, as instructed.

## Unfinished and concerns

- Index state (UU and staged entries) needs the committer's `git add`; see the top.
- Unchanged from round 3: bignums do not reach guests from Python source yet (literals past 2^53 and
  operator promotion are later slices). Bignum keys, `hash(2**80)` and `2**70 is 2**70` are pinned at
  prelude level on all four VMs on every host.
- `hash()` of inf, NaN, str, None and tuples raises NotImplementedError; any cell raises TypeError.
- No mutation evidence was recorded.
- The NaN pin is my decision under the ruling's "pin one" instruction and wants Architect
  confirmation.
- The landed 8.5.5 implementation paragraph (~2268) still lists `numeric-key` among the three data
  exports. Per the brief I did not rewrite landed 8.5.5 text; only the ~2288 sentence now records
  the deletion.

---

# Round 5

Completed-GMT: 2026-10-03 17:45:50 GMT
Completed-Local: 2026-10-04 00:45:50 +07
Coding-Agent: claude
Session-ID: 65b24574-4b26-45d4-993a-cfccf0b2604a

Base: master a932bb55 (rebased by the orchestrator). No git write commands were run. The index still
shows `UU` for float_address_test.cljc and `MM` for three files; the committer must `git add`.

## Per item

**A. Conflict markers.** Removed from float_address_test.cljc, keeping upstream; the real values come
in E. My round-4 edits in that file (integer module, dict-keys-test) were outside the hunks and had
survived.

**B. Docs (yang.antlr.md).**
1. The 8.5.5 implementation paragraph now names two data exports (`float64`, `float-value`).
   `numeric-key` remains only in the historical dict-key paragraph.
2. The 8.5.4 NaN bullet gains:
   - (a) the equivalence-class sentence, verbatim;
   - (b) the three Python-terms cases, the float-identity departure (the same family as ruling 8's
     `is`), and tuple inheritance through `:py/tuple-key`;
   - (c) the `exact-key` clause, rewritten as "a storage-layer helper, not the guest key contract,
     and is not reused".
3. The ~2130 sentence now reads "CPython NaN object-identity fidelity remains unsupported;
   deterministic NaN key behavior is now specified." Float rendering parity stays tracked.
4. A "Known limits" bullet in 8.5.4 covers:
   - integer keys past `::max-digits` (base-10 formatting) are refused;
   - float keys need limits of at least 1075 bits and 324 digits;
   - smaller limits refuse and never approximate, and must surface as the ruling-11 guest failure.
   It also states that today the prelude does not map `integer` refusals to guest exceptions, so such
   a refusal fails the run.

**C. Portable tests (prelude_parity_test.cljc, four VMs).**
1. New `preserved-key-arms-on-every-host-test`, through `py/key` by dict and set insertion:
   - two classes, a generator and the `len` function stay four distinct keys, and reinsertion
     updates the value;
   - list, dict and set keys, a tuple holding a list and a tuple of a tuple holding a dict each
     raise TypeError;
   - set dedup keeps the first original key: `[1 1.0 True 2]` gives keys `[1 2]`, and
     `[True 1 1.0]` gives keys `[True]`.
2. New `nan-keys-on-every-host-test`. x is inf - inf, y is 0 * inf, and z is a decoded canonical
   Jing float64 NaN. Checked:
   - `d[x]`, `d[y]` and `d[z]` all find `x -> 1`;
   - `y -> 2` leaves one entry, and `d[x]` is then 2;
   - the set of x and y has length 1, and membership works through x, y and z;
   - the tuple keys `(1, y)` and `(1.0, z)` find `(1, x)`.
   A test comment says a "same object" case is not testable (no object identity).
3. Boundary cases (key and hash, values checked against CPython):

   | Value | Key | hash |
   |---|---|---|
   | max finite | (2^53-1)*2^971 | 2234066890152476671 |
   | -5e-324 | -1/2^1074 | -16777216 |
   | smallest normal | 1/2^1022 | 32768 |
   | largest subnormal | 4503599627370495/2^1074 | 2305843009196949503 |

4. The integer `-0` case (`(* -1 0)`) keys as "0" and hashes as 0. This needed a prelude fix:
   - on JS, `-0` is not an exact integer to the integer module, so `integer/format` would refuse;
   - new `py/int-canon`, `(if (= n 0) 0 n)`, is applied in `py/key`'s int arm and `py/hash`;
   - `(+ n 0)` was rejected because `+` throws on a JS or Dart BigInt.

   The JS behaviour is verified by reasoning only; I ran no Node lane this round.
5. Optional small-limit test: **not done.** A test today could only pin the host refusal
   `[:thrown "integer primitive refused"]`, the wrong contract; the guest-failure mapping is pending.
6. `range-fast-path-on-every-host-test` gained the two edge lookups under the same stubbed prelude:
   `range-at(range(0, 67108865), 67108864)` is 67108864, and
   `range-at(range(0, 67108866), 67108865)` enters `range-elem`.
   The stub sentinel changed from `:range-elem-entered` to -12345: range-at compares the stub's result
   with `stop`, a keyword would throw on the JVM but not on JS, and no element of these ranges equals
   -12345.

Found while doing C: a vector literal in prelude notation is data, not evaluated. My round-3 case
`(py/key (py/tuple [1 two-53+1]))` therefore compared two quoted forms and proved nothing. It now
builds its vectors with `py/conj`, as do all the new tuple and set constructions; it passes.

**D.** The `py/hash` comment names the fourth arm: a tuple is a valid key through `py/key`, but
`hash()` of a tuple, a string or +-inf is not yet supported (NotImplementedError).

**E. Goldens, re-minted last** (JVM; the orchestrator's Node and Dart lanes check the same
assertions).

| Golden | Old (master a932bb55) | New |
|---|---|---|
| prelude root | 3d287776… | d99d4805e2f9ca83367176890b2b084b1b79da8024368c3db1f58ee664b35d1d |
| A | 38e17ba2… | d9185a98b7ea85f1c21b757127cbc0f50acf3019d655a22e03e5dd2b829fe2fc |
| A′ | 270bc081… | b3a62d04067505d5b11702e4074ec1cf67a65262fe54cc128045fd1a74a6f422 |
| record-address | 88d5972b… | 48586d14f8f19c5e4ff58795e17202133e3a953a72d688f67fe929c045f9d00f |
| prelude-id | 9eb6ff93… | 306f688f8249931e9950c7fe36c0a141e191b766b0e1c70956fabbeb462a4ac2 |
| hook prelude | 76e1cfe8… | unchanged |
| dict-keys-test bytes | hIPYG4… | unchanged (same key values) |

`git diff HEAD` on the file shows those five lines plus my round-4 changes in the same file:
the integer-module require and registration, and the dict-keys-test re-pin. HEAD is master, which
never had those.

## Focused runs (JVM only; no full lanes, no Dart)

| Run | Result |
|---|---|
| yang.python.antlr.prelude-parity-test | 16 tests / 64 assertions, 0 failures |
| yang.python.antlr.float-address-test (after re-mint) | 12 / 105, 0 failures |
| yin.vm.data-test + yang.python.antlr.lower-test | 48 / 469, 0 failures |
| e2e-test: exact-numeric-keys, numeric-hash, unhashable, integer-is | 4 / 48, 0 failures |
| yang.python.antlr.e2e-c2-test with `-e :slow` | 15 / 75, 0 failures |
| kondo, 3 changed files | 0 errors, 0 warnings |
| cljstyle fix then check | clean |

## Out of scope, recorded only

- `py/is` on floats is host `=` on the wrapped value, so NaN and signed-zero identity differ per host
  (fable).
- The interpreted cost of repeated float decomposition, especially for subnormals (up to 1074
  doublings, each with a divmod descent), is not measured (astra).
- The integer-key digit-limit decision (fable): recorded as a known limit in 8.5.4, not decided.
