Created-GMT: 2026-09-15 22:05:00 GMT
Created-Local: 2026-09-16 05:05:00 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: 01a0a6ef-ad1a-71e0-8293-304dc99be863
# Task: r3 re-review of yin.vm/ast->semantic-bytecode
Role: Adversarial Review
Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-16 05:05:00 +07 | Status: active | Rationale: same reviewer, resumed session, third round

## What changed

`claude-opus-5` fixed your r2 Findings 2 and 5:
- Finding 2 (MapEntry corruption): `strip-reader-positions`'s vector
  branch now uses `(mapv strip-reader-positions x)` instead of
  `(into (empty x) ...)`, since a MapEntry is `vector?` but its `empty`
  isn't a vector. Map/set branches still use `(into (empty x) ...)`,
  where `empty` is reliable.
- Finding 5 (params vector metadata asymmetry): reconstruction's `:syms`
  case now also requires `(nil? (meta v))`, rejecting a hashed row whose
  params vector carries metadata (which `dao.jing` would hash but
  projection's `mapv` can never reproduce).

Findings 1 and 4 (metadata nested inside metadata; a custom-comparator
sorted set holding `=`-equal metadata-distinct elements) were
**deliberately not fixed** — the orchestrator judged them genuinely
pathological inputs no real frontend or reconstruction path produces, in
the same class as `dao.jing.md`'s own already-accepted pathological-symbol
and scalar-metadata residuals. A docstring note was added to `same-meta?`
disclosing both as explicit scope boundaries. This is a judgment call you
should evaluate on its merits, not just confirm was implemented as asked
— if you think either is actually reachable in practice (not purely
theoretical), say so.

Your Finding 3 (reader positions nested inside a metadata map survive
stripping) was NOT addressed — the implementer flagged that it wasn't in
the fix/scope-out list given to them and left it genuinely open. Note
this in your verdict.

Full account:
`collab/1789525100000-vmruntime-ast-semantic-bytecode-r3.claude-opus-5.findings.md`
— verify rather than trust.

## Task

1. Verify Findings 2 and 5 are actually closed — reproduce your own r2
   test cases against the current diff.
2. Evaluate the scope-out decision for Findings 1 and 4: is it
   defensible, or do you think either is practically reachable? If you
   believe either should actually be fixed, say so and explain why it's
   NOT equivalent to `dao.jing`'s already-accepted pathological residuals.
3. Your Finding 3 is still open — confirm it's still reachable exactly as
   you found it (reader positions nested inside metadata maps), and
   assess: does leaving it open block sign-off, or is it acceptable to
   track as a known gap alongside Findings 1/4 (same underlying
   metadata-on-metadata shape)?
4. One more adversarial pass: try to construct any NEW case that would
   still break this implementation.

## Boundaries

Read-only. Do not edit anything.

## Deliverable

Report per your established format. End with an explicit verdict: is
this implementation now ready for Architect sign-off — yes or no, and if
no, what specifically must still change (versus what can be disclosed and
deferred). Produce the complete deliverable now.
