Created-GMT: 2026-10-24 11:15:34 GMT
Created-Local: 2026-10-24 18:15:34 +07
Coding-Agent: claude
Session-ID: C34FEBEA-30A9-4DC6-97C8-75FF6B67F0AD (resumed)
# Task: dao.star Mode 1 — ruling on client-initiated close detection under http-kit
Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-24 18:15:34 +07 | Status: active | Rationale: continuation of your Phase B scoping session; the question is the weighing you own

Read-only. Rule on one question raised by the Phase B implementation. Do not
edit files.

Context: Phase A (`src/cljc/dao/stream/http.cljc` + `src/clj/dao/stream/http/jvm.clj`
in worktree `/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-m1`)
is implemented and verified. Phase B (dao.star layer) likewise. Both report
green. One finding from the Phase B delegate:

1. **http-kit never reports a client-initiated close of an idle streaming
   exchange.** Its minimal repro (no dao code) showed no `on-close` after
   client FIN or RST on a response that sent head + one chunk and holds open;
   40 further `kit/send!` calls all answered true. So `peer-closed!` is
   unreachable by a real client disconnect through the current `jvm.clj`.
2. The seam contract in `http.cljc` says a seam function reports failure by
   throwing or answering false; `kit/send!` on a dead peer's channel does
   neither (buffered writes "succeed").
3. `dao.star.md` Liveness says: "Keep-alive is the responder's. On its tick
   it may append an `:http/chunk` carrying an SSE comment line. That chunk is
   bytes for the wire, so the host discovers a dead peer by writing; the
   renewal is evidence for the judge. Neither stands in for the other. A
   peer's close or a failed write is deposited as `:http/closed`..."
4. The spec's designed backstop: an exchange nobody answers lapses by
   `:silence` via the judge (`close-exchange` reclaim), and Datastar
   reconnects. The Phase B loopback test therefore exercises the
   closed-socket criterion via boundary-side `close-exchange`, not a real
   client disconnect.

The question: with a host whose send neither throws nor answers false and
whose `on-close` does not fire for an idle half-dead streaming exchange, what
is the ruling?

Candidates we see (you may add others):
(a) **Accept the lease as the detector.** The judge's `:silence` reclaim is
    the designed backstop; detection latency is bounded by the exchange
    lease's duration plus renewal policy; the criterion "a closed socket is
    observed as `:http/closed`" is satisfied through the reclaim path, and
    the host reality (http-kit buffers) is recorded as an Honest Limit.
    Keep-alive chunks stay as renew-evidence, not as detectors.
(b) **Fix the host edge.** Amend `jvm.clj` (a Phase A file) so a write's
    failure surfaces — e.g. deref the promise `kit/send!` returns with a
    bounded timeout per write, or force a real flush — changing the seam's
    timing contract.
(c) **Fix the seam contract.** Let `:write!` be allowed to answer a
    third value ("unknown") that the core maps to no-outcome, leaving
    detection entirely to the judge.

Rule with reasons and rejected alternatives, addressing: what
`dao.stream.http.md`'s "a failed socket write closes the exchange and
deposits `:http/closed`" means when the host cannot tell; whether the
completion criterion "a closed socket is observed as `:http/closed`" passes
under your ruling and what its honest test is; and what, if anything, the
Phase A `jvm.clj` must change (name the exact change if so). Prefer the
ruling that changes the least text.

Begin the final response exactly with:
Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and timezone>
