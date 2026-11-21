Created-GMT: 2026-11-20 20:57:43 GMT
Created-Local: 2026-11-21 03:57:43 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: yang-preludes-P0prime-implementation
Role: Engineer

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-21 03:57:43 +0700 | Status: active | Rationale: continuity with the landed DRY rounds; the P0′ slice is the design's own extension of the seams this family implemented and had reviewed

Implement slice **P0′** of the signed-off design `docs/design/yang.preludes.md` (r8, architect
SIGN-OFF + VM-engineer SOUND — read it first, especially §4.1, §5.1–5.2, §9 P0′, §8.1–8.2).
You may edit files and run tests in this worktree (detached at current origin/master).
**Do not commit, do not push, do not touch collab/.** Scratch scripts in /tmp. Match
surrounding style.

## Deliverables (per the signed-off design; the r7/r8 reviewer conditions are binding)

### 1. `:pure` host core extensions
- **`yin.vm.data` additions** (`src/cljc/yin/vm/data.cljc`): `data/float64?` (delegating to
  the existing `dao.jing.cbor/float64?`), `data/equiv` (portable IEEE-aware kind-strict
  equality per §4.1.1: exact-int×exact-int via `integer/compare`; float64×float64 IEEE with
  NaN≠NaN and +0.0==-0.0; exact-int×float64 FALSE; kind-strict keywords/symbols so
  `(keyword nil "a/b") != (keyword "a" "b")` on CLJS too; recursive vectors/maps-ignoring-
  cell-refs/sets; other scalars host `=`), `data/admit-key?` (recursive; **absent namespace
  ADMITTED — the namespace-non-empty rule applies to two-part keywords only**; name free of
  "/" and not starting with ":"; refuses float64 at any depth, ambiguous identifiers,
  seqs/lists, closures, `:cell-ref`), `data/canonical-compare` and `data/canonical-entries`
  (**normative order: Jing's unsigned encoded-byte order per `dao.jing.cbor.md:173` — `0`
  before `nil` — as the SINGLE ordering rule, including compound keys compared by encoded
  bytes; the r7 recursive-lexicographic clause is withdrawn**).
- **NEW `yin.vm.float`** (`src/cljc/yin/vm/float.cljc`, module name `'float`, all `:pure`,
  effects `#{}`, ALL 4 HOSTS — JVM/CLJS/CLJD/Jolt per the `yin.vm.integer` v4 pattern):
  boxed `data/float64` in/out, exports per §4.1.2: constants/predicates (`inf`, `neg-inf`,
  `nan`, `zero`, `neg-zero`, `nan?`, `finite?`, `zero?`, `neg-zero?`, `zero-like`), IEEE
  arithmetic (`add`, `sub`, `mul`, `div`/`div-checked` → `:yin.vm.float/div-zero`, `neg`
  preserving `-0.0`, `abs`, `compare` → `-1/0/1` or `:nan`), `floor`, `floor-int` (exact for
  |x| < 2^53 else `:out-of-range`), `fmod-pos` (Sterbenz), `floordiv-mod` (`[q r]` or
  `:div-zero`, `-0.0` quotient sign, `2.1 // 0.7 == 3.0` correction), `fpow-half` (for
  py/js/clj.py), `neg-pow` (`:underflow`), `parts`, `format` (deterministic 4-host via
  `dao.host.float/float-digits`; `NaN`/`Infinity`/`-Infinity`/`0.0`/`-0.0`/`1.0 -> "1.0"`).
  **`fpow-base` is DEFERRED to Track B** (D7 r7 note) — php keeps its existing mechanics.
  **Host-boundary conversion rule:** add `integer/to-float-boxed` and
  `integer/true-div-boxed` (+ `from-float-boxed`) to `yin.vm.integer` — boxed results only;
  the legacy `to-float`/`true-div` stay for the frozen native track.
- **`kind/namespace [x]`** added to the kind module (locate it — design §4.1.3 says
  `yang.clojure/kind-module`).

### 2. Composition plumbing
- **`yang.core.defquoted`** (NEW host macro namespace): consolidate the `defquoted`
  ClojureDart AOT macro from `yang.python.antlr.prelude`, `yang.typescript.antlr.prelude`,
  and `yang.php.antlr.quoted` — the three namespaces require the new one; their self-loop
  analyzer warnings should disappear. `yang.core.prelude` stays at 11 publics (the macro
  does NOT go there — reviewer condition).
- **`:yang.prelude/recipe` / `:yang.prelude/profile`** schemas (§5.1 exactly) +
  **`validate-profile`** (verified `:yin.module/tree` closure walk, finiteness/acyclicity,
  duplicate-binding refusal, direct provenance-door ban on non-trusted modules vs.
  transitive footprint ceiling via `:yang.prelude/max-effects`) and **`admit-profile`**
  (host-export admission via the existing `prelude-admission/admit`, `host-limits` floors
  `:min-bits`/`:min-digits`/`:min-max-items` failing closed) — **implemented in
  `yang.core.prelude-admission` (3→5 publics, exactly at bound; `yang.core.prelude` stays
  pure at 11 — reviewer condition)**.
- **Base-prelude emitter recipes as migration truth:** a recipe record per existing base
  prelude recording its CURRENT mode (`:linear` for py, `:balanced` for php/js) and C1/
  trailing-requires facts AS LANDED — recorded, not changed. No existing prelude's UAST
  shape changes; existing content addresses are FROZEN (zero golden churn for py/js/php).

## Mandatory validation plan

1. **Baseline first:** the existing py/php oracle + parity suites must pass UNCHANGED before
   your edits (they are the frozen-track regression net). Run: the php + python parity/
   prelude namespaces you used in the batch-1 round, plus `yang.clojure-test`.
2. **New-primitive suites (4-host where the lane allows):** `data/equiv` (`[0.0] == [-0.0]`,
   `[NaN] != [NaN]`, `(keyword nil "a/b") != (keyword "a" "b")`), `data/admit-key?`
   (unqualified `:k` admits; two-part keyword rules; float refusal at depth),
   `canonical-compare`/`canonical-entries` (Jing encoded-byte order: `0` before `nil`),
   `yin.vm.float` (`2.1 // 0.7 == 3.0`, `-0.0 // 2.0 == -0.0`, `0.5 ** -2000 → :underflow`,
   `format` `1.0 -> "1.0"`), `validate-profile`/`admit-profile` (closure, missing-manifest,
   host-limits, direct-provenance vs. transitive-footprint, duplicate bindings). JVM+Node on
   this host; **CLJD and Jolt implementations must be WRITTEN (the cljc + host files), with
   their lane gates noted as follow-up** if the lanes are heavy — the orchestrator routes
   them.
3. **Regression net after edits:** the same focused namespaces as baseline + `analyze-deps`
   (the defquoted self-loops should be GONE; report the count).
4. `git diff --check` clean.

## Constraints

- No UAST node type, no VM opcode, no manifest schema change, no closed language registry.
- `yang.core.prelude` stays pure emission (11 publics); admission grows only in
  `prelude-admission` (3→5).
- Zero shape/address change to the landed py/js/php preludes and their goldens.
- The frozen native ABI (`integer/to-float`, `true-div`) is unchanged.

## Report format (mandatory)

Begin exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then: deliverable-by-deliverable summary (files, exports, LOC), the baseline-vs-after gate
table with counts, the analyzer self-loop count before/after, noticed-but-not-done. End with:
VERDICT: DONE — <one line> | or | BLOCKED — <why>
