Created-GMT: 2026-09-19 14:42:24 GMT
Created-Local: 2026-09-19 21:42:24 +07 (Indochina Time)
Coding-Agent: deepseek
Session-ID: e83a86bf-e75a-44e1-a171-a25669447a21 (resumed — you authored this plan in this conversation)
# Task: revise dao.lease implementation plan per adversarial review (r2)

Role: Architect (author revision round)

You drafted `docs/design/dao.lease.implementation-plan.md` in this
conversation. An independent architecture reviewer (gpt-5.6-sol, different
family) has since audited it against the two design documents and the real
`dao.stream` sources, with verdict **unsound** and twenty findings.

Read first:
- `/Users/sto/workspace/datomworld/collab/1789828376000-architect-dao-lease-plan.gpt-5.6-sol.findings.md` — the full review (authoritative findings list, numbered 1–20)
- `docs/design/dao.lease.implementation-plan.md` — the plan you are revising (your cwd's copy)
- `docs/design/dao.lease.md` — the operative contract
- `docs/design/dao.stream.md` — the stream contract

Revise the plan in place to resolve every finding. The orchestrator has
spot-checked the reviewer's four load-bearing source citations
(`observe/step`'s writer-outcome effect set, `outcomes-next`'s
`cursor-mismatch`/`invalid-cursor`, `forward-step`'s batch budget, the ring
buffer's atom) — they are accurate; do not rebut those on citation grounds.

Specific dispositions the orchestrator directs:

- Finding 1 (D1 contradiction): do NOT edit `dao.stream.md`. In the plan,
  state the contradiction plainly and propose the exact one-sentence
  amendment text for `dao.stream.md`'s Composition section as a handoff to
  the orchestrator; until it is applied, the plan builds plain maps and
  records the unresolved-contract status.
- Finding 2 (timer prohibition): the reference tick source must not install
  a timer or hold a callback inside `dao.lease.cljc`. Reshape it as a
  step/deposit function the host driver calls, or move it into the test
  tree; fix C5's grep scope accordingly.
- Finding 3 (purity wording): the judge is a state-threaded, effectful
  interpreter step in the `forward-step` discipline — not pure. Fix D2's
  wording everywhere it claims purity.
- Finding 4 (D5): drop the `observe/step` mechanism for the reclaim→record
  pair — a boolean reclaim is not a writer outcome. Specify explicit
  sequencing in `judge-step`: mark `pending` with cause, perform the
  injected reclaim, append `:lapsed`, remove from the ledger only on
  `:dao.stream/ok`, otherwise remain `pending` for the next pass. The
  record-after-act law itself stands.
- Findings 5–20: apply as itemized — add or amend invariant rows for every
  unpinned contract rule (or defer it explicitly in §6), apply the J7
  eligibility-before-policy rule, complete J9's window semantics, add a
  drain budget or a stated quiescence guarantee to the pass, widen the
  attribution resolver's signature to bind source context, specify the
  medium declaration `make-*` validates, complete the Phase 1–4 test lists
  per the reviewer's itemization, classify `cursor-mismatch`/`invalid-cursor`
  as pass-aborting defects, and harden the tests named in finding 20.

Where you disagree with a finding, you may rebut it — but a rebuttal must
argue from the contract text, not from convenience, and goes in a short
reconciliation note rather than leaving the finding unaddressed.

Add a brief "Revision — 2026-09-19 reconciliation" section at the end of the
plan listing each finding number with its disposition (applied / rebutted /
deferred and where). Keep the plan's existing method: invariants grouped by
test, `[D]`/`[T]` markers maintained, §7's accounting claim true when this
revision is done.

Scope: edit ONLY `docs/design/dao.lease.implementation-plan.md` in your
current directory. Do not stage or commit. One single simple command per
step if you run any (no chaining, no pipes).

Produce the complete revision now without waiting for a human. Begin the
final response exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then report: the per-finding disposition list (1–20), any rebuttals with
their contract-text grounds, and anything you could not resolve.
