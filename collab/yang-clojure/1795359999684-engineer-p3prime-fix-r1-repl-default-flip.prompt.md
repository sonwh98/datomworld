Created-GMT: 2026-11-22 11:06:39 GMT
Created-Local: 2026-11-22 18:06:39 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: pending (provider-generated; fresh session — resume-by-state)

# Task: yang.preludes P3′ fix r1 — yin.repl.python-antlr-test 32 failures after the DEFAULT flip

Role: Engineer

Implementers:
- Model: gpt-6.2-sol | Assigned: 2026-11-22 18:06 +07 | Status: active | Rationale: same model/lineage; fresh session (0.170 resume mounts read-only)

## Context (resume-by-state)

Your predecessor's P3′ implementation sits COMPLETE and review-APPROVED in `/Users/sto/workspace/dw-p3prime` (branch `feat/p3prime-descriptor-cljpy`, committed locally as `fd148fd9`, base master `b1eb3e40`). The jupiter wide gate then caught a regression this slice introduced. Your work on disk is intact; resume by state from the tree.

## The regression (orchestrator-verified, master-vs-P3′)

- master `b1eb3e40`: `clj -M:test -n yin.repl.python-antlr-test` = 22 tests / 201 assertions / **0 failures**.
- P3′ `fd148fd9`: same command = 22 tests / **32 failures**, including:
  - `installed-parser-evaluates-the-filtered-comprehension-test` (python_antlr_test.cljc:540): expected `["" "[4, 16, 36, 64, 100]"]`, actual `["Error: Cannot apply non-function" "Error: Cannot apply non-function"]`.
  - `the-loop-prints-the-last-expression-value-test` (python_antlr_test.cljc:194): expected `["'hi'"]`, actual `["\"hi\""]` — the REPL now renders Clojure-style strings.

## Diagnosis direction (verify, don't assume)

The P3′ DEFAULT flip (§6.5) makes the default `:yang/python` revision `clojure-core-v1`. These fixtures create REPL sessions with the DEFAULT selection and implicitly relied on the default being native Python: their sessions now run Clojure semantics and render via the Clojure display path. Two distinct things may be mixed in the 32:

1. **Fixture-precondition change (expected under the flip):** fixtures whose PURPOSE is the native parser/lowering/display should pin `[:yang/python "py-v1"]` explicitly per §6.5's explicit-pin semantics — that is the design-intended adaptation, not masking.
2. **Possible genuine breakage under the new default:** the comprehension failure (`Cannot apply non-function` instead of `[4, 16, 36, 64, 100]`) may be display-shape OR may be a real defect in the clojure-core-v1 REPL path (e.g. the session's `lower-session`/primitives binding, the `:render` hook choosing the wrong path for the new revision, or comprehension lowering producing something the VM can't apply). Distinguish carefully: run the same program through the descriptor-profile path and the legacy pin, and inspect the actual lowering/render pipeline for the default revision.

## Task

1. For each of the 32 failures, classify: fixture-precondition (native-behavior fixture → add the explicit `py-v1` pin) vs genuine defect (fix the implementation in `dw-p3prime`).
2. Native-behavior fixtures: add the explicit pin; do NOT rewrite their assertions — they remain the native track's guards.
3. If any failure is a genuine clojure-core-v1 defect: fix it in the implementation, and add/extend a descriptor-profile fixture covering it (the fixtures in `test/yang/python/antlr/descriptor_profile_test.cljc` are the pattern).
4. Whatever you do, the §9 P3′ invariants stand: default request → clojure-core-v1 behavior; explicit `py-v1` pin → native behavior; in-flight sessions keep their snapshot.
5. Verify (all must pass):
   - `clj -M:test -n yin.repl.python-antlr-test` → 0 failures (with the pin adaptations)
   - `clj -M:test -n yang.python.antlr.descriptor-profile-test -n yin.repl.frontend-catalog-test -n yin.repl.query-test` → stay 0 failures
   - `clj -M:test -n yang.clojure-test -n yang.clojure-callsite-test -n yang.rt.effect-test` → stay 160/1677/0
   - `clj -M:test -n yang.python.antlr.prelude-parity-test` → stays green (legacy corpus untouched)
   - `clj ./bin/analyze-deps.clj` clean
6. Do NOT commit. `git add -N` any new files.

## Deliverable

Final report: the 32-failure classification (fixture-pin vs defect, one line each or grouped), any implementation fix with file:line, verification matrix with counts, and confirmation that both revisions' behavior still matches §6.5.
