Created-GMT: 2026-11-06 12:10:00 GMT
Coding-Agent: glm (glm-5.3, yang.clojure orchestrator dispatch)
# Task: implement the call-site occurrence contract C1 for PRODUCTION yang.clojure (Addendum A, adoption round 1 of 4)

Role: compiler engineer (glm). THE SPEC is the signed Addendum A (`collab/yang-clojure/1794630000000-architect-attribution-addendum-a-RECORD.md`) and the architect design doc (`collab/yang-clojure/1794670000000-architect-callsite-generalization-design.gpt-6-astra.findings.md`) — read BOTH first, especially design §2 (the occurrence contract C1, the `:yang/call-site 1` reserved key, the coverage/validation rules) and §2.2 (the minimal persisted annotation). A fresh codex gate reviews against the design before landing.

## Scope (adoption round 1: production yang.clojure ONLY)

Per Addendum A3: adoption covers PRODUCTION compilation paths — oracle instrumentation is not coverage. This round implements, for the yang.clojure production compiler:

1. The `:yang/call-site 1` reserved-key annotation emitted into the frontend-metadata side table for **every final `:application` occurrence** in the produced UAST — per design §2.1-2.2's identity rules (`[origin root-address path]`; the value is the version integer; the first three coordinates are the site id; NO callee identity, NO source arity, NO ambient classification in the key or value).
2. The complete-coverage property: a consumer can derive the eligible application set from canonical rows and compare it with the marker set — under a C1 publication claim the sets must be equal (design §2.2's validation rules: duplicate transport deliveries dedupe; conflicting values, unsupported versions, nonexistent paths, non-application targets, missing/extra rows under a complete-coverage claim are validation failures).
3. The `:yang/site` safepoint marks and all other frontend metadata preserved independently (the keys are distinct; do not touch safepoint.cljc).
4. The coverage boundaries from design §2.1: `yin/def` lowering to `:define` gets an occurrence annotation and never a fictitious callee-entry event; dedicated operations (`:dao.stream.apply/call`, stream nodes, continuation nodes) keep their existing instruction provenance and are NOT counted as application sites; generated post-expansion trees carry their `[:expansion …]` origins honestly.

## The three-kind correspondence and the sibling frontends

The oracle's three-kind correspondence (ambient-literal / context-API carriers / delegation exclusion) is ORACLE-side attribution — out of scope here. This round emits the FACTS; the oracle and future consumers interpret them. The ts/php/py adoptions are separate rounds (rounds 2-4) — do not touch their preludes (track isolation; the relay thread collab/yang-call/1794800000001 records their inputs, including the centrally-fixed key name and the dynamic-call-site edge case which the DESIGN already covers via `[:expansion …]` origins — verify the design's answer covers their case and note any gap in your report rather than inventing semantics).

## Verification (test-boundary ruling: focused local, suite-scale remote via the seat)

- The equality-gate test: derive the eligible application set from canonical rows, compare with the marker set — equal under a C1 publication claim (the design's own validation test).
- The boundary tests: `yin/def` annotated; stream/continuation nodes NOT marked; duplicate delivery deduped; a conflicting value, an unsupported version, a nonexistent path, a non-application target each fail validation.
- The oracle's own site tables (the instantiation-#1 consumer) must still reconcile — run the oracle's attribution test as a consumer check.
- Production-compile a real program (not a fixture stub) and verify the markers appear in its side table with correct coordinates.
- kondo 0/0 on touched files. Suite-scale runs: list the exact commands for the seat (jupiter).

## Boundaries

- src/cljc/yang/clojure.cljc + its production publication path + test/yang/** for the new tests. NO yin.vm representation changes (provenance stays OUT of hashed code content — the occurrence/content boundary is A1's hard line). NO other frontends' files. The oracle's existing site-table format is untouched (it is the instantiation-#1 consumer).
- Commit on a branch (`callsite-contract-clojure`); push; do NOT merge.

## Report

The diff per file; every test's real output; the equality-gate demonstration on a production-compiled program; any design gap found (report, don't invent).

## ROUTING NOTE (2026-11-06, the orchestrator): glm hit its weekly/monthly cap (resets 2026-11-10 14:30 +07); this round is RE-ROUTED to muse-wonder-1.0 via the API lane (probed OK). The implementer is muse, not glm — the brief stands unchanged. VERIFICATION-aaq.md's census and the signed ruling remain the verification contract.
