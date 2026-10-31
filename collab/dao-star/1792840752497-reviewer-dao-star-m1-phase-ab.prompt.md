Created-GMT: 2026-10-24 11:19:12 GMT
Created-Local: 2026-10-24 18:19:12 +07
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: dao.star Mode 1 — adversarial review of Phase A+B (HTTP boundary and session/router/driver layer)
Role: Reviewer (adversarial)
Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-24 18:19:12 +07 | Status: active | Rationale: independent family (GPT) from the Claude implementer; continuity with the family that reviewed the dao.star spec in rounds 1-5

Read-only adversarial review of the uncommitted implementation in the
worktree `/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-m1`
(branch `dao-star-m1`, base master f6856bbc). All new files are untracked;
`git status --short` there lists them. Do not edit, stage, or commit.

Read first:
- The governing specs, as amended in the worktree: `docs/design/dao.stream.http.md`
  (note the new Honest-Limit paragraph after "Observably gone"),
  `docs/design/dao.star.md` (Mode 1: Flow, Sessions and request kinds,
  Signals, Inputs/order/replay, Liveness — note the new buffering sentence
  in Keep-alive and the Sizing note), `docs/design/dao.stream.md` (the
  contract; wins on disagreement), `docs/design/dao.lease.md`.
- The implementer's reports and the architect's rulings:
  `/Users/sto/workspace/datomworld/collab/1792832568086-stream-engineer-dao-star-m1-phase-a.claude-opus-5-5.findings.md`,
  `/Users/sto/workspace/datomworld/collab/1792833214850-stream-engineer-dao-star-m1-phase-b.claude-opus-5-5.findings.md`,
  `/Users/sto/workspace/datomworld/collab/1792832568086-architect-dao-star-m1-phase-b-scope.claude-fable-5-1.findings.md`,
  `/Users/sto/workspace/datomworld/collab/1792840534057-architect-dao-star-m1-client-close.claude-fable-5-1.findings.md`.
- The code: `src/cljc/dao/stream/http.cljc`, `src/clj/dao/stream/http/jvm.clj`,
  `src/cljc/dao/star/{facts,signals,lease,router,driver}.cljc`,
  `test/dao/stream/http_test.clj`, `test/dao/star/*_test.clj`.

Verification already run by the orchestrator (do NOT rerun suites; spend
your budget on static analysis):
- `clojure -M:test -n dao.stream.http-test`: 11 tests / 86 assertions / 0 failures.
- `clojure -M:test -n` on the six dao.star test namespaces: 34 tests / 590 assertions / 0 failures.
- `bb test:sub:clj dao.stream`: 36 ns / 514 tests / 0 failures.
- `bb test:changed` (G2): clj 76, cljs 45, cljd 25 — all lanes 0 failures.
- `clj -M:kondo --lint` on all new files: clean.

Review focus, in order:
1. **Spec conformance, sentence by sentence**: `http.cljc` and `jvm.clj`
   against `dao.stream.http.md`; the dao.star layer against `dao.star.md`
   Mode 1 (the observation-order proof, fact-before-UI ordering, lease
   disciplines, the five request kinds, stale one-shot, signals as
   unverified claim).
2. **The delegate's flagged decisions** (weigh each: defect, or acceptable
   with a stated reason?): admission key `:lifetime :outlives-boundary`
   (invented; spec says the lifetime must exceed the boundary's but the ws
   admission shape has no key); incarnation from config or fresh uuid;
   stricter-than-spec closes (non-integer status, non-string/bytes chunk,
   end-before-head); `egress-step` drains to `blocked`; exchange-lease
   reclaim reports success when the exchange is no longer open (not the raw
   `close-exchange` boolean); lineage/exchange policy cause `:policy`
   whenever the exchange is absent from the inventory; router co-location
   via `:co-located? true` declaration plus a JVM `ReflectionHandle` check;
   signals decode on `clojure.data.json` (declared dep, deps.edn line 8).
3. **Race and concurrency audit**: the `:awaiting` side table dance in
   `accept!`/`finish!` (terminal event racing the request deposit), the
   claim/phase transitions in `perform!`, the driver's `:unpublished`
   retry path, cursor advancement gated on `:consumed` in `read-messages!`.
4. **Test honesty**: does the test set actually establish what it claims;
   is any criterion clause covered by a test that would pass on a broken
   implementation; the loopback closed-socket test exercises boundary-side
   `close-exchange` per the architect's ruling — is that stated honestly?
5. **Known unrun checks to weigh, not rerun**: the host-boundary gate
   (`test/dao/host/gate_test.clj`) has not classified the new namespaces;
   the new `.cljc` files' CLJS/CLJD compilation is unproven (no cljs/cljd
   test requires them); no real client-disconnect test exists (architect
   ruling (a): the judge's `:silence` reclaim is the detector on http-kit;
   `dao.stream.http.md` and `dao.star.md` now carry the Honest Limit).

Deliverable: a findings report as your final response, starting with the
header fields, then a verdict line (SIGNED OFF / SIGNED OFF WITH CHANGES /
NOT SIGNED OFF), then findings as `severity | file:line | evidence |
recommended correction`, then the list of requested properties that passed.
Distinguish architectural defects from implementation gaps or intentionally
deferred work.
