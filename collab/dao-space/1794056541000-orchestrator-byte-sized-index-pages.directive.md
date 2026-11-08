Created-GMT: 2026-11-07 12:09:01 GMT
Created-Local: 2026-11-07 19:09:01 +07
Coding-Agent: interactive (ZCode / glm-5.3-flash, master-green orchestrator seat)
Session-ID: not-applicable (interactive seat)

# OWNER DIRECTIVE: index pages are cut by encoded byte size, not by datom count

## The owner's words (2026-11-07, in conversation during the repair-slot incident triage)

> "it seems to me this problem should be reframed in terms of byte size not in
> terms of datom count"

(Owner instruction relayed verbatim; the design below is this seat's working-out
of it, to be confirmed by an Architect round before implementation.)

## Problem this solves

`dao.space.index/from-sequential` cuts index pages by a fixed key count
(`:branching-factor`, generic default 512). The 64 KiB DHT message bound is a
BYTE bound on segment+envelope. A count-based cut cannot know whether 512 keys
encode to 3 KiB (small scalar datoms — safe) or 70 KiB (REPL code-datom pages —
refused at the sender; the nightly 20261106-0944 ack collapse). Fix 5
(`4f772d61`, `:publish-opts {:branching-factor 64}` in yin/repl/dht.cljc) is a
per-store constant workaround; its 8× page amplification spawned the repair-slot
economics incident (dart ticker-idle regression, currently under an engineer
sweep). A count dial cannot converge across lanes because the real constraint
was never the count.

## The design (working-out; Architect adjudication required)

1. **Cut by encoded bytes.** `from-sequential` accumulates sorted datoms into a
   leaf until the next datom would push the leaf's canonical CBOR size past a
   byte budget, then cuts. Key count becomes emergent (64 for code-heavy trees,
   512+ for scalar ones). The manifest's `:branching-factor` field becomes
   descriptive (or is retired per the manifest schema's evolution rules —
   schema change is a proposal to the work log, not a unilateral edit).
2. **Budget derivation.** One authoritative constant: a page budget as a
   fraction of `dao.jing.dht`'s `max-message-bytes` (65536), headroom reserved
   for the envelope (chunk framing, cookie/pad fields). Proposed starting point:
   budget = 48 KiB (≈75% of the bound), confirmed by measurement per the
   existing "S3 measures real covered-index nodes" plan in dao.jing.dht.md:805.
3. **Branch pages** use the same byte budget over child-address entries.
4. **Deprecate count-based cuts** once byte-cut lands: fix 5's
   `:branching-factor 64` override and the generic 512 become defaults only for
   tests that pin tree *shape*, not for safety.
5. **Consequences to preserve/verify:**
   - determinism: the cut must remain a pure function of the sorted datom
     stream (canonical CBOR sizes are deterministic; sorting is stable) —
     addresses stay content-functions, so cache-sharing across rounds and the
     manifest-address provenance property survive.
   - the four `*_test` pins on page shape (dao.space index tests) get updated
     per the measure-then-pin rule, never reflexively.
   - the repair-slot tuning (current incident) becomes a cadence question only:
     byte-cut pages make per-page message size constant, but page COUNT per
     round still scales with datom volume, so `max-repairing` economics remain
     — the sweep's data still informs the schedule.

## Sequencing (orchestrator's proposal)

- NOW: finish the repair-slot sweep + branch blockers on nightly-reds-fix as
  dispatched (the merge needs a working 64-cut configuration regardless).
- AFTER master-green: an Architect round adjudicates this design; an
  implementation round follows on its own slice with its own pins. Do NOT
  fold it into nightly-reds-fix.

— the master-green orchestrator seat
