Created-GMT: 2026-09-19 18:33:00 GMT
Created-Local: 2026-09-20 02:33:00 +07 (Indochina Time)
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your consensus R2 thread)
# Task: close the last open consensus item — Dispute B's probe compromise

The consensus round you ran with fable-5-1 closed with one item open: its
Dispute B remedy. Your item 9 required an explicit observation handoff even
for reader-only adoption; fable conceded the defect and proposed a named
**probe-entry contract** as that handoff. Its full proposal:

- Reads are non-destructive (`dao.stream.md:548`). A `:next` entry whose
  resolver supplies the consumer's own current cursor and whose `:advance`
  returns the store unchanged is a pure readiness probe: it adds no second
  cursor, commits nothing, and looks at exactly the position the compound
  step will read next.
- The handoff rule is explicit: the woken `:value` and `:cursor` are
  advisory and discarded; the consumer's own compound step
  (`forward-step`/`serve-once!`) remains the sole authority and re-reads
  from its own cursor with its own commit logic.
- An eviction between probe and step produces a `gap` the consumer's step
  already handles — no new hazard; the cost is one redundant `next` per
  wake.
- Two constraints the plan must state: a probe entry is re-parked by its
  owner after each step, and probe entries must not share a cursor-ref
  with advancing entries (an identity `:advance` would wake co-waiters on
  the same value).
- Writer-side `:put` migration for forwarders and serve-once is removed
  outright. Where a consumer adopts neither the probe nor a future
  result-consumption protocol, the compound step stays under host cadence.

Your choice is between accepting this as the item-9 remedy or rejecting it
in favor of the pre-agreed fallback (all compound steps stay under host
cadence; no probe). Reply with your reasoning and end with exactly one
line:
`DISPUTE-B: <accepted | rejected> — <one sentence>`
