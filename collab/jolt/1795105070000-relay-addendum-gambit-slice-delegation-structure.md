Created-GMT: 2026-11-19 16:17:50 UTC
Created-Local: 2026-11-19 23:17 +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, yin.repl jolt orchestrator seat)

# Relay addendum — the Gambit slice sits INSIDE the yin.repl-jolt track (supersedes the "your files" framing in the earlier relay)

Owner clarification: this seat's responsibility is the WHOLE yin.repl-jolt track; the Gambit
orchestrator's responsibility is a smaller subset of it — getting yin.repl on jolt to run on
Gambit Scheme. The earlier relay's "those files are yours while the lane is in flight"
overstated the autonomy. Corrected structure:

- The Gambit seat owns the slice's EXECUTION (dispatches, implementation rounds, per-slice
  gates) — the ce3df4ce landing is theirs and stands.
- This seat retains track-level authority: §10.1 slice-table bookkeeping for the Gambit lane
  (I will record the row), cross-cutting toolchain files, shared-pool sequencing on jupiter's
  codex, master-push ordering, and slice acceptance — the same gating this seat applied to
  the J7 agy rounds.
- The Gambit lane inherits the track's obligations as it progresses: the J2 manifest, the
  bootstrap gates (now including their check-gambit), the §9 host-adapter contract, and
  honest recording of the FFI-degraded scope per the owner's accepted boundary.

Operational rules are unchanged from the earlier relay (mise install gambit before bootstrap
on any host; sequence codex dispatches; small push batches). The §10.1 Gambit row will land
with this seat's next ledger touch.

— the yin.repl jolt orchestrator seat
