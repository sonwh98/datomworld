Created-GMT: 2026-10-24 09:02:48 GMT
Created-Local: 2026-10-24 16:02:48 +07
Coding-Agent: claude
Session-ID: 59B70221-8A9E-4D0F-91EF-0A9609418224
# Task: dao.star Mode 1 Phase A — the DaoStream HTTP boundary
Role: DaoStream and Distributed Protocol Engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-24 16:02:48 +07 | Status: active | Rationale: flat-plan claude seat; team.md lists opus-5.5 for complex agentic coding at lower cost than Fable; Fable is held free for the jolt track's review legs

Implement Phase A of the dao.star Mode 1 track in the worktree
`/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-m1` (branch
`dao-star-m1`). Do all your work in that worktree; it has no `collab/`, so
write your findings report to the main tree's collab/ by absolute path
(see Deliverables).

Read first, in this order:
- `/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-m1/docs/design/dao.stream.http.md`
  — the governing spec. Every sentence is a rule. It is subordinate to
  `dao.stream.md`, which wins on any disagreement.
- `/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-m1/docs/design/dao.stream.md`
  — the DaoStream contract (envelopes, deposit admission, complete history,
  granularity, Surfaces, gap semantics).
- `/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-m1/docs/design/dao.lease.md`
  — lease facts vocabulary and the judge; the boundary's Inventory section
  exists for a judge composed inside it.
- `/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-m1/docs/design/dao.star.md`
  — Mode 1 "Flow" steps 1, 5, 6 for how the boundary is consumed (context only;
  the dao.star layer itself is Phase B, not yours).
- The sibling WebSocket boundary, whose deposit model and admission rule this
  spec reuses — follow its structure and host-seam discipline:
  `src/cljc/dao/stream/ws.cljc`, `src/clj/dao/stream/ws/jvm.clj`.
- `src/cljc/dao/stream.cljc` (the contract's implementation), `src/cljc/dao/lease.cljc`,
  and the `src/cljc/dao/host/` seam namespaces.

## Deliverables

1. `src/cljc/dao/stream/http.cljc` — the application-agnostic boundary core,
   host-clean (only the project's `dao.host.*` seams and `dao.stream` itself;
   no host libraries). Per the spec:
   - a constructor taking the ingress destination and its admission declaration
     as data (evict-oldest retention, value domain `:portable-values`, lifetime
     longer than the boundary), plus `:http/max-open` and `:http/max-body-bytes`;
     wiring a reject-mode destination is an assembly defect refused at assembly;
   - the adapter (events in): mint `:http/exchange [incarnation n]` ids unique
     for the egress medium's lifetime, carry the boundary's incarnation, deposit
     `:http/request` (method lower-case keyword, lower-cased header names, query
     and body absent when none), then exactly one terminal event
     (`:http/ended` after an `:http/end` effect performed; `:http/closed` for
     peer close, write failure, out-of-order effect, judge reclaim, boundary
     stop);
   - `egress-step` (effects out): one cursor minted at `:dao.stream/newest`
     before the listener starts; `:http/head` sent once and first; `:http/chunk`
     writes bytes/string-as-UTF-8 and flushes; `:http/end` completes the
     response and the exchange; an effect for an exchange this boundary does not
     hold open is ignored; out of order for an open exchange (chunk before head,
     second head) closes that exchange; an egress `gap` closes every open
     exchange and adopts the recovery cursor; an egress `end`/`transport-error`
     closes every open exchange and the listener;
   - the inventory: `open-exchanges` answering the open ids and idempotent
     `close-exchange`, both transport-owned operations beside `egress-step`,
     exposed for a `dao.lease.md` judge composed inside this boundary;
   - observably-gone: a non-`ok` ingress deposit closes that exchange's socket
     with no synthesized response; a failed socket write closes the exchange and
     deposits `:http/closed`.
   - The step takes no clock and holds no timer.
2. `src/clj/dao/stream/http/jvm.clj` — the JVM listener over http-kit (already a
   dependency; do not edit deps.edn): wire framing, header parsing and size
   limits below the transform (400/413/503 answered there and never deposited);
   holds sockets open; performs effects by writing to the socket; records no
   role and reads no path, token, query or body content.
3. `test/dao/stream/http_test.clj` — TDD (write tests first), covering at least:
   - deposit admission: capacity/evict-oldest; over-limit (`:http/max-open`,
     `:http/max-body-bytes`) answered below the transform and never deposited;
   - exchange ids unique across the egress medium's lifetime; incarnation
     distinguishes a restarted boundary (a stale effect retained from before a
     restart can never match a new socket);
   - the terminal-event rule: exactly one per exchange, after its request event,
     for peer close, write failure, out-of-order effect, close-exchange, and
     boundary stop;
   - effects: head once and first; chunk bytes reach the wire UTF-8; end
     completes; out-of-order closes; foreign egress values (unknown exchange,
     already ended, after end) ignored;
   - inventory: open-exchanges answers open ids; close-exchange idempotent;
     reclaim deposits the terminal event;
   - observably-gone: non-ok ingress deposit closes the socket with no response;
     failed write closes and deposits `:http/closed`;
   - one real-socket round trip over loopback HTTP: a real request lands as an
     ingressed event; performed effects reach the wire as bytes; the response
     completes.
   Tag anything that takes about 5 s or more `^:slow` (JVM-only file, so no
   `dao.test-slow/guard` wrapper needed). Keep every wait bounded.

## Acceptance criteria

- G0 green: `clojure -M:test -n dao.stream.http-test` (from the worktree) and
  the focused subsystem lane `bb test:sub:clj dao.stream` — 0 failures.
- `clj -M:kondo --lint src/cljc/dao/stream/http.cljc src/clj/dao/stream/http/jvm.clj test/dao/stream/http_test.clj` clean.
- Every normative sentence of `dao.stream.http.md` is either implemented,
  covered by a test, or explicitly listed in your findings as host-deferred
  with a reason. The spec's own Deferred list (remote channel, streamed request
  bodies, TLS/CORS/compression) is out of scope by design; anything else you
  defer needs a stated reason.
- cljc core remains host-clean per the host-boundary discipline (see
  `src/dev/host_boundary/closure.clj` and `test/dao/host/gate_test.clj` for the
  gate that later slices enforce; do not edit those files).

## File ownership

You may create and edit only:
- `src/cljc/dao/stream/http.cljc`
- `src/clj/dao/stream/http/jvm.clj`
- `test/dao/stream/http_test.clj`
Do not edit docs, deps.edn, bb.edn, src/dev/, or anything under src/cljd/,
src/cljs/ (Node/cljd listeners are a later slice), or anything owned by the
jolt track (src/cljc/yin/**, src/jolt, src/cljd/yin/**). If a required change
demands expansion, stop and request authorization in your findings instead of
editing.

## Reporting

Write your findings report to
`/Users/sto/workspace/datomworld/collab/1792832568086-stream-engineer-dao-star-m1-phase-a.claude-opus-5-5.findings.md`
(starting with the same header fields as this prompt) when done. Do not stage
or commit anything.

Begin the final response exactly with:
Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and timezone>

Report changed files, exact test/check outcomes (commands and assertion
counts), unresolved concerns, and any incomplete work. Do not claim edits or
tests that did not occur.
