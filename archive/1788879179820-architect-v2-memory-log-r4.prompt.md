Created-GMT: 2026-09-08 14:52:59 GMT
Created-Local: 2026-09-08 21:52:59 +0700 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: 0dae45ea-4204-49d9-bf35-c56e24cce14e
# Task: gate population on the manifest (r4) — final correction
Role: Lead System Architect

Review: `collab/1788878885493-review-v2-memory-log-plan-r3.gpt-5.6-sol.findings.md`
Everything else is confirmed closed. **One P1 remains**, and it is the bug
class one level up from the last: the law that polices false exclusions does
not itself check the manifest.

## The finding

Population treats every `full` as a legitimate stop and every
`invalid-value` as a legitimate skip **without first checking whether the
manifest declares that outcome**. So: an allegedly unbounded transport
excludes `full`; its faulty implementation accepts one value then answers
`full`; population records one accepted value and stops; both replays return
that value and `blocked`; the law **passes**, having directly observed an
outcome the manifest excludes. Induction coverage does not save it — that
only inspects results from the manifest's own fixtures, never the appends the
retention law performs itself.

## Required

**Gate every population outcome on the manifest before interpreting it.**
Any append result outside `[:operations :append! :produces]` is
`:undeclared-population-outcome`, carrying the offending result. Then:

- declared `full` → stop population, proceed to replay with the accepted
  prefix;
- `closed` or `transport-error` on a fresh handle → setup failure **even
  when declared**, because the law could not establish its populated history;
- the population helper returns structured failure information including the
  offending result, not `::defect true` metadata on the accepted vector.

## The terminology choice — I am deciding it, not forwarding it

`:retention-values` means exactly what §4 says: **values this transport
accepts.** So an `invalid-value` during population is a **fixture defect**
(`:retention-values-rejected`), not a skip. A restricted value domain is
expressed by supplying values *in* that domain — that is what the field is
for. Delete the skip branch entirely; it buys nothing and costs a way for the
law to pass while the transport refused most of its input.

## P2 — reader-only fixtures

Require `:expected` non-empty for reader-only fixtures too
(`:empty-retention-fixture`), matching the writer path's
`:no-accepted-values`. An immutable empty history is contract-valid but
behaviourally vacuous here, and its completeness stays a structural proof
obligation rather than something this law certifies.

## Tests the plan must require

- an excluded `full` observed during population **fails**;
- an excluded `invalid-value` observed during population **fails**;
- a declared `full` stops population and the accepted prefix passes;
- a reader-only fixture with empty `:expected` fails.

Change nothing else. Emit the whole revised plan to stdout.
