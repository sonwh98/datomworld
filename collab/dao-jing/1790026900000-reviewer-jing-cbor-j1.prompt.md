Created-GMT: 2026-09-21 21:50:00 GMT
Created-Local: 2026-09-22 04:50:00 +07 (Indochina Time)
Coding-Agent: cmd (qwen/qwen3.8-max) | deepseek (shared brief; two independent reviewers)
Session-ID: pending (provider-generated) for cmd; caller-generated for deepseek
# Task: jing-cbor-j1: independent adversarial review of the JVM/Node canonical CBOR codec
Role: Adversarial Review (Storage & Encoding)
Implementers:
- Model: qwen/qwen3.8-max and deepseek-v4-pro | Assigned: 2026-09-22 04:50:00 +07 | Status: active | Rationale: the code was written by claude-opus-5 (Claude family); reviewers must differ; both families are owner-authorized

Read-only. Work ONLY in /Users/sto/workspace/worktree-jing-cbor (branch jing-cbor,
HEAD f5f71e95 = frozen J0 corpus; the J1 files are UNCOMMITTED in the working tree).
Do NOT edit or create any file and do not run tests (the orchestrator ran them: full
JVM green, focused 26 tests / 4269 assertions, CLJS 1601 tests, kondo and cljstyle
clean). You may read files and run read-only git commands (`git status`, `git diff`).
Produce the complete review now as your final response; do not wait for approval and
do not promise a verdict.

## What you are reviewing (new, untracked, plus one test helper edit)

- src/cljc/dao/jing/cbor.cljc (about 1130 lines: codec, structural reader, portable
  numeric carriers and operations; the body is gated `:cljd nil`)
- src/cljc/dao/jing/cbor/boring.cljc (Boring 0.1.30 writer adapter)
- test/dao/jing/cbor_test.cljc (the corpus runner and numeric tests)
- test/dao/jing/cbor_fixtures.cljc (`git diff`: helper additions only)
Spec: docs/design/dao.jing.cbor.md "Encoding contract" and "Numeric identity" IN FULL.
Frozen corpus (immutable, architect-ratified rulings A1-A17 and the refusal class
vocabulary in the README): test/resources/dao/jing/cbor-v1.json and cbor-v1.README.md.
Architect J1 completion criteria and Boring notes:
collab/1790011562103-architect-jing-cbor-work-package.gpt-5.6-sol.findings.md
(section J1, "Effective Boring options", and section 6 risks). The implementer's
report is collab/1790026500000-ref-implementer-j1-report.md (read it; the two
items in section 3 are quoted from it).

## What to judge (concrete; cite file:line and corpus case ids)

1. **Spec conformance.** Walk the codec against each J1 completion criterion: bytewise
   ordering and duplicate/collapse refusal BEFORE collection construction; strict UTF-8
   and recursive surrogate handling (validate-before-strip, ruling A9); identifier
   construction never via print/parse and tag 39 always refused; the four named frames
   are exactly `[name payload]` with CLOSED dispatch; the outer `clojure/with-meta` frame
   (A4) and the metadata rules (A5, P2-1); native CBOR floats refused; float32 widening;
   the single canonical NaN; decimal/ratio grammar. Is anything accepted that the
   plan rejects, or rejected that it accepts?
2. **Numeric identity.** `num=`, `num-hash`, `num-compare`, `equiv`, `equiv-hash`:
   exact comparison with no double rounding; native/carrier symmetry in BOTH operand
   orders; equal values compare zero AND hash alike across kind, scale and zero sign;
   -inf < finite < +inf < NaN with canonical NaNs equal; recursion through collections;
   the `Rational` carrier for denominator 1 on the JVM; BigInt vs long; the CLJS
   BigInt/number split (safe-integer numbers, BigInt). Try to construct a counterexample
   for hash consistency and for transitivity of compare.
3. **The two ratification items from the implementer (give your OWN recommendation
   for each; the architect decides afterwards):**
   a. **Decoding does not go through Boring.** The implementer probed Boring 0.1.30's
      decoder and found: tag 30 `3/1` decodes to `3N` (ratio kind lost); negative
      denominators accepted; stringrefs, tag 0/1 dates and tag 37 UUIDs decoded as
      ordinary values; it maps `clojure/with-meta` itself; `decode-seq` drops a trailing
      index frame; on JS a decoded native float is indistinguishable from an integer.
      So `decode` uses a Jing-owned structural reader (a port of the J0 Python checker),
      then re-encodes the decoded value through Boring and requires identical bytes.
      Boring stays the only byte WRITER. Is this sound? Is the Jing-owned reader a
      violation of the plan (which expects the codec to sit on Boring) or the correct
      reading of "validate shapes before any lossy host conversion"? Any hidden risk in
      hand-parsing CBOR here (recursion depth, allocation bombs, large lengths,
      indefinite lengths, huge tag/length heads)?
   b. **`coll/map-both-slash-keywords` cannot be decoded on Node.** ClojureScript keyword
      equality compares the joined name, so `(keyword nil "a/b")` and `(keyword "a" "b")`
      collapse to one map key. The decoder refuses this canonical case on Node with
      `:host-collapse`, a class OUTSIDE the frozen 16. Is that the right behavior, is a
      new class acceptable, or should it map to an existing class (which)? Does the same
      limit apply to sets, to `:keyword` values at all (identity injectivity on CLJS),
      and does it contradict the plan's "do not let the accepting host determine this
      boundary"?
4. **Boring adapter and effect proofs.** Are the option checks real (canonical profile
   locked; stringref and shapes off; ordinary encoder not the indexed API)? Could a
   Boring change of behavior silently alter bytes without a test failing?
5. **Tests.** Mutation thinking: would the runner fail if the codec were wrong in the
   ways it claims to catch? Are the skip lists (by id, per host, with reason) tight, and
   is anything skipped that should run? The implementer reports README N/A-table errors
   (a case the README calls not constructible on a host that in fact holds both members);
   check that claim for the cases named in the implementer's finding 3.
6. **Independence and safety.** No print/parse anywhere; no dependence on ambient print
   settings; no mutation of the frozen resource; the `:cljd nil` gating hides nothing
   that the Dart build would try to compile; no edits to existing entry points.

## Deliverable

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: <cmd|deepseek>
Session-ID: <your session id if visible, else pending>
then a verdict: READY, READY WITH CHANGES, or NOT READY; findings as P1 (wrong bytes,
wrong acceptance/refusal, unsound equality/hash/compare, an unsafe reader), P2
(significant gap), P3 (minor), each with file:line, the plan sentence and the smallest
fix; your recommendations on 3a and 3b; what you checked and found clean. Findings
only; edit no file.
