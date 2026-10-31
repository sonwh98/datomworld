Created-GMT: 2026-09-15 22:20:00 GMT
Created-Local: 2026-09-16 05:20:00 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: 01a0a6ef-ad1a-71e0-8293-304dc99be863
# Task: r4 re-review of yin.vm/ast->semantic-bytecode
Role: Adversarial Review
Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-16 05:20:00 +07 | Status: active | Rationale: same reviewer, resumed session, fourth round

## What changed

`claude-opus-5` fixed all 3 of your r3 findings by addressing the shared
root cause you correctly identified — `same-meta?` and
`strip-reader-positions` now recurse into a value's metadata itself
(the metadata map's entries AND the metadata map's own metadata), not
just the value's structure:

1. `same-meta?` (`v2.cljc`, read the current function) now does
   `(and (= (meta a) (meta b)) (or (nil? (meta a)) (same-meta? (meta a) (meta b))) ...)`
   before its existing structural recursion — so your exact reproduction
   (`^{:note ^{:meaning 1} x} []` vs `^{:note ^{:meaning 2} x} []` through
   `yang/compile`) should now throw an address collision instead of
   silently merging.
2. `strip-reader-positions` now recursively strips reader-position keys
   from a metadata map's own values AND the metadata map's own metadata,
   reattaching via `with-meta`, in addition to its existing structural
   recursion. Your reader-generated reproduction
   (`^{:note (helper x)} []` read with `LineNumberingPushbackReader`)
   should no longer survive into the row body.
3. Reconstruction's `:nodes` case (used by both `:application` operands
   and `:dao.stream.apply/call` operands) now also requires
   `(nil? (meta v))`, mirroring the `:syms` fix from r3 — your new
   operand-vector-metadata reproduction should now throw `:slot-kind`.

The `same-meta?` docstring's scope-boundary note was corrected to remove
the incorrect grouping of nested-metadata with the custom-comparator-set
case — only the set case remains disclosed as deferred now.

Full account:
`collab/1789527000000-vmruntime-ast-semantic-bytecode-r4.claude-opus-5.findings.md`.
The orchestrator independently confirmed 23 tests / 170 assertions / 0
failures and spot-read the actual `same-meta?`/`strip-reader-positions`/
`:nodes` code (not just trusting the report) before requesting this
review.

## Task

1. Reproduce your exact r3 cases (1, 2, 3) against the current diff and
   confirm they're genuinely fixed — don't just check the new tests pass,
   construct your own probes as you have every round so far.
2. One more adversarial pass specifically targeting the NEW recursive
   logic itself: is `same-meta?`'s `(some #(when (= e %) %) b)` set-element
   lookup (still using plain `=`, not `same-meta?`, to FIND the matching
   element before comparing) correct when a set has two `=`-equal elements
   with different metadata (the still-deferred custom-comparator case) —
   does it at least behave predictably (always throw, never silently
   pick the wrong one) now, or is there a subtler issue? Try constructing
   any other case that would defeat the new recursive metadata comparison
   or stripping (e.g., cyclic metadata references if constructible,
   metadata on a record's fields if that's even reachable, very deep
   nesting).
3. Confirm whether Finding 4 (custom-comparator sorted set) remains the
   ONLY deferred item, or whether anything else from your r1-r3 rounds is
   still open that shouldn't be.

## Boundaries

Read-only. Do not edit anything.

## Deliverable

Report per your established format. End with an explicit verdict: is
this implementation now ready for Architect sign-off — yes or no.
Produce the complete deliverable now.
