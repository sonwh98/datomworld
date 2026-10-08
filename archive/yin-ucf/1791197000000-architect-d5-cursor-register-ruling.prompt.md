Created-GMT: 2026-10-05 09:53:00 GMT
Created-Local: 2026-10-05 16:53:00 +0700
Coding-Agent: claude (fable-5-1, resume of session ff5b8c32-5cb0-4d81-9476-0a88c6109319)
Session-ID: ff5b8c32-5cb0-4d81-9476-0a88c6109319

# Task: rule on gated :stream/cursor at the register kernel for D5 (read-only; the ruling is the deliverable)
Role: Architect

Your D4 ruling moved gated `:stream/cursor` to D5 with the kernel round.
The D4 engineer (same session, datomworld-d4 worktree) then reported the
kernel-side facts that D5 inherits:

- semantic.cljc (about l.443): the cursor site passes `nil` for
  park-entry-fns; it needs `(call-park-entries seg pc E St K)` as
  `:stream-next` and `:stream-put` already pass.
- debruijn/register.cljc: `:stream-cursor` is not in `boundary-opcodes`,
  so the site has no `:live` operand and cannot compute the continuation
  payload; fixing that "changes the register encoder and code format".
- ast_walker.cljc (about l.411): the cursor site passes no builder and
  ignores `blocked?`; it needs the `{:k (:next k) :env env}` builder and
  a `blocked?` branch.
- debruijn/stack.cljc: works as is.
- Poll is unaffected (no kernel emits `:stream/poll`; the caller's
  builders serve).

D4 proceeds on your ruling's option 2 without cursor (put, next, poll,
close, gate-mode, the `:observe` entry, the sweep skip,
apply-observation for poll).

Rule on D5's cursor path:

1. semantic.cljc and ast_walker.cljc: confirm the two builder/branch
   additions are the intended fix (mechanical, no format change).
2. debruijn/register.cljc: `boundary-opcodes` gains `:stream-cursor`
   only if that does NOT change the register code format for existing
   code (check what the `:live` operand does to encoding and to the
   stamped `register-contract "r2"` and whether existing registered
   images/encodings are unaffected — the repo is development-only, per
   the I-4 precedent, but say which). If it does change the format,
   rule the alternative: what D5 does at the register kernel's cursor
   site instead (e.g. gate cursor answers `full` with a retained-style
   entry built without the `:live` operand, or cursor parks ungated
   under the register kernel only, or something better), and the
   consequence for the gate-completeness claim of D6 and for the UCF
   7.4.1 amendment wording.
3. The D5 zero-call list gains the cursor rows; state them.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
