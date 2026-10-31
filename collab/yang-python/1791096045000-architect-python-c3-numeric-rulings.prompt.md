Created-GMT: 2026-10-04 06:42:12 GMT
Created-Local: 2026-10-04 13:42:12 +07 (+0700)
Coding-Agent: claude (fable-5-1, session 9682d342-338e-4359-9676-8c0bec32eaf7) and codex (gpt-6-astra, resume of thread 01a0f878-281b-7253-ac44-ff2402583d35)
Session-ID: 9682d342-338e-4359-9676-8c0bec32eaf7 (fable); 01a0f878-281b-7253-ac44-ff2402583d35 (astra)

# Task: three Python numeric rulings the next C3 slices need

Role: Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-04 13:42 +07 | Status: active | Rationale: standing mob; design questions go to the architect pair
- Model: gpt-6-astra | Assigned: 2026-10-04 13:42 +07 | Status: active | Rationale: standing mob partner; independent opinion on the same brief

Read-only. Answer independently; do not edit, do not run suites. Repo: /Users/sto/workspace/datomworld (master ff1e0195). Read docs/design/yang.antlr.md 8.5.4 (C3 integers: rulings 1 to 14, the dict-key section
around the reduced-rational keys, the NaN bullet, the "Known limits" bullet and "Integer `is` is value-based through the unchanged `py/is`"), 8.5.5 (float addresses), 8.11 ("`is` on immutable values is same type and value",
owner decision 1) and src/cljc/yang/python/antlr/prelude.cljc (`py/is`, `py/key`, `py/hash`, `py/float?`), src/cljc/yin/vm/data.cljc (`float64`, `float-value`), src/cljc/dao/jing/cbor.cljc (the JS Float64 carrier).

## Q1. `py/is` on floats is host-dependent
`py/is` is host `=` on the wrapped value (ruling 8: value-based, "same type and value"). For floats the host `=` differs: on JavaScript the Float64 carrier's equality treats NaN as equal to NaN and +0.0 as DIFFERENT from -0.0; on the
JVM `=` on doubles gives NaN not equal to NaN (unless the same box) and 0.0 equal to -0.0 (and Dart differs again). So `nan is nan2` and `0.0 is -0.0` likely answer differently per host: a cross-host parity defect against the
"same address, same behavior on every host" invariant, found by fable while reviewing C3-S2. CPython: `0.0 is -0.0` is False (distinct objects), `x = float('nan'); x is x` is True, `float('nan') is float('nan')` is False,
`1.0 is 1.0` is implementation-defined. Rule: what must `py/is` do for floats so every host agrees, consistent with "floats have no object identity here" and with the one-shared-NaN dict-key decision (all NaNs are one key)?
Candidates: (a) float64 CONTENT equality: one NaN (so `nan is nan2` is True, a stated departure like the NaN key), signed zeros distinct; (b) value equality with 0.0 `is` -0.0 True; (c) something else. Give the exact rule, the
implementation seam (where the comparison lives so the carrier, host doubles and canonical bytes agree), the cross-host test that pins it, and any doc text to add to 8.5.4.

## Q2. Integer dict and set keys past the digit limit
Ruling-6 reduced-rational keys format integers as decimal text through the integer module's base-10 formatting, which is refused beyond ::max-digits (4300 in the test configuration). CPython's limit applies to `str()` and
`int()` conversions, NOT to hashing or dict keys, so a large integer key that works in CPython is refused here. Not reachable from Python source yet (big literals and operator promotion are later C3 slices). Rule: (a) key with a
power-of-two radix (hex), which the digit limit exempts; (b) keep decimal and record the limit as a documented departure; (c) bind the key's digit budget separately from the str() budget; (d) other. State the exact key form if it
changes (the ruling-6 key shape is `[:py.numeric/finite numerator-decimal denominator-decimal]` and is pinned in goldens), the effect on the 2^53 vs 2^53+1 and 1/1.0/True equalities, the migration cost (the canonical-bytes golden
in dict-keys-test), and the slice that should carry it.

## Q3. Guest exceptions for integer-module refusals
Today the prelude does not map `integer` refusals (digit/bit limit, wrong type) to guest exceptions: such a refusal fails the whole run with a host ex-info (docs say so in 8.5.4 "Known limits"; ruling 11 plans MemoryError/ValueError).
Which C3 slice should deliver the mapping, what is the mapping table (limit exceeded to MemoryError or OverflowError or ValueError, per CPython 3.9.6 behavior for the same operation), and must it precede the operator slice
(C3-S3) that makes big integers reachable from source? Give an order of the remaining C3 slices (S0 to S7 in 8.5.4's table) that respects dependencies and fixes where Q1 and Q2 land.

Give a recommendation, not a survey; one-line verdict per question first.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
