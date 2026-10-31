Created-GMT: 2026-09-23 18:59:25 GMT
Completed-Local: 2026-09-24 01:59:25 +0700 ICT
Coding-Agent: codex
Session-ID: (provider-generated, see stdout.log)

# Findings: Architect Review of yin.vm.debruijn.linker.md

Role: Lead System Architect
Model: gpt-6-astra

## Verdict: REQUEST CHANGES

## P1 | linker.md:208 | Address verification uses minting default

`(jing/segment-key value)` selects BLAKE3 regardless of the requested
address's algorithm. A valid SHA-256 address therefore fails the step-2
comparison, contradicting lines 230-233 of the new doc.
dao.jing:406 confirms this behavior.

**Correction:** Replace with `(not (jing/segment-matches? address value))`.
Require successful fetch coverage for both registered address algorithms
in the test criteria.

## P2 | linker.md:477 | Completion criterion overstates handle independence

The criterion implies the linker itself diagnoses peer content mismatches.
In practice, a DHT peer's mismatched payload is discarded inside the handle
(dao.jing.dht:218 make-get); if no valid peer answers, the handle returns
absence. The linker never receives that payload.

**Correction:** Preserve the original requirement (wrong peer content is
rejected before load on both topologies), but separately test the linker's
own step-2 check using: (a) corrupted DHT-local content and (b) a corrupt
client response. Permit `:absent` for peer content filtered by the DHT.

## Confirmed Passing

- BLAKE3 correction approved in substance: addresses use the selected
  algorithm over UTF-8 bytes of the order-normalized print. H and R remain
  explicitly SHA-256 and independent of storage encoding. No remaining
  SHA-256 reference incorrectly describes segment-key.
- Extraction is substantially faithful: shared fetch function, refusal rules,
  closure checks, descriptor binding, migration risk, and dependency
  boundaries are preserved.
- Address vs H/R: both checks remain mandatory and protect different
  preimages and claims.
- Same-root pairing: root-associated datoms, trusted fallback, local
  re-lowering for verification, comparison of both hashes, and
  :pairing-mismatch are accurately preserved.

No files edited.
