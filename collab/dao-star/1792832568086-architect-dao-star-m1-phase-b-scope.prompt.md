Created-GMT: 2026-10-24 09:02:48 GMT
Created-Local: 2026-10-24 16:02:48 +07
Coding-Agent: claude
Session-ID: C34FEBEA-30A9-4DC6-97C8-75FF6B67F0AD
# Task: dao.star Mode 1 Phase B — scope the session/router/driver layer
Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-24 16:02:48 +07 | Status: active | Rationale: owner routing 2026-10-24: fable open via plain claude CLI for architecture legs; the Phase A boundary implementation is running in parallel on opus-5.5, so Phase B needs a ruling before its brief

Perform a read-only architecture scoping of Phase B of the dao.star Mode 1
track. Mode 1 was ratified by owner ruling 2026-10-24
(`docs/design/dao.star.md` status line); Phase A — the HTTP boundary per
`docs/design/dao.stream.http.md` — is being implemented now in the worktree
`.claude/worktrees/dao-star-m1` by another delegate. Do not edit any file.

Read first:
- `docs/design/dao.star.md` — ratified Mode 1: Flow, Sessions and request
  kinds, Signals, Inputs/order/replay, Liveness, Completion Criteria item 1.
- `docs/design/dao.stream.http.md` — the boundary Phase A is building.
- `docs/design/dao.stream.md` — the contract (envelopes, deposit admission,
  complete history, granularity, OD-4).
- `docs/design/dao.lease.md` and `src/cljc/dao/lease.cljc` — the implemented
  judge/holder halves Phase B must compose.
- `src/cljc/dao/stream.cljc` and the stream constructors available to a
  composition (ring buffer, journal, ws-project — whichever apply).
- The yin.vm parking surfaces, to answer question 2:
  `docs/design/yin.vm.semantic.continuation.md`, `docs/design/dao.await.md`,
  `docs/design/yin.vm.streams-all-the-way-down.md`,
  `docs/design/yin.vm.ucf-transport-tuples.md`, and the corresponding
  `src/cljc/yin/vm/` namespaces (parking, waitsets, UCF rows, FFI stream ops).
  Also `src/cljs/dao/stream/waitset/driver.cljs` as an existing driver shape.

Questions to rule on (give a ruling with reasons and rejected alternatives;
prefer the smallest Phase B that satisfies Completion Criteria item 1 on the
JVM):

1. **Namespace layout**: where the dao.star layer lives
   (`dao.star` vs `dao.star.router`/`dao.star.driver`/`dao.star.facts`/...),
   consistent with the repo's existing ns granularity.
2. **The parked continuation**: Completion Criteria item 1 says "A `yin.vm`
   agent receives a Datastar input through the HTTP boundary's ingress stream,
   ... parks, and resumes on the next input". Rule concretely what the driver
   holds and steps: (a) a real `yin.vm` CESK continuation parked on a stream
   wait, resumed by the driver observing the input — name the exact existing
   integration point (waitset? UCF rows? FFI op?) and whether it is buildable
   today without UCF v1; or (b) a composition-supplied resumable abstraction
   (a step function over parked state) with yin.vm as a documented later
   instance. If (a) is buildable, is it Phase B or a Phase C? What is the
   honest reading of "A yin.vm agent" for the criterion to pass?
3. **Session-facts medium**: which stream constructor a composition wires for
   it (evict-oldest capacity per Liveness "Sizing"), and who owns enforcing the
   three-fact grammar.
4. **Router**: the classification pass algorithm exactly per "Observation
   order" (read ingress first, drain session-facts and lineage-lease cursors to
   blocked, then classify), the co-location constraint's implementation shape,
   and what the router forwards for terminal events.
5. **Lease compositions**: for the exchange lease (judge composed with the
   boundary's `close-exchange` as reclaim and `open-exchanges` as inventory;
   unsolicited grant per `:http/request`) and the lineage lease (grantor = peer
   holding the parked record; driver observes grant before consuming or
   projecting, renews at under half the duration, stops at its bound, releases
   on halt) — name the exact `dao.lease.cljc` functions/constructor calls a
   composition uses, and what driver-side holder state threads where.
6. **The projection seam**: the signature of the composition-supplied pure
   projection function (parked state → SSE wire text), where the Datastar
   vocabulary lives, and how subscribe/stale re-projections reuse it without
   stepping.
7. **Signals decode**: the pure projection from `:http/query`/`:http/body` to a
   signals map, derived from the retained ingress stream.
8. **Test plan**: map every clause of Completion Criteria item 1 (there are
   ~10 semicolon-separated scenarios) to a concrete JVM test over the real
   boundary; say which need real loopback sockets and which run on in-memory
   media, and which are `^:slow` candidates.
9. **File ownership proposal** for the Phase B implementer brief, plus a risk
   list (what is most likely to go wrong, and what the brief must pin down).

Do not edit files. Begin the final response exactly with:
Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and timezone>

Then report the nine rulings, each with reasons and rejected alternatives,
and confirm which completion-criteria clauses your test plan covers fully,
partially, or defers.
