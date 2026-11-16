Created-GMT: 2026-11-13 15:5x GMT
Created-Local: 2026-11-13 22:5x +0700
Coding-Agent: codex
Session-ID: pending (provider-generated — fresh thread)

# Task: Design adjudication — the D1 recipe's reconstruction input (an order-sensitive replay hazard, found in implementation)

Role: Lead System Architect (design adjudication on your own design of record)

Implementers:
- Model: gpt-6-astra | Assigned: 2026-11-13 22:5x +0700 | Status: active | Rationale: you authored the D1 design (1794670000000); the question is a defect/ambiguity in its §4.1 recipe contract, found during implementation

Perform a read-only design adjudication. Do NOT edit files. Your output is a ruling.

## The finding (from the D1 implementation, round r3 — honest, red-first)

D1's recipe contract (your design §4.1: "The consumer must implement that exact recipe/profile... The exact oracle schema identity remains the dependency") assumed the reconstruction input would be the retained compilation state. Implementation found:

- **Recompiling the decoded Jing `:program` produces a DIFFERENT root than the original, even though the decoded value compares equal** (`dao.jing.cbor/content=` passes). A dynamic map at program path `[48 4]` changes iteration order across the canonical codec, and `yang.clojure/compile-constructed-collection` lowers collection iteration order into operands — so the iteration order is observable by the compiler and the roots diverge (measured: `:segment/blake3-174be2…` vs `:segment/blake3-540da0…`).
- A **single-fixture pilot** captured the program's printed EDN (with metadata) before storage; reading that text after reopen yields content= to the captured original AND recompiles to the original root. But that is one fixture — printing/reading is not proven to preserve all source shapes, metadata, ordering, or semantics corpus-wide.
- The D1 round correctly refused to invent a solution and escalated per the brief. The findings: `collab/yang-clojure/1794422563000-engineer-d1-correspondence-r3.gpt-6.1-terra.findings.md` (read it — "Live admitted compilation and the order-sensitive replay finding" + "Unresolved items").

## The question to rule on

**What is the D1 recipe's reconstruction input?** The design §4.1 must say. The candidates:

- **(A) Captured source text**: the recipe's retained inputs include the program's printed EDN (with metadata) captured at admission; reconstruction = read-that-text-and-compile. The pilot proves feasibility on one fixture; corpus-wide requires a specified printing/reading contract and a per-input replay gate (recompile → root must match the retained root, else REFUSE the record as unreconstructable). This keeps `dao.jing` values as values (equality) and moves the exactness obligation to the text layer.
- **(B) An ordering-preserving representation contract**: define what the codec must preserve (or a canonical iteration order for dynamic maps at compilation-relevant paths), fix `yang.clojure/compile-constructed-collection`'s order-sensitivity, and reconstruct from decoded values. This changes the codec/compiler contract — larger blast radius, touches `yin.vm`/`dao.jing` surfaces this round does not own.
- **(C) Hybrid**: retain BOTH the source text and the decoded values; the recipe's replay gate compares roots and refuses on divergence; the text is the reconstruction path, the values are the equality check.

## Rule with this evidence in hand

Read: the r3 findings (path above); your design §4.1 (the recipe/profile contract) and §2.4–2.5 (what reconstruction means for this program store); `yang.clojure/compile-constructed-collection` (the order-lowering site); `dao.jing`'s canonical codec contract as it treats dynamic-map iteration order; the cc3 rebind note `1794519755000-...reverification...findings.md` item 3 (what the recipe must reproduce). Consider: unitarity (values equal but roots differ — is that a violation or an expected consequence of order-observable lowering?), the "derive, don't persist" principle vs the recipe's exactness obligation, and what the fix costs in each option's blast radius.

Rule: **(A), (B), or (C)** — with the contract text the implementer should write (what the recipe pins, what the replay gate asserts, what refuses), and the migration path for the already-piloted code.

## Output

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then: `RULING: (A|B|C) — <one-line contract statement>`, the contract text for §4.1's amendment, the migration steps, and any defects found in the piloted code (file:line). Static review; no suites; no edits.
