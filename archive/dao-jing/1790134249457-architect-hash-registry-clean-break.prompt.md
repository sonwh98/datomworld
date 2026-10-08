Created-GMT: 2026-09-22 21:10:49 GMT
Created-Local: 2026-09-23 04:10:49 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0cad7-2f30-7b82-ae67-288922310f75 (resume of your hash-registry plan session)
# Task: architect-hash-registry-clean-break — drop all legacy-SHA-256 support, make BLAKE3 a clean break
Role: Lead System Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-23 04:10:49 +07 | Status: active | Rationale: same author, resumed after the owner rejected the legacy-support premise of the committed doc

Still read-only: do not write any file, do not run git add/commit. Your
final report's text IS the deliverable -- the orchestrator will write it
verbatim over docs/design/dao.jing.hash-registry.md and commit it, so it
must be complete, self-contained markdown from the title down.

## The owner's correction

The owner read the committed doc
(docs/design/dao.jing.hash-registry.md, commit 5de9e7b1 -- read it in
full, it is the thing you are revising) and said: "there's no need to
support legacy."

This project has an established, explicit rule, already applied once to
this exact subsystem: datom.world is a dev-only repository with no
deployed dao.jing stores, no published index manifests, no externally
held addresses, and no legacy systems depending on it. The owner's own
words, recorded verbatim in this project's memory: "you do not have to
worry about maintaining backwards compatibility. there are no legacy
systems to maintain compatibility with." This already shaped
docs/design/dao.jing.cbor.md's own "Addressing and clean break" section
-- read that section now, in full, as your precedent: no legacy reader,
no old-address alias, no graph migration, for the CBOR encoding change.
The hash-algorithm change gets the identical treatment.

## What this means concretely for your plan

The current committed doc's entire architecture is built around
"SHA-256 remains supported... permanently" and "old data remains under
its original identity... no bulk migration required." That premise is
now void. Rewrite the plan as a clean break:

- BLAKE3 becomes THE hash algorithm. There is no dual-algorithm runtime
  state, no "current default vs legacy address" distinction to carry
  forever, no `segment-matches?` needing to dispatch between two live
  algorithms in production.
- Drop entirely: the "legacy SHA-256 spelling remains valid permanently"
  rule, the two-phase mixed-address-storage phase (your former H2), the
  DaoSpace checkpoint "legacy candidate vs new candidate" split, the
  "old SHA file store reopens without rewrite" requirement, the
  "provider replacement does not change addresses" backward-compat
  framing, and any test obligation whose only purpose is proving old and
  new coexist.
- yin.vm's H/R (image-hash, register-hash) still stay pinned to explicit
  SHA-256 -- but say plainly WHY this is not a legacy-compat exception:
  it is a VM contract-freeze concern (golden test values, a format's own
  contract-version process), unrelated to backward compatibility for
  deployed content. Don't let the clean-break framing accidentally sweep
  H/R into "just use BLAKE3 everywhere now" -- the reason to keep them
  SHA-256 has nothing to do with legacy data and everything to do with
  not touching a frozen contract outside its own versioning process.
- Decide, and state plainly, whether the encoding-profile-plus-algorithm
  address shape (`:segment/<encoding-id>+<algorithm-id>-<hex>`) is still
  worth keeping now that there is no legacy SHA-256 spelling to
  distinguish from. Consider: the owner's original ask was for
  multihash-style ability to support multiple algorithms in the future
  (not necessarily SHA-256 specifically, not necessarily forever) --
  that general self-describing-address capability may still be worth
  keeping for forward agility (a future third algorithm, or the CBOR
  encoding change) even with zero legacy burden today. Make your own
  call: either keep the self-describing shape (justify why it earns its
  complexity even as a clean break) or simplify further to a flatter
  address if you conclude the self-describing shape was ONLY earning
  its keep by solving the legacy problem you're now told doesn't exist.
  State your reasoning either way -- don't just keep the old shape by
  inertia.
- Re-derive the phased rollout from scratch given the new premise. A
  clean break likely needs far fewer phases than the six (H0-H5) in the
  committed doc -- don't just delete the legacy-specific bullets from
  the existing phases, restructure the phases to fit a clean-break
  epic's actual shape (implement, verify on all three hosts, flip,
  document -- or fewer).
- Keep whatever of your original call-site audit remains accurate: the
  five direct `jing/sha256` consumers, the `primitive-profile` mislabel
  bug (still real -- a hardcoded "sha256-" label is wrong regardless of
  legacy policy), the bare DaoSpace `:schema-hash` (still worth a
  self-describing address, just without a "legacy candidate" fallback
  path since there's nothing to fall back to), the host library choices
  and their maintenance risk notes, and the CBOR-sequencing discussion
  (independent of the legacy question -- CBOR is a real future item on
  its own timeline, not a legacy concern).

## Never

Do not reintroduce any legacy-support machinery. Do not hedge with "keep
SHA-256 support just in case" language anywhere in the revised text.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0cad7-2f30-7b82-ae67-288922310f75
Then, immediately after that header, the complete revised document,
starting with a top-level markdown title, through every section this
clean-break version needs. No meta-commentary inside the document body;
if you want to tell the orchestrator anything about what you changed,
put it after a `---` rule following the document's own final section.
