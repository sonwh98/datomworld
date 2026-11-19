Created-GMT: 2026-11-20 1x:xx GMT
Created-Local: 2026-11-20 1x:xx +07 (+0700)
Coding-Agent: claude-sonnet-5-5 via agy (independent family from the glm author per team.md's independence rule)
Status: SLICE 3 REVIEW (parallel-lane rule: review N concurrent with slice N+1's implementation; review must complete before slice N+1 merges)

# Review: py-fstrings — the f-string slice (READ-ONLY)

Author: a glm-5.3 round (resumed from an interrupted zcode-subagent round; the 795-insertion resume-by-state worked — verify the seams), branch `py-fstrings` merged to master (three commits: `215911fa` tier-1 interpolation / `906586c5` tier-2 format-spec / `4bb17fa8` e2e pins). The worktree /Users/sto/workspace/datomworld-py-fstrings holds the branch. Read-only: edit NOTHING.

The author's claims to verify against the code (lower.cljc string/f-string handling; prelude.cljc `py/format-spec`/`py/format`):

1. **The interpolation lowering** (tier 1): each `{expr}` re-parses as its own packet joined at offset ids, the module scope a lambda bridge to the enclosing scope. Verify full expressions (calls, subscripts, arithmetic) genuinely evaluate in the enclosing scope — the scope bridge is the risk point.
2. **Evaluation order/once** (the slice-1 review lesson, explicitly required): interpolated expressions evaluate ONCE each, left to right, in source order. Check for the eager/lazy hazard class: is anything evaluated at lowering time that CPython evaluates at runtime, or vice versa?
3. **The spec-opening rule**: `:` at depth 0 always opens the spec (CPython 3.9.6: `f'{42:=6}'` is 42 with the `=`-align spec; a `:=` lives only inside brackets) — verify the depth tracking.
4. **The format-spec mini-language** (tier 2): str fill/align/width/precision; int/bool sign, `#`, 0-flag, grouping, `d`/`b`/`o`/`x`/`X`/`c`; float fill/align/sign/`0`/width. The author claims CPython 3.9.6's own ValueError messages and TypeError for `__format__`-less types — spot-check the messages against the claimed shapes.
5. **The refusals** (must be enumerated and honest): nested format specs, `=` debug suffix, nested f-strings, multi-line interpolations, bytes-in-fstring — do the diagnostics name what's missing?
6. **The goldens**: three-run byte-identical claimed with `:py` movement 4051→4398 applications / 393→414 free names — plausible for the format machinery's size?
7. **The resume seam**: the round was interrupted and resumed by-state across a lane change (zcode subagent → glm). Check the four touched files for style discontinuities, duplicated definitions, or half-finished branches at the seam.
8. **Regression risk**: the prelude gained format machinery — did it disturb the existing str/repr paths (the value-display surface every submitted string exercises)?

## Format

Findings table (P0/P1/P2/P3, file:line, invariant, recommended correction) — the evaluation-order check and the resume-seam check are mandatory rows — then one verdict line: ACCEPT / FIX-LIST / REJECT. Read-only.
