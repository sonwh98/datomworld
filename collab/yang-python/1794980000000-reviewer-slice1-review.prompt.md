Created-GMT: 2026-11-20 0x:xx GMT
Created-Local: 2026-11-20 0x:xx +07 (+0700)
Coding-Agent: deepseek (independent family from the GLM-family author per team.md's independence rule)
Status: SLICE 1 REVIEW (parallel-lane rule: review N concurrent with slice N+1's implementation; review must complete before slice N+1 merges)

# Review: py-quickwins — the three quick-win coverage slices (READ-ONLY)

Author: a zcode-subagent round (GLM family), branch `py-quickwins` (merged to master `b305acac`, three commits: `ca7dd6d9` assert / `36b25363` annassign / `3a2c64cc` unicode-escapes, rebased onto the slice-0 merge).

The worktree with the full diff: /Users/sto/workspace/datomworld-py-quickwins (branch py-quickwins @ the rebased tip). The diff vs master is the three commits. Read-only: edit NOTHING.

## What to review (roadmap slice 1: assert / annotated assignment / named unicode escapes)

The author's claims to verify against the code:
1. `assert` (lower.cljc:1858 lower-assert, prelude.cljc:762 py/assert, :3439 the AssertionError builtin): falsey test raises AssertionError through the existing raise machinery; `assert x, msg` carries the message; the bare form raises with `args = ()` per CPython. NOTED DIVERGENCE by the author: a literal `assert x, None` raises with empty args where CPython has `(None,)` — evaluate whether that divergence is acceptable (a pin documents it) or worth fixing (nil vs :py/None sentinel).
2. `annassign` (lower.cljc:1424 assign-sides extracted and shared with the plain path, :1439 lower-annassign): value assignment once-evaluated into a temp exactly like the plain store path; annotations ERASED (documented decision — the runtime has no type-checking layer); bare `x: T` lowers to nothing (CPython binds nothing).
3. Named unicode escapes (lower.cljc:373 named-escapes, :425 named-escape): a ~140-name static table; unknown/malformed `\N{...}` refuses loudly at lowering.

## Review checklist

- **CPython semantics parity** for each construct (the language reference is the bar): assert's evaluation order (test first, message only on failure — verify the lowering doesn't evaluate the message eagerly!), annassign's evaluation order (the value evaluates; the annotation ALSO evaluates at runtime in CPython when it has side effects — the author ERASED annotations; is that semantically defensible given the doc's residue analysis, or does it change observable behavior? State which), `\N{...}` resolution.
- **The refusal boundaries**: what still refuses and do the diagnostics name it honestly.
- **The pins**: do they actually pin the claimed semantics (evaluation order, args shapes, refusal messages)?
- **The goldens**: two-run byte-identical claimed; the `:py` group gains `py.b/AssertionError` + `py/assert` — plausible?
- **Regression risk**: the `assign-sides` extraction is shared with the PLAIN assignment path — did the refactor change any existing assignment behavior (the 47/496 gate says no, but review the extraction for subtle differences: when does the temp get introduced, does the plain path's one-evaluation guarantee hold in every arm)?

## Format

Findings table (P0/P1/P2/P3, file:line, invariant, recommended correction), then one verdict line: ACCEPT / FIX-LIST / REJECT. Read-only.
