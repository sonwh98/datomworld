Created-GMT: 2026-11-13 15:4x GMT
Created-Local: 2026-11-13 22:4x +0700
Coding-Agent: codex
Session-ID: (resume of thread 01a1d5bd-11ef-7513-9672-97c79d26f3bc)

# Task: D1 continuation r3 — durable retention, the real corpus record, and the companion file

Role: VM Engineer (compiler provenance)

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-13 22:4x +0700 | Status: active | Rationale: continuity — r1 (inventory + structural checker) and r2 (storage functions + binding coverage) are your rounds; this is the next coherent checkpoint

## State resume (by state)

Worktree `d1-correspondence` @ `56cc5ca3` + UNCOMMITTED r1/r2 work: `src/cljc/yang/correspondence.cljc` (structural checker + store-evidence!/store-recipe!/store-record!/binding-coverage?), tests (red-first, 46/608/0 combined green incl. the callsite battery), findings chain r1 (`1794670000001-...partial.findings.md`, worktree mirror) + r2 (`1794422563000-engineer-d1-correspondence-r2.gpt-6.1-terra.findings.md`, worktree mirror). Your own stated limits: in-memory store only; record = claim not verification; no companion; no verifier; no bounds declaration; inventory incomplete.

## The three items (the natural checkpoint your r2 findings point at)

1. **Durable retention**: replace/extend the in-memory store with a FILE-BACKED one (`yin.repl.store`'s file-backed store is the in-tree precedent; r8-of-cc3 used it for the provenance artifacts). Test: materialize the evidence/recipe/record, CLOSE and REOPEN the store, fetch byte-identical values. The r8-cc3 reopen-retrieval pattern is the template.
2. **The real corpus record**: produce the D1 record from the LIVE pinned-corpus compilation — a fresh admission (scope + ordinal retained per cc3's `kernel-compile`), the full compile through the C1 pipeline, the site declarations re-derived per cc3's machinery, the origin-bindings from the live run's retained coordinates. This is the record whose independent verification (r4) will check — build it honestly from retained coordinates only, never the historical table's identities.
3. **The companion file** `test/yang/latte_oracle/slice9_correspondence.edn`: write the corpus record there; the byte-pin test for the v1 tables (`e435ac40...`) stays green; a NEW test pins the companion's own schema and its reference to the v1 evidence address.

The verifier, bounds declaration, and tagged run stay OUT of this round (r4+): do not start them. If the corpus record's construction reveals a missing piece (e.g. an unestablished expansion-environment record), record it as an inventory item — do not invent it.

## Bounds/invariants/deliverable

Same as r2: bounded files (correspondence.cljc, tests, the companion file, latte_oracle_test.clj if needed); forbidden set unchanged; origins only from retained coordinates; red-first; the v1 bytes frozen. Verification: the reopen-retrieval test, the corpus-record construction test, the v1/companion pin tests, battery + correspondence tests green, kondo 0 errors. Deliverable: `/Users/sto/workspace/datomworld/collab/yang-clojure/1794422563000-engineer-d1-correspondence-r3.gpt-6.1-terra.findings.md` (or worktree mirror + say-so); r2's findings file stays as-is (append-only chain). Commit NOTHING.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
