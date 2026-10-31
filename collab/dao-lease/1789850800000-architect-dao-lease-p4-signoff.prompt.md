Created-GMT: 2026-09-19 22:02:00 GMT
Created-Local: 2026-09-20 06:02:00 +07 (Indochina Time)
Session-ID: 01a0bada-797f-71b0-9c8f-84ea65c6beba (resumed — your Phase 3 sign-off thread)
# Task: architect sign-off — dao.lease Phase 4 (composition) delta

You granted sign-off on Phases 1–2 and Phase 3. Phase 4 (the composition
constructors, the test-tree stepped tick driver, and the three use-case
sketches) has since been built by glm-5.3-flash, reviewed by fable-5-1
across three gates (r1: 1 P1 — nil :self made a nil resolver answer read
as grantor-authored — plus 5 P2; r2: the capacity refusal the reviewer had
prescribed was retracted by the reviewer itself as unsound, and the real
envelope-unwrap gap was surfaced; r3: all confirmed, ready), and verified
on all three hosts (JVM 1527/168550/0; CLJS 1443/38414/0; CLJD green apart
from the 29 pre-existing voxel failures). The plan's §5/§6 record all
phases built; the unwrap seam is a §6 owed row (option 2: recorded, not
built).

Under review — the Phase 4 delta in this working tree (the composition
section of `lease.cljc`, the tick driver and composition tests), against
the plan's §4.4 and C1–C8, and `dao.lease.md`'s *Composition duties*.

Sign-off questions:
1. Do the constructors enforce the composition duties at assembly —
   medium declarations, attribution, reclaim, tolerance, cadence, and the
   durable prerequisites with the process-scoped fallback?
2. Is the deliberate non-derivation of the capacity/budget relation (S3
   stays a sizing relation, documented) sound, given the silence-
   suppression rule covers truncated drains?
3. Do the sketches pin the C1/C8 wiring meaningfully, with the envelope
   gap honestly recorded rather than papered over?
4. Any inconsistency with the signed Phases 1–3?

Do not relitigate closed findings. End with exactly one line:
`SIGN-OFF: <granted | denied> — <one sentence>`
