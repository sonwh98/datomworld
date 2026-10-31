Created-GMT: 2026-09-21 23:20:00 GMT
Created-Local: 2026-09-22 06:20:00 +07 (Indochina Time)
Coding-Agent: glm (glm-5.3) | cmd (qwen/qwen3.8-max) (shared brief; two independent reviewers)
Session-ID: caller-generated for glm; pending for cmd
# Task: jing-cbor-j2: independent adversarial review of the Dart codec and the now-shared codec
Role: Adversarial Review (Storage & Encoding, Dart)
Implementers:
- Model: glm-5.3 and qwen/qwen3.8-max | Assigned: 2026-09-22 06:20:00 +07 | Status: active | Rationale: the code was written by claude-opus-5 (Claude family); reviewers must differ; the architect earmarked GLM for one bounded, independent J2 review; both are owner-authorized

Read-only. Work ONLY in /Users/sto/workspace/worktree-jing-cbor (branch jing-cbor,
HEAD 4f928dbd; J0 f5f71e95 and J1 7968884b are committed and architect-signed; the
J2 changes are UNCOMMITTED in the working tree). Do NOT edit or create any file and do
not run tests (the orchestrator is running all lanes). You may read files and run
read-only git commands (`git status`, `git diff`). Produce the complete review now as
your final response; do not wait for approval and do not promise a verdict.

## What you are reviewing

`git diff` for: src/cljc/dao/jing/cbor.cljc (the `:cljd nil` gate removed; the codec is
now shared by JVM, Node and Dart), src/cljc/dao/jing/cbor/boring.cljc (adapter
functions), test/dao/jing/cbor_fixtures.cljc, test/dao/jing/cbor_test.cljc; and the NEW
src/cljd/dao/jing/cbor/cljd.cljd (the Dart byte writer over `cbor` 6.5.1). The
implementer's report is collab/1790028200000-ref-implementer-j2-report.md.
Contract: docs/design/dao.jing.cbor.md ("Encoding contract", "Numeric identity"); the
frozen corpus test/resources/dao/jing/cbor-v1.json with README and the RATIFIED
errata test/resources/dao/jing/cbor-v1.errata.md (read both). The architect's J1
sign-off and the J2 constraints: collab/1790027800000-ref-architect-j1-signoff.findings.md.
Work package (J2 section): collab/1790011562103-architect-jing-cbor-work-package.gpt-5.6-sol.findings.md.

## What to judge (concrete; cite file:line and corpus case ids)

1. **JVM/Node non-regression.** The J1 encoder now builds nodes through adapter
   functions (`null`, `bool`, `text`, `byte-string`, `integer`, `array-node`,
   `map-node`). Do the JVM and CLJS adapter functions return EXACTLY what the J1 code
   passed to Boring before (`git diff` shows the change)? Could any byte change on
   JVM or Node for an input outside the corpus (integer widths, bignums, nested
   metadata, empty collections, negative zero)?
2. **Dart writer correctness (cljd.cljd).** Does the writer produce shortest-form heads
   for every major type including the 8-byte cases and bignum tags 2/3, definite lengths
   only, tag 258 sets, tag 4/30 numbers, tag 27 frames, byte strings, exact float64
   payload bytes? The `cbor` 6.5.1 package does not sort map keys (the adapter sorts
   by canonical bytes) and writes `?` for a lone surrogate (the codec must reject
   before). Is either assumption violated on any path? Any place the package could
   re-encode, re-order, narrow (integral double to int) or allocate differently from
   what the shared code intends?
3. **Dart host traps in the shared reader/profile code.** Dart `int` is 64-bit and
   distinct from double; `BigInt.compareTo` may return any sign-magnitude (the
   implementer normalized it); Dart's UTF-8 decoder drops a leading BOM (the implementer
   restored it); UTF-8 decoding strictness (overlong, surrogates, > U+10FFFF); bit
   operations; hashing; string code-unit vs rune handling for surrogate detection
   (Dart strings are UTF-16: is an unpaired-surrogate check correct on Dart?); Dart
   equality and hashing of doubles, NaN and -0.0; Set/Map construction and the
   collapse checks; keyword and symbol equality of slash-crossed identifiers; the
   `fresh-list` metadata clearing. Anything that behaves differently on Dart in a way
   the corpus does not pin?
4. **Ratified constraints.** Exact decimal window, max-depth 128, host-collapse only if
   Dart merges (implementer says never), float64 kind preserved, no narrowing, BigInt
   for exact integers, explicit outer with-meta frame, structural validation before any
   package materialization, decode followed by strict re-encode. Each satisfied?
5. **Tests.** Mutation thinking for the Dart lane: skips only by id with a reason; the
   encode-refusal runner now collects host merges and asserts once at the end; Dart
   `is` failures throw and can hide later cases (is that handled everywhere, including
   decode runners?). The implementer proposes a Dart column for the errata E1 table
   (signed-zero and vector-list N/A on Dart, all others live): verify it against the code.
6. **Safety.** No edits to dao.jing, backends, dao.space, transport, deps.edn, bb.edn,
   pubspec; the frozen files untouched; no print/parse; ASCII only.

## Deliverable

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: <glm|cmd>
Session-ID: <your session id if visible, else pending>
then a verdict: READY, READY WITH CHANGES, or NOT READY; findings as P1 (wrong bytes,
wrong acceptance or refusal, an unsound operation, a JVM/Node regression), P2
(significant gap), P3 (minor), each with file:line, the plan or errata sentence and
the smallest fix; what you checked and found clean. Findings only; edit no file.
