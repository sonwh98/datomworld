Created-GMT: 2026-11-13 17:0x GMT
Created-Local: 2026-11-14 00:0x +0700
Coding-Agent: codex
Session-ID: (resume of thread 01a1d5bd-11ef-7513-9672-97c79d26f3bc)

# Task: D1 continuation r4 — implement the (A) ruling: capture-at-boundary, the pinned profile, and the replay gates

Role: VM Engineer (compiler provenance)

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-14 00:0x +0700 | Status: active | Rationale: continuity

## State resume (by state)

Worktree `d1-correspondence` @ `56cc5ca3` + UNCOMMITTED r1-r3 work (correspondence.cljc structural checker + storage functions + binding-coverage?, tests incl. the durable-store and live-capture pilots, findings chain r1-r3). Astra's RULING (A) just landed: `/Users/sto/workspace/datomworld/collab/yang-clojure/1794524600000-architect-d1-recipe-input-adjudication.gpt-6-astra.findings.md` — READ IT FIRST; it contains the normative §4.1 amendment (six clauses) and five concrete pilot deficiencies with file:line. Your r3 pilot is reclassified: feasibility pilot only, no accepted corpus record exists to migrate.

## The work (implement the ruling verbatim)

1. **Capture at the input boundary** (amendment clause 1 + deficiency 1): move capture to BEFORE the order-observing consumption — capture the assembled program text at the same point `yang/compile-program` receives it, and pin `forms` and `call` as text too (deficiency 2: the source-hash function traverses and prints them; their order-sensitivity is unverified without text capture). Capture-before-consumption, not post-compilation printing.
2. **The pinned printing/reading profile** (amendment clause 2 + deficiency 3): a versioned profile record pinning the executable printer/reader implementations, versions, host/runtime, encoding, the JVM-initial profile (UTF-8, `pr-str` with metadata, unlimited length/level, readable printing, duplicate printing disabled, namespace-map shorthand disabled), the EDN reader contract (exactly one top-level value, EOF afterward, no ambient readers, pinned tag handlers, unsupported forms refuse). Store the profile by address; the recipe references it.
3. **Admission-time fidelity + replay gates** (amendment clause 3 + deficiencies 4-5): at admission, capture → read-back → compare with the ACTUAL input (syntax distinctions, numeric identities, metadata — NOT `content=`, which ignores metadata and equates list/vector; write the profile's fidelity check) → compile the read-back under the pinned profile and recorded context → require the canonical root equals the original's → retain the complete row closure. After durable storage, repeat reconstruction from fetched-only inputs with the same gate. Per-compilation independence: one fixture's pass licenses nothing.
4. **Exactness beyond the root** (amendment clause 4): replay must also reproduce the canonical-source-hash, independently derived site declarations, the admission/expansion associations, the complete C1 relation, and the transformation correspondence — reusing the cc3 rebind mechanics (2 applications per wrapper, extraction `[:operands 1 :operands 1 :value]`, `[3 1] [3 2]` paths — pinned via executable dependency retention, per clause 4's pointer to the rebind note).
5. **Refusal discipline** (amendment clause 5): every refusal case from the clause implemented and tested (missing capture, unavailable dependencies, unsupported profiles/forms, reader failure, fidelity mismatch, root divergence, source-hash mismatch, correspondence mismatch) — each reports the failing compilation and stage. Never recover by sorting, switching to decoded-value compilation, weakening equality, or minting origins.
6. **Regression set** (migration step 3): the observed decoded-value divergence becomes a regression case; add metadata, collection-kind, map/set order, and unreadable-input cases. Red-first for each.

The verifier, bounds declaration, and tagged run stay OUT of this round (r5+): this round ends at "each corpus compilation passes the admission+replay gates under the pinned profile."

## Bounds/invariants/deliverable

Same as r3: bounded files (correspondence.cljc, the profile record, tests, latte_oracle_test.clj capture-site changes); forbidden set unchanged; the frozen v1 bytes stay frozen; no tagged run; commit NOTHING. Verification: red-first per item; the full r3 table re-run green plus the new regression set; kondo 0 errors. Deliverable: `/Users/sto/workspace/datomworld/collab/yang-clojure/1794422563000-engineer-d1-correspondence-r4.gpt-6.1-terra.findings.md` (or worktree mirror + say-so); the findings chain stays append-only.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
