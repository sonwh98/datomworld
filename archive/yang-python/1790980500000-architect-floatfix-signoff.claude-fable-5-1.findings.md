Completed-GMT: 2026-10-02 22:29:31 GMT
Completed-Local: 2026-10-03 05:29:31 +0700

# Float-fix sign-off: two rulings

Read-only; I inspected `/Users/sto/workspace/datomworld-py-floatfix` (uncommitted diff), the C3-S2 worktree `datomworld-py-c3key1`, its brief, and the engineer's report. I ran one Node one-liner to confirm JS semantics for question 2; no project lanes were run and no files were edited.

## Q1. Dict-key normalization and C3-S2 sequencing

**What was built.** `data/numeric-key` (`src/cljc/yin/vm/data.cljc`, new, under the float seam) keys a numeric as the integer when `integral` (bounded ±(2^53−1), `data.cljc:74-86`) accepts it, normalizing JS `-0` with `(+ i 0)`, else as `float64` content. `py/key` calls it through `py/num` (`prelude.cljc:1192-1197` in the fix tree). Pinned cross-host by `dict-keys-test` (`test/yang/python/antlr/float_address_test.cljc:316`).

**Assessment.**
- It is correct within C1's contract (1, 1.0, True one key; ±0.0 together) and byte-identical on every host, which the old `(* 1.0 (py/num k))` was not: on the JVM that produced a Double 1.0 key inside the dict cell, a heap value that reaches snapshots, so the old key form was itself an address divergence. Leaving keys "untouched" is not an option: the old form is also an integral float literal in the prelude, which the ruling forbids.
- It is **not** a foundation for C3 ruling 6. Its float64 fallback above 2^53 merges distinct large integers (2^53 and 2^53+1 round to one double), which is exactly the C1 defect ruling 6 exists to remove (`docs/design/yang.antlr.md:2069-2079`, `:2574-2580`; the S2 brief, items 1 and 45-46). Stringifying over it would stringify a lossy value.
- Residual, pre-existing, not blocking: a NaN key behaves differently per host (the JS carrier's `-equiv` makes NaN keys equal, `cbor.cljc:351-355`; JVM `Numbers.equal` never does). Ruling 6 says "NaN keeps today's behavior", but today's behavior is two behaviors. C3-S2 should pin one.

**Sequencing.** `datomworld-py-c3key1` has not touched `py/key` yet (`prelude.cljc:1171-1176` there is still master's text; only four test files are modified). So:

1. Float-fix lands first, with `numeric-key` as the interim key.
2. C3-S2 rebases onto it and **replaces** `py/key`'s numeric arm with the ruling-6 reduced-rational decimal-string key, built from `data/float-value`-unwrapped doubles through the integer module's exact binary64 decomposition (`yang.antlr.md:2016-2018`). A key must never contain a float64 carrier or a bare double; strings and vectors of strings are host-identical by construction.
3. C3-S2 deletes `data/numeric-key` and its registry entry (`data.cljc` export table) and updates the float-key expectation in `dict-keys-test`. Nothing else may depend on `numeric-key` in the meantime.

## Q2. Bridge scope

**The engineer's reading is correct as scope, but not safe as built.** Correction 2 named gates (`plain-data?`, `machine-data?`, `scalar?`); the engineer rightly added the de Bruijn executable-scalar gates (`debruijn_code.cljc:344-353`, both `double-le-hex` copies, with a real −0.0 sign fix) and `values/kind-of` (`values.cljc:196`). The ruling's seam model is that values re-enter host arithmetic only through Python's `data/float-value`. Generic yin was never promised carrier arithmetic: `dao.jing.cbor.md:420` keeps VM arithmetic outside the migration.

**But opening the gates changed a loud failure into silent garbage on JS.** Before the fix, a JVM-minted generic row holding a float literal decoded on Node to the carrier (`cbor.cljc:1436-1439`) and was refused at row validation (`vm.cljc:1308`, `(:data :key) (plain-data? v)`). Now it is admitted, and yin's pure registry binds `+ - * / < > <= >=` to host functions (`vm.cljc:382-392`). On JS those coerce an object through `toString`. Confirmed with Node: for a carrier-like object `f` holding 1.5, `f+1` is the **string** `"1.51"`, `f<2` is `true`, `f*3` is `4.5`, `f-0` is `1.5`. So `(+ 1.5 1)` in a generic yin program lowered on the JVM and run on Node yields a string, with no error.

**Ruling: the bridge stays Python-scoped, and this slice must restore the loud refusal on the one surface that can now compute on a carrier.**

- In `vm.cljc`'s `pure` wrapper (`:379-380`), on `:cljs` only, the arithmetic and ordering entries (`+ - * / < > <= >=`) refuse any `cbor/float64?` argument with a shaped error (the `:yin.k/non-portable` fail at `engine.cljc:628-629` is the nearest existing shape; a plain `ex-info` naming the primitive and kind is acceptable). One guard, applied in one place. `= == !=` are left alone: they are host `=` by contract already.
- **Do not** unwrap in `pure`. Unwrapping makes results bare numbers, so `(+ 1.5 0.5)` becomes the integer 2 on JS when re-addressed, which recreates the divergence this slice removes, now inside generic yin. Carrier-preserving arithmetic for generic yin is a separate slice (portable VM arithmetic, the yin analogue of C3), not this one.
- Record the gap where the engineer already amended the UCF doc (`yin.vm.universal-continuation-format.md`, scalar-carrier note): on JavaScript a float64 carrier is a scalar for rows, images and hashing; generic yin primitives refuse it; only a runtime profile with an explicit seam (Python's `data/float64`/`data/float-value`) computes on floats portably.
- Add one Node test: a generic yin row with a float literal, decoded from JVM-minted bytes (or built with `cbor/float64` directly), applied to `+`, must refuse, not return a string.

The other items in the report need no action from this slice: `linker/portable-scalar?` (link ids are never floats) and the "no capture inside the seam" reliance on the existing safepoint test are both acceptable, the latter until C2 yields inside prelude bodies, when it must be pinned explicitly.

**Ruling:**
1. **Q1:** sign off `data/numeric-key` as the **interim** key; float-fix lands first; C3-S2 rebases onto it and **replaces** the numeric arm with ruling-6 decimal-string keys (built from unwrapped doubles via exact decomposition, never containing a carrier or double), then deletes `numeric-key`; C3-S2 also pins one NaN-key behavior.
2. **Q2:** the Python-scoped bridge is the correct reading; universal unwrapping is rejected. Landing is conditional on one addition: yin's `pure` arithmetic/ordering primitives refuse a float64 carrier loudly on JS (one guard in `vm.cljc:379-380`), with one Node test and the documented gap; carrier arithmetic for generic yin is a separate slice.

Status: COMPLETE
