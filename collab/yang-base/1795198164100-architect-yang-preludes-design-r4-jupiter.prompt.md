Created-GMT: 2026-11-20 18:09:24 GMT
Created-Local: 2026-11-21 01:09:24 +07 (+0700)
Coding-Agent: codex
Host: jupiter.stigmergy.systems (Codex Pool 2)
Role: Lead System Architect
Model: gpt-6.2-astra

# Task: Architecture review r4 confirmation of docs/design/yang.preludes.md (all 13 r3 findings reconciled; fresh Pool-2 session)

You are acting in the **Lead System Architect** role (`docs/agents/roles/architect.md`) under the ground rules of `docs/design/datom.world.md` and `docs/agents/malleability.md`.

Context on session failover: an earlier Pool-1 `gpt-6.2-astra` r4 confirmation attempt hit its Codex account usage limit mid-run before emitting a final response, so this fresh Pool-2 `gpt-6.2-astra` session is performing the complete read-only r4 confirmation inside `/home/sto/tmp/yang-preludes-r4-review` (commit `9cf135c2` + the updated `docs/design/yang.preludes.md` + prior findings). Do NOT edit any files.

## Read first (repo-relative paths)
1. `docs/agents/roles/architect.md` and relevant sections of `docs/design/datom.world.md`
2. Prior Lead System Architect reviews:
   - `collab/yang-base/1795196337796-architect-yang-preludes-design-r3.gpt-6.2-astra.findings.md` (primary baseline — all 13 r3 findings)
   - `collab/yang-base/1795186644731-architect-yang-preludes-design-r2.gpt-6-astra.findings.md` (and `1795169993265-architect-yang-preludes-design.gpt-6-astra.findings.md` as needed to confirm zero regressions on closed r1/r2 invariants)
3. Updated target design document:
   - `docs/design/yang.preludes.md` (794 lines)
4. Referenced live implementation / spec contracts (read-only cross-checks):
   - `src/cljc/yang/python/antlr/prelude.cljc`, `src/cljc/yang/python/antlr/lower.cljc`, `antlr/python3/Python3Parser.g4`
   - `src/cljc/yang/core/{uast,call,prelude,prelude_admission,module,publication}.cljc`, `src/cljc/yang/clojure.cljc`, `src/cljc/yang/clojure/prelude.cljc`, `src/cljc/yang/frontend.cljc`
   - `src/cljc/yin/vm/{data,integer,engine,module}.cljc`, `src/cljc/dao/jing/cbor.cljc`, `src/cljc/dao/host/number.cljc`, `docs/design/dao.jing.cbor.md`, `docs/design/yang.antlr.md`

## Summary of all 13 r3 reconciliations made in `docs/design/yang.preludes.md`

1. **HIGH (`§7.2`, `§4.3` — Float `//`, signed zero, and full float/mixed arithmetic rules):**
   - `yrt.float` exports total `float-floor-raw` / `float-floor` (mirroring `src/cljc/yang/python/antlr/prelude.cljc:1441`) and status-returning `floor-int-result` / `neg-pow-result`.
   - `clj.py/float-floordiv-raw` mirrors `py/float-divmod` (`src/cljc/yang/python/antlr/prelude.cljc:1427–1437`): preserves signed-zero quotients (`(* (data/float-value 0) (/ x y))` when `div == 0.0`, pinning `-0.0 // 2.0 -> -0.0`) AND applies quotient rounding correction `(if (< (data/float-value 0.5) (- div f)) (+ f (data/float-value 1)) f)` (pinning `2.1 // 0.7 -> 3.0`).
   - Added explicit Float × Float and Mixed Int × Float rules for `+`, `-`, `*`, `/` (`yrt.float/zero?` divisor check raising guest `:zero-division`, else `yrt.float/fdiv`), and unary `-` / `+` (`(* (data/float-value -1) x)`).
2. **HIGH (`§7.2`, `§7.5` — `integer/*` limit checks before `compare-float` / `data/nth` / `integer/neg`):**
   - `§7.2` item 2 mandates EVERY `integer/*` operation returning through `yin.vm.integer`'s `out` / `pair` contract (`add`, `sub`, `neg`, `mul`, `floor-div-mod`, `quot-rem`, `pow`, `to-float`, `true-div`, `from-float`, `format` — including `integer/neg` on externally supplied exact operands) passes through `yrt.int/check-limit` BEFORE indexing via `data/nth` or passing results to subsequent primitives.
   - `clj.py/float-pow` (`float-exp-info`) unwraps `y = (data/float-value y-boxed)` in a pure leaf helper, binds `e = (integer/from-float y)`, checks `(integer/integer? e)` **before** `(integer/compare-float e y)`, and returns any `:yin.vm.integer/bit-limit` out of the leaf frame for translation via `yrt.int/check-limit`.
   - Mixed ordering (`<`, `<=`, `>`, `>=`) unwraps boxed floats via `data/float-value` inside a pure leaf helper, excludes NaN, and passes only native host doubles to `integer/compare-float`.
   - Standalone `clojure.core/rem` binds `qr = (yrt.int/check-limit (integer/quot-rem a b) ...)` BEFORE `(data/nth qr 1)`.
3. **HIGH (`§7.2`, `§7.3`, `§7.5` — Uniform portable map/set key & element domain):**
   - Defined one uniform `v1` recursive key/element admission gate (`admit-key!`) shared across BOTH `clj.py` (`map-from`, `set-from`, `get-item`, `comp-set-add`, `comp-dict-set`) AND standalone `clojure.core` (`assoc`, `assoc-in`, `update`, `update-in`, `get`, `get-in`, `contains?`, `conj` on maps/sets, `disj`, `merge`, `into`, `union`, `difference`, `hash-map`, `set`).
   - Refuses any `data/float64?` or `seq?` (or host closure/continuation/`cell-ref`) at any key/element nesting depth (`-0.0`, `1.0`, `NaN`, `[[-0.0]]`), while keeping arbitrary floats, lists, and callables supported as map **values** and vector/list elements.
   - `clj.py/eq` compares maps via equal count + admitted key lookup + recursive `eq` on values (`{"a": -0.0} == {"a": 0.0}` -> `true`, `{"a": NaN} == {"a": NaN}` -> `false` on all 4 hosts).
4. **HIGH (`§7.4`, `§7.5` — Prelude `if` nesting instead of `and`; `fn?` aligned 1-to-1):**
   - Rewrote `clj.bridge/admitted-callable?` and standalone `clojure.core/fn?` using nested `(if ...)` (zero `and` special form per `src/cljc/yang/core/uast.cljc:82`).
   - Removed `(data/callable? x)` from `clojure.core/fn?` so `fn?` and the dynamic higher-order caller share the exact same authenticated cell admission predicate.
5. **HIGH (`§7.4` — Full 4-step invocation-record validation mirroring `src/cljc/yang/python/antlr/prelude.cljc:906–943`):**
   - Step A: Establish `(data/map? invocation)` and `(data/callable? (get invocation :yang.call/raise))` first (failing operationally if unusable).
   - Step B: Outside `try-value`, check `(yang.call/valid-invocation? invocation)` AND owner identity `(= (get invocation :yang.call/callee) owner)`, transferring `:yang.call/invalid-invocation`.
   - Step C: Outside `try-value`, reject `:yang.call/binding-profile` with `:yang.call/unsupported-binding`.
   - Step D: Inside `yrt.escape/try-value` on `(clj/rt-ctx)`, run parameter binding and body execution.
6. **HIGH (`§6.3`, `§7.3`, `§7.5` — Cell-free linker exports, idempotent `init!`/`export`, 0-arg accessors, `raw-*` internal adapters):**
   - `clojure.core` publishes zero `:cell-ref` values in `:slice` (`src/cljc/yin/vm/engine.cljc:915, 1055`, `src/cljc/yin/vm/module.cljc:40`): internal `clojure.core.rt/{state,exports}`, idempotent `(clojure.core/init!)`, `(clojure.core/export sym)`, pure 0-arg cell-accessor exports for every public name, and separately named `clojure.core/raw-seq`, `raw-first`, `raw-rest` (reserved/banned from source `:ambient-projections`) for trusted Layer-2b hooks like `clj.py/for-each`.
7. **HIGH (`§2.2`, `§3`, `§6.3`, `§7.3`, `§7.4`, `§8.2` — Trusted `clj.bridge` ownership, bundled parity, and shared `(clj/rt-ctx)`):**
   - Consolidated Clojure-family §8.10.7 provenance doors (`cell/dsg__new-provenance`, `cell/dsg__provenance`) into stateless trusted Layer-2b module `clj.bridge` in **both** bundled (`uast`) and linked (`module-uast`) emitters (leaving `clj` golden-neutral in P3 and keeping provenance doors out of `yrt.*`, `clj.py`, and Layer-2c `clojure.core`).
   - Recorded in §2.2 invariant 1 that `clj.bridge`, `clj.py`, and `clojure.core` run inside the Clojure semantics/exception domain and intentionally share `(clj/rt-ctx)`, and gated nested `clj → clj.py → clj` unwinds in P3.
8. **HIGH (`§7.2`, `§7.5` — Audited retained `clojure.core` exports for 4-host determinism):**
   - Added pure P3 host prerequisite `kind/namespace`.
   - Rewrote `fresh-symbol` and `gensym` onto `integer/integer?` + `integer/add` + `integer/format` + `yrt.int/check-limit` + `data/str-concat`.
   - Specified explicit 4-host deterministic `str` rendering (`data/float64?`, `1.0 -> "1.0"`, `-0.0 -> "-0.0"`, `NaN`, `±Inf`, exact ints, keywords/symbols via `kind/namespace`/`kind/name`, canonical maps/sets).
   - Specified `canonical-compare` deterministic ordering across `raw-seq`/`seq`/`first`/`rest`/`vec`/`keys`/`vals`/`str`/`reduce`/`map`/`filter`/`remove`/`into`/`merge`/`union`/`difference`, exact-int `nth` on sequential collections only, `map?`/`reduced?` exclusion of authenticated cell-refs, and excluded `invoke-value` alongside `var?`/`resolve`.
9. **HIGH (`§6.2`, `§6.4` — All executable descriptor fields validated against selected closure):**
   - Updated `validate-descriptor` to validate `:hooks` (including optional `:comp/finish`), executable `:literals.*` fields (`:literals.*.overflow-op`), AND `:ambient-projections` against the descriptor's **own selected, address-verified closure** (`:ambient-modules` + transitive `:requires`), enforcing qualification, frontend-authorized role/arity, and the reserved-door ban (including `raw-*`).
10. **MEDIUM (`§5.2` — `admit-profile` `host-limits` & direct vs. transitive `:requires`):**
    - Updated `admit-profile` signature to `(profile artifact-store registry host-limits)` (referencing `src/cljc/yin/vm/module.cljc:249` and `src/cljc/yang/python/antlr/prelude.cljc:4163`).
    - Separated checking root `:requires` == `:yang.prelude/components` from recursively verifying the full transitive `:requires` closure across `artifact-store`.
11. **MEDIUM (`§4.3`, `§7.2`, `§9` — Corrected P1 underflow witness & `data/float64?` phase alignment):**
    - Pinned `0.5 ** -2000` for `:yrt.float/underflow` + outer overflow policy, `2.0 ** -1074` for exact subnormal `5e-324`, and `2.0 ** -2000` for normal boxed `+0.0`.
    - Removed `data/float64?` from `yrt.float`'s P1 dependencies and placed both `data/float64?` and `kind/namespace` cleanly in P3.
12. **MEDIUM (`§6.3`, `§6.4`, `§7.3`, `§9` — Valid Python bare `except:` grammar & comprehension accumulators):**
    - Replaced `except as e:` with bare `except:` (noting `antlr/python3/Python3Parser.g4:313` grammar rule) and kept typed `except ExcType [as e]:` under `:control/except-typed`.
    - Specified comprehension accumulator hooks (`:comp/list-new`, `:comp/list-append`, `:comp/set-new`, `:comp/set-add`, `:comp/dict-new`, `:comp/dict-set`, optional `:comp/finish`), cell-backed mutable accumulators in `clj.py`, and `nil` `:comp/finish` in `:yang.python/py-v1` to keep default Python UAST byte-identical.
13. **LOW (`§5.1`, `§10 D7` — Complete Python 6-effect example & consistent D7 `-raw` names):**
    - Fixed §5.1 `:yang.prelude/max-effects` comment to list the verified Python 6-effect closure (`:cell/new`, `:cell/get`, `:cell/set!`, `:cell/authentic?`, `:cell/new-provenance`, `:cell/provenance`).
    - Aligned §10 D7 option text to `yrt.float/fpow-half-raw` and `yrt.float/fpow-base-raw`.

## Review Output Format

1. Run `date -u` and `date` in your read-only shell so your output begins with accurate `Completed-GMT:` and `Completed-Local:` lines.
2. **Section 1 — Verdict:** State either **`READY`** or **`REVISE`** concisely, with a brief executive assessment and explicit read-only verification scope statement (no files edited).
3. **Section 2 — r3 Finding-by-Finding Disposition:** A markdown table covering all 13 r3 findings (#1–#13), each marked `CONFIRMED-FIXED` or `OPEN` with line/code evidence, plus confirmation that previously closed r1/r2 findings (`#2`, `#5`, `#8`, `#9`, `#10` and `F2`, `F5`, `F6`, `F7`, `F8`) remain intact without regression.
4. **Section 3 — New or Remaining Findings (if any):** List any remaining issues with **Severity** (`CRITICAL` / `HIGH` / `MEDIUM` / `LOW`), **`file:line`**, **Invariant / Evidence**, and **Recommended Correction**.
5. **Section 4 — Owner-Decision Confirmation (D1–D7):** Briefly confirm D1–D7 remain accurate and cleanly framed for owner disposition.
