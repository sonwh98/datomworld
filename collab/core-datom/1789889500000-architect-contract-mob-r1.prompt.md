Created-GMT: 2026-09-20 07:07:00 GMT
Created-Local: 2026-09-20 14:07:00 +07 (Indochina Time)
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your lease/waitset consensus thread)
# Task: mob ruling — three contract-owner questions

The contract owner has delegated three open §6 questions to a mob of the
two architects (you and fable-5-1, who will respond to your positions in
the next round). Read the exact context:

- `docs/design/dao.lease.md:150-160` — The pass §4, the unknown-evidence
  silence rule (Q1)
- `docs/design/dao.lease.md:198-206` — The holder, the bound and cap rules
  (Q2)
- `docs/design/dao.lease.implementation-plan.md:734-739` — the §6 rows
  recording both questions
- `docs/design/dao.stream.waitset.implementation-plan.md:545-551` — the
  latency note (Q3)
- `src/cljc/dao/lease.cljc:1820-1830` — the holder's cap implementation

The three questions:

**Q1 — unknown-evidence silence tolerance.** After a gap, a lease is
`unknown` and due for `:silence` once a FULL duration passes since the
resumed reading — no tolerance added. A continuously-observed lease gets
duration + tolerance. So a gapped lease loses its tolerance grace exactly
when evidence is least reliable. Rule: keep the stricter literal rule, or
extend duration + tolerance across gaps?

**Q2 — holder cap-basis flight time.** The judge measures the cap from
ITS tenure start (grant reading); the holder measures its bound from ITS
observation of the grant — later by the grant's flight time. So the
holder can act past the judge's `:cap` reclaim by that flight window, and
tolerance does not cover the cap. Rule: accept the flight-time gap
(fencing is the resource's job), or require the holder to discount
estimated flight time from its cap?

**Q3 — served-endpoint latency.** An idle served endpoint now answers its
first request up to the 200 ms backoff ceiling (was a fixed 25 ms tick),
because the serving inbound path has no nudge caller. Rule: accept as
composition policy, lower the ceiling for served compositions, or add a
serving-path nudge?

For each question give: your RULING (one of the offered options or a
better-specified alternative), the REASONING grounded in the contracts'
text and the design's stated bias (err toward the holder; possession not
truth), and — where the ruling changes contract text — the EXACT amended
sentence. Remember the design bias cuts both ways: tolerance extends
tenure (favors holders), while a gap is exactly when evidence is least
trustworthy (favors strictness). Weigh honestly.

Read-only. End with a compact summary block: `Q1: <ruling> | Q2:
<ruling> | Q3: <ruling>`
