Created-GMT: 2026-09-27 15:32:00 GMT
Created-Local: 2026-09-27 22:32:00 +0700
Coding-Agent: codex
Session-ID: pending

# Task: Pending-link failure policy — owner decision brief

Role: Lead System Architect (decision brief for the owner)

An open owner decision has been parked since the linker epic: the
pending-link failure policy, docs/design/yin.vm.linker.md section 12
bullet 4. Current state (M5 as landed): a pending run is clock-free —
a blocked link retains its input and the caller can (abandon); nothing
times out. Both glm and codex previously suggested composing dao.lease
later if a deadline is wanted; the owner has not ruled.

Task: write a ONE-PAGE decision brief the owner can rule from. Read
yin.vm.linker.md section 12 bullet 4 and the M5 pending state, plus
docs/design/dao.lease.md. Then present:

- Option A: keep the clock-free pending run as the permanent answer
  (composition owns time; a deadline is a lease the caller composes).
  Cost: nothing now; the caller owns timeout policy forever.
- Option B: compose dao.lease into the linker now (a lease per pending
  link). Cost: a new dependency and lease bookkeeping per link.
- Option C: defer with a documented hook (record the seam where a lease
  would compose, decide when a real deployment needs it).

State which option the current design already implements, what each
other option would change, and your recommendation with rationale. Do
not edit files; the brief goes in your final response for the
orchestrator to relay.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
