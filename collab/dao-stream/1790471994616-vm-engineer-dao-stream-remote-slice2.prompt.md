Created-GMT: 2026-09-27 09:20:00 GMT
Created-Local: 2026-09-27 16:20:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (dao.stream.remote slice 2)

# Task: dao.stream.remote Implementation — Slice 2 (remote core)

Role: Stream & Network Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master @ ea660cd1,
clean tracked tree; slices 0-1 are committed).

Implement Slice 2 exactly as the plan defines it. Read first, in order:
- docs/design/dao.stream.remote.md sections 2 through 2.5 (the design:
  mirror and reflection; 2.1 wire shapes incl. the three protocol
  errors; 2.2 the remote descriptor; 2.3 the mirror step with its
  four-step order and the budget chase; 2.4 the reflection: attach!,
  the link, drain, the per-operation answer rules, protocol errors at
  the reflection, channel loss, refused, declared nature; 2.5 loss and
  resend) — this is the contract you implement
- docs/design/dao.stream.remote.implementation-plan.md section 3,
  slice 2 row: "dao.stream.remote core: mirror-step, link, reflection,
  remote descriptor dispatch, the protocol errors.
  src/cljc/dao/stream/remote.cljc, tests." Proof: "The toy over two
  in-process ring buffers as the channel; gap from an evicting source
  crosses verbatim with the source's cursor; blocked then ok on
  re-ask; not-found marks gone; no-surface does not; the descriptor
  answer carries the surface; a kept cursor from a reflection is
  accepted by the source handle."
- docs/design/dao.stream.middleware.md (apply-request is the mirror's
  only handle path; your slice-1 gate work is load-bearing here)
- docs/design/dao.stream.md (the contract the reflection implements)
- src/cljc/dao/stream/middleware.cljc (apply-request; wrap for any
  entry-side middleware in tests)

Work items:
1. NEW src/cljc/dao/stream/remote.cljc implementing exactly the spec:
   mirror-step (the four-step order, table lookup, no-surface check,
   descriptor answers with :dao.stream.remote/surface, apply-request
   with the channel context, the budget chase under
   :dao.stream.remote/more, oversize per 2.1), the link (attach probe,
   drain-to-blocked filing, outstanding ids, resend-after counting,
   channel loss), the reflection handle (all six operations per 2.4,
   protocol-error translation, gone semantics, refused verbatim,
   declared nature incl. :closable learning, excluded outcomes), and
   remote descriptor dispatch (attach! integration per
   dao.stream.md's deferred-confirmation clause).
2. NEW test/dao/stream/remote_test.cljc covering the proof row
   exhaustively: the two-ring-buffer toy; verbatim gap crossing with
   the source's cursor; blocked-then-ok on re-ask; not-found marks
   gone; no-surface does not; descriptor answer carries the surface; a
   kept cursor from a reflection is accepted by the source handle.
   Plus: the budget chase (k>1 and the ignore-budget-is-correct rule),
   oversize, drain filing and id-dropping, resend-after on an
   unreliable toy channel, append-unknown on channel end, refused
   verbatim, the declared-nature learning, close! semantics.

Constraints:
- NEW files only (remote.cljc, remote_test.cljc). Touch no other file.
  The channel for tests is two in-process ring buffers (the toy); ws/
  UDP/pair adapters are later slices. If the spec requires a
  dao.stream.md change, STOP and report BLOCKED with the gap.
- The spec is the contract. Where explicit, implement exactly. Genuine
  ambiguities: minimal reading, noted in your report.
- Pure ASCII, <= 80 columns on every added line; cljstyle and kondo
  clean; no commit/stage/checkout/reset/stash; no leftover diagnostics.
- Verify: JVM full suite green (current baseline 2,234/183,020/0 plus
  your new tests), Node green, Dart green. Sequential, solo. Exact
  counts.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
