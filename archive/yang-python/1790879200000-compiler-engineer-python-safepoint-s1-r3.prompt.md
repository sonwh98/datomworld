Created-GMT: 2026-10-01 20:45:00 GMT
Created-Local: 2026-10-02 03:45:00 +07 (+0700)
Coding-Agent: claude
Session-ID: bfaf5e35-70a5-4e4b-9686-bf5717d46bd8 (resumed; slice 1 fix round 2 — the Node blocker)

# Task: Safepoint slice 1 fix round 2 — Node: prelude integer-bound literals refused by dao.jing.cbor

Role: Yang Compiler and Universal AST Engineer

Your Node lane report: 13 errors, all "dao.jing.cbor refused: unsupported-value (unsafe integral JavaScript
number)", caused by the base prelude's C1 integer-bound literals at prelude.cljc:560,561,572,748 (plus 748's
sibling if any). You declared it out of scope; it blocks slice 1's Node acceptance, so it is now this round's
task, bounded as follows.

STEP 1 — DIAGNOSE (report in the final response):
- Confirm exactly which hashing/projection path refuses on these literals, and why the same bundled prelude
  passes on the C2 worktree's Node lane (its e2e corpus is green on bb test:cljs) — the difference matters
  (probably which rows actually get hashed on each path). Cite file:line.

STEP 2 — FIX (bounded):
- Preferred: make the four (or fewer) bound expressions JS-safe WITHOUT changing their values or the naive
  semantics on JVM/Dart (e.g. construct 2^53 so no unsafe integral JS number is ever hashed; keep exact
  comparisons identical on all hosts). Add a comment stating the constraint: values hashed by dao.jing.cbor must
  be JS-safe-integer-representable.
- If the minimal fix requires touching dao.jing.cbor's classification, or anything outside prelude.cljc and the
  safepoint tests: STOP, write up the design question, and end the round without making that change — the
  orchestrator will mob it to the architects.
- Also fix the one-line prelude declaration gap your round noted: prelude/host-names should declare the data/
  names the base prelude calls (data/number?, data/dissoc), and revert your registry-resolution workaround in
  the closure check to use the declaration.

MECHANICS: foreground, one command per step, no background runs, no watchers: mise exec -- bb build:yin-repl-node;
bb test:clj; bb test:cljs (must be 0 failures AND 0 errors); bb test:cljd; kondo + cljstyle on touched files.
Report exact counts, the diagnosis from step 1, and any remaining concerns.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>
