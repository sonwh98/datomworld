Created-GMT: 2026-09-06 17:56:16 GMT
Created-Local: 2026-09-07 00:56:16 +07 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 50d48a71-9ff9-44b7-8dc0-b334e5f42aac
# Task: dao.jing.v2 plan — revision 3 to 4 delta review
Role: Adversarial Code Reviewer and Security Auditor
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-07 00:56:16 +07 | Status: active | Rationale: resumed session; it filed the P1 and P3 this revision closes, and set the exhaustiveness bar this revision must meet

Narrow scope again. Review **only** what changed between revision 3, which you
reviewed, and revision 4.

- `collab/dao-jing-v2-r3-r4.delta.diff` — the unified diff, 374 lines
- `collab/architect-dao-jing-v2-revision-r4.claude-fable-5-1.findings.md` —
  revision 4 in full, for context around any hunk
- your own delta findings:
  `collab/adversarial-dao-jing-v2-delta.glm-5.3.findings.md`

Everything you accepted in the r2→r3 review is closed: the verify-hop
ownership decision, the principle argument, and all four precision
corrections. The seven consensus items remain settled. Do not reopen any of it.

## What revision 4 does

Your P1 fix, applied: the record registers under the get id as soon as
`rpc/request!` returns one — `requested`, `pending-request` and
`request-undeliverable` alike — and under the put id at
`request-materialize`; any completion carrying a registered id completes the
materialization once, the record's removal being the guard; the terminal
sweep is assigned to step order 4. Your P3 fix, applied: under the
documentation route none of the seven repoints happens.

Two things it did beyond your fix, and they are what to scrutinise:

1. **It split the phase** into `:verify-unissued` and `:verify-issued` (r3 had
   one `:verify-pending`), so the terminal sweep touches only unissued records
   while issued hops complete through their registered get id via
   `lose-outstanding` or order 3's `abandon-unsent`. It also states that step
   order 5 drains after order 4 registers, so an undeliverable hop's
   already-outboxed completion routes in the same step.
2. **It added a 24-row lifecycle table** (Decision 3, "The lifecycle, as a
   table") enumerating every record phase against every event, with the record
   transition, the published completion, and the routing id — including cells
   marked **impossible** with a reason.

## What to judge

- **Is the table actually exhaustive and actually correct?** It is the
  structural answer to a defect that recurred three times as a missed exit, so
  it is worth more scrutiny than the prose it replaces. Check every
  *impossible* cell: is it truly unreachable, or merely unconsidered? Check
  that each reachable cell's routing id matches what `rpc.cljc` actually puts
  on that completion. Look specifically for an event the table does not have a
  row for.
- **Does the phase split hold?** A record is `:verify-issued` the moment
  `request!` returns `pending-request` — i.e. while the envelope is still the
  RPC layer's `:unsent`. Is that the right boundary, and does the terminal
  sweep's "unissued only" rule leave any issued-but-unsent record without an
  exit on terminal?
- **Is the order-4-then-order-5 claim sound**, given that order 4 can leave
  the writer `full` and stop issuing further hops that step?
- **New defects in the 374 changed lines**, as
  `P0-P3 | section or hunk | evidence | concrete fix`, or "none found".

Settled facts, do not re-derive: `request!` is the sole unsent retry path;
`attempt-unsent`'s closed/invalid-value/transport-error branch appends a
completion immediately and answers `request-undeliverable`; `requested`,
`pending-request` and `request-undeliverable` all carry
`:dao.stream.rpc/id`; `lose-outstanding` covers `:outstanding` only;
`abandon-unsent` completes with `/abandoned` and sets no terminal;
`allocation-failure` sets terminal and mints no id. Suite green per the user;
no authority to run tests.

If this closes, say so plainly. Do not edit any file. Produce the complete
response in this run.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: 50d48a71-9ff9-44b7-8dc0-b334e5f42aac
