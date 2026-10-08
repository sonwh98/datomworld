Created-GMT: 2026-09-15 22:15:50 GMT
Created-Local: 2026-09-16 05:15:50 +07 (Asia/Ho_Chi_Minh)

# Task: Architect sign-off r2 — dao.jing canonical encoder, comparator fix folded in

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-16 05:15:50 +07 | Status: active | Rationale: same Architect, resumed session, confirming the fold-in you yourself requested
Session-ID: 7216a6f9-10f5-4468-8198-f9009b55e6ad

Your r1 review was APPROVE-WITH-FINDINGS (nonblocking), and you explicitly
asked for Finding 2 to be folded into this commit since the lines were
already touched. GLM applied it. Re-review the current diff
(`git diff -- src/cljc/dao/jing.cljc test/dao/jing_test.cljc`) to confirm.

## What changed since your r1 review

1. **Finding 2 (your requested fold-in).** Both comparators in
   `order-normalize` now sort by `canonical-print` instead of `pr-str`.
   `canonical-print` was moved above `order-normalize` in the file (so the
   dependency points backward — no forward `declare` needed). GLM's
   before/after probe: `(def m {[1 2] :a, [1 3] :b})` — before the fix,
   `(binding [*print-length* 1] (= (content-hash m) (content-hash m)))`
   with the *binding* comparison against the unbound hash was `false` (a
   vector-key entry silently dropped under the tied `pr-str` comparator);
   after the fix, `true`. GLM explicitly did NOT bind the print vars to
   defaults at `content-hash`'s entry (your "optional" correction) —
   deferred to the Finding 1 backend/wire fail-closed follow-up unit,
   since `*print-readably*` still reaches scalar bytes (a disclosed,
   unchanged residual — canonical-print still delegates scalars to
   `pr-str`).
2. **Finding 5.** The record-rejection message is now a single line.
3. **Finding 6.** One new test asserts a vector with `:line`/`:column`
   metadata hashes identically to the bare vector.

Findings 1, 3, 4 from your r1 review were intentionally NOT touched here
— 1 is a separate future unit, 3 is a doc amendment the orchestrator made
directly to `docs/design/dao.jing.md` (uncommitted, will be its own
`docs:` commit reviewed separately), 4 is a commit-message note.

## Task

Confirm Finding 2's fix is sound: does sorting by `canonical-print`
actually close the crack you found (ambient print-var bindings causing a
sort-tie that drops a map/set entry)? Is moving `canonical-print` above
`order-normalize` safe on all three hosts (any circular reference risk,
any host-specific declaration-order requirement)? Re-verify the doc
amendment to `docs/design/dao.jing.md` you can see in `git diff` (it now
records the encoder's contract precisely and adds Finding 1 plus a new
byte-array Open Item) — is it accurate to what the code now actually
does?

## Do not edit files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report per your role's format, and end with an explicit sign-off verdict:
APPROVE, APPROVE-WITH-FINDINGS (nonblocking), or BLOCKED.
