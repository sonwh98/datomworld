Created-GMT: 2026-09-06 15:38:05 GMT
Created-Local: 2026-09-06 22:38:05 +07 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f
# Task: dao.jing.v2 migration plan
Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-06 22:38:05 +07 | Status: active | Rationale: Architect primary per team.md; this is a migration-strategy and boundary decision, not an implementation

Repository root: /Users/sto/workspace/datomworld (branch `dao.stream-redesign-v2`).

Produce a **draft implementation plan** migrating `dao.jing` off v1 `dao.stream`
onto `dao.stream`. This is design work, not implementation. Do not edit any
file: emit the complete plan document as the body of your final response. The
orchestrator promotes it to `docs/design/dao.jing.v2.implementation-plan.md`
after independent review.

Read first:
- docs/design/datom.world.md
- docs/design/dao.jing.md (especially: The intake pool, Storage ignorance,
  Cursor tracking and recovery, Implemented surface, Open items)
- docs/design/dao.stream.md (the v2 contract — superior to everything below)
- docs/design/dao.stream.implementation-plan.md (especially: "What v1
  callers lose, and what replaces it", "Boundary of this plan", "End condition")
- docs/design/dao.runtime.implementation-plan.md and
  docs/design/yin.repl.implementation-plan.md — **shape precedents only**:
  match their structure, altitude, and the discipline of naming what is
  deferred and why. Do not copy their content.
- src/cljc/dao/jing.cljc, src/cljc/dao/jing/file.cljc,
  src/cljc/dao/jing/remote.cljc, src/cljc/dao/jing/mem.cljc
- src/cljc/dao/stream.cljc, src/cljc/dao/stream/ringbuffer.cljc,
  src/cljc/dao/stream/rpc.cljc, src/cljc/dao/stream/rpc/ws.cljc
- test/dao/stream/conformance.cljc, test/dao/jing_test.cljc,
  test/dao/jing/file_test.cljc

## State already established — do not re-derive

The dao.stream slice (Phases 1-5) is implemented on clj, cljs (Node) and
cljd, including the cljd ws transport. Four consumers have migrated under their
own plans: `yin.vm`, `dao.runtime`, `dao.await`, `yin.repl`.
`dao.jing` is next because `dao.space.{index,schema}` require it, and
`dao.space` on the v2 contract is what ends ADR-0003's time-boxed exception.
The full local suite was reported green by the user on this revision; you are
not asked to run or re-verify tests.

`dao.jing`'s v1 surface is small and contains **no** `closed?`, `strict-vec`,
`drain-one!` or `take!!` call. It is these sites only:
- src/cljc/dao/jing.cljc:391 — `ds/next` in the observer pool walk
- src/cljc/dao/jing/file.cljc:83,149,175,190,207 — `ds/next`, `ds/append!`,
  `ds/close!`, and `ds/open!` of `{:dao.stream/type :append-log}`

## The four questions the plan must settle

These are the reason an Architect is drafting this rather than an implementer
doing a mechanical port. Answer each explicitly; do not leave one as a phase
that "will decide".

1. **The observer (`dao.jing/observe-step!`, jing.cljc:391).** v2 cursors are
   minted by the stream and opaque; `dao.jing.md` documents pool entries as
   `{:stream <ref>, :cursor {:position n}, :status s}` and describes v1's bare
   `:ok`/`:blocked`/`:end`/`:daostream/gap` returns. Specify the v2 entry
   shape, the cursor discipline (retain exactly the successor `next` returns;
   never arithmetic), and how `gap` resynchronization works when the cursor is
   opaque. Say what changes in `dao.jing.md` prose and what stays.

2. **`dao.jing.file` and the missing append-log (the hard one).** The file
   content store is backed by the v1 `:append-log` transport via `ds/open!` +
   `dao.stream.log`. The stream v2 plan explicitly defers "Other transports
   (file, UDP, log, relation, apply/RPC layers)" — **there is no v2
   append-log.** Decide between, and justify against `dao.jing.md` §Storage
   ignorance and the principle that the well is passive and
   payload-agnostic:
   (a) this plan owns a v2 append-log transport phase, which then owes the
       Phase 1 conformance suite; or
   (b) the file backend's durable log is a storage implementation detail that
       should never have been a `dao.stream` at all, and the migration is a
       removal of that coupling rather than a port; or
   (c) something you argue for instead.
   Whichever you choose, name what it costs and what it defers.

3. **`dao.jing.remote`.** It wraps v1 `dao.stream.rpc.client` / `rpc.ws` and
   its JVM-only `connect-content!` is a *synchronous* constructor.
   `dao.stream.rpc` is poll-shaped and state-machine-valued
   (`client-state`, `serve-once!`, explicit completions), built for the REPL.
   Reconcile a synchronous content `get`/`put` against a polling RPC without
   reintroducing blocking (`take!!` is gone by construction, and blocking is
   unimplementable on cljs/cljd). If the honest answer is that remote content
   needs a driver its caller owns, say so and specify it.

4. **Phase boundaries and the end condition.** Give ordered phases, each with
   its own completion criteria and host matrix (clj, cljs Node, cljd — note
   the `#?(:cljd nil :clj ...)` reader-conditional trap, `:cljd` first). State
   the end condition, whether `dao.jing.v2` is a parallel namespace or an
   in-place migration (justify against how the four prior consumers did it),
   and what the naming decision is at the end.

Also state explicitly what is **out of scope**: `dao.jing.dht` /
`dao.jing.dht.node` (UDP, deferred by the stream plan), `dao.space`'s own
migration, canonical encoding, durable observer checkpoints, and anything else
you judge to belong to a different plan. Distinguish architectural decisions
from implementation gaps from intentionally deferred work.

Constraints: enforce the six non-negotiable invariants. No hidden global state
— note that v1's `defopen`/`open!` registry is gone in v2 and jing must not
reinvent it; `dao.jing.coordinate/open!` is already an explicit closed
dispatch and is the precedent. Do not broaden scope beyond `dao.jing*`. Do not
edit files. Produce the complete deliverable in this run without waiting for
approval; a plan that promises a plan is an unfinished turn.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f

Then the plan document, followed by a short list of the decisions you made that
you consider most likely to be contested in review.
