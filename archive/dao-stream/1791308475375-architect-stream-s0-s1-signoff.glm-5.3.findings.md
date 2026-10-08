Completed-GMT: 2026-10-06 17:49:23 GMT
Completed-Local: 2026-10-07 00:49:23 Asia/Ho_Chi_Minh
Coding-Agent: glm
Session-ID: 99f86c83-6be4-4e2d-a7cc-aaf5d5739722

# Lead System Architect gate sign-off: stream-crossmachine S0 + S1

Read-only architectural gate pass on the uncommitted working-tree diff of
branch `stream-crossmachine-s0-s1` (three files: `docs/design/dao.stream.remote.md`,
`src/cljc/dao/stream/ws_project.cljc`, `test/dao/stream/ws_project_test.cljc`),
evaluated against the master architecture (`docs/design/datom.world.md`), the
architect mob consensus of 1791304310868 (D1–D5), and reviewer rounds r1
(`collab/1791306950355-...-review.glm-5.3.findings.md`, CHANGES REQUESTED:
A1, B1) and r2 (`collab/1791307753592-...-review-r2.glm-5.3.findings.md`,
both fixed, signed off). No code or design file was modified; no other
worktree was touched. Only this findings file was written.

Method: I read the full diff and the complete current `ws_project.cljc`
myself, re-verified every brief checkpoint at its file:line, confirmed the
A1/B1 fixes firsthand, and re-ran the focused suite (below). Where a claim
rests on a reviewer's run rather than mine, that is stated.

## Verdict

**CHANGES REQUESTED — narrowly. One blocking item: A2 (the step budget must
be wired into `accept-step!` as part of Slice S1, not deferred to S2).**
Everything else in S0 and S1 as now diffed is verified sound and formally
accepted below, with dispositions that close the open notes. The conversion
from DENIED to GRANTED is pre-scoped at the end of this file: it requires
only the A2 wiring plus its test and a green re-run — no further full review
round.

Verdict: CHANGES REQUESTED — narrowly (one blocking item)
Sign-off: DENIED — conversion to GRANTED pre-scoped in §5

## 1. Verified and accepted

### 1.1 Contract amendment (Slice S0, `dao.stream.remote.md` §3.0) — ACCEPT

§3.0 (`dao.stream.remote.md:375-408`) is correctly placed (directly under
`## 3. Channels`, before `### 3.1 WebSocket`), compact, and states the
boundary invariant in substance verbatim from D1/D2:

- **Boundary**: dao.stream is the sole abstraction boundary across machine
  boundaries; neither `yin.repl` nor `yin.vm.linker` knows or cares about
  TCP WebSocket vs UDP; transport swap preserves handle operations, outcome
  algebras, opaque cursors, source gap/recovery semantics and append
  semantics; a raw network drop is never invented as a source-retention gap.
  This restates the master doc's own law: agents communicate with the
  outside world through `dao.stream` and are specified entirely through
  stream effects (`datom.world.md:62`), and a host boundary is a stream
  boundary whose one sanctioned function-shaped contact is a handle
  operation (`datom.world.md:69-71`).
- **Cadence**: driver-owned — no ambient timers, daemon loops or background
  threads. This matches the codebase axiom that the adapter "appends one
  plain-data event and returns. It invokes nothing"
  (`datom.world.md:80-84`) and `ws.cljc`'s own refusal to invent wall-clock
  time or a scheduler.
- **Bounds**: the four bullets — finite admission cap with rejection after
  reaping, idle reaping with ms semantics and documented `:idle-timeout-ms`
  alias precedence (the B1 fix, `:395-399`), step event budget, session
  failure isolation — each match the implementation as verified in §1.2.
  No new registry, no new core operation, no public `closed?` predicate on
  handles: S0's D5 constraints hold.

One sentence is owed in §3.0's step-event-budget bullet when A2 lands (see
§2 scope): naming the acceptor's `:step-budget` composition key.

### 1.2 Implementation (Slice S1, `ws_project.cljc`) — ACCEPT except A2

- **Resource bounding — verified** (`adopt!`, `ws_project.cljc:283-332`):
  reaping runs first (`reap-sessions!` at `:304`), then a newcomer at the
  active cap has its offered handle closed (`:306-309`) with no ack append
  and no `make-media` call. Reap-then-reject is a live bound, not a
  shrinking one: `WsHandle.close!` sets the handle phase closed, and the
  second `endpoint-step` of the same tick runs the pending-release doseq
  (`ws.cljc:609-612`, "bounded admission is a live bound rather than a
  monotonically shrinking one"); the pre-accept close resolves client-side
  as `:ws/transport-error` (`ws.cljc:358-361`), the same answer a full
  slot pool gives. An idle session reaped at admission time has its ring
  closed, so a link waiting on it observes channel loss (2.4) rather than
  blocking.
- **Resource reclamation — verified**: idle sessions have handle and ring
  closed (`close-session-resources!`, `ws_project.cljc:237-243`, invoked
  from `reaped` `:264-265`); reaping runs outside `swap!` with the driver
  as sole writer (`reap-sessions!`, `:272-280`), so no side effect repeats
  under swap retry.
- **Event budget primitive — verified** (the A1 fix): the 2-arity `step!`
  validates at entry — any non-nil budget that is not a positive integer
  throws `ex-info` carrying `{:budget budget}` (`ws_project.cljc:106-109`);
  nil stays the documented unbounded mode. Budgeted stepping preserves the
  reading cursor and continuation state; ok reads and gaps both count
  (D3: "Count malformed reads/gaps too"); blocked and end consume no
  budget.
- **Error isolation — verified** (`accept-step!`, `ws_project.cljc:360-377`):
  each session's projection step and mirror step run inside a try/catch
  whose clause closes that session's handle and ring and marks it
  `:closed?`; the end-of-tick reap drops it and healthy sessions answer in
  the same tick (proven by `session-error-isolation`). The
  `#?(:clj Throwable :cljs :default :cljd Object)` catch follows the
  three-host idiom (`ws.cljc:122`); ring `close!` is idempotent, so the
  catch may close an already-closed ring safely.
- **Bounds validation — verified**: `make-acceptor` throws a composition
  error for non-nil `:max-sessions`/`:idle-timeout`(-ms) that are not
  positive integers (`ws_project.cljc:196-208`), matching the A1 idiom;
  the `:idle-timeout-ms` alias precedence is implemented (`:195`) and now
  documented in both §3.0 and the docstring (the B1 fix, `:186-190`).
- **Purity/concurrency — verified**: all side-effecting closes run outside
  `swap!`; the `swap!` fns in the session loop are pure; closed-projection
  sessions are dropped without closing their handle (`reaped`, `:259-263`)
  because the transport already ended them — pre-S1 behaviour preserved.

### 1.3 Tests (`ws_project_test.cljc`) — ACCEPT

Read in full; every brief checkpoint has a focused test: budget
exact-count/continuation/unbounded tail; invalid budgets 0/-1/1.5 with
`ex-data` equality and a reads-nothing proof; cap rejection (ack withheld
— only att-1 ever acked — offered handle closed, no media composed);
admission-time reap freeing the cap with handle **and** ring closed;
tick-driven reap with no offers honouring the `-ms` key; activity reset
exercising the `>=` boundary (alive at 40, reaped at 50); default
unbounded; invalid bounds as composition errors; isolation with the
healthy session answering in the same tick. The `acceptor-over` fixture's
optional `config` merge preserves the original helper's behaviour, and the
two-tick admission in `session-error-isolation` matches the
one-offer-per-slot-per-tick handoff protocol. One non-blocking gap is
dispositioned in §3 (C2).

### 1.4 Backward compatibility — ACCEPT

Both consumers compose `make-acceptor` with no new keys and pass `now`
(`yin/repl/serve.cljc:288,696`; `yin/vm/linker/head/ws.cljc:206,286`); the
1-arity `step!` is preserved (`dial-step!`, `ws_project.cljc:546`);
`sessions` only gains `:last-activity-ms`; `no-bounds-keeps-sessions-
unbounded` proves behaviour identical to pre-diff. D2's zero-breakage
rule — unchanged consumer source and stream contract — holds.

## 2. The A2 ruling — blocking

**Question** (brief item 3, from r1 A2): `accept-step!` calls the
unbounded 1-arity `(step! (:project session))` at `ws_project.cljc:363`.
The budget primitive exists, is validated and tested, but no composition
uses it. Waive into S2 with formal tracking, or block?

**Ruling: block. The wiring lands in S1.** Four reasons:

1. **The consensus letter assigns it to S1.** D5's S1 line is
   "admission/session/pending/idle bounds, rejection policy, **fair
   scheduling** and session failure isolation", and D3 assigns "fair
   aggregate scheduling" to the generic channel composition (ws-project).
   Nothing else in this diff delivers fair scheduling — one offer per slot
   per tick and reap-then-reject ordering are pre-existing — so the budget
   wiring is the only candidate reading of that letter item.
2. **The harm it prevents is real and is the slice's own subject.** A
   flood cannot grow memory without bound: every deposit medium carries a
   mandatory admission declaration (`:evict-oldest` + capacity,
   `ws.cljc:87-109`) and retention is the medium's policy
   (`datom.world.md:115-116`). What remains unbounded is **driver-tick
   time**. Deposits are off-driver by design — the host adapter "appends
   one plain-data event and returns. It invokes nothing"
   (`datom.world.md:80-84`; the chain `:message!` → `receive!` →
   `deliver!` → `emit!` → `deposit!`, `ws.cljc:147-158, 330-382`, never
   paced by `endpoint-step`) — so pacing can live only in the driver's
   step. A continuously producing peer keeps `step!`'s read-to-blocked
   loop spinning inside one `accept-step!` tick, deferring — in the limit
   indefinitely — every later session's step, idle reaping, and admission.
   The step budget is the only mechanism in the design that bounds
   composition tick time; that is precisely what D3 names "fair aggregate
   scheduling", and the required acceptance evidence "continuous producer
   cannot defeat step budget" is false today in the only shipped acceptor.
3. **The cost is small and the seam is S1's.** The wiring is ~10–15 lines:
   one optional config key, one validation clause, one call-site change,
   one test. It is zero-breakage by construction (nil default = exactly
   current behaviour). Waiving does not avoid the work — S2's budgeting of
   the nested loops would have to reopen this same composition surface —
   it only moves it across a gate it is assigned to.
4. **Gate discipline.** The mob consensus (three models, multiple rounds,
   formal reconciliation) is the governing law for this milestone. A
   cheap, unentangled, letter-mandated item should not become the first
   precedent that slice letters are informally deferrable. That the
   unbounded behaviour is pre-existing cuts no ice: S1's purpose is to
   bound exactly that pre-existing unboundedness — the same argument
   applied to `:max-sessions` and `:idle-timeout`, which landed.

**Exact blocking scope** (nothing beyond this is asked of S1):

- `make-acceptor` gains optional `:step-budget` (positive integer or nil),
  validated in the existing `when-not` block beside the other bounds,
  stored in the atom, documented in the docstring.
- `accept-step!` passes it at the per-session call:
  `(step! (:project session) step-budget)` (nil = unbounded, current
  behaviour).
- §3.0's step-event-budget bullet gains one sentence naming the acceptor's
  `:step-budget` key.
- One flood/no-starvation test: two sessions; the first session's traffic
  medium flooded (≥4 events); `:step-budget 2`; one tick asserts the first
  session's ring holds exactly 2 values **and** the second session's
  request is answered in the same tick. Invalid rows (`:step-budget 0`,
  `1.5`) join `invalid-bounds-are-a-composition-error`.

**Explicitly not required in S1** (tracked for S2 in §3): mirror-step
budgeting, dial-side (`dial-step!`) budgeting, aggregate shared-pool
scheduling across sessions, adoption-path failure isolation. S1's
per-session per-tick budget plus the existing visit-every-session tick is
the composition-level minimum D3 asks; the deeper loop budgets are S2's
"all nested remote/projection loops".

## 3. Dispositions of recorded notes and slice boundaries

These dispositions close r1's open notes and pin the D3-bullet ↔ D5-slice
mapping so later gates stop re-deriving it:

| D3 composition duty | Slice | Status |
|---|---|---|
| finite admission cap | S1 | done, verified (§1.2) |
| idle expiry | S1 | done, verified (§1.2) |
| pending expiry | — / S2 | vacuous at composition level: adoption is atomic adopt-or-reject, no persistent pending state between ticks; the ws layer's pre-adoption frame/count/byte bounds are S2 per D5 |
| fair aggregate scheduling | S1 min + S2 depth | S1 minimum = per-session `:step-budget` (§2, blocking); nested-loop/mirror/dial budgets and any shared-pool scheduling → S2 |
| per-session isolation | S1 / S2 | delivered-path isolation done in S1; adoption-path isolation (`make-media`/ack throws aborting the tick, r1 A5) → S2 with pending/retiring bounds |
| explicit stop | S3a | per D5 |
| lifecycle loss handling | pre-existing + S3a | 2.4 loss semantics retained and verified; S3a completes lifecycle-gap recovery |

- **A4 (flood defers idle reaping — r1 note): intended at S1, no change.**
  Traffic-counts-as-activity is the correct S1 semantics: a chattering peer
  is by definition active work for its session, its memory is bounded by
  the medium's admission declaration, and once §2 lands its per-tick work
  is bounded too. The consensus evidence item "unrelated-traffic flood
  cannot defer expiry" is written against liveness supervision (D3: fixed
  per-request deadlines, S3a), not against server idle expiry, which D3
  keeps separate precisely because a server may have no outstanding
  request.
- **A5 (adoption-path failures not isolated): S2**, with pending/retiring
  bounds, per the table.
- **A6 (`now` nil → `:last-activity-ms 0`): harmless.** Both callers
  always pass `now`; `session-idle?` never fires on nil-`now` ticks. Note
  stands, no action.
- **C2 (post-cap-rejection re-admission unasserted): optional, not
  blocking.** A one-line third offer would close it; A2's flood test is
  the required addition.
- **S3a input (recorded for the neutral board composition):** it must set
  non-nil `:step-budget`, `:max-sessions` and `:idle-timeout` so the S1
  mechanisms are actually exercised in production composition; defaults
  stay nil at the ws-project layer for zero breakage.
- **Landing process debt (repeated from r1/r2, and a condition of
  conversion):** kondo, the CLJS/CLJD lanes and the full three-lane run
  remain owed before commit — the full run is the gate before a commit
  (`docs/agents/build-n-test.md:47-49`), and neither reviewers nor
  implementer could run kondo through their permission gates
  (`docs/agents/roles/orchestrator.md:196-198,250`: no blind sign-off
  with unrun checks). §3.0 correctly states the target contract today;
  `yin.vm.linker.head.ws` and `yin.repl` still build `:ws` descriptors
  until S3a/S3b migrate them — disclosed and per plan.

## 4. Verification runs (this pass)

```
$ clojure -M:test -n dao.stream.ws-project-test
Ran 22 tests containing 80 assertions.
0 failures, 0 errors.
```

22/80 matches r2's run exactly (r1 was 21/76 before the A1 test). The
125/839 dependent-suite result is r2's and the implementer's attestation,
not re-run here. Full diff of all three files, the complete current
`ws_project.cljc`, both consumer call sites, the §3.0 region of
`dao.stream.remote.md`, the ws.cljc deposit/adapter/pending-release
regions, and the master doc's normative statements were read firsthand
this pass.

## 5. Conversion to SIGN-OFF (pre-scoped — no further full review)

This DENIED verdict converts to GRANTED when all of:

1. The §2 blocking scope lands exactly: `:step-budget` config key with
   validation and docstring, the call-site change at the per-session
   `step!`, the one-sentence §3.0 addition, and the flood/no-starvation
   test plus invalid-budget rows.
2. The focused suite and the dependent-suite run
   (`ws-project`, `ws-project-jvm`, `ws-project-cross-jvm`,
   `head-ws-test`, `serve/connect/wire/embed/dht-head/main`) are green.
3. A delta-only spot check confirms nothing else changed (r2 reviewer or
   architect; diff delta versus this reviewed state should be
   approximately +5 source, +2 design, +25 test lines).

Kondo, CLJS/CLJD lanes and the full three-lane run remain conditions of
landing/commit, not of this gate's conversion.

Verdict: CHANGES REQUESTED — narrowly (one blocking item)
Sign-off: DENIED — conversion to GRANTED pre-scoped in §5
