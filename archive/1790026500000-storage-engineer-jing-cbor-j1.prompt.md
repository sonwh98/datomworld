Created-GMT: 2026-09-21 21:35:00 GMT
Created-Local: 2026-09-22 04:35:00 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 51cbe9c5-542b-4efc-99ce-082ca4a1beb8 (resumed: your J0 corpus and fix turns)
# Task: jing-cbor-j1: the JVM/Node canonical CBOR codec and portable numeric operations
Role: DaoSpace & DaoJing Storage Engineer
Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-22 04:35:00 +07 | Status: active | Rationale: same implementer as J0 (resume; it holds the corpus context); J0 is frozen and committed (f5f71e95); reviewers will be a different family

Work ONLY in /Users/sto/workspace/worktree-jing-cbor (branch jing-cbor, HEAD f5f71e95;
launch directory). Do NOT stage, commit, merge or push.

## Scope (architect's J1, collab/1790011562103-ref... is NOT staged under that
## name: read collab/1790011562103-architect-jing-cbor-work-package.gpt-5.6-sol.findings.md,
## section J1, section 3 "Effective Boring options", and section 6 risks)

File box (exactly these):
- NEW src/cljc/dao/jing/cbor.cljc (the codec: encode value -> canonical bytes;
  decode bytes -> value; the closed named-frame dispatch; validation, refusals;
  the portable numeric carriers and operations)
- NEW src/cljc/dao/jing/cbor/boring.cljc (the Boring 0.1.30 adapter, mirroring the
  pattern of src/cljc/dao/stream/cbor/boring.cljc; JVM and CLJS)
- NEW test/dao/jing/cbor_test.cljc
- Test-only additions to test/dao/jing/cbor_fixtures.cljc (helpers only; do not touch
  the frozen resource or the fixtures_test self-tests)
- NO edits to dao.jing, its backends, dao.space, transport code, deps.edn, bb.edn.
- Not in scope: Dart (J2), any wiring into dao.jing entry points (step 3), the dao.space
  comparator changes (gated on the owner).

The contract is docs/design/dao.jing.cbor.md ("Encoding contract", "Numeric identity")
plus the FROZEN corpus test/resources/dao/jing/cbor-v1.json (372 cases; README =
the rulings A1-A17 and the refusal class vocabulary, architect-ratified). The corpus
is immutable: NEVER edit it. If a case seems wrong, stop and report it; do not
"fix" it. Pattern-only prior art (a DIFFERENT profile; do not reuse its bytes):
src/cljc/dao/stream/cbor.cljc and cbor/boring.cljc.

## Completion criteria (the architect's, restated)

- JVM and Node run EVERY corpus case: canonical inputs encode to the exact frozen hex
  and decode back to an equal value; the SHA-256 of the bytes equals the frozen digest;
  every encode-refusal case is refused with the frozen class; every decode-refusal case
  is refused with the frozen class. Cases the README marks N/A on a host are skipped
  explicitly by id with the README's reason, never silently.
- Equivalence groups encode to identical bytes; retained-distinction groups to
  different bytes.
- Map keys and set elements sorted by unsigned canonical bytes (prefix first);
  duplicates and equality collapses are rejected before collection construction.
- Strict UTF-8, no normalization; unpaired surrogates rejected recursively (metadata
  and identifier parts included, validate-before-strip per A9).
- Identifiers via exact ns/name fields, never print/parse; tag 39 always refused.
- The four named frames use exactly [name-string payload] with closed dispatch; note
  Boring 0.1.30 does not read them as UnknownRecord: use its callback/sentinel path,
  or, if the outer with-meta wrapper (A4) cannot be produced by Boring, construct that
  frozen frame explicitly. NEVER change the bytes and NEVER use the stream profile's
  inline-meta list form. Tests prove effects (repeated strings stay text, no shapes, no
  index/trailing frame, one value then EOF, the ordinary encoder not the indexed API,
  conflicting options rejected).
- Native CBOR floats rejected; float content is the eight-byte big-endian
  dao.jing/float64 payload; float32 widens exactly.
- Integer, float64, decimal and rational carriers keep their kind; portable =, hash and
  compare are exact (no double rounding), symmetric across carrier/native operands, equal
  numeric values compare zero and hash alike across kind, scale and zero sign;
  -inf < finite < +inf < NaN, canonical NaNs equal; collections containing carriers
  recurse. Independent of ambient print settings (*print-length*, *print-dup*, ...).
- No change to any existing entry point's behavior.

## Environment (same notes as before)

Kondo, cljstyle, the Java 17 lane and the CLJD lane are run by the orchestrator; do
not retry denied commands. Your runnable lanes: focused JVM
`clojure -M:test -n dao.jing.cbor-test` (Java 21 default is fine) and the generator
`--check`. The CLJS lane needs node_modules (symlinked into this worktree now); if
`bb test:cljs` is denied, say so. Reader conditionals: put :cljd FIRST when a branch
must exclude cljd (`#?(:cljd nil :clj ... :cljs ...)`); this unit is JVM+CLJS only, so
guard cljd-visible code explicitly. Never report counts you did not observe. Keep
files pure ASCII, no em dashes, cljstyle-style Clojure (blank lines between forms),
docstrings that say what and why.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 51cbe9c5-542b-4efc-99ce-082ca4a1beb8
Then: files created/changed; how each completion criterion is met; corpus cases run
per kind and any skipped by id with reason; every deviation or ruling you needed; what
you ran with exact counts; what you could not run. Facts only.
