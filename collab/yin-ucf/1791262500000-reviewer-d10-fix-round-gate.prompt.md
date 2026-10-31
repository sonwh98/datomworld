Created-GMT: 2026-10-06 23:40:00 GMT
Created-Local: 2026-10-07 06:40:00 +0700
Coding-Agent: agy (gemini-3.1-pro-high, implementation round)

# Task: UCF M-next D10, fix round — the architect's two P1s and one P2
Role: Yang Compiler and Universal AST Engineer

Implementers:
- Status-Event: 2026-10-07 06:35 +0700 | Model: gpt-6.1-sol (codex) | Status: failed | Rationale: codex out of usage until 7:28am; the round never started
- Model: gemini-3.1-pro-high (agy) | Assigned: 2026-10-07 06:40:00 +0700 | Status: active | Rationale: the owner authorized codex/agy implementation rounds; agy is flat-rate and available

You are continuing an existing implementation in
/Users/sto/workspace/datomworld-d10 (branch ucf-d10-v1-lower; the work
is uncommitted in the tree). First read, in the main tree's collab/:
the architect's sign-off findings
collab/1791260000000-architect-d10-signoff-astra.gpt-6-astra
.findings.md (NOT READY; three items below are yours), the ruling you
already implemented (collab/1791240000000-architect-d10-lower-inputs
-ruling.gpt-6-astra.findings.md), and the engineer's report so far
(/Users/sto/workspace/datomworld-d10/collab/1791253000000-compiler
-engineer-ucf-d10-v1-lower.findings.md). Then `git -C
/Users/sto/workspace/datomworld-d10 diff` for the current state.

Your fixes, test-first per new row (the fourth item is being scoped
separately — do NOT attempt it):

1. **P1 grant bypass**: the public
   `:yin.vm.ucf.handoff/install-child` option (handoff.cljc about
   :1823 and :1833) suppresses custody inspection and
   `accept-grant!` — a caller supplying it to public `resume-task`
   can restore a blocked v1 body gated `:running` with no grant.
   Move recursive child restoration behind a private helper whose
   child context is established only after root validation and grant
   acceptance. Add a public-entry regression proving the option
   cannot bypass authorization and that rejection performs zero
   attachment calls.

2. **P1 issue-order stamps**: restored puts (about :1899) must be
   stamped `:yin.k/issue` in wait order per machine, children
   included, with `:yin.k/issued` initialized beyond those stamps;
   clear receiver-local `:yin.k/closes` and rebuild `:yin.k/issued`
   in the isolated receiver (about :1775 currently retains both).
   Pin restored-put -> newly queued close -> newly issued put
   ordering, and receiver-state contamination.

3. **P2 grant-test rows**: add an integral float epoch, an
   out-of-range epoch, and equal-length prefixes with duplicate,
   skipped and out-of-order sequence numbers, each failing even when
   the other grant fields agree, via the existing refusal helper (no
   `:vm`, zero attachments).

Acceptance criteria:
- The existing suites stay green; each new row red-then-green where
  the fix is behavioral.
- `git diff` stays within handoff.cljc and handoff_v1_test.cljc.
  Anything else: stop and report.
- No git writes; kondo/cljstyle may be sandbox-blocked — note it.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes with counts, the red
and green evidence, unresolved concerns, and any incomplete work. Do
not claim edits or tests that did not occur.
