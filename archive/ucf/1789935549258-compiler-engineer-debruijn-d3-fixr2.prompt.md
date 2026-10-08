Created-GMT: 2026-09-20 20:19:09 GMT
Created-Local: 2026-09-21 03:19:09 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: 923b8885-4549-4b46-ad11-0731ebb614ef (resumed — your D3 session)
# Task: apply the D3 review round's accepted findings
Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-21 03:19:09 +07 | Status: active | Rationale: same implementer, warm session

Review verdict was READY with 3 P2 + 5 P3; the orchestrator accepted all
except the design-document P3 (the orchestrator amends the doc). Apply in
src/cljc/yin/vm/debruijn.cljc and test/yin/vm/debruijn_test.cljc only:

- **P2 (unpaired surrogates, ~766-786 + canonical-class)**: a high surrogate
  not followed by a low surrogate (or a lone low surrogate) in a string or in
  an ident's namespace/name must classify :unsupported-value — the three
  hosts currently encode it three different ways, breaking byte identity.
  Add the fixture to the pinned-bytes rows so all three lanes assert the
  diagnostic.
- **P2 (map-key collision, ~933)**: if canonicalizing a map's keys merges
  entries (count shrinks — e.g. {1 :a, 1.0 :b} on the JVM, or two string
  keys collapsing to one NFC form), raise :unsupported-value for the key
  collision; iteration order must never decide which entry survives. Sets
  are unaffected. Fixture: a colliding map diagnosed.
- **P2 (test, records-carry-canonical-scalars)**: the NFC half reads the
  composed input (already NFC) and proves nothing. Assert
  (= (:records decomposed) (:records composed)) and read the stored scalar
  from the decomposed projection.
- **P3**: pin the settled dimension digest literal 11954e461ed58cfef109c6e426cb2eabbdc89ae7850c95ef9e4a5e59f578a2d3
  directly in the dimension test (the definition-restating assertion cannot
  fail; the literal catches descriptor drift as a descriptor failure).
- **P3 (~829-833)**: read the CLJS double bits through a DataView with
  littleEndian: true (explicit, matches the Dart branch) instead of a
  Float64Array/Uint8Array platform-order read.
- **P3 (~975-980)**: merkle-node docstring — remove the now-false "first
  spelling is the record written"; state that records carry canonical
  spellings.
- **P3 (~933-934)**: (empty v) throws on a record literal — guard the map
  branch so a record value diagnoses :unsupported-value instead of raising.

Verification (exact counts): focused JVM, kondo, cljstyle. Orchestrator
reruns the full lanes. One simple command per step.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
