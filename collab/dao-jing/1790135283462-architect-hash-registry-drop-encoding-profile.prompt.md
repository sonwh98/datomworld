Created-GMT: 2026-09-22 21:28:03 GMT
Created-Local: 2026-09-23 04:28:03 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0cad7-2f30-7b82-ae67-288922310f75 (resume of your hash-registry plan session)
# Task: architect-hash-registry-drop-encoding-profile — simplify the address to pure multihash
Role: Lead System Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-23 04:28:03 +07 | Status: active | Rationale: same author, resumed; a further owner simplification of the just-committed doc

Still read-only: do not write any file, do not run git add/commit. Your
final report's text IS the deliverable -- the orchestrator will write it
verbatim over docs/design/dao.jing.hash-registry.md and commit it.

## The owner's instruction

The committed doc (docs/design/dao.jing.hash-registry.md, commit
67585e52 -- read it in full, it is the thing you are revising) carries
an encoding-profile identifier in every address (`:jing.print/v1`, with
`:jing.cbor/v1` reserved for later) alongside the algorithm identifier:
`:segment/<encoding-id>+<algorithm-id>-<hex>`.

The owner's instruction: "drop support for :jing.print/v1."

## Why this is correct, not just a simplification for its own sake

The committed doc's own justification for carrying the encoding profile
was: "DaoJing already has a separate, planned transition from the
current order-normalized printer to canonical CBOR... [without the
encoding id] a future encoder change [would] silently change the
interpretation of an address." That justification is a
backward-compatibility concern: it exists to keep OLD addresses
verifiable across a FUTURE encoding change.

This project has already rejected that category of concern once in this
exact document (the SHA-256 legacy-support rejection you already
implemented in 67585e52) and once before that in
docs/design/dao.jing.cbor.md's own "Addressing and clean break" section,
which you should re-read now: it already establishes that when canonical
CBOR lands, it is its OWN clean break -- no legacy reader, no old-address
alias, no graph migration. Every address gets regenerated at that point,
the same way every DaoJing address is being regenerated right now for
the BLAKE3 default. There is therefore nothing an encoding-profile
identifier needs to protect against: the CBOR transition was never going
to preserve old addresses in the first place, so an address does not
need to self-describe which encoder produced it.

Drop the encoding-profile axis entirely. The address becomes pure
multihash-over-a-single-current-encoding:

```text
:segment/<algorithm-id>-<lowercase-hex-digest>
```

With the initial registry:

```text
:segment/blake3-<64 lowercase hex>
:segment/sha256-<64 lowercase hex>
```

## What this changes in the document

- Remove the "Address format" section's encoding-plus-algorithm grammar;
  replace with the flat algorithm-only grammar above.
- Remove "Why encoding remains in the address" entirely -- its
  justification no longer applies now that CBOR is understood to be its
  own future clean break, not a migration to protect addresses across.
- Remove the closed encoding registry, `default-content-encoding`, the
  `{:encoding ...}` option throughout `canonical-bytes`/`content-hash`/
  `segment-key`/`materialize!`, and the `:jing.print/v1`/`:jing.cbor/v1`
  identifiers everywhere they appear.
- `canonical-bytes` continues to mean exactly what it means today (the
  order-normalized print encoder) -- it does not need a name or a
  version exposed in the address, because there is only ever the current
  encoder; a future CBOR change swaps its implementation as a clean
  break, not as a second coexisting profile.
- Update "Sequencing with canonical CBOR": CBOR's eventual landing is
  still a separate future item, but it no longer needs "activation"
  language about a new encoding profile joining a registry -- state
  plainly that CBOR replaces the current encoder outright when it lands,
  as its own clean-break epic (citing dao.jing.cbor.md's existing
  ruling), regenerating every DaoJing address at that point exactly as
  this epic regenerates every address for BLAKE3. This epic's job is
  only to make the ALGORITHM axis multihash-agile; it says nothing
  further about encoding.
- Update every place in the call-site audit, invariants, phased rollout,
  and test obligations that mentioned an encoding profile, encoding
  identifiers containing `-`, the `cbor-v1` test profile, or dispatching
  by "encoding and algorithm" -- dispatch is by algorithm alone now.
- Update the address parser: it now only needs to recognize the
  algorithm identifier and validate digest length/hex, not resolve an
  encoding identifier first.
- Re-check the phased rollout (H0-H3): with the encoding axis gone, does
  the former H3 ("encoding seam and documentation closure") still need
  to exist as its own phase, or does its remaining substance (H/R
  documentation, the primitive-profile/DaoSpace fixes, docstring
  cleanup) fold into H0/H1? Restructure the phases to fit the simpler
  shape rather than leaving a phase built around a deleted concept.
- Keep everything about SHA-256 and BLAKE3 both being permanent,
  first-class, non-legacy algorithm-registry members exactly as
  established in 67585e52 -- this change is only about the encoding
  axis, not a reopening of the algorithm-agility decision.
- Keep the yin.vm H/R disposition and the call-site audit's real findings
  (primitive-profile mislabel, DaoSpace bare schema-hash, the ~18
  validation sites, the 3 address-directed mint sites) -- these are
  independent of the encoding-profile question and should carry over
  with only the `{:encoding ...}` option removed from any code sketch
  that had it.

## Never

Do not reintroduce an encoding-profile identifier, a `:jing.print/v1` or
`:jing.cbor/v1` keyword, or an `{:encoding ...}` option anywhere in the
revised text. Do not reopen the SHA-256/BLAKE3 algorithm-agility decision
-- that part of 67585e52 is correct and stays.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0cad7-2f30-7b82-ae67-288922310f75
Then, immediately after that header, the complete revised document,
starting with a top-level markdown title, through every section this
simplified version needs. No meta-commentary inside the document body;
if you want to tell the orchestrator anything about what changed, put it
after a `---` rule following the document's own final section.
