Created-GMT: 2026-11-20 18:00:40 GMT
Created-Local: 2026-11-21 01:00:40 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a1fa0a-94cf-7df3-acd0-16353342ed04
Role: Lead System Architect
Model: gpt-6.2-astra

# Task: Architecture review r4 confirmation of docs/design/yang.preludes.md (all 13 r3 findings reconciled)

You are acting in the **Lead System Architect** role (`docs/agents/roles/architect.md`) under the ground rules of `docs/design/datom.world.md` and `docs/agents/malleability.md`.

Please perform a **confirmation review (r4)** of the updated design document:
- `/Users/sto/workspace/datomworld/docs/design/yang.preludes.md`

Against your prior r3 review:
- `/Users/sto/workspace/datomworld/collab/yang-base/1795196337796-architect-yang-preludes-design-r3.gpt-6.2-astra.findings.md`

Note that you are running **read-only** in the repository. Do NOT edit any files.

## What was updated between r3 and r4

All 13 r3 findings (9 HIGH, 3 MEDIUM, 1 LOW) have been reconciled in `docs/design/yang.preludes.md`:

1. **HIGH (`§7.2`, `§4.3` — Float `//`, signed zero, and full float/mixed arithmetic rules):**
   - `yrt.float` exports total `float-floor-raw` / `float-floor` (mirroring `yang.python.antlr.prelude:1441`) and status-returning `floor-int-result` / `neg-pow-result`.
   - `clj.py/float-floordiv-raw` now mirrors `py/float-divmod` (`src/cljc/yang/python/antlr/prelude.cljc:1427–1437`): preserves signed-zero quotients (`(* (data/float-value 0) (/ x y))` when `div == 0.0`, pinning `-0.0 // 2.0 -> -0.0`) AND applies the quotient rounding correction `(if (< (data/float-value 0.5) (- div f)) (+ f (data/float-value 1)) f)` (pinning `2.1 // 0.7 -> 3.0`).
   - Added explicit Float × Float and Mixed Int × Float rules for `+`, `-`, `*`, `/` (`yrt.float/zero?` divisor check raising guest `:zero-division`, else `yrt.float/fdiv`), and unary `-` / `+` (`(* (data/float-value -1) x)`).
2. **HIGH (`§7.2`, `§7.5` — `integer/*` limit checks before `compare-float` / `data/nth` / `integer/neg`):**
   - `§7.2` item 2 mandates that EVERY `integer/*` operation returning through `yin.vm.integer`'s `out` / `pair` contract (`add`, `sub`, `neg`, `mul`, `floor-div-mod`, `quot-rem`, `pow`, `to-float`, `true-div`, `from-float`, `format`) passes through `yrt.int/check-limit` BEFORE indexing via `data/nth` or passing results to subsequent primitives.
   - `clj.py/float-pow` (`float-exp-info`) unwraps `y = (data/float-value y-boxed)` in a pure leaf helper, runs `e = (integer/from-float y)`, checks `(integer/integer? e)` **before** calling `(integer/compare-float e y)`, and returns any `:yin.vm.integer/bit-limit` out of the leaf frame for translation via `yrt.int/check-limit`.
   - Mixed ordering (`<`, `<=`, `>`, `>=`) unwraps boxed floats via `data/float-value` inside a pure leaf helper, excludes NaN, and passes only native host doubles to `integer/compare-float`.
   - Standalone `clojure.core/rem` binds `qr = (yrt.int/check-limit (integer/quot-rem a b) ...)` BEFORE `(data/nth qr 1)`.
3. **HIGH (`§7.2`, `§7.3`, `§7.5` — Uniform portable map/set key & element domain):**
   - Defined one uniform `v1` recursive key/element admission gate (`admit-key!`) shared across BOTH `clj.py` (`map-from`, `set-from`, `get-item`, `comp-set-add`, `comp-dict-set`) AND standalone `clojure.core` (`assoc`, `assoc-in`, `update`, `update-in`, `get`, `get-in`, `contains?`, `conj` on maps/sets, `disj`, `merge`, `into`, `union`, `difference`, `hash-map`, `set`).
   - Refuses any `data/float64?` or `seq?` (or host closure/continuation/`cell-ref`) at any key/element nesting depth (`-0.0`, `1.0`, `NaN`, `[[-0.0]]`), while keeping arbitrary floats, lists, and callables supported as map **values** and vector/list elements.
   - `clj.py/eq` compares maps via equal count + admitted key lookup + recursive `eq` on values (handling `{"a": -0.0} == {"a": 0.0}` and `{"a": NaN} == {"a": NaN}` on all 4 hosts).
4. **HIGH (`§7.4`, `§7.5` — Prelude `if` nesting instead of `and`; `fn?` aligned 1-to-1):**
   - Rewrote `clj.bridge/admitted-callable?` and standalone `clojure.core/fn?` using nested `(if ...)` (zero `and` special form per `src/cljc/yang/core/uast.cljc:82`).
   - Removed the `(data/callable? x)` fallback from `clojure.core/fn?` so `fn?` and the dynamic higher-order caller share the exact same authenticated cell admission predicate.
5. **HIGH (`§7.4` — Full 4-step invocation-record validation mirroring `py/function-entry:906–943`):**
   - Step A: Establish `(data/map? invocation)` and `(data/callable? (get invocation :yang.call/raise))` first (failing operationally if unusable).
   - Step B: Outside `try-value`, check `(yang.call/valid-invocation? invocation)` AND owner identity `(= (get invocation :yang.call/callee) owner)`, transferring `:yang.call/invalid-invocation`.
   - Step C: Outside `try-value`, reject any `:yang.call/binding-profile` with `:yang.call/unsupported-binding`.
   - Step D: Inside `yrt.escape/try-value` on `(clj/rt-ctx)`, run parameter binding and body execution.
6. **HIGH (`§6.3`, `§7.3`, `§7.5` — Cell-free linker exports, idempotent `init!`/`export`, 0-arg accessors, `raw-*` internal adapters):**
   - `clojure.core` publishes zero `:cell-ref` values in `:slice` (`yin.vm.engine/lift-slice:1055`, `engine.cljc:915`): internal `clojure.core.rt/{state,exports}`, idempotent `(clojure.core/init!)`, `(clojure.core/export sym)`, pure 0-arg cell-accessor exports for every public name, and separately named `clojure.core/raw-seq`, `raw-first`, `raw-rest` (reserved/banned from source `:ambient-projections`) for trusted Layer-2b hooks like `clj.py/for-each`.
7. **HIGH (`§2.2`, `§3`, `§6.3`, `§7.3`, `§7.4`, `§8.2` — Trusted `clj.bridge` ownership, bundled parity, and shared `(clj/rt-ctx)`):**
   - Consolidated Clojure-family §8.10.7 provenance doors (`cell/dsg__new-provenance`, `cell/dsg__provenance`) into stateless trusted Layer-2b module `clj.bridge` in **both** bundled (`uast`) and linked (`module-uast`) emitters (leaving `clj` golden-neutral in P3 and keeping provenance doors out of `yrt.*`, `clj.py`, and Layer-2c `clojure.core`).
   - Explicitly recorded in §2.2 invariant 1 that `clj.bridge`, `clj.py`, and `clojure.core` run inside the Clojure semantics/exception domain and intentionally share `(clj/rt-ctx)`, and gated nested `clj → clj.py → clj` unwinds in P3.
8. **HIGH (`§7.2`, `§7.5` — Audited retained `clojure.core` exports for 4-host determinism):**
   - Added pure P3 host prerequisite `kind/namespace`.
   - Rewrote `fresh-symbol` and `gensym` onto `integer/integer?` + `integer/add` + `integer/format` + `yrt.int/check-limit` + `data/str-concat`.
   - Specified explicit 4-host deterministic `str` rendering (covering `data/float64?`, `1.0 -> "1.0"`, `-0.0 -> "-0.0"`, `NaN`, `±Inf`, exact ints, keywords/symbols via `kind/namespace`/`kind/name`, canonical maps/sets).
   - Specified `canonical-compare` deterministic ordering across `raw-seq`/`seq`/`first`/`rest`/`vec`/`keys`/`vals`/`str`/`reduce`/`map`/`filter`/`remove`/`into`/`merge`/`union`/`difference`, exact-int `nth` on sequential collections only, `map?`/`reduced?` exclusion of authenticated cell-refs, and excluded `invoke-value` alongside `var?`/`resolve`.
9. **HIGH (`§6.2`, `§6.4` — All executable descriptor fields validated against selected closure):**
   - Updated `validate-descriptor` to validate `:hooks` (including optional `:comp/finish`), executable `:literals.*` fields (`:literals.*.overflow-op`), AND `:ambient-projections` against the descriptor's **own selected, address-verified closure** (`:ambient-modules` + transitive `:requires`), enforcing qualification, frontend-authorized role/arity, and the reserved-door ban (including `raw-*`).
10. **MEDIUM (`§5.2` — `admit-profile` `host-limits` & direct vs. transitive `:requires`):**
    - Updated `admit-profile` signature to `(profile artifact-store registry host-limits)` (explicitly referencing `yin.vm.module:249` and `yang.python.antlr.prelude:4163`).
    - Separated checking root `:requires` == `:yang.prelude/components` from recursively verifying the full transitive `:requires` closure across `artifact-store`.
11. **MEDIUM (`§4.3`, `§7.2`, `§9` — Corrected P1 underflow witness & `data/float64?` phase alignment):**
    - Fixed P1 gate to pin **`0.5 ** -2000`** for `:yrt.float/underflow` + outer overflow policy, `2.0 ** -1074` for exact subnormal `5e-324`, and `2.0 ** -2000` for normal boxed `+0.0`.
    - Removed `data/float64?` from `yrt.float`'s P1 dependencies and placed both `data/float64?` and `kind/namespace` cleanly in P3.
12. **MEDIUM (`§6.3`, `§6.4`, `§7.3`, `§9` — Valid Python bare `except:` grammar & comprehension accumulators):**
    - Replaced `except as e:` with bare `except:` (noting `Python3Parser.g4:313` grammar rule) and kept typed `except ExcType [as e]:` under `:control/except-typed`.
    - Specified comprehension accumulator hooks (`:comp/list-new`, `:comp/list-append`, `:comp/set-new`, `:comp/set-add`, `:comp/dict-new`, `:comp/dict-set`, optional `:comp/finish`), cell-backed mutable accumulators in `clj.py`, and `nil` `:comp/finish` in `:yang.python/py-v1` to keep default Python UAST byte-identical.
13. **LOW (`§5.1`, `§10 D7` — Complete Python 6-effect example & consistent D7 `-raw` names):**
    - Fixed §5.1 `:yang.prelude/max-effects` comment to list the verified Python 6-effect closure (including `:cell/new-provenance` and `:cell/provenance`).
    - Aligned §10 D7 option text to `yrt.float/fpow-half-raw` and `yrt.float/fpow-base-raw`.

## Review Output Format

1. Run `date -u` and `date` in your read-only shell if needed so your output starts with accurate `Completed-GMT:` and `Completed-Local:` lines.
2. **Section 1 — Verdict:** State either **`READY`** or **`REVISE`** concisely, with a brief executive assessment. Note explicitly what you verified read-only (no files edited).
3. **Section 2 — r3 Finding-by-Finding Disposition:** A markdown table covering all 13 r3 findings (#1–#13), each marked `CONFIRMED-FIXED` or `OPEN`, plus confirmation that previously closed r1/r2 invariants remain intact without regression.
4. **Section 3 — New or Remaining Findings (if any):** List any remaining issues with **Severity** (`CRITICAL` / `HIGH` / `MEDIUM` / `LOW`), **`file:line`**, **Invariant / Evidence**, and **Recommended Correction**.
5. **Section 4 — Owner-Decision Confirmation (D1–D7):** Briefly confirm D1–D7 remain accurate and cleanly framed for owner disposition.
