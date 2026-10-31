Completed-GMT: 2026-09-21 19:21:19 GMT  
Completed-Local: 2026-09-22 02:21:19 +07  
Coding-Agent: codex  
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a

SIGN-OFF: READY to commit and begin B0.

D8-D11 dispositions:

- D8 ADOPTED: B6 is committed closed-image fetch-by-hash over `dao.stream`,
  with request, response, verification, and cross-runtime execution tests.
- D9 ADOPTED: B1’s single `image-hash` computes H; Jing addresses are storage
  addresses only.
- D10 ADOPTED: scalar classes are derived from hashed `:const` tags; unsupported
  receiver classes refuse before execution.
- D11 ADOPTED: pre-B7 sharing is safe only for closed images or identically
  bound free names; unresolved names are qualified runtime errors.

Folded review findings:

- Linker pull-path gap: ADOPTED via committed B6 and later B7.
- Hash-verification ambiguity: ADOPTED via the pinned `image-hash` function.
- Free-name boundary: ADOPTED explicitly in §1 and §7.2.
- Scalar-class persistence concern: ADOPTED as derive-from-tags.
- Continuation compliance scope: ADOPTED; cross-host UCF remains deferred.
- Environment-leak citation and ownership: ADOPTED with source locations and a
  deferred named-VM fix design.
- B2 lift equation: ADOPTED, distinguishing original side-table names from
  synthesized alpha-equivalent names.
- Validator agreement: ADOPTED with bidirectional B1/B2 validation tests.
- Naming and benchmark wording: ADOPTED.
- Compliance-table corrections: ADOPTED, distinguishing B0-B5 transfer from
  committed B6 request/response.
- All other deepseek/qwen P3 findings were adopted as wording or phase-box
  corrections.

Sections changed:

- §1 compliance table, invariant-I boundary, closed-image/free-name rules.
- §2 scalar derivation and `image-hash` identity.
- §6 opening plus committed B6 and B7 phase boxes.
- §7 test matrix and §7.2 linker topology.
- §8 D8-D11 decisions and deferred mechanics.

Still deferred: B6 request/response schema and contract stamps, B7 dependency
manifest mechanics, retry/timeout/absence event vocabulary, and the separate
named-VM environment-leak fix design. None blocks B0.
