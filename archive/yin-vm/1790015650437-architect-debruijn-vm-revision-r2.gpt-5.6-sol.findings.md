Completed-GMT: 2026-09-21 18:37:25 GMT  
Completed-Local: 2026-09-22 01:37:25 +07  
Coding-Agent: codex  
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a

## P2-F ruling

Use `yin.vm.linearize/lower` as the ordering and control-flow authority, then
adapt its image: rewrite `:var` operands using `resolve-name` and replace
`:closure` parameter vectors with arities. This guarantees parity for ordering,
labels, and front-end tail flags without modifying `yin.vm.linearize` or
`:yin.code/*`. B2 now requires a structural opcode comparison. The separate
B0 lossless tree encoding remains necessary for invertibility and does not
require decompiling the linear image.

## Disposition table

```text
Finding   Disposition   Correction
P1-A      ACCEPTED      Separate executable scalar encoding; exact bytes,
                       distinct long/double/NFC spellings; unsupported values
                       diagnose; image-hash collision tests added.
P1-B      ACCEPTED      Memo context is [eid, full parameter-name stack,
                       tail-context]; arities remain validation data only.
P1-C      ACCEPTED      encode-db/decode-db now live in B0's new
                       debruijn_encoding namespace and operate on a
                       tree-shaped lossless record schema.
P2-A      ACCEPTED      Input is restricted to one fact per entity/attribute
                       and exactly one root; other named-valid batches diagnose.
P2-B      ACCEPTED      Only public resolve-name is reused; projection builders
                       and canonicalizing helpers are excluded.
P2-C      ACCEPTED      Closures normalize to type plus arity; continuations
                       and parked values normalize by type only.
P2-D      ACCEPTED      Cross-program park/resume parity is restricted to
                       initial-environment/store free names until the named
                       environment leak is fixed.
P2-E      ACCEPTED      Provenance is diagnostic metadata outside image hash.
P2-F      ACCEPTED      B2 adapts linearize/lower output and adds structural
                       opcode parity tests.
P2-G      ACCEPTED      The linker consumes lossless images; fingerprint is
                       only a lookup index.

P1-1      ACCEPTED      Exact spelling is preserved in the executable artifact;
                       projection canonicalization is never reused for hashing.
P1-3      ACCEPTED      Fixed initial free-env is explicit; leak is excluded
                       or reported as a named-path refusal.
P1-4      ACCEPTED      B0 defines a concrete normalized comparison, including
                       closure and continuation treatment.
P2-7      ACCEPTED      Error equality now includes message plus normalized
                       ex-data, with closure and parked-value rules.
P2-10     ACCEPTED      Lexical memo context is the full parameter-name stack,
                       not frame arities.
P3        ACCEPTED      Unison claims are limited to the verified list;
                       remaining runtime facts are explicitly UNVERIFIED.
```

No finding was rejected outright. P2-F was resolved by choosing the adapter
architecture rather than duplicating the named traversal.

## Sections changed

- §1: lossless tree artifact, normalization, and equivalence.
- §2: executable scalar encoding and image identity.
- §3: resolver reuse, memo context, and scope.
- §6 B0, B1, B2, B4, and B5 phase contracts.
- §7 test matrix and §7.1 prior-art wording.
- §7.2 linker input and fingerprint use.
- §8 owner decisions.

## Remaining owner decisions

1. B0 blocker: tempid normalization and stable source identity.
2. B0 blocker: exact comparison normalizer.
3. B0 blocker: treatment of non-`:yin` attributes.
4. B1: executable scalar domain and code descriptor approval.
5. B5: fix the named VM environment leak or maintain the stated fixture
   restriction.
6. B6: authority ledger, SCC identity, request-stream failure policy, and
   whether hash linking is commissioned.
