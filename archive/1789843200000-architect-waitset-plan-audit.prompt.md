Created-GMT: 2026-09-19 18:42:00 GMT
Created-Local: 2026-09-20 02:42:00 +07 (Indochina Time)
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your consensus R2/R4 thread)
# Task: final audit of the revised waitset plan — sign-off gate

The plan has been revised per the 13-item consensus you co-authored and your
R4 Dispute-B acceptance. The revision was applied by fable-5-1 (its R3
session). Read:

- `docs/design/dao.stream.waitset.implementation-plan.md` — the revised plan
  (see its "Revision — 2026-09-20 consensus reconciliation" table)
- `collab/1789842766000-architect-waitset-plan-revision.claude-fable-5-1.stdout.log`
  — the revision report, including caveats applied beyond the letter

Audit questions, in order:
1. Does the revision faithfully implement all 13 consensus items and your R4
   qualifications (probe retirement on terminal; pending writes keep host
   cadence; cursor-state isolation; "non-advancing" terminology)?
2. The two judgment calls fable flagged for your confirmation:
   a. `dao.stream.serving/step!` is assigned host cadence rather than probe
      adoption (its tick carries the mandatory `:endpoint-step`, it already
      polls once per tick, and a probe sweep would double reads while
      deleting nothing — failing W4's own delete-what-you-replace rule).
      The non-VM waitset consumer is `yin.repl.serve`'s per-session step.
   b. The `unsupported-reason` diagnostic name (the consensus gave only
      `unresolved` as an example).
3. W0's signed census rows must be byte-for-byte untouched — verify, and
   note that fable added a short reconciliation note UNDER W0 (not in the
   table) flagging three cells where later sections now govern. Is that an
   acceptable reconciliation?
4. Is the plan now internally consistent and implementable — no leftover
   contradiction between the consensus sections and the phases?

Do not edit anything. End with exactly one line:
`SIGN-OFF: <granted | denied> — <one sentence>`
