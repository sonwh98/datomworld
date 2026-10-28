Created-GMT: 2026-10-19 15:10:15 GMT
Created-Local: 2026-10-19 22:10:15 +07

# Review: the jolt host-lane implementation plan

You are the independent reviewer (the plan's author was gpt-6-astra; you have
not seen its conversation). Review it on the merits against the repository.

Under review:
- The plan: collab/1792421958748-architect-jolt-lane-plan.gpt-6-astra.findings.md
- Its evidence base (read first): the verified spike report
  collab/1792412960461-engineer-jolt-yin-repl-spike.claude-opus-5-5.findings.md
  and the working artifacts it references under build/jolt-spike/ (overlay/,
  blake3.clj, entry.clj, climb.sh — reproducible via JOLT_CHEZ at the mise
  Chez 10.4.1).

Review tasks:
1. EVIDENCE HONESTY: the plan labels claims S (spike-reported) / V (newly
   verified by reading) / P (proposed). Spot-check the V citations against the
   actual files (src/cljc line cites, jolt checkout cites, mise.toml, bb.edn,
   deps.edn, test fixtures). A mislabeled or wrong citation is a finding.
2. TECHNICAL CORRECTNESS of the load-bearing claims, especially:
   - The CBOR float correction: dao.jing represents floats as tag-27 float64
     frames and REJECTS native CBOR float encodings (plan cites
     dao/jing/cbor.cljc:19-20,993-1012,1448-1452 and
     test/dao/jing/cbor_conformance_test.cljc:127,415). Verify. If true, this
     corrects the brief's "finish f16/f64 framing" instruction — confirm the
     plan's replacement instruction is the right one.
   - The exact-integer bit-op algorithm (§4: residues mod 2^w, base-2 digit
     consumption, sign-extension reconstruction) — is it correct for
     infinite two's-complement AND/OR/XOR incl. negatives and zero? Any edge
     it gets wrong?
   - The reader-conditional discipline (:jolt before :clj before :default,
     .jolt file precedence) against jolt's host/chez/reader.ss and loader.ss.
   - The FFI approach (defcfn, tiny C wrapper around libblake3
     init/update/finalize, no C-string args, no raw pointers as guest values)
     and the libcrypto EVP recommendation for SHA-256/Ed25519.
   - The frontend recommendation (JVM ANTLR parsing worker via the existing
     process/stream patterns; reject ANTLR-free and CPython-AST options) —
     is the reading of yang.antlr.md's worker design and the legacy-parser
     removal correct?
3. COMPLETENESS vs the goal "on par with clj/cljs/cljd": any entry path,
   test-lane obligation, or host seam the plan misses? Any slice gate that is
   not actually falsifiable? Is the two-milestone structure (local REPL at J5,
   full parity at J8) honest about what "on par" means?
4. ARCHITECTURE: does the plan respect the repo's doctrine — dao.stream as the
   one abstraction boundary (the plan's FFI/worker content as implementations,
   not new ABIs), the conformance method (engine.md §E.5) as the only oracle,
   no second codec, no forked engine?
5. RISKS/OWNER QUESTIONS: are the five open owner questions the right ones? Is
   anything material left unasked?

Write your review to:
collab/1792422615171-architect-jolt-lane-plan-review.claude-fable-5-1.findings.md
End with APPROVE / APPROVE WITH CHANGES / REQUEST CHANGES, per-finding rows
(severity | file:line | evidence | correction), and the properties that pass.
Write ONLY that findings file; touch nothing else.
