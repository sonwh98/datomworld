Created-GMT: 2026-09-22 00:55:00 GMT
Created-Local: 2026-09-22 07:55:00 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0c501-5311-71f0-94e5-033950e0473d (resumed: your J0 and J1 sign-off turns)
# Task: jing-cbor-j2-j3-signoff: ratify the Dart codec, the E6 errata column, and the conformance gate
Role: Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-22 07:55:00 +07 | Status: active | Rationale: batching J2 and J3 sign-off in one turn (weekly budget about 19 percent, resets 2026-09-23 20:50 +07)

Work in /Users/sto/workspace/worktree-jing-cbor (launch directory; branch jing-cbor;
J0 f5f71e95 and J1 7968884b/4f928dbd are committed and signed; J2 and J3 are
UNCOMMITTED in the working tree). READ-ONLY: edit nothing. BE ECONOMICAL.

## Read

- collab/1790028200000-ref-implementer-j2-report.md (J2 report)
- collab/1790028600000-ref-j2-review-glm.findings.md (READY) and
  collab/1790028600000-ref-j2-review-deepseek.findings.md (READY WITH CHANGES; its one
  P2 was the missing E6 column, now added)
- collab/1790029400000-ref-implementer-j3-report.md (J3 report)
- collab/1790029900000-ref-j3-review-qwen.findings.md (READY WITH CHANGES)
- collab/1790030200000-ref-implementer-j3-fix-report.md (the J3 hardening round)
- test/resources/dao/jing/cbor-v1.errata.md, section E6 (PENDING your ratification;
  includes the Dart column and the `coll/set-vector-list-collapse` addendum)
- All lanes are green after every round (kondo/cljstyle clean; JVM Java 17 full suite;
  CLJS; the full CLJD lane, now 1580 Dart tests). Canonical and refusal manifest
  digests agree across JVM, Node and Dart in every run.

## Rule

1. **E6, the Dart column of the errata E1 table:** ratify or amend. It states: on
   Dart, signed-zero and vector-list collapses are N/A (Dart's set constructor merges
   the members); the other seven N/A-table rows are live and refused with the frozen
   class; `host-collapse` is never raised on Dart (identifier equality is field-based);
   `BigInt.compareTo` is normalized to -1/0/1; the reader restores a leading BOM the
   Dart UTF-8 decoder drops.
2. **`coll/set-vector-list-collapse` runs on NO host** (JVM, Node and Dart all merge
   the list and the vector before the codec sees the value; only its decode twin
   `mal/collapse-vector-list-set`, refused `equality-collapse`, exercises the class).
   Both the J2 and J3 reviewers recommend accepting this as the single documented
   exception to "every case runs on at least one host", now recorded as a PENDING
   addendum at the end of E6. Ratify or require a different construction.
3. **The conformance gate's cross-host method:** one digest over 208 canonical cases
   and one over 154 refusal cases, each computed identically on all three hosts from
   the same documented-skip-derived case set, asserted equal to a value independently
   derived from the resource AND to a pinned literal; plus an independent raw
   CBOR-tree walker (shares no code with the codec) that now also enforces the E3
   decimal window and the E4 depth limit; plus a resource-immutability pin
   (c8f5ef38124110bca48d0c7e5596eef00028ecc66c7d032e4e12bb1477f97351, cross-checked by
   the orchestrator with `sha256sum`) and a hardened write-scan (Clojure/Dart/JS/Python/
   shell/bb.edn markers) with a JVM after-hook re-assertion. Sound as the three-host
   conformance gate the work package specified? Anything missing before you would call
   the steps-1-2 unit (J0-J3) COMPLETE?
4. **Boundary check:** does anything in J2 or J3 touch dao.jing, its backends,
   dao.space, transport, deps.edn, bb.edn, or pubspec? (It should not.) Confirm the
   epic's additive boundary is intact and that reaching J0-J3 complete does NOT itself
   authorize step 3 (consumer wiring) or either owner gate (dao.space comparators,
   rebuild readiness) — those remain owner decisions per your original work package.
5. **Design doc debt:** your J1 sign-off said the decimal window, max-depth and the
   host-collapse rule belong in docs/design/dao.jing.cbor.md's Encoding contract before
   step-3 integration. Restate that requirement (or say it is satisfied) now that E6
   exists; it is not yet applied to the design doc.

## Deliver

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0c501-5311-71f0-94e5-033950e0473d
then: sign-off lines for J2 and J3 separately (SIGNED OFF or NOT, with required
changes); the E6 ratification wording (including the addendum); confirmation of items
3-5; whether J0-J3 (steps 1-2 of the epic) can be declared COMPLETE, and exactly what
that does and does not authorize. Nothing needs owner action unless you say so.
