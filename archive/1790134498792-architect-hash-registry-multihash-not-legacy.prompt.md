Created-GMT: 2026-09-22 21:14:58 GMT
Created-Local: 2026-09-23 04:14:58 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0cad7-2f30-7b82-ae67-288922310f75 (resume of your hash-registry plan session)
# Task: architect-hash-registry-multihash-not-legacy — correct the previous instruction
Role: Lead System Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-23 04:14:58 +07 | Status: active | Rationale: same author, resumed; the orchestrator's PRIOR resume instruction to you (architect-hash-registry-clean-break) was itself wrong and is being corrected before you act on it

## Ignore the prior "clean-break" instruction if you have already started it

A previous message in this thread asked you to drop SHA-256 entirely and
make BLAKE3 the sole algorithm. That was a misreading of the owner's
actual instruction and is WRONG. Disregard it. The owner's exact words,
verbatim, across two messages:

1. "there's no need to support legacy"
2. (clarifying the first, after seeing the orchestrator misapply it):
   "there's no need to support legacy but support multihash like IPFS.
   sha-256 is supported but it is not a legacy support"

## The corrected instruction

SHA-256 stays as a real, permanent, first-class member of the algorithm
registry alongside BLAKE3 -- exactly the way IPFS multihash supports
several algorithms as equal citizens, not the way a compatibility shim
keeps an old format alive for deployed data. BLAKE3 is the DEFAULT
minting algorithm; SHA-256 remains fully supported and mintable on
request, forever, as a first-class registry entry -- not as a "legacy
spelling" tolerated for content that already exists.

What DOES go away, per this project's own established rule (already
applied identically to docs/design/dao.jing.cbor.md's "Addressing and
clean break" section -- read that section now as your precedent: no
legacy reader, no old-address alias, no graph migration): this repo is
dev-only with no deployed dao.jing stores, no published index manifests,
no externally held addresses. So drop everything in the committed doc
(docs/design/dao.jing.hash-registry.md, commit 5de9e7b1 -- read it in
full, it is the thing you are revising) that exists ONLY to preserve or
migrate PAST content:

- the "legacy SHA-256 spelling remains valid permanently because old
  data depends on it" framing -- reframe as "the plain `sha256-<hex>`
  spelling is simply this registry's spelling for the print-v1/sha256
  pair, no different in kind from any other combination's spelling";
- the two-phase "mixed-address storage" migration phase (former H2)
  built around proving old-vs-new coexistence -- there is no "old" to
  coexist with; if BLAKE3 becomes the default on day one of this
  registry's existence, every DaoJing store starts empty and there is no
  transition to prove;
- "an old SHA file store reopens without rewrite" and similar
  already-deployed-data framing;
- the DaoSpace checkpoint "legacy candidate vs new candidate" split --
  there are no existing checkpoint candidates to be legacy;
- any test obligation whose only purpose is proving migration
  compatibility rather than proving the registry's actual multi-algorithm
  correctness.

What STAYS, because it is the multihash feature itself, not a compat
shim:

- the closed algorithm registry with (at minimum) `:sha256` and `:blake3`
  as genuine, equally supported, permanent members;
- explicit non-default minting (`(segment-key value {:algorithm :sha256})`)
  as a normal, intended, permanently supported operation -- not a
  fallback path, a first-class one;
- address parsing/verification that dispatches by whichever algorithm
  the address actually carries, because a multihash-style store
  genuinely may hold content minted under either algorithm at the same
  time by ordinary, ongoing use (two callers choosing differently), not
  because of a migration in progress;
- the encoding-profile question (print-v1 vs future cbor-v1) is a
  SEPARATE axis from algorithm choice -- decide independently whether it
  still needs the same self-describing treatment; the CBOR item is a
  real future item on its own timeline, not a legacy concern, so treat
  its sequencing on its own merits rather than conflating it with the
  algorithm-legacy question you just resolved.

yin.vm's H/R (image-hash, register-hash) still stay pinned to explicit
SHA-256, but for a reason that has nothing to do with either legacy
compatibility OR multihash agility: it is a VM contract-freeze concern
(golden test values, a format's own contract-version process). State
this distinction plainly so a reader does not confuse "H/R happen to use
SHA-256" with "H/R are a legacy exception."

Re-derive the phased rollout given this corrected premise: you are
building permanent multi-algorithm support with a default of BLAKE3 and
no migration story (since nothing is deployed), not a legacy-compat
system and not a single-algorithm clean break either. Size the phases to
that actual shape.

## Never

Do not drop SHA-256 from the registry. Do not describe SHA-256 support
as legacy, deprecated, or transitional anywhere in the revised text.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0cad7-2f30-7b82-ae67-288922310f75
Then, immediately after that header, the complete revised document,
starting with a top-level markdown title, through every section this
corrected version needs. No meta-commentary inside the document body; if
you want to tell the orchestrator anything about what changed, put it
after a `---` rule following the document's own final section.
