Created-GMT: 2026-11-21 10:32:39 GMT
Created-Local: 2026-11-21 17:32:39 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: agy
Session-ID: not-applicable (agy assigns its own conversation id; capture it)

# Task: Step-7 review — yang.preludes P1′ (yrt.effect + yrt.int + unified clj)

Role: Reviewer (independent, read-only)

Implementers:
- Model: gemini-4-argon-high | Assigned: 2026-11-21 17:32 +07 | Status: active | Rationale: independent family from the codex/gpt-6.2-sol implementer; argon for careful design review

## What to review

The UNCOMMITTED working-tree diff in `/Users/sto/workspace/dw-p1prime` (branch `feat/p1prime-yrt-effect`, base master `f23728d3`). Review the real diff (`git diff` plus the four `git add -N` new files via `git diff -- <file>`), not any summary. Do NOT modify anything — you have no write authority.

- Implementer report: `/Users/sto/workspace/dw-p1prime/collab/yang-clojure/1795254600033-engineer-p1prime-yrt-effect-int-unified-clj.gpt-6.2-sol.stdout.log` (final agent message; treat as claims to verify).
- Governing design: `/Users/sto/workspace/dw-p1prime/docs/design/yang.preludes.md` r8 — review against §4.3 (`yrt.effect`), §4.4 (`yrt.int`), §7.4 (unified trusted `clj`), §8.1 (portability/canonical ordering), and the §9 "P1′" acceptance-gate row.
- The implementer's engineer brief: `/Users/sto/workspace/datomworld/collab/yang-clojure/1795254600033-engineer-p1prime-yrt-effect-int-unified-clj.prompt.md` (scope fences: py/js/php untouched; pre-existing `linked-prelude-test` red excluded).

## Already verified by the orchestrator (do not re-run suites)

JVM `clj -M:test -n yang.rt.effect-test -n yang.clojure-callsite-test` = 54 tests / 674 assertions, 0 failures; `clj ./bin/analyze-deps.clj` clean (no SCCs, no self-loops). The implementer separately reports Node (49/585), Dart (8 tests), Jolt (8 tests/69 assertions) parity lanes green, py/js/php diffs byte-identical. Spot-check code, not suites.

## Review focus (prioritized)

1. **§4.3 unwind semantics — one rule per frame kind.** Verify `raise` never delivers to an `:effect` frame (pop-restore-continue), `:finally` cleanup runs exactly once per activation (including a guest throw crossing an unrelated effect frame), `perform` walks without running `:finally` frames, and only abort-without-resume runs the traversed cleanups. The implementer's `perform` handler-runs-outside-delimiter design (temporary cleanup frame) is a resolved ambiguity — verify it actually implements "only when the handler aborts without resuming" and cannot double-run cleanup.
2. **Continuation-portability envelope change in `test/yang/latte_oracle_test.clj`.** The old fixture asserted a live registry with a captured guest closure LIFTS to a wire representation ("serialized context contains code references, not host closures"); the new fixture asserts `:yin.k/non-portable`/`:cell`. This aligns with the r8 envelope ("%capture continuations are task-local, non-portable"), but confirm the new assertion tests the SAME property the old one protected (task-local resumability: the test still asserts `[:yin.k/ok 7]` on resume) and that no still-needed coverage was deleted (`assert-machine-data-within-budget!` retained, `lift-task` refusal now asserted). Flag any net coverage loss.
3. **§7.4 door enforcement.** Private provenance doors present in BOTH linked and bundled emitters, never exported, absent from `yrt.*`/`clojure.core` surfaces; bundled-mode enforcement by calling-definition address (a forged co-bundled call cannot mint provenance); the four-step entry boundary (Steps A–D) matches §7.4 exactly, including evaluated-assoc condition evidence and Step D inside `try-value`.
4. **`data/nth` discipline.** Every 5-slot frame-vector read uses `data/nth`; `get` only for context-map fields. Check for any bare vector `get`/`nth` regressions in the new code paths.
5. **Scope fences.** py/js/php preludes untouched; `dsg__call_token` fully retired (no dead residue); VM changes (`yin/vm/engine.cljc`, `provenance_scope.cljc` new file, `ast_walker.cljc`, `debruijn/{stack,register}.cljc`, `semantic.cljc`) are the minimum needed for §7.4 bundled-mode door enforcement — flag anything beyond that need.
6. **§8.1 portability.** No JVM-only reflection in the new namespaces; canonical ordering intact for CBOR byte-identity; zero raw host doubles.

## Deliverable

Write your findings to `/Users/sto/workspace/dw-p1prime/collab/yang-clojure/1795257159499-reviewer-p1prime-yrt-effect-int-unified-clj.gemini-4-argon-high.findings.md` with the artifact header (Created-GMT/Created-Local/Coding-Agent/Session-ID/Task/Role/Implementers). Verdict per finding: CRITICAL / HIGH / MEDIUM / LOW / NOTE with file:line evidence, then ONE overall verdict line: `VERDICT: APPROVED` or `VERDICT: CHANGES-REQUIRED` (or `VERDICT: APPROVED-WITH-FIXES` for LOW-only items). End your agent message with the same verdict.
