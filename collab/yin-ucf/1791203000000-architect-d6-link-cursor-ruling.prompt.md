Created-GMT: 2026-10-05 13:42:00 GMT
Created-Local: 2026-10-05 20:42:00 +0700
Coding-Agent: claude (fable-5-1, resume of session ff5b8c32-5cb0-4d81-9476-0a88c6109319)
Session-ID: ff5b8c32-5cb0-4d81-9476-0a88c6109319

# Task: rule on the deferred link-response cursor's install mechanism (read-only; the ruling is the deliverable)
Role: Architect

The D6 engineer implemented everything else (gated link append with a
new `[resources entry gate]` arity on `module/append-link-request`, the
skipped `:dao.stream/newest` mint under a gate, child stamping, install
children and direct-resume gating, and the gate-completeness zero-call
list on all four kernels — 27 tests / 340 assertions, red was 60
failures + 3 errors). Their report:
collab/1791200000000-vm-engineer-ucf-d6-engine-r3.claude-opus-5-5
.stdout.log (main tree) and the worktree copy.

Your D4 ruling said the driver "installs it on the entry through the
public apply". The engineer verified no existing public fits:
`apply-observation` is poll-only, `apply-mint` replaces an unminted
cell, `apply-close` removes a close record. The engine now builds the
`:link-request` entry without `:cursor` and never scans or retries it
under a gate, so a gated link cannot complete until you rule.

Rule the mechanism, with the machine-facing contract, the D6 test rows
it adds, and the D12/D13 driver obligations it implies. Candidates (not
exhaustive): a new narrow public `engine/apply-link-cursor [state
entry-id cursor]` mirroring apply-mint's shape (install the minted
cursor on that entry, wake nothing — the entry's next retry uses it);
generalizing apply-mint; or a public entry-update the driver owns.
Whichever you pick: state how the cursor-before-append order is kept,
what happens on a double install, and how :exporting/:ended treat it.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
