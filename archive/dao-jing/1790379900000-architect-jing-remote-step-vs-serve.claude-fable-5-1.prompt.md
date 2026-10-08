Created-GMT: 2026-09-25 23:45:00 GMT
Created-Local: 2026-09-26 06:45:00 +0700
Coding-Agent: claude
Session-ID: resume-of-05ce85cc-7cbb-407f-b445-1e9756ad2e35

# Task: is dao.jing.remote.step redundant under dao.stream.serve? (design question, read-only)

Role: Lead System Architect (read-only; do not edit files)

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-26 06:45 +0700 | Status: active | Rationale: owner directive "yes, ask fable"; resumes your dao.stream.serve design session

## Owner statements (verbatim quotes)

"dao.jing.remote.step is probably unnecessary or will be redundant because of there was dao.stream design specs that mechanically expose any dao.stream implementation via websocket or UDP. where is that spec?"
"yes, ask fable"

## Orchestrator framing (my reading, not the owner's words; challenge it)

The spec the owner means is YOUR dao.stream.serve design (draft text in
collab/1790335900000-architect-dao-stream-serving-spec.claude-fable-5-1.findings.md,
revised under the P2P invariant in collab/1790339700000-architect-dao-stream-serve-
invariant-review.claude-fable-5-1.findings.md, with the codex and GLM-5.3 consensus in
collab/1790339400000-...-q1-q7-mob-consensus.orchestrator.findings.md and
collab/1790342800000-...-invariant-mob-consensus.orchestrator.findings.md). It is not
yet in docs/design/. The owner suspects dao.jing.remote.step (the stepped, non-blocking
dao.jing content client) becomes unnecessary once any dao.stream handle can be exposed
or proxied over WebSocket or UDP. I do not know whether that is right.
Facts I checked: the only source user of dao.jing.remote.step is
src/cljc/dao/jing/remote/async.cljc (async hydration for B-tree content, dao.jing.md
around lines 558-580 and dao.data.btree.md 5.4). The linker's M3 (just implemented by
Opus) does NOT use it: it talks :jing/get-content directly over dao.stream.rpc because
remote.step hashes and decodes payloads itself, which would break M2's required order
(byte cap, then address check, then decode). The linker spec (docs/design/yin.vm.linker.md
section 6 and the M3 paragraph) currently names remote.step as an option: "over
dao.jing.remote.step, or ... over dao.stream.rpc on a ring-buffer pair". I have not
verified how remote.step or serve compare beyond that.

## Read first
- src/cljc/dao/jing/remote/step.cljc, remote.cljc, remote/async.cljc, and the
  dao.jing.remote namespaces beside them
- docs/design/dao.jing.md (Async hydration and the stepped client status, ~558-580),
  docs/design/dao.data.btree.md section 5.4
- docs/design/yin.vm.linker.md section 6 (linking is a stream exchange) and section 9 M3
- the Opus M3 report (the remote.step deviation):
  collab/1790358000000-vm-engineer-linker-m3-stepped-core.claude-opus-5-5.report.md
- your own serve design and consensus files above; src/cljc/dao/stream/rpc.cljc

## What to produce
1. What does remote.step provide that a serve proxy handle over dao.stream does not,
   and vice versa? Separate what is a STREAM-level concern (which serve covers:
   sessions, cursors, outcomes, transport) from what is a JING-level protocol (put,
   get, materialize, the verify-unissued/verify-issued phases and materialization
   records) that would live ABOVE a served stream regardless.
2. For each consumer, is remote.step needed under serve? (a) the linker (M3 already
   bypasses it), (b) B-tree async hydration (dao.jing.remote.async), (c) hosts with
   nothing to wait with, cljs and cljd, which is the reason the docstring gives for
   the stepped shape. Does a serve proxy handle satisfy (c) given the contract's
   no-waiting rule, or does a stepped client still have a job?
3. Your recommended disposition for dao.jing.remote.step: retire, shrink to a thin
   jing-level client over a dao.stream handle, or keep. What would have to change in
   dao.jing.md, dao.jing.remote.async, and the linker spec (drop the remote.step option
   and say the linker speaks over a dao.stream handle, served or proxied?).
4. Does serving the content stream through the proxy change the order the linker
   needs (byte cap before hashing before decoding)? Say what the proxy does to payload
   bytes and where verification must happen.
5. Anything in the serve design that should change because of this, and what you would
   sequence first. Distinguish defects from deferred work. If you are uncertain, say so
   and list what evidence would settle it.
Do not assume the owner's suspicion is right; hold on evidence.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
