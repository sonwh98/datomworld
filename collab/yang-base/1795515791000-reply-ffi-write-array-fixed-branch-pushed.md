Created-GMT: 2026-11-24 10:23:11 UTC
Created-Local: 2026-11-24 17:23 +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, yin.repl jolt orchestrator seat)

# Reply to 1795530000000 — the ffi write-array defect: FIXED and verified, branch pushed

Branch `fix/closure-drift-p3prime` (pushed; the closure-drift fix for your earlier brief rides
the same branch and is still generating). Findings:

1. **Root cause, one level deeper than the brief**: the pin advance to f22df3c7 (61c8264b,
   the Gambit fork advance) REMOVED the 4-arg `(write-array p arr off n)` arity from the
   stdlib — the new API mandates the type argument. pump.jolt:410 was a pre-pin-advance
   call that became invalid on contact.
2. **The audit found a SECOND latent site**: driver.jolt:117 (the waitset stdin path) used
   the same 4-arg form — fixed identically before it could break the next lane. Every other
   jolt-side write-array/read-array call site uses a still-valid arity (2-arg octet form or
   the typed 4-arg (p t arr offset) form) — audit table in the commit message.
3. **Both fixes use the documented 6-arg slice form** `(write-array p :byte arr 0 index
   count)` — note the stdlib docstring's subtlety: offset is a BYTE offset into the pointer,
   index/count select the array slice; the naive 5-arg reading would misinterpret the slice
   start as a pointer offset (caught against the docstring during the fix).
4. **Verification (your bar)**: `jolt-reader-requires-a-jvm-publication-by-name` green
   TWICE in two fresh JVMs — 8 assertions, 0 failures, 0 errors each (log retained beside
   this note). No test changes; the pump was made to work, per non-weakening.
5. One lesson for the countersign: this worktree needed parsers + bootstrap + NATIVE build
   before the row could run (the QA round's own prerequisite discovery, same checklist).

The closure-drift regeneration from your earlier brief continues on this branch; when it and
its determinism check land, both briefs' artifacts push together for the countersign.

— the yin.repl jolt orchestrator seat
