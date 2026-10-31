Created-GMT: 2026-09-27 07:35:00 GMT
Created-Local: 2026-09-27 14:35:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (dao.stream.remote slice 1)

# Task: dao.stream.remote Implementation — Slice 1 (dao.stream.middleware)

Role: Stream & Network Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master @ 431a269c,
clean tracked tree; slice 0 is committed).

Implement Slice 1 exactly as the plan defines it. Read first, in order:
- docs/design/dao.stream.middleware.md — the COMPLETE specification you
  are implementing (middleware value, operation map, apply-request,
  wrap, the position rule, the four prohibitions, failure inside a
  transform, the gate with its decision-read lifecycle, present, the
  encryption and metering exemplars, invariant compliance)
- docs/design/dao.stream.remote.implementation-plan.md section 3,
  slice 1 row: "dao.stream.middleware: apply-request, wrap, gate,
  present, a metering exemplar, an index-interpreter exemplar
  publishing decisions. src/cljc/dao/stream/middleware.cljc, tests."
  Proof: "Position rule under a value cipher over a ring buffer; a gate
  with the channel allow-list refuses with :dao.stream/refused; a gate
  reads the latest decision through a capacity-1 medium after eviction;
  a filter cannot be expressed."
- docs/design/dao.shibi.md (How it plugs in — the verify adapter
  contract the gate honors)
- src/cljc/dao/stream.cljc (the contract you build on; slice 0's
  :dao.stream/refused is available)

Work items:
1. NEW src/cljc/dao/stream/middleware.cljc implementing exactly the
   spec: the middleware map shape, apply-request, wrap (outermost
   first; short-circuit on in-outcomes; out transforms innermost
   outward; descriptor/close! delegation), the position rule and
   prohibitions as structural properties (they are enforced by the
   design and pinned by tests, not runtime checks), the failure
   markers (:dao.stream.middleware/undecodable, :invalid-value), gate
   (with the exact decision-read lifecycle: mint once at wrap, next
   once per operation, one recovery read after gap, cursor re-mint on
   other non-ok, ended/none markers, nil pass-through, refused
   short-circuit with reason), present, the encryption and metering
   exemplars, and the allow-list policy exemplar (keyed on
   :dao.stream.remote/channel in ctx).
2. An index-interpreter exemplar publishing decisions (per the slice
   row): a small fold-over-streams interpreter that appends derived
   decisions to a capacity-1 medium — test-support quality, placed in
   the test file or a dev namespace per your judgment within the
   allowed files.
3. NEW tests (test/dao/stream/middleware_test.cljc) covering the proof
   row: position rule under a value cipher over a ring buffer (cursors
   and outcomes untouched through encipher/decipher); the allow-list
   gate refusing with :dao.stream/refused; latest-decision-after-
   eviction reads; a filter cannot be expressed (document/pin via the
   position rule's test); plus: short-circuit ordering (out transforms
   of outer middlewares still run), undecodable marker on the way out,
   invalid-value on the way in, decision-read lifecycle states
   (none/ended/gap-recovery/re-mint), metering emission, and
   present's credential association.

Constraints:
- NEW files: src/cljc/dao/stream/middleware.cljc,
  test/dao/stream/middleware_test.cljc. Touch no other file. If an
  existing file must change (e.g. dao.stream.md contract gaps), STOP
  and report BLOCKED with the specific gap.
- The spec is the contract; where it is explicit, implement exactly.
  Where genuinely ambiguous, choose the minimal reading, note it in
  your report, and do not invent features.
- Pure ASCII, <= 80 columns on every added line; cljstyle and kondo
  clean; no commit/stage/checkout/reset/stash; no leftover diagnostics.
- Verify: JVM full suite green (current baseline 2,217/182,889/0 plus
  your new tests), Node green, Dart green. Sequential, solo. Exact
  counts.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
