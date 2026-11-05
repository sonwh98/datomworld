Created-GMT: 2026-11-05 04:30:00 GMT (v1)
Revised-GMT: 2026-11-06 02:30:00 GMT (v2 — astra's four amendments; endorsed for owner signature with these)
Status: ADDENDUM A v2 to the signed attribution-as-query ruling — pending owner signature (astra verdict: endorsed as architectural direction and scheduling authority, with the four amendments below incorporated)
# ADDENDUM A v2 — call-site annotation generalizes: every yang.* compilation pipeline preserves caller-site provenance

## The owner's directive (recorded)

"the other compilers must also add more information to the compilation pipeline by annotating the callsite." Caller-site provenance — caller-site identity at post-expansion UAST application occurrences — is the declared standard for every yang.* frontend. The oracle's instantiation is the first consumer; the other frontends' adoption is scheduled direction.

## A1 — The annotation attaches to UAST application OCCURRENCES; no canonical node field

The existing architecture defines a call site as `[origin root-address path]` and **excludes provenance from hashed code content** (docs/design/yin.vm.code-as-tuples.md ~:659); occurrence side tables and instruction-provenance mappings already exist. Ruling: every frontend preserves caller-site provenance for post-expansion UAST application **occurrences** through a shared, language-neutral occurrence contract; the design round starts from the existing occurrence and instruction-provenance relations. **This addendum does not authorize a new canonical AST slot** — any such proposal requires explicit justification and amendment of the occurrence/content boundary.

## A2 — Versioning obligations are separated, and inventoried before anything is revised

Evidence schemas, lowering profiles, execution contracts, and module-manifest schemas have **distinct revision rules** (docs/design/yin.vm.linker.md ~:1871; lowering profiles at code-as-tuples.md ~:861); existing machinery detects incompatibility but does not migrate artifacts. The design round must inventory: affected producers, validators, lowering and reconstruction paths, derivation verification, publication, persisted artifacts, and mismatch behavior — and revise only the contracts actually changed.

## A3 — Adoption coverage and host verification are explicit

Adoption covers **production `yang.clojure`** (oracle instrumentation does not establish coverage of ordinary compilation paths), TypeScript, PHP, and Python, with additional entry points inventoried during design. Each frontend has an acceptance gate; applicable JVM, Node, Dart, and Jolt host lanes verify the resulting revision. Current sweeps, the J2 re-run, and the oracle round continue under their existing scopes. The oracle's instantiation is described as **oracle-specific instrumentation** (its signed scope already permits walker changes) — not as production coverage.

## A4 — R1 activates at signature; the dependency is precise

By signing this addendum, the owner **activates R1's deferred work** — no shipped second consumer is required. The design round specifies the durable correspondence record and its additive relationship to versioned oracle evidence; implementation and independent reconstruction complete **before, or atomically with, acceptance of the first additional consumer that relies on it**. The oracle round remains unblocked.

## Design-round obligations (declared)

Measure: application counts, provenance and trace sizes, peak trace occupancy, instruction overhead, compilation/loading/query time, and memory. Separate static annotation costs from runtime tracing costs; declare bounds before tagged runs. Correctness gates: duplicate occurrences, macro expansion, helper calls, indirect and tail calls, and applicable suspension/resumption paths.

## Boundaries

The signed ruling's R1-R7 stand as written. The in-flight sweeps (cell-ref, ts/php/python), the jolt J2 re-run, and the oracle round are unaffected. Budget impact per frontend is a design-round deliverable, not assumed here.
