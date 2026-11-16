Created-GMT: 2026-11-13 17:5x GMT
Created-Local: 2026-11-14 00:5x +0700
Coding-Agent: codex
Session-ID: (resume of thread 01a1d5bd-11ef-7513-9672-97c79d26f3bc)

# Task: D1 continuation r5 — implement the (b) fidelity amendment, then complete the admission/replay gates

Role: VM Engineer (compiler provenance)

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-14 00:5x +0700 | Status: active | Rationale: continuity

## State resume (by state)

Worktree `d1-correspondence` @ `56cc5ca3` + UNCOMMITTED r1-r4 work. Astra's second ruling: `/Users/sto/workspace/datomworld/collab/yang-clojure/1794422563000-architect-d1-profile-amendment-r2.gpt-6-astra.findings.md` — READ IT FIRST. RULING (b): equal-valued Byte/Short/Integer/Long wrappers interchange (the Integer is tools.reader source-logging provenance — ordinary); BigInt/Ratio/BigDecimal/Double keep class identity (with the NaN clause); no other cross-class conversion; the wrapper-level relation is scoped to the pinned D1 compilation; the fidelity relation gets its own versioned profile identity; r4's class-strict refusal is preserved as historical evidence with the real-fixture expectation changed to success-ONLY-IF-observed under the amended profile.

## Part 1 — implement the (b) amendment (the six r4-prototype findings, file:line in the ruling)

1. Replace the numeric branch of `mismatch` (correspondence_text.clj:47) with the ruling's explicit relation: the four-wrapper interchange rule, same-class `.equals` for the admitted distinctions (no generic `=` before it — NaN), no other cross-class conversion. Keep syntax, ordering, metadata-presence checks intact.
2. Version the fidelity relation explicitly in the profile record (correspondence_text.clj:6 — `:domain :edn-with-readable-metadata` cannot distinguish old from amended rules); retain the relation's implementation under the new profile identity.
3. Map-mismatch diagnostics carry the entry KEY (correspondence_text.clj:38), not just the ordinal.
4. Capture diagnostics carry the admission scope/ordinal (latte_oracle_test.clj:2513) — never identity synthesized from content.
5. The regression test (latte_oracle_test.clj:4267) preserves the precise Integer/Long-at-`[15 2 :metadata [:value 2]]` cause AND separately asserts successful replay under the amended profile — per the ruling's step 3 ("change the real-fixture expectation to successful capture only if observed", keeping the refusal's precise form).
6. Red-first per the ruling's step 2 acceptance list: equal Integer/Long accepted (leaf + metadata), refusals retained for changed coordinate VALUES, missing metadata, decimal-scale differences, signed-zero, Long/BigInt substitution, syntax-kind changes, collection-order changes.

## Part 2 — complete the admission/replay gates under the amended profile

With fidelity passing on the real fixture: establish compilation/root, source-hash re-derivation, and reopened-store gates for the corpus fixture (the r4 remaining list). Every gate per the ruling's "Reconstruction obligation" clause — unchanged comparisons. Report any NEW independent refusal at its actual stage (step 4 of the ruling).

## Bounds/invariants/deliverable

Same as r4. The v1 bytes stay frozen. r4's class-strict refusal preserved as historical evidence. Verification: red-first per item; the full table re-run; kondo 0 errors. Deliverable: `/Users/sto/workspace/datomworld/collab/yang-clojure/1794422563000-engineer-d1-correspondence-r5.gpt-6.1-terra.findings.md` (or mirror + say-so). Commit NOTHING.

If the amended profile passes the real fixture and all gates complete: say so explicitly — that is the checkpoint the orchestrator needs for r6 (the corpus record + companion file + verifier construction).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
