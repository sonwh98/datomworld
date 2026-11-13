Created-GMT: 2026-11-14 00:1x GMT
Created-Local: 2026-11-14 07:1x +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: qwen (token-plan gateway)
Model: deepseek-v4-pro
Session-ID: 4f032a59-6b1e-46f0-9d2a-72f3c41d8e0b
Role: Architect, read-only step-7 review
Note: plan-mode session could not write files; this is the verbatim response promoted from
1794582736000-architect-p7b-review.deepseek-v4-pro.stdout.log (artifact-protocol promotion).

---

# Architect P7-b Review — Findings

**Verdict: MERGE-READY** with 2 stated risks (fixture-gap, cross-include coverage).

---

## Rule 1: Decision-Table Conformance — PASS

Reviewed against `collab/yang-php/1794567570000-architect-ticks-counter-ruling.gpt-6-astra.findings.md`
(the normative decision table) and the slice-3 design at
`collab/orch-lane/1794700000000-architect-safepoint-slice3-design.gpt-6.1-sol.findings.md`.

| Concern | Verdict | File:line evidence |
|---|---|---|
| One request-owned counter | PASS | `prelude.cljc:6149` — `(cell/new 0)` once per task; `:5848` — reset to 0 at `run-main` start |
| Increment; if ≥ N reset to 0 before dispatch | PASS | `prelude.cljc:4503-4506` — `integer/compare n ticks`, `< 0` → store; else reset to 0 then `php/tick-dispatch` |
| No reset at declare/function/include/generator boundaries | PASS | `php/tick-count` is never touched outside `php/tick-line` and `run-main` init |
| Empty registry still increments/resets | PASS | `prelude.cljc:4503-4506` — no registry guard; `:4491-4500` — `tick-dispatch` iterates empty vector to `:php/null` |
| Lexical N per mark | PASS | `lower.cljc:3602-3607` — `@(:ticks ctx)` carried in site operand `{:ticks N}` |
| N=0 skips site emission | PASS | `lower.cljc:3602` — `(pos? @(:ticks ctx))` guard on every tickable statement |
| Negative N refused | PASS | `lower.cljc:3573` — `(unsupported! n :ticks)` for negative values |
| Semicolon vs block directive | PASS | `lower.cljc:3575` — semicolon: `reset!` outer ctx ticks; block: scoped `:ticks` atom in `body-ctx` |
| Per-entry reentry suppression (not global busy flag) | PASS | `prelude.cljc:4493-4500` — index-2 executing flag per entry; `try-finally` clears after call |
| Unregister-executing raises Error | PASS | `prelude.cljc:4484-4486` — `php/throw-error "Error" "Registered tick function cannot be unregistered while it is being executed"` |
| Dynamic registration during dispatch | PASS | `prelude.cljc:4492` — reads `php.rt/tick-functions` from cell on each iteration |
| Generator after-hook preserved in continuation | PASS | `safepoint.cljc:133-138` — `after-hook` wraps value in let-binding; suspension freezes the let; resume completes it |
| Nonlocal exit skips after-hook | PASS | `safepoint.cljc:133-138` — `after-hook` is not a `finally`; escape bypasses the sequencing |
| Budget/profile separation | PASS | `safepoint.cljc:14-17` (`ticks-profile` `:line`) separate from `:11` (`profile` `:loop`/`:call`) |

---

## Rule 2: Shared-Core Additivity — PASS

Reviewed `src/cljc/yang/safepoint.cljc` against the slice-3 design §1 and §6.

| Concern | Verdict | File:line evidence |
|---|---|---|
| `:after` value preservation | PASS | `safepoint.cljc:133-138` — `after-hook` binds `yang.safepoint/value` to node result, sequences hook after, returns value |
| Statement-site-only validation for `:after` | PASS | `safepoint.cljc:211-213` — `insert` validates `(or (not (contains? statement-kinds kind)) (:sequence entry))` |
| Existing before-path untouched | PASS | `safepoint.cljc:148-158,161-164` — `sequence-hook`, `prefix-hook`, `wrap-hook` unchanged; `:after?` is a new branch in `rewrite` |
| Lambda `:line` with `:after` refused | PASS | `safepoint.cljc:211-213` — `:after` requires `statement-kinds` membership or `:sequence` |
| Result binder reserved | PASS | `safepoint.cljc:47-49` — `result-binder` = `'yang.safepoint/value` |
| Tail recomputation | PASS | `safepoint.cljc:218` — `tails/remark-tails` called after rewrite on full tree |
| Derivation deterministic across hosts | PASS | `safepoint.cljc:97-99` — `site-set` sorts by `pr-str`; insertion is purely structural |
| Python regression | NOTE | No changes to python path. `:after` is additive. Python `454A + linked 60A` green confirmation needed but no architectural interference observed. |

---

## Rule 3: Linker/Site-Set Chain — PASS

Reviewed `src/cljc/yang/php/antlr/siteset.cljc`, `src/cljc/yang/php/antlr/publication.cljc`,
`src/cljc/yang/core/publication.cljc`, `test/yang/php/antlr/linked_harness.cljc`.

| Concern | Verdict | File:line evidence |
|---|---|---|
| Site publication during `publish-units!` | PASS | `publication.cljc:42` — `:site-set siteset/publish!`; `core/publication.cljc:138` — calls per module |
| Site set extracted from lowered AST | PASS | `siteset.cljc:57-65` — projects envelope, extracts `safepoint/sites`, publishes set + record |
| Signed discovery of site records | PASS | `siteset.cljc:89-102` — `signed-entry`; `:104-128` — `discover` verifies signatures against declared public keys |
| Verified admission (fail-closed) | PASS | `siteset.cljc:235-253` — `stage-admit`: absent manifest → `:missing`; empty records → `:missing-site-set`; mismatched → `:site-set-mismatch` |
| Derivation from verified set | PASS | `siteset.cljc:134-150` — `derive-verified`: checks manifest/root match, row integrity, then `safepoint/insert` |
| Included module derivation (bottom-up) | PASS | `siteset.cljc:256-310` — `derive-closure`: sorts deps; reads published sites; calls `derive-verified` with `ticks-profile`; rebinds `php.sp` |
| `php.sp` module resolution | PASS | `safepoint.cljc:60-68` — `module-spec` creates `php.sp` requiring `php`; `linked_harness.cljc:47-49` — published at `:hook-address` |
| SCC bundle handling | PASS | `lower.cljc:3999-4054` — `scc-module-specs` bundles mutual includes; `core/publication.cljc:147-162` — bundle carries marks |
| Composition stage wiring | PASS | `linked_harness.cljc:71-88` — `source` creates `siteset/stage` with `{:hooks true, :hooks-profile safepoint/ticks-profile, :php-sp ...}` |
| UNINSTRUMENTED image cannot pass verification | PASS | `siteset.cljc:241` — empty records → refusal; `verify:82-87` — kind enumeration `#{:call :line :loop}` |
| Empty site set is legitimate | PASS | `siteset.cljc:76` — `(every? pred [])` is true; `derive-verified` produces identity derivation |

---

## Rule 4: Fixture Coverage — Conditional PASS

Reviewed `test/yang/php/antlr/programs_p7b.cljc` (22 fixtures), oracle folds at
`collab/yang-php/1794579968000-oracle-p7-folds.txt`, and tests at
`test/yang/php/antlr/ticks_test.clj`.

22 fixtures all recorded against `php:8.3.35` (all `:oracle :pending`). The oracle folds match
the fixture expectations exactly.

### Covered discriminators

| Category | Fixtures |
|---|---|
| Basic N=1 | `:sequence`, `:loop-body`, `:function-call`, `:generator-resume` |
| N>1 | `:sequence-n2` |
| Nested thresholds | `:nested-threshold`, `:nested-zero-completion`, `:nested-zero-n2` |
| Cross-function counter | `:cross-function` |
| Semicolon directive | `:semicolon-directive` |
| Hoisted threshold | `:hoisted-threshold` |
| Generator | `:generator-resume`, `:generator-foreach` |
| Loop constructs | `:loop-body`, `:do-while-selection`, `:for-selection`, `:foreach-selection` |
| Switch | `:switch-selection` |
| Adjacent suppression | `:adjacent-marked` |
| Reentry suppression | `:per-entry-reentry` |
| Dynamic registration | `:dispatch-add`, `:dispatch-remove` |
| Unregister error | `:unregister-executing` |
| Include handoff | `:include-handoff` |

### Gap risk 1 — Ruling-required discriminators absent (severity: P2)

The GPT-6-Astra counter ruling (lines 182-185) explicitly requires **at minimum** these
counter-ownership discriminators that are **not** in the fixture corpus:

| Missing fixture | Why it discriminates |
|---|---|
| `separate-declares` (N=2 blocks) | Proves no per-declare counter — two N=2 blocks with one fire across the boundary |
| `cross-include` | Proves counter shared across include — included unit's count continues caller's phase |
| `generator-shared` | Proves two generators share one counter — interleaved `current()`/`next()` across two instances |

### Gap risk 2 — Additional ruling appendix cases absent (severity: P3)

The ruling's reproducible probes appendix lists these additional cases not in the fixture corpus:
`empty-registry`, `exception-no-reset`, `once-skip`, `include-closure`, `registration-no-reset`,
`unticked-include`, `zero-gap`, `repeat-function`.

### Assessment

These gaps do not block merge of the implementation slice — the 22 existing fixtures validate
the core mechanism. They do block **full P7-b acceptance** per the ruling's own criteria
("The original five N=1 rows cannot replace these discriminators"). Forward work for the
QA/verification seat (slice-3 ordering, seat 6).

---

## Rule 5: Scope — PASS

Reviewed `src/cljc/yang/php/antlr/prelude.cljc` for interference with ceded exception-round regions.

### New tick regions in this slice

| Region | Lines | Content |
|---|---|---|
| Tick builtins | `prelude.cljc:4469-4508` | `php/bi-register-tick-function`, `php/bi-unregister-tick-function`, `php/tick-remove`, `php/tick-dispatch`, `php/tick-line`, `php.sp/line` |
| Run-main init | `prelude.cljc:5848-5849` | Reset `php.rt/tick-count` and `php.rt/tick-functions` at request start |
| Prelude slots | `prelude.cljc:6149-6150` | `php.rt/tick-count` and `php.rt/tick-functions` cell definitions |
| Slot whitelist | `prelude.cljc:6239` | Both slots in the `php/init!` export list |

### Exception-track regions (ceded, no interference)

The exception handler chain (`php/handle-uncaught`, `php/bi-set-exception-handler`,
`php/bi-restore-exception-handler` at `prelude.cljc:5807-5833`) is unchanged. Tick code
uses separate cells with no overlap in handler path.

### Other touched files — all additive

`lower.cljc:3554-3660`, `safepoint.cljc` (php), `publication.cljc`, `siteset.cljc` (new),
`builtins.cljc:51-52`, `programs_p7b.cljc` (new), `ticks_test.clj` (new),
`linked_harness.cljc` (additive), `e2e_harness.clj` (additive), `oracle.clj` (additive).

---

## Host-Specific Observations

| Concern | Observation |
|---|---|
| `Long/parseLong` vs `js/parseInt` | `lower.cljc:3571` — reader conditional. JS `parseInt` stops at non-digit. However, the ANTLR grammar already lexes `numericConstant` as digits only, so malformed input is excluded by the parser before lowering. Safe. |
| `integer/compare` vs host comparison | `prelude.cljc:4504` — uses `integer/compare` from the portable `integer` module. Correct for bigint carriers. |

---

## Summary

| Rule | Verdict |
|---|---|
| 1. Decision-table conformance | **PASS** |
| 2. Shared-core additivity | **PASS** |
| 3. Linker/site-set chain soundness | **PASS** |
| 4. Fixture coverage vs P7-b acceptance bar | **PASS** with 2 stated gap risks |
| 5. Scope (no interference with ceded surfaces) | **PASS** |

**Overall: MERGE-READY.**

The implementation faithfully executes the slice-3 design and the counter ruling.
The 2 fixture gaps (missing counter-ownership discriminators from the ruling appendix)
are forward work for the QA/verification seat and do not block merge of the current
implementation slice. No architectural defects found.
```
