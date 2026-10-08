Created-GMT: 2026-09-02 07:58:02 GMT
Created-Local: 2026-09-02 15:58:02 Asia/Shanghai

# Role: Adversarial architecture reviewer — a split, not a rewrite

## What just happened

`docs/design/dao.lease.md` had reached 815 lines after four review rounds. The
round-4 verdict from four independent reviewers was unanimous: **the document
was accreting faster than it converged.** Six of fifteen findings that round had
been *created* by round-3 fixes. The diagnosis: every fix was a local argument
appended to a document that had no normative core separate from its rationale,
so each fix argued against the previous argument and nothing checked the rules
against each other.

The prescribed remedy was a split, and it has now been done:

- **`docs/design/dao.lease.md`** — 261 lines. The operative contract. Rules
  only. Every sentence is meant to be a rule.
- **`docs/design/dao.lease.rationale.md`** — 525 lines. Binds nothing. Lineage,
  precedent, the Jini critique, and the argument behind each rule.

## Scope, strictly

Read exactly these:

- `docs/design/dao.lease.md`            — the contract (authority)
- `docs/design/dao.lease.rationale.md`  — the companion (binds nothing)
- `docs/design/datom.world.md`          — the governing authority
- `docs/design/dao.stream.md`           — the contract the lease must not touch

Read no source code. Do not read anything under `collab/`.

Precedence: `datom.world.md` > `dao.stream.md` > `dao.lease.md` >
`dao.lease.rationale.md`.

## What to judge

This is a review of a **split**, so the usual defect hunt is secondary. Weight
these, in this order:

1. **Is the normative half self-sufficient?** An implementer holding only
   `dao.lease.md` — never opening the rationale — must be able to build a
   grantor and a holder. Name anything they cannot determine. Conversely, name
   anything still in the contract that is argument rather than rule, and
   anything in the rationale that is load-bearing and was left behind.

2. **Did the split lose or duplicate an obligation?** 815 lines became 261+525.
   Something was dropped. Walk the enumerations — the fact table and its
   required keys, the judge's ledger fields against the five pass steps, the
   composition duties, the sizing relations, the limits — and say what fell
   through. Separately: where the two documents now state the same rule twice,
   that is the failure mode the split existed to end, and it should be named.

3. **Did these five round-4 fixes actually land, or relocate again?**
   - "root set" replaced by "ledger" throughout the normative half.
   - Pending reclaim is now a due-for-reclaim condition (pass step 3), so a
     failed record in step 5 is retried.
   - `now` is defined when zero ticks are drained.
   - The holder measures its bound against ticks on its own medium.
   - The honour-the-granted-duration vs cap-below-the-duration contradiction is
     resolved in *Sizing*.

4. **Walk two compositions end to end through the normative half alone.**
   Every step must be expressible in the contract as written; say precisely
   where it is not.
   - A **served connection**: unsolicited grant seeded from the grantor's own
     act, renewals arriving on a deposit medium, a tick, a pass, a reclaim that
     closes the socket, a `:lapsed` record on the grantor's own stream.
   - An **in-process pause**: a reader holds a pause over a forwarder, over a
     medium where nothing distinguishes writers unless the composition arranges
     it.

5. **Conformance.** Does the contract still keep its central claim — that
   `dao.stream.md` is unchanged by its existence, adding no operation and no
   result-map key? And does it conform to the axioms and the non-negotiable
   invariants?

## Output

Findings ranked most severe first: severity | file:line | the claim | the exact
correction. Be specific; cite line numbers.

Then three short sections:

- **What the split got right**, if anything, stated plainly and briefly.
- **What an implementer cannot determine from `dao.lease.md` alone.**
- **Whether this document is now converging or still accreting**, and your
  evidence either way. If your judgment is that a further editing round would
  make it worse, say so — that is a useful finding.

Read-only; do not edit files.
