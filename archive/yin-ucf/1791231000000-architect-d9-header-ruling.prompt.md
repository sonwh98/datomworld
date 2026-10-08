Created-GMT: 2026-10-06 17:45:00 GMT
Created-Local: 2026-10-06 00:45:00 +0700
Coding-Agent: claude (fable-5-1, resume of session ff5b8c32-5cb0-4d81-9476-0a88c6109319)
Session-ID: ff5b8c32-5cb0-4d81-9476-0a88c6109319

# Task: rule on D9's header channel and the fixture regeneration (read-only; the ruling is the deliverable)
Role: Architect

The D9 engineer stopped before any edit on your D8 sign-off's own
carried-forward question. Their report (the datomworld-d9 worktree's
collab/1791223000000-compiler-engineer-ucf-d9-v1-lift.findings.md, also
promoted to the main tree's collab/): nothing builds
`:yin.k/custody` (r3 1.1 describes it, nothing constructs it), and a
first export has none, so the lift has no input for the version-1
header values — occurrence, arbitration {identity, descriptor}, origin,
next-op-seq, and the enrolled-stream set that `:unprotected-pending`
needs.

Their recommendation: pass all of these in one `header` argument to
`prepare`, stored in the export record beside `:served`; encode stays a
pure function of the record; a D14 journal can store the record; the
composition supplies the header after folding the ledger reader, and an
incomplete fold is `:unsatisfied` before prepare is called. They list
two alternatives and four short questions in the report.

Rule:
1. The header channel: accept the recommendation, amend it, or replace
   it. State the exact argument shape, who validates what (the
   inspector's rules on a halted root: origin required; occurrence and
   next-op-seq absent — does prepare or encode enforce the
   kind/header agreement, or does the existing v1 reader refuse at
   lower?), and what `:unsatisfied` covers.
2. The D7 reader/lift agreement: the v1 grammar's phase and parent on
   install entries must come from somewhere on lift — rule where
   (the export record's install metadata? the header? the child's own
   body?).
3. The two fixture concerns: regenerating the C4 accepted fixtures
   through a real lift (frame-order-dependent mutation paths), and
   op ids set by hand on wait entries until D11. Say what D9's
   regeneration commits to.
4. Anything else the report's four questions raise that changes the
   brief.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
