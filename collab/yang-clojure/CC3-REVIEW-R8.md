Created-GMT: 2026-11-12 02:0x GMT
Created-Local: 2026-11-12 09:0x +0700
Coding-Agent: codex
Session-ID: (resume of your thread 01a1c902-699d-7f42-8535-abc350d95451)

# Task: Confirm the cc3 fix round r8 (your F1 + F4)

Role: Reviewer (adversarial, read-only) — eighth pass, same thread

Your r7 confirm accepted F3 (frozen) and F2 (conditional-on-shiva), and objected on F1 (blanket catch made failed retention invisible) and F4 (targeted regression missing). The author's r8 report: `/Users/sto/workspace/datomworld/collab/yang-clojure/1794405917000-engineer-cc3-fix-r8.muse-wonder-1.0.findings.md`. Cumulative uncommitted diff on top of `0ec036d1` is now 4 files (+280/−23): repl.cljc + index_test + query_test + clojure_callsite_test. Worktree files are truth. Orchestrator re-verified: index+query 47/615/0; battery 37/423/0.

## Confirm or reject

1. **F1**: is failed retention now observable per your spec — admission-scoped diagnostic distinguishing failed retention from unclaimed admission; no handle advertised for a failed packet; completed handles surviving; the diagnostic reaching an observable surface even when indexing succeeded (the `:call-provenance-failures` session state + additive second Warning line)? Adversarial: can a failure entry be forged or lost on the recovery paths (the ex-info carriage + link-raise + recheck merges — the same paths F4-of-cc2 traversed)? Does the additive Warning break any EXISTING contract (the author claims all old assertions pass unmodified)?
2. **F4**: do the three new targeted cases cover relation-write failure, record-write failure, and the unclaimed-vs-failed distinction, with the no-incomplete-handle and earlier-handles-survive assertions?
3. **The stage-tagging** (`retention-throw` with `:validation`/`:relation`/`:record` + admission + root): does it leak anything into canonical surfaces, and are the tags plain data?
4. NEW defects in the r8 diff (the failures volatile + session-state merge is the fresh surface — same adversarial focus as cc2's r4-confirm: identity/keying errors in the merge paths).

## Output

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then `F<N>: CONFIRMED-FIXED | OBJECTION: <...>`, ending with `CONFIRMATION VERDICT: APPROVE | REQUEST CHANGES`.
