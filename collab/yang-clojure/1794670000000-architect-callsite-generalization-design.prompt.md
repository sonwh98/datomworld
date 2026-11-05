Created-GMT: 2026-11-05 04:40:00 GMT
Coding-Agent: gpt-6-astra (architect design round; read-only)
# DESIGN ROUND: the call-site provenance generalization — the occurrence contract and the durable correspondence record

Role: architect design (gpt-6-astra). The owner SIGNED Addendum A v3 (collab/yang-clojure/1794630000000-architect-attribution-addendum-a-RECORD.md) — read it first, then the signed base ruling (1794600000000-...ruling-of-record.md), your own addendum review (1794630000000-...addendum-a.astra-review.stdout.log), and qwen's addendum review if present in collab/yang-clojure/. This design round is authorized by A4 (R1 activates at signature) and delivers A1-A2's obligations.

## The deliverable: the generalization design document

A design doc (findings file in collab/yang-clojure/) specifying, per the addendum's A1-A4:

1. **The occurrence contract**: caller-site provenance for post-expansion UAST application occurrences, shared across frontends, language-neutral. START from the existing machinery per A1: the `[origin root-address path]` occurrence identity, the frontend-metadata side table `[origin root-address path key value]` (code-as-tuples.md ~:445-455 — the likely minimal form: one reserved key for persisted artifacts + the 3-vector trace field for ephemeral tracing), the instruction-provenance mappings, and §2.6's runtime-stamping legality. NO new canonical AST slot (the occurrence/content boundary holds; amending it requires its own justification).
2. **The ephemeral-origin stand-in**: what occupies the `origin` coordinate in ephemeral (oracle-style) instrumentation vs persisted artifacts — named precisely (post-expansion origins are `[:expansion record-address]`; the oracle's harness-level stamping mints no expansion records today).
3. **The versioning inventory** (A2): every affected producer, validator, lowering/reconstruction path, derivation verification, publication path, persisted artifact (INCLUDING the per-host prelude publication artifacts and the oracle's checked-in EDN evidence schemas), and mismatch behavior — and which contracts actually change vs which are only read.
4. **The durable correspondence record** (R1, now activated): its schema, its additive relationship to the oracle's versioned site tables (REUSE the oracle evidence format verbatim per the addendum), its landing condition (before/atomically-with the first consumer's acceptance).
5. **Adoption coverage** (A3): production yang.clojure + ts/php/python; the additional entry points inventoried; the mandatory host-lane set per frontend (JVM/Node/Dart/Jolt as applicable) + the cross-host occurrence-key equality gate; the ordering against the in-flight seats' work (the cell-ref sweeps, the J2 re-run).
6. **The measurement plan** (the addendum's declared obligations): application counts, provenance/trace sizes, peak occupancy, instruction overhead, compile/load/query times, memory — static annotation costs separated from runtime tracing; bounds declared before tagged runs.
7. **The correctness gates**: duplicate occurrences, macro expansion, helper calls, indirect and tail calls, suspension/resumption paths.

## Constraints

- Read-only; no code changes. The design round's output is the document.
- Ground every claim in the current sources (file/line). Where a fact is unknown (e.g. whether the linker's derivation records can carry the correspondence without a schema bump), say "unknown — the implementation round's inventory decides" rather than guessing.
- The per-frontend adoption rounds are OUT of scope (they consume this design); their briefs cite this document.
- The oracle round (in flight, branch attribution-as-query) is instantiation #1 — design around its recorded format per A4 (verbatim reuse), and note any divergence risk between its in-flight site tables and this design (the race astra's review flagged: one format, not two).

## Acceptance

The design document answers A1-A4 fully, is grounded, and gives each frontend adoption round a complete brief-from-which-to-work. Verdict format: the design + any risks for the owner's attention.
