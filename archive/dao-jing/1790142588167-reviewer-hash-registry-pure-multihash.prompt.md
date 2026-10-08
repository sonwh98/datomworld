Created-GMT: 2026-09-23 05:50:00 GMT
Created-Local: 2026-09-23 12:50:00 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 4de80508-4e9d-41e6-a131-409d06ee6520

# Task: reviewer-hash-registry-pure-multihash — independent review of the final pure-multihash design doc

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-23 12:50:00 +07 | Status: active | Rationale: architectural design review of pure-multihash content addressing (docs/design/dao.jing.hash-registry.md at b7a765eb)

Work in /Users/sto/workspace/datomworld (your launch directory; branch master). READ-ONLY, STATIC REVIEW. Do not edit any file, do not run `git add`/`commit`/`push`. Do not run the test suite -- there is no implementation yet.

## Context

The content addressing design in `docs/design/dao.jing.hash-registry.md` went through three committed revisions:
1. `5de9e7b1` (gpt-5.6-sol): framed SHA-256 as legacy with two-phase migration and `:segment/<encoding-id>+<algorithm-id>-<hex>` addresses. Reviewed by glm-5.3 (two P2s, six P3s).
2. `67585e52` (gpt-5.6-sol): the owner rejected the legacy framing ("no need to support legacy", "support multihash like IPFS. sha-256 is supported but it is not a legacy support"). Reworked so BLAKE3 and SHA-256 are permanent, first-class algorithms; migration phases dropped. Reviewed by glm-5.3 (READY, no P1, no P2, three P3s).
3. `b7a765eb` (interactive): the owner asked what `:jing.print/v1` (the encoding-profile identifier) was, then instructed dropping it: the encoding-profile axis existed only to keep addresses verifiable across a future CBOR encoder change, which is itself the same category of backward-compatibility concern already rejected (a future CBOR landing is its own clean break per `docs/design/dao.jing.cbor.md`, regenerating every address anyway). Simplified to pure multihash (`:segment/<algorithm-id>-<hex>`, just `blake3` and `sha256`), rollout collapsed from three phases to two (H0-H2).

This third revision (`b7a765eb`) has NOT had an independent review pass yet. That is your task.

## Review Objectives

Perform an adversarial architectural review of `docs/design/dao.jing.hash-registry.md` on master:

1. **Address Grammar & Parsing**:
   - Check the `:segment/<algorithm-id>-<lowercase-hex-digest>` format (specifically `:segment/blake3-<64 hex>` and `:segment/sha256-<64 hex>`).
   - Does dropping the encoding-profile prefix leave any ambiguities in `parse-segment-address`, `segment-address?`, `segment-matches?`, or `segment-key`?
   - Verify invariant coverage: lowercase hex, length enforcement, registered algorithms only, fail-closed on unknown algorithm or malformed format.

2. **Encoding Clean Break & CBOR Sequencing**:
   - Verify the relationship between `canonical-bytes` and `content-hash`/`segment-key`.
   - Does the document clearly state that `canonical-bytes` precedes algorithm selection?
   - Is the clean-break boundary with canonical CBOR (`docs/design/dao.jing.cbor.md`) completely consistent? When CBOR lands, does regenerating addresses from CBOR bytes cleanly avoid any need for encoding profiles in the address?

3. **Multi-Algorithm Operations & Copy Paths**:
   - Verify that address verification is address-directed (`segment-matches?`) and never follows ambient defaults.
   - Verify that all copy paths (`dao.jing.dht/make-get`, `dao.data.btree.storage/hydrate!`, etc.) preserve the source address's algorithm (`{:algorithm (segment-algorithm address)}`).
   - Are there any copy paths, cache paths, or verification paths in the codebase that the audit missed?

4. **yin.vm H and R Preservation**:
   - Verify that VM format hashes (H in stack VM, R in register VM) remain explicitly pinned to SHA-256 and are decoupled from DaoJing's `default-hash-algorithm`.
   - Check if any VM contract is accidentally coupled to the new BLAKE3 default.

5. **Phased Rollout Completeness (H0-H2)**:
   - Check H0 (contract and evidence), H1 (registry and whole-system cutover), and H2 (hardening).
   - Are the completion criteria boolean-checkable and complete?
   - Are the host library provider risks and gates (JVM stale Maven library, Dart single-release package, Node `@noble/hashes`) adequately addressed?

6. **Call-Site Audit Accuracy**:
   - Check the cited line numbers and call sites against actual source in `src/cljc/dao/jing.cljc`, `src/cljc/dao/data/btree/storage.cljc`, `src/cljc/dao/space/index.cljc`, `src/cljc/yin/vm/*.cljc`, etc. Are any sites misrepresented?

## Deliverable

Begin your final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 4de80508-4e9d-41e6-a131-409d06ee6520

Report actionable findings as:
P0-P3 | file:line | evidence | concrete fix

State "No actionable findings" when appropriate.
Explicitly state whether the design is READY to proceed to H0 implementation.
