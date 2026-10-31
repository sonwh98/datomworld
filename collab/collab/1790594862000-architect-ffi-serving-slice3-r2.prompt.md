Created-GMT: 2026-09-28 11:34:52 GMT
Created-Local: 2026-09-28 18:34:52 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a0e7c5-6b35-7e01-9081-541da3b78e92 (resumed)
# Task: Architect design — Slice 3, round 2: reconcile P1 with the signed-off remote spec
Role: Lead System Architect
Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-28 18:34:52 +07 (+0700) | Status: active | Rationale: same thread; conflict between your P1 and the design's stated intent goes back to the Architect, not adjudicated by the orchestrator

Your round-1 design (collab/1790594862000-architect-ffi-serving-slice3.gpt-6-sol.findings.md) rates as P1 that
remote/mirror-step drops an answer refused with full and still advances its cursor, and prescribes retaining the
request+answer in caller-owned mirror state (sub-slice 3.1).

That appears to contradict the fable-signed spec docs/design/dao.stream.remote.md, which states the behaviour as
intended:
- line ~75: "mirror step: the pure step ... It holds no state between calls beyond the channel reader's cursor."
- section 2.5 Loss and resend: descriptor/cursor/next are re-sent after resend-after k while unanswered (idempotent,
  so a recomputed answer is equally true); "append! is never re-sent: deduplication and correlation are the
  payload's (OD-2, accepted)"; abandoned appends surface as append-unknown on channel loss.
- remote.cljc write-answer! docstring: "any other refused write leaves the request unanswered."

Rule, read-only, no edits:
1. Is the P1 a real defect given 2.5's resend recovery for the idempotent ops? Consider specifically append!: a
   served append that applied but whose answer was refused full — does the spec's append-unknown / payload-owned
   dedup path already cover it, or does the asker wait forever (no channel loss, no resend)? Cite code.
2. If real: is the correction a spec change (mirror gains state — which contradicts a signed-off statement and
   so needs owner sign-off) or can it be satisfied within the spec (e.g. the serving composition, not the mirror,
   retains; or the asker's resend covers it)? Prefer the within-spec option if one is sound.
3. Restate sub-slice ordering accordingly: is 3.1 still first, dropped, or moved into the export-binding slice?
4. Confirm whether sub-slice 3.2 (export binding) can be implemented now with the owner-policy items (channel
   adapter, lease durations/cadence, retention capacities, handler-invocation authority) injected as opts, deferring
   those decisions — or whether any of them must be decided before 3.2.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Then answer 1-4 concisely, with file:line evidence.

- Status-Event: 2026-09-28 18:36:18 +07 | Model: gpt-6-luna | Status: superseded | Rationale: codex resume without -m ran luna instead of sol; answer preserved in -r2.gpt-6-luna.findings.md
- Model: gpt-6-sol | Assigned: 2026-09-28 18:36:18 +07 | Status: active | Rationale: re-run pinned with -m gpt-6-sol (log -r2b)
