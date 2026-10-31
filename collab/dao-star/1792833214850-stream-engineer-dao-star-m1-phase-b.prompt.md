Created-GMT: 2026-10-24 09:13:34 GMT
Created-Local: 2026-10-24 16:13:34 +07
Coding-Agent: claude
Session-ID: 59B70221-8A9E-4D0F-91EF-0A9609418224 (resumed)
# Task: dao.star Mode 1 Phase B — the session/router/driver layer
Role: DaoStream and Distributed Protocol Engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-24 16:13:34 +07 | Status: active | Rationale: same seat as Phase A (session resumed for context); team.md lists opus-5.5 for complex agentic coding

Implement Phase B of the dao.star Mode 1 track in the same worktree
`/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-m1` (branch
`dao-star-m1`). Phase A — the HTTP boundary per `dao.stream.http.md` — is
landed in that worktree (uncommitted) and verified by the orchestrator:
`dao.stream.http-test` 11 tests / 86 assertions / 0 failures;
`bb test:sub:clj dao.stream` 36 ns / 514 tests / 0 failures; kondo clean.

Read first:
- `/Users/sto/workspace/datomworld/collab/1792832568086-architect-dao-star-m1-phase-b-scope.claude-fable-5-1.findings.md`
  — the architect's Phase B scoping. Its nine rulings are binding for this
  task; where this brief and the findings disagree, this brief wins.
- `docs/design/dao.star.md` — the ratified Mode 1 spec (Flow, Sessions and
  request kinds, Signals, Inputs/order/replay, Liveness, Completion Criteria
  item 1).
- `src/cljc/dao/stream/http.cljc` — the Phase A core you compose against.
- `src/cljc/dao/lease.cljc` and `docs/design/dao.lease.md`.
- `src/cljc/dao/await.cljc` — the parked-continuation surface.
- `docs/design/dao.stream.md` — the underlying contract.

## Architecture (per the architect's rulings — binding)

- Four namespaces, each small: `src/cljc/dao/star/facts.cljc`,
  `src/cljc/dao/star/signals.cljc`, `src/cljc/dao/star/router.cljc`,
  `src/cljc/dao/star/driver.cljc` (a `dao.star.lease` split is optional; only
  if `driver` would pass ~400 lines).
- **The parked continuation is a real `yin.vm` CESK continuation** via
  `dao.await/run` and `dao.await/resume`; no UCF v1 and no Mode 2 code
  (no custody, export, or migration). One VM per session, never one VM for
  all sessions. The **driver does all I/O**: the VM program is pure
  sequential logic that reads its input medium and emits model values on a
  view medium; the driver drains the view medium, mints the successor
  occurrence, appends the consumption fact, and only after that append's
  `:dao.stream/ok` projects. The program never appends egress effects.
- **Fact-before-UI ordering is tested**: a test must show no UI carrying the
  successor id is projected when the fact append does not answer `ok`.
- The session-facts medium is a `dao.stream.ringbuffer` the composition
  creates (evict-oldest, declared capacity). Facts are built only through
  `dao.star.facts` constructors (`root`, `consumption`, `ended`);
  `valid-fact?` guards readers; the router and lineage resolver treat a
  fact failing `valid-fact?` as noise (ignore, never throw).
- **Router pass order is exactly**: read ingress with its own cursor to
  `blocked` (collecting a `gap` marker), then drain the session-facts cursor
  to `blocked` folding via `facts/index-step`, then drain the lineage-lease
  cursor to `blocked` folding `:lapsed` records, then classify what was
  read. Classification is a pure `classify` over index + request; unknown is
  judged only after both drains. The router constructor asserts co-location
  of the facts and lineage-lease handles (direct, non-reflection) at
  assembly, throwing `ex-info` with `:refused` otherwise.
- **Lease compositions** use `dao.lease.cljc` entry points exactly as the
  findings name them (`make-judge`, `make-holder`, `author-grant`,
  `renewal-interval`, `observe-grant`, `due-to-renew?`, `observe-renewal`,
  `at-bound?`, `release`); size durations via `renewal-interval` rather than
  hand-picked numbers; reclaim for the exchange lease is the Phase A
  `close-exchange` (already idempotent, answers boolean).
- **Projection seam**: `(project-fn occurrence-id model) →` the complete SSE
  wire text; the composition supplies it; the root begin page is a separate
  supplied `begin-fn`. No `datastar` namespace under `src/` — the reference
  SSE projection lives in the test namespace. Re-projection (subscribe,
  stale) applies `project-fn` to the stored latest
  `{:occurrence O :model m}` without resuming the VM.
- **`dao.star.signals/decode`** is pure, maps an `:http/request` event to a
  signals map, reads the `datastar` query parameter for GET/DELETE and the
  JSON body otherwise, and answers `{}` (never throws) on missing or
  malformed input. Use the JSON parser the repo already uses on the JVM —
  check `deps.edn` and existing usage before choosing.
- The driver, per session and per the spec's Liveness section: observes its
  grants before consuming or projecting; renews the exchange lease and the
  lineage lease at under half the duration from its own control flow;
  renews on each consumed input and on its tick while a subscription is
  open; **stops at its bound** (consumes and projects nothing past it; an
  input arriving then goes to the root program as begin); releases on halt.
  A driver whose cursor answers `gap` on any medium forgets its
  subscriptions and renews nothing. A `:lapsed` record for one of its
  exchanges drops that exchange.

## The Phase A API you compose against (verified)

`make-boundary` (config: `:ingress`, `:ingress-admission`, `:egress`,
`:http/max-open`, `:http/max-body-bytes`, optional `:http/incarnation`),
`accept!` (host adapter entry), `peer-closed!`, `egress-step`,
`open-exchanges`, `close-exchange` (idempotent reclaim, answers boolean),
`stop!`, `on-stop!`. Exchange ids are `[incarnation n]` vectors.

## Deliverables

- The four (or five) `src/cljc/dao/star/*.cljc` namespaces.
- Tests under `test/dao/star/` implementing the architect's test plan (its
  section 8 table): all ten completion-criteria clauses of
  `dao.star.md` §Completion Criteria item 1, on the JVM. Two clauses need
  real loopback sockets (agent receive/answer/resume end-to-end over the
  Phase A boundary; closed socket observed as `:http/closed`); the rest run
  on in-memory ring buffers and a fake boundary implementing
  `open-exchanges`/`close-exchange` and the two media. Silent-lapse tests
  drive ticks through the tick stream, never a real clock. Tag anything
  about 5 s or more `^:slow`; keep every wait bounded; a tight-loop stress
  variant for the just-published-token clause is a `^:slow` candidate.
- TDD: write the tests first where practical; the Phase A report noted
  one-pass implementation, which the reviewer flagged as a process note —
  do better here where you can without ceremony.

## Acceptance criteria

- G0 green: `clojure -M:test -n dao.star.facts-test dao.star.signals-test dao.star.router-test dao.star.driver-test` (or the file's actual ns names), and `bb test:sub:clj dao.stream` still 0 failures (the boundary tests keep passing).
- `clj -M:kondo --lint` clean on every new file.
- Every clause of Completion Criteria item 1 is covered by a named test, or
  listed in your findings as deferred with a reason.
- No code under `src/` mentions Datastar, UCF, custody, or export.

## File ownership

You may create and edit only:
- `src/cljc/dao/star/*.cljc`
- `test/dao/star/*_test.clj`
Do not edit the Phase A files (`src/cljc/dao/stream/http.cljc`,
`src/clj/dao/stream/http/jvm.clj`, `test/dao/stream/http_test.clj`),
`dao.lease`, `dao.stream.*` (other than requiring them), `dao.await`,
`yin.vm.*`, docs, deps.edn, bb.edn, src/dev/, or anything under src/cljs/,
src/cljd/, src/jolt. If a required change demands expansion, stop and
request authorization in your findings instead of editing.

## Reporting

Write your findings report to
`/Users/sto/workspace/datomworld/collab/1792833214850-stream-engineer-dao-star-m1-phase-b.claude-opus-5-5.findings.md`
(if that write is denied, say so and put the full report in your final
response — the orchestrator will promote it). Do not stage or commit.

Begin the final response exactly with:
Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and timezone>

Report changed files, exact test/check outcomes (commands and assertion
counts), unresolved concerns, and any incomplete work. Do not claim edits or
tests that did not occur.
