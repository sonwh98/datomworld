Completed-GMT: 2026-10-24 09:06:16 GMT
Completed-Local: 2026-10-24 16:06:16 +0700

I edited no files. The sources I read are `dao.star.md`, `dao.stream.http.md`, `dao.lease.md`, `lease.cljc` (the `make-judge`, `make-holder`, `initial-holder`, `observe-grant` and `renewal-interval` entry points), `ringbuffer.cljc`, `waitset.cljc`, `engine.cljc` (`check-wait-set`), `await.cljc` and `waitset/driver.cljs`. I did not read the stream.md, UCF or streams-all-the-way-down documents. I did not read `dao.stream.md` (envelopes, deposit admission, OD-4). The OD-4 point in ruling 3 comes only from `dao.star.md`'s own text. I also did not read the continuation or UCF documents or the `ucf/rows` source. The rulings below rest on `await.cljc` and `engine.cljc`, which I did read.

The worktree has no HTTP boundary code yet, so Phase B's contract is the `dao.stream.http.md` text: `open-exchanges`, `close-exchange`, `egress-step` and the ingress/egress media.

## 1. Namespace layout

**Ruling:** four small namespaces under `src/cljc/dao/star/`, with no `dao.star` umbrella that holds logic.

| Namespace | Owns |
|---|---|
| `dao.star.facts` | The three-fact grammar as pure functions: `root`, `consumption`, `ended`, `valid-fact?`, and `index-step` (fold one fact or lapse into the occurrence → lineage → current index). |
| `dao.star.signals` | Ruling 7. |
| `dao.star.router` | Ruling 4: a pure `classify` plus a stepped `router-step`. |
| `dao.star.driver` | Rulings 2, 5 and 6: per-session parked state, the step, lease holder state and subscription state. |

A small `dao.star.lease` for the two judge/holder compositions is optional. It is worth it only if the exchange-judge wiring would otherwise bloat `dao.star.driver`. I'd start inside `dao.star.driver` and split if the file passes about 400 lines.

**Reasons:**
- This matches the repo's granularity: one concern per namespace, as in `dao.stream.ringbuffer`, `.waitset` and `.ws-project`.
- `facts` and `signals` are pure and portable `.cljc`. They stay testable without streams and reusable when Mode 2 changes the driver.
- The Datastar vocabulary is not in any of them (see ruling 6).

**Rejected:**
- A single `dao.star` namespace. It would mix pure grammar with stepped interpreters.
- A `dao.star.facts` that owns the stream. The facts medium belongs to the composition (ruling 3).

## 2. The parked continuation

**Ruling:** option (a). A real `yin.vm` CESK continuation parked on a stream wait is buildable on the JVM today and belongs in Phase B. It needs no UCF v1, because Mode 1 never migrates the continuation.

**The integration point:** `dao.await/run` and `dao.await/resume` (`src/cljc/dao/await.cljc`).
- `run` builds a fresh ast-walker VM, attaches env stream handles via `engine/attach-resource`, evaluates, and returns `{:vm :blocked? :value :proc}`.
- `resume` calls `vm/eval vm nil`. The engine's `check-wait-set` then runs `dao.stream.waitset/check` over the parked `:next` entries and re-queues the woken ones.
- The driver's "poll" is therefore `await/resume`, which is the `waitset/check` sweep. The cljs `waitset/driver.cljs` is only a timer shape, and the JVM driver is a plain loop.

**What the driver holds and steps, per session:**
- The `:dao.await/result` map, which is the parked record.
- Two ring-buffer handles. The input medium carries routed inputs `{:exchange x :signals {...}}`. The view medium carries the model the program emits.
- The driver's cursors, the lease holder state and the open-subscription set.

**One step, in this order:**
1. The driver reads its routed-input cursor.
2. On a `value` for a current-token input, it appends the input to the VM's input medium.
3. It calls `await/resume` on the parked result.
4. It drains the VM's view medium, which holds the new model.
5. It mints the successor occurrence O2.
6. It appends the consumption fact with `:dao.stream/ok`.
7. Only after that `ok` does it project, and it appends `:http/head`, one `:http/chunk` per SSE event and `:http/end` (or only a chunk, for a subscription exchange) to egress.

**Why the program emits a model, not egress effects:** the spec requires the fact to be accepted before any UI carrying O2 is projected. If the program appended to egress itself, it would bypass that ordering. The view medium is the program's only output.

**The honest reading of "A yin.vm agent":**
- A real VM continuation is parked and then resumed by the driver observing a stream value. The mechanism is the existing `check-wait-set` path.
- The driver, not the VM program, performs the HTTP effects and the fact appends. This keeps ordering and the lease in one place.
- This reading should be stated in the Phase B brief and in the criterion's test. The driver is the only part that does I/O, and the program is pure sequential logic over `<!` and `>!`.

**Rejected:**
- (b), a bespoke step function over parked state. It would not exercise the CESK parking surface at all, and the criterion names a `yin.vm` agent.
- The program appending to egress directly. It breaks fact-before-UI ordering.
- Waiting for UCF v1. Mode 1 continuations are one-shot and never move.
- Extracting local variables from the CESK frames for projection. That is not a stable surface; the emitted model is.

**Caveat for the brief:** the parked VM holds a host-local stream handle for its input. That is fine in one process. Mode 2 is where it stops being portable.

## 3. Session-facts medium

**Ruling:** a `dao.stream.ringbuffer` created by the composition, evict-oldest, with a capacity sized above the router's and the lineage judge's lag. The constructor call is `ringbuffer/create!` with `:dao.stream/type :dao.stream/ringbuffer` and `:dao.stream.ringbuffer/capacity n`.

**Enforcement of the grammar** belongs to the writer, not the medium:
- The driver builds facts only through `dao.star.facts/root|consumption|ended`.
- The router and the lineage-lease resolver treat a read fact that fails `valid-fact?` as noise: they ignore it and never throw on it.

**Rejected:**
- A journal, which is durable. Compaction waits on OD-4 and is deferred in the spec.
- A guard middleware on the medium that rejects malformed facts. The medium stays payload-agnostic, per the "dao.jing is syntax, agents are semantics" stance.

## 4. Router

**Ruling:** a stepped `router-step`, threading `{ingress-cursor facts-cursor lease-cursor index}`.

**One pass, in the spec's order:**
1. Read ingress with its own cursor until `blocked`, collecting events, including a `gap` marker if one appears.
2. Drain the session-facts cursor to `blocked`, folding each fact into the index via `facts/index-step`.
3. Drain the lineage-lease cursor to `blocked`, folding `:lapsed` records into the index. A lapsed lineage is ended for routing.
4. Classify what step 1 read.

**Classification (`dao.star.router/classify`, pure, over index plus request):**
- `/` is begin.
- `/s/<t>` is subscribe if the token resolves to a live lineage, else begin.
- `/k/<t>` is input if `t` is the lineage's current occurrence, stale if `t` is consumed, and begin if the lineage is ended, lapsed or unknown.

**Unknown is judged only after both drains.**

**Co-location constraint:** an assembly-time assertion in the router constructor. The facts and lease handles must be direct, non-reflection handles, tested with `(not (satisfies? ... reflection))` or an explicit `:co-located? true` declaration at assembly. It throws ex-info with `:refused`, as the lease constructors do.

**Forwarding:**
- A request goes to the session medium, or to the begin handler's medium for `begin`.
- A terminal event (`:http/ended` or `:http/closed`) goes to whichever session holds that exchange. The router keeps `exchange → session` as interpreter state, built only from what it routed.
- On an ingress `gap` it forwards nothing. The lost window is unrouted, and those exchanges lapse by `:silence`.
- A forward whose `append!` is not `ok` is the same loss and is dropped silently.
- A terminal event for an unrouted exchange is ignored.

**Rejected:** a router that keeps a token table independent of the facts. The spec forbids it.

## 5. Lease compositions

**Exchange lease (inside the boundary peer):**
- **Judge:** `lease/make-judge` with `:reclaim` = `(fn [subject] (http/close-exchange (:http/exchange subject)))`, which must be idempotent and report success.
- **Writer:** the lease medium.
- **Media:** the ingress cursor, so the judge sees `:http/request`.
- **Ticks:** a dedicated tick stream for the judge.
- **Required config:** an explicit `:tolerance` (`{:ms 0}` for the zero), a `:cadence` and a `:resolver`.
- **Inventory:** `open-exchanges` feeds gap reconciliation, as `dao.star.md`, Liveness, specifies.
- **Grants:** a stepped wrapper authors an unsolicited grant per `:http/request` through `lease/author-grant`. Subject is `{:http/exchange x}`, holder is the egress identity, and the duration is something like `{:s 30}`.
- **Reclaim:** closing the exchange makes the adapter deposit `:http/closed`, and then the judge records `:lapsed`.

**Exchange holder (driver side):** `lease/make-holder` with `:self` = the egress identity and `:renewal-interval` from `lease/renewal-interval` (strictly under half the duration). It observes its grant, renews through `due-to-renew?` and `observe-renewal`, and stops on a `:lapsed` record. A one-shot responder renews nothing.

**Lineage lease:**
- **Grantor:** the peer holding the parked records, so the lineage judge is the driver's own judge, on the same peer as the router and the drivers.
- **Judge:** `lease/make-judge` over the session-facts and lease media. `:resolver-bindings` must cover envelope-key attribution (`:dao.star/lineage`). The reclaim procedure drops the parked record: the `await` result and the session's media handles.
- **Grant:** unsolicited at lineage begin, subject and holder `{:dao.star/lineage S}`, duration being the idle allowance.
- **Holder per lineage:** `make-holder` with `:subject {:dao.star/lineage S}`. It threads this state:
  - `observe-grant` before the first consume or project;
  - `due-to-renew?` on each consumed input and on a tick while a subscription is open;
  - `at-bound?` to stop at the cap, after which it consumes and projects nothing, and an arriving input is handed to the root program as begin;
  - `release` on halt.
- **Where the state threads:** the driver's per-session map holds the holder state. One holder state per lease, because the holder discipline keeps one state per lease, and a lineage is one lease.

**Rejected:** a shared holder for several lineages.

## 6. The projection seam

**Ruling:** `(project-fn model) → string`, a pure function the composition supplies to the driver. The returned string is the complete SSE wire text, such as `event: datastar-patch-elements\ndata: …\n\n`.

**Where Datastar lives:** inside `project-fn` and any helper the composition builds. `dao.star` has no `datastar` namespace. It ships one reference projection for the tests, in a test or example namespace, not in `src/`.

**What the model contains:**
- It is the program's emitted model.
- The occurrence id must reach the projection, since the spec says every projected UI carries the id of the occurrence that rendered it, so the seam is `(project-fn occurrence-id model)`.
- The root begin page, which is a full HTML document, is a separate supplied `begin-fn`. It is not an SSE patch.

**Re-projection (subscribe and stale):** the driver keeps the latest `{:occurrence O :model m}` as part of its parked record and calls the same `project-fn` on it without calling `resume`. This is why the model is stored: re-projection never touches the continuation.

**Rejected:** a `project-fn` that reads the VM state. It could not be used for stale and subscribe without stepping.

## 7. Signals decode

**Ruling:** `dao.star.signals/decode` is a pure function from an `:http/request` event to a signals map.
- GET and DELETE read the `datastar` query parameter, URL-decoded and JSON-parsed.
- Other methods parse the JSON body.
- A missing, malformed or non-map input gives `{}`. Decode never throws, because signals are the client's unverified claim.

**Where it runs:** in the driver. The router forwards the raw request and decodes nothing, which keeps the ingress tuple unchanged and the signals non-persistent.

**JSON:** use the parser the repo already uses on the JVM, and check which one the boundary needs before the brief names it. I did not check.

## 8. Test plan

All tests run on the JVM against the real boundary from Phase A. Items marked **L** need real loopback sockets, and the rest run on in-memory ring buffers and a fake boundary that implements `open-exchanges`, `close-exchange` and the egress/ingress media.

| Clause | Test | Medium |
|---|---|---|
| Agent receives input, answers head/chunks/end, parks, resumes on next input | A VM program that loops `<!`/`>!`. Post twice and assert the second response reflects the first. | **L** for one end-to-end test; the rest in-memory |
| Request with no token begins a lineage | A `/` request produces a root fact and a complete HTML document. | in-memory |
| Subscribe consumes nothing; a re-subscribe after several inputs gets the current projection | Three inputs, a subscribe, and a check that no consumption fact was added. | in-memory |
| Stale token is re-projected | Post the same token twice. The second runs nothing and gets the current projection plus `:http/end`. | in-memory |
| Closed socket observed as `:http/closed` | Client disconnects mid-subscription. | **L** |
| Silent exchange lapses and closes | Ticks past the duration, then assert `close-exchange` and `:lapsed`. | in-memory (fake clock via tick stream) |
| Unreturned lineage lapses, record retired, facts remain | Tick past the idle allowance, assert the parked record is gone and the facts are still readable. | in-memory |
| Request naming a just-published token is never answered as begin | A tight loop of post, read UI id, post immediately. | in-memory, with a stress variant |
| After an ingress gap, no exchange unleased and no closed exchange leased | Overflow a small ingress ring, then reconcile. | in-memory |
| Driver stops renewing after observing the lapse or its own gap | Assert no renewal appended after each. | in-memory |
| Renewal in flight counts while live; one observed after retirement does not revive | Interleave renewal and `:lapsed` on the lease medium. | in-memory |

**`^:slow` candidates:** the stress variant of the just-published-token test, and the loopback test if it needs real time. Everything else belongs in the quick tier.

**Coverage:** this plan covers all ten clauses fully on the JVM if the Phase A boundary is accurate. Two things it defers:
- Cljs and cljd hosts are untested.
- A real long-lived SSE subscription with a browser is not tested. A raw socket client stands in for Datastar.

## 9. File ownership and risks

**Ownership for the Phase B implementer:**
- Creates `src/cljc/dao/star/{facts,signals,router,driver}.cljc` and the matching tests under `test/dao/star/`.
- Does not touch `dao.lease`, `dao.stream.*`, `dao.await`, `yin.vm.*` or the Phase A boundary files. Changes there go back to the Architect.
- Does not touch the worktree `dao-star-m1`'s Phase A files while that delegate is running. Phase B should start only after Phase A lands.

**Risks, in order of likelihood:**
1. **Phase A API drift.** I could not read the real `open-exchanges` and `close-exchange` signatures. The brief must pin them down once Phase A lands.
2. **`await/resume` re-evaluates the whole VM.** Each call runs the sweep over every parked entry in that VM. This is fine per session, but the brief should say one VM per session, not one VM for all sessions.
3. **Fact-before-UI ordering.** An implementer may project before the fact's `ok`. The brief must say the driver blocks on the append outcome and test it.
4. **Router classification races.** Reading ingress after the drains instead of before breaks the proof. The brief should pin the order and the test that checks it.
5. **Lease sizing.** Duration must exceed twice the renewal interval plus the tick period. Use `lease/renewal-interval` with a tick period rather than hand-picked numbers.
6. **Reclaim idempotence.** `close-exchange` must be idempotent and must report success. A reclaim that throws on an already-closed exchange stalls the judge.
7. **Resolver attribution.** The lineage lease's resolver reads the `:dao.star/lineage` envelope key. A fact's own claim about its author must never be consulted, which `observe-grant` already enforces.
8. **Scope creep into Mode 2.** The brief should state that no UCF, custody or export code is in Phase B.

**Open for the owner:** the "A yin.vm agent" reading in ruling 2 (driver does I/O, program emits a model). I recommend it.

**Test coverage summary:** I cover all ten semicolon-separated scenarios of Completion Criteria item 1 on the JVM. Criterion item 2 (Fat Client) is deferred and conditional on UCF v1.
