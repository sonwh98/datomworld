Created-GMT: 2026-11-18 15:14:01 GMT (delta-review dispatch)
Created-Local: 2026-11-18 22:14:01 +07 (Asia/Ho_Chi_Minh)

# Task: yang-compilers-dedup-items245-step7-delta-review

Role: Reviewer (step-7 delta, same thread as your original APPROVED)

You approved the full 13-path DRY dedup (2026-11-16) and confirmed the P2-2 docstring.
The landing then happened in two halves under coordination constraints:
- Items 1+3 (siteset store + publication) LANDED as 1604aabe — the state you approved.
- Items 2/4/5 (prelude emitters, safepoint emitters, lower helpers) were PARKED pending
  the php seat's prelude-clear, which arrived today (1795016657000).

## The delta under review now

The items-2/4/5 residual, extracted from the parked diff and re-based onto current master
9c43930b: 9 files, +38/−38 (filed as `1795017191125-dry-items245.patch.txt`, piped after
this prompt). Differences from what you already reviewed:

1. Same extractions, but the surrounding code moved: master gained the dyn-continuation
   fix (php prelude +22 in php/make-function's region), const-admission (lower.cljc
   const-resolver arms), and a staging reconcile. Our extraction regions don't overlap
   those (verified by the clean 3-way apply except one trivial conflict).
2. That one conflict: master's side of `yang/core/prelude.cljc` already carries the P2-2
   docstring you confirmed; the parked patch predates it. RESOLUTION TAKEN: keep ours
   (the docstring); the patch contributed no code there. Net effect = docstring preserved,
   item-4 helper added below it.
3. Content-address guard evidence, re-run today on the applied tree: golden byte-identity
   (fixed-key signed-entry/entry-datoms) BYTE-IDENTICAL; focused gates 6 namespaces
   (php/py/ts prelude-safepoint-lower) = 124 tests / 1896 assertions / 0 failures.

## Your task

Verify the delta against the invariants you already checked (d: balanced sequence-tree
shape; e: thin wrappers, correct per-target parameters — vconj-as-data, reader/absent
predicates; a/b remain landed and untouched). Confirm the conflict resolution is
semantically null (docstring-only). Confirm scope discipline: nothing beyond the five
extractions. READ-ONLY; final response is the deliverable.

Report format: same as your original review — findings with severity, then exactly one
final line: APPROVED | CHANGES STILL REQUIRED — <why> | PROOF FAILS — <why>
