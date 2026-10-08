Created-GMT: 2026-09-27 18:15:00 GMT
Created-Local: 2026-09-28 01:15:00 +0700
Coding-Agent: codex
Session-ID: pending

# Task: Ruling — the dao.lease serving sketch vs the copy-path retirement

Role: Lead System Architect

Slice 5 of the dao.stream.remote implementation (REPL service + the
copy-path retirement) is BLOCKED on one consumer the plan's section 5
fate list missed: test/dao/lease_composition_test.cljc contains a live,
active JVM-only test (served-connection-real-close-path-sketch-test,
gated only :cljd-nil/:clj) that directly drives
dao.stream.serving/make-serving, start!, step!, and state as a
served-connection-lifetime sketch for dao.lease's reclaim path. Slice 5
deletes dao.stream.serving.

Read first:
- test/dao/lease_composition_test.cljc (the sketch test and its
  surroundings -- what it actually proves about lease reclaim)
- docs/design/dao.lease.md (lease subjects: served entries and relay
  pairs are named; the reclaim path)
- docs/design/dao.stream.remote.md section 6 (reclaim: a reflection
  marked gone is how a remote holder observes it) and section 5 (the
  request-and-response service convention; lease-governed served
  entries)
- docs/design/dao.stream.remote.implementation-plan.md section 5 (the
  fate list: dao.stream.serving retired; its consumers yin.repl.serve
  and dao.jing.remote retired by slices 4-5)

Rule on the sketch's fate, and prescribe exactly:
1. Option A: migrate the sketch onto the mirror/reflection composition
   -- prescribe the replacement test's shape (what composes in its
   place: a lease-governed served entry in a mirror table? a reflection
   observing reclaim via not-found/gone? which assertions carry over),
   keeping the lease-reclaim property the sketch exists to demonstrate.
2. Option B: drop the sketch as stale (the copy-model serving example
   no longer has a subject) -- prescribe the dao.lease.md edit if its
   examples reference the pattern, and whether the reclaim-lifetime
   property moves into a slice-7+ proof row.

Choose the option you judge correct, prescribe the concrete replacement
(whoever implements slice 5 will apply it verbatim), and keep it
minimal -- this is one sketch test, not a dao.lease redesign.

Do not edit files. Cite file:line evidence.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
