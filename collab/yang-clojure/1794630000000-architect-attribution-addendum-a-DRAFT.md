Created-GMT: 2026-11-05 04:30:00 GMT (v1)
Revised-GMT: 2026-11-06 02:30:00 GMT (v2 — astra's four amendments)
Revised-GMT: 2026-11-06 03:20:00 GMT (v3 — qwen3.8-max's four amendments; both reviews endorse for owner signature)
Status: ADDENDUM A v2 to the signed attribution-as-query ruling — pending owner signature (astra verdict: endorsed as architectural direction and scheduling authority, with the four amendments below incorporated)
# ADDENDUM A v2 — call-site annotation generalizes: every yang.* compilation pipeline preserves caller-site provenance

## The owner's directive (recorded)

"the other compilers must also add more information to the compilation pipeline by annotating the callsite." Caller-site provenance — caller-site identity at post-expansion UAST application occurrences — is the declared standard for every yang.* frontend. The oracle's instantiation is the first consumer; the other frontends' adoption is scheduled direction.

## A1 — The annotation attaches to UAST application OCCURRENCES; no canonical node field

The existing architecture defines a call site as `[origin root-address path]` and **excludes provenance from hashed code content** (docs/design/yin.vm.code-as-tuples.md ~:659); occurrence side tables and instruction-provenance mappings already exist. Ruling: every frontend preserves caller-site provenance for post-expansion UAST application **occurrences** through a shared, language-neutral occurrence contract; the design round starts from the existing occurrence and instruction-provenance relations. **The contract must define what occupies the `origin` coordinate in EPHEMERAL instrumentation**: post-expansion occurrences carry origin `[:expansion record-address]` (code-as-tuples.md ~:426), and the oracle's harness-level stamping mints no expansion records — its site ids are occurrence-key-shaped stand-ins, not persisted keys; the shared contract names the stand-in or it is shared in name only. The design round also cites **§2.6 (owner ruling 2026-09-15, code-as-tuples.md ~:470)** — runtime `assoc` into walker map nodes is legal because the held node is an ephemeral image never projected back; that is the clause licensing the oracle's stamping, and it must be on the record before someone flags it as a boundary violation. And the likely minimal form is named as the starting point (not mandated): **one reserved key in the EXISTING frontend-metadata side table** `[origin root-address path key value]` (code-as-tuples.md ~:445-455) for persisted artifacts, plus the 3-vector trace field for ephemeral tracing — the design round may find the "contract" is one key and one field. **This addendum does not authorize a new canonical AST slot** — any such proposal requires explicit justification and amendment of the occurrence/content boundary.

## A2 — Versioning obligations are separated, and inventoried before anything is revised

Evidence schemas, lowering profiles, execution contracts, and module-manifest schemas have **distinct revision rules** (docs/design/yin.vm.linker.md ~:1871; lowering profiles at code-as-tuples.md ~:861); existing machinery detects incompatibility but does not migrate artifacts. The design round must inventory: affected producers, validators, lowering and reconstruction paths, derivation verification, publication, persisted artifacts, and mismatch behavior — and revise only the contracts actually changed. NAMED so they are not skipped: **per-host prelude publication artifacts** (php/python/typescript publication.cljc, prelude_module/prelude_p9 — annotating production compilation means republished prelude artifacts on all four host lanes: the single largest persisted-artifact blast radius); **the oracle's own checked-in EDN evidence schemas** (throw_sites.edn, case_arms.edn — they are evidence schemas too); and the **backward-compat policy**: unannotated persisted artifacts remain loadable (side tables are additive) — stated, or it is re-litigated later.

## A3 — Adoption coverage and host verification are explicit

Adoption covers **production `yang.clojure`** (oracle instrumentation does not establish coverage of ordinary compilation paths), TypeScript, PHP, and Python, with additional entry points inventoried during design. Each frontend has an acceptance gate; the host lanes are a MANDATORY set per frontend (JVM, Node, Dart, Jolt as applicable — "applicable" alone is a loophole that lets a frontend ship under the easiest lane), plus a **cross-host occurrence-key equality gate**: the same source compiled on JVM/Node/Dart must yield identical occurrence coordinates, or the shared contract breaks. One ordering sentence against the in-flight work: the frontend adoption rounds sequence AFTER the in-flight cell-ref sweeps and the J2 re-run land (they revise the same prelude/publication files); the oracle round is unaffected. Current sweeps, the J2 re-run, and the oracle round continue under their existing scopes. The oracle's instantiation is described as **oracle-specific instrumentation** (its signed scope already permits walker changes) — not as production coverage.

## A4 — R1 activates at signature; the dependency is precise

By signing this addendum, the owner **activates R1's deferred work** — no shipped second consumer is required. The design round specifies the durable correspondence record, and it **REUSES THE ORACLE EVIDENCE FORMAT VERBATIM** (the base ruling's R1(b) already demands the site tables be in that format — the durable record binds to it, it does not merely relate to it; two rounds independently fixing "the stable, versioned evidence format" is a textbook race). Implementation and independent reconstruction complete **BEFORE acceptance of the first additional consumer that relies on it** — "or atomically with" is struck: reliance is accepted only after independent verification has passed (the linker's own `:verifying` policy). The oracle round remains unblocked.

## Design-round obligations (declared)

Measure: application counts, provenance and trace sizes, peak trace occupancy, instruction overhead, compilation/loading/query time, and memory. Separate static annotation costs from runtime tracing costs; declare bounds before tagged runs. Correctness gates: duplicate occurrences, macro expansion, helper calls, indirect and tail calls, and applicable suspension/resumption paths.

## Boundaries

The signed ruling's R1-R7 stand as written. The in-flight sweeps (cell-ref, ts/php/python), the jolt J2 re-run, and the oracle round are unaffected. Budget impact per frontend is a design-round deliverable, not assumed here.
