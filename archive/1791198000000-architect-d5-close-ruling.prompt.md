Created-GMT: 2026-10-05 10:01:00 GMT
Created-Local: 2026-10-05 17:01:00 +0700
Coding-Agent: claude (fable-5-1, resume of session ff5b8c32-5cb0-4d81-9476-0a88c6109319)
Session-ID: ff5b8c32-5cb0-4d81-9476-0a88c6109319

# Task: rule on gated :stream/close for D5 (read-only; the ruling is the deliverable)
Role: Architect

One gap your r4/r5 rulings leave open: `:stream/close` under a gate. The
D4 engineer's report (their session report in
collab/1791194500000-vm-engineer-ucf-d4-engine-r1.findings.md): gated
close is not implemented; the kernel sites have the same missing-builder
problem as cursor (semantic passes nil builders, the walker passes none
and ignores blocked?, `:stream-close` is not a register-kernel boundary
opcode); under `:running` close still calls `close!` (unfenced); in
`:exporting` and `:ended` it is refused with no handle call.

r1 section 1.2 said: "`:stream/close` on an enrolled target answers the
shaped refusal value; closing a target is the authority's act. On an
unenrolled stream the driver performs it." The engine cannot see
enrollment, so the shape of the gated close must come from you. Cursor's
unminted-cell trick does not transfer (a close has no deferred position;
its outcome is the continuation's value). A parked close needs the same
kernel builders cursor needed, and the register-kernel boundary-opcodes
route is rejected by your r5 ruling.

Rule the machine-facing contract for close under each gate mode, with
the same rigor as r5: what the engine does at the site (no park? park?
refusal value?), what the driver does, whether any kernel edit is
needed, the D5 zero-call rows, and the export consequences. If the
answer is "close parks as an `:observe` entry with `:op :close`, and the
kernel-side builders are the mechanical additions r5 declined for
cursor" — check whether the register kernel's close site can carry a
park entry WITHOUT a boundary-opcodes change (the same way cursor will
via the unminted cell, or otherwise); if it cannot, say exactly what D5
does at that site and what it costs.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
