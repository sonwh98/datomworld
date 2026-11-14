Created-GMT: 2026-11-12 23:08:10 GMT
Created-Local: 2026-11-13 06:08:10 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: interactive (ZCode / GLM seat — yang.typescript orchestrator)
Session-ID: pending (provider-generated; fresh thread — subject change from the exception-round arc)
# Task: ts callsite-provenance adoption — step-7 architecture review

Role: Lead System Architect

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-11-13 06:08 +07 | Status: active | Rationale: standing architect lane; GPT family independent of the Qwen author (team.md reviewer-independence rule)

Perform a read-only architecture review of the ts callsite-provenance adoption round.

## The change under review

Branch `callsite-ts-adoption` in worktree `/Users/sto/workspace/datomworld-callsite`, base
master `b134207f`, two commits: `86967ee7` (the adoption) + `603d2d4e` (first-execution repairs).
Full diff: `git -C /Users/sto/workspace/datomworld-callsite diff b134207f..callsite-ts-adoption`
— or read it here: `/Users/sto/workspace/datomworld/collab/yang-typescript/1794514249000-...`
exports and the findings cover the same ground. Files: `lower.cljc` (+64: annotation at every
production finalization point), `prelude.cljc` (+7) and `safepoint.cljc` (+9: published module
trees finalized), 3 golden-test files repaired, new `test/yang/typescript_callsite_test.cljc`
(727 lines, 11 deftests).

Read first:
- docs/design/datom.world.md (invariants — esp. unitarity/no-information-loss, derive-don't-persist)
- `/Users/sto/workspace/datomworld/collab/yang-clojure/1794670000000-architect-callsite-generalization-design.gpt-6-astra.findings.md` — THE DESIGN OF RECORD (astra).
- `/Users/sto/workspace/datomworld/test/yang/clojure_callsite_test.cljc` — the reference implementation the round mirrors.
- `/Users/sto/workspace/datomworld/src/cljc/yang/core/uast.cljc` + `core/call.cljc` — the generic mechanism (claimed read-only; verify no core edit appears in the diff).
- `/Users/sto/workspace/datomworld/collab/yang-typescript/1794514249000-engineer-ts-callsite-adoption.qwen3.8-max.findings.md` — engineer report INCLUDING its own disclosures (read section 0 first).

## Provenance of the work (disclosed, material to your posture)

The implementer lane (qwen3.8-max headless) has NO shell: the entire round was authored
UNEXECUTED, including paren-balance regex verification only for the new file. The orchestrator
executed everything: first run surfaced one compile error and one semantic self-conflict, both
fixed by the same session with a design citation (603d2d4e), and the FULL battery then passed:
CALLSITE 11/242, exception-regression 6/48, pins/manifest/module 18/90, lower suites 28/777,
source-call 2/22 — all 0F/0E (orchestrator runs, this session). Node/Dart legs and the
cross-host EQUALITY-GATE matrix are NOT yet run — they are your sign-off's downstream
(shiva/jupiter), per the standing rules. So your focus is static review + contract judgment,
not re-running the JVM suites.

## Specific asks (beyond the standard invariant sweep)

1. **THE SELF-JUDGED SEMANTIC FIX — verify the citation, not just the green.** The
   stage-envelope test first asserted NO occurrences marked under a `:bundled` admission origin;
   the emission marked 8+; the delegate resolved it by fixing its own TEST, citing design
   `:65`/`:139`/`:278` ("TypeScript packet/body/unit/multi-unit/**stage**" production rows;
   "the origin is the admission's, minted by the composition"). Read those design lines and
   rule: is the corrected expectation right, or did author-not-executed convenience pick the
   cheap side of a genuine conflict?
2. **Four judgement calls from the findings ("judgement calls needing your ruling")**:
   (a) `jssp` (safepoint) trees marked; (b) bundled fragments (`prelude/uast`, `hooks/uast`)
   not marked at their own def, finalized by the owning publication instead; (c) no
   `:injected-origin` supplied to the safepoint stage → derived-envelope C1 rows
   intentionally under-covered; (d) the validator borrowed read-only from `yang.clojure`
   rather than re-implemented (follow-up lift into `yang.core` suggested). Rule each: accept,
   require-now, or named-obligation.
3. **Blind-authored test quality**: the 11 deftests passed first-execution after one repair —
   passing proves nothing about assertion strength. Spot-check the equality-gate deftest and
   the export-audit closed-set pin specifically: would they catch a renamed wrapper / a shifted
   coordinate path / a missing finalize at one of the four seams? Cite line evidence; a
   weak pin that passes is a P2.
4. **Golden-repair honesty**: the six repaired assertions finalized the EXPECTED side rather
   than stripping the produced side — verify none of them silently weakened (e.g. replaced a
   precise coordinate with a superset match).
5. **Scope/invariant sweep**: core untouched (claim), no `dsg__` door reuse (claim), unitarity
   (markers outside hashed content), and anything else material.

Distinguish architectural defects from implementation gaps or intentionally deferred work.
Do not edit files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report: severity | file:line | invariant/evidence | recommended correction — plus
explicit per-answer on asks 1-5 and the final verdict: SIGN-OFF or FIX-LIST.
