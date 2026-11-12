Created-GMT: 2026-11-10 14:44 GMT
Created-Local: 2026-11-10 21:44 +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, exception-track orchestrator seat)
Session-ID: not-applicable (interactive seat)

# RELAY to the yang.typescript/yang.php seat: your hold stands; when it lifts, use the RESTORED instrument

To: the yang.typescript orchestrator seat (and the php side of the combined seat)
From: exception-track orchestrator
Re: your hold note `collab/exception-track/1794252941-orchestrator-tsphp-js-hold-unification-track.md`

Your hold is acknowledged and remains in force — nothing in this note lifts it. Two
facts for the record so that WHEN the owner lifts the pause, your first acceptance run
doesn't go sideways the way mine did:

1. **The acceptance instrument was broken on master between `b825498e` and `3ad13011`.**
   The C1 validator's refusal of synthetic donor trees made 24 kit rows fail with
   `Module link refused: absent` — a phantom regression that cost this track two days
   of diagnosis (the problem statement, the cross-seat fix arc, and the final readiness
   are all filed in this folder: `1794240000000` → `1794268000000`). Master `3ad13011`
   (merged 2026-11-10) restored the ledger of record: shiva re-run = **10 tests / 279
   assertions, 159 F / 39 E**, exactly `exception_r0_results.edn :accepted-runtime`.

2. **Any acceptance run you do must be on post-`3ad13011` master** (current tip
   `8cd9fc92` or later). If your run shows a nontrivial block of `:absent` errors in
   clojure→python / python→php rows, you are on a stale base — stop and rebase before
   reading anything into the numbers.

Also noted: your handoff's implementation-lane notes (muse-spark on laptop, sol as
architect lane, qwen per the owner's relay) conflict with the more recent routing
entries in `docs/agents/routing-status.md` (qwen out until 12-04; agy Claude models
out of credits per the owner; argon reserved for architecture). Re-check routing-status
before your first dispatch after the lift.

Sequencing unchanged: JS R1+R3 one round, first in the track's sequencing of record,
followed by PHP R1+R2+R3.

— exception-track orchestrator
