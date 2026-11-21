Created-GMT: 2026-11-20 21:15:59 GMT (dispatch)
Created-Local: 2026-11-21 04:15:59 +07 (Asia/Ho_Chi_Minh)

# Task: yang-preludes-p0prime-step7-review

Role: Reviewer (step-7, your thread continues)

Slice **P0′** of the signed-off `yang.preludes.md` r8 design is implemented: the `:pure`
host core extensions + composition plumbing, per the plan the conformance review amended.
The 17-file patch (950 lines incl. 4 NEW files) is piped after this prompt.

## What to verify

1. **`yin.vm.data` additions**: `float64?`, `equiv` (IEEE-aware, kind-strict:
   `(keyword nil "a/b") != (keyword "a" "b")` on CLJS; exact-int×float64 false;
   +0.0==-0.0 true; NaN≠NaN), `admit-key?` (absent namespace ADMITTED; two-part keyword
   rules; float refusal at depth), `canonical-compare`/`canonical-entries` (Jing
   encoded-byte normative order, `0` before `nil`), and `data/normalize-key` + NEW
   `src/cljc/yin/vm/key.cljc` wired into map/set insertion/lookup/membership/removal
   (cross-carrier integer identity).
2. **NEW `yin.vm.float`** (158L): boxed `data/float64` in/out, the §4.1.2 export list,
   `fpow-base` DEFERRED, the P0′ float gates as tests (2.1 // 0.7 == 3.0, -0.0 // 2.0 ==
   -0.0, 0.5 ** -2000 → :underflow, format 1.0 -> "1.0"). 4-host structure per the
   integer v4 pattern — note: the CLJD/Jolt host implementations are WRITTEN but their
   lane gates are orchestrator-routed follow-up (declared scope).
3. **`integer/to-float-boxed`/`true-div-boxed`/`from-float-boxed`**: boxed-only results;
   the legacy `to-float`/`true-div` ABI untouched (frozen native track).
4. **`yang.core.defquoted`** (NEW host macro ns): consolidated from py/ts preludes +
   php `quoted.cljc`; the analyzer's 3 self-loops are GONE.
5. **`prelude-admission` 3→5 publics**: `validate-profile`/`admit-profile` per §5.2's six
   fail-closed checks; the ambient-projection alias-collision check DEFERRED to
   `validate-descriptor` at P3′ (layering: no descriptor exists at profile-validation
   time) with an in-code comment. `yang.core.prelude` untouched at 11 publics.
6. **Migration recipe records** (`prelude_recipes.cljc`): current per-prelude modes
   recorded, NO shape change — landed py/js/php addresses frozen.
7. **Scope discipline**: no UAST node type, no VM opcode, no manifest schema change, no
   closed registry; `core.prelude` pure; admission only in `prelude-admission`.

## Implementer's evidence

- Baseline frozen net before edits: 178/1,636/0 — unchanged after every turn.
- Final gates: JVM admission+data/integer/float 62/1,039; Node 62/1,041; frozen net
  178/1,636; my (orchestrator) independent run 138/1,384/0; analyzer self-loops 3→0;
  diff --check clean. CLJD/Jolt lanes: implementations written, gates pending (declared).

## Report format

Begin exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

End with exactly one line:
APPROVED | CHANGES STILL REQUIRED — <why> | PROOF FAILS — <why>
