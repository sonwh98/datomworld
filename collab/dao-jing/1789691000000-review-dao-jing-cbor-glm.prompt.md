Created-GMT: 2026-09-18T02:20:00Z
Created-Local: 2026-09-18 09:20:00 +0700 (Asia/Ho_Chi_Minh)

Session-ID: 9467305a-6626-4e5f-87e8-bd1ea01c0305

# Task: Independent cross-family review of docs/design/dao.jing.cbor.md

Role: Adversarial Review

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-18 09:20:00 +07 | Status: active | Rationale: independent cross-family review — a Claude-family Architect authored and already reviewed this document; this is a fresh, unrelated-family pass to catch what shared context might miss

## Context (this is a fresh session with no prior history — read everything below before starting)

`docs/design/dao.jing.cbor.md` (507 lines) is an implementation plan,
not yet built: it replaces `dao.jing`'s current handwritten EDN/hash
encoding with canonical CBOR bytes as the storage format, across every
backend (memory, file, PostgreSQL, S3, remote/DHT). Jing is
`datom.world`'s content-addressed storage substrate — see
`docs/design/dao.jing.md` for the system this plan amends, and
`docs/design/datom.world.md` for the project's foundational axioms and
six non-negotiable invariants (no hidden global state, no implicit
control flow, no callbacks, no shared mutable state, no layer collapsing,
no assumed graphs — read this file's "Foundational Axioms" and
"Non-Negotiable Invariants" sections in full).

This plan had never been reviewed until today. A Claude-family Architect
(claude-fable-5-1) performed a first read-only architecture review and
found no blocking defects, but four findings, two of which were real
defects in the document (not just claims made about it elsewhere):

1. **Medium severity**: the Objective section states backends "need no
   knowledge of CBOR," but the *Memory and files* section specifies the
   file backend's frame format as a CBOR `[digest payload-bytes]` record
   that something must parse on replay — the original text never said
   who. This is a real internal contradiction.
2. (Corrected a claim made in a separate conversation about a different
   document, not a `dao.jing.cbor.md` defect — not relevant to your review.)
3. (Confirmed a caveat already present in the document — not a defect.)
4. **Informational**: the plan's only change outside the `dao.jing*`
   namespaces — pushing portable `=`/`hash`/`compare` into
   `dao.space.index`'s datom comparators and `dao.space.query`'s
   comparison builtins (see the *Numeric identity* section) — is
   architecturally sound but is the widest blast radius in the plan and
   was flagged as needing separate sign-off from whoever owns
   `dao.space`, which the original text didn't call out.

The orchestrator integrated corrections for findings 1 and 4 directly
into the document (already committed as `cb09b53`, current working tree):

- Finding 1: added a paragraph to the Objective section clarifying that
  the "no CBOR knowledge" promise is about the *pluggable,
  third-party-implementable* backend layer (PostgreSQL, S3, the
  memory/remote/DHT byte-store implementations), and explicitly named
  `dao.jing.file` as Jing's own built-in durability code that legitimately
  owns a thin CBOR framing envelope around that boundary — not a
  "backend" in the sense the objective was restricting. The *Memory and
  files* section was also edited to name `dao.jing.file` as the explicit
  owner of parsing/constructing the two-element frame.
- Finding 4: added a paragraph in *Numeric identity* naming the
  `dao.space.index`/`query` change as a real, intentional boundary
  widening that needs explicit sign-off from `dao.space`'s owners before
  implementation starts on that section.

The same Claude-family Architect who found the original issues has
already been asked to confirm its own corrections (a resumed session,
not yet returned as of this brief). You are a **separate, independent
pass** — don't just check whether the two named corrections read
sensibly; treat this as a fresh adversarial review of the whole document,
since a same-family follow-up reviewing its own prior finding is exactly
the kind of check that benefits most from an independent second opinion.

## Task

1. Read `docs/design/dao.jing.cbor.md` in full.
2. Independently judge whether the finding-1 correction actually resolves
   the contradiction, or whether "Jing's own built-in durability code" as
   a category distinct from "backend" creates a new inconsistency
   elsewhere in the document (the doc uses "backend" extensively — memory
   backend, file backend, PostgreSQL/S3 backends — check whether carving
   `dao.jing.file` out of that category in just the Objective section
   reads consistently against every other place "backend" is used).
3. Independently judge the finding-4 addition's placement, accuracy, and
   completeness.
4. Do a full adversarial pass of your own, not limited to the two
   corrections: check the numeric identity design (§Numeric identity),
   the encoding contract's completeness and internal consistency, the
   backend migration plan's clean-break/rollback story, and whether any
   of the six non-negotiable invariants are at risk anywhere in this
   document that the prior review might have missed. State plainly if
   you find nothing beyond what's already named — a clean independent
   pass is itself a useful result, not a failure to find something.

## Deliverable

A findings list, most severe first, and an explicit verdict: ready for
owner sign-off, or not (with what must change first). Do not edit any
file.
