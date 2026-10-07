I’ll fold the six P2 clarifications, correct the remaining normalizer and prior-art wording, and close the two owner decisions while keeping the linker transport-agnostic.
Completed-GMT: 2026-09-21 18:43:46 GMT  
Completed-Local: 2026-09-22 01:43:46 +07  
Coding-Agent: codex  
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a

## Disposition table

```text
Finding   Status       Concrete disposition
P2-1      ACCEPTED     B2 reconstructs scope from the image only: body ranges,
                       containing closure, innermost-first resolver stack,
                       explicit frame-order conversion, and layout validation.
P2-2      ACCEPTED     B1 copies private framing helpers and owns its scalar tag
                       table; unpaired surrogates diagnose unsupported-value.
P2-3      ACCEPTED     Host classification is explicit; shared byte fixtures
                       cover common values, while long/double, ratio, and char
                       tests are JVM/Dart scoped.
P2-4      ACCEPTED     encode-db/decode-db use a DAG keyed by
                       [source-eid name-stack]; B0 adds a doubling-chain test.
P2-5      ACCEPTED     The image is executable and derived, not invertible.
                       The lossless DAG is equal up to consistent entity-id
                       renaming; every-node tail flags are preserved.
P2-6      ACCEPTED     B1 hashes the canonical positional instruction vector,
                       with pc refs and provenance in a side table.
P3-1      ACCEPTED     Unverified Unison pipeline/component claims are marked
                       UNVERIFIED.
P3-2      ACCEPTED     Memo and occurrence expansion are stated as properties
                       inherited from linearize/lower.
P3-3      ACCEPTED     Exact spelling uses one canonical side table.

P1-1      ACCEPTED     Independent executable scalar encoding prevents
                       projection canonicalization from entering image hashes.
P1-3      ACCEPTED     Cross-program park/resume parity is restricted until
                       the named environment leak is fixed.
P1-4      ACCEPTED     Closure, continuation, and parked-value normalization
                       is now explicit.
P2-7      ACCEPTED     Ex-data values are recursively normalized before compare.
P2-10     ACCEPTED     Any source-level memo uses the full parameter-name stack.
```

The adapter architecture remains approved: B2 derives from
`yin.vm.linearize/lower`, preserving order and tail flags by construction, and
rewrites only lexical operands.

No finding was rejected. The owner rulings were applied: Unison interoperability
is not a goal, the linker boundary is transport-neutral `dao.stream`, and
lambda lifting or ANF is optional upstream AST-to-AST work.

## Sections changed

- §1: executable-versus-lossless artifact distinction, DAG identity,
  normalization, and invertibility.
- §2: private helper ownership, host scalar classification, side-table choice,
  and canonical image hashing.
- §3: image-only scope reconstruction and inherited linearizer traversal.
- §6 B0, B1, and B2 criteria.
- §7.1: Unison qualification and upstream lambda-lifting note.
- §7.2: transport-neutral linker boundary.
- §8: revised owner-decision list.

## Remaining owner decisions ranked by B0 impact

1. B0 blocker: which stable identity, if any, replaces emitter-local tempids.
2. B0 blocker: exact result, error, closure, continuation, and stream
   normalizer.
3. B0 blocker: whether non-`:yin` attributes remain in the diagnostic side
   area or are outside the input domain.
4. B1: executable scalar-domain and `:yin.debruijn.code/*` descriptor approval.
5. B5: named VM environment-leak fix or continued parity restriction.
6. B6: authoritative ledger and trust/provenance, SCC identity, and request
   retry, timeout, and permanent-absence policy. The transport boundary is
   already fixed as `dao.stream`.
